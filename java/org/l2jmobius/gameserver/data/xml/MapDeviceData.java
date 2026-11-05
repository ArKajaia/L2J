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
package org.l2jmobius.gameserver.data.xml;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.logging.Logger;

import org.w3c.dom.Document;
import org.w3c.dom.Node;

import org.l2jmobius.commons.util.IXmlReader;
import org.l2jmobius.gameserver.model.Location;
import org.l2jmobius.gameserver.model.StatSet;
import org.l2jmobius.gameserver.model.eventmap.EventBarrier;
import org.l2jmobius.gameserver.model.eventmap.EventMap;
import org.l2jmobius.gameserver.model.eventmap.EventMapType;
import org.l2jmobius.gameserver.model.mapdevice.MapArena;
import org.l2jmobius.gameserver.model.mapdevice.MapModifier;
import org.l2jmobius.gameserver.model.mapdevice.MapTier;
import org.l2jmobius.gameserver.model.mapdevice.UniqueMap;
import org.l2jmobius.gameserver.model.mapdevice.MonsterStrength;

/**
 * Loads data/MapDevice.xml: the hideout (where its owner arrives and where the Map Device stands), the map tiers (their item, their monsters and which monsters drop them), the difficulty bonus that lets stronger monsters drop higher tiers, and the arenas a map can be opened in.
 * @author Mobius
 */
public class MapDeviceData implements IXmlReader
{
	private static final Logger LOGGER = Logger.getLogger(MapDeviceData.class.getName());

	private static final String FILE = "data/MapDevice.xml";

	private volatile Location _hideoutEntry = new Location(-23560, -8963, -5384, 32768);
	private volatile Location _devicePosition = new Location(-23770, -8975, -5384, 0);
	private volatile Map<Integer, MapTier> _tiers = Collections.emptyMap();
	private volatile Map<Integer, MapTier> _tiersByItem = Collections.emptyMap();
	private volatile List<MapArena> _arenas = Collections.emptyList();
	private volatile Map<UniqueMap.Type, UniqueMap> _uniqueMaps = Collections.emptyMap();
	private volatile Map<Integer, UniqueMap> _uniqueMapsByItem = Collections.emptyMap();
	private volatile Set<Integer> _excludedNpcIds = Collections.emptySet();
	private volatile int _difficultyChampion1 = 0;
	private volatile int _difficultyChampion2 = 1;
	private volatile int _difficultyChampion3 = 2;
	private volatile int _difficultySpecial = 1;
	private volatile int _difficultyRaid = 2;
	private volatile double[] _strengthWeights = defaultStrengthWeights();

	// Filled while a file is parsed, then put in place at once.
	private Location _newHideoutEntry;
	private Location _newDevicePosition;
	private Map<Integer, MapTier> _newTiers;
	private List<MapArena> _newArenas;
	private Map<UniqueMap.Type, UniqueMap> _newUniqueMaps;
	private Set<Integer> _newExcluded;
	private double[] _newStrengthWeights;

	protected MapDeviceData()
	{
		load();
	}

	@Override
	public synchronized void load()
	{
		_newHideoutEntry = _hideoutEntry;
		_newDevicePosition = _devicePosition;
		_newTiers = new TreeMap<>();
		_newArenas = new ArrayList<>();
		_newUniqueMaps = new java.util.EnumMap<>(UniqueMap.Type.class);
		_newExcluded = new HashSet<>();
		_newStrengthWeights = defaultStrengthWeights();
		parseDatapackFile(FILE);

		final Map<Integer, MapTier> byItem = new TreeMap<>();
		for (MapTier tier : _newTiers.values())
		{
			byItem.put(tier.itemId(), tier);
		}

		_hideoutEntry = _newHideoutEntry;
		_devicePosition = _newDevicePosition;
		_tiers = Collections.unmodifiableMap(_newTiers);
		_tiersByItem = Collections.unmodifiableMap(byItem);
		_arenas = List.copyOf(_newArenas);
		final Map<Integer, UniqueMap> uniqueByItem = new TreeMap<>();
		for (UniqueMap unique : _newUniqueMaps.values())
		{
			uniqueByItem.put(unique.itemId(), unique);
		}
		_uniqueMaps = Collections.unmodifiableMap(_newUniqueMaps);
		_uniqueMapsByItem = Collections.unmodifiableMap(uniqueByItem);
		_excludedNpcIds = Set.copyOf(_newExcluded);
		_strengthWeights = _newStrengthWeights;
		MonsterStrength.clear();
		LOGGER.info(getClass().getSimpleName() + ": Loaded " + _tiers.size() + " map tiers, " + _arenas.size() + " arenas and " + _uniqueMaps.size() + " unique maps.");
	}

