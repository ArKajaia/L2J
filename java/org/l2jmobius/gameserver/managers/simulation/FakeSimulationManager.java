/*
 * This file is part of the L2J Mobius project.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>.
 */
package org.l2jmobius.gameserver.managers.simulation;

import java.awt.Color;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.l2jmobius.commons.threads.ThreadPool;
import org.l2jmobius.gameserver.ai.Intention;
import org.l2jmobius.gameserver.config.custom.FakeSimulationConfig;
import org.l2jmobius.gameserver.data.enums.CategoryType;
import org.l2jmobius.gameserver.geoengine.GeoEngine;
import org.l2jmobius.gameserver.managers.CombatLogManager;
import org.l2jmobius.gameserver.managers.FakePartyManager;
import org.l2jmobius.gameserver.managers.FakePlayerPvpManager;
import org.l2jmobius.gameserver.managers.GlobalVariablesManager;
import org.l2jmobius.gameserver.model.Location;
import org.l2jmobius.gameserver.model.World;
import org.l2jmobius.gameserver.model.WorldObject;
import org.l2jmobius.gameserver.model.WorldRegion;
import org.l2jmobius.gameserver.model.actor.Creature;
import org.l2jmobius.gameserver.model.actor.Npc;
import org.l2jmobius.gameserver.model.actor.Player;
import org.l2jmobius.gameserver.model.actor.holders.npc.FakePlayerParty;
import org.l2jmobius.gameserver.model.actor.holders.npc.FakePlayerPvpBuild;
import org.l2jmobius.gameserver.model.actor.holders.npc.FakePlayerPvpBuild.SkillCategory;
import org.l2jmobius.gameserver.model.actor.holders.npc.FakePlayerPvpProfile;
import org.l2jmobius.gameserver.model.actor.instance.Monster;
import org.l2jmobius.gameserver.model.effects.EffectFlag;
import org.l2jmobius.gameserver.model.item.type.WeaponType;
import org.l2jmobius.gameserver.model.item.Weapon;
import org.l2jmobius.gameserver.model.skill.BuffInfo;
import org.l2jmobius.gameserver.model.skill.Skill;
import org.l2jmobius.gameserver.network.serverpackets.ExServerPrimitive;
import org.l2jmobius.gameserver.network.serverpackets.NpcHtmlMessage;
import org.l2jmobius.gameserver.taskmanagers.DecayTaskManager;

/**
 * Party-vs-party simulations of fake players, a testing and debugging tool for their AI (see docs/fake-player-ai-plan.md, section 10).
 * <ul>
 * <li>An admin saves spawn points ({@link #setPoint}), kept in the global variables.</li>
 * <li>{@link #simulate} spawns one balanced party at every point (see {@link SimulationComposer}): every team gets the same slots, and the same seed gives the same teams. They get ready, march toward the nearest other team and fight until one team is left or the
 * time limit; then the bodies are removed and the next round (if any) starts.</li>
 * <li>An admin can join a team in place of one of its fake players ({@link #joinParty}).</li>
 * <li>Every round writes its log folder (see {@link SimulationLog}): run.json, events, AI decisions ({@link FakeAiTrace}), positions and anomalies ({@link SimulationMonitor}), and a summary shown to the admin.</li>
 * </ul>
 * The combat code calls the hooks ({@link #onDamage}, {@link #onDeath}...) only while {@link #ACTIVE} is set, so nothing costs anything outside a run.
 */
public class FakeSimulationManager
{
	private static final Logger LOGGER = Logger.getLogger(FakeSimulationManager.class.getName());

	/** {@code true} while a round runs: the combat hooks look at nothing otherwise. */
	public static volatile boolean ACTIVE = false;

	private static final String POINT_VARIABLE = "SIM_POINT_";
	private static final DateTimeFormatter FOLDER_TIME = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");
	private static final int HTML_LIMIT = 15000;
	private static final int DRAW_INTERVAL = 500;
	/** The fake players of a round leave this long after they stopped and dropped their transformation, so the players around see them as themselves first. */
	private static final long REMOVE_DELAY = 1000;
	/** A fake player engages an enemy this close even without a line of sight (around a corner). */
	private static final int CLOSE_ENEMY_RANGE = 400;

	private static final AtomicInteger RUN_IDS = new AtomicInteger();

	private final Map<Integer, SimulationPoint> _points = new TreeMap<>();
	private volatile SimulationRun _run;
	private volatile Series _series;
	/** Admins in (or waiting to join) a team, by object id. */
	private final Map<Integer, Guest> _guests = new ConcurrentHashMap<>();
	/** Regions this run keeps awake (they were not before). */
	private final Set<WorldRegion> _keptRegions = ConcurrentHashMap.newKeySet();
	/** //SimulationDraw: admin object id -> fake player object id. */
	private final Map<Integer, Integer> _draws = new ConcurrentHashMap<>();
	private ScheduledFuture<?> _drawTask;

	/**
	 * The rounds of one //Simulate.
	 */
	private static class Series
	{
		final SimulationSettings settings;
		final List<SimulationPoint> points;
		final String name;
		final Path dir;
		volatile boolean stopped;
		final List<JsonLine> results = new ArrayList<>();
		final Map<Integer, Integer> teamWins = new TreeMap<>();
		final Map<String, int[]> buildStats = new TreeMap<>();
		final Map<String, int[]> roleStats = new TreeMap<>();
		final Map<String, Integer> anomalies = new TreeMap<>();

		Series(SimulationSettings settings, List<SimulationPoint> points, String name, Path dir)
		{
			this.settings = settings;
			this.points = points;
			this.name = name;
			this.dir = dir;
		}
	}

	/**
	 * An admin that joined a team, or waits for //Simulate to spawn it.
	 */
	private static class Guest
	{
		final Player admin;
		final int teamId;
		final String role;
		final boolean leads;
		volatile boolean joined;
		boolean wasInvul;
		boolean wasInvisible;
		String replacedBuild;
		SimRole replacedRole;
		long replacedSeed;

		Guest(Player admin, int teamId, String role, boolean leads)
		{
			this.admin = admin;
			this.teamId = teamId;
			this.role = role;
			this.leads = leads;
		}
	}

	protected FakeSimulationManager()
	{
		loadPoints();
	}

	// ---------------------------------------------------------------------------------------------
	// Points
	// ---------------------------------------------------------------------------------------------

	private void loadPoints()
	{
		try
		{
			for (String key : new ArrayList<>(GlobalVariablesManager.getInstance().getSet().keySet()))
			{
				if (!key.startsWith(POINT_VARIABLE))
				{
					continue;
				}

				final int id = Integer.parseInt(key.substring(POINT_VARIABLE.length()));
				final SimulationPoint point = SimulationPoint.decode(id, GlobalVariablesManager.getInstance().getString(key, ""));
				if (point != null)
				{
					_points.put(id, point);
				}
			}
		}
		catch (Exception e)
		{
			LOGGER.log(Level.WARNING, getClass().getSimpleName() + ": Could not load the simulation points.", e);
		}
	}

	/**
	 * Saves the position of {@code admin} as point {@code id}.
	 * @param admin the admin
	 * @param id the point number (1-99)
	 * @return what to tell the admin
	 */
	public synchronized String setPoint(Player admin, int id)
	{
		if ((id < 1) || (id > 99))
		{
			return "The point number goes from 1 to 99.";
		}

		final SimulationPoint point = new SimulationPoint(id, admin.getX(), admin.getY(), admin.getZ(), admin.getHeading(), admin.getInstanceId());
		final boolean moved = _points.put(id, point) != null;
		GlobalVariablesManager.getInstance().set(POINT_VARIABLE + id, point.encode());
		GlobalVariablesManager.getInstance().storeMe();
		drawPoints(admin);
		return "Simulation point " + id + (moved ? " moved to " : " set at ") + point.x() + ", " + point.y() + ", " + point.z() + (point.instanceId() != 0 ? " (instance " + point.instanceId() + ")" : "") + ". " + _points.size() + " point(s).";
	}

