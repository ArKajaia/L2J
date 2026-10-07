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
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

import org.l2jmobius.commons.util.Rnd;
import org.l2jmobius.gameserver.ai.Intention;
import org.l2jmobius.gameserver.config.custom.TownLifeConfig;
import org.l2jmobius.gameserver.data.SpawnTable;
import org.l2jmobius.gameserver.geoengine.GeoEngine;
import org.l2jmobius.gameserver.geoengine.geodata.Cell;
import org.l2jmobius.gameserver.managers.FakePlayerTown.Role;
import org.l2jmobius.gameserver.managers.FakePlayerTown.TownNpc;
import org.l2jmobius.gameserver.managers.TownLifeResident.Kind;
import org.l2jmobius.gameserver.model.Location;
import org.l2jmobius.gameserver.model.World;
import org.l2jmobius.gameserver.model.WorldObject;
import org.l2jmobius.gameserver.model.actor.Npc;
import org.l2jmobius.gameserver.model.actor.Player;
import org.l2jmobius.gameserver.model.interfaces.ILocational;
import org.l2jmobius.gameserver.model.nightcycle.NightPhase;
import org.l2jmobius.gameserver.model.spawns.Spawn;
import org.l2jmobius.gameserver.network.enums.ChatType;
import org.l2jmobius.gameserver.network.serverpackets.MagicSkillUse;
import org.l2jmobius.gameserver.util.LocationUtil;

/**
 * A town of the town life (see {@link TownLifeManager}), or a harbor: the places its npcs go to (houses, the square, the tavern, the patrol round, the shore), worked out once from the geodata, and its npcs.
 */
final class TownLifeTown
{
	// Npc templates (data/stats/npcs/custom/TownLife.xml).
	private static final int[] TOWNSFOLK =
	{
		900400,
		900401,
		900402,
		900403,
		900404,
		900405,
		900406,
		900407,
		900408,
		900409,
		900410,
		900411
	};
	private static final int[] PORTERS =
	{
		900415,
		900416
	};
	private static final int ERRAND_RUNNER = 900417;
	private static final int SWEEPER = 900418;
	private static final int[] CHILDREN =
	{
		900420,
		900421,
		900422,
		900423,
		900424
	};
	private static final int[] PATROLS =
	{
		900426,
		900427
	};
	private static final int[] NIGHT_WATCH =
	{
		900428,
		900429
	};
	private static final int LAMPLIGHTER = 900431;
	private static final int CRIER = 900433;
	private static final int[] FISHERMEN =
	{
		900435,
		900436,
		900437
	};
	private static final int[] DOCK_WORKERS =
	{
		900438,
		900439
	};
	private static final int[] BANNERS =
	{
		900441,
		900442,
		900443,
		900444,
		900445
	};
	private static final int BONFIRE = 900446;
	
	/** Fireworks over the square at dusk on festival day: Firework and Large Firework. */
	private static final int[] FIREWORK_SKILLS =
	{
		2024,
		2025
	};
	/** How long the fireworks last after dusk falls on festival day. */
	private static final long FIREWORKS_DURATION = 240_000;
	
	private static final int PLAY_RADIUS = 340;
	/** How far around the wharf manager the harbor folk go. */
	private static final int SHORE_RANGE = 1000;
	/** A house: a spot this closed in (of 16 directions, this many walled within {@link #ENCLOSED_RANGE}). */
	private static final int ENCLOSED_WALLS = 13;
	private static final int ENCLOSED_RANGE = 550;
	
	/** A spot in front of a town npc. */
	record Spot(Location location, Npc npc)
	{
	}
	
	/** Two townsfolk talking in the street. */
	static final class Conversation
	{
		final TownLifeResident first;
		final TownLifeResident second;
		final String[] lines;
		final Location meetingPoint;
		final long started;
		boolean talking;
		int index;
		long nextLine;
		
		Conversation(TownLifeResident first, TownLifeResident second, String[] lines, Location meetingPoint, long now)
		{
			this.first = first;
			this.second = second;
			this.lines = lines;
			this.meetingPoint = meetingPoint;
			started = now;
		}
		
		boolean isApproaching()
		{
			return !talking;
		}
	}
	
	/** A retail npc that goes home at dusk and comes back at dawn. */
	private static final class Homebound
	{
		final Spawn spawn;
		Location home;
		final boolean walker;
		final double bedtime = Rnd.get(10, 80) / 100.0;
		final double wakeTime = Rnd.get(0, 60) / 100.0;
		/** 0 at its spot (or on its route), 1 walking home, 2 at home (invisible), 3 walking back. */
		int state;
		long deadline;
		Location lastPosition;
		int stuck;
		
		Homebound(Spawn spawn, boolean walker)
		{
			this.spawn = spawn;
			this.walker = walker;
		}
	}
	
	// Definition (data/TownLife.xml).
	final String name;
	/** The map region of a town, {@code null} for a harbor. */
	final String region;
	/** The wharf manager of a harbor, 0 for a town. */
	final int wharfId;
	final double sizeFactor;
	final boolean hasChildren;
	final Location squareOverride;
	final int tavernNpcId;
	final Location tavernOverride;
	final List<Integer> homeboundIds;
	
	/** The streets and npcs of a town (shared code with the town fake players), {@code null} for a harbor. */
	private FakePlayerTown _streets;
	
	// Places, worked out by prepare().
	Location center;
	Location playCenter;
	Location crierSpot;
	int crierHeading;
	Location tavernCenter;
	final List<Location> homes = new ArrayList<>();
	final List<Location> tavernSpots = new ArrayList<>();
	final List<Location> patrolRoute = new ArrayList<>();
	final List<Location> lampRoute = new ArrayList<>();
	final List<Location> shoreSpots = new ArrayList<>();
	final List<Integer> shoreHeadings = new ArrayList<>();
	final List<Location> dockPoints = new ArrayList<>();
	final List<Location> bannerSpots = new ArrayList<>();
	final List<Spot> shopSpots = new ArrayList<>();
	private final List<Location> _pickups = new ArrayList<>();
	private final List<Location> _dropoffs = new ArrayList<>();
	
	final List<TownLifeResident> residents = new ArrayList<>();
	private final List<TownLifeResident> _children = new ArrayList<>();
	private final List<Homebound> _homebound = new ArrayList<>();
	private final List<Conversation> _conversations = new ArrayList<>();
	private volatile boolean _ready;
	
	// Town-wide pacing.
	private long _nextGreet;
	private long _nextTavernLine;
	private long _nextCry;
	private long _nextFirework;
	private long _fireworksUntil;
	private NightPhase _lastPhase;
	
	// The children's game.
	private TownLifeResident _it;
	private TownLifeResident _chased;
	private long _chaseStarted;
	private long _nextPlayLine;
	private TownLifeResident _asking;
	private long _askUntil;
	private boolean _askSaid;
	private long _nextAsk;
	
