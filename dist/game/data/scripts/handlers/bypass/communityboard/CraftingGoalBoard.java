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
package handlers.bypass.communityboard;

import java.util.ArrayList;
import java.util.List;

import org.l2jmobius.gameserver.cache.HtmCache;
import org.l2jmobius.gameserver.config.custom.CraftingGoalsConfig;
import org.l2jmobius.gameserver.data.holders.RecipeHolder;
import org.l2jmobius.gameserver.data.xml.ItemData;
import org.l2jmobius.gameserver.data.xml.RecipeData;
import org.l2jmobius.gameserver.handler.CommunityBoardHandler;
import org.l2jmobius.gameserver.handler.IParseBoardHandler;
import org.l2jmobius.gameserver.managers.CraftingGoalManager;
import org.l2jmobius.gameserver.model.actor.Player;
import org.l2jmobius.gameserver.model.craftinggoal.CraftingGoal;
import org.l2jmobius.gameserver.model.craftinggoal.CraftingGoalLine;
import org.l2jmobius.gameserver.model.item.ItemTemplate;
import org.l2jmobius.gameserver.model.item.recipe.RecipeList;

/**
 * The crafting goal tracker (see {@link CraftingGoalManager}): the goals with the progress of each resource, a recipe search, the player's recipe book and a recipe's page where a goal is taken.<br>
 * The same pages show on the Community Board ({@code _bbscraft}) and in a window ({@code .craft}); only the width and the bypass differ. The commands after the bypass:
 * <ul>
 * <li>(none) - the goals</li>
 * <li>{@code goal <recipe>} - the resources of a goal</li>
 * <li>{@code find <page> <words>} - recipes whose item name holds the words</li>
 * <li>{@code book <page>} - the recipes the player knows</li>
 * <li>{@code view <recipe>} - a recipe, and how many items to aim for</li>
 * <li>{@code add <recipe> <amount>} - take (or change) a goal</li>
 * <li>{@code abandon <recipe>} - drop a goal</li>
 * </ul>
 */
public class CraftingGoalBoard implements IParseBoardHandler
{
	public static final String BOARD_BYPASS = "_bbscraft";
	public static final String WINDOW_BYPASS = "voiced_craft";

	private static final String NAVIGATION_PATH = "data/html/CommunityBoard/Custom/navigation.html";
	private static final String PAGE_PATH = "data/html/CommunityBoard/Custom/craft/main.html";

	private static final String COLOR_TITLE = "CDB67F";
	private static final String COLOR_VALUE = "FFCC33";
	private static final String COLOR_TEXT = "B0B0B0";
	private static final String COLOR_MUTED = "707070";
	private static final String COLOR_DONE = "88DD66";
	private static final String COLOR_RED = "FF6666";

	private static final String BAR_BACK = "L2UI_CT1.Gauges.Gauge_DF_Large_MP_bg_Center";
	private static final String BAR_FILL = "L2UI_CT1.Gauges.Gauge_DF_Large_MP_Center";
	private static final String BAR_DONE_BACK = "L2UI_CT1.Gauges.Gauge_DF_Large_CP_bg_Center";
	private static final String BAR_DONE_FILL = "L2UI_CT1.Gauges.Gauge_DF_Large_CP_Center";
	private static final String NO_ICON = "icon.etc_question_mark_i00";

	private static final int BOARD_WIDTH = 540;
	private static final int BOARD_RESULTS = 12;
	private static final int WINDOW_RESULTS = 7;
	/** A window holds 17,200 characters of html, the buttons below take about 1,000: past this the remaining materials are left to the board. */
	private static final int WINDOW_HTML_BUDGET = 16100;
	/** About how long the html of one resource line is. */
	private static final int LINE_HTML_SIZE = 700;
	/** The height of a row with an icon, a bit more than the 32x32 icon. */
	private static final int ROW_HEIGHT = 40;
	private static final int ICON_CELL = 40;

