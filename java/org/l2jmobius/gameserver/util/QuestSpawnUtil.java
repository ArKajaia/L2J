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
package org.l2jmobius.gameserver.util;

import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;

import org.l2jmobius.commons.threads.ThreadPool;
import org.l2jmobius.commons.util.Rnd;
import org.l2jmobius.gameserver.ai.Intention;
import org.l2jmobius.gameserver.config.GeoEngineConfig;
import org.l2jmobius.gameserver.geoengine.GeoEngine;
import org.l2jmobius.gameserver.geoengine.pathfinding.GeoLocation;
import org.l2jmobius.gameserver.geoengine.pathfinding.PathFinding;
import org.l2jmobius.gameserver.managers.ZoneManager;
import org.l2jmobius.gameserver.model.Location;
import org.l2jmobius.gameserver.model.actor.Attackable;
import org.l2jmobius.gameserver.model.actor.Creature;
import org.l2jmobius.gameserver.model.actor.Npc;
import org.l2jmobius.gameserver.model.actor.Player;
import org.l2jmobius.gameserver.model.interfaces.ILocational;
import org.l2jmobius.gameserver.model.script.Quest;
import org.l2jmobius.gameserver.model.zone.type.PeaceZone;
import org.l2jmobius.gameserver.model.zone.type.TownZone;
import org.l2jmobius.gameserver.network.enums.ChatType;
import org.l2jmobius.gameserver.network.serverpackets.CreatureSay;
import org.l2jmobius.gameserver.network.serverpackets.ExShowScreenMessage;
import org.l2jmobius.gameserver.network.serverpackets.RadarControl;
import org.l2jmobius.gameserver.network.serverpackets.ShowMiniMap;

/**
 * Spawning, placement and message helpers shared by the scripted quest engines (Hidden Quests, Dynamic Quests).
 */
public final class QuestSpawnUtil
{
	private QuestSpawnUtil()
	{
	}

	/**
	 * Finds a reachable point at a distance from a location: walkable in a straight line from it, at about the same height and outside towns.
	 * @param from the origin
	 * @param minDistance the minimum distance
	 * @param maxDistance the maximum distance
	 * @param allowTown {@code true} to accept points inside towns
	 * @param instanceId the instance
	 * @return the point; if none is found, the farthest walkable point in a random direction
	 */
	public static Location findPoint(ILocational from, int minDistance, int maxDistance, boolean allowTown, int instanceId)
	{
		return findPoint(from, minDistance, maxDistance, allowTown, instanceId, false);
	}

	/**
	 * Finds a reachable point at a distance from a location: walkable in a straight line from it, at about the same height and outside towns.
	 * @param from the origin
	 * @param minDistance the minimum distance
	 * @param maxDistance the maximum distance
	 * @param allowTown {@code true} to accept points inside towns
	 * @param instanceId the instance
	 * @param bothWays {@code true} if the way back has to be walkable too (geodata lets anyone walk off a cliff, but not back up it): for a monster that has to come to
	 *            the origin, or a place a player has to come back from
	 * @return the point; if none is found, the farthest walkable point in a random direction
	 */
	public static Location findPoint(ILocational from, int minDistance, int maxDistance, boolean allowTown, int instanceId, boolean bothWays)
	{
		final GeoEngine geo = GeoEngine.getInstance();
		final int attempts = 40;
		for (int attempt = 0; attempt < attempts; attempt++)
		{
			// Later attempts try shorter distances.
			final int max = Math.max(minDistance, maxDistance - (((maxDistance - minDistance) * attempt) / attempts));
			final int distance = Rnd.get(minDistance, max);
			final double angle = Rnd.nextDouble() * 2 * Math.PI;
			final int x = from.getX() + (int) (Math.cos(angle) * distance);
			final int y = from.getY() + (int) (Math.sin(angle) * distance);
			final int z = geo.getHeight(x, y, from.getZ());
			if (Math.abs(z - from.getZ()) > 500)
			{
				continue;
			}
			if (!geo.canMoveToTarget(from.getX(), from.getY(), from.getZ(), x, y, z, instanceId) || (bothWays && !geo.canMoveToTarget(x, y, z, from.getX(), from.getY(), from.getZ(), instanceId)))
			{
				continue;
			}
			if (!allowTown && isTown(x, y, z))
			{
				continue;
			}
			return new Location(x, y, z);
		}

		final double angle = Rnd.nextDouble() * 2 * Math.PI;
		final int x = from.getX() + (int) (Math.cos(angle) * minDistance);
		final int y = from.getY() + (int) (Math.sin(angle) * minDistance);
		final Location valid = geo.getValidLocation(from.getX(), from.getY(), from.getZ(), x, y, geo.getHeight(x, y, from.getZ()), instanceId);
		return valid != null ? valid : new Location(from.getX(), from.getY(), from.getZ());
	}

