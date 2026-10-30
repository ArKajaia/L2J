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
package org.l2jmobius.gameserver.managers.conquest;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;

import org.l2jmobius.gameserver.geoengine.GeoEngine;
import org.l2jmobius.gameserver.model.Location;
import org.l2jmobius.gameserver.model.conquest.ConquestMap;
import org.l2jmobius.gameserver.model.conquest.ConquestPoint;

/**
 * The ways across a Conquest map, worked out once per match: a grid of {@link #CELL}-wide cells over the map's area, each with its ground height and the neighbours a fighter can walk to from it (the geodata and the doors of the match's instance, so a shut
 * door is a wall). The pathfinding of the game only finds ways of a few thousand units, so the fake players are led across the map from one waypoint of this grid to the next ({@link #nextWaypoint}), and the commanders compare how far each party
 * walks to each place ({@link #walkDistance}), not how far it is as the crow flies.
 */
public class ConquestNavGrid
{
	/** The width of a cell. */
	public static final int CELL = 64;
	/** Two cells side by side whose ground differs more than this are not linked (a cliff, a floor over another). */
	private static final int MAX_STEP_HEIGHT = 160;
	/** A waypoint lies at most this many cells on along the way. */
	private static final int WAYPOINT_CELLS = 28;
	/** How many distance fields are kept: one per place the parties are sent to (capture points, spawns, enemy groups). */
	private static final int MAX_FIELDS = 48;
	/** Cell flags: walkable, then the neighbours it links to. */
	private static final byte WALKABLE = 1;
	private static final byte EAST = 2;
	private static final byte WEST = 4;
	private static final byte SOUTH = 8;
	private static final byte NORTH = 16;
	private static final int UNREACHED = Integer.MAX_VALUE;
	/** A way of at most this many cells (as the crow flies) is searched for on its own (A*), a longer one follows the distance field of its end. */
	private static final int SHORT_ROUTE = 90;
	/** The search of a short way gives up after looking at this many cells. */
	private static final int MAX_EXPANSIONS = 12000;
	/** Of a way found, the farthest point at most this many cells along that can be walked to in a straight line is the next step. */
	private static final int PULL_CELLS = 40;
	/** A fighter this far above or below the ground of the cells around it is not standing on it. */
	private static final int GROUND_TOLERANCE = 250;

	private final int _minX;
	private final int _minY;
	private final int _width;
	private final int _height;
	private final int[] _z;
	private final byte[] _flags;
	private volatile boolean _ready;
	private int _walkable;
	private final Map<Integer, int[]> _fields = new LinkedHashMap<Integer, int[]>(64, 0.75f, true)
	{
		private static final long serialVersionUID = 1L;

		@Override
		protected boolean removeEldestEntry(Map.Entry<Integer, int[]> eldest)
		{
			return size() > MAX_FIELDS;
		}
	};

	ConquestNavGrid(ConquestMap map)
	{
		_minX = map.minX();
		_minY = map.minY();
		_width = Math.max(1, ((map.maxX() - map.minX()) / CELL) + 1);
		_height = Math.max(1, ((map.maxY() - map.minY()) / CELL) + 1);
		_z = new int[_width * _height];
		_flags = new byte[_width * _height];
	}

	/**
	 * Floods the grid from the map's spawn and capture points, through the ground the fighters can walk, in the match's instance. Takes a moment on a big map: called off the game threads.
	 * @param map the map
	 * @param instanceId the match's instance (its doors)
	 */
	void build(ConquestMap map, int instanceId)
	{
		final GeoEngine geo = GeoEngine.getInstance();
		final int[] queue = new int[_width * _height];
		int head = 0;
		int tail = 0;
		for (ConquestPoint point : map.points())
		{
			final int cell = cellOf(point.x(), point.y());
			if ((cell < 0) || ((_flags[cell] & WALKABLE) != 0))
			{
				continue;
			}
			_z[cell] = geo.getHeight(centerX(cell), centerY(cell), point.z());
			_flags[cell] = WALKABLE;
			queue[tail++] = cell;
		}

		while (head < tail)
		{
			final int cell = queue[head++];
			final int cx = cell % _width;
			final int cy = cell / _width;
			final int x = centerX(cell);
			final int y = centerY(cell);
			final int z = _z[cell];
			for (int dir = 0; dir < 4; dir++)
			{
				final int nx = cx + (dir == 0 ? 1 : dir == 1 ? -1 : 0);
				final int ny = cy + (dir == 2 ? 1 : dir == 3 ? -1 : 0);
				if ((nx < 0) || (ny < 0) || (nx >= _width) || (ny >= _height))
				{
					continue;
				}

				final int next = (ny * _width) + nx;
				final boolean known = (_flags[next] & WALKABLE) != 0;
				final int tx = centerX(next);
				final int ty = centerY(next);
				final int tz = known ? _z[next] : geo.getHeight(tx, ty, z);
				if ((Math.abs(tz - z) > MAX_STEP_HEIGHT) || !geo.canMoveToTarget(x, y, z, tx, ty, tz, instanceId))
				{
					continue;
				}

				_flags[cell] |= flag(dir);
				_flags[next] |= flag(opposite(dir));
				if (!known)
				{
					_z[next] = tz;
					_flags[next] |= WALKABLE;
					queue[tail++] = next;
				}
			}
		}
		_walkable = tail;
		_ready = true;
	}

