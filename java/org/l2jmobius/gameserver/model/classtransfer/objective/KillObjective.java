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

import org.l2jmobius.gameserver.model.classtransfer.ChallengeSession;
import org.l2jmobius.gameserver.model.classtransfer.ObjectiveDefinition;
import org.l2jmobius.gameserver.model.classtransfer.TrackedNpc;
import org.l2jmobius.gameserver.model.classtransfer.TrackedNpc.NpcRole;

/**
 * KILL: defeat {@code count} of the objective's monsters. Each {@code <spawn>} line keeps its {@code count} alive at once until enough kills are made; a single specific monster is simply {@code count="1"} with one spawn line.
 * @author Mobius
 */
public class KillObjective extends AbstractChallengeObjective
{
	private final int _required;
	private int _kills;

	public KillObjective(ObjectiveDefinition definition)
	{
		super(definition);
		_required = Math.max(1, params().getInt("count", getSpawnTotal()));
	}

	@Override
	public void start(ChallengeSession session)
	{
		spawnAll(session, NpcRole.MOB);
	}

	@Override
	public void onKill(ChallengeSession session, TrackedNpc tracked, boolean credited)
	{
		if (tracked.getRole() != NpcRole.MOB)
		{
			return;
		}

		if (credited)
		{
			_kills++;
		}

		if (!isComplete())
		{
			replenish(session, tracked, Math.min(_required - _kills, getSpawnTotal()));
		}
	}

	@Override
	public boolean isComplete()
	{
		return _kills >= _required;
	}

	@Override
	public String getProgress()
	{
		return Math.min(_kills, _required) + "/" + _required;
	}
}
