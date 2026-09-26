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

import java.util.HashSet;
import java.util.Set;
import java.util.logging.Logger;

import org.l2jmobius.commons.util.ConfigReader;

/**
 * Loads the Monster Rage configuration: once a monster has been stunned or held {@link #DISABLES_REQUIRED} times by players, every further stun/hold has {@link #CHANCE}% to make it rage for {@link #DURATION_SECONDS} seconds (see
 * {@link org.l2jmobius.gameserver.managers.MonsterRageManager}).
 * @author Mobius
 */
public class MonsterRageConfig
{
	private static final Logger LOGGER = Logger.getLogger(MonsterRageConfig.class.getName());

	// File
	private static final String MONSTER_RAGE_CONFIG_FILE = "./config/Custom/MonsterRage.ini";

	// Constants
	public static boolean ENABLED;
	public static int DISABLES_REQUIRED;
	public static double CHANCE;
	public static int DURATION_SECONDS;
	public static int ATTACK_BONUS;
	public static int SPEED_BONUS;
	public static double DEBUFF_RESISTANCE;
	public static boolean BREAKS_DISABLES;
	public static boolean ALLOW_RAIDS;
	public static Set<Integer> EXCLUDED_NPC_IDS = new HashSet<>();
	public static String TITLE_TAG;
	public static String MESSAGE;

	public static void load()
	{
		final ConfigReader config = new ConfigReader(MONSTER_RAGE_CONFIG_FILE);
		ENABLED = config.getBoolean("RageEnabled", true);
		DISABLES_REQUIRED = Math.max(0, config.getInt("RageDisablesRequired", 10));
		CHANCE = config.getDouble("RageChance", 20.0);
		DURATION_SECONDS = Math.max(1, config.getInt("RageDurationSeconds", 10));
		ATTACK_BONUS = config.getInt("RageAttackBonus", 20);
		SPEED_BONUS = config.getInt("RageSpeedBonus", 20);
		DEBUFF_RESISTANCE = Math.max(0, Math.min(100, config.getDouble("RageDebuffResistance", 100)));
		BREAKS_DISABLES = config.getBoolean("RageBreaksDisables", true);
		ALLOW_RAIDS = config.getBoolean("RageAllowRaids", false);

		EXCLUDED_NPC_IDS.clear();
		for (String idStr : config.getString("RageExcludedNpcIds", "").split(","))
		{
			idStr = idStr.trim();
			if (!idStr.isEmpty())
			{
				try
				{
					EXCLUDED_NPC_IDS.add(Integer.parseInt(idStr));
				}
				catch (NumberFormatException e)
				{
					LOGGER.warning("Invalid RageExcludedNpcIds entry: " + idStr);
				}
			}
		}

		TITLE_TAG = config.getString("RageTitleTag", "[RAGE]");
		MESSAGE = config.getString("RageMessage", "%s flies into a rage!");
	}
}
