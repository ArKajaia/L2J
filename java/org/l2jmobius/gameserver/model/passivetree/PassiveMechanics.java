package org.l2jmobius.gameserver.model.passivetree;

import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;

import org.l2jmobius.commons.threads.ThreadPool;
import org.l2jmobius.commons.util.Rnd;
import org.l2jmobius.gameserver.geoengine.GeoEngine;
import org.l2jmobius.gameserver.model.World;
import org.l2jmobius.gameserver.model.conditions.Condition;
import org.l2jmobius.gameserver.model.conditions.ConditionGameTime;
import org.l2jmobius.gameserver.model.conditions.ConditionLogicNot;
import org.l2jmobius.gameserver.model.conditions.ConditionPlayerHp;
import org.l2jmobius.gameserver.model.conditions.ConditionStandingStill;
import org.l2jmobius.gameserver.model.conditions.ConditionUsingItemType;
import org.l2jmobius.gameserver.model.actor.Creature;
import org.l2jmobius.gameserver.model.actor.Player;
import org.l2jmobius.gameserver.model.actor.holders.npc.FakePlayerPvpPassives;
import org.l2jmobius.gameserver.model.groups.Party;
import org.l2jmobius.gameserver.model.effects.EffectType;
import org.l2jmobius.gameserver.model.item.Weapon;
import org.l2jmobius.gameserver.model.item.type.ArmorType;
import org.l2jmobius.gameserver.model.item.type.WeaponType;
import org.l2jmobius.gameserver.model.skill.AbnormalType;
import org.l2jmobius.gameserver.model.skill.AbnormalVisualEffect;
import org.l2jmobius.gameserver.model.skill.BuffInfo;
import org.l2jmobius.gameserver.model.skill.Skill;
import org.l2jmobius.gameserver.model.skill.targets.TargetType;
import org.l2jmobius.gameserver.model.stats.BaseStat;
import org.l2jmobius.gameserver.model.stats.Stat;
import org.l2jmobius.gameserver.network.SystemMessageId;
import org.l2jmobius.gameserver.network.serverpackets.ExShowScreenMessage;
import org.l2jmobius.gameserver.network.serverpackets.MagicSkillLaunched;
import org.l2jmobius.gameserver.network.serverpackets.MagicSkillUse;
import org.l2jmobius.gameserver.network.serverpackets.SystemMessage;
import org.l2jmobius.gameserver.util.LocationUtil;

/**
 * Build-defining passive tree keystones: rules that change how a mechanic works rather than adding a stat. Every value is read from the allocated nodes' effect strings (see {@link PassiveStatBonusCache}), so balancing stays in data/passivetree/*.xml.
 * <p>
 * Players and roaming fake players own a passive tree (a fake player's is rolled by {@link org.l2jmobius.gameserver.managers.FakePlayerPvpPassiveTree}). Every helper is a no-op for anything else (summons, NPCs), so the hooks in the combat code can call them unconditionally. Fake
 * players only take the keystones that work for them (FakePvpKeystones.* in FakePlayerPvp.ini); the ones built on MP, CP, servitors or parties stay player-only.
 */
public final class PassiveMechanics
{
	/** % of incoming HP damage taken from MP first, until MP runs out. */
	public static final String MIND_OVER_MATTER = "KS_MIND_OVER_MATTER";
	/** Spell damage bonus % at 0% MP, scaling linearly down to 0 at full MP. */
	public static final String ARCANE_OVERLOAD = "KS_ARCANE_OVERLOAD";
	/** Flag: stuns never land. */
	public static final String STUN_IMMUNE = "KS_STUN_IMMUNE";
	/** Flag: toggle skills cost and drain HP instead of MP. */
	public static final String BLOOD_TOGGLES = "KS_BLOOD_TOGGLES";
	/** Every skill MP cost is paid with HP; value = % of max MP added to max HP. */
	public static final String BLOOD_MAGIC = "KS_BLOOD_MAGIC";
	/** Bow/spell damage % at point-blank range, the same % lower at long range. */
	public static final String POINT_BLANK = "KS_POINT_BLANK";
	/** Bow/spell damage % at long range, the same % lower at point-blank range. */
	public static final String FAR_SHOT = "KS_FAR_SHOT";
	/** Flag: CON-resisted effects are resisted with MEN instead. */
	public static final String MEN_STUN = "KS_MEN_STUN";
	/** M.Def is replaced by P.Def, reduced by this %. */
	public static final String PDEF_AS_MDEF = "KS_PDEF_AS_MDEF";
	/** % chance per landed normal hit to gain a Force charge and a soul. */
	public static final String SOUL_HARVEST = "KS_SOUL_HARVEST";
	/** Physical critical rate cap (1000 scale) replacing the configured one. */
	public static final String CRIT_CAP = "KS_CRIT_CAP";
	/** Flag: attacks and physical skills never miss, never critically hit. */
	public static final String RESOLUTE = "KS_RESOLUTE";
	/** P.Atk bonus % at 0% HP, scaling linearly down to 0 at full HP. */
	public static final String RAMPAGE = "KS_RAMPAGE";
	/** % of direct spell damage dealt restored as HP. */
	public static final String SPELL_LEECH = "KS_SPELL_LEECH";
	/** Max CP becomes 1; this % of the CP you would have had is added to max MP. */
	public static final String ELDRITCH_BATTERY = "KS_ELDRITCH_BATTERY";
	/** Flag: poison and bleed never land. */
	public static final String PURITY = "KS_PURITY";
	/** % of healing you cast beyond a player's max HP granted to them as CP. */
	public static final String OVERHEAL_CP = "KS_OVERHEAL_CP";
	/** Normal attacks with any melee weapon also hit up to CLEAVE_TARGETS more enemies in front of you, for this % of the damage; a polearm hits CLEAVE_TARGETS more than it would. */
	public static final String WHIRLING_STEEL = "KS_WHIRLING_STEEL";
	/** Flag: a shield block makes your next normal attack or physical skill within RIPOSTE_WINDOW a critical hit (a blow skill lands); your critical rate is halved otherwise. */
	public static final String RIPOSTE = "KS_RIPOSTE";
	/** % of the damage party members within GUARDIAN_RANGE take that you take instead. */
	public static final String GUARDIAN = "KS_GUARDIAN";
	/** Single-target damage spells arc to ARC_TARGETS more enemies near the target, one after the other with the spell's animation, the first for this % of the damage, each next one for half the one before. */
	public static final String ARC_CONDUIT = "KS_ARC_CONDUIT";
	/** % of M.Atk added to P.Atk. */
	public static final String BATTLEMAGE = "KS_BATTLEMAGE";
	/** % chance for a damage spell to Surge (CHAOS_SURGE times the damage), half this % to Fizzle (no damage); no magic critical hits. */
	public static final String CHAOS_WEAVE = "KS_CHAOS_WEAVE";
	/** Once every PHOENIX_COOLDOWN, a killing blow leaves you alive with this % of max HP. */
	public static final String PHOENIX = "KS_PHOENIX";
	/** Each normal hit landed on your target adds this % Atk. Spd, up to RELENTLESS_MAX_STACKS hits; switching target or RELENTLESS_WINDOW without a hit resets it. */
	public static final String RELENTLESS = "KS_RELENTLESS";
	/** Physical critical hits deal no critical bonus up front; the target bleeds for this % of it over BLEED_TICKS seconds instead. */
	public static final String BLOODLETTER = "KS_BLOODLETTER";
	/** % chance for a single-target damage spell to hit its target again ECHO_DELAY later, free, for ECHO_DAMAGE of the damage. */
	public static final String SPELL_ECHO = "KS_SPELL_ECHO";
	
