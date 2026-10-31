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
package org.l2jmobius.gameserver.managers;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.l2jmobius.commons.threads.ThreadPool;
import org.l2jmobius.commons.time.SchedulingPattern;
import org.l2jmobius.commons.time.TimeUtil;
import org.l2jmobius.commons.util.Rnd;
import org.l2jmobius.gameserver.ai.AttackableAI;
import org.l2jmobius.gameserver.ai.FakeBrain;
import org.l2jmobius.gameserver.ai.Intention;
import org.l2jmobius.gameserver.config.custom.DualboxCheckConfig;
import org.l2jmobius.gameserver.config.custom.TeamVsTeamConfig;
import org.l2jmobius.gameserver.data.enums.CategoryType;
import org.l2jmobius.gameserver.geoengine.GeoEngine;
import org.l2jmobius.gameserver.geoengine.pathfinding.PathFinding;
import org.l2jmobius.gameserver.managers.simulation.SimRole;
import org.l2jmobius.gameserver.managers.simulation.SimulationComposer;
import org.l2jmobius.gameserver.model.Location;
import org.l2jmobius.gameserver.model.World;
import org.l2jmobius.gameserver.model.WorldRegion;
import org.l2jmobius.gameserver.model.actor.Creature;
import org.l2jmobius.gameserver.model.actor.Npc;
import org.l2jmobius.gameserver.model.actor.Player;
import org.l2jmobius.gameserver.model.actor.enums.creature.Team;
import org.l2jmobius.gameserver.model.actor.holders.npc.FakePlayerHolder;
import org.l2jmobius.gameserver.model.actor.holders.npc.FakePlayerParty;
import org.l2jmobius.gameserver.model.actor.instance.Fence;
import org.l2jmobius.gameserver.model.actor.holders.npc.FakePlayerPvpBuild;
import org.l2jmobius.gameserver.model.actor.holders.npc.FakePlayerPvpPersonality;
import org.l2jmobius.gameserver.model.actor.holders.npc.FakePlayerPvpProfile;
import org.l2jmobius.gameserver.model.groups.Party;
import org.l2jmobius.gameserver.model.instancezone.Instance;
import org.l2jmobius.gameserver.model.item.enums.ItemProcessType;
import org.l2jmobius.gameserver.model.item.holders.ItemChanceHolder;
import org.l2jmobius.gameserver.model.item.holders.ItemHolder;
import org.l2jmobius.gameserver.model.item.type.WeaponType;
import org.l2jmobius.gameserver.model.item.Weapon;
import org.l2jmobius.gameserver.model.olympiad.OlympiadManager;
import org.l2jmobius.gameserver.model.script.Quest;
import org.l2jmobius.gameserver.model.skill.AbnormalVisualEffect;
import org.l2jmobius.gameserver.model.skill.CommonSkill;
import org.l2jmobius.gameserver.model.skill.Skill;
import org.l2jmobius.gameserver.model.skill.enums.SkillFinishType;
import org.l2jmobius.gameserver.model.skill.holders.SkillHolder;
import org.l2jmobius.gameserver.model.zone.ZoneId;
import org.l2jmobius.gameserver.network.serverpackets.Ex2TeamEventState;
import org.l2jmobius.gameserver.network.serverpackets.ExShowScreenMessage;
import org.l2jmobius.gameserver.network.serverpackets.MagicSkillUse;
import org.l2jmobius.gameserver.network.serverpackets.NicknameChanged;
import org.l2jmobius.gameserver.network.serverpackets.ServerPacket;
import org.l2jmobius.gameserver.taskmanagers.AttackableThinkTaskManager;
import org.l2jmobius.gameserver.taskmanagers.DecayTaskManager;
import org.l2jmobius.gameserver.util.Broadcast;
import org.l2jmobius.gameserver.util.QuestSpawnUtil;

/**
 * Team vs Team: every {@link TeamVsTeamConfig#SCHEDULE} the TvT Event Manager takes registrations, then every level bracket with players gets its own copy of the Ruins of Despair (an instance), each team with its spawn points on its own half of the ring wall ({@link TeamVsTeamConfig#BLUE_BASES}, {@link TeamVsTeamConfig#RED_BASES}), {@link TeamVsTeamConfig#TEAM_SIZE} a side.
 * <ul>
 * <li>A party plays in one team. One whose members are in different brackets is broken up, each member going to its own bracket (the members of a bracket stay together).</li>
 * <li>The real players are spread evenly between the teams, and the free places are filled with fake players of the bracket's levels. A party of players gets the fake players it lacks (a tank, a healer, an archer...) up to {@link TeamVsTeamConfig#PARTY_SIZE}, the rest of a team
 * fights in parties of fake players. Fake players fight as parties (they call targets, heal, buff and resurrect each other), help the team mates that fight nearby, and go for the closest enemies otherwise.</li>
 * <li>The faction system's rules hold between the teams: the other team is attacked without Ctrl, the own team can't be hurt even with Ctrl, and no kill gives karma (the instance is a PvP one). Everyone shows a blue or red circle and name.</li>
 * <li>The dead come back in waves, each wave at one of its team's spawn points picked by chance. Each kill gives the killer one of {@link TeamVsTeamConfig#KILL_REWARDS}; the team with the most kills wins, and each of its players gets {@link TeamVsTeamConfig#WIN_REWARD}.</li>
 * </ul>
 * The dialogs of the manager are in the TvT event script (custom/events/TeamVsTeam).
 */
public class TeamVsTeamManager
{
	private static final Logger LOGGER = Logger.getLogger(TeamVsTeamManager.class.getName());

	/** The TvT Event Manager. */
	public static final int MANAGER_NPC_ID = 70010;
	/** data/instances/custom/tvt_event.xml: a PvP instance without spawns. */
	public static final int INSTANCE_TEMPLATE_ID = 3051;

	/** {@code true} while a match goes on: the combat hooks only look further then. */
	public static volatile boolean ACTIVE = false;

	private static final long TICK_MS = 1000;
	/** A fake player engages an enemy it sees this close (or that is very close, seen or not). */
	private static final int SIGHT_RANGE = 1200;
	private static final int CLOSE_ENEMY_RANGE = 150;
	/** A fake player with no enemy in sight goes to help team mates fighting this close. */
	private static final int HELP_RANGE = 2500;
	/** A fake player doesn't march on enemies this close to the edge of their base (it fights them if it sees them). */
	private static final int BASE_APPROACH = 600;
	/** A fighter with no living team mate this close is alone: the fake players look for enemies alone, and one alone doesn't take on a group. */
	private static final int ALONE_RANGE = 700;
	/** A fake player that knows the game least (see {@link FakePlayerPvpPersonality#getKnowledge}) sees enemies this share of {@link #SIGHT_RANGE} less far. */
	private static final double SIGHT_KNOWLEDGE_LOSS = 0.2;
	/** Picking the enemy to engage, for one that knows the game best: an enemy alone, or one low on HP, counts as this much closer (a share of {@link #SIGHT_RANGE}), one in a group as farther for one alone. */
	private static final double LONE_TARGET_BONUS = 0.35;
	private static final double LOW_TARGET_BONUS = 0.3;
	private static final double GROUP_TARGET_PENALTY = 0.3;
	/** A fake player that knows the game best picks off an enemy alone this close before it joins a fight further away (see {@link #getMarchTarget}). */
	private static final int LONE_PICK_RANGE = 1500;
	/** Fake players on their own (see {@link #goLone}): at most this share of a team's fake players at once, and at least this many. */
	private static final int LONE_SHARE_PERCENT = 25;
	private static final int LONE_MIN = 2;
	/** A fake player waiting in ambush at its corner is noticed by an enemy fake player only this close, and this much more for one that knows the game best. */
	private static final int AMBUSH_NOTICE_MIN = 350;
	private static final int AMBUSH_NOTICE_KNOWLEDGE = 350;
	/** It stands at its ambush spot this close. */
	public static final int AMBUSH_REACHED = 60;
	/** An ambush spot lies this far from the enemies' way (see {@link #findAmbushSpot}), and that way at most this far from the one looking. */
	private static final int AMBUSH_MIN_DISTANCE = 180;
	private static final int AMBUSH_MAX_DISTANCE = 650;
	private static final int AMBUSH_SEARCH_RANGE = 1800;
	/** Looking for a wall at an ambush spot: so many ways round it, this far. A spot with a wall on 2 to 5 of them is by a wall or in a corner, not in the open nor in a hole. */
	private static final int AMBUSH_PROBES = 8;
	private static final int AMBUSH_PROBE_LENGTH = 90;
	private static final int AMBUSH_MIN_WALLS = 2;
	private static final int AMBUSH_MAX_WALLS = 5;
	/** A kill this soon after its killer left its ambush counts as one from ambush (the simulated matches' log). */
	private static final long AMBUSH_KILL_TIME = 20000;
	/** A fake player roaming on its own goes to spots of the field this far from it. */
	private static final int WANDER_MIN_DISTANCE = 600;
	private static final int WANDER_MAX_DISTANCE = 2500;
	/** The parties of a team march in lanes (see {@link #inLane}): this far apart across the field. */
	private static final int LANE_WIDTH = 450;
	/** A lane narrows from this far from where it leads, and is gone this close to it: the parties come from the sides, then close in together. */
	private static final int LANE_TAPER = 1800;
	private static final int LANE_MERGE = 350;
	/** The lanes, in the order a team's parties take them: the middle, left, right. */
	private static final int[] LANES =
	{
		0,
		-1,
		1
	};
	/** How long the result stays on screen before everyone goes home. */
	private static final long END_DELAY = 10000;
	/** The fake players of a match say something at most once in this many milliseconds, all of them together (see {@link #mayChat}). */
	private static final int CHAT_MIN_GAP = 8000;
	private static final int CHAT_MAX_GAP = 20000;
	/** Registration reminders, in minutes before the end of it. */
	private static final int[] WARNINGS =
	{
		5,
		3,
		1
	};
	private static final SkillHolder GHOST_WALKING = new SkillHolder(100000, 1); // Spawn protection look (Ultimate Defense effect).
	/** A player close to an enemy base is warned again at most this often. */
	private static final long BASE_WARNING_INTERVAL = 6000;
	/** A fake player this close to the edge of an enemy base turns back (it checks every second: farther than it runs in one). */
	private static final int FAKE_TURN_BACK_DISTANCE = 350;
	/** It turns back toward the middle of the field, this far. */
	private static final int TURN_BACK_STEP = 500;
	/** Someone this far beyond the fence (a skill that moved them, a gap in it) is brought back inside it. */
	private static final int BARRIER_MARGIN = 250;

	public enum Phase
	{
		/** Teleported: buffs, nobody leaves the bases. */
		PREP,
		/** The fight. */
		FIGHT,
		/** The result is shown, everyone goes home shortly. */
		ENDED
	}

	/** How two creatures stand to each other in the event. */
	public enum Relation
	{
		/** Neither is in a match. */
		NONE,
		/** Same match, same team. */
		ALLY,
		/** Same match, other team. */
		ENEMY,
		/** Only one of them is in a match, or they are in different ones: they leave each other alone. */
		STRANGER
	}

	/**
	 * A player or fake player of a match.
	 */
	public static class Fighter
	{
		private final Creature _creature;
		private final Match _match;
		private final Side _side;
		private final SimRole _role;
		// Where a player was before the match, its name color and its own title (it shows its score meanwhile, see showScore).
		private Location _returnLocation;
		private int _nameColor;
		private volatile String _title;
		private volatile int _kills;
		private volatile int _deaths;
		private volatile long _respawnAt;
		// The spawn point of its wave, where it comes back.
		private volatile Location _respawnBase;
		private volatile long _protectedUntil;
		// Worked out by the tick: how many living team mates stand within ALONE_RANGE of it.
		private volatile int _alliesNear;
		// A fake player on its own (see goLone): until when, the corner it waits at in ambush (null for none), and when it last left one to jump someone.
		private volatile long _loneUntil;
		private volatile Location _ambushSpot;
		private volatile long _ambushSprungAt;
		// Stunned in an enemy base until it dies (see trap), and when a player may be warned again that it comes close to one.
		private volatile boolean _trapped;
		private long _nextBaseWarning;

		Fighter(Creature creature, Match match, Side side, SimRole role)
		{
			_creature = creature;
			_match = match;
			_side = side;
			_role = role;
		}

		public Creature getCreature()
		{
			return _creature;
		}

		public Match getMatch()
		{
			return _match;
		}

		public Side getSide()
		{
			return _side;
		}

		public boolean isPlayer()
		{
			return _creature.isPlayer();
		}

		public int getKills()
		{
			return _kills;
		}

		public int getDeaths()
		{
			return _deaths;
		}

		/**
		 * @return {@code true} while it is under its spawn protection
		 */
		public boolean isProtected()
		{
			return _protectedUntil > 0;
		}

		boolean isAlive()
		{
			return !_creature.isDead() && (_creature.isPlayer() || _creature.isSpawned());
		}

		/**
		 * @return {@code true} if it is a fake player on its own for now (see {@link TeamVsTeamManager#goLone})
		 */
		boolean isLone()
		{
			return System.currentTimeMillis() < _loneUntil;
		}

		/**
		 * @return {@code true} if it stands still at its ambush spot, waiting
		 */
		boolean isInAmbush()
		{
			final Location spot = _ambushSpot;
			return (spot != null) && !_creature.isMoving() && (_creature.calculateDistance2D(spot) <= AMBUSH_REACHED);
		}
	}

	/**
	 * Blue or Red of a match.
	 */
	public static class Side
	{
		private final Team _team;
		private final String _name;
		// Its spawn points: it starts at the first one, its dead come back at one of them.
		private final List<Location> _bases;
		private final Location _base;
		private final int _nameColor;
		private final List<Fighter> _fighters = new CopyOnWriteArrayList<>();
		private final List<FakePlayerParty> _parties = new CopyOnWriteArrayList<>();
		private final AtomicInteger _score = new AtomicInteger();
		// Worked out by the tick for the fake players: where team mates fight right now, and where the enemy groups are.
		private volatile List<Location> _clashes = List.of();
		// When the next group of its dead comes back, and where: the ones that die close together come back together at one spawn point, instead of one after the other into the enemies.
		private long _nextWave;
		private Location _waveBase;
		private volatile List<Location> _enemyGroups = List.of();
		// Worked out by the tick too: where the enemies alone are (out of their base), which the fake players on their own hunt.
		private volatile List<Location> _loneEnemies = List.of();
		// The lane of each of its parties (and of its members on their own), see inLane.
		private final Map<Object, Integer> _lanes = new ConcurrentHashMap<>();
		private final AtomicInteger _nextLane = new AtomicInteger(Rnd.get(LANES.length));

