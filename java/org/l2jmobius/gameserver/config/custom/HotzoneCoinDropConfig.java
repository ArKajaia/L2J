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
 * Loads the hotzone coin-drop configuration: killing a monster inside a hotzone (see {@link org.l2jmobius.gameserver.model.zone.type.HotZone}) drops {@link org.l2jmobius.gameserver.config.RatesConfig#ARENA_CURRENCY_ITEM_ID} - the same currency the Survival Arena pays out - scaled by the
 * victim's level: {@link #MIN_AMOUNT} at level 1, rising linearly to {@link #MAX_AMOUNT} at {@link #LEVEL_FOR_MAX_AMOUNT} and beyond.
 * @author Mobius
 */
public class HotzoneCoinDropConfig
{
	// File
	private static final String HOTZONE_COIN_DROP_CONFIG_FILE = "./config/Custom/HotzoneCoinDrop.ini";

	// Constants
	public static boolean ENABLED;
	public static double DROP_CHANCE;
	public static int MIN_AMOUNT;
	public static int MAX_AMOUNT;
	public static int LEVEL_FOR_MAX_AMOUNT;

	public static void load()
	{
		final ConfigReader config = new ConfigReader(HOTZONE_COIN_DROP_CONFIG_FILE);
		ENABLED = config.getBoolean("HotzoneCoinDropEnabled", true);
		DROP_CHANCE = config.getDouble("HotzoneCoinDropChance", 100.0);
		MIN_AMOUNT = config.getInt("HotzoneCoinDropMinAmount", 1);
		MAX_AMOUNT = config.getInt("HotzoneCoinDropMaxAmount", 20);
		LEVEL_FOR_MAX_AMOUNT = config.getInt("HotzoneCoinDropLevelForMaxAmount", 85);
	}
}
