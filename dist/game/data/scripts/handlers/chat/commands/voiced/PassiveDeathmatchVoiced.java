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
import org.l2jmobius.gameserver.managers.PassiveDeathmatchManager;
import org.l2jmobius.gameserver.model.actor.Player;

/**
 * Passive Deathmatch picks (see {@link PassiveDeathmatchManager}): ".dmpick" shows the first waiting pick again (after its window was closed), ".dmpick &lt;offer&gt; &lt;node&gt;" takes a passive of it (the window's buttons).
 */
public class PassiveDeathmatchVoiced implements IVoicedCommandHandler
{
	private static final String[] COMMANDS =
	{
		PassiveDeathmatchManager.COMMAND
	};
	
	@Override
	public boolean useVoicedCommand(String command, Player player, String params)
	{
		if (player == null)
		{
			return false;
		}
		
		final PassiveDeathmatchManager manager = PassiveDeathmatchManager.getInstance();
		final String[] args = params == null ? new String[0] : params.trim().split("\\s+");
		if ((args.length >= 2) && !args[0].isEmpty())
		{
			try
			{
				manager.choose(player, Integer.parseInt(args[0]), Integer.parseInt(args[1]));
			}
			catch (NumberFormatException e)
			{
				manager.showWindow(player);
			}
			return true;
		}
		
		manager.showWindow(player);
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
