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
package org.l2jmobius.gameserver.model.conquest;

/**
 * What a {@link ConquestPoint} is.
 */
public enum ConquestPointType
{
	/** A spawn point of the blue team. */
	BLUE("blue", 300),
	/** A spawn point of the red team. */
	RED("red", 300),
	/** A capture point. */
	CAPTURE("capture", 600);

	private final String _key;
	private final int _defaultRadius;

	ConquestPointType(String key, int defaultRadius)
	{
		_key = key;
		_defaultRadius = defaultRadius;
	}

	/**
	 * @return the name in the map files, the web page and the admin command (blue, red, capture)
	 */
	public String getKey()
	{
		return _key;
	}

	/**
	 * @return the radius of a new point of this type
	 */
	public int getDefaultRadius()
	{
		return _defaultRadius;
	}

	/**
	 * @param key blue, red or capture (also cap, c, b, r), any case
	 * @return the type, {@code null} if {@code key} is none
	 */
	public static ConquestPointType fromKey(String key)
	{
		if (key == null)
		{
			return null;
		}
		return switch (key.toLowerCase())
		{
			case "blue", "b" -> BLUE;
			case "red", "r" -> RED;
			case "capture", "cap", "c" -> CAPTURE;
			default -> null;
		};
	}
}
