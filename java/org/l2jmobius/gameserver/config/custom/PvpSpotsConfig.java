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

import java.util.logging.Logger;

import org.l2jmobius.commons.util.ConfigReader;

/**
 * Loads the PvP spot configuration: open-world areas (zones of type PvpSpotZone, see data/zones/pvp_spots.xml) where everyone is flagged, kills are never a PK, players teleport with .pvp and fake players come only to fight (see
 * {@link org.l2jmobius.gameserver.managers.PvpSpotManager}).
 */
public class PvpSpotsConfig
{
	private static final Logger LOGGER = Logger.getLogger(PvpSpotsConfig.class.getName());

	// File
	private static final String PVP_SPOTS_CONFIG_FILE = "./config/Custom/PvpSpots.ini";

	// Players
	public static boolean ENABLED;
	public static boolean NO_DEATH_PENALTY;
	public static boolean TELEPORT_ENABLED;
	public static long TELEPORT_PRICE;
	public static boolean TELEPORT_PEACE_ONLY;
	public static boolean TELEPORT_KARMA;

	// Fake players
	public static boolean FAKE_PLAYERS;
	public static int FIGHTERS_MIN;
	public static int FIGHTERS_MAX;
	public static int ARRIVAL_DELAY_MIN;
	public static int ARRIVAL_DELAY_MAX;
	public static int STAY_TIME_MIN;
	public static int STAY_TIME_MAX;
	public static int RETURN_CHANCE;
	public static int RETURN_DELAY_MIN;
	public static int RETURN_DELAY_MAX;
	public static int GROUP_CHANCE;
	public static int GROUP_MAX_SIZE;
	public static int MATCH_PLAYER_LEVEL_CHANCE;
	public static int MATCH_PLAYER_LEVEL_RANGE;
	public static double EQUIPMENT_DROP_CHANCE;

	// The strong one
	public static int ELITE_CHANCE;
	public static int MAX_ELITES;
	public static int ELITE_ENCHANT_BONUS;

	// Leader
	public static int LEADER_MIN_STREAK;
	public static String LEADER_TITLE;
	public static int LEADER_TITLE_COLOR;
	public static boolean LEADER_HERO_AURA;
	public static boolean LEADER_ANNOUNCE;
	public static int LEADER_FOCUS_CHANCE;

	// Combat
	public static boolean CP_POTIONS;
	public static int CP_POTION_REUSE;
	public static int CHASE_OUTSIDE;
	public static int TARGET_RANGE;

