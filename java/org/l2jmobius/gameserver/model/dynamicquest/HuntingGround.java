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

import java.util.Collections;
import java.util.List;

import org.l2jmobius.gameserver.model.Location;

/**
 * A place in the open world where monsters of a level live: the centre of a quest and the monsters it uses.
 * @author Mobius
 */
public class HuntingGround
{
	private final Location _center;
	private final List<Integer> _npcIds;
	private final int _level;
	private final String _town;

	public HuntingGround(Location center, List<Integer> npcIds, int level, String town)
	{
		_center = center;
		_npcIds = Collections.unmodifiableList(npcIds);
		_level = level;
		_town = town;
	}

	public Location getCenter()
	{
		return _center;
	}

	/**
	 * @return the monsters living there, the most common first
	 */
	public List<Integer> getNpcIds()
	{
		return _npcIds;
	}

	/**
	 * @return the average level of its monsters
	 */
	public int getLevel()
	{
		return _level;
	}

	/**
	 * @return the nearest town's name
	 */
	public String getTown()
	{
		return _town;
	}
}
