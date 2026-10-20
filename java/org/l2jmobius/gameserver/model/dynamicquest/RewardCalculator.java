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

import org.l2jmobius.commons.util.Rnd;
import org.l2jmobius.gameserver.config.custom.DynamicQuestConfig;
import org.l2jmobius.gameserver.data.xml.ExperienceData;
import org.l2jmobius.gameserver.data.xml.ItemData;
import org.l2jmobius.gameserver.model.item.ItemTemplate;

/**
 * Works out what a dynamic quest pays a participant.
 * <p>
 * EXP is a share of the EXP needed to pass the quest's level, so it pays the same effort at every level; SP follows the EXP and adena the level. All of it is multiplied by the tier, the optional objectives done and the follow-up chain (capped by
 * DynamicQuestMaxRewardMultiplier), then by the participant's own factors: repeats of the quest today, its level against the quest's, and its contribution to a world quest.
 * </p>
 * @author Mobius
 */
public final class RewardCalculator
{
	public static final int ADENA_ID = 57;

	/**
	 * What one participant gets.
	 */
	public static class Payout
	{
		public long _exp;
		public long _sp;
		public long _adena;
		public final List<long[]> _items = new ArrayList<>();
		public double _multiplier;

		public boolean isEmpty()
		{
			return (_exp <= 0) && (_sp <= 0) && (_adena <= 0) && _items.isEmpty();
		}
	}

	private RewardCalculator()
	{
	}

	/**
	 * @param quest a quest
	 * @return the quest's own multiplier: tier, optional objectives and chain, capped
	 */
	public static double getQuestMultiplier(QuestInstance quest)
	{
		double multiplier = quest.getDifficulty().getTier().getRewardMultiplier();
		multiplier *= 1 + ((quest.getOptionalDone() * DynamicQuestConfig.OPTIONAL_BONUS_PERCENT) / 100.0);
		multiplier *= 1 + ((quest.getChain() * DynamicQuestConfig.CHAIN_BONUS_PERCENT) / 100.0);
		return Math.min(DynamicQuestConfig.MAX_REWARD_MULTIPLIER, multiplier);
	}

	/**
	 * @param memberLevel a participant's level
	 * @param questLevel the quest's level
	 * @return the participant's share: whole up to DynamicQuestMaxLevelGap levels above the quest, then less, none at twice the gap
	 */
	public static double getLevelShare(int memberLevel, int questLevel)
	{
		final int gap = DynamicQuestConfig.MAX_LEVEL_GAP;
		final int above = memberLevel - questLevel;
		if (above <= gap)
		{
			return 1;
		}
		return Math.max(0, 1 - ((above - gap) / (double) gap));
	}

	/**
	 * @param repeats how many times the account completed the quest today before
	 * @return the repeat factor
	 */
	public static double getRepeatFactor(int repeats)
	{
		final int percent = Math.max(DynamicQuestConfig.MIN_REPEAT_PERCENT, 100 - (repeats * DynamicQuestConfig.REPEAT_PENALTY_PERCENT));
		return percent / 100.0;
	}

	/**
	 * @param quest the quest
	 * @param factor the participant's own factor (repeats, level share, contribution)
	 * @param roll {@code true} to roll the item chances, {@code false} to list every item that could come (a preview)
	 * @return the payout
	 */
	public static Payout compute(QuestInstance quest, double factor, boolean roll)
	{
		return compute(quest.getTemplate().getReward(), quest.getDifficulty().getQuestLevel(), getQuestMultiplier(quest) * factor, roll);
	}

	/**
	 * @param reward the reward definition
	 * @param level the quest's level
	 * @param multiplier everything multiplied together
	 * @param roll {@code true} to roll the item chances
	 * @return the payout
	 */
	public static Payout compute(RewardDefinition reward, int level, double multiplier, boolean roll)
	{
		final Payout payout = new Payout();
		payout._multiplier = multiplier;
		if (multiplier <= 0)
		{
			return payout;
		}

		final ExperienceData experience = ExperienceData.getInstance();
		final int safeLevel = Math.max(1, Math.min(experience.getMaxLevel() - 1, level));
		final long levelExp = Math.max(0, experience.getExpForLevel(safeLevel + 1) - experience.getExpForLevel(safeLevel));
		final double exp = (levelExp * reward.getExpPercent() * multiplier) / 100;
		payout._exp = (long) (exp * DynamicQuestConfig.EXP_RATE);
		payout._sp = (long) (exp * reward.getSpRatio());
		payout._adena = (long) (reward.getAdenaPerLevel() * level * multiplier * DynamicQuestConfig.ADENA_RATE);

		for (RewardEntry entry : reward.getItems())
		{
			if (!entry.isFor(level))
			{
				continue;
			}
			final double chance = entry.getChance() * Math.min(1, multiplier);
			if (roll && (Rnd.get(100.0) >= chance))
			{
				continue;
			}
			final long count = entry.isScaled() ? Math.max(1, Math.round(entry.getCount() * multiplier)) : entry.getCount();
			if (entry.getId() == ADENA_ID)
			{
				payout._adena += count;
			}
			else
			{
				payout._items.add(new long[]
				{
					entry.getId(),
					count,
					(long) Math.min(100, chance)
				});
			}
		}
		return payout;
	}

	/**
	 * @param payout a payout
	 * @param withChances {@code true} to show the chance of items below 100%
	 * @return the payout as html lines
	 */
	public static String describe(Payout payout, boolean withChances)
	{
		final StringBuilder sb = new StringBuilder();
		if (payout._exp > 0)
		{
			line(sb, String.format("%,d", payout._exp) + " EXP, " + String.format("%,d", payout._sp) + " SP");
		}
		if (payout._adena > 0)
		{
			line(sb, String.format("%,d", payout._adena) + " Adena");
		}
		for (long[] item : payout._items)
		{
			final ItemTemplate template = ItemData.getInstance().getTemplate((int) item[0]);
			if (template != null)
			{
				line(sb, template.getName() + (item[1] > 1 ? " x" + item[1] : "") + (withChances && (item[2] < 100) ? " <font color=\"808080\">(" + item[2] + "%)</font>" : ""));
			}
		}
		return sb.length() == 0 ? "Nothing." : sb.toString();
	}

	private static void line(StringBuilder sb, String text)
	{
		sb.append("<font color=\"LEVEL\">-</font> ").append(text).append("<br1>");
	}
}
