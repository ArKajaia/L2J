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
 * The graph is read once, when this is first used (at server start, see {@link FakePlayerPvpManager}). The first fake player of a starting point, {@link Role} and gear gets {@link FakePlayerPvpConfig#PASSIVE_TREE_VARIANTS} random growth orders made for it, which every later one
 * picks from. Each order walks out from the START node and prefers the nodes the role uses with that gear (a {@code @HEAVY} bonus only counts in heavy armour) and that still have room under their caps ({@link PassiveStatBonusCache#getCap}). Once it has spent
 * {@link FakePlayerPvpConfig#PASSIVE_TREE_KEYSTONE_POINTS} points it heads for one of the keystones of its role (FakePvpKeystones.* in FakePlayerPvp.ini) by the cheapest path. Every prefix of an order is connected to its START node, so it is an allocation a player could have
 * made. A spawn ({@link #roll}) only picks one of them and adds up the effects of its first nodes.
 * <p>
 * Keystones are only taken from the role's list, since the ones built on MP, CP, servitors or parties don't work for fake players; they are leaves of the tree, so a fake player below {@link FakePlayerPvpConfig#PASSIVE_TREE_KEYSTONE_MIN_LEVEL} just leaves them out. Skill
 * nodes are never taken: node skills only work for players.
 */
public class FakePlayerPvpPassiveTree
{
	private static final Logger LOGGER = Logger.getLogger(FakePlayerPvpPassiveTree.class.getName());
	
	/** Highest fake player level. */
	private static final int MAX_LEVEL = 85;
	
	/** Seed the prepared trees are grown with. */
	private static final long POOL_SEED = 0x4C324A50415353L;
	/** Weight of a node with nothing the role uses, so it is still taken now and then as a bridge to better ones. */
	private static final double FILLER_WEIGHT = 0.25;
	/** Weight of an effect key every role uses (HP, defence...). */
	private static final double COMMON_WEIGHT = 2;
	/** Weight of an effect key the role is built on (P. Atk. for fighters, M. Atk. for mages...). */
	private static final double ROLE_WEIGHT = 3;
	/** How much an effect counts whose condition holds only part of the time (LOWHP, NIGHT, STILL...). */
	private static final double PART_TIME_WEIGHT = 0.5;
	/** Path search: what a point costs, against the up to 99 a poor node adds, so the cheapest path always wins and the best nodes break ties. */
	private static final long PATH_POINT = 10000;
	
	/** Keys every role uses. */
	private static final Set<String> COMMON_KEYS = Set.of("MAXHP", "MAXHP_PCT", "MAXCP", "PDEF_PCT", "MDEF_PCT", "EVASION_ADD", "CON", "MEN", "HP_REGEN_PCT", "DEBUFF_RES_PCT", "CRIT_DMG_TAKEN_RED_PCT", "MOVE_SPEED_ADD", "HEALING_RECEIVED_PCT", "SKILL_DODGE_PCT", "MAGIC_REFLECT_PCT", "SKILL_REFLECT_PCT", "REFLECT_PCT", "INTERRUPT_RES_PCT");
	/** Keys every physical role uses. */
	private static final Set<String> PHYSICAL_KEYS = Set.of("PATK_PCT", "ATK_SPD_PCT", "CRIT_RATE_ADD", "CRIT_DMG_PCT", "ACCURACY_ADD", "STR", "DEX", "LIFESTEAL_PCT", "PHYS_SKILL_POWER_PCT", "SKILL_CDR_PCT", "PVE_PDMG_PCT");
	/** Keys mages use. */
	private static final Set<String> MAGIC_KEYS = Set.of("MATK_PCT", "CAST_SPD_PCT", "MCRIT_RATE_ADD", "MCRIT_DMG_PCT", "INT", "WIT", "SPELL_CDR_PCT", "PVE_MDMG_PCT");
	private static final Set<String> TANK_KEYS = Set.of("SHIELD_RATE_PCT", "SHIELD_DEF_PCT", "SHIELD_RATE_MUL_PCT");
	private static final Set<String> DAGGER_KEYS = Set.of("BLOW_RATE_PCT");
	private static final Set<String> ARCHER_KEYS = Set.of("PVE_BOW_DMG_PCT");
	
	/** The armour and weapon condition tokens; bit {@code i} of a gear key is set if {@code GEAR_TOKENS[i]} holds for the fake player's gear. */
	private static final List<String> GEAR_TOKENS = PassiveMechanics.CONDITION_TOKENS.stream().filter(token -> PassiveMechanics.gearMask(token) != 0).toList();
	
	/**
	 * One growth order.
	 * @param nodeIds the node ids in the order they were taken, the START node first
	 * @param costs the point cost of each of them
	 * @param keystones which of them are keystones (which a fake player below the keystone level leaves out)
	 * @param sector the sector of the START node
	 */
	private record Variant(int[] nodeIds, int[] costs, boolean[] keystones, String sector)
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
	/** Index -> whether the growth walk may take it (keystones are only reached on purpose, see {@link #_keystones}). */
	private final boolean[] _eligible;
	/** Index -> its effects: the index of the base key (the key without its condition) in {@link #_caps}, the condition token ({@code null} for none) and the value. */
	private final int[][] _effectKeys;
	private final String[][] _effectTokens;
	private final double[][] _effectValues;
	/** Index -> base key. */
	private final String[] _keys;
	/** Base key index -> the most the tree may add to it, {@link Double#NaN} if uncapped. */
	private final double[] _caps;
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
	 * Rolls the passive tree of a new fake player: its subclasses, then {@code subclasses * FakePvpPassiveTreeNodesPerSubclass + level} nodes of one of the prepared trees of its class, role and gear, never costing more than the points a player can have
	 * ({@link PassiveTreeConfig#PASSIVE_TREE_MAX_POINTS}).
	 * @param build the build
	 * @param playerClass the class it has now (used when the build has no final class)
	 * @param level its level
	 * @param allSubclasses {@code true} for a player that took every subclass it could (from {@link FakePlayerPvpConfig#PASSIVE_TREE_SUBCLASS_MIN_LEVEL}), {@code false} to roll them
	 * @param armorMask the item mask of its body armour, 0 if it wears none
	 * @param wornMask the item mask of everything it wears and holds (armour, weapon and shield)
	 * @return its passive tree, {@code null} if it can't have one
	 */
	public FakePlayerPvpPassives roll(FakePlayerPvpBuild build, PlayerClass playerClass, int level, boolean allSubclasses, int armorMask, int wornMask)
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
		final int subclasses = allSubclasses && (level >= FakePlayerPvpConfig.PASSIVE_TREE_SUBCLASS_MIN_LEVEL) ? FakePlayerPvpConfig.PASSIVE_TREE_MAX_SUBCLASSES : rollSubclasses(level);
		
		// As many nodes as asked, as long as they fit in the points a player can have. Keystones are leaves, so leaving them out keeps the rest connected.
		final boolean keystones = level >= FakePlayerPvpConfig.PASSIVE_TREE_KEYSTONE_MIN_LEVEL;
		final int wanted = (subclasses * FakePlayerPvpConfig.PASSIVE_TREE_NODES_PER_SUBCLASS) + level;
		final List<Integer> nodeIds = new ArrayList<>();
		int points = 0;
		for (int i = 0; (i < variant.nodeIds().length) && (nodeIds.size() < wanted); i++)
		{
			if (variant.keystones()[i] && !keystones)
			{
				continue;
			}
			if ((points + variant.costs()[i]) > PassiveTreeConfig.PASSIVE_TREE_MAX_POINTS)
			{
				break;
			}
			
			nodeIds.add(variant.nodeIds()[i]);
			points += variant.costs()[i];
		}
		
		final PassiveStatBonusCache bonus = new PassiveStatBonusCache();
		bonus.recompute(nodeIds);
		return new FakePlayerPvpPassives(subclasses, nodeIds.size(), points, variant.sector(), nodeIds, bonus);
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
			// How much the role wants each effect of each node with this gear, before caps.
			final double[][] effectWeights = new double[_nodes.length][];
			for (int i = 0; i < _nodes.length; i++)
			{
				effectWeights[i] = new double[_effectKeys[i].length];
				for (int e = 0; e < effectWeights[i].length; e++)
				{
					final double weight = keyWeight(key.role(), _keys[_effectKeys[i][e]]) * conditionWeight(_effectTokens[i][e], key.gear());
					effectWeights[i][e] = _effectValues[i][e] < 0 ? -weight : weight;
				}
			}
			
			// The keystones of the role that are of use with this gear.
			final List<Integer> keystones = new ArrayList<>();
			for (Keystone keystone : _keystones.getOrDefault(key.role(), List.of()))
			{
				if (conditionWeight(keystone.gearToken(), key.gear()) > 0)
				{
					keystones.add(keystone.index());
				}
			}
			
			final int maxNodes = (FakePlayerPvpConfig.PASSIVE_TREE_MAX_SUBCLASSES * FakePlayerPvpConfig.PASSIVE_TREE_NODES_PER_SUBCLASS) + MAX_LEVEL;
			final Variant[] pool = new Variant[FakePlayerPvpConfig.PASSIVE_TREE_VARIANTS];
			for (int v = 0; v < pool.length; v++)
			{
				pool[v] = new Growth(key.origin(), effectWeights, keystones, maxNodes, PassiveTreeConfig.PASSIVE_TREE_MAX_POINTS).grow();
			}
			return pool;
		});
	}
	
	/**
	 * One growth order being made: a walk out from a START node, each step taking a random node next to the ones already taken, the ones the role wants more often, and heading for a keystone at each of {@link FakePlayerPvpConfig#PASSIVE_TREE_KEYSTONE_POINTS}.
	 */
	private class Growth
	{
		private final double[][] _effectWeights;
		private final List<Integer> _keystoneChoices;
		private final int _maxNodes;
		private final int _maxPoints;
		
		private final int[] _order;
		private final boolean[] _isKeystone;
		private final boolean[] _taken;
		private final boolean[] _queued;
		private final int[] _frontier;
		private final double[] _totals;
		private final List<Integer> _chosen = new ArrayList<>();
		private int _count;
		private int _frontierSize;
		private int _points;
		
		Growth(int origin, double[][] effectWeights, List<Integer> keystones, int maxNodes, int maxPoints)
		{
			_effectWeights = effectWeights;
			_keystoneChoices = keystones;
			_maxNodes = Math.max(1, Math.min(maxNodes, _nodes.length));
			_maxPoints = maxPoints;
			_order = new int[_maxNodes];
			_isKeystone = new boolean[_maxNodes];
			_taken = new boolean[_nodes.length];
			_queued = new boolean[_nodes.length];
			_frontier = new int[_nodes.length];
			_totals = new double[_keys.length];
			_queued[origin] = true;
			take(origin, false);
		}
		
		Variant grow()
		{
			final int[] targets = FakePlayerPvpConfig.PASSIVE_TREE_KEYSTONE_POINTS;
			int nextTarget = 0;
			while (_count < _maxNodes)
			{
				// Head for the next keystone once enough points are spent.
				if ((nextTarget < targets.length) && (_points >= targets[nextTarget]))
				{
					nextTarget++;
					final List<Integer> path = pathToKeystone();
					if (path != null)
					{
						for (int i = 0; (i < path.size()) && (_count < _maxNodes); i++)
						{
							take(path.get(i), i == (path.size() - 1));
						}
					}
					continue;
				}
				
				// Weighted pick among the nodes that still fit in the points left.
				final double[] weights = new double[_frontierSize];
				double total = 0;
				for (int i = 0; i < _frontierSize; i++)
				{
					if ((_points + _costs[_frontier[i]]) <= _maxPoints)
					{
						weights[i] = weight(_frontier[i]);
						total += weights[i];
					}
				}
				if (total <= 0)
				{
					break;
				}
				
				double roll = Rnd.nextDouble() * total;
				int pick = -1;
				for (int i = 0; i < _frontierSize; i++)
				{
					if (weights[i] > 0)
					{
						pick = i;
						roll -= weights[i];
						if (roll < 0)
						{
							break;
						}
					}
				}
				take(_frontier[pick], false);
			}
			
			final int[] nodeIds = new int[_count];
			final int[] costs = new int[_count];
			for (int i = 0; i < _count; i++)
			{
				nodeIds[i] = _nodes[_order[i]].getId();
				costs[i] = _costs[_order[i]];
			}
			return new Variant(nodeIds, costs, Arrays.copyOf(_isKeystone, _count), _nodes[_order[0]].getSector());
		}
		
		/**
		 * Takes a node: adds it to the order and its effects to the totals, and its neighbours the walk may take to the frontier (none for a keystone, which nothing hangs from).
		 * @param index the node index
		 * @param keystone whether it is a keystone
		 */
		private void take(int index, boolean keystone)
		{
			for (int i = 0; i < _frontierSize; i++)
			{
				if (_frontier[i] == index)
				{
					_frontier[i] = _frontier[--_frontierSize];
					break;
				}
			}
			
			_taken[index] = true;
			_order[_count] = index;
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
				return;
			}
			
			for (int next : _neighbors[index])
			{
				if (_eligible[next] && !_queued[next])
				{
					_queued[next] = true;
					_frontier[_frontierSize++] = next;
				}
			}
		}
		
		/**
		 * @param index a node index
		 * @return how much the role wants the node now: the weight of each of its effects (a drawback taken off), each bonus only for the part that still fits under its cap, twice for a notable
		 */
		private double weight(int index)
		{
			double weight = 0;
			for (int e = 0; e < _effectKeys[index].length; e++)
			{
				double effectWeight = _effectWeights[index][e];
				final double value = _effectValues[index][e];
				final double cap = _caps[_effectKeys[index][e]];
				if ((effectWeight > 0) && (value > 0) && !Double.isNaN(cap))
				{
					effectWeight *= Math.max(0, Math.min(1, (cap - _totals[_effectKeys[index][e]]) / value));
				}
				weight += effectWeight;
			}
			
			weight = Math.max(FILLER_WEIGHT, weight);
			return _nodes[index].getType() == NodeType.NOTABLE ? weight * 2 : weight;
		}
		
		/**
		 * Picks one of the role's keystones not taken yet, that can be combined with the ones taken and whose path fits in the points left (the nearer, the likelier), and finds the cheapest path to it.
		 * @return the nodes to take, the keystone last; {@code null} if none can be reached
		 */
		private List<Integer> pathToKeystone()
		{
			if (_keystoneChoices.isEmpty())
			{
				return null;
			}
			
			// Cheapest paths from the nodes taken, over the nodes the walk may take; ties go to the path through the nodes the role wants most.
			final long[] distance = new long[_nodes.length];
			final int[] pathPoints = new int[_nodes.length];
			final int[] previous = new int[_nodes.length];
			Arrays.fill(distance, Long.MAX_VALUE);
			Arrays.fill(previous, -1);
			final PriorityQueue<long[]> queue = new PriorityQueue<>((a, b) -> a[0] != b[0] ? Long.compare(a[0], b[0]) : Long.compare(a[1], b[1]));
			for (int i = 0; i < _count; i++)
			{
				if (!_isKeystone[i])
				{
					distance[_order[i]] = 0;
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
				if (head[0] > distance[index])
				{
					continue;
				}
				for (int next : _neighbors[index])
				{
					final boolean keystone = _keystoneChoices.contains(next);
					if (_taken[next] || (!_eligible[next] && !keystone))
					{
						continue;
					}
					
					final long step = (_costs[next] * PATH_POINT) + (99 - (long) Math.min(99, Math.max(0, weight(next))));
					if ((distance[index] + step) < distance[next])
					{
						distance[next] = distance[index] + step;
						pathPoints[next] = pathPoints[index] + _costs[next];
						previous[next] = index;
						if (!keystone) // A keystone ends a path.
						{
							queue.add(new long[]
							{
								distance[next],
								next
							});
						}
					}
				}
			}
			
			// The keystones in reach, the nearer the likelier.
			final List<Integer> reachable = new ArrayList<>();
			final List<Double> chances = new ArrayList<>();
			double total = 0;
			for (int keystone : _keystoneChoices)
			{
				if (_taken[keystone] || (distance[keystone] == Long.MAX_VALUE) || ((_points + pathPoints[keystone]) > _maxPoints) || conflictsWithChosen(keystone))
				{
					continue;
				}
				
				final double chance = 1.0 / (1 + pathPoints[keystone]);
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
			
			final List<Integer> path = new ArrayList<>();
			for (int index = target; (index >= 0) && !_taken[index]; index = previous[index])
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
	 * @return {@code true} if the growth walk may take it: not a START node (it only ever takes its own, as the first node), no skill (node skills only work for players) and no keystone (those are only reached on purpose, from the role's list)
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