	private static final String[] COMMANDS =
	{
		BOARD_BYPASS
	};

	@Override
	public String[] getCommandList()
	{
		return COMMANDS;
	}

	@Override
	public boolean onCommand(String command, Player player)
	{
		CommunityBoardHandler.getInstance().addBypass(player, "Crafting Goals", command);

		final String params = command.length() > BOARD_BYPASS.length() ? command.substring(BOARD_BYPASS.length()).trim() : "";
		String html = HtmCache.getInstance().getHtm(player, PAGE_PATH);
		html = html.replace("%navigation%", HtmCache.getInstance().getHtm(player, NAVIGATION_PATH));
		html = html.replace("%content%", buildContent(player, params, BOARD_WIDTH, BOARD_BYPASS));
		CommunityBoardHandler.separateAndSend(html, player);
		return true;
	}

	/**
	 * Runs a command of the tracker and builds the page to show.
	 * @param player the player
	 * @param params the command and its arguments (empty for the goals)
	 * @param width the width of the page
	 * @param bypass the bypass of the page's buttons ({@link #BOARD_BYPASS} or {@link #WINDOW_BYPASS})
	 * @return the page as html
	 */
	public static String buildContent(Player player, String params, int width, String bypass)
	{
		if (!CraftingGoalsConfig.ENABLED)
		{
			return "<table width=" + width + "><tr><td align=center><font color=\"" + COLOR_TEXT + "\">Crafting goals are disabled.</font></td></tr></table>";
		}

		final String[] args = params.isEmpty() ? new String[0] : params.split("\\s+");
		final String action = args.length > 0 ? args[0].toLowerCase() : "";
		try
		{
			switch (action)
			{
				case "goal":
				{
					return buildGoal(player, Integer.parseInt(args[1]), width, bypass);
				}
				case "find":
				{
					final StringBuilder words = new StringBuilder();
					for (int i = 2; i < args.length; i++)
					{
						words.append(i > 2 ? " " : "").append(args[i]);
					}
					return buildSearch(player, words.toString(), args.length > 1 ? Integer.parseInt(args[1]) : 1, width, bypass);
				}
				case "book":
				{
					return buildBook(player, args.length > 1 ? Integer.parseInt(args[1]) : 1, width, bypass);
				}
				case "view":
				{
					return buildRecipe(player, Integer.parseInt(args[1]), null, width, bypass);
				}
				case "add":
				{
					final int recipeListId = Integer.parseInt(args[1]);
					long amount = 1;
					if (args.length > 2)
					{
						try
						{
							amount = Long.parseLong(args[2]);
						}
						catch (NumberFormatException e)
						{
							return buildRecipe(player, recipeListId, "Type how many items you want.", width, bypass);
						}
					}

					final String error = CraftingGoalManager.getInstance().addGoal(player, recipeListId, amount);
					if (error != null)
					{
						return buildRecipe(player, recipeListId, error, width, bypass);
					}
					return buildGoal(player, recipeListId, width, bypass);
				}
				case "abandon":
				{
					CraftingGoalManager.getInstance().removeGoal(player, Integer.parseInt(args[1]));
					return buildGoals(player, width, bypass);
				}
				default:
				{
					return buildGoals(player, width, bypass);
				}
			}
		}
		catch (NumberFormatException | ArrayIndexOutOfBoundsException e)
		{
			return buildGoals(player, width, bypass);
		}
	}

	// ---------------------------------------------------------------------------------------------
	// Pages
	// ---------------------------------------------------------------------------------------------

