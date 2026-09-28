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

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

import org.l2jmobius.gameserver.config.PlayerConfig;
import org.l2jmobius.gameserver.data.AugmentationData;
import org.l2jmobius.gameserver.data.xml.ItemData;
import org.l2jmobius.gameserver.model.actor.Player;
import org.l2jmobius.gameserver.model.item.Armor;
import org.l2jmobius.gameserver.model.item.ItemTemplate;
import org.l2jmobius.gameserver.model.item.Weapon;
import org.l2jmobius.gameserver.model.item.enums.BodyPart;
import org.l2jmobius.gameserver.model.item.enums.ItemLocation;
import org.l2jmobius.gameserver.model.item.enums.ItemProcessType;
import org.l2jmobius.gameserver.model.item.instance.Item;
import org.l2jmobius.gameserver.model.item.type.CrystalType;
import org.l2jmobius.gameserver.model.options.Augmentation;
import org.l2jmobius.gameserver.network.SystemMessageId;
import org.l2jmobius.gameserver.network.serverpackets.InventoryUpdate;
import org.l2jmobius.gameserver.network.serverpackets.StatusUpdate;

public abstract class AbstractRefinePacket extends ClientPacket
{
	public static final int GRADE_NONE = 0;
	public static final int GRADE_MID = 1;
	public static final int GRADE_HIGH = 2;
	public static final int GRADE_TOP = 3;
	public static final int GRADE_ACC = 4; // Accessory LS
	
	protected static final int GEMSTONE_D = 2130;
	protected static final int GEMSTONE_C = 2131;
	protected static final int GEMSTONE_B = 2132;
	
	private static final Map<Integer, LifeStone> _lifeStones = new HashMap<>();
	
	protected static class LifeStone
	{
		// lifestone level to player level table
		private static final int[] LEVELS =
		{
			46,
			49,
			52,
			55,
			58,
			61,
			64,
			67,
			70,
			76,
			80,
			82,
			84,
			85
		};
		private final int _grade;
		private final int _level;
		
		public LifeStone(int grade, int level)
		{
			_grade = grade;
			_level = level;
		}
		
		public int getLevel()
		{
			return _level;
		}
		
		public int getGrade()
		{
			return _grade;
		}
		
		public int getPlayerLevel()
		{
			return LEVELS[_level];
		}
	}
	
