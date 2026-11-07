package org.l2jmobius.gameserver.managers;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Future;
import java.util.logging.Logger;

import org.l2jmobius.commons.threads.ThreadPool;
import org.l2jmobius.commons.util.Rnd;
import org.l2jmobius.gameserver.ai.AttackableAI;
import org.l2jmobius.gameserver.ai.Intention;
import org.l2jmobius.gameserver.config.RatesConfig;
import org.l2jmobius.gameserver.config.custom.InfusedMonsterConfig;
import org.l2jmobius.gameserver.data.xml.ClassListData;
import org.l2jmobius.gameserver.data.xml.ItemData;
import org.l2jmobius.gameserver.data.xml.SkillData;
import org.l2jmobius.gameserver.model.World;
import org.l2jmobius.gameserver.model.actor.Creature;
import org.l2jmobius.gameserver.model.actor.Player;
import org.l2jmobius.gameserver.model.actor.enums.npc.MonsterArchetype;
import org.l2jmobius.gameserver.model.actor.enums.player.TeleportWhereType;
import org.l2jmobius.gameserver.model.actor.holders.player.ClassInfoHolder;
import org.l2jmobius.gameserver.model.actor.instance.Monster;
import org.l2jmobius.gameserver.model.actor.templates.NpcTemplate;
import org.l2jmobius.gameserver.model.arena.ArenaBoon;
import org.l2jmobius.gameserver.model.arena.ArenaOmen;
import org.l2jmobius.gameserver.model.arena.ArenaRun;
import org.l2jmobius.gameserver.model.instancezone.Instance;
import org.l2jmobius.gameserver.model.item.ItemTemplate;
import org.l2jmobius.gameserver.model.item.enums.ItemProcessType;
import org.l2jmobius.gameserver.model.skill.AbnormalVisualEffect;
import org.l2jmobius.gameserver.model.skill.BuffInfo;
import org.l2jmobius.gameserver.model.skill.Skill;
import org.l2jmobius.gameserver.model.skill.enums.SkillFinishType;
import org.l2jmobius.gameserver.model.variables.PlayerVariables;
import org.l2jmobius.gameserver.network.serverpackets.ExSendUIEvent;
import org.l2jmobius.gameserver.network.serverpackets.ExShowScreenMessage;
import org.l2jmobius.gameserver.network.serverpackets.NpcHtmlMessage;
import org.l2jmobius.gameserver.util.Broadcast;

/**
 * The Survival Arena: one challenger, wave after wave, until the player falls or leaves. The Arena Master ({@code custom.ArenaMaster} in the datapack) only shows the window and takes the stake; everything a run does lives here, in core, because {@code Player#doDie()} and {@code Monster}
 * need to call it and core code can never reference a datapack script class.
 * <p>
 * <b>A run</b> ({@link ArenaRun}) pays a pot that grows with every wave cleared: the wave's value ({@link Monster#calculateArenaWaveValue(int)}) times {@link RatesConfig#ARENA_LIVE_REWARD_MULTIPLIER}, plus the wave's omens, a swift clear, Greed and an Arena Rush. The payout is the
 * pot times the Champion Medals staked, plus the daily bonus. It is paid however the run ends - death to anything, or leaving the instance alive - and forfeited only by a disconnect.
 * <p>
 * <b>Omens</b> ({@link ArenaOmen}) twist single waves; every milestone wave is a Champion Wave with two. After a milestone the challenger freezes for a breather in which the player picks one of three <b>boons</b> ({@link ArenaBoon}) for the rest of the run, and gets a Sealed Cache.
 * <p>
 * <b>The death guard</b> keeps a "last known good" snapshot of the player's XP/SP/buffs, refreshed while they are alive, and gives back what the run took once they are safely out of the instance. Restoration is deliberately NOT done the instant death is detected: reviving the player in
 * place, next to a live challenger, let it kill them again. Two paths can trigger it and both are safe to race: {@link #finishArenaRun(Player)}, called once {@link #onPlayerDeath(Player)}'s teleport to town is done, and the guard's own poll noticing the player already left the instance
 * some other way (most often "To Village" on the death screen). {@code _deathGuards.remove()} is atomic, so exactly one of them restores.
 */
public class ArenaSurvivalManager
{
	private static final Logger LOGGER = Logger.getLogger(ArenaSurvivalManager.class.getName());

	/** How often (ms) the death guard refreshes its "last known good" snapshot while the player is alive. */
	private static final int DEATH_GUARD_INTERVAL_MS = 1000;
	private static final int NOBLESSE_BLESSING_SKILL_ID = 1323;
	private static final int ENRAGE_SKILL_ID = 7029;
	private static final int BOONS_OFFERED = 3;
	private static final int HALL_SIZE = 10;
	private static final String HALL_VAR = "ARENA_HALL_";
	/** Player variable set once the character's records were moved to the doubled payouts. */
	private static final String ECONOMY_V2_VAR = "ARENA_ECONOMY_V2";
	public static final String DAILY_DATE_VAR = "ARENA_DAILY_DATE";

	/** One entry per player currently inside the arena, tracked by player object ID. */
	private final Map<Integer, ArenaDeathGuard> _deathGuards = new ConcurrentHashMap<>();
	private final Map<Integer, ArenaRun> _runsByPlayer = new ConcurrentHashMap<>();
	private final Map<Integer, ArenaRun> _runsByInstance = new ConcurrentHashMap<>();

	private volatile long _rushUntil;
	private volatile long _nextRushTime;
	private Future<?> _rushEndTask;

	protected ArenaSurvivalManager()
	{
	}

	// =========================================================================
	// Starting a run
	// =========================================================================

