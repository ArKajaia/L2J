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
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.StringJoiner;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.l2jmobius.commons.database.DatabaseFactory;
import org.l2jmobius.commons.threads.ThreadPool;
import org.l2jmobius.gameserver.config.PlayerConfig;
import org.l2jmobius.gameserver.config.custom.BestiaryConfig;
import org.l2jmobius.gameserver.data.SpawnTable;
import org.l2jmobius.gameserver.data.xml.NpcData;
import org.l2jmobius.gameserver.model.World;
import org.l2jmobius.gameserver.model.actor.Attackable;
import org.l2jmobius.gameserver.model.actor.Player;
import org.l2jmobius.gameserver.model.actor.templates.NpcTemplate;
import org.l2jmobius.gameserver.network.serverpackets.ExShowScreenMessage;
import org.l2jmobius.gameserver.network.serverpackets.MagicSkillUse;
import org.l2jmobius.gameserver.network.serverpackets.ServerPacket;
import org.l2jmobius.gameserver.util.FormatUtil;
import org.l2jmobius.gameserver.util.LocationUtil;

/**
 * The bestiary: how many of each monster every character has killed, shown as a collection on the web page of the passive tree server.
 * <ul>
 * <li><b>Kills</b> - a monster killed by a player (or their summon) counts for them, and for the party members near it when BestiaryPartyCredit is on. Only kills that give exp count, so npcs killing npcs and fake players never do.</li>
 * <li><b>Tiers</b> - each monster's entry goes through four tiers (Discovered, Studied, Mastered, Legendary) at the kills BestiaryTiers sets, BestiaryBossTiers for raid and grand bosses. Each tier shows more of the monster on the page.</li>
 * <li><b>Collection</b> - the monsters of the BestiaryTypes that are spawned in the world: the spawn files, raid bosses and grand bosses. Monsters only met elsewhere (instances, quests) are counted too, but not toward completion.</li>
 * <li><b>Storage</b> - a character's entries are loaded at login and kept in memory; the ones that changed are written every BestiaryStoreInterval seconds, at logout and at shutdown (table {@code character_bestiary}).</li>
 * <li><b>Server stats</b> - every BestiaryServerStatsInterval minutes the table is counted up: kills of each monster, how many hunters found it, who found it first, and the ranking of hunters.</li>
 * </ul>
 * @author Claude
 */
public class BestiaryManager
{
	private static final Logger LOGGER = Logger.getLogger(BestiaryManager.class.getName());

	private static final String CREATE_TABLE = "CREATE TABLE IF NOT EXISTS `character_bestiary` (`char_id` INT UNSIGNED NOT NULL, `npc_id` INT UNSIGNED NOT NULL, `kills` INT UNSIGNED NOT NULL DEFAULT 0, `first_kill` BIGINT UNSIGNED NOT NULL DEFAULT 0, `last_kill` BIGINT UNSIGNED NOT NULL DEFAULT 0, PRIMARY KEY (`char_id`, `npc_id`), KEY `npc_id` (`npc_id`)) ENGINE=InnoDB DEFAULT CHARSET=utf8";
	private static final String SELECT_CHARACTER = "SELECT npc_id, kills, first_kill, last_kill FROM character_bestiary WHERE char_id=?";
	private static final String UPSERT = "INSERT INTO character_bestiary (char_id, npc_id, kills, first_kill, last_kill) VALUES (?, ?, ?, ?, ?) ON DUPLICATE KEY UPDATE kills=VALUES(kills), last_kill=VALUES(last_kill)";
	private static final String DELETE_CHARACTER = "DELETE FROM character_bestiary WHERE char_id=?";
	private static final String SELECT_MONSTER_STATS = "SELECT b.npc_id, SUM(b.kills) AS kills, COUNT(*) AS hunters FROM character_bestiary b JOIN characters c ON c.charId=b.char_id WHERE c.accesslevel=0 GROUP BY b.npc_id";
	private static final String SELECT_FIRST_FINDERS = "SELECT b.npc_id, c.char_name, b.first_kill FROM character_bestiary b JOIN characters c ON c.charId=b.char_id JOIN (SELECT b2.npc_id, MIN(b2.first_kill) AS first FROM character_bestiary b2 JOIN characters c2 ON c2.charId=b2.char_id WHERE c2.accesslevel=0 GROUP BY b2.npc_id) m ON m.npc_id=b.npc_id AND m.first=b.first_kill WHERE c.accesslevel=0";
	private static final String SELECT_HUNTERS = "SELECT c.charId, c.char_name, c.level, c.classid, COUNT(*) AS found, SUM(b.kills) AS kills FROM character_bestiary b JOIN characters c ON c.charId=b.char_id WHERE c.accesslevel=0 AND b.npc_id IN (%s) GROUP BY c.charId, c.char_name, c.level, c.classid";

