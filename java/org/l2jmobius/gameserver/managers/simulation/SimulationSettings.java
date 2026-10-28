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

import org.l2jmobius.gameserver.ai.FakeBrain;

/**
 * What //Simulate was asked for.
 * @param size the party size of every team
 * @param level the level of every fake player
 * @param mirror {@code true} if every team gets the same builds, gear and personalities
 * @param seed the seed of the teams: the same seed gives the same teams
 * @param rounds how many rounds to play
 * @param timeLimit the time limit of a round, in seconds
 * @param starterId the object id of the admin that started it
 * @param brains the AI of each team, in point order, as letters separated by colons ("A", "A:B"): the last one goes for the teams after it (see {@link FakeBrain})
 * @param swap {@code true} for an A/B test: the builds are mirrored, the brains move one point on every round, and a new composition comes every 2 rounds (each one is played from both sides)
 */
public record SimulationSettings(int size, int level, boolean mirror, long seed, int rounds, int timeLimit, int starterId, String brains, boolean swap)
{
	/**
	 * @param text brain letters separated by colons, spaces or commas ("A:B", "ab" is not one)
	 * @return the brains in the canonical form ("A:B"), {@code null} if the text is empty or names an unknown brain
	 */
	public static String normalizeBrains(String text)
	{
		if ((text == null) || text.isBlank())
		{
			return null;
		}

		final List<String> letters = new ArrayList<>();
		for (String part : text.split("[:,\\s]+"))
		{
			if (part.isEmpty())
			{
				continue;
			}
			final FakeBrain brain = FakeBrain.parse(part);
			if (brain == null)
			{
				return null;
			}
			letters.add(brain.name());
		}
		return letters.isEmpty() ? null : String.join(":", letters);
	}

	/**
	 * @return {@code true} if every team gets the same builds ({@link #mirror} or an A/B test)
	 */
	public boolean mirrored()
	{
		return mirror || swap;
	}

	/**
	 * @param round a round (from 1)
	 * @return the seed its teams are made from: the seed, or in an A/B test a new one every 2 rounds (the first 2 rounds keep the seed itself)
	 */
	public long compositionSeed(int round)
	{
		final int composition = (round - 1) / 2;
		// Scrambled: java.util.Random gives the same first number for seeds next to each other (seed + 1, seed + 2... all drew the same tank).
		return !swap || (composition == 0) ? seed : mix(seed + composition);
	}

	/**
	 * @param value a number
	 * @return a well spread number made from it (SplitMix64)
	 */
	static long mix(long value)
	{
		long z = value + 0x9E3779B97F4A7C15L;
		z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
		z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
		return z ^ (z >>> 31);
	}

	/**
	 * @param teamIndex the team, in point order (from 0)
	 * @param teamCount how many teams the round has
	 * @param round the round (from 1)
	 * @return the AI the team runs that round
	 */
	public FakeBrain brainOf(int teamIndex, int teamCount, int round)
	{
		final String[] letters = (brains != null) && !brains.isEmpty() ? brains.split(":") : new String[]
		{
			FakeBrain.A.name()
		};
		final int index = swap && (teamCount > 0) ? (teamIndex + round - 1) % teamCount : teamIndex;
		final FakeBrain brain = FakeBrain.parse(letters[Math.min(index, letters.length - 1)]);
		return brain != null ? brain : FakeBrain.A;
	}
}
