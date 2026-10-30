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
import org.l2jmobius.gameserver.model.Location;
import org.l2jmobius.gameserver.model.item.holders.ItemChanceHolder;
import org.l2jmobius.gameserver.model.item.holders.ItemHolder;

/**
 * Loads the Conquest event configuration (see {@link org.l2jmobius.gameserver.managers.conquest.ConquestManager}).
 */
public class ConquestConfig
{
	private static final Logger LOGGER = Logger.getLogger(ConquestConfig.class.getName());

	// File
	private static final String CONQUEST_CONFIG_FILE = "./config/Custom/Conquest.ini";

	public static int START_SCORE;
	public static int PREP_TIME;
	public static int TIME_LIMIT;
	public static int MIN_LEVEL;
	public static int MAX_LEVEL;
	public static int RESPAWN_WAVE;
	public static int SPAWN_PROTECTION;
	public static int BASE_RADIUS;
	public static double CAPTURE_BASE_RATE;
	public static double CAPTURE_RATE_PER_EXTRA;
	public static double CAPTURE_MAX_RATE;
	public static int CAPTURE_MAJORITY_PERCENT;
	public static int CAPTURE_STALEMATE_COUNT;
	public static int CAPTURE_HEIGHT;
	public static int COMMANDER_INTERVAL;
	public static String BLUE_BRAIN;
	public static String RED_BRAIN;
	public static String REGISTRATION_BRAIN;
	public static String JEV_API_KEY;
	public static String JEV_URL;
	public static String JEV_MODEL;
	public static int JEV_INTERVAL;
	public static int JEV_MAX_REQUESTS_PER_SECOND;
	public static int JEV_TIMEOUT;
	public static double JEV_MIN_CONFIDENCE;
	public static boolean JEV_LOG;
	public static int END_DELAY;
	public static int BLUE_NAME_COLOR;
	public static int RED_NAME_COLOR;
	public static boolean REGISTRATION_ENABLED;
	public static String SCHEDULE;
	public static int REGISTRATION_TIME;
	/** The maps a registration may pick (ids of data/conquest), empty for any enabled one. */
	public static List<String> MAPS;
	/** Places per team in a match players registered for, 0 for the map's maxPlayers. */
	public static int PLAYERS_PER_TEAM;
	public static int MIN_PLAYERS;
	/** The level brackets, {lowest, highest}, sorted: the players of each play their own match. */
	public static List<int[]> BRACKETS;
	public static List<Location> MANAGER_LOCATIONS;
	public static List<ItemChanceHolder> KILL_REWARDS;
	public static List<ItemHolder> WIN_REWARD;

