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
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.logging.Logger;

import org.l2jmobius.commons.util.ConfigReader;
import org.l2jmobius.gameserver.model.skill.holders.SkillHolder;

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
	public static int WEAPON_ENCHANT_MIN;
	public static int WEAPON_ENCHANT_MAX;
	public static int ARMOR_ENCHANT_MIN;
	public static int ARMOR_ENCHANT_MAX;
	public static boolean BUFFS_ENABLED;
	public static List<SkillHolder> FIGHTER_BUFFS = new ArrayList<>();
	public static List<SkillHolder> MAGE_BUFFS = new ArrayList<>();
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
	public static int SKILL_CHANCE;
	public static int KITE_DISTANCE;
	public static int KITE_STEP;
	public static int TAUNT_CHANCE;
	
	// Rewards
	public static double REWARD_EXP_SP_MULTIPLIER;
	public static boolean REWARD_DROPS;
	
	public static void load()
	{
		final ConfigReader config = new ConfigReader(FAKE_PLAYER_PVP_CONFIG_FILE);
		ENABLED = config.getBoolean("FakePvpEnabled", true);
		SPAWN_CHANCE = Math.max(0, config.getDouble("FakePvpSpawnChance", 2.0));
		RESPAWN_COOLDOWN = Math.max(0, config.getInt("FakePvpRespawnCooldown", 3));
		MIN_LEVEL = config.getInt("FakePvpMinLevel", 1);
		MAX_LEVEL = config.getInt("FakePvpMaxLevel", 85);
		LEVEL_VARIANCE = Math.max(0, config.getInt("FakePvpLevelVariance", 0));
		ALLOW_IN_INSTANCES = config.getBoolean("FakePvpAllowInInstances", false);
		EXCLUDED_NPC_IDS.clear();
		for (String idStr : config.getString("FakePvpExcludedNpcIds", "").split(","))
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
					LOGGER.warning("Invalid FakePvpExcludedNpcIds entry: " + idStr);
				}
			}
		}
		
		MAX_ALIVE = Math.max(0, config.getInt("FakePvpMaxAlive", 500));
		MIN_DISTANCE = Math.max(0, config.getInt("FakePvpMinDistance", 1500));
		LIFETIME = Math.max(0, config.getInt("FakePvpLifetime", 3600));
		
		INCLUDE_CP_IN_HP = config.getBoolean("FakePvpIncludeCpInHp", true);
		WEAPON_ENCHANT_MIN = Math.max(0, config.getInt("FakePvpWeaponEnchantMin", 0));
		WEAPON_ENCHANT_MAX = Math.max(WEAPON_ENCHANT_MIN, config.getInt("FakePvpWeaponEnchantMax", 6));
		ARMOR_ENCHANT_MIN = Math.max(0, config.getInt("FakePvpArmorEnchantMin", 0));
		ARMOR_ENCHANT_MAX = Math.max(ARMOR_ENCHANT_MIN, config.getInt("FakePvpArmorEnchantMax", 4));
		BUFFS_ENABLED = config.getBoolean("FakePvpBuffsEnabled", true);
		FIGHTER_BUFFS = parseSkills(config.getString("FakePvpFighterBuffs", ""), "FakePvpFighterBuffs");
		MAGE_BUFFS = parseSkills(config.getString("FakePvpMageBuffs", ""), "FakePvpMageBuffs");
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
		SKILL_CHANCE = Math.max(0, Math.min(100, config.getInt("FakePvpSkillChance", 65)));
		KITE_DISTANCE = Math.max(0, config.getInt("FakePvpKiteDistance", 250));
		KITE_STEP = Math.max(50, config.getInt("FakePvpKiteStep", 300));
		TAUNT_CHANCE = Math.max(0, Math.min(100, config.getInt("FakePvpTauntChance", 50)));
		
		REWARD_EXP_SP_MULTIPLIER = Math.max(0, config.getDouble("FakePvpRewardExpSpMultiplier", 1.0));
		REWARD_DROPS = config.getBoolean("FakePvpRewardDrops", true);
	}
	
	private static List<SkillHolder> parseSkills(String value, String key)
	{
		final List<SkillHolder> result = new ArrayList<>();
		for (String entry : value.split(";"))
		{
			entry = entry.trim();
			if (entry.isEmpty())
			{
				continue;
			}
			
			final String[] parts = entry.split(",");
			try
			{
				result.add(new SkillHolder(Integer.parseInt(parts[0].trim()), parts.length > 1 ? Integer.parseInt(parts[1].trim()) : 1));
			}
			catch (NumberFormatException e)
			{
				LOGGER.warning("Invalid " + key + " entry: " + entry);
			}
		}
		
		return result;
	}
}
