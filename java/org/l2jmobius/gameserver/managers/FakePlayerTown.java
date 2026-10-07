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
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

import org.l2jmobius.commons.util.Rnd;
import org.l2jmobius.gameserver.config.GeoEngineConfig;
import org.l2jmobius.gameserver.data.SpawnTable;
import org.l2jmobius.gameserver.geoengine.GeoEngine;
import org.l2jmobius.gameserver.geoengine.geodata.Cell;
import org.l2jmobius.gameserver.geoengine.pathfinding.GeoLocation;
import org.l2jmobius.gameserver.geoengine.pathfinding.PathFinding;
import org.l2jmobius.gameserver.model.Location;
import org.l2jmobius.gameserver.model.WorldRegion;
import org.l2jmobius.gameserver.model.actor.Npc;
import org.l2jmobius.gameserver.model.actor.enums.creature.Race;
import org.l2jmobius.gameserver.model.actor.templates.NpcTemplate;
import org.l2jmobius.gameserver.model.interfaces.ILocational;
import org.l2jmobius.gameserver.model.spawns.Spawn;
import org.l2jmobius.gameserver.model.zone.ZoneType;
import org.l2jmobius.gameserver.model.zone.type.PeaceZone;
import org.l2jmobius.gameserver.model.zone.type.TownZone;
import org.l2jmobius.gameserver.util.LocationUtil;

/**
 * A town the town fake players live in (see {@link FakePlayerTownManager}): its zone, the npcs players go to (gatekeepers, warehouses, shops, blacksmiths, masters...), the spots in front of each npc where a player stands to talk to it, and the street points long walks
 * go through. Everything about the geodata is checked once and remembered, so a fake player never walks through a wall or stands behind a counter.
 */
final class FakePlayerTown
{
	/** What a town npc is for. */
	enum Role
	{
		GATEKEEPER,
		WAREHOUSE,
		GROCER,
		MERCHANT,
		BLACKSMITH,
		/** Newbie Guide and Adventurers' Guide: the newbie support magic. */
		GUIDE,
		/** Class masters, magisters, priests... */
		MASTER,
		/** Quest npcs, fishermen, pet managers, auctioneers, olympiad managers... */
		OTHER
	}
	
	/** Npcs whose dialog gives the newbie support magic (bypass supportmagic in data/html and data/scripts/ai/others/NewbieGuide). */
	private static final Set<Integer> GUIDE_IDS = Set.of(30598, 30599, 30600, 30601, 30602, 31076, 31077, 32135, 32327);
	/** Words in the name of npcs that are things rather than people (chests, jars, towers...). */
	private static final String[] OBJECT_WORDS =
	{
		"chest",
		"jar",
		"box",
		"tower",
		"warpgate",
		"statue",
		"monument",
		"totem",
		"flag",
		"altar",
		"crystal",
		"barrel",
		"stone",
		"pillar",
		"cubic"
	};
	
	/** One walk (straight or found by path finding) is kept under this, the path finding buffers don't go much further. */
	static final int LEG_RANGE = 2600;
	/** Street points closer than this to one known to be connected to the town center are tried to connect to it. */
	private static final int LINK_RANGE = 1500;
	/** How many street points are looked for per town zone. */
	private static final int HUB_SAMPLES = 260;
	private static final int HUB_SPACING = 330;
	private static final int SPOTS_PER_NPC = 7;
	
	/** A town npc and the spots players stand at to talk to it. */
	static final class TownNpc
	{
		final Spawn spawn;
		final Role role;
		final String name;
		/** Set once by the preparation (another thread), {@code null} until then. */
		volatile List<Location> spots;
		final Map<Location, FakePlayerTownVisitor> taken = new HashMap<>();
		
		TownNpc(Spawn spawn, Role role)
		{
			this.spawn = spawn;
			this.role = role;
			name = spawn.getTemplate().getName();
		}
		
		/**
		 * @return the npc, {@code null} if it isn't in the world right now
		 */
		Npc getNpc()
		{
			final Npc npc = spawn.getLastSpawn();
			return (npc != null) && npc.isSpawned() && !npc.isDead() ? npc : null;
		}
	}
	