	/**
	 * Sends {@code player} into a new arena instance against {@code template}. The caller (the Arena Master) has already checked the arena is open, that the player is not in an instance or a run, and has taken the stake.
	 * @param player the challenger
	 * @param template the monster to fight
	 * @param stake Champion Medals staked: the payout is multiplied by it
	 * @param startWave the checkpoint to start from (0, or a milestone up to 5 waves below the player's best)
	 */
	public void startRun(Player player, NpcTemplate template, int stake, int startWave)
	{
		final boolean daily = isDailyAvailable(player);
		if (daily)
		{
			player.getVariables().set(DAILY_DATE_VAR, LocalDate.now().toString());
		}

		final double skippedValue = Monster.calculateArenaReward(startWave) * RatesConfig.ARENA_LIVE_REWARD_MULTIPLIER * (RatesConfig.ARENA_SKIP_AHEAD_PAY_PERCENT / 100.0);
		final Instance instance = InstanceManager.getInstance().createDynamicInstance(0);
		final int instanceId = instance.getId();
		// Anyone removed from the instance without a teleport of their own (e.g. relogging after it was cleaned up) lands back where they entered from, not at the arena coordinates in the open world.
		instance.setExitLoc(player.getLocation());

		final ArenaRun run = new ArenaRun(player.getObjectId(), instanceId, stake, startWave, daily, skippedValue);
		_runsByPlayer.put(player.getObjectId(), run);
		_runsByInstance.put(instanceId, run);

		player.teleToLocation(RatesConfig.ARENA_X, RatesConfig.ARENA_Y, RatesConfig.ARENA_Z, 0, instanceId);

		// Retail Noblesse Blessing: buffs/debuffs survive death for the next hour. It doesn't cover XP/SP loss, which the death guard still does.
		final Skill noblesseBlessing = SkillData.getInstance().getSkill(NOBLESSE_BLESSING_SKILL_ID, 1);
		if (noblesseBlessing != null)
		{
			noblesseBlessing.applyEffects(player, player);
		}
		startDeathGuard(player, instanceId);

		final Monster challenger = new Monster(template);
		challenger.setInstanceId(instanceId);
		challenger.setXYZ(RatesConfig.ARENA_X + 100, RatesConfig.ARENA_Y, RatesConfig.ARENA_Z);
		challenger.getVariables().set("IS_ARENA_CHALLENGER", true);
		challenger.getVariables().set("ARENA_START_WAVE", startWave);
		challenger.getVariables().set("ARENA_WAVE", startWave);
		challenger.getVariables().set("ARENA_PLAYER_OBJ_ID", player.getObjectId());
		challenger.setArenaRun(run);
		run.setChallenger(challenger);
		challenger.spawnMe();

		final StringBuilder sb = new StringBuilder();
		sb.append("You have entered the Survival Arena with ").append(stake).append(stake == 1 ? " medal" : " medals").append(" staked");
		if (startWave > 0)
		{
			sb.append(", starting at wave ").append(startWave + 1).append(" (").append(format(Math.round(skippedValue))).append(" per medal credited for the skipped waves)");
		}
		sb.append('.');
		player.sendMessage(sb.toString());
		if (daily)
		{
			player.sendMessage("First run of the day: it pays " + RatesConfig.ARENA_DAILY_BONUS_PERCENT + "% more and gives a Sealed Cache.");
		}
		if (isRushActive())
		{
			player.sendMessage("Arena Rush! Every wave pays " + RatesConfig.ARENA_RUSH_BONUS_PERCENT + "% more for another " + (getRushRemainingSeconds() / 60) + " minutes.");
		}

		beginWave(run);
	}

	/**
	 * @param player a player
	 * @return {@code true} if the character hasn't started a live run today
	 */
	public boolean isDailyAvailable(Player player)
	{
		return (RatesConfig.ARENA_DAILY_BONUS_PERCENT > 0) && !LocalDate.now().toString().equals(player.getVariables().getString(DAILY_DATE_VAR, ""));
	}

	public ArenaRun getRunByPlayer(int playerObjectId)
	{
		return _runsByPlayer.get(playerObjectId);
	}

	public ArenaRun getRun(int instanceId)
	{
		return _runsByInstance.get(instanceId);
	}

	// =========================================================================
	// Waves
	// =========================================================================

	/**
	 * Starts the next wave: rolls its omens, heals the challenger, shows the wave and restarts the enrage timer.
	 * @param run the run
	 */
	private void beginWave(ArenaRun run)
	{
		final Monster challenger = run.getChallenger();
		if ((challenger == null) || run.isEnded() || !challenger.isSpawned())
		{
			return;
		}

		final int wave = run.getWave() + 1;
		final List<ArenaOmen> omens = rollOmens(wave);
		run.setOmens(omens);
		for (ArenaOmen omen : omens)
		{
			applyVariant(run, challenger, omen);
		}

		final StringBuilder tag = new StringBuilder();
		for (ArenaOmen omen : omens)
		{
			tag.append(tag.length() > 0 ? "/" : "").append(omen.getName());
		}
		challenger.setEventTitleTag(tag.length() > 0 ? "[" + tag + "]" : null);
		challenger.setCurrentHpMp(challenger.getMaxHp(), challenger.getMaxMp());
		challenger.broadcastInfo();

		run.startWaveClock();
		final int enrageSeconds = run.getEnrageSeconds();
		challenger.startArenaEnrageTimer(enrageSeconds);

		final Player player = World.getInstance().getPlayer(run.getPlayerObjectId());
		if (player == null)
		{
			return;
		}

		final int bonus = run.getOmenPayPercent();
		final boolean championWave = isMilestone(wave);
		final StringBuilder screen = new StringBuilder();
		screen.append(championWave ? "CHAMPION WAVE " : "WAVE ").append(wave);
		for (ArenaOmen omen : omens)
		{
			screen.append(" - ").append(omen.getName());
		}
		if (bonus > 0)
		{
			screen.append(" (+").append(bonus).append("% pay)");
		}
		player.sendPacket(new ExShowScreenMessage(screen.toString(), ExShowScreenMessage.MIDDLE_CENTER, 4000));
		for (ArenaOmen omen : omens)
		{
			player.sendMessage("Omen - " + omen.getName() + ": " + omen.getDescription() + " (+" + omen.getPayBonusPercent() + "% pay)");
		}
		player.sendPacket(new ExSendUIEvent(player, false, false, enrageSeconds, 0, "Until enrage"));
	}

