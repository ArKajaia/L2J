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
package org.l2jmobius.gameserver.model.villagesiege;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.l2jmobius.gameserver.config.custom.VillageSiegeConfig;
import org.l2jmobius.gameserver.geoengine.GeoEngine;
import org.l2jmobius.gameserver.geoengine.pathfinding.GeoLocation;
import org.l2jmobius.gameserver.geoengine.pathfinding.PathFinding;
import org.l2jmobius.gameserver.model.Location;
import org.l2jmobius.gameserver.model.dynamicquest.WorldMonsterIndex;
import org.l2jmobius.gameserver.model.villagesiege.SiegeVillage.FixedEntrance;
import org.l2jmobius.gameserver.model.zone.ZoneType;

/**
 * Finds the entrances of a village: the roads the monsters of the hunting grounds around it take into it.
 * <p>
 * The monster spawn points around the village are grouped into hunting grounds. Toward each ground a path is found into the village (its gatekeeper is its heart), and where the path crosses into the village's TownZone is an entrance. Crossings close to each other are one
 * entrance, and the entrances the most monsters come through are kept. The gate standard stands a little outside the crossing, the waves form further out on the same road, and the guards stand on the road behind the gate, inside the village.
 * </p>
 * Pathfinding only reaches about 3,500 units, so the road is looked for where the line from the heart to a ground leaves the village: from a point out on that line to a point just inside.
 */
public final class SiegeEntranceFinder
{
	/** Spawn points are grouped into hunting grounds of this size. */
	private static final int CELL_SIZE = 1500;
	/** Most hunting grounds a path is tried from. */
	private static final int MAX_SOURCES = 16;
	/** The ray from the heart looks this far for the edge of the village. */
	private static final int MAX_RAY = 8000;
	/** Steps of the ray. */
	private static final int RAY_STEP = 50;
	/** A road is looked for from these distances outside the village, in turn (pathfinding reaches about 3,500). */
	private static final int[] PROBE_OUTSIDE =
	{
		1500,
		1000,
		2200,
		700
	};
	/** Waves form at least this far from their gate. */
	private static final int MIN_RALLY_DISTANCE = 400;
	/** ... to a point this far inside it. */
	private static final int TARGET_INSIDE = 500;
	/** The gate standard stands this far outside the village, on the road. */
	private static final int GATE_OUTSIDE = 120;
	/** The guards of a line stand this far apart. */
	private static final int GUARD_SPACING = 110;

	private static final String[] DIRECTIONS =
	{
		"East",
		"South-East",
		"South",
		"South-West",
		"West",
		"North-West",
		"North",
		"North-East"
	};

	private SiegeEntranceFinder()
	{
	}

	/**
	 * A road crossing into the village.
	 */
	private static class Crossing
	{
		final Location _point;
		final List<Location> _approach;
		final List<Location> _inside;
		int _weight;
		// Set in the data: the gate stands where it says, and the waves form at its rally point if it has one.
		boolean _fixed;
		Location _rally;

		Crossing(Location point, List<Location> approach, List<Location> inside, int weight)
		{
			_point = point;
			_approach = approach;
			_inside = inside;
			_weight = weight;
		}
	}