	/**
	 * @return {@code true} once the grid is built
	 */
	public boolean isReady()
	{
		return _ready;
	}

	/**
	 * @return how many cells can be walked
	 */
	public int getWalkableCells()
	{
		return _walkable;
	}

	/**
	 * @param x a fighter's x
	 * @param y its y
	 * @param z its z
	 * @return {@code true} if it stands on ground of the battlefield (a walkable cell next to it at about its height), or the grid isn't built yet; {@code false} for one inside a wall or the rock, or under or over the floor
	 */
	public boolean isOnGround(int x, int y, int z)
	{
		if (!_ready)
		{
			return true;
		}

		final int cx = (x - _minX) / CELL;
		final int cy = (y - _minY) / CELL;
		for (int dy = -1; dy <= 1; dy++)
		{
			for (int dx = -1; dx <= 1; dx++)
			{
				final int nx = cx + dx;
				final int ny = cy + dy;
				if ((nx < 0) || (ny < 0) || (nx >= _width) || (ny >= _height))
				{
					continue;
				}
				final int cell = (ny * _width) + nx;
				if (((_flags[cell] & WALKABLE) != 0) && (Math.abs(_z[cell] - z) <= GROUND_TOLERANCE))
				{
					return true;
				}
			}
		}
		return false;
	}

	/**
	 * @param from where a fighter stands
	 * @param to where it goes
	 * @return the next waypoint on the way there, at most a short walk ahead (the target itself when it is that close, or when the grid knows no way)
	 */
	public Location nextWaypoint(Location from, Location to)
	{
		if (!_ready)
		{
			return to;
		}

		final int target = nearestWalkable(to.getX(), to.getY(), 4);
		int cell = nearestWalkable(from.getX(), from.getY(), 4);
		if ((target < 0) || (cell < 0))
		{
			return to;
		}

		final int[] field = field(target);
		if (field[cell] == UNREACHED)
		{
			return to;
		}
		if (field[cell] <= WAYPOINT_CELLS)
		{
			return to;
		}

		for (int step = 0; (step < WAYPOINT_CELLS) && (field[cell] > 0); step++)
		{
			int best = -1;
			for (int dir = 0; dir < 4; dir++)
			{
				if ((_flags[cell] & flag(dir)) == 0)
				{
					continue;
				}
				final int next = neighbour(cell, dir);
				if ((best < 0) || (field[next] < field[best]))
				{
					best = next;
				}
			}
			if ((best < 0) || (field[best] >= field[cell]))
			{
				break;
			}
			cell = best;
		}
		return new Location(centerX(cell), centerY(cell), _z[cell]);
	}

	/**
	 * @param from a spot
	 * @param to another spot
	 * @return how far a fighter walks from one to the other, as the crow flies when the grid knows no way (or isn't built yet)
	 */
	public double walkDistance(Location from, Location to)
	{
		final double straight = Math.hypot(from.getX() - to.getX(), from.getY() - to.getY());
		if (!_ready)
		{
			return straight;
		}

		final int target = nearestWalkable(to.getX(), to.getY(), 4);
		final int cell = nearestWalkable(from.getX(), from.getY(), 4);
		if ((target < 0) || (cell < 0))
		{
			return straight;
		}
		final int cells = field(target)[cell];
		return cells == UNREACHED ? straight * 3 : Math.max(straight, (double) cells * CELL);
	}

