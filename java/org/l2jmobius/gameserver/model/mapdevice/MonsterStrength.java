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
package org.l2jmobius.gameserver.model.mapdevice;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.ToDoubleFunction;
import java.util.logging.Logger;

import org.l2jmobius.gameserver.data.xml.MapDeviceData;
import org.l2jmobius.gameserver.data.xml.NpcData;
import org.l2jmobius.gameserver.model.actor.templates.NpcTemplate;

/**
 * How strong a monster is for its level, so a map can be filled with monsters of the same strength and not only of the same level (a level 84 Batwing Drake has 14 times the HP and 10 times the P. Atk. of a level 84 Sel Mahum Soldier).
 * <ul>
 * <li><b>The standard monster of a level</b>: for every {@link Stat}, the lower quartile of that stat over every {@code type="Monster"} template of the level. Most monsters of a level share the stats of the server's ordinary monster of that level, so the lower quartile is that
 * monster, and the party monsters (Dragon Valley, Antharas' Lair, the Seed of Annihilation, Hellbound) don't pull it up. A level with fewer than {@link #MIN_SAMPLES} templates takes its standard from the levels round it.</li>
 * <li><b>Relative strength of a stat</b>: the monster's stat divided by the standard monster's. 1.0 is ordinary, 14.0 is fourteen times as much.</li>
 * <li><b>Score</b>: the weighted geometric mean of the relative strengths (the weights are the {@code <strength>} element of data/MapDevice.xml). 1.0 is the ordinary monster of the level. A geometric mean, because the stats multiply into how hard a monster is: twice the
 * HP and twice the P. Def. take four times as long to kill.</li>
 * </ul>
 * The scores are worked out once and kept; {@link #clear()} forgets them (after a reload).
 * @author Mobius
 */
public final class MonsterStrength
{
	private static final Logger LOGGER = Logger.getLogger(MonsterStrength.class.getName());

	/** A level needs this many templates with a stat for its own standard of that stat; with fewer it takes it from the levels round it. */
	public static final int MIN_SAMPLES = 5;
	/** Which quantile of a level's stat values is the standard monster. */
	private static final double REFERENCE_QUANTILE = 0.25;
	/** The farthest level looked at for a standard. */
	private static final int MAX_LEVEL_DISTANCE = 10;

	/**
	 * A stat a monster's strength is scored on. The XML name is the attribute of the {@code <strength>} element of data/MapDevice.xml.
	 */
	public enum Stat
	{
		HP("hp", "HP", 2.0, t -> t.getBaseHpMax()),
		P_ATK("pAtk", "P. Atk.", 1.0, NpcTemplate::getBasePAtk),
		M_ATK("mAtk", "M. Atk.", 1.0, NpcTemplate::getBaseMAtk),
		P_DEF("pDef", "P. Def.", 1.0, NpcTemplate::getBasePDef),
		M_DEF("mDef", "M. Def.", 1.0, NpcTemplate::getBaseMDef),
		P_ATK_SPD("pAtkSpd", "Atk. Spd.", 0.5, NpcTemplate::getBasePAtkSpd);
		// No casting speed: the npc data has none, every npc casts at the default 333.

		private final String _xmlName;
		private final String _displayName;
		private final double _defaultWeight;
		private final ToDoubleFunction<NpcTemplate> _getter;

		Stat(String xmlName, String displayName, double defaultWeight, ToDoubleFunction<NpcTemplate> getter)
		{
			_xmlName = xmlName;
			_displayName = displayName;
			_defaultWeight = defaultWeight;
			_getter = getter;
		}

		public String getXmlName()
		{
			return _xmlName;
		}

		public String getDisplayName()
		{
			return _displayName;
		}

		public double getDefaultWeight()
		{
			return _defaultWeight;
		}

		public double getValue(NpcTemplate template)
		{
			return _getter.applyAsDouble(template);
		}
	}

	private static final Stat[] STATS = Stat.values();

	/** level -> the standard value of every stat (NaN: none), worked out from the levels round it where the level has too few templates. */
	private static final Map<Integer, double[]> REFERENCES = new ConcurrentHashMap<>();
	/** npc id -> its score. */
	private static final Map<Integer, Double> SCORES = new ConcurrentHashMap<>();
	/** level -> the lower quartile of every stat over the level's own templates (NaN: too few). Built once. */
	private static volatile Map<Integer, double[]> _ownQuartiles;

	private MonsterStrength()
	{
	}

	/**
	 * @param template a monster
	 * @return its score: 1.0 for the ordinary monster of its level, higher for stronger ones
	 */
	public static double getScore(NpcTemplate template)
	{
		if (template == null)
		{
			return 1.0;
		}
		return SCORES.computeIfAbsent(template.getId(), _ -> computeScore(template));
	}

	/**
	 * @param template a monster
	 * @return the relative strength of each of its stats ({@link Stat} order): its stat divided by the standard monster's of its level; NaN where there is no standard or the monster has no such stat
	 */
	public static double[] getRelativeStats(NpcTemplate template)
	{
		final double[] relative = new double[STATS.length];
		final double[] reference = getReference(template.getLevel());
		for (Stat stat : STATS)
		{
			final double value = stat.getValue(template);
			final double standard = reference[stat.ordinal()];
			relative[stat.ordinal()] = (value > 1) && (standard > 1) ? value / standard : Double.NaN;
		}
		return relative;
	}

	private static double computeScore(NpcTemplate template)
	{
		final double[] relative = getRelativeStats(template);
		final double[] weights = MapDeviceData.getInstance().getStrengthWeights();
		double logSum = 0;
		double weightSum = 0;
		for (Stat stat : STATS)
		{
			final double ratio = relative[stat.ordinal()];
			final double weight = weights[stat.ordinal()];
			if ((weight > 0) && !Double.isNaN(ratio))
			{
				logSum += weight * Math.log(ratio);
				weightSum += weight;
			}
		}
		return weightSum > 0 ? Math.exp(logSum / weightSum) : 1.0;
	}

