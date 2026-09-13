package custom.ClassTransferMaster;

import java.util.List;
import java.util.logging.Logger;

import org.l2jmobius.gameserver.config.custom.ClassTransferConfig;
import org.l2jmobius.gameserver.data.sql.ClassTransferData;
import org.l2jmobius.gameserver.data.sql.ClassTransferHolder;
import org.l2jmobius.gameserver.model.actor.Npc;
import org.l2jmobius.gameserver.model.actor.Player;
import org.l2jmobius.gameserver.model.actor.enums.player.PlayerClass;
import org.l2jmobius.gameserver.model.item.enums.ItemProcessType;
import org.l2jmobius.gameserver.model.script.Quest;

/**
 * Class transfer NPC, handling all three tiers (900001/900002/900003) through one script. Gated by level only, per design - the class tree itself (which current class unlocks which target class(es) at which tier) lives in the class_transfer_tree table, so this script never needs to know
 * PlayerClass's internal tree-navigation methods.
 */
public class ClassTransferMaster extends Quest
{
	private static final Logger LOGGER = Logger.getLogger(ClassTransferMaster.class.getName());
	
	public ClassTransferMaster()
	{
		super(-1);
		
		final int[] npcIds =
		{
			ClassTransferConfig.CLASS_MASTER_TIER1_NPC_ID,
			ClassTransferConfig.CLASS_MASTER_TIER2_NPC_ID,
			ClassTransferConfig.CLASS_MASTER_TIER3_NPC_ID
		};
		
		for (int npcId : npcIds)
		{
			addStartNpc(npcId);
			addFirstTalkId(npcId);
			addTalkId(npcId);
		}
		
		LOGGER.info("ClassTransferMaster: Registered tier 1/2/3 NPCs: " + npcIds[0] + ", " + npcIds[1] + ", " + npcIds[2]);
	}
	
	@Override
	public String onEvent(String event, Npc npc, Player player)
	{
		System.out.println("ClassTransferMaster caught event: " + event + " (npc=" + npc + ", player=" + player + ")");
		
		if (event.startsWith("transfer_"))
		{
			final String[] parts = event.substring("transfer_".length()).split("_");
			if (parts.length != 2)
			{
				System.out.println("ClassTransferMaster: Malformed transfer event, expected 'transfer_<tier>_<toClassId>': " + event);
				return null;
			}
			
			try
			{
				final int tier = Integer.parseInt(parts[0]);
				final int toClassId = Integer.parseInt(parts[1]);
				attemptTransfer(player, tier, toClassId);
			}
			catch (NumberFormatException e)
			{
				System.out.println("ClassTransferMaster: Failed parsing transfer event '" + event + "': " + e.getMessage());
			}
			
			return null;
		}
		
		return super.onEvent(event, npc, player);
	}
	
	@Override
	public String onFirstTalk(Npc npc, Player player)
	{
		return buildHtml(npc, player);
	}
	
	@Override
	public String onTalk(Npc npc, Player player)
	{
		return buildHtml(npc, player);
	}
	
	private void attemptTransfer(Player player, int tier, int toClassId)
	{
		System.out.println("ClassTransferMaster: attemptTransfer() called - player=" + player.getName() + ", tier=" + tier + ", toClassId=" + toClassId);
		
		if (!ClassTransferConfig.CLASS_TRANSFER_ENABLED)
		{
			player.sendMessage("Class transfers are currently disabled.");
			return;
		}
		
		// TODO: verify getPlayerClass()/getId() match your Player/PlayerClass API - these are the
		// accessors I'd expect given "ClassId was renamed to PlayerClass", but weren't directly
		// confirmed against PlayerClass.java.
		final int currentClassId = player.getPlayerClass().getId();
		
		final List<ClassTransferHolder> options = ClassTransferData.getInstance().getEligibleTransfers(currentClassId, tier);
		System.out.println("ClassTransferMaster: currentClassId=" + currentClassId + ", tier=" + tier + " -> " + options.size() + " eligible option(s) found.");
		
		ClassTransferHolder chosen = null;
		for (ClassTransferHolder option : options)
		{
			if (option.getToClassId() == toClassId)
			{
				chosen = option;
				break;
			}
		}
		
		if (chosen == null)
		{
			System.out.println("ClassTransferMaster: toClassId=" + toClassId + " was not among the eligible options - rejecting.");
			player.sendMessage("That class transfer is not available to you.");
			return;
		}
		
		final int requiredLevel = getTierMinLevel(tier);
		if (player.getLevel() < requiredLevel)
		{
			System.out.println("ClassTransferMaster: player level " + player.getLevel() + " below required " + requiredLevel + " - rejecting.");
			player.sendMessage("You must be at least level " + requiredLevel + " for this transfer.");
			return;
		}
		
		if (chosen.getRequiredItemId() != null)
		{
			// TODO: verify destroyItemByItemId's signature - mirrors the exact call already used in
			// your ArenaMaster script's entry-fee logic.
			if (player.getInventory().getInventoryItemCount(chosen.getRequiredItemId(), -1) < chosen.getRequiredItemCount())
			{
				player.sendMessage("You do not have the required item for this transfer.");
				return;
			}
			
			player.destroyItemByItemId(ItemProcessType.FEE, chosen.getRequiredItemId(), chosen.getRequiredItemCount(), player, true);
		}
		
		// Properly set the class and update the database/subclass records
		final int newClassId = chosen.getToClassId();
		player.setPlayerClass(newClassId);
		
		if (player.isSubClassActive())
		{
			player.getSubClasses().get(player.getClassIndex()).setPlayerClass(newClassId);
		}
		else
		{
			player.setBaseClass(newClassId);
		}
		
		player.broadcastUserInfo(); // Updates the client UI immediately
		
		System.out.println("ClassTransferMaster: " + player.getName() + " transferred " + currentClassId + " -> " + newClassId);
		player.sendMessage("Congratulations! Your class has been changed.");
	}
	