	/** How long the entries of a character who is not online are kept after the page read them. */
	private static final long OFFLINE_CACHE_TIME = 60000;
	private static final int OFFLINE_CACHE_SIZE = 500;

	private static final int FIREWORK = 2024;
	private static final int LARGE_FIREWORK = 2025;
	private static final int SCREEN_MESSAGE_TIME = 5000;

	/** What each tier shows, for the message when an entry reaches it. */
	private static final String[] TIER_UNLOCKS =
	{
		"",
		"its combat stats, elements and skills are in your bestiary",
		"its drops and spoils are in your bestiary",
		"it shines in gold in your bestiary"
	};

	/**
	 * One monster in a character's bestiary.
	 * @param kills how many the character killed
	 * @param firstKill when the first one died (ms)
	 * @param lastKill when the last one died (ms)
	 */
	public record Entry(int kills, long firstKill, long lastKill)
	{
	}

	/**
	 * A monster counted over every hunter.
	 * @param kills how many died, all hunters together
	 * @param hunters how many hunters found it
	 * @param firstFinder who found it first, or {@code null}
	 * @param firstKill when it was found first (ms), or 0
	 */
	public record MonsterStats(long kills, int hunters, String firstFinder, long firstKill)
	{
	}

	/**
	 * A hunter in the ranking.
	 * @param charId the character
	 * @param name its name
	 * @param level its level
	 * @param classId its class
	 * @param found how many monsters of the collection it found
	 * @param kills how many monsters of the collection it killed
	 */
	public record Hunter(int charId, String name, int level, int classId, int found, long kills)
	{
	}

	/**
	 * The server-wide numbers, counted every BestiaryServerStatsInterval minutes.
	 * @param monsters per monster
	 * @param hunters every hunter, the most monsters found first
	 * @param time when they were counted (ms), 0 before the first count
	 */
	public record ServerStats(Map<Integer, MonsterStats> monsters, List<Hunter> hunters, long time)
	{
		/**
		 * @param charId a character
		 * @return its place in the ranking (1 is first), or 0 if it found nothing
		 */
		public int getRank(int charId)
		{
			for (int i = 0; i < hunters.size(); i++)
			{
				if (hunters.get(i).charId() == charId)
				{
					return i + 1;
				}
			}
			return 0;
		}
	}

	/**
	 * The entries of a character who is online.
	 */
	private static final class Record
	{
		final Map<Integer, Entry> entries = new ConcurrentHashMap<>();
		final Set<Integer> changed = ConcurrentHashMap.newKeySet();
		/** Held while the record is written, so two writes of it never cross (an older one landing after a newer one). */
		final Object storeLock = new Object();
	}

	private record OfflineSnapshot(Map<Integer, Entry> entries, long time)
	{
	}

	private final Map<Integer, Record> _records = new ConcurrentHashMap<>();
	private final Map<Integer, OfflineSnapshot> _offline = new ConcurrentHashMap<>();
	private final AtomicBoolean _countingStats = new AtomicBoolean();

	private volatile Set<Integer> _collection;
	private volatile ServerStats _serverStats = new ServerStats(Collections.emptyMap(), Collections.emptyList(), 0);

