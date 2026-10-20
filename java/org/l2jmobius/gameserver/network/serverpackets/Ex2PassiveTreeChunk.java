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
import org.l2jmobius.gameserver.data.custom.PassiveTreeBlob;
import org.l2jmobius.gameserver.network.GameClient;
import org.l2jmobius.gameserver.network.ServerPackets;

/**
 * One piece of the passive tree definition (layout: client/PassiveTree/PROTOCOL.txt).<br>
 * {@code int version, short index, short length}, then that many bytes of the definition.
 */
public class Ex2PassiveTreeChunk extends ServerPacket
{
	private final int _version;
	private final int _index;
	private final byte[] _bytes;

	/**
	 * @param blob the live definition
	 * @param index a chunk of it, from 0, below {@link PassiveTreeBlob.Blob#chunkCount()}
	 */
	public Ex2PassiveTreeChunk(PassiveTreeBlob.Blob blob, int index)
	{
		_version = blob.version();
		_index = index;
		_bytes = blob.chunk(index);
	}

	@Override
	public void writeImpl(GameClient client, WritableBuffer buffer)
	{
		ServerPackets.EX2_PASSIVE_TREE_CHUNK.writeId(this, buffer);
		buffer.writeInt(_version);
		buffer.writeShort(_index);
		buffer.writeShort(_bytes.length);
		buffer.writeBytes(_bytes);
	}
}