		Side(Team team, String name, List<Location> bases, int nameColor)
		{
			_team = team;
			_name = name;
			_bases = bases;
			_base = bases.get(0);
			_nameColor = nameColor;
		}

		public Team getTeam()
		{
			return _team;
		}

		public String getName()
		{
			return _name;
		}

		/**
		 * @return the spawn point it starts at
		 */
		public Location getBase()
		{
			return _base;
		}

		/**
		 * @return its spawn points
		 */
		public List<Location> getBases()
		{
			return _bases;
		}

		/**
		 * @param creature a creature
		 * @return how far it stands from the closest of these spawn points
		 */
		double getBaseDistance(Creature creature)
		{
			double closest = Double.MAX_VALUE;
			for (Location base : _bases)
			{
				closest = Math.min(closest, creature.calculateDistance2D(base));
			}
			return closest;
		}

		/**
		 * @param x a spot's x
		 * @param y a spot's y
		 * @return how far the spot lies from the closest of these spawn points
		 */
		double getBaseDistance(int x, int y)
		{
			double closest = Double.MAX_VALUE;
			for (Location base : _bases)
			{
				closest = Math.min(closest, Math.hypot(base.getX() - x, base.getY() - y));
			}
			return closest;
		}

		/**
		 * @param creature a creature
		 * @return the closest of these spawn points
		 */
		Location getClosestBase(Creature creature)
		{
			Location closest = _base;
			for (Location base : _bases)
			{
				if (creature.calculateDistance2D(base) < creature.calculateDistance2D(closest))
				{
					closest = base;
				}
			}
			return closest;
		}

		/**
		 * @return the middle of its spawn points
		 */
		Location getCenter()
		{
			return center(_bases);
		}

		public int getScore()
		{
			return _score.get();
		}
		
		/**
		 * Puts one that died into a wave: the wave already waiting if it comes in at least half the respawn delay, else a new wave after the full delay, at a spawn point picked by chance.
		 * @param dead the one that died
		 * @param now the time of its death
		 */
		synchronized void joinWave(Fighter dead, long now)
		{
			final long delay = TeamVsTeamConfig.RESPAWN_DELAY * 1000L;
			if (((_nextWave - now) < (delay / 2)) || (_waveBase == null))
			{
				_nextWave = now + delay;
				_waveBase = _bases.get(Rnd.get(_bases.size()));
			}
			dead._respawnAt = _nextWave;
			dead._respawnBase = _waveBase;
		}

		/**
		 * @param group a party, or a fighter on its own
		 * @return its lane: -1, 0 or 1 (the parties take them in turns)
		 */
		int getLane(Object group)
		{
			return _lanes.computeIfAbsent(group, _ -> LANES[_nextLane.getAndIncrement() % LANES.length]);
		}

		public List<Fighter> getFighters()
		{
			return _fighters;
		}

		public int getPlayerCount()
		{
			int count = 0;
			for (Fighter fighter : _fighters)
			{
				if (fighter.isPlayer())
				{
					count++;
				}
			}
			return count;
		}
	}

	/**
	 * One instance of the Ruins of Despair, for one bracket.
	 */
	public static class Match
	{
		private final int _id;
		private final int _minLevel;
		private final int _maxLevel;
		private final int _instanceId;
		private final Side _blue;
		private final Side _red;
		private final long _prepEnd;
		private final Set<WorldRegion> _keptRegions = new HashSet<>();
		// The GMs watching it (//tvt watch): object ids. They get its event strip and radar zones too.
		private final Set<Integer> _watchers = ConcurrentHashMap.newKeySet();
		private volatile Phase _phase = Phase.PREP;
		private volatile long _fightEnd;
		private volatile ScheduledFuture<?> _task;
		// A match of fake players only (//tvt simulate): it plays to its end without players, and logs its kills.
		private volatile boolean _simulated;
		// When its fake players may say something again.
		private long _nextChat;
		// The fence round its field (see TeamVsTeamBarrier).
		private final List<Fence> _barrier = new ArrayList<>();

		Match(int id, int minLevel, int maxLevel, int instanceId)
		{
			_id = id;
			_minLevel = minLevel;
			_maxLevel = maxLevel;
			_instanceId = instanceId;
			_blue = new Side(Team.BLUE, "Blue", TeamVsTeamConfig.BLUE_BASES, TeamVsTeamConfig.BLUE_NAME_COLOR);
			_red = new Side(Team.RED, "Red", TeamVsTeamConfig.RED_BASES, TeamVsTeamConfig.RED_NAME_COLOR);
			_prepEnd = System.currentTimeMillis() + (TeamVsTeamConfig.PREP_TIME * 1000L);
		}

		public int getId()
		{
			return _id;
		}

		public String getBracketName()
		{
			return _minLevel == _maxLevel ? String.valueOf(_minLevel) : _minLevel + "-" + _maxLevel;
		}

		public int getInstanceId()
		{
			return _instanceId;
		}

		public Phase getPhase()
		{
			return _phase;
		}

		public Side getBlue()
		{
			return _blue;
		}

		public Side getRed()
		{
			return _red;
		}

		public Side getOther(Side side)
		{
			return side == _blue ? _red : _blue;
		}

		/**
		 * @return the seconds of fighting left (or of preparation, before the fight)
		 */
		public int getSecondsLeft()
		{
			final long end = _phase == Phase.PREP ? _prepEnd : _fightEnd;
			return (int) Math.max(0, (end - System.currentTimeMillis()) / 1000);
		}

		List<Fighter> getAllFighters()
		{
			final List<Fighter> all = new ArrayList<>(_blue._fighters);
			all.addAll(_red._fighters);
			return all;
		}

		List<Player> getPlayers()
		{
			final List<Player> players = new ArrayList<>();
			for (Fighter fighter : getAllFighters())
			{
				if (fighter.isPlayer())
				{
					players.add(fighter._creature.asPlayer());
				}
			}
			return players;
		}

		void broadcast(ServerPacket packet)
		{
			for (Player player : getPlayers())
			{
				player.sendPacket(packet);
			}
		}
	}

	private enum State
	{
		INACTIVE,
		REGISTRATION
	}

	private final AtomicInteger _nextMatchId = new AtomicInteger(1);
	private final Map<Integer, Match> _matches = new ConcurrentHashMap<>();
	/** Every player and fake player of a match, by object id. */
	private final Map<Integer, Fighter> _fighters = new ConcurrentHashMap<>();
	/** The registered players by object id, in the order they registered. */
	private final Map<Integer, Long> _registered = new ConcurrentHashMap<>();
	private final List<Npc> _managers = new CopyOnWriteArrayList<>();
	private final List<ScheduledFuture<?>> _registrationTasks = new CopyOnWriteArrayList<>();
	private volatile State _state = State.INACTIVE;
	private volatile long _registrationEnd;
	private ScheduledFuture<?> _scheduleTask;

	protected TeamVsTeamManager()
	{
		if (!TeamVsTeamConfig.ENABLED)
		{
			return;
		}

		scheduleNext();
	}

	// ---------------------------------------------------------------------------------------------
	// Schedule and registration
	// ---------------------------------------------------------------------------------------------

	private synchronized void scheduleNext()
	{
		if (_scheduleTask != null)
		{
			_scheduleTask.cancel(false);
			_scheduleTask = null;
		}

		final long delay;
		try
		{
			delay = new SchedulingPattern(TeamVsTeamConfig.SCHEDULE).getDelayToNextFromNow() + 5000; // 5 seconds late, so it never runs twice for one time.
		}
		catch (Exception e)
		{
			LOGGER.warning(getClass().getSimpleName() + ": Bad TvTSchedule \"" + TeamVsTeamConfig.SCHEDULE + "\": the event doesn't start by itself.");
			return;
		}

		_scheduleTask = ThreadPool.schedule(() ->
		{
			final String refusal = openRegistration();
			if (refusal != null)
			{
				LOGGER.info(getClass().getSimpleName() + ": Registration did not open: " + refusal + ".");
			}
			scheduleNext();
		}, delay);
		LOGGER.info(getClass().getSimpleName() + ": Next registration at " + TimeUtil.getDateTimeString(System.currentTimeMillis() + delay) + ".");
	}

	/**
	 * Opens the registration (the schedule, or an admin's //event).
	 * @return {@code null} if it opened, else why not
	 */
	public synchronized String openRegistration()
	{
		if (!TeamVsTeamConfig.ENABLED)
		{
			return "the event is disabled";
		}
		if (_state != State.INACTIVE)
		{
			return "registration is already open";
		}
		if (TeamVsTeamConfig.BRACKETS.isEmpty())
		{
			return "there is no level bracket (TvTBrackets)";
		}
		if ((TeamVsTeamConfig.BLUE_BASE == null) || (TeamVsTeamConfig.RED_BASE == null))
		{
			return "a team has no spawn point (TvTBlueBase, TvTRedBase)";
		}

		_state = State.REGISTRATION;
		_registered.clear();
		_registrationEnd = System.currentTimeMillis() + (TeamVsTeamConfig.REGISTRATION_TIME * 60000L);
		if (DualboxCheckConfig.DUALBOX_CHECK_MAX_L2EVENT_PARTICIPANTS_PER_IP > 0)
		{
			AntiFeedManager.getInstance().registerEvent(AntiFeedManager.L2EVENT_ID);
			AntiFeedManager.getInstance().clear(AntiFeedManager.L2EVENT_ID);
		}

		for (Location location : TeamVsTeamConfig.MANAGER_LOCATIONS)
		{
			final Npc manager = Quest.addSpawn(MANAGER_NPC_ID, location, false, 0);
			if (manager != null)
			{
				manager.setRandomWalking(false);
				_managers.add(manager);
			}
		}

		for (int warning : WARNINGS)
		{
			if (TeamVsTeamConfig.REGISTRATION_TIME > warning)
			{
				_registrationTasks.add(ThreadPool.schedule(() -> Broadcast.toAllOnlinePlayers("TvT: Registration closes in " + warning + " minute" + (warning > 1 ? "s" : "") + ". " + _registered.size() + " registered so far."), (TeamVsTeamConfig.REGISTRATION_TIME - warning) * 60000L));
			}
		}
		_registrationTasks.add(ThreadPool.schedule(this::closeRegistration, TeamVsTeamConfig.REGISTRATION_TIME * 60000L));

		Broadcast.toAllOnlinePlayers("TvT: Registration is open for " + TeamVsTeamConfig.REGISTRATION_TIME + " minutes at the TvT Event Manager in Giran. " + TeamVsTeamConfig.TEAM_SIZE + " vs " + TeamVsTeamConfig.TEAM_SIZE + " in the Ruins of Despair, levels " + describeBrackets() + ".");
		LOGGER.info(getClass().getSimpleName() + ": Registration opened.");
		return null;
	}

	/**
	 * @return the brackets as text: 41-45, 46-50...
	 */
	public static String describeBrackets()
	{
		final StringBuilder sb = new StringBuilder();
		for (int[] bracket : TeamVsTeamConfig.BRACKETS)
		{
			sb.append(sb.length() > 0 ? ", " : "").append(bracket[0] == bracket[1] ? String.valueOf(bracket[0]) : bracket[0] + "-" + bracket[1]);
		}
		return sb.toString();
	}

	/**
	 * @param level a level
	 * @return the index of its bracket in {@link TeamVsTeamConfig#BRACKETS}, -1 for none
	 */
	public static int getBracket(int level)
	{
		for (int i = 0; i < TeamVsTeamConfig.BRACKETS.size(); i++)
		{
			final int[] bracket = TeamVsTeamConfig.BRACKETS.get(i);
			if ((level >= bracket[0]) && (level <= bracket[1]))
			{
				return i;
			}
		}
		return -1;
	}

	public boolean isRegistrationOpen()
	{
		return _state == State.REGISTRATION;
	}

	/**
	 * @return the seconds of registration left, 0 if it is closed
	 */
	public int getRegistrationSecondsLeft()
	{
		return isRegistrationOpen() ? (int) Math.max(0, (_registrationEnd - System.currentTimeMillis()) / 1000) : 0;
	}

	public boolean isRegistered(Player player)
	{
		return _registered.containsKey(player.getObjectId());
	}

	public int getRegisteredCount()
	{
		return _registered.size();
	}

	/**
	 * @param bracket a bracket index
	 * @return how many registered players are in it now
	 */
	public int getRegisteredCount(int bracket)
	{
		int count = 0;
		for (int objectId : _registered.keySet())
		{
			final Player player = World.getInstance().getPlayer(objectId);
			if ((player != null) && (getBracket(player.getLevel()) == bracket))
			{
				count++;
			}
		}
		return count;
	}

	/**
	 * @param player a player
	 * @return why it can't play now, {@code null} if it can
	 */
	public static String getJoinRefusal(Player player)
	{
		if (getBracket(player.getLevel()) < 0)
		{
			return "Level " + player.getLevel() + " is outside every TvT bracket (" + describeBrackets() + ").";
		}
		if (player.isOnEvent() || (player.getBlockCheckerArena() > -1))
		{
			return "You are already on an event.";
		}
		if (player.isInOlympiadMode() || OlympiadManager.getInstance().isRegistered(player))
		{
			return "You are registered on the Olympiad.";
		}
		if (player.isInDuel())
		{
			return "You are in a duel.";
		}
		if (player.getInstanceId() > 0)
		{
			return "You are in an instance.";
		}
		if (player.isInSiege() || player.isInsideZone(ZoneId.SIEGE))
		{
			return "You are on a siege.";
		}
		if (player.isCursedWeaponEquipped() || (player.getKarma() > 0))
		{
			return "Players with karma can't take part.";
		}
		if (player.isJailed() || player.isInOfflineMode())
		{
			return "You can't take part now.";
		}
		if (player.isFlyingMounted())
		{
			return "You can't take part while flying.";
		}
		if (player.isFishing())
		{
			return "You can't take part while fishing.";
		}
		return null;
	}

