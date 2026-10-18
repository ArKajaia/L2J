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
package org.l2jmobius.gameserver.managers;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.ToLongFunction;
import java.util.logging.Logger;

import org.l2jmobius.commons.threads.ThreadPool;
import org.l2jmobius.gameserver.config.custom.CraftingGoalsConfig;
import org.l2jmobius.gameserver.data.holders.RecipeHolder;
import org.l2jmobius.gameserver.data.xml.ItemData;
import org.l2jmobius.gameserver.data.xml.RecipeData;
import org.l2jmobius.gameserver.handler.IVoicedCommandHandler;
import org.l2jmobius.gameserver.handler.VoicedCommandHandler;
import org.l2jmobius.gameserver.model.actor.Player;
import org.l2jmobius.gameserver.model.craftinggoal.CraftingGoal;
import org.l2jmobius.gameserver.model.craftinggoal.CraftingGoalLine;
import org.l2jmobius.gameserver.model.craftinggoal.CraftingGoalNode;
import org.l2jmobius.gameserver.model.events.Containers;
import org.l2jmobius.gameserver.model.events.EventType;
import org.l2jmobius.gameserver.model.events.holders.actor.player.OnPlayerLogin;
import org.l2jmobius.gameserver.model.events.holders.actor.player.OnPlayerLogout;
import org.l2jmobius.gameserver.model.events.holders.actor.player.OnPlayerPressTutorialMark;
import org.l2jmobius.gameserver.model.events.listeners.ConsumerEventListener;
import org.l2jmobius.gameserver.model.item.ItemTemplate;
import org.l2jmobius.gameserver.model.item.recipe.RecipeList;
import org.l2jmobius.gameserver.model.script.QuestSound;
import org.l2jmobius.gameserver.network.serverpackets.Ex2CraftingGoalInfo;
import org.l2jmobius.gameserver.network.serverpackets.Ex2CraftingGoalRemove;
import org.l2jmobius.gameserver.network.serverpackets.Ex2CraftingGoalUpdate;
import org.l2jmobius.gameserver.network.serverpackets.ExShowScreenMessage;
import org.l2jmobius.gameserver.network.serverpackets.MagicSkillUse;
import org.l2jmobius.gameserver.network.serverpackets.ServerPacket;
import org.l2jmobius.gameserver.network.serverpackets.TutorialShowQuestionMark;

/**
 * Crafting goals: a player picks an item to craft and tracks it like a quest.
 * <ul>
 * <li><b>Resources</b> - every ingredient of the recipe is a line with how many the player has and how many the crafts need in total. Ingredients that are crafted themselves (Crafted Leather, Mithril Alloy...) are broken down into their own materials for the part still missing, a few
 * recipe levels deep.</li>
 * <li><b>Tracking</b> - every change of the inventory ends in {@code PlayerInventory.refreshWeight()}, which calls {@link #onInventoryChanged(Player)}. The goals are checked a moment later, once for a batch of changes (a sweep, a craft, a trade).</li>
 * <li><b>Celebration</b> - a resource gathered shows its count on screen; one collected sets off a small Firework (once per resource and craft, so dropping and picking it up again sets off nothing), and having every component a Large Firework.</li>
 * <li><b>Completion</b> - crafting the item (yourself or in a dwarf's workshop) counts a craft; the last one completes the goal.</li>
 * <li><b>Pinning</b> - a goal pinned to the screen is drawn by the client's own tracker: it gets the recipe tree and every line ({@link Ex2CraftingGoalInfo}), then only the lines that change or are gone after each check ({@link Ex2CraftingGoalUpdate}), and {@link Ex2CraftingGoalRemove} when it leaves the screen. The client's
 * tracker unpins with the bypass {@code _craftpin unpin <recipe>} ({@link #onPinBypass(Player, String)}).</li>
 * </ul>
 * Goals are kept in the player variable {@code CRAFT_GOALS}. The tracker window itself is the {@code .craft} voiced command ({@code _bbscraft} on the Community Board).
 */
public class CraftingGoalManager
{
	private static final Logger LOGGER = Logger.getLogger(CraftingGoalManager.class.getName());

	/** The tutorial question mark raised when a resource is collected; clicking it opens the tracker. */
	public static final int QUESTION_MARK_ID = 9310;

	private static final String VAR_GOALS = "CRAFT_GOALS";
	private static final String VOICED_COMMAND = "craft";

	private static final int FIREWORK = 2024;
	private static final int LARGE_FIREWORK = 2025;

