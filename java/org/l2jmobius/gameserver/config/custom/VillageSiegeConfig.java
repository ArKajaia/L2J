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
 * Village Under Siege (see {@code VillageSiegeManager}): one starting village after the other, monsters of the hunting grounds around it attack its entrances.
 */
public class VillageSiegeConfig
{
	private static final Logger LOGGER = Logger.getLogger(VillageSiegeConfig.class.getName());

	// File
	private static final String VILLAGE_SIEGE_CONFIG_FILE = "./config/Custom/VillageSiege.ini";

	// Schedule
	public static boolean ENABLED;
	public static List<String> ORDER;
	public static int FIRST_START_HOUR;
	public static int FIRST_START_MINUTE;
	public static int DURATION;
	public static int BREAK;
	public static int WARNING;
	public static int LATE_START_MIN_MINUTES;
	public static boolean ANNOUNCE;
	public static int ANNOUNCE_MAX_LEVEL;
	public static int STATUS_ANNOUNCE_MINUTES;
	public static boolean INVITE;
	public static boolean TELEPORT;

	// Who takes part
	public static int MAX_PLAYER_LEVEL;
	public static int GREY_LEVEL_DIFFERENCE;
	public static int AREA_RANGE;
	public static boolean SCALE_TO_DEFENDERS;
	public static int SCALE_LEVELS_BELOW;
	public static int SCALE_LEVELS_ABOVE;

	// Entrances
	public static int MAX_ENTRANCES;
	public static int ENTRANCE_SPACING;
	public static int SOURCE_RANGE;
	public static int RALLY_DISTANCE;
	public static int GUARD_LINE_DISTANCE;

	// Waves
	public static int[] PRESSURE;
	public static double PRESSURE_PER_DEFENDER;
	public static int PRESSURE_MAX;
	public static int REINFORCE_INTERVAL;
	public static int WAVE_INTERVAL_MIN;
	public static int WAVE_INTERVAL_MAX;
	public static int WAVE_SIZE;
	public static int WAVE_SIZE_PER_DEFENDERS;
	public static int WAVE_SIZE_MAX;
	public static int MAX_MONSTERS;
	public static int CHAMPION_CHANCE;
	public static int LEADER_INTERVAL;
	public static int LORD_MINUTES;
	public static int LEVEL_WINDOW;

	// Gates
	public static int GATE_HP;
	public static int GATE_HIT_DAMAGE;
	public static int GATE_REPAIR;
	public static int GATE_REBUILD_MINUTES;
	public static int GATE_REBUILD_PERCENT;

	// Guards
	public static int GUARD_LEVEL_OFFSET;
	public static int GUARDS_PER_ENTRANCE;
	public static double GUARD_HP_MULTIPLIER;
	public static double GUARD_ATTACK_MULTIPLIER;
	public static double GUARD_DEFENCE_MULTIPLIER;
	public static int GUARD_AGGRO_RANGE;
	public static int GUARD_RESPAWN;
	public static int RETAIL_GUARD_RANGE;

	// Fake defenders
	public static boolean FAKE_DEFENDERS;
	public static int FAKE_DEFENDERS_ALONE;
	public static int FAKE_DEFENDERS_CROWDED;
	public static int FAKE_DEFENDERS_CROWD;
	public static int FAKE_STAY_MIN;
	public static int FAKE_STAY_MAX;
	public static int FAKE_ARRIVAL_MIN;
	public static int FAKE_ARRIVAL_MAX;

	// Contribution
	public static int KILL_POINTS;
	public static int LEADER_POINTS;
	public static int LORD_POINTS;
	public static int HEAL_POINTS;
	public static int ACTIVE_POINTS;
	public static double MEDAL_RATE;
	public static int EXPECTED_POINTS_PER_HOUR;
	public static int ADENA_PER_MEDAL;
	public static int END_BONUS_PERCENT;
	public static int END_BONUS_MINUTES;
	public static boolean ONE_PER_COMPUTER;

