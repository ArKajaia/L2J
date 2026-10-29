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

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import org.l2jmobius.gameserver.model.Location;
import org.l2jmobius.gameserver.model.actor.Creature;
import org.l2jmobius.gameserver.model.actor.holders.npc.FakePlayerParty;

/**
 * A party of 5 fake players of a Conquest match, and what its team's commander told it to do. Only its leader marches (the others keep to it, see the party AI), so one order moves the whole party.
 */
public class ConquestParty
{
	/** What a commander tells a party to do. */
	public enum OrderKind
	{
		/** Take a capture point the team doesn't hold. */
		CAPTURE,
		/** Hold or win back a capture point of its team. */
		DEFEND,
		/** Go for an enemy group. */
		ATTACK,
		/** Stay where it is. */
		HOLD
	}

	/**
	 * An order of a commander.
	 * @param kind what to do
	 * @param target where to go (already spread out among the parties sent there, and inside the map's area)
	 * @param point the capture point it is about, {@code null} for none
	 * @param since when it was given (a new order of the same kind and point keeps it)
	 */
	public record Order(OrderKind kind, Location target, String point, long since)
	{
	}

	private final int _id;
	private final ConquestTeam _team;
	private final List<ConquestFighter> _members = new CopyOnWriteArrayList<>();
	private volatile FakePlayerParty _party;
	private volatile Order _order;
	// Its dead come back together: the wave they wait for, where, and the spot they gather at.
	volatile long _waveAt;
	volatile Location _waveBase;
	volatile Location _waveSpot;

	ConquestParty(int id, ConquestTeam team)
	{
		_id = id;
		_team = team;
	}

	/**
	 * @return its number in its team, from 1
	 */
	public int getId()
	{
		return _id;
	}

	public ConquestTeam getTeam()
	{
		return _team;
	}

	public List<ConquestFighter> getMembers()
	{
		return _members;
	}

	void addMember(ConquestFighter fighter)
	{
		_members.add(fighter);
		fighter.setParty(this);
	}

	public FakePlayerParty getFakeParty()
	{
		return _party;
	}

	void setFakeParty(FakePlayerParty party)
	{
		_party = party;
	}

	/**
	 * @return the commander's order, {@code null} before the first
	 */
	public Order getOrder()
	{
		return _order;
	}

	/**
	 * Gives the party an order (by its team's commander).
	 * @param order the order, {@code null} to take it back
	 */
	public void setOrder(Order order)
	{
		_order = order;
	}

	/**
	 * @return its leader: the party's own leader while it lives, else its first living member, {@code null} if all are dead
	 */
	public Creature getLeader()
	{
		final FakePlayerParty party = _party;
		final Creature leader = party != null ? party.getActiveLeader() : null;
		if ((leader != null) && !leader.isDead())
		{
			return leader;
		}
		for (ConquestFighter member : _members)
		{
			if (member.isAlive())
			{
				return member.getNpc();
			}
		}
		return null;
	}

	/**
	 * @return how many of its members are alive
	 */
	public int getAlive()
	{
		int alive = 0;
		for (ConquestFighter member : _members)
		{
			if (member.isAlive())
			{
				alive++;
			}
		}
		return alive;
	}

	/**
	 * @return where it is: its leader, {@code null} if all are dead
	 */
	public Location getLocation()
	{
		final Creature leader = getLeader();
		return leader != null ? leader.getLocation() : null;
	}
}
