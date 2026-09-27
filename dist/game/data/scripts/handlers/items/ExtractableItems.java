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
package handlers.items;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;

import org.l2jmobius.commons.util.Rnd;
import org.l2jmobius.gameserver.config.RatesConfig;
import org.l2jmobius.gameserver.data.xml.ItemData;
import org.l2jmobius.gameserver.handler.IItemHandler;
import org.l2jmobius.gameserver.model.actor.Playable;
import org.l2jmobius.gameserver.model.actor.Player;
import org.l2jmobius.gameserver.model.item.EtcItem;
import org.l2jmobius.gameserver.model.item.ItemTemplate;
import org.l2jmobius.gameserver.model.item.enums.ItemProcessType;
import org.l2jmobius.gameserver.model.item.holders.ExtractableProduct;
import org.l2jmobius.gameserver.model.item.instance.Item;
import org.l2jmobius.gameserver.network.SystemMessageId;
import org.l2jmobius.gameserver.network.serverpackets.InventoryUpdate;
import org.l2jmobius.gameserver.network.serverpackets.SystemMessage;

/**
 * Extractable Items handler.
 * @author HorridoJoho, Mobius
 */
public class ExtractableItems implements IItemHandler
{
	@Override
	public boolean onItemUse(Playable playable, Item item, boolean forceUse)
	{
		if (!playable.isPlayer())
		{
			playable.sendPacket(SystemMessageId.YOUR_PET_CANNOT_CARRY_THIS_ITEM);
			return false;
		}
		
		final Player player = playable.asPlayer();
		final EtcItem etcitem = (EtcItem) item.getTemplate();
		final List<ExtractableProduct> exitems = etcitem.getExtractableItems();
		if (exitems == null)
		{
			LOGGER.info("No extractable data defined for " + etcitem);
			return false;
		}
		
		// Roll the contents first, so the box is only used up if this exact result fits in the inventory.
		final List<ExtractedProduct> rolled = rollProducts(etcitem, exitems);
		final int requiredSlots = getRequiredSlots(player, rolled);
		if (!player.getInventory().validateCapacity(requiredSlots))
		{
			player.sendMessage("You need " + requiredSlots + " free inventory slots to open this item.");
			return false;
		}
		
		// destroy item
		if (!player.destroyItem(ItemProcessType.FEE, item.getObjectId(), 1, player, true))
		{
			return false;
		}
		
		final Map<Item, Long> extractedItems = new HashMap<>();
		final List<Item> enchantedItems = new ArrayList<>();
		for (ExtractedProduct extracted : rolled)
		{
			final ExtractableProduct expi = extracted.product();
			final int count = extracted.template().isStackable() ? 1 : (int) extracted.count();
			final long amountEach = extracted.template().isStackable() ? extracted.count() : 1;
			for (int i = 0; i < count; i++)
			{
				final Item newItem = player.addItem(ItemProcessType.REWARD, expi.getId(), amountEach, player, false);
				if (newItem == null)
				{
					continue;
				}
				
				if (expi.getMaxEnchant() > 0)
				{
					newItem.setEnchantLevel(Rnd.get(expi.getMinEnchant(), expi.getMaxEnchant()));
					enchantedItems.add(newItem);
				}
				
				addItem(extractedItems, newItem, amountEach);
			}
		}
		
		if (extractedItems.isEmpty())
		{
			player.sendPacket(SystemMessageId.THERE_WAS_NOTHING_FOUND_INSIDE);
		}
		
		if (!enchantedItems.isEmpty())
		{
			final InventoryUpdate playerIU = new InventoryUpdate();
			for (Item i : enchantedItems)
			{
				playerIU.addModifiedItem(i);
			}
			
			player.sendInventoryUpdate(playerIU);
		}
		
		for (Entry<Item, Long> entry : extractedItems.entrySet())
		{
			sendMessage(player, entry.getKey(), entry.getValue().longValue());
		}
		
		return true;
	}
	
	/**
	 * One rolled product: a stackable product is one stack of {@code count}, a non-stackable one is {@code count} separate items.
	 * @param product the extractable product that hit
	 * @param template its item template
	 * @param count the amount rolled
	 */
	private record ExtractedProduct(ExtractableProduct product, ItemTemplate template, long count)
	{
		/**
		 * @return how many distinct items this adds to the extraction (a stack counts once)
		 */
		int distinctItems()
		{
			return template.isStackable() ? 1 : (int) count;
		}
	}
	
