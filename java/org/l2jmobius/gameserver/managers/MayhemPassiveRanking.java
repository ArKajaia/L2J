/*
 * Copyright (c) 2013 L2jMobius
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be
 * included in all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY,
 * WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR
 * IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
 */
package org.l2jmobius.gameserver.managers;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;

import org.l2jmobius.gameserver.config.custom.TeamVsTeamConfig;
import org.l2jmobius.gameserver.data.custom.PassiveTreeData;
import org.l2jmobius.gameserver.model.conditions.ConditionUsingItemType;
import org.l2jmobius.gameserver.model.passivetree.PassiveMechanics;
import org.l2jmobius.gameserver.model.passivetree.PassiveNode;

/**
 * How a fake player of Deathmatch: Mayhem ranks every passive a kill may offer it, by class type: it takes the best ranked of its offer (see PassiveDeathmatchManager.pickForFake).
 * <p>
 * The ranks are reasoned, not measured: a passive's score is what its effects are worth to the class type in a Mayhem fight, in "notables": 1 is about what +3% P.Atk is to a fighter (+3% damage). The yardstick:
 * <ul>
 * <li>1% more damage is worth 1/3, 1% more effective HP 1/4 (a kill needs the damage; staying alive only keeps it fighting).</li>
 * <li>Magic damage grows with the square root of M.Atk, so +3.6% M.Atk is +1.8% damage to a mage: 0.6.</li>
 * <li>MP hardly matters: skills cost {@link TeamVsTeamConfig#PDM_MP_COST} % of their MP in Mayhem. Monster-only stats (PVE_*) and servitor stats are worth nothing in it.</li>
 * <li>A stat of a notable counts as much as Mayhem makes notables count for the class type (MayhemMageNotables, MayhemTankNotables, MayhemArcherNotables, a hybrid's bonus); a keystone's in full.</li>
 * <li>An effect under a gear condition (@HEAVY, @BOW...) counts with that gear and not at all without; one that holds part of the time by how often it does ({@link #CONDITION_TIME}).</li>
 * <li>A keystone's mechanic (its KS_ key) by what it does for the class type ({@link #MECHANICS}), on top of the stats it carries (its drawbacks count against it).</li>
 * <li>Speed lost counts {@link #SPEED_LOSS_FACTOR} times what the same speed gained does.</li>
 * </ul>
 * The ranking of a class type and gear is made once and kept; {@link #describe} shows it, {@link #clear} drops them after the tree was edited.
 */
public final class MayhemPassiveRanking
{
	/** The class types Mayhem ranks passives for. */
	public enum ClassType
	{
		FIGHTER,
		TANK,
		DAGGER,
		ARCHER,
		MAGE,
		/** A Soul Hound (MayhemHybridClasses): a caster that also fights in melee. */
		HYBRID
	}

	/** What one typical notable's amount of a stat is worth to each class type, in {@link ClassType} order. */
	private static final class StatValue
	{
		private final double _unit;
		private final double[] _values;

		StatValue(double unit, double... values)
		{
			_unit = unit;
			_values = values;
		}
	}

	/** How often a condition that comes and goes holds: below 50% HP, at full HP, by day, by night, standing still (2 s), moving. */
	private static final Map<String, Double> CONDITION_TIME = Map.of("LOWHP", 0.3, "FULLHP", 0.35, "DAY", 0.5, "NIGHT", 0.5, "STILL", 0.5, "MOBILE", 0.5);

	/** How much more a loss of speed counts than the same gain: a slower kiter gets caught, a slower melee never reaches anyone. */
	private static final double SPEED_LOSS_FACTOR = 2;

	/** Stats that only work with a shield, whatever their condition says. */
	private static final List<String> SHIELD_STATS = List.of("SHIELD_RATE_PCT", "SHIELD_RATE_MUL_PCT", "SHIELD_DEF_PCT");

