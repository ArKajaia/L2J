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
package org.l2jmobius.gameserver.managers;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.l2jmobius.commons.threads.ThreadPool;
import org.l2jmobius.commons.util.Rnd;
import org.l2jmobius.gameserver.config.custom.FakePlayersConfig;
import org.l2jmobius.gameserver.data.SpawnTable;
import org.l2jmobius.gameserver.data.xml.FakePlayerData;
import org.l2jmobius.gameserver.data.xml.FakePlayerPvpData;
import org.l2jmobius.gameserver.data.xml.MapRegionData;
import org.l2jmobius.gameserver.data.xml.SkillData;
import org.l2jmobius.gameserver.geoengine.GeoEngine;
import org.l2jmobius.gameserver.managers.FakePlayerTown.Role;
import org.l2jmobius.gameserver.managers.FakePlayerTown.TownNpc;
import org.l2jmobius.gameserver.managers.FakePlayerTownChat.Topic;
import org.l2jmobius.gameserver.model.Location;
import org.l2jmobius.gameserver.model.World;
import org.l2jmobius.gameserver.model.WorldObject;
import org.l2jmobius.gameserver.model.WorldRegion;
import org.l2jmobius.gameserver.model.actor.Npc;
import org.l2jmobius.gameserver.model.actor.Player;
import org.l2jmobius.gameserver.model.actor.enums.creature.Race;
import org.l2jmobius.gameserver.model.actor.enums.player.PlayerClass;
import org.l2jmobius.gameserver.model.actor.holders.npc.FakePlayerPvpBuild;
import org.l2jmobius.gameserver.model.actor.holders.npc.FakePlayerPvpBuild.SkillCategory;
import org.l2jmobius.gameserver.model.actor.templates.NpcTemplate;
import org.l2jmobius.gameserver.model.skill.Skill;
import org.l2jmobius.gameserver.model.spawns.Spawn;
import org.l2jmobius.gameserver.network.holders.RequestTrade;
import org.l2jmobius.gameserver.network.serverpackets.ActionFailed;
import org.l2jmobius.gameserver.network.serverpackets.FakePlayerPrivateStoreListSell;

/**
 * Town fake players (FakeTownPlayers* in FakePlayers.ini): peaceful fake players that come and go in the towns like players do.<br>
 * They arrive by gatekeeper, by Scroll of Escape at a respawn point or by logging in, sometimes a whole party back from a hunt saying goodbye. Then each one follows a plan of its own ({@link FakePlayerTownVisitor}): warehouse, grocer, blacksmith, shops, masters,
 * quest npcs, buffs from the Newbie or Adventurers' Guide or from a buffer of the town, a talk with others ({@link FakePlayerTownCircle}), sitting or standing around, and finally a gatekeeper, the community board teleport or logging off. Someone new arrives a while
 * later, and the population of each town drifts up and down. Town npcs and walkable spots come from the town itself ({@link FakePlayerTown}), so it works in every town without coordinates.
 */
public class FakePlayerTownManager
{
	private static final Logger LOGGER = Logger.getLogger(FakePlayerTownManager.class.getName());
	
	/** Npc ids of the town fake player templates (reused once they leave), far above any datapack id and below the roaming fake players. */
	private static final int FIRST_NPC_ID = 9_400_000;
	private static final int LAST_NPC_ID = 9_499_999;
	private static final int TICK = 250;
	/** A town buffer needs a town where players are at least this level. */
	private static final int BUFFER_MIN_LEVEL = 40;
	/** How far a fake player looks for a private store to have a look at. */
	private static final int STORE_RANGE = 1800;
	
	/** A buffer class line: the 2nd and 3rd class, the gear it wears (weapon kit of data/FakePlayerPvp.xml) and its buffs (data/stats/players/skillTrees). The Prophet and Warcryer lines aren't buffers any more (config/Custom/OraclesWarchiefs.ini). */
	enum BufferLine
	{
		// @formatter:off
		ELDER(PlayerClass.ELDER, PlayerClass.EVA_SAINT, "MAGE", false, 14,
			new int[] {1204, 1068, 1040, 1087, 1243, 1304, 1044},
			new int[] {1204, 1078, 1040, 1303, 1397, 1044},
			new int[] {1353, 1354, 1460}),
		SHILLIEN_ELDER(PlayerClass.SHILLIEN_ELDER, PlayerClass.SHILLIEN_SAINT, "MAGE", false, 14,
			new int[] {1204, 1068, 1040, 1077, 1240, 1242, 1268, 1502},
			new int[] {1204, 1059, 1078, 1040, 1303, 1500},
			new int[] {1354, 1460, 1507}),
		SWORDSINGER(PlayerClass.SWORDSINGER, PlayerClass.SWORD_MUSE, "SWORD_SHIELD", true, 13,
			new int[] {264, 265, 267, 268, 269, 304, 305, 306, 308},
			new int[] {264, 265, 266, 267, 268, 270, 304},
			new int[] {349, 363, 364, 529, 914}),
		BLADEDANCER(PlayerClass.BLADEDANCER, PlayerClass.SPECTRAL_DANCER, "DUAL", true, 13,
			new int[] {271, 274, 275, 276, 277, 307, 309, 310, 311},
			new int[] {273, 276, 277, 307, 309, 311},
			new int[] {365, 530, 765, 915});
		// @formatter:on
		
		private final PlayerClass _second;
		private final PlayerClass _third;
		private final String _weaponKit;
		private final boolean _songsOrDances;
		private final int _weight;
		private final int[] _fighterBuffs;
		private final int[] _mageBuffs;
		private final int[] _thirdClassBuffs;
		
		BufferLine(PlayerClass second, PlayerClass third, String weaponKit, boolean songsOrDances, int weight, int[] fighterBuffs, int[] mageBuffs, int[] thirdClassBuffs)
		{
			_second = second;
			_third = third;
			_weaponKit = weaponKit;
			_songsOrDances = songsOrDances;
			_weight = weight;
			_fighterBuffs = fighterBuffs;
			_mageBuffs = mageBuffs;
			_thirdClassBuffs = thirdClassBuffs;
		}
		
		boolean songsOrDances()
		{
			return _songsOrDances;
		}
		
		PlayerClass getPlayerClass(int level)
		{
			return level >= 76 ? _third : _second;
		}
		
		/**
		 * @return a build whose gear this buffer wears, {@code null} if there is none
		 */
		FakePlayerPvpBuild getGearBuild()
		{
			final List<FakePlayerPvpBuild> builds = new ArrayList<>();
			for (FakePlayerPvpBuild build : FakePlayerPvpData.getInstance().getBuilds())
			{
				if (_weaponKit.equals(build.getWeaponKit()))
				{
					builds.add(build);
				}
			}
			return builds.isEmpty() ? null : builds.get(Rnd.get(builds.size()));
		}
		
		/**
		 * @param mage {@code true} if the one asking is a mage
		 * @param level the buffer's level
		 * @return the buffs it casts this time, the main ones first, in the order a buffer goes through them
		 */
		List<Skill> pickBuffs(boolean mage, int level)
		{
			final int[] list = mage ? _mageBuffs : _fighterBuffs;
			final List<Integer> ids = new ArrayList<>();
			for (int id : list)
			{
				ids.add(id);
			}
			
			// The first three always, then some of the others.
			final List<Integer> rest = new ArrayList<>(ids.subList(Math.min(3, ids.size()), ids.size()));
			Collections.shuffle(rest);
			final List<Integer> picked = new ArrayList<>(ids.subList(0, Math.min(3, ids.size())));
			final int extra = _songsOrDances ? Rnd.get(1, 4) : Rnd.get(2, 5);
			picked.addAll(rest.subList(0, Math.min(rest.size(), extra)));
			if (level >= 76)
			{
				final int[] third = _thirdClassBuffs;
				final int count = Rnd.get(1, Math.min(2, third.length));
				for (int i = 0; i < count; i++)
				{
					final int id = third[Rnd.get(third.length)];
					if (!picked.contains(id))
					{
						picked.add(id);
					}
				}
			}
			
			final List<Skill> skills = new ArrayList<>();
			for (int id : picked)
			{
				final int skillLevel = SkillData.getInstance().getMaxLevel(id);
				final Skill skill = skillLevel > 0 ? SkillData.getInstance().getSkill(id, skillLevel) : null;
				if (skill != null)
				{
					skills.add(skill);
				}
			}
			return skills;
		}
		
