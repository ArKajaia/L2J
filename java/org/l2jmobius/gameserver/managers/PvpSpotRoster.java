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
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.l2jmobius.commons.database.DatabaseFactory;
import org.l2jmobius.commons.util.Rnd;
import org.l2jmobius.gameserver.config.custom.PvpSpotsConfig;
import org.l2jmobius.gameserver.data.xml.FakePlayerPvpData;
import org.l2jmobius.gameserver.model.actor.Npc;
import org.l2jmobius.gameserver.model.actor.holders.npc.FakePlayerPvpBuild;
import org.l2jmobius.gameserver.model.actor.holders.npc.FakePlayerPvpProfile;

/**
 * The known fake players of the PvP spots (see {@link PvpSpotManager}). A fake player that comes to a spot for the first time is saved: its build, level, whether it is a strong one and the seed its gear, enchants, looks and passive tree are rolled from, so that it
 * is made again exactly the same, like the members of a players' clan (see {@link FakeClanManager}). Each one also has its own play time, allocated on the real clock: some hours a day around a favourite time of day, so the same pvpers are seen again at about the same
 * hours. Every real day the oldest ones may retire (the chance grows with age from 0% to {@link PvpSpotsConfig#ROSTER_RETIRE_CHANCE}%), and new ones now and then take their place.
 */
public class PvpSpotRoster
{
	private static final Logger LOGGER = Logger.getLogger(PvpSpotRoster.class.getName());
	
	private static final String CREATE_TABLE = "CREATE TABLE IF NOT EXISTS `fake_pvp_roster` (`name` VARCHAR(35) NOT NULL, `build` VARCHAR(64) NOT NULL, `class_id` SMALLINT UNSIGNED NOT NULL, `level` TINYINT UNSIGNED NOT NULL, `elite` TINYINT UNSIGNED NOT NULL DEFAULT 0, `seed` BIGINT NOT NULL, `created` BIGINT UNSIGNED NOT NULL, `daily_play` BIGINT UNSIGNED NOT NULL, `window_hour` TINYINT UNSIGNED NOT NULL, `play_day` INT NOT NULL DEFAULT 0, `played` BIGINT UNSIGNED NOT NULL DEFAULT 0, `last_seen` BIGINT UNSIGNED NOT NULL DEFAULT 0, PRIMARY KEY (`name`)) ENGINE=InnoDB DEFAULT CHARSET=utf8";
	private static final String SELECT_ALL = "SELECT * FROM fake_pvp_roster";
	private static final String INSERT = "REPLACE INTO fake_pvp_roster (name, build, class_id, level, elite, seed, created, daily_play, window_hour, play_day, played, last_seen) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
	private static final String UPDATE_PLAY = "UPDATE fake_pvp_roster SET play_day=?, played=?, last_seen=? WHERE name=?";
	private static final String DELETE = "DELETE FROM fake_pvp_roster WHERE name=?";
	
	/** Global variable with the day (days since 1970) retirement was last rolled for. */
	private static final String RETIRE_VARIABLE = "PVP_SPOT_ROSTER_RETIRE_DAY";
	/** The most days of retirement rolled at once, after the server was down for a long time. */
	private static final int MAX_MISSED_DAYS = 30;
	private static final long DAY_MILLIS = 86400000L;
	private static final long HOUR_MILLIS = 3600000L;
	/** A character with less play time than this left today doesn't log in. */
	private static final long MIN_SESSION = 180000;
	/** How often the play time of the ones online is saved. */
	private static final long FLUSH_INTERVAL = 300000;
	/** How often retirement is looked at (it rolls once a day). */
	private static final long RETIRE_CHECK_INTERVAL = 60000;
	/** A character whose fake player is gone, and isn't coming back, for this long is logged off. */
	private static final long LOST_GRACE = 30000;
	
