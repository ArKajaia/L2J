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
package org.l2jmobius.gameserver.model.dynamicquest;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.logging.Logger;

import org.l2jmobius.gameserver.data.SpawnTable;
import org.l2jmobius.gameserver.data.xml.DynamicQuestData;
import org.l2jmobius.gameserver.data.xml.MapRegionData;
import org.l2jmobius.gameserver.data.xml.NpcData;
import org.l2jmobius.gameserver.managers.ZoneManager;
import org.l2jmobius.gameserver.model.Location;
import org.l2jmobius.gameserver.model.actor.Npc;
import org.l2jmobius.gameserver.model.actor.templates.NpcTemplate;
import org.l2jmobius.gameserver.model.events.EventType;
import org.l2jmobius.gameserver.model.interfaces.ILocational;
import org.l2jmobius.gameserver.model.spawns.Spawn;
import org.l2jmobius.gameserver.model.zone.ZoneType;
import org.l2jmobius.gameserver.model.zone.type.ArenaZone;
import org.l2jmobius.gameserver.model.zone.type.BossZone;
import org.l2jmobius.gameserver.model.zone.type.CastleZone;
import org.l2jmobius.gameserver.model.zone.type.ClanHallZone;
import org.l2jmobius.gameserver.model.zone.type.FortZone;
import org.l2jmobius.gameserver.model.zone.type.HotZone;
import org.l2jmobius.gameserver.model.zone.type.JailZone;
import org.l2jmobius.gameserver.model.zone.type.OlympiadStadiumZone;
import org.l2jmobius.gameserver.model.zone.type.PeaceZone;
import org.l2jmobius.gameserver.model.zone.type.PvpSpotZone;
import org.l2jmobius.gameserver.model.zone.type.SiegeZone;
import org.l2jmobius.gameserver.model.zone.type.TownZone;
import org.l2jmobius.gameserver.model.zone.type.WaterZone;

/**
 * The spawn points of the plain monsters of the open world, by level, from the spawn table. Dynamic quests pick their hunting grounds and monsters from it, so every quest uses retail monsters where they really live.
 * <p>
 * Built the first time it is needed, once the world is spawned. Raid bosses, scripted monsters, instances, towns, boss lairs, sieges, arenas, jail, PvP spots, water and the
 * restricted areas of data/DynamicQuests.xml (catacombs, necropolises...) are left out; places with a level limit (Cruma Tower) are only picked for characters who can enter.
 * </p>
 * @author Mobius
 */
public class WorldMonsterIndex
{
	private static final Logger LOGGER = Logger.getLogger(WorldMonsterIndex.class.getName());

	/** Monsters within this range of a ground's centre belong to it. */
	private static final int GROUND_RADIUS = 2500;
	/** Most monster kinds a ground keeps. */
	private static final int MAX_KINDS = 6;
	/** Grounds are drawn by cells of this size, not by spawn points, so a dense area doesn't take every quest. */
	private static final int CELL_SHIFT = 12;
	/** How far from where a quest is taken its ground may be when nothing of its level is near. */
	private static final int FAR_RANGE = 60000;
	/** The widest level window a ground picked only near a place may be in: a ground near the player 12 levels off is no fit, and none is better. */
	private static final int NEAR_ONLY_MAX_WINDOW = 8;
	/** A ground is too dangerous with this many monsters this many levels above the quest within this range. */
	private static final int DANGER_COUNT = 3;
	private static final int DANGER_LEVELS = 8;
	private static final int DANGER_RANGE = 3500;
	private static final int[] LEVEL_WINDOWS =
	{
		3,
		5,
		8,
		12,
		20
	};

	private static class Point
	{
		final int _npcId;
		final int _level;
		final int _x;
		final int _y;
		final int _z;
		/** The highest level of a character who can enter the place (Integer.MAX_VALUE: anyone). */
		final int _maxEntryLevel;

		Point(int npcId, int level, int x, int y, int z, int maxEntryLevel)
		{
			_npcId = npcId;
			_level = level;
			_x = x;
			_y = y;
			_z = z;
			_maxEntryLevel = maxEntryLevel;
		}