	/** Keys that cancel each other out; a node carrying one can't be allocated next to a node carrying another key of the same group. */
	private static final List<Set<String>> EXCLUSIVE_GROUPS = List.of(Set.of(POINT_BLANK, FAR_SHOT));
	
	/** Point Blank / Far Shot: full bonus at or below this range, full penalty at or above LONG_RANGE. */
	private static final double POINT_BLANK_RANGE = 150;
	private static final double LONG_RANGE = 900;
	
	/** Whirling Steel: how many more enemies a normal attack hits, within what range and angle of your heading (a polearm's). */
	public static final int CLEAVE_TARGETS = 2;
	public static final int CLEAVE_RANGE = 80;
	public static final int CLEAVE_ANGLE = 120;
	
	/** Riposte: how long a shield block keeps the riposte ready, and the critical rate multiplier the rest of the time. */
	private static final long RIPOSTE_WINDOW = 5000;
	private static final double RIPOSTE_CRIT_RATE = 0.5;
	/** objectId -> time the readied riposte runs out. */
	private static final Map<Integer, Long> RIPOSTE_READY = new ConcurrentHashMap<>();
	
	/** Guardian's Oath: range to the party member being hit. */
	private static final int GUARDIAN_RANGE = 900;
	/** Set while a guardian takes a share, so the share isn't passed on again (to another guardian). */
	private static final ThreadLocal<Boolean> GUARDING = ThreadLocal.withInitial(() -> Boolean.FALSE);
	
	/** Arc Conduit: how many times a spell jumps, and how far from the creature it jumps from. */
	private static final int ARC_TARGETS = 2;
	private static final int ARC_RANGE = 300;
	/** Arc Conduit: time between the hit and the first jump, and between jumps, so the chain is seen jump by jump. */
	private static final long ARC_DELAY = 200;
	/** Target types of the spells that arc: single-target ones. */
	private static final Set<TargetType> SINGLE_TARGET = EnumSet.of(TargetType.ONE, TargetType.UNDEAD, TargetType.ENEMY_SUMMON);
	/** Set while arcs are dealt, so an arc doesn't arc again. */
	private static final ThreadLocal<Boolean> ARCING = ThreadLocal.withInitial(() -> Boolean.FALSE);
	
	/** Chaos Weave: damage multiplier of a Surge. */
	private static final double CHAOS_SURGE = 2.5;
	
	/** Ley Anchor: how long you must stand still for the STILL condition. */
	public static final long STILL_TIME = 2000;
	
	/** Phoenix Heart: cooldown, and the skill whose animation plays (Soul of the Phoenix). */
	private static final long PHOENIX_COOLDOWN = 300000;
	private static final int PHOENIX_VISUAL_SKILL = 438;
	/** objectId -> time Phoenix Heart can save the player again. */
	private static final Map<Integer, Long> PHOENIX_READY_AT = new ConcurrentHashMap<>();
	
	/** Relentless Assault: most hits that count, and how long a streak lasts without a hit. */
	private static final int RELENTLESS_MAX_STACKS = 10;
	private static final long RELENTLESS_WINDOW = 3000;
	/** objectId -> the player's current Relentless Assault streak. */
	private static final Map<Integer, Relentless> RELENTLESS_STREAKS = new ConcurrentHashMap<>();
	
	/** Bloodletter: how many ticks a bleed lasts, one a second; a new critical hit adds to it and starts the count again. */
	private static final int BLEED_TICKS = 6;
	private static final long BLEED_PERIOD = 1000;
	/** attacker objectId << 32 | target objectId -> the bleed that attacker keeps on that target. */
	private static final Map<Long, Bleed> BLEEDS = new ConcurrentHashMap<>();
	
	/** Spell Echo: delay and damage of the echo, and how far the target may have gone. */
	private static final long ECHO_DELAY = 500;
	private static final double ECHO_DAMAGE = 0.6;
	private static final int ECHO_RANGE = 1500;
	/** Set while an echo is dealt, so an echo doesn't echo again. */
	private static final ThreadLocal<Boolean> ECHOING = ThreadLocal.withInitial(() -> Boolean.FALSE);
	
	/** Force charge skills: Sonic Focus, Focused Force, Sonic Mastery, Force Mastery. Their level is the most charges the class can hold. */
	private static final int[] CHARGE_SKILLS =
	{
		8,
		50,
		992,
		993
	};
	
	private PassiveMechanics()
	{
	}
	
	/**
	 * @param creature any creature
	 * @param key a passive tree effect key
	 * @return the creature's total for that key, or 0 if it has no passive tree
	 */
	public static double value(Creature creature, String key)
	{
		final PassiveStatBonusCache bonus = bonusOf(creature);
		return bonus == null ? 0 : bonus.get(key);
	}
	
