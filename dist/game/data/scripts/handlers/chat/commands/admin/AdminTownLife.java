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

import java.util.StringTokenizer;

import org.l2jmobius.gameserver.config.custom.TownLifeConfig;
import org.l2jmobius.gameserver.handler.IAdminCommandHandler;
import org.l2jmobius.gameserver.managers.TownLifeManager;
import org.l2jmobius.gameserver.model.WorldObject;
import org.l2jmobius.gameserver.model.actor.Player;

/**
 * GM tools of the Town Life (see {@link TownLifeManager}).
 * <ul>
 * <li>{@code //townlife} - what every town is doing, or what the targeted town life npc is doing.</li>
 * <li>{@code //townlife festival on|off|auto} - makes it festival day or not, or follows the calendar again.</li>
 * </ul>
 * The phases of the day are forced with {@code //night}.
 */
public class AdminTownLife implements IAdminCommandHandler
{
	private static final String[] ADMIN_COMMANDS =
	{
		"admin_townlife"
	};
	
	@Override
	public boolean onCommand(String command, Player activeChar)
	{
		if (!TownLifeConfig.ENABLED)
		{
			activeChar.sendSysMessage("Town Life is disabled (TownLifeEnabled in TownLife.ini).");
			return false;
		}
		
		final StringTokenizer st = new StringTokenizer(command);
		st.nextToken();
		final String action = st.hasMoreTokens() ? st.nextToken().toLowerCase() : "status";
		final TownLifeManager manager = TownLifeManager.getInstance();
		switch (action)
		{
			case "status":
			{
				final WorldObject target = activeChar.getTarget();
				final String npc = (target != null) && target.isNpc() ? manager.describe(target.asNpc()) : null;
				if (npc != null)
				{
					activeChar.sendSysMessage(npc);
					return true;
				}
				
				for (String line : manager.describe())
				{
					activeChar.sendSysMessage(line);
				}
				return true;
			}
			case "festival":
			{
				final String value = st.hasMoreTokens() ? st.nextToken().toLowerCase() : "";
				switch (value)
				{
					case "on":
					{
						manager.setFestivalOverride(true);
						break;
					}
					case "off":
					{
						manager.setFestivalOverride(false);
						break;
					}
					case "auto":
					{
						manager.setFestivalOverride(null);
						break;
					}
					default:
					{
						activeChar.sendSysMessage("Usage: //townlife festival on|off|auto");
						return false;
					}
				}
				activeChar.sendSysMessage("Festival day: " + (manager.isFestival() ? "yes" : "no") + (value.equals("auto") ? " (follows the calendar)." : " (until //townlife festival auto or a restart)."));
				return true;
			}
			default:
			{
				activeChar.sendSysMessage("Usage: //townlife [status|festival on|off|auto]");
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