		long cell()
		{
			return (((long) (_x >> CELL_SHIFT)) << 32) | ((_y >> CELL_SHIFT) & 0xFFFFFFFFL);
		}

		long distanceSq(int x, int y)
		{
			final long dx = _x - x;
			final long dy = _y - y;
			return (dx * dx) + (dy * dy);
		}
	}

	private volatile List<Point> _points;
	private volatile Map<Long, List<Point>> _grid;

	/**
	 * Forgets the index: it is built again the next time it is needed (after the restricted areas changed).
	 */
	public void reset()
	{
		_points = null;
		_grid = null;
	}

	/**
	 * @return the spawn points by map cell, for looking around a point quickly
	 */
	private Map<Long, List<Point>> getGrid()
	{
		Map<Long, List<Point>> grid = _grid;
		if (grid == null)
		{
			grid = new HashMap<>();
			for (Point point : getPoints())
			{
				grid.computeIfAbsent(point.cell(), key -> new ArrayList<>()).add(point);
			}
			_grid = grid;
		}
		return grid;
	}

	/**
	 * @param point a candidate ground
	 * @param level the quest's level
	 * @param cache what was found for the cells already looked at during this pick
	 * @return {@code false} if {@link #DANGER_COUNT} or more monsters {@link #DANGER_LEVELS} levels above the quest live within {@link #DANGER_RANGE} of it (on its layer)
	 */
	private boolean isSafe(Point point, int level, Map<Long, Boolean> cache)
	{
		final Boolean known = cache.get(point.cell());
		if (known != null)
		{
			return known;
		}
		final int dangerous = countDangerous(point._x, point._y, point._z, level);
		final boolean safe = dangerous < DANGER_COUNT;
		cache.put(point.cell(), safe);
		return safe;
	}

	/**
	 * @param location a place
	 * @param level a quest's level
	 * @return {@code true} if monsters much stronger than the quest live around the place (a quest shouldn't send anyone there)
	 */
	public boolean isDangerous(ILocational location, int level)
	{
		return countDangerous(location.getX(), location.getY(), location.getZ(), level) >= DANGER_COUNT;
	}

	private int countDangerous(int x, int y, int z, int level)
	{
		final Map<Long, List<Point>> grid = getGrid();
		final long rangeSq = (long) DANGER_RANGE * DANGER_RANGE;
		final int cellX = x >> CELL_SHIFT;
		final int cellY = y >> CELL_SHIFT;
		int dangerous = 0;
		for (int dx = -1; dx <= 1; dx++)
		{
			for (int dy = -1; dy <= 1; dy++)
			{
				final List<Point> cell = grid.get((((long) (cellX + dx)) << 32) | ((cellY + dy) & 0xFFFFFFFFL));
				if (cell == null)
				{
					continue;
				}
				for (Point other : cell)
				{
					if ((other._level > (level + DANGER_LEVELS)) && (Math.abs(other._z - z) < 1500) && (other.distanceSq(x, y) <= rangeSq))
					{
						dangerous++;
					}
				}
			}
		}
		return dangerous;
	}

	private List<Point> getPoints()
	{
		List<Point> points = _points;
		if (points == null)
		{
			synchronized (this)
			{
				points = _points;
				if (points == null)
				{
					points = build();
					_points = points;
				}
			}
		}
		return points;
	}

	private List<Point> build()
	{
		final long start = System.currentTimeMillis();
		final List<Point> points = new ArrayList<>();
		final List<RestrictedArea> restricted = DynamicQuestData.getInstance().getRestrictedAreas();
		int left = 0;
		for (Map.Entry<Integer, Set<Spawn>> entry : SpawnTable.getInstance().getSpawnTable().entrySet())
		{
			final NpcTemplate template = NpcData.getInstance().getTemplate(entry.getKey());
			if (!isPlainMonster(template))
			{
				continue;
			}

			for (Spawn spawn : entry.getValue())
			{
				if (spawn.getInstanceId() != 0)
				{
					continue;
				}

				int x = spawn.getX();
				int y = spawn.getY();
				int z = spawn.getZ();
				if ((x == 0) && (y == 0))
				{
					final Npc last = spawn.getLastSpawn();
					if (last == null)
					{
						continue;
					}
					x = last.getX();
					y = last.getY();
					z = last.getZ();
				}
				if (!isOpenWorld(x, y, z))
				{
					continue;
				}
				final int maxEntryLevel = getMaxEntryLevel(restricted, x, y, z);
				if (maxEntryLevel <= 0)
				{
					left++;
					continue;
				}
				points.add(new Point(template.getId(), template.getLevel(), x, y, z, maxEntryLevel));
			}
		}
		LOGGER.info(getClass().getSimpleName() + ": " + points.size() + " monster spawn points for dynamic quests, " + left + " left out in restricted areas (" + (System.currentTimeMillis() - start) + " ms).");
		return Collections.unmodifiableList(points);
	}