	/**
	 * Finds a point near a wanted place that can be walked to from an origin (and back), for routes whose next stop is planned from afar (where a straight line from the start may cross a mountain or a
	 * cliff): the place itself if it can be reached, else the nearest reachable spot around it out to {@code radius}, else the farthest walkable point on the straight way to it.
	 * @param from the origin, a spot on the ground (the previous stop, an npc)
	 * @param toward the wanted place; only its x and y are used
	 * @param radius how far around the wanted place to look
	 * @param minAdvance how far from the origin the point has to be
	 * @param allowTown {@code true} to accept points inside towns
	 * @param instanceId the instance
	 * @return the point, {@code null} if there is none at least {@code minAdvance} away
	 */
	public static Location findPointToward(ILocational from, ILocational toward, int radius, int minAdvance, boolean allowTown, int instanceId)
	{
		final GeoEngine geo = GeoEngine.getInstance();
		final int originZ = geo.getHeight(from.getX(), from.getY(), from.getZ());
		final double startAngle = Rnd.nextDouble() * 2 * Math.PI;
		int pathSearches = 0;
		// The wanted place first, then rings around it, nearest first.
		for (int ring = 0; ring <= REACH_RINGS; ring++)
		{
			final int distance = (radius * ring) / REACH_RINGS;
			final int points = ring == 0 ? 1 : REACH_RING_POINTS;
			for (int i = 0; i < points; i++)
			{
				final double angle = startAngle + ((2 * Math.PI * i) / points);
				final int x = toward.getX() + (int) (Math.cos(angle) * distance);
				final int y = toward.getY() + (int) (Math.sin(angle) * distance);
				if (Math.hypot(x - from.getX(), y - from.getY()) < minAdvance)
				{
					continue;
				}
				final int z = geo.getHeight(x, y, originZ);
				if (!allowTown && isTown(x, y, z))
				{
					continue;
				}
				if (canWalk(geo, from.getX(), from.getY(), originZ, x, y, z, instanceId))
				{
					return new Location(x, y, z);
				}
				// Not in a straight line: a short detour around a rock or a ridge will do, a long way around a mountain will not.
				if (pathSearches < REACH_PATH_SEARCHES)
				{
					pathSearches++;
					if (hasShortPath(from.getX(), from.getY(), originZ, x, y, z, instanceId, REACH_MAX_DETOUR))
					{
						return new Location(x, y, z);
					}
				}
			}
		}

		// The farthest walkable point on the way.
		final Location valid = geo.getValidLocation(from.getX(), from.getY(), originZ, toward.getX(), toward.getY(), geo.getHeight(toward.getX(), toward.getY(), originZ), instanceId);
		if ((valid != null) && (Math.hypot(valid.getX() - from.getX(), valid.getY() - from.getY()) >= minAdvance) && (allowTown || !isTown(valid.getX(), valid.getY(), valid.getZ())) && geo.canMoveToTarget(valid.getX(), valid.getY(), valid.getZ(), from.getX(), from.getY(), originZ, instanceId))
		{
			return valid;
		}
		return null;
	}

	/**
	 * @param from the origin, a spot on the ground
	 * @param to the place
	 * @param instanceId the instance
	 * @return {@code true} if the place can be walked to from the origin (and back): in a straight line, or by a path not much longer than one
	 */
	public static boolean canReach(ILocational from, ILocational to, int instanceId)
	{
		final GeoEngine geo = GeoEngine.getInstance();
		final int fromZ = geo.getHeight(from.getX(), from.getY(), from.getZ());
		final int toZ = geo.getHeight(to.getX(), to.getY(), to.getZ());
		return canWalk(geo, from.getX(), from.getY(), fromZ, to.getX(), to.getY(), toZ, instanceId) || hasShortPath(from.getX(), from.getY(), fromZ, to.getX(), to.getY(), toZ, instanceId, REACH_MAX_DETOUR);
	}

	/**
	 * @return {@code true} if the points can be walked between in a straight line, both ways: geodata lets anyone walk off a cliff, but not back up it
	 */
	private static boolean canWalk(GeoEngine geo, int x, int y, int z, int tx, int ty, int tz, int instanceId)
	{
		return geo.canMoveToTarget(x, y, z, tx, ty, tz, instanceId) && geo.canMoveToTarget(tx, ty, tz, x, y, z, instanceId);
	}

