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
package handlers.chat.commands.admin;

import java.util.List;
import java.util.StringTokenizer;
import java.util.logging.Logger;

import org.l2jmobius.gameserver.config.custom.HiddenQuestConfig;
import org.l2jmobius.gameserver.data.xml.HiddenQuestData;
import org.l2jmobius.gameserver.handler.IAdminCommandHandler;
import org.l2jmobius.gameserver.managers.HiddenQuestManager;
import org.l2jmobius.gameserver.model.World;
import org.l2jmobius.gameserver.model.WorldObject;
import org.l2jmobius.gameserver.model.actor.Player;
import org.l2jmobius.gameserver.model.hiddenquest.HiddenQuestDefinition;
import org.l2jmobius.gameserver.model.hiddenquest.HiddenQuestSession;
import org.l2jmobius.gameserver.network.serverpackets.NpcHtmlMessage;

/**
 * GM tools of the Hidden Quests. Each command works on the named player, else the targeted player, else yourself.
 * <ul>
 * <li>{@code //hiddenquest [name]} - every quest with its hidden condition, the player's progress and state.</li>
 * <li>{@code //hiddenquest_trigger <id> [name]} - queues a quest as if its condition were met; the messenger comes at the next check.</li>
 * <li>{@code //hiddenquest_send <id> [name]} - sends the quest's messenger right now.</li>
 * <li>{@code //hiddenquest_complete [name]} - completes the running task.</li>
 * <li>{@code //hiddenquest_abort [name]} - fails the running task.</li>
 * <li>{@code //hiddenquest_reset <id|all> [name]} - forgets the quest: completion, queue and counter.</li>
 * </ul>
 * @author Mobius
 */
public class AdminHiddenQuests implements IAdminCommandHandler
{
	private static final Logger LOGGER = Logger.getLogger(AdminHiddenQuests.class.getName());

	private static final String[] ADMIN_COMMANDS =
	{
		"admin_hiddenquest",
		"admin_hiddenquest_trigger",
		"admin_hiddenquest_send",
		"admin_hiddenquest_complete",
		"admin_hiddenquest_abort",
		"admin_hiddenquest_reset"
	};

	@Override
	public boolean onCommand(String command, Player activeChar)
	{
		if (!HiddenQuestConfig.ENABLED)
		{
			activeChar.sendSysMessage("Hidden quests are disabled (EnableHiddenQuests in HiddenQuests.ini).");
			return false;
		}

		final StringTokenizer st = new StringTokenizer(command);
		final String action = st.nextToken();
		final HiddenQuestManager manager = HiddenQuestManager.getInstance();
		switch (action)
		{
			case "admin_hiddenquest":
			{
				final Player player = getPlayer(activeChar, st.hasMoreTokens() ? st.nextToken() : null);
				if (player == null)
				{
					return false;
				}
				showStatus(activeChar, player);
				return true;
			}
			case "admin_hiddenquest_trigger":
			case "admin_hiddenquest_send":
			{
				final HiddenQuestDefinition quest = getQuest(activeChar, st);
				final Player player = quest != null ? getPlayer(activeChar, st.hasMoreTokens() ? st.nextToken() : null) : null;
				if (player == null)
				{
					return false;
				}
				if (action.equals("admin_hiddenquest_trigger"))
				{
					manager.adminTrigger(player, quest);
					activeChar.sendSysMessage("Queued " + quest.getName() + " for " + player.getName() + ". The messenger comes at the next check when the player is safe.");
					log(activeChar, "triggered quest " + quest.getId() + " for " + player.getName());
					return true;
				}

				final String problem = manager.adminSendMessenger(player, quest);
				activeChar.sendSysMessage(problem == null ? "The messenger of " + quest.getName() + " is on its way to " + player.getName() + "." : "Could not send the messenger: " + problem + ".");
				log(activeChar, "sent the messenger of quest " + quest.getId() + " to " + player.getName());
				return problem == null;
			}
			case "admin_hiddenquest_complete":
			case "admin_hiddenquest_abort":
			{
				final Player player = getPlayer(activeChar, st.hasMoreTokens() ? st.nextToken() : null);
				if (player == null)
				{
					return false;
				}
				final boolean complete = action.equals("admin_hiddenquest_complete");
				final boolean done = complete ? manager.adminComplete(player) : manager.adminAbort(player);
				activeChar.sendSysMessage(done ? (complete ? "Completed" : "Aborted") + " the hidden task of " + player.getName() + "." : player.getName() + " has no hidden task in progress.");
				log(activeChar, (complete ? "completed" : "aborted") + " the hidden task of " + player.getName());
				return done;
			}
			case "admin_hiddenquest_reset":
			{
				if (!st.hasMoreTokens())
				{
					activeChar.sendSysMessage("Usage: //hiddenquest_reset <id|all> [name]");
					return false;
				}
				final String which = st.nextToken();
				HiddenQuestDefinition quest = null;
				if (!which.equalsIgnoreCase("all"))
				{
					quest = getQuest(activeChar, which);
					if (quest == null)
					{
						return false;
					}
				}
				final Player player = getPlayer(activeChar, st.hasMoreTokens() ? st.nextToken() : null);
				if (player == null)
				{
					return false;
				}
				manager.adminReset(player, quest);
				activeChar.sendSysMessage("Reset " + (quest == null ? "all hidden quests" : quest.getName()) + " for " + player.getName() + ".");
				log(activeChar, "reset " + (quest == null ? "all hidden quests" : "quest " + quest.getId()) + " for " + player.getName());
				return true;
			}
		}
		return false;
	}