	private static boolean isMilestone(int wave)
	{
		return (wave > 0) && ((wave % Math.max(1, RatesConfig.ARENA_MILESTONE_INTERVAL)) == 0);
	}

	private static List<ArenaOmen> rollOmens(int wave)
	{
		final List<ArenaOmen> pool = new ArrayList<>();
		for (ArenaOmen omen : ArenaOmen.values())
		{
			if (omen.isAvailable())
			{
				pool.add(omen);
			}
		}
		if ((wave < RatesConfig.ARENA_OMEN_START_WAVE) || pool.isEmpty())
		{
			return List.of();
		}

		final int count = isMilestone(wave) ? 2 : (Rnd.get(100) < RatesConfig.ARENA_OMEN_CHANCE ? 1 : 0);
		Collections.shuffle(pool);
		return new ArrayList<>(pool.subList(0, Math.min(count, pool.size())));
	}

	private static void applyVariant(ArenaRun run, Monster challenger, ArenaOmen omen)
	{
		switch (omen.getVariant())
		{
			case CHAMPION:
			{
				challenger.setChampionTier(2);
				break;
			}
			case INFUSED:
			{
				challenger.startInfused((byte) Rnd.get(InfusedMonsterConfig.ELEMENT_COUNT));
				break;
			}
			case RAGE:
			{
				challenger.startRage();
				break;
			}
			case MAGE:
			{
				// A caster archetype first, so the Mage has spells to cast (as class transfer trials do).
				if (challenger.getAI() instanceof AttackableAI ai)
				{
					run.setOriginalArchetype(ai.getArchetype());
					ai.setArchetype(MonsterArchetype.MAGE);
				}
				challenger.startMage();
				break;
			}
			default:
			{
				break;
			}
		}
	}

	private static void clearVariants(ArenaRun run, Monster challenger)
	{
		for (ArenaOmen omen : run.getOmens())
		{
			switch (omen.getVariant())
			{
				case CHAMPION:
				{
					challenger.setChampionTier(0);
					break;
				}
				case INFUSED:
				{
					challenger.stopInfused();
					break;
				}
				case MAGE:
				{
					challenger.stopMage();
					final MonsterArchetype original = run.getOriginalArchetype();
					if ((original != null) && (challenger.getAI() instanceof AttackableAI ai))
					{
						ai.setArchetype(original);
					}
					run.setOriginalArchetype(null);
					break;
				}
				default:
				{
					// A Frenzy rage ends on its own.
					break;
				}
			}
		}
		run.setOmens(List.of());
	}

	/**
	 * Called by the challenger once a lethal blow cleared a wave (its wave counter, passives and buffs are already updated). Pays the wave into the pot, then starts the next wave - after a breather and a boon pick if it was a milestone.
	 * @param challenger the challenger
	 * @param clearedWave the wave just cleared
	 * @param killer whoever landed the blow
	 * @return {@code false} if the challenger is not part of a live run (the caller then heals it and restarts the enrage itself)
	 */
	public boolean onWaveCleared(Monster challenger, int clearedWave, Creature killer)
	{
		final ArenaRun run = challenger.getArenaRun();
		if ((run == null) || run.isEnded())
		{
			return false;
		}

		final boolean swift = !run.isEnragedThisWave() && ((System.currentTimeMillis() - run.getWaveStartTime()) <= (RatesConfig.ARENA_SWIFT_SECONDS * 1000L));
		final int swiftPercent = run.recordClear(swift);
		final int bonusPercent = run.getOmenPayPercent() + swiftPercent + (ArenaBoon.GREED_PERCENT * run.getBoonCount(ArenaBoon.GREED));
		final double base = Monster.calculateArenaWaveValue(clearedWave) * RatesConfig.ARENA_LIVE_REWARD_MULTIPLIER;
		final double earned = base * (1 + (bonusPercent / 100.0));
		final double rush = isRushActive() ? base * (RatesConfig.ARENA_RUSH_BONUS_PERCENT / 100.0) : 0;
		run.addToPot(earned, rush);

		clearVariants(run, challenger);

		final Player player = World.getInstance().getPlayer(run.getPlayerObjectId());
		final boolean milestone = isMilestone(clearedWave);
		if (player != null)
		{
			if (run.getBoonCount(ArenaBoon.SECOND_WIND) > 0)
			{
				player.setCurrentHpMp(player.getMaxHp(), player.getMaxMp());
				player.setCurrentCp(player.getMaxCp());
			}

			final long waveCoins = Math.round(earned + rush) * run.getStake();
			final StringBuilder sb = new StringBuilder();
			sb.append("Wave ").append(clearedWave).append(" cleared! +").append(format(waveCoins)).append(" coins - pot ").append(format(Math.round(run.getTotalPot()) * run.getStake()));
			if (swift)
			{
				sb.append(" - Swift x").append(run.getSwiftStreak());
			}
			player.sendPacket(new ExShowScreenMessage(sb.toString(), ExShowScreenMessage.TOP_CENTER, 4000));
			player.sendMessage(sb.toString() + (bonusPercent > 0 ? " (+" + bonusPercent + "% bonus" + (rush > 0 ? ", +" + RatesConfig.ARENA_RUSH_BONUS_PERCENT + "% Rush" : "") + ")" : (rush > 0 ? " (+" + RatesConfig.ARENA_RUSH_BONUS_PERCENT + "% Rush)" : "")) + ".");

			if (milestone && RatesConfig.ARENA_MILESTONE_CACHE)
			{
				LuckyLootManager.getInstance().giveGuaranteedCache(player, player.getLevel());
				run.addCache();
			}

			if (milestone && (clearedWave >= RatesConfig.ARENA_ANNOUNCE_WAVE) && (RatesConfig.ARENA_ANNOUNCE_WAVE > 0))
			{
				Broadcast.toAllOnlinePlayers("Survival Arena: " + player.getName() + " has cleared wave " + clearedWave + "!");
			}
		}

		if (milestone && (player != null))
		{
			offerBoons(run, player);
			if (RatesConfig.ARENA_INTERMISSION_SECONDS > 0)
			{
				startIntermission(run, challenger, player);
				return true;
			}
		}

		beginWave(run);
		return true;
	}

