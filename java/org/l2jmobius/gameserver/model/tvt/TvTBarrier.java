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

import org.l2jmobius.gameserver.model.interfaces.ILocational;

/**
 * The fence round the field of a Team vs Team map: an ellipse that nobody gets through, players or fake players (see {@link org.l2jmobius.gameserver.managers.TeamVsTeamBarrier}).
 * @param x the middle's x
 * @param y the middle's y
 * @param radiusX the radius along x
 * @param radiusY the radius along y
 */
public record TvTBarrier(int x, int y, int radiusX, int radiusY)
{
	/** The smallest radius a fence may have. */
	public static final int MIN_RADIUS = 500;

	/**
	 * @param px a spot's x
	 * @param py a spot's y
	 * @param margin how far beyond the fence still counts as inside
	 * @return {@code true} if the spot lies inside the fence (or within {@code margin} beyond it)
	 */
	public boolean contains(int px, int py, int margin)
	{
		final double dx = (px - x) / (double) (radiusX + margin);
		final double dy = (py - y) / (double) (radiusY + margin);
		return ((dx * dx) + (dy * dy)) <= 1;
	}

	/**
	 * @param location a spot
	 * @param margin how far beyond the fence still counts as inside
	 * @return {@code true} if the spot lies inside the fence (or within {@code margin} beyond it)
	 */
	public boolean contains(ILocational location, int margin)
	{
		return contains(location.getX(), location.getY(), margin);
	}
}
