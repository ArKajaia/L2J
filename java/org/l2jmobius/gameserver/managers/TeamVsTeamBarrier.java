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

import org.l2jmobius.gameserver.data.xml.FenceData;
import org.l2jmobius.gameserver.geoengine.GeoEngine;
import org.l2jmobius.gameserver.model.Location;
import org.l2jmobius.gameserver.model.actor.enums.creature.FenceState;
import org.l2jmobius.gameserver.model.actor.instance.Fence;
import org.l2jmobius.gameserver.model.interfaces.ILocational;
import org.l2jmobius.gameserver.model.eventmap.EventBarrier;
import org.l2jmobius.gameserver.model.eventmap.EventMap;

/**
 * The fence round the field of a Team vs Team match (the {@link EventMap#barrier} of its map): an ellipse round every spawn point, that nobody gets through, players or fake players. On the Ruins of Despair it goes round the ring wall, which has gaps all round,
 * so the fence closes the whole field rather than its gates.<br>
 * A fence is a rectangle drawn by the client and kept in the world regions round its middle, and it stops only who walks at most 100 above or below it. So the fence is many short walls: thin rectangles going round the ellipse in steps (one along x, one along y),
 * each at the height of the ground under it. Where the ground climbs fast along a wall, it is cut shorter, and on a slope steeper than that (a cliff's edge) it is stacked at several heights.
 */
public final class TeamVsTeamBarrier
{
	/** The length of one step round the ellipse. */
	private static final int STEP = 220;
	/** How thick a wall is: its ends overlap the next one's, so there is no gap at a corner. */
	private static final int THICKNESS = 16;
	/** A wall is cut in two while the ground under it climbs more than this, down to this length; a shorter one with more is stacked every this much height. */
	private static final int MAX_CLIMB = 120;
	private static final int MIN_WALL = 48;
	private static final int STACK_STEP = 150;
	/** The ground is looked at every this much along a wall. */
	private static final int SAMPLE = 16;
	private TeamVsTeamBarrier()
	{
	}

	/**
	 * Puts up the fence of a map in a match's instance.
	 * @param map the match's map
	 * @param instanceId the instance
	 * @return its walls, to take down with {@link #remove}; empty if the map has no barrier
	 */
	public static List<Fence> build(EventMap map, int instanceId)
	{
		final List<Fence> walls = new ArrayList<>();
		final EventBarrier barrier = map.barrier();
		if (barrier == null)
		{
			return walls;
		}

		final int cx = barrier.x();
		final int cy = barrier.y();
		final int rx = barrier.radiusX();
		final int ry = barrier.radiusY();
		final int groundZ = map.z();
		// Ramanujan's length of an ellipse.
		final double length = Math.PI * ((3 * (rx + ry)) - Math.sqrt(((3 * rx) + ry) * (rx + (3 * ry))));
		final int steps = Math.max(16, (int) Math.ceil(length / STEP));
		int px = cx + rx;
		int py = cy;
		for (int i = 1; i <= steps; i++)
		{
			final double angle = (i * 2 * Math.PI) / steps;
			final int x = cx + (int) Math.round(Math.cos(angle) * rx);
			final int y = cy + (int) Math.round(Math.sin(angle) * ry);
			addWall(walls, px, py, x, py, groundZ, instanceId);
			addWall(walls, x, py, x, y, groundZ, instanceId);
			px = x;
			py = y;
		}
		return walls;
	}

	/**
	 * Takes the fence of a match down.
	 * @param walls its walls
	 */
	public static void remove(List<Fence> walls)
	{
		for (Fence wall : walls)
		{
			wall.deleteMe();
		}
		walls.clear();
	}

	/**
	 * A wall along x or along y, at the height of the ground under it: cut in two while the ground climbs too much along it, stacked at several heights when it is short and still climbs.
	 */
	private static void addWall(List<Fence> walls, int x1, int y1, int x2, int y2, int groundZ, int instanceId)
	{
		final int length = Math.abs(x2 - x1) + Math.abs(y2 - y1);
		if (length < 2)
		{
			return;
		}

		final GeoEngine geo = GeoEngine.getInstance();
		int low = Integer.MAX_VALUE;
		int high = Integer.MIN_VALUE;
		for (int d = 0; d <= length; d += SAMPLE)
		{
			final double share = (double) Math.min(d, length) / length;
			final int z = geo.getHeight(x1 + (int) ((x2 - x1) * share), y1 + (int) ((y2 - y1) * share), groundZ);
			low = Math.min(low, z);
			high = Math.max(high, z);
		}
		final int z2 = geo.getHeight(x2, y2, groundZ);
		low = Math.min(low, z2);
		high = Math.max(high, z2);

		if (((high - low) > MAX_CLIMB) && (length > MIN_WALL))
		{
			final int mx = (x1 + x2) / 2;
			final int my = (y1 + y2) / 2;
			addWall(walls, x1, y1, mx, my, groundZ, instanceId);
			addWall(walls, mx, my, x2, y2, groundZ, instanceId);
			return;
		}

		final int width = Math.abs(x2 - x1) + THICKNESS;
		final int depth = Math.abs(y2 - y1) + THICKNESS;
		final int x = (x1 + x2) / 2;
		final int y = (y1 + y2) / 2;
		for (int z = (high - low) <= MAX_CLIMB ? (low + high) / 2 : low; z <= Math.max(low, high); z += STACK_STEP)
		{
			walls.add(FenceData.getInstance().spawnFence(x, y, z, "TvT Barrier", width, depth, 1, instanceId, FenceState.CLOSED));
			if ((high - low) <= MAX_CLIMB)
			{
				break;
			}
		}
	}

	/**
	 * @param barrier the fence, {@code null} for none
	 * @param location a spot
	 * @param margin how far beyond the fence counts
	 * @return {@code true} if the spot lies more than {@code margin} beyond the fence (someone got through: a skill that moves them, or a gap)
	 */
	public static boolean isOutside(EventBarrier barrier, ILocational location, int margin)
	{
		return (barrier != null) && !barrier.contains(location, margin);
	}

	/**
	 * @param barrier the fence
	 * @param location a spot beyond the fence
	 * @return a spot on the ground inside it, on the way from {@code location} to the middle of the field
	 */
	public static Location getInside(EventBarrier barrier, ILocational location)
	{
		final double dx = (location.getX() - barrier.x()) / (double) barrier.radiusX();
		final double dy = (location.getY() - barrier.y()) / (double) barrier.radiusY();
		final double scale = 0.93 / Math.max(1e-6, Math.sqrt((dx * dx) + (dy * dy)));
		final int x = barrier.x() + (int) ((location.getX() - barrier.x()) * scale);
		final int y = barrier.y() + (int) ((location.getY() - barrier.y()) * scale);
		return new Location(x, y, GeoEngine.getInstance().getHeight(x, y, location.getZ()));
	}
}