	@Override
	public boolean isValidating()
	{
		return false;
	}

	@Override
	public void parseDocument(Document document, File file)
	{
		forEach(document, "list", listNode ->
		{
			forEach(listNode, "hideout", hideoutNode ->
			{
				final StatSet set = new StatSet(parseAttributes(hideoutNode));
				_newHideoutEntry = new Location(set.getInt("x"), set.getInt("y"), set.getInt("z"), set.getInt("heading", 0));
				forEach(hideoutNode, "device", deviceNode ->
				{
					final StatSet device = new StatSet(parseAttributes(deviceNode));
					_newDevicePosition = new Location(device.getInt("x"), device.getInt("y"), device.getInt("z"), device.getInt("heading", 0));
				});
			});
			forEach(listNode, "difficulty", difficultyNode ->
			{
				final StatSet set = new StatSet(parseAttributes(difficultyNode));
				_difficultyChampion1 = set.getInt("champion1", 0);
				_difficultyChampion2 = set.getInt("champion2", 1);
				_difficultyChampion3 = set.getInt("champion3", 2);
				_difficultySpecial = set.getInt("special", 1);
				_difficultyRaid = set.getInt("raid", 2);
			});
			forEach(listNode, "tier", tierNode ->
			{
				final StatSet set = new StatSet(parseAttributes(tierNode));
				final int tier = set.getInt("tier");
				final int minLevel = set.getInt("minLevel");
				final int maxLevel = Math.max(minLevel, set.getInt("maxLevel", minLevel));
				final double minScore = Math.max(0, set.getDouble("minScore", 0));
				double maxScore = Math.max(0, set.getDouble("maxScore", 0));
				if ((maxScore > 0) && (maxScore < minScore))
				{
					LOGGER.warning(getClass().getSimpleName() + ": Tier " + tier + " has maxScore " + maxScore + " below its minScore " + minScore + "; it takes no upper limit.");
					maxScore = 0;
				}
				_newTiers.put(tier, new MapTier(tier, set.getInt("itemId"), minLevel, maxLevel, minScore, maxScore, set.getInt("dropLevel", minLevel), set.getDouble("hp", 1.0), set.getDouble("attack", 1.0), set.getDouble("defence", 1.0), set.getDouble("reward", 1.0)));
			});
			forEach(listNode, "strength", strengthNode ->
			{
				final StatSet set = new StatSet(parseAttributes(strengthNode));
				for (MonsterStrength.Stat stat : MonsterStrength.Stat.values())
				{
					_newStrengthWeights[stat.ordinal()] = Math.max(0, set.getDouble(stat.getXmlName(), stat.getDefaultWeight()));
				}
			});
			forEach(listNode, "arena", arenaNode ->
			{
				final StatSet set = new StatSet(parseAttributes(arenaNode));
				final int radiusX = Math.max(EventBarrier.MIN_RADIUS, set.getInt("radiusX"));
				final int radiusY = Math.max(EventBarrier.MIN_RADIUS, set.getInt("radiusY", radiusX));
				_newArenas.add(MapArena.of(set.getString("name"), set.getInt("x"), set.getInt("y"), set.getInt("z"), radiusX, radiusY, set.getInt("entryAngle", 0), set.getInt("minTier", 0), set.getInt("maxTier", 0)));
			});
			forEach(listNode, "uniqueMap", uniqueNode -> parseUniqueMap(uniqueNode));
			forEach(listNode, "excludedNpcs", excludedNode ->
			{
				for (String id : excludedNode.getTextContent().split("[,\\s]+"))
				{
					if (!id.isEmpty())
					{
						try
						{
							_newExcluded.add(Integer.parseInt(id));
						}
						catch (NumberFormatException e)
						{
							LOGGER.warning(getClass().getSimpleName() + ": Invalid excluded npc id " + id + ".");
						}
					}
				}
			});
		});
	}

