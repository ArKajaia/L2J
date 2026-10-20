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

import org.l2jmobius.gameserver.config.custom.PassiveTreeConfig;
import org.l2jmobius.gameserver.managers.PassiveTreeManager;
import org.l2jmobius.gameserver.model.actor.Player;
import org.l2jmobius.gameserver.network.clientpackets.ClientPacket;
import org.l2jmobius.gameserver.network.serverpackets.Ex2PassiveTreeResult;

/**
 * D0:00FE:5 - resets the whole tree for the configured cost. No body. The same rules as the web planner and the Community Board (PassiveTreeManager.resetTree). Answered with Ex2PassiveTreeResult and Ex2PassiveTreeState.
 */
public class RequestEx2PassiveTreeReset extends ClientPacket
{
	private boolean _whole;

	@Override
	protected void readImpl()
	{
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
		final PassiveTreeManager mgr = PassiveTreeManager.getInstance();
		final int reason;
		if (mgr.getAllocatedNodes(player).isEmpty())
		{
			reason = Ex2PassiveTreeResult.NOT_ALLOCATED;
		}
		else if ((PassiveTreeConfig.RESET_ITEM_COUNT > 0) && (player.getInventory().getInventoryItemCount(PassiveTreeConfig.RESET_ITEM_ID, -1) < PassiveTreeConfig.RESET_ITEM_COUNT))
		{
			reason = Ex2PassiveTreeResult.NOT_ENOUGH_ITEMS;
		}
		else
		{
			reason = mgr.resetTree(player) ? Ex2PassiveTreeResult.OK : Ex2PassiveTreeResult.NOT_AVAILABLE;
		}
		PassiveTreeRequests.answer(player, Ex2PassiveTreeResult.RESET, reason, 0);
	}
}
