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
import org.l2jmobius.gameserver.managers.PrimalCrucibleManager;
import org.l2jmobius.gameserver.model.actor.Player;

/**
 * {@code .crucible}: the Primal Crucible, a unique map of the Map Device (see {@link PrimalCrucibleManager}).
 * <ul>
 * <li>{@code .crucible} - inside: your stage, Essence, form, Mutations and the waiting choice; in town: the Titan Scroll exchange.</li>
 * <li>{@code .crucible evolve <n>} / {@code .crucible mutate <n>} - take an option of the waiting choice.</li>
 * <li>{@code .crucible rampage} - a Titan's Rampage (good in a macro).</li>
 * <li>{@code .crucible form} - take your form again (after Transform Dispel).</li>
 * <li>{@code .crucible exchange} - 30 Primal Essence for a Titan Scroll.</li>
 * </ul>
 * @author Mobius
 */
public class CrucibleVoiced implements IVoicedCommandHandler
{
	private static final String[] COMMANDS =
	{
		"crucible"
	};

	@Override
	public boolean onCommand(String command, Player player, String params)
	{
		if (player == null)
		{
			return false;
		}
		final PrimalCrucibleManager manager = PrimalCrucibleManager.getInstance();
		final String[] args = (params == null) || params.trim().isEmpty() ? new String[0] : params.trim().toLowerCase().split("\\s+");
		final String action = args.length > 0 ? args[0] : "";
		String refused = null;
		boolean show = true;
		switch (action)
		{
			case "evolve":
			case "mutate":
			{
				refused = manager.choose(player, args.length > 1 ? parse(args[1]) : -1);
				break;
			}
			case "rampage":
			{
				refused = manager.rampage(player);
				show = refused != null;
				break;
			}
			case "form":
			{
				refused = manager.takeForm(player);
				break;
			}
			case "exchange":
			{
				refused = manager.exchange(player);
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
			PrimalCrucibleManager.showWindow(player);
		}
		return true;
	}

	private static int parse(String text)
	{
		try
		{
			return Integer.parseInt(text);
		}
		catch (NumberFormatException e)
		{
			return -1;
		}
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