	/**
	 * A saved character.
	 */
	public static class Entry
	{
		private final String _name;
		private final String _buildName;
		private final int _classId;
		private final int _level;
		private final boolean _elite;
		private final long _seed;
		private final long _created;
		/** Its play time per day (milliseconds), for life. */
		private final long _dailyPlay;
		/** The hour of the day its play window starts at, for life. */
		private final int _windowHour;
		/** The day (days since 1970) {@link #_played} counts for. */
		private int _playDay;
		/** Play time of {@link #_playDay} (milliseconds). */
		private long _played;
		private long _lastSeen;
		/** Not before this time (memory only). */
		private long _nextLogin;
		private boolean _online;
		private boolean _retired;
		private long _lastAccrue;
		private long _sessionStart;
		private Npc _npc;
		private boolean _dirty;
		
		Entry(String name, String buildName, int classId, int level, boolean elite, long seed, long created, long dailyPlay, int windowHour, int playDay, long played, long lastSeen)
		{
			_name = name;
			_buildName = buildName;
			_classId = classId;
			_level = level;
			_elite = elite;
			_seed = seed;
			_created = created;
			_dailyPlay = dailyPlay;
			_windowHour = windowHour;
			_playDay = playDay;
			_played = played;
			_lastSeen = lastSeen;
		}
		
		public String getName()
		{
			return _name;
		}
		
		public String getBuildName()
		{
			return _buildName;
		}
		
		public int getLevel()
		{
			return _level;
		}
		
		public boolean isElite()
		{
			return _elite;
		}
		
		public long getSeed()
		{
			return _seed;
		}
		
		/**
		 * @return how many hours its play window lasts: a bit more than twice its play time, so that its visits fit
		 */
		private int getWindowLength()
		{
			return windowLengthFor(_dailyPlay);
		}
	}
	
	private final Map<String, Entry> _entries = new ConcurrentHashMap<>();
	private final Set<Entry> _online = ConcurrentHashMap.newKeySet();
	private long _nextFlush;
	private long _nextRetireCheck;
	
	protected PvpSpotRoster()
	{
		load();
	}
	
	/**
	 * @return {@code true} if fake players are saved and come back
	 */
	public static boolean isEnabled()
	{
		return PvpSpotsConfig.ROSTER_ENABLED && (PvpSpotsConfig.ROSTER_MAX > 0);
	}
	
	public int size()
	{
		return _entries.size();
	}
	
	private void load()
	{
		final List<String> gone = new ArrayList<>();
		try (Connection con = DatabaseFactory.getConnection();
			Statement statement = con.createStatement())
		{
			statement.execute(CREATE_TABLE);
			try (ResultSet rs = statement.executeQuery(SELECT_ALL))
			{
				while (rs.next())
				{
					final String name = rs.getString("name");
					if (FakePlayerPvpData.getInstance().getBuild(rs.getString("build")) == null)
					{
						gone.add(name); // Its build is no more.
						continue;
					}
					
					final Entry entry = new Entry(name, rs.getString("build"), rs.getInt("class_id"), rs.getInt("level"), rs.getInt("elite") == 1, rs.getLong("seed"), rs.getLong("created"), rs.getLong("daily_play"), rs.getInt("window_hour"), rs.getInt("play_day"), rs.getLong("played"), rs.getLong("last_seen"));
					_entries.put(key(name), entry);
					FakePlayerPvpManager.getInstance().keepName(name);
				}
			}
		}
		catch (Exception e)
		{
			LOGGER.log(Level.WARNING, getClass().getSimpleName() + ": Could not load the known fake players.", e);
		}
		
		for (String name : gone)
		{
			delete(name);
		}
		
		LOGGER.info(getClass().getSimpleName() + ": " + _entries.size() + " known fake players.");
	}
	
	private static int windowLengthFor(long dailyPlay)
	{
		return (int) Math.min(24, Math.ceil((dailyPlay * 2.0) / HOUR_MILLIS) + 1);
	}
	
	private static String key(String name)
	{
		return name.toLowerCase();
	}
	
	private static int getDay(long time)
	{
		return (int) Instant.ofEpochMilli(time).atZone(ZoneId.systemDefault()).toLocalDate().toEpochDay();
	}
	
	private static int getHour(long time)
	{
		return Instant.ofEpochMilli(time).atZone(ZoneId.systemDefault()).getHour();
	}
	