	public static void load()
	{
		final ConfigReader config = new ConfigReader(PVP_SPOTS_CONFIG_FILE);
		ENABLED = config.getBoolean("PvpSpotsEnabled", true);
		NO_DEATH_PENALTY = config.getBoolean("PvpSpotNoDeathPenalty", true);
		TELEPORT_ENABLED = config.getBoolean("PvpSpotTeleportEnabled", true);
		TELEPORT_PRICE = Math.max(0, config.getLong("PvpSpotTeleportPrice", 0));
		TELEPORT_PEACE_ONLY = config.getBoolean("PvpSpotTeleportPeaceZoneOnly", false);
		TELEPORT_KARMA = config.getBoolean("PvpSpotTeleportWithKarma", false);

		FAKE_PLAYERS = config.getBoolean("PvpSpotFakePlayers", true);
		FIGHTERS_MIN = Math.max(0, config.getInt("PvpSpotFightersMin", 10));
		FIGHTERS_MAX = Math.max(FIGHTERS_MIN, config.getInt("PvpSpotFightersMax", 16));
		final int[] arrival = parseRange(config, "PvpSpotArrivalDelay", "4-15", 1);
		ARRIVAL_DELAY_MIN = arrival[0];
		ARRIVAL_DELAY_MAX = arrival[1];
		final int[] stay = parseRange(config, "PvpSpotStayTime", "900-2700", 60);
		STAY_TIME_MIN = stay[0];
		STAY_TIME_MAX = stay[1];
		RETURN_CHANCE = Math.max(0, Math.min(100, config.getInt("PvpSpotReturnChance", 75)));
		final int[] returnDelay = parseRange(config, "PvpSpotReturnDelay", "20-60", 1);
		RETURN_DELAY_MIN = returnDelay[0];
		RETURN_DELAY_MAX = returnDelay[1];
		GROUP_CHANCE = Math.max(0, Math.min(100, config.getInt("PvpSpotGroupChance", 25)));
		GROUP_MAX_SIZE = Math.max(2, Math.min(5, config.getInt("PvpSpotGroupMaxSize", 3)));
		MATCH_PLAYER_LEVEL_CHANCE = Math.max(0, Math.min(100, config.getInt("PvpSpotMatchPlayerLevelChance", 60)));
		MATCH_PLAYER_LEVEL_RANGE = Math.max(0, config.getInt("PvpSpotMatchPlayerLevelRange", 3));
		EQUIPMENT_DROP_CHANCE = Math.max(0, Math.min(100, config.getDouble("PvpSpotEquipmentDropChance", 0)));

		ELITE_CHANCE = Math.max(0, Math.min(100, config.getInt("PvpSpotEliteChance", 20)));
		MAX_ELITES = Math.max(0, config.getInt("PvpSpotMaxElites", 1));
		ELITE_ENCHANT_BONUS = Math.max(0, config.getInt("PvpSpotEliteEnchantBonus", 3));

		LEADER_MIN_STREAK = Math.max(1, config.getInt("PvpSpotLeaderMinStreak", 3));
		LEADER_TITLE = config.getString("PvpSpotLeaderTitle", "PvP Leader");
		LEADER_TITLE_COLOR = parseColor(config.getString("PvpSpotLeaderTitleColor", "00CCFF"));
		LEADER_HERO_AURA = config.getBoolean("PvpSpotLeaderHeroAura", true);
		LEADER_ANNOUNCE = config.getBoolean("PvpSpotLeaderAnnounce", true);
		LEADER_FOCUS_CHANCE = Math.max(0, Math.min(100, config.getInt("PvpSpotLeaderFocusChance", 50)));

		CP_POTIONS = config.getBoolean("PvpSpotCpPotions", true);
		CP_POTION_REUSE = Math.max(500, config.getInt("PvpSpotCpPotionReuse", 1000));
		CHASE_OUTSIDE = Math.max(0, config.getInt("PvpSpotChaseOutside", 600));
		TARGET_RANGE = Math.max(300, config.getInt("PvpSpotTargetRange", 1200));
	}

	/**
	 * @param config the config
	 * @param key a "min-max" key
	 * @param defaultValue its default
	 * @param lowest the lowest value allowed
	 * @return {min, max}
	 */
	private static int[] parseRange(ConfigReader config, String key, String defaultValue, int lowest)
	{
		final String value = config.getString(key, defaultValue);
		try
		{
			final String[] range = value.split("-");
			final int min = Math.max(lowest, Integer.parseInt(range[0].trim()));
			return new int[]
			{
				min,
				Math.max(min, Integer.parseInt(range[range.length - 1].trim()))
			};
		}
		catch (Exception e)
		{
			LOGGER.warning("Invalid " + key + ": " + value);
			final String[] range = defaultValue.split("-");
			return new int[]
			{
				Integer.parseInt(range[0]),
				Integer.parseInt(range[1])
			};
		}
	}

	/**
	 * @param value an RGB color, like an HTML one ("00CCFF")
	 * @return the color as the client wants it (BGR)
	 */
	private static int parseColor(String value)
	{
		try
		{
			final int rgb = Integer.decode("0x" + value.trim().replace("#", ""));
			return ((rgb & 0xFF) << 16) | (rgb & 0xFF00) | ((rgb >> 16) & 0xFF);
		}
		catch (Exception e)
		{
			LOGGER.warning("Invalid PvpSpotLeaderTitleColor: " + value);
			return 0xFFCC00;
		}
	}
}