	/**
	 * @param arg a point number, or "all"
	 * @return what to tell the admin
	 */
	public synchronized String clearPoint(String arg)
	{
		final List<Integer> ids = new ArrayList<>();
		if (arg.equalsIgnoreCase("all"))
		{
			ids.addAll(_points.keySet());
		}
		else
		{
			try
			{
				ids.add(Integer.parseInt(arg));
			}
			catch (NumberFormatException e)
			{
				return "Usage: //ClearSimulationPoint <number|all>";
			}
		}

		int removed = 0;
		for (int id : ids)
		{
			if (_points.remove(id) != null)
			{
				GlobalVariablesManager.getInstance().remove(POINT_VARIABLE + id);
				removed++;
			}
		}
		GlobalVariablesManager.getInstance().storeMe();
		return "Removed " + removed + " simulation point(s), " + _points.size() + " left.";
	}

	/**
	 * Lists the points with the distances between them, and draws them for {@code admin}.
	 * @param admin the admin
	 * @return the lines to tell the admin
	 */
	public synchronized List<String> listPoints(Player admin)
	{
		final List<String> lines = new ArrayList<>();
		if (_points.isEmpty())
		{
			lines.add("No simulation points. Stand somewhere and use //SetSimulationPoint 1, //SetSimulationPoint 2...");
			return lines;
		}

		final List<SimulationPoint> points = new ArrayList<>(_points.values());
		for (SimulationPoint point : points)
		{
			final StringBuilder sb = new StringBuilder("Point " + point.id() + ": " + point.x() + ", " + point.y() + ", " + point.z() + (point.instanceId() != 0 ? " instance " + point.instanceId() : ""));
			for (SimulationPoint other : points)
			{
				if (other.id() > point.id())
				{
					sb.append(" | to ").append(other.id()).append(": ").append((int) point.distance2D(other)).append(canSee(point, other) ? "" : " (no line of sight)");
				}
			}
			lines.add(sb.toString());
		}
		drawPoints(admin);
		lines.add("Drawn in green where two points see each other, red where they don't.");
		return lines;
	}

	private static boolean canSee(SimulationPoint point, SimulationPoint other)
	{
		return GeoEngine.getInstance().canSeeTarget(point.x(), point.y(), point.z() + 40, other.x(), other.y(), other.z() + 40, point.instanceId());
	}

	private void drawPoints(Player admin)
	{
		final ExServerPrimitive packet = new ExServerPrimitive("SimulationPoints", admin.getX(), admin.getY(), admin.getZ());
		final List<SimulationPoint> points = new ArrayList<>(_points.values());
		for (SimulationPoint point : points)
		{
			packet.addPoint("Point " + point.id(), Color.YELLOW, true, point.x(), point.y(), point.z() + 20);
			addCircle(packet, Color.YELLOW, point.x(), point.y(), point.z() + 20, FakeSimulationConfig.SPAWN_RADIUS + 40);
			for (SimulationPoint other : points)
			{
				if (other.id() > point.id())
				{
					packet.addLine(canSee(point, other) ? Color.GREEN : Color.RED, point.x(), point.y(), point.z() + 20, other.x(), other.y(), other.z() + 20);
				}
			}
		}
		if (points.isEmpty())
		{
			packet.addPoint(Color.YELLOW, 0, 0, Short.MIN_VALUE);
		}
		admin.sendPacket(packet);
	}

	private static void addCircle(ExServerPrimitive packet, Color color, int x, int y, int z, int radius)
	{
		final int segments = 24;
		for (int i = 0; i < segments; i++)
		{
			final double a1 = (2 * Math.PI * i) / segments;
			final double a2 = (2 * Math.PI * (i + 1)) / segments;
			packet.addLine(color, x + (int) (Math.cos(a1) * radius), y + (int) (Math.sin(a1) * radius), z, x + (int) (Math.cos(a2) * radius), y + (int) (Math.sin(a2) * radius), z);
		}
	}

	// ---------------------------------------------------------------------------------------------
	// Runs
	// ---------------------------------------------------------------------------------------------

	/**
	 * Starts a simulation: one party of {@code size} at every point.
	 * @param admin the admin
	 * @param size the party size (1-9)
	 * @param level the level of the fake players
	 * @param mirror {@code true} for the same builds in every team
	 * @param seed the seed of the teams
	 * @param rounds how many rounds
	 * @param timeLimit the time limit of a round, in seconds
	 * @return the lines to tell the admin
	 */
	public synchronized List<String> simulate(Player admin, int size, int level, boolean mirror, long seed, int rounds, int timeLimit)
	{
		final List<String> lines = new ArrayList<>();
		if ((_series != null) || (_run != null))
		{
			lines.add("A simulation is already running. //SimulationStop ends it.");
			return lines;
		}
		if (_points.size() < 2)
		{
			lines.add("At least 2 simulation points are needed (//SetSimulationPoint 1, //SetSimulationPoint 2...).");
			return lines;
		}
		if ((size < 1) || (size > SimulationComposer.MAX_SIZE))
		{
			lines.add("The party size goes from 1 to " + SimulationComposer.MAX_SIZE + ".");
			return lines;
		}
		if (!FakePlayerPvpManager.getInstance().isEnabled())
		{
			lines.add("Roaming fake players are disabled (FakePvpEnabled in FakePlayerPvp.ini).");
			return lines;
		}

		// Monsters and players around the points join in: better an empty place.
		final List<SimulationPoint> points = new ArrayList<>(_points.values());
		for (SimulationPoint point : points)
		{
			final int[] crowd = new int[2];
			final Location location = new Location(point.x(), point.y(), point.z());
			World.getInstance().forEachVisibleObjectInRange(admin, Creature.class, Integer.MAX_VALUE, creature ->
			{
				if ((creature.getInstanceId() != point.instanceId()) || creature.isDead() || (creature.calculateDistance2D(location) > FakeSimulationConfig.CROWD_WARNING_RANGE))
				{
					return;
				}
				if (creature.isPlayer() && (creature != admin))
				{
					crowd[0]++;
				}
				else if ((creature instanceof Monster) && !creature.isFakePlayer())
				{
					crowd[1]++;
				}
			});
			if ((crowd[0] > 0) || (crowd[1] > 0))
			{
				lines.add("Warning: point " + point.id() + " has " + crowd[1] + " monster(s) and " + crowd[0] + " other player(s) within " + FakeSimulationConfig.CROWD_WARNING_RANGE + " (seen from where you stand). Monsters may attack the teams.");
			}
		}

		final String name = LocalDateTime.now().format(FOLDER_TIME) + "-" + seed;
		final Path dir = Path.of(FakeSimulationConfig.LOG_DIR, name);
		final SimulationSettings settings = new SimulationSettings(size, level, mirror, seed, Math.max(1, rounds), timeLimit, admin.getObjectId());
		final Series series = new Series(settings, points, name, dir);
		_series = series;

		final SimulationRun run = startRound(series, 1);
		if (run == null)
		{
			_series = null;
			lines.add("Could not spawn the teams (see the server log).");
			return lines;
		}

		final StringBuilder roles = new StringBuilder();
		for (SimRole role : SimulationComposer.getRoles(size))
		{
			roles.append(roles.length() > 0 ? ", " : "").append(role.name().toLowerCase());
		}
		lines.add("Simulation started: " + points.size() + " teams of " + size + " (" + roles + "), level " + level + (mirror ? ", mirror" : "") + ", seed " + seed + ", " + settings.rounds() + " round(s), " + timeLimit + " s limit.");
		lines.add("They get ready for " + FakeSimulationConfig.PREP_TIME + " s, then march. Log: " + dir.toAbsolutePath());
		return lines;
	}

