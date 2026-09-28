package custom.ArenaMaster;

import java.util.logging.Level;
import java.util.logging.Logger;

import org.l2jmobius.commons.util.Rnd;
import org.l2jmobius.gameserver.config.RatesConfig;
import org.l2jmobius.gameserver.data.xml.ItemData;
import org.l2jmobius.gameserver.data.xml.NpcData;
import org.l2jmobius.gameserver.data.xml.SkillData;
import org.l2jmobius.gameserver.managers.ArenaSurvivalManager;
import org.l2jmobius.gameserver.managers.InstanceManager;
import org.l2jmobius.gameserver.model.actor.Npc;
import org.l2jmobius.gameserver.model.actor.Player;
import org.l2jmobius.gameserver.model.actor.instance.Monster;
import org.l2jmobius.gameserver.model.actor.templates.NpcTemplate;
import org.l2jmobius.gameserver.model.instancezone.Instance;
import org.l2jmobius.gameserver.model.item.ItemTemplate;
import org.l2jmobius.gameserver.model.item.enums.ItemProcessType;
import org.l2jmobius.gameserver.model.script.Quest;
import org.l2jmobius.gameserver.model.skill.Skill;
import org.l2jmobius.gameserver.model.variables.PlayerVariables;
import org.l2jmobius.gameserver.network.serverpackets.NpcHtmlMessage;

/**
 * Death-guard snapshotting and restoration for the arena run now lives in {@link ArenaSurvivalManager} (core), since {@code Player#doDie()} needs to call it directly and core code can't reference a datapack script class.
 */
public class ArenaMaster extends Quest
{
	private static final Logger LOGGER = Logger.getLogger(ArenaMaster.class.getName());
	private static final int NOBLESSE_BLESSING_SKILL_ID = 1323;
	
	/** Entry fee of a run, which is also the price of a quick run. */
	private static final int ENTRY_FEE_ID = 6393; // Event - Glittering Medal
	private static final long ENTRY_FEE_AMOUNT = 1; // Change this to charge more than 1
	
	// Window size and palette (same card style as the hotzone teleporter).
	private static final int WINDOW_WIDTH = 440;
	private static final int WINDOW_HEIGHT = 700;
	private static final int CONTENT_WIDTH = 400;
	private static final String BG_BAR = "1A1A1A";
	private static final String BG_CARD = "111111";
	private static final String COLOR_TITLE = "FFFFFF";
	private static final String COLOR_VALUE = "E6C35C";
	private static final String COLOR_POSITIVE = "66CC66";
	private static final String COLOR_NEGATIVE = "FF6666";
	private static final String COLOR_MUTED = "A0A0A0";
	private static final String COLOR_HINT = "707070";
	
	/** Waves whose payout is shown in the reward table. */
	private static final int[] REWARD_TABLE_WAVES =
	{
		5,
		10,
		15,
		20,
		25,
		30
	};

	public ArenaMaster()
	{
		super(-1);
		
		LOGGER.info("ArenaMaster: Constructor called. ARENA_MASTER_NPC_ID = " + RatesConfig.ARENA_MASTER_NPC_ID);
		
		addStartNpc(RatesConfig.ARENA_MASTER_NPC_ID);
		addFirstTalkId(RatesConfig.ARENA_MASTER_NPC_ID);
		addTalkId(RatesConfig.ARENA_MASTER_NPC_ID);
		
		LOGGER.info("ArenaMaster: Registered as start/firstTalk/talk NPC for ID " + RatesConfig.ARENA_MASTER_NPC_ID);
	}
	
	@Override
	public String onEvent(String event, Npc npc, Player player)
	{
		System.out.println("ArenaMaster caught event: " + event);
		
		if (event.equals("enter_arena"))
		{
			enterArena(player);
			return null;
		}
		
		if (event.startsWith("quick_run"))
		{
			quickRun(player, npc, event.substring(9).trim());
			return null;
		}
		
		return super.onEvent(event, npc, player);
	}
	
	@Override
	public String onFirstTalk(Npc npc, Player player)
	{
		showMenu(player, npc);
		return null;
	}
	
	@Override
	public String onTalk(Npc npc, Player player)
	{
		showMenu(player, npc);
		return null;
	}
	
