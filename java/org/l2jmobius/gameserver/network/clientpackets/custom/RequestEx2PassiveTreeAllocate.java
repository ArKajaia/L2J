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
import org.l2jmobius.gameserver.data.custom.PassiveTreeData;
import org.l2jmobius.gameserver.managers.PassiveTreeManager;
import org.l2jmobius.gameserver.model.actor.Player;
import org.l2jmobius.gameserver.model.passivetree.PassiveNode;
import org.l2jmobius.gameserver.network.clientpackets.ClientPacket;
import org.l2jmobius.gameserver.network.serverpackets.Ex2PassiveTreeResult;

/**
 * D0:00FE:3 - allocates one node: {@code int nodeId}. The same rules as the web planner (PassiveTreeManager.allocate). Answered with Ex2PassiveTreeResult and Ex2PassiveTreeState.
 */
public class RequestEx2PassiveTreeAllocate extends ClientPacket
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
		final PassiveTreeManager mgr = PassiveTreeManager.getInstance();
		final PassiveNode node = PassiveTreeData.getInstance().getNode(_nodeId);
		final int reason;
		if ((node == null) || !PassiveTreeConfig.PASSIVE_TREE_ENABLED)
		{
			reason = Ex2PassiveTreeResult.NOT_AVAILABLE;
		}
		else if (!mgr.getAllocatedNodes(player).contains(_nodeId) && (mgr.getAvailablePoints(player) < node.getCost()))
		{
			reason = Ex2PassiveTreeResult.NOT_ENOUGH_POINTS;
		}
		else
		{
			reason = mgr.allocate(player, _nodeId) ? Ex2PassiveTreeResult.OK : Ex2PassiveTreeResult.NOT_AVAILABLE;
		}
		PassiveTreeRequests.answer(player, Ex2PassiveTreeResult.ALLOCATE, reason, _nodeId);
	}
}
