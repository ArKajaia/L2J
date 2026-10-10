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
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

import org.l2jmobius.commons.util.Rnd;
import org.l2jmobius.gameserver.config.custom.FakePlayerPvpConfig;
import org.l2jmobius.gameserver.config.custom.PassiveTreeConfig;
import org.l2jmobius.gameserver.data.custom.PassiveTreeData;
import org.l2jmobius.gameserver.model.actor.enums.player.PlayerClass;
import org.l2jmobius.gameserver.model.actor.holders.npc.FakePlayerPvpBuild;
import org.l2jmobius.gameserver.model.actor.holders.npc.FakePlayerPvpBuild.Role;
import org.l2jmobius.gameserver.model.actor.holders.npc.FakePlayerPvpPassives;
import org.l2jmobius.gameserver.model.conditions.ConditionUsingItemType;
import org.l2jmobius.gameserver.model.passivetree.PassiveMechanics;
import org.l2jmobius.gameserver.model.passivetree.PassiveNode;
import org.l2jmobius.gameserver.model.passivetree.PassiveNode.NodeType;
import org.l2jmobius.gameserver.model.passivetree.PassiveStatBonusCache;
import org.l2jmobius.gameserver.model.skill.PassiveTreeArchetypes;

/**
 * Allocates the passive tree of roaming fake players.
 * <p>
 * The graph is read once, when this is first used (at server start, see {@link FakePlayerPvpManager}). The first fake player of a starting point, {@link Role} and gear gets {@link FakePlayerPvpConfig#PASSIVE_TREE_VARIANTS} growth orders made for it, which every later one picks
 * from. Each order has a {@link Style} of its own (some lean on critical hits, some leave them for P. Atk. and attack speed, some on HP and defence...) on top of what the role uses with that gear (a {@code @HEAVY} bonus only counts in heavy armour), and plans its tree like a
 * player: from the nodes already taken it looks at every node it can reach, weighs what the cheapest path there gives (each bonus by its size, only for the part still under its cap, {@link PassiveStatBonusCache#getCap}) against the points it costs, and takes the whole path with
 * the best worth per point. So it heads straight for the notables worth having, takes the small nodes on the way because they lead there, and never builds a second path to where it already is. Once it has spent {@link FakePlayerPvpConfig#PASSIVE_TREE_KEYSTONE_POINTS} points it
 * heads for one of the keystones of its role by the cheapest path.
 * <p>
 * Each path is a step of the order. A spawn ({@link #roll}) only picks one of the orders and takes its steps while they fit in its points, so it never stops halfway to a notable; a step that no longer fits is left out and a later, smaller one that still hangs from its tree is
 * taken instead. What it takes is always connected to its START node, so it is an allocation a player could have made.
 * <p>
 * Keystones are only taken from the role's list, since the ones built on MP, CP, servitors or parties don't work for fake players; a fake player below {@link FakePlayerPvpConfig#PASSIVE_TREE_KEYSTONE_MIN_LEVEL} leaves out the steps that lead to them. Skill nodes are never
 * taken: node skills only work for players.
 */
public class FakePlayerPvpPassiveTree
{
	private static final Logger LOGGER = Logger.getLogger(FakePlayerPvpPassiveTree.class.getName());

	/** Highest fake player level. */
	private static final int MAX_LEVEL = 85;

	/** Seed the prepared trees are grown with. */
	private static final long POOL_SEED = 0x4C324A50415353L;
	/** Weight of an effect key every role uses (HP, defence...). */
	private static final double COMMON_WEIGHT = 2;
	/** Weight of an effect key the role is built on (P. Atk. for fighters, M. Atk. for mages...). */
	private static final double ROLE_WEIGHT = 3;
	/** How much an effect counts whose condition holds only part of the time (LOWHP, NIGHT, STILL...). */
	private static final double PART_TIME_WEIGHT = 0.5;
	/** Worth of a path that gives nothing, so once nothing of use is left a tree still spends its points, on the shortest steps. */
	private static final double FILLER_WORTH = 0.05;
	/** Up to how much (as a share) the worth of a path is raised at random when the next step is picked, so two orders of the same style still differ. */
	private static final double PICK_NOISE = 0.15;
	/** Path search: what a point costs, against the up to {@link #PATH_WORTH_STEPS} the worth of a node takes off, so the cheapest path always wins and the best nodes break ties. */
	private static final long PATH_POINT = 10000;
	private static final long PATH_WORTH_STEPS = 9999;
	private static final double PATH_WORTH_SCALE = 100;
	/** How far each stat group of a style may stray from it, and each key from its group, as a share. */
	private static final double GROUP_JITTER = 0.25;
	private static final double KEY_JITTER = 0.15;

