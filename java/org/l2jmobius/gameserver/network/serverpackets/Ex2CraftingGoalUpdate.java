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

import java.util.List;

import org.l2jmobius.commons.network.WritableBuffer;
import org.l2jmobius.gameserver.model.craftinggoal.CraftingGoal;
import org.l2jmobius.gameserver.model.craftinggoal.CraftingGoalLine;
import org.l2jmobius.gameserver.network.GameClient;
import org.l2jmobius.gameserver.network.ServerPackets;

/**
 * What changed in a pinned crafting goal since the last {@link Ex2CraftingGoalInfo} or update: the lines that are new or whose counts changed, and the keys of the lines that are gone (the materials of a component just completed).<br>
 * The layout is in {@code client/CraftingGoals/README.txt}.
 */
public class Ex2CraftingGoalUpdate extends ServerPacket
{
	private final int _recipeListId;
	private final int _crafts;
	private final boolean _ready;
	private final int _done;
	private final int _total;
	private final List<CraftingGoalLine> _changed;
	private final List<Integer> _removed;

	/**
	 * @param recipeListId the goal's recipe
	 * @param crafts the crafts still to make
	 * @param lines every line of the goal now
	 * @param changed the lines that are new or changed
	 * @param removed the keys ({@link CraftingGoalLine#getKey()}) of the lines that are gone
	 */
	public Ex2CraftingGoalUpdate(int recipeListId, int crafts, List<CraftingGoalLine> lines, List<CraftingGoalLine> changed, List<Integer> removed)
	{
		_recipeListId = recipeListId;
		_crafts = crafts;
		_ready = CraftingGoal.isReady(lines);
		_done = CraftingGoal.countCompleteComponents(lines);
		_total = CraftingGoal.countComponents(lines);
		_changed = changed;
		_removed = removed;
	}

	@Override
	public void writeImpl(GameClient client, WritableBuffer buffer)
	{
		ServerPackets.EX2_CRAFTING_GOAL_UPDATE.writeId(this, buffer);
		buffer.writeInt(_recipeListId);
		buffer.writeInt(_crafts);
		buffer.writeByte(_ready);
		buffer.writeInt(_done);
		buffer.writeInt(_total);

		buffer.writeInt(_changed.size());
		for (CraftingGoalLine line : _changed)
		{
			Ex2CraftingGoalInfo.writeLine(buffer, line);
		}

		buffer.writeInt(_removed.size());
		for (int key : _removed)
		{
			buffer.writeInt(Math.abs(key));
			buffer.writeByte(key < 0);
		}
	}
}
