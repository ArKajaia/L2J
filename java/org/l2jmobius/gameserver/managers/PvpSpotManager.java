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
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.l2jmobius.commons.threads.ThreadPool;
import org.l2jmobius.commons.util.Rnd;
import org.l2jmobius.gameserver.ai.AttackableAI;
import org.l2jmobius.gameserver.ai.CreatureAI;
import org.l2jmobius.gameserver.ai.Intention;
import org.l2jmobius.gameserver.config.custom.PvpSpotsConfig;
import org.l2jmobius.gameserver.data.xml.FakePlayerPvpData;
import org.l2jmobius.gameserver.geoengine.GeoEngine;
import org.l2jmobius.gameserver.model.Location;
import org.l2jmobius.gameserver.model.World;
import org.l2jmobius.gameserver.model.WorldObject;
import org.l2jmobius.gameserver.model.WorldRegion;
import org.l2jmobius.gameserver.model.actor.Creature;
import org.l2jmobius.gameserver.model.actor.Npc;
import org.l2jmobius.gameserver.model.actor.Player;
import org.l2jmobius.gameserver.model.actor.holders.npc.FakePlayerHolder;
import org.l2jmobius.gameserver.model.actor.holders.npc.FakePlayerPvpBuild;
import org.l2jmobius.gameserver.model.actor.holders.npc.FakePlayerPvpBuild.SkillCategory;
import org.l2jmobius.gameserver.model.actor.holders.npc.FakePlayerPvpProfile;
import org.l2jmobius.gameserver.model.actor.instance.FakePlayerPvpServitor;
import org.l2jmobius.gameserver.model.actor.templates.NpcTemplate;
import org.l2jmobius.gameserver.model.zone.ZoneForm;
import org.l2jmobius.gameserver.model.zone.ZoneId;
import org.l2jmobius.gameserver.model.zone.type.PvpSpotZone;
import org.l2jmobius.gameserver.network.serverpackets.ExShowScreenMessage;

/**
 * The PvP spots (zones of type {@link PvpSpotZone}, see config/Custom/PvpSpots.ini): open-world areas made for fighting, where everyone is flagged and nobody gets karma. This manager keeps them alive:
 * <ul>
 * <li>fake players of the spot's levels come there only to fight, like players teleporting in: alone, or as a small group of the same clan, now and then a stronger one ({@link #arrive}). No healer or buffer ever comes;</li>
 * <li>how many fight there drifts between PvpSpotFightersMin and PvpSpotFightersMax, and the ones that leave or die are replaced;</li>
 * <li>a fake player killed there often comes back a while later, like a player that restarted in town, and goes after its killer first ({@link #onFighterDecay});</li>
 * <li>it keeps the kill streaks of everyone fighting there (players and fake players, {@link #onDeath}): the longest one makes the PvP leader of the spot, who shows it (a title and the hero aura for a fake player), and whom the others go for
 * ({@link #updateLeader}).</li>
 * </ul>
 * How the fake players fight there is in {@link org.l2jmobius.gameserver.ai.FakePlayerPvpAI}.
 */
public class PvpSpotManager
{
	private static final Logger LOGGER = Logger.getLogger(PvpSpotManager.class.getName());

	/** Milliseconds between two looks at the spots, and before the first one (the world is still loading). */
	private static final long TICK = 3000;
	private static final long START_DELAY = 30000;
	/** How far from the center of a spot one lands when teleporting there. */
	private static final int LANDING_SCATTER = 250;
	/** How far apart the members of a group land. */
	private static final int GROUP_SCATTER = 80;
	/** How long a fake player that came back from town looks for its killer. */
	private static final long GRUDGE_TIME = 300000;
	/** How often the number of fighters a spot wants changes: min, max milliseconds. */
	private static final int DRIFT_MIN = 60000;
	private static final int DRIFT_MAX = 240000;
	/** A fake player that should have left this long ago, and still couldn't read its scroll, logs off. */
	private static final long LEAVE_GRACE = 60000;
	/** A player this far from a spot (or in it) wakes its fake players up. */
	private static final int WATCH_RANGE = 2500;
	/** A killer this far from a spot it isn't in still gets the kill (an archer shooting in from the edge). */
	private static final int KILL_CREDIT_RANGE = 1500;
	/** Kill streaks the players of the spot are told about. */
	private static final int[] STREAK_ANNOUNCES =
	{
		5,
		7,
		10,
		15,
		20,
		25,
		30,
		40,
		50
	};

