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

import java.time.DayOfWeek;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.logging.Logger;

import org.l2jmobius.commons.util.ConfigReader;
import org.l2jmobius.gameserver.model.item.holders.ItemHolder;

/**
 * Loads the Town Life configuration (see {@link org.l2jmobius.gameserver.managers.TownLifeManager}): the npcs that go about their day in the towns and follow the day/night cycle.
 */
public class TownLifeConfig
{
	private static final Logger LOGGER = Logger.getLogger(TownLifeConfig.class.getName());
	
	// File
	private static final String TOWN_LIFE_CONFIG_FILE = "./config/Custom/TownLife.ini";
	
	public static boolean ENABLED;
	public static double POPULATION;
	
	// Daytime
	public static int WANDERERS;
	public static int WORKERS;
	public static int PATROLS;
	public static boolean CHILDREN_ENABLED;
	public static int CHILDREN;
	
	// Day and night
	public static boolean GO_HOME;
	public static boolean RETAIL_GO_HOME;
	public static boolean LAMPLIGHTER;
	public static int NIGHT_WATCH;
	public static int TAVERN_CROWD;
	
	// Ambience
	public static boolean CRIER;
	public static int CRIER_INTERVAL;
	public static int CRIER_RANGE;
	public static boolean CHATTER;
	public static boolean GREET_PLAYERS;
	public static int GREET_CHANCE;
	public static boolean HARBOR_ENABLED;
	public static int FISHERMEN;
	public static int DOCK_WORKERS;
	
	// Extras
	public static boolean KIDS_TREATS;
	public static long TREAT_PRICE;
	public static List<ItemHolder> TREAT_GIFTS;
	public static boolean FESTIVAL;
	public static DayOfWeek FESTIVAL_DAY;
	public static double FESTIVAL_POPULATION;
	
	public static void load()
	{
		final ConfigReader config = new ConfigReader(TOWN_LIFE_CONFIG_FILE);
		ENABLED = config.getBoolean("TownLifeEnabled", true);
		POPULATION = Math.max(0, Math.min(5, config.getDouble("TownLifePopulation", 1.0)));
		
		WANDERERS = Math.max(0, Math.min(40, config.getInt("TownLifeWanderers", 8)));
		WORKERS = Math.max(0, Math.min(20, config.getInt("TownLifeWorkers", 3)));
		PATROLS = Math.max(0, Math.min(10, config.getInt("TownLifePatrols", 1)));
		CHILDREN_ENABLED = config.getBoolean("TownLifeChildrenEnabled", true);
		CHILDREN = Math.max(0, Math.min(12, config.getInt("TownLifeChildren", 4)));
		
		GO_HOME = config.getBoolean("TownLifeGoHome", true);
		RETAIL_GO_HOME = config.getBoolean("TownLifeRetailGoHome", true);
		LAMPLIGHTER = config.getBoolean("TownLifeLamplighter", true);
		NIGHT_WATCH = Math.max(0, Math.min(10, config.getInt("TownLifeNightWatch", 2)));
		TAVERN_CROWD = Math.max(0, Math.min(20, config.getInt("TownLifeTavernCrowd", 6)));
		
		CRIER = config.getBoolean("TownLifeCrier", true);
		CRIER_INTERVAL = Math.max(1, config.getInt("TownLifeCrierInterval", 4));
		CRIER_RANGE = Math.max(300, config.getInt("TownLifeCrierRange", 2500));
		CHATTER = config.getBoolean("TownLifeChatter", true);
		GREET_PLAYERS = config.getBoolean("TownLifeGreetPlayers", true);
		GREET_CHANCE = Math.max(0, Math.min(100, config.getInt("TownLifeGreetChance", 25)));
		HARBOR_ENABLED = config.getBoolean("TownLifeHarborEnabled", true);
		FISHERMEN = Math.max(0, Math.min(12, config.getInt("TownLifeFishermen", 4)));
		DOCK_WORKERS = Math.max(0, Math.min(12, config.getInt("TownLifeDockWorkers", 3)));
		
		KIDS_TREATS = config.getBoolean("TownLifeKidsTreats", true);
		TREAT_PRICE = Math.max(0, config.getLong("TownLifeTreatPrice", 500));
		TREAT_GIFTS = parseGifts(config.getString("TownLifeTreatGifts", "6406,1;6407,1;6403,3"));
		FESTIVAL = config.getBoolean("TownLifeFestival", true);
		FESTIVAL_DAY = config.getEnum("TownLifeFestivalDay", DayOfWeek.class, DayOfWeek.SATURDAY);
		FESTIVAL_POPULATION = Math.max(1, Math.min(3, config.getDouble("TownLifeFestivalPopulation", 1.5)));
	}
	
	/**
	 * @param value itemId,count;itemId,count...
	 * @return the gifts, invalid entries left out with a warning
	 */
	private static List<ItemHolder> parseGifts(String value)
	{
		final List<ItemHolder> gifts = new ArrayList<>();
		for (String entry : value.split(";"))
		{
			final String trimmed = entry.trim();
			if (trimmed.isEmpty())
			{
				continue;
			}
			
			final String[] parts = trimmed.split(",");
			try
			{
				final int itemId = Integer.parseInt(parts[0].trim());
				final long count = parts.length > 1 ? Long.parseLong(parts[1].trim()) : 1;
				if ((itemId > 0) && (count > 0))
				{
					gifts.add(new ItemHolder(itemId, count));
				}
			}
			catch (NumberFormatException e)
			{
				LOGGER.warning("TownLifeConfig: Invalid treat gift " + trimmed + ".");
			}
		}
		return Collections.unmodifiableList(gifts);
	}
}
