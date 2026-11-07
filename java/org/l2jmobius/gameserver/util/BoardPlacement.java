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

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.logging.Logger;

import org.l2jmobius.gameserver.data.SpawnTable;
import org.l2jmobius.gameserver.data.xml.NpcData;
import org.l2jmobius.gameserver.data.xml.StaticObjectData;
import org.l2jmobius.gameserver.geoengine.GeoEngine;
import org.l2jmobius.gameserver.geoengine.geodata.Cell;
import org.l2jmobius.gameserver.managers.WalkingManager;
import org.l2jmobius.gameserver.model.Location;
import org.l2jmobius.gameserver.model.World;
import org.l2jmobius.gameserver.model.WorldObject;
import org.l2jmobius.gameserver.model.actor.Npc;
import org.l2jmobius.gameserver.model.actor.holders.npc.WalkNode;
import org.l2jmobius.gameserver.model.actor.holders.npc.WalkRoute;
import org.l2jmobius.gameserver.model.actor.instance.StaticObject;
import org.l2jmobius.gameserver.model.actor.templates.NpcTemplate;
import org.l2jmobius.gameserver.model.interfaces.ILocational;
import org.l2jmobius.gameserver.model.spawns.Spawn;

/**
 * Where to stand a big square board (the Notice Board, the Defence Force Board) near a town npc, the gatekeeper:
 * <ul>
 * <li>squared up with the town: its heading is east, north, west or south, never in between;</li>
 * <li>on flat ground with no wall inside its base, and with room in front of it to read it;</li>
 * <li>clear of every npc, town map and walking route around, and of the other boards;</li>
 * <li>not in front of the gatekeeper, where the players stand to talk to it.</li>
 * </ul>
 * The search is the same every time for the same town. The boards' data can also give a fixed place, which is used as it is.
 * @author Mobius
 */
public final class BoardPlacement
{
	/** The four headings a board can have: facing east, south, west, north (heading 0 faces +X, 16384 faces +Y). */
	private static final int[] HEADINGS =
	{
		0,
		16384,
		32768,
		49152
	};
	private static final int[][] DIRECTIONS =
	{
		{
			1,
			0
		},
		{
			0,
			1
		},
		{
			-1,
			0
		},
		{
			0,
			-1
		}
	};

	/** Searched around the gatekeeper on the geodata cells, preferably this far from it. */
	private static final int STEP = 16;
	private static final int PREFERRED_DISTANCE = 220;
	/** How far an npc's own body reaches when it is not known. */
	private static final int NPC_RADIUS = 24;
	/** The space in front of the gatekeeper kept free: this far. */
	private static final int FRONT_DISTANCE = 320;
	/** The rules of the search, then looser ones where the gatekeeper is hemmed in. */
	private static final Rules[] RULES =
	{
		new Rules(420, 48, 40, 55),
		new Rules(700, 32, 28, 40),
	};
	/** The ground under a board may not be more uneven than this. */
	private static final int FLAT = 12;
	/** The board stands on the same floor as the gatekeeper. */
	private static final int SAME_FLOOR = 48;
	/** No wall or pillar this close to its sides and front (its back may be against a wall). */
	private static final int WALL_ROOM = 32;
	/** Where the players stand to read it, in front of it. */
	private static final int READING_ROOM = 70;

	private static final Logger LOGGER = Logger.getLogger(BoardPlacement.class.getName());

	/** The boards already placed, so that two boards don't stand on each other. */
	private static final List<Spot> PLACED = new CopyOnWriteArrayList<>();

	/**
	 * A thing a board keeps clear of: a circle on the ground.
	 * @param x its x
	 * @param y its y
	 * @param z its z
	 * @param radius its radius
	 */
	public record Obstacle(int x, int y, int z, int radius)
	{
	}

	/**
	 * A stretch of a walking route.
	 * @param x1 start x
	 * @param y1 start y
	 * @param x2 end x
	 * @param y2 end y
	 * @param z its z
	 */
	public record Path(int x1, int y1, int x2, int y2, int z)
	{
	}

	/**
	 * How far from the gatekeeper a board may stand and how much room it leaves around it.
	 * @param maxDistance the farthest from the gatekeeper
	 * @param margin the room left between a board and an npc, a town map or another board
	 * @param routeMargin the room left between a board and a walking route
	 * @param frontAngle the space in front of the gatekeeper kept free, in degrees each side
	 */
	private record Rules(int maxDistance, int margin, int routeMargin, double frontAngle)
	{
	}

	/**
	 * A board's place.
	 * @param location where it stands, its heading included
	 * @param halfSide half the side of its square base
	 */
	private record Spot(Location location, int halfSide)
	{
	}

