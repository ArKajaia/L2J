package org.l2jmobius.gameserver.managers;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Future;

import org.l2jmobius.commons.threads.ThreadPool;
import org.l2jmobius.gameserver.model.actor.Player;
import org.l2jmobius.gameserver.model.skill.BuffInfo;
import org.l2jmobius.gameserver.model.skill.Skill;

/**
 * Backs the Survival Arena's death handling (see {@code custom.ArenaMaster} in the datapack, which is the only caller). Lives in core - rather than in the script itself - purely so {@link org.l2jmobius.gameserver.model.actor.Player#doDie} can call {@link #finishArenaRun(Player)} directly:
 * core code can never reference a datapack script class, since scripts compile against the core jar, not the other way around (mirrors how {@link PassiveTreeManager} is a core manager with thin script wrappers around it).
 * <p>
 * Owns one {@link ArenaDeathGuard} per player currently inside the arena: a continuously-refreshed "last known good" snapshot of their XP/SP/buffs, taken while alive, used to undo the death penalty once their run is over. Restoration is deliberately NOT triggered the instant death is
 * detected - reviving the player in place, next to a still-live challenger, let it kill them a second time before they could be teleported out (double reward from Player#doDie, and the second death's penalty stuck because there was no re-arming step).
 * <p>
 * Two independent paths can trigger the restore, and both are safe to race: {@link #finishArenaRun(Player)}, called explicitly by Player#doDie() once its own scheduled teleport-to-town completes, and the death guard's own poll noticing the player already left the instance some OTHER way -
 * most commonly because a dead player is perfectly free to click "To Village" on their own death screen at any time, well before doDie()'s 3-second timer runs out. Either path is safe once the player is actually outside the arena instance, so whichever notices first performs the
 * restore; {@code _deathGuards.remove()} is atomic, so exactly one of them ever finds the guard still present.
 */
public class ArenaSurvivalManager
{
	/** How often (ms) the death guard refreshes its "last known good" snapshot while the player is alive. */
	private static final int DEATH_GUARD_INTERVAL_MS = 1000;

	/** One entry per player currently inside the arena, tracked by player object ID. */
	private final Map<Integer, ArenaDeathGuard> _deathGuards = new ConcurrentHashMap<>();

	protected ArenaSurvivalManager()
	{
	}

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
	 * Starts polling {@code player} once per {@link #DEATH_GUARD_INTERVAL_MS} while they remain inside {@code instanceId}, refreshing the "last known good" snapshot while alive. Restoration normally happens via {@link #finishArenaRun(Player)}, but if the player leaves the instance some
	 * OTHER way first - most commonly by clicking "To Village" on their own death screen before doDie()'s timer runs out - this poll notices the instance mismatch and performs the restore itself right then, since by that point they're already safely away from the challenger.
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
				// Logged out mid-run: nothing to restore to an offline object (they left alive, or their loss already got saved).
				// Just stop polling and free the arena instance, which would otherwise live forever with its challenger in it.
				if (_deathGuards.remove(player.getObjectId(), guard))
				{
					if (guard.task != null)
					{
						guard.task.cancel(false);
					}
					InstanceManager.getInstance().destroyInstance(guard.instanceId);
				}
				return;
			}

			if (player.getInstanceId() != instanceId)
			{
				// Already outside the arena instance - whether doDie()'s own teleport got them
				// there or they left under their own power - so it's safe to restore right now.
				restoreIfStillGuarded(player);
				return;
			}

			// Only refresh the baseline while alive. Restoration is deliberately NOT triggered
			// from here - see the class javadoc for why.
			if (!player.isDead())
			{
				guard.snapshot(player);
			}
		};

		guard.task = ThreadPool.scheduleAtFixedRate(task, DEATH_GUARD_INTERVAL_MS, DEATH_GUARD_INTERVAL_MS);
	}

	/**
	 * Called by {@code Player#doDie()} once the arena run is over AND the player has already been teleported out of the instance - never while they might still be standing next to a live challenger. A no-op if this player has no tracked guard: either they were never actually inside the
	 * arena, or the death guard's own poll already restored and cleared it first (see {@link #startDeathGuard}).
	 * @param player the player whose arena run just ended
	 */
	public void finishArenaRun(Player player)
	{
		restoreIfStillGuarded(player);
	}

	/**
	 * Removes and restores from this player's guard if one is still present. {@code _deathGuards.remove()} is atomic, so whichever of the two call sites (see class javadoc) gets here first performs the restore - the other finds nothing left to do.
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

		player.sendMessage("Your experience and buffs have been preserved.");
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
