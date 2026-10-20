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
 * D0:00FE:4 - refunds one node for Adena: {@code int nodeId}. The same rules as the web planner (PassiveTreeManager.deallocateNode). Answered with Ex2PassiveTreeResult and Ex2PassiveTreeState.
 */
public class RequestEx2PassiveTreeDeallocate extends ClientPacket
{
	private int _nodeId;
	private boolean _whole;

	@Override
	protected void readImpl()
	{
		_nodeId = readInt();
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
		switch (PassiveTreeManager.getInstance().deallocateNode(player, _nodeId))
		{
			case OK:
			{
				reason = Ex2PassiveTreeResult.OK;
				break;
			}
			case IS_ORIGIN:
			{
				reason = Ex2PassiveTreeResult.IS_ORIGIN;
				break;
			}
			case NOT_ALLOCATED:
			{
				reason = Ex2PassiveTreeResult.NOT_ALLOCATED;
				break;
			}
			case WOULD_DISCONNECT:
			{
				reason = Ex2PassiveTreeResult.WOULD_DISCONNECT;
				break;
			}
			case NOT_ENOUGH_ADENA:
			{
				reason = Ex2PassiveTreeResult.NOT_ENOUGH_ADENA;
				break;
			}
			default:
			{
				reason = Ex2PassiveTreeResult.NOT_AVAILABLE;
				break;
			}
		}
		PassiveTreeRequests.answer(player, Ex2PassiveTreeResult.DEALLOCATE, reason, _nodeId);
	}
}