	/**
	 * @param creature any creature
	 * @return the passive tree totals of a player or a roaming fake player, {@code null} for anything else
	 */
	private static PassiveStatBonusCache bonusOf(Creature creature)
	{
		if (creature == null)
		{
			return null;
		}
		
		if (creature.isPlayer())
		{
			return creature.asPlayer().getPassiveStatBonus();
		}
		
		if (creature.isPvpFakePlayer())
		{
			final FakePlayerPvpPassives passives = creature.asNpc().getTemplate().getFakePlayerPvpProfile().getPassives();
			return passives == null ? null : passives.getBonus();
		}
		return null;
	}
	
	public static boolean has(Creature creature, String key)
	{
		return value(creature, key) > 0;
	}
	
	// ---------------------------------------------------------------- resource pools
	
	/**
	 * @param player the player
	 * @param statMaxCp max CP from the stat system
	 * @return max CP after the tree's flat bonus and Eldritch Battery
	 */
	public static double maxCp(Player player, double statMaxCp)
	{
		if (has(player, ELDRITCH_BATTERY))
		{
			return 1;
		}
		return statMaxCp + value(player, "MAXCP");
	}
	
	/**
	 * @param player the player
	 * @param statMaxMp max MP from the stat system
	 * @param statMaxCp max CP from the stat system, which Eldritch Battery converts into MP
	 * @return max MP after the tree's flat bonus and Eldritch Battery
	 */
	public static double maxMp(Player player, double statMaxMp, double statMaxCp)
	{
		double result = statMaxMp + value(player, "MAXMP");
		final double battery = value(player, ELDRITCH_BATTERY);
		if (battery > 0)
		{
			result += ((statMaxCp + value(player, "MAXCP")) * battery) / 100;
		}
		return Math.max(1, result);
	}
	
	/**
	 * @param player the player
	 * @param statMaxHp max HP from the stat system
	 * @return max HP after the tree's flat and % bonuses and Blood Magic
	 */
	public static double maxHp(Player player, double statMaxHp)
	{
		double result = (statMaxHp + value(player, "MAXHP")) * (1 + (value(player, "MAXHP_PCT") / 100));
		final double bloodMagic = value(player, BLOOD_MAGIC);
		if (bloodMagic > 0)
		{
			result += (player.getStat().getMaxMp() * bloodMagic) / 100;
		}
		return Math.max(1, result);
	}
	
	// ---------------------------------------------------------------- skill costs
	
	/**
	 * @param creature the caster
	 * @param skill the skill being paid for
	 * @return {@code true} if this skill's MP costs are paid with HP (Martyr's Vow for every skill, Blood Stance for toggles)
	 */
	public static boolean paysWithHp(Creature creature, Skill skill)
	{
		return (skill != null) && (has(creature, BLOOD_MAGIC) || (skill.isToggle() && has(creature, BLOOD_TOGGLES)));
	}
	
	/**
	 * Takes an MP cost out of HP instead. An HP cost never kills: nothing is taken unless at least 1 HP would be left.
	 * @param creature the caster
	 * @param cost the MP cost
	 * @return {@code true} if it was paid
	 */
	public static boolean payWithHp(Creature creature, double cost)
	{
		if (cost <= 0)
		{
			return true;
		}
		
		if (cost > (creature.getCurrentHp() - 1))
		{
			return false;
		}
		
		creature.getStatus().reduceHp(cost, creature, true);
		return true;
	}
	
	/**
	 * Per-tick MP drain of a toggle (MpConsumePerLevel, ManaDamOverTime), paid with HP under Blood Stance or Martyr's Vow.
	 * @param effected the toggle owner
	 * @param skill the toggle
	 * @param cost the MP the tick would drain
	 * @return {@code null} if the drain stays on MP, otherwise whether the toggle keeps running
	 */
	public static Boolean drainToggleWithHp(Creature effected, Skill skill, double cost)
	{
		if (!skill.isToggle() || !paysWithHp(effected, skill))
		{
			return null;
		}
		
		if (!payWithHp(effected, cost))
		{
			effected.sendPacket(SystemMessageId.YOUR_SKILL_HAS_BEEN_CANCELED_DUE_TO_LACK_OF_HP);
			return false;
		}
		return true;
	}
	
	// ---------------------------------------------------------------- damage
	
	/**
	 * Mind Over Matter: part of the damage about to hit HP is taken from MP instead.
	 * @param player the player taking damage
	 * @param amount damage about to be applied to HP
	 * @return the damage left for HP
	 */
	public static double absorbDamageWithMp(Player player, double amount)
	{
		final double pct = value(player, MIND_OVER_MATTER);
		if ((pct <= 0) || (amount <= 0))
		{
			return amount;
		}
		
		final double mpDamage = Math.min((amount * pct) / 100, player.getCurrentMp());
		if (mpDamage <= 0)
		{
			return amount;
		}
		
		player.getStatus().reduceMp(mpDamage);
		return amount - mpDamage;
	}
	
	/**
	 * @param attacker the caster
	 * @param target the target
	 * @param skill the damaging skill
	 * @return Arcane Overload and Point Blank / Far Shot multiplier for spell damage
	 */
	public static double spellDamageMultiplier(Creature attacker, Creature target, Skill skill)
	{
		if ((skill == null) || !skill.isMagic())
		{
			return 1;
		}
		
		double multiplier = rangeMultiplier(attacker, target);
		final double overload = value(attacker, ARCANE_OVERLOAD);
		if (overload > 0)
		{
			final double missingMp = 1 - Math.min(1, attacker.getCurrentMp() / Math.max(1, attacker.getMaxMp()));
			multiplier *= 1 + ((overload / 100) * missingMp);
		}
		return multiplier * chaosWeaveMultiplier(attacker);
	}
	
