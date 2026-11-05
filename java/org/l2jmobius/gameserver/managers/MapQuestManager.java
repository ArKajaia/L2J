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
package org.l2jmobius.gameserver.managers;

import java.util.logging.Logger;

import org.l2jmobius.commons.threads.ThreadPool;
import org.l2jmobius.gameserver.config.custom.MapDeviceConfig;
import org.l2jmobius.gameserver.data.xml.MapDeviceData;
import org.l2jmobius.gameserver.handler.IVoicedCommandHandler;
import org.l2jmobius.gameserver.handler.VoicedCommandHandler;
import org.l2jmobius.gameserver.model.actor.Player;
import org.l2jmobius.gameserver.model.events.Containers;
import org.l2jmobius.gameserver.model.events.EventType;
import org.l2jmobius.gameserver.model.events.holders.actor.player.OnPlayerLogin;
import org.l2jmobius.gameserver.model.events.holders.actor.player.OnPlayerPressTutorialMark;
import org.l2jmobius.gameserver.model.events.holders.actor.player.inventory.OnPlayerItemAdd;
import org.l2jmobius.gameserver.model.events.listeners.ConsumerEventListener;
import org.l2jmobius.gameserver.model.item.enums.ItemProcessType;
import org.l2jmobius.gameserver.model.item.instance.Item;
import org.l2jmobius.gameserver.model.mapdevice.Hideout;
import org.l2jmobius.gameserver.model.mapdevice.MapRun;
import org.l2jmobius.gameserver.model.script.QuestSound;
import org.l2jmobius.gameserver.network.serverpackets.ExShowScreenMessage;
import org.l2jmobius.gameserver.network.serverpackets.TutorialShowQuestionMark;

/**
 * "A Map of Your Own": the quest that teaches the Map Device, raised as a popup (the blinking tutorial question mark) the first time a character gets a map.
 * <ol>
 * <li><b>Go to your hideout</b> ({@code .hideout} in a town).</li>
 * <li><b>Open the map</b> at the Map Device.</li>
 * <li><b>Take a portal</b> into the map.</li>
 * <li><b>Clear a map</b>: be inside one when its last monster falls.</li>
 * </ol>
 * Every new step raises the question mark again; clicking it (or {@code .hideout quest}) opens the quest window ({@code MapDeviceWindow.showQuest} of the scripts). A step already done when it comes up is passed at once (a map picked up in the hideout skips the first).
 * Clearing the map pays {@link MapDeviceConfig#QUEST_REWARD_MEDALS} medals. The step is kept in the player variable {@value #VAR_STEP}; each character does the quest once.
 * @author Mobius
 */
public class MapQuestManager
{
	private static final Logger LOGGER = Logger.getLogger(MapQuestManager.class.getName());

	/** The tutorial question mark of the quest; clicking it opens the quest window. */
	public static final int QUESTION_MARK_ID = 9340;

	public static final String QUEST_NAME = "A Map of Your Own";

	private static final String VAR_STEP = "MAP_QUEST_STEP";
	/** Set while a step's question mark hasn't been clicked: it is raised again at login. */
	private static final String VAR_UNREAD = "MAP_QUEST_UNREAD";

	private static final String VOICED_COMMAND = "hideout";
	/** Steps are checked this long after a teleport, once the player has arrived. */
	private static final int ARRIVAL_DELAY = 1500;
	private static final int SCREEN_MESSAGE_TIME = 5000;

	/**
	 * The steps of the quest, in order.
	 */
	public enum Step
	{
		NONE("", ""),
		HIDEOUT("Go to your hideout", "Type .hideout in a town, or another peaceful place. Your hideout is a private cabin where your Map Device stands."),
		OPEN_MAP("Open the map", "Talk to the Map Device (or double-click the map in your hideout), insert the map and open it. Champion Medals are optional: each one adds a modifier, more experience and more drops."),
		TAKE_PORTAL("Take a portal", "Portals open in a ring round the Map Device. Each one takes one player into the map. Your party may come along (.hideout party)."),
		CLEAR_MAP("Clear the map", "Slay every monster of the map. Nothing respawns, and the last ten come looking for you. If you die you wake up in your hideout and may take another portal."),
		DONE("Complete", "");

