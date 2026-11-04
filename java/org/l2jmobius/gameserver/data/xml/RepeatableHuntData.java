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
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

import org.w3c.dom.Document;
import org.w3c.dom.NamedNodeMap;
import org.w3c.dom.Node;

import org.l2jmobius.commons.util.IXmlReader;
import org.l2jmobius.gameserver.model.repeatablehunt.RepeatableHunt;

/**
 * Loads the repeatable hunts from {@code data/RepeatableHunts.xml}.
 * @author Mobius
 */
public class RepeatableHuntData implements IXmlReader
{
	private static final Logger LOGGER = Logger.getLogger(RepeatableHuntData.class.getName());

	private volatile Map<Integer, RepeatableHunt> _hunts = Collections.emptyMap();
	private volatile Map<Integer, List<RepeatableHunt>> _byMonster = Collections.emptyMap();
	private boolean _enabled = true;
	private int _giverNpcId = 900530;
	private int _courierNpcId = 900531;
	private int _courierKills = 12;
	private int _courierStay = 120;
	private int _courierCooldown = 7200;
	private int _declineCooldown = 21600;
	private int _countLevelGap = 3;

	private Map<Integer, RepeatableHunt> _loading;

	protected RepeatableHuntData()
	{
		load();
	}

	@Override
	public synchronized void load()
	{
		_loading = new LinkedHashMap<>();
		parseDatapackFile("data/RepeatableHunts.xml");
		final Map<Integer, List<RepeatableHunt>> byMonster = new HashMap<>();
		for (RepeatableHunt hunt : _loading.values())
		{
			for (int npcId : hunt.getMonsterIds())
			{
				byMonster.computeIfAbsent(npcId, key -> new ArrayList<>()).add(hunt);
			}
		}
		_hunts = Collections.unmodifiableMap(_loading);
		_byMonster = Collections.unmodifiableMap(byMonster);
		_loading = null;
		LOGGER.info(getClass().getSimpleName() + ": Loaded " + _hunts.size() + " repeatable hunts" + (_enabled ? "." : " (disabled)."));
	}

	@Override
	public void parseDocument(Document document, File file)
	{
		for (Node list = document.getFirstChild(); list != null; list = list.getNextSibling())
		{
			if (!"list".equalsIgnoreCase(list.getNodeName()))
			{
				continue;
			}
			for (Node node = list.getFirstChild(); node != null; node = node.getNextSibling())
			{
				final NamedNodeMap attrs = node.getAttributes();
				switch (node.getNodeName())
				{
					case "settings":
					{
						_enabled = parseBoolean(attrs, "enabled", true);
						_giverNpcId = parseInteger(attrs, "giverNpc", 900530);
						_courierNpcId = parseInteger(attrs, "courierNpc", 900531);
						_courierKills = parseInteger(attrs, "courierKills", 12);
						_courierStay = parseInteger(attrs, "courierStay", 120);
						_courierCooldown = parseInteger(attrs, "courierCooldown", 7200);
						_declineCooldown = parseInteger(attrs, "declineCooldown", 21600);
						_countLevelGap = parseInteger(attrs, "countLevelGap", 3);
						break;
					}
					case "hunt":
					{
						try
						{
							final RepeatableHunt hunt = parseHunt(node);
							if (_loading.putIfAbsent(hunt.getId(), hunt) != null)
							{
								LOGGER.warning(getClass().getSimpleName() + ": Duplicate hunt id " + hunt.getId() + ".");
							}
						}
						catch (Exception e)
						{
							LOGGER.warning(getClass().getSimpleName() + ": Could not load a hunt: " + e.getMessage());
						}
						break;
					}
				}
			}
		}
	}

	private RepeatableHunt parseHunt(Node node)
	{
		final NamedNodeMap attrs = node.getAttributes();
		final Map<Integer, Double> monsters = new LinkedHashMap<>();
		final Map<String, String> texts = new HashMap<>();
		for (Node child = node.getFirstChild(); child != null; child = child.getNextSibling())
		{
			final NamedNodeMap childAttrs = child.getAttributes();
			switch (child.getNodeName())
			{
				case "monster":
				{
					monsters.put(parseInteger(childAttrs, "id"), parseDouble(childAttrs, "chance", 100d));
					break;
				}
				case "text":
				{
					texts.put(parseString(childAttrs, "key"), child.getTextContent().trim().replaceAll("\\s+", " "));
					break;
				}
			}
		}
		final int id = parseInteger(attrs, "id");
		if (monsters.isEmpty())
		{
			throw new IllegalArgumentException("hunt " + id + " has no monsters");
		}
		final RepeatableHunt.Type type = parseEnum(attrs, RepeatableHunt.Type.class, "type");
		final int itemId = parseInteger(attrs, "item", 0);
		if ((type == RepeatableHunt.Type.COLLECT) && (itemId <= 0))
		{
			throw new IllegalArgumentException("hunt " + id + " collects no item");
		}
		return new RepeatableHunt(id, parseString(attrs, "name"), parseString(attrs, "place"), parseString(attrs, "town"), parseInteger(attrs, "anchor"), parseString(attrs, "giverName"), parseString(attrs, "giverTitle", ""), type, itemId, parseString(attrs, "unit", type == RepeatableHunt.Type.KILL ? "kill" : "item"), parseInteger(attrs, "minLevel"), parseInteger(attrs, "maxLevel"), parseLong(attrs, "perUnit"), parseLong(attrs, "bonus", 0L), parseInteger(attrs, "bonusMin", 0), monsters, texts);
	}

	public boolean isEnabled()
	{
		return _enabled;
	}

	public Collection<RepeatableHunt> getHunts()
	{
		return _hunts.values();
	}

	public RepeatableHunt getHunt(int id)
	{
		return _hunts.get(id);
	}

	/**
	 * @param npcId a monster
	 * @return the hunts it counts for, empty if none
	 */
	public List<RepeatableHunt> getHuntsOf(int npcId)
	{
		return _byMonster.getOrDefault(npcId, Collections.emptyList());
	}

	public int getGiverNpcId()
	{
		return _giverNpcId;
	}

	public int getCourierNpcId()
	{
		return _courierNpcId;
	}

	/**
	 * @return kills of a hunt's monsters, without the hunt, after which a courier comes with it
	 */
	public int getCourierKills()
	{
		return _courierKills;
	}

	/**
	 * @return seconds a courier waits
	 */
	public int getCourierStay()
	{
		return _courierStay;
	}

	/**
	 * @return seconds before a courier brings the same hunt again
	 */
	public int getCourierCooldown()
	{
		return _courierCooldown;
	}

	/**
	 * @return seconds before a courier brings a hunt the player turned down again
	 */
	public int getDeclineCooldown()
	{
		return _declineCooldown;
	}

	/**
	 * @return levels above a hunt's level range at which kills still count
	 */
	public int getCountLevelGap()
	{
		return _countLevelGap;
	}

	public static RepeatableHuntData getInstance()
	{
		return SingletonHolder.INSTANCE;
	}

	private static class SingletonHolder
	{
		protected static final RepeatableHuntData INSTANCE = new RepeatableHuntData();
	}
}
