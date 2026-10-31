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
package org.l2jmobius.gameserver.managers;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.l2jmobius.commons.threads.ThreadPool;
import org.l2jmobius.commons.util.Rnd;
import org.l2jmobius.gameserver.ai.AttackableAI;
import org.l2jmobius.gameserver.ai.FakeBrain;
import org.l2jmobius.gameserver.ai.Intention;
import org.l2jmobius.gameserver.config.custom.DualboxCheckConfig;
import org.l2jmobius.gameserver.config.custom.RaceWarsConfig;
import org.l2jmobius.gameserver.data.SpawnTable;
import org.l2jmobius.gameserver.data.xml.EventMapData;
import org.l2jmobius.gameserver.data.xml.NpcData;
import org.l2jmobius.gameserver.geoengine.GeoEngine;
import org.l2jmobius.gameserver.managers.simulation.SimRole;
import org.l2jmobius.gameserver.managers.simulation.SimulationComposer;
import org.l2jmobius.gameserver.managers.simulation.SimulationWar;
import org.l2jmobius.gameserver.model.Location;
import org.l2jmobius.gameserver.model.World;
import org.l2jmobius.gameserver.model.WorldRegion;
import org.l2jmobius.gameserver.model.actor.Attackable;
import org.l2jmobius.gameserver.model.actor.Creature;
import org.l2jmobius.gameserver.model.actor.Npc;
import org.l2jmobius.gameserver.model.actor.Player;
import org.l2jmobius.gameserver.model.actor.enums.creature.Race;
import org.l2jmobius.gameserver.model.actor.enums.creature.Team;
import org.l2jmobius.gameserver.model.actor.holders.npc.FakePlayerHolder;
import org.l2jmobius.gameserver.model.actor.holders.npc.FakePlayerParty;
import org.l2jmobius.gameserver.model.actor.holders.npc.FakePlayerPvpProfile;
import org.l2jmobius.gameserver.model.actor.instance.Fence;
import org.l2jmobius.gameserver.model.actor.templates.NpcTemplate;
import org.l2jmobius.gameserver.model.eventmap.EventMap;
import org.l2jmobius.gameserver.model.eventmap.EventMapType;
import org.l2jmobius.gameserver.model.groups.Party;
import org.l2jmobius.gameserver.model.instancezone.Instance;
import org.l2jmobius.gameserver.model.item.enums.ItemProcessType;
import org.l2jmobius.gameserver.model.item.holders.ItemHolder;
import org.l2jmobius.gameserver.model.olympiad.OlympiadManager;
import org.l2jmobius.gameserver.model.script.Quest;
import org.l2jmobius.gameserver.model.skill.CommonSkill;
import org.l2jmobius.gameserver.model.skill.Skill;
import org.l2jmobius.gameserver.model.spawns.Spawn;
import org.l2jmobius.gameserver.model.zone.ZoneId;
import org.l2jmobius.gameserver.network.serverpackets.ExShowScreenMessage;
import org.l2jmobius.gameserver.network.serverpackets.MagicSkillUse;
import org.l2jmobius.gameserver.network.serverpackets.NicknameChanged;
import org.l2jmobius.gameserver.taskmanagers.DecayTaskManager;
import org.l2jmobius.gameserver.util.Broadcast;
import org.l2jmobius.gameserver.util.QuestSpawnUtil;

/**
 * Race Wars: race against race, until the last one standing (see config/Custom/RaceWars.ini).
 * <ul>
 * <li>Players register at the TvT Event Manager while the registration is open (the event rotation opens it in its turn, see {@link EventRotationManager}, or //racewars open). Each level bracket with players gets a tournament of the six races.</li>
 * <li>A player fights for the race of the class it plays when the registration closes, a subclass included ({@code player.getTemplate().getRace()}, not {@link Player#getRace}, which is its base class's race). Every race's army has the same size: at least
 * {@link RaceWarsConfig#ARMY_SIZE}, its players, the rest fake players of that race, in parties.</li>
 * <li>The races are drawn into pairs; the race-vs-race matches run at once, each in an instance of its own of the map, the armies at the map's first Team vs Team bases. Nobody comes back: a dead fighter can't be resurrected (see {@link #isEliminated}) and a race
 * is out once its last fighter is dead. After {@link RaceWarsConfig#MONSTER_TIME} minutes, monsters of the bracket's level come, champions that go for every fighter, through Silent Move and Hide (see {@link #isPrey}). If they kill everyone, the race whose last
 * fighter died last wins; {@link RaceWarsConfig#HARD_LIMIT} minutes after they came, the race with the most fighters alive.</li>
 * <li>The winners meet in a final, all against all, with fresh fake players; each player of the race left standing gets {@link RaceWarsConfig#WIN_REWARD}.</li>
 * </ul>
 */
public class RaceWarsManager
{
	private static final Logger LOGGER = Logger.getLogger(RaceWarsManager.class.getName());

	public static final String TITLE = "Race Wars";
	private static final String TAG = "Race Wars";

	/** {@code true} while a match goes on: the combat hooks only look further then. */
	public static volatile boolean ACTIVE = false;

	private static final long TICK_MS = 1000;
	/** A fake player engages a fighter of another race it sees this close (or one very close, seen or not), and a monster this close. */
	private static final int SIGHT_RANGE = 1200;
	private static final int CLOSE_ENEMY_RANGE = 150;
	private static final int MONSTER_ENGAGE_RANGE = 500;
	/** How long the result stays on screen before everyone goes on (home, or to the final). */
	private static final long END_DELAY = 10000;
	/** Someone this far beyond the fence is brought back inside it. */
	private static final int BARRIER_MARGIN = 250;
	/** The players see how many of each race are alive this often. */
	private static final long STATUS_INTERVAL = 15000;
	/** Registration reminders, in minutes before its end. */
	private static final int[] WARNINGS =
	{
		5,
		3,
		1
	};
	/** Monster spots are picked this far inside the fence, at most. */
	private static final double MONSTER_FIELD_SHARE = 0.85;

	public enum Phase
	{
		/** At the start points: buffs, nobody leaves them. */
		PREP,
		/** The fight. */
		FIGHT,
		/** The fight, with the monsters. */
		MONSTERS,
		/** The result is shown; everyone goes on shortly. */
		ENDED
	}

	public enum Stage
	{
		SEMIFINAL,
		FINAL
	}

	/**
	 * What a player was before the event, given back when it leaves it.
	 */
	private static class PlayerState
	{
		final Location returnLocation;
		final int nameColor;
		final String title;

		PlayerState(Player player)
		{
			returnLocation = new Location(player.getX(), player.getY(), player.getZ(), player.getHeading());
			nameColor = player.getAppearance().getNameColor();
			title = player.getTitle();
		}
	}

	/**
	 * A player or fake player of a match.
	 */
	public static class Fighter
	{
		final Creature _creature;
		final Match _match;
		final Army _army;
		final SimRole _role;
		volatile int _kills;
		volatile boolean _dead;

		Fighter(Creature creature, Match match, Army army, SimRole role)
		{
			_creature = creature;
			_match = match;
			_army = army;
			_role = role;
		}

		public boolean isPlayer()
		{
			return _creature.isPlayer();
		}

		boolean isAlive()
		{
			return !_dead && !_creature.isDead() && (_creature.isPlayer() || _creature.isSpawned());
		}
	}

	/**
	 * The army of one race in a match.
	 */
	public static class Army
	{
		final Race _race;
		final Location _start;
		final Team _team;
		final List<Fighter> _fighters = new CopyOnWriteArrayList<>();
		final List<FakePlayerParty> _parties = new CopyOnWriteArrayList<>();
		/** When its last fighter so far died (the race whose last fighter died last wins when everyone is dead). */
		volatile long _lastDeath;

		Army(Race race, Location start, Team team)
		{
			_race = race;
			_start = start;
			_team = team;
		}

		int getAlive()
		{
			int alive = 0;
			for (Fighter fighter : _fighters)
			{
				if (fighter.isAlive())
				{
					alive++;
				}
			}
			return alive;
		}

		/**
		 * @return the HP and CP its living fighters have left, as a ratio of the whole army's
		 */
		double getHealthLeft()
		{
			double current = 0;
			double max = 0;
			for (Fighter fighter : _fighters)
			{
				final Creature creature = fighter._creature;
				max += creature.getMaxHp() + creature.getMaxCp();
				if (fighter.isAlive())
				{
					current += creature.getCurrentHp() + creature.getCurrentCp();
				}
			}
			return max > 0 ? current / max : 0;
		}

		int getPlayerCount()
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
	 * A race-vs-race match or the final, in an instance of its own.
	 */
	public static class Match
	{
		final int _id;
		final Tournament _tournament;
		final Stage _stage;
		final int _instanceId;
		final List<Army> _armies = new ArrayList<>();
		final List<Fence> _barrier = new ArrayList<>();
		final Set<Integer> _monsters = ConcurrentHashMap.newKeySet();
		final long _prepEnd;
		volatile Phase _phase = Phase.PREP;
		volatile long _fightStart;
		volatile long _monstersAt;
		volatile long _hardLimitAt;
		volatile long _nextStatus;
		volatile Race _winner;
		volatile String _result;
		volatile ScheduledFuture<?> _task;
		/** Its instance was destroyed: its id may already belong to another instance, which must not be destroyed in its place. */
		volatile boolean _instanceGone;
		/** Fighters the monsters killed, and monsters the fighters killed. */
		final AtomicInteger _monsterKills = new AtomicInteger();
		final AtomicInteger _monstersKilled = new AtomicInteger();
		/** Hits the monsters landed on fighters, and the damage. */
		final AtomicInteger _monsterHits = new AtomicInteger();
		final AtomicLong _monsterDamage = new AtomicLong();

		Match(int id, Tournament tournament, Stage stage, int instanceId)
		{
			_id = id;
			_tournament = tournament;
			_stage = stage;
			_instanceId = instanceId;
			_prepEnd = System.currentTimeMillis() + (RaceWarsConfig.PREP_TIME * 1000L);
		}