	/** What a fake player of a spot says when someone attacks it. */
	public static final String[] TAUNTS_ATTACKED =
	{
		"?",
		"lol",
		"come",
		"ok",
		"u sure?",
		"bad idea",
		"here we go",
		"lets go",
		"try me",
		"cmon",
		"wrong target",
		"u again?",
		"2v1?",
		"lol ok",
		"big mistake",
		"come here",
		"hit harder",
		"is that all?",
		"ok lets dance",
		"from behind? lol"
	};
	/** What it says when the one it fought goes down. */
	public static final String[] TAUNTS_KILL =
	{
		"gg",
		"ez",
		"next",
		"whos next",
		"sit",
		"rip",
		"stay down",
		"too easy",
		"bye",
		"gg wp",
		"lol",
		"go res",
		"cya in town",
		"one more",
		"outplayed",
		"need pots?",
		"nice try",
		"come back",
		"sleep",
		"ty for the pvp"
	};
	/** What it says from the ground. */
	public static final String[] TAUNTS_DEATH =
	{
		"gg",
		"lag",
		"wtf",
		"lucky",
		"nice one",
		"rematch",
		"brb",
		"omg",
		"crit...",
		"2v1 gj",
		"ill be back",
		"no pots",
		"ugh",
		"rip",
		"ok gj",
		"not again",
		"next time",
		"so lucky",
		"back in a sec",
		"fml"
	};
	/** What it may say when it picks someone to fight. */
	public static final String[] TAUNTS_ENGAGE =
	{
		"u",
		"come",
		"pvp?",
		"fight me",
		"lets go",
		"u next",
		"found u",
		"hi :)",
		"ur mine",
		"come here"
	};
	/** What it may say when, back from town, it finds its killer. */
	public static final String[] TAUNTS_RETURN =
	{
		"im back",
		"round 2",
		"again",
		"remember me?",
		"found u",
		"rebuffed, lets go",
		"now im ready",
		"revenge time",
		"u again, good",
		"this time no lag"
	};
	/** What the others say about a new leader (%s is its name). */
	private static final String[] TAUNTS_LEADER =
	{
		"focus %s",
		"kill %s",
		"%s again...",
		"everyone on %s",
		"%s is too strong lol",
		"who is this %s",
		"get %s",
		"someone stop %s",
		"%s go die",
		"%s ur next"
	};
	/** What the others say when the leader goes down (%s is its name). */
	private static final String[] TAUNTS_LEADER_DOWN =
	{
		"finally %s down",
		"gg %s",
		"%s not so strong now",
		"rip %s",
		"bye %s",
		"%s sit"
	};

	/**
	 * A spot: its zone and its fights.
	 */
	private static class Spot
	{
		final int _zoneId;
		/** Its fake players, alive or lying dead. */
		final Set<Npc> _fighters = ConcurrentHashMap.newKeySet();
		/** Kills without dying of everyone fighting there (object id -> kills). */
		final Map<Integer, Integer> _streaks = new ConcurrentHashMap<>();
		/** Its fake players on their way back from town. */
		final AtomicInteger _returning = new AtomicInteger();
		/** The strong ones among them: their place is kept. */
		final AtomicInteger _returningElites = new AtomicInteger();
		volatile int _wanted;
		volatile long _nextArrival;
		volatile long _nextDrift;
		volatile int _leaderId;
		volatile int _leaderStreak;
		volatile String _leaderName;

		Spot(int zoneId)
		{
			_zoneId = zoneId;
		}
	}

	/**
	 * What the .pvp window and the admin page show of a spot.
	 */
	public static class SpotInfo
	{
		private final int _zoneId;
		private final String _name;
		private final int _minLevel;
		private final int _maxLevel;
		private final int _fakePlayers;
		private final int _players;
		private final String _leader;
		private final int _leaderStreak;
		private final int _wanted;

		SpotInfo(int zoneId, String name, int minLevel, int maxLevel, int fakePlayers, int players, String leader, int leaderStreak, int wanted)
		{
			_zoneId = zoneId;
			_name = name;
			_minLevel = minLevel;
			_maxLevel = maxLevel;
			_fakePlayers = fakePlayers;
			_players = players;
			_leader = leader;
			_leaderStreak = leaderStreak;
			_wanted = wanted;
		}

		public int getZoneId()
		{
			return _zoneId;
		}

		public String getName()
		{
			return _name;
		}

		public int getMinLevel()
		{
			return _minLevel;
		}

		public int getMaxLevel()
		{
			return _maxLevel;
		}

		/**
		 * @return how many fake players fight there (alive)
		 */
		public int getFakePlayers()
		{
			return _fakePlayers;
		}

		/**
		 * @return how many players are there
		 */
		public int getPlayers()
		{
			return _players;
		}

		/**
		 * @return the name of its PvP leader, {@code null} if it has none
		 */
		public String getLeader()
		{
			return _leader;
		}

		public int getLeaderStreak()
		{
			return _leaderStreak;
		}

		/**
		 * @return how many fake players it wants now
		 */
		public int getWanted()
		{
			return _wanted;
		}
	}

