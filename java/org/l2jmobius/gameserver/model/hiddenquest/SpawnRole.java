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
package org.l2jmobius.gameserver.model.hiddenquest;

/**
 * A group of monsters a hidden quest task spawns ("ambush", "leader", "wave"...): which monster set, how many and with what title.
 * @author Mobius
 */
public class SpawnRole
{
	private final String _name;
	private final MonsterSet _set;
	private final int _count;
	private final int _tierBonus;
	private final String _title;

	public SpawnRole(String name, MonsterSet set, int count, int tierBonus, String title)
	{
		_name = name;
		_set = set;
		_count = Math.max(1, count);
		_tierBonus = tierBonus;
		_title = title;
	}

	public String getName()
	{
		return _name;
	}

	public MonsterSet getSet()
	{
		return _set;
	}

	public int getCount()
	{
		return _count;
	}

	public int getTierBonus()
	{
		return _tierBonus;
	}

	/**
	 * @return the title shown over the monsters, {@code null} to keep their own
	 */
	public String getTitle()
	{
		return _title;
	}
}