		static BufferLine random()
		{
			int total = 0;
			for (BufferLine line : values())
			{
				total += line._weight;
			}
			
			int roll = Rnd.get(total);
			for (BufferLine line : values())
			{
				roll -= line._weight;
				if (roll < 0)
				{
					return line;
				}
			}
			return ELDER;
		}
	}
	
	/** How a fake player shows up in town. */
	private enum Arrival
	{
		/** Teleported in: next to the gatekeeper. */
		GATEKEEPER,
		/** Scroll of Escape, or back from death: at a respawn point. */
		RESPAWN,
		/** Logged in where it logged off. */
		LOGIN
	}
	
	/** Something to do a bit later, on the manager's own thread. */
	private static final class Timed implements Comparable<Timed>
	{
		final long time;
		final long order;
		final Runnable action;
		
		Timed(long time, long order, Runnable action)
		{
			this.time = time;
			this.order = order;
			this.action = action;
		}
		
		@Override
		public int compareTo(Timed other)
		{
			return time != other.time ? Long.compare(time, other.time) : Long.compare(order, other.order);
		}
	}
	
	private final List<FakePlayerTown> _towns = new ArrayList<>();
	private final PriorityQueue<Timed> _timeline = new PriorityQueue<>();
	private final Map<Integer, FakePlayerTownVisitor> _visitors = new ConcurrentHashMap<>();
	private final Deque<Integer> _freeNpcIds = new ArrayDeque<>();
	private final Map<Integer, Long> _browsedStores = new HashMap<>();
	/** Players a newbie asked for adena lately (object id, until when they are left alone). */
	private final Map<Integer, Long> _begged = new HashMap<>();
	private int _nextNpcId = FIRST_NPC_ID;
	private long _order;
	/** Set once the server made this manager (others ask before using it, so they never make it out of the server start order). */
	private static volatile boolean _started;
	
	protected FakePlayerTownManager()
	{
		_started = true;
		
		if (!FakePlayersConfig.FAKE_PLAYERS_ENABLED || !FakePlayersConfig.FAKE_TOWN_PLAYERS_ENABLED)
		{
			LOGGER.info(getClass().getSimpleName() + ": Disabled.");
			return;
		}
		
		if (FakePlayerPvpData.getInstance().getBuilds().isEmpty())
		{
			LOGGER.warning(getClass().getSimpleName() + ": No builds in data/FakePlayerPvp.xml, no town fake players.");
			return;
		}
		
		for (String entry : FakePlayersConfig.FAKE_TOWN_PLAYERS_TOWNS.split(","))
		{
			final FakePlayerTown town = parseTown(entry.trim());
			if (town == null)
			{
				continue;
			}
			
			town.discover();
			if (!town.isReady())
			{
				LOGGER.warning(getClass().getSimpleName() + ": No town zone or npcs found for " + town.region + ", its fake players only walk around.");
			}
			_towns.add(town);
		}
		
		LOGGER.info(getClass().getSimpleName() + ": " + _towns.size() + " towns, their fake players arrive once the town is prepared.");
		if (!_towns.isEmpty())
		{
			ThreadPool.scheduleAtFixedRate(this::tick, TICK, TICK);
			
			// The path finding work of every town, once, away from the server start (they only stand around until their town is done).
			ThreadPool.execute(() ->
			{
				final long start = System.currentTimeMillis();
				int routes = 0;
				int spots = 0;
				for (FakePlayerTown town : _towns)
				{
					try
					{
						town.prepare();
						routes += town.getRouteHubs().size();
						for (List<TownNpc> list : town.npcs.values())
						{
							for (TownNpc townNpc : list)
							{
								spots += town.getSpots(townNpc).size();
							}
						}
					}
					catch (Exception e)
					{
						LOGGER.log(Level.WARNING, getClass().getSimpleName() + ": Could not prepare " + town.region + ".", e);
					}
				}
				LOGGER.info(getClass().getSimpleName() + ": Prepared " + _towns.size() + " towns (" + routes + " street points, " + spots + " npc spots) in " + ((System.currentTimeMillis() - start) / 1000) + " seconds.");
			});
		}
	}
	
	/**
	 * @param entry region:minLevel-maxLevel[:RACE]
	 * @return the town, {@code null} if the entry is empty or invalid
	 */
	private FakePlayerTown parseTown(String entry)
	{
		if (entry.isEmpty())
		{
			return null;
		}
		
		try
		{
			final String[] parts = entry.split(":");
			final String[] levels = parts[1].split("-");
			final int minLevel = Math.max(1, Integer.parseInt(levels[0].trim()));
			final int maxLevel = Math.min(85, Integer.parseInt(levels[levels.length - 1].trim()));
			final Race race = parts.length > 2 ? Race.valueOf(parts[2].trim().toUpperCase()) : null;
			final String region = parts[0].trim();
			final List<Location> points = MapRegionData.getInstance().getSpawnLocsByRegionName(region);
			if (points.isEmpty() || (minLevel > maxLevel))
			{
				LOGGER.warning(getClass().getSimpleName() + ": Invalid town " + entry + " (unknown map region or levels).");
				return null;
			}
			
			return new FakePlayerTown(region, shortName(region), points, minLevel, maxLevel, race);
		}
		catch (Exception e)
		{
			LOGGER.warning(getClass().getSimpleName() + ": Invalid town " + entry + ", expected region:minLevel-maxLevel[:RACE].");
			return null;
		}
	}
	
	/**
	 * @param region a map region name
	 * @return how players call that town
	 */
	private static String shortName(String region)
	{
		switch (region)
		{
			case "talking_island_town":
			{
				return "ti";
			}
			case "elf_town":
			{
				return "elven village";
			}
			case "darkelf_town":
			{
				return "de village";
			}
			case "orc_town":
			{
				return "orc village";
			}
			case "dwarf_town":
			{
				return "dwarf village";
			}
			case "kamael_town":
			{
				return "kamael village";
			}
			case "heiness_town":
			{
				return "heine";
			}
			case "hunter_town":
			{
				return "hunters village";
			}
			case "godard_town":
			{
				return "goddard";
			}
			case "town_of_schuttgart":
			{
				return "schuttgart";
			}
			default:
			{
				return region.replace("_castle_town", "").replace("_town", "").replace('_', ' ');
			}
		}
	}
	
	// Population.
	
