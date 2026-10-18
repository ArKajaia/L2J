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
package org.l2jmobius.gameserver.network.serverpackets;

import java.util.ArrayList;
import java.util.List;

import org.l2jmobius.commons.network.WritableBuffer;
import org.l2jmobius.gameserver.model.craftinggoal.CraftingGoal;
import org.l2jmobius.gameserver.model.craftinggoal.CraftingGoalLine;
import org.l2jmobius.gameserver.model.craftinggoal.CraftingGoalNode;
import org.l2jmobius.gameserver.model.item.recipe.RecipeList;
import org.l2jmobius.gameserver.network.GameClient;
import org.l2jmobius.gameserver.network.ServerPackets;

/**
 * A pinned crafting goal for the client's tracker: the recipe, its tree and every resource line. Sent when the goal is pinned, on login and when the client asks for it again; {@link Ex2CraftingGoalUpdate} follows with what changes.<br>
 * The layout is in {@code client/CraftingGoals/README.txt}.
 */
public class Ex2CraftingGoalInfo extends ServerPacket
{
	private final int _recipeListId;
	private final int _productId;
	private final int _countPerCraft;
	private final int _crafts;
	private final int _successRate;
	private final boolean _known;
	private final boolean _dwarven;
	private final List<CraftingGoalNode> _tree;
	private final List<CraftingGoalLine> _lines;

	/**
	 * @param goal the goal, its lines as of its last check
	 * @param known {@code true} if the player knows the recipe
	 * @param tree the recipe tree
	 */
	public Ex2CraftingGoalInfo(CraftingGoal goal, boolean known, List<CraftingGoalNode> tree)
	{
		final RecipeList recipe = goal.getRecipe();
		_recipeListId = goal.getRecipeListId();
		_productId = goal.getProductId();
		_countPerCraft = recipe != null ? Math.max(1, recipe.getCount()) : 1;
		_crafts = goal.getCrafts();
		_successRate = recipe != null ? recipe.getSuccessRate() : 0;
		_known = known;
		_dwarven = (recipe != null) && recipe.isDwarvenRecipe();
		_tree = tree;
		_lines = new ArrayList<>(goal.getLines());
	}

	@Override
	public void writeImpl(GameClient client, WritableBuffer buffer)
	{
		ServerPackets.EX2_CRAFTING_GOAL_INFO.writeId(this, buffer);
		buffer.writeInt(_recipeListId);
		buffer.writeInt(_productId);
		buffer.writeInt(_countPerCraft);
		buffer.writeInt(_crafts);
		buffer.writeInt(_successRate);
		buffer.writeByte(_known);
		buffer.writeByte(_dwarven);

		buffer.writeInt(_tree.size());
		for (CraftingGoalNode node : _tree)
		{
			buffer.writeInt(node.getParentIndex());
			buffer.writeInt(node.getItemId());
			buffer.writeLong(node.getQuantity());
			buffer.writeInt(node.getOutputPerCraft());
		}

		buffer.writeByte(CraftingGoal.isReady(_lines));
		buffer.writeInt(CraftingGoal.countCompleteComponents(_lines));
		buffer.writeInt(CraftingGoal.countComponents(_lines));
		buffer.writeInt(_lines.size());
		for (CraftingGoalLine line : _lines)
		{
			writeLine(buffer, line);
		}
	}

	/**
	 * Writes a resource line: {@code int itemId, byte material, byte craftable, long needed, long have}.
	 * @param buffer the buffer
	 * @param line the line
	 */
	static void writeLine(WritableBuffer buffer, CraftingGoalLine line)
	{
		buffer.writeInt(line.getItemId());
		buffer.writeByte(line.isMaterial());
		buffer.writeByte(line.isCraftable());
		buffer.writeLong(line.getNeeded());
		buffer.writeLong(line.getHave());
	}
}