	protected BestiaryManager()
	{
		try (Connection con = DatabaseFactory.getConnection();
			Statement statement = con.createStatement())
		{
			statement.execute(CREATE_TABLE);
		}
		catch (Exception e)
		{
			LOGGER.log(Level.WARNING, getClass().getSimpleName() + ": Could not create table character_bestiary.", e);
		}

		if (BestiaryConfig.ENABLED)
		{
			final long storeInterval = BestiaryConfig.STORE_INTERVAL * 1000L;
			ThreadPool.scheduleAtFixedRate(this::storeAll, storeInterval, storeInterval);
			final long statsInterval = BestiaryConfig.SERVER_STATS_INTERVAL * 60000L;
			ThreadPool.scheduleAtFixedRate(this::countServerStats, 120000, statsInterval);
			LOGGER.info(getClass().getSimpleName() + ": Enabled.");
		}
	}

	// ---------------------------------------------------------------------------------------------
	// Login, logout, shutdown
	// ---------------------------------------------------------------------------------------------

	/**
	 * Loads the character's bestiary when it enters the world.
	 * @param player the player
	 */
	public void onEnter(Player player)
	{
		// Already loaded (entering the world again without leaving it): its kills not written yet must not be read over.
		if (!BestiaryConfig.ENABLED || _records.containsKey(player.getObjectId()))
		{
			return;
		}

		final Record record = new Record();
		record.entries.putAll(load(player.getObjectId()));
		_records.put(player.getObjectId(), record);
		_offline.remove(player.getObjectId());
	}

	/**
	 * Writes what changed in the character's bestiary when it leaves the world, and lets it go.
	 * @param player the player
	 */
	public void onLeave(Player player)
	{
		final Record record = _records.remove(player.getObjectId());
		if (record != null)
		{
			store(player.getObjectId(), record);
		}
	}

	/**
	 * Writes the entries that changed since the last time, for every character. Runs every BestiaryStoreInterval seconds and at shutdown.
	 */
	public void storeAll()
	{
		for (Map.Entry<Integer, Record> entry : _records.entrySet())
		{
			store(entry.getKey(), entry.getValue());

			// A character that left the world without going through onLeave (should not happen): nothing more will change.
			if (World.getInstance().getPlayer(entry.getKey()) == null)
			{
				_records.remove(entry.getKey(), entry.getValue());
			}
		}
	}

	private void store(int charId, Record record)
	{
		synchronized (record.storeLock)
		{
			if (record.changed.isEmpty())
			{
				return;
			}

			final List<Integer> npcIds = new ArrayList<>(record.changed);
			try (Connection con = DatabaseFactory.getConnection();
				PreparedStatement ps = con.prepareStatement(UPSERT))
			{
				for (int npcId : npcIds)
				{
					// Taken off before the entry is read: a kill that comes in meanwhile marks it again, and the next write takes it.
					record.changed.remove(npcId);
					final Entry entry = record.entries.get(npcId);
					if (entry == null)
					{
						continue;
					}

					ps.setInt(1, charId);
					ps.setInt(2, npcId);
					ps.setInt(3, entry.kills());
					ps.setLong(4, entry.firstKill());
					ps.setLong(5, entry.lastKill());
					ps.addBatch();
				}
				ps.executeBatch();
			}
			catch (Exception e)
			{
				// Kept as changed, so the next write tries again.
				record.changed.addAll(npcIds);
				LOGGER.log(Level.WARNING, getClass().getSimpleName() + ": Could not store the bestiary of character " + charId + ".", e);
			}
		}
	}

	private Map<Integer, Entry> load(int charId)
	{
		final Map<Integer, Entry> entries = new HashMap<>();
		try (Connection con = DatabaseFactory.getConnection();
			PreparedStatement ps = con.prepareStatement(SELECT_CHARACTER))
		{
			ps.setInt(1, charId);
			try (ResultSet rs = ps.executeQuery())
			{
				while (rs.next())
				{
					entries.put(rs.getInt("npc_id"), new Entry(rs.getInt("kills"), rs.getLong("first_kill"), rs.getLong("last_kill")));
				}
			}
		}
		catch (Exception e)
		{
			LOGGER.log(Level.WARNING, getClass().getSimpleName() + ": Could not load the bestiary of character " + charId + ".", e);
		}
		return entries;
	}

