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

import java.util.EnumSet;
import java.util.Set;
import java.util.logging.Logger;

import org.l2jmobius.commons.util.ConfigReader;
import org.l2jmobius.gameserver.model.hotzone.HotzoneModifier;

/**
 * Loads the Map Device configuration (config/Custom/MapDevice.ini): maps dropped by high level monsters, opened at the Map Device of a player's hideout ({@code .hideout}) into a fenced instance full of monsters (see {@link org.l2jmobius.gameserver.managers.MapDeviceManager}).
 * @author Mobius
 */
public class MapDeviceConfig
{
	private static final Logger LOGGER = Logger.getLogger(MapDeviceConfig.class.getName());

	private static final String FILE = "./config/Custom/MapDevice.ini";

	public static boolean ENABLED;

	// Hideout.
	public static boolean HIDEOUT_PEACE_ZONE_ONLY;
	public static int HIDEOUT_MIN_LEVEL;
	public static boolean HIDEOUT_ALLOW_PARTY;
	public static int HIDEOUT_EMPTY_MINUTES;
	public static int DEVICE_NPC_ID;
	public static int PORTAL_NPC_ID;
	public static int RETURN_PORTAL_NPC_ID;

	// Opening a map.
	public static int MEDAL_ITEM_ID;
	public static int MAX_MEDALS;
	public static int XP_BONUS_PER_MEDAL;
	public static int DROP_BONUS_PER_MEDAL;
	public static int MONSTER_COUNT;
	public static int MONSTER_TYPES;
	public static int PORTAL_COUNT;
	public static int PORTAL_RADIUS;
	public static int MAP_DURATION;
	public static int COMPLETED_CLOSE_DELAY;
	public static int SAFE_RADIUS;
	public static int MONSTER_SPACING;
	public static boolean RANDOM_PASSIVES;
	public static Set<HotzoneModifier> EXCLUDED_HOTZONE_EFFECTS = EnumSet.noneOf(HotzoneModifier.class);

	// Dying.
	public static int DEATH_RETURN_DELAY;
	public static boolean DEATH_REVIVE_FULL;
	public static boolean DEATH_EXP_LOSS;

	// Map drops.
	public static double DROP_CHANCE;
	public static double DROP_CHANCE_PER_CHAMPION_TIER;
	public static double DROP_CHANCE_RAID;
	public static double DROP_CHANCE_IN_MAP;
	public static int DROP_MAX_LEVEL_DIFFERENCE;
	public static int IN_MAP_TIER_UP_CHANCE;
	public static int IN_MAP_TIER_UP_TWO_CHANCE;
	public static boolean COMPLETION_REWARD;
	public static int GUARDIAN_EXTRA_MAPS;
	public static boolean UNIQUE_MAPS_ENABLED;
	public static int UNIQUE_MAP_MIN_TIER;
	public static double UNIQUE_MAP_COMPLETION_CHANCE;
	public static double UNIQUE_MAP_CHANCE_PER_MEDAL;
	public static double UNIQUE_MAP_RAID_CHANCE;
	public static int UNIQUE_MAP_RAID_LEVEL;
	public static double UNIQUE_MAP_BOSS_CHANCE;

	// The quest.
	public static boolean QUEST_ENABLED;
	public static int QUEST_REWARD_MEDALS;