	/**
	 * Chaos Weave: a damage spell may Surge or Fizzle.
	 * @param attacker the caster
	 * @return the damage multiplier of this cast: CHAOS_SURGE, 0 or 1
	 */
	private static double chaosWeaveMultiplier(Creature attacker)
	{
		final double chance = value(attacker, CHAOS_WEAVE);
		if (chance <= 0)
		{
			return 1;
		}
		
		final double roll = Rnd.get(1000) / 10.0;
		if (roll < chance)
		{
			attacker.sendPacket(new ExShowScreenMessage("Chaos Weave: your spell surges!", ExShowScreenMessage.BOTTOM_CENTER, 1500));
			return CHAOS_SURGE;
		}
		if (roll < (chance * 1.5))
		{
			attacker.sendPacket(new ExShowScreenMessage("Chaos Weave: your spell fizzles.", ExShowScreenMessage.BOTTOM_CENTER, 1500));
			return 0;
		}
		return 1;
	}
	
	/**
	 * @param creature the caster
	 * @return {@code true} if the creature can't land magic critical hits (Chaos Weave)
	 */
	public static boolean cannotMagicCrit(Creature creature)
	{
		return has(creature, CHAOS_WEAVE);
	}
	
	/**
	 * @param attacker the attacker
	 * @param target the target
	 * @return Point Blank / Far Shot multiplier for bow and crossbow attacks and skills
	 */
	public static double bowDamageMultiplier(Creature attacker, Creature target)
	{
		final WeaponType type;
		if (attacker.isPlayer())
		{
			final Weapon weapon = attacker.getActiveWeaponItem();
			type = weapon != null ? weapon.getItemType() : null;
		}
		else
		{
			type = attacker.isPvpFakePlayer() ? attacker.getAttackType() : null; // the weapon it holds now (it may have its bow out)
		}
		
		if ((type != WeaponType.BOW) && (type != WeaponType.CROSSBOW))
		{
			return 1;
		}
		return rangeMultiplier(attacker, target);
	}
	
	private static double rangeMultiplier(Creature attacker, Creature target)
	{
		final double pointBlank = value(attacker, POINT_BLANK);
		final double farShot = value(attacker, FAR_SHOT);
		if (((pointBlank <= 0) && (farShot <= 0)) || (target == null))
		{
			return 1;
		}
		
		// 0 at point-blank range, 1 at long range.
		final double far = Math.max(0, Math.min(1, (attacker.calculateDistance3D(target) - POINT_BLANK_RANGE) / (LONG_RANGE - POINT_BLANK_RANGE)));
		return Math.max(0, 1 + ((pointBlank / 100) * (1 - (2 * far))) + ((farShot / 100) * ((2 * far) - 1)));
	}
	
	/**
	 * Unending Fury (Rampage): P.Atk rises as HP falls.
	 * @param creature the player or roaming fake player
	 * @return the P.Atk multiplier
	 */
	public static double rampageMultiplier(Creature creature)
	{
		final double rampage = value(creature, RAMPAGE);
		if (rampage <= 0)
		{
			return 1;
		}
		
		final double missingHp = 1 - Math.min(1, creature.getCurrentHp() / Math.max(1, creature.getMaxHp()));
		return 1 + ((rampage / 100) * missingHp);
	}
	
	/**
	 * Vampiric Sorcery: direct spell damage heals the caster. Arc Conduit: it jumps to more enemies.
	 * @param attacker the caster
	 * @param target the creature that took the damage
	 * @param skill the damaging skill
	 * @param damage the damage dealt
	 * @param damageOverTime whether it was a damage-over-time tick
	 */
	public static void onDamageDealt(Creature attacker, Creature target, Skill skill, double damage, boolean damageOverTime)
	{
		if (damageOverTime || (damage <= 0) || (skill == null) || !skill.isMagic() || attacker.isDead())
		{
			return;
		}
		
		final double leech = value(attacker, SPELL_LEECH);
		if (leech > 0)
		{
			final double heal = Math.min((damage * leech) / 100, attacker.getMaxRecoverableHp() - attacker.getCurrentHp());
			if (heal > 0)
			{
				attacker.setCurrentHp(attacker.getCurrentHp() + heal);
			}
		}
		
		arc(attacker, target, skill, damage);
		echo(attacker, target, skill, damage);
	}
	
	/**
	 * Spell Echo: a single-target damage spell may hit its target again a moment later.
	 * @param attacker the caster
	 * @param target the spell's target
	 * @param skill the spell
	 * @param damage the damage the target took
	 */
	private static void echo(Creature attacker, Creature target, Skill skill, double damage)
	{
		final double chance = value(attacker, SPELL_ECHO);
		if ((chance <= 0) || (target == null) || (target == attacker) || !SINGLE_TARGET.contains(skill.getTargetType()) || ARCING.get() || ECHOING.get() || (Rnd.get(100) >= chance))
		{
			return;
		}
		
		ThreadPool.schedule(() -> echoHit(attacker, target, skill, damage * ECHO_DAMAGE), ECHO_DELAY);
	}
	
	private static void echoHit(Creature caster, Creature target, Skill skill, double damage)
	{
		if ((damage < 1) || caster.isDead() || !caster.isSpawned() || target.isAlikeDead() || !target.isSpawned() || !target.isAutoAttackable(caster) || caster.isInsidePeaceZone(caster, target) || (caster.calculateDistance3D(target) > ECHO_RANGE))
		{
			return;
		}
		
		caster.broadcastPacket(new MagicSkillUse(caster, target, skill.getDisplayId(), skill.getDisplayLevel(), 0, 0));
		caster.broadcastPacket(new MagicSkillLaunched(caster, skill.getDisplayId(), skill.getDisplayLevel(), target));
		if (caster.isPlayer() && target.isPlayable())
		{
			caster.asPlayer().updatePvPStatus(target);
		}
		
		ECHOING.set(Boolean.TRUE);
		try
		{
			target.reduceCurrentHp(damage, caster, skill);
			target.notifyDamageReceived(damage, caster, skill, false, false);
			caster.sendDamageMessage(target, (int) damage, false, false, false);
		}
		finally
		{
			ECHOING.set(Boolean.FALSE);
		}
	}
	
