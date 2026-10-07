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
import org.l2jmobius.gameserver.model.actor.enums.npc.MonsterArchetype;
import org.l2jmobius.gameserver.model.hotzone.HotzoneModifier;

/**
 * Loads the Night Cycle configuration (see {@link org.l2jmobius.gameserver.managers.NightCycleManager}): the night's phases, its Omens, the Witching Hour, the Nightlords and the Night Watch.
 */
public class NightCycleConfig
{
	private static final Logger LOGGER = Logger.getLogger(NightCycleConfig.class.getName());

	// File
	private static final String NIGHT_CYCLE_CONFIG_FILE = "./config/Custom/NightCycle.ini";

	// Night Cycle
	public static boolean ENABLED;
	public static int DUSK_MINUTES;
	public static int WITCHING_HOUR_MINUTES;
	public static int DAWN_MINUTES;
	public static boolean ANNOUNCE;
	public static boolean RED_SKY;

	// Night Omens
	public static boolean OMENS_ENABLED;
	public static List<HotzoneModifier> OMEN_POOL;
	public static List<HotzoneModifier> OMEN_RED_SKY;

	// Witching Hour
	public static boolean WITCHING_HOUR_ENABLED;
	public static double WITCHING_HOUR_WAVE_CHANCE;
	public static int WITCHING_HOUR_WAVE_RADIUS;
	public static int WITCHING_HOUR_WAVE_COUNT;
	public static int WITCHING_HOUR_DAWN_BURN_PERCENT;

	// Nightlords
	public static boolean NIGHTLORD_ENABLED;
	public static int NIGHTLORD_SPAWN_DELAY;
	public static int NIGHTLORD_CHAMPION_TIER;
	public static int NIGHTLORD_WAVES;
	public static MonsterArchetype NIGHTLORD_ARCHETYPE;
	public static String NIGHTLORD_TITLE_TAG;

	// Night Watch
	public static boolean NIGHT_WATCH_ENABLED;
	public static int NIGHT_WATCH_KILL_LEVEL_GAP;
	public static int NIGHT_WATCH_KILL_POINTS;
	public static int NIGHT_WATCH_NIGHTLORD_POINTS;
	public static int NIGHT_WATCH_MIN_POINTS;
	public static int[] NIGHT_WATCH_COIN_REWARDS;
	public static int[] NIGHT_WATCH_CACHE_REWARDS;
	public static int NIGHT_WATCH_SURVIVOR_MIN_KILLS;

	// Night Market and Thieves' Night
	public static boolean NIGHT_MARKET_ENABLED;
	public static int NIGHT_MARKET_TOWN_POPULATION;
	public static int NIGHT_MARKET_BLACK_MARKET_STORES;
	public static double NIGHT_THIEF_SPAWN_MULTIPLIER;
	public static boolean NIGHT_THIEVES_FLEE;
	public static int NIGHT_THIEF_RADAR_RANGE;

	// Shadow Raids
	public static boolean NIGHT_RAID_ENABLED;
	public static int NIGHT_RAID_COUNT;
	public static int NIGHT_RAID_SIZE_MIN;
	public static int NIGHT_RAID_SIZE_MAX;
	public static int NIGHT_RAID_DURATION;
	public static int NIGHT_RAID_AGGRO_RANGE;
	public static int NIGHT_RAID_LEVELS_ABOVE;
	public static int NIGHT_RAID_LEVELS_BELOW;
	public static int NIGHT_RAID_KILL_COINS;
	public static int NIGHT_WATCH_RAIDER_POINTS;

	// Moonlit Melee
	public static double NIGHT_PVP_SPOT_FIGHTER_MULTIPLIER;
	public static int NIGHT_KING_COINS;

	// Children of the Night
	public static boolean NIGHT_RACE_TRAITS_ENABLED;
	public static double NIGHT_TRAIT_HUMAN_HP_REGEN;
	public static double NIGHT_TRAIT_HUMAN_ACCURACY;
	public static double NIGHT_TRAIT_ELF_MP_REGEN;
	public static double NIGHT_TRAIT_ELF_CAST_SPEED;
	public static double NIGHT_TRAIT_ORC_PATK;
	public static double NIGHT_TRAIT_DWARF_PDEF;
	public static double NIGHT_TRAIT_DWARF_ACCURACY;
	public static double NIGHT_TRAIT_KAMAEL_SPEED;