	/**
	 * @param village the village
	 * @param heart the heart of the village (its gatekeeper)
	 * @param town the village's TownZone
	 * @param level the level of the siege's monsters
	 * @return the entrances, the busiest first, at most {@link VillageSiegeConfig#MAX_ENTRANCES}
	 */
	public static List<SiegeEntrance> find(SiegeVillage village, Location heart, ZoneType town, int level)
	{
		// The hunting grounds around: spawn points of about the siege's level, outside the village, grouped into cells.
		final Map<Long, long[]> cells = new HashMap<>();
		final int window = VillageSiegeConfig.LEVEL_WINDOW + 4;
		WorldMonsterIndex.getInstance().forEachPointNear(heart.getX(), heart.getY(), VillageSiegeConfig.SOURCE_RANGE, (npcId, pointLevel, x, y, z) ->
		{
			if ((Math.abs(pointLevel - level) > window) || isInside(town, x, y))
			{
				return;
			}
			final long key = (((long) Math.floorDiv(x, CELL_SIZE)) << 32) ^ (Math.floorDiv(y, CELL_SIZE) & 0xFFFFFFFFL);
			final long[] cell = cells.computeIfAbsent(key, k -> new long[3]);
			cell[0]++;
			cell[1] += x;
			cell[2] += y;
		});

		final List<int[]> sources = new ArrayList<>();
		for (long[] cell : cells.values())
		{
			sources.add(new int[]
			{
				(int) (cell[1] / cell[0]),
				(int) (cell[2] / cell[0]),
				(int) cell[0]
			});
		}
		return find(village, heart, town, sources);
	}

	/**
	 * @param village the village
	 * @param heart the heart of the village (its gatekeeper)
	 * @param town the village's TownZone
	 * @param sources the hunting grounds around it: x, y and how many monsters live there
	 * @return the entrances, the busiest first, at most {@link VillageSiegeConfig#MAX_ENTRANCES}
	 */
	public static List<SiegeEntrance> find(SiegeVillage village, Location heart, ZoneType town, List<int[]> sources)
	{
		final List<Crossing> crossings = new ArrayList<>();
		if (!village.getEntrances().isEmpty())
		{
			for (FixedEntrance entrance : village.getEntrances())
			{
				final Crossing crossing = straightCrossing(heart, entrance.getGate(), 1);
				crossing._fixed = true;
				crossing._rally = entrance.getRally();
				crossings.add(crossing);
			}
		}
		else
		{
			// The roads toward the busiest hunting grounds.
			final List<int[]> busiest = new ArrayList<>(sources);
			busiest.sort((a, b) -> Integer.compare(b[2], a[2]));
			for (int i = 0; (i < busiest.size()) && (i < MAX_SOURCES); i++)
			{
				final int[] source = busiest.get(i);
				final Crossing crossing = crossingToward(heart, town, Math.atan2(source[1] - heart.getY(), source[0] - heart.getX()), source[2]);
				if (crossing != null)
				{
					crossings.add(crossing);
				}
			}

			// No road found that way: the eight directions.
			if (crossings.isEmpty())
			{
				for (int direction = 0; direction < 8; direction++)
				{
					final Crossing crossing = crossingToward(heart, town, (direction * Math.PI) / 4, 1);
					if (crossing != null)
					{
						crossings.add(crossing);
					}
				}
			}
		}

		// Crossings close to each other are one entrance; the busiest are kept.
		crossings.sort((a, b) -> Integer.compare(b._weight, a._weight));
		final List<Crossing> entrances = new ArrayList<>();
		for (Crossing crossing : crossings)
		{
			Crossing same = null;
			for (Crossing entrance : entrances)
			{
				if (distance2D(entrance._point, crossing._point) < VillageSiegeConfig.ENTRANCE_SPACING)
				{
					same = entrance;
					break;
				}
			}
			if (same != null)
			{
				same._weight += crossing._weight;
			}
			else
			{
				entrances.add(crossing);
			}
		}
		entrances.sort((a, b) -> Integer.compare(b._weight, a._weight));

		final List<SiegeEntrance> result = new ArrayList<>();
		final Set<String> names = new HashSet<>();
		for (Crossing entrance : entrances)
		{
			if (result.size() >= VillageSiegeConfig.MAX_ENTRANCES)
			{
				break;
			}

			final Location gate = entrance._fixed ? entrance._point : ground(pointAlong(entrance._approach, GATE_OUTSIDE, true));
			Location rally = entrance._rally != null ? entrance._rally : ground(pointAlong(entrance._approach, VillageSiegeConfig.RALLY_DISTANCE, true));
			if (distance2D(rally, gate) < MIN_RALLY_DISTANCE)
			{
				rally = findOpenGround(gate, heart, town);
			}
			final Location guardCentre = ground(pointAlong(entrance._inside, VillageSiegeConfig.GUARD_LINE_DISTANCE, false));
			String name = DIRECTIONS[direction(heart, gate)] + " Gate";
			if (!names.add(name))
			{
				name = DIRECTIONS[direction(heart, gate)] + " Road";
				names.add(name);
			}
			result.add(new SiegeEntrance(name, gate, rally, guardLine(guardCentre, gate, Math.max(1, VillageSiegeConfig.GUARDS_PER_ENTRANCE)), entrance._weight));
		}
		return result;
	}