	private static final Map<String, StatValue> STATS = new HashMap<>();
	static
	{
		// Value of the amount a typical notable has (the first number), for FIGHTER, TANK, DAGGER, ARCHER, MAGE, HYBRID.
		// Damage. P.Atk is damage one for one; a tank deals less of its kills' damage, a Soul Hound fights mostly with spells (MayhemHybridMagicShare).
		STATS.put("PATK_PCT", new StatValue(3, 1.0, 0.7, 1.0, 1.0, 0, 0.3));
		STATS.put("MATK_PCT", new StatValue(3.6, 0, 0, 0, 0, 0.6, 0.42));
		STATS.put("STR", new StatValue(1, 0.5, 0.35, 0.5, 0.5, 0, 0.15));
		STATS.put("INT", new StatValue(1, 0, 0, 0, 0, 0.35, 0.25));
		STATS.put("PHYS_SKILL_POWER_PCT", new StatValue(10, 1.6, 1.0, 2.6, 0.5, 0, 0.5)); // half a fighter's damage is skills, most of a dagger's, little of an archer's
		STATS.put("ACCURACY_ADD", new StatValue(2, 0.5, 0.4, 0.4, 0.6, 0, 0.15));
		// Speed. An archer lives on its normal hits; a fighter's skills go with attack speed too; a dagger waits on its blows' reuse.
		STATS.put("ATK_SPD_PCT", new StatValue(3, 0.8, 0.6, 0.5, 1.0, 0, 0.25));
		STATS.put("CAST_SPD_PCT", new StatValue(3, 0, 0, 0, 0, 0.7, 0.5));
		STATS.put("DEX", new StatValue(1, 0.3, 0.25, 0.5, 0.4, 0, 0.15));
		STATS.put("WIT", new StatValue(1, 0, 0, 0, 0, 0.35, 0.25));
		STATS.put("SKILL_CDR_PCT", new StatValue(4, 0.6, 0.4, 0.9, 0.3, 0, 0.2));
		STATS.put("SPELL_CDR_PCT", new StatValue(3, 0, 0, 0, 0, 0.6, 0.4));
		// Critical hits. A dagger's blows are critical hits: critical damage and blow rate are its damage; critical rate only helps its normal hits.
		STATS.put("CRIT_RATE_ADD", new StatValue(28, 0.7, 0.5, 0.3, 0.9, 0, 0.25));
		STATS.put("CRIT_DMG_PCT", new StatValue(5, 0.4, 0.3, 1.3, 0.5, 0, 0.15));
		STATS.put("BLOW_RATE_PCT", new StatValue(5, 0, 0, 1.3, 0, 0, 0));
		STATS.put("MCRIT_RATE_ADD", new StatValue(25, 0, 0, 0, 0, 0.8, 0.55));
		STATS.put("MCRIT_DMG_PCT", new StatValue(8, 0, 0, 0, 0, 0.4, 0.3));
		STATS.put("LIFESTEAL_PCT", new StatValue(1, 0.3, 0.3, 0.3, 0.3, 0, 0.1));
		// Life: 1% effective HP is 1/4. 100 HP is more of a mage's HP than of a tank's.
		STATS.put("MAXHP_PCT", new StatValue(4, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0));
		STATS.put("MAXHP", new StatValue(100, 0.4, 0.3, 0.45, 0.45, 0.55, 0.5));
		STATS.put("CON", new StatValue(1, 0.6, 0.6, 0.6, 0.6, 0.6, 0.6));
		STATS.put("HP_REGEN_PCT", new StatValue(6, 0.1, 0.1, 0.1, 0.1, 0.1, 0.1)); // a respawn heals in full; regeneration barely counts in a fight
		STATS.put("HEALING_RECEIVED_PCT", new StatValue(8, 0.1, 0.1, 0.1, 0.1, 0.1, 0.1)); // every one for themselves: no healer
		// Guard. About two thirds of the damage taken is physical.
		STATS.put("PDEF_PCT", new StatValue(2, 0.33, 0.4, 0.33, 0.33, 0.33, 0.33));
		STATS.put("MDEF_PCT", new StatValue(3, 0.25, 0.3, 0.25, 0.25, 0.25, 0.25));
		STATS.put("MEN", new StatValue(1, 0.3, 0.3, 0.3, 0.3, 0.3, 0.3));
		STATS.put("EVASION_ADD", new StatValue(1.5, 0.3, 0.25, 0.35, 0.3, 0.3, 0.3));
		STATS.put("SKILL_DODGE_PCT", new StatValue(3, 0.25, 0.25, 0.25, 0.25, 0.25, 0.25));
		STATS.put("CRIT_DMG_TAKEN_RED_PCT", new StatValue(6, 0.35, 0.35, 0.35, 0.35, 0.35, 0.35));
		STATS.put("DEBUFF_RES_PCT", new StatValue(6, 0.3, 0.3, 0.3, 0.3, 0.3, 0.3));
		STATS.put("INTERRUPT_RES_PCT", new StatValue(30, 0, 0, 0, 0, 0.6, 0.4));
		STATS.put("REFLECT_PCT", new StatValue(7, 0.4, 0.4, 0.4, 0.4, 0.4, 0.4));
		STATS.put("MAGIC_REFLECT_PCT", new StatValue(4, 0.3, 0.3, 0.3, 0.3, 0.3, 0.3));
		STATS.put("SKILL_REFLECT_PCT", new StatValue(3, 0.25, 0.25, 0.25, 0.25, 0.25, 0.25));
		STATS.put("SHIELD_RATE_PCT", new StatValue(4, 0.4, 0.4, 0.4, 0.4, 0.4, 0.4));
		STATS.put("SHIELD_RATE_MUL_PCT", new StatValue(50, 1.2, 1.2, 1.2, 1.2, 1.2, 1.2));
		STATS.put("SHIELD_DEF_PCT", new StatValue(15, 0.3, 0.3, 0.3, 0.3, 0.3, 0.3));
		STATS.put("ALL_ELEM_RES", new StatValue(20, 0.3, 0.3, 0.3, 0.3, 0.3, 0.3));
		for (String element : List.of("FIRE", "WATER", "WIND", "EARTH", "HOLY", "DARK"))
		{
			STATS.put(element + "_RES", new StatValue(15, 0.1, 0.1, 0.1, 0.1, 0.1, 0.1));
			STATS.put(element + "_ATK", new StatValue(30, 0.1, 0.1, 0.1, 0.1, 0.1, 0.1));
		}
		// Movement: catching and getting away; kiting is how archers and mages live.
		STATS.put("MOVE_SPEED_ADD", new StatValue(3, 0.4, 0.35, 0.5, 0.6, 0.5, 0.45));
		// MP: skills cost a fraction of their MP in Mayhem.
		STATS.put("MAXMP", new StatValue(120, 0.03, 0.03, 0.03, 0.03, 0.05, 0.05));
		STATS.put("MAXMP_PCT", new StatValue(30, 0.03, 0.03, 0.03, 0.03, 0.05, 0.05));
		STATS.put("MP_REGEN_PCT", new StatValue(5, 0.03, 0.03, 0.03, 0.03, 0.05, 0.05));
		STATS.put("SPELL_MP_COST_RED_PCT", new StatValue(8, 0.03, 0.03, 0.03, 0.03, 0.05, 0.05));
		STATS.put("MANA_LEECH_PCT", new StatValue(5, 0.03, 0.03, 0.03, 0.03, 0.05, 0.05));
		// Not of use in Mayhem: the PVE_* and SUMMON_* stats, SERVITOR_SHARE_PCT (no fake player has a servitor). Any stat missing here is worth 0.
	}