	/**
	 * Arc Conduit: a single-target damage spell jumps from its target to the nearest enemy, and from there to the next.
	 * @param attacker the caster
	 * @param target the spell's target
	 * @param skill the spell
	 * @param damage the damage the target took
	 */
	private static void arc(Creature attacker, Creature target, Skill skill, double damage)
	{
		final double pct = value(attacker, ARC_CONDUIT);
		if ((pct <= 0) || (target == null) || (target == attacker) || !SINGLE_TARGET.contains(skill.getTargetType()) || ARCING.get())
		{
			return;
		}
		
		final Set<Creature> struck = ConcurrentHashMap.newKeySet();
		struck.add(target);
		ThreadPool.schedule(() -> arcJump(attacker, target, skill, (damage * pct) / 100, 1, struck), ARC_DELAY);
	}
	
	/**
	 * One jump of an Arc Conduit chain: the spell's animation flies from the creature it jumps from to the nearest enemy, which takes the damage. The next jump follows ARC_DELAY later, for half the damage.
	 * @param caster the caster
	 * @param from the creature the spell jumps from
	 * @param skill the spell
	 * @param arcDamage the damage of this jump
	 * @param jump which jump this is, from 1
	 * @param struck the creatures the spell already hit
	 */
	private static void arcJump(Creature caster, Creature from, Skill skill, double arcDamage, int jump, Set<Creature> struck)
	{
		if ((arcDamage < 1) || caster.isDead() || !caster.isSpawned() || !from.isSpawned())
		{
			return;
		}
		
		final Creature next = nearestArcTarget(caster, from, struck);
		if (next == null)
		{
			return;
		}
		
		struck.add(next);
		from.broadcastPacket(new MagicSkillUse(from, next, skill.getDisplayId(), skill.getDisplayLevel(), 0, 0));
		from.broadcastPacket(new MagicSkillLaunched(from, skill.getDisplayId(), skill.getDisplayLevel(), next));
		if (caster.isPlayer() && next.isPlayable())
		{
			caster.asPlayer().updatePvPStatus(next);
		}
		
		ARCING.set(Boolean.TRUE);
		try
		{
			next.reduceCurrentHp(arcDamage, caster, skill);
			next.notifyDamageReceived(arcDamage, caster, skill, false, false);
			caster.sendDamageMessage(next, (int) arcDamage, false, false, false);
		}
		finally
		{
			ARCING.set(Boolean.FALSE);
		}
		
		if (jump < ARC_TARGETS)
		{
			ThreadPool.schedule(() -> arcJump(caster, next, skill, arcDamage / 2, jump + 1, struck), ARC_DELAY);
		}
	}
	
	private static Creature nearestArcTarget(Creature caster, Creature from, Set<Creature> struck)
	{
		final Creature summon = caster.isPlayer() ? caster.asPlayer().getSummon() : null;
		Creature nearest = null;
		double nearestDistance = Double.MAX_VALUE;
		for (Creature creature : World.getInstance().getVisibleObjectsInRange(from, Creature.class, ARC_RANGE))
		{
			if ((creature == caster) || struck.contains(creature) || creature.isAlikeDead() || !creature.isAutoAttackable(caster) || (creature == summon) || caster.isInsidePeaceZone(caster, creature))
			{
				continue;
			}
			
			final double distance = from.calculateDistance3D(creature);
			if ((distance < nearestDistance) && GeoEngine.getInstance().canSeeTarget(from, creature))
			{
				nearest = creature;
				nearestDistance = distance;
			}
		}
		return nearest;
	}
	
	/**
	 * Guardian's Oath: a share of the damage a party member takes goes to a guardian nearby. The share never kills the guardian.
	 * @param player the party member being hit
	 * @param attacker the attacker
	 * @param amount damage about to be applied
	 * @return the damage left for the party member
	 */
	public static double redirectToGuardian(Player player, Creature attacker, double amount)
	{
		final Party party = player.getParty();
		if ((amount <= 0) || (party == null) || GUARDING.get())
		{
			return amount;
		}
		
		Player guardian = null;
		double pct = 0;
		for (Player member : party.getMembers())
		{
			if ((member == player) || (member == attacker) || member.isDead() || member.isInDuel() || !LocationUtil.checkIfInRange(GUARDIAN_RANGE, player, member, true))
			{
				continue;
			}
			
			final double value = value(member, GUARDIAN);
			if (value > pct)
			{
				guardian = member;
				pct = value;
			}
		}
		if (guardian == null)
		{
			return amount;
		}
		
		final double share = Math.min((amount * Math.min(pct, 100)) / 100, guardian.getCurrentHp() - 1);
		if (share < 1)
		{
			return amount;
		}
		
		GUARDING.set(Boolean.TRUE);
		try
		{
			guardian.reduceCurrentHp(share, attacker, null);
		}
		finally
		{
			GUARDING.set(Boolean.FALSE);
		}
		return amount - share;
	}
	
	/**
	 * Phoenix Heart: once every PHOENIX_COOLDOWN, a killing blow leaves the player alive.
	 * @param player the player about to die
	 * @return the HP to leave the player with, 0 to let them die
	 */
	public static double phoenixRebirth(Player player)
	{
		final double pct = value(player, PHOENIX);
		if (pct <= 0)
		{
			return 0;
		}
		
		final long now = System.currentTimeMillis();
		final Long readyAt = PHOENIX_READY_AT.get(player.getObjectId());
		if ((readyAt != null) && (readyAt > now))
		{
			return 0;
		}
		
		PHOENIX_READY_AT.put(player.getObjectId(), now + PHOENIX_COOLDOWN);
		player.broadcastPacket(new MagicSkillUse(player, player, PHOENIX_VISUAL_SKILL, 1, 0, 0));
		player.sendPacket(new ExShowScreenMessage("Phoenix Heart: you rise from the ashes! It is ready again in " + (PHOENIX_COOLDOWN / 60000) + " minutes.", ExShowScreenMessage.TOP_CENTER, 4000));
		return Math.max(1, (player.getMaxHp() * Math.min(pct, 100)) / 100);
	}
	
	// ---------------------------------------------------------------- hit / crit
	
	/**
	 * @param attacker the attacker
	 * @return {@code true} if the attacker's attacks and physical skills can't miss (Resolute Technique)
	 */
	public static boolean neverMisses(Creature attacker)
	{
		return has(attacker, RESOLUTE);
	}
	
	/**
	 * @param attacker the attacker
	 * @return {@code true} if the attacker can't land physical critical hits (Resolute Technique)
	 */
	public static boolean cannotCrit(Creature attacker)
	{
		return has(attacker, RESOLUTE);
	}
	
