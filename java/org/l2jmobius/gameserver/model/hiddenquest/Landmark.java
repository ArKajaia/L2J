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

import org.l2jmobius.gameserver.model.Location;

/**
 * A place in the world used by a RIDDLE task: the riddle that points to it and a hint for players who are stuck.
 * @author Mobius
 */
public class Landmark
{
	private final String _name;
	private final Location _location;
	private final String _riddle;
	private final String _hint;

	public Landmark(String name, Location location, String riddle, String hint)
	{
		_name = name;
		_location = location;
		_riddle = riddle;
		_hint = hint;
	}

	public String getName()
	{
		return _name;
	}

	public Location getLocation()
	{
		return _location;
	}

	public String getRiddle()
	{
		return _riddle;
	}

	public String getHint()
	{
		return _hint;
	}
}
