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
package handlers.bypass.communityboard;

import java.util.List;

import org.l2jmobius.gameserver.cache.HtmCache;
import org.l2jmobius.gameserver.data.xml.ClassListData;
import org.l2jmobius.gameserver.handler.CommunityBoardHandler;
import org.l2jmobius.gameserver.handler.IParseBoardHandler;
import org.l2jmobius.gameserver.managers.PvpRankingManager;
import org.l2jmobius.gameserver.managers.PvpRankingManager.Entry;
import org.l2jmobius.gameserver.model.World;
import org.l2jmobius.gameserver.model.WorldObject;
import org.l2jmobius.gameserver.model.actor.Player;
import org.l2jmobius.gameserver.model.actor.holders.player.ClassInfoHolder;

import handlers.chat.commands.voiced.GearVoiced;

/**
 * PvP ranking board: the players and fake players with the most PvP kills, with their class. The "Gear" button of a line in the world now opens its build in the web planner, like ".gear" on it ({@code _bbspvprank gear <name>}).
 * @author Claude
 */
public class PvpRankingBoard implements IParseBoardHandler
{
	private static final String NAVIGATION_PATH = "data/html/CommunityBoard/Custom/navigation.html";
	private static final String PAGE_PATH = "data/html/CommunityBoard/Custom/pvpranking/main.html";
	
	private static final String COMMAND = "_bbspvprank";
	private static final String GEAR = COMMAND + " gear ";
	
	private static final String[] COMMANDS =
	{
		COMMAND
	};
	
	@Override
	public String[] getCommandList()
	{
		return COMMANDS;
	}
	
	@Override
	public boolean onCommand(String command, Player player)
	{
		if (command.startsWith(GEAR))
		{
			showGear(player, command.substring(GEAR.length()).trim());
		}
		else
		{
			CommunityBoardHandler.getInstance().addBypass(player, "PvP Ranking", command);
		}
		
		final PvpRankingManager manager = PvpRankingManager.getInstance();
		final List<Entry> ranking = manager.getRanking();
		final StringBuilder sb = new StringBuilder();
		if (ranking.isEmpty())
		{
			sb.append("<table width=480><tr><td align=\"center\" height=25>Nobody has PvP kills yet.</td></tr></table>");
		}
		else
		{
			int rank = 0;
			for (Entry entry : ranking)
			{
				rank++;
				final boolean self = entry.getName().equals(player.getName());
				final String color = self ? "LEVEL" : (rank == 1) ? "FFCC33" : (rank <= 3) ? "D0D0D0" : "FFFFFF";
				sb.append("<table width=480 height=24").append((rank % 2) == 0 ? " bgcolor=111111" : "").append("><tr>");
				sb.append("<td width=40 align=\"center\"><font color=\"").append(color).append("\">").append(rank).append("</font></td>");
				sb.append("<td width=150 align=\"left\"><font color=\"").append(color).append("\">").append(entry.getName()).append("</font></td>");
				sb.append("<td width=150 align=\"left\"><font color=\"").append(self ? "LEVEL" : "B0B0B0").append("\">").append(className(entry.getClassId())).append("</font></td>");
				sb.append("<td width=60 align=\"center\"><font color=\"").append(color).append("\">").append(entry.getKills()).append("</font></td>");
				sb.append("<td width=80 align=\"center\">");
				if (find(manager, entry.getName(), entry.isFake()) != null)
				{
					sb.append("<button value=\"Gear\" action=\"bypass ").append(GEAR).append(entry.getName()).append("\" width=60 height=20 back=\"L2UI_CT1.Button_DF_Down\" fore=\"L2UI_CT1.Button_DF\">");
				}
				else
				{
					sb.append("<font color=\"696969\">offline</font>");
				}
				sb.append("</td></tr></table>");
			}
		}
		
		String html = HtmCache.getInstance().getHtm(player, PAGE_PATH);
		html = html.replace("%navigation%", HtmCache.getInstance().getHtm(player, NAVIGATION_PATH));
		html = html.replace("%ranking%", sb.toString());
		html = html.replace("%myPvp%", String.valueOf(player.getPvpKills()));
		CommunityBoardHandler.separateAndSend(html, player);
		return true;
	}
	
	/**
	 * Sends the player the ".gear" link of a character of the ranking.
	 * @param player the player that asks
	 * @param name the name of a player or fake player of the ranking
	 */
	private static void showGear(Player player, String name)
	{
		final PvpRankingManager manager = PvpRankingManager.getInstance();
		for (Entry entry : manager.getRanking())
		{
			if (entry.getName().equals(name))
			{
				final WorldObject target = find(manager, name, entry.isFake());
				if (target == null)
				{
					player.sendMessage(name + " is not in the world now, so their gear can't be viewed.");
					return;
				}
				
				GearVoiced.showBuild(player, target);
				return;
			}
		}
		player.sendMessage(name + " is no longer in the PvP ranking.");
	}
	
	/**
	 * @param manager the ranking
	 * @param name the name of a line of the ranking
	 * @param fake {@code true} if it is a fake player
	 * @return the online player or the spawned fake player of that name, {@code null} if not in the world now
	 */
	private static WorldObject find(PvpRankingManager manager, String name, boolean fake)
	{
		if (fake)
		{
			return manager.findFakePlayer(name);
		}
		
		final Player target = World.getInstance().getPlayer(name);
		return (target != null) && target.isOnline() ? target : null;
	}
	
	/**
	 * @param classId a class id, {@link PvpRankingManager#NO_CLASS} if not known
	 * @return the name of the class, "-" if not known
	 */
	private static String className(int classId)
	{
		final ClassInfoHolder info = classId >= 0 ? ClassListData.getInstance().getClass(classId) : null;
		return info != null ? info.getClassName() : "-";
	}
}