	/**
	 * Fills a town at server start: everyone is somewhere in the middle of their visit, at an npc, standing or sitting around, a few talking.
	 */
	private void populate(FakePlayerTown town, long now)
	{
		town.nextFactorChange = now + Rnd.get(300000, 900000);
		town.nextArrival = now + Rnd.get(20000, 60000);
		town.nextLoneChat = now + Rnd.get(60000, 240000);
		
		final int buffers = wantedBuffers(town);
		for (int i = 0; i < buffers; i++)
		{
			final Location location = arrivalPoint(town, Arrival.GATEKEEPER);
			if (location != null)
			{
				spawnVisitor(town, location, randomLevel(town, BUFFER_MIN_LEVEL), BufferLine.random(), false, false);
			}
		}
		
		// Sellers already sitting in their stores.
		town.nextVendor = now + Rnd.get(60000, 240000);
		final int vendors = FakePlayersConfig.FAKE_TOWN_PLAYERS_STORES;
		for (int i = 0; i < vendors; i++)
		{
			final Location post = pickStorePost(town, null);
			if (post != null)
			{
				final FakePlayerTownVisitor vendor = spawnVisitor(town, post, randomLevel(town, 1), null, true, true, true);
				if (vendor != null)
				{
					vendor.startInStore();
				}
			}
		}
		
		final List<FakePlayerTownVisitor> idle = new ArrayList<>();
		for (int i = buffers; i < FakePlayersConfig.FAKE_TOWN_PLAYERS_PER_TOWN; i++)
		{
			final int roll = Rnd.get(100);
			if ((roll < 35) && town.isReady())
			{
				// At an npc, in the middle of an errand.
				final Role role = Role.values()[Rnd.get(Role.values().length)];
				final TownNpc townNpc = town.pickNpc(role, town.getRandomPoint());
				final Location spot = townNpc != null ? town.takeSpot(townNpc, null) : null;
				if (spot != null)
				{
					final FakePlayerTownVisitor visitor = spawnVisitor(town, spot, randomLevel(town, 1), null, false, true);
					if (visitor != null)
					{
						townNpc.taken.put(spot, visitor);
						visitor.startAtNpc(townNpc, spot);
					}
					else
					{
						FakePlayerTown.releaseSpot(townNpc, spot);
					}
					continue;
				}
			}
			
			if (roll < 65)
			{
				// Standing or sitting around.
				final boolean sitting = Rnd.get(100) < 20;
				final Location location = jitter(roll < 70 ? town.getRandomHub() : town.getRandomPoint(), 120);
				final FakePlayerTownVisitor visitor = spawnVisitor(town, location, randomLevel(town, 1), null, sitting, true);
				if (visitor != null)
				{
					visitor.startIdle(sitting);
					idle.add(visitor);
				}
				continue;
			}
			
			// Out in the street, on its way somewhere.
			if (roll < 85)
			{
				final FakePlayerTownVisitor visitor = spawnVisitor(town, jitter(town.getRandomHub(), 120), randomLevel(town, 1), null, false, true);
				if (visitor != null)
				{
					visitor.delay(Rnd.get(200, 3000));
				}
				continue;
			}
			
			// Just arrived.
			final Location location = arrivalPoint(town, Rnd.nextBoolean() ? Arrival.GATEKEEPER : Arrival.RESPAWN);
			if (location != null)
			{
				final FakePlayerTownVisitor visitor = spawnVisitor(town, location, randomLevel(town, 1), null, false, false);
				if (visitor != null)
				{
					visitor.delay(Rnd.get(1000, 20000));
				}
			}
		}
		
		// A pair or two already talking, once the server runs.
		Collections.shuffle(idle);
		for (int i = 0; ((i + 1) < idle.size()) && (i < 4); i += 2)
		{
			final FakePlayerTownVisitor first = idle.get(i);
			final FakePlayerTownVisitor second = idle.get(i + 1);
			if (FakePlayerTownVisitor.isChatEnabled() && (FakePlayerTown.distance2D(first.npc, second.npc) < 2500))
			{
				schedule(Rnd.get(3000, 30000), () -> pairUp(town, first, second));
			}
		}
	}
	
	/**
	 * Server start: one walks over to the other for a talk, if both are still standing around.
	 */
	private void pairUp(FakePlayerTown town, FakePlayerTownVisitor first, FakePlayerTownVisitor second)
	{
		final long now = System.currentTimeMillis();
		if (first.isAvailableForChat(now) && second.isAvailableForChat(now) && (FakePlayerTown.distance2D(first.npc, second.npc) < 150))
		{
			startCircle(town, List.of(first, second), Topic.CHAT, now);
		}
	}
	
	private void updateTown(FakePlayerTown town, long now)
	{
		// The path finding work isn't done yet: nobody shows up before, so nobody ends up somewhere walled off.
		if (!town.isPrepared())
		{
			return;
		}
		
		if (!town.populated)
		{
			town.populated = true;
			populate(town, now);
		}
		
		// The number of people in town drifts up and down.
		if (now >= town.nextFactorChange)
		{
			final double variation = FakePlayersConfig.FAKE_TOWN_PLAYERS_POPULATION_VARIATION / 100.0;
			town.populationFactor = Math.max(1 - variation, Math.min(1 + (variation * 0.7), town.populationFactor + (Rnd.nextGaussian() * 0.5 * variation)));
			town.nextFactorChange = now + Rnd.get(240000, 900000);
		}
		
		// Fewer people in town at night (Night Market): the ones who leave aren't replaced until the town is down to it.
		final int target = Math.max(1, (int) Math.round(FakePlayersConfig.FAKE_TOWN_PLAYERS_PER_TOWN * town.populationFactor * NightCycleManager.getInstance().getTownPopulationFactor()));
		final int vendors = town.countVendors();
		final int count = town.visitors.size() - vendors;
		if ((count < target) && (now >= town.nextArrival))
		{
			arrive(town, now);
			
			// Far below: they come in faster, like after a server restart or a siege.
			final double missing = (double) (target - count) / target;
			town.nextArrival = now + (long) (Rnd.get(4000, 30000) * (1.2 - missing));
		}
		
		// A seller comes to open its store a while after another one left. At night the black market sellers come on top (Night Market).
		final int blackMarkets = NightCycleManager.getInstance().getBlackMarketStores();
		if ((vendors < (FakePlayersConfig.FAKE_TOWN_PLAYERS_STORES + blackMarkets)) && (now >= town.nextVendor))
		{
			town.nextVendor = now + Rnd.get(60000, 300000);
			final Location location = arrivalPoint(town, Rnd.get(100) < 60 ? Arrival.LOGIN : Arrival.GATEKEEPER);
			final FakePlayerTownVisitor vendor = location != null ? spawnVisitor(town, location, randomLevel(town, 1), null, false, false, true) : null;
			if (vendor != null)
			{
				vendor.delay(Rnd.get(1500, 6000));
			}
		}
		
		if (now >= town.nextNotice)
		{
			town.nextNotice = now + Rnd.get(2500, 7000);
			noticePlayers(town);
		}
		
		for (FakePlayerTownVisitor visitor : town.visitors)
		{
			try
			{
				visitor.update(now);
			}
			catch (Exception e)
			{
				LOGGER.log(Level.WARNING, getClass().getSimpleName() + ": Problem with town fake player " + visitor.npc.getName() + ".", e);
			}
		}
		
		for (FakePlayerTownCircle circle : town.circles)
		{
			circle.update(now);
		}
		
		if (now >= town.nextLoneChat)
		{
			final double rate = FakePlayersConfig.FAKE_TOWN_PLAYERS_CHAT_RATE / 100.0;
			town.nextLoneChat = now + (rate > 0 ? (long) (Rnd.get(60000, 240000) / rate) : 3600000);
			if (FakePlayerTownVisitor.isChatEnabled())
			{
				loneChat(town);
			}
		}
	}
	
