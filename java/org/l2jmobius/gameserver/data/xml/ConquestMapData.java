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
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.regex.Pattern;

import org.w3c.dom.Document;
import org.w3c.dom.NamedNodeMap;
import org.w3c.dom.Node;

import org.l2jmobius.commons.util.IXmlReader;
import org.l2jmobius.commons.util.Rnd;
import org.l2jmobius.gameserver.model.conquest.ConquestDoor;
import org.l2jmobius.gameserver.model.conquest.ConquestMap;
import org.l2jmobius.gameserver.model.conquest.ConquestPoint;
import org.l2jmobius.gameserver.model.conquest.ConquestPointType;

/**
 * The battlefields of the Conquest event, one file each in {@code data/conquest}. GMs edit them on the Conquest map page (//conquest), which saves them here; they can also be edited by hand and loaded again with //conquest reload.
 */
public class ConquestMapData implements IXmlReader
{
	private static final String DIRECTORY = "data/conquest";
	/** A map's id is its file name. */
	private static final Pattern ID_PATTERN = Pattern.compile("[a-z0-9_]{1,40}");
	/** At most so many points of one type on a map. */
	public static final int MAX_POINTS = 50;
	/** How many players a team takes on a map that doesn't say (maxPlayers), and the bounds of what a map may say. */
	public static final int DEFAULT_MAX_PLAYERS = 250;
	public static final int MIN_PLAYERS = 1;
	public static final int MAX_PLAYERS = 1000;

	private volatile Map<String, ConquestMap> _maps = new TreeMap<>();
	/** The maps being read by {@link #load}: the files may be read at once by several threads (ThreadsForLoading). */
	private Map<String, ConquestMap> _loading;

	protected ConquestMapData()
	{
		load();
	}

	@Override
	public synchronized void load()
	{
		_loading = new ConcurrentSkipListMap<>();
		parseDatapackDirectory(DIRECTORY, false);
		_maps = new TreeMap<>(_loading);
		_loading = null;
		LOGGER.info(getClass().getSimpleName() + ": Loaded " + _maps.size() + " Conquest maps (" + getEnabledMaps().size() + " enabled).");
	}

	@Override
	public boolean isValidating()
	{
		// The maps have no schema: the page that writes them checks them.
		return false;
	}

	@Override
	public void parseDocument(Document document, File file)
	{
		for (Node node = document.getFirstChild(); node != null; node = node.getNextSibling())
		{
			if (!"map".equalsIgnoreCase(node.getNodeName()))
			{
				continue;
			}

			final NamedNodeMap attrs = node.getAttributes();
			final String fileId = file.getName().replaceFirst("\\.xml$", "").toLowerCase();
			final String id = parseString(attrs, "id", fileId).toLowerCase();
			if (!id.equals(fileId))
			{
				LOGGER.warning(getClass().getSimpleName() + ": " + file.getName() + " names its map " + id + ", it goes by its file name " + fileId + ".");
			}

			String description = "";
			int minX = 0;
			int minY = 0;
			int maxX = 0;
			int maxY = 0;
			Integer z = null;
			final List<ConquestPoint> points = new ArrayList<>();
			final List<ConquestDoor> doors = new ArrayList<>();
			for (Node child = node.getFirstChild(); child != null; child = child.getNextSibling())
			{
				final NamedNodeMap childAttrs = child.getAttributes();
				switch (child.getNodeName().toLowerCase())
				{
					case "description":
					{
						description = child.getTextContent().trim();
						break;
					}
					case "area":
					{
						minX = parseInteger(childAttrs, "minX");
						minY = parseInteger(childAttrs, "minY");
						maxX = parseInteger(childAttrs, "maxX");
						maxY = parseInteger(childAttrs, "maxY");
						z = parseInteger(childAttrs, "z", null);
						break;
					}
					case "team":
					{
						final ConquestPointType type = ConquestPointType.fromKey(parseString(childAttrs, "id", ""));
						if ((type == null) || (type == ConquestPointType.CAPTURE))
						{
							LOGGER.warning(getClass().getSimpleName() + ": " + file.getName() + " has a team that is neither blue nor red.");
							break;
						}
						for (Node spawn = child.getFirstChild(); spawn != null; spawn = spawn.getNextSibling())
						{
							if ("spawn".equalsIgnoreCase(spawn.getNodeName()))
							{
								points.add(readPoint(type, spawn.getAttributes()));
							}
						}
						break;
					}
					case "capture":
					{
						points.add(readPoint(ConquestPointType.CAPTURE, childAttrs));
						break;
					}
					case "door":
					{
						doors.add(new ConquestDoor(parseInteger(childAttrs, "id"), parseBoolean(childAttrs, "open", true)));
						break;
					}
				}
			}

			if ((maxX <= minX) || (maxY <= minY))
			{
				LOGGER.warning(getClass().getSimpleName() + ": " + file.getName() + " has no area (minX, minY, maxX, maxY), it is left out.");
				continue;
			}
			_loading.put(fileId, new ConquestMap(fileId, parseString(attrs, "name", fileId), parseBoolean(attrs, "enabled", true), Math.max(MIN_PLAYERS, Math.min(MAX_PLAYERS, parseInteger(attrs, "maxPlayers", DEFAULT_MAX_PLAYERS))), description, minX, minY, maxX, maxY, z != null ? z : averageZ(points), points, doors));
		}
	}

