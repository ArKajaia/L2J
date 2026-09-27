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
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.logging.Logger;

import org.l2jmobius.commons.util.ConfigReader;
import org.l2jmobius.commons.util.Rnd;

/**
 * Loads the roaming fake player configuration: a regular monster has {@link #SPAWN_CHANCE}% chance on every spawn to be replaced by a fake player of the same level that hunts nearby monsters and fights back against real players (see
 * {@link org.l2jmobius.gameserver.managers.FakePlayerPvpManager}).
 */
public class FakePlayerPvpConfig
{
	private static final Logger LOGGER = Logger.getLogger(FakePlayerPvpConfig.class.getName());
	
	// File
	private static final String FAKE_PLAYER_PVP_CONFIG_FILE = "./config/Custom/FakePlayerPvp.ini";
	
	// Spawning
	public static boolean ENABLED;
	public static double SPAWN_CHANCE;
	public static double HOTZONE_SPAWN_MULTIPLIER;
	public static int RESPAWN_COOLDOWN;
	public static int MIN_LEVEL;
	public static int MAX_LEVEL;
	public static int LEVEL_VARIANCE;
	public static boolean ALLOW_IN_INSTANCES;
	public static Set<Integer> EXCLUDED_NPC_IDS = new HashSet<>();
	public static int MAX_ALIVE;
	public static int MIN_DISTANCE;
	public static int LIFETIME;
	
	// Strength
	public static boolean INCLUDE_CP_IN_HP;
	/** {minLevel, minEnchant, maxEnchant} rows sorted by level. */
	public static List<int[]> WEAPON_ENCHANT = new ArrayList<>();
	public static List<int[]> ARMOR_ENCHANT = new ArrayList<>();
	public static int BONUS_STAT_MAX;
	public static boolean BUFFS_ENABLED;
	public static int POTION_HP_PERCENT;
	public static int POTION_HEAL_PERCENT;
	public static int POTION_REUSE;
	
	// Behaviour
	public static double MONSTER_DAMAGE_FLOOR;
	public static int HUNT_RANGE;
	public static int LEASH_RANGE;
	public static int CHASE_RANGE;
	public static boolean AVOID_PLAYER_MONSTERS;
	public static boolean REVENGE_ON_KILL_STEAL;
	public static int REVENGE_RANGE;
	public static int REVENGE_CHANCE;
	public static int ATTACK_FLAGGED_CHANCE;
	public static int ATTACK_KARMA_CHANCE;
	public static int FLEE_CHANCE;
	public static boolean ESCAPE_SCROLL;
	public static int CORPSE_TIME_MIN;
	public static int CORPSE_TIME_MAX;
	public static int SKILL_CHANCE;
	public static int PVP_SKILL_CHANCE;
	public static int PVP_DEBUFF_CHANCE;
	public static int PVP_ONLY_REUSE;
	public static int KITE_DISTANCE;
	public static int KITE_STEP;
	public static boolean WEAPON_SWAP_ENABLED;
	public static int WEAPON_SWAP_MIN_LEVEL;
	public static int WEAPON_SWAP_CHASE_TIME;
	public static int WEAPON_SWAP_MELEE_DISTANCE;
	public static int WEAPON_SWAP_INTERVAL;
	public static int TAUNT_CHANCE;
	
	// Rewards
	public static double REWARD_EXP_SP_MULTIPLIER;
	public static boolean REWARD_DROPS;
	public static double EQUIPMENT_DROP_CHANCE;
	