	/**
	 * The goals, each with how many of its components the player has.
	 */
	private static String buildGoals(Player player, int width, String bypass)
	{
		final CraftingGoalManager manager = CraftingGoalManager.getInstance();
		final List<CraftingGoal> goals = manager.getGoals(player);
		final StringBuilder sb = new StringBuilder();
		appendSearch(sb, width, bypass);

		sb.append("<table width=").append(width).append("><tr><td><font color=\"").append(COLOR_TITLE).append("\">Crafting Goals</font> <font color=\"").append(COLOR_MUTED).append("\">(").append(goals.size()).append("/").append(CraftingGoalsConfig.MAX_GOALS).append(")</font></td></tr></table>");
		if (goals.isEmpty())
		{
			sb.append("<table width=").append(width).append("><tr><td><font color=\"").append(COLOR_TEXT).append("\">");
			sb.append("Pick an item you want to craft: search for it above or open your recipe book. It becomes a goal you can track like a quest: each resource shows how many you have and how many you need in total, and every resource you collect is celebrated with a firework.");
			sb.append("</font></td></tr></table>");
			return sb.toString();
		}

		for (CraftingGoal goal : goals)
		{
			// Fresh counts, without touching what the tracker celebrates.
			final List<CraftingGoalLine> lines = manager.computeLines(player, goal);
			final int productId = goal.getProductId();
			final int done = CraftingGoal.countCompleteComponents(lines);
			final int total = CraftingGoal.countComponents(lines);
			final boolean ready = CraftingGoal.isReady(lines);
			rowStart(sb, width, productId, ROW_HEIGHT + 8);
			sb.append("<td width=").append(width - 120).append(">");
			sb.append("<font color=\"").append(COLOR_VALUE).append("\">").append(CraftingGoalManager.describe(goal)).append("</font><br1>");
			sb.append(bar(width - 120, done, total, done >= total));
			sb.append("<font color=\"").append(ready ? COLOR_DONE : COLOR_TEXT).append("\">");
			sb.append(ready ? "Ready to craft!" : (done + " of " + total + " resources collected"));
			sb.append("</font></td>");
			sb.append("<td width=80 align=right>").append(button("Track", bypass + " goal " + goal.getRecipeListId(), 70)).append("</td>");
			rowEnd(sb, width);
		}
		return sb.toString();
	}

	/**
	 * One goal: every resource with how many the player has and how many are needed.
	 */
	private static String buildGoal(Player player, int recipeListId, int width, String bypass)
	{
		final CraftingGoalManager manager = CraftingGoalManager.getInstance();
		final CraftingGoal goal = manager.getGoal(player, recipeListId);
		if (goal == null)
		{
			return buildGoals(player, width, bypass);
		}

		final RecipeList recipe = goal.getRecipe();
		final List<CraftingGoalLine> lines = manager.computeLines(player, goal);
		final int done = CraftingGoal.countCompleteComponents(lines);
		final int total = CraftingGoal.countComponents(lines);
		final boolean ready = CraftingGoal.isReady(lines);
		final StringBuilder sb = new StringBuilder();

		// The goal.
		rowStart(sb, width, goal.getProductId(), ROW_HEIGHT + 16);
		sb.append("<td width=").append(width - ICON_CELL).append(">");
		sb.append("<font color=\"").append(COLOR_VALUE).append("\">").append(CraftingGoalManager.describe(goal)).append("</font><br1>");
		appendRecipeInfo(sb, player, recipe);
		sb.append("</td>");
		rowEnd(sb, width);
		sb.append("<table width=").append(width).append("><tr><td>").append(bar(width, done, total, done >= total)).append("</td></tr>");
		sb.append("<tr><td align=center><font color=\"").append(ready ? COLOR_DONE : COLOR_TEXT).append("\">");
		if (ready)
		{
			sb.append(player.hasRecipeList(recipeListId) ? "Ready to craft! Open your recipe book." : "Ready to craft! Take it to a crafter who knows the recipe.");
		}
		else
		{
			sb.append(done).append(" of ").append(total).append(" resources collected");
		}
		sb.append("</font></td></tr></table><br>");

		// The components of the recipe.
		sb.append("<table width=").append(width).append("><tr><td><font color=\"").append(COLOR_TITLE).append("\">Resources</font></td></tr></table>");
		for (CraftingGoalLine line : lines)
		{
			if (!line.isMaterial())
			{
				appendLine(sb, line, width);
			}
		}

		// The materials of the components still missing.
		boolean header = false;
		int hidden = 0;
		for (CraftingGoalLine line : lines)
		{
			if (!line.isMaterial())
			{
				continue;
			}
			if (bypass.equals(WINDOW_BYPASS) && ((sb.length() + LINE_HTML_SIZE) > WINDOW_HTML_BUDGET))
			{
				hidden++;
				continue;
			}
			if (!header)
			{
				header = true;
				sb.append("<br><table width=").append(width).append("><tr><td><font color=\"").append(COLOR_TITLE).append("\">To craft the missing parts</font><br1><font color=\"").append(COLOR_MUTED).append("\">Counts what you carry beyond the resources above.</font></td></tr></table>");
			}
			appendLine(sb, line, width);
		}
		if (hidden > 0)
		{
			sb.append("<table width=").append(width).append("><tr><td align=center><font color=\"").append(COLOR_MUTED).append("\">").append(hidden).append(" more on the Community Board.</font></td></tr></table>");
		}

		sb.append("<br><table width=").append(width).append("><tr>");
		sb.append("<td align=center>").append(button("Back", bypass, 80)).append("</td>");
		sb.append("<td align=center>").append(button("Refresh", bypass + " goal " + recipeListId, 80)).append("</td>");
		sb.append("<td align=center>").append(button("Recipe", bypass + " view " + recipeListId, 80)).append("</td>");
		sb.append("<td align=center>").append(button("Abandon", bypass + " abandon " + recipeListId, 80)).append("</td>");
		sb.append("</tr></table>");
		return sb.toString();
	}

