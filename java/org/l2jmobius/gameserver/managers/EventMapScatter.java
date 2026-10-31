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

import org.l2jmobius.commons.util.Rnd;
import org.l2jmobius.gameserver.geoengine.GeoEngine;
import org.l2jmobius.gameserver.geoengine.geodata.Cell;
import org.l2jmobius.gameserver.geoengine.pathfinding.PathFinding;
import org.l2jmobius.gameserver.model.Location;
import org.l2jmobius.gameserver.model.World;
import org.l2jmobius.gameserver.model.conquest.ConquestPoint;
import org.l2jmobius.gameserver.model.eventmap.EventBarrier;
import org.l2jmobius.gameserver.model.eventmap.EventMap;

/**
 * Spots of an event map picked by chance, for the event map page's "scatter" of Deathmatch spawns: open ground of the battlefield (a player can walk off it in every direction), inside its fence with room to spare and on its level, apart from each other and
 * from the map's Deathmatch spawns, from which a player can walk to one of the map's other points (so none is on a roof or a ledge it can't leave).
 */
public final class EventMapScatter
{
	/** Of so many tries at most. */
	private static final int TRIES = 1500;
	/** This far inside the fence. */
	private static final int FENCE_MARGIN = 400;
	/** At most this far above or below the map's ground. */
	private static final int LEVEL = 600;

	private EventMapScatter()
	{
	}

	/**
	 * @param map the map as it is now (saved or not)
	 * @param count how many spots
	 * @param gap how far apart they are at least (and from the map's Deathmatch spawns)
	 * @return the spots, facing the middle of the area: fewer than {@code count} if the field has no room for more
	 */
	public static List<Location> scatter(EventMap map, int count, int gap)
	{
		final GeoEngine geo = GeoEngine.getInstance();
		final List<Location> anchors = new ArrayList<>(map.blue());
		anchors.addAll(map.red());
		anchors.addAll(map.deathmatch());
		for (ConquestPoint point : map.conquestPoints())
		{
			anchors.add(new Location(point.x(), point.y(), point.z()));
		}

		final List<Location> taken = new ArrayList<>(map.deathmatch());
		final List<Location> spots = new ArrayList<>();
		final EventBarrier barrier = map.barrier();
		final int minX = Math.max(World.WORLD_X_MIN, barrier != null ? Math.max(map.minX(), barrier.x() - barrier.radiusX()) : map.minX());
		final int maxX = Math.min(World.WORLD_X_MAX - 1, barrier != null ? Math.min(map.maxX(), barrier.x() + barrier.radiusX()) : map.maxX());
		final int minY = Math.max(World.WORLD_Y_MIN, barrier != null ? Math.max(map.minY(), barrier.y() - barrier.radiusY()) : map.minY());
		final int maxY = Math.min(World.WORLD_Y_MAX - 1, barrier != null ? Math.min(map.maxY(), barrier.y() + barrier.radiusY()) : map.maxY());
		if ((maxX <= minX) || (maxY <= minY))
		{
			return spots;
		}

		final int[] center = map.getCenter();
		for (int tries = 0; (tries < TRIES) && (spots.size() < count); tries++)
		{
			final int x = Rnd.get(minX, maxX);
			final int y = Rnd.get(minY, maxY);
			if (((barrier != null) && !barrier.contains(x, y, -Math.min(FENCE_MARGIN, Math.min(barrier.radiusX(), barrier.radiusY()) - 1))) || !geo.hasGeo(x, y))
			{
				continue;
			}
			final int z = geo.getHeight(x, y, map.z());
			if ((Math.abs(z - map.z()) > LEVEL) || !geo.checkNearestNswe(GeoEngine.getGeoX(x), GeoEngine.getGeoY(y), z, Cell.NSWE_ALL))
			{
				continue;
			}

			// Apart from the others, and a way from it to the closest point of the map (the first spot is that point for the others, on a map without any).
			boolean crowded = false;
			for (Location spot : taken)
			{
				if (Math.hypot(spot.getX() - x, spot.getY() - y) < gap)
				{
					crowded = true;
					break;
				}
			}
			if (crowded)
			{
				continue;
			}
			Location anchor = null;
			for (Location point : anchors)
			{
				if ((anchor == null) || (Math.hypot(point.getX() - x, point.getY() - y) < Math.hypot(anchor.getX() - x, anchor.getY() - y)))
				{
					anchor = point;
				}
			}
			if ((anchor != null) && !geo.canMoveToTarget(x, y, z, anchor.getX(), anchor.getY(), anchor.getZ(), 0) && (PathFinding.getInstance().findPath(x, y, z, anchor.getX(), anchor.getY(), anchor.getZ(), 0, false) == null))
			{
				continue;
			}

			final double angle = Math.atan2(center[1] - y, center[0] - x);
			final Location spot = new Location(x, y, z, ((int) Math.round((angle * 65536) / (2 * Math.PI)) + 65536) % 65536);
			spots.add(spot);
			taken.add(spot);
			if (anchors.isEmpty())
			{
				anchors.add(spot);
			}
		}
		return spots;
	}
}
