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

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ScheduledFuture;
import java.util.function.Predicate;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.l2jmobius.commons.database.DatabaseFactory;
import org.l2jmobius.commons.threads.ThreadPool;
import org.l2jmobius.commons.util.Rnd;
import org.l2jmobius.gameserver.ai.Intention;
import org.l2jmobius.gameserver.config.PlayerConfig;
import org.l2jmobius.gameserver.config.custom.FakePlayerPvpConfig;
import org.l2jmobius.gameserver.config.custom.VillageSiegeConfig;
import org.l2jmobius.gameserver.data.SpawnTable;
import org.l2jmobius.gameserver.data.xml.ExperienceData;
import org.l2jmobius.gameserver.data.xml.NpcData;
import org.l2jmobius.gameserver.data.xml.VillageSiegeData;
import org.l2jmobius.gameserver.geoengine.GeoEngine;
import org.l2jmobius.gameserver.model.Location;
import org.l2jmobius.gameserver.model.StatSet;
import org.l2jmobius.gameserver.model.World;
import org.l2jmobius.gameserver.model.WorldRegion;
import org.l2jmobius.gameserver.model.actor.Attackable;
import org.l2jmobius.gameserver.model.actor.Creature;
import org.l2jmobius.gameserver.model.actor.Npc;
import org.l2jmobius.gameserver.model.actor.Player;
import org.l2jmobius.gameserver.model.actor.holders.npc.FakePlayerPvpProfile;
import org.l2jmobius.gameserver.model.actor.instance.Guard;
import org.l2jmobius.gameserver.model.actor.instance.VillageGate;
import org.l2jmobius.gameserver.model.actor.templates.NpcTemplate;
import org.l2jmobius.gameserver.model.dynamicquest.WorldMonsterIndex;
import org.l2jmobius.gameserver.model.events.EventType;
import org.l2jmobius.gameserver.model.events.holders.actor.creature.OnCreatureDeath;
import org.l2jmobius.gameserver.model.events.listeners.ConsumerEventListener;
import org.l2jmobius.gameserver.model.interfaces.ILocational;
import org.l2jmobius.gameserver.model.item.enums.ItemProcessType;
import org.l2jmobius.gameserver.model.olympiad.OlympiadManager;
import org.l2jmobius.gameserver.model.script.Quest;
import org.l2jmobius.gameserver.model.spawns.Spawn;
import org.l2jmobius.gameserver.model.villagesiege.SiegeEntrance;
import org.l2jmobius.gameserver.model.villagesiege.SiegeEntranceFinder;
import org.l2jmobius.gameserver.model.villagesiege.SiegeVillage;
import org.l2jmobius.gameserver.model.zone.type.TownZone;
import org.l2jmobius.gameserver.network.enums.ChatType;
import org.l2jmobius.gameserver.network.serverpackets.CreatureSay;
import org.l2jmobius.gameserver.network.serverpackets.ExShowScreenMessage;
import org.l2jmobius.gameserver.network.serverpackets.MagicSkillUse;
import org.l2jmobius.gameserver.network.serverpackets.NpcHtmlMessage;
import org.l2jmobius.gameserver.taskmanagers.AttackStanceTaskManager;
import org.l2jmobius.gameserver.util.LocationUtil;
import org.l2jmobius.gameserver.util.QuestSpawnUtil;

/**
 * Village Under Siege: one village or town after the other, the monsters of the hunting grounds around it attack its entrances.
 * <ul>
 * <li>Schedule: the villages of {@link VillageSiegeConfig#ORDER} are besieged one after the other, over and over, {@link VillageSiegeConfig#DURATION} minutes each with a {@link VillageSiegeConfig#BREAK} minute rest, counted from a fixed time ({@link #getSlots}). A
 * restart picks the rotation up where it stands. Each siege is for the characters of its village's defender levels ({@link SiegeVillage#isDefenderLevel}: up to {@link VillageSiegeConfig#MAX_PLAYER_LEVEL} for the starting villages, higher for the towns): only they
 * hear of it, are invited and may teleport there for free ({@link #join}).</li>
 * <li>Entrances: the roads the monsters take into the village ({@link SiegeEntranceFinder}). Each has a gate ({@link VillageGate}) with HP: the monsters fight the defenders and the guards first and, with nobody left in front of them, beat on the gate. At 0 HP it falls and
 * the monsters run into the streets; it is raised again after a while. A fallen gate doesn't end the siege, but halves its end bonus and cuts the defenders' reward.</li>
 * <li>Three stages (Vanguard, Main Assault, Final Assault): each entrance is kept under pressure, a number of monsters on it that grows with the stage and the defenders there, topped up every few seconds; and now and then a great wave marches on it. The monsters are
 * the ones living around the village, at its level ({@link SiegeEntranceFinder#findLevel}), and for the defenders above that level, monsters of their own level ({@link VillageSiegeConfig#SCALE_TO_DEFENDERS}). A Warband Leader comes now and then and a Siege Lord near the
 * end.</li>
 * <li>Guards: the village's own guards are hidden for the siege (they would end every wave at once), and siege guards a few levels above the monsters hold a line at each gate with the defenders.</li>
 * <li>Fake players come to defend too ({@link FakePlayerPvpProfile#isSiegeDefender()}): more when no player is there, so the fighting goes on unwatched. The monsters can kill them, and they leave after a while.</li>
 * <li>Contribution: damage to the siege's monsters (shared by damage on each kill), healing the defenders and every minute of fighting. Players of the siege's defender levels are paid in Defence Medals as they earn them (the more, the stronger the monster:
 * {@link #getMedalsPerPoint}), get more experience from the siege's monsters and a reward in experience at the end (a smaller share of a level the higher they are: {@link #getRewardExpPercent}); fake players are ranked too. The Defence Force Board of each village
 * shows the rankings and exchanges the medals for gear up to C grade and for decorations.</li>
 * </ul>
 */
public class VillageSiegeManager
{
	private static final Logger LOGGER = Logger.getLogger(VillageSiegeManager.class.getName());

	/** The Defence Force Board, near the gatekeeper of every village. */
	public static final int BOARD_NPC_ID = 900600;
	/** The gate of an entrance (its template is made for each siege, never in the npc data). */
	public static final int STANDARD_NPC_ID = 900601;
	/** Ids of the siege guard templates made for each siege (never in the npc data). */
	private static final int GUARD_NPC_ID = 900610;
	/** The exchange lists of the board: No-grade, D and C weapons, armor and jewelry, then the decorations (hair accessories, hats, and agathions with the Formal Wear). */
	public static final int FIRST_MULTISELL_ID = 900600;
	public static final int MULTISELL_COUNT = 12;

	/** Kinds of monster. */
	private static final int KIND_WAVE = 0;
	private static final int KIND_LEADER = 1;
	private static final int KIND_LORD = 2;

	private static final long TICK_MS = 1000;
	/** The siege going on is logged every this many ticks. */
	private static final int STATUS_LOG_TICKS = 300;
	/** A monster that hasn't met anyone for this long is taken away (the limit of monsters would fill up). */
	private static final long MONSTER_LIFETIME = 15 * 60000L;
	/** A monster looks for a defender this far around it. */
	private static final int MONSTER_SIGHT = 450;
	/** A fake defender fights the monsters this far from its gate (or from itself). */
	private static final int DEFENDER_REACH = 900;
	/** Regions this far around the village stay awake during a siege. */
	private static final int KEEP_ACTIVE_RANGE = 2500;
	/** A monster with nobody to fight this close to a standing gate beats on it. */
	private static final int GATE_ATTACK_RANGE = 700;
	/** A monster beating on the gate turns on a defender this close (now and then, so the gate keeps taking blows). */
	private static final int GATE_TURN_RANGE = 300;
	/** The gate is mended only when it hasn't been hit for this long. */
	private static final long GATE_REPAIR_DELAY = 10000;
	/** The defenders near a gate are told how it stands at these HP percents. */
	private static final int[] GATE_ALARMS =
	{
		75,
		50,
		25,
		10
	};
	/** Monsters of a top-up of the pressure come in groups of this many. */
	private static final int REINFORCE_GROUP_MIN = 2;
	private static final int REINFORCE_GROUP_MAX = 4;
	/** Each stage starts at this part of the siege. */
	private static final double[] STAGE_STARTS =
	{
		0,
		0.3,
		0.7
	};
	private static final String[] STAGE_NAMES =
	{
		"Vanguard",
		"Main Assault",
		"Final Assault"
	};
	/** The players who came by the siege's teleport can go back where they were this long after it ended. */
	private static final long RETURN_TIME = 20 * 60000L;
	/** Fake defenders arrive in groups of up to this many. */
	private static final int FAKE_GROUP_MAX = 3;
	/** A siege's waves are made of this many monster kinds at least, looked for farther out until there are. */
	private static final int MIN_MONSTER_KINDS = 3;
	/** When no monster of a siege's levels lives around the village, they are looked for this far. */
	private static final int FAR_MONSTER_RANGE = 40000;
	/** Fake defenders arrive this far behind the gate, inside the village. */
	private static final int FAKE_ARRIVAL_RANGE = 250;

	private static final String CREATE_TABLE = "CREATE TABLE IF NOT EXISTS `village_siege_ranking` (`day` INT UNSIGNED NOT NULL, `village` VARCHAR(32) NOT NULL, `name` VARCHAR(35) NOT NULL, `char_id` INT UNSIGNED NOT NULL DEFAULT 0, `fake` TINYINT UNSIGNED NOT NULL DEFAULT 0, `level` TINYINT UNSIGNED NOT NULL DEFAULT 0, `points` INT UNSIGNED NOT NULL DEFAULT 0, `medals` INT UNSIGNED NOT NULL DEFAULT 0, `gate_fell` TINYINT UNSIGNED NOT NULL DEFAULT 0, `end_time` BIGINT UNSIGNED NOT NULL DEFAULT 0, PRIMARY KEY (`day`, `village`, `name`)) ENGINE=InnoDB DEFAULT CHARSET=utf8 COLLATE=utf8_unicode_ci";
	private static final String INSERT_RANKING = "REPLACE INTO `village_siege_ranking` (`day`, `village`, `name`, `char_id`, `fake`, `level`, `points`, `medals`, `gate_fell`, `end_time`) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
	private static final String SELECT_LATEST = "SELECT r.* FROM `village_siege_ranking` r JOIN (SELECT `village`, MAX(`day`) AS `day` FROM `village_siege_ranking` GROUP BY `village`) l ON r.`village` = l.`village` AND r.`day` = l.`day`";
	private static final String DELETE_OLD = "DELETE FROM `village_siege_ranking` WHERE `day` < ?";
	private static final String UPDATE_GATE_FELL = "UPDATE `village_siege_ranking` SET `gate_fell` = 1 WHERE `day` = ? AND `village` = ?";

	private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("HH:mm");

	/**
	 * Someone who fought in a siege: a player, or a fake player (by name).
	 */
	public static class Contributor
	{
		private final String _key;
		private final int _objectId;
		private final String _name;
		private final boolean _fake;
		private volatile int _level;
		private double _points;
		private double _pendingMedals;
		private long _medals;
		private volatile long _lastActive;
		private volatile String _blocked;
		private volatile boolean _told;
		private int _activeMinutes;

		Contributor(String key, int objectId, String name, boolean fake, int level)
		{
			_key = key;
			_objectId = objectId;
			_name = name;
			_fake = fake;
			_level = level;
		}

		public String getName()
		{
			return _name;
		}

		public boolean isFake()
		{
			return _fake;
		}

		public int getObjectId()
		{
			return _objectId;
		}

		public int getLevel()
		{
			return _level;
		}

		public synchronized int getPoints()
		{
			return (int) _points;
		}

		public synchronized long getMedals()
		{
			return _medals;
		}

		/**
		 * @return the medals earned so far, paid or not
		 */
		private synchronized double getEarnedMedals()
		{
			return _medals + _pendingMedals;
		}

		/**
		 * @return why it earns no medals ({@code null} if it does)
		 */
		public String getBlocked()
		{
			return _blocked;
		}

		public long getLastActive()
		{
			return _lastActive;
		}

		/**
		 * @return the minutes in which it fought
		 */
		public synchronized int getActiveMinutes()
		{
			return _activeMinutes;
		}
	}

	/**
	 * A monster of a siege, and who hurt it.
	 */
	private static class SiegeMonster
	{
		final Npc _npc;
		final EntranceState _entrance;
		final int _kind;
		final long _spawned;
		final Map<String, Double> _damage = new ConcurrentHashMap<>();
		volatile long _lastMet;

		SiegeMonster(Npc npc, EntranceState entrance, int kind)
		{
			_npc = npc;
			_entrance = entrance;
			_kind = kind;
			_spawned = System.currentTimeMillis();
			_lastMet = _spawned;
		}
	}

	/**
	 * An entrance during a siege.
	 */
	public static class EntranceState
	{
		private final int _index;
		private final SiegeEntrance _entrance;
		private final List<Integer> _monsterIds;
		private final Npc[] _guards;
		private final long[] _guardRespawn;
		private volatile Npc _standard;
		private volatile boolean _fallen;
		private volatile long _fallenAt;
		private volatile long _lastHit;
		private volatile int _alarm = 100;
		private volatile long _nextWave;
		private volatile long _nextReinforce;
		private volatile int _shownPercent = -1;
		private volatile int _falls;

		EntranceState(int index, SiegeEntrance entrance, List<Integer> monsterIds)
		{
			_index = index;
			_entrance = entrance;
			_monsterIds = monsterIds;
			_guards = new Npc[entrance.getGuardPosts().size()];
			_guardRespawn = new long[_guards.length];
		}

		public String getName()
		{
			return _entrance.getName();
		}

		public Location getGate()
		{
			return _entrance.getGate();
		}

		public boolean isFallen()
		{
			return _fallen;
		}

		/**
		 * @return how many times it fell
		 */
		public int getFalls()
		{
			return _falls;
		}

		/**
		 * @return the gate's HP, 0 if it has fallen
		 */
		public int getGateHp()
		{
			final Npc standard = _standard;
			return _fallen || (standard == null) ? 0 : (int) Math.ceil(standard.getCurrentHp());
		}

		/**
		 * @return the gate's HP when whole
		 */
		public int getGateMaxHp()
		{
			final Npc standard = _standard;
			return standard != null ? standard.getMaxHp() : VillageSiegeConfig.GATE_HP;
		}

		/**
		 * @return what is left of the gate, in %
		 */
		public int getMeterPercent()
		{
			return _fallen ? 0 : (int) Math.ceil((getGateHp() * 100.0) / Math.max(1, getGateMaxHp()));
		}

