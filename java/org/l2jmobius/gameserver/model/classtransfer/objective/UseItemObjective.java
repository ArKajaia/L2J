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
 * USE_ITEM: use the challenge item {@code itemId} {@code count} times (default 1). The item is handed out when the objective starts and taken back when it ends. With {@code x/y/z}, it only works within {@code radius} of that spot, which {@code npcId} can mark. The item needs the
 * {@code ClassTransferChallengeItem} handler.
 * @author Mobius
 */
public class UseItemObjective extends AbstractChallengeObjective
{
	private final int _itemId;
	private final int _required;
	private final Location _spot;
	private final int _radius;
	private int _uses;

	public UseItemObjective(ObjectiveDefinition definition)
	{
		super(definition);
		_itemId = params().getInt("itemId", 0);
		_required = Math.max(1, params().getInt("count", 1));
		_spot = getLocation();
		_radius = Math.max(50, params().getInt("radius", 150));
	}

	@Override
	public void start(ChallengeSession session)
	{
		if (_itemId <= 0)
		{
			fail("The trial is misconfigured (USE_ITEM without an item).");
			return;
		}

		session.giveItem(_itemId, _required);
		final int markerId = params().getInt("npcId", 0);
		if ((markerId > 0) && (_spot != null))
		{
			session.spawnAt(markerId, _spot, getIndex(), NpcRole.MARKER);
		}
		spawnAll(session, NpcRole.MOB);
	}

	@Override
	public boolean onItemUse(ChallengeSession session, Player player, int itemId)
	{
		if (itemId != _itemId)
		{
			return false;
		}

		if ((_spot != null) && !player.isInsideRadius3D(_spot, _radius))
		{
			player.sendMessage("Nothing happens. Use it in the marked place.");
			return true;
		}

		if (session.takeItem(_itemId, 1) > 0)
		{
			_uses++;
		}
		return true;
	}

	@Override
	public boolean isComplete()
	{
		return _uses >= _required;
	}

	@Override
	public String getProgress()
	{
		return _required > 1 ? (Math.min(_uses, _required) + "/" + _required) : "";
	}

	@Override
	public void finish(ChallengeSession session)
	{
		session.takeItem(_itemId, Long.MAX_VALUE);
		super.finish(session);
	}
}
