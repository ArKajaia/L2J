package org.l2jmobius.gameserver.managers;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;

import org.l2jmobius.gameserver.config.custom.PassiveTreeConfig;
import org.l2jmobius.gameserver.data.custom.PassiveTreeData;
import org.l2jmobius.gameserver.model.actor.Player;
import org.l2jmobius.gameserver.model.passivetree.PassiveNode;

/**
 * One player's passive tree as the planners show it: the web page (PassiveTreeApiServer's character JSON) and the game client's overlay (Ex2PassiveTreeState) both read this, so they always report the same numbers.
 * @param name the character's name
 * @param level its level
 * @param earnedPoints points earned
 * @param spentPoints points the allocated nodes cost
 * @param availablePoints points left
 * @param allocated the allocated node ids
 * @param activeTemplate the template in use, from 1
 * @param peaceOnly whether templates may only be switched in a peace zone
 * @param inPeaceZone whether the player may switch here
 * @param delaySeconds the wait between two switches
 * @param cooldownMs how long until the player may switch again
 * @param templates every template of the current class slot
 */
public record PassiveTreeView(String name, int level, int earnedPoints, int spentPoints, int availablePoints, List<Integer> allocated, int activeTemplate, boolean peaceOnly, boolean inPeaceZone, int delaySeconds, long cooldownMs, List<PassiveTreeManager.TemplateInfo> templates)
{
	/**
	 * @param player a player
	 * @return the player's tree now
	 */
	/**
	 * @param allocated allocated node ids
	 * @return the ones on the tree: the event-only nodes of a Mayhem tree (see PassiveTreeData#EVENT_FILE) are not on the client's tree, and are shown in the event's pick window instead
	 */
	private static List<Integer> treeNodes(Collection<Integer> allocated)
	{
		final Map<Integer, PassiveNode> tree = PassiveTreeData.getInstance().getAllNodes();
		final List<Integer> nodes = new ArrayList<>(allocated.size());
		for (int id : allocated)
		{
			if (tree.containsKey(id))
			{
				nodes.add(id);
			}
		}
		return nodes;
	}
	
	public static PassiveTreeView of(Player player)
	{
		final PassiveTreeManager mgr = PassiveTreeManager.getInstance();
		final int earned = mgr.getEarnedPoints(player);
		final int spent = mgr.getSpentPoints(player);
		return new PassiveTreeView(player.getName(), player.getLevel(), earned, spent, earned - spent, treeNodes(mgr.getAllocatedNodes(player)), mgr.getActiveTemplate(player), PassiveTreeConfig.TEMPLATE_PEACE_ZONE_ONLY, mgr.canSwitchTemplateHere(player), PassiveTreeConfig.TEMPLATE_SWITCH_DELAY, mgr.getTemplateCooldownRemaining(player), mgr.getTemplates(player));
	}
}
