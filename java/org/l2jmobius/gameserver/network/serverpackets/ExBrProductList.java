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
import org.l2jmobius.gameserver.data.holders.PrimeShopProductHolder;
import org.l2jmobius.gameserver.data.xml.PrimeShopData;
import org.l2jmobius.gameserver.network.GameClient;
import org.l2jmobius.gameserver.network.ServerPackets;

/**
 * @author Mobius
 */
public class ExBrProductList extends ServerPacket
{
	private final Collection<PrimeShopProductHolder> _itemList = PrimeShopData.getInstance().getAllItems();
	
	@Override
	public void writeImpl(GameClient client, WritableBuffer buffer)
	{
		ServerPackets.EX_BR_PRODUCT_LIST.writeId(this, buffer);
		buffer.writeInt(_itemList.size());
		for (PrimeShopProductHolder product : _itemList)
		{
			final int category = product.getCategory();
			buffer.writeInt(product.getProductId()); // product id
			buffer.writeShort(category); // category id
			buffer.writeInt(product.getPrice()); // points
			switch (category)
			{
				case 6:
				{
					buffer.writeInt(1); // event
					break;
				}
				case 7:
				{
					buffer.writeInt(2); // best
					break;
				}
				case 8:
				{
					buffer.writeInt(3); // event & best
					break;
				}
				default:
				{
					buffer.writeInt(0); // normal
					break;
				}
			}
			
			buffer.writeInt(0); // start sale
			buffer.writeInt(0); // end sale
			buffer.writeByte(0); // day week
			buffer.writeByte(0); // start hour
			buffer.writeByte(0); // start min
			buffer.writeByte(0); // end hour
			buffer.writeByte(0); // end min
			buffer.writeInt(0); // current stock
			buffer.writeInt(0); // max stock
		}
	}
}
