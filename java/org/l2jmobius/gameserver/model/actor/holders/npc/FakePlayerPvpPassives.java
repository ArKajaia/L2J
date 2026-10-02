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

import org.l2jmobius.gameserver.model.passivetree.PassiveStatBonusCache;

/**
 * The passive tree a roaming fake player rolled when its template was made (see {@link org.l2jmobius.gameserver.managers.FakePlayerPvpPassiveTree}). It lives on the profile, so a fake player that comes back after dying has the same tree.
 */
public class FakePlayerPvpPassives
{
	private final int _subclasses;
	private final int _nodeCount;
	private final int _points;
	private final String _sector;
	private final PassiveStatBonusCache _bonus;
	private double _hpShare = 1;
	
	/**
	 * @param subclasses the subclasses it rolled
	 * @param nodeCount the nodes it allocated
	 * @param points the points those nodes cost
	 * @param sector the sector of its starting point
	 * @param bonus the summed effects of its nodes
	 */
	public FakePlayerPvpPassives(int subclasses, int nodeCount, int points, String sector, PassiveStatBonusCache bonus)
	{
		_subclasses = subclasses;
		_nodeCount = nodeCount;
		_points = points;
		_sector = sector;
		_bonus = bonus;
	}
	
	public int getSubclasses()
	{
		return _subclasses;
	}
	
	public int getNodeCount()
	{
		return _nodeCount;
	}
	
	public int getPoints()
	{
		return _points;
	}
	
	public String getSector()
	{
		return _sector;
	}
	
	public PassiveStatBonusCache getBonus()
	{
		return _bonus;
	}
	
	/**
	 * @return the part of its max HP that is HP and not the class CP folded into it (see FakePvpIncludeCpInHp), which max HP % bonuses apply to
	 */
	public double getHpShare()
	{
		return _hpShare;
	}
	
	public void setHpShare(double hpShare)
	{
		_hpShare = Math.max(0, Math.min(1, hpShare));
	}
	
	@Override
	public String toString()
	{
		return _nodeCount + " nodes / " + _points + " points (" + _subclasses + " subclasses, " + _sector + ")";
	}
}