	/** Keys every role uses. */
	private static final Set<String> COMMON_KEYS = Set.of("MAXHP", "MAXHP_PCT", "MAXCP", "PDEF_PCT", "MDEF_PCT", "EVASION_ADD", "CON", "MEN", "HP_REGEN_PCT", "DEBUFF_RES_PCT", "CRIT_DMG_TAKEN_RED_PCT", "MOVE_SPEED_ADD", "HEALING_RECEIVED_PCT", "SKILL_DODGE_PCT", "MAGIC_REFLECT_PCT", "SKILL_REFLECT_PCT", "REFLECT_PCT", "INTERRUPT_RES_PCT");
	/** Keys every physical role uses. */
	private static final Set<String> PHYSICAL_KEYS = Set.of("PATK_PCT", "ATK_SPD_PCT", "CRIT_RATE_ADD", "CRIT_DMG_PCT", "ACCURACY_ADD", "STR", "DEX", "LIFESTEAL_PCT", "PHYS_SKILL_POWER_PCT", "SKILL_CDR_PCT", "PVE_PDMG_PCT");
	/** Keys mages use. */
	private static final Set<String> MAGIC_KEYS = Set.of("MATK_PCT", "CAST_SPD_PCT", "MCRIT_RATE_ADD", "MCRIT_DMG_PCT", "INT", "WIT", "SPELL_CDR_PCT", "PVE_MDMG_PCT");
	private static final Set<String> TANK_KEYS = Set.of("SHIELD_RATE_PCT", "SHIELD_DEF_PCT", "SHIELD_RATE_MUL_PCT");
	private static final Set<String> DAGGER_KEYS = Set.of("BLOW_RATE_PCT");
	private static final Set<String> ARCHER_KEYS = Set.of("PVE_BOW_DMG_PCT");

	/** The stat groups a {@link Style} leans toward or away from. */
	private static final Set<String> DAMAGE_KEYS = Set.of("PATK_PCT", "MATK_PCT", "STR", "INT", "PHYS_SKILL_POWER_PCT", "ACCURACY_ADD", "PVE_PDMG_PCT", "PVE_MDMG_PCT", "PVE_BOW_DMG_PCT");
	private static final Set<String> SPEED_KEYS = Set.of("ATK_SPD_PCT", "CAST_SPD_PCT", "DEX", "WIT", "SKILL_CDR_PCT", "SPELL_CDR_PCT", "MOVE_SPEED_ADD");
	private static final Set<String> CRIT_KEYS = Set.of("CRIT_RATE_ADD", "CRIT_DMG_PCT", "MCRIT_RATE_ADD", "MCRIT_DMG_PCT", "BLOW_RATE_PCT");
	private static final Set<String> LIFE_KEYS = Set.of("MAXHP", "MAXHP_PCT", "MAXCP", "CON", "HP_REGEN_PCT", "HEALING_RECEIVED_PCT", "LIFESTEAL_PCT");
	private static final Set<String> GUARD_KEYS = Set.of("PDEF_PCT", "MDEF_PCT", "MEN", "EVASION_ADD", "SHIELD_RATE_PCT", "SHIELD_DEF_PCT", "SHIELD_RATE_MUL_PCT", "CRIT_DMG_TAKEN_RED_PCT", "SKILL_DODGE_PCT", "MAGIC_REFLECT_PCT", "SKILL_REFLECT_PCT", "REFLECT_PCT", "DEBUFF_RES_PCT", "INTERRUPT_RES_PCT");

	/**
	 * What a growth order leans on, as a factor on each stat group. A spawn picks one of the orders, so fake players of one class, role and gear don't all build the same tree.
	 */
	private enum Style
	{
		/** A bit of everything. */
		BALANCED(1, 1, 1, 1, 1),
		/** Critical rate and damage (blows for daggers, magic critical hits for mages). */
		CRITICAL(1, 0.8, 1.7, 0.8, 0.8),
		/** P. Atk. (or M. Atk.) and attack (or casting) speed, critical hits left out. */
		TEMPO(1.4, 1.6, 0.2, 1, 0.9),
		/** HP and defence first. */
		BRUISER(0.9, 0.9, 0.6, 1.7, 1.4),
		/** All damage, little to survive with. */
		GLASS(1.5, 1.2, 1.4, 0.6, 0.5);

		private final double _damage;
		private final double _speed;
		private final double _crit;
		private final double _life;
		private final double _guard;

		Style(double damage, double speed, double crit, double life, double guard)
		{
			_damage = damage;
			_speed = speed;
			_crit = crit;
			_life = life;
			_guard = guard;
		}

		/**
		 * @param role the role
		 * @return how often an order of that role has each style, in {@link #values()} order: no critical-free daggers (blows need critical damage), no glass tanks
		 */
		static int[] chances(Role role)
		{
			switch (role)
			{
				case TANK:
				{
					return new int[]
					{
						3,
						1,
						2,
						5,
						0
					};
				}
				case DAGGER:
				{
					return new int[]
					{
						3,
						4,
						0,
						2,
						3
					};
				}
				case ARCHER:
				{
					return new int[]
					{
						3,
						3,
						3,
						1,
						3
					};
				}
				default:
				{
					return new int[]
					{
						3,
						3,
						3,
						2,
						2
					};
				}
			}
		}

		static Style roll(Role role)
		{
			final int[] chances = chances(role);
			int total = 0;
			for (int chance : chances)
			{
				total += chance;
			}
			int roll = Rnd.get(total);
			for (Style style : values())
			{
				roll -= chances[style.ordinal()];
				if (roll < 0)
				{
					return style;
				}
			}
			return BALANCED;
		}
	}