	/**
	 * The next stretch of a way the game's pathfinding didn't find (too long for it, or to a spot inside a wall): the way along the grid's walkable cells (A* for a short one, the distance field of its end for a long one), and of it the farthest point a fighter
	 * can walk to in a straight line from where it stands.
	 * @param from where the fighter stands
	 * @param to where it wants to go (a spot inside a wall or the rock counts as the walkable ground nearest it)
	 * @param instanceId the instance (its doors)
	 * @return the spot to walk to now, {@code null} if the grid knows no way (or isn't built yet)
	 */
	public Location routeStep(Location from, Location to, int instanceId)
	{
		if (!_ready)
		{
			return null;
		}

		final int start = nearestWalkable(from.getX(), from.getY(), 3);
		final int goal = nearestWalkable(to.getX(), to.getY(), 12);
		if ((start < 0) || (goal < 0))
		{
			return null;
		}
		if (start == goal)
		{
			return new Location(centerX(goal), centerY(goal), _z[goal]);
		}

		final int cells = Math.abs((start % _width) - (goal % _width)) + Math.abs((start / _width) - (goal / _width));
		final List<Integer> way = cells <= SHORT_ROUTE ? search(start, goal) : downhill(start, goal);
		if ((way == null) || way.isEmpty())
		{
			return null;
		}

		// The farthest point along it that can be walked to straight away.
		final GeoEngine geo = GeoEngine.getInstance();
		final int last = Math.min(way.size() - 1, PULL_CELLS);
		for (int i = last; i > 0; i -= i > 10 ? 2 : 1)
		{
			final int cell = way.get(i);
			if (geo.canMoveToTarget(from.getX(), from.getY(), from.getZ(), centerX(cell), centerY(cell), _z[cell], instanceId))
			{
				return new Location(centerX(cell), centerY(cell), _z[cell]);
			}
		}
		final int next = way.get(Math.min(1, way.size() - 1));
		return new Location(centerX(next), centerY(next), _z[next]);
	}

	/** @return the cells of the shortest way from start to goal, start first (A* over the linked cells), {@code null} if there is none close enough */
	private List<Integer> search(int start, int goal)
	{
		final int gx = goal % _width;
		final int gy = goal / _width;
		final Map<Integer, Integer> cost = new HashMap<>();
		final Map<Integer, Integer> cameFrom = new HashMap<>();
		final PriorityQueue<long[]> open = new PriorityQueue<>((a, b) -> Long.compare(a[0], b[0]));
		cost.put(start, 0);
		open.add(new long[]
		{
			0,
			start
		});
		int expansions = 0;
		while (!open.isEmpty() && (expansions++ < MAX_EXPANSIONS))
		{
			final int cell = (int) open.poll()[1];
			if (cell == goal)
			{
				final List<Integer> way = new ArrayList<>();
				for (Integer at = goal; at != null; at = cameFrom.get(at))
				{
					way.add(0, at);
				}
				return way;
			}
			final int known = cost.get(cell);
			for (int dir = 0; dir < 4; dir++)
			{
				if ((_flags[cell] & flag(dir)) == 0)
				{
					continue;
				}
				final int next = neighbour(cell, dir);
				final int nextCost = known + 1;
				final Integer old = cost.get(next);
				if ((old == null) || (nextCost < old))
				{
					cost.put(next, nextCost);
					cameFrom.put(next, cell);
					final int estimate = nextCost + Math.abs((next % _width) - gx) + Math.abs((next / _width) - gy);
					open.add(new long[]
					{
						estimate,
						next
					});
				}
			}
		}
		return null;
	}

	/** @return the first cells of the way from start down the distance field of goal, start first, {@code null} if goal can't be reached from start */
	private List<Integer> downhill(int start, int goal)
	{
		final int[] field = field(goal);
		if (field[start] == UNREACHED)
		{
			return null;
		}
		final List<Integer> way = new ArrayList<>();
		int cell = start;
		way.add(cell);
		while ((way.size() <= PULL_CELLS) && (field[cell] > 0))
		{
			int best = -1;
			for (int dir = 0; dir < 4; dir++)
			{
				if ((_flags[cell] & flag(dir)) != 0)
				{
					final int next = neighbour(cell, dir);
					if ((best < 0) || (field[next] < field[best]))
					{
						best = next;
					}
				}
			}
			if ((best < 0) || (field[best] >= field[cell]))
			{
				break;
			}
			cell = best;
			way.add(cell);
		}
		return way;
	}

