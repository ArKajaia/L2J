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
import org.l2jmobius.gameserver.model.actor.Npc;
import org.l2jmobius.gameserver.model.actor.Player;
import org.l2jmobius.gameserver.model.actor.instance.QuestGuard;
import org.l2jmobius.gameserver.model.classtransfer.ChallengeSession;
import org.l2jmobius.gameserver.model.classtransfer.ChallengeSpawnHolder;
import org.l2jmobius.gameserver.model.classtransfer.ObjectiveDefinition;
import org.l2jmobius.gameserver.model.classtransfer.TrackedNpc;
import org.l2jmobius.gameserver.model.classtransfer.TrackedNpc.NpcRole;

/**
 * PROTECT: keep {@code npcId} (the Ward, spawned at {@code x/y/z}) alive for {@code seconds}. The {@code <spawn>} lines attack the Ward, and a fresh group arrives every {@code interval} seconds. The Ward's death fails the challenge. The Ward should be a QuestGuard: players can't hit it
 * and it doesn't fight back.
 * @author Mobius
 */
public class ProtectObjective extends AbstractChallengeObjective
{
	private final int _seconds;
	private final int _interval;
	private final int _maxAlive;
	private Npc _ward;
	private int _elapsed;

	public ProtectObjective(ObjectiveDefinition definition)
	{
		super(definition);
		_seconds = Math.max(10, params().getInt("seconds", 90));
		_interval = Math.max(5, params().getInt("interval", 20));
		_maxAlive = Math.max(1, params().getInt("maxAlive", getSpawnTotal() * 3));
	}

	@Override
	public void start(ChallengeSession session)
	{
		final int wardId = params().getInt("npcId", 0);
		final Location loc = getLocation();
		if ((wardId <= 0) || (loc == null))
		{
			fail("The trial is misconfigured (PROTECT without a Ward).");
			return;
		}

		_ward = session.spawnAt(wardId, loc, getIndex(), NpcRole.WARD);
		if (_ward == null)
		{
			fail("The Ward could not be summoned.");
			return;
		}
		if (_ward instanceof QuestGuard)
		{
			((QuestGuard) _ward).setPassive(true);
		}

		spawnAttackers(session);
	}

	private void spawnAttackers(ChallengeSession session)
	{
		for (ChallengeSpawnHolder holder : _definition.getSpawns())
		{
			for (int i = 0; i < holder.getCount(); i++)
			{
				if (session.getAlive(getIndex(), NpcRole.MOB).size() >= _maxAlive)
				{
					return;
				}
				attack(session.spawn(holder, getIndex(), NpcRole.MOB), _ward);
			}
		}
	}

	@Override
	public void onKill(ChallengeSession session, TrackedNpc tracked, boolean credited)
	{
		if (tracked.getRole() == NpcRole.WARD)
		{
			fail("The Ward has fallen.");
		}
	}

	@Override
	public void onTick(ChallengeSession session, Player player)
	{
		if ((_ward == null) || _ward.isDead() || isComplete())
		{
			return;
		}

		_elapsed++;
		if ((_elapsed % _interval) == 0)
		{
			spawnAttackers(session);
		}

		// Attackers the challenger isn't holding go back to the Ward.
		if ((_elapsed % 3) == 0)
		{
			for (TrackedNpc attacker : session.getAlive(getIndex(), NpcRole.MOB))
			{
				if (!attacker.getNpc().isInCombat())
				{
					attack(attacker.getNpc(), _ward);
				}
			}
		}

		if ((_elapsed % 10) == 0)
		{
			session.showProgress();
		}
	}

	@Override
	public boolean isComplete()
	{
		return !isFailed() && (_ward != null) && !_ward.isDead() && (_elapsed >= _seconds);
	}

	@Override
	public String getProgress()
	{
		if (_ward == null)
		{
			return "";
		}
		final int hpPercent = (int) Math.round((_ward.getCurrentHp() * 100) / Math.max(1, _ward.getMaxHp()));
		return "Ward " + hpPercent + "% HP, " + formatTime(_seconds - _elapsed) + " left";
	}
}