	// Rewards
	public static int EXP_BONUS;
	public static int REWARD_EXP_PERCENT;
	public static int REWARD_EXP_FULL_LEVEL;
	public static int REWARD_EXP_HALVING_LEVELS;
	public static int REWARD_FULL_MINUTES;
	public static int REWARD_MIN_MINUTES;
	public static int MEDAL_ITEM_ID;
	public static int RANKING_SIZE;
	public static int ANNOUNCE_TOP;

	public static void load()
	{
		final ConfigReader config = new ConfigReader(VILLAGE_SIEGE_CONFIG_FILE);
		ENABLED = config.getBoolean("VillageSiegeEnabled", true);
		final List<String> order = new ArrayList<>();
		for (String village : config.getString("VillageSiegeOrder", "talking_island,rune,dion,aden,dark_elven,goddard,gludin,giran,orc,oren,gludio,hunters,kamael,rune,dion,aden,elven,schuttgart,gludin,giran,dwarven,heine,gludio,hunters").split(","))
		{
			if (!village.trim().isEmpty())
			{
				order.add(village.trim().toLowerCase());
			}
		}
		ORDER = Collections.unmodifiableList(order);
		final String firstStart = config.getString("VillageSiegeFirstStart", "07:00");
		try
		{
			final String[] time = firstStart.split(":");
			FIRST_START_HOUR = Math.max(0, Math.min(23, Integer.parseInt(time[0].trim())));
			FIRST_START_MINUTE = Math.max(0, Math.min(59, Integer.parseInt(time[1].trim())));
		}
		catch (Exception e)
		{
			LOGGER.warning("Invalid VillageSiegeFirstStart: " + firstStart);
			FIRST_START_HOUR = 7;
			FIRST_START_MINUTE = 0;
		}
		DURATION = Math.max(10, config.getInt("VillageSiegeDuration", 90));
		BREAK = Math.max(1, config.getInt("VillageSiegeBreak", 20));
		WARNING = Math.max(0, config.getInt("VillageSiegeWarning", 10));
		LATE_START_MIN_MINUTES = Math.max(1, config.getInt("VillageSiegeLateStartMinMinutes", 30));
		ANNOUNCE = config.getBoolean("VillageSiegeAnnounce", true);
		ANNOUNCE_MAX_LEVEL = Math.max(1, config.getInt("VillageSiegeAnnounceMaxLevel", 40));
		STATUS_ANNOUNCE_MINUTES = Math.max(0, config.getInt("VillageSiegeStatusAnnounceMinutes", 15));
		INVITE = config.getBoolean("VillageSiegeInvite", true);
		TELEPORT = config.getBoolean("VillageSiegeTeleport", true);

		MAX_PLAYER_LEVEL = Math.max(1, config.getInt("VillageSiegeMaxPlayerLevel", 39));
		GREY_LEVEL_DIFFERENCE = Math.max(1, config.getInt("VillageSiegeGreyLevelDifference", 10));
		AREA_RANGE = Math.max(500, config.getInt("VillageSiegeAreaRange", 2000));
		SCALE_TO_DEFENDERS = config.getBoolean("VillageSiegeScaleToDefenders", true);
		SCALE_LEVELS_BELOW = Math.max(0, config.getInt("VillageSiegeScaleLevelsBelow", 3));
		SCALE_LEVELS_ABOVE = Math.max(0, config.getInt("VillageSiegeScaleLevelsAbove", 1));

		MAX_ENTRANCES = Math.max(1, config.getInt("VillageSiegeMaxEntrances", 3));
		ENTRANCE_SPACING = Math.max(300, config.getInt("VillageSiegeEntranceSpacing", 1200));
		SOURCE_RANGE = Math.max(2000, config.getInt("VillageSiegeSourceRange", 14000));
		RALLY_DISTANCE = Math.max(300, config.getInt("VillageSiegeRallyDistance", 1100));
		GUARD_LINE_DISTANCE = Math.max(60, config.getInt("VillageSiegeGuardLineDistance", 120));

		PRESSURE = parseStages(config, "VillageSiegePressure", "10,16,24");
		PRESSURE_PER_DEFENDER = Math.max(0, config.getDouble("VillageSiegePressurePerDefender", 1.0));
		PRESSURE_MAX = Math.max(1, config.getInt("VillageSiegePressureMax", 45));
		REINFORCE_INTERVAL = Math.max(1, config.getInt("VillageSiegeReinforceInterval", 5));
		final int[] waveInterval = parseRange(config, "VillageSiegeWaveInterval", "90-150", 10);
		WAVE_INTERVAL_MIN = waveInterval[0];
		WAVE_INTERVAL_MAX = waveInterval[1];
		WAVE_SIZE = Math.max(1, config.getInt("VillageSiegeWaveSize", 10));
		WAVE_SIZE_PER_DEFENDERS = Math.max(0, config.getInt("VillageSiegeWaveSizePerDefenders", 2));
		WAVE_SIZE_MAX = Math.max(WAVE_SIZE, config.getInt("VillageSiegeWaveSizeMax", 30));
		MAX_MONSTERS = Math.max(1, config.getInt("VillageSiegeMaxMonsters", 150));
		CHAMPION_CHANCE = Math.max(0, Math.min(100, config.getInt("VillageSiegeChampionChance", 10)));
		LEADER_INTERVAL = Math.max(0, config.getInt("VillageSiegeLeaderInterval", 20));
		LORD_MINUTES = Math.max(0, config.getInt("VillageSiegeLordMinutes", 10));
		LEVEL_WINDOW = Math.max(1, config.getInt("VillageSiegeLevelWindow", 6));

		GATE_HP = Math.max(100, config.getInt("VillageSiegeGateHp", 15000));
		GATE_HIT_DAMAGE = Math.max(1, config.getInt("VillageSiegeGateHitDamage", 10));
		GATE_REPAIR = Math.max(0, config.getInt("VillageSiegeGateRepair", 5));
		GATE_REBUILD_MINUTES = Math.max(0, config.getInt("VillageSiegeGateRebuildMinutes", 5));
		GATE_REBUILD_PERCENT = Math.max(1, Math.min(100, config.getInt("VillageSiegeGateRebuildPercent", 30)));

		GUARD_LEVEL_OFFSET = config.getInt("VillageSiegeGuardLevelOffset", 4);
		GUARDS_PER_ENTRANCE = Math.max(0, config.getInt("VillageSiegeGuardsPerEntrance", 4));
		GUARD_HP_MULTIPLIER = Math.max(0.1, config.getDouble("VillageSiegeGuardHpMultiplier", 4.0));
		GUARD_ATTACK_MULTIPLIER = Math.max(0.1, config.getDouble("VillageSiegeGuardAttackMultiplier", 0.6));
		GUARD_DEFENCE_MULTIPLIER = Math.max(0.1, config.getDouble("VillageSiegeGuardDefenceMultiplier", 1.5));
		GUARD_AGGRO_RANGE = Math.max(100, config.getInt("VillageSiegeGuardAggroRange", 350));
		GUARD_RESPAWN = Math.max(10, config.getInt("VillageSiegeGuardRespawn", 150));
		RETAIL_GUARD_RANGE = Math.max(0, config.getInt("VillageSiegeRetailGuardRange", 1500));

		FAKE_DEFENDERS = config.getBoolean("VillageSiegeFakeDefenders", true);
		FAKE_DEFENDERS_ALONE = Math.max(0, config.getInt("VillageSiegeFakeDefendersAlone", 24));
		FAKE_DEFENDERS_CROWDED = Math.max(0, Math.min(FAKE_DEFENDERS_ALONE, config.getInt("VillageSiegeFakeDefendersCrowded", 12)));
		FAKE_DEFENDERS_CROWD = Math.max(1, config.getInt("VillageSiegeFakeDefendersCrowd", 20));
		final int[] stay = parseRange(config, "VillageSiegeFakeDefenderStay", "40-90", 1);
		FAKE_STAY_MIN = stay[0];
		FAKE_STAY_MAX = stay[1];
		final int[] arrival = parseRange(config, "VillageSiegeFakeDefenderArrival", "8-25", 1);
		FAKE_ARRIVAL_MIN = arrival[0];
		FAKE_ARRIVAL_MAX = arrival[1];

		KILL_POINTS = Math.max(0, config.getInt("VillageSiegeKillPoints", 10));
		LEADER_POINTS = Math.max(0, config.getInt("VillageSiegeLeaderPoints", 100));
		LORD_POINTS = Math.max(0, config.getInt("VillageSiegeLordPoints", 300));
		HEAL_POINTS = Math.max(0, config.getInt("VillageSiegeHealPoints", 10));
		ACTIVE_POINTS = Math.max(0, config.getInt("VillageSiegeActivePoints", 5));
		MEDAL_RATE = Math.max(0, config.getDouble("VillageSiegeMedalRate", 0.025));
		EXPECTED_POINTS_PER_HOUR = Math.max(1, config.getInt("VillageSiegeExpectedPointsPerHour", 3000));
		ADENA_PER_MEDAL = Math.max(1, config.getInt("VillageSiegeAdenaPerMedal", 1000));
		END_BONUS_PERCENT = Math.max(0, config.getInt("VillageSiegeEndBonusPercent", 30));
		END_BONUS_MINUTES = Math.max(1, config.getInt("VillageSiegeEndBonusMinutes", 15));
		ONE_PER_COMPUTER = config.getBoolean("VillageSiegeOnePerComputer", true);

		EXP_BONUS = Math.max(0, config.getInt("VillageSiegeExpBonus", 50));
		REWARD_EXP_PERCENT = Math.max(0, config.getInt("VillageSiegeRewardExpPercent", 30));
		REWARD_EXP_FULL_LEVEL = Math.max(1, config.getInt("VillageSiegeRewardExpFullLevel", 40));
		REWARD_EXP_HALVING_LEVELS = Math.max(0, config.getInt("VillageSiegeRewardExpHalvingLevels", 10));
		REWARD_FULL_MINUTES = Math.max(1, config.getInt("VillageSiegeRewardFullMinutes", 30));
		REWARD_MIN_MINUTES = Math.max(0, config.getInt("VillageSiegeRewardMinMinutes", 5));
		MEDAL_ITEM_ID = config.getInt("VillageSiegeMedalItemId", 4674);
		RANKING_SIZE = Math.max(3, config.getInt("VillageSiegeRankingSize", 20));
		ANNOUNCE_TOP = Math.max(0, config.getInt("VillageSiegeAnnounceTop", 3));
	}

