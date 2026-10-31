/*
 * Copyright (c) 2013 L2jMobius
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be
 * included in all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY,
 * WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR
 * IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
 */
package org.l2jmobius.gameserver.managers.conquest;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.l2jmobius.commons.threads.ThreadPool;
import org.l2jmobius.commons.util.Rnd;
import org.l2jmobius.gameserver.ai.AttackableAI;
import org.l2jmobius.gameserver.ai.FakeBrain;
import org.l2jmobius.gameserver.ai.Intention;
import org.l2jmobius.gameserver.config.custom.ConquestConfig;
import org.l2jmobius.gameserver.data.xml.DoorData;
import org.l2jmobius.gameserver.geoengine.GeoEngine;
import org.l2jmobius.gameserver.managers.CombatLogManager;
import org.l2jmobius.gameserver.managers.FakePartyManager;
import org.l2jmobius.gameserver.managers.FakePlayerPvpManager;
import org.l2jmobius.gameserver.managers.InstanceManager;
import org.l2jmobius.gameserver.managers.BuffTemplateManager;
import org.l2jmobius.gameserver.managers.TeamEventHud;
import org.l2jmobius.gameserver.managers.TeamVsTeamManager;
import org.l2jmobius.gameserver.managers.TeamVsTeamManager.Relation;
import org.l2jmobius.gameserver.managers.simulation.SimRole;
import org.l2jmobius.gameserver.managers.simulation.SimulationComposer;
import org.l2jmobius.gameserver.model.Location;
import org.l2jmobius.gameserver.model.StatSet;
import org.l2jmobius.gameserver.model.World;
import org.l2jmobius.gameserver.model.WorldRegion;
import org.l2jmobius.gameserver.model.actor.Creature;
import org.l2jmobius.gameserver.model.actor.Npc;
import org.l2jmobius.gameserver.model.actor.Player;
import org.l2jmobius.gameserver.model.actor.enums.creature.Team;
import org.l2jmobius.gameserver.model.actor.holders.npc.FakePlayerParty;
import org.l2jmobius.gameserver.model.actor.holders.npc.FakePlayerPvpBuild;
import org.l2jmobius.gameserver.model.actor.holders.npc.FakePlayerPvpPersonality;
import org.l2jmobius.gameserver.model.actor.holders.npc.FakePlayerPvpProfile;
import org.l2jmobius.gameserver.model.actor.instance.Door;
import org.l2jmobius.gameserver.model.conquest.ConquestDoor;
import org.l2jmobius.gameserver.model.conquest.ConquestMap;
import org.l2jmobius.gameserver.model.conquest.ConquestPoint;
import org.l2jmobius.gameserver.model.conquest.ConquestPointType;
import org.l2jmobius.gameserver.model.groups.Party;
import org.l2jmobius.gameserver.model.instancezone.Instance;
import org.l2jmobius.gameserver.model.item.enums.ItemProcessType;
import org.l2jmobius.gameserver.model.item.holders.ItemChanceHolder;
import org.l2jmobius.gameserver.model.item.holders.ItemHolder;
import org.l2jmobius.gameserver.model.skill.CommonSkill;
import org.l2jmobius.gameserver.model.skill.Skill;
import org.l2jmobius.gameserver.model.skill.enums.SkillFinishType;
import org.l2jmobius.gameserver.model.skill.holders.SkillHolder;
import org.l2jmobius.gameserver.network.serverpackets.Ex2RadarZoneProgress;
import org.l2jmobius.gameserver.network.serverpackets.Ex2TeamEventState;
import org.l2jmobius.gameserver.network.serverpackets.ExShowScreenMessage;
import org.l2jmobius.gameserver.network.serverpackets.MagicSkillUse;
import org.l2jmobius.gameserver.taskmanagers.DecayTaskManager;
import org.l2jmobius.gameserver.util.QuestSpawnUtil;

/**
 * Conquest: a match on a map of {@link org.l2jmobius.gameserver.data.xml.ConquestMapData}, for the players that registered (see {@link ConquestRegistration}), or of fake players only when a GM starts it (//conquest &lt;map&gt; start). It runs in its own
 * copy of the map's place, without monsters, with the doors the map names opened or shut. The fighters play in parties of 5: a party of players is led by its leader and topped up with fake players in the roles it lacks, the rest of each team are parties of
 * fake players, both teams the same (roles, builds, levels) as far as the players let them. Several matches can run at once, each in its own copy (one for each level bracket players registered in).
 * <ul>
 * <li>Each team starts with {@link ConquestConfig#START_SCORE}. Every second each loses max(1, the capture points the other team holds - those it holds); a team at 0 loses, and when the time runs out the higher score wins.</li>
 * <li>A capture point goes over to a team as {@link ConquestCapture} says, through neutral.</li>
 * <li>The dead come back in waves at their team's spawn points. Nobody fights from inside its own base (around its spawn points), whoever walks into an enemy base is sent to its own, and whoever leaves the map's area is put back in it.</li>
 * <li>Each team's {@link ConquestCommander} sends its parties to the capture points or the enemy; the parties fight what they meet on their own (the fake player and party AIs), their leaders marching across the map along {@link ConquestNavGrid}.</li>
 * </ul>
 */
public class ConquestManager
{
	private static final Logger LOGGER = Logger.getLogger(ConquestManager.class.getName());

	/** data/instances/custom/conquest_event.xml: a PvP instance without spawns or doors. */
	public static final int INSTANCE_TEMPLATE_ID = 3052;
	/** The size of the parties. */
	public static final int PARTY_SIZE = 5;

	/** {@code true} while a match goes on: the combat hooks only look further then. */
	public static volatile boolean ACTIVE = false;

	private static final long TICK_MS = 1000;
	/** The commanders are given their turns in steps this long (each when its own interval is up). */
	public static final long COMMAND_STEP_MS = 250;
	/** The fake players are spawned this many at a time, every so many ms, so hundreds of them don't arrive in one go. */
	private static final int SPAWN_BATCH = 20;
	private static final long SPAWN_INTERVAL = 250;
	/** A fake player engages an enemy it sees this close (less far the less it knows the game), or one this close seen or not. */
	private static final int SIGHT_RANGE = 1200;
	private static final double SIGHT_KNOWLEDGE_LOSS = 0.2;
	private static final int CLOSE_ENEMY_RANGE = 150;
	/** Of the enemies in range, only the closest few are checked for line of sight (the costly part). */
	private static final int SIGHT_CANDIDATES = 6;
	private static final double LOW_TARGET_BONUS = 0.3;
	/** The enemy one saw is kept this long before it looks again. */
	private static final long SEEN_CACHE = 500;
	/** Outside the map's area by more than this, a fighter is put back, this far inside the edge. */
	private static final int BOUNDARY_MARGIN = 100;
	private static final int BOUNDARY_INSET = 200;
	/** Before the fight, nobody goes farther than this from its spawn point's base. */
	private static final int PREP_LEASH = 300;
	/** The regions around the map's area kept awake: the fake players' AI only runs in active regions. */
	private static final int REGION_MARGIN = 1000;
	private static final SkillHolder GHOST_WALKING = new SkillHolder(100000, 1); // Spawn protection look (Ultimate Defense effect).
	/** A party spawns around a spot of its own in its spawn point, its members this close to it. */
	private static final int PARTY_SPREAD = 120;
	/** Where a fighter is put, the ground is looked at on a circle this wide around it, at so many spots, and the highest (at most this much higher) taken: on an uneven floor the geodata under one spot can lie below the floor that is seen. */
	private static final int FLOOR_RADIUS = 40;
	private static final int FLOOR_SAMPLES = 8;
	private static final int FLOOR_STEP = 120;
	/** A fighter found off the ground (inside a wall, in the rock) this many ticks in a row is put back on the nearest ground: one moment off it may be a knock back or a jump down. */
	private static final int OFF_GROUND_TICKS = 2;
	/** A servitor left farther behind than this (or in another instance) is brought back next to its owner. */
	private static final int SERVITOR_LEASH = 900;

	/** One fake player to spawn. */
	private static class Slot
	{
		final SimRole role;
		final FakePlayerPvpBuild build;
		final int level;

		Slot(SimRole role, FakePlayerPvpBuild build, int level)
		{
			this.role = role;
			this.build = build;
			this.level = level;
		}
	}

	/** A party to spawn: its players (the leader first), and the fake players. */
	private static class PartyPlan
	{
		final ConquestTeam team;
		final int number;
		final List<Slot> slots;
		final List<Player> players;

		PartyPlan(ConquestTeam team, int number, List<Slot> slots, List<Player> players)
		{
			this.team = team;
			this.number = number;
			this.slots = slots;
			this.players = players;
		}
	}

	private final Map<Integer, ConquestFighter> _fighters = new ConcurrentHashMap<>();
	private final AtomicInteger _nextMatchId = new AtomicInteger(1);
	/** The parties each match still has to spawn, by match id. */
	private final Map<Integer, Deque<PartyPlan>> _toSpawn = new ConcurrentHashMap<>();
	/** The matches running (or ending), by id. */
	private final Map<Integer, ConquestMatch> _matches = new ConcurrentHashMap<>();
	// The brain of each team in the next matches, null for the one Conquest.ini names.
	private volatile Supplier<ConquestCommander> _blueBrain;
	private volatile Supplier<ConquestCommander> _redBrain;
	// The results of each pairing of brains: "blue brain vs red brain" -> {blue wins, red wins, draws}.
	private final Map<String, int[]> _brainResults = new ConcurrentHashMap<>();

	protected ConquestManager()
	{
	}

	// ---------------------------------------------------------------------------------------------
	// Start and stop
	// ---------------------------------------------------------------------------------------------

	/**
	 * Starts a match of fake players only.
	 * @param map the map
	 * @param perTeam the fighters of each team (rounded down to whole parties; at most the map's maxPlayers)
	 * @param minLevel the lowest level of the fake players
	 * @param maxLevel the highest
	 * @return what to tell the GM
	 */
	public String start(ConquestMap map, int perTeam, int minLevel, int maxLevel)
	{
		final String refusal = getStartRefusal(map);
		if (refusal != null)
		{
			return refusal;
		}
		final ConquestMatch match = start(map, perTeam, minLevel, maxLevel, List.of(), List.of());
		if (match == null)
		{
			return "Could not create the instance (data/instances/custom/conquest_event.xml, id " + INSTANCE_TEMPLATE_ID + ").";
		}
		final int size = match.getPerTeam();
		return "Conquest on " + map.name() + ": " + size + " vs " + size + " fake players, levels " + minLevel + "-" + maxLevel + " (match " + match.getId() + "). The fight starts in " + ConquestConfig.PREP_TIME + " seconds. //conquest live shows it, //conquest watch takes you there.";
	}

	/**
	 * @param map a map
	 * @return why no match can start on it, {@code null} if one can
	 */
	public static String getStartRefusal(ConquestMap map)
	{
		if (!FakePlayerPvpManager.getInstance().isEnabled())
		{
			return "Conquest needs the fake players: EnableFakePlayers in FakePlayers.ini and FakePvpEnabled in FakePlayerPvp.ini.";
		}
		if (map.getPoints(ConquestPointType.BLUE).isEmpty() || map.getPoints(ConquestPointType.RED).isEmpty() || map.getPoints(ConquestPointType.CAPTURE).isEmpty())
		{
			return map.name() + " needs spawn points for both teams and at least one capture point.";
		}
		return null;
	}

