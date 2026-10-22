package handlers.chat.commands.admin;

import java.io.IOException;
import java.nio.file.Path;

import org.l2jmobius.gameserver.config.custom.BestiaryConfig;
import org.l2jmobius.gameserver.handler.IAdminCommandHandler;
import org.l2jmobius.gameserver.managers.BestiaryManager;
import org.l2jmobius.gameserver.model.actor.Player;
import org.l2jmobius.gameserver.network.serverpackets.ExLinkOpen;

import custom.Bestiary.BestiaryArt;
import custom.Bestiary.BestiaryBlob;
import custom.Bestiary.BestiaryPackets;
import custom.PassiveSkillTree.PassiveTreeApiServer;

/**
 * //bestiary - opens the bestiary bake page: turns the monster models (taken from the client by client/Bestiary/extract_models.py) to face the camera, and renders the portrait of every monster of the collection in the GM's browser. The portraits are saved in data/html/custom/bestiary and the collection shows them at once.
 * <p>
 * //bestiary_export - writes the catalog the client's bestiary window caches ({@link BestiaryBlob}) to data/html/custom/bestiary/client/Bestiary.cache. Copied into the client's System folder, the window opens without fetching it (client/Bestiary/PROTOCOL.txt).
 * <p>
 * //bestiary_unlock [tier] - raises every monster of the collection to a tier (1 Discovered ... 4 Legendary, the default), with every enabled rare form, for the account of the targeted player, or the GM's own
 * with no player targeted. Entries already higher keep their kills.
 */
public class AdminBestiary implements IAdminCommandHandler
{
	private static final String EXPORT_COMMAND = "admin_bestiary_export";
	private static final String UNLOCK_COMMAND = "admin_bestiary_unlock";
	private static final Path EXPORT_FILE = Path.of("data/html/custom/bestiary/client/Bestiary.cache");

	private static final String[] ADMIN_COMMANDS =
	{
		BestiaryArt.ADMIN_COMMAND,
		EXPORT_COMMAND,
		UNLOCK_COMMAND
	};

	@Override
	public boolean onCommand(String command, Player activeChar)
	{
		if (command.startsWith(EXPORT_COMMAND))
		{
			try
			{
				final BestiaryBlob.Blob blob = BestiaryBlob.export(EXPORT_FILE);
				activeChar.sendMessage("Bestiary catalog: " + blob.getData().length + " bytes, SHA-256 " + BestiaryBlob.hex(blob.getHash()).substring(0, 16) + "...");
				activeChar.sendMessage("Written to " + EXPORT_FILE.toAbsolutePath() + " - copy it into the client's System folder.");
			}
			catch (IOException e)
			{
				activeChar.sendMessage("Could not write " + EXPORT_FILE + ": " + e.getMessage());
			}
			return true;
		}

		if (command.startsWith(UNLOCK_COMMAND))
		{
			int tier = BestiaryConfig.TIER_NAMES.length;
			final String argument = command.substring(UNLOCK_COMMAND.length()).trim();
			if (!argument.isEmpty())
			{
				try
				{
					tier = Integer.parseInt(argument);
				}
				catch (NumberFormatException e)
				{
					tier = 0;
				}
			}
			if ((tier < 1) || (tier > BestiaryConfig.TIER_NAMES.length))
			{
				activeChar.sendMessage("Usage: //bestiary_unlock [tier] - 1 " + BestiaryConfig.TIER_NAMES[0] + " ... " + BestiaryConfig.TIER_NAMES.length + " " + BestiaryConfig.TIER_NAMES[BestiaryConfig.TIER_NAMES.length - 1] + " (the default); on the targeted player, or yourself.");
				return true;
			}
			final Player target = (activeChar.getTarget() != null) && activeChar.getTarget().isPlayer() ? activeChar.getTarget().asPlayer() : activeChar;
			final int changed = BestiaryManager.getInstance().unlockAll(target, tier);
			if (changed < 0)
			{
				activeChar.sendMessage("Bestiary: " + target.getName() + " has no account.");
				return true;
			}
			activeChar.sendMessage("Bestiary: " + changed + " of " + BestiaryManager.getInstance().getCollection().size() + " monsters raised to " + BestiaryConfig.TIER_NAMES[tier - 1] + " for the account of " + target.getName() + ".");
			// The game client's window shows it at once (the web page reads it on its next load).
			if (changed > 0)
			{
				BestiaryPackets.sendState(target);
			}
			if (target != activeChar)
			{
				target.sendMessage("Bestiary: a game master raised your collection to " + BestiaryConfig.TIER_NAMES[tier - 1] + ".");
			}
			return true;
		}

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