	/**
	 * Registers a player.
	 * @param player the player
	 * @return what to tell it
	 */
	public String register(Player player)
	{
		if (!isRegistrationOpen())
		{
			return "Registration is closed.";
		}
		if (isRegistered(player))
		{
			return "You are already registered.";
		}
		if (player.isRegisteredOnEvent())
		{
			return "You are already registered on another event.";
		}

		final String refusal = getJoinRefusal(player);
		if (refusal != null)
		{
			return refusal;
		}

		if ((DualboxCheckConfig.DUALBOX_CHECK_MAX_L2EVENT_PARTICIPANTS_PER_IP > 0) && !AntiFeedManager.getInstance().tryAddPlayer(AntiFeedManager.L2EVENT_ID, player, DualboxCheckConfig.DUALBOX_CHECK_MAX_L2EVENT_PARTICIPANTS_PER_IP))
		{
			return "Too many players from your computer are registered already.";
		}

		_registered.put(player.getObjectId(), System.currentTimeMillis());
		player.setRegisteredOnEvent(true);
		final int[] bracket = TeamVsTeamConfig.BRACKETS.get(getBracket(player.getLevel()));
		return "You are registered for the levels " + bracket[0] + "-" + bracket[1] + " match.";
	}

	/**
	 * The party leader registers its whole party.
	 * @param leader the leader
	 * @return what to tell it
	 */
	public String registerParty(Player leader)
	{
		final Party party = leader.getParty();
		if ((party == null) || !party.isLeader(leader))
		{
			return "Only a party leader can register a party.";
		}
		if (!isRegistrationOpen())
		{
			return "Registration is closed.";
		}

		final StringBuilder sb = new StringBuilder();
		int registered = 0;
		for (Player member : party.getMembers())
		{
			if (isRegistered(member))
			{
				registered++;
				continue;
			}

			final String result = register(member);
			if (isRegistered(member))
			{
				registered++;
				if (member != leader)
				{
					member.sendMessage("TvT: " + leader.getName() + " registered the party. " + result);
				}
			}
			else
			{
				sb.append(member.getName()).append(": ").append(result).append(" ");
				member.sendMessage("TvT: You could not be registered: " + result);
			}
		}
		return registered + " of " + party.getMemberCount() + " members are registered." + (sb.length() > 0 ? " " + sb.toString().trim() : "");
	}

	/**
	 * @param player a player that cancels its registration
	 * @return what to tell it
	 */
	public String unregister(Player player)
	{
		if (_registered.remove(player.getObjectId()) == null)
		{
			return "You are not registered.";
		}

		player.setRegisteredOnEvent(false);
		if (DualboxCheckConfig.DUALBOX_CHECK_MAX_L2EVENT_PARTICIPANTS_PER_IP > 0)
		{
			AntiFeedManager.getInstance().removePlayer(AntiFeedManager.L2EVENT_ID, player);
		}
		return "Your registration is canceled.";
	}

	private void cancelRegistrationTasks()
	{
		for (ScheduledFuture<?> task : _registrationTasks)
		{
			task.cancel(false);
		}
		_registrationTasks.clear();
		for (Npc manager : _managers)
		{
			manager.deleteMe();
		}
		_managers.clear();
	}

	/**
	 * Registration is over: the players are sorted into brackets, teams and parties, and the matches start.
	 */
	public synchronized void closeRegistration()
	{
		if (_state != State.REGISTRATION)
		{
			return;
		}

		_state = State.INACTIVE;
		cancelRegistrationTasks();

		// Who is still there and can go.
		final List<Player> players = new ArrayList<>();
		for (int objectId : _registered.keySet())
		{
			final Player player = World.getInstance().getPlayer(objectId);
			if ((player == null) || !player.isOnline())
			{
				continue;
			}

			player.setRegisteredOnEvent(false);
			final String refusal = getJoinRefusal(player);
			if (refusal != null)
			{
				player.sendMessage("TvT: You can't take part: " + refusal);
				continue;
			}
			players.add(player);
		}
		_registered.clear();

		if (players.isEmpty())
		{
			Broadcast.toAllOnlinePlayers("TvT: Nobody registered, no match this time.");
			return;
		}

		// The groups of each bracket: a party's members of one bracket stay together; one with members in several brackets is broken up.
		final Map<Integer, List<List<Player>>> brackets = new LinkedHashMap<>();
		final Set<Player> placed = new HashSet<>();
		for (Player player : players)
		{
			if (placed.contains(player))
			{
				continue;
			}

			final List<Player> members = new ArrayList<>();
			final Party party = player.getParty();
			if (party != null)
			{
				for (Player member : party.getMembers())
				{
					if (players.contains(member))
					{
						members.add(member);
					}
				}
			}
			else
			{
				members.add(player);
			}

			final Map<Integer, List<Player>> byBracket = new LinkedHashMap<>();
			for (Player member : members)
			{
				placed.add(member);
				byBracket.computeIfAbsent(getBracket(member.getLevel()), _ -> new ArrayList<>()).add(member);
			}
			if (byBracket.size() > 1)
			{
				for (Player member : members)
				{
					member.sendMessage("TvT: Your party has members of different level brackets: it is broken up, and each of you plays in the match of their level.");
				}
			}
			for (Map.Entry<Integer, List<Player>> entry : byBracket.entrySet())
			{
				brackets.computeIfAbsent(entry.getKey(), _ -> new ArrayList<>()).add(entry.getValue());
			}
		}

		final boolean fakes = FakePlayerPvpManager.getInstance().isEnabled();
		final StringBuilder started = new StringBuilder();
		for (Map.Entry<Integer, List<List<Player>>> entry : brackets.entrySet())
		{
			final int[] bracket = TeamVsTeamConfig.BRACKETS.get(entry.getKey());
			final List<List<Player>> groups = entry.getValue();
			int count = 0;
			for (List<Player> group : groups)
			{
				count += group.size();
			}

			if ((count < TeamVsTeamConfig.MIN_PLAYERS) || (!fakes && (count < 2)))
			{
				for (List<Player> group : groups)
				{
					for (Player player : group)
					{
						player.sendMessage("TvT: Not enough players registered for the levels " + bracket[0] + "-" + bracket[1] + " match.");
					}
				}
				continue;
			}

			// A match holds two teams: more players make more matches. The biggest groups are placed first.
			groups.sort((a, b) -> Integer.compare(b.size(), a.size()));
			final List<List<List<Player>>> matches = new ArrayList<>();
			final List<Integer> sizes = new ArrayList<>();
			for (List<Player> group : groups)
			{
				int index = -1;
				for (int i = 0; i < matches.size(); i++)
				{
					if ((sizes.get(i) + group.size()) <= (TeamVsTeamConfig.TEAM_SIZE * 2))
					{
						index = i;
						break;
					}
				}
				if (index < 0)
				{
					matches.add(new ArrayList<>());
					sizes.add(0);
					index = matches.size() - 1;
				}
				matches.get(index).add(group);
				sizes.set(index, sizes.get(index) + group.size());
			}

			for (List<List<Player>> matchGroups : matches)
			{
				// Even teams: each group goes where there are fewer players so far.
				final List<List<Player>> blue = new ArrayList<>();
				final List<List<Player>> red = new ArrayList<>();
				int blueCount = 0;
				int redCount = 0;
				final List<List<Player>> unplaced = new ArrayList<>();
				for (List<Player> group : matchGroups)
				{
					final boolean toBlue = blueCount < redCount ? true : (redCount < blueCount ? false : Rnd.nextBoolean());
					if (toBlue && ((blueCount + group.size()) <= TeamVsTeamConfig.TEAM_SIZE))
					{
						blue.add(group);
						blueCount += group.size();
					}
					else if ((redCount + group.size()) <= TeamVsTeamConfig.TEAM_SIZE)
					{
						red.add(group);
						redCount += group.size();
					}
					else if ((blueCount + group.size()) <= TeamVsTeamConfig.TEAM_SIZE)
					{
						blue.add(group);
						blueCount += group.size();
					}
					else
					{
						unplaced.add(group);
					}
				}

				for (List<Player> group : unplaced)
				{
					for (Player player : group)
					{
						player.sendMessage("TvT: There was no room left for your group in the levels " + bracket[0] + "-" + bracket[1] + " match.");
					}
				}

				// Without fake players both teams need someone.
				if (!fakes && ((blueCount == 0) || (redCount == 0)))
				{
					for (List<Player> group : matchGroups)
					{
						for (Player player : group)
						{
							player.sendMessage("TvT: Not enough players for two teams in the levels " + bracket[0] + "-" + bracket[1] + " match.");
						}
					}
					continue;
				}

				final Match match = startMatch(bracket, blue, red, false);
				if (match != null)
				{
					started.append(started.length() > 0 ? ", " : "").append(match.getBracketName());
				}
			}
		}

		if (started.length() > 0)
		{
			Broadcast.toAllOnlinePlayers("TvT: The matches start in the Ruins of Despair (levels " + started + "). Good luck!");
		}
		else
		{
			Broadcast.toAllOnlinePlayers("TvT: Not enough players registered, no match this time.");
		}
	}

	// ---------------------------------------------------------------------------------------------
	// Match setup
	// ---------------------------------------------------------------------------------------------

	private Match startMatch(int[] bracket, List<List<Player>> blueGroups, List<List<Player>> redGroups, boolean simulated)
	{
		final Instance instance = InstanceManager.getInstance().createDynamicInstance(INSTANCE_TEMPLATE_ID);
		if (instance == null)
		{
			return null;
		}

		final Match match = new Match(_nextMatchId.getAndIncrement(), bracket[0], bracket[1], instance.getId());
		match._simulated = simulated;
		match._barrier.addAll(TeamVsTeamBarrier.build(instance.getId()));
		_matches.put(match.getId(), match);
		ACTIVE = true;
		keepRegionsAwake(match);

		try
		{
			// Everyone leaves their party first: the event parties are made afresh, a broken up party included.
			for (List<Player> group : blueGroups)
			{
				group.forEach(Player::leaveParty);
			}
			for (List<Player> group : redGroups)
			{
				group.forEach(Player::leaveParty);
			}

			buildSide(match, match._blue, blueGroups, instance);
			buildSide(match, match._red, redGroups, instance);
		}
		catch (Exception e)
		{
			LOGGER.log(Level.WARNING, getClass().getSimpleName() + ": Problem while setting up match " + match.getId() + ".", e);
		}

		for (Player player : match.getPlayers())
		{
			final Fighter fighter = _fighters.get(player.getObjectId());
			sendScreen(player, "You fight for team " + fighter._side._name + ". The fight starts in " + TeamVsTeamConfig.PREP_TIME + " seconds.", 8, true);
			player.sendMessage("TvT: Team " + fighter._side._name + " (levels " + match.getBracketName() + "): " + TeamVsTeamConfig.TEAM_SIZE + " vs " + TeamVsTeamConfig.TEAM_SIZE + ". The other team is attacked without Ctrl; most kills in " + TeamVsTeamConfig.FIGHT_TIME + " minutes wins.");
		}

		match._task = ThreadPool.scheduleAtFixedRate(() -> tick(match), TICK_MS, TICK_MS);
		LOGGER.info(getClass().getSimpleName() + ": Match " + match.getId() + " (levels " + match.getBracketName() + ") started: Blue " + match._blue.getPlayerCount() + " players / " + match._blue._fighters.size() + ", Red " + match._red.getPlayerCount() + " players / " + match._red._fighters.size() + ", " + match._barrier.size() + " walls of fence round the field.");
		return match;
	}

	/**
	 * Fills one side: the players' groups become its parties, topped up with the fake players they lack, then parties of fake players fill the free places.
	 */
	private void buildSide(Match match, Side side, List<List<Player>> groups, Instance instance)
	{
		final boolean fakes = FakePlayerPvpManager.getInstance().isEnabled();
		int players = 0;
		for (List<Player> group : groups)
		{
			players += group.size();
		}
		int slots = fakes ? Math.max(0, TeamVsTeamConfig.TEAM_SIZE - players) : 0;

		// Lone players get a party each while the team has room for parties of its size, the others join theirs.
		final int partyCount = Math.max(1, (TeamVsTeamConfig.TEAM_SIZE + TeamVsTeamConfig.PARTY_SIZE - 1) / TeamVsTeamConfig.PARTY_SIZE);
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

		for (List<Player> members : parties)
		{
			final Player leader = members.get(0);
			for (Player player : members)
			{
				enterPlayer(match, side, player, instance);
			}

			if (members.size() > 1)
			{
				final Party party = new Party(leader, leader.getPartyDistributionType());
				leader.setParty(party);
				for (int i = 1; i < members.size(); i++)
				{
					members.get(i).joinParty(party);
				}
			}

			// The fake players it lacks, in the roles its players don't fill.
			final int wanted = Math.min(slots, Math.max(0, TeamVsTeamConfig.PARTY_SIZE - members.size()));
			if (wanted <= 0)
			{
				continue;
			}

			final List<SimRole> roles = new ArrayList<>(SimulationComposer.getRoles(members.size() + wanted));
			for (Player player : members)
			{
				roles.remove(getRole(player));
			}
			while (roles.size() > wanted)
			{
				roles.remove(roles.size() - 1);
			}

			final List<Npc> spawned = spawnFakes(match, side, roles, instance);
			slots -= wanted;
			if (!spawned.isEmpty())
			{
				side._parties.add(FakePartyManager.getInstance().createEventParty(leader, spawned));
			}
		}

		// Parties of fake players for the rest.
		while (slots > 0)
		{
			final int size = Math.min(TeamVsTeamConfig.PARTY_SIZE, slots);
			slots -= size;
			final List<Npc> spawned = spawnFakes(match, side, SimulationComposer.getRoles(size), instance);
			if (spawned.size() > 1)
			{
				side._parties.add(FakePartyManager.getInstance().createSimulationParty(spawned, FakeBrain.A));
			}
		}
	}

