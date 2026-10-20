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
import org.l2jmobius.gameserver.network.GameClient;
import org.l2jmobius.gameserver.network.ServerPackets;

/**
 * What became of a passive tree request (layout: client/PassiveTree/PROTOCOL.txt). The client words it.<br>
 * {@code byte action, byte reason, int id} (the node, or the template; 0 for a reset).
 */
public class Ex2PassiveTreeResult extends ServerPacket
{
	public static final int ALLOCATE = 1;
	public static final int DEALLOCATE = 2;
	public static final int RESET = 3;
	public static final int TEMPLATE = 4;

	public static final int OK = 0;
	public static final int NOT_AVAILABLE = 1;
	public static final int IS_ORIGIN = 2;
	public static final int NOT_ALLOCATED = 3;
	public static final int WOULD_DISCONNECT = 4;
	public static final int NOT_ENOUGH_ADENA = 5;
	public static final int NOT_ENOUGH_ITEMS = 6;
	public static final int NOT_IN_PEACE = 7;
	public static final int COOLDOWN = 8;
	public static final int NOT_ENOUGH_POINTS = 9;

	private final int _action;
	private final int _reason;
	private final int _id;

	public Ex2PassiveTreeResult(int action, int reason, int id)
	{
		_action = action;
		_reason = reason;
		_id = id;
	}

	@Override
	public void writeImpl(GameClient client, WritableBuffer buffer)
	{
		ServerPackets.EX2_PASSIVE_TREE_RESULT.writeId(this, buffer);
		buffer.writeByte(_action);
		buffer.writeByte(_reason);
		buffer.writeInt(_id);
	}
}
