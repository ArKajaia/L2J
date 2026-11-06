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
package org.l2jmobius.gameserver.model.mapdevice;

import java.util.List;

import org.l2jmobius.gameserver.model.Location;
import org.l2jmobius.gameserver.model.eventmap.EventBarrier;
import org.l2jmobius.gameserver.model.eventmap.EventMap;
import org.l2jmobius.gameserver.model.eventmap.EventMapDevice;

/**
 * A place a map can be opened in: an open field that a map run copies into its own instance and closes with a fence, like the field of a Team vs Team match. It comes from data/MapDevice.xml (an {@code <arena>}), or from the event map page (a map of data/maps ticked for
 * the Map Device), which can also say where the monsters and the Map Guardian stand, which tiers and monsters it takes, and which doors its copy of the place has.
 * @param id where it comes from, for the logs: "xml:&lt;name&gt;" or the event map's id
 * @param name the name shown to the players
 * @param x the fence's middle x
 * @param y the fence's middle y
 * @param z the height of the ground
 * @param radiusX the fence's radius along x
 * @param radiusY the fence's radius along y
 * @param entry where the players come in, facing into the field; the Return Portal stands there
 * @param guardian where the Map Guardian stands, {@code null} for the middle
 * @param packs the spots the monsters stand round, empty to spread them over the field
 * @param minTier the lowest tier that may open here, 0 for any
 * @param maxTier the highest tier that may open here, 0 for any
 * @param monsterCount how many monsters before the modifiers, 0 for the server's setting
 * @param monsters the npc ids the map is filled with, empty for monsters of the tier's levels
 * @param source the event map it comes from (its doors go in the map's copy of the place), {@code null} for an arena of data/MapDevice.xml
 */
public record MapArena(String id, String name, int x, int y, int z, int radiusX, int radiusY, Location entry, Location guardian, List<EventMapDevice.Pack> packs, int minTier, int maxTier, int monsterCount, List<Integer> monsters, EventMap source)
{
	/** How far inside the fence the entry of an arena of data/MapDevice.xml lies, as a share of the radius. */
	public static final double ENTRY_SHARE = 0.80;

	public MapArena
	{
		packs = List.copyOf(packs);
		monsters = List.copyOf(monsters);
	}

	/**
	 * An arena of data/MapDevice.xml: the entry lies at {@link #ENTRY_SHARE} of the radius in the given direction, facing the middle.
	 * @param name the name shown to the players
	 * @param x the middle's x
	 * @param y the middle's y
	 * @param z the height of the ground
	 * @param radiusX the fence's radius along x
	 * @param radiusY the fence's radius along y
	 * @param entryAngle where the players come in, in degrees round the middle (0 = east, 90 = south)
	 * @param minTier the lowest tier that may open here, 0 for any
	 * @param maxTier the highest tier that may open here, 0 for any
	 * @return the arena
	 */
	public static MapArena of(String name, int x, int y, int z, int radiusX, int radiusY, int entryAngle, int minTier, int maxTier)
	{
		final int entryX = x + (int) Math.round(Math.cos(Math.toRadians(entryAngle)) * radiusX * ENTRY_SHARE);
		final int entryY = y + (int) Math.round(Math.sin(Math.toRadians(entryAngle)) * radiusY * ENTRY_SHARE);
		final int heading = (int) Math.round((Math.atan2(y - entryY, x - entryX) * 32768) / Math.PI) & 0xFFFF;
		return new MapArena("xml:" + name, name, x, y, z, radiusX, radiusY, new Location(entryX, entryY, z, heading), null, List.of(), minTier, maxTier, 0, List.of(), null);
	}

	/**
	 * An arena from the event map page.
	 * @param map a map ticked for the Map Device
	 * @return the arena, {@code null} if the map lacks the fence or the entry
	 */
	public static MapArena of(EventMap map)
	{
		final EventBarrier barrier = map.barrier();
		final EventMapDevice device = map.mapDevice();
		if ((barrier == null) || (device.entry() == null))
		{
			return null;
		}
		return new MapArena(map.id(), map.name(), barrier.x(), barrier.y(), map.z(), barrier.radiusX(), barrier.radiusY(), device.entry(), device.guardian(), device.packs(), device.minTier(), device.maxTier(), device.monsterCount(), device.monsters(), map);
	}

	/**
	 * A smaller copy of this field, round the same middle: the fence's radii times {@code factor}, and the entry pulled in towards the middle by as much (same heading).
	 * Everything placed as a share of the radius (the monsters, a twist's totems, reliquaries, phylacteries, eggs) comes in with it.
	 * @param factor the share of the radius to keep (0-1); 1 or more gives this arena back
	 * @return the smaller field, under the same id and name
	 */
	public MapArena scaled(double factor)
	{
		if ((factor >= 1) || (factor <= 0))
		{
			return this;
		}
		final Location scaledEntry = new Location(x + (int) Math.round((entry.getX() - x) * factor), y + (int) Math.round((entry.getY() - y) * factor), entry.getZ(), entry.getHeading());
		final Location scaledGuardian = guardian == null ? null : new Location(x + (int) Math.round((guardian.getX() - x) * factor), y + (int) Math.round((guardian.getY() - y) * factor), guardian.getZ(), guardian.getHeading());
		return new MapArena(id, name, x, y, z, (int) Math.round(radiusX * factor), (int) Math.round(radiusY * factor), scaledEntry, scaledGuardian, packs, minTier, maxTier, monsterCount, monsters, source);
	}

	/**
	 * @param tier a map tier
	 * @return {@code true} if a map of that tier may open here
	 */
	public boolean allowsTier(int tier)
	{
		return ((minTier <= 0) || (tier >= minTier)) && ((maxTier <= 0) || (tier <= maxTier));
	}

	/**
	 * @return the fence round the field
	 */
	public EventBarrier toBarrier()
	{
		return new EventBarrier(x, y, radiusX, radiusY);
	}

	/**
	 * @return the x of the spot the players come in at
	 */
	public int getEntryX()
	{
		return entry.getX();
	}

	/**
	 * @return the y of the spot the players come in at
	 */
	public int getEntryY()
	{
		return entry.getY();
	}
}