	public static void load()
	{
		final ConfigReader config = new ConfigReader(NIGHT_CYCLE_CONFIG_FILE);
		ENABLED = config.getBoolean("NightCycleEnabled", true);
		// Dusk and dawn share the 18 game hours of daylight; the Witching Hour is part of the 6 hours of night.
		DUSK_MINUTES = Math.max(0, Math.min(540, config.getInt("NightCycleDuskMinutes", 60)));
		WITCHING_HOUR_MINUTES = Math.max(0, Math.min(300, config.getInt("NightCycleWitchingHourMinutes", 60)));
		DAWN_MINUTES = Math.max(0, Math.min(540, config.getInt("NightCycleDawnMinutes", 60)));
		ANNOUNCE = config.getBoolean("NightCycleAnnounce", true);
		RED_SKY = config.getBoolean("NightCycleRedSky", true);

		OMENS_ENABLED = config.getBoolean("NightOmensEnabled", true);
		OMEN_POOL = parseModifiers(config.getString("NightOmenPool", "BLOOD_MOON,RESTLESS_DEAD,HORNETS_NEST,COVEN,THIEVES_DEN,METAMORPHOSIS,LUCKY_STARS,SPLITTING_GROUND,CHAMPION_SURGE,NEW_MOON,FULL_MOON,STARFALL"), true);
		OMEN_RED_SKY = parseModifiers(config.getString("NightOmenRedSky", "BLOOD_MOON"), false);

		WITCHING_HOUR_ENABLED = config.getBoolean("WitchingHourEnabled", true);
		WITCHING_HOUR_WAVE_CHANCE = config.getDouble("WitchingHourWaveChance", 3.0);
		WITCHING_HOUR_WAVE_RADIUS = config.getInt("WitchingHourWaveRadius", 1200);
		WITCHING_HOUR_WAVE_COUNT = Math.max(1, config.getInt("WitchingHourWaveCount", 3));
		WITCHING_HOUR_DAWN_BURN_PERCENT = Math.max(0, Math.min(99, config.getInt("WitchingHourDawnBurnPercent", 30)));

		NIGHTLORD_ENABLED = config.getBoolean("NightlordEnabled", true);
		NIGHTLORD_SPAWN_DELAY = Math.max(0, config.getInt("NightlordSpawnDelay", 60));
		NIGHTLORD_CHAMPION_TIER = Math.max(0, Math.min(3, config.getInt("NightlordChampionTier", 3)));
		NIGHTLORD_WAVES = Math.max(1, config.getInt("NightlordWaves", 4));
		final String archetype = config.getString("NightlordArchetype", "RAGER").trim();
		NIGHTLORD_ARCHETYPE = null;
		if (!archetype.isEmpty())
		{
			try
			{
				NIGHTLORD_ARCHETYPE = MonsterArchetype.valueOf(archetype.toUpperCase());
			}
			catch (IllegalArgumentException e)
			{
				LOGGER.warning("NightCycleConfig: Unknown NightlordArchetype " + archetype + ", Nightlords keep their own.");
			}
		}
		NIGHTLORD_TITLE_TAG = config.getString("NightlordTitleTag", "[Nightlord]");

		NIGHT_WATCH_ENABLED = config.getBoolean("NightWatchEnabled", true);
		NIGHT_WATCH_KILL_LEVEL_GAP = config.getInt("NightWatchKillLevelGap", 9);
		NIGHT_WATCH_KILL_POINTS = config.getInt("NightWatchKillPoints", 1);
		NIGHT_WATCH_NIGHTLORD_POINTS = config.getInt("NightWatchNightlordPoints", 100);
		NIGHT_WATCH_MIN_POINTS = config.getInt("NightWatchMinPoints", 50);
		NIGHT_WATCH_COIN_REWARDS = config.getIntArray("NightWatchCoinRewards", ",", "500,250,100");
		NIGHT_WATCH_CACHE_REWARDS = config.getIntArray("NightWatchCacheRewards", ",", "2,1,1");
		NIGHT_WATCH_SURVIVOR_MIN_KILLS = Math.max(0, config.getInt("NightWatchSurvivorMinKills", 20));

		NIGHT_MARKET_ENABLED = config.getBoolean("NightMarketEnabled", true);
		NIGHT_MARKET_TOWN_POPULATION = Math.max(10, Math.min(100, config.getInt("NightMarketTownPopulation", 50)));
		NIGHT_MARKET_BLACK_MARKET_STORES = Math.max(0, config.getInt("NightMarketBlackMarketStores", 2));
		NIGHT_THIEF_SPAWN_MULTIPLIER = Math.max(0, config.getDouble("NightThiefSpawnMultiplier", 2.0));
		NIGHT_THIEVES_FLEE = config.getBoolean("NightThievesFlee", true);
		NIGHT_THIEF_RADAR_RANGE = Math.max(0, config.getInt("NightThiefRadarRange", 3000));

		NIGHT_RAID_ENABLED = config.getBoolean("NightRaidEnabled", true);
		NIGHT_RAID_COUNT = Math.max(0, config.getInt("NightRaidCount", 2));
		NIGHT_RAID_SIZE_MIN = Math.max(1, config.getInt("NightRaidSizeMin", 4));
		NIGHT_RAID_SIZE_MAX = Math.max(NIGHT_RAID_SIZE_MIN, config.getInt("NightRaidSizeMax", 8));
		NIGHT_RAID_DURATION = Math.max(1, config.getInt("NightRaidDuration", 20));
		NIGHT_RAID_AGGRO_RANGE = Math.max(100, config.getInt("NightRaidAggroRange", 900));
		NIGHT_RAID_LEVELS_ABOVE = Math.max(0, config.getInt("NightRaidLevelsAbove", 8));
		NIGHT_RAID_LEVELS_BELOW = Math.max(0, config.getInt("NightRaidLevelsBelow", 10));
		NIGHT_RAID_KILL_COINS = Math.max(0, config.getInt("NightRaidKillCoins", 300));
		NIGHT_WATCH_RAIDER_POINTS = config.getInt("NightWatchRaiderPoints", 20);

		NIGHT_PVP_SPOT_FIGHTER_MULTIPLIER = Math.max(0.1, config.getDouble("NightPvpSpotFighterMultiplier", 1.5));
		NIGHT_KING_COINS = Math.max(0, config.getInt("NightKingCoins", 1000));

		NIGHT_RACE_TRAITS_ENABLED = config.getBoolean("NightRaceTraitsEnabled", true);
		NIGHT_TRAIT_HUMAN_HP_REGEN = config.getDouble("NightTraitHumanHpRegen", 20);
		NIGHT_TRAIT_HUMAN_ACCURACY = config.getDouble("NightTraitHumanAccuracy", 3);
		NIGHT_TRAIT_ELF_MP_REGEN = config.getDouble("NightTraitElfMpRegen", 25);
		NIGHT_TRAIT_ELF_CAST_SPEED = config.getDouble("NightTraitElfCastSpeed", 5);
		NIGHT_TRAIT_ORC_PATK = config.getDouble("NightTraitOrcPAtk", 6);
		NIGHT_TRAIT_DWARF_PDEF = config.getDouble("NightTraitDwarfPDef", 8);
		NIGHT_TRAIT_DWARF_ACCURACY = config.getDouble("NightTraitDwarfAccuracy", 3);
		NIGHT_TRAIT_KAMAEL_SPEED = config.getDouble("NightTraitKamaelSpeed", 8);
	}