	private BoardPlacement()
	{
	}

	/**
	 * @param heading any heading
	 * @return the nearest of the four square headings (0, 16384, 32768, 49152)
	 */
	public static int squareHeading(int heading)
	{
		return ((((heading & 0xFFFF) + 8192) / 16384) % 4) * 16384;
	}

	/**
	 * Marks a board's place as taken, for the boards placed after it.
	 * @param location where it stands
	 * @param halfSide half the side of its square base
	 */
	public static void reserve(ILocational location, int halfSide)
	{
		PLACED.add(new Spot(new Location(location.getX(), location.getY(), location.getZ(), location.getHeading()), halfSide));
	}

	/**
	 * Places a board next to a town npc: where the data says, else where {@link #find(ILocational, int)} finds room, and marks its place as taken.
	 * @param anchorSpawn the town npc's spawn
	 * @param boardNpcId the board npc (the size of its base is its collision radius)
	 * @param fixed where the data says the board stands, its heading included, {@code null} to find a place
	 * @return the board's place, its heading included, {@code null} if the town npc is nowhere
	 */
	public static Location place(Spawn anchorSpawn, int boardNpcId, Location fixed)
	{
		final NpcTemplate template = NpcData.getInstance().getTemplate(boardNpcId);
		final int halfSide = template != null ? (int) Math.ceil(template.getFCollisionRadius()) : 64;
		Location location = fixed;
		if (location == null)
		{
			final Npc npc = anchorSpawn.getLastSpawn();
			final ILocational anchor = npc != null ? npc : anchorSpawn;
			if ((anchor.getX() == 0) && (anchor.getY() == 0))
			{
				return null;
			}
			location = find(anchor, halfSide);
			if (location == null)
			{
				final Location point = QuestSpawnUtil.findPoint(anchor, halfSide + 90, halfSide + 200, true, 0);
				location = new Location(point.getX(), point.getY(), point.getZ(), squareHeading(LocationUtil.calculateHeadingFrom(point, anchor)));
				LOGGER.warning(BoardPlacement.class.getSimpleName() + ": No room for board " + boardNpcId + " near npc " + anchorSpawn.getId() + ", put at " + location + ". Set its place in the data.");
			}
		}
		reserve(location, halfSide);
		return location;
	}

	/**
	 * Finds a board's place near a town npc, looking at what stands there now: the spawns, the npcs around it, the town maps, the walking routes and the boards already placed.
	 * @param anchor the town npc (or its place), its heading included (the way it faces)
	 * @param halfSide half the side of the board's square base
	 * @return the place, its heading included, {@code null} if there is none
	 */
	public static Location find(ILocational anchor, int halfSide)
	{
		final int range = RULES[RULES.length - 1].maxDistance() + 400;
		final List<Obstacle> obstacles = new ArrayList<>();
		for (Set<Spawn> spawns : SpawnTable.getInstance().getSpawnTable().values())
		{
			for (Spawn spawn : spawns)
			{
				addIfNear(obstacles, anchor, range, spawn.getX(), spawn.getY(), spawn.getZ(), NPC_RADIUS);
			}
		}
		if (anchor instanceof WorldObject object)
		{
			for (Npc npc : World.getInstance().getVisibleObjectsInRange(object, Npc.class, range))
			{
				addIfNear(obstacles, anchor, range, npc.getX(), npc.getY(), npc.getZ(), Math.max(NPC_RADIUS, (int) npc.getCollisionRadius()));
			}
		}
		for (StaticObject object : StaticObjectData.getInstance().getStaticObjects())
		{
			addIfNear(obstacles, anchor, range, object.getX(), object.getY(), object.getZ(), NPC_RADIUS);
		}

		final List<Path> paths = new ArrayList<>();
		for (WalkRoute route : WalkingManager.getInstance().getRoutes())
		{
			final List<WalkNode> nodes = route.getNodeList();
			for (int i = 1; i < nodes.size(); i++)
			{
				final WalkNode from = nodes.get(i - 1);
				final WalkNode to = nodes.get(i);
				if ((distanceToSegment(anchor.getX(), anchor.getY(), from.getX(), from.getY(), to.getX(), to.getY()) < range) && (Math.abs(from.getZ() - anchor.getZ()) < 400))
				{
					paths.add(new Path(from.getX(), from.getY(), to.getX(), to.getY(), from.getZ()));
				}
			}
		}
		return find(anchor, halfSide, obstacles, paths);
	}