	static
	{
		// itemId, (LS grade, LS level)
		_lifeStones.put(8723, new LifeStone(GRADE_NONE, 0));
		_lifeStones.put(8724, new LifeStone(GRADE_NONE, 1));
		_lifeStones.put(8725, new LifeStone(GRADE_NONE, 2));
		_lifeStones.put(8726, new LifeStone(GRADE_NONE, 3));
		_lifeStones.put(8727, new LifeStone(GRADE_NONE, 4));
		_lifeStones.put(8728, new LifeStone(GRADE_NONE, 5));
		_lifeStones.put(8729, new LifeStone(GRADE_NONE, 6));
		_lifeStones.put(8730, new LifeStone(GRADE_NONE, 7));
		_lifeStones.put(8731, new LifeStone(GRADE_NONE, 8));
		_lifeStones.put(8732, new LifeStone(GRADE_NONE, 9));
		
		_lifeStones.put(8733, new LifeStone(GRADE_MID, 0));
		_lifeStones.put(8734, new LifeStone(GRADE_MID, 1));
		_lifeStones.put(8735, new LifeStone(GRADE_MID, 2));
		_lifeStones.put(8736, new LifeStone(GRADE_MID, 3));
		_lifeStones.put(8737, new LifeStone(GRADE_MID, 4));
		_lifeStones.put(8738, new LifeStone(GRADE_MID, 5));
		_lifeStones.put(8739, new LifeStone(GRADE_MID, 6));
		_lifeStones.put(8740, new LifeStone(GRADE_MID, 7));
		_lifeStones.put(8741, new LifeStone(GRADE_MID, 8));
		_lifeStones.put(8742, new LifeStone(GRADE_MID, 9));
		
		_lifeStones.put(8743, new LifeStone(GRADE_HIGH, 0));
		_lifeStones.put(8744, new LifeStone(GRADE_HIGH, 1));
		_lifeStones.put(8745, new LifeStone(GRADE_HIGH, 2));
		_lifeStones.put(8746, new LifeStone(GRADE_HIGH, 3));
		_lifeStones.put(8747, new LifeStone(GRADE_HIGH, 4));
		_lifeStones.put(8748, new LifeStone(GRADE_HIGH, 5));
		_lifeStones.put(8749, new LifeStone(GRADE_HIGH, 6));
		_lifeStones.put(8750, new LifeStone(GRADE_HIGH, 7));
		_lifeStones.put(8751, new LifeStone(GRADE_HIGH, 8));
		_lifeStones.put(8752, new LifeStone(GRADE_HIGH, 9));
		
		_lifeStones.put(8753, new LifeStone(GRADE_TOP, 0));
		_lifeStones.put(8754, new LifeStone(GRADE_TOP, 1));
		_lifeStones.put(8755, new LifeStone(GRADE_TOP, 2));
		_lifeStones.put(8756, new LifeStone(GRADE_TOP, 3));
		_lifeStones.put(8757, new LifeStone(GRADE_TOP, 4));
		_lifeStones.put(8758, new LifeStone(GRADE_TOP, 5));
		_lifeStones.put(8759, new LifeStone(GRADE_TOP, 6));
		_lifeStones.put(8760, new LifeStone(GRADE_TOP, 7));
		_lifeStones.put(8761, new LifeStone(GRADE_TOP, 8));
		_lifeStones.put(8762, new LifeStone(GRADE_TOP, 9));
		
		_lifeStones.put(9573, new LifeStone(GRADE_NONE, 10));
		_lifeStones.put(9574, new LifeStone(GRADE_MID, 10));
		_lifeStones.put(9575, new LifeStone(GRADE_HIGH, 10));
		_lifeStones.put(9576, new LifeStone(GRADE_TOP, 10));
		
		_lifeStones.put(10483, new LifeStone(GRADE_NONE, 11));
		_lifeStones.put(10484, new LifeStone(GRADE_MID, 11));
		_lifeStones.put(10485, new LifeStone(GRADE_HIGH, 11));
		_lifeStones.put(10486, new LifeStone(GRADE_TOP, 11));
		
		_lifeStones.put(12754, new LifeStone(GRADE_ACC, 0));
		_lifeStones.put(12755, new LifeStone(GRADE_ACC, 1));
		_lifeStones.put(12756, new LifeStone(GRADE_ACC, 2));
		_lifeStones.put(12757, new LifeStone(GRADE_ACC, 3));
		_lifeStones.put(12758, new LifeStone(GRADE_ACC, 4));
		_lifeStones.put(12759, new LifeStone(GRADE_ACC, 5));
		_lifeStones.put(12760, new LifeStone(GRADE_ACC, 6));
		_lifeStones.put(12761, new LifeStone(GRADE_ACC, 7));
		_lifeStones.put(12762, new LifeStone(GRADE_ACC, 8));
		_lifeStones.put(12763, new LifeStone(GRADE_ACC, 9));
		
		_lifeStones.put(12821, new LifeStone(GRADE_ACC, 10));
		_lifeStones.put(12822, new LifeStone(GRADE_ACC, 11));
		
		_lifeStones.put(12840, new LifeStone(GRADE_ACC, 0));
		_lifeStones.put(12841, new LifeStone(GRADE_ACC, 1));
		_lifeStones.put(12842, new LifeStone(GRADE_ACC, 2));
		_lifeStones.put(12843, new LifeStone(GRADE_ACC, 3));
		_lifeStones.put(12844, new LifeStone(GRADE_ACC, 4));
		_lifeStones.put(12845, new LifeStone(GRADE_ACC, 5));
		_lifeStones.put(12846, new LifeStone(GRADE_ACC, 6));
		_lifeStones.put(12847, new LifeStone(GRADE_ACC, 7));
		_lifeStones.put(12848, new LifeStone(GRADE_ACC, 8));
		_lifeStones.put(12849, new LifeStone(GRADE_ACC, 9));
		_lifeStones.put(12850, new LifeStone(GRADE_ACC, 10));
		_lifeStones.put(12851, new LifeStone(GRADE_ACC, 11));
		
		_lifeStones.put(14008, new LifeStone(GRADE_ACC, 12));
		
		_lifeStones.put(14166, new LifeStone(GRADE_NONE, 12));
		_lifeStones.put(14167, new LifeStone(GRADE_MID, 12));
		_lifeStones.put(14168, new LifeStone(GRADE_HIGH, 12));
		_lifeStones.put(14169, new LifeStone(GRADE_TOP, 12));
		
		_lifeStones.put(16160, new LifeStone(GRADE_NONE, 13));
		_lifeStones.put(16161, new LifeStone(GRADE_MID, 13));
		_lifeStones.put(16162, new LifeStone(GRADE_HIGH, 13));
		_lifeStones.put(16163, new LifeStone(GRADE_TOP, 13));
		_lifeStones.put(16177, new LifeStone(GRADE_ACC, 13));
		
		_lifeStones.put(16164, new LifeStone(GRADE_NONE, 13));
		_lifeStones.put(16165, new LifeStone(GRADE_MID, 13));
		_lifeStones.put(16166, new LifeStone(GRADE_HIGH, 13));
		_lifeStones.put(16167, new LifeStone(GRADE_TOP, 13));
		_lifeStones.put(16178, new LifeStone(GRADE_ACC, 13));
	}
	
