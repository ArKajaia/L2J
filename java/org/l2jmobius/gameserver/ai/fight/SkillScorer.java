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
	/** Share of a skill's value lost per second of its cast when a melee near may interrupt it: magic casts break on damage and stuns, physical skills only on stuns. */
	private static final double MAGIC_INTERRUPT_SHARE = 0.2;
	private static final double PHYSICAL_INTERRUPT_SHARE = 0.1;
	/** Chance at skill level 0 to take the second-best option (none at level 1). */
	private static final double MISPLAY_CHANCE = 0.2;
	/** Bonus of a plan's next step, in damage weights. */
	private static final double PLAN_BONUS = 2;
	/** Lowest land chance of the crowd control a burst is built on. */
	private static final double BURST_MIN_LAND_CHANCE = 0.5;
	/** How early the next crowd control of a chain is cast, before the held one ends, beyond its cast time. */
	private static final long CHAIN_LEAD = 100;
	/** Planning depth a burst needs, and a crowd control chain. */
	private static final int BURST_DEPTH = 2;
	private static final int CHAIN_DEPTH = 3;
	
	private final FightRead _read;
	private final SkillWeights _weights;
	private final Random _random;
	private SkillPlan _plan;
	
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
	 * @return {@code true} if it kills its target well before it would die, at the rate it really deals damage: then it keeps attacking
	 */
	public boolean raceWon(ScoreContext ctx)
	{
		// The damage it really deals decides, its estimate only before any is seen.
		final double dealt = _read.damageDealtPerSecond(ctx.now());
		final double ttk = _read.timeToKill(ctx.now(), dealt > 0 ? dealt : ctx.myDps());
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
		if ((_plan != null) && (_plan.done() || _plan.expired(ctx.now())))
		{
			_plan = null;
		}
		final boolean raceWon = raceWon(ctx);
		final List<Scored> scored = new ArrayList<>(options.size());
		for (SkillOption option : options)
		{
			scored.add(value(option, ctx, raceWon));
		}
		scored.sort(Comparator.comparingDouble(Scored::value).reversed().thenComparingInt(s -> rank(s.option().kind())));
		// An unskilled fake player sometimes takes its second-best option, like a player who misjudges.
		if ((scored.size() > 1) && (_random.nextDouble() < (MISPLAY_CHANCE * (1 - ctx.skillLevel()))))
		{
			scored.add(0, scored.remove(1));
		}
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
		
		if ((_plan != null) && (option.kind() == Kind.SKILL) && (option.skill().id() == _plan.next()))
		{
			terms.put("plan", _weights.damage() * PLAN_BONUS);
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
		final double seconds = Math.max(MIN_ACTION_SECONDS, option.castMs() / 1000.0);
		
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
		if (ctx.meleeNear() && (option.castMs() > 0) && !_read.knownCcOnCooldown(target, now))
		{
			final boolean magic = skill.effectTypes().contains("MAGICAL_ATTACK") || skill.effectTypes().contains("DEATH_LINK") || skill.effectTypes().contains("HP_DRAIN");
			terms.put("risk", -_weights.risk() * (magic ? MAGIC_INTERRUPT_SHARE : PHYSICAL_INTERRUPT_SHARE) * (option.castMs() / 1000.0) * damage);
		}
	}
	
	/**
	 * @return its plan, {@code null} for none
	 */
	public SkillPlan currentPlan()
	{
		return _plan;
	}
	
	/**
	 * @param plan a plan
	 * @param now the current time
	 * @param skillLevel the fake player's skill, 0 to 1
	 * @return {@code true} if it took the plan up (one that doesn't plan refuses)
	 */
	public boolean startPlan(SkillPlan plan, long now, double skillLevel)
	{
		if (depth(skillLevel) < 1)
		{
			return false;
		}
		_plan = plan;
		return true;
	}
	
	public void dropPlan()
	{
		_plan = null;
	}
	
	/**
	 * Its skill used: the plan moves on if it was the next step.
	 * @param skillId the skill
	 */
	public void onSkillUsed(int skillId)
	{
		if (_plan != null)
		{
			_plan.advance(skillId);
		}
	}
	
	/**
	 * @param skillLevel the fake player's skill, 0 to 1
	 * @return how many steps ahead it plans
	 */
	public int depth(double skillLevel)
	{
		return (int) Math.round(_weights.planDepth() * skillLevel);
	}
	
	/**
	 * A burst from what is ready: its burst buffs, the crowd control most likely to land (if likely enough), then its damage skills, biggest first.
	 * @param ready its skills ready to use
	 * @param ctx the fake player and its target
	 * @return the plan, {@code null} without a crowd control likely to land or for one that doesn't plan that far
	 */
	public SkillPlan buildBurst(List<SkillOption> ready, ScoreContext ctx)
	{
		if (depth(ctx.skillLevel()) < BURST_DEPTH)
		{
			return null;
		}
		SkillOption control = null;
		double best = BURST_MIN_LAND_CHANCE;
		final List<Integer> steps = new ArrayList<>();
		final List<SkillOption> nukes = new ArrayList<>();
		for (SkillOption option : ready)
		{
			if ((option.kind() != Kind.SKILL) || (option.skill() == null))
			{
				continue;
			}
			final EnumSet<SkillMeaning> meanings = SkillMeanings.classify(option.skill());
			if (meanings.contains(SkillMeaning.BURST_BUFF))
			{
				steps.add(option.skill().id());
			}
			else if (meanings.contains(SkillMeaning.CC))
			{
				final double chance = _read.landChance(ctx.targetId(), option.skill(), ctx.targetGroup(), ctx.now());
				if (chance >= best)
				{
					best = chance;
					control = option;
				}
			}
			else if (meanings.contains(SkillMeaning.NUKE))
			{
				nukes.add(option);
			}
		}
		if (control == null)
		{
			return null;
		}
		steps.add(control.skill().id());
		nukes.sort(Comparator.comparingDouble(SkillOption::expectedDamage).reversed());
		for (SkillOption nuke : nukes)
		{
			steps.add(nuke.skill().id());
		}
		return new SkillPlan(SkillPlan.Type.BURST, steps, ctx.now());
	}
	
	/**
	 * The next crowd control of a chain: when the one holding the target is about to end, the one most likely to land, to be cast so it lands as the other ends.
	 * @param ready its skills ready to use
	 * @param ctx the fake player and its target
	 * @return the plan, {@code null} if the target isn't held, the hold isn't ending yet, there is nothing to chain, or the fake player doesn't plan that far
	 */
	public SkillPlan buildCcChain(List<SkillOption> ready, ScoreContext ctx)
	{
		if (depth(ctx.skillLevel()) < CHAIN_DEPTH)
		{
			return null;
		}
		final long now = ctx.now();
		final int target = ctx.targetId();
		final long held = Math.max(_read.remaining(target, EffectMeaning.STUNNED, now), Math.max(_read.remaining(target, EffectMeaning.ROOTED, now), _read.remaining(target, EffectMeaning.SLEEPING, now)));
		if (held <= 0)
		{
			return null;
		}
		SkillOption next = null;
		double best = 0;
		for (SkillOption option : ready)
		{
			if ((option.kind() == Kind.SKILL) && (option.skill() != null) && SkillMeanings.classify(option.skill()).contains(SkillMeaning.CC) && (held <= (option.skill().hitTimeMs() + CHAIN_LEAD)))
			{
				final double chance = _read.landChance(target, option.skill(), ctx.targetGroup(), now);
				if (chance > best)
				{
					best = chance;
					next = option;
				}
			}
		}
		return next != null ? new SkillPlan(SkillPlan.Type.CC_CHAIN, List.of(next.skill().id()), now) : null;
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
