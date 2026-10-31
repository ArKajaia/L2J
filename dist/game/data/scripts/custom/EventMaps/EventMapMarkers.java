/*
 * This file is part of the L2J Mobius project.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>.
 */
package custom.EventMaps;

import java.awt.Color;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.l2jmobius.gameserver.config.custom.RaceWarsConfig;
import org.l2jmobius.gameserver.config.custom.TeamVsTeamConfig;
import org.l2jmobius.gameserver.data.xml.DoorData;
import org.l2jmobius.gameserver.data.xml.EventMapData;
import org.l2jmobius.gameserver.geoengine.GeoEngine;
import org.l2jmobius.gameserver.model.Location;
import org.l2jmobius.gameserver.model.World;
import org.l2jmobius.gameserver.model.actor.Player;
import org.l2jmobius.gameserver.model.actor.instance.Door;
import org.l2jmobius.gameserver.model.conquest.ConquestPoint;
import org.l2jmobius.gameserver.model.conquest.ConquestPointType;
import org.l2jmobius.gameserver.model.eventmap.EventBarrier;
import org.l2jmobius.gameserver.model.eventmap.EventDoor;
import org.l2jmobius.gameserver.model.eventmap.EventMap;
import org.l2jmobius.gameserver.model.eventmap.EventMapType;
import org.l2jmobius.gameserver.network.serverpackets.ExServerPrimitive;

/**
 * The waypoints of an event map drawn in game for a GM: the edge of the battlefield (white), the fence (orange), the doors (green: a match opens it, pink: it shuts it), and the points of one event or of every event the map is used in: Team vs Team bases (a
 * small ring where they spawn, a wide one for the base), Conquest spawn points and capture points (gold, double ring), Deathmatch spawn spots (violet), each with a beacon, its name and (spawn points) an arrow where its players face. Only the GM sees them.
 * A GM who shows a map sees it again after every save of it, until it is hidden.
 */
public class EventMapMarkers
{
	/** The name of the drawing in the client: a new one of the same name replaces it. */
	private static final String PRIMITIVE_NAME = "EventMap";

	private static final Color BLUE = new Color(70, 140, 255);
	private static final Color RED = new Color(255, 75, 60);
	private static final Color CONQUEST_BLUE = new Color(120, 180, 255);
	private static final Color CONQUEST_RED = new Color(255, 130, 110);
	private static final Color CAPTURE = new Color(255, 210, 40);
	private static final Color DEATHMATCH = new Color(195, 120, 255);
	private static final Color RACEWARS_DUEL = new Color(60, 210, 180);
	private static final Color RACEWARS_FINAL = new Color(240, 230, 80);
	private static final Color FENCE = new Color(255, 160, 40);
	private static final Color EDGE = new Color(230, 230, 230);
	private static final Color DOOR_OPEN = new Color(80, 230, 110);
	private static final Color DOOR_CLOSED = new Color(255, 90, 200);

	/** Drawn this far above the ground, so the lines don't sink into it. */
	private static final int LIFT = 16;
	/** The height of the beacon over a spawn point, and over a capture point. */
	private static final int SPAWN_BEACON = 400;
	private static final int CAPTURE_BEACON = 600;
	/** The ring of a Deathmatch spawn spot (how far its fighter may walk before the fight). */
	private static final int DEATHMATCH_RING = 300;
	/** One line of the battlefield's edge is this long. */
	private static final int EDGE_STEP = 400;
	/** The edge and the fence are not drawn where the ground there is this far above or below the battlefield (the rock over an underground map). */
	private static final int EDGE_LEVEL = 2000;
	/** The client takes a packet of at most 64 KB: at about 45 bytes a line, this many lines and points at most. */
	private static final int MAX_SHAPES = 1300;

	/** GM object id -> the map drawn for them, and the event (null for every event). */
	private final Map<Integer, String> _shown = new ConcurrentHashMap<>();
	private final Map<Integer, EventMapType> _shownType = new ConcurrentHashMap<>();

	private EventMapMarkers()
	{
	}

	/**
	 * Draws a map for a GM, and again after every save of it.
	 * @param gm the GM
	 * @param map the map
	 * @param type the event whose points are drawn, {@code null} for every event the map is used in
	 */
	public void show(Player gm, EventMap map, EventMapType type)
	{
		_shown.put(gm.getObjectId(), map.id());
		if (type != null)
		{
			_shownType.put(gm.getObjectId(), type);
		}
		else
		{
			_shownType.remove(gm.getObjectId());
		}
		draw(gm, map, type);
	}

