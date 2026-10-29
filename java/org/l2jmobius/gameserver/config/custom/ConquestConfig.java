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

import java.util.logging.Logger;

import org.l2jmobius.commons.util.ConfigReader;

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
	public static int END_DELAY;
	public static int BLUE_NAME_COLOR;
	public static int RED_NAME_COLOR;

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
		END_DELAY = Math.max(1, config.getInt("ConquestEndDelay", 15));
		BLUE_NAME_COLOR = Integer.decode("0x" + config.getString("ConquestBlueNameColor", "FF9F7F"));
		RED_NAME_COLOR = Integer.decode("0x" + config.getString("ConquestRedNameColor", "5F5FFF"));
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