	/**
	 * Starts a match.
	 * @param map the map
	 * @param perTeam the fighters of each team, players and fake players (rounded down to whole parties; at most the map's maxPlayers)
	 * @param minLevel the lowest level of the fake players
	 * @param maxLevel the highest
	 * @param blueGroups the players of Blue: a party (the leader first) or a lone player each, still registered on the event (see {@link ConquestRegistration#close})
	 * @param redGroups the players of Red
	 * @return the match, {@code null} if it could not start (see {@link #getStartRefusal}, or no instance)
	 */
	public synchronized ConquestMatch start(ConquestMap map, int perTeam, int minLevel, int maxLevel, List<List<Player>> blueGroups, List<List<Player>> redGroups)
	{
		if (getStartRefusal(map) != null)
		{
			return null;
		}

		final int size = Math.max(PARTY_SIZE, (Math.min(perTeam, map.maxPlayers()) / PARTY_SIZE) * PARTY_SIZE);
		final Instance instance = InstanceManager.getInstance().createDynamicInstance(INSTANCE_TEMPLATE_ID);
		if (instance == null)
		{
			return null;
		}

		// A match the registration started (it has players) is led on both sides by ConquestRegistrationBrain; a GM's by the brains of the next matches.
		final boolean registered = !blueGroups.isEmpty() || !redGroups.isEmpty();
		final boolean registrationBrain = registered && !ConquestConfig.REGISTRATION_BRAIN.isEmpty();
		final ConquestCommander blueBrain = registrationBrain ? makeBrain(null, ConquestConfig.REGISTRATION_BRAIN) : makeBrain(_blueBrain, ConquestConfig.BLUE_BRAIN);
		final ConquestCommander redBrain = registrationBrain ? makeBrain(null, ConquestConfig.REGISTRATION_BRAIN) : makeBrain(_redBrain, ConquestConfig.RED_BRAIN);
		final ConquestMatch match = new ConquestMatch(_nextMatchId.getAndIncrement(), map, instance.getId(), size, minLevel, maxLevel, blueBrain, redBrain);
		match._withPlayers = registered;
		setUpDoors(map, instance);
		keepDoors(match);
		_matches.put(match.getId(), match);
		ACTIVE = true;
		keepRegionsAwake(match);

		// The ways across the map, through the doors as the map set them: a moment of work on a big map, done while the teams spawn.
		ThreadPool.execute(() ->
		{
			final long start = System.currentTimeMillis();
			try
			{
				match.getNav().build(map, instance.getId());
				LOGGER.info(getClass().getSimpleName() + ": " + map.name() + ": " + match.getNav().getWalkableCells() + " walkable cells in " + (System.currentTimeMillis() - start) + " ms.");
			}
			catch (Exception e)
			{
				LOGGER.log(Level.WARNING, getClass().getSimpleName() + ": Could not work out the ways across " + map.name() + ".", e);
			}
		});

		// The players' parties first (they are taken in at once), topped up with the fake players they lack. Then parties of fake players fill both teams, the same roles, builds and levels party for party.
		final ThreadLocalRandom random = ThreadLocalRandom.current();
		final Deque<PartyPlan> toSpawn = new ArrayDeque<>();
		_toSpawn.put(match.getId(), toSpawn);
		synchronized (toSpawn)
		{
			final List<PartyPlan> bluePlans = planPlayerParties(match.getBlue(), blueGroups, size, minLevel, maxLevel, random);
			final List<PartyPlan> redPlans = planPlayerParties(match.getRed(), redGroups, size, minLevel, maxLevel, random);
			toSpawn.addAll(bluePlans);
			toSpawn.addAll(redPlans);

			int blueFree = size - countPlanned(bluePlans);
			int redFree = size - countPlanned(redPlans);
			int blueNumber = bluePlans.size();
			int redNumber = redPlans.size();
			while ((blueFree > 0) || (redFree > 0))
			{
				final List<Slot> slots = new ArrayList<>();
				for (SimRole role : SimulationComposer.getRoles(PARTY_SIZE))
				{
					final FakePlayerPvpBuild build = SimulationComposer.pick(role, random);
					if (build != null)
					{
						slots.add(new Slot(role, build, Rnd.get(minLevel, maxLevel)));
					}
				}
				if (blueFree > 0)
				{
					toSpawn.add(new PartyPlan(match.getBlue(), ++blueNumber, slots.subList(0, Math.min(slots.size(), blueFree)), List.of()));
					blueFree -= PARTY_SIZE;
				}
				if (redFree > 0)
				{
					toSpawn.add(new PartyPlan(match.getRed(), ++redNumber, slots.subList(0, Math.min(slots.size(), redFree)), List.of()));
					redFree -= PARTY_SIZE;
				}
			}
		}

		match._prepEnd = System.currentTimeMillis() + (ConquestConfig.PREP_TIME * 1000L);
		match._spawnTask = ThreadPool.scheduleAtFixedRate(() -> spawnSome(match), 0, SPAWN_INTERVAL);
		match._task = ThreadPool.scheduleAtFixedRate(() -> tick(match), TICK_MS, TICK_MS);
		match.log("Match " + match.getId() + " on " + map.name() + ": " + size + " vs " + size + ", levels " + minLevel + "-" + maxLevel + (match._withPlayers ? ", players: Blue " + countPlayers(blueGroups) + ", Red " + countPlayers(redGroups) : "") + ". Brains: Blue " + match.getBlue().getCommander().getName() + ", Red " + match.getRed().getCommander().getName() + ".");
		LOGGER.info(getClass().getSimpleName() + ": Match " + match.getId() + " started on " + map.name() + ", " + size + " vs " + size + ", levels " + minLevel + "-" + maxLevel + ", instance " + instance.getId() + ", brains " + match.getBlue().getCommander().getName() + " vs " + match.getRed().getCommander().getName() + ".");
		return match;
	}

	private static int countPlayers(List<List<Player>> groups)
	{
		int count = 0;
		for (List<Player> group : groups)
		{
			count += group.size();
		}
		return count;
	}

	private static int countPlanned(List<PartyPlan> plans)
	{
		int count = 0;
		for (PartyPlan plan : plans)
		{
			count += plan.players.size() + plan.slots.size();
		}
		return count;
	}

	/**
	 * The parties of a team's players, like a Team vs Team team's: a party of players stays together, its leader leading it; the lone players get a party each while the team has room for parties, else join the smallest of those. Each party gets the fake
	 * players it lacks up to {@link #PARTY_SIZE}, in the roles its players don't fill (a bigger party stays as it is).
	 * @param team the team
	 * @param groups its players' groups, the leader of each first
	 * @param size the places of the team
	 * @param minLevel the lowest level of the fake players
	 * @param maxLevel the highest
	 * @param random the random
	 * @return the parties to spawn, numbered from 1
	 */
	private static List<PartyPlan> planPlayerParties(ConquestTeam team, List<List<Player>> groups, int size, int minLevel, int maxLevel, ThreadLocalRandom random)
	{
		final List<PartyPlan> plans = new ArrayList<>();
		if (groups.isEmpty())
		{
			return plans;
		}

		final int partyCount = Math.max(1, size / PARTY_SIZE);
		final List<List<Player>> parties = new ArrayList<>();
		final List<List<Player>> soloParties = new ArrayList<>();
		final List<Player> solos = new ArrayList<>();
		for (List<Player> group : groups)
		{
			if (group.size() > 1)
			{
				parties.add(new ArrayList<>(group));
			}
			else
			{
				solos.addAll(group);
			}
		}
		for (Player solo : solos)
		{
			if ((parties.size() + soloParties.size()) < partyCount)
			{
				final List<Player> party = new ArrayList<>();
				party.add(solo);
				soloParties.add(party);
				continue;
			}

			// The solo party with the fewest players takes it (a party holds 9).
			List<Player> smallest = null;
			for (List<Player> party : soloParties)
			{
				if ((party.size() < 9) && ((smallest == null) || (party.size() < smallest.size())))
				{
					smallest = party;
				}
			}
			if (smallest == null)
			{
				smallest = new ArrayList<>();
				soloParties.add(smallest);
			}
			smallest.add(solo);
		}
		parties.addAll(soloParties);

		int slots = Math.max(0, size - countPlayers(groups));
		for (List<Player> members : parties)
		{
			final int wanted = Math.min(slots, Math.max(0, PARTY_SIZE - members.size()));
			final List<SimRole> roles = new ArrayList<>(SimulationComposer.getRoles(members.size() + wanted));
			for (Player player : members)
			{
				roles.remove(TeamVsTeamManager.getRole(player));
			}
			while (roles.size() > wanted)
			{
				roles.remove(roles.size() - 1);
			}

			final List<Slot> fakes = new ArrayList<>();
			for (SimRole role : roles)
			{
				final FakePlayerPvpBuild build = SimulationComposer.pick(role, random);
				if (build != null)
				{
					fakes.add(new Slot(role, build, Rnd.get(minLevel, maxLevel)));
				}
			}
			slots -= wanted;
			plans.add(new PartyPlan(team, plans.size() + 1, fakes, members));
		}
		return plans;
	}

	/**
	 * The doors the map names, in the match's copy of its place: an instance only has the doors added to it, closed or open as data/Doors.xml has them, so each is set here as the map says.
	 */
	private static void setUpDoors(ConquestMap map, Instance instance)
	{
		final List<ConquestDoor> doors = new ArrayList<>();
		for (ConquestDoor door : map.doors())
		{
			if (DoorData.getInstance().getDoorTemplate(door.id()) == null)
			{
				LOGGER.warning(ConquestManager.class.getSimpleName() + ": " + map.id() + " names door " + door.id() + ", which is not in data/Doors.xml.");
				continue;
			}
			final StatSet set = new StatSet();
			set.set("DoorId", door.id());
			instance.addDoor(set);
			doors.add(door);
		}
		if (doors.isEmpty())
		{
			return;
		}

		instance.spawnDoors();
	}

	/**
	 * Sets the doors of the match's instance as the map says, again at every tick. A door is opened without its close timer ({@code Door#openMe} starts one: the temple's corridor doors shut again after 10 seconds), and one shut or opened by anything else
	 * goes back.
	 */
	private static void keepDoors(ConquestMatch match)
	{
		final Instance instance = InstanceManager.getInstance().getInstance(match.getInstanceId());
		if (instance == null)
		{
			return;
		}
		for (ConquestDoor setting : match.getMap().doors())
		{
			final Door door = instance.getDoor(setting.id());
			if ((door == null) || (door.isOpen() == setting.open()))
			{
				continue;
			}
			if (setting.open())
			{
				door.setOpen(true);
				door.broadcastStatusUpdate();
			}
			else
			{
				door.closeMe();
			}
		}
	}

	/**
	 * Spawns the next few fake players (whole parties).
	 */
	private void spawnSome(ConquestMatch match)
	{
		try
		{
			int spawned = 0;
			while (spawned < SPAWN_BATCH)
			{
				final Deque<PartyPlan> toSpawn = _toSpawn.get(match.getId());
				PartyPlan plan = null;
				if (toSpawn != null)
				{
					synchronized (toSpawn)
					{
						plan = toSpawn.pollFirst();
					}
				}
				if ((plan == null) || (match._phase == ConquestMatch.Phase.ENDED))
				{
					if (match._spawnTask != null)
					{
						match._spawnTask.cancel(false);
						match._spawnTask = null;
					}
					return;
				}
				spawnParty(match, plan);
				spawned += plan.slots.size() + plan.players.size();
			}
		}
		catch (Exception e)
		{
			LOGGER.log(Level.WARNING, getClass().getSimpleName() + ": Problem while spawning match " + match.getId() + ".", e);
		}
	}

