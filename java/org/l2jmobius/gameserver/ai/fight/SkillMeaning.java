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
 * What a skill means in a fight, as a player reads it from the cast bar (see {@link SkillMeanings#classify}). A skill can mean several things (Stun Shot is CC and NUKE).
 */
public enum SkillMeaning
{
	/** Damage on the target. */
	NUKE,
	/** Stops the target: stun, root, sleep, paralysis, silence, fear. */
	CC,
	/** Closes the distance to the target (Rush, Shadow Step). */
	GAP_CLOSER,
	/** Restores HP. */
	HEAL,
	/** Removes debuffs from a friend or oneself. */
	CLEANSE,
	/** Removes buffs from an enemy. */
	CANCEL,
	/** A lasting effect on an enemy that isn't crowd control (P. Def. down, Hex, poison...). */
	DEBUFF,
	/** A long cooldown buff on oneself for a burst (Frenzy, Arcane Power...). */
	BURST_BUFF,
	/** A buff that keeps damage or debuffs out (Ultimate Defense, Sonic Barrier, Touch of Life...). */
	DEFENSIVE,
	/** None of the above. */
	OTHER
}
