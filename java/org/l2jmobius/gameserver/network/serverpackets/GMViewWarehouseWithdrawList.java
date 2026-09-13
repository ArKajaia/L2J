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

import java.util.Collection;

import org.l2jmobius.commons.network.WritableBuffer;
import org.l2jmobius.gameserver.model.actor.Player;
import org.l2jmobius.gameserver.model.clan.Clan;
import org.l2jmobius.gameserver.model.item.instance.Item;
import org.l2jmobius.gameserver.network.GameClient;
import org.l2jmobius.gameserver.network.ServerPackets;

public class GMViewWarehouseWithdrawList extends AbstractItemPacket
{
	private final Collection<Item> _items;
	private final String _playerName;
	private final long _money;
	
	public GMViewWarehouseWithdrawList(Player player)
	{
		_items = player.getWarehouse().getItems();
		_playerName = player.getName();
		_money = player.getWarehouse().getAdena();
	}
	
	public GMViewWarehouseWithdrawList(Clan clan)
	{
		_playerName = clan.getLeaderName();
		_items = clan.getWarehouse().getItems();
		_money = clan.getWarehouse().getAdena();
	}
	
	@Override
	public void writeImpl(GameClient client, WritableBuffer buffer)
	{
		ServerPackets.GM_VIEW_WAREHOUSE_WITHDRAW_LIST.writeId(this, buffer);
		buffer.writeString(_playerName);
		buffer.writeLong(_money);
		buffer.writeShort(_items.size());
		for (Item item : _items)
		{
			writeItem(item, buffer);
			buffer.writeInt(item.getObjectId());
		}
	}
}