	/**
	 * Someone comes to town: alone most of the time, sometimes a party back from a hunt, sometimes a buffer that settles down for a while.
	 */
	private void arrive(FakePlayerTown town, long now)
	{
		if ((town.countBuffers() < wantedBuffers(town)) && (Rnd.get(100) < 40))
		{
			final Location location = arrivalPoint(town, Rnd.nextBoolean() ? Arrival.GATEKEEPER : Arrival.LOGIN);
			if (location != null)
			{
				final FakePlayerTownVisitor buffer = spawnVisitor(town, location, randomLevel(town, BUFFER_MIN_LEVEL), BufferLine.random(), false, false);
				if (buffer != null)
				{
					buffer.delay(Rnd.get(1500, 6000));
				}
			}
			return;
		}
		
		final int roll = Rnd.get(100);
		final Arrival arrival = (roll < 45) && hasGatekeeper(town) ? Arrival.GATEKEEPER : roll < 82 ? Arrival.RESPAWN : Arrival.LOGIN;
		final Location location = arrivalPoint(town, arrival);
		if (location == null)
		{
			return;
		}
		
		final int level = randomLevel(town, 1);
		final int partySize = (arrival != Arrival.LOGIN) && (level >= 15) && (Rnd.get(100) < 14) ? (Rnd.get(100) < 70 ? 2 : 3) : 1;
		if (partySize == 1)
		{
			final FakePlayerTownVisitor visitor = spawnVisitor(town, location, level, null, false, false);
			if (visitor != null)
			{
				visitor.delay(Rnd.get(800, 4000)); // The loading screen.
			}
			return;
		}
		
		// A party back from hunting: they show up together and say goodbye before going their own ways.
		final List<FakePlayerTownVisitor> party = new ArrayList<>();
		for (int i = 0; i < partySize; i++)
		{
			final int memberLevel = Math.max(town.minLevel, Math.min(town.maxLevel, level + Rnd.get(-4, 4)));
			final FakePlayerTownVisitor member = spawnVisitor(town, i == 0 ? location : jitter(location, 90), memberLevel, null, false, false);
			if (member != null)
			{
				member.delay(15000);
				party.add(member);
			}
		}
		
		// Friends that hunt together are often of the same clan.
		for (int i = 1; i < party.size(); i++)
		{
			FakeClanManager.getInstance().shareClan(party.get(i).npc, party.get(0).npc);
		}
		
		if ((party.size() >= 2) && FakePlayerTownVisitor.isChatEnabled())
		{
			schedule(Rnd.get(2000, 5000), () ->
			{
				final List<FakePlayerTownVisitor> alive = new ArrayList<>();
				for (FakePlayerTownVisitor member : party)
				{
					if (!member.gone && (member.circle == null) && !member.isHeld())
					{
						alive.add(member);
					}
				}
				if (alive.size() >= 2)
				{
					startCircle(town, alive, Topic.PARTY_END, System.currentTimeMillis());
				}
			});
		}
	}
	
	private static int wantedBuffers(FakePlayerTown town)
	{
		return town.maxLevel >= BUFFER_MIN_LEVEL ? FakePlayersConfig.FAKE_TOWN_PLAYERS_BUFFERS : 0;
	}
	
	private static int randomLevel(FakePlayerTown town, int min)
	{
		final int low = Math.max(town.minLevel, Math.min(town.maxLevel, min));
		return Rnd.get(low, town.maxLevel);
	}
	
	private static boolean hasGatekeeper(FakePlayerTown town)
	{
		for (TownNpc townNpc : town.getNpcs(Role.GATEKEEPER))
		{
			if ("Teleporter".equals(townNpc.spawn.getTemplate().getType()) && (townNpc.getNpc() != null))
			{
				return true;
			}
		}
		return false;
	}
	
	/**
	 * @param town the town
	 * @param arrival how it arrives
	 * @return where it appears, {@code null} if nowhere was found
	 */
	private Location arrivalPoint(FakePlayerTown town, Arrival arrival)
	{
		switch (arrival)
		{
			case GATEKEEPER:
			{
				// Teleporting to a town puts you right next to its gatekeeper.
				final List<TownNpc> gatekeepers = new ArrayList<>();
				for (TownNpc townNpc : town.getNpcs(Role.GATEKEEPER))
				{
					if ("Teleporter".equals(townNpc.spawn.getTemplate().getType()) && (townNpc.getNpc() != null))
					{
						gatekeepers.add(townNpc);
					}
				}
				
				if (!gatekeepers.isEmpty())
				{
					final List<Location> spots = town.getSpots(gatekeepers.get(Rnd.get(gatekeepers.size())));
					if (!spots.isEmpty())
					{
						return jitter(spots.get(Rnd.get(spots.size())), 70);
					}
				}
				return jitter(town.getRandomPoint(), 140);
			}
			case RESPAWN:
			{
				return jitter(town.getRandomPoint(), 140);
			}
			default:
			{
				// Where it logged off: out in the street, or next to an npc.
				if (town.isReady() && (Rnd.get(100) < 40))
				{
					final TownNpc townNpc = town.pickNpc(Role.values()[Rnd.get(Role.values().length)], town.getRandomHub());
					if (townNpc != null)
					{
						final List<Location> spots = town.getSpots(townNpc);
						if (!spots.isEmpty())
						{
							return jitter(spots.get(Rnd.get(spots.size())), 60);
						}
					}
				}
				return jitter(town.getRandomHub(), 100);
			}
		}
	}
	
	private static Location jitter(Location location, int range)
	{
		final int x = location.getX() + Rnd.get(-range, range);
		final int y = location.getY() + Rnd.get(-range, range);
		return GeoEngine.getInstance().getValidLocation(location.getX(), location.getY(), location.getZ(), x, y, location.getZ(), 0);
	}
	
	/**
	 * @param race the race most fake players of the town are, {@code null} for any
	 * @return a random build, of {@code race} {@link FakePlayersConfig#FAKE_TOWN_PLAYERS_RACE_CHANCE} % of the time when there is one
	 */
	private static FakePlayerPvpBuild getBuild(Race race)
	{
		final FakePlayerPvpData data = FakePlayerPvpData.getInstance();
		if ((race != null) && (Rnd.get(100) < FakePlayersConfig.FAKE_TOWN_PLAYERS_RACE_CHANCE))
		{
			final List<FakePlayerPvpBuild> sameRace = new ArrayList<>();
			for (FakePlayerPvpBuild build : data.getBuilds())
			{
				if (build.getPlayerClass().getRace() == race)
				{
					sameRace.add(build);
				}
			}
			
			if (!sameRace.isEmpty())
			{
				return sameRace.get(Rnd.get(sameRace.size()));
			}
		}
		return data.getRandomBuild();
	}
	
	/**
	 * Creates a town fake player and puts it in the world.
	 * @param town the town
	 * @param location where it appears
	 * @param level its level
	 * @param bufferLine its buffer class line, {@code null} for a regular visitor
	 * @param sitting {@code true} if it appears sitting
	 * @param midSession {@code true} if it was already in town for a while (server start)
	 * @return the fake player, {@code null} if it could not be created
	 */
	private FakePlayerTownVisitor spawnVisitor(FakePlayerTown town, Location location, int level, BufferLine bufferLine, boolean sitting, boolean midSession)
	{
		return spawnVisitor(town, location, level, bufferLine, sitting, midSession, false);
	}
	
