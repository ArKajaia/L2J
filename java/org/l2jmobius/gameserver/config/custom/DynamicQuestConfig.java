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

import org.l2jmobius.commons.util.ConfigReader;

/**
 * Dynamic Quests (config/Custom/DynamicQuests.ini).
 */
public class DynamicQuestConfig
{
	// File
	private static final String DYNAMIC_QUESTS_CONFIG_FILE = "./config/Custom/DynamicQuests.ini";

	// Constants
	public static boolean ENABLED;
	public static int MIN_LEVEL;
	public static int BOARD_RESET_HOUR;
	public static int BOARD_OFFERS;
	public static int REQUEST_OFFERS;
	public static int MAX_ACTIVE;
	public static int ACCOUNT_DAILY_LIMIT;
	public static int REPEAT_PENALTY_PERCENT;
	public static int MIN_REPEAT_PERCENT;
	public static int MAX_LEVEL_GAP;
	public static int KILL_LEVEL_GAP;
	public static int PARTY_RANGE;
	public static int MIN_PRESENCE_PERCENT;
	public static boolean ONE_REWARD_PER_COMPUTER;
	public static double MAX_REWARD_MULTIPLIER;
	public static double EXP_RATE;
	public static double ADENA_RATE;
	public static int OPTIONAL_BONUS_PERCENT;
	public static int CHAIN_BONUS_PERCENT;
	public static int EVENT_INTERVAL;
	public static int HERALD_WAIT_TIME;
	public static int OFFER_RETRY_DELAY;
	public static int AREA_KILL_WINDOW;
	public static int WORLD_EVENT_RANGE;
	public static boolean ANNOUNCE_WORLD_EVENTS;
	public static boolean NOTICE_BOARDS;
	public static boolean LOGGING;

	public static void load()
	{
		final ConfigReader config = new ConfigReader(DYNAMIC_QUESTS_CONFIG_FILE);
		ENABLED = config.getBoolean("EnableDynamicQuests", true);
		MIN_LEVEL = Math.max(1, config.getInt("DynamicQuestMinLevel", 10));
		BOARD_RESET_HOUR = Math.max(0, Math.min(23, config.getInt("DynamicQuestBoardResetHour", 6)));
		BOARD_OFFERS = Math.max(1, Math.min(12, config.getInt("DynamicQuestBoardOffers", 5)));
		REQUEST_OFFERS = Math.max(0, Math.min(8, config.getInt("DynamicQuestRequestOffers", 3)));
		MAX_ACTIVE = Math.max(1, Math.min(10, config.getInt("DynamicQuestMaxActive", 3)));
		ACCOUNT_DAILY_LIMIT = Math.max(1, config.getInt("DynamicQuestAccountDailyLimit", 15));
		REPEAT_PENALTY_PERCENT = Math.max(0, Math.min(100, config.getInt("DynamicQuestRepeatPenaltyPercent", 25)));
		MIN_REPEAT_PERCENT = Math.max(0, Math.min(100, config.getInt("DynamicQuestMinRepeatPercent", 50)));
		MAX_LEVEL_GAP = Math.max(1, config.getInt("DynamicQuestMaxLevelGap", 5));
		KILL_LEVEL_GAP = Math.max(0, config.getInt("DynamicQuestKillLevelGap", 8));
		PARTY_RANGE = Math.max(300, config.getInt("DynamicQuestPartyRange", 1500));
		MIN_PRESENCE_PERCENT = Math.max(0, Math.min(100, config.getInt("DynamicQuestMinPresencePercent", 40)));
		ONE_REWARD_PER_COMPUTER = config.getBoolean("DynamicQuestOneRewardPerComputer", true);
		MAX_REWARD_MULTIPLIER = Math.max(1, config.getDouble("DynamicQuestMaxRewardMultiplier", 2.5));
		EXP_RATE = Math.max(0, config.getDouble("DynamicQuestExpRate", 1));
		ADENA_RATE = Math.max(0, config.getDouble("DynamicQuestAdenaRate", 1));
		OPTIONAL_BONUS_PERCENT = Math.max(0, config.getInt("DynamicQuestOptionalBonusPercent", 15));
		CHAIN_BONUS_PERCENT = Math.max(0, config.getInt("DynamicQuestChainBonusPercent", 10));
		EVENT_INTERVAL = Math.max(60, config.getInt("DynamicQuestEventInterval", 300));
		HERALD_WAIT_TIME = Math.max(30, config.getInt("DynamicQuestHeraldWaitTime", 120));
		OFFER_RETRY_DELAY = Math.max(60, config.getInt("DynamicQuestOfferRetryDelay", 1800));
		AREA_KILL_WINDOW = Math.max(60, config.getInt("DynamicQuestAreaKillWindow", 900));
		WORLD_EVENT_RANGE = Math.max(500, config.getInt("DynamicQuestWorldEventRange", 3000));
		ANNOUNCE_WORLD_EVENTS = config.getBoolean("DynamicQuestAnnounceWorldEvents", true);
		NOTICE_BOARDS = config.getBoolean("DynamicQuestNoticeBoards", true);
		LOGGING = config.getBoolean("DynamicQuestLogging", true);
	}
}
