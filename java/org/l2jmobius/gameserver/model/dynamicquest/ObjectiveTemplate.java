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
 * An objective of a stage, as written in the data.
 * @author Mobius
 */
public class ObjectiveTemplate
{
	private final ObjectiveType _type;
	private final StatSet _params;
	private final boolean _optional;
	private final boolean _hidden;
	private final String _text;

	public ObjectiveTemplate(ObjectiveType type, StatSet params, boolean optional, boolean hidden, String text)
	{
		_type = type;
		_params = params;
		_optional = optional;
		_hidden = hidden;
		_text = text;
	}

	public ObjectiveType getType()
	{
		return _type;
	}

	public StatSet getParams()
	{
		return _params;
	}

	/**
	 * @return {@code true} if the stage doesn't need it; done, it adds DynamicQuestOptionalBonusPercent to the reward
	 */
	public boolean isOptional()
	{
		return _optional;
	}

	/**
	 * @return {@code true} if it isn't shown in the quest's description before its stage starts
	 */
	public boolean isHidden()
	{
		return _hidden;
	}

	/**
	 * @return the line describing it (with placeholders), {@code null} for the objective's own
	 */
	public String getText()
	{
		return _text;
	}
}
