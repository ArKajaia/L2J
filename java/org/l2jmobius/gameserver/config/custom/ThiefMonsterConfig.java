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
 * Loads the Thief monster configuration: a regular monster has {@link #SPAWN_CHANCE}% chance on spawn to become a Thief. Every monster a player kills within {@link #DETECTION_RADIUS} of it fills its loot bag by {@code 100 / KILLS_FOR_MAX} percent, and its adena drop is multiplied by up to
 * {@link #MAX_ADENA_MULTIPLIER} at a full bag (see {@link org.l2jmobius.gameserver.managers.ThiefMonsterManager}).
 * @author Mobius
 */
public class ThiefMonsterConfig
{
	private static final Logger LOGGER = Logger.getLogger(ThiefMonsterConfig.class.getName());
	
	// File
	private static final String THIEF_MONSTER_CONFIG_FILE = "./config/Custom/ThiefMonsters.ini";
	
	// Constants
	public static boolean ENABLED;
	public static double SPAWN_CHANCE;
	public static int RESPAWN_COOLDOWN;
	public static int MIN_LEVEL;
	public static int MAX_LEVEL;
	public static boolean ALLOW_IN_INSTANCES;
	public static boolean ALLOW_CHAMPIONS;
	public static Set<Integer> EXCLUDED_NPC_IDS = new HashSet<>();
	public static int DETECTION_RADIUS;
	public static int KILLS_FOR_MAX;
	public static double MIN_ADENA_MULTIPLIER;
	public static double MAX_ADENA_MULTIPLIER;
	public static String TITLE_TAG;
	
	public static void load()
	{
		final ConfigReader config = new ConfigReader(THIEF_MONSTER_CONFIG_FILE);
		ENABLED = config.getBoolean("ThiefEnabled", true);
		SPAWN_CHANCE = config.getDouble("ThiefSpawnChance", 2.0);
		RESPAWN_COOLDOWN = Math.max(0, config.getInt("ThiefRespawnCooldown", 1));
		MIN_LEVEL = config.getInt("ThiefMinLevel", 20);
		MAX_LEVEL = config.getInt("ThiefMaxLevel", 99);
		ALLOW_IN_INSTANCES = config.getBoolean("ThiefAllowInInstances", false);
		ALLOW_CHAMPIONS = config.getBoolean("ThiefAllowChampions", false);
		
		EXCLUDED_NPC_IDS.clear();
		for (String idStr : config.getString("ThiefExcludedNpcIds", "").split(","))
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
					LOGGER.warning("Invalid ThiefExcludedNpcIds entry: " + idStr);
				}
			}
		}
		
		DETECTION_RADIUS = Math.max(0, config.getInt("ThiefDetectionRadius", 1000));
		KILLS_FOR_MAX = Math.max(1, config.getInt("ThiefKillsForMax", 10));
		MIN_ADENA_MULTIPLIER = config.getDouble("ThiefMinAdenaMultiplier", 1.0);
		MAX_ADENA_MULTIPLIER = config.getDouble("ThiefMaxAdenaMultiplier", 20.0);
		TITLE_TAG = config.getString("ThiefTitleTag", "[Thief: %d%%]");
	}
}
