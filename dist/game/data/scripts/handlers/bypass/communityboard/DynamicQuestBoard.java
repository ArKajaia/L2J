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

import org.l2jmobius.gameserver.cache.HtmCache;
import org.l2jmobius.gameserver.config.custom.DynamicQuestConfig;
import org.l2jmobius.gameserver.handler.CommunityBoardHandler;
import org.l2jmobius.gameserver.handler.IParseBoardHandler;
import org.l2jmobius.gameserver.managers.DynamicQuestHtml;
import org.l2jmobius.gameserver.managers.DynamicQuestManager;
import org.l2jmobius.gameserver.managers.DynamicQuestManager.Channel;
import org.l2jmobius.gameserver.model.actor.Player;

/**
 * Bounties & Requests: today's board of the nearest town, the player's dynamic quests and the world events. Board offers can be taken from here; town requests only at the town's Notice Board.
 * @author Mobius
 */
public class DynamicQuestBoard implements IParseBoardHandler
{
	private static final String NAVIGATION_PATH = "data/html/CommunityBoard/Custom/navigation.html";
	private static final String PAGE_PATH = "data/html/CommunityBoard/Custom/quests/main.html";

	private static final String[] COMMANDS =
	{
		"_bbsquests"
	};

	@Override
	public String[] getCommandList()
	{
		return COMMANDS;
	}

	@Override
	public boolean onCommand(String command, Player player)
	{
		CommunityBoardHandler.getInstance().addBypass(player, "Quests", command);

		final String args = command.length() > COMMANDS[0].length() ? command.substring(COMMANDS[0].length()).trim() : "";
		String content;
		if (!DynamicQuestConfig.ENABLED)
		{
			content = DynamicQuestHtml.message("The board is empty.", Channel.BOARD);
		}
		else
		{
			final DynamicQuestManager manager = DynamicQuestManager.getInstance();
			content = manager.handleCommand(player, null, Channel.BOARD, args);
			if (content == null)
			{
				content = manager.handleCommand(player, null, Channel.BOARD, "main");
			}
		}

		String html = HtmCache.getInstance().getHtm(player, PAGE_PATH);
		html = html.replace("%navigation%", HtmCache.getInstance().getHtm(player, NAVIGATION_PATH));
		html = html.replace("%content%", content);
		CommunityBoardHandler.separateAndSend(html, player);
		return true;
	}
}
