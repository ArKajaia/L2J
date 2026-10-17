package custom.Bestiary;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Stream;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import org.l2jmobius.gameserver.config.ServerConfig;
import org.l2jmobius.gameserver.data.SpawnTable;
import org.l2jmobius.gameserver.data.xml.MapRegionData;
import org.l2jmobius.gameserver.data.xml.NpcData;
import org.l2jmobius.gameserver.managers.BestiaryManager;
import org.l2jmobius.gameserver.managers.GrandBossManager;
import org.l2jmobius.gameserver.managers.IdManager;
import org.l2jmobius.gameserver.model.StatSet;
import org.l2jmobius.gameserver.model.actor.Npc;
import org.l2jmobius.gameserver.model.actor.instance.Monster;
import org.l2jmobius.gameserver.model.actor.templates.NpcTemplate;
import org.l2jmobius.gameserver.model.spawns.Spawn;
import org.l2jmobius.gameserver.model.stats.MoveType;
import org.l2jmobius.gameserver.model.stats.TraitType;

/**
 * What the bestiary page knows about the monsters of the collection, worked out once from the spawn files, the spawn table and the npc templates:
 * <ul>
 * <li><b>Hunting grounds</b> - each spawn file is a hunting ground, its folder the region ("Dion/TanorCanyon.xml" is Tanor Canyon, in Dion). A monster spawned outside the spawn files (raid bosses, grand bosses) lives in the hunting ground of the spawn point nearest to it, or "Near" the closest town.</li>
 * <li><b>Map</b> - where each monster spawns (at most {@link #MAX_POINTS} points), and every spawn point of the collection in cells of {@link #MAP_CELL} units, which the page draws as the land.</li>
 * <li><b>Stats</b> - read from a monster of that kind made for the purpose and never spawned: it has the passive skills of its template (HP Increase alone is on thousands of monsters), but none of what spawning adds (random passives, champion and other variants, buffs), so they are the kind's own and the same every time. Kept once read.</li>
 * </ul>
 * @author Claude
 */
public class BestiaryCatalog
{
	private static final Logger LOGGER = Logger.getLogger(BestiaryCatalog.class.getName());

	/** Most spawn points kept per monster for its map. */
	public static final int MAX_POINTS = 80;
	/** Size of a cell of the map's land, in world units. */
	public static final int MAP_CELL = 4096;
	/** How far (world units) a spawn point outside the spawn files looks for the nearest hunting ground. */
	private static final int NEAREST_AREA_RANGE = 8000;
	private static final int GRID = 8192;
	/** A hunting ground wider than this (world units) is scattered over the world, and doesn't count as the nearest one of anything. */
	private static final int SCATTERED_SPAN = 120000;

	/** The region of the hunting grounds outside the spawn folders: their own names ("Near Giran") say more. */
	public static final String OTHER_REGION = "Other Lands";
	private static final String HIDDEN_LAIRS = "Hidden Lairs";
	/** Town names of data/mapregion that read better in the game's own words. */
	private static final Map<String, String> TOWN_NAMES = new HashMap<>();
	/** Spawn file names that lost their apostrophe. */
	private static final Map<String, String> AREA_NAMES = new HashMap<>();
	static
	{
		TOWN_NAMES.put("Darkelven Town", "Dark Elven Village");
		TOWN_NAMES.put("Elven Town", "Elven Village");
		TOWN_NAMES.put("Orc Town", "Orc Village");
		TOWN_NAMES.put("Dwarven Town", "Dwarven Village");
		TOWN_NAMES.put("Talking Island Town", "Talking Island Village");
		TOWN_NAMES.put("Giran Habor", "Giran Harbor");
		AREA_NAMES.put("Brekas Stronghold", "Breka's Stronghold");
		AREA_NAMES.put("Hardins Academy", "Hardin's Academy");
		AREA_NAMES.put("Devils Isle", "Devil's Isle");
		AREA_NAMES.put("Giants Cave", "Giant's Cave");
	}
	/** Words that stay lowercase inside a name: "Isle of Souls", "Tower of Insolence". */
	private static final Set<String> SMALL_WORDS = new HashSet<>(Arrays.asList("of", "the", "and", "in", "on", "at", "to", "for"));

