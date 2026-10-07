package org.l2jmobius.gameserver.managers;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.atomic.AtomicInteger;

import org.l2jmobius.commons.threads.ThreadPool;
import org.l2jmobius.commons.util.Rnd;
import org.l2jmobius.gameserver.data.xml.SkillData;
import org.l2jmobius.gameserver.model.actor.Attackable;
import org.l2jmobius.gameserver.model.actor.Creature;
import org.l2jmobius.gameserver.model.actor.Player;
import org.l2jmobius.gameserver.model.actor.instance.Chest;
import org.l2jmobius.gameserver.model.actor.instance.Monster;
import org.l2jmobius.gameserver.model.groups.Party;
import org.l2jmobius.gameserver.model.hotzone.HotzoneModifier;
import org.l2jmobius.gameserver.model.skill.BuffInfo;
import org.l2jmobius.gameserver.model.skill.Skill;
import org.l2jmobius.gameserver.model.zone.ZoneId;
import org.l2jmobius.gameserver.model.zone.ZoneType;
import org.l2jmobius.gameserver.model.zone.type.HotZone;
import org.l2jmobius.gameserver.network.serverpackets.ExShowScreenMessage;

/**
 * Owns which {@link HotzoneModifier} (if any) is currently active for each hotzone, the ticking tasks for whichever modifiers drain player HP or post bounties, and the Heat of RISING_HEAT zones. {@code custom.RotatingHotZones} (the only caller) rolls a fresh modifier per zone on every rotation via {@link #rollModifier(int)} and
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

	/** How often (ms) a BOUNTY_HUNT zone checks its bounty. */
	private static final int BOUNTY_TICK_MS = 10000;
	/** A new bounty is posted this long (ms) after the previous one was. */
	private static final long BOUNTY_INTERVAL_MS = 300000;
	/** A bounty nobody claims within this long (ms) moves to another monster. */
	private static final long BOUNTY_DURATION_MS = 180000;
	/** The first bounty of a rotation is posted this long (ms) after the zone becomes active. */
	private static final long BOUNTY_FIRST_DELAY_MS = 30000;
	/** Hotzone coin multiplier for a bounty kill (always paid). */
	public static final double BOUNTY_COIN_MULT = 20.0;

	/** XP/SP bonus per other party member hunting in the same KINSHIP zone. */
	private static final double KINSHIP_BONUS_PER_MEMBER = 0.05;
	/** Highest KINSHIP XP/SP multiplier. */
	private static final double KINSHIP_MAX_MULT = 1.40;
	/** KINSHIP XP/SP multiplier of a player who hunts alone. */
	private static final double KINSHIP_SOLO_MULT = 0.90;

	/** HP (fraction of max) of each copy a SPLITTING_GROUND victim splits into. */
	private static final double SPLIT_HP_FRACTION = 0.3;
	/** How far (game units) the two copies land from the victim. */
	private static final int SPLIT_OFFSET = 60;

	/** The modifiers a hotzone rotation may roll: every one but the night-only omens. */
	private static final List<HotzoneModifier> HOTZONE_POOL = Arrays.stream(HotzoneModifier.values()).filter(modifier -> !modifier.isNightOnly()).toList();

	/** The modifier the night lays over the open world (see {@link NightCycleManager}), or {@code null} by day. */
	private volatile HotzoneModifier _nightModifier;

	/** zone id -> the modifier currently active there, if any. Absent = no modifier (vanilla hotzone). */
	private final Map<Integer, HotzoneModifier> _activeModifiers = new ConcurrentHashMap<>();

	/** zone id -> its player-HP-drain ticking task, only present while that zone's modifier actually drains HP. */
	private final Map<Integer, ScheduledFuture<?>> _drainTasks = new ConcurrentHashMap<>();

	/** instance id -> the modifier applied to everything inside that instance (a class transfer challenge "Omen"). The HP drain of such a modifier is ticked by its owner, not here. */
	private final Map<Integer, HotzoneModifier> _instanceModifiers = new ConcurrentHashMap<>();

	/** zone id -> its BOUNTY_HUNT state, only present while that zone's modifier posts bounties. */
	private final Map<Integer, BountyState> _bounties = new ConcurrentHashMap<>();

	/** zone id -> kills counted towards the RISING_HEAT stacks, only present while that zone's modifier builds Heat. */
	private final Map<Integer, AtomicInteger> _heatKills = new ConcurrentHashMap<>();

	/**
	 * The bounty of one BOUNTY_HUNT zone: the marked monster (if any), and when the next one is due.
	 */
	private static class BountyState
	{
		private ScheduledFuture<?> task;
		private Monster target;
		private long markedAt;
		private long nextMarkAt;
	}

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

		final HotzoneModifier chosen = HOTZONE_POOL.get(Rnd.get(HOTZONE_POOL.size()));
		_activeModifiers.put(zoneId, chosen);

		if (chosen.getPlayerHpDrainPctPerTick() > 0)
		{
			_drainTasks.put(zoneId, ThreadPool.scheduleAtFixedRate(() -> drainPlayers(zoneId, chosen), PLAYER_DRAIN_INTERVAL_MS, PLAYER_DRAIN_INTERVAL_MS));
		}

		if (chosen.isBounty())
		{
			final BountyState state = new BountyState();
			state.nextMarkAt = System.currentTimeMillis() + BOUNTY_FIRST_DELAY_MS;
			_bounties.put(zoneId, state);
			state.task = ThreadPool.scheduleAtFixedRate(() -> tickBounty(zoneId, state), BOUNTY_TICK_MS, BOUNTY_TICK_MS);
		}

		if (chosen.isHeat())
		{
			_heatKills.put(zoneId, new AtomicInteger());
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
		HotZoneMinibossManager.getInstance().resetKills(zoneId);

		final ScheduledFuture<?> task = _drainTasks.remove(zoneId);
		if (task != null)
		{
			task.cancel(false);
		}

		final BountyState bounty = _bounties.remove(zoneId);
		if (bounty != null)
		{
			synchronized (bounty)
			{
				if (bounty.task != null)
				{
					bounty.task.cancel(false);
				}
				unmarkBounty(bounty);
			}
		}

		_heatKills.remove(zoneId);
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
	 * Applies {@code modifier} to every creature inside {@code instanceId}, whatever its coordinates. Set it before spawning the instance's monsters so their stats pick it up from the start.
	 * @param instanceId the instance id
	 * @param modifier the modifier to apply
	 */
	public void setInstanceModifier(int instanceId, HotzoneModifier modifier)
	{
		if ((instanceId > 0) && (modifier != null))
		{
			_instanceModifiers.put(instanceId, modifier);
		}
	}

	/**
	 * Removes the instance-wide modifier of {@code instanceId}, if any.
	 * @param instanceId the instance id
	 */
	public void clearInstanceModifier(int instanceId)
	{
		_instanceModifiers.remove(instanceId);
	}

	/**
	 * @param instanceId the instance id
	 * @return the instance-wide modifier of {@code instanceId}, or {@code null} if none
	 */
	public HotzoneModifier getInstanceModifier(int instanceId)
	{
		return _instanceModifiers.get(instanceId);
	}

	/**
	 * Every hotzone stays flagged {@link ZoneId#HOTZONE} permanently, but only the rotation's current picks are actually hot - and each of those always has a modifier rolled.
	 * @param zoneId the hotzone's zone id
	 * @return {@code true} if {@code zoneId} is one of the hotzones the rotation currently has active
	 */
	public boolean isActive(int zoneId)
	{
		return _activeModifiers.containsKey(zoneId);
	}

	/**
	 * Resolves whichever hotzone {@code creature} is currently standing in and returns its active modifier, or else the night's Omen if {@code creature} is in the open world at night.
	 * @param creature the creature to check
	 * @return the active modifier for that creature's current hotzone or instance, else the night's Omen (see {@link #getNightModifierFor}), or {@code null} if neither applies
	 */
	public HotzoneModifier getModifierFor(Creature creature)
	{
		final HotzoneModifier modifier = getZoneModifierFor(creature);
		return modifier != null ? modifier : getNightModifierFor(creature);
	}

	/**
	 * Lays {@code modifier} over the whole open world, or lifts it. Called by {@link NightCycleManager} as night falls, as the Witching Hour begins and at dawn.
	 * @param modifier the night's modifier, or {@code null} to lift it
	 */
	public void setNightModifier(HotzoneModifier modifier)
	{
		_nightModifier = modifier;
	}

	/**
	 * @return the modifier the night lays over the open world, or {@code null} if none
	 */
	public HotzoneModifier getNightModifier()
	{
		return _nightModifier;
	}

	/**
	 * The night's Omen reaches every creature in the open world, but not raids, which keep their own fights, nor towns, instances, sieges, arenas, PvP spots or jail.
	 * @param creature the creature to check
	 * @return the night's modifier if it reaches {@code creature}, otherwise {@code null}
	 */
	public HotzoneModifier getNightModifierFor(Creature creature)
	{
		final HotzoneModifier modifier = _nightModifier;
		if ((modifier == null) || (creature == null) || creature.isRaid() || creature.isRaidMinion() || !isOpenWorld(creature))
		{
			return null;
		}
		return modifier;
	}

	/**
	 * @param creature the creature to check
	 * @return {@code true} if {@code creature} is outside every instance, town, siege, arena, PvP spot and jail
	 */
	public static boolean isOpenWorld(Creature creature)
	{
		return (creature.getInstanceId() == 0) && !creature.isInsideZone(ZoneId.PEACE) && !creature.isInsideZone(ZoneId.TOWN) && !creature.isInsideZone(ZoneId.SIEGE) && !creature.isInsideZone(ZoneId.PVP) && !creature.isInsideZone(ZoneId.PVP_SPOT) && !creature.isInsideZone(ZoneId.JAIL);
	}

	/**
	 * Like {@link #getModifierFor(Creature)}, but without the night's Omen: what belongs to hotzones alone (the coin drops, the flat champion bonus) reads this.
	 * @param creature the creature to check
	 * @return the active modifier for that creature's current hotzone or instance, or {@code null} if it isn't in one, or its hotzone has no modifier active
	 */
	public HotzoneModifier getZoneModifierFor(Creature creature)
	{
		if (creature == null)
		{
			return null;
		}

		// An instance-wide modifier wins over whatever world hotzone the instance's coordinates happen to overlap.
		if ((creature.getInstanceId() > 0) && !_instanceModifiers.isEmpty())
		{
			final HotzoneModifier instanceModifier = _instanceModifiers.get(creature.getInstanceId());
			if (instanceModifier != null)
			{
				return instanceModifier;
			}
		}

		if (!creature.isInsideZone(ZoneId.HOTZONE))
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
	 * Unlike {@link #getModifierFor(Creature)}, this ignores instance-wide modifiers: only a world hotzone has a zone id to key per-zone state (bounties, Heat) on.
	 * @param creature the creature to check
	 * @return the id of the active hotzone {@code creature} stands in, or 0 if none
	 */
	public int getActiveZoneId(Creature creature)
	{
		if ((creature == null) || !creature.isInsideZone(ZoneId.HOTZONE))
		{
			return 0;
		}

		for (ZoneType zone : ZoneManager.getInstance().getZones(creature))
		{
			if ((zone instanceof HotZone) && _activeModifiers.containsKey(zone.getId()))
			{
				return zone.getId();
			}
		}

		return 0;
	}

	/**
	 * For rolls made before the creature exists (the roaming fake player spawn roll). Instance-wide modifiers are not covered.
	 * @param x the x coordinate
	 * @param y the y coordinate
	 * @param z the z coordinate
	 * @return the modifier of the active hotzone at these coordinates, or {@code null} if none
	 */
	public HotzoneModifier getModifierAt(int x, int y, int z)
	{
		if (_activeModifiers.isEmpty())
		{
			return null;
		}

		for (ZoneType zone : ZoneManager.getInstance().getZones(x, y, z))
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
	 * @param creature the creature to check
	 * @return the RISING_HEAT stacks of the hotzone {@code creature} stands in, 0 if it isn't in one that builds Heat
	 */
	public int getHeatStacks(Creature creature)
	{
		if (_heatKills.isEmpty())
		{
			return 0;
		}

		final int zoneId = getActiveZoneId(creature);
		final AtomicInteger kills = zoneId > 0 ? _heatKills.get(zoneId) : null;
		return kills != null ? Math.min(HotzoneModifier.HEAT_MAX_STACKS, kills.get() / HotzoneModifier.HEAT_KILLS_PER_STACK) : 0;
	}

	/**
	 * KINSHIP: a player hunting alone earns less, one whose party members hunt in the same zone earns more. A player whose only party is fake players ({@link FakePartyManager}) is left as is.
	 * @param victim the attackable that just died
	 * @param player the player (or party leader) being rewarded
	 * @return the XP/SP multiplier for this kill, 1.0 outside a KINSHIP zone
	 */
	public double getKinshipMultiplier(Attackable victim, Player player)
	{
		if ((victim == null) || (player == null))
		{
			return 1.0;
		}

		final HotzoneModifier modifier = getModifierFor(victim);
		if ((modifier == null) || !modifier.isKinship())
		{
			return 1.0;
		}

		final Party party = player.getParty();
		if (party == null)
		{
			return FakePartyManager.getInstance().getFakeCount(player) > 0 ? 1.0 : KINSHIP_SOLO_MULT;
		}

		// Members in this very zone - another zone can have rolled the same modifier. An instance-wide modifier has no zone, so there it is the instance.
		final int zoneId = getActiveZoneId(victim);
		int together = 0;
		for (Player member : party.getMembers())
		{
			if ((member != null) && (member != player) && (zoneId > 0 ? getActiveZoneId(member) == zoneId : getModifierFor(member) == modifier))
			{
				together++;
			}
		}

		return Math.min(KINSHIP_MAX_MULT, 1.0 + (together * KINSHIP_BONUS_PER_MEMBER));
	}

	/**
	 * Kill-triggered modifier effects: VAMPIRIC_HUNT heals the killer, KILL_STREAK stacks its XP/SP buff one level higher, RESTLESS_DEAD may raise the victim once more, SPLITTING_GROUND may split it in two, HAIR_TRIGGER rewards a raging kill with Luck, RISING_HEAT counts the kill
	 * and BOUNTY_HUNT pays out a bounty. Called from {@code Attackable.doDie} only for kills that reward exp/sp.
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

		rewardKiller(modifier, killer);

		if ((modifier.getRiseChancePct() > 0) && victim.isMonster() && victim.asMonster().canHotzoneRise() && (Rnd.get(100) < modifier.getRiseChancePct()))
		{
			ThreadPool.schedule(() -> raiseAgain(victim.asMonster(), killer), RISE_DELAY_MS);
		}

		if ((modifier.getSplitChancePct() > 0) && victim.isMonster() && victim.asMonster().canHotzoneRise() && (Rnd.get(100) < modifier.getSplitChancePct()))
		{
			ThreadPool.schedule(() -> split(victim.asMonster(), killer), RISE_DELAY_MS);
		}

		if (modifier.isRageKillLuckStack() && victim.isMonster() && victim.asMonster().isRaging())
		{
			LuckyLootManager.getInstance().grantLuckStack(victim, killer);
		}

		if (modifier.isHeat())
		{
			addHeatKill(victim, killer);
		}

		if (victim.isMonster() && victim.asMonster().isHotzoneBounty())
		{
			onBountyKilled(victim.asMonster(), killer);
		}
	}

	/**
	 * CONTESTED_GROUND: a roaming fake player a player killed in the zone pays guaranteed coins (see {@link HotzoneCoinDropManager}) and a Sealed Cache. Called from {@code Attackable.doDie}: a fake player's death never rewards exp/sp, so {@link #onAttackableKilled} doesn't see it.
	 * @param victim the roaming fake player that just died
	 * @param killer the player credited with the kill
	 */
	public void onFakePlayerKilled(Attackable victim, Player killer)
	{
		if ((victim == null) || (killer == null) || !victim.isPvpFakePlayer())
		{
			return;
		}

		final HotzoneModifier modifier = getModifierFor(victim);
		if (modifier == null)
		{
			return;
		}

		if (modifier.getFakePlayerCoinMult() > 1.0)
		{
			HotzoneCoinDropManager.getInstance().onAttackableKilled(victim, killer);
		}

		if (modifier.isFakePlayerCache())
		{
			LuckyLootManager.getInstance().dropGuaranteedCache(victim, killer);
		}
	}

	/**
	 * A roaming fake player is no monster: like a player, it gets the kill effects of the modifier (VAMPIRIC_HUNT heal, KILL_STREAK buff) for the monsters it kills in a hotzone. Called from {@code Attackable.doDie}.
	 * @param victim the monster that just died
	 * @param killer the roaming fake player that killed it
	 */
	public void onAttackableKilledByFakePlayer(Attackable victim, Creature killer)
	{
		if ((victim == null) || (killer == null) || !killer.isPvpFakePlayer())
		{
			return;
		}

		final HotzoneModifier modifier = getModifierFor(victim);
		if (modifier != null)
		{
			rewardKiller(modifier, killer);
		}
	}

	/**
	 * VAMPIRIC_HUNT heals the killer, KILL_STREAK stacks its buff one level higher.
	 * @param modifier the modifier of the zone the kill happened in
	 * @param killer the player (or roaming fake player) that made the kill
	 */
	private void rewardKiller(HotzoneModifier modifier, Creature killer)
	{
		if (killer.isDead())
		{
			return;
		}

		if (modifier.getKillHealPct() > 0)
		{
			final double pct = modifier.getKillHealPct() / 100.0;
			killer.setCurrentHp(Math.min(killer.getMaxHp(), killer.getCurrentHp() + (killer.getMaxHp() * pct)));
			killer.setCurrentMp(Math.min(killer.getMaxMp(), killer.getCurrentMp() + (killer.getMaxMp() * pct)));
		}

		if (modifier.isKillStreak())
		{
			final BuffInfo current = killer.getEffectList().getBuffInfoBySkillId(HotzoneModifier.KILL_STREAK_SKILL_ID);
			final int level = Math.min((current != null ? current.getSkill().getLevel() : 0) + 1, HotzoneModifier.KILL_STREAK_MAX_LEVEL);
			final Skill streak = SkillData.getInstance().getSkill(HotzoneModifier.KILL_STREAK_SKILL_ID, level);
			if (streak != null)
			{
				streak.applyEffects(killer, killer);
			}
		}
	}

	private void raiseAgain(Monster victim, Player killer)
	{
		// The instance may have been destroyed (and its id reused) during the rise delay.
		if ((victim.getInstanceId() > 0) && (InstanceManager.getInstance().getInstance(victim.getInstanceId()) == null))
		{
			return;
		}

		final Monster risen = new Monster(victim.getTemplate());
		risen.setInstanceId(victim.getInstanceId());
		risen.getVariables().set(Monster.HOTZONE_RISEN_VAR, true);
		risen.setXYZ(victim.getX(), victim.getY(), victim.getZ());
		risen.setHeading(victim.getHeading());
		risen.spawnMe();
		risen.setCurrentHp(risen.getMaxHp() * 0.5);

		// A class transfer challenge tracks the risen monster so killing it again counts.
		if (ClassTransferChallengeManager.isChallengeInstance(risen.getInstanceId()))
		{
			ClassTransferChallengeManager.getInstance().onMonsterRisen(victim, risen);
		}

		if ((killer != null) && killer.isOnline())
		{
			killer.sendMessage("The " + risen.getName() + " rises again!");
		}
	}

	/**
	 * SPLITTING_GROUND: the victim comes back as two weaker copies next to its corpse, each worth half the rewards (see {@link Monster#isHotzoneSplit()}).
	 * @param victim the monster that died
	 * @param killer the player who killed it
	 */
	private void split(Monster victim, Player killer)
	{
		// The instance may have been destroyed (and its id reused) during the delay.
		if ((victim.getInstanceId() > 0) && (InstanceManager.getInstance().getInstance(victim.getInstanceId()) == null))
		{
			return;
		}

		for (int i = 0; i < 2; i++)
		{
			final Monster copy = new Monster(victim.getTemplate());
			copy.setInstanceId(victim.getInstanceId());
			copy.getVariables().set(Monster.HOTZONE_SPLIT_VAR, true);
			copy.setXYZ(victim.getX() + Rnd.get(-SPLIT_OFFSET, SPLIT_OFFSET), victim.getY() + Rnd.get(-SPLIT_OFFSET, SPLIT_OFFSET), victim.getZ());
			copy.setHeading(victim.getHeading());
			copy.spawnMe();
			copy.setCurrentHp(copy.getMaxHp() * SPLIT_HP_FRACTION);

			// A class transfer challenge tracks the copies so killing them counts.
			if (ClassTransferChallengeManager.isChallengeInstance(copy.getInstanceId()))
			{
				ClassTransferChallengeManager.getInstance().onMonsterRisen(victim, copy);
			}
		}

		if ((killer != null) && killer.isOnline())
		{
			killer.sendMessage("The " + victim.getName() + " splits in two!");
		}
	}

	/**
	 * RISING_HEAT: counts one kill and announces a new stack to everyone in the zone.
	 * @param victim the attackable that just died
	 * @param killer the player credited with the kill
	 */
	private void addHeatKill(Attackable victim, Player killer)
	{
		final int zoneId = getActiveZoneId(victim);
		final AtomicInteger kills = zoneId > 0 ? _heatKills.get(zoneId) : null;
		if (kills == null)
		{
			return;
		}

		final int total = kills.incrementAndGet();
		final int stacks = total / HotzoneModifier.HEAT_KILLS_PER_STACK;
		if (((total % HotzoneModifier.HEAT_KILLS_PER_STACK) != 0) || (stacks > HotzoneModifier.HEAT_MAX_STACKS))
		{
			return;
		}

		final String text = stacks >= HotzoneModifier.HEAT_MAX_STACKS ? "The hunt is at its hottest! Heat " + stacks + " (max)" : "The hunt heats up! Heat " + stacks;
		sendToZone(zoneId, text);
	}

	/**
	 * BOUNTY_HUNT: drops a mark nobody claimed in time, and posts a new one when it is due.
	 * @param zoneId the hotzone's zone id
	 * @param state the zone's bounty state
	 */
	private void tickBounty(int zoneId, BountyState state)
	{
		synchronized (state)
		{
			// Rotated out while this tick was waiting.
			if (_bounties.get(zoneId) != state)
			{
				return;
			}

			final long now = System.currentTimeMillis();
			final Monster target = state.target;
			if (target != null)
			{
				// Killed by someone the kill hook doesn't see (a fake player), gone, or lured out of the zone: the next one is posted on schedule.
				final ZoneType zone = ZoneManager.getInstance().getZoneById(zoneId);
				if (target.isDead() || !target.isSpawned() || !target.isHotzoneBounty() || (zone == null) || !zone.isInsideZone(target))
				{
					unmarkBounty(state);
					state.nextMarkAt = state.markedAt + BOUNTY_INTERVAL_MS;
					return;
				}

				if ((now - state.markedAt) < BOUNTY_DURATION_MS)
				{
					return;
				}

				// Nobody claimed it - the mark moves on right away.
				unmarkBounty(state);
				markBounty(zoneId, state, now, true);
				return;
			}

			if (now >= state.nextMarkAt)
			{
				markBounty(zoneId, state, now, false);
			}
		}
	}

	private void markBounty(int zoneId, BountyState state, long now, boolean moved)
	{
		final ZoneType zone = ZoneManager.getInstance().getZoneById(zoneId);
		if (zone == null)
		{
			return;
		}

		final List<Monster> candidates = new ArrayList<>();
		for (Creature creature : zone.getCharactersInside())
		{
			if (creature.isMonster() && isBountyCandidate(creature.asMonster()))
			{
				candidates.add(creature.asMonster());
			}
		}

		// Nothing to mark right now - try again on the next tick.
		if (candidates.isEmpty())
		{
			return;
		}

		final Monster target = candidates.get(Rnd.get(candidates.size()));
		target.setHotzoneBounty(true);
		state.target = target;
		state.markedAt = now;
		sendToZone(zoneId, (moved ? "The bounty moved to " : "A bounty is on ") + target.getName() + "! Claim it within " + (BOUNTY_DURATION_MS / 60000) + " minutes.");
	}

	private static boolean isBountyCandidate(Monster monster)
	{
		return !monster.isDead() && !monster.isRaid() && !monster.isMinion() && !monster.isHotzoneMiniboss() && !monster.isWaveChallenge() && !monster.getVariables().getBoolean("IS_ARENA_CHALLENGER", false) && !monster.isFakePlayer() && !monster.isPvpFakePlayer() && !monster.isQuestMonster() && !(monster instanceof Chest) && !monster.isHotzoneBounty();
	}

	private void unmarkBounty(BountyState state)
	{
		final Monster target = state.target;
		state.target = null;
		if ((target != null) && !target.isDead())
		{
			target.setHotzoneBounty(false);
		}
	}

	/**
	 * A player claimed a bounty: the coins are paid by {@link HotzoneCoinDropManager}, which runs first; this drops the Sealed Cache and schedules the next bounty.
	 * @param victim the bounty monster that just died
	 * @param killer the player credited with the kill
	 */
	private void onBountyKilled(Monster victim, Player killer)
	{
		LuckyLootManager.getInstance().dropGuaranteedCache(victim, killer);

		for (Map.Entry<Integer, BountyState> entry : _bounties.entrySet())
		{
			final BountyState state = entry.getValue();
			synchronized (state)
			{
				if (state.target == victim)
				{
					state.target = null;
					state.nextMarkAt = state.markedAt + BOUNTY_INTERVAL_MS;
					sendToZone(entry.getKey(), killer.getName() + " claimed the bounty on " + victim.getName() + "!");
				}
			}
		}
	}

	private void sendToZone(int zoneId, String text)
	{
		final ZoneType zone = ZoneManager.getInstance().getZoneById(zoneId);
		if (zone == null)
		{
			return;
		}

		final ExShowScreenMessage message = new ExShowScreenMessage(text, 5000);
		for (Creature creature : zone.getCharactersInside())
		{
			if (creature.isPlayer())
			{
				creature.sendPacket(message);
				creature.asPlayer().sendMessage(text);
			}
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
			// Players, and roaming fake players, which get the player side of a hotzone (the drain can't take them below their monster damage floor).
			final Creature player = creature.isPvpFakePlayer() ? creature : creature.asPlayer();
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
