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

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

import org.l2jmobius.commons.util.ConfigReader;

/**
 * The bestiary: every monster a character has killed, collected on a web page (see {@link org.l2jmobius.gameserver.managers.BestiaryManager}).
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
