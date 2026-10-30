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
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

import org.l2jmobius.gameserver.ai.FakePartyBrain;
import org.l2jmobius.gameserver.model.actor.Creature;
import org.l2jmobius.gameserver.model.actor.Npc;
import org.l2jmobius.gameserver.model.actor.Player;
import org.l2jmobius.gameserver.model.groups.Party;
import org.l2jmobius.gameserver.model.skill.Skill;

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
	/** The party brain its fake players share (their AI's party tier). */
	private final FakePartyBrain _brain = new FakePartyBrain(this);
	private final List<Npc> _fakes = new CopyOnWriteArrayList<>();
	// A team of a party-vs-party simulation (see FakeSimulationManager): it stays together until the run ends, with an admin that joined it as a guest member, who may lead it.
	private volatile boolean _simulation;
	// Kept together by whoever made it (a simulation team, a Shadow Raid warband): it doesn't break up as its members die or drift apart, and its leader doesn't log off for all of them.
	private volatile boolean _keptTogether;
	private volatile Player _guest;
	private volatile boolean _guestLeads;
	// The enemy its fake players were called to kill together (an enemy healer, one about to fall), and until when.
	private volatile Creature _focus;
	private volatile long _focusUntil;
	// Since when the current call is on, and how much HP and CP its target had then (a call that doesn't bring it down is dropped).
	private volatile long _focusSince;
	private volatile double _focusStartHealth;
	// Enemies whose call was dropped because they were healed through: object id -> until when they aren't called again.
	private final Map<Integer, Long> _droppedCalls = new ConcurrentHashMap<>();

	/**
	 * @param host the player that invited the fake players, {@code null} for a party of fake players only
	 */
	public FakePlayerParty(Player host)
	{
		_host = host;
	}
	
	/**
	 * @return the party brain its fake players share
	 */
	public FakePartyBrain getBrain()
	{
		return _brain;
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
	 * @return {@code true} for a team of a party-vs-party simulation: it doesn't break up when members die
	 */
	public boolean isSimulation()
	{
		return _simulation;
	}

	public void setSimulation(boolean simulation)
	{
		_simulation = simulation;
	}

	/**
	 * @return {@code true} for a party of fake players kept together by whoever made it (a simulation team, a Shadow Raid warband): it stays a party until they are all gone, a member far from its leader runs back instead of leaving, and its leader doesn't log off for
	 *         all of them
	 */
	public boolean isKeptTogether()
	{
		return _keptTogether || _simulation;
	}

	public void setKeptTogether(boolean keptTogether)
	{
		_keptTogether = keptTogether;
	}

	/**
	 * @param now the current time
	 * @return the enemy the party was called to kill together, {@code null} if none (or it is dead, or the call is old)
	 */
	public Creature getFocus(long now)
	{
		final Creature focus = _focus;
		return (focus != null) && (now < _focusUntil) && !focus.isAlikeDead() ? focus : null;
	}

	/**
	 * Calls an enemy for the party to kill together.
	 * @param focus the enemy
	 * @param now the current time
	 * @param until until when the call holds (calling it again extends it)
	 */
	public void setFocus(Creature focus, long now, long until)
	{
		if ((focus != _focus) || (now >= _focusUntil))
		{
			_focusSince = now;
			_focusStartHealth = getHealth(focus);
		}
		_focus = focus;
		_focusUntil = until;
	}

	/**
	 * Drops the call when it doesn't work: after {@code after} ms its target lost less than {@code minLoss} of its HP and CP (a healer heals it through). The target isn't called again for {@code block} ms.
	 * @param now the current time
	 * @param after how long a call gets to work
	 * @param minLoss the ratio of HP and CP its target must have lost by then
	 * @param block how long the target isn't called again
	 * @return the target of the dropped call, {@code null} if the call goes on (or there is none)
	 */
	public Creature dropFailedFocus(long now, long after, double minLoss, long block)
	{
		final Creature focus = getFocus(now);
		if ((focus == null) || ((now - _focusSince) < after) || ((_focusStartHealth - getHealth(focus)) >= minLoss))
		{
			return null;
		}

		synchronized (this)
		{
			if (_focus != focus)
			{
				return null;
			}
			_focus = null;
			_focusUntil = 0;
		}
		_droppedCalls.put(focus.getObjectId(), now + block);
		return focus;
	}

	/**
	 * @param creature an enemy
	 * @param now the current time
	 * @return {@code true} if a call on it was dropped lately (healed through): it isn't called again for a while
	 */
	public boolean isCallDropped(Creature creature, long now)
	{
		final Long until = _droppedCalls.get(creature.getObjectId());
		if (until == null)
		{
			return false;
		}
		if (now >= until)
		{
			_droppedCalls.remove(creature.getObjectId());
			return false;
		}
		return true;
	}

	/**
	 * @return how much HP and CP the current call's target had when it was called, as a ratio of their maximum
	 */
	public double getFocusStartHealth()
	{
		return _focusStartHealth;
	}

	private static double getHealth(Creature creature)
	{
		return (creature.getCurrentHp() + creature.getCurrentCp()) / Math.max(1, creature.getMaxHp() + creature.getMaxCp());
	}

	/**
	 * @return the admin that joined this party of fake players for a simulation, {@code null} if none
	 */
	public Player getGuest()
	{
		return _guest;
	}

	/**
	 * @param guest the admin that joins (a player member of a party of fake players), {@code null} when it leaves
	 * @param leads {@code true} if the others follow it instead of their fake leader
	 */
	public void setGuest(Player guest, boolean leads)
	{
		_guest = guest;
		_guestLeads = (guest != null) && leads;
	}

	/**
	 * @return the players of the party: the host and the members of its {@link Party}, the guest of a party of fake players, none for a party of fake players only
	 */
	public List<Player> getPlayers()
	{
		if (_host == null)
		{
			final Player guest = _guest;
			return guest != null ? Collections.singletonList(guest) : Collections.emptyList();
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

		final Player guest = _guest;
		if ((guest != null) && _guestLeads)
		{
			return guest;
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
	
	/** A leader casting something this long (a summon...) is busy: the party doesn't wait for it. Fight casts (Entangle, Drain Health, heals) are shorter. */
	private static final int LONG_CAST = 5000;

	/**
	 * @return who the fake players follow right now: the {@link #getLeader() leader}, unless that is a fake player that can't lead for the moment (stunned, rooted, asleep, under Ultimate Defense, or casting something long), then the first fake player that can. Players
	 *         (a host, a guest that leads) always lead. The party window, the log-off and the like go on with {@link #getLeader()}.
	 */
	public Creature getActiveLeader()
	{
		final Creature leader = getLeader();
		if ((_host != null) || (leader == null) || leader.isPlayer() || !isBusy(leader))
		{
			return leader;
		}

		for (Npc fake : _fakes)
		{
			if (!fake.isDead() && fake.isSpawned() && !isBusy(fake))
			{
				return fake;
			}
		}
		return leader;
	}

	/**
	 * @param creature a party member
	 * @return {@code true} if it can't lead for the moment: it can't move, or it casts something long
	 */
	private static boolean isBusy(Creature creature)
	{
		if (creature.isMovementDisabled())
		{
			return true;
		}

		final Skill skill = creature.getLastSkillCast();
		return creature.isCastingNow() && (skill != null) && (skill.getHitTime() >= LONG_CAST);
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
