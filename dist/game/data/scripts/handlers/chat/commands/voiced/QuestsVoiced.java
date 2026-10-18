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

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.l2jmobius.gameserver.config.custom.DynamicQuestConfig;
import org.l2jmobius.gameserver.handler.IVoicedCommandHandler;
import org.l2jmobius.gameserver.managers.DynamicQuestManager;
import org.l2jmobius.gameserver.managers.DynamicQuestManager.Channel;
import org.l2jmobius.gameserver.model.actor.Player;

/**
 * .quests: the player's dynamic quests, today's board of the nearest town and the world events, in a window.
 * @author Mobius
 */
public class QuestsVoiced implements IVoicedCommandHandler
{
	private static final String[] COMMANDS =
	{
		"quests"
	};

	/** Least time between two uses. */
	private static final long REUSE_MS = 500;
	private static final Map<Integer, Long> LAST_USE = new ConcurrentHashMap<>();

	@Override
	public boolean useVoicedCommand(String command, Player player, String params)
	{
		if ((player == null) || !DynamicQuestConfig.ENABLED)
		{
			return false;
		}

		final long now = System.currentTimeMillis();
		final Long last = LAST_USE.get(player.getObjectId());
		if ((last != null) && ((now - last) < REUSE_MS))
		{
			return true;
		}
		LAST_USE.put(player.getObjectId(), now);

		final DynamicQuestManager manager = DynamicQuestManager.getInstance();
		final String html = manager.handleCommand(player, null, Channel.WINDOW, params != null ? params : "");
		manager.sendWindow(player, html);
		return true;
	}

	@Override
	public String[] getVoicedCommandList()
	{
		return COMMANDS;
	}

	@Override
	public boolean onCommand(String command, Player player, String params)
	{
		return useVoicedCommand(command, player, params);
	}

	@Override
	public String[] getCommandList()
	{
		return COMMANDS;
	}
}