		List<Fighter> getAllFighters()
		{
			final List<Fighter> all = new ArrayList<>();
			for (Army army : _armies)
			{
				all.addAll(army._fighters);
			}
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

		Army getArmy(Race race)
		{
			for (Army army : _armies)
			{
				if (army._race == race)
				{
					return army;
				}
			}
			return null;
		}

		String describe()
		{
			final StringBuilder sb = new StringBuilder();
			for (Army army : _armies)
			{
				sb.append(sb.length() > 0 ? " vs " : "").append(SimulationWar.name(army._race));
			}
			return sb.toString();
		}

		String describeAlive()
		{
			final StringBuilder sb = new StringBuilder();
			for (Army army : _armies)
			{
				sb.append(sb.length() > 0 ? ", " : "").append(SimulationWar.name(army._race)).append(' ').append(army.getAlive()).append('/').append(army._fighters.size());
			}
			return sb.toString();
		}

		void screen(String text, int seconds, boolean effect)
		{
			for (Player player : getPlayers())
			{
				sendScreen(player, text, seconds, effect);
			}
		}

		void message(String text)
		{
			for (Player player : getPlayers())
			{
				player.sendMessage(TAG + ": " + text);
			}
		}
	}

	/**
	 * The tournament of one level bracket: the race-vs-race matches, then the final.
	 */
	public static class Tournament
	{
		final int _id;
		final int _minLevel;
		final int _maxLevel;
		final EventMap _map;
		final boolean _simulated;
		/** Every army's size: at least {@link RaceWarsConfig#ARMY_SIZE}, as big as the race with the most players. */
		final int _armySize;
		/** The registered players of each race, in groups (the members of a party of one race stay together). */
		final Map<Race, List<List<Player>>> _groups = new EnumMap<>(Race.class);
		final Map<Integer, PlayerState> _states = new ConcurrentHashMap<>();
		final List<Match> _semifinals = new CopyOnWriteArrayList<>();
		final List<Race> _winners = new CopyOnWriteArrayList<>();
		volatile Match _final;
		volatile boolean _over;

		Tournament(int id, int minLevel, int maxLevel, EventMap map, boolean simulated, int armySize)
		{
			_id = id;
			_minLevel = minLevel;
			_maxLevel = maxLevel;
			_map = map;
			_simulated = simulated;
			_armySize = armySize;
		}

		String getBracketName()
		{
			return _minLevel == _maxLevel ? String.valueOf(_minLevel) : _minLevel + "-" + _maxLevel;
		}
	}

	private enum State
	{
		INACTIVE,
		REGISTRATION
	}

	private final AtomicInteger _nextId = new AtomicInteger(1);
	private final Map<Integer, Match> _matches = new ConcurrentHashMap<>();
	private final Map<Integer, Tournament> _tournaments = new ConcurrentHashMap<>();
	/** Every player and fake player of a match, by object id. */
	private final Map<Integer, Fighter> _fighters = new ConcurrentHashMap<>();
	/** The monsters of every match, by object id. */
	private final Map<Integer, Match> _monsterMatches = new ConcurrentHashMap<>();
	/** The players waiting for the final (their race won its match): object id -> tournament. */
	private final Map<Integer, Tournament> _waiting = new ConcurrentHashMap<>();
	private final Map<Integer, Long> _registered = new ConcurrentHashMap<>();
	private final List<Npc> _managers = new CopyOnWriteArrayList<>();
	private final List<ScheduledFuture<?>> _registrationTasks = new CopyOnWriteArrayList<>();
	private final Set<WorldRegion> _keptRegions = new HashSet<>();
	private volatile State _state = State.INACTIVE;
	private volatile long _registrationEnd;

	protected RaceWarsManager()
	{
	}

	// ---------------------------------------------------------------------------------------------
	// Registration
	// ---------------------------------------------------------------------------------------------

	/**
	 * @return the map of the event, {@code null} if it has no Team vs Team bases for both sides
	 */
	private static EventMap getMap()
	{
		final EventMap map = EventMapData.getInstance().getMap(RaceWarsConfig.MAP);
		return (map != null) && map.isComplete(EventMapType.TVT) ? map : null;
	}

	/**
	 * Opens the registration (the event rotation's turn, or a GM's //racewars open).
	 * @return {@code null} if it opened, else why not
	 */
	public synchronized String openRegistration()
	{
		if (!RaceWarsConfig.ENABLED)
		{
			return "the event is disabled";
		}
		if (_state != State.INACTIVE)
		{
			return "Race Wars registration is already open";
		}
		if (isRunning())
		{
			return "Race Wars matches still go on";
		}
		final String busy = EventRotationManager.getBusyEvent();
		if (busy != null)
		{
			return busy + " is going on (the events never overlap)";
		}
		if (RaceWarsConfig.BRACKETS.isEmpty())
		{
			return "there is no level bracket (RaceWarsBrackets)";
		}
		final EventMap map = getMap();
		if (map == null)
		{
			return "the map " + RaceWarsConfig.MAP + " has no Team vs Team bases for both teams (data/maps, RaceWarsMap)";
		}
		if (!FakePlayerPvpManager.getInstance().isEnabled())
		{
			return "fake players are disabled (FakePvpEnabled): the armies are filled with them";
		}

		_state = State.REGISTRATION;
		_registered.clear();
		_registrationEnd = System.currentTimeMillis() + (RaceWarsConfig.REGISTRATION_TIME * 60000L);
		if (DualboxCheckConfig.DUALBOX_CHECK_MAX_L2EVENT_PARTICIPANTS_PER_IP > 0)
		{
			AntiFeedManager.getInstance().registerEvent(AntiFeedManager.L2EVENT_ID);
			AntiFeedManager.getInstance().clear(AntiFeedManager.L2EVENT_ID);
		}

		for (Location location : org.l2jmobius.gameserver.config.custom.TeamVsTeamConfig.MANAGER_LOCATIONS)
		{
			final Npc manager = Quest.addSpawn(TeamVsTeamManager.MANAGER_NPC_ID, location, false, 0);
			if (manager != null)
			{
				manager.setRandomWalking(false);
				_managers.add(manager);
			}
		}

		for (int warning : WARNINGS)
		{
			if (RaceWarsConfig.REGISTRATION_TIME > warning)
			{
				_registrationTasks.add(ThreadPool.schedule(() -> Broadcast.toAllOnlinePlayers(TAG + ": Registration closes in " + warning + " minute" + (warning > 1 ? "s" : "") + ". " + _registered.size() + " registered so far."), (RaceWarsConfig.REGISTRATION_TIME - warning) * 60000L));
			}
		}
		_registrationTasks.add(ThreadPool.schedule(this::closeRegistration, RaceWarsConfig.REGISTRATION_TIME * 60000L));

		Broadcast.toAllOnlinePlayers(TAG + ": Registration is open for " + RaceWarsConfig.REGISTRATION_TIME + " minutes at the TvT Event Manager in Giran. Your race against another, then the winners all against all, until one race is left standing in the " + map.name() + ". You fight for the race of the class you play. Levels " + describeBrackets() + ".");
		LOGGER.info(getClass().getSimpleName() + ": Registration opened.");
		return null;
	}

	public static String describeBrackets()
	{
		final StringBuilder sb = new StringBuilder();
		for (int[] bracket : RaceWarsConfig.BRACKETS)
		{
			sb.append(sb.length() > 0 ? ", " : "").append(bracket[0] == bracket[1] ? String.valueOf(bracket[0]) : bracket[0] + "-" + bracket[1]);
		}
		return sb.toString();
	}

	/**
	 * @param level a level
	 * @return the index of its bracket in {@link RaceWarsConfig#BRACKETS}, -1 for none
	 */
	public static int getBracket(int level)
	{
		for (int i = 0; i < RaceWarsConfig.BRACKETS.size(); i++)
		{
			final int[] bracket = RaceWarsConfig.BRACKETS.get(i);
			if ((level >= bracket[0]) && (level <= bracket[1]))
			{
				return i;
			}
		}
		return -1;
	}

	/**
	 * @param player a player
	 * @return the race it fights for: the race of the class it plays, a subclass included
	 */
	public static Race getRaceOf(Player player)
	{
		return player.getTemplate().getRace();
	}

	public boolean isRegistrationOpen()
	{
		return _state == State.REGISTRATION;
	}

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
	 * @param race a race
	 * @param bracket a bracket index
	 * @return how many registered players of that race (their class now) are in that bracket now
	 */
	public int getRegisteredCount(Race race, int bracket)
	{
		int count = 0;
		for (int objectId : _registered.keySet())
		{
			final Player player = World.getInstance().getPlayer(objectId);
			if ((player != null) && (getBracket(player.getLevel()) == bracket) && (getRaceOf(player) == race))
			{
				count++;
			}
		}
		return count;
	}

	/**
	 * @return {@code true} while a tournament goes on (a match, or players waiting for the final)
	 */
	public boolean isRunning()
	{
		return !_tournaments.isEmpty();
	}

	/**
	 * @return {@code true} while a match with players goes on, or players wait for their final (a GM's tournament of fake players only doesn't count)
	 */
	public boolean hasPlayersInEvent()
	{
		for (Tournament tournament : _tournaments.values())
		{
			if (!tournament._simulated && !tournament._over)
			{
				return true;
			}
		}
		return false;
	}

