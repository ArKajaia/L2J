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
import java.util.HashSet;
import java.util.List;
import java.util.Set;
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
	/** The maps a registration picks from, by id (data/maps), empty for any map ticked for Team vs Team (see EventMapData). */
	public static List<String> MAPS;
	public static int SPAWN_RADIUS;
	public static int BASE_RADIUS;
	/** Players coming this close to the edge of an enemy base are warned that it stuns them (see TeamVsTeamManager). */
	public static int BASE_WARNING_DISTANCE;
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

	// Deathmatch: every one for themselves on the TvT maps (see TeamVsTeamManager.Mode#DEATHMATCH).
	public static boolean DM_ENABLED;
	/** Fighters per match, players and fake players. */
	public static int DM_SIZE;
	public static int DM_FIGHT_TIME;
	public static int DM_RESPAWN_DELAY;
	/** The maps a Deathmatch registration picks from, by id (data/maps), empty for any map ticked for Deathmatch. */
	public static List<String> DM_MAPS;
	/** The reward of the best player (the fake players left out of the places). */
	public static List<ItemHolder> DM_WIN_REWARD;
	/** What a player gets for each kill in a Deathmatch of either kind, on top of {@link #KILL_REWARDS}. */
	public static List<ItemHolder> DM_KILL_REWARD;
	/** The extra rewards of the best players, first place first: Crystal Scrolls of their grade. */
	public static List<TopReward> DM_TOP_REWARDS;
	public static int DM_NAME_COLOR;
	/** The plain Deathmatch gets Deathmatch: Mayhem's stat balance too (the Mayhem* M.Atk, P.Atk, MP cost, HP, Speed and area damage settings; not the fake players' late auto attacks). */
	public static boolean DM_BALANCE;

	// Deathmatch: Mayhem (the Passive Deathmatch in the code): a Deathmatch with blank passive trees, a passive picked on each kill (see PassiveDeathmatchManager). Its size, time, respawn and rewards are the Deathmatch ones.
	public static boolean PDM_ENABLED;
	/** The maps a Mayhem registration picks from, by id (data/maps), empty for {@link #DM_MAPS}. */
	public static List<String> PDM_MAPS;
	/** How many passives a kill offers to pick 1 from. */
	public static int PDM_CHOICES;
	/** How many of them are of use to the fighter's role, at least (when the pool has so many). */
	public static int PDM_USEFUL_CHOICES;
	/** % of their damage area skills deal to the fighters. */
	public static int PDM_AREA_DAMAGE;
	/** % of their M.Atk the fighters have. */
	public static int PDM_MATK;
	/** % of their P.Atk fighters, tanks and archers have (daggers and mages keep theirs). */
	public static int PDM_PATK;
	/** % of their P.Atk daggers have. */
	public static int PDM_DAGGER_PATK;
	/** Classes (PlayerClass names) left out of {@link #PDM_PATK}. */
	public static Set<String> PDM_PATK_EXCLUDED;
	/** Speed archers get. */
	public static int PDM_ARCHER_SPEED;
	/** Classes (PlayerClass names) that play as tanks in this mode (their offers, HP, notables and keystones). */
	public static Set<String> PDM_TANK_CLASSES;
	/** A fighter's first offer and every this many after it holds one of its own event keystones (0: never). */
	public static int PDM_KEYSTONE_EVERY;
	/** Notables (names, as in the tree) a fake player always takes when offered one of use to it: the ones that raised kills most in the simulations. */
	public static Set<String> PDM_PRIORITY_NOTABLES;
	/** A fighter or tank fake player with this many kills or more uses its attack skills {@link #PDM_LATE_SKILL_CHANCE} % as often, auto attacking more (0: never). */
	public static int PDM_LATE_KILLS;
	public static int PDM_LATE_SKILL_CHANCE;
	/** How effective the notables of mages and of tanks are, in %. */
	public static int PDM_MAGE_NOTABLES;
	public static int PDM_TANK_NOTABLES;
	/** % of their skills' MP cost everyone pays. */
	public static int PDM_MP_COST;
	/** % of their max HP tanks and dwarves have. */
	public static int PDM_TANK_HP;
	/** Classes (PlayerClass names) offered magic and fighter passives both: {@link #PDM_HYBRID_MAGIC} % of the passives of use to them are a mage's, the rest a fighter's. */
	public static Set<String> PDM_HYBRID_CLASSES;
	public static int PDM_HYBRID_MAGIC;
	/** % more the notables of those classes give. */
	public static int PDM_HYBRID_NOTABLE_BONUS;
	/** An assist (a pick too): at least this % of the damage the victim took in the last {@link #PDM_ASSIST_WINDOW} seconds, 0 for no assists. */
	public static int PDM_ASSIST_SHARE;
	public static int PDM_ASSIST_WINDOW;
	/** The node types the offered passives are of (PassiveNode.NodeType names: NOTABLE, KEYSTONE, MASTER...), upper case. */
	public static Set<String> PDM_NODE_TYPES;
	/** A node with any of these effect keys (their base key, without the @condition) is never offered: loot, PvE-only damage... */
	public static Set<String> PDM_EXCLUDED_EFFECTS;

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

		final List<String> maps = new ArrayList<>();
		for (String id : config.getString("TvTMaps", "").split(","))
		{
			if (!id.isBlank())
			{
				maps.add(id.trim().toLowerCase());
			}
		}
		MAPS = Collections.unmodifiableList(maps);
		SPAWN_RADIUS = Math.max(0, config.getInt("TvTSpawnRadius", 150));
		BASE_RADIUS = Math.max(100, config.getInt("TvTBaseRadius", 700));
		BASE_WARNING_DISTANCE = Math.max(0, config.getInt("TvTBaseWarningDistance", 500));
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

		WIN_REWARD = parseItems(config.getString("TvTWinReward", "6393,10"), "TvTWinReward");

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

		DM_ENABLED = config.getBoolean("DeathmatchEnabled", true);
		DM_SIZE = Math.max(2, Math.min(100, config.getInt("DeathmatchSize", 20)));
		DM_FIGHT_TIME = Math.max(1, config.getInt("DeathmatchFightTime", 20));
		DM_RESPAWN_DELAY = Math.max(1, config.getInt("DeathmatchRespawnDelay", 10));
		final List<String> dmMaps = new ArrayList<>();
		for (String id : config.getString("DeathmatchMaps", "").split(","))
		{
			if (!id.isBlank())
			{
				dmMaps.add(id.trim().toLowerCase());
			}
		}
		DM_MAPS = Collections.unmodifiableList(dmMaps);
		DM_WIN_REWARD = parseItems(config.getString("DeathmatchWinReward", "6393,10"), "DeathmatchWinReward");
		DM_KILL_REWARD = parseItems(config.getString("DeathmatchKillReward", "6393,1"), "DeathmatchKillReward");
		DM_TOP_REWARDS = parseTopRewards(config.getString("DeathmatchTopRewards", "weapon,1;armor,2;armor,1"), "DeathmatchTopRewards");
		DM_NAME_COLOR = Integer.decode("0x" + config.getString("DeathmatchNameColor", "FFFFFF"));
		DM_BALANCE = config.getBoolean("DeathmatchBalance", true);

		PDM_ENABLED = config.getBoolean("MayhemEnabled", config.getBoolean("PassiveDeathmatchEnabled", true));
		final List<String> pdmMaps = new ArrayList<>();
		for (String id : config.getString("MayhemMaps", config.getString("PassiveDeathmatchMaps", "")).split(","))
		{
			if (!id.isBlank())
			{
				pdmMaps.add(id.trim().toLowerCase());
			}
		}
		PDM_MAPS = Collections.unmodifiableList(pdmMaps);
		PDM_CHOICES = Math.max(1, Math.min(5, config.getInt("MayhemChoices", config.getInt("PassiveDeathmatchChoices", 3))));
		PDM_USEFUL_CHOICES = Math.max(0, Math.min(PDM_CHOICES, config.getInt("MayhemUsefulChoices", 2)));
		PDM_AREA_DAMAGE = Math.max(0, config.getInt("MayhemAreaDamage", 70));
		PDM_MATK = Math.max(0, config.getInt("MayhemMAtk", 70));
		PDM_PATK = Math.max(0, config.getInt("MayhemPAtk", 115));
		PDM_DAGGER_PATK = Math.max(0, config.getInt("MayhemDaggerPAtk", 85));
		PDM_PATK_EXCLUDED = parseNames(config.getString("MayhemPAtkExcludedClasses", "GRAND_KHAVATARI"));
		PDM_ARCHER_SPEED = config.getInt("MayhemArcherSpeed", 20);
		PDM_TANK_CLASSES = parseNames(config.getString("MayhemTankClasses", "SPECTRAL_DANCER,JUDICATOR"));
		PDM_KEYSTONE_EVERY = Math.max(0, config.getInt("MayhemKeystoneEvery", 4));
		final Set<String> priority = new HashSet<>();
		for (String name : config.getString("MayhemFakePriorityNotables", "Quickened Mind;Backstabber;Aegis of Dawn;Radiant Smite;Untouched;Starfire;Pillar of Grace;Devout Disciple;Archon's Insight;Spell Surge").split(";"))
		{
			if (!name.isBlank())
			{
				priority.add(name.trim());
			}
		}
		PDM_PRIORITY_NOTABLES = Collections.unmodifiableSet(priority);
		PDM_LATE_KILLS = Math.max(0, config.getInt("MayhemFakeAutoAttackKills", 10));
		PDM_LATE_SKILL_CHANCE = Math.max(0, Math.min(100, config.getInt("MayhemFakeAutoAttackSkillChance", 75)));
		PDM_MAGE_NOTABLES = Math.max(0, config.getInt("MayhemMageNotables", 70));
		PDM_TANK_NOTABLES = Math.max(0, config.getInt("MayhemTankNotables", 120));
		PDM_MP_COST = Math.max(0, config.getInt("MayhemMpCost", 30));
		PDM_TANK_HP = Math.max(0, config.getInt("MayhemTankHp", 115));
		PDM_HYBRID_CLASSES = parseNames(config.getString("MayhemHybridClasses", "MALE_SOUL_HOUND,FEMALE_SOUL_HOUND"));
		PDM_HYBRID_MAGIC = Math.max(0, Math.min(100, config.getInt("MayhemHybridMagicShare", 70)));
		PDM_HYBRID_NOTABLE_BONUS = Math.max(0, config.getInt("MayhemHybridNotableBonus", 20));
		PDM_ASSIST_SHARE = Math.max(0, Math.min(100, config.getInt("MayhemAssistShare", 30)));
		PDM_ASSIST_WINDOW = Math.max(1, config.getInt("MayhemAssistWindow", 10));
		final Set<String> nodeTypes = new HashSet<>();
		for (String type : config.getString("MayhemNodeTypes", config.getString("PassiveDeathmatchNodeTypes", "NOTABLE,KEYSTONE")).split(","))
		{
			if (!type.isBlank())
			{
				nodeTypes.add(type.trim().toUpperCase());
			}
		}
		if (nodeTypes.isEmpty())
		{
			nodeTypes.add("NOTABLE");
			nodeTypes.add("KEYSTONE");
		}
		PDM_NODE_TYPES = Collections.unmodifiableSet(nodeTypes);
		final Set<String> excluded = new HashSet<>();
		for (String key : config.getString("MayhemExcludedEffects", config.getString("PassiveDeathmatchExcludedEffects", "DROP_RATE_PCT,SPOIL_RATE_PCT,ADENA_RATE_PCT,EXP_RATE_PCT,SP_RATE_PCT,PVE_PDMG_PCT,PVE_MDMG_PCT,PVE_BOW_DMG_PCT")).split(","))
		{
			if (!key.isBlank())
			{
				excluded.add(key.trim().toUpperCase());
			}
		}
		PDM_EXCLUDED_EFFECTS = Collections.unmodifiableSet(excluded);
	}

	/**
	 * @param text names separated by ,
	 * @return them, upper case
	 */
	private static Set<String> parseNames(String text)
	{
		final Set<String> names = new HashSet<>();
		for (String name : text.split(","))
		{
			if (!name.isBlank())
			{
				names.add(name.trim().toUpperCase());
			}
		}
		return Collections.unmodifiableSet(names);
	}

	/**
	 * @param text items: itemId,count;itemId,count...
	 * @param key the config key, for the warnings
	 * @return the items
	 */
	private static List<ItemHolder> parseItems(String text, String key)
	{
		final List<ItemHolder> items = new ArrayList<>();
		for (String part : text.split(";"))
		{
			final String[] values = part.trim().split(",");
			if (values.length == 2)
			{
				try
				{
					items.add(new ItemHolder(Integer.parseInt(values[0].trim()), Long.parseLong(values[1].trim())));
					continue;
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
		return Collections.unmodifiableList(items);
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