	private final Map<Integer, Spot> _spots = new ConcurrentHashMap<>();
	/** Fake players of the spots on their way back from town, by name. */
	private final Map<String, ScheduledFuture<?>> _returns = new ConcurrentHashMap<>();

	protected PvpSpotManager()
	{
		if (!PvpSpotsConfig.ENABLED)
		{
			LOGGER.info(getClass().getSimpleName() + ": Disabled.");
			return;
		}

		ThreadPool.scheduleAtFixedRate(this::tick, START_DELAY, TICK);
		LOGGER.info(getClass().getSimpleName() + ": " + ZoneManager.getInstance().getAllZones(PvpSpotZone.class).size() + " PvP spots" + (PvpSpotsConfig.FAKE_PLAYERS ? ", fake players fight there." : "."));
	}

	// ---------------------------------------------------------------------------------------------
	// The spots
	// ---------------------------------------------------------------------------------------------

	/**
	 * Every {@link #TICK}: keeps everyone in the spots flagged, sends off the fake players whose time is up, brings in new ones and crowns the leaders.
	 */
	private void tick()
	{
		final long now = System.currentTimeMillis();
		for (PvpSpotZone zone : ZoneManager.getInstance().getAllZones(PvpSpotZone.class))
		{
			try
			{
				tick(zone, _spots.computeIfAbsent(zone.getId(), Spot::new), now);
			}
			catch (Exception e)
			{
				LOGGER.log(Level.WARNING, getClass().getSimpleName() + ": Problem with PvP spot " + zone.getName() + ".", e);
			}
		}
	}

	private void tick(PvpSpotZone zone, Spot spot, long now)
	{
		// Deleted without going through their decay (should not happen).
		spot._fighters.removeIf(fake -> !fake.isSpawned() && fake.isDecayed());

		// The flag of a player there lasts until they leave.
		for (Player player : zone.getPlayersInside())
		{
			if (!player.isDead() && (player.getPvpFlagLasts() != Long.MAX_VALUE))
			{
				PvpSpotZone.keepFlag(player);
			}
		}

		// Like monsters around a player, its fake players fight while a player is there to see it, and stand by meanwhile. The ones that came while nobody was around (their AI asleep in an empty region) are woken up.
		final boolean watched = isWatched(zone);
		
		// Its fake players stay flagged, and leave once their time is up (reading a scroll, see FakePlayerPvpAI#thinkLeave). A strong one lying dead or on its way back keeps its place.
		int elites = spot._returningElites.get();
		int alive = 0;
		for (Npc fake : spot._fighters)
		{
			final FakePlayerPvpProfile profile = fake.getTemplate().getFakePlayerPvpProfile();
			if (profile.isElite())
			{
				elites++;
			}

			if (fake.isDead())
			{
				continue;
			}
			alive++;

			if (watched)
			{
				wake(fake);
			}
			
			FakePlayerPvpManager.flagForSpot(fake);
			final boolean fighting = FakePlayerPvpManager.isFightingPvp(fake);
			if ((profile.getLeaveTime() == 0) && (now >= profile.getSpotStayUntil()) && !fighting)
			{
				profile.setLeaveTime(now);
			}
			else if ((profile.getLeaveTime() > 0) && (now > (profile.getLeaveTime() + LEAVE_GRACE)) && !fighting && !fake.isCastingNow())
			{
				fake.deleteMe(); // Logs off.
			}
		}

		if (PvpSpotsConfig.FAKE_PLAYERS && FakePlayerPvpManager.getInstance().isEnabled())
		{
			// How many it wants drifts, like the crowd of a real spot.
			if (now >= spot._nextDrift)
			{
				spot._wanted = spot._wanted == 0 ? Rnd.get(PvpSpotsConfig.FIGHTERS_MIN, PvpSpotsConfig.FIGHTERS_MAX) : Math.max(PvpSpotsConfig.FIGHTERS_MIN, Math.min(PvpSpotsConfig.FIGHTERS_MAX, spot._wanted + Rnd.get(-2, 2)));
				spot._nextDrift = now + Rnd.get(DRIFT_MIN, DRIFT_MAX);
			}

			// Newcomers, faster while the spot is still filling up. The ones on their way back count, but below the minimum alive newcomers come quickly.
			final int present = spot._fighters.size() + spot._returning.get();
			final int room = Math.max(spot._wanted - present, PvpSpotsConfig.FIGHTERS_MIN - alive);
			if ((room > 0) && (now >= spot._nextArrival))
			{
				arrive(zone, spot, room, elites, now);
				spot._nextArrival = now + ((present < (spot._wanted / 2)) || (alive < PvpSpotsConfig.FIGHTERS_MIN) ? Rnd.get(1000, 3000) : (Rnd.get(PvpSpotsConfig.ARRIVAL_DELAY_MIN, PvpSpotsConfig.ARRIVAL_DELAY_MAX) * 1000L));
			}
		}

		updateLeader(zone, spot);
	}

