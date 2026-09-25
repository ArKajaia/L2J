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

import java.util.HashSet;
import java.util.Set;
import java.util.logging.Logger;

import org.l2jmobius.commons.util.ConfigReader;

/**
 * Loads the open-world Wave Challenge configuration: a regular monster has {@link #SPAWN_CHANCE}% chance on spawn to become a {@link #WAVE_COUNT}-wave challenge that heals and grows stronger each time it is "killed" - the same compounding scaling the Survival Arena challenger uses. Defeating the final
 * wave pays the Survival Arena reward of an equivalent arena wave, {@code (finalLevel - COIN_LEVEL_OFFSET) / COIN_LEVEL_DIVISOR}, split evenly between everyone who damaged it, and multiplies XP/SP while dropping no adena.
 * @author Mobius
 */
public class WaveChallengeConfig
{
	private static final Logger LOGGER = Logger.getLogger(WaveChallengeConfig.class.getName());
	
	// File
	private static final String WAVE_CHALLENGE_CONFIG_FILE = "./config/Custom/WaveChallenge.ini";
	
	// Constants
	public static boolean ENABLED;
	public static double SPAWN_CHANCE;
	public static int MIN_LEVEL;
	public static int MAX_LEVEL;
	public static boolean ALLOW_IN_INSTANCES;
	public static boolean ALLOW_CHAMPIONS;
	public static Set<Integer> EXCLUDED_NPC_IDS = new HashSet<>();
	public static int WAVE_COUNT;
	public static int STAT_GROWTH_PER_WAVE;
	public static int OFFENSE_GROWTH_PER_WAVE;
	public static int DEFENSE_GROWTH_PER_WAVE;
	public static int LEVEL_GROWTH_PER_WAVE;
	public static int MAX_VIRTUAL_LEVEL;
	public static boolean REROLL_PASSIVES_PER_WAVE;
	public static boolean APPLY_ARENA_BUFFS;
	public static boolean RESET_ON_LEASH;
	public static double XP_MULTIPLIER;
	public static double SP_MULTIPLIER;
	public static boolean NO_ADENA;
	public static int COIN_ITEM_ID;
	public static int COIN_LEVEL_OFFSET;
	public static int COIN_LEVEL_DIVISOR;
	public static int MIN_ARENA_WAVE;
	public static String TITLE_TAG;
	
	public static void load()
	{
		final ConfigReader config = new ConfigReader(WAVE_CHALLENGE_CONFIG_FILE);
		ENABLED = config.getBoolean("WaveChallengeEnabled", true);
		SPAWN_CHANCE = config.getDouble("WaveChallengeSpawnChance", 1.0);
		MIN_LEVEL = config.getInt("WaveChallengeMinLevel", 40);
		MAX_LEVEL = config.getInt("WaveChallengeMaxLevel", 99);
		ALLOW_IN_INSTANCES = config.getBoolean("WaveChallengeAllowInInstances", false);
		ALLOW_CHAMPIONS = config.getBoolean("WaveChallengeAllowChampions", false);
		
		EXCLUDED_NPC_IDS.clear();
		for (String idStr : config.getString("WaveChallengeExcludedNpcIds", "").split(","))
		{
			idStr = idStr.trim();
			if (!idStr.isEmpty())
			{
				try
				{
					EXCLUDED_NPC_IDS.add(Integer.parseInt(idStr));
				}
				catch (NumberFormatException e)
				{
					LOGGER.warning("Invalid WaveChallengeExcludedNpcIds entry: " + idStr);
				}
			}
		}
		
		WAVE_COUNT = Math.max(1, config.getInt("WaveChallengeWaveCount", 3));
		STAT_GROWTH_PER_WAVE = config.getInt("WaveChallengeStatGrowthPerWave", 8);
		OFFENSE_GROWTH_PER_WAVE = config.getInt("WaveChallengeOffenseGrowthPerWave", 13);
		DEFENSE_GROWTH_PER_WAVE = config.getInt("WaveChallengeDefenseGrowthPerWave", 7);
		LEVEL_GROWTH_PER_WAVE = config.getInt("WaveChallengeLevelGrowthPerWave", 2);
		MAX_VIRTUAL_LEVEL = config.getInt("WaveChallengeMaxVirtualLevel", 90);
		REROLL_PASSIVES_PER_WAVE = config.getBoolean("WaveChallengeRerollPassivesPerWave", true);
		APPLY_ARENA_BUFFS = config.getBoolean("WaveChallengeApplyArenaBuffs", false);
		RESET_ON_LEASH = config.getBoolean("WaveChallengeResetOnLeash", true);
		XP_MULTIPLIER = config.getDouble("WaveChallengeXpMultiplier", 5.0);
		SP_MULTIPLIER = config.getDouble("WaveChallengeSpMultiplier", 5.0);
		NO_ADENA = config.getBoolean("WaveChallengeNoAdena", true);
		COIN_ITEM_ID = config.getInt("WaveChallengeCoinItemId", 3481);
		COIN_LEVEL_OFFSET = config.getInt("WaveChallengeCoinLevelOffset", 40);
		COIN_LEVEL_DIVISOR = Math.max(1, config.getInt("WaveChallengeCoinLevelDivisor", 2));
		MIN_ARENA_WAVE = Math.max(1, config.getInt("WaveChallengeMinArenaWave", 1));
		TITLE_TAG = config.getString("WaveChallengeTitleTag", "[Wave %d/%d]");
	}
}
