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

import java.util.function.Supplier;

import org.l2jmobius.gameserver.config.DevelopmentConfig;
import org.l2jmobius.gameserver.network.PacketLogger;
import org.l2jmobius.gameserver.network.clientpackets.ClientPacket;

/**
 * Project client packets: D0:00FE followed by an int32 sub-opcode, the mirror of the FE:00FE server packets. The game client (l2DecompileTest, engine/custom_requests.cpp) sends them; layouts in client/PassiveTree/PROTOCOL.txt.
 */
public enum CustomClientPackets
{
	REQUEST_EX2_PASSIVE_TREE_CHUNK(1, RequestEx2PassiveTreeChunk::new),
	REQUEST_EX2_PASSIVE_TREE_STATE(2, RequestEx2PassiveTreeState::new),
	REQUEST_EX2_PASSIVE_TREE_ALLOCATE(3, RequestEx2PassiveTreeAllocate::new),
	REQUEST_EX2_PASSIVE_TREE_DEALLOCATE(4, RequestEx2PassiveTreeDeallocate::new),
	REQUEST_EX2_PASSIVE_TREE_RESET(5, RequestEx2PassiveTreeReset::new),
	REQUEST_EX2_PASSIVE_TREE_TEMPLATE(6, RequestEx2PassiveTreeTemplate::new);

	private final int _subId;
	private final Supplier<ClientPacket> _supplier;

	CustomClientPackets(int subId, Supplier<ClientPacket> supplier)
	{
		_subId = subId;
		_supplier = supplier;
	}

	public int getSubId()
	{
		return _subId;
	}

	/**
	 * @param subId the sub-opcode after D0:00FE
	 * @return a new packet of that kind, or {@code null} for an unknown sub-opcode (the packet is dropped)
	 */
	public static ClientPacket newPacket(int subId)
	{
		for (CustomClientPackets packet : values())
		{
			if (packet._subId == subId)
			{
				final ClientPacket result = packet._supplier.get();
				if (DevelopmentConfig.DEBUG_EX_CLIENT_PACKETS && !DevelopmentConfig.EXCLUDED_DEBUG_PACKETS.contains(result.getClass().getSimpleName()))
				{
					PacketLogger.info("[C EX2] " + result.getClass().getSimpleName());
				}
				return result;
			}
		}
		if (DevelopmentConfig.DEBUG_EX_CLIENT_PACKETS && DevelopmentConfig.DEBUG_UNKNOWN_PACKETS)
		{
			PacketLogger.info("[C EX2] 0x" + Integer.toHexString(subId).toUpperCase());
		}
		return null;
	}
}
