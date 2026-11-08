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
package org.l2jmobius.gameserver.ai.fight;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import org.l2jmobius.gameserver.ai.fight.SkillOption.Kind;

/**
 * Weighs what a fake player could do now and picks the best, like a good player: damage for the time it takes, crowd control by its chance to land and what holding the target is worth now (an interrupt, a melee stopped, a burst), debuffs before a burst, long
 * cooldowns kept for a good moment unless it is winning the race, and long casts avoided with a melee near whose stun may be ready. Every value is in seconds of its own damage, so the parts add up.
 */
public class SkillScorer
{
	/** Shortest time an option is counted to take (an instant skill still takes a moment). */
	private static final double MIN_ACTION_SECONDS = 0.5;
	/** Longest crowd control time counted. */
	private static final double MAX_HELD_SECONDS = 5;
	/** What a second of the target held is worth, in seconds of its own damage. */
	private static final double CONTROL_SCALE = 0.25;
	/** Reuse from which a skill's cooldown is worth keeping for a good moment. */
	private static final int LONG_COOLDOWN = 30000;
	/** Below this HP the target is low: a good moment for a long cooldown. */
	private static final double LOW_HP = 0.35;
	/** What an option out of reach keeps of its value (it has to walk first). */
	private static final double OUT_OF_REACH = 0.8;
	
	private final FightRead _read;
	private final SkillWeights _weights;
	private final Random _random;
	
	/**
	 * @param read the fake player's fight read
	 * @param weights the weights of its role
	 * @param seed the seed of its misjudgements
	 */
	public SkillScorer(FightRead read, SkillWeights weights, long seed)
	{
		_read = read;
		_weights = weights;
		_random = new Random(seed);
	}
	
	/**
	 * @param ctx the fake player and its target
	 * @return {@code true} if it kills its target well before it would die: then it keeps attacking
	 */
	public boolean raceWon(ScoreContext ctx)
	{
		final double ttk = _read.timeToKill(ctx.now(), ctx.myDps());
		final double ttd = _read.timeToDie(ctx.now(), 0);
		if (Double.isInfinite(ttk) || Double.isInfinite(ttd))
		{
			return false;
		}
		return ttk < (_weights.raceMargin() * (0.75 + (0.25 * ctx.skillLevel())) * ttd);
	}
	
	/**
	 * @param options what it could do
	 * @param ctx the fake player and its target
	 * @return the options with their values, best first (on a tie, the ones that spend no skill first)
	 */
	public List<Scored> score(List<SkillOption> options, ScoreContext ctx)
	{
		final boolean raceWon = raceWon(ctx);
		final List<Scored> scored = new ArrayList<>(options.size());
		for (SkillOption option : options)
		{
			scored.add(value(option, ctx, raceWon));
		}
		scored.sort(Comparator.comparingDouble(Scored::value).reversed().thenComparingInt(s -> rank(s.option().kind())));
		return scored;
	}
	
	private static int rank(Kind kind)
	{
		return switch (kind)
		{
			case HOLD -> 0;
			case AUTO_ATTACK -> 1;
			case STEP_BACK -> 2;
			case SKILL -> 3;
		};
	}
	
	private Scored value(SkillOption option, ScoreContext ctx, boolean raceWon)
	{
		final Map<String, Double> terms = new LinkedHashMap<>();
		final double unit = Math.max(1, Math.max(ctx.myDps(), ctx.autoAttackDps()));
		final boolean invulnerable = _read.has(ctx.targetId(), EffectMeaning.INVULNERABLE, ctx.now());
		switch (option.kind())
		{
			case AUTO_ATTACK:
			{
				terms.put("damage", _weights.damage() * (invulnerable ? 0 : ctx.autoAttackDps() / unit));
				break;
			}
			case STEP_BACK:
			{
				terms.put("risk", (ctx.meleeNear() && ctx.ranged()) ? _weights.risk() * 0.5 : -0.1);
				break;
			}
			case SKILL:
			{
				skillTerms(option, ctx, raceWon, unit, invulnerable, terms);
				break;
			}
			default:
			{
				break;
			}
		}
		
		double value = 0;
		for (double term : terms.values())
		{
			value += term;
		}
		if (!option.inReach() && (value > 0))
		{
			value *= OUT_OF_REACH;
		}
		final double noise = _weights.noise() * (1 - ctx.skillLevel());
		if (noise > 0)
		{
			value *= 1 + (((_random.nextDouble() * 2) - 1) * noise);
		}
		return new Scored(option, value, terms);
	}
	
