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
 * An item of a reward or item table, for a range of quest levels.
 * @author Mobius
 */
public class RewardEntry
{
	private final int _id;
	private final long _count;
	private final int _minLevel;
	private final int _maxLevel;
	private final double _chance;
	private final boolean _scaled;

	public RewardEntry(int id, long count, int minLevel, int maxLevel, double chance, boolean scaled)
	{
		_id = id;
		_count = count;
		_minLevel = minLevel;
		_maxLevel = maxLevel;
		_chance = chance;
		_scaled = scaled;
	}

	public int getId()
	{
		return _id;
	}

	public long getCount()
	{
		return _count;
	}

	public double getChance()
	{
		return _chance;
	}

	/**
	 * @return {@code true} if its count is multiplied by the reward multipliers
	 */
	public boolean isScaled()
	{
		return _scaled;
	}

	/**
	 * @param level a quest level
	 * @return {@code true} if it applies at that level
	 */
	public boolean isFor(int level)
	{
		return (level >= _minLevel) && (level <= _maxLevel);
	}
}