		/**
		 * @return when a fallen gate is raised again, 0 if never (or it stands)
		 */
		public long getRebuildTime()
		{
			return _fallen && (VillageSiegeConfig.GATE_REBUILD_MINUTES > 0) ? _fallenAt + (VillageSiegeConfig.GATE_REBUILD_MINUTES * 60000L) : 0;
		}

		/**
		 * @return where the guards stand, in the middle of their line
		 */
		Location getGuardCentre()
		{
			final List<Location> posts = _entrance.getGuardPosts();
			return posts.get(posts.size() / 2);
		}
	}

	/**
	 * A siege going on.
	 */
	public static class Siege
	{
		private final SiegeVillage _village;
		private final int _level;
		private final long _start;
		private final long _end;
		private final int _day;
		private final Location _heart;
		private final TownZone _town;
		private final List<EntranceState> _entrances = new ArrayList<>();
		private final Map<Integer, SiegeMonster> _monsters = new ConcurrentHashMap<>();
		private final List<Spawn> _hiddenGuards = new ArrayList<>();
		private final Set<Npc> _fakes = ConcurrentHashMap.newKeySet();
		private final Map<String, Contributor> _contributors = new ConcurrentHashMap<>();
		private final Map<String, String> _computers = new ConcurrentHashMap<>();
		private final List<WorldRegion> _keptRegions = new ArrayList<>();
		/** The monster kinds of each level, for the defenders above the village's levels. */
		private final Map<Integer, List<Integer>> _scaledMonsters = new ConcurrentHashMap<>();
		private volatile NpcTemplate _guardTemplate;
		private volatile NpcTemplate _gateTemplate;
		private volatile boolean _gateFell;
		private volatile long _nextLeader;
		private volatile boolean _lordSent;
		private volatile long _nextFake;
		private volatile long _nextStatus;
		private volatile int _stage;
		private volatile int _kills;
		private volatile int _waves;
		private int _ticks;

		Siege(SiegeVillage village, int level, long start, long end, int day, Location heart, TownZone town)
		{
			_village = village;
			_level = level;
			_start = start;
			_end = end;
			_day = day;
			_heart = heart;
			_town = town;
		}

		public SiegeVillage getVillage()
		{
			return _village;
		}

		public int getLevel()
		{
			return _level;
		}

		public long getEnd()
		{
			return _end;
		}

		public long getStart()
		{
			return _start;
		}

		public List<EntranceState> getEntrances()
		{
			return Collections.unmodifiableList(_entrances);
		}

		public boolean hasGateFallen()
		{
			return _gateFell;
		}

		public int getKills()
		{
			return _kills;
		}

		/**
		 * @return the great waves sent so far
		 */
		public int getWaves()
		{
			return _waves;
		}

		/**
		 * @return the stage it is at: 0, 1 or 2
		 */
		public int getStage()
		{
			return _stage;
		}

		public String getStageName()
		{
			return STAGE_NAMES[_stage];
		}

		/**
		 * @return how many gates stand
		 */
		public int getGatesStanding()
		{
			int standing = 0;
			for (EntranceState entrance : _entrances)
			{
				if (!entrance._fallen)
				{
					standing++;
				}
			}
			return standing;
		}

		/**
		 * @return the players at the siege now (of its defender levels)
		 */
		public int getPlayersPresent()
		{
			int players = 0;
			for (Player player : World.getInstance().getPlayers())
			{
				if (player.isOnline() && !player.isInOfflineMode() && !player.isGM() && _village.isDefenderLevel(player.getLevel()) && isInArea(this, player))
				{
					players++;
				}
			}
			return players;
		}

		public int getMonstersAlive()
		{
			return _monsters.size();
		}

		public int getFakeDefenders()
		{
			int alive = 0;
			for (Npc fake : _fakes)
			{
				if (fake.isSpawned() && !fake.isDead())
				{
					alive++;
				}
			}
			return alive;
		}

		/**
		 * @param where a place
		 * @return {@code true} if it is in the besieged village (its town zone, or close to its heart)
		 */
		public boolean covers(ILocational where)
		{
			return _town.isInsideZone(where) || (LocationUtil.calculateDistance(where, _heart, false, false) <= VillageSiegeConfig.AREA_RANGE);
		}

		public Contributor getContributor(Player player)
		{
			return _contributors.get(playerKey(player));
		}

		/**
		 * @return the contributors, the most points first
		 */
		public List<Contributor> getRanking()
		{
			final List<Contributor> ranking = new ArrayList<>();
			for (Contributor contributor : _contributors.values())
			{
				if (contributor.getPoints() > 0)
				{
					ranking.add(contributor);
				}
			}
			ranking.sort((a, b) -> Integer.compare(b.getPoints(), a.getPoints()));
			return ranking;
		}
	}

	/**
	 * A line of a ranking that was saved.
	 */
	public static class RankRow
	{
		private final String _name;
		private final boolean _fake;
		private final int _level;
		private final int _points;
		private final long _medals;

		RankRow(String name, boolean fake, int level, int points, long medals)
		{
			_name = name;
			_fake = fake;
			_level = level;
			_points = points;
			_medals = medals;
		}

		public String getName()
		{
			return _name;
		}

		public boolean isFake()
		{
			return _fake;
		}

		public int getLevel()
		{
			return _level;
		}

		public int getPoints()
		{
			return _points;
		}

		public long getMedals()
		{
			return _medals;
		}
	}

	/**
	 * The result of a village's last day of siege (its sieges of that day added up).
	 */
	public static class SiegeResult
	{
		private final String _village;
		private final int _day;
		private final long _endTime;
		private final boolean _gateFell;
		private final List<RankRow> _rows;

		SiegeResult(String village, int day, long endTime, boolean gateFell, List<RankRow> rows)
		{
			_village = village;
			_day = day;
			_endTime = endTime;
			_gateFell = gateFell;
			_rows = rows;
		}

		public String getVillage()
		{
			return _village;
		}

		public int getDay()
		{
			return _day;
		}

		public long getEndTime()
		{
			return _endTime;
		}

		public boolean hasGateFallen()
		{
			return _gateFell;
		}

		/**
		 * @return the rows, the most points first
		 */
		public List<RankRow> getRows()
		{
			return _rows;
		}
	}

	/**
	 * A siege of the day's schedule.
	 */
	public static class Slot
	{
		private final SiegeVillage _village;
		private final long _start;
		private final long _end;

		Slot(SiegeVillage village, long start, long end)
		{
			_village = village;
			_start = start;
			_end = end;
		}

		public SiegeVillage getVillage()
		{
			return _village;
		}

		public long getStart()
		{
			return _start;
		}

		public long getEnd()
		{
			return _end;
		}
	}

	private volatile Siege _siege;
	private ScheduledFuture<?> _tickTask;
	private final List<ScheduledFuture<?>> _scheduled = new CopyOnWriteArrayList<>();
	/** Found once per village: its entrances and its level. */
	private final Map<String, List<SiegeEntrance>> _entrances = new ConcurrentHashMap<>();
	private final Map<String, Integer> _levels = new ConcurrentHashMap<>();
	/** The last result of each village. */
	private final Map<String, SiegeResult> _results = new ConcurrentHashMap<>();
	/** The Defence Force Boards, by village. */
	private final Map<String, Npc> _boards = new ConcurrentHashMap<>();
	/** Where the players who came by the siege's teleport were, by object id. */
	private final Map<Integer, Location> _returns = new ConcurrentHashMap<>();
	private volatile long _returnUntil;

	protected VillageSiegeManager()
	{
		if (!VillageSiegeConfig.ENABLED)
		{
			return;
		}

		createTable();
		loadResults();
		spawnBoards();

		// The entrances and levels are found once, at startup.
		for (SiegeVillage village : VillageSiegeData.getInstance().getVillages())
		{
			final List<SiegeEntrance> entrances = getEntrances(village);
			final StringBuilder names = new StringBuilder();
			for (SiegeEntrance entrance : entrances)
			{
				names.append(names.length() > 0 ? ", " : "").append(entrance.getName());
			}
			LOGGER.info(getClass().getSimpleName() + ": " + village.getName() + ": level " + getLevel(village) + ", " + entrances.size() + " entrances (" + names + "), " + getMedalsPerHour(getLevel(village)) + " medals an hour.");
		}
		scheduleNext();
	}

	// ---------------------------------------------------------------------------------------------
	// Schedule
	// ---------------------------------------------------------------------------------------------

	/**
	 * @param now a time
	 * @return the start of the schedule's day that time is in (the day starts at {@link VillageSiegeConfig#FIRST_START_HOUR})
	 */
	private static LocalDateTime getCycleStart(LocalDateTime now)
	{
		final LocalDateTime today = now.toLocalDate().atTime(LocalTime.of(VillageSiegeConfig.FIRST_START_HOUR, VillageSiegeConfig.FIRST_START_MINUTE));
		return now.isBefore(today) ? today.minusDays(1) : today;
	}

	/**
	 * @param time a time
	 * @return the schedule's day of that time, as yyyymmdd
	 */
	public static int getDay(long time)
	{
		final LocalDate date = getCycleStart(LocalDateTime.ofInstant(Instant.ofEpochMilli(time), ZoneId.systemDefault())).toLocalDate();
		return (date.getYear() * 10000) + (date.getMonthValue() * 100) + date.getDayOfMonth();
	}

	/**
	 * @return the villages of {@link VillageSiegeConfig#ORDER}, in order
	 */
	private static List<SiegeVillage> getOrder()
	{
		final List<SiegeVillage> villages = new ArrayList<>();
		for (String id : VillageSiegeConfig.ORDER)
		{
			final SiegeVillage village = VillageSiegeData.getInstance().getVillage(id);
			if (village != null)
			{
				villages.add(village);
			}
		}
		return villages;
	}

	/**
	 * The sieges follow each other without end, {@link VillageSiegeConfig#DURATION} minutes each with a {@link VillageSiegeConfig#BREAK} minute rest, counted from {@link VillageSiegeConfig#FIRST_START_HOUR} on 1 January 2026 (server time): a restart picks the rotation up where
	 * it stands.
	 * @return when the first siege of the rotation started
	 */
	private static long getAnchor()
	{
		return LocalDate.of(2026, 1, 1).atTime(VillageSiegeConfig.FIRST_START_HOUR, VillageSiegeConfig.FIRST_START_MINUTE).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
	}

	/**
	 * @param time a time
	 * @return the siege going on at that time (if any) and those that start within the following 24 hours, in order
	 */
	public static List<Slot> getSlots(long time)
	{
		final List<Slot> slots = new ArrayList<>();
		final List<SiegeVillage> villages = getOrder();
		if (villages.isEmpty())
		{
			return slots;
		}

		final long anchor = getAnchor();
		final long period = (VillageSiegeConfig.DURATION + VillageSiegeConfig.BREAK) * 60000L;
		for (long index = Math.floorDiv(time - anchor, period);; index++)
		{
			final long start = anchor + (index * period);
			if (start > (time + 86400000L))
			{
				break;
			}
			final long end = start + (VillageSiegeConfig.DURATION * 60000L);
			if (end > time)
			{
				slots.add(new Slot(villages.get((int) Math.floorMod(index, villages.size())), start, end));
			}
		}
		return slots;
	}

	/**
	 * @param village a village, {@code null} for any
	 * @return the next siege of the village that hasn't started, {@code null} if none
	 */
	public static Slot getNextSlot(SiegeVillage village)
	{
		return findNextSlot(besieged -> (village == null) || (besieged == village));
	}

	/**
	 * @param player a player
	 * @return the next siege for the player's level that hasn't started, {@code null} if none
	 */
	public static Slot getNextSlotFor(Player player)
	{
		return findNextSlot(besieged -> besieged.isDefenderLevel(player.getLevel()));
	}

	/**
	 * @param wanted the villages looked for
	 * @return the next siege of one of them that hasn't started, {@code null} if none
	 */
	private static Slot findNextSlot(Predicate<SiegeVillage> wanted)
	{
		final long now = System.currentTimeMillis();
		final int villages = Math.max(1, getOrder().size());
		final long period = (VillageSiegeConfig.DURATION + VillageSiegeConfig.BREAK) * 60000L;
		// A whole rotation ahead at most.
		for (long time = now; time <= (now + (villages * period) + 86400000L); time += 86400000L)
		{
			for (Slot slot : getSlots(time))
			{
				if ((slot.getStart() > now) && wanted.test(slot.getVillage()))
				{
					return slot;
				}
			}
		}
		return null;
	}

	/**
	 * Sets the timers of the next siege: the one going on now if enough of it is left, else the next one.
	 */
	private synchronized void scheduleNext()
	{
		cancelScheduled();
		if (!VillageSiegeConfig.ENABLED)
		{
			return;
		}

		final long now = System.currentTimeMillis();
		Slot next = null;
		for (Slot slot : getSlots(now))
		{
			if ((slot.getEnd() - Math.max(now, slot.getStart())) >= (VillageSiegeConfig.LATE_START_MIN_MINUTES * 60000L))
			{
				next = slot;
				break;
			}
		}
		if (next == null)
		{
			next = getNextSlot(null);
		}
		if (next == null)
		{
			LOGGER.warning(getClass().getSimpleName() + ": No siege to schedule (check VillageSiegeOrder).");
			return;
		}

		final Slot slot = next;
		final long warning = slot.getStart() - (VillageSiegeConfig.WARNING * 60000L);
		if ((VillageSiegeConfig.WARNING > 0) && (warning > now))
		{
			_scheduled.add(ThreadPool.schedule(() -> announce(slot.getVillage(), "In " + VillageSiegeConfig.WARNING + " minutes monsters will attack " + slot.getVillage().getName() + "! Defenders " + slot.getVillage().describeDefenderLevels() + " are called to its gates."), warning - now));
		}
		_scheduled.add(ThreadPool.schedule(() ->
		{
			final String error = startSiege(slot.getVillage(), slot.getEnd());
			if (error != null)
			{
				LOGGER.warning(getClass().getSimpleName() + ": The siege of " + slot.getVillage().getName() + " could not start: " + error + ".");
				scheduleNext();
			}
		}, Math.max(0, slot.getStart() - now)));
		LOGGER.info(getClass().getSimpleName() + ": Next siege: " + slot.getVillage().getName() + " at " + formatTime(Math.max(now, slot.getStart())) + ".");
	}

	private void cancelScheduled()
	{
		for (ScheduledFuture<?> task : _scheduled)
		{
			task.cancel(false);
		}
		_scheduled.clear();
	}

	// ---------------------------------------------------------------------------------------------
	// Start and end
	// ---------------------------------------------------------------------------------------------

	/**
	 * @param village a village
	 * @return the village's gatekeeper's place, {@code null} if it has none
	 */
	private static Location getHeart(SiegeVillage village)
	{
		final Spawn spawn = SpawnTable.getInstance().getAnySpawn(village.getGatekeeperId());
		if (spawn == null)
		{
			return null;
		}
		final Npc npc = spawn.getLastSpawn();
		final Location location = npc != null ? npc.getLocation() : new Location(spawn.getX(), spawn.getY(), spawn.getZ());
		return (location.getX() == 0) && (location.getY() == 0) ? null : location;
	}