	private void skillTerms(SkillOption option, ScoreContext ctx, boolean raceWon, double unit, boolean invulnerable, Map<String, Double> terms)
	{
		final SkillFacts skill = option.skill();
		final EnumSet<SkillMeaning> meanings = SkillMeanings.classify(skill);
		final long now = ctx.now();
		final int target = ctx.targetId();
		final double seconds = Math.max(MIN_ACTION_SECONDS, (skill.hitTimeMs() + skill.coolTimeMs()) / 1000.0);
		
		double damage = option.expectedDamage() / seconds / unit;
		if (invulnerable)
		{
			damage = 0;
		}
		else if (meanings.contains(SkillMeaning.NUKE) && _read.has(target, EffectMeaning.REFLECT, now))
		{
			damage *= 0.3;
		}
		terms.put("damage", _weights.damage() * damage);
		
		final boolean held = isHeld(target, now, skill.hitTimeMs());
		if (meanings.contains(SkillMeaning.CC))
		{
			double control = 0;
			if (!held)
			{
				final double chance = _read.landChance(target, skill, ctx.targetGroup(), now);
				final double heldSeconds = Math.min(MAX_HELD_SECONDS, skill.abnormalTimeMs() / 1000.0);
				control = (chance * heldSeconds * urgency(skill, ctx) * CONTROL_SCALE) / seconds;
			}
			terms.put("control", _weights.control() * control);
		}
		if (meanings.contains(SkillMeaning.DEBUFF) || meanings.contains(SkillMeaning.CANCEL))
		{
			terms.put("setup", _weights.setup() * (ctx.burstReady() ? 1 : 0.3));
		}
		if (skill.reuseMs() >= LONG_COOLDOWN)
		{
			double cost = 0;
			if (!raceWon)
			{
				cost = -(skill.reuseMs() / 60000.0);
				final boolean defended = _read.has(target, EffectMeaning.DEFENDING, now) || invulnerable;
				if (held || (ctx.targetHpRatio() < LOW_HP) || !defended)
				{
					cost /= 2;
				}
			}
			terms.put("cooldownCost", _weights.cooldownCost() * cost);
		}
		if (ctx.meleeNear() && (skill.hitTimeMs() > 0) && !_read.knownCcOnCooldown(target, now))
		{
			terms.put("risk", -_weights.risk() * (skill.hitTimeMs() / 1000.0));
		}
	}
	
	/**
	 * @return {@code true} if the target is held (stunned, rooted or asleep) for longer than the skill takes to land
	 */
	private boolean isHeld(int target, long now, int hitTime)
	{
		final long held = Math.max(_read.remaining(target, EffectMeaning.STUNNED, now), Math.max(_read.remaining(target, EffectMeaning.ROOTED, now), _read.remaining(target, EffectMeaning.SLEEPING, now)));
		return held > hitTime;
	}
	
	/**
	 * @return what holding the target is worth now: 3 to interrupt a nuke, heal or crowd control it is casting (if this lands first), 2 to stop a melee on an archer or mage or to hold the target for a ready burst, 1 otherwise
	 */
	private double urgency(SkillFacts skill, ScoreContext ctx)
	{
		final ActiveCast cast = _read.currentCast(ctx.targetId(), ctx.now());
		if ((cast != null) && (skill.hitTimeMs() < (cast.end() - ctx.now())) && (cast.meanings().contains(SkillMeaning.NUKE) || cast.meanings().contains(SkillMeaning.HEAL) || cast.meanings().contains(SkillMeaning.CC)))
		{
			return 3;
		}
		if ((ctx.meleeNear() && ctx.ranged()) || ctx.burstReady())
		{
			return 2;
		}
		return 1;
	}
}
