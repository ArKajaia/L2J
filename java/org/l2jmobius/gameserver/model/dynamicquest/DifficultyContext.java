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

import java.util.List;

import org.l2jmobius.gameserver.config.custom.DynamicQuestConfig;
import org.l2jmobius.gameserver.data.xml.ExperienceData;
import org.l2jmobius.gameserver.model.actor.Player;

/**
 * How hard a rolled quest is: its level (the party's, moved by the tier), the party's size and the tier. Every count, champion tier, meter and time limit of the quest comes from it.
 * @author Mobius
 */
public class DifficultyContext
{
	private final int _questLevel;
	private final int _partySize;
	private final QuestTier _tier;

	public DifficultyContext(int questLevel, int partySize, QuestTier tier)
	{
		_questLevel = questLevel;
		_partySize = Math.max(1, partySize);
		_tier = tier;
	}

	/**
	 * @param leader the player taking the quest
	 * @param members the party members taking part (the leader included)
	 * @param tier the tier
	 * @return the context: members too far from the leader's level don't count for the level nor the size
	 */
	public static DifficultyContext of(Player leader, List<Player> members, QuestTier tier)
	{
		final int gap = DynamicQuestConfig.MAX_LEVEL_GAP * 2;
		int sum = 0;
		int counted = 0;
		for (Player member : members)
		{
			if (Math.abs(member.getLevel() - leader.getLevel()) <= gap)
			{
				sum += member.getLevel();
				counted++;
			}
		}
		final int average = counted > 0 ? Math.round((float) sum / counted) : leader.getLevel();
		return new DifficultyContext(clampLevel(average + tier.getLevelOffset()), Math.max(1, counted), tier);
	}

	public static int clampLevel(int level)
	{
		return Math.max(1, Math.min(ExperienceData.getInstance().getMaxLevel() - 1, level));
	}

	public int getQuestLevel()
	{
		return _questLevel;
	}

	public int getPartySize()
	{
		return _partySize;
	}

	public QuestTier getTier()
	{
		return _tier;
	}

	/**
	 * @param base a count at the Normal tier for one player
	 * @param scaleWithParty {@code true} to scale it with the party's size
	 * @return the count for this quest (at least 1)
	 */
	public int count(int base, boolean scaleWithParty)
	{
		final double party = scaleWithParty ? 1 + (0.6 * (_partySize - 1)) : 1;
		return Math.max(1, (int) Math.round(base * _tier.getCountMultiplier() * party));
	}

	/**
	 * @param bonus tiers added by the objective
	 * @return the champion tier of the quest's own monsters (0-3)
	 */
	public int championTier(int bonus)
	{
		final int party = _partySize >= 7 ? 2 : (_partySize >= 4 ? 1 : 0);
		return Math.max(0, Math.min(3, _tier.getChampionBonus() + party + bonus));
	}

	/**
	 * @return how much longer escort and defend meters last
	 */
	public double meterMultiplier()
	{
		return 1 + (0.5 * (_partySize - 1));
	}

	/**
	 * @param base a time limit at the Normal tier in seconds
	 * @return the time limit at this tier
	 */
	public int timeLimit(int base)
	{
		return base <= 0 ? 0 : Math.max(60, (int) Math.round(base * _tier.getTimeMultiplier()));
	}
}
