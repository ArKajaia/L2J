/*
 * This file is part of the L2J Mobius project.
 * 
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 * 
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * General Public License for more details.
 * 
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>.
 */
package org.l2jmobius.gameserver.network.clientpackets;

import static org.l2jmobius.gameserver.model.itemcontainer.Inventory.ADENA_ID;

import org.l2jmobius.gameserver.config.GeneralConfig;
import org.l2jmobius.gameserver.managers.ItemManager;
import org.l2jmobius.gameserver.managers.MailManager;
import org.l2jmobius.gameserver.managers.PunishmentManager;
import org.l2jmobius.gameserver.model.World;
import org.l2jmobius.gameserver.model.actor.Player;
import org.l2jmobius.gameserver.model.item.enums.ItemLocation;
import org.l2jmobius.gameserver.model.item.enums.ItemProcessType;
import org.l2jmobius.gameserver.model.item.instance.Item;
import org.l2jmobius.gameserver.model.itemcontainer.ItemContainer;
import org.l2jmobius.gameserver.model.zone.ZoneId;
import org.l2jmobius.gameserver.network.SystemMessageId;
import org.l2jmobius.gameserver.network.holders.MailMessage;
import org.l2jmobius.gameserver.network.serverpackets.ExChangePostState;
import org.l2jmobius.gameserver.network.serverpackets.ExShowReceivedPostList;
import org.l2jmobius.gameserver.network.serverpackets.InventoryUpdate;
import org.l2jmobius.gameserver.network.serverpackets.StatusUpdate;
import org.l2jmobius.gameserver.network.serverpackets.SystemMessage;

/**
 * @author Migi, DS
 */
public class RequestPostAttachment extends ClientPacket
{
	private int _msgId;
	
	@Override
	protected void readImpl()
	{
		_msgId = readInt();
	}
	
