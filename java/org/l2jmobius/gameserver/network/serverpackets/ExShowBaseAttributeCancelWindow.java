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
import org.l2jmobius.gameserver.model.item.Weapon;
import org.l2jmobius.gameserver.model.item.instance.Item;
import org.l2jmobius.gameserver.network.GameClient;
import org.l2jmobius.gameserver.network.ServerPackets;

public class ExShowBaseAttributeCancelWindow extends ServerPacket
{
	private final Collection<Item> _items;
	private long _price;
	
	public ExShowBaseAttributeCancelWindow(Player player)
	{
		_items = player.getInventory().getElementItems();
	}
	
	@Override
	public void writeImpl(GameClient client, WritableBuffer buffer)
	{
		ServerPackets.EX_SHOW_BASE_ATTRIBUTE_CANCEL_WINDOW.writeId(this, buffer);
		buffer.writeInt(_items.size());
		for (Item item : _items)
		{
			buffer.writeInt(item.getObjectId());
			buffer.writeLong(getPrice(item));
		}
	}
	
	/**
	 * TODO: Update prices for Top/Mid/Low S80/S84
	 * @param item
	 * @return
	 */
	private long getPrice(Item item)
	{
		switch (item.getTemplate().getCrystalType())
		{
			case S:
			{
				if (item.getTemplate() instanceof Weapon)
				{
					_price = 50000;
				}
				else
				{
					_price = 40000;
				}
				break;
			}
			case S80:
			{
				if (item.getTemplate() instanceof Weapon)
				{
					_price = 100000;
				}
				else
				{
					_price = 80000;
				}
				break;
			}
			case S84:
			{
				if (item.getTemplate() instanceof Weapon)
				{
					_price = 200000;
				}
				else
				{
					_price = 160000;
				}
				break;
			}
		}
		
		return _price;
	}
}