	/**
	 * Follows the ground from the heart of the village in a direction until it leaves the village, and finds the road that crosses there: a path from a point out on that side to a point just inside.
	 * @param heart the heart of the village
	 * @param town the TownZone
	 * @param angle the direction
	 * @param weight how many monsters live that way
	 * @return the crossing, {@code null} if no road could be found there
	 */
	private static Crossing crossingToward(Location heart, ZoneType town, double angle, int weight)
	{
		final GeoEngine geo = GeoEngine.getInstance();
		final double dx = Math.cos(angle);
		final double dy = Math.sin(angle);

		// The ray, step by step on the ground, until it is out of the village (and as far again as a probe may go).
		final List<Location> ray = new ArrayList<>();
		ray.add(heart);
		int border = -1;
		int z = heart.getZ();
		for (int step = 1; step <= ((MAX_RAY + PROBE_OUTSIDE[PROBE_OUTSIDE.length - 1]) / RAY_STEP); step++)
		{
			final int x = heart.getX() + (int) (dx * step * RAY_STEP);
			final int y = heart.getY() + (int) (dy * step * RAY_STEP);
			if (!geo.hasGeo(x, y))
			{
				break;
			}
			z = geo.getHeight(x, y, z);
			ray.add(new Location(x, y, z));
			if ((border < 0) && !isInside(town, x, y))
			{
				border = ray.size() - 1;
			}
			if ((border < 0) && ((step * RAY_STEP) > MAX_RAY))
			{
				return null;
			}
		}
		if (border < 0)
		{
			return null;
		}

		// A point just inside, and points out on that side: the first one a path joins is the road.
		final Location target = ray.get(Math.max(0, border - (TARGET_INSIDE / RAY_STEP)));
		for (int outside : PROBE_OUTSIDE)
		{
			final int index = border + (outside / RAY_STEP);
			if (index >= ray.size())
			{
				break;
			}
			final Location probe = ray.get(index);
			if (isInside(town, probe.getX(), probe.getY()))
			{
				continue;
			}
			final Crossing crossing = crossingOf(probe, target, heart, town, weight);
			if (crossing != null)
			{
				return crossing;
			}
		}
		return null;
	}

	/**
	 * @param from where the road starts, outside the village
	 * @param target a point just inside the village
	 * @param heart the heart of the village
	 * @param town the TownZone
	 * @param weight how many monsters live where the road starts
	 * @return where the path from {@code from} to {@code target} crosses into the village, {@code null} if there is no path or it doesn't
	 */
	private static Crossing crossingOf(Location from, Location target, Location heart, ZoneType town, int weight)
	{
		final List<GeoLocation> path = PathFinding.getInstance().findPath(from.getX(), from.getY(), from.getZ(), target.getX(), target.getY(), target.getZ(), 0, false);
		final List<Location> points = new ArrayList<>();
		points.add(from);
		if (path == null)
		{
			// A straight walk needs no path.
			if (!GeoEngine.getInstance().canMoveToTarget(from, target))
			{
				return null;
			}
			points.add(target);
		}
		else
		{
			for (GeoLocation node : path)
			{
				points.add(new Location(node.getX(), node.getY(), node.getZ()));
			}
		}

		for (int i = 1; i < points.size(); i++)
		{
			final Location before = points.get(i - 1);
			final Location after = points.get(i);
			if (!isInside(town, after.getX(), after.getY()) || isInside(town, before.getX(), before.getY()))
			{
				continue;
			}

			// The border, between the last node outside and the first inside.
			Location out = before;
			Location in = after;
			for (int step = 0; step < 10; step++)
			{
				final Location middle = new Location((out.getX() + in.getX()) / 2, (out.getY() + in.getY()) / 2, (out.getZ() + in.getZ()) / 2);
				if (isInside(town, middle.getX(), middle.getY()))
				{
					in = middle;
				}
				else
				{
					out = middle;
				}
			}
			final Location border = ground(out);

			// The road out, from the border back to where it started; the road in, from the border to the heart.
			final List<Location> approach = new ArrayList<>();
			approach.add(border);
			for (int j = i - 1; j >= 0; j--)
			{
				approach.add(points.get(j));
			}
			final List<Location> inside = new ArrayList<>();
			inside.add(border);
			for (int j = i; j < points.size(); j++)
			{
				inside.add(points.get(j));
			}
			inside.add(heart);
			return new Crossing(border, approach, inside, weight);
		}
		return null;
	}

