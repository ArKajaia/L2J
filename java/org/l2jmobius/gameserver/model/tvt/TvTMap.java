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
package org.l2jmobius.gameserver.model.tvt;

import java.util.ArrayList;
import java.util.List;

import org.l2jmobius.gameserver.model.Location;

/**
 * A battlefield of the Team vs Team event (data/tvt/&lt;id&gt;.xml): its area, the spawn points (bases) of each team, the fence round the field and the doors. A registration picks one of the enabled maps, and every match of it is played in its own copy
 * (an instance) of that place.<br>
 * Immutable: an edit makes a new map, which {@link org.l2jmobius.gameserver.data.xml.TvTMapData#save} writes and puts in place.
 * @param id the file name, lower case letters, digits and _
 * @param name the name shown to the players
 * @param enabled {@code true} if the event may pick it
 * @param description a few words for the GMs (where it is, what it plays like)
 * @param minX the west edge of the battlefield (the edge on the players' radar)
 * @param minY the north edge
 * @param maxX the east edge
 * @param maxY the south edge
 * @param z the height of the battlefield's ground: where it has several layers (bridges, caves), the one nearest this height is the battlefield
 * @param blue the spawn points of Blue (x, y, z, heading): it starts at the first one, its dead come back at one of them
 * @param red the spawn points of Red
 * @param barrier the fence round the field, {@code null} for none
 * @param doors the doors a match opens or shuts, by id
 */
public record TvTMap(String id, String name, boolean enabled, String description, int minX, int minY, int maxX, int maxY, int z, List<Location> blue, List<Location> red, TvTBarrier barrier, List<TvTDoor> doors)
{
	public TvTMap
	{
		blue = copy(blue);
		red = copy(red);
		doors = List.copyOf(doors);
	}

	private static List<Location> copy(List<Location> locations)
	{
		final List<Location> result = new ArrayList<>(locations.size());
		for (Location location : locations)
		{
			result.add(new Location(location.getX(), location.getY(), location.getZ(), location.getHeading()));
		}
		return List.copyOf(result);
	}

	/**
	 * @param team {@code true} for Blue, {@code false} for Red
	 * @return the spawn points of that team
	 */
	public List<Location> getSpawns(boolean team)
	{
		return team ? blue : red;
	}

	/**
	 * @return {@code true} if both teams have a spawn point (the event may play it, when it is enabled)
	 */
	public boolean isComplete()
	{
		return !blue.isEmpty() && !red.isEmpty();
	}

	/**
	 * @param newBlue the new spawn points of Blue
	 * @param newRed the new spawn points of Red
	 * @return this map with those spawn points instead
	 */
	public TvTMap withSpawns(List<Location> newBlue, List<Location> newRed)
	{
		return new TvTMap(id, name, enabled, description, minX, minY, maxX, maxY, z, newBlue, newRed, barrier, doors);
	}

	/**
	 * @return the middle of the battlefield, {x, y}
	 */
	public int[] getCenter()
	{
		return new int[]
		{
			(minX + maxX) / 2,
			(minY + maxY) / 2
		};
	}
}