	/**
	 * The challenger's enrage timer ran out: the swift streak is lost.
	 * @param challenger the challenger
	 */
	public void onEnraged(Monster challenger)
	{
		final ArenaRun run = challenger.getArenaRun();
		if (run == null)
		{
			return;
		}
		run.setEnragedThisWave();
		final Player player = World.getInstance().getPlayer(run.getPlayerObjectId());
		if (player != null)
		{
			player.sendPacket(new ExShowScreenMessage("The challenger is ENRAGED!", ExShowScreenMessage.MIDDLE_CENTER, 3000));
		}
	}

	// =========================================================================
	// Breather and boons
	// =========================================================================

	private void startIntermission(ArenaRun run, Monster challenger, Player player)
	{
		run.setIntermission(true);
		challenger.cancelArenaEnrageTimer();
		challenger.getEffectList().stopSkillEffects(SkillFinishType.REMOVED, ENRAGE_SKILL_ID);
		challenger.abortAttack();
		challenger.abortCast();
		challenger.setInvul(true);
		challenger.setParalyzed(true);
		challenger.startAbnormalVisualEffect(true, AbnormalVisualEffect.FLESH_STONE);
		challenger.getAI().setIntention(Intention.ACTIVE);

		final int seconds = RatesConfig.ARENA_INTERMISSION_SECONDS;
		player.sendPacket(new ExShowScreenMessage("Breather! Pick a boon - the next wave starts in " + seconds + " seconds.", ExShowScreenMessage.MIDDLE_CENTER, 4000));
		player.sendPacket(new ExSendUIEvent(player, false, false, seconds, 0, "Next wave"));
		run.setIntermissionTask(ThreadPool.schedule(() -> endIntermission(run), seconds * 1000L));
	}

	/**
	 * Ends the breather early (the "Ready" button of the boon window).
	 * @param player the player
	 */
	public void skipIntermission(Player player)
	{
		final ArenaRun run = _runsByPlayer.get(player.getObjectId());
		if ((run != null) && run.isIntermission())
		{
			final Future<?> task = run.getIntermissionTask();
			if (task != null)
			{
				task.cancel(false);
			}
			endIntermission(run);
		}
	}

	private void endIntermission(ArenaRun run)
	{
		synchronized (run)
		{
			if (!run.isIntermission())
			{
				return;
			}
			run.setIntermission(false);
		}

		final Player player = World.getInstance().getPlayer(run.getPlayerObjectId());
		if (!run.getOfferedBoons().isEmpty() && (player != null))
		{
			final List<ArenaBoon> offered = run.getOfferedBoons();
			grantBoon(run, player, offered.get(Rnd.get(offered.size())), true);
		}

		final Monster challenger = run.getChallenger();
		if ((challenger == null) || run.isEnded())
		{
			return;
		}
		challenger.stopAbnormalVisualEffect(true, AbnormalVisualEffect.FLESH_STONE);
		challenger.setParalyzed(false);
		challenger.setInvul(false);
		beginWave(run);
		if ((player != null) && (player.getInstanceId() == run.getInstanceId()) && !player.isDead())
		{
			challenger.addDamageHate(player, 0, 1);
			challenger.getAI().setIntention(Intention.ATTACK, player);
		}
	}

	private void offerBoons(ArenaRun run, Player player)
	{
		final List<ArenaBoon> pool = new ArrayList<>();
		for (ArenaBoon boon : ArenaBoon.values())
		{
			if (boon.isStackable() || (run.getBoonCount(boon) == 0))
			{
				pool.add(boon);
			}
		}
		Collections.shuffle(pool);
		run.setOfferedBoons(pool.subList(0, Math.min(BOONS_OFFERED, pool.size())));
		showBoonWindow(run, player);
	}

	/**
	 * Shows the boon draft (or, with nothing to pick, the run's status).
	 * @param player the player
	 */
	public void showBoonWindow(Player player)
	{
		final ArenaRun run = _runsByPlayer.get(player.getObjectId());
		if (run == null)
		{
			player.sendMessage("You are not in a Survival Arena run.");
			return;
		}
		showBoonWindow(run, player);
	}

