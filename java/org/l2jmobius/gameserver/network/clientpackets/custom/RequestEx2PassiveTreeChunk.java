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

import org.l2jmobius.gameserver.data.custom.PassiveTreeBlob;
import org.l2jmobius.gameserver.model.actor.Player;
import org.l2jmobius.gameserver.network.clientpackets.ClientPacket;
import org.l2jmobius.gameserver.network.serverpackets.Ex2PassiveTreeChunk;
import org.l2jmobius.gameserver.network.serverpackets.Ex2PassiveTreeVersion;

/**
 * D0:00FE:1 - one chunk of the passive tree definition: {@code int version, short index}. Answered with the chunk, or with Ex2PassiveTreeVersion when the client asks for a version that is no longer live.
 */
public class RequestEx2PassiveTreeChunk extends ClientPacket
{
	private int _version;
	private int _index;
	private boolean _whole;

	@Override
	protected void readImpl()
	{
		_version = readInt();
		_index = Short.toUnsignedInt(readShort());
		_whole = remaining() == 0;
	}

	@Override
	protected void runImpl()
	{
		final Player player = getPlayer();
		if ((player == null) || !_whole || !PassiveTreeRequests.tryChunk(getClient()))
		{
			return;
		}
		final PassiveTreeBlob.Blob blob = PassiveTreeBlob.get();
		if ((_version != blob.version()) || (_index >= blob.chunkCount()))
		{
			player.sendPacket(new Ex2PassiveTreeVersion());
			return;
		}
		player.sendPacket(new Ex2PassiveTreeChunk(blob, _index));
	}
}