	TownLifeTown(String name, String region, int wharfId, double sizeFactor, boolean hasChildren, Location squareOverride, int tavernNpcId, Location tavernOverride, List<Integer> homeboundIds)
	{
		this.name = name;
		this.region = region;
		this.wharfId = wharfId;
		this.sizeFactor = sizeFactor;
		this.hasChildren = hasChildren;
		this.squareOverride = squareOverride;
		this.tavernNpcId = tavernNpcId;
		this.tavernOverride = tavernOverride;
		this.homeboundIds = homeboundIds;
	}
	
	boolean isHarbor()
	{
		return wharfId > 0;
	}
	
	boolean isReady()
	{
		return _ready;
	}
	
	/**
	 * Finds the town's zone and npcs (on the server start thread, like the town fake players).
	 * @param points the respawn points of the town's map region
	 * @return {@code false} if the town can't be found
	 */
	boolean discover(List<Location> points)
	{
		if (isHarbor())
		{
			final Spawn wharf = findSpawn(wharfId, null);
			if (wharf == null)
			{
				return false;
			}
			center = ground(new Location(wharf.getX(), wharf.getY(), wharf.getZ()));
			return true;
		}
		
		_streets = new FakePlayerTown(region, name, points, 1, 85, null);
		_streets.discover();
		long x = 0;
		long y = 0;
		for (Location point : points)
		{
			x += point.getX();
			y += point.getY();
		}
		final Location first = points.get(0);
		center = ground(new Location((int) (x / points.size()), (int) (y / points.size()), first.getZ()));
		return true;
	}
	
	/**
	 * Works out the places of the town from the geodata, once (path finding: on its own thread), then its npcs.
	 */
	void prepare()
	{
		if (_streets != null)
		{
			_streets.prepare();
			// The center of the town is a street point near the middle of the respawn points.
			final Location nearest = FakePlayerTown.nearest(_streets.getRouteHubs(), center);
			if (nearest != null)
			{
				center = nearest;
			}
			findShopSpots();
			findHomes();
			findSquare();
			findTavern();
			findRoutes();
			findBanners();
			findHomebound();
		}
		else
		{
			findShore();
		}
		createResidents();
		_ready = true;
	}
	
	// ------------------------------------------------------------------
	// Places
	// ------------------------------------------------------------------
	
	private void findShopSpots()
	{
		for (Role role : Role.values())
		{
			if ((role == Role.GATEKEEPER) || (role == Role.GUIDE))
			{
				continue;
			}
			
			for (TownNpc townNpc : _streets.getNpcs(role))
			{
				final Npc npc = townNpc.getNpc();
				if (npc == null)
				{
					continue;
				}
				
				final List<Location> spots = _streets.getSpots(townNpc);
				for (Location spot : spots)
				{
					shopSpots.add(new Spot(spot, npc));
				}
				if (spots.isEmpty())
				{
					continue;
				}
				
				if (role == Role.WAREHOUSE)
				{
					_pickups.add(spots.get(0));
				}
				else if ((role == Role.GROCER) || (role == Role.MERCHANT) || (role == Role.BLACKSMITH))
				{
					_dropoffs.add(spots.get(0));
				}
			}
		}
	}
	
	/**
	 * The houses the npcs go into at dusk: spots in front of the town npcs that are walled in on nearly every side (inside a building), reachable from the street. A town without any uses the street points farthest from the center (they walk out of town).
	 */
	private void findHomes()
	{
		for (Spot spot : shopSpots)
		{
			final Location location = spot.location();
			if (!isCrowded(homes, location, 250) && isEnclosed(location))
			{
				homes.add(location);
			}
		}
		
		if (homes.size() < 3)
		{
			final List<Location> hubs = new ArrayList<>(_streets.getRouteHubs());
			hubs.sort(Comparator.comparingDouble(hub -> -FakePlayerTown.distance2D(hub, center)));
			for (Location hub : hubs)
			{
				if (homes.size() >= 5)
				{
					break;
				}
				if (!isCrowded(homes, hub, 400))
				{
					homes.add(hub);
				}
			}
		}
	}
	
	/**
	 * Where the children play and the crier stands: the square given in TownLife.xml, otherwise the most open street point near the center.
	 */
	private void findSquare()
	{
		final List<Location> hubs = _streets.getRouteHubs();
		if (squareOverride != null)
		{
			playCenter = ground(squareOverride);
		}
		else
		{
			Location best = center;
			int bestOpen = -1;
			for (Location hub : hubs)
			{
				final double distance = FakePlayerTown.distance2D(hub, center);
				if (distance > 1500)
				{
					continue;
				}
				
				final int open = countOpen(hub, PLAY_RADIUS, 24);
				if ((open > bestOpen) || ((open == bestOpen) && (distance < FakePlayerTown.distance2D(best, center))))
				{
					best = hub;
					bestOpen = open;
				}
			}
			playCenter = best;
		}
		
		// The crier stands at the edge of the square, facing it.
		Location crier = null;
		double crierDistance = Double.MAX_VALUE;
		for (Location hub : hubs)
		{
			final double distance = FakePlayerTown.distance2D(hub, playCenter);
			if ((distance >= 220) && (distance <= 800) && (distance < crierDistance))
			{
				crier = hub;
				crierDistance = distance;
			}
		}
		crierSpot = crier != null ? crier : center;
		crierHeading = LocationUtil.calculateHeadingFrom(crierSpot, playCenter);
	}
	
	/**
	 * Where the crowd gathers at night: in front of the tavern npc of TownLife.xml (or the point given there), otherwise the grocer nearest the center.
	 */
	private void findTavern()
	{
		final List<Location> anchors = new ArrayList<>();
		if (tavernOverride != null)
		{
			anchors.add(ground(tavernOverride));
		}
		else
		{
			TownNpc tavern = null;
			double tavernDistance = Double.MAX_VALUE;
			for (Role role : new Role[]
			{
				Role.OTHER,
				Role.GROCER
			})
			{
				for (TownNpc townNpc : _streets.getNpcs(role))
				{
					final boolean wanted = tavernNpcId > 0 ? townNpc.spawn.getId() == tavernNpcId : role == Role.GROCER;
					if (!wanted || _streets.getSpots(townNpc).isEmpty())
					{
						continue;
					}
					
					final double distance = FakePlayerTown.distance2D(townNpc.spawn, center);
					if (distance < tavernDistance)
					{
						tavern = townNpc;
						tavernDistance = distance;
					}
				}
			}
			if (tavern != null)
			{
				anchors.addAll(_streets.getSpots(tavern));
			}
		}
		
		if (anchors.isEmpty())
		{
			anchors.add(center);
		}
		
		// The spots in front of it, and more around them (on the same ground, in a straight line from one).
		final GeoEngine geo = GeoEngine.getInstance();
		tavernSpots.addAll(anchors);
		final int wanted = Math.max(8, TownLifeConfig.TAVERN_CROWD * 2);
		for (int attempt = 0; (attempt < 80) && (tavernSpots.size() < wanted); attempt++)
		{
			final Location from = anchors.get(Rnd.get(anchors.size()));
			final double angle = Rnd.nextDouble() * Math.PI * 2;
			final int distance = Rnd.get(40, 140);
			final int x = from.getX() + (int) (Math.cos(angle) * distance);
			final int y = from.getY() + (int) (Math.sin(angle) * distance);
			final int z = geo.getHeight(x, y, from.getZ());
			if ((Math.abs(z - from.getZ()) < 40) && !isCrowded(tavernSpots, x, y, 35) && geo.canMoveToTarget(from.getX(), from.getY(), from.getZ(), x, y, z, 0))
			{
				tavernSpots.add(new Location(x, y, z));
			}
		}
		
		long x = 0;
		long y = 0;
		for (Location spot : tavernSpots)
		{
			x += spot.getX();
			y += spot.getY();
		}
		tavernCenter = ground(new Location((int) (x / tavernSpots.size()), (int) (y / tavernSpots.size()), tavernSpots.get(0).getZ()));
	}
	