	private ConquestPoint readPoint(ConquestPointType type, NamedNodeMap attrs)
	{
		return new ConquestPoint(type, parseString(attrs, "name", "").trim(), parseInteger(attrs, "x"), parseInteger(attrs, "y"), parseInteger(attrs, "z"), parseInteger(attrs, "heading", 0), parseInteger(attrs, "radius", type.getDefaultRadius()));
	}

	/**
	 * @param points some points
	 * @return their average height, 0 without any
	 */
	public static int averageZ(List<ConquestPoint> points)
	{
		if (points.isEmpty())
		{
			return 0;
		}
		long sum = 0;
		for (ConquestPoint point : points)
		{
			sum += point.z();
		}
		return (int) (sum / points.size());
	}

	/**
	 * @param id a map's id
	 * @return {@code true} if it can be a file name
	 */
	public static boolean isValidId(String id)
	{
		return (id != null) && ID_PATTERN.matcher(id).matches();
	}

	/**
	 * Writes a map to its file (data/conquest/&lt;id&gt;.xml), replacing what was there, and makes it the live one.
	 * @param map the map
	 * @throws IOException if it can't be written
	 */
	public synchronized void save(ConquestMap map) throws IOException
	{
		if (!isValidId(map.id()))
		{
			throw new IllegalArgumentException("the map id must be 1-40 lower case letters, digits or _");
		}

		final Path directory = Path.of(DIRECTORY);
		Files.createDirectories(directory);
		final Path file = directory.resolve(map.id() + ".xml");
		final Path temp = directory.resolve(map.id() + ".xml.tmp");
		Files.writeString(temp, toXml(map), StandardCharsets.UTF_8);
		Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);

