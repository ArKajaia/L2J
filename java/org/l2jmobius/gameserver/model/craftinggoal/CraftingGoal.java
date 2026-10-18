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

import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.l2jmobius.gameserver.data.xml.RecipeData;
import org.l2jmobius.gameserver.model.item.recipe.RecipeList;

/**
 * An item a player wants to craft, tracked like a quest (see {@link org.l2jmobius.gameserver.managers.CraftingGoalManager}).<br>
 * It keeps the crafts still to make, the resources already celebrated (so dropping and picking an item up again sets off no new firework) and the state of each resource at the last check.
 */
public class CraftingGoal
{
	private final int _recipeListId;
	private int _crafts;
	private boolean _readyCelebrated;
	private boolean _pinned;
	private final Set<Integer> _celebrated = new HashSet<>();

	private List<CraftingGoalLine> _lines = Collections.emptyList();
	private final Set<Integer> _complete = new HashSet<>();
	private final Map<Integer, Long> _lastHave = new HashMap<>();

	public CraftingGoal(int recipeListId, int crafts)
	{
		_recipeListId = recipeListId;
		_crafts = crafts;
	}

	public int getRecipeListId()
	{
		return _recipeListId;
	}

	public RecipeList getRecipe()
	{
		return RecipeData.getInstance().getRecipeList(_recipeListId);
	}

	/**
	 * @return the item the goal makes
	 */
	public int getProductId()
	{
		final RecipeList recipe = getRecipe();
		return recipe != null ? recipe.getItemId() : 0;
	}

	/**
	 * @return how many times the recipe still has to be crafted
	 */
	public int getCrafts()
	{
		return _crafts;
	}

	public void setCrafts(int crafts)
	{
		_crafts = crafts;
	}

	/**
	 * @return how many items the crafts still to make give
	 */
	public long getAmount()
	{
		final RecipeList recipe = getRecipe();
		return (long) _crafts * (recipe != null ? Math.max(1, recipe.getCount()) : 1);
	}

	public boolean isReadyCelebrated()
	{
		return _readyCelebrated;
	}

	public void setReadyCelebrated(boolean readyCelebrated)
	{
		_readyCelebrated = readyCelebrated;
	}

	/**
	 * @return {@code true} if the goal is pinned to the client's screen
	 */
	public boolean isPinned()
	{
		return _pinned;
	}

	public void setPinned(boolean pinned)
	{
		_pinned = pinned;
	}

	public Set<Integer> getCelebrated()
	{
		return _celebrated;
	}

	public List<CraftingGoalLine> getLines()
	{
		return _lines;
	}

	public void setLines(List<CraftingGoalLine> lines)
	{
		_lines = lines;
	}

	/**
	 * @return the keys of the lines complete at the last check
	 */
	public Set<Integer> getComplete()
	{
		return _complete;
	}

	/**
	 * @return how many of each line the player had at the last check
	 */
	public Map<Integer, Long> getLastHave()
	{
		return _lastHave;
	}

	/**
	 * @return {@code true} if the player has every component of the recipe for the crafts still to make
	 */
	public boolean isReady()
	{
		return isReady(_lines);
	}
	
	/**
	 * @return how many components of the recipe the player has enough of
	 */
	public int getCompleteComponents()
	{
		return countCompleteComponents(_lines);
	}
	
	/**
	 * @return how many components the recipe has
	 */
	public int getComponentCount()
	{
		return countComponents(_lines);
	}
	
	/**
	 * @param lines the lines of a goal
	 * @return {@code true} if every component is complete
	 */
	public static boolean isReady(List<CraftingGoalLine> lines)
	{
		return !lines.isEmpty() && (countCompleteComponents(lines) == countComponents(lines));
	}
	
	/**
	 * @param lines the lines of a goal
	 * @return how many components are complete
	 */
	public static int countCompleteComponents(List<CraftingGoalLine> lines)
	{
		int count = 0;
		for (CraftingGoalLine line : lines)
		{
			if (!line.isMaterial() && line.isComplete())
			{
				count++;
			}
		}
		return count;
	}
	
	/**
	 * @param lines the lines of a goal
	 * @return how many components there are
	 */
	public static int countComponents(List<CraftingGoalLine> lines)
	{
		int count = 0;
		for (CraftingGoalLine line : lines)
		{
			if (!line.isMaterial())
			{
				count++;
			}
		}
		return count;
	}
}