	/** What a keystone's mechanic (its KS_ key, at the node's own strength) is worth to each class type, in {@link ClassType} order. The stats it carries come on top. */
	private static final Map<String, double[]> MECHANICS = new HashMap<>();
	static
	{
		// Survival.
		MECHANICS.put("KS_MIND_OVER_MATTER", v(2.7, 2.2, 2.5, 2.7, 4.5, 4.0)); // 30% of the damage taken comes off MP, which skills hardly use here: a mage's HP goes about 40% further
		MECHANICS.put("KS_BLOOD_MAGIC", v(2.2, 1.6, 2.2, 2.4, 5.0, 3.8)); // Martyr's Vow: +50% of max MP as max HP (a mage's MP is half its HP); the HP its cheap skills cost is little
		MECHANICS.put("KS_STUN_IMMUNE", v(2.5, 2.5, 2.5, 2.5, 3.0, 2.8)); // a stun in a crowd is a death; it comes with -15% P.Def
		MECHANICS.put("KS_PHOENIX", v(2.5, 2.5, 2.5, 2.5, 2.5, 2.5)); // about one death in four undone; it comes with -15% max HP
		MECHANICS.put("KS_LAST_LIGHT", v(0.8, 0.5, 1.2, 1.6, 2.0, 1.4)); // big single hits land on the frail: blows, nukes
		MECHANICS.put("KS_TENACITY", v(1.0, 1.0, 1.0, 1.0, 1.2, 1.1)); // no chain of stuns
		MECHANICS.put("KS_MEN_STUN", v(-0.5, -0.5, -0.3, -0.2, 1.5, 1.2)); // Unshaken Mind: a mage's MEN is far above its CON, a fighter's below
		MECHANICS.put("KS_SPELL_LEECH", v(0, 0, 0, 0, 2.0, 1.4)); // 8% of spell damage healed: damage dealt is about damage taken
		MECHANICS.put("KS_PURITY", v(0.75, 0.75, 0.75, 0.75, 0.75, 0.75)); // poison and bleeding: a few classes only
		MECHANICS.put("KS_ARCANE_BULWARK", v(1.8, 1.8, 1.8, 1.8, 1.8, 1.8)); // area skills, the mages', are about a fifth of the damage taken
		MECHANICS.put("KS_JUGGERNAUT", v(1.0, 1.0, 1.0, 1.0, 1.0, 1.0)); // no root or slow to stop it reaching its target
		MECHANICS.put("KS_BLOODTHIRST", v(2.0, 1.8, 2.0, 2.0, 2.0, 2.0)); // a quarter of HP and CP back on each kill, kills come in runs
		MECHANICS.put("KS_LAST_STAND", v(4.0, 4.0, 4.0, 4.0, 4.0, 4.0)); // 6 s of no damage (and +20% damage) once every 2 minutes: an escape or a kill per life
		MECHANICS.put("KS_UNBROKEN_FAITH", v(0.2, 0.2, 0.2, 0.2, 0.2, 0.2)); // buffs are rarely cancelled
		MECHANICS.put("KS_ELDRITCH_BATTERY", v(-10, -10, -10, -10, -8, -9)); // all CP gone: CP is a big part of a fighter's life in PvP
		MECHANICS.put("KS_BLOOD_TOGGLES", v(-0.2, -0.2, -0.2, -0.2, -0.2, -0.2));
		MECHANICS.put("KS_ARCANE_RETORT", v(0.5, 0.4, 0.5, 0.5, 0.4, 0.4)); // magic critical hits on it are rare
		// Damage.
		MECHANICS.put("KS_BATTLEMAGE", v(2.5, 1.7, 2.5, 2.3, 0, 1.2)); // 40% of M.Atk (a third of a fighter's P.Atk, cut to 70% by MayhemMAtk) added to P.Atk: about +8%; casting speed means nothing to a fighter
		MECHANICS.put("KS_RELENTLESS", v(3.2, 2.4, 1.3, 6.5, 0, 0.8)); // +30% attack speed after 10 hits on one target: an archer keeps shooting one, a melee switches
		MECHANICS.put("KS_RAMPAGE", v(3.3, 2.3, 3.3, 2.5, 0, 1.0)); // Unending Fury: about +10% P.Atk on average (an archer that kites stays fuller)
		MECHANICS.put("KS_BLOOD_FOR_BLOOD", v(2.3, 1.6, 2.0, 2.0, 0, 0.6)); // on about a third of the time, while it is being hit
		MECHANICS.put("KS_BLOODLETTER", v(1.0, 0.5, 0.2, 1.5, 0, 0.2)); // +50% critical bonus, but late (blows aside)
		MECHANICS.put("KS_FLEETING_SHADOW", v(0.5, 0.3, 0.6, 0.7, 0.2, 0.4));
		MECHANICS.put("KS_BLOODRUSH", v(0.3, 0.2, 0.3, 0.3, 0.3, 0.3)); // 5 s after a kill
		MECHANICS.put("KS_WHIRLING_STEEL", v(1.0, 0.7, 0.3, 0, 0, 0.2)); // cleaves the crowd, but at -15% attack speed
		MECHANICS.put("KS_RESOLUTE", v(-2.0, -1.0, -5.0, -4.0, 0, -0.5)); // never a critical hit: worst for the ones whose damage is critical hits
		MECHANICS.put("KS_SOUL_HARVEST", v(0.5, 0, 0, 0.3, 0, 0.5)); // charges and souls, for the classes that use them
		MECHANICS.put("KS_SPELL_ECHO", v(0, 0, 0, 0, 4.0, 3.5)); // +15% single-target spell damage, at -15% casting speed
		MECHANICS.put("KS_CHAOS_WEAVE", v(0, 0, 0, 0, 2.6, 1.8)); // +20% on average, less the magic critical hits it gives up
		MECHANICS.put("KS_ARCANE_OVERLOAD", v(0, 0, 0, 0, 2.5, 1.7)); // MP rarely runs low here: about +8%
		MECHANICS.put("KS_ARC_CONDUIT", v(0, 0, 0, 0, 1.5, 1.0)); // damage on bystanders: assists, seldom kills
		MECHANICS.put("KS_FAR_SHOT", v(0, 0, 0, 1.5, 1.0, -1.0)); // an archer or mage fights far, a Soul Hound close
		MECHANICS.put("KS_POINT_BLANK", v(0, 0, 0, -1.5, -1.5, -0.5));
		// The Mayhem keystones (each only offered to the classes it is for).
		MECHANICS.put("KS_ASSASSINS_MARK", v(0, 0, 5.0, 0, 0, 0)); // +35% from behind, where a dagger strikes half the time
		MECHANICS.put("KS_BLADE_DANCE", v(4.0, 3.0, 3.0, 0, 0, 0)); // on most of the time: about +7% P.Atk and +10% attack speed
		MECHANICS.put("KS_EXECUTIONER", v(3.5, 2.5, 3.5, 0, 0, 0)); // a quarter of its damage lands on targets below 30%, and it takes the kills
		MECHANICS.put("KS_SWEEPING_MOMENTUM", v(2.7, 2.0, 0, 0, 0, 0)); // a crowd around the target: about +8%
		MECHANICS.put("KS_CHARGE", v(2.5, 2.5, 0, 0, 0, 0)); // an opening hit +30% and a 1 s stun
		MECHANICS.put("KS_SPELLBREAKER", v(2.0, 2.0, 1.8, 0, 0, 0)); // a third of the field casts
		MECHANICS.put("KS_HAMSTRING", v(1.5, 1.2, 1.2, 0, 0, 0)); // catches the ones that run
		MECHANICS.put("KS_PINNING_SHOT", v(0, 0, 0, 2.5, 0, 0)); // roots the melee that chases it
		MECHANICS.put("KS_KITE_STEP", v(0, 0, 0, 2.0, 0, 0));
		// Of no use here: KS_GUARDIAN (no party), KS_OVERHEAL_CP (heals others), KS_CRIT_CAP (no fake player gets near the cap), KS_BERSERK_PACT (only a flag). The gear-bound ones are in mechanicValue.
	}

