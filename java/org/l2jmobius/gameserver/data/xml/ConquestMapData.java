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
package org.l2jmobius.gameserver.data.xml;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import org.l2jmobius.commons.util.Rnd;
import org.l2jmobius.gameserver.config.custom.ConquestConfig;
import org.l2jmobius.gameserver.model.Location;
import org.l2jmobius.gameserver.model.conquest.ConquestMap;
import org.l2jmobius.gameserver.model.conquest.ConquestPoint;
import org.l2jmobius.gameserver.model.eventmap.EventMap;
import org.l2jmobius.gameserver.model.eventmap.EventMapType;

/**
 * The battlefields of the Conquest event, as Conquest sees the shared event maps ({@link EventMapData}, data/maps): each map with its Conquest points, {@link ConquestMap#enabled} when it is ticked for Conquest. GMs edit them on the event map page (//conquest),
 * or with //conquest set and remove, which save the Conquest points back into the shared map.
 */
public class ConquestMapData
{
	/** At most so many points of one type on a map. */
	public static final int MAX_POINTS = EventMapData.MAX_CONQUEST_POINTS;
	/** How many players a team takes on a map that doesn't say (maxPlayers), and the bounds of what a map may say. */
	public static final int DEFAULT_MAX_PLAYERS = EventMapData.DEFAULT_MAX_PLAYERS;
	public static final int MIN_PLAYERS = EventMapData.MIN_PLAYERS;
	public static final int MAX_PLAYERS = EventMapData.MAX_PLAYERS;

	protected ConquestMapData()
	{
		EventMapData.getInstance();
	}

	/**
	 * Reads the event maps again (every event's).
	 */
	public void load()
	{
		EventMapData.getInstance().load();
	}

	/**
	 * Saves a map's Conquest points, players per team and tick into its event map (a new event map for a new id); the area, fence, doors and the other events' points stay as they are.
	 * @param map the map as Conquest edited it
	 * @throws IOException if it can't be written
	 */
	public void save(ConquestMap map) throws IOException
	{
		final EventMapData data = EventMapData.getInstance();
		final EventMap existing = data.getMap(map.id());
		final EventMap base = existing != null ? existing : new EventMap(map.id(), map.name(), map.description(), map.minX(), map.minY(), map.maxX(), map.maxY(), map.z(), null, List.of(), Set.of(), List.of(), List.of(), map.maxPlayers(), List.of(), List.of());
		data.save(base.withConquest(map));
	}

	/**
	 * @param points some points
	 * @return their average height, 0 without any
	 */
	public static int averageZ(List<ConquestPoint> points)
	{
		final List<Location> locations = new ArrayList<>(points.size());
		for (ConquestPoint point : points)
		{
			locations.add(new Location(point.x(), point.y(), point.z()));
		}
		return EventMapData.averageZ(locations);
	}

	/**
	 * @param id a map's id
	 * @return {@code true} if it can be a file name
	 */
	public static boolean isValidId(String id)
	{
		return EventMapData.isValidId(id);
	}

	/**
	 * @param id a map's id
	 * @return the map as Conquest sees it (any event map: one not ticked for Conquest is disabled), {@code null} if there is none
	 */
	public ConquestMap getMap(String id)
	{
		final EventMap map = EventMapData.getInstance().getMap(id);
		return map != null ? map.toConquestMap() : null;
	}

	/**
	 * @return the Conquest maps: ticked for Conquest or with Conquest points, by id
	 */
	public List<ConquestMap> getMaps()
	{
		final List<ConquestMap> result = new ArrayList<>();
		for (EventMap map : EventMapData.getInstance().getMaps(EventMapType.CONQUEST))
		{
			result.add(map.toConquestMap());
		}
		return result;
	}

	/**
	 * @return the maps the event may pick: ticked for Conquest, with spawn points for both teams and at least one capture point
	 */
	public List<ConquestMap> getEnabledMaps()
	{
		final List<ConquestMap> result = new ArrayList<>();
		for (EventMap map : EventMapData.getInstance().getPlayableMaps(EventMapType.CONQUEST))
		{
			result.add(map.toConquestMap());
		}
		return result;
	}

	/**
	 * @return one of the enabled maps of {@link ConquestConfig#MAPS} by chance, {@code null} if there is none
	 */
	public ConquestMap getRandomMap()
	{
		final List<ConquestMap> maps = getEnabledMaps();
		if (!ConquestConfig.MAPS.isEmpty())
		{
			maps.removeIf(map -> !ConquestConfig.MAPS.contains(map.id()));
		}
		return maps.isEmpty() ? null : maps.get(Rnd.get(maps.size()));
	}

	public static ConquestMapData getInstance()
	{
		return SingletonHolder.INSTANCE;
	}

	private static class SingletonHolder
	{
		protected static final ConquestMapData INSTANCE = new ConquestMapData();
	}
}