	/**
	 * @return where the owner of a hideout (and their visitors) arrive in it
	 */
	private void parseUniqueMap(Node uniqueNode)
	{
		final StatSet set = new StatSet(parseAttributes(uniqueNode));
		final UniqueMap.Type type;
		try
		{
			type = UniqueMap.Type.valueOf(set.getString("type"));
		}
		catch (Exception e)
		{
			LOGGER.warning(getClass().getSimpleName() + ": Unknown unique map type " + set.getString("type", "") + ": it is left out.");
			return;
		}

		final List<MapModifier> modifiers = new ArrayList<>();
		for (String name : set.getString("modifiers", "").split("[,\\s]+"))
		{
			if (name.isEmpty())
			{
				continue;
			}
			try
			{
				final MapModifier modifier = MapModifier.valueOf(name);
				if (!modifiers.contains(modifier))
				{
					modifiers.add(modifier);
				}
			}
			catch (IllegalArgumentException e)
			{
				LOGGER.warning(getClass().getSimpleName() + ": The unique map " + type + " names an unknown modifier " + name + ": it is left out.");
			}
		}
		modifiers.sort(null);

		final String[] texts = new String[3];
		final List<UniqueMap.Drop> drops = new ArrayList<>();
		final List<UniqueMap.Drop> bossDrops = new ArrayList<>();
		final List<UniqueMap.Drop> specialDrops = new ArrayList<>();
		for (Node child = uniqueNode.getFirstChild(); child != null; child = child.getNextSibling())
		{
			switch (child.getNodeName())
			{
				case "description" -> texts[0] = child.getTextContent().trim().replaceAll("\\s+", " ");
				case "twist" -> texts[1] = child.getTextContent().trim().replaceAll("\\s+", " ");
				case "finale" -> texts[2] = child.getTextContent().trim().replaceAll("\\s+", " ");
				case "drop" ->
				{
					final StatSet drop = new StatSet(parseAttributes(child));
					final long min = Math.max(1, drop.getLong("min", 1));
					final UniqueMap.Drop entry = new UniqueMap.Drop(drop.getInt("id"), min, Math.max(min, drop.getLong("max", min)), Math.max(0, Math.min(100, drop.getDouble("chance", 100))));
					switch (drop.getString("group", "map"))
					{
						case "boss" -> bossDrops.add(entry);
						case "special" -> specialDrops.add(entry);
						default -> drops.add(entry);
					}
				}
				default ->
				{
				}
			}
		}

		final String arena = set.getString("arena", "");
		_newUniqueMaps.put(type, new UniqueMap(type, set.getString("name", type.name()), set.getInt("itemId"), set.getInt("tier", 1), Math.max(1, set.getInt("monsters", 50)), arena.isEmpty() ? null : arena, List.copyOf(modifiers), Math.max(0, set.getDouble("reward", 1.0)), set.getInt("boss"), set.getString("bossName", "Boss"), Math.max(0.01, set.getDouble("bossHp", 1.0)), Math.max(0.01, set.getDouble("bossAttack", 1.0)), Math.max(0.01, set.getDouble("bossDefence", 1.0)), texts[0] == null ? "" : texts[0], texts[1] == null ? "" : texts[1], texts[2] == null ? "" : texts[2], List.copyOf(drops), List.copyOf(bossDrops), List.copyOf(specialDrops)));
	}

	/**
	 * @return the unique maps, by type
	 */
	public Map<UniqueMap.Type, UniqueMap> getUniqueMaps()
	{
		return _uniqueMaps;
	}

	/**
	 * @param type a unique map type
	 * @return its unique map, {@code null} if the XML has none
	 */
	public UniqueMap getUniqueMap(UniqueMap.Type type)
	{
		return _uniqueMaps.get(type);
	}

	/**
	 * @param itemId an item id
	 * @return the unique map that item opens, {@code null} for none
	 */
	public UniqueMap getUniqueMapByItem(int itemId)
	{
		return _uniqueMapsByItem.get(itemId);
	}

	public Location getHideoutEntry()
	{
		return _hideoutEntry;
	}

	/**
	 * @return where the Map Device stands in a hideout; its portals stand in a ring round it
	 */
	public Location getDevicePosition()
	{
		return _devicePosition;
	}