	/** Changes within this time are checked together. */
	private static final long CHECK_DELAY = 300;
	private static final int PROGRESS_MESSAGE_TIME = 3000;
	private static final int CELEBRATION_MESSAGE_TIME = 5000;
	/** How many gathered resources one progress message names. */
	private static final int PROGRESS_MESSAGE_LINES = 2;
	/** The most nodes of a recipe tree sent to the client. */
	private static final int MAX_TREE_NODES = 256;

	private final Map<Integer, Tracker> _trackers = new ConcurrentHashMap<>();
	/** The recipe used to break an item down into its materials, by the item it makes. */
	private final Map<Integer, RecipeList> _partRecipes = new HashMap<>();

	/** The goals of a player online. */
	private static class Tracker
	{
		final List<CraftingGoal> _goals = new CopyOnWriteArrayList<>();
		final AtomicBoolean _checkPending = new AtomicBoolean();
	}

	protected CraftingGoalManager()
	{
		if (!CraftingGoalsConfig.ENABLED)
		{
			return;
		}

		loadPartRecipes();

		Containers.Players().addListener(new ConsumerEventListener(Containers.Players(), EventType.ON_PLAYER_LOGIN, (OnPlayerLogin event) -> onLogin(event.getPlayer()), this));
		Containers.Players().addListener(new ConsumerEventListener(Containers.Players(), EventType.ON_PLAYER_LOGOUT, (OnPlayerLogout event) -> _trackers.remove(event.getPlayer().getObjectId()), this));
		Containers.Players().addListener(new ConsumerEventListener(Containers.Players(), EventType.ON_PLAYER_PRESS_TUTORIAL_MARK, (OnPlayerPressTutorialMark event) -> onQuestionMark(event.getPlayer(), event.getMarkId()), this));
		LOGGER.info(getClass().getSimpleName() + ": Crafting goals are enabled (" + _partRecipes.size() + " craftable items).");
	}

	/**
	 * Picks one recipe per item to break it down with: the surest one, then the lowest level.
	 */
	private void loadPartRecipes()
	{
		for (RecipeList recipe : RecipeData.getInstance().getRecipes())
		{
			if ((recipe.getRecipes() == null) || (recipe.getRecipes().length == 0))
			{
				continue;
			}

			final RecipeList known = _partRecipes.get(recipe.getItemId());
			if ((known == null) || (recipe.getSuccessRate() > known.getSuccessRate()) || ((recipe.getSuccessRate() == known.getSuccessRate()) && (recipe.getLevel() < known.getLevel())))
			{
				_partRecipes.put(recipe.getItemId(), recipe);
			}
		}
	}

	// ---------------------------------------------------------------------------------------------
	// Goals
	// ---------------------------------------------------------------------------------------------

	/**
	 * @param player the player
	 * @return the player's goals, in the order they were taken
	 */
	public List<CraftingGoal> getGoals(Player player)
	{
		final Tracker tracker = _trackers.get(player.getObjectId());
		return tracker != null ? tracker._goals : Collections.emptyList();
	}

	/**
	 * @param player the player
	 * @param recipeListId the recipe
	 * @return the player's goal for this recipe, or {@code null}
	 */
	public CraftingGoal getGoal(Player player, int recipeListId)
	{
		for (CraftingGoal goal : getGoals(player))
		{
			if (goal.getRecipeListId() == recipeListId)
			{
				return goal;
			}
		}
		return null;
	}