	/** The armour and weapon condition tokens; bit {@code i} of a gear key is set if {@code GEAR_TOKENS[i]} holds for the fake player's gear. */
	private static final List<String> GEAR_TOKENS = PassiveMechanics.CONDITION_TOKENS.stream().filter(token -> PassiveMechanics.gearMask(token) != 0).toList();

	/**
	 * One growth order.
	 * @param order the node indexes in the order they were taken, the START node first
	 * @param stepStart for each of them, whether it starts a step (a path taken as a whole)
	 * @param keystone for each of them, whether it is a keystone (the last node of its step)
	 * @param style what it leans on
	 * @param sector the sector of the START node
	 */
	private record Variant(int[] order, boolean[] stepStart, boolean[] keystone, Style style, String sector)
	{
	}

	/**
	 * The prepared orders of one starting point, role and gear.
	 * @param origin index of the START node
	 * @param role the role
	 * @param gear the gear key, see {@link #gearKey}
	 */
	private record PoolKey(int origin, Role role, int gear)
	{
	}

	/**
	 * A keystone a role may head for.
	 * @param index its node index
	 * @param gearToken the armour or weapon condition the fake player must meet for it to be of use ({@code SHIELD} for Riposte...), {@code null} for none
	 */
	private record Keystone(int index, String gearToken)
	{
	}

	/** Index -> node. */
	private final PassiveNode[] _nodes;
	/** Index -> point cost. */
	private final int[] _costs;
	/** Index -> indexes of its neighbours, both directions. */
	private final int[][] _neighbors;
	/** Index -> whether the growth may take it (keystones are only reached on purpose, see {@link #_keystones}). */
	private final boolean[] _eligible;
	/** Index -> its effects: the index of the base key (the key without its condition) in {@link #_caps}, the condition token ({@code null} for none) and the value. */
	private final int[][] _effectKeys;
	private final String[][] _effectTokens;
	private final double[][] _effectValues;
	/** Index -> base key. */
	private final String[] _keys;
	/** Base key index -> the most the tree may add to it, {@link Double#NaN} if uncapped. */
	private final double[] _caps;
	/** Base key index -> what one small node usually gives of it, so a notable giving five times as much is worth five small nodes. */
	private final double[] _units;
	/** Sector -> index of its START node. */
	private final Map<String, Integer> _origins = new HashMap<>();
	/** Role -> the keystones it may head for, in the order FakePvpKeystones.* lists them. */
	private final Map<Role, List<Keystone>> _keystones = new HashMap<>();
	/** The growth orders made so far. */
	private final Map<PoolKey, Variant[]> _pools = new ConcurrentHashMap<>();