	private void spawnParty(ConquestMatch match, PartyPlan plan)
	{
		final ConquestTeam team = plan.team;
		final ConquestPoint spawn = team.getSpawns().get((plan.number - 1) % team.getSpawns().size());
		final Location base = new Location(spawn.x(), spawn.y(), spawn.z(), spawn.heading());
		final ConquestParty party = new ConquestParty(plan.number, team);
		final List<Npc> fakes = new ArrayList<>();
		// The party together: around a spot of its own in the spawn point.
		final Location anchor = spawnSpot(base, Math.max(0, spawn.radius() - PARTY_SPREAD), match.getInstanceId());

		// Its players that are still there and free to come (the place of one that isn't goes to a fake player of its role), out of their parties: the party is made afresh, its leader leading it.
		final List<Player> players = new ArrayList<>();
		final List<Slot> slots = new ArrayList<>(plan.slots);
		for (Player player : plan.players)
		{
			final String refusal = player.isOnline() && (match._phase != ConquestMatch.Phase.ENDED) ? ConquestRegistration.getJoinRefusal(player) : "";
			if (refusal == null)
			{
				players.add(player);
				continue;
			}

			player.setRegisteredOnEvent(false);
			if (!refusal.isEmpty())
			{
				player.sendMessage("Conquest: You can't take part: " + refusal);
			}
			final SimRole role = TeamVsTeamManager.getRole(player);
			final FakePlayerPvpBuild build = SimulationComposer.pick(role, ThreadLocalRandom.current());
			if (build != null)
			{
				slots.add(new Slot(role, build, Rnd.get(match.getMinLevel(), match.getMaxLevel())));
			}
		}
		players.forEach(Player::leaveParty);
		for (Player player : players)
		{
			enterPlayer(match, party, player, anchor, spawn.heading());
		}
		if (players.size() > 1)
		{
			final Player leader = players.get(0);
			final Party group = new Party(leader, leader.getPartyDistributionType());
			leader.setParty(group);
			for (int i = 1; i < players.size(); i++)
			{
				players.get(i).joinParty(group);
			}
		}

		for (Slot slot : slots)
		{
			final Location location = spawnSpot(anchor, PARTY_SPREAD, match.getInstanceId());
			final Npc fake = FakePlayerPvpManager.getInstance().spawnConquestFighter(slot.build, slot.level, team.getName(), match.getId(), team.getTeam(), team.getNameColor(), location.getX(), location.getY(), location.getZ(), spawn.heading(), match.getInstanceId());
			if (fake == null)
			{
				continue;
			}

			final ConquestFighter fighter = new ConquestFighter(fake, team, slot.role);
			team.getFighters().add(fighter);
			_fighters.put(fake.getObjectId(), fighter);
			party.addMember(fighter);
			fakes.add(fake);
		}
		if (fakes.isEmpty() && players.isEmpty())
		{
			return;
		}

		// Stopped while it spawned: the cleanup has gone by already.
		if ((match._phase == ConquestMatch.Phase.ENDED) || (_matches.get(match.getId()) != match))
		{
			for (ConquestFighter fighter : party.getMembers())
			{
				if (fighter.isPlayer())
				{
					restorePlayer(match, fighter, true);
				}
				else
				{
					removeFake(fighter.getNpc());
				}
				_fighters.remove(fighter.getCreature().getObjectId(), fighter);
				team.getFighters().remove(fighter);
			}
			return;
		}

		// A party of players: its fake players heal, buff and follow its leader like invited ones. One of fake players only: it follows its commander's orders.
		if (!players.isEmpty())
		{
			if (!fakes.isEmpty())
			{
				party.setFakeParty(FakePartyManager.getInstance().createEventParty(players.get(0), fakes));
			}
		}
		else if (fakes.size() > 1)
		{
			party.setFakeParty(FakePartyManager.getInstance().createSimulationParty(fakes, FakeBrain.A));
		}
		team.getParties().add(party);
		if (match._phase == ConquestMatch.Phase.FIGHT)
		{
			for (Npc fake : fakes)
			{
				fake.setRunning();
				fake.getAI().setIntention(Intention.ACTIVE);
			}
		}
	}

	// ---------------------------------------------------------------------------------------------
	// Players
	// ---------------------------------------------------------------------------------------------

	/**
	 * Takes a player into the match: its team's name color and circle, a spot of its party in the spawn point, its TvT Event buffs and full HP.
	 */
	private void enterPlayer(ConquestMatch match, ConquestParty party, Player player, Location anchor, int heading)
	{
		final ConquestTeam team = party.getTeam();
		final ConquestFighter fighter = new ConquestFighter(player, team, TeamVsTeamManager.getRole(player));
		fighter._returnLocation = new Location(player.getX(), player.getY(), player.getZ(), player.getHeading());
		fighter._nameColor = player.getAppearance().getNameColor();
		team.getFighters().add(fighter);
		_fighters.put(player.getObjectId(), fighter);
		party.addMember(fighter);

		player.setRegisteredOnEvent(false);
		player.setOnEvent(true);
		// No "To Village" when it dies: the dead come back with their team's waves (the button took a player out of the match).
		player.setCanRevive(false);
		if (player.isDead())
		{
			player.doRevive();
		}
		if (player.isSitting())
		{
			player.standUp();
		}

		player.getAppearance().setNameColor(team.getNameColor());
		player.setTeam(team.getTeam());
		final Location spot = spawnSpot(anchor, PARTY_SPREAD, match.getInstanceId());
		player.teleToLocation(spot.getX(), spot.getY(), spot.getZ(), heading, match.getInstanceId(), 0);
		buffAndHeal(player);
		player.broadcastUserInfo();

		final int prep = (int) Math.max(0, (match._prepEnd - System.currentTimeMillis()) / 1000);
		TeamEventHud.sendState(player, state(match, Ex2TeamEventState.RESULT_NONE), team.getTeam());
		showZones(match, player);
		sendScreen(player, "You fight for team " + team.getName() + ". The fight starts in " + prep + " seconds.", 8, true);
		player.sendMessage("Conquest: Team " + team.getName() + " on " + match.getMap().name() + ", " + match.getPerTeam() + " vs " + match.getPerTeam() + ". Every capture point the other team holds more than yours drains your score; a team at 0 loses. The other team is attacked without Ctrl.");
		final BuffTemplateManager templates = BuffTemplateManager.getInstance();
		if (templates.isTvtTemplateEnabled() && templates.getTemplate(player, BuffTemplateManager.TVT_SLOT).skillIds().isEmpty())
		{
			player.sendMessage("Conquest: Your TvT Event buff template is empty. Fill it on the Community Board (Buff Templates) to get those buffs whenever you come back at a spawn point.");
		}
	}

	/**
	 * Gives a player its TvT Event buff template (see {@link BuffTemplateManager#applyTvtTemplate}), then full HP, CP and MP.
	 * @param player the player, alive
	 */
	private static void buffAndHeal(Player player)
	{
		BuffTemplateManager.getInstance().applyTvtTemplate(player);
		player.setCurrentHpMp(player.getMaxHp(), player.getMaxMp());
		player.setCurrentCp(player.getMaxCp());
	}

	private static void sendScreen(Player player, String text, int seconds, boolean effect)
	{
		player.sendPacket(new ExShowScreenMessage(text, ExShowScreenMessage.TOP_CENTER, seconds * 1000, 0, true, effect));
	}

	/**
	 * @return {@code false} if the player left the match (it went offline or out of the instance): it is taken out of it
	 */
	private boolean checkPlayerStillIn(ConquestMatch match, ConquestFighter fighter)
	{
		final Player player = fighter.getCreature().asPlayer();
		if (player.isOnline() && !player.isInOfflineMode() && ((player.getInstanceId() == match.getInstanceId()) || player.isTeleporting()))
		{
			return true;
		}

		// One that went offline logs in again where it was before the match.
		leave(match, fighter, !player.isOnline());
		return false;
	}

	/**
	 * Gives a player back its state from before the match.
	 * @param match the match
	 * @param fighter the player's fighter
	 * @param teleport {@code true} to send it back where it was
	 */
	private static void restorePlayer(ConquestMatch match, ConquestFighter fighter, boolean teleport)
	{
		final Player player = fighter.getCreature().asPlayer();
		unprotect(fighter);
		player.setInvul(false);
		player.setTeam(Team.NONE);
		player.getAppearance().setNameColor(fighter._nameColor);
		player.setOnEvent(false);
		player.setRegisteredOnEvent(false);
		player.setCanRevive(true);
		player.leaveParty();
		if (player.isDead())
		{
			player.doRevive();
		}
		player.setCurrentHpMp(player.getMaxHp(), player.getMaxMp());
		player.setCurrentCp(player.getMaxCp());
		TeamEventHud.close(player, zoneIds(match));

		final Instance instance = InstanceManager.getInstance().getInstance(match.getInstanceId());
		if (instance != null)
		{
			instance.removePlayer(player.getObjectId());
		}

		final Location back = fighter._returnLocation;
		if (teleport && player.isOnline() && (back != null))
		{
			player.teleToLocation(back.getX(), back.getY(), back.getZ(), back.getHeading(), 0, 0);
		}
		player.broadcastUserInfo();
	}

	/**
	 * A player leaves the match before its end (logged off, left the instance): it gets its state back, and a fake player of its role takes its place in its party.
	 * @param match the match
	 * @param fighter the player's fighter
	 * @param loggingOff {@code true} if it logs off: it logs in again where it was before the match
	 */
	private void leave(ConquestMatch match, ConquestFighter fighter, boolean loggingOff)
	{
		final Player player = fighter.getCreature().asPlayer();
		if (!_fighters.remove(player.getObjectId(), fighter))
		{
			return;
		}
		final ConquestTeam team = fighter.getTeam();
		team.getFighters().remove(fighter);
		final ConquestParty party = fighter.getParty();
		if (party != null)
		{
			party.removeMember(fighter);
		}

		// The fake players it led stay in the match, as a party of their own that follows the commander.
		final FakePlayerParty fakeParty = party != null ? party.getFakeParty() : null;
		if ((fakeParty != null) && (fakeParty.getHost() == player))
		{
			final List<Npc> fakes = new ArrayList<>(fakeParty.getFakes());
			FakePartyManager.getInstance().disbandEventParty(fakeParty);
			party.setFakeParty(fakes.size() > 1 ? FakePartyManager.getInstance().createSimulationParty(fakes, FakeBrain.A) : null);
		}

		restorePlayer(match, fighter, false);
		if (loggingOff && (fighter._returnLocation != null))
		{
			player.setInstanceId(0);
			player.setXYZInvisible(fighter._returnLocation.getX(), fighter._returnLocation.getY(), fighter._returnLocation.getZ());
		}

		// A fake player of its role takes its place.
		if ((match._phase != ConquestMatch.Phase.ENDED) && (party != null))
		{
			final FakePlayerPvpBuild build = SimulationComposer.pick(fighter.getRole(), ThreadLocalRandom.current());
			final ConquestPoint spawn = team.getClosestSpawn(player);
			final Location location = spawnSpot(new Location(spawn.x(), spawn.y(), spawn.z(), spawn.heading()), spawn.radius(), match.getInstanceId());
			final Npc fake = build == null ? null : FakePlayerPvpManager.getInstance().spawnConquestFighter(build, Rnd.get(match.getMinLevel(), match.getMaxLevel()), team.getName(), match.getId(), team.getTeam(), team.getNameColor(), location.getX(), location.getY(), location.getZ(), spawn.heading(), match.getInstanceId());
			if (fake != null)
			{
				final ConquestFighter replacement = new ConquestFighter(fake, team, fighter.getRole());
				team.getFighters().add(replacement);
				_fighters.put(fake.getObjectId(), replacement);
				party.addMember(replacement);
				final FakePlayerParty current = party.getFakeParty();
				if (current == null)
				{
					final List<Npc> fakes = new ArrayList<>();
					for (ConquestFighter member : party.getMembers())
					{
						if (!member.isPlayer())
						{
							fakes.add(member.getNpc());
						}
					}
					if (fakes.size() > 1)
					{
						party.setFakeParty(FakePartyManager.getInstance().createSimulationParty(fakes, FakeBrain.A));
					}
				}
				else if (current.isFakeOnly())
				{
					FakePartyManager.getInstance().addToSimulationParty(current, fake);
				}
				else
				{
					FakePartyManager.getInstance().addToEventParty(current, fake);
				}
				if (match._phase == ConquestMatch.Phase.FIGHT)
				{
					fake.setRunning();
					fake.getAI().setIntention(Intention.ACTIVE);
					protect(replacement);
				}
			}
		}

		match.log(player.getName() + " left the match.");
		for (Player other : match.getPlayers())
		{
			other.sendMessage("Conquest: " + player.getName() + " left the match.");
		}
	}