	private synchronized SimulationRun startRound(Series series, int round)
	{
		final SimulationSettings settings = series.settings;
		final int runId = RUN_IDS.incrementAndGet();
		final SimulationLog log = new SimulationLog(series.dir.resolve(String.format("round-%02d", round)));
		final SimulationRun run = new SimulationRun(runId, round, settings, log);
		_run = run;
		ACTIVE = true;

		final Random random = new Random(settings.seed());
		final List<SimRole> roles = SimulationComposer.getRoles(settings.size());
		final FakePlayerPvpBuild[] mirrorBuilds = new FakePlayerPvpBuild[roles.size()];
		for (int i = 0; i < roles.size(); i++)
		{
			mirrorBuilds[i] = SimulationComposer.pick(roles.get(i), random);
		}

		int spawned = 0;
		for (SimulationPoint point : series.points)
		{
			final SimulationTeam team = new SimulationTeam(point.id(), point, roles);
			run.getTeams().add(team);
			final List<Npc> fakes = new ArrayList<>();
			for (int i = 0; i < roles.size(); i++)
			{
				final SimRole role = roles.get(i);
				final FakePlayerPvpBuild build = settings.mirror() ? mirrorBuilds[i] : SimulationComposer.pick(role, random);
				if (build == null)
				{
					LOGGER.warning(getClass().getSimpleName() + ": No build for the " + role + " slot.");
					continue;
				}

				final long seed = settings.mirror() ? ((settings.seed() * 31) + i) : ((((settings.seed() * 31) + i) * 1009) + point.id());
				final Location location = getSpawnLocation(point, i, roles.size());
				final Npc fake = FakePlayerPvpManager.getInstance().spawnSimulant(build, settings.level(), "Team " + point.id(), seed, runId, point.id(), location.getX(), location.getY(), location.getZ(), point.instanceId());
				if (fake == null)
				{
					continue;
				}

				run.addMember(team, new SimulationMember(fake, point.id(), role, build.getName(), false, seed, run.time()));
				fakes.add(fake);
				spawned++;
			}
			team.setParty(FakePartyManager.getInstance().createSimulationParty(fakes));
		}

		if (spawned == 0)
		{
			log.close();
			_run = null;
			ACTIVE = false;
			return null;
		}

		writeRunFile(run, series);
		log.write(SimulationLog.EVENTS, new JsonLine().put("t", run.time()).put("type", "round_start").put("round", round).put("runId", runId));

		// Admins that asked to join a team.
		for (Guest guest : _guests.values())
		{
			if (!guest.joined)
			{
				final SimulationTeam team = run.getTeam(guest.teamId);
				final String result = team != null ? joinTeam(run, team, guest) : "Team " + guest.teamId + " is not in this simulation.";
				guest.admin.sendSysMessage(result);
			}
		}

		keepRegionsAwake(run);
		run.setTask(ThreadPool.scheduleAtFixedRate(() -> tick(run), FakeSimulationConfig.POSITION_INTERVAL, FakeSimulationConfig.POSITION_INTERVAL));
		return run;
	}

	private static Location getSpawnLocation(SimulationPoint point, int slot, int size)
	{
		if ((slot == 0) || (FakeSimulationConfig.SPAWN_RADIUS == 0))
		{
			return new Location(point.x(), point.y(), point.z());
		}

		final double angle = ((2 * Math.PI * (slot - 1)) / Math.max(1, size - 1)) + Math.toRadians(point.heading() / 182.044444444);
		final int x = point.x() + (int) (Math.cos(angle) * FakeSimulationConfig.SPAWN_RADIUS);
		final int y = point.y() + (int) (Math.sin(angle) * FakeSimulationConfig.SPAWN_RADIUS);
		return GeoEngine.getInstance().getValidLocation(point.x(), point.y(), point.z(), x, y, point.z(), point.instanceId());
	}

	private void writeRunFile(SimulationRun run, Series series)
	{
		final SimulationSettings settings = run.getSettings();
		final JsonLine json = new JsonLine();
		json.put("series", series.name);
		json.put("round", run.getRound());
		json.put("runId", run.getId());
		json.put("start", LocalDateTime.now().toString());
		json.put("size", settings.size());
		json.put("level", settings.level());
		json.put("mirror", settings.mirror());
		json.put("seed", settings.seed());
		json.put("rounds", settings.rounds());
		json.put("timeLimit", settings.timeLimit());
		final Map<String, Object> config = new LinkedHashMap<>();
		config.put("prepTime", FakeSimulationConfig.PREP_TIME);
		config.put("positionInterval", FakeSimulationConfig.POSITION_INTERVAL);
		config.put("decisionHeartbeat", FakeSimulationConfig.DECISION_HEARTBEAT);
		config.put("sightRange", FakeSimulationConfig.SIGHT_RANGE);
		config.put("spawnRadius", FakeSimulationConfig.SPAWN_RADIUS);
		json.put("config", config);

		final List<JsonLine> points = new ArrayList<>();
		for (SimulationPoint point : series.points)
		{
			points.add(new JsonLine().put("id", point.id()).put("x", point.x()).put("y", point.y()).put("z", point.z()).put("heading", point.heading()).put("instance", point.instanceId()));
		}
		json.put("points", points);

		final List<JsonLine> members = new ArrayList<>();
		for (SimulationTeam team : run.getTeams())
		{
			for (SimulationMember member : team.getMembers())
			{
				members.add(describe(member));
			}
		}
		json.put("members", members);
		run.getLog().writeFile("run.json", json.toString());
	}

	private static JsonLine describe(SimulationMember member)
	{
		final Creature creature = member.getCreature();
		final JsonLine line = new JsonLine().put("id", member.getObjectId()).put("name", member.getName()).put("team", member.getTeamId()).put("role", member.getRole()).put("build", member.getBuild()).put("admin", member.isAdmin()).put("level", creature.getLevel()).put("seed", member.getSeed());
		line.put("buffs", getBuffNames(creature));
		line.put("maxHp", creature.getMaxHp()).put("maxMp", creature.getMaxMp()).put("pAtk", (int) creature.getPAtk(null)).put("mAtk", (int) creature.getMAtk(null, null)).put("pDef", (int) creature.getPDef(null)).put("mDef", (int) creature.getMDef(null, null)).put("attackRange", creature.getPhysicalAttackRange()).put("runSpeed", (int) creature.getRunSpeed());
		if (creature instanceof Npc npc)
		{
			final FakePlayerPvpProfile profile = npc.getTemplate().getFakePlayerPvpProfile();
			if (profile != null)
			{
				line.put("classId", (npc.getTemplate().getFakePlayerInfo() != null) && (npc.getTemplate().getFakePlayerInfo().getPlayerClass() != null) ? npc.getTemplate().getFakePlayerInfo().getPlayerClass().getId() : null);
				line.put("buildRole", profile.getRole());
				line.put("personality", String.valueOf(profile.getPersonality()));
				line.put("runner", profile.isRunner());
				final Map<String, List<String>> skills = new LinkedHashMap<>();
				for (SkillCategory category : SkillCategory.values())
				{
					final List<String> names = new ArrayList<>();
					for (Skill skill : profile.getSkills(category))
					{
						names.add(skill.getName() + " (" + skill.getId() + "-" + skill.getLevel() + ", range " + skill.getCastRange() + ")");
					}
					if (!names.isEmpty())
					{
						skills.put(category.name(), names);
					}
				}
				line.put("skills", skills);
			}
		}
		else if (creature.isPlayer())
		{
			line.put("classId", creature.asPlayer().getPlayerClass().getId());
		}
		return line;
	}

	/**
	 * @param creature a creature
	 * @return the buffs, songs, dances and toggles it has on, as "name level"
	 */
	private static List<String> getBuffNames(Creature creature)
	{
		final List<String> names = new ArrayList<>();
		for (BuffInfo info : creature.getEffectList().getBuffs())
		{
			names.add(info.getSkill().getName() + " " + info.getSkill().getLevel());
		}
		for (BuffInfo info : creature.getEffectList().getDances())
		{
			names.add(info.getSkill().getName() + " " + info.getSkill().getLevel());
		}
		for (BuffInfo info : creature.getEffectList().getToggles())
		{
			names.add(info.getSkill().getName() + " " + info.getSkill().getLevel() + " (toggle)");
		}
		return names;
	}
	