	/**
	 * Takes a goal, or changes how many items an existing goal for the recipe asks for.
	 * @param player the player
	 * @param recipeListId the recipe
	 * @param amount how many items the player wants
	 * @return {@code null} if it worked, else why not
	 */
	public String addGoal(Player player, int recipeListId, long amount)
	{
		if (!CraftingGoalsConfig.ENABLED)
		{
			return "Crafting goals are disabled.";
		}

		final RecipeList recipe = RecipeData.getInstance().getRecipeList(recipeListId);
		if ((recipe == null) || (recipe.getRecipes() == null) || (recipe.getRecipes().length == 0))
		{
			return "There is no such recipe.";
		}
		if ((amount < 1) || (amount > CraftingGoalsConfig.MAX_AMOUNT))
		{
			return "You can aim for 1 to " + CraftingGoalsConfig.MAX_AMOUNT + " items.";
		}

		final Tracker tracker = _trackers.computeIfAbsent(player.getObjectId(), id -> new Tracker());
		final int perCraft = Math.max(1, recipe.getCount());
		final int crafts = (int) ((amount + perCraft - 1) / perCraft);
		final String name = getItemName(recipe.getItemId());

		final CraftingGoal existing = getGoal(player, recipeListId);
		if (existing != null)
		{
			synchronized (existing)
			{
				existing.setCrafts(crafts);
				resetCelebrations(existing);
			}
			check(player, existing, false);
			save(player);
			player.sendMessage("Crafting goal updated: " + describe(existing) + ".");
			return null;
		}

		if (tracker._goals.size() >= CraftingGoalsConfig.MAX_GOALS)
		{
			return "You can track " + CraftingGoalsConfig.MAX_GOALS + " crafting goals at most. Abandon one first.";
		}

		final CraftingGoal goal = new CraftingGoal(recipeListId, crafts);
		check(player, goal, false);
		final boolean ready = goal.isReady();
		if (ready)
		{
			// Everything is already in the bag: nothing to celebrate.
			goal.setReadyCelebrated(true);
		}
		tracker._goals.add(goal);
		save(player);

		player.sendPacket(QuestSound.ITEMSOUND_QUEST_ACCEPT.getPacket());
		player.sendPacket(new ExShowScreenMessage("New crafting goal: " + name, ExShowScreenMessage.TOP_CENTER, CELEBRATION_MESSAGE_TIME, 0, true, true));
		player.sendMessage("Crafting goal accepted: " + describe(goal) + ". Type .craft to see your progress.");
		if (ready)
		{
			player.sendMessage("You already have everything you need to craft " + name + ".");
		}
		return null;
	}

	/**
	 * Abandons a goal.
	 * @param player the player
	 * @param recipeListId the recipe of the goal
	 * @return {@code true} if the player had that goal
	 */
	public boolean removeGoal(Player player, int recipeListId)
	{
		final CraftingGoal goal = getGoal(player, recipeListId);
		if (goal == null)
		{
			return false;
		}

		getGoals(player).remove(goal);
		takeOffScreen(player, goal, Ex2CraftingGoalRemove.ABANDONED);
		save(player);
		player.sendPacket(QuestSound.ITEMSOUND_QUEST_GIVEUP.getPacket());
		player.sendMessage("Crafting goal abandoned: " + getItemName(goal.getProductId()) + ".");
		return true;
	}

	/**
	 * Called by {@code PlayerInventory.refreshWeight()} on every change of a player's inventory. Checks the player's goals a moment later.
	 * @param player the player
	 */
	public void onInventoryChanged(Player player)
	{
		final Tracker tracker = _trackers.get(player.getObjectId());
		if ((tracker == null) || tracker._goals.isEmpty() || !tracker._checkPending.compareAndSet(false, true))
		{
			return;
		}

		ThreadPool.schedule(() ->
		{
			tracker._checkPending.set(false);
			if (player.isOnline())
			{
				checkAll(player, true);
			}
		}, CHECK_DELAY);
	}

	/**
	 * Called by the {@code RecipeManager} when items are crafted for a player (by themselves or in a workshop). Counts the crafts of the goal that makes this item; the last one completes the goal.
	 * @param player the player who gets the item
	 * @param recipe the recipe crafted
	 * @param crafts the number of successful crafts
	 */
	public void onCrafted(Player player, RecipeList recipe, int crafts)
	{
		for (CraftingGoal goal : getGoals(player))
		{
			if (goal.getProductId() != recipe.getItemId())
			{
				continue;
			}

			final String name = getItemName(goal.getProductId());
			final int left;
			synchronized (goal)
			{
				left = goal.getCrafts() - crafts;
				goal.setCrafts(left);
				resetCelebrations(goal);
			}

			if (left <= 0)
			{
				getGoals(player).remove(goal);
				takeOffScreen(player, goal, Ex2CraftingGoalRemove.COMPLETED);
				player.sendPacket(QuestSound.ITEMSOUND_QUEST_FINISH.getPacket());
				player.sendPacket(new ExShowScreenMessage("Crafting goal complete: " + name + "!", ExShowScreenMessage.TOP_CENTER, CELEBRATION_MESSAGE_TIME, 0, true, true));
				player.sendMessage("Crafting goal complete: " + name + ".");
				firework(player, LARGE_FIREWORK);
			}
			else
			{
				// The materials of this craft are gone: start counting the next one from here.
				check(player, goal, false);
				player.sendPacket(QuestSound.ITEMSOUND_QUEST_MIDDLE.getPacket());
				player.sendMessage("Crafting goal: " + name + " crafted, " + describe(goal) + " to go.");
			}
			save(player);
			return;
		}
	}