	/**
	 * @return {@code true} if pathfinding finds a way between the points no longer than {@code maxDetour} times the straight distance
	 */
	private static boolean hasShortPath(int x, int y, int z, int tx, int ty, int tz, int instanceId, double maxDetour)
	{
		if (GeoEngineConfig.PATHFINDING <= 0)
		{
			return false;
		}
		final List<GeoLocation> path = PathFinding.getInstance().findPath(x, y, z, tx, ty, tz, instanceId, true);
		if ((path == null) || path.isEmpty())
		{
			return false;
		}
		double length = 0;
		int previousX = x;
		int previousY = y;
		for (GeoLocation node : path)
		{
			length += Math.hypot(node.getX() - previousX, node.getY() - previousY);
			previousX = node.getX();
			previousY = node.getY();
		}
		length += Math.hypot(tx - previousX, ty - previousY);
		return length <= ((Math.hypot(tx - x, ty - y) * maxDetour) + 200);
	}

	/**
	 * Finds a road over land from an origin to a place: a coarse search over a grid of points {@code spacing} apart, each one walkable in a straight line from the one before it (and back), that goes
	 * around mountains, cliffs and water, and finds the way up to a town on a hill.
	 * @param from the origin, a spot on the ground
	 * @param to the place
	 * @param spacing the distance between the grid's points
	 * @param maxNodes how many points the search may look at before giving up
	 * @param maxLength the longest road wanted
	 * @param instanceId the instance
	 * @return the road's points after the origin, ending at the place (or right next to it, if the place itself can't be walked to); {@code null} if none was found
	 */
	public static List<Location> findRoute(ILocational from, ILocational to, int spacing, int maxNodes, double maxLength, int instanceId)
	{
		final GeoEngine geo = GeoEngine.getInstance();
		final int startX = from.getX();
		final int startY = from.getY();
		final int targetZ = geo.getHeight(to.getX(), to.getY(), to.getZ());
		final Map<Long, RouteNode> best = new HashMap<>();
		final Set<Long> closed = new HashSet<>();
		final PriorityQueue<RouteNode> open = new PriorityQueue<>(Comparator.comparingDouble(node -> node._estimate));
		final RouteNode start = new RouteNode(0, 0, startX, startY, geo.getHeight(startX, startY, from.getZ()), 0, null);
		start._estimate = Math.hypot(to.getX() - startX, to.getY() - startY);
		best.put(start.key(), start);
		open.add(start);
		int expanded = 0;
		int approaches = 0;
		while (!open.isEmpty() && (expanded < maxNodes))
		{
			final RouteNode node = open.poll();
			final long key = node.key();
			if ((best.get(key) != node) || !closed.add(key))
			{
				continue;
			}
			expanded++;

			// Close enough to walk the rest: in a straight line, or (near a town, whose gates the grid is too coarse to find) by the way pathfinding finds.
			final double left = Math.hypot(to.getX() - node._x, to.getY() - node._y);
			boolean arrived = (left <= (spacing * 1.5)) && canWalk(geo, node._x, node._y, node._z, to.getX(), to.getY(), targetZ, instanceId);
			if (!arrived && (left <= ROUTE_APPROACH_RANGE) && (approaches < ROUTE_APPROACH_SEARCHES))
			{
				approaches++;
				arrived = hasShortPath(node._x, node._y, node._z, to.getX(), to.getY(), targetZ, instanceId, ROUTE_APPROACH_DETOUR);
			}
			// Right next to the place, but on a spot it can't be walked to from (a building, a platform): the road ends here.
			final boolean endsHere = !arrived && (left <= ROUTE_END_RANGE);
			if (arrived || endsHere)
			{
				final LinkedList<Location> road = new LinkedList<>();
				if (arrived)
				{
					road.add(new Location(to.getX(), to.getY(), targetZ));
				}
				for (RouteNode step = node; step._parent != null; step = step._parent)
				{
					road.addFirst(new Location(step._x, step._y, step._z));
				}
				if (road.isEmpty())
				{
					road.add(new Location(node._x, node._y, node._z));
				}
				return road;
			}

			for (int[] direction : ROUTE_DIRECTIONS)
			{
				final int gridX = node._gridX + direction[0];
				final int gridY = node._gridY + direction[1];
				final long nextKey = RouteNode.key(gridX, gridY);
				if (closed.contains(nextKey))
				{
					continue;
				}
				final int x = startX + (gridX * spacing);
				final int y = startY + (gridY * spacing);
				if (!geo.hasGeo(x, y))
				{
					continue;
				}
				final int z = geo.getHeight(x, y, node._z);
				// Geodata lets anyone walk off a cliff: the road doesn't.
				if (!canWalk(geo, node._x, node._y, node._z, x, y, z, instanceId))
				{
					continue;
				}
				final double cost = node._cost + Math.hypot(x - node._x, y - node._y);
				final double remaining = Math.hypot(to.getX() - x, to.getY() - y);
				if ((cost + remaining) > maxLength)
				{
					continue;
				}
				final RouteNode known = best.get(nextKey);
				if ((known != null) && (known._cost <= cost))
				{
					continue;
				}
				final RouteNode next = new RouteNode(gridX, gridY, x, y, z, cost, node);
				// A little greedy: a slightly longer road found much sooner.
				next._estimate = cost + (remaining * 1.2);
				best.put(nextKey, next);
				open.add(next);
			}
		}
		return null;
	}

