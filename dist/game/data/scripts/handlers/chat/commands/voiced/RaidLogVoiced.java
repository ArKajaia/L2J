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
package handlers.chat.commands.voiced;

import org.l2jmobius.gameserver.handler.IVoicedCommandHandler;
import org.l2jmobius.gameserver.managers.RaidLogManager;
import org.l2jmobius.gameserver.model.actor.Player;
import org.l2jmobius.gameserver.network.serverpackets.ActionFailed;

/**
 * ".raidlog" (or ".rblog"): the log of the last raid boss kill the player took part in (see {@link RaidLogManager}). ".raidlog damage|support|parties|mine [page]" opens its other pages.
 */
public class RaidLogVoiced implements IVoicedCommandHandler
{
	private static final String[] COMMANDS =
	{
		"raidlog",
		"rblog"
	};
	
	@Override
	public boolean onCommand(String command, Player player, String params)
	{
		if (player == null)
		{
			return false;
		}
		
		final String[] args = (params == null) || params.isBlank() ? new String[0] : params.trim().toLowerCase().split("\\s+");
		int pageNumber = 1;
		if (args.length > 1)
		{
			try
			{
				pageNumber = Integer.parseInt(args[1]);
			}
			catch (NumberFormatException e)
			{
				// The first page.
			}
		}
		RaidLogManager.show(player, args.length == 0 ? "summary" : args[0], pageNumber);
		player.sendPacket(ActionFailed.STATIC_PACKET);
		return true;
	}
	
	@Override
	public String[] getCommandList()
	{
		return COMMANDS;
	}
	
	@Override
	public boolean useVoicedCommand(String command, Player player, String params)
	{
		return onCommand(command, player, params);
	}
	
	@Override
	public String[] getVoicedCommandList()
	{
		return COMMANDS;
	}
}
