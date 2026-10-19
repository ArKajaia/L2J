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

import org.l2jmobius.gameserver.cache.HtmCache;
import org.l2jmobius.gameserver.config.custom.AdventurersGuideConfig;
import org.l2jmobius.gameserver.handler.CommunityBoardHandler;
import org.l2jmobius.gameserver.handler.IParseBoardHandler;
import org.l2jmobius.gameserver.managers.AdventurersGuideManager;
import org.l2jmobius.gameserver.model.actor.Player;
import org.l2jmobius.gameserver.model.guide.GuideLesson;

/**
 * The Adventurer's Guide on the Community Board: the Guidebook (every lesson unlocked, and the ones still to come) and its lessons. {@code _bbsguide show <lesson>} reads one, {@code _bbsguide radar <place>} marks a place of a lesson on the map, {@code _bbsguide tips on|off} turns the
 * tutorial pop-ups on or off for the character. {@code .guide} shows the same in the
 * tutorial window.
 */
public class GuideBoard implements IParseBoardHandler
{
	private static final String NAVIGATION_PATH = "data/html/CommunityBoard/Custom/navigation.html";
	private static final String PAGE_PATH = "data/html/CommunityBoard/Custom/guide/main.html";
	private static final int WIDTH = 480;

	private static final String[] COMMANDS =
	{
		AdventurersGuideManager.BOARD_BYPASS
	};

	@Override
	public String[] getCommandList()
	{
		return COMMANDS;
	}

	@Override
	public boolean onCommand(String command, Player player)
	{
		CommunityBoardHandler.getInstance().addBypass(player, "Guide", command);

		final AdventurersGuideManager manager = AdventurersGuideManager.getInstance();
		final String[] args = command.substring(COMMANDS[0].length()).trim().split("\\s+");
		GuideLesson lesson = null;
		if (args[0].equalsIgnoreCase("show") && (args.length > 1))
		{
			lesson = GuideLesson.find(args[1]);
		}
		else if (args[0].equalsIgnoreCase("radar") && (args.length > 1))
		{
			manager.showOnMap(player, args[1]);
		}
		else if (args[0].equalsIgnoreCase("tips") && (args.length > 1) && AdventurersGuideConfig.ENABLED)
		{
			manager.setTipsEnabled(player, args[1].equalsIgnoreCase("on"));
		}

		final StringBuilder sb = new StringBuilder();
		sb.append("<table width=").append(WIDTH).append("><tr><td>");
		if (!AdventurersGuideConfig.ENABLED)
		{
			sb.append("<center>The Adventurer's Guide is closed.</center>");
		}
		else if ((lesson != null) && lesson.isEnabled() && manager.isUnlocked(player, lesson))
		{
			// Links of the lesson stay on the board.
			sb.append(manager.getLessonContent(player, lesson).replace("bypass -h " + AdventurersGuideManager.BYPASS + " ", "bypass " + AdventurersGuideManager.BOARD_BYPASS + " "));
			sb.append("</td></tr></table><br>");
			sb.append("<button value=\"Guidebook\" action=\"bypass ").append(AdventurersGuideManager.BOARD_BYPASS).append("\" width=120 height=25 back=\"L2UI_CT1.Button_DF_Down\" fore=\"L2UI_CT1.Button_DF\">");
			manager.markSeen(player, lesson);
			send(player, sb.toString());
			return true;
		}
		else
		{
			sb.append(manager.getBookContent(player, "bypass " + AdventurersGuideManager.BOARD_BYPASS + " show "));
			sb.append("</td></tr></table><br>");
			appendTipsSwitch(sb, manager.isTipsEnabled(player));
			send(player, sb.toString());
			return true;
		}
		sb.append("</td></tr></table>");
		send(player, sb.toString());
		return true;
	}

	/**
	 * The switch of the guide's pop-ups: whether new lessons raise the question mark or open by themselves.
	 * @param sb the page
	 * @param enabled {@code true} if the pop-ups are on
	 */
	private static void appendTipsSwitch(StringBuilder sb, boolean enabled)
	{
		sb.append("<table width=").append(WIDTH).append(" bgcolor=111111><tr>");
		sb.append("<td width=330 height=30>Tutorial pop-ups: ").append(enabled ? "<font color=\"66CC66\">ON</font>" : "<font color=\"FF6666\">OFF</font>");
		sb.append("<br1><font color=\"808080\">").append(enabled ? "New lessons blink as a question mark when the moment comes." : "No lesson pops up. The Guidebook can still be read here.").append("</font></td>");
		sb.append("<td width=150 align=right><button value=\"").append(enabled ? "Turn Off" : "Turn On").append("\" action=\"bypass ").append(AdventurersGuideManager.BOARD_BYPASS).append(" tips ").append(enabled ? "off" : "on").append("\" width=120 height=25 back=\"L2UI_CT1.Button_DF_Down\" fore=\"L2UI_CT1.Button_DF\"></td>");
		sb.append("</tr></table>");
	}

	private static void send(Player player, String content)
	{
		String html = HtmCache.getInstance().getHtm(player, PAGE_PATH);
		html = html.replace("%navigation%", HtmCache.getInstance().getHtm(player, NAVIGATION_PATH));
		html = html.replace("%content%", content);
		CommunityBoardHandler.separateAndSend(html, player);
	}
}
