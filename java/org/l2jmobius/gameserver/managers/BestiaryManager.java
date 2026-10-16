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
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.StringJoiner;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
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
import org.l2jmobius.gameserver.model.actor.Creature;
import org.l2jmobius.gameserver.model.actor.Player;
import org.l2jmobius.gameserver.model.actor.templates.NpcTemplate;
import org.l2jmobius.gameserver.network.serverpackets.ExShowScreenMessage;
import org.l2jmobius.gameserver.network.serverpackets.MagicSkillUse;
import org.l2jmobius.gameserver.network.serverpackets.ServerPacket;
import org.l2jmobius.gameserver.util.FormatUtil;
import org.l2jmobius.gameserver.util.LocationUtil;

/**
 * The bestiary: how many of each monster every account has killed, shown as a collection on the web page of the passive tree server, and the bonuses it gives.
 * <ul>
 * <li><b>Account-wide</b> - the bestiary belongs to the account: every character of it adds to the same entries, and has the same bonuses.</li>
 * <li><b>Kills</b> - a monster killed by a player (or their summon) counts for them, and for the party members near it when BestiaryPartyCredit is on, once per account. Only kills that give exp count, so npcs killing npcs and fake players never do.</li>
 * <li><b>Tiers</b> - each monster's entry goes through four tiers (Discovered, Studied, Mastered, Legendary) at the kills BestiaryTiers sets, BestiaryBossTiers for raid and grand bosses. Each tier shows more of the monster on the page.</li>
 * <li><b>Drop bonus</b> - the drops of a monster (items, not adena or herbs) come more often by its tier's BestiaryDropBonus (BestiaryBossDropBonus for bosses), for the hunter whose drop it is. A higher chance, never an extra roll.</li>
 * <li><b>Region EXP bonus</b> - the monsters of a region (a folder of the spawn files, as the page groups them) give more EXP the more of them the account found and the higher their tiers: each counts for its tier's BestiaryRegionExpBonus divided by the monsters of the region, so a region all Discovered doubles its EXP and all Legendary makes it four times as much (the defaults). The regions come from the bestiary's scripts ({@link #setRegionSource}).</li>
 * <li><b>Collection</b> - the monsters of the BestiaryTypes that are spawned in the world: the spawn files, raid bosses and grand bosses. Monsters only met elsewhere (instances, quests) are counted too, but not toward completion.</li>
 * <li><b>Storage</b> - an account's entries are loaded when one of its characters enters the world and kept in memory; the ones that changed are written every BestiaryStoreInterval seconds, at logout and at shutdown (table {@code account_bestiary}).</li>
 * <li><b>Server stats</b> - every BestiaryServerStatsInterval minutes the table is counted up: kills of each monster, how many hunters (accounts) found it, who found it first, and the ranking of hunters. Accounts with a game master are left out.</li>
 * </ul>
 * @author Claude
 */
public class BestiaryManager
{
	private static final Logger LOGGER = Logger.getLogger(BestiaryManager.class.getName());

