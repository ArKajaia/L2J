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
package org.l2jmobius.gameserver.model.conquest;

import java.util.ArrayList;
import java.util.List;

/**
 * A battlefield of the Conquest event (data/conquest/&lt;id&gt;.xml): its area, the spawn points of each team and the capture points. One of the enabled maps is picked by chance for each event.<br>
 * Immutable: an edit makes a new map, which {@link org.l2jmobius.gameserver.data.xml.ConquestMapData#save} writes and puts in place.
 * @param id the file name, lower case letters, digits and _
 * @param name the name shown to the players
 * @param enabled {@code true} if the event may pick it
 * @param maxPlayers how many players a team takes at most on this map (a small map takes fewer)
 * @param description a few words for the GMs (where it is, what it plays like)
 * @param minX the west edge of the battlefield
 * @param minY the north edge
 * @param maxX the east edge
 * @param maxY the south edge
 * @param z the height of the battlefield's ground: where it has several layers (bridges, caves), the one nearest this height is the battlefield
 * @param points the spawn points of both teams and the capture points, in file order
 * @param doors the doors the event opens or closes when it starts, by id
 */
public record ConquestMap(String id, String name, boolean enabled, int maxPlayers, String description, int minX, int minY, int maxX, int maxY, int z, List<ConquestPoint> points, List<ConquestDoor> doors)
{
	public ConquestMap
	{
		points = List.copyOf(points);
		doors = List.copyOf(doors);
	}

	/**
	 * @param type blue, red or capture
	 * @return the points of that type, in file order
	 */
	public List<ConquestPoint> getPoints(ConquestPointType type)
	{
		final List<ConquestPoint> result = new ArrayList<>();
		for (ConquestPoint point : points)
		{
			if (point.type() == type)
			{
				result.add(point);
			}
		}
		return result;
	}

	/**
	 * @param type blue, red or capture
	 * @param key the number of the point (1 for the first of its type), or a capture point's name, any case
	 * @return the point, {@code null} if there is none
	 */
	public ConquestPoint findPoint(ConquestPointType type, String key)
	{
		final List<ConquestPoint> list = getPoints(type);
		for (ConquestPoint point : list)
		{
			if (!point.name().isEmpty() && point.name().equalsIgnoreCase(key))
			{
				return point;
			}
		}
		try
		{
			final int index = Integer.parseInt(key.trim()) - 1;
			return (index >= 0) && (index < list.size()) ? list.get(index) : null;
		}
		catch (NumberFormatException e)
		{
			return null;
		}
	}

	/**
	 * @param point a point of this map
	 * @return what it is called in game: "Blue spawn 2", "Capture A"
	 */
	public String label(ConquestPoint point)
	{
		return switch (point.type())
		{
			case BLUE -> "Blue spawn " + (getPoints(ConquestPointType.BLUE).indexOf(point) + 1);
			case RED -> "Red spawn " + (getPoints(ConquestPointType.RED).indexOf(point) + 1);
			case CAPTURE -> "Capture " + (point.name().isEmpty() ? String.valueOf(getPoints(ConquestPointType.CAPTURE).indexOf(point) + 1) : point.name());
		};
	}

	/**
	 * @param newPoints the new points
	 * @return this map with those points instead
	 */
	public ConquestMap withPoints(List<ConquestPoint> newPoints)
	{
		return new ConquestMap(id, name, enabled, maxPlayers, description, minX, minY, maxX, maxY, z, newPoints, doors);
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
