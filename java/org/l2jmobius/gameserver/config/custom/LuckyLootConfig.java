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
package org.l2jmobius.gameserver.config.custom;

import org.l2jmobius.commons.util.ConfigReader;

/**
 * Loads the lucky loot configuration: Luck stacks built from a kill streak (see {@link org.l2jmobius.gameserver.managers.LuckyLootManager}), the rare Jackpot kill (its chance grows with Luck) that re-rolls a monster's drop table several times, and the Sealed Cache drop (Common/Rare/Epic extractable boxes).
 * @author Mobius
 */
public class LuckyLootConfig
{
	// File
	private static final String LUCKY_LOOT_CONFIG_FILE = "./config/Custom/LuckyLoot.ini";

	// Lucky Streak
	public static boolean LUCK_ENABLED;
	public static int LUCK_MAX_STACKS;
	public static double LUCK_DROP_BONUS_PER_STACK;
	public static double LUCK_GAIN_CHANCE_AT_ZERO;
	public static double LUCK_GAIN_CHANCE_AT_MAX;
	public static int LUCK_MAX_LEVEL_DIFFERENCE;
	public static boolean LUCK_TITLE_ENABLED;
	public static String LUCK_TITLE_FORMAT;
	public static boolean LUCK_MILESTONE_MESSAGE;

	// Jackpot
	public static boolean JACKPOT_ENABLED;
	public static double JACKPOT_CHANCE_AT_MIN_LUCK;
	public static double JACKPOT_CHANCE_AT_MAX_LUCK;
	public static boolean JACKPOT_RESETS_LUCK;
	public static int JACKPOT_DROP_ROLLS;
	public static boolean JACKPOT_ANNOUNCE;
	public static int JACKPOT_MIN_LEVEL;

	// Sealed Cache
	public static boolean SEALED_CACHE_ENABLED;
	public static double SEALED_CACHE_CHANCE;
	public static double SEALED_CACHE_CHANCE_BONUS_PER_STACK;
	public static int SEALED_CACHE_MIN_LEVEL;
	public static int SEALED_CACHE_COMMON_ITEM_ID;
	public static int SEALED_CACHE_RARE_ITEM_ID;
	public static int SEALED_CACHE_EPIC_ITEM_ID;
	public static int SEALED_CACHE_COMMON_WEIGHT;
	public static int SEALED_CACHE_RARE_WEIGHT;
	public static int SEALED_CACHE_EPIC_WEIGHT;
	public static boolean SEALED_CACHE_HIGH_LEVEL_BONUS;

	public static void load()
	{
		final ConfigReader config = new ConfigReader(LUCKY_LOOT_CONFIG_FILE);
		LUCK_ENABLED = config.getBoolean("LuckEnabled", true);
		LUCK_MAX_STACKS = Math.max(0, config.getInt("LuckMaxStacks", 20));
		LUCK_DROP_BONUS_PER_STACK = config.getDouble("LuckDropBonusPerStack", 2.0);
		LUCK_GAIN_CHANCE_AT_ZERO = config.getDouble("LuckGainChanceAtZero", 30.0);
		LUCK_GAIN_CHANCE_AT_MAX = config.getDouble("LuckGainChanceAtMax", 20.0);
		LUCK_MAX_LEVEL_DIFFERENCE = config.getInt("LuckMaxLevelDifference", 8);
		LUCK_TITLE_ENABLED = config.getBoolean("LuckTitleEnabled", true);
		LUCK_TITLE_FORMAT = config.getString("LuckTitleFormat", "[Luck %d]");
		LUCK_MILESTONE_MESSAGE = config.getBoolean("LuckMilestoneMessage", true);

		JACKPOT_ENABLED = config.getBoolean("JackpotEnabled", true);
		JACKPOT_CHANCE_AT_MIN_LUCK = config.getDouble("JackpotChanceAtMinLuck", 0.05);
		JACKPOT_CHANCE_AT_MAX_LUCK = config.getDouble("JackpotChanceAtMaxLuck", 1.0);
		JACKPOT_RESETS_LUCK = config.getBoolean("JackpotResetsLuck", true);
		JACKPOT_DROP_ROLLS = Math.max(1, config.getInt("JackpotDropRolls", 10));
		JACKPOT_ANNOUNCE = config.getBoolean("JackpotAnnounce", true);
		JACKPOT_MIN_LEVEL = config.getInt("JackpotMinLevel", 20);

		SEALED_CACHE_ENABLED = config.getBoolean("SealedCacheEnabled", true);
		SEALED_CACHE_CHANCE = config.getDouble("SealedCacheChance", 0.5);
		SEALED_CACHE_CHANCE_BONUS_PER_STACK = config.getDouble("SealedCacheChanceBonusPerStack", 2.0);
		SEALED_CACHE_MIN_LEVEL = config.getInt("SealedCacheMinLevel", 20);
		SEALED_CACHE_COMMON_ITEM_ID = config.getInt("SealedCacheCommonItemId", 6492);
		SEALED_CACHE_RARE_ITEM_ID = config.getInt("SealedCacheRareItemId", 6499);
		SEALED_CACHE_EPIC_ITEM_ID = config.getInt("SealedCacheEpicItemId", 6509);
		SEALED_CACHE_COMMON_WEIGHT = Math.max(0, config.getInt("SealedCacheCommonWeight", 70));
		SEALED_CACHE_RARE_WEIGHT = Math.max(0, config.getInt("SealedCacheRareWeight", 25));
		SEALED_CACHE_EPIC_WEIGHT = Math.max(0, config.getInt("SealedCacheEpicWeight", 5));
		SEALED_CACHE_HIGH_LEVEL_BONUS = config.getBoolean("SealedCacheHighLevelBonus", true);
	}
}