	/**
	 * The patrol round (street points around the center, in order around it) and the lamplighter's round.
	 */
	private void findRoutes()
	{
		final List<Location> ring = new ArrayList<>();
		for (Location hub : _streets.getRouteHubs())
		{
			final double distance = FakePlayerTown.distance2D(hub, center);
			if ((distance >= 400) && (distance <= 2200))
			{
				ring.add(hub);
			}
		}
		ring.sort(Comparator.comparingDouble(hub -> Math.atan2(hub.getY() - center.getY(), hub.getX() - center.getX())));
		for (Location hub : ring)
		{
			if ((patrolRoute.size() < 8) && !isCrowded(patrolRoute, hub, 450))
			{
				patrolRoute.add(hub);
			}
		}
		if (patrolRoute.size() < 3)
		{
			patrolRoute.clear();
			patrolRoute.addAll(_streets.getRouteHubs().subList(0, Math.min(4, _streets.getRouteHubs().size())));
		}
		
		// The lamplighter goes from the square from one street point to the next nearest.
		final List<Location> lamps = new ArrayList<>();
		for (Location hub : _streets.getRouteHubs())
		{
			if ((FakePlayerTown.distance2D(hub, center) <= 1600) && !isCrowded(lamps, hub, 300))
			{
				lamps.add(hub);
			}
		}
		Location from = crierSpot;
		while (!lamps.isEmpty() && (lampRoute.size() < 9))
		{
			final Location next = FakePlayerTown.nearest(lamps, from);
			lamps.remove(next);
			lampRoute.add(next);
			from = next;
		}
	}
	
	/**
	 * Festival banners around the square.
	 */
	private void findBanners()
	{
		final GeoEngine geo = GeoEngine.getInstance();
		for (int i = 0; i < 12; i++)
		{
			final double angle = (Math.PI * 2 * i) / 12;
			final int distance = PLAY_RADIUS + 60;
			final int x = playCenter.getX() + (int) (Math.cos(angle) * distance);
			final int y = playCenter.getY() + (int) (Math.sin(angle) * distance);
			final int z = geo.getHeight(x, y, playCenter.getZ());
			if ((Math.abs(z - playCenter.getZ()) < 60) && isOpen(x, y, z) && !isCrowded(bannerSpots, x, y, 250))
			{
				bannerSpots.add(new Location(x, y, z, LocationUtil.calculateHeadingFrom(x, y, playCenter.getX(), playCenter.getY())));
			}
		}
	}
	
	/**
	 * The retail npcs of TownLife.xml that go home at dusk.
	 */
	private void findHomebound()
	{
		if (homes.isEmpty())
		{
			return;
		}
		
		for (int npcId : homeboundIds)
		{
			for (Spawn spawn : SpawnTable.getInstance().getSpawns(npcId))
			{
				if ((spawn.getInstanceId() != 0) || (FakePlayerTown.distance2D(spawn, center) > 4000))
				{
					continue;
				}
				
				final Npc npc = spawn.getLastSpawn();
				final boolean walker = (npc != null) && !WalkingManager.getInstance().getRouteName(npc).isEmpty();
				final Homebound homebound = new Homebound(spawn, walker);
				homebound.home = FakePlayerTown.nearest(homes, spawn);
				_homebound.add(homebound);
			}
		}
	}
	
	/**
	 * The harbor: spots on the shore near the wharf manager, facing the water, for the fishermen; spots on the quay for the dock workers; and a spot inland they all go home to.
	 */
	private void findShore()
	{
		// Every walkable point around the wharf manager on its ground: by the water (the ground drops away into it a few steps away) or on the quay.
		final GeoEngine geo = GeoEngine.getInstance();
		final List<Location> shore = new ArrayList<>();
		final List<Location> quay = new ArrayList<>();
		for (int dx = -SHORE_RANGE; dx <= SHORE_RANGE; dx += 40)
		{
			for (int dy = -SHORE_RANGE; dy <= SHORE_RANGE; dy += 40)
			{
				final int x = center.getX() + dx;
				final int y = center.getY() + dy;
				if (!geo.hasGeo(x, y))
				{
					continue;
				}
				
				final int z = geo.getHeight(x, y, center.getZ());
				if (Math.abs(z - center.getZ()) > 150)
				{
					continue;
				}
				
				final Location point = new Location(x, y, z);
				final int heading = waterHeading(point);
				if (heading >= 0)
				{
					shore.add(new Location(x, y, z, heading));
				}
				else if (isOpen(x, y, z))
				{
					quay.add(point);
				}
			}
		}
		
		// The fishermen stand along the water nearest the wharf manager, where one can walk from it.
		shore.sort(Comparator.comparingDouble(point -> FakePlayerTown.distance2D(point, center)));
		final int wanted = Math.max(1, TownLifeConfig.FISHERMEN) * 2;
		int tries = 0;
		for (Location point : shore)
		{
			if ((shoreSpots.size() >= wanted) || (++tries > 500))
			{
				break;
			}
			if (!isCrowded(shoreSpots, point, 140) && isReachable(point))
			{
				shoreSpots.add(point);
				shoreHeadings.add(point.getHeading());
			}
		}
		
		// The dock workers carry between spots of the quay.
		quay.sort(Comparator.comparingDouble(point -> FakePlayerTown.distance2D(point, center)));
		tries = 0;
		for (Location point : quay)
		{
			if ((dockPoints.size() >= 6) || (++tries > 300))
			{
				break;
			}
			if (!isCrowded(dockPoints, point, 220) && isReachable(point))
			{
				dockPoints.add(point);
			}
		}
		Collections.shuffle(dockPoints);
		
		// Home: the quay spot farthest from the water still close to the wharf manager.
		Location home = center;
		double best = -1;
		for (Location point : dockPoints)
		{
			final double distance = FakePlayerTown.distance2D(point, center);
			if ((distance < 700) && (distance > best))
			{
				home = point;
				best = distance;
			}
		}
		homes.add(home);
		playCenter = center;
		crierSpot = center;
		tavernCenter = center;
	}
	
