/*
 * This file is part of the L2J Mobius project.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>.
 */
package org.l2jmobius.gameserver.managers.simulation;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import org.l2jmobius.gameserver.ai.FakeBrain;
import org.l2jmobius.gameserver.model.actor.holders.npc.FakePlayerParty;

/**
 * One party of a simulation run, spawned at one point. Its id is the number of its point.
 */
public class SimulationTeam
{
	private final int _id;
	private final SimulationPoint _point;
	private final List<SimRole> _roles;
	private final FakeBrain _brain;
	private final List<SimulationMember> _members = new CopyOnWriteArrayList<>();
	private volatile FakePlayerParty _party;
	/** The other parties of an army of several (a race war, see {@link SimulationWar}): the first one is {@link #getParty}. */
	private final List<FakePlayerParty> _moreParties = new CopyOnWriteArrayList<>();
	private volatile boolean _firstDeathSeen;

	public SimulationTeam(int id, SimulationPoint point, List<SimRole> roles, FakeBrain brain)
	{
		_id = id;
		_point = point;
		_roles = roles;
		_brain = brain;
	}

	public int getId()
	{
		return _id;
	}

	public SimulationPoint getPoint()
	{
		return _point;
	}

	/**
	 * @return the AI its fake players run
	 */
	public FakeBrain getBrain()
	{
		return _brain;
	}

	/**
	 * @return the slots of the team, in spawn order
	 */
	public List<SimRole> getRoles()
	{
		return _roles;
	}

	/**
	 * @return its members, the ones replaced by the admin and the admin included
	 */
	public List<SimulationMember> getMembers()
	{
		return _members;
	}

	public FakePlayerParty getParty()
	{
		return _party;
	}

	public void setParty(FakePlayerParty party)
	{
		_party = party;
	}

	/**
	 * Adds a party to the team: the first one becomes {@link #getParty}, the next ones make it an army of several parties.
	 * @param party the party
	 */
	public void addParty(FakePlayerParty party)
	{
		if (_party == null)
		{
			_party = party;
		}
		else
		{
			_moreParties.add(party);
		}
	}

	/**
	 * @return every party of the team, its first one first
	 */
	public List<FakePlayerParty> getParties()
	{
		final List<FakePlayerParty> parties = new ArrayList<>(1 + _moreParties.size());
		final FakePlayerParty party = _party;
		if (party != null)
		{
			parties.add(party);
		}
		parties.addAll(_moreParties);
		return parties;
	}

	/**
	 * @param member a member
	 * @return the party of the team that holds it, {@code null} if none
	 */
	public FakePlayerParty getPartyOf(SimulationMember member)
	{
		final FakePlayerParty party = _party;
		if ((party != null) && party.isMember(member.getCreature()))
		{
			return party;
		}
		for (FakePlayerParty other : _moreParties)
		{
			if (other.isMember(member.getCreature()))
			{
				return other;
			}
		}
		return null;
	}

	/**
	 * @return {@code true} if a member of the team is alive (its party still holds it)
	 */
	public boolean isAlive()
	{
		for (SimulationMember member : _members)
		{
			if (isActive(member) && member.isAlive())
			{
				return true;
			}
		}
		return false;
	}

	/**
	 * @param member a member
	 * @return {@code true} if it is still part of the team (not replaced by the admin, and the admin hasn't left)
	 */
	public boolean isActive(SimulationMember member)
	{
		return getPartyOf(member) != null;
	}

	/**
	 * @return the HP and CP its living members have left, as a ratio of their maximum (who wins at the time limit)
	 */
	public double getHealthLeft()
	{
		double current = 0;
		double max = 0;
		for (SimulationMember member : _members)
		{
			if (!isActive(member))
			{
				continue;
			}

			max += member.getCreature().getMaxHp() + member.getCreature().getMaxCp();
			if (member.isAlive())
			{
				current += member.getCreature().getCurrentHp() + member.getCreature().getCurrentCp();
			}
		}
		return max > 0 ? current / max : 0;
	}

	/**
	 * Notes the first death of the team.
	 * @return {@code true} if this is it
	 */
	public synchronized boolean takeFirstDeath()
	{
		if (_firstDeathSeen)
		{
			return false;
		}
		_firstDeathSeen = true;
		return true;
	}
}
