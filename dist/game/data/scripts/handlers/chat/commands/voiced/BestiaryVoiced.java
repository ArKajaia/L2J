package handlers.chat.commands.voiced;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.l2jmobius.gameserver.config.custom.PassiveTreeConfig;
import org.l2jmobius.gameserver.handler.IVoicedCommandHandler;
import org.l2jmobius.gameserver.managers.BestiaryManager;
import org.l2jmobius.gameserver.model.WorldObject;
import org.l2jmobius.gameserver.model.actor.Player;
import org.l2jmobius.gameserver.network.serverpackets.ExLinkOpen;

import custom.Bestiary.BestiaryWeb;
import custom.PassiveSkillTree.PassiveTreeApiServer;

/**
 * ".bestiary" - opens the bestiary of the character's account on the web page of the passive tree server: every monster its characters killed, the bonuses it gives, how far each entry is, and the monsters still to find. With a monster targeted, the page opens on that monster.
 * <p>
 * The link carries the same token as ".treelink" (PassiveTreeWebTokenLifetime, an hour by default); the page keeps working after a class change or a logout until it expires.
 */
public class BestiaryVoiced implements IVoicedCommandHandler
{
	private static final String[] COMMANDS =
	{
		"bestiary"
	};

	/** Least time between two uses, so a macro can't flood the chat with links. */
	private static final long REUSE_MS = 3000;
	private static final Map<Integer, Long> LAST_USE = new ConcurrentHashMap<>();

	@Override
	public boolean useVoicedCommand(String command, Player player, String params)
	{
		if (player == null)
		{
			return false;
		}

		final long now = System.currentTimeMillis();
		final Long last = LAST_USE.get(player.getObjectId());
		if ((last != null) && ((now - last) < REUSE_MS))
		{
			player.sendMessage("Wait a moment before using .bestiary again.");
			return true;
		}
		LAST_USE.put(player.getObjectId(), now);

		// A targeted monster of the collection, or one the player already found, opens the page on it.
		final WorldObject target = player.getTarget();
		int npcId = 0;
		if ((target != null) && target.isMonster() && !target.isFakePlayer())
		{
			final int id = target.asNpc().getId();
			if (BestiaryManager.getInstance().getCollection().contains(id) || BestiaryManager.getInstance().getEntries(player.getObjectId()).containsKey(id))
			{
				npcId = id;
			}
		}

		final String url = BestiaryWeb.getUrl(player, npcId);
		player.sendMessage("Your bestiary (click to open, valid " + PassiveTreeApiServer.describeLifetime(PassiveTreeConfig.WEB_TOKEN_LIFETIME) + "):");
		player.sendMessage(url);
		player.sendPacket(new ExLinkOpen(url));
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