	/**
	 * @return {@code true} if one can walk to the point from the wharf manager
	 */
	private boolean isReachable(Location point)
	{
		return GeoEngine.getInstance().canMoveToTarget(center.getX(), center.getY(), center.getZ(), point.getX(), point.getY(), point.getZ(), 0) || FakePlayerTown.canReach(center, point);
	}
	
	/**
	 * @return the heading towards water within a few steps of {@code point}, -1 if there is none
	 */
	private static int waterHeading(Location point)
	{
		final GeoEngine geo = GeoEngine.getInstance();
		for (int step = 40; step <= 320; step += 40)
		{
			for (int i = 0; i < 16; i++)
			{
				final double angle = (Math.PI * 2 * i) / 16;
				final int x = point.getX() + (int) (Math.cos(angle) * step);
				final int y = point.getY() + (int) (Math.sin(angle) * step);
				// The water itself is no use (water zones reach up over many piers): the ground drops away into it.
				final boolean drop = !geo.hasGeo(x, y) || ((point.getZ() - geo.getHeight(x, y, point.getZ())) > 150);
				if (drop)
				{
					return LocationUtil.calculateHeadingFrom(point.getX(), point.getY(), x, y);
				}
			}
		}
		return -1;
	}
	
	// ------------------------------------------------------------------
	// Npcs
	// ------------------------------------------------------------------
	
	private int count(int base, boolean scaled)
	{
		final double factor = TownLifeConfig.POPULATION * (scaled ? sizeFactor : 1);
		return (int) Math.round(base * factor);
	}
	
	private int festivalExtra(int count)
	{
		return TownLifeConfig.FESTIVAL ? (int) Math.round(count * (TownLifeConfig.FESTIVAL_POPULATION - 1)) : 0;
	}
	
	private void createResidents()
	{
		if (isHarbor())
		{
			if (!TownLifeConfig.HARBOR_ENABLED)
			{
				return;
			}
			
			final int fishermen = Math.min(shoreSpots.size(), count(TownLifeConfig.FISHERMEN, false));
			for (int i = 0; i < fishermen; i++)
			{
				final TownLifeResident fisherman = add(Kind.FISHERMAN, FISHERMEN[i % FISHERMEN.length], false);
				fisherman.workA = shoreSpots.get(i);
				fisherman.workHeading = shoreHeadings.get(i);
			}
			
			final int workers = dockPoints.size() < 2 ? 0 : count(TownLifeConfig.DOCK_WORKERS, false);
			for (int i = 0; i < workers; i++)
			{
				final TownLifeResident worker = add(Kind.DOCK_WORKER, DOCK_WORKERS[i % DOCK_WORKERS.length], false);
				worker.workA = dockPoints.get(i % dockPoints.size());
				worker.workB = dockPoints.get((i + 1 + Rnd.get(dockPoints.size() - 1)) % dockPoints.size());
				if (worker.workA == worker.workB)
				{
					worker.workB = homes.get(0);
				}
			}
			return;
		}
		
		// Townsfolk.
		final int wanderers = count(TownLifeConfig.WANDERERS, true);
		addSeveral(Kind.WANDERER, TOWNSFOLK, wanderers, festivalExtra(wanderers));
		
		// Workers: porters and an errand runner between the warehouse and the shops, a sweeper.
		final int workers = count(TownLifeConfig.WORKERS, true);
		for (int i = 0; i < workers; i++)
		{
			final TownLifeResident worker;
			switch (i % 4)
			{
				case 1:
				{
					worker = add(Kind.ERRAND_RUNNER, ERRAND_RUNNER, false);
					break;
				}
				case 2:
				{
					worker = add(Kind.SWEEPER, SWEEPER, false);
					break;
				}
				default:
				{
					worker = add(Kind.PORTER, PORTERS[Rnd.get(PORTERS.length)], false);
					break;
				}
			}
			if (worker.kind != Kind.SWEEPER)
			{
				worker.workA = !_pickups.isEmpty() ? _pickups.get(Rnd.get(_pickups.size())) : randomHub();
				worker.workB = !_dropoffs.isEmpty() ? _dropoffs.get(Rnd.get(_dropoffs.size())) : randomHub();
			}
		}
		
		// Patrols, in pairs.
		final int patrols = count(TownLifeConfig.PATROLS, false);
		for (int i = 0; i < patrols; i++)
		{
			final TownLifeResident lead = add(Kind.PATROL, PATROLS[0], false);
			final TownLifeResident follower = add(Kind.PATROL, PATROLS[1], false);
			follower.leader = lead;
			follower.home = lead.home;
		}
		
		// Children.
		if (hasChildren && TownLifeConfig.CHILDREN_ENABLED)
		{
			final int children = count(TownLifeConfig.CHILDREN, false);
			final int extra = festivalExtra(children);
			final List<Integer> models = new ArrayList<>();
			for (int i = 0; i < (children + extra); i++)
			{
				if (models.isEmpty())
				{
					for (int id : CHILDREN)
					{
						models.add(id);
					}
					Collections.shuffle(models);
				}
				_children.add(add(Kind.CHILD, models.remove(0), i >= children));
			}
		}
		
		// Dusk and night.
		if (TownLifeConfig.LAMPLIGHTER && !lampRoute.isEmpty())
		{
			add(Kind.LAMPLIGHTER, LAMPLIGHTER, false);
		}
		addSeveral(Kind.NIGHT_WATCH, NIGHT_WATCH, count(TownLifeConfig.NIGHT_WATCH, true), 0);
		final int crowd = Math.min(tavernSpots.size(), count(TownLifeConfig.TAVERN_CROWD, true));
		final List<Location> spots = new ArrayList<>(tavernSpots);
		Collections.shuffle(spots);
		for (int i = 0; i < crowd; i++)
		{
			final TownLifeResident drinker = add(Kind.TAVERN, TOWNSFOLK[Rnd.get(TOWNSFOLK.length)], false);
			drinker.workA = spots.get(i);
		}
		
		// The crier.
		if (TownLifeConfig.CRIER)
		{
			final TownLifeResident crier = add(Kind.CRIER, CRIER, false);
			crier.workA = crierSpot;
			crier.workHeading = crierHeading;
		}
		
		// Festival decorations.
		if (TownLifeConfig.FESTIVAL)
		{
			for (int i = 0; i < bannerSpots.size(); i++)
			{
				final TownLifeResident banner = add(Kind.DECORATION, BANNERS[i % BANNERS.length], false);
				banner.workA = bannerSpots.get(i);
				banner.workHeading = bannerSpots.get(i).getHeading();
			}
			if (tavernCenter != null)
			{
				final TownLifeResident bonfire = add(Kind.DECORATION, BONFIRE, false);
				bonfire.workA = tavernCenter;
			}
		}
	}
	