	private void showMenu(Player player, Npc npc)
	{
		final NpcHtmlMessage html = new NpcHtmlMessage(npc != null ? npc.getObjectId() : 0);
		html.setWindowSize(WINDOW_WIDTH, WINDOW_HEIGHT);
		html.setHtml(buildHtml(player));
		player.sendPacket(html);
	}
	
	private String buildHtml(Player player)
	{
		final PlayerVariables vars = player.getVariables();
		final int bestWave = vars.getInt("ARENA_BEST_WAVE", 0);
		final int bestWaveLevel = vars.getInt("ARENA_BEST_WAVE_LEVEL", 0);
		final long bestReward = getBestReward(player);
		final int bestRewardLevel = vars.getInt("ARENA_BEST_REWARD_LEVEL", 0);
		final int runs = vars.getInt("ARENA_RUNS", 0);
		final long totalReward = vars.getLong("ARENA_TOTAL_REWARD", 0);
		final int lastWave = vars.getInt("ARENA_LAST_WAVE", 0);
		final long medals = player.getInventory().getInventoryItemCount(ENTRY_FEE_ID, -1);
		final long coins = player.getInventory().getInventoryItemCount(RatesConfig.ARENA_CURRENCY_ITEM_ID, -1);
		final String medalName = getItemName(ENTRY_FEE_ID);
		final String coinName = getItemName(RatesConfig.ARENA_CURRENCY_ITEM_ID);
		
		final StringBuilder sb = new StringBuilder(9000);
		sb.append("<html><title>Survival Arena</title><body><center>");
		
		// Header and status.
		sb.append("<table width=").append(CONTENT_WIDTH).append(" cellpadding=0 cellspacing=0 bgcolor=\"").append(BG_BAR).append("\">");
		sb.append("<tr><td height=8></td></tr>");
		sb.append("<tr><td align=center><font name=\"hs12\" color=\"").append(COLOR_TITLE).append("\">Survival Arena</font></td></tr>");
		sb.append("<tr><td height=4></td></tr>");
		sb.append("<tr><td align=center>").append(RatesConfig.ARENA_SYSTEM_ENABLED ? font(COLOR_POSITIVE, "The Arena is OPEN") : font(COLOR_NEGATIVE, "The Arena is CLOSED")).append("</td></tr>");
		sb.append("<tr><td height=8></td></tr></table><br>");
		
		// Records of this character.
		section(sb, "Your Records");
		sb.append("<table width=").append(CONTENT_WIDTH).append(" cellpadding=3 cellspacing=0>");
		row(sb, "Best wave", bestWave > 0 ? font(COLOR_VALUE, "Wave " + bestWave) + (bestWaveLevel > 0 ? font(COLOR_MUTED, " at Lv. " + bestWaveLevel) : "") : font(COLOR_HINT, "No run yet"), false);
		row(sb, "Best payout", bestReward > 0 ? font(COLOR_VALUE, format(bestReward) + " " + coinName) + (bestRewardLevel > 0 ? font(COLOR_MUTED, " at Lv. " + bestRewardLevel) : "") : font(COLOR_HINT, "-"), true);
		row(sb, "Last run", lastWave > 0 ? "Wave " + lastWave : font(COLOR_HINT, "-"), false);
		row(sb, "Runs", String.valueOf(runs), true);
		row(sb, "Total earned", format(totalReward) + " " + coinName, false);
		sb.append("</table><br>");
		
		// What it costs and what they have.
		section(sb, "Wallet");
		sb.append("<table width=").append(CONTENT_WIDTH).append(" cellpadding=3 cellspacing=0>");
		row(sb, medalName, font(medals >= ENTRY_FEE_AMOUNT ? COLOR_TITLE : COLOR_NEGATIVE, format(medals)), false);
		row(sb, coinName, format(coins), true);
		row(sb, "Entry fee", ENTRY_FEE_AMOUNT + " " + medalName, false);
		sb.append("</table><br>");
		
		// Rewards: what each wave pays, and the next record.
		section(sb, "Rewards");
		sb.append("<table width=").append(CONTENT_WIDTH).append(" cellpadding=3 cellspacing=0>");
		boolean shade = false;
		for (int wave : REWARD_TABLE_WAVES)
		{
			row(sb, "Reach wave " + wave, (wave <= bestWave ? font(COLOR_POSITIVE, format(Monster.calculateArenaReward(wave))) : format(Monster.calculateArenaReward(wave))) + " " + coinName, shade);
			shade = !shade;
		}
		sb.append("</table>");
		if (bestWave > 0)
		{
			sb.append(font(COLOR_MUTED, "Beat your record: wave " + (bestWave + 1) + " pays " + format(Monster.calculateArenaReward(bestWave + 1)) + " " + coinName + ".")).append("<br1>");
		}
		sb.append(font(COLOR_HINT, "Every " + RatesConfig.ARENA_MILESTONE_INTERVAL + " waves adds a " + format(Math.round(RatesConfig.ARENA_MILESTONE_BONUS * RatesConfig.ARENA_REWARD_MULTIPLIER)) + " bonus.")).append("<br>");
		
		// How a run works.
		section(sb, "Rules");
		sb.append("<table width=").append(CONTENT_WIDTH).append(" cellpadding=3 cellspacing=0><tr><td width=").append(CONTENT_WIDTH).append(">");
		sb.append(font("CCCCCC", "One challenger, wave after wave, until you fall. The payout is for the last wave you cleared.")).append("<br1>");
		sb.append(font(COLOR_MUTED, "Each wave: +" + RatesConfig.ARENA_OFFENSE_GROWTH_PER_WAVE + "% attack, +" + RatesConfig.ARENA_DEFENSE_GROWTH_PER_WAVE + "% defence, +" + RatesConfig.ARENA_STAT_GROWTH_PER_WAVE + "% HP, +" + RatesConfig.ARENA_VIRTUAL_LEVEL_GROWTH_PER_WAVE + " levels.")).append("<br1>");
		sb.append(font(COLOR_MUTED, "It enrages if a wave takes over " + RatesConfig.ARENA_ENRAGE_TIME + " seconds.")).append("<br1>");
		sb.append(font(COLOR_MUTED, "You enter with Noblesse Blessing; dying here costs no experience or buffs."));
		sb.append("</td></tr></table><br>");
		
		if (RatesConfig.ARENA_SYSTEM_ENABLED)
		{
			sb.append("<button value=\"Enter Arena\" action=\"bypass -h Script ArenaMaster enter_arena\" width=160 height=25 back=\"L2UI_CT1.Button_DF_Down\" fore=\"L2UI_CT1.Button_DF\"><br>");
			
			// Quick run: a medal for the best payout, without fighting.
			section(sb, "Quick Run");
			sb.append("<table width=").append(CONTENT_WIDTH).append(" cellpadding=3 cellspacing=0><tr><td width=").append(CONTENT_WIDTH).append(">");
			if (bestReward > 0)
			{
				sb.append(font("CCCCCC", "Exchange " + ENTRY_FEE_AMOUNT + " " + medalName + " for your best payout: ")).append(font(COLOR_VALUE, format(bestReward) + " " + coinName)).append(font("CCCCCC", " each, without fighting."));
				sb.append("</td></tr></table>");
				sb.append("<table width=").append(CONTENT_WIDTH).append(" cellpadding=3 cellspacing=0><tr>");
				sb.append("<td width=90>").append(font(COLOR_MUTED, "Quick runs:")).append("</td>");
				sb.append("<td width=90><edit var=\"qty\" width=70 height=15 type=number></td>");
				sb.append("<td width=220><button value=\"Exchange\" action=\"bypass -h Script ArenaMaster quick_run $qty\" width=100 height=25 back=\"L2UI_CT1.Button_DF_Down\" fore=\"L2UI_CT1.Button_DF\"></td>");
				sb.append("</tr></table>");
				sb.append(font(COLOR_HINT, "You can afford " + format(medals / ENTRY_FEE_AMOUNT) + ". Beat your record in the Arena to raise the payout."));
			}
			else
			{
				sb.append(font(COLOR_HINT, "Finish a run first: a quick run pays your best payout for a " + medalName + "."));
				sb.append("</td></tr></table>");
			}
		}
		else
		{
			sb.append(font(COLOR_MUTED, "Come back later, challenger."));
		}
		
		sb.append("</center></body></html>");
		return sb.toString();
	}
	
