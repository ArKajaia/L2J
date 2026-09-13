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
import org.l2jmobius.gameserver.config.RatesConfig;
import org.l2jmobius.gameserver.model.buylist.BuyListHolder;
import org.l2jmobius.gameserver.model.buylist.Product;
import org.l2jmobius.gameserver.network.GameClient;
import org.l2jmobius.gameserver.network.ServerPackets;

public class BuyList extends ServerPacket
{
	private final int _listId;
	private final Collection<Product> _list;
	private final long _money;
	private double _taxRate = 0;
	
	public BuyList(BuyListHolder list, long currentMoney, double taxRate)
	{
		_listId = list.getListId();
		_list = list.getProducts();
		_money = currentMoney;
		_taxRate = taxRate;
	}
	
	@Override
	public void writeImpl(GameClient client, WritableBuffer buffer)
	{
		ServerPackets.EX_BUY_SELL_LIST.writeId(this, buffer);
		buffer.writeInt(0);
		buffer.writeLong(_money); // current money
		buffer.writeInt(_listId);
		buffer.writeShort(_list.size());
		for (Product product : _list)
		{
			if ((product.getCount() > 0) || !product.hasLimitedStock())
			{
				buffer.writeInt(product.getItemId());
				buffer.writeInt(product.getItemId());
				buffer.writeInt(0);
				buffer.writeLong(product.getCount() < 0 ? 0 : product.getCount());
				buffer.writeShort(product.getItem().getType2());
				buffer.writeShort(product.getItem().getType1()); // Custom Type 1
				buffer.writeShort(0); // isEquipped
				buffer.writeInt(product.getItem().getBodyPart().getMask()); // Body Part
				buffer.writeShort(product.getItem().getDefaultEnchantLevel()); // Enchant
				buffer.writeShort(0); // Custom Type
				buffer.writeInt(0); // Augment
				buffer.writeInt(-1); // Mana
				buffer.writeInt(-9999); // Time
				buffer.writeShort(0); // Element Type
				buffer.writeShort(0); // Element Power
				for (byte i = 0; i < 6; i++)
				{
					buffer.writeShort(0);
				}
				
				// Enchant Effects
				buffer.writeShort(0);
				buffer.writeShort(0);
				buffer.writeShort(0);
				if ((product.getItemId() >= 3960) && (product.getItemId() <= 4026))
				{
					buffer.writeLong((long) (product.getPrice() * RatesConfig.RATE_SIEGE_GUARDS_PRICE * (1 + _taxRate)));
				}
				else
				{
					buffer.writeLong((long) (product.getPrice() * (1 + _taxRate)));
				}
			}
		}
	}
}
