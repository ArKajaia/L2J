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

import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.l2jmobius.commons.threads.ThreadPool;
import org.l2jmobius.commons.util.Rnd;
import org.l2jmobius.gameserver.ai.Intention;
import org.l2jmobius.gameserver.config.custom.FakePlayersConfig;
import org.l2jmobius.gameserver.data.SpawnTable;
import org.l2jmobius.gameserver.data.xml.FakePlayerPvpData;
import org.l2jmobius.gameserver.data.xml.MapRegionData;
import org.l2jmobius.gameserver.geoengine.GeoEngine;
import org.l2jmobius.gameserver.model.Location;
import org.l2jmobius.gameserver.model.actor.Npc;
import org.l2jmobius.gameserver.model.actor.enums.creature.Race;
import org.l2jmobius.gameserver.model.actor.holders.npc.FakePlayerHolder;
import org.l2jmobius.gameserver.model.actor.holders.npc.FakePlayerPvpBuild;
import org.l2jmobius.gameserver.model.actor.templates.NpcTemplate;
import org.l2jmobius.gameserver.model.spawns.Spawn;
import org.l2jmobius.gameserver.model.zone.ZoneType;
import org.l2jmobius.gameserver.model.zone.type.PeaceZone;
import org.l2jmobius.gameserver.model.zone.type.TownZone;
import org.l2jmobius.gameserver.network.serverpackets.ChangeWaitType;

/**
 * Peaceful fake players that walk around the towns (FakeTownPlayers* in FakePlayers.ini).<br>
 * Each one strolls around where it stands, now and then walks over to another respawn point of its town, and stops or sits down for a while in between, like a player waiting in town.
 */
public class FakePlayerTownManager
{
	private static final Logger LOGGER = Logger.getLogger(FakePlayerTownManager.class.getName());

	private static final int FIRST_NPC_ID = 9_400_000;
	private static final int TICK = 1000;
	private static final int RESPAWN_DELAY = 60;
	private static final int TRAVEL_CHANCE = 25; // % of moves that go to another respawn point of the town.
	private static final int RUN_CHANCE = 70; // % of moves done running.
	private static final int SIT_CHANCE = 6; // % of decisions that sit down.
	private static final int MOVE_CHANCE = 65; // % of decisions that move.

	private final List<Walker> _walkers = new ArrayList<>();
	private int _nextNpcId = FIRST_NPC_ID;

	protected FakePlayerTownManager()
	{
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

		int towns = 0;
		for (String entry : FakePlayersConfig.FAKE_TOWN_PLAYERS_TOWNS.split(","))
		{
			final Town town = parseTown(entry.trim());
			if (town == null)
			{
				continue;
			}

			for (int i = 0; i < FakePlayersConfig.FAKE_TOWN_PLAYERS_PER_TOWN; i++)
			{
				spawnWalker(town);
			}
			towns++;
		}

		LOGGER.info(getClass().getSimpleName() + ": Spawned " + _walkers.size() + " fake players in " + towns + " towns.");
		if (!_walkers.isEmpty())
		{
			ThreadPool.scheduleAtFixedRate(this::tick, TICK, TICK);
		}
	}

	/**
	 * @param entry region:minLevel-maxLevel[:RACE]
	 * @return the town, {@code null} if the entry is empty or invalid
	 */
	private Town parseTown(String entry)
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
			final List<Location> points = MapRegionData.getInstance().getSpawnLocsByRegionName(parts[0].trim());
			if (points.isEmpty() || (minLevel > maxLevel))
			{
				LOGGER.warning(getClass().getSimpleName() + ": Invalid town " + entry + " (unknown map region or levels).");
				return null;
			}

