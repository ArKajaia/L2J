package handlers.chat.commands.voiced;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.l2jmobius.gameserver.handler.IVoicedCommandHandler;
import org.l2jmobius.gameserver.model.WorldObject;
import org.l2jmobius.gameserver.model.actor.Npc;
import org.l2jmobius.gameserver.model.actor.Player;
import org.l2jmobius.gameserver.network.serverpackets.ExLinkOpen;

import custom.PassiveSkillTree.BuildSnapshot;
import custom.PassiveSkillTree.PassiveTreeApiServer;

/**
 * ".gear" - opens the web planner on the full build of the targeted player or fake player (yourself with no target): its gear, stats and passive tree. From there the tree can be copied as a build code or imported straight into your own tree.
 * <p>
 * The build is taken when the command is used and the link stays valid for 30 minutes, even if the target logs off or despawns meanwhile. The link also carries your own 15-minute .treelink token, which is what the import button uses.
 */
public class GearVoiced implements IVoicedCommandHandler
{
	private static final String[] COMMANDS =
	{
		"gear"
	};

	/** Least time between two uses, so a macro can't flood the snapshot store. */
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
			player.sendMessage("Wait a moment before using .gear again.");
			return true;
		}

		final WorldObject target = player.getTarget();
		final String json;
		final String name;
		if ((target == null) || (target == player))
		{
			json = BuildSnapshot.of(player);
			name = "your";
		}
		else if (target.isPlayer())
		{
			json = BuildSnapshot.of(target.asPlayer());
			name = target.getName() + "'s";
		}
		else if (target.isNpc() && target.isFakePlayer())
		{
			json = BuildSnapshot.of((Npc) target);
			name = target.getName() + "'s";
		}
		else
		{
			player.sendMessage("Target a player or a fake player to view their build (or nothing, to view your own).");
			return true;
		}
		LAST_USE.put(player.getObjectId(), now);

		final PassiveTreeApiServer server = PassiveTreeApiServer.getInstance();
		final String url = PassiveTreeLinkVoiced.WEB_BASE_URL + "?token=" + server.generateToken(player.getObjectId(), player.getClassIndex()) + "&inspect=" + server.registerInspect(json);

		player.sendMessage("Link to " + name + " build (click to open, valid 30 minutes):");
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
