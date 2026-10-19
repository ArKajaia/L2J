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

import java.util.List;

import org.l2jmobius.gameserver.config.custom.VillageSiegeConfig;
import org.l2jmobius.gameserver.data.xml.VillageSiegeData;
import org.l2jmobius.gameserver.handler.IAdminCommandHandler;
import org.l2jmobius.gameserver.managers.VillageSiegeManager;
import org.l2jmobius.gameserver.managers.VillageSiegeManager.Contributor;
import org.l2jmobius.gameserver.managers.VillageSiegeManager.EntranceState;
import org.l2jmobius.gameserver.managers.VillageSiegeManager.Siege;
import org.l2jmobius.gameserver.managers.VillageSiegeManager.Slot;
import org.l2jmobius.gameserver.model.Location;
import org.l2jmobius.gameserver.model.actor.Player;
import org.l2jmobius.gameserver.model.villagesiege.SiegeEntrance;
import org.l2jmobius.gameserver.model.villagesiege.SiegeVillage;
import org.l2jmobius.gameserver.network.serverpackets.NpcHtmlMessage;

/**
 * Village Under Siege (see {@link VillageSiegeManager}):
 * <ul>
 * <li>//villagesiege: the siege going on, the villages (level, entrances, medals per hour) and today's schedule.</li>
 * <li>//villagesiege start &lt;village&gt; [minutes]: starts the siege of a village now ({@code VillageSiegeDuration} minutes by default).</li>
 * <li>//villagesiege stop: calls off the siege going on (no end bonus, nothing scheduled until the next siege).</li>
 * <li>//villagesiege entrances &lt;village&gt;: the entrances found, with a teleport to each gate and the line to keep it in data/VillageSiege.xml.</li>
 * <li>//villagesiege goto &lt;village&gt; &lt;entrance&gt; [gate|rally|guards]: teleports to an entrance.</li>
 * <li>//villagesiege stats: the contribution of every defender of the siege going on, per hour, to tune the medal rates.</li>
 * <li>//villagesiege reset: finds the entrances and levels again (after data/VillageSiege.xml changed).</li>
 * </ul>
 */
public class AdminVillageSiege implements IAdminCommandHandler
{
	private static final String[] ADMIN_COMMANDS =
	{
		"admin_villagesiege"
	};

	@Override
	public boolean onCommand(String command, Player activeChar)
	{
		final VillageSiegeManager manager = VillageSiegeManager.getInstance();
		final String[] args = command.trim().split("\\s+");
		final String action = args.length > 1 ? args[1].toLowerCase() : "status";
		switch (action)
		{
			case "start":
			{
				final SiegeVillage village = args.length > 2 ? VillageSiegeData.getInstance().getVillage(args[2]) : null;
				if (village == null)
				{
					activeChar.sendMessage("Usage: //villagesiege start <village> [minutes]");
					break;
				}
				int minutes = VillageSiegeConfig.DURATION;
				if (args.length > 3)
				{
					try
					{
						minutes = Math.max(1, Integer.parseInt(args[3]));
					}
					catch (NumberFormatException e)
					{
						activeChar.sendMessage("Usage: //villagesiege start <village> [minutes]");
						break;
					}
				}
				final String error = manager.startSiege(village, System.currentTimeMillis() + (minutes * 60000L));
				activeChar.sendMessage(error == null ? "The siege of " + village.getName() + " began for " + minutes + " minutes." : "Could not start: " + error + ".");
				break;
			}
			case "stop":
			{
				if (manager.getSiege() == null)
				{
					activeChar.sendMessage("No siege is going on.");
					break;
				}
				manager.endSiege(true);
				activeChar.sendMessage("The siege was called off.");
				break;
			}
			case "entrances":
			{
				final SiegeVillage village = args.length > 2 ? VillageSiegeData.getInstance().getVillage(args[2]) : null;
				if (village == null)
				{
					activeChar.sendMessage("Usage: //villagesiege entrances <village>");
					break;
				}
				showEntrances(activeChar, village);
				return true;
			}
			case "goto":
			{
				final SiegeVillage village = args.length > 3 ? VillageSiegeData.getInstance().getVillage(args[2]) : null;
				if (village == null)
				{
					activeChar.sendMessage("Usage: //villagesiege goto <village> <entrance> [gate|rally|guards]");
					break;
				}
				try
				{
					final SiegeEntrance entrance = manager.getEntrances(village).get(Integer.parseInt(args[3]));
					final String where = args.length > 4 ? args[4].toLowerCase() : "gate";
					final Location location = where.equals("rally") ? entrance.getRally() : where.equals("guards") ? entrance.getGuardPosts().get(entrance.getGuardPosts().size() / 2) : entrance.getGate();
					activeChar.teleToLocation(location);
				}
				catch (Exception e)
				{
					activeChar.sendMessage("No such entrance.");
				}
				break;
			}
			case "stats":
			{
				showStats(activeChar);
				return true;
			}
			case "reset":
			{
				manager.clearCache();
				activeChar.sendMessage("The entrances and levels will be found again.");
				break;
			}
			default:
			{
				break;
			}
		}

		showStatus(activeChar);
		return true;
	}