	/**
	 * Called by the {@code RecipeManager} when a craft for a player fails: its materials are gone, so gathering them again is celebrated again.
	 * @param player the player the item was crafted for
	 * @param recipe the recipe tried
	 */
	public void onCraftFailed(Player player, RecipeList recipe)
	{
		for (CraftingGoal goal : getGoals(player))
		{
			if (goal.getProductId() == recipe.getItemId())
			{
				synchronized (goal)
				{
					resetCelebrations(goal);
				}
				check(player, goal, false);
				save(player);
				player.sendMessage("Crafting goal: the craft failed, " + describe(goal) + " still to make.");
				return;
			}
		}
	}

	private void resetCelebrations(CraftingGoal goal)
	{
		goal.getCelebrated().clear();
		goal.setReadyCelebrated(false);
	}

	// ---------------------------------------------------------------------------------------------
	// Pinning
	// ---------------------------------------------------------------------------------------------

	/**
	 * Pins a goal to the client's screen, or takes it off. Pinning a pinned goal sends it again.
	 * @param player the player
	 * @param recipeListId the recipe of the goal
	 * @param pinned {@code true} to pin, {@code false} to unpin
	 * @return {@code true} if the player has that goal
	 */
	public boolean pin(Player player, int recipeListId, boolean pinned)
	{
		final CraftingGoal goal = getGoal(player, recipeListId);
		if (goal == null)
		{
			return false;
		}

		if (pinned)
		{
			// Fresh lines first: the updates that follow are worked out from the ones sent here.
			check(player, goal, false);
			synchronized (goal)
			{
				goal.setPinned(true);
				sendInfo(player, goal);
			}
			player.sendMessage("Crafting goal pinned to your screen: " + getItemName(goal.getProductId()) + ".");
		}
		else
		{
			if (!takeOffScreen(player, goal, Ex2CraftingGoalRemove.UNPINNED))
			{
				return true;
			}
			player.sendMessage("Crafting goal unpinned: " + getItemName(goal.getProductId()) + ".");
		}
		save(player);
		return true;
	}

	/**
	 * The bypass {@code _craftpin} the client's tracker sends: {@code pin <recipe>}, {@code unpin <recipe>}, or {@code sync <recipe>} to get a pinned goal again (one the player no longer has, or that is not pinned, is taken off the screen). Shows no window.
	 * @param player the player
	 * @param args what follows {@code _craftpin}
	 */
	public void onPinBypass(Player player, String args)
	{
		final String[] parts = args.trim().split("\\s+");
		if (!CraftingGoalsConfig.ENABLED || (parts.length < 2))
		{
			return;
		}

		final int recipeListId;
		try
		{
			recipeListId = Integer.parseInt(parts[1]);
		}
		catch (NumberFormatException e)
		{
			return;
		}

		switch (parts[0].toLowerCase(Locale.ROOT))
		{
			case "pin":
			{
				pin(player, recipeListId, true);
				break;
			}
			case "unpin":
			{
				if (!pin(player, recipeListId, false))
				{
					player.sendPacket(new Ex2CraftingGoalRemove(recipeListId, Ex2CraftingGoalRemove.ABANDONED));
				}
				break;
			}
			case "sync":
			{
				final CraftingGoal goal = getGoal(player, recipeListId);
				if (goal == null)
				{
					player.sendPacket(new Ex2CraftingGoalRemove(recipeListId, Ex2CraftingGoalRemove.ABANDONED));
					break;
				}
				synchronized (goal)
				{
					if (goal.isPinned())
					{
						sendInfo(player, goal);
					}
					else
					{
						player.sendPacket(new Ex2CraftingGoalRemove(recipeListId, Ex2CraftingGoalRemove.UNPINNED));
					}
				}
				break;
			}
		}
	}

	/**
	 * Sends a pinned goal with its lines as of its last check. Called holding the goal's lock, so no update goes out between reading the lines here and the client getting them.
	 */
	private void sendInfo(Player player, CraftingGoal goal)
	{
		player.sendPacket(new Ex2CraftingGoalInfo(goal, player.hasRecipeList(goal.getRecipeListId()), buildTree(goal.getRecipe())));
	}

	/**
	 * Unpins a goal and tells the client, if it was pinned.
	 * @return {@code true} if the goal was pinned
	 */
	private boolean takeOffScreen(Player player, CraftingGoal goal, int reason)
	{
		synchronized (goal)
		{
			if (!goal.isPinned())
			{
				return false;
			}
			goal.setPinned(false);
			player.sendPacket(new Ex2CraftingGoalRemove(goal.getRecipeListId(), reason));
			return true;
		}
	}

