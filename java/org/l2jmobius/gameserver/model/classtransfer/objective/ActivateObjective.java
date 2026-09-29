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
import org.l2jmobius.gameserver.model.classtransfer.ChallengeSpawnHolder;
import org.l2jmobius.gameserver.model.classtransfer.ObjectiveDefinition;
import org.l2jmobius.gameserver.model.classtransfer.TrackedNpc;
import org.l2jmobius.gameserver.model.classtransfer.TrackedNpc.NpcRole;
import org.l2jmobius.gameserver.network.serverpackets.MagicSkillUse;

/**
 * ACTIVATE: activate {@code count} object NPCs. The {@code <spawn>} lines whose {@code npcId} equals the objective's {@code npcId} are the objects (Trial Seals); every other line spawns their guards. An activated object vanishes.
 * @author Mobius
 */
public class ActivateObjective extends AbstractChallengeObjective
{
	private static final int ACTIVATION_EFFECT_SKILL_ID = 2036; // Blessed Scroll of Escape cast effect, only visual.

	private final int _objectNpcId;
	private final int _required;
	private int _activated;

	public ActivateObjective(ObjectiveDefinition definition)
	{
		super(definition);
		_objectNpcId = params().getInt("npcId", 0);

		int objects = 0;
		for (ChallengeSpawnHolder holder : definition.getSpawns())
		{
			if (holder.getNpcId() == _objectNpcId)
			{
				objects += holder.getCount();
			}
		}
		_required = Math.max(1, Math.min(objects, params().getInt("count", objects)));
	}

	@Override
	public void start(ChallengeSession session)
	{
		for (ChallengeSpawnHolder holder : _definition.getSpawns())
		{
			final NpcRole role = holder.getNpcId() == _objectNpcId ? NpcRole.SEAL : NpcRole.MOB;
			for (int i = 0; i < holder.getCount(); i++)
			{
				session.spawn(holder, getIndex(), role);
			}
		}
	}

	@Override
	public boolean onActivate(ChallengeSession session, TrackedNpc tracked)
	{
		if (tracked.getRole() != NpcRole.SEAL)
		{
			return false;
		}

		_activated++;
		session.untrack(tracked.getNpc());
		tracked.getNpc().broadcastPacket(new MagicSkillUse(tracked.getNpc(), tracked.getNpc(), ACTIVATION_EFFECT_SKILL_ID, 1, 1000, 0));
		tracked.getNpc().deleteMe();
		return true;
	}

	@Override
	public boolean isComplete()
	{
		return _activated >= _required;
	}

	@Override
	public String getProgress()
	{
		return Math.min(_activated, _required) + "/" + _required;
	}
}
