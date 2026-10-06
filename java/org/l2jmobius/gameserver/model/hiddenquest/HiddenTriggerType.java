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
 * The secret conditions that make a hidden quest messenger come to a player.
 * <ul>
 * <li>State conditions are read from the character itself (PK count, PvP count, fame, adena, towns visited).</li>
 * <li>Counter conditions are counted by the Hidden Quest manager in player variables from the day the feature is enabled.</li>
 * </ul>
 * @author Mobius
 */
public enum HiddenTriggerType
{
	/** The character's PK count reaches the value. */
	PK_COUNT(false),
	/** The character's PvP kill count reaches the value. */
	PVP_COUNT(false),
	/** The character's fame reaches the value. */
	FAME(false),
	/** The character carries at least this much adena. */
	ADENA(false),
	/** The character has entered every listed town zone ({@code towns}, town ids). */
	TOWNS_VISITED(false),
	/** Deaths outside PvP zones, Olympiad, duels and events. */
	DEATHS(true),
	/** Levels gained in a row without such a death. A level only counts the first time it is reached. */
	LEVEL_STREAK(true),
	/** Monsters killed whose name contains the whole word {@code word} (case-sensitive). */
	KILL_NAMED(true),
	/** Undead monsters killed. */
	KILL_UNDEAD(true),
	/** Monsters killed during the game night. */
	KILL_NIGHT(true),
	/** Monsters killed while not in a party. */
	KILL_SOLO(true),
	/** Raid bosses killed (the killing blow, or the party member the kill is credited to). */
	KILL_RAID(true),
	/** Players with karma killed. */
	KILL_KARMA_PLAYER(true);

	private final boolean _counter;

	HiddenTriggerType(boolean counter)
	{
		_counter = counter;
	}

	/**
	 * @return {@code true} if the condition is a counter kept by the manager, {@code false} if it is read from the character
	 */
	public boolean isCounter()
	{
		return _counter;
	}
}