	/**
	 * Called through {@link ConquestRegistration#onLogout} by {@code Player#deleteMe}: a player in the match leaves it and logs in again where it was before.
	 * @param player the player logging off
	 */
	public void onLogout(Player player)
	{
		final ConquestFighter fighter = _fighters.get(player.getObjectId());
		if ((fighter != null) && fighter.isPlayer())
		{
			leave(fighter.getTeam().getMatch(), fighter, true);
		}
	}

	/**
	 * Ends every match running now, without a result.
	 * @return what to tell the GM
	 */
	public synchronized String stop()
	{
		int stopped = 0;
		for (ConquestMatch match : _matches.values())
		{
			if (match._phase != ConquestMatch.Phase.ENDED)
			{
				endMatch(match, null, "Stopped by a GM.", true);
				stopped++;
			}
		}
		return _matches.isEmpty() ? "No Conquest match is running." : stopped == 0 ? "The matches are ending already." : stopped + " match(es) stopped.";
	}

	/**
	 * Ends one match, without a result.
	 * @param id the match's id
	 * @return what to tell the GM
	 */
	public synchronized String stop(int id)
	{
		final ConquestMatch match = _matches.get(id);
		if (match == null)
		{
			return "No Conquest match " + id + " is running.";
		}
		if (match._phase == ConquestMatch.Phase.ENDED)
		{
			return "Match " + id + " on " + match.getMap().name() + " is ending already.";
		}
		endMatch(match, null, "Stopped by a GM.", true);
		return "Match " + id + " on " + match.getMap().name() + " was stopped.";
	}

	// ---------------------------------------------------------------------------------------------
	// Tick
	// ---------------------------------------------------------------------------------------------

	private void tick(ConquestMatch match)
	{
		final long start = System.nanoTime();
		try
		{
			final long now = System.currentTimeMillis();
			match.rebuildGrid();
			pruneWatchers(match);
			if (match._phase != ConquestMatch.Phase.ENDED)
			{
				keepDoors(match);
				keepServitors(match);
			}
			switch (match._phase)
			{
				case PREP:
				{
					// Nobody leaves its spawn point before the fight.
					for (ConquestFighter fighter : match.getAllFighters())
					{
						if (fighter.isPlayer() && !checkPlayerStillIn(match, fighter))
						{
							continue;
						}
						final Creature npc = fighter.getCreature();
						if (npc.isDead() || npc.isTeleporting())
						{
							continue;
						}
						final ConquestPoint spawn = fighter.getTeam().getClosestSpawn(npc);
						if (Math.hypot(spawn.x() - npc.getX(), spawn.y() - npc.getY()) > (spawn.radius() + ConquestConfig.BASE_RADIUS + PREP_LEASH))
						{
							sendHome(match, fighter, new Location(spawn.x(), spawn.y(), spawn.z(), spawn.heading()), spawn.radius());
							if (fighter.isPlayer())
							{
								sendScreen(npc.asPlayer(), "The fight hasn't started yet!", 4, false);
							}
						}
					}

					// It waits for the last parties to arrive.
					if ((now >= match._prepEnd) && (match._spawnTask == null))
					{
						startFight(match);
					}
					break;
				}
				case FIGHT:
				{
					fightTick(match, now);
					break;
				}
				case ENDED:
				{
					break;
				}
			}
		}
		catch (Exception e)
		{
			LOGGER.log(Level.WARNING, getClass().getSimpleName() + ": Problem in match " + match.getId() + ".", e);
		}
		match._ticks++;
		match._lastTickNanos = System.nanoTime() - start;
	}

	private void startFight(ConquestMatch match)
	{
		final long now = System.currentTimeMillis();
		match._fightStart = now;
		match._fightEnd = now + (ConquestConfig.TIME_LIMIT * 60000L);
		match._phase = ConquestMatch.Phase.FIGHT;
		match.log("The fight begins: " + match.getBlue().getFighters().size() + " vs " + match.getRed().getFighters().size() + ".");
		announce(match, "The fight has begun!");
		match._commandTask = ThreadPool.scheduleAtFixedRate(() -> command(match), 0, COMMAND_STEP_MS);
		sendState(match, Ex2TeamEventState.RESULT_NONE);
		for (ConquestFighter fighter : match.getAllFighters())
		{
			final Npc fake = fighter.getNpc();
			if ((fake != null) && !fake.isDead())
			{
				fake.setRunning();
				fake.getAI().setIntention(Intention.ACTIVE);
			}
		}
	}

	private void fightTick(ConquestMatch match, long now)
	{
		for (ConquestFighter fighter : match.getAllFighters())
		{
			if (fighter.isPlayer() && !checkPlayerStillIn(match, fighter))
			{
				continue;
			}
			final Creature npc = fighter.getCreature();
			if (npc.isDead())
			{
				if (fighter._respawnAt <= 0)
				{
					// Dead without a wave (it died before the fight, or a respawn did not take): the next one takes it, players as fake players.
					fighter.getTeam().joinWave(fighter, now);
					if (fighter.isPlayer())
					{
						npc.sendMessage("Conquest: You come back with your team's next wave in " + (Math.max(0, fighter._respawnAt - now) / 1000) + " seconds.");
					}
				}
				else if (now >= fighter._respawnAt)
				{
					respawn(match, fighter);
				}
				continue;
			}
			fighter._respawnAt = 0; // Resurrected by a team mate.

			if (npc.isTeleporting())
			{
				continue;
			}

			// Nobody leaves the map's area: put back inside, on the nearest ground.
			if (!match.isInside(npc.getX(), npc.getY(), BOUNDARY_MARGIN))
			{
				final ConquestMap map = match.getMap();
				final int x = Math.max(map.minX() + BOUNDARY_INSET, Math.min(map.maxX() - BOUNDARY_INSET, npc.getX()));
				final int y = Math.max(map.minY() + BOUNDARY_INSET, Math.min(map.maxY() - BOUNDARY_INSET, npc.getY()));
				sendHome(match, fighter, match.getNav().nearestGround(x, y, npc.getZ()), 0);
				match._returned++;
				if (fighter.isPlayer())
				{
					sendScreen(npc.asPlayer(), "You can't leave the battlefield!", 4, false);
				}
				continue;
			}

			// Nobody stays inside a wall or the rock (a knock back, a skill, a move with no way there): back on the nearest ground. A player's own client keeps it on the ground.
			if (fighter.isPlayer())
			{
				fighter._offGround = 0;
			}
			else if (!match.getNav().isOnGround(npc.getX(), npc.getY(), npc.getZ()))
			{
				if (++fighter._offGround >= OFF_GROUND_TICKS)
				{
					fighter._offGround = 0;
					sendHome(match, fighter, match.getNav().nearestGround(npc.getX(), npc.getY(), npc.getZ()), 0);
					match._unstuck++;
					continue;
				}
			}
			else
			{
				fighter._offGround = 0;
			}

			// No camping in front of the other team's spawn: whoever walks into one of its bases goes back to its own closest one.
			if (match.getOther(fighter.getTeam()).isInBase(npc.getX(), npc.getY()))
			{
				final ConquestPoint spawn = fighter.getTeam().getClosestSpawn(npc);
				sendHome(match, fighter, new Location(spawn.x(), spawn.y(), spawn.z(), spawn.heading()), spawn.radius());
				if (fighter.isPlayer())
				{
					sendScreen(npc.asPlayer(), "Entering the enemy base is not allowed!", 5, false);
				}
				continue;
			}

			if (fighter.isProtected() && (now >= fighter._protectedUntil))
			{
				unprotect(fighter);
			}
		}

		// Every player left: the fake players don't fight on alone.
		if (match._withPlayers && match.getPlayers().isEmpty())
		{
			endMatch(match, null, "Every player left.", true);
			return;
		}

		// The capture points.
		int blueHeld = 0;
		int redHeld = 0;
		for (ConquestCapture capture : match.getCaptures())
		{
			final int blue = countOn(match, match.getBlue(), capture);
			final int red = countOn(match, match.getRed(), capture);
			final Team before = capture.update(blue, red);
			if (before != null)
			{
				final Team owner = capture.getOwner();
				final String text = owner == Team.NONE ? (before == Team.BLUE ? "Blue" : "Red") + " lost " + capture.getName() + "." : (owner == Team.BLUE ? "Blue" : "Red") + " captured " + capture.getName() + ".";
				match.log(text);
				announce(match, text);
			}
			if (capture.getOwner() == Team.BLUE)
			{
				blueHeld++;
			}
			else if (capture.getOwner() == Team.RED)
			{
				redHeld++;
			}
		}

		// The scores: each loses max(1, the other's points - its own).
		final ConquestTeam blue = match.getBlue();
		final ConquestTeam red = match.getRed();
		blue.setHeld(blueHeld);
		red.setHeld(redHeld);
		if (redHeld == blueHeld) {
			blue.setDrain(1);
			red.setDrain(1);
		}
		else {
			blue.setDrain(Math.max(0, redHeld - blueHeld));
			red.setDrain(Math.max(0, blueHeld - redHeld));
		}
		blue.setScore(Math.max(0, blue.getScore() - blue.getDrain()));
		red.setScore(Math.max(0, red.getScore() - red.getDrain()));
		sendState(match, Ex2TeamEventState.RESULT_NONE);
		updateRadar(match, false);

		if ((blue.getScore() <= 0) || (red.getScore() <= 0))
		{
			final Team winner = blue.getScore() > red.getScore() ? Team.BLUE : red.getScore() > blue.getScore() ? Team.RED : null;
			endMatch(match, winner, winner == null ? "Both teams ran out at once." : (winner == Team.BLUE ? "Red" : "Blue") + " ran out of score.", false);
			return;
		}
		if (now >= match._fightEnd)
		{
			final Team winner = blue.getScore() > red.getScore() ? Team.BLUE : red.getScore() > blue.getScore() ? Team.RED : null;
			endMatch(match, winner, "Time is up.", false);
			return;
		}
	}

	/**
	 * @return how many living fighters of the team stand on the capture point
	 */
	private static int countOn(ConquestMatch match, ConquestTeam team, ConquestCapture capture)
	{
		int count = 0;
		final Location location = capture.getLocation();
		for (ConquestFighter fighter : match.getNear(team, location.getX(), location.getY(), capture.getRadius()))
		{
			final Creature npc = fighter.getCreature();
			if (!npc.isDead() && capture.contains(npc.getX(), npc.getY(), npc.getZ()))
			{
				count++;
			}
		}
		return count;
	}

