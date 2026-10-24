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
 * The PvP combat log shown on death (see {@link org.l2jmobius.gameserver.managers.CombatLogManager}), the raid log shown after a raid boss kill (see {@link org.l2jmobius.gameserver.managers.RaidLogManager}), and the combat block of the damage messages (see {@link org.l2jmobius.gameserver.network.serverpackets.CombatInfo}).
 */
public class CombatLogConfig
{
	// File
	private static final String COMBAT_LOG_CONFIG_FILE = "./config/Custom/CombatLog.ini";
	
	// Constants
	public static boolean ENABLED;
	public static long FIGHT_MILLIS;
	public static int TIMELINE_SIZE;
	public static boolean TIMELINE_OWN_HITS;
	public static boolean SHOW_ON_DEATH;
	public static boolean CHAT_SUMMARY;
	public static boolean RAID_ENABLED;
	public static long RAID_IDLE_MILLIS;
	public static boolean RAID_SHOW_ON_KILL;
	public static boolean RAID_CHAT_SUMMARY;
	public static boolean PACKET_EXTENSION;
	
	public static void load()
	{
		final ConfigReader config = new ConfigReader(COMBAT_LOG_CONFIG_FILE);
		ENABLED = config.getBoolean("CombatLogEnabled", true);
		FIGHT_MILLIS = Math.max(5, config.getInt("CombatLogFightSeconds", 30)) * 1000L;
		TIMELINE_SIZE = Math.max(10, Math.min(100, config.getInt("CombatLogTimelineSize", 60)));
		TIMELINE_OWN_HITS = config.getBoolean("CombatLogTimelineOwnHits", true);
		SHOW_ON_DEATH = config.getBoolean("CombatLogShowOnDeath", true);
		CHAT_SUMMARY = config.getBoolean("CombatLogChatSummary", true);
		RAID_ENABLED = config.getBoolean("RaidLogEnabled", true);
		RAID_IDLE_MILLIS = Math.max(1, config.getInt("RaidLogIdleMinutes", 5)) * 60000L;
		RAID_SHOW_ON_KILL = config.getBoolean("RaidLogShowOnKill", true);
		RAID_CHAT_SUMMARY = config.getBoolean("RaidLogChatSummary", true);
		PACKET_EXTENSION = config.getBoolean("CombatLogPacketExtension", true);
	}
}
