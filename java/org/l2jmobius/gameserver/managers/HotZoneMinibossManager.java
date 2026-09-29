package org.l2jmobius.gameserver.managers;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import org.l2jmobius.gameserver.config.custom.HotzoneMinibossConfig;
import org.l2jmobius.gameserver.model.actor.Attackable;
import org.l2jmobius.gameserver.model.actor.Creature;
import org.l2jmobius.gameserver.model.actor.instance.Monster;
import org.l2jmobius.gameserver.model.actor.templates.NpcTemplate;
import org.l2jmobius.gameserver.model.hotzone.HotzoneModifier;
import org.l2jmobius.gameserver.model.zone.ZoneId;
import org.l2jmobius.gameserver.model.zone.ZoneType;
import org.l2jmobius.gameserver.model.zone.type.HotZone;

/**
 * Counts monster kills per hotzone (see {@link HotZone}) and, once {@link HotzoneMinibossConfig#KILLS_REQUIRED} is reached in one zone, spawns a buffed clone of whichever monster type died LAST there as a miniboss - tough, but tuned for roughly 2 players rather than a full raid (see
 * {@link Monster#isHotzoneMiniboss()} and the stat/reward overrides that key off it).
 * <p>
 * Only the zones the hourly rotation currently has active count (see {@link HotzoneModifierManager#isActive(int)}): every {@link HotZone} stays flagged {@link ZoneId#HOTZONE} whether or not it is the rotation's pick, so without that gate a zone that was no longer hot kept
 * spawning minibosses. A zone's progress is dropped when it rotates out (see {@link #resetKills(int)}).
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

		// Credit only ONE hotzone per kill - where hotzones overlap, counting every zone would double the miniboss rate there.
		for (ZoneType zone : ZoneManager.getInstance().getZones(victim))
		{
			// Skip hotzones the rotation isn't currently running - they're still flagged HOTZONE, but no longer hot.
			if (!(zone instanceof HotZone) || !HotzoneModifierManager.getInstance().isActive(zone.getId()))
			{
				continue;
			}

			// MINIBOSS_FRENZY-style modifiers lower the kill threshold for this zone.
			final HotzoneModifier modifier = HotzoneModifierManager.getInstance().getModifier(zone.getId());
			final int killsRequired = modifier != null ? Math.max(1, (int) Math.ceil(HotzoneMinibossConfig.KILLS_REQUIRED * modifier.getMinibossKillsMult())) : HotzoneMinibossConfig.KILLS_REQUIRED;
			final AtomicInteger counter = _killCounters.computeIfAbsent(zone.getId(), k -> new AtomicInteger());
			if (counter.incrementAndGet() >= killsRequired)
			{
				counter.set(0);
				spawnMiniboss(template, victim, zone);
			}
			return;
		}
	}

	/**
	 * Drops the kill progress of a hotzone, so a zone that rotates out doesn't carry a half-filled counter into its next activation.
	 * @param zoneId the hotzone's zone id
	 */
	public void resetKills(int zoneId)
	{
		_killCounters.remove(zoneId);
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
