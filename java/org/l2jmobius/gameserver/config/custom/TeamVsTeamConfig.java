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
import java.util.Collections;
import java.util.List;
import java.util.logging.Logger;

import org.l2jmobius.commons.util.ConfigReader;
import org.l2jmobius.gameserver.model.Location;
import org.l2jmobius.gameserver.model.actor.Player;
import org.l2jmobius.gameserver.model.item.holders.ItemChanceHolder;
import org.l2jmobius.gameserver.model.item.holders.ItemHolder;

/**
 * Loads the Team vs Team event configuration (see {@link org.l2jmobius.gameserver.managers.TeamVsTeamManager}).
 */
public class TeamVsTeamConfig
{
	private static final Logger LOGGER = Logger.getLogger(TeamVsTeamConfig.class.getName());

	// File
	private static final String TEAM_VS_TEAM_CONFIG_FILE = "./config/Custom/TeamVsTeam.ini";

	public static boolean ENABLED;
	public static String SCHEDULE;
	public static int REGISTRATION_TIME;
	public static int PREP_TIME;
	public static int FIGHT_TIME;
	public static int TEAM_SIZE;
	public static int PARTY_SIZE;
	public static int MIN_PLAYERS;
	/** The level brackets, lowest first: {min, max} each. */
	public static List<int[]> BRACKETS;
	/** The spawn points of the teams: each team starts at its first one, its dead come back at one of them (see TeamVsTeamManager). */
	public static List<Location> BLUE_BASES;
	public static List<Location> RED_BASES;
	/** The first spawn point of each team, where it starts ({@code null} for none). */
	public static Location BLUE_BASE;
	public static Location RED_BASE;
	public static int SPAWN_RADIUS;
	public static int BASE_RADIUS;
	/** Players coming this close to the edge of an enemy base are warned that it stuns them (see TeamVsTeamManager). */
	public static int BASE_WARNING_DISTANCE;
	/** The fence round the field: {middle x, middle y, radius along x, radius along y} of an ellipse, {@code null} for none (see TeamVsTeamBarrier). */
	public static int[] BARRIER;
	/** The battlefield's edge on the players' radar: minX, minY, maxX, maxY, or {@code null} for around the bases. */
	public static int[] AREA;
	public static int RESPAWN_DELAY;
	public static int SPAWN_PROTECTION;
	public static List<ItemChanceHolder> KILL_REWARDS;
	public static List<ItemHolder> WIN_REWARD;
	/** The extra rewards of the best players of the winning team, first place first: Crystal Scrolls of their grade (see TeamVsTeamManager). */
	public static List<TopReward> TOP_REWARDS;
	public static List<Location> MANAGER_LOCATIONS;
	public static int BLUE_NAME_COLOR;
	public static int RED_NAME_COLOR;
	public static boolean BUFF_TEMPLATE;
	/** The buffs players may put in their TvT Event buff template: {skill id, level}, level 0 for the skill's highest. */
	public static List<int[]> BUFFS;

