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
import java.util.Set;
import java.util.logging.Logger;

import org.l2jmobius.commons.util.Rnd;
import org.l2jmobius.gameserver.config.custom.FakePlayerPvpConfig;
import org.l2jmobius.gameserver.config.custom.PassiveTreeConfig;
import org.l2jmobius.gameserver.data.custom.PassiveTreeData;
import org.l2jmobius.gameserver.model.actor.enums.player.PlayerClass;
import org.l2jmobius.gameserver.model.actor.holders.npc.FakePlayerPvpBuild;
import org.l2jmobius.gameserver.model.actor.holders.npc.FakePlayerPvpBuild.Role;
import org.l2jmobius.gameserver.model.actor.holders.npc.FakePlayerPvpPassives;
import org.l2jmobius.gameserver.model.passivetree.PassiveNode;
import org.l2jmobius.gameserver.model.passivetree.PassiveNode.NodeType;
import org.l2jmobius.gameserver.model.passivetree.PassiveStatBonusCache;
import org.l2jmobius.gameserver.model.skill.PassiveTreeArchetypes;

/**
 * Allocates the passive tree of roaming fake players.
 * <p>
 * All the graph work happens once, when this is first used (at server start, see {@link FakePlayerPvpManager}): for every starting point and every {@link Role}, {@link FakePlayerPvpConfig#PASSIVE_TREE_VARIANTS} random growth orders are made, each one walking out from the START node and
 * preferring the nodes that role uses. Every prefix of an order is connected to its START node, so it is an allocation a player could have made. A spawn ({@link #roll}) only picks one of them and adds up the effects of its first nodes.
 * <p>
 * Keystones ({@code KS_*} keys) and skill nodes are never taken: the passive tree mechanics and node skills only work for players.
 */
public class FakePlayerPvpPassiveTree
{
	private static final Logger LOGGER = Logger.getLogger(FakePlayerPvpPassiveTree.class.getName());

	/** Highest fake player level. */
	private static final int MAX_LEVEL = 85;

	/** Weight of a node with nothing the role uses, so it is still taken now and then as a bridge to better ones. */
	private static final double FILLER_WEIGHT = 0.25;
	/** Weight of an effect key every role uses (HP, defence...). */
	private static final double COMMON_WEIGHT = 2;
	/** Weight of an effect key the role is built on (P. Atk. for fighters, M. Atk. for mages...). */
	private static final double ROLE_WEIGHT = 3;

	/** Keys every role uses. */
	private static final Set<String> COMMON_KEYS = Set.of("MAXHP", "MAXHP_PCT", "MAXCP", "PDEF_PCT", "MDEF_PCT", "EVASION_ADD", "CON", "MEN", "HP_REGEN_PCT", "DEBUFF_RES_PCT", "CRIT_DMG_TAKEN_RED_PCT", "MOVE_SPEED_ADD", "HEALING_RECEIVED_PCT", "SKILL_DODGE_PCT", "MAGIC_REFLECT_PCT", "SKILL_REFLECT_PCT", "REFLECT_PCT", "INTERRUPT_RES_PCT");
	/** Keys every physical role uses. */
	private static final Set<String> PHYSICAL_KEYS = Set.of("PATK_PCT", "ATK_SPD_PCT", "CRIT_RATE_ADD", "CRIT_DMG_PCT", "ACCURACY_ADD", "STR", "DEX", "LIFESTEAL_PCT", "PHYS_SKILL_POWER_PCT", "SKILL_CDR_PCT", "PVE_PDMG_PCT");
	/** Keys mages use. */
	private static final Set<String> MAGIC_KEYS = Set.of("MATK_PCT", "CAST_SPD_PCT", "MCRIT_RATE_ADD", "MCRIT_DMG_PCT", "INT", "WIT", "SPELL_CDR_PCT", "PVE_MDMG_PCT");
	private static final Set<String> TANK_KEYS = Set.of("SHIELD_RATE_PCT", "SHIELD_DEF_PCT", "SHIELD_RATE_MUL_PCT");
	private static final Set<String> DAGGER_KEYS = Set.of("BLOW_RATE_PCT");
	private static final Set<String> ARCHER_KEYS = Set.of("PVE_BOW_DMG_PCT");

	/**
	 * One growth order.
	 * @param nodeIds the node ids in the order they were taken, the START node first
	 * @param cumulativeCost the points the first {@code i + 1} nodes cost
	 * @param sector the sector of the START node
	 */
	private record Variant(int[] nodeIds, int[] cumulativeCost, String sector)
	{
	}

