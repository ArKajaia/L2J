/*
 * This file is part of the L2J Mobius project.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>.
 */
package org.l2jmobius.gameserver.config.custom;

import org.l2jmobius.commons.util.ConfigReader;

/**
 * Crafting goals: an item to craft tracked like a quest (see {@link org.l2jmobius.gameserver.managers.CraftingGoalManager}).
 */
public class CraftingGoalsConfig
{
	// File
	private static final String CRAFTING_GOALS_CONFIG_FILE = "./config/Custom/CraftingGoals.ini";

	// Constants
	public static boolean ENABLED;
	public static int MAX_GOALS;
	public static int MAX_AMOUNT;
	public static boolean BREAKDOWN;
	public static int BREAKDOWN_DEPTH;
	public static boolean PROGRESS_MESSAGES;
	public static boolean FIREWORKS;
	public static boolean QUESTION_MARK;

	public static void load()
	{
		final ConfigReader config = new ConfigReader(CRAFTING_GOALS_CONFIG_FILE);
		ENABLED = config.getBoolean("CraftingGoalsEnabled", true);
		MAX_GOALS = Math.max(1, config.getInt("CraftingGoalsMax", 3));
		MAX_AMOUNT = Math.max(1, config.getInt("CraftingGoalsMaxAmount", 1000));
		BREAKDOWN = config.getBoolean("CraftingGoalsBreakdown", true);
		BREAKDOWN_DEPTH = Math.max(1, Math.min(5, config.getInt("CraftingGoalsBreakdownDepth", 3)));
		PROGRESS_MESSAGES = config.getBoolean("CraftingGoalsProgressMessages", true);
		FIREWORKS = config.getBoolean("CraftingGoalsFireworks", true);
		QUESTION_MARK = config.getBoolean("CraftingGoalsQuestionMark", true);
	}
}
