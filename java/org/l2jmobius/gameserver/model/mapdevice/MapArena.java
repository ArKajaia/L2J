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
 * @param path the path the field follows, {@code null} for an ellipse (x, y and the radii are then the middle of the path and the box round its fence)
 * @param uniqueOnly {@code true} if only unique maps that name it open here, never a map of the random rotation
 */
public record MapArena(String id, String name, int x, int y, int z, int radiusX, int radiusY, Location entry, Location guardian, List<EventMapDevice.Pack> packs, int minTier, int maxTier, int monsterCount, List<Integer> monsters, EventMap source, ArenaPath path, boolean uniqueOnly)
{
	/** How far inside the fence the entry of an elliptic arena of data/MapDevice.xml lies, as a share of the radius: near the edge, so the players start at one end of the field. */
	public static final double ENTRY_SHARE = 0.90;
	/** How far along a path the entry lies from its first waypoint. */
	public static final int PATH_ENTRY_DISTANCE = 600;

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
	 * @param uniqueOnly {@code true} if only unique maps that name it open here
	 * @return the arena
	 */
	public static MapArena of(String name, int x, int y, int z, int radiusX, int radiusY, int entryAngle, int minTier, int maxTier, boolean uniqueOnly)
	{
		final int entryX = x + (int) Math.round(Math.cos(Math.toRadians(entryAngle)) * radiusX * ENTRY_SHARE);
		final int entryY = y + (int) Math.round(Math.sin(Math.toRadians(entryAngle)) * radiusY * ENTRY_SHARE);
		final int heading = (int) Math.round((Math.atan2(y - entryY, x - entryX) * 32768) / Math.PI) & 0xFFFF;
		return new MapArena("xml:" + name, name, x, y, z, radiusX, radiusY, new Location(entryX, entryY, z, heading), null, List.of(), minTier, maxTier, 0, List.of(), null, null, uniqueOnly);
	}

	/**
	 * An arena of data/MapDevice.xml that follows a path: the players come in {@link #PATH_ENTRY_DISTANCE} along it from its first waypoint, facing along it; its middle (the Map
	 * Guardian's spot, the middle waystone) is halfway along it.
	 * @param name the name shown to the players
	 * @param path the path
	 * @param minTier the lowest tier that may open here, 0 for any
	 * @param maxTier the highest tier that may open here, 0 for any
	 * @param uniqueOnly {@code true} if only unique maps that name it open here
	 * @return the arena
	 */
	public static MapArena of(String name, ArenaPath path, int minTier, int maxTier, boolean uniqueOnly)
	{
		final ArenaPath.Spot entry = path.at(Math.min(PATH_ENTRY_DISTANCE, path.getLength() / 4));
		final int heading = (int) Math.round((Math.atan2(entry.dirY(), entry.dirX()) * 32768) / Math.PI) & 0xFFFF;
		final ArenaPath.Spot middle = path.at(path.getLength() / 2);
		return new MapArena("xml:" + name, name, middle.x(), middle.y(), middle.z(), Math.max(1, path.getRadiusX()), Math.max(1, path.getRadiusY()), new Location(entry.x(), entry.y(), entry.z(), heading), null, List.of(), minTier, maxTier, 0, List.of(), null, path, uniqueOnly);
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
		return new MapArena(map.id(), map.name(), barrier.x(), barrier.y(), map.z(), barrier.radiusX(), barrier.radiusY(), device.entry(), device.guardian(), device.packs(), device.minTier(), device.maxTier(), device.monsterCount(), device.monsters(), map, null, false);
	}

	/**
	 * A smaller copy of this field (an ellipse; a path is given back as it is), round the same middle: the fence's radii times {@code factor}, and the entry pulled in towards the middle by as much (same heading).
	 * Everything placed as a share of the radius (the monsters, a twist's totems, reliquaries, phylacteries, eggs) comes in with it.
	 * @param factor the share of the radius to keep (0-1); 1 or more gives this arena back
	 * @return the smaller field, under the same id and name
	 */
	public MapArena scaled(double factor)
	{
		if ((factor >= 1) || (factor <= 0) || (path != null))
		{
			return this;
		}
		final Location scaledEntry = new Location(x + (int) Math.round((entry.getX() - x) * factor), y + (int) Math.round((entry.getY() - y) * factor), entry.getZ(), entry.getHeading());
		final Location scaledGuardian = guardian == null ? null : new Location(x + (int) Math.round((guardian.getX() - x) * factor), y + (int) Math.round((guardian.getY() - y) * factor), guardian.getZ(), guardian.getHeading());
		return new MapArena(id, name, x, y, z, (int) Math.round(radiusX * factor), (int) Math.round(radiusY * factor), scaledEntry, scaledGuardian, packs, minTier, maxTier, monsterCount, monsters, source, path, uniqueOnly);
	}