	/**
	 * Every {@link FakeSimulationConfig#POSITION_INTERVAL}: the fight starts after the preparation time, the monitor writes the positions and checks its rules, and the round ends with one team left or at the time limit.
	 */
	private void tick(SimulationRun run)
	{
		try
		{
			if (run.getPhase() == SimulationRun.Phase.ENDED)
			{
				return;
			}

			final long time = run.time();
			if ((run.getPhase() == SimulationRun.Phase.PREP) && (time >= (FakeSimulationConfig.PREP_TIME * 1000L)))
			{
				run.setPhase(SimulationRun.Phase.FIGHT);
				run.setFightStart(time);
				run.getLog().write(SimulationLog.EVENTS, new JsonLine().put("t", time).put("type", "fight_start"));
				
				// Everyone starts the fight with its class buffs on (a region that slept meanwhile took them) and full HP.
				for (SimulationMember member : run.getMembers().values())
				{
					if (!member.isAdmin() && member.isAlive() && (member.getCreature() instanceof Npc fake))
					{
						FakePlayerPvpManager.getInstance().prepareSimulant(fake);
						run.getLog().write(SimulationLog.EVENTS, new JsonLine().put("t", time).put("type", "buffs").put("id", member.getObjectId()).put("name", member.getName()).put("team", member.getTeamId()).put("buffs", getBuffNames(fake)));
					}
				}
			}

			final boolean fighting = run.getPhase() == SimulationRun.Phase.FIGHT;
			run.getMonitor().tick(fighting);
			keepRegionsAwake(run);

			// The admin of a team stays flagged.
			for (Guest guest : _guests.values())
			{
				if (guest.joined && (guest.admin.getPvpFlag() == 0) && !guest.admin.isDead())
				{
					guest.admin.updatePvPStatus();
				}
			}

			if (!fighting)
			{
				return;
			}

			final List<SimulationTeam> alive = new ArrayList<>();
			for (SimulationTeam team : run.getTeams())
			{
				if (team.isAlive())
				{
					alive.add(team);
				}
			}

			if (alive.size() <= 1)
			{
				end(run, alive.isEmpty() ? 0 : alive.get(0).getId(), alive.isEmpty() ? "draw" : "victory");
			}
			else if ((time - run.getFightStart()) >= (run.getSettings().timeLimit() * 1000L))
			{
				SimulationTeam best = null;
				for (SimulationTeam team : alive)
				{
					if ((best == null) || (team.getHealthLeft() > best.getHealthLeft()))
					{
						best = team;
					}
				}
				end(run, best.getId(), "timeout");
			}
		}
		catch (Exception e)
		{
			LOGGER.log(Level.WARNING, getClass().getSimpleName() + ": Problem in simulation run " + run.getId() + ".", e);
		}
	}

	/**
	 * Keeps the regions of the members awake: a fake player only thinks while its region is, and the admin may stand far from the teams.
	 */
	private void keepRegionsAwake(SimulationRun run)
	{
		for (SimulationMember member : run.getMembers().values())
		{
			final WorldRegion region = member.getCreature().getWorldRegion();
			if ((region != null) && !region.isKeptActive())
			{
				region.keepActive();
				_keptRegions.add(region);
			}
		}
	}

	private void releaseRegions()
	{
		for (WorldRegion region : _keptRegions)
		{
			region.releaseKeepActive();
		}
		_keptRegions.clear();
	}

	/**
	 * Ends a round: writes its summary, shows it to the admins, and removes the bodies a moment later.
	 * @param run the round
	 * @param winner the winning team, 0 for none
	 * @param reason victory, draw, timeout or stopped
	 */
	private void end(SimulationRun run, int winner, String reason)
	{
		synchronized (run)
		{
			if (run.getPhase() == SimulationRun.Phase.ENDED)
			{
				return;
			}
			run.setPhase(SimulationRun.Phase.ENDED);
		}

		final ScheduledFuture<?> task = run.getTask();
		if (task != null)
		{
			task.cancel(false);
		}

		final long time = run.time();
		run.setResult(time, winner, reason);
		run.getLog().write(SimulationLog.EVENTS, new JsonLine().put("t", time).put("type", "round_end").put("winner", winner).put("reason", reason));

		final Series series = _series;
		final String text = SimulationSummary.write(run, series != null ? series.settings.rounds() : 1);
		if (series != null)
		{
			record(series, run);
		}

		final Set<Player> viewers = new HashSet<>();
		final Player starter = World.getInstance().getPlayer(run.getSettings().starterId());
		if (starter != null)
		{
			viewers.add(starter);
		}
		for (Guest guest : _guests.values())
		{
			if (guest.joined && guest.admin.isOnline())
			{
				viewers.add(guest.admin);
			}
		}
		for (Player viewer : viewers)
		{
			showText(viewer, "Simulation round " + run.getRound(), text);
			viewer.sendSysMessage("Simulation round " + run.getRound() + " over: " + (winner > 0 ? "team " + winner + " wins" : "no winner") + " (" + reason + "). Log: " + run.getLog().getDir().toAbsolutePath());
		}

		ThreadPool.schedule(() -> cleanup(run), FakeSimulationConfig.CLEANUP_DELAY * 1000L);
	}

	private void record(Series series, SimulationRun run)
	{
		synchronized (series)
		{
			series.results.add(new JsonLine().put("round", run.getRound()).put("winner", run.getWinner()).put("reason", run.getEndReason()).put("duration", run.getEnd() - run.getFightStart()).put("anomalies", run.getMonitor().getCounts()));
			if (run.getWinner() > 0)
			{
				series.teamWins.merge(run.getWinner(), 1, Integer::sum);
			}
			for (SimulationTeam team : run.getTeams())
			{
				final boolean won = team.getId() == run.getWinner();
				for (SimulationMember member : team.getMembers())
				{
					if (member.isAdmin())
					{
						continue;
					}
					final int[] build = series.buildStats.computeIfAbsent(member.getBuild(), _ -> new int[2]);
					final int[] role = series.roleStats.computeIfAbsent(member.getRole().name(), _ -> new int[2]);
					build[1]++;
					role[1]++;
					if (won)
					{
						build[0]++;
						role[0]++;
					}
				}
			}
			for (Map.Entry<String, Integer> entry : run.getMonitor().getCounts().entrySet())
			{
				series.anomalies.merge(entry.getKey(), entry.getValue(), Integer::sum);
			}
		}
	}

	/**
	 * Removes what is left of a round, then starts the next one or ends the simulation.
	 */
	private void cleanup(SimulationRun run)
	{
		try
		{
			for (SimulationTeam team : run.getTeams())
			{
				final FakePlayerParty party = team.getParty();
				if (party == null)
				{
					continue;
				}

				final Player admin = party.getGuest();
				if (admin != null)
				{
					final Guest guest = _guests.get(admin.getObjectId());
					if (guest != null)
					{
						restoreGuest(guest);
						guest.joined = false;
					}
				}
				FakePartyManager.getInstance().disbandSimulationParty(party);
			}

			// They stop, drop their transformation (a transformed fake player deleted as such leaves its name behind in the client), and leave a moment later.
			final List<Npc> leaving = new ArrayList<>();
			for (SimulationMember member : run.getMembers().values())
			{
				if ((member.getCreature() instanceof Npc npc) && !npc.isDecayed())
				{
					DecayTaskManager.getInstance().cancel(npc);
					if (!npc.isDead())
					{
						npc.abortAttack();
						npc.abortCast();
						if (npc.hasAI())
						{
							npc.getAI().setIntention(Intention.IDLE);
						}
						npc.stopTransformation(true);
						FakePlayerPvpManager.getInstance().untransform(npc);
						FakePlayerPvpManager.getInstance().unsummonServitor(npc);
					}
					leaving.add(npc);
				}
			}
			ThreadPool.schedule(() -> removeFakes(run, leaving), REMOVE_DELAY);
		}
		catch (Exception e)
		{
			LOGGER.log(Level.WARNING, getClass().getSimpleName() + ": Problem while cleaning up simulation run " + run.getId() + ".", e);
		}
		finally
		{
			run.getLog().close();
			synchronized (this)
			{
				if (_run == run)
				{
					_run = null;
					ACTIVE = false;
				}
				releaseRegions();
			}
		}

		final Series series = _series;
		if (series == null)
		{
			return;
		}

		if (!series.stopped && (run.getRound() < series.settings.rounds()))
		{
			if (startRound(series, run.getRound() + 1) != null)
			{
				return;
			}
		}
		finishSeries(series);
	}

	/**
	 * Removes the fake players of a round that ended, and makes sure none is left in the world.
	 */
	private void removeFakes(SimulationRun run, List<Npc> fakes)
	{
		for (Npc npc : fakes)
		{
			try
			{
				if (!npc.isDecayed())
				{
					npc.deleteMe();
				}
				FakeAiTrace.forget(npc.getObjectId());
			}
			catch (Exception e)
			{
				LOGGER.log(Level.WARNING, getClass().getSimpleName() + ": Could not remove " + npc.getName() + " of simulation run " + run.getId() + ".", e);
			}
		}
		
		for (Npc npc : fakes)
		{
			if ((World.getInstance().findObject(npc.getObjectId()) != null) || npc.isSpawned())
			{
				LOGGER.warning(getClass().getSimpleName() + ": " + npc.getName() + " of simulation run " + run.getId() + " is still in the world after its removal: deleting it again.");
				npc.decayMe();
				npc.deleteMe();
			}
		}
	}
	
