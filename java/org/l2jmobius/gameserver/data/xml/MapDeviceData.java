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
import org.l2jmobius.gameserver.model.mapdevice.MapArena;
import org.l2jmobius.gameserver.model.mapdevice.MapTier;

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
	private volatile Set<Integer> _excludedNpcIds = Collections.emptySet();
	private volatile int _difficultyChampion1 = 0;
	private volatile int _difficultyChampion2 = 1;
	private volatile int _difficultyChampion3 = 2;
	private volatile int _difficultySpecial = 1;
	private volatile int _difficultyRaid = 2;

	// Filled while a file is parsed, then put in place at once.
	private Location _newHideoutEntry;
	private Location _newDevicePosition;
	private Map<Integer, MapTier> _newTiers;
	private List<MapArena> _newArenas;
	private Set<Integer> _newExcluded;

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
		_newExcluded = new HashSet<>();
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
		_excludedNpcIds = Set.copyOf(_newExcluded);
		LOGGER.info(getClass().getSimpleName() + ": Loaded " + _tiers.size() + " map tiers and " + _arenas.size() + " arenas.");
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
				_newTiers.put(tier, new MapTier(tier, set.getInt("itemId"), minLevel, maxLevel, set.getInt("dropLevel", minLevel), set.getDouble("hp", 1.0), set.getDouble("attack", 1.0), set.getDouble("defence", 1.0), set.getDouble("reward", 1.0)));
			});
			forEach(listNode, "arena", arenaNode ->
			{
				final StatSet set = new StatSet(parseAttributes(arenaNode));
				final int radiusX = Math.max(EventBarrier.MIN_RADIUS, set.getInt("radiusX"));
				final int radiusY = Math.max(EventBarrier.MIN_RADIUS, set.getInt("radiusY", radiusX));
				_newArenas.add(new MapArena(set.getString("name"), set.getInt("x"), set.getInt("y"), set.getInt("z"), radiusX, radiusY, set.getInt("entryAngle", 0)));
			});
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
	 * @return the highest tier whose drop level the monster reaches, 0 if it drops none
	 */
	public int getDropTier(int level)
	{
		int best = 0;
		for (MapTier tier : _tiers.values())
		{
			if ((level >= tier.dropLevel()) && (tier.tier() > best))
			{
				best = tier.tier();
			}
		}
		return best;
	}

	/**
	 * @return the places a map can be opened in
	 */
	public List<MapArena> getArenas()
	{
		return _arenas;
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