	protected FakePlayerPvpPassiveTree()
	{
		final Map<Integer, PassiveNode> nodes = PassiveTreeData.getInstance().getAllNodes();
		final int count = nodes.size();
		_nodes = new PassiveNode[count];
		_costs = new int[count];
		_eligible = new boolean[count];
		_effectKeys = new int[count][];
		_effectTokens = new String[count][];
		_effectValues = new double[count][];

		final Map<Integer, Integer> indexes = new HashMap<>();
		final Map<String, Integer> keyIndexes = new HashMap<>();
		final List<String> keys = new ArrayList<>();
		int index = 0;
		for (PassiveNode node : nodes.values())
		{
			_nodes[index] = node;
			_costs[index] = Math.max(0, node.getCost());
			_eligible[index] = isEligible(node);
			indexes.put(node.getId(), index);
			if (node.getType() == NodeType.START)
			{
				_origins.putIfAbsent(node.getSector(), index);
			}

			final int effects = node.getEffects().size();
			_effectKeys[index] = new int[effects];
			_effectTokens[index] = new String[effects];
			_effectValues[index] = new double[effects];
			int effect = 0;
			for (Map.Entry<String, Double> entry : node.getEffects().entrySet())
			{
				final String key = entry.getKey();
				final int at = key.indexOf('@');
				final String base = at > 0 ? key.substring(0, at).trim() : key;
				_effectKeys[index][effect] = keyIndexes.computeIfAbsent(base, k ->
				{
					keys.add(k);
					return keys.size() - 1;
				});
				_effectTokens[index][effect] = at > 0 ? key.substring(at + 1).trim().toUpperCase() : null;
				_effectValues[index][effect] = entry.getValue();
				effect++;
			}
			index++;
		}

		_keys = keys.toArray(new String[0]);
		_caps = new double[_keys.length];
		for (int i = 0; i < _keys.length; i++)
		{
			final Double cap = PassiveStatBonusCache.getCap(_keys[i]);
			_caps[i] = cap == null ? Double.NaN : cap;
		}

		// What a small node usually gives of each key: the median of the small nodes, else a quarter of the notables', else 1.
		final List<List<Double>> smallValues = new ArrayList<>();
		final List<List<Double>> notableValues = new ArrayList<>();
		for (int i = 0; i < _keys.length; i++)
		{
			smallValues.add(new ArrayList<>());
			notableValues.add(new ArrayList<>());
		}
		for (int i = 0; i < count; i++)
		{
			final NodeType type = _nodes[i].getType();
			for (int e = 0; e < _effectKeys[i].length; e++)
			{
				final double value = Math.abs(_effectValues[i][e]);
				if (value <= 0)
				{
					continue;
				}
				if (type == NodeType.SMALL)
				{
					smallValues.get(_effectKeys[i][e]).add(value);
				}
				else if (type == NodeType.NOTABLE)
				{
					notableValues.get(_effectKeys[i][e]).add(value);
				}
			}
		}
		_units = new double[_keys.length];
		for (int i = 0; i < _keys.length; i++)
		{
			final double small = median(smallValues.get(i));
			final double notable = median(notableValues.get(i));
			_units[i] = small > 0 ? small : notable > 0 ? notable / 4 : 1;
		}

		// START nodes have no parents, so the links are rebuilt from both ends (like PassiveTreeManager.neighborMap()).
		final List<Set<Integer>> links = new ArrayList<>(count);
		for (int i = 0; i < count; i++)
		{
			links.add(new HashSet<>());
		}
		for (PassiveNode node : nodes.values())
		{
			final int nodeIndex = indexes.get(node.getId());
			for (int parentId : node.getParents())
			{
				final Integer parentIndex = indexes.get(parentId);
				if (parentIndex != null)
				{
					links.get(nodeIndex).add(parentIndex);
					links.get(parentIndex).add(nodeIndex);
				}
			}
		}
		_neighbors = new int[count][];
		for (int i = 0; i < count; i++)
		{
			_neighbors[i] = links.get(i).stream().mapToInt(Integer::intValue).sorted().toArray();
		}

		// The keystones each role may head for, by name.
		final Map<String, Integer> keystoneIndexes = new HashMap<>();
		for (int i = 0; i < count; i++)
		{
			if ((_nodes[i].getType() == NodeType.KEYSTONE) && !_nodes[i].grantsSkill())
			{
				keystoneIndexes.putIfAbsent(_nodes[i].getName().toLowerCase(), i);
			}
		}
		for (Role role : Role.values())
		{
			final List<Keystone> keystones = new ArrayList<>();
			for (String entry : FakePlayerPvpConfig.PASSIVE_TREE_KEYSTONES.getOrDefault(role.name(), List.of()))
			{
				final int at = entry.indexOf('@');
				final String name = (at > 0 ? entry.substring(0, at) : entry).trim();
				final String token = at > 0 ? entry.substring(at + 1).trim().toUpperCase() : null;
				final Integer keystone = keystoneIndexes.get(name.toLowerCase());
				if (keystone == null)
				{
					LOGGER.warning(getClass().getSimpleName() + ": FakePvpKeystones." + role + " lists \"" + name + "\", which is no keystone of the passive tree.");
				}
				else if ((token != null) && (PassiveMechanics.gearMask(token) == 0))
				{
					LOGGER.warning(getClass().getSimpleName() + ": FakePvpKeystones." + role + " gives \"" + name + "\" the condition @" + token + ", which is no armour or weapon condition.");
				}
				else
				{
					keystones.add(new Keystone(keystone, token));
				}
			}
			_keystones.put(role, List.copyOf(keystones));
		}

		LOGGER.info(getClass().getSimpleName() + ": Read the passive tree for " + _origins.size() + " starting points; fake player trees are grown on first use.");
	}

	/**
	 * @return {@code true} if fake players get a passive tree
	 */
	public static boolean isEnabled()
	{
		return FakePlayerPvpConfig.PASSIVE_TREE_ENABLED && PassiveTreeConfig.PASSIVE_TREE_ENABLED && (PassiveTreeConfig.PASSIVE_TREE_MAX_POINTS > 0);
	}

