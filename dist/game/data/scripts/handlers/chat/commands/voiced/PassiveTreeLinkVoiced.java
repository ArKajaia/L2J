package handlers.chat.commands.voiced;

import org.l2jmobius.gameserver.handler.IVoicedCommandHandler;
import org.l2jmobius.gameserver.model.actor.Player;

import custom.PassiveSkillTree.PassiveTreeApiServer;
import org.l2jmobius.gameserver.network.serverpackets.ExLinkOpen;

/**
 * ".treelink" - hands the player a direct, clickable link to the visual passive tree planner, showing THEIR live allocation for their current class_index. Valid for 15 minutes.
 * <p>
 * Sent as its own chat line with nothing else on it, so the client's URL auto-detection picks up the whole thing cleanly - a trailing sentence on the same line risks getting swallowed into the clickable region or breaking the parser depending on client revision.
 */
public class PassiveTreeLinkVoiced implements IVoicedCommandHandler
{
	private static final String[] COMMANDS =
	{
		"treelink"
	};
	
	// Point this at wherever your server is reachable from.
	public static final String WEB_BASE_URL = "http://10.8.0.6:8788/passive-tree.html";

	@Override
	public boolean useVoicedCommand(String command, Player player, String params)
	{
		sendPassiveTreeLink(player);
		return true;
	}

	/**
	 * Generates a fresh 15-minute token and hands the player a direct, clickable link to the visual passive tree planner. Shared by both ".treelink" and the Community Board passives page's "Open Web Planner" button, so there's exactly one place that builds this link.
	 * @param player the player to send the link to
	 */
	public static void sendPassiveTreeLink(Player player)
	{
		final String token = PassiveTreeApiServer.getInstance().generateToken(player.getObjectId(), player.getClassIndex());
		final String url = WEB_BASE_URL + "?token=" + token;

		player.sendMessage("Your passive tree link (click to open, valid 15 minutes):");
		player.sendMessage(url);

		ExLinkOpen lo = new ExLinkOpen(url);
		player.sendPacket(lo);
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