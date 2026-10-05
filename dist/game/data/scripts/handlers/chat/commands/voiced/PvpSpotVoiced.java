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
package handlers.chat.commands.voiced;

import org.l2jmobius.gameserver.config.PlayerConfig;
import org.l2jmobius.gameserver.config.custom.PvpSpotsConfig;
import org.l2jmobius.gameserver.handler.IVoicedCommandHandler;
import org.l2jmobius.gameserver.managers.PvpSpotManager;
import org.l2jmobius.gameserver.managers.PvpSpotManager.SpotInfo;
import org.l2jmobius.gameserver.model.Location;
import org.l2jmobius.gameserver.model.actor.Player;
import org.l2jmobius.gameserver.model.item.enums.ItemProcessType;
import org.l2jmobius.gameserver.model.olympiad.OlympiadManager;
import org.l2jmobius.gameserver.model.zone.ZoneId;
import org.l2jmobius.gameserver.network.serverpackets.ActionFailed;
import org.l2jmobius.gameserver.network.serverpackets.NpcHtmlMessage;
import org.l2jmobius.gameserver.taskmanagers.AttackStanceTaskManager;

/**
 * ".pvp" (or ".pvpspots"): the PvP spots (see {@link PvpSpotManager}) with their levels, how many fight there and their leader, and a button to teleport to each. ".pvp go &lt;zone id&gt;" teleports, under the rules of a gatekeeper (not in a fight, the Olympiad, a duel,
 * an event, a siege, an instance or jail...).
 */
public class PvpSpotVoiced implements IVoicedCommandHandler
{
	private static final String[] COMMANDS =
	{
		"pvp",
		"pvpspots"
	};

	@Override
	public boolean onCommand(String command, Player player, String params)
	{
		if (player == null)
		{
			return false;
		}

		if (!PvpSpotsConfig.ENABLED || !PvpSpotsConfig.TELEPORT_ENABLED)
		{
			player.sendMessage("The PvP spots are closed.");
			return false;
		}

		final String[] args = (params == null) || params.trim().isEmpty() ? new String[0] : params.trim().split("\\s+");
		if ((args.length >= 2) && args[0].equalsIgnoreCase("go"))
		{
			try
			{
				teleport(player, Integer.parseInt(args[1]));
			}
			catch (NumberFormatException e)
			{
				player.sendMessage("Usage: .pvp go <spot>");
			}
			return true;
		}

		showSpots(player);
		return true;
	}

	/**
	 * Shows the spots, lowest levels first.
	 * @param player the player
	 */
	private void showSpots(Player player)
	{
		final StringBuilder sb = new StringBuilder();
		sb.append("<html><title>PvP Spots</title><body><center>");
		sb.append("<table width=280 height=30 bgcolor=000000><tr><td align=center valign=middle><font color=\"LEVEL\">PvP SPOTS</font></td></tr></table>");
		sb.append("<font color=\"A2A2A2\">Everyone in a PvP spot is flagged.<br1>Kills there never give karma");
		if (PvpSpotsConfig.NO_DEATH_PENALTY)
		{
			sb.append(", and dying costs no exp");
		}
		sb.append(".</font><br>");

		boolean any = false;
		for (SpotInfo spot : PvpSpotManager.getInstance().getSpotInfos())
		{
			any = true;
			final boolean fits = (player.getLevel() >= spot.getMinLevel()) && (player.getLevel() <= spot.getMaxLevel());
			sb.append("<table width=280 border=0 cellspacing=2 cellpadding=2 bgcolor=111111>");
			sb.append("<tr><td width=180><font color=\"").append(fits ? "FFCC33" : "B09B79").append("\">").append(spot.getName()).append("</font></td>");
			sb.append("<td width=100 align=right><font color=\"00FFFF\">Lv. ").append(spot.getMinLevel()).append("-").append(spot.getMaxLevel()).append("</font></td></tr>");
			sb.append("<tr><td colspan=2><font color=\"B0B0B0\">Fighting: ").append(spot.getFakePlayers() + spot.getPlayers()).append("</font>");
			if (spot.getLeader() != null)
			{
				sb.append("<font color=\"B0B0B0\"> - Leader: </font><font color=\"FF6666\">").append(spot.getLeader()).append("</font><font color=\"B0B0B0\"> (").append(spot.getLeaderStreak()).append(" kills)</font>");
			}
			sb.append("</td></tr>");
			sb.append("<tr><td colspan=2 align=center><button value=\"Teleport\" action=\"bypass voiced_pvp go ").append(spot.getZoneId()).append("\" width=100 height=22 back=\"L2UI_CT1.Button_DF_Down\" fore=\"L2UI_CT1.Button_DF\"></td></tr>");
			sb.append("</table><br>");
		}

		if (!any)
		{
			sb.append("<font color=\"B0B0B0\">There is no PvP spot.</font><br>");
		}

		if (PvpSpotsConfig.TELEPORT_PRICE > 0)
		{
			sb.append("<font color=\"A2A2A2\">A teleport costs ").append(PvpSpotsConfig.TELEPORT_PRICE).append(" adena.</font><br>");
		}
		sb.append("<button value=\"Refresh\" action=\"bypass voiced_pvp\" width=80 height=22 back=\"L2UI_CT1.Button_DF_Down\" fore=\"L2UI_CT1.Button_DF\">");
		sb.append("</center></body></html>");

		final NpcHtmlMessage html = new NpcHtmlMessage(0);
		html.setHtml(sb.toString());
		player.sendPacket(html);
		player.sendPacket(ActionFailed.STATIC_PACKET);
	}

