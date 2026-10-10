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

import java.util.Arrays;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.logging.Logger;

import org.l2jmobius.commons.util.ConfigReader;
import org.l2jmobius.gameserver.model.item.holders.Elementals;
import org.l2jmobius.gameserver.model.skill.AbnormalVisualEffect;

/**
 * Loads the Infused monster configuration: a regular monster has {@link #SPAWN_CHANCE}% chance on spawn to be infused with an element. It attacks with that element, resists it, is weak to the opposite one, pulses an elemental nova and drops attribute stones (and at high levels
 * attribute crystals) of its element (see {@link org.l2jmobius.gameserver.managers.InfusedMonsterManager}).
 * @author Mobius
 */
public class InfusedMonsterConfig
{
	private static final Logger LOGGER = Logger.getLogger(InfusedMonsterConfig.class.getName());

	// File
	private static final String INFUSED_MONSTER_CONFIG_FILE = "./config/Custom/InfusedMonsters.ini";

	/** Number of elements, indexed by the {@link Elementals} ids (FIRE 0 ... DARK 5). */
	public static final int ELEMENT_COUNT = 6;

	// Constants
	public static boolean ENABLED;
	public static double SPAWN_CHANCE;
	public static int RESPAWN_COOLDOWN;
	public static int MIN_LEVEL;
	public static int MAX_LEVEL;
	public static boolean ALLOW_IN_INSTANCES;
	public static boolean ALLOW_CHAMPIONS;
	public static Set<Integer> EXCLUDED_NPC_IDS = new HashSet<>();
	/** Weight of each element in the random pick, indexed by element id. */
	public static double[] ELEMENT_WEIGHTS = new double[ELEMENT_COUNT];
	public static int KEEP_TEMPLATE_ELEMENT_CHANCE;
	public static int ATTACK_POWER;
	public static int OWN_RESIST;
	public static int OPPOSITE_RESIST;
	public static double HP_MULTIPLIER;
	public static double ATTACK_MULTIPLIER;
	public static int NOVA_INTERVAL;
	/** Nova skill id of each element, indexed by element id. 0 = none. */
	public static int[] NOVA_SKILLS = new int[ELEMENT_COUNT];
	/** Visual effect of each element, indexed by element id. */
	public static AbnormalVisualEffect[] VISUAL_EFFECTS = new AbnormalVisualEffect[ELEMENT_COUNT];
	/** From this monster level on: {min, max} attribute stones dropped. */
	public static TreeMap<Integer, int[]> STONE_AMOUNTS = new TreeMap<>();
	/** From this monster level on: chance (%) of one attribute crystal. */
	public static TreeMap<Integer, Double> CRYSTAL_CHANCES = new TreeMap<>();
	public static int MAX_LEVEL_DIFFERENCE;
	public static boolean MESSAGE;
	public static String TITLE_TAG;

