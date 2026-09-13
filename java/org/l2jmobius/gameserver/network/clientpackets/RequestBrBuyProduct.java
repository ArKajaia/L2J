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

import java.sql.Connection;
import java.sql.PreparedStatement;

import org.l2jmobius.commons.database.DatabaseFactory;
import org.l2jmobius.gameserver.config.GeneralConfig;
import org.l2jmobius.gameserver.data.holders.PrimeShopProductHolder;
import org.l2jmobius.gameserver.data.xml.ItemData;
import org.l2jmobius.gameserver.data.xml.PrimeShopData;
import org.l2jmobius.gameserver.model.actor.Player;
import org.l2jmobius.gameserver.model.item.ItemTemplate;
import org.l2jmobius.gameserver.model.item.enums.ItemProcessType;
import org.l2jmobius.gameserver.network.PacketLogger;
import org.l2jmobius.gameserver.network.serverpackets.ExBrBuyProduct;
import org.l2jmobius.gameserver.network.serverpackets.ExBrGamePoint;
import org.l2jmobius.gameserver.network.serverpackets.StatusUpdate;

/**
 * @author Mobius
 */
public class RequestBrBuyProduct extends ClientPacket
{
	private int _productId;
	private int _count;
	
	@Override
	protected void readImpl()
	{
		_productId = readInt();
		_count = readInt();
	}
	
	@Override
	protected void runImpl()
	{
		final Player player = getPlayer();
		if (player == null)
		{
			return;
		}
		
		if ((_count > 99) || (_count < 0))
		{
			return;
		}
		
		final PrimeShopProductHolder product = PrimeShopData.getInstance().getProduct(_productId);
		if (product == null)
		{
			player.sendPacket(new ExBrBuyProduct(ExBrBuyProduct.RESULT_WRONG_PRODUCT));
			return;
		}
		
		final long totalPoints = product.getPrice() * _count;
		if (totalPoints < 0)
		{
			player.sendPacket(new ExBrBuyProduct(ExBrBuyProduct.RESULT_WRONG_PRODUCT));
			return;
		}
		
		final long gamePointSize = GeneralConfig.PRIME_SHOP_ITEM_ID == -1 ? player.getGamePoints() : player.getInventory().getInventoryItemCount(GeneralConfig.PRIME_SHOP_ITEM_ID, -1);
		if (totalPoints > gamePointSize)
		{
			player.sendPacket(new ExBrBuyProduct(ExBrBuyProduct.RESULT_NOT_ENOUGH_POINTS));
			return;
		}
		
		final ItemTemplate item = ItemData.getInstance().getTemplate(product.getItemId());
		if (item == null)
		{
			player.sendPacket(new ExBrBuyProduct(ExBrBuyProduct.RESULT_WRONG_PRODUCT));
			return;
		}
		
		final int totalWeight = product.getItemWeight() * product.getItemCount() * _count;
		int totalCount = 0;
		totalCount += item.isStackable() ? 1 : product.getItemCount() * _count;
		if (!player.getInventory().validateCapacity(totalCount) || !player.getInventory().validateWeight(totalWeight))
		{
			player.sendPacket(new ExBrBuyProduct(ExBrBuyProduct.RESULT_INVENTORY_FULL));
			return;
		}
		
		// Pay for Item
		if (GeneralConfig.PRIME_SHOP_ITEM_ID == -1)
		{
			player.setGamePoints(player.getGamePoints() - totalPoints);
		}
		else
		{
			player.getInventory().destroyItemByItemId(ItemProcessType.FEE, GeneralConfig.PRIME_SHOP_ITEM_ID, totalPoints, player, null);
		}
		
		// Buy Item
		player.addItem(ItemProcessType.BUY, product.getItemId(), product.getItemCount() * _count, player, true);
		
		final StatusUpdate su = new StatusUpdate(player.getObjectId());
		su.addAttribute(StatusUpdate.CUR_LOAD, player.getCurrentLoad());
		player.sendPacket(su);
		
		player.sendPacket(new ExBrGamePoint(player));
		player.sendPacket(new ExBrBuyProduct(ExBrBuyProduct.RESULT_OK));
		player.broadcastUserInfo();
		
		// Save transaction info at SQL table prime_shop_transactions
		try (Connection con = DatabaseFactory.getConnection();
			PreparedStatement statement = con.prepareStatement("INSERT INTO prime_shop_transactions (charId, productId, quantity) values (?,?,?)"))
		{
			statement.setLong(1, player.getObjectId());
			statement.setInt(2, product.getProductId());
			statement.setLong(3, _count);
			statement.executeUpdate();
		}
		catch (Exception e)
		{
			PacketLogger.warning("Could not save Item Mall transaction: " + e.getMessage());
		}
	}
}
