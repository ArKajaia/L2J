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
package custom.TeamVsTeam;

import java.awt.Color;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.l2jmobius.gameserver.config.custom.TeamVsTeamConfig;
import org.l2jmobius.gameserver.data.xml.DoorData;
import org.l2jmobius.gameserver.data.xml.TvTMapData;
import org.l2jmobius.gameserver.geoengine.GeoEngine;
import org.l2jmobius.gameserver.model.Location;
import org.l2jmobius.gameserver.model.World;
import org.l2jmobius.gameserver.model.actor.Player;
import org.l2jmobius.gameserver.model.actor.instance.Door;
import org.l2jmobius.gameserver.model.tvt.TvTBarrier;
import org.l2jmobius.gameserver.model.tvt.TvTDoor;
import org.l2jmobius.gameserver.model.tvt.TvTMap;
import org.l2jmobius.gameserver.network.serverpackets.ExServerPrimitive;

/**
 * The waypoints of a Team vs Team map drawn in game for a GM, like the Conquest ones: for every spawn point of each team (blue, red) a beacon, its name, an arrow where its players face, a small ring where they spawn (TvTSpawnRadius) and a wide one for its base
 * (TvTBaseRadius); the fence round the field in orange, the doors the map sets (green: the match opens it, pink: it shuts it), and the edge of the battlefield in white. Only the GM sees them. A GM who shows a map sees it again after every save of that
 * map, until //tvt hide.
 */
public class TvTMarkers
{
	/** The name of the drawing in the client: a new one of the same name replaces it. */
	private static final String PRIMITIVE_NAME = "TvTMap";

	private static final Color BLUE = new Color(70, 140, 255);
	private static final Color RED = new Color(255, 75, 60);
	private static final Color FENCE = new Color(255, 160, 40);
	private static final Color EDGE = new Color(230, 230, 230);
	private static final Color DOOR_OPEN = new Color(80, 230, 110);
	private static final Color DOOR_CLOSED = new Color(255, 90, 200);

	/** Drawn this far above the ground, so the lines don't sink into it. */
	private static final int LIFT = 16;
	/** The height of the beacon over a spawn point. */
	private static final int SPAWN_BEACON = 400;
	/** One line of the battlefield's edge is this long. */
	private static final int EDGE_STEP = 400;
	/** The edge and the fence are not drawn where the ground there is this far above or below the battlefield (the rock over an underground map). */
	private static final int EDGE_LEVEL = 2000;
	/** The client takes a packet of at most 64 KB: at about 45 bytes a line, this many lines and points at most. */
	private static final int MAX_SHAPES = 1300;

	/** GM object id -> the map drawn for them. */
	private final Map<Integer, String> _shown = new ConcurrentHashMap<>();

	private TvTMarkers()
	{
	}

	/**
	 * Draws a map for a GM, and again after every save of it.
	 * @param gm the GM
	 * @param map the map
	 */
	public void show(Player gm, TvTMap map)
	{
		_shown.put(gm.getObjectId(), map.id());
		draw(gm, map);
	}

	/**
	 * Takes the drawing away.
	 * @param gm the GM
	 */
	public void hide(Player gm)
	{
		_shown.remove(gm.getObjectId());
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
		final TvTMap map = TvTMapData.getInstance().getMap(mapId);
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
			}
			else if (map == null)
			{
				hide(gm);
			}
			else
			{
				draw(gm, map);
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

	private static void draw(Player gm, TvTMap map)
	{
		final ExServerPrimitive packet = new ExServerPrimitive(PRIMITIVE_NAME, gm.getX(), gm.getY(), gm.getZ());
		final int z = map.z();

		// What is left of the budget after the beacons, names and arrows goes to the rings, the fence and the edge.
		final int points = map.blue().size() + map.red().size();
		final int perimeter = 2 * ((map.maxX() - map.minX()) + (map.maxY() - map.minY()));
		final int edgeLines = Math.min(perimeter / EDGE_STEP, MAX_SHAPES / 5);
		final int fenceLines = map.barrier() != null ? MAX_SHAPES / 5 : 0;
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
				final int x1 = xs[side] + (((xs[side + 1] - xs[side]) * i) / steps);
				final int y1 = ys[side] + (((ys[side + 1] - ys[side]) * i) / steps);
				final int x2 = xs[side] + (((xs[side + 1] - xs[side]) * (i + 1)) / steps);
				final int y2 = ys[side] + (((ys[side + 1] - ys[side]) * (i + 1)) / steps);
				addLevelLine(packet, EDGE, x1, y1, x2, y2, z);
			}
		}
		final int cornerZ = ground(map.minX(), map.minY(), z);
		if (Math.abs(cornerZ - z) <= EDGE_LEVEL)
		{
			packet.addPoint(map.name() + " (north-west corner)", EDGE, true, map.minX(), map.minY(), cornerZ + 60);
		}

		// The fence: an ellipse on the ground.
		final TvTBarrier barrier = map.barrier();
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
			final int northZ = ground(barrier.x(), barrier.y() - barrier.radiusY(), z);
			packet.addPoint("Fence", FENCE, true, barrier.x(), barrier.y() - barrier.radiusY(), northZ + 80);
		}

		for (boolean blue : new boolean[]
		{
			true,
			false
		})
		{
			final Color color = blue ? BLUE : RED;
			final List<Location> spawns = map.getSpawns(blue);
			for (int i = 0; i < spawns.size(); i++)
			{
				final Location spawn = spawns.get(i);
				final int x = spawn.getX();
				final int y = spawn.getY();
				final int sz = spawn.getZ();
				packet.addPoint((blue ? "Blue" : "Red") + " base " + (i + 1) + (i == 0 ? " (start)" : ""), color, true, x, y, sz + SPAWN_BEACON + 20);
				packet.addLine(color, x, y, sz + LIFT, x, y, sz + SPAWN_BEACON);
				addRing(packet, color, x, y, sz, Math.max(40, TeamVsTeamConfig.SPAWN_RADIUS), Math.max(8, segments / 2));
				addRing(packet, color, x, y, sz, TeamVsTeamConfig.BASE_RADIUS, segments);

				// Where the players spawned there face.
				final double angle = (spawn.getHeading() * 2 * Math.PI) / 65536;
				final int length = Math.max(40, TeamVsTeamConfig.SPAWN_RADIUS) + 250;
				final int tipX = x + (int) (Math.cos(angle) * length);
				final int tipY = y + (int) (Math.sin(angle) * length);
				final int tipZ = ground(tipX, tipY, sz) + LIFT;
				packet.addLine(color, x, y, sz + LIFT, tipX, tipY, tipZ);
				for (int side = -1; side <= 1; side += 2)
				{
					final double back = angle + Math.PI + (side * 0.45);
					packet.addLine(color, tipX, tipY, tipZ, tipX + (int) (Math.cos(back) * 120), tipY + (int) (Math.sin(back) * 120), tipZ);
				}
			}
		}

		for (TvTDoor setting : map.doors())
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

	public static TvTMarkers getInstance()
	{
		return SingletonHolder.INSTANCE;
	}

	private static class SingletonHolder
	{
		protected static final TvTMarkers INSTANCE = new TvTMarkers();
	}
}