	/**
	 * Creates a town fake player and puts it in the world.
	 * @param town the town
	 * @param location where it appears
	 * @param level its level
	 * @param bufferLine its buffer class line, {@code null} for a regular visitor
	 * @param sitting {@code true} if it appears sitting
	 * @param midSession {@code true} if it was already in town for a while (server start)
	 * @param vendor {@code true} if it comes to sell in a private store
	 * @return the fake player, {@code null} if it could not be created
	 */
	private FakePlayerTownVisitor spawnVisitor(FakePlayerTown town, Location location, int level, BufferLine bufferLine, boolean sitting, boolean midSession, boolean vendor)
	{
		if (location == null)
		{
			return null;
		}

		
		final FakePlayerPvpBuild build = bufferLine != null ? bufferLine.getGearBuild() : getBuild(town.race);
		if (build == null)
		{
			return null;
		}
		
		final FakePlayerPvpManager names = FakePlayerPvpManager.getInstance();
		final String name = names.generateName();
		final int npcId = takeNpcId();
		try
		{
			final PlayerClass forcedClass = bufferLine != null ? bufferLine.getPlayerClass(level) : null;
			final NpcTemplate template = FakePlayerPvpFactory.createTownTemplate(build, level, npcId, name, forcedClass, sitting);
			if (template == null)
			{
				FakePlayerData.getInstance().removeFakePlayer(name);
				names.releaseName(name);
				_freeNpcIds.addLast(npcId);
				return null;
			}
			
			// Sometimes a member of a clan of fake players.
			FakeClanManager.getInstance().assignClan(template);
			
			final Spawn spawn = new Spawn(template);
			spawn.setXYZ(location.getX(), location.getY(), location.getZ());
			spawn.setHeading(-1);
			spawn.setAmount(1);
			spawn.setRespawnDelay(0);
			spawn.stopRespawn();
			SpawnTable.getInstance().addSpawn(spawn);
			final Npc npc = spawn.doSpawn(false);
			if (npc == null)
			{
				SpawnTable.getInstance().removeSpawn(spawn);
				FakePlayerData.getInstance().removeFakePlayer(name);
				names.releaseName(name);
				_freeNpcIds.addLast(npcId);
				return null;
			}
			
			// Nothing to sell after all (should not happen): it comes as a regular visitor.
			final FakePlayerTownStore store = vendor ? createStore(town, level) : null;
			final PlayerClass playerClass = template.getFakePlayerInfo().getPlayerClass();
			final FakePlayerTownVisitor visitor = new FakePlayerTownVisitor(this, town, spawn, npc, level, playerClass, bufferLine, store, selfBuffs(build, level, bufferLine));
			visitor.makePlan(midSession);
			town.visitors.add(visitor);
			_visitors.put(npc.getObjectId(), visitor);
			
			// Back from a hunt, it often still runs with Wind Walk on.
			if (!midSession && (bufferLine == null) && (store == null) && (level >= 20) && (Rnd.get(100) < 40))
			{
				final Skill windWalk = SkillData.getInstance().getSkill(1204, 2);
				if (windWalk != null)
				{
					windWalk.applyEffects(npc, npc);
				}
			}
			return visitor;
		}
		catch (Exception e)
		{
			LOGGER.log(Level.WARNING, getClass().getSimpleName() + ": Could not spawn town fake player " + build.getName() + " level " + level + " in " + town.region + ".", e);
			FakePlayerData.getInstance().removeFakePlayer(name);
			names.releaseName(name);
			_freeNpcIds.addLast(npcId);
			return null;
		}
	}
	
	/**
	 * @param town the town the seller comes to
	 * @param level the seller's level
	 * @return its store: a black market one while the town has fewer than the night calls for, else the usual one
	 */
	private static FakePlayerTownStore createStore(FakePlayerTown town, int level)
	{
		if (town.countBlackMarkets() < NightCycleManager.getInstance().getBlackMarketStores())
		{
			final FakePlayerTownStore blackMarket = FakePlayerTownStore.createBlackMarket(level);
			if (blackMarket != null)
			{
				return blackMarket;
			}
		}
		return FakePlayerTownStore.create(level, FakePlayersConfig.FAKE_TOWN_PLAYERS_STORE_RARE_CHANCE, FakePlayersConfig.FAKE_TOWN_PLAYERS_STORE_LOOT_CHANCE, FakePlayersConfig.FAKE_TOWN_PLAYERS_STORE_LOOT_KILLS);
	}
	
	/**
	 * Dawn: the black market sellers pack up and leave. Runs on the town thread, like everything else the visitors do.
	 */
	public void onDawn()
	{
		schedule(0, () ->
		{
			for (FakePlayerTown town : _towns)
			{
				for (FakePlayerTownVisitor visitor : town.visitors)
				{
					if (visitor.isVendor() && visitor.store.isBlackMarket())
					{
						visitor.packUp();
					}
				}
			}
		});
	}
	
	private int takeNpcId()
	{
		final Integer free = _freeNpcIds.pollFirst();
		if (free != null)
		{
			return free;
		}
		return _nextNpcId < LAST_NPC_ID ? _nextNpcId++ : LAST_NPC_ID;
	}
	
	/**
	 * @return the class buffs a fighter or mage of that build casts on itself (from level 40, when classes have them), none for a buffer
	 */
	private static List<Skill> selfBuffs(FakePlayerPvpBuild build, int level, BufferLine bufferLine)
	{
		if ((bufferLine != null) || (level < 40))
		{
			return Collections.emptyList();
		}
		
		final List<Skill> skills = new ArrayList<>();
		for (int[] alternatives : build.getSkills(SkillCategory.BUFF))
		{
			for (int id : alternatives)
			{
				final int skillLevel = SkillData.getInstance().getMaxLevel(id);
				final Skill skill = skillLevel > 0 ? SkillData.getInstance().getSkill(id, skillLevel) : null;
				if ((skill != null) && !skill.isToggle() && !skill.isPassive())
				{
					skills.add(skill);
					break;
				}
			}
		}
		return skills;
	}
	
	/**
	 * Takes a town fake player out of the world: it teleported away or logged off. Someone else comes later.
	 * @param visitor the fake player
	 */
	void leave(FakePlayerTownVisitor visitor)
	{
		if (visitor.gone)
		{
			return;
		}
		
		final long now = System.currentTimeMillis();
		if (visitor.circle != null)
		{
			visitor.circle.leave(visitor, now);
		}
		visitor.onGone();
		
		final Npc npc = visitor.npc;
		final String name = npc.getName();
		final int npcId = npc.getId();
		_visitors.remove(npc.getObjectId());
		visitor.town.visitors.remove(visitor);
		try
		{
			visitor.spawn.stopRespawn();
			if (npc.isSpawned())
			{
				npc.deleteMe();
			}
			SpawnTable.getInstance().removeSpawn(visitor.spawn);
		}
		catch (Exception e)
		{
			LOGGER.log(Level.WARNING, getClass().getSimpleName() + ": Problem removing town fake player " + name + ".", e);
		}
		
		FakePlayerData.getInstance().removeFakePlayer(name);
		FakePlayerPvpManager.getInstance().releaseName(name);
		_freeNpcIds.addLast(npcId);
	}
	
	// Finding others.
	
	/**
	 * @param object a world object
	 * @return the town fake player it is, {@code null} if it isn't one
	 */
	FakePlayerTownVisitor getVisitor(WorldObject object)
	{
		return object != null ? _visitors.get(object.getObjectId()) : null;
	}
	
	/**
	 * @param town the town
	 * @param requester the one who wants buffs
	 * @return a buffer at its post with not too many waiting, closer ones more likely, {@code null} if none
	 */
	FakePlayerTownVisitor pickBuffer(FakePlayerTown town, FakePlayerTownVisitor requester)
	{
		FakePlayerTownVisitor best = null;
		double bestScore = Double.MAX_VALUE;
		for (FakePlayerTownVisitor visitor : town.visitors)
		{
			if ((visitor == requester) || !visitor.isOnDuty() || (visitor.getQueueSize() >= 2))
			{
				continue;
			}
			
			final double distance = FakePlayerTown.distance2D(visitor.npc, requester.npc);
			final double score = distance * (0.6 + Rnd.nextDouble());
			if ((distance < 4000) && (score < bestScore))
			{
				best = visitor;
				bestScore = score;
			}
		}
		return best;
	}
	
