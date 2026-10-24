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
 * What //Simulate was asked for.
 * @param size the party size of every team
 * @param level the level of every fake player
 * @param mirror {@code true} if every team gets the same builds, gear and personalities
 * @param seed the seed of the teams: the same seed gives the same teams
 * @param rounds how many rounds to play
 * @param timeLimit the time limit of a round, in seconds
 * @param starterId the object id of the admin that started it
 */
public record SimulationSettings(int size, int level, boolean mirror, long seed, int rounds, int timeLimit, int starterId)
{
}
