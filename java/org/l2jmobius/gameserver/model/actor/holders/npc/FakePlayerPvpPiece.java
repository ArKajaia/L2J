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
package org.l2jmobius.gameserver.model.actor.holders.npc;

import java.util.Arrays;

import org.l2jmobius.gameserver.data.xml.ItemData;
import org.l2jmobius.gameserver.model.item.ItemTemplate;
import org.l2jmobius.gameserver.model.item.holders.ItemEnchantHolder;
import org.l2jmobius.gameserver.model.item.instance.Item;

/**
 * A piece of the gear a roaming fake player wears or carries (see {@link FakePlayerPvpProfile#getEquipment()}): the item with what the fake player has done to it, its enchant level, its attributes (see {@code FakePlayerPvpRefine}) and whether it is augmented.<br>
 * What a kill drops is made from it ({@link #applyTo}), so the dropped item is the one the fake player held and not a plain one of the same id.
 */
public class FakePlayerPvpPiece extends ItemEnchantHolder
{
	/** The six attributes, indexed like Elementals.FIRE ... Elementals.DARK. */
	private static final int ELEMENTS = 6;

	private final int[] _attributes;
	private final boolean _augmented;

	/**
	 * A piece with no attribute and no augment.
	 * @param id the item id
	 * @param enchantLevel its enchant level
	 */
	public FakePlayerPvpPiece(int id, int enchantLevel)
	{
		this(id, enchantLevel, null, false);
	}

	/**
	 * @param id the item id
	 * @param enchantLevel its enchant level
	 * @param attributes its attribute value of each element (index Elementals.FIRE ... Elementals.DARK), 0 for none; {@code null} for none at all
	 * @param augmented {@code true} if the item is augmented
	 */
	public FakePlayerPvpPiece(int id, int enchantLevel, int[] attributes, boolean augmented)
	{
		super(id, 1, enchantLevel);
		_attributes = attributes != null ? Arrays.copyOf(attributes, ELEMENTS) : new int[ELEMENTS];
		_augmented = augmented;
	}

	/**
	 * @param element an element (Elementals.FIRE ...)
	 * @return the attribute value the item has in it, 0 for none (the attack attribute of a weapon, the resistance of an armor piece)
	 */
	public int getAttribute(byte element)
	{
		return (element >= 0) && (element < ELEMENTS) ? _attributes[element] : 0;
	}

	/**
	 * @return {@code true} if the item is augmented
	 */
	public boolean isAugmented()
	{
		return _augmented;
	}

	/**
	 * @return {@code true} if a kill may drop it: what a player could drop (see {@code Item#isDropable()}) and never an augmented item, which can't be dropped, so its augment can't be lost on the ground either
	 */
	public boolean isDropable()
	{
		if (_augmented)
		{
			return false;
		}

		final ItemTemplate template = ItemData.getInstance().getTemplate(getId());
		return (template != null) && template.isDropable();
	}

	/**
	 * Gives an item just made for the drop the enchant level and the attributes of this piece. It has no owner and no place in an inventory yet, so it is only changed in memory, and it is saved with them when somebody picks it up.
	 * @param item the item made from this piece's id, before it is dropped
	 */
	public void applyTo(Item item)
	{
		if ((getEnchantLevel() > 0) && item.getTemplate().isEnchantable())
		{
			item.setEnchantLevel(getEnchantLevel());
		}

		for (byte element = 0; element < ELEMENTS; element++)
		{
			if (_attributes[element] > 0)
			{
				item.applyAttribute(element, _attributes[element]);
			}
		}
	}
}
