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

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import org.l2jmobius.gameserver.model.actor.holders.npc.FakePlayerParty;

/**
 * One party of a simulation run, spawned at one point. Its id is the number of its point.
 */
public class SimulationTeam
{
	private final int _id;
	private final SimulationPoint _point;
	private final List<SimRole> _roles;
	private final List<SimulationMember> _members = new CopyOnWriteArrayList<>();
	private volatile FakePlayerParty _party;
	private volatile boolean _firstDeathSeen;

	public SimulationTeam(int id, SimulationPoint point, List<SimRole> roles)
	{
		_id = id;
		_point = point;
		_roles = roles;
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
		final FakePlayerParty party = _party;
		return (party != null) && party.isMember(member.getCreature());
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