	/**
	 * @param template an npc template
	 * @return {@code true} if it is an ordinary monster a quest can use
	 */
	public static boolean isPlainMonster(NpcTemplate template)
	{
		return (template != null) && template.isType("Monster") && (template.getLevel() > 0) && !template.isFakePlayer() && (template.getFakePlayerPvpProfile() == null) && !template.isQuestMonster() && !template.isUndying() && !template.hasListener(EventType.ON_NPC_SPAWN) && !template.hasListener(EventType.ON_NPC_MOVE_ROUTE_FINISHED);
	}

	/**
	 * @param x the x
	 * @param y the y
	 * @param z the z
	 * @return {@code true} if a quest can take place there
	 */
	public static boolean isOpenWorld(int x, int y, int z)
	{
		for (ZoneType zone : ZoneManager.getInstance().getZones(x, y, z))
		{
			if ((zone instanceof TownZone) || (zone instanceof PeaceZone) || (zone instanceof BossZone) || (zone instanceof SiegeZone) || (zone instanceof CastleZone) || (zone instanceof FortZone) || (zone instanceof ClanHallZone) || (zone instanceof JailZone) || (zone instanceof ArenaZone) || (zone instanceof OlympiadStadiumZone) || (zone instanceof PvpSpotZone) || (zone instanceof WaterZone))
			{
				return false;
			}
		}
		return true;
	}

	/** Last words of place names that read with "the" in front ("the Cruma Marshlands", "the Ant Nest"). */
	private static final Set<String> THE_WORDS = Set.of("Grounds", "Plains", "Marshlands", "Hills", "Mines", "Springs", "Ruins", "Barracks", "Forest", "Swamp", "Territory", "Outskirts", "Waterfall", "Battleground", "Gateway", "Area", "Garden", "Hideaway", "Checkpoint", "Dwellings", "Stronghold", "Nest", "Zone", "Outpost", "Entrance", "Relic", "Temple", "Hive", "Labyrinth", "Cabin", "Village", "Tomb", "Farm", "Workshop", "Plateau");

	/**
	 * @param x the x
	 * @param y the y
	 * @param z the z
	 * @return the name of the land a point lies in, as it reads in a sentence ("the Plains of Dion", "Death Pass"), {@code null} if it has none
	 */
	public static String getPlaceName(int x, int y, int z)
	{
		ZoneType best = null;
		long bestDistance = Long.MAX_VALUE;
		for (ZoneType zone : ZoneManager.getInstance().getZones(x, y, z))
		{
			if (!(zone instanceof HotZone) || (zone.getName() == null) || (zone.getZone() == null))
			{
				continue;
			}
			final Location center = zone.getZone().getCenterPoint();
			if (center == null)
			{
				continue;
			}
			final long dx = center.getX() - x;
			final long dy = center.getY() - y;
			final long distance = (dx * dx) + (dy * dy);
			if (distance < bestDistance)
			{
				best = zone;
				bestDistance = distance;
			}
		}
		return best != null ? asPlace(best.getName()) : null;
	}