	/**
	 * Starts a new day of play time when the day changed.
	 * @param entry the character
	 * @param day today
	 */
	private static void rollDay(Entry entry, int day)
	{
		if (entry._playDay != day)
		{
			entry._playDay = day;
			entry._played = 0;
			entry._dirty = true;
		}
	}
	
	private static void accrue(Entry entry, long now, int day)
	{
		rollDay(entry, day);
		if (entry._online)
		{
			entry._played += Math.max(0, now - entry._lastAccrue);
			entry._lastAccrue = now;
			entry._dirty = true;
		}
	}
	
	/**
	 * @param entry the character
	 * @param now the current time
	 * @param day today
	 * @param hour the hour of the day now
	 * @return {@code true} if it may come now: it is not online, not retired, rested, inside its play window of the day and has play time left
	 */
	private static boolean isAvailable(Entry entry, long now, int day, int hour)
	{
		if (entry._online || entry._retired || (now < entry._nextLogin))
		{
			return false;
		}
		
		rollDay(entry, day);
		return ((entry._dailyPlay - entry._played) >= MIN_SESSION) && (((hour - entry._windowHour + 24) % 24) < entry.getWindowLength());
	}
	
	/**
	 * Picks a known character for an arrival.
	 * @param minLevel the lowest level the spot takes
	 * @param maxLevel the highest level the spot takes
	 * @param level the level wanted
	 * @param range how far from {@code level} it may be
	 * @param elite {@code true} for a strong one
	 * @return a known character that is free and fits, {@code null} if the arrival is a new one (by chance, or because nobody fits)
	 */
	public synchronized Entry pick(int minLevel, int maxLevel, int level, int range, boolean elite)
	{
		if (!isEnabled() || (Rnd.get(100) < PvpSpotsConfig.ROSTER_NEW_CHANCE))
		{
			return null;
		}
		
		final long now = System.currentTimeMillis();
		final int day = getDay(now);
		final int hour = getHour(now);
		final List<Entry> fitting = new ArrayList<>();
		for (Entry entry : _entries.values())
		{
			if ((entry._elite == elite) && (entry._level >= minLevel) && (entry._level <= maxLevel) && (Math.abs(entry._level - level) <= range) && isAvailable(entry, now, day, hour))
			{
				fitting.add(entry);
			}
		}
		
		return fitting.isEmpty() ? null : fitting.get(Rnd.get(fitting.size()));
	}
	
	/**
	 * Saves a new character that just arrived, with its own play time: the hours of a day it plays, around a time of the day it favours (its window includes this moment).
	 * @param fake the fake player
	 * @param build its build
	 * @param level its level
	 * @param elite {@code true} for a strong one
	 * @param seed the seed it was made from
	 * @return the saved character, {@code null} if the roster is full
	 */
	public synchronized Entry register(Npc fake, FakePlayerPvpBuild build, int level, boolean elite, long seed)
	{
		if (!isEnabled() || (_entries.size() >= PvpSpotsConfig.ROSTER_MAX))
		{
			return null;
		}
		
		final FakePlayerPvpProfile profile = fake.getTemplate().getFakePlayerPvpProfile();
		final long now = System.currentTimeMillis();
		final long dailyPlay = Rnd.get(PvpSpotsConfig.ROSTER_PLAY_HOURS_MIN, PvpSpotsConfig.ROSTER_PLAY_HOURS_MAX) * HOUR_MILLIS;
		final int windowHour = (getHour(now) - Rnd.get(windowLengthFor(dailyPlay)) + 24) % 24;
		final Entry entry = new Entry(fake.getName(), build.getName(), profile.getPlayerClass().getId(), level, elite, seed, now, dailyPlay, windowHour, getDay(now), 0, now);
		_entries.put(key(entry._name), entry);
		FakePlayerPvpManager.getInstance().keepName(entry._name);
		store(entry);
		begin(entry, fake);
		return entry;
	}
	
	/**
	 * A known character logs in.
	 * @param entry the character
	 * @param fake its fake player
	 */
	public synchronized void begin(Entry entry, Npc fake)
	{
		final long now = System.currentTimeMillis();
		rollDay(entry, getDay(now));
		entry._online = true;
		entry._npc = fake;
		entry._sessionStart = now;
		entry._lastAccrue = now;
		_online.add(entry);
	}
	
