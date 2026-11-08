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

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.l2jmobius.commons.database.DatabaseFactory;
import org.l2jmobius.commons.threads.ThreadPool;
import org.l2jmobius.gameserver.model.World;
import org.l2jmobius.gameserver.model.WorldObject;
import org.l2jmobius.gameserver.model.actor.Creature;
import org.l2jmobius.gameserver.model.actor.Npc;
import org.l2jmobius.gameserver.model.actor.Player;
import org.l2jmobius.gameserver.model.actor.enums.player.PlayerClass;
import org.l2jmobius.gameserver.model.actor.holders.npc.FakePlayerHolder;
import org.l2jmobius.gameserver.model.actor.holders.npc.FakePlayerPvpProfile;
import org.l2jmobius.gameserver.model.actor.instance.FakePlayerPvpServitor;
import org.l2jmobius.gameserver.model.zone.ZoneId;

/**
 * PvP ranking of players and fake players, shown on the Community Board.
 * <ul>
 * <li>Players count their PvP kills like always ({@code characters.pvpkills}).</li>
 * <li>Fake players count theirs here, by name, in table {@code fake_player_pvp}: a kill of a flagged player or fake player, of one with karma, or of anyone in a PvP spot. A name a fake player took again keeps its count.</li>
 * </ul>
 * Every line has the class of its character: a player's active class (as it is now when online), a fake player's class when it last made a PvP kill (as it is now when spawned).
 * @author Claude
 */
public class PvpRankingManager
{
	private static final Logger LOGGER = Logger.getLogger(PvpRankingManager.class.getName());

	/** How many names the ranking shows. */
	public static final int RANKING_SIZE = 20;
	/** How long the ranking is kept before it is made again. */
	private static final long CACHE_TIME = 60000;

	private static final String CREATE_TABLE = "CREATE TABLE IF NOT EXISTS `fake_player_pvp` (`name` VARCHAR(35) NOT NULL, `pvp_kills` INT UNSIGNED NOT NULL DEFAULT 0, `class_id` SMALLINT NOT NULL DEFAULT -1, PRIMARY KEY (`name`)) ENGINE=InnoDB DEFAULT CHARSET=utf8";
	/** Tables made before the class was kept get the column. */
	private static final String FIND_CLASS_COLUMN = "SHOW COLUMNS FROM fake_player_pvp LIKE 'class_id'";
	private static final String ADD_CLASS_COLUMN = "ALTER TABLE fake_player_pvp ADD COLUMN `class_id` SMALLINT NOT NULL DEFAULT -1";
	private static final String SELECT_FAKES = "SELECT name, pvp_kills, class_id FROM fake_player_pvp";
	private static final String UPDATE_FAKE = "INSERT INTO fake_player_pvp (name, pvp_kills, class_id) VALUES (?, ?, ?) ON DUPLICATE KEY UPDATE pvp_kills=VALUES(pvp_kills), class_id=VALUES(class_id)";
	private static final String SELECT_PLAYERS = "SELECT char_name, pvpkills, classid FROM characters WHERE pvpkills > 0 AND accesslevel = 0 ORDER BY pvpkills DESC LIMIT " + RANKING_SIZE;

	/** The class id of a line whose class isn't known. */
	public static final int NO_CLASS = -1;

	/** How often the PvP kills that changed are written to the database. */
	private static final long STORE_INTERVAL = 60000;

	/** PvP kills of fake players, by name. */
	private final Map<String, Integer> _fakeKills = new ConcurrentHashMap<>();
	/** Class ids of fake players, by name: their class when they last made a PvP kill. */
	private final Map<String, Integer> _fakeClasses = new ConcurrentHashMap<>();
	/** Object ids of fake players, by name: the last one that made a PvP kill under that name (not stored, fake players get new ones at a restart). */
	private final Map<String, Integer> _fakeObjectIds = new ConcurrentHashMap<>();
	/** The fake players whose PvP kills changed since they were last stored. */
	private final Set<String> _changed = ConcurrentHashMap.newKeySet();

	private volatile List<Entry> _ranking = Collections.emptyList();
	private volatile long _rankingTime;

	/**
	 * A line of the ranking.
	 */
	public static class Entry
	{
		private final String _name;
		private final int _kills;
		private final int _classId;
		private final boolean _fake;

		public Entry(String name, int kills, int classId, boolean fake)
		{
			_name = name;
			_kills = kills;
			_classId = classId;
			_fake = fake;
		}

		public String getName()
		{
			return _name;
		}

		public int getKills()
		{
			return _kills;
		}

		/**
		 * @return the class id of the character, {@link #NO_CLASS} if it isn't known
		 */
		public int getClassId()
		{
			return _classId;
		}

		/**
		 * @return {@code true} for a fake player, {@code false} for a player
		 */
		public boolean isFake()
		{
			return _fake;
		}
	}

