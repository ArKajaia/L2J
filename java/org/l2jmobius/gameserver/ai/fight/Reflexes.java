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
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

import org.l2jmobius.gameserver.ai.fight.ReflexEvent.Kind;
import org.l2jmobius.gameserver.model.effects.AbstractEffect;
import org.l2jmobius.gameserver.model.skill.EffectScope;
import org.l2jmobius.gameserver.model.skill.Skill;

/**
 * Decides whether a fake player reacts to what just happened in its fight, between its thinks: interrupt a cast coming at it, stop a heal, ready a peel for a gap closer, answer a burst with a defensive skill, cleanse a root with a melee coming, start its burst when
 * its crowd control lands, go in when a defense ends, chain its crowd control. It reacts only when reacting is worth more than what it is doing, and not at all while it is winning the race (it keeps attacking) unless the reaction is instant.
 */
public class Reflexes
{
	/** How soon the same kind of reflex may fire again. */
	private static final long KIND_COOLDOWN = 1500;
	/** How many handled events it remembers (to never react twice to one). */
	private static final int HANDLED_MEMORY = 64;
	
	private final FightRead _read;
	private final SkillScorer _scorer;
	private final SkillWeights _weights;
	private final Set<String> _handled = new LinkedHashSet<>();
	private final Map<Kind, Long> _lastFired = new EnumMap<>(Kind.class);
	
	/**
	 * @param read the fake player's fight read
	 * @param scorer its scorer
	 * @param weights the weights of its role
	 */
	public Reflexes(FightRead read, SkillScorer scorer, SkillWeights weights)
	{
		_read = read;
		_scorer = scorer;
		_weights = weights;
	}
	
	/**
	 * @param skillLevel the fake player's skill, 0 to 1
	 * @param random the random source
	 * @return how long it takes to react, in milliseconds: 75-125 at skill level 1, 300-500 at level 0
	 */
	public static long reactionDelay(double skillLevel, Random random)
	{
		final double level = Math.max(0, Math.min(1, skillLevel));
		final double low = 300 - (225 * level);
		final double high = 500 - (375 * level);
		return (long) (low + (random.nextDouble() * (high - low)));
	}
	
	/**
	 * @param pending the events since its last reaction
	 * @param ctx the fake player and its target
	 * @param options what it could do now
	 * @param current what it is doing (or about to), {@code null} for nothing
	 * @return its reaction, {@code null} to carry on
	 */
	public synchronized Reaction decide(List<ReflexEvent> pending, ScoreContext ctx, List<SkillOption> options, SkillOption current)
	{
		final List<ReflexEvent> events = new ArrayList<>();
		for (ReflexEvent event : pending)
		{
			if (remember(event.key()))
			{
				events.add(event);
			}
		}
		if (events.isEmpty() || ctx.disabled())
		{
			return null;
		}
		
		final boolean raceWon = _scorer.raceWon(ctx);
		final double currentValue = current != null ? _scorer.score(List.of(current), ctx).get(0).value() : 0;
		Reaction best = null;
		double bestValue = currentValue;
		for (ReflexEvent event : events)
		{
			final Long last = _lastFired.get(event.kind());
			if ((last != null) && ((ctx.now() - last) < KIND_COOLDOWN))
			{
				continue;
			}
			for (SkillOption option : candidates(event, ctx, options))
			{
				if (raceWon && ((option.kind() != SkillOption.Kind.SKILL) || (option.skill().hitTimeMs() > 0)))
				{
					continue;
				}
				final double value = value(event, option, ctx);
				if (value > bestValue)
				{
					bestValue = value;
					best = new Reaction(event, option, event.kind().name());
				}
			}
		}
		if (best != null)
		{
			_lastFired.put(best.event().kind(), ctx.now());
		}
		return best;
	}
	
	/**
	 * @return {@code true} the first time it sees the key
	 */
	private boolean remember(String key)
	{
		if (!_handled.add(key))
		{
			return false;
		}
		if (_handled.size() > HANDLED_MEMORY)
		{
			final Iterator<String> oldest = _handled.iterator();
			oldest.next();
			oldest.remove();
		}
		return true;
	}
	