	private void showBoonWindow(ArenaRun run, Player player)
	{
		final StringBuilder sb = new StringBuilder(3000);
		sb.append("<html><title>Survival Arena</title><body><center>");
		sb.append("<font name=\"hs12\" color=\"LEVEL\">Wave ").append(run.getWave()).append("</font><br1>");
		sb.append("<font color=\"E6C35C\">Pot: ").append(format(Math.round(run.getTotalPot()) * run.getStake())).append(" coins</font>");
		if (run.getStake() > 1)
		{
			sb.append("<font color=\"A0A0A0\"> (").append(format(Math.round(run.getTotalPot()))).append(" x ").append(run.getStake()).append(" medals)</font>");
		}
		sb.append("<br1><font color=\"A0A0A0\">Swift streak: ").append(run.getSwiftStreak()).append(" - Caches: ").append(run.getCaches()).append("</font><br>");

		final List<ArenaBoon> offered = run.getOfferedBoons();
		if (!offered.isEmpty())
		{
			sb.append("<font color=\"FFFFFF\">Choose a boon for the rest of the run:</font><br>");
			for (int i = 0; i < offered.size(); i++)
			{
				final ArenaBoon boon = offered.get(i);
				final int owned = run.getBoonCount(boon);
				sb.append("<table width=270 bgcolor=\"111111\"><tr><td align=center>");
				sb.append("<button value=\"").append(boon.getName()).append(owned > 0 ? " (" + (owned + 1) + ")" : "").append("\" action=\"bypass voiced_arena boon ").append(i).append("\" width=200 height=25 back=\"L2UI_CT1.Button_DF_Down\" fore=\"L2UI_CT1.Button_DF\">");
				sb.append("</td></tr><tr><td align=center><font color=\"A0A0A0\">").append(boon.getDescription()).append("</font></td></tr></table><br1>");
			}
			sb.append("<br><font color=\"707070\">No pick before the next wave gives a random one.</font><br>");
		}

		final Map<ArenaBoon, Integer> boons = run.getBoons();
		if (!boons.isEmpty())
		{
			sb.append("<br><font color=\"LEVEL\">Your boons</font><br1>");
			for (Map.Entry<ArenaBoon, Integer> entry : boons.entrySet())
			{
				sb.append("<font color=\"66CC66\">").append(entry.getKey().getName()).append(entry.getValue() > 1 ? " x" + entry.getValue() : "").append("</font><br1>");
			}
		}
		if (run.isIntermission())
		{
			sb.append("<br><button value=\"Ready - next wave\" action=\"bypass voiced_arena ready\" width=160 height=25 back=\"L2UI_CT1.Button_DF_Down\" fore=\"L2UI_CT1.Button_DF\">");
		}
		sb.append("</center></body></html>");
		player.sendPacket(new NpcHtmlMessage(0, sb.toString()));
	}

	/**
	 * The player clicked a boon of the draft.
	 * @param player the player
	 * @param index the boon's place in the draft
	 */
	public void pickBoon(Player player, int index)
	{
		final ArenaRun run = _runsByPlayer.get(player.getObjectId());
		if (run == null)
		{
			return;
		}
		final List<ArenaBoon> offered = run.getOfferedBoons();
		if ((index < 0) || (index >= offered.size()))
		{
			return;
		}
		grantBoon(run, player, offered.get(index), false);
		showBoonWindow(run, player);
	}

	private void grantBoon(ArenaRun run, Player player, ArenaBoon boon, boolean random)
	{
		synchronized (run)
		{
			if (!run.getOfferedBoons().contains(boon))
			{
				return; // Already picked (a click racing the end of the breather).
			}
			run.setOfferedBoons(List.of());
			run.addBoon(boon);
		}
		player.sendMessage((random ? "The arena chose a boon for you: " : "Boon: ") + boon.getName() + " - " + boon.getDescription());
		player.sendPacket(new ExShowScreenMessage("Boon: " + boon.getName(), ExShowScreenMessage.TOP_CENTER, 3000));
	}

	// =========================================================================
	// Damage hooks (called by the challenger)
	// =========================================================================

	/**
	 * The player (or their summon) hit the challenger for {@code damage}: Leech heals the player.
	 * @param run the run
	 * @param attacker who hit
	 * @param damage the damage dealt
	 */
	public void onChallengerHit(ArenaRun run, Creature attacker, double damage)
	{
		final double share = run.getLeechShare();
		if ((share <= 0) || (attacker == null) || (damage <= 0))
		{
			return;
		}
		final Player player = attacker.asPlayer();
		if ((player != null) && !player.isDead())
		{
			player.setCurrentHp(Math.min(player.getMaxHp(), player.getCurrentHp() + (damage * share)));
		}
	}

	// =========================================================================
	// Ending a run
	// =========================================================================

	/**
	 * Called by {@code Player#doDie()} for every death: if the player died inside their arena run - whatever killed them - the run ends and pays, and 3 seconds later they are taken to town and the death guard gives back what the death cost.
	 * @param player the player who died
	 */
	public void onPlayerDeath(Player player)
	{
		final ArenaRun run = _runsByPlayer.get(player.getObjectId());
		if ((run == null) || (player.getInstanceId() != run.getInstanceId()))
		{
			return;
		}

		endRun(player, run, true, "You fell");

		final int instanceId = run.getInstanceId();
		ThreadPool.schedule(() ->
		{
			try
			{
				if (player.getInstanceId() == instanceId)
				{
					player.teleToLocation(TeleportWhereType.TOWN);
				}
			}
			catch (Exception e)
			{
				// A failed teleport must never also cost the player their pre-death buffs/XP below.
				LOGGER.warning("Arena: failed to teleport " + player.getName() + " to town after their run ended - " + e);
			}

			// Only now - out of the instance, away from the challenger - give back what the death took. Unconditional, so a failed teleport can't swallow it.
			finishArenaRun(player);
			InstanceManager.getInstance().destroyInstance(instanceId);
		}, 3000);
	}

