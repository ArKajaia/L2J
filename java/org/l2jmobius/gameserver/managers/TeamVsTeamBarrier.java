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

import org.l2jmobius.gameserver.config.custom.TeamVsTeamConfig;
import org.l2jmobius.gameserver.data.xml.FenceData;
import org.l2jmobius.gameserver.geoengine.GeoEngine;
import org.l2jmobius.gameserver.model.Location;
import org.l2jmobius.gameserver.model.actor.enums.creature.FenceState;
import org.l2jmobius.gameserver.model.actor.instance.Fence;
import org.l2jmobius.gameserver.model.interfaces.ILocational;

/**
 * The fence round the field of a Team vs Team match ({@link TeamVsTeamConfig#BARRIER}): an ellipse round the ring wall of the Ruins of Despair and every spawn point, that nobody gets through, players or fake players. The ring wall has gaps all round, so the
 * fence closes the whole field rather than its gates.<br>
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
	/** The ground height looked for (the layer closest to it). */
	private static final int GROUND_Z = -3800;

	private TeamVsTeamBarrier()
	{
	}

	/**
	 * Puts up the fence in a match's instance.
	 * @param instanceId the instance
	 * @return its walls, to take down with {@link #remove}; empty if there is no barrier
	 */
	public static List<Fence> build(int instanceId)
	{
		final List<Fence> walls = new ArrayList<>();
		final int[] barrier = TeamVsTeamConfig.BARRIER;
		if (barrier == null)
		{
			return walls;
		}

		final int cx = barrier[0];
		final int cy = barrier[1];
		final int rx = barrier[2];
		final int ry = barrier[3];
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
			addWall(walls, px, py, x, py, instanceId);
			addWall(walls, x, py, x, y, instanceId);
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
	private static void addWall(List<Fence> walls, int x1, int y1, int x2, int y2, int instanceId)
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
			final int z = geo.getHeight(x1 + (int) ((x2 - x1) * share), y1 + (int) ((y2 - y1) * share), GROUND_Z);
			low = Math.min(low, z);
			high = Math.max(high, z);
		}
		final int z2 = geo.getHeight(x2, y2, GROUND_Z);
		low = Math.min(low, z2);
		high = Math.max(high, z2);

		if (((high - low) > MAX_CLIMB) && (length > MIN_WALL))
		{
			final int mx = (x1 + x2) / 2;
			final int my = (y1 + y2) / 2;
			addWall(walls, x1, y1, mx, my, instanceId);
			addWall(walls, mx, my, x2, y2, instanceId);
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
	 * @param location a spot
	 * @param margin how far beyond the fence counts
	 * @return {@code true} if the spot lies more than {@code margin} beyond the fence (someone got through: a skill that moves them, or a gap)
	 */
	public static boolean isOutside(ILocational location, int margin)
	{
		final int[] barrier = TeamVsTeamConfig.BARRIER;
		if (barrier == null)
		{
			return false;
		}

		final double dx = (location.getX() - barrier[0]) / (double) (barrier[2] + margin);
		final double dy = (location.getY() - barrier[1]) / (double) (barrier[3] + margin);
		return ((dx * dx) + (dy * dy)) > 1;
	}

	/**
	 * @param location a spot beyond the fence
	 * @return a spot on the ground inside it, on the way from {@code location} to the middle of the field
	 */
	public static Location getInside(ILocational location)
	{
		final int[] barrier = TeamVsTeamConfig.BARRIER;
		final double dx = (location.getX() - barrier[0]) / (double) barrier[2];
		final double dy = (location.getY() - barrier[1]) / (double) barrier[3];
		final double scale = 0.93 / Math.max(1e-6, Math.sqrt((dx * dx) + (dy * dy)));
		final int x = barrier[0] + (int) ((location.getX() - barrier[0]) * scale);
		final int y = barrier[1] + (int) ((location.getY() - barrier[1]) * scale);
		return new Location(x, y, GeoEngine.getInstance().getHeight(x, y, location.getZ()));
	}
}
