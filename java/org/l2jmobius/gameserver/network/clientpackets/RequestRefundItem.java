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

import static org.l2jmobius.gameserver.model.actor.Npc.INTERACTION_DISTANCE;

import org.l2jmobius.gameserver.config.GeneralConfig;
import org.l2jmobius.gameserver.config.PlayerConfig;
import org.l2jmobius.gameserver.config.custom.MerchantZeroSellPriceConfig;
import org.l2jmobius.gameserver.data.xml.BuyListData;
import org.l2jmobius.gameserver.managers.PunishmentManager;
import org.l2jmobius.gameserver.model.WorldObject;
import org.l2jmobius.gameserver.model.actor.Creature;
import org.l2jmobius.gameserver.model.actor.Player;
import org.l2jmobius.gameserver.model.actor.instance.Merchant;
import org.l2jmobius.gameserver.model.buylist.BuyListHolder;
import org.l2jmobius.gameserver.model.item.ItemTemplate;
import org.l2jmobius.gameserver.model.item.enums.ItemProcessType;
import org.l2jmobius.gameserver.model.item.instance.Item;
import org.l2jmobius.gameserver.network.PacketLogger;
import org.l2jmobius.gameserver.network.SystemMessageId;
import org.l2jmobius.gameserver.network.serverpackets.ActionFailed;
import org.l2jmobius.gameserver.network.serverpackets.ExBuySellList;
import org.l2jmobius.gameserver.network.serverpackets.StatusUpdate;

/**
 * RequestRefundItem client packet class.
 */
public class RequestRefundItem extends ClientPacket
{
	private static final int BATCH_LENGTH = 4; // length of the one item
	private static final int CUSTOM_CB_SELL_LIST = 423;
	
	private int _listId;
	private int[] _items = null;
	
	@Override
	protected void readImpl()
	{
		_listId = readInt();
		final int count = readInt();
		if ((count <= 0) || (count > PlayerConfig.MAX_ITEM_IN_PACKET) || ((count * BATCH_LENGTH) != remaining()))
		{
			return;
		}
		
		_items = new int[count];
		for (int i = 0; i < count; i++)
		{
			_items[i] = readInt();
		}
	}
	
	@Override
	protected void runImpl()
	{
		final Player player = getPlayer();
		if (player == null)
		{
			return;
		}
		
		if (!getClient().getFloodProtectors().canPerformTransaction())
		{
			player.sendMessage("You are using refund too fast.");
			return;
		}
		
		if ((_items == null) || !player.hasRefund())
		{
			player.sendPacket(ActionFailed.STATIC_PACKET);
			return;
		}
		
		final WorldObject target = player.getTarget();
		Creature merchant = null;
		if (!player.isGM() && (_listId != CUSTOM_CB_SELL_LIST))
		{
			if (!(target instanceof Merchant) || (!player.isInsideRadius3D(target, INTERACTION_DISTANCE)) || (player.getInstanceId() != target.getInstanceId()))
			{
				player.sendPacket(ActionFailed.STATIC_PACKET);
				return;
			}
			
			merchant = target.asCreature();
		}
		
		if ((merchant == null) && !player.isGM() && (_listId != CUSTOM_CB_SELL_LIST))
		{
			player.sendPacket(ActionFailed.STATIC_PACKET);
			return;
		}
		
		final BuyListHolder buyList = BuyListData.getInstance().getBuyList(_listId);
		if (buyList == null)
		{
			PunishmentManager.handleIllegalPlayerAction(player, "Warning!! Character " + player.getName() + " of account " + player.getAccountName() + " sent a false BuyList list_id " + _listId, GeneralConfig.DEFAULT_PUNISH);
			return;
		}
		
		if ((merchant != null) && !buyList.isNpcAllowed(merchant.getId()))
		{
			player.sendPacket(ActionFailed.STATIC_PACKET);
			return;
		}
		
		long weight = 0;
		long adena = 0;
		long slots = 0;
		
		final Item[] refund = player.getRefund().getItems().toArray(new Item[0]);
		final int[] objectIds = new int[_items.length];
		for (int i = 0; i < _items.length; i++)
		{
			final int idx = _items[i];
			if ((idx < 0) || (idx >= refund.length))
			{
				PunishmentManager.handleIllegalPlayerAction(player, "Warning!! Character " + player.getName() + " of account " + player.getAccountName() + " sent invalid refund index", GeneralConfig.DEFAULT_PUNISH);
				return;
			}
			
			// check for duplicates - indexes
			for (int j = i + 1; j < _items.length; j++)
			{
				if (idx == _items[j])
				{
					PunishmentManager.handleIllegalPlayerAction(player, "Warning!! Character " + player.getName() + " of account " + player.getAccountName() + " sent duplicate refund index", GeneralConfig.DEFAULT_PUNISH);
					return;
				}
			}
			
			final Item item = refund[idx];
			final ItemTemplate template = item.getTemplate();
			objectIds[i] = item.getObjectId();
			
			// second check for duplicates - object ids
			for (int j = 0; j < i; j++)
			{
				if (objectIds[i] == objectIds[j])
				{
					PunishmentManager.handleIllegalPlayerAction(player, "Warning!! Character " + player.getName() + " of account " + player.getAccountName() + " has duplicate items in refund list", GeneralConfig.DEFAULT_PUNISH);
					return;
				}
			}
			
			final long count = item.getCount();
			weight += count * template.getWeight();
			adena += count * (template.getReferencePrice() / 2);
			if (!template.isStackable())
			{
				slots += count;
			}
			else if (player.getInventory().getItemByItemId(template.getId()) == null)
			{
				slots++;
			}
		}
		
		if ((weight > Integer.MAX_VALUE) || (weight < 0) || !player.getInventory().validateWeight((int) weight))
		{
			player.sendPacket(SystemMessageId.YOU_HAVE_EXCEEDED_THE_WEIGHT_LIMIT);
			player.sendPacket(ActionFailed.STATIC_PACKET);
			return;
		}
		
		if ((slots > Integer.MAX_VALUE) || (slots < 0) || !player.getInventory().validateCapacity((int) slots))
		{
			player.sendPacket(SystemMessageId.YOUR_INVENTORY_IS_FULL);
			player.sendPacket(ActionFailed.STATIC_PACKET);
			return;
		}
		
		if (!MerchantZeroSellPriceConfig.MERCHANT_ZERO_SELL_PRICE && ((adena < 0) || !player.reduceAdena(ItemProcessType.FEE, adena, player.getLastFolkNPC(), false)))
		{
			player.sendPacket(SystemMessageId.YOU_DO_NOT_HAVE_ENOUGH_ADENA);
			player.sendPacket(ActionFailed.STATIC_PACKET);
			return;
		}
		
		for (int i = 0; i < _items.length; i++)
		{
			final Item item = player.getRefund().transferItem(ItemProcessType.TRANSFER, objectIds[i], Long.MAX_VALUE, player.getInventory(), player, player.getLastFolkNPC());
			if (item == null)
			{
				PacketLogger.warning("Error refunding object for char " + player.getName() + " (newitem == null)");
			}
		}
		
		// Update current load status on player
		final StatusUpdate su = new StatusUpdate(player);
		su.addAttribute(StatusUpdate.CUR_LOAD, player.getCurrentLoad());
		player.sendPacket(su);
		player.sendPacket(new ExBuySellList(player, true));
	}
}
