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
package org.l2jmobius.gameserver.data.xml;

import java.io.File;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.logging.Level;

import org.w3c.dom.Document;
import org.w3c.dom.NamedNodeMap;
import org.w3c.dom.Node;

import org.l2jmobius.commons.util.IXmlReader;
import org.l2jmobius.gameserver.model.StatSet;
import org.l2jmobius.gameserver.model.item.enchant.EnchantScroll;
import org.l2jmobius.gameserver.model.item.enchant.EnchantSupportItem;
import org.l2jmobius.gameserver.model.item.instance.Item;

/**
 * @author UnAfraid
 */
public class EnchantItemData implements IXmlReader
{
	private final Map<Integer, EnchantScroll> _scrolls = new HashMap<>();
	private final Map<Integer, EnchantSupportItem> _supports = new HashMap<>();
	
	public EnchantItemData()
	{
		load();
	}
	
	@Override
	public synchronized void load()
	{
		_scrolls.clear();
		_supports.clear();
		parseDatapackFile("data/EnchantItemData.xml");
		LOGGER.info(getClass().getSimpleName() + ": Loaded " + _scrolls.size() + " enchant scrolls.");
		LOGGER.info(getClass().getSimpleName() + ": Loaded " + _supports.size() + " support items.");
	}
	
	@Override
	public void parseDocument(Document document, File file)
	{
		StatSet set;
		Node att;
		NamedNodeMap attrs;
		for (Node n = document.getFirstChild(); n != null; n = n.getNextSibling())
		{
			if ("list".equalsIgnoreCase(n.getNodeName()))
			{
				for (Node d = n.getFirstChild(); d != null; d = d.getNextSibling())
				{
					if ("enchant".equalsIgnoreCase(d.getNodeName()))
					{
						attrs = d.getAttributes();
						set = new StatSet();
						for (int i = 0; i < attrs.getLength(); i++)
						{
							att = attrs.item(i);
							set.set(att.getNodeName(), att.getNodeValue());
						}
						
						try
						{
							final EnchantScroll item = new EnchantScroll(set);
							for (Node cd = d.getFirstChild(); cd != null; cd = cd.getNextSibling())
							{
								if ("item".equalsIgnoreCase(cd.getNodeName()))
								{
									item.addItem(parseInteger(cd.getAttributes(), "id"), parseInteger(cd.getAttributes(), "altScrollGroupId", -1));
								}
							}
							
							_scrolls.put(item.getId(), item);
						}
						catch (NullPointerException e)
						{
							LOGGER.log(Level.WARNING, getClass().getSimpleName() + ": Unexistent enchant scroll: " + set.getString("id") + " defined in enchant data!");
						}
						catch (IllegalAccessError e)
						{
							LOGGER.log(Level.WARNING, getClass().getSimpleName() + ": Wrong enchant scroll item type: " + set.getString("id") + " defined in enchant data!");
						}
					}
					else if ("support".equalsIgnoreCase(d.getNodeName()))
					{
						attrs = d.getAttributes();
						set = new StatSet();
						for (int i = 0; i < attrs.getLength(); i++)
						{
							att = attrs.item(i);
							set.set(att.getNodeName(), att.getNodeValue());
						}
						
						try
						{
							final EnchantSupportItem item = new EnchantSupportItem(set);
							_supports.put(item.getId(), item);
						}
						catch (NullPointerException e)
						{
							LOGGER.log(Level.WARNING, getClass().getSimpleName() + ": Unexistent enchant support item: " + set.getString("id") + " defined in enchant data!");
						}
						catch (IllegalAccessError e)
						{
							LOGGER.log(Level.WARNING, getClass().getSimpleName() + ": Wrong enchant support item type: " + set.getString("id") + " defined in enchant data!");
						}
					}
				}
			}
		}
	}
	
	/**
	 * Retrieves all registered enchant scrolls.
	 * @return a collection of all {@link EnchantScroll} instances currently stored.
	 */
	public Collection<EnchantScroll> getScrolls()
	{
		return _scrolls.values();
	}
	
	/**
	 * Retrieves the enchant scroll template associated with the specified item.
	 * @param item the {@link Item} representing the scroll to retrieve.
	 * @return the {@link EnchantScroll} template for the specified scroll item, or {@code null} if the item is not found.
	 */
	public EnchantScroll getEnchantScroll(Item item)
	{
		if (item == null)
		{
			return null;
		}
		
		return _scrolls.get(item.getId());
	}
	
	/**
	 * Retrieves the support item template associated with the specified item.
	 * @param item the {@link Item} representing the support item to retrieve.
	 * @return the {@link EnchantSupportItem} template for the specified support item, or {@code null} if the item is not found.
	 */
	public EnchantSupportItem getSupportItem(Item item)
	{
		if (item == null)
		{
			return null;
		}
		
		return _supports.get(item.getId());
	}
	
	public static EnchantItemData getInstance()
	{
		return SingletonHolder.INSTANCE;
	}
	
	private static class SingletonHolder
	{
		protected static final EnchantItemData INSTANCE = new EnchantItemData();
	}
}