	private static double[] v(double... values)
	{
		return values;
	}

	/** One ranked passive. */
	public static final class Ranked
	{
		private final PassiveNode _node;
		private final double _score;
		private final int _rank;

		Ranked(PassiveNode node, double score, int rank)
		{
			_node = node;
			_score = score;
			_rank = rank;
		}

		public PassiveNode getNode()
		{
			return _node;
		}

		public double getScore()
		{
			return _score;
		}

		public int getRank()
		{
			return _rank;
		}
	}

	/** The rankings made so far, by class type, gear and notable scale. */
	private static final Map<String, List<Ranked>> RANKINGS = new ConcurrentHashMap<>();

	private MayhemPassiveRanking()
	{
	}

	/**
	 * @param type the class type
	 * @param armorMask the item mask of the body armour, 0 for none
	 * @param wornMask the item mask of everything worn and held
	 * @param notableScale how much notables count for it in Mayhem
	 * @param offered which passives Mayhem offers at all
	 * @return every offered passive, best first, ranked 1, 2, 3...
	 */
	public static List<Ranked> getRanking(ClassType type, int armorMask, int wornMask, double notableScale, Predicate<PassiveNode> offered)
	{
		return RANKINGS.computeIfAbsent(type + "|" + armorMask + "|" + wornMask + "|" + notableScale, key -> rank(type, armorMask, wornMask, notableScale, offered));
	}

