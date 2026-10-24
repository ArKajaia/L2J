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

/**
 * A spawn point of simulation teams, saved by an admin with //SetSimulationPoint. Kept in the global variables as "x,y,z,heading,instance".
 * @param id the number of the point (and of the team spawned there)
 * @param x the x
 * @param y the y
 * @param z the z
 * @param heading the heading
 * @param instanceId the instance
 */
public record SimulationPoint(int id, int x, int y, int z, int heading, int instanceId)
{
	/**
	 * @return the point as it is saved
	 */
	public String encode()
	{
		return x + "," + y + "," + z + "," + heading + "," + instanceId;
	}

	/**
	 * @param id the number of the point
	 * @param text the point as it was saved
	 * @return the point, {@code null} if {@code text} is not one
	 */
	public static SimulationPoint decode(int id, String text)
	{
		final String[] parts = text.split(",");
		if (parts.length != 5)
		{
			return null;
		}

		try
		{
			return new SimulationPoint(id, Integer.parseInt(parts[0].trim()), Integer.parseInt(parts[1].trim()), Integer.parseInt(parts[2].trim()), Integer.parseInt(parts[3].trim()), Integer.parseInt(parts[4].trim()));
		}
		catch (NumberFormatException e)
		{
			return null;
		}
	}

	/**
	 * @param other another point
	 * @return the distance between both, on the ground
	 */
	public double distance2D(SimulationPoint other)
	{
		return Math.hypot(x - other.x, y - other.y);
	}
}
