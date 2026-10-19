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
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.logging.Logger;

import org.l2jmobius.gameserver.data.SpawnTable;
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
 * Built the first time it is needed, once the world is spawned. Raid bosses, scripted monsters, instances, towns, boss lairs, sieges, arenas, jail, PvP spots and water are left out.
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

		Point(int npcId, int level, int x, int y, int z)
		{
			_npcId = npcId;
			_level = level;
			_x = x;
			_y = y;
			_z = z;
		}

		long distanceSq(int x, int y)
		{
			final long dx = _x - x;
			final long dy = _y - y;
			return (dx * dx) + (dy * dy);
		}
	}

	private volatile List<Point> _points;

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
				if (isOpenWorld(x, y, z))
				{
					points.add(new Point(template.getId(), template.getLevel(), x, y, z));
				}
			}
		}
		LOGGER.info(getClass().getSimpleName() + ": " + points.size() + " monster spawn points for dynamic quests (" + (System.currentTimeMillis() - start) + " ms).");
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
		final List<Point> points = getPoints();
		if (points.isEmpty())
		{
			return null;
		}

		List<Point> candidates = new ArrayList<>();
		int window = LEVEL_WINDOWS[LEVEL_WINDOWS.length - 1];
		for (boolean useNear : new boolean[]
		{
			true,
			false
		})
		{
			if (!useNear || (near != null))
			{
				for (int levelWindow : LEVEL_WINDOWS)
				{
					candidates = collect(points, level, levelWindow, useNear ? near : null, nearRange);
					if (!candidates.isEmpty())
					{
						window = levelWindow;
						break;
					}
				}
			}
			if (!candidates.isEmpty())
			{
				break;
			}
		}
		if (candidates.isEmpty())
		{
			candidates = points;
		}

		final Point chosen = candidates.get(random.nextInt(candidates.size()));
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
		return new HuntingGround(new Location(chosen._x, chosen._y, chosen._z), npcIds, (int) Math.round((double) levelSum / counted), MapRegionData.getInstance().getClosestTownName(chosen._x, chosen._y));
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