	/**
	 * Checks the player's own conditions for augmentation (store, trade, dead, sitting...), sending the reason on failure.
	 * @param player the player
	 * @return {@code true} if the player can augment right now
	 */
	public static boolean canAugment(Player player)
	{
		return isValid(player);
	}
	
	/**
	 * Checks whether the player has at least one weapon or accessory the given life stone can augment.
	 * @param player the player using the life stone
	 * @param lifeStone the life stone
	 * @return {@code true} if some item in the inventory or paperdoll can be augmented with this life stone
	 */
	public static boolean hasAugmentableItem(Player player, Item lifeStone)
	{
		for (Item item : player.getInventory().getItems())
		{
			if (isAugmentableWith(player, item, lifeStone))
			{
				return true;
			}
		}
		
		return false;
	}
	
	/**
	 * @param player the player
	 * @param item the weapon or accessory
	 * @param lifeStone the life stone
	 * @return {@code true} if the item can be augmented with this life stone, already augmented items included (costs not checked)
	 */
	public static boolean isAugmentableWith(Player player, Item item, Item lifeStone)
	{
		return isValid(player, item, lifeStone, true);
	}
	
	/**
	 * @param item the augmented item
	 * @return the adena it costs to remove the augmentation of this item, 0 if its grade can't be augmented
	 */
	public static long getAugmentRemovalPrice(Item item)
	{
		switch (item.getTemplate().getCrystalType())
		{
			case C:
			{
				if (item.getCrystalCount() < 1720)
				{
					return 95000;
				}
				else if (item.getCrystalCount() < 2452)
				{
					return 150000;
				}
				return 210000;
			}
			case B:
			{
				if (item.getCrystalCount() < 1746)
				{
					return 240000;
				}
				return 270000;
			}
			case A:
			{
				if (item.getCrystalCount() < 2160)
				{
					return 330000;
				}
				else if (item.getCrystalCount() < 2824)
				{
					return 390000;
				}
				return 420000;
			}
			case S:
			{
				return 480000;
			}
			case S80:
			case S84:
			{
				// TODO: S84 TOP price 3.2M
				return 920000;
			}
			default:
			{
				// any other item type is not augmentable
				return 0;
			}
		}
	}
	
	/**
	 * @param targetItem the item to augment
	 * @return the Gemstone item id the augmentation of this item costs
	 */
	public static int getRequiredGemStoneId(Item targetItem)
	{
		return getGemStoneId(targetItem.getTemplate().getCrystalType());
	}
	
	/**
	 * @param targetItem the item to augment
	 * @param lifeStone the life stone used
	 * @return how many Gemstones the augmentation of this item with this life stone costs, 0 if the stone is not a life stone
	 */
	public static int getRequiredGemStoneCount(Item targetItem, Item lifeStone)
	{
		final LifeStone ls = _lifeStones.get(lifeStone.getId());
		return ls == null ? 0 : getGemStoneCount(targetItem.getTemplate().getCrystalType(), ls.getGrade());
	}
	