	final String region;
	final String shortName;
	final List<Location> points;
	final int minLevel;
	final int maxLevel;
	final Race race;
	final List<ZoneType> zones = new ArrayList<>();
	final Map<Role, List<TownNpc>> npcs = new EnumMap<>(Role.class);
	/** Changed only by the manager's thread, read by //faketown as well. */
	final List<FakePlayerTownVisitor> visitors = new CopyOnWriteArrayList<>();
	final List<FakePlayerTownCircle> circles = new CopyOnWriteArrayList<>();
	private final Deque<String> _recentLines = new ArrayDeque<>();
	private final List<Location> _groundPoints = new ArrayList<>();
	/** Open street points of the town, to stand around and to appear at. */
	private List<Location> _hubs = Collections.emptyList();
	/** The street points known to be connected to the town center by a path, which walks go through (the respawn points until the preparation is done). */
	private volatile List<Location> _routeHubs;
	private volatile boolean _prepared;
	
	// Population.
	double populationFactor = 1;
	long nextFactorChange;
	long nextArrival;
	long nextLoneChat;
	/** No new conversation starts before this (a town isn't a chat room). */
	long nextCircle;
	long nextVendor;
	/** When the fake players next look around for players walking by. */
	long nextNotice;
	/** Its first fake players came (once it was prepared). */
	boolean populated;
	
	FakePlayerTown(String region, String shortName, List<Location> points, int minLevel, int maxLevel, Race race)
	{
		this.region = region;
		this.shortName = shortName;
		this.points = points;
		this.minLevel = minLevel;
		this.maxLevel = maxLevel;
		this.race = race;
		
		final GeoEngine geo = GeoEngine.getInstance();
		for (Location point : points)
		{
			_groundPoints.add(new Location(point.getX(), point.getY(), geo.getHeight(point.getX(), point.getY(), point.getZ())));
		}
		_routeHubs = Collections.unmodifiableList(new ArrayList<>(_groundPoints));
	}
	
	/**
	 * Finds the zones of the town (the town zones its respawn points are in) and the npcs in them.
	 */
	void discover()
	{
		for (Location point : _groundPoints)
		{
			for (ZoneType zone : ZoneManager.getInstance().getZones(point.getX(), point.getY(), point.getZ()))
			{
				if ((zone instanceof TownZone) && !zones.contains(zone))
				{
					zones.add(zone);
				}
			}
		}
		
		for (Set<Spawn> spawns : SpawnTable.getInstance().getSpawnTable().values())
		{
			for (Spawn spawn : spawns)
			{
				final NpcTemplate template = spawn.getTemplate();
				if ((template == null) || template.isFakePlayer() || (spawn.getInstanceId() != 0) || !isInside(spawn.getX(), spawn.getY(), spawn.getZ()))
				{
					continue;
				}
				
				final Role role = classify(template);
				if (role != null)
				{
					npcs.computeIfAbsent(role, _ -> new ArrayList<>()).add(new TownNpc(spawn, role));
				}
			}
		}
		
		_hubs = sampleHubs();
	}
	
	/**
	 * Works out, once, everything about the geodata of the town that takes path finding: which street points a walk can go through (connected to the town center), and the spots in front of each npc. Runs on its own thread at server start; until it is done, the town's fake
	 * players only stand around.
	 */
	void prepare()
	{
		// Street points connected to the respawn points, one link after the other.
		final List<Location> connected = new ArrayList<>(_groundPoints);
		final List<Location> unknown = new ArrayList<>(_hubs);
		unknown.removeAll(_groundPoints);
		final Map<Location, Integer> tries = new HashMap<>();
		final Deque<Location> queue = new ArrayDeque<>(_groundPoints);
		while (!queue.isEmpty() && !unknown.isEmpty())
		{
			final Location from = queue.pollFirst();
			for (Location hub : new ArrayList<>(unknown))
			{
				if ((distance2D(from, hub) > LINK_RANGE) || (tries.merge(hub, 1, Integer::sum) > 4))
				{
					continue;
				}
				
				if (canReach(from, hub))
				{
					unknown.remove(hub);
					connected.add(hub);
					queue.addLast(hub);
				}
			}
		}
		_routeHubs = Collections.unmodifiableList(connected);
		
		for (List<TownNpc> list : npcs.values())
		{
			for (TownNpc townNpc : list)
			{
				townNpc.spots = findSpots(townNpc);
			}
		}
		_prepared = true;
	}
	