	/**
	 * @param town the town
	 * @param initiator the one who wants to talk
	 * @return someone standing around close enough to walk up to, {@code null} if none
	 */
	FakePlayerTownVisitor pickPartner(FakePlayerTown town, FakePlayerTownVisitor initiator)
	{
		final long now = System.currentTimeMillis();
		final List<FakePlayerTownVisitor> candidates = new ArrayList<>();
		final List<Double> weights = new ArrayList<>();
		double total = 0;
		for (FakePlayerTownVisitor visitor : town.visitors)
		{
			if ((visitor == initiator) || !visitor.isAvailableForChat(now) || (visitor.loner && (Rnd.get(100) < 80)))
			{
				continue;
			}
			
			final double distance = FakePlayerTown.distance2D(visitor.npc, initiator.npc);
			if (distance > 2200)
			{
				continue;
			}
			
			final double weight = 1 / Math.max(250, distance);
			candidates.add(visitor);
			weights.add(weight);
			total += weight;
		}
		
		double roll = Rnd.nextDouble() * total;
		for (int i = 0; i < candidates.size(); i++)
		{
			roll -= weights.get(i);
			if (roll <= 0)
			{
				return candidates.get(i);
			}
		}
		return null;
	}
	
	/**
	 * @param town the town
	 * @param joiner the one who would join
	 * @return a conversation close by that someone may still join, {@code null} if none
	 */
	FakePlayerTownCircle pickCircle(FakePlayerTown town, FakePlayerTownVisitor joiner)
	{
		final List<FakePlayerTownCircle> open = new ArrayList<>();
		for (FakePlayerTownCircle circle : town.circles)
		{
			if (circle.isOpen() && (FakePlayerTown.distance2D(circle.getCenter(), joiner.npc) < 2000))
			{
				open.add(circle);
			}
		}
		return open.isEmpty() ? null : open.get(Rnd.get(open.size()));
	}
	
	/**
	 * @param town the town
	 * @param now the current time
	 * @return {@code true} if a new conversation may start: only a few at a time, and not one right after the other
	 */
	boolean canStartCircle(FakePlayerTown town, long now)
	{
		return (now >= town.nextCircle) && (town.circles.size() < Math.max(1, town.visitors.size() / 9));
	}
	
	/**
	 * Starts a conversation. Two of about the same level may also team up for a hunt and leave together.
	 * @param town the town
	 * @param members who talk
	 * @param topic the kind of conversation
	 * @param now the current time
	 */
	void startCircle(FakePlayerTown town, List<FakePlayerTownVisitor> members, Topic topic, long now)
	{
		Topic used = topic;
		if ((used == Topic.CHAT) && (members.size() == 2))
		{
			final FakePlayerTownVisitor first = members.get(0);
			final FakePlayerTownVisitor second = members.get(1);
			if (!first.isBuffer() && !second.isBuffer() && (Math.min(first.level, second.level) >= 20) && (Math.abs(first.level - second.level) <= 8) && (Rnd.get(100) < 12))
			{
				used = Topic.TEAM_UP;
			}
		}
		town.circles.add(new FakePlayerTownCircle(this, town, members, used, now));
		final double rate = Math.max(0.1, FakePlayersConfig.FAKE_TOWN_PLAYERS_CHAT_RATE / 100.0);
		town.nextCircle = now + (long) (Rnd.get(40000, 150000) / rate);
	}
	
	/**
	 * @param visitor the one who would look
	 * @return a player or fake player selling or buying in a private store close by that no other fake player looks at right now, {@code null} if none
	 */
	WorldObject pickStore(FakePlayerTownVisitor visitor)
	{
		final long now = System.currentTimeMillis();
		_browsedStores.values().removeIf(until -> until < now);
		final List<WorldObject> stores = new ArrayList<>();
		World.getInstance().forEachVisibleObjectInRange(visitor.npc, Player.class, STORE_RANGE, player ->
		{
			if (player.isInStoreMode() && !_browsedStores.containsKey(player.getObjectId()) && visitor.town.isInside(player.getX(), player.getY(), player.getZ()))
			{
				stores.add(player);
			}
		});
		for (FakePlayerTownVisitor other : visitor.town.visitors)
		{
			if ((other != visitor) && other.isStoreOpen() && !_browsedStores.containsKey(other.npc.getObjectId()) && (FakePlayerTown.distance2D(other.npc, visitor.npc) < STORE_RANGE))
			{
				stores.add(other.npc);
			}
		}
		
		if (stores.isEmpty())
		{
			return null;
		}
		
		final WorldObject store = stores.get(Rnd.get(stores.size()));
		_browsedStores.put(store.getObjectId(), now + 45000);
		return store;
	}
	
	/**
	 * @param beggar a newbie that wants a little adena
	 * @return someone close by to ask: a player (not one asked lately) or a fake player of a much higher level standing around, {@code null} if none
	 */
	WorldObject pickBegTarget(FakePlayerTownVisitor beggar)
	{
		final long now = System.currentTimeMillis();
		_begged.values().removeIf(until -> until < now);
		final List<WorldObject> players = new ArrayList<>();
		World.getInstance().forEachVisibleObjectInRange(beggar.npc, Player.class, 1800, player ->
		{
			if (!player.isInStoreMode() && !player.isDead() && !player.isInvisible() && !_begged.containsKey(player.getObjectId()) && beggar.town.isInside(player.getX(), player.getY(), player.getZ()))
			{
				players.add(player);
			}
		});
		
		if (!players.isEmpty() && (Rnd.get(100) < 60))
		{
			final WorldObject player = players.get(Rnd.get(players.size()));
			_begged.put(player.getObjectId(), now + (6 * 60000));
			return player;
		}
		
		final List<FakePlayerTownVisitor> rich = new ArrayList<>();
		for (FakePlayerTownVisitor visitor : beggar.town.visitors)
		{
			if ((visitor != beggar) && !visitor.isBuffer() && (visitor.level >= Math.max(beggar.level + 12, 30)) && visitor.isAvailableForChat(now) && (FakePlayerTown.distance2D(visitor.npc, beggar.npc) < 1800))
			{
				rich.add(visitor);
			}
		}
		return rich.isEmpty() ? null : rich.get(Rnd.get(rich.size())).npc;
	}
	
	/**
	 * Players walking by: a fake player standing close by turns to them, and may say hello or wave.
	 */
	private void noticePlayers(FakePlayerTown town)
	{
		final long now = System.currentTimeMillis();
		for (FakePlayerTownVisitor visitor : town.visitors)
		{
			if (visitor.gone || visitor.isVendor() || !visitor.isAvailableForChat(now) || !FakePlayerTown.isWatched(visitor.npc) || (Rnd.get(100) < 50))
			{
				continue;
			}
			
			final List<Player> close = new ArrayList<>();
			World.getInstance().forEachVisibleObjectInRange(visitor.npc, Player.class, 220, player ->
			{
				if (!player.isInvisible() && !player.isInStoreMode())
				{
					close.add(player);
				}
			});
			if (!close.isEmpty())
			{
				visitor.notice(close.get(Rnd.get(close.size())));
			}
		}
	}
	
	/**
	 * @param town the town
	 * @param vendor a seller, {@code null} for one that isn't in the world yet
	 * @return where it sits with its store: along a street close to a gatekeeper, a warehouse, a grocer or a respawn point, not in front of an npc and not on top of other stores, {@code null} if nowhere was found
	 */
	Location pickStorePost(FakePlayerTown town, FakePlayerTownVisitor vendor)
	{
		final GeoEngine geo = GeoEngine.getInstance();
		final Role[] near =
		{
			Role.GATEKEEPER,
			Role.WAREHOUSE,
			Role.WAREHOUSE,
			Role.GROCER,
			Role.MERCHANT
		};
		for (int attempt = 0; attempt < 14; attempt++)
		{
			Location base = town.getRandomPoint();
			if (Rnd.get(100) < 70)
			{
				final TownNpc townNpc = town.pickNpc(near[Rnd.get(near.length)], vendor != null ? vendor.npc : town.getRandomPoint());
				final Npc npc = townNpc != null ? townNpc.getNpc() : null;
				if (npc != null)
				{
					base = new Location(npc.getX(), npc.getY(), npc.getZ());
				}
			}
			
			final double angle = Rnd.nextDouble() * 2 * Math.PI;
			final int distance = Rnd.get(160, 520);
			final int x = base.getX() + (int) (Math.cos(angle) * distance);
			final int y = base.getY() + (int) (Math.sin(angle) * distance);
			final int z = geo.getHeight(x, y, base.getZ());
			if ((Math.abs(z - base.getZ()) > 120) || !town.isInside(x, y, z) || !geo.canMoveToTarget(base.getX(), base.getY(), base.getZ(), x, y, z, 0))
			{
				continue;
			}
			
			if (!isFreeForStore(town, vendor, x, y, z))
			{
				continue;
			}
			return new Location(x, y, z);
		}
		return null;
	}
	
