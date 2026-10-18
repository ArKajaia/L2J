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

import java.util.StringTokenizer;
import java.util.logging.Logger;

import org.l2jmobius.gameserver.config.custom.DynamicQuestConfig;
import org.l2jmobius.gameserver.data.xml.DynamicQuestData;
import org.l2jmobius.gameserver.handler.IAdminCommandHandler;
import org.l2jmobius.gameserver.managers.DynamicQuestManager;
import org.l2jmobius.gameserver.model.World;
import org.l2jmobius.gameserver.model.WorldObject;
import org.l2jmobius.gameserver.model.actor.Player;
import org.l2jmobius.gameserver.model.dynamicquest.QuestInstance;
import org.l2jmobius.gameserver.model.dynamicquest.QuestTemplate;
import org.l2jmobius.gameserver.model.dynamicquest.QuestTier;
import org.l2jmobius.gameserver.model.dynamicquest.objective.AbstractObjective;
import org.l2jmobius.gameserver.network.serverpackets.NpcHtmlMessage;

/**
 * GM tools of the Dynamic Quests.
 * <ul>
 * <li>//dynquest [name]: every template, and a player's quests with their stage and objectives</li>
 * <li>//dynquest_start id [tier] [name]: start a template for a player (a world template starts around the player)</li>
 * <li>//dynquest_herald id [name]: send a herald with a template to a player</li>
 * <li>//dynquest_complete [name], //dynquest_fail [name]: end a player's quests</li>
 * <li>//dynquest_reset [name]: forget what the player's account did today (limits, cooldowns, board offers taken)</li>
 * <li>//dynquest_reroll: roll every Notice Board again</li>
 * </ul>
 * @author Mobius
 */
public class AdminDynamicQuests implements IAdminCommandHandler
{
	private static final Logger LOGGER = Logger.getLogger(AdminDynamicQuests.class.getName());

	private static final String[] ADMIN_COMMANDS =
	{
		"admin_dynquest",
		"admin_dynquest_start",
		"admin_dynquest_herald",
		"admin_dynquest_complete",
		"admin_dynquest_fail",
		"admin_dynquest_reset",
		"admin_dynquest_reroll"
	};

	@Override
	public boolean onCommand(String command, Player activeChar)
	{
		if (!DynamicQuestConfig.ENABLED)
		{
			activeChar.sendSysMessage("Dynamic quests are disabled (EnableDynamicQuests in DynamicQuests.ini).");
			return false;
		}

		final StringTokenizer st = new StringTokenizer(command);
		final String action = st.nextToken();
		final DynamicQuestManager manager = DynamicQuestManager.getInstance();
		switch (action)
		{
			case "admin_dynquest":
			{
				final Player player = getPlayer(activeChar, st.hasMoreTokens() ? st.nextToken() : null);
				if (player == null)
				{
					return false;
				}
				showStatus(activeChar, player);
				return true;
			}
			case "admin_dynquest_start":
			case "admin_dynquest_herald":
			{
				final QuestTemplate template = getTemplate(activeChar, st);
				if (template == null)
				{
					return false;
				}
				QuestTier tier = QuestTier.NORMAL;
				String name = null;
				while (st.hasMoreTokens())
				{
					final String token = st.nextToken();
					final QuestTier parsed = QuestTier.parse(token, null);
					if (parsed != null)
					{
						tier = parsed;
					}
					else
					{
						name = token;
					}
				}
				final Player player = getPlayer(activeChar, name);
				if (player == null)
				{
					return false;
				}
				if (action.equals("admin_dynquest_herald"))
				{
					final boolean sent = manager.adminHerald(player, template);
					activeChar.sendSysMessage(sent ? "A herald is on its way to " + player.getName() + "." : "No herald could be sent (the player can't take it, or no spot was found).");
					log(activeChar, "sent a herald with template " + template.getId() + " to " + player.getName());
					return sent;
				}
				final String problem = manager.adminStart(player, template, tier);
				activeChar.sendSysMessage(problem == null ? "Started template " + template.getId() + " (" + tier.getDisplayName() + ") for " + player.getName() + "." : "Could not start it: " + problem);
				log(activeChar, "started template " + template.getId() + " for " + player.getName());
				return problem == null;
			}
			case "admin_dynquest_complete":
			case "admin_dynquest_fail":
			{
				final Player player = getPlayer(activeChar, st.hasMoreTokens() ? st.nextToken() : null);
				if (player == null)
				{
					return false;
				}
				final boolean success = action.equals("admin_dynquest_complete");
				final int count = manager.adminFinish(player, success);
				activeChar.sendSysMessage((success ? "Completed " : "Failed ") + count + " dynamic quests of " + player.getName() + ".");
				log(activeChar, (success ? "completed " : "failed ") + count + " dynamic quests of " + player.getName());
				return true;
			}
			case "admin_dynquest_reset":
			{
				final Player player = getPlayer(activeChar, st.hasMoreTokens() ? st.nextToken() : null);
				if (player == null)
				{
					return false;
				}
				manager.adminReset(player);
				activeChar.sendSysMessage("Reset the dynamic quest limits of " + player.getAccountName() + ".");
				log(activeChar, "reset the dynamic quest limits of " + player.getAccountName());
				return true;
			}
			case "admin_dynquest_reroll":
			{
				manager.adminReroll();
				activeChar.sendSysMessage("Every Notice Board was rolled again.");
				log(activeChar, "rerolled the notice boards");
				return true;
			}
		}
		return false;
	}

