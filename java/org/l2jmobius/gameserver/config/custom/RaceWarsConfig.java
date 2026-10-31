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

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.logging.Logger;

import org.l2jmobius.commons.util.ConfigReader;
import org.l2jmobius.gameserver.model.item.holders.ItemHolder;

/**
 * Race Wars: race against race elimination (see {@code RaceWarsManager}).
 */
public class RaceWarsConfig
{
	private static final Logger LOGGER = Logger.getLogger(RaceWarsConfig.class.getName());

	// File
	private static final String RACE_WARS_CONFIG_FILE = "./config/Custom/RaceWars.ini";

	public static boolean ENABLED;
	/** The maps a registration may pick (ids of data/maps), empty for any map ticked for Race Wars. */
	public static List<String> MAPS;
	public static int REGISTRATION_TIME;
	public static int PREP_TIME;
	public static int ARMY_SIZE;
	public static int PARTY_SIZE;
	public static int MIN_PLAYERS;
	public static List<int[]> BRACKETS;
	public static int MONSTER_TIME;
	public static int MONSTER_COUNT;
	public static int MONSTER_TIER;
	public static int MONSTER_AGGRO_RANGE;
	public static int MONSTER_KINDS;
	public static int HARD_LIMIT;
	public static int FINAL_DELAY;
	public static List<ItemHolder> WIN_REWARD;
	/** What each player of a race that lost the final gets. */
	public static List<ItemHolder> FINAL_LOSE_REWARD;
	public static int SPAWN_RADIUS;
	public static int BASE_RADIUS;

	public static void load()
	{
		final ConfigReader config = new ConfigReader(RACE_WARS_CONFIG_FILE);
		ENABLED = config.getBoolean("RaceWarsEnabled", true);
		final List<String> maps = new ArrayList<>();
		for (String id : config.getString("RaceWarsMaps", "").split(","))
		{
			if (!id.isBlank())
			{
				maps.add(id.trim().toLowerCase());
			}
		}
		MAPS = Collections.unmodifiableList(maps);
		REGISTRATION_TIME = Math.max(1, config.getInt("RaceWarsRegistrationTime", 10));
		PREP_TIME = Math.max(5, config.getInt("RaceWarsPrepTime", 60));
		ARMY_SIZE = Math.max(1, Math.min(100, config.getInt("RaceWarsArmySize", 20)));
		PARTY_SIZE = Math.max(2, Math.min(9, config.getInt("RaceWarsPartySize", 7)));
		MIN_PLAYERS = Math.max(1, config.getInt("RaceWarsMinPlayers", 1));
		MONSTER_TIME = Math.max(1, config.getInt("RaceWarsMonsterTime", 10));
		MONSTER_COUNT = Math.max(0, Math.min(500, config.getInt("RaceWarsMonsterCount", 100)));
		MONSTER_TIER = Math.max(0, Math.min(3, config.getInt("RaceWarsMonsterTier", 3)));
		MONSTER_AGGRO_RANGE = Math.max(300, config.getInt("RaceWarsMonsterAggroRange", 3000));
		MONSTER_KINDS = Math.max(1, config.getInt("RaceWarsMonsterKinds", 4));
		HARD_LIMIT = Math.max(1, config.getInt("RaceWarsHardLimit", 5));
		FINAL_DELAY = Math.max(5, config.getInt("RaceWarsFinalDelay", 30));
		SPAWN_RADIUS = Math.max(0, config.getInt("RaceWarsSpawnRadius", 150));
		BASE_RADIUS = Math.max(100, config.getInt("RaceWarsBaseRadius", 700));

		final List<int[]> brackets = new ArrayList<>();
		for (String part : config.getString("RaceWarsBrackets", "41-45;46-50;51-55;56-60;61-65;66-70;71-75;76-78;79-81;82-84;85-85").split(";"))
		{
			final String[] range = part.trim().split("-");
			try
			{
				final int min = Integer.parseInt(range[0].trim());
				final int max = range.length > 1 ? Integer.parseInt(range[1].trim()) : min;
				brackets.add(new int[]
				{
					Math.min(min, max),
					Math.max(min, max)
				});
			}
			catch (Exception e)
			{
				LOGGER.warning(RaceWarsConfig.class.getSimpleName() + ": Bad bracket \"" + part + "\" in RaceWarsBrackets.");
			}
		}
		brackets.sort((a, b) -> Integer.compare(a[0], b[0]));
		BRACKETS = Collections.unmodifiableList(brackets);

		WIN_REWARD = parseRewards(config, "RaceWarsWinReward", "6509,10");
		FINAL_LOSE_REWARD = parseRewards(config, "RaceWarsFinalLoseReward", "6509,3");
	}

	/**
	 * @param config the config
	 * @param key a key of items, id,count;id,count
	 * @param defaultValue its default
	 * @return the items
	 */
	private static List<ItemHolder> parseRewards(ConfigReader config, String key, String defaultValue)
	{
		final List<ItemHolder> rewards = new ArrayList<>();
		for (String part : config.getString(key, defaultValue).split(";"))
		{
			final String[] values = part.trim().split(",");
			try
			{
				if (values.length == 2)
				{
					rewards.add(new ItemHolder(Integer.parseInt(values[0].trim()), Long.parseLong(values[1].trim())));
					continue;
				}
			}
			catch (NumberFormatException e)
			{
				// Reported below.
			}
			if (!part.isBlank())
			{
				LOGGER.warning(RaceWarsConfig.class.getSimpleName() + ": Bad reward \"" + part + "\" in " + key + ".");
			}
		}
		return Collections.unmodifiableList(rewards);
	}
}