	private static boolean isFreeForStore(FakePlayerTown town, FakePlayerTownVisitor vendor, int x, int y, int z)
	{
		// Not in front of an npc.
		for (List<TownNpc> list : town.npcs.values())
		{
			for (TownNpc townNpc : list)
			{
				if (Math.hypot(townNpc.spawn.getX() - x, townNpc.spawn.getY() - y) < 150)
				{
					return false;
				}
			}
		}
		
		// Not on top of another store or a buffer.
		for (FakePlayerTownVisitor other : town.visitors)
		{
			if ((other != vendor) && (other.isVendor() || other.isBuffer()) && (Math.hypot(other.npc.getX() - x, other.npc.getY() - y) < 110))
			{
				return false;
			}
		}
		
		// Nor on top of the store of a player.
		final WorldRegion region = World.getInstance().getRegion(x, y, z);
		if (region != null)
		{
			for (WorldRegion surrounding : region.getSurroundingRegions())
			{
				for (WorldObject object : surrounding.getVisibleObjects())
				{
					if (object.isPlayer() && object.asPlayer().isInStoreMode() && (Math.hypot(object.getX() - x, object.getY() - y) < 110))
					{
						return false;
					}
				}
			}
		}
		return true;
	}
	
	// Private stores of the fake players, used by players (network threads).
	
	/**
	 * A player opens the private store of a town fake player.
	 * @param player the player
	 * @param npc the fake player
	 * @return {@code true} if it is a town fake player with its store open (the sell list was sent)
	 */
	public boolean showStore(Player player, Npc npc)
	{
		final FakePlayerTownVisitor visitor = _visitors.get(npc.getObjectId());
		if ((visitor == null) || !visitor.isStoreOpen())
		{
			return false;
		}
		
		player.sendPacket(new FakePlayerPrivateStoreListSell(player, npc.getObjectId(), visitor.store.getItems()));
		return true;
	}
	
	/**
	 * A player buys in the private store of a town fake player.
	 * @param player the buyer
	 * @param objectId the object id of the seller
	 * @param items what it buys
	 * @return {@code false} if {@code objectId} isn't a town fake player
	 */
	public boolean buyFromStore(Player player, int objectId, Set<RequestTrade> items)
	{
		final FakePlayerTownVisitor visitor = _visitors.get(objectId);
		if (visitor == null)
		{
			return false;
		}
		
		if (!visitor.isStoreOpen() || player.isCursedWeaponEquipped() || !player.isInsideRadius3D(visitor.npc, Npc.INTERACTION_DISTANCE) || (player.getInstanceId() != visitor.npc.getInstanceId()))
		{
			player.sendPacket(ActionFailed.STATIC_PACKET);
			return true;
		}
		
		if (!player.getAccessLevel().allowTransaction())
		{
			player.sendMessage("Transactions are disabled for your Access Level.");
			player.sendPacket(ActionFailed.STATIC_PACKET);
			return true;
		}
		
		if (visitor.store.buy(player, items, visitor.npc))
		{
			schedule(0, visitor::boughtByPlayer);
		}
		else
		{
			player.sendPacket(ActionFailed.STATIC_PACKET);
		}
		return true;
	}
	
	/**
	 * @param town the town
	 * @param buffer a buffer
	 * @return where it waits for people to buff: in sight of a gatekeeper or a respawn point, away from other buffers, {@code null} if nowhere was found
	 */
	Location pickBufferPost(FakePlayerTown town, FakePlayerTownVisitor buffer)
	{
		final GeoEngine geo = GeoEngine.getInstance();
		for (int attempt = 0; attempt < 10; attempt++)
		{
			Location base = town.getRandomPoint();
			if (Rnd.get(100) < 60)
			{
				final TownNpc gatekeeper = town.pickNpc(Role.GATEKEEPER, buffer.npc);
				final Npc npc = gatekeeper != null ? gatekeeper.getNpc() : null;
				if (npc != null)
				{
					base = new Location(npc.getX(), npc.getY(), npc.getZ());
				}
			}
			
			final double angle = Rnd.nextDouble() * 2 * Math.PI;
			final int distance = Rnd.get(180, 480);
			final int x = base.getX() + (int) (Math.cos(angle) * distance);
			final int y = base.getY() + (int) (Math.sin(angle) * distance);
			final int z = geo.getHeight(x, y, base.getZ());
			if ((Math.abs(z - base.getZ()) > 120) || !town.isInside(x, y, z) || !geo.canMoveToTarget(base.getX(), base.getY(), base.getZ(), x, y, z, 0))
			{
				continue;
			}
			
			boolean crowded = false;
			for (FakePlayerTownVisitor other : town.visitors)
			{
				if ((other != buffer) && other.isBuffer() && (Math.hypot(other.npc.getX() - x, other.npc.getY() - y) < 250))
				{
					crowded = true;
					break;
				}
			}
			if (!crowded)
			{
				return new Location(x, y, z);
			}
		}
		return null;
	}
	
	/**
	 * Someone standing around says something in general chat, if a player is there to read it: looking for a party, buying, selling, asking.
	 */
	private void loneChat(FakePlayerTown town)
	{
		final List<FakePlayerTownVisitor> candidates = new ArrayList<>();
		for (FakePlayerTownVisitor visitor : town.visitors)
		{
			if (!visitor.gone && !visitor.isHeld() && (visitor.circle == null) && FakePlayerTown.isWatched(visitor.npc))
			{
				candidates.add(visitor);
			}
		}
		
		if (candidates.isEmpty())
		{
			return;
		}
		
		final FakePlayerTownVisitor speaker = candidates.get(Rnd.get(candidates.size()));
		final FakePlayerTownChat.Speaker info = new FakePlayerTownChat.Speaker(speaker.npc.getName(), speaker.level, className(speaker.playerClass), speaker.npc.getTemplate().getFakePlayerInfo().getEquipRHand(), speaker.npc.getTemplate().getFakePlayerInfo().getWeaponEnchantLevel(), speaker.style);
		for (int attempt = 0; attempt < 5; attempt++)
		{
			final String text = FakePlayerTownChat.lone(info, town.shortName);
			if (!town.wasSaidLately(text))
			{
				speaker.say(text);
				return;
			}
		}
	}
	
	// Buffs.
	
	/**
	 * Shows a caster buffing a target, one buff after another: the cast, then the effect when it lands. Speed buffs really apply, so a buffed fake player runs faster like a buffed player.
	 * @param caster the caster (a guide npc, a buffer, or the fake player itself)
	 * @param target the one buffed
	 * @param skills the buffs
	 * @param castFactor the cast time of each buff, from its hit time (casting speed)
	 * @param onDone called when the last one landed, {@code null} for nothing
	 * @return how long it takes, in milliseconds
	 */
	long castBuffs(Npc caster, Npc target, List<Skill> skills, double castFactor, Runnable onDone)
	{
		long time = 0;
		for (Skill skill : skills)
		{
			final int hitTime = castTimeOf(skill, castFactor);
			schedule(time, () ->
			{
				if (caster.isSpawned() && target.isSpawned())
				{
					FakePlayerTownVisitor.showCast(caster, target, skill, hitTime);
				}
			});
			schedule(time + hitTime, () ->
			{
				if (caster.isSpawned() && target.isSpawned())
				{
					FakePlayerTownVisitor.showLaunch(caster, target, skill);
					if (FakePlayerTownVisitor.isSpeedBuff(skill))
					{
						skill.applyEffects(caster, target);
					}
				}
			});
			time += hitTime + Rnd.get(120, 450);
		}
		
		if (onDone != null)
		{
			schedule(time, onDone);
		}
		return time;
	}
	
