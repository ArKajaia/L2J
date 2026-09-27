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
package org.l2jmobius.gameserver.model.actor.holders.npc;

import org.l2jmobius.gameserver.model.StatSet;

/**
 * One level tier of a roaming fake player gear kit (see data/FakePlayerPvp.xml). Unused slots are 0.
 */
public class FakePlayerPvpGearTier
{
	private final int _minLevel;
	private final int _rHand;
	private final int _lHand;
	private final int _chest;
	private final int _legs;
	private final int _head;
	private final int _gloves;
	private final int _feet;
	private final int _earring;
	private final int _necklace;
	private final int _ring;
	
	public FakePlayerPvpGearTier(StatSet set)
	{
		_minLevel = set.getInt("minLevel");
		_rHand = set.getInt("rhand", 0);
		_lHand = set.getInt("lhand", 0);
		_chest = set.getInt("chest", 0);
		_legs = set.getInt("legs", 0);
		_head = set.getInt("head", 0);
		_gloves = set.getInt("gloves", 0);
		_feet = set.getInt("feet", 0);
		_earring = set.getInt("earring", 0);
		_necklace = set.getInt("necklace", 0);
		_ring = set.getInt("ring", 0);
	}
	
	public int getMinLevel()
	{
		return _minLevel;
	}
	
	public int getRHand()
	{
		return _rHand;
	}
	
	public int getLHand()
	{
		return _lHand;
	}
	
	public int getChest()
	{
		return _chest;
	}
	
	public int getLegs()
	{
		return _legs;
	}
	
	public int getHead()
	{
		return _head;
	}
	
	public int getGloves()
	{
		return _gloves;
	}
	
	public int getFeet()
	{
		return _feet;
	}
	
	public int getEarring()
	{
		return _earring;
	}
	
	public int getNecklace()
	{
		return _necklace;
	}
	
	public int getRing()
	{
		return _ring;
	}
}
