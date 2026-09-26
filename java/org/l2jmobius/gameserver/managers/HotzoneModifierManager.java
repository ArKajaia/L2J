package org.l2jmobius.gameserver.managers;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;

import org.l2jmobius.commons.threads.ThreadPool;
import org.l2jmobius.commons.util.Rnd;
import org.l2jmobius.gameserver.data.xml.SkillData;
import org.l2jmobius.gameserver.model.actor.Attackable;
import org.l2jmobius.gameserver.model.actor.Creature;
import org.l2jmobius.gameserver.model.actor.Player;
import org.l2jmobius.gameserver.model.actor.instance.Monster;
import org.l2jmobius.gameserver.model.hotzone.HotzoneModifier;
import org.l2jmobius.gameserver.model.skill.BuffInfo;
import org.l2jmobius.gameserver.model.skill.Skill;
import org.l2jmobius.gameserver.model.zone.ZoneId;
import org.l2jmobius.gameserver.model.zone.ZoneType;
import org.l2jmobius.gameserver.model.zone.type.HotZone;

/**
 * Owns which {@link HotzoneModifier} (if any) is currently active for each hotzone, and the ticking task for whichever modifiers drain player HP over time. {@code custom.RotatingHotZones} (the only caller) rolls a fresh modifier per zone on every rotation via {@link #rollModifier(int)} and
 * clears it via {@link #clearModifier(int)} right before a zone stops being that rotation's active pick.
 * <p>
 * Lives in core - rather than in the script itself - purely so {@link org.l2jmobius.gameserver.model.actor.instance.Monster}, {@code Spawn}, {@code AttackableAI} and the {@code Stun} effect can all query the active modifier directly: core code can never reference a datapack script class,
 * since scripts compile against the core jar, not the other way around (mirrors how {@link ArenaSurvivalManager} and {@link HotZoneMinibossManager} are structured for the same reason).
 */
public class HotzoneModifierManager
{
	/** How often (ms) FRAGILE_GROUND-style modifiers tick their player HP drain. */
	public static final int PLAYER_DRAIN_INTERVAL_MS = 5000;

	/** Delay (ms) before a RESTLESS_DEAD victim rises again, so the corpse is seen first. */
	private static final int RISE_DELAY_MS = 1500;

	/** zone id -> the modifier currently active there, if any. Absent = no modifier (vanilla hotzone). */
	private final Map<Integer, HotzoneModifier> _activeModifiers = new ConcurrentHashMap<>();

	/** zone id -> its player-HP-drain ticking task, only present while that zone's modifier actually drains HP. */
	private final Map<Integer, ScheduledFuture<?>> _drainTasks = new ConcurrentHashMap<>();

	protected HotzoneModifierManager()
	{
	}

	/**
	 * Rolls a fresh random modifier for {@code zoneId} and starts whatever ticking it needs. Safe to call on a zone that already has one active - the old one (and its task, if any) is cleared first.
	 * @param zoneId the hotzone's zone id
	 * @return the modifier that was rolled
	 */
	public HotzoneModifier rollModifier(int zoneId)
	{
		clearModifier(zoneId);

		final HotzoneModifier[] pool = HotzoneModifier.values();
		final HotzoneModifier chosen = pool[Rnd.get(pool.length)];
		_activeModifiers.put(zoneId, chosen);

		if (chosen.getPlayerHpDrainPctPerTick() > 0)
		{
			_drainTasks.put(zoneId, ThreadPool.scheduleAtFixedRate(() -> drainPlayers(zoneId, chosen), PLAYER_DRAIN_INTERVAL_MS, PLAYER_DRAIN_INTERVAL_MS));
		}

		return chosen;
	}

	/**
	 * Clears whatever modifier is active for {@code zoneId} (and stops its drain task, if any). Safe to call on a zone with no active modifier - it's then just a no-op.
	 * @param zoneId the hotzone's zone id
	 */
	public void clearModifier(int zoneId)
	{
		_activeModifiers.remove(zoneId);

		final ScheduledFuture<?> task = _drainTasks.remove(zoneId);
		if (task != null)
		{
			task.cancel(false);
		}
	}

	/**
	 * @param zoneId the hotzone's zone id
	 * @return the modifier active there, or {@code null} if none
	 */
	public HotzoneModifier getModifier(int zoneId)
	{
		return _activeModifiers.get(zoneId);
	}