	/**
	 * A hunting ground: one spawn file, or the land near a town.
	 */
	public static final class Area
	{
		final int _id;
		final String _region;
		final String _name;
		final List<Integer> _monsters = new ArrayList<>();
		double _levelSum;

		Area(int id, String region, String name)
		{
			_id = id;
			_region = region;
			_name = name;
		}

		public int getId()
		{
			return _id;
		}

		public String getRegion()
		{
			return _region;
		}

		public String getName()
		{
			return _name;
		}

		public List<Integer> getMonsters()
		{
			return _monsters;
		}

		public double getAverageLevel()
		{
			return _monsters.isEmpty() ? 0 : _levelSum / _monsters.size();
		}
	}

	/**
	 * A monster of the collection.
	 */
	public static final class Kind
	{
		final NpcTemplate _template;
		final Set<Integer> _areas = new LinkedHashSet<>();
		final List<int[]> _points = new ArrayList<>();
		int _spawnCount;
		int _respawnMin;
		int _respawnMax;

		Kind(NpcTemplate template)
		{
			_template = template;
		}

		public NpcTemplate getTemplate()
		{
			return _template;
		}

		public Set<Integer> getAreas()
		{
			return _areas;
		}

		public List<int[]> getPoints()
		{
			return _points;
		}

		public int getSpawnCount()
		{
			return _spawnCount;
		}

		/** @return the shortest respawn delay (ms), 0 if it does not respawn on its own */
		public int getRespawnMin()
		{
			return _respawnMin;
		}

		/** @return the longest respawn delay (ms) */
		public int getRespawnMax()
		{
			return _respawnMax;
		}
	}

	/**
	 * A monster's stats: of an instance of its kind ({@link #live}), or the template's base values if one could not be made.
	 */
	public static final class Stats
	{
		public final boolean live;
		public final int hp;
		public final int mp;
		public final int pAtk;
		public final int mAtk;
		public final int pDef;
		public final int mDef;
		public final int pAtkSpd;
		public final int mAtkSpd;
		public final int runSpeed;
		public final int walkSpeed;
		public final int accuracy;
		public final int evasion;
		public final int critical;
		public final int attackRange;
		public final byte attackElement;
		public final int attackElementValue;
		public final int[] defenseElements;
		/** Defence against each {@link TraitType}: 1 is none, below 1 a weakness, above 1 a resistance. */
		public final float[] defenceTraits;
		/** Above 0 for each {@link TraitType} it is immune to. */
		public final int[] traitsInvul;

		Stats(Npc npc)
		{
			live = true;
			hp = npc.getMaxHp();
			mp = npc.getMaxMp();
			pAtk = (int) npc.getPAtk(null);
			mAtk = (int) npc.getMAtk(null, null);
			pDef = (int) npc.getPDef(null);
			mDef = (int) npc.getMDef(null, null);
			pAtkSpd = (int) npc.getPAtkSpd();
			mAtkSpd = npc.getMAtkSpd();
			runSpeed = (int) npc.getRunSpeed();
			walkSpeed = (int) npc.getWalkSpeed();
			accuracy = npc.getAccuracy();
			evasion = npc.getEvasionRate(null);
			critical = npc.getCriticalHit(null, null);
			attackRange = npc.getPhysicalAttackRange();
			attackElement = npc.getAttackElement();
			attackElementValue = attackElement >= 0 ? npc.getAttackElementValue(attackElement) : 0;
			defenseElements = new int[6];
			for (byte element = 0; element < 6; element++)
			{
				defenseElements[element] = npc.getDefenseElementValue(element);
			}
			defenceTraits = npc.getStat().getDefenceTraits().clone();
			traitsInvul = npc.getStat().getTraitsInvul().clone();
		}

