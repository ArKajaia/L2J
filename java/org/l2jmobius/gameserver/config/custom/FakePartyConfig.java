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
 * Loads the fake party member configuration: a player invites a roaming fake player it meets, which may join the party depending on their levels (see {@link org.l2jmobius.gameserver.managers.FakePartyManager}).
 */
public class FakePartyConfig
{
	// File
	private static final String FAKE_PARTY_CONFIG_FILE = "./config/Custom/FakeParty.ini";
	
	public static boolean ENABLED;
	public static int INVITE_ACCEPT_CHANCE;
	public static int INVITE_CHANCE_PER_LEVEL_BELOW;
	public static int INVITE_CHANCE_PER_LEVEL_ABOVE;
	public static int INVITE_MAX_LEVEL_DIFFERENCE;
	public static int INVITE_CLAN_BONUS;
	public static int MAX_FAKES;
	public static boolean CAN_DIE;
	public static int RETURN_DELAY;
	public static boolean EXP_SHARE;
	public static int FOLLOW_DISTANCE;
	public static int LEASH_RANGE;
	public static int TELEPORT_DELAY;
	public static boolean HUNT_WHEN_IDLE;
	public static boolean CHAT;
	public static int ROAMING_CHANCE;
	public static int ROAMING_MAX_SIZE;
	public static int ROAMING_SUPPORT_CHANCE;
	
	public static void load()
	{
		final ConfigReader config = new ConfigReader(FAKE_PARTY_CONFIG_FILE);
		ENABLED = config.getBoolean("FakePartyEnabled", true);
		
		INVITE_ACCEPT_CHANCE = Math.max(0, Math.min(100, config.getInt("FakePartyInviteAcceptChance", 80)));
		INVITE_CHANCE_PER_LEVEL_BELOW = Math.max(0, config.getInt("FakePartyInviteChancePerLevelBelow", 10));
		INVITE_CHANCE_PER_LEVEL_ABOVE = Math.max(0, config.getInt("FakePartyInviteChancePerLevelAbove", 6));
		INVITE_MAX_LEVEL_DIFFERENCE = Math.max(0, config.getInt("FakePartyInviteMaxLevelDifference", 10));
		INVITE_CLAN_BONUS = Math.max(0, Math.min(100, config.getInt("FakePartyInviteClanBonus", 20)));
		MAX_FAKES = Math.max(1, Math.min(8, config.getInt("FakePartyMaxFakes", 8)));
		CAN_DIE = config.getBoolean("FakePartyCanDie", true);
		RETURN_DELAY = Math.max(0, config.getInt("FakePartyReturnDelay", 60));
		EXP_SHARE = config.getBoolean("FakePartyExpShare", true);
		FOLLOW_DISTANCE = Math.max(50, config.getInt("FakePartyFollowDistance", 200));
		LEASH_RANGE = Math.max(300, config.getInt("FakePartyLeashRange", 1500));
		TELEPORT_DELAY = Math.max(0, config.getInt("FakePartyTeleportDelay", 10));
		HUNT_WHEN_IDLE = config.getBoolean("FakePartyHuntWhenIdle", false);
		CHAT = config.getBoolean("FakePartyChat", true);
		ROAMING_CHANCE = Math.max(0, Math.min(100, config.getInt("FakePartyRoamingChance", 15)));
		ROAMING_MAX_SIZE = Math.max(2, Math.min(9, config.getInt("FakePartyRoamingMaxSize", 3)));
		ROAMING_SUPPORT_CHANCE = Math.max(0, Math.min(100, config.getInt("FakePartyRoamingSupportChance", 60)));
	}
}