	/**
	 * @return the ranks of {@link #getRanking}, by node id
	 */
	public static Map<Integer, Integer> getRanks(ClassType type, int armorMask, int wornMask, double notableScale, Predicate<PassiveNode> offered)
	{
		final Map<Integer, Integer> ranks = new HashMap<>();
		for (Ranked ranked : getRanking(type, armorMask, wornMask, notableScale, offered))
		{
			ranks.put(ranked.getNode().getId(), ranked.getRank());
		}
		return ranks;
	}

	/** Drops the rankings made, after the tree was edited. */
	public static void clear()
	{
		RANKINGS.clear();
	}

	private static List<Ranked> rank(ClassType type, int armorMask, int wornMask, double notableScale, Predicate<PassiveNode> offered)
	{
		final List<PassiveNode> nodes = new ArrayList<>(PassiveTreeData.getInstance().getAllNodes().values());
		nodes.addAll(PassiveTreeData.getInstance().getEventNodes().values());
		// The tree repeats a notable in several places: one of each name (the event keystones apart from tree notables of the same name).
		final Map<String, PassiveNode> unique = new TreeMap<>();
		for (PassiveNode node : nodes)
		{
			if (offered.test(node))
			{
				unique.merge((node.isEventOnly() ? "event:" : "") + node.getName(), node, (a, b) -> a.getId() <= b.getId() ? a : b);
			}
		}
		final List<PassiveNode> sorted = new ArrayList<>(unique.values());
		final Map<PassiveNode, Double> scores = new HashMap<>();
		for (PassiveNode node : sorted)
		{
			scores.put(node, score(type, node, armorMask, wornMask, notableScale));
		}
		sorted.sort(Comparator.comparingDouble((PassiveNode n) -> -scores.get(n)).thenComparing(PassiveNode::getName));
		final List<Ranked> ranking = new ArrayList<>(sorted.size());
		final Map<String, Integer> byName = new HashMap<>();
		for (PassiveNode node : sorted)
		{
			ranking.add(new Ranked(node, scores.get(node), ranking.size() + 1));
			byName.put((node.isEventOnly() ? "event:" : "") + node.getName(), ranking.size());
		}
		// The other copies of a repeated notable share its rank.
		for (PassiveNode node : nodes)
		{
			if (offered.test(node) && !unique.containsValue(node))
			{
				final Integer rank = byName.get((node.isEventOnly() ? "event:" : "") + node.getName());
				if (rank != null)
				{
					ranking.add(new Ranked(node, scores.get(ranking.get(rank - 1).getNode()), rank));
				}
			}
		}
		return Collections.unmodifiableList(ranking);
	}