		private final String _title;
		private final String _objective;

		Step(String title, String objective)
		{
			_title = title;
			_objective = objective;
		}

		public String getTitle()
		{
			return _title;
		}

		public String getObjective()
		{
			return _objective;
		}

		/**
		 * @return {@code true} for the steps of a quest under way (not before it starts, not once it is complete)
		 */
		public boolean isActive()
		{
			return (this != NONE) && (this != DONE);
		}
	}

	protected MapQuestManager()
	{
		if (!MapDeviceConfig.ENABLED || !MapDeviceConfig.QUEST_ENABLED)
		{
			return;
		}

		Containers.Players().addListener(new ConsumerEventListener(Containers.Players(), EventType.ON_PLAYER_ITEM_ADD, (OnPlayerItemAdd event) -> onItemAdd(event.getPlayer(), event.getItem()), this));
		Containers.Players().addListener(new ConsumerEventListener(Containers.Players(), EventType.ON_PLAYER_PRESS_TUTORIAL_MARK, (OnPlayerPressTutorialMark event) -> onQuestionMark(event.getPlayer(), event.getMarkId()), this));
		Containers.Players().addListener(new ConsumerEventListener(Containers.Players(), EventType.ON_PLAYER_LOGIN, (OnPlayerLogin event) -> onLogin(event.getPlayer()), this));
		LOGGER.info(getClass().getSimpleName() + ": The Map Device quest \"" + QUEST_NAME + "\" starts with a character's first map.");
	}

	private static boolean isEnabled()
	{
		return MapDeviceConfig.ENABLED && MapDeviceConfig.QUEST_ENABLED;
	}

	// =========================================================================
	// State
	// =========================================================================

	/**
	 * @param player a player
	 * @return where the player is in the quest
	 */
	public Step getStep(Player player)
	{
		final int ordinal = player.getVariables().getInt(VAR_STEP, 0);
		final Step[] steps = Step.values();
		return (ordinal >= 0) && (ordinal < steps.length) ? steps[ordinal] : Step.NONE;
	}

	/**
	 * The quest window was read: the question mark isn't raised again at login.
	 * @param player a player
	 */
	public void markRead(Player player)
	{
		if (player.getVariables().hasVariable(VAR_UNREAD))
		{
			player.getVariables().remove(VAR_UNREAD);
		}
	}

	/**
	 * @return how many medals clearing the map pays
	 */
	public int getRewardMedals()
	{
		return MapDeviceConfig.QUEST_REWARD_MEDALS;
	}

	// =========================================================================
	// Progress
	// =========================================================================

	private void onItemAdd(Player player, Item item)
	{
		if ((player == null) || (item == null) || !isEnabled() || (MapDeviceData.getInstance().getTierByItem(item.getId()) == null))
		{
			return;
		}
		if (player.isInOfflineMode() || player.isFakePlayer())
		{
			return;
		}
		if (advance(player, Step.NONE, Step.HIDEOUT))
		{
			player.sendPacket(new ExShowScreenMessage("You found a map! Click the blinking question mark.", ExShowScreenMessage.TOP_CENTER, SCREEN_MESSAGE_TIME));
		}
	}

	/**
	 * Called when a player arrives in a hideout or a map (the instance changed). The steps are checked a moment later, once the player has arrived.
	 * @param player the player
	 */
	public void onArrived(Player player)
	{
		if (!isEnabled() || !getStep(player).isActive())
		{
			return;
		}
		ThreadPool.schedule(() ->
		{
			if (player.isOnline())
			{
				checkProgress(player);
			}
		}, ARRIVAL_DELAY);
	}

	/**
	 * Called when an owner opened a map at their device.
	 * @param player the owner
	 */
	public void onMapOpened(Player player)
	{
		if (isEnabled() && getStep(player).isActive())
		{
			checkProgress(player);
		}
	}