	/**
	 * @param x a spot's x
	 * @param y a spot's y
	 * @param z the height to fall back on
	 * @return the walkable ground nearest the spot (within a dozen cells), the spot itself at that height if there is none
	 */
	public Location nearestGround(int x, int y, int z)
	{
		final int cell = _ready ? nearestWalkable(x, y, 12) : -1;
		if (cell < 0)
		{
			return new Location(x, y, GeoEngine.getInstance().getHeight(x, y, z));
		}
		return new Location(centerX(cell), centerY(cell), _z[cell]);
	}

	/** The cells' distance (in steps) to a target cell, worked out once and kept for a while. */
	private int[] field(int target)
	{
		synchronized (_fields)
		{
			final int[] known = _fields.get(target);
			if (known != null)
			{
				return known;
			}
		}

		final int[] field = new int[_width * _height];
		Arrays.fill(field, UNREACHED);
		final int[] queue = new int[_width * _height];
		int head = 0;
		int tail = 0;
		field[target] = 0;
		queue[tail++] = target;
		while (head < tail)
		{
			final int cell = queue[head++];
			for (int dir = 0; dir < 4; dir++)
			{
				if ((_flags[cell] & flag(dir)) == 0)
				{
					continue;
				}
				final int next = neighbour(cell, dir);
				if (field[next] == UNREACHED)
				{
					field[next] = field[cell] + 1;
					queue[tail++] = next;
				}
			}
		}

		synchronized (_fields)
		{
			_fields.put(target, field);
		}
		return field;
	}

	/**
	 * @return the walkable cell nearest the spot, looking at most {@code reach} cells around, -1 if none
	 */
	private int nearestWalkable(int x, int y, int reach)
	{
		final int cx = Math.max(0, Math.min(_width - 1, (x - _minX) / CELL));
		final int cy = Math.max(0, Math.min(_height - 1, (y - _minY) / CELL));
		for (int ring = 0; ring <= reach; ring++)
		{
			int best = -1;
			double bestDistance = Double.MAX_VALUE;
			for (int dy = -ring; dy <= ring; dy++)
			{
				for (int dx = -ring; dx <= ring; dx++)
				{
					if ((Math.abs(dx) != ring) && (Math.abs(dy) != ring))
					{
						continue;
					}
					final int nx = cx + dx;
					final int ny = cy + dy;
					if ((nx < 0) || (ny < 0) || (nx >= _width) || (ny >= _height))
					{
						continue;
					}
					final int cell = (ny * _width) + nx;
					if ((_flags[cell] & WALKABLE) == 0)
					{
						continue;
					}
					final double distance = Math.hypot(centerX(cell) - x, centerY(cell) - y);
					if (distance < bestDistance)
					{
						best = cell;
						bestDistance = distance;
					}
				}
			}
			if (best >= 0)
			{
				return best;
			}
		}
		return -1;
	}

	/**
	 * @param places some places
	 * @return {@code true} if every one of them can be walked to from the first (the map has no island a team can't leave)
	 */
	public boolean linksAll(List<Location> places)
	{
		if (!_ready || places.isEmpty())
		{
			return false;
		}
		final int first = nearestWalkable(places.get(0).getX(), places.get(0).getY(), 4);
		if (first < 0)
		{
			return false;
		}
		final int[] field = field(first);
		for (Location place : places)
		{
			final int cell = nearestWalkable(place.getX(), place.getY(), 4);
			if ((cell < 0) || (field[cell] == UNREACHED))
			{
				return false;
			}
		}
		return true;
	}

	private int cellOf(int x, int y)
	{
		final int cx = (x - _minX) / CELL;
		final int cy = (y - _minY) / CELL;
		return (cx < 0) || (cy < 0) || (cx >= _width) || (cy >= _height) ? -1 : (cy * _width) + cx;
	}

	private int centerX(int cell)
	{
		return _minX + ((cell % _width) * CELL) + (CELL / 2);
	}

	private int centerY(int cell)
	{
		return _minY + ((cell / _width) * CELL) + (CELL / 2);
	}

	private int neighbour(int cell, int dir)
	{
		return switch (dir)
		{
			case 0 -> cell + 1;
			case 1 -> cell - 1;
			case 2 -> cell + _width;
			default -> cell - _width;
		};
	}

	private static byte flag(int dir)
	{
		return switch (dir)
		{
			case 0 -> EAST;
			case 1 -> WEST;
			case 2 -> SOUTH;
			default -> NORTH;
		};
	}

	private static int opposite(int dir)
	{
		return dir ^ 1;
	}
}