	/**
	 * @param zone a spot
	 * @return {@code true} if a player is in it or close enough to see it
	 */
	private static boolean isWatched(PvpSpotZone zone)
	{
		final Location center = zone.getZone().getCenterPoint();
		final WorldRegion region = World.getInstance().getRegion(center.getX(), center.getY(), center.getZ());
		if (region == null)
		{
			return false;
		}
		
		for (WorldRegion surrounding : region.getSurroundingRegions())
		{
			for (WorldObject object : surrounding.getVisibleObjects())
			{
				if (object.isPlayer() && (object.getInstanceId() == 0) && !object.asPlayer().isInvisible() && (zone.isInsideZone(object) || (zone.getDistanceToZone(object) < WATCH_RANGE)))
				{
					return true;
				}
			}
		}
		return false;
	}
	
	/**
	 * Makes sure the AI of a fake player of a spot thinks: one that arrived while nobody was around got its AI task stopped (an inactive region), and a player coming by only wakes the idle ones.
	 * @param fake the fake player
	 */
	private static void wake(Npc fake)
	{
		final CreatureAI ai = fake.getAI();
		if (ai instanceof AttackableAI)
		{
			if (ai.getIntention() == Intention.IDLE)
			{
				ai.setIntention(Intention.ACTIVE);
			}
			else
			{
				((AttackableAI) ai).startAITask();
			}
		}
	}
	
	/**
	 * Fake players come to the spot like players teleporting in: alone, or as a group of the same clan (they fight side by side), and now and then a stronger one.
	 * @param zone the spot
	 * @param spot its state
	 * @param room how many more it wants
	 * @param elites how many strong ones it already has
	 * @param now the current time
	 */
	private void arrive(PvpSpotZone zone, Spot spot, int room, int elites, long now)
	{
		final boolean elite = (elites < PvpSpotsConfig.MAX_ELITES) && (Rnd.get(100) < PvpSpotsConfig.ELITE_CHANCE);
		final int size = !elite && (room >= 2) && FakeClanManager.getInstance().isEnabled() && (Rnd.get(100) < PvpSpotsConfig.GROUP_CHANCE) ? Rnd.get(2, Math.min(PvpSpotsConfig.GROUP_MAX_SIZE, room)) : 1;
		final Location landing = getLandingPoint(zone);
		final int level = pickLevel(zone);
		Npc first = null;
		for (int i = 0; i < size; i++)
		{
			final FakePlayerPvpBuild build = FakePlayerPvpData.getInstance().getRandomBuild(PvpSpotManager::isFighterBuild);
			if (build == null)
			{
				return;
			}

			// The strong one is about the level of the others (its gear makes it strong), friends are about the same level.
			final int memberLevel = clamp(zone, i == 0 ? (elite ? level + Rnd.get(0, 2) : level) : level + Rnd.get(-2, 2));
			final Location point = i == 0 ? landing : scatter(zone, landing, GROUP_SCATTER);
			final Npc fake = FakePlayerPvpManager.getInstance().spawnSpotFighter(build, memberLevel, elite && (i == 0), zone.getId(), point.getX(), point.getY(), point.getZ(), first);
			if (fake == null)
			{
				continue;
			}

			fake.getTemplate().getFakePlayerPvpProfile().setSpotStayUntil(now + (Rnd.get(PvpSpotsConfig.STAY_TIME_MIN, PvpSpotsConfig.STAY_TIME_MAX) * 1000L));
			spot._fighters.add(fake);
			if (first == null)
			{
				first = fake;

				// A group is a clan that came to fight together.
				if ((size > 1) && !FakeClanManager.getInstance().joinAnyClan(fake))
				{
					return;
				}
			}
		}
	}

	/**
	 * @param build a build
	 * @return {@code true} if it may come to a spot: one that fights on its own, no healer or buffer
	 */
	private static boolean isFighterBuild(FakePlayerPvpBuild build)
	{
		return !build.isSupport() && (build.getWeight() > 0) && build.getSkills(SkillCategory.PARTY_HEAL).isEmpty() && build.getSkills(SkillCategory.RESURRECT).isEmpty();
	}