	/**
	 * A quick run: {@link #ENTRY_FEE_AMOUNT} medals each, for the best payout this character ever got from one run.
	 * @param player the player
	 * @param npc the Arena Master
	 * @param quantity how many quick runs, as typed
	 */
	private void quickRun(Player player, Npc npc, String quantity)
	{
		if (!RatesConfig.ARENA_SYSTEM_ENABLED)
		{
			player.sendMessage("The Arena is currently closed.");
			return;
		}
		
		final long bestReward = getBestReward(player);
		if (bestReward <= 0)
		{
			player.sendMessage("Finish an Arena run first.");
			showMenu(player, npc);
			return;
		}
		
		long runs;
		try
		{
			runs = Long.parseLong(quantity);
		}
		catch (NumberFormatException e)
		{
			player.sendMessage("Enter how many quick runs you want.");
			showMenu(player, npc);
			return;
		}
		
		final long affordable = player.getInventory().getInventoryItemCount(ENTRY_FEE_ID, -1) / ENTRY_FEE_AMOUNT;
		if ((runs <= 0) || (affordable <= 0))
		{
			player.sendMessage("You need " + ENTRY_FEE_AMOUNT + " " + getItemName(ENTRY_FEE_ID) + " per quick run.");
			showMenu(player, npc);
			return;
		}
		
		// No more than they can pay for, nor than a stack can hold.
		runs = Math.min(runs, Math.min(affordable, Integer.MAX_VALUE / bestReward));
		if ((runs <= 0) || !player.destroyItemByItemId(ItemProcessType.FEE, ENTRY_FEE_ID, runs * ENTRY_FEE_AMOUNT, npc, true))
		{
			showMenu(player, npc);
			return;
		}
		
		final long reward = runs * bestReward;
		player.addItem(ItemProcessType.REWARD, RatesConfig.ARENA_CURRENCY_ITEM_ID, reward, npc, true);
		player.getVariables().set("ARENA_TOTAL_REWARD", player.getVariables().getLong("ARENA_TOTAL_REWARD", 0) + reward);
		player.sendMessage("Quick run x" + runs + ": you earned " + format(reward) + " " + getItemName(RatesConfig.ARENA_CURRENCY_ITEM_ID) + ".");
		showMenu(player, npc);
	}
	
