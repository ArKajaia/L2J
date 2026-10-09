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
 * Loads the dwarven trades configuration: what the dwarven spoilers (Scavenger, Bounty Hunter, Fortune Seeker) and crafters (Artisan, Warsmith, Maestro) do better than a character who took Spoil, Crystallize or Dwarven Craft from the passive tree (see {@link org.l2jmobius.gameserver.model.actor.holders.player.DwarvenTrades}).
 * @author Mobius
 */
public class DwarvenTradesConfig
{
	// File
	private static final String DWARVEN_TRADES_CONFIG_FILE = "./config/Custom/DwarvenTrades.ini";
	
	public static boolean DWARVEN_TRADES_ENABLED;
	
	// Spoil: index 0 = 1st class (Scavenger), 1 = 2nd class (Bounty Hunter), 2 = 3rd class (Fortune Seeker).
	public static boolean SPOIL_ALWAYS_LANDS;
	public static double[] SPOIL_CHANCE_MULTIPLIER;
	public static double[] SPOIL_AMOUNT_MULTIPLIER;
	
	// Craft: index 0 = 1st class (Artisan), 1 = 2nd class (Warsmith), 2 = 3rd class (Maestro).
	public static double[] CRAFT_SUCCESS_BONUS;
	public static double[] CRAFT_MASTERWORK_MULTIPLIER;
	public static double[] CRAFT_DOUBLE_CHANCE;
	
	// Crystallize
	public static double CRYSTALLIZE_BONUS;
	public static double[] CRAFTER_CRYSTALLIZE_BONUS;
	
	// Passive tree
	public static int TREE_CREATE_ITEM_MAX_LEVEL;
	
	// Combat: index 0 = 1st class, 1 = 2nd class, 2 = 3rd class.
	public static double[] SKULLCRUSHER_CHANCE_MULTIPLIER;
	public static double[] PLUNDER_DAMAGE_BONUS;
	public static double[] SPOILS_OF_WAR_RESTORE;
	public static double[] GOLEM_BONUS;
	public static double[] FORGED_WEAPON_BONUS;
	public static double[] FORGED_ARMOR_BONUS;
	public static int FORGED_SAFE_ENCHANT;
	public static double FORGED_MAX_BONUS;
	
	public static void load()
	{
		final ConfigReader config = new ConfigReader(DWARVEN_TRADES_CONFIG_FILE);
		DWARVEN_TRADES_ENABLED = config.getBoolean("DwarvenTradesEnabled", true);
		
		SPOIL_ALWAYS_LANDS = config.getBoolean("SpoilAlwaysLands", true);
		SPOIL_CHANCE_MULTIPLIER = getTiers(config, "SpoilChanceMultiplier", "1.25,1.5,1.75", 0);
		SPOIL_AMOUNT_MULTIPLIER = getTiers(config, "SpoilAmountMultiplier", "1.0,1.2,1.4", 0);
		
		CRAFT_SUCCESS_BONUS = getTiers(config, "CraftSuccessBonus", "5,10,15", 0);
		CRAFT_MASTERWORK_MULTIPLIER = getTiers(config, "CraftMasterworkMultiplier", "1.25,1.5,2.0", 0);
		CRAFT_DOUBLE_CHANCE = getTiers(config, "CraftDoubleChance", "5,10,15", 0);
		
		CRYSTALLIZE_BONUS = Math.max(0, config.getDouble("CrystallizeBonus", 20));
		CRAFTER_CRYSTALLIZE_BONUS = getTiers(config, "CrafterCrystallizeBonus", "10,20,30", 0);
		
		TREE_CREATE_ITEM_MAX_LEVEL = Math.max(1, config.getInt("TreeCreateItemMaxLevel", 5));
		
		SKULLCRUSHER_CHANCE_MULTIPLIER = getTiers(config, "SkullcrusherChanceMultiplier", "1.1,1.2,1.3", 0);
		PLUNDER_DAMAGE_BONUS = getTiers(config, "PlunderDamageBonus", "8,12,16", 0);
		SPOILS_OF_WAR_RESTORE = getTiers(config, "SpoilsOfWarRestore", "2,3,4", 0);
		GOLEM_BONUS = getTiers(config, "GolemBonus", "10,20,30", 0);
		FORGED_WEAPON_BONUS = getTiers(config, "ForgedWeaponBonus", "0.5,0.75,1.0", 0);
		FORGED_ARMOR_BONUS = getTiers(config, "ForgedArmorBonus", "0.1,0.15,0.2", 0);
		FORGED_SAFE_ENCHANT = Math.max(0, config.getInt("ForgedSafeEnchant", 3));
		FORGED_MAX_BONUS = Math.max(0, config.getDouble("ForgedMaxBonus", 15));
	}
	
	/**
	 * @param config the reader
	 * @param key the setting
	 * @param defaultValue three comma separated values, for the 1st, 2nd and 3rd class
	 * @param min the lowest value allowed
	 * @return the three values; a missing one repeats the last one given
	 */
	private static double[] getTiers(ConfigReader config, String key, String defaultValue, double min)
	{
		final double[] defaults = parse(defaultValue, new double[3], min);
		return parse(config.getString(key, defaultValue), defaults, min);
	}
	
	private static double[] parse(String text, double[] fallback, double min)
	{
		final double[] result = fallback.clone();
		final String[] parts = text.split(",");
		for (int i = 0; i < result.length; i++)
		{
			try
			{
				final String part = parts[Math.min(i, parts.length - 1)].trim();
				result[i] = Math.max(min, Double.parseDouble(part));
			}
			catch (NumberFormatException e)
			{
				// Keep the default for this class.
			}
		}
		return result;
	}
}