	/**
	 * Each step of {@link #COMMAND_STEP_MS} during the fight: the commanders whose interval is up have their turn, each at its own pace.
	 */
	private static void command(ConquestMatch match)
	{
		if (match._phase != ConquestMatch.Phase.FIGHT)
		{
			return;
		}
		final long now = System.currentTimeMillis();
		for (ConquestTeam team : new ConquestTeam[]
		{
			match.getBlue(),
			match.getRed()
		})
		{
			if (now < team._nextCommand)
			{
				continue;
			}
			final ConquestCommander commander = team.getCommander();
			team._nextCommand = now + Math.max(COMMAND_STEP_MS, commander.getInterval());
			final long start = System.nanoTime();
			try
			{
				commander.command(match, team, now);
			}
			catch (Exception e)
			{
				LOGGER.log(Level.WARNING, ConquestManager.class.getSimpleName() + ": The " + team.getName() + " commander failed.", e);
			}
			match._lastCommandNanos = System.nanoTime() - start;
		}
	}

	// ---------------------------------------------------------------------------------------------
	// Fighters
	// ---------------------------------------------------------------------------------------------

	private void respawn(ConquestMatch match, ConquestFighter fighter)
	{
		fighter._respawnAt = 0;
		final Creature creature = fighter.getCreature();
		if (!creature.isDead())
		{
			return;
		}

		final ConquestPoint first = fighter.getTeam().getSpawns().get(0);
		final Location base = fighter._respawnBase != null ? fighter._respawnBase : new Location(first.x(), first.y(), first.z(), first.heading());
		// The party's dead of a wave gather at one spot of the spawn point.
		Location spot = null;
		final ConquestParty party = fighter.getParty();
		if ((party != null) && (party._waveBase == base))
		{
			spot = party._waveSpot;
			if (spot == null)
			{
				spot = spawnSpot(base, Math.max(0, spreadAt(fighter.getTeam(), base) - PARTY_SPREAD), match.getInstanceId());
				party._waveSpot = spot;
			}
		}
		if (creature.isPlayer())
		{
			final Player player = creature.asPlayer();
			// Still arriving from a teleport, the revive would only be noted for later: it comes back a second after its wave, at the same spot.
			if (player.isTeleporting())
			{
				fighter._respawnAt = System.currentTimeMillis() + 1000;
				return;
			}
			final Location to = spawnSpot(spot != null ? spot : base, spot != null ? PARTY_SPREAD : spreadAt(fighter.getTeam(), base), match.getInstanceId());
			player.doRevive();
			player.teleToLocation(to.getX(), to.getY(), to.getZ(), base.getHeading(), match.getInstanceId(), 0);
			if (player.isDead())
			{
				// The revive did not take: the next tick tries again.
				fighter._respawnAt = System.currentTimeMillis() + 1000;
				return;
			}
			buffAndHeal(player);
		}
		else
		{
			final Npc fake = fighter.getNpc();
			DecayTaskManager.getInstance().cancel(fake);
			fake.doRevive();
			sendHome(match, fighter, spot != null ? spot : base, spot != null ? PARTY_SPREAD : spreadAt(fighter.getTeam(), base));
			FakePartyManager.getInstance().onRevived(fake);
			FakePlayerPvpManager.getInstance().prepareSimulant(fake);
			fake.setRunning();
		}
		protect(fighter);
	}

	/** @return the spread radius of the team's spawn point at that spot */
	private static int spreadAt(ConquestTeam team, Location base)
	{
		for (ConquestPoint spawn : team.getSpawns())
		{
			if ((spawn.x() == base.getX()) && (spawn.y() == base.getY()))
			{
				return spawn.radius();
			}
		}
		return 150;
	}

	/**
	 * Teleports a fighter in the match's instance, to a spot on the floor within {@code radius} of the place (not with the teleport's own random offset, which gives the new spot the height of the place: under the floor where it is uneven), its servitor
	 * with it. A fake player's teleport stops its AI task while its intention stays ACTIVE: it starts again here.
	 */
	private static void sendHome(ConquestMatch match, ConquestFighter fighter, Location location, int radius)
	{
		final Creature creature = fighter.getCreature();
		final Location spot = spawnSpot(location, radius, match.getInstanceId());
		creature.teleToLocation(spot.getX(), spot.getY(), spot.getZ(), location.getHeading(), match.getInstanceId(), 0);
		final Npc npc = fighter.getNpc();
		if (npc != null)
		{
			restartAI(npc);
			bringServitor(match, npc);
		}
	}

	/**
	 * @param around the place
	 * @param radius how far from it, 0 for the place itself
	 * @param instanceId the instance
	 * @return a spot within {@code radius} of the place that can be walked to from it, moved to the highest ground on a small circle around it: the geodata of an uneven floor (steps, a raised edge) can lie below the floor that is seen, and a fighter put
	 *         there stands under it
	 */
	static Location spawnSpot(Location around, int radius, int instanceId)
	{
		final GeoEngine geo = GeoEngine.getInstance();
		final Location picked = radius > 0 ? QuestSpawnUtil.findPoint(around, 0, radius, true, instanceId) : new Location(around.getX(), around.getY(), geo.getHeight(around.getX(), around.getY(), around.getZ()));
		int bestX = picked.getX();
		int bestY = picked.getY();
		int bestZ = picked.getZ();
		for (int i = 0; i < FLOOR_SAMPLES; i++)
		{
			final double angle = (2 * Math.PI * i) / FLOOR_SAMPLES;
			final int x = picked.getX() + (int) (Math.cos(angle) * FLOOR_RADIUS);
			final int y = picked.getY() + (int) (Math.sin(angle) * FLOOR_RADIUS);
			final int z = geo.getHeight(x, y, picked.getZ());
			if ((z > bestZ) && ((z - picked.getZ()) <= FLOOR_STEP) && geo.canMoveToTarget(picked.getX(), picked.getY(), picked.getZ(), x, y, z, instanceId))
			{
				bestX = x;
				bestY = y;
				bestZ = z;
			}
		}
		return new Location(bestX, bestY, bestZ, around.getHeading());
	}

	private static void restartAI(Npc npc)
	{
		if (!npc.isDead() && npc.hasAI() && (npc.getAI() instanceof AttackableAI ai))
		{
			ai.startAITask();
		}
	}

	/**
	 * Brings a fake player's servitor next to it: one left behind (a teleport of its owner, a long march) would be unsummoned, and its owner fight without it.
	 */
	private static void bringServitor(ConquestMatch match, Npc owner)
	{
		final FakePlayerPvpProfile profile = owner.getTemplate().getFakePlayerPvpProfile();
		final Npc servitor = profile != null ? profile.getServitor() : null;
		if ((servitor == null) || servitor.isDead() || !servitor.isSpawned() || owner.isDead())
		{
			return;
		}
		final Location spot = spawnSpot(owner.getLocation(), 80, match.getInstanceId());
		servitor.teleToLocation(spot.getX(), spot.getY(), spot.getZ(), owner.getHeading(), match.getInstanceId(), 0);
		servitor.setRunning();
		restartAI(servitor);
	}

	/**
	 * The servitors keep up with their owners: one left farther than {@link #SERVITOR_LEASH} behind is brought back.
	 */
	private static void keepServitors(ConquestMatch match)
	{
		for (ConquestFighter fighter : match.getAllFighters())
		{
			final Npc owner = fighter.getNpc();
			if ((owner == null) || owner.isDead() || owner.isTeleporting())
			{
				continue;
			}
			final FakePlayerPvpProfile profile = owner.getTemplate().getFakePlayerPvpProfile();
			final Npc servitor = profile != null ? profile.getServitor() : null;
			if ((servitor != null) && !servitor.isDead() && servitor.isSpawned() && !servitor.isTeleporting() && ((servitor.getInstanceId() != owner.getInstanceId()) || (servitor.calculateDistance2D(owner) > SERVITOR_LEASH)))
			{
				bringServitor(match, owner);
			}
		}
	}

	private static void protect(ConquestFighter fighter)
	{
		if (ConquestConfig.SPAWN_PROTECTION <= 0)
		{
			return;
		}

		final Creature npc = fighter.getCreature();
		fighter._protectedUntil = System.currentTimeMillis() + (ConquestConfig.SPAWN_PROTECTION * 1000L);
		npc.setInvul(true);
		final Skill skill = GHOST_WALKING.getSkill();
		if (skill != null)
		{
			skill.applyEffects(npc, npc);
		}
	}

	private static void unprotect(ConquestFighter fighter)
	{
		if (!fighter.isProtected())
		{
			return;
		}

		fighter._protectedUntil = 0;
		final Creature npc = fighter.getCreature();
		npc.setInvul(false);
		final Skill skill = GHOST_WALKING.getSkill();
		if ((skill != null) && npc.isAffectedBySkill(skill.getId()))
		{
			npc.getEffectList().stopSkillEffects(SkillFinishType.REMOVED, skill);
		}
	}

	/**
	 * Called by {@code Creature#doDie} while a match goes on.
	 * @param victim who died
	 * @param killer who killed it
	 */
	public void onDeath(Creature victim, Creature killer)
	{
		final ConquestFighter dead = _fighters.get(victim.getObjectId());
		final ConquestMatch match = dead != null ? dead.getTeam().getMatch() : null;
		if ((match == null) || (match._phase != ConquestMatch.Phase.FIGHT))
		{
			return;
		}

		dead._deaths++;
		dead._seen = null;
		dead.getTeam().joinWave(dead, System.currentTimeMillis());
		final ConquestFighter slayer = getFighter(killer);
		if ((slayer != null) && (slayer.getTeam() != dead.getTeam()))
		{
			slayer._kills++;
			slayer.getTeam().addKill();
			if (slayer.isPlayer())
			{
				rewardKill(slayer.getCreature().asPlayer(), victim);
			}
		}

		if (dead.isPlayer())
		{
			final long wait = Math.max(0, dead._respawnAt - System.currentTimeMillis()) / 1000;
			victim.sendMessage("Conquest: You come back with your team's next wave in " + wait + " seconds.");
		}
	}

	private static void rewardKill(Player killer, Creature victim)
	{
		final List<ItemChanceHolder> rewards = ConquestConfig.KILL_REWARDS;
		if (rewards.isEmpty())
		{
			return;
		}

		double total = 0;
		for (ItemChanceHolder reward : rewards)
		{
			total += reward.getChance();
		}

		double roll = Rnd.nextDouble() * Math.max(100, total);
		for (ItemChanceHolder reward : rewards)
		{
			roll -= reward.getChance();
			if (roll < 0)
			{
				killer.addItem(ItemProcessType.REWARD, reward.getId(), reward.getCount(), victim, true);
				return;
			}
		}
	}

	/**
	 * Called by the combat log when a creature hurt another: a fighter under spawn protection loses it.
	 * @param attacker who dealt the damage (a summon counts as its owner)
	 */
	public void onDamageDealt(Creature attacker)
	{
		final ConquestFighter fighter = getFighter(attacker);
		if ((fighter != null) && fighter.isProtected())
		{
			unprotect(fighter);
		}
	}

	// ---------------------------------------------------------------------------------------------
	// End
	// ---------------------------------------------------------------------------------------------

