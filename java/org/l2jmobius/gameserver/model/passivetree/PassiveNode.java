package org.l2jmobius.gameserver.model.passivetree;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * A single node in the passive tree.
 * <p>
 * Nodes now carry explicit x/y layout coordinates. This is required because
 * the tree is a MESH (nodes average ~5 connections, with ring loops and
 * cross-sector bridges), not a strict tree - so a client-side "compute
 * position from BFS depth" layout no longer produces a sane picture. The
 * generator decides positions; everything downstream just reads them.
 * <p>
 * "Parents" are really NEIGHBOURS: edges are emitted symmetrically, so a
 * path can be walked in either direction. Only START nodes have an empty
 * parent list, which is what makes them the sole valid entry points.
 */
public class PassiveNode
{
	public enum NodeType { START, SMALL, NOTABLE, KEYSTONE, MASTER, FUNCTION, ACTIVE_SKILL }

	private final int id;
	private final String name;
	private final String description;
	private final String sector;
	private final NodeType type;
	private final int tier;
	private final int cost;
	private final double x;
	private final double y;

	/** e.g. "STR:1", "PATK_PCT:0.5", "PDEF_PCT:1.5;MDEF_PCT:1.5". Empty for pure skill-grant nodes. */
	private final String effectSpec;
	/** effectSpec parsed once: key -> value, summed if a key repeats. Malformed parts are left out. */
	private final Map<String, Double> effects;

	/** 0 if this node grants no skill. */
	private final int skillId;
	/** 0 = "auto": scaled to the character level. */
	private final int skillLevel;

	private final List<Integer> parents = new ArrayList<>();

	/** Centre of the circle this node sits on (orbitX/orbitY in the XML), NaN if none. The planner draws a link between two nodes on the same circle as an arc. */
	private double orbitX = Double.NaN;
	private double orbitY = Double.NaN;

	/** Themed glyph the web planner draws for this node (icon="hammer" in the XML), empty for "pick by stat". */
	private String icon = "";

	/** An event-only node (data/passivetree/MayhemNodes.xml): never part of the tree, only offered in Deathmatch: Mayhem, to the ones it is for. */
	private boolean eventOnly;
	/** Who an event-only node is for: fake player roles (FIGHTER, TANK, DAGGER, ARCHER, MAGE), races (DWARF...) and class names (PlayerClass); an empty set doesn't restrict. A creature must match every set that isn't empty. */
	private Set<String> eventRoles = Set.of();
	private Set<String> eventRaces = Set.of();
	private Set<String> eventClasses = Set.of();
	/** How much a fake player wants an event-only node it may take (a typical notable weighs about 1). */
	private double eventWeight = 1;

	public PassiveNode(int id, String name, String description, String sector, NodeType type, int tier, int cost,
		double x, double y, String effectSpec, int skillId, int skillLevel)
	{
		this.id = id;
		this.name = name;
		this.description = description;
		this.sector = sector;
		this.type = type;
		this.tier = tier;
		this.cost = cost;
		this.x = x;
		this.y = y;
		this.effectSpec = effectSpec == null ? "" : effectSpec;
		this.effects = parseEffects(this.effectSpec);
		this.skillId = skillId;
		this.skillLevel = skillLevel;
	}

	public int getId() { return id; }
	public String getName() { return name; }
	public String getDescription() { return description; }
	public String getSector() { return sector; }
	public NodeType getType() { return type; }
	public int getTier() { return tier; }
	public int getCost() { return cost; }
	public double getX() { return x; }
	public double getY() { return y; }
	public String getEffectSpec() { return effectSpec; }
	public Map<String, Double> getEffects() { return effects; }
	public int getSkillId() { return skillId; }
	public int getSkillLevel() { return skillLevel; }
	public List<Integer> getParents() { return parents; }
	public void addParent(int nodeId) { parents.add(nodeId); }
	public boolean isRoot() { return parents.isEmpty(); }
	public boolean hasOrbit() { return !Double.isNaN(orbitX) && !Double.isNaN(orbitY); }
	public double getOrbitX() { return orbitX; }
	public double getOrbitY() { return orbitY; }
	public void setOrbit(double centerX, double centerY) { orbitX = centerX; orbitY = centerY; }
	public String getIcon() { return icon; }
	public void setIcon(String name) { icon = name == null ? "" : name.trim(); }
	public boolean isEventOnly() { return eventOnly; }
	public Set<String> getEventRoles() { return eventRoles; }
	public Set<String> getEventRaces() { return eventRaces; }
	public Set<String> getEventClasses() { return eventClasses; }
	public double getEventWeight() { return eventWeight; }
	public void setEventOnly(Set<String> roles, Set<String> races, Set<String> classes, double weight)
	{
		eventOnly = true;
		eventRoles = Set.copyOf(roles);
		eventRaces = Set.copyOf(races);
		eventClasses = Set.copyOf(classes);
		eventWeight = weight;
	}
	public boolean grantsSkill() { return skillId > 0; }
	/** skillLevel="auto" in the XML: the granted level follows the character level (see PassiveTreeManager.getScaledSkillLevel). */
	public boolean isSkillLevelScaled() { return skillLevel <= 0; }

	private static Map<String, Double> parseEffects(String spec)
	{
		if (spec.isEmpty())
		{
			return Collections.emptyMap();
		}

		final Map<String, Double> result = new LinkedHashMap<>();
		for (String part : spec.split(";"))
		{
			final String[] kv = part.split(":");
			if (kv.length != 2)
			{
				continue;
			}

			try
			{
				result.merge(kv[0].trim(), Double.parseDouble(kv[1].trim()), Double::sum);
			}
			catch (NumberFormatException ignored)
			{
				// Malformed effect spec in the XML - skip rather than crash stat calc.
			}
		}
		return Collections.unmodifiableMap(result);
	}
}
