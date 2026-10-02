package org.l2jmobius.gameserver.model.passivetree;

import java.util.Collection;
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
		recompute(PassiveTreeManager.getInstance().getAllocatedNodes(player));
	}
	
	/**
	 * Rebuilds the totals from an allocation that isn't a player's own (a roaming fake player's precomputed path).
	 * @param nodeIds the allocated node ids
	 */
	public void recompute(Collection<Integer> nodeIds)
	{
		_totals.clear();
		
		for (int nodeId : nodeIds)
		{
			final PassiveNode node = PassiveTreeData.getInstance().getNode(nodeId);
			if (node == null)
			{
				continue;
			}
			
			for (Map.Entry<String, Double> effect : node.getEffects().entrySet())
			{
				_totals.merge(effect.getKey(), effect.getValue(), Double::sum);
			}
		}
	}
	
	/** Maximum the passive tree may contribute to any single stat. */
	private static final Map<String, Double> CAPS = Map.ofEntries(Map.entry("STR", 5.0), Map.entry("DEX", 5.0), Map.entry("CON", 5.0), Map.entry("INT", 5.0), Map.entry("WIT", 5.0), Map.entry("MEN", 5.0),
		// keep the worst runaway offenders bounded too
		Map.entry("CRIT_DMG_PCT", 60.0), Map.entry("CRIT_RATE_ADD", 150.0), // /1000 scale -> +15% crit
		Map.entry("ACCURACY_ADD", 12.0), Map.entry("EVASION_ADD", 12.0), Map.entry("SHIELD_RATE_PCT", 25.0), Map.entry("REFLECT_PCT", 30.0),
		// hybrid-sector mechanics
		Map.entry("LIFESTEAL_PCT", 8.0), Map.entry("MANA_LEECH_PCT", 6.0), Map.entry("SKILL_DODGE_PCT", 12.0), Map.entry("MAGIC_REFLECT_PCT", 10.0), Map.entry("SKILL_REFLECT_PCT", 10.0), //
		Map.entry("PVE_PDMG_PCT", 25.0), Map.entry("PVE_MDMG_PCT", 25.0), Map.entry("PVE_BOW_DMG_PCT", 25.0), Map.entry("PHYS_SKILL_POWER_PCT", 20.0), //
		Map.entry("MCRIT_DMG_PCT", 40.0), Map.entry("BLOW_RATE_PCT", 20.0), Map.entry("HEALING_RECEIVED_PCT", 40.0), //
		Map.entry("SKILL_CDR_PCT", 20.0), Map.entry("SPELL_CDR_PCT", 20.0), Map.entry("SPELL_MP_COST_RED_PCT", 30.0), //
		Map.entry("CRIT_DMG_TAKEN_RED_PCT", 30.0), Map.entry("INTERRUPT_RES_PCT", 50.0), Map.entry("DEBUFF_RES_PCT", 30.0), //
		// keystones
		Map.entry("SERVITOR_SHARE_PCT", 50.0), Map.entry("SHIELD_RATE_MUL_PCT", 50.0), Map.entry("MAXHP_PCT", 60.0), //
		// utility unlocks
		Map.entry("INVENTORY_SLOTS_ADD", 40.0), Map.entry("WEIGHT_LIMIT_PCT", 100.0), Map.entry("EXP_RATE_PCT", 25.0), Map.entry("SP_RATE_PCT", 25.0));
	
	/**
	 * @return every effect key the allocated nodes carry, including conditional ones such as {@code PDEF_PCT@HEAVY}
	 */
	public Set<String> keys()
	{
		return _totals.keySet();
	}
	
	public double get(String key)
	{
		final double raw = _totals.getOrDefault(key, 0.0);
		// a conditional key (PDEF_PCT@HEAVY) is capped like its base key
		final int at = key.indexOf('@');
		final Double cap = CAPS.get(at > 0 ? key.substring(0, at) : key);
		if (cap == null)
		{
			return raw;
		}
		// only cap the positive side - keystone drawbacks must stay fully applied
		return raw > 0 ? Math.min(raw, cap) : raw;
	}
}
