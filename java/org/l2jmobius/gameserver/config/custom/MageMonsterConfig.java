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
 * Loads the Mage monster configuration: a regular monster that knows a magic damage spell has {@link #SPAWN_CHANCE}% chance on spawn to become a Mage, which casts non-stop until its MP drops to {@link #LOW_MANA_PERCENT}%, kites, has boosted MP and cast speed, drops more and always drops a
 * Sealed Cache (see {@link org.l2jmobius.gameserver.managers.MageMonsterManager}).
 * @author Mobius
 */
public class MageMonsterConfig
{
	private static final Logger LOGGER = Logger.getLogger(MageMonsterConfig.class.getName());

	// File
	private static final String MAGE_MONSTER_CONFIG_FILE = "./config/Custom/MageMonsters.ini";

	// Constants
	public static boolean ENABLED;
	public static double SPAWN_CHANCE;
	public static int RESPAWN_COOLDOWN;
	public static int MIN_LEVEL;
	public static int MAX_LEVEL;
	public static boolean ALLOW_IN_INSTANCES;
	public static boolean ALLOW_CHAMPIONS;
	public static Set<Integer> EXCLUDED_NPC_IDS = new HashSet<>();
	public static double MP_MULTIPLIER;
	public static double CAST_SPEED_BONUS;
	public static double DROP_AMOUNT_MULTIPLIER;
	public static double SPOIL_AMOUNT_MULTIPLIER;
	public static double LOW_MANA_PERCENT;
	public static int KITE_DISTANCE;
	public static int KITE_STEP;
	public static int KITE_INTERVAL;
	public static boolean CACHE_ON_DEATH;
	public static String TITLE_TAG;

	public static void load()
	{
		final ConfigReader config = new ConfigReader(MAGE_MONSTER_CONFIG_FILE);
		ENABLED = config.getBoolean("MageEnabled", true);
		SPAWN_CHANCE = config.getDouble("MageSpawnChance", 2.0);
		RESPAWN_COOLDOWN = Math.max(0, config.getInt("MageRespawnCooldown", 1));
		MIN_LEVEL = config.getInt("MageMinLevel", 20);
		MAX_LEVEL = config.getInt("MageMaxLevel", 99);
		ALLOW_IN_INSTANCES = config.getBoolean("MageAllowInInstances", false);
		ALLOW_CHAMPIONS = config.getBoolean("MageAllowChampions", false);

		EXCLUDED_NPC_IDS.clear();
		for (String idStr : config.getString("MageExcludedNpcIds", "").split(","))
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
					LOGGER.warning("Invalid MageExcludedNpcIds entry: " + idStr);
				}
			}
		}

		MP_MULTIPLIER = Math.max(0.1, config.getDouble("MageMpMultiplier", 3.0));
		CAST_SPEED_BONUS = config.getDouble("MageCastSpeedBonus", 30);
		DROP_AMOUNT_MULTIPLIER = Math.max(0, config.getDouble("MageDropAmountMultiplier", 2.0));
		SPOIL_AMOUNT_MULTIPLIER = Math.max(0, config.getDouble("MageSpoilAmountMultiplier", 2.0));
		LOW_MANA_PERCENT = Math.max(0, Math.min(100, config.getDouble("MageLowManaPercent", 10)));
		KITE_DISTANCE = Math.max(0, config.getInt("MageKiteDistance", 300));
		KITE_STEP = Math.max(50, config.getInt("MageKiteStep", 350));
		KITE_INTERVAL = Math.max(0, config.getInt("MageKiteInterval", 4000));
		CACHE_ON_DEATH = config.getBoolean("MageCacheOnDeath", true);
		TITLE_TAG = config.getString("MageTitleTag", "[Mage]");
	}
}
