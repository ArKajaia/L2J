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
package org.l2jmobius.gameserver.model.classtransfer;

/**
 * Objective types a class transfer challenge can chain together.
 * @author Mobius
 */
public enum ObjectiveType
{
	/** Kill a number of the objective's monsters (a single specific monster is simply {@code count="1"}). */
	KILL,
	/** Gather challenge items dropped by the objective's monsters. A Mark Thief can pocket them. */
	COLLECT,
	/** Walk to a location. */
	REACH,
	/** Talk to an NPC. */
	TALK,
	/** Activate a number of object NPCs (Trial Seals). */
	ACTIVATE,
	/** Keep an NPC alive for a duration while monsters attack it. */
	PROTECT,
	/** Stay in the fight for a duration while monsters keep coming. */
	SURVIVE,
	/** Defeat a boss (optionally a multi-phase Wave/Arena Champion). */
	BOSS,
	/** Use a challenge item (optionally at a location). */
	USE_ITEM,
	/** Clear a number of timed waves. */
	WAVES,
	/** Defeat a Rival Shade: a fake player built as one of the player's target classes. */
	DUEL
}
