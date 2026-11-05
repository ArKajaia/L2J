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

import org.l2jmobius.gameserver.model.eventmap.EventBarrier;

/**
 * A place a map can be opened in (data/MapDevice.xml): an open field that a map run copies into its own instance and closes with a fence, like the field of a Team vs Team match.
 * @param name the name shown to the players
 * @param x the middle's x
 * @param y the middle's y
 * @param z the height of the ground
 * @param radiusX the fence's radius along x
 * @param radiusY the fence's radius along y
 * @param entryAngle where the players come in, in degrees round the middle (0 = east, 90 = south): the entry lies near the fence in that direction
 */
public record MapArena(String name, int x, int y, int z, int radiusX, int radiusY, int entryAngle)
{
	/** How far inside the fence the entry lies, as a share of the radius. */
	public static final double ENTRY_SHARE = 0.80;

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
		return x + (int) Math.round(Math.cos(Math.toRadians(entryAngle)) * radiusX * ENTRY_SHARE);
	}

	/**
	 * @return the y of the spot the players come in at
	 */
	public int getEntryY()
	{
		return y + (int) Math.round(Math.sin(Math.toRadians(entryAngle)) * radiusY * ENTRY_SHARE);
	}
}