	/**
	 * @param player a player
	 * @return the most the character ever got from one run. Records from before the payout was kept are worked out from the best wave.
	 */
	private static long getBestReward(Player player)
	{
		final long bestReward = player.getVariables().getLong("ARENA_BEST_REWARD", 0);
		if (bestReward > 0)
		{
			return bestReward;
		}
		
		final int bestWave = player.getVariables().getInt("ARENA_BEST_WAVE", 0);
		return bestWave > 0 ? Monster.calculateArenaReward(bestWave) : 0;
	}
	
	private static void section(StringBuilder sb, String title)
	{
		sb.append("<table width=").append(CONTENT_WIDTH).append(" cellpadding=4 cellspacing=0 bgcolor=\"").append(BG_BAR).append("\"><tr><td><font color=\"LEVEL\">").append(title).append("</font></td></tr></table>");
	}
	
	private static void row(StringBuilder sb, String label, String value, boolean shaded)
	{
		sb.append("<tr><td width=150").append(shaded ? " bgcolor=\"" + BG_CARD + "\"" : "").append(">").append(font(COLOR_MUTED, label)).append("</td>");
		sb.append("<td width=250 align=right").append(shaded ? " bgcolor=\"" + BG_CARD + "\"" : "").append(">").append(value).append("</td></tr>");
	}
	
	private static String font(String color, String text)
	{
		return "<font color=\"" + color + "\">" + text + "</font>";
	}
	
	private static String format(long value)
	{
		return String.format("%,d", value);
	}
	
	private static String getItemName(int itemId)
	{
		final ItemTemplate item = ItemData.getInstance().getTemplate(itemId);
		return item != null ? item.getName().replace("Event - ", "") : ("Item " + itemId);
	}
	
