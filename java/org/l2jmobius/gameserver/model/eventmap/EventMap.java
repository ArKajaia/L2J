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
package org.l2jmobius.gameserver.model.eventmap;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

import org.l2jmobius.gameserver.model.Location;
import org.l2jmobius.gameserver.model.conquest.ConquestDoor;
import org.l2jmobius.gameserver.model.conquest.ConquestMap;
import org.l2jmobius.gameserver.model.conquest.ConquestPoint;
import org.l2jmobius.gameserver.model.conquest.ConquestPointType;

/**
 * A battlefield of the PvP events (data/maps/&lt;id&gt;.xml), shared by Team vs Team, Conquest and Deathmatch: one place, with its area, its fence and its doors, used by each event it is ticked for, with that event's own points.<br>
 * Immutable: an edit makes a new map, which {@link org.l2jmobius.gameserver.data.xml.EventMapData#save} writes and puts in place.
 * @param id the file name, lower case letters, digits and _
 * @param name the name shown to the players
 * @param description a few words for the GMs (where it is, what it plays like)
 * @param minX the west edge of the battlefield (the edge on the players' radar)
 * @param minY the north edge
 * @param maxX the east edge
 * @param maxY the south edge
 * @param z the height of the battlefield's ground: where it has several layers (bridges, caves), the one nearest this height is the battlefield
 * @param barrier the fence round the field (Team vs Team and Deathmatch), {@code null} for none
 * @param doors the doors a match puts in its copy of the place, open or shut
 * @param uses the events it is ticked for: an event only picks a map ticked for it (and with the points it needs)
 * @param blue Team vs Team: the bases of Blue (x, y, z, heading); it starts at the first one, its dead come back at one of them
 * @param red Team vs Team: the bases of Red
 * @param conquestMaxPlayers Conquest: how many players a team takes at most
 * @param conquestPoints Conquest: the spawn points of both teams and the capture points, in file order
 * @param deathmatch Deathmatch: the spots everyone spawns at (x, y, z, heading)
 */
public record EventMap(String id, String name, String description, int minX, int minY, int maxX, int maxY, int z, EventBarrier barrier, List<EventDoor> doors, Set<EventMapType> uses, List<Location> blue, List<Location> red, int conquestMaxPlayers, List<ConquestPoint> conquestPoints, List<Location> deathmatch)
{
	/** A Deathmatch needs at least so many spawn spots. */
	public static final int MIN_DEATHMATCH_SPAWNS = 2;

	public EventMap
	{
		doors = List.copyOf(doors);
		uses = uses.isEmpty() ? Set.of() : Set.copyOf(EnumSet.copyOf(uses));
		blue = copy(blue);
		red = copy(red);
		conquestPoints = List.copyOf(conquestPoints);
		deathmatch = copy(deathmatch);
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
	 * @param type an event
	 * @return {@code true} if the map is ticked for it
	 */
	public boolean isUsedBy(EventMapType type)
	{
		return uses.contains(type);
	}

	/**
	 * @param type an event
	 * @return {@code true} if the map has the points that event needs: bases for both teams (Team vs Team), spawn points for both teams and a capture point (Conquest), {@link #MIN_DEATHMATCH_SPAWNS} spawn spots (Deathmatch)
	 */
	public boolean isComplete(EventMapType type)
	{
		return switch (type)
		{
			case TVT -> !blue.isEmpty() && !red.isEmpty();
			case CONQUEST -> hasConquestPoints(ConquestPointType.BLUE) && hasConquestPoints(ConquestPointType.RED) && hasConquestPoints(ConquestPointType.CAPTURE);
			case DEATHMATCH -> deathmatch.size() >= MIN_DEATHMATCH_SPAWNS;
		};
	}

	/**
	 * @param type an event
	 * @return {@code true} if that event may pick it: ticked for it, with the points it needs
	 */
	public boolean isPlayable(EventMapType type)
	{
		return isUsedBy(type) && isComplete(type);
	}

	/**
	 * @param type an event
	 * @return {@code true} if it is ticked for that event or has some of its points (a map of that event, maybe not ready or switched off)
	 */
	public boolean belongsTo(EventMapType type)
	{
		return isUsedBy(type) || switch (type)
		{
			case TVT -> !blue.isEmpty() || !red.isEmpty();
			case CONQUEST -> !conquestPoints.isEmpty();
			case DEATHMATCH -> !deathmatch.isEmpty();
		};
	}

	private boolean hasConquestPoints(ConquestPointType type)
	{
		for (ConquestPoint point : conquestPoints)
		{
			if (point.type() == type)
			{
				return true;
			}
		}
		return false;
	}

	/**
	 * @param team {@code true} for Blue, {@code false} for Red
	 * @return the Team vs Team bases of that team
	 */
	public List<Location> getSpawns(boolean team)
	{
		return team ? blue : red;
	}

	/**
	 * @return the map as Conquest sees it: its Conquest points, ticked or not for Conquest ({@link ConquestMap#enabled})
	 */
	public ConquestMap toConquestMap()
	{
		final List<ConquestDoor> conquestDoors = new ArrayList<>(doors.size());
		for (EventDoor door : doors)
		{
			conquestDoors.add(new ConquestDoor(door.id(), door.open()));
		}
		return new ConquestMap(id, name, isUsedBy(EventMapType.CONQUEST), conquestMaxPlayers, description, minX, minY, maxX, maxY, z, conquestPoints, conquestDoors);
	}

	/**
	 * @param conquest the map as Conquest edited it
	 * @return this map with its Conquest points, players per team and tick taken from it (the rest is shared and stays as it is)
	 */
	public EventMap withConquest(ConquestMap conquest)
	{
		final Set<EventMapType> newUses = EnumSet.noneOf(EventMapType.class);
		newUses.addAll(uses);
		if (conquest.enabled())
		{
			newUses.add(EventMapType.CONQUEST);
		}
		else
		{
			newUses.remove(EventMapType.CONQUEST);
		}
		return new EventMap(id, name, description, minX, minY, maxX, maxY, z, barrier, doors, newUses, blue, red, conquest.maxPlayers(), conquest.points(), deathmatch);
	}

	/**
	 * @param newBlue the new bases of Blue
	 * @param newRed the new bases of Red
	 * @return this map with those Team vs Team bases instead
	 */
	public EventMap withSpawns(List<Location> newBlue, List<Location> newRed)
	{
		return new EventMap(id, name, description, minX, minY, maxX, maxY, z, barrier, doors, uses, newBlue, newRed, conquestMaxPlayers, conquestPoints, deathmatch);
	}

	/**
	 * @param spawns the new Deathmatch spawn spots
	 * @return this map with them instead
	 */
	public EventMap withDeathmatch(List<Location> spawns)
	{
		return new EventMap(id, name, description, minX, minY, maxX, maxY, z, barrier, doors, uses, blue, red, conquestMaxPlayers, conquestPoints, spawns);
	}

	/**
	 * @param newBarrier the new fence, {@code null} for none
	 * @return this map with it instead
	 */
	public EventMap withBarrier(EventBarrier newBarrier)
	{
		return new EventMap(id, name, description, minX, minY, maxX, maxY, z, newBarrier, doors, uses, blue, red, conquestMaxPlayers, conquestPoints, deathmatch);
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