	/**
	 * A recipe: what it makes, its resources for one craft, and where to choose how many items to aim for.
	 */
	private static String buildRecipe(Player player, int recipeListId, String error, int width, String bypass)
	{
		final RecipeList recipe = RecipeData.getInstance().getRecipeList(recipeListId);
		if ((recipe == null) || (recipe.getRecipes() == null))
		{
			return buildGoals(player, width, bypass);
		}

		final CraftingGoal goal = CraftingGoalManager.getInstance().getGoal(player, recipeListId);
		final StringBuilder sb = new StringBuilder();
		rowStart(sb, width, recipe.getItemId(), ROW_HEIGHT + 16);
		sb.append("<td width=").append(width - ICON_CELL).append(">");
		sb.append("<font color=\"").append(COLOR_VALUE).append("\">").append(CraftingGoalManager.getItemName(recipe.getItemId())).append("</font>");
		if (recipe.getCount() > 1)
		{
			sb.append(" <font color=\"").append(COLOR_TEXT).append("\">x").append(recipe.getCount()).append(" per craft</font>");
		}
		sb.append("<br1>");
		appendRecipeInfo(sb, player, recipe);
		sb.append("</td>");
		rowEnd(sb, width);
		sb.append("<br>");

		sb.append("<table width=").append(width).append("><tr><td><font color=\"").append(COLOR_TITLE).append("\">Resources for one craft</font></td></tr></table>");
		for (RecipeHolder ingredient : recipe.getRecipes())
		{
			final long have = player.getInventory().getInventoryItemCount(ingredient.getItemId(), -1, false);
			rowStart(sb, width, ingredient.getItemId(), ROW_HEIGHT);
			sb.append("<td width=").append(width - ICON_CELL - 90).append("><font color=\"").append(COLOR_TEXT).append("\">").append(CraftingGoalManager.getItemName(ingredient.getItemId())).append("</font>");
			if (CraftingGoalManager.getInstance().isCraftable(ingredient.getItemId()))
			{
				sb.append(" <font color=\"").append(COLOR_MUTED).append("\">(craftable)</font>");
			}
			sb.append("</td><td width=90 align=right><font color=\"").append(COLOR_VALUE).append("\">").append(ingredient.getQuantity()).append("</font> <font color=\"").append(COLOR_MUTED).append("\">(").append(have).append(")</font></td>");
			rowEnd(sb, width);
		}
		sb.append("<br>");

		if (error != null)
		{
			sb.append("<table width=").append(width).append("><tr><td align=center><font color=\"").append(COLOR_RED).append("\">").append(error).append("</font></td></tr></table>");
		}

		sb.append("<table width=").append(width).append("><tr>");
		sb.append("<td width=").append(width - 190).append(" align=right><font color=\"").append(COLOR_TEXT).append("\">Items wanted:</font></td>");
		sb.append("<td width=70><edit var=\"amount\" width=60 height=15></td>");
		sb.append("<td width=120>").append(button(goal != null ? "Change Goal" : "Track Goal", bypass + " add " + recipeListId + " $amount", 110)).append("</td>");
		sb.append("</tr></table>");
		sb.append("<table width=").append(width).append("><tr><td align=center><font color=\"").append(COLOR_MUTED).append("\">");
		if (goal != null)
		{
			sb.append("You track this goal: ").append(CraftingGoalManager.describe(goal)).append(".");
		}
		else
		{
			sb.append("Leave it empty for one craft.");
		}
		sb.append("</font></td></tr></table><br>");

		sb.append("<table width=").append(width).append("><tr>");
		sb.append("<td align=center>").append(button("Goals", bypass, 90)).append("</td>");
		sb.append("<td align=center>").append(button("Recipe Book", bypass + " book 1", 90)).append("</td>");
		if (goal != null)
		{
			sb.append("<td align=center>").append(button("Track", bypass + " goal " + recipeListId, 90)).append("</td>");
		}
		sb.append("</tr></table>");
		return sb.toString();
	}