	private static final String CREATE_TABLE = "CREATE TABLE IF NOT EXISTS `account_bestiary` (`account_name` VARCHAR(45) NOT NULL, `npc_id` INT UNSIGNED NOT NULL, `kills` INT UNSIGNED NOT NULL DEFAULT 0, `first_kill` BIGINT UNSIGNED NOT NULL DEFAULT 0, `last_kill` BIGINT UNSIGNED NOT NULL DEFAULT 0, `first_char` INT UNSIGNED NOT NULL DEFAULT 0, PRIMARY KEY (`account_name`, `npc_id`), KEY `npc_id` (`npc_id`)) ENGINE=InnoDB DEFAULT CHARSET=utf8 COLLATE=utf8_unicode_ci";
	/** The bestiary of each character, before it became the account's. */
	private static final String FIND_CHARACTER_TABLE = "SHOW TABLES LIKE 'character_bestiary'";
	private static final String MIGRATE_CHARACTER_TABLE = "INSERT IGNORE INTO account_bestiary (account_name, npc_id, kills, first_kill, last_kill, first_char) SELECT c.account_name, b.npc_id, SUM(b.kills), MIN(b.first_kill), MAX(b.last_kill), CAST(SUBSTRING_INDEX(GROUP_CONCAT(b.char_id ORDER BY b.first_kill), ',', 1) AS UNSIGNED) FROM character_bestiary b JOIN characters c ON c.charId=b.char_id WHERE c.account_name IS NOT NULL GROUP BY c.account_name, b.npc_id";
	private static final String DROP_CHARACTER_TABLE = "DROP TABLE character_bestiary";
	private static final String SELECT_ACCOUNT = "SELECT npc_id, kills, first_kill, last_kill, first_char FROM account_bestiary WHERE account_name=?";
	private static final String SELECT_ACCOUNT_NAME = "SELECT account_name FROM characters WHERE charId=?";
	private static final String UPSERT = "INSERT INTO account_bestiary (account_name, npc_id, kills, first_kill, last_kill, first_char) VALUES (?, ?, ?, ?, ?, ?) ON DUPLICATE KEY UPDATE kills=VALUES(kills), last_kill=VALUES(last_kill)";
	/** The accounts counted server-wide: none of their characters is a game master (or has any other access level). */
	private static final String HUNTER_ACCOUNTS = "(SELECT account_name FROM characters WHERE account_name IS NOT NULL GROUP BY account_name HAVING MAX(accesslevel<>0)=0)";
	private static final String SELECT_MONSTER_STATS = "SELECT b.npc_id, SUM(b.kills) AS kills, COUNT(*) AS hunters FROM account_bestiary b JOIN " + HUNTER_ACCOUNTS + " a ON a.account_name=b.account_name GROUP BY b.npc_id";
	private static final String SELECT_FIRST_FINDERS = "SELECT b.npc_id, b.account_name, c.char_name, b.first_kill FROM account_bestiary b JOIN " + HUNTER_ACCOUNTS + " a ON a.account_name=b.account_name JOIN (SELECT b2.npc_id, MIN(b2.first_kill) AS first FROM account_bestiary b2 JOIN " + HUNTER_ACCOUNTS + " a2 ON a2.account_name=b2.account_name GROUP BY b2.npc_id) m ON m.npc_id=b.npc_id AND m.first=b.first_kill LEFT JOIN characters c ON c.charId=b.first_char";
	/** Each account of the ranking goes by its highest character. */
	private static final String SELECT_HUNTERS = "SELECT r.account_name, r.found, r.kills, c.char_name, c.level, c.classid FROM (SELECT b.account_name, COUNT(*) AS found, SUM(b.kills) AS kills FROM account_bestiary b JOIN " + HUNTER_ACCOUNTS + " a ON a.account_name=b.account_name WHERE b.npc_id IN (%s) GROUP BY b.account_name) r JOIN characters c ON c.charId=(SELECT c2.charId FROM characters c2 WHERE c2.account_name=r.account_name ORDER BY c2.level DESC, c2.exp DESC, c2.charId LIMIT 1)";

	/** How long the entries of an account with no character online are kept after the page read them. */
	private static final long OFFLINE_CACHE_TIME = 60000;
	private static final int OFFLINE_CACHE_SIZE = 500;
	private static final int ACCOUNT_CACHE_SIZE = 5000;

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
	 * One monster in an account's bestiary.
	 * @param kills how many the account's characters killed
	 * @param firstKill when the first one died (ms)
	 * @param lastKill when the last one died (ms)
	 * @param firstChar the character that killed the first one
	 */
	public record Entry(int kills, long firstKill, long lastKill, int firstChar)
	{
	}

	/**
	 * A monster counted over every hunter.
	 * @param kills how many died, all hunters together
	 * @param hunters how many hunters (accounts) found it
	 * @param firstFinder the character who found it first, or {@code null}
	 * @param firstKill when it was found first (ms), or 0
	 */
	public record MonsterStats(long kills, int hunters, String firstFinder, long firstKill)
	{
	}

