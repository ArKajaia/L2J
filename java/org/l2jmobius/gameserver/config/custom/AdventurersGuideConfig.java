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
 * The Adventurer's Guide: tutorial lessons about the server's own systems (see {@link org.l2jmobius.gameserver.managers.AdventurersGuideManager}).
 */
public class AdventurersGuideConfig
{
	// File
	private static final String ADVENTURERS_GUIDE_CONFIG_FILE = "./config/Custom/AdventurersGuide.ini";

	// Constants
	public static boolean ENABLED;
	public static boolean WELCOME_WINDOW;
	public static boolean CHAT_NOTICE;
	public static int MAX_QUESTION_MARKS;
	public static int COMMUNITY_BOARD_LEVEL;
	public static int MONSTER_TRAITS_LEVEL;
	public static int BUFFS_LEVEL;
	public static int HOT_ZONES_LEVEL;
	public static int ARENA_LEVEL;
	public static int TREASURE_CHESTS_LEVEL;
	public static int NIGHT_LEVEL;

	public static void load()
	{
		final ConfigReader config = new ConfigReader(ADVENTURERS_GUIDE_CONFIG_FILE);
		ENABLED = config.getBoolean("AdventurersGuideEnabled", true);
		WELCOME_WINDOW = config.getBoolean("AdventurersGuideWelcome", true);
		CHAT_NOTICE = config.getBoolean("AdventurersGuideChatNotice", true);
		MAX_QUESTION_MARKS = Math.max(1, config.getInt("AdventurersGuideMaxQuestionMarks", 3));
		COMMUNITY_BOARD_LEVEL = config.getInt("AdventurersGuideCommunityBoardLevel", 3);
		MONSTER_TRAITS_LEVEL = config.getInt("AdventurersGuideMonsterTraitsLevel", 5);
		BUFFS_LEVEL = config.getInt("AdventurersGuideBuffsLevel", 15);
		HOT_ZONES_LEVEL = config.getInt("AdventurersGuideHotZonesLevel", 12);
		ARENA_LEVEL = config.getInt("AdventurersGuideArenaLevel", 20);
		TREASURE_CHESTS_LEVEL = config.getInt("AdventurersGuideTreasureChestsLevel", 25);
		NIGHT_LEVEL = config.getInt("AdventurersGuideNightLevel", 20);
	}
}