	/**
	 * @param heart the heart of the village
	 * @param border where the road crosses into the village
	 * @param weight its weight
	 * @return a straight road through {@code border}, from the heart out
	 */
	private static Crossing straightCrossing(Location heart, Location border, int weight)
	{
		final double distance = Math.max(1, Math.hypot(border.getX() - heart.getX(), border.getY() - heart.getY()));
		final double dx = (border.getX() - heart.getX()) / distance;
		final double dy = (border.getY() - heart.getY()) / distance;
		final int far = VillageSiegeConfig.RALLY_DISTANCE + GATE_OUTSIDE + 200;
		final List<Location> approach = new ArrayList<>();
		approach.add(border);
		approach.add(new Location(border.getX() + (int) (dx * far), border.getY() + (int) (dy * far), border.getZ()));
		final List<Location> inside = new ArrayList<>();
		inside.add(border);
		inside.add(heart);
		return new Crossing(border, approach, inside, weight);
	}

	/**
	 * When the road out of a gate can't be followed in a straight line: open ground out from the gate, as straight out from the village as can be walked to.
	 * @param gate the gate
	 * @param heart the heart of the village
	 * @param town the TownZone
	 * @return the point, the gate itself if there is none
	 */
	private static Location findOpenGround(Location gate, Location heart, ZoneType town)
	{
		final GeoEngine geo = GeoEngine.getInstance();
		final double out = Math.atan2(gate.getY() - heart.getY(), gate.getX() - heart.getX());
		for (int distance = VillageSiegeConfig.RALLY_DISTANCE; distance >= MIN_RALLY_DISTANCE; distance -= 100)
		{
			for (int turn = 0; turn <= 6; turn++)
			{
				for (int side : new int[]
				{
					1,
					-1
				})
				{
					final double angle = out + (side * turn * (Math.PI / 12));
					final int x = gate.getX() + (int) (Math.cos(angle) * distance);
					final int y = gate.getY() + (int) (Math.sin(angle) * distance);
					final Location point = new Location(x, y, geo.getHeight(x, y, gate.getZ()));
					if (!isInside(town, x, y) && (Math.abs(point.getZ() - gate.getZ()) < 600) && geo.canMoveToTarget(gate, point))
					{
						return point;
					}
					if (turn == 0)
					{
						break;
					}
				}
			}
		}
		return gate;
	}

	/**
	 * @param line a line of points
	 * @param distance how far along it
	 * @param checkMove {@code true} to stop where the ground can't be walked any further
	 * @return the point that far along the line, or its end
	 */
	private static Location pointAlong(List<Location> line, int distance, boolean checkMove)
	{
		double left = distance;
		for (int i = 1; i < line.size(); i++)
		{
			final Location a = line.get(i - 1);
			final Location b = line.get(i);
			final double length = Math.hypot(b.getX() - a.getX(), b.getY() - a.getY());
			if (length >= left)
			{
				final double share = length < 1 ? 0 : left / length;
				final Location point = new Location(a.getX() + (int) ((b.getX() - a.getX()) * share), a.getY() + (int) ((b.getY() - a.getY()) * share), a.getZ() + (int) ((b.getZ() - a.getZ()) * share));
				if (checkMove && !GeoEngine.getInstance().canMoveToTarget(a, ground(point)))
				{
					return a;
				}
				return point;
			}
			left -= length;
		}
		return line.get(line.size() - 1);
	}