	/**
	 * @param type the class type
	 * @param node a passive
	 * @param armorMask the item mask of the body armour, 0 for none
	 * @param wornMask the item mask of everything worn and held
	 * @param notableScale how much notables count for it in Mayhem
	 * @return what the passive is worth to it, in notables (see the class description)
	 */
	public static double score(ClassType type, PassiveNode node, int armorMask, int wornMask, double notableScale)
	{
		final int index = type.ordinal();
		final double statScale = node.getType() == PassiveNode.NodeType.NOTABLE ? notableScale : 1;
		final boolean shield = holds("SHIELD", armorMask, wornMask);
		double score = 0;
		for (Map.Entry<String, Double> effect : node.getEffects().entrySet())
		{
			final String key = effect.getKey();
			final int at = key.indexOf('@');
			final String stat = at > 0 ? key.substring(0, at) : key;
			if (stat.startsWith("KS_"))
			{
				score += mechanicValue(stat, index, armorMask, wornMask, shield);
				continue;
			}
			final StatValue value = STATS.get(stat);
			if ((value == null) || (SHIELD_STATS.contains(stat) && !shield))
			{
				continue;
			}
			final double time = at > 0 ? conditionTime(key.substring(at + 1), armorMask, wornMask) : 1;
			final double amount = effect.getValue() * (stat.equals("MOVE_SPEED_ADD") && (effect.getValue() < 0) ? SPEED_LOSS_FACTOR : 1);
			score += (amount / value._unit) * value._values[index] * time * statScale;
		}
		return score;
	}