	protected PvpRankingManager()
	{
		try (Connection con = DatabaseFactory.getConnection();
			Statement statement = con.createStatement())
		{
			statement.execute(CREATE_TABLE);
			boolean hasClassColumn;
			try (ResultSet rs = statement.executeQuery(FIND_CLASS_COLUMN))
			{
				hasClassColumn = rs.next();
			}
			if (!hasClassColumn)
			{
				statement.execute(ADD_CLASS_COLUMN);
				LOGGER.info(getClass().getSimpleName() + ": Added column class_id to fake_player_pvp.");
			}

			try (ResultSet rs = statement.executeQuery(SELECT_FAKES))
			{
				while (rs.next())
				{
					final String name = rs.getString("name");
					_fakeKills.put(name, rs.getInt("pvp_kills"));
					final int classId = rs.getInt("class_id");
					if (PlayerClass.getPlayerClass(classId) != null)
					{
						_fakeClasses.put(name, classId);
					}
				}
			}
		}
		catch (Exception e)
		{
			LOGGER.log(Level.WARNING, getClass().getSimpleName() + ": Could not load fake player PvP kills.", e);
		}

		LOGGER.info(getClass().getSimpleName() + ": Loaded PvP kills of " + _fakeKills.size() + " fake players.");
		ThreadPool.scheduleAtFixedRate(this::storeChanged, STORE_INTERVAL, STORE_INTERVAL);
	}

	/**
	 * Called when a fake player (or a fake player's servitor) killed a player or another fake player.
	 * @param killer the fake player or its servitor
	 * @param victim the player or fake player killed
	 */
	public void onFakePlayerKill(Creature killer, Creature victim)
	{
		final Npc fake = getFakePlayer(killer);
		if ((fake == null) || (victim == null) || (victim == fake) || !isPvpKill(victim))
		{
			return;
		}

		// Written with the others that changed, once a minute (PvP spots make many kills) and at shutdown.
		final String name = fake.getName();
		_fakeKills.merge(name, 1, Integer::sum);
		_fakeObjectIds.put(name, fake.getObjectId());
		final PlayerClass playerClass = getFakePlayerClass(fake);
		if (playerClass != null)
		{
			_fakeClasses.put(name, playerClass.getId());
		}
		_changed.add(name);
	}

	/**
	 * @param fake a fake player
	 * @return its class (a roaming PvP one's profile, else a town one's data), {@code null} if it has none
	 */
	public static PlayerClass getFakePlayerClass(Npc fake)
	{
		final FakePlayerPvpProfile profile = fake.getTemplate().getFakePlayerPvpProfile();
		if (profile != null)
		{
			return profile.getPlayerClass();
		}

		final FakePlayerHolder info = fake.getTemplate().getFakePlayerInfo();
		return info != null ? info.getPlayerClass() : null;
	}

	/**
	 * @param name the name of a fake player of the ranking
	 * @return the fake player of that name in the world now (spawned), {@code null} if there is none
	 */
	public Npc findFakePlayer(String name)
	{
		final Integer objectId = _fakeObjectIds.get(name);
		if (objectId != null)
		{
			final WorldObject object = World.getInstance().findObject(objectId);
			if ((object != null) && object.isFakePlayer() && object.getName().equals(name) && object.asNpc().isSpawned())
			{
				return object.asNpc();
			}
		}

		// Respawned under another object id, or not seen since the server started.
		for (Npc fake : FakePlayerPvpManager.getInstance().getFakePlayers())
		{
			if (fake.getName().equals(name) && fake.isSpawned())
			{
				_fakeObjectIds.put(name, fake.getObjectId());
				return fake;
			}
		}
		return null;
	}

	/**
	 * @param creature a fake player or the servitor of one
	 * @return the fake player, or {@code null}
	 */
	private static Npc getFakePlayer(Creature creature)
	{
		if (creature == null)
		{
			return null;
		}

		if (creature.isFakePlayer())
		{
			return creature.asNpc();
		}

		if (creature instanceof FakePlayerPvpServitor)
		{
			final Npc owner = ((FakePlayerPvpServitor) creature).getOwner();
			return (owner != null) && owner.isFakePlayer() ? owner : null;
		}

		return null;
	}

	/**
	 * @param victim a player or fake player that was killed
	 * @return {@code true} if killing it counts as PvP: it was flagged, had karma, or was in a PvP spot
	 */
	private static boolean isPvpKill(Creature victim)
	{
		if (victim.isInsideZone(ZoneId.PVP_SPOT))
		{
			return true;
		}

		if (victim.isPlayer())
		{
			final Player player = victim.asPlayer();
			if (player.isInOlympiadMode() || player.isInDuel())
			{
				return false;
			}

			return (player.getPvpFlag() != 0) || (player.getKarma() > 0);
		}

		if (victim.isFakePlayer())
		{
			final Npc npc = victim.asNpc();
			return !npc.isScriptValue(0) || (npc.getKarma() > 0);
		}

		return false;
	}