	/**
	 * @param zone a spot
	 * @return the level of a newcomer: often about the level of a player who is there, so players find opponents of their level, otherwise any level of the spot
	 */
	private static int pickLevel(PvpSpotZone zone)
	{
		if (Rnd.get(100) < PvpSpotsConfig.MATCH_PLAYER_LEVEL_CHANCE)
		{
			final List<Player> players = new ArrayList<>();
			for (Player player : zone.getPlayersInside())
			{
				if (!player.isInvisible() && (player.getLevel() >= (zone.getSpotMinLevel() - PvpSpotsConfig.MATCH_PLAYER_LEVEL_RANGE)) && (player.getLevel() <= (zone.getSpotMaxLevel() + PvpSpotsConfig.MATCH_PLAYER_LEVEL_RANGE)))
				{
					players.add(player);
				}
			}

			if (!players.isEmpty())
			{
				final int range = PvpSpotsConfig.MATCH_PLAYER_LEVEL_RANGE;
				return clamp(zone, players.get(Rnd.get(players.size())).getLevel() + Rnd.get(-range, range));
			}
		}

		return Rnd.get(zone.getSpotMinLevel(), zone.getSpotMaxLevel());
	}

	private static int clamp(PvpSpotZone zone, int level)
	{
		return Math.max(zone.getSpotMinLevel(), Math.min(zone.getSpotMaxLevel(), level));
	}

	/**
	 * @param zone a spot
	 * @return the ground at its center
	 */
	private static Location getCenter(PvpSpotZone zone)
	{
		final ZoneForm form = zone.getZone();
		final Location center = form.getCenterPoint();
		return new Location(center.getX(), center.getY(), GeoEngine.getInstance().getHeight(center.getX(), center.getY(), (form.getLowZ() + form.getHighZ()) / 2));
	}

	/**
	 * @param zone a spot
	 * @return where one lands teleporting to it: around its center, on ground it can walk from
	 */
	private static Location getLandingPoint(PvpSpotZone zone)
	{
		return scatter(zone, getCenter(zone), LANDING_SCATTER);
	}

	/**
	 * @param zone a spot
	 * @param origin a point of it
	 * @param radius how far from it
	 * @return a point up to {@code radius} from {@code origin} that can be walked to from it, inside the spot ({@code origin} if none is found)
	 */
	private static Location scatter(PvpSpotZone zone, Location origin, int radius)
	{
		for (int i = 0; i < 8; i++)
		{
			final double angle = Rnd.nextDouble() * 2 * Math.PI;
			final int distance = Rnd.get(radius / 4, radius);
			final Location point = GeoEngine.getInstance().getValidLocation(origin.getX(), origin.getY(), origin.getZ(), origin.getX() + (int) (Math.cos(angle) * distance), origin.getY() + (int) (Math.sin(angle) * distance), origin.getZ(), 0);
			if (zone.isInsideZone(point.getX(), point.getY(), point.getZ()))
			{
				return point;
			}
		}

		return origin;
	}

	// ---------------------------------------------------------------------------------------------
	// Deaths, streaks and the leader
	// ---------------------------------------------------------------------------------------------

	/**
	 * Called when a player or a roaming fake player dies: in a spot, its kill streak ends, its killer's grows, the leader may change, and a fake player of the spot remembers its killer.
	 * @param victim the one that died
	 * @param killer its killer
	 */
	public void onDeath(Creature victim, Creature killer)
	{
		if (!PvpSpotsConfig.ENABLED || (victim == null) || (killer == null))
		{
			return;
		}

		final PvpSpotZone zone = getSpotOf(victim);
		if (zone == null)
		{
			return;
		}

		final Spot spot = _spots.computeIfAbsent(zone.getId(), Spot::new);
		final Integer victimStreak = spot._streaks.remove(victim.getObjectId());
		final boolean wasLeader = victim.getObjectId() == spot._leaderId;

		// The kill goes to the player (not its summon), or to the fake player (not its servitor).
		final Creature credited = getCredited(killer);
		if ((credited != null) && (credited != victim) && (credited.isPlayer() || credited.isPvpFakePlayer()) && (zone.isInsideZone(credited) || (credited.calculateDistance2D(victim) < KILL_CREDIT_RANGE)))
		{
			final int streak = spot._streaks.merge(credited.getObjectId(), 1, Integer::sum);
			if (wasLeader && (victimStreak != null))
			{
				tell(zone, credited.getName() + " ended the reign of " + victim.getName() + " (" + victimStreak + " kills in a row)!");
				chatter(zone, TAUNTS_LEADER_DOWN, victim.getName(), victim, 2);
			}
			else if (isStreakAnnounced(streak) && (credited.getObjectId() != spot._leaderId))
			{
				tell(zone, credited.getName() + " is on a killing spree: " + streak + " kills in a row!");
			}

			// Back from town, it goes after its killer first.
			if (victim.isPvpFakePlayer())
			{
				final FakePlayerPvpProfile profile = victim.asNpc().getTemplate().getFakePlayerPvpProfile();
				if (profile.isSpotFighter())
				{
					profile.setGrudge(credited.getObjectId(), System.currentTimeMillis() + GRUDGE_TIME);
				}
			}
		}

		if (wasLeader)
		{
			showLeader(victim, false);
			spot._leaderId = 0;
			spot._leaderStreak = 0;
			spot._leaderName = null;
		}

		updateLeader(zone, spot);
	}