		Stats(NpcTemplate template)
		{
			live = false;
			hp = (int) template.getBaseHpMax();
			mp = (int) template.getBaseMpMax();
			pAtk = template.getBasePAtk();
			mAtk = template.getBaseMAtk();
			pDef = template.getBasePDef();
			mDef = template.getBaseMDef();
			pAtkSpd = template.getBasePAtkSpd();
			mAtkSpd = template.getBaseMAtkSpd();
			runSpeed = (int) template.getBaseMoveSpeed(MoveType.RUN);
			walkSpeed = (int) template.getBaseMoveSpeed(MoveType.WALK);
			accuracy = 0;
			evasion = 0;
			critical = template.getBaseCritRate();
			attackRange = template.getBaseAttackRange();
			final int[] attacks =
			{
				template.getBaseFire(),
				template.getBaseWater(),
				template.getBaseWind(),
				template.getBaseEarth(),
				template.getBaseHoly(),
				template.getBaseDark()
			};
			byte best = -1;
			for (byte element = 0; element < 6; element++)
			{
				if ((attacks[element] > 0) && ((best < 0) || (attacks[element] > attacks[best])))
				{
					best = element;
				}
			}
			attackElement = best;
			attackElementValue = best >= 0 ? attacks[best] : 0;
			defenseElements = new int[]
			{
				(int) template.getBaseFireRes(),
				(int) template.getBaseWaterRes(),
				(int) template.getBaseWindRes(),
				(int) template.getBaseEarthRes(),
				(int) template.getBaseHolyRes(),
				(int) template.getBaseDarkRes()
			};
			defenceTraits = new float[TraitType.values().length];
			Arrays.fill(defenceTraits, 1);
			traitsInvul = new int[TraitType.values().length];
		}
	}

	private final Map<Integer, Kind> _monsters = new LinkedHashMap<>();
	private final List<Area> _areas = new ArrayList<>();
	private final List<String> _regions = new ArrayList<>();
	private final Map<Long, int[]> _land = new HashMap<>();
	private final Map<Integer, Stats> _stats = new ConcurrentHashMap<>();

	private BestiaryCatalog()
	{
		final long start = System.currentTimeMillis();
		final Set<Integer> collection = BestiaryManager.getInstance().getCollection();
		for (Integer npcId : collection)
		{
			final NpcTemplate template = NpcData.getInstance().getTemplate(npcId);
			if (template != null)
			{
				_monsters.put(npcId, new Kind(template));
			}
		}

		final Map<String, Area> areasByKey = new LinkedHashMap<>();
		final Map<Long, List<int[]>> grid = new HashMap<>(); // spawn points of the spawn files: {x, y, area id}
		readSpawnFiles(areasByKey, grid);
		readSpawnTable(areasByKey, grid);

		// Each hunting ground lists its monsters, from the lowest level; the regions and their grounds go from the lowest average level up.
		for (Kind monster : _monsters.values())
		{
			for (int areaId : monster._areas)
			{
				final Area area = _areas.get(areaId);
				area._monsters.add(monster._template.getId());
				area._levelSum += monster._template.getLevel();
			}
			sample(monster._points);
		}
		for (Area area : _areas)
		{
			area._monsters.sort(Comparator.comparingInt((Integer id) -> _monsters.get(id)._template.getLevel()).thenComparing(id -> _monsters.get(id)._template.getName()));
		}

		final Map<String, double[]> regionLevels = new HashMap<>();
		for (Area area : _areas)
		{
			if (!area._monsters.isEmpty())
			{
				final double[] sum = regionLevels.computeIfAbsent(area._region, key -> new double[2]);
				sum[0] += area._levelSum;
				sum[1] += area._monsters.size();
			}
		}
		_regions.addAll(regionLevels.keySet());
		_regions.sort(Comparator.comparingDouble((String region) -> OTHER_REGION.equals(region) ? Double.MAX_VALUE : regionLevels.get(region)[0] / regionLevels.get(region)[1]));

		LOGGER.info(getClass().getSimpleName() + ": " + _monsters.size() + " monsters in " + areasByKey.size() + " hunting grounds of " + _regions.size() + " regions (" + (System.currentTimeMillis() - start) + " ms).");
	}

