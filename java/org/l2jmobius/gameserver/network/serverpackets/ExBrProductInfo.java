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

import org.l2jmobius.commons.network.WritableBuffer;
import org.l2jmobius.gameserver.data.holders.PrimeShopProductHolder;
import org.l2jmobius.gameserver.data.xml.PrimeShopData;
import org.l2jmobius.gameserver.network.GameClient;
import org.l2jmobius.gameserver.network.ServerPackets;

/**
 * @author Mobius
 */
public class ExBrProductInfo extends ServerPacket
{
	private final PrimeShopProductHolder _product;
	
	public ExBrProductInfo(int id)
	{
		_product = PrimeShopData.getInstance().getProduct(id);
	}
	
	@Override
	public void writeImpl(GameClient client, WritableBuffer buffer)
	{
		if (_product == null)
		{
			return;
		}
		
		ServerPackets.EX_BR_PRODUCT_INFO.writeId(this, buffer);
		buffer.writeInt(_product.getProductId()); // product id
		buffer.writeInt(_product.getPrice()); // points
		buffer.writeInt(1); // components size
		buffer.writeInt(_product.getItemId()); // item id
		buffer.writeInt(_product.getItemCount()); // quality
		buffer.writeInt(_product.getItemWeight()); // weight
		buffer.writeInt(_product.isTradable()); // 0 - do not drop/trade
	}
}