	private void finishSeries(Series series)
	{
		synchronized (this)
		{
			if (_series == series)
			{
				_series = null;
			}
		}

		final String text = SimulationSummary.writeSeries(series.dir, series.name, series.settings, series.results, series.teamWins, series.buildStats, series.roleStats, series.anomalies);
		SimulationLog.prune(Path.of(FakeSimulationConfig.LOG_DIR), FakeSimulationConfig.KEEP_RUNS);

		final Player starter = World.getInstance().getPlayer(series.settings.starterId());
		if ((starter != null) && (series.settings.rounds() > 1))
		{
			showText(starter, "Simulation " + series.name, text);
		}
		if (starter != null)
		{
			starter.sendSysMessage("Simulation " + series.name + " finished" + (series.stopped ? " (stopped)" : "") + ".");
		}

		// Admins waiting for the next round wait no more.
		for (Guest guest : new ArrayList<>(_guests.values()))
		{
			_guests.remove(guest.admin.getObjectId());
			guest.admin.sendSysMessage("You left the simulation team " + guest.teamId + ".");
		}
	}

	/**
	 * @param admin the admin
	 * @return what to tell the admin
	 */
	public String stop(Player admin)
	{
		final Series series = _series;
		final SimulationRun run = _run;
		if ((series == null) && (run == null))
		{
			return "No simulation is running.";
		}

		if (series != null)
		{
			series.stopped = true;
		}
		if ((run != null) && (run.getPhase() != SimulationRun.Phase.ENDED))
		{
			end(run, 0, "stopped");
			return "Simulation stopped: the bodies are removed in " + FakeSimulationConfig.CLEANUP_DELAY + " s.";
		}
		return "The simulation ends after this round's cleanup.";
	}

	/**
	 * @return the lines of //SimulationStatus
	 */
	public List<String> getStatus()
	{
		final List<String> lines = new ArrayList<>();
		final SimulationRun run = _run;
		if (run == null)
		{
			lines.add("No simulation is running. " + _points.size() + " point(s) saved.");
			for (Guest guest : _guests.values())
			{
				lines.add(guest.admin.getName() + " joins team " + guest.teamId + " at the next //Simulate.");
			}
			return lines;
		}

		final long time = run.time();
		lines.add("Round " + run.getRound() + "/" + run.getSettings().rounds() + ", " + run.getPhase() + ", " + (time / 1000) + " s" + (run.getFightStart() > 0 ? ", fighting for " + ((time - run.getFightStart()) / 1000) + " s" : "") + ", seed " + run.getSettings().seed() + ".");
		for (SimulationTeam team : run.getTeams())
		{
			int alive = 0;
			int total = 0;
			for (SimulationMember member : team.getMembers())
			{
				if (team.isActive(member))
				{
					total++;
					if (member.isAlive())
					{
						alive++;
					}
				}
			}
			lines.add("Team " + team.getId() + ": " + alive + "/" + total + " alive, " + Math.round(team.getHealthLeft() * 100) + "% HP+CP left.");
			for (SimulationMember member : team.getMembers())
			{
				if (!team.isActive(member))
				{
					continue;
				}
				final Creature creature = member.getCreature();
				final WorldObject target = creature.getTarget();
				lines.add("  " + member.getName() + " (" + member.getRole().name().toLowerCase() + ", " + member.getBuild() + (member.isAdmin() ? ", admin" : "") + "): " + (member.isAlive() ? Math.round((creature.getCurrentHp() * 100) / creature.getMaxHp()) + "% HP" : "dead") + (target != null ? " -> " + target.getName() : "") + (member.getLastReason() != null ? " [" + member.getLastReason() + "]" : ""));
			}
		}
		final Map<String, Integer> counts = run.getMonitor().getCounts();
		lines.add("Anomalies: " + (counts.isEmpty() ? "none" : counts.toString()));
		return lines;
	}

	// ---------------------------------------------------------------------------------------------
	// Lookups for the AI
	// ---------------------------------------------------------------------------------------------

	/**
	 * @param runId a run id
	 * @return the round running under that id, {@code null} if none (it ended and was cleaned up)
	 */
	public SimulationRun getRun(int runId)
	{
		final SimulationRun run = _run;
		return (run != null) && (run.getId() == runId) ? run : null;
	}

	/**
	 * @param runId a run id
	 * @return the phase of that run, {@code null} if it is gone
	 */
	public SimulationRun.Phase getPhase(int runId)
	{
		final SimulationRun run = getRun(runId);
		return run != null ? run.getPhase() : null;
	}

	/**
	 * @param npc a fake player of a run
	 * @return the closest living enemy it sees within {@link FakeSimulationConfig#SIGHT_RANGE} (or that is very close), {@code null} if none
	 */
	public Creature findEnemyInSight(Npc npc)
	{
		final SimulationRun run = getRun(npc.getTemplate().getFakePlayerPvpProfile().getSimRunId());
		final SimulationMember member = run != null ? run.getMember(npc) : null;
		if (member == null)
		{
			return null;
		}

		Creature closest = null;
		double closestDistance = Double.MAX_VALUE;
		for (SimulationMember enemy : run.getEnemiesOf(member))
		{
			final Creature creature = enemy.getCreature();
			final double distance = npc.calculateDistance2D(creature);
			if ((distance < closestDistance) && (distance <= FakeSimulationConfig.SIGHT_RANGE) && (creature.getInstanceId() == npc.getInstanceId()) && !creature.isInvisible() && ((distance <= CLOSE_ENEMY_RANGE) || GeoEngine.getInstance().canSeeTarget(npc, creature)))
			{
				closest = creature;
				closestDistance = distance;
			}
		}
		return closest;
	}

	/**
	 * @param npc a fake player of a run
	 * @return where the nearest other team is (the middle of its living members), {@code null} if no team is left
	 */
	public Location getMarchTarget(Npc npc)
	{
		final SimulationRun run = getRun(npc.getTemplate().getFakePlayerPvpProfile().getSimRunId());
		final SimulationMember member = run != null ? run.getMember(npc) : null;
		if (member == null)
		{
			return null;
		}

		Location best = null;
		double bestDistance = Double.MAX_VALUE;
		for (SimulationTeam team : run.getTeams())
		{
			if (team.getId() == member.getTeamId())
			{
				continue;
			}

			long x = 0;
			long y = 0;
			long z = 0;
			int count = 0;
			for (SimulationMember other : team.getMembers())
			{
				if (team.isActive(other) && other.isAlive())
				{
					x += other.getCreature().getX();
					y += other.getCreature().getY();
					z += other.getCreature().getZ();
					count++;
				}
			}
			if (count == 0)
			{
				continue;
			}

			final Location center = new Location((int) (x / count), (int) (y / count), (int) (z / count));
			final double distance = npc.calculateDistance2D(center);
			if (distance < bestDistance)
			{
				best = center;
				bestDistance = distance;
			}
		}
		return best;
	}

	/**
	 * @param npc a fake player
	 * @param range a distance
	 * @return {@code true} if it is in a simulation run and a living member of another team is within {@code range}
	 */
	public static boolean isRunEnemyWithin(Creature npc, int range)
	{
		final SimulationRun run = ACTIVE ? getInstance()._run : null;
		final SimulationMember member = run != null ? run.getMember(npc) : null;
		if (member == null)
		{
			return false;
		}

		for (SimulationMember enemy : run.getEnemiesOf(member))
		{
			if (npc.calculateDistance2D(enemy.getCreature()) <= range)
			{
				return true;
			}
		}
		return false;
	}

	/**
	 * @param creature a fake player
	 * @param other another creature
	 * @return {@code true} if both fight in the same simulation run, on different teams: every member of the other teams is an enemy of the fight
	 */
	public static boolean isRunEnemy(Creature creature, Creature other)
	{
		final int runId = getRunId(creature);
		if ((runId == 0) || (getRunId(other) != runId))
		{
			return false;
		}

		final SimulationRun run = getInstance()._run;
		final SimulationMember a = run != null ? getMemberBehind(run, creature) : null;
		final SimulationMember b = run != null ? getMemberBehind(run, other) : null;
		return (a != null) && (b != null) && (a.getTeamId() != b.getTeamId());
	}