	/**
	 * Rolls the passive tree of a new fake player: its subclasses, then {@code subclasses * FakePvpPassiveTreeNodesPerSubclass + level} points of one of the prepared trees of its class, role and gear, never more than the points a player can have
	 * ({@link PassiveTreeConfig#PASSIVE_TREE_MAX_POINTS}).
	 * @param build the build
	 * @param playerClass the class it has now (used when the build has no final class)
	 * @param level its level
	 * @param elite {@code true} for one of the strong players of the PvP spots, which took every subclass it could (from {@link FakePlayerPvpConfig#PASSIVE_TREE_SUBCLASS_MIN_LEVEL})
	 * @param pvpSpot {@code true} for a player that came to a PvP spot to fight: from {@link FakePlayerPvpConfig#PASSIVE_TREE_SPOT_SUBCLASS_LEVEL} it took every subclass with a {@link FakePlayerPvpConfig#PASSIVE_TREE_SPOT_SUBCLASS_CHANCE} % chance
	 * @param armorMask the item mask of its body armour, 0 if it wears none
	 * @param wornMask the item mask of everything it wears and holds (armour, weapon and shield)
	 * @return its passive tree, {@code null} if it can't have one
	 */
	public FakePlayerPvpPassives roll(FakePlayerPvpBuild build, PlayerClass playerClass, int level, boolean elite, boolean pvpSpot, int armorMask, int wornMask)
	{
		final PlayerClass sectorClass = build.getPlayerClass() != null ? build.getPlayerClass() : playerClass;
		Integer origin = _origins.get(PassiveTreeArchetypes.sectorFor(sectorClass != null ? sectorClass.getId() : -1));
		if (origin == null)
		{
			origin = _origins.get(PassiveTreeArchetypes.VANGUARD);
		}
		if (origin == null)
		{
			return null;
		}

		final Variant[] variants = _pools.computeIfAbsent(new PoolKey(origin, build.getRole(), gearKey(armorMask, wornMask)), this::growPool);
		final Variant variant = variants[Rnd.get(variants.length)];
		final boolean allSubclasses = (level >= FakePlayerPvpConfig.PASSIVE_TREE_SUBCLASS_MIN_LEVEL) && (elite || (pvpSpot && (level >= FakePlayerPvpConfig.PASSIVE_TREE_SPOT_SUBCLASS_LEVEL) && (Rnd.get(100) < FakePlayerPvpConfig.PASSIVE_TREE_SPOT_SUBCLASS_CHANCE)));
		final int subclasses = allSubclasses ? FakePlayerPvpConfig.PASSIVE_TREE_MAX_SUBCLASSES : rollSubclasses(level);

		// Whole steps, as long as they fit in its points; a step that hangs from one left out is left out too.
		final boolean keystones = level >= FakePlayerPvpConfig.PASSIVE_TREE_KEYSTONE_MIN_LEVEL;
		final int budget = Math.min(PassiveTreeConfig.PASSIVE_TREE_MAX_POINTS, (subclasses * FakePlayerPvpConfig.PASSIVE_TREE_NODES_PER_SUBCLASS) + level);
		final int[] order = variant.order();
		final boolean[] taken = new boolean[_nodes.length];
		final List<Integer> nodeIds = new ArrayList<>();
		int points = 0;
		for (int start = 0, end; start < order.length; start = end)
		{
			end = start + 1;
			while ((end < order.length) && !variant.stepStart()[end])
			{
				end++;
			}

			int cost = 0;
			boolean keystone = false;
			for (int i = start; i < end; i++)
			{
				cost += _costs[order[i]];
				keystone |= variant.keystone()[i];
			}
			if ((keystone && !keystones) || ((points + cost) > budget) || ((start > 0) && !touches(order[start], taken)))
			{
				continue;
			}

			for (int i = start; i < end; i++)
			{
				taken[order[i]] = true;
				nodeIds.add(_nodes[order[i]].getId());
			}
			points += cost;
		}

		final PassiveStatBonusCache bonus = new PassiveStatBonusCache();
		bonus.recompute(nodeIds);
		return new FakePlayerPvpPassives(subclasses, nodeIds.size(), points, variant.sector(), variant.style().name().toLowerCase(), nodeIds, bonus);
	}

	/**
	 * @param index a node index
	 * @param taken the nodes taken
	 * @return {@code true} if one of its neighbours is taken
	 */
	private boolean touches(int index, boolean[] taken)
	{
		for (int next : _neighbors[index])
		{
			if (taken[next])
			{
				return true;
			}
		}
		return false;
	}

	/**
	 * @param armorMask the item mask of the body armour, 0 for none
	 * @param wornMask the item mask of everything worn and held
	 * @return which armour and weapon conditions hold for that gear, one bit per {@link #GEAR_TOKENS} entry
	 */
	private static int gearKey(int armorMask, int wornMask)
	{
		int key = 0;
		for (int i = 0; i < GEAR_TOKENS.size(); i++)
		{
			if (ConditionUsingItemType.matchesFakeGear(PassiveMechanics.gearMask(GEAR_TOKENS.get(i)), armorMask, wornMask))
			{
				key |= 1 << i;
			}
		}
		return key;
	}

	/**
	 * @param token a condition token, {@code null} for none
	 * @param gear the gear key
	 * @return how much an effect with that condition counts: fully without one, fully or not at all for an armour or weapon condition, by half for one that holds part of the time
	 */
	private static double conditionWeight(String token, int gear)
	{
		if (token == null)
		{
			return 1;
		}

		final int bit = GEAR_TOKENS.indexOf(token);
		if (bit >= 0)
		{
			return (gear & (1 << bit)) != 0 ? 1 : 0;
		}
		return PART_TIME_WEIGHT;
	}

	/**
	 * @param level the fake player level
	 * @return how many subclasses it has: none below {@link FakePlayerPvpConfig#PASSIVE_TREE_SUBCLASS_MIN_LEVEL}, then each one rolled on its own with a chance growing with the level
	 */
	private static int rollSubclasses(int level)
	{
		final int minLevel = FakePlayerPvpConfig.PASSIVE_TREE_SUBCLASS_MIN_LEVEL;
		if (level < minLevel)
		{
			return 0;
		}

		final double progress = minLevel >= MAX_LEVEL ? 1 : Math.min(1, (double) (level - minLevel) / (MAX_LEVEL - minLevel));
		final double chance = FakePlayerPvpConfig.PASSIVE_TREE_SUBCLASS_CHANCE_MIN + ((FakePlayerPvpConfig.PASSIVE_TREE_SUBCLASS_CHANCE_MAX - FakePlayerPvpConfig.PASSIVE_TREE_SUBCLASS_CHANCE_MIN) * progress);
		int subclasses = 0;
		for (int i = 0; i < FakePlayerPvpConfig.PASSIVE_TREE_MAX_SUBCLASSES; i++)
		{
			if (Rnd.get(100.0) < chance)
			{
				subclasses++;
			}
		}
		return subclasses;
	}

