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

/**
 * The difficulty a dynamic quest is taken at. It moves the quest's level, the number and strength of its monsters, its time limits and its reward.
 * @author Mobius
 */
public enum QuestTier
{
	EASY("Easy", "99CC99", -3, 0.7, 0, 0.75, 1.3),
	NORMAL("Normal", "FFFFFF", 0, 1.0, 0, 1.0, 1.0),
	HARD("Hard", "FFCC33", 2, 1.3, 1, 1.35, 0.9),
	ELITE("Elite", "FF6666", 4, 1.6, 2, 1.8, 0.8);

	private final String _displayName;
	private final String _color;
	private final int _levelOffset;
	private final double _countMultiplier;
	private final int _championBonus;
	private final double _rewardMultiplier;
	private final double _timeMultiplier;

	private QuestTier(String displayName, String color, int levelOffset, double countMultiplier, int championBonus, double rewardMultiplier, double timeMultiplier)
	{
		_displayName = displayName;
		_color = color;
		_levelOffset = levelOffset;
		_countMultiplier = countMultiplier;
		_championBonus = championBonus;
		_rewardMultiplier = rewardMultiplier;
		_timeMultiplier = timeMultiplier;
	}

	public String getDisplayName()
	{
		return _displayName;
	}

	public String getColor()
	{
		return _color;
	}

	/**
	 * @return levels added to the party's level to make the quest's level
	 */
	public int getLevelOffset()
	{
		return _levelOffset;
	}

	public double getCountMultiplier()
	{
		return _countMultiplier;
	}

	/**
	 * @return champion tiers added to the quest's own monsters
	 */
	public int getChampionBonus()
	{
		return _championBonus;
	}

	public double getRewardMultiplier()
	{
		return _rewardMultiplier;
	}

	public double getTimeMultiplier()
	{
		return _timeMultiplier;
	}

	/**
	 * @param name a tier name
	 * @param defaultTier the tier to use when the name is unknown
	 * @return the tier
	 */
	public static QuestTier parse(String name, QuestTier defaultTier)
	{
		if (name != null)
		{
			for (QuestTier tier : values())
			{
				if (tier.name().equalsIgnoreCase(name.trim()))
				{
					return tier;
				}
			}
		}
		return defaultTier;
	}
}