	/**
	 * @param a a creature
	 * @param b another creature
	 * @return {@code false} if one of them is in a simulation run and the other isn't in the same one: simulation teams only fight each other
	 */
	public static boolean canFight(Creature a, Creature b)
	{
		return getRunId(a) == getRunId(b);
	}

	/**
	 * @param creature a creature (its summon or servitor counts as its owner)
	 * @return the run it fights in, 0 for none
	 */
	private static int getRunId(Creature creature)
	{
		final Creature owner = CombatLogManager.getPvpOwner(creature);
		final Creature who = owner != null ? owner : creature;
		if (who == null)
		{
			return 0;
		}

		if (who.isNpc())
		{
			final FakePlayerPvpProfile profile = who.asNpc().getTemplate().getFakePlayerPvpProfile();
			return profile != null ? profile.getSimRunId() : 0;
		}

		if (who.isPlayer())
		{
			final FakeSimulationManager manager = getInstance();
			final Guest guest = manager._guests.isEmpty() ? null : manager._guests.get(who.getObjectId());
			final SimulationRun run = manager._run;
			return (guest != null) && guest.joined && (run != null) ? run.getId() : 0;
		}
		return 0;
	}

	/**
	 * @param player a player
	 * @return {@code true} if it is an admin in a simulation team: fake players fight it even with its GM access level
	 */
	public boolean isGuest(Player player)
	{
		final Guest guest = _guests.isEmpty() ? null : _guests.get(player.getObjectId());
		return (guest != null) && guest.joined;
	}

	// ---------------------------------------------------------------------------------------------
	// Joining a team
	// ---------------------------------------------------------------------------------------------

	/**
	 * {@code admin} joins team {@code teamId} in place of one of its fake players, now or when //Simulate spawns it.
	 * @param admin the admin
	 * @param teamId the team (its point number)
	 * @param role the slot to take (tank, melee, ranged, healer, buffer), {@code null} for the one of its class
	 * @param leads {@code true} if the team follows the admin instead of its fake leader
	 * @return what to tell the admin
	 */
	public synchronized String joinParty(Player admin, int teamId, String role, boolean leads)
	{
		final Guest current = _guests.get(admin.getObjectId());
		if ((current != null) && current.joined)
		{
			return "You are already in team " + current.teamId + ". //LeaveParty first.";
		}
		if ((role != null) && SimulationComposer.parseGroup(role).isEmpty())
		{
			return "Unknown role " + role + ": tank, melee, ranged, healer or buffer.";
		}

		final Guest guest = new Guest(admin, teamId, role, leads);
		final SimulationRun run = _run;
		if ((run == null) || (run.getPhase() == SimulationRun.Phase.ENDED))
		{
			if (!_points.containsKey(teamId))
			{
				return "There is no point " + teamId + ".";
			}
			_guests.put(admin.getObjectId(), guest);
			return "You join team " + teamId + " when //Simulate spawns it.";
		}

		final SimulationTeam team = run.getTeam(teamId);
		if (team == null)
		{
			return "Team " + teamId + " is not in this simulation.";
		}
		_guests.put(admin.getObjectId(), guest);
		final String result = joinTeam(run, team, guest);
		if (!guest.joined)
		{
			_guests.remove(admin.getObjectId());
		}
		return result;
	}

	private String joinTeam(SimulationRun run, SimulationTeam team, Guest guest)
	{
		final Player admin = guest.admin;
		final List<SimRole> wanted = guest.role != null ? SimulationComposer.parseGroup(guest.role) : List.of(getRole(admin));
		final FakePlayerParty party = team.getParty();
		if (party == null)
		{
			return "Team " + team.getId() + " has no party.";
		}

		// The fake player of the slot asked for, otherwise the last damage dealer, otherwise anyone but the leader.
		SimulationMember replaced = null;
		SimulationMember lastDps = null;
		SimulationMember anyone = null;
		for (SimulationMember member : team.getMembers())
		{
			if (member.isAdmin() || !team.isActive(member) || !member.isAlive())
			{
				continue;
			}
			if ((replaced == null) && wanted.contains(member.getRole()))
			{
				replaced = member;
			}
			if ((member.getRole() == SimRole.MELEE) || member.getRole().isRanged())
			{
				lastDps = member;
			}
			if ((anyone == null) || (party.getLeader() == anyone.getCreature()))
			{
				anyone = member;
			}
		}
		if (replaced == null)
		{
			replaced = lastDps != null ? lastDps : anyone;
		}
		if (replaced == null)
		{
			return "Team " + team.getId() + " has no living fake player to replace.";
		}

		final Npc fake = (Npc) replaced.getCreature();
		final int x = fake.getX();
		final int y = fake.getY();
		final int z = fake.getZ();
		guest.replacedBuild = replaced.getBuild();
		guest.replacedRole = replaced.getRole();
		guest.replacedSeed = replaced.getSeed();
		run.getLog().write(SimulationLog.EVENTS, new JsonLine().put("t", run.time()).put("type", "admin_join").put("admin", admin.getName()).put("team", team.getId()).put("replaced", fake.getName()).put("replacedBuild", replaced.getBuild()).put("replacedRole", replaced.getRole()).ratio("replacedHp", fake.getCurrentHp() / fake.getMaxHp()).put("leads", guest.leads));

		FakeAiTrace.forget(fake.getObjectId());
		fake.deleteMe();

		// Fair game for the fake players: no invincibility or hiding, flagged, full HP.
		guest.wasInvul = admin.isInvul() && !admin.isAffected(EffectFlag.INVUL) && !admin.isTeleporting();
		guest.wasInvisible = admin.isInvisible();
		admin.setInvul(false);
		admin.setInvisible(false);
		if (admin.isDead())
		{
			admin.doRevive();
		}
		admin.setCurrentHpMp(admin.getMaxHp(), admin.getMaxMp());
		admin.setCurrentCp(admin.getMaxCp());
		admin.teleToLocation(x, y, z, fake.getHeading(), team.getPoint().instanceId());
		admin.updatePvPStatus();
		admin.broadcastUserInfo();

		run.addMember(team, new SimulationMember(admin, team.getId(), replaced.getRole(), admin.getPlayerClass().name(), true, 0, run.time()));
		guest.joined = true;
		FakePartyManager.getInstance().setGuest(party, admin, guest.leads);
		return "You joined team " + team.getId() + " in place of " + fake.getName() + " (" + replaced.getBuild() + ", " + replaced.getRole().name().toLowerCase() + ")" + (guest.leads ? ", and you lead it" : "") + ". //LeaveParty to leave.";
	}

	/**
	 * @param player a player
	 * @return the slot of its class: archer with a bow, healer for a cleric, nuker for another mystic, tank for a knight, melee otherwise
	 */
	private static SimRole getRole(Player player)
	{
		final Weapon weapon = player.getActiveWeaponItem();
		if ((weapon != null) && ((weapon.getItemType() == WeaponType.BOW) || (weapon.getItemType() == WeaponType.CROSSBOW)))
		{
			return SimRole.ARCHER;
		}
		if (player.isInCategory(CategoryType.CLERIC_GROUP) || player.isInCategory(CategoryType.HEAL_GROUP))
		{
			return SimRole.HEALER;
		}
		if (player.isMageClass())
		{
			return SimRole.NUKER;
		}
		if (player.isInCategory(CategoryType.TANKER_GROUP) || player.isInCategory(CategoryType.KNIGHT_GROUP))
		{
			return SimRole.TANK;
		}
		return SimRole.MELEE;
	}