	public static void load()
	{
		final ConfigReader config = new ConfigReader(FAKE_PLAYER_PVP_CONFIG_FILE);
		ENABLED = config.getBoolean("FakePvpEnabled", true);
		SPAWN_CHANCE = Math.max(0, config.getDouble("FakePvpSpawnChance", 2.0));
		HOTZONE_SPAWN_MULTIPLIER = Math.max(0, config.getDouble("FakePvpHotzoneSpawnMultiplier", 2.0));
		RESPAWN_COOLDOWN = Math.max(0, config.getInt("FakePvpRespawnCooldown", 3));
		MIN_LEVEL = config.getInt("FakePvpMinLevel", 1);
		MAX_LEVEL = config.getInt("FakePvpMaxLevel", 85);
		LEVEL_VARIANCE = Math.max(0, config.getInt("FakePvpLevelVariance", 0));
		ALLOW_IN_INSTANCES = config.getBoolean("FakePvpAllowInInstances", false);
		// Built aside and swapped in, so a config reload never shows spawning threads a half filled set.
		final Set<Integer> excludedNpcIds = new HashSet<>();
		for (String idStr : config.getString("FakePvpExcludedNpcIds", "").split(","))
		{
			idStr = idStr.trim();
			if (!idStr.isEmpty())
			{
				try
				{
					excludedNpcIds.add(Integer.parseInt(idStr));
				}
				catch (NumberFormatException e)
				{
					LOGGER.warning("Invalid FakePvpExcludedNpcIds entry: " + idStr);
				}
			}
		}
		
		EXCLUDED_NPC_IDS = excludedNpcIds;
		
		MAX_ALIVE = Math.max(0, config.getInt("FakePvpMaxAlive", 500));
		MIN_DISTANCE = Math.max(0, config.getInt("FakePvpMinDistance", 1500));
		LIFETIME = Math.max(0, config.getInt("FakePvpLifetime", 3600));
		
		INCLUDE_CP_IN_HP = config.getBoolean("FakePvpIncludeCpInHp", true);
		WEAPON_ENCHANT = parseEnchantTiers(config.getString("FakePvpWeaponEnchant", "1:0-3;20:0-4;40:1-5;52:2-6;61:3-8;76:4-10;80:5-12;84:6-16"), "FakePvpWeaponEnchant");
		ARMOR_ENCHANT = parseEnchantTiers(config.getString("FakePvpArmorEnchant", "1:0-2;20:0-3;40:1-4;52:2-4;61:3-5;76:3-6;80:4-7;84:4-8"), "FakePvpArmorEnchant");
		BONUS_STAT_MAX = Math.max(0, config.getInt("FakePvpBonusStatMax", 10));
		BUFFS_ENABLED = config.getBoolean("FakePvpBuffsEnabled", true);
		POTION_HP_PERCENT = Math.max(0, Math.min(100, config.getInt("FakePvpPotionHpPercent", 50)));
		POTION_HEAL_PERCENT = Math.max(0, Math.min(100, config.getInt("FakePvpPotionHealPercent", 6)));
		POTION_REUSE = Math.max(1000, config.getInt("FakePvpPotionReuse", 10000));
		
		MONSTER_DAMAGE_FLOOR = Math.max(0, Math.min(100, config.getDouble("FakePvpMonsterDamageFloor", 70)));
		HUNT_RANGE = Math.max(100, config.getInt("FakePvpHuntRange", 900));
		LEASH_RANGE = Math.max(500, config.getInt("FakePvpLeashRange", 2500));
		CHASE_RANGE = Math.max(500, config.getInt("FakePvpChaseRange", 4000));
		AVOID_PLAYER_MONSTERS = config.getBoolean("FakePvpAvoidPlayerMonsters", true);
		REVENGE_ON_KILL_STEAL = config.getBoolean("FakePvpRevengeOnKillSteal", true);
		REVENGE_RANGE = Math.max(100, config.getInt("FakePvpRevengeRange", 1500));
		REVENGE_CHANCE = Math.max(0, Math.min(100, config.getInt("FakePvpRevengeChance", 70)));
		ATTACK_FLAGGED_CHANCE = Math.max(0, Math.min(100, config.getInt("FakePvpAttackFlaggedChance", 15)));
		ATTACK_KARMA_CHANCE = Math.max(0, Math.min(100, config.getInt("FakePvpAttackKarmaChance", 40)));
		FLEE_CHANCE = Math.max(0, Math.min(100, config.getInt("FakePvpFleeChance", 50)));
		ESCAPE_SCROLL = config.getBoolean("FakePvpEscapeScroll", true);
		CORPSE_TIME_MIN = 10;
		CORPSE_TIME_MAX = 30;
		final String corpseTime = config.getString("FakePvpCorpseTime", "10-30");
		try
		{
			final String[] range = corpseTime.split("-");
			CORPSE_TIME_MIN = Math.max(1, Integer.parseInt(range[0].trim()));
			CORPSE_TIME_MAX = Math.max(CORPSE_TIME_MIN, Integer.parseInt(range[range.length - 1].trim()));
		}
		catch (Exception e)
		{
			LOGGER.warning("Invalid FakePvpCorpseTime: " + corpseTime);
		}
		SKILL_CHANCE = Math.max(0, Math.min(100, config.getInt("FakePvpSkillChance", 65)));
		PVP_SKILL_CHANCE = Math.max(0, Math.min(100, config.getInt("FakePvpPvpSkillChance", 90)));
		PVP_DEBUFF_CHANCE = Math.max(0, Math.min(100, config.getInt("FakePvpPvpDebuffChance", 35)));
		PVP_ONLY_REUSE = Math.max(0, config.getInt("FakePvpPvpOnlyReuse", 30000));
		KITE_DISTANCE = Math.max(0, config.getInt("FakePvpKiteDistance", 250));
		KITE_STEP = Math.max(50, config.getInt("FakePvpKiteStep", 300));
		WEAPON_SWAP_ENABLED = config.getBoolean("FakePvpWeaponSwapEnabled", true);
		WEAPON_SWAP_MIN_LEVEL = config.getInt("FakePvpWeaponSwapMinLevel", 20);
		WEAPON_SWAP_CHASE_TIME = Math.max(0, config.getInt("FakePvpWeaponSwapChaseTime", 4000));
		WEAPON_SWAP_MELEE_DISTANCE = Math.max(0, config.getInt("FakePvpWeaponSwapMeleeDistance", 150));
		WEAPON_SWAP_INTERVAL = Math.max(500, config.getInt("FakePvpWeaponSwapInterval", 3000));
		TAUNT_CHANCE = Math.max(0, Math.min(100, config.getInt("FakePvpTauntChance", 50)));
		
		REWARD_EXP_SP_MULTIPLIER = Math.max(0, config.getDouble("FakePvpRewardExpSpMultiplier", 1.0));
		REWARD_DROPS = config.getBoolean("FakePvpRewardDrops", true);
		EQUIPMENT_DROP_CHANCE = Math.max(0, Math.min(100, config.getDouble("FakePvpEquipmentDropChance", 0.5)));
	}
	