	public static void load()
	{
		final ConfigReader config = new ConfigReader(TEAM_VS_TEAM_CONFIG_FILE);
		ENABLED = config.getBoolean("TvTEnabled", true);
		SCHEDULE = config.getString("TvTSchedule", "0 */2 * * *");
		REGISTRATION_TIME = Math.max(1, config.getInt("TvTRegistrationTime", 10));
		PREP_TIME = Math.max(5, config.getInt("TvTPrepTime", 60));
		FIGHT_TIME = Math.max(1, config.getInt("TvTFightTime", 20));
		TEAM_SIZE = Math.max(1, Math.min(100, config.getInt("TvTTeamSize", 20)));
		PARTY_SIZE = Math.max(2, Math.min(9, config.getInt("TvTPartySize", 7)));
		MIN_PLAYERS = Math.max(1, config.getInt("TvTMinPlayers", 1));

		final List<int[]> brackets = new ArrayList<>();
		for (String part : config.getString("TvTBrackets", "41-45;46-50;51-55;56-60;61-65;66-70;71-75;76-78;79-81;82-84;85-85").split(";"))
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
				LOGGER.warning(TeamVsTeamConfig.class.getSimpleName() + ": Bad bracket \"" + part + "\" in TvTBrackets.");
			}
		}
		brackets.sort((a, b) -> Integer.compare(a[0], b[0]));
		BRACKETS = Collections.unmodifiableList(brackets);

		BLUE_BASES = parseLocations(config.getString("TvTBlueBase", "-17200,147000,-3704,46757;-19800,146600,-3736,52784;-21600,145400,-3760,57879"), "TvTBlueBase");
		RED_BASES = parseLocations(config.getString("TvTRedBase", "-20200,138700,-3896,11380;-16400,138400,-3792,20956;-14600,139400,-3792,25627"), "TvTRedBase");
		BLUE_BASE = BLUE_BASES.isEmpty() ? null : BLUE_BASES.get(0);
		RED_BASE = RED_BASES.isEmpty() ? null : RED_BASES.get(0);
		SPAWN_RADIUS = Math.max(0, config.getInt("TvTSpawnRadius", 150));
		BASE_RADIUS = Math.max(100, config.getInt("TvTBaseRadius", 700));
		BASE_WARNING_DISTANCE = Math.max(0, config.getInt("TvTBaseWarningDistance", 500));
		BARRIER = null;
		final String barrier = config.getString("TvTBarrier", "-17800,142600,5900,5300").trim();
		if (!barrier.isEmpty())
		{
			final String[] values = barrier.split(",");
			try
			{
				if (values.length != 4)
				{
					throw new NumberFormatException();
				}
				final int[] parsed = new int[4];
				for (int i = 0; i < 4; i++)
				{
					parsed[i] = Integer.parseInt(values[i].trim());
				}
				if ((parsed[2] < 500) || (parsed[3] < 500))
				{
					throw new NumberFormatException();
				}
				BARRIER = parsed;
			}
			catch (NumberFormatException e)
			{
				LOGGER.warning(TeamVsTeamConfig.class.getSimpleName() + ": Bad TvTBarrier \"" + barrier + "\" (x,y,radius x,radius y, radii at least 500): no barrier.");
			}
		}
		AREA = null;
		final String area = config.getString("TvTArea", "").trim();
		if (!area.isEmpty())
		{
			try
			{
				final String[] parts = area.split(",");
				final int minX = Integer.parseInt(parts[0].trim());
				final int minY = Integer.parseInt(parts[1].trim());
				final int maxX = Integer.parseInt(parts[2].trim());
				final int maxY = Integer.parseInt(parts[3].trim());
				if ((parts.length == 4) && (minX < maxX) && (minY < maxY))
				{
					AREA = new int[]
					{
						minX,
						minY,
						maxX,
						maxY
					};
				}
				else
				{
					LOGGER.warning(TeamVsTeamConfig.class.getSimpleName() + ": Bad TvTArea \"" + area + "\", the area around the bases is shown.");
				}
			}
			catch (Exception e)
			{
				LOGGER.warning(TeamVsTeamConfig.class.getSimpleName() + ": Bad TvTArea \"" + area + "\", the area around the bases is shown.");
			}
		}
		RESPAWN_DELAY = Math.max(1, config.getInt("TvTRespawnDelay", 10));
		SPAWN_PROTECTION = Math.max(0, config.getInt("TvTSpawnProtection", 15));

		final List<ItemChanceHolder> killRewards = new ArrayList<>();
		for (String part : config.getString("TvTKillRewards", "6492,1,80;6499,1,17;6509,1,3").split(";"))
		{
			final String[] values = part.trim().split(",");
			if (values.length == 3)
			{
				try
				{
					killRewards.add(new ItemChanceHolder(Integer.parseInt(values[0].trim()), Double.parseDouble(values[2].trim()), Long.parseLong(values[1].trim())));
					continue;
				}
				catch (NumberFormatException e)
				{
					// Reported below.
				}
			}
			if (!part.isBlank())
			{
				LOGGER.warning(TeamVsTeamConfig.class.getSimpleName() + ": Bad reward \"" + part + "\" in TvTKillRewards.");
			}
		}
		KILL_REWARDS = Collections.unmodifiableList(killRewards);

		final List<ItemHolder> winReward = new ArrayList<>();
		for (String part : config.getString("TvTWinReward", "6393,10").split(";"))
		{
			final String[] values = part.trim().split(",");
			if (values.length == 2)
			{
				try
				{
					winReward.add(new ItemHolder(Integer.parseInt(values[0].trim()), Long.parseLong(values[1].trim())));
					continue;
				}
				catch (NumberFormatException e)
				{
					// Reported below.
				}
			}
			if (!part.isBlank())
			{
				LOGGER.warning(TeamVsTeamConfig.class.getSimpleName() + ": Bad reward \"" + part + "\" in TvTWinReward.");
			}
		}
		WIN_REWARD = Collections.unmodifiableList(winReward);

		TOP_REWARDS = parseTopRewards(config.getString("TvTTopRewards", "weapon,1;armor,2;armor,1"), "TvTTopRewards");

		final List<Location> managers = new ArrayList<>();
		for (String part : config.getString("TvTManagerLocations", "83425,148585,-3406,32938").split(";"))
		{
			if (!part.isBlank())
			{
				final Location location = parseLocation(part, "TvTManagerLocations");
				if (location != null)
				{
					managers.add(location);
				}
			}
		}
		MANAGER_LOCATIONS = Collections.unmodifiableList(managers);

		BLUE_NAME_COLOR = Integer.decode("0x" + config.getString("TvTBlueNameColor", "FF9F7F"));
		RED_NAME_COLOR = Integer.decode("0x" + config.getString("TvTRedNameColor", "5F5FFF"));
		
		BUFF_TEMPLATE = config.getBoolean("TvTBuffTemplate", true);
		final List<int[]> buffs = new ArrayList<>();
		for (String part : config.getString("TvTBuffs", "1204;1068;1040;1036;1035;1045;1048;1086;1085;1062;1077;1240;1242;1268;1059;1303;1078;1087;1397;1032;1073;1044;1182;1191;1189;1033;1392;1393;271;272;273;274;275;310;264;265;266;267;268;269;304;349;363;364").split(";"))
		{
			if (part.isBlank())
			{
				continue;
			}
			
			final String[] values = part.trim().split(",");
			try
			{
				buffs.add(new int[]
				{
					Integer.parseInt(values[0].trim()),
					values.length > 1 ? Math.max(0, Integer.parseInt(values[1].trim())) : 0
				});
			}
			catch (NumberFormatException e)
			{
				LOGGER.warning(TeamVsTeamConfig.class.getSimpleName() + ": Bad buff \"" + part + "\" in TvTBuffs.");
			}
		}
		BUFFS = Collections.unmodifiableList(buffs);
	}

	/**
	 * The reward of one place of the winning team: {@code count} Crystal Scrolls of the player's grade, for weapons or for armor.
	 * @param weapon {@code true} for Crystal Scroll: Enchant Weapon, {@code false} for Crystal Scroll: Enchant Armor
	 * @param count how many
	 */
	public record TopReward(boolean weapon, int count)
	{
		/** Crystal Scroll: Enchant Weapon and Enchant Armor of each expertise level (none, D, C, B, A, S, S80, S84): S80 and S84 items take the S-grade ones. */
		private static final int[][] CRYSTAL_SCROLLS =
		{
			{
				957,
				958
			},
			{
				957,
				958
			},
			{
				953,
				954
			},
			{
				949,
				950
			},
			{
				731,
				732
			},
			{
				961,
				962
			},
			{
				961,
				962
			},
			{
				961,
				962
			}
		};

		/**
		 * @param player a player
		 * @return the Crystal Scroll of its grade (its expertise)
		 */
		public int getItemId(Player player)
		{
			final int expertise = Math.max(0, Math.min(CRYSTAL_SCROLLS.length - 1, player.getExpertiseLevel()));
			return CRYSTAL_SCROLLS[expertise][weapon ? 0 : 1];
		}

		/**
		 * @param rewards the rewards of the places, first place first
		 * @return them as text: "1st place 1 Crystal Scroll: Enchant Weapon, 2nd place..." (empty for none)
		 */
		public static String describe(List<TopReward> rewards)
		{
			final StringBuilder sb = new StringBuilder();
			for (int i = 0; i < rewards.size(); i++)
			{
				final TopReward reward = rewards.get(i);
				sb.append(sb.length() > 0 ? ", " : "").append(ordinal(i + 1)).append(" place ").append(reward.count()).append(reward.weapon() ? " Crystal Scroll: Enchant Weapon" : " Crystal Scroll: Enchant Armor");
			}
			return sb.toString();
		}

		private static String ordinal(int place)
		{
			final int lastTwo = place % 100;
			final int last = place % 10;
			return place + (((lastTwo >= 11) && (lastTwo <= 13)) ? "th" : (last == 1 ? "st" : (last == 2 ? "nd" : (last == 3 ? "rd" : "th"))));
		}
	}

	/**
	 * @param text the rewards of the places, first place first: weapon,count;armor,count;...
	 * @param key the config key, for the warnings
	 * @return the rewards
	 */
	static List<TopReward> parseTopRewards(String text, String key)
	{
		final List<TopReward> topRewards = new ArrayList<>();
		for (String part : text.split(";"))
		{
			final String[] values = part.trim().split(",");
			if (values.length == 2)
			{
				final String kind = values[0].trim().toLowerCase();
				try
				{
					final int count = Integer.parseInt(values[1].trim());
					if ((count > 0) && (kind.equals("weapon") || kind.equals("armor")))
					{
						topRewards.add(new TopReward(kind.equals("weapon"), count));
						continue;
					}
				}
				catch (NumberFormatException e)
				{
					// Reported below.
				}
			}
			if (!part.isBlank())
			{
				LOGGER.warning(TeamVsTeamConfig.class.getSimpleName() + ": Bad reward \"" + part + "\" in " + key + ".");
			}
		}
		return Collections.unmodifiableList(topRewards);
	}

	static List<Location> parseLocations(String text, String key)
	{
		final List<Location> locations = new ArrayList<>();
		for (String part : text.split(";"))
		{
			if (!part.isBlank())
			{
				final Location location = parseLocation(part, key);
				if (location != null)
				{
					locations.add(location);
				}
			}
		}
		return Collections.unmodifiableList(locations);
	}

	private static Location parseLocation(String text, String key)
	{
		final String[] values = text.trim().split(",");
		try
		{
			return new Location(Integer.parseInt(values[0].trim()), Integer.parseInt(values[1].trim()), Integer.parseInt(values[2].trim()), values.length > 3 ? Integer.parseInt(values[3].trim()) : 0);
		}
		catch (Exception e)
		{
			LOGGER.warning(TeamVsTeamConfig.class.getSimpleName() + ": Bad location \"" + text + "\" in " + key + ".");
			return null;
		}
	}
}