	/**
	 * Deletes a character's bestiary, when the character is deleted.
	 * @param con the connection the deletion uses
	 * @param charId the character
	 */
	public void deleteCharacter(Connection con, int charId)
	{
		_offline.remove(charId);
		try (PreparedStatement ps = con.prepareStatement(DELETE_CHARACTER))
		{
			ps.setInt(1, charId);
			ps.execute();
		}
		catch (SQLException e)
		{
			LOGGER.log(Level.WARNING, getClass().getSimpleName() + ": Could not delete the bestiary of character " + charId + ".", e);
		}
	}

	// ---------------------------------------------------------------------------------------------
	// Kills
	// ---------------------------------------------------------------------------------------------

	/**
	 * Called when a monster that gives exp dies.
	 * @param monster the monster
	 * @param player the player the kill is for (the killer, or the owner of the summon or the party of the fake player that killed it)
	 */
	public void onMonsterKilled(Attackable monster, Player player)
	{
		if (!BestiaryConfig.ENABLED || !monster.isMonster() || monster.isFakePlayer() || BestiaryConfig.EXCLUDED_NPC_IDS.contains(monster.getId()))
		{
			return;
		}

		final long now = System.currentTimeMillis();
		addKill(player, monster.getTemplate(), now);
		if (BestiaryConfig.PARTY_CREDIT && player.isInParty())
		{
			for (Player member : player.getParty().getMembers())
			{
				if ((member != player) && LocationUtil.checkIfInRange(PlayerConfig.ALT_PARTY_RANGE, monster, member, true))
				{
					addKill(member, monster.getTemplate(), now);
				}
			}
		}
	}

	private void addKill(Player player, NpcTemplate template, long now)
	{
		final Record record = _records.get(player.getObjectId());
		if (record == null)
		{
			return;
		}

		final int npcId = template.getId();
		final Entry entry = record.entries.compute(npcId, (id, old) -> old == null ? new Entry(1, now, now) : new Entry(old.kills() + 1, old.firstKill(), now));
		record.changed.add(npcId);

		final int tier = getTier(template, entry.kills());
		if (tier > getTier(template, entry.kills() - 1))
		{
			announce(player, template, tier, entry.kills(), record);
		}
	}

	private void announce(Player player, NpcTemplate template, int tier, int kills, Record record)
	{
		final String name = template.getName();
		if (tier == 1)
		{
			if (!BestiaryConfig.DISCOVERY_MESSAGES)
			{
				return;
			}

			final Set<Integer> collection = getCollection();
			if (collection.contains(template.getId()))
			{
				player.sendMessage("Bestiary: new monster discovered, " + name + " (" + FormatUtil.formatAdena(countFound(record.entries.keySet())) + " of " + FormatUtil.formatAdena(collection.size()) + ").");
			}
			else
			{
				player.sendMessage("Bestiary: " + name + " found, a monster met outside the world's hunting grounds.");
			}
			if (BestiaryConfig.SCREEN_MESSAGES)
			{
				player.sendPacket(new ExShowScreenMessage("Bestiary: " + name + " discovered!", ExShowScreenMessage.TOP_CENTER, SCREEN_MESSAGE_TIME, 0, true, true));
			}
			return;
		}

		final String tierName = BestiaryConfig.TIER_NAMES[tier - 1];
		final int[] tiers = getTiers(template);
		if (BestiaryConfig.TIER_MESSAGES)
		{
			final String next = tier < tiers.length ? " Next: " + BestiaryConfig.TIER_NAMES[tier] + " at " + FormatUtil.formatAdena(tiers[tier]) + " kills." : "";
			player.sendMessage("Bestiary: " + name + " is now " + tierName + " (" + FormatUtil.formatAdena(kills) + " kills), " + TIER_UNLOCKS[tier - 1] + "." + next);
			if (BestiaryConfig.SCREEN_MESSAGES)
			{
				player.sendPacket(new ExShowScreenMessage("Bestiary: " + name + " " + tierName + "!", ExShowScreenMessage.TOP_CENTER, SCREEN_MESSAGE_TIME, 0, true, true));
			}
		}
		if (BestiaryConfig.FIREWORKS)
		{
			final ServerPacket packet = new MagicSkillUse(player, player, tier == tiers.length ? LARGE_FIREWORK : FIREWORK, 1, 1, 0);
			if (player.isInvisible())
			{
				player.sendPacket(packet);
			}
			else
			{
				player.broadcastPacket(packet);
			}
		}
	}