	/**
	 * @param from where a road starts
	 * @param road its points after that
	 * @return its length, on the ground's plane
	 */
	public static double getLength(ILocational from, List<Location> road)
	{
		double length = 0;
		ILocational previous = from;
		for (Location point : road)
		{
			length += Math.hypot(point.getX() - previous.getX(), point.getY() - previous.getY());
			previous = point;
		}
		return length;
	}

	/** How near the place {@link #findRoute} tries pathfinding for the last stretch, how many times at most, and how much longer than a straight line that way may be. */
	private static final int ROUTE_APPROACH_RANGE = 3000;
	private static final int ROUTE_APPROACH_SEARCHES = 24;
	private static final double ROUTE_APPROACH_DETOUR = 2.5;
	/** How near the place a road may end when the place itself can't be walked to. */
	private static final int ROUTE_END_RANGE = 150;

	/** The neighbours of a point of {@link #findRoute}'s grid: the eight around it and the eight a knight's move away, for roads that are not only straight or diagonal. */
	private static final int[][] ROUTE_DIRECTIONS =
	{
		{ 1, 0 }, { -1, 0 }, { 0, 1 }, { 0, -1 }, { 1, 1 }, { 1, -1 }, { -1, 1 }, { -1, -1 },
		{ 2, 1 }, { 2, -1 }, { -2, 1 }, { -2, -1 }, { 1, 2 }, { 1, -2 }, { -1, 2 }, { -1, -2 }
	};

	private static final class RouteNode
	{
		final int _gridX;
		final int _gridY;
		final int _x;
		final int _y;
		final int _z;
		final double _cost;
		final RouteNode _parent;
		double _estimate;

		RouteNode(int gridX, int gridY, int x, int y, int z, double cost, RouteNode parent)
		{
			_gridX = gridX;
			_gridY = gridY;
			_x = x;
			_y = y;
			_z = z;
			_cost = cost;
			_parent = parent;
		}

		long key()
		{
			return key(_gridX, _gridY);
		}

		static long key(int gridX, int gridY)
		{
			return (((long) gridX) << 32) | (gridY & 0xFFFFFFFFL);
		}
	}

	/** Rings searched around a wanted place by {@link #findPointToward}, and points on each. */
	private static final int REACH_RINGS = 4;
	private static final int REACH_RING_POINTS = 8;
	/** Pathfinding searches allowed in one {@link #findPointToward} call. */
	private static final int REACH_PATH_SEARCHES = 4;
	/** How much longer than the straight line a path may be. */
	private static final double REACH_MAX_DETOUR = 1.6;

	/**
	 * @param x the x
	 * @param y the y
	 * @param z the z
	 * @return {@code true} if the point is in a town or a peace zone
	 */
	public static boolean isTown(int x, int y, int z)
	{
		final ZoneManager zones = ZoneManager.getInstance();
		return (zones.getZone(x, y, z, PeaceZone.class) != null) || (zones.getZone(x, y, z, TownZone.class) != null);
	}

