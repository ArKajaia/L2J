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

import org.l2jmobius.commons.util.Rnd;
import org.l2jmobius.gameserver.config.GeneralConfig;
import org.l2jmobius.gameserver.config.PlayerConfig;
import org.l2jmobius.gameserver.data.holders.ElementalItemHolder;
import org.l2jmobius.gameserver.managers.PunishmentManager;
import org.l2jmobius.gameserver.model.actor.Player;
import org.l2jmobius.gameserver.model.item.enums.ItemProcessType;
import org.l2jmobius.gameserver.model.item.holders.Elementals;
import org.l2jmobius.gameserver.model.item.instance.Item;
import org.l2jmobius.gameserver.network.SystemMessageId;
import org.l2jmobius.gameserver.network.serverpackets.ExAttributeEnchantResult;
import org.l2jmobius.gameserver.network.serverpackets.InventoryUpdate;
import org.l2jmobius.gameserver.network.serverpackets.SystemMessage;

public class RequestExEnchantItemAttribute extends ClientPacket
{
	private int _objectId;
	
	@Override
	protected void readImpl()
	{
		_objectId = readInt();
	}
	
	@Override
	protected void runImpl()
	{
		final Player player = getPlayer();
		if (player == null)
		{
			return;
		}
		
		if (_objectId == 0xFFFFFFFF)
		{
			// Player canceled enchant
			player.setActiveEnchantAttrItemId(Player.ID_NONE);
			player.sendPacket(SystemMessageId.ATTRIBUTE_ITEM_USAGE_HAS_BEEN_CANCELLED);
			return;
		}
		
		if (!player.isOnline())
		{
			player.setActiveEnchantAttrItemId(Player.ID_NONE);
			return;
		}
		
		if (player.isInStoreMode())
		{
			player.sendPacket(SystemMessageId.YOU_CANNOT_ADD_ELEMENTAL_POWER_WHILE_OPERATING_A_PRIVATE_STORE_OR_PRIVATE_WORKSHOP);
			player.setActiveEnchantAttrItemId(Player.ID_NONE);
			return;
		}
		
		// Restrict enchant during a trade (bug if enchant fails)
		if (player.getActiveRequester() != null)
		{
			// Cancel trade
			player.cancelActiveTrade();
			player.setActiveEnchantAttrItemId(Player.ID_NONE);
			player.sendMessage("You cannot add elemental power while trading.");
			return;
		}
		
		final Item item = player.getInventory().getItemByObjectId(_objectId);
		final Item stone = player.getInventory().getItemByObjectId(player.getActiveEnchantAttrItemId());
		if ((item == null) || (stone == null))
		{
			player.setActiveEnchantAttrItemId(Player.ID_NONE);
			player.sendPacket(SystemMessageId.ATTRIBUTE_ITEM_USAGE_HAS_BEEN_CANCELLED);
			return;
		}
		
		if (!item.isElementable())
		{
			player.sendPacket(SystemMessageId.ELEMENTAL_POWER_ENHANCER_USAGE_REQUIREMENT_IS_NOT_SUFFICIENT);
			player.setActiveEnchantAttrItemId(Player.ID_NONE);
			return;
		}
		
		switch (item.getItemLocation())
		{
			case INVENTORY:
			case PAPERDOLL:
			{
				if (item.getOwnerId() != player.getObjectId())
				{
					player.setActiveEnchantAttrItemId(Player.ID_NONE);
					return;
				}
				break;
			}
			default:
			{
				player.setActiveEnchantAttrItemId(Player.ID_NONE);
				PunishmentManager.handleIllegalPlayerAction(player, player + " tried to use enchant Exploit!", GeneralConfig.DEFAULT_PUNISH);
				return;
			}
		}
		
		final int stoneId = stone.getId();
		byte elementToAdd = Elementals.getItemElement(stoneId);
		
		// Armors have the opposite element
		if (item.isArmor())
		{
			elementToAdd = Elementals.getOppositeElement(elementToAdd);
		}
		
		final byte opositeElement = Elementals.getOppositeElement(elementToAdd);
		final Elementals oldElement = item.getElemental(elementToAdd);
		final int elementValue = oldElement == null ? 0 : oldElement.getValue();
		final int limit = getLimit(item, stoneId);
		int powerToAdd = getPowerToAdd(stoneId, elementValue, item);
		if ((item.isWeapon() && (oldElement != null) && (oldElement.getElement() != elementToAdd) && (oldElement.getElement() != -2)) || (item.isArmor() && (item.getElemental(elementToAdd) == null) && (item.getElementals() != null) && (item.getElementals().length >= 3)))
		{
			player.sendPacket(SystemMessageId.ANOTHER_ELEMENTAL_POWER_HAS_ALREADY_BEEN_ADDED_THIS_ELEMENTAL_POWER_CANNOT_BE_ADDED);
			player.setActiveEnchantAttrItemId(Player.ID_NONE);
			return;
		}
		
		if (item.isArmor() && (item.getElementals() != null))
		{
			// cannot add opposite element
			for (Elementals elm : item.getElementals())
			{
				if (elm.getElement() == opositeElement)
				{
					player.setActiveEnchantAttrItemId(Player.ID_NONE);
					PunishmentManager.handleIllegalPlayerAction(player, player + " tried to add oposite attribute to item!", GeneralConfig.DEFAULT_PUNISH);
					return;
				}
			}
		}
		
		int newPower = elementValue + powerToAdd;
		if (newPower > limit)
		{
			newPower = limit;
			powerToAdd = limit - elementValue;
		}
		
		if (powerToAdd <= 0)
		{
			player.sendPacket(SystemMessageId.ATTRIBUTE_ITEM_USAGE_HAS_BEEN_CANCELLED);
			player.setActiveEnchantAttrItemId(Player.ID_NONE);
			return;
		}
		
		if (!player.destroyItem(ItemProcessType.FEE, stone, 1, player, true))
		{
			player.sendPacket(SystemMessageId.INCORRECT_ITEM_COUNT_2);
			PunishmentManager.handleIllegalPlayerAction(player, player + " tried to attribute enchant with a stone he doesn't have", GeneralConfig.DEFAULT_PUNISH);
			player.setActiveEnchantAttrItemId(Player.ID_NONE);
			return;
		}
		
		boolean success = false;
		switch (Elementals.getItemElemental(stoneId).getType())
		{
			case STONE:
			case ROUGH_ORE:
			{
				success = Rnd.get(100) < PlayerConfig.ENCHANT_CHANCE_ELEMENT_STONE;
				break;
			}
			case CRYSTAL:
			{
				success = Rnd.get(100) < PlayerConfig.ENCHANT_CHANCE_ELEMENT_CRYSTAL;
				break;
			}
			case JEWEL:
			{
				success = Rnd.get(100) < PlayerConfig.ENCHANT_CHANCE_ELEMENT_JEWEL;
				break;
			}
			case ENERGY:
			{
				success = Rnd.get(100) < PlayerConfig.ENCHANT_CHANCE_ELEMENT_ENERGY;
				break;
			}
		}
		
		if (success)
		{
			final byte realElement = item.isArmor() ? opositeElement : elementToAdd;
			SystemMessage sm;
			if (item.getEnchantLevel() == 0)
			{
				if (item.isArmor())
				{
					sm = new SystemMessage(SystemMessageId.THE_S2_S_ATTRIBUTE_WAS_SUCCESSFULLY_BESTOWED_ON_S1_AND_RESISTANCE_TO_S3_WAS_INCREASED);
				}
				else
				{
					sm = new SystemMessage(SystemMessageId.S2_ELEMENTAL_POWER_HAS_BEEN_ADDED_SUCCESSFULLY_TO_S1);
				}
				
				sm.addItemName(item);
				sm.addElemental(realElement);
				if (item.isArmor())
				{
					sm.addElemental(Elementals.getOppositeElement(realElement));
				}
			}
			else
			{
				if (item.isArmor())
				{
					sm = new SystemMessage(SystemMessageId.THE_S3_S_ATTRIBUTE_WAS_SUCCESSFULLY_BESTOWED_ON_S1_S2_AND_RESISTANCE_TO_S4_WAS_INCREASED);
				}
				else
				{
					sm = new SystemMessage(SystemMessageId.S3_ELEMENTAL_POWER_HAS_BEEN_ADDED_SUCCESSFULLY_TO_S1_S2);
				}
				
				sm.addInt(item.getEnchantLevel());
				sm.addItemName(item);
				sm.addElemental(realElement);
				if (item.isArmor())
				{
					sm.addElemental(Elementals.getOppositeElement(realElement));
				}
			}
			
			player.sendPacket(sm);
			item.setElementAttr(elementToAdd, newPower);
			if (item.isEquipped())
			{
				item.updateElementAttrBonus(player);
			}
			
			// send packets
			final InventoryUpdate iu = new InventoryUpdate();
			iu.addModifiedItem(item);
			player.sendInventoryUpdate(iu);
		}
		else
		{
			player.sendPacket(SystemMessageId.YOU_HAVE_FAILED_TO_ADD_ELEMENTAL_POWER);
		}
		
		player.sendPacket(new ExAttributeEnchantResult(powerToAdd));
		player.updateUserInfo();
		player.setActiveEnchantAttrItemId(Player.ID_NONE);
	}
	
	public int getLimit(Item item, int sotneId)
	{
		final ElementalItemHolder elementItem = Elementals.getItemElemental(sotneId);
		if (elementItem == null)
		{
			return 0;
		}
		
		if (item.isWeapon())
		{
			return Elementals.WEAPON_VALUES[elementItem.getType().getMaxLevel()];
		}
		
		return Elementals.ARMOR_VALUES[elementItem.getType().getMaxLevel()];
	}
	
	public int getPowerToAdd(int stoneId, int oldValue, Item item)
	{
		if (Elementals.getItemElement(stoneId) != Elementals.NONE)
		{
			if (item.isWeapon())
			{
				if (oldValue == 0)
				{
					return Elementals.FIRST_WEAPON_BONUS;
				}
				
				return Elementals.NEXT_WEAPON_BONUS;
			}
			else if (item.isArmor())
			{
				return Elementals.ARMOR_BONUS;
			}
		}
		
		return 0;
	}
}
