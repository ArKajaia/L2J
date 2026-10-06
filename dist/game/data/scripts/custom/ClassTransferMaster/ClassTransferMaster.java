package custom.ClassTransferMaster;

import java.util.List;
import java.util.logging.Logger;

import org.l2jmobius.gameserver.config.PlayerConfig;
import org.l2jmobius.gameserver.config.custom.ClassTransferChallengeConfig;
import org.l2jmobius.gameserver.config.custom.ClassTransferConfig;
import org.l2jmobius.gameserver.data.sql.ClassTransferChallengeCompletionTable;
import org.l2jmobius.gameserver.data.sql.ClassTransferData;
import org.l2jmobius.gameserver.data.sql.ClassTransferHolder;
import org.l2jmobius.gameserver.managers.ClassTransferChallengeManager;
import org.l2jmobius.gameserver.model.actor.Npc;
import org.l2jmobius.gameserver.model.actor.Player;
import org.l2jmobius.gameserver.model.actor.enums.player.PlayerClass;
import org.l2jmobius.gameserver.model.classtransfer.TransferStage;
import org.l2jmobius.gameserver.model.item.enums.ItemProcessType;
import org.l2jmobius.gameserver.model.script.Quest;
import org.l2jmobius.gameserver.network.serverpackets.PlaySound;

/**
 * Class transfer NPC (900001), handling all three tiers: the tier it offers follows the player's current class. Gated by level only, per design - the class tree itself (which current class unlocks which target class(es) at which tier) lives in the class_transfer_tree table, so this script never needs to know
 * PlayerClass's internal tree-navigation methods.
 */
public class ClassTransferMaster extends Quest
{
	private static final Logger LOGGER = Logger.getLogger(ClassTransferMaster.class.getName());
	
	public ClassTransferMaster()
	{
		super(-1);
		
		addStartNpc(ClassTransferConfig.CLASS_MASTER_NPC_ID);
		addFirstTalkId(ClassTransferConfig.CLASS_MASTER_NPC_ID);
		addTalkId(ClassTransferConfig.CLASS_MASTER_NPC_ID);
		
		LOGGER.info("ClassTransferMaster: Registered Class Master NPC: " + ClassTransferConfig.CLASS_MASTER_NPC_ID);
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
				attemptTransfer(player, npc, tier, toClassId);
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
	
	private void attemptTransfer(Player player, Npc npc, int tier, int toClassId)
	{
		System.out.println("ClassTransferMaster: attemptTransfer() called - player=" + player.getName() + ", tier=" + tier + ", toClassId=" + toClassId);
		
		if (!ClassTransferConfig.CLASS_TRANSFER_ENABLED)
		{
			player.sendMessage("Class transfers are currently disabled.");
			return;
		}
		
		// The tier is the one the player's current class is at, whatever the link says.
		if ((npc == null) || (npc.getId() != ClassTransferConfig.CLASS_MASTER_NPC_ID) || (getTier(player) != tier))
		{
			player.sendMessage("That class transfer is not available here.");
			return;
		}
		
		if (player.isDead() || (player.getInstanceId() != 0))
		{
			player.sendMessage("You can't change your class right now.");
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
		
		// Alternative Class Transfer Challenge: checked here, right before the transfer, so no dialog or hand-made link can skip it.
		final TransferStage stage = TransferStage.fromTier(tier);
		if (!ClassTransferChallengeManager.getInstance().isTransferUnlocked(player, stage))
		{
			player.sendMessage("You must clear the " + stage.getDisplayName() + " trial before this transfer.");
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
		if (player.getPlayerClass().getId() != newClassId)
		{
			// setPlayerClass refuses while a subclass change is in progress.
			player.sendMessage("Your class could not be changed right now. Please try again.");
			return;
		}
		
		if (player.isSubClassActive())
		{
			player.getSubClasses().get(player.getClassIndex()).setPlayerClass(newClassId);
		}
		else
		{
			player.setBaseClass(newClassId);
		}
		
		if (PlayerConfig.AUTO_LEARN_SKILLS)
		{
			player.giveAvailableSkills(PlayerConfig.AUTO_LEARN_FS_SKILLS, true, PlayerConfig.AUTO_LEARN_SKILLS_WITHOUT_ITEMS);
		}
		player.store(false); // Saved at once, so a crash can't lose the new class (or give back the used trial).
		
		// The trial was cleared for this transfer: it is used up.
		ClassTransferChallengeManager.getInstance().onClassTransferred(player, stage);
		
		player.broadcastUserInfo(); // Updates the client UI immediately
		player.sendSkillList();
		player.sendPacket(new PlaySound("ItemSound.quest_fanfare_2"));
		
		System.out.println("ClassTransferMaster: " + player.getName() + " transferred " + currentClassId + " -> " + newClassId);
		player.sendMessage("Congratulations! Your class has been changed.");
	}
	
	private String buildHtml(Npc npc, Player player)
	{
		final int tier = getTier(player);
		final TransferStage stage = TransferStage.fromTier(tier);
		final StringBuilder sb = new StringBuilder();
		sb.append("<html><body>");
		sb.append("<table width=270 cellpadding=0 cellspacing=0><tr><td align=center>");
		sb.append("<br><font color=\"LEVEL\">- Class Master -</font><br1>");
		if (stage != null)
		{
			sb.append("<font color=\"999999\">").append(stage.getDisplayName()).append("</font><br1>");
		}
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
			sb.append("(You may have already completed all your class transfers.)</font>");
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
		
		// Alternative Class Transfer Challenge: a trial must be cleared before the transfer is offered.
		if (ClassTransferChallengeConfig.ENABLED && (stage != null))
		{
			if (!ClassTransferChallengeManager.getInstance().isTransferUnlocked(player, stage))
			{
				sb.append("<font color=\"CCCCCC\">Before I grant your new class, you must prove yourself in a trial.<br1>It takes place in a private hall - no long journey needed.</font><br><br>");
				sb.append("<button value=\"Alternative Class Transfer Challenge\" action=\"bypass -h Script ClassTransferChallenge info\" width=230 height=25 back=\"L2UI_CT1.Button_DF_Down\" fore=\"L2UI_CT1.Button_DF\"><br>");
				sb.append("<font color=\"999999\">Prefer the old ways? The village masters still offer the classic class transfer quests.</font>");
				sb.append("</td></tr></table></body></html>");
				return sb.toString();
			}
			if (ClassTransferChallengeCompletionTable.getInstance().hasValidCompletion(player, stage))
			{
				sb.append("<font color=\"55FF55\">You have cleared your trial.</font><br1>");
			}
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
	
	/**
	 * @param player the player
	 * @return the tier (1, 2 or 3) of the player's next class transfer, 0 if the current class has none
	 */
	private int getTier(Player player)
	{
		return ClassTransferData.getInstance().getNextTier(player.getPlayerClass().getId());
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