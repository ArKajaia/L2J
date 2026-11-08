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

import java.util.EnumSet;
import java.util.Set;

/**
 * Sorts skills by what they mean in a fight, from their data alone (see {@link SkillFacts}).
 */
public final class SkillMeanings
{
	/** Effect types that stop a target. */
	private static final Set<String> CC_EFFECTS = Set.of("STUN", "ROOT", "SLEEP", "PARALYZE", "MUTE", "FEAR");
	/** Abnormal types that stop a target. */
	private static final Set<String> CC_ABNORMALS = Set.of("STUN", "ROOT_PHYSICALLY", "ROOT_MAGICALLY", "SLEEP", "PARALYZE", "SILENCE", "SILENCE_PHYSICAL", "SILENCE_ALL", "TURN_STONE");
	/** Effect types that deal damage. */
	private static final Set<String> DAMAGE_EFFECTS = Set.of("MAGICAL_ATTACK", "PHYSICAL_ATTACK", "PHYSICAL_ATTACK_HP_LINK", "DEATH_LINK", "HP_DRAIN");
	private static final Set<String> HEAL_EFFECTS = Set.of("HEAL", "CPHEAL", "REBALANCE_HP");
	private static final Set<String> DISPEL_EFFECTS = Set.of("DISPEL", "DISPEL_BY_SLOT");
	private static final Set<String> CANCEL_EFFECTS = Set.of("DISPEL", "DISPEL_BY_SLOT", "STEAL_ABNORMAL");
	/** A buff on oneself with at least this reuse is a burst buff (unless it is defensive). */
	private static final int BURST_REUSE = 60000;
	
	/**
	 * Skills that keep damage or debuffs out: the defensive buffs brain B already plays around (Ultimate Defense, Vengeance, Guts, Magical Mirror, Shield Deflect Magic, Reflect Magic), and Touch of Life, Mirage, Fake Death, Sonic Barrier, Force Barrier, Ultimate
	 * Evasion, Hide, Shield Fortress, Celestial Shield, Final Fortress and Soul Barrier.
	 */
	public static final Set<Integer> DEFENSIVE_SKILL_IDS = Set.of(110, 368, 139, 351, 916, 6282, 341, 445, 60, 442, 443, 111, 922, 322, 1418, 291, 1514);
	
	private SkillMeanings()
	{
	}
	
	/**
	 * @param facts a skill
	 * @return what it means in a fight, never empty ({@link SkillMeaning#OTHER} when nothing else fits, and always for an id of 0 or less)
	 */
	public static EnumSet<SkillMeaning> classify(SkillFacts facts)
	{
		final EnumSet<SkillMeaning> meanings = EnumSet.noneOf(SkillMeaning.class);
		if (facts.id() <= 0)
		{
			return EnumSet.of(SkillMeaning.OTHER);
		}
		
		final Set<String> effects = facts.effectTypes();
		final boolean cc = facts.bad() && (any(effects, CC_EFFECTS) || CC_ABNORMALS.contains(facts.abnormalType()));
		if (cc)
		{
			meanings.add(SkillMeaning.CC);
		}
		if (facts.bad() && (facts.power() > 0) && any(effects, DAMAGE_EFFECTS))
		{
			meanings.add(SkillMeaning.NUKE);
		}
		if (facts.charge() || effects.contains("TELEPORT_TO_TARGET"))
		{
			meanings.add(SkillMeaning.GAP_CLOSER);
		}
		if (!facts.bad() && any(effects, HEAL_EFFECTS))
		{
			meanings.add(SkillMeaning.HEAL);
		}
		if (!facts.bad() && any(effects, DISPEL_EFFECTS))
		{
			meanings.add(SkillMeaning.CLEANSE);
		}
		if (facts.bad() && any(effects, CANCEL_EFFECTS))
		{
			meanings.add(SkillMeaning.CANCEL);
		}
		if (facts.bad() && (facts.abnormalTimeMs() > 0) && !cc)
		{
			meanings.add(SkillMeaning.DEBUFF);
		}
		final boolean defensive = !facts.bad() && (DEFENSIVE_SKILL_IDS.contains(facts.id()) || "INVINCIBILITY".equals(facts.abnormalType()));
		if (defensive)
		{
			meanings.add(SkillMeaning.DEFENSIVE);
		}
		else if (!facts.bad() && facts.self() && (facts.reuseMs() >= BURST_REUSE) && (facts.abnormalTimeMs() > 0))
		{
			meanings.add(SkillMeaning.BURST_BUFF);
		}
		
		if (meanings.isEmpty())
		{
			meanings.add(SkillMeaning.OTHER);
		}
		return meanings;
	}
	
	private static boolean any(Set<String> have, Set<String> wanted)
	{
		for (String name : have)
		{
			if (wanted.contains(name))
			{
				return true;
			}
		}
		return false;
	}
}
