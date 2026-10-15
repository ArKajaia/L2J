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
package org.l2jmobius.gameserver.model.craftinggoal;

/**
 * One resource of a {@link CraftingGoal}: how many the player has and how many are needed in total.<br>
 * A component is an ingredient of the goal's recipe. A material is needed to craft the components still missing (Leather for Crafted Leather, Animal Skin for Leather), and only counts what is left after the components.
 */
public class CraftingGoalLine
{
	private final int _itemId;
	private final long _needed;
	private final long _have;
	private final boolean _material;
	private final boolean _craftable;

	public CraftingGoalLine(int itemId, long needed, long have, boolean material, boolean craftable)
	{
		_itemId = itemId;
		_needed = needed;
		_have = have;
		_material = material;
		_craftable = craftable;
	}

	public int getItemId()
	{
		return _itemId;
	}

	public long getNeeded()
	{
		return _needed;
	}

	public long getHave()
	{
		return _have;
	}

	/**
	 * @return {@code true} for a material of a missing component, {@code false} for a component of the recipe
	 */
	public boolean isMaterial()
	{
		return _material;
	}

	/**
	 * @return {@code true} if a recipe makes this item
	 */
	public boolean isCraftable()
	{
		return _craftable;
	}

	public boolean isComplete()
	{
		return _have >= _needed;
	}

	/**
	 * @return the key of the line in its goal (an item can be both a component and a material)
	 */
	public int getKey()
	{
		return getKey(_itemId, _material);
	}

	public static int getKey(int itemId, boolean material)
	{
		return material ? -itemId : itemId;
	}
}
