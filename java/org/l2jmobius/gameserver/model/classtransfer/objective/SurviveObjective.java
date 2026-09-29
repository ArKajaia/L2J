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

import org.l2jmobius.gameserver.model.actor.Npc;
import org.l2jmobius.gameserver.model.actor.Player;
import org.l2jmobius.gameserver.model.classtransfer.ChallengeSession;
import org.l2jmobius.gameserver.model.classtransfer.ChallengeSpawnHolder;
import org.l2jmobius.gameserver.model.classtransfer.ObjectiveDefinition;
import org.l2jmobius.gameserver.model.classtransfer.TrackedNpc.NpcRole;

/**
 * SURVIVE: stay on your feet for {@code seconds} while the {@code <spawn>} lines come at you, with a fresh group every {@code interval} seconds (at most {@code maxAlive} at once). A knockout restarts the count unless {@code resetOnKnockout="false"}.
 * @author Mobius
 */
public class SurviveObjective extends AbstractChallengeObjective
{
	private final int _seconds;
	private final int _interval;
	private final int _maxAlive;
	private final boolean _resetOnKnockout;
	private int _elapsed;

	public SurviveObjective(ObjectiveDefinition definition)
	{
		super(definition);
		_seconds = Math.max(10, params().getInt("seconds", 60));
		_interval = Math.max(5, params().getInt("interval", 15));
		_maxAlive = Math.max(1, params().getInt("maxAlive", getSpawnTotal() * 3));
		_resetOnKnockout = params().getBoolean("resetOnKnockout", true);
	}

	@Override
	public void start(ChallengeSession session)
	{
		spawnGroup(session);
	}

	private void spawnGroup(ChallengeSession session)
	{
		final Player player = session.getPlayerInside();
		for (ChallengeSpawnHolder holder : _definition.getSpawns())
		{
			for (int i = 0; i < holder.getCount(); i++)
			{
				if (session.getAlive(getIndex(), NpcRole.MOB).size() >= _maxAlive)
				{
					return;
				}
				final Npc npc = session.spawn(holder, getIndex(), NpcRole.MOB);
				if ((player != null) && !session.isKnockedOut())
				{
					attack(npc, player);
				}
			}
		}
	}

	@Override
	public void onTick(ChallengeSession session, Player player)
	{
		if ((player == null) || isComplete())
		{
			return;
		}

		_elapsed++;
		if ((_elapsed % _interval) == 0)
		{
			spawnGroup(session);
		}
		if ((_elapsed % 10) == 0)
		{
			session.showProgress();
		}
	}

	@Override
	public void onPlayerKnockout(ChallengeSession session)
	{
		if (_resetOnKnockout && (_elapsed > 0))
		{
			_elapsed = 0;
			session.announce("You fell - the survival count starts over.");
		}
	}

	@Override
	public boolean isComplete()
	{
		return _elapsed >= _seconds;
	}

	@Override
	public String getProgress()
	{
		return formatTime(_seconds - _elapsed) + " left";
	}
}