	/**
	 * @return {@code true} once {@link #prepare()} is done
	 */
	boolean isPrepared()
	{
		return _prepared;
	}
	
	/**
	 * @param template an npc template
	 * @return what players go to that npc for, {@code null} if they don't
	 */
	private static Role classify(NpcTemplate template)
	{
		if (GUIDE_IDS.contains(template.getId()))
		{
			return Role.GUIDE;
		}
		
		final String type = template.getType();
		final String title = template.getTitle() != null ? template.getTitle().toLowerCase() : "";
		final String name = template.getName() != null ? template.getName().toLowerCase() : "";
		if (title.contains("blacksmith"))
		{
			return Role.BLACKSMITH;
		}
		
		switch (type)
		{
			case "Teleporter":
			{
				return Role.GATEKEEPER;
			}
			case "Warehouse":
			{
				return Role.WAREHOUSE;
			}
			case "Merchant":
			{
				return title.contains("grocer") || title.equals("trader") ? Role.GROCER : Role.MERCHANT;
			}
			case "Trainer":
			{
				return Role.MASTER;
			}
			case "Fisherman":
			case "PetManager":
			case "Auctioneer":
			case "OlympiadManager":
			case "SignsPriest":
			case "DawnPriest":
			case "DuskPriest":
			case "Adventurer":
			{
				return isObject(name) ? null : Role.OTHER;
			}
			case "Folk":
			{
				if (!template.isTalkable() || isObject(name))
				{
					return null;
				}
				
				// A custom teleporter (the hotzone teleporter of Giran...) is left from like a gatekeeper.
				return name.contains("teleporter") ? Role.GATEKEEPER : Role.OTHER;
			}
			default:
			{
				return type.startsWith("VillageMaster") ? Role.MASTER : null;
			}
		}
	}
	
	private static boolean isObject(String name)
	{
		for (String word : OBJECT_WORDS)
		{
			if (name.contains(word))
			{
				return true;
			}
		}
		return false;
	}
	
	/**
	 * @return {@code true} if the town has a zone and npcs to visit
	 */
	boolean isReady()
	{
		return !zones.isEmpty() && !npcs.isEmpty();
	}
	
	/**
	 * @param x the x
	 * @param y the y
	 * @param z the z
	 * @return {@code true} if the point is in the town
	 */
	boolean isInside(int x, int y, int z)
	{
		for (ZoneType zone : zones)
		{
			if (zone.isInsideZone(x, y, z))
			{
				return true;
			}
		}
		
		// No town zone found (custom regions): anything peaceful around the respawn points.
		if (zones.isEmpty())
		{
			for (ZoneType zone : ZoneManager.getInstance().getZones(x, y, z))
			{
				if ((zone instanceof TownZone) || (zone instanceof PeaceZone))
				{
					return true;
				}
			}
		}
		return false;
	}
	
	/**
	 * @param role a role
	 * @return the npcs of that role in the town
	 */
	List<TownNpc> getNpcs(Role role)
	{
		return npcs.getOrDefault(role, Collections.emptyList());
	}
	
	/**
	 * @param role a role
	 * @return {@code true} if the town has an npc of that role
	 */
	boolean has(Role role)
	{
		return !getNpcs(role).isEmpty();
	}
	
	/**
	 * Picks an npc of a role for a fake player standing at {@code from}: closer ones are more likely, but not always the closest (players have their habits).
	 * @param role the role
	 * @param from where the fake player is
	 * @return the npc, {@code null} if there is none with a free spot
	 */
	TownNpc pickNpc(Role role, ILocational from)
	{
		final List<TownNpc> candidates = getNpcs(role);
		if (candidates.isEmpty())
		{
			return null;
		}
		
		double total = 0;
		final double[] weights = new double[candidates.size()];
		for (int i = 0; i < candidates.size(); i++)
		{
			final TownNpc townNpc = candidates.get(i);
			final Npc npc = townNpc.getNpc();
			final List<Location> spots = townNpc.spots;
			if ((npc == null) || (spots == null) || (spots.size() <= townNpc.taken.size()))
			{
				continue;
			}
			
			final double distance = Math.max(300, distance2D(from, npc));
			weights[i] = 1 / Math.pow(distance, 1.3);
			total += weights[i];
		}
		
		double roll = Rnd.nextDouble() * total;
		for (int i = 0; i < candidates.size(); i++)
		{
			if (weights[i] <= 0)
			{
				continue;
			}
			
			roll -= weights[i];
			if (roll <= 0)
			{
				return candidates.get(i);
			}
		}
		return null;
	}
	