	/**
	 * @param village a village
	 * @return its entrances (found once)
	 */
	public List<SiegeEntrance> getEntrances(SiegeVillage village)
	{
		final List<SiegeEntrance> known = _entrances.get(village.getId());
		if (known != null)
		{
			return known;
		}

		final Location heart = getHeart(village);
		final TownZone town = ZoneManager.getInstance().getZoneByName(village.getTown(), TownZone.class);
		if ((heart == null) || (town == null))
		{
			return Collections.emptyList();
		}

		// The roads of the monsters living around, whatever the siege's levels.
		final List<SiegeEntrance> entrances = SiegeEntranceFinder.find(village, heart, town, getNaturalLevel(village));
		_entrances.put(village.getId(), entrances);
		return entrances;
	}

	/**
	 * @param village a village
	 * @return the level of its siege's monsters: the middle of the levels set in the data, else the average level of the monsters living around it
	 */
	public int getLevel(SiegeVillage village)
	{
		if (village.hasLevels())
		{
			return (village.getMinLevel() + village.getMaxLevel()) / 2;
		}
		return getNaturalLevel(village);
	}

	/**
	 * @param village a village
	 * @return the lowest and highest level of its siege's monsters: the levels set in the data, else {@link VillageSiegeConfig#LEVEL_WINDOW} around the average level of the monsters living around it
	 */
	public int[] getLevelRange(SiegeVillage village)
	{
		if (village.hasLevels())
		{
			return new int[]
			{
				village.getMinLevel(),
				village.getMaxLevel()
			};
		}
		final int level = getNaturalLevel(village);
		return new int[]
		{
			Math.max(1, level - VillageSiegeConfig.LEVEL_WINDOW),
			level + VillageSiegeConfig.LEVEL_WINDOW
		};
	}

	/**
	 * @param village a village
	 * @return the average level of the monsters living around it (found once)
	 */
	private int getNaturalLevel(SiegeVillage village)
	{
		return _levels.computeIfAbsent(village.getId(), id ->
		{
			final Location heart = getHeart(village);
			return heart == null ? 1 : SiegeEntranceFinder.findLevel(heart, ZoneManager.getInstance().getZoneByName(village.getTown(), TownZone.class), village.getMaxDefenderLevel());
		});
	}

	/**
	 * Forgets the entrances and levels found, to find them again (after {@code data/VillageSiege.xml} changed).
	 */
	public void clearCache()
	{
		_entrances.clear();
		_levels.clear();
	}

	/**
	 * @param level the level of the monster a point was earned on (of the defender, for healing and the minutes of fighting)
	 * @return how many medals the point pays: the stronger the monster, the more
	 */
	public static double getMedalsPerPoint(int level)
	{
		return level * VillageSiegeConfig.MEDAL_RATE;
	}

	/**
	 * @param level the level of the monsters
	 * @return the medals an hour of fighting ({@link VillageSiegeConfig#EXPECTED_POINTS_PER_HOUR} points) pays against them
	 */
	public static long getMedalsPerHour(int level)
	{
		return Math.round(getMedalsPerPoint(level) * VillageSiegeConfig.EXPECTED_POINTS_PER_HOUR);
	}

	/**
	 * Starts the siege of a village.
	 * @param village the village
	 * @param end when it ends
	 * @return {@code null} if it started, else why not
	 */
	public synchronized String startSiege(SiegeVillage village, long end)
	{
		if (_siege != null)
		{
			return "the siege of " + _siege._village.getName() + " is going on";
		}

		final Location heart = getHeart(village);
		final TownZone town = ZoneManager.getInstance().getZoneByName(village.getTown(), TownZone.class);
		if (heart == null)
		{
			return "its gatekeeper " + village.getGatekeeperId() + " has no spawn";
		}
		if (town == null)
		{
			return "there is no TownZone named " + village.getTown();
		}

		final List<SiegeEntrance> entrances = getEntrances(village);
		if (entrances.isEmpty())
		{
			return "no entrance was found";
		}

		final int level = getLevel(village);
		final long now = System.currentTimeMillis();
		final Siege siege = new Siege(village, level, now, end, getDay(now), heart, town);
		// The monsters of the siege's levels living nearest: on the entrance's road, around the village, farther out, or anywhere.
		// A few kinds at least, so the waves aren't all the same monster.
		final int[] levels = getLevelRange(village);
		List<Integer> fallback = pickMonsters(heart, VillageSiegeConfig.SOURCE_RANGE, levels);
		if (fallback.size() < MIN_MONSTER_KINDS)
		{
			fallback = pickMonsters(heart, FAR_MONSTER_RANGE, levels);
		}
		if (fallback.size() < MIN_MONSTER_KINDS)
		{
			fallback = pickMonsters(heart, Integer.MAX_VALUE / 2, levels);
		}
		for (int i = 0; i < entrances.size(); i++)
		{
			final SiegeEntrance entrance = entrances.get(i);
			List<Integer> monsters = pickMonsters(entrance.getRally(), 3500, levels);
			if (monsters.size() < MIN_MONSTER_KINDS)
			{
				monsters = fallback;
			}
			if (monsters.isEmpty())
			{
				continue;
			}
			final EntranceState state = new EntranceState(i, entrance, monsters);
			// The vanguard comes at once, one gate after the other; the first great wave a little later.
			state._nextReinforce = now + 5000 + (i * 3000L);
			state._nextWave = now + 60000 + (i * 20000L);
			siege._entrances.add(state);
		}
		if (siege._entrances.isEmpty())
		{
			return "no monster of level " + level + " lives around it";
		}

		siege._guardTemplate = createGuardTemplate(village, level);
		siege._gateTemplate = createGateTemplate();
		siege._nextLeader = now + (VillageSiegeConfig.LEADER_INTERVAL * 60000L);
		siege._nextFake = now + 3000;
		siege._nextStatus = now + (VillageSiegeConfig.STATUS_ANNOUNCE_MINUTES * 60000L);
		_siege = siege;
		_returns.clear();

		keepActive(siege);
		hideRetailGuards(siege);
		for (EntranceState entrance : siege._entrances)
		{
			spawnStandard(siege, entrance, 100);
			for (int post = 0; post < entrance._guards.length; post++)
			{
				spawnGuard(siege, entrance, post);
			}
		}

		_tickTask = ThreadPool.scheduleAtFixedRate(this::tick, TICK_MS, TICK_MS);
		final StringBuilder gates = new StringBuilder();
		for (EntranceState entrance : siege._entrances)
		{
			gates.append(gates.length() > 0 ? ", " : "").append(entrance.getName());
		}
		announce(village, "Monsters attack " + village.getName() + "! Defend the " + gates + " until " + formatTime(end) + ". Defenders " + village.describeDefenderLevels() + " earn Defence Medals, more experience and a reward at the end" + (VillageSiegeConfig.TELEPORT ? " - type .siege to join." : "."));
		invite(siege);
		TownLifeManager.getInstance().setVillageSiege(siege);
		LOGGER.info(getClass().getSimpleName() + ": The siege of " + village.getName() + " began (level " + level + ", " + siege._entrances.size() + " entrances, until " + formatTime(end) + ").");
		return null;
	}

	/**
	 * @param where a place
	 * @param range how far around it
	 * @param levels the lowest and highest level wanted
	 * @return the monsters of those levels living there, the most common first, at most 4
	 */
	private static List<Integer> pickMonsters(Location where, int range, int[] levels)
	{
		final List<Integer> monsters = new ArrayList<>();
		for (int npcId : WorldMonsterIndex.getInstance().getNpcIdsInLevels(where.getX(), where.getY(), range, levels[0], levels[1]))
		{
			final NpcTemplate template = NpcData.getInstance().getTemplate(npcId);
			if ((template != null) && WorldMonsterIndex.isPlainMonster(template) && !template.isFlying())
			{
				monsters.add(npcId);
				if (monsters.size() >= 4)
				{
					break;
				}
			}
		}
		return monsters;
	}

	/**
	 * Ends the siege going on.
	 * @param stopped {@code true} if a GM stopped it (no end bonus)
	 */
	public synchronized void endSiege(boolean stopped)
	{
		final Siege siege = _siege;
		if (siege == null)
		{
			return;
		}

		_siege = null;
		if (_tickTask != null)
		{
			_tickTask.cancel(false);
			_tickTask = null;
		}

		try
		{
			// The end bonus, for those still fighting at the end; halved if a gate fell.
			final long now = System.currentTimeMillis();
			if (!stopped && (VillageSiegeConfig.END_BONUS_PERCENT > 0))
			{
				final double percent = siege._gateFell ? VillageSiegeConfig.END_BONUS_PERCENT / 2.0 : VillageSiegeConfig.END_BONUS_PERCENT;
				for (Contributor contributor : siege._contributors.values())
				{
					if ((contributor._lastActive >= (now - (VillageSiegeConfig.END_BONUS_MINUTES * 60000L))) && (contributor.getPoints() > 0))
					{
						final double bonus = (contributor._points * percent) / 100;
						addPoints(contributor, bonus, (contributor.getEarnedMedals() * percent) / 100);
						final Player player = contributor._fake ? null : World.getInstance().getPlayer(contributor._objectId);
						if (player != null)
						{
							player.sendMessage(siege._village.getName() + ": end of the siege bonus +" + (int) percent + "% (" + (int) bonus + " points)" + (siege._gateFell ? ", halved because a gate fell." : "."));
						}
					}
				}
			}
			payMedals(siege, true);
			if (!stopped)
			{
				rewardDefenders(siege);
			}

			// The ranking.
			final List<Contributor> ranking = siege.getRanking();
			saveResult(siege, ranking, now);
			if (!stopped)
			{
				final StringBuilder top = new StringBuilder();
				for (int i = 0; (i < ranking.size()) && (i < VillageSiegeConfig.ANNOUNCE_TOP); i++)
				{
					top.append(i > 0 ? ", " : "").append(i + 1).append(". ").append(ranking.get(i).getName()).append(" (").append(ranking.get(i).getPoints()).append(")");
				}
				final String outcome = siege._gateFell ? " - a gate fell, but the village stands." : " - victory, every gate held!";
				announce(siege._village, "The siege of " + siege._village.getName() + " is over" + outcome + " " + siege._kills + " monsters slain by " + siege.getRanking().size() + " defenders." + (top.length() > 0 ? " Best defenders: " + top + "." : ""));
				screenNear(siege, siege._gateFell ? "The village stands! The siege is over." : "Victory! Every gate held!");
			}
			else
			{
				announce(siege._village, "The siege of " + siege._village.getName() + " was called off.");
			}
		}
		catch (Exception e)
		{
			LOGGER.log(Level.WARNING, getClass().getSimpleName() + ": Could not end the siege of " + siege._village.getName() + ".", e);
		}
		finally
		{
			cleanUp(siege);
			TownLifeManager.getInstance().setVillageSiege(null);
			_returnUntil = System.currentTimeMillis() + RETURN_TIME;
			LOGGER.info(getClass().getSimpleName() + ": The siege of " + siege._village.getName() + " ended (" + siege._kills + " monsters slain, " + siege._contributors.size() + " defenders" + (siege._gateFell ? ", a gate fell" : "") + ").");
			if (!stopped)
			{
				scheduleNext();
			}
		}
	}

	/**
	 * @param level a defender's level
	 * @return the share of a level (%) the end of a siege gives a defender of that level for {@link VillageSiegeConfig#REWARD_FULL_MINUTES} minutes of fighting: {@link VillageSiegeConfig#REWARD_EXP_PERCENT} up to {@link VillageSiegeConfig#REWARD_EXP_FULL_LEVEL}, halved
	 *         every {@link VillageSiegeConfig#REWARD_EXP_HALVING_LEVELS} levels above it (a level takes far more fighting up there: with the defaults the reward stays worth about as many kills of monsters of the defender's level from level 40 to 75)
	 */
	public static double getRewardExpPercent(int level)
	{
		if ((VillageSiegeConfig.REWARD_EXP_HALVING_LEVELS <= 0) || (level <= VillageSiegeConfig.REWARD_EXP_FULL_LEVEL))
		{
			return VillageSiegeConfig.REWARD_EXP_PERCENT;
		}
		return VillageSiegeConfig.REWARD_EXP_PERCENT * Math.pow(0.5, (level - VillageSiegeConfig.REWARD_EXP_FULL_LEVEL) / (double) VillageSiegeConfig.REWARD_EXP_HALVING_LEVELS);
	}

	/**
	 * @param percent a share of a level
	 * @return it for a page: "30", "7.5", "1.9"
	 */
	public static String formatPercent(double percent)
	{
		final double rounded = Math.round(percent * 10) / 10.0;
		return rounded == Math.rint(rounded) ? String.valueOf((long) rounded) : String.valueOf(rounded);
	}

	/**
	 * The defenders who fought long enough get a share of a level in experience: the whole of {@link #getRewardExpPercent} for {@link VillageSiegeConfig#REWARD_FULL_MINUTES} minutes of fighting, less for each time a gate fell (never less than half).
	 * @param siege the siege
	 */
	private static void rewardDefenders(Siege siege)
	{
		if (VillageSiegeConfig.REWARD_EXP_PERCENT <= 0)
		{
			return;
		}

		int falls = 0;
		for (EntranceState entrance : siege._entrances)
		{
			falls += entrance._falls;
		}
		final double outcome = Math.max(0.5, 1 - ((0.5 * falls) / Math.max(1, siege._entrances.size())));
		for (Contributor contributor : siege._contributors.values())
		{
			if (contributor._fake || (contributor._blocked != null) || (contributor.getActiveMinutes() < Math.max(1, VillageSiegeConfig.REWARD_MIN_MINUTES)))
			{
				continue;
			}

			final Player player = World.getInstance().getPlayer(contributor._objectId);
			if ((player == null) || !player.isOnline() || !siege._village.isDefenderLevel(player.getLevel()))
			{
				continue;
			}

			final int level = player.getLevel();
			final long span = ExperienceData.getInstance().getExpForLevel(level + 1) - ExperienceData.getInstance().getExpForLevel(level);
			final double share = Math.min(1, contributor.getActiveMinutes() / (double) VillageSiegeConfig.REWARD_FULL_MINUTES);
			final long exp = (long) ((((span * getRewardExpPercent(level)) / 100.0) * share) * outcome);
			if (exp <= 0)
			{
				continue;
			}
			player.addExpAndSp(exp, exp / 10);
			player.sendMessage(siege._village.getName() + ": the Defence Force thanks you for " + contributor.getActiveMinutes() + " minutes of fighting - " + exp + " experience" + (outcome < 1 ? " (less, as a gate fell)." : "."));
		}
	}