	/**
	 * The recipe tree the client's tracker draws: every ingredient of the recipe, then the ingredients of the ones that are crafted, as deep as the breakdown goes. Depth first, so a node's children follow it.
	 * @param recipe the goal's recipe
	 * @return the nodes, at most {@link #MAX_TREE_NODES}
	 */
	public List<CraftingGoalNode> buildTree(RecipeList recipe)
	{
		final List<CraftingGoalNode> tree = new ArrayList<>();
		if ((recipe != null) && (recipe.getRecipes() != null))
		{
			final Set<Integer> path = new HashSet<>();
			path.add(recipe.getItemId());
			addNodes(tree, recipe, -1, 0, path);
		}
		return tree;
	}

	/**
	 * @param path the items on the way down from the goal, so a recipe that loops back is not broken down again
	 */
	private void addNodes(List<CraftingGoalNode> tree, RecipeList recipe, int parentIndex, int depth, Set<Integer> path)
	{
		for (RecipeHolder ingredient : recipe.getRecipes())
		{
			if (tree.size() >= MAX_TREE_NODES)
			{
				return;
			}

			final int itemId = ingredient.getItemId();
			final RecipeList part = CraftingGoalsConfig.BREAKDOWN && (depth < CraftingGoalsConfig.BREAKDOWN_DEPTH) && !path.contains(itemId) ? _partRecipes.get(itemId) : null;
			final int index = tree.size();
			tree.add(new CraftingGoalNode(parentIndex, itemId, ingredient.getQuantity(), part != null ? Math.max(1, part.getCount()) : 0));
			if (part != null)
			{
				path.add(itemId);
				addNodes(tree, part, index, depth + 1, path);
				path.remove(itemId);
			}
		}
	}

	/**
	 * @param goal a pinned goal, its lines as the client has them
	 * @param lines the lines now
	 * @return the update with the lines that are new, changed or gone, {@code null} if nothing changed
	 */
	private static Ex2CraftingGoalUpdate diff(CraftingGoal goal, List<CraftingGoalLine> lines)
	{
		final Map<Integer, CraftingGoalLine> before = new LinkedHashMap<>();
		for (CraftingGoalLine line : goal.getLines())
		{
			before.put(line.getKey(), line);
		}

		final List<CraftingGoalLine> changed = new ArrayList<>();
		for (CraftingGoalLine line : lines)
		{
			final CraftingGoalLine old = before.remove(line.getKey());
			if ((old == null) || (old.getHave() != line.getHave()) || (old.getNeeded() != line.getNeeded()) || (old.isCraftable() != line.isCraftable()))
			{
				changed.add(line);
			}
		}

		if (changed.isEmpty() && before.isEmpty())
		{
			return null;
		}
		return new Ex2CraftingGoalUpdate(goal.getRecipeListId(), goal.getCrafts(), lines, changed, new ArrayList<>(before.keySet()));
	}

	// ---------------------------------------------------------------------------------------------
	// Progress
	// ---------------------------------------------------------------------------------------------

	/**
	 * Checks every goal of the player.
	 * @param player the player
	 * @param announce {@code true} to show the progress and celebrate, {@code false} to only take note of where the player stands
	 */
	public void checkAll(Player player, boolean announce)
	{
		for (CraftingGoal goal : getGoals(player))
		{
			check(player, goal, announce);
		}
	}

