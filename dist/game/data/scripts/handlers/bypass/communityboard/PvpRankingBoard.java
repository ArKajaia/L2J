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
import org.l2jmobius.gameserver.handler.CommunityBoardHandler;
import org.l2jmobius.gameserver.handler.IParseBoardHandler;
import org.l2jmobius.gameserver.managers.PvpRankingManager;
import org.l2jmobius.gameserver.managers.PvpRankingManager.Entry;
import org.l2jmobius.gameserver.model.actor.Player;

/**
 * PvP ranking board: the players and fake players with the most PvP kills.
 * @author Claude
 */
public class PvpRankingBoard implements IParseBoardHandler
{
	private static final String NAVIGATION_PATH = "data/html/CommunityBoard/Custom/navigation.html";
	private static final String PAGE_PATH = "data/html/CommunityBoard/Custom/pvpranking/main.html";
	
	private static final String[] COMMANDS =
	{
		"_bbspvprank"
	};
	
	@Override
	public String[] getCommandList()
	{
		return COMMANDS;
	}
	
	@Override
	public boolean onCommand(String command, Player player)
	{
		CommunityBoardHandler.getInstance().addBypass(player, "PvP Ranking", command);
		
		final List<Entry> ranking = PvpRankingManager.getInstance().getRanking();
		final StringBuilder sb = new StringBuilder();
		if (ranking.isEmpty())
		{
			sb.append("<table width=400><tr><td align=\"center\" height=25>Nobody has PvP kills yet.</td></tr></table>");
		}
		else
		{
			int rank = 0;
			for (Entry entry : ranking)
			{
				rank++;
				final boolean self = entry.getName().equals(player.getName());
				final String color = self ? "LEVEL" : (rank == 1) ? "FFCC33" : (rank <= 3) ? "D0D0D0" : "FFFFFF";
				sb.append("<table width=400 height=20").append((rank % 2) == 0 ? " bgcolor=111111" : "").append("><tr>");
				sb.append("<td width=60 align=\"center\"><font color=\"").append(color).append("\">").append(rank).append("</font></td>");
				sb.append("<td width=220 align=\"left\"><font color=\"").append(color).append("\">").append(entry.getName()).append("</font></td>");
				sb.append("<td width=120 align=\"center\"><font color=\"").append(color).append("\">").append(entry.getKills()).append("</font></td>");
				sb.append("</tr></table>");
			}
		}
		
		String html = HtmCache.getInstance().getHtm(player, PAGE_PATH);
		html = html.replace("%navigation%", HtmCache.getInstance().getHtm(player, NAVIGATION_PATH));
		html = html.replace("%ranking%", sb.toString());
		html = html.replace("%myPvp%", String.valueOf(player.getPvpKills()));
		CommunityBoardHandler.separateAndSend(html, player);
		return true;
	}
}