	private String buildHtml(Npc npc, Player player)
	{
		final int tier = getTier(npc.getId());
		final StringBuilder sb = new StringBuilder();
		sb.append("<html><body>");
		sb.append("<table width=270 cellpadding=0 cellspacing=0><tr><td align=center>");
		sb.append("<br><font color=\"LEVEL\">- Class Master (Tier ").append(tier).append(") -</font><br1>");
		sb.append("<img src=\"L2UI.SquareGray\" width=270 height=1><br>");
		
		if (!ClassTransferConfig.CLASS_TRANSFER_ENABLED)
		{
			sb.append("<font color=\"FF5555\">Class transfers are currently disabled.</font>");
			sb.append("</td></tr></table></body></html>");
			return sb.toString();
		}
		
		final int currentClassId = player.getPlayerClass().getId(); // TODO: see note in attemptTransfer()
		final List<ClassTransferHolder> options = ClassTransferData.getInstance().getEligibleTransfers(currentClassId, tier);
		
		if (options.isEmpty())
		{
			sb.append("<font color=\"999999\">You have no class transfer available here.<br1>");
			sb.append("(Your current class may not lead to a Tier ").append(tier).append(" transfer here,<br1>");
			sb.append("or you have already transferred.)</font>");
			sb.append("</td></tr></table></body></html>");
			return sb.toString();
		}
		
		final int requiredLevel = getTierMinLevel(tier);
		if (player.getLevel() < requiredLevel)
		{
			sb.append("<font color=\"FF9999\">You must be at least level ").append(requiredLevel).append(" to transfer here.</font><br1>");
			sb.append("<font color=\"999999\">Your level: ").append(player.getLevel()).append("</font>");
			sb.append("</td></tr></table></body></html>");
			return sb.toString();
		}
		
		sb.append("<font color=\"CCCCCC\">Choose your path:</font><br><br>");
		
		for (ClassTransferHolder option : options)
		{
			final String className = resolveClassName(option.getToClassId());
			sb.append("<button value=\"").append(className).append("\" action=\"bypass -h Script ClassTransferMaster transfer_").append(tier).append("_").append(option.getToClassId()).append("\" width=200 height=21 back=\"L2UI_CT1.Button_DF_Down\" fore=\"L2UI_CT1.Button_DF\"><br1>");
		}
		
		sb.append("</td></tr></table>");
		sb.append("</body></html>");
		
		return sb.toString();
	}
	
	/**
	 * Resolves a display name for a PlayerClass ID for the button label. TODO: this is the least certain part of this script - verify against your actual PlayerClass API. If there's no static by-ID lookup, this needs to be replaced with whatever accessor your enum actually exposes; falls back to a
	 * numeric label if resolution fails either way.
	 */
	/**
	 * Resolves a display name for a PlayerClass ID for the button label. TODO: this is the least certain part of this script - verify against your actual PlayerClass API. If there's no static by-ID lookup, this needs to be replaced with whatever accessor your enum actually exposes; falls back to a
	 * numeric label if resolution fails either way.
	 * @param classId the PlayerClass ID to resolve a display name for
	 * @return the class's display name, or {@code "Class #" + classId} if it couldn't be resolved
	 */
	private String resolveClassName(int classId)
	{
		try
		{
			final PlayerClass playerClass = PlayerClass.getPlayerClass(classId);
			return (playerClass != null) ? playerClass.name() : ("Class #" + classId);
		}
		catch (Exception e)
		{
			return "Class #" + classId;
		}
	}
	
	private int getTier(int npcId)
	{
		if (npcId == ClassTransferConfig.CLASS_MASTER_TIER1_NPC_ID)
		{
			return 1;
		}
		if (npcId == ClassTransferConfig.CLASS_MASTER_TIER2_NPC_ID)
		{
			return 2;
		}
		if (npcId == ClassTransferConfig.CLASS_MASTER_TIER3_NPC_ID)
		{
			return 3;
		}
		return 0;
	}
	
	private int getTierMinLevel(int tier)
	{
		switch (tier)
		{
			case 1:
			{
				return ClassTransferConfig.TIER1_MIN_LEVEL;
			}
			case 2:
			{
				return ClassTransferConfig.TIER2_MIN_LEVEL;
			}
			case 3:
			{
				return ClassTransferConfig.TIER3_MIN_LEVEL;
			}
			default:
			{
				return Integer.MAX_VALUE;
			}
		}
	}
	
	public static void main(String[] args)
	{
		new ClassTransferMaster();
		LOGGER.info("---- ClassTransferMaster Script Loaded ----");
	}
}