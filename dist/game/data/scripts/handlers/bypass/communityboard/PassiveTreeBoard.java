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
package handlers.bypass.communityboard;

import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import org.l2jmobius.gameserver.data.custom.PassiveTreeData;
import org.l2jmobius.gameserver.handler.CommunityBoardHandler;
import org.l2jmobius.gameserver.handler.IParseBoardHandler;
import org.l2jmobius.gameserver.managers.PassiveTreeManager;
import org.l2jmobius.gameserver.model.actor.Player;
import org.l2jmobius.gameserver.model.passivetree.PassiveNode;

/**
 * Community Board page for the passive tree, reached via Alt+B or .passives.
 */
public class PassiveTreeBoard implements IParseBoardHandler
{
	private static final String[] COMMAND =
	{
		"_bbspassives",
		"_bbspassives_allsectors",
		"_bbspassives_sector",
		"_bbspassives_allocate",
		"_bbspassives_reset"
	};
	
	@Override
	public boolean onCommand(String command, Player player)
	{
		if (player == null)
		{
			return false;
		}
		
		final String[] params = command.split(" ");
		switch (params[0])
		{
			case "_bbspassives":
			{
				final String defaultSector = PassiveTreeManager.getInstance().getDefaultSector(player);
				showNodesInSector(player, defaultSector != null ? defaultSector : "Vanguard");
				break;
			}
			case "_bbspassives_allsectors":
			{
				showSectorMenu(player);
				break;
			}
			case "_bbspassives_sector":
			{
				if (params.length > 1)
				{
					showNodesInSector(player, params[1]);
				}
				break;
			}
			case "_bbspassives_allocate":
			{
				if (params.length > 1)
				{
					final int nodeId = Integer.parseInt(params[1]);
					final PassiveNode node = PassiveTreeData.getInstance().getNode(nodeId);
					final boolean ok = PassiveTreeManager.getInstance().allocate(player, nodeId);
					if (!ok)
					{
						player.sendMessage("Cannot allocate that node right now.");
					}
					showNodesInSector(player, node != null ? node.getSector() : "Vanguard");
				}
				break;
			}
			case "_bbspassives_reset":
			{
				PassiveTreeManager.getInstance().resetTree(player);
				final String defaultSector = PassiveTreeManager.getInstance().getDefaultSector(player);
				showNodesInSector(player, defaultSector != null ? defaultSector : "Vanguard");
				break;
			}
		}
		
		return true;
	}
	
	private void showSectorMenu(Player player)
	{
		final Map<String, Integer> sectors = new TreeMap<>();
		if (PassiveTreeData.getInstance().getAllNodes() != null)
		{
			for (PassiveNode node : PassiveTreeData.getInstance().getAllNodes().values())
			{
				if ((node != null) && (node.getSector() != null))
				{
					sectors.merge(node.getSector(), 1, Integer::sum);
				}
			}
		}
		
		final PassiveTreeManager mgr = PassiveTreeManager.getInstance();
		
		final StringBuilder sb = new StringBuilder();
		sb.append("<html><body><center>");
		sb.append("<br><font color=\"LEVEL\" name=\"hs16\">Passive Skill Tree</font><br>");
		sb.append("<font color=\"aaaaaa\">Points available: ").append(mgr.getAvailablePoints(player)).append(" / ").append(mgr.getEarnedPoints(player)).append(" &nbsp; (spent ").append(mgr.getSpentPoints(player)).append(")</font><br><br>");
		sb.append("<img src=\"L2UI.SquareGray\" width=700 height=1><br><br>");
		
		sb.append("<table width=700 border=0 cellpadding=0 cellspacing=0>");
		int col = 0;
		for (Map.Entry<String, Integer> entry : sectors.entrySet())
		{
			if (col == 0)
			{
				sb.append("<tr>");
			}
			
			sb.append("<td width=230 align=center>");
			sb.append("<button value=\"").append(entry.getKey()).append(" (").append(entry.getValue()).append(")\" ").append("action=\"bypass _bbspassives_sector ").append(entry.getKey()).append("\" ").append("width=200 height=25 back=\"L2UI_CT1.Button_DF_Down\" fore=\"L2UI_CT1.Button_DF\"></button>");
			sb.append("</td>");
			
			col++;
			if (col == 3)
			{
				sb.append("</tr><tr><td height=8></td><td height=8></td><td height=8></td></tr>");
				col = 0;
			}
		}
		if (col != 0)
		{
			sb.append("</tr>");
		}
		sb.append("</table>");
		
		sb.append("<br><img src=\"L2UI.SquareGray\" width=700 height=1><br><br>");
		sb.append("<button value=\"Reset Tree (").append(formatCount()).append(")\" ").append("action=\"bypass _bbspassives_reset\" width=260 height=25 ").append("back=\"L2UI_CT1.Button_DF_Down\" fore=\"L2UI_CT1.Button_DF\"></button>");
		sb.append("</center></body></html>");
		
		CommunityBoardHandler.separateAndSend(sb.toString(), player);
	}
	