	/**
	 * @param zoneName a zone's name ("Fields of Massacre (West)", "Immortal Plateau, Northern Region")
	 * @return the name as it reads in a sentence ("the Fields of Massacre", "the Immortal Plateau")
	 */
	static String asPlace(String zoneName)
	{
		String name = zoneName.trim();
		final int parenthesis = name.indexOf('(');
		if (parenthesis > 0)
		{
			name = name.substring(0, parenthesis).trim();
		}
		final int comma = name.indexOf(',');
		if (comma > 0)
		{
			name = name.substring(0, comma).trim();
		}
		if (name.endsWith(" Upper"))
		{
			name = name.substring(0, name.length() - 6).trim();
		}
		if (name.startsWith("The "))
		{
			return "the " + name.substring(4);
		}
		if (name.contains("'s "))
		{
			return name; // Breka's Stronghold, Devil's Isle
		}
		final String lastWord = name.substring(name.lastIndexOf(' ') + 1);
		return name.contains(" of ") || THE_WORDS.contains(lastWord) ? "the " + name : name;
	}

	/**
	 * @param areas the restricted areas
	 * @param x the x
	 * @param y the y
	 * @param z the z
	 * @return the highest level of a character who can enter the point, 0 if nobody may be sent there, Integer.MAX_VALUE if anyone can
	 */
	private static int getMaxEntryLevel(List<RestrictedArea> areas, int x, int y, int z)
	{
		int maxLevel = Integer.MAX_VALUE;
		for (RestrictedArea area : areas)
		{
			if (area.contains(x, y, z))
			{
				if (area.getMaxLevel() <= 0)
				{
					return 0;
				}
				maxLevel = Math.min(maxLevel, area.getMaxLevel());
			}
		}
		return maxLevel;
	}

	/**
	 * Picks a hunting ground for a level.
	 * @param level the level wanted
	 * @param random the random source (seeded by the quest)
	 * @param near a place to stay near, {@code null} for anywhere
	 * @param nearRange how far from {@code near} the ground may be
	 * @return the ground, {@code null} if the world has no monsters at all
	 */
	public HuntingGround pick(int level, Random random, ILocational near, int nearRange)
	{
		return pick(level, random, near, nearRange, Collections.emptyList(), 0, 0);
	}

	/**
	 * Picks a hunting ground for a level, away from the places a player was sent to lately when there are others.
	 * @param level the level wanted
	 * @param random the random source (seeded by the quest)
	 * @param near a place to stay near, {@code null} for anywhere
	 * @param nearRange how far from {@code near} the ground may be
	 * @param avoid places to stay away from if possible (the last quests' grounds)
	 * @param avoidRange how far from them
	 * @param entryLevel the highest level of the characters sent there (places they can't enter are left out), 0 for any
	 * @return the ground, {@code null} if the world has no monsters at all
	 */
	public HuntingGround pick(int level, Random random, ILocational near, int nearRange, List<? extends ILocational> avoid, int avoidRange, int entryLevel)
	{
		return pick(level, random, near, nearRange, avoid, avoidRange, entryLevel, false);
	}

