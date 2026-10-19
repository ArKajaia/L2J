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
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
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
import java.util.concurrent.atomic.AtomicIntegerArray;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.l2jmobius.commons.database.DatabaseFactory;
import org.l2jmobius.commons.threads.ThreadPool;
import org.l2jmobius.commons.util.Rnd;
import org.l2jmobius.gameserver.config.PlayerConfig;
import org.l2jmobius.gameserver.config.custom.BestiaryConfig;
import org.l2jmobius.gameserver.config.custom.ChampionMonstersConfig;
import org.l2jmobius.gameserver.config.custom.InfusedMonsterConfig;
import org.l2jmobius.gameserver.config.custom.MageMonsterConfig;
import org.l2jmobius.gameserver.config.custom.ResonantMonsterConfig;
import org.l2jmobius.gameserver.data.SpawnTable;
import org.l2jmobius.gameserver.data.xml.ItemData;
import org.l2jmobius.gameserver.data.xml.NpcData;
import org.l2jmobius.gameserver.model.World;
import org.l2jmobius.gameserver.model.actor.Attackable;
import org.l2jmobius.gameserver.model.actor.Creature;
import org.l2jmobius.gameserver.model.actor.Player;
import org.l2jmobius.gameserver.model.actor.enums.creature.Race;
import org.l2jmobius.gameserver.model.actor.instance.Monster;
import org.l2jmobius.gameserver.model.actor.templates.NpcTemplate;
import org.l2jmobius.gameserver.model.guide.GuideLesson;
import org.l2jmobius.gameserver.model.item.ItemTemplate;
import org.l2jmobius.gameserver.model.item.enums.ItemProcessType;
import org.l2jmobius.gameserver.model.item.holders.ItemHolder;
import org.l2jmobius.gameserver.model.itemcontainer.Inventory;
import org.l2jmobius.gameserver.model.spawns.Spawn;
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
 * <li><b>Server stats</b> - every BestiaryServerStatsInterval minutes the table is counted up: kills of each monster, how many hunters (accounts) found it, who found it first, its fastest kill, and the ranking of hunters. Accounts with a game master are left out.</li>
 * <li><b>Rare forms</b> - each entry remembers which variants of the monster the account killed: Champion, Mage, Infused and Resonant (the ones enabled).</li>
 * <li><b>Records</b> - each entry keeps the account's fastest kill (from the first hit to the death) and who made it; raid and grand boss kills go into a server-wide log ({@code bestiary_boss_kills}).</li>
 * <li><b>Slayer</b> - the account deals more damage to the monsters of a race, and takes less from them, the more of that race it found and the higher their tiers (BestiarySlayerDamageBonus, BestiarySlayerDefenseBonus), counted like the region EXP bonus. Races with fewer than BestiarySlayerMinMonsters monsters in the collection give none.</li>
 * <li><b>Observed drops</b> - every kill a player's drops are rolled for counts, with the items that came out of it, and every spoiled kill the same for the spoil: the page shows how often each item really drops next to its chance ({@code bestiary_drop_stats}, written with the kills).</li>
 * <li><b>Bounties</b> - every day (from BestiaryBountyResetHour) each account gets BestiaryBountyCount monsters to hunt near the level of the character that rolled them, preferring the ones it has the least of. While a bounty is open its kills count BestiaryBountyKillMultiplier times, and finishing it pays adena and BestiaryBountyRewardItems ({@code account_bestiary_bounty}).</li>
 * </ul>
 * @author Claude
 */
public class BestiaryManager
{
	private static final Logger LOGGER = Logger.getLogger(BestiaryManager.class.getName());