	/**
	 * Takes the drawing away.
	 * @param gm the GM
	 */
	public void hide(Player gm)
	{
		_shown.remove(gm.getObjectId());
		_shownType.remove(gm.getObjectId());
		final ExServerPrimitive packet = new ExServerPrimitive(PRIMITIVE_NAME, gm.getX(), gm.getY(), gm.getZ());
		packet.addPoint(Color.WHITE, 0, 0, Short.MIN_VALUE);
		gm.sendPacket(packet);
	}

	/**
	 * @param gm a GM
	 * @return the id of the map drawn for them, {@code null} if none
	 */
	public String getShown(Player gm)
	{
		return _shown.get(gm.getObjectId());
	}

	/**
	 * Draws a map again for every GM who shows it, after it was saved. A map that is gone is taken away.
	 * @param mapId the map
	 */
	public void refresh(String mapId)
	{
		final EventMap map = EventMapData.getInstance().getMap(mapId);
		for (Map.Entry<Integer, String> entry : _shown.entrySet())
		{
			if (!entry.getValue().equals(mapId))
			{
				continue;
			}
			final Player gm = World.getInstance().getPlayer(entry.getKey());
			if (gm == null)
			{
				_shown.remove(entry.getKey());
				_shownType.remove(entry.getKey());
			}
			else if (map == null)
			{
				hide(gm);
			}
			else
			{
				draw(gm, map, _shownType.get(entry.getKey()));
			}
		}
	}

	/**
	 * Draws every shown map again, after the files were read again.
	 */
	public void refreshAll()
	{
		for (String mapId : new HashSet<>(_shown.values()))
		{
			refresh(mapId);
		}
	}

