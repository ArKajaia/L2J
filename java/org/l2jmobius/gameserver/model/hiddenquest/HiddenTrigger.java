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

import java.util.regex.Pattern;

/**
 * The secret condition of a hidden quest.
 * @author Mobius
 */
public class HiddenTrigger
{
	private final HiddenTriggerType _type;
	private final long _count;
	private final String _word;
	private final Pattern _namePattern;
	private final int[] _towns;

	public HiddenTrigger(HiddenTriggerType type, long count, String word, int[] towns)
	{
		_type = type;
		_count = Math.max(1, count);
		_word = (word == null) || word.isEmpty() ? null : word;
		_namePattern = _word == null ? null : Pattern.compile("\\b" + Pattern.quote(_word) + "\\b");
		_towns = towns == null ? new int[0] : towns;
	}

	public HiddenTriggerType getType()
	{
		return _type;
	}

	/**
	 * @return the value to reach (for {@link HiddenTriggerType#TOWNS_VISITED} the number of listed towns)
	 */
	public long getCount()
	{
		return _type == HiddenTriggerType.TOWNS_VISITED ? _towns.length : _count;
	}

	public int[] getTowns()
	{
		return _towns;
	}

	/**
	 * @return the player variable holding the counter, {@code null} for state conditions
	 */
	public String getCounterKey()
	{
		if (!_type.isCounter())
		{
			return null;
		}
		return _type == HiddenTriggerType.KILL_NAMED ? "HQ_C_KILL_NAMED_" + _word.toUpperCase() : "HQ_C_" + _type.name();
	}

	/**
	 * @param name a monster name
	 * @return {@code true} if the name contains the trigger's whole word
	 */
	public boolean matchesName(String name)
	{
		return (_namePattern != null) && (name != null) && _namePattern.matcher(name).find();
	}

	@Override
	public String toString()
	{
		return _type + (_word != null ? "(" + _word + ")" : "") + " >= " + getCount();
	}
}
