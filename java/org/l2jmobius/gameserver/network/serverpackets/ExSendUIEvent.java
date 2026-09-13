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

import java.util.Arrays;
import java.util.List;

import org.l2jmobius.commons.network.WritableBuffer;
import org.l2jmobius.gameserver.model.actor.Player;
import org.l2jmobius.gameserver.network.GameClient;
import org.l2jmobius.gameserver.network.NpcStringId;
import org.l2jmobius.gameserver.network.ServerPackets;

public class ExSendUIEvent extends ServerPacket
{
	private final int _objectId;
	private final boolean _type;
	private final boolean _countUp;
	private final int _startTime;
	private final int _endTime;
	private final int _npcstringId;
	private List<String> _params = null;
	
	/**
	 * @param player
	 * @param hide
	 * @param countUp
	 * @param startTime
	 * @param endTime
	 * @param text
	 */
	public ExSendUIEvent(Player player, boolean hide, boolean countUp, int startTime, int endTime, String text)
	{
		this(player, hide, countUp, startTime, endTime, -1, text);
	}
	
	/**
	 * @param player
	 * @param hide
	 * @param countUp
	 * @param startTime
	 * @param endTime
	 * @param npcString
	 * @param params
	 */
	public ExSendUIEvent(Player player, boolean hide, boolean countUp, int startTime, int endTime, NpcStringId npcString, String... params)
	{
		this(player, hide, countUp, startTime, endTime, npcString.getId(), params);
	}
	
	/**
	 * @param player
	 * @param hide
	 * @param countUp
	 * @param startTime
	 * @param endTime
	 * @param npcstringId
	 * @param params
	 */
	public ExSendUIEvent(Player player, boolean hide, boolean countUp, int startTime, int endTime, int npcstringId, String... params)
	{
		_objectId = player.getObjectId();
		_type = hide;
		_countUp = countUp;
		_startTime = startTime;
		_endTime = endTime;
		_npcstringId = npcstringId;
		_params = Arrays.asList(params);
	}
	
	@Override
	public void writeImpl(GameClient client, WritableBuffer buffer)
	{
		ServerPackets.EX_SEND_UI_EVENT.writeId(this, buffer);
		buffer.writeInt(_objectId);
		buffer.writeInt(_type); // 0 = show, 1 = hide (there is 2 = pause and 3 = resume also but they don't work well you can only pause count down and you cannot resume it because resume hides the counter).
		buffer.writeInt(0); // unknown
		buffer.writeInt(0); // unknown
		buffer.writeString(_countUp ? "1" : "0"); // 0 = count down, 1 = count up
		// timer always disappears 10 seconds before end
		buffer.writeString(String.valueOf(_startTime / 60));
		buffer.writeString(String.valueOf(_startTime % 60));
		buffer.writeString(String.valueOf(_endTime / 60));
		buffer.writeString(String.valueOf(_endTime % 60));
		buffer.writeInt(_npcstringId);
		if (_params != null)
		{
			for (String param : _params)
			{
				buffer.writeString(param);
			}
		}
	}
}