	/**
	 * The recipes whose item holds every word.
	 */
	private static String buildSearch(Player player, String words, int page, int width, String bypass)
	{
		final StringBuilder sb = new StringBuilder();
		appendSearch(sb, width, bypass);
		if (words.trim().length() < 2)
		{
			sb.append("<table width=").append(width).append("><tr><td align=center><font color=\"").append(COLOR_TEXT).append("\">Type at least two letters of the item you want to craft.</font></td></tr></table>");
			return sb.toString();
		}

		final List<RecipeList> found = CraftingGoalManager.getInstance().findRecipes(words);
		sb.append("<table width=").append(width).append("><tr><td><font color=\"").append(COLOR_TITLE).append("\">Recipes for \"").append(escape(words)).append("\"</font> <font color=\"").append(COLOR_MUTED).append("\">(").append(found.size()).append(")</font></td></tr></table>");
		appendRecipes(sb, player, found, page, width, bypass, bypass + " find %page% " + escape(words));
		return sb.toString();
	}

	/**
	 * The recipes the player knows.
	 */
	private static String buildBook(Player player, int page, int width, String bypass)
	{
		final List<RecipeList> known = new ArrayList<>(player.getDwarvenRecipeBook());
		known.addAll(player.getCommonRecipeBook());
		known.sort((a, b) -> CraftingGoalManager.getItemName(a.getItemId()).compareToIgnoreCase(CraftingGoalManager.getItemName(b.getItemId())));

		final StringBuilder sb = new StringBuilder();
		appendSearch(sb, width, bypass);
		sb.append("<table width=").append(width).append("><tr><td><font color=\"").append(COLOR_TITLE).append("\">My Recipe Book</font> <font color=\"").append(COLOR_MUTED).append("\">(").append(known.size()).append(")</font></td></tr></table>");
		if (known.isEmpty())
		{
			sb.append("<table width=").append(width).append("><tr><td align=center><font color=\"").append(COLOR_TEXT).append("\">You know no recipe yet. Search for the item instead: a dwarf can craft it for you in their workshop.</font></td></tr></table>");
			return sb.toString();
		}
		appendRecipes(sb, player, known, page, width, bypass, bypass + " book %page%");
		return sb.toString();
	}

