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

import org.l2jmobius.gameserver.model.actor.templates.NpcTemplate;

/**
 * A map monster of a lower level, or a little weaker, lifted to its map's tier (see {@code LiftedMonsterMinLevel} and {@code LiftedMaxStrengthGain} in MapDevice.ini): it is
 * played at a level of the tier, its stats raised as far as that level's standard monster stands above its own level's ({@link MonsterStrength#getLiftFactor}) and, if it was
 * too weak, further until its strength fits the tier, with the experience that goes with it, and the drops of a monster of the tier's own.
 * @param level the level it is played at
 * @param statFactors the multiplier of each {@link MonsterStrength.Stat}, by ordinal
 * @param expFactor the multiplier of its experience and SP
 * @param dropTemplate whose drops it has, {@code null} for its own
 */
public record MapLift(int level, double[] statFactors, double expFactor, NpcTemplate dropTemplate)
{
	/**
	 * @param ownLevel the monster's own level
	 * @param level the level it is played at
	 * @param dropTemplate whose drops it has, {@code null} for its own
	 * @return the lift
	 */
	public static MapLift of(int ownLevel, int level, NpcTemplate dropTemplate)
	{
		return of(ownLevel, level, 1.0, dropTemplate);
	}

	/**
	 * @param ownLevel the monster's own level
	 * @param level the level it is played at
	 * @param strengthGain how much its strength score is raised besides (1 for none): its HP, attack and defence are multiplied so that the weighted score grows by this much
	 *            (attack speed stays: a quicker monster is not a stronger one the way the score reads it), its experience and SP with its HP
	 * @param dropTemplate whose drops it has, {@code null} for its own
	 * @return the lift
	 */
	public static MapLift of(int ownLevel, int level, double strengthGain, NpcTemplate dropTemplate)
	{
		final MonsterStrength.Stat[] stats = MonsterStrength.Stat.values();
		double gain = 1.0;
		if (strengthGain > 1.0)
		{
			// The score is a weighted geometric mean: raising every stat but attack speed by g raises it by g^((W - w_spd) / W).
			final double[] weights = MonsterStrength.getWeights();
			double total = 0;
			for (double weight : weights)
			{
				total += weight;
			}
			final double raised = total - weights[MonsterStrength.Stat.P_ATK_SPD.ordinal()];
			gain = raised > 0 ? Math.pow(strengthGain, total / raised) : 1.0;
		}
		final double[] factors = new double[stats.length];
		for (MonsterStrength.Stat stat : stats)
		{
			factors[stat.ordinal()] = MonsterStrength.getLiftFactor(ownLevel, level, stat) * (stat == MonsterStrength.Stat.P_ATK_SPD ? 1.0 : gain);
		}
		return new MapLift(level, factors, MonsterStrength.getExpLiftFactor(ownLevel, level) * gain, dropTemplate);
	}

	/**
	 * @param stat a stat
	 * @return how much it is raised
	 */
	public double factor(MonsterStrength.Stat stat)
	{
		return statFactors[stat.ordinal()];
	}
}
