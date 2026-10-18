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

import org.l2jmobius.gameserver.model.StatSet;

/**
 * One way a dynamic quest is offered, with its own parameters.
 * @author Mobius
 */
public class QuestTrigger
{
	private final TriggerType _type;
	private final StatSet _params;

	public QuestTrigger(TriggerType type, StatSet params)
	{
		_type = type;
		_params = params;
	}

	public TriggerType getType()
	{
		return _type;
	}

	public StatSet getParams()
	{
		return _params;
	}

	/**
	 * @return the weight of the quest when offers are rolled (default 10)
	 */
	public int getWeight()
	{
		return Math.max(1, _params.getInt("weight", 10));
	}

	/**
	 * @return the chance in percent (default 100)
	 */
	public double getChance()
	{
		return _params.getDouble("chance", 100);
	}
}