	private void enterArena(Player player)
	{
		
		System.out.println("ArenaMaster: enterArena() called for " + player.getName());
		
		if (!RatesConfig.ARENA_SYSTEM_ENABLED)
		{
			System.out.println("ArenaMaster: ABORT - system disabled via config.");
			player.sendMessage("The Arena is currently closed.");
			return;
		}
		
		if (player.getInstanceId() != 0)
		{
			System.out.println("ArenaMaster: ABORT - player already in instance " + player.getInstanceId());
			player.sendMessage("You cannot enter the Arena from within another instance.");
			return;
		}
		
		if (RatesConfig.ARENA_CHALLENGER_NPC_IDS.isEmpty())
		{
			LOGGER.log(Level.WARNING, "ArenaMaster: ABORT - ARENA_CHALLENGER_NPC_IDS list is empty.");
			player.sendMessage("The Arena Challenger is not configured correctly. Contact an administrator.");
			return;
		}
		
		// Pick a random NPC ID from the configured list
		final int selectedNpcId = RatesConfig.ARENA_CHALLENGER_NPC_IDS.get(Rnd.get(RatesConfig.ARENA_CHALLENGER_NPC_IDS.size()));
		final NpcTemplate challengerTemplate = NpcData.getInstance().getTemplate(selectedNpcId);
		
		if (challengerTemplate == null)
		{
			LOGGER.log(Level.WARNING, "ArenaMaster: ABORT - no NpcTemplate found for selected ARENA_CHALLENGER_NPC_ID = " + selectedNpcId);
			player.sendMessage("The Arena Challenger is not configured correctly. Contact an administrator.");
			return;
		}
		
		// --- ARENA ENTRY FEE LOGIC ---
		// Charged only after every check above passed - it used to be taken first, so a closed arena, an
		// instance check or a misconfigured challenger still cost the player their medal.
		if (player.getInventory().getInventoryItemCount(ENTRY_FEE_ID, -1) < ENTRY_FEE_AMOUNT)
		{
			player.sendMessage("You need " + ENTRY_FEE_AMOUNT + " " + getItemName(ENTRY_FEE_ID) + " to enter the Arena.");
			return; // Stops the code here so they don't teleport
		}
		
		if (!player.destroyItemByItemId(ItemProcessType.FEE, ENTRY_FEE_ID, ENTRY_FEE_AMOUNT, player, true))
		{
			return;
		}
		// -----------------------------
		
		System.out.println("ArenaMaster: Challenger template found (" + challengerTemplate.getName() + " [ID " + selectedNpcId + "]). Creating instance...");
		
		final Instance instance = InstanceManager.getInstance().createDynamicInstance(0);
		final int instanceId = instance.getId();
		// Anyone removed from the instance without a teleport of their own (e.g. relogging after it was cleaned up) lands back where they entered from,
		// not at the arena coordinates in the open world.
		instance.setExitLoc(player.getLocation());
		
		System.out.println("ArenaMaster: Dynamic instance created with ID " + instanceId + ". Teleporting player to (" + RatesConfig.ARENA_X + ", " + RatesConfig.ARENA_Y + ", " + RatesConfig.ARENA_Z + ")");
		
		player.teleToLocation(RatesConfig.ARENA_X, RatesConfig.ARENA_Y, RatesConfig.ARENA_Z, 0, instanceId);
		player.sendMessage("You have entered the Survival Arena. Good luck.");

		// Retail Noblesse Blessing: buffs/debuffs survive death and resurrection for the next
		// hour. Complements rather than replaces ArenaSurvivalManager's own snapshot/restore -
		// Noblesse Blessing doesn't touch XP/SP loss, which the death guard still covers.
		final Skill noblesseBlessing = SkillData.getInstance().getSkill(NOBLESSE_BLESSING_SKILL_ID, 1);
		if (noblesseBlessing != null)
		{
			noblesseBlessing.applyEffects(player, player);
		}

		ArenaSurvivalManager.getInstance().startDeathGuard(player, instanceId);

		System.out.println("ArenaMaster: Spawning challenger " + challengerTemplate.getName() + " in instance " + instanceId);

		final Monster challenger = new Monster(challengerTemplate);
		challenger.setInstanceId(instanceId);
		challenger.setXYZ(RatesConfig.ARENA_X + 100, RatesConfig.ARENA_Y, RatesConfig.ARENA_Z);
		// Tag this specific monster as the Arena Boss before it spawns
		challenger.getVariables().set("IS_ARENA_CHALLENGER", true);
		challenger.spawnMe();

		System.out.println("ArenaMaster: Challenger " + challengerTemplate.getName() + " spawned successfully. Setup complete for " + player.getName());
	}

	public static void main(String[] args)
	{
		new ArenaMaster();
		LOGGER.info("---- ArenaMaster Script Loaded ----");
	}
}