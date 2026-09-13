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
package org.l2jmobius.gameserver.data.holders;

import org.l2jmobius.gameserver.data.xml.ItemData;
import org.l2jmobius.gameserver.model.item.ItemTemplate;

/**
 * @author Mobius
 */
public class PrimeShopProductHolder
{
	private final int _productId;
	private final int _category;
	private final int _points;
	private final int _item;
	private final int _count;
	
	private final int _weight;
	private final boolean _tradable;
	
	public PrimeShopProductHolder(int productId, int category, int points, int item, int count)
	{
		_productId = productId;
		_category = category;
		_points = points;
		_item = item;
		_count = count;
		
		final ItemTemplate itemTemplate = ItemData.getInstance().getTemplate(item);
		if (itemTemplate != null)
		{
			_weight = itemTemplate.getWeight();
			_tradable = itemTemplate.isTradeable();
		}
		else
		{
			_weight = 0;
			_tradable = true;
		}
	}
	
	public int getProductId()
	{
		return _productId;
	}
	
	public int getCategory()
	{
		return _category;
	}
	
	public int getPrice()
	{
		return _points;
	}
	
	public int getItemId()
	{
		return _item;
	}
	
	public int getItemCount()
	{
		return _count;
	}
	
	public int getItemWeight()
	{
		return _weight;
	}
	
	public boolean isTradable()
	{
		return _tradable;
	}
}
