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
		// Expected shape: "custombuffbook <page> <itemObjId>"
		final String[] parts = command.trim().split("\\s+");
		if (parts.length < 3)
		{
			return false;
		}
		
		try
		{
			final int page = Integer.parseInt(parts[1]);
			final int itemObjId = Integer.parseInt(parts[2]);
			CustomBuffBook.showPage(player, itemObjId, page);
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