	public static void load()
	{
		final ConfigReader config = new ConfigReader(CONQUEST_CONFIG_FILE);
		START_SCORE = Math.max(1, config.getInt("ConquestStartScore", 1000));
		PREP_TIME = Math.max(5, config.getInt("ConquestPrepTime", 30));
		TIME_LIMIT = Math.max(1, config.getInt("ConquestTimeLimit", 30));
		final int[] levels = parseLevels(config.getString("ConquestLevelRange", "80-85"));
		MIN_LEVEL = levels[0];
		MAX_LEVEL = levels[1];
		RESPAWN_WAVE = Math.max(1, config.getInt("ConquestRespawnWave", 20));
		SPAWN_PROTECTION = Math.max(0, config.getInt("ConquestSpawnProtection", 5));
		BASE_RADIUS = Math.max(0, config.getInt("ConquestBaseRadius", 100));
		CAPTURE_BASE_RATE = Math.max(0.1, config.getDouble("ConquestCaptureBaseRate", 5));
		CAPTURE_RATE_PER_EXTRA = Math.max(0, config.getDouble("ConquestCaptureRatePerExtra", 2.5));
		CAPTURE_MAX_RATE = Math.max(CAPTURE_BASE_RATE, config.getDouble("ConquestCaptureMaxRate", 15));
		CAPTURE_MAJORITY_PERCENT = Math.max(0, config.getInt("ConquestCaptureMajorityPercent", 25));
		CAPTURE_STALEMATE_COUNT = Math.max(1, config.getInt("ConquestCaptureStalemateCount", 10));
		CAPTURE_HEIGHT = Math.max(50, config.getInt("ConquestCaptureHeight", 300));
		COMMANDER_INTERVAL = Math.max(1, config.getInt("ConquestCommanderInterval", 3));
		BLUE_BRAIN = config.getString("ConquestBlueBrain", "generic").trim();
		RED_BRAIN = config.getString("ConquestRedBrain", "generic").trim();
		REGISTRATION_BRAIN = config.getString("ConquestRegistrationBrain", "jev").trim();
		final String key = config.getString("ConquestJevApiKey", "").trim();
		final String envKey = System.getenv("TYPESAFE_API_KEY");
		JEV_API_KEY = !key.isEmpty() ? key : envKey != null ? envKey.trim() : "";
		JEV_URL = config.getString("ConquestJevUrl", "https://api.typesafe.ai/v1/systemone").trim();
		JEV_MODEL = config.getString("ConquestJevModel", "jev-latest").trim();
		JEV_INTERVAL = Math.max(1000, config.getInt("ConquestJevInterval", 5000));
		JEV_MAX_REQUESTS_PER_SECOND = Math.max(1, Math.min(40, config.getInt("ConquestJevMaxRequestsPerSecond", 30)));
		JEV_TIMEOUT = Math.max(200, config.getInt("ConquestJevTimeout", 2000));
		JEV_MIN_CONFIDENCE = Math.max(0, Math.min(1, config.getDouble("ConquestJevMinConfidence", 0.2)));
		JEV_LOG = config.getBoolean("ConquestJevLog", true);
		END_DELAY = Math.max(1, config.getInt("ConquestEndDelay", 15));
		BLUE_NAME_COLOR = Integer.decode("0x" + config.getString("ConquestBlueNameColor", "FF9F7F"));
		RED_NAME_COLOR = Integer.decode("0x" + config.getString("ConquestRedNameColor", "5F5FFF"));

		REGISTRATION_ENABLED = config.getBoolean("ConquestRegistrationEnabled", true);
		SCHEDULE = config.getString("ConquestSchedule", "30 */2 * * *");
		REGISTRATION_TIME = Math.max(1, config.getInt("ConquestRegistrationTime", 10));
		final List<String> maps = new ArrayList<>();
		for (String part : config.getString("ConquestMaps", "").split("[,;]"))
		{
			if (!part.isBlank())
			{
				maps.add(part.trim());
			}
		}
		MAPS = Collections.unmodifiableList(maps);
		PLAYERS_PER_TEAM = Math.max(0, config.getInt("ConquestPlayersPerTeam", 0));
		MIN_PLAYERS = Math.max(1, config.getInt("ConquestMinPlayers", 1));
		final List<int[]> brackets = new ArrayList<>();
		for (String part : config.getString("ConquestBrackets", "41-45;46-50;51-55;56-60;61-65;66-70;71-75;76-78;79-81;82-84;85-85").split(";"))
		{
			final String[] range = part.trim().split("-");
			try
			{
				final int min = Integer.parseInt(range[0].trim());
				final int max = range.length > 1 ? Integer.parseInt(range[1].trim()) : min;
				brackets.add(new int[]
				{
					Math.min(min, max),
					Math.max(min, max)
				});
			}
			catch (Exception e)
			{
				if (!part.isBlank())
				{
					LOGGER.warning(ConquestConfig.class.getSimpleName() + ": Bad bracket \"" + part + "\" in ConquestBrackets.");
				}
			}
		}
		brackets.sort((a, b) -> Integer.compare(a[0], b[0]));
		BRACKETS = Collections.unmodifiableList(brackets);
		MANAGER_LOCATIONS = TeamVsTeamConfig.parseLocations(config.getString("ConquestManagerLocations", "83480,148320,-3406,32938"), "ConquestManagerLocations");

		final List<ItemChanceHolder> killRewards = new ArrayList<>();
		for (String part : config.getString("ConquestKillRewards", "6492,1,80;6499,1,17;6509,1,3").split(";"))
		{
			final String[] values = part.trim().split(",");
			try
			{
				killRewards.add(new ItemChanceHolder(Integer.parseInt(values[0].trim()), Double.parseDouble(values[2].trim()), Long.parseLong(values[1].trim())));
			}
			catch (Exception e)
			{
				if (!part.isBlank())
				{
					LOGGER.warning(ConquestConfig.class.getSimpleName() + ": Bad reward \"" + part + "\" in ConquestKillRewards.");
				}
			}
		}
		KILL_REWARDS = Collections.unmodifiableList(killRewards);

		final List<ItemHolder> winReward = new ArrayList<>();
		for (String part : config.getString("ConquestWinReward", "6393,10").split(";"))
		{
			final String[] values = part.trim().split(",");
			try
			{
				winReward.add(new ItemHolder(Integer.parseInt(values[0].trim()), Long.parseLong(values[1].trim())));
			}
			catch (Exception e)
			{
				if (!part.isBlank())
				{
					LOGGER.warning(ConquestConfig.class.getSimpleName() + ": Bad reward \"" + part + "\" in ConquestWinReward.");
				}
			}
		}
		WIN_REWARD = Collections.unmodifiableList(winReward);
	}

	/**
	 * @param text a level range, "40-50" or "85"
	 * @return {min, max}, between 1 and 85; 80-85 when it can't be read
	 */
	public static int[] parseLevels(String text)
	{
		try
		{
			final String[] parts = text.trim().split("-");
			final int a = Integer.parseInt(parts[0].trim());
			final int b = parts.length > 1 ? Integer.parseInt(parts[1].trim()) : a;
			return new int[]
			{
				Math.max(1, Math.min(85, Math.min(a, b))),
				Math.max(1, Math.min(85, Math.max(a, b)))
			};
		}
		catch (Exception e)
		{
			LOGGER.warning(ConquestConfig.class.getSimpleName() + ": Bad level range \"" + text + "\", 80-85 is used.");
			return new int[]
			{
				80,
				85
			};
		}
	}
}