	/**
	 * Spawns an NPC for a scripted task: it doesn't walk around, doesn't go back to its spawn point and carries a marker variable.
	 * @param npcId the NPC id
	 * @param location where
	 * @param heading the heading
	 * @param instanceId the instance
	 * @param markerVar the NPC variable set to {@code true} on it, {@code null} for none
	 * @return the NPC, {@code null} if it could not be spawned
	 */
	public static Npc spawnTaskNpc(int npcId, ILocational location, int heading, int instanceId, String markerVar)
	{
		final Npc npc = Quest.addSpawn(npcId, location.getX(), location.getY(), location.getZ(), heading, false, 0, false, instanceId);
		if (npc == null)
		{
			return null;
		}

		if (markerVar != null)
		{
			npc.getVariables().set(markerVar, true);
		}
		npc.setRandomWalking(false);
		if (npc.isAttackable())
		{
			npc.asAttackable().setCanReturnToSpawnPoint(false);
		}
		return npc;
	}

	/**
	 * Makes a spawned monster a champion of a tier (0 for none), restores its HP/MP and sets its title.
	 * @param npc the monster
	 * @param tier the champion tier (clamped to 0-3)
	 * @param title the title, {@code null} to keep it
	 */
	public static void applyTier(Npc npc, int tier, String title)
	{
		if (npc.isAttackable())
		{
			final Attackable monster = npc.asAttackable();
			final int championTier = Math.max(0, Math.min(3, tier));
			if (championTier > 0)
			{
				monster.setChampionTier(championTier);
			}
			monster.setCurrentHpMp(monster.getMaxHp(), monster.getMaxMp());
		}
		if (title != null)
		{
			npc.setTitle(title);
		}
		npc.broadcastInfo();
	}

	/**
	 * Makes a monster attack a target.
	 * @param npc the monster
	 * @param target the target
	 */
	public static void attack(Npc npc, Creature target)
	{
		if ((npc == null) || (target == null) || !npc.isAttackable() || npc.isDead() || target.isDead())
		{
			return;
		}
		npc.asAttackable().addDamageHate(target, 0, 1000);
		npc.getAI().setIntention(Intention.ATTACK, target);
	}

	public static boolean isAlive(Npc npc)
	{
		return (npc != null) && npc.isSpawned() && !npc.isDead();
	}

	public static int countAlive(Collection<Npc> npcs)
	{
		int alive = 0;
		for (Npc npc : npcs)
		{
			if (isAlive(npc))
			{
				alive++;
			}
		}
		return alive;
	}

	public static void screen(Player player, String message)
	{
		if ((player != null) && (message != null) && !message.isEmpty())
		{
			player.sendPacket(new ExShowScreenMessage(message, ExShowScreenMessage.TOP_CENTER, 5000));
		}
	}

	public static void announce(Player player, String message)
	{
		if ((player != null) && (message != null) && !message.isEmpty())
		{
			player.sendPacket(new ExShowScreenMessage(message, ExShowScreenMessage.MIDDLE_CENTER, 6000));
		}
	}

	/**
	 * An NPC line only one player hears.
	 * @param player the listener
	 * @param npc the speaker
	 * @param message the line
	 */
	public static void say(Player player, Npc npc, String message)
	{
		if ((player != null) && (npc != null) && (message != null) && !message.isEmpty())
		{
			player.sendPacket(new CreatureSay(npc, ChatType.NPC_GENERAL, npc.getName(), message));
		}
	}

	/**
	 * Moves a player's radar marker.
	 * @param player the player
	 * @param previous the marker to remove, may be {@code null}
	 * @param location the new marker, {@code null} to only clear
	 */
	public static void moveMarker(Player player, Location previous, Location location)
	{
		if (player == null)
		{
			return;
		}
		if (previous != null)
		{
			player.getRadar().removeMarker(previous.getX(), previous.getY(), previous.getZ());
			player.sendPacket(new RadarControl(1, 2, previous.getX(), previous.getY(), previous.getZ()));
		}
		if (location != null)
		{
			// The radar's arrow, and the flag on the map (the map only shows type 2 marks).
			player.getRadar().addMarker(location.getX(), location.getY(), location.getZ());
			player.sendPacket(new RadarControl(0, 2, location.getX(), location.getY(), location.getZ()));
		}
	}

	/**
	 * Opens the player's map with a place flagged on it (and the radar pointing at it).
	 * @param player the player
	 * @param location the place
	 */
	public static void showOnMap(Player player, Location location)
	{
		if ((player == null) || (location == null))
		{
			return;
		}
		player.sendPacket(new ShowMiniMap(-1));
		// The map has to be open before the flag is set, or the client doesn't draw it.
		ThreadPool.schedule(() -> moveMarker(player, location, location), 500);
	}

	public static String formatTime(int seconds)
	{
		final int safe = Math.max(0, seconds);
		return (safe / 60) + ":" + ((safe % 60) < 10 ? "0" : "") + (safe % 60);
	}
}