	/**
	 * @param name a fake player name
	 * @param fake the fake player of that name, that came back from town
	 */
	public synchronized void attach(String name, Npc fake)
	{
		final Entry entry = _entries.get(key(name));
		if (entry != null)
		{
			entry._npc = fake;
		}
	}
	
	/**
	 * @param entry the character, {@code null} for a stranger that isn't saved
	 * @param stay the time (milliseconds) it would stay
	 * @return the time it stays: its play time left today if that is less
	 */
	public synchronized long limitStay(Entry entry, long stay)
	{
		if (entry == null)
		{
			return stay;
		}
		
		final long now = System.currentTimeMillis();
		accrue(entry, now, getDay(now));
		return Math.max(MIN_SESSION / 3, Math.min(stay, entry._dailyPlay - entry._played));
	}
	
	/**
	 * A character logs off (or can't come back from town): its play time is saved, and it rests before its next visit. One that retired meanwhile is deleted now.
	 * @param name the character name
	 */
	public synchronized void endSession(String name)
	{
		final Entry entry = _entries.get(key(name));
		if ((entry == null) || !entry._online)
		{
			return;
		}
		
		final long now = System.currentTimeMillis();
		accrue(entry, now, getDay(now));
		entry._online = false;
		entry._npc = null;
		entry._lastSeen = now;
		entry._nextLogin = now + (Rnd.get(PvpSpotsConfig.ROSTER_REST_MIN, PvpSpotsConfig.ROSTER_REST_MAX) * 60000L);
		_online.remove(entry);
		if (entry._retired)
		{
			remove(entry);
			return;
		}
		
		updatePlay(entry);
	}
	
	/**
	 * Called every few seconds: counts play time, saves it now and then and rolls retirement once a day.
	 * @param now the current time
	 */
	public synchronized void tick(long now)
	{
		final int day = getDay(now);
		final List<Entry> lost = new ArrayList<>();
		for (Entry entry : _online)
		{
			accrue(entry, now, day);
			
			// Its fake player is gone without a word (should not happen) and it isn't on its way back from town.
			final Npc fake = entry._npc;
			if (((fake == null) || (!fake.isSpawned() && fake.isDecayed())) && ((now - entry._sessionStart) > LOST_GRACE) && !PvpSpotManager.getInstance().isComingBack(entry._name))
			{
				lost.add(entry);
			}
		}
		
		for (Entry entry : lost)
		{
			endSession(entry._name);
		}
		
		if (now >= _nextFlush)
		{
			_nextFlush = now + FLUSH_INTERVAL;
			for (Entry entry : _online)
			{
				if (entry._dirty)
				{
					updatePlay(entry);
				}
			}
		}
		
		if (now >= _nextRetireCheck)
		{
			_nextRetireCheck = now + RETIRE_CHECK_INTERVAL;
			checkRetirement(now);
		}
	}
	
	/**
	 * Rolls the retirement of every day that went by since the last time (see {@link #MAX_MISSED_DAYS}).
	 * @param now the current time
	 */
	private void checkRetirement(long now)
	{
		try
		{
			final long today = getDay(now);
			final long last = GlobalVariablesManager.getInstance().getLong(RETIRE_VARIABLE, 0);
			if ((last <= 0) || (last > today))
			{
				GlobalVariablesManager.getInstance().set(RETIRE_VARIABLE, today);
				GlobalVariablesManager.getInstance().storeMe();
				return;
			}
			
			final long days = today - last;
			if (days <= 0)
			{
				return;
			}
			
			// Moved on first: a day is never rolled twice.
			GlobalVariablesManager.getInstance().set(RETIRE_VARIABLE, today);
			GlobalVariablesManager.getInstance().storeMe();
			int retired = 0;
			for (long i = Math.min(days, MAX_MISSED_DAYS); i > 0; i--)
			{
				retired += rollRetirement(now - ((i - 1) * DAY_MILLIS));
			}
			
			if (retired > 0)
			{
				LOGGER.info(getClass().getSimpleName() + ": " + retired + " fake players retired, " + _entries.size() + " known.");
			}
		}
		catch (Exception e)
		{
			LOGGER.log(Level.WARNING, getClass().getSimpleName() + ": Could not roll the retirement of the known fake players.", e);
		}
	}
	