	/**
	 * Takes away what the siege brought and gives back what it took.
	 * @param siege the siege
	 */
	private static void cleanUp(Siege siege)
	{
		for (SiegeMonster monster : siege._monsters.values())
		{
			monster._npc.deleteMe();
		}
		siege._monsters.clear();

		for (EntranceState entrance : siege._entrances)
		{
			if (entrance._standard != null)
			{
				entrance._standard.deleteMe();
			}
			for (Npc guard : entrance._guards)
			{
				if (guard != null)
				{
					guard.deleteMe();
				}
			}
		}

		// The fake defenders go home in a while.
		final long now = System.currentTimeMillis();
		for (Npc fake : siege._fakes)
		{
			final FakePlayerPvpProfile profile = fake.getTemplate().getFakePlayerPvpProfile();
			if ((profile != null) && fake.isSpawned() && !fake.isDead())
			{
				profile.setLeaveTime(Math.min(profile.getLeaveTime() > 0 ? profile.getLeaveTime() : Long.MAX_VALUE, now + Rnd.get(10000, 60000)));
			}
		}

		for (Spawn spawn : siege._hiddenGuards)
		{
			spawn.startRespawn();
			if ((spawn.getLastSpawn() == null) || !spawn.getLastSpawn().isSpawned())
			{
				spawn.doSpawn();
			}
		}
		siege._hiddenGuards.clear();

		for (WorldRegion region : siege._keptRegions)
		{
			region.releaseKeepActive();
		}
		siege._keptRegions.clear();
	}

	/**
	 * Keeps the regions around the village awake, so the fighting goes on with nobody watching.
	 * @param siege the siege
	 */
	private static void keepActive(Siege siege)
	{
		final List<Location> points = new ArrayList<>();
		points.add(siege._heart);
		for (EntranceState entrance : siege._entrances)
		{
			points.add(entrance._entrance.getGate());
			points.add(entrance._entrance.getRally());
		}

		final Set<WorldRegion> regions = new HashSet<>();
		final int step = 1024;
		for (Location point : points)
		{
			for (int dx = -KEEP_ACTIVE_RANGE; dx <= KEEP_ACTIVE_RANGE; dx += step)
			{
				for (int dy = -KEEP_ACTIVE_RANGE; dy <= KEEP_ACTIVE_RANGE; dy += step)
				{
					for (int dz = -World.Z_REGION_SIZE; dz <= World.Z_REGION_SIZE; dz += World.Z_REGION_SIZE)
					{
						final WorldRegion region = World.getInstance().getRegion(point.getX() + dx, point.getY() + dy, Math.max(World.WORLD_Z_MIN, Math.min(World.WORLD_Z_MAX - 1, point.getZ() + dz)));
						if (region != null)
						{
							regions.add(region);
						}
					}
				}
			}
		}

		for (WorldRegion region : regions)
		{
			// Leave alone what is kept awake for something else (a PvP spot).
			if (!region.isKeptActive())
			{
				region.keepActive();
				siege._keptRegions.add(region);
			}
		}
	}

	/**
	 * Hides the village's own guards (and those on the roads around): they would end every wave at once.
	 * @param siege the siege
	 */
	private static void hideRetailGuards(Siege siege)
	{
		double reach = 0;
		for (EntranceState entrance : siege._entrances)
		{
			reach = Math.max(reach, LocationUtil.calculateDistance(siege._heart, entrance._entrance.getRally(), false, false));
		}
		reach += VillageSiegeConfig.RETAIL_GUARD_RANGE;
		final double reachSq = reach * reach;

		for (Set<Spawn> spawns : SpawnTable.getInstance().getSpawnTable().values())
		{
			for (Spawn spawn : spawns)
			{
				if ((spawn.getInstanceId() != 0) || !spawn.getTemplate().isType("Guard"))
				{
					continue;
				}
				final Npc npc = spawn.getLastSpawn();
				final Location location = npc != null ? npc.getLocation() : new Location(spawn.getX(), spawn.getY(), spawn.getZ());
				final double dx = location.getX() - siege._heart.getX();
				final double dy = location.getY() - siege._heart.getY();
				if (((dx * dx) + (dy * dy)) > reachSq)
				{
					continue;
				}

				spawn.stopRespawn();
				if (npc != null)
				{
					npc.deleteMe();
				}
				siege._hiddenGuards.add(spawn);
			}
		}
	}

	/**
	 * Makes the template of a village's siege guards: its guards' look, {@link VillageSiegeConfig#GUARD_LEVEL_OFFSET} levels above the siege, as strong as the median monster of that level times the guard multipliers.
	 * @param village the village
	 * @param siegeLevel the level of the siege
	 * @return the template
	 */
	private static NpcTemplate createGuardTemplate(SiegeVillage village, int siegeLevel)
	{
		final int level = Math.max(1, Math.min(85, siegeLevel + VillageSiegeConfig.GUARD_LEVEL_OFFSET));
		final List<Double> hp = new ArrayList<>();
		final List<Double> pAtk = new ArrayList<>();
		final List<Double> mAtk = new ArrayList<>();
		final List<Double> pDef = new ArrayList<>();
		final List<Double> mDef = new ArrayList<>();
		for (NpcTemplate monster : NpcData.getInstance().getAllMonstersOfLevel(level))
		{
			if (WorldMonsterIndex.isPlainMonster(monster) && (monster.getBaseHpMax() > 0))
			{
				hp.add((double) monster.getBaseHpMax());
				pAtk.add((double) monster.getBasePAtk());
				mAtk.add((double) monster.getBaseMAtk());
				pDef.add((double) monster.getBasePDef());
				mDef.add((double) monster.getBaseMDef());
			}
		}

		final NpcTemplate look = NpcData.getInstance().getTemplate(village.getGuardLookId());
		final StatSet set = new StatSet(new HashMap<>());
		set.set("id", GUARD_NPC_ID + Math.max(0, new ArrayList<>(VillageSiegeData.getInstance().getVillages()).indexOf(village)));
		set.set("displayId", village.getGuardLookId());
		set.set("level", level);
		set.set("type", "Guard");
		set.set("name", "Village Guard");
		set.set("usingServerSideName", true);
		set.set("title", "Defence Force");
		set.set("usingServerSideTitle", true);
		set.set("race", look != null ? look.getRace().name() : "HUMAN");
		set.set("sex", look != null ? look.getSex().name() : "MALE");
		set.set("rhandId", look != null ? look.getRHandId() : 0);
		set.set("lhandId", look != null ? look.getLHandId() : 0);
		set.set("baseSTR", 40);
		set.set("baseCON", 43);
		set.set("baseDEX", 30);
		set.set("baseINT", 21);
		set.set("baseWIT", 20);
		set.set("baseMEN", 20);
		set.set("baseHpMax", median(hp, 1000) * VillageSiegeConfig.GUARD_HP_MULTIPLIER);
		set.set("baseMpMax", 1000);
		set.set("baseHpReg", 8.5);
		set.set("baseMpReg", 3);
		set.set("basePAtk", (int) (median(pAtk, 100) * VillageSiegeConfig.GUARD_ATTACK_MULTIPLIER));
		set.set("baseMAtk", (int) (median(mAtk, 70) * VillageSiegeConfig.GUARD_ATTACK_MULTIPLIER));
		set.set("basePDef", (int) (median(pDef, 100) * VillageSiegeConfig.GUARD_DEFENCE_MULTIPLIER));
		set.set("baseMDef", (int) (median(mDef, 70) * VillageSiegeConfig.GUARD_DEFENCE_MULTIPLIER));
		set.set("basePAtkSpd", 253);
		set.set("baseMAtkSpd", 333);
		set.set("baseCritRate", 4);
		set.set("baseRndDam", 30);
		set.set("baseAtkType", "SWORD");
		set.set("baseAtkRange", 40);
		set.set("baseShldDef", 148);
		set.set("baseShldRate", 20);
		set.set("baseRunSpd", 160);
		set.set("baseWalkSpd", 50);
		set.set("collisionRadius", look != null ? look.getCollisionRadius() : 8);
		set.set("collisionHeight", look != null ? look.getCollisionHeight() : 24);
		set.set("attackable", false);
		set.set("targetable", true);
		set.set("talkable", false);
		set.set("undying", false);
		set.set("randomWalk", false);
		set.set("randomAnimation", false);
		set.set("aiType", "FIGHTER");
		set.set("aggroRange", VillageSiegeConfig.GUARD_AGGRO_RANGE);
		set.set("clanHelpRange", 300);
		set.set("isAggressive", false);
		set.set("corpseTime", 7);
		final NpcTemplate template = new NpcTemplate(set);
		template.setParameters(look != null ? look.getParameters() : new StatSet());
		template.setClans(null);
		template.setIgnoreClanNpcIds(null);
		template.setAISkillLists(null);
		template.setSkills(new HashMap<>());
		return template;
	}

	private static double median(List<Double> values, double fallback)
	{
		if (values.isEmpty())
		{
			return fallback;
		}
		Collections.sort(values);
		return values.get(values.size() / 2);
	}

	/**
	 * Makes the template of the gates: {@link VillageSiegeConfig#GATE_HP} HP, mended by the siege only.
	 * @return the template
	 */
	private static NpcTemplate createGateTemplate()
	{
		final StatSet set = new StatSet(new HashMap<>());
		set.set("id", STANDARD_NPC_ID);
		set.set("displayId", 35423); // A siege headquarters flag.
		set.set("level", 70);
		set.set("type", "VillageGate");
		set.set("name", "Village Gate");
		set.set("usingServerSideName", true);
		set.set("title", "Gate");
		set.set("usingServerSideTitle", true);
		set.set("race", "ETC");
		set.set("sex", "ETC");
		set.set("baseSTR", 40);
		set.set("baseCON", 43);
		set.set("baseDEX", 30);
		set.set("baseINT", 21);
		set.set("baseWIT", 20);
		set.set("baseMEN", 20);
		set.set("baseHpMax", VillageSiegeConfig.GATE_HP);
		set.set("baseMpMax", 100);
		set.set("baseHpReg", 0);
		set.set("baseMpReg", 0);
		set.set("basePAtk", 0);
		set.set("baseMAtk", 0);
		set.set("basePDef", 500);
		set.set("baseMDef", 500);
		set.set("baseRunSpd", 1);
		set.set("baseWalkSpd", 1);
		set.set("collisionRadius", 30);
		set.set("collisionHeight", 40);
		set.set("attackable", false);
		set.set("targetable", true);
		set.set("talkable", false);
		set.set("undying", true);
		set.set("randomWalk", false);
		set.set("randomAnimation", false);
		set.set("aggroRange", 0);
		set.set("isAggressive", false);
		final NpcTemplate template = new NpcTemplate(set);
		template.setParameters(new StatSet());
		template.setClans(null);
		template.setIgnoreClanNpcIds(null);
		template.setAISkillLists(null);
		template.setSkills(new HashMap<>());
		return template;
	}

	/**
	 * Sets up the gate of an entrance, at the HP given.
	 * @param siege the siege
	 * @param entrance the entrance
	 * @param percent its HP, in %
	 */
	private static void spawnStandard(Siege siege, EntranceState entrance, int percent)
	{
		final Location gate = entrance._entrance.getGate();
		try
		{
			final Spawn spawn = new Spawn(siege._gateTemplate);
			spawn.setXYZ(gate.getX(), gate.getY(), gate.getZ());
			spawn.setHeading(LocationUtil.calculateHeadingFrom(siege._heart, gate));
			spawn.setAmount(1);
			spawn.setRespawnDelay(0);
			spawn.stopRespawn();
			final Npc standard = spawn.doSpawn(false);
			if (standard != null)
			{
				standard.setRandomWalking(false);
				standard.setCurrentHp((standard.getMaxHp() * percent) / 100.0);
				entrance._shownPercent = -1;
				entrance._alarm = 100;
				for (int alarm : GATE_ALARMS)
				{
					if (percent <= alarm)
					{
						entrance._alarm = alarm;
					}
				}
				entrance._standard = standard;
				updateGateTitle(entrance);
			}
		}
		catch (Exception e)
		{
			LOGGER.log(Level.WARNING, VillageSiegeManager.class.getSimpleName() + ": Could not set up a gate of " + siege._village.getName() + ".", e);
		}
	}

	/**
	 * The gate's title shows what is left of it.
	 * @param entrance the entrance
	 */
	private static void updateGateTitle(EntranceState entrance)
	{
		final Npc standard = entrance._standard;
		final int percent = entrance.getMeterPercent();
		if ((standard != null) && (percent != entrance._shownPercent))
		{
			entrance._shownPercent = percent;
			standard.setTitle(entrance.getName() + " " + percent + "%");
			standard.broadcastInfo();
		}
	}

	private static void spawnGuard(Siege siege, EntranceState entrance, int post)
	{
		final NpcTemplate template = siege._guardTemplate;
		final Location location = entrance._entrance.getGuardPosts().get(post);
		try
		{
			final Spawn spawn = new Spawn(template);
			spawn.setXYZ(location.getX(), location.getY(), location.getZ());
			spawn.setHeading(LocationUtil.calculateHeadingFrom(location, entrance._entrance.getGate()));
			spawn.setAmount(1);
			spawn.setRespawnDelay(0);
			spawn.stopRespawn();
			final Npc guard = spawn.doSpawn(false);
			if (guard != null)
			{
				guard.asAttackable().setVillageSiegeRole(Attackable.VILLAGE_SIEGE_GUARD);
				guard.setRandomWalking(false);
				guard.setCurrentHpMp(guard.getMaxHp(), guard.getMaxMp());
				entrance._guards[post] = guard;
			}
		}
		catch (Exception e)
		{
			LOGGER.log(Level.WARNING, VillageSiegeManager.class.getSimpleName() + ": Could not spawn a guard of " + siege._village.getName() + ".", e);
		}
	}

	// ---------------------------------------------------------------------------------------------
	// The fight
	// ---------------------------------------------------------------------------------------------

