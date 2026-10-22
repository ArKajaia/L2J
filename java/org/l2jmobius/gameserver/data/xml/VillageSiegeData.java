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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.w3c.dom.Document;
import org.w3c.dom.NamedNodeMap;
import org.w3c.dom.Node;

import org.l2jmobius.commons.util.IXmlReader;
import org.l2jmobius.gameserver.model.Location;
import org.l2jmobius.gameserver.model.villagesiege.SiegeVillage;
import org.l2jmobius.gameserver.model.villagesiege.SiegeVillage.FixedEntrance;

/**
 * The villages of Village Under Siege, from {@code data/VillageSiege.xml}.
 */
public class VillageSiegeData implements IXmlReader
{
	private final Map<String, SiegeVillage> _villages = new LinkedHashMap<>();

	protected VillageSiegeData()
	{
		load();
	}

	@Override
	public void load()
	{
		_villages.clear();
		parseDatapackFile("data/VillageSiege.xml");
		LOGGER.info(getClass().getSimpleName() + ": Loaded " + _villages.size() + " villages.");
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
				if (!"village".equalsIgnoreCase(node.getNodeName()))
				{
					continue;
				}

				final NamedNodeMap attrs = node.getAttributes();
				final String id = parseString(attrs, "id").toLowerCase();
				final List<FixedEntrance> entrances = new ArrayList<>();
				for (Node child = node.getFirstChild(); child != null; child = child.getNextSibling())
				{
					if ("entrance".equalsIgnoreCase(child.getNodeName()))
					{
						final NamedNodeMap entrance = child.getAttributes();
						final Location rally = entrance.getNamedItem("rallyX") != null ? new Location(parseInteger(entrance, "rallyX"), parseInteger(entrance, "rallyY"), parseInteger(entrance, "rallyZ")) : null;
						entrances.add(new FixedEntrance(new Location(parseInteger(entrance, "x"), parseInteger(entrance, "y"), parseInteger(entrance, "z")), rally));
					}
				}
				_villages.put(id, new SiegeVillage(id, parseString(attrs, "name"), parseString(attrs, "town"), parseInteger(attrs, "gatekeeper"), parseInteger(attrs, "guardLook"), parseInteger(attrs, "minLevel", 0), parseInteger(attrs, "maxLevel", 0), entrances));
			}
		}
	}

	/**
	 * @param id a village key, any case
	 * @return the village, {@code null} if there is none
	 */
	public SiegeVillage getVillage(String id)
	{
		return id == null ? null : _villages.get(id.toLowerCase());
	}

	/**
	 * @return every village, in file order
	 */
	public Collection<SiegeVillage> getVillages()
	{
		return Collections.unmodifiableCollection(_villages.values());
	}

	public static VillageSiegeData getInstance()
	{
		return SingletonHolder.INSTANCE;
	}

	private static class SingletonHolder
	{
		protected static final VillageSiegeData INSTANCE = new VillageSiegeData();
	}
}