	/**
	 * @param ageDays the age of a character in days
	 * @return the chance (in %) it retires in a day: 0 up to {@link PvpSpotsConfig#ROSTER_RETIRE_MIN_AGE} days old, then rising in a straight line to {@link PvpSpotsConfig#ROSTER_RETIRE_CHANCE} at {@link PvpSpotsConfig#ROSTER_RETIRE_FULL_AGE} days
	 */
	static double getRetireChance(double ageDays)
	{
		final double span = PvpSpotsConfig.ROSTER_RETIRE_FULL_AGE - PvpSpotsConfig.ROSTER_RETIRE_MIN_AGE;
		final double progress = Math.max(0, Math.min(1, (ageDays - PvpSpotsConfig.ROSTER_RETIRE_MIN_AGE) / span));
		return PvpSpotsConfig.ROSTER_RETIRE_CHANCE * progress;
	}
	
	private int rollRetirement(long time)
	{
		int retired = 0;
		for (Entry entry : new ArrayList<>(_entries.values()))
		{
			if (entry._retired)
			{
				continue;
			}
			
			final double ageDays = (time - entry._created) / (double) DAY_MILLIS;
			if (Rnd.nextDouble() * 100 >= getRetireChance(ageDays))
			{
				continue;
			}
			
			retired++;
			if (entry._online)
			{
				entry._retired = true; // Leaves when it logs off.
			}
			else
			{
				remove(entry);
			}
		}
		
		return retired;
	}
	
	private void remove(Entry entry)
	{
		_entries.remove(key(entry._name));
		_online.remove(entry);
		FakePlayerPvpManager.getInstance().unkeepName(entry._name, false);
		delete(entry._name);
	}
	
	private static void store(Entry entry)
	{
		try (Connection con = DatabaseFactory.getConnection();
			PreparedStatement ps = con.prepareStatement(INSERT))
		{
			ps.setString(1, entry._name);
			ps.setString(2, entry._buildName);
			ps.setInt(3, entry._classId);
			ps.setInt(4, entry._level);
			ps.setInt(5, entry._elite ? 1 : 0);
			ps.setLong(6, entry._seed);
			ps.setLong(7, entry._created);
			ps.setLong(8, entry._dailyPlay);
			ps.setInt(9, entry._windowHour);
			ps.setInt(10, entry._playDay);
			ps.setLong(11, entry._played);
			ps.setLong(12, entry._lastSeen);
			ps.execute();
		}
		catch (Exception e)
		{
			LOGGER.log(Level.WARNING, PvpSpotRoster.class.getSimpleName() + ": Could not store fake player " + entry._name + ".", e);
		}
	}
	
	private static void updatePlay(Entry entry)
	{
		entry._dirty = false;
		try (Connection con = DatabaseFactory.getConnection();
			PreparedStatement ps = con.prepareStatement(UPDATE_PLAY))
		{
			ps.setInt(1, entry._playDay);
			ps.setLong(2, entry._played);
			ps.setLong(3, entry._lastSeen);
			ps.setString(4, entry._name);
			ps.execute();
		}
		catch (Exception e)
		{
			LOGGER.log(Level.WARNING, PvpSpotRoster.class.getSimpleName() + ": Could not update fake player " + entry._name + ".", e);
		}
	}
	
	private static void delete(String name)
	{
		try (Connection con = DatabaseFactory.getConnection();
			PreparedStatement ps = con.prepareStatement(DELETE))
		{
			ps.setString(1, name);
			ps.execute();
		}
		catch (Exception e)
		{
			LOGGER.log(Level.WARNING, PvpSpotRoster.class.getSimpleName() + ": Could not delete fake player " + name + ".", e);
		}
	}
	
	public static PvpSpotRoster getInstance()
	{
		return SingletonHolder.INSTANCE;
	}
	
	private static class SingletonHolder
	{
		protected static final PvpSpotRoster INSTANCE = new PvpSpotRoster();
	}
}
