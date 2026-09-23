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
package org.l2jmobius.gameserver.config;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

import org.l2jmobius.commons.util.ConfigReader;
import org.l2jmobius.commons.util.StringUtil;
import org.l2jmobius.gameserver.model.actor.enums.npc.DropType;
import org.l2jmobius.gameserver.model.actor.holders.npc.DropHolder;

/**
 * This class loads all the rates related configurations.
 * @author Mobius
 */
public class RatesConfig
{
	private static final Logger LOGGER = Logger.getLogger(RatesConfig.class.getName());
	
	// File
	private static final String RATES_CONFIG_FILE = "./config/Rates.ini";
	
	// Constants
	public static float RATE_XP;
	public static float RATE_SP;
	public static float RATE_PARTY_XP;
	public static float RATE_PARTY_SP;
	public static float RATE_EXTRACTABLE;
	public static int RATE_DROP_MANOR;
	public static float QUEST_ITEM_DROP_AMOUNT_MULTIPLIER;
	public static float RATE_QUEST_REWARD;
	public static float RATE_QUEST_REWARD_XP;
	public static float RATE_QUEST_REWARD_SP;
	public static float RATE_QUEST_REWARD_ADENA;
	public static boolean RATE_QUEST_REWARD_USE_MULTIPLIERS;
	public static float RATE_QUEST_REWARD_POTION;
	public static float RATE_QUEST_REWARD_SCROLL;
	public static float RATE_QUEST_REWARD_RECIPE;
	public static float RATE_QUEST_REWARD_MATERIAL;
	public static int MONSTER_EXP_MAX_LEVEL_DIFFERENCE;
	public static float RATE_HB_TRUST_INCREASE;
	public static float RATE_HB_TRUST_DECREASE;
	public static float RATE_VITALITY_LEVEL_1;
	public static float RATE_VITALITY_LEVEL_2;
	public static float RATE_VITALITY_LEVEL_3;
	public static float RATE_VITALITY_LEVEL_4;
	public static float RATE_RECOVERY_VITALITY_PEACE_ZONE;
	public static float RATE_VITALITY_LOST;
	public static float RATE_VITALITY_GAIN;
	public static float RATE_RECOVERY_ON_RECONNECT;
	public static float RATE_KARMA_LOST;
	public static float RATE_KARMA_EXP_LOST;
	public static float RATE_SIEGE_GUARDS_PRICE;
	public static int PLAYER_DROP_LIMIT;
	public static int PLAYER_RATE_DROP;
	public static int PLAYER_RATE_DROP_ITEM;
	public static int PLAYER_RATE_DROP_EQUIP;
	public static int PLAYER_RATE_DROP_EQUIP_WEAPON;
	public static float PET_XP_RATE;
	public static int PET_FOOD_RATE;
	public static float SINEATER_XP_RATE;
	public static int KARMA_DROP_LIMIT;
	public static int KARMA_RATE_DROP;
	public static int KARMA_RATE_DROP_ITEM;
	public static int KARMA_RATE_DROP_EQUIP;
	public static int KARMA_RATE_DROP_EQUIP_WEAPON;
	public static float RATE_DEATH_DROP_AMOUNT_MULTIPLIER;
	public static float RATE_SPOIL_DROP_AMOUNT_MULTIPLIER;
	public static float RATE_HERB_DROP_AMOUNT_MULTIPLIER;
	public static float RATE_RAID_DROP_AMOUNT_MULTIPLIER;
	public static float RATE_DEATH_DROP_CHANCE_MULTIPLIER;
	public static float RATE_SPOIL_DROP_CHANCE_MULTIPLIER;
	public static float RATE_HERB_DROP_CHANCE_MULTIPLIER;
	public static float RATE_RAID_DROP_CHANCE_MULTIPLIER;
	public static Map<Integer, Float> RATE_DROP_AMOUNT_BY_ID;
	public static Map<Integer, Float> RATE_DROP_CHANCE_BY_ID;
	public static int DROP_MAX_OCCURRENCES_NORMAL;
	public static int DROP_MAX_OCCURRENCES_RAIDBOSS;
	public static int DROP_ADENA_MIN_LEVEL_DIFFERENCE;
	public static int DROP_ADENA_MAX_LEVEL_DIFFERENCE;
	public static double DROP_ADENA_MIN_LEVEL_GAP_CHANCE;
	public static int DROP_ITEM_MIN_LEVEL_DIFFERENCE;
	public static int DROP_ITEM_MAX_LEVEL_DIFFERENCE;
	public static double DROP_ITEM_MIN_LEVEL_GAP_CHANCE;
	public static int EVENT_ITEM_MAX_LEVEL_DIFFERENCE;
	public static boolean BOSS_DROP_ENABLED;
	public static int BOSS_DROP_MIN_LEVEL;
	public static int BOSS_DROP_MAX_LEVEL;
	public static List<DropHolder> BOSS_DROP_LIST = new ArrayList<>();
	