	/**
	 * The hunting grounds and spawn points of the spawn files, where most monsters live.
	 */
	private void readSpawnFiles(Map<String, Area> areasByKey, Map<Long, List<int[]>> grid)
	{
		final Path root = new File(ServerConfig.DATAPACK_ROOT, "data/spawns").toPath();
		final List<Path> files = new ArrayList<>();
		try (Stream<Path> walk = Files.walk(root))
		{
			walk.filter(path -> path.toString().endsWith(".xml")).sorted().forEach(files::add);
		}
		catch (IOException e)
		{
			LOGGER.log(Level.WARNING, getClass().getSimpleName() + ": Could not read " + root + ".", e);
			return;
		}

		final DocumentBuilder builder;
		try
		{
			final DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
			factory.setNamespaceAware(false);
			builder = factory.newDocumentBuilder();
		}
		catch (Exception e)
		{
			LOGGER.log(Level.WARNING, getClass().getSimpleName() + ": No XML parser.", e);
			return;
		}

		final List<Object[]> tilePoints = new ArrayList<>(); // {monster, point} of the map tile files
		for (Path file : files)
		{
			final Path relative = root.relativize(file);
			final String folder = relative.getNameCount() > 1 ? relative.getName(0).toString() : "";
			final String region = folder.isEmpty() || "Others".equals(folder) ? OTHER_REGION : words(folder);
			final String fileName = relative.getFileName().toString();
			final String stem = fileName.substring(0, fileName.length() - 4);

			// "19_16.xml" is a map tile, not a hunting ground: its spawn points join the nearest hunting ground once every named file is read. "19_16_PaganAltar.xml" is the Pagan Altar.
			final boolean tile = stem.matches("\\d+_\\d+");
			final String words = words(stem.replaceFirst("^\\d+_\\d+_", ""));
			final String name = AREA_NAMES.getOrDefault(words, words);

			final Document document;
			try
			{
				document = builder.parse(file.toFile());
			}
			catch (Exception e)
			{
				LOGGER.warning(getClass().getSimpleName() + ": Could not read " + file + " - " + e.getMessage());
				continue;
			}

			final Element list = document.getDocumentElement();
			if ("false".equalsIgnoreCase(list.getAttribute("enabled")))
			{
				continue;
			}

			final NodeList spawns = list.getElementsByTagName("spawn");
			for (int i = 0; i < spawns.getLength(); i++)
			{
				final Element spawn = (Element) spawns.item(i);
				final int[] center = territoryCenter(spawn);
				for (Node child = spawn.getFirstChild(); child != null; child = child.getNextSibling())
				{
					if (!"npc".equals(child.getNodeName()))
					{
						continue;
					}

					final Element npc = (Element) child;
					final Kind monster = _monsters.get(parseInt(npc.getAttribute("id"), 0));
					if (monster == null)
					{
						continue;
					}

					final int[] point = npc.hasAttribute("x") ? new int[]
					{
						parseInt(npc.getAttribute("x"), 0),
						parseInt(npc.getAttribute("y"), 0)
					} : center;
					if (tile)
					{
						if (point != null)
						{
							monster._points.add(point);
							addLand(point[0], point[1]);
							tilePoints.add(new Object[]
							{
								monster,
								point
							});
						}
						continue;
					}

					final Area area = areasByKey.computeIfAbsent(region + "/" + name, key -> newArea(region, name));
					monster._areas.add(area._id);
					if (point != null)
					{
						monster._points.add(point);
						addLand(point[0], point[1]);
						grid.computeIfAbsent(gridKey(point[0] / GRID, point[1] / GRID), key -> new ArrayList<>()).add(new int[]
						{
							point[0],
							point[1],
							area._id
						});
					}
				}
			}
		}

		// A file spread over the whole world (Treasure Boxes, Gremlins) is no landmark: points near one of its spawns are not in it.
		final Map<Integer, int[]> bounds = new HashMap<>();
		for (List<int[]> cell : grid.values())
		{
			for (int[] point : cell)
			{
				final int[] box = bounds.computeIfAbsent(point[2], key -> new int[]
				{
					Integer.MAX_VALUE,
					Integer.MAX_VALUE,
					Integer.MIN_VALUE,
					Integer.MIN_VALUE
				});
				box[0] = Math.min(box[0], point[0]);
				box[1] = Math.min(box[1], point[1]);
				box[2] = Math.max(box[2], point[0]);
				box[3] = Math.max(box[3], point[1]);
			}
		}
		for (List<int[]> cell : grid.values())
		{
			cell.removeIf(point ->
			{
				final int[] box = bounds.get(point[2]);
				return ((box[2] - box[0]) > SCATTERED_SPAN) || ((box[3] - box[1]) > SCATTERED_SPAN);
			});
		}

		for (Object[] tilePoint : tilePoints)
		{
			((Kind) tilePoint[0])._areas.add(nearestArea((int[]) tilePoint[1], grid, areasByKey));
		}
	}