	/**
	 * Teleports the player to a spot, like a gatekeeper would.
	 * @param player the player
	 * @param zoneId the zone id of the spot
	 */
	private void teleport(Player player, int zoneId)
	{
		final Location location = PvpSpotManager.getInstance().getTeleportPoint(zoneId);
		if (location == null)
		{
			player.sendMessage("There is no such PvP spot.");
			return;
		}

		final String refusal = getRefusal(player);
		if (refusal != null)
		{
			player.sendMessage(refusal);
			return;
		}

		if ((PvpSpotsConfig.TELEPORT_PRICE > 0) && !player.reduceAdena(ItemProcessType.FEE, PvpSpotsConfig.TELEPORT_PRICE, player, true))
		{
			return;
		}

		player.teleToLocation(location, false);
	}

	/**
	 * @param player the player
	 * @return why the player can't teleport now, {@code null} if it can
	 */
	private static String getRefusal(Player player)
	{
		if (player.isAlikeDead())
		{
			return "You can't teleport while dead.";
		}

		if (AttackStanceTaskManager.getInstance().hasAttackStanceTask(player) || player.isCastingNow() || player.isTeleporting())
		{
			return "You can't teleport in the middle of a fight.";
		}

		if (player.isInOlympiadMode() || OlympiadManager.getInstance().isRegistered(player) || player.isInDuel() || player.isOnEvent())
		{
			return "You can't teleport while you take part in the Olympiad, a duel or an event.";
		}

		if (player.isJailed() || player.isInSiege() || player.isInsideZone(ZoneId.SIEGE) || (player.getInstanceId() != 0) || player.isInBoat())
		{
			return "You can't teleport from here.";
		}

		if (player.isInStoreMode() || player.isFishing() || player.isFlyingMounted() || player.isMovementDisabled())
		{
			return "You can't teleport right now.";
		}

		if (player.isCursedWeaponEquipped())
		{
			return "You can't teleport with a cursed weapon.";
		}

		if (!PvpSpotsConfig.TELEPORT_KARMA && !PlayerConfig.ALT_GAME_KARMA_PLAYER_CAN_USE_GK && (player.getKarma() > 0))
		{
			return "Chaotic characters can't teleport to the PvP spots.";
		}

		if (PvpSpotsConfig.TELEPORT_PEACE_ONLY && !player.isInsideZone(ZoneId.PEACE))
		{
			return "You can only teleport to the PvP spots from a town.";
		}
		return null;
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
