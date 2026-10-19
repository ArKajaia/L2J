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
import org.l2jmobius.gameserver.model.zone.ZoneType;

/**
 * Finds the entrances of a village: the roads the monsters of the hunting grounds around it take into it.
 * <p>
 * The monster spawn points around the village are grouped into hunting grounds. From each ground a path is found to the heart of the village (its gatekeeper), and where the path crosses into the village's TownZone is an entrance. Crossings close to each other are one
 * entrance, and the entrances the most monsters come through are kept. The gate standard stands a little outside the crossing, the waves form further out on the same road, and the guards stand on the road behind the gate, inside the village.
 * </p>
 * Pathfinding only reaches about 3,500 units, so a path starts at most {@link #PROBE_DISTANCE} from the heart, on the line to its ground.
 */
public final class SiegeEntranceFinder
{
	/** Spawn points are grouped into hunting grounds of this size. */
	private static final int CELL_SIZE = 1500;
	/** Most hunting grounds a path is tried from. */
	private static final int MAX_SOURCES = 16;
	/** A path starts at most this far from the heart of the village (pathfinding reaches about 3,500). */
	private static final int PROBE_DISTANCE = 3300;
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
		final List<Crossing> crossings = new ArrayList<>();
		if (!village.getEntrances().isEmpty())
		{
			for (Location gate : village.getEntrances())
			{
				crossings.add(straightCrossing(heart, gate, 1));
			}
		}
		else
		{
			crossings.addAll(findCrossings(heart, town, level));
			if (crossings.isEmpty())
			{
				crossings.addAll(findRayCrossings(heart, town));
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

			final Location gate = ground(pointAlong(entrance._approach, GATE_OUTSIDE, true));
			final Location rally = ground(pointAlong(entrance._approach, VillageSiegeConfig.RALLY_DISTANCE, true));
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
	 * @param heart the heart of the village
	 * @param town the TownZone
	 * @param level the level of the siege's monsters
	 * @return where the roads from the hunting grounds around cross into the village
	 */
	private static List<Crossing> findCrossings(Location heart, ZoneType town, int level)
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
			final long[] cell = cells.computeIfAbsent(key, k -> new long[4]);
			cell[0]++;
			cell[1] += x;
			cell[2] += y;
			cell[3] += z;
		});

		final List<long[]> sources = new ArrayList<>(cells.values());
		sources.sort((a, b) -> Long.compare(b[0], a[0]));

		final List<Crossing> crossings = new ArrayList<>();
		final GeoEngine geo = GeoEngine.getInstance();
		for (int i = 0; (i < sources.size()) && (i < MAX_SOURCES); i++)
		{
			final long[] cell = sources.get(i);
			final int x = (int) (cell[1] / cell[0]);
			final int y = (int) (cell[2] / cell[0]);
			final int z = (int) (cell[3] / cell[0]);
			final double distance = Math.hypot(x - heart.getX(), y - heart.getY());
			if (distance < 1)
			{
				continue;
			}

			// The path starts on the line to the ground, within pathfinding's reach.
			for (double share : new double[]
			{
				1.0,
				0.8,
				0.6
			})
			{
				final double reach = Math.min(distance, PROBE_DISTANCE) * share;
				final int px = heart.getX() + (int) (((x - heart.getX()) * reach) / distance);
				final int py = heart.getY() + (int) (((y - heart.getY()) * reach) / distance);
				if (isInside(town, px, py) || !geo.hasGeo(px, py))
				{
					continue;
				}
				final Location probe = new Location(px, py, geo.getHeight(px, py, reach >= distance ? z : heart.getZ()));
				final Crossing crossing = crossingOf(probe, heart, town, (int) cell[0]);
				if (crossing != null)
				{
					crossings.add(crossing);
					break;
				}
			}
		}
		return crossings;
	}

	/**
	 * @param from where the road starts, outside the village
	 * @param heart the heart of the village
	 * @param town the TownZone
	 * @param weight how many monsters live where the road starts
	 * @return where the path from {@code from} to the heart crosses into the village, {@code null} if there is no path or it doesn't
	 */
	private static Crossing crossingOf(Location from, Location heart, ZoneType town, int weight)
	{
		final List<GeoLocation> path = PathFinding.getInstance().findPath(from.getX(), from.getY(), from.getZ(), heart.getX(), heart.getY(), heart.getZ(), 0, false);
		if ((path == null) || path.isEmpty())
		{
			return null;
		}

		final List<Location> points = new ArrayList<>(path.size() + 1);
		points.add(from);
		for (GeoLocation node : path)
		{
			points.add(new Location(node.getX(), node.getY(), node.getZ()));
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
			return new Crossing(border, approach, inside, weight);
		}
		return null;
	}

	/**
	 * When no road could be found: rays from the heart in eight directions, where they leave the village.
	 * @param heart the heart of the village
	 * @param town the TownZone
	 * @return the crossings
	 */
	private static List<Crossing> findRayCrossings(Location heart, ZoneType town)
	{
		final List<Crossing> crossings = new ArrayList<>();
		final GeoEngine geo = GeoEngine.getInstance();
		for (int direction = 0; direction < 8; direction++)
		{
			final double angle = (direction * Math.PI) / 4;
			for (int distance = 200; distance <= 5000; distance += 100)
			{
				final int x = heart.getX() + (int) (Math.cos(angle) * distance);
				final int y = heart.getY() + (int) (Math.sin(angle) * distance);
				if (!isInside(town, x, y))
				{
					final Location border = new Location(x, y, geo.getHeight(x, y, heart.getZ()));
					if (geo.canMoveToTarget(heart, border))
					{
						crossings.add(straightCrossing(heart, border, 1));
					}
					break;
				}
			}
		}
		return crossings;
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