	/**
	 * @return what a keystone's mechanic is worth to the class type of that index, with its gear
	 */
	private static double mechanicValue(String key, int index, int armorMask, int wornMask, boolean shield)
	{
		switch (key)
		{
			case "KS_RIPOSTE":
			{
				// With a shield, a sure critical hit after each block; without one, half the critical rate.
				return shield ? 0.5 : new double[]
				{
					-2.0,
					-1.0,
					-0.5,
					-3.0,
					0,
					-0.5
				}[index];
			}
			case "KS_COUNTERWEIGHT":
			{
				// +12% P.Atk and +10% attack speed for 4 s after a block, about a third of the time: with a shield only.
				return shield ? new double[]
				{
					2.0,
					1.6,
					1.0,
					0,
					0,
					1.0
				}[index] : 0;
			}
			case "KS_PDEF_AS_MDEF":
			{
				// Arcane Plating: M.Def becomes 75% of P.Def, far more in heavy armour, about as much in light, far less in a robe.
				if (holds("HEAVY", armorMask, wornMask))
				{
					return index == ClassType.TANK.ordinal() ? 2.5 : 2.0;
				}
				return holds("LIGHT", armorMask, wornMask) ? -0.3 : -2.0;
			}
			default:
			{
				final double[] values = MECHANICS.get(key);
				return values == null ? 0 : values[index];
			}
		}
	}

	/**
	 * @return how often a condition holds: 1 or 0 for gear, part of the time for the others ({@link #CONDITION_TIME}), 1 for one unknown
	 */
	private static double conditionTime(String token, int armorMask, int wornMask)
	{
		final Double time = CONDITION_TIME.get(token);
		if (time != null)
		{
			return time;
		}
		if (PassiveMechanics.gearMask(token) != 0)
		{
			return holds(token, armorMask, wornMask) ? 1 : 0;
		}
		return 1;
	}

	private static boolean holds(String token, int armorMask, int wornMask)
	{
		return ConditionUsingItemType.matchesFakeGear(PassiveMechanics.gearMask(token), armorMask, wornMask);
	}

	/**
	 * @param tokens gear condition tokens (HEAVY, LIGHT, ROBE, SHIELD, BOW, DAGGER, SWORD...)
	 * @return the body armour mask and the mask of everything worn of that gear
	 */
	public static int[] gearMasks(List<String> tokens)
	{
		int armor = 0;
		int worn = 0;
		for (String token : tokens)
		{
			final int mask = PassiveMechanics.gearMask(token.trim().toUpperCase());
			if (List.of("HEAVY", "LIGHT", "ROBE").contains(token.trim().toUpperCase()))
			{
				armor |= mask;
			}
			worn |= mask;
		}
		return new int[]
		{
			armor,
			worn
		};
	}