	// ---------------------------------------------------------------------------------------------
	// Parts
	// ---------------------------------------------------------------------------------------------

	private static void appendSearch(StringBuilder sb, int width, String bypass)
	{
		sb.append("<table width=").append(width).append("><tr>");
		sb.append("<td width=").append(width - 170).append("><edit var=\"query\" width=").append(width - 180).append(" height=15></td>");
		sb.append("<td width=80>").append(button("Search", bypass + " find 1 $query", 75)).append("</td>");
		sb.append("<td width=90>").append(button("My Recipes", bypass + " book 1", 85)).append("</td>");
		sb.append("</tr></table><br>");
	}

	/**
	 * A page of recipes, each with its success rate and a button to its page.
	 */
	private static void appendRecipes(StringBuilder sb, Player player, List<RecipeList> recipes, int page, int width, String bypass, String pageBypass)
	{
		final int perPage = bypass.equals(BOARD_BYPASS) ? BOARD_RESULTS : WINDOW_RESULTS;
		final int pages = Math.max(1, (recipes.size() + perPage - 1) / perPage);
		final int current = Math.max(1, Math.min(page, pages));
		if (recipes.isEmpty())
		{
			sb.append("<table width=").append(width).append("><tr><td align=center><font color=\"").append(COLOR_TEXT).append("\">No recipe makes such an item.</font></td></tr></table>");
			return;
		}

		for (int i = (current - 1) * perPage; i < Math.min(recipes.size(), current * perPage); i++)
		{
			final RecipeList recipe = recipes.get(i);
			final CraftingGoal goal = CraftingGoalManager.getInstance().getGoal(player, recipe.getId());
			rowStart(sb, width, recipe.getItemId(), ROW_HEIGHT);
			sb.append("<td width=").append(width - ICON_CELL - 80).append("><font color=\"").append(goal != null ? COLOR_DONE : COLOR_VALUE).append("\">").append(CraftingGoalManager.getItemName(recipe.getItemId())).append("</font>");
			if (recipe.getCount() > 1)
			{
				sb.append(" <font color=\"").append(COLOR_TEXT).append("\">x").append(recipe.getCount()).append("</font>");
			}
			sb.append("<br1><font color=\"").append(COLOR_MUTED).append("\">").append(recipe.getSuccessRate()).append("%, ").append(recipe.isDwarvenRecipe() ? "dwarven" : "common").append(" level ").append(recipe.getLevel());
			if (player.hasRecipeList(recipe.getId()))
			{
				sb.append(", known");
			}
			sb.append("</font></td>");
			sb.append("<td width=80 align=right>").append(button(goal != null ? "Tracked" : "Choose", bypass + " view " + recipe.getId(), 70)).append("</td>");
			rowEnd(sb, width);
		}

		if (pages > 1)
		{
			sb.append("<table width=").append(width).append("><tr>");
			sb.append("<td width=100 align=left>").append(current > 1 ? button("Previous", pageBypass.replace("%page%", String.valueOf(current - 1)), 80) : "").append("</td>");
			sb.append("<td width=").append(width - 200).append(" align=center><font color=\"").append(COLOR_MUTED).append("\">Page ").append(current).append(" of ").append(pages).append("</font></td>");
			sb.append("<td width=100 align=right>").append(current < pages ? button("Next", pageBypass.replace("%page%", String.valueOf(current + 1)), 80) : "").append("</td>");
			sb.append("</tr></table>");
		}
	}

	/**
	 * "100%, dwarven level 5, you know it" - the success rate, the craft and whether the player knows the recipe.
	 */
	private static void appendRecipeInfo(StringBuilder sb, Player player, RecipeList recipe)
	{
		sb.append("<font color=\"").append(COLOR_MUTED).append("\">");
		if (recipe == null)
		{
			sb.append("The recipe is gone.</font>");
			return;
		}

		sb.append(recipe.getSuccessRate()).append("% success, ").append(recipe.isDwarvenRecipe() ? "Dwarven" : "Common").append(" Craft level ").append(recipe.getLevel()).append("<br1>");
		sb.append(player.hasRecipeList(recipe.getId()) ? "You know this recipe." : ("Recipe: " + CraftingGoalManager.getItemName(recipe.getRecipeId()) + " (not learned)"));
		sb.append("</font>");
	}