	private void endMatch(ConquestMatch match, Team winner, String reason, boolean abandoned)
	{
		if (match._phase == ConquestMatch.Phase.ENDED)
		{
			return;
		}
		if (match._phase == ConquestMatch.Phase.PREP)
		{
			match._fightStart = System.currentTimeMillis();
		}
		match._fightEnd = Math.min(match._fightEnd > 0 ? match._fightEnd : Long.MAX_VALUE, System.currentTimeMillis());
		match._phase = ConquestMatch.Phase.ENDED;
		match._winner = winner;
		if (match._commandTask != null)
		{
			match._commandTask.cancel(false);
			match._commandTask = null;
		}

		final int blue = match.getBlue().getScore();
		final int red = match.getRed().getScore();
		match._result = abandoned ? reason : (winner == null ? "Draw, " + blue + " : " + red + ". " + reason : (winner == Team.BLUE ? "Blue" : "Red") + " wins, " + blue + " : " + red + ". " + reason);
		match.log(match._result);
		announce(match, match._result);
		final ConquestCommander blueBrain = match.getBlue().getCommander();
		final ConquestCommander redBrain = match.getRed().getCommander();
		match.log("Brains: Blue " + describe(blueBrain) + ", Red " + describe(redBrain) + ".");
		if (!abandoned)
		{
			_brainResults.computeIfAbsent(blueBrain.getName() + " vs " + redBrain.getName(), _ -> new int[3])[winner == Team.BLUE ? 0 : winner == Team.RED ? 1 : 2]++;
		}
		for (ConquestFighter fighter : match.getAllFighters())
		{
			final Creature creature = fighter.getCreature();
			creature.abortAttack();
			creature.abortCast();
			creature.setInvul(true);
			if (!fighter.isPlayer() && creature.hasAI())
			{
				creature.getAI().setIntention(Intention.IDLE);
			}
		}

		// The players: their result, and the winners' reward.
		final Ex2TeamEventState.State state = state(match, abandoned ? Ex2TeamEventState.RESULT_NO_RESULT : winner == Team.BLUE ? Ex2TeamEventState.RESULT_BLUE : winner == Team.RED ? Ex2TeamEventState.RESULT_RED : Ex2TeamEventState.RESULT_DRAW);
		updateRadar(match, true);
		final Skill firework = CommonSkill.FIREWORK.getSkill();
		for (Player player : match.getPlayers())
		{
			final ConquestFighter fighter = _fighters.get(player.getObjectId());
			TeamEventHud.sendState(player, state, fighter != null ? fighter.getTeam().getTeam() : Team.NONE);
			player.sendMessage("Conquest: " + match._result + (fighter != null ? " You: " + fighter.getKills() + " kills, " + fighter.getDeaths() + " deaths." : ""));
			if (!abandoned && (winner != null) && (fighter != null) && (fighter.getTeam().getTeam() == winner))
			{
				if (firework != null)
				{
					player.broadcastPacket(new MagicSkillUse(player, player, firework.getId(), firework.getLevel(), firework.getHitTime(), firework.getReuseDelay()));
				}
				player.broadcastSocialAction(3);
				for (ItemHolder reward : ConquestConfig.WIN_REWARD)
				{
					player.addItem(ItemProcessType.REWARD, reward.getId(), reward.getCount(), null, true);
				}
			}
		}

		for (Player gm : watchers(match))
		{
			TeamEventHud.sendState(gm, state, Team.NONE);
		}

		LOGGER.info(getClass().getSimpleName() + ": Match " + match.getId() + " on " + match.getMap().name() + " ended: " + match._result + " Brains: Blue " + describe(blueBrain) + ", Red " + describe(redBrain) + ".");
		ThreadPool.schedule(() -> cleanup(match), abandoned ? 2000 : ConquestConfig.END_DELAY * 1000L);
	}

	/**
	 * The fake players out of the world, the instance away (a GM watching goes back to the open world).
	 */
	private void cleanup(ConquestMatch match)
	{
		try
		{
			if (match._task != null)
			{
				match._task.cancel(false);
				match._task = null;
			}
			if (match._spawnTask != null)
			{
				match._spawnTask.cancel(false);
				match._spawnTask = null;
			}
			if (match._commandTask != null)
			{
				match._commandTask.cancel(false);
				match._commandTask = null;
			}
			final Deque<PartyPlan> toSpawn = _toSpawn.remove(match.getId());
			if (toSpawn != null)
			{
				synchronized (toSpawn)
				{
					// Players whose party had not come in yet are free again.
					for (PartyPlan plan : toSpawn)
					{
						for (Player player : plan.players)
						{
							player.setRegisteredOnEvent(false);
						}
					}
					toSpawn.clear();
				}
			}

			for (ConquestTeam team : new ConquestTeam[]
			{
				match.getBlue(),
				match.getRed()
			})
			{
				for (ConquestParty party : team.getParties())
				{
					final FakePlayerParty fakeParty = party.getFakeParty();
					if (fakeParty == null)
					{
						continue;
					}
					if (fakeParty.isFakeOnly())
					{
						FakePartyManager.getInstance().disbandSimulationParty(fakeParty);
					}
					else
					{
						FakePartyManager.getInstance().disbandEventParty(fakeParty);
					}
				}
			}

			int removed = 0;
			for (ConquestFighter fighter : match.getAllFighters())
			{
				if (fighter.isPlayer())
				{
					restorePlayer(match, fighter, true);
				}
				else
				{
					removeFake(fighter.getNpc());
					removed++;
				}
				_fighters.remove(fighter.getCreature().getObjectId(), fighter);
			}
			LOGGER.info(getClass().getSimpleName() + ": Match " + match.getId() + ": removed " + removed + " fake players (" + _fighters.size() + " left registered).");
		}
		catch (Exception e)
		{
			LOGGER.log(Level.WARNING, getClass().getSimpleName() + ": Problem while cleaning up match " + match.getId() + ".", e);
		}
		finally
		{
			for (int objectId : match._watchers)
			{
				final Player gm = World.getInstance().getPlayer(objectId);
				if (gm != null)
				{
					TeamEventHud.close(gm, zoneIds(match));
				}
			}
			match._watchers.clear();
			releaseRegions(match);
			InstanceManager.getInstance().destroyInstance(match.getInstanceId());
			synchronized (this)
			{
				_matches.remove(match.getId(), match);
				ACTIVE = !_matches.isEmpty();
			}
		}
	}

	private static void removeFake(Npc fake)
	{
		try
		{
			DecayTaskManager.getInstance().cancel(fake);
			FakePlayerPvpManager.getInstance().unsummonServitor(fake);
			if (!fake.isDead())
			{
				fake.abortAttack();
				fake.abortCast();
				fake.stopTransformation(true);
				FakePlayerPvpManager.getInstance().untransform(fake);
			}
			if (!fake.isDecayed())
			{
				fake.deleteMe();
			}
		}
		catch (Exception e)
		{
			LOGGER.log(Level.WARNING, ConquestManager.class.getSimpleName() + ": Could not remove " + fake.getName() + ".", e);
		}
	}

	/**
	 * Shows a line on the screen of everyone watching the match (in its instance).
	 */
	private static void announce(ConquestMatch match, String text)
	{
		final Instance instance = InstanceManager.getInstance().getInstance(match.getInstanceId());
		if (instance == null)
		{
			return;
		}
		final ExShowScreenMessage message = new ExShowScreenMessage(text, ExShowScreenMessage.TOP_CENTER, 4000);
		for (int objectId : instance.getPlayers())
		{
			final Player player = World.getInstance().getPlayer(objectId);
			if (player != null)
			{
				player.sendPacket(message);
			}
		}
	}

	// ---------------------------------------------------------------------------------------------
	// Event strip and radar (client/TeamEvents/PROTOCOL.txt)
	// ---------------------------------------------------------------------------------------------

	/** How far (per-mille) a capture's sweep may drift from where the client extrapolates it before it is sent again. */
	private static final int SWEEP_TOLERANCE = 10;

	/**
	 * A capture's sweep on the radar: the color of the team the progress lies toward, filled as far as the progress, moving as it moved at the last tick (a red push on a blue point shrinks the blue sweep).
	 * @param team the team it lies toward, {@link Team#NONE} at neutral
	 * @param fraction 0 to 1000
	 * @param rate per-mille per second, signed
	 * @param flags {@link Ex2RadarZoneProgress#CONTESTED}
	 */
	private record Sweep(Team team, int fraction, int rate, int flags)
	{
		static Sweep of(ConquestCapture capture, boolean frozen)
		{
			final double progress = capture.getProgress();
			final Team team = progress > 0 ? Team.BLUE : progress < 0 ? Team.RED : Team.NONE;
			final int rate = frozen ? 0 : (int) Math.round(Math.signum(progress) * capture.getRate() * 10);
			final int flags = !frozen && capture.isContested() ? Ex2RadarZoneProgress.CONTESTED : 0;
			return new Sweep(team, (int) Math.round(Math.abs(progress) * 10), rate, flags);
		}

		Ex2RadarZoneProgress packet(int zoneId)
		{
			return new Ex2RadarZoneProgress(zoneId, team == Team.RED ? TeamEventHud.RED_SWEEP : TeamEventHud.BLUE_SWEEP, fraction, rate, flags);
		}
	}

	/**
	 * @param match a match
	 * @param result {@link Ex2TeamEventState#RESULT_NONE} until it ended, then who won
	 * @return its state for the event strip
	 */
	private static Ex2TeamEventState.State state(ConquestMatch match, int result)
	{
		final long now = System.currentTimeMillis();
		final boolean ended = match._phase == ConquestMatch.Phase.ENDED;
		final int phase = switch (match._phase)
		{
			case PREP -> Ex2TeamEventState.PHASE_PREP;
			case FIGHT -> Ex2TeamEventState.PHASE_FIGHT;
			case ENDED -> Ex2TeamEventState.PHASE_ENDED;
		};
		final int secondsLeft = ended ? 0 : (int) Math.max(0, ((match._phase == ConquestMatch.Phase.PREP ? match._prepEnd : match._fightEnd) - now) / 1000);

		final List<Ex2TeamEventState.Point> points = new ArrayList<>();
		final List<ConquestCapture> captures = match.getCaptures();
		for (int i = 0; i < captures.size(); i++)
		{
			final ConquestCapture capture = captures.get(i);
			final int rate = ended ? 0 : (int) Math.round(capture.getRate() * 10);
			final int flags = !ended && capture.isContested() ? Ex2TeamEventState.POINT_CONTESTED : 0;
			points.add(new Ex2TeamEventState.Point(pointName(capture, i), TeamEventHud.code(capture.getOwner()), (int) Math.round(capture.getProgress() * 10), rate, flags));
		}

		final ConquestTeam blue = match.getBlue();
		final ConquestTeam red = match.getRed();
		final boolean draining = match._phase == ConquestMatch.Phase.FIGHT;
		return new Ex2TeamEventState.State(Ex2TeamEventState.MODE_CONQUEST, phase, result, secondsLeft, blue.getScore(), red.getScore(), ConquestConfig.START_SCORE, draining ? blue.getDrain() : 0, draining ? red.getDrain() : 0, points);
	}

	/**
	 * Sends the match's state to each of its players.
	 */
	private void sendState(ConquestMatch match, int result)
	{
		final Ex2TeamEventState.State state = state(match, result);
		for (Player player : audience(match))
		{
			final ConquestFighter fighter = _fighters.get(player.getObjectId());
			TeamEventHud.sendState(player, state, fighter != null ? fighter.getTeam().getTeam() : Team.NONE);
		}
	}

	/**
	 * @return the players who see the match's strip and radar zones: its fighters, and the GMs watching it
	 */
	private List<Player> audience(ConquestMatch match)
	{
		final List<Player> players = match.getPlayers();
		for (Player gm : watchers(match))
		{
			if (!players.contains(gm))
			{
				players.add(gm);
			}
		}
		return players;
	}

	/**
	 * @return the GMs watching the match who are still in its instance
	 */
	private static List<Player> watchers(ConquestMatch match)
	{
		final List<Player> watchers = new ArrayList<>();
		for (int objectId : match._watchers)
		{
			final Player gm = World.getInstance().getPlayer(objectId);
			if ((gm != null) && (gm.getInstanceId() == match.getInstanceId()))
			{
				watchers.add(gm);
			}
		}
		return watchers;
	}