	/**
	 * Ends a run and, if {@code pay}, pays it: the pot times the stake, the daily bonus, records and the Hall of Champions. Safe to call more than once - only the first call does anything.
	 */
	private void endRun(Player player, ArenaRun run, boolean pay, String reason)
	{
		if (!run.markEnded())
		{
			return;
		}

		_runsByPlayer.remove(run.getPlayerObjectId(), run);
		_runsByInstance.remove(run.getInstanceId(), run);
		final Future<?> intermission = run.getIntermissionTask();
		if (intermission != null)
		{
			intermission.cancel(false);
		}

		final Monster challenger = run.getChallenger();
		if (challenger != null)
		{
			challenger.getVariables().set("ARENA_RUN_ENDED", true);
			challenger.cancelArenaEnrageTimer();
		}

		if (!pay || (player == null))
		{
			return;
		}
		player.sendPacket(new ExSendUIEvent(player, true, false, 0, 0, ""));

		final int finalWave = run.getWave();
		final double perMedal = run.getTotalPot() * (run.isDaily() ? 1 + (RatesConfig.ARENA_DAILY_BONUS_PERCENT / 100.0) : 1);
		final long reward = Math.min(Integer.MAX_VALUE, Math.round(perMedal) * run.getStake());
		if (reward > 0)
		{
			player.addItem(ItemProcessType.REWARD, RatesConfig.ARENA_CURRENCY_ITEM_ID, reward, challenger, true);
		}
		if (run.isDaily())
		{
			LuckyLootManager.getInstance().giveGuaranteedCache(player, player.getLevel());
		}

		final PlayerVariables vars = player.getVariables();
		final int previousBest = vars.getInt("ARENA_BEST_WAVE", 0);
		if (finalWave > previousBest)
		{
			vars.set("ARENA_BEST_WAVE", finalWave);
			vars.set("ARENA_BEST_WAVE_LEVEL", player.getLevel());
			player.sendMessage("New personal best! You cleared wave " + finalWave + ".");
		}

		// The best per-medal payout of a run (Rush and daily bonuses left out) is what a quick run is based on.
		final long earnedPerMedal = Math.round(run.getPot());
		if (earnedPerMedal > getBestReward(player))
		{
			vars.set("ARENA_BEST_REWARD", earnedPerMedal);
			vars.set("ARENA_BEST_REWARD_LEVEL", player.getLevel());
		}

		vars.set("ARENA_LAST_WAVE", finalWave);
		vars.set("ARENA_RUNS", vars.getInt("ARENA_RUNS", 0) + 1);
		vars.set("ARENA_TOTAL_REWARD", vars.getLong("ARENA_TOTAL_REWARD", 0) + reward);

		player.sendMessage(reason + " at wave " + (finalWave + 1) + ". The run pays " + format(reward) + " " + getItemName(RatesConfig.ARENA_CURRENCY_ITEM_ID) + (run.getStake() > 1 ? " (" + format(Math.round(perMedal)) + " x " + run.getStake() + " medals)" : "") + (run.isDaily() ? ", with the daily bonus" : "") + ".");
		player.sendPacket(new ExShowScreenMessage("Run over: wave " + finalWave + " cleared - " + format(reward) + " coins", ExShowScreenMessage.MIDDLE_CENTER, 5000));

		if (finalWave > run.getStartWave())
		{
			recordHall(player, finalWave, run.getStartWave());
		}
	}

	// =========================================================================
	// Death guard
	// =========================================================================

	/** A buff as it was at snapshot time: the skill and how many seconds it had left. */
	private record SnapshotBuff(Skill skill, int remainingSeconds)
	{
	}

	private static class ArenaDeathGuard
	{
		private final int instanceId;
		private long snapshotExp;
		private long snapshotSp;
		private List<SnapshotBuff> snapshotBuffs = new ArrayList<>();
		private Future<?> task;

		private ArenaDeathGuard(int instanceId)
		{
			this.instanceId = instanceId;
		}

		private void snapshot(Player player)
		{
			snapshotExp = player.getExp();
			snapshotSp = player.getSp();

			final List<SnapshotBuff> buffs = new ArrayList<>();
			for (BuffInfo info : player.getEffectList().getEffects())
			{
				final Skill skill = info.getSkill();
				// Only timed buffs. Endless ones (abnormal time <= 0) are owned by whatever system granted them - hotzone buffs, for one, are
				// given/taken on zone enter/exit - so restoring them from here could hand out a zone buff permanently, outside its zone.
				if ((skill != null) && !skill.isPassive() && !skill.isDebuff() && !skill.isToggle() && (info.getAbnormalTime() > 0) && (info.getTime() > 0))
				{
					buffs.add(new SnapshotBuff(skill, info.getTime()));
				}
			}
			snapshotBuffs = buffs;
		}
	}

	/**
	 * Polls {@code player} once per {@link #DEATH_GUARD_INTERVAL_MS} while they remain inside {@code instanceId}, refreshing the "last known good" snapshot while alive. If they leave the instance alive (escape, unstuck...), the run ends and pays. If they log out, it is forfeited and the
	 * instance freed.
	 * @param player the player entering the arena
	 * @param instanceId the arena instance they were just teleported into
	 */
	public void startDeathGuard(Player player, int instanceId)
	{
		final ArenaDeathGuard guard = new ArenaDeathGuard(instanceId);
		guard.snapshot(player); // capture an initial baseline immediately, before anything can happen
		final ArenaDeathGuard previous = _deathGuards.put(player.getObjectId(), guard);
		if ((previous != null) && (previous.task != null))
		{
			// A leftover guard (e.g. from a run abandoned by logging out) must not keep ticking - or later remove this new one.
			previous.task.cancel(false);
		}

		final Runnable task = () ->
		{
			if (!player.isOnline())
			{
				// Logged out mid-run: the run is forfeited. Just stop polling and free the arena instance, which would otherwise live forever with its challenger in it.
				if (_deathGuards.remove(player.getObjectId(), guard))
				{
					if (guard.task != null)
					{
						guard.task.cancel(false);
					}
					final ArenaRun run = _runsByInstance.get(guard.instanceId);
					if (run != null)
					{
						endRun(null, run, false, "");
					}
					InstanceManager.getInstance().destroyInstance(guard.instanceId);
				}
				return;
			}

			if (player.getInstanceId() != instanceId)
			{
				// Already outside the arena instance - doDie()'s teleport got them there or they left under their own power - so it's safe to restore (and pay) right now.
				restoreIfStillGuarded(player);
				return;
			}

			// Only refresh the baseline while alive. Restoration is deliberately NOT triggered from here - see the class javadoc for why.
			if (!player.isDead())
			{
				guard.snapshot(player);
			}
		};

		guard.task = ThreadPool.scheduleAtFixedRate(task, DEATH_GUARD_INTERVAL_MS, DEATH_GUARD_INTERVAL_MS);
	}

