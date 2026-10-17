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
package org.l2jmobius.gameserver.config.custom;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.l2jmobius.commons.util.ConfigReader;

/**
 * The bestiary: every monster an account's characters have killed, collected on a web page, and the drop and EXP bonuses it gives (see {@link org.l2jmobius.gameserver.managers.BestiaryManager}).
 */
public class BestiaryConfig
{
	// File
	private static final String BESTIARY_CONFIG_FILE = "./config/Custom/Bestiary.ini";

	/** The tiers a monster's entry goes through. The first one is the discovery. */
	public static final String[] TIER_NAMES =
	{
		"Discovered",
		"Studied",
		"Mastered",
		"Legendary"
	};

	// Constants
	public static boolean ENABLED;
	public static int[] TIERS;
	public static int[] BOSS_TIERS;
	public static boolean SHOW_UNDISCOVERED_NAMES;
	public static boolean SHOW_UNDISCOVERED_AREAS;
	public static boolean PARTY_CREDIT;
	public static boolean DISCOVERY_MESSAGES;
	public static boolean TIER_MESSAGES;
	public static boolean SCREEN_MESSAGES;
	public static boolean FIREWORKS;
	public static Set<String> TYPES;
	public static Set<Integer> EXCLUDED_NPC_IDS;
	public static Set<Integer> EXTRA_NPC_IDS;
	public static int STORE_INTERVAL;
	public static int SERVER_STATS_INTERVAL;
	public static int RANKING_SIZE;
	public static int[] DROP_BONUS;
	public static int[] BOSS_DROP_BONUS;
	public static int[] REGION_EXP_BONUS;
	public static int[] SLAYER_DAMAGE_BONUS;
	public static int[] SLAYER_DEFENSE_BONUS;
	public static int SLAYER_MIN_MONSTERS;
	public static boolean BOUNTY_ENABLED;
	public static int BOUNTY_COUNT;
	public static int BOUNTY_KILLS;
	public static int BOUNTY_LEVEL_RANGE;
	public static int BOUNTY_KILL_MULTIPLIER;
	public static int BOUNTY_RESET_HOUR;
	public static int BOUNTY_ADENA_PER_LEVEL;
	public static int[][] BOUNTY_REWARD_ITEMS;
	public static boolean VARIANT_MESSAGES;
	public static int BOSS_LOG_SIZE;

	public static void load()
	{
		final ConfigReader config = new ConfigReader(BESTIARY_CONFIG_FILE);
		ENABLED = config.getBoolean("BestiaryEnabled", true);
		TIERS = readTiers(config.getString("BestiaryTiers", "1,50,200,1000"), new int[]
		{
			1,
			50,
			200,
			1000
		});
		BOSS_TIERS = readTiers(config.getString("BestiaryBossTiers", "1,3,5,10"), new int[]
		{
			1,
			3,
			5,
			10
		});
		SHOW_UNDISCOVERED_NAMES = config.getBoolean("BestiaryShowUndiscoveredNames", false);
		SHOW_UNDISCOVERED_AREAS = config.getBoolean("BestiaryShowUndiscoveredAreas", true);
		PARTY_CREDIT = config.getBoolean("BestiaryPartyCredit", true);
		DISCOVERY_MESSAGES = config.getBoolean("BestiaryDiscoveryMessages", true);
		TIER_MESSAGES = config.getBoolean("BestiaryTierMessages", true);
		SCREEN_MESSAGES = config.getBoolean("BestiaryScreenMessages", true);
		FIREWORKS = config.getBoolean("BestiaryFireworks", true);
		TYPES = readStrings(config.getString("BestiaryTypes", "Monster,RaidBoss,GrandBoss,FeedableBeast,Chest,RiftInvader"));
		EXCLUDED_NPC_IDS = readIds(config.getString("BestiaryExcludedNpcIds", ""));
		EXTRA_NPC_IDS = readIds(config.getString("BestiaryExtraNpcIds", ""));
		STORE_INTERVAL = Math.max(10, config.getInt("BestiaryStoreInterval", 60));
		SERVER_STATS_INTERVAL = Math.max(1, config.getInt("BestiaryServerStatsInterval", 10));
		RANKING_SIZE = Math.max(5, Math.min(100, config.getInt("BestiaryRankingSize", 20)));
		DROP_BONUS = readBonuses(config.getString("BestiaryDropBonus", "0,10,25,50"), new int[]
		{
			0,
			10,
			25,
			50
		});
		BOSS_DROP_BONUS = readBonuses(config.getString("BestiaryBossDropBonus", "0,5,10,20"), new int[]
		{
			0,
			5,
			10,
			20
		});
		REGION_EXP_BONUS = readBonuses(config.getString("BestiaryRegionExpBonus", "100,167,233,300"), new int[]
		{
			100,
			167,
			233,
			300
		});
		SLAYER_DAMAGE_BONUS = readBonuses(config.getString("BestiarySlayerDamageBonus", "2,4,7,10"), new int[]
		{
			2,
			4,
			7,
			10
		});
		SLAYER_DEFENSE_BONUS = readBonuses(config.getString("BestiarySlayerDefenseBonus", "1,2,3,5"), new int[]
		{
			1,
			2,
			3,
			5
		});
		for (int i = 0; i < SLAYER_DEFENSE_BONUS.length; i++)
		{
			SLAYER_DEFENSE_BONUS[i] = Math.min(50, SLAYER_DEFENSE_BONUS[i]);
		}
		SLAYER_MIN_MONSTERS = Math.max(1, config.getInt("BestiarySlayerMinMonsters", 10));
		BOUNTY_ENABLED = config.getBoolean("BestiaryBountyEnabled", true);
		BOUNTY_COUNT = Math.max(1, Math.min(6, config.getInt("BestiaryBountyCount", 3)));
		BOUNTY_KILLS = Math.max(1, config.getInt("BestiaryBountyKills", 30));
		BOUNTY_LEVEL_RANGE = Math.max(1, config.getInt("BestiaryBountyLevelRange", 5));
		BOUNTY_KILL_MULTIPLIER = Math.max(1, Math.min(10, config.getInt("BestiaryBountyKillMultiplier", 2)));
		BOUNTY_RESET_HOUR = Math.max(0, Math.min(23, config.getInt("BestiaryBountyResetHour", 6)));
		BOUNTY_ADENA_PER_LEVEL = Math.max(0, config.getInt("BestiaryBountyAdenaPerLevel", 2000));
		BOUNTY_REWARD_ITEMS = readItems(config.getString("BestiaryBountyRewardItems", ""));
		VARIANT_MESSAGES = config.getBoolean("BestiaryVariantMessages", true);
		BOSS_LOG_SIZE = Math.max(0, Math.min(50, config.getInt("BestiaryBossLogSize", 10)));
	}
	