	// ---------------------------------------------------------------------------------------------
	// Tiers and collection
	// ---------------------------------------------------------------------------------------------

	/**
	 * @param template a monster
	 * @return {@code true} for raid bosses and grand bosses, which go through their tiers with fewer kills
	 */
	public static boolean isBoss(NpcTemplate template)
	{
		return template.isType("RaidBoss") || template.isType("GrandBoss");
	}

	/**
	 * @param template a monster
	 * @return the kills each tier needs for it
	 */
	public static int[] getTiers(NpcTemplate template)
	{
		return isBoss(template) ? BestiaryConfig.BOSS_TIERS : BestiaryConfig.TIERS;
	}

	/**
	 * @param template a monster
	 * @param kills how many a character killed
	 * @return the tier its entry is at: 0 not found yet, 1 Discovered ... 4 Legendary
	 */
	public static int getTier(NpcTemplate template, int kills)
	{
		final int[] tiers = getTiers(template);
		int tier = 0;
		while ((tier < tiers.length) && (kills >= tiers[tier]))
		{
			tier++;
		}
		return tier;
	}

	/**
	 * @param template an npc
	 * @return {@code true} if monsters of its kind can be part of the collection
	 */
	public static boolean isCollectible(NpcTemplate template)
	{
		return (template != null) && BestiaryConfig.TYPES.contains(template.getType()) && !template.isFakePlayer() && (template.getFakePlayerPvpProfile() == null) && (template.getLevel() > 0) && !BestiaryConfig.EXCLUDED_NPC_IDS.contains(template.getId());
	}

	/**
	 * @return the npc ids of the collection: the monsters spawned in the world (spawn files, raid bosses), the grand bosses, and BestiaryExtraNpcIds. Worked out the first time it is needed, once everything is spawned.
	 */
	public Set<Integer> getCollection()
	{
		Set<Integer> collection = _collection;
		if (collection == null)
		{
			synchronized (this)
			{
				collection = _collection;
				if (collection == null)
				{
					collection = buildCollection();
					_collection = collection;
					LOGGER.info(getClass().getSimpleName() + ": " + collection.size() + " monsters to collect.");
				}
			}
		}
		return collection;
	}

	private Set<Integer> buildCollection()
	{
		final Set<Integer> ids = new HashSet<>();
		for (Integer npcId : SpawnTable.getInstance().getSpawnTable().keySet())
		{
			if (isCollectible(NpcData.getInstance().getTemplate(npcId)))
			{
				ids.add(npcId);
			}
		}

		// Grand bosses are spawned by their scripts, not by a spawn list: the ones with a grandboss_data row.
		for (NpcTemplate template : NpcData.getInstance().getTemplates(template -> template.isType("GrandBoss")))
		{
			if (isCollectible(template) && (GrandBossManager.getInstance().getStatSet(template.getId()) != null))
			{
				ids.add(template.getId());
			}
		}

		for (int npcId : BestiaryConfig.EXTRA_NPC_IDS)
		{
			final NpcTemplate template = NpcData.getInstance().getTemplate(npcId);
			if ((template != null) && !BestiaryConfig.EXCLUDED_NPC_IDS.contains(npcId))
			{
				ids.add(npcId);
			}
		}
		return Collections.unmodifiableSet(ids);
	}

	/**
	 * @param npcIds the monsters a character found
	 * @return how many of them are part of the collection
	 */
	public int countFound(Set<Integer> npcIds)
	{
		final Set<Integer> collection = getCollection();
		int found = 0;
		for (Integer npcId : npcIds)
		{
			if (collection.contains(npcId))
			{
				found++;
			}
		}
		return found;
	}

	// ---------------------------------------------------------------------------------------------
	// Reading
	// ---------------------------------------------------------------------------------------------

