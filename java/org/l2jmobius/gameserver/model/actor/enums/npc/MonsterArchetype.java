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
package org.l2jmobius.gameserver.model.actor.enums.npc;

/**
 * Defines a combat "personality" for an {@link org.l2jmobius.gameserver.model.actor.Attackable}.
 * <p>
 * This is intentionally orthogonal to {@link AIType} (FIGHTER/MAGE/ARCHER/HEALER/CORPSE), which
 * governs low-level targeting/range mechanics. MonsterArchetype instead governs the *style* of
 * decision-making layered on top: how eager the monster is to flee, heal itself, support allies,
 * hold a grudge, or spiral into a rage as it loses HP.
 * <p>
 * A monster's archetype can be:
 * <ul>
 * <li>Declared explicitly in the NPC's XML {@code <parameters>} block, e.g.
 * {@code <param name="AIArchetype" value="RAGER" />}</li>
 * <li>Left unset, in which case {@code AttackableAI} rolls a weighted-random archetype the first
 * time it's needed, biased by the NPC's template (has heal/buff skills, is a raid, its
 * {@link AIType}, etc.) and caches it for the lifetime of that spawned instance.</li>
 * </ul>
 * @author Zoey76
 */
public enum MonsterArchetype
{
	/** No strong personality bias; behaves like the original stock AI. */
	BALANCED,
	/** Rarely flees, presses the attack, low self-preservation instinct. */
	AGGRESSIVE,
	/** Flees early and often when hurt, avoids melee range, gives up chases quickly. */
	COWARD,
	/** Casts skills often and skillfully, minimizes wasted actions. */
	SKILLFUL,
	/** Caster-leaning bias: prefers to keep range and lean on its skill kit. */
	MAGE,
	/** Melee-leaning bias: low cast frequency, presses in, doesn't kite. */
	FIGHTER,
	/** Strongly favors healing/buffing itself and, especially, its clan/allies. */
	SUPPORTER,
	/** Gets angrier (more hate, more aggression) as its own HP drops. Never flees. */
	RAGER,
	/** Fixates on whoever last hurt it; very reluctant to switch targets. */
	AVENGER
}