	/**
	 * @param player a player
	 * @return why it can't play now, {@code null} if it can
	 */
	public static String getJoinRefusal(Player player)
	{
		if (getBracket(player.getLevel()) < 0)
		{
			return "Level " + player.getLevel() + " is outside every Race Wars bracket (" + describeBrackets() + ").";
		}
		if (!SimulationWar.PLAYABLE.contains(getRaceOf(player)))
		{
			return "Your class has no race of the six.";
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
		final int[] bracket = RaceWarsConfig.BRACKETS.get(getBracket(player.getLevel()));
		return "You are registered for the levels " + bracket[0] + "-" + bracket[1] + " tournament. You fight for the " + SimulationWar.name(getRaceOf(player)) + " race (the race of the class you play when registration closes).";
	}

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
					member.sendMessage(TAG + ": " + leader.getName() + " registered the party. " + result);
				}
			}
			else
			{
				sb.append(member.getName()).append(": ").append(result).append(" ");
				member.sendMessage(TAG + ": You could not be registered: " + result);
			}
		}
		return registered + " of " + party.getMemberCount() + " members are registered. Members of different races fight in their own races' armies." + (sb.length() > 0 ? " " + sb.toString().trim() : "");
	}

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
	 * Registration is over: the players are sorted into brackets and races, and every bracket with players gets its tournament.
	 */
	public synchronized void closeRegistration()
	{
		if (_state != State.REGISTRATION)
		{
			return;
		}
		_state = State.INACTIVE;
		cancelRegistrationTasks();

		final EventMap map = getMap();
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
				player.sendMessage(TAG + ": You can't take part: " + refusal);
				continue;
			}
			players.add(player);
		}
		_registered.clear();

		if (map == null)
		{
			Broadcast.toAllOnlinePlayers(TAG + ": The battlefield is missing, no tournament this time.");
			return;
		}
		if (players.isEmpty())
		{
			Broadcast.toAllOnlinePlayers(TAG + ": Nobody registered, no tournament this time.");
			return;
		}

		// Bracket -> race -> groups: the members of a party in the same bracket and of the same race stay together.
		final Map<Integer, Map<Race, List<List<Player>>>> brackets = new LinkedHashMap<>();
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

			final Map<String, List<Player>> split = new LinkedHashMap<>();
			for (Player member : members)
			{
				placed.add(member);
				split.computeIfAbsent(getBracket(member.getLevel()) + "/" + getRaceOf(member), _ -> new ArrayList<>()).add(member);
			}
			if (split.size() > 1)
			{
				for (Player member : members)
				{
					member.sendMessage(TAG + ": Your party has members of different races or level brackets: each of you fights for their own race in their own bracket.");
				}
			}
			for (List<Player> group : split.values())
			{
				final Player first = group.get(0);
				brackets.computeIfAbsent(getBracket(first.getLevel()), _ -> new LinkedHashMap<>()).computeIfAbsent(getRaceOf(first), _ -> new ArrayList<>()).add(group);
			}
		}

		final StringBuilder started = new StringBuilder();
		for (Map.Entry<Integer, Map<Race, List<List<Player>>>> entry : brackets.entrySet())
		{
			final int[] bracket = RaceWarsConfig.BRACKETS.get(entry.getKey());
			int count = 0;
			for (List<List<Player>> groups : entry.getValue().values())
			{
				for (List<Player> group : groups)
				{
					count += group.size();
				}
			}
			if (count < RaceWarsConfig.MIN_PLAYERS)
			{
				for (List<List<Player>> groups : entry.getValue().values())
				{
					for (List<Player> group : groups)
					{
						for (Player player : group)
						{
							player.sendMessage(TAG + ": Not enough players registered for the levels " + bracket[0] + "-" + bracket[1] + " tournament.");
						}
					}
				}
				continue;
			}

			final Tournament tournament = startTournament(map, bracket, entry.getValue(), false);
			if (tournament != null)
			{
				started.append(started.length() > 0 ? ", " : "").append(tournament.getBracketName());
			}
		}

		if (started.length() > 0)
		{
			Broadcast.toAllOnlinePlayers(TAG + ": The race-vs-race matches start in the " + map.name() + " (levels " + started + "). May your race prevail!");
		}
		else
		{
			Broadcast.toAllOnlinePlayers(TAG + ": Not enough players registered, no tournament this time.");
		}
	}

	// ---------------------------------------------------------------------------------------------
	// Tournament
	// ---------------------------------------------------------------------------------------------

	/**
	 * Starts the tournament of a bracket: the six races drawn into pairs, a match for each pair.
	 * @param map the map
	 * @param bracket its levels
	 * @param groups the players of each race, in groups
	 * @param simulated {@code true} for a GM's tournament of fake players only
	 * @return the tournament, {@code null} if it could not start
	 */
	private Tournament startTournament(EventMap map, int[] bracket, Map<Race, List<List<Player>>> groups, boolean simulated)
	{
		int largest = 0;
		for (List<List<Player>> raceGroups : groups.values())
		{
			int count = 0;
			for (List<Player> group : raceGroups)
			{
				count += group.size();
			}
			largest = Math.max(largest, count);
		}

		final Tournament tournament = new Tournament(_nextId.getAndIncrement(), bracket[0], bracket[1], map, simulated, Math.max(RaceWarsConfig.ARMY_SIZE, largest));
		tournament._groups.putAll(groups);
		for (List<List<Player>> raceGroups : groups.values())
		{
			for (List<Player> group : raceGroups)
			{
				for (Player player : group)
				{
					tournament._states.put(player.getObjectId(), new PlayerState(player));
				}
			}
		}
		_tournaments.put(tournament._id, tournament);

		final List<Race> races = new ArrayList<>(SimulationWar.PLAYABLE);
		Collections.shuffle(races, ThreadLocalRandom.current());
		final StringBuilder draw = new StringBuilder();
		for (int i = 0; (i + 1) < races.size(); i += 2)
		{
			final List<Race> pair = List.of(races.get(i), races.get(i + 1));
			draw.append(draw.length() > 0 ? ", " : "").append(SimulationWar.name(pair.get(0))).append(" vs ").append(SimulationWar.name(pair.get(1)));
			final Match match = startMatch(tournament, Stage.SEMIFINAL, pair);
			if (match != null)
			{
				tournament._semifinals.add(match);
			}
		}

		LOGGER.info(getClass().getSimpleName() + ": Tournament " + tournament._id + " (levels " + tournament.getBracketName() + (simulated ? ", fake players only" : "") + ", armies of " + tournament._armySize + "): " + draw + ".");
		for (Match match : tournament._semifinals)
		{
			match.message("Levels " + tournament.getBracketName() + ": " + draw + ". The winners meet in a final, all against all.");
		}
		if (tournament._semifinals.isEmpty())
		{
			_tournaments.remove(tournament._id);
			return null;
		}
		return tournament;
	}

	/**
	 * Starts a match of a tournament: an instance of the map, an army for each race.
	 * @param tournament the tournament
	 * @param stage a race-vs-race match or the final
	 * @param races its races
	 * @return the match, {@code null} if it could not start
	 */
	private Match startMatch(Tournament tournament, Stage stage, List<Race> races)
	{
		final Instance instance = InstanceManager.getInstance().createDynamicInstance(TeamVsTeamManager.INSTANCE_TEMPLATE_ID);
		if (instance == null)
		{
			return null;
		}

		final EventMap map = tournament._map;
		final Match match = new Match(_nextId.getAndIncrement(), tournament, stage, instance.getId());
		final List<Location> starts = getStarts(map, stage, races.size());
		for (int i = 0; i < races.size(); i++)
		{
			final Team team = races.size() == 2 ? (i == 0 ? Team.BLUE : Team.RED) : Team.NONE;
			match._armies.add(new Army(races.get(i), starts.get(i), team));
		}
		match._barrier.addAll(TeamVsTeamBarrier.build(map, instance.getId()));
		TeamVsTeamManager.setUpDoors(map, instance);
		_matches.put(match._id, match);
		ACTIVE = true;
		keepRegionsAwake(map);

		try
		{
			for (Army army : match._armies)
			{
				buildArmy(match, army, stage == Stage.SEMIFINAL ? tournament._groups.getOrDefault(army._race, List.of()) : getFinalists(tournament, army._race), instance);
			}
		}
		catch (Exception e)
		{
			LOGGER.log(Level.WARNING, getClass().getSimpleName() + ": Problem while setting up match " + match._id + ".", e);
		}

		final String what = stage == Stage.FINAL ? "The final: " + match.describe() + ", all against all" : match.describe();
		for (Player player : match.getPlayers())
		{
			final Fighter fighter = _fighters.get(player.getObjectId());
			sendScreen(player, what + "! You fight for the " + SimulationWar.name(fighter._army._race) + " race. The fight starts in " + RaceWarsConfig.PREP_TIME + " seconds.", 8, true);
			player.sendMessage(TAG + ": " + what + " (levels " + tournament.getBracketName() + ", " + tournament._armySize + " per race). Every other race is attacked without Ctrl. Nobody comes back: the dead stay dead, and a race is out once its last fighter falls. After " + RaceWarsConfig.MONSTER_TIME + " minutes " + RaceWarsConfig.MONSTER_COUNT + " monsters come for everyone, Silent Move and Hide or not.");
		}

		match._task = ThreadPool.scheduleAtFixedRate(() -> tick(match), TICK_MS, TICK_MS);
		final StringBuilder armies = new StringBuilder();
		for (Army army : match._armies)
		{
			armies.append(armies.length() > 0 ? ", " : "").append(SimulationWar.name(army._race)).append(' ').append(army.getPlayerCount()).append(" players / ").append(army._fighters.size());
		}
		LOGGER.info(getClass().getSimpleName() + ": " + (stage == Stage.FINAL ? "Final" : "Match") + " " + match._id + " of tournament " + tournament._id + " (levels " + tournament.getBracketName() + ") started: " + armies + ".");
		return match;
	}

	/**
	 * @return the start points of the armies: the map's first blue and red bases for a race-vs-race match, {@link RaceWarsConfig#FINAL_SPAWNS} for the final
	 */
	private static List<Location> getStarts(EventMap map, Stage stage, int count)
	{
		final List<Location> starts = new ArrayList<>();
		if ((stage == Stage.SEMIFINAL) || (count == 2))
		{
			starts.add(map.blue().get(0));
			starts.add(map.red().get(0));
		}
		else
		{
			starts.addAll(RaceWarsConfig.FINAL_SPAWNS);
		}
		// Not enough spots configured: the others around the middle of the field.
		final Location middle = new Location(map.barrier().x(), map.barrier().y(), map.z());
		while (starts.size() < count)
		{
			final double angle = (2 * Math.PI * starts.size()) / count;
			final int x = middle.getX() + (int) (Math.cos(angle) * map.barrier().radiusX() * 0.7);
			final int y = middle.getY() + (int) (Math.sin(angle) * map.barrier().radiusY() * 0.7);
			starts.add(new Location(x, y, GeoEngine.getInstance().getHeight(x, y, map.z())));
		}

		// Each faces the middle of the field.
		final List<Location> facing = new ArrayList<>();
		for (int i = 0; i < count; i++)
		{
			final Location start = starts.get(i);
			final int heading = (int) Math.round(((Math.atan2(middle.getY() - start.getY(), middle.getX() - start.getX()) * 32768) / Math.PI)) & 0xFFFF;
			facing.add(new Location(start.getX(), start.getY(), start.getZ(), heading));
		}
		return facing;
	}

	/**
	 * @return the players of a race that won its match and still wait for the final, in groups of the parties they fought in
	 */
	private List<List<Player>> getFinalists(Tournament tournament, Race race)
	{
		final List<List<Player>> groups = new ArrayList<>();
		final List<Player> solos = new ArrayList<>();
		for (Map.Entry<Integer, Tournament> entry : _waiting.entrySet())
		{
			if (entry.getValue() != tournament)
			{
				continue;
			}
			final Player player = World.getInstance().getPlayer(entry.getKey());
			if ((player == null) || !player.isOnline() || (getRaceOf(player) != race))
			{
				continue;
			}
			solos.add(player);
		}
		// They come in their parties of the match before.
		final Set<Player> done = new HashSet<>();
		for (Player player : solos)
		{
			if (done.contains(player))
			{
				continue;
			}
			final List<Player> group = new ArrayList<>();
			final Party party = player.getParty();
			if (party != null)
			{
				for (Player member : party.getMembers())
				{
					if (solos.contains(member) && done.add(member))
					{
						group.add(member);
					}
				}
			}
			if (group.isEmpty())
			{
				done.add(player);
				group.add(player);
			}
			groups.add(group);
		}
		return groups;
	}

	/**
	 * Fills an army: its players' groups become its parties, topped up with fake players of the race up to {@link RaceWarsConfig#PARTY_SIZE}, then parties of fake players of the race fill the free places.
	 */
	private void buildArmy(Match match, Army army, List<List<Player>> groups, Instance instance)
	{
		final int size = match._tournament._armySize;
		int players = 0;
		for (List<Player> group : groups)
		{
			players += group.size();
			group.forEach(Player::leaveParty);
		}
		int slots = Math.max(0, size - players);

		// Lone players get a party each while the army has room for parties, the others join the smallest one.
		final int partyCount = Math.max(1, (size + RaceWarsConfig.PARTY_SIZE - 1) / RaceWarsConfig.PARTY_SIZE);
		final List<List<Player>> parties = new ArrayList<>();
		final List<List<Player>> soloParties = new ArrayList<>();
		for (List<Player> group : groups)
		{
			if (group.size() > 1)
			{
				for (int i = 0; i < group.size(); i += 9)
				{
					parties.add(new ArrayList<>(group.subList(i, Math.min(group.size(), i + 9))));
				}
				continue;
			}
			final Player solo = group.get(0);
			if ((parties.size() + soloParties.size()) < partyCount)
			{
				final List<Player> party = new ArrayList<>();
				party.add(solo);
				soloParties.add(party);
				continue;
			}
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

		final Random random = ThreadLocalRandom.current();
		for (List<Player> members : parties)
		{
			final Player leader = members.get(0);
			for (Player player : members)
			{
				enterPlayer(match, army, player, instance);
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

			// The fake players it lacks, in the slots its players don't fill.
			final int wanted = Math.min(slots, Math.max(0, RaceWarsConfig.PARTY_SIZE - members.size()));
			if (wanted <= 0)
			{
				continue;
			}
			final List<SimRole> roles = new ArrayList<>(SimulationComposer.getRoles(members.size() + wanted));
			for (Player player : members)
			{
				roles.remove(TeamVsTeamManager.getRole(player));
			}
			while (roles.size() > wanted)
			{
				roles.remove(roles.size() - 1);
			}
			final List<Npc> spawned = spawnFakes(match, army, roles, instance, random);
			slots -= wanted;
			if (!spawned.isEmpty())
			{
				army._parties.add(FakePartyManager.getInstance().createEventParty(leader, spawned));
			}
		}

		// Parties of fake players for the rest.
		while (slots > 0)
		{
			final int partySize = Math.min(RaceWarsConfig.PARTY_SIZE, slots);
			slots -= partySize;
			final List<Npc> spawned = spawnFakes(match, army, SimulationComposer.getRoles(partySize), instance, random);
			if (spawned.size() > 1)
			{
				army._parties.add(FakePartyManager.getInstance().createSimulationParty(spawned, FakeBrain.A));
			}
		}
	}

	private List<Npc> spawnFakes(Match match, Army army, List<SimRole> roles, Instance instance, Random random)
	{
		final List<Npc> spawned = new ArrayList<>();
		final Set<String> supports = new HashSet<>();
		final Tournament tournament = match._tournament;
		for (SimRole role : roles)
		{
			final SimulationWar.Recruit recruit = SimulationWar.pick(army._race, role, random, supports);
			if (recruit == null)
			{
				continue;
			}
			final int level = Rnd.get(tournament._minLevel, tournament._maxLevel);
			final Location location = QuestSpawnUtil.findPoint(army._start, 20, Math.max(40, RaceWarsConfig.SPAWN_RADIUS), true, instance.getId());
			final Npc fake = FakePlayerPvpManager.getInstance().spawnRaceWarsFighter(recruit.build(), level, SimulationWar.name(army._race), match._id, army._team, getNameColor(army._race), location.getX(), location.getY(), location.getZ(), instance.getId());
			if (fake == null)
			{
				continue;
			}
			fake.setHeading(army._start.getHeading());
			final Fighter fighter = new Fighter(fake, match, army, recruit.role());
			army._fighters.add(fighter);
			_fighters.put(fake.getObjectId(), fighter);
			showScore(fighter);
			spawned.add(fake);
		}
		return spawned;
	}

	/**
	 * Takes a player into a match: its race's name color, at its army's start point, buffed and healed.
	 */
	private void enterPlayer(Match match, Army army, Player player, Instance instance)
	{
		final Fighter fighter = new Fighter(player, match, army, TeamVsTeamManager.getRole(player));
		army._fighters.add(fighter);
		_fighters.put(player.getObjectId(), fighter);
		_waiting.remove(player.getObjectId());
		match._tournament._states.computeIfAbsent(player.getObjectId(), _ -> new PlayerState(player));

		player.setRegisteredOnEvent(false);
		player.setOnEvent(true);
		// No "To Village": the dead stay on the field until their race's match ends.
		player.setCanRevive(false);
		player.setInvul(false);
		if (player.isDead())
		{
			player.doRevive();
		}
		if (player.isSitting())
		{
			player.standUp();
		}
		player.getAppearance().setNameColor(getNameColor(army._race));
		player.setTeam(army._team);
		player.setTitle(scoreTitle(fighter));
		player.teleToLocation(army._start, instance.getId(), RaceWarsConfig.SPAWN_RADIUS);
		buffAndHeal(player);
		player.broadcastUserInfo();
	}

	private static void buffAndHeal(Player player)
	{
		BuffTemplateManager.getInstance().applyTvtTemplate(player);
		player.setCurrentHpMp(player.getMaxHp(), player.getMaxMp());
		player.setCurrentCp(player.getMaxCp());
	}

	/**
	 * @param race a race
	 * @return the name color of its fighters (BGR)
	 */
	public static int getNameColor(Race race)
	{
		return switch (race)
		{
			case HUMAN -> 0xFFFFFF; // White.
			case ELF -> 0x7FFF7F; // Light green.
			case DARK_ELF -> 0xFF7FBF; // Violet.
			case ORC -> 0x3F3FFF; // Red.
			case DWARF -> 0x00CFFF; // Gold.
			case KAMAEL -> 0xFFCF7F; // Light blue.
			default -> 0xFFFFFF;
		};
	}

	private static String scoreTitle(Fighter fighter)
	{
		return SimulationWar.name(fighter._army._race) + " K:" + fighter._kills;
	}

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

	// ---------------------------------------------------------------------------------------------
	// The match
	// ---------------------------------------------------------------------------------------------

	private void tick(Match match)
	{
		try
		{
			final long now = System.currentTimeMillis();
			if (match._phase == Phase.ENDED)
			{
				return;
			}

			final Instance instance = InstanceManager.getInstance().getInstance(match._instanceId);
			if ((instance != null) && !match._tournament._map.doors().isEmpty())
			{
				TeamVsTeamManager.keepDoors(match._tournament._map, instance);
			}

			for (Fighter fighter : match.getAllFighters())
			{
				if (fighter.isPlayer() && !checkPlayerStillIn(fighter))
				{
					continue;
				}
				final Creature creature = fighter._creature;
				if (creature.isDead() || creature.isTeleporting())
				{
					continue;
				}

				// Nobody leaves the start points before the fight, nor the field during it.
				if (match._phase == Phase.PREP)
				{
					if (creature.calculateDistance2D(fighter._army._start) > RaceWarsConfig.BASE_RADIUS)
					{
						sendTo(fighter, fighter._army._start, RaceWarsConfig.SPAWN_RADIUS);
						if (fighter.isPlayer())
						{
							sendScreen(creature.asPlayer(), "The fight hasn't started yet!", 4, false);
						}
					}
				}
				else if (TeamVsTeamBarrier.isOutside(match._tournament._map.barrier(), creature, BARRIER_MARGIN))
				{
					sendTo(fighter, TeamVsTeamBarrier.getInside(match._tournament._map.barrier(), creature), 0);
					if (fighter.isPlayer())
					{
						sendScreen(creature.asPlayer(), "You can't leave the battlefield!", 4, false);
					}
				}
			}

			switch (match._phase)
			{
				case PREP:
				{
					if (now >= match._prepEnd)
					{
						startFight(match);
					}
					break;
				}
				case FIGHT:
				case MONSTERS:
				{
					// The monsters follow the fighters anywhere on the field: their spawn goes with them (nothing leashes them home), and one that went idle wakes up.
					for (int objectId : match._monsters)
					{
						if ((World.getInstance().findObject(objectId) instanceof Npc monster) && !monster.isDead())
						{
							if (monster.getSpawn() != null)
							{
								monster.getSpawn().setXYZ(monster.getX(), monster.getY(), monster.getZ());
							}
							if (!monster.hasAI() || (monster.getAI().getIntention() == Intention.IDLE))
							{
								monster.getAI().setIntention(Intention.ACTIVE);
							}
						}
					}

					// A tournament whose players all left is over.
					if (!match._tournament._simulated && !hasPlayers(match._tournament))
					{
						endTournament(match._tournament, "every player left");
						return;
					}

					final List<Army> standing = new ArrayList<>();
					for (Army army : match._armies)
					{
						if (army.getAlive() > 0)
						{
							standing.add(army);
						}
					}

					if (standing.size() <= 1)
					{
						// The last race standing, or, everyone dead, the one whose last fighter fell last.
						Army winner = standing.isEmpty() ? null : standing.get(0);
						if (winner == null)
						{
							for (Army army : match._armies)
							{
								if ((army._lastDeath > 0) && ((winner == null) || (army._lastDeath > winner._lastDeath)))
								{
									winner = army;
								}
							}
							// Nobody alive and nobody fell: its fighters are gone some other way. No result rather than a prize for nothing.
							if (winner == null)
							{
								LOGGER.warning(getClass().getSimpleName() + ": Match " + match._id + " has nobody left and no death: it ends without a winner.");
								endTournament(match._tournament, "the match could not go on");
								return;
							}
						}
						endMatch(match, winner, standing.isEmpty() ? "everyone fell; the " + SimulationWar.name(winner._race) + " fell last" : "last race standing");
						return;
					}

					if ((match._phase == Phase.FIGHT) && (now >= match._monstersAt))
					{
						spawnMonsters(match);
					}
					else if ((match._phase == Phase.MONSTERS) && (now >= match._hardLimitAt))
					{
						// Time is up: the most fighters alive, then the most HP and CP left, then chance.
						Army winner = null;
						for (Army army : standing)
						{
							if ((winner == null) || (army.getAlive() > winner.getAlive()) || ((army.getAlive() == winner.getAlive()) && (army.getHealthLeft() > winner.getHealthLeft())))
							{
								winner = army;
							}
						}
						endMatch(match, winner, "time is up, most fighters alive (" + match.describeAlive() + ")");
						return;
					}

					if (now >= match._nextStatus)
					{
						match._nextStatus = now + STATUS_INTERVAL;
						final long left = match._phase == Phase.FIGHT ? (match._monstersAt - now) / 1000 : (match._hardLimitAt - now) / 1000;
						match.screen("Alive: " + match.describeAlive() + (match._phase == Phase.FIGHT ? " | monsters in " : " | time up in ") + (left / 60) + ":" + String.format("%02d", left % 60), 5, false);
					}
					break;
				}
				default:
				{
					break;
				}
			}
		}
		catch (Exception e)
		{
			LOGGER.log(Level.WARNING, getClass().getSimpleName() + ": Problem in match " + match._id + ".", e);
		}
	}

	private boolean hasPlayers(Tournament tournament)
	{
		for (Fighter fighter : _fighters.values())
		{
			if (fighter.isPlayer() && (fighter._match._tournament == tournament))
			{
				return true;
			}
		}
		for (Tournament waiting : _waiting.values())
		{
			if (waiting == tournament)
			{
				return true;
			}
		}
		return false;
	}

	private static void sendTo(Fighter fighter, Location location, int offset)
	{
		final Creature creature = fighter._creature;
		creature.abortAttack();
		creature.abortCast();
		creature.teleToLocation(location, fighter._match._instanceId, offset);
		if (!creature.isPlayer() && !creature.isDead() && creature.hasAI() && (creature.getAI() instanceof AttackableAI ai))
		{
			ai.startAITask();
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
		leave(fighter, !player.isOnline());
		return false;
	}

	private void startFight(Match match)
	{
		final long now = System.currentTimeMillis();
		match._phase = Phase.FIGHT;
		match._fightStart = now;
		match._monstersAt = now + (RaceWarsConfig.MONSTER_TIME * 60000L);
		match._nextStatus = now + STATUS_INTERVAL;
		match.screen("The fight has begun! The last race standing wins.", 5, true);
		for (Fighter fighter : match.getAllFighters())
		{
			if (!fighter.isPlayer() && (fighter._creature instanceof Npc fake) && !fake.isDead())
			{
				fake.setRunning();
				fake.getAI().setIntention(Intention.ACTIVE);
			}
		}
	}

	// ---------------------------------------------------------------------------------------------
	// The monsters
	// ---------------------------------------------------------------------------------------------

	/**
	 * No winner after {@link RaceWarsConfig#MONSTER_TIME} minutes: {@link RaceWarsConfig#MONSTER_COUNT} monsters of the bracket's level come all over the field, champions of {@link RaceWarsConfig#MONSTER_TIER} that go for every fighter (see {@link #isPrey}).
	 */
	private void spawnMonsters(Match match)
	{
		final long now = System.currentTimeMillis();
		match._phase = Phase.MONSTERS;
		match._hardLimitAt = now + (RaceWarsConfig.HARD_LIMIT * 60000L);
		final Tournament tournament = match._tournament;
		final List<NpcTemplate> kinds = pickMonsters(tournament._minLevel, tournament._maxLevel);
		if (kinds.isEmpty())
		{
			LOGGER.warning(getClass().getSimpleName() + ": No monster of levels " + tournament.getBracketName() + " to send into match " + match._id + ".");
		}

		final EventMap map = tournament._map;
		final GeoEngine geo = GeoEngine.getInstance();
		int spawned = 0;
		for (int i = 0; (i < RaceWarsConfig.MONSTER_COUNT) && !kinds.isEmpty(); i++)
		{
			// A spot of the field by chance, inside the fence (moved short of a wall in the way from the middle).
			final double angle = Rnd.nextDouble() * 2 * Math.PI;
			final double distance = Math.sqrt(Rnd.nextDouble()) * MONSTER_FIELD_SHARE;
			final int x = map.barrier().x() + (int) (Math.cos(angle) * map.barrier().radiusX() * distance);
			final int y = map.barrier().y() + (int) (Math.sin(angle) * map.barrier().radiusY() * distance);
			final Location spot = geo.getValidLocation(map.barrier().x(), map.barrier().y(), geo.getHeight(map.barrier().x(), map.barrier().y(), map.z()), x, y, geo.getHeight(x, y, map.z()), match._instanceId);
			final NpcTemplate kind = kinds.get(i % kinds.size());
			final Npc npc = Quest.addSpawn(kind.getId(), spot.getX(), spot.getY(), spot.getZ(), Rnd.get(65536), false, 0, false, match._instanceId);
			if ((npc == null) || !npc.isAttackable())
			{
				if (npc != null)
				{
					npc.deleteMe();
				}
				continue;
			}

			final Attackable monster = npc.asAttackable();
			if (RaceWarsConfig.MONSTER_TIER > 0)
			{
				monster.setChampionTier(RaceWarsConfig.MONSTER_TIER);
			}
			monster.setCurrentHp(monster.getMaxHp());
			monster.setCurrentMp(monster.getMaxMp());
			monster.setSeeThroughSilentMove(true);
			final Spawn spawn = monster.getSpawn();
			if (spawn != null)
			{
				spawn.stopRespawn();
				spawn.setChaseRange(Integer.MAX_VALUE / 2);
			}
			match._monsters.add(monster.getObjectId());
			_monsterMatches.put(monster.getObjectId(), match);
			// Spawned with no player in view, it went idle and dropped its AI before it was known as a match's monster: getAI gives it a new one, which now stays active.
			monster.getAI().setIntention(Intention.ACTIVE);
			spawned++;
		}

		final StringBuilder names = new StringBuilder();
		for (NpcTemplate kind : kinds)
		{
			names.append(names.length() > 0 ? ", " : "").append(kind.getName()).append(" (").append(kind.getLevel()).append(')');
		}
		match.screen("No race fell in " + RaceWarsConfig.MONSTER_TIME + " minutes: the monsters are coming! They see through Silent Move and Hide.", 8, true);
		match.message(spawned + " monsters came: " + names + ". " + RaceWarsConfig.HARD_LIMIT + " minutes from now, the race with the most fighters alive wins.");
		LOGGER.info(getClass().getSimpleName() + ": Match " + match._id + ": " + spawned + " monsters came (" + names + "). Alive: " + match.describeAlive() + ".");
	}

	/**
	 * @return {@link RaceWarsConfig#MONSTER_KINDS} kinds of ordinary monsters of the levels (or the closest levels there are) that live somewhere in the world, picked by chance
	 */
	private static List<NpcTemplate> pickMonsters(int minLevel, int maxLevel)
	{
		for (int widen = 0; widen <= 10; widen += 2)
		{
			final int min = minLevel - widen;
			final int max = maxLevel + widen;
			final List<NpcTemplate> templates = NpcData.getInstance().getTemplates(template -> template.isType("Monster") && (template.getLevel() >= min) && (template.getLevel() <= max) && !template.isQuestMonster() && !template.isUndying() && (template.getFakePlayerPvpProfile() == null) && (template.getFakePlayerInfo() == null) && (SpawnTable.getInstance().getSpawnCount(template.getId()) > 0));
			if (templates.size() >= Math.min(RaceWarsConfig.MONSTER_KINDS, 2))
			{
				Collections.shuffle(templates, ThreadLocalRandom.current());
				return new ArrayList<>(templates.subList(0, Math.min(RaceWarsConfig.MONSTER_KINDS, templates.size())));
			}
		}
		return List.of();
	}

	/**
	 * @param npc a creature
	 * @return {@code true} if it is a monster of a Race Wars match
	 */
	public static boolean isEventMonster(Creature npc)
	{
		return ACTIVE && (npc != null) && getInstance()._monsterMatches.containsKey(npc.getObjectId());
	}

	/**
	 * Whether a monster of a match goes for a creature (see {@code AttackableAI#isAggressiveTowards}): every living fighter of its match (a summon of one too) within {@link RaceWarsConfig#MONSTER_AGGRO_RANGE}, Silent Move and Hide or not; nothing else.
	 * @param monster a monster
	 * @param target a creature it sees
	 * @return {@code null} if the monster is no Race Wars monster (the usual rules decide), else whether it goes for the creature
	 */
	public static Boolean isPrey(Attackable monster, Creature target)
	{
		if (!ACTIVE)
		{
			return null;
		}
		final Match match = getInstance()._monsterMatches.get(monster.getObjectId());
		if (match == null)
		{
			return null;
		}
		if ((match._phase == Phase.ENDED) || target.isAlikeDead() || target.isInvul())
		{
			return false;
		}
		final Fighter fighter = getFighter(target);
		if ((fighter == null) || (fighter._match != match) || !fighter.isAlive())
		{
			return false;
		}
		if (target.isPlayer() && !target.asPlayer().getAccessLevel().canTakeAggro())
		{
			return false;
		}
		return monster.isInsideRadius3D(target, RaceWarsConfig.MONSTER_AGGRO_RANGE);
	}

	/**
	 * @param monster a monster
	 * @param target a creature it fights
	 * @return {@code true} if it is a Race Wars monster and the target a fighter of its match: it keeps on it even when the target hides (Hide makes a player invisible)
	 */
	public static boolean seesThroughHide(Creature monster, Creature target)
	{
		if (!ACTIVE || (monster == null) || (target == null))
		{
			return false;
		}
		final Match match = getInstance()._monsterMatches.get(monster.getObjectId());
		final Fighter fighter = match != null ? getFighter(target) : null;
		return (fighter != null) && (fighter._match == match);
	}

	// ---------------------------------------------------------------------------------------------
	// Deaths and the end of a match
	// ---------------------------------------------------------------------------------------------

	/**
	 * Called by {@code Creature#doDie} while a match goes on (see {@link TeamEventRules#onDeath}).
	 * @param victim who died
	 * @param killer who killed it
	 */
	public void onDeath(Creature victim, Creature killer)
	{
		// A monster of a match: it is gone.
		final Match monsterMatch = _monsterMatches.remove(victim.getObjectId());
		if (monsterMatch != null)
		{
			monsterMatch._monsters.remove(victim.getObjectId());
			monsterMatch._monstersKilled.incrementAndGet();
			return;
		}

		final Fighter dead = _fighters.get(victim.getObjectId());
		if ((dead == null) || dead._dead || ((dead._match._phase != Phase.FIGHT) && (dead._match._phase != Phase.MONSTERS)))
		{
			return;
		}

		final Match match = dead._match;
		dead._dead = true;
		dead._army._lastDeath = System.currentTimeMillis();
		if (isEventMonster(killer))
		{
			match._monsterKills.incrementAndGet();
		}
		final Fighter slayer = getFighter(killer);
		final String by;
		if ((slayer != null) && (slayer._match == match) && (slayer._army != dead._army))
		{
			slayer._kills++;
			showScore(slayer);
			if (slayer.isPlayer())
			{
				sendScreen(slayer._creature.asPlayer(), "You killed " + victim.getName() + "! " + match.describeAlive(), 3, false);
			}
			by = slayer._creature.getName() + " (" + SimulationWar.name(slayer._army._race) + ")";
		}
		else
		{
			by = killer != null ? killer.getName() : "?";
		}

		final int alive = dead._army.getAlive();
		if (dead.isPlayer())
		{
			dead._creature.sendMessage(TAG + ": You fell to " + by + ". There is no coming back: you wait here until your race's match ends. " + SimulationWar.name(dead._army._race) + " fighters left: " + alive + ".");
		}
		if (alive == 0)
		{
			match.screen("The " + SimulationWar.name(dead._army._race) + " race has fallen!", 5, true);
		}
	}

	/**
	 * Called by the combat log for every hit while a match goes on: counts what the monsters of a match deal to its fighters.
	 * @param target who was hit
	 * @param attacker who hit
	 * @param damage how much
	 */
	public void onDamage(Creature target, Creature attacker, double damage)
	{
		final Match match = _monsterMatches.get(attacker.getObjectId());
		if ((match != null) && (getFighter(target) != null))
		{
			match._monsterHits.incrementAndGet();
			match._monsterDamage.addAndGet((long) damage);
		}
	}

	/**
	 * A match is decided.
	 * @param match the match
	 * @param winner the race that won it, {@code null} for none (stopped)
	 * @param why how it was decided
	 */
	private void endMatch(Match match, Army winner, String why)
	{
		synchronized (match)
		{
			if (match._phase == Phase.ENDED)
			{
				return;
			}
			match._phase = Phase.ENDED;
		}
		if (match._task != null)
		{
			match._task.cancel(false);
			match._task = null;
		}

		match._winner = winner != null ? winner._race : null;
		final Tournament tournament = match._tournament;
		final String result = winner == null ? "The match is over without a winner." : (match._stage == Stage.FINAL ? "The " + SimulationWar.name(winner._race) + " race wins Race Wars (levels " + tournament.getBracketName() + ")!" : "The " + SimulationWar.name(winner._race) + " race wins and goes to the final!");
		match._result = result + (winner != null ? " (" + why + ")" : "");

		// Everyone stops; the monsters leave.
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
		removeMonsters(match);

		match.screen(result, 8, true);
		match.message(match._result);
		LOGGER.info(getClass().getSimpleName() + ": " + (match._stage == Stage.FINAL ? "Final " : "Match ") + match._id + " of tournament " + tournament._id + " (levels " + tournament.getBracketName() + ", " + match.describe() + ") ended: " + match._result + " Alive: " + match.describeAlive() + "." + (match._hardLimitAt > 0 ? " Monsters hit fighters " + match._monsterHits.get() + " times (" + match._monsterDamage.get() + " damage), killed " + match._monsterKills.get() + ", and lost " + match._monstersKilled.get() + "." : ""));

		if (match._stage == Stage.FINAL)
		{
			if (winner != null)
			{
				rewardWinners(match, winner);
				Broadcast.toAllOnlinePlayers(TAG + ": " + result);
			}
			ThreadPool.schedule(() ->
			{
				cleanup(match, null);
				finishTournament(tournament);
			}, END_DELAY);
			return;
		}

		if (winner != null)
		{
			tournament._winners.add(winner._race);
		}
		ThreadPool.schedule(() ->
		{
			cleanup(match, winner);
			onSemifinalDone(tournament);
		}, END_DELAY);
	}

	private void removeMonsters(Match match)
	{
		for (int objectId : match._monsters)
		{
			_monsterMatches.remove(objectId);
			if (World.getInstance().findObject(objectId) instanceof Npc monster)
			{
				monster.deleteMe();
			}
		}
		match._monsters.clear();
	}

	/**
	 * Each player of the race that won the final gets {@link RaceWarsConfig#WIN_REWARD}, alive or not.
	 */
	private void rewardWinners(Match match, Army winner)
	{
		final Skill firework = CommonSkill.FIREWORK.getSkill();
		final StringBuilder rewards = new StringBuilder();
		for (ItemHolder reward : RaceWarsConfig.WIN_REWARD)
		{
			rewards.append(rewards.length() > 0 ? ", " : "").append(reward.getCount()).append(" of item ").append(reward.getId());
		}
		for (Fighter fighter : winner._fighters)
		{
			if (!fighter.isPlayer())
			{
				continue;
			}
			final Player player = fighter._creature.asPlayer();
			if (!player.isOnline())
			{
				continue;
			}
			if ((firework != null) && !player.isDead())
			{
				player.broadcastPacket(new MagicSkillUse(player, player, firework.getId(), firework.getLevel(), firework.getHitTime(), firework.getReuseDelay()));
				player.broadcastSocialAction(3);
			}
			for (ItemHolder reward : RaceWarsConfig.WIN_REWARD)
			{
				player.addItem(ItemProcessType.REWARD, reward.getId(), reward.getCount(), null, true);
			}
			LOGGER.info(getClass().getSimpleName() + ": Final " + match._id + ": " + player.getName() + " (" + SimulationWar.name(winner._race) + ", " + fighter._kills + " kills) gets " + rewards + ".");
		}
	}

	/**
	 * After a match: its fake players leave the world, its players go home, but the players of the race that goes to the final wait in the field, healed and out of harm, until the final takes them.
	 * @param match the match
	 * @param advancing the army that goes to the final, {@code null} for none
	 */
	private void cleanup(Match match, Army advancing)
	{
		try
		{
			for (Army army : match._armies)
			{
				for (FakePlayerParty party : army._parties)
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
				army._parties.clear();
			}

			for (Fighter fighter : match.getAllFighters())
			{
				_fighters.remove(fighter._creature.getObjectId(), fighter);
				if (!fighter.isPlayer())
				{
					removeFake((Npc) fighter._creature);
					continue;
				}

				final Player player = fighter._creature.asPlayer();
				if ((fighter._army == advancing) && player.isOnline() && (player.getInstanceId() == match._instanceId))
				{
					// It waits for the final, back on its feet.
					if (player.isDead())
					{
						player.doRevive();
					}
					player.setInvul(true);
					player.setCurrentHpMp(player.getMaxHp(), player.getMaxMp());
					player.setCurrentCp(player.getMaxCp());
					_waiting.put(player.getObjectId(), match._tournament);
					sendScreen(player, "Your race goes to the final! Wait here for the other matches.", 8, true);
					continue;
				}
				restorePlayer(player, match._tournament, true);
			}
		}
		catch (Exception e)
		{
			LOGGER.log(Level.WARNING, getClass().getSimpleName() + ": Problem while cleaning up match " + match._id + ".", e);
		}
		finally
		{
			TeamVsTeamBarrier.remove(match._barrier);
			removeMonsters(match);
			_matches.remove(match._id);
			// A field with players waiting for the final stays until the final takes them.
			if (!hasWaitingIn(match))
			{
				destroyInstance(match);
			}
			updateActive();
		}
	}

	/**
	 * Destroys the instance of a match, once: an instance id is given out again once its instance is gone.
	 */
	private static void destroyInstance(Match match)
	{
		synchronized (match)
		{
			if (match._instanceGone)
			{
				return;
			}
			match._instanceGone = true;
		}
		InstanceManager.getInstance().destroyInstance(match._instanceId);
	}

	private boolean hasWaitingIn(Match match)
	{
		for (int objectId : _waiting.keySet())
		{
			final Player player = World.getInstance().getPlayer(objectId);
			if ((player != null) && (player.getInstanceId() == match._instanceId))
			{
				return true;
			}
		}
		return false;
	}

	/**
	 * A race-vs-race match was cleaned up: once all of them are, the final starts a little later.
	 */
	private void onSemifinalDone(Tournament tournament)
	{
		if (tournament._over)
		{
			return;
		}
		for (Match semifinal : tournament._semifinals)
		{
			if (_matches.containsKey(semifinal._id))
			{
				return; // Another one still goes on.
			}
		}
		if (tournament._final != null)
		{
			return;
		}

		final List<Race> finalists = new ArrayList<>(tournament._winners);
		if (finalists.size() < 2)
		{
			// One race left (or none): no final to fight.
			if (finalists.size() == 1)
			{
				Broadcast.toAllOnlinePlayers(TAG + ": The " + SimulationWar.name(finalists.get(0)) + " race wins Race Wars (levels " + tournament.getBracketName() + ") without a final!");
				for (Map.Entry<Integer, Tournament> entry : _waiting.entrySet())
				{
					final Player player = entry.getValue() == tournament ? World.getInstance().getPlayer(entry.getKey()) : null;
					if (player != null)
					{
						for (ItemHolder reward : RaceWarsConfig.WIN_REWARD)
						{
							player.addItem(ItemProcessType.REWARD, reward.getId(), reward.getCount(), null, true);
						}
					}
				}
			}
			finishTournament(tournament);
			return;
		}

		final StringBuilder names = new StringBuilder();
		for (Race race : finalists)
		{
			names.append(names.length() > 0 ? ", " : "").append(SimulationWar.name(race));
		}
		for (Map.Entry<Integer, Tournament> entry : _waiting.entrySet())
		{
			final Player player = entry.getValue() == tournament ? World.getInstance().getPlayer(entry.getKey()) : null;
			if (player != null)
			{
				sendScreen(player, "The final starts in " + RaceWarsConfig.FINAL_DELAY + " seconds: " + names + ", all against all!", 8, true);
			}
		}
		LOGGER.info(getClass().getSimpleName() + ": Tournament " + tournament._id + ": the final (" + names + ") starts in " + RaceWarsConfig.FINAL_DELAY + " s.");
		ThreadPool.schedule(() -> startFinal(tournament, finalists), RaceWarsConfig.FINAL_DELAY * 1000L);
	}

	private synchronized void startFinal(Tournament tournament, List<Race> finalists)
	{
		if (tournament._over || (tournament._final != null))
		{
			return;
		}

		final Match finalMatch = startMatch(tournament, Stage.FINAL, finalists);
		tournament._final = finalMatch;
		// The fields the waiting players stood in go now that they are moved.
		for (Match semifinal : tournament._semifinals)
		{
			destroyInstance(semifinal);
		}

		// Whoever still waits (it could not be placed) goes home.
		for (Map.Entry<Integer, Tournament> entry : new ArrayList<>(_waiting.entrySet()))
		{
			if (entry.getValue() != tournament)
			{
				continue;
			}
			_waiting.remove(entry.getKey());
			final Player player = World.getInstance().getPlayer(entry.getKey());
			if (player != null)
			{
				restorePlayer(player, tournament, true);
			}
		}

		if (finalMatch == null)
		{
			finishTournament(tournament);
		}
	}

	private void finishTournament(Tournament tournament)
	{
		tournament._over = true;
		for (Map.Entry<Integer, Tournament> entry : new ArrayList<>(_waiting.entrySet()))
		{
			if (entry.getValue() == tournament)
			{
				_waiting.remove(entry.getKey());
				final Player player = World.getInstance().getPlayer(entry.getKey());
				if (player != null)
				{
					restorePlayer(player, tournament, true);
				}
			}
		}
		for (Match semifinal : tournament._semifinals)
		{
			destroyInstance(semifinal);
		}
		_tournaments.remove(tournament._id);
		updateActive();
		LOGGER.info(getClass().getSimpleName() + ": Tournament " + tournament._id + " (levels " + tournament.getBracketName() + ") is over.");
	}

	/**
	 * Ends a tournament at once, without a winner (every player left, or a GM stopped it).
	 */
	private void endTournament(Tournament tournament, String why)
	{
		if (tournament._over)
		{
			return;
		}
		tournament._over = true;
		LOGGER.info(getClass().getSimpleName() + ": Tournament " + tournament._id + " is stopped: " + why + ".");
		final List<Match> matches = new ArrayList<>(tournament._semifinals);
		if (tournament._final != null)
		{
			matches.add(tournament._final);
		}
		for (Match match : matches)
		{
			if (_matches.containsKey(match._id))
			{
				synchronized (match)
				{
					match._phase = Phase.ENDED;
				}
				if (match._task != null)
				{
					match._task.cancel(false);
				}
				match.message("The event is over: " + why + ".");
				cleanup(match, null);
			}
		}
		finishTournament(tournament);
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
			LOGGER.log(Level.WARNING, RaceWarsManager.class.getSimpleName() + ": Could not remove " + fake.getName() + ".", e);
		}
	}

	/**
	 * Gives a player back its state from before the event.
	 * @param player the player
	 * @param tournament its tournament
	 * @param teleport {@code true} to send it back where it was
	 */
	private void restorePlayer(Player player, Tournament tournament, boolean teleport)
	{
		final PlayerState state = tournament._states.remove(player.getObjectId());
		player.setInvul(false);
		player.setTeam(Team.NONE);
		if (state != null)
		{
			player.getAppearance().setNameColor(state.nameColor);
			player.setTitle(state.title);
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

		final Instance instance = InstanceManager.getInstance().getInstance(player.getInstanceId());
		if (instance != null)
		{
			instance.removePlayer(player.getObjectId());
		}
		if (teleport && player.isOnline() && (state != null))
		{
			final Location back = state.returnLocation;
			player.teleToLocation(back.getX(), back.getY(), back.getZ(), back.getHeading(), 0, 0);
		}
		player.broadcastUserInfo();
	}

	/**
	 * A player leaves a match before its end (logged off, left the field). Before the fight a fake player of its race takes its place; once it started, it is out like one that fell.
	 * @param fighter the player's fighter
	 * @param loggingOff {@code true} if it logs off: it logs in again where it was before the event
	 */
	private void leave(Fighter fighter, boolean loggingOff)
	{
		if (!_fighters.remove(fighter._creature.getObjectId(), fighter))
		{
			return;
		}
		final Match match = fighter._match;
		final Player player = fighter._creature.asPlayer();
		final boolean beforeFight = match._phase == Phase.PREP;
		if (beforeFight)
		{
			fighter._army._fighters.remove(fighter);
		}
		else
		{
			// Out like one that fell (it still counts in its army: its last moment counts too).
			fighter._dead = true;
			fighter._army._lastDeath = System.currentTimeMillis();
		}

		// The fake players of its party stay, as a party of their own.
		for (FakePlayerParty party : fighter._army._parties)
		{
			if (party.getHost() == player)
			{
				final List<Npc> fakes = new ArrayList<>(party.getFakes());
				FakePartyManager.getInstance().disbandEventParty(party);
				fighter._army._parties.remove(party);
				if (fakes.size() > 1)
				{
					fighter._army._parties.add(FakePartyManager.getInstance().createSimulationParty(fakes, FakeBrain.A));
				}
				break;
			}
		}

		final PlayerState state = match._tournament._states.get(player.getObjectId());
		restorePlayer(player, match._tournament, false);
		if (loggingOff && (state != null))
		{
			player.setInstanceId(0);
			player.setXYZInvisible(state.returnLocation.getX(), state.returnLocation.getY(), state.returnLocation.getZ());
		}

		if (beforeFight)
		{
			final Instance instance = InstanceManager.getInstance().getInstance(match._instanceId);
			if (instance != null)
			{
				spawnFakes(match, fighter._army, List.of(fighter._role), instance, ThreadLocalRandom.current());
			}
		}
		match.message(player.getName() + " left the match.");
	}

	/**
	 * Called by {@code Player#deleteMe}: a registered player is taken off the list, one in a match or waiting for the final leaves the event and logs in again where it was before.
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
			return;
		}

		final Tournament waiting = _waiting.remove(player.getObjectId());
		if (waiting != null)
		{
			final PlayerState state = waiting._states.get(player.getObjectId());
			restorePlayer(player, waiting, false);
			if (state != null)
			{
				player.setInstanceId(0);
				player.setXYZInvisible(state.returnLocation.getX(), state.returnLocation.getY(), state.returnLocation.getZ());
			}
		}
	}

	/**
	 * @param player a player
	 * @return the title to save for it: its own, not the score it shows in the event
	 */
	public static String getOwnTitle(Player player)
	{
		if (!ACTIVE && getInstance()._waiting.isEmpty())
		{
			return null;
		}
		for (Tournament tournament : getInstance()._tournaments.values())
		{
			final PlayerState state = tournament._states.get(player.getObjectId());
			if (state != null)
			{
				return state.title;
			}
		}
		return null;
	}

	/**
	 * Stops everything: the registration and every tournament, without rewards (a GM's //racewars stop).
	 * @return what to tell the GM
	 */
	public synchronized String stop()
	{
		final StringBuilder sb = new StringBuilder();
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
			Broadcast.toAllOnlinePlayers(TAG + ": The event was canceled.");
			sb.append("Registration canceled. ");
		}
		int stopped = 0;
		for (Tournament tournament : new ArrayList<>(_tournaments.values()))
		{
			endTournament(tournament, "stopped by a GM");
			stopped++;
		}
		return sb.append(stopped).append(" tournament(s) stopped.").toString();
	}

	private void updateActive()
	{
		ACTIVE = !_matches.isEmpty();
		if (_tournaments.isEmpty() && _matches.isEmpty())
		{
			releaseRegions();
		}
	}

	private void keepRegionsAwake(EventMap map)
	{
		final int margin = 2000;
		final int step = 1 << World.SHIFT_BY;
		synchronized (_keptRegions)
		{
			for (int x = map.minX() - margin; x <= (map.maxX() + margin); x += step)
			{
				for (int y = map.minY() - margin; y <= (map.maxY() + margin); y += step)
				{
					final WorldRegion region = World.getInstance().getRegion(x, y, map.z());
					if ((region != null) && !region.isKeptActive())
					{
						region.keepActive();
						_keptRegions.add(region);
					}
				}
			}
		}
	}

	private void releaseRegions()
	{
		synchronized (_keptRegions)
		{
			for (WorldRegion region : _keptRegions)
			{
				region.releaseKeepActive();
			}
			_keptRegions.clear();
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
		final RaceWarsManager manager = getInstance();
		final Fighter fighter = manager._fighters.get(creature.getObjectId());
		if (fighter != null)
		{
			return fighter;
		}
		final Creature owner = CombatLogManager.getPvpOwner(creature);
		return (owner != null) && (owner != creature) ? manager._fighters.get(owner.getObjectId()) : null;
	}

	/**
	 * @return how two creatures stand to each other in the event: a match's monsters follow the usual rules with everyone ({@link TeamVsTeamManager.Relation#NONE})
	 */
	public static TeamVsTeamManager.Relation getRelation(Creature a, Creature b)
	{
		if (!ACTIVE || isEventMonster(a) || isEventMonster(b))
		{
			return TeamVsTeamManager.Relation.NONE;
		}
		final Fighter fa = getFighter(a);
		final Fighter fb = getFighter(b);
		if ((fa == null) && (fb == null))
		{
			return TeamVsTeamManager.Relation.NONE;
		}
		if ((fa == null) || (fb == null) || (fa._match != fb._match))
		{
			return TeamVsTeamManager.Relation.STRANGER;
		}
		return fa._army == fb._army ? TeamVsTeamManager.Relation.ALLY : TeamVsTeamManager.Relation.ENEMY;
	}

	/**
	 * @return {@code true} if one of them is in a match and the other isn't an enemy of a fight going on: the own race even with Ctrl, anyone before or after the fight
	 */
	public static boolean isForbidden(Creature attacker, Creature target)
	{
		if (!ACTIVE)
		{
			return false;
		}
		final TeamVsTeamManager.Relation relation = getRelation(attacker, target);
		return (relation != TeamVsTeamManager.Relation.NONE) && !isFightOn(attacker, relation);
	}

	/**
	 * @return {@code true} if they are fighters of different races in a fight going on: attacked without Ctrl
	 */
	public static boolean isEnemy(Creature attacker, Creature target)
	{
		return ACTIVE && isFightOn(attacker, getRelation(attacker, target));
	}

	/**
	 * @return {@code true} if they are in a match, whatever their relation (the event's rules decide whether they fight)
	 */
	public static boolean isInvolved(Creature a, Creature b)
	{
		return ACTIVE && (getRelation(a, b) != TeamVsTeamManager.Relation.NONE);
	}

	private static boolean isFightOn(Creature attacker, TeamVsTeamManager.Relation relation)
	{
		if (relation != TeamVsTeamManager.Relation.ENEMY)
		{
			return false;
		}
		final Fighter fighter = getFighter(attacker);
		return (fighter != null) && ((fighter._match._phase == Phase.FIGHT) || (fighter._match._phase == Phase.MONSTERS));
	}

	/**
	 * @param creature a creature
	 * @return {@code true} if it fell in a match going on: nothing brings it back before its race's match ends
	 */
	public static boolean isEliminated(Creature creature)
	{
		if (!ACTIVE || (creature == null))
		{
			return false;
		}
		final Fighter fighter = getInstance()._fighters.get(creature.getObjectId());
		return (fighter != null) && (fighter._dead || (creature.isDead() && (fighter._match._phase != Phase.PREP)));
	}

	/**
	 * @param fake a fake player of a match
	 * @return the phase of its match, {@code null} if it is in none
	 */
	public Phase getPhase(Npc fake)
	{
		final Fighter fighter = _fighters.get(fake.getObjectId());
		return fighter != null ? fighter._match._phase : null;
	}

	/**
	 * The enemy a fake player engages: the closest living fighter of another race it sees within {@link #SIGHT_RANGE} (or that is very close), else a monster of the match close to it.
	 * @param fake a fake player of a match
	 * @return the enemy, {@code null} if none
	 */
	public Creature findEnemyInSight(Npc fake)
	{
		final Fighter fighter = _fighters.get(fake.getObjectId());
		if ((fighter == null) || ((fighter._match._phase != Phase.FIGHT) && (fighter._match._phase != Phase.MONSTERS)))
		{
			return null;
		}

		Creature best = null;
		double bestDistance = Double.MAX_VALUE;
		for (Army army : fighter._match._armies)
		{
			if (army == fighter._army)
			{
				continue;
			}
			for (Fighter enemy : army._fighters)
			{
				final Creature creature = enemy._creature;
				if (!enemy.isAlive() || creature.isInvisible() || (creature.getInstanceId() != fake.getInstanceId()))
				{
					continue;
				}
				final double distance = fake.calculateDistance2D(creature);
				if ((distance < bestDistance) && (distance <= SIGHT_RANGE) && ((distance <= CLOSE_ENEMY_RANGE) || GeoEngine.getInstance().canSeeTarget(fake, creature)))
				{
					best = creature;
					bestDistance = distance;
				}
			}
		}
		if (best != null)
		{
			return best;
		}

		for (int objectId : fighter._match._monsters)
		{
			if ((World.getInstance().findObject(objectId) instanceof Npc monster) && !monster.isDead())
			{
				final double distance = fake.calculateDistance2D(monster);
				if ((distance < bestDistance) && (distance <= MONSTER_ENGAGE_RANGE))
				{
					best = monster;
					bestDistance = distance;
				}
			}
		}
		return best;
	}

	/**
	 * Where a fake player with no enemy in sight goes: the middle of the closest living group of another race (a party's living members), else the middle of the field.
	 * @param fake a fake player of a match
	 * @return where to go, {@code null} for nowhere
	 */
	public Location getMarchTarget(Npc fake)
	{
		final Fighter fighter = _fighters.get(fake.getObjectId());
		if ((fighter == null) || ((fighter._match._phase != Phase.FIGHT) && (fighter._match._phase != Phase.MONSTERS)))
		{
			return null;
		}

		Location best = null;
		double bestDistance = Double.MAX_VALUE;
		for (Army army : fighter._match._armies)
		{
			if (army == fighter._army)
			{
				continue;
			}
			for (Fighter enemy : army._fighters)
			{
				if (!enemy.isAlive())
				{
					continue;
				}
				final double distance = fake.calculateDistance2D(enemy._creature);
				if (distance < bestDistance)
				{
					best = enemy._creature.getLocation();
					bestDistance = distance;
				}
			}
		}
		if (best != null)
		{
			return best;
		}
		final EventMap map = fighter._match._tournament._map;
		return new Location(map.barrier().x(), map.barrier().y(), GeoEngine.getInstance().getHeight(map.barrier().x(), map.barrier().y(), map.z()));
	}

	// ---------------------------------------------------------------------------------------------
	// GM tools
	// ---------------------------------------------------------------------------------------------

	/**
	 * A tournament of fake players only (//racewars simulate), for testing: the bracket of the level, no registration.
	 * @param level a level of the bracket
	 * @return what to tell the GM
	 */
	public synchronized String simulate(int level)
	{
		final int index = getBracket(level);
		if (index < 0)
		{
			return "Level " + level + " is in no bracket (" + describeBrackets() + ").";
		}
		final EventMap map = getMap();
		if (map == null)
		{
			return "The map " + RaceWarsConfig.MAP + " has no Team vs Team bases for both teams.";
		}
		if (!FakePlayerPvpManager.getInstance().isEnabled())
		{
			return "Fake players are disabled (FakePvpEnabled).";
		}
		final Tournament tournament = startTournament(map, RaceWarsConfig.BRACKETS.get(index), Map.of(), true);
		if (tournament == null)
		{
			return "The tournament could not start.";
		}
		final StringBuilder sb = new StringBuilder("Tournament " + tournament._id + " (levels " + tournament.getBracketName() + ", fake players only, " + tournament._armySize + " per race): ");
		for (Match match : tournament._semifinals)
		{
			sb.append(match.describe()).append(" (match ").append(match._id).append(", instance ").append(match._instanceId).append("); ");
		}
		return sb.toString().trim();
	}

	/**
	 * @return the status lines for a GM
	 */
	public List<String> getStatus()
	{
		final List<String> lines = new ArrayList<>();
		if (isRegistrationOpen())
		{
			lines.add("Registration open, " + getRegistrationSecondsLeft() + " s left, " + _registered.size() + " registered.");
		}
		for (Tournament tournament : _tournaments.values())
		{
			lines.add("Tournament " + tournament._id + " (levels " + tournament.getBracketName() + (tournament._simulated ? ", fake players only" : "") + ", " + tournament._armySize + " per race), finalists so far: " + tournament._winners + ".");
			final List<Match> matches = new ArrayList<>(tournament._semifinals);
			if (tournament._final != null)
			{
				matches.add(tournament._final);
			}
			for (Match match : matches)
			{
				final long now = System.currentTimeMillis();
				final String time = match._phase == Phase.PREP ? ((match._prepEnd - now) / 1000) + " s to the fight" : match._phase == Phase.FIGHT ? ((match._monstersAt - now) / 1000) + " s to the monsters" : match._phase == Phase.MONSTERS ? ((match._hardLimitAt - now) / 1000) + " s to the time limit, " + match._monsters.size() + " monsters (killed " + match._monsterKills.get() + " fighters)" : "ended";
				lines.add("  " + (match._stage == Stage.FINAL ? "Final " : "Match ") + match._id + " (instance " + match._instanceId + "): " + match.describeAlive() + " - " + match._phase + ", " + time + (match._result != null ? " - " + match._result : ""));
				if (match._phase == Phase.MONSTERS)
				{
					lines.add("    Monsters: " + describeMonsters(match));
				}
			}
		}
		if (lines.isEmpty())
		{
			lines.add("Race Wars: nothing going on.");
		}
		return lines;
	}

	/**
	 * @return what the monsters of a match do, for a GM: how many have each AI intention, how many have a target and hate someone, and how many fighters are within their aggro range
	 */
	private static String describeMonsters(Match match)
	{
		final Map<String, Integer> intentions = new LinkedHashMap<>();
		int targeting = 0;
		int hating = 0;
		int inRange = 0;
		for (int objectId : match._monsters)
		{
			if (!(World.getInstance().findObject(objectId) instanceof Attackable monster) || monster.isDead())
			{
				continue;
			}
			intentions.merge(monster.hasAI() ? monster.getAI().getIntention().name() : "NO_AI", 1, Integer::sum);
			if (monster.getTarget() != null)
			{
				targeting++;
			}
			if (monster.getMostHated() != null)
			{
				hating++;
			}
			for (Fighter fighter : match.getAllFighters())
			{
				if (fighter.isAlive() && monster.isInsideRadius3D(fighter._creature, RaceWarsConfig.MONSTER_AGGRO_RANGE))
				{
					inRange++;
					break;
				}
			}
		}
		return intentions + ", " + targeting + " with a target, " + hating + " hating someone, " + inRange + " with a fighter in range, " + match._monsterHits.get() + " hits on fighters (" + match._monsterDamage.get() + " damage), killed " + match._monsterKills.get() + " fighters, lost " + match._monstersKilled.get();
	}

	private static void sendScreen(Player player, String text, int seconds, boolean effect)
	{
		player.sendPacket(new ExShowScreenMessage(text, ExShowScreenMessage.TOP_CENTER, seconds * 1000, 0, true, effect));
	}

	public static RaceWarsManager getInstance()
	{
		return SingletonHolder.INSTANCE;
	}

	private static class SingletonHolder
	{
		protected static final RaceWarsManager INSTANCE = new RaceWarsManager();
	}
}