	private void showStatus(Player activeChar, Player player)
	{
		final HiddenQuestManager manager = HiddenQuestManager.getInstance();
		final StringBuilder sb = new StringBuilder();
		sb.append("<html><body><center><font color=\"LEVEL\">Hidden Quests - ").append(player.getName()).append("</font></center><br>");

		final HiddenQuestSession session = manager.getSession(player);
		if (session != null)
		{
			sb.append("Active: <font color=\"00FF00\">").append(session.getDefinition().getName()).append("</font><br1>");
			sb.append(session.getTask().getProgress()).append(" - ").append(session.getSecondsLeft() / 60).append(" min left<br>");
		}
		final HiddenQuestDefinition visiting = manager.getVisitingQuest(player);
		if (visiting != null)
		{
			sb.append("Messenger with the player: ").append(visiting.getName()).append("<br>");
		}
		final List<Integer> pending = manager.getPending(player);
		sb.append("Queued: ").append(pending.isEmpty() ? "none" : pending.toString()).append("<br><br>");

		sb.append("<table width=280>");
		for (HiddenQuestDefinition quest : HiddenQuestData.getInstance().getQuests())
		{
			final String state = manager.getState(player, quest);
			sb.append("<tr><td width=20>").append(quest.getId()).append("</td><td width=160>").append(quest.getName());
			sb.append("<br1><font color=\"999999\">").append(quest.getTrigger().getType()).append(": ").append(manager.getProgress(player, quest.getTrigger())).append("/").append(quest.getTrigger().getCount()).append("</font></td>");
			sb.append("<td width=50>").append(state).append("</td>");
			sb.append("<td width=50><a action=\"bypass -h admin_hiddenquest_send ").append(quest.getId()).append(' ').append(player.getName()).append("\">send</a></td></tr>");
		}
		sb.append("</table></body></html>");
		activeChar.sendPacket(new NpcHtmlMessage(0, sb.toString()));
	}

	private HiddenQuestDefinition getQuest(Player activeChar, StringTokenizer st)
	{
		if (!st.hasMoreTokens())
		{
			activeChar.sendSysMessage("Give a hidden quest id (see //hiddenquest).");
			return null;
		}
		return getQuest(activeChar, st.nextToken());
	}

	private HiddenQuestDefinition getQuest(Player activeChar, String value)
	{
		try
		{
			final HiddenQuestDefinition quest = HiddenQuestData.getInstance().getQuest(Integer.parseInt(value));
			if (quest == null)
			{
				activeChar.sendSysMessage("No hidden quest " + value + ".");
			}
			return quest;
		}
		catch (NumberFormatException e)
		{
			activeChar.sendSysMessage("Not a quest id: " + value);
			return null;
		}
	}

	/**
	 * @param activeChar the GM
	 * @param name a player name, {@code null} for the GM's target (or the GM)
	 * @return the player, {@code null} if the named one is not online
	 */
	private Player getPlayer(Player activeChar, String name)
	{
		if (name != null)
		{
			final Player player = World.getInstance().getPlayer(name);
			if (player == null)
			{
				activeChar.sendSysMessage("Player " + name + " is not online.");
			}
			return player;
		}

		final WorldObject target = activeChar.getTarget();
		return (target != null) && target.isPlayer() ? target.asPlayer() : activeChar;
	}

	private void log(Player activeChar, String what)
	{
		LOGGER.info("GM " + activeChar.getName() + " " + what + ".");
	}

	@Override
	public String[] getCommandList()
	{
		return ADMIN_COMMANDS;
	}
}