	/**
	 * @return every tier, lowest first
	 */
	public Map<Integer, MapTier> getTiers()
	{
		return _tiers;
	}

	/**
	 * @param tier a tier
	 * @return that tier, {@code null} for none
	 */
	public MapTier getTier(int tier)
	{
		return _tiers.get(tier);
	}

	/**
	 * @param itemId an item
	 * @return the tier that item holds, {@code null} if it is no map
	 */
	public MapTier getTierByItem(int itemId)
	{
		return _tiersByItem.get(itemId);
	}

	/**
	 * @return the highest tier, 0 if there is none
	 */
	public int getMaxTier()
	{
		int max = 0;
		for (int tier : _tiers.keySet())
		{
			max = Math.max(max, tier);
		}
		return max;
	}

	/**
	 * @param level a monster's level
	 * @param score the monster's strength score ({@link MonsterStrength})
	 * @return the highest tier whose drop level and lowest score the monster reaches, 0 if it drops none
	 */
	public int getDropTier(int level, double score)
	{
		int best = 0;
		for (MapTier tier : _tiers.values())
		{
			if ((level >= tier.dropLevel()) && (score >= tier.minScore()) && (tier.tier() > best))
			{
				best = tier.tier();
			}
		}
		return best;
	}

	/**
	 * @return the places of data/MapDevice.xml a map can be opened in (see {@link #getArenas(int)} for those of the event map page too)
	 */
	public List<MapArena> getArenas()
	{
		return _arenas;
	}
	
	/**
	 * @return the places of the event map page (data/maps) a map can be opened in: ticked for the Map Device, with a fence and an entry
	 */
	public List<MapArena> getEditorArenas()
	{
		final List<MapArena> arenas = new ArrayList<>();
		for (EventMap map : EventMapData.getInstance().getPlayableMaps(EventMapType.MAPDEVICE))
		{
			final MapArena arena = MapArena.of(map);
			if (arena != null)
			{
				arenas.add(arena);
			}
		}
		return arenas;
	}
	
	/**
	 * @param tier a map tier
	 * @return every place a map of that tier can open in: the arenas of data/MapDevice.xml and the maps of the event map page that take the tier
	 */
	public List<MapArena> getArenas(int tier)
	{
		final List<MapArena> arenas = new ArrayList<>();
		for (MapArena arena : _arenas)
		{
			if (arena.allowsTier(tier))
			{
				arenas.add(arena);
			}
		}
		for (MapArena arena : getEditorArenas())
		{
			if (arena.allowsTier(tier))
			{
				arenas.add(arena);
			}
		}
		return arenas;
	}

	/**
	 * @return how much each stat counts in a monster's strength score, in {@link MonsterStrength.Stat} order (the {@code <strength>} element)
	 */
	public double[] getStrengthWeights()
	{
		return _strengthWeights;
	}

	private static double[] defaultStrengthWeights()
	{
		final double[] weights = new double[MonsterStrength.Stat.values().length];
		for (MonsterStrength.Stat stat : MonsterStrength.Stat.values())
		{
			weights[stat.ordinal()] = stat.getDefaultWeight();
		}
		return weights;
	}

	/**
	 * @return monsters that never fill a map
	 */
	public Set<Integer> getExcludedNpcIds()
	{
		return _excludedNpcIds;
	}

	/**
	 * @param championTier a champion's tier (1-3)
	 * @return how many tiers higher a map it drops
	 */
	public int getChampionDifficulty(int championTier)
	{
		return switch (championTier)
		{
			case 1 -> _difficultyChampion1;
			case 2 -> _difficultyChampion2;
			case 3 -> _difficultyChampion3;
			default -> 0;
		};
	}

	/**
	 * @return how many tiers higher a map a special monster (Mage, Thief, Infused, Resonant, wave challenge) drops
	 */
	public int getSpecialDifficulty()
	{
		return _difficultySpecial;
	}

	/**
	 * @return how many tiers higher a map a raid boss drops
	 */
	public int getRaidDifficulty()
	{
		return _difficultyRaid;
	}

	public static MapDeviceData getInstance()
	{
		return SingletonHolder.INSTANCE;
	}

	private static class SingletonHolder
	{
		protected static final MapDeviceData INSTANCE = new MapDeviceData();
	}
}
