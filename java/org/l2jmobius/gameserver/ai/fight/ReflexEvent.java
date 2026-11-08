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
 * Something in the fight a fake player may react to between its thinks.
 * @param kind what happened
 * @param enemyId the enemy it is about, 0 for none
 * @param skill the skill it is about, {@code null} for none
 * @param at when it happened
 */
public record ReflexEvent(Kind kind, int enemyId, SkillFacts skill, long at)
{
	public enum Kind
	{
		/** An enemy started a nuke or crowd control at it. */
		ENEMY_CAST_AT_ME,
		/** An enemy started a heal (on itself or a friend). */
		ENEMY_HEAL,
		/** An enemy started a gap closer at it (Rush, Shadow Step). */
		ENEMY_GAP_CLOSER,
		/** A burst buff appeared on an enemy. */
		ENEMY_BURST,
		/** Its time to die fell below the burst reflex threshold. */
		HP_CRASH,
		/** A crowd control landed on it. */
		CC_ON_ME,
		/** Its own crowd control landed on its target. */
		MY_CC_LANDED,
		/** Its target's invulnerability or reflect ended. */
		ENEMY_DEFENSE_ENDED,
		/** The crowd control holding its target is about to end. */
		TARGET_CC_ENDING
	}
	
	/**
	 * @return what makes this event the same event if it comes again
	 */
	String key()
	{
		return kind + ":" + enemyId + ":" + (skill != null ? skill.id() : 0) + ":" + at;
	}
}