	/**
	 * @param level a level
	 * @return the standard monster of that level: every stat in {@link Stat} order, NaN where none could be worked out
	 */
	public static double[] getReference(int level)
	{
		return REFERENCES.computeIfAbsent(level, MonsterStrength::computeReference).clone();
	}

	private static double[] computeReference(int level)
	{
		final Map<Integer, double[]> own = getOwnQuartiles();
		final double[] reference = new double[STATS.length];
		for (Stat stat : STATS)
		{
			reference[stat.ordinal()] = referenceOf(own, level, stat.ordinal());
		}
		return reference;
	}

	/**
	 * The level's own lower quartile; else the geometric interpolation of the nearest levels below and above that have one; else the nearest one on one side, carried on at the rate that stat grows by there.
	 */
	private static double referenceOf(Map<Integer, double[]> own, int level, int stat)
	{
		final double self = quartile(own, level, stat);
		if (!Double.isNaN(self))
		{
			return self;
		}

		final int below = nearest(own, level, stat, -1);
		final int above = nearest(own, level, stat, 1);
		if ((below > 0) && (above > 0))
		{
			final double low = quartile(own, below, stat);
			final double high = quartile(own, above, stat);
			return low * Math.pow(high / low, (double) (level - below) / (above - below));
		}

		final int side = below > 0 ? below : above;
		if (side <= 0)
		{
			return Double.NaN;
		}
		final double sideValue = quartile(own, side, stat);
		final int direction = below > 0 ? -1 : 1;
		final int second = nearest(own, side, stat, direction);
		if (second <= 0)
		{
			return sideValue;
		}
		final double perLevel = Math.pow(sideValue / quartile(own, second, stat), 1.0 / (side - second));
		return sideValue * Math.pow(perLevel, level - side);
	}

	/**
	 * @return the nearest level from {@code level} in that direction (-1 below, 1 above), within {@link #MAX_LEVEL_DISTANCE}, with its own quartile of the stat; 0 for none
	 */
	private static int nearest(Map<Integer, double[]> own, int level, int stat, int direction)
	{
		for (int distance = 1; distance <= MAX_LEVEL_DISTANCE; distance++)
		{
			final int other = level + (direction * distance);
			if ((other > 0) && !Double.isNaN(quartile(own, other, stat)))
			{
				return other;
			}
		}
		return 0;
	}

	private static double quartile(Map<Integer, double[]> own, int level, int stat)
	{
		final double[] values = own.get(level);
		return values != null ? values[stat] : Double.NaN;
	}

	private static Map<Integer, double[]> getOwnQuartiles()
	{
		Map<Integer, double[]> own = _ownQuartiles;
		if (own == null)
		{
			synchronized (MonsterStrength.class)
			{
				own = _ownQuartiles;
				if (own == null)
				{
					own = buildOwnQuartiles();
					_ownQuartiles = own;
				}
			}
		}
		return own;
	}

	private static Map<Integer, double[]> buildOwnQuartiles()
	{
		// level -> stat -> values
		final Map<Integer, List<List<Double>>> values = new HashMap<>();
		for (NpcTemplate template : NpcData.getInstance().getTemplates(t -> t.isType("Monster")))
		{
			final List<List<Double>> byStat = values.computeIfAbsent((int) template.getLevel(), _ ->
			{
				final List<List<Double>> lists = new ArrayList<>(STATS.length);
				for (int i = 0; i < STATS.length; i++)
				{
					lists.add(new ArrayList<>());
				}
				return lists;
			});
			for (Stat stat : STATS)
			{
				final double value = stat.getValue(template);
				if (value > 1)
				{
					byStat.get(stat.ordinal()).add(value);
				}
			}
		}

		final Map<Integer, double[]> own = new TreeMap<>();
		for (Map.Entry<Integer, List<List<Double>>> entry : values.entrySet())
		{
			final double[] quartiles = new double[STATS.length];
			for (Stat stat : STATS)
			{
				final List<Double> list = entry.getValue().get(stat.ordinal());
				if (list.size() < MIN_SAMPLES)
				{
					quartiles[stat.ordinal()] = Double.NaN;
					continue;
				}
				Collections.sort(list);
				quartiles[stat.ordinal()] = list.get((int) (REFERENCE_QUANTILE * (list.size() - 1)));
			}
			own.put(entry.getKey(), quartiles);
		}
		LOGGER.info(MonsterStrength.class.getSimpleName() + ": Worked out the standard monster of " + own.size() + " levels.");
		return Collections.unmodifiableMap(own);
	}

	/**
	 * Forgets every standard and score, so they are worked out again (after a reload of the npcs or of data/MapDevice.xml).
	 */
	public static void clear()
	{
		synchronized (MonsterStrength.class)
		{
			_ownQuartiles = null;
		}
		REFERENCES.clear();
		SCORES.clear();
	}

	/**
	 * @param score a score
	 * @return what monsters of that strength are, in words: "ordinary monsters" (below 2), "party monsters" (below 6), "elite party monsters" (below 7.5), else "giants"
	 */
	public static String describe(double score)
	{
		if (score < 2)
		{
			return "ordinary monsters";
		}
		if (score < 6)
		{
			return "party monsters";
		}
		return score < 7.5 ? "elite party monsters" : "giants";
	}

	/**
	 * @param score a score
	 * @return the score with one decimal, as the windows show it
	 */
	public static String format(double score)
	{
		return String.format(java.util.Locale.ROOT, "%.1f", score);
	}
}
