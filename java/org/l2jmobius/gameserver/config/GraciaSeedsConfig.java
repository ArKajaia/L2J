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
package org.l2jmobius.gameserver.config;

import org.l2jmobius.commons.util.ConfigReader;

/**
 * This class loads all the Gracia seed related configurations.
 * @author Mobius
 */
public class GraciaSeedsConfig
{
	// File
	private static final String GRACIA_SEEDS_CONFIG_FILE = "./config/GraciaSeeds.ini";
	
	// Constants
	public static int SOD_TIAT_KILL_COUNT;
	public static long SOD_STAGE_2_LENGTH;
	public static int MIN_TIAT_PLAYERS;
	public static int MAX_TIAT_PLAYERS;
	public static int MIN_TIAT_LEVEL;
	public static int SOI_EKIMUS_KILL_COUNT;
	public static int EROSION_ATTACK_MIN_PLAYERS;
	public static int EROSION_ATTACK_MAX_PLAYERS;
	public static int EROSION_DEFENCE_MIN_PLAYERS;
	public static int EROSION_DEFENCE_MAX_PLAYERS;
	public static int HEART_ATTACK_MIN_PLAYERS;
	public static int HEART_ATTACK_MAX_PLAYERS;
	public static int HEART_DEFENCE_MIN_PLAYERS;
	public static int HEART_DEFENCE_MAX_PLAYERS;
	
	public static void load()
	{
		final ConfigReader config = new ConfigReader(GRACIA_SEEDS_CONFIG_FILE);
		SOD_TIAT_KILL_COUNT = config.getInt("TiatKillCountForNextState", 10);
		SOD_STAGE_2_LENGTH = config.getLong("Stage2Length", 720) * 60000;
		MIN_TIAT_PLAYERS = config.getInt("MinPlayers", 36);
		MAX_TIAT_PLAYERS = config.getInt("MaxPlayers", 45);
		MIN_TIAT_LEVEL = config.getInt("MinLevel", 75);
		SOI_EKIMUS_KILL_COUNT = config.getInt("EkimusKillCount", 5);
		EROSION_ATTACK_MIN_PLAYERS = config.getInt("MinEroAttPlayers", 18);
		EROSION_ATTACK_MAX_PLAYERS = config.getInt("MaxEroAttPlayers", 27);
		EROSION_DEFENCE_MIN_PLAYERS = config.getInt("MinEroDefPlayers", 18);
		EROSION_DEFENCE_MAX_PLAYERS = config.getInt("MaxEroDefPlayers", 27);
		HEART_ATTACK_MIN_PLAYERS = config.getInt("MinHeaAttPlayers", 18);
		HEART_ATTACK_MAX_PLAYERS = config.getInt("MaxHeaAttPlayers", 27);
		HEART_DEFENCE_MIN_PLAYERS = config.getInt("MinHeaDefPlayers", 18);
		HEART_DEFENCE_MAX_PLAYERS = config.getInt("MaxHeaDefPlayers", 27);
	}
}