	/**
	 * Writes the PvP kills that changed since the last time, in one batch. Also called at shutdown.
	 */
	public synchronized void storeChanged()
	{
		if (_changed.isEmpty())
		{
			return;
		}

		final List<String> names = new ArrayList<>(_changed);
		_changed.removeAll(names);
		try (Connection con = DatabaseFactory.getConnection();
			PreparedStatement ps = con.prepareStatement(UPDATE_FAKE))
		{
			for (String name : names)
			{
				ps.setString(1, name);
				ps.setInt(2, _fakeKills.getOrDefault(name, 0));
				ps.setInt(3, _fakeClasses.getOrDefault(name, NO_CLASS));
				ps.addBatch();
			}
			ps.executeBatch();
		}
		catch (Exception e)
		{
			// Try again next time.
			_changed.addAll(names);
			LOGGER.log(Level.WARNING, getClass().getSimpleName() + ": Could not store PvP kills of fake players.", e);
		}
	}

	/**
	 * @param name the name of a fake player
	 * @return its PvP kills
	 */
	public int getFakePlayerKills(String name)
	{
		return _fakeKills.getOrDefault(name, 0);
	}

	/**
	 * @return the players and fake players with the most PvP kills, most first (made again at most once a minute)
	 */
	public List<Entry> getRanking()
	{
		final long now = System.currentTimeMillis();
		if ((now - _rankingTime) < CACHE_TIME)
		{
			return _ranking;
		}

		// Players, from the database and then as they are now when online.
		final Map<String, Integer> kills = new HashMap<>();
		final Map<String, Integer> classes = new HashMap<>();
		try (Connection con = DatabaseFactory.getConnection();
			Statement statement = con.createStatement();
			ResultSet rs = statement.executeQuery(SELECT_PLAYERS))
		{
			while (rs.next())
			{
				final String name = rs.getString("char_name");
				kills.put(name, rs.getInt("pvpkills"));
				classes.put(name, rs.getInt("classid"));
			}
		}
		catch (Exception e)
		{
			LOGGER.log(Level.WARNING, getClass().getSimpleName() + ": Could not load the PvP kills of players.", e);
		}

		for (Player player : World.getInstance().getPlayers())
		{
			if (player.getPvpKills() > 0)
			{
				if (!player.isGM())
				{
					kills.put(player.getName(), player.getPvpKills());
					classes.put(player.getName(), player.getPlayerClass().getId());
				}
			}
			else
			{
				kills.remove(player.getName());
			}
		}

		// Fake players. Their names are never the names of players.
		for (Map.Entry<String, Integer> fake : _fakeKills.entrySet())
		{
			if (fake.getValue() > 0)
			{
				kills.putIfAbsent(fake.getKey(), fake.getValue());
			}
		}

		final List<Entry> ranking = new ArrayList<>(kills.size());
		for (Map.Entry<String, Integer> entry : kills.entrySet())
		{
			final String name = entry.getKey();
			final boolean fake = !classes.containsKey(name);
			ranking.add(new Entry(name, entry.getValue(), fake ? _fakeClasses.getOrDefault(name, NO_CLASS) : classes.get(name), fake));
		}

		ranking.sort((a, b) -> (a.getKills() != b.getKills()) ? Integer.compare(b.getKills(), a.getKills()) : a.getName().compareToIgnoreCase(b.getName()));
		final List<Entry> top = new ArrayList<>(Math.min(RANKING_SIZE, ranking.size()));
		for (Entry entry : ranking.subList(0, Math.min(RANKING_SIZE, ranking.size())))
		{
			// A fake player in the world now shows its class as it is (also for a count from before the class was kept).
			final Npc spawned = entry.isFake() ? findFakePlayer(entry.getName()) : null;
			final PlayerClass playerClass = spawned != null ? getFakePlayerClass(spawned) : null;
			if ((playerClass != null) && (playerClass.getId() != entry.getClassId()))
			{
				_fakeClasses.put(entry.getName(), playerClass.getId());
				_changed.add(entry.getName());
				top.add(new Entry(entry.getName(), entry.getKills(), playerClass.getId(), true));
			}
			else
			{
				top.add(entry);
			}
		}
		_ranking = Collections.unmodifiableList(top);
		_rankingTime = now;
		return _ranking;
	}

	public static PvpRankingManager getInstance()
	{
		return SingletonHolder.INSTANCE;
	}

	private static class SingletonHolder
	{
		protected static final PvpRankingManager INSTANCE = new PvpRankingManager();
	}
}
