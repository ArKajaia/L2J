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

/**
 * How a dynamic quest pays: EXP as a share of the quest level's EXP, SP from the EXP, adena by level, and items.
 * @author Mobius
 */
public class RewardDefinition
{
	private final double _expPercent;
	private final double _spRatio;
	private final long _adenaPerLevel;
	private final List<RewardEntry> _items;

	public RewardDefinition(double expPercent, double spRatio, long adenaPerLevel, List<RewardEntry> items)
	{
		_expPercent = expPercent;
		_spRatio = spRatio;
		_adenaPerLevel = adenaPerLevel;
		_items = Collections.unmodifiableList(items);
	}

	/**
	 * @return the EXP reward in percent of the EXP needed to go from the quest's level to the next
	 */
	public double getExpPercent()
	{
		return _expPercent;
	}

	/**
	 * @return SP given per point of EXP given
	 */
	public double getSpRatio()
	{
		return _spRatio;
	}

	public long getAdenaPerLevel()
	{
		return _adenaPerLevel;
	}

	/**
	 * @return the items (a reward table's and the template's own)
	 */
	public List<RewardEntry> getItems()
	{
		return _items;
	}
}
