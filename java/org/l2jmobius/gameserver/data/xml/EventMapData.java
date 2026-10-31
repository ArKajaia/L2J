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
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.regex.Pattern;

import org.w3c.dom.Document;
import org.w3c.dom.NamedNodeMap;
import org.w3c.dom.Node;

import org.l2jmobius.commons.util.IXmlReader;
import org.l2jmobius.commons.util.Rnd;
import org.l2jmobius.gameserver.model.Location;
import org.l2jmobius.gameserver.model.conquest.ConquestPoint;
import org.l2jmobius.gameserver.model.conquest.ConquestPointType;
import org.l2jmobius.gameserver.model.eventmap.EventBarrier;
import org.l2jmobius.gameserver.model.eventmap.EventDoor;
import org.l2jmobius.gameserver.model.eventmap.EventMap;
import org.l2jmobius.gameserver.model.eventmap.EventMapType;

/**
 * The battlefields of the PvP events, one file each in {@code data/maps}, shared by Team vs Team, Conquest and Deathmatch (see {@link EventMap}). GMs edit them on the event map page (//tvt map, //conquest), which saves them here; they can also be edited by hand
 * and loaded again with //tvt reload or //conquest reload.<br>
 * The maps of the old separate folders (data/conquest, data/tvt) are moved in at the first start: each becomes data/maps/&lt;id&gt;.xml (a Conquest and a TvT map of the same id become one map used by both), and its old file &lt;id&gt;.xml.migrated.
 */
public class EventMapData implements IXmlReader
{
	private static final String DIRECTORY = "data/maps";
	private static final String LEGACY_CONQUEST = "data/conquest";
	private static final String LEGACY_TVT = "data/tvt";
	/** A map's id is its file name. */
	private static final Pattern ID_PATTERN = Pattern.compile("[a-z0-9_]{1,40}");
	/** Team vs Team: at most so many bases a team (each is a zone on the players' radar). */
	public static final int MAX_TVT_SPAWNS = 20;
	/** Conquest: at most so many points of one type. */
	public static final int MAX_CONQUEST_POINTS = 50;
	/** Deathmatch: at most so many spawn spots. */
	public static final int MAX_DEATHMATCH_SPAWNS = 100;
	/** Race Wars start points: the two of a race-vs-race match and the three of the final (only so many are used). */
	public static final int MAX_RACEWARS_DUEL_SPAWNS = EventMap.RACEWARS_DUEL_SPAWNS;
	public static final int MAX_RACEWARS_FINAL_SPAWNS = EventMap.RACEWARS_FINAL_SPAWNS;
	/** Conquest: how many players a team takes on a map that doesn't say, and the bounds of what a map may say. */
	public static final int DEFAULT_MAX_PLAYERS = 250;
	public static final int MIN_PLAYERS = 1;
	public static final int MAX_PLAYERS = 1000;

	private enum Source
	{
		MAPS,
		LEGACY_CONQUEST,
		LEGACY_TVT
	}

	private volatile Map<String, EventMap> _maps = new TreeMap<>();
	/** The maps being read by {@link #load}, by where they come from: the files may be read at once by several threads (ThreadsForLoading). */
	private final Map<Source, Map<String, EventMap>> _loading = new ConcurrentSkipListMap<>();
	private final Map<String, List<Path>> _legacyFiles = new ConcurrentSkipListMap<>();
	private volatile Source _source;

	protected EventMapData()
	{
		load();
	}

	@Override
	public synchronized void load()
	{
		_loading.clear();
		_legacyFiles.clear();
		for (Source source : Source.values())
		{
			_loading.put(source, new ConcurrentSkipListMap<>());
		}
		_source = Source.MAPS;
		parseDatapackDirectory(DIRECTORY, false);
		// The old folders, only while they are there (they hold nothing more once moved in).
		if (new File(".", LEGACY_CONQUEST).isDirectory())
		{
			_source = Source.LEGACY_CONQUEST;
			parseDatapackDirectory(LEGACY_CONQUEST, false);
		}
		if (new File(".", LEGACY_TVT).isDirectory())
		{
			_source = Source.LEGACY_TVT;
			parseDatapackDirectory(LEGACY_TVT, false);
		}

		final Map<String, EventMap> maps = new TreeMap<>(_loading.get(Source.MAPS));
		migrate(maps);
		_maps = maps;
		_loading.clear();
		_legacyFiles.clear();

		final StringBuilder counts = new StringBuilder();
		for (EventMapType type : EventMapType.values())
		{
			counts.append(counts.length() > 0 ? ", " : "").append(getPlayableMaps(type).size()).append(' ').append(type.getTitle());
		}
		LOGGER.info(getClass().getSimpleName() + ": Loaded " + _maps.size() + " event maps (ready for " + counts + ").");
	}