	/**
	 * Lets go of the GMs who stopped watching the match without //conquest leave (another teleport, a logout): their strip and the match's zones go.
	 */
	private static void pruneWatchers(ConquestMatch match)
	{
		for (int objectId : match._watchers)
		{
			final Player gm = World.getInstance().getPlayer(objectId);
			if ((gm == null) || (gm.getInstanceId() != match.getInstanceId()))
			{
				match._watchers.remove(objectId);
				if (gm != null)
				{
					TeamEventHud.close(gm, zoneIds(match));
				}
			}
		}
	}

	/**
	 * A GM stops watching the match: its strip and zones go.
	 */
	private static void stopWatching(ConquestMatch match, Player gm)
	{
		if (match._watchers.remove(gm.getObjectId()))
		{
			TeamEventHud.close(gm, zoneIds(match));
		}
	}

	/**
	 * @return a capture's name on the strip: its own, or its number
	 */
	private static String pointName(ConquestCapture capture, int index)
	{
		return capture.getName().isEmpty() ? String.valueOf(index + 1) : capture.getName();
	}

	/**
	 * @return the ids of every zone the match shows on a player's radar
	 */
	private static List<Integer> zoneIds(ConquestMatch match)
	{
		return TeamEventHud.zoneIds(match.getCaptures().size(), match.getBlue().getSpawns().size(), match.getRed().getSpawns().size());
	}

	private static void showCapture(Player player, int index, ConquestCapture capture)
	{
		final ConquestPoint point = capture.getPoint();
		TeamEventHud.showCircle(player, TeamEventHud.zoneId(TeamEventHud.KIND_CAPTURE, index), point.x(), point.y(), point.z(), ConquestConfig.CAPTURE_HEIGHT, point.radius(), TeamEventHud.ownerStyle(capture.getOwner()).labelled(), pointName(capture, index));
	}

	/**
	 * Shows a player entering the match its zones: the capture points in their owners' colors with their sweeps, both teams' bases and the map's area.
	 */
	private static void showZones(ConquestMatch match, Player player)
	{
		final List<ConquestCapture> captures = match.getCaptures();
		for (int i = 0; i < captures.size(); i++)
		{
			final ConquestCapture capture = captures.get(i);
			showCapture(player, i, capture);
			final Sweep sweep = Sweep.of(capture, match._phase == ConquestMatch.Phase.ENDED);
			if (sweep.fraction() > 0)
			{
				player.sendPacket(sweep.packet(TeamEventHud.zoneId(TeamEventHud.KIND_CAPTURE, i)));
			}
		}
		for (ConquestTeam team : List.of(match.getBlue(), match.getRed()))
		{
			final boolean blue = team.getTeam() == Team.BLUE;
			final List<ConquestPoint> spawns = team.getSpawns();
			for (int i = 0; i < spawns.size(); i++)
			{
				final ConquestPoint spawn = spawns.get(i);
				TeamEventHud.showCircle(player, TeamEventHud.zoneId(blue ? TeamEventHud.KIND_BLUE_BASE : TeamEventHud.KIND_RED_BASE, i), spawn.x(), spawn.y(), spawn.z(), TeamEventHud.SPAWN_HEIGHT, spawn.radius() + ConquestConfig.BASE_RADIUS, TeamEventHud.spawnStyle(team.getTeam()), team.getName() + " base " + (i + 1));
			}
		}
		final ConquestMap map = match.getMap();
		TeamEventHud.showEdge(player, map.minX(), map.minY(), map.maxX(), map.maxY(), map.z());
	}

	/**
	 * After a tick: the capture points that changed hands get their new colors, and the sweeps that went another way than the clients extrapolate are sent again.
	 * @param match the match
	 * @param frozen {@code true} once it ended: every sweep stops where it is
	 */
	private void updateRadar(ConquestMatch match, boolean frozen)
	{
		final List<Player> players = audience(match);
		final long now = System.currentTimeMillis();
		final List<ConquestCapture> captures = match.getCaptures();
		for (int i = 0; i < captures.size(); i++)
		{
			final ConquestCapture capture = captures.get(i);
			final int zoneId = TeamEventHud.zoneId(TeamEventHud.KIND_CAPTURE, i);
			// A zone shown again may have lost its sweep: it goes with it.
			final boolean reshown = capture.getOwner() != capture._sentOwner;
			if (reshown)
			{
				capture._sentOwner = capture.getOwner();
				for (Player player : players)
				{
					showCapture(player, i, capture);
				}
			}

			final Sweep sweep = Sweep.of(capture, frozen);
			final long expected = Math.max(0, Math.min(1000, capture._sentFraction + ((capture._sentRate * (now - capture._sentAt)) / 1000)));
			if (!reshown && (sweep.team() == capture._sentSweep) && (sweep.rate() == capture._sentRate) && (sweep.flags() == capture._sentFlags) && (Math.abs(expected - sweep.fraction()) <= SWEEP_TOLERANCE))
			{
				continue;
			}
			capture._sentSweep = sweep.team();
			capture._sentFraction = sweep.fraction();
			capture._sentRate = sweep.rate();
			capture._sentFlags = sweep.flags();
			capture._sentAt = now;
			final Ex2RadarZoneProgress packet = sweep.packet(zoneId);
			for (Player player : players)
			{
				player.sendPacket(packet);
			}
		}
	}

	// ---------------------------------------------------------------------------------------------
	// Watching
	// ---------------------------------------------------------------------------------------------

	/**
	 * Takes a GM into a match's copy of the map, at its middle capture point.
	 * @param gm the GM
	 * @param id the match's id, 0 for the next one after the one the GM watches (the newest first)
	 * @return what to tell the GM
	 */
	public String watch(Player gm, int id)
	{
		ConquestMatch match = id > 0 ? _matches.get(id) : null;
		if (id <= 0)
		{
			final List<ConquestMatch> matches = getMatches();
			final ConquestMatch current = getMatch(gm);
			final int index = (current != null) && (gm.getInstanceId() == current.getInstanceId()) ? matches.indexOf(current) : -1;
			match = matches.isEmpty() ? null : matches.get((index + 1) % matches.size());
		}
		if (match == null)
		{
			return id > 0 ? "No Conquest match " + id + " is running." : "No Conquest match is running.";
		}

		final ConquestMap map = match.getMap();
		final int[] center = map.getCenter();
		ConquestCapture closest = match.getCaptures().get(0);
		for (ConquestCapture capture : match.getCaptures())
		{
			if (Math.hypot(capture.getLocation().getX() - center[0], capture.getLocation().getY() - center[1]) < Math.hypot(closest.getLocation().getX() - center[0], closest.getLocation().getY() - center[1]))
			{
				closest = capture;
			}
		}
		// From another match: its strip and zones go first.
		for (ConquestMatch other : getMatches())
		{
			if (other != match)
			{
				stopWatching(other, gm);
			}
		}
		final Location spot = closest.getLocation();
		gm.teleToLocation(spot.getX(), spot.getY(), spot.getZ(), gm.getHeading(), match.getInstanceId());
		// The event strip and radar zones, as a player of neither team sees them.
		match._watchers.add(gm.getObjectId());
		TeamEventHud.sendState(gm, state(match, Ex2TeamEventState.RESULT_NONE), Team.NONE);
		showZones(match, gm);
		return "Watching match " + match.getId() + " (levels " + match.getMinLevel() + "-" + match.getMaxLevel() + ") on " + map.name() + " at capture point " + closest.getName() + ". The fake players leave you alone. //conquest watch goes to the next match, //conquest leave takes you back.";
	}

	/**
	 * Takes a GM watching a match back to the open world (Giran, the instance's exit).
	 * @param gm the GM
	 * @return what to tell the GM
	 */
	public String leave(Player gm)
	{
		final ConquestMatch match = getMatch(gm);
		if ((match == null) || (gm.getInstanceId() != match.getInstanceId()))
		{
			return "You are not watching a Conquest match.";
		}
		final Instance instance = InstanceManager.getInstance().getInstance(match.getInstanceId());
		final Location exit = (instance != null) && (instance.getExitLoc() != null) ? instance.getExitLoc() : new Location(83425, 148585, -3406);
		stopWatching(match, gm);
		gm.teleToLocation(exit.getX(), exit.getY(), exit.getZ(), gm.getHeading(), 0);
		return "Back in the open world.";
	}

	// ---------------------------------------------------------------------------------------------
	// Regions
	// ---------------------------------------------------------------------------------------------

	private static void keepRegionsAwake(ConquestMatch match)
	{
		final ConquestMap map = match.getMap();
		final int step = 1 << World.SHIFT_BY;
		synchronized (match._keptRegions)
		{
			for (int x = map.minX() - REGION_MARGIN; x <= (map.maxX() + REGION_MARGIN + step); x += step)
			{
				for (int y = map.minY() - REGION_MARGIN; y <= (map.maxY() + REGION_MARGIN + step); y += step)
				{
					final WorldRegion region = World.getInstance().getRegion(x, y, map.z());
					if ((region != null) && !region.isKeptActive())
					{
						region.keepActive();
						match._keptRegions.add(region);
					}
				}
			}
		}
	}

	private void releaseRegions(ConquestMatch match)
	{
		// Another match going on on the same map still needs them (a region holds every copy of its place): it takes them over.
		ConquestMatch heir = null;
		for (ConquestMatch other : _matches.values())
		{
			if ((other != match) && (other._phase != ConquestMatch.Phase.ENDED) && other.getMap().id().equals(match.getMap().id()))
			{
				heir = other;
				break;
			}
		}

		synchronized (match._keptRegions)
		{
			if (heir != null)
			{
				synchronized (heir._keptRegions)
				{
					heir._keptRegions.addAll(match._keptRegions);
				}
			}
			else
			{
				for (WorldRegion region : match._keptRegions)
				{
					region.releaseKeepActive();
				}
			}
			match._keptRegions.clear();
		}
	}

	// ---------------------------------------------------------------------------------------------
	// Who fights whom (combat hooks, fake player AI)
	// ---------------------------------------------------------------------------------------------

	/**
	 * @param creature a creature (a summon or servitor counts as its owner)
	 * @return its fighter, {@code null} if it is in no match
	 */
	public static ConquestFighter getFighter(Creature creature)
	{
		if (!ACTIVE || (creature == null))
		{
			return null;
		}

		final ConquestManager manager = getInstance();
		final ConquestFighter fighter = manager._fighters.get(creature.getObjectId());
		if (fighter != null)
		{
			return fighter;
		}

		final Creature owner = CombatLogManager.getPvpOwner(creature);
		return (owner != null) && (owner != creature) ? manager._fighters.get(owner.getObjectId()) : null;
	}

	/**
	 * @param a a creature
	 * @param b another creature
	 * @return how they stand to each other in the match: NONE if neither fights in it, STRANGER if only one does
	 */
	public static Relation getRelation(Creature a, Creature b)
	{
		if (!ACTIVE)
		{
			return Relation.NONE;
		}

		final ConquestFighter fa = getFighter(a);
		final ConquestFighter fb = getFighter(b);
		if ((fa == null) && (fb == null))
		{
			return Relation.NONE;
		}
		if ((fa == null) || (fb == null) || (fa.getTeam().getMatch() != fb.getTeam().getMatch()))
		{
			return Relation.STRANGER;
		}
		return fa.getTeam() == fb.getTeam() ? Relation.ALLY : Relation.ENEMY;
	}

	/**
	 * @param attacker a creature
	 * @param target another creature
	 * @return {@code true} if one of them fights in the match and the other isn't an enemy in the fight going on: the attack is not allowed
	 */
	public static boolean isForbidden(Creature attacker, Creature target)
	{
		if (!ACTIVE)
		{
			return false;
		}

		final Relation relation = getRelation(attacker, target);
		return (relation != Relation.NONE) && !isFightOn(attacker, target, relation);
	}

