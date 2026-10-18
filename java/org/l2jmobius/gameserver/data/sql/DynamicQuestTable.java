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
package org.l2jmobius.gameserver.data.sql;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.logging.Logger;

import org.l2jmobius.commons.database.DatabaseFactory;

/**
 * The tables of the Dynamic Quests: the quests running for each character, what each account completed today (and the cooldowns), and the board offers each account took today.
 * The tables are created at startup, so a database set up before the feature gets them without running {@code dynamic_quests.sql}.
 * @author Mobius
 */
public class DynamicQuestTable
{
	private static final Logger LOGGER = Logger.getLogger(DynamicQuestTable.class.getName());

	private static final String CREATE_QUESTS = "CREATE TABLE IF NOT EXISTS `character_dynamic_quests` (`instance_id` BIGINT NOT NULL, `char_id` INT UNSIGNED NOT NULL, `template_id` INT UNSIGNED NOT NULL, `seed` BIGINT NOT NULL, `tier` VARCHAR(8) NOT NULL, `quest_level` TINYINT UNSIGNED NOT NULL, `party_size` TINYINT UNSIGNED NOT NULL DEFAULT 1, `stage` TINYINT UNSIGNED NOT NULL DEFAULT 0, `escalations` TINYINT UNSIGNED NOT NULL DEFAULT 0, `chain` TINYINT UNSIGNED NOT NULL DEFAULT 0, `optional_done` TINYINT UNSIGNED NOT NULL DEFAULT 0, `expire_at` BIGINT NOT NULL DEFAULT 0, `context` TEXT NOT NULL, `objective_state` TEXT NOT NULL, `participants` VARCHAR(255) NOT NULL DEFAULT '', `offer_key` VARCHAR(64) NOT NULL DEFAULT '', `created_at` BIGINT NOT NULL DEFAULT 0, PRIMARY KEY (`instance_id`), KEY `char_id` (`char_id`)) ENGINE=InnoDB DEFAULT CHARSET=utf8 COLLATE=utf8_unicode_ci";
	private static final String CREATE_USAGE = "CREATE TABLE IF NOT EXISTS `dynamic_quest_usage` (`account_name` VARCHAR(45) NOT NULL, `template_id` INT UNSIGNED NOT NULL, `day` INT UNSIGNED NOT NULL DEFAULT 0, `daily_count` SMALLINT UNSIGNED NOT NULL DEFAULT 0, `cooldown_until` BIGINT NOT NULL DEFAULT 0, PRIMARY KEY (`account_name`, `template_id`)) ENGINE=InnoDB DEFAULT CHARSET=utf8 COLLATE=utf8_unicode_ci";
	private static final String CREATE_TAKEN = "CREATE TABLE IF NOT EXISTS `dynamic_quest_taken` (`account_name` VARCHAR(45) NOT NULL, `offer_key` VARCHAR(64) NOT NULL, `day` INT UNSIGNED NOT NULL DEFAULT 0, PRIMARY KEY (`account_name`, `offer_key`), KEY `day` (`day`)) ENGINE=InnoDB DEFAULT CHARSET=utf8 COLLATE=utf8_unicode_ci";

	private static final String REPLACE_QUEST = "REPLACE INTO character_dynamic_quests (instance_id, char_id, template_id, seed, tier, quest_level, party_size, stage, escalations, chain, optional_done, expire_at, context, objective_state, participants, offer_key, created_at) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)";
	private static final String DELETE_QUEST = "DELETE FROM character_dynamic_quests WHERE instance_id=?";
	private static final String SELECT_QUESTS = "SELECT * FROM character_dynamic_quests WHERE char_id=?";
	private static final String SELECT_MAX_ID = "SELECT MAX(instance_id) FROM character_dynamic_quests";
	private static final String SELECT_USAGE = "SELECT template_id, day, daily_count, cooldown_until FROM dynamic_quest_usage WHERE account_name=?";
	private static final String REPLACE_USAGE = "REPLACE INTO dynamic_quest_usage (account_name, template_id, day, daily_count, cooldown_until) VALUES (?,?,?,?,?)";
	private static final String DELETE_USAGE = "DELETE FROM dynamic_quest_usage WHERE account_name=?";
	private static final String SELECT_TAKEN = "SELECT offer_key FROM dynamic_quest_taken WHERE account_name=? AND day=?";
	private static final String INSERT_TAKEN = "INSERT IGNORE INTO dynamic_quest_taken (account_name, offer_key, day) VALUES (?,?,?)";
	private static final String DELETE_TAKEN = "DELETE FROM dynamic_quest_taken WHERE account_name=?";
	private static final String DELETE_OLD_TAKEN = "DELETE FROM dynamic_quest_taken WHERE day<?";