	private static final String CREATE_TABLE = "CREATE TABLE IF NOT EXISTS `account_bestiary` (`account_name` VARCHAR(45) NOT NULL, `npc_id` INT UNSIGNED NOT NULL, `kills` INT UNSIGNED NOT NULL DEFAULT 0, `first_kill` BIGINT UNSIGNED NOT NULL DEFAULT 0, `last_kill` BIGINT UNSIGNED NOT NULL DEFAULT 0, `first_char` INT UNSIGNED NOT NULL DEFAULT 0, `variants` TINYINT UNSIGNED NOT NULL DEFAULT 0, `best_time` INT UNSIGNED NOT NULL DEFAULT 0, `best_char` INT UNSIGNED NOT NULL DEFAULT 0, PRIMARY KEY (`account_name`, `npc_id`), KEY `npc_id` (`npc_id`)) ENGINE=InnoDB DEFAULT CHARSET=utf8 COLLATE=utf8_unicode_ci";
	/** Columns added since the table first came: a table made before gets them at startup. */
	private static final String[][] ADDED_COLUMNS =
	{
		{
			"variants",
			"TINYINT UNSIGNED NOT NULL DEFAULT 0"
		},
		{
			"best_time",
			"INT UNSIGNED NOT NULL DEFAULT 0"
		},
		{
			"best_char",
			"INT UNSIGNED NOT NULL DEFAULT 0"
		}
	};
	private static final String CREATE_BOUNTY_TABLE = "CREATE TABLE IF NOT EXISTS `account_bestiary_bounty` (`account_name` VARCHAR(45) NOT NULL, `day` INT UNSIGNED NOT NULL DEFAULT 0, `npc_ids` VARCHAR(100) NOT NULL DEFAULT '', `progress` VARCHAR(100) NOT NULL DEFAULT '', PRIMARY KEY (`account_name`)) ENGINE=InnoDB DEFAULT CHARSET=utf8 COLLATE=utf8_unicode_ci";
	private static final String CREATE_DROP_TABLE = "CREATE TABLE IF NOT EXISTS `bestiary_drop_stats` (`npc_id` INT UNSIGNED NOT NULL, `spoil` TINYINT UNSIGNED NOT NULL DEFAULT 0, `item_id` INT UNSIGNED NOT NULL, `times` BIGINT UNSIGNED NOT NULL DEFAULT 0, `amount` BIGINT UNSIGNED NOT NULL DEFAULT 0, PRIMARY KEY (`npc_id`, `spoil`, `item_id`)) ENGINE=InnoDB DEFAULT CHARSET=utf8 COLLATE=utf8_unicode_ci";
	private static final String SELECT_DROP_STATS = "SELECT npc_id, spoil, item_id, times, amount FROM bestiary_drop_stats";
	private static final String UPSERT_DROP_STATS = "INSERT INTO bestiary_drop_stats (npc_id, spoil, item_id, times, amount) VALUES (?, ?, ?, ?, ?) ON DUPLICATE KEY UPDATE times=VALUES(times), amount=VALUES(amount)";
	private static final String CREATE_BOSS_TABLE = "CREATE TABLE IF NOT EXISTS `bestiary_boss_kills` (`id` INT UNSIGNED NOT NULL AUTO_INCREMENT, `npc_id` INT UNSIGNED NOT NULL, `kill_time` BIGINT UNSIGNED NOT NULL, `duration` INT UNSIGNED NOT NULL DEFAULT 0, `killer` VARCHAR(35) NOT NULL, `clan` VARCHAR(45) NOT NULL DEFAULT '', `party_size` TINYINT UNSIGNED NOT NULL DEFAULT 1, PRIMARY KEY (`id`), KEY `npc_time` (`npc_id`, `kill_time`)) ENGINE=InnoDB DEFAULT CHARSET=utf8 COLLATE=utf8_unicode_ci";
	/** The bestiary of each character, before it became the account's. */
	private static final String FIND_CHARACTER_TABLE = "SHOW TABLES LIKE 'character_bestiary'";
	private static final String MIGRATE_CHARACTER_TABLE = "INSERT IGNORE INTO account_bestiary (account_name, npc_id, kills, first_kill, last_kill, first_char) SELECT c.account_name, b.npc_id, SUM(b.kills), MIN(b.first_kill), MAX(b.last_kill), CAST(SUBSTRING_INDEX(GROUP_CONCAT(b.char_id ORDER BY b.first_kill), ',', 1) AS UNSIGNED) FROM character_bestiary b JOIN characters c ON c.charId=b.char_id WHERE c.account_name IS NOT NULL GROUP BY c.account_name, b.npc_id";
	private static final String DROP_CHARACTER_TABLE = "DROP TABLE character_bestiary";
	private static final String SELECT_ACCOUNT = "SELECT npc_id, kills, first_kill, last_kill, first_char, variants, best_time, best_char FROM account_bestiary WHERE account_name=?";
	private static final String SELECT_ACCOUNT_NAME = "SELECT account_name FROM characters WHERE charId=?";
	private static final String UPSERT = "INSERT INTO account_bestiary (account_name, npc_id, kills, first_kill, last_kill, first_char, variants, best_time, best_char) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?) ON DUPLICATE KEY UPDATE kills=VALUES(kills), last_kill=VALUES(last_kill), variants=VALUES(variants), best_time=VALUES(best_time), best_char=VALUES(best_char)";
	private static final String SELECT_BOUNTY = "SELECT day, npc_ids, progress FROM account_bestiary_bounty WHERE account_name=?";
	private static final String UPSERT_BOUNTY = "REPLACE INTO account_bestiary_bounty (account_name, day, npc_ids, progress) VALUES (?, ?, ?, ?)";
	private static final String INSERT_BOSS_KILL = "INSERT INTO bestiary_boss_kills (npc_id, kill_time, duration, killer, clan, party_size) VALUES (?, ?, ?, ?, ?, ?)";
	private static final String SELECT_BOSS_KILLS = "SELECT kill_time, duration, killer, clan, party_size FROM bestiary_boss_kills WHERE npc_id=? ORDER BY kill_time DESC LIMIT ?";
	/** The accounts counted server-wide: none of their characters is a game master (or has any other access level). */
	private static final String HUNTER_ACCOUNTS = "(SELECT account_name FROM characters WHERE account_name IS NOT NULL GROUP BY account_name HAVING MAX(accesslevel<>0)=0)";
	private static final String SELECT_MONSTER_STATS = "SELECT b.npc_id, SUM(b.kills) AS kills, COUNT(*) AS hunters, MIN(NULLIF(b.best_time, 0)) AS best_time FROM account_bestiary b JOIN " + HUNTER_ACCOUNTS + " a ON a.account_name=b.account_name GROUP BY b.npc_id";
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

	/** The rare forms an entry remembers, as bits of its {@code variants}. */
	public static final int VARIANT_CHAMPION = 1;
	public static final int VARIANT_MAGE = 2;
	public static final int VARIANT_INFUSED = 4;
	public static final int VARIANT_RESONANT = 8;
	/** Their names, by bit (1, 2, 4, 8). */
	public static final String[] VARIANT_NAMES =
	{
		"Champion",
		"Mage",
		"Infused",
		"Resonant"
	};

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
	 * @param variants the rare forms killed, as VARIANT_* bits
	 * @param bestTime the fastest kill (ms from the first hit to the death), 0 if none was timed
	 * @param bestChar the character that made it
	 */
	public record Entry(int kills, long firstKill, long lastKill, int firstChar, int variants, int bestTime, int bestChar)
	{
	}

	/**
	 * A monster counted over every hunter.
	 * @param kills how many died, all hunters together
	 * @param hunters how many hunters (accounts) found it
	 * @param firstFinder the character who found it first, or {@code null}
	 * @param firstKill when it was found first (ms), or 0
	 * @param bestTime the fastest kill of any hunter (ms), or 0
	 */
	public record MonsterStats(long kills, int hunters, String firstFinder, long firstKill, int bestTime)
	{
	}

	/**
	 * How often an item really came out of a monster's drops or spoil.
	 * @param itemId the item
	 * @param times in how many of the rolls it came out
	 * @param amount how many of it came out, all those rolls together
	 */
	public record ObservedItem(int itemId, long times, long amount)
	{
	}

	/**
	 * What a monster really dropped across the server.
	 * @param dropRolls how many of its kills had their drops rolled
	 * @param drops the items that dropped
	 * @param spoilRolls how many of its kills were spoiled
	 * @param spoils the items its spoil gave
	 */
	public record ObservedDrops(long dropRolls, List<ObservedItem> drops, long spoilRolls, List<ObservedItem> spoils)
	{
	}