	// Nemesis
	public static boolean NEMESIS_SYSTEM_ENABLED;
	public static int NEMESIS_XP_SP_MULTIPLIER;
	public static boolean RANDOM_PASSIVE_SKILLS_ENABLED;
	public static int RANDOM_PASSIVE_SKILLS_MAX_COUNT;
	public static int RANDOM_PASSIVE_SKILLS_XP_SP_BONUS_PER_SKILL;
	public static int RANDOM_PASSIVE_OVERLOAD_CHANCE;
	public static double RANDOM_PASSIVE_HP_PER_SKILL;
	public static double RANDOM_PASSIVE_DROP_PER_SKILL;
	
	// Arena
	public static boolean ARENA_SYSTEM_ENABLED;
	public static List<Integer> ARENA_CHALLENGER_NPC_IDS = new ArrayList<>();
	public static int ARENA_STAT_GROWTH_PER_WAVE;
	public static int ARENA_OFFENSE_GROWTH_PER_WAVE;
	public static int ARENA_DEFENSE_GROWTH_PER_WAVE;
	public static int ARENA_VIRTUAL_LEVEL_GROWTH_PER_WAVE;
	public static int ARENA_CURRENCY_ITEM_ID;
	public static int ARENA_BASE_CURRENCY_PER_WAVE;
	public static int ARENA_ESCALATION_FACTOR;
	public static int ARENA_MILESTONE_INTERVAL;
	public static int ARENA_MILESTONE_BONUS;
	public static double ARENA_REWARD_MULTIPLIER;
	public static int ARENA_MASTER_NPC_ID;
	public static int ARENA_X;
	public static int ARENA_Y;
	public static int ARENA_Z;
	public static boolean ARENA_CHALLENGER_BUFFS_ENABLED;
	public static List<Integer> ARENA_BUFF_SKILL_IDS;
	public static List<Integer> ARENA_BUFF_SKILL_LEVELS;
	public static int ARENA_ENRAGE_TIME;
	public static int ARENA_WAVE_REMINDER_INTERVAL_SECONDS;
	