	private String formatCount()
	{
		return org.l2jmobius.gameserver.config.custom.PassiveTreeConfig.RESET_ITEM_COUNT + " Adena";
	}
	
	private void showNodesInSector(Player player, String sector)
	{
		final PassiveTreeManager mgr = PassiveTreeManager.getInstance();
		Set<Integer> allocated = mgr.getAllocatedNodes(player);
		if (allocated == null)
		{
			allocated = Collections.emptySet();
		}
		
		final Set<Integer> finalAllocated = allocated;
		final String currentSector = ((sector == null) || sector.isEmpty()) ? "Vanguard" : sector;
		
		final StringBuilder sb = new StringBuilder();
		sb.append("<html><body><center>");
		sb.append("<br><font color=\"LEVEL\" name=\"hs16\">").append(currentSector).append("</font><br>");
		sb.append("<font color=\"aaaaaa\">Points available: ").append(mgr.getAvailablePoints(player)).append("</font><br>");
		sb.append("<button value=\"All Sectors\" action=\"bypass _bbspassives_allsectors\" width=100 height=21 ").append("back=\"L2UI_CT1.Button_DF_Down\" fore=\"L2UI_CT1.Button_DF\"></button><br><br>");
		sb.append("<img src=\"L2UI.SquareGray\" width=700 height=1><br><br>");
		
		sb.append("<table width=700 border=0 cellpadding=0 cellspacing=0>");
		
		if (PassiveTreeData.getInstance().getAllNodes() != null)
		{
			PassiveTreeData.getInstance().getAllNodes().values().stream().filter(n -> (n != null) && (n.getSector() != null) && n.getSector().equalsIgnoreCase(currentSector)).sorted((a, b) -> Integer.compare(a.getId(), b.getId())).forEach(node ->
			{
				final boolean has = finalAllocated.contains(node.getId());
				final boolean can = mgr.canAllocate(player, node.getId());
				
				String typeColor;
				if (node.getType() != null)
				{
					switch (node.getType())
					{
						case KEYSTONE:
							typeColor = "FF6060";
							break;
						case MASTER:
							typeColor = "FF60FF";
							break;
						case NOTABLE:
							typeColor = "FFAA00";
							break;
						case ACTIVE_SKILL:
						case FUNCTION:
							typeColor = "60C0FF";
							break;
						default:
							typeColor = "AAAAAA";
							break;
					}
				}
				else
				{
					typeColor = "AAAAAA";
				}
				
				sb.append("<tr><td width=700>");
				sb.append("<font color=\"").append(has ? "00FF00" : typeColor).append("\">").append("[").append(node.getType()).append("] ").append(node.getName() != null ? node.getName() : "").append("</font> ").append("<font color=\"777777\">- ").append(node.getDescription() != null ? node.getDescription() : "").append("</font>");
				sb.append("</td></tr>");
				
				sb.append("<tr><td width=700 align=center>");
				if (has)
				{
					sb.append("<font color=\"00FF00\">Allocated</font>");
				}
				else if (can)
				{
					sb.append("<button value=\"Allocate (").append(node.getCost()).append(" pt)\" ").append("action=\"bypass _bbspassives_allocate ").append(node.getId()).append("\" ").append("width=180 height=21 back=\"L2UI_CT1.Button_DF_Down\" fore=\"L2UI_CT1.Button_DF\"></button>");
				}
				else
				{
					sb.append("<font color=\"777777\">Locked</font>");
				}
				sb.append("</td></tr><tr><td height=8></td></tr>");
			});
		}
		
		sb.append("</table>");
		sb.append("</center></body></html>");
		
		CommunityBoardHandler.separateAndSend(sb.toString(), player);
	}
	
	@Override
	public String[] getCommandList()
	{
		return COMMAND;
	}
}