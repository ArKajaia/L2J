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

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Random;
import java.util.Set;

/**
 * What one fake player sees of its fight, the way a player sees it: the casts of its enemies, the cooldowns of the skills it saw them use, the icons on them and on itself, how fast HP goes down on both sides, and what its own crowd control did. It never acts; the
 * scorer and the reflexes read it. It knows nothing a player couldn't see: no hidden stats, no cooldown of a skill it never saw used.
 * <p>
 * How much it notices follows the fake player's knowledge: an expert remembers more cooldowns, notices new icons at once and guesses land chances closely; a novice remembers two cooldowns, notices icons late and guesses roughly.
 */
public class FightRead
{
	/** Cooldowns remembered at knowledge level 0 and 1. */
	private static final int MIN_MEMORY = 2;
	private static final int MAX_MEMORY = 12;
	/** How late a new icon is noticed at knowledge level 0, in milliseconds (at once at level 1). */
	private static final long MAX_NOTICE_DELAY = 1500;
	/** How far back the HP samples go for damage per second. */
	private static final long HP_WINDOW = 4000;
	/** The most a land chance guess strays at knowledge level 0. */
	private static final double MAX_ESTIMATE_NOISE = 0.2;
	/** Base land chance of a skill without one in its data. */
	private static final double DEFAULT_LAND_CHANCE = 0.8;
	/** How many attempts the base guess counts for against the record of landed and resisted ones. */
	private static final int PRIOR_ATTEMPTS = 4;
	private static final Set<String> STUN_TYPE = Set.of("STUN", "PARALYZE");
	private static final Set<String> MEN_TYPE = Set.of("SLEEP", "SILENCE", "SILENCE_PHYSICAL", "SILENCE_ALL", "MUTE");
	
	private record Cooldown(long usedAt, int reuse)
	{
	}
	
	private record Sample(long time, double hp)
	{
	}
	
	private static final class Icon
	{
		long expiresAt;
		long visibleFrom;
		
		Icon(long expiresAt, long visibleFrom)
		{
			this.expiresAt = expiresAt;
			this.visibleFrom = visibleFrom;
		}
	}
	
	private final int _memory;
	private final long _noticeDelay;
	private final double _noise;
	private final Random _random;
	/** (enemy, skill) -> its cooldown, oldest first. */
	private final LinkedHashMap<Long, Cooldown> _cooldowns = new LinkedHashMap<>();
	private final Map<Integer, ActiveCast> _casts = new HashMap<>();
	private final Map<Integer, Map<EffectMeaning, Icon>> _icons = new HashMap<>();
	/** Enemy -> {attempts, landed} of its own crowd control on it. */
	private final Map<Integer, int[]> _ccRecord = new HashMap<>();
	private final Deque<Sample> _selfHp = new ArrayDeque<>();
	private final Deque<Sample> _targetHp = new ArrayDeque<>();
	private int _targetId;
	private int _seenCasts;
	
	/**
	 * @param knowledge the fake player's knowledge, -1 to 1 (see FakePlayerPvpPersonality)
	 * @param seed the seed of its guesses
	 */
	public FightRead(double knowledge, long seed)
	{
		final double level = level(knowledge);
		_memory = (int) Math.round(MIN_MEMORY + ((MAX_MEMORY - MIN_MEMORY) * level));
		_noticeDelay = Math.round(MAX_NOTICE_DELAY * (1 - level));
		_noise = MAX_ESTIMATE_NOISE * (1 - level);
		_random = new Random(seed);
	}
	
	/**
	 * @param trait a personality trait, -1 to 1
	 * @return its level, 0 to 1
	 */
	public static double level(double trait)
	{
		return Math.max(0, Math.min(1, (trait + 1) / 2));
	}
	
	private static long key(int enemyId, int skillId)
	{
		return ((long) enemyId << 32) | (skillId & 0xFFFFFFFFL);
	}
	
	/**
	 * An enemy started a cast.
	 * @param enemyId the caster
	 * @param skill the skill
	 * @param targetId who it is cast on
	 * @param now the current time
	 * @param end when it lands
	 */
	public synchronized void onEnemyCast(int enemyId, SkillFacts skill, int targetId, long now, long end)
	{
		_seenCasts++;
		_casts.put(enemyId, new ActiveCast(enemyId, skill, SkillMeanings.classify(skill), targetId, now, end));
		if ((skill.id() > 0) && (skill.reuseMs() > 0))
		{
			final long key = key(enemyId, skill.id());
			_cooldowns.remove(key);
			_cooldowns.put(key, new Cooldown(now, skill.reuseMs()));
			while (_cooldowns.size() > _memory)
			{
				final Iterator<Long> oldest = _cooldowns.keySet().iterator();
				oldest.next();
				oldest.remove();
			}
		}
	}
	
