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
import org.l2jmobius.gameserver.model.actor.Player;
import org.l2jmobius.gameserver.network.serverpackets.ActionFailed;
import org.l2jmobius.gameserver.network.serverpackets.NpcHtmlMessage;

import handlers.bypass.communityboard.CraftingGoalBoard;

/**
 * ".craft": the crafting goal tracker in a window (see {@link org.l2jmobius.gameserver.managers.CraftingGoalManager}). ".craft &lt;item name&gt;" searches the recipes for that item.
 */
public class CraftingGoalVoiced implements IVoicedCommandHandler
{
	private static final String[] COMMANDS =
	{
		"craft"
	};

	private static final String[] ACTIONS =
	{
		"goal",
		"parts",
		"find",
		"book",
		"view",
		"add",
		"abandon"
	};

	@Override
	public boolean onCommand(String command, Player player, String params)
	{
		if (player == null)
		{
			return false;
		}

		String args = params == null ? "" : params.trim();
		if (!args.isEmpty() && !isAction(args.split("\\s+")[0]))
		{
			// ".craft sword of valhalla"
			args = "find 1 " + args;
		}

		final NpcHtmlMessage html = new NpcHtmlMessage(0);
		html.setHtml("<html><title>Crafting Goals</title><body><center>" + CraftingGoalBoard.buildContent(player, args, 280, CraftingGoalBoard.WINDOW_BYPASS) + "</center></body></html>");
		player.sendPacket(html);
		player.sendPacket(ActionFailed.STATIC_PACKET);
		return true;
	}

	private static boolean isAction(String word)
	{
		for (String action : ACTIONS)
		{
			if (action.equalsIgnoreCase(word))
			{
				return true;
			}
		}
		return false;
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