	/** A saved quest. */
	public static class QuestRow
	{
		public long _instanceId;
		public int _charId;
		public int _templateId;
		public long _seed;
		public String _tier;
		public int _questLevel;
		public int _partySize;
		public int _stage;
		public int _escalations;
		public int _chain;
		public int _optionalDone;
		public long _expireAt;
		public String _context;
		public String _objectiveState;
		public String _participants;
		public String _offerKey;
		public long _createdAt;
	}

	/** What an account did with one template. */
	public static class UsageRow
	{
		public int _day;
		public int _dailyCount;
		public long _cooldownUntil;
	}

	private DynamicQuestTable()
	{
	}

	public static void createTables()
	{
		try (Connection con = DatabaseFactory.getConnection();
			Statement st = con.createStatement())
		{
			st.execute(CREATE_QUESTS);
			st.execute(CREATE_USAGE);
			st.execute(CREATE_TAKEN);
		}
		catch (SQLException e)
		{
			LOGGER.warning(DynamicQuestTable.class.getSimpleName() + ": Could not create the dynamic quest tables - " + e.getMessage());
		}
	}

	/**
	 * @return the highest quest id saved, 0 if none
	 */
	public static long getMaxInstanceId()
	{
		try (Connection con = DatabaseFactory.getConnection();
			Statement st = con.createStatement();
			ResultSet rs = st.executeQuery(SELECT_MAX_ID))
		{
			return rs.next() ? rs.getLong(1) : 0;
		}
		catch (SQLException e)
		{
			LOGGER.warning(DynamicQuestTable.class.getSimpleName() + ": Could not read the last quest id - " + e.getMessage());
			return 0;
		}
	}

	public static void saveQuest(QuestRow row)
	{
		try (Connection con = DatabaseFactory.getConnection();
			PreparedStatement ps = con.prepareStatement(REPLACE_QUEST))
		{
			ps.setLong(1, row._instanceId);
			ps.setInt(2, row._charId);
			ps.setInt(3, row._templateId);
			ps.setLong(4, row._seed);
			ps.setString(5, row._tier);
			ps.setInt(6, row._questLevel);
			ps.setInt(7, row._partySize);
			ps.setInt(8, row._stage);
			ps.setInt(9, row._escalations);
			ps.setInt(10, row._chain);
			ps.setInt(11, row._optionalDone);
			ps.setLong(12, row._expireAt);
			ps.setString(13, row._context);
			ps.setString(14, row._objectiveState);
			ps.setString(15, row._participants.length() > 255 ? row._participants.substring(0, 255) : row._participants);
			ps.setString(16, row._offerKey);
			ps.setLong(17, row._createdAt);
			ps.execute();
		}
		catch (SQLException e)
		{
			LOGGER.warning(DynamicQuestTable.class.getSimpleName() + ": Could not save dynamic quest " + row._instanceId + " - " + e.getMessage());
		}
	}

	public static void deleteQuest(long instanceId)
	{
		try (Connection con = DatabaseFactory.getConnection();
			PreparedStatement ps = con.prepareStatement(DELETE_QUEST))
		{
			ps.setLong(1, instanceId);
			ps.execute();
		}
		catch (SQLException e)
		{
			LOGGER.warning(DynamicQuestTable.class.getSimpleName() + ": Could not delete dynamic quest " + instanceId + " - " + e.getMessage());
		}
	}

	/**
	 * @param charId a character
	 * @return its saved quests
	 */
	public static List<QuestRow> loadQuests(int charId)
	{
		final List<QuestRow> rows = new ArrayList<>();
		try (Connection con = DatabaseFactory.getConnection();
			PreparedStatement ps = con.prepareStatement(SELECT_QUESTS))
		{
			ps.setInt(1, charId);
			try (ResultSet rs = ps.executeQuery())
			{
				while (rs.next())
				{
					final QuestRow row = new QuestRow();
					row._instanceId = rs.getLong("instance_id");
					row._charId = rs.getInt("char_id");
					row._templateId = rs.getInt("template_id");
					row._seed = rs.getLong("seed");
					row._tier = rs.getString("tier");
					row._questLevel = rs.getInt("quest_level");
					row._partySize = rs.getInt("party_size");
					row._stage = rs.getInt("stage");
					row._escalations = rs.getInt("escalations");
					row._chain = rs.getInt("chain");
					row._optionalDone = rs.getInt("optional_done");
					row._expireAt = rs.getLong("expire_at");
					row._context = rs.getString("context");
					row._objectiveState = rs.getString("objective_state");
					row._participants = rs.getString("participants");
					row._offerKey = rs.getString("offer_key");
					row._createdAt = rs.getLong("created_at");
					rows.add(row);
				}
			}
		}
		catch (SQLException e)
		{
			LOGGER.warning(DynamicQuestTable.class.getSimpleName() + ": Could not load the dynamic quests of " + charId + " - " + e.getMessage());
		}
		return rows;
	}

