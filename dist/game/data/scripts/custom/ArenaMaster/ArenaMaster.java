package custom.ArenaMaster;

import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.l2jmobius.commons.threads.ThreadPool;
import org.l2jmobius.commons.util.Rnd;
import org.l2jmobius.gameserver.config.RatesConfig;
import org.l2jmobius.gameserver.data.xml.ItemData;
import org.l2jmobius.gameserver.data.xml.NpcData;
import org.l2jmobius.gameserver.managers.AdventurersGuideManager;
import org.l2jmobius.gameserver.managers.ArenaSurvivalManager;
import org.l2jmobius.gameserver.managers.ArenaSurvivalManager.HallEntry;
import org.l2jmobius.gameserver.model.actor.Npc;
import org.l2jmobius.gameserver.model.actor.Player;
import org.l2jmobius.gameserver.model.actor.instance.Monster;
import org.l2jmobius.gameserver.model.actor.templates.NpcTemplate;
import org.l2jmobius.gameserver.model.guide.GuideLesson;
import org.l2jmobius.gameserver.model.item.ItemTemplate;
import org.l2jmobius.gameserver.model.item.enums.ItemProcessType;
import org.l2jmobius.gameserver.model.script.Quest;
import org.l2jmobius.gameserver.model.variables.PlayerVariables;
import org.l2jmobius.gameserver.network.serverpackets.NpcHtmlMessage;
import org.l2jmobius.gameserver.util.Broadcast;

/**
 * The Arena Master: the Survival Arena's window. A live run is staked with Champion Medals (the payout is multiplied by the medals staked) and may start from a checkpoint below the player's best wave; a quick run pays half the best payout per medal without fighting. The window also
 * shows the Arena Rush, the daily bonus and the Hall of Champions.
 * <p>
 * Everything a run does once it starts lives in {@link ArenaSurvivalManager} (core), since {@code Player#doDie()} and {@code Monster} need to call it and core code can't reference a datapack script class. This script also schedules the Arena Rush.
 */
public class ArenaMaster extends Quest
{
	private static final Logger LOGGER = Logger.getLogger(ArenaMaster.class.getName());

	/** The stake of a live run, and the price of a quick run. */
	private static final int MEDAL_ID = 6393; // Champion Medal

	/** How long before an Arena Rush it is announced. */
	private static final int RUSH_WARNING_MINUTES = 5;

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

		addStartNpc(RatesConfig.ARENA_MASTER_NPC_ID);
		addFirstTalkId(RatesConfig.ARENA_MASTER_NPC_ID);
		addTalkId(RatesConfig.ARENA_MASTER_NPC_ID);