	/**
	 * Riposte: a shield block readies a critical counter-attack. A dodge doesn't.
	 * @param target the creature that blocked
	 */
	public static void onShieldBlock(Creature target)
	{
		if (has(target, RIPOSTE))
		{
			RIPOSTE_READY.put(target.getObjectId(), System.currentTimeMillis() + RIPOSTE_WINDOW);
		}
	}
	
	/**
	 * @param attacker the attacker
	 * @return {@code true} if the attacker had a riposte ready: this hit is a critical one (or a blow that lands), and the riposte is spent
	 */
	public static boolean consumeRiposte(Creature attacker)
	{
		if ((attacker == null) || RIPOSTE_READY.isEmpty())
		{
			return false;
		}
		
		final Long until = RIPOSTE_READY.remove(attacker.getObjectId());
		return (until != null) && (until >= System.currentTimeMillis());
	}
	
	/**
	 * @param attacker the attacker
	 * @return the multiplier on the attacker's critical rate (Riposte halves it)
	 */
	public static double critRateMultiplier(Creature attacker)
	{
		return has(attacker, RIPOSTE) ? RIPOSTE_CRIT_RATE : 1;
	}
	
	/**
	 * @param creature the attacker
	 * @param configuredCap the server's physical critical rate cap
	 * @return the cap that applies to this creature (Thousand Cuts raises it)
	 */
	public static double critCap(Creature creature, double configuredCap)
	{
		final double cap = value(creature, CRIT_CAP);
		return cap > 0 ? Math.max(cap, configuredCap) : configuredCap;
	}
	
	/**
	 * A normal hit landed: Soul Harvest and Relentless Assault.
	 * @param attacker the attacker
	 * @param target the creature it hit
	 */
	public static void onNormalHitLanded(Creature attacker, Creature target)
	{
		harvestSoul(attacker);
		addRelentlessHit(attacker, target);
	}
	
	/**
	 * Soul Harvest: a landed normal hit may grant a Force charge and a soul.
	 * @param attacker the attacker
	 */
	private static void harvestSoul(Creature attacker)
	{
		final double chance = value(attacker, SOUL_HARVEST);
		if ((chance <= 0) || !attacker.isPlayer() || (Rnd.get(100) >= chance))
		{
			return;
		}
		
		final Player player = attacker.asPlayer();
		final int maxCharges = maxCharges(player);
		if (player.getCharges() < maxCharges)
		{
			player.increaseCharges(1, maxCharges);
		}
		
		final int maxSouls = (int) player.calcStat(Stat.MAX_SOULS, 0, null, null);
		if (player.getChargedSouls() < maxSouls)
		{
			player.increaseSouls(1);
		}
	}
	
	/** A Relentless Assault streak: the target, how many hits on it count, and when the last one landed. */
	private static final class Relentless
	{
		int targetId;
		int hits;
		long lastHit;
		boolean expiryScheduled;
		/** Set once the streak has ended and left RELENTLESS_STREAKS (a fake player comes back with a new object id, so ended streaks don't stay behind). */
		boolean removed;
	}
	
	/**
	 * Relentless Assault: a normal hit on your target (not one a sweep lands on another enemy) adds to the streak; a hit on another target starts a new one.
	 * @param attacker the attacker
	 * @param target the creature it hit
	 */
	private static void addRelentlessHit(Creature attacker, Creature target)
	{
		if ((target == null) || (target != attacker.getTarget()) || !has(attacker, RELENTLESS))
		{
			return;
		}
		
		Relentless streak;
		boolean changed;
		boolean scheduleExpiry;
		while (true)
		{
			streak = RELENTLESS_STREAKS.computeIfAbsent(attacker.getObjectId(), id -> new Relentless());
			synchronized (streak)
			{
				if (streak.removed)
				{
					continue; // ended while this hit was on its way: start a new one
				}
				
				final long now = System.currentTimeMillis();
				final int before = streak.hits;
				if ((streak.targetId != target.getObjectId()) || ((now - streak.lastHit) > RELENTLESS_WINDOW))
				{
					streak.targetId = target.getObjectId();
					streak.hits = 1;
				}
				else
				{
					streak.hits = Math.min(RELENTLESS_MAX_STACKS, streak.hits + 1);
				}
				streak.lastHit = now;
				changed = streak.hits != before;
				scheduleExpiry = !streak.expiryScheduled;
				streak.expiryScheduled = true;
				break;
			}
		}
		
		if (scheduleExpiry)
		{
			final Relentless scheduled = streak;
			ThreadPool.schedule(() -> expireRelentless(attacker, scheduled), RELENTLESS_WINDOW);
		}
		if (changed)
		{
			broadcastAttackSpeed(attacker); // the client animates attacks at the speed it was last told
		}
	}
	
	/**
	 * @param creature a player or roaming fake player whose attack speed changed
	 */
	private static void broadcastAttackSpeed(Creature creature)
	{
		if (creature.isPlayer())
		{
			creature.asPlayer().broadcastUserInfo();
		}
		else
		{
			creature.broadcastInfo();
		}
	}
	
	/**
	 * Ends a streak once RELENTLESS_WINDOW has passed without a hit, so the client shows the plain attack speed again.
	 * @param creature the player or roaming fake player
	 * @param streak its streak
	 */
	private static void expireRelentless(Creature creature, Relentless streak)
	{
		final long left;
		final boolean ended;
		synchronized (streak)
		{
			left = (streak.lastHit + RELENTLESS_WINDOW) - System.currentTimeMillis();
			ended = (left <= 0) && (streak.hits > 0);
			if (left <= 0)
			{
				streak.hits = 0;
				streak.expiryScheduled = false;
				streak.removed = true;
				RELENTLESS_STREAKS.remove(creature.getObjectId(), streak);
			}
		}
		
		if (left > 0)
		{
			ThreadPool.schedule(() -> expireRelentless(creature, streak), left);
		}
		else if (ended && creature.isSpawned() && (!creature.isPlayer() || creature.asPlayer().isOnline()))
		{
			broadcastAttackSpeed(creature);
		}
	}
	