	private static void draw(Player gm, EventMap map, EventMapType only)
	{
		final ExServerPrimitive packet = new ExServerPrimitive(PRIMITIVE_NAME, gm.getX(), gm.getY(), gm.getZ());
		final int z = map.z();
		final boolean tvt = (only == null) ? map.belongsTo(EventMapType.TVT) : only == EventMapType.TVT;
		final boolean conquest = (only == null) ? map.belongsTo(EventMapType.CONQUEST) : only == EventMapType.CONQUEST;
		final boolean deathmatch = (only == null) ? map.belongsTo(EventMapType.DEATHMATCH) : only == EventMapType.DEATHMATCH;
		final boolean raceWars = (only == null) ? map.belongsTo(EventMapType.RACEWARS) : only == EventMapType.RACEWARS;

		// What is left of the budget after the beacons, names and arrows goes to the rings, the fence and the edge.
		final int points = (tvt ? map.blue().size() + map.red().size() : 0) + (conquest ? map.conquestPoints().size() : 0) + (deathmatch ? map.deathmatch().size() : 0) + (raceWars ? map.raceWarsDuel().size() + map.raceWarsFinal().size() : 0);
		final int perimeter = 2 * ((map.maxX() - map.minX()) + (map.maxY() - map.minY()));
		final int edgeLines = Math.min(perimeter / EDGE_STEP, MAX_SHAPES / 5);
		final int fenceLines = map.barrier() != null ? MAX_SHAPES / 6 : 0;
		final int segments = Math.max(8, Math.min(32, (MAX_SHAPES - edgeLines - fenceLines - (map.doors().size() * 6) - (points * 6)) / Math.max(1, points * 2)));

		final int[] xs =
		{
			map.minX(),
			map.maxX(),
			map.maxX(),
			map.minX(),
			map.minX()
		};
		final int[] ys =
		{
			map.minY(),
			map.minY(),
			map.maxY(),
			map.maxY(),
			map.minY()
		};
		for (int side = 0; side < 4; side++)
		{
			final int length = Math.abs(xs[side + 1] - xs[side]) + Math.abs(ys[side + 1] - ys[side]);
			final int steps = Math.max(1, (int) Math.round(((double) edgeLines * length) / Math.max(1, perimeter)));
			for (int i = 0; i < steps; i++)
			{
				addLevelLine(packet, EDGE, xs[side] + (((xs[side + 1] - xs[side]) * i) / steps), ys[side] + (((ys[side + 1] - ys[side]) * i) / steps), xs[side] + (((xs[side + 1] - xs[side]) * (i + 1)) / steps), ys[side] + (((ys[side + 1] - ys[side]) * (i + 1)) / steps), z);
			}
		}
		final int cornerZ = ground(map.minX(), map.minY(), z);
		if (Math.abs(cornerZ - z) <= EDGE_LEVEL)
		{
			packet.addPoint(map.name() + " (north-west corner)", EDGE, true, map.minX(), map.minY(), cornerZ + 60);
		}

		final EventBarrier barrier = map.barrier();
		if (barrier != null)
		{
			int lastX = barrier.x() + barrier.radiusX();
			int lastY = barrier.y();
			for (int i = 1; i <= fenceLines; i++)
			{
				final double angle = (2 * Math.PI * i) / fenceLines;
				final int nextX = barrier.x() + (int) Math.round(Math.cos(angle) * barrier.radiusX());
				final int nextY = barrier.y() + (int) Math.round(Math.sin(angle) * barrier.radiusY());
				addLevelLine(packet, FENCE, lastX, lastY, nextX, nextY, z);
				lastX = nextX;
				lastY = nextY;
			}
			packet.addPoint("Fence (TvT, Deathmatch, Race Wars)", FENCE, true, barrier.x(), barrier.y() - barrier.radiusY(), ground(barrier.x(), barrier.y() - barrier.radiusY(), z) + 80);
		}

		if (tvt)
		{
			for (boolean blue : new boolean[]
			{
				true,
				false
			})
			{
				final List<Location> spawns = map.getSpawns(blue);
				for (int i = 0; i < spawns.size(); i++)
				{
					final Location spawn = spawns.get(i);
					final Color color = blue ? BLUE : RED;
					addSpawn(packet, color, "TvT " + (blue ? "Blue" : "Red") + " base " + (i + 1) + (i == 0 ? " (start)" : ""), spawn.getX(), spawn.getY(), spawn.getZ(), spawn.getHeading(), Math.max(40, TeamVsTeamConfig.SPAWN_RADIUS), segments);
					addRing(packet, color, spawn.getX(), spawn.getY(), spawn.getZ(), TeamVsTeamConfig.BASE_RADIUS, segments);
				}
			}
		}

		if (conquest)
		{
			int blueCount = 0;
			int redCount = 0;
			for (ConquestPoint point : map.conquestPoints())
			{
				if (point.type() == ConquestPointType.CAPTURE)
				{
					final String label = "Capture " + point.name() + " (" + point.radius() + ")";
					packet.addPoint(label, CAPTURE, true, point.x(), point.y(), point.z() + CAPTURE_BEACON + 20);
					packet.addLine(CAPTURE, point.x(), point.y(), point.z() + LIFT, point.x(), point.y(), point.z() + CAPTURE_BEACON);
					addRing(packet, CAPTURE, point.x(), point.y(), point.z(), point.radius(), segments);
					addRing(packet, CAPTURE, point.x(), point.y(), point.z(), Math.max(40, point.radius() / 4), Math.max(8, segments / 2));
					continue;
				}
				final boolean blue = point.type() == ConquestPointType.BLUE;
				final int number = blue ? ++blueCount : ++redCount;
				addSpawn(packet, blue ? CONQUEST_BLUE : CONQUEST_RED, "Conquest " + (blue ? "Blue" : "Red") + " spawn " + number, point.x(), point.y(), point.z(), point.heading(), point.radius(), segments);
			}
		}

		if (deathmatch)
		{
			final List<Location> spawns = map.deathmatch();
			for (int i = 0; i < spawns.size(); i++)
			{
				final Location spawn = spawns.get(i);
				addSpawn(packet, DEATHMATCH, "DM spawn " + (i + 1), spawn.getX(), spawn.getY(), spawn.getZ(), spawn.getHeading(), DEATHMATCH_RING, Math.max(8, segments / 2));
			}
		}

		if (raceWars)
		{
			for (int i = 0; i < map.raceWarsDuel().size(); i++)
			{
				final Location start = map.raceWarsDuel().get(i);
				addSpawn(packet, RACEWARS_DUEL, "Race Wars duel start " + (i + 1) + (i == 0 ? " (first race)" : " (second race)"), start.getX(), start.getY(), start.getZ(), start.getHeading(), Math.max(40, RaceWarsConfig.SPAWN_RADIUS), segments);
				addRing(packet, RACEWARS_DUEL, start.getX(), start.getY(), start.getZ(), RaceWarsConfig.BASE_RADIUS, segments);
			}
			for (int i = 0; i < map.raceWarsFinal().size(); i++)
			{
				final Location start = map.raceWarsFinal().get(i);
				addSpawn(packet, RACEWARS_FINAL, "Race Wars final start " + (i + 1), start.getX(), start.getY(), start.getZ(), start.getHeading(), Math.max(40, RaceWarsConfig.SPAWN_RADIUS), segments);
				addRing(packet, RACEWARS_FINAL, start.getX(), start.getY(), start.getZ(), RaceWarsConfig.BASE_RADIUS, segments);
			}
		}

		for (EventDoor setting : map.doors())
		{
			final Door door = DoorData.getInstance().getDoor(setting.id());
			if (door == null)
			{
				continue;
			}
			final Color color = setting.open() ? DOOR_OPEN : DOOR_CLOSED;
			final int[] nodeX = door.getTemplate().getNodeX();
			final int[] nodeY = door.getTemplate().getNodeY();
			final int doorZ = door.getZ() + LIFT;
			for (int i = 0; i < nodeX.length; i++)
			{
				final int next = (i + 1) % nodeX.length;
				packet.addLine(color, nodeX[i], nodeY[i], doorZ, nodeX[next], nodeY[next], doorZ);
			}
			packet.addLine(color, door.getX(), door.getY(), doorZ, door.getX(), door.getY(), door.getZ() + 250);
			packet.addPoint("Door " + setting.id() + (setting.open() ? " opens" : " shuts"), color, true, door.getX(), door.getY(), door.getZ() + 270);
		}
		gm.sendPacket(packet);
	}