	/**
	 * The icons on a creature now (all of them: a meaning left out is gone).
	 * @param creatureId the creature
	 * @param expiresAt each meaning it shows and when it ends
	 * @param now the current time
	 */
	public synchronized void onEffects(int creatureId, Map<EffectMeaning, Long> expiresAt, long now)
	{
		final Map<EffectMeaning, Icon> before = _icons.getOrDefault(creatureId, Map.of());
		final Map<EffectMeaning, Icon> icons = new EnumMap<>(EffectMeaning.class);
		for (Map.Entry<EffectMeaning, Long> entry : expiresAt.entrySet())
		{
			final Icon known = before.get(entry.getKey());
			icons.put(entry.getKey(), new Icon(entry.getValue(), known != null ? known.visibleFrom : now + _noticeDelay));
		}
		_icons.put(creatureId, icons);
	}
	
	/**
	 * HP now, its own and its target's.
	 * @param now the current time
	 * @param selfId the fake player
	 * @param selfHp its HP
	 * @param selfMaxHp its max HP
	 * @param targetId its target (another target starts the target's samples over)
	 * @param targetHp the target's HP
	 * @param targetMaxHp the target's max HP
	 */
	public synchronized void onHp(long now, int selfId, double selfHp, double selfMaxHp, int targetId, double targetHp, double targetMaxHp)
	{
		add(_selfHp, now, selfHp);
		if (targetId != _targetId)
		{
			_targetHp.clear();
			_targetId = targetId;
		}
		add(_targetHp, now, targetHp);
	}
	
	private static void add(Deque<Sample> samples, long now, double hp)
	{
		samples.addLast(new Sample(now, hp));
		while ((samples.size() > 2) && (samples.peekFirst().time() < (now - HP_WINDOW)))
		{
			samples.pollFirst();
		}
	}
	
	/**
	 * Its own crowd control on an enemy landed or was resisted.
	 * @param enemyId the enemy
	 * @param skillId the skill
	 * @param landed {@code true} if it landed
	 */
	public synchronized void onCcAttempt(int enemyId, int skillId, boolean landed)
	{
		final int[] record = _ccRecord.computeIfAbsent(enemyId, k -> new int[2]);
		record[0]++;
		if (landed)
		{
			record[1]++;
		}
	}
	
	/**
	 * Drops everything about an enemy that left the fight.
	 * @param enemyId the enemy
	 */
	public synchronized void forget(int enemyId)
	{
		_casts.remove(enemyId);
		_icons.remove(enemyId);
		_ccRecord.remove(enemyId);
		_cooldowns.keySet().removeIf(key -> (int) (key >> 32) == enemyId);
	}
	
	/**
	 * @return how many enemy casts it saw in all
	 */
	public synchronized int seenCasts()
	{
		return _seenCasts;
	}
	
	/**
	 * @return how many enemy cooldowns it remembers now
	 */
	public synchronized int rememberedCount()
	{
		return _cooldowns.size();
	}
	
	public synchronized boolean isRemembered(int enemyId, int skillId)
	{
		return _cooldowns.containsKey(key(enemyId, skillId));
	}
	
	/**
	 * @param enemyId an enemy
	 * @param skillId one of its skills
	 * @param now the current time
	 * @return {@code true} if it saw the enemy use it and its reuse isn't over (an unseen skill may be ready)
	 */
	public synchronized boolean isOnCooldown(int enemyId, int skillId, long now)
	{
		final Cooldown cooldown = _cooldowns.get(key(enemyId, skillId));
		return (cooldown != null) && (now < (cooldown.usedAt() + cooldown.reuse()));
	}
	
	/**
	 * @param enemyId an enemy
	 * @param now the current time
	 * @return its cast in progress, {@code null} for none
	 */
	public synchronized ActiveCast currentCast(int enemyId, long now)
	{
		final ActiveCast cast = _casts.get(enemyId);
		return (cast != null) && (now < cast.end()) ? cast : null;
	}
	