	private void tick()
	{
		final Siege siege = _siege;
		if (siege == null)
		{
			return;
		}

		try
		{
			final long now = System.currentTimeMillis();
			if (now >= siege._end)
			{
				ThreadPool.execute(() -> endSiege(false));
				return;
			}

			siege._ticks++;
			tickStage(siege, now);
			for (EntranceState entrance : siege._entrances)
			{
				tickGate(siege, entrance, now);
				if (now >= entrance._nextReinforce)
				{
					reinforce(siege, entrance);
					entrance._nextReinforce = now + (VillageSiegeConfig.REINFORCE_INTERVAL * 1000L);
				}
				if (now >= entrance._nextWave)
				{
					spawnWave(siege, entrance, now);
					// The great waves come more often as the siege goes on.
					final double quicker = 1 - (0.15 * siege._stage);
					entrance._nextWave = now + (long) (Rnd.get(VillageSiegeConfig.WAVE_INTERVAL_MIN, VillageSiegeConfig.WAVE_INTERVAL_MAX) * 1000L * quicker);
				}
				tickGuards(siege, entrance, now);
			}

			// The Siege Lord, near the end, at the busiest gate.
			if (!siege._lordSent && (VillageSiegeConfig.LORD_MINUTES > 0) && ((siege._end - now) <= (VillageSiegeConfig.LORD_MINUTES * 60000L)))
			{
				siege._lordSent = true;
				final EntranceState busiest = getBusiestEntrance(siege);
				final Npc lord = spawnMonster(siege, busiest, KIND_LORD);
				if (lord != null)
				{
					announce(siege._village, "The Siege Lord comes for the " + busiest.getName() + " of " + siege._village.getName() + "!");
					screenNear(siege, "The Siege Lord comes for the " + busiest.getName() + "!");
				}
			}

			if ((siege._ticks % 2) == 0)
			{
				direct(siege, now);
			}
			if ((siege._ticks % 5) == 0)
			{
				tickFakes(siege, now);
			}
			if ((siege._ticks % 30) == 0)
			{
				payMedals(siege, false);
			}
			if ((siege._ticks % 60) == 0)
			{
				payActiveMinute(siege, now);
			}
			if ((VillageSiegeConfig.STATUS_ANNOUNCE_MINUTES > 0) && (now >= siege._nextStatus))
			{
				siege._nextStatus = now + (VillageSiegeConfig.STATUS_ANNOUNCE_MINUTES * 60000L);
				announce(siege._village, siege._village.getName() + " holds: " + describeGates(siege) + ". " + siege._kills + " monsters slain, " + siege.getPlayersPresent() + " defenders at the gates, " + ((siege._end - now) / 60000) + " minutes to go." + (VillageSiegeConfig.TELEPORT ? " Join with .siege!" : ""));
			}
			if ((siege._ticks % STATUS_LOG_TICKS) == 0)
			{
				LOGGER.info(getClass().getSimpleName() + ": " + siege._village.getName() + " (" + siege.getStageName() + "): " + siege._kills + " slain, " + siege._monsters.size() + " attacking, " + siege.getFakeDefenders() + " fake defenders, " + siege._contributors.size() + " defenders so far; " + describeGates(siege) + ".");
			}
		}
		catch (Exception e)
		{
			LOGGER.log(Level.WARNING, getClass().getSimpleName() + ": Siege tick failed.", e);
		}
	}

	/**
	 * @param siege the siege
	 * @return its gates and how they stand ("North Gate 80%, East Gate fallen")
	 */
	private static String describeGates(Siege siege)
	{
		final StringBuilder gates = new StringBuilder();
		for (EntranceState entrance : siege._entrances)
		{
			gates.append(gates.length() > 0 ? ", " : "").append(entrance.getName()).append(' ').append(entrance._fallen ? "fallen" : entrance.getMeterPercent() + "%");
		}
		return gates.toString();
	}

	/**
	 * The siege goes from one stage to the next (Vanguard, Main Assault, Final Assault): more monsters on each gate, and the great waves come more often.
	 * @param siege the siege
	 * @param now the time
	 */
	private static void tickStage(Siege siege, long now)
	{
		final double done = (now - siege._start) / (double) Math.max(1, siege._end - siege._start);
		int stage = 0;
		for (int i = 0; i < STAGE_STARTS.length; i++)
		{
			if (done >= STAGE_STARTS[i])
			{
				stage = i;
			}
		}
		if (stage <= siege._stage)
		{
			return;
		}

		siege._stage = stage;
		if (stage == (STAGE_NAMES.length - 1))
		{
			announce(siege._village, "The Final Assault on " + siege._village.getName() + " begins! The monsters throw everything they have at its gates. Defenders, to the walls!");
		}
		else
		{
			announce(siege._village, "The " + STAGE_NAMES[stage] + " on " + siege._village.getName() + " begins! More monsters march on its gates (" + describeGates(siege) + "). Defenders, hold the line!");
		}
		screenNear(siege, STAGE_NAMES[stage] + "!");
	}

	/**
	 * The monsters at a gate beat on it (see {@link VillageGate}); left alone for a while, it is mended a little. At 0 HP it falls, and a fallen gate is raised again after a while if no monster is at it.
	 * @param siege the siege
	 * @param entrance the entrance
	 * @param now the time
	 */
	private void tickGate(Siege siege, EntranceState entrance, long now)
	{
		final Npc standard = entrance._standard;
		if (entrance._fallen)
		{
			// Raised again, when no monster stands in the way.
			final long rebuild = entrance.getRebuildTime();
			if ((rebuild > 0) && (now >= rebuild) && (countMonstersNear(siege, entrance._entrance.getGate(), GATE_ATTACK_RANGE) == 0))
			{
				entrance._fallen = false;
				spawnStandard(siege, entrance, VillageSiegeConfig.GATE_REBUILD_PERCENT);
				announceNear(siege, "The defenders raise the " + entrance.getName() + " of " + siege._village.getName() + " again!");
			}
			return;
		}
		if (standard == null)
		{
			return;
		}

		if (standard.getCurrentHp() < 1)
		{
			entrance._fallen = true;
			entrance._fallenAt = now;
			entrance._falls++;
			siege._gateFell = true;
			entrance._standard = null;
			for (SiegeMonster monster : siege._monsters.values())
			{
				monster._npc.asAttackable().stopHating(standard);
			}
			standard.setTitle(entrance.getName() + " - fallen");
			standard.broadcastPacket(new MagicSkillUse(standard, standard, 5130, 1, 500, 0)); // A blast.
			ThreadPool.schedule(standard::deleteMe, 1500);
			announce(siege._village, "The " + entrance.getName() + " of " + siege._village.getName() + " has fallen! Monsters pour into the streets!");
			screenNear(siege, "The " + entrance.getName() + " has fallen!");
			return;
		}

		// Left alone for a while: mended a little.
		if (((now - entrance._lastHit) >= GATE_REPAIR_DELAY) && (standard.getCurrentHp() < standard.getMaxHp()) && (VillageSiegeConfig.GATE_REPAIR > 0))
		{
			standard.setCurrentHp(Math.min(standard.getMaxHp(), standard.getCurrentHp() + VillageSiegeConfig.GATE_REPAIR));
		}

		// The defenders are told when it gets weak.
		final int percent = entrance.getMeterPercent();
		for (int alarm : GATE_ALARMS)
		{
			if ((percent <= alarm) && (alarm < entrance._alarm))
			{
				entrance._alarm = alarm;
				announceNear(siege, "The " + entrance.getName() + " of " + siege._village.getName() + " is down to " + percent + "%" + (alarm <= 25 ? " - it is about to fall!" : "!"));
				break;
			}
		}
		// Mended well above the last warning: that warning is given again if it comes down to it.
		if ((entrance._alarm < 100) && (percent >= (entrance._alarm + 15)))
		{
			int above = 100;
			for (int alarm : GATE_ALARMS)
			{
				if (alarm > entrance._alarm)
				{
					above = alarm;
				}
			}
			entrance._alarm = above;
		}
		updateGateTitle(entrance);
	}

	/**
	 * Called from {@link VillageGate#reduceCurrentHp} when a monster of the siege hits a gate.
	 * @param gate the gate
	 * @param attacker the monster
	 * @return the HP the hit takes off: {@link VillageSiegeConfig#GATE_HIT_DAMAGE}, more for a champion, a Warband Leader or the Siege Lord
	 */
	public double onGateHit(Npc gate, Creature attacker)
	{
		final Siege siege = _siege;
		if (siege == null)
		{
			return 0;
		}

		for (EntranceState entrance : siege._entrances)
		{
			if (entrance._standard == gate)
			{
				entrance._lastHit = System.currentTimeMillis();
				final SiegeMonster monster = siege._monsters.get(attacker.getObjectId());
				if (monster != null)
				{
					monster._lastMet = entrance._lastHit;
				}
				return VillageSiegeConfig.GATE_HIT_DAMAGE * (1 + attacker.getChampionTier());
			}
		}
		return 0;
	}

	/**
	 * @param creature a creature
	 * @return {@code true} if it is one of the monsters of a village siege
	 */
	public static boolean isSiegeMonster(Creature creature)
	{
		return (creature != null) && creature.isAttackable() && (creature.asAttackable().getVillageSiegeRole() == Attackable.VILLAGE_SIEGE_MONSTER);
	}

	/**
	 * @param siege the siege
	 * @param entrance an entrance
	 * @return how many monsters the entrance should have on it now: by the stage, and more with more defenders there
	 */
	private static int getPressure(Siege siege, EntranceState entrance)
	{
		final int defenders = countDefenders(siege, entrance._entrance.getGate(), VillageSiegeConfig.AREA_RANGE / 2);
		return Math.min(VillageSiegeConfig.PRESSURE_MAX, VillageSiegeConfig.PRESSURE[siege._stage] + (int) (defenders * VillageSiegeConfig.PRESSURE_PER_DEFENDER));
	}

	/**
	 * Keeps an entrance under pressure: while fewer monsters than {@link #getPressure} are on it, a group more comes up the road.
	 * @param siege the siege
	 * @param entrance the entrance
	 */
	private void reinforce(Siege siege, EntranceState entrance)
	{
		int on = 0;
		for (SiegeMonster monster : siege._monsters.values())
		{
			if ((monster._entrance == entrance) && !monster._npc.isDead())
			{
				on++;
			}
		}

		final int missing = Math.min(getPressure(siege, entrance) - on, VillageSiegeConfig.MAX_MONSTERS - siege._monsters.size());
		final int group = Math.min(missing, Rnd.get(REINFORCE_GROUP_MIN, REINFORCE_GROUP_MAX));
		for (int i = 0; i < group; i++)
		{
			spawnMonster(siege, entrance, KIND_WAVE);
		}
	}

	/**
	 * A great wave forms out on the road, on top of the pressure.
	 * @param siege the siege
	 * @param entrance the entrance
	 * @param now the time
	 */
	private void spawnWave(Siege siege, EntranceState entrance, long now)
	{
		final int room = VillageSiegeConfig.MAX_MONSTERS - siege._monsters.size();
		if (room <= 0)
		{
			return;
		}

		final int defenders = countDefenders(siege, entrance._entrance.getGate(), VillageSiegeConfig.AREA_RANGE);
		final int bonus = VillageSiegeConfig.WAVE_SIZE_PER_DEFENDERS > 0 ? defenders / VillageSiegeConfig.WAVE_SIZE_PER_DEFENDERS : 0;
		final int size = Math.min(room, Math.min(VillageSiegeConfig.WAVE_SIZE_MAX, VillageSiegeConfig.WAVE_SIZE + ((VillageSiegeConfig.WAVE_SIZE * siege._stage) / 2) + bonus));
		for (int i = 0; i < size; i++)
		{
			spawnMonster(siege, entrance, KIND_WAVE);
		}
		siege._waves++;

		// Now and then a Warband Leader marches with the wave.
		if ((VillageSiegeConfig.LEADER_INTERVAL > 0) && (now >= siege._nextLeader) && (siege._monsters.size() < VillageSiegeConfig.MAX_MONSTERS))
		{
			siege._nextLeader = now + (VillageSiegeConfig.LEADER_INTERVAL * 60000L);
			if (spawnMonster(siege, entrance, KIND_LEADER) != null)
			{
				announceNear(siege, "A Warband Leader marches on the " + entrance.getName() + " of " + siege._village.getName() + " with " + size + " monsters!");
				return;
			}
		}
		announceNear(siege, "A great wave of " + size + " monsters marches on the " + entrance.getName() + "!");
	}

	/**
	 * @param siege the siege
	 * @param entrance the entrance it attacks
	 * @param kind {@link #KIND_WAVE}, {@link #KIND_LEADER} or {@link #KIND_LORD}
	 * @return the monster, {@code null} if it could not be spawned
	 */
	private Npc spawnMonster(Siege siege, EntranceState entrance, int kind)
	{
		final int npcId = pickMonster(siege, entrance, kind);
		final Location location = QuestSpawnUtil.findPoint(entrance._entrance.getRally(), 20, 260, true, 0);
		final Npc npc = QuestSpawnUtil.spawnTaskNpc(npcId, location, LocationUtil.calculateHeadingFrom(location, entrance._entrance.getGate()), 0, null);
		if (npc == null)
		{
			return null;
		}

		final int tier;
		final String title;
		switch (kind)
		{
			case KIND_LORD:
			{
				tier = 3;
				title = "Siege Lord";
				break;
			}
			case KIND_LEADER:
			{
				tier = 2;
				title = "Warband Leader";
				break;
			}
			default:
			{
				tier = Rnd.get(100) < (VillageSiegeConfig.CHAMPION_CHANCE * (1 + siege._stage)) ? 1 : 0;
				title = "Besieger";
				break;
			}
		}
		npc.asAttackable().setVillageSiegeRole(Attackable.VILLAGE_SIEGE_MONSTER);
		QuestSpawnUtil.applyTier(npc, tier, title);
		npc.setRunning();
		final SiegeMonster monster = new SiegeMonster(npc, entrance, kind);
		siege._monsters.put(npc.getObjectId(), monster);
		npc.addListener(new ConsumerEventListener(npc, EventType.ON_CREATURE_DEATH, (OnCreatureDeath event) -> onSiegeMonsterDeath(npc), this));
		march(siege, monster);
		return npc;
	}