	/**
	 * A count of the observed drops: how many times, and how many in all.
	 */
	private static final class DropCounter
	{
		final AtomicLong times = new AtomicLong();
		final AtomicLong amount = new AtomicLong();
	}

	/**
	 * A raid or grand boss kill of the server-wide log.
	 * @param time when it died (ms)
	 * @param duration how long the fight took (ms), 0 if unknown
	 * @param killer the character the kill was for
	 * @param clan that character's clan, or ""
	 * @param partySize how many fought it: the party members near it, or 1
	 */
	public record BossKill(long time, int duration, String killer, String clan, int partySize)
	{
	}

	/**
	 * One of today's bounties of an account.
	 * @param npcId the monster to hunt
	 * @param progress how many were killed since it was given
	 * @param goal how many it asks
	 */
	public record Bounty(int npcId, int progress, int goal)
	{
		public boolean isDone()
		{
			return progress >= goal;
		}
	}

	/**
	 * An account's bounties of the day.
	 * @param bounties the bounties, none when the account has not rolled today's yet
	 * @param resetAt when the next ones come (ms)
	 */
	public record BountyBoard(List<Bounty> bounties, long resetAt)
	{
	}

	/**
	 * The slayer bonus of an account against one race.
	 * @param race the race
	 * @param monsters how many monsters of the race the collection has
	 * @param found how many of them the account found
	 * @param damage how much more damage (percent) the account deals to them
	 * @param defense how much less damage (percent) it takes from them
	 */
	public record Slayer(Race race, int monsters, int found, double damage, double defense)
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
	 * The slayer bonuses of an account, as its entries were at a count of tier changes.
	 */
	private record SlayerCache(int version, Map<Race, Slayer> bonuses)
	{
	}

	/**
	 * An account's bounties of one day: the monsters, and how many of each were killed since.
	 */
	private static final class Bounties
	{
		final int day;
		final int[] npcIds;
		final AtomicIntegerArray progress;

		Bounties(int day, int[] npcIds, int[] progress)
		{
			this.day = day;
			this.npcIds = npcIds;
			this.progress = new AtomicIntegerArray(progress);
		}

		int indexOf(int npcId)
		{
			for (int i = 0; i < npcIds.length; i++)
			{
				if (npcIds[i] == npcId)
				{
					return i;
				}
			}
			return -1;
		}
	}

	/**
	 * What is read from the database for an account.
	 */
	private record Loaded(Map<Integer, Entry> entries, Bounties bounties)
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
		volatile SlayerCache slayer;
		/** Today's bounties, {@code null} until the account rolls them. */
		volatile Bounties bounties;
		volatile boolean bountiesChanged;

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
	/** The races of the collection with enough monsters for a slayer bonus, and their monsters. */
	private volatile Map<Race, int[]> _races;
	/** The monsters bounties can ask for: plain monsters of the collection that respawn. */
	private volatile List<NpcTemplate> _bountyPool;
	/** Observed drops by monster, then by {@link #dropKey}; item 0 counts the rolls. All of the table, read at startup. */
	private final Map<Integer, Map<Integer, DropCounter>> _drops = new ConcurrentHashMap<>();
	/** The counts that changed since the last write, as npc id << 32 | drop key. */
	private final Set<Long> _dropsChanged = ConcurrentHashMap.newKeySet();
	private final Object _dropStoreLock = new Object();
	/** The last kills of each raid and grand boss, read from the log when first asked for. */
	private final Map<Integer, List<BossKill>> _bossKills = new ConcurrentHashMap<>();
	private volatile Supplier<Map<String, ? extends Collection<Integer>>> _regionSource;
	private volatile Regions _regions;
	private volatile ServerStats _serverStats = new ServerStats(Collections.emptyMap(), Collections.emptyList(), 0);