	/**
	 * Augments an item with a life stone, taking the life stone and the required Gemstones straight from the inventory (no refinery window needed).<br>
	 * An already augmented item has its augmentation removed first, for the usual removal adena fee, and gets the new one in the same action.<br>
	 * An equipped item is unequipped for the augmentation and equipped back afterwards so the new augmentation applies right away.
	 * @param player the player
	 * @param targetItem the weapon or accessory to augment
	 * @param lifeStone the life stone to use
	 * @return {@code null} if the item was augmented, otherwise the reason it wasn't (also sent to the player)
	 */
	public static String augmentFromInventory(Player player, Item targetItem, Item lifeStone)
	{
		if (!isValid(player, targetItem, lifeStone, true))
		{
			player.sendPacket(SystemMessageId.AUGMENTATION_FAILED_DUE_TO_INAPPROPRIATE_CONDITIONS);
			return "Augmentation failed due to inappropriate conditions.";
		}
		
		final LifeStone ls = _lifeStones.get(lifeStone.getId());
		final int gemStoneId = getRequiredGemStoneId(targetItem);
		final int gemStoneCount = getGemStoneCount(targetItem.getTemplate().getCrystalType(), ls.getGrade());
		final Item gemStones = player.getInventory().getItemByItemId(gemStoneId);
		final long ownedGemStones = gemStones == null ? 0 : gemStones.getCount();
		final boolean replace = targetItem.isAugmented();
		final long removalPrice = replace ? getAugmentRemovalPrice(targetItem) : 0;
		if ((gemStoneCount <= 0) || (replace && (removalPrice <= 0)))
		{
			player.sendPacket(SystemMessageId.AUGMENTATION_FAILED_DUE_TO_INAPPROPRIATE_CONDITIONS);
			return "Augmentation failed due to inappropriate conditions.";
		}
		
		// Check every cost before taking anything.
		final StringBuilder missing = new StringBuilder();
		if (ownedGemStones < gemStoneCount)
		{
			final ItemTemplate gemStoneTemplate = ItemData.getInstance().getTemplate(gemStoneId);
			missing.append(gemStoneTemplate == null ? "Gemstones" : gemStoneTemplate.getName()).append(" (required: ").append(gemStoneCount).append(", you have: ").append(ownedGemStones).append(")");
		}
		if (player.getAdena() < removalPrice)
		{
			missing.append(missing.length() > 0 ? " and " : "").append("Adena (required: ").append(removalPrice).append(", you have: ").append(player.getAdena()).append(")");
		}
		if (missing.length() > 0)
		{
			final String reason = "You do not have enough " + missing + ".";
			player.sendMessage(reason);
			return reason;
		}
		
		// Unequip the item.
		final boolean wasEquipped = targetItem.isEquipped();
		if (wasEquipped)
		{
			final InventoryUpdate iu = new InventoryUpdate();
			for (Item itm : player.getInventory().unEquipItemInSlotAndRecord(targetItem.getLocationSlot()))
			{
				iu.addModifiedItem(itm);
			}
			
			player.sendPacket(iu); // Sent inventory update for unequip instantly.
			player.broadcastUserInfo();
		}
		
		// Consume the removal fee, the life stone and the gemstones.
		if (((removalPrice > 0) && !player.reduceAdena(ItemProcessType.FEE, removalPrice, null, true)) || !player.destroyItem(ItemProcessType.FEE, lifeStone, 1, null, false) || !player.destroyItem(ItemProcessType.FEE, gemStones, gemStoneCount, null, false))
		{
			return "Augmentation failed due to inappropriate conditions.";
		}
		
		// Remove the old augmentation.
		if (replace)
		{
			targetItem.removeAugmentation();
		}
		
		final Augmentation aug = AugmentationData.getInstance().generateRandomAugmentation(ls.getLevel(), ls.getGrade(), targetItem.getTemplate().getBodyPart(), lifeStone.getId(), targetItem);
		targetItem.setAugmentation(aug);
		
		final InventoryUpdate iu = new InventoryUpdate();
		iu.addModifiedItem(targetItem);
		player.sendPacket(iu);
		
		final StatusUpdate su = new StatusUpdate(player);
		su.addAttribute(StatusUpdate.CUR_LOAD, player.getCurrentLoad());
		player.sendPacket(su);
		
		if (wasEquipped)
		{
			player.useEquippableItem(targetItem, false);
		}
		
		player.sendPacket(SystemMessageId.THE_ITEM_WAS_SUCCESSFULLY_AUGMENTED);
		return null;
	}
	
