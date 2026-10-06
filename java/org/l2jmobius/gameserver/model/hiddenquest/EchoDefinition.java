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
 * One opponent of an ECHO_GAUNTLET task.
 * @author Mobius
 */
public class EchoDefinition
{
	/** What makes an echo different from a plain champion. */
	public enum EchoGimmick
	{
		/** No trick. */
		NONE,
		/** Heals while it is not being hurt. */
		REGEN,
		/** Steps through the shadows next to the player now and then. */
		BLINK,
		/** Splits into two shards at half health. */
		SPLIT,
		/** Cannot be hurt while its two wards stand. */
		WARDS
	}

	private final String _name;
	private final EchoGimmick _gimmick;
	private final int _tier;
	private final String _line;

	public EchoDefinition(String name, EchoGimmick gimmick, int tier, String line)
	{
		_name = name;
		_gimmick = gimmick;
		_tier = Math.max(-1, Math.min(3, tier));
		_line = line;
	}

	public String getName()
	{
		return _name;
	}

	public EchoGimmick getGimmick()
	{
		return _gimmick;
	}

	/**
	 * @return the champion tier, -1 for the monster set's own tier
	 */
	public int getTier()
	{
		return _tier;
	}

	/**
	 * @return what the echo says when it appears, may be {@code null}
	 */
	public String getLine()
	{
		return _line;
	}
}