			return new Town(parts[0].trim(), points, minLevel, maxLevel, race);
		}
		catch (Exception e)
		{
			LOGGER.warning(getClass().getSimpleName() + ": Invalid town " + entry + ", expected region:minLevel-maxLevel[:RACE].");
			return null;
		}
	}

	private void spawnWalker(Town town)
	{
		final FakePlayerPvpManager names = FakePlayerPvpManager.getInstance();
		final int level = Rnd.get(town.minLevel, town.maxLevel);
		final FakePlayerPvpBuild build = getBuild(town.race);
		if (build == null)
		{
			return;
		}

		final String name = names.generateName();
		try
		{
			final NpcTemplate template = FakePlayerPvpFactory.createTownTemplate(build, level, _nextNpcId++, name);
			if (template == null)
			{
				names.releaseName(name);
				return;
			}

			final Location point = town.points.get(Rnd.get(town.points.size()));
			final Location loc = GeoEngine.getInstance().getValidLocation(point.getX(), point.getY(), point.getZ(), (point.getX() + Rnd.get(-150, 150)), (point.getY() + Rnd.get(-150, 150)), point.getZ(), 0);
			final Spawn spawn = new Spawn(template);
			spawn.setXYZ(loc.getX(), loc.getY(), loc.getZ());
			spawn.setHeading(-1);
			spawn.setAmount(1);
			spawn.setRespawnDelay(RESPAWN_DELAY);
			SpawnTable.getInstance().addSpawn(spawn);
			if (spawn.doSpawn(false) == null)
			{
				SpawnTable.getInstance().removeSpawn(spawn);
				names.releaseName(name);
				return;
			}

			spawn.startRespawn();
			_walkers.add(new Walker(town, spawn));
		}
		catch (Exception e)
		{
			LOGGER.log(Level.WARNING, getClass().getSimpleName() + ": Could not spawn town fake player " + build.getName() + " level " + level + " in " + town.region + ".", e);
			names.releaseName(name);
		}
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

	private void tick()
	{
		final long now = System.currentTimeMillis();
		for (Walker walker : _walkers)
		{
			try
			{
				act(walker, now);
			}
			catch (Exception e)
			{
				LOGGER.log(Level.WARNING, getClass().getSimpleName() + ": Problem while moving a town fake player.", e);
			}
		}
	}

	private void act(Walker walker, long now)
	{
		final Npc npc = walker.spawn.getLastSpawn();
		if ((npc == null) || !npc.isSpawned() || npc.isDead() || (now < walker.nextAction))
		{
			return;
		}

		// Walking somewhere: look again soon, then wait a bit once there.
		if (npc.isMoving())
		{
			walker.moving = true;
			walker.nextAction = now + TICK;
			return;
		}
		if (walker.moving)
		{
			walker.moving = false;
			walker.nextAction = now + Rnd.get(2000, 15000);
			return;
		}

		final FakePlayerHolder holder = npc.getTemplate().getFakePlayerInfo();
		if (holder.isSitting())
		{
			holder.setSitting(false);
			npc.broadcastPacket(new ChangeWaitType(npc, ChangeWaitType.WT_STANDING));
			walker.nextAction = now + Rnd.get(2000, 5000);
			return;
		}

		final int roll = Rnd.get(100);
		if (roll < SIT_CHANCE)
		{
			holder.setSitting(true);
			npc.broadcastPacket(new ChangeWaitType(npc, ChangeWaitType.WT_SITTING));
			walker.nextAction = now + Rnd.get(30000, 120000);
			return;
		}

		if (roll < (SIT_CHANCE + MOVE_CHANCE))
		{
			final Location destination = getDestination(walker.town, npc);
			if (destination != null)
			{
				if (Rnd.get(100) < RUN_CHANCE)
				{
					npc.setRunning();
				}
				else
				{
					npc.setWalking();
				}
				npc.getAI().setIntention(Intention.MOVE_TO, destination);
				walker.nextAction = now + TICK;
				return;
			}
		}

		// Stands around.
		walker.nextAction = now + Rnd.get(5000, 20000);
	}

	/**
	 * @param town the town
	 * @param npc the fake player
	 * @return a spot of the town to go to: another respawn point of the town now and then, else somewhere around, {@code null} if none was found
	 */
	private static Location getDestination(Town town, Npc npc)
	{
		final GeoEngine geo = GeoEngine.getInstance();
		if (Rnd.get(100) < TRAVEL_CHANCE)
		{
			final Location point = town.points.get(Rnd.get(town.points.size()));
			final int x = point.getX() + Rnd.get(-150, 150);
			final int y = point.getY() + Rnd.get(-150, 150);
			final int z = geo.getHeight(x, y, point.getZ());
			if (isInTown(x, y, z) && (npc.calculateDistance2D(new Location(x, y, z)) > 50))
			{
				return new Location(x, y, z); // Path finding takes it there.
			}
		}

		for (int attempt = 0; attempt < 3; attempt++)
		{
			final double angle = Rnd.nextDouble() * 2 * Math.PI;
			final int distance = Rnd.get(100, Math.max(101, FakePlayersConfig.FAKE_TOWN_PLAYERS_WANDER_RANGE));
			final Location loc = geo.getValidLocation(npc.getX(), npc.getY(), npc.getZ(), npc.getX() + (int) (Math.cos(angle) * distance), npc.getY() + (int) (Math.sin(angle) * distance), npc.getZ(), npc.getInstanceId());
			if ((npc.calculateDistance2D(loc) > 50) && isInTown(loc.getX(), loc.getY(), loc.getZ()))
			{
				return loc;
			}
		}
		return null;
	}

	private static boolean isInTown(int x, int y, int z)
	{
		for (ZoneType zone : ZoneManager.getInstance().getZones(x, y, z))
		{
			if ((zone instanceof TownZone) || (zone instanceof PeaceZone))
			{
				return true;
			}
		}
		return false;
	}

	/**
	 * @return how many town fake players there are
	 */
	public int getCount()
	{
		return _walkers.size();
	}

	private static class Town
	{
		final String region;
		final List<Location> points;
		final int minLevel;
		final int maxLevel;
		final Race race;

		Town(String region, List<Location> points, int minLevel, int maxLevel, Race race)
		{
			this.region = region;
			this.points = points;
			this.minLevel = minLevel;
			this.maxLevel = maxLevel;
			this.race = race;
		}
	}

	private static class Walker
	{
		final Town town;
		final Spawn spawn;
		long nextAction = System.currentTimeMillis() + Rnd.get(1000, 20000); // Not everyone sets off at once.
		boolean moving;

		Walker(Town town, Spawn spawn)
		{
			this.town = town;
			this.spawn = spawn;
		}
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
