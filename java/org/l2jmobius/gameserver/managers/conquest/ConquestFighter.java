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
package org.l2jmobius.gameserver.managers.conquest;

import org.l2jmobius.gameserver.managers.simulation.SimRole;
import org.l2jmobius.gameserver.model.Location;
import org.l2jmobius.gameserver.model.actor.Npc;

/**
 * A fake player of a Conquest match.
 */
public class ConquestFighter
{
	private final Npc _npc;
	private final ConquestTeam _team;
	private final SimRole _role;
	private volatile ConquestParty _party;
	volatile int _kills;
	volatile int _deaths;
	/** When it comes back from the dead (0: not waiting), and where. */
	volatile long _respawnAt;
	volatile Location _respawnBase;
	volatile long _protectedUntil;
	/** The enemy it saw last, kept a moment (looking is the costly part of its thinking). */
	volatile Npc _seen;
	volatile long _seenUntil;
	/** For how many ticks in a row it was found off the ground (inside a wall, in the rock). */
	volatile int _offGround;

	ConquestFighter(Npc npc, ConquestTeam team, SimRole role)
	{
		_npc = npc;
		_team = team;
		_role = role;
	}

	public Npc getNpc()
	{
		return _npc;
	}

	public ConquestTeam getTeam()
	{
		return _team;
	}

	public SimRole getRole()
	{
		return _role;
	}

	public ConquestParty getParty()
	{
		return _party;
	}

	void setParty(ConquestParty party)
	{
		_party = party;
	}

	public int getKills()
	{
		return _kills;
	}

	public int getDeaths()
	{
		return _deaths;
	}

	/**
	 * @return {@code true} while it is under its spawn protection
	 */
	public boolean isProtected()
	{
		return _protectedUntil > 0;
	}

	public boolean isAlive()
	{
		return !_npc.isDead() && _npc.isSpawned();
	}
}