	/**
	 * @param charId a character, online or not
	 * @return its entries by npc id: the live ones while it is online, otherwise as they were last written
	 */
	public Map<Integer, Entry> getEntries(int charId)
	{
		final Record record = _records.get(charId);
		if (record != null)
		{
			return new HashMap<>(record.entries);
		}

		final long now = System.currentTimeMillis();
		final OfflineSnapshot cached = _offline.get(charId);
		if ((cached != null) && ((now - cached.time()) < OFFLINE_CACHE_TIME))
		{
			return cached.entries();
		}

		if (_offline.size() >= OFFLINE_CACHE_SIZE)
		{
			_offline.values().removeIf(snapshot -> (now - snapshot.time()) >= OFFLINE_CACHE_TIME);
			if (_offline.size() >= OFFLINE_CACHE_SIZE)
			{
				_offline.clear();
			}
		}

		final Map<Integer, Entry> entries = Collections.unmodifiableMap(load(charId));
		_offline.put(charId, new OfflineSnapshot(entries, now));
		return entries;
	}

	/**
	 * @return the server-wide numbers of the last count (empty until the first one, a couple of minutes after startup)
	 */
	public ServerStats getServerStats()
	{
		if (_serverStats.time() == 0)
		{
			ThreadPool.execute(this::countServerStats);
		}
		return _serverStats;
	}

	/**
	 * Counts the server-wide numbers again. Kills not written yet are left for the next count.
	 */
	private void countServerStats()
	{
		if (!_countingStats.compareAndSet(false, true))
		{
			return;
		}

		try
		{
			final Set<Integer> collection = getCollection();
			final Map<Integer, MonsterStats> monsters = new HashMap<>();
			final List<Hunter> hunters = new ArrayList<>();
			try (Connection con = DatabaseFactory.getConnection();
				Statement statement = con.createStatement())
			{
				final Map<Integer, long[]> counts = new HashMap<>();
				try (ResultSet rs = statement.executeQuery(SELECT_MONSTER_STATS))
				{
					while (rs.next())
					{
						counts.put(rs.getInt("npc_id"), new long[]
						{
							rs.getLong("kills"),
							rs.getInt("hunters")
						});
					}
				}

				final Map<Integer, String> firstNames = new HashMap<>();
				final Map<Integer, Long> firstTimes = new HashMap<>();
				try (ResultSet rs = statement.executeQuery(SELECT_FIRST_FINDERS))
				{
					while (rs.next())
					{
						// Two hunters who found it in the same millisecond: the first one read stays.
						final int npcId = rs.getInt("npc_id");
						firstNames.putIfAbsent(npcId, rs.getString("char_name"));
						firstTimes.putIfAbsent(npcId, rs.getLong("first_kill"));
					}
				}

				for (Map.Entry<Integer, long[]> entry : counts.entrySet())
				{
					final int npcId = entry.getKey();
					monsters.put(npcId, new MonsterStats(entry.getValue()[0], (int) entry.getValue()[1], firstNames.get(npcId), firstTimes.getOrDefault(npcId, 0L)));
				}

				if (!collection.isEmpty())
				{
					final StringJoiner ids = new StringJoiner(",");
					for (Integer npcId : collection)
					{
						ids.add(String.valueOf(npcId));
					}
					try (ResultSet rs = statement.executeQuery(String.format(SELECT_HUNTERS, ids)))
					{
						while (rs.next())
						{
							hunters.add(new Hunter(rs.getInt("charId"), rs.getString("char_name"), rs.getInt("level"), rs.getInt("classid"), rs.getInt("found"), rs.getLong("kills")));
						}
					}
				}
			}

			hunters.sort((a, b) -> a.found() != b.found() ? Integer.compare(b.found(), a.found()) : Long.compare(b.kills(), a.kills()));
			_serverStats = new ServerStats(Collections.unmodifiableMap(monsters), Collections.unmodifiableList(hunters), System.currentTimeMillis());
		}
		catch (Exception e)
		{
			LOGGER.log(Level.WARNING, getClass().getSimpleName() + ": Could not count the server-wide bestiary.", e);
		}
		finally
		{
			_countingStats.set(false);
		}
	}

	public static BestiaryManager getInstance()
	{
		return SingletonHolder.INSTANCE;
	}

	private static class SingletonHolder
	{
		protected static final BestiaryManager INSTANCE = new BestiaryManager();
	}
}
