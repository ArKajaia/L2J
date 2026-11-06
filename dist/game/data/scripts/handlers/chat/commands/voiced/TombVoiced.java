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
import org.l2jmobius.gameserver.managers.TombRaidersManager;
import org.l2jmobius.gameserver.model.actor.Player;

/**
 * {@code .tomb}: the Tomb Raiders, a unique map of the Map Device (see {@link TombRaidersManager}).
 * <ul>
 * <li>{@code .tomb} - the race: the relics, the reliquaries, the pact, the pedestals.</li>
 * <li>{@code .tomb break} - break the seal of the reliquary you stand by (30 seconds, a hit breaks it).</li>
 * <li>{@code .tomb truce} / {@code .tomb rivalry} - answer the rivals' parley.</li>
 * <li>{@code .tomb seal} - seal the Relic Pedestal you stand by with a relic.</li>
 * </ul>
 * @author Mobius
 */
public class TombVoiced implements IVoicedCommandHandler
{
	private static final String[] COMMANDS =
	{
		TombRaidersManager.COMMAND
	};

	@Override
	public boolean onCommand(String command, Player player, String params)
	{
		if (player == null)
		{
			return false;
		}
		final TombRaidersManager manager = TombRaidersManager.getInstance();
		final String[] args = (params == null) || params.trim().isEmpty() ? new String[0] : params.trim().toLowerCase().split("\\s+");
		final String action = args.length > 0 ? args[0] : "";
		String refused = null;
		boolean show = true;
		switch (action)
		{
			case "break":
			{
				refused = manager.breakSeal(player);
				show = refused != null;
				break;
			}
			case "truce":
			case "rivalry":
			{
				refused = manager.choosePact(player, "truce".equals(action));
				break;
			}
			case "seal":
			{
				refused = manager.seal(player);
				break;
			}
			default:
			{
				break;
			}
		}
		if (refused != null)
		{
			player.sendMessage(refused);
		}
		if (show)
		{
			TombRaidersManager.showWindow(player);
		}
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