	private void addSeveral(Kind kind, int[] templates, int count, int festivalExtra)
	{
		for (int i = 0; i < (count + festivalExtra); i++)
		{
			add(kind, templates[Rnd.get(templates.length)], i >= count);
		}
	}
	
	private TownLifeResident add(Kind kind, int npcId, boolean festivalOnly)
	{
		final TownLifeResident resident = new TownLifeResident(this, kind, npcId, homes.isEmpty() ? center : homes.get(Rnd.get(homes.size())), festivalOnly);
		residents.add(resident);
		return resident;
	}
	
	/**
	 * Where an npc already is the first time it comes out (the server started in the middle of the day).
	 * @param resident the npc
	 * @return the location
	 */
	Location startLocation(TownLifeResident resident)
	{
		switch (resident.kind)
		{
			case CHILD:
			{
				return randomPlayPoint();
			}
			case CRIER:
			case FISHERMAN:
			case TAVERN:
			case PORTER:
			case ERRAND_RUNNER:
			case DOCK_WORKER:
			case DECORATION:
			{
				return resident.workA != null ? resident.workA : randomHub();
			}
			case PATROL:
			{
				return !patrolRoute.isEmpty() ? patrolRoute.get(0) : randomHub();
			}
			default:
			{
				return randomHub();
			}
		}
	}
	
	/**
	 * @param kind what the npc is
	 * @return its title, {@code null} to keep the template's
	 */
	String titleFor(Kind kind)
	{
		switch (kind)
		{
			case WANDERER:
			case TAVERN:
			{
				return "Citizen of " + shortName();
			}
			case CRIER:
			{
				return shortName() + " Herald";
			}
			case FISHERMAN:
			case DOCK_WORKER:
			{
				return name;
			}
			default:
			{
				return null;
			}
		}
	}
	
	private String shortName()
	{
		return name.startsWith("the ") ? name.substring(4) : name;
	}
	
	// ------------------------------------------------------------------
	// Every tick
	// ------------------------------------------------------------------
	
	/**
	 * @param clock the time of day
	 * @param festival {@code true} on festival day
	 * @param now the current time
	 */
	void update(TownLifeManager.Clock clock, boolean festival, long now)
	{
		if ((_lastPhase != clock.phase()) && (_lastPhase != null) && festival && (clock.phase() == NightPhase.DUSK))
		{
			_fireworksUntil = now + FIREWORKS_DURATION;
		}
		_lastPhase = clock.phase();
		
		for (TownLifeResident resident : residents)
		{
			resident.update(clock, festival, now);
		}
		
		updateConversations(now);
		updateHomebound(clock, now);
		updateCrier(clock, festival, now);
		updateFireworks(now);
	}
	
	/**
	 * Sends everyone home at once (server shutdown or the feature reloaded): the town life npcs leave the world, the retail npcs come back.
	 */
	void clear()
	{
		for (TownLifeResident resident : residents)
		{
			resident.despawn();
		}
		for (Homebound homebound : _homebound)
		{
			final Npc npc = homebound.spawn.getLastSpawn();
			if ((npc != null) && (homebound.state != 0))
			{
				showHomebound(homebound, npc, true);
			}
		}
	}
	
	// The crier.
	
	private void updateCrier(TownLifeManager.Clock clock, boolean festival, long now)
	{
		if (!TownLifeConfig.CRIER || (now < _nextCry))
		{
			return;
		}
		_nextCry = now + (TownLifeConfig.CRIER_INTERVAL * 60_000L) + Rnd.get(-20_000, 20_000);
		
		for (TownLifeResident resident : residents)
		{
			if ((resident.kind == Kind.CRIER) && resident.isOut())
			{
				final Npc npc = resident.getNpc();
				if (TownLifeManager.isWatched(npc))
				{
					npc.onRandomAnimation(Rnd.get(1, 3));
					npc.broadcastSay(ChatType.NPC_SHOUT, TownLifeManager.getInstance().nextCry(this, clock, festival), TownLifeConfig.CRIER_RANGE);
				}
				return;
			}
		}
	}
	
	// Festival fireworks.
	
	private void updateFireworks(long now)
	{
		if ((now > _fireworksUntil) || (now < _nextFirework))
		{
			return;
		}
		_nextFirework = now + Rnd.get(6000, 15000);
		
		final List<Npc> near = new ArrayList<>();
		for (TownLifeResident resident : residents)
		{
			final Npc npc = resident.getNpc();
			if ((npc != null) && (FakePlayerTown.distance2D(npc, playCenter) < 900))
			{
				near.add(npc);
			}
		}
		if (near.isEmpty() || !TownLifeManager.isWatched(near.get(0)))
		{
			return;
		}
		
		final Npc npc = near.get(Rnd.get(near.size()));
		npc.broadcastPacket(new MagicSkillUse(npc, npc, FIREWORK_SKILLS[Rnd.get(FIREWORK_SKILLS.length)], 1, 1, 0));
	}
	
	// Pacing.
	
	boolean mayGreet(long now)
	{
		if (now < _nextGreet)
		{
			return false;
		}
		_nextGreet = now + 4000;
		return true;
	}
	
	boolean mayTalkInTavern(long now)
	{
		if (now < _nextTavernLine)
		{
			return false;
		}
		_nextTavernLine = now + 8000;
		return true;
	}
	
	// ------------------------------------------------------------------
	// Conversations
	// ------------------------------------------------------------------
	
	/**
	 * A townsperson looks for someone close by to have a talk with.
	 * @param first who starts it
	 * @param now the current time
	 * @return {@code true} if it found someone
	 */
	boolean startConversation(TownLifeResident first, long now)
	{
		final Npc firstNpc = first.getNpc();
		TownLifeResident second = null;
		double best = 900;
		for (TownLifeResident other : residents)
		{
			if ((other == first) || (other.kind != Kind.WANDERER) || !other.isIdle(now))
			{
				continue;
			}
			
			final Npc otherNpc = other.getNpc();
			final double distance = firstNpc.calculateDistance2D(otherNpc);
			if ((distance < best) && (Math.abs(otherNpc.getZ() - firstNpc.getZ()) < 100))
			{
				second = other;
				best = distance;
			}
		}
		
		if (second == null)
		{
			return false;
		}
		
		// They meet where the second one stands, a step in front of it.
		final Npc secondNpc = second.getNpc();
		final double angle = Math.atan2(firstNpc.getY() - secondNpc.getY(), firstNpc.getX() - secondNpc.getX());
		final int x = secondNpc.getX() + (int) (Math.cos(angle) * 45);
		final int y = secondNpc.getY() + (int) (Math.sin(angle) * 45);
		final Location meetingPoint = TownLifeManager.groundAt(x, y, secondNpc.getZ(), secondNpc);
		final Conversation conversation = new Conversation(first, second, TownLifeLines.CONVERSATIONS[Rnd.get(TownLifeLines.CONVERSATIONS.length)], meetingPoint, now);
		first.conversation = conversation;
		second.conversation = conversation;
		secondNpc.stopMove(null);
		_conversations.add(conversation);
		return true;
	}
	