	/**
	 * @return one value for each of the three stages of a siege (one value for all three)
	 */
	private static int[] parseStages(ConfigReader config, String key, String defaultValue)
	{
		final String value = config.getString(key, defaultValue);
		try
		{
			return toStages(value);
		}
		catch (Exception e)
		{
			LOGGER.warning("Invalid " + key + ": " + value);
			return toStages(defaultValue);
		}
	}

	private static int[] toStages(String value)
	{
		final String[] parts = value.split(",");
		final int[] stages = new int[3];
		for (int i = 0; i < stages.length; i++)
		{
			stages[i] = Math.max(0, Integer.parseInt(parts[Math.min(i, parts.length - 1)].trim()));
		}
		return stages;
	}

	private static int[] parseRange(ConfigReader config, String key, String defaultValue, int lowest)
	{
		final String value = config.getString(key, defaultValue);
		try
		{
			final String[] range = value.split("-");
			final int min = Math.max(lowest, Integer.parseInt(range[0].trim()));
			return new int[]
			{
				min,
				Math.max(min, Integer.parseInt(range[range.length - 1].trim()))
			};
		}
		catch (Exception e)
		{
			LOGGER.warning("Invalid " + key + ": " + value);
			final String[] range = defaultValue.split("-");
			return new int[]
			{
				Integer.parseInt(range[0]),
				Integer.parseInt(range[1])
			};
		}
	}
}