	/**
	 * Rolls what the item gives, without handing anything out. Follows the extractableCountMin/Max rules: with a minimum, the product list is rolled again until at least that many distinct items hit, never going past the maximum.
	 * @param etcitem the extractable item template
	 * @param exitems its possible products
	 * @return the rolled products
	 */
	private List<ExtractedProduct> rollProducts(EtcItem etcitem, List<ExtractableProduct> exitems)
	{
		final List<ExtractedProduct> rolled = new ArrayList<>();
		final int countMin = etcitem.getExtractableCountMin();
		final int countMax = etcitem.getExtractableCountMax();
		int distinctItems = 0;
		do
		{
			for (ExtractableProduct expi : exitems)
			{
				if ((countMax > 0) && (distinctItems >= countMax))
				{
					break;
				}
				
				if (Rnd.get(100000) > expi.getChance())
				{
					continue;
				}
				
				final long min = (long) (expi.getMin() * RatesConfig.RATE_EXTRACTABLE);
				final long max = (long) (expi.getMax() * RatesConfig.RATE_EXTRACTABLE);
				final long createItemAmount = (max == min) ? min : (Rnd.get((max - min) + 1) + min);
				if (createItemAmount == 0)
				{
					continue;
				}
				
				// Do not extract the same item twice on a re-roll.
				if ((countMin > 0) && (exitems.size() >= countMax) && isRolled(rolled, expi.getId()))
				{
					continue;
				}
				
				final ItemTemplate template = ItemData.getInstance().getTemplate(expi.getId());
				if (template == null)
				{
					LOGGER.warning("ExtractableItems: Could not find " + etcitem + " product template with id " + expi.getId() + "!");
					continue;
				}
				
				final ExtractedProduct extracted = new ExtractedProduct(expi, template, createItemAmount);
				if (!template.isStackable() || !isRolled(rolled, expi.getId()))
				{
					distinctItems += extracted.distinctItems();
				}
				rolled.add(extracted);
			}
		}
		while (distinctItems < countMin);
		
		return rolled;
	}
	
	private static boolean isRolled(List<ExtractedProduct> rolled, int itemId)
	{
		for (ExtractedProduct extracted : rolled)
		{
			if (extracted.product().getId() == itemId)
			{
				return true;
			}
		}
		return false;
	}
	
	/**
	 * @param player the player opening the item
	 * @param rolled the rolled products
	 * @return the free inventory slots the rolled products need: one per stackable item the player doesn't already hold, one per non-stackable item
	 */
	private int getRequiredSlots(Player player, List<ExtractedProduct> rolled)
	{
		final Set<Integer> newStacks = new HashSet<>();
		long slots = 0;
		for (ExtractedProduct extracted : rolled)
		{
			if (!extracted.template().isStackable())
			{
				slots += extracted.count();
			}
			else if ((player.getInventory().getItemByItemId(extracted.product().getId()) == null) && newStacks.add(extracted.product().getId()))
			{
				slots++;
			}
		}
		return (int) Math.min(slots, Integer.MAX_VALUE);
	}
	
	private void addItem(Map<Item, Long> extractedItems, Item newItem, long count)
	{
		if (extractedItems.containsKey(newItem))
		{
			extractedItems.put(newItem, extractedItems.get(newItem) + count);
		}
		else
		{
			extractedItems.put(newItem, count);
		}
	}
	
	private void sendMessage(Player player, Item item, long count)
	{
		final SystemMessage sm;
		if (count > 1)
		{
			sm = new SystemMessage(SystemMessageId.YOU_HAVE_OBTAINED_S2_S1);
			sm.addItemName(item);
			sm.addLong(count);
		}
		else if (item.isEnchanted())
		{
			sm = new SystemMessage(SystemMessageId.YOU_HAVE_OBTAINED_A_S1_S2);
			sm.addInt(item.getEnchantLevel());
			sm.addItemName(item);
		}
		else
		{
			sm = new SystemMessage(SystemMessageId.YOU_HAVE_OBTAINED_S1);
			sm.addItemName(item);
		}
		
		player.sendPacket(sm);
	}
}
