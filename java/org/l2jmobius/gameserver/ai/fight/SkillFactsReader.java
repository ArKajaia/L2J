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

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.l2jmobius.gameserver.model.effects.AbstractEffect;
import org.l2jmobius.gameserver.model.effects.EffectType;
import org.l2jmobius.gameserver.model.skill.EffectScope;
import org.l2jmobius.gameserver.model.skill.Skill;
import org.l2jmobius.gameserver.model.skill.enums.FlyType;
import org.l2jmobius.gameserver.model.skill.targets.TargetType;

/**
 * Reads the {@link SkillFacts} of a server skill, once per skill and level.
 */
public final class SkillFactsReader
{
	private static final Map<Integer, SkillFacts> CACHE = new ConcurrentHashMap<>();
	/** The scopes whose effects a player sees the skill do. */
	private static final List<EffectScope> SCOPES = List.of(EffectScope.GENERAL, EffectScope.START, EffectScope.SELF, EffectScope.CHANNELING, EffectScope.PVP);
	
	private SkillFactsReader()
	{
	}
	
	/**
	 * @param skill a skill
	 * @return its facts
	 */
	public static SkillFacts of(Skill skill)
	{
		return CACHE.computeIfAbsent(skill.hashCode(), key -> read(skill));
	}
	
	private static SkillFacts read(Skill skill)
	{
		final Set<String> effectTypes = new HashSet<>();
		for (EffectScope scope : SCOPES)
		{
			final List<AbstractEffect> effects = skill.getEffects(scope);
			if (effects != null)
			{
				for (AbstractEffect effect : effects)
				{
					effectTypes.add(effect.getEffectType().name());
				}
			}
		}
		final boolean bad = skill.isDebuff() || skill.hasEffectType(EffectType.PHYSICAL_ATTACK, EffectType.MAGICAL_ATTACK, EffectType.PHYSICAL_ATTACK_HP_LINK, EffectType.DEATH_LINK, EffectType.HP_DRAIN, EffectType.STUN, EffectType.ROOT, EffectType.SLEEP, EffectType.PARALYZE, EffectType.MUTE, EffectType.FEAR, EffectType.DMG_OVER_TIME, EffectType.DMG_OVER_TIME_PERCENT, EffectType.DEBUFF);
		final String abnormalType = skill.getAbnormalType() != null ? skill.getAbnormalType().name() : "NONE";
		final String basic = skill.getBasicProperty() != null ? skill.getBasicProperty().name() : "NONE";
		return new SkillFacts(skill.getId(), skill.getLevel(), bad, skill.getTargetType() == TargetType.SELF, skill.getCastRange(), skill.getHitTime(), skill.getCoolTime(), skill.getReuseDelay(), skill.getAbnormalTime(), abnormalType, Set.copyOf(effectTypes), skill.getFlyType() == FlyType.CHARGE, skill.getPower(), skill.getActivateRate(), skill.getMagicLevel(), basic);
	}
}
