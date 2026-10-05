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

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.logging.Logger;

import org.l2jmobius.commons.util.ConfigReader;
import org.l2jmobius.commons.util.StringUtil;

/**
 * Loads the configuration of the clans run by fake players (see {@link org.l2jmobius.gameserver.managers.FakeClanManager}).
 */
public class FakeClanConfig
{
	private static final Logger LOGGER = Logger.getLogger(FakeClanConfig.class.getName());
	
	// File
	private static final String FAKE_CLANS_CONFIG_FILE = "./config/Custom/FakeClans.ini";
	
	public static boolean ENABLED;
	public static List<String> NAMES = new ArrayList<>();
	public static List<String[]> WARS = new ArrayList<>();
	public static int LEVEL_MIN;
	public static int LEVEL_MAX;
	public static int MEMBERS_MIN;
	public static int MEMBERS_MAX;
	public static int MEMBER_CHANCE;
	public static int SAME_CLAN_CHANCE;
	public static int TITLE_CHANCE;
	public static List<String> TITLES = new ArrayList<>();
	public static int WAR_ATTACK_CHANCE;
	public static int WAR_MAX_LEVEL_ABOVE;
	public static int WAR_REPLY_CHANCE;
	public static int WAR_REPLY_DELAY_MIN;
	public static int WAR_REPLY_DELAY_MAX;
	public static int WAR_GRUDGE_KILLS;
	public static int WAR_GRUDGE_TIME;
	public static int ALLY_ACCEPT_CHANCE;
	public static int INVITE_ACCEPT_CHANCE;
	public static int MAX_RECRUITS;
	public static int MEMBER_CYCLE_HOURS;
	public static int MEMBER_LEAVE_CHANCE;
	public static int MEMBER_LEVEL_UP_CHANCE;
	public static Set<Integer> MEMBER_REROLL_LEVELS = new HashSet<>();
	public static int MEMBER_OFFLINE_MIN;
	public static int MEMBER_OFFLINE_MAX;
	public static int MEMBER_LOGIN_LEVEL_RANGE;
	
	public static void load()
	{
		final ConfigReader config = new ConfigReader(FAKE_CLANS_CONFIG_FILE);
		ENABLED = config.getBoolean("FakeClansEnabled", true);
		
		NAMES = new ArrayList<>();
		for (String value : config.getString("FakeClanNames", "Valhalla,IronLegion,Nightshade,DragonGuard,SilverWolves,CrimsonDawn,Eclipse,Phoenix").split(","))
		{
			final String name = value.trim();
			if (name.isEmpty())
			{
				continue;
			}
			
			if (!StringUtil.isAlphaNumeric(name) || (name.length() < 2) || (name.length() > 16))
			{
				LOGGER.warning(FakeClanConfig.class.getSimpleName() + ": Clan name " + name + " in FakeClanNames is not 2-16 letters and digits, skipped.");
				continue;
			}
			
			if (NAMES.stream().noneMatch(name::equalsIgnoreCase))
			{
				NAMES.add(name);
			}
		}
		
		WARS = new ArrayList<>();
		for (String value : config.getString("FakeClanWars", "IronLegion:CrimsonDawn").split(";"))
		{
			final String[] pair = value.trim().split(":");
			if ((pair.length != 2) || pair[0].isBlank() || pair[1].isBlank())
			{
				if (!value.isBlank())
				{
					LOGGER.warning(FakeClanConfig.class.getSimpleName() + ": FakeClanWars entry " + value.trim() + " is not Name1:Name2, skipped.");
				}
				continue;
			}
			
			WARS.add(new String[]
			{
				pair[0].trim(),
				pair[1].trim()
			});
		}
		
		LEVEL_MIN = Math.max(3, Math.min(11, config.getInt("FakeClanLevelMin", 4)));
		LEVEL_MAX = Math.max(LEVEL_MIN, Math.min(11, config.getInt("FakeClanLevelMax", 8)));
		MEMBERS_MIN = Math.max(1, config.getInt("FakeClanMembersMin", 18));
		MEMBERS_MAX = Math.max(MEMBERS_MIN, config.getInt("FakeClanMembersMax", 40));
		MEMBER_CHANCE = Math.max(0, Math.min(100, config.getInt("FakeClanMemberChance", 35)));
		SAME_CLAN_CHANCE = Math.max(0, Math.min(100, config.getInt("FakeClanSameClanChance", 70)));
		TITLE_CHANCE = Math.max(0, Math.min(100, config.getInt("FakeClanTitleChance", 80)));
		
		TITLES = new ArrayList<>();
		for (String value : config.getString("FakeClanTitles", "").split(","))
		{
			final String title = value.trim();
			if (!title.isEmpty())
			{
				TITLES.add(title.length() > 16 ? title.substring(0, 16) : title);
			}
		}
		
		WAR_ATTACK_CHANCE = Math.max(0, Math.min(100, config.getInt("FakeClanWarAttackChance", 70)));
		WAR_MAX_LEVEL_ABOVE = Math.max(0, config.getInt("FakeClanWarMaxLevelAbove", 5));
		WAR_REPLY_CHANCE = Math.max(0, Math.min(100, config.getInt("FakeClanWarReplyChance", 100)));
		WAR_REPLY_DELAY_MIN = Math.max(0, config.getInt("FakeClanWarReplyDelayMin", 30));
		WAR_REPLY_DELAY_MAX = Math.max(WAR_REPLY_DELAY_MIN, config.getInt("FakeClanWarReplyDelayMax", 300));
		WAR_GRUDGE_KILLS = Math.max(0, config.getInt("FakeClanWarGrudgeKills", 5));
		WAR_GRUDGE_TIME = Math.max(1, config.getInt("FakeClanWarGrudgeTime", 60));
		ALLY_ACCEPT_CHANCE = Math.max(0, Math.min(100, config.getInt("FakeClanAllyAcceptChance", 80)));
		INVITE_ACCEPT_CHANCE = Math.max(0, Math.min(100, config.getInt("FakeClanInviteAcceptChance", 60)));
		MAX_RECRUITS = Math.max(0, config.getInt("FakeClanMaxRecruits", 0));
		MEMBER_CYCLE_HOURS = Math.max(1, config.getInt("FakeClanMemberCycleHours", 24));
		MEMBER_LEAVE_CHANCE = Math.max(0, Math.min(100, config.getInt("FakeClanMemberLeaveChance", 5)));
		MEMBER_LEVEL_UP_CHANCE = Math.max(0, Math.min(100, config.getInt("FakeClanMemberLevelUpChance", 5)));
		
		MEMBER_REROLL_LEVELS = new HashSet<>();
		for (String value : config.getString("FakeClanMemberRerollLevels", "20,40,52,61,76,80,82,84").split(","))
		{
			if (value.isBlank())
			{
				continue;
			}
			
			try
			{
				MEMBER_REROLL_LEVELS.add(Integer.parseInt(value.trim()));
			}
			catch (NumberFormatException e)
			{
				LOGGER.warning(FakeClanConfig.class.getSimpleName() + ": FakeClanMemberRerollLevels entry " + value.trim() + " is not a level, skipped.");
			}
		}
		
		MEMBER_OFFLINE_MIN = Math.max(1, config.getInt("FakeClanMemberOfflineMin", 30));
		MEMBER_OFFLINE_MAX = Math.max(MEMBER_OFFLINE_MIN, config.getInt("FakeClanMemberOfflineMax", 240));
		MEMBER_LOGIN_LEVEL_RANGE = Math.max(0, config.getInt("FakeClanMemberLoginLevelRange", 5));
	}
}