	/**
	 * Called for every player inside a map when its last monster falls.
	 * @param player a player inside
	 */
	public void onMapCompleted(Player player)
	{
		if (!isEnabled() || (getStep(player) != Step.CLEAR_MAP))
		{
			return;
		}
		if (!advance(player, Step.CLEAR_MAP, Step.DONE))
		{
			return;
		}

		final int medals = MapDeviceConfig.QUEST_REWARD_MEDALS;
		if (medals > 0)
		{
			player.addItem(ItemProcessType.REWARD, MapDeviceConfig.MEDAL_ITEM_ID, medals, player, true);
		}
		player.sendPacket(QuestSound.ITEMSOUND_QUEST_FINISH.getPacket());
		player.sendPacket(new ExShowScreenMessage(QUEST_NAME + " - complete!", ExShowScreenMessage.MIDDLE_CENTER, SCREEN_MESSAGE_TIME));
		LOGGER.info(getClass().getSimpleName() + ": " + player.getName() + " completed \"" + QUEST_NAME + "\".");
	}

	/**
	 * Passes every step the player has already done: standing in a hideout (their own, or their party's), their own map open, standing in a map (a party member's map counts too).
	 */
	private void checkProgress(Player player)
	{
		final MapDeviceManager manager = MapDeviceManager.getInstance();
		final boolean inMap = MapDeviceManager.isMapInstance(player.getInstanceId());
		if ((getStep(player) == Step.HIDEOUT) && (inMap || MapDeviceManager.isHideoutInstance(player.getInstanceId())))
		{
			advance(player, Step.HIDEOUT, Step.OPEN_MAP);
		}
		if (getStep(player) == Step.OPEN_MAP)
		{
			final Hideout hideout = manager.getHideout(player.getObjectId());
			final MapRun run = hideout != null ? hideout.getRun() : null;
			if (((run != null) && !run.isClosed()) || inMap)
			{
				advance(player, Step.OPEN_MAP, Step.TAKE_PORTAL);
			}
		}
		if ((getStep(player) == Step.TAKE_PORTAL) && inMap)
		{
			advance(player, Step.TAKE_PORTAL, Step.CLEAR_MAP);
		}
	}

	/**
	 * Moves the player from one step to the next, if they are at the first, and raises the question mark.
	 * @return {@code true} if the player moved on
	 */
	private boolean advance(Player player, Step from, Step to)
	{
		synchronized (player.getVariables())
		{
			if (getStep(player) != from)
			{
				return false;
			}
			player.getVariables().set(VAR_STEP, to.ordinal());
			player.getVariables().set(VAR_UNREAD, true);
		}

		raiseMark(player);
		if (to.isActive())
		{
			if (from != Step.NONE)
			{
				player.sendPacket(QuestSound.ITEMSOUND_QUEST_MIDDLE.getPacket());
			}
			player.sendMessage(QUEST_NAME + ": " + to.getTitle() + ". " + to.getObjective() + " (click the blinking question mark, or type .hideout quest)");
			// A step may be done already (a map picked up in the hideout, a map already open).
			checkProgress(player);
		}
		return true;
	}

	private static void raiseMark(Player player)
	{
		player.sendPacket(new TutorialShowQuestionMark(QUESTION_MARK_ID));
		player.sendPacket(QuestSound.ITEMSOUND_QUEST_TUTORIAL.getPacket());
	}

	private void onLogin(Player player)
	{
		if (isEnabled() && player.getVariables().getBoolean(VAR_UNREAD, false) && (getStep(player) != Step.NONE))
		{
			ThreadPool.schedule(() ->
			{
				if (player.isOnline())
				{
					raiseMark(player);
				}
			}, ARRIVAL_DELAY);
		}
	}

	private void onQuestionMark(Player player, int markId)
	{
		if (markId == QUESTION_MARK_ID)
		{
			openWindow(player);
		}
	}

	/**
	 * Opens the quest window ({@code .hideout quest}).
	 * @param player the player
	 */
	public void openWindow(Player player)
	{
		final IVoicedCommandHandler handler = VoicedCommandHandler.getInstance().getHandler(VOICED_COMMAND);
		if (handler != null)
		{
			handler.onCommand(VOICED_COMMAND, player, "quest");
		}
	}

	public static MapQuestManager getInstance()
	{
		return SingletonHolder.INSTANCE;
	}

	private static class SingletonHolder
	{
		protected static final MapQuestManager INSTANCE = new MapQuestManager();
	}
}
