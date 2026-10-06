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
 * One reward line of a hidden quest, given when the player's level is within {@code minLevel}-{@code maxLevel}.
 * @author Mobius
 */
public class HiddenReward
{
	public enum RewardType
	{
		/** An item ({@code id}, {@code count}). */
		ITEM,
		/** Experience ({@code count}). */
		EXP,
		/** Skill points ({@code count}). */
		SP,
		/** A character title ({@code value}). */
		TITLE,
		/** The title colour ({@code value}, RRGGBB). Saved with the character. */
		TITLE_COLOR,
		/** The name colour ({@code value}, RRGGBB). Kept in a player variable and applied at every login. */
		NAME_COLOR,
		/** Clears the karma. */
		CLEAR_KARMA,
		/** Lowers the PK count by {@code count}. */
		REDUCE_PK
	}

	private final RewardType _type;
	private final int _minLevel;
	private final int _maxLevel;
	private final int _id;
	private final long _count;
	private final String _value;

	public HiddenReward(RewardType type, int minLevel, int maxLevel, int id, long count, String value)
	{
		_type = type;
		_minLevel = minLevel;
		_maxLevel = maxLevel;
		_id = id;
		_count = count;
		_value = value;
	}

	public RewardType getType()
	{
		return _type;
	}

	public boolean isFor(int level)
	{
		return (level >= _minLevel) && (level <= _maxLevel);
	}

	public int getId()
	{
		return _id;
	}

	public long getCount()
	{
		return _count;
	}

	public String getValue()
	{
		return _value;
	}
}