	/**
	 * @return {@code false} for a Mayhem keystone made for other roles than the class type's (a Soul Hound plays as a mage or a fighter), which it is never offered
	 */
	private static boolean mayBeOffered(ClassType type, PassiveNode node)
	{
		if (!node.isEventOnly() || node.getEventRoles().isEmpty())
		{
			return true;
		}
		return type == ClassType.HYBRID ? node.getEventRoles().contains("MAGE") || node.getEventRoles().contains("FIGHTER") : node.getEventRoles().contains(type.name());
	}

	/**
	 * @param type a class type
	 * @return the gear its classes usually fight in, for {@link #describe}
	 */
	public static List<String> typicalGear(ClassType type)
	{
		return switch (type)
		{
			case FIGHTER -> List.of("HEAVY", "SWORD");
			case TANK -> List.of("HEAVY", "SWORD", "SHIELD");
			case DAGGER -> List.of("LIGHT", "DAGGER");
			case ARCHER -> List.of("LIGHT", "BOW");
			case MAGE -> List.of("ROBE");
			case HYBRID -> List.of("LIGHT", "SWORD");
		};
	}

	/**
	 * @param type a class type
	 * @return how much Mayhem makes its notables count
	 */
	public static double notableScale(ClassType type)
	{
		return switch (type)
		{
			case MAGE -> TeamVsTeamConfig.PDM_MAGE_NOTABLES / 100.0;
			case TANK -> TeamVsTeamConfig.PDM_TANK_NOTABLES / 100.0;
			case ARCHER -> TeamVsTeamConfig.PDM_ARCHER_NOTABLES / 100.0;
			case HYBRID -> 1 + (TeamVsTeamConfig.PDM_HYBRID_NOTABLE_BONUS / 100.0);
			default -> 1;
		};
	}

	/**
	 * The ranking of a class type in some gear, to read: rank, score, name, kind, effects.
	 * @param type the class type
	 * @param gear its gear tokens, empty for {@link #typicalGear}
	 * @param top how many to show, 0 for all
	 * @param offered which passives Mayhem offers at all
	 * @return the lines
	 */
	public static List<String> describe(ClassType type, List<String> gear, int top, Predicate<PassiveNode> offered)
	{
		final List<String> tokens = gear.isEmpty() ? typicalGear(type) : gear;
		final int[] masks = gearMasks(tokens);
		final double scale = notableScale(type);
		final List<String> lines = new ArrayList<>();
		lines.add("Mayhem passive ranking of " + type + " in " + String.join(",", tokens) + " (notables count " + Math.round(scale * 100) + "%):");
		final List<Ranked> ranking = getRanking(type, masks[0], masks[1], scale, offered);
		int shown = 0;
		int last = 0;
		for (Ranked ranked : ranking)
		{
			// One line per name: the copies of a repeated notable come last with the same rank.
			if ((ranked.getRank() <= last) || ((top > 0) && (shown >= top)) || !mayBeOffered(type, ranked.getNode()))
			{
				continue;
			}
			last = ranked.getRank();
			shown++;
			final PassiveNode node = ranked.getNode();
			final String who = node.isEventOnly() ? " [Mayhem" + (node.getEventRoles().isEmpty() ? "" : " " + node.getEventRoles()) + (node.getEventRaces().isEmpty() ? "" : " " + node.getEventRaces()) + (node.getEventClasses().isEmpty() ? "" : " " + node.getEventClasses()) + "]" : "";
			lines.add(String.format("%3d %6.2f %-26s %-8s%s %s", ranked.getRank(), ranked.getScore(), node.getName(), node.getType(), who, node.getEffects()));
		}
		return lines;
	}
}