	/**
	 * @return the options that answer the event
	 */
	private List<SkillOption> candidates(ReflexEvent event, ScoreContext ctx, List<SkillOption> options)
	{
		final List<SkillOption> candidates = new ArrayList<>();
		final long now = ctx.now();
		switch (event.kind())
		{
			case ENEMY_CAST_AT_ME:
			{
				final ActiveCast cast = _read.currentCast(event.enemyId(), now);
				final boolean target = event.enemyId() == ctx.targetId();
				for (SkillOption option : options)
				{
					if (target && has(option, SkillMeaning.CC) && (cast != null) && (option.skill().hitTimeMs() < (cast.end() - now)))
					{
						candidates.add(option);
					}
					else if ((option.kind() == SkillOption.Kind.STEP_BACK) && ctx.ranged())
					{
						candidates.add(option);
					}
				}
				break;
			}
			case ENEMY_HEAL:
			{
				// Its skills go on its target: a heal by another enemy isn't stopped by them.
				if (event.enemyId() != ctx.targetId())
				{
					break;
				}
				for (SkillOption option : options)
				{
					if (has(option, SkillMeaning.CC) || has(option, SkillMeaning.CANCEL))
					{
						candidates.add(option);
					}
				}
				break;
			}
			case ENEMY_GAP_CLOSER:
			{
				if (ctx.ranged() && (event.enemyId() == ctx.targetId()))
				{
					for (SkillOption option : options)
					{
						if (has(option, SkillMeaning.CC))
						{
							candidates.add(option);
						}
					}
				}
				break;
			}
			case HP_CRASH:
			case ENEMY_BURST:
			{
				final boolean dying = _read.timeToDie(now, 0) < _weights.burstReflexTtd();
				if ((event.kind() == Kind.ENEMY_BURST) || dying)
				{
					for (SkillOption option : options)
					{
						// A defence that roots its caster (Ultimate Defense, Vengeance: 30 s, 15 min reuse) only when it is about to die: cast on the first burst at full HP it stood
						// rooted while an archer or a mage shot it from afar, and had nothing left for the end of the fight (docs/class-clinic/tanks.md).
						if (!dying && (option.skill() != null) && rootsCaster(option.skill()))
						{
							continue;
						}
						if (has(option, SkillMeaning.DEFENSIVE) || ((option.kind() == SkillOption.Kind.STEP_BACK) && ctx.ranged()))
						{
							candidates.add(option);
						}
					}
				}
				break;
			}
			case CC_ON_ME:
			{
				if (_read.has(ctx.selfId(), EffectMeaning.ROOTED, now) && ctx.meleeNear())
				{
					for (SkillOption option : options)
					{
						if (has(option, SkillMeaning.CLEANSE))
						{
							candidates.add(option);
						}
					}
				}
				break;
			}
			case MY_CC_LANDED:
			{
				if (ctx.burstReady() && (_scorer.currentPlan() == null))
				{
					final SkillPlan burst = _scorer.buildBurst(options, ctx);
					if ((burst != null) && _scorer.startPlan(burst, now, ctx.skillLevel()))
					{
						addStep(candidates, options, burst.next());
					}
				}
				break;
			}
			case ENEMY_DEFENSE_ENDED:
			{
				for (SkillOption option : options)
				{
					if (has(option, SkillMeaning.NUKE))
					{
						candidates.add(option);
					}
				}
				break;
			}
			case TARGET_CC_ENDING:
			{
				final SkillPlan chain = _scorer.buildCcChain(options, ctx);
				if ((chain != null) && _scorer.startPlan(chain, now, ctx.skillLevel()))
				{
					addStep(candidates, options, chain.next());
				}
				else if (ctx.ranged())
				{
					for (SkillOption option : options)
					{
						if (option.kind() == SkillOption.Kind.STEP_BACK)
						{
							candidates.add(option);
						}
					}
				}
				break;
			}
		}
		return candidates;
	}
	
	private static void addStep(List<SkillOption> candidates, List<SkillOption> options, int skillId)
	{
		for (SkillOption option : options)
		{
			if ((option.kind() == SkillOption.Kind.SKILL) && (option.skill().id() == skillId))
			{
				candidates.add(option);
				return;
			}
		}
	}
	
	private static boolean has(SkillOption option, SkillMeaning meaning)
	{
		return (option.kind() == SkillOption.Kind.SKILL) && (option.skill() != null) && SkillMeanings.classify(option.skill()).contains(meaning);
	}
	
	/**
	 * @return {@code true} for a skill whose effect on its caster roots it (ImmobileBuff: Ultimate Defense, Vengeance)
	 */
	private static boolean rootsCaster(Skill skill)
	{
		for (EffectScope scope : new EffectScope[]
		{
			EffectScope.GENERAL,
			EffectScope.SELF
		})
		{
			final List<AbstractEffect> effects = skill.getEffects(scope);
			if (effects != null)
			{
				for (AbstractEffect effect : effects)
				{
					if ("ImmobileBuff".equals(effect.getClass().getSimpleName()))
					{
						return true;
					}
				}
			}
		}
		return false;
	}
	
	/**
	 * @return the option's value as an answer to the event: its scorer value, plus what answering this event is worth (an emergency leaves the cost of a long cooldown out)
	 */
	private double value(ReflexEvent event, SkillOption option, ScoreContext ctx)
	{
		final Scored scored = _scorer.score(List.of(option), ctx).get(0);
		double value = scored.value();
		final EnumSet<SkillMeaning> meanings = option.skill() != null ? SkillMeanings.classify(option.skill()) : EnumSet.noneOf(SkillMeaning.class);
		switch (event.kind())
		{
			case HP_CRASH:
			case ENEMY_BURST:
			{
				if (meanings.contains(SkillMeaning.DEFENSIVE))
				{
					value -= scored.terms().getOrDefault("cooldownCost", 0.0);
					value += (2 * _weights.risk() * _weights.burstReflexTtd()) / Math.max(0.5, Math.min(_read.timeToDie(ctx.now(), 0), 60));
				}
				else
				{
					value += 0.5 * _weights.risk();
				}
				break;
			}
			case CC_ON_ME:
			{
				value -= scored.terms().getOrDefault("cooldownCost", 0.0);
				value += 2 * _weights.control();
				break;
			}
			case ENEMY_HEAL:
			case ENEMY_GAP_CLOSER:
			{
				value += _weights.control();
				break;
			}
			case ENEMY_CAST_AT_ME:
			{
				if (option.kind() == SkillOption.Kind.STEP_BACK)
				{
					value += 0.5 * _weights.risk();
				}
				break;
			}
			default:
			{
				break;
			}
		}
		return value;
	}
}
