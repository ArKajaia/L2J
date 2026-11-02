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
package org.l2jmobius.gameserver.model.dynamicquest;

import java.util.ArrayList;
import java.util.List;

import org.l2jmobius.gameserver.model.actor.Player;

/**
 * What the world remembers of a character's dynamic quests, kept in player variables:
 * <ul>
 * <li><b>Standing in a town</b>: every quest done for a town raises it (more at harder tiers). Its rank (Stranger, Known, Trusted, Honored, Hero) adds to the rewards of that town's quests.</li>
 * <li><b>Regulars</b>: a town npc whose requests a character has done before knows them, and pays a little more each time.</li>
 * </ul>
 * @author Mobius
 */
public final class QuestStanding
{
	private static final String TOWN_VAR = "DQ_REP_";
	private static final String REGULAR_VAR = "DQ_REG_";

	/** Points of standing for a rank, its name and the reward bonus in percent. */
	private static final int[] RANK_POINTS =
	{
		0,
		5,
		15,
		30,
		60
	};
	private static final String[] RANK_NAMES =
	{
		"Stranger",
		"Known",
		"Trusted",
		"Honored",
		"Hero of {town}"
	};
	private static final int[] RANK_BONUS =
	{
		0,
		3,
		6,
		9,
		12
	};

	/** Reward bonus per favour done for the same requester before, in percent, and its cap. */
	private static final int REGULAR_BONUS = 5;
	private static final int REGULAR_MAX_BONUS = 25;

	private QuestStanding()
	{
	}

	private static String key(String name)
	{
		return name.replaceAll("[^A-Za-z0-9]", "");
	}

	public static int getTownPoints(Player player, String town)
	{
		return town != null ? player.getVariables().getInt(TOWN_VAR + key(town), 0) : 0;
	}

	public static int getRank(int points)
	{
		int rank = 0;
		for (int i = 0; i < RANK_POINTS.length; i++)
		{
			if (points >= RANK_POINTS[i])
			{
				rank = i;
			}
		}
		return rank;
	}

	public static String getRankName(int points, String town)
	{
		return RANK_NAMES[getRank(points)].replace("{town}", town != null ? town : "the town");
	}

	/**
	 * @param player a player
	 * @param town a town
	 * @return a line of the player's standing there ("Trusted (16), +6% rewards")
	 */
	public static String describeTown(Player player, String town)
	{
		final int points = getTownPoints(player, town);
		final int rank = getRank(points);
		final String next = rank < (RANK_POINTS.length - 1) ? ", " + (RANK_POINTS[rank + 1] - points) + " more to " + getRankName(RANK_POINTS[rank + 1], town) : "";
		return getRankName(points, town) + " (" + points + ")" + (RANK_BONUS[rank] > 0 ? ", +" + RANK_BONUS[rank] + "% rewards" : "") + next;
	}

	public static int getFavours(Player player, String requester)
	{
		return requester != null ? player.getVariables().getInt(REGULAR_VAR + key(requester), 0) : 0;
	}

	/**
	 * @param favours favours done before
	 * @return the requester's bonus in percent
	 */
	public static int getRegularBonus(int favours)
	{
		return Math.min(REGULAR_MAX_BONUS, favours * REGULAR_BONUS);
	}

	/**
	 * @param quest a quest
	 * @return the town it was done for (the board it was taken at, or carried by a follow-up), {@code null} for a herald's or a world quest
	 */
	public static String getTown(QuestInstance quest)
	{
		return quest.getContext("town", null);
	}

	/**
	 * @param quest a quest
	 * @return the town npc it was done for, {@code null} if it isn't a request
	 */
	public static String getRequester(QuestInstance quest)
	{
		return quest.getTemplate().getRequester() != null ? quest.getContext("requester", null) : null;
	}

	/**
	 * @param player a participant
	 * @param quest the quest
	 * @return what multiplies the player's reward: standing in the quest's town and favours for its requester
	 */
	public static double getRewardFactor(Player player, QuestInstance quest)
	{
		final int townBonus = RANK_BONUS[getRank(getTownPoints(player, getTown(quest)))];
		final int regularBonus = getRegularBonus(getFavours(player, getRequester(quest)));
		return 1 + ((townBonus + regularBonus) / 100.0);
	}

	/**
	 * Remembers a quest the player was rewarded for.
	 * @param player the player
	 * @param quest the quest
	 * @return what the player is told
	 */
	public static List<String> recordSuccess(Player player, QuestInstance quest)
	{
		final List<String> lines = new ArrayList<>();
		final String town = getTown(quest);
		if (town != null)
		{
			final int before = getTownPoints(player, town);
			final int gain = Math.max(1, quest.getDifficulty().getTier().ordinal());
			final int after = before + gain;
			player.getVariables().set(TOWN_VAR + key(town), after);
			if (getRank(after) > getRank(before))
			{
				lines.add("Your standing in " + town + " rises: you are now " + getRankName(after, town) + ". Its quests pay " + RANK_BONUS[getRank(after)] + "% more.");
			}
			else
			{
				lines.add("Standing in " + town + ": " + describeTown(player, town) + ".");
			}
		}
		final String requester = getRequester(quest);
		if (requester != null)
		{
			final int favours = getFavours(player, requester) + 1;
			player.getVariables().set(REGULAR_VAR + key(requester), favours);
			if (favours >= 2)
			{
				lines.add(requester + " counts on you now: " + favours + " favours done. Their requests pay " + getRegularBonus(favours) + "% more.");
			}
		}
		return lines;
	}
}