	/**
	 * @param streak a kill streak
	 * @return {@code true} if the players of the spot hear about it
	 */
	private static boolean isStreakAnnounced(int streak)
	{
		if (streak < PvpSpotsConfig.LEADER_MIN_STREAK)
		{
			return false;
		}

		for (int announced : STREAK_ANNOUNCES)
		{
			if (announced == streak)
			{
				return true;
			}
		}
		return false;
	}

	/**
	 * @param killer a killer
	 * @return who gets the kill: a summon's owner, a fake player's servitor's owner, or the killer itself
	 */
	private static Creature getCredited(Creature killer)
	{
		if (killer instanceof FakePlayerPvpServitor)
		{
			return ((FakePlayerPvpServitor) killer).getOwner();
		}

		if (killer.isSummon())
		{
			return killer.asPlayer();
		}
		return killer;
	}

	/**
	 * The PvP leader of a spot is whoever there has the longest kill streak, from {@link PvpSpotsConfig#LEADER_MIN_STREAK} kills (the current leader keeps it on a tie). Someone who left the spot has no streak anymore.
	 * @param zone the spot
	 * @param spot its state
	 */
	private void updateLeader(PvpSpotZone zone, Spot spot)
	{
		int bestId = 0;
		int best = 0;
		Creature bestCreature = null;
		for (Map.Entry<Integer, Integer> entry : spot._streaks.entrySet())
		{
			final Creature creature = findCreature(entry.getKey());
			if ((creature == null) || creature.isAlikeDead() || !zone.isInsideZone(creature))
			{
				spot._streaks.remove(entry.getKey(), entry.getValue());
				continue;
			}

			final int streak = entry.getValue();
			if ((streak > best) || ((streak == best) && (entry.getKey() == spot._leaderId)))
			{
				best = streak;
				bestId = entry.getKey();
				bestCreature = creature;
			}
		}

		if (best < PvpSpotsConfig.LEADER_MIN_STREAK)
		{
			bestId = 0;
			bestCreature = null;
		}

		if (bestId == spot._leaderId)
		{
			spot._leaderStreak = best;
			return;
		}

		// A new leader (or none).
		showLeader(findCreature(spot._leaderId), false);
		spot._leaderId = bestId;
		spot._leaderStreak = best;
		spot._leaderName = bestCreature != null ? bestCreature.getName() : null;
		if (bestCreature != null)
		{
			showLeader(bestCreature, true);
			tell(zone, bestCreature.getName() + " is the PvP leader of " + zone.getName() + " (" + best + " kills in a row)!");
			chatter(zone, TAUNTS_LEADER, bestCreature.getName(), bestCreature, 2);
		}
	}

	/**
	 * Shows (or stops showing) a fake player as the leader of its spot: a title and the hero aura. A player is told.
	 * @param creature the leader, can be {@code null}
	 * @param leader {@code true} to show it
	 */
	private static void showLeader(Creature creature, boolean leader)
	{
		if (creature == null)
		{
			return;
		}

		if (creature.isFakePlayer())
		{
			final FakePlayerHolder holder = creature.asNpc().getTemplate().getFakePlayerInfo();
			if ((holder != null) && (holder.isShownAsLeader() != leader))
			{
				holder.setLeader(leader ? PvpSpotsConfig.LEADER_TITLE : null, PvpSpotsConfig.LEADER_TITLE_COLOR, PvpSpotsConfig.LEADER_HERO_AURA);
				creature.broadcastInfo();
			}
		}
		else if (leader && creature.isPlayer())
		{
			creature.sendPacket(new ExShowScreenMessage("You are the PvP leader of this spot!", 5000));
		}
	}

	/**
	 * Tells the players of a spot (with PvpSpotLeaderAnnounce).
	 * @param zone the spot
	 * @param text what they are told
	 */
	private static void tell(PvpSpotZone zone, String text)
	{
		if (!PvpSpotsConfig.LEADER_ANNOUNCE)
		{
			return;
		}

		final ExShowScreenMessage message = new ExShowScreenMessage(text, ExShowScreenMessage.TOP_CENTER, 5000);
		for (Player player : zone.getPlayersInside())
		{
			player.sendPacket(message);
			player.sendMessage(text);
		}
	}

	/**
	 * A few fake players of the spot say something about someone in general chat (not their own clan mates).
	 * @param zone the spot
	 * @param lines what they may say, %s being the name
	 * @param name the name
	 * @param about the one they talk about
	 * @param count how many of them at most
	 */
	private void chatter(PvpSpotZone zone, String[] lines, String name, Creature about, int count)
	{
		final Spot spot = _spots.get(zone.getId());
		if (spot == null)
		{
			return;
		}

		final List<Npc> speakers = new ArrayList<>();
		for (Npc fake : spot._fighters)
		{
			if ((fake != about) && !fake.isDead() && fake.isSpawned() && !FakeClanManager.getInstance().isFriend(fake, about))
			{
				speakers.add(fake);
			}
		}

		for (int i = 0; (i < count) && !speakers.isEmpty(); i++)
		{
			final Npc speaker = speakers.remove(Rnd.get(speakers.size()));
			FakePlayerPvpManager.getInstance().say(speaker, String.format(lines[Rnd.get(lines.length)], name), 60);
		}
	}

