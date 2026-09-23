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
 * Loads the Hotzone Miniboss configuration: once {@link #KILLS_REQUIRED} monsters die inside the same hotzone (see {@link org.l2jmobius.gameserver.model.zone.type.HotZone}), a buffed clone of whichever monster type died last spawns on the spot as a tough-but-two-player-killable miniboss with
 * greatly boosted XP/SP and drop rates.
 * @author Mobius
 */
public class HotzoneMinibossConfig
{
	// File
	private static final String HOTZONE_MINIBOSS_CONFIG_FILE = "./config/Custom/HotzoneMiniboss.ini";

	// Constants
	public static boolean ENABLED;
	public static int KILLS_REQUIRED;
	public static double STAT_MULTIPLIER;
	public static double XP_SP_MULTIPLIER;
	public static double DROP_MULTIPLIER;
	public static String TITLE_TAG;

	public static void load()
	{
		final ConfigReader config = new ConfigReader(HOTZONE_MINIBOSS_CONFIG_FILE);
		ENABLED = config.getBoolean("HotzoneMinibossEnabled", true);
		KILLS_REQUIRED = config.getInt("HotzoneMinibossKillsRequired", 100);
		// Tuned for a 2-player kill, not a full raid: HP/PAtk/MAtk/PDef/MDef all scale by this
		// same factor, so it stays a straightforward "hits much harder, tanks much more" fight
		// rather than one stat spiking out of proportion with the others.
		STAT_MULTIPLIER = config.getDouble("HotzoneMinibossStatMultiplier", 6.0);
		XP_SP_MULTIPLIER = config.getDouble("HotzoneMinibossXpSpMultiplier", 10.0);
		DROP_MULTIPLIER = config.getDouble("HotzoneMinibossDropMultiplier", 10.0);
		TITLE_TAG = config.getString("HotzoneMinibossTitleTag", "[Hotzone Menace]");
	}
}
