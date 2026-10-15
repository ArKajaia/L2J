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
 * The session log: what a character achieved between logging in and out (see {@link org.l2jmobius.gameserver.managers.SessionStatsManager}).
 */
public class SessionStatsConfig
{
	// File
	private static final String SESSION_STATS_CONFIG_FILE = "./config/Custom/SessionStats.ini";
	
	// Constants
	public static boolean ENABLED;
	public static int HISTORY_SIZE;
	public static int MIN_MINUTES;
	public static boolean LOGIN_SUMMARY;
	
	public static void load()
	{
		final ConfigReader config = new ConfigReader(SESSION_STATS_CONFIG_FILE);
		ENABLED = config.getBoolean("SessionStatsEnabled", true);
		HISTORY_SIZE = Math.max(1, Math.min(10, config.getInt("SessionStatsHistory", 5)));
		MIN_MINUTES = Math.max(0, config.getInt("SessionStatsMinMinutes", 10));
		LOGIN_SUMMARY = config.getBoolean("SessionStatsLoginSummary", true);
	}
}
