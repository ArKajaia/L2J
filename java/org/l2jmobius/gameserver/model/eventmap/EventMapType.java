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
package org.l2jmobius.gameserver.model.eventmap;

/**
 * The events an {@link EventMap} can be used in. Each has its own points on the map; the area, the fence and the doors are shared.
 */
public enum EventMapType
{
	/** Team vs Team: the bases of Blue and Red. */
	TVT("tvt", "Team vs Team"),
	/** Conquest: the spawn points of Blue and Red, and the capture points. */
	CONQUEST("conquest", "Conquest"),
	/** Deathmatch: the spots everyone spawns at, every one for themselves. */
	DEATHMATCH("deathmatch", "Deathmatch"),
	/** Race Wars: the start points of the two races of a race-vs-race match, and of the three races of the final. */
	RACEWARS("racewars", "Race Wars");

	private final String _key;
	private final String _title;

	EventMapType(String key, String title)
	{
		_key = key;
		_title = title;
	}

	/**
	 * @return its name in the map files, the web page and the admin commands: tvt, conquest, deathmatch, racewars
	 */
	public String getKey()
	{
		return _key;
	}

	/**
	 * @return its name for people: Team vs Team, Conquest, Deathmatch, Race Wars
	 */
	public String getTitle()
	{
		return _title;
	}

	/**
	 * @param key tvt, conquest, deathmatch (also dm) or racewars (also rw), any case
	 * @return the type, {@code null} for none
	 */
	public static EventMapType fromKey(String key)
	{
		if (key == null)
		{
			return null;
		}
		return switch (key.trim().toLowerCase())
		{
			case "tvt" -> TVT;
			case "conquest" -> CONQUEST;
			case "deathmatch", "dm" -> DEATHMATCH;
			case "racewars", "rw" -> RACEWARS;
			default -> null;
		};
	}
}