	public static void load()
	{
		final ConfigReader config = new ConfigReader(INFUSED_MONSTER_CONFIG_FILE);
		ENABLED = config.getBoolean("InfusedEnabled", true);
		SPAWN_CHANCE = config.getDouble("InfusedSpawnChance", 1.5);
		RESPAWN_COOLDOWN = Math.max(0, config.getInt("InfusedRespawnCooldown", 1));
		MIN_LEVEL = config.getInt("InfusedMinLevel", 61);
		MAX_LEVEL = config.getInt("InfusedMaxLevel", 99);
		ALLOW_IN_INSTANCES = config.getBoolean("InfusedAllowInInstances", false);
		ALLOW_CHAMPIONS = config.getBoolean("InfusedAllowChampions", false);

		EXCLUDED_NPC_IDS.clear();
		for (String idStr : config.getString("InfusedExcludedNpcIds", "").split(","))
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
					LOGGER.warning("Invalid InfusedExcludedNpcIds entry: " + idStr);
				}
			}
		}

		ELEMENT_WEIGHTS = new double[ELEMENT_COUNT];
		Arrays.fill(ELEMENT_WEIGHTS, 1);
		for (Map.Entry<Byte, String> entry : parseElementMap(config.getString("InfusedElementWeights", "FIRE:1,WATER:1,WIND:1,EARTH:1,HOLY:1,DARK:1"), "InfusedElementWeights").entrySet())
		{
			try
			{
				ELEMENT_WEIGHTS[entry.getKey()] = Math.max(0, Double.parseDouble(entry.getValue()));
			}
			catch (NumberFormatException e)
			{
				LOGGER.warning("Invalid InfusedElementWeights weight: " + entry.getValue());
			}
		}

		KEEP_TEMPLATE_ELEMENT_CHANCE = Math.max(0, Math.min(100, config.getInt("InfusedKeepTemplateElementChance", 70)));
		ATTACK_POWER = config.getInt("InfusedAttackPower", 150);
		OWN_RESIST = config.getInt("InfusedOwnResist", 150);
		OPPOSITE_RESIST = config.getInt("InfusedOppositeResist", -100);
		HP_MULTIPLIER = Math.max(0.1, config.getDouble("InfusedHpMultiplier", 2.0));
		ATTACK_MULTIPLIER = Math.max(0.1, config.getDouble("InfusedAttackMultiplier", 1.2));
		NOVA_INTERVAL = Math.max(0, config.getInt("InfusedNovaInterval", 12000));

		NOVA_SKILLS = new int[ELEMENT_COUNT];
		for (Map.Entry<Byte, String> entry : parseElementMap(config.getString("InfusedNovaSkills", "FIRE:3180,WATER:3181,WIND:3182,EARTH:3183,HOLY:3184,DARK:3185"), "InfusedNovaSkills").entrySet())
		{
			try
			{
				NOVA_SKILLS[entry.getKey()] = Math.max(0, Integer.parseInt(entry.getValue()));
			}
			catch (NumberFormatException e)
			{
				LOGGER.warning("Invalid InfusedNovaSkills skill id: " + entry.getValue());
			}
		}

		VISUAL_EFFECTS = new AbnormalVisualEffect[ELEMENT_COUNT];
		Arrays.fill(VISUAL_EFFECTS, AbnormalVisualEffect.NONE);
		for (Map.Entry<Byte, String> entry : parseElementMap(config.getString("InfusedVisualEffects", "FIRE:DOT_FIRE,WATER:DOT_WATER,WIND:DOT_WIND,EARTH:DOT_SOIL,HOLY:ULTIMATE_DEFENCE,DARK:STIGMA_OF_SILEN"), "InfusedVisualEffects").entrySet())
		{
			try
			{
				VISUAL_EFFECTS[entry.getKey()] = AbnormalVisualEffect.valueOf(entry.getValue().toUpperCase());
			}
			catch (IllegalArgumentException e)
			{
				LOGGER.warning("Unknown InfusedVisualEffects effect: " + entry.getValue());
			}
		}

		STONE_AMOUNTS = new TreeMap<>();
		for (Map.Entry<Integer, String> entry : parseLevelMap(config.getString("InfusedStoneAmounts", "61:1-1,76:1-2,80:2-3"), "InfusedStoneAmounts").entrySet())
		{
			final String[] range = entry.getValue().split("-");
			try
			{
				final int min = Math.max(0, Integer.parseInt(range[0].trim()));
				final int max = range.length > 1 ? Math.max(min, Integer.parseInt(range[1].trim())) : min;
				STONE_AMOUNTS.put(entry.getKey(), new int[]
				{
					min,
					max
				});
			}
			catch (NumberFormatException e)
			{
				LOGGER.warning("Invalid InfusedStoneAmounts amount: " + entry.getValue());
			}
		}

		CRYSTAL_CHANCES = new TreeMap<>();
		for (Map.Entry<Integer, String> entry : parseLevelMap(config.getString("InfusedCrystalChances", "80:3,84:6"), "InfusedCrystalChances").entrySet())
		{
			try
			{
				CRYSTAL_CHANCES.put(entry.getKey(), Math.max(0, Math.min(100, Double.parseDouble(entry.getValue()))));
			}
			catch (NumberFormatException e)
			{
				LOGGER.warning("Invalid InfusedCrystalChances chance: " + entry.getValue());
			}
		}

		MAX_LEVEL_DIFFERENCE = config.getInt("InfusedMaxLevelDifference", 8);
		MESSAGE = config.getBoolean("InfusedMessage", true);
		TITLE_TAG = config.getString("InfusedTitleTag", "[%s]");
	}

	/**
	 * Parses {@code ELEMENT:value,ELEMENT:value...} into element id -&gt; value.
	 * @param value the config value
	 * @param key the config key, for warnings
	 * @return the parsed entries
	 */
	private static Map<Byte, String> parseElementMap(String value, String key)
	{
		final Map<Byte, String> result = new TreeMap<>();
		for (String part : value.split(","))
		{
			final String[] pair = part.trim().split(":");
			if (pair.length != 2)
			{
				if (!part.isBlank())
				{
					LOGGER.warning("Invalid " + key + " entry: " + part);
				}
				continue;
			}

			final byte element = Elementals.getElementId(pair[0].trim());
			if ((element < 0) || (element >= ELEMENT_COUNT))
			{
				LOGGER.warning("Unknown element in " + key + ": " + pair[0]);
				continue;
			}

			result.put(element, pair[1].trim());
		}
		return result;
	}

	/**
	 * Parses {@code level:value,level:value...} into level -&gt; value.
	 * @param value the config value
	 * @param key the config key, for warnings
	 * @return the parsed entries
	 */
	private static Map<Integer, String> parseLevelMap(String value, String key)
	{
		final Map<Integer, String> result = new TreeMap<>();
		for (String part : value.split(","))
		{
			final String[] pair = part.trim().split(":");
			if (pair.length != 2)
			{
				if (!part.isBlank())
				{
					LOGGER.warning("Invalid " + key + " entry: " + part);
				}
				continue;
			}

			try
			{
				result.put(Integer.parseInt(pair[0].trim()), pair[1].trim());
			}
			catch (NumberFormatException e)
			{
				LOGGER.warning("Invalid level in " + key + ": " + pair[0]);
			}
		}
		return result;
	}
}