	/**
	 * A hunter (an account) in the ranking, going by its highest character.
	 * @param account the account, never shown
	 * @param name its highest character's name
	 * @param level that character's level
	 * @param classId that character's class
	 * @param found how many monsters of the collection the account found
	 * @param kills how many monsters of the collection it killed
	 */
	public record Hunter(String account, String name, int level, int classId, int found, long kills)
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
		 * @param account an account
		 * @return its place in the ranking (1 is first), or 0 if it found nothing
		 */
		public int getRank(String account)
		{
			for (int i = 0; i < hunters.size(); i++)
			{
				if (hunters.get(i).account().equalsIgnoreCase(account))
				{
					return i + 1;
				}
			}
			return 0;
		}
	}

	/**
	 * The regions of the collection, from the bestiary's scripts.
	 * @param monsters the monsters of each region
	 * @param byMonster the regions each monster lives in (a few live in two)
	 */
	private record Regions(Map<String, int[]> monsters, Map<Integer, String[]> byMonster)
	{
	}

	private static final Regions NO_REGIONS = new Regions(Collections.emptyMap(), Collections.emptyMap());

	/**
	 * The EXP multiplier of each region for an account, as its entries were at a count of tier changes.
	 */
	private record RegionExp(int version, Map<String, Double> multipliers)
	{
	}

	/**
	 * The entries of an account with a character in the world.
	 */
	private static final class Record
	{
		final String account;
		final Map<Integer, Entry> entries = new ConcurrentHashMap<>();
		final Set<Integer> changed = ConcurrentHashMap.newKeySet();
		/** The account's characters in the world: more than one with offline shops and offline play. */
		final Set<Integer> players = ConcurrentHashMap.newKeySet();
		/** Held while the record is written, so two writes of it never cross (an older one landing after a newer one). */
		final Object storeLock = new Object();
		/** Goes up each time an entry reaches a new tier, which changes the regions' EXP. */
		final AtomicInteger tierChanges = new AtomicInteger();
		volatile RegionExp regionExp;

		Record(String account)
		{
			this.account = account;
		}
	}

	private record OfflineSnapshot(Map<Integer, Entry> entries, long time)
	{
	}

	/** By account, lowercase. */
	private final Map<String, Record> _records = new ConcurrentHashMap<>();
	private final Map<String, OfflineSnapshot> _offline = new ConcurrentHashMap<>();
	/** The account of characters not online, by character ("" for none). */
	private final Map<Integer, String> _accounts = new ConcurrentHashMap<>();
	private final AtomicBoolean _countingStats = new AtomicBoolean();
	private final Object _regionLock = new Object();

	private volatile Set<Integer> _collection;
	private volatile Supplier<Map<String, ? extends Collection<Integer>>> _regionSource;
	private volatile Regions _regions;
	private volatile ServerStats _serverStats = new ServerStats(Collections.emptyMap(), Collections.emptyList(), 0);

	protected BestiaryManager()
	{
		try (Connection con = DatabaseFactory.getConnection();
			Statement statement = con.createStatement())
		{
			statement.execute(CREATE_TABLE);
			migrateCharacterTable(statement);
		}
		catch (Exception e)
		{
			LOGGER.log(Level.WARNING, getClass().getSimpleName() + ": Could not create table account_bestiary.", e);
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

	/**
	 * The bestiary was each character's before it became the account's: their kills are added up into their account's, once.
	 */
	private void migrateCharacterTable(Statement statement) throws Exception
	{
		try (ResultSet rs = statement.executeQuery(FIND_CHARACTER_TABLE))
		{
			if (!rs.next())
			{
				return;
			}
		}

		// INSERT IGNORE: run again after a stop between the two statements, it adds nothing twice.
		final int rows = statement.executeUpdate(MIGRATE_CHARACTER_TABLE);
		statement.execute(DROP_CHARACTER_TABLE);
		LOGGER.info(getClass().getSimpleName() + ": Moved the characters' bestiaries to their accounts (" + rows + " entries), table character_bestiary removed.");
	}

	// ---------------------------------------------------------------------------------------------
	// Login, logout, shutdown
	// ---------------------------------------------------------------------------------------------

	/**
	 * Loads the account's bestiary when one of its characters enters the world.
	 * @param player the player
	 */
	public void onEnter(Player player)
	{
		if (BestiaryConfig.ENABLED)
		{
			getRecord(player);
		}
	}

	/**
	 * Writes what changed in the account's bestiary when one of its characters leaves the world. The entries stay in memory until the next write finds none of its characters left, so a character entering right after reads them as they are.
	 * @param player the player
	 */
	public void onLeave(Player player)
	{
		final String account = player.getAccountName();
		final Record record = account != null ? _records.get(account.toLowerCase(Locale.ROOT)) : null;
		if (record != null)
		{
			record.players.remove(player.getObjectId());
			store(record);
		}
	}

	/**
	 * Works out the collection and the regions once the world is spawned, so the first kill doesn't wait for them.
	 */
	public void onServerStarted()
	{
		if (BestiaryConfig.ENABLED)
		{
			ThreadPool.execute(() ->
			{
				getCollection();
				getRegions();
			});
		}
	}

	/**
	 * @param player a player
	 * @return its account's record, loaded if no character of the account was in the world (offline players restored at startup kill without entering it), or {@code null} without an account
	 */
	private Record getRecord(Player player)
	{
		final String account = player.getAccountName();
		if (account == null)
		{
			return null;
		}

		final String key = account.toLowerCase(Locale.ROOT);
		final int objectId = player.getObjectId();
		final Record existing = _records.get(key);
		if ((existing != null) && existing.players.contains(objectId))
		{
			return existing;
		}

		// Loaded outside the map's lock; adding the character and letting the record go (storeAll) both go through compute, so neither misses the other.
		final Map<Integer, Entry> loaded = existing == null ? load(account) : null;
		final Record record = _records.compute(key, (k, old) ->
		{
			Record result = old;
			if (result == null)
			{
				result = new Record(account);
				result.entries.putAll(loaded != null ? loaded : load(account));
			}
			result.players.add(objectId);
			return result;
		});
		_offline.remove(key);
		return record;
	}

	/**
	 * Writes the entries that changed since the last time, for every account, and lets go of the accounts with no character left in the world. Runs every BestiaryStoreInterval seconds and at shutdown.
	 */
	public void storeAll()
	{
		for (Map.Entry<String, Record> entry : _records.entrySet())
		{
			final Record record = entry.getValue();
			// A character that left the world without going through onLeave (should not happen) holds nothing.
			record.players.removeIf(objectId -> World.getInstance().getPlayer(objectId) == null);
			store(record);
			_records.computeIfPresent(entry.getKey(), (key, current) -> (current == record) && current.players.isEmpty() && current.changed.isEmpty() ? null : current);
		}
	}

	private void store(Record record)
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

					ps.setString(1, record.account);
					ps.setInt(2, npcId);
					ps.setInt(3, entry.kills());
					ps.setLong(4, entry.firstKill());
					ps.setLong(5, entry.lastKill());
					ps.setInt(6, entry.firstChar());
					ps.addBatch();
				}
				ps.executeBatch();
			}
			catch (Exception e)
			{
				// Kept as changed, so the next write tries again.
				record.changed.addAll(npcIds);
				LOGGER.log(Level.WARNING, getClass().getSimpleName() + ": Could not store the bestiary of account " + record.account + ".", e);
			}
		}
	}

	private Map<Integer, Entry> load(String account)
	{
		final Map<Integer, Entry> entries = new HashMap<>();
		try (Connection con = DatabaseFactory.getConnection();
			PreparedStatement ps = con.prepareStatement(SELECT_ACCOUNT))
		{
			ps.setString(1, account);
			try (ResultSet rs = ps.executeQuery())
			{
				while (rs.next())
				{
					entries.put(rs.getInt("npc_id"), new Entry(rs.getInt("kills"), rs.getLong("first_kill"), rs.getLong("last_kill"), rs.getInt("first_char")));
				}
			}
		}
		catch (Exception e)
		{
			LOGGER.log(Level.WARNING, getClass().getSimpleName() + ": Could not load the bestiary of account " + account + ".", e);
		}
		return entries;
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
		final Set<Record> credited = new HashSet<>(2);
		addKill(player, monster.getTemplate(), now, credited);
		if (BestiaryConfig.PARTY_CREDIT && player.isInParty())
		{
			for (Player member : player.getParty().getMembers())
			{
				if ((member != player) && LocationUtil.checkIfInRange(PlayerConfig.ALT_PARTY_RANGE, monster, member, true))
				{
					addKill(member, monster.getTemplate(), now, credited);
				}
			}
		}
	}

	/**
	 * Counts the kill for the player's account, unless another character of the account in the party already counted it.
	 */
	private void addKill(Player player, NpcTemplate template, long now, Set<Record> credited)
	{
		final Record record = getRecord(player);
		if ((record == null) || !credited.add(record))
		{
			return;
		}

		final int npcId = template.getId();
		final int charId = player.getObjectId();
		final Entry entry = record.entries.compute(npcId, (id, old) -> old == null ? new Entry(1, now, now, charId) : new Entry(old.kills() + 1, old.firstKill(), now, old.firstChar()));
		record.changed.add(npcId);

		final int tier = getTier(template, entry.kills());
		if (tier > getTier(template, entry.kills() - 1))
		{
			record.tierChanges.incrementAndGet();
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
				player.sendMessage("Bestiary: new monster discovered, " + name + " (" + FormatUtil.formatAdena(countFound(record.entries.keySet())) + " of " + FormatUtil.formatAdena(collection.size()) + ")." + regionExpLine(record, template));
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
			final int dropBonus = getDropBonus(template, tier);
			final String drops = dropBonus > 0 ? ", and its items drop " + dropBonus + "% more often for your account" : "";
			final String next = tier < tiers.length ? " Next: " + BestiaryConfig.TIER_NAMES[tier] + " at " + FormatUtil.formatAdena(tiers[tier]) + " kills." : "";
			player.sendMessage("Bestiary: " + name + " is now " + tierName + " (" + FormatUtil.formatAdena(kills) + " kills), " + TIER_UNLOCKS[tier - 1] + drops + "." + regionExpLine(record, template) + next);
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

	/** @return " EXP in Dion: x1.42." for the regions the monster lives in, or "" */
	private String regionExpLine(Record record, NpcTemplate template)
	{
		final String[] regions = getRegions().byMonster().get(template.getId());
		if (regions == null)
		{
			return "";
		}

		final Map<String, Double> multipliers = getRegionExp(record);
		final StringJoiner line = new StringJoiner(", ", " EXP in ", ".");
		for (String region : regions)
		{
			line.add(region + ": x" + String.format(Locale.US, "%.2f", multipliers.getOrDefault(region, 1.0)));
		}
		return line.toString();
	}

	// ---------------------------------------------------------------------------------------------
	// Bonuses
	// ---------------------------------------------------------------------------------------------

	/**
	 * @param template a monster
	 * @param tier the tier of its entry
	 * @return how much more often (percent) its items drop at that tier
	 */
	public static int getDropBonus(NpcTemplate template, int tier)
	{
		return tier > 0 ? (isBoss(template) ? BestiaryConfig.BOSS_DROP_BONUS : BestiaryConfig.DROP_BONUS)[tier - 1] : 0;
	}

	/**
	 * The drop bonus of the hunter whose drop it is, for the chance of the monster's items (not adena or herbs, which the caller leaves out).
	 * @param template the monster
	 * @param killer who the drop is for: the top damage dealer, or the party leader
	 * @return the multiplier of the items' drop chance
	 */
	public double getDropMultiplier(NpcTemplate template, Creature killer)
	{
		if (!BestiaryConfig.ENABLED || (killer == null))
		{
			return 1;
		}

		final Player player = killer.asPlayer();
		final Record record = player != null ? getRecord(player) : null;
		final Entry entry = record != null ? record.entries.get(template.getId()) : null;
		return entry != null ? 1 + (getDropBonus(template, getTier(template, entry.kills())) / 100.0) : 1;
	}

	/**
	 * @param player a player gaining EXP from the monster
	 * @param monster the monster
	 * @return the multiplier of that EXP: the bonus of the region the monster lives in (the best one, for a monster living in two)
	 */
	public double getExpMultiplier(Player player, Attackable monster)
	{
		if (!BestiaryConfig.ENABLED || (player == null) || (monster == null) || !monster.isMonster())
		{
			return 1;
		}

		final String[] regions = getRegions().byMonster().get(monster.getId());
		final Record record = regions != null ? getRecord(player) : null;
		if (record == null)
		{
			return 1;
		}

		final Map<String, Double> multipliers = getRegionExp(record);
		double best = 1;
		for (String region : regions)
		{
			best = Math.max(best, multipliers.getOrDefault(region, 1.0));
		}
		return best;
	}

	private Map<String, Double> getRegionExp(Record record)
	{
		final RegionExp cached = record.regionExp;
		final int version = record.tierChanges.get();
		if ((cached != null) && (cached.version() == version))
		{
			return cached.multipliers();
		}

		// Read after the version: a tier reached meanwhile changes the version again, and the next call works it out again.
		final Map<String, Double> multipliers = getRegionExp(record.entries);
		record.regionExp = new RegionExp(version, multipliers);
		return multipliers;
	}

	/**
	 * @param entries an account's entries
	 * @return the EXP multiplier of each region: 1 plus each monster's tier bonus (BestiaryRegionExpBonus) divided by the monsters of the region
	 */
	public Map<String, Double> getRegionExp(Map<Integer, Entry> entries)
	{
		final Map<String, Double> multipliers = new HashMap<>();
		for (Map.Entry<String, int[]> region : getRegions().monsters().entrySet())
		{
			final int[] npcIds = region.getValue();
			double sum = 0;
			for (int npcId : npcIds)
			{
				final Entry entry = entries.get(npcId);
				final NpcTemplate template = entry != null ? NpcData.getInstance().getTemplate(npcId) : null;
				final int tier = template != null ? getTier(template, entry.kills()) : 0;
				if (tier > 0)
				{
					sum += BestiaryConfig.REGION_EXP_BONUS[tier - 1];
				}
			}
			multipliers.put(region.getKey(), 1 + (sum / (100.0 * npcIds.length)));
		}
		return multipliers;
	}

	/**
	 * Gives the bestiary the regions of the collection and their monsters, as the page groups them. Worked out the first time a bonus needs them, once everything is spawned.
	 * @param source the monsters of each region
	 */
	public void setRegionSource(Supplier<Map<String, ? extends Collection<Integer>>> source)
	{
		synchronized (_regionLock)
		{
			_regionSource = source;
			_regions = null;
		}
	}

	private Regions getRegions()
	{
		Regions regions = _regions;
		if (regions == null)
		{
			// The scripts did not give them (yet): no region bonus.
			if (_regionSource == null)
			{
				return NO_REGIONS;
			}

			synchronized (_regionLock)
			{
				regions = _regions;
				if (regions == null)
				{
					final Supplier<Map<String, ? extends Collection<Integer>>> source = _regionSource;
					if (source == null)
					{
						return NO_REGIONS;
					}

					regions = buildRegions(source);
					_regions = regions;
				}
			}
		}
		return regions;
	}

	private Regions buildRegions(Supplier<Map<String, ? extends Collection<Integer>>> source)
	{
		try
		{
			final Map<String, int[]> monsters = new HashMap<>();
			final Map<Integer, List<String>> byMonster = new HashMap<>();
			for (Map.Entry<String, ? extends Collection<Integer>> region : source.get().entrySet())
			{
				if (region.getValue().isEmpty())
				{
					continue;
				}

				final int[] npcIds = new int[region.getValue().size()];
				int i = 0;
				for (Integer npcId : region.getValue())
				{
					npcIds[i++] = npcId;
					byMonster.computeIfAbsent(npcId, id -> new ArrayList<>(1)).add(region.getKey());
				}
				monsters.put(region.getKey(), npcIds);
			}

			final Map<Integer, String[]> regionsOf = new HashMap<>();
			for (Map.Entry<Integer, List<String>> entry : byMonster.entrySet())
			{
				regionsOf.put(entry.getKey(), entry.getValue().toArray(new String[0]));
			}
			LOGGER.info(getClass().getSimpleName() + ": " + monsters.size() + " regions for the EXP bonus.");
			return new Regions(Collections.unmodifiableMap(monsters), Collections.unmodifiableMap(regionsOf));
		}
		catch (Exception e)
		{
			LOGGER.log(Level.WARNING, getClass().getSimpleName() + ": Could not read the regions of the collection, no region EXP bonus.", e);
			return NO_REGIONS;
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
	 * @param kills how many an account killed
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
	 * @param npcIds the monsters an account found
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
	 * @return its account, or {@code null} if it has none
	 */
	public String getAccountName(int charId)
	{
		final Player player = World.getInstance().getPlayer(charId);
		if (player != null)
		{
			return player.getAccountName();
		}

		String account = _accounts.get(charId);
		if (account == null)
		{
			account = "";
			try (Connection con = DatabaseFactory.getConnection();
				PreparedStatement ps = con.prepareStatement(SELECT_ACCOUNT_NAME))
			{
				ps.setInt(1, charId);
				try (ResultSet rs = ps.executeQuery())
				{
					if (rs.next() && (rs.getString(1) != null))
					{
						account = rs.getString(1);
					}
				}
			}
			catch (Exception e)
			{
				LOGGER.log(Level.WARNING, getClass().getSimpleName() + ": Could not read the account of character " + charId + ".", e);
				return null;
			}

			if (_accounts.size() >= ACCOUNT_CACHE_SIZE)
			{
				_accounts.clear();
			}
			_accounts.put(charId, account);
		}
		return account.isEmpty() ? null : account;
	}

	/**
	 * @param charId a character, online or not
	 * @return the entries of its account by npc id: the live ones while one of its characters is online, otherwise as they were last written
	 */
	public Map<Integer, Entry> getEntries(int charId)
	{
		final String account = getAccountName(charId);
		if (account == null)
		{
			return Collections.emptyMap();
		}

		final String key = account.toLowerCase(Locale.ROOT);
		final Record record = _records.get(key);
		if (record != null)
		{
			return new HashMap<>(record.entries);
		}

		final long now = System.currentTimeMillis();
		final OfflineSnapshot cached = _offline.get(key);
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

		final Map<Integer, Entry> entries = Collections.unmodifiableMap(load(account));
		_offline.put(key, new OfflineSnapshot(entries, now));
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

				final Map<String, String> names = new HashMap<>(); // highest character of each account
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
							final Hunter hunter = new Hunter(rs.getString("account_name"), rs.getString("char_name"), rs.getInt("level"), rs.getInt("classid"), rs.getInt("found"), rs.getLong("kills"));
							hunters.add(hunter);
							names.put(hunter.account().toLowerCase(Locale.ROOT), hunter.name());
						}
					}
				}

				final Map<Integer, String> firstNames = new HashMap<>();
				final Map<Integer, Long> firstTimes = new HashMap<>();
				try (ResultSet rs = statement.executeQuery(SELECT_FIRST_FINDERS))
				{
					while (rs.next())
					{
						// The character that found it, or the account's highest one if it was deleted since. Two hunters who found it in the same millisecond: the first one read stays.
						final int npcId = rs.getInt("npc_id");
						final String name = rs.getString("char_name");
						final String finder = name != null ? name : names.get(rs.getString("account_name").toLowerCase(Locale.ROOT));
						if ((finder != null) && !firstNames.containsKey(npcId))
						{
							firstNames.put(npcId, finder);
							firstTimes.put(npcId, rs.getLong("first_kill"));
						}
					}
				}

				for (Map.Entry<Integer, long[]> entry : counts.entrySet())
				{
					final int npcId = entry.getKey();
					monsters.put(npcId, new MonsterStats(entry.getValue()[0], (int) entry.getValue()[1], firstNames.get(npcId), firstTimes.getOrDefault(npcId, 0L)));
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
