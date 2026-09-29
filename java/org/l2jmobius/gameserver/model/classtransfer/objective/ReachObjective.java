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
import org.l2jmobius.gameserver.model.actor.Player;
import org.l2jmobius.gameserver.model.classtransfer.ChallengeSession;
import org.l2jmobius.gameserver.model.classtransfer.ObjectiveDefinition;
import org.l2jmobius.gameserver.model.classtransfer.TrackedNpc.NpcRole;

/**
 * REACH: stand within {@code radius} of {@code x/y/z}. An optional {@code npcId} marks the spot; the objective's {@code <spawn>} lines are guards on the way. Checked by the session's one-second tick while this objective is current - never by scanning the world.
 * @author Mobius
 */
public class ReachObjective extends AbstractChallengeObjective
{
	private final Location _target;
	private final int _radius;
	private boolean _reached;

	public ReachObjective(ObjectiveDefinition definition)
	{
		super(definition);
		_target = getLocation();
		_radius = Math.max(50, params().getInt("radius", 150));
	}

	@Override
	public void start(ChallengeSession session)
	{
		if (_target == null)
		{
			fail("The trial is misconfigured (REACH without a location).");
			return;
		}

		final int markerId = params().getInt("npcId", 0);
		if (markerId > 0)
		{
			session.spawnAt(markerId, _target, getIndex(), NpcRole.MARKER);
		}
		spawnAll(session, NpcRole.MOB);
	}

	@Override
	public void onTick(ChallengeSession session, Player player)
	{
		if ((player != null) && (_target != null) && player.isInsideRadius3D(_target, _radius))
		{
			_reached = true;
		}
	}

	@Override
	public boolean isComplete()
	{
		return _reached;
	}

	@Override
	public String getProgress()
	{
		return "";
	}
}
