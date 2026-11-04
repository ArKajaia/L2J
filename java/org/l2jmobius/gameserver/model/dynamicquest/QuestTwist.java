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
import java.util.Random;

/**
 * Something that makes one offer of a quest unlike the others: more foes, tougher foes, less time, a generous patron... Rolled from the offer's seed, so the board shows it before the
 * quest is taken, and kept in the quest's context ("twist"). A template lists the twists it allows with {@code twists="..."} (ALL by default, NONE for none).
 * @author Mobius
 */
public enum QuestTwist
{
	REINFORCED("Reinforced", "99CCFF", "The enemy is stronger in number than the notice said.", 1.35, 0, 1.0, 1.2, 1.0),
	VETERANS("Veterans", "FFCC66", "Seasoned foes: the quest's monsters are champions of a tier higher.", 1.0, 1, 1.0, 1.25, 1.0),
	URGENT("Urgent", "FF9966", "No time to lose: a quarter less time, a fifth more pay.", 1.0, 0, 0.75, 1.2, 1.0),
	GENEROUS("Generous Patron", "FFDD55", "Someone wealthy wants this done: half again the adena.", 1.0, 0, 1.0, 1.0, 1.5),
	QUIET("Quiet Roads", "99CC99", "The land is calmer than usual: fewer foes, a little less pay.", 0.75, 0, 1.0, 0.9, 1.0),
	NIGHT_RAID("Night Raid", "FF6666", "They strike in force: more foes, and tougher, for much better pay.", 1.25, 1, 1.0, 1.4, 1.0);

	/** Share of offers (percent) that come with a twist. */
	private static final int CHANCE = 40;

	private final String _displayName;
	private final String _color;
	private final String _description;
	private final double _countMultiplier;
	private final int _championBonus;
	private final double _timeMultiplier;
	private final double _rewardMultiplier;
	private final double _adenaMultiplier;

	private QuestTwist(String displayName, String color, String description, double countMultiplier, int championBonus, double timeMultiplier, double rewardMultiplier, double adenaMultiplier)
	{
		_displayName = displayName;
		_color = color;
		_description = description;
		_countMultiplier = countMultiplier;
		_championBonus = championBonus;
		_timeMultiplier = timeMultiplier;
		_rewardMultiplier = rewardMultiplier;
		_adenaMultiplier = adenaMultiplier;
	}

	public String getDisplayName()
	{
		return _displayName;
	}

	public String getColor()
	{
		return _color;
	}

	public String getDescription()
	{
		return _description;
	}

	/**
	 * @return what multiplies the quest's monster and item counts
	 */
	public double getCountMultiplier()
	{
		return _countMultiplier;
	}

	/**
	 * @return champion tiers added to the quest's own monsters (the tier's caps still hold)
	 */
	public int getChampionBonus()
	{
		return _championBonus;
	}

	public double getTimeMultiplier()
	{
		return _timeMultiplier;
	}

	/**
	 * @return what multiplies the whole reward
	 */
	public double getRewardMultiplier()
	{
		return _rewardMultiplier;
	}

	/**
	 * @return what multiplies the adena on top
	 */
	public double getAdenaMultiplier()
	{
		return _adenaMultiplier;
	}

	/**
	 * @return the twist as a line of html
	 */
	public String describe()
	{
		return "<font color=\"" + _color + "\">" + _displayName + "</font>: " + _description;
	}

	/**
	 * @param template the quest's template
	 * @param seed the offer's seed
	 * @return the offer's twist, {@code null} for none
	 */
	public static QuestTwist roll(QuestTemplate template, long seed)
	{
		final List<QuestTwist> allowed = template.getTwists();
		if (allowed.isEmpty())
		{
			return null;
		}
		final Random random = new Random(seed ^ 0x7E157A1D5EEDL);
		if (random.nextInt(100) >= CHANCE)
		{
			return null;
		}
		return allowed.get(random.nextInt(allowed.size()));
	}

	/**
	 * @param value a twist list of the data ("ALL", "NONE", "URGENT,GENEROUS")
	 * @return the twists
	 */
	public static List<QuestTwist> parseList(String value)
	{
		final List<QuestTwist> twists = new ArrayList<>();
		if ((value == null) || value.trim().isEmpty() || value.trim().equalsIgnoreCase("ALL"))
		{
			for (QuestTwist twist : values())
			{
				twists.add(twist);
			}
			return twists;
		}
		for (String name : value.split(","))
		{
			final QuestTwist twist = parse(name);
			if (twist != null)
			{
				twists.add(twist);
			}
		}
		return twists;
	}

	/**
	 * @param name a twist's name, may be {@code null}
	 * @return the twist, {@code null} if none
	 */
	public static QuestTwist parse(String name)
	{
		if (name != null)
		{
			for (QuestTwist twist : values())
			{
				if (twist.name().equalsIgnoreCase(name.trim()))
				{
					return twist;
				}
			}
		}
		return null;
	}

	/**
	 * @param context a quest's saved context ("key=value;key=value;")
	 * @return the twist it holds, {@code null} if none
	 */
	public static QuestTwist fromContext(String context)
	{
		if (context == null)
		{
			return null;
		}
		for (String pair : context.split(";"))
		{
			if (pair.startsWith("twist="))
			{
				return parse(pair.substring(6));
			}
		}
		return null;
	}
}