	/**
	 * @param attacker a creature
	 * @param target another creature
	 * @return {@code true} if they are enemies of the match and the fight goes on, neither in its own base: attacked without Ctrl
	 */
	public static boolean isEnemy(Creature attacker, Creature target)
	{
		return ACTIVE && isFightOn(attacker, target, getRelation(attacker, target));
	}

	private static boolean isFightOn(Creature attacker, Creature target, Relation relation)
	{
		if (relation != Relation.ENEMY)
		{
			return false;
		}
		final ConquestFighter fa = getFighter(attacker);
		final ConquestFighter fb = getFighter(target);
		return (fa != null) && (fb != null) && (fa.getTeam().getMatch() == fb.getTeam().getMatch()) && (fa.getTeam().getMatch()._phase == ConquestMatch.Phase.FIGHT) && !fa.getTeam().isInBase(attacker.getX(), attacker.getY()) && !fb.getTeam().isInBase(target.getX(), target.getY());
	}

	/**
	 * @param fake a fake player
	 * @return the phase of its match, {@code null} if it fights in none
	 */
	public ConquestMatch.Phase getPhase(Npc fake)
	{
		final FakePlayerPvpProfile profile = fake.getTemplate().getFakePlayerPvpProfile();
		final ConquestMatch match = profile != null ? _matches.get(profile.getConquestMatchId()) : null;
		return match != null ? match._phase : null;
	}

	/**
	 * @param fake a fighter of the match
	 * @return {@code true} if it stands in its own team's base
	 */
	public boolean isAtOwnBase(Creature fake)
	{
		final ConquestFighter fighter = _fighters.get(fake.getObjectId());
		return (fighter != null) && fighter.getTeam().isInBase(fake.getX(), fake.getY());
	}

	/**
	 * The enemy a fake player engages: of the enemies within its sight (less far the less it knows the game), outside their base and not under spawn protection, the closest few it can see, the one low on HP rather for one that knows the game.
	 * @param fake a fake player of the match
	 * @return the enemy, {@code null} if none
	 */
	public Creature findEnemyInSight(Npc fake)
	{
		final ConquestFighter fighter = _fighters.get(fake.getObjectId());
		final ConquestMatch match = fighter != null ? fighter.getTeam().getMatch() : null;
		if ((match == null) || (match._phase != ConquestMatch.Phase.FIGHT))
		{
			return null;
		}

		final long now = System.currentTimeMillis();
		final ConquestTeam other = match.getOther(fighter.getTeam());
		final Creature seen = fighter._seen;
		if ((now < fighter._seenUntil) && ((seen == null) || isEngageable(fake, seen, other)))
		{
			return seen;
		}

		final double knowledge = FakePlayerPvpPersonality.of(fake).getKnowledge();
		final double sight = SIGHT_RANGE * (1 - (SIGHT_KNOWLEDGE_LOSS * (1 - knowledge)));
		final List<ConquestFighter> near = match.getNear(other, fake.getX(), fake.getY(), (int) sight);
		near.sort(Comparator.comparingDouble(enemy -> fake.calculateDistance2D(enemy.getCreature())));
		Creature best = null;
		double bestScore = Double.MAX_VALUE;
		int checked = 0;
		for (ConquestFighter enemy : near)
		{
			final Creature npc = enemy.getCreature();
			if (enemy.isProtected() || !isEngageable(fake, npc, other))
			{
				continue;
			}
			final double distance = fake.calculateDistance2D(npc);
			if (distance > CLOSE_ENEMY_RANGE)
			{
				if (checked >= SIGHT_CANDIDATES)
				{
					break;
				}
				checked++;
				if (!GeoEngine.getInstance().canSeeTarget(fake, npc))
				{
					continue;
				}
			}

			final double score = (distance / SIGHT_RANGE) - (knowledge * LOW_TARGET_BONUS * (1 - (npc.getCurrentHp() / Math.max(1, npc.getMaxHp()))));
			if (score < bestScore)
			{
				best = npc;
				bestScore = score;
			}
		}

		fighter._seen = best;
		fighter._seenUntil = now + SEEN_CACHE;
		return best;
	}

	private static boolean isEngageable(Npc fake, Creature enemy, ConquestTeam enemyTeam)
	{
		return !enemy.isDead() && enemy.isSpawned() && !enemy.isInvisible() && !enemy.isInvul() && (enemy.getInstanceId() == fake.getInstanceId()) && !enemyTeam.isInBase(enemy.getX(), enemy.getY());
	}

	/**
	 * Called by {@code Creature#moveToLocation} when the game's pathfinding finds no way for a fighter of the match (too long for it, or to a spot inside a wall).
	 * @param creature the fighter (or its servitor)
	 * @param x where it wants to go
	 * @param y where it wants to go
	 * @param z where it wants to go
	 * @return the spot to walk to now along the ways across the map (see {@link ConquestNavGrid#routeStep}), {@code null} if none
	 */
	public Location routeStep(Creature creature, int x, int y, int z)
	{
		final ConquestFighter fighter = getFighter(creature);
		final ConquestMatch match = fighter != null ? fighter.getTeam().getMatch() : null;
		if ((match == null) || (creature.getInstanceId() != match.getInstanceId()))
		{
			return null;
		}
		return match.getNav().routeStep(creature.getLocation(), new Location(x, y, z), match.getInstanceId());
	}

	/**
	 * Where a fake player marches when it has no enemy in sight: toward its party's order, one waypoint at a time (the next stretch of the way across the map).
	 * @param fake a fake player of the match
	 * @return the spot, {@code null} if it has nowhere to go
	 */
	public Location getMarchTarget(Npc fake)
	{
		final ConquestFighter fighter = _fighters.get(fake.getObjectId());
		if (fighter == null)
		{
			return null;
		}

		final ConquestMatch match = fighter.getTeam().getMatch();
		final ConquestParty party = fighter.getParty();
		final ConquestParty.Order order = party != null ? party.getOrder() : null;
		Location target = order != null ? order.target() : null;
		if (target == null)
		{
			// No order yet: the nearest capture point its team doesn't hold.
			double best = Double.MAX_VALUE;
			for (ConquestCapture capture : match.getCaptures())
			{
				final double distance = fake.calculateDistance2D(capture.getLocation());
				if ((capture.getOwner() != fighter.getTeam().getTeam()) && (distance < best))
				{
					best = distance;
					target = capture.getLocation();
				}
			}
			if (target == null)
			{
				return null;
			}
		}
		return match.getNav().nextWaypoint(fake.getLocation(), target);
	}

	// ---------------------------------------------------------------------------------------------
	// For the GM page
	// ---------------------------------------------------------------------------------------------

	/**
	 * @return the matches running now (or ending), the oldest first
	 */
	public List<ConquestMatch> getMatches()
	{
		final List<ConquestMatch> matches = new ArrayList<>(_matches.values());
		matches.sort(Comparator.comparingInt(ConquestMatch::getId));
		return matches;
	}

	/**
	 * @param id a match's id
	 * @return that match, {@code null} if it doesn't run
	 */
	public ConquestMatch getMatch(int id)
	{
		return _matches.get(id);
	}

	/**
	 * @return the newest match running now (or ending), {@code null} if none
	 */
	public ConquestMatch getMatch()
	{
		final List<ConquestMatch> matches = getMatches();
		return matches.isEmpty() ? null : matches.get(matches.size() - 1);
	}

	/**
	 * @param gm a GM
	 * @return the match the GM is in (watching it), else the newest one, {@code null} if none runs
	 */
	public ConquestMatch getMatch(Player gm)
	{
		for (ConquestMatch match : _matches.values())
		{
			if (match.getInstanceId() == gm.getInstanceId())
			{
				return match;
			}
		}
		return getMatch();
	}

	/**
	 * @param match a match
	 * @return its fake players still to spawn
	 */
	public int getPendingSpawns(ConquestMatch match)
	{
		final Deque<PartyPlan> toSpawn = _toSpawn.get(match.getId());
		if (toSpawn == null)
		{
			return 0;
		}
		int pending = 0;
		synchronized (toSpawn)
		{
			for (PartyPlan plan : toSpawn)
			{
				pending += plan.slots.size();
			}
		}
		return pending;
	}

	// ---------------------------------------------------------------------------------------------
	// Brains
	// ---------------------------------------------------------------------------------------------

	/** The brains a team can have: the plain one (A), and JEV deciding with it (B, {@link JevConquestCommander}). */
	public static final List<String> BRAINS = List.of("generic", "jev");

	/**
	 * @param name a brain of {@link #BRAINS} (or "a", "b")
	 * @return a new commander of it, {@code null} for an unknown name
	 */
	public static ConquestCommander createCommander(String name)
	{
		return switch (name.trim().toLowerCase(Locale.ROOT))
		{
			case "generic", "a" -> new GenericConquestCommander();
			case "jev", "b" -> new JevConquestCommander();
			default -> null;
		};
	}

	/**
	 * Lets another commander lead both teams of the next matches (a learned one).
	 * @param commanders makes one commander for each team, {@code null} for the ones Conquest.ini names
	 */
	public void setCommanders(Supplier<ConquestCommander> commanders)
	{
		setCommanders(commanders, commanders);
	}

	/**
	 * Sets two commanders against each other in the next matches.
	 * @param blue makes Blue's commander, {@code null} for the one Conquest.ini names (ConquestBlueBrain)
	 * @param red makes Red's, {@code null} for ConquestRedBrain
	 */
	public void setCommanders(Supplier<ConquestCommander> blue, Supplier<ConquestCommander> red)
	{
		_blueBrain = blue;
		_redBrain = red;
	}

	/**
	 * Sets two of the known brains ({@link #BRAINS}) against each other in the next matches.
	 * @param blue Blue's brain, {@code null} for the one Conquest.ini names
	 * @param red Red's
	 * @return what to tell the GM
	 */
	public String setBrains(String blue, String red)
	{
		for (String name : new String[]
		{
			blue,
			red
		})
		{
			if ((name != null) && (createCommander(name) == null))
			{
				return "No brain \"" + name + "\". The brains: " + String.join(", ", BRAINS) + ".";
			}
		}
		setCommanders(blue != null ? () -> createCommander(blue) : null, red != null ? () -> createCommander(red) : null);
		return getBrainReport().get(0);
	}

	/**
	 * @return the brains of the next matches, then how each pairing did since the server started, for the GM
	 */
	public List<String> getBrainReport()
	{
		final List<String> lines = new ArrayList<>();
		lines.add("The next matches: Blue " + makeBrain(_blueBrain, ConquestConfig.BLUE_BRAIN).getName() + " vs Red " + makeBrain(_redBrain, ConquestConfig.RED_BRAIN).getName() + "." + (ConquestConfig.REGISTRATION_BRAIN.isEmpty() ? "" : " Registered matches: " + makeBrain(null, ConquestConfig.REGISTRATION_BRAIN).getName() + " on both sides."));
		for (Map.Entry<String, int[]> entry : _brainResults.entrySet())
		{
			final int[] result = entry.getValue();
			lines.add(entry.getKey() + ": Blue won " + result[0] + ", Red won " + result[1] + ", " + result[2] + " draw(s).");
		}
		return lines;
	}

	private static ConquestCommander makeBrain(Supplier<ConquestCommander> brain, String configured)
	{
		final ConquestCommander commander = brain != null ? brain.get() : createCommander(configured);
		return commander != null ? commander : new GenericConquestCommander();
	}

	private static String describe(ConquestCommander commander)
	{
		final String status = commander.getStatus();
		return commander.getName() + (status.isEmpty() ? "" : " (" + status + ")");
	}

	public static ConquestManager getInstance()
	{
		return SingletonHolder.INSTANCE;
	}

	private static class SingletonHolder
	{
		protected static final ConquestManager INSTANCE = new ConquestManager();
	}
}