	private static void showStatus(Player activeChar)
	{
		final VillageSiegeManager manager = VillageSiegeManager.getInstance();
		final StringBuilder sb = new StringBuilder("<html><title>Village Under Siege</title><body>");
		final Siege siege = manager.getSiege();
		if (siege != null)
		{
			sb.append("<font color=\"FF6666\">").append(siege.getVillage().getName()).append("</font> until ").append(VillageSiegeManager.formatTime(siege.getEnd())).append(", level ").append(siege.getLevel()).append("<br1>");
			sb.append("Monsters: ").append(siege.getMonstersAlive()).append(", slain ").append(siege.getKills()).append(", fake defenders ").append(siege.getFakeDefenders()).append("<br1>");
			for (EntranceState entrance : siege.getEntrances())
			{
				sb.append(entrance.getName()).append(": ").append(entrance.isFallen() ? "fallen" : entrance.getMeterPercent() + "%").append("<br1>");
			}
			sb.append("<button value=\"Stop\" action=\"bypass -h admin_villagesiege stop\" width=60 height=22 back=\"L2UI_CT1.Button_DF_Down\" fore=\"L2UI_CT1.Button_DF\"> ");
			sb.append("<button value=\"Stats\" action=\"bypass -h admin_villagesiege stats\" width=60 height=22 back=\"L2UI_CT1.Button_DF_Down\" fore=\"L2UI_CT1.Button_DF\"><br>");
		}
		else
		{
			sb.append("No siege is going on.<br>");
		}

		sb.append("<table width=280><tr><td width=90>Village</td><td width=30>Lv</td><td width=40>Gates</td><td width=50>Medals/h</td><td width=70></td></tr>");
		for (SiegeVillage village : VillageSiegeData.getInstance().getVillages())
		{
			final int perHour = (int) Math.round(VillageSiegeManager.getMedalsPerPoint(village) * VillageSiegeConfig.EXPECTED_POINTS_PER_HOUR);
			sb.append("<tr><td>").append(village.getId()).append("</td><td>").append(manager.getLevel(village)).append("</td><td><a action=\"bypass -h admin_villagesiege entrances ").append(village.getId()).append("\">").append(manager.getEntrances(village).size()).append("</a></td><td>").append(perHour).append("</td><td><a action=\"bypass -h admin_villagesiege start ").append(village.getId()).append("\">start</a></td></tr>");
		}
		sb.append("</table><br><font color=\"LEVEL\">Today</font><br1>");
		for (Slot slot : manager.getSlots(System.currentTimeMillis()))
		{
			sb.append(VillageSiegeManager.formatTime(slot.getStart())).append("-").append(VillageSiegeManager.formatTime(slot.getEnd())).append(" ").append(slot.getVillage().getName()).append("<br1>");
		}
		sb.append("</body></html>");
		activeChar.sendPacket(new NpcHtmlMessage(0, sb.toString()));
	}

	private static void showEntrances(Player activeChar, SiegeVillage village)
	{
		final List<SiegeEntrance> entrances = VillageSiegeManager.getInstance().getEntrances(village);
		final StringBuilder sb = new StringBuilder("<html><title>").append(village.getName()).append("</title><body>");
		sb.append("Level ").append(VillageSiegeManager.getInstance().getLevel(village)).append(", ").append(entrances.size()).append(" entrances").append(village.getEntrances().isEmpty() ? " (found on their own)" : " (from the data)").append(".<br>");
		for (int i = 0; i < entrances.size(); i++)
		{
			final SiegeEntrance entrance = entrances.get(i);
			final Location gate = entrance.getGate();
			sb.append("<font color=\"LEVEL\">").append(entrance.getName()).append("</font> (weight ").append(entrance.getWeight()).append(")<br1>");
			sb.append("<a action=\"bypass -h admin_villagesiege goto ").append(village.getId()).append(" ").append(i).append(" gate\">gate</a> ");
			sb.append("<a action=\"bypass -h admin_villagesiege goto ").append(village.getId()).append(" ").append(i).append(" rally\">rally</a> ");
			sb.append("<a action=\"bypass -h admin_villagesiege goto ").append(village.getId()).append(" ").append(i).append(" guards\">guards</a><br1>");
			sb.append("&lt;entrance x=\"").append(gate.getX()).append("\" y=\"").append(gate.getY()).append("\" z=\"").append(gate.getZ()).append("\" /&gt;<br>");
		}
		sb.append("<a action=\"bypass -h admin_villagesiege\">Back</a></body></html>");
		activeChar.sendPacket(new NpcHtmlMessage(0, sb.toString()));
	}

	private static void showStats(Player activeChar)
	{
		final Siege siege = VillageSiegeManager.getInstance().getSiege();
		if (siege == null)
		{
			activeChar.sendMessage("No siege is going on.");
			showStatus(activeChar);
			return;
		}

		final double hours = Math.max(1.0 / 60, (System.currentTimeMillis() - siege.getStart()) / 3600000.0);
		final StringBuilder sb = new StringBuilder("<html><title>Siege Stats</title><body>");
		sb.append(siege.getVillage().getName()).append(", ").append(String.format("%.1f", hours * 60)).append(" minutes so far. Expected ").append(VillageSiegeConfig.EXPECTED_POINTS_PER_HOUR).append(" points per hour of fighting.<br>");
		sb.append("<table width=280><tr><td width=110>Defender</td><td width=60>Points</td><td width=50>Pts/h</td><td width=60>Medals</td></tr>");
		for (Contributor contributor : siege.getRanking())
		{
			sb.append("<tr><td>").append(contributor.getName()).append(contributor.isFake() ? "*" : "").append("</td><td>").append(contributor.getPoints()).append("</td><td>").append((int) (contributor.getPoints() / hours)).append("</td><td>").append(contributor.getMedals()).append("</td></tr>");
		}
		sb.append("</table><br1>* fake player<br><a action=\"bypass -h admin_villagesiege\">Back</a></body></html>");
		activeChar.sendPacket(new NpcHtmlMessage(0, sb.toString()));
	}

	@Override
	public String[] getCommandList()
	{
		return ADMIN_COMMANDS;
	}
}