	/**
	 * Moves the maps of the old folders into data/maps: a Conquest and a TvT map of the same id become one; a map already in data/maps is kept as it is.
	 */
	private void migrate(Map<String, EventMap> maps)
	{
		final Map<String, EventMap> legacy = new TreeMap<>();
		for (Source source : new Source[]
		{
			Source.LEGACY_CONQUEST,
			Source.LEGACY_TVT
		})
		{
			for (EventMap map : _loading.get(source).values())
			{
				final EventMap other = legacy.get(map.id());
				legacy.put(map.id(), other == null ? map : merge(other, map));
			}
		}

		for (EventMap map : legacy.values())
		{
			final List<Path> files = _legacyFiles.getOrDefault(map.id(), List.of());
			if (maps.containsKey(map.id()))
			{
				LOGGER.warning(getClass().getSimpleName() + ": " + files + " not moved in: data/maps already has a map " + map.id() + ".");
				continue;
			}
			try
			{
				write(map);
				for (Path file : files)
				{
					Files.move(file, file.resolveSibling(file.getFileName() + ".migrated"), StandardCopyOption.REPLACE_EXISTING);
				}
				LOGGER.info(getClass().getSimpleName() + ": Moved " + files + " into data/maps/" + map.id() + ".xml.");
			}
			catch (IOException e)
			{
				LOGGER.warning(getClass().getSimpleName() + ": Could not move " + files + " into data/maps (" + e.getMessage() + "): it is used from there for now.");
			}
			maps.put(map.id(), map);
		}
	}

