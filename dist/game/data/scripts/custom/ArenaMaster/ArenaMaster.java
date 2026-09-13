package custom.ArenaMaster;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Future;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.l2jmobius.commons.threads.ThreadPool;
import org.l2jmobius.commons.util.Rnd;
import org.l2jmobius.gameserver.config.RatesConfig;
import org.l2jmobius.gameserver.data.xml.NpcData;
import org.l2jmobius.gameserver.managers.InstanceManager;
import org.l2jmobius.gameserver.model.actor.Npc;
import org.l2jmobius.gameserver.model.actor.Player;
import org.l2jmobius.gameserver.model.actor.instance.Monster;
import org.l2jmobius.gameserver.model.actor.templates.NpcTemplate;
import org.l2jmobius.gameserver.model.instancezone.Instance;
import org.l2jmobius.gameserver.model.item.enums.ItemProcessType;
import org.l2jmobius.gameserver.model.script.Quest;
import org.l2jmobius.gameserver.model.skill.BuffInfo;
import org.l2jmobius.gameserver.model.skill.Skill;

public class ArenaMaster extends Quest
{
	private static final Logger LOGGER = Logger.getLogger(ArenaMaster.class.getName());
	
	/** How often (ms) the death guard refreshes its "last known good" snapshot / checks for death. */
	private static final int DEATH_GUARD_INTERVAL_MS = 1000;
	
	/** One entry per player currently inside the arena, tracked by player object ID. */
	private final Map<Integer, ArenaDeathGuard> _deathGuards = new ConcurrentHashMap<>();
	
	/**
	 * Continuously-refreshed snapshot of a player's XP/SP/active buffs while alive inside the arena, used to undo the death penalty (XP/SP loss + buff wipe) the instant death is detected. Since buffs are already gone by the time isDead() can be observed, this snapshots BEFORE death (every tick,
	 * overwriting the previous one) rather than trying to catch the moment of death itself.
	 */
	private static class ArenaDeathGuard
	{
		private long snapshotExp;
		private long snapshotSp;
		private List<Skill> snapshotBuffs = new ArrayList<>();
		private boolean restoredThisDeath;
		private Future<?> task;
		
		private void snapshot(Player player)
		{
			snapshotExp = player.getExp();
			snapshotSp = player.getSp();
			
			final List<Skill> buffs = new ArrayList<>();
			// TODO: verify getEffectList()/getEffects()/BuffInfo match your Creature/Player API -
			// BuffInfo is guessed to live alongside the confirmed-working EffectType import
			// (org.l2jmobius.gameserver.model.effects), but the exact enumeration method may differ.
			for (BuffInfo info : player.getEffectList().getEffects())
			{
				final Skill skill = info.getSkill();
				if ((skill != null) && !skill.isPassive() && !skill.isDebuff())
				{
					buffs.add(skill);
				}
			}
			snapshotBuffs = buffs;
		}
	}
	
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
		