	/**
	 * A resource: its icon and name, a bar and "have / needed".
	 */
	private static void appendLine(StringBuilder sb, CraftingGoalLine line, int width)
	{
		final boolean complete = line.isComplete();
		rowStart(sb, width, line.getItemId(), ROW_HEIGHT);
		sb.append("<td width=").append(width - ICON_CELL).append(">");
		sb.append("<table width=").append(width - ICON_CELL - 4).append(" cellpadding=0 cellspacing=0><tr>");
		sb.append("<td><font color=\"").append(complete ? COLOR_DONE : COLOR_TEXT).append("\">").append(CraftingGoalManager.getItemName(line.getItemId())).append("</font>");
		if (line.isCraftable() && !complete)
		{
			sb.append(" <font color=\"").append(COLOR_MUTED).append("\">(craftable)</font>");
		}
		sb.append("</td><td width=110 align=right><font color=\"").append(complete ? COLOR_DONE : COLOR_VALUE).append("\">").append(line.getHave()).append(" / ").append(line.getNeeded()).append("</font></td>");
		sb.append("</tr></table>");
		sb.append(bar(width - ICON_CELL - 4, line.getHave(), line.getNeeded(), complete));
		sb.append("</td>");
		rowEnd(sb, width);
	}

	/**
	 * A progress bar, gold once complete.
	 */
	private static String bar(int width, long current, long max, boolean complete)
	{
		final int filled = max <= 0 ? width : (int) ((Math.min(current, max) * width) / max);
		final StringBuilder sb = new StringBuilder();
		sb.append("<table width=").append(width).append(" cellpadding=0 cellspacing=0><tr><td background=\"").append(complete ? BAR_DONE_BACK : BAR_BACK).append("\">");
		if (filled > 0)
		{
			sb.append("<img src=\"").append(complete ? BAR_DONE_FILL : BAR_FILL).append("\" width=").append(filled).append(" height=10>");
		}
		else
		{
			sb.append("<img src=\"L2UI.SquareBlank\" width=1 height=10>");
		}
		sb.append("</td></tr></table>");
		return sb.toString();
	}

	/**
	 * Opens a row with an item icon. The row has a fixed height so the 32x32 icon never runs into the next row.
	 */
	private static void rowStart(StringBuilder sb, int width, int itemId, int height)
	{
		sb.append("<table width=").append(width).append(" height=").append(height).append(" cellpadding=0 cellspacing=0><tr>");
		sb.append("<td width=").append(ICON_CELL).append(" height=").append(height).append(" valign=center>").append(icon(itemId)).append("</td>");
	}

	/**
	 * Closes a row opened by {@link #rowStart} and draws a thin line under it.
	 */
	private static void rowEnd(StringBuilder sb, int width)
	{
		sb.append("</tr></table><img src=\"L2UI.SquareGray\" width=").append(width).append(" height=1>");
	}

	private static String icon(int itemId)
	{
		final ItemTemplate template = ItemData.getInstance().getTemplate(itemId);
		final String icon = (template != null) && (template.getIcon() != null) ? template.getIcon() : NO_ICON;
		return "<img src=\"" + icon + "\" width=32 height=32>";
	}

	private static String button(String text, String action, int width)
	{
		return "<button value=\"" + text + "\" action=\"bypass " + action + "\" width=" + width + " height=22 back=\"L2UI_CT1.Button_DF_Down\" fore=\"L2UI_CT1.Button_DF\">";
	}

	private static String escape(String text)
	{
		return text.replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
	}
}