	/** Index -> node id. */
	private final int[] _ids;
	/** Index -> point cost. */
	private final int[] _costs;
	/** Index -> indexes of its neighbours, both directions. */
	private final int[][] _neighbors;
	/** Index -> whether a fake player may take it. */
	private final boolean[] _eligible;
	/** Sector -> index of its START node. */
	private final Map<String, Integer> _origins = new HashMap<>();
	/** [START node index][role] -> growth orders. */
	private final Map<Integer, Variant[][]> _pools = new HashMap<>();

	protected FakePlayerPvpPassiveTree()
	{
		final long start = System.currentTimeMillis();
		final Map<Integer, PassiveNode> nodes = PassiveTreeData.getInstance().getAllNodes();
		final int count = nodes.size();
		_ids = new int[count];
		_costs = new int[count];
		_eligible = new boolean[count];

		final Map<Integer, Integer> indexes = new HashMap<>();
		int index = 0;
		for (PassiveNode node : nodes.values())
		{
			_ids[index] = node.getId();
			_costs[index] = Math.max(0, node.getCost());
			_eligible[index] = isEligible(node);
			indexes.put(node.getId(), index);
			if (node.getType() == NodeType.START)
			{
				_origins.putIfAbsent(node.getSector(), index);
			}
			index++;
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

		// How much each role wants each node.
		final Role[] roles = Role.values();
		final double[][] weights = new double[roles.length][count];
		for (Role role : roles)
		{
			for (int i = 0; i < count; i++)
			{
				weights[role.ordinal()][i] = nodeWeight(role, nodes.get(_ids[i]));
			}
		}

		final int maxNodes = (FakePlayerPvpConfig.PASSIVE_TREE_MAX_SUBCLASSES * FakePlayerPvpConfig.PASSIVE_TREE_NODES_PER_SUBCLASS) + MAX_LEVEL;
		final int maxPoints = PassiveTreeConfig.PASSIVE_TREE_MAX_POINTS;
		int variants = 0;
		for (int origin : _origins.values())
		{
			final Variant[][] pool = new Variant[roles.length][FakePlayerPvpConfig.PASSIVE_TREE_VARIANTS];
			for (Role role : roles)
			{
				for (int v = 0; v < pool[role.ordinal()].length; v++)
				{
					pool[role.ordinal()][v] = grow(origin, weights[role.ordinal()], maxNodes, maxPoints);
					variants++;
				}
			}
			_pools.put(origin, pool);
		}

		LOGGER.info(getClass().getSimpleName() + ": Prepared " + variants + " passive trees for " + _origins.size() + " starting points in " + (System.currentTimeMillis() - start) + " ms.");
	}

	/**
	 * @return {@code true} if fake players get a passive tree
	 */
	public static boolean isEnabled()
	{
		return FakePlayerPvpConfig.PASSIVE_TREE_ENABLED && PassiveTreeConfig.PASSIVE_TREE_ENABLED && (PassiveTreeConfig.PASSIVE_TREE_MAX_POINTS > 0);
	}

	/**
	 * Rolls the passive tree of a new fake player: its subclasses, then {@code subclasses * FakePvpPassiveTreeNodesPerSubclass + level} nodes of one of the prepared trees of its class, never costing more than the points a player can have
	 * ({@link PassiveTreeConfig#PASSIVE_TREE_MAX_POINTS}).
	 * @param build the build
	 * @param playerClass the class it has now (used when the build has no final class)
	 * @param level its level
	 * @return its passive tree, {@code null} if it can't have one
	 */
	public FakePlayerPvpPassives roll(FakePlayerPvpBuild build, PlayerClass playerClass, int level)
	{
		final PlayerClass sectorClass = build.getPlayerClass() != null ? build.getPlayerClass() : playerClass;
		Integer origin = _origins.get(PassiveTreeArchetypes.sectorFor(sectorClass != null ? sectorClass.getId() : -1));
		if (origin == null)
		{
			origin = _origins.get(PassiveTreeArchetypes.VANGUARD);
		}
		final Variant[][] pool = origin != null ? _pools.get(origin) : null;
		if (pool == null)
		{
			return null;
		}

		final Variant[] variants = pool[build.getRole().ordinal()];
		final Variant variant = variants[Rnd.get(variants.length)];
		final int subclasses = rollSubclasses(level);

		// As many nodes as asked, as long as they fit in the points a player can have.
		int count = Math.min((subclasses * FakePlayerPvpConfig.PASSIVE_TREE_NODES_PER_SUBCLASS) + level, variant.nodeIds().length);
		while ((count > 0) && (variant.cumulativeCost()[count - 1] > PassiveTreeConfig.PASSIVE_TREE_MAX_POINTS))
		{
			count--;
		}

		final List<Integer> nodeIds = new ArrayList<>(count);
		for (int i = 0; i < count; i++)
		{
			nodeIds.add(variant.nodeIds()[i]);
		}
		final PassiveStatBonusCache bonus = new PassiveStatBonusCache();
		bonus.recompute(nodeIds);
		return new FakePlayerPvpPassives(subclasses, count, count > 0 ? variant.cumulativeCost()[count - 1] : 0, variant.sector(), bonus);
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
	 * Walks out from a START node, each step taking a random node next to the ones already taken, the ones the role wants more often.
	 * @param origin index of the START node
	 * @param weights index -> how much the role wants the node
	 * @param maxNodes the most nodes to take
	 * @param maxPoints the most points to spend
	 * @return the growth order
	 */
	private Variant grow(int origin, double[] weights, int maxNodes, int maxPoints)
	{
		final int length = Math.max(1, Math.min(maxNodes, _ids.length));
		final int[] order = new int[length];
		final int[] cumulativeCost = new int[length];
		final boolean[] queued = new boolean[_ids.length];
		final int[] frontier = new int[_ids.length];
		int frontierSize = 0;

		int points = _costs[origin];
		order[0] = _ids[origin];
		cumulativeCost[0] = points;
		int count = 1;
		queued[origin] = true;
		for (int next : _neighbors[origin])
		{
			if (_eligible[next] && !queued[next])
			{
				queued[next] = true;
				frontier[frontierSize++] = next;
			}
		}

		while ((count < length) && (frontierSize > 0))
		{
			// Weighted pick among the nodes that still fit in the points left.
			double total = 0;
			for (int i = 0; i < frontierSize; i++)
			{
				if ((points + _costs[frontier[i]]) <= maxPoints)
				{
					total += weights[frontier[i]];
				}
			}
			if (total <= 0)
			{
				break;
			}

			double roll = Rnd.nextDouble() * total;
			int pick = -1;
			for (int i = 0; i < frontierSize; i++)
			{
				if ((points + _costs[frontier[i]]) <= maxPoints)
				{
					pick = i;
					roll -= weights[frontier[i]];
					if (roll < 0)
					{
						break;
					}
				}
			}

			final int taken = frontier[pick];
			frontier[pick] = frontier[--frontierSize];
			points += _costs[taken];
			order[count] = _ids[taken];
			cumulativeCost[count] = points;
			count++;

			for (int next : _neighbors[taken])
			{
				if (_eligible[next] && !queued[next])
				{
					queued[next] = true;
					frontier[frontierSize++] = next;
				}
			}
		}

		return new Variant(Arrays.copyOf(order, count), Arrays.copyOf(cumulativeCost, count), PassiveTreeData.getInstance().getNode(_ids[origin]).getSector());
	}

	/**
	 * @param node a node
	 * @return {@code true} if a fake player may take it: not a START node (it only ever takes its own, as the first node), no skill and no keystone mechanic, since those only work for players
	 */
	private static boolean isEligible(PassiveNode node)
	{
		if ((node.getType() == NodeType.START) || node.grantsSkill())
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
	 * @param node a node
	 * @return how much the role wants the node: the weight of every effect key it has (half for a conditional one, which may not apply, and taken off for a drawback), twice for a notable
	 */
	private static double nodeWeight(Role role, PassiveNode node)
	{
		double weight = 0;
		for (Map.Entry<String, Double> effect : node.getEffects().entrySet())
		{
			final String key = effect.getKey();
			final int at = key.indexOf('@');
			final double keyWeight = keyWeight(role, at > 0 ? key.substring(0, at) : key) / (at > 0 ? 2 : 1);
			weight += effect.getValue() < 0 ? -keyWeight : keyWeight;
		}

		weight = Math.max(FILLER_WEIGHT, weight);
		return node.getType() == NodeType.NOTABLE ? weight * 2 : weight;
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

	public static FakePlayerPvpPassiveTree getInstance()
	{
		return SingletonHolder.INSTANCE;
	}

	private static class SingletonHolder
	{
		protected static final FakePlayerPvpPassiveTree INSTANCE = new FakePlayerPvpPassiveTree();
	}
}
