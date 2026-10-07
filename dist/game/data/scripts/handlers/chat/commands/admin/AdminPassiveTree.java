package handlers.chat.commands.admin;

import org.l2jmobius.gameserver.data.custom.PassiveTreeData;
import org.l2jmobius.gameserver.data.custom.PassiveTreeEditor;
import org.l2jmobius.gameserver.handler.IAdminCommandHandler;
import org.l2jmobius.gameserver.model.actor.Player;
import org.l2jmobius.gameserver.network.serverpackets.ExLinkOpen;

import custom.PassiveSkillTree.PassiveTreeApiServer;

/**
 * The passive tree editor.
 * <ul>
 * <li>//passivetree - opens the web tree editor: move nodes, change links, effects, costs and skills, add and remove nodes. A save writes data/passivetree/*.xml (after a backup in data/passivetree_backup) and is live at once.</li>
 * <li>//passivetree_reload - loads data/passivetree/*.xml again, after they were edited by hand.</li>
 * </ul>
 */
public class AdminPassiveTree implements IAdminCommandHandler
{
	private static final String[] ADMIN_COMMANDS =
	{
		"admin_passivetree",
		"admin_passivetree_reload"
	};
	
	@Override
	public boolean onCommand(String command, Player activeChar)
	{
		if (command.startsWith("admin_passivetree_reload"))
		{
			PassiveTreeEditor.reload();
			activeChar.sendMessage("Passive tree reloaded: " + PassiveTreeData.getInstance().getAllNodes().size() + " nodes. Online players' trees were rebuilt.");
			return true;
		}
		
		final PassiveTreeApiServer server = PassiveTreeApiServer.getInstance();
		final String url = server.getAdminUrl(server.generateAdminToken(activeChar.getObjectId()));
		activeChar.sendMessage("Passive tree editor (works while you stay online):");
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
