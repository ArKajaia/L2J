package org.l2jmobius.gameserver.data.custom;

import java.io.File;
import java.util.HashMap;
import java.util.Map;
import java.util.logging.Logger;

import org.w3c.dom.Document;
import org.w3c.dom.NamedNodeMap;
import org.w3c.dom.Node;

import org.l2jmobius.commons.util.IXmlReader;
import org.l2jmobius.gameserver.model.passivetree.PassiveNode;
import org.l2jmobius.gameserver.model.passivetree.PassiveNode.NodeType;

/**
 * Loads every data/passivetree/*.xml file into a flat node map keyed by id. Node ids must be globally unique across all sector files (not just unique within one file) since edges cross sector boundaries at the bridge and Nexus-ingress nodes.
 */
public class PassiveTreeData implements IXmlReader
{
	private static final Logger LOGGER = Logger.getLogger(PassiveTreeData.class.getName());
	
	private final Map<Integer, PassiveNode> _nodes = new HashMap<>();
	
	protected PassiveTreeData()
	{
		load();
		validate();
	}
	
	@Override
	public void load()
	{
		_nodes.clear();
		parseDatapackDirectory("data/passivetree", false);
		LOGGER.info(getClass().getSimpleName() + ": Loaded " + _nodes.size() + " passive tree nodes.");
	}
	
	@Override
	public void parseDocument(Document document, File file)
	{
		for (Node list = document.getFirstChild(); list != null; list = list.getNextSibling())
		{
			if (!list.getNodeName().equalsIgnoreCase("list"))
			{
				continue;
			}
			
			for (Node n = list.getFirstChild(); n != null; n = n.getNextSibling())
			{
				if (!n.getNodeName().equalsIgnoreCase("node"))
				{
					continue;
				}
				
				final NamedNodeMap attrs = n.getAttributes();
				final int id = parseInteger(attrs, "id");
				final String name = parseString(attrs, "name");
				final String sector = parseString(attrs, "sector");
				final NodeType type = NodeType.valueOf(parseString(attrs, "type", "SMALL").toUpperCase());
				final int tier = attrs.getNamedItem("tier") != null ? parseInteger(attrs, "tier") : 0;
				final String effect = attrs.getNamedItem("effect") != null ? parseString(attrs, "effect") : "";
				final int skillId = attrs.getNamedItem("skillId") != null ? parseInteger(attrs, "skillId") : 0;
				final int skillLevel = attrs.getNamedItem("skillLevel") != null ? parseInteger(attrs, "skillLevel") : 1;
				final int cost = attrs.getNamedItem("cost") != null ? parseInteger(attrs, "cost") : defaultCost(type);
				final double x = attrs.getNamedItem("x") != null ? Double.parseDouble(attrs.getNamedItem("x").getNodeValue()) : 0;
				final double y = attrs.getNamedItem("y") != null ? Double.parseDouble(attrs.getNamedItem("y").getNodeValue()) : 0;
				final String description = attrs.getNamedItem("description") != null ? parseString(attrs, "description") : autoDescribe(effect);
				
				if (_nodes.containsKey(id))
				{
					LOGGER.warning(getClass().getSimpleName() + ": Duplicate node id " + id + " in " + file.getName() + " - ignoring the duplicate.");
					continue;
				}
				
				final PassiveNode node = new PassiveNode(id, name, description, sector, type, tier, cost, x, y, effect, skillId, skillLevel);
				
				for (Node child = n.getFirstChild(); child != null; child = child.getNextSibling())
				{
					if (child.getNodeName().equalsIgnoreCase("requires"))
					{
						node.addParent(parseInteger(child.getAttributes(), "nodeId"));
					}
				}
				
				_nodes.put(id, node);
			}
		}
	}
	
	private int defaultCost(NodeType type)
	{
		switch (type)
		{
			case NOTABLE:
			case ACTIVE_SKILL:
			case FUNCTION:
				return 2;
			case KEYSTONE:
			case MASTER:
				return 3;
			case START:
				return 0;
			default:
				return 1;
		}
	}
	
	private String autoDescribe(String effect)
	{
		if ((effect == null) || effect.isEmpty())
		{
			return "";
		}
		return effect.replace(";", ", ").replace(":", " ");
	}
	
	/**
	 * Checks every parent reference resolves, and that every node is actually reachable from some START node. On a 500+ node hand-tunable mesh these are the two failures that hide silently: a typo'd nodeId just makes a node permanently un-allocatable (it looks "locked" forever), and an orphaned
	 * cluster looks fine on the map but can never be pathed to.
	 */
	public void validate()
	{
		int refErrors = 0;
		for (PassiveNode node : _nodes.values())
		{
			for (int parentId : node.getParents())
			{
				if (!_nodes.containsKey(parentId))
				{
					LOGGER.warning(getClass().getSimpleName() + ": Node " + node.getId() + " (\"" + node.getName() + "\") references missing parent id " + parentId + ".");
					refErrors++;
				}
			}
		}
		
		// Reachability sweep from every START node.
		final Map<Integer, java.util.List<Integer>> adjacency = new HashMap<>();
		for (PassiveNode node : _nodes.values())
		{
			for (int parentId : node.getParents())
			{
				adjacency.computeIfAbsent(parentId, k -> new java.util.ArrayList<>()).add(node.getId());
			}
		}
		
		final java.util.Set<Integer> seen = new java.util.HashSet<>();
		final java.util.Deque<Integer> queue = new java.util.ArrayDeque<>();
		for (PassiveNode node : _nodes.values())
		{
			if (node.getType() == NodeType.START)
			{
				seen.add(node.getId());
				queue.add(node.getId());
			}
		}
		while (!queue.isEmpty())
		{
			final int current = queue.poll();
			for (int next : adjacency.getOrDefault(current, java.util.Collections.emptyList()))
			{
				if (seen.add(next))
				{
					queue.add(next);
				}
			}
		}
		
		final int unreachable = _nodes.size() - seen.size();
		if ((refErrors == 0) && (unreachable == 0))
		{
			LOGGER.info(getClass().getSimpleName() + ": Validation passed - all references resolve, all " + _nodes.size() + " nodes reachable from a START node.");
		}
		else
		{
			LOGGER.warning(getClass().getSimpleName() + ": Validation issues - " + refErrors + " broken reference(s), " + unreachable + " unreachable node(s).");
		}
	}
	
	public PassiveNode getNode(int id)
	{
		return _nodes.get(id);
	}
	
	public Map<Integer, PassiveNode> getAllNodes()
	{
		return _nodes;
	}
	
	public static PassiveTreeData getInstance()
	{
		return SingletonHolder.INSTANCE;
	}
	
	private static class SingletonHolder
	{
		protected static final PassiveTreeData INSTANCE = new PassiveTreeData();
	}
}