	/**
	 * Picks a hunting ground for a level, away from the places a player was sent to lately when there are others.
	 * @param level the level wanted
	 * @param random the random source (seeded by the quest)
	 * @param near a place to stay near, {@code null} for anywhere
	 * @param nearRange how far from {@code near} the ground may be
	 * @param avoid places to stay away from if possible (the last quests' grounds)
	 * @param avoidRange how far from them
	 * @param entryLevel the highest level of the characters sent there (places they can't enter are left out), 0 for any
	 * @param nearOnly {@code true} to never leave {@code nearRange} of {@code near} (nor a few levels of {@code level}): a quest that came to the player where they are
	 * @return the ground, {@code null} if there is none (with {@code nearOnly}, none near enough)
	 */
	public HuntingGround pick(int level, Random random, ILocational near, int nearRange, List<? extends ILocational> avoid, int avoidRange, int entryLevel, boolean nearOnly)
	{
		if (nearOnly && ((near == null) || (nearRange <= 0)))
		{
			return null;
		}

		final List<Point> all = getPoints();
		final List<Point> points;
		if (entryLevel > 0)
		{
			points = new ArrayList<>(all.size());
			for (Point point : all)
			{
				if (point._maxEntryLevel >= entryLevel)
				{
					points.add(point);
				}
			}
		}
		else
		{
			points = all;
		}
		if (points.isEmpty())
		{
			return null;
		}

		// The level comes first: a ground of about the right level anywhere within reach beats a near one 15 levels off. Within a level window, somewhere new
		// beats somewhere the player was sent to lately, near beats far, and a ground with much stronger monsters around it is never picked.
		List<Point> candidates = new ArrayList<>();
		int window = LEVEL_WINDOWS[LEVEL_WINDOWS.length - 1];
		final int farRange = Math.max(FAR_RANGE, nearRange * 3);
		final boolean canAvoid = !avoid.isEmpty() && (avoidRange > 0);
		// When every suitable ground was visited lately, at least not the very last one again: grounds take turns.
		final List<? extends ILocational> last = canAvoid ? avoid.subList(avoid.size() - 1, avoid.size()) : Collections.emptyList();
		final Map<Long, Boolean> safety = new HashMap<>();
		search:
		for (int levelWindow : LEVEL_WINDOWS)
		{
			if (nearOnly && (levelWindow > NEAR_ONLY_MAX_WINDOW))
			{
				break;
			}
			// near + new, far + new, near + not the last, far + not the last, near, far, anywhere + new, anywhere
			for (int pass = 0; pass < 8; pass++)
			{
				// Near only: the near passes (0, 2, 4).
				if (nearOnly && (((pass % 2) != 0) || (pass > 4)))
				{
					continue;
				}
				final List<? extends ILocational> skip = (pass == 0) || (pass == 1) || (pass == 6) ? avoid : ((pass == 2) || (pass == 3) ? last : Collections.emptyList());
				if (((pass < 4) || (pass == 6)) && !canAvoid)
				{
					continue;
				}
				final int range = (pass == 0) || (pass == 2) || (pass == 4) ? nearRange : ((pass == 1) || (pass == 3) || (pass == 5) ? farRange : 0);
				if ((range > 0) && (near == null))
				{
					continue;
				}
				candidates = collect(points, level, levelWindow, range > 0 ? near : null, range);
				if (!skip.isEmpty())
				{
					removeNear(candidates, skip, avoidRange);
				}
				candidates.removeIf(point -> !isSafe(point, level, safety));
				if (!candidates.isEmpty())
				{
					window = levelWindow;
					break search;
				}
			}
		}
		if (candidates.isEmpty())
		{
			if (nearOnly)
			{
				return null;
			}
			candidates = points;
		}

		final Point chosen = pickByCell(candidates, random);
		final long radiusSq = (long) GROUND_RADIUS * GROUND_RADIUS;
		final Map<Integer, Integer> kinds = new HashMap<>();
		long levelSum = 0;
		int counted = 0;
		for (Point point : points)
		{
			if ((Math.abs(point._level - level) <= (window + 2)) && (point.distanceSq(chosen._x, chosen._y) <= radiusSq) && (Math.abs(point._z - chosen._z) < 1000))
			{
				kinds.merge(point._npcId, 1, Integer::sum);
				levelSum += point._level;
				counted++;
			}
		}
		if (kinds.isEmpty())
		{
			kinds.put(chosen._npcId, 1);
			levelSum = chosen._level;
			counted = 1;
		}

		final List<Integer> npcIds = new ArrayList<>(kinds.keySet());
		npcIds.sort((a, b) -> Integer.compare(kinds.get(b), kinds.get(a)) != 0 ? Integer.compare(kinds.get(b), kinds.get(a)) : Integer.compare(a, b));
		while (npcIds.size() > MAX_KINDS)
		{
			npcIds.remove(npcIds.size() - 1);
		}
		return new HuntingGround(new Location(chosen._x, chosen._y, chosen._z), npcIds, (int) Math.round((double) levelSum / counted), MapRegionData.getInstance().getClosestTownName(chosen._x, chosen._y), getPlaceName(chosen._x, chosen._y, chosen._z));
	}

	/**
	 * Draws a cell of the map (weighted by the logarithm of its spawn points, so big grounds are a little likelier but don't take every quest), then a point in it.
	 */
	private static Point pickByCell(List<Point> candidates, Random random)
	{
		final Map<Long, List<Point>> cells = new LinkedHashMap<>();
		for (Point point : candidates)
		{
			cells.computeIfAbsent(point.cell(), key -> new ArrayList<>()).add(point);
		}
		final List<List<Point>> groups = new ArrayList<>(cells.values());
		double total = 0;
		for (List<Point> group : groups)
		{
			total += cellWeight(group.size());
		}
		double roll = random.nextDouble() * total;
		for (List<Point> group : groups)
		{
			roll -= cellWeight(group.size());
			if (roll <= 0)
			{
				return group.get(random.nextInt(group.size()));
			}
		}
		final List<Point> last = groups.get(groups.size() - 1);
		return last.get(random.nextInt(last.size()));
	}

