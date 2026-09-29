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
package org.l2jmobius.gameserver.model.classtransfer;

/**
 * A random event that may interrupt a challenge attempt.
 * @author Mobius
 */
public class TwistDefinition
{
	public enum TwistType
	{
		/** A rival fake player invades after an objective and hunts the challenger. */
		INVADER
	}

	private final TwistType _type;
	private final int _chance;
	private final int _afterObjective;
	private final int _arenaCoins;

	public TwistDefinition(TwistType type, int chance, int afterObjective, int arenaCoins)
	{
		_type = type;
		_chance = Math.max(0, Math.min(100, chance));
		_afterObjective = Math.max(0, afterObjective);
		_arenaCoins = Math.max(0, arenaCoins);
	}

	public TwistType getType()
	{
		return _type;
	}

	/**
	 * @return percent chance the twist is rolled for an attempt
	 */
	public int getChance()
	{
		return _chance;
	}

	/**
	 * @return the twist triggers once this many objectives are complete
	 */
	public int getAfterObjective()
	{
		return _afterObjective;
	}

	/**
	 * @return bonus Arena currency for defeating the twist
	 */
	public int getArenaCoins()
	{
		return _arenaCoins;
	}
}
