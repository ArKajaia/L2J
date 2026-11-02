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
package org.l2jmobius.gameserver.model.dynamicquest;

/**
 * A part of the open world a dynamic quest must not send anyone to (data/DynamicQuests.xml {@code <restrictedArea>}): a place only a teleport with conditions reaches,
 * such as the catacombs and necropolises, or one a character above a level can't enter, such as the Cruma Tower.
 * @author Mobius
 */
public class RestrictedArea
{
	private final String _name;
	private final int _minX;
	private final int _maxX;
	private final int _minY;
	private final int _maxY;
	private final int _minZ;
	private final int _maxZ;
	private final int _maxLevel;

	public RestrictedArea(String name, int minX, int maxX, int minY, int maxY, int minZ, int maxZ, int maxLevel)
	{
		_name = name;
		_minX = Math.min(minX, maxX);
		_maxX = Math.max(minX, maxX);
		_minY = Math.min(minY, maxY);
		_maxY = Math.max(minY, maxY);
		_minZ = Math.min(minZ, maxZ);
		_maxZ = Math.max(minZ, maxZ);
		_maxLevel = Math.max(0, maxLevel);
	}

	public String getName()
	{
		return _name;
	}

	/**
	 * @return the highest level of a character who can still enter, 0 if nobody can be sent there
	 */
	public int getMaxLevel()
	{
		return _maxLevel;
	}

	/**
	 * @param x the x
	 * @param y the y
	 * @param z the z
	 * @return {@code true} if the point is inside
	 */
	public boolean contains(int x, int y, int z)
	{
		return (x >= _minX) && (x <= _maxX) && (y >= _minY) && (y <= _maxY) && (z >= _minZ) && (z <= _maxZ);
	}
}