	/**
	 * Respawn delays and counts of every monster, and the place of the ones spawned outside the spawn files: raid bosses (spawn table) and grand bosses (grandboss_data).
	 */
	private void readSpawnTable(Map<String, Area> areasByKey, Map<Long, List<int[]>> grid)
	{
		for (Kind monster : _monsters.values())
		{
			final int npcId = monster._template.getId();
			final List<int[]> extraPoints = new ArrayList<>();
			for (Spawn spawn : SpawnTable.getInstance().getSpawns(npcId))
			{
				monster._spawnCount += Math.max(1, spawn.getAmount());
				if (spawn.getRespawnMinDelay() > 0)
				{
					monster._respawnMin = monster._respawnMin == 0 ? spawn.getRespawnMinDelay() : Math.min(monster._respawnMin, spawn.getRespawnMinDelay());
					monster._respawnMax = Math.max(monster._respawnMax, spawn.getRespawnMaxDelay());
				}
				if (monster._points.isEmpty() && ((spawn.getX() != 0) || (spawn.getY() != 0)))
				{
					extraPoints.add(new int[]
					{
						spawn.getX(),
						spawn.getY()
					});
				}
			}

			final StatSet bossInfo = GrandBossManager.getInstance().getStatSet(npcId);
			if (monster._points.isEmpty() && (bossInfo != null) && ((bossInfo.getInt("loc_x", 0) != 0) || (bossInfo.getInt("loc_y", 0) != 0)))
			{
				extraPoints.add(new int[]
				{
					bossInfo.getInt("loc_x", 0),
					bossInfo.getInt("loc_y", 0)
				});
				monster._spawnCount = Math.max(1, monster._spawnCount);
			}

			for (int[] point : extraPoints)
			{
				monster._points.add(point);
				if (monster._areas.isEmpty())
				{
					monster._areas.add(nearestArea(point, grid, areasByKey));
				}
			}

			// Spawned by its script somewhere the bestiary can't see (a grand boss with no location in grandboss_data).
			if (monster._areas.isEmpty())
			{
				monster._areas.add(areasByKey.computeIfAbsent(OTHER_REGION + "/" + HIDDEN_LAIRS, key -> newArea(OTHER_REGION, HIDDEN_LAIRS))._id);
			}
		}
	}