	/**
	 * @param skills buffs
	 * @param castFactor the cast time factor
	 * @return about how long {@link #castBuffs} takes for them
	 */
	long castTime(List<Skill> skills, double castFactor)
	{
		long time = 0;
		for (Skill skill : skills)
		{
			time += castTimeOf(skill, castFactor) + 285;
		}
		return time;
	}
	
	private static int castTimeOf(Skill skill, double castFactor)
	{
		// The guide's adventurer buffs are instant (factor 1), a buffer's buffs take their hit time at its casting speed.
		final int hitTime = skill.getHitTime() > 0 ? skill.getHitTime() : (castFactor < 1 ? 2500 : 0);
		return (int) Math.max(200, hitTime * castFactor);
	}
	
	/**
	 * Runs something a bit later on the manager's thread (where everything about town fake players happens).
	 * @param delay the delay in milliseconds
	 * @param action what to do
	 */
	void schedule(long delay, Runnable action)
	{
		synchronized (_timeline)
		{
			_timeline.add(new Timed(System.currentTimeMillis() + Math.max(0, delay), _order++, action));
		}
	}
	
	private void tick()
	{
		final long now = System.currentTimeMillis();
		while (true)
		{
			final Timed timed;
			synchronized (_timeline)
			{
				timed = _timeline.peek();
				if ((timed == null) || (timed.time > now))
				{
					break;
				}
				_timeline.poll();
			}
			
			try
			{
				timed.action.run();
			}
			catch (Exception e)
			{
				LOGGER.log(Level.WARNING, getClass().getSimpleName() + ": Problem with a timed action.", e);
			}
		}
		
		for (FakePlayerTown town : _towns)
		{
			try
			{
				updateTown(town, now);
			}
			catch (Exception e)
			{
				LOGGER.log(Level.WARNING, getClass().getSimpleName() + ": Problem while running " + town.region + ".", e);
			}
		}
	}
	
	/**
	 * @param playerClass a class
	 * @return how players call it in chat
	 */
	static String className(PlayerClass playerClass)
	{
		final String full = playerClass.name().toLowerCase().replace('_', ' ');
		if (Rnd.nextBoolean())
		{
			return full;
		}
		
		switch (playerClass.name())
		{
			case "PALADIN":
			case "PHOENIX_KNIGHT":
			{
				return "pala";
			}
			case "DARK_AVENGER":
			{
				return "da";
			}
			case "HELL_KNIGHT":
			{
				return "hk";
			}
			case "TEMPLE_KNIGHT":
			case "EVA_TEMPLAR":
			{
				return "tk";
			}
			case "SHILLIEN_KNIGHT":
			case "SHILLIEN_TEMPLAR":
			{
				return "sk";
			}
			case "TREASURE_HUNTER":
			{
				return "th";
			}
			case "ADVENTURER":
			{
				return "adv";
			}
			case "PLAINS_WALKER":
			case "PLAINSWALKER":
			{
				return "pw";
			}
			case "WIND_RIDER":
			{
				return "wr";
			}
			case "ABYSS_WALKER":
			{
				return "aw";
			}
			case "GHOST_HUNTER":
			{
				return "gh";
			}
			case "HAWKEYE":
			{
				return "he";
			}
			case "SAGITTARIUS":
			{
				return "sagi";
			}
			case "SILVER_RANGER":
			{
				return "sr";
			}
			case "MOONLIGHT_SENTINEL":
			{
				return "ms";
			}
			case "PHANTOM_RANGER":
			{
				return "pr";
			}
			case "GHOST_SENTINEL":
			{
				return "gs";
			}
			case "GLADIATOR":
			{
				return "glad";
			}
			case "DESTROYER":
			{
				return "destro";
			}
			case "WARLORD":
			{
				return "wl";
			}
			case "DREADNOUGHT":
			{
				return "dread";
			}
			case "SORCERER":
			{
				return "sorc";
			}
			case "ARCHMAGE":
			{
				return "am";
			}
			case "MYSTIC_MUSE":
			{
				return "mm";
			}
			case "STORM_SCREAMER":
			{
				return "storm";
			}
			case "NECROMANCER":
			{
				return "necro";
			}
			case "OVERLORD":
			{
				return "ol";
			}
			case "DOMINATOR":
			{
				return "dom";
			}
			case "BERSERKER":
			{
				return "zerk";
			}
			case "DOOMBRINGER":
			{
				return "doom";
			}
			case "PROPHET":
			{
				return "pp";
			}
			case "HIEROPHANT":
			{
				return "hiero";
			}
			case "ELDER":
			case "EVA_SAINT":
			{
				return "ee";
			}
			case "SHILLIEN_ELDER":
			case "SHILLIEN_SAINT":
			{
				return "se";
			}
			case "WARCRYER":
			case "DOOMCRYER":
			{
				return "wc";
			}
			case "SWORDSINGER":
			case "SWORD_MUSE":
			{
				return "sws";
			}
			case "BLADEDANCER":
			case "SPECTRAL_DANCER":
			{
				return "bd";
			}
			default:
			{
				return full;
			}
		}
	}
	
	/**
	 * @return how many town fake players there are
	 */
	public int getCount()
	{
		return _visitors.size();
	}
	
	/**
	 * @return the town fake players in the world
	 */
	public List<Npc> getVisitorNpcs()
	{
		final List<Npc> npcs = new ArrayList<>(_visitors.size());
		for (FakePlayerTownVisitor visitor : _visitors.values())
		{
			npcs.add(visitor.npc);
		}
		return npcs;
	}
	
	/**
	 * @return {@code true} once the server made this manager
	 */
	static boolean isStarted()
	{
		return _started;
	}
	
	/**
	 * @param target what a GM targets
	 * @return a few lines about the towns, and about {@code target} if it is a town fake player (for //faketown)
	 */
	public List<String> getInfo(WorldObject target)
	{
		final List<String> lines = new ArrayList<>();
		lines.add("Town fake players: " + _visitors.size() + " in " + _towns.size() + " towns.");
		for (FakePlayerTown town : _towns)
		{
			int buffers = 0;
			int talking = 0;
			int stores = 0;
			for (FakePlayerTownVisitor visitor : town.visitors)
			{
				buffers += visitor.isBuffer() ? 1 : 0;
				talking += visitor.circle != null ? 1 : 0;
				stores += visitor.isStoreOpen() ? 1 : 0;
			}
			
			final int wanted = Math.max(1, (int) Math.round(FakePlayersConfig.FAKE_TOWN_PLAYERS_PER_TOWN * town.populationFactor));
			lines.add(town.shortName + ": " + town.visitors.size() + "/" + wanted + (talking > 0 ? ", " + talking + " talking" : "") + (buffers > 0 ? ", " + buffers + " buffer" : "") + (stores > 0 ? ", " + stores + " store" : "") + (town.isPrepared() ? "" : " (preparing)"));
		}
		
		final FakePlayerTownVisitor visitor = getVisitor(target);
		if (visitor != null)
		{
			lines.add(visitor.describe());
		}
		return lines;
	}
	
	public static FakePlayerTownManager getInstance()
	{
		return SingletonHolder.INSTANCE;
	}
	
	private static class SingletonHolder
	{
		protected static final FakePlayerTownManager INSTANCE = new FakePlayerTownManager();
	}
}