	/**
	 * @param creatureId a creature
	 * @param meaning an icon meaning
	 * @param now the current time
	 * @return {@code true} if the creature shows it and the fake player noticed it
	 */
	public synchronized boolean has(int creatureId, EffectMeaning meaning, long now)
	{
		final Icon icon = _icons.getOrDefault(creatureId, Map.of()).get(meaning);
		return (icon != null) && (now >= icon.visibleFrom) && (now < icon.expiresAt);
	}
	
	/**
	 * @return how long the icon has left, 0 if it isn't seen
	 */
	public synchronized long remaining(int creatureId, EffectMeaning meaning, long now)
	{
		return has(creatureId, meaning, now) ? _icons.get(creatureId).get(meaning).expiresAt - now : 0;
	}
	
	public synchronized double damageTakenPerSecond(long now)
	{
		return perSecond(_selfHp, now);
	}
	
	public synchronized double damageDealtPerSecond(long now)
	{
		return perSecond(_targetHp, now);
	}
	
	private static double perSecond(Deque<Sample> samples, long now)
	{
		Sample first = null;
		for (Sample sample : samples)
		{
			if (sample.time() >= (now - HP_WINDOW))
			{
				first = sample;
				break;
			}
		}
		final Sample last = samples.peekLast();
		if ((first == null) || (last == null) || (last.time() <= first.time()))
		{
			return 0;
		}
		return Math.max(0, (first.hp() - last.hp()) / ((last.time() - first.time()) / 1000.0));
	}
	
	/**
	 * @param now the current time
	 * @param extraIncoming damage about to land on it (a big cast started at it)
	 * @return seconds until it dies at the current rate, infinite with no damage coming
	 */
	public synchronized double timeToDie(long now, double extraIncoming)
	{
		final Sample last = _selfHp.peekLast();
		if (last == null)
		{
			return Double.POSITIVE_INFINITY;
		}
		final double hp = last.hp() - extraIncoming;
		if (hp <= 0)
		{
			return 0;
		}
		final double rate = damageTakenPerSecond(now);
		return rate > 0 ? hp / rate : Double.POSITIVE_INFINITY;
	}
	
	/**
	 * @param now the current time
	 * @param myDps its damage per second with its best skills in reach
	 * @return seconds until its target dies, infinite if it deals nothing
	 */
	public synchronized double timeToKill(long now, double myDps)
	{
		final Sample last = _targetHp.peekLast();
		if ((last == null) || (myDps <= 0))
		{
			return Double.POSITIVE_INFINITY;
		}
		return last.hp() / myDps;
	}
	
	/**
	 * Guesses the chance a crowd control skill lands on an enemy, from what a player sees: the skill's base chance, the enemy's kind of class, its resist icons and what landed or was resisted so far.
	 * @param enemyId the enemy
	 * @param skill the skill
	 * @param group the enemy's kind of class
	 * @param now the current time
	 * @return 0 to 1
	 */
	public synchronized double landChance(int enemyId, SkillFacts skill, ClassGroup group, long now)
	{
		final boolean stun = STUN_TYPE.contains(skill.abnormalType()) || skill.effectTypes().contains("STUN") || skill.effectTypes().contains("PARALYZE");
		if (stun && has(enemyId, EffectMeaning.CC_IMMUNE, now))
		{
			return 0;
		}
		final boolean men = MEN_TYPE.contains(skill.abnormalType()) || skill.effectTypes().contains("SLEEP") || skill.effectTypes().contains("MUTE");
		double base = skill.activateRate() > 0 ? skill.activateRate() / 100.0 : DEFAULT_LAND_CHANCE;
		if (stun)
		{
			base *= switch (group)
			{
				case HEAVY_FIGHTER -> 0.7;
				case LIGHT_FIGHTER -> 0.85;
				case ROBE_MAGE -> 1.0;
			};
		}
		else if (men && (group == ClassGroup.ROBE_MAGE))
		{
			base *= 0.7;
		}
		final int[] record = _ccRecord.getOrDefault(enemyId, new int[2]);
		double chance = ((base * PRIOR_ATTEMPTS) + record[1]) / (PRIOR_ATTEMPTS + record[0]);
		if (_noise > 0)
		{
			chance += ((_random.nextDouble() * 2) - 1) * _noise;
		}
		return Math.max(0, Math.min(1, chance));
	}
}