	/**
	 * Resolves whichever hotzone {@code creature} is currently standing in and returns its active modifier.
	 * @param creature the creature to check
	 * @return the active modifier for that creature's current hotzone, or {@code null} if it isn't in one, or its hotzone has no modifier active
	 */
	public HotzoneModifier getModifierFor(Creature creature)
	{
		if ((creature == null) || !creature.isInsideZone(ZoneId.HOTZONE))
		{
			return null;
		}

		for (ZoneType zone : ZoneManager.getInstance().getZones(creature))
		{
			if (zone instanceof HotZone)
			{
				final HotzoneModifier modifier = _activeModifiers.get(zone.getId());
				if (modifier != null)
				{
					return modifier;
				}
			}
		}

		return null;
	}

	/**
	 * Kill-triggered modifier effects: VAMPIRIC_HUNT heals the killer, KILL_STREAK stacks its XP/SP buff one level higher, RESTLESS_DEAD may raise the victim once more. Called from {@code Attackable.doDie} only for kills that reward exp/sp.
	 * @param victim the attackable that just died
	 * @param killer the player credited with the kill
	 */
	public void onAttackableKilled(Attackable victim, Player killer)
	{
		if ((victim == null) || (killer == null))
		{
			return;
		}

		final HotzoneModifier modifier = getModifierFor(victim);
		if (modifier == null)
		{
			return;
		}

		if ((modifier.getKillHealPct() > 0) && !killer.isDead())
		{
			final double pct = modifier.getKillHealPct() / 100.0;
			killer.setCurrentHp(Math.min(killer.getMaxHp(), killer.getCurrentHp() + (killer.getMaxHp() * pct)));
			killer.setCurrentMp(Math.min(killer.getMaxMp(), killer.getCurrentMp() + (killer.getMaxMp() * pct)));
		}

		if (modifier.isKillStreak() && !killer.isDead())
		{
			final BuffInfo current = killer.getEffectList().getBuffInfoBySkillId(HotzoneModifier.KILL_STREAK_SKILL_ID);
			final int level = Math.min((current != null ? current.getSkill().getLevel() : 0) + 1, HotzoneModifier.KILL_STREAK_MAX_LEVEL);
			final Skill streak = SkillData.getInstance().getSkill(HotzoneModifier.KILL_STREAK_SKILL_ID, level);
			if (streak != null)
			{
				streak.applyEffects(killer, killer);
			}
		}

		if ((modifier.getRiseChancePct() > 0) && victim.isMonster() && victim.asMonster().canHotzoneRise() && (Rnd.get(100) < modifier.getRiseChancePct()))
		{
			ThreadPool.schedule(() -> raiseAgain(victim.asMonster(), killer), RISE_DELAY_MS);
		}
	}

	private void raiseAgain(Monster victim, Player killer)
	{
		final Monster risen = new Monster(victim.getTemplate());
		risen.setInstanceId(victim.getInstanceId());
		risen.getVariables().set(Monster.HOTZONE_RISEN_VAR, true);
		risen.setXYZ(victim.getX(), victim.getY(), victim.getZ());
		risen.setHeading(victim.getHeading());
		risen.spawnMe();
		risen.setCurrentHp(risen.getMaxHp() * 0.5);

		if ((killer != null) && killer.isOnline())
		{
			killer.sendMessage("The " + risen.getName() + " rises again!");
		}
	}

	private void drainPlayers(int zoneId, HotzoneModifier modifier)
	{
		final ZoneType zone = ZoneManager.getInstance().getZoneById(zoneId);
		if (zone == null)
		{
			clearModifier(zoneId);
			return;
		}

		final double pct = modifier.getPlayerHpDrainPctPerTick() / 100.0;
		for (Creature creature : zone.getCharactersInside())
		{
			final Player player = creature.asPlayer();
			if ((player == null) || player.isDead() || player.isInvul())
			{
				continue;
			}

			final double drain = player.getMaxHp() * pct;
			if (drain > 0)
			{
				player.reduceCurrentHp(drain, null, false, false, null);
			}
		}
	}

	public static HotzoneModifierManager getInstance()
	{
		return SingletonHolder.INSTANCE;
	}

	private static class SingletonHolder
	{
		protected static final HotzoneModifierManager INSTANCE = new HotzoneModifierManager();
	}
}
