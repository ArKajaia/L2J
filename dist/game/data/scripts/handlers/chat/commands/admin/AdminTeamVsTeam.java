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

import org.l2jmobius.gameserver.handler.IAdminCommandHandler;
import org.l2jmobius.gameserver.managers.TeamVsTeamManager;
import org.l2jmobius.gameserver.managers.TeamVsTeamManager.Match;
import org.l2jmobius.gameserver.model.Location;
import org.l2jmobius.gameserver.model.actor.Player;
import org.l2jmobius.gameserver.network.serverpackets.NpcHtmlMessage;

/**
 * Team vs Team (see {@link TeamVsTeamManager}):
 * <ul>
 * <li>//tvt [status|detail]: registration and the matches going on (detail: every fighter).</li>
 * <li>//tvt open | close: opens the registration now, or closes it now (the matches start).</li>
 * <li>//tvt simulate [level]: a match of fake players only, of the bracket of that level (the GM's by default), to watch.</li>
 * <li>//tvt stop [match]: ends a match (all of them by default), without rewards.</li>
 * <li>//tvt watch [match]: goes into a match's instance, between the bases, to watch it (the fighters leave a GM outside the match alone).</li>
 * </ul>
 */
public class AdminTeamVsTeam implements IAdminCommandHandler
{
	private static final String[] ADMIN_COMMANDS =
	{
		"admin_tvt"
	};

	@Override
	public boolean onCommand(String command, Player activeChar)
	{
		final TeamVsTeamManager manager = TeamVsTeamManager.getInstance();
		final String[] args = command.trim().split("\\s+");
		final String action = args.length > 1 ? args[1].toLowerCase() : "status";
		switch (action)
		{
			case "open":
			{
				final String refusal = manager.openRegistration();
				activeChar.sendMessage(refusal == null ? "TvT registration opened." : "Could not open: " + refusal + ".");
				break;
			}
			case "close":
			{
				if (!manager.isRegistrationOpen())
				{
					activeChar.sendMessage("Registration is not open.");
					break;
				}
				manager.closeRegistration();
				activeChar.sendMessage("TvT registration closed.");
				break;
			}
			case "simulate":
			{
				final int level = args.length > 2 ? parse(args[2], activeChar.getLevel()) : activeChar.getLevel();
				activeChar.sendMessage(manager.simulate(level));
				break;
			}
			case "stop":
			{
				activeChar.sendMessage(manager.stopMatch(args.length > 2 ? parse(args[2], 0) : 0));
				break;
			}
			case "watch":
			{
				final int id = args.length > 2 ? parse(args[2], 0) : 0;
				Match match = null;
				for (Match running : manager.getMatches())
				{
					if ((id == 0) || (running.getId() == id))
					{
						match = running;
						break;
					}
				}
				if (match == null)
				{
					activeChar.sendMessage("No such match.");
					break;
				}
				final Location center = TeamVsTeamManager.getFieldCenter();
				activeChar.teleToLocation(center.getX(), center.getY(), center.getZ(), 0, match.getInstanceId(), 0);
				activeChar.sendMessage("Watching match " + match.getId() + " (levels " + match.getBracketName() + "). //instance or a teleport takes you back out.");
				break;
			}
			case "detail":
			case "status":
			default:
			{
				final StringBuilder sb = new StringBuilder("<html><title>Team vs Team</title><body>");
				for (String line : manager.getStatus(action.equals("detail")))
				{
					sb.append(line.replace("<", "&lt;")).append("<br1>");
				}
				sb.append("<br>//tvt open | close | simulate [level] | stop [match] | watch [match] | detail</body></html>");
				activeChar.sendPacket(new NpcHtmlMessage(0, sb.toString()));
				break;
			}
		}
		return true;
	}

	private static int parse(String text, int fallback)
	{
		try
		{
			return Integer.parseInt(text);
		}
		catch (NumberFormatException e)
		{
			return fallback;
		}
	}

	@Override
	public String[] getCommandList()
	{
		return ADMIN_COMMANDS;
	}
}
