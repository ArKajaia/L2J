/*
 * This file is part of the L2J Mobius project.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>.
 */
package org.l2jmobius.gameserver.network.clientpackets.custom;

import org.l2jmobius.gameserver.managers.PassiveTreeManager;
import org.l2jmobius.gameserver.model.actor.Player;
import org.l2jmobius.gameserver.network.clientpackets.ClientPacket;
import org.l2jmobius.gameserver.network.serverpackets.Ex2PassiveTreeResult;

/**
 * D0:00FE:6 - switches to another template: {@code byte id} (from 1). The same rules as the web planner (PassiveTreeManager.switchTemplate). Answered with Ex2PassiveTreeResult and Ex2PassiveTreeState.
 */
public class RequestEx2PassiveTreeTemplate extends ClientPacket
{
	private int _templateId;
	private boolean _whole;

	@Override
	protected void readImpl()
	{
		_templateId = readUnsignedByte();
		_whole = remaining() == 0;
	}

	@Override
	protected void runImpl()
	{
		final Player player = getPlayer();
		if ((player == null) || !_whole || !PassiveTreeRequests.tryAction(getClient()))
		{
			return;
		}
		final int reason;
		switch (PassiveTreeManager.getInstance().switchTemplate(player, _templateId))
		{
			case OK:
			{
				reason = Ex2PassiveTreeResult.OK;
				break;
			}
			case NOT_PEACE_ZONE:
			{
				reason = Ex2PassiveTreeResult.NOT_IN_PEACE;
				break;
			}
			case COOLDOWN:
			{
				reason = Ex2PassiveTreeResult.COOLDOWN;
				break;
			}
			case NOT_ENOUGH_POINTS:
			{
				reason = Ex2PassiveTreeResult.NOT_ENOUGH_POINTS;
				break;
			}
			default:
			{
				reason = Ex2PassiveTreeResult.NOT_AVAILABLE;
				break;
			}
		}
		PassiveTreeRequests.answer(player, Ex2PassiveTreeResult.TEMPLATE, reason, _templateId);
	}
}