	/**
	 * @param value comma separated modifier names
	 * @param omenPool {@code true} to leave out what a night Omen can't do outside a hot zone
	 * @return the modifiers, unknown names left out with a warning
	 */
	private static List<HotzoneModifier> parseModifiers(String value, boolean omenPool)
	{
		final List<HotzoneModifier> modifiers = new ArrayList<>();
		for (String name : value.split(","))
		{
			final String trimmed = name.trim().toUpperCase();
			if (trimmed.isEmpty())
			{
				continue;
			}

			final HotzoneModifier modifier;
			try
			{
				modifier = HotzoneModifier.valueOf(trimmed);
			}
			catch (IllegalArgumentException e)
			{
				LOGGER.warning("NightCycleConfig: Unknown hot zone modifier " + trimmed + ".");
				continue;
			}

			// These need a hot zone: its player buff, its bounty or Heat state, its HP drain, its party count, or its fake player spawns.
			if (omenPool && ((modifier == HotzoneModifier.WITCHING_HOUR) || (modifier.getPlayerBuffSkillId() > 0) || modifier.isBounty() || modifier.isHeat() || (modifier.getPlayerHpDrainPctPerTick() > 0) || modifier.isKinship() || (modifier.getFakePvpSpawnMult() != 1.0)))
			{
				LOGGER.warning("NightCycleConfig: " + trimmed + " can't be a night Omen, left out.");
				continue;
			}

			if (!modifiers.contains(modifier))
			{
				modifiers.add(modifier);
			}
		}
		return Collections.unmodifiableList(modifiers);
	}
}
