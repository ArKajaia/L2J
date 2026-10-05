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
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.l2jmobius.commons.database.DatabaseFactory;
import org.l2jmobius.commons.threads.ThreadPool;
import org.l2jmobius.gameserver.model.World;
import org.l2jmobius.gameserver.model.actor.Creature;
import org.l2jmobius.gameserver.model.actor.Npc;
import org.l2jmobius.gameserver.model.actor.Player;
import org.l2jmobius.gameserver.model.actor.instance.FakePlayerPvpServitor;
import org.l2jmobius.gameserver.model.zone.ZoneId;

/**
 * PvP ranking of players and fake players, shown on the Community Board.
 * <ul>
 * <li>Players count their PvP kills like always ({@code characters.pvpkills}).</li>
 * <li>Fake players count theirs here, by name, in table {@code fake_player_pvp}: a kill of a flagged player or fake player, of one with karma, or of anyone in a PvP spot. A name a fake player took again keeps its count.</li>
 * </ul>
 * @author Claude
 */
public class PvpRankingManager
{
	private static final Logger LOGGER = Logger.getLogger(PvpRankingManager.class.getName());

	/** How many names the ranking shows. */
	public static final int RANKING_SIZE = 20;
	/** How long the ranking is kept before it is made again. */
	private static final long CACHE_TIME = 60000;

	private static final String CREATE_TABLE = "CREATE TABLE IF NOT EXISTS `fake_player_pvp` (`name` VARCHAR(35) NOT NULL, `pvp_kills` INT UNSIGNED NOT NULL DEFAULT 0, PRIMARY KEY (`name`)) ENGINE=InnoDB DEFAULT CHARSET=utf8";
	private static final String SELECT_FAKES = "SELECT name, pvp_kills FROM fake_player_pvp";
	private static final String UPDATE_FAKE = "INSERT INTO fake_player_pvp (name, pvp_kills) VALUES (?, ?) ON DUPLICATE KEY UPDATE pvp_kills=VALUES(pvp_kills)";
	private static final String SELECT_PLAYERS = "SELECT char_name, pvpkills FROM characters WHERE pvpkills > 0 AND accesslevel = 0 ORDER BY pvpkills DESC LIMIT " + RANKING_SIZE;

	/** PvP kills of fake players, by name. */
	private final Map<String, Integer> _fakeKills = new ConcurrentHashMap<>();

	private volatile List<Entry> _ranking = Collections.emptyList();
	private volatile long _rankingTime;

	/**
	 * A line of the ranking.
	 */
	public static class Entry
	{
		private final String _name;
		private final int _kills;

		public Entry(String name, int kills)
		{
			_name = name;
			_kills = kills;
		}

		public String getName()
		{
			return _name;
		}

		public int getKills()
		{
			return _kills;
		}
	}

	protected PvpRankingManager()
	{
		try (Connection con = DatabaseFactory.getConnection();
			Statement statement = con.createStatement())
		{
			statement.execute(CREATE_TABLE);
			try (ResultSet rs = statement.executeQuery(SELECT_FAKES))
			{
				while (rs.next())
				{
					_fakeKills.put(rs.getString("name"), rs.getInt("pvp_kills"));
				}
			}
		}
		catch (Exception e)
		{
			LOGGER.log(Level.WARNING, getClass().getSimpleName() + ": Could not load fake player PvP kills.", e);
		}

		LOGGER.info(getClass().getSimpleName() + ": Loaded PvP kills of " + _fakeKills.size() + " fake players.");
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

		final String name = fake.getName();
		final int kills = _fakeKills.merge(name, 1, Integer::sum);
		ThreadPool.execute(() -> store(name, kills));
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

	private void store(String name, int kills)
	{
		try (Connection con = DatabaseFactory.getConnection();
			PreparedStatement ps = con.prepareStatement(UPDATE_FAKE))
		{
			ps.setString(1, name);
			ps.setInt(2, kills);
			ps.execute();
		}
		catch (Exception e)
		{
			LOGGER.log(Level.WARNING, getClass().getSimpleName() + ": Could not store PvP kills of fake player " + name + ".", e);
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
		try (Connection con = DatabaseFactory.getConnection();
			Statement statement = con.createStatement();
			ResultSet rs = statement.executeQuery(SELECT_PLAYERS))
		{
			while (rs.next())
			{
				kills.put(rs.getString("char_name"), rs.getInt("pvpkills"));
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
			ranking.add(new Entry(entry.getKey(), entry.getValue()));
		}

		ranking.sort((a, b) -> (a.getKills() != b.getKills()) ? Integer.compare(b.getKills(), a.getKills()) : a.getName().compareToIgnoreCase(b.getName()));
		_ranking = Collections.unmodifiableList(new ArrayList<>(ranking.subList(0, Math.min(RANKING_SIZE, ranking.size()))));
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
