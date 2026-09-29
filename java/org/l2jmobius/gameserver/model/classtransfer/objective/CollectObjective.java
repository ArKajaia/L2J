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

import org.l2jmobius.commons.util.Rnd;
import org.l2jmobius.gameserver.config.custom.ThiefMonsterConfig;
import org.l2jmobius.gameserver.model.actor.Npc;
import org.l2jmobius.gameserver.model.classtransfer.ChallengeSession;
import org.l2jmobius.gameserver.model.classtransfer.ChallengeSpawnHolder;
import org.l2jmobius.gameserver.model.classtransfer.ObjectiveDefinition;
import org.l2jmobius.gameserver.model.classtransfer.SpawnVariant;
import org.l2jmobius.gameserver.model.classtransfer.TrackedNpc;
import org.l2jmobius.gameserver.model.classtransfer.TrackedNpc.NpcRole;

/**
 * COLLECT: gather {@code count} challenge items ({@code itemId}, 0 for a plain counter) that the objective's monsters drop with {@code chance}% each. Only this objective's own counter counts, so items brought from outside never help, and whatever it gave is taken back when it completes.
 * <p>
 * A spawn line with {@code variant="THIEF"} is a Mark Thief (a Thief monster): an item from a kill within the Thief's detection radius goes into its bag instead of to the challenger, while its {@code [Thief: n%]} bag fills as usual. Killing the Thief returns every pocketed item plus up to
 * {@code thiefBonus} more for a full bag - wait for a bigger payout, or kill it early.
 * @author Mobius
 */
public class CollectObjective extends AbstractChallengeObjective
{
	private final int _required;
	private final int _itemId;
	private final int _chance;
	private final int _thiefBonus;
	private final int _mobTotal;
	private int _collected;
	private int _pocketed;

	public CollectObjective(ObjectiveDefinition definition)
	{
		super(definition);
		_required = Math.max(1, params().getInt("count", 1));
		_itemId = params().getInt("itemId", 0);
		_chance = Math.max(1, Math.min(100, params().getInt("chance", 50)));
		_thiefBonus = Math.max(0, params().getInt("thiefBonus", 2));

		int mobTotal = 0;
		for (ChallengeSpawnHolder holder : definition.getSpawns())
		{
			if (holder.getVariant() != SpawnVariant.THIEF)
			{
				mobTotal += holder.getCount();
			}
		}
		_mobTotal = mobTotal;
	}

	@Override
	public void start(ChallengeSession session)
	{
		for (ChallengeSpawnHolder holder : _definition.getSpawns())
		{
			final NpcRole role = holder.getVariant() == SpawnVariant.THIEF ? NpcRole.THIEF : NpcRole.MOB;
			for (int i = 0; i < holder.getCount(); i++)
			{
				session.spawn(holder, getIndex(), role);
			}
		}

		if (!session.getAlive(getIndex(), NpcRole.THIEF).isEmpty())
		{
			session.announce("A Mark Thief lurks nearby. Marks won near it end up in its bag!");
		}
	}

	@Override
	public void onKill(ChallengeSession session, TrackedNpc tracked, boolean credited)
	{
		if (tracked.getRole() == NpcRole.THIEF)
		{
			if (credited)
			{
				final Npc thief = tracked.getNpc();
				final int fullness = thief.isMonster() ? thief.asMonster().getThiefPercent() : 0;
				final int bonus = (int) Math.round((_thiefBonus * fullness) / 100.0);
				final int recovered = _pocketed + bonus;
				_pocketed = 0;
				if (recovered > 0)
				{
					collect(session, recovered);
					session.announce("The Mark Thief's bag bursts open: " + recovered + " mark" + (recovered > 1 ? "s" : "") + " recovered!");
				}
			}
			return;
		}

		if (tracked.getRole() != NpcRole.MOB)
		{
			return;
		}

		if (credited && (Rnd.get(100) < _chance))
		{
			final Npc thief = findThiefNear(session, tracked.getNpc());
			if (thief != null)
			{
				_pocketed++;
				session.announce("The Mark Thief snatches the mark! (" + _pocketed + " in its bag)");
			}
			else
			{
				collect(session, 1);
			}
		}

		if (!isComplete())
		{
			replenish(session, tracked, _mobTotal);
		}
	}

	private void collect(ChallengeSession session, int amount)
	{
		final int counted = Math.min(amount, _required - _collected);
		if (counted <= 0)
		{
			return;
		}

		_collected += counted;
		if (_itemId > 0)
		{
			session.giveItem(_itemId, counted);
		}
	}

	private Npc findThiefNear(ChallengeSession session, Npc victim)
	{
		for (TrackedNpc thief : session.getAlive(getIndex(), NpcRole.THIEF))
		{
			if (thief.getNpc().isInsideRadius3D(victim, ThiefMonsterConfig.DETECTION_RADIUS))
			{
				return thief.getNpc();
			}
		}
		return null;
	}

	@Override
	public boolean isComplete()
	{
		return _collected >= _required;
	}

	@Override
	public String getProgress()
	{
		return _collected + "/" + _required + (_pocketed > 0 ? " (Thief holds " + _pocketed + ")" : "");
	}

	@Override
	public void finish(ChallengeSession session)
	{
		// The marks are handed in.
		if (_itemId > 0)
		{
			session.takeItem(_itemId, _collected);
		}
		super.finish(session);
	}
}