	/**
	 * @param centre the middle of the line
	 * @param gate the gate the line faces
	 * @param count how many guards
	 * @return the posts of a line of guards across the road, facing the gate
	 */
	private static List<Location> guardLine(Location centre, Location gate, int count)
	{
		final double distance = Math.max(1, Math.hypot(gate.getX() - centre.getX(), gate.getY() - centre.getY()));
		// Across the road.
		final double ax = -(gate.getY() - centre.getY()) / distance;
		final double ay = (gate.getX() - centre.getX()) / distance;
		final List<Location> posts = new ArrayList<>(count);
		final GeoEngine geo = GeoEngine.getInstance();
		for (int i = 0; i < count; i++)
		{
			final double offset = (i - ((count - 1) / 2.0)) * GUARD_SPACING;
			final Location post = ground(new Location(centre.getX() + (int) (ax * offset), centre.getY() + (int) (ay * offset), centre.getZ()));
			posts.add(geo.canMoveToTarget(centre, post) ? post : centre);
		}
		return posts;
	}

	/**
	 * @param location a point
	 * @return the point on the ground
	 */
	private static Location ground(Location location)
	{
		return new Location(location.getX(), location.getY(), GeoEngine.getInstance().getHeight(location.getX(), location.getY(), location.getZ()));
	}

	/**
	 * @param town the TownZone
	 * @param x the x
	 * @param y the y
	 * @return {@code true} if the point is in the village, whatever its height
	 */
	public static boolean isInside(ZoneType town, int x, int y)
	{
		return town.getZone().isInsideZone(x, y, (town.getZone().getLowZ() + town.getZone().getHighZ()) / 2);
	}

	/**
	 * @param heart the heart of the village
	 * @param point a point
	 * @return the index of the compass direction of the point from the heart in {@link #DIRECTIONS}
	 */
	private static int direction(Location heart, Location point)
	{
		// The y axis points south.
		final double angle = Math.toDegrees(Math.atan2(point.getY() - heart.getY(), point.getX() - heart.getX()));
		return (int) Math.floorMod(Math.round(angle / 45), 8);
	}

	private static double distance2D(Location a, Location b)
	{
		return Math.hypot(a.getX() - b.getX(), a.getY() - b.getY());
	}

	/**
	 * @param heart the heart of the village
	 * @param town the TownZone, to leave its monsters out ({@code null} to keep them)
	 * @return the level of a siege of the village: the average level of the monsters living around it, around their median level, at most {@link VillageSiegeConfig#MAX_PLAYER_LEVEL}
	 */
	public static int findLevel(Location heart, ZoneType town)
	{
		final List<Integer> levels = new ArrayList<>();
		WorldMonsterIndex.getInstance().forEachPointNear(heart.getX(), heart.getY(), VillageSiegeConfig.SOURCE_RANGE, (npcId, level, x, y, z) ->
		{
			if ((town == null) || !isInside(town, x, y))
			{
				levels.add(level);
			}
		});
		if (levels.isEmpty())
		{
			return 1;
		}

		Collections.sort(levels);
		final int median = levels.get(levels.size() / 2);
		long sum = 0;
		int count = 0;
		for (int level : levels)
		{
			if (Math.abs(level - median) <= 8)
			{
				sum += level;
				count++;
			}
		}
		final int average = (int) Math.round((double) sum / Math.max(1, count));
		return Math.max(1, Math.min(VillageSiegeConfig.MAX_PLAYER_LEVEL, average));
	}
}