	/**
	 * @param creature the player or roaming fake player
	 * @return the Atk. Spd multiplier of its Relentless Assault streak
	 */
	public static double relentlessMultiplier(Creature creature)
	{
		final double pct = value(creature, RELENTLESS);
		final Relentless streak = pct > 0 ? RELENTLESS_STREAKS.get(creature.getObjectId()) : null;
		if (streak == null)
		{
			return 1;
		}
		
		synchronized (streak)
		{
			return (System.currentTimeMillis() - streak.lastHit) > RELENTLESS_WINDOW ? 1 : 1 + ((streak.hits * pct) / 100);
		}
	}
	
	// ---------------------------------------------------------------- bleeding
	
	/** A Bloodletter bleed one attacker keeps on one target: the damage still to come and the ticks left to deal it in. */
	private static final class Bleed
	{
		final Creature attacker;
		final Creature target;
		double remaining;
		int ticksLeft;
		boolean ended;
		ScheduledFuture<?> task;
		
		Bleed(Creature attacker, Creature target)
		{
			this.attacker = attacker;
			this.target = target;
		}
	}
	
	/**
	 * @param attacker the attacker
	 * @return {@code true} if the attacker's critical bonus bleeds instead of landing up front (Bloodletter)
	 */
	public static boolean bleedsCriticalBonus(Creature attacker)
	{
		return has(attacker, BLOODLETTER);
	}
	
	/**
	 * Bloodletter: the critical bonus held back from a hit bleeds over BLEED_TICKS seconds. A new one adds to the bleed already running and starts its count again.
	 * @param attacker the attacker
	 * @param target the creature hit
	 * @param critBonus the critical bonus held back
	 */
	public static void startBleed(Creature attacker, Creature target, double critBonus)
	{
		final double pct = value(attacker, BLOODLETTER);
		if ((pct <= 0) || (critBonus <= 0) || target.isDead() || has(target, PURITY))
		{
			return;
		}
		
		final double amount = (critBonus * pct) / 100;
		final long key = (((long) attacker.getObjectId()) << 32) | (target.getObjectId() & 0xFFFFFFFFL);
		while (true)
		{
			final Bleed bleed = BLEEDS.computeIfAbsent(key, k -> new Bleed(attacker, target));
			synchronized (bleed)
			{
				if (bleed.ended)
				{
					BLEEDS.remove(key, bleed);
					continue;
				}
				
				bleed.remaining += amount;
				bleed.ticksLeft = BLEED_TICKS;
				if (bleed.task == null)
				{
					if (!target.hasAbnormalVisualEffect(AbnormalVisualEffect.DOT_BLEEDING))
					{
						target.startAbnormalVisualEffect(true, AbnormalVisualEffect.DOT_BLEEDING);
					}
					bleed.task = ThreadPool.scheduleAtFixedRate(() -> bleedTick(key, bleed), BLEED_PERIOD, BLEED_PERIOD);
				}
				return;
			}
		}
	}
	
	private static void bleedTick(long key, Bleed bleed)
	{
		final double tick;
		synchronized (bleed)
		{
			final Creature target = bleed.target;
			if (bleed.ended || (bleed.ticksLeft <= 0) || target.isDead() || !target.isSpawned() || !bleed.attacker.isSpawned() || has(target, PURITY))
			{
				endBleed(key, bleed);
				return;
			}
			
			tick = bleed.remaining / bleed.ticksLeft;
			bleed.remaining -= tick;
			bleed.ticksLeft--;
		}
		
		if (tick >= 1)
		{
			bleed.target.reduceCurrentHp(tick, bleed.attacker, true, true, null);
			bleed.target.notifyDamageReceived(tick, bleed.attacker, null, false, true);
		}
	}
	
	/** Called holding the bleed's lock. */
	private static void endBleed(long key, Bleed bleed)
	{
		bleed.ended = true;
		if (bleed.task != null)
		{
			bleed.task.cancel(false);
		}
		BLEEDS.remove(key, bleed);
		
		// Clear the bleeding look once no Bloodletter bleed and no bleed effect is left on the target.
		final Creature target = bleed.target;
		if (target.hasAbnormalVisualEffect(AbnormalVisualEffect.DOT_BLEEDING) && (target.getEffectList().getBuffInfoByAbnormalType(AbnormalType.BLEEDING) == null) && BLEEDS.values().stream().noneMatch(other -> other.target == target))
		{
			target.stopAbnormalVisualEffect(true, AbnormalVisualEffect.DOT_BLEEDING);
		}
	}
	
	/**
	 * @param player the player
	 * @return the most Force charges the player's class can hold, 0 for classes without charges
	 */
	public static int maxCharges(Player player)
	{
		int result = 0;
		for (int skillId : CHARGE_SKILLS)
		{
			result = Math.max(result, Math.max(0, player.getSkillLevel(skillId)));
		}
		return result;
	}
	
	// ---------------------------------------------------------------- effects landing
	
	/**
	 * Unwavering Stance blocks stuns, Purity of Flesh blocks poison and bleed.
	 * @param owner the creature the effect would land on
	 * @param info the effect
	 * @return {@code true} if the effect must not be applied
	 */
	public static boolean isAbnormalBlocked(Creature owner, BuffInfo info)
	{
		if ((!owner.isPlayer() && !owner.isPvpFakePlayer()) || (info.getEffector() == owner))
		{
			return false;
		}
		
		final Skill skill = info.getSkill();
		final AbnormalType type = skill.getAbnormalType();
		final boolean blocked = (has(owner, STUN_IMMUNE) && ((type == AbnormalType.STUN) || skill.hasEffectType(EffectType.STUN))) || (has(owner, PURITY) && ((type == AbnormalType.POISON) || (type == AbnormalType.BLEEDING)));
		if (blocked && (info.getEffector() != null))
		{
			final SystemMessage sm = new SystemMessage(SystemMessageId.C1_HAS_RESISTED_YOUR_S2);
			sm.addString(owner.getName());
			sm.addSkillName(skill);
			info.getEffector().sendPacket(sm);
		}
		return blocked;
	}
	
