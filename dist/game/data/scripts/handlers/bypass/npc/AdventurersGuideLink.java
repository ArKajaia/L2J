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
package handlers.bypass.npc;

import org.l2jmobius.gameserver.handler.IBypassHandler;
import org.l2jmobius.gameserver.managers.AdventurersGuideManager;
import org.l2jmobius.gameserver.model.actor.Creature;
import org.l2jmobius.gameserver.model.actor.Player;

/**
 * The links of the Adventurer's Guide pages in the tutorial window: {@code guide book}, {@code guide show <lesson>} and {@code guide radar <place>} (see {@link AdventurersGuideManager#onBypass(Player, String)}). The radar link also works in the retail tutorial's class transfer pages.
 */
public class AdventurersGuideLink implements IBypassHandler
{
	private static final String[] COMMANDS =
	{
		AdventurersGuideManager.BYPASS
	};

	@Override
	public boolean onCommand(String command, Player player, Creature target)
	{
		final String params = command.length() > AdventurersGuideManager.BYPASS.length() ? command.substring(AdventurersGuideManager.BYPASS.length()).trim() : "";
		AdventurersGuideManager.getInstance().onBypass(player, params);
		return true;
	}

	@Override
	public String[] getCommandList()
	{
		return COMMANDS;
	}
}