	/**
	 * Takes a free spot in front of an npc.
	 * @param townNpc the npc
	 * @param visitor who takes it
	 * @return the spot, {@code null} if none is free (or the npc can't be reached)
	 */
	Location takeSpot(TownNpc townNpc, FakePlayerTownVisitor visitor)
	{
		final List<Location> free = new ArrayList<>();
		for (Location spot : getSpots(townNpc))
		{
			if (!townNpc.taken.containsKey(spot))
			{
				free.add(spot);
			}
		}
		
		if (free.isEmpty())
		{
			return null;
		}
		
		final Location spot = free.get(Rnd.get(free.size()));
		townNpc.taken.put(spot, visitor);
		return spot;
	}
	
	/**
	 * @param townNpc the npc
	 * @return the spots in front of it, empty if none (or not known yet)
	 */
	List<Location> getSpots(TownNpc townNpc)
	{
		final List<Location> spots = townNpc.spots;
		return spots != null ? spots : Collections.emptyList();
	}
	
	/**
	 * @param townNpc the npc
	 * @param spot a spot taken with {@link #takeSpot}
	 */
	static void releaseSpot(TownNpc townNpc, Location spot)
	{
		if ((townNpc != null) && (spot != null))
		{
			townNpc.taken.remove(spot);
		}
	}
	
	/**
	 * Spots in front of an npc where a player stands to talk to it: on the side the npc faces (a shopkeeper faces its customers over the counter), close enough to talk, on the ground the npc stands on, not on another npc, and reachable from the street.
	 * @param townNpc the npc
	 * @return the spots, empty if none
	 */
	private List<Location> findSpots(TownNpc townNpc)
	{
		final Npc npc = townNpc.getNpc();
		if (npc == null)
		{
			return Collections.emptyList();
		}
		
		final GeoEngine geo = GeoEngine.getInstance();
		final Location anchor = nearest(_routeHubs, new Location(npc.getX(), npc.getY(), npc.getZ()));
		final double front = Math.toRadians(LocationUtil.convertHeadingToDegree(npc.getHeading()));
		final List<Location> spots = new ArrayList<>();
		for (int attempt = 0; (attempt < 20) && (spots.size() < SPOTS_PER_NPC); attempt++)
		{
			final double spread = Math.toRadians(attempt < 14 ? 70 : 125);
			final double angle = front + (((Rnd.nextDouble() * 2) - 1) * spread);
			final int distance = Rnd.get(50, 100) + (int) npc.getCollisionRadius();
			final int x = npc.getX() + (int) (Math.cos(angle) * distance);
			final int y = npc.getY() + (int) (Math.sin(angle) * distance);
			final int z = geo.getHeight(x, y, npc.getZ());
			if ((Math.abs(z - npc.getZ()) > 60) || !isInside(x, y, z) || isCrowded(spots, x, y, 30) || isOnOtherNpc(townNpc, x, y))
			{
				continue;
			}
			
			// Reachable from the street: in a straight line from a spot already known to be (cheap), or found by path finding.
			final Location spot = new Location(x, y, z);
			boolean reachable = false;
			for (Location known : spots)
			{
				if (geo.canMoveToTarget(known.getX(), known.getY(), known.getZ(), x, y, z, 0))
				{
					reachable = true;
					break;
				}
			}
			if (reachable || ((anchor != null) && canReach(anchor, spot)))
			{
				spots.add(spot);
			}
		}
		return spots;
	}
	
	private boolean isOnOtherNpc(TownNpc self, int x, int y)
	{
		for (List<TownNpc> list : npcs.values())
		{
			for (TownNpc other : list)
			{
				if ((other != self) && (Math.abs(other.spawn.getX() - x) < 35) && (Math.abs(other.spawn.getY() - y) < 35))
				{
					return true;
				}
			}
		}
		return false;
	}
	
