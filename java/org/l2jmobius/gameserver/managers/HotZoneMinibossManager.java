package org.l2jmobius.gameserver.managers;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import org.l2jmobius.gameserver.config.custom.HotzoneMinibossConfig;
import org.l2jmobius.gameserver.model.actor.Attackable;
import org.l2jmobius.gameserver.model.actor.Creature;
import org.l2jmobius.gameserver.model.actor.instance.Monster;
import org.l2jmobius.gameserver.model.actor.templates.NpcTemplate;
import org.l2jmobius.gameserver.model.zone.ZoneId;
import org.l2jmobius.gameserver.model.zone.ZoneType;
import org.l2jmobius.gameserver.model.zone.type.HotZone;

/**
 * Counts monster kills per hotzone (see {@link HotZone}) and, once {@link HotzoneMinibossConfig#KILLS_REQUIRED} is reached in one zone, spawns a buffed clone of whichever monster type died LAST there as a miniboss - tough, but tuned for roughly 2 players rather than a full raid (see
 * {@link Monster#isHotzoneMiniboss()} and the stat/reward overrides that key off it).
 * <p>
 * Scoped to any zone flagged {@link ZoneId#HOTZONE}, matching the existing convention in {@code Spawn} (the champion-frequency hotzone bonus applies the same way) - not gated behind whichever single zone the hourly rotation currently has "active", since all hotzone-flagged terrain is
 * already treated as boosted ground elsewhere in this codebase.
 */
public class HotZoneMinibossManager
{
	/** zone id -> kills counted since the last miniboss spawn there. */
	private final Map<Integer, AtomicInteger> _killCounters = new ConcurrentHashMap<>();

	protected HotZoneMinibossManager()
	{
	}

	/**
	 * Called from {@code Attackable#doDie()} for every attackable a player (or their summon) kills. A no-op unless the victim died inside a hotzone.
	 * @param victim the attackable that just died
	 * @param killer the creature credited with the kill
	 */
	public void onAttackableKilled(Attackable victim, Creature killer)
	{
		if (!HotzoneMinibossConfig.ENABLED || (victim == null) || !victim.isInsideZone(ZoneId.HOTZONE))
		{
			return;
		}

		// Don't let a miniboss's own death re-arm the counter that just spawned it.
		if (victim.isMonster() && ((Monster) victim).isHotzoneMiniboss())
		{
			return;
		}

		final NpcTemplate template = victim.getTemplate();
		if (template == null)
		{
			return;
		}

		for (ZoneType zone : ZoneManager.getInstance().getZones(victim))
		{
			if (!(zone instanceof HotZone))
			{
				continue;
			}

			final AtomicInteger counter = _killCounters.computeIfAbsent(zone.getId(), k -> new AtomicInteger());
			if (counter.incrementAndGet() < HotzoneMinibossConfig.KILLS_REQUIRED)
			{
				continue;
			}

			counter.set(0);
			spawnMiniboss(template, victim, zone);
		}
	}

	private void spawnMiniboss(NpcTemplate template, Attackable victim, ZoneType zone)
	{
		final Monster miniboss = new Monster(template);
		miniboss.setInstanceId(victim.getInstanceId());
		miniboss.getVariables().set("IS_HOTZONE_MINIBOSS", true);
		miniboss.setXYZ(victim.getX(), victim.getY(), victim.getZ());
		miniboss.spawnMe();

		final String zoneName = (zone.getName() != null) && !zone.getName().isEmpty() ? zone.getName() : ("zone " + zone.getId());
		for (Creature creature : zone.getCharactersInside())
		{
			if (creature.isPlayer())
			{
				creature.asPlayer().sendMessage("A powerful " + template.getName() + " has emerged in " + zoneName + "!");
			}
		}
	}

	public static HotZoneMinibossManager getInstance()
	{
		return SingletonHolder.INSTANCE;
	}

	private static class SingletonHolder
	{
		protected static final HotZoneMinibossManager INSTANCE = new HotZoneMinibossManager();
	}
}
