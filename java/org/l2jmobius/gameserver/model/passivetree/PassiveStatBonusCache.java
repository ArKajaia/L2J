package org.l2jmobius.gameserver.model.passivetree;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import org.l2jmobius.gameserver.data.custom.PassiveTreeData;
import org.l2jmobius.gameserver.managers.PassiveTreeManager;
import org.l2jmobius.gameserver.model.actor.Player;

/**
 * One aggregated lookup table per player: every currently-allocated node's effectSpec, summed by key, for the player's CURRENT class_index. IMPORTANT: recompute() always clears and rebuilds from scratch. Never patch this incrementally (add on allocate / subtract on deallocate) - a rebuild-
 * from-source-of-truth is immune to the kind of desync bugs that come from two code paths disagreeing about current state. Call recompute() any time the allocated node set could have changed: after allocate(), after a subclass switch, and once on login.
 */
public class PassiveStatBonusCache
{
	private final Map<String, Double> _totals = new HashMap<>();
	
	public void recompute(Player player)
	{
		_totals.clear();
		
		final Set<Integer> allocated = PassiveTreeManager.getInstance().getAllocatedNodes(player);
		for (int nodeId : allocated)
		{
			final PassiveNode node = PassiveTreeData.getInstance().getNode(nodeId);
			if ((node == null) || node.getEffectSpec().isEmpty())
			{
				continue;
			}
			
			for (String part : node.getEffectSpec().split(";"))
			{
				final String[] kv = part.split(":");
				if (kv.length != 2)
				{
					continue;
				}
				
				try
				{
					_totals.merge(kv[0].trim(), Double.parseDouble(kv[1].trim()), Double::sum);
				}
				catch (NumberFormatException ignored)
				{
					// Malformed effect spec in the XML - skip rather than crash stat calc.
				}
			}
		}
	}
	
	/** Maximum the passive tree may contribute to any single stat. */
	private static final Map<String, Double> CAPS = Map.ofEntries(Map.entry("STR", 5.0), Map.entry("DEX", 5.0), Map.entry("CON", 5.0), Map.entry("INT", 5.0), Map.entry("WIT", 5.0), Map.entry("MEN", 5.0),
		// keep the worst runaway offenders bounded too
		Map.entry("CRIT_DMG_PCT", 60.0), Map.entry("CRIT_RATE_ADD", 150.0), // /1000 scale -> +15% crit
		Map.entry("ACCURACY_ADD", 12.0), Map.entry("EVASION_ADD", 12.0), Map.entry("SHIELD_RATE_PCT", 25.0), Map.entry("REFLECT_PCT", 30.0));
	
	public double get(String key)
	{
		final double raw = _totals.getOrDefault(key, 0.0);
		final Double cap = CAPS.get(key);
		if (cap == null)
		{
			return raw;
		}
		// only cap the positive side - keystone drawbacks must stay fully applied
		return raw > 0 ? Math.min(raw, cap) : raw;
	}
}
