package handlers.chat.commands.admin;

import org.l2jmobius.gameserver.handler.IAdminCommandHandler;
import org.l2jmobius.gameserver.model.actor.Player;
import org.l2jmobius.gameserver.network.serverpackets.ExLinkOpen;

import custom.Bestiary.BestiaryArt;
import custom.PassiveSkillTree.PassiveTreeApiServer;

/**
 * //bestiary - opens the bestiary bake page: turns the monster models (taken from the client by client/Bestiary/extract_models.py) to face the camera, and renders the portrait of every monster of the collection in the GM's browser. The portraits are saved in data/html/custom/bestiary and the collection shows them at once.
 */
public class AdminBestiary implements IAdminCommandHandler
{
	private static final String[] ADMIN_COMMANDS =
	{
		BestiaryArt.ADMIN_COMMAND
	};

	@Override
	public boolean onCommand(String command, Player activeChar)
	{
		final PassiveTreeApiServer server = PassiveTreeApiServer.getInstance();
		final String url = server.getPageUrl("bestiary-bake.html") + "?admin=" + server.generateAdminToken(activeChar.getObjectId());
		activeChar.sendMessage("Bestiary bake page (works while you stay online):");
		activeChar.sendMessage(url);
		activeChar.sendPacket(new ExLinkOpen(url));
		return true;
	}

	@Override
	public String[] getCommandList()
	{
		return ADMIN_COMMANDS;
	}
}