	public static void load()
	{
		final ConfigReader config = new ConfigReader(RATES_CONFIG_FILE);
		RATE_XP = config.getFloat("RateXp", 1);
		RATE_SP = config.getFloat("RateSp", 1);
		RATE_PARTY_XP = config.getFloat("RatePartyXp", 1);
		RATE_PARTY_SP = config.getFloat("RatePartySp", 1);
		RATE_EXTRACTABLE = config.getFloat("RateExtractable", 1);
		RATE_DROP_MANOR = config.getInt("RateDropManor", 1);
		QUEST_ITEM_DROP_AMOUNT_MULTIPLIER = config.getFloat("QuestItemDropAmountMultiplier", 1);
		RATE_QUEST_REWARD = config.getFloat("RateQuestReward", 1);
		RATE_QUEST_REWARD_XP = config.getFloat("RateQuestRewardXP", 1);
		RATE_QUEST_REWARD_SP = config.getFloat("RateQuestRewardSP", 1);
		RATE_QUEST_REWARD_ADENA = config.getFloat("RateQuestRewardAdena", 1);
		RATE_QUEST_REWARD_USE_MULTIPLIERS = config.getBoolean("UseQuestRewardMultipliers", false);
		RATE_QUEST_REWARD_POTION = config.getFloat("RateQuestRewardPotion", 1);
		RATE_QUEST_REWARD_SCROLL = config.getFloat("RateQuestRewardScroll", 1);
		RATE_QUEST_REWARD_RECIPE = config.getFloat("RateQuestRewardRecipe", 1);
		RATE_QUEST_REWARD_MATERIAL = config.getFloat("RateQuestRewardMaterial", 1);
		MONSTER_EXP_MAX_LEVEL_DIFFERENCE = config.getInt("MonsterExpMaxLevelDifference", 11);
		RATE_HB_TRUST_INCREASE = config.getFloat("RateHellboundTrustIncrease", 1);
		RATE_HB_TRUST_DECREASE = config.getFloat("RateHellboundTrustDecrease", 1);
		RATE_VITALITY_LEVEL_1 = config.getFloat("RateVitalityLevel1", 1.5f);
		RATE_VITALITY_LEVEL_2 = config.getFloat("RateVitalityLevel2", 2);
		RATE_VITALITY_LEVEL_3 = config.getFloat("RateVitalityLevel3", 2.5f);
		RATE_VITALITY_LEVEL_4 = config.getFloat("RateVitalityLevel4", 3);
		RATE_RECOVERY_VITALITY_PEACE_ZONE = config.getFloat("RateRecoveryPeaceZone", 1);
		RATE_VITALITY_LOST = config.getFloat("RateVitalityLost", 1);
		RATE_VITALITY_GAIN = config.getFloat("RateVitalityGain", 1);
		RATE_RECOVERY_ON_RECONNECT = config.getFloat("RateRecoveryOnReconnect", 4);
		RATE_KARMA_LOST = config.getFloat("RateKarmaLost", -1);
		if (RATE_KARMA_LOST == -1)
		{
			RATE_KARMA_LOST = RATE_XP;
		}
		RATE_KARMA_EXP_LOST = config.getFloat("RateKarmaExpLost", 1);
		RATE_SIEGE_GUARDS_PRICE = config.getFloat("RateSiegeGuardsPrice", 1);
		PLAYER_DROP_LIMIT = config.getInt("PlayerDropLimit", 3);
		PLAYER_RATE_DROP = config.getInt("PlayerRateDrop", 5);
		PLAYER_RATE_DROP_ITEM = config.getInt("PlayerRateDropItem", 70);
		PLAYER_RATE_DROP_EQUIP = config.getInt("PlayerRateDropEquip", 25);
		PLAYER_RATE_DROP_EQUIP_WEAPON = config.getInt("PlayerRateDropEquipWeapon", 5);
		PET_XP_RATE = config.getFloat("PetXpRate", 1);
		PET_FOOD_RATE = config.getInt("PetFoodRate", 1);
		SINEATER_XP_RATE = config.getFloat("SinEaterXpRate", 1);
		KARMA_DROP_LIMIT = config.getInt("KarmaDropLimit", 10);
		KARMA_RATE_DROP = config.getInt("KarmaRateDrop", 70);
		KARMA_RATE_DROP_ITEM = config.getInt("KarmaRateDropItem", 50);
		KARMA_RATE_DROP_EQUIP = config.getInt("KarmaRateDropEquip", 40);
		KARMA_RATE_DROP_EQUIP_WEAPON = config.getInt("KarmaRateDropEquipWeapon", 10);
		RATE_DEATH_DROP_AMOUNT_MULTIPLIER = config.getFloat("DeathDropAmountMultiplier", 1);
		RATE_SPOIL_DROP_AMOUNT_MULTIPLIER = config.getFloat("SpoilDropAmountMultiplier", 1);
		RATE_HERB_DROP_AMOUNT_MULTIPLIER = config.getFloat("HerbDropAmountMultiplier", 1);
		RATE_RAID_DROP_AMOUNT_MULTIPLIER = config.getFloat("RaidDropAmountMultiplier", 1);
		RATE_DEATH_DROP_CHANCE_MULTIPLIER = config.getFloat("DeathDropChanceMultiplier", 1);
		RATE_SPOIL_DROP_CHANCE_MULTIPLIER = config.getFloat("SpoilDropChanceMultiplier", 1);
		RATE_HERB_DROP_CHANCE_MULTIPLIER = config.getFloat("HerbDropChanceMultiplier", 1);
		RATE_RAID_DROP_CHANCE_MULTIPLIER = config.getFloat("RaidDropChanceMultiplier", 1);
		final String[] dropAmountMultiplier = config.getString("DropAmountMultiplierByItemId", "").split(";");
		RATE_DROP_AMOUNT_BY_ID = new HashMap<>(dropAmountMultiplier.length);
		if (!dropAmountMultiplier[0].isEmpty())
		{
			for (String item : dropAmountMultiplier)
			{
				final String[] itemSplit = item.split(",");
				if (itemSplit.length != 2)
				{
					LOGGER.warning(StringUtil.concat("Config.load(): invalid config property -> DropAmountMultiplierByItemId \"", item, "\""));
				}
				else
				{
					try
					{
						RATE_DROP_AMOUNT_BY_ID.put(Integer.parseInt(itemSplit[0]), Float.parseFloat(itemSplit[1]));
					}
					catch (NumberFormatException nfe)
					{
						if (!item.isEmpty())
						{
							LOGGER.warning(StringUtil.concat("Config.load(): invalid config property -> DropAmountMultiplierByItemId \"", item, "\""));
						}
					}
				}
			}
		}
		final String[] dropChanceMultiplier = config.getString("DropChanceMultiplierByItemId", "").split(";");
		RATE_DROP_CHANCE_BY_ID = new HashMap<>(dropChanceMultiplier.length);
		if (!dropChanceMultiplier[0].isEmpty())
		{
			for (String item : dropChanceMultiplier)
			{
				final String[] itemSplit = item.split(",");
				if (itemSplit.length != 2)
				{
					LOGGER.warning(StringUtil.concat("Config.load(): invalid config property -> DropChanceMultiplierByItemId \"", item, "\""));
				}
				else
				{
					try
					{
						RATE_DROP_CHANCE_BY_ID.put(Integer.parseInt(itemSplit[0]), Float.parseFloat(itemSplit[1]));
					}
					catch (NumberFormatException nfe)
					{
						if (!item.isEmpty())
						{
							LOGGER.warning(StringUtil.concat("Config.load(): invalid config property -> DropChanceMultiplierByItemId \"", item, "\""));
						}
					}
				}
			}
		}
		DROP_MAX_OCCURRENCES_NORMAL = config.getInt("DropMaxOccurrencesNormal", 2);
		DROP_MAX_OCCURRENCES_RAIDBOSS = config.getInt("DropMaxOccurrencesRaidboss", 7);
		DROP_ADENA_MIN_LEVEL_DIFFERENCE = config.getInt("DropAdenaMinLevelDifference", 8);
		DROP_ADENA_MAX_LEVEL_DIFFERENCE = config.getInt("DropAdenaMaxLevelDifference", 15);
		DROP_ADENA_MIN_LEVEL_GAP_CHANCE = config.getDouble("DropAdenaMinLevelGapChance", 10);
		DROP_ITEM_MIN_LEVEL_DIFFERENCE = config.getInt("DropItemMinLevelDifference", 5);
		DROP_ITEM_MAX_LEVEL_DIFFERENCE = config.getInt("DropItemMaxLevelDifference", 10);
		DROP_ITEM_MIN_LEVEL_GAP_CHANCE = config.getDouble("DropItemMinLevelGapChance", 10);
		EVENT_ITEM_MAX_LEVEL_DIFFERENCE = config.getInt("EventItemMaxLevelDifference", 9);
		BOSS_DROP_ENABLED = config.getBoolean("BossDropEnable", false);
		BOSS_DROP_MIN_LEVEL = config.getInt("BossDropMinLevel", 40);
		BOSS_DROP_MAX_LEVEL = config.getInt("BossDropMaxLevel", 999);
		BOSS_DROP_LIST.clear();
		for (String s : config.getString("BossDropList", "").trim().split(";"))
		{
			if (s.isEmpty())
			{
				continue;
			}
			BOSS_DROP_LIST.add(new DropHolder(DropType.DROP, Integer.parseInt(s.split(",")[0]), Integer.parseInt(s.split(",")[1]), Integer.parseInt(s.split(",")[2]), (Double.parseDouble(s.split(",")[3]))));
		}
		NEMESIS_SYSTEM_ENABLED = config.getBoolean("NemesisSystemEnabled", false);
		NEMESIS_XP_SP_MULTIPLIER = config.getInt("NemesisXpSpMultiplier", 10);
		RANDOM_PASSIVE_SKILLS_ENABLED = config.getBoolean("RandomPassiveSkillsEnabled", false);
		RANDOM_PASSIVE_SKILLS_MAX_COUNT = config.getInt("RandomPassiveSkillsMaxCount", 10);
		RANDOM_PASSIVE_SKILLS_XP_SP_BONUS_PER_SKILL = config.getInt("RandomPassiveSkillsXpSpBonusPerSkill", 15);
		RANDOM_PASSIVE_OVERLOAD_CHANCE = config.getInt("RandomPassiveOverloadChance", 1);
		RANDOM_PASSIVE_HP_PER_SKILL = config.getInt("hpPerPassiveMultiplier", 0);
		RANDOM_PASSIVE_DROP_PER_SKILL = config.getInt("dropChancePerPassiveMultiplier", 0);
		
		// <Arena Master>
		ARENA_SYSTEM_ENABLED = config.getBoolean("ArenaSystemEnabled", false);
		
		// Parse the comma-separated string into a List<Integer>
		ARENA_CHALLENGER_NPC_IDS.clear();
		String[] ids = config.getString("ArenaChallengerNpcIds", "").split(",");
		for (String idStr : ids)
		{
			idStr = idStr.trim();
			if (!idStr.isEmpty())
			{
				try
				{
					ARENA_CHALLENGER_NPC_IDS.add(Integer.parseInt(idStr));
				}
				catch (NumberFormatException e)
				{
					LOGGER.warning("Invalid ArenaChallengerNpcId entry: " + idStr);
				}
			}
		}
		ARENA_STAT_GROWTH_PER_WAVE = config.getInt("ArenaStatGrowthPerWave", 8);
		ARENA_OFFENSE_GROWTH_PER_WAVE = config.getInt("ArenaOffenseGrowthPerWave", 13);
		ARENA_DEFENSE_GROWTH_PER_WAVE = config.getInt("ArenaDefenseGrowthPerWave", 7);
		ARENA_VIRTUAL_LEVEL_GROWTH_PER_WAVE = config.getInt("ArenaVirtualLevelGrowthPerWave", 2);
		ARENA_CURRENCY_ITEM_ID = config.getInt("ArenaCurrencyItemId", 900002);
		ARENA_BASE_CURRENCY_PER_WAVE = config.getInt("ArenaBaseCurrencyPerWave", 50);
		ARENA_ESCALATION_FACTOR = config.getInt("ArenaEscalationFactor", 15);
		ARENA_MILESTONE_INTERVAL = config.getInt("ArenaMilestoneInterval", 5);
		ARENA_MILESTONE_BONUS = config.getInt("ArenaMilestoneBonus", 500);
		ARENA_REWARD_MULTIPLIER = config.getInt("ArenaRewardBonusPercent", 20) / 100.0 + 1.0;
		ARENA_MASTER_NPC_ID = config.getInt("ArenaMasterNpcId", 900000);
		ARENA_X = config.getInt("ArenaX", 0);
		ARENA_Y = config.getInt("ArenaY", 0);
		ARENA_Z = config.getInt("ArenaZ", 0);
		ARENA_CHALLENGER_BUFFS_ENABLED = config.getBoolean("ArenaChallengerBuffsEnabled", true);
		
		ARENA_BUFF_SKILL_IDS = new ArrayList<>();
		for (String id : config.getString("ArenaBuffSkillIds", "").split(","))
		{
			if (!id.isBlank())
			{
				ARENA_BUFF_SKILL_IDS.add(Integer.parseInt(id.trim()));
			}
		}
		
		ARENA_BUFF_SKILL_LEVELS = new ArrayList<>();
		for (String lvl : config.getString("ArenaBuffSkillLevels", "").split(","))
		{
			if (!lvl.isBlank())
			{
				ARENA_BUFF_SKILL_LEVELS.add(Integer.parseInt(lvl.trim()));
			}
		}
		ARENA_ENRAGE_TIME = config.getInt("ArenaEnrageTime", 120);
		ARENA_WAVE_REMINDER_INTERVAL_SECONDS = config.getInt("ArenaWaveReminderIntervalSeconds", 30);
		// </Arena Master>
	}
}