	/**
	 * The one who started a conversation got there: they face each other and start talking.
	 */
	void beginTalk(Conversation conversation)
	{
		final Npc first = conversation.first.getNpc();
		final Npc second = conversation.second.getNpc();
		if ((first == null) || (second == null))
		{
			endConversation(conversation);
			return;
		}
		
		TownLifeResident.face(first, second);
		TownLifeResident.face(second, first);
		conversation.talking = true;
		conversation.nextLine = System.currentTimeMillis() + 600;
	}
	
	private void updateConversations(long now)
	{
		for (Conversation conversation : new ArrayList<>(_conversations))
		{
			final Npc first = conversation.first.getNpc();
			final Npc second = conversation.second.getNpc();
			if ((first == null) || (second == null) || ((now - conversation.started) > 90_000) || (!conversation.talking && ((now - conversation.started) > 40_000)))
			{
				endConversation(conversation);
				continue;
			}
			
			if (!conversation.talking || (now < conversation.nextLine))
			{
				continue;
			}
			
			if (conversation.index >= conversation.lines.length)
			{
				endConversation(conversation);
				continue;
			}
			
			final TownLifeResident speaker = (conversation.index % 2) == 0 ? conversation.first : conversation.second;
			final Npc speakerNpc = speaker.getNpc();
			if (TownLifeManager.isWatched(speakerNpc))
			{
				speaker.say(speakerNpc, conversation.lines[conversation.index], null);
				if (Rnd.get(2) == 0)
				{
					speakerNpc.onRandomAnimation(Rnd.get(1, 3));
				}
			}
			conversation.index++;
			conversation.nextLine = now + Rnd.get(3500, 5000);
		}
	}
	
	/**
	 * @param resident an npc leaving whatever conversation it is in (it went home)
	 */
	void leaveConversation(TownLifeResident resident)
	{
		if (resident.conversation != null)
		{
			endConversation(resident.conversation);
		}
	}
	
	private void endConversation(Conversation conversation)
	{
		_conversations.remove(conversation);
		final long now = System.currentTimeMillis();
		for (TownLifeResident resident : new TownLifeResident[]
		{
			conversation.first,
			conversation.second
		})
		{
			if (resident.conversation == conversation)
			{
				resident.conversation = null;
				resident.waitUntil = now + Rnd.get(1000, 4000);
			}
		}
	}
	
	// ------------------------------------------------------------------
	// The children's game of tag
	// ------------------------------------------------------------------
	
	/**
	 * One child's turn in the game: the one who is "it" runs after the nearest other child and tags it, the others run away when it comes close. Now and then one of them runs up to a player for a sweet.
	 */
	void thinkChild(TownLifeResident child, Npc npc, long now)
	{
		// Asking a player for a sweet.
		if (_asking == child)
		{
			if (thinkAsking(child, npc, now))
			{
				return;
			}
		}
		else if ((_asking == null) && (now >= _nextAsk))
		{
			_nextAsk = now + Rnd.get(120_000, 300_000);
			if (TownLifeConfig.KIDS_TREATS && (child != _it) && startAsking(child, npc, now))
			{
				return;
			}
		}
		
		if (now < child.frozenUntil)
		{
			return;
		}
		
		// Too far from the square (just came out, or chased someone off): back to it.
		if (FakePlayerTown.distance2D(npc, playCenter) > (PLAY_RADIUS + 250))
		{
			if (!npc.isMoving())
			{
				final Location leg = nextLeg(npc, randomPlayPoint(), null);
				if (leg != null)
				{
					npc.getAI().setIntention(Intention.MOVE_TO, leg);
				}
			}
			return;
		}
		
		final List<TownLifeResident> players = new ArrayList<>();
		for (TownLifeResident other : _children)
		{
			if (other.isOut() && (other != _asking) && (FakePlayerTown.distance2D(other.getNpc(), playCenter) <= (PLAY_RADIUS + 250)))
			{
				players.add(other);
			}
		}
		if ((_it == null) || !players.contains(_it))
		{
			_it = players.contains(child) ? child : null;
			_chased = null;
			if (_it == null)
			{
				return;
			}
		}
		
		if (child == _it)
		{
			chase(child, npc, players, now);
		}
		else
		{
			final Npc itNpc = _it.getNpc();
			final double distance = itNpc != null ? npc.calculateDistance2D(itNpc) : Double.MAX_VALUE;
			if (((distance < 260) && (!npc.isMoving() || (Rnd.get(3) == 0)) && (Rnd.get(100) < 60)) || (!npc.isMoving() && (Rnd.get(6) == 0)))
			{
				runFrom(npc, itNpc);
			}
			if (TownLifeConfig.CHATTER && (now >= _nextPlayLine) && (Rnd.get(20) == 0))
			{
				_nextPlayLine = now + 7000;
				child.say(npc, TownLifeLines.random(TownLifeLines.CHILD_PLAY), null);
			}
		}
	}
	
	private void chase(TownLifeResident it, Npc npc, List<TownLifeResident> players, long now)
	{
		// The nearest one, or another one after chasing the same one too long.
		if ((_chased == null) || !players.contains(_chased) || ((now - _chaseStarted) > 15_000))
		{
			TownLifeResident nearest = null;
			double best = Double.MAX_VALUE;
			for (TownLifeResident other : players)
			{
				if ((other == it) || (other == _chased))
				{
					continue;
				}
				final double distance = npc.calculateDistance2D(other.getNpc());
				if (distance < best)
				{
					nearest = other;
					best = distance;
				}
			}
			if ((nearest == null) && players.contains(_chased))
			{
				nearest = _chased;
			}
			_chased = nearest;
			_chaseStarted = now;
		}
		
		if (_chased == null)
		{
			if (!npc.isMoving())
			{
				npc.getAI().setIntention(Intention.MOVE_TO, randomPlayPoint());
			}
			return;
		}
		
		final Npc target = _chased.getNpc();
		if (target == null)
		{
			_chased = null;
			return;
		}
		
		final double distance = npc.calculateDistance2D(target);
		if ((distance < 45) || ((distance < 110) && ((now - _chaseStarted) > 25_000)))
		{
			// Tag!
			npc.stopMove(null);
			TownLifeResident.face(npc, target);
			if (TownLifeConfig.CHATTER)
			{
				it.say(npc, TownLifeLines.random(TownLifeLines.CHILD_TAG), null);
			}
			target.stopMove(null);
			_chased.frozenUntil = now + 2500;
			it.frozenUntil = now + 600;
			_it = _chased;
			_chased = it;
			_chaseStarted = now;
			runFrom(npc, target);
			return;
		}
		
		npc.getAI().setIntention(Intention.MOVE_TO, new Location(target.getX(), target.getY(), target.getZ()));
	}
	