	private static void addIfNear(List<Obstacle> obstacles, ILocational anchor, int range, int x, int y, int z, int radius)
	{
		final long dx = x - anchor.getX();
		final long dy = y - anchor.getY();
		// Not the anchor itself: it is kept clear of by the search.
		if (((dx * dx) + (dy * dy)) < ((long) range * range) && (((dx != 0) || (dy != 0))) && (Math.abs(z - anchor.getZ()) < 400))
		{
			obstacles.add(new Obstacle(x, y, z, radius));
		}
	}

	/**
	 * Finds a board's place near a town npc.
	 * @param anchor the town npc's place, its heading included (the way it faces)
	 * @param halfSide half the side of the board's square base
	 * @param obstacles what the board keeps clear of, the anchor excluded
	 * @param paths the walking routes the board keeps clear of
	 * @return the place, its heading included, {@code null} if there is none
	 */
	public static Location find(ILocational anchor, int halfSide, List<Obstacle> obstacles, List<Path> paths)
	{
		for (Rules rules : RULES)
		{
			final Location location = find(anchor, halfSide, obstacles, paths, rules);
			if (location != null)
			{
				return location;
			}
		}
		return null;
	}

	private static Location find(ILocational anchor, int halfSide, List<Obstacle> obstacles, List<Path> paths, Rules rules)
	{
		final GeoEngine geo = GeoEngine.getInstance();
		final int ax = anchor.getX();
		final int ay = anchor.getY();
		final int az = geo.getHeight(ax, ay, anchor.getZ());
		final double frontAngle = Math.toRadians(LocationUtil.convertHeadingToDegree(anchor.getHeading()));
		final double reach = halfSide * Math.sqrt(2); // From its middle to a corner.

		Location best = null;
		double bestScore = Double.MAX_VALUE;
		final int maxDistance = rules.maxDistance();
		for (int dx = -maxDistance; dx <= maxDistance; dx += STEP)
		{
			for (int dy = -maxDistance; dy <= maxDistance; dy += STEP)
			{
				final double distance = Math.sqrt((dx * dx) + (dy * dy));
				if ((distance > maxDistance) || (distance < (reach + NPC_RADIUS + rules.margin())))
				{
					continue;
				}

				// Not in front of the gatekeeper.
				if (distance < (FRONT_DISTANCE + reach))
				{
					double off = Math.abs(Math.toDegrees(Math.atan2(dy, dx) - frontAngle)) % 360;
					if (off > 180)
					{
						off = 360 - off;
					}
					if (off < rules.frontAngle())
					{
						continue;
					}
				}

				final int x = ax + dx;
				final int y = ay + dy;
				final int z = geo.getHeight(x, y, az + 32);
				if (Math.abs(z - az) > SAME_FLOOR)
				{
					continue;
				}
				if (nearestClearance(x, y, z, reach, obstacles, paths, rules) < 0)
				{
					continue;
				}
				if (!hasFlatBase(geo, x, y, z, halfSide))
				{
					continue;
				}
				// The gatekeeper can walk there: the board is not behind a wall or on another floor.
				if (!geo.canMoveToTarget(ax, ay, az, x, y, z, 0))
				{
					continue;
				}

				// It faces the side with room in front of it, the gatekeeper's side if it can.
				final double toAnchor = Math.atan2(ay - y, ax - x);
				int bestSide = -1;
				double bestSideScore = Double.MAX_VALUE;
				for (int side = 0; side < 4; side++)
				{
					final int fx = x + (DIRECTIONS[side][0] * (halfSide + READING_ROOM));
					final int fy = y + (DIRECTIONS[side][1] * (halfSide + READING_ROOM));
					final int fz = geo.getHeight(fx, fy, z + 32);
					if ((Math.abs(fz - z) > SAME_FLOOR) || !geo.canMoveToTarget(x, y, z, fx, fy, fz, 0) || !geo.canMoveToTarget(ax, ay, az, fx, fy, fz, 0) || !hasRoomAround(geo, x, y, z, halfSide, side))
					{
						continue;
					}
					double off = Math.abs(Math.toDegrees(Math.atan2(DIRECTIONS[side][1], DIRECTIONS[side][0]) - toAnchor)) % 360;
					if (off > 180)
					{
						off = 360 - off;
					}
					if (off < bestSideScore)
					{
						bestSideScore = off;
						bestSide = side;
					}
				}
				if (bestSide < 0)
				{
					continue;
				}

				// Close to the gatekeeper but not on top of it, facing it, its back to a wall if there is one.
				final int bx = x - (DIRECTIONS[bestSide][0] * (halfSide + 24));
				final int by = y - (DIRECTIONS[bestSide][1] * (halfSide + 24));
				final boolean wallBehind = !geo.canMoveToTarget(x, y, z, bx, by, geo.getHeight(bx, by, z + 32), 0);
				final double score = Math.abs(distance - PREFERRED_DISTANCE) + (bestSideScore * 2) + (wallBehind ? -60 : 0) - (nearestClearance(x, y, z, reach, obstacles, paths, rules) / 4);
				if (score < bestScore)
				{
					bestScore = score;
					best = new Location(x, y, z, HEADINGS[bestSide]);
				}
			}
		}
		return best;
	}

