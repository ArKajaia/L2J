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

import org.l2jmobius.gameserver.model.actor.Npc;

/**
 * An NPC a challenge spawned: which objective it belongs to and what part it plays. Only tracked NPCs can advance a challenge.
 * @author Mobius
 */
public class TrackedNpc
{
	public enum NpcRole
	{
		/** A monster to defeat. */
		MOB,
		/** A Mark Thief: pockets the challenge items of kills near it. */
		THIEF,
		/** A boss (possibly a multi-phase Wave/Arena Champion). */
		BOSS,
		/** The NPC a PROTECT objective guards. */
		WARD,
		/** An object a player activates. */
		SEAL,
		/** The NPC of a TALK objective (Trial Master). */
		TALK,
		/** A decoration marking a location. */
		MARKER,
		/** The Trial Guide at the entrance: progress and abandon. */
		GUIDE,
		/** The Rival Shade of a DUEL objective. */
		RIVAL,
		/** A rival that invaded the challenge (twist). */
		INVADER
	}

	private final Npc _npc;
	private final int _objectiveIndex;
	private final ChallengeSpawnHolder _holder;
	private final NpcRole _role;

	public TrackedNpc(Npc npc, int objectiveIndex, ChallengeSpawnHolder holder, NpcRole role)
	{
		_npc = npc;
		_objectiveIndex = objectiveIndex;
		_holder = holder;
		_role = role;
	}

	public Npc getNpc()
	{
		return _npc;
	}

	/**
	 * @return index of the objective that spawned it, -1 for NPCs of the whole challenge (guide, invader)
	 */
	public int getObjectiveIndex()
	{
		return _objectiveIndex;
	}

	/**
	 * @return the spawn line it came from, {@code null} for NPCs placed by an objective's own settings
	 */
	public ChallengeSpawnHolder getHolder()
	{
		return _holder;
	}

	public NpcRole getRole()
	{
		return _role;
	}
}
