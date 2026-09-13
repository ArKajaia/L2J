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
package org.l2jmobius.gameserver.network.clientpackets;

import org.l2jmobius.gameserver.config.PlayerConfig;
import org.l2jmobius.gameserver.model.actor.Player;
import org.l2jmobius.gameserver.model.variables.PlayerVariables;
import org.l2jmobius.gameserver.network.ConnectionState;

/**
 * @author Mobius
 */
public class RequestSaveKeyMapping extends ClientPacket
{
	public static final String SPLIT_VAR = "	";
	
	private byte[] _uiKeyMapping;
	
	@Override
	protected void readImpl()
	{
		final int dataSize = readInt();
		if (dataSize > 0)
		{
			_uiKeyMapping = readBytes(dataSize);
		}
	}
	
	@Override
	protected void runImpl()
	{
		final Player player = getPlayer();
		if (!PlayerConfig.STORE_UI_SETTINGS || //
			(player == null) || //
			(_uiKeyMapping == null) || //
			(getClient().getConnectionState() != ConnectionState.IN_GAME))
		{
			return;
		}
		
		final StringBuilder sb = new StringBuilder(_uiKeyMapping.length * 4);
		for (Byte b : _uiKeyMapping)
		{
			sb.append(b).append(SPLIT_VAR);
		}
		
		player.getVariables().set(PlayerVariables.UI_KEY_MAPPING, sb.toString());
	}
}