	/**
	 * Grows the orders of a starting point, role and gear. Each pool has a seed of its own, so a fake player made again from the same seed gets the same tree whichever pools were grown before (the members of players' clans, see FakeClanManager).
	 * @param key the starting point, role and gear
	 * @return its {@link FakePlayerPvpConfig#PASSIVE_TREE_VARIANTS} orders
	 */
	private Variant[] growPool(PoolKey key)
	{
		final String sector = _nodes[key.origin()].getSector();
		final long seed = (((POOL_SEED * 31) + sector.hashCode()) * 31 * 31) + (key.role().ordinal() * 31L) + key.gear();
		return Rnd.seeded(seed, () ->
		{
			// The keystones of the role that are of use with this gear.
			final List<Integer> keystones = new ArrayList<>();
			for (Keystone keystone : _keystones.getOrDefault(key.role(), List.of()))
			{
				if (conditionWeight(keystone.gearToken(), key.gear()) > 0)
				{
					keystones.add(keystone.index());
				}
			}

			final Variant[] pool = new Variant[FakePlayerPvpConfig.PASSIVE_TREE_VARIANTS];
			for (int v = 0; v < pool.length; v++)
			{
				// Every order has a style of its own, and strays a bit from it.
				final Style style = Style.roll(key.role());
				final double[] groups =
				{
					style._damage * jitter(GROUP_JITTER),
					style._speed * jitter(GROUP_JITTER),
					style._crit * jitter(GROUP_JITTER),
					style._life * jitter(GROUP_JITTER),
					style._guard * jitter(GROUP_JITTER)
				};
				final double[] keyWeights = new double[_keys.length];
				for (int k = 0; k < _keys.length; k++)
				{
					keyWeights[k] = (keyWeight(key.role(), _keys[k]) * groupFactor(_keys[k], groups) * jitter(KEY_JITTER)) / _units[k];
				}

				// How much this order wants each effect of each node with this gear, by its size, before caps.
				final double[][] effectWeights = new double[_nodes.length][];
				for (int i = 0; i < _nodes.length; i++)
				{
					effectWeights[i] = new double[_effectKeys[i].length];
					for (int e = 0; e < effectWeights[i].length; e++)
					{
						effectWeights[i][e] = keyWeights[_effectKeys[i][e]] * conditionWeight(_effectTokens[i][e], key.gear()) * _effectValues[i][e];
					}
				}

				pool[v] = new Growth(key.origin(), effectWeights, keystones, style, PassiveTreeConfig.PASSIVE_TREE_MAX_POINTS).grow();
			}
			return pool;
		});
	}

	/**
	 * @param spread the most it may stray, as a share
	 * @return a random factor between {@code 1 - spread} and {@code 1 + spread}
	 */
	private static double jitter(double spread)
	{
		return 1 + ((Rnd.nextDouble() * 2) - 1) * spread;
	}

	/**
	 * @param key an effect key without its condition
	 * @param groups the factors of a style: damage, speed, critical, life, guard
	 * @return the factor of the group of that key, 1 for a key of none
	 */
	private static double groupFactor(String key, double[] groups)
	{
		if (DAMAGE_KEYS.contains(key))
		{
			return groups[0];
		}
		if (SPEED_KEYS.contains(key))
		{
			return groups[1];
		}
		if (CRIT_KEYS.contains(key))
		{
			return groups[2];
		}
		if (LIFE_KEYS.contains(key))
		{
			return groups[3];
		}
		if (GUARD_KEYS.contains(key))
		{
			return groups[4];
		}
		return 1;
	}

	/**
	 * @param values some values
	 * @return their median, 0 for none
	 */
	private static double median(List<Double> values)
	{
		if (values.isEmpty())
		{
			return 0;
		}

		final double[] sorted = values.stream().mapToDouble(Double::doubleValue).sorted().toArray();
		final int middle = sorted.length / 2;
		return (sorted.length % 2) == 1 ? sorted[middle] : (sorted[middle - 1] + sorted[middle]) / 2;
	}

	/**
	 * One growth order being made, planned like a player plans a tree: each step takes the whole cheapest path to the node whose path is worth the most per point, from all the nodes taken so far, and heads for a keystone at each of
	 * {@link FakePlayerPvpConfig#PASSIVE_TREE_KEYSTONE_POINTS}.
	 */
	private class Growth
	{
		private final double[][] _effectWeights;
		private final List<Integer> _keystoneChoices;
		private final boolean[] _isKeystoneChoice;
		private final Style _style;
		private final int _maxPoints;

		private final int[] _order;
		private final boolean[] _stepStart;
		private final boolean[] _isKeystone;
		private final boolean[] _taken;
		private final double[] _totals;
		private final List<Integer> _chosen = new ArrayList<>();
		private int _count;
		private int _points;

		/** The last path search: for each node, the cheapest path to it from the nodes taken (its sort key, points, worth and the node before it). */
		private final long[] _distance;
		private final int[] _pathPoints;
		private final double[] _pathWorth;
		private final int[] _previous;

