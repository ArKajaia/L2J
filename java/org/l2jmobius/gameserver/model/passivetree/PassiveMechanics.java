package org.l2jmobius.gameserver.model.passivetree;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.l2jmobius.commons.util.Rnd;
import org.l2jmobius.gameserver.model.conditions.Condition;
import org.l2jmobius.gameserver.model.conditions.ConditionGameTime;
import org.l2jmobius.gameserver.model.conditions.ConditionLogicNot;
import org.l2jmobius.gameserver.model.conditions.ConditionPlayerHp;
import org.l2jmobius.gameserver.model.conditions.ConditionUsingItemType;
import org.l2jmobius.gameserver.model.actor.Creature;
import org.l2jmobius.gameserver.model.actor.Player;
import org.l2jmobius.gameserver.model.effects.EffectType;
import org.l2jmobius.gameserver.model.item.Weapon;
import org.l2jmobius.gameserver.model.item.type.ArmorType;
import org.l2jmobius.gameserver.model.item.type.WeaponType;
import org.l2jmobius.gameserver.model.skill.AbnormalType;
import org.l2jmobius.gameserver.model.skill.BuffInfo;
import org.l2jmobius.gameserver.model.skill.Skill;
import org.l2jmobius.gameserver.model.stats.BaseStat;
import org.l2jmobius.gameserver.model.stats.Stat;
import org.l2jmobius.gameserver.network.SystemMessageId;
import org.l2jmobius.gameserver.network.serverpackets.SystemMessage;

/**
 * Build-defining passive tree keystones: rules that change how a mechanic works rather than adding a stat. Every value is read from the allocated nodes' effect strings (see {@link PassiveStatBonusCache}), so balancing stays in data/passivetree/*.xml.
 * <p>
 * Only players own a passive tree. Every helper is a no-op for anything else (summons, NPCs, roaming fake players), so the hooks in the combat code can call them unconditionally.
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
	
	/** Keys that cancel each other out; a node carrying one can't be allocated next to a node carrying another key of the same group. */
	private static final List<Set<String>> EXCLUSIVE_GROUPS = List.of(Set.of(POINT_BLANK, FAR_SHOT));
	
	/** Point Blank / Far Shot: full bonus at or below this range, full penalty at or above LONG_RANGE. */
	private static final double POINT_BLANK_RANGE = 150;
	private static final double LONG_RANGE = 900;
	
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
		if ((creature == null) || !creature.isPlayer())
		{
			return 0;
		}
		
		final PassiveStatBonusCache bonus = creature.asPlayer().getPassiveStatBonus();
		return bonus == null ? 0 : bonus.get(key);
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
		if (!attacker.isPlayer() || (skill == null) || !skill.isMagic())
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
		return multiplier;
	}
	
	/**
	 * @param attacker the attacker
	 * @param target the target
	 * @return Point Blank / Far Shot multiplier for bow and crossbow attacks and skills
	 */
	public static double bowDamageMultiplier(Creature attacker, Creature target)
	{
		if (!attacker.isPlayer())
		{
			return 1;
		}
		
		final Weapon weapon = attacker.getActiveWeaponItem();
		if ((weapon == null) || ((weapon.getItemType() != WeaponType.BOW) && (weapon.getItemType() != WeaponType.CROSSBOW)))
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
	 * @param player the player
	 * @return the P.Atk multiplier
	 */
	public static double rampageMultiplier(Player player)
	{
		final double rampage = value(player, RAMPAGE);
		if (rampage <= 0)
		{
			return 1;
		}
		
		final double missingHp = 1 - Math.min(1, player.getCurrentHp() / Math.max(1, player.getMaxHp()));
		return 1 + ((rampage / 100) * missingHp);
	}
	
	/**
	 * Vampiric Sorcery: direct spell damage heals the caster.
	 * @param attacker the caster
	 * @param skill the damaging skill
	 * @param damage the damage dealt
	 * @param damageOverTime whether it was a damage-over-time tick
	 */
	public static void onDamageDealt(Creature attacker, Skill skill, double damage, boolean damageOverTime)
	{
		if (damageOverTime || (damage <= 0) || (skill == null) || !skill.isMagic() || attacker.isDead())
		{
			return;
		}
		
		final double leech = value(attacker, SPELL_LEECH);
		if (leech <= 0)
		{
			return;
		}
		
		final double heal = Math.min((damage * leech) / 100, attacker.getMaxRecoverableHp() - attacker.getCurrentHp());
		if (heal > 0)
		{
			attacker.setCurrentHp(attacker.getCurrentHp() + heal);
		}
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
	 * Soul Harvest: a landed normal hit may grant a Force charge and a soul.
	 * @param attacker the attacker
	 */
	public static void onNormalHitLanded(Creature attacker)
	{
		final double chance = value(attacker, SOUL_HARVEST);
		if ((chance <= 0) || (Rnd.get(100) >= chance))
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
		if (!owner.isPlayer() || (info.getEffector() == owner))
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
	public static final List<String> CONDITION_TOKENS = List.of("HEAVY", "LIGHT", "ROBE", "NOARMOR", "SHIELD", "BOW", "DAGGER", "DUAL", "SWORD", "BLUNT", "POLE", "FIST", "LOWHP", "FULLHP", "NIGHT", "DAY");
	
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
		switch (token)
		{
			case "HEAVY":
				return new ConditionUsingItemType(ArmorType.HEAVY.mask());
			case "LIGHT":
				return new ConditionUsingItemType(ArmorType.LIGHT.mask());
			case "ROBE":
				return new ConditionUsingItemType(ArmorType.MAGIC.mask());
			case "NOARMOR":
				return new ConditionUsingItemType(ArmorType.NONE.mask());
			case "SHIELD":
				return new ConditionUsingItemType(ArmorType.SHIELD.mask());
			case "BOW":
				return new ConditionUsingItemType(WeaponType.BOW.mask() | WeaponType.CROSSBOW.mask());
			case "DAGGER":
				return new ConditionUsingItemType(WeaponType.DAGGER.mask() | WeaponType.DUALDAGGER.mask());
			case "DUAL":
				return new ConditionUsingItemType(WeaponType.DUAL.mask());
			case "SWORD":
				return new ConditionUsingItemType(WeaponType.SWORD.mask() | WeaponType.ANCIENTSWORD.mask() | WeaponType.RAPIER.mask());
			case "BLUNT":
				return new ConditionUsingItemType(WeaponType.BLUNT.mask());
			case "POLE":
				return new ConditionUsingItemType(WeaponType.POLE.mask());
			case "FIST":
				return new ConditionUsingItemType(WeaponType.FIST.mask() | WeaponType.DUALFIST.mask());
			case "LOWHP":
				return new ConditionPlayerHp(50);
			case "FULLHP":
				return new ConditionLogicNot(new ConditionPlayerHp(90));
			case "NIGHT":
				return new ConditionGameTime(true);
			case "DAY":
				return new ConditionGameTime(false);
			default:
				return null;
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
