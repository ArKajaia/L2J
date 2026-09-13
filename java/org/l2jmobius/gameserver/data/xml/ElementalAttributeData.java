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
package org.l2jmobius.gameserver.data.xml;

import java.io.File;
import java.util.HashMap;
import java.util.Map;

import org.w3c.dom.Document;

import org.l2jmobius.commons.util.IXmlReader;
import org.l2jmobius.gameserver.data.holders.ElementalItemHolder;
import org.l2jmobius.gameserver.model.StatSet;
import org.l2jmobius.gameserver.model.item.enums.ElementalItemType;
import org.l2jmobius.gameserver.model.item.holders.Elementals;

/**
 * @author Mobius
 */
public class ElementalAttributeData implements IXmlReader
{
	private static final Map<Integer, ElementalItemHolder> ELEMENTAL_ITEMS = new HashMap<>();
	
	protected ElementalAttributeData()
	{
		load();
	}
	
	@Override
	public void load()
	{
		ELEMENTAL_ITEMS.clear();
		parseDatapackFile("data/ElementalAttributeData.xml");
		LOGGER.info(getClass().getSimpleName() + ": Loaded " + ELEMENTAL_ITEMS.size() + " elemental attribute items.");
	}
	
	@Override
	public void parseDocument(Document document, File file)
	{
		forEach(document, "list", listNode -> forEach(listNode, "item", itemNode ->
		{
			final StatSet set = new StatSet(parseAttributes(itemNode));
			
			final int id = set.getInt("id");
			if (ItemData.getInstance().getTemplate(id) == null)
			{
				LOGGER.info(getClass().getSimpleName() + ": Could not find item with id " + id + ".");
				return;
			}
			
			int elementalId = Elementals.NONE;
			switch (set.getString("elemental"))
			{
				case "FIRE":
				{
					elementalId = Elementals.FIRE;
					break;
				}
				case "WATER":
				{
					elementalId = Elementals.WATER;
					break;
				}
				case "WIND":
				{
					elementalId = Elementals.WIND;
					break;
				}
				case "EARTH":
				{
					elementalId = Elementals.EARTH;
					break;
				}
				case "HOLY":
				{
					elementalId = Elementals.HOLY;
					break;
				}
				case "DARK":
				{
					elementalId = Elementals.DARK;
					break;
				}
			}
			
			ELEMENTAL_ITEMS.put(id, new ElementalItemHolder(id, elementalId, set.getEnum("type", ElementalItemType.class)));
		}));
	}
	
	public ElementalItemHolder getElementalItem(int itemId)
	{
		return ELEMENTAL_ITEMS.getOrDefault(itemId, null);
	}
	
	public static ElementalAttributeData getInstance()
	{
		return SingletonHolder.INSTANCE;
	}
	
	private static class SingletonHolder
	{
		protected static final ElementalAttributeData INSTANCE = new ElementalAttributeData();
	}
	
}
