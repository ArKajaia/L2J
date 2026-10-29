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
import org.l2jmobius.gameserver.model.actor.holders.npc.FakePlayerPvpBuild;
import org.l2jmobius.gameserver.model.actor.holders.npc.FakePlayerPvpPersonality;
import org.l2jmobius.gameserver.model.actor.holders.npc.FakePlayerPvpProfile;
import org.l2jmobius.gameserver.model.actor.instance.Door;
import org.l2jmobius.gameserver.model.conquest.ConquestDoor;
import org.l2jmobius.gameserver.model.conquest.ConquestMap;
import org.l2jmobius.gameserver.model.conquest.ConquestPoint;
import org.l2jmobius.gameserver.model.conquest.ConquestPointType;
import org.l2jmobius.gameserver.model.instancezone.Instance;
import org.l2jmobius.gameserver.model.skill.Skill;
import org.l2jmobius.gameserver.model.skill.enums.SkillFinishType;
import org.l2jmobius.gameserver.model.skill.holders.SkillHolder;
import org.l2jmobius.gameserver.network.serverpackets.ExShowScreenMessage;
import org.l2jmobius.gameserver.taskmanagers.DecayTaskManager;
import org.l2jmobius.gameserver.util.QuestSpawnUtil;

/**
 * Conquest: a GM starts a match on a map of {@link org.l2jmobius.gameserver.data.xml.ConquestMapData} (//conquest &lt;map&gt; start). It runs in its own copy of the map's place, without monsters, with the doors the map names opened or shut. For now only
 * fake players fight, in parties of 5, both teams the same (roles, builds, levels).
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

	/** A party to spawn. */
	private static class PartyPlan
	{
		final ConquestTeam team;
		final int number;
		final List<Slot> slots;

		PartyPlan(ConquestTeam team, int number, List<Slot> slots)
		{
			this.team = team;
			this.number = number;
			this.slots = slots;
		}
	}

	private final Map<Integer, ConquestFighter> _fighters = new ConcurrentHashMap<>();
	private final AtomicInteger _nextMatchId = new AtomicInteger(1);
	private final Deque<PartyPlan> _toSpawn = new ArrayDeque<>();
	private volatile ConquestMatch _match;
	private volatile Supplier<ConquestCommander> _commanders = GenericConquestCommander::new;

	protected ConquestManager()
	{
	}

	// ---------------------------------------------------------------------------------------------
	// Start and stop
	// ---------------------------------------------------------------------------------------------

	/**
	 * Starts a match.
	 * @param map the map
	 * @param perTeam the fighters of each team (rounded down to whole parties; at most the map's maxPlayers)
	 * @param minLevel the lowest level of the fake players
	 * @param maxLevel the highest
	 * @return what to tell the GM
	 */
	public synchronized String start(ConquestMap map, int perTeam, int minLevel, int maxLevel)
	{
		if (_match != null)
		{
			return "A Conquest match is already running on " + _match.getMap().name() + ": //conquest stop ends it.";
		}
		if (!FakePlayerPvpManager.getInstance().isEnabled())
		{
			return "Conquest needs the fake players: EnableFakePlayers in FakePlayers.ini and FakePvpEnabled in FakePlayerPvp.ini.";
		}
		if (map.getPoints(ConquestPointType.BLUE).isEmpty() || map.getPoints(ConquestPointType.RED).isEmpty() || map.getPoints(ConquestPointType.CAPTURE).isEmpty())
		{
			return map.name() + " needs spawn points for both teams and at least one capture point.";
		}

		final int size = Math.max(PARTY_SIZE, (Math.min(perTeam, map.maxPlayers()) / PARTY_SIZE) * PARTY_SIZE);
		final Instance instance = InstanceManager.getInstance().createDynamicInstance(INSTANCE_TEMPLATE_ID);
		if (instance == null)
		{
			return "Could not create the instance (data/instances/custom/conquest_event.xml, id " + INSTANCE_TEMPLATE_ID + ").";
		}

		final ConquestMatch match = new ConquestMatch(_nextMatchId.getAndIncrement(), map, instance.getId(), size, minLevel, maxLevel, _commanders.get(), _commanders.get());
		setUpDoors(map, instance);
		keepDoors(match);
		_match = match;
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

		// Both teams the same: the same roles, builds and levels, party for party.
		final ThreadLocalRandom random = ThreadLocalRandom.current();
		synchronized (_toSpawn)
		{
			_toSpawn.clear();
			for (int number = 1; number <= (size / PARTY_SIZE); number++)
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
				_toSpawn.add(new PartyPlan(match.getBlue(), number, slots));
				_toSpawn.add(new PartyPlan(match.getRed(), number, slots));
			}
		}

		match._prepEnd = System.currentTimeMillis() + (ConquestConfig.PREP_TIME * 1000L);
		match._spawnTask = ThreadPool.scheduleAtFixedRate(() -> spawnSome(match), 0, SPAWN_INTERVAL);
		match._task = ThreadPool.scheduleAtFixedRate(() -> tick(match), TICK_MS, TICK_MS);
		match.log("Match " + match.getId() + " on " + map.name() + ": " + size + " vs " + size + ", levels " + minLevel + "-" + maxLevel + ".");
		LOGGER.info(getClass().getSimpleName() + ": Match " + match.getId() + " started on " + map.name() + ", " + size + " vs " + size + ", levels " + minLevel + "-" + maxLevel + ", instance " + instance.getId() + ".");
		return "Conquest on " + map.name() + ": " + size + " vs " + size + " fake players, levels " + minLevel + "-" + maxLevel + ". The fight starts in " + ConquestConfig.PREP_TIME + " seconds. //conquest live shows it, //conquest watch takes you there.";
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
				final PartyPlan plan;
				synchronized (_toSpawn)
				{
					plan = _toSpawn.pollFirst();
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
				spawned += plan.slots.size();
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
		for (Slot slot : plan.slots)
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
		if (fakes.isEmpty())
		{
			return;
		}

		// Stopped while it spawned: the cleanup has gone by already.
		if ((match._phase == ConquestMatch.Phase.ENDED) || (_match != match))
		{
			for (ConquestFighter fighter : party.getMembers())
			{
				removeFake(fighter.getNpc());
				_fighters.remove(fighter.getNpc().getObjectId(), fighter);
				team.getFighters().remove(fighter);
			}
			return;
		}

		if (fakes.size() > 1)
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

	/**
	 * Ends the match running now, without a result.
	 * @return what to tell the GM
	 */
	public synchronized String stop()
	{
		final ConquestMatch match = _match;
		if (match == null)
		{
			return "No Conquest match is running.";
		}
		if (match._phase == ConquestMatch.Phase.ENDED)
		{
			return "The match on " + match.getMap().name() + " is ending already.";
		}
		endMatch(match, null, "Stopped by a GM.", true);
		return "The match on " + match.getMap().name() + " was stopped.";
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
						final Npc npc = fighter.getNpc();
						if (npc.isDead() || npc.isTeleporting())
						{
							continue;
						}
						final ConquestPoint spawn = fighter.getTeam().getClosestSpawn(npc);
						if (Math.hypot(spawn.x() - npc.getX(), spawn.y() - npc.getY()) > (spawn.radius() + ConquestConfig.BASE_RADIUS + PREP_LEASH))
						{
							sendHome(match, fighter, new Location(spawn.x(), spawn.y(), spawn.z(), spawn.heading()), spawn.radius());
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
		command(match, now);
		for (ConquestFighter fighter : match.getAllFighters())
		{
			final Npc fake = fighter.getNpc();
			if (!fake.isDead())
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
			final Npc npc = fighter.getNpc();
			if (npc.isDead())
			{
				if ((fighter._respawnAt > 0) && (now >= fighter._respawnAt))
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
				continue;
			}

			// Nobody stays inside a wall or the rock (a knock back, a skill, a move with no way there): back on the nearest ground.
			if (!match.getNav().isOnGround(npc.getX(), npc.getY(), npc.getZ()))
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
				continue;
			}

			if (fighter.isProtected() && (now >= fighter._protectedUntil))
			{
				unprotect(fighter);
			}
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

		if ((match._ticks % ConquestConfig.COMMANDER_INTERVAL) == 0)
		{
			command(match, now);
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
			final Npc npc = fighter.getNpc();
			if (!npc.isDead() && capture.contains(npc.getX(), npc.getY(), npc.getZ()))
			{
				count++;
			}
		}
		return count;
	}

	private static void command(ConquestMatch match, long now)
	{
		final long start = System.nanoTime();
		for (ConquestTeam team : new ConquestTeam[]
		{
			match.getBlue(),
			match.getRed()
		})
		{
			try
			{
				team.getCommander().command(match, team, now);
			}
			catch (Exception e)
			{
				LOGGER.log(Level.WARNING, ConquestManager.class.getSimpleName() + ": The " + team.getName() + " commander failed.", e);
			}
		}
		match._lastCommandNanos = System.nanoTime() - start;
	}

	// ---------------------------------------------------------------------------------------------
	// Fighters
	// ---------------------------------------------------------------------------------------------

	private void respawn(ConquestMatch match, ConquestFighter fighter)
	{
		fighter._respawnAt = 0;
		final Npc fake = fighter.getNpc();
		if (!fake.isDead())
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
		DecayTaskManager.getInstance().cancel(fake);
		fake.doRevive();
		sendHome(match, fighter, spot != null ? spot : base, spot != null ? PARTY_SPREAD : spreadAt(fighter.getTeam(), base));
		FakePartyManager.getInstance().onRevived(fake);
		FakePlayerPvpManager.getInstance().prepareSimulant(fake);
		fake.setRunning();
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
		final Npc npc = fighter.getNpc();
		final Location spot = spawnSpot(location, radius, match.getInstanceId());
		npc.teleToLocation(spot.getX(), spot.getY(), spot.getZ(), location.getHeading(), match.getInstanceId(), 0);
		restartAI(npc);
		bringServitor(match, npc);
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
			if (owner.isDead() || owner.isTeleporting())
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

		final Npc npc = fighter.getNpc();
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
		final Npc npc = fighter.getNpc();
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
		final ConquestMatch match = _match;
		if ((dead == null) || (match == null) || (match._phase != ConquestMatch.Phase.FIGHT))
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

		final int blue = match.getBlue().getScore();
		final int red = match.getRed().getScore();
		match._result = abandoned ? reason : (winner == null ? "Draw, " + blue + " : " + red + ". " + reason : (winner == Team.BLUE ? "Blue" : "Red") + " wins, " + blue + " : " + red + ". " + reason);
		match.log(match._result);
		announce(match, match._result);
		for (ConquestFighter fighter : match.getAllFighters())
		{
			final Npc npc = fighter.getNpc();
			npc.abortAttack();
			npc.abortCast();
			npc.setInvul(true);
			if (npc.hasAI())
			{
				npc.getAI().setIntention(Intention.IDLE);
			}
		}

		LOGGER.info(getClass().getSimpleName() + ": Match " + match.getId() + " on " + match.getMap().name() + " ended: " + match._result);
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
			synchronized (_toSpawn)
			{
				_toSpawn.clear();
			}

			for (ConquestTeam team : new ConquestTeam[]
			{
				match.getBlue(),
				match.getRed()
			})
			{
				for (ConquestParty party : team.getParties())
				{
					if (party.getFakeParty() != null)
					{
						FakePartyManager.getInstance().disbandSimulationParty(party.getFakeParty());
					}
				}
			}

			int removed = 0;
			for (ConquestFighter fighter : match.getAllFighters())
			{
				removeFake(fighter.getNpc());
				_fighters.remove(fighter.getNpc().getObjectId(), fighter);
				removed++;
			}
			LOGGER.info(getClass().getSimpleName() + ": Match " + match.getId() + ": removed " + removed + " fake players (" + _fighters.size() + " left registered).");
		}
		catch (Exception e)
		{
			LOGGER.log(Level.WARNING, getClass().getSimpleName() + ": Problem while cleaning up match " + match.getId() + ".", e);
		}
		finally
		{
			releaseRegions(match);
			InstanceManager.getInstance().destroyInstance(match.getInstanceId());
			synchronized (this)
			{
				if (_match == match)
				{
					_match = null;
				}
				ACTIVE = _match != null;
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
	// Watching
	// ---------------------------------------------------------------------------------------------

	/**
	 * Takes a GM into the match's copy of the map, at its middle capture point.
	 * @param gm the GM
	 * @return what to tell the GM
	 */
	public String watch(Player gm)
	{
		final ConquestMatch match = _match;
		if (match == null)
		{
			return "No Conquest match is running.";
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
		final Location spot = closest.getLocation();
		gm.teleToLocation(spot.getX(), spot.getY(), spot.getZ(), gm.getHeading(), match.getInstanceId());
		return "Watching " + map.name() + " at capture point " + closest.getName() + ". The fake players leave you alone. //conquest leave takes you back.";
	}

	/**
	 * Takes a GM watching a match back to the open world (Giran, the instance's exit).
	 * @param gm the GM
	 * @return what to tell the GM
	 */
	public String leave(Player gm)
	{
		final ConquestMatch match = _match;
		if ((match == null) || (gm.getInstanceId() != match.getInstanceId()))
		{
			return "You are not watching a Conquest match.";
		}
		final Instance instance = InstanceManager.getInstance().getInstance(match.getInstanceId());
		final Location exit = (instance != null) && (instance.getExitLoc() != null) ? instance.getExitLoc() : new Location(83425, 148585, -3406);
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

	private static void releaseRegions(ConquestMatch match)
	{
		synchronized (match._keptRegions)
		{
			for (WorldRegion region : match._keptRegions)
			{
				region.releaseKeepActive();
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
		if ((fa == null) || (fb == null))
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
		final ConquestMatch match = getInstance()._match;
		final ConquestFighter fa = getFighter(attacker);
		final ConquestFighter fb = getFighter(target);
		return (match != null) && (match._phase == ConquestMatch.Phase.FIGHT) && (fa != null) && (fb != null) && !fa.getTeam().isInBase(attacker.getX(), attacker.getY()) && !fb.getTeam().isInBase(target.getX(), target.getY());
	}

	/**
	 * @param fake a fake player
	 * @return the phase of its match, {@code null} if it fights in none
	 */
	public ConquestMatch.Phase getPhase(Npc fake)
	{
		final ConquestMatch match = _match;
		final FakePlayerPvpProfile profile = fake.getTemplate().getFakePlayerPvpProfile();
		return (match != null) && (profile != null) && (profile.getConquestMatchId() == match.getId()) ? match._phase : null;
	}

	/**
	 * @param fake a fake player of the match
	 * @return {@code true} if it stands in its own team's base
	 */
	public boolean isAtOwnBase(Npc fake)
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
		final ConquestMatch match = _match;
		if ((fighter == null) || (match == null) || (match._phase != ConquestMatch.Phase.FIGHT))
		{
			return null;
		}

		final long now = System.currentTimeMillis();
		final ConquestTeam other = match.getOther(fighter.getTeam());
		final Npc seen = fighter._seen;
		if ((now < fighter._seenUntil) && ((seen == null) || isEngageable(fake, seen, other)))
		{
			return seen;
		}

		final double knowledge = FakePlayerPvpPersonality.of(fake).getKnowledge();
		final double sight = SIGHT_RANGE * (1 - (SIGHT_KNOWLEDGE_LOSS * (1 - knowledge)));
		final List<ConquestFighter> near = match.getNear(other, fake.getX(), fake.getY(), (int) sight);
		near.sort(Comparator.comparingDouble(enemy -> fake.calculateDistance2D(enemy.getNpc())));
		Npc best = null;
		double bestScore = Double.MAX_VALUE;
		int checked = 0;
		for (ConquestFighter enemy : near)
		{
			final Npc npc = enemy.getNpc();
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

	private static boolean isEngageable(Npc fake, Npc enemy, ConquestTeam enemyTeam)
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
		final ConquestMatch match = _match;
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
		final ConquestMatch match = _match;
		if ((fighter == null) || (match == null))
		{
			return null;
		}

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
	 * @return the match running now (or ending), {@code null} if none
	 */
	public ConquestMatch getMatch()
	{
		return _match;
	}

	/**
	 * @return the fake players still to spawn
	 */
	public int getPendingSpawns()
	{
		int pending = 0;
		synchronized (_toSpawn)
		{
			for (PartyPlan plan : _toSpawn)
			{
				pending += plan.slots.size();
			}
		}
		return pending;
	}

	/**
	 * Lets another commander lead the teams of the next matches (a learned one).
	 * @param commanders makes one commander for each team
	 */
	public void setCommanders(Supplier<ConquestCommander> commanders)
	{
		_commanders = commanders != null ? commanders : GenericConquestCommander::new;
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