	private static double cellWeight(int points)
	{
		return 1 + Math.log(points);
	}

	private static void removeNear(List<Point> candidates, List<? extends ILocational> avoid, int range)
	{
		final long rangeSq = (long) range * range;
		candidates.removeIf(point ->
		{
			for (ILocational place : avoid)
			{
				if (point.distanceSq(place.getX(), place.getY()) <= rangeSq)
				{
					return true;
				}
			}
			return false;
		});
	}

	private static List<Point> collect(List<Point> points, int level, int window, ILocational near, int nearRange)
	{
		final List<Point> result = new ArrayList<>();
		final long rangeSq = (long) nearRange * nearRange;
		for (Point point : points)
		{
			if (Math.abs(point._level - level) > window)
			{
				continue;
			}
			if ((near != null) && (point.distanceSq(near.getX(), near.getY()) > rangeSq))
			{
				continue;
			}
			result.add(point);
		}
		return result;
	}

	/**
	 * @param x the x
	 * @param y the y
	 * @param range the range
	 * @param level the level wanted
	 * @param window the level window
	 * @return the monster kinds living within range of a point at about a level, the most common first
	 */
	public List<Integer> getNpcIdsNear(int x, int y, int range, int level, int window)
	{
		final long rangeSq = (long) range * range;
		final Map<Integer, Integer> kinds = new HashMap<>();
		for (Point point : getPoints())
		{
			if ((Math.abs(point._level - level) <= window) && (point.distanceSq(x, y) <= rangeSq))
			{
				kinds.merge(point._npcId, 1, Integer::sum);
			}
		}
		final List<Integer> npcIds = new ArrayList<>(kinds.keySet());
		npcIds.sort((a, b) -> Integer.compare(kinds.get(b), kinds.get(a)));
		return npcIds;
	}

	/**
	 * @param x the x
	 * @param y the y
	 * @param range the range
	 * @param minLevel the lowest level wanted
	 * @param maxLevel the highest level wanted
	 * @return the monster kinds of those levels living within range of a point, the most common first
	 */
	public List<Integer> getNpcIdsInLevels(int x, int y, int range, int minLevel, int maxLevel)
	{
		final long rangeSq = (long) range * range;
		final Map<Integer, Integer> kinds = new HashMap<>();
		for (Point point : getPoints())
		{
			if ((point._level >= minLevel) && (point._level <= maxLevel) && (point.distanceSq(x, y) <= rangeSq))
			{
				kinds.merge(point._npcId, 1, Integer::sum);
			}
		}
		final List<Integer> npcIds = new ArrayList<>(kinds.keySet());
		npcIds.sort((a, b) -> Integer.compare(kinds.get(b), kinds.get(a)));
		return npcIds;
	}

	/**
	 * Something that looks at spawn points (see {@link #forEachPointNear}).
	 */
	public interface SpawnPointVisitor
	{
		void visit(int npcId, int level, int x, int y, int z);
	}

	/**
	 * Visits the monster spawn points within range of a point, of any level.
	 * @param x the x
	 * @param y the y
	 * @param range the range
	 * @param visitor what looks at each of them
	 */
	public void forEachPointNear(int x, int y, int range, SpawnPointVisitor visitor)
	{
		final long rangeSq = (long) range * range;
		for (Point point : getPoints())
		{
			if (point.distanceSq(x, y) <= rangeSq)
			{
				visitor.visit(point._npcId, point._level, point._x, point._y, point._z);
			}
		}
	}

	public static WorldMonsterIndex getInstance()
	{
		return SingletonHolder.INSTANCE;
	}

	private static class SingletonHolder
	{
		protected static final WorldMonsterIndex INSTANCE = new WorldMonsterIndex();
	}
}