	/**
	 * @param account an account
	 * @return what it did with each template
	 */
	public static Map<Integer, UsageRow> loadUsage(String account)
	{
		final Map<Integer, UsageRow> usage = new HashMap<>();
		try (Connection con = DatabaseFactory.getConnection();
			PreparedStatement ps = con.prepareStatement(SELECT_USAGE))
		{
			ps.setString(1, account);
			try (ResultSet rs = ps.executeQuery())
			{
				while (rs.next())
				{
					final UsageRow row = new UsageRow();
					row._day = rs.getInt("day");
					row._dailyCount = rs.getInt("daily_count");
					row._cooldownUntil = rs.getLong("cooldown_until");
					usage.put(rs.getInt("template_id"), row);
				}
			}
		}
		catch (SQLException e)
		{
			LOGGER.warning(DynamicQuestTable.class.getSimpleName() + ": Could not load the dynamic quest usage of " + account + " - " + e.getMessage());
		}
		return usage;
	}

	public static void saveUsage(String account, int templateId, UsageRow row)
	{
		try (Connection con = DatabaseFactory.getConnection();
			PreparedStatement ps = con.prepareStatement(REPLACE_USAGE))
		{
			ps.setString(1, account);
			ps.setInt(2, templateId);
			ps.setInt(3, row._day);
			ps.setInt(4, row._dailyCount);
			ps.setLong(5, row._cooldownUntil);
			ps.execute();
		}
		catch (SQLException e)
		{
			LOGGER.warning(DynamicQuestTable.class.getSimpleName() + ": Could not save the dynamic quest usage of " + account + " - " + e.getMessage());
		}
	}

	/**
	 * @param account an account
	 * @param day a day
	 * @return the board offers it took that day
	 */
	public static Set<String> loadTaken(String account, int day)
	{
		final Set<String> taken = new HashSet<>();
		try (Connection con = DatabaseFactory.getConnection();
			PreparedStatement ps = con.prepareStatement(SELECT_TAKEN))
		{
			ps.setString(1, account);
			ps.setInt(2, day);
			try (ResultSet rs = ps.executeQuery())
			{
				while (rs.next())
				{
					taken.add(rs.getString(1));
				}
			}
		}
		catch (SQLException e)
		{
			LOGGER.warning(DynamicQuestTable.class.getSimpleName() + ": Could not load the offers taken by " + account + " - " + e.getMessage());
		}
		return taken;
	}

	public static void saveTaken(String account, String offerKey, int day)
	{
		try (Connection con = DatabaseFactory.getConnection();
			PreparedStatement ps = con.prepareStatement(INSERT_TAKEN))
		{
			ps.setString(1, account);
			ps.setString(2, offerKey);
			ps.setInt(3, day);
			ps.execute();
		}
		catch (SQLException e)
		{
			LOGGER.warning(DynamicQuestTable.class.getSimpleName() + ": Could not save the offer taken by " + account + " - " + e.getMessage());
		}
	}

	/**
	 * Forgets what an account did (GM reset).
	 * @param account the account
	 */
	public static void resetAccount(String account)
	{
		try (Connection con = DatabaseFactory.getConnection();
			PreparedStatement usage = con.prepareStatement(DELETE_USAGE);
			PreparedStatement taken = con.prepareStatement(DELETE_TAKEN))
		{
			usage.setString(1, account);
			usage.execute();
			taken.setString(1, account);
			taken.execute();
		}
		catch (SQLException e)
		{
			LOGGER.warning(DynamicQuestTable.class.getSimpleName() + ": Could not reset the dynamic quests of " + account + " - " + e.getMessage());
		}
	}

	/**
	 * @param day the current day; offers taken before it are forgotten
	 */
	public static void purgeTaken(int day)
	{
		try (Connection con = DatabaseFactory.getConnection();
			PreparedStatement ps = con.prepareStatement(DELETE_OLD_TAKEN))
		{
			ps.setInt(1, day);
			ps.execute();
		}
		catch (SQLException e)
		{
			LOGGER.warning(DynamicQuestTable.class.getSimpleName() + ": Could not purge old taken offers - " + e.getMessage());
		}
	}
}
