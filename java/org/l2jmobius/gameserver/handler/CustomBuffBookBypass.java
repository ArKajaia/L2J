package org.l2jmobius.gameserver.handler;

import java.util.logging.Logger;

import org.l2jmobius.gameserver.model.actor.Creature;
import org.l2jmobius.gameserver.model.actor.Player;

public class CustomBuffBookBypass implements IBypassHandler
{
	private static final Logger LOGGER = Logger.getLogger(CustomBuffBookBypass.class.getName());
	
	private static final String[] COMMANDS =
	{
		"custombuffbook"
	};
	
	public CustomBuffBookBypass()
	{
		LOGGER.info("CustomBuffBookBypass: Loaded, registered bypass command 'custombuffbook'.");
	}
	
	@Override
	public boolean onCommand(String command, Player player, Creature target)
	{
		// Expected shapes: "custombuffbook <page>" and "custombuffbook learn <skillId> <page>"
		final String[] parts = command.trim().split("\\s+");
		try
		{
			if ((parts.length >= 4) && parts[1].equals("learn"))
			{
				CustomBuffBook.learn(player, Integer.parseInt(parts[2]), Integer.parseInt(parts[3]));
			}
			else if (parts.length >= 2)
			{
				CustomBuffBook.showPage(player, Integer.parseInt(parts[1]));
			}
			else
			{
				return false;
			}
		}
		catch (NumberFormatException e)
		{
			return false;
		}
		
		return true;
	}
	
	@Override
	public String[] getCommandList()
	{
		return COMMANDS;
	}
}