	/**
	 * @return the room left past the margins to the nearest obstacle, route or board (capped), negative if one is too close
	 */
	private static double nearestClearance(int x, int y, int z, double reach, List<Obstacle> obstacles, List<Path> paths, Rules rules)
	{
		double nearest = 200;
		for (Obstacle obstacle : obstacles)
		{
			if (Math.abs(obstacle.z() - z) < 300)
			{
				nearest = Math.min(nearest, Math.hypot(obstacle.x() - x, obstacle.y() - y) - reach - obstacle.radius() - rules.margin());
			}
		}
		for (Path path : paths)
		{
			if (Math.abs(path.z() - z) < 300)
			{
				nearest = Math.min(nearest, distanceToSegment(x, y, path.x1(), path.y1(), path.x2(), path.y2()) - reach - rules.routeMargin());
			}
		}
		for (Spot spot : PLACED)
		{
			if (Math.abs(spot.location().getZ() - z) < 300)
			{
				nearest = Math.min(nearest, Math.hypot(spot.location().getX() - x, spot.location().getY() - y) - reach - (spot.halfSide() * Math.sqrt(2)) - rules.margin());
			}
		}
		return nearest;
	}

	/**
	 * @return whether there is no wall or pillar around a square base facing that side, but behind it
	 */
	private static boolean hasRoomAround(GeoEngine geo, int x, int y, int z, int halfSide, int side)
	{
		final int reach = halfSide + WALL_ROOM;
		for (int ox = -reach; ox <= reach; ox += STEP)
		{
			for (int oy = -reach; oy <= reach; oy += STEP)
			{
				// Behind it: along the back, past its base.
				if ((((ox * -DIRECTIONS[side][0]) + (oy * -DIRECTIONS[side][1])) > halfSide))
				{
					continue;
				}
				final int px = x + ox;
				final int py = y + oy;
				if (!geo.checkNearestNswe(GeoEngine.getGeoX(px), GeoEngine.getGeoY(py), z, Cell.NSWE_ALL))
				{
					return false;
				}
			}
		}
		return true;
	}

	/**
	 * @return whether the ground under a square base is flat and has no wall in it
	 */
	private static boolean hasFlatBase(GeoEngine geo, int x, int y, int z, int halfSide)
	{
		for (int ox = -halfSide; ox <= halfSide; ox += STEP)
		{
			for (int oy = -halfSide; oy <= halfSide; oy += STEP)
			{
				final int px = x + ox;
				final int py = y + oy;
				final int pz = geo.getHeight(px, py, z + 32);
				if ((Math.abs(pz - z) > FLAT) || !geo.checkNearestNswe(GeoEngine.getGeoX(px), GeoEngine.getGeoY(py), pz, Cell.NSWE_ALL))
				{
					return false;
				}
			}
		}
		// No wall between its middle and its edges.
		for (int[] corner : new int[][]
		{
			{
				halfSide,
				0
			},
			{
				-halfSide,
				0
			},
			{
				0,
				halfSide
			},
			{
				0,
				-halfSide
			},
			{
				halfSide,
				halfSide
			},
			{
				-halfSide,
				halfSide
			},
			{
				halfSide,
				-halfSide
			},
			{
				-halfSide,
				-halfSide
			}
		})
		{
			final int px = x + corner[0];
			final int py = y + corner[1];
			if (!geo.canMoveToTarget(x, y, z, px, py, geo.getHeight(px, py, z + 32), 0))
			{
				return false;
			}
		}
		return true;
	}

	private static double distanceToSegment(int px, int py, int x1, int y1, int x2, int y2)
	{
		final double dx = x2 - x1;
		final double dy = y2 - y1;
		final double length = (dx * dx) + (dy * dy);
		double t = length == 0 ? 0 : (((px - x1) * dx) + ((py - y1) * dy)) / length;
		t = Math.max(0, Math.min(1, t));
		return Math.hypot(px - (x1 + (t * dx)), py - (y1 + (t * dy)));
	}
}
