package handlers.chat.commands.voiced;

import org.l2jmobius.gameserver.handler.CommunityBoardHandler;
import org.l2jmobius.gameserver.handler.IVoicedCommandHandler;
import org.l2jmobius.gameserver.model.actor.Player;

/**
 * Convenience opener: ".passives" in chat routes straight into the Community Board page.
 */
public class PassiveTreeVoiced implements IVoicedCommandHandler
{
	private static final String[] COMMANDS =
	{
		"passives"
	};
	
	@Override
	public boolean useVoicedCommand(String command, Player player, String params)
	{
		if (player == null)
		{
			return false;
		}
		
		CommunityBoardHandler.getInstance().handleParseCommand("_bbspassives", player);
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