	private void showStatus(Player activeChar, Player player)
	{
		final DynamicQuestManager manager = DynamicQuestManager.getInstance();
		final StringBuilder sb = new StringBuilder();
		sb.append("<html><body><center><font color=\"LEVEL\">Dynamic Quests - ").append(player.getName()).append("</font></center><br>");
		sb.append("Done today by the account: ").append(manager.getAccountDailyTotal(player.getAccountName())).append("/").append(DynamicQuestConfig.ACCOUNT_DAILY_LIMIT).append("<br>");

		for (QuestInstance quest : manager.getQuests(player))
		{
			sb.append("<font color=\"00FF00\">").append(quest.getName()).append("</font> #").append(quest.getId()).append("<br1>");
			sb.append(quest.getDifficulty().getTier().getDisplayName()).append(", level ").append(quest.getDifficulty().getQuestLevel()).append(", party ").append(quest.getDifficulty().getPartySize()).append(", stage ").append(quest.getStageIndex() + 1).append("/").append(quest.getTemplate().getStages().size());
			sb.append(", escalations ").append(quest.getEscalations()).append(", optional ").append(quest.getOptionalDone()).append(", chain ").append(quest.getChain()).append(", ").append(quest.getSecondsLeft()).append("s left<br1>");
			synchronized (quest)
			{
				for (AbstractObjective objective : quest.getObjectives())
				{
					sb.append("<font color=\"999999\">").append(objective.getTemplate().getType()).append(objective.isOptional() ? " (optional)" : "").append(": ").append(quest.format(objective.getProgress())).append(objective.isDone() ? " - done" : "").append("</font><br1>");
				}
			}
			sb.append("<br>");
		}

		sb.append("<table width=280>");
		for (QuestTemplate template : DynamicQuestData.getInstance().getTemplates())
		{
			sb.append("<tr><td width=20>").append(template.getId()).append("</td><td width=150>").append(template.getName()).append("<br1><font color=\"999999\">").append(template.getCategory()).append(", ").append(template.getScope()).append("</font></td>");
			sb.append("<td width=55><a action=\"bypass -h admin_dynquest_start ").append(template.getId()).append(" NORMAL ").append(player.getName()).append("\">start</a></td>");
			sb.append("<td width=55><a action=\"bypass -h admin_dynquest_herald ").append(template.getId()).append(' ').append(player.getName()).append("\">herald</a></td></tr>");
		}
		sb.append("</table></body></html>");
		activeChar.sendPacket(new NpcHtmlMessage(0, sb.toString()));
	}

	private QuestTemplate getTemplate(Player activeChar, StringTokenizer st)
	{
		if (!st.hasMoreTokens())
		{
			activeChar.sendSysMessage("Give a template id (see //dynquest).");
			return null;
		}
		final String value = st.nextToken();
		try
		{
			final QuestTemplate template = DynamicQuestData.getInstance().getTemplate(Integer.parseInt(value));
			if (template == null)
			{
				activeChar.sendSysMessage("No template " + value + ".");
			}
			return template;
		}
		catch (NumberFormatException e)
		{
			activeChar.sendSysMessage("Not a template id: " + value);
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