	/**
	 * Called once the run is over AND the player is out of the instance - never while they might still be standing next to a live challenger. A no-op if this player has no tracked guard (never in the arena, or the guard's own poll already restored).
	 * @param player the player whose arena run just ended
	 */
	public void finishArenaRun(Player player)
	{
		restoreIfStillGuarded(player);
	}

	/**
	 * Removes and restores from this player's guard if one is still present. {@code _deathGuards.remove()} is atomic, so whichever of the call sites gets here first performs the restore. A run still going (the player left alive) ends and pays here.
	 */
	private void restoreIfStillGuarded(Player player)
	{
		final ArenaDeathGuard guard = _deathGuards.remove(player.getObjectId());
		if (guard == null)
		{
			return;
		}

		if (guard.task != null)
		{
			guard.task.cancel(false);
		}

		final ArenaRun run = _runsByInstance.get(guard.instanceId);
		if (run != null)
		{
			endRun(player, run, true, "You left the arena");
		}

		final long expLost = Math.max(0, guard.snapshotExp - player.getExp());
		final long spLost = Math.max(0, guard.snapshotSp - player.getSp());
		if ((expLost > 0) || (spLost > 0))
		{
			player.addExpAndSp(expLost, spLost);
		}

		if (player.isDead())
		{
			player.doRevive();
		}

		// Only give back buffs the player actually lost, with the time they had left. Re-casting every snapshot buff at full duration used to
		// refresh all of them for free on every run - even one abandoned alive through a scroll of escape.
		for (SnapshotBuff buff : guard.snapshotBuffs)
		{
			if (!player.isAffectedBySkill(buff.skill().getId()))
			{
				buff.skill().applyEffects(player, player, false, buff.remainingSeconds());
			}
		}

		// However the run ended (death, "To Village", escape, unstuck...), its instance and challenger are no longer needed.
		InstanceManager.getInstance().destroyInstance(guard.instanceId);
		player.sendPacket(new ExSendUIEvent(player, true, false, 0, 0, ""));
		player.sendMessage("Your experience and buffs have been preserved.");
	}

	// =========================================================================
	// Records, quick runs and the Hall of Champions
	// =========================================================================

	/**
	 * @param player a player
	 * @return the most the character's best run paid per medal (Rush and daily bonuses left out), on today's payout scale. Records from before the doubled payouts are doubled once; records from before the payout was kept are worked out from the best wave.
	 */
	public static long getBestReward(Player player)
	{
		final PlayerVariables vars = player.getVariables();
		if (!vars.getBoolean(ECONOMY_V2_VAR, false))
		{
			final long old = vars.getLong("ARENA_BEST_REWARD", 0);
			if (old > 0)
			{
				vars.set("ARENA_BEST_REWARD", Math.round(old * RatesConfig.ARENA_LIVE_REWARD_MULTIPLIER));
			}
			vars.set(ECONOMY_V2_VAR, true);
		}

		final long bestReward = vars.getLong("ARENA_BEST_REWARD", 0);
		if (bestReward > 0)
		{
			return bestReward;
		}
		final int bestWave = vars.getInt("ARENA_BEST_WAVE", 0);
		return bestWave > 0 ? Math.round(Monster.calculateArenaReward(bestWave) * RatesConfig.ARENA_LIVE_REWARD_MULTIPLIER) : 0;
	}

	/**
	 * @param player a player
	 * @return what a quick run pays per medal: {@link RatesConfig#ARENA_QUICK_RUN_PERCENT} of the best payout
	 */
	public static long getQuickRunReward(Player player)
	{
		return Math.round(getBestReward(player) * (RatesConfig.ARENA_QUICK_RUN_PERCENT / 100.0));
	}

	/**
	 * @param quickRunReward what a quick run pays per medal
	 * @return the first wave whose plain live payout (from wave 0, no bonuses) beats a quick run, or 0 if there is no quick run yet
	 */
	public static int getBreakEvenWave(long quickRunReward)
	{
		if (quickRunReward <= 0)
		{
			return 0;
		}
		for (int wave = 1; wave <= 200; wave++)
		{
			if ((Monster.calculateArenaReward(wave) * RatesConfig.ARENA_LIVE_REWARD_MULTIPLIER) > quickRunReward)
			{
				return wave;
			}
		}
		return 200;
	}

	/**
	 * @param player a player
	 * @return the checkpoints the player may start a run from: 0, and every milestone up to 5 waves below their best wave
	 */
	public static List<Integer> getStartWaves(Player player)
	{
		final int interval = Math.max(1, RatesConfig.ARENA_MILESTONE_INTERVAL);
		final int highest = player.getVariables().getInt("ARENA_BEST_WAVE", 0) - 5;
		final List<Integer> waves = new ArrayList<>();
		waves.add(0);
		for (int wave = interval; wave <= highest; wave += interval)
		{
			waves.add(wave);
		}
		return waves;
	}