	/**
	 * Runs off, away from {@code from}, staying in the square.
	 */
	private void runFrom(Npc npc, WorldObject from)
	{
		final GeoEngine geo = GeoEngine.getInstance();
		final double away = from != null ? Math.atan2(npc.getY() - from.getY(), npc.getX() - from.getX()) : Rnd.nextDouble() * Math.PI * 2;
		for (int attempt = 0; attempt < 10; attempt++)
		{
			final double angle = attempt < 6 ? away + Math.toRadians(Rnd.get(-70, 70)) : Rnd.nextDouble() * Math.PI * 2;
			final int distance = Rnd.get(120, 280);
			int x = npc.getX() + (int) (Math.cos(angle) * distance);
			int y = npc.getY() + (int) (Math.sin(angle) * distance);
			final double fromCenter = Math.hypot(x - playCenter.getX(), y - playCenter.getY());
			if (fromCenter > PLAY_RADIUS)
			{
				final double scale = (PLAY_RADIUS * (0.6 + (Rnd.nextDouble() * 0.35))) / fromCenter;
				x = playCenter.getX() + (int) ((x - playCenter.getX()) * scale);
				y = playCenter.getY() + (int) ((y - playCenter.getY()) * scale);
			}
			
			final int z = geo.getHeight(x, y, npc.getZ());
			if ((Math.abs(z - playCenter.getZ()) < 60) && geo.canMoveToTarget(npc.getX(), npc.getY(), npc.getZ(), x, y, z, 0))
			{
				npc.getAI().setIntention(Intention.MOVE_TO, new Location(x, y, z));
				return;
			}
		}
	}
	
	/**
	 * @return a random open point of the square (around its fountain, if it has one)
	 */
	Location randomPlayPoint()
	{
		final GeoEngine geo = GeoEngine.getInstance();
		for (int attempt = 0; attempt < 20; attempt++)
		{
			final double angle = Rnd.nextDouble() * Math.PI * 2;
			final int distance = Rnd.get(80, PLAY_RADIUS);
			final int x = playCenter.getX() + (int) (Math.cos(angle) * distance);
			final int y = playCenter.getY() + (int) (Math.sin(angle) * distance);
			final int z = geo.getHeight(x, y, playCenter.getZ());
			if ((Math.abs(z - playCenter.getZ()) < 60) && isOpen(x, y, z))
			{
				return new Location(x, y, z);
			}
		}
		return playCenter;
	}
	
	private boolean startAsking(TownLifeResident child, Npc npc, long now)
	{
		for (Player player : World.getInstance().getVisibleObjectsInRange(npc, Player.class, 700))
		{
			if (player.isInvisible() || player.isAlikeDead() || player.isInStoreMode() || player.isInCombat() || player.isFakePlayer() || (FakePlayerTown.distance2D(player, playCenter) > 1200))
			{
				continue;
			}
			
			_asking = child;
			_askUntil = now + 40_000;
			_askSaid = false;
			child.askedPlayerId = player.getObjectId();
			return true;
		}
		return false;
	}
	
	/**
	 * @return {@code true} while the child is busy asking
	 */
	private boolean thinkAsking(TownLifeResident child, Npc npc, long now)
	{
		final Player player = World.getInstance().getPlayer(child.askedPlayerId);
		if ((now > _askUntil) || (player == null) || !player.isOnline() || (npc.calculateDistance2D(player) > 1500))
		{
			_asking = null;
			// The player can still talk to the child for a sweet a little while longer.
			return false;
		}
		
		if (npc.calculateDistance2D(player) > 70)
		{
			npc.getAI().setIntention(Intention.MOVE_TO, new Location(player.getX(), player.getY(), player.getZ()));
			return true;
		}
		
		npc.stopMove(null);
		TownLifeResident.face(npc, player);
		if (!_askSaid)
		{
			_askSaid = true;
			npc.onRandomAnimation(Rnd.get(1, 3));
			child.say(npc, TownLifeLines.random(TownLifeLines.CHILD_ASK), player.getName());
		}
		return true;
	}
	
	/**
	 * A player gave a child a sweet: it is happy and runs back to play.
	 */
	void onTreat(TownLifeResident child)
	{
		if (_asking == child)
		{
			_asking = null;
		}
		child.askedPlayerId = 0;
		child.frozenUntil = System.currentTimeMillis() + 3000;
	}
	
	// ------------------------------------------------------------------
	// Retail npcs going home
	// ------------------------------------------------------------------
	
	private void updateHomebound(TownLifeManager.Clock clock, long now)
	{
		if (!TownLifeConfig.RETAIL_GO_HOME)
		{
			return;
		}
		
		for (Homebound homebound : _homebound)
		{
			final Npc npc = homebound.spawn.getLastSpawn();
			if ((npc == null) || !npc.isSpawned() || npc.isDead() || (homebound.home == null))
			{
				continue;
			}
			
			final boolean out = isDayFor(clock, homebound.bedtime, homebound.wakeTime);
			switch (homebound.state)
			{
				case 0:
				{
					if (!out)
					{
						if (homebound.walker)
						{
							WalkingManager.getInstance().stopMoving(npc, true, false);
						}
						
						if (!TownLifeManager.isWatched(npc))
						{
							hideHomebound(homebound, npc);
						}
						else
						{
							npc.setWalking();
							homebound.state = 1;
							homebound.deadline = now + 180_000;
							homebound.stuck = 0;
						}
					}
					break;
				}
				case 1:
				{
					if (out)
					{
						homebound.state = 3;
					}
					else if (!TownLifeManager.isWatched(npc) || (now > homebound.deadline) || walkHomebound(homebound, npc, homebound.home))
					{
						hideHomebound(homebound, npc);
					}
					break;
				}
				case 2:
				{
					if (out)
					{
						showHomebound(homebound, npc, false);
					}
					break;
				}
				case 3:
				{
					final Location spot = new Location(homebound.spawn.getX(), homebound.spawn.getY(), homebound.spawn.getZ());
					if (!TownLifeManager.isWatched(npc))
					{
						npc.teleToLocation(spot);
						homebound.state = 0;
						npc.setHeading(homebound.spawn.getHeading());
					}
					else if (walkHomebound(homebound, npc, spot))
					{
						homebound.state = 0;
						TownLifeResident.face(npc, homebound.spawn.getHeading());
					}
					break;
				}
			}
		}
	}
	