		final Map<String, ConquestMap> maps = new TreeMap<>(_maps);
		maps.put(map.id(), map);
		_maps = maps;
	}

	/**
	 * Writes a map, as its file in data/conquest holds it, to another file too (the GM page's "also save to"): a copy only, the live map stays the one in data/conquest.
	 * @param map the map
	 * @param file the file: an .xml file in a folder that exists
	 * @return where it was written
	 * @throws IOException if it can't be written
	 */
	public Path writeCopy(ConquestMap map, Path file) throws IOException
	{
		final Path target = file.toAbsolutePath().normalize();
		if (!target.getFileName().toString().toLowerCase().endsWith(".xml"))
		{
			throw new IOException("the file must end in .xml");
		}
		if ((target.getParent() == null) || !Files.isDirectory(target.getParent()))
		{
			throw new IOException("the folder " + target.getParent() + " does not exist");
		}
		Files.writeString(target, toXml(map), StandardCharsets.UTF_8);
		return target;
	}

	/**
	 * Takes a map out: its file is renamed to &lt;id&gt;.xml.deleted, so it can still be brought back by hand.
	 * @param id the map's id
	 * @return {@code true} if there was such a map
	 * @throws IOException if the file can't be renamed
	 */
	public synchronized boolean delete(String id) throws IOException
	{
		if (!_maps.containsKey(id))
		{
			return false;
		}

		final Path directory = Path.of(DIRECTORY);
		final Path file = directory.resolve(id + ".xml");
		if (Files.exists(file))
		{
			Files.move(file, directory.resolve(id + ".xml.deleted"), StandardCopyOption.REPLACE_EXISTING);
		}
		final Map<String, ConquestMap> maps = new TreeMap<>(_maps);
		maps.remove(id);
		_maps = maps;
		return true;
	}

	private static String toXml(ConquestMap map)
	{
		final StringBuilder sb = new StringBuilder(4096);
		sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
		sb.append("<!--\n");
		sb.append("\tA battlefield of the Conquest event (ConquestMapData). Written by the Conquest map page (//conquest): edits by hand are kept, comments are not.\n");
		sb.append("\tmap      id (the file name), name (shown to the players), enabled (the event picks one of the enabled maps by chance), maxPlayers (per team)\n");
		sb.append("\tarea     the battlefield, minX/minY (north-west corner) to maxX/maxY (south-east corner); z: the ground's height, where it has several layers\n");
		sb.append("\tteam     blue or red, with its spawn points: x, y, z, heading, radius (how far around the point a wave is spread)\n");
		sb.append("\tcapture  a capture point: name (A, B...), x, y, z, radius (how close a player must stand to capture it)\n");
		sb.append("\tdoor     a door of data/Doors.xml the event opens (open=\"true\") or closes (open=\"false\") when it starts; doors not named are left as they are\n");
		sb.append("-->\n");
		sb.append("<map id=\"").append(map.id()).append("\" name=\"").append(escape(map.name())).append("\" enabled=\"").append(map.enabled()).append("\" maxPlayers=\"").append(map.maxPlayers()).append("\">\n");
		if (!map.description().isEmpty())
		{
			sb.append("\t<description>").append(escape(map.description())).append("</description>\n");
		}
		sb.append("\t<area minX=\"").append(map.minX()).append("\" minY=\"").append(map.minY()).append("\" maxX=\"").append(map.maxX()).append("\" maxY=\"").append(map.maxY()).append("\" z=\"").append(map.z()).append("\" />\n");
		for (ConquestPointType team : new ConquestPointType[]
		{
			ConquestPointType.BLUE,
			ConquestPointType.RED
		})
		{
			sb.append("\t<team id=\"").append(team.getKey()).append("\">\n");
			for (ConquestPoint point : map.getPoints(team))
			{
				sb.append("\t\t<spawn");
				appendPoint(sb, point);
				sb.append(" />\n");
			}
			sb.append("\t</team>\n");
		}
		for (ConquestPoint point : map.getPoints(ConquestPointType.CAPTURE))
		{
			sb.append("\t<capture name=\"").append(escape(point.name())).append('"');
			appendPoint(sb, point);
			sb.append(" />\n");
		}
		for (ConquestDoor door : map.doors())
		{
			sb.append("\t<door id=\"").append(door.id()).append("\" open=\"").append(door.open()).append("\" />\n");
		}
		sb.append("</map>\n");
		return sb.toString();
	}

	private static void appendPoint(StringBuilder sb, ConquestPoint point)
	{
		sb.append(" x=\"").append(point.x()).append("\" y=\"").append(point.y()).append("\" z=\"").append(point.z()).append('"');
		if (point.type() != ConquestPointType.CAPTURE)
		{
			sb.append(" heading=\"").append(point.heading()).append('"');
		}
		sb.append(" radius=\"").append(point.radius()).append('"');
	}

	private static String escape(String text)
	{
		return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
	}

	/**
	 * @param id a map's id
	 * @return the map, {@code null} if there is none
	 */
	public ConquestMap getMap(String id)
	{
		return id == null ? null : _maps.get(id.toLowerCase());
	}

	/**
	 * @return every map, by id
	 */
	public List<ConquestMap> getMaps()
	{
		return new ArrayList<>(_maps.values());
	}

	/**
	 * @return the maps the event may pick: enabled, with spawn points for both teams and at least one capture point
	 */
	public List<ConquestMap> getEnabledMaps()
	{
		final List<ConquestMap> result = new ArrayList<>();
		for (ConquestMap map : _maps.values())
		{
			if (map.enabled() && !map.getPoints(ConquestPointType.BLUE).isEmpty() && !map.getPoints(ConquestPointType.RED).isEmpty() && !map.getPoints(ConquestPointType.CAPTURE).isEmpty())
			{
				result.add(map);
			}
		}
		return result;
	}

	/**
	 * @return one of the enabled maps by chance, {@code null} if there is none
	 */
	public ConquestMap getRandomMap()
	{
		final List<ConquestMap> maps = getEnabledMaps();
		return maps.isEmpty() ? null : maps.get(Rnd.get(maps.size()));
	}

	public static ConquestMapData getInstance()
	{
		return SingletonHolder.INSTANCE;
	}

	private static class SingletonHolder
	{
		protected static final ConquestMapData INSTANCE = new ConquestMapData();
	}
}
