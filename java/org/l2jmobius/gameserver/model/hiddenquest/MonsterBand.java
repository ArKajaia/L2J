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
 * One level band of a monster set: the monster spawned for players of at least {@code minLevel}.
 * @author Mobius
 */
public class MonsterBand
{
	private final int _minLevel;
	private final int _npcId;
	private final int _tier;

	public MonsterBand(int minLevel, int npcId, int tier)
	{
		_minLevel = minLevel;
		_npcId = npcId;
		_tier = Math.max(0, Math.min(3, tier));
	}

	public int getMinLevel()
	{
		return _minLevel;
	}

	public int getNpcId()
	{
		return _npcId;
	}

	/**
	 * @return the champion tier (0-3) the monster gets
	 */
	public int getTier()
	{
		return _tier;
	}
}
