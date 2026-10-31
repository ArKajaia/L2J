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
import java.util.Collection;
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
import org.l2jmobius.gameserver.model.Location;
import org.l2jmobius.gameserver.model.tvt.TvTBarrier;
import org.l2jmobius.gameserver.model.tvt.TvTDoor;
import org.l2jmobius.gameserver.model.tvt.TvTMap;

/**
 * The battlefields of the Team vs Team event, one file each in {@code data/tvt}. GMs edit them on the TvT map page (//tvt map), which saves them here; they can also be edited by hand and loaded again with //tvt reload.
 */
public class TvTMapData implements IXmlReader
{
	private static final String DIRECTORY = "data/tvt";
	/** A map's id is its file name. */
	private static final Pattern ID_PATTERN = Pattern.compile("[a-z0-9_]{1,40}");
	/** At most so many spawn points a team (each is a zone on the players' radar). */
	public static final int MAX_SPAWNS = 20;

	private volatile Map<String, TvTMap> _maps = new TreeMap<>();
	/** The maps being read by {@link #load}: the files may be read at once by several threads (ThreadsForLoading). */
	private Map<String, TvTMap> _loading;

	protected TvTMapData()
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
		LOGGER.info(getClass().getSimpleName() + ": Loaded " + _maps.size() + " Team vs Team maps (" + getEnabledMaps().size() + " enabled).");
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
			final List<Location> blue = new ArrayList<>();
			final List<Location> red = new ArrayList<>();
			TvTBarrier barrier = null;
			final List<TvTDoor> doors = new ArrayList<>();
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
						final String team = parseString(childAttrs, "id", "").toLowerCase();
						final List<Location> spawns = team.equals("blue") ? blue : team.equals("red") ? red : null;
						if (spawns == null)
						{
							LOGGER.warning(getClass().getSimpleName() + ": " + file.getName() + " has a team that is neither blue nor red.");
							break;
						}
						for (Node spawn = child.getFirstChild(); spawn != null; spawn = spawn.getNextSibling())
						{
							if ("spawn".equalsIgnoreCase(spawn.getNodeName()))
							{
								final NamedNodeMap spawnAttrs = spawn.getAttributes();
								spawns.add(new Location(parseInteger(spawnAttrs, "x"), parseInteger(spawnAttrs, "y"), parseInteger(spawnAttrs, "z"), parseInteger(spawnAttrs, "heading", 0)));
							}
						}
						break;
					}
					case "barrier":
					{
						final int radiusX = parseInteger(childAttrs, "radiusX");
						final int radiusY = parseInteger(childAttrs, "radiusY");
						if ((radiusX < TvTBarrier.MIN_RADIUS) || (radiusY < TvTBarrier.MIN_RADIUS))
						{
							LOGGER.warning(getClass().getSimpleName() + ": " + file.getName() + " has a barrier with a radius under " + TvTBarrier.MIN_RADIUS + ": no barrier.");
							break;
						}
						barrier = new TvTBarrier(parseInteger(childAttrs, "x"), parseInteger(childAttrs, "y"), radiusX, radiusY);
						break;
					}
					case "door":
					{
						doors.add(new TvTDoor(parseInteger(childAttrs, "id"), parseBoolean(childAttrs, "open", true)));
						break;
					}
				}
			}

			if ((maxX <= minX) || (maxY <= minY))
			{
				LOGGER.warning(getClass().getSimpleName() + ": " + file.getName() + " has no area (minX, minY, maxX, maxY), it is left out.");
				continue;
			}
			_loading.put(fileId, new TvTMap(fileId, parseString(attrs, "name", fileId), parseBoolean(attrs, "enabled", true), description, minX, minY, maxX, maxY, z != null ? z : averageZ(blue, red), blue, red, barrier, doors));
		}
	}

	/**
	 * @param lists some spots
	 * @return their average height, 0 without any
	 */
	@SafeVarargs
	public static int averageZ(Collection<Location>... lists)
	{
		long sum = 0;
		int count = 0;
		for (Collection<Location> list : lists)
		{
			for (Location location : list)
			{
				sum += location.getZ();
				count++;
			}
		}
		return count == 0 ? 0 : (int) (sum / count);
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
	 * Writes a map to its file (data/tvt/&lt;id&gt;.xml), replacing what was there, and makes it the live one. A match going on keeps the map it started with.
	 * @param map the map
	 * @throws IOException if it can't be written
	 */
	public synchronized void save(TvTMap map) throws IOException
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

		final Map<String, TvTMap> maps = new TreeMap<>(_maps);
		maps.put(map.id(), map);
		_maps = maps;
	}

	/**
	 * Writes a map, as its file in data/tvt holds it, to another file too (the GM page's "also save to"): a copy only, the live map stays the one in data/tvt.
	 * @param map the map
	 * @param file the file: an .xml file in a folder that exists
	 * @return where it was written
	 * @throws IOException if it can't be written
	 */
	public Path writeCopy(TvTMap map, Path file) throws IOException
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
		final Map<String, TvTMap> maps = new TreeMap<>(_maps);
		maps.remove(id);
		_maps = maps;
		return true;
	}

	private static String toXml(TvTMap map)
	{
		final StringBuilder sb = new StringBuilder(2048);
		sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
		sb.append("<!--\n");
		sb.append("\tA battlefield of the Team vs Team event (TvTMapData). Written by the TvT map page (//tvt map): edits by hand are kept, comments are not.\n");
		sb.append("\tmap      id (the file name), name (shown to the players), enabled (a registration picks one of the enabled maps of TvTMaps by chance)\n");
		sb.append("\tarea     the battlefield's edge on the players' radar, minX/minY (north-west corner) to maxX/maxY (south-east corner); z: the ground's height, where it has several layers\n");
		sb.append("\tteam     blue or red, with its spawn points (its bases, TvTBaseRadius round each): x, y, z, heading. A team starts at its first one, its dead come back at one of them\n");
		sb.append("\tbarrier  optional: the fence round the field that nobody gets through, an ellipse: middle x, y and its radiusX, radiusY (at least 500). It must hold every base\n");
		sb.append("\tdoor     a door of data/Doors.xml put in the match's copy of the place, opened (open=\"true\") or shut (open=\"false\"); a match has no other doors\n");
		sb.append("-->\n");
		sb.append("<map id=\"").append(map.id()).append("\" name=\"").append(escape(map.name())).append("\" enabled=\"").append(map.enabled()).append("\">\n");
		if (!map.description().isEmpty())
		{
			sb.append("\t<description>").append(escape(map.description())).append("</description>\n");
		}
		sb.append("\t<area minX=\"").append(map.minX()).append("\" minY=\"").append(map.minY()).append("\" maxX=\"").append(map.maxX()).append("\" maxY=\"").append(map.maxY()).append("\" z=\"").append(map.z()).append("\" />\n");
		for (boolean blue : new boolean[]
		{
			true,
			false
		})
		{
			sb.append("\t<team id=\"").append(blue ? "blue" : "red").append("\">\n");
			for (Location spawn : map.getSpawns(blue))
			{
				sb.append("\t\t<spawn x=\"").append(spawn.getX()).append("\" y=\"").append(spawn.getY()).append("\" z=\"").append(spawn.getZ()).append("\" heading=\"").append(spawn.getHeading()).append("\" />\n");
			}
			sb.append("\t</team>\n");
		}
		final TvTBarrier barrier = map.barrier();
		if (barrier != null)
		{
			sb.append("\t<barrier x=\"").append(barrier.x()).append("\" y=\"").append(barrier.y()).append("\" radiusX=\"").append(barrier.radiusX()).append("\" radiusY=\"").append(barrier.radiusY()).append("\" />\n");
		}
		for (TvTDoor door : map.doors())
		{
			sb.append("\t<door id=\"").append(door.id()).append("\" open=\"").append(door.open()).append("\" />\n");
		}
		sb.append("</map>\n");
		return sb.toString();
	}

	private static String escape(String text)
	{
		return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
	}

	/**
	 * @param id a map's id
	 * @return the map, {@code null} if there is none
	 */
	public TvTMap getMap(String id)
	{
		return id == null ? null : _maps.get(id.toLowerCase());
	}

	/**
	 * @return every map, by id
	 */
	public List<TvTMap> getMaps()
	{
		return new ArrayList<>(_maps.values());
	}

	/**
	 * @return the maps the event may pick: enabled, with spawn points for both teams
	 */
	public List<TvTMap> getEnabledMaps()
	{
		final List<TvTMap> result = new ArrayList<>();
		for (TvTMap map : _maps.values())
		{
			if (map.enabled() && map.isComplete())
			{
				result.add(map);
			}
		}
		return result;
	}

	/**
	 * @param allowed the ids a registration may pick from, empty for any
	 * @return one of the enabled maps among them by chance, {@code null} if there is none
	 */
	public TvTMap getRandomMap(Collection<String> allowed)
	{
		final List<TvTMap> maps = getEnabledMaps();
		if (!allowed.isEmpty())
		{
			maps.removeIf(map -> !allowed.contains(map.id()));
		}
		return maps.isEmpty() ? null : maps.get(Rnd.get(maps.size()));
	}

	public static TvTMapData getInstance()
	{
		return SingletonHolder.INSTANCE;
	}

	private static class SingletonHolder
	{
		protected static final TvTMapData INSTANCE = new TvTMapData();
	}
}
