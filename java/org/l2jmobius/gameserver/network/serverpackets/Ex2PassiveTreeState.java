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
package org.l2jmobius.gameserver.network.serverpackets;

import org.l2jmobius.commons.network.WritableBuffer;
import org.l2jmobius.gameserver.managers.PassiveTreeManager;
import org.l2jmobius.gameserver.managers.PassiveTreeView;
import org.l2jmobius.gameserver.model.actor.Player;
import org.l2jmobius.gameserver.network.GameClient;
import org.l2jmobius.gameserver.network.ServerPackets;

/**
 * The player's passive tree: what the client can't work out from the definition it caches (layout: client/PassiveTree/PROTOCOL.txt).<br>
 * {@code int earnedPoints, int spentPoints, short count, count x int nodeId}, then the templates:
 * {@code byte active, byte inPeaceZone, byte peaceOnly, int delaySeconds, int cooldownMs, byte count, count x (short nodes, short points)}, then {@code String name, int level}.
 */
public class Ex2PassiveTreeState extends ServerPacket
{
	private final PassiveTreeView _view;

	public Ex2PassiveTreeState(Player player)
	{
		_view = PassiveTreeView.of(player);
	}

	@Override
	public void writeImpl(GameClient client, WritableBuffer buffer)
	{
		ServerPackets.EX2_PASSIVE_TREE_STATE.writeId(this, buffer);
		buffer.writeInt(_view.earnedPoints());
		buffer.writeInt(_view.spentPoints());
		buffer.writeShort(_view.allocated().size());
		for (int nodeId : _view.allocated())
		{
			buffer.writeInt(nodeId);
		}
		buffer.writeByte(_view.activeTemplate());
		buffer.writeByte(_view.inPeaceZone());
		buffer.writeByte(_view.peaceOnly());
		buffer.writeInt(_view.delaySeconds());
		buffer.writeInt((int) Math.min(Integer.MAX_VALUE, _view.cooldownMs()));
		buffer.writeByte(_view.templates().size());
		for (PassiveTreeManager.TemplateInfo info : _view.templates())
		{
			buffer.writeShort(info.nodes());
			buffer.writeShort(info.points());
		}
		buffer.writeString(_view.name());
		buffer.writeInt(_view.level());
	}
}
