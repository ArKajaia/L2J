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

/**
 * A point of a Conquest map: a spawn point of one team, or a capture point.
 * @param type a blue spawn, a red spawn or a capture point
 * @param name the name shown in game and on the editor (a capture point's letter, A, B...); blank for a spawn point, which goes by its number
 * @param x the x
 * @param y the y
 * @param z the z, on the ground
 * @param heading where the players spawned there face (spawn points)
 * @param radius a spawn point: how far around it the players of a wave are spread; a capture point: how close a player must stand to capture it
 */
public record ConquestPoint(ConquestPointType type, String name, int x, int y, int z, int heading, int radius)
{
	/**
	 * @param other another point
	 * @return the distance between both, on the ground
	 */
	public double distance2D(ConquestPoint other)
	{
		return Math.hypot(x - other.x, y - other.y);
	}

	/**
	 * @param newX the new x
	 * @param newY the new y
	 * @param newZ the new z
	 * @return this point moved there
	 */
	public ConquestPoint moveTo(int newX, int newY, int newZ)
	{
		return new ConquestPoint(type, name, newX, newY, newZ, heading, radius);
	}
}