		Growth(int origin, double[][] effectWeights, List<Integer> keystones, Style style, int maxPoints)
		{
			_effectWeights = effectWeights;
			_keystoneChoices = keystones;
			_isKeystoneChoice = new boolean[_nodes.length];
			for (int keystone : keystones)
			{
				_isKeystoneChoice[keystone] = true;
			}
			_style = style;
			_maxPoints = maxPoints;
			_order = new int[_nodes.length];
			_stepStart = new boolean[_nodes.length];
			_isKeystone = new boolean[_nodes.length];
			_taken = new boolean[_nodes.length];
			_totals = new double[_keys.length];
			_distance = new long[_nodes.length];
			_pathPoints = new int[_nodes.length];
			_pathWorth = new double[_nodes.length];
			_previous = new int[_nodes.length];
			take(origin, true, false);
		}

		Variant grow()
		{
			final int[] targets = FakePlayerPvpConfig.PASSIVE_TREE_KEYSTONE_POINTS;
			int nextTarget = 0;
			while ((_points < _maxPoints) && (_count < _nodes.length))
			{
				search();

				// Head for the next keystone once enough points are spent.
				final boolean keystone = (nextTarget < targets.length) && (_points >= targets[nextTarget]);
				final List<Integer> path;
				if (keystone)
				{
					nextTarget++;
					path = pathToKeystone();
					if (path == null)
					{
						continue;
					}
				}
				else
				{
					path = bestPath();
					if (path == null)
					{
						break;
					}
				}

				for (int i = 0; i < path.size(); i++)
				{
					take(path.get(i), i == 0, keystone && (i == (path.size() - 1)));
				}
			}

			return new Variant(Arrays.copyOf(_order, _count), Arrays.copyOf(_stepStart, _count), Arrays.copyOf(_isKeystone, _count), _style, _nodes[_order[0]].getSector());
		}

		/**
		 * Takes a node: adds it to the order and its effects to the totals.
		 * @param index the node index
		 * @param stepStart whether it starts a step
		 * @param keystone whether it is a keystone
		 */
		private void take(int index, boolean stepStart, boolean keystone)
		{
			_taken[index] = true;
			_order[_count] = index;
			_stepStart[_count] = stepStart;
			_isKeystone[_count] = keystone;
			_count++;
			_points += _costs[index];
			for (int e = 0; e < _effectKeys[index].length; e++)
			{
				if ((_effectTokens[index][e] == null) || (_effectWeights[index][e] != 0))
				{
					_totals[_effectKeys[index][e]] += _effectValues[index][e];
				}
			}
			if (keystone)
			{
				_chosen.add(index);
			}
		}

		/**
		 * @param index a node index
		 * @return how much this order wants the node now: the weight of each of its effects by its size (a drawback taken off), each bonus only for the part that still fits under its cap
		 */
		private double worth(int index)
		{
			double worth = 0;
			for (int e = 0; e < _effectKeys[index].length; e++)
			{
				double effectWeight = _effectWeights[index][e];
				final double value = _effectValues[index][e];
				final double cap = _caps[_effectKeys[index][e]];
				if ((effectWeight > 0) && (value > 0) && !Double.isNaN(cap))
				{
					effectWeight *= Math.max(0, Math.min(1, (cap - _totals[_effectKeys[index][e]]) / value));
				}
				worth += effectWeight;
			}
			return worth;
		}

		/**
		 * Finds the cheapest path from the nodes taken (not through a keystone, which nothing hangs from) to every node the growth may take or the keystones of the role, within the points left; ties go to the path through the nodes worth the most.
		 */
		private void search()
		{
			final int left = _maxPoints - _points;
			Arrays.fill(_distance, Long.MAX_VALUE);
			Arrays.fill(_previous, -1);
			final PriorityQueue<long[]> queue = new PriorityQueue<>((a, b) -> a[0] != b[0] ? Long.compare(a[0], b[0]) : Long.compare(a[1], b[1]));
			for (int i = 0; i < _count; i++)
			{
				if (!_isKeystone[i])
				{
					_distance[_order[i]] = 0;
					_pathPoints[_order[i]] = 0;
					_pathWorth[_order[i]] = 0;
					queue.add(new long[]
					{
						0,
						_order[i]
					});
				}
			}
			while (!queue.isEmpty())
			{
				final long[] head = queue.poll();
				final int index = (int) head[1];
				if (head[0] > _distance[index])
				{
					continue;
				}
				for (int next : _neighbors[index])
				{
					final boolean keystone = _isKeystoneChoice[next];
					if (_taken[next] || (!_eligible[next] && !keystone))
					{
						continue;
					}

					final int points = _pathPoints[index] + _costs[next];
					if (points > left)
					{
						continue;
					}

					final double worth = keystone ? 0 : Math.max(0, worth(next));
					final long step = (_costs[next] * PATH_POINT) + (PATH_WORTH_STEPS - Math.min(PATH_WORTH_STEPS, Math.round(worth * PATH_WORTH_SCALE)));
					if ((_distance[index] + step) < _distance[next])
					{
						_distance[next] = _distance[index] + step;
						_pathPoints[next] = points;
						_pathWorth[next] = _pathWorth[index] + worth;
						_previous[next] = index;
						if (!keystone) // A keystone ends a path.
						{
							queue.add(new long[]
							{
								_distance[next],
								next
							});
						}
					}
				}
			}
		}