	/**
	 * The kind of a monster to send. Most are the village's own; but for every defender at the entrance above the village's levels, as many come at that defender's level ({@link VillageSiegeConfig#SCALE_TO_DEFENDERS}), so nobody outgrows the siege.
	 * @param siege the siege
	 * @param entrance the entrance
	 * @param kind {@link #KIND_WAVE}, {@link #KIND_LEADER} or {@link #KIND_LORD}
	 * @return the npc id
	 */
	private int pickMonster(Siege siege, EntranceState entrance, int kind)
	{
		final List<Integer> own = entrance._monsterIds;
		if (VillageSiegeConfig.SCALE_TO_DEFENDERS)
		{
			final int top = getLevelRange(siege._village)[1];
			final List<Integer> levels = new ArrayList<>();
			int others = 0;
			for (Player player : World.getInstance().getPlayers())
			{
				if (!player.isOnline() || player.isInOfflineMode() || player.isGM() || player.isDead() || (player.getInstanceId() != 0) || !siege._village.isDefenderLevel(player.getLevel()) || (player.calculateDistance2D(entrance._entrance.getGate()) > VillageSiegeConfig.AREA_RANGE))
				{
					continue;
				}
				if (player.getLevel() > (top + VillageSiegeConfig.SCALE_LEVELS_BELOW))
				{
					levels.add(player.getLevel());
				}
				else
				{
					others++;
				}
			}

			// A leader or the Lord comes at the level of the strongest of them; a monster of the wave at the level of one of them, as often as they are among the defenders.
			if (!levels.isEmpty())
			{
				Collections.sort(levels);
				final int level;
				if (kind != KIND_WAVE)
				{
					level = levels.get(levels.size() - 1);
				}
				else if (Rnd.get(levels.size() + others) < levels.size())
				{
					level = levels.get(Rnd.get(levels.size()));
				}
				else
				{
					level = 0;
				}
				if (level > 0)
				{
					final List<Integer> scaled = siege._scaledMonsters.computeIfAbsent(level, l -> findScaledMonsters(siege, l));
					if (!scaled.isEmpty())
					{
						return scaled.get(kind == KIND_WAVE ? Rnd.get(scaled.size()) : 0);
					}
				}
			}
		}
		return own.get(kind == KIND_WAVE ? Rnd.get(own.size()) : 0);
	}

	/**
	 * @param siege the siege
	 * @param level a defender's level
	 * @return the monsters around that level living nearest the village
	 */
	private static List<Integer> findScaledMonsters(Siege siege, int level)
	{
		final int[] levels =
		{
			Math.max(1, level - VillageSiegeConfig.SCALE_LEVELS_BELOW),
			level + VillageSiegeConfig.SCALE_LEVELS_ABOVE
		};
		List<Integer> monsters = pickMonsters(siege._heart, FAR_MONSTER_RANGE, levels);
		if (monsters.size() < MIN_MONSTER_KINDS)
		{
			monsters = pickMonsters(siege._heart, Integer.MAX_VALUE / 2, levels);
		}
		return monsters;
	}

	/**
	 * Every monster without a fight goes for the nearest defender (the guards included); with nobody left in front of it, it beats on its gate, else it marches on it (or, past a fallen gate, on the guards and the heart of the village). Monsters left alone too long are
	 * taken away.
	 * @param siege the siege
	 * @param now the time
	 */
	private void direct(Siege siege, long now)
	{
		for (SiegeMonster monster : siege._monsters.values())
		{
			final Npc npc = monster._npc;
			if (npc.isDead() || !npc.isSpawned())
			{
				if (!npc.isSpawned())
				{
					siege._monsters.remove(npc.getObjectId());
				}
				continue;
			}

			final Attackable attackable = npc.asAttackable();
			final Npc gate = monster._entrance._standard;
			Creature target = attackable.getMostHated();
			if ((target != null) && !target.isSpawned())
			{
				// A fallen gate (or someone gone): forgotten.
				attackable.stopHating(target);
				target = attackable.getMostHated();
			}
			if ((target != null) && !target.isDead() && target.isSpawned() && (npc.getAI().getIntention() == Intention.ATTACK) && (npc.calculateDistance2D(target) < 1500))
			{
				monster._lastMet = now;
				// Beating on the gate: now and then it turns on a defender who came up to it.
				if ((target == gate) && Rnd.nextBoolean())
				{
					final Creature defender = findDefenderNear(npc, GATE_TURN_RANGE);
					if (defender != null)
					{
						attackable.stopHating(gate);
						QuestSpawnUtil.attack(npc, defender);
					}
				}
				continue;
			}

			final Creature defender = findDefenderNear(npc, MONSTER_SIGHT);
			if (defender != null)
			{
				monster._lastMet = now;
				QuestSpawnUtil.attack(npc, defender);
				continue;
			}

			// Nobody left in front of it: the gate.
			if ((gate != null) && !monster._entrance._fallen && gate.isSpawned() && (npc.calculateDistance2D(gate) <= GATE_ATTACK_RANGE))
			{
				monster._lastMet = now;
				QuestSpawnUtil.attack(npc, gate);
				continue;
			}

			// Too long with nobody to fight (a monster stuck on the road): gone.
			if ((now - monster._lastMet) > MONSTER_LIFETIME)
			{
				siege._monsters.remove(npc.getObjectId());
				npc.deleteMe();
				continue;
			}

			march(siege, monster);
		}
	}

	/**
	 * Sends a monster on: to its gate, or past a fallen gate to the guards and the heart of the village.
	 * @param siege the siege
	 * @param monster the monster
	 */
	private static void march(Siege siege, SiegeMonster monster)
	{
		final Npc npc = monster._npc;
		final EntranceState entrance = monster._entrance;
		final Location goal;
		if (!entrance._fallen)
		{
			goal = entrance._entrance.getGate();
		}
		else
		{
			boolean guards = false;
			for (Npc guard : entrance._guards)
			{
				if ((guard != null) && !guard.isDead())
				{
					guards = true;
					break;
				}
			}
			goal = guards ? entrance.getGuardCentre() : siege._heart;
		}

		if (npc.calculateDistance2D(goal) <= 120)
		{
			return;
		}
		if (npc.isMoving() && (npc.getAI().getIntention() == Intention.MOVE_TO))
		{
			return;
		}

		final Location spot = new Location(goal.getX() + Rnd.get(-60, 60), goal.getY() + Rnd.get(-60, 60), goal.getZ());
		npc.setRunning();
		npc.getAI().setIntention(Intention.MOVE_TO, GeoEngine.getInstance().getValidLocation(npc, spot));
	}

	/**
	 * @param npc a monster of the siege
	 * @param range how far it looks
	 * @return the defender it goes for (a player, a fake defender or a siege guard): the nearest, but rather one of about its level, {@code null} if none
	 */
	private static Creature findDefenderNear(Npc npc, int range)
	{
		Creature best = null;
		double bestScore = Double.MAX_VALUE;
		for (Creature creature : World.getInstance().getVisibleObjectsInRange(npc, Creature.class, range))
		{
			if (creature.isDead() || creature.isInvisible() || !isDefender(creature))
			{
				continue;
			}
			// A monster sent for a strong defender leaves the weak ones to the others when it can.
			final int levelGap = Math.max(0, Math.abs(creature.getLevel() - npc.getLevel()) - 5);
			final double score = npc.calculateDistance2D(creature) + (levelGap * 40);
			if (score < bestScore)
			{
				best = creature;
				bestScore = score;
			}
		}
		return best;
	}

	/**
	 * @param creature a creature
	 * @return {@code true} if the monsters of a siege go for it: a player (or summon), a fake defender or a siege guard
	 */
	private static boolean isDefender(Creature creature)
	{
		if (creature.isPlayable())
		{
			final Player player = creature.asPlayer();
			return (player != null) && !player.isInOfflineMode() && !(player.isGM() && !player.getAccessLevel().canTakeAggro());
		}
		if (isSiegeDefender(creature))
		{
			return true;
		}
		return isSiegeGuard(creature);
	}

	private static boolean isSiegeGuard(Creature creature)
	{
		return creature.isAttackable() && (creature.asAttackable().getVillageSiegeRole() == Attackable.VILLAGE_SIEGE_GUARD);
	}

	/**
	 * The fallen siege guards come back after a while, the others are mended between fights.
	 * @param siege the siege
	 * @param entrance the entrance
	 * @param now the time
	 */
	private static void tickGuards(Siege siege, EntranceState entrance, long now)
	{
		for (int post = 0; post < entrance._guards.length; post++)
		{
			final Npc guard = entrance._guards[post];
			if ((guard == null) || !guard.isSpawned() || guard.isDead())
			{
				if (entrance._guardRespawn[post] == 0)
				{
					entrance._guardRespawn[post] = now + (VillageSiegeConfig.GUARD_RESPAWN * 1000L);
				}
				else if (now >= entrance._guardRespawn[post])
				{
					entrance._guardRespawn[post] = 0;
					if (guard != null)
					{
						guard.deleteMe();
					}
					spawnGuard(siege, entrance, post);
				}
			}
		}
	}

	/**
	 * @param siege the siege
	 * @return the entrance with the most defenders around it
	 */
	private static EntranceState getBusiestEntrance(Siege siege)
	{
		EntranceState busiest = siege._entrances.get(0);
		int most = -1;
		for (EntranceState entrance : siege._entrances)
		{
			final int defenders = countDefenders(siege, entrance._entrance.getGate(), VillageSiegeConfig.AREA_RANGE / 2);
			if (defenders > most)
			{
				busiest = entrance;
				most = defenders;
			}
		}
		return busiest;
	}

	/**
	 * @param siege the siege
	 * @param where a place
	 * @param range how far around it
	 * @return how many players and fake defenders are there
	 */
	private static int countDefenders(Siege siege, Location where, int range)
	{
		int count = countPlayers(where, range);
		for (Npc fake : siege._fakes)
		{
			if (fake.isSpawned() && !fake.isDead() && (fake.calculateDistance2D(where) <= range))
			{
				count++;
			}
		}
		return count;
	}

	private static int countPlayers(Location where, int range)
	{
		int count = 0;
		for (Player player : World.getInstance().getPlayers())
		{
			if (player.isOnline() && !player.isInOfflineMode() && !player.isDead() && !player.isGM() && (player.getInstanceId() == 0) && (player.calculateDistance2D(where) <= range))
			{
				count++;
			}
		}
		return count;
	}

	/**
	 * @param siege the siege
	 * @param where a place
	 * @param range how far around it
	 * @return how many of the siege's monsters are there
	 */
	private static int countMonstersNear(Siege siege, Location where, int range)
	{
		int count = 0;
		for (SiegeMonster monster : siege._monsters.values())
		{
			if (!monster._npc.isDead() && (monster._npc.calculateDistance2D(where) <= range))
			{
				count++;
			}
		}
		return count;
	}

	/**
	 * @param siege the siege
	 * @param creature a creature
	 * @return {@code true} if it is around the village (near its heart or an entrance)
	 */
	private static boolean isInArea(Siege siege, Creature creature)
	{
		if (creature.getInstanceId() != 0)
		{
			return false;
		}
		if (creature.calculateDistance2D(siege._heart) <= VillageSiegeConfig.AREA_RANGE)
		{
			return true;
		}
		for (EntranceState entrance : siege._entrances)
		{
			if ((creature.calculateDistance2D(entrance._entrance.getGate()) <= VillageSiegeConfig.AREA_RANGE) || (creature.calculateDistance2D(entrance._entrance.getRally()) <= VillageSiegeConfig.AREA_RANGE))
			{
				return true;
			}
		}
		return false;
	}

	// ---------------------------------------------------------------------------------------------
	// Fake defenders
	// ---------------------------------------------------------------------------------------------

	/**
	 * Fake players come to defend: more when no player is there, so the fighting goes on unwatched.
	 * @param siege the siege
	 * @param now the time
	 */
	private void tickFakes(Siege siege, long now)
	{
		siege._fakes.removeIf(fake -> !fake.isSpawned() || fake.isDead());
		if (!VillageSiegeConfig.FAKE_DEFENDERS || !FakePlayerPvpManager.getInstance().isEnabled() || (now < siege._nextFake))
		{
			return;
		}

		int players = 0;
		for (Player player : World.getInstance().getPlayers())
		{
			if (player.isOnline() && !player.isInOfflineMode() && !player.isGM() && isInArea(siege, player))
			{
				players++;
			}
		}
		final int crowd = Math.min(players, VillageSiegeConfig.FAKE_DEFENDERS_CROWD);
		final int wanted = VillageSiegeConfig.FAKE_DEFENDERS_ALONE - (((VillageSiegeConfig.FAKE_DEFENDERS_ALONE - VillageSiegeConfig.FAKE_DEFENDERS_CROWDED) * crowd) / VillageSiegeConfig.FAKE_DEFENDERS_CROWD);
		if (siege._fakes.size() >= wanted)
		{
			return;
		}
		if ((FakePlayerPvpConfig.MAX_ALIVE > 0) && (FakePlayerPvpManager.getInstance().getFakePlayers().size() >= FakePlayerPvpConfig.MAX_ALIVE))
		{
			return;
		}

		// To the gate with the fewest defenders.
		EntranceState entrance = siege._entrances.get(0);
		int fewest = Integer.MAX_VALUE;
		for (EntranceState state : siege._entrances)
		{
			final int defenders = countDefenders(siege, state._entrance.getGate(), VillageSiegeConfig.AREA_RANGE / 2);
			if (defenders < fewest)
			{
				entrance = state;
				fewest = defenders;
			}
		}

		// A small party, at the levels of the defenders there: the village's, or now and then a player's.
		final int[] levels = getLevelRange(siege._village);
		final List<Integer> playerLevels = new ArrayList<>();
		for (Player player : World.getInstance().getPlayers())
		{
			if (player.isOnline() && !player.isInOfflineMode() && !player.isGM() && siege._village.isDefenderLevel(player.getLevel()) && isInArea(siege, player))
			{
				playerLevels.add(player.getLevel());
			}
		}
		final int group = Math.min(wanted - siege._fakes.size(), Rnd.get(1, FAKE_GROUP_MAX));
		for (int i = 0; i < group; i++)
		{
			final int base = !playerLevels.isEmpty() && Rnd.nextBoolean() ? playerLevels.get(Rnd.get(playerLevels.size())) + Rnd.get(-2, 2) : Rnd.get(levels[0], levels[1]);
			final int level = Math.max(siege._village.getMinDefenderLevel(), Math.min(siege._village.getMaxDefenderLevel(), base));
			final Location arrival = QuestSpawnUtil.findPoint(entrance.getGuardCentre(), 50, FAKE_ARRIVAL_RANGE, true, 0);
			final Npc fake = FakePlayerPvpManager.getInstance().spawnFakePlayer(null, level, arrival.getX(), arrival.getY(), arrival.getZ(), 0, null, null);
			if (fake == null)
			{
				break;
			}
			final FakePlayerPvpProfile profile = fake.getTemplate().getFakePlayerPvpProfile();
			profile.setSiegeDefence(siege._village.getId(), entrance._index);
			profile.setHotzoneId(0);
			profile.setLeaveTime(Math.min(siege._end + Rnd.get(10000, 60000), now + (Rnd.get(VillageSiegeConfig.FAKE_STAY_MIN * 60, VillageSiegeConfig.FAKE_STAY_MAX * 60) * 1000L)));
			fake.broadcastPacket(new MagicSkillUse(fake, fake, 2036, 1, 1000, 0)); // Arrives by Scroll of Escape, like a player.
			siege._fakes.add(fake);
		}

		// The first ones come quickly.
		siege._nextFake = now + (siege._fakes.size() < (wanted / 2) ? 3000 : Rnd.get(VillageSiegeConfig.FAKE_ARRIVAL_MIN, VillageSiegeConfig.FAKE_ARRIVAL_MAX) * 1000L);
	}

