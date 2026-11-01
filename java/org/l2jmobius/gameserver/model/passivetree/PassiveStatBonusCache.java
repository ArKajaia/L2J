package org.l2jmobius.gameserver.model.passivetree;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import org.l2jmobius.gameserver.config.custom.PassiveTreeConfig;
import org.l2jmobius.gameserver.data.custom.PassiveTreeData;
import org.l2jmobius.gameserver.managers.PassiveTreeManager;
import org.l2jmobius.gameserver.model.actor.Player;

/**
 * One aggregated lookup table per player: every currently-allocated node's effectSpec, summed by key, for the player's CURRENT class_index. IMPORTANT: recompute() always clears and rebuilds from scratch. Never patch this incrementally (add on allocate / subtract on deallocate) - a rebuild-
 * from-source-of-truth is immune to the kind of desync bugs that come from two code paths disagreeing about current state. Call recompute() any time the allocated node set could have changed: after allocate(), after a subclass switch, and once on login.
 */
public class PassiveStatBonusCache
{
	// Rebuilt into a new map and then swapped in, so the combat threads reading it never see one half built (it is never changed once published).
	private volatile Map<String, Double> _totals = new HashMap<>();
	private volatile double _scale = 1.0;
	
	public void recompute(Player player)
	{
		recompute(PassiveTreeManager.getInstance().getAllocatedNodes(player), PassiveTreeManager.getInstance().getEventNotableScale(player));
	}
	
	/**
	 * Rebuilds the totals from an allocation that isn't a player's own (a roaming fake player's precomputed path).
	 * @param nodeIds the allocated node ids
	 */
	public void recompute(Collection<Integer> nodeIds)
	{
		recompute(nodeIds, 1);
	}
	
	/**
	 * @param nodeIds the allocated node ids
	 * @param notableScale how much the effects of NOTABLE nodes count (1 as they are; a Deathmatch: Mayhem soul hound's count more)
	 */
	public void recompute(Collection<Integer> nodeIds, double notableScale)
	{
		final Map<String, Double> totals = new HashMap<>();
		for (int nodeId : nodeIds)
		{
			final PassiveNode node = PassiveTreeData.getInstance().getNode(nodeId);
			if (node == null)
			{
				continue;
			}
			
			final double scale = node.getType() == PassiveNode.NodeType.NOTABLE ? notableScale : 1;
			for (Map.Entry<String, Double> effect : node.getEffects().entrySet())
			{
				totals.merge(effect.getKey(), effect.getValue() * scale, Double::sum);
			}
		}
		
		_totals = totals;
	}
	
	/** Base stats the passive tree may contribute to, capped by {@link PassiveTreeConfig#BASE_STAT_CAP}. */
	private static final Set<String> BASE_STATS = Set.of("STR", "DEX", "CON", "INT", "WIT", "MEN");
	
	/**
	 * @param key an effect key; a conditional key ({@code PDEF_PCT@HEAVY}) is capped like its base key
	 * @return the most the tree may add to that key, or {@code null} if it is uncapped
	 */
	public static Double getCap(String key)
	{
		final int at = key.indexOf('@');
		final String base = at > 0 ? key.substring(0, at) : key;
		if (BASE_STATS.contains(base))
		{
			return PassiveTreeConfig.BASE_STAT_CAP < 0 ? null : (double) PassiveTreeConfig.BASE_STAT_CAP;
		}
		return PassiveTreeConfig.STAT_CAPS.get(base);
	}
	
	/**
	 * @return every capped effect key and its cap, as {@link #getCap} applies them
	 */
	public static Map<String, Double> getCaps()
	{
		final Map<String, Double> caps = new HashMap<>(PassiveTreeConfig.STAT_CAPS);
		if (PassiveTreeConfig.BASE_STAT_CAP >= 0)
		{
			for (String stat : BASE_STATS)
			{
				caps.put(stat, (double) PassiveTreeConfig.BASE_STAT_CAP);
			}
		}
		return caps;
	}
	
	/**
	 * @return every effect key the allocated nodes carry, including conditional ones such as {@code PDEF_PCT@HEAVY}
	 */
	public Set<String> keys()
	{
		return _totals.keySet();
	}
	
	/**
	 * @param scale share of the positive totals {@link #get} returns, e.g. lowered while in a combat transformation; survives {@link #recompute}
	 */
	public void setScale(double scale)
	{
		_scale = scale;
	}

	public double get(String key)
	{
		double raw = _totals.getOrDefault(key, 0.0);
		if (raw > 0)
		{
			raw *= _scale;
		}

		final Double cap = getCap(key);
		if (cap == null)
		{
			return raw;
		}
		// only scale and cap the positive side - keystone drawbacks must stay fully applied
		return raw > 0 ? Math.min(raw, cap) : raw;
	}
}