	/**
	 * Unshaken Mind: CON-resisted effects are resisted with MEN.
	 * @param target the creature resisting
	 * @param property the skill's resist property
	 * @return the base stat the target resists with
	 */
	public static BaseStat resistStat(Creature target, BaseStat property)
	{
		return (property == BaseStat.CON) && has(target, MEN_STUN) ? BaseStat.MEN : property;
	}
	
	// ---------------------------------------------------------------- healing
	
	/**
	 * Overflowing Grace: healing past a player's max HP is granted to them as CP.
	 * @param healer the caster
	 * @param target the healed creature
	 * @param overheal healing beyond what the target could take as HP
	 */
	public static void grantOverhealAsCp(Creature healer, Creature target, double overheal)
	{
		final double pct = value(healer, OVERHEAL_CP);
		if ((pct <= 0) || (overheal <= 0) || !target.isPlayer())
		{
			return;
		}
		
		final double cp = Math.min((overheal * pct) / 100, target.getMaxRecoverableCp() - target.getCurrentCp());
		if (cp > 0)
		{
			target.setCurrentCp(target.getCurrentCp() + cp);
			final SystemMessage sm = new SystemMessage(SystemMessageId.S1_CP_HAS_BEEN_RESTORED);
			sm.addInt((int) cp);
			target.sendPacket(sm);
		}
	}
	
	// ---------------------------------------------------------------- conditional stats
	
	/**
	 * A conditional effect key such as {@code PDEF_PCT@HEAVY}: the base key and the condition that gates it.
	 * @param baseKey the effect key the bonus applies to, e.g. {@code PDEF_PCT}
	 * @param token the condition token, e.g. {@code HEAVY}
	 * @param condition the condition, tested on every stat calculation
	 */
	public record ConditionalKey(String baseKey, String token, Condition condition)
	{
	}
	
	/** Every condition token {@link #parseConditional} knows, for the admin tree editor. */
	public static final List<String> CONDITION_TOKENS = List.of("HEAVY", "LIGHT", "ROBE", "NOARMOR", "SHIELD", "BOW", "DAGGER", "DUAL", "SWORD", "BLUNT", "POLE", "FIST", "LOWHP", "FULLHP", "NIGHT", "DAY", "STILL", "MOBILE");
	
	/**
	 * @param key an effect key, possibly with an {@code @CONDITION} suffix
	 * @return the parsed key, or {@code null} if it has no condition, the condition is unknown, or the base key can't be conditional (max HP/MP/CP and keystone keys)
	 */
	public static ConditionalKey parseConditional(String key)
	{
		final int at = key.indexOf('@');
		if (at <= 0)
		{
			return null;
		}
		
		final String base = key.substring(0, at).trim();
		final String token = key.substring(at + 1).trim().toUpperCase();
		if (base.startsWith("MAXHP") || base.startsWith("MAXMP") || base.startsWith("MAXCP") || base.startsWith("KS_"))
		{
			return null; // a pool that depends on HP% would feed back into itself
		}
		
		final Condition condition = conditionFor(token);
		return condition == null ? null : new ConditionalKey(base, token, condition);
	}
	
	/**
	 * @param token a condition token
	 * @return the condition, or {@code null} if the token is unknown
	 */
	private static Condition conditionFor(String token)
	{
		final int gearMask = gearMask(token);
		if (gearMask != 0)
		{
			return new ConditionUsingItemType(gearMask);
		}
		
		switch (token)
		{
			case "LOWHP":
				return new ConditionPlayerHp(50);
			case "FULLHP":
				return new ConditionLogicNot(new ConditionPlayerHp(90));
			case "NIGHT":
				return new ConditionGameTime(true);
			case "DAY":
				return new ConditionGameTime(false);
			case "STILL":
				return new ConditionStandingStill(STILL_TIME, true);
			case "MOBILE":
				return new ConditionStandingStill(STILL_TIME, false);
			default:
				return null;
		}
	}
	
	/**
	 * @param token a condition token
	 * @return the item type mask an armour or weapon token tests (see {@link ConditionUsingItemType}), 0 for any other token
	 */
	public static int gearMask(String token)
	{
		switch (token)
		{
			case "HEAVY":
				return ArmorType.HEAVY.mask();
			case "LIGHT":
				return ArmorType.LIGHT.mask();
			case "ROBE":
				return ArmorType.MAGIC.mask();
			case "NOARMOR":
				return ArmorType.NONE.mask();
			case "SHIELD":
				return ArmorType.SHIELD.mask();
			case "BOW":
				return WeaponType.BOW.mask() | WeaponType.CROSSBOW.mask();
			case "DAGGER":
				return WeaponType.DAGGER.mask() | WeaponType.DUALDAGGER.mask();
			case "DUAL":
				return WeaponType.DUAL.mask();
			case "SWORD":
				return WeaponType.SWORD.mask() | WeaponType.ANCIENTSWORD.mask() | WeaponType.RAPIER.mask();
			case "BLUNT":
				return WeaponType.BLUNT.mask();
			case "POLE":
				return WeaponType.POLE.mask();
			case "FIST":
				return WeaponType.FIST.mask() | WeaponType.DUALFIST.mask();
			default:
				return 0;
		}
	}
	
	// ---------------------------------------------------------------- allocation
	
	/**
	 * @param a a node
	 * @param b another node
	 * @return {@code true} if the two nodes carry keystones that can't be taken together (Point Blank and Far Shot)
	 */
	public static boolean conflicts(PassiveNode a, PassiveNode b)
	{
		final Set<String> keysA = effectKeys(a);
		final Set<String> keysB = effectKeys(b);
		for (Set<String> group : EXCLUSIVE_GROUPS)
		{
			for (String keyA : keysA)
			{
				if (group.contains(keyA))
				{
					for (String keyB : keysB)
					{
						if (group.contains(keyB) && !keyA.equals(keyB))
						{
							return true;
						}
					}
				}
			}
		}
		return false;
	}
	
	private static Set<String> effectKeys(PassiveNode node)
	{
		final Set<String> keys = new HashSet<>();
		for (String part : node.getEffectSpec().split(";"))
		{
			final int colon = part.indexOf(':');
			if (colon > 0)
			{
				keys.add(part.substring(0, colon).trim());
			}
		}
		return keys;
	}
}
