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
package org.l2jmobius.gameserver.model.actor.holders.npc;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import org.l2jmobius.gameserver.model.actor.Creature;
import org.l2jmobius.gameserver.model.actor.Npc;
import org.l2jmobius.gameserver.model.actor.Player;
import org.l2jmobius.gameserver.model.groups.Party;

/**
 * The fake players of a party (see {@link org.l2jmobius.gameserver.managers.FakePartyManager}). Fake players are npcs, so they can't be in a {@link Party}: they are kept here, next to it.
 * <ul>
 * <li>With a host: the player that invited them. The party is the host and the players of its {@link Party} (if any) plus these fake players.</li>
 * <li>Without a host: fake players hunting together, led by the first one.</li>
 * </ul>
 */
public class FakePlayerParty
{
	private final Player _host;
	private final List<Npc> _fakes = new CopyOnWriteArrayList<>();
	
	/**
	 * @param host the player that invited the fake players, {@code null} for a party of fake players only
	 */
	public FakePlayerParty(Player host)
	{
		_host = host;
	}
	
	/**
	 * @return the player that invited the fake players, {@code null} for a party of fake players only
	 */
	public Player getHost()
	{
		return _host;
	}
	
	/**
	 * @return {@code true} for fake players hunting together, without a player
	 */
	public boolean isFakeOnly()
	{
		return _host == null;
	}
	
	/**
	 * @return its fake players, in the order they joined (dead ones too)
	 */
	public List<Npc> getFakes()
	{
		return _fakes;
	}
	
	public void addFake(Npc fake)
	{
		if (!_fakes.contains(fake))
		{
			_fakes.add(fake);
		}
	}
	
	public boolean removeFake(Npc fake)
	{
		return _fakes.remove(fake);
	}
	
	/**
	 * @return the players of the party: the host and the members of its {@link Party}, none for a party of fake players only
	 */
	public List<Player> getPlayers()
	{
		if (_host == null)
		{
			return Collections.emptyList();
		}
		
		final Party party = _host.getParty();
		return party != null ? party.getMembers() : Collections.singletonList(_host);
	}
	
	/**
	 * @return every member: players first, then fake players
	 */
	public List<Creature> getMembers()
	{
		final List<Player> players = getPlayers();
		final List<Creature> members = new ArrayList<>(players.size() + _fakes.size());
		members.addAll(players);
		members.addAll(_fakes);
		return members;
	}
	
	/**
	 * @return how many members it has, players and fake players
	 */
	public int size()
	{
		return getPlayers().size() + _fakes.size();
	}
	
	/**
	 * @param creature a creature
	 * @return {@code true} if {@code creature} is one of its members
	 */
	public boolean isMember(Creature creature)
	{
		if (creature == null)
		{
			return false;
		}
		
		if (creature.isPlayer())
		{
			return getPlayers().contains(creature.asPlayer());
		}
		
		return creature.isNpc() && _fakes.contains(creature);
	}
	
	/**
	 * @return who the others follow: the leader of the host's {@link Party} (or the host), or the first living fake player of a party of fake players only
	 */
	public Creature getLeader()
	{
		if (_host != null)
		{
			final Party party = _host.getParty();
			return party != null ? party.getLeader() : _host;
		}
		
		for (Npc fake : _fakes)
		{
			if (!fake.isDead())
			{
				return fake;
			}
		}
		
		return _fakes.isEmpty() ? null : _fakes.get(0);
	}
	
	/**
	 * @return the object id of the leader the party window shows
	 */
	public int getLeaderObjectId()
	{
		final Creature leader = getLeader();
		return leader != null ? leader.getObjectId() : 0;
	}
}