	private static boolean isCrowded(List<Location> locations, int x, int y, int distance)
	{
		final long distanceSq = (long) distance * distance;
		for (Location location : locations)
		{
			final long dx = location.getX() - x;
			final long dy = location.getY() - y;
			if (((dx * dx) + (dy * dy)) < distanceSq)
			{
				return true;
			}
		}
		return false;
	}
	
	/**
	 * @return the respawn points (on the ground) and open street points of the town
	 */
	private List<Location> sampleHubs()
	{
		final GeoEngine geo = GeoEngine.getInstance();
		final List<Location> hubs = new ArrayList<>(_groundPoints);
		for (ZoneType zone : zones)
		{
			for (int i = 0; i < HUB_SAMPLES; i++)
			{
				final Location point = zone.getZone().getRandomPoint();
				if (!zone.isInsideZone(point.getX(), point.getY(), point.getZ()) || isCrowded(hubs, point.getX(), point.getY(), HUB_SPACING))
				{
					continue;
				}
				
				// Open ground only: not against a wall, a fence or a counter.
				if (geo.hasGeo(point.getX(), point.getY()) && geo.checkNearestNswe(GeoEngine.getGeoX(point.getX()), GeoEngine.getGeoY(point.getY()), point.getZ(), Cell.NSWE_ALL))
				{
					hubs.add(point);
				}
			}
		}
		return Collections.unmodifiableList(hubs);
	}
	
	/**
	 * @return the respawn points (on the ground) and open street points of the town
	 */
	List<Location> getHubs()
	{
		return _hubs.isEmpty() ? _groundPoints : _hubs;
	}
	
	/**
	 * @return the street points walks go through (connected to the town center)
	 */
	List<Location> getRouteHubs()
	{
		return _routeHubs;
	}
	
	/**
	 * @return a random respawn point of the town, on the ground
	 */
	Location getRandomPoint()
	{
		return _groundPoints.get(Rnd.get(_groundPoints.size()));
	}
	
	/**
	 * @return a random street point of the town (one walks can reach once the town is prepared)
	 */
	Location getRandomHub()
	{
		final List<Location> hubs = _prepared ? _routeHubs : getHubs();
		return hubs.get(Rnd.get(hubs.size()));
	}
	
	/**
	 * @param locations some locations
	 * @param from a location
	 * @return the location closest to {@code from}, height counting double (a street point on another floor of the town is far), {@code null} if there is none
	 */
	static Location nearest(List<Location> locations, ILocational from)
	{
		Location nearest = null;
		double nearestDistance = Double.MAX_VALUE;
		for (Location location : locations)
		{
			final double dz = 2.0 * (location.getZ() - from.getZ());
			final double distance = distanceSq2D(from, location) + (dz * dz);
			if (distance < nearestDistance)
			{
				nearest = location;
				nearestDistance = distance;
			}
		}
		return nearest;
	}
	
	/**
	 * @param from a point
	 * @param to another point
	 * @return {@code true} if walking from one to the other doesn't go through a wall: a straight line, or a path the path finding finds within {@link #LEG_RANGE}
	 */
	static boolean canReach(ILocational from, ILocational to)
	{
		final GeoEngine geo = GeoEngine.getInstance();
		if (geo.canMoveToTarget(from.getX(), from.getY(), from.getZ(), to.getX(), to.getY(), to.getZ(), 0))
		{
			return true;
		}
		
		if ((GeoEngineConfig.PATHFINDING <= 0) || (distance2D(from, to) > LEG_RANGE))
		{
			return false;
		}
		
		final List<GeoLocation> path = PathFinding.getInstance().findPath(from.getX(), from.getY(), from.getZ(), to.getX(), to.getY(), to.getZ(), 0, false);
		return (path != null) && !path.isEmpty();
	}
	