		return super.onEvent(event, npc, player);
	}
	
	@Override
	public String onFirstTalk(Npc npc, Player player)
	{
		System.out.println("ArenaMaster: onFirstTalk triggered by " + player.getName() + " on NPC " + npc.getId());
		return buildHtml(player);
	}
	
	@Override
	public String onTalk(Npc npc, Player player)
	{
		System.out.println("ArenaMaster: onTalk triggered by " + player.getName() + " on NPC " + npc.getId());
		return buildHtml(player);
	}
	
	private String buildHtml(Player player)
	{
		final StringBuilder sb = new StringBuilder();
		sb.append("<html><body>");
		sb.append("<table width=270 cellpadding=0 cellspacing=0><tr><td align=center>");
		
		// Header
		sb.append("<br><font color=\"LEVEL\">- Arena Master -</font><br1>");
		sb.append("<img src=\"L2UI.SquareGray\" width=270 height=1><br>");
		
		// Status banner - green panel when open, red panel when closed, instead of a plain text line.
		if (RatesConfig.ARENA_SYSTEM_ENABLED)
		{
			sb.append("<table width=250 cellpadding=4 bgcolor=\"1E3D1E\"><tr><td align=center>");
			sb.append("<font color=\"55FF55\">The Arena is OPEN</font>");
			sb.append("</td></tr></table><br>");
		}
		else
		{
			sb.append("<table width=250 cellpadding=4 bgcolor=\"3D1E1E\"><tr><td align=center>");
			sb.append("<font color=\"FF5555\">The Arena is CLOSED</font>");
			sb.append("</td></tr></table><br>");
		}
		
		// Personal best - shown as a two-column stat box instead of a plain inline sentence.
		final int bestWave = player.getVariables().getInt("ARENA_BEST_WAVE", 0);
		if (bestWave > 0)
		{
			sb.append("<table width=250 cellpadding=4 bgcolor=\"1A1A1A\"><tr>");
			sb.append("<td width=125 align=center><font color=\"999999\">Personal Best</font></td>");
			sb.append("<td width=125 align=center><font color=\"FFFFFF\">Wave ").append(bestWave).append("</font></td>");
			sb.append("</tr></table><br>");
		}
		
		sb.append("<img src=\"L2UI.SquareGray\" width=270 height=1><br1><br>");
		
		if (RatesConfig.ARENA_SYSTEM_ENABLED)
		{
			sb.append("<font color=\"CCCCCC\">Survive as many waves as you can.<br1>Each wave grows harder than the last.</font><br><br>");
			sb.append("<button value=\"Enter Arena\" action=\"bypass -h Script ArenaMaster enter_arena\" width=140 height=21 back=\"L2UI_CT1.Button_DF_Down\" fore=\"L2UI_CT1.Button_DF\">");
		}
		else
		{
			sb.append("<font color=\"999999\">Come back later, challenger.</font>");
		}
		
		sb.append("<br></td></tr></table>");
		sb.append("</body></html>");
		
		return sb.toString();
	}
	
	private void enterArena(Player player)
	{
		
		// --- ARENA ENTRY FEE LOGIC ---
		final int ENTRY_FEE_ID = 6393; // Event Medal
		final long ENTRY_FEE_AMOUNT = 1; // Change this to charge more than 1
		
		// 1. Check if the player has enough of the item
		if (player.getInventory().getInventoryItemCount(ENTRY_FEE_ID, -1) < ENTRY_FEE_AMOUNT)
		{
			player.sendMessage("You need " + ENTRY_FEE_AMOUNT + " Event Medal(s) to enter the Arena.");
			return; // Stops the code here so they don't teleport
		}
		
		// 2. Consume the item from their inventory
		player.destroyItemByItemId(ItemProcessType.FEE, ENTRY_FEE_ID, ENTRY_FEE_AMOUNT, player, true);
		// -----------------------------
		
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
		
		System.out.println("ArenaMaster: Challenger template found (" + challengerTemplate.getName() + " [ID " + selectedNpcId + "]). Creating instance...");
		
		final Instance instance = InstanceManager.getInstance().createDynamicInstance(0);
		final int instanceId = instance.getId();
		
		System.out.println("ArenaMaster: Dynamic instance created with ID " + instanceId + ". Teleporting player to (" + RatesConfig.ARENA_X + ", " + RatesConfig.ARENA_Y + ", " + RatesConfig.ARENA_Z + ")");
		
		player.teleToLocation(RatesConfig.ARENA_X, RatesConfig.ARENA_Y, RatesConfig.ARENA_Z, 0, instanceId);
		player.sendMessage("You have entered the Survival Arena. Good luck.");
		
		startDeathGuard(player, instanceId);
		
		System.out.println("ArenaMaster: Spawning challenger " + challengerTemplate.getName() + " in instance " + instanceId);
		
		final Monster challenger = new Monster(challengerTemplate);
		challenger.setInstanceId(instanceId);
		challenger.setXYZ(RatesConfig.ARENA_X + 100, RatesConfig.ARENA_Y, RatesConfig.ARENA_Z);
		// Tag this specific monster as the Arena Boss before it spawns
		challenger.getVariables().set("IS_ARENA_CHALLENGER", true);
		challenger.spawnMe();
		
		System.out.println("ArenaMaster: Challenger " + challengerTemplate.getName() + " spawned successfully. Setup complete for " + player.getName());
	}
	
	/**
	 * Starts polling {@code player} once per {@link #DEATH_GUARD_INTERVAL_MS} while they remain inside {@code instanceId}: refreshes the "last known good" snapshot while alive, and restores XP/SP/buffs from that snapshot the instant death is detected. Stops itself automatically once the player
	 * leaves the instance (run complete, failed, or manually left) for any reason.
	 * @param player the player entering the arena
	 * @param instanceId the arena instance they were just teleported into
	 */
	private void startDeathGuard(Player player, int instanceId)
	{
		final ArenaDeathGuard guard = new ArenaDeathGuard();
		guard.snapshot(player); // capture an initial baseline immediately, before anything can happen
		_deathGuards.put(player.getObjectId(), guard);
		
		final Runnable task = () ->
		{
			if (player.getInstanceId() != instanceId)
			{
				stopDeathGuard(player);
				return;
			}
			
			if (player.isDead())
			{
				if (!guard.restoredThisDeath)
				{
					restoreOnDeath(player, guard);
					guard.restoredThisDeath = true;
				}
			}
			else
			{
				guard.snapshot(player); // still alive - refresh the baseline
				guard.restoredThisDeath = false; // arm it again for the next death, if any
			}
		};
		
		guard.task = ThreadPool.scheduleAtFixedRate(task, DEATH_GUARD_INTERVAL_MS, DEATH_GUARD_INTERVAL_MS);
	}
	
	private void stopDeathGuard(Player player)
	{
		final ArenaDeathGuard guard = _deathGuards.remove(player.getObjectId());
		if ((guard != null) && (guard.task != null))
		{
			guard.task.cancel(false);
		}
	}
	
	/**
	 * Undoes the XP/SP loss and buff wipe that death just caused, using {@code guard}'s last pre-death snapshot. Revives the player so effects can actually be reapplied.
	 * @param player the player who just died inside the arena
	 * @param guard their snapshot from the moment before death
	 */
	private void restoreOnDeath(Player player, ArenaDeathGuard guard)
	{
		// Compensate for whatever the death penalty took, whatever its formula actually is - we
		// don't need to know it, just cancel out the difference from the last snapshot.
		final long expLost = Math.max(0, guard.snapshotExp - player.getExp());
		final long spLost = Math.max(0, guard.snapshotSp - player.getSp());
		if ((expLost > 0) || (spLost > 0))
		{
			// TODO: verify addExpAndSp(long, long) matches your Player API - this is a very standard
			// L2J method (used for normal kill rewards everywhere), so fairly high confidence.
			player.addExpAndSp(expLost, spLost);
		}
		
		if (player.isDead())
		{
			// TODO: verify doRevive() is the correct call to programmatically resurrect.
			player.doRevive();
		}
		
		for (Skill buff : guard.snapshotBuffs)
		{
			if (buff != null)
			{
				// TODO: verify applyEffects(caster, target) is the right way to silently reapply a
				// buff at full duration - mirrors the pattern already used for the Stat Boost reward
				// in TreasureTowerManager.
				buff.applyEffects(player, player);
			}
		}
		
		player.sendMessage("Your experience and buffs have been preserved.");
	}
	
	public static void main(String[] args)
	{
		new ArenaMaster();
		LOGGER.info("---- ArenaMaster Script Loaded ----");
	}
}