	// ---------------------------------------------------------------------------------------------
	// Fake players leaving and coming back
	// ---------------------------------------------------------------------------------------------

	/**
	 * Called when a roaming fake player leaves the world (its dead body went to town, it logged off or read its scroll): one of a spot that died there often comes back a while later, like a player that restarted in town and teleports back. Its name stays taken
	 * meanwhile, otherwise it is free.
	 * @param fake the fake player
	 * @return {@code true} if it was a fighter of a spot (then nothing else is to be done about it)
	 */
	public boolean onFighterDecay(Npc fake)
	{
		final FakePlayerPvpProfile profile = fake.getTemplate().getFakePlayerPvpProfile();
		if ((profile == null) || !profile.isSpotFighter())
		{
			return false;
		}

		final int spotId = profile.getPvpSpotId();
		final Spot spot = _spots.get(spotId);
		if (spot != null)
		{
			spot._fighters.remove(fake);
			spot._streaks.remove(fake.getObjectId());
		}

		// Its template is used again when it comes back.
		final FakePlayerHolder holder = fake.getTemplate().getFakePlayerInfo();
		if (holder != null)
		{
			holder.setLeader(null, 0, false);
		}
		profile.setLeaveTime(0);

		final long now = System.currentTimeMillis();
		if (fake.isDead() && (spot != null) && PvpSpotsConfig.ENABLED && PvpSpotsConfig.FAKE_PLAYERS && FakePlayerPvpManager.getInstance().isEnabled() && (now < profile.getSpotStayUntil()) && (Rnd.get(100) < PvpSpotsConfig.RETURN_CHANCE))
		{
			final NpcTemplate template = fake.getTemplate();
			final String name = fake.getName();
			spot._returning.incrementAndGet();
			if (profile.isElite())
			{
				spot._returningElites.incrementAndGet();
			}
			_returns.put(name, ThreadPool.schedule(() -> returnFighter(template, spotId), Rnd.get(PvpSpotsConfig.RETURN_DELAY_MIN, PvpSpotsConfig.RETURN_DELAY_MAX) * 1000L));
			return true;
		}

		FakePlayerPvpManager.getInstance().releaseName(fake.getName());
		return true;
	}

	/**
	 * A fake player that died in its spot comes back, by teleport, fully buffed.
	 * @param template its template
	 * @param spotId the zone id of its spot
	 */
	private void returnFighter(NpcTemplate template, int spotId)
	{
		final String name = template.getName();
		if (_returns.remove(name) == null)
		{
			return; // Cancelled meanwhile.
		}

		final Spot spot = _spots.get(spotId);
		if (spot != null)
		{
			spot._returning.decrementAndGet();
			if (template.getFakePlayerPvpProfile().isElite())
			{
				spot._returningElites.decrementAndGet();
			}
		}

		final PvpSpotZone zone = ZoneManager.getInstance().getZoneById(spotId, PvpSpotZone.class);
		if ((spot == null) || (zone == null) || !PvpSpotsConfig.ENABLED || !PvpSpotsConfig.FAKE_PLAYERS || !FakePlayerPvpManager.getInstance().isEnabled() || (spot._fighters.size() >= PvpSpotsConfig.FIGHTERS_MAX))
		{
			FakePlayerPvpManager.getInstance().releaseName(name);
			return;
		}

		final Location point = getLandingPoint(zone);
		final Npc fake = FakePlayerPvpManager.getInstance().returnSpotFighter(template, point.getX(), point.getY(), point.getZ());
		if (fake != null)
		{
			spot._fighters.add(fake);
		}
	}

	/**
	 * Cancels the fake players of the spots on their way back from town (//fakepvp_clear).
	 * @return how many were cancelled
	 */
	public int clearReturns()
	{
		int count = 0;
		for (Map.Entry<String, ScheduledFuture<?>> entry : _returns.entrySet())
		{
			if (_returns.remove(entry.getKey(), entry.getValue()))
			{
				entry.getValue().cancel(false);
				FakePlayerPvpManager.getInstance().releaseName(entry.getKey());
				count++;
			}
		}

		for (Spot spot : _spots.values())
		{
			spot._returning.set(0);
			spot._returningElites.set(0);
		}
		return count;
	}

