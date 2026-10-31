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
package handlers.chat.commands.admin;

import org.l2jmobius.gameserver.handler.IAdminCommandHandler;
import org.l2jmobius.gameserver.managers.RaceWarsManager;
import org.l2jmobius.gameserver.model.actor.Player;

/**
 * Race Wars (see {@link RaceWarsManager}):
 * <ul>
 * <li>//racewars [status]: the registration and the tournaments going on.</li>
 * <li>//racewars open | close: opens the registration now (outside the rotation's turn), or closes it now (the tournaments start).</li>
 * <li>//racewars simulate [level]: a tournament of fake players only, of the bracket of that level (the GM's by default), to watch or test.</li>
 * <li>//racewars stop: the registration and every tournament end now, without rewards.</li>
 * </ul>
 */
public class AdminRaceWars implements IAdminCommandHandler
{
	private static final String[] ADMIN_COMMANDS =
	{
		"admin_racewars"
	};

	private static final String USAGE = "//racewars [status] | open | close | simulate [level] | stop";

	@Override
	public boolean onCommand(String command, Player activeChar)
	{
		final RaceWarsManager manager = RaceWarsManager.getInstance();
		final String[] args = command.trim().split("\\s+");
		final String action = args.length > 1 ? args[1].toLowerCase() : "status";
		switch (action)
		{
			case "status":
			{
				for (String line : manager.getStatus())
				{
					activeChar.sendMessage(line);
				}
				return true;
			}
			case "open":
			{
				final String refusal = manager.openRegistration();
				activeChar.sendMessage(refusal == null ? "Race Wars registration is open." : "Race Wars: " + refusal + ".");
				return refusal == null;
			}
			case "close":
			{
				if (!manager.isRegistrationOpen())
				{
					activeChar.sendMessage("Race Wars registration is not open.");
					return false;
				}
				manager.closeRegistration();
				activeChar.sendMessage("Race Wars registration closed: the tournaments start.");
				return true;
			}
			case "simulate":
			{
				int level = activeChar.getLevel();
				if (args.length > 2)
				{
					try
					{
						level = Integer.parseInt(args[2]);
					}
					catch (NumberFormatException e)
					{
						activeChar.sendMessage(USAGE);
						return false;
					}
				}
				activeChar.sendMessage(manager.simulate(level));
				return true;
			}
			case "stop":
			{
				activeChar.sendMessage(manager.stop());
				return true;
			}
			default:
			{
				activeChar.sendMessage(USAGE);
				return false;
			}
		}
	}

	@Override
	public String[] getCommandList()
	{
		return ADMIN_COMMANDS;
	}
}
