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
import org.l2jmobius.gameserver.model.skill.AbnormalVisualEffect;

/**
 * Loads the Hidden Quests configuration: secret conditions that send a messenger NPC to the player with a one-off quest. The quests themselves live in {@code data/HiddenQuests.xml}.
 * @author Mobius
 */
public class HiddenQuestConfig
{
	private static final Logger LOGGER = Logger.getLogger(HiddenQuestConfig.class.getName());

	// File
	private static final String HIDDEN_QUESTS_CONFIG_FILE = "./config/Custom/HiddenQuests.ini";

	// Constants
	public static boolean ENABLED;
	public static int MIN_LEVEL;
	public static int CHECK_INTERVAL;
	public static int MESSENGER_DELAY_MIN;
	public static int MESSENGER_DELAY_MAX;
	public static int MESSENGER_WAIT_TIME;
	public static int MESSENGER_RETRY_DELAY;
	public static int FAIL_RETRY_DELAY;
	public static int TASK_TIME_LIMIT;
	public static int KILL_LEVEL_GAP;
	public static AbnormalVisualEffect MESSENGER_VISUAL_EFFECT;
	public static boolean ALLOW_FORFEIT;
	public static boolean LOGGING;

	public static void load()
	{
		final ConfigReader config = new ConfigReader(HIDDEN_QUESTS_CONFIG_FILE);
		ENABLED = config.getBoolean("EnableHiddenQuests", true);
		MIN_LEVEL = Math.max(1, config.getInt("HiddenQuestMinLevel", 20));
		CHECK_INTERVAL = Math.max(5, config.getInt("HiddenQuestCheckInterval", 15));
		MESSENGER_DELAY_MIN = Math.max(0, config.getInt("HiddenQuestMessengerDelayMin", 60));
		MESSENGER_DELAY_MAX = Math.max(MESSENGER_DELAY_MIN, config.getInt("HiddenQuestMessengerDelayMax", 300));
		MESSENGER_WAIT_TIME = Math.max(30, config.getInt("HiddenQuestMessengerWaitTime", 180));
		MESSENGER_RETRY_DELAY = Math.max(60, config.getInt("HiddenQuestMessengerRetryDelay", 1800));
		FAIL_RETRY_DELAY = Math.max(60, config.getInt("HiddenQuestFailRetryDelay", 1800));
		TASK_TIME_LIMIT = Math.max(300, config.getInt("HiddenQuestTaskTimeLimit", 2700));
		KILL_LEVEL_GAP = Math.max(0, config.getInt("HiddenQuestKillLevelGap", 9));
		ALLOW_FORFEIT = config.getBoolean("HiddenQuestAllowForfeit", true);
		LOGGING = config.getBoolean("HiddenQuestLogging", true);

		final String effect = config.getString("HiddenQuestMessengerVisualEffect", "REAL_TARGET").trim().toUpperCase();
		try
		{
			MESSENGER_VISUAL_EFFECT = AbnormalVisualEffect.valueOf(effect);
		}
		catch (IllegalArgumentException e)
		{
			LOGGER.warning(HiddenQuestConfig.class.getSimpleName() + ": Unknown HiddenQuestMessengerVisualEffect " + effect + ", using NONE.");
			MESSENGER_VISUAL_EFFECT = AbnormalVisualEffect.NONE;
		}
	}
}