	private List<Npc> spawnFakes(Match match, Side side, List<SimRole> roles, Instance instance)
	{
		final List<Npc> spawned = new ArrayList<>();
		for (SimRole role : roles)
		{
			final FakePlayerPvpBuild build = SimulationComposer.pick(role, ThreadLocalRandom.current());
			if (build == null)
			{
				continue;
			}

			final int level = Rnd.get(match._minLevel, match._maxLevel);
			final Location location = QuestSpawnUtil.findPoint(side._base, 20, Math.max(40, TeamVsTeamConfig.SPAWN_RADIUS), true, instance.getId());
			final Npc fake = FakePlayerPvpManager.getInstance().spawnTvtFighter(build, level, side._name, match.getId(), side._team, side._nameColor, location.getX(), location.getY(), location.getZ(), instance.getId());
			if (fake == null)
			{
				continue;
			}

			final Fighter fighter = new Fighter(fake, match, side, role);
			side._fighters.add(fighter);
			_fighters.put(fake.getObjectId(), fighter);
			showScore(fighter);
			spawned.add(fake);
		}
		return spawned;
	}

	/**
	 * Takes a player into a match: its team color and circle, its base, full HP.
	 */
	private void enterPlayer(Match match, Side side, Player player, Instance instance)
	{
		final Fighter fighter = new Fighter(player, match, side, getRole(player));
		fighter._returnLocation = new Location(player.getX(), player.getY(), player.getZ(), player.getHeading());
		fighter._nameColor = player.getAppearance().getNameColor();
		fighter._title = player.getTitle();
		side._fighters.add(fighter);
		_fighters.put(player.getObjectId(), fighter);

		player.setRegisteredOnEvent(false);
		player.setOnEvent(true);
		// No "To Village" when it dies: the dead come back at their base by themselves (the button took a player out of the match).
		player.setCanRevive(false);
		if (player.isDead())
		{
			player.doRevive();
		}
		if (player.isSitting())
		{
			player.standUp();
		}

		player.getAppearance().setNameColor(side._nameColor);
		player.setTeam(side._team);
		player.setTitle(scoreTitle(fighter));
		player.teleToLocation(side._base, instance.getId(), TeamVsTeamConfig.SPAWN_RADIUS);
		buffAndHeal(player);
		player.broadcastUserInfo();
		TeamEventHud.sendState(player, state(match, Ex2TeamEventState.RESULT_NONE), side._team);
		showZones(player, side._team);

		final BuffTemplateManager templates = BuffTemplateManager.getInstance();
		if (templates.isTvtTemplateEnabled() && templates.getTemplate(player, BuffTemplateManager.TVT_SLOT).skillIds().isEmpty())
		{
			player.sendMessage("TvT: Your TvT Event buff template is empty. Fill it on the Community Board (Buff Templates) to get those buffs whenever you come back at your base.");
		}
	}