	/**
	 * @param name a fake player name
	 * @return {@code true} if the fake player of that name died in a spot and comes back
	 */
	boolean isComingBack(String name)
	{
		return _returns.containsKey(name);
	}

	// ---------------------------------------------------------------------------------------------
	// Helpers
	// ---------------------------------------------------------------------------------------------

	/**
	 * @param objectId an object id
	 * @return the creature of that id in the world, {@code null} if none
	 */
	private static Creature findCreature(int objectId)
	{
		if (objectId == 0)
		{
			return null;
		}

		final WorldObject object = World.getInstance().findObject(objectId);
		return object instanceof Creature ? (Creature) object : null;
	}

	/**
	 * @param creature a creature
	 * @return the spot it is in, or for a fake player of a spot that left it, its spot; {@code null} if none
	 */
	private static PvpSpotZone getSpotOf(Creature creature)
	{
		if (creature.isInsideZone(ZoneId.PVP_SPOT))
		{
			final PvpSpotZone zone = ZoneManager.getInstance().getZone(creature, PvpSpotZone.class);
			if (zone != null)
			{
				return zone;
			}
		}

		if (creature.isPvpFakePlayer())
		{
			final FakePlayerPvpProfile profile = creature.asNpc().getTemplate().getFakePlayerPvpProfile();
			if (profile.isSpotFighter())
			{
				return getZone(profile.getPvpSpotId());
			}
		}
		return null;
	}

	/**
	 * @param zoneId the zone id of a spot
	 * @return the spot, {@code null} if there is none of that id
	 */
	public static PvpSpotZone getZone(int zoneId)
	{
		return ZoneManager.getInstance().getZoneById(zoneId, PvpSpotZone.class);
	}

	/**
	 * @param npc an npc
	 * @return {@code true} if it is a fake player that came to a spot to fight
	 */
	public static boolean isSpotFighter(Npc npc)
	{
		final FakePlayerPvpProfile profile = npc.getTemplate().getFakePlayerPvpProfile();
		return (profile != null) && profile.isSpotFighter();
	}

	/**
	 * @param zoneId the zone id of a spot
	 * @return the object id of its PvP leader, 0 if it has none
	 */
	public int getLeaderId(int zoneId)
	{
		final Spot spot = _spots.get(zoneId);
		return spot != null ? spot._leaderId : 0;
	}

	/**
	 * @param zoneId the zone id of a spot
	 * @param objectId a player or fake player
	 * @return its kill streak in that spot
	 */
	public int getStreak(int zoneId, int objectId)
	{
		final Spot spot = _spots.get(zoneId);
		return spot != null ? spot._streaks.getOrDefault(objectId, 0) : 0;
	}

	/**
	 * @param zoneId the zone id of a spot
	 * @return where a player teleporting there lands, {@code null} if there is no such spot
	 */
	public Location getTeleportPoint(int zoneId)
	{
		final PvpSpotZone zone = getZone(zoneId);
		return zone != null ? getLandingPoint(zone) : null;
	}

	/**
	 * @return what the .pvp window and the admin page show of the spots, ordered by level
	 */
	public List<SpotInfo> getSpotInfos()
	{
		final List<SpotInfo> infos = new ArrayList<>();
		for (PvpSpotZone zone : ZoneManager.getInstance().getAllZones(PvpSpotZone.class))
		{
			final Spot spot = _spots.get(zone.getId());
			int fakes = 0;
			if (spot != null)
			{
				for (Npc fake : spot._fighters)
				{
					if (!fake.isDead())
					{
						fakes++;
					}
				}
			}

			int players = 0;
			for (Player player : zone.getPlayersInside())
			{
				if (!player.isInvisible())
				{
					players++;
				}
			}

			infos.add(new SpotInfo(zone.getId(), zone.getName(), zone.getSpotMinLevel(), zone.getSpotMaxLevel(), fakes, players, spot != null ? spot._leaderName : null, spot != null ? spot._leaderStreak : 0, spot != null ? spot._wanted : 0));
		}

		infos.sort((a, b) -> Integer.compare(a.getMinLevel(), b.getMinLevel()));
		return infos;
	}

	/**
	 * Sends off every fake player of the spots (//fakepvp_spots_clear): they log off, and new ones come.
	 * @return how many left
	 */
	public int clearFighters()
	{
		int count = clearReturns();
		for (Spot spot : _spots.values())
		{
			for (Npc fake : new ArrayList<>(spot._fighters))
			{
				fake.deleteMe();
				count++;
			}
			spot._streaks.clear();
			spot._leaderId = 0;
			spot._leaderStreak = 0;
			spot._leaderName = null;
		}
		return count;
	}

	public static PvpSpotManager getInstance()
	{
		return SingletonHolder.INSTANCE;
	}

	private static class SingletonHolder
	{
		protected static final PvpSpotManager INSTANCE = new PvpSpotManager();
	}
}