	protected BestiaryManager()
	{
		try (Connection con = DatabaseFactory.getConnection();
			Statement statement = con.createStatement())
		{
			statement.execute(CREATE_TABLE);
			addColumns(statement);
			statement.execute(CREATE_BOUNTY_TABLE);
			statement.execute(CREATE_BOSS_TABLE);
			statement.execute(CREATE_DROP_TABLE);
			if (BestiaryConfig.ENABLED && BestiaryConfig.DROP_STATS_ENABLED)
			{
				loadDropStats(statement);
			}
			migrateCharacterTable(statement);
		}
		catch (Exception e)
		{
			LOGGER.log(Level.WARNING, getClass().getSimpleName() + ": Could not create the bestiary tables.", e);
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
	 * Adds the columns that came after the table: rare forms and records.
	 */
	private void addColumns(Statement statement) throws Exception
	{
		for (String[] column : ADDED_COLUMNS)
		{
			try (ResultSet rs = statement.executeQuery("SHOW COLUMNS FROM account_bestiary LIKE '" + column[0] + "'"))
			{
				if (rs.next())
				{
					continue;
				}
			}
			statement.execute("ALTER TABLE account_bestiary ADD COLUMN `" + column[0] + "` " + column[1]);
			LOGGER.info(getClass().getSimpleName() + ": Added column " + column[0] + " to account_bestiary.");
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
			final Record record = getRecord(player);
			if (record != null)
			{
				ensureBounties(player, record);
			}
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
		final Loaded loaded = existing == null ? load(account) : null;
		final Record record = _records.compute(key, (k, old) ->
		{
			Record result = old;
			if (result == null)
			{
				final Loaded read = loaded != null ? loaded : load(account);
				result = new Record(account);
				result.entries.putAll(read.entries());
				result.bounties = read.bounties();
			}
			result.players.add(objectId);
			return result;
		});
		_offline.remove(key);
		return record;
	}

	/**
	 * @param player a player
	 * @return its account's record if it is in memory, without loading it (for what runs on every hit)
	 */
	private Record peekRecord(Player player)
	{
		final String account = player.getAccountName();
		return account != null ? _records.get(account.toLowerCase(Locale.ROOT)) : null;
	}

	/**
	 * Writes the entries that changed since the last time, for every account, and lets go of the accounts with no character left in the world. Runs every BestiaryStoreInterval seconds and at shutdown.
	 */
	public void storeAll()
	{
		storeDropStats();
		for (Map.Entry<String, Record> entry : _records.entrySet())
		{
			final Record record = entry.getValue();
			// A character that left the world without going through onLeave (should not happen) holds nothing.
			record.players.removeIf(objectId -> World.getInstance().getPlayer(objectId) == null);
			store(record);
			_records.computeIfPresent(entry.getKey(), (key, current) -> (current == record) && current.players.isEmpty() && current.changed.isEmpty() && !current.bountiesChanged ? null : current);
		}
	}

	private void store(Record record)
	{
		synchronized (record.storeLock)
		{
			storeBounties(record);
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
					ps.setInt(7, entry.variants());
					ps.setInt(8, entry.bestTime());
					ps.setInt(9, entry.bestChar());
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

	/** Writes the account's bounties if they changed (progress, or new ones rolled). Called under the record's store lock. */
	private void storeBounties(Record record)
	{
		final Bounties bounties = record.bounties;
		if (!record.bountiesChanged || (bounties == null))
		{
			return;
		}

		// Taken off before the progress is read: a kill that comes in meanwhile marks it again, and the next write takes it.
		record.bountiesChanged = false;
		final StringJoiner ids = new StringJoiner(",");
		final StringJoiner progress = new StringJoiner(",");
		for (int i = 0; i < bounties.npcIds.length; i++)
		{
			ids.add(String.valueOf(bounties.npcIds[i]));
			progress.add(String.valueOf(bounties.progress.get(i)));
		}
		try (Connection con = DatabaseFactory.getConnection();
			PreparedStatement ps = con.prepareStatement(UPSERT_BOUNTY))
		{
			ps.setString(1, record.account);
			ps.setInt(2, bounties.day);
			ps.setString(3, ids.toString());
			ps.setString(4, progress.toString());
			ps.execute();
		}
		catch (Exception e)
		{
			record.bountiesChanged = true;
			LOGGER.log(Level.WARNING, getClass().getSimpleName() + ": Could not store the bounties of account " + record.account + ".", e);
		}
	}

	private Loaded load(String account)
	{
		final Map<Integer, Entry> entries = new HashMap<>();
		Bounties bounties = null;
		try (Connection con = DatabaseFactory.getConnection())
		{
			try (PreparedStatement ps = con.prepareStatement(SELECT_ACCOUNT))
			{
				ps.setString(1, account);
				try (ResultSet rs = ps.executeQuery())
				{
					while (rs.next())
					{
						entries.put(rs.getInt("npc_id"), new Entry(rs.getInt("kills"), rs.getLong("first_kill"), rs.getLong("last_kill"), rs.getInt("first_char"), rs.getInt("variants"), rs.getInt("best_time"), rs.getInt("best_char")));
					}
				}
			}
			bounties = loadBounties(con, account);
		}
		catch (Exception e)
		{
			LOGGER.log(Level.WARNING, getClass().getSimpleName() + ": Could not load the bestiary of account " + account + ".", e);
		}
		return new Loaded(entries, bounties);
	}

	/** @return the account's last bounties, of whatever day they were rolled, or {@code null} */
	private static Bounties loadBounties(Connection con, String account) throws Exception
	{
		try (PreparedStatement ps = con.prepareStatement(SELECT_BOUNTY))
		{
			ps.setString(1, account);
			try (ResultSet rs = ps.executeQuery())
			{
				if (!rs.next())
				{
					return null;
				}

				final int[] npcIds = parseInts(rs.getString("npc_ids"));
				final int[] progress = parseInts(rs.getString("progress"));
				return npcIds.length == progress.length ? new Bounties(rs.getInt("day"), npcIds, progress) : null;
			}
		}
	}

	private static int[] parseInts(String value)
	{
		if ((value == null) || value.isBlank())
		{
			return new int[0];
		}
		final String[] parts = value.split(",");
		final int[] result = new int[parts.length];
		for (int i = 0; i < parts.length; i++)
		{
			try
			{
				result[i] = Integer.parseInt(parts[i].trim());
			}
			catch (NumberFormatException e)
			{
				return new int[0];
			}
		}
		return result;
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
		final long firstHit = monster.getFirstDamageTime();
		final int duration = (firstHit > 0) && (now > firstHit) ? (int) Math.min(Integer.MAX_VALUE, now - firstHit) : 0;
		final int variants = getVariants(monster);
		final Set<Record> credited = new HashSet<>(2);
		int partySize = 1;
		addKill(player, monster.getTemplate(), now, duration, variants, credited);
		if (BestiaryConfig.PARTY_CREDIT && player.isInParty())
		{
			for (Player member : player.getParty().getMembers())
			{
				if ((member != player) && LocationUtil.checkIfInRange(PlayerConfig.ALT_PARTY_RANGE, monster, member, true))
				{
					partySize++;
					addKill(member, monster.getTemplate(), now, duration, variants, credited);
				}
			}
		}

		if (isBoss(monster.getTemplate()) && (BestiaryConfig.BOSS_LOG_SIZE > 0) && !player.isGM())
		{
			logBossKill(monster.getId(), new BossKill(now, duration, player.getName(), player.getClan() != null ? player.getClan().getName() : "", partySize));
		}
	}

	/**
	 * @return the rare forms the monster has, as VARIANT_* bits (only of the systems enabled)
	 */
	private static int getVariants(Attackable monster)
	{
		int variants = 0;
		if (ChampionMonstersConfig.CHAMPION_ENABLE && (monster.getChampionTier() > 0))
		{
			variants |= VARIANT_CHAMPION;
		}
		if (monster instanceof Monster)
		{
			final Monster mob = (Monster) monster;
			if (mob.isMageMonster())
			{
				variants |= VARIANT_MAGE;
			}
			if (mob.isInfused())
			{
				variants |= VARIANT_INFUSED;
			}
			if (mob.isResonant())
			{
				variants |= VARIANT_RESONANT;
			}
		}
		return variants;
	}

	/**
	 * @return the VARIANT_* bits of the rare form systems that are enabled
	 */
	public static int getEnabledVariants()
	{
		return (ChampionMonstersConfig.CHAMPION_ENABLE ? VARIANT_CHAMPION : 0) | (MageMonsterConfig.ENABLED ? VARIANT_MAGE : 0) | (InfusedMonsterConfig.ENABLED ? VARIANT_INFUSED : 0) | (ResonantMonsterConfig.ENABLED ? VARIANT_RESONANT : 0);
	}

	/**
	 * Counts the kill for the player's account, unless another character of the account in the party already counted it. An open bounty on the monster makes it count more, and moves on.
	 * @param duration how long the fight took (ms), 0 if unknown
	 * @param variants the rare forms of the monster, as VARIANT_* bits
	 */
	private void addKill(Player player, NpcTemplate template, long now, int duration, int variants, Set<Record> credited)
	{
		final Record record = getRecord(player);
		if ((record == null) || !credited.add(record))
		{
			return;
		}

		final int npcId = template.getId();
		final int charId = player.getObjectId();

		// A bounty still open counts the kill more than once, and is done at its goal.
		ensureBounties(player, record);
		int amount = 1;
		boolean bountyDone = false;
		final Bounties bounties = record.bounties;
		final int slot = bounties != null ? bounties.indexOf(npcId) : -1;
		if (slot >= 0)
		{
			final int before = bounties.progress.getAndUpdate(slot, progress -> Math.min(BestiaryConfig.BOUNTY_KILLS, progress + 1));
			if (before < BestiaryConfig.BOUNTY_KILLS)
			{
				amount = BestiaryConfig.BOUNTY_KILL_MULTIPLIER;
				bountyDone = (before + 1) >= BestiaryConfig.BOUNTY_KILLS;
				record.bountiesChanged = true;
			}
		}

		final int added = amount;
		final int[] oldVariants = new int[1];
		final Entry entry = record.entries.compute(npcId, (id, old) ->
		{
			if (old == null)
			{
				return new Entry(added, now, now, charId, variants, duration, duration > 0 ? charId : 0);
			}
			oldVariants[0] = old.variants();
			final boolean faster = (duration > 0) && ((old.bestTime() == 0) || (duration < old.bestTime()));
			return new Entry(old.kills() + added, old.firstKill(), now, old.firstChar(), old.variants() | variants, faster ? duration : old.bestTime(), faster ? charId : old.bestChar());
		});
		record.changed.add(npcId);

		final int tier = getTier(template, entry.kills());
		if (tier > getTier(template, entry.kills() - added))
		{
			record.tierChanges.incrementAndGet();
			announce(player, template, tier, entry.kills(), record);
		}

		final int newVariants = variants & ~oldVariants[0];
		if ((newVariants != 0) && BestiaryConfig.VARIANT_MESSAGES)
		{
			announceVariants(player, template, newVariants, entry.variants());
		}
		if (bountyDone)
		{
			payBounty(player, template, record);
		}
	}

	private void announceVariants(Player player, NpcTemplate template, int newVariants, int allVariants)
	{
		final int enabled = getEnabledVariants();
		for (int i = 0; i < VARIANT_NAMES.length; i++)
		{
			if ((newVariants & (1 << i)) != 0)
			{
				player.sendMessage("Bestiary: rare form found, " + VARIANT_NAMES[i] + " " + template.getName() + " (" + Integer.bitCount(allVariants & enabled) + " of " + Integer.bitCount(enabled) + " forms).");
				if (BestiaryConfig.SCREEN_MESSAGES)
				{
					player.sendPacket(new ExShowScreenMessage("Bestiary: " + VARIANT_NAMES[i] + " " + template.getName() + " recorded!", ExShowScreenMessage.TOP_CENTER, SCREEN_MESSAGE_TIME, 0, true, true));
				}
			}
		}
	}

	private void announce(Player player, NpcTemplate template, int tier, int kills, Record record)
	{
		final String name = template.getName();
		if (tier == 1)
		{
			AdventurersGuideManager.getInstance().trigger(player, GuideLesson.BESTIARY, "You have discovered a new monster for your Bestiary: " + name + "!");
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
	// Slayer
	// ---------------------------------------------------------------------------------------------

	/**
	 * The slayer bonus between a player and a monster: more damage dealt to the monsters of a race the account collected, less taken from them. Runs on every hit, so it reads only what is in memory.
	 * @param attacker who deals the damage
	 * @param target who takes it
	 * @return the multiplier of the damage
	 */
	public double getSlayerMultiplier(Creature attacker, Creature target)
	{
		if (!BestiaryConfig.ENABLED || (attacker == null) || (target == null))
		{
			return 1;
		}

		if (target.isMonster() && !target.isFakePlayer())
		{
			final Slayer slayer = getSlayer(attacker.asPlayer(), target.asNpc().getTemplate().getRace());
			return slayer != null ? 1 + (slayer.damage() / 100) : 1;
		}
		if (attacker.isMonster() && !attacker.isFakePlayer())
		{
			final Slayer slayer = getSlayer(target.asPlayer(), attacker.asNpc().getTemplate().getRace());
			return slayer != null ? 1 - (slayer.defense() / 100) : 1;
		}
		return 1;
	}

	private Slayer getSlayer(Player player, Race race)
	{
		final Record record = player != null ? peekRecord(player) : null;
		if (record == null)
		{
			return null;
		}

		SlayerCache cached = record.slayer;
		final int version = record.tierChanges.get();
		if ((cached == null) || (cached.version() != version))
		{
			// Read after the version: a tier reached meanwhile changes the version again, and the next call works it out again.
			cached = new SlayerCache(version, getSlayer(record.entries));
			record.slayer = cached;
		}
		return cached.bonuses().get(race);
	}

	/**
	 * @param entries an account's entries
	 * @return its slayer bonus against each race of the collection that has enough monsters: each monster counts for its tier's bonus divided by the monsters of its race
	 */
	public Map<Race, Slayer> getSlayer(Map<Integer, Entry> entries)
	{
		final Map<Race, Slayer> bonuses = new HashMap<>();
		for (Map.Entry<Race, int[]> race : getRaces().entrySet())
		{
			final int[] npcIds = race.getValue();
			double damage = 0;
			double defense = 0;
			int found = 0;
			for (int npcId : npcIds)
			{
				final Entry entry = entries.get(npcId);
				final NpcTemplate template = entry != null ? NpcData.getInstance().getTemplate(npcId) : null;
				final int tier = template != null ? getTier(template, entry.kills()) : 0;
				if (tier > 0)
				{
					found++;
					damage += BestiaryConfig.SLAYER_DAMAGE_BONUS[tier - 1];
					defense += BestiaryConfig.SLAYER_DEFENSE_BONUS[tier - 1];
				}
			}
			bonuses.put(race.getKey(), new Slayer(race.getKey(), npcIds.length, found, damage / npcIds.length, defense / npcIds.length));
		}
		return bonuses;
	}

	/**
	 * @return the races of the collection with at least BestiarySlayerMinMonsters monsters, and their monsters
	 */
	public Map<Race, int[]> getRaces()
	{
		Map<Race, int[]> races = _races;
		if (races == null)
		{
			final Map<Race, List<Integer>> byRace = new HashMap<>();
			for (Integer npcId : getCollection())
			{
				final NpcTemplate template = NpcData.getInstance().getTemplate(npcId);
				if (template != null)
				{
					byRace.computeIfAbsent(template.getRace(), race -> new ArrayList<>()).add(npcId);
				}
			}

			races = new HashMap<>();
			for (Map.Entry<Race, List<Integer>> race : byRace.entrySet())
			{
				if (race.getValue().size() >= BestiaryConfig.SLAYER_MIN_MONSTERS)
				{
					races.put(race.getKey(), race.getValue().stream().mapToInt(Integer::intValue).toArray());
				}
			}
			races = Collections.unmodifiableMap(races);
			_races = races;
		}
		return races;
	}

	// ---------------------------------------------------------------------------------------------
	// Bounties
	// ---------------------------------------------------------------------------------------------

	/** @return the bounty day it is now, as yyyymmdd: a day starts at BestiaryBountyResetHour */
	private static int today()
	{
		final LocalDate day = LocalDateTime.now().minusHours(BestiaryConfig.BOUNTY_RESET_HOUR).toLocalDate();
		return (day.getYear() * 10000) + (day.getMonthValue() * 100) + day.getDayOfMonth();
	}

	/** @return when the next bounties come (ms) */
	public static long getBountyResetTime()
	{
		final LocalDate day = LocalDateTime.now().minusHours(BestiaryConfig.BOUNTY_RESET_HOUR).toLocalDate();
		return day.plusDays(1).atTime(BestiaryConfig.BOUNTY_RESET_HOUR, 0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
	}

	/**
	 * Gives the account today's bounties if it has none yet, around the level of the player.
	 */
	private void ensureBounties(Player player, Record record)
	{
		if (!BestiaryConfig.BOUNTY_ENABLED)
		{
			return;
		}

		final int day = today();
		final Bounties current = record.bounties;
		if ((current != null) && (current.day == day))
		{
			return;
		}

		synchronized (record)
		{
			if ((record.bounties != null) && (record.bounties.day == day))
			{
				return;
			}

			final int[] npcIds = rollBounties(player.getLevel(), record.entries);
			if (npcIds.length == 0)
			{
				return;
			}
			record.bounties = new Bounties(day, npcIds, new int[npcIds.length]);
			record.bountiesChanged = true;
		}

		final StringJoiner names = new StringJoiner(", ");
		for (int npcId : record.bounties.npcIds)
		{
			final NpcTemplate template = NpcData.getInstance().getTemplate(npcId);
			names.add(template != null ? template.getName() + " (Lv. " + template.getLevel() + ")" : String.valueOf(npcId));
		}
		player.sendMessage("Bestiary: today's bounties, " + FormatUtil.formatAdena(BestiaryConfig.BOUNTY_KILLS) + " of each: " + names + ". Type .bestiary bounties to see them.");
	}

	/**
	 * Picks the day's bounties: monsters near the level that respawn, the ones the account has the least of first. Legendary entries only when nothing else is near.
	 * @param level the level of the character rolling them
	 * @param entries the account's entries
	 * @return the monsters, at most BestiaryBountyCount
	 */
	private int[] rollBounties(int level, Map<Integer, Entry> entries)
	{
		final List<NpcTemplate> pool = getBountyPool();
		for (int range = BestiaryConfig.BOUNTY_LEVEL_RANGE;; range *= 2)
		{
			final List<NpcTemplate> candidates = new ArrayList<>();
			final List<Integer> weights = new ArrayList<>();
			for (NpcTemplate template : pool)
			{
				if (Math.abs(template.getLevel() - level) > range)
				{
					continue;
				}

				final Entry entry = entries.get(template.getId());
				final int tier = entry != null ? getTier(template, entry.kills()) : 0;
				candidates.add(template);
				weights.add(tier >= BestiaryConfig.TIER_NAMES.length ? 0 : (BestiaryConfig.TIER_NAMES.length + 1) - tier); // 5 for a monster not found yet, 2 for a Mastered one
			}

			// Too few near: look further, until the range holds every level.
			final int usable = (int) weights.stream().filter(weight -> weight > 0).count();
			if ((usable < BestiaryConfig.BOUNTY_COUNT) && (range < 100) && (candidates.size() < pool.size()))
			{
				continue;
			}

			// Legendary entries only fill what the others can't.
			for (int i = 0; i < weights.size(); i++)
			{
				if ((weights.get(i) == 0) && (usable < BestiaryConfig.BOUNTY_COUNT))
				{
					weights.set(i, 1);
				}
			}

			final List<Integer> picked = new ArrayList<>();
			while ((picked.size() < BestiaryConfig.BOUNTY_COUNT) && !candidates.isEmpty())
			{
				final int total = weights.stream().mapToInt(Integer::intValue).sum();
				if (total <= 0)
				{
					break;
				}
				int roll = Rnd.get(total);
				for (int i = 0; i < candidates.size(); i++)
				{
					roll -= weights.get(i);
					if (roll < 0)
					{
						picked.add(candidates.remove(i).getId());
						weights.remove(i);
						break;
					}
				}
			}
			return picked.stream().mapToInt(Integer::intValue).toArray();
		}
	}

	/**
	 * @return the monsters bounties can ask for: the plain monsters of the collection with a spawn that respawns
	 */
	private List<NpcTemplate> getBountyPool()
	{
		List<NpcTemplate> pool = _bountyPool;
		if (pool == null)
		{
			pool = new ArrayList<>();
			for (Integer npcId : getCollection())
			{
				final NpcTemplate template = NpcData.getInstance().getTemplate(npcId);
				if ((template == null) || !template.isType("Monster"))
				{
					continue;
				}
				for (Spawn spawn : SpawnTable.getInstance().getSpawns(npcId))
				{
					if (spawn.getRespawnMinDelay() > 0)
					{
						pool.add(template);
						break;
					}
				}
			}
			pool = Collections.unmodifiableList(pool);
			_bountyPool = pool;
		}
		return pool;
	}

	/**
	 * Pays a finished bounty to the player whose kill finished it: adena by the monster's level, and BestiaryBountyRewardItems.
	 */
	private void payBounty(Player player, NpcTemplate template, Record record)
	{
		final long adena = (long) BestiaryConfig.BOUNTY_ADENA_PER_LEVEL * template.getLevel();
		final StringJoiner rewards = new StringJoiner(", ");
		if (adena > 0)
		{
			player.addItem(ItemProcessType.REWARD, Inventory.ADENA_ID, adena, null, true);
			rewards.add(FormatUtil.formatAdena(adena) + " adena");
		}
		for (int[] item : BestiaryConfig.BOUNTY_REWARD_ITEMS)
		{
			player.addItem(ItemProcessType.REWARD, item[0], item[1], null, true);
			final ItemTemplate reward = ItemData.getInstance().getTemplate(item[0]);
			rewards.add(FormatUtil.formatAdena(item[1]) + " " + (reward != null ? reward.getName() : "item " + item[0]));
		}

		final Bounties bounties = record.bounties;
		int done = 0;
		for (int i = 0; (bounties != null) && (i < bounties.npcIds.length); i++)
		{
			done += bounties.progress.get(i) >= BestiaryConfig.BOUNTY_KILLS ? 1 : 0;
		}
		final int total = bounties != null ? bounties.npcIds.length : 0;
		player.sendMessage("Bestiary: bounty done, " + template.getName() + " (" + done + " of " + total + " today)." + (rewards.length() > 0 ? " Reward: " + rewards + "." : ""));
		if (BestiaryConfig.SCREEN_MESSAGES)
		{
			player.sendPacket(new ExShowScreenMessage("Bestiary: bounty on " + template.getName() + " done!", ExShowScreenMessage.TOP_CENTER, SCREEN_MESSAGE_TIME, 0, true, true));
		}
	}

	/**
	 * @param charId a character, online or not
	 * @return its account's bounties of today (none if it has not rolled them yet), with when the next ones come
	 */
	public BountyBoard getBountyBoard(int charId)
	{
		final long resetAt = getBountyResetTime();
		if (!BestiaryConfig.BOUNTY_ENABLED)
		{
			return new BountyBoard(Collections.emptyList(), resetAt);
		}

		final String account = getAccountName(charId);
		if (account == null)
		{
			return new BountyBoard(Collections.emptyList(), resetAt);
		}

		final Record record = _records.get(account.toLowerCase(Locale.ROOT));
		Bounties bounties = record != null ? record.bounties : null;
		if (record == null)
		{
			try (Connection con = DatabaseFactory.getConnection())
			{
				bounties = loadBounties(con, account);
			}
			catch (Exception e)
			{
				LOGGER.log(Level.WARNING, getClass().getSimpleName() + ": Could not load the bounties of account " + account + ".", e);
			}
		}
		if ((bounties == null) || (bounties.day != today()))
		{
			return new BountyBoard(Collections.emptyList(), resetAt);
		}

		final List<Bounty> list = new ArrayList<>(bounties.npcIds.length);
		for (int i = 0; i < bounties.npcIds.length; i++)
		{
			list.add(new Bounty(bounties.npcIds[i], Math.min(BestiaryConfig.BOUNTY_KILLS, bounties.progress.get(i)), BestiaryConfig.BOUNTY_KILLS));
		}
		return new BountyBoard(Collections.unmodifiableList(list), resetAt);
	}

	// ---------------------------------------------------------------------------------------------
	// Observed drops
	// ---------------------------------------------------------------------------------------------

	/** @return the key of an item of a monster's drops or spoil (item 0: the rolls) */
	private static int dropKey(boolean spoil, int itemId)
	{
		return (itemId << 1) | (spoil ? 1 : 0);
	}

	/**
	 * Counts what came out of one roll of a monster's drops or spoil: the roll, and each item once, with its amount.
	 * @param npcId the monster
	 * @param spoil {@code true} for its spoil, {@code false} for its drops
	 * @param items what came out, {@code null} or empty for nothing
	 */
	public void onDropsRolled(int npcId, boolean spoil, Collection<ItemHolder> items)
	{
		if (!BestiaryConfig.ENABLED || !BestiaryConfig.DROP_STATS_ENABLED)
		{
			return;
		}

		final Map<Integer, DropCounter> counters = _drops.computeIfAbsent(npcId, id -> new ConcurrentHashMap<>());
		count(npcId, counters, dropKey(spoil, 0), 0);
		if ((items == null) || items.isEmpty())
		{
			return;
		}

		// The same item twice in one roll (two groups) counts once, with both amounts.
		final Map<Integer, Long> amounts = new HashMap<>();
		for (ItemHolder item : items)
		{
			amounts.merge(item.getId(), item.getCount(), Long::sum);
		}
		for (Map.Entry<Integer, Long> item : amounts.entrySet())
		{
			count(npcId, counters, dropKey(spoil, item.getKey()), item.getValue());
		}
	}

	private void count(int npcId, Map<Integer, DropCounter> counters, int key, long amount)
	{
		final DropCounter counter = counters.computeIfAbsent(key, k -> new DropCounter());
		counter.times.incrementAndGet();
		counter.amount.addAndGet(amount);
		_dropsChanged.add(((long) npcId << 32) | (key & 0xFFFFFFFFL));
	}

	/**
	 * @param npcId a monster
	 * @return what it really dropped across the server, the items most often first
	 */
	public ObservedDrops getObservedDrops(int npcId)
	{
		final Map<Integer, DropCounter> counters = _drops.get(npcId);
		if (counters == null)
		{
			return new ObservedDrops(0, Collections.emptyList(), 0, Collections.emptyList());
		}

		long dropRolls = 0;
		long spoilRolls = 0;
		final List<ObservedItem> drops = new ArrayList<>();
		final List<ObservedItem> spoils = new ArrayList<>();
		for (Map.Entry<Integer, DropCounter> entry : counters.entrySet())
		{
			final boolean spoil = (entry.getKey() & 1) != 0;
			final int itemId = entry.getKey() >>> 1;
			final long times = entry.getValue().times.get();
			if (itemId == 0)
			{
				if (spoil)
				{
					spoilRolls = times;
				}
				else
				{
					dropRolls = times;
				}
				continue;
			}
			(spoil ? spoils : drops).add(new ObservedItem(itemId, times, entry.getValue().amount.get()));
		}
		drops.sort((a, b) -> Long.compare(b.times(), a.times()));
		spoils.sort((a, b) -> Long.compare(b.times(), a.times()));
		return new ObservedDrops(dropRolls, drops, spoilRolls, spoils);
	}

	private void loadDropStats(Statement statement) throws Exception
	{
		int rows = 0;
		try (ResultSet rs = statement.executeQuery(SELECT_DROP_STATS))
		{
			while (rs.next())
			{
				final DropCounter counter = new DropCounter();
				counter.times.set(rs.getLong("times"));
				counter.amount.set(rs.getLong("amount"));
				_drops.computeIfAbsent(rs.getInt("npc_id"), id -> new ConcurrentHashMap<>()).put(dropKey(rs.getInt("spoil") != 0, rs.getInt("item_id")), counter);
				rows++;
			}
		}
		LOGGER.info(getClass().getSimpleName() + ": " + rows + " observed drop counts of " + _drops.size() + " monsters.");
	}

	/**
	 * Writes the observed drop counts that changed. They are kept whole in memory, so each write sets the totals.
	 */
	private void storeDropStats()
	{
		synchronized (_dropStoreLock)
		{
			if (_dropsChanged.isEmpty())
			{
				return;
			}

			final List<Long> keys = new ArrayList<>(_dropsChanged);
			try (Connection con = DatabaseFactory.getConnection();
				PreparedStatement ps = con.prepareStatement(UPSERT_DROP_STATS))
			{
				for (long key : keys)
				{
					// Taken off before the count is read: a drop that comes in meanwhile marks it again, and the next write takes it.
					_dropsChanged.remove(key);
					final int npcId = (int) (key >>> 32);
					final int dropKey = (int) key;
					final Map<Integer, DropCounter> counters = _drops.get(npcId);
					final DropCounter counter = counters != null ? counters.get(dropKey) : null;
					if (counter == null)
					{
						continue;
					}

					ps.setInt(1, npcId);
					ps.setInt(2, dropKey & 1);
					ps.setInt(3, dropKey >>> 1);
					ps.setLong(4, counter.times.get());
					ps.setLong(5, counter.amount.get());
					ps.addBatch();
				}
				ps.executeBatch();
			}
			catch (Exception e)
			{
				// Kept as changed, so the next write tries again.
				_dropsChanged.addAll(keys);
				LOGGER.log(Level.WARNING, getClass().getSimpleName() + ": Could not store the observed drops.", e);
			}
		}
	}

	// ---------------------------------------------------------------------------------------------
	// Boss kills
	// ---------------------------------------------------------------------------------------------

	private void logBossKill(int npcId, BossKill kill)
	{
		ThreadPool.execute(() ->
		{
			try (Connection con = DatabaseFactory.getConnection();
				PreparedStatement ps = con.prepareStatement(INSERT_BOSS_KILL))
			{
				ps.setInt(1, npcId);
				ps.setLong(2, kill.time());
				ps.setInt(3, kill.duration());
				ps.setString(4, kill.killer());
				ps.setString(5, kill.clan());
				ps.setInt(6, Math.min(255, kill.partySize()));
				ps.execute();
			}
			catch (Exception e)
			{
				LOGGER.log(Level.WARNING, getClass().getSimpleName() + ": Could not log the kill of boss " + npcId + ".", e);
			}
			// Read again the next time, with this kill.
			_bossKills.remove(npcId);
		});
	}

	/**
	 * @param npcId a raid or grand boss
	 * @return its last kills, newest first (at most BestiaryBossLogSize)
	 */
	public List<BossKill> getBossKills(int npcId)
	{
		if (BestiaryConfig.BOSS_LOG_SIZE <= 0)
		{
			return Collections.emptyList();
		}

		final List<BossKill> cached = _bossKills.get(npcId);
		if (cached != null)
		{
			return cached;
		}

		final List<BossKill> kills = new ArrayList<>();
		try (Connection con = DatabaseFactory.getConnection();
			PreparedStatement ps = con.prepareStatement(SELECT_BOSS_KILLS))
		{
			ps.setInt(1, npcId);
			ps.setInt(2, BestiaryConfig.BOSS_LOG_SIZE);
			try (ResultSet rs = ps.executeQuery())
			{
				while (rs.next())
				{
					kills.add(new BossKill(rs.getLong("kill_time"), rs.getInt("duration"), rs.getString("killer"), rs.getString("clan"), rs.getInt("party_size")));
				}
			}
		}
		catch (Exception e)
		{
			LOGGER.log(Level.WARNING, getClass().getSimpleName() + ": Could not read the kills of boss " + npcId + ".", e);
			return Collections.emptyList();
		}

		final List<BossKill> result = Collections.unmodifiableList(kills);
		if (_bossKills.size() > 500)
		{
			_bossKills.clear();
		}
		_bossKills.put(npcId, result);
		return result;
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

		final Map<Integer, Entry> entries = Collections.unmodifiableMap(load(account).entries());
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
							rs.getInt("hunters"),
							rs.getLong("best_time")
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
					monsters.put(npcId, new MonsterStats(entry.getValue()[0], (int) entry.getValue()[1], firstNames.get(npcId), firstTimes.getOrDefault(npcId, 0L), (int) entry.getValue()[2]));
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