	/** One line of the Hall of Champions. */
	public static class HallEntry
	{
		private final String _name;
		private final int _classId;
		private final int _level;
		private final int _wave;
		private final int _startWave;

		public HallEntry(String name, int classId, int level, int wave, int startWave)
		{
			_name = name;
			_classId = classId;
			_level = level;
			_wave = wave;
			_startWave = startWave;
		}

		public String getName()
		{
			return _name;
		}

		public int getClassId()
		{
			return _classId;
		}

		public String getClassName()
		{
			final ClassInfoHolder info = ClassListData.getInstance().getClass(_classId);
			return info != null ? info.getClassName() : ("Class " + _classId);
		}

		public int getLevel()
		{
			return _level;
		}

		public int getWave()
		{
			return _wave;
		}

		public int getStartWave()
		{
			return _startWave;
		}

		private String serialize()
		{
			return _name + ";" + _classId + ";" + _level + ";" + _wave + ";" + _startWave;
		}

		private static HallEntry parse(String value)
		{
			final String[] parts = value.split(";");
			if (parts.length < 5)
			{
				return null;
			}
			try
			{
				return new HallEntry(parts[0], Integer.parseInt(parts[1]), Integer.parseInt(parts[2]), Integer.parseInt(parts[3]), Integer.parseInt(parts[4]));
			}
			catch (NumberFormatException e)
			{
				return null;
			}
		}
	}

	/**
	 * @return the Hall of Champions: the best wave of up to {@link #HALL_SIZE} characters, best first
	 */
	public synchronized List<HallEntry> getHall()
	{
		final List<HallEntry> hall = new ArrayList<>();
		for (int i = 0; i < HALL_SIZE; i++)
		{
			final String value = GlobalVariablesManager.getInstance().getString(HALL_VAR + i, "");
			final HallEntry entry = value.isEmpty() ? null : HallEntry.parse(value);
			if (entry != null)
			{
				hall.add(entry);
			}
		}
		return hall;
	}

	private synchronized void recordHall(Player player, int wave, int startWave)
	{
		final List<HallEntry> hall = getHall();
		final int previousTop = hall.isEmpty() ? 0 : hall.get(0).getWave();
		for (HallEntry entry : hall)
		{
			if (entry.getName().equals(player.getName()))
			{
				if (entry.getWave() >= wave)
				{
					return; // Their place already shows a better run.
				}
				hall.remove(entry);
				break;
			}
		}

		final HallEntry newEntry = new HallEntry(player.getName(), player.getPlayerClass().getId(), player.getLevel(), wave, startWave);
		int index = 0;
		while ((index < hall.size()) && (hall.get(index).getWave() >= wave))
		{
			index++;
		}
		if (index >= HALL_SIZE)
		{
			return;
		}
		hall.add(index, newEntry);

		for (int i = 0; i < HALL_SIZE; i++)
		{
			if (i < hall.size())
			{
				GlobalVariablesManager.getInstance().set(HALL_VAR + i, hall.get(i).serialize());
			}
			else
			{
				GlobalVariablesManager.getInstance().remove(HALL_VAR + i);
			}
		}

		player.sendMessage("Your run earned place " + (index + 1) + " in the Hall of Champions.");
		if ((index == 0) && (wave > previousTop))
		{
			Broadcast.toAllOnlinePlayers("Survival Arena: " + player.getName() + " set a new server record - wave " + wave + "!");
		}
	}

	// =========================================================================
	// Arena Rush
	// =========================================================================

	/**
	 * Opens an Arena Rush now: for {@code minutes} every wave cleared in the arena pays {@link RatesConfig#ARENA_RUSH_BONUS_PERCENT} more. Announced to everyone.
	 * @param minutes how long it lasts
	 */
	public synchronized void startRush(int minutes)
	{
		_rushUntil = System.currentTimeMillis() + (minutes * 60000L);
		if (_rushEndTask != null)
		{
			_rushEndTask.cancel(false);
		}
		_rushEndTask = ThreadPool.schedule(() -> Broadcast.toAllOnlinePlayers("Survival Arena: the Arena Rush is over."), minutes * 60000L);

		final String text = "ARENA RUSH! For " + minutes + " minutes the Survival Arena pays " + RatesConfig.ARENA_RUSH_BONUS_PERCENT + "% more. See the Arena Master in Giran!";
		Broadcast.toAllOnlinePlayers(text);
		Broadcast.toAllOnlinePlayersOnScreen(text);
	}

	/**
	 * @return when the next Arena Rush starts (ms since the epoch), or 0 if none is scheduled. Kept here rather than in the Arena Master script, so a reloaded script knows a Rush is already scheduled.
	 */
	public long getNextRushTime()
	{
		return _nextRushTime;
	}
	
	public void setNextRushTime(long time)
	{
		_nextRushTime = time;
	}
	
	public boolean isRushActive()
	{
		return System.currentTimeMillis() < _rushUntil;
	}

	/**
	 * @return seconds left of the current Arena Rush, or 0
	 */
	public long getRushRemainingSeconds()
	{
		return Math.max(0, (_rushUntil - System.currentTimeMillis()) / 1000);
	}

	// =========================================================================
	// Helpers
	// =========================================================================

	private static String format(long value)
	{
		return String.format("%,d", value);
	}

	private static String getItemName(int itemId)
	{
		final ItemTemplate item = ItemData.getInstance().getTemplate(itemId);
		return item != null ? item.getName().replace("Event - ", "") : ("Item " + itemId);
	}

	public static ArenaSurvivalManager getInstance()
	{
		return SingletonHolder.INSTANCE;
	}

	private static class SingletonHolder
	{
		protected static final ArenaSurvivalManager INSTANCE = new ArenaSurvivalManager();
	}
}