	/**
	 * {@code admin} leaves its team: the fake player it replaced comes back where it stands.
	 * @param admin the admin
	 * @return what to tell the admin
	 */
	public synchronized String leaveParty(Player admin)
	{
		final Guest guest = _guests.remove(admin.getObjectId());
		if (guest == null)
		{
			return "You are not in a simulation team.";
		}
		if (!guest.joined)
		{
			return "You no longer join team " + guest.teamId + ".";
		}

		restoreGuest(guest);
		guest.joined = false;

		final SimulationRun run = _run;
		final SimulationTeam team = run != null ? run.getTeam(guest.teamId) : null;
		if ((team == null) || (run.getPhase() == SimulationRun.Phase.ENDED) || (guest.replacedBuild == null))
		{
			return "You left team " + guest.teamId + ".";
		}

		run.getLog().write(SimulationLog.EVENTS, new JsonLine().put("t", run.time()).put("type", "admin_leave").put("admin", admin.getName()).put("team", team.getId()));
		final FakePlayerPvpBuild build = org.l2jmobius.gameserver.data.xml.FakePlayerPvpData.getInstance().getBuild(guest.replacedBuild);
		final Npc fake = build != null ? FakePlayerPvpManager.getInstance().spawnSimulant(build, run.getSettings().level(), "Team " + team.getId(), guest.replacedSeed, run.getId(), team.getId(), admin.getX(), admin.getY(), admin.getZ(), admin.getInstanceId()) : null;
		if (fake == null)
		{
			return "You left team " + guest.teamId + " (the fake player you replaced could not come back).";
		}

		run.addMember(team, new SimulationMember(fake, team.getId(), guest.replacedRole, build.getName(), false, guest.replacedSeed, run.time()));
		FakePartyManager.getInstance().addToSimulationParty(team.getParty(), fake);
		return "You left team " + guest.teamId + "; " + fake.getName() + " (" + build.getName() + ") takes your place.";
	}

	/**
	 * Gives an admin back its state from before it joined: out of the party window, alive with full HP, invincible and hidden again if it was.
	 */
	private void restoreGuest(Guest guest)
	{
		final Player admin = guest.admin;
		FakePartyManager.getInstance().removeGuest(admin);
		if (admin.isDead())
		{
			admin.doRevive();
		}
		admin.setCurrentHpMp(admin.getMaxHp(), admin.getMaxMp());
		admin.setCurrentCp(admin.getMaxCp());
		admin.setInvul(guest.wasInvul);
		if (guest.wasInvisible)
		{
			admin.setInvisible(true);
		}
		admin.broadcastUserInfo();
	}

	// ---------------------------------------------------------------------------------------------
	// Debug views
	// ---------------------------------------------------------------------------------------------

	/**
	 * //SimulationDraw: draws the ranges of {@code fake} for {@code admin}, or stops.
	 * @param admin the admin
	 * @param fake a fake player, {@code null} to stop
	 * @return what to tell the admin
	 */
	public synchronized String toggleDraw(Player admin, Npc fake)
	{
		final Integer drawn = _draws.get(admin.getObjectId());
		if ((fake == null) || ((drawn != null) && (drawn == fake.getObjectId())))
		{
			_draws.remove(admin.getObjectId());
			clearDraw(admin);
			if (_draws.isEmpty() && (_drawTask != null))
			{
				_drawTask.cancel(false);
				_drawTask = null;
			}
			return "Drawing stopped.";
		}

		_draws.put(admin.getObjectId(), fake.getObjectId());
		if (_drawTask == null)
		{
			_drawTask = ThreadPool.scheduleAtFixedRate(this::draw, DRAW_INTERVAL, DRAW_INTERVAL);
		}
		return "Drawing " + fake.getName() + ": green its auto attack range, red its longest attack skill, yellow to its target, blue to its party leader. Use //SimulationDraw on it again to stop.";
	}

	private void draw()
	{
		for (Map.Entry<Integer, Integer> entry : _draws.entrySet())
		{
			final Player admin = World.getInstance().getPlayer(entry.getKey());
			final WorldObject object = World.getInstance().findObject(entry.getValue());
			if ((admin == null) || !(object instanceof Npc fake) || !fake.isSpawned())
			{
				_draws.remove(entry.getKey());
				if (admin != null)
				{
					clearDraw(admin);
				}
				continue;
			}

			final int z = fake.getZ() + 15;
			final ExServerPrimitive packet = new ExServerPrimitive("SimulationDraw", fake.getX(), fake.getY(), z);
			addCircle(packet, Color.GREEN, fake.getX(), fake.getY(), z, fake.getPhysicalAttackRange() + fake.getTemplate().getCollisionRadius());
			final FakePlayerPvpProfile profile = fake.getTemplate().getFakePlayerPvpProfile();
			int reach = 0;
			if (profile != null)
			{
				for (Skill skill : profile.getSkills(SkillCategory.ATTACK))
				{
					reach = Math.max(reach, skill.getCastRange());
				}
			}
			if (reach > 0)
			{
				addCircle(packet, Color.RED, fake.getX(), fake.getY(), z, reach + fake.getTemplate().getCollisionRadius());
			}
			final WorldObject target = fake.getTarget();
			if ((target != null) && (target != fake))
			{
				packet.addLine(target.getName(), Color.YELLOW.getRGB(), true, fake.getX(), fake.getY(), z, target.getX(), target.getY(), target.getZ() + 15);
			}
			final FakePlayerParty party = profile != null ? profile.getParty() : null;
			final Creature leader = party != null ? party.getLeader() : null;
			if ((leader != null) && (leader != fake))
			{
				packet.addLine(Color.BLUE, fake.getX(), fake.getY(), z, leader.getX(), leader.getY(), leader.getZ() + 15);
			}
			admin.sendPacket(packet);
		}
	}

	private static void clearDraw(Player admin)
	{
		final ExServerPrimitive packet = new ExServerPrimitive("SimulationDraw", admin.getX(), admin.getY(), admin.getZ());
		packet.addPoint(Color.GREEN, 0, 0, Short.MIN_VALUE);
		admin.sendPacket(packet);
	}

	/**
	 * Shows a text (a summary) to an admin in a window.
	 */
	private static void showText(Player player, String title, String text)
	{
		final StringBuilder sb = new StringBuilder("<html><title>").append(title).append("</title><body><font color=\"LEVEL\">").append(title).append("</font><br>");
		for (String line : text.split("\n"))
		{
			final String escaped = line.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("  ", "&nbsp;&nbsp;");
			if ((sb.length() + escaped.length()) > HTML_LIMIT)
			{
				sb.append("... (the rest is in summary.txt)<br>");
				break;
			}
			sb.append(escaped).append("<br>");
		}
		sb.append("</body></html>");
		final NpcHtmlMessage html = new NpcHtmlMessage(0);
		html.setHtml(sb.toString());
		player.sendPacket(html);
	}

	// ---------------------------------------------------------------------------------------------
	// Combat hooks (called only while ACTIVE)
	// ---------------------------------------------------------------------------------------------

	/**
	 * @param run the round
	 * @param creature a creature
	 * @return the member behind {@code creature} (itself, or the owner of its summon or servitor), {@code null} if none
	 */
	private static SimulationMember getMemberBehind(SimulationRun run, Creature creature)
	{
		if (creature == null)
		{
			return null;
		}
		final SimulationMember member = run.getMember(creature);
		if (member != null)
		{
			return member;
		}
		final Creature owner = CombatLogManager.getPvpOwner(creature);
		return (owner != null) && (owner != creature) ? run.getMember(owner) : null;
	}

	private static JsonLine event(SimulationRun run, String type)
	{
		return new JsonLine().put("t", run.time()).put("type", type);
	}

	private static void who(JsonLine line, String key, Creature creature, SimulationMember member)
	{
		line.put(key, creature != null ? creature.getName() : null);
		line.put(key + "Id", creature != null ? creature.getObjectId() : null);
		line.put(key + "Team", member != null ? member.getTeamId() : null);
	}

	/**
	 * Damage dealt (see {@code Creature#notifyDamageReceived}).
	 */
	public void onDamage(Creature target, Creature attacker, Skill skill, double damage, boolean crit, boolean dot)
	{
		final SimulationRun run = _run;
		if ((run == null) || (damage <= 0))
		{
			return;
		}

		final SimulationMember dealer = getMemberBehind(run, attacker);
		final SimulationMember victim = getMemberBehind(run, target);
		if ((dealer == null) && (victim == null))
		{
			return;
		}

		final long time = run.time();
		final boolean self = (dealer != null) && (dealer == victim); // A toggle that costs HP (Arcane Wisdom...), a skill that hurts its caster.
		if ((dealer != null) && !self)
		{
			dealer.addDealt(damage);
		}
		if ((victim != null) && !self)
		{
			victim.addTaken(time, dealer != null ? dealer.getObjectId() : (attacker != null ? attacker.getObjectId() : 0), damage);
		}

		final JsonLine line = event(run, self ? "self_damage" : "damage");
		who(line, "attacker", attacker, dealer);
		who(line, "target", target, victim);
		line.put("skill", skill != null ? skill.getName() + " (" + skill.getId() + ")" : null).put("amount", (int) damage).put("crit", crit).put("dot", dot).ratio("targetHp", target.getCurrentHp() / Math.max(1, target.getMaxHp())).put("targetCp", (int) target.getCurrentCp());
		run.getLog().write(SimulationLog.EVENTS, line);
	}