	/** A spawn point: a beacon with its name, a ring, and an arrow where its players face. */
	private static void addSpawn(ExServerPrimitive packet, Color color, String label, int x, int y, int z, int heading, int radius, int segments)
	{
		packet.addPoint(label, color, true, x, y, z + SPAWN_BEACON + 20);
		packet.addLine(color, x, y, z + LIFT, x, y, z + SPAWN_BEACON);
		addRing(packet, color, x, y, z, radius, segments);
		final double angle = (heading * 2 * Math.PI) / 65536;
		final int length = radius + 200;
		final int tipX = x + (int) (Math.cos(angle) * length);
		final int tipY = y + (int) (Math.sin(angle) * length);
		final int tipZ = ground(tipX, tipY, z) + LIFT;
		packet.addLine(color, x, y, z + LIFT, tipX, tipY, tipZ);
		for (int side = -1; side <= 1; side += 2)
		{
			final double back = angle + Math.PI + (side * 0.45);
			packet.addLine(color, tipX, tipY, tipZ, tipX + (int) (Math.cos(back) * 120), tipY + (int) (Math.sin(back) * 120), tipZ);
		}
	}

	/** A line on the ground, left out where the ground is on another level than the battlefield. */
	private static void addLevelLine(ExServerPrimitive packet, Color color, int x1, int y1, int x2, int y2, int z)
	{
		final int z1 = ground(x1, y1, z);
		final int z2 = ground(x2, y2, z);
		if ((Math.abs(z1 - z) <= EDGE_LEVEL) && (Math.abs(z2 - z) <= EDGE_LEVEL))
		{
			packet.addLine(color, x1, y1, z1 + LIFT, x2, y2, z2 + LIFT);
		}
	}

	/** A ring on the ground: every point at the height of the ground there, so it follows the slopes. */
	private static void addRing(ExServerPrimitive packet, Color color, int x, int y, int z, int radius, int segments)
	{
		int lastX = x + radius;
		int lastY = y;
		int lastZ = ground(lastX, lastY, z) + LIFT;
		for (int i = 1; i <= segments; i++)
		{
			final double angle = (2 * Math.PI * i) / segments;
			final int nextX = x + (int) (Math.cos(angle) * radius);
			final int nextY = y + (int) (Math.sin(angle) * radius);
			final int nextZ = ground(nextX, nextY, z) + LIFT;
			packet.addLine(color, lastX, lastY, lastZ, nextX, nextY, nextZ);
			lastX = nextX;
			lastY = nextY;
			lastZ = nextZ;
		}
	}

	/** @return the height of the ground at x, y: the layer nearest {@code z} */
	private static int ground(int x, int y, int z)
	{
		if ((x < World.WORLD_X_MIN) || (y < World.WORLD_Y_MIN) || (x >= World.WORLD_X_MAX) || (y >= World.WORLD_Y_MAX))
		{
			return z;
		}
		final GeoEngine geo = GeoEngine.getInstance();
		return geo.hasGeo(x, y) ? geo.getHeight(x, y, z) : z;
	}

	public static EventMapMarkers getInstance()
	{
		return SingletonHolder.INSTANCE;
	}

	private static class SingletonHolder
	{
		protected static final EventMapMarkers INSTANCE = new EventMapMarkers();
	}
}