	@Override
	protected void runImpl()
	{
		if (!GeneralConfig.ALLOW_MAIL || !GeneralConfig.ALLOW_ATTACHMENTS)
		{
			return;
		}
		
		final Player player = getPlayer();
		if (player == null)
		{
			return;
		}
		
		if (!getClient().getFloodProtectors().canPerformTransaction())
		{
			return;
		}
		
		if (!player.getAccessLevel().allowTransaction())
		{
			player.sendMessage("Transactions are disabled for your Access Level");
			return;
		}
		
		if (!player.isInsideZone(ZoneId.PEACE))
		{
			player.sendPacket(SystemMessageId.YOU_CANNOT_RECEIVE_IN_A_NON_PEACE_ZONE_LOCATION);
			return;
		}
		
		if (player.getActiveTradeList() != null)
		{
			player.sendPacket(SystemMessageId.YOU_CANNOT_RECEIVE_DURING_AN_EXCHANGE);
			return;
		}
		
		if (player.isEnchanting())
		{
			player.sendPacket(SystemMessageId.YOU_CANNOT_RECEIVE_DURING_AN_ITEM_ENHANCEMENT_OR_ATTRIBUTE_ENHANCEMENT);
			return;
		}
		
		if (player.isInStoreMode())
		{
			player.sendPacket(SystemMessageId.YOU_CANNOT_RECEIVE_BECAUSE_THE_PRIVATE_SHOP_OR_WORKSHOP_IS_IN_PROGRESS);
			return;
		}
		
		final MailMessage msg = MailManager.getInstance().getMessage(_msgId);
		if (msg == null)
		{
			return;
		}
		
		if (msg.getReceiverId() != player.getObjectId())
		{
			PunishmentManager.handleIllegalPlayerAction(player, player + " tried to get not own attachment!", GeneralConfig.DEFAULT_PUNISH);
			return;
		}
		
		if (!msg.hasAttachments())
		{
			return;
		}
		
		final ItemContainer attachments = msg.getAttachments();
		if (attachments == null)
		{
			return;
		}
		
		int weight = 0;
		int slots = 0;
		for (Item item : attachments.getItems())
		{
			if (item == null)
			{
				continue;
			}
			
			// Calculate needed slots
			if (item.getOwnerId() != msg.getSenderId())
			{
				PunishmentManager.handleIllegalPlayerAction(player, player + " tried to get wrong item (ownerId != senderId) from attachment!", GeneralConfig.DEFAULT_PUNISH);
				return;
			}
			
			if (item.getItemLocation() != ItemLocation.MAIL)
			{
				PunishmentManager.handleIllegalPlayerAction(player, player + " tried to get wrong item (Location != MAIL) from attachment!", GeneralConfig.DEFAULT_PUNISH);
				return;
			}
			
			if (item.getLocationSlot() != msg.getId())
			{
				PunishmentManager.handleIllegalPlayerAction(player, player + " tried to get items from different attachment!", GeneralConfig.DEFAULT_PUNISH);
				return;
			}
			
			weight += item.getCount() * item.getTemplate().getWeight();
			if (!item.isStackable())
			{
				slots += item.getCount();
			}
			else if (player.getInventory().getItemByItemId(item.getId()) == null)
			{
				slots++;
			}
		}
		
		// Item Max Limit Check
		if (!player.getInventory().validateCapacity(slots))
		{
			player.sendPacket(SystemMessageId.YOU_COULD_NOT_RECEIVE_BECAUSE_YOUR_INVENTORY_IS_FULL);
			return;
		}
		
		// Weight limit Check
		if (!player.getInventory().validateWeight(weight))
		{
			player.sendPacket(SystemMessageId.YOU_COULD_NOT_RECEIVE_BECAUSE_YOUR_INVENTORY_IS_FULL);
			return;
		}
		
		final long adena = msg.getReqAdena();
		if ((adena > 0) && !player.reduceAdena(ItemProcessType.FEE, adena, null, true))
		{
			player.sendPacket(SystemMessageId.YOU_CANNOT_RECEIVE_BECAUSE_YOU_DON_T_HAVE_ENOUGH_ADENA);
			return;
		}
		
		// Proceed to the transfer
		final InventoryUpdate playerIU = new InventoryUpdate();
		for (Item item : attachments.getItems())
		{
			if (item == null)
			{
				continue;
			}
			
			if (item.getOwnerId() != msg.getSenderId())
			{
				PunishmentManager.handleIllegalPlayerAction(player, player + " tried to get items with owner != sender !", GeneralConfig.DEFAULT_PUNISH);
				return;
			}
			
			final long count = item.getCount();
			final Item newItem = attachments.transferItem(ItemProcessType.TRANSFER, item.getObjectId(), item.getCount(), player.getInventory(), player, null);
			if (newItem == null)
			{
				return;
			}
			
			if (newItem.isStackable() && (newItem.getCount() > count))
			{
				playerIU.addModifiedItem(newItem);
			}
			else
			{
				playerIU.addNewItem(newItem);
			}
			
			final SystemMessage sm = new SystemMessage(SystemMessageId.YOU_HAVE_ACQUIRED_S2_S1);
			sm.addItemName(item.getId());
			sm.addLong(count);
			player.sendPacket(sm);
		}
		
		// Send updated item list to the player
		player.sendInventoryUpdate(playerIU);
		
		// Send full list to avoid duplicates.
		player.sendItemList(false);
		
		msg.removeAttachments();
		
		// Update current load status on player
		final StatusUpdate su = new StatusUpdate(player);
		su.addAttribute(StatusUpdate.CUR_LOAD, player.getCurrentLoad());
		player.sendPacket(su);
		
		SystemMessage sm;
		final Player sender = World.getInstance().getPlayer(msg.getSenderId());
		if (adena > 0)
		{
			if (sender != null)
			{
				sender.addAdena(ItemProcessType.TRANSFER, adena, player, false);
				sm = new SystemMessage(SystemMessageId.S2_HAS_MADE_A_PAYMENT_OF_S1_ADENA_PER_YOUR_PAYMENT_REQUEST_MAIL);
				sm.addLong(adena);
				sm.addString(player.getName());
				sender.sendPacket(sm);
			}
			else
			{
				final Item paidAdena = ItemManager.createItem(ItemProcessType.FEE, ADENA_ID, adena, player, null);
				paidAdena.setOwnerId(msg.getSenderId());
				paidAdena.setItemLocation(ItemLocation.INVENTORY);
				paidAdena.updateDatabase(true);
				World.getInstance().removeObject(paidAdena);
			}
		}
		else if (sender != null)
		{
			sm = new SystemMessage(SystemMessageId.S1_ACQUIRED_THE_ATTACHED_ITEM_TO_YOUR_MAIL);
			sm.addString(player.getName());
			sender.sendPacket(sm);
		}
		
		player.sendPacket(new ExChangePostState(true, _msgId, MailMessage.READED));
		player.sendPacket(SystemMessageId.MAIL_SUCCESSFULLY_RECEIVED);
		player.sendPacket(new ExShowReceivedPostList(player.getObjectId()));
	}
}