	private static boolean isDayFor(TownLifeManager.Clock clock, double bedtime, double wakeTime)
	{
		switch (clock.phase())
		{
			case DAY:
			{
				return true;
			}
			case DAWN:
			{
				return clock.dawnProgress() >= wakeTime;
			}
			case DUSK:
			{
				return clock.duskProgress() < bedtime;
			}
			default:
			{
				return false;
			}
		}
	}
	
	/**
	 * @return {@code true} once it is there (or can't get closer)
	 */
	private boolean walkHomebound(Homebound homebound, Npc npc, Location destination)
	{
		if (npc.calculateDistance2D(destination) <= 50)
		{
			return true;
		}
		if (npc.isMoving())
		{
			return false;
		}
		
		if ((homebound.lastPosition != null) && (npc.calculateDistance2D(homebound.lastPosition) < 20) && (++homebound.stuck > 5))
		{
			return true;
		}
		homebound.lastPosition = new Location(npc.getX(), npc.getY(), npc.getZ());
		final Location leg = nextLeg(npc, destination, null);
		if (leg == null)
		{
			return ++homebound.stuck > 5;
		}
		npc.getAI().setIntention(Intention.MOVE_TO, leg);
		return false;
	}
	
	private static void hideHomebound(Homebound homebound, Npc npc)
	{
		npc.stopMove(null);
		npc.setInvisible(true);
		homebound.state = 2;
	}
	
	/**
	 * Morning: it comes out of its house and walks back to its spot (or takes up its route again).
	 * @param atSpot {@code true} to put it straight back at its spot
	 */
	private static void showHomebound(Homebound homebound, Npc npc, boolean atSpot)
	{
		if (atSpot)
		{
			npc.teleToLocation(homebound.spawn.getX(), homebound.spawn.getY(), homebound.spawn.getZ(), homebound.spawn.getHeading());
		}
		npc.setInvisible(false);
		homebound.stuck = 0;
		if (homebound.walker)
		{
			homebound.state = 0;
			WalkingManager.getInstance().resumeMoving(npc);
		}
		else
		{
			homebound.state = atSpot ? 0 : 3;
		}
	}
	
	// ------------------------------------------------------------------
	// Helpers
	// ------------------------------------------------------------------
	
	/**
	 * @return the next leg of a walk towards {@code destination} (street point to street point in a town, straight in a harbor)
	 */
	Location nextLeg(ILocational from, Location destination, Location avoid)
	{
		if (_streets != null)
		{
			return _streets.nextLeg(from, destination, avoid);
		}
		return destination != avoid ? destination : null;
	}
	
	/**
	 * @return a random street point of the town (a quay spot of a harbor)
	 */
	Location randomHub()
	{
		if (_streets != null)
		{
			return _streets.getRandomHub();
		}
		return !dockPoints.isEmpty() ? dockPoints.get(Rnd.get(dockPoints.size())) : center;
	}
	
	/**
	 * @return a random street point within {@code range} of the npc, or any one if none is that close
	 */
	Location nearbyHub(Npc npc, int range)
	{
		if (_streets == null)
		{
			return randomHub();
		}
		
		final List<Location> near = new ArrayList<>();
		for (Location hub : _streets.getRouteHubs())
		{
			final double distance = FakePlayerTown.distance2D(npc, hub);
			if ((distance > 100) && (distance <= range))
			{
				near.add(hub);
			}
		}
		return near.isEmpty() ? randomHub() : near.get(Rnd.get(near.size()));
	}
	
	/**
	 * @return a random spot in front of a shop of the town, {@code null} if none
	 */
	Spot randomShopSpot()
	{
		if (shopSpots.isEmpty())
		{
			return null;
		}
		
		final Spot spot = shopSpots.get(Rnd.get(shopSpots.size()));
		return (spot.npc() != null) && spot.npc().isSpawned() && !spot.npc().isInvisible() ? spot : null;
	}
	
	private static Spawn findSpawn(int npcId, Location near)
	{
		for (Spawn spawn : SpawnTable.getInstance().getSpawns(npcId))
		{
			if ((spawn.getInstanceId() == 0) && ((near == null) || (FakePlayerTown.distance2D(spawn, near) < 4000)))
			{
				return spawn;
			}
		}
		return null;
	}
	
	private static Location ground(Location location)
	{
		final GeoEngine geo = GeoEngine.getInstance();
		return new Location(location.getX(), location.getY(), geo.getHeight(location.getX(), location.getY(), location.getZ()));
	}
	
	private static boolean isOpen(int x, int y, int z)
	{
		final GeoEngine geo = GeoEngine.getInstance();
		return geo.hasGeo(x, y) && geo.checkNearestNswe(GeoEngine.getGeoX(x), GeoEngine.getGeoY(y), z, Cell.NSWE_ALL);
	}
	
	/**
	 * @return how many of {@code rays} straight walks of {@code range} from the point aren't stopped by a wall
	 */
	private static int countOpen(Location point, int range, int rays)
	{
		final GeoEngine geo = GeoEngine.getInstance();
		int open = 0;
		for (int i = 0; i < rays; i++)
		{
			final double angle = (Math.PI * 2 * i) / rays;
			final int x = point.getX() + (int) (Math.cos(angle) * range);
			final int y = point.getY() + (int) (Math.sin(angle) * range);
			final int z = geo.getHeight(x, y, point.getZ());
			if ((Math.abs(z - point.getZ()) < 80) && geo.canMoveToTarget(point.getX(), point.getY(), point.getZ(), x, y, z, 0))
			{
				open++;
			}
		}
		return open;
	}
	
	private static boolean isEnclosed(Location point)
	{
		return (16 - countOpen(point, ENCLOSED_RANGE, 16)) >= ENCLOSED_WALLS;
	}
	
	private static boolean isCrowded(List<Location> locations, ILocational point, int distance)
	{
		return isCrowded(locations, point.getX(), point.getY(), distance);
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
	 * @return what the town does right now, for //townlife
	 */
	List<String> describe()
	{
		final List<String> lines = new ArrayList<>();
		int out = 0;
		for (TownLifeResident resident : residents)
		{
			if (resident.isOut())
			{
				out++;
			}
		}
		int hidden = 0;
		for (Homebound homebound : _homebound)
		{
			if (homebound.state == 2)
			{
				hidden++;
			}
		}
		lines.add(name + (_ready ? "" : " (preparing)") + ": " + out + "/" + residents.size() + " out, " + homes.size() + " houses, " + _homebound.size() + " retail homebound (" + hidden + " at home)" + (isHarbor() ? ", " + shoreSpots.size() + " shore spots" : ""));
		return lines;
	}
}