		// A reloaded script finds the Rush already scheduled by the one before it.
		if (RatesConfig.ARENA_SYSTEM_ENABLED && RatesConfig.ARENA_RUSH_ENABLED && (RatesConfig.ARENA_RUSH_BONUS_PERCENT > 0) && (ArenaSurvivalManager.getInstance().getNextRushTime() < System.currentTimeMillis()))
		{
			scheduleNextRush();
		}
	}

	@Override
	public String onEvent(String event, Npc npc, Player player)
	{
		if (event.startsWith("enter_arena"))
		{
			final String[] params = event.substring("enter_arena".length()).trim().split("\\s+");
			enterArena(player, npc, params.length > 0 ? params[0] : "", params.length > 1 ? params[1] : "");
			return null;
		}

		if (event.startsWith("quick_run"))
		{
			quickRun(player, npc, event.substring("quick_run".length()).trim());
			return null;
		}

		if (event.equals("gm_rush") && player.isGM())
		{
			ArenaSurvivalManager.getInstance().startRush(RatesConfig.ARENA_RUSH_MINUTES);
			showMenu(player, npc);
			return null;
		}

		return super.onEvent(event, npc, player);
	}

	@Override
	public String onFirstTalk(Npc npc, Player player)
	{
		showMenu(player, npc);
		AdventurersGuideManager.getInstance().discover(player, GuideLesson.ARENA);
		return null;
	}

	@Override
	public String onTalk(Npc npc, Player player)
	{
		showMenu(player, npc);
		return null;
	}

	// =========================================================================
	// Arena Rush
	// =========================================================================

	/**
	 * Schedules the next Arena Rush {@code ArenaRushIntervalHours} from now, give or take a quarter, with a warning {@link #RUSH_WARNING_MINUTES} minutes before.
	 */
	private void scheduleNextRush()
	{
		final long interval = TimeUnit.HOURS.toMillis(RatesConfig.ARENA_RUSH_INTERVAL_HOURS);
		final long delay = Math.max(TimeUnit.MINUTES.toMillis(RUSH_WARNING_MINUTES + 1), interval + Rnd.get(-interval / 4, interval / 4));
		ArenaSurvivalManager.getInstance().setNextRushTime(System.currentTimeMillis() + delay);

		ThreadPool.schedule(new Runnable()
		{
			@Override
			public void run()
			{
				Broadcast.toAllOnlinePlayers("Survival Arena: an Arena Rush starts in " + RUSH_WARNING_MINUTES + " minutes - every wave will pay " + RatesConfig.ARENA_RUSH_BONUS_PERCENT + "% more for " + RatesConfig.ARENA_RUSH_MINUTES + " minutes.");
			}
		}, delay - TimeUnit.MINUTES.toMillis(RUSH_WARNING_MINUTES));

		ThreadPool.schedule(new Runnable()
		{
			@Override
			public void run()
			{
				ArenaSurvivalManager.getInstance().startRush(RatesConfig.ARENA_RUSH_MINUTES);
				scheduleNextRush();
			}
		}, delay);
	}

	// =========================================================================
	// Window
	// =========================================================================

	private void showMenu(Player player, Npc npc)
	{
		final NpcHtmlMessage html = new NpcHtmlMessage(npc != null ? npc.getObjectId() : 0);
		html.setWindowSize(WINDOW_WIDTH, WINDOW_HEIGHT);
		html.setHtml(buildHtml(player));
		player.sendPacket(html);
	}

	private String buildHtml(Player player)
	{
		final ArenaSurvivalManager manager = ArenaSurvivalManager.getInstance();
		final PlayerVariables vars = player.getVariables();
		final int bestWave = vars.getInt("ARENA_BEST_WAVE", 0);
		final int bestWaveLevel = vars.getInt("ARENA_BEST_WAVE_LEVEL", 0);
		final long bestReward = ArenaSurvivalManager.getBestReward(player);
		final long quickReward = ArenaSurvivalManager.getQuickRunReward(player);
		final int runs = vars.getInt("ARENA_RUNS", 0);
		final long totalReward = vars.getLong("ARENA_TOTAL_REWARD", 0);
		final int lastWave = vars.getInt("ARENA_LAST_WAVE", 0);
		final long medals = player.getInventory().getInventoryItemCount(MEDAL_ID, -1);
		final long coins = player.getInventory().getInventoryItemCount(RatesConfig.ARENA_CURRENCY_ITEM_ID, -1);
		final String medalName = getItemName(MEDAL_ID);
		final String coinName = getItemName(RatesConfig.ARENA_CURRENCY_ITEM_ID);
		final double liveMultiplier = RatesConfig.ARENA_LIVE_REWARD_MULTIPLIER;

		final StringBuilder sb = new StringBuilder(14000);
		sb.append("<html><title>Survival Arena</title><body><center>");

		// Header and status.
		sb.append("<table width=").append(CONTENT_WIDTH).append(" cellpadding=0 cellspacing=0 bgcolor=\"").append(BG_BAR).append("\">");
		sb.append("<tr><td height=8></td></tr>");
		sb.append("<tr><td align=center><font name=\"hs12\" color=\"").append(COLOR_TITLE).append("\">Survival Arena</font></td></tr>");
		sb.append("<tr><td height=4></td></tr>");
		sb.append("<tr><td align=center>").append(RatesConfig.ARENA_SYSTEM_ENABLED ? font(COLOR_POSITIVE, "The Arena is OPEN") : font(COLOR_NEGATIVE, "The Arena is CLOSED")).append("</td></tr>");
		if (manager.isRushActive())
		{
			sb.append("<tr><td align=center>").append(font(COLOR_VALUE, "ARENA RUSH: +" + RatesConfig.ARENA_RUSH_BONUS_PERCENT + "% on every wave for " + minutes(manager.getRushRemainingSeconds()) + "!")).append("</td></tr>");
		}
		else if (RatesConfig.ARENA_RUSH_ENABLED && (manager.getNextRushTime() > System.currentTimeMillis()))
		{
			sb.append("<tr><td align=center>").append(font(COLOR_MUTED, "Next Arena Rush in about " + minutes((manager.getNextRushTime() - System.currentTimeMillis()) / 1000) + ".")).append("</td></tr>");
		}
		if (manager.isDailyAvailable(player))
		{
			sb.append("<tr><td align=center>").append(font(COLOR_POSITIVE, "Your first run today pays +" + RatesConfig.ARENA_DAILY_BONUS_PERCENT + "% and a Sealed Cache.")).append("</td></tr>");
		}
		sb.append("<tr><td height=8></td></tr></table><br>");

		// Records of this character.
		section(sb, "Your Records");
		sb.append("<table width=").append(CONTENT_WIDTH).append(" cellpadding=3 cellspacing=0>");
		row(sb, "Best wave", bestWave > 0 ? font(COLOR_VALUE, "Wave " + bestWave) + (bestWaveLevel > 0 ? font(COLOR_MUTED, " at Lv. " + bestWaveLevel) : "") : font(COLOR_HINT, "No run yet"), false);
		row(sb, "Best payout", bestReward > 0 ? font(COLOR_VALUE, format(bestReward)) + font(COLOR_MUTED, " per medal") : font(COLOR_HINT, "-"), true);
		row(sb, "Last run", lastWave > 0 ? "Wave " + lastWave : font(COLOR_HINT, "-"), false);
		row(sb, "Runs", String.valueOf(runs), true);
		row(sb, "Total earned", format(totalReward) + " " + coinName, false);
		row(sb, medalName, font(medals > 0 ? COLOR_TITLE : COLOR_NEGATIVE, format(medals)), true);
		row(sb, coinName, format(coins), false);
		sb.append("</table><br>");

		if (RatesConfig.ARENA_SYSTEM_ENABLED)
		{
			// Live run: stake and checkpoint.
			section(sb, "Live Run");
			sb.append("<table width=").append(CONTENT_WIDTH).append(" cellpadding=3 cellspacing=0><tr><td width=").append(CONTENT_WIDTH).append(">");
			sb.append(font("CCCCCC", "Stake " + medalName + "s: the whole payout is multiplied by the medals you stake (up to " + RatesConfig.ARENA_MAX_STAKE + ")."));
			sb.append("</td></tr></table>");

			final List<Integer> startWaves = ArenaSurvivalManager.getStartWaves(player);
			final StringBuilder list = new StringBuilder();
			for (int startWave : startWaves)
			{
				list.append(list.length() > 0 ? ";" : "").append(startWave + 1);
			}
			sb.append("<table width=").append(CONTENT_WIDTH).append(" cellpadding=3 cellspacing=0><tr>");
			sb.append("<td width=60>").append(font(COLOR_MUTED, "Medals:")).append("</td>");
			sb.append("<td width=70><edit var=\"stake\" width=55 height=15 type=number></td>");
			sb.append("<td width=90>").append(font(COLOR_MUTED, "Start at wave:")).append("</td>");
			sb.append("<td width=70><combobox width=55 var=\"start\" list=\"").append(list).append("\"></td>");
			sb.append("<td width=110><button value=\"Enter Arena\" action=\"bypass -h Script ArenaMaster enter_arena $stake $start\" width=100 height=25 back=\"L2UI_CT1.Button_DF_Down\" fore=\"L2UI_CT1.Button_DF\"></td>");
			sb.append("</tr></table>");
			if (startWaves.size() > 1)
			{
				sb.append(font(COLOR_HINT, "Starting later skips the easy waves; they are paid at " + RatesConfig.ARENA_SKIP_AHEAD_PAY_PERCENT + "%.")).append("<br1>");
			}
			else
			{
				sb.append(font(COLOR_HINT, "Clear wave 10 to unlock starting at a later wave.")).append("<br1>");
			}
			final int breakEven = ArenaSurvivalManager.getBreakEvenWave(quickReward);
			if (breakEven > 0)
			{
				sb.append(font(COLOR_MUTED, "A live run beats a quick run from wave " + breakEven + " - before omens, swift clears and boons.")).append("<br1>");
			}
			sb.append(font(COLOR_HINT, "You are paid for every wave you clear, whether you fall or leave. A disconnect forfeits the run.")).append("<br>");

			// Rewards per medal.
			section(sb, "Rewards per Medal");
			sb.append("<table width=").append(CONTENT_WIDTH).append(" cellpadding=3 cellspacing=0>");
			boolean shade = false;
			for (int wave : REWARD_TABLE_WAVES)
			{
				final String value = format(Math.round(Monster.calculateArenaReward(wave) * liveMultiplier)) + " " + coinName;
				row(sb, "Clear wave " + wave, wave <= bestWave ? font(COLOR_POSITIVE, value) : value, shade);
				shade = !shade;
			}
			sb.append("</table>");
			sb.append(font(COLOR_HINT, "Plain wave values; omens, swift clears, boons, the Rush and the daily bonus add to them.")).append("<br>");

			// How a run works.
			section(sb, "The Gauntlet");
			sb.append("<table width=").append(CONTENT_WIDTH).append(" cellpadding=3 cellspacing=0><tr><td width=").append(CONTENT_WIDTH).append(">");
			sb.append(font("CCCCCC", "One challenger, wave after wave. It heals and grows stronger with every wave: +" + RatesConfig.ARENA_OFFENSE_GROWTH_PER_WAVE + "% attack, +" + RatesConfig.ARENA_DEFENSE_GROWTH_PER_WAVE + "% defence, +" + RatesConfig.ARENA_STAT_GROWTH_PER_WAVE + "% HP.")).append("<br1>");
			sb.append(font(COLOR_VALUE, "Omens: ")).append(font(COLOR_MUTED, "from wave " + RatesConfig.ARENA_OMEN_START_WAVE + ", waves may roll an omen - Glass Cannon, Vampiric, Frenzy, Haste... - that pays more. Every " + RatesConfig.ARENA_MILESTONE_INTERVAL + "th wave is a Champion Wave with two.")).append("<br1>");
			sb.append(font(COLOR_VALUE, "Milestones: ")).append(font(COLOR_MUTED, "after each Champion Wave the challenger freezes for a breather, you pick one of three boons for the rest of the run" + (RatesConfig.ARENA_MILESTONE_CACHE ? " and get a Sealed Cache" : "") + ".")).append("<br1>");
			sb.append(font(COLOR_VALUE, "Swift clears: ")).append(font(COLOR_MUTED, "a wave cleared within " + RatesConfig.ARENA_SWIFT_SECONDS + " seconds pays +" + RatesConfig.ARENA_SWIFT_BONUS_PERCENT + "%, more for every swift clear in a row.")).append("<br1>");
			sb.append(font(COLOR_MUTED, "It enrages if a wave takes over " + RatesConfig.ARENA_ENRAGE_TIME + " seconds. You enter with Noblesse Blessing; dying here costs no experience or buffs. Type .arena to see your run."));
			sb.append("</td></tr></table><br>");

			// Quick run.
			section(sb, "Quick Run");
			sb.append("<table width=").append(CONTENT_WIDTH).append(" cellpadding=3 cellspacing=0><tr><td width=").append(CONTENT_WIDTH).append(">");
			if (quickReward > 0)
			{
				sb.append(font("CCCCCC", "Exchange " + medalName + "s without fighting: ")).append(font(COLOR_VALUE, format(quickReward) + " " + coinName)).append(font("CCCCCC", " each (" + RatesConfig.ARENA_QUICK_RUN_PERCENT + "% of your best payout)."));
				sb.append("</td></tr></table>");
				sb.append("<table width=").append(CONTENT_WIDTH).append(" cellpadding=3 cellspacing=0><tr>");
				sb.append("<td width=90>").append(font(COLOR_MUTED, "Quick runs:")).append("</td>");
				sb.append("<td width=90><edit var=\"qty\" width=70 height=15 type=number></td>");
				sb.append("<td width=220><button value=\"Exchange\" action=\"bypass -h Script ArenaMaster quick_run $qty\" width=100 height=25 back=\"L2UI_CT1.Button_DF_Down\" fore=\"L2UI_CT1.Button_DF\"></td>");
				sb.append("</tr></table>");
				sb.append(font(COLOR_HINT, "You can afford " + format(medals) + ". Staking the same medals on a live run pays the full payout instead.")).append("<br>");
			}
			else
			{
				sb.append(font(COLOR_HINT, "Finish a run first: a quick run pays " + RatesConfig.ARENA_QUICK_RUN_PERCENT + "% of your best payout per " + medalName + "."));
				sb.append("</td></tr></table><br>");
			}
		}
		else
		{
			sb.append(font(COLOR_MUTED, "Come back later, challenger.")).append("<br>");
		}

		// Hall of Champions.
		section(sb, "Hall of Champions");
		final List<HallEntry> hall = manager.getHall();
		if (hall.isEmpty())
		{
			sb.append(font(COLOR_HINT, "No champion yet. Be the first.")).append("<br>");
		}
		else
		{
			sb.append("<table width=").append(CONTENT_WIDTH).append(" cellpadding=2 cellspacing=0>");
			for (int i = 0; i < hall.size(); i++)
			{
				final HallEntry entry = hall.get(i);
				final boolean shaded = (i % 2) == 1;
				final String bg = shaded ? " bgcolor=\"" + BG_CARD + "\"" : "";
				sb.append("<tr><td width=25").append(bg).append(">").append(font(i == 0 ? COLOR_VALUE : COLOR_MUTED, (i + 1) + ".")).append("</td>");
				sb.append("<td width=185").append(bg).append(">").append(font(entry.getName().equals(player.getName()) ? COLOR_POSITIVE : COLOR_TITLE, entry.getName())).append(font(COLOR_HINT, " Lv. " + entry.getLevel())).append("</td>");
				sb.append("<td width=110").append(bg).append(">").append(font(COLOR_MUTED, entry.getClassName())).append("</td>");
				sb.append("<td width=80 align=right").append(bg).append(">").append(font(COLOR_VALUE, "Wave " + entry.getWave())).append("</td></tr>");
			}
			sb.append("</table><br>");
		}

		if (player.isGM() && RatesConfig.ARENA_SYSTEM_ENABLED)
		{
			sb.append("<button value=\"GM: start a Rush\" action=\"bypass -h Script ArenaMaster gm_rush\" width=140 height=21 back=\"L2UI_CT1.Button_DF_Down\" fore=\"L2UI_CT1.Button_DF\">");
		}

		sb.append("</center></body></html>");
		return sb.toString();
	}

	// =========================================================================
	// Live run and quick run
	// =========================================================================

	/**
	 * Takes the stake and sends the player into the arena.
	 * @param player the player
	 * @param npc the Arena Master
	 * @param stakeText medals to stake, as typed
	 * @param startText the wave to start at, as picked (1 for the start)
	 */
	private void enterArena(Player player, Npc npc, String stakeText, String startText)
	{
		final ArenaSurvivalManager manager = ArenaSurvivalManager.getInstance();
		if (!RatesConfig.ARENA_SYSTEM_ENABLED)
		{
			player.sendMessage("The Arena is currently closed.");
			return;
		}

		if (player.getInstanceId() != 0)
		{
			player.sendMessage("You cannot enter the Arena from within another instance.");
			return;
		}

		if (manager.getRunByPlayer(player.getObjectId()) != null)
		{
			player.sendMessage("You are already in an Arena run.");
			return;
		}

		if (player.isDead() || player.isInCombat() || player.isInOlympiadMode())
		{
			player.sendMessage("You can't enter the Arena right now.");
			return;
		}

		if (RatesConfig.ARENA_CHALLENGER_NPC_IDS.isEmpty())
		{
			LOGGER.log(Level.WARNING, "ArenaMaster: ArenaChallengerNpcIds is empty.");
			player.sendMessage("The Arena Challenger is not configured correctly. Contact an administrator.");
			return;
		}

		final int selectedNpcId = RatesConfig.ARENA_CHALLENGER_NPC_IDS.get(Rnd.get(RatesConfig.ARENA_CHALLENGER_NPC_IDS.size()));
		final NpcTemplate challengerTemplate = NpcData.getInstance().getTemplate(selectedNpcId);
		if (challengerTemplate == null)
		{
			LOGGER.log(Level.WARNING, "ArenaMaster: no NpcTemplate found for arena challenger " + selectedNpcId);
			player.sendMessage("The Arena Challenger is not configured correctly. Contact an administrator.");
			return;
		}

		final int stake = parse(stakeText, 1);
		if ((stake < 1) || (stake > RatesConfig.ARENA_MAX_STAKE))
		{
			player.sendMessage("You can stake 1 to " + RatesConfig.ARENA_MAX_STAKE + " medals on a run.");
			showMenu(player, npc);
			return;
		}

		final int startWave = parse(startText, 1) - 1;
		if (!ArenaSurvivalManager.getStartWaves(player).contains(startWave))
		{
			player.sendMessage("You can't start at that wave yet.");
			showMenu(player, npc);
			return;
		}

		// Charged only after every check above passed.
		if (player.getInventory().getInventoryItemCount(MEDAL_ID, -1) < stake)
		{
			player.sendMessage("You need " + stake + " " + getItemName(MEDAL_ID) + (stake == 1 ? "" : "s") + " to stake on this run.");
			showMenu(player, npc);
			return;
		}
		if (!player.destroyItemByItemId(ItemProcessType.FEE, MEDAL_ID, stake, npc, true))
		{
			return;
		}

		manager.startRun(player, challengerTemplate, stake, startWave);
	}

	/**
	 * A quick run: one medal each, for {@code ArenaQuickRunPercent} of the best payout this character ever got from one run.
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

		final long reward = ArenaSurvivalManager.getQuickRunReward(player);
		if (reward <= 0)
		{
			player.sendMessage("Finish an Arena run first.");
			showMenu(player, npc);
			return;
		}

		long runs = parse(quantity, 0);
		final long affordable = player.getInventory().getInventoryItemCount(MEDAL_ID, -1);
		if ((runs <= 0) || (affordable <= 0))
		{
			player.sendMessage("You need a " + getItemName(MEDAL_ID) + " per quick run.");
			showMenu(player, npc);
			return;
		}

		// No more than they can pay for, nor than a stack can hold.
		runs = Math.min(runs, Math.min(affordable, Integer.MAX_VALUE / reward));
		if ((runs <= 0) || !player.destroyItemByItemId(ItemProcessType.FEE, MEDAL_ID, runs, npc, true))
		{
			showMenu(player, npc);
			return;
		}

		final long total = runs * reward;
		player.addItem(ItemProcessType.REWARD, RatesConfig.ARENA_CURRENCY_ITEM_ID, total, npc, true);
		player.getVariables().set("ARENA_TOTAL_REWARD", player.getVariables().getLong("ARENA_TOTAL_REWARD", 0) + total);
		player.sendMessage("Quick run x" + runs + ": you earned " + format(total) + " " + getItemName(RatesConfig.ARENA_CURRENCY_ITEM_ID) + ".");
		showMenu(player, npc);
	}

	// =========================================================================
	// Helpers
	// =========================================================================

	/**
	 * @return the number typed, or {@code fallback} for an empty box (some clients send the variable name itself)
	 */
	private static int parse(String text, int fallback)
	{
		if ((text == null) || text.isEmpty() || text.startsWith("$"))
		{
			return fallback;
		}
		try
		{
			return Integer.parseInt(text.trim());
		}
		catch (NumberFormatException e)
		{
			return -1;
		}
	}

	private static String minutes(long seconds)
	{
		final long minutes = Math.max(1, (seconds + 59) / 60);
		return minutes >= 120 ? (minutes / 60) + " hours" : minutes + (minutes == 1 ? " minute" : " minutes");
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

	public static void main(String[] args)
	{
		new ArenaMaster();
		LOGGER.info("---- ArenaMaster Script Loaded ----");
	}
}
