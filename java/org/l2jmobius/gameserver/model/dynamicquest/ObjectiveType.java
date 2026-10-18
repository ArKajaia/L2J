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
package org.l2jmobius.gameserver.model.dynamicquest;

/**
 * The reusable steps a dynamic quest is built from.
 * @author Mobius
 */
public enum ObjectiveType
{
	/** Kill monsters: the quest's own (spawned), those of its hunting ground, or any of its level. */
	KILL(true),
	/** Trim a surge of monsters the world spawned, together with everyone near it. */
	CULL(true),
	/** Gather things: from kills (a chance per kill) or from nodes spawned around the hunting ground. */
	COLLECT(true),
	/** Hand items over at a Notice Board; real items are taken from the inventory. */
	DELIVER(true),
	/** Lead an npc or a caravan along a route while ambushes try to stop it. */
	ESCORT(false),
	/** Find clues: go to the radar point and search (stand near it), each clue pointing at the next. */
	INVESTIGATE(true),
	/** Keep something standing through waves of monsters. */
	DEFEND(false),
	/** Talk to an npc. */
	INTERACT(true),
	/** Get somewhere, maybe without teleporting, maybe through ambushes. */
	REACH(false),
	/** Burn nests that keep spawning monsters until they are gone. */
	DESTROY(false);

	private final boolean _persistent;

	private ObjectiveType(boolean persistent)
	{
		_persistent = persistent;
	}

	/**
	 * @return {@code true} if progress survives a logout; a quest in a stage with a non-persistent objective fails when its owner logs out
	 */
	public boolean isPersistent()
	{
		return _persistent;
	}
}
