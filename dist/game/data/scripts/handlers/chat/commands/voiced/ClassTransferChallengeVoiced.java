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

import java.util.List;

import org.l2jmobius.gameserver.handler.IVoicedCommandHandler;
import org.l2jmobius.gameserver.managers.ClassTransferChallengeManager;
import org.l2jmobius.gameserver.model.actor.Player;
import org.l2jmobius.gameserver.model.classtransfer.ChallengeSession;

/**
 * {@code .trial} - shows the progress of the player's class transfer trial in chat; {@code .trial abandon} gives it up. Works anywhere, without an NPC.
 * @author Mobius
 */
public class ClassTransferChallengeVoiced implements IVoicedCommandHandler
{
	private static final String[] COMMANDS =
	{
		"trial"
	};

	@Override
	public boolean useVoicedCommand(String command, Player player, String params)
	{
		final ClassTransferChallengeManager manager = ClassTransferChallengeManager.getInstance();
		final ChallengeSession session = manager.getSession(player);
		if (session == null)
		{
			player.sendMessage("You are not taking a class transfer trial. Speak with a Class Master to start one.");
			return true;
		}

		if ((params != null) && params.trim().equalsIgnoreCase("abandon"))
		{
			if (!manager.abandon(player))
			{
				player.sendMessage("Your trial can't be abandoned right now.");
			}
			return true;
		}

		player.sendMessage("== " + session.getDefinition().getName() + " (" + session.getStage().getDisplayName() + ") ==");
		if (session.getStatus() == ChallengeSession.Status.COMPLETED)
		{
			player.sendMessage("Cleared! Speak with the Trial Master to return.");
			return true;
		}

		final List<String[]> rows = manager.describeObjectives(session);
		for (int i = 0; i < rows.size(); i++)
		{
			final String[] row = rows.get(i);
			final String mark = "done".equals(row[0]) ? "[x]" : "current".equals(row[0]) ? "[>]" : "[ ]";
			player.sendMessage(mark + " " + (i + 1) + ". " + row[1] + (row[2].isEmpty() ? "" : " (" + row[2] + ")"));
		}

		final int seconds = session.getRemainingSeconds();
		player.sendMessage("Time left: " + (seconds / 60) + "m " + (seconds % 60) + "s" + (session.getOmen() != null ? " | Omen: " + ClassTransferChallengeManager.formatOmen(session.getOmen()) : ""));
		player.sendMessage("Type .trial abandon to give up.");
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
		return getVoicedCommandList();
	}
}