	public static void load()
	{
		final ConfigReader config = new ConfigReader(FILE);
		ENABLED = config.getBoolean("MapDeviceEnabled", true);

		HIDEOUT_PEACE_ZONE_ONLY = config.getBoolean("HideoutPeaceZoneOnly", true);
		HIDEOUT_MIN_LEVEL = Math.max(1, config.getInt("HideoutMinLevel", 76));
		HIDEOUT_ALLOW_PARTY = config.getBoolean("HideoutAllowParty", true);
		HIDEOUT_EMPTY_MINUTES = Math.max(1, config.getInt("HideoutEmptyMinutes", 10));
		DEVICE_NPC_ID = config.getInt("MapDeviceNpcId", 900700);
		PORTAL_NPC_ID = config.getInt("MapPortalNpcId", 900701);
		RETURN_PORTAL_NPC_ID = config.getInt("MapReturnPortalNpcId", 900702);

		MEDAL_ITEM_ID = config.getInt("MedalItemId", 6393);
		MAX_MEDALS = Math.max(0, Math.min(50, config.getInt("MaxMedals", 8)));
		XP_BONUS_PER_MEDAL = Math.max(0, config.getInt("XpBonusPerMedal", 20));
		DROP_BONUS_PER_MEDAL = Math.max(0, config.getInt("DropBonusPerMedal", 20));
		MONSTER_COUNT = Math.max(1, config.getInt("MonsterCount", 100));
		MONSTER_TYPES = Math.max(1, config.getInt("MonsterTypesPerMap", 6));
		PORTAL_COUNT = Math.max(1, Math.min(16, config.getInt("PortalCount", 8)));
		PORTAL_RADIUS = Math.max(60, config.getInt("PortalRadius", 150));
		MAP_DURATION = Math.max(5, config.getInt("MapDurationMinutes", 60));
		COMPLETED_CLOSE_DELAY = Math.max(10, config.getInt("CompletedMapCloseSeconds", 120));
		SAFE_RADIUS = Math.max(0, config.getInt("EntrySafeRadius", 900));
		MONSTER_SPACING = Math.max(0, config.getInt("MonsterSpacing", 500));
		RANDOM_PASSIVES = config.getBoolean("MapMonsterRandomPassives", true);
		EXCLUDED_HOTZONE_EFFECTS = EnumSet.noneOf(HotzoneModifier.class);
		for (String name : config.getString("ExcludedHotzoneEffects", "BOUNTY_HUNT,RISING_HEAT,MINIBOSS_FRENZY,BLOOD_MOON,CONTESTED_GROUND").split(","))
		{
			final String trimmed = name.trim();
			if (!trimmed.isEmpty())
			{
				try
				{
					EXCLUDED_HOTZONE_EFFECTS.add(HotzoneModifier.valueOf(trimmed));
				}
				catch (IllegalArgumentException e)
				{
					LOGGER.warning("MapDeviceConfig: unknown hotzone effect " + trimmed + " in ExcludedHotzoneEffects.");
				}
			}
		}

		DEATH_RETURN_DELAY = Math.max(1, config.getInt("DeathReturnDelay", 3));
		DEATH_REVIVE_FULL = config.getBoolean("DeathReviveFull", true);
		DEATH_EXP_LOSS = config.getBoolean("DeathExpLoss", false);

		DROP_CHANCE = Math.max(0, config.getDouble("MapDropChance", 1.0));
		DROP_CHANCE_PER_CHAMPION_TIER = Math.max(0, config.getDouble("MapDropChancePerChampionTier", 3.0));
		DROP_CHANCE_RAID = Math.max(0, config.getDouble("MapDropChanceRaid", 50.0));
		DROP_CHANCE_IN_MAP = Math.max(0, config.getDouble("MapDropChanceInMap", 2.5));
		DROP_MAX_LEVEL_DIFFERENCE = config.getInt("MapDropMaxLevelDifference", 9);
		IN_MAP_TIER_UP_CHANCE = Math.max(0, Math.min(100, config.getInt("InMapTierUpChance", 25)));
		IN_MAP_TIER_UP_TWO_CHANCE = Math.max(0, Math.min(100, config.getInt("InMapTierUpTwoChance", 5)));
		COMPLETION_REWARD = config.getBoolean("CompletionMapReward", true);
		GUARDIAN_EXTRA_MAPS = Math.max(0, config.getInt("GuardianExtraMaps", 2));
		UNIQUE_MAPS_ENABLED = config.getBoolean("UniqueMapsEnabled", true);
		UNIQUE_MAP_MIN_TIER = config.getInt("UniqueMapMinTier", 5);
		UNIQUE_MAP_COMPLETION_CHANCE = Math.max(0, config.getDouble("UniqueMapCompletionChance", 2.0));
		UNIQUE_MAP_CHANCE_PER_MEDAL = Math.max(0, config.getDouble("UniqueMapChancePerMedal", 0.5));
		UNIQUE_MAP_RAID_CHANCE = Math.max(0, config.getDouble("UniqueMapRaidChance", 1.0));
		UNIQUE_MAP_RAID_LEVEL = config.getInt("UniqueMapRaidLevel", 80);
		UNIQUE_MAP_BOSS_CHANCE = Math.max(0, config.getDouble("UniqueMapBossChance", 10.0));

		QUEST_ENABLED = config.getBoolean("MapQuestEnabled", true);
		QUEST_REWARD_MEDALS = Math.max(0, config.getInt("MapQuestRewardMedals", 5));
	}
}