	protected static LifeStone getLifeStone(int itemId)
	{
		return _lifeStones.get(itemId);
	}
	
	/**
	 * Checks player, source item, lifestone and gemstone validity for augmentation process
	 * @param player
	 * @param item
	 * @param refinerItem
	 * @param gemStones
	 * @return
	 */
	protected static boolean isValid(Player player, Item item, Item refinerItem, Item gemStones)
	{
		if (!isValid(player, item, refinerItem))
		{
			return false;
		}
		
		// GemStones must belong to owner
		if (gemStones.getOwnerId() != player.getObjectId())
		{
			return false;
		}
		
		// .. and located in inventory
		if (gemStones.getItemLocation() != ItemLocation.INVENTORY)
		{
			return false;
		}
		
		final CrystalType grade = item.getTemplate().getCrystalType();
		final LifeStone ls = _lifeStones.get(refinerItem.getId());
		
		// Check for item id
		if (getGemStoneId(grade) != gemStones.getId())
		{
			return false;
		}
		
		// Count must be greater or equal of required number
		if (getGemStoneCount(grade, ls.getGrade()) > gemStones.getCount())
		{
			return false;
		}
		
		return true;
	}
	
	/**
	 * Checks player, source item and lifestone validity for augmentation process
	 * @param player
	 * @param item
	 * @param refinerItem
	 * @return
	 */
	protected static boolean isValid(Player player, Item item, Item refinerItem)
	{
		return isValid(player, item, refinerItem, false);
	}
	
	/**
	 * Checks player, source item and lifestone validity for augmentation process
	 * @param player
	 * @param item
	 * @param refinerItem
	 * @param allowAugmented {@code true} to accept an already augmented item (its augmentation gets replaced)
	 * @return
	 */
	protected static boolean isValid(Player player, Item item, Item refinerItem, boolean allowAugmented)
	{
		if (!isValid(player, item, allowAugmented))
		{
			return false;
		}
		
		// Item must belong to owner
		if (refinerItem.getOwnerId() != player.getObjectId())
		{
			return false;
		}
		
		// Lifestone must be located in inventory
		if (refinerItem.getItemLocation() != ItemLocation.INVENTORY)
		{
			return false;
		}
		
		final LifeStone ls = _lifeStones.get(refinerItem.getId());
		if (ls == null)
		{
			return false;
		}
		
		// weapons can't be augmented with accessory ls
		if ((item.getTemplate() instanceof Weapon) && (ls.getGrade() == GRADE_ACC))
		{
			return false;
		}
		
		// and accessory can't be augmented with weapon ls
		if ((item.getTemplate() instanceof Armor) && (ls.getGrade() != GRADE_ACC))
		{
			return false;
		}
		
		// check for level of the lifestone
		if (player.getLevel() < ls.getPlayerLevel())
		{
			return false;
		}
		
		return true;
	}
	
	/**
	 * Check both player and source item conditions for augmentation process
	 * @param player
	 * @param item
	 * @return
	 */
	protected static boolean isValid(Player player, Item item)
	{
		return isValid(player, item, false);
	}
	
	/**
	 * Check both player and source item conditions for augmentation process
	 * @param player
	 * @param item
	 * @param allowAugmented {@code true} to accept an already augmented item (its augmentation gets replaced)
	 * @return
	 */
	protected static boolean isValid(Player player, Item item, boolean allowAugmented)
	{
		if (!isValid(player))
		{
			return false;
		}
		
		// Item must belong to owner
		if (item.getOwnerId() != player.getObjectId())
		{
			return false;
		}
		
		if (item.isAugmented() && !allowAugmented)
		{
			return false;
		}
		
		if (item.isHeroItem())
		{
			return false;
		}
		
		if (item.isShadowItem())
		{
			return false;
		}
		
		if (item.isCommonItem())
		{
			return false;
		}
		
		if (item.isEtcItem())
		{
			return false;
		}
		
		if (item.isTimeLimitedItem())
		{
			return false;
		}
		
		if (item.isPvp() && !PlayerConfig.ALT_ALLOW_AUGMENT_PVP_ITEMS)
		{
			return false;
		}
		
		if (item.getTemplate().getCrystalType().isLesser(CrystalType.C))
		{
			return false;
		}
		
		// Source item can be equipped or in inventory
		switch (item.getItemLocation())
		{
			case INVENTORY:
			case PAPERDOLL:
			{
				break;
			}
			default:
			{
				return false;
			}
		}
		
		if (item.getTemplate() instanceof Weapon)
		{
			switch (((Weapon) item.getTemplate()).getItemType())
			{
				case NONE:
				case FISHINGROD:
				{
					return false;
				}
				default:
				{
					break;
				}
			}
		}
		else if (item.getTemplate() instanceof Armor)
		{
			// Only accessories can be augmented.
			switch (item.getTemplate().getBodyPart())
			{
				case BodyPart.LR_FINGER:
				case BodyPart.LR_EAR:
				case BodyPart.NECK:
				{
					break;
				}
				default:
				{
					return false;
				}
			}
		}
		else
		{
			return false; // neither weapon nor armor ?
		}
		
		// blacklist check
		if (Arrays.binarySearch(PlayerConfig.AUGMENTATION_BLACKLIST, item.getId()) >= 0)
		{
			return false;
		}
		
		return true;
	}
	
