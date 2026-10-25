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
import java.util.Random;

import org.l2jmobius.gameserver.data.xml.FakePlayerPvpData;
import org.l2jmobius.gameserver.model.actor.holders.npc.FakePlayerPvpBuild;

/**
 * Picks balanced teams for a simulation. Every team of a run gets the same slots: a party of 5 is 1 tank, 1 melee, 2 ranged (an archer and a nuker) and a healer, and the other sizes take the slots in an order that keeps them as balanced as they can be.
 */
public class SimulationComposer
{
	/** The slots, in the order a party fills them (sizes 2 to 9). */
	private static final SimRole[] ORDER =
	{
		SimRole.TANK,
		SimRole.ARCHER,
		SimRole.HEALER,
		SimRole.MELEE,
		SimRole.NUKER,
		SimRole.BUFFER,
		SimRole.MELEE,
		SimRole.NUKER,
		SimRole.HEALER
	};

	/** The largest party: a party holds 9 members. */
	public static final int MAX_SIZE = ORDER.length;

	private SimulationComposer()
	{
	}

	/**
	 * @param size the party size, 1 to {@link #MAX_SIZE}
	 * @return the slots of a party of that size, the leader's first (a lone member is a melee)
	 */
	public static List<SimRole> getRoles(int size)
	{
		final List<SimRole> roles = new ArrayList<>(size);
		if (size == 1)
		{
			roles.add(SimRole.MELEE);
			return roles;
		}

		for (int i = 0; i < Math.min(size, ORDER.length); i++)
		{
			roles.add(ORDER[i]);
		}
		return roles;
	}

	/**
	 * @param role a slot
	 * @param random the random source of the run (seeded)
	 * @return a build for that slot, {@code null} if none of its builds exists
	 */
	public static FakePlayerPvpBuild pick(SimRole role, Random random)
	{
		final List<FakePlayerPvpBuild> builds = new ArrayList<>();
		for (String name : role.getBuilds())
		{
			final FakePlayerPvpBuild build = FakePlayerPvpData.getInstance().getBuild(name);
			if (build != null)
			{
				builds.add(build);
			}
		}
		return builds.isEmpty() ? null : builds.get(random.nextInt(builds.size()));
	}

	/**
	 * @param name what an admin typed (tank, melee, ranged, healer, buffer, archer, nuker...)
	 * @return the matching slots, the first one preferred, empty if none
	 */
	public static List<SimRole> parseGroup(String name)
	{
		final List<SimRole> roles = new ArrayList<>();
		for (SimRole role : SimRole.values())
		{
			if (role.name().equalsIgnoreCase(name) || role.getGroup().equalsIgnoreCase(name) || (name.equalsIgnoreCase("dps") && (role.isRanged() || (role == SimRole.MELEE))) || (name.equalsIgnoreCase("support") && ((role == SimRole.HEALER) || (role == SimRole.BUFFER))))
			{
				roles.add(role);
			}
		}
		return roles;
	}
}
