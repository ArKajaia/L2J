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
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.logging.Logger;

import org.l2jmobius.commons.util.ConfigReader;

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
	public static boolean KEEP_POPULATION;
	
	// Strength
	public static boolean INCLUDE_CP_IN_HP;
	/** {minLevel, minEnchant, maxEnchant} rows sorted by level: the bounds of the enchant of a weapon, and of an armor piece, shield, jewel, shirt or belt. */
	public static List<int[]> WEAPON_ENCHANT = new ArrayList<>();
	public static List<int[]> ARMOR_ENCHANT = new ArrayList<>();
	/** {minLevel, enchantChance, stepChance} rows sorted by level: how players enchant their weapons, armor (shields, shirts and belts too) and jewels (see {@link org.l2jmobius.gameserver.managers.FakePlayerPvpEnchant}). */
	public static List<int[]> WEAPON_ENCHANT_ODDS = new ArrayList<>();
	public static List<int[]> ARMOR_ENCHANT_ODDS = new ArrayList<>();
	public static List<int[]> JEWEL_ENCHANT_ODDS = new ArrayList<>();
	/** {minLevel, chance} rows sorted by level: the chance (in %) a fake player went for a +6 armor set. */
	public static List<int[]> ARMOR_SET_ENCHANT_CHANCE = new ArrayList<>();
	/** The range of the wealth of a fake player, a multiplier of its enchant odds and of its chances to wear a shirt, a belt and a cloak. */
	public static double GEAR_WEALTH_MIN;
	public static double GEAR_WEALTH_MAX;
	/** {minLevel, chance} rows sorted by level: the chance (in %) a fake player wears a shirt, a belt, and a cloak when its armor set opens the cloak slot. */
	public static List<int[]> SHIRT_CHANCE = new ArrayList<>();
	public static List<int[]> BELT_CHANCE = new ArrayList<>();
	public static List<int[]> CLOAK_CHANCE = new ArrayList<>();
	public static int BONUS_STAT_MAX;
	public static boolean PASSIVE_TREE_ENABLED;
	public static int PASSIVE_TREE_MAX_SUBCLASSES;
	public static int PASSIVE_TREE_NODES_PER_SUBCLASS;
	public static int PASSIVE_TREE_SUBCLASS_MIN_LEVEL;
	public static int PASSIVE_TREE_SUBCLASS_CHANCE_MIN;
	public static int PASSIVE_TREE_SUBCLASS_CHANCE_MAX;
	/** From this level a fake player that came to a PvP spot took every subclass with {@link #PASSIVE_TREE_SPOT_SUBCLASS_CHANCE} % chance. */
	public static int PASSIVE_TREE_SPOT_SUBCLASS_LEVEL;
	public static int PASSIVE_TREE_SPOT_SUBCLASS_CHANCE;
	public static int PASSIVE_TREE_VARIANTS;
	/** Points spent at which a fake player heads for its next keystone, one entry per keystone. */
	public static int[] PASSIVE_TREE_KEYSTONE_POINTS;
	public static int PASSIVE_TREE_KEYSTONE_MIN_LEVEL;
	/** Role name -> the keystones (node names, optionally {@code Name@TOKEN} for a gear condition) a fake player of that role may take. */
	public static Map<String, List<String>> PASSIVE_TREE_KEYSTONES;
	public static boolean BUFFS_ENABLED;
	public static int POTION_HP_PERCENT;
	public static int POTION_HEAL_PERCENT;
	public static int POTION_REUSE;
	public static int GEAR_LEVEL_DROP_ABOVE_80;
	public static int GEAR_LEVEL_DROP_ABOVE_51;
	
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
	public static int OUTLEVELED_DIFFERENCE;
	public static boolean ESCAPE_SCROLL;
	public static int BLESSED_ESCAPE_MIN_LEVEL;
	public static int BLESSED_ESCAPE_CHANCE;
	public static int BLESSED_ESCAPE_DISTANCE;
	public static boolean HOTZONE_LEAVE;
	public static int HOTZONE_LEAVE_DELAY_MIN;
	public static int HOTZONE_LEAVE_DELAY_MAX;
	public static int UNSEEN_RANGE;
	public static int DEFENSE_DETECT_CHANCE;
	public static int DEFENSE_KEEP_DISTANCE;
	public static int CORPSE_TIME_MIN;
	public static int CORPSE_TIME_MAX;
	public static int SKILL_CHANCE;
	public static int PVP_SKILL_CHANCE;
	public static int AUTO_ATTACK_SKILL_CHANCE;
	public static int AUTO_ATTACK_PVP_SKILL_CHANCE;
	public static int PVP_DEBUFF_CHANCE;
	public static int PVP_ONLY_REUSE;
	public static int KITE_DISTANCE;
	public static int KITE_STEP;
	public static boolean WEAPON_SWAP_ENABLED;
	public static int WEAPON_SWAP_MIN_LEVEL;
	public static int WEAPON_SWAP_CHASE_TIME;
	public static int WEAPON_SWAP_MELEE_DISTANCE;
	public static int WEAPON_SWAP_INTERVAL;
	public static boolean POLEARM_SWAP_ENABLED;
	public static int POLEARM_SWAP_MIN_LEVEL;
	public static int POLEARM_SWAP_MONSTERS;
	public static int POLEARM_PUT_AWAY_MONSTERS;
	public static int POLEARM_SURROUND_RANGE;
	public static int TAUNT_CHANCE;
	public static int GREET_CHANCE;
	public static int RETURN_CHANCE;
	public static int RETURN_DELAY_MIN;
	public static int RETURN_DELAY_MAX;
	public static int RETURN_REVENGE_TIME;
	
	// PvP taunt
	public static int POKE_CHANCE_MIN;
	public static int POKE_CHANCE_MAX;
	public static int POKE_MAX_CHANCE_LEVEL_DIFF;
	public static boolean POKE_FAKE_PLAYERS;
	public static int POKE_LOITER_MIN;
	public static int POKE_LOITER_MAX;
	public static int REFUSE_CHANCE_MIN;
	public static int REFUSE_CHANCE_MAX;
	public static int REFUSE_MAX_CHANCE_LEVEL_DIFF;
	
	// Fake players among themselves
	public static int MEET_CHANCE;
	public static int MEET_RANGE;
	public static int RIVALRY_CHANCE;
	public static int RIVALRY_MAX_LEVEL_ABOVE;
	public static int FAKE_KILL_STEAL_CHANCE;
	public static int JOIN_FIGHT_CHANCE;
	public static int FAKE_POKE_SCALE;
	public static int FAKE_REFUSE_SCALE;
	
	// Personality
	public static int PERSONALITY_VARIANCE;
	public static int PERSONALITY_RANGE_VARIANCE;
	
	// Rewards
	public static double REWARD_EXP_SP_MULTIPLIER;
	public static boolean REWARD_DROPS;
	public static double EQUIPMENT_DROP_CHANCE;
	
	/** Role name and its default FakePvpKeystones.* list. */
	private static final String[][] DEFAULT_KEYSTONES =
	{
		{
			"FIGHTER",
			"Unending Fury;Relentless Assault;Whirling Steel;Berserk Pact;Bloodletter;Glass Cannon"
		},
		{
			"TANK",
			"Unwavering Stance;Deflection@SHIELD;Arcane Plating;Riposte@SHIELD;Living Fortress;Purity of Flesh"
		},
		{
			"DAGGER",
			"Unwavering Stance;Purity of Flesh;Nocturne;Child of Night"
		},
		{
			"ARCHER",
			"Far Shot@BOW;Relentless Assault;Glass Cannon"
		},
		{
			"MAGE",
			"Chaos Weave;Spell Echo;Arc Conduit;Vampiric Sorcery;Ley Anchor;Unshaken Mind"
		}
	};
	
	public static void load()
	{
		final ConfigReader config = new ConfigReader(FAKE_PLAYER_PVP_CONFIG_FILE);
		ENABLED = config.getBoolean("FakePvpEnabled", true);
		SPAWN_CHANCE = Math.max(0, config.getDouble("FakePvpSpawnChance", 3.0));
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
		KEEP_POPULATION = config.getBoolean("FakePvpKeepPopulation", true);
		
		INCLUDE_CP_IN_HP = config.getBoolean("FakePvpIncludeCpInHp", true);
		WEAPON_ENCHANT = parseEnchantTiers(config.getString("FakePvpWeaponEnchant", "1:0-0;20:0-8;40:0-10;52:0-12;61:0-14;76:0-16"), "FakePvpWeaponEnchant");
		ARMOR_ENCHANT = parseEnchantTiers(config.getString("FakePvpArmorEnchant", "1:0-0;20:0-6;40:0-8;52:0-10;61:0-10;76:0-12"), "FakePvpArmorEnchant");
		WEAPON_ENCHANT_ODDS = parseTiers(config.getString("FakePvpWeaponEnchantOdds", "1:0-0;20:40-35;40:55-40;52:65-45;61:75-50;76:85-55;80:85-60;84:90-65"), "FakePvpWeaponEnchantOdds", 2);
		ARMOR_ENCHANT_ODDS = parseTiers(config.getString("FakePvpArmorEnchantOdds", "1:0-0;20:25-10;40:40-10;52:50-15;61:60-15;76:70-20;80:80-20;84:85-25"), "FakePvpArmorEnchantOdds", 2);
		JEWEL_ENCHANT_ODDS = parseTiers(config.getString("FakePvpJewelEnchantOdds", "1:0-0;20:25-20;40:40-25;52:50-30;61:60-35;76:70-40;80:80-45;84:85-50"), "FakePvpJewelEnchantOdds", 2);
		ARMOR_SET_ENCHANT_CHANCE = parseTiers(config.getString("FakePvpArmorSetEnchantChance", "1:0;61:3;76:8;80:15;84:25"), "FakePvpArmorSetEnchantChance", 1);
		GEAR_WEALTH_MIN = Math.max(0, config.getDouble("FakePvpGearWealthMin", 0.6));
		GEAR_WEALTH_MAX = Math.max(GEAR_WEALTH_MIN, config.getDouble("FakePvpGearWealthMax", 1.4));
		SHIRT_CHANCE = parseTiers(config.getString("FakePvpShirtChance", "1:0;20:10;40:25;52:35;61:50;76:65;80:75;84:80"), "FakePvpShirtChance", 1);
		BELT_CHANCE = parseTiers(config.getString("FakePvpBeltChance", "1:0;40:10;52:25;61:40;76:55;80:70;84:80"), "FakePvpBeltChance", 1);
		CLOAK_CHANCE = parseTiers(config.getString("FakePvpCloakChance", "1:0;80:50;82:60;84:70"), "FakePvpCloakChance", 1);
		BONUS_STAT_MAX = Math.max(0, config.getInt("FakePvpBonusStatMax", 10));
		PASSIVE_TREE_ENABLED = config.getBoolean("FakePvpPassiveTreeEnabled", true);
		PASSIVE_TREE_MAX_SUBCLASSES = Math.max(0, config.getInt("FakePvpPassiveTreeMaxSubclasses", 3));
		PASSIVE_TREE_NODES_PER_SUBCLASS = Math.max(0, config.getInt("FakePvpPassiveTreeNodesPerSubclass", 76));
		PASSIVE_TREE_SUBCLASS_MIN_LEVEL = Math.max(1, config.getInt("FakePvpPassiveTreeSubclassMinLevel", 40));
		PASSIVE_TREE_SUBCLASS_CHANCE_MIN = Math.max(0, Math.min(100, config.getInt("FakePvpPassiveTreeSubclassChanceMin", 10)));
		PASSIVE_TREE_SUBCLASS_CHANCE_MAX = Math.max(0, Math.min(100, config.getInt("FakePvpPassiveTreeSubclassChanceMax", 75)));
		PASSIVE_TREE_SPOT_SUBCLASS_LEVEL = Math.max(1, config.getInt("FakePvpPassiveTreeSpotSubclassLevel", 76));
		PASSIVE_TREE_SPOT_SUBCLASS_CHANCE = Math.max(0, Math.min(100, config.getInt("FakePvpPassiveTreeSpotSubclassChance", 80)));
		PASSIVE_TREE_VARIANTS = Math.max(1, Math.min(64, config.getInt("FakePvpPassiveTreeVariants", 16)));
		final List<Integer> keystonePoints = new ArrayList<>();
		for (String entry : config.getString("FakePvpKeystonePoints", "30,90").split(","))
		{
			entry = entry.trim();
			if (!entry.isEmpty())
			{
				try
				{
					keystonePoints.add(Math.max(0, Integer.parseInt(entry)));
				}
				catch (NumberFormatException e)
				{
					LOGGER.warning("FakePlayerPvpConfig: Invalid FakePvpKeystonePoints entry: " + entry);
				}
			}
		}
		PASSIVE_TREE_KEYSTONE_POINTS = keystonePoints.stream().sorted().mapToInt(Integer::intValue).toArray();
		PASSIVE_TREE_KEYSTONE_MIN_LEVEL = Math.max(1, config.getInt("FakePvpKeystoneMinLevel", 40));
		final Map<String, List<String>> keystones = new HashMap<>();
		for (String[] role : DEFAULT_KEYSTONES)
		{
			final List<String> names = new ArrayList<>();
			for (String name : config.getString("FakePvpKeystones." + role[0], role[1]).split(";"))
			{
				name = name.trim();
				if (!name.isEmpty())
				{
					names.add(name);
				}
			}
			keystones.put(role[0], List.copyOf(names));
		}
		PASSIVE_TREE_KEYSTONES = keystones;
		BUFFS_ENABLED = config.getBoolean("FakePvpBuffsEnabled", true);
		POTION_HP_PERCENT = Math.max(0, Math.min(100, config.getInt("FakePvpPotionHpPercent", 50)));
		POTION_HEAL_PERCENT = Math.max(0, Math.min(100, config.getInt("FakePvpPotionHealPercent", 6)));
		POTION_REUSE = Math.max(1000, config.getInt("FakePvpPotionReuse", 10000));
		GEAR_LEVEL_DROP_ABOVE_80 = Math.max(0, config.getInt("FakePvpGearLevelDropAbove80", 7));
		GEAR_LEVEL_DROP_ABOVE_51 = Math.max(0, config.getInt("FakePvpGearLevelDropAbove51", 15));
		
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
		FLEE_CHANCE = Math.max(0, Math.min(100, config.getInt("FakePvpFleeChance", 20)));
		OUTLEVELED_DIFFERENCE = Math.max(0, config.getInt("FakePvpOutleveledDifference", 8));
		ESCAPE_SCROLL = config.getBoolean("FakePvpEscapeScroll", true);
		BLESSED_ESCAPE_MIN_LEVEL = config.getInt("FakePvpBlessedEscapeMinLevel", 76);
		BLESSED_ESCAPE_CHANCE = Math.max(0, Math.min(100, config.getInt("FakePvpBlessedEscapeChance", 35)));
		BLESSED_ESCAPE_DISTANCE = Math.max(0, config.getInt("FakePvpBlessedEscapeDistance", 400));
		HOTZONE_LEAVE = config.getBoolean("FakePvpHotzoneLeave", true);
		HOTZONE_LEAVE_DELAY_MIN = 60;
		HOTZONE_LEAVE_DELAY_MAX = 600;
		final String leaveDelay = config.getString("FakePvpHotzoneLeaveDelay", "60-600");
		try
		{
			final String[] range = leaveDelay.split("-");
			HOTZONE_LEAVE_DELAY_MIN = Math.max(0, Integer.parseInt(range[0].trim()));
			HOTZONE_LEAVE_DELAY_MAX = Math.max(HOTZONE_LEAVE_DELAY_MIN, Integer.parseInt(range[range.length - 1].trim()));
		}
		catch (Exception e)
		{
			LOGGER.warning("Invalid FakePvpHotzoneLeaveDelay: " + leaveDelay);
		}
		UNSEEN_RANGE = Math.max(0, config.getInt("FakePvpUnseenRange", 2500));
		DEFENSE_DETECT_CHANCE = Math.max(0, Math.min(100, config.getInt("FakePvpDefenseDetectChance", 25)));
		DEFENSE_KEEP_DISTANCE = Math.max(0, config.getInt("FakePvpDefenseKeepDistance", 450));
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
		AUTO_ATTACK_SKILL_CHANCE = Math.max(0, Math.min(100, config.getInt("FakePvpAutoAttackSkillChance", 10)));
		AUTO_ATTACK_PVP_SKILL_CHANCE = Math.max(0, Math.min(100, config.getInt("FakePvpAutoAttackPvpSkillChance", 25)));
		PVP_DEBUFF_CHANCE = Math.max(0, Math.min(100, config.getInt("FakePvpPvpDebuffChance", 35)));
		PVP_ONLY_REUSE = Math.max(0, config.getInt("FakePvpPvpOnlyReuse", 30000));
		KITE_DISTANCE = Math.max(0, config.getInt("FakePvpKiteDistance", 250));
		KITE_STEP = Math.max(50, config.getInt("FakePvpKiteStep", 300));
		WEAPON_SWAP_ENABLED = config.getBoolean("FakePvpWeaponSwapEnabled", true);
		WEAPON_SWAP_MIN_LEVEL = config.getInt("FakePvpWeaponSwapMinLevel", 20);
		WEAPON_SWAP_CHASE_TIME = Math.max(0, config.getInt("FakePvpWeaponSwapChaseTime", 4000));
		WEAPON_SWAP_MELEE_DISTANCE = Math.max(0, config.getInt("FakePvpWeaponSwapMeleeDistance", 150));
		WEAPON_SWAP_INTERVAL = Math.max(500, config.getInt("FakePvpWeaponSwapInterval", 3000));
		POLEARM_SWAP_ENABLED = config.getBoolean("FakePvpPolearmSwapEnabled", true);
		POLEARM_SWAP_MIN_LEVEL = config.getInt("FakePvpPolearmSwapMinLevel", 20);
		POLEARM_SWAP_MONSTERS = Math.max(1, config.getInt("FakePvpPolearmSwapMonsters", 8));
		POLEARM_PUT_AWAY_MONSTERS = Math.max(0, Math.min(POLEARM_SWAP_MONSTERS, config.getInt("FakePvpPolearmPutAwayMonsters", 4)));
		POLEARM_SURROUND_RANGE = Math.max(50, config.getInt("FakePvpPolearmSurroundRange", 300));
		TAUNT_CHANCE = Math.max(0, Math.min(100, config.getInt("FakePvpTauntChance", 50)));
		GREET_CHANCE = Math.max(0, Math.min(100, config.getInt("FakePvpGreetChance", 25)));
		RETURN_CHANCE = Math.max(0, Math.min(100, config.getInt("FakePvpReturnChance", 30)));
		RETURN_DELAY_MIN = 60;
		RETURN_DELAY_MAX = 180;
		final String returnDelay = config.getString("FakePvpReturnDelay", "60-180");
		try
		{
			final String[] range = returnDelay.split("-");
			RETURN_DELAY_MIN = Math.max(1, Integer.parseInt(range[0].trim()));
			RETURN_DELAY_MAX = Math.max(RETURN_DELAY_MIN, Integer.parseInt(range[range.length - 1].trim()));
		}
		catch (Exception e)
		{
			LOGGER.warning("Invalid FakePvpReturnDelay: " + returnDelay);
		}
		RETURN_REVENGE_TIME = Math.max(0, config.getInt("FakePvpReturnRevengeTime", 300));
		
		POKE_CHANCE_MIN = Math.max(0, Math.min(100, config.getInt("FakePvpPokeChanceMin", 5)));
		POKE_CHANCE_MAX = Math.max(0, Math.min(100, config.getInt("FakePvpPokeChanceMax", 20)));
		POKE_MAX_CHANCE_LEVEL_DIFF = Math.max(1, config.getInt("FakePvpPokeMaxChanceLevelDiff", 8));
		POKE_FAKE_PLAYERS = config.getBoolean("FakePvpPokeFakePlayers", true);
		POKE_LOITER_MIN = Math.max(0, config.getInt("FakePvpPokeLoiterMin", 3000));
		POKE_LOITER_MAX = Math.max(POKE_LOITER_MIN, config.getInt("FakePvpPokeLoiterMax", 7000));
		REFUSE_CHANCE_MIN = Math.max(0, Math.min(100, config.getInt("FakePvpRefuseChanceMin", 5)));
		REFUSE_CHANCE_MAX = Math.max(0, Math.min(100, config.getInt("FakePvpRefuseChanceMax", 40)));
		REFUSE_MAX_CHANCE_LEVEL_DIFF = Math.max(1, config.getInt("FakePvpRefuseMaxChanceLevelDiff", 8));
		
		MEET_CHANCE = Math.max(0, Math.min(100, config.getInt("FakePvpMeetChance", 20)));
		MEET_RANGE = Math.max(0, config.getInt("FakePvpMeetRange", 2500));
		RIVALRY_CHANCE = Math.max(0, Math.min(100, config.getInt("FakePvpRivalryChance", 35)));
		RIVALRY_MAX_LEVEL_ABOVE = Math.max(0, config.getInt("FakePvpRivalryMaxLevelAbove", 5));
		FAKE_KILL_STEAL_CHANCE = Math.max(0, Math.min(100, config.getInt("FakePvpFakeKillStealChance", 15)));
		JOIN_FIGHT_CHANCE = Math.max(0, Math.min(100, config.getInt("FakePvpJoinFightChance", 30)));
		FAKE_POKE_SCALE = Math.max(0, config.getInt("FakePvpFakePokeScale", 200));
		FAKE_REFUSE_SCALE = Math.max(0, config.getInt("FakePvpFakeRefuseScale", 50));
		
		PERSONALITY_VARIANCE = Math.max(0, Math.min(100, config.getInt("FakePvpPersonalityVariance", 30)));
		PERSONALITY_RANGE_VARIANCE = Math.max(0, Math.min(90, config.getInt("FakePvpPersonalityRangeVariance", 20)));
		
		REWARD_EXP_SP_MULTIPLIER = Math.max(0, config.getDouble("FakePvpRewardExpSpMultiplier", 1.0));
		REWARD_DROPS = config.getBoolean("FakePvpRewardDrops", true);
		EQUIPMENT_DROP_CHANCE = Math.max(0, Math.min(100, config.getDouble("FakePvpEquipmentDropChance", 0.5)));
	}
	
	/**
	 * @param min the chance (in %) at 1 level of difference
	 * @param max the chance (in %) at {@code maxLevelDiff} levels of difference and more
	 * @param maxLevelDiff the level difference that reaches {@code max}
	 * @param levelDiff the level difference
	 * @return the chance (in %) for {@code levelDiff}, growing linearly from {@code min} to {@code max}, 0 below 1 level
	 */
	public static double levelDiffChance(int min, int max, int maxLevelDiff, int levelDiff)
	{
		if (levelDiff < 1)
		{
			return 0;
		}
		
		if ((levelDiff >= maxLevelDiff) || (maxLevelDiff <= 1))
		{
			return max;
		}
		
		return min + (((max - min) * (levelDiff - 1)) / (double) (maxLevelDiff - 1));
	}
	
	/**
	 * @param tiers {minLevel, ...} rows sorted by level
	 * @param level a fake player level
	 * @return the highest row usable at {@code level}, or {@code null} if there is none
	 */
	public static int[] getTier(List<int[]> tiers, int level)
	{
		int[] result = null;
		for (int[] tier : tiers)
		{
			if (tier[0] > level)
			{
				break;
			}
			result = tier;
		}
		
		return result;
	}
	
	/**
	 * @param tiers {minLevel, chance} rows sorted by level
	 * @param level a fake player level
	 * @return the chance (in %) of the highest row usable at {@code level}, 0 if there is none
	 */
	public static int getTierChance(List<int[]> tiers, int level)
	{
		final int[] tier = getTier(tiers, level);
		return tier != null ? tier[1] : 0;
	}
	
	/**
	 * Parses "minLevel:min-max;minLevel:min-max...".
	 * @param value the config value
	 * @param key the config key, for warnings
	 * @return the rows sorted by level
	 */
	private static List<int[]> parseEnchantTiers(String value, String key)
	{
		final List<int[]> result = parseTiers(value, key, 2);
		for (int[] tier : result)
		{
			tier[2] = Math.max(tier[1], tier[2]);
		}
		
		return result;
	}
	
	/**
	 * Parses "minLevel:a-b;minLevel:a-b..." (or "minLevel:a;..." for one value).
	 * @param value the config value
	 * @param key the config key, for warnings
	 * @param values how many values each row has after its level
	 * @return {minLevel, values...} rows sorted by level, the values at least 0
	 */
	private static List<int[]> parseTiers(String value, String key, int values)
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
				final String[] levelAndValues = entry.split(":");
				final String[] parts = levelAndValues[1].split("-");
				final int[] tier = new int[values + 1];
				tier[0] = Integer.parseInt(levelAndValues[0].trim());
				for (int i = 0; i < values; i++)
				{
					tier[i + 1] = Math.max(0, Integer.parseInt(parts[Math.min(i, parts.length - 1)].trim()));
				}
				result.add(tier);
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