	/**
	 * @param value items as "id,count;id,count"
	 * @return {id, count} of each item that could be read
	 */
	private static int[][] readItems(String value)
	{
		final List<int[]> items = new ArrayList<>();
		for (String part : value.split(";"))
		{
			final String[] pair = part.split(",");
			if (pair.length != 2)
			{
				continue;
			}
			try
			{
				final int id = Integer.parseInt(pair[0].trim());
				final int count = Integer.parseInt(pair[1].trim());
				if ((id > 0) && (count > 0))
				{
					items.add(new int[]
					{
						id,
						count
					});
				}
			}
			catch (NumberFormatException e)
			{
				// not an item: skip it
			}
		}
		return items.toArray(new int[0][]);
	}

	/**
	 * @param value the kills each tier needs, comma separated, lowest first
	 * @param defaults the tiers to use when the value can't be read
	 * @return one threshold per tier, each at least one more than the one before
	 */
	private static int[] readTiers(String value, int[] defaults)
	{
		final String[] parts = value.split(",");
		if (parts.length != TIER_NAMES.length)
		{
			return defaults;
		}

		final int[] tiers = new int[TIER_NAMES.length];
		try
		{
			for (int i = 0; i < tiers.length; i++)
			{
				tiers[i] = Math.max(i == 0 ? 1 : tiers[i - 1] + 1, Integer.parseInt(parts[i].trim()));
			}
		}
		catch (NumberFormatException e)
		{
			return defaults;
		}
		return tiers;
	}

	/**
	 * @param value a bonus (percent) per tier, comma separated, Discovered first
	 * @param defaults the bonuses to use when the value can't be read
	 * @return one bonus per tier, none below 0
	 */
	private static int[] readBonuses(String value, int[] defaults)
	{
		final String[] parts = value.split(",");
		if (parts.length != TIER_NAMES.length)
		{
			return defaults;
		}

		final int[] bonuses = new int[TIER_NAMES.length];
		try
		{
			for (int i = 0; i < bonuses.length; i++)
			{
				bonuses[i] = Math.max(0, Integer.parseInt(parts[i].trim()));
			}
		}
		catch (NumberFormatException e)
		{
			return defaults;
		}
		return bonuses;
	}

	private static Set<String> readStrings(String value)
	{
		final Set<String> result = new HashSet<>();
		for (String part : value.split(","))
		{
			if (!part.isBlank())
			{
				result.add(part.trim());
			}
		}
		return Collections.unmodifiableSet(result);
	}

	private static Set<Integer> readIds(String value)
	{
		final Set<Integer> result = new HashSet<>();
		for (String part : value.split(","))
		{
			if (!part.isBlank())
			{
				try
				{
					result.add(Integer.parseInt(part.trim()));
				}
				catch (NumberFormatException e)
				{
					// not an id: skip it
				}
			}
		}
		return Collections.unmodifiableSet(result);
	}
}