	/**
	 * Check if player's conditions valid for augmentation process
	 * @param player
	 * @return
	 */
	protected static boolean isValid(Player player)
	{
		if (player.isInStoreMode())
		{
			player.sendPacket(SystemMessageId.YOU_CANNOT_AUGMENT_ITEMS_WHILE_A_PRIVATE_STORE_OR_PRIVATE_WORKSHOP_IS_IN_OPERATION);
			return false;
		}
		
		if (player.getActiveTradeList() != null)
		{
			player.sendPacket(SystemMessageId.YOU_CANNOT_AUGMENT_ITEMS_WHILE_ENGAGED_IN_TRADE_ACTIVITIES);
			return false;
		}
		
		if (player.isDead())
		{
			player.sendPacket(SystemMessageId.YOU_CANNOT_AUGMENT_ITEMS_WHILE_DEAD);
			return false;
		}
		
		if (player.isParalyzed())
		{
			player.sendPacket(SystemMessageId.YOU_CANNOT_AUGMENT_ITEMS_WHILE_PARALYZED);
			return false;
		}
		
		if (player.isFishing())
		{
			player.sendPacket(SystemMessageId.YOU_CANNOT_AUGMENT_ITEMS_WHILE_FISHING);
			return false;
		}
		
		if (player.isSitting())
		{
			player.sendPacket(SystemMessageId.YOU_CANNOT_AUGMENT_ITEMS_WHILE_SITTING_DOWN);
			return false;
		}
		
		if (player.isCursedWeaponEquipped())
		{
			return false;
		}
		
		if (player.isEnchanting() || player.isProcessingTransaction())
		{
			return false;
		}
		
		return true;
	}
	
	/**
	 * @param itemGrade
	 * @return GemStone itemId based on item grade
	 */
	protected static int getGemStoneId(CrystalType itemGrade)
	{
		switch (itemGrade)
		{
			case C:
			case B:
			{
				return GEMSTONE_D;
			}
			case A:
			case S:
			{
				return GEMSTONE_C;
			}
			case S80:
			case S84:
			{
				return GEMSTONE_B;
			}
			default:
			{
				return 0;
			}
		}
	}
	
	/**
	 * Different for weapon and accessory augmentation.
	 * @param itemGrade
	 * @param lifeStoneGrade
	 * @return GemStone count based on item grade and life stone grade
	 */
	protected static int getGemStoneCount(CrystalType itemGrade, int lifeStoneGrade)
	{
		switch (lifeStoneGrade)
		{
			case GRADE_ACC:
			{
				switch (itemGrade)
				{
					case C:
					{
						return 200;
					}
					case B:
					{
						return 300;
					}
					case A:
					{
						return 200;
					}
					case S:
					{
						return 250;
					}
					case S80:
					{
						return 360;
					}
					case S84:
					{
						return 480;
					}
					default:
					{
						return 0;
					}
				}
			}
			default:
			{
				switch (itemGrade)
				{
					case C:
					{
						return 20;
					}
					case B:
					{
						return 30;
					}
					case A:
					{
						return 20;
					}
					case S:
					{
						return 25;
					}
					case S80:
					case S84:
					{
						return 36;
					}
					default:
					{
						return 0;
					}
				}
			}
		}
	}
}