	/**
	 * A heal (see {@code CombatLogManager#onHealed}).
	 */
	public void onHeal(Creature healer, Creature target, Skill skill, double hp, double cp)
	{
		final SimulationRun run = _run;
		if ((run == null) || (target == null) || ((hp < 1) && (cp < 1)))
		{
			return;
		}

		final SimulationMember giver = getMemberBehind(run, healer);
		final SimulationMember receiver = getMemberBehind(run, target);
		if ((giver == null) && (receiver == null))
		{
			return;
		}

		if (giver != null)
		{
			giver.addHealDone(hp + cp);
		}
		if (receiver != null)
		{
			receiver.addHealReceived(hp + cp);
		}
		if ((healer != null) && (giver != null) && (receiver != null))
		{
			run.getMonitor().recordHeal(run.time(), giver.getCreature(), receiver.getCreature());
		}

		final JsonLine line = event(run, "heal");
		who(line, "healer", healer, giver);
		who(line, "target", target, receiver);
		line.put("skill", skill != null ? skill.getName() + " (" + skill.getId() + ")" : null).put("hp", (int) hp).put("cp", (int) cp).ratio("targetHp", target.getCurrentHp() / Math.max(1, target.getMaxHp()));
		run.getLog().write(SimulationLog.EVENTS, line);
	}

	/**
	 * A debuff landed (see {@code CombatLogManager#onDebuffLanded}).
	 */
	public void onDebuff(BuffInfo info)
	{
		final SimulationRun run = _run;
		if (run == null)
		{
			return;
		}

		final SimulationMember caster = getMemberBehind(run, info.getEffector());
		final SimulationMember victim = getMemberBehind(run, info.getEffected());
		if ((caster == null) && (victim == null))
		{
			return;
		}

		final JsonLine line = event(run, "debuff_landed");
		who(line, "caster", info.getEffector(), caster);
		who(line, "target", info.getEffected(), victim);
		line.put("skill", info.getSkill().getName() + " (" + info.getSkill().getId() + ")").put("seconds", info.getAbnormalTime());
		run.getLog().write(SimulationLog.EVENTS, line);
	}

	/**
	 * A skill was resisted (see {@code CombatLogManager#onResisted}).
	 */
	public void onResist(Creature attacker, Creature target, Skill skill)
	{
		final SimulationRun run = _run;
		if (run == null)
		{
			return;
		}

		final SimulationMember caster = getMemberBehind(run, attacker);
		final SimulationMember victim = getMemberBehind(run, target);
		if ((caster == null) && (victim == null))
		{
			return;
		}

		if (caster != null)
		{
			caster.addResisted();
		}
		final JsonLine line = event(run, "resisted");
		who(line, "caster", attacker, caster);
		who(line, "target", target, victim);
		line.put("skill", skill != null ? skill.getName() + " (" + skill.getId() + ")" : null);
		run.getLog().write(SimulationLog.EVENTS, line);
	}

	/**
	 * A cast started (see {@code Creature#beginCast}).
	 */
	public void onCastStart(Creature caster, Skill skill, Creature target, int castTime)
	{
		final SimulationRun run = _run;
		final SimulationMember member = run != null ? run.getMember(caster) : null;
		if (member == null)
		{
			return;
		}

		final SimulationMember victim = getMemberBehind(run, target);
		final JsonLine line = event(run, "cast_start");
		who(line, "caster", caster, member);
		who(line, "target", target, victim);
		line.put("skill", skill.getName() + " (" + skill.getId() + "-" + skill.getLevel() + ")").put("castTime", castTime).put("targetInvul", target != null ? target.isInvul() : null).put("gap", (target != null) && (target != caster) ? (int) caster.calculateDistance2D(target) : null);
		run.getLog().write(SimulationLog.EVENTS, line);
		if (target != caster)
		{
			run.getMonitor().onCast(member, target, skill);
		}
	}

	/**
	 * A cast was interrupted (see {@code Creature#abortCast}).
	 */
	public void onCastAbort(Creature caster)
	{
		final SimulationRun run = _run;
		final SimulationMember member = run != null ? run.getMember(caster) : null;
		if (member == null)
		{
			return;
		}

		final Skill skill = caster.getLastSkillCast();
		final JsonLine line = event(run, "cast_abort");
		who(line, "caster", caster, member);
		line.put("skill", skill != null ? skill.getName() + " (" + skill.getId() + ")" : null);
		run.getLog().write(SimulationLog.EVENTS, line);
	}

	/**
	 * A cast finished (see {@code Creature#onMagicFinalizer}).
	 */
	public void onCastEnd(Creature caster, Skill skill)
	{
		final SimulationRun run = _run;
		final SimulationMember member = run != null ? run.getMember(caster) : null;
		if (member == null)
		{
			return;
		}

		final JsonLine line = event(run, "cast_end");
		who(line, "caster", caster, member);
		line.put("skill", skill != null ? skill.getName() + " (" + skill.getId() + ")" : null);
		run.getLog().write(SimulationLog.EVENTS, line);
	}

	/**
	 * A death (see {@code Creature#doDie}).
	 */
	public void onDeath(Creature victim, Creature killer)
	{
		final SimulationRun run = _run;
		final SimulationMember member = run != null ? run.getMember(victim) : null;
		if (member == null)
		{
			return;
		}

		final long time = run.time();
		member.onDeath(time);
		final SimulationMember slayer = getMemberBehind(run, killer);
		if ((slayer != null) && (slayer != member))
		{
			slayer.addKill();
		}

		final JsonLine line = event(run, "death");
		who(line, "victim", victim, member);
		line.put("victimRole", member.getRole()).put("victimBuild", member.getBuild());
		who(line, "killer", killer, slayer);
		line.put("killerBuild", slayer != null ? slayer.getBuild() : null);
		final Skill skill = killer != null ? killer.getLastSkillCast() : null;
		line.put("killerLastSkill", skill != null ? skill.getName() : null);
		run.addKill(line, String.format("%.1fs ", time / 1000.0) + (killer != null ? killer.getName() : "?") + (slayer != null ? " (team " + slayer.getTeamId() + ", " + slayer.getBuild() + ")" : "") + " killed " + victim.getName() + " (team " + member.getTeamId() + ", " + member.getBuild() + ")" + (skill != null ? " with " + skill.getName() : ""));
		run.getLog().write(SimulationLog.EVENTS, line);
		run.getMonitor().onDeath(member);
	}

	/**
	 * A resurrection (see {@code Creature#doRevive}).
	 */
	public void onRevive(Creature creature)
	{
		final SimulationRun run = _run;
		final SimulationMember member = run != null ? run.getMember(creature) : null;
		if ((member == null) || (run.getPhase() == SimulationRun.Phase.ENDED))
		{
			return;
		}

		final JsonLine line = event(run, "resurrect");
		who(line, "target", creature, member);
		run.getLog().write(SimulationLog.EVENTS, line);
	}

	/**
	 * A path search found no path (see {@code Creature#moveToLocation}).
	 * @param creature who moves
	 * @param x where to
	 * @param y where to
	 * @param z where to
	 * @param fallback {@code true} if it goes to the closest reachable point instead
	 */
	public void onPathFail(Creature creature, int x, int y, int z, boolean fallback)
	{
		final SimulationRun run = _run;
		final SimulationMember member = run != null ? run.getMember(creature) : null;
		if (member != null)
		{
			run.getMonitor().onPathFail(member, x, y, z, fallback);
		}
	}

	/**
	 * @return the decision time of a member, most first (for the summary)
	 */
	static List<Map.Entry<Reason, Long>> sortedReasons(SimulationMember member)
	{
		final List<Map.Entry<Reason, Long>> entries = new ArrayList<>(new EnumMap<>(member.getReasonTime()).entrySet());
		entries.sort(Map.Entry.<Reason, Long> comparingByValue(Comparator.reverseOrder()));
		return entries;
	}

	public static FakeSimulationManager getInstance()
	{
		return SingletonHolder.INSTANCE;
	}

	private static class SingletonHolder
	{
		protected static final FakeSimulationManager INSTANCE = new FakeSimulationManager();
	}
}
