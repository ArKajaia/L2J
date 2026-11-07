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
import org.l2jmobius.gameserver.managers.ArenaSurvivalManager;
import org.l2jmobius.gameserver.model.actor.Player;

/**
 * .arena: the Survival Arena run of the character - wave, pot, swift streak, boons - and the boon draft after a milestone wave. The boon window's buttons come back here ({@code voiced_arena boon <n>}, {@code voiced_arena ready}), since a Script bypass needs an NPC next to the player and
 * the arena has none.
 */
public class ArenaVoiced implements IVoicedCommandHandler
{
	private static final String[] COMMANDS =
	{
		"arena"
	};

	@Override
	public boolean useVoicedCommand(String command, Player player, String params)
	{
		if (player == null)
		{
			return false;
		}

		final ArenaSurvivalManager manager = ArenaSurvivalManager.getInstance();
		final String[] args = (params == null) ? new String[0] : params.trim().split("\\s+");
		if ((args.length >= 2) && args[0].equalsIgnoreCase("boon"))
		{
			try
			{
				manager.pickBoon(player, Integer.parseInt(args[1]));
			}
			catch (NumberFormatException e)
			{
				manager.showBoonWindow(player);
			}
			return true;
		}

		if ((args.length >= 1) && args[0].equalsIgnoreCase("ready"))
		{
			manager.skipIntermission(player);
			return true;
		}

		manager.showBoonWindow(player);
		return true;
	}

	@Override
	public String[] getVoicedCommandList()
	{
		return COMMANDS;
	}

	@Override
	public boolean onCommand(String command, Player player, String params)
	{
		return useVoicedCommand(command, player, params);
	}

	@Override
	public String[] getCommandList()
	{
		return COMMANDS;
	}
}
