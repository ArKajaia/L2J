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

import org.l2jmobius.gameserver.handler.IVoicedCommandHandler;
import org.l2jmobius.gameserver.managers.KetraVarkaFrontManager;
import org.l2jmobius.gameserver.model.actor.Player;

/**
 * {@code .front}: the Ketra-Varka Front, a unique map of the Map Device (see {@link KetraVarkaFrontManager}).
 * <ul>
 * <li>{@code .front} - inside: your side, the war totems, the Prophet and the envoy's offer; outside: your Hero title.</li>
 * <li>{@code .front swear ketra} / {@code .front swear varka} - swear the Front to a side (the first oath binds everyone inside).</li>
 * <li>{@code .front betray} - take the enemy envoy's deal.</li>
 * </ul>
 * @author Mobius
 */
public class FrontVoiced implements IVoicedCommandHandler
{
	private static final String[] COMMANDS =
	{
		KetraVarkaFrontManager.COMMAND
	};

	@Override
	public boolean onCommand(String command, Player player, String params)
	{
		if (player == null)
		{
			return false;
		}
		final KetraVarkaFrontManager manager = KetraVarkaFrontManager.getInstance();
		final String[] args = (params == null) || params.trim().isEmpty() ? new String[0] : params.trim().toLowerCase().split("\\s+");
		final String action = args.length > 0 ? args[0] : "";
		String refused = null;
		if ("swear".equals(action))
		{
			refused = manager.swear(player, args.length > 1 ? args[1] : "");
		}
		else if ("betray".equals(action))
		{
			refused = manager.betray(player);
		}
		if (refused != null)
		{
			player.sendMessage(refused);
		}
		KetraVarkaFrontManager.showWindow(player);
		return true;
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
