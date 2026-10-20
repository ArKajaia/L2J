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
 * Tells the client which passive tree definition is live (layout: client/PassiveTree/PROTOCOL.txt). A client whose cached copy has another version or hash fetches it with RequestEx2PassiveTreeChunk.<br>
 * {@code int version, byte[32] sha256, int blobSize, short chunkCount}.
 */
public class Ex2PassiveTreeVersion extends ServerPacket
{
	private final PassiveTreeBlob.Blob _blob;

	public Ex2PassiveTreeVersion()
	{
		_blob = PassiveTreeBlob.get();
	}

	@Override
	public void writeImpl(GameClient client, WritableBuffer buffer)
	{
		ServerPackets.EX2_PASSIVE_TREE_VERSION.writeId(this, buffer);
		buffer.writeInt(_blob.version());
		buffer.writeBytes(_blob.hash());
		buffer.writeInt(_blob.data().length);
		buffer.writeShort(_blob.chunkCount());
	}
}