		/**
		 * @return the path of the last search worth the most per point (a little at random), {@code null} if no node is in reach
		 */
		private List<Integer> bestPath()
		{
			int best = -1;
			double bestScore = 0;
			for (int i = 0; i < _nodes.length; i++)
			{
				if ((_distance[i] == Long.MAX_VALUE) || _taken[i] || _isKeystoneChoice[i])
				{
					continue;
				}

				final double score = ((_pathWorth[i] + FILLER_WORTH) / Math.max(1, _pathPoints[i])) * (1 + (Rnd.nextDouble() * PICK_NOISE));
				if (score > bestScore)
				{
					best = i;
					bestScore = score;
				}
			}
			return best < 0 ? null : path(best);
		}

		/**
		 * Picks one of the role's keystones not taken yet, that can be combined with the ones taken and that the last search reached (the nearer, the likelier).
		 * @return the nodes to take, the keystone last; {@code null} if none can be reached
		 */
		private List<Integer> pathToKeystone()
		{
			final List<Integer> reachable = new ArrayList<>();
			final List<Double> chances = new ArrayList<>();
			double total = 0;
			for (int keystone : _keystoneChoices)
			{
				if (_taken[keystone] || (_distance[keystone] == Long.MAX_VALUE) || conflictsWithChosen(keystone))
				{
					continue;
				}

				final double chance = 1.0 / (1 + _pathPoints[keystone]);
				reachable.add(keystone);
				chances.add(chance);
				total += chance;
			}
			if (reachable.isEmpty())
			{
				return null;
			}

			double roll = Rnd.nextDouble() * total;
			int target = reachable.get(reachable.size() - 1);
			for (int i = 0; i < reachable.size(); i++)
			{
				roll -= chances.get(i);
				if (roll < 0)
				{
					target = reachable.get(i);
					break;
				}
			}
			return path(target);
		}

		/**
		 * @param target a node the last search reached
		 * @return the nodes of its path not taken yet, from the one next to the tree to the target
		 */
		private List<Integer> path(int target)
		{
			final List<Integer> path = new ArrayList<>();
			for (int index = target; (index >= 0) && !_taken[index]; index = _previous[index])
			{
				path.add(0, index);
			}
			return path;
		}

		private boolean conflictsWithChosen(int keystone)
		{
			for (int chosen : _chosen)
			{
				if (PassiveMechanics.conflicts(_nodes[keystone], _nodes[chosen]))
				{
					return true;
				}
			}
			return false;
		}
	}

	/**
	 * @param node a node
	 * @return {@code true} if the growth may take it on its way: not a START node (it only ever takes its own, as the first node), no skill (node skills only work for players) and no keystone (those are only reached on purpose, from the role's list)
	 */
	private static boolean isEligible(PassiveNode node)
	{
		if ((node.getType() == NodeType.START) || (node.getType() == NodeType.KEYSTONE) || node.grantsSkill())
		{
			return false;
		}

		for (String key : node.getEffects().keySet())
		{
			if (key.startsWith("KS_"))
			{
				return false;
			}
		}
		return true;
	}

	/**
	 * @param role the fake player role
	 * @param key an effect key without its condition
	 * @return how much the role wants it; 0 for what does nothing for a fake player (MP, it never runs out of it) or for this role
	 */
	private static double keyWeight(Role role, String key)
	{
		if (COMMON_KEYS.contains(key))
		{
			return COMMON_WEIGHT;
		}

		switch (role)
		{
			case MAGE:
			{
				return MAGIC_KEYS.contains(key) ? ROLE_WEIGHT : 0;
			}
			case TANK:
			{
				return PHYSICAL_KEYS.contains(key) || TANK_KEYS.contains(key) ? ROLE_WEIGHT : 0;
			}
			case DAGGER:
			{
				return PHYSICAL_KEYS.contains(key) || DAGGER_KEYS.contains(key) ? ROLE_WEIGHT : 0;
			}
			case ARCHER:
			{
				return PHYSICAL_KEYS.contains(key) || ARCHER_KEYS.contains(key) ? ROLE_WEIGHT : 0;
			}
			default:
			{
				return PHYSICAL_KEYS.contains(key) ? ROLE_WEIGHT : 0;
			}
		}
	}

	/** Made on first use, and made again by {@link #reload()} once the tree has been edited. */
	private static volatile FakePlayerPvpPassiveTree _instance;

	public static FakePlayerPvpPassiveTree getInstance()
	{
		FakePlayerPvpPassiveTree instance = _instance;
		if (instance == null)
		{
			synchronized (FakePlayerPvpPassiveTree.class)
			{
				instance = _instance;
				if (instance == null)
				{
					instance = new FakePlayerPvpPassiveTree();
					_instance = instance;
				}
			}
		}
		return instance;
	}

	/**
	 * Reads the tree again from the current {@link PassiveTreeData} (and the FakePvpKeystones.* lists), after the tree was edited, and drops the orders grown so far. Fake players already out keep the tree they rolled. Does nothing if the tree was never read.
	 */
	public static void reload()
	{
		synchronized (FakePlayerPvpPassiveTree.class)
		{
			if (_instance != null)
			{
				_instance = new FakePlayerPvpPassiveTree();
			}
		}
	}
}