	/**
	 * @param creature a creature
	 * @return {@code true} if it is a fake player defending a village under siege
	 */
	public static boolean isSiegeDefender(Creature creature)
	{
		if ((creature == null) || !creature.isNpc())
		{
			return false;
		}
		final FakePlayerPvpProfile profile = creature.asNpc().getTemplate().getFakePlayerPvpProfile();
		return (profile != null) && profile.isSiegeDefender();
	}

	/**
	 * @param fake a fake defender
	 * @return the siege monster it should fight: the nearest one near its gate or itself, {@code null} if none
	 */
	public Creature findDefenderTarget(Npc fake)
	{
		final Siege siege = _siege;
		final EntranceState entrance = getDefenderEntrance(siege, fake);
		if (entrance == null)
		{
			return null;
		}

		final Location gate = entrance._entrance.getGate();
		Creature best = null;
		double bestDistance = Double.MAX_VALUE;
		for (SiegeMonster monster : siege._monsters.values())
		{
			final Npc npc = monster._npc;
			if (npc.isDead() || !npc.isSpawned() || npc.isInvisible())
			{
				continue;
			}
			final double distance = fake.calculateDistance2D(npc);
			if ((distance > DEFENDER_REACH) && (npc.calculateDistance2D(gate) > DEFENDER_REACH))
			{
				continue;
			}
			if (distance < bestDistance)
			{
				best = npc;
				bestDistance = distance;
			}
		}
		return best;
	}

	/**
	 * @param fake a fake defender
	 * @return where it waits for the next wave: by its gate, on the village side, {@code null} if the siege is over
	 */
	public Location getDefenderPost(Npc fake)
	{
		final Siege siege = _siege;
		final EntranceState entrance = getDefenderEntrance(siege, fake);
		if (entrance == null)
		{
			return null;
		}

		final Location gate = entrance._entrance.getGate();
		final Location line = entrance.getGuardCentre();
		// A spot of its own around the gate, from a little out on the road to the guards' line (its object id spreads them out).
		final double share = -1.2 + ((fake.getObjectId() % 6) * 0.4);
		final int side = ((fake.getObjectId() / 6) % 7) - 3;
		final double dx = line.getX() - gate.getX();
		final double dy = line.getY() - gate.getY();
		final double length = Math.max(1, Math.hypot(dx, dy));
		final int x = gate.getX() + (int) (dx * share) + (int) ((-dy / length) * side * 40);
		final int y = gate.getY() + (int) (dy * share) + (int) ((dx / length) * side * 40);
		final Location post = new Location(x, y, GeoEngine.getInstance().getHeight(x, y, gate.getZ()));
		return GeoEngine.getInstance().canMoveToTarget(gate, post) ? post : gate;
	}

	private static EntranceState getDefenderEntrance(Siege siege, Npc fake)
	{
		if (siege == null)
		{
			return null;
		}
		final FakePlayerPvpProfile profile = fake.getTemplate().getFakePlayerPvpProfile();
		if ((profile == null) || !siege._village.getId().equals(profile.getSiegeVillage()))
		{
			return null;
		}
		final int index = profile.getSiegeEntrance();
		return (index >= 0) && (index < siege._entrances.size()) ? siege._entrances.get(index) : siege._entrances.get(0);
	}

	/**
	 * @param guard a siege guard
	 * @param target a monster
	 * @return {@code true} if the guard goes for the monster: near enough to its post (the guards hold their line at the gate)
	 */
	public static boolean isInGuardReach(Attackable guard, Creature target)
	{
		final Spawn spawn = guard.getSpawn();
		final int postX = spawn != null ? spawn.getX() : guard.getX();
		final int postY = spawn != null ? spawn.getY() : guard.getY();
		final double dx = target.getX() - postX;
		final double dy = target.getY() - postY;
		return ((dx * dx) + (dy * dy)) <= ((double) VillageSiegeConfig.GUARD_AGGRO_RANGE * VillageSiegeConfig.GUARD_AGGRO_RANGE);
	}

	// ---------------------------------------------------------------------------------------------
	// Contribution
	// ---------------------------------------------------------------------------------------------

	private static String playerKey(Player player)
	{
		return "p" + player.getObjectId();
	}

	private static String fakeKey(Npc fake)
	{
		return "f" + fake.getName();
	}

	/**
	 * @param siege the siege
	 * @param creature who did something (a player, its summon, or a fake defender)
	 * @return its contributor, {@code null} if it can't contribute
	 */
	private static Contributor getContributor(Siege siege, Creature creature)
	{
		if (creature == null)
		{
			return null;
		}

		final Player player = creature.asPlayer();
		if (player != null)
		{
			final Contributor contributor = siege._contributors.computeIfAbsent(playerKey(player), key -> new Contributor(key, player.getObjectId(), player.getName(), false, player.getLevel()));
			contributor._level = player.getLevel();
			if (!siege._village.isDefenderLevel(player.getLevel()))
			{
				if (!contributor._told)
				{
					contributor._told = true;
					player.sendMessage(siege._village.getName() + ": only defenders " + siege._village.describeDefenderLevels() + " earn contribution.");
				}
				return null;
			}
			if (VillageSiegeConfig.ONE_PER_COMPUTER && (contributor._blocked == null))
			{
				final String owner = siege._computers.computeIfAbsent(getComputer(player), computer -> contributor._key);
				if (!owner.equals(contributor._key))
				{
					contributor._blocked = "another character of your computer defends this siege";
					player.sendMessage(siege._village.getName() + ": no Defence Medals - " + contributor._blocked + ".");
				}
			}
			return contributor;
		}

		if (isSiegeDefender(creature))
		{
			final Npc fake = creature.asNpc();
			return siege._contributors.computeIfAbsent(fakeKey(fake), key -> new Contributor(key, 0, fake.getName(), true, fake.getLevel()));
		}
		return null;
	}

	private static String getComputer(Player player)
	{
		if ((player.getClient() != null) && (player.getClient().getHardwareInfo() != null) && (player.getClient().getHardwareInfo().getMacAddress() != null))
		{
			return "hw:" + player.getClient().getHardwareInfo().getMacAddress();
		}
		return (player.getClient() != null) && (player.getClient().getIp() != null) ? "ip:" + player.getClient().getIp() : "char:" + player.getObjectId();
	}

	/**
	 * Called from {@code Attackable#reduceCurrentHp} when a monster of a siege takes damage.
	 * @param monster the monster
	 * @param attacker who hit it
	 * @param damage the damage
	 */
	public void onSiegeMonsterDamaged(Attackable monster, Creature attacker, double damage)
	{
		final Siege siege = _siege;
		if ((siege == null) || (damage <= 0))
		{
			return;
		}

		final SiegeMonster siegeMonster = siege._monsters.get(monster.getObjectId());
		if (siegeMonster == null)
		{
			return;
		}

		siegeMonster._lastMet = System.currentTimeMillis();
		final Contributor contributor = getContributor(siege, attacker);
		if (contributor == null)
		{
			return;
		}

		siegeMonster._damage.merge(contributor._key, Math.min(damage, monster.getMaxHp()), Double::sum);
		if (!isGrey(contributor, monster))
		{
			contributor._lastActive = System.currentTimeMillis();
		}
	}

	/**
	 * @param contributor a contributor
	 * @param monster a monster
	 * @return {@code true} if the monster is too far below the contributor to count
	 */
	private static boolean isGrey(Contributor contributor, Creature monster)
	{
		return (contributor._level - monster.getLevel()) >= VillageSiegeConfig.GREY_LEVEL_DIFFERENCE;
	}

	/**
	 * A monster of the siege died: its points are shared by the damage each defender did.
	 * @param npc the monster
	 */
	private void onSiegeMonsterDeath(Npc npc)
	{
		final Siege siege = _siege;
		if (siege == null)
		{
			return;
		}

		final SiegeMonster monster = siege._monsters.remove(npc.getObjectId());
		if (monster == null)
		{
			return;
		}

		siege._kills++;
		final double points;
		switch (monster._kind)
		{
			case KIND_LORD:
			{
				points = VillageSiegeConfig.LORD_POINTS;
				announceNear(siege, "The Siege Lord of " + siege._village.getName() + " has fallen!");
				break;
			}
			case KIND_LEADER:
			{
				points = VillageSiegeConfig.LEADER_POINTS;
				break;
			}
			default:
			{
				points = VillageSiegeConfig.KILL_POINTS * (1 + npc.asAttackable().getChampionTier());
				break;
			}
		}

		// Shared by damage; what the guards (and anyone else) did is lost.
		final double maxHp = Math.max(1, npc.getMaxHp());
		for (Map.Entry<String, Double> entry : monster._damage.entrySet())
		{
			final Contributor contributor = siege._contributors.get(entry.getKey());
			if ((contributor == null) || isGrey(contributor, npc))
			{
				continue;
			}
			final double share = (points * Math.min(maxHp, entry.getValue())) / maxHp;
			addPoints(contributor, share, share * getMedalsPerPoint(npc.getLevel()));
		}
	}

	/**
	 * Called from {@code CombatLogManager#onHealed}: healing a defender of a siege is contribution.
	 * @param healer who healed
	 * @param target who was healed
	 * @param hp the HP restored
	 */
	public void onHealed(Creature healer, Creature target, double hp)
	{
		final Siege siege = _siege;
		if ((siege == null) || (healer == null) || (target == null) || (healer == target) || target.isDead() || (target.getMaxHp() <= 0))
		{
			return;
		}

		// Only a defender in the middle of the siege.
		if (!isDefender(target) || !isInArea(siege, target) || ((healer.asPlayer() != null) && (healer.asPlayer() == target.asPlayer())))
		{
			return;
		}

		final Contributor contributor = getContributor(siege, healer);
		if (contributor == null)
		{
			return;
		}

		contributor._lastActive = System.currentTimeMillis();
		final double points = (VillageSiegeConfig.HEAL_POINTS * Math.min(hp, target.getMaxHp())) / target.getMaxHp();
		addPoints(contributor, points, points * getMedalsPerPoint(contributor._level));
	}

	/**
	 * Every minute of fighting is worth something.
	 * @param siege the siege
	 * @param now the time
	 */
	private static void payActiveMinute(Siege siege, long now)
	{
		for (Contributor contributor : siege._contributors.values())
		{
			if (contributor._lastActive >= (now - 60000))
			{
				synchronized (contributor)
				{
					contributor._activeMinutes++;
				}
				addPoints(contributor, VillageSiegeConfig.ACTIVE_POINTS, VillageSiegeConfig.ACTIVE_POINTS * getMedalsPerPoint(contributor._level));
			}
		}
	}

	/**
	 * @param contributor who earned it
	 * @param points the points
	 * @param medals the medals they pay (not to a fake player, nor to a second character of a computer)
	 */
	private static void addPoints(Contributor contributor, double points, double medals)
	{
		if (points <= 0)
		{
			return;
		}

		synchronized (contributor)
		{
			contributor._points += points;
			if (!contributor._fake && (contributor._blocked == null))
			{
				contributor._pendingMedals += medals;
			}
		}
	}

	/**
	 * Pays the medals earned to the players online.
	 * @param siege the siege
	 * @param end {@code true} at the end of the siege: what is left of a medal is rounded
	 */
	private static void payMedals(Siege siege, boolean end)
	{
		if (VillageSiegeConfig.MEDAL_ITEM_ID <= 0)
		{
			return;
		}

		for (Contributor contributor : siege._contributors.values())
		{
			if (contributor._fake)
			{
				continue;
			}

			final Player player = World.getInstance().getPlayer(contributor._objectId);
			if ((player == null) || !player.isOnline())
			{
				continue;
			}

			final long due;
			synchronized (contributor)
			{
				due = end ? Math.round(contributor._pendingMedals) : (long) Math.floor(contributor._pendingMedals);
				if (due <= 0)
				{
					continue;
				}
				contributor._pendingMedals = Math.max(0, contributor._pendingMedals - due);
				contributor._medals += due;
			}
			player.addItem(ItemProcessType.REWARD, VillageSiegeConfig.MEDAL_ITEM_ID, due, null, true);
		}
	}

	// ---------------------------------------------------------------------------------------------
	// Rankings
	// ---------------------------------------------------------------------------------------------

	private static void createTable()
	{
		try (Connection con = DatabaseFactory.getConnection();
			Statement st = con.createStatement())
		{
			st.execute(CREATE_TABLE);
		}
		catch (Exception e)
		{
			LOGGER.log(Level.WARNING, VillageSiegeManager.class.getSimpleName() + ": Could not create the ranking table.", e);
		}
	}

	private void loadResults()
	{
		final Map<String, List<RankRow>> rows = new HashMap<>();
		final Map<String, long[]> info = new HashMap<>();
		try (Connection con = DatabaseFactory.getConnection())
		{
			// Rankings older than a month are of no use to anyone.
			try (PreparedStatement ps = con.prepareStatement(DELETE_OLD))
			{
				ps.setInt(1, getDay(System.currentTimeMillis() - (30 * 86400000L)));
				ps.executeUpdate();
			}
			try (PreparedStatement ps = con.prepareStatement(SELECT_LATEST);
				ResultSet rs = ps.executeQuery())
			{
				while (rs.next())
				{
					final String village = rs.getString("village");
					rows.computeIfAbsent(village, v -> new ArrayList<>()).add(new RankRow(rs.getString("name"), rs.getInt("fake") > 0, rs.getInt("level"), rs.getInt("points"), rs.getLong("medals")));
					info.put(village, new long[]
					{
						rs.getInt("day"),
						rs.getLong("end_time"),
						rs.getInt("gate_fell")
					});
				}
			}
		}
		catch (Exception e)
		{
			LOGGER.log(Level.WARNING, getClass().getSimpleName() + ": Could not load the rankings.", e);
		}

		for (Map.Entry<String, List<RankRow>> entry : rows.entrySet())
		{
			final List<RankRow> list = entry.getValue();
			list.sort((a, b) -> Integer.compare(b.getPoints(), a.getPoints()));
			final long[] data = info.get(entry.getKey());
			_results.put(entry.getKey(), new SiegeResult(entry.getKey(), (int) data[0], data[1], data[2] > 0, list));
		}
	}