	/**
	 * Works out the resources of a goal and compares them with the last check.
	 * @param player the player
	 * @param goal the goal
	 * @param announce {@code true} to show the progress and celebrate what changed
	 */
	private void check(Player player, CraftingGoal goal, boolean announce)
	{
		final List<String> progress = new ArrayList<>();
		final List<String> collected = new ArrayList<>();
		boolean celebrateReady = false;
		boolean changed = false;

		synchronized (goal)
		{
			final List<CraftingGoalLine> lines = computeLines(player, goal);
			if (goal.isPinned())
			{
				// Sent holding the lock, so the updates of two checks reach the client in order.
				final Ex2CraftingGoalUpdate update = diff(goal, lines);
				if (update != null)
				{
					player.sendPacket(update);
				}
			}

			final Set<Integer> complete = new HashSet<>();
			final Map<Integer, Long> have = new HashMap<>();
			for (CraftingGoalLine line : lines)
			{
				final int key = line.getKey();
				have.put(key, line.getHave());
				if (line.isComplete())
				{
					complete.add(key);
					if (announce && !goal.getComplete().contains(key) && goal.getCelebrated().add(key))
					{
						collected.add(getItemName(line.getItemId()));
						changed = true;
					}
				}
				else if (announce && (line.getHave() > goal.getLastHave().getOrDefault(key, Long.MAX_VALUE)))
				{
					progress.add(getItemName(line.getItemId()) + " " + line.getHave() + "/" + line.getNeeded());
				}
			}

			goal.setLines(lines);
			goal.getComplete().clear();
			goal.getComplete().addAll(complete);
			goal.getLastHave().clear();
			goal.getLastHave().putAll(have);

			if (announce && goal.isReady() && !goal.isReadyCelebrated())
			{
				goal.setReadyCelebrated(true);
				celebrateReady = true;
				changed = true;
			}
		}

		if (!announce)
		{
			return;
		}

		final String name = getItemName(goal.getProductId());
		if (celebrateReady)
		{
			player.sendPacket(QuestSound.ITEMSOUND_QUEST_FINISH.getPacket());
			player.sendPacket(new ExShowScreenMessage("Everything is ready to craft " + name + "!", ExShowScreenMessage.TOP_CENTER, CELEBRATION_MESSAGE_TIME, 0, true, true));
			player.sendMessage("Crafting goal: you have everything to craft " + describe(goal) + ". " + (player.hasRecipeList(goal.getRecipeListId()) ? "Open your recipe book to craft it." : "Take it to a crafter who knows the recipe."));
			firework(player, LARGE_FIREWORK);
		}
		else if (!collected.isEmpty())
		{
			final String what = String.join(", ", collected);
			player.sendPacket(QuestSound.ITEMSOUND_QUEST_MIDDLE.getPacket());
			player.sendPacket(new ExShowScreenMessage(what + " collected! (" + name + ": " + goal.getCompleteComponents() + "/" + goal.getComponentCount() + ")", ExShowScreenMessage.TOP_CENTER, CELEBRATION_MESSAGE_TIME, 0, true, true));
			player.sendMessage("Crafting goal " + name + ": " + what + " collected.");
			firework(player, FIREWORK);
		}
		else if (!progress.isEmpty() && CraftingGoalsConfig.PROGRESS_MESSAGES)
		{
			player.sendPacket(QuestSound.ITEMSOUND_QUEST_ITEMGET.getPacket());
			player.sendPacket(new ExShowScreenMessage(String.join(", ", progress.subList(0, Math.min(PROGRESS_MESSAGE_LINES, progress.size()))), ExShowScreenMessage.TOP_CENTER, PROGRESS_MESSAGE_TIME, 1, true, false));
		}

		if (changed)
		{
			if (CraftingGoalsConfig.QUESTION_MARK)
			{
				player.sendPacket(new TutorialShowQuestionMark(QUESTION_MARK_ID));
			}
			save(player);
		}
	}

	/**
	 * The resources of a goal:
	 * <ol>
	 * <li>the components: each ingredient of the recipe times the crafts still to make, against everything the player carries;</li>
	 * <li>the materials: the components still missing that can be crafted are broken down into their ingredients, level by level. A material only counts what the player carries beyond the components, and what an earlier level used up.</li>
	 * </ol>
	 * @param player the player
	 * @param goal the goal
	 * @return the lines, components first
	 */
	public List<CraftingGoalLine> computeLines(Player player, CraftingGoal goal)
	{
		final Map<Integer, Long> carried = new HashMap<>();
		return computeLines(goal, itemId -> carried.computeIfAbsent(itemId, id -> player.getInventory().getInventoryItemCount(id, -1, false)));
	}
	