	private Area newArea(String region, String name)
	{
		final Area area = new Area(_areas.size(), region, name);
		_areas.add(area);
		return area;
	}

	/**
	 * @return the hunting ground of the spawn file point nearest to {@code point}, or the land near the closest town
	 */
	private int nearestArea(int[] point, Map<Long, List<int[]>> grid, Map<String, Area> areasByKey)
	{
		final int cellX = point[0] / GRID;
		final int cellY = point[1] / GRID;
		long best = (long) NEAREST_AREA_RANGE * NEAREST_AREA_RANGE;
		int bestArea = -1;
		for (int dx = -1; dx <= 1; dx++)
		{
			for (int dy = -1; dy <= 1; dy++)
			{
				final List<int[]> cell = grid.get(gridKey(cellX + dx, cellY + dy));
				if (cell == null)
				{
					continue;
				}
				for (int[] candidate : cell)
				{
					final long x = candidate[0] - point[0];
					final long y = candidate[1] - point[1];
					final long distance = (x * x) + (y * y);
					if (distance < best)
					{
						best = distance;
						bestArea = candidate[2];
					}
				}
			}
		}
		if (bestArea >= 0)
		{
			return bestArea;
		}

		final String town = MapRegionData.getInstance().getClosestTownName(point[0], point[1]);
		final String name = "Near " + TOWN_NAMES.getOrDefault(town, town.replace(" Castle Town", "").replace("Town of ", "").replace(" Town", ""));
		return areasByKey.computeIfAbsent(OTHER_REGION + "/" + name, key -> newArea(OTHER_REGION, name))._id;
	}

	private static long gridKey(int x, int y)
	{
		return ((long) x << 32) ^ (y & 0xFFFFFFFFL);
	}

	private void addLand(int x, int y)
	{
		final int cellX = Math.floorDiv(x, MAP_CELL);
		final int cellY = Math.floorDiv(y, MAP_CELL);
		_land.computeIfAbsent(gridKey(cellX, cellY), key -> new int[]
		{
			cellX,
			cellY,
			0
		})[2]++;
	}

	/** @return the middle of the spawn's territory, or {@code null} if it has none */
	private static int[] territoryCenter(Element spawn)
	{
		final NodeList territories = spawn.getElementsByTagName("territory");
		if (territories.getLength() == 0)
		{
			return null;
		}

		final NodeList nodes = ((Element) territories.item(0)).getElementsByTagName("node");
		if (nodes.getLength() == 0)
		{
			return null;
		}

		long x = 0;
		long y = 0;
		for (int i = 0; i < nodes.getLength(); i++)
		{
			final Element node = (Element) nodes.item(i);
			x += parseInt(node.getAttribute("x"), 0);
			y += parseInt(node.getAttribute("y"), 0);
		}
		return new int[]
		{
			(int) (x / nodes.getLength()),
			(int) (y / nodes.getLength())
		};
	}

	/** Keeps at most MAX_POINTS of the points, spread over the whole list. */
	private static void sample(List<int[]> points)
	{
		if (points.size() <= MAX_POINTS)
		{
			return;
		}
		final List<int[]> kept = new ArrayList<>(MAX_POINTS);
		final double step = (double) points.size() / MAX_POINTS;
		for (int i = 0; i < MAX_POINTS; i++)
		{
			kept.add(points.get((int) (i * step)));
		}
		points.clear();
		points.addAll(kept);
	}

