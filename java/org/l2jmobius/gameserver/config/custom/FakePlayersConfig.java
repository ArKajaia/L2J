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
 * This class loads all the custom fake player related configurations.
 * @author Mobius
 */
public class FakePlayersConfig
{
	// File
	private static final String FAKE_PLAYERS_CONFIG_FILE = "./config/Custom/FakePlayers.ini";
	
	// Constants
	public static boolean FAKE_PLAYERS_ENABLED;
	public static boolean FAKE_PLAYER_CHAT;
	public static int FAKE_PLAYER_CHAT_NEARBY_CHANCE;
	public static int FAKE_PLAYER_CHAT_NEARBY_RANGE;
	public static int FAKE_PLAYER_CHAT_NEARBY_COOLDOWN;
	public static boolean FAKE_PLAYER_USE_SHOTS;
	public static boolean FAKE_PLAYER_KILL_PVP;
	public static int FAKE_PLAYER_KILL_PVP_LEVEL_GAP;
	public static boolean FAKE_PLAYER_KILL_KARMA;
	public static boolean FAKE_PLAYER_AUTO_ATTACKABLE;
	public static boolean FAKE_PLAYER_AGGRO_MONSTERS;
	public static boolean FAKE_PLAYER_AGGRO_PLAYERS;
	public static boolean FAKE_PLAYER_AGGRO_FPC;
	public static boolean FAKE_PLAYER_CAN_DROP_ITEMS;
	public static boolean FAKE_PLAYER_CAN_PICKUP;
	public static boolean FAKE_TOWN_PLAYERS_ENABLED;
	public static int FAKE_TOWN_PLAYERS_PER_TOWN;
	public static String FAKE_TOWN_PLAYERS_TOWNS;
	public static int FAKE_TOWN_PLAYERS_WANDER_RANGE;
	public static int FAKE_TOWN_PLAYERS_RACE_CHANCE;
	public static boolean FAKE_TOWN_PLAYERS_CHAT;
	public static int FAKE_TOWN_PLAYERS_CHAT_RATE;
	public static int FAKE_TOWN_PLAYERS_BUFFERS;
	public static int FAKE_TOWN_PLAYERS_POPULATION_VARIATION;
	public static int FAKE_TOWN_PLAYERS_STAY_SCALE;
	public static int FAKE_TOWN_PLAYERS_STORES;
	public static int FAKE_TOWN_PLAYERS_STORE_RARE_CHANCE;
	public static boolean FAKE_TOWN_PLAYERS_BEGGARS;
	
	public static void load()
	{
		final ConfigReader config = new ConfigReader(FAKE_PLAYERS_CONFIG_FILE);
		FAKE_PLAYERS_ENABLED = config.getBoolean("EnableFakePlayers", false);
		FAKE_PLAYER_CHAT = config.getBoolean("FakePlayerChat", false);
		FAKE_PLAYER_CHAT_NEARBY_CHANCE = Math.max(0, Math.min(100, config.getInt("FakePlayerChatNearbyChance", 30)));
		FAKE_PLAYER_CHAT_NEARBY_RANGE = Math.max(0, config.getInt("FakePlayerChatNearbyRange", 600));
		FAKE_PLAYER_CHAT_NEARBY_COOLDOWN = Math.max(0, config.getInt("FakePlayerChatNearbyCooldown", 30));
		FAKE_PLAYER_USE_SHOTS = config.getBoolean("FakePlayerUseShots", false);
		FAKE_PLAYER_KILL_PVP = config.getBoolean("FakePlayerKillsRewardPvP", false);
		FAKE_PLAYER_KILL_PVP_LEVEL_GAP = config.getInt("FakePlayerKillsRewardPvPLevelGap", 8);
		FAKE_PLAYER_KILL_KARMA = config.getBoolean("FakePlayerUnflaggedKillsKarma", false);
		FAKE_PLAYER_AUTO_ATTACKABLE = config.getBoolean("FakePlayerAutoAttackable", false);
		FAKE_PLAYER_AGGRO_MONSTERS = config.getBoolean("FakePlayerAggroMonsters", false);
		FAKE_PLAYER_AGGRO_PLAYERS = config.getBoolean("FakePlayerAggroPlayers", false);
		FAKE_PLAYER_AGGRO_FPC = config.getBoolean("FakePlayerAggroFPC", false);
		FAKE_PLAYER_CAN_DROP_ITEMS = config.getBoolean("FakePlayerCanDropItems", false);
		FAKE_PLAYER_CAN_PICKUP = config.getBoolean("FakePlayerCanPickup", false);
		FAKE_TOWN_PLAYERS_ENABLED = config.getBoolean("FakeTownPlayersEnabled", false);
		FAKE_TOWN_PLAYERS_PER_TOWN = config.getInt("FakeTownPlayersPerTown", 12);
		FAKE_TOWN_PLAYERS_TOWNS = config.getString("FakeTownPlayersTowns", "");
		FAKE_TOWN_PLAYERS_WANDER_RANGE = config.getInt("FakeTownPlayersWanderRange", 500);
		FAKE_TOWN_PLAYERS_RACE_CHANCE = config.getInt("FakeTownPlayersRaceChance", 60);
		FAKE_TOWN_PLAYERS_CHAT = config.getBoolean("FakeTownPlayersChat", true);
		FAKE_TOWN_PLAYERS_CHAT_RATE = Math.max(0, config.getInt("FakeTownPlayersChatRate", 100));
		FAKE_TOWN_PLAYERS_BUFFERS = Math.max(0, config.getInt("FakeTownPlayersBuffers", 1));
		FAKE_TOWN_PLAYERS_POPULATION_VARIATION = Math.max(0, Math.min(90, config.getInt("FakeTownPlayersPopulationVariation", 25)));
		FAKE_TOWN_PLAYERS_STAY_SCALE = Math.max(10, config.getInt("FakeTownPlayersStayScale", 100));
		FAKE_TOWN_PLAYERS_STORES = Math.max(0, config.getInt("FakeTownPlayersStores", 3));
		FAKE_TOWN_PLAYERS_STORE_RARE_CHANCE = Math.max(0, Math.min(100, config.getInt("FakeTownPlayersStoreRareChance", 12)));
		FAKE_TOWN_PLAYERS_BEGGARS = config.getBoolean("FakeTownPlayersBeggars", true);
	}
}
