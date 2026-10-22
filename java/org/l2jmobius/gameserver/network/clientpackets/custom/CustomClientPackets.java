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

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

import org.l2jmobius.gameserver.config.DevelopmentConfig;
import org.l2jmobius.gameserver.network.PacketLogger;
import org.l2jmobius.gameserver.network.clientpackets.ClientPacket;

/**
 * Project client packets: D0:00FE followed by an int32 sub-opcode, the mirror of the FE:00FE server packets. The game client (l2DecompileTest, engine/custom_requests.cpp) sends them; layouts in client/PassiveTree/PROTOCOL.txt.
 * <p>
 * Scripts add their own with {@link #register} (the bestiary's, subs 7-12, client/Bestiary/PROTOCOL.txt): the packets of a feature that lives in the scripts stay there.
 */
public enum CustomClientPackets
{
	REQUEST_EX2_PASSIVE_TREE_CHUNK(1, RequestEx2PassiveTreeChunk::new),
	REQUEST_EX2_PASSIVE_TREE_STATE(2, RequestEx2PassiveTreeState::new),
	REQUEST_EX2_PASSIVE_TREE_ALLOCATE(3, RequestEx2PassiveTreeAllocate::new),
	REQUEST_EX2_PASSIVE_TREE_DEALLOCATE(4, RequestEx2PassiveTreeDeallocate::new),
	REQUEST_EX2_PASSIVE_TREE_RESET(5, RequestEx2PassiveTreeReset::new),
	REQUEST_EX2_PASSIVE_TREE_TEMPLATE(6, RequestEx2PassiveTreeTemplate::new);

	/** Packets registered by the scripts, by sub-opcode. */
	private static final Map<Integer, Supplier<ClientPacket>> REGISTERED = new ConcurrentHashMap<>();

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
	 * Adds a packet of the scripts, or replaces the one a script registered before (a script reload).
	 * @param subId its sub-opcode after D0:00FE, not one of this enum's
	 * @param supplier makes a new packet of that kind
	 */
	public static void register(int subId, Supplier<ClientPacket> supplier)
	{
		for (CustomClientPackets packet : values())
		{
			if (packet._subId == subId)
			{
				throw new IllegalArgumentException("Custom client packet " + subId + " is " + packet.name());
			}
		}
		REGISTERED.put(subId, supplier);
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
				return logged(packet._supplier.get());
			}
		}
		final Supplier<ClientPacket> registered = REGISTERED.get(subId);
		if (registered != null)
		{
			return logged(registered.get());
		}
		if (DevelopmentConfig.DEBUG_EX_CLIENT_PACKETS && DevelopmentConfig.DEBUG_UNKNOWN_PACKETS)
		{
			PacketLogger.info("[C EX2] 0x" + Integer.toHexString(subId).toUpperCase());
		}
		return null;
	}

	private static ClientPacket logged(ClientPacket result)
	{
		if (DevelopmentConfig.DEBUG_EX_CLIENT_PACKETS && !DevelopmentConfig.EXCLUDED_DEBUG_PACKETS.contains(result.getClass().getSimpleName()))
		{
			PacketLogger.info("[C EX2] " + result.getClass().getSimpleName());
		}
		return result;
	}
}
