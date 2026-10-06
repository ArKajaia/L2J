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
package org.l2jmobius.gameserver.model.hiddenquest;

/**
 * The kinds of single task a hidden quest asks for. Each is implemented in {@code model.hiddenquest.task}.
 * @author Mobius
 */
public enum HiddenTaskType
{
	/** Visit shrines and pray (sit) at each, optionally under vows. */
	PILGRIMAGE,
	/** Defeat a sequence of echoes, each with its own trick. */
	ECHO_GAUNTLET,
	/** Ambushes find the player wherever they are, then their leader comes. */
	HUNTED,
	/** Catch fleeing wisps, or couriers running for an escape point. */
	CHASE,
	/** Lead an NPC to a series of stops and protect it while it works. */
	ESCORT,
	/** Guard a totem against waves until the vigil ends. */
	VIGIL,
	/** Solve riddles that point to famous places and go there. */
	RIDDLE
}