	/**
	 * @param goal the goal
	 * @param carried how many of an item the player carries, not counting what is worn
	 * @return the lines, components first
	 * @see #computeLines(Player, CraftingGoal)
	 */
	public List<CraftingGoalLine> computeLines(CraftingGoal goal, ToLongFunction<Integer> carried)
	{
		final RecipeList recipe = goal.getRecipe();
		if ((recipe == null) || (goal.getCrafts() <= 0))
		{
			return Collections.emptyList();
		}

		final List<CraftingGoalLine> lines = new ArrayList<>();
		final Map<Integer, Long> components = new LinkedHashMap<>();
		for (RecipeHolder ingredient : recipe.getRecipes())
		{
			components.merge(ingredient.getItemId(), (long) ingredient.getQuantity() * goal.getCrafts(), Long::sum);
		}

		final Map<Integer, Long> missing = new LinkedHashMap<>();
		for (Entry<Integer, Long> entry : components.entrySet())
		{
			final int itemId = entry.getKey();
			final long needed = entry.getValue();
			final long have = carried.applyAsLong(itemId);
			lines.add(new CraftingGoalLine(itemId, needed, have, false, _partRecipes.containsKey(itemId)));
			if ((have < needed) && _partRecipes.containsKey(itemId))
			{
				missing.put(itemId, needed - have);
			}
		}

		if (!CraftingGoalsConfig.BREAKDOWN || missing.isEmpty())
		{
			return lines;
		}

		// What is left for materials once the components are counted.
		final Map<Integer, Long> spare = new HashMap<>();
		final Map<Integer, Long> spareAtStart = new HashMap<>();
		final Map<Integer, Long> materials = new LinkedHashMap<>();
		final Set<Integer> brokenDown = new HashSet<>();
		brokenDown.add(recipe.getItemId());

		Map<Integer, Long> toBreak = missing;
		for (int depth = 0; (depth < CraftingGoalsConfig.BREAKDOWN_DEPTH) && !toBreak.isEmpty(); depth++)
		{
			final Map<Integer, Long> level = new LinkedHashMap<>();
			for (Entry<Integer, Long> entry : toBreak.entrySet())
			{
				final RecipeList part = _partRecipes.get(entry.getKey());
				if ((part == null) || !brokenDown.add(entry.getKey()))
				{
					continue;
				}

				final int perCraft = Math.max(1, part.getCount());
				final long crafts = (entry.getValue() + perCraft - 1) / perCraft;
				for (RecipeHolder ingredient : part.getRecipes())
				{
					level.merge(ingredient.getItemId(), ingredient.getQuantity() * crafts, Long::sum);
				}
			}

			final Map<Integer, Long> next = new LinkedHashMap<>();
			for (Entry<Integer, Long> entry : level.entrySet())
			{
				final int itemId = entry.getKey();
				final long needed = entry.getValue();
				final long available = spare.computeIfAbsent(itemId, id ->
				{
					final long left = Math.max(0, carried.applyAsLong(id) - components.getOrDefault(id, 0L));
					spareAtStart.put(id, left);
					return left;
				});
				final long used = Math.min(available, needed);
				spare.put(itemId, available - used);
				materials.merge(itemId, needed, Long::sum);
				if ((used < needed) && _partRecipes.containsKey(itemId))
				{
					next.merge(itemId, needed - used, Long::sum);
				}
			}
			toBreak = next;
		}

		for (Entry<Integer, Long> entry : materials.entrySet())
		{
			final int itemId = entry.getKey();
			lines.add(new CraftingGoalLine(itemId, entry.getValue(), spareAtStart.getOrDefault(itemId, 0L), true, _partRecipes.containsKey(itemId)));
		}
		return lines;
	}

	// ---------------------------------------------------------------------------------------------
	// Recipes
	// ---------------------------------------------------------------------------------------------

	/**
	 * @param itemId an item
	 * @return {@code true} if a recipe makes the item
	 */
	public boolean isCraftable(int itemId)
	{
		return _partRecipes.containsKey(itemId);
	}

	/**
	 * Finds the recipes whose item (or recipe) name holds every word of the text.
	 * @param text the words to look for
	 * @return the recipes found, by item name
	 */
	public List<RecipeList> findRecipes(String text)
	{
		final String[] words = text.toLowerCase(Locale.ROOT).trim().split("\\s+");
		final List<RecipeList> found = new ArrayList<>();
		for (RecipeList recipe : RecipeData.getInstance().getRecipes())
		{
			if ((recipe.getRecipes() == null) || (recipe.getRecipes().length == 0))
			{
				continue;
			}

			final String name = (getItemName(recipe.getItemId()) + " " + getItemName(recipe.getRecipeId())).toLowerCase(Locale.ROOT);
			boolean match = true;
			for (String word : words)
			{
				if (!name.contains(word))
				{
					match = false;
					break;
				}
			}
			if (match)
			{
				found.add(recipe);
			}
		}

		found.sort((a, b) ->
		{
			final int byName = getItemName(a.getItemId()).compareToIgnoreCase(getItemName(b.getItemId()));
			return byName != 0 ? byName : Integer.compare(b.getSuccessRate(), a.getSuccessRate());
		});
		return found;
	}

	/**
	 * @param itemId an item
	 * @return the item's name
	 */
	public static String getItemName(int itemId)
	{
		final ItemTemplate template = ItemData.getInstance().getTemplate(itemId);
		return template != null ? template.getName() : ("Item " + itemId);
	}

