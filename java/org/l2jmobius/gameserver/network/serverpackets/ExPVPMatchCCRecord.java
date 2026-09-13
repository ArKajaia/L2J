/*
 * Copyright (c) 2013 L2jMobius
 * 
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 * 
 * The above copyright notice and this permission notice shall be
 * included in all copies or substantial portions of the Software.
 * 
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY,
 * WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR
 * IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
 */
package org.l2jmobius.gameserver.network.serverpackets;

import java.util.Map;
import java.util.Map.Entry;

import org.l2jmobius.commons.network.WritableBuffer;
import org.l2jmobius.gameserver.model.actor.Player;
import org.l2jmobius.gameserver.network.GameClient;
import org.l2jmobius.gameserver.network.ServerPackets;

/**
 * @author Mobius
 */
public class ExPVPMatchCCRecord extends ServerPacket
{
	public static final int INITIALIZE = 0;
	public static final int UPDATE = 1;
	public static final int FINISH = 2;
	
	private final int _state;
	private final Map<Player, Integer> _players;
	
	public ExPVPMatchCCRecord(int state, Map<Player, Integer> players)
	{
		_state = state;
		_players = players;
	}
	
	@Override
	public void writeImpl(GameClient client, WritableBuffer buffer)
	{
		ServerPackets.EX_PVP_MATCH_CC_RECORD.writeId(this, buffer);
		buffer.writeInt(_state); // 0 - initialize, 1 - update, 2 - finish
		buffer.writeInt(Math.min(_players.size(), 25));
		int counter = 0;
		for (Entry<Player, Integer> entry : _players.entrySet())
		{
			counter++;
			if (counter > 25)
			{
				break;
			}
			
			buffer.writeString(entry.getKey().getName());
			buffer.writeInt(entry.getValue());
		}
	}
}
