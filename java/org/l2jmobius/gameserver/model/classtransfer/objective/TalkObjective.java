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
package org.l2jmobius.gameserver.model.classtransfer.objective;

import org.l2jmobius.gameserver.model.Location;
import org.l2jmobius.gameserver.model.classtransfer.ChallengeSession;
import org.l2jmobius.gameserver.model.classtransfer.ObjectiveDefinition;
import org.l2jmobius.gameserver.model.classtransfer.TrackedNpc;
import org.l2jmobius.gameserver.model.classtransfer.TrackedNpc.NpcRole;

/**
 * TALK: speak with {@code npcId}, spawned at {@code x/y/z}. The NPC stays after the objective (the Trial Master of the last objective also sends the challenger back).
 * @author Mobius
 */
public class TalkObjective extends AbstractChallengeObjective
{
	private boolean _talked;

	public TalkObjective(ObjectiveDefinition definition)
	{
		super(definition);
	}

	@Override
	public void start(ChallengeSession session)
	{
		final int npcId = params().getInt("npcId", 0);
		final Location loc = getLocation();
		if ((npcId <= 0) || (loc == null))
		{
			fail("The trial is misconfigured (TALK without an NPC or location).");
			return;
		}
		session.spawnAt(npcId, loc, getIndex(), NpcRole.TALK);
		spawnAll(session, NpcRole.MOB);
	}

	@Override
	public boolean onTalk(ChallengeSession session, TrackedNpc tracked)
	{
		if (tracked.getRole() != NpcRole.TALK)
		{
			return false;
		}
		_talked = true;
		return true;
	}

	@Override
	public boolean isComplete()
	{
		return _talked;
	}

	@Override
	public String getProgress()
	{
		return "";
	}

	@Override
	public void finish(ChallengeSession session)
	{
		// Keep the NPC talked to; remove anything else this objective spawned.
		for (TrackedNpc tracked : session.getAlive(getIndex(), null))
		{
			if (tracked.getRole() != NpcRole.TALK)
			{
				session.untrack(tracked.getNpc());
				tracked.getNpc().deleteMe();
			}
		}
	}
}