	/**
	 * @param tiers {minLevel, minEnchant, maxEnchant} rows sorted by level
	 * @param level a fake player level
	 * @return a random enchant level from the highest row usable at {@code level}
	 */
	public static int rollEnchant(List<int[]> tiers, int level)
	{
		int[] range = null;
		for (int[] tier : tiers)
		{
			if (tier[0] > level)
			{
				break;
			}
			range = tier;
		}
		
		return range == null ? 0 : Rnd.get(range[1], range[2]);
	}
	
	/**
	 * Parses "minLevel:min-max;minLevel:min-max...".
	 * @param value the config value
	 * @param key the config key, for warnings
	 * @return the rows sorted by level
	 */
	private static List<int[]> parseEnchantTiers(String value, String key)
	{
		final List<int[]> result = new ArrayList<>();
		for (String entry : value.split(";"))
		{
			entry = entry.trim();
			if (entry.isEmpty())
			{
				continue;
			}
			
			try
			{
				final String[] levelAndRange = entry.split(":");
				final String[] range = levelAndRange[1].split("-");
				final int min = Math.max(0, Integer.parseInt(range[0].trim()));
				final int max = Math.max(min, Integer.parseInt(range[range.length - 1].trim()));
				result.add(new int[]
				{
					Integer.parseInt(levelAndRange[0].trim()),
					min,
					max
				});
			}
			catch (Exception e)
			{
				LOGGER.warning("Invalid " + key + " entry: " + entry);
			}
		}
		
		result.sort(Comparator.comparingInt(tier -> tier[0]));
		return result;
	}
}
