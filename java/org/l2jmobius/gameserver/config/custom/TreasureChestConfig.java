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

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.logging.Logger;

import org.l2jmobius.commons.util.ConfigReader;

/**
 * Loads the treasure chest configuration: real treasure chests look exactly like mimics, a real chest vanishes when it is hit, a mimic attacks whoever tries to open it with a key and curses whoever hits it with random debuffs, and an opened real chest gives Common, Rare or Epic crafting materials (see {@link org.l2jmobius.gameserver.model.actor.instance.Chest}).
 * @author Mobius
 */
public class TreasureChestConfig
{
	private static final Logger LOGGER = Logger.getLogger(TreasureChestConfig.class.getName());
	
	// File
	private static final String TREASURE_CHEST_CONFIG_FILE = "./config/Custom/TreasureChests.ini";
	
	/**
	 * One material a chest tier can give: item id and the amount range.
	 */
	public static class ChestMaterial
	{
		public final int itemId;
		public final int min;
		public final int max;
		
		public ChestMaterial(int itemId, int min, int max)
		{
			this.itemId = itemId;
			this.min = min;
			this.max = max;
		}
	}
	
	// Constants
	public static boolean ENABLED;
	public static boolean LEVEL_CHECK;
	public static boolean MESSAGES;
	public static double COMMON_CHANCE;
	public static double RARE_CHANCE;
	public static double EPIC_CHANCE;
	public static List<ChestMaterial> COMMON_MATERIALS = Collections.emptyList();
	public static List<ChestMaterial> RARE_MATERIALS = Collections.emptyList();
	public static List<ChestMaterial> EPIC_MATERIALS = Collections.emptyList();
	public static int COMMON_MIN_ITEMS;
	public static int COMMON_MAX_ITEMS;
	public static int RARE_MIN_ITEMS;
	public static int RARE_MAX_ITEMS;
	public static int EPIC_MIN_ITEMS;
	public static int EPIC_MAX_ITEMS;
	public static boolean MIMIC_DEBUFF_ENABLED;
	public static int MIMIC_DEBUFF_COUNT;
	public static boolean MIMIC_DEBUFF_ON_KEY;
	public static boolean MIMIC_DEBUFF_IGNORE_RESIST;
	public static List<Integer> MIMIC_DEBUFF_SKILLS = Collections.emptyList();
	public static boolean MIMIC_CAN_MOVE;
	public static int MIMIC_CHASE_RANGE;
	
	public static void load()
	{
		final ConfigReader config = new ConfigReader(TREASURE_CHEST_CONFIG_FILE);
		ENABLED = config.getBoolean("TreasureChestEnabled", true);
		LEVEL_CHECK = config.getBoolean("TreasureChestLevelCheck", true);
		MESSAGES = config.getBoolean("TreasureChestMessages", true);
		
		COMMON_CHANCE = Math.max(0, config.getDouble("TreasureChestCommonChance", 70));
		RARE_CHANCE = Math.max(0, config.getDouble("TreasureChestRareChance", 20));
		EPIC_CHANCE = Math.max(0, config.getDouble("TreasureChestEpicChance", 10));
		
		COMMON_MATERIALS = parseMaterials(config, "TreasureChestCommonMaterials", "1864,10,30;1865,10,30;1866,10,30;1867,10,30;1868,10,30;1869,10,30;1870,10,30;1871,10,30;1872,10,30;1873,5,15");
		RARE_MATERIALS = parseMaterials(config, "TreasureChestRareMaterials", "1878,3,8;1879,3,8;1880,3,8;1881,3,8;1882,3,8;1883,3,8;1884,3,8;1885,3,8;1886,3,8;1887,3,8;1874,2,5;1875,2,5;1876,2,5;1877,2,5");
		EPIC_MATERIALS = parseMaterials(config, "TreasureChestEpicMaterials", "1888,1,3;1889,1,3;1890,1,3;1891,1,3;1892,1,3;1893,1,3;1894,1,3;1895,1,3;4039,1,2;4040,1,2;4041,1,2;4042,1,2;4043,1,2;4044,1,2;5549,1,2;5550,1,2");
		
		COMMON_MIN_ITEMS = Math.max(1, config.getInt("TreasureChestCommonMinItems", 2));
		COMMON_MAX_ITEMS = Math.max(COMMON_MIN_ITEMS, config.getInt("TreasureChestCommonMaxItems", 3));
		RARE_MIN_ITEMS = Math.max(1, config.getInt("TreasureChestRareMinItems", 2));
		RARE_MAX_ITEMS = Math.max(RARE_MIN_ITEMS, config.getInt("TreasureChestRareMaxItems", 3));
		EPIC_MIN_ITEMS = Math.max(1, config.getInt("TreasureChestEpicMinItems", 2));
		EPIC_MAX_ITEMS = Math.max(EPIC_MIN_ITEMS, config.getInt("TreasureChestEpicMaxItems", 3));
		
		MIMIC_DEBUFF_ENABLED = config.getBoolean("MimicDebuffEnabled", true);
		MIMIC_DEBUFF_COUNT = Math.max(0, config.getInt("MimicDebuffCount", 5));
		MIMIC_DEBUFF_ON_KEY = config.getBoolean("MimicDebuffOnKey", true);
		MIMIC_DEBUFF_IGNORE_RESIST = config.getBoolean("MimicDebuffIgnoreResist", true);
		final List<Integer> skills = new ArrayList<>();
		for (String idStr : config.getString("MimicDebuffSkills", "4182,4188,4183,4184,4187,4190,4054,4119,4098,4186,4185,4189,4208").split(","))
		{
			idStr = idStr.trim();
			if (!idStr.isEmpty())
			{
				try
				{
					final int skillId = Integer.parseInt(idStr);
					if (!skills.contains(skillId))
					{
						skills.add(skillId);
					}
				}
				catch (NumberFormatException e)
				{
					LOGGER.warning("Invalid MimicDebuffSkills entry: " + idStr);
				}
			}
		}
		MIMIC_DEBUFF_SKILLS = skills;
		
		MIMIC_CAN_MOVE = config.getBoolean("MimicCanMove", true);
		MIMIC_CHASE_RANGE = Math.max(0, config.getInt("MimicChaseRange", 1000));
	}
	
	private static List<ChestMaterial> parseMaterials(ConfigReader config, String key, String defaultValue)
	{
		final List<ChestMaterial> result = new ArrayList<>();
		for (String entry : config.getString(key, defaultValue).split(";"))
		{
			entry = entry.trim();
			if (entry.isEmpty())
			{
				continue;
			}
			
			final String[] parts = entry.split(",");
			try
			{
				final int itemId = Integer.parseInt(parts[0].trim());
				final int min = Math.max(1, Integer.parseInt(parts[1].trim()));
				final int max = Math.max(min, Integer.parseInt(parts[2].trim()));
				result.add(new ChestMaterial(itemId, min, max));
			}
			catch (NumberFormatException | ArrayIndexOutOfBoundsException e)
			{
				LOGGER.warning("Invalid " + key + " entry (expected itemId,min,max): " + entry);
			}
		}
		return result;
	}
}