	/**
	 * @param goal a goal
	 * @return "Sword of Valhalla" or "1904 Soulshot (B-Grade) (2 crafts)"
	 */
	public static String describe(CraftingGoal goal)
	{
		final RecipeList recipe = goal.getRecipe();
		final String name = getItemName(goal.getProductId());
		if ((recipe == null) || ((recipe.getCount() <= 1) && (goal.getCrafts() <= 1)))
		{
			return name;
		}
		return goal.getAmount() + " " + name + " (" + goal.getCrafts() + (goal.getCrafts() == 1 ? " craft)" : " crafts)");
	}

	// ---------------------------------------------------------------------------------------------
	// Players
	// ---------------------------------------------------------------------------------------------

	private void onLogin(Player player)
	{
		final String saved = player.getVariables().getString(VAR_GOALS, "");
		if (saved.isEmpty())
		{
			return;
		}

		final Tracker tracker = new Tracker();
		final List<CraftingGoal> pinned = new ArrayList<>();
		for (String entry : saved.split(";"))
		{
			// recipe,crafts,ready,celebrated|celebrated...,pinned
			final String[] parts = entry.split(",", -1);
			if (parts.length < 3)
			{
				continue;
			}

			try
			{
				final int recipeListId = Integer.parseInt(parts[0]);
				final int crafts = Integer.parseInt(parts[1]);
				if ((RecipeData.getInstance().getRecipeList(recipeListId) == null) || (crafts <= 0) || (tracker._goals.size() >= CraftingGoalsConfig.MAX_GOALS))
				{
					continue;
				}

				final CraftingGoal goal = new CraftingGoal(recipeListId, crafts);
				goal.setReadyCelebrated(parts[2].equals("1"));
				if ((parts.length > 3) && !parts[3].isEmpty())
				{
					for (String key : parts[3].split("\\|"))
					{
						goal.getCelebrated().add(Integer.parseInt(key));
					}
				}
				if ((parts.length > 4) && parts[4].equals("1"))
				{
					pinned.add(goal);
				}
				tracker._goals.add(goal);
			}
			catch (NumberFormatException e)
			{
				LOGGER.warning(getClass().getSimpleName() + ": Bad crafting goal \"" + entry + "\" of " + player.getName() + ".");
			}
		}

		if (!tracker._goals.isEmpty())
		{
			_trackers.put(player.getObjectId(), tracker);
			checkAll(player, false);

			// Pinned once the lines are counted, so the client gets them whole and no update comes first.
			for (CraftingGoal goal : pinned)
			{
				synchronized (goal)
				{
					goal.setPinned(true);
					sendInfo(player, goal);
				}
			}
		}
	}

	private void save(Player player)
	{
		final StringBuilder sb = new StringBuilder();
		for (CraftingGoal goal : getGoals(player))
		{
			if (sb.length() > 0)
			{
				sb.append(';');
			}

			synchronized (goal)
			{
				sb.append(goal.getRecipeListId()).append(',').append(goal.getCrafts()).append(',').append(goal.isReadyCelebrated() ? '1' : '0').append(',');
				boolean first = true;
				for (int key : goal.getCelebrated())
				{
					if (!first)
					{
						sb.append('|');
					}
					sb.append(key);
					first = false;
				}
				sb.append(',').append(goal.isPinned() ? '1' : '0');
			}
		}

		if (sb.length() == 0)
		{
			player.getVariables().remove(VAR_GOALS);
		}
		else
		{
			player.getVariables().set(VAR_GOALS, sb.toString());
		}
	}

	private void onQuestionMark(Player player, int markId)
	{
		if (markId == QUESTION_MARK_ID)
		{
			openTracker(player);
		}
	}

	/**
	 * Opens the tracker window ({@code .craft}).
	 * @param player the player
	 */
	public void openTracker(Player player)
	{
		final IVoicedCommandHandler handler = VoicedCommandHandler.getInstance().getHandler(VOICED_COMMAND);
		if (handler != null)
		{
			handler.onCommand(VOICED_COMMAND, player, "");
		}
	}

	private static void firework(Player player, int skillId)
	{
		if (!CraftingGoalsConfig.FIREWORKS)
		{
			return;
		}

		final ServerPacket packet = new MagicSkillUse(player, player, skillId, 1, 1, 0);
		if (player.isInvisible())
		{
			player.sendPacket(packet);
		}
		else
		{
			player.broadcastPacket(packet);
		}
	}

	public static CraftingGoalManager getInstance()
	{
		return SingletonHolder.INSTANCE;
	}

	private static class SingletonHolder
	{
		protected static final CraftingGoalManager INSTANCE = new CraftingGoalManager();
	}
}
