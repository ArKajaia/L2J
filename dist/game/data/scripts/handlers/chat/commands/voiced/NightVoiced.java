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

import java.util.List;

import org.l2jmobius.gameserver.handler.IVoicedCommandHandler;
import org.l2jmobius.gameserver.managers.NightlordManager;
import org.l2jmobius.gameserver.managers.NightlordManager.Nightlord;
import org.l2jmobius.gameserver.managers.ShadowRaidManager;
import org.l2jmobius.gameserver.managers.ShadowRaidManager.Raid;
import org.l2jmobius.gameserver.model.actor.Npc;
import org.l2jmobius.gameserver.model.actor.Player;
import org.l2jmobius.gameserver.model.actor.instance.Monster;
import org.l2jmobius.gameserver.network.serverpackets.ActionFailed;
import org.l2jmobius.gameserver.network.serverpackets.NpcHtmlMessage;

import handlers.bypass.communityboard.NightBoard;

/**
 * ".night": the phase of the day, tonight's Omen, the Nightlords and the Night Watch (see {@link org.l2jmobius.gameserver.managers.NightCycleManager}). .night radar &lt;id&gt;" marks a Nightlord on the radar, ".night raid &lt;id&gt;" a Shadow Raid.
 */
public class NightVoiced implements IVoicedCommandHandler
{
	private static final String[] COMMANDS =
	{
		"night"
	};

	@Override
	public boolean onCommand(String command, Player player, String params)
	{
		if (player == null)
		{
			return false;
		}

		final String[] args = (params == null) || params.trim().isEmpty() ? new String[0] : params.trim().split("\\s+");
		if ((args.length >= 2) && args[0].equalsIgnoreCase("radar"))
		{
			try
			{
				showRadar(player, Integer.parseInt(args[1]));
			}
			catch (NumberFormatException e)
			{
				player.sendMessage("Usage: .night radar <id>");
			}
			return true;
		}

		if ((args.length >= 2) && args[0].equalsIgnoreCase("raid"))
		{
			try
			{
				showRaid(player, Integer.parseInt(args[1]));
			}
			catch (NumberFormatException e)
			{
				player.sendMessage("Usage: .night raid <id>");
			}
			return true;
		}

		final NpcHtmlMessage html = new NpcHtmlMessage(0);
		html.setHtml("<html><title>Night</title><body><center>" + NightBoard.buildContent(player, 280, "voiced_night") + "</center></body></html>");
		player.sendPacket(html);
		player.sendPacket(ActionFailed.STATIC_PACKET);
		return true;
	}

	/**
	 * Marks a Nightlord on the player's radar.
	 * @param player the player
	 * @param objectId the Nightlord's object id
	 */
	private void showRadar(Player player, int objectId)
	{
		final Nightlord nightlord = NightlordManager.getInstance().getNightlord(objectId);
		if (nightlord == null)
		{
			player.sendMessage("That Nightlord is gone.");
			return;
		}

		final Monster monster = nightlord.getMonster();
		player.getRadar().removeAllMarkers();
		player.getRadar().addMarker(monster.getX(), monster.getY(), monster.getZ());
		player.sendMessage("The Nightlord " + monster.getName() + " is marked on your radar (" + nightlord.getZoneName() + ").");
	}

	/**
	 * Marks a raider of a Shadow Raid on the player's radar.
	 * @param player the player
	 * @param raidId the raid's id
	 */
	private void showRaid(Player player, int raidId)
	{
		final Raid raid = ShadowRaidManager.getInstance().getRaid(raidId);
		final List<Npc> raiders = raid != null ? raid.getAliveRaiders() : null;
		if ((raiders == null) || raiders.isEmpty())
		{
			player.sendMessage("That raid is over.");
			return;
		}

		final Npc raider = raiders.get(0);
		player.getRadar().removeAllMarkers();
		player.getRadar().addMarker(raider.getX(), raider.getY(), raider.getZ());
		player.sendMessage("The " + raid.getClanName() + " raid near " + raid.getPlace() + " is marked on your radar.");
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
