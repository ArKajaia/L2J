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

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;

import org.l2jmobius.commons.database.DatabaseFactory;
import org.l2jmobius.commons.network.WritableBuffer;
import org.l2jmobius.gameserver.data.holders.PrimeShopProductHolder;
import org.l2jmobius.gameserver.data.xml.PrimeShopData;
import org.l2jmobius.gameserver.model.actor.Player;
import org.l2jmobius.gameserver.network.GameClient;
import org.l2jmobius.gameserver.network.PacketLogger;
import org.l2jmobius.gameserver.network.ServerPackets;

/**
 * @author Mobius
 */
public class ExBrRecentProductList extends ServerPacket
{
	private final List<PrimeShopProductHolder> _itemList = new ArrayList<>();
	
	public ExBrRecentProductList(Player player)
	{
		final int playerObj = player.getObjectId();
		try (Connection con = DatabaseFactory.getConnection();
			PreparedStatement statement = con.prepareStatement("SELECT productId FROM prime_shop_transactions WHERE charId=? ORDER BY transactionTime DESC"))
		{
			statement.setInt(1, playerObj);
			try (ResultSet rset = statement.executeQuery())
			{
				while (rset.next())
				{
					final PrimeShopProductHolder product = PrimeShopData.getInstance().getProduct(rset.getInt("productId"));
					if ((product != null) && !_itemList.contains(product))
					{
						_itemList.add(product);
					}
				}
			}
		}
		catch (Exception e)
		{
			PacketLogger.warning("Could not restore Item Mall transaction: " + e.getMessage());
		}
	}
	
	@Override
	public void writeImpl(GameClient client, WritableBuffer buffer)
	{
		if (_itemList.isEmpty())
		{
			return;
		}
		
		ServerPackets.EX_BR_RECENT_PRODUCT_LIST.writeId(this, buffer);
		buffer.writeInt(_itemList.size());
		for (PrimeShopProductHolder product : _itemList)
		{
			buffer.writeInt(product.getProductId());
			buffer.writeShort(product.getCategory());
			buffer.writeInt(product.getPrice());
			buffer.writeInt(0); // category
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