	/** One map out of a Conquest one and a TvT one of the same id: the area round both, the points and ticks of each, the doors of both. */
	private static EventMap merge(EventMap conquest, EventMap tvt)
	{
		final Set<EventMapType> uses = EnumSet.noneOf(EventMapType.class);
		uses.addAll(conquest.uses());
		uses.addAll(tvt.uses());
		final List<EventDoor> doors = new ArrayList<>(conquest.doors());
		for (EventDoor door : tvt.doors())
		{
			if (doors.stream().noneMatch(d -> d.id() == door.id()))
			{
				doors.add(door);
			}
		}
		final String description = conquest.description().isEmpty() ? tvt.description() : tvt.description().isEmpty() ? conquest.description() : conquest.description() + " " + tvt.description();
		return new EventMap(conquest.id(), conquest.name(), description, Math.min(conquest.minX(), tvt.minX()), Math.min(conquest.minY(), tvt.minY()), Math.max(conquest.maxX(), tvt.maxX()), Math.max(conquest.maxY(), tvt.maxY()), conquest.z(), tvt.barrier(), doors, uses, tvt.blue(), tvt.red(), conquest.conquestMaxPlayers(), conquest.conquestPoints(), tvt.deathmatch(), tvt.raceWarsDuel(), tvt.raceWarsFinal());
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
		final Source source = _source;
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
			EventBarrier barrier = null;
			final List<EventDoor> doors = new ArrayList<>();
			final Set<EventMapType> uses = EnumSet.noneOf(EventMapType.class);
			final List<Location> blue = new ArrayList<>();
			final List<Location> red = new ArrayList<>();
			final List<ConquestPoint> conquestPoints = new ArrayList<>();
			final List<Location> deathmatch = new ArrayList<>();
			final List<Location> raceWarsDuel = new ArrayList<>();
			final List<Location> raceWarsFinal = new ArrayList<>();
			int maxPlayers = DEFAULT_MAX_PLAYERS;

			// The old files: the whole map is the one event's, its ticks the map's "enabled".
			final boolean enabled = parseBoolean(attrs, "enabled", true);
			if (source == Source.LEGACY_CONQUEST)
			{
				maxPlayers = parseInteger(attrs, "maxPlayers", DEFAULT_MAX_PLAYERS);
				if (enabled)
				{
					uses.add(EventMapType.CONQUEST);
				}
			}
			else if ((source == Source.LEGACY_TVT) && enabled)
			{
				uses.add(EventMapType.TVT);
			}

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
					case "barrier":
					{
						barrier = readBarrier(file, childAttrs);
						break;
					}
					case "door":
					{
						doors.add(new EventDoor(parseInteger(childAttrs, "id"), parseBoolean(childAttrs, "open", true)));
						break;
					}
					case "team":
					{
						// An old file's teams: Conquest spawn points, or TvT bases.
						if (source == Source.LEGACY_CONQUEST)
						{
							readConquestTeam(file, child, conquestPoints);
						}
						else if (source == Source.LEGACY_TVT)
						{
							readTvtTeam(file, child, blue, red);
						}
						break;
					}
					case "capture":
					{
						if (source == Source.LEGACY_CONQUEST)
						{
							conquestPoints.add(readConquestPoint(ConquestPointType.CAPTURE, childAttrs));
						}
						break;
					}
					case "tvt":
					{
						if (parseBoolean(childAttrs, "enabled", true))
						{
							uses.add(EventMapType.TVT);
						}
						for (Node team = child.getFirstChild(); team != null; team = team.getNextSibling())
						{
							if ("team".equalsIgnoreCase(team.getNodeName()))
							{
								readTvtTeam(file, team, blue, red);
							}
						}
						break;
					}
					case "conquest":
					{
						if (parseBoolean(childAttrs, "enabled", true))
						{
							uses.add(EventMapType.CONQUEST);
						}
						maxPlayers = parseInteger(childAttrs, "maxPlayers", DEFAULT_MAX_PLAYERS);
						for (Node part = child.getFirstChild(); part != null; part = part.getNextSibling())
						{
							if ("team".equalsIgnoreCase(part.getNodeName()))
							{
								readConquestTeam(file, part, conquestPoints);
							}
							else if ("capture".equalsIgnoreCase(part.getNodeName()))
							{
								conquestPoints.add(readConquestPoint(ConquestPointType.CAPTURE, part.getAttributes()));
							}
						}
						break;
					}
					case "deathmatch":
					{
						if (parseBoolean(childAttrs, "enabled", true))
						{
							uses.add(EventMapType.DEATHMATCH);
						}
						for (Node spawn = child.getFirstChild(); spawn != null; spawn = spawn.getNextSibling())
						{
							if ("spawn".equalsIgnoreCase(spawn.getNodeName()))
							{
								deathmatch.add(readLocation(spawn.getAttributes()));
							}
						}
						break;
					}
					case "racewars":
					{
						if (parseBoolean(childAttrs, "enabled", true))
						{
							uses.add(EventMapType.RACEWARS);
						}
						for (Node start = child.getFirstChild(); start != null; start = start.getNextSibling())
						{
							if (!"start".equalsIgnoreCase(start.getNodeName()))
							{
								continue;
							}
							final String stage = parseString(start.getAttributes(), "stage", "duel");
							final List<Location> starts = "final".equalsIgnoreCase(stage) ? raceWarsFinal : raceWarsDuel;
							if (starts.size() < ("final".equalsIgnoreCase(stage) ? MAX_RACEWARS_FINAL_SPAWNS : MAX_RACEWARS_DUEL_SPAWNS))
							{
								starts.add(readLocation(start.getAttributes()));
							}
							else
							{
								LOGGER.warning(getClass().getSimpleName() + ": " + file.getName() + " has more Race Wars " + stage + " start points than are used; the extra ones are left out.");
							}
						}
						break;
					}
				}
			}

			if ((maxX <= minX) || (maxY <= minY))
			{
				LOGGER.warning(getClass().getSimpleName() + ": " + file.getName() + " has no area (minX, minY, maxX, maxY), it is left out.");
				continue;
			}
			final List<Location> all = new ArrayList<>(blue);
			all.addAll(red);
			all.addAll(deathmatch);
			all.addAll(raceWarsDuel);
			all.addAll(raceWarsFinal);
			for (ConquestPoint point : conquestPoints)
			{
				all.add(new Location(point.x(), point.y(), point.z()));
			}
			final EventMap map = new EventMap(fileId, parseString(attrs, "name", fileId), description, minX, minY, maxX, maxY, z != null ? z : averageZ(all), barrier, doors, uses, blue, red, Math.max(MIN_PLAYERS, Math.min(MAX_PLAYERS, maxPlayers)), conquestPoints, deathmatch, raceWarsDuel, raceWarsFinal);
			_loading.get(source).put(fileId, map);
			if (source != Source.MAPS)
			{
				_legacyFiles.computeIfAbsent(fileId, _ -> new CopyOnWriteArrayList<>()).add(file.toPath());
			}
		}
	}

	private EventBarrier readBarrier(File file, NamedNodeMap attrs)
	{
		final int radiusX = parseInteger(attrs, "radiusX");
		final int radiusY = parseInteger(attrs, "radiusY");
		if ((radiusX < EventBarrier.MIN_RADIUS) || (radiusY < EventBarrier.MIN_RADIUS))
		{
			LOGGER.warning(getClass().getSimpleName() + ": " + file.getName() + " has a fence with a radius under " + EventBarrier.MIN_RADIUS + ": no fence.");
			return null;
		}
		return new EventBarrier(parseInteger(attrs, "x"), parseInteger(attrs, "y"), radiusX, radiusY);
	}

	private void readTvtTeam(File file, Node team, List<Location> blue, List<Location> red)
	{
		final String id = parseString(team.getAttributes(), "id", "").toLowerCase();
		final List<Location> spawns = id.equals("blue") ? blue : id.equals("red") ? red : null;
		if (spawns == null)
		{
			LOGGER.warning(getClass().getSimpleName() + ": " + file.getName() + " has a TvT team that is neither blue nor red.");
			return;
		}
		for (Node spawn = team.getFirstChild(); spawn != null; spawn = spawn.getNextSibling())
		{
			if ("spawn".equalsIgnoreCase(spawn.getNodeName()))
			{
				spawns.add(readLocation(spawn.getAttributes()));
			}
		}
	}

	private void readConquestTeam(File file, Node team, List<ConquestPoint> points)
	{
		final ConquestPointType type = ConquestPointType.fromKey(parseString(team.getAttributes(), "id", ""));
		if ((type == null) || (type == ConquestPointType.CAPTURE))
		{
			LOGGER.warning(getClass().getSimpleName() + ": " + file.getName() + " has a Conquest team that is neither blue nor red.");
			return;
		}
		for (Node spawn = team.getFirstChild(); spawn != null; spawn = spawn.getNextSibling())
		{
			if ("spawn".equalsIgnoreCase(spawn.getNodeName()))
			{
				points.add(readConquestPoint(type, spawn.getAttributes()));
			}
		}
	}

	private ConquestPoint readConquestPoint(ConquestPointType type, NamedNodeMap attrs)
	{
		return new ConquestPoint(type, parseString(attrs, "name", "").trim(), parseInteger(attrs, "x"), parseInteger(attrs, "y"), parseInteger(attrs, "z"), parseInteger(attrs, "heading", 0), parseInteger(attrs, "radius", type.getDefaultRadius()));
	}

	private Location readLocation(NamedNodeMap attrs)
	{
		return new Location(parseInteger(attrs, "x"), parseInteger(attrs, "y"), parseInteger(attrs, "z"), parseInteger(attrs, "heading", 0));
	}

	/**
	 * @param locations some spots
	 * @return their average height, 0 without any
	 */
	public static int averageZ(Collection<Location> locations)
	{
		if (locations.isEmpty())
		{
			return 0;
		}
		long sum = 0;
		for (Location location : locations)
		{
			sum += location.getZ();
		}
		return (int) (sum / locations.size());
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
	 * Writes a map to its file (data/maps/&lt;id&gt;.xml), replacing what was there, and makes it the live one. A match going on keeps the map it started with.
	 * @param map the map
	 * @throws IOException if it can't be written
	 */
	public synchronized void save(EventMap map) throws IOException
	{
		write(map);
		final Map<String, EventMap> maps = new TreeMap<>(_maps);
		maps.put(map.id(), map);
		_maps = maps;
	}

	private static void write(EventMap map) throws IOException
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
	}

	/**
	 * Writes a map, as its file in data/maps holds it, to another file too (the GM page's "also save to"): a copy only, the live map stays the one in data/maps.
	 * @param map the map
	 * @param file the file: an .xml file in a folder that exists
	 * @return where it was written
	 * @throws IOException if it can't be written
	 */
	public Path writeCopy(EventMap map, Path file) throws IOException
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
		final Map<String, EventMap> maps = new TreeMap<>(_maps);
		maps.remove(id);
		_maps = maps;
		return true;
	}

	private static String toXml(EventMap map)
	{
		final StringBuilder sb = new StringBuilder(4096);
		sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
		sb.append("<!--\n");
		sb.append("\tA battlefield of the PvP events (EventMapData), shared by Team vs Team, Conquest, Deathmatch and Race Wars. Written by the event map page (//tvt map, //conquest): edits by hand are kept, comments are not.\n");
		sb.append("\tmap         id (the file name), name (shown to the players)\n");
		sb.append("\tarea        the battlefield's edge on the players' radar, minX/minY (north-west corner) to maxX/maxY (south-east corner); z: the ground's height, where it has several layers\n");
		sb.append("\tbarrier     optional, Team vs Team, Deathmatch and Race Wars: the fence round the field that nobody gets through, an ellipse: middle x, y and its radiusX, radiusY (at least 500)\n");
		sb.append("\tdoor        a door of data/Doors.xml put in a match's copy of the place, open (open=\"true\") or shut (open=\"false\"); a match has no other doors\n");
		sb.append("\ttvt         enabled: Team vs Team may pick the map. The bases of each team (blue, red): x, y, z, heading; a team starts at its first one\n");
		sb.append("\tconquest    enabled: Conquest may pick the map; maxPlayers per team. The spawn points of each team (x, y, z, heading, radius: how far a wave is spread) and the capture points (name, x, y, z, radius)\n");
		sb.append("\tdeathmatch  enabled: Deathmatch may pick the map. The spots everyone spawns at: x, y, z, heading\n");
		sb.append("\tracewars    enabled: Race Wars may pick the map. Where the races start (x, y, z, heading): stage=\"duel\" the two races of a race-vs-race match (the first race at the first one), stage=\"final\" the three races of the final\n");
		sb.append("-->\n");
		sb.append("<map id=\"").append(map.id()).append("\" name=\"").append(escape(map.name())).append("\">\n");
		if (!map.description().isEmpty())
		{
			sb.append("\t<description>").append(escape(map.description())).append("</description>\n");
		}
		sb.append("\t<area minX=\"").append(map.minX()).append("\" minY=\"").append(map.minY()).append("\" maxX=\"").append(map.maxX()).append("\" maxY=\"").append(map.maxY()).append("\" z=\"").append(map.z()).append("\" />\n");
		final EventBarrier barrier = map.barrier();
		if (barrier != null)
		{
			sb.append("\t<barrier x=\"").append(barrier.x()).append("\" y=\"").append(barrier.y()).append("\" radiusX=\"").append(barrier.radiusX()).append("\" radiusY=\"").append(barrier.radiusY()).append("\" />\n");
		}
		for (EventDoor door : map.doors())
		{
			sb.append("\t<door id=\"").append(door.id()).append("\" open=\"").append(door.open()).append("\" />\n");
		}

		if (map.belongsTo(EventMapType.TVT))
		{
			sb.append("\t<tvt enabled=\"").append(map.isUsedBy(EventMapType.TVT)).append("\">\n");
			for (boolean blue : new boolean[]
			{
				true,
				false
			})
			{
				sb.append("\t\t<team id=\"").append(blue ? "blue" : "red").append("\">\n");
				for (Location spawn : map.getSpawns(blue))
				{
					sb.append("\t\t\t<spawn");
					appendLocation(sb, spawn);
					sb.append(" />\n");
				}
				sb.append("\t\t</team>\n");
			}
			sb.append("\t</tvt>\n");
		}

		if (map.belongsTo(EventMapType.CONQUEST))
		{
			sb.append("\t<conquest enabled=\"").append(map.isUsedBy(EventMapType.CONQUEST)).append("\" maxPlayers=\"").append(map.conquestMaxPlayers()).append("\">\n");
			for (ConquestPointType team : new ConquestPointType[]
			{
				ConquestPointType.BLUE,
				ConquestPointType.RED
			})
			{
				sb.append("\t\t<team id=\"").append(team.getKey()).append("\">\n");
				for (ConquestPoint point : map.conquestPoints())
				{
					if (point.type() == team)
					{
						sb.append("\t\t\t<spawn x=\"").append(point.x()).append("\" y=\"").append(point.y()).append("\" z=\"").append(point.z()).append("\" heading=\"").append(point.heading()).append("\" radius=\"").append(point.radius()).append("\" />\n");
					}
				}
				sb.append("\t\t</team>\n");
			}
			for (ConquestPoint point : map.conquestPoints())
			{
				if (point.type() == ConquestPointType.CAPTURE)
				{
					sb.append("\t\t<capture name=\"").append(escape(point.name())).append("\" x=\"").append(point.x()).append("\" y=\"").append(point.y()).append("\" z=\"").append(point.z()).append("\" radius=\"").append(point.radius()).append("\" />\n");
				}
			}
			sb.append("\t</conquest>\n");
		}

		if (map.belongsTo(EventMapType.DEATHMATCH))
		{
			sb.append("\t<deathmatch enabled=\"").append(map.isUsedBy(EventMapType.DEATHMATCH)).append("\">\n");
			for (Location spawn : map.deathmatch())
			{
				sb.append("\t\t<spawn");
				appendLocation(sb, spawn);
				sb.append(" />\n");
			}
			sb.append("\t</deathmatch>\n");
		}

		if (map.belongsTo(EventMapType.RACEWARS))
		{
			sb.append("\t<racewars enabled=\"").append(map.isUsedBy(EventMapType.RACEWARS)).append("\">\n");
			for (Location start : map.raceWarsDuel())
			{
				sb.append("\t\t<start stage=\"duel\"");
				appendLocation(sb, start);
				sb.append(" />\n");
			}
			for (Location start : map.raceWarsFinal())
			{
				sb.append("\t\t<start stage=\"final\"");
				appendLocation(sb, start);
				sb.append(" />\n");
			}
			sb.append("\t</racewars>\n");
		}
		sb.append("</map>\n");
		return sb.toString();
	}

	private static void appendLocation(StringBuilder sb, Location location)
	{
		sb.append(" x=\"").append(location.getX()).append("\" y=\"").append(location.getY()).append("\" z=\"").append(location.getZ()).append("\" heading=\"").append(location.getHeading()).append('"');
	}

	private static String escape(String text)
	{
		return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
	}

	/**
	 * @param id a map's id
	 * @return the map, {@code null} if there is none
	 */
	public EventMap getMap(String id)
	{
		return id == null ? null : _maps.get(id.toLowerCase());
	}

	/**
	 * @return every map, by id
	 */
	public List<EventMap> getMaps()
	{
		return new ArrayList<>(_maps.values());
	}

	/**
	 * @param type an event
	 * @return the maps of that event (ticked for it, or with some of its points), by id
	 */
	public List<EventMap> getMaps(EventMapType type)
	{
		final List<EventMap> result = new ArrayList<>();
		for (EventMap map : _maps.values())
		{
			if (map.belongsTo(type))
			{
				result.add(map);
			}
		}
		return result;
	}

	/**
	 * @param type an event
	 * @return the maps it may pick: ticked for it, with the points it needs
	 */
	public List<EventMap> getPlayableMaps(EventMapType type)
	{
		final List<EventMap> result = new ArrayList<>();
		for (EventMap map : _maps.values())
		{
			if (map.isPlayable(type))
			{
				result.add(map);
			}
		}
		return result;
	}

	/**
	 * @param type an event
	 * @param allowed the ids it may pick from, empty for any
	 * @return one of the maps it may pick among them by chance, {@code null} if there is none
	 */
	public EventMap getRandomMap(EventMapType type, Collection<String> allowed)
	{
		final List<EventMap> maps = getPlayableMaps(type);
		if (!allowed.isEmpty())
		{
			maps.removeIf(map -> !allowed.contains(map.id()));
		}
		return maps.isEmpty() ? null : maps.get(Rnd.get(maps.size()));
	}

	public static EventMapData getInstance()
	{
		return SingletonHolder.INSTANCE;
	}

	private static class SingletonHolder
	{
		protected static final EventMapData INSTANCE = new EventMapData();
	}
}