	/** "DarkElfTerritory" and "dark_elf_territory" read "Dark Elf Territory". */
	static String words(String name)
	{
		final StringBuilder sb = new StringBuilder();
		boolean upperNext = true;
		for (int i = 0; i < name.length(); i++)
		{
			final char c = name.charAt(i);
			if ((c == '_') || (c == '-') || (c == ' '))
			{
				if ((sb.length() > 0) && (sb.charAt(sb.length() - 1) != ' '))
				{
					sb.append(' ');
				}
				upperNext = true;
				continue;
			}

			if ((i > 0) && (sb.length() > 0) && (sb.charAt(sb.length() - 1) != ' '))
			{
				final char previous = name.charAt(i - 1);
				final boolean wordStart = (Character.isUpperCase(c) && (Character.isLowerCase(previous) || (((i + 1) < name.length()) && Character.isUpperCase(previous) && Character.isLowerCase(name.charAt(i + 1))))) || (Character.isDigit(c) && !Character.isDigit(previous));
				if (wordStart)
				{
					sb.append(' ');
				}
			}
			sb.append(upperNext ? Character.toUpperCase(c) : c);
			upperNext = false;
		}

		final String[] parts = sb.toString().split(" ");
		for (int i = 1; i < parts.length; i++)
		{
			if (SMALL_WORDS.contains(parts[i].toLowerCase()))
			{
				parts[i] = parts[i].toLowerCase();
			}
		}
		return String.join(" ", parts);
	}

	private static int parseInt(String value, int defaultValue)
	{
		try
		{
			return Integer.parseInt(value.trim());
		}
		catch (NumberFormatException e)
		{
			return defaultValue;
		}
	}

	// ---------------------------------------------------------------------------------------------
	// Reading
	// ---------------------------------------------------------------------------------------------

	/** @return the monsters of the collection, by npc id */
	public Map<Integer, Kind> getMonsters()
	{
		return Collections.unmodifiableMap(_monsters);
	}

	public List<Area> getAreas()
	{
		return Collections.unmodifiableList(_areas);
	}

	/** @return the regions, from the lowest average level up */
	public List<String> getRegions()
	{
		return Collections.unmodifiableList(_regions);
	}

	/**
	 * @return the monsters of each region, as the hunting grounds tab groups them: the bestiary's region EXP bonus counts them
	 */
	public Map<String, Set<Integer>> getRegionMonsters()
	{
		final Map<String, Set<Integer>> regions = new HashMap<>();
		for (Area area : _areas)
		{
			if (!area._monsters.isEmpty())
			{
				regions.computeIfAbsent(area._region, key -> new HashSet<>()).addAll(area._monsters);
			}
		}
		return regions;
	}

	/** @return the land of the map: {cell x, cell y, spawn points} for every cell of MAP_CELL units with a spawn point */
	public List<int[]> getLand()
	{
		return new ArrayList<>(_land.values());
	}

	/**
	 * @param template a monster
	 * @return its stats, read once from an instance of its kind that is never spawned
	 */
	public Stats getStats(NpcTemplate template)
	{
		return _stats.computeIfAbsent(template.getId(), id -> readStats(template));
	}

	/**
	 * @param template a monster
	 * @return the element it attacks with
	 */
	public byte getAttackElement(NpcTemplate template)
	{
		return getStats(template).attackElement;
	}

	/**
	 * Makes a monster of the kind without spawning it: the constructor gives it the passive skills of its template, and onSpawn (random passives, variants) never runs. Its object id is given back afterwards.
	 */
	private static Stats readStats(NpcTemplate template)
	{
		Monster monster = null;
		try
		{
			monster = new Monster(template);
			return new Stats(monster);
		}
		catch (Exception e)
		{
			LOGGER.log(Level.WARNING, BestiaryCatalog.class.getSimpleName() + ": Could not read the stats of " + template.getName() + " (" + template.getId() + "), using its template.", e);
			return new Stats(template);
		}
		finally
		{
			if (monster != null)
			{
				IdManager.getInstance().releaseId(monster.getObjectId());
			}
		}
	}

	public static BestiaryCatalog getInstance()
	{
		return SingletonHolder.INSTANCE;
	}

	private static class SingletonHolder
	{
		protected static final BestiaryCatalog INSTANCE = new BestiaryCatalog();
	}
}