	/**
	 * A path cut short, for a unique map that holds fewer monsters: the players still come in at its start.
	 * @param lengthShare how much of the path to keep (0-1)
	 * @return the shorter field, under the same id and name; this arena if it is no path, or if nothing is cut
	 */
	public MapArena trimmed(double lengthShare)
	{
		if ((path == null) || (lengthShare >= 1) || (lengthShare <= 0))
		{
			return this;
		}
		final ArenaPath shorter = path.trimmed(path.getLength() * lengthShare);
		if (shorter == path)
		{
			return this;
		}
		final MapArena trimmed = of(name, shorter, minTier, maxTier, uniqueOnly);
		return new MapArena(id, name, trimmed.x, trimmed.y, trimmed.z, trimmed.radiusX, trimmed.radiusY, trimmed.entry, guardian, packs, minTier, maxTier, monsterCount, monsters, source, shorter, uniqueOnly);
	}

	// Where things stand, whatever the field's shape. A path is taken as an ellipse straightened along it: "along" its length (the long axis, the entry at one
	// end) and "across" it (the short axis, its width there), so what a twist places round an ellipse lands along the path instead.

	/**
	 * @return how far along the path the entry lies (0 for an ellipse)
	 */
	private double entryAlong()
	{
		return path == null ? 0 : Math.min(PATH_ENTRY_DISTANCE, path.getLength() / 4);
	}

	/**
	 * A spot of the field by its place round the middle. An ellipse: at {@code angle} round the middle, {@code share} of the radius out. A path: {@code share} of the
	 * way from its middle towards its far end (cosine of {@code angle}, negative towards the entry) and towards its sides (sine).
	 * @param angle in radians
	 * @param share of the radius, 0-1
	 * @return the spot (the ground's height is the field's there; take the ground under it)
	 */
	public Location fieldPoint(double angle, double share)
	{
		if (path == null)
		{
			return new Location(x + (int) (Math.cos(angle) * radiusX * share), y + (int) (Math.sin(angle) * radiusY * share), z);
		}
		final double half = path.getLength() / 2;
		final ArenaPath.Spot spot = path.at(half + (half * share * Math.cos(angle)));
		final double side = spot.width() * share * Math.sin(angle);
		return new Location(spot.x() + (int) Math.round(-spot.dirY() * side), spot.y() + (int) Math.round(spot.dirX() * side), spot.z());
	}

	/**
	 * @param share of the radius past the middle
	 * @return how far from the entry the spot lies that is {@code share} of the radius past the middle, on the way from the entry to the far side
	 */
	public double farSideDistance(double share)
	{
		if (path != null)
		{
			return ((path.getLength() / 2) - entryAlong()) + ((path.getLength() / 2) * share);
		}
		final double dx = x - entry.getX();
		final double dy = y - entry.getY();
		final double toMiddle = Math.max(1, Math.hypot(dx, dy));
		return toMiddle + (Math.hypot((radiusX * dx) / toMiddle, (radiusY * dy) / toMiddle) * share);
	}

	/**
	 * @param distance how far
	 * @return the spot that far from the entry on the way to the far side: straight through the middle of an ellipse, along a path
	 */
	public Location fromEntry(double distance)
	{
		if (path != null)
		{
			final ArenaPath.Spot spot = path.at(entryAlong() + distance);
			return new Location(spot.x(), spot.y(), spot.z());
		}
		final double dx = x - entry.getX();
		final double dy = y - entry.getY();
		final double toMiddle = Math.max(1, Math.hypot(dx, dy));
		return new Location(entry.getX() + (int) ((dx / toMiddle) * distance), entry.getY() + (int) ((dy / toMiddle) * distance), z);
	}

	/**
	 * @param px a spot's x
	 * @param py a spot's y
	 * @return how far out the spot lies, squared: 0 in the middle, 1 at the fence (an ellipse's ((x - middle) / radius) squared; on a path, along it and across it)
	 */
	public double fieldShare(int px, int py)
	{
		if (path == null)
		{
			final double ex = (px - x) / (double) radiusX;
			final double ey = (py - y) / (double) radiusY;
			return (ex * ex) + (ey * ey);
		}
		final double half = path.getLength() / 2;
		final double along = path.nearestDistance(px, py);
		final ArenaPath.Spot spot = path.at(along);
		final double ea = (along - half) / half;
		final double ec = Math.hypot(px - spot.x(), py - spot.y()) / Math.max(1, spot.width());
		return (ea * ea) + (ec * ec);
	}

	/**
	 * @return the middle of the box round the fence, x (an ellipse's middle; a path's middle is halfway along it, which can lie off the box's)
	 */
	public int boxX()
	{
		return path == null ? x : path.getCenterX();
	}

	/**
	 * @return the middle of the box round the fence, y
	 */
	public int boxY()
	{
		return path == null ? y : path.getCenterY();
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
		return new EventBarrier(x, y, radiusX, radiusY, path);
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
