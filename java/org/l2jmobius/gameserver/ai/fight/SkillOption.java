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

/**
 * One thing a fake player could do now, for the scorer to weigh.
 * @param kind what it is
 * @param skill the skill, {@code null} unless {@link Kind#SKILL}
 * @param expectedDamage the damage it expects the skill to deal (from the fake player's own damage estimate), 0 for none
 * @param inReach {@code true} if it reaches the target from where the fake player stands
 * @param castMs how long the fake player takes to cast it (its cast bar, with its casting or attack speed), 0 for none
 */
public record SkillOption(Kind kind, SkillFacts skill, double expectedDamage, boolean inReach, int castMs)
{
	/**
	 * An option timed by the skill's data (cast time and pause after it).
	 */
	public SkillOption(Kind kind, SkillFacts skill, double expectedDamage, boolean inReach)
	{
		this(kind, skill, expectedDamage, inReach, skill != null ? skill.hitTimeMs() + skill.coolTimeMs() : 0);
	}
	
	public enum Kind
	{
		/** Use a skill on the target. */
		SKILL,
		/** Hit the target with normal attacks. */
		AUTO_ATTACK,
		/** Do nothing for now. */
		HOLD,
		/** Step back from the target. */
		STEP_BACK
	}
}
