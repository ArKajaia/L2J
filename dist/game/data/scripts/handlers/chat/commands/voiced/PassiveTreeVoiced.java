package handlers.chat.commands.voiced;

import org.l2jmobius.gameserver.handler.CommunityBoardHandler;
import org.l2jmobius.gameserver.handler.IVoicedCommandHandler;
import org.l2jmobius.gameserver.model.actor.Player;
import org.l2jmobius.gameserver.network.serverpackets.Ex2PassiveTreeOpen;

/**
 * ".passives" opens the game client's passive tree window (Ex2PassiveTreeOpen; Ctrl+P opens it too). ".passives board" opens the Community Board page instead.
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
		
		if ((params != null) && params.trim().equalsIgnoreCase("board"))
		{
			CommunityBoardHandler.getInstance().handleParseCommand("_bbspassives", player);
			return true;
		}

		player.sendPacket(Ex2PassiveTreeOpen.STATIC_PACKET);
		player.sendMessage("Passive tree opened (Ctrl+P opens it too). For the Community Board page, type .passives board");
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