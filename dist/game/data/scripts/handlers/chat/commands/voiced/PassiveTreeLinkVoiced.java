package handlers.chat.commands.voiced;

import org.l2jmobius.gameserver.handler.IVoicedCommandHandler;
import org.l2jmobius.gameserver.model.actor.Player;

import custom.PassiveSkillTree.PassiveTreeApiServer;

/**
 * ".treelink" - hands the player a short 6-digit code to type into the web passive tree page, instead of a long link (L2's chat window generally can't be copy-pasted from). The code is valid for 10 minutes and is exchanged for the real session token by the webpage itself.
 */
public class PassiveTreeLinkVoiced implements IVoicedCommandHandler
{
	private static final String[] COMMANDS =
	{
		"treelink"
	};
	
	// Point this at wherever your server is reachable from.
	private static final String WEB_BASE_URL = "http://10.8.0.6:8788/passive-tree.html";
	
	@Override
	public boolean useVoicedCommand(String command, Player player, String params)
	{
		final String pin = PassiveTreeApiServer.getInstance().generatePin(player.getObjectId(), player.getClassIndex());
		
		player.sendMessage("Open this page in your browser: " + WEB_BASE_URL);
		player.sendMessage("Then enter this code (valid 60 minutes): " + pin);
		return true;
	}
	
	@Override
	public String[] getVoicedCommandList()
	{
		return COMMANDS;
	}
	
	// See PassiveTreeBoard.java note: some revisions' IVoicedCommandHandler
	// extends a more generic handler interface that separately declares
	// these two - delegate rather than duplicate logic.
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