	/**
	 * @param player a player
	 * @return the slot of its class in a party: archer with a bow, healer for a cleric, nuker for another mystic, buffer for a dancer or bard, tank for a knight, melee otherwise
	 */
	public static SimRole getRole(Player player)
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
		if (player.isInCategory(CategoryType.SUBJOB_GROUP_DANCE))
		{
			return SimRole.BUFFER;
		}
		if (player.isInCategory(CategoryType.TANKER_GROUP) || player.isInCategory(CategoryType.KNIGHT_GROUP))
		{
			return SimRole.TANK;
		}
		return SimRole.MELEE;
	}

	// ---------------------------------------------------------------------------------------------
	// The match
	// ---------------------------------------------------------------------------------------------

	private void tick(Match match)
	{
		try
		{
			final long now = System.currentTimeMillis();
			pruneWatchers(match);
			switch (match._phase)
			{
				case PREP:
				{
					// Nobody leaves the bases before the fight.
					for (Fighter fighter : match.getAllFighters())
					{
						if (fighter.isPlayer() && !checkPlayerStillIn(fighter))
						{
							continue;
						}

						final Creature creature = fighter._creature;
						if (!creature.isDead() && !creature.isTeleporting() && (creature.calculateDistance2D(fighter._side._base) > TeamVsTeamConfig.BASE_RADIUS))
						{
							sendHome(fighter, fighter._side._base);
							if (fighter.isPlayer())
							{
								sendScreen(creature.asPlayer(), "The fight hasn't started yet!", 4, false);
							}
						}
					}

					if (now >= match._prepEnd)
					{
						startFight(match);
					}
					break;
				}
				case FIGHT:
				{
					for (Fighter fighter : match.getAllFighters())
					{
						if (fighter.isPlayer() && !checkPlayerStillIn(fighter))
						{
							continue;
						}

						final Creature creature = fighter._creature;
						if (creature.isDead())
						{
							if ((fighter._respawnAt > 0) && (now >= fighter._respawnAt))
							{
								respawn(fighter);
							}
							continue;
						}
						fighter._respawnAt = 0; // Resurrected by a team mate.

						if (creature.isTeleporting())
						{
							continue;
						}

						// Nobody leaves the field: the fence stops everyone, and whoever got through anyway (a skill that moves them) is brought back inside.
						if (TeamVsTeamBarrier.isOutside(creature, BARRIER_MARGIN))
						{
							sendInside(fighter);
							continue;
						}

						// No camping in front of the other team's spawn: whoever walks into one of its bases is stunned there until it dies, and its team may kill it from inside the base. Players coming close are warned first, fake players turn back.
						final Side other = match.getOther(fighter._side);
						final double enemyBase = other.getBaseDistance(creature);
						if (enemyBase < TeamVsTeamConfig.BASE_RADIUS)
						{
							if (!fighter._trapped)
							{
								trap(fighter);
							}
							continue;
						}
						if (fighter.isPlayer())
						{
							if ((enemyBase < (TeamVsTeamConfig.BASE_RADIUS + TeamVsTeamConfig.BASE_WARNING_DISTANCE)) && (now >= fighter._nextBaseWarning))
							{
								fighter._nextBaseWarning = now + BASE_WARNING_INTERVAL;
								sendScreen(creature.asPlayer(), "Enemy base ahead! Step into it and you are stunned until you die.", 4, true);
							}
						}
						else if (enemyBase < (TeamVsTeamConfig.BASE_RADIUS + FAKE_TURN_BACK_DISTANCE))
						{
							turnBack(fighter, other);
						}

						// Spawn protection lasts a while (it ends sooner when it hurts someone, see onDamageDealt), so the ones coming back can join their team.
						if (fighter.isProtected() && (now >= fighter._protectedUntil))
						{
							unprotect(fighter);
						}
					}

					updateTactics(match);

					// Every player left: the fake players don't fight on alone.
					if (!match._simulated && match.getPlayers().isEmpty())
					{
						endMatch(match, true);
					}
					else if (now >= match._fightEnd)
					{
						endMatch(match, false);
					}
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
	}

	/**
	 * @return {@code false} if the player left the match (it went offline or out of the instance): it is taken out of it
	 */
	private boolean checkPlayerStillIn(Fighter fighter)
	{
		final Player player = fighter._creature.asPlayer();
		if (player.isOnline() && !player.isInOfflineMode() && ((player.getInstanceId() == fighter._match._instanceId) || player.isTeleporting()))
		{
			return true;
		}

		// One that went offline logs in again where it was before the match.
		leave(fighter, !player.isOnline());
		return false;
	}

	private void startFight(Match match)
	{
		match._phase = Phase.FIGHT;
		match._fightEnd = System.currentTimeMillis() + (TeamVsTeamConfig.FIGHT_TIME * 60000L);
		sendState(match, Ex2TeamEventState.RESULT_NONE);
		for (Player player : match.getPlayers())
		{
			sendScreen(player, "The fight has begun!", 5, true);
		}
		for (Fighter fighter : match.getAllFighters())
		{
			if (!fighter.isPlayer() && (fighter._creature instanceof Npc fake) && !fake.isDead())
			{
				fake.setRunning();
				fake.getAI().setIntention(Intention.ACTIVE);
			}
		}
	}

	/**
	 * For the fake players: where each side's members fight right now, and where the enemy groups are (the middle of each party's living members outside their base).
	 */
	private static void updateTactics(Match match)
	{
		// How many living team mates each fighter has close by: alone or in a group.
		for (Side side : new Side[]
		{
			match._blue,
			match._red
		})
		{
			for (Fighter fighter : side._fighters)
			{
				int near = 0;
				if (fighter.isAlive())
				{
					for (Fighter mate : side._fighters)
					{
						if ((mate != fighter) && mate.isAlive() && (fighter._creature.calculateDistance2D(mate._creature) <= ALONE_RANGE))
						{
							near++;
						}
					}
				}
				fighter._alliesNear = near;
			}
		}

		for (Side side : new Side[]
		{
			match._blue,
			match._red
		})
		{
			final Side other = match.getOther(side);
			final List<Location> clashes = new ArrayList<>();
			for (Fighter fighter : side._fighters)
			{
				if (!fighter.isAlive())
				{
					continue;
				}
				for (Fighter enemy : other._fighters)
				{
					if (enemy.isAlive() && !enemy.isProtected() && !isInBase(enemy._creature, enemy) && !isInBase(fighter._creature, fighter) && (fighter._creature.calculateDistance2D(enemy._creature) <= SIGHT_RANGE))
					{
						clashes.add(new Location(fighter._creature.getX(), fighter._creature.getY(), fighter._creature.getZ()));
						break;
					}
				}
			}
			side._clashes = clashes;

			// The groups of the other side: its parties, and its members on their own.
			final Map<Object, long[]> groups = new LinkedHashMap<>();
			for (Fighter enemy : other._fighters)
			{
				// Not the ones still by their base: the fake players don't go and wait at the other team's base for them to come out.
				if (!enemy.isAlive() || enemy.isProtected() || (other.getBaseDistance(enemy._creature) <= (TeamVsTeamConfig.BASE_RADIUS + BASE_APPROACH)))
				{
					continue;
				}
				final FakePlayerParty party = FakePartyManager.getInstance().getParty(enemy._creature);
				final Object key = party != null ? party : enemy;
				final long[] sum = groups.computeIfAbsent(key, _ -> new long[4]);
				sum[0] += enemy._creature.getX();
				sum[1] += enemy._creature.getY();
				sum[2] += enemy._creature.getZ();
				sum[3]++;
			}
			final List<Location> enemyGroups = new ArrayList<>();
			for (long[] sum : groups.values())
			{
				enemyGroups.add(new Location((int) (sum[0] / sum[3]), (int) (sum[1] / sum[3]), (int) (sum[2] / sum[3])));
			}
			side._enemyGroups = enemyGroups;

			// The enemies alone, out of their base, for the fake players hunting on their own.
			final List<Location> loneEnemies = new ArrayList<>();
			for (Fighter enemy : other._fighters)
			{
				if (enemy.isAlive() && !enemy.isProtected() && (enemy._alliesNear == 0) && !enemy._creature.isInvisible() && (other.getBaseDistance(enemy._creature) > (TeamVsTeamConfig.BASE_RADIUS + BASE_APPROACH)))
				{
					loneEnemies.add(enemy._creature.getLocation());
				}
			}
			side._loneEnemies = loneEnemies;
		}
	}

	private void respawn(Fighter fighter)
	{
		fighter._respawnAt = 0;
		final Creature creature = fighter._creature;
		if (!creature.isDead())
		{
			return;
		}

		final Match match = fighter._match;
		final Location base = fighter._respawnBase != null ? fighter._respawnBase : fighter._side._base;
		if (creature.isPlayer())
		{
			final Player player = creature.asPlayer();
			player.setIsPendingRevive(true);
			player.teleToLocation(base, match._instanceId, TeamVsTeamConfig.SPAWN_RADIUS);
			buffAndHeal(player);
		}
		else if (creature instanceof Npc fake)
		{
			DecayTaskManager.getInstance().cancel(fake);
			fake.doRevive();
			sendHome(fighter, base);
			FakePartyManager.getInstance().onRevived(fake);
			FakePlayerPvpManager.getInstance().prepareSimulant(fake);
			fake.setRunning();
		}
		protect(fighter);
	}

	/**
	 * Gives a player its TvT Event buff template (see {@link BuffTemplateManager#applyTvtTemplate}), then full HP, CP and MP (after the buffs, which may raise them).
	 * @param player the player, alive
	 */
	private static void buffAndHeal(Player player)
	{
		BuffTemplateManager.getInstance().applyTvtTemplate(player);
		player.setCurrentHpMp(player.getMaxHp(), player.getMaxMp());
		player.setCurrentCp(player.getMaxCp());
	}
	
	/**
	 * Teleports a fighter to one of its bases. A fake player's teleport stops its AI task (the old spot's {@code decayMe}) while its intention stays ACTIVE, so setting ACTIVE again wouldn't wake it: its AI task starts again here.
	 * @param fighter the fighter
	 * @param base the spawn point
	 */
	private static void sendHome(Fighter fighter, Location base)
	{
		final Creature creature = fighter._creature;
		creature.teleToLocation(base, fighter._match._instanceId, TeamVsTeamConfig.SPAWN_RADIUS);
		if (!creature.isPlayer() && !creature.isDead() && creature.hasAI() && (creature.getAI() instanceof AttackableAI ai))
		{
			ai.startAITask();
		}
	}
	
	/**
	 * Brings someone that got beyond the fence back inside it (on the way to the middle of the field).
	 */
	private static void sendInside(Fighter fighter)
	{
		final Creature creature = fighter._creature;
		if (fighter._match._simulated)
		{
			LOGGER.info(TeamVsTeamManager.class.getSimpleName() + ": Match " + fighter._match.getId() + ": " + describe(fighter) + " got beyond the fence at " + creature.getX() + "," + creature.getY() + "," + creature.getZ() + " and is brought back.");
		}
		creature.abortAttack();
		creature.abortCast();
		creature.teleToLocation(TeamVsTeamBarrier.getInside(creature), fighter._match._instanceId, 0);
		if (creature.isPlayer())
		{
			sendScreen(creature.asPlayer(), "You can't leave the battlefield!", 4, false);
		}
		else if (!creature.isDead() && creature.hasAI() && (creature.getAI() instanceof AttackableAI ai))
		{
			ai.startAITask();
		}
	}

	/**
	 * Someone walked into an enemy base: it is stunned there until it dies (it can't move, attack nor use a skill, and nothing cleanses it), and the base's team may kill it from inside its base.
	 */
	private void trap(Fighter fighter)
	{
		final Creature creature = fighter._creature;
		fighter._trapped = true;
		creature.abortAttack();
		creature.abortCast();
		creature.stopMove(null);
		creature.setParalyzed(true);
		creature.startAbnormalVisualEffect(true, AbnormalVisualEffect.STUN);
		if (creature.isPlayer())
		{
			sendScreen(creature.asPlayer(), "You entered the enemy base: you are stunned until you die!", 6, true);
			creature.sendMessage("TvT: You entered the enemy base and are stunned until you die.");
		}
		if (fighter._match._simulated)
		{
			LOGGER.info(getClass().getSimpleName() + ": Match " + fighter._match.getId() + ": " + describe(fighter) + " walked into the enemy base and is stunned there.");
		}
	}

	/**
	 * Ends the stun of an enemy base (at its death, or when it leaves the match).
	 */
	private static void untrap(Fighter fighter)
	{
		if (!fighter._trapped)
		{
			return;
		}

		fighter._trapped = false;
		final Creature creature = fighter._creature;
		creature.setParalyzed(false);
		creature.stopAbnormalVisualEffect(true, AbnormalVisualEffect.STUN);
	}

	/**
	 * A fake player about to walk into an enemy base turns back toward the middle of the field (it sees the warning like a player does).
	 */
	private static void turnBack(Fighter fighter, Side enemySide)
	{
		final Creature creature = fighter._creature;
		if (creature.isMovementDisabled() || !creature.hasAI())
		{
			return;
		}

		final Location base = enemySide.getClosestBase(creature);
		final double length = Math.max(1, creature.calculateDistance2D(base));
		final int x = creature.getX() + (int) (((creature.getX() - base.getX()) / length) * TURN_BACK_STEP);
		final int y = creature.getY() + (int) (((creature.getY() - base.getY()) / length) * TURN_BACK_STEP);
		final Location away = GeoEngine.getInstance().getValidLocation(creature.getX(), creature.getY(), creature.getZ(), x, y, GeoEngine.getInstance().getHeight(x, y, creature.getZ()), creature.getInstanceId());
		creature.abortAttack();
		creature.setTarget(null);
		if (creature.isAttackable())
		{
			creature.asAttackable().clearAggroList();
		}
		creature.setRunning();
		creature.getAI().setIntention(Intention.MOVE_TO, away);
	}

	/**
	 * @param creature a creature
	 * @return {@code true} if it is stunned in an enemy base of its match until it dies
	 */
	public static boolean isTrapped(Creature creature)
	{
		final Fighter fighter = getFighter(creature);
		return (fighter != null) && fighter._trapped;
	}

	private static void protect(Fighter fighter)
	{
		if (TeamVsTeamConfig.SPAWN_PROTECTION <= 0)
		{
			return;
		}

		final Creature creature = fighter._creature;
		fighter._protectedUntil = System.currentTimeMillis() + (TeamVsTeamConfig.SPAWN_PROTECTION * 1000L);
		creature.setInvul(true);
		final Skill skill = GHOST_WALKING.getSkill();
		if (skill != null)
		{
			skill.applyEffects(creature, creature);
		}
	}

	private static void unprotect(Fighter fighter)
	{
		if (!fighter.isProtected())
		{
			return;
		}

		fighter._protectedUntil = 0;
		final Creature creature = fighter._creature;
		creature.setInvul(false);
		final Skill skill = GHOST_WALKING.getSkill();
		if ((skill != null) && creature.isAffectedBySkill(skill.getId()))
		{
			creature.getEffectList().stopSkillEffects(SkillFinishType.REMOVED, skill);
		}
	}

	/**
	 * Called by {@code Creature#doDie} while a match goes on.
	 * @param victim who died
	 * @param killer who killed it
	 */
	public void onDeath(Creature victim, Creature killer)
	{
		final Fighter dead = _fighters.get(victim.getObjectId());
		if ((dead == null) || (dead._match._phase != Phase.FIGHT))
		{
			return;
		}

		final Match match = dead._match;
		final boolean deadAlone = dead._alliesNear == 0;
		dead._deaths++;
		showScore(dead);
		dead._loneUntil = 0;
		dead._ambushSpot = null;
		untrap(dead);
		dead._side.joinWave(dead, System.currentTimeMillis());

		final Fighter slayer = getFighter(killer);
		if ((slayer != null) && (slayer._match == match) && (slayer._side != dead._side))
		{
			slayer._kills++;
			slayer._side._score.incrementAndGet();
			showScore(slayer);
			if (slayer.isPlayer())
			{
				sendScreen(slayer._creature.asPlayer(), "You killed " + victim.getName() + "! Your score: " + slayer._kills + " kills, " + slayer._deaths + " deaths.", 3, false);
			}
			if (slayer.isPlayer())
			{
				rewardKill(slayer._creature.asPlayer(), victim);
			}
			sendState(match, Ex2TeamEventState.RESULT_NONE);
		}
		
		if (match._simulated)
		{
			// How the kill came about: its killer on its own, out of an ambush, its victim alone.
			final String how = (slayer == null ? "" : (slayer.isLone() ? " [lone]" : "") + ((System.currentTimeMillis() - slayer._ambushSprungAt) < AMBUSH_KILL_TIME ? " [ambush]" : "")) + (deadAlone ? " [victim alone]" : "");
			LOGGER.info(getClass().getSimpleName() + ": Match " + match.getId() + " " + (match.getSecondsLeft() / 60) + ":" + String.format("%02d", match.getSecondsLeft() % 60) + " left: " + (slayer != null ? describe(slayer) : (killer != null ? killer.getName() : "?")) + " killed " + describe(dead) + how + ". Blue " + match._blue.getScore() + " : " + match._red.getScore() + " Red.");
		}

		if (dead.isPlayer())
		{
			dead._creature.sendMessage("TvT: You come back at your base in " + TeamVsTeamConfig.RESPAWN_DELAY + " seconds. Your score: " + dead._kills + " kills, " + dead._deaths + " deaths.");
		}
	}

	/**
	 * @param fighter a fighter
	 * @return its score as a title: kills and deaths ("K:3 D:1")
	 */
	private static String scoreTitle(Fighter fighter)
	{
		return "K:" + fighter._kills + " D:" + fighter._deaths;
	}

	/**
	 * Shows a fighter's score as its title, to itself and to everyone around (a player's own title comes back when it leaves the match).
	 * @param fighter a fighter
	 */
	private static void showScore(Fighter fighter)
	{
		final Creature creature = fighter._creature;
		final String title = scoreTitle(fighter);
		if (creature.isPlayer())
		{
			creature.setTitle(title);
			creature.asPlayer().broadcastTitleInfo();
			return;
		}

		final FakePlayerHolder holder = creature.asNpc().getTemplate().getFakePlayerInfo();
		if (holder != null)
		{
			holder.setTitle(title);
		}
		creature.setTitle(title);
		creature.broadcastPacket(new NicknameChanged(creature));
	}

	/**
	 * @param player a player
	 * @return the title to save for it: its own, not the score it shows in a match
	 */
	public static String getOwnTitle(Player player)
	{
		final Fighter fighter = getFighter(player);
		final String title = fighter != null ? fighter._title : null;
		return title != null ? title : player.getTitle();
	}

	private static void rewardKill(Player killer, Creature victim)
	{
		final List<ItemChanceHolder> rewards = TeamVsTeamConfig.KILL_REWARDS;
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
	 * The fight is over: the result, the rewards, and everyone goes home a little later.
	 * @param match the match
	 * @param abandoned {@code true} if it ends without a result (every player left, or an admin stopped it)
	 */
	private void endMatch(Match match, boolean abandoned)
	{
		if (match._phase == Phase.ENDED)
		{
			return;
		}
		match._phase = Phase.ENDED;

		for (Fighter fighter : match.getAllFighters())
		{
			final Creature creature = fighter._creature;
			creature.abortAttack();
			creature.abortCast();
			creature.setInvul(true);
			if (!fighter.isPlayer() && creature.hasAI())
			{
				creature.getAI().setIntention(Intention.IDLE);
			}
		}

		final int blue = match._blue.getScore();
		final int red = match._red.getScore();
		final Side winner = abandoned ? null : (blue > red ? match._blue : (red > blue ? match._red : null));
		final String result = abandoned ? "The match is over." : (winner == null ? "The match ended in a tie, " + blue + " : " + red + "." : "Team " + winner._name + " won the match, " + Math.max(blue, red) + " : " + Math.min(blue, red) + "!");

		// The best killers of the match.
		final List<Fighter> ranking = match.getAllFighters();
		ranking.sort((a, b) -> Integer.compare(b._kills, a._kills));
		final StringBuilder best = new StringBuilder();
		for (int i = 0; (i < 3) && (i < ranking.size()) && (ranking.get(i)._kills > 0); i++)
		{
			best.append(best.length() > 0 ? ", " : "").append(ranking.get(i)._creature.getName()).append(" (").append(ranking.get(i)._kills).append(')');
		}

		final Skill firework = CommonSkill.FIREWORK.getSkill();
		sendState(match, abandoned ? Ex2TeamEventState.RESULT_NO_RESULT : winner == match._blue ? Ex2TeamEventState.RESULT_BLUE : winner == match._red ? Ex2TeamEventState.RESULT_RED : Ex2TeamEventState.RESULT_DRAW);
		for (Player player : match.getPlayers())
		{
			final Fighter fighter = _fighters.get(player.getObjectId());
			sendScreen(player, result, 7, true);
			player.sendMessage("TvT: " + result + (best.length() > 0 ? " Best killers: " + best + "." : "") + ((fighter != null) ? " You: " + fighter._kills + " kills, " + fighter._deaths + " deaths." : ""));
			if ((winner != null) && (fighter != null) && (fighter._side == winner))
			{
				if (firework != null)
				{
					player.broadcastPacket(new MagicSkillUse(player, player, firework.getId(), firework.getLevel(), firework.getHitTime(), firework.getReuseDelay()));
				}
				player.broadcastSocialAction(3);
				for (ItemHolder reward : TeamVsTeamConfig.WIN_REWARD)
				{
					player.addItem(ItemProcessType.REWARD, reward.getId(), reward.getCount(), null, true);
				}
			}
		}

		LOGGER.info(getClass().getSimpleName() + ": Match " + match.getId() + " (levels " + match.getBracketName() + ") ended: " + result);
		ThreadPool.schedule(() -> cleanup(match), abandoned ? 2000 : END_DELAY);
	}

	/**
	 * Everyone goes home: the players back where they were, the fake players out of the world, the instance away.
	 */
	private void cleanup(Match match)
	{
		try
		{
			if (match._task != null)
			{
				match._task.cancel(false);
				match._task = null;
			}

			for (Side side : new Side[]
			{
				match._blue,
				match._red
			})
			{
				for (FakePlayerParty party : side._parties)
				{
					if (party.isFakeOnly())
					{
						FakePartyManager.getInstance().disbandSimulationParty(party);
					}
					else
					{
						FakePartyManager.getInstance().disbandEventParty(party);
					}
				}
				side._parties.clear();
			}

			for (Fighter fighter : match.getAllFighters())
			{
				if (fighter.isPlayer())
				{
					restorePlayer(fighter, true);
				}
				else
				{
					untrap(fighter);
					removeFake((Npc) fighter._creature);
				}
				_fighters.remove(fighter._creature.getObjectId(), fighter);
			}
		}
		catch (Exception e)
		{
			LOGGER.log(Level.WARNING, getClass().getSimpleName() + ": Problem while cleaning up match " + match.getId() + ".", e);
		}
		finally
		{
			TeamVsTeamBarrier.remove(match._barrier);
			for (int objectId : match._watchers)
			{
				final Player gm = World.getInstance().getPlayer(objectId);
				if (gm != null)
				{
					TeamEventHud.close(gm, zoneIds());
				}
			}
			match._watchers.clear();
			releaseRegions(match);
			InstanceManager.getInstance().destroyInstance(match._instanceId);
			_matches.remove(match.getId());
			ACTIVE = !_matches.isEmpty();
		}
	}

	private static void removeFake(Npc fake)
	{
		try
		{
			DecayTaskManager.getInstance().cancel(fake);
			if (!fake.isDead())
			{
				fake.abortAttack();
				fake.abortCast();
				fake.stopTransformation(true);
				FakePlayerPvpManager.getInstance().untransform(fake);
				FakePlayerPvpManager.getInstance().unsummonServitor(fake);
			}
			if (!fake.isDecayed())
			{
				fake.deleteMe();
			}
		}
		catch (Exception e)
		{
			LOGGER.log(Level.WARNING, TeamVsTeamManager.class.getSimpleName() + ": Could not remove " + fake.getName() + ".", e);
		}
	}

	/**
	 * Gives a player back its state from before the match.
	 * @param fighter the player's fighter
	 * @param teleport {@code true} to send it back where it was
	 */
	private void restorePlayer(Fighter fighter, boolean teleport)
	{
		final Player player = fighter._creature.asPlayer();
		unprotect(fighter);
		untrap(fighter);
		player.setInvul(false);
		player.setTeam(Team.NONE);
		player.getAppearance().setNameColor(fighter._nameColor);
		if (fighter._title != null)
		{
			player.setTitle(fighter._title);
			fighter._title = null;
		}
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
		TeamEventHud.close(player, zoneIds());

		final Instance instance = InstanceManager.getInstance().getInstance(fighter._match._instanceId);
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
	 * A player leaves a match before its end (logged off, left the instance): it gets its state back, and a fake player of its role takes its place.
	 * @param fighter the player's fighter
	 * @param loggingOff {@code true} if it logs off: it logs in again where it was before the match
	 */
	private void leave(Fighter fighter, boolean loggingOff)
	{
		if (_fighters.remove(fighter._creature.getObjectId(), fighter))
		{
			fighter._side._fighters.remove(fighter);
		}
		else
		{
			return;
		}

		final Player player = fighter._creature.asPlayer();
		
		// The fake players of its party stay in the match, as a party of their own.
		for (FakePlayerParty party : fighter._side._parties)
		{
			if (party.getHost() == player)
			{
				final List<Npc> fakes = new ArrayList<>(party.getFakes());
				FakePartyManager.getInstance().disbandEventParty(party);
				fighter._side._parties.remove(party);
				if (fakes.size() > 1)
				{
					fighter._side._parties.add(FakePartyManager.getInstance().createSimulationParty(fakes, FakeBrain.A));
				}
				break;
			}
		}
		
		restorePlayer(fighter, false);
		if (loggingOff && (fighter._returnLocation != null))
		{
			player.setInstanceId(0);
			player.setXYZInvisible(fighter._returnLocation.getX(), fighter._returnLocation.getY(), fighter._returnLocation.getZ());
		}

		final Match match = fighter._match;
		if ((match._phase != Phase.ENDED) && FakePlayerPvpManager.getInstance().isEnabled())
		{
			final Instance instance = InstanceManager.getInstance().getInstance(match._instanceId);
			if (instance != null)
			{
				final List<Npc> spawned = spawnFakes(match, fighter._side, List.of(fighter._role), instance);
				for (Npc fake : spawned)
				{
					if (match._phase == Phase.FIGHT)
					{
						protect(_fighters.get(fake.getObjectId()));
					}
				}
			}
		}

		for (Player other : match.getPlayers())
		{
			other.sendMessage("TvT: " + player.getName() + " left the match.");
		}
	}

	/**
	 * Called by {@code Player#deleteMe}: a registered player is taken off the list, one in a match leaves it and logs in again where it was before.
	 * @param player the player logging off
	 */
	public void onLogout(Player player)
	{
		if (_registered.remove(player.getObjectId()) != null)
		{
			player.setRegisteredOnEvent(false);
		}

		final Fighter fighter = _fighters.get(player.getObjectId());
		if ((fighter != null) && fighter.isPlayer())
		{
			leave(fighter, true);
		}
	}

	/**
	 * Stops everything: registration and the matches going on, without rewards (an admin's //event stop).
	 */
	public synchronized void stop()
	{
		if (_state == State.REGISTRATION)
		{
			_state = State.INACTIVE;
			cancelRegistrationTasks();
			for (int objectId : _registered.keySet())
			{
				final Player player = World.getInstance().getPlayer(objectId);
				if (player != null)
				{
					player.setRegisteredOnEvent(false);
				}
			}
			_registered.clear();
			Broadcast.toAllOnlinePlayers("TvT: The event was canceled.");
		}

		for (Match match : _matches.values())
		{
			endMatch(match, true);
		}
	}

	// ---------------------------------------------------------------------------------------------
	// Event strip and radar (client/TeamEvents/PROTOCOL.txt)
	// ---------------------------------------------------------------------------------------------

	/**
	 * @param match a match
	 * @param result {@link Ex2TeamEventState#RESULT_NONE} until it ended, then who won
	 * @return its state for the event strip
	 */
	private static Ex2TeamEventState.State state(Match match, int result)
	{
		final int phase = switch (match._phase)
		{
			case PREP -> Ex2TeamEventState.PHASE_PREP;
			case FIGHT -> Ex2TeamEventState.PHASE_FIGHT;
			case ENDED -> Ex2TeamEventState.PHASE_ENDED;
		};
		final int secondsLeft = match._phase == Phase.ENDED ? 0 : match.getSecondsLeft();
		return new Ex2TeamEventState.State(Ex2TeamEventState.MODE_TVT, phase, result, secondsLeft, match._blue.getScore(), match._red.getScore(), 0, 0, 0, List.of());
	}

	/**
	 * Sends the match's state to each of its players (the event strip of their combat log: time left and score).
	 */
	private void sendState(Match match, int result)
	{
		final Ex2TeamEventState.State state = state(match, result);
		for (Player player : match.getPlayers())
		{
			final Fighter fighter = _fighters.get(player.getObjectId());
			TeamEventHud.sendState(player, state, fighter != null ? fighter._side._team : Team.NONE);
		}
		for (Player gm : watchers(match))
		{
			if (!_fighters.containsKey(gm.getObjectId()))
			{
				TeamEventHud.sendState(gm, state, Team.NONE);
			}
		}
	}

	/**
	 * @return the ids of every zone a match shows on a player's radar: both teams' bases and the edge
	 */
	private static List<Integer> zoneIds()
	{
		return TeamEventHud.zoneIds(0, TeamVsTeamConfig.BLUE_BASES.size(), TeamVsTeamConfig.RED_BASES.size());
	}

	/**
	 * Takes a GM into a match's instance, between the bases, to watch it: the GM gets its event strip and radar zones as a player of neither team sees them, until a teleport out of the instance or the match's end.
	 * @param gm the GM
	 * @param match the match
	 */
	public void watch(Player gm, Match match)
	{
		// From another match: its strip and zones go first.
		for (Match other : _matches.values())
		{
			if ((other != match) && other._watchers.remove(gm.getObjectId()))
			{
				TeamEventHud.close(gm, zoneIds());
			}
		}
		final Location center = getFieldCenter();
		gm.teleToLocation(center.getX(), center.getY(), center.getZ(), 0, match.getInstanceId(), 0);
		match._watchers.add(gm.getObjectId());
		TeamEventHud.sendState(gm, state(match, Ex2TeamEventState.RESULT_NONE), Team.NONE);
		showZones(gm, Team.NONE);
	}

	/**
	 * @return the GMs watching the match who are still in its instance
	 */
	private static List<Player> watchers(Match match)
	{
		final List<Player> watchers = new ArrayList<>();
		for (int objectId : match._watchers)
		{
			final Player gm = World.getInstance().getPlayer(objectId);
			if ((gm != null) && (gm.getInstanceId() == match._instanceId))
			{
				watchers.add(gm);
			}
		}
		return watchers;
	}

	/**
	 * Lets go of the GMs who stopped watching the match (a teleport out of its instance, a logout): their strip and the match's zones go.
	 */
	private static void pruneWatchers(Match match)
	{
		for (int objectId : match._watchers)
		{
			final Player gm = World.getInstance().getPlayer(objectId);
			if ((gm == null) || (gm.getInstanceId() != match._instanceId))
			{
				match._watchers.remove(objectId);
				if (gm != null)
				{
					TeamEventHud.close(gm, zoneIds());
				}
			}
		}
	}

	/**
	 * Shows a player entering a match the bases and the battlefield on its radar: its own team's bases in its color, the other team's in red, striped and pulsing. A GM watching ({@link Team#NONE}) sees each team's bases in its color.
	 */
	private static void showZones(Player player, Team viewer)
	{
		for (Team team : List.of(Team.BLUE, Team.RED))
		{
			final List<Location> bases = team == Team.BLUE ? TeamVsTeamConfig.BLUE_BASES : TeamVsTeamConfig.RED_BASES;
			final boolean own = (viewer == Team.NONE) || (team == viewer);
			for (int i = 0; i < bases.size(); i++)
			{
				final Location base = bases.get(i);
				TeamEventHud.showCircle(player, TeamEventHud.zoneId(team == Team.BLUE ? TeamEventHud.KIND_BLUE_BASE : TeamEventHud.KIND_RED_BASE, i), base.getX(), base.getY(), base.getZ(), TeamEventHud.SPAWN_HEIGHT, TeamVsTeamConfig.BASE_RADIUS, own ? TeamEventHud.spawnStyle(team) : TeamEventHud.ENEMY_BASE, (team == Team.BLUE ? "Blue" : "Red") + " base " + (i + 1));
			}
		}

		final List<Location> all = new ArrayList<>(TeamVsTeamConfig.BLUE_BASES);
		all.addAll(TeamVsTeamConfig.RED_BASES);
		long z = 0;
		for (Location base : all)
		{
			z += base.getZ();
		}
		// The fence round the field when there is one (an ellipse), else the drawn area.
		final int[] barrier = TeamVsTeamConfig.BARRIER;
		if (barrier != null)
		{
			TeamEventHud.showEllipseEdge(player, barrier[0], barrier[1], barrier[2], barrier[3], (int) (z / all.size()));
			return;
		}
		final int[] area = getFieldArea();
		TeamEventHud.showEdge(player, area[0], area[1], area[2], area[3], (int) (z / all.size()));
	}

	/**
	 * @return the battlefield's edge on the radar, minX, minY, maxX, maxY: {@link TeamVsTeamConfig#AREA}, or the box around every spawn point 2000 wider (as {@link #keepRegionsAwake} keeps awake)
	 */
	public static int[] getFieldArea()
	{
		if (TeamVsTeamConfig.AREA != null)
		{
			return TeamVsTeamConfig.AREA;
		}

		final List<Location> bases = new ArrayList<>(TeamVsTeamConfig.BLUE_BASES);
		bases.addAll(TeamVsTeamConfig.RED_BASES);
		int minX = Integer.MAX_VALUE;
		int maxX = Integer.MIN_VALUE;
		int minY = Integer.MAX_VALUE;
		int maxY = Integer.MIN_VALUE;
		for (Location base : bases)
		{
			minX = Math.min(minX, base.getX());
			maxX = Math.max(maxX, base.getX());
			minY = Math.min(minY, base.getY());
			maxY = Math.max(maxY, base.getY());
		}
		final int margin = 2000;
		return new int[]
		{
			minX - margin,
			minY - margin,
			maxX + margin,
			maxY + margin
		};
	}

	// ---------------------------------------------------------------------------------------------
	// Regions
	// ---------------------------------------------------------------------------------------------

	/**
	 * Keeps the regions around the spawn points awake for the match: the fake players' AI only runs in active regions.
	 */
	private static void keepRegionsAwake(Match match)
	{
		final List<Location> bases = new ArrayList<>(TeamVsTeamConfig.BLUE_BASES);
		bases.addAll(TeamVsTeamConfig.RED_BASES);
		int minX = Integer.MAX_VALUE;
		int maxX = Integer.MIN_VALUE;
		int minY = Integer.MAX_VALUE;
		int maxY = Integer.MIN_VALUE;
		for (Location base : bases)
		{
			minX = Math.min(minX, base.getX());
			maxX = Math.max(maxX, base.getX());
			minY = Math.min(minY, base.getY());
			maxY = Math.max(maxY, base.getY());
		}
		final int z = bases.get(0).getZ();
		final int margin = 2000;
		final int step = 1 << World.SHIFT_BY;
		synchronized (match._keptRegions)
		{
			for (int x = minX - margin; x <= (maxX + margin); x += step)
			{
				for (int y = minY - margin; y <= (maxY + margin); y += step)
				{
					final WorldRegion region = World.getInstance().getRegion(x, y, z);
					if ((region != null) && !region.isKeptActive())
					{
						region.keepActive();
						match._keptRegions.add(region);
					}
				}
			}
		}
	}

	private void releaseRegions(Match match)
	{
		// Another match going on still needs them (they all play in the same place): it takes them over.
		Match heir = null;
		for (Match other : _matches.values())
		{
			if ((other != match) && (other._phase != Phase.ENDED))
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
	public static Fighter getFighter(Creature creature)
	{
		if (!ACTIVE || (creature == null))
		{
			return null;
		}

		final TeamVsTeamManager manager = getInstance();
		final Fighter fighter = manager._fighters.get(creature.getObjectId());
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
	 * @return how they stand to each other in the event
	 */
	public static Relation getRelation(Creature a, Creature b)
	{
		if (!ACTIVE)
		{
			return Relation.NONE;
		}

		final Fighter fa = getFighter(a);
		final Fighter fb = getFighter(b);
		if ((fa == null) && (fb == null))
		{
			return Relation.NONE;
		}
		if ((fa == null) || (fb == null) || (fa._match != fb._match))
		{
			return Relation.STRANGER;
		}
		return fa._side == fb._side ? Relation.ALLY : Relation.ENEMY;
	}

	/**
	 * @param attacker a creature
	 * @param target another creature
	 * @return {@code true} if one of them is in a match and the other isn't an enemy of the fight going on: the attack is not allowed (the own team even with Ctrl, anyone before or after the fight, and anyone standing in their own base)
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
	 * @return {@code true} if they are enemies of a match whose fight goes on, neither of them in their own base: attacked without Ctrl
	 */
	public static boolean isEnemy(Creature attacker, Creature target)
	{
		return ACTIVE && isFightOn(attacker, target, getRelation(attacker, target));
	}

	/**
	 * The bases are safe: nobody standing in their own base hurts anyone or can be hurt, so nobody camps at a base's edge to kill the ones coming back (and no one shoots out of it). One stunned in an enemy base (see {@link #trap}) may be hurt from inside it.
	 */
	private static boolean isFightOn(Creature attacker, Creature target, Relation relation)
	{
		if (relation != Relation.ENEMY)
		{
			return false;
		}
		final Fighter fa = getFighter(attacker);
		final Fighter fb = getFighter(target);
		return (fa != null) && (fb != null) && (fa._match._phase == Phase.FIGHT) && !isInBase(target, fb) && (fb._trapped || !isInBase(attacker, fa));
	}

	/**
	 * @param creature a fighter (or its summon)
	 * @param fighter its fighter
	 * @return {@code true} if it stands in its own team's base
	 */
	private static boolean isInBase(Creature creature, Fighter fighter)
	{
		return fighter._side.getBaseDistance(creature) <= TeamVsTeamConfig.BASE_RADIUS;
	}

	/**
	 * Called by the combat log when a creature hurt another: a fighter under spawn protection loses it.
	 * @param attacker who dealt the damage (a summon counts as its owner)
	 */
	public void onDamageDealt(Creature attacker)
	{
		final Fighter fighter = getFighter(attacker);
		if ((fighter != null) && fighter.isProtected())
		{
			unprotect(fighter);
		}
	}
	
	/**
	 * @param creature a creature
	 * @return {@code true} if it is in a match and stands in its own team's base
	 */
	public static boolean isAtOwnBase(Creature creature)
	{
		final Fighter fighter = getFighter(creature);
		return (fighter != null) && (fighter._side.getBaseDistance(creature) <= TeamVsTeamConfig.BASE_RADIUS);
	}
	
	/**
	 * @param fake a fake player of a match
	 * @return the phase of its match, {@code null} if it is in none
	 */
	public Phase getPhase(Npc fake)
	{
		final FakePlayerPvpProfile profile = fake.getTemplate().getFakePlayerPvpProfile();
		final Match match = profile != null ? _matches.get(profile.getTvtMatchId()) : null;
		return match != null ? match._phase : null;
	}

	/**
	 * @param fake a fake player of a match about to say something
	 * @return {@code true} if it may: the fake players of a match talk like the players of an event, one line now and then, not each of them every few seconds
	 */
	public boolean mayChat(Npc fake)
	{
		final FakePlayerPvpProfile profile = fake.getTemplate().getFakePlayerPvpProfile();
		final Match match = profile != null ? _matches.get(profile.getTvtMatchId()) : null;
		if (match == null)
		{
			return false;
		}

		final long now = System.currentTimeMillis();
		synchronized (match)
		{
			if (now < match._nextChat)
			{
				return false;
			}
			match._nextChat = now + Rnd.get(CHAT_MIN_GAP, CHAT_MAX_GAP);
			return true;
		}
	}

	/**
	 * The enemy a fake player engages: one it sees within {@link #SIGHT_RANGE} (less far the less it knows the game, see {@link FakePlayerPvpPersonality#getKnowledge}) or that is very close, not under spawn protection. One waiting in ambush at its corner it
	 * only notices close by. It takes the closest; the better it knows the game, the more it goes for an enemy alone or low on HP instead, and (alone itself) the less for one in a group.
	 * @param fake a fake player of a match
	 * @return the enemy, {@code null} if none
	 */
	public Creature findEnemyInSight(Npc fake)
	{
		final Fighter fighter = _fighters.get(fake.getObjectId());
		if ((fighter == null) || (fighter._match._phase != Phase.FIGHT))
		{
			return null;
		}

		final double knowledge = FakePlayerPvpPersonality.of(fake).getKnowledge();
		final double sight = SIGHT_RANGE * (1 - (SIGHT_KNOWLEDGE_LOSS * (1 - knowledge)));
		final double ambushNotice = AMBUSH_NOTICE_MIN + (AMBUSH_NOTICE_KNOWLEDGE * knowledge);
		final boolean alone = fighter._alliesNear == 0;
		Creature best = null;
		double bestScore = Double.MAX_VALUE;
		for (Fighter enemy : fighter._match.getOther(fighter._side)._fighters)
		{
			final Creature creature = enemy._creature;
			if (!enemy.isAlive() || enemy.isProtected() || creature.isInvisible() || (creature.getInstanceId() != fake.getInstanceId()) || isInBase(creature, enemy))
			{
				continue;
			}

			final double distance = fake.calculateDistance2D(creature);
			if ((distance > sight) || ((distance > CLOSE_ENEMY_RANGE) && ((enemy.isInAmbush() && (distance > ambushNotice)) || !GeoEngine.getInstance().canSeeTarget(fake, creature))))
			{
				continue;
			}

			double score = distance / SIGHT_RANGE;
			score -= knowledge * (((enemy._alliesNear == 0) ? LONE_TARGET_BONUS : 0) + (LOW_TARGET_BONUS * (1 - (creature.getCurrentHp() / Math.max(1, creature.getMaxHp())))));
			if (alone && (enemy._alliesNear >= 2))
			{
				score += knowledge * GROUP_TARGET_PENALTY;
			}
			if (score < bestScore)
			{
				best = creature;
				bestScore = score;
			}
		}
		return best;
	}

	/**
	 * @param creature a fighter of a match
	 * @return how many living team mates stand close to it (worked out every second), 0 if it is in no match
	 */
	public static int getAlliesNear(Creature creature)
	{
		final Fighter fighter = getFighter(creature);
		return fighter != null ? fighter._alliesNear : 0;
	}

	/**
	 * @param creature a fighter of a match
	 * @return how many times it died in its match, 0 if it is in none
	 */
	public static int getDeaths(Creature creature)
	{
		final Fighter fighter = getFighter(creature);
		return fighter != null ? fighter._deaths : 0;
	}

	/**
	 * A fake player goes off on its own: it leaves its party for a while to hunt enemies alone, roam the field or wait in ambush. Only a damage dealer (melee, archer, nuker: a tank, healer or buffer alone kills nobody), and only so many of a team's fake players
	 * at once (see {@link #LONE_SHARE_PERCENT}), so the team still fights as one.
	 * @param fake a fake player of a match, alive
	 * @param until until when ({@link Long#MAX_VALUE} for the rest of this life)
	 * @return {@code true} if it may
	 */
	public boolean goLone(Npc fake, long until)
	{
		final Fighter fighter = _fighters.get(fake.getObjectId());
		if ((fighter == null) || (fighter._match._phase != Phase.FIGHT) || ((fighter._role != SimRole.MELEE) && (fighter._role != SimRole.ARCHER) && (fighter._role != SimRole.NUKER)))
		{
			return false;
		}

		final Side side = fighter._side;
		synchronized (side)
		{
			int fakes = 0;
			int lone = 0;
			for (Fighter mate : side._fighters)
			{
				if (!mate.isPlayer())
				{
					fakes++;
					if (mate.isAlive() && mate.isLone())
					{
						lone++;
					}
				}
			}
			if (lone >= Math.max(LONE_MIN, (fakes * LONE_SHARE_PERCENT) / 100))
			{
				return false;
			}
			fighter._loneUntil = until;
		}
		return true;
	}

	/**
	 * @param fake a fake player of a match
	 * @return {@code true} if it is on its own for now (see {@link #goLone})
	 */
	public boolean isLone(Npc fake)
	{
		final Fighter fighter = _fighters.get(fake.getObjectId());
		return (fighter != null) && fighter.isLone();
	}

	/**
	 * A fake player on its own goes back to its party.
	 * @param fake a fake player of a match
	 */
	public void endLone(Npc fake)
	{
		final Fighter fighter = _fighters.get(fake.getObjectId());
		if (fighter != null)
		{
			fighter._loneUntil = 0;
			fighter._ambushSpot = null;
		}
	}

	/**
	 * @param fake a fake player of a match
	 * @return the corner it waits at in ambush, {@code null} for none
	 */
	public Location getAmbushSpot(Npc fake)
	{
		final Fighter fighter = _fighters.get(fake.getObjectId());
		return fighter != null ? fighter._ambushSpot : null;
	}

	/**
	 * @param fake a fake player of a match
	 * @param spot the corner it waits at in ambush (see {@link #findAmbushSpot}), {@code null} when it leaves it
	 */
	public void setAmbushSpot(Npc fake, Location spot)
	{
		final Fighter fighter = _fighters.get(fake.getObjectId());
		if (fighter != null)
		{
			fighter._ambushSpot = spot;
			if ((spot != null) && fighter._match._simulated)
			{
				LOGGER.info(getClass().getSimpleName() + ": Match " + fighter._match.getId() + ": " + describe(fighter) + " sets up an ambush at " + spot.getX() + "," + spot.getY() + "," + spot.getZ() + ", " + (int) fake.calculateDistance2D(spot) + " away.");
			}
		}
	}

	/**
	 * A fake player leaves its ambush to jump an enemy.
	 * @param fake a fake player of a match
	 */
	public void springAmbush(Npc fake)
	{
		final Fighter fighter = _fighters.get(fake.getObjectId());
		if ((fighter != null) && (fighter._ambushSpot != null))
		{
			if (fighter.isInAmbush())
			{
				fighter._ambushSprungAt = System.currentTimeMillis();
			}
			fighter._ambushSpot = null;
		}
	}

	/**
	 * @param fake a fake player of a match
	 * @param range how far it looks
	 * @return where the closest enemy alone is (out of its base), {@code null} if none within {@code range}
	 */
	public Location findLoneEnemy(Npc fake, int range)
	{
		final Fighter fighter = _fighters.get(fake.getObjectId());
		return (fighter != null) && (fighter._match._phase == Phase.FIGHT) ? closest(fake, fighter._side._loneEnemies, range) : null;
	}

	/**
	 * @param fake a fake player of a match
	 * @return where the closest of its team mates that stand together are (it slips back to them), {@code null} if none
	 */
	public Location findRegroupSpot(Npc fake)
	{
		final Fighter fighter = _fighters.get(fake.getObjectId());
		if (fighter == null)
		{
			return null;
		}

		Location best = null;
		double bestDistance = Double.MAX_VALUE;
		for (Fighter mate : fighter._side._fighters)
		{
			if ((mate == fighter) || !mate.isAlive() || (mate._alliesNear == 0) || isInBase(mate._creature, mate))
			{
				continue;
			}
			final double distance = fake.calculateDistance2D(mate._creature);
			if (distance < bestDistance)
			{
				best = mate._creature.getLocation();
				bestDistance = distance;
			}
		}
		return best;
	}

	/**
	 * A spot for an ambush: by a wall or in a corner, near where the enemies come by (on their way from their spawn points to where the fighting is, the ones coming back from the dead often one by one; or to the middle of the field), out of their sight from that
	 * way until they are close. The ruins have walls and corners all over.
	 * @param fake a fake player of a match
	 * @return the spot, {@code null} if none found close enough
	 */
	public Location findAmbushSpot(Npc fake)
	{
		final Fighter fighter = _fighters.get(fake.getObjectId());
		if ((fighter == null) || (fighter._match._phase != Phase.FIGHT))
		{
			return null;
		}

		final Side side = fighter._side;
		final Side enemySide = fighter._match.getOther(side);
		final Location center = getFieldCenter();
		final GeoEngine geo = GeoEngine.getInstance();
		final int instanceId = fake.getInstanceId();

		// Where the enemies come by: somewhere on the way from each of their spawn points to the closest fight (else the middle), out of their base.
		final List<Location> ways = new ArrayList<>();
		final double reach = (TeamVsTeamConfig.BASE_RADIUS + BASE_APPROACH) * 1.2;
		for (Location base : enemySide._bases)
		{
			Location goal = center;
			for (Location clash : side._clashes)
			{
				if (Math.hypot(clash.getX() - base.getX(), clash.getY() - base.getY()) < Math.hypot(goal.getX() - base.getX(), goal.getY() - base.getY()))
				{
					goal = clash;
				}
			}
			final double length = Math.max(1, Math.hypot(goal.getX() - base.getX(), goal.getY() - base.getY()));
			if (length <= reach)
			{
				continue;
			}
			final double share = (reach / length) + (Rnd.nextDouble() * (1 - (reach / length)) * 0.7);
			final int x = base.getX() + (int) ((goal.getX() - base.getX()) * share);
			final int y = base.getY() + (int) ((goal.getY() - base.getY()) * share);
			ways.add(new Location(x, y, geo.getHeight(x, y, base.getZ() + (int) ((goal.getZ() - base.getZ()) * share))));
		}
		if (ways.isEmpty())
		{
			ways.add(center);
		}
		ways.sort((a, b) -> Double.compare(fake.calculateDistance2D(a), fake.calculateDistance2D(b)));

		for (int w = 0; w < Math.min(2, ways.size()); w++)
		{
			final Location way = ways.get(w);
			if (fake.calculateDistance2D(way) > AMBUSH_SEARCH_RANGE)
			{
				break;
			}

			Location best = null;
			double bestScore = Double.MAX_VALUE;
			for (int i = 0; i < 28; i++)
			{
				final double angle = Rnd.nextDouble() * 2 * Math.PI;
				final int distance = Rnd.get(AMBUSH_MIN_DISTANCE, AMBUSH_MAX_DISTANCE);
				final int x = way.getX() + (int) (Math.cos(angle) * distance);
				final int y = way.getY() + (int) (Math.sin(angle) * distance);
				final int z = geo.getHeight(x, y, way.getZ());
				if ((Math.abs(z - way.getZ()) > 200) || (enemySide.getBaseDistance(x, y) <= (TeamVsTeamConfig.BASE_RADIUS + BASE_APPROACH)) || (side.getBaseDistance(x, y) <= TeamVsTeamConfig.BASE_RADIUS))
				{
					continue;
				}

				// By a wall or in a corner: some ways round it are blocked, not all.
				int walls = 0;
				for (int p = 0; p < AMBUSH_PROBES; p++)
				{
					final double probe = (p * 2 * Math.PI) / AMBUSH_PROBES;
					final int px = x + (int) (Math.cos(probe) * AMBUSH_PROBE_LENGTH);
					final int py = y + (int) (Math.sin(probe) * AMBUSH_PROBE_LENGTH);
					if (!geo.canMoveToTarget(x, y, z, px, py, geo.getHeight(px, py, z), instanceId))
					{
						walls++;
					}
				}
				if ((walls < AMBUSH_MIN_WALLS) || (walls > AMBUSH_MAX_WALLS))
				{
					continue;
				}

				// Out of sight from where they come.
				if (geo.canSeeTarget(way.getX(), way.getY(), way.getZ(), x, y, z, instanceId))
				{
					continue;
				}

				// A corner (three or four ways blocked) rather than a plain wall, and close to their way.
				final double score = Math.abs(walls - 3.5) + (distance / 1000.0);
				if (score < bestScore)
				{
					best = new Location(x, y, z);
					bestScore = score;
				}
			}

			if ((best != null) && (geo.canMoveToTarget(fake.getX(), fake.getY(), fake.getZ(), best.getX(), best.getY(), best.getZ(), instanceId) || (PathFinding.getInstance().findPath(fake.getX(), fake.getY(), fake.getZ(), best.getX(), best.getY(), best.getZ(), instanceId, false) != null)))
			{
				return best;
			}
		}
		return null;
	}

	/**
	 * @param fake a fake player of a match roaming on its own
	 * @return a spot of the field to roam to, out of both teams' bases, {@code null} if none found
	 */
	public Location findWanderSpot(Npc fake)
	{
		final Fighter fighter = _fighters.get(fake.getObjectId());
		if (fighter == null)
		{
			return null;
		}

		final Side side = fighter._side;
		final Side enemySide = fighter._match.getOther(side);
		final Location center = getFieldCenter();
		final double radius = Math.hypot(side.getCenter().getX() - enemySide.getCenter().getX(), side.getCenter().getY() - enemySide.getCenter().getY()) * 0.45;
		final GeoEngine geo = GeoEngine.getInstance();
		for (int i = 0; i < 12; i++)
		{
			final double angle = Rnd.nextDouble() * 2 * Math.PI;
			final double distance = Math.sqrt(Rnd.nextDouble()) * radius;
			final int x = center.getX() + (int) (Math.cos(angle) * distance);
			final int y = center.getY() + (int) (Math.sin(angle) * distance);
			final double away = Math.hypot(fake.getX() - x, fake.getY() - y);
			if ((away < WANDER_MIN_DISTANCE) || (away > WANDER_MAX_DISTANCE) || (enemySide.getBaseDistance(x, y) <= (TeamVsTeamConfig.BASE_RADIUS + BASE_APPROACH)) || (side.getBaseDistance(x, y) <= TeamVsTeamConfig.BASE_RADIUS))
			{
				continue;
			}
			final int z = geo.getHeight(x, y, center.getZ());
			if (Math.abs(z - center.getZ()) <= 400)
			{
				return new Location(x, y, z);
			}
		}
		return null;
	}

	/**
	 * Where a fake player with no enemy in sight goes: to the closest team mates fighting within {@link #HELP_RANGE}, else to the closest enemy group out of its base, else to the middle of the field (the enemies are all at their base).
	 * @param fake a fake player of a match
	 * @return where to go, {@code null} for nowhere
	 */
	public Location getMarchTarget(Npc fake)
	{
		final Fighter fighter = _fighters.get(fake.getObjectId());
		if ((fighter == null) || (fighter._match._phase != Phase.FIGHT))
		{
			return null;
		}

		final Location help = closest(fake, fighter._side._clashes, HELP_RANGE);

		// One that knows the game picks off an enemy alone close by before it joins a fight further away.
		final Location lone = closest(fake, fighter._side._loneEnemies, (int) (LONE_PICK_RANGE * FakePlayerPvpPersonality.of(fake).getKnowledge()));
		if ((lone != null) && ((help == null) || (fake.calculateDistance2D(lone) < fake.calculateDistance2D(help))))
		{
			return lone;
		}

		if (help != null)
		{
			return inLane(fake, fighter, help, true);
		}

		final Location group = closest(fake, fighter._side._enemyGroups, Integer.MAX_VALUE);
		if (group != null)
		{
			return inLane(fake, fighter, group, true);
		}

		// The enemies are all back at their bases: it holds the middle of the field, where they come out to, instead of waiting at a base's edge.
		return inLane(fake, fighter, getFieldCenter(), false);
	}

	/**
	 * @return the middle of the field: of every spawn point of both teams
	 */
	public static Location getFieldCenter()
	{
		final List<Location> all = new ArrayList<>(TeamVsTeamConfig.BLUE_BASES);
		all.addAll(TeamVsTeamConfig.RED_BASES);
		return center(all);
	}

	/**
	 * @param locations some spots (at least one)
	 * @return their middle, on the ground
	 */
	static Location center(List<Location> locations)
	{
		long x = 0;
		long y = 0;
		long z = 0;
		for (Location location : locations)
		{
			x += location.getX();
			y += location.getY();
			z += location.getZ();
		}
		final int size = locations.size();
		return new Location((int) (x / size), (int) (y / size), GeoEngine.getInstance().getHeight((int) (x / size), (int) (y / size), (int) (z / size)));
	}

	/**
	 * Moves a spot a fake player marches to into its party's lane: the parties of a team come at it from its left, the middle and its right (across the field, between the bases) instead of all on one line. Coming at a fight the lane narrows as it closes in, so
	 * they flank it, then meet; holding the middle of the field it stays as wide, a front across it. A lane that runs into a wall is half as wide, else the party takes the middle one.
	 * @param fake a fake player of a match
	 * @param fighter its fighter
	 * @param spot where it marches to
	 * @param closingIn {@code true} if the lane narrows close to {@code spot}
	 * @return where it goes
	 */
	private static Location inLane(Npc fake, Fighter fighter, Location spot, boolean closingIn)
	{
		final FakePlayerParty party = FakePartyManager.getInstance().getParty(fake);
		final int lane = fighter._side.getLane(party != null ? party : fighter);
		double offset = lane * LANE_WIDTH;
		if (closingIn)
		{
			offset *= Math.max(0, Math.min(1, (fake.calculateDistance2D(spot) - LANE_MERGE) / (LANE_TAPER - LANE_MERGE)));
		}
		if (Math.abs(offset) < 50)
		{
			return spot;
		}

		// Across the field: square to the line between the middles of the teams' spawn points.
		final Location own = fighter._side.getCenter();
		final Location enemy = fighter._match.getOther(fighter._side).getCenter();
		final double length = Math.max(1, Math.hypot(enemy.getX() - own.getX(), enemy.getY() - own.getY()));
		final double acrossX = -(enemy.getY() - own.getY()) / length;
		final double acrossY = (enemy.getX() - own.getX()) / length;
		final GeoEngine geo = GeoEngine.getInstance();
		for (double share = 1; share >= 0.5; share -= 0.5)
		{
			final int x = spot.getX() + (int) (acrossX * offset * share);
			final int y = spot.getY() + (int) (acrossY * offset * share);
			final int z = geo.getHeight(x, y, spot.getZ());
			if (geo.canMoveToTarget(spot.getX(), spot.getY(), spot.getZ(), x, y, z, fake.getInstanceId()))
			{
				return new Location(x, y, z);
			}
		}
		return spot;
	}

	private static Location closest(Npc fake, Collection<Location> locations, int range)
	{
		Location best = null;
		double bestDistance = range;
		for (Location location : locations)
		{
			final double distance = fake.calculateDistance2D(location);
			if ((distance > 50) && (distance < bestDistance))
			{
				best = location;
				bestDistance = distance;
			}
		}
		return best;
	}

	/**
	 * Starts a match of fake players only (no registration, no players): both teams are parties of fake players of the bracket of {@code level}. It plays to its end with nobody watching and logs its kills, to try the event out.
	 * @param level a level of the bracket to play
	 * @return what to tell the GM
	 */
	public synchronized String simulate(int level)
	{
		if (!FakePlayerPvpManager.getInstance().isEnabled())
		{
			return "Fake players are disabled (EnableFakePlayers, FakePvpEnabled).";
		}
		if ((TeamVsTeamConfig.BLUE_BASE == null) || (TeamVsTeamConfig.RED_BASE == null))
		{
			return "A team has no spawn point (TvTBlueBase, TvTRedBase).";
		}
		
		final int index = getBracket(level);
		if (index < 0)
		{
			return "Level " + level + " is outside every bracket (" + describeBrackets() + ").";
		}
		
		final Match match = startMatch(TeamVsTeamConfig.BRACKETS.get(index), List.of(), List.of(), true);
		if (match == null)
		{
			return "The instance could not be created.";
		}
		return "Simulated match " + match.getId() + " (levels " + match.getBracketName() + ") started: " + match._blue._fighters.size() + " vs " + match._red._fighters.size() + " fake players, the fight in " + TeamVsTeamConfig.PREP_TIME + " seconds.";
	}
	
	/**
	 * Ends a match now, without rewards.
	 * @param id the match id, 0 for all
	 * @return what to tell the GM
	 */
	public String stopMatch(int id)
	{
		int stopped = 0;
		for (Match match : _matches.values())
		{
			if (((id == 0) || (match.getId() == id)) && (match._phase != Phase.ENDED))
			{
				endMatch(match, true);
				stopped++;
			}
		}
		return stopped == 0 ? "No match to stop." : stopped + " match" + (stopped > 1 ? "es" : "") + " stopped.";
	}
	
	/**
	 * @param detail {@code true} for a line per fighter
	 * @return the state of the event: registration, and each match with its score and teams
	 */
	public List<String> getStatus(boolean detail)
	{
		final List<String> lines = new ArrayList<>();
		lines.add(isRegistrationOpen() ? "Registration open, " + getRegistrationSecondsLeft() + " s left, " + getRegisteredCount() + " registered, " + _managers.size() + " TvT Event Manager(s) out." : "Registration closed.");
		if (_matches.isEmpty())
		{
			lines.add("No match going on.");
		}
		
		// What is left in the world (should be nothing once the matches are over).
		int fakesInWorld = 0;
		for (Npc fake : FakePlayerPvpManager.getInstance().getFakePlayers())
		{
			final FakePlayerPvpProfile profile = fake.getTemplate().getFakePlayerPvpProfile();
			if ((profile != null) && profile.isTvtFighter())
			{
				fakesInWorld++;
			}
		}
		lines.add("Fighters tracked: " + _fighters.size() + ", Team vs Team fake players in the world: " + fakesInWorld + ".");
		for (Match match : _matches.values())
		{
			lines.add("Match " + match.getId() + " (levels " + match.getBracketName() + (match._simulated ? ", simulated" : "") + "): " + match._phase + ", " + match.getSecondsLeft() + " s left, Blue " + match._blue.getScore() + " : " + match._red.getScore() + " Red, instance " + match._instanceId + ".");
			for (Side side : new Side[]
			{
				match._blue,
				match._red
			})
			{
				int alive = 0;
				int atBase = 0;
				long x = 0;
				long y = 0;
				for (Fighter fighter : side._fighters)
				{
					if (fighter.isAlive())
					{
						alive++;
						x += fighter._creature.getX();
						y += fighter._creature.getY();
						if (side.getBaseDistance(fighter._creature) <= TeamVsTeamConfig.BASE_RADIUS)
						{
							atBase++;
						}
					}
				}
				lines.add("  " + side._name + ": " + alive + "/" + side._fighters.size() + " alive (" + side.getPlayerCount() + " players, " + side._parties.size() + " parties), " + atBase + " at base" + (alive > 0 ? ", middle " + (x / alive) + "," + (y / alive) : "") + ", " + side._clashes.size() + " fighting.");
				if (!detail)
				{
					continue;
				}
				for (Fighter fighter : side._fighters)
				{
					final Creature creature = fighter._creature;
					final FakePlayerParty party = FakePartyManager.getInstance().getParty(creature);
					final Creature target = creature.getTarget() instanceof Creature ? (Creature) creature.getTarget() : null;
					lines.add("    " + describe(fighter) + " lv" + creature.getLevel() + (creature.isDead() ? " DEAD" : " " + (int) ((100 * creature.getCurrentHp()) / creature.getMaxHp()) + "%hp") + (fighter.isProtected() ? " protected" : "") + " k" + fighter._kills + "/d" + fighter._deaths + " at " + creature.getX() + "," + creature.getY() + "," + creature.getZ() + " base " + (int) side.getBaseDistance(creature) + (fighter._trapped ? " TRAPPED" : "") + (fighter.isLone() ? " lone" : "") + (fighter._ambushSpot != null ? (fighter.isInAmbush() ? " in-ambush" : " to-ambush") : "") + " near " + fighter._alliesNear + (creature.hasAI() ? " " + creature.getAI().getIntention() : "") + (creature.isMoving() ? " moving" : "") + (creature.isCastingNow() ? " casting" : "") + describeAiState(creature) + (target != null ? " target " + target.getName() : "") + (party != null ? " party " + Integer.toHexString(System.identityHashCode(party)) : " no party"));
				}
			}
		}
		return lines;
	}
	
	/**
	 * @param creature a fighter
	 * @return what would keep a fake player's AI from thinking: its AI task off, a think stuck, its core AI or all its skills disabled (empty if none)
	 */
	private static String describeAiState(Creature creature)
	{
		if (!creature.isAttackable() || creature.isDead())
		{
			return "";
		}
		
		final StringBuilder sb = new StringBuilder();
		if (!AttackableThinkTaskManager.getInstance().isThinking(creature.asAttackable()))
		{
			sb.append(" NO-AI-TASK");
		}
		if (creature.hasAI() && (creature.getAI() instanceof AttackableAI ai) && ai.isThinkingNow())
		{
			sb.append(" THINKING");
		}
		if (creature.asAttackable().isCoreAIDisabled())
		{
			sb.append(" CORE-AI-OFF");
		}
		if (creature.isAllSkillsDisabled())
		{
			sb.append(" SKILLS-OFF");
		}
		if ((creature.getWorldRegion() == null) || !creature.getWorldRegion().areNeighborsActive())
		{
			sb.append(" REGION-ASLEEP");
		}
		return sb.toString();
	}
	
	/**
	 * @param fighter a fighter
	 * @return its name, team and build (a player's class)
	 */
	private static String describe(Fighter fighter)
	{
		final Creature creature = fighter._creature;
		final String build;
		if (creature.isPlayer())
		{
			build = creature.asPlayer().getPlayerClass().name();
		}
		else
		{
			final FakePlayerPvpProfile profile = creature.asNpc().getTemplate().getFakePlayerPvpProfile();
			build = ((profile != null) && (profile.getBuild() != null) ? profile.getBuild().getName() : "?") + (profile != null ? String.format(", knowledge %.2f, teamplay %.2f", profile.getPersonality().getKnowledge(), profile.getPersonality().getTeamplay()) : "");
		}
		return creature.getName() + " (" + fighter._side._name + ", " + build + ")";
	}
	
	/**
	 * @return the matches going on
	 */
	public Collection<Match> getMatches()
	{
		return _matches.values();
	}

	private static void sendScreen(Player player, String text, int seconds, boolean effect)
	{
		player.sendPacket(new ExShowScreenMessage(text, ExShowScreenMessage.TOP_CENTER, seconds * 1000, 0, true, effect));
	}

	public static TeamVsTeamManager getInstance()
	{
		return SingletonHolder.INSTANCE;
	}

	private static class SingletonHolder
	{
		protected static final TeamVsTeamManager INSTANCE = new TeamVsTeamManager();
	}
}