	private void saveResult(Siege siege, List<Contributor> ranking, long now)
	{
		// The rotation is shorter than a day, so a village can be besieged twice in one: its day's ranking adds both up.
		final SiegeResult earlier = _results.get(siege._village.getId());
		final boolean sameDay = (earlier != null) && (earlier.getDay() == siege._day);
		final Map<String, RankRow> rows = new LinkedHashMap<>();
		if (sameDay)
		{
			for (RankRow row : earlier.getRows())
			{
				rows.put(row.getName(), row);
			}
		}
		for (Contributor contributor : ranking)
		{
			final RankRow before = rows.get(contributor.getName());
			rows.put(contributor.getName(), new RankRow(contributor.getName(), contributor._fake, contributor._level, contributor.getPoints() + (before != null ? before.getPoints() : 0), contributor.getMedals() + (before != null ? before.getMedals() : 0)));
		}
		final List<RankRow> list = new ArrayList<>(rows.values());
		list.sort((a, b) -> Integer.compare(b.getPoints(), a.getPoints()));
		final boolean gateFell = siege._gateFell || (sameDay && earlier.hasGateFallen());
		_results.put(siege._village.getId(), new SiegeResult(siege._village.getId(), siege._day, now, gateFell, list));

		try (Connection con = DatabaseFactory.getConnection())
		{
			try (PreparedStatement ps = con.prepareStatement(INSERT_RANKING))
			{
				for (Contributor contributor : ranking)
				{
					final RankRow row = rows.get(contributor.getName());
					ps.setInt(1, siege._day);
					ps.setString(2, siege._village.getId());
					ps.setString(3, contributor.getName());
					ps.setInt(4, contributor._objectId);
					ps.setInt(5, contributor._fake ? 1 : 0);
					ps.setInt(6, Math.max(0, Math.min(255, contributor._level)));
					ps.setInt(7, row.getPoints());
					ps.setLong(8, row.getMedals());
					ps.setInt(9, gateFell ? 1 : 0);
					ps.setLong(10, now);
					ps.addBatch();
				}
				ps.executeBatch();
			}
			// The defenders of the day's first siege who missed the second keep their rows: they learn of a fallen gate too.
			if (gateFell && sameDay)
			{
				try (PreparedStatement ps = con.prepareStatement(UPDATE_GATE_FELL))
				{
					ps.setInt(1, siege._day);
					ps.setString(2, siege._village.getId());
					ps.executeUpdate();
				}
			}
		}
		catch (Exception e)
		{
			LOGGER.log(Level.WARNING, getClass().getSimpleName() + ": Could not save the ranking of " + siege._village.getName() + ".", e);
		}
	}

	/**
	 * @param village a village id
	 * @return the last siege of the village that ended, {@code null} if none
	 */
	public SiegeResult getResult(String village)
	{
		return _results.get(village);
	}

	/**
	 * @return today's ranking: every siege of the schedule's day added up, the one going on included, the most points first
	 */
	public List<RankRow> getTodayRanking()
	{
		final int today = getDay(System.currentTimeMillis());
		final Map<String, long[]> sums = new LinkedHashMap<>();
		final Map<String, RankRow> last = new HashMap<>();
		for (SiegeResult result : _results.values())
		{
			if (result.getDay() != today)
			{
				continue;
			}
			for (RankRow row : result.getRows())
			{
				final long[] sum = sums.computeIfAbsent(row.getName(), n -> new long[2]);
				sum[0] += row.getPoints();
				sum[1] += row.getMedals();
				last.put(row.getName(), row);
			}
		}

		// The siege going on: what its village's earlier sieges of the day gave is in the results, and this one is added to them when it ends.
		final Siege siege = _siege;
		if ((siege != null) && (siege._day == today))
		{
			for (Contributor contributor : siege.getRanking())
			{
				final long[] sum = sums.computeIfAbsent(contributor.getName(), n -> new long[2]);
				sum[0] += contributor.getPoints();
				sum[1] += contributor.getMedals();
				last.put(contributor.getName(), new RankRow(contributor.getName(), contributor._fake, contributor._level, 0, 0));
			}
		}

		final List<RankRow> rows = new ArrayList<>();
		for (Map.Entry<String, long[]> entry : sums.entrySet())
		{
			final RankRow row = last.get(entry.getKey());
			rows.add(new RankRow(entry.getKey(), row.isFake(), row.getLevel(), (int) entry.getValue()[0], entry.getValue()[1]));
		}
		rows.sort((a, b) -> Integer.compare(b.getPoints(), a.getPoints()));
		return rows;
	}

	/**
	 * Saves the siege going on, as if it ended now (the shutdown).
	 */
	public void storeAll()
	{
		final Siege siege = _siege;
		if (siege != null)
		{
			payMedals(siege, true);
			saveResult(siege, siege.getRanking(), System.currentTimeMillis());
			cleanUp(siege);
			_siege = null;
		}
	}

	// ---------------------------------------------------------------------------------------------
	// Boards and announcements
	// ---------------------------------------------------------------------------------------------

	private void spawnBoards()
	{
		for (SiegeVillage village : VillageSiegeData.getInstance().getVillages())
		{
			final Location heart = getHeart(village);
			if (heart == null)
			{
				LOGGER.warning(getClass().getSimpleName() + ": No spawn of gatekeeper " + village.getGatekeeperId() + " for the Defence Force Board of " + village.getName() + ".");
				continue;
			}
			final Location location = QuestSpawnUtil.findPoint(heart, 150, 230, true, 0);
			final Npc board = Quest.addSpawn(BOARD_NPC_ID, location.getX(), location.getY(), location.getZ(), LocationUtil.calculateHeadingFrom(location, heart), false, 0, false, 0);
			if (board != null)
			{
				board.setRandomWalking(false);
				board.getVariables().set("VILLAGE_SIEGE_VILLAGE", village.getId());
				_boards.put(village.getId(), board);
			}
		}
		LOGGER.info(getClass().getSimpleName() + ": " + _boards.size() + " Defence Force Boards placed.");
	}

	/**
	 * @param npc a Defence Force Board
	 * @return the village it stands in, {@code null} if none
	 */
	public SiegeVillage getBoardVillage(Npc npc)
	{
		return (npc != null) && npc.hasVariables() ? VillageSiegeData.getInstance().getVillage(npc.getVariables().getString("VILLAGE_SIEGE_VILLAGE", null)) : null;
	}

	/**
	 * @param player a player
	 * @return the village whose board is the nearest to the player, {@code null} if none
	 */
	public SiegeVillage getNearestVillage(Player player)
	{
		SiegeVillage nearest = null;
		double best = Double.MAX_VALUE;
		for (Map.Entry<String, Npc> entry : _boards.entrySet())
		{
			final double distance = player.calculateDistance2D(entry.getValue());
			if (distance < best)
			{
				best = distance;
				nearest = VillageSiegeData.getInstance().getVillage(entry.getKey());
			}
		}
		return nearest;
	}

	/**
	 * Announces to the players a siege is for ({@link #isAnnouncedTo}), and the GMs.
	 * @param village the village besieged
	 * @param text what
	 */
	private static void announce(SiegeVillage village, String text)
	{
		if (!VillageSiegeConfig.ANNOUNCE)
		{
			return;
		}

		final CreatureSay packet = new CreatureSay(null, ChatType.CRITICAL_ANNOUNCE, "", text);
		for (Player player : World.getInstance().getPlayers())
		{
			if (player.isOnline() && isAnnouncedTo(village, player))
			{
				player.sendPacket(packet);
			}
		}
	}

	/**
	 * @param village the village besieged
	 * @param player a player
	 * @return {@code true} if the village's sieges are announced to the player: from its lowest defender level to {@link SiegeVillage#getAnnounceMaxLevel}, or a GM
	 */
	private static boolean isAnnouncedTo(SiegeVillage village, Player player)
	{
		return ((player.getLevel() >= village.getMinDefenderLevel()) && (player.getLevel() <= village.getAnnounceMaxLevel())) || player.isGM();
	}

	/**
	 * Tells the players around the village, big on screen.
	 * @param siege the siege
	 * @param text what
	 */
	private static void screenNear(Siege siege, String text)
	{
		final ExShowScreenMessage packet = new ExShowScreenMessage(text, ExShowScreenMessage.TOP_CENTER, 6000);
		for (Player player : World.getInstance().getPlayers())
		{
			if (player.isOnline() && isAnnouncedTo(siege._village, player) && isInArea(siege, player))
			{
				player.sendPacket(packet);
			}
		}
	}

	/**
	 * At the start of a siege, the players it is for and who aren't there yet are told on screen, and shown the siege's window with a button to join it.
	 * @param siege the siege
	 */
	private static void invite(Siege siege)
	{
		if (!VillageSiegeConfig.ANNOUNCE)
		{
			return;
		}

		final ExShowScreenMessage packet = new ExShowScreenMessage(siege._village.getName() + " is under siege! Type .siege to join the defence.", ExShowScreenMessage.TOP_CENTER, 8000);
		for (Player player : World.getInstance().getPlayers())
		{
			if (!player.isOnline() || player.isInOfflineMode() || !siege._village.isDefenderLevel(player.getLevel()) || isInArea(siege, player))
			{
				continue;
			}
			player.sendPacket(packet);
			if (VillageSiegeConfig.INVITE && (getJoinRefusal(siege, player) == null))
			{
				final String html = VillageSiegeBoard.handle(player, null, "invite");
				if (html != null)
				{
					player.sendPacket(new NpcHtmlMessage(0, html));
				}
			}
		}
	}

	/**
	 * @param siege the siege
	 * @param player a player
	 * @return why the player can't take the siege's teleport now, {@code null} if it can
	 */
	public static String getJoinRefusal(Siege siege, Player player)
	{
		if (!VillageSiegeConfig.TELEPORT)
		{
			return "There is no teleport to the siege.";
		}
		if (!siege._village.isDefenderLevel(player.getLevel()) && !player.isGM())
		{
			return "The Defence Force of " + siege._village.getName() + " only calls defenders " + siege._village.describeDefenderLevels() + ".";
		}
		return getTeleportRefusal(player);
	}

	/**
	 * @param player a player
	 * @return why the player can't teleport now, like at a gatekeeper, {@code null} if it can
	 */
	private static String getTeleportRefusal(Player player)
	{
		if (player.isAlikeDead())
		{
			return "You can't teleport while dead.";
		}
		if (AttackStanceTaskManager.getInstance().hasAttackStanceTask(player) || player.isCastingNow() || player.isTeleporting())
		{
			return "You can't teleport in the middle of a fight.";
		}
		if (player.isInOlympiadMode() || OlympiadManager.getInstance().isRegistered(player) || player.isInDuel() || player.isOnEvent())
		{
			return "You can't teleport while you take part in the Olympiad, a duel or an event.";
		}
		if (player.isJailed() || player.isInSiege() || player.isInsideZone(org.l2jmobius.gameserver.model.zone.ZoneId.SIEGE) || (player.getInstanceId() != 0) || player.isInBoat())
		{
			return "You can't teleport from here.";
		}
		if (player.isInStoreMode() || player.isFishing() || player.isFlyingMounted() || player.isMovementDisabled() || player.isCursedWeaponEquipped())
		{
			return "You can't teleport right now.";
		}
		if (!PlayerConfig.ALT_GAME_KARMA_PLAYER_CAN_USE_GK && (player.getKarma() > 0))
		{
			return "The Defence Force doesn't call on chaotic characters.";
		}
		return null;
	}

	/**
	 * Takes a player to the siege going on, behind the gate with the fewest defenders, for free. It can go back where it was ({@link #returnHome}) until a while after the siege.
	 * @param player the player
	 * @return {@code null} if it went, else why not
	 */
	public String join(Player player)
	{
		final Siege siege = _siege;
		if (siege == null)
		{
			return "No village is under siege now.";
		}
		final String refusal = getJoinRefusal(siege, player);
		if (refusal != null)
		{
			return refusal;
		}
		if (isInArea(siege, player))
		{
			return "You are at the siege already.";
		}

		EntranceState entrance = null;
		int fewest = Integer.MAX_VALUE;
		for (EntranceState state : siege._entrances)
		{
			final int defenders = countDefenders(siege, state._entrance.getGate(), VillageSiegeConfig.AREA_RANGE / 2);
			if (defenders < fewest)
			{
				entrance = state;
				fewest = defenders;
			}
		}
		if (entrance == null)
		{
			return "No gate to go to.";
		}

		_returns.put(player.getObjectId(), player.getLocation());
		final Location location = QuestSpawnUtil.findPoint(entrance.getGuardCentre(), 30, 150, true, 0);
		player.teleToLocation(location, false);
		player.sendMessage(siege._village.getName() + ": you join the defence of the " + entrance.getName() + ". Type .siege to go back when you are done.");
		return null;
	}

	/**
	 * Takes a player who came by the siege's teleport back where it was.
	 * @param player the player
	 * @return {@code null} if it went, else why not
	 */
	public String returnHome(Player player)
	{
		final Location location = _returns.get(player.getObjectId());
		if ((location == null) || ((_siege == null) && (System.currentTimeMillis() > _returnUntil)))
		{
			_returns.remove(player.getObjectId());
			return "You have nowhere to go back to.";
		}
		final String refusal = getTeleportRefusal(player);
		if (refusal != null)
		{
			return refusal;
		}
		_returns.remove(player.getObjectId());
		player.teleToLocation(location, false);
		return null;
	}

	/**
	 * @param player a player
	 * @return {@code true} if a siege is going on and the player is at it
	 */
	public boolean isAtSiege(Player player)
	{
		final Siege siege = _siege;
		return (siege != null) && isInArea(siege, player);
	}

	/**
	 * @param player a player
	 * @return {@code true} if it came by the siege's teleport and can go back
	 */
	public boolean canReturn(Player player)
	{
		return _returns.containsKey(player.getObjectId()) && ((_siege != null) || (System.currentTimeMillis() <= _returnUntil));
	}

	/**
	 * Tells the players around the village, on screen and in chat.
	 * @param siege the siege
	 * @param text what
	 */
	private static void announceNear(Siege siege, String text)
	{
		for (Player player : World.getInstance().getPlayers())
		{
			if (player.isOnline() && isAnnouncedTo(siege._village, player) && isInArea(siege, player))
			{
				player.sendPacket(new ExShowScreenMessage(text, ExShowScreenMessage.TOP_CENTER, 5000));
				player.sendMessage(text);
			}
		}
	}

	/**
	 * @param time a time
	 * @return it as HH:mm (server time)
	 */
	public static String formatTime(long time)
	{
		return LocalDateTime.ofInstant(Instant.ofEpochMilli(time), ZoneId.systemDefault()).format(TIME_FORMAT);
	}

	/**
	 * @param level a character's level
	 * @return {@code true} if a siege is going on and it is for characters of that level
	 */
	public boolean isDefenderLevel(int level)
	{
		final Siege siege = _siege;
		return (siege != null) && siege._village.isDefenderLevel(level);
	}

	/**
	 * @return the siege going on, {@code null} if none
	 */
	public Siege getSiege()
	{
		return _siege;
	}

	public static VillageSiegeManager getInstance()
	{
		return SingletonHolder.INSTANCE;
	}

	private static class SingletonHolder
	{
		protected static final VillageSiegeManager INSTANCE = new VillageSiegeManager();
	}
}
