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

import java.util.HashMap;
import java.util.Map;

import org.l2jmobius.gameserver.managers.ClassTransferChallengeManager;
import org.l2jmobius.gameserver.model.actor.Npc;
import org.l2jmobius.gameserver.model.actor.Player;
import org.l2jmobius.gameserver.model.actor.instance.Monster;
import org.l2jmobius.gameserver.model.classtransfer.ChallengeSession;
import org.l2jmobius.gameserver.model.classtransfer.ChallengeSpawnHolder;
import org.l2jmobius.gameserver.model.classtransfer.ObjectiveDefinition;
import org.l2jmobius.gameserver.model.classtransfer.TrackedNpc;
import org.l2jmobius.gameserver.model.classtransfer.TrackedNpc.NpcRole;

/**
 * BOSS: defeat every boss of the {@code <spawn>} lines. A line with {@code waves="N"} is a Wave Champion (the open-world Wave Challenge engine): each "death" before the last heals it and makes it stronger. {@code arenaBuffs="true"} casts the Survival Arena challenger buffs at every
 * phase, and {@code enrageSeconds} casts the arena enrage skill when a phase drags on - an Arena Champion.
 * @author Mobius
 */
public class BossObjective extends AbstractChallengeObjective
{
	private final int _total;
	private int _defeated;

	/** Boss object id -> [last seen phase, phase start time (s), enraged 0/1, phase count]. */
	private final Map<Integer, long[]> _phases = new HashMap<>();
	private int _seconds;

	public BossObjective(ObjectiveDefinition definition)
	{
		super(definition);
		_total = Math.max(1, getSpawnTotal());
	}

	@Override
	public void start(ChallengeSession session)
	{
		for (ChallengeSpawnHolder holder : _definition.getSpawns())
		{
			for (int i = 0; i < holder.getCount(); i++)
			{
				final Npc boss = session.spawn(holder, getIndex(), NpcRole.BOSS);
				if (boss != null)
				{
					_phases.put(boss.getObjectId(), new long[]
					{
						1,
						0,
						0,
						holder.getWaves()
					});
				}
			}
		}

		if (_phases.isEmpty())
		{
			fail("The trial boss could not be summoned.");
		}
	}

	@Override
	public void onTick(ChallengeSession session, Player player)
	{
		_seconds++;
		for (TrackedNpc tracked : session.getAlive(getIndex(), NpcRole.BOSS))
		{
			final Npc npc = tracked.getNpc();
			final long[] phase = _phases.get(npc.getObjectId());
			if ((phase == null) || !npc.isMonster())
			{
				continue;
			}

			final Monster boss = npc.asMonster();
			final ChallengeSpawnHolder holder = tracked.getHolder();
			if (boss.isWaveChallenge() && (boss.getWaveChallengeWave() != phase[0]))
			{
				// The Wave Challenge engine healed it: a new, stronger phase - or, when nobody kept fighting it (a knockout), a full recovery to phase 1.
				final boolean recovered = boss.getWaveChallengeWave() < phase[0];
				phase[0] = boss.getWaveChallengeWave();
				phase[1] = _seconds;
				phase[2] = 0;
				ClassTransferChallengeManager.getInstance().onBossPhase(boss, holder);
				session.announce(recovered ? boss.getName() + " recovered while you were down - back to phase 1!" : boss.getName() + " rises again - phase " + phase[0] + "/" + boss.getWaveChallengeTotal() + "!");
			}

			if ((holder != null) && (holder.getEnrageSeconds() > 0) && (phase[2] == 0) && ((_seconds - phase[1]) >= holder.getEnrageSeconds()))
			{
				phase[2] = 1;
				ClassTransferChallengeManager.getInstance().enrage(boss);
				session.announce(boss.getName() + " is ENRAGED! Finish it quickly!");
			}
		}
	}

	@Override
	public void onKill(ChallengeSession session, TrackedNpc tracked, boolean credited)
	{
		if (tracked.getRole() != NpcRole.BOSS)
		{
			return;
		}
		_defeated++;
		session.setLastDefeatedBoss(tracked.getNpc());
	}

	@Override
	public boolean isComplete()
	{
		return _defeated >= _total;
	}

	@Override
	public String getProgress()
	{
		if (_total > 1)
		{
			return _defeated + "/" + _total;
		}
		for (long[] phase : _phases.values())
		{
			if ((_defeated == 0) && (phase[3] > 1))
			{
				return "phase " + phase[0] + "/" + phase[3];
			}
		}
		return "";
	}
}
