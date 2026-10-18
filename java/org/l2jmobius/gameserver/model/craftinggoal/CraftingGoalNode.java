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
 * One node of a goal's recipe tree, sent to the client for a pinned goal: an ingredient of the goal's recipe (no parent) or of the recipe that makes its parent node.<br>
 * The tree is the recipe's structure only. How many the player has and needs are the goal's {@link CraftingGoalLine}s: a node without a parent goes with the component line of its item, a deeper node with the material line.
 */
public class CraftingGoalNode
{
	private final int _parentIndex;
	private final int _itemId;
	private final long _quantity;
	private final int _outputPerCraft;

	/**
	 * @param parentIndex the index of the parent node, -1 for an ingredient of the goal's recipe
	 * @param itemId the item
	 * @param quantity how many one craft of the parent's recipe uses
	 * @param outputPerCraft how many one craft of the recipe broken down under this node makes, 0 if it is not broken down
	 */
	public CraftingGoalNode(int parentIndex, int itemId, long quantity, int outputPerCraft)
	{
		_parentIndex = parentIndex;
		_itemId = itemId;
		_quantity = quantity;
		_outputPerCraft = outputPerCraft;
	}

	public int getParentIndex()
	{
		return _parentIndex;
	}

	public int getItemId()
	{
		return _itemId;
	}

	public long getQuantity()
	{
		return _quantity;
	}

	public int getOutputPerCraft()
	{
		return _outputPerCraft;
	}
}