	/**
	 * The next walk towards {@code destination}: straight there when it is close enough for one walk (the movement's own path finding takes it around the walls), otherwise to a connected street point on the way.
	 * @param from where the fake player is
	 * @param destination where it goes
	 * @param avoid a street point that just failed, {@code null} for none
	 * @return where to walk now, {@code null} if there is no way
	 */
	Location nextLeg(ILocational from, Location destination, Location avoid)
	{
		final double distance = distance2D(from, destination);
		if ((distance <= LEG_RANGE) && (avoid != destination))
		{
			return destination;
		}
		
		// A street point in reach that brings it closer, the most direct ones first (with some randomness, not everyone takes the same street).
		Location best = null;
		double bestCost = Double.MAX_VALUE;
		for (Location hub : _routeHubs)
		{
			final double toHub = distance2D(from, hub);
			if ((hub == avoid) || (toHub < 150) || (toHub > LEG_RANGE) || (Math.abs(hub.getZ() - from.getZ()) > 600))
			{
				continue;
			}
			
			final double rest = distance2D(hub, destination);
			if (rest >= (distance - 150))
			{
				continue;
			}
			
			final double cost = toHub + (rest * 1.15) + Rnd.get(0, 300);
			if (cost < bestCost)
			{
				best = hub;
				bestCost = cost;
			}
		}
		
		if (best == null)
		{
			return null;
		}
		
		// Not exactly on the street point either: each one cuts the corner its own way.
		final Location near = GeoEngine.getInstance().getValidLocation(best.getX(), best.getY(), best.getZ(), best.getX() + Rnd.get(-60, 60), best.getY() + Rnd.get(-60, 60), best.getZ(), 0);
		return distance2D(near, best) > 10 ? near : best;
	}
	
	/**
	 * Forgets a spot in front of an npc that turned out not to be reachable.
	 * @param townNpc the npc
	 * @param spot the spot
	 */
	static void dropSpot(TownNpc townNpc, Location spot)
	{
		final List<Location> spots = townNpc.spots;
		if ((spots != null) && spots.contains(spot))
		{
			final List<Location> rest = new ArrayList<>(spots);
			rest.remove(spot);
			townNpc.spots = Collections.unmodifiableList(rest);
		}
	}
	
	/**
	 * @param a a point
	 * @param b another point
	 * @return the distance between them, height left out
	 */
	static double distance2D(ILocational a, ILocational b)
	{
		return Math.sqrt(distanceSq2D(a, b));
	}
	
	/**
	 * @param a a point
	 * @param b another point
	 * @return the squared distance between them, height left out
	 */
	static double distanceSq2D(ILocational a, ILocational b)
	{
		final double dx = a.getX() - b.getX();
		final double dy = a.getY() - b.getY();
		return (dx * dx) + (dy * dy);
	}
	
	/**
	 * @param npc a fake player
	 * @return {@code true} if a player is around: movement uses the geodata only then, and nothing needs to look right when nobody watches
	 */
	static boolean isWatched(Npc npc)
	{
		final WorldRegion region = npc.getWorldRegion();
		return (region != null) && region.areNeighborsActive();
	}
	
	/**
	 * @param text a line
	 * @return {@code true} if it was said in this town lately
	 */
	boolean wasSaidLately(String text)
	{
		return _recentLines.contains(text);
	}
	
	/**
	 * @param text a line said in this town
	 */
	void remember(String text)
	{
		_recentLines.addLast(text);
		while (_recentLines.size() > 60)
		{
			_recentLines.removeFirst();
		}
	}
	
	/**
	 * @return how many resident buffers are in town
	 */
	int countBuffers()
	{
		int count = 0;
		for (FakePlayerTownVisitor visitor : visitors)
		{
			if (visitor.isBuffer())
			{
				count++;
			}
		}
		return count;
	}
	
	/**
	 * @return how many fake players came to sell in a private store
	 */
	int countVendors()
	{
		int count = 0;
		for (FakePlayerTownVisitor visitor : visitors)
		{
			if (visitor.isVendor())
			{
				count++;
			}
		}
		return count;
	}
	
	/**
	 * @return how many of its visitors came to sell in a black market store
	 */
	int countBlackMarkets()
	{
		int count = 0;
		for (FakePlayerTownVisitor visitor : visitors)
		{
			if (visitor.isVendor() && visitor.store.isBlackMarket())
			{
				count++;
			}
		}
		return count;
	}
	
	@Override
	public String toString()
	{
		return region;
	}
}
