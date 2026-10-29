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
package custom.Conquest;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.logging.Logger;

import javax.imageio.ImageIO;

import org.l2jmobius.commons.threads.ThreadPool;
import org.l2jmobius.commons.util.SimpleJson;
import org.l2jmobius.gameserver.config.custom.ConquestConfig;
import org.l2jmobius.gameserver.data.xml.ConquestMapData;
import org.l2jmobius.gameserver.data.xml.DoorData;
import org.l2jmobius.gameserver.geoengine.GeoEngine;
import org.l2jmobius.gameserver.geoengine.geodata.Cell;
import org.l2jmobius.gameserver.managers.conquest.ConquestCapture;
import org.l2jmobius.gameserver.managers.conquest.ConquestFighter;
import org.l2jmobius.gameserver.managers.conquest.ConquestManager;
import org.l2jmobius.gameserver.managers.conquest.ConquestMatch;
import org.l2jmobius.gameserver.managers.conquest.ConquestParty;
import org.l2jmobius.gameserver.managers.conquest.ConquestTeam;
import org.l2jmobius.gameserver.model.Location;
import org.l2jmobius.gameserver.model.World;
import org.l2jmobius.gameserver.model.actor.Npc;
import org.l2jmobius.gameserver.model.actor.Player;
import org.l2jmobius.gameserver.model.actor.enums.creature.Team;
import org.l2jmobius.gameserver.model.actor.holders.npc.FakePlayerPvpProfile;
import org.l2jmobius.gameserver.model.actor.instance.Door;
import org.l2jmobius.gameserver.model.conquest.ConquestDoor;
import org.l2jmobius.gameserver.model.conquest.ConquestMap;
import org.l2jmobius.gameserver.model.conquest.ConquestPoint;
import org.l2jmobius.gameserver.model.conquest.ConquestPointType;

import com.sun.net.httpserver.HttpExchange;

import custom.PassiveSkillTree.PassiveTreeApiServer;

/**
 * The GM page of the Conquest maps ({@link ConquestMapData}), served by the passive tree's web server. A GM opens it with //conquest; the link works while that GM is online and allowed to use //conquest.
 * <ul>
 * <li>GET /conquest.html - the page</li>
 * <li>GET /api/conquest/maps - every map with its points, and which one is drawn in game for the GM</li>
 * <li>GET /api/conquest/gm - where the GM is now</li>
 * <li>GET /api/conquest/doors?minX=&amp;minY=&amp;maxX=&amp;maxY= - the doors in an area (data/Doors.xml), with their shape and whether they are open by default and now</li>
 * <li>GET /api/conquest/terrain?minX=&amp;minY=&amp;maxX=&amp;maxY=&amp;z= - a picture of the ground from the geodata, seen from above: heights, slopes, and where it can't be walked</li>
 * <li>GET /api/conquest/ground?x=&amp;y=&amp;z= - the height of the ground at a spot (the layer nearest z), for a point the page moved</li>
 * <li>POST /api/conquest/save (JSON: the map, previousId when it was renamed, saveTo for a file to write a copy to) - checks the map, puts every point on the ground, writes data/conquest/&lt;id&gt;.xml (and the copy) and draws it again for the GMs who show it</li>
 * <li>POST /api/conquest/delete (id) - takes a map out (its file becomes &lt;id&gt;.xml.deleted)</li>
 * <li>POST /api/conquest/teleport (x, y, z, heading) - takes the GM there</li>
 * <li>POST /api/conquest/show (id), /hide - draws a saved map in game for the GM (see {@link ConquestMarkers}), or takes it away</li>
 * <li>POST /api/conquest/reload - reads the map files again, after they were edited by hand</li>
 * </ul>
 * The running match (see {@link ConquestManager}):
 * <ul>
 * <li>GET /conquest-live.html - the live page</li>
 * <li>GET /api/conquest/live - the match: phase, time, both teams (score, drain, parties and their orders, every fighter), the capture points, the log</li>
 * <li>POST /api/conquest/match/start (map, perTeam, levels like 40-50), /match/stop - like //conquest &lt;map&gt; start and //conquest stop</li>
 * <li>POST /api/conquest/match/watch, /match/leave - take the GM of the link into the match's copy of the map, or back</li>
 * </ul>
 * @author Claude
 */
public class ConquestWeb
{
	private static final Logger LOGGER = Logger.getLogger(ConquestWeb.class.getName());

	private static final Path HTML_FILE = Path.of("data/html/custom/conquest.html");
	private static final Path LIVE_HTML_FILE = Path.of("data/html/custom/conquest-live.html");
	/** The command a GM's access level must allow. */
	private static final String ADMIN_COMMAND = "admin_conquest";
	private static final int MAX_BODY_BYTES = 256 * 1024;

	/** A battlefield is at least this wide and at most this wide (about two geodata regions). */
	private static final int MIN_AREA = 1000;
	private static final int MAX_AREA = 65536;
	private static final int MIN_RADIUS = 50;
	private static final int MAX_RADIUS = 5000;
	private static final int MAX_NAME = 60;
	private static final int MAX_POINT_NAME = 12;
	private static final int MAX_DESCRIPTION = 2000;

	/** Ground more than this above or below the battlefield's height is another level (the rock over a cave, a cliff top): drawn as such, and not followed. */
	private static final int LEVEL_WINDOW = 2000;
	/** The terrain picture is at most this many pixels on its long side. */
	private static final int MAX_TERRAIN_PIXELS = 1400;
	/** The last terrain pictures, by the area they show. */
	private final Map<String, byte[]> _terrain = new LinkedHashMap<String, byte[]>(8, 0.75f, true)
	{
		private static final long serialVersionUID = 1L;

		@Override
		protected boolean removeEldestEntry(Map.Entry<String, byte[]> eldest)
		{
			return size() > 6;
		}
	};

	private ConquestWeb()
	{
	}

	/**
	 * Adds the Conquest page's paths to the passive tree's web server. Called once its server is running.
	 */
	public void register()
	{
		final PassiveTreeApiServer server = PassiveTreeApiServer.getInstance();
		server.addRoute("/conquest.html", "GET", false, exchange -> server.sendPage(exchange, HTML_FILE));
		server.addRoute("/api/conquest/maps", "GET", false, this::handleMaps);
		server.addRoute("/api/conquest/gm", "GET", false, this::handleGm);
		server.addRoute("/api/conquest/ground", "GET", false, this::handleGround);
		server.addRoute("/api/conquest/terrain", "GET", false, this::handleTerrain);
		server.addRoute("/api/conquest/doors", "GET", false, this::handleDoors);
		server.addRoute("/api/conquest/save", "POST", true, this::handleSave);
		server.addRoute("/api/conquest/delete", "POST", true, this::handleDelete);
		server.addRoute("/api/conquest/teleport", "POST", true, this::handleTeleport);
		server.addRoute("/api/conquest/show", "POST", true, this::handleShow);
		server.addRoute("/api/conquest/hide", "POST", true, this::handleHide);
		server.addRoute("/api/conquest/reload", "POST", true, this::handleReload);
		server.addRoute("/conquest-live.html", "GET", false, exchange -> server.sendPage(exchange, LIVE_HTML_FILE));
		server.addRoute("/api/conquest/live", "GET", false, this::handleLive);
		server.addRoute("/api/conquest/match/start", "POST", true, this::handleMatchStart);
		server.addRoute("/api/conquest/match/stop", "POST", true, this::handleMatchStop);
		server.addRoute("/api/conquest/match/watch", "POST", true, this::handleMatchWatch);
		server.addRoute("/api/conquest/match/leave", "POST", true, this::handleMatchLeave);
	}

	/**
	 * @param gm the GM
	 * @return the address of the live page of the match for that GM
	 */
	public static String getLiveUrl(Player gm)
	{
		final PassiveTreeApiServer server = PassiveTreeApiServer.getInstance();
		return server.getPageUrl("conquest-live.html") + "?admin=" + server.generateAdminToken(gm.getObjectId());
	}

	/**
	 * @param gm the GM
	 * @return the address of the Conquest page for that GM
	 */
	public static String getUrl(Player gm)
	{
		final PassiveTreeApiServer server = PassiveTreeApiServer.getInstance();
		return server.getPageUrl("conquest.html") + "?admin=" + server.generateAdminToken(gm.getObjectId());
	}

	// ---------------------------------------------------------------------------------------------
	// Reading
	// ---------------------------------------------------------------------------------------------

	private void handleMaps(HttpExchange exchange) throws IOException
	{
		final Player gm = getGm(exchange);
		if (gm == null)
		{
			return;
		}

		final List<ConquestMap> maps = ConquestMapData.getInstance().getMaps();
		final StringBuilder json = new StringBuilder(4096);
		json.append("{\"admin\":").append(SimpleJson.quote(gm.getName()));
		json.append(",\"shown\":").append(quoteOrNull(ConquestMarkers.getInstance().getShown(gm)));
		json.append(",\"maxPoints\":").append(ConquestMapData.MAX_POINTS);
		json.append(",\"players\":{\"default\":").append(ConquestMapData.DEFAULT_MAX_PLAYERS).append(",\"min\":").append(ConquestMapData.MIN_PLAYERS).append(",\"max\":").append(ConquestMapData.MAX_PLAYERS).append('}');
		json.append(",\"world\":[").append(World.WORLD_X_MIN).append(',').append(World.WORLD_Y_MIN).append(',').append(World.WORLD_X_MAX).append(',').append(World.WORLD_Y_MAX).append(']');
		json.append(",\"enabled\":[");
		boolean first = true;
		for (ConquestMap map : ConquestMapData.getInstance().getEnabledMaps())
		{
			json.append(first ? "" : ",").append(SimpleJson.quote(map.id()));
			first = false;
		}
		json.append("],\"maps\":[");
		for (int i = 0; i < maps.size(); i++)
		{
			json.append(i > 0 ? "," : "");
			appendMap(json, maps.get(i));
		}
		json.append("]}");
		PassiveTreeApiServer.getInstance().sendJson(exchange, 200, json.toString());
	}

	private void handleGm(HttpExchange exchange) throws IOException
	{
		final Player gm = getGm(exchange);
		if (gm == null)
		{
			return;
		}

		final StringBuilder json = new StringBuilder(160);
		json.append("{\"name\":").append(SimpleJson.quote(gm.getName()));
		json.append(",\"x\":").append(gm.getX()).append(",\"y\":").append(gm.getY()).append(",\"z\":").append(gm.getZ());
		json.append(",\"heading\":").append(gm.getHeading()).append(",\"instance\":").append(gm.getInstanceId());
		json.append(",\"shown\":").append(quoteOrNull(ConquestMarkers.getInstance().getShown(gm))).append('}');
		PassiveTreeApiServer.getInstance().sendJson(exchange, 200, json.toString());
	}

	/**
	 * The ground at a spot, for a point the page just moved: its height (the layer nearest the height it had) and whether a player can walk off it in every direction.
	 */
	private void handleGround(HttpExchange exchange) throws IOException
	{
		final PassiveTreeApiServer server = PassiveTreeApiServer.getInstance();
		if (getGm(exchange) == null)
		{
			return;
		}

		final Map<String, String> params = server.readParams(exchange);
		final int x = parseInt(params.get("x"), Integer.MIN_VALUE);
		final int y = parseInt(params.get("y"), Integer.MIN_VALUE);
		if (!inWorld(x, y))
		{
			sendError(exchange, "that place is outside the world");
			return;
		}
		final GeoEngine geo = GeoEngine.getInstance();
		final int hint = parseInt(params.get("z"), 0);
		final boolean hasGeo = geo.hasGeo(x, y);
		final int z = hasGeo ? geo.getHeight(x, y, hint) : hint;
		final boolean open = hasGeo && geo.checkNearestNswe(GeoEngine.getGeoX(x), GeoEngine.getGeoY(y), z, Cell.NSWE_ALL);
		server.sendJson(exchange, 200, "{\"x\":" + x + ",\"y\":" + y + ",\"z\":" + z + ",\"geo\":" + hasGeo + ",\"open\":" + open + "}");
	}

	private static void appendMap(StringBuilder json, ConquestMap map)
	{
		final GeoEngine geo = GeoEngine.getInstance();
		json.append("{\"id\":").append(SimpleJson.quote(map.id()));
		json.append(",\"name\":").append(SimpleJson.quote(map.name()));
		json.append(",\"enabled\":").append(map.enabled());
		json.append(",\"maxPlayers\":").append(map.maxPlayers());
		json.append(",\"description\":").append(SimpleJson.quote(map.description()));
		json.append(",\"minX\":").append(map.minX()).append(",\"minY\":").append(map.minY()).append(",\"maxX\":").append(map.maxX()).append(",\"maxY\":").append(map.maxY()).append(",\"z\":").append(map.z());
		json.append(",\"points\":[");
		boolean first = true;
		for (ConquestPoint point : map.points())
		{
			json.append(first ? "" : ",");
			first = false;
			json.append("{\"type\":").append(SimpleJson.quote(point.type().getKey()));
			json.append(",\"name\":").append(SimpleJson.quote(point.name()));
			json.append(",\"x\":").append(point.x()).append(",\"y\":").append(point.y()).append(",\"z\":").append(point.z());
			json.append(",\"heading\":").append(point.heading()).append(",\"radius\":").append(point.radius());
			// Whether a player can stand there and walk off in every direction: not in a wall, on a cliff edge or out of the geodata.
			json.append(",\"open\":").append(inWorld(point.x(), point.y()) && geo.hasGeo(point.x(), point.y()) && geo.checkNearestNswe(GeoEngine.getGeoX(point.x()), GeoEngine.getGeoY(point.y()), point.z(), Cell.NSWE_ALL));
			json.append('}');
		}
		json.append("],\"doors\":[");
		first = true;
		for (ConquestDoor door : map.doors())
		{
			json.append(first ? "" : ",").append("{\"id\":").append(door.id()).append(",\"open\":").append(door.open()).append('}');
			first = false;
		}
		json.append("]}");
	}

	private void handleDoors(HttpExchange exchange) throws IOException
	{
		final PassiveTreeApiServer server = PassiveTreeApiServer.getInstance();
		if (getGm(exchange) == null)
		{
			return;
		}

		final Map<String, String> params = server.readParams(exchange);
		final int minX = parseInt(params.get("minX"), 0);
		final int minY = parseInt(params.get("minY"), 0);
		final int maxX = parseInt(params.get("maxX"), 0);
		final int maxY = parseInt(params.get("maxY"), 0);
		final StringBuilder json = new StringBuilder(2048);
		json.append("{\"doors\":[");
		boolean first = true;
		for (Door door : DoorData.getInstance().getDoors())
		{
			if ((door.getX() < minX) || (door.getX() > maxX) || (door.getY() < minY) || (door.getY() > maxY))
			{
				continue;
			}
			json.append(first ? "" : ",");
			first = false;
			json.append("{\"id\":").append(door.getId());
			json.append(",\"name\":").append(SimpleJson.quote(door.getTemplate().getName()));
			json.append(",\"x\":").append(door.getX()).append(",\"y\":").append(door.getY()).append(",\"z\":").append(door.getZ());
			json.append(",\"defaultOpen\":").append(door.getTemplate().isOpenByDefault()).append(",\"open\":").append(door.isOpen());
			// The corners of the door, so the page draws it across the passage it shuts.
			final int[] nodeX = door.getTemplate().getNodeX();
			final int[] nodeY = door.getTemplate().getNodeY();
			json.append(",\"nodes\":[");
			for (int i = 0; i < nodeX.length; i++)
			{
				json.append(i > 0 ? "," : "").append('[').append(nodeX[i]).append(',').append(nodeY[i]).append(']');
			}
			json.append("]}");
		}
		json.append("]}");
		server.sendJson(exchange, 200, json.toString());
	}

	// ---------------------------------------------------------------------------------------------
	// Terrain
	// ---------------------------------------------------------------------------------------------

	private void handleTerrain(HttpExchange exchange) throws IOException
	{
		final PassiveTreeApiServer server = PassiveTreeApiServer.getInstance();
		if (getGm(exchange) == null)
		{
			return;
		}

		final Map<String, String> params = server.readParams(exchange);
		final int minX = Math.max(World.WORLD_X_MIN, parseInt(params.get("minX"), 0));
		final int minY = Math.max(World.WORLD_Y_MIN, parseInt(params.get("minY"), 0));
		final int maxX = Math.min(World.WORLD_X_MAX - 1, parseInt(params.get("maxX"), 0));
		final int maxY = Math.min(World.WORLD_Y_MAX - 1, parseInt(params.get("maxY"), 0));
		final int z = parseInt(params.get("z"), 0);
		if (((maxX - minX) < 100) || ((maxY - minY) < 100) || ((maxX - minX) > (MAX_AREA * 2)) || ((maxY - minY) > (MAX_AREA * 2)))
		{
			server.sendText(exchange, 400, "application/json", "{\"error\":\"the area must be 100 to " + (MAX_AREA * 2) + " wide\"}");
			return;
		}

		final String key = minX + "," + minY + "," + maxX + "," + maxY + "," + z;
		byte[] png;
		synchronized (_terrain)
		{
			png = _terrain.get(key);
		}
		if (png == null)
		{
			png = renderTerrain(minX, minY, maxX, maxY, z);
			synchronized (_terrain)
			{
				_terrain.put(key, png);
			}
		}
		server.sendStatic(exchange, "image/png", png, 300);
	}

	/**
	 * Draws the ground seen from above, north up, from the geodata: low ground dark green, high ground pale, slopes shaded as if lit from the north-west, red where a player can't walk off in every direction (walls, cliffs, rocks), dark blue where
	 * there is no geodata, and dark grey where the ground is on another level than the battlefield (more than {@link #LEVEL_WINDOW} above or below it: the rock over an underground map, the mountains around a valley).
	 * @param minX the west edge
	 * @param minY the north edge
	 * @param maxX the east edge
	 * @param maxY the south edge
	 * @param z the height of the battlefield: where the ground has several layers, the one nearest the ground next to it, starting from this height
	 * @return the picture, as PNG
	 */
	private static byte[] renderTerrain(int minX, int minY, int maxX, int maxY, int z) throws IOException
	{
		final GeoEngine geo = GeoEngine.getInstance();
		final int span = Math.max(maxX - minX, maxY - minY);
		// World units per pixel: whole geodata cells (16), as few as fit.
		final int step = Math.max(1, (int) Math.ceil(span / 16.0 / MAX_TERRAIN_PIXELS)) * 16;
		final int width = Math.max(1, (maxX - minX) / step);
		final int height = Math.max(1, (maxY - minY) / step);

		final int[] heights = new int[width * height];
		final byte[] kind = new byte[width * height]; // 0 open, 1 blocked, 2 no geodata, 3 another level
		final List<Integer> sample = new ArrayList<>();
		for (int py = 0; py < height; py++)
		{
			for (int px = 0; px < width; px++)
			{
				final int index = (py * width) + px;
				final int x = minX + (px * step) + (step / 2);
				final int y = minY + (py * step) + (step / 2);
				if (!geo.hasGeo(x, y))
				{
					kind[index] = 2;
					heights[index] = z;
					continue;
				}
				// The ground next to it, so a picture of a hill doesn't jump to a cave under it, as long as that is on the battlefield's level (an underground map is not drawn on the rock over it).
				final int neighbour = px > 0 ? index - 1 : py > 0 ? index - width : -1;
				final int hint = (neighbour >= 0) && (kind[neighbour] < 2) ? heights[neighbour] : z;
				final int ground = geo.getHeight(x, y, hint);
				heights[index] = ground;
				if (Math.abs(ground - z) > LEVEL_WINDOW)
				{
					kind[index] = 3;
					continue;
				}
				kind[index] = geo.checkNearestNswe(GeoEngine.getGeoX(x), GeoEngine.getGeoY(y), ground, Cell.NSWE_ALL) ? (byte) 0 : (byte) 1;
				if ((index % 7) == 0)
				{
					sample.add(ground);
				}
			}
		}

		// The colours span the heights of most of the ground, so one high rock doesn't wash the rest out.
		int low = z - 500;
		int high = z + 500;
		if (sample.size() > 10)
		{
			final int[] sorted = sample.stream().mapToInt(Integer::intValue).toArray();
			Arrays.sort(sorted);
			low = sorted[sorted.length / 50];
			high = Math.max(low + 200, sorted[sorted.length - 1 - (sorted.length / 50)]);
		}

		final BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
		for (int py = 0; py < height; py++)
		{
			for (int px = 0; px < width; px++)
			{
				final int index = (py * width) + px;
				if (kind[index] == 2)
				{
					image.setRGB(px, py, 0x0B1224);
					continue;
				}

				final double t = Math.max(0, Math.min(1, (heights[index] - low) / (double) (high - low)));
				// Lit from the north-west: ground rising towards it is brighter.
				final int west = level(heights, kind, index, px > 0 ? index - 1 : -1);
				final int east = level(heights, kind, index, px < (width - 1) ? index + 1 : -1);
				final int north = level(heights, kind, index, py > 0 ? index - width : -1);
				final int south = level(heights, kind, index, py < (height - 1) ? index + width : -1);
				final double slope = ((east - west) + (south - north)) / (4.0 * step);
				final double shade = Math.max(0.45, Math.min(1.35, 1 + (slope * 2.5)));
				if (kind[index] == 3)
				{
					final int grey = clamp((int) (36 * shade));
					image.setRGB(px, py, (grey << 16) | (grey << 8) | clamp(grey + 6));
					continue;
				}
				int r = (int) ((46 + (t * 150)) * shade);
				int g = (int) ((66 + (t * 118)) * shade);
				int b = (int) ((42 + (t * 86)) * shade);
				if (kind[index] == 1)
				{
					r = (r + (3 * 190)) / 4;
					g = (g + (3 * 52)) / 4;
					b = (b + (3 * 44)) / 4;
				}
				image.setRGB(px, py, (clamp(r) << 16) | (clamp(g) << 8) | clamp(b));
			}
		}

		final ByteArrayOutputStream out = new ByteArrayOutputStream(width * height);
		ImageIO.write(image, "png", out);
		return out.toByteArray();
	}

	/** @return the height of a neighbouring pixel for the shading, or the pixel's own when the neighbour is on the other side of a level's edge (or outside the picture) */
	private static int level(int[] heights, byte[] kind, int index, int neighbour)
	{
		return (neighbour >= 0) && ((kind[neighbour] == 3) == (kind[index] == 3)) && (kind[neighbour] != 2) ? heights[neighbour] : heights[index];
	}

	private static int clamp(int value)
	{
		return Math.max(0, Math.min(255, value));
	}

	// ---------------------------------------------------------------------------------------------
	// Editing
	// ---------------------------------------------------------------------------------------------

	private void handleSave(HttpExchange exchange) throws IOException
	{
		final PassiveTreeApiServer server = PassiveTreeApiServer.getInstance();
		final Player gm = getGm(exchange);
		if (gm == null)
		{
			return;
		}

		final byte[] body = server.readBody(exchange, MAX_BODY_BYTES);
		Map<?, ?> json = null;
		try
		{
			final Object parsed = body != null ? SimpleJson.parse(new String(body, StandardCharsets.UTF_8)) : null;
			json = parsed instanceof Map ? (Map<?, ?>) parsed : null;
		}
		catch (IllegalArgumentException e)
		{
			// answered below
		}
		if (json == null)
		{
			sendError(exchange, "the map could not be read");
			return;
		}

		final List<String> warnings = new ArrayList<>();
		final ConquestMap map;
		try
		{
			map = readMap(json, warnings);
		}
		catch (IllegalArgumentException e)
		{
			sendError(exchange, e.getMessage());
			return;
		}

		final ConquestMapData data = ConquestMapData.getInstance();
		final String previousId = asString(json.get("previousId"));
		final boolean renamed = (previousId != null) && !previousId.isEmpty() && !previousId.equals(map.id());
		if (((previousId == null) || previousId.isEmpty() || renamed) && (data.getMap(map.id()) != null))
		{
			sendError(exchange, "there is already a map " + map.id());
			return;
		}

		try
		{
			data.save(map);
			if (renamed && (data.getMap(previousId) != null))
			{
				data.delete(previousId);
				ConquestMarkers.getInstance().refresh(previousId);
			}
		}
		catch (IOException | IllegalArgumentException e)
		{
			LOGGER.warning(getClass().getSimpleName() + ": Could not save the Conquest map " + map.id() + " - " + e.getMessage());
			sendError(exchange, "could not save: " + e.getMessage());
			return;
		}
		ConquestMarkers.getInstance().refresh(map.id());
		LOGGER.info(getClass().getSimpleName() + ": " + gm.getName() + " saved the Conquest map " + map.id() + (renamed ? " (was " + previousId + ")" : "") + ".");

		// Also to a file of the GM's choice (the live map stays the one in data/conquest).
		String copiedTo = null;
		final String saveTo = asString(json.get("saveTo")) != null ? asString(json.get("saveTo")).trim() : "";
		if (!saveTo.isEmpty())
		{
			try
			{
				copiedTo = data.writeCopy(map, Path.of(saveTo)).toString();
				LOGGER.info(getClass().getSimpleName() + ": " + gm.getName() + " also wrote the Conquest map " + map.id() + " to " + copiedTo + ".");
			}
			catch (Exception e)
			{
				warnings.add(0, "Not written to " + saveTo + ": " + e.getMessage() + " (the map itself was saved).");
			}
		}

		final StringBuilder answer = new StringBuilder(2048);
		answer.append("{\"map\":");
		appendMap(answer, map);
		answer.append(",\"copiedTo\":").append(quoteOrNull(copiedTo));
		answer.append(",\"warnings\":[");
		for (int i = 0; i < warnings.size(); i++)
		{
			answer.append(i > 0 ? "," : "").append(SimpleJson.quote(warnings.get(i)));
		}
		answer.append("]}");
		server.sendJson(exchange, 200, answer.toString());
	}

	/**
	 * Reads a map sent by the page, and puts every point on the ground of the geodata (the layer nearest the height it had).
	 * @param json the map
	 * @param warnings where to add what is allowed but likely wrong
	 * @return the map
	 * @throws IllegalArgumentException with what is wrong, if it can't be saved
	 */
	private static ConquestMap readMap(Map<?, ?> json, List<String> warnings)
	{
		final String id = asString(json.get("id")) != null ? asString(json.get("id")).trim().toLowerCase() : "";
		if (!ConquestMapData.isValidId(id))
		{
			throw new IllegalArgumentException("the id must be 1-40 lower case letters, digits or _ (it is the file name)");
		}
		String name = asString(json.get("name")) != null ? asString(json.get("name")).trim() : "";
		if (name.isEmpty())
		{
			name = id;
		}
		if (name.length() > MAX_NAME)
		{
			throw new IllegalArgumentException("the name is longer than " + MAX_NAME + " characters");
		}
		final String description = asString(json.get("description")) != null ? asString(json.get("description")).trim() : "";
		if (description.length() > MAX_DESCRIPTION)
		{
			throw new IllegalArgumentException("the description is longer than " + MAX_DESCRIPTION + " characters");
		}

		final int minX = asInt(json.get("minX"));
		final int minY = asInt(json.get("minY"));
		final int maxX = asInt(json.get("maxX"));
		final int maxY = asInt(json.get("maxY"));
		if (((maxX - minX) < MIN_AREA) || ((maxY - minY) < MIN_AREA) || ((maxX - minX) > MAX_AREA) || ((maxY - minY) > MAX_AREA))
		{
			throw new IllegalArgumentException("the area must be " + MIN_AREA + " to " + MAX_AREA + " wide and high");
		}
		if ((minX < World.WORLD_X_MIN) || (minY < World.WORLD_Y_MIN) || (maxX >= World.WORLD_X_MAX) || (maxY >= World.WORLD_Y_MAX))
		{
			throw new IllegalArgumentException("the area is outside the world");
		}

		final GeoEngine geo = GeoEngine.getInstance();
		final Object pointList = json.get("points");
		final List<ConquestPoint> points = new ArrayList<>();
		final int[] counts = new int[ConquestPointType.values().length];
		final Set<String> captureNames = new HashSet<>();
		final int hintZ = json.get("z") instanceof Number ? asInt(json.get("z")) : 0;
		if (pointList instanceof List)
		{
			for (Object entry : (List<?>) pointList)
			{
				if (!(entry instanceof Map))
				{
					continue;
				}
				final Map<?, ?> p = (Map<?, ?>) entry;
				final ConquestPointType type = ConquestPointType.fromKey(asString(p.get("type")));
				if (type == null)
				{
					throw new IllegalArgumentException("a point is neither blue, red nor capture");
				}
				if (++counts[type.ordinal()] > ConquestMapData.MAX_POINTS)
				{
					throw new IllegalArgumentException("more than " + ConquestMapData.MAX_POINTS + " " + type.getKey() + " points");
				}
				final int x = asInt(p.get("x"));
				final int y = asInt(p.get("y"));
				if (!inWorld(x, y))
				{
					throw new IllegalArgumentException("a point is outside the world");
				}
				final int givenZ = p.get("z") instanceof Number ? asInt(p.get("z")) : hintZ;
				final int z = geo.hasGeo(x, y) ? geo.getHeight(x, y, givenZ) : givenZ;
				final int radius = Math.max(MIN_RADIUS, Math.min(MAX_RADIUS, p.get("radius") instanceof Number ? asInt(p.get("radius")) : type.getDefaultRadius()));
				final int heading = ((asInt(p.get("heading")) % 65536) + 65536) % 65536;
				String pointName = "";
				if (type == ConquestPointType.CAPTURE)
				{
					pointName = asString(p.get("name")) != null ? asString(p.get("name")).trim() : "";
					if (pointName.isEmpty())
					{
						pointName = nextCaptureName(captureNames, json.get("points"));
					}
					if (pointName.length() > MAX_POINT_NAME)
					{
						throw new IllegalArgumentException("the capture point name " + pointName + " is longer than " + MAX_POINT_NAME + " characters");
					}
					if (!captureNames.add(pointName.toUpperCase()))
					{
						throw new IllegalArgumentException("two capture points are called " + pointName);
					}
				}
				final ConquestPoint point = new ConquestPoint(type, pointName, x, y, z, heading, radius);
				points.add(point);

				if ((x < minX) || (x > maxX) || (y < minY) || (y > maxY))
				{
					warnings.add(describe(point, counts[type.ordinal()]) + " is outside the area.");
				}
				if (!geo.hasGeo(x, y))
				{
					warnings.add(describe(point, counts[type.ordinal()]) + " has no geodata under it.");
				}
				else if (!geo.checkNearestNswe(GeoEngine.getGeoX(x), GeoEngine.getGeoY(y), z, Cell.NSWE_ALL))
				{
					warnings.add(describe(point, counts[type.ordinal()]) + " is by a wall or a cliff edge: players can't walk off it in every direction.");
				}
			}
		}
		if (counts[ConquestPointType.BLUE.ordinal()] == 0)
		{
			warnings.add("The blue team has no spawn point: the event won't pick this map.");
		}
		if (counts[ConquestPointType.RED.ordinal()] == 0)
		{
			warnings.add("The red team has no spawn point: the event won't pick this map.");
		}
		if (counts[ConquestPointType.CAPTURE.ordinal()] == 0)
		{
			warnings.add("There is no capture point: the event won't pick this map.");
		}

		final List<ConquestDoor> doors = new ArrayList<>();
		final Set<Integer> doorIds = new HashSet<>();
		if (json.get("doors") instanceof List)
		{
			for (Object entry : (List<?>) json.get("doors"))
			{
				if (!(entry instanceof Map))
				{
					continue;
				}
				final int doorId = asInt(((Map<?, ?>) entry).get("id"));
				final Door door = DoorData.getInstance().getDoor(doorId);
				if (door == null)
				{
					warnings.add("There is no door " + doorId + ": it is left out.");
					continue;
				}
				if (!doorIds.add(doorId))
				{
					continue;
				}
				doors.add(new ConquestDoor(doorId, !Boolean.FALSE.equals(((Map<?, ?>) entry).get("open"))));
				if ((door.getX() < minX) || (door.getX() > maxX) || (door.getY() < minY) || (door.getY() > maxY))
				{
					warnings.add("Door " + doorId + " (" + door.getTemplate().getName() + ") is outside the area.");
				}
			}
		}

		final int maxPlayers = json.get("maxPlayers") instanceof Number ? asInt(json.get("maxPlayers")) : ConquestMapData.DEFAULT_MAX_PLAYERS;
		if ((maxPlayers < ConquestMapData.MIN_PLAYERS) || (maxPlayers > ConquestMapData.MAX_PLAYERS))
		{
			throw new IllegalArgumentException("the players per team must be " + ConquestMapData.MIN_PLAYERS + " to " + ConquestMapData.MAX_PLAYERS);
		}

		final int z = json.get("z") instanceof Number ? asInt(json.get("z")) : ConquestMapData.averageZ(points);
		return new ConquestMap(id, name, !Boolean.FALSE.equals(json.get("enabled")), maxPlayers, description, minX, minY, maxX, maxY, z, points, doors);
	}

	/** @return the first letter A-Z (then A2, B2...) no capture point of the map is called yet */
	private static String nextCaptureName(Set<String> taken, Object allPoints)
	{
		final Set<String> used = new HashSet<>(taken);
		if (allPoints instanceof List)
		{
			for (Object entry : (List<?>) allPoints)
			{
				if ((entry instanceof Map) && (asString(((Map<?, ?>) entry).get("name")) != null))
				{
					used.add(asString(((Map<?, ?>) entry).get("name")).trim().toUpperCase());
				}
			}
		}
		for (int round = 1;; round++)
		{
			for (char letter = 'A'; letter <= 'Z'; letter++)
			{
				final String name = round == 1 ? String.valueOf(letter) : letter + String.valueOf(round);
				if (!used.contains(name))
				{
					return name;
				}
			}
		}
	}

	private static String describe(ConquestPoint point, int number)
	{
		if (point.type() == ConquestPointType.CAPTURE)
		{
			return "Capture point " + point.name();
		}
		return (point.type() == ConquestPointType.BLUE ? "Blue" : "Red") + " spawn " + number;
	}

	private void handleDelete(HttpExchange exchange) throws IOException
	{
		final PassiveTreeApiServer server = PassiveTreeApiServer.getInstance();
		final Player gm = getGm(exchange);
		if (gm == null)
		{
			return;
		}

		final String id = server.readParams(exchange).get("id");
		final List<String> lines = new ArrayList<>();
		try
		{
			if ((id != null) && ConquestMapData.getInstance().delete(id))
			{
				ConquestMarkers.getInstance().refresh(id);
				LOGGER.info(getClass().getSimpleName() + ": " + gm.getName() + " deleted the Conquest map " + id + ".");
				lines.add("Deleted " + id + " (its file is now data/conquest/" + id + ".xml.deleted).");
			}
			else
			{
				lines.add("There is no map " + id + ".");
			}
		}
		catch (IOException e)
		{
			sendError(exchange, "could not delete: " + e.getMessage());
			return;
		}
		sendLines(exchange, lines);
	}

	private void handleTeleport(HttpExchange exchange) throws IOException
	{
		final PassiveTreeApiServer server = PassiveTreeApiServer.getInstance();
		final Player gm = getGm(exchange);
		if (gm == null)
		{
			return;
		}

		final Map<String, String> params = server.readParams(exchange);
		final int x = parseInt(params.get("x"), Integer.MIN_VALUE);
		final int y = parseInt(params.get("y"), Integer.MIN_VALUE);
		if (!inWorld(x, y))
		{
			sendError(exchange, "that place is outside the world");
			return;
		}
		final GeoEngine geo = GeoEngine.getInstance();
		final int givenZ = parseInt(params.get("z"), gm.getZ());
		final int z = geo.hasGeo(x, y) ? geo.getHeight(x, y, givenZ) : givenZ;
		final int heading = parseInt(params.get("heading"), gm.getHeading());
		ThreadPool.execute(() -> gm.teleToLocation(x, y, z, heading, 0));
		final List<String> lines = new ArrayList<>();
		final String label = params.get("label");
		lines.add(gm.getName() + " goes to " + ((label != null) && !label.isEmpty() ? label + " (" : "") + x + ", " + y + ", " + z + ((label != null) && !label.isEmpty() ? ")" : "") + ".");
		sendLines(exchange, lines);
	}

	private void handleShow(HttpExchange exchange) throws IOException
	{
		final PassiveTreeApiServer server = PassiveTreeApiServer.getInstance();
		final Player gm = getGm(exchange);
		if (gm == null)
		{
			return;
		}

		final ConquestMap map = ConquestMapData.getInstance().getMap(server.readParams(exchange).get("id"));
		if (map == null)
		{
			sendError(exchange, "save the map first: only saved maps are drawn in game");
			return;
		}
		ConquestMarkers.getInstance().show(gm, map);
		final List<String> lines = new ArrayList<>();
		lines.add(map.name() + " is drawn in game for " + gm.getName() + " (again after every save).");
		sendLines(exchange, lines);
	}

	private void handleHide(HttpExchange exchange) throws IOException
	{
		final Player gm = getGm(exchange);
		if (gm == null)
		{
			return;
		}

		ConquestMarkers.getInstance().hide(gm);
		final List<String> lines = new ArrayList<>();
		lines.add("The waypoints are gone from " + gm.getName() + "'s screen.");
		sendLines(exchange, lines);
	}

	private void handleReload(HttpExchange exchange) throws IOException
	{
		final Player gm = getGm(exchange);
		if (gm == null)
		{
			return;
		}

		ConquestMapData.getInstance().load();
		ConquestMarkers.getInstance().refreshAll();
		final List<String> lines = new ArrayList<>();
		lines.add("Read " + ConquestMapData.getInstance().getMaps().size() + " map(s) from data/conquest again.");
		sendLines(exchange, lines);
	}

	// ---------------------------------------------------------------------------------------------
	// The running match
	// ---------------------------------------------------------------------------------------------

	private void handleLive(HttpExchange exchange) throws IOException
	{
		final Player gm = getGm(exchange);
		if (gm == null)
		{
			return;
		}

		final ConquestManager manager = ConquestManager.getInstance();
		final ConquestMatch match = manager.getMatch();
		final StringBuilder json = new StringBuilder(match != null ? 32768 : 512);
		json.append("{\"admin\":").append(SimpleJson.quote(gm.getName()));
		json.append(",\"watching\":").append((match != null) && (gm.getInstanceId() == match.getInstanceId()));
		json.append(",\"gm\":{\"x\":").append(gm.getX()).append(",\"y\":").append(gm.getY()).append(",\"instance\":").append(gm.getInstanceId()).append('}');
		json.append(",\"defaults\":{\"levels\":").append(SimpleJson.quote(ConquestConfig.MIN_LEVEL + "-" + ConquestConfig.MAX_LEVEL)).append(",\"startScore\":").append(ConquestConfig.START_SCORE).append('}');
		json.append(",\"maps\":[");
		boolean first = true;
		for (ConquestMap map : ConquestMapData.getInstance().getEnabledMaps())
		{
			json.append(first ? "" : ",").append("{\"id\":").append(SimpleJson.quote(map.id())).append(",\"name\":").append(SimpleJson.quote(map.name())).append(",\"maxPlayers\":").append(map.maxPlayers()).append('}');
			first = false;
		}
		json.append(']');
		json.append(",\"running\":").append(match != null);
		if (match != null)
		{
			final ConquestMap map = match.getMap();
			json.append(",\"match\":{\"id\":").append(match.getId());
			json.append(",\"map\":{\"id\":").append(SimpleJson.quote(map.id())).append(",\"name\":").append(SimpleJson.quote(map.name()));
			json.append(",\"minX\":").append(map.minX()).append(",\"minY\":").append(map.minY()).append(",\"maxX\":").append(map.maxX()).append(",\"maxY\":").append(map.maxY()).append(",\"z\":").append(map.z()).append('}');
			json.append(",\"instance\":").append(match.getInstanceId());
			json.append(",\"phase\":").append(SimpleJson.quote(match.getPhase().name()));
			json.append(",\"time\":").append(match.getTime()).append(",\"timeLimit\":").append(match.getTimeLimit());
			json.append(",\"perTeam\":").append(match.getPerTeam()).append(",\"levels\":").append(SimpleJson.quote(match.getMinLevel() + "-" + match.getMaxLevel()));
			json.append(",\"pending\":").append(manager.getPendingSpawns());
			json.append(",\"navReady\":").append(match.getNav().isReady());
			json.append(",\"returned\":").append(match.getReturned());
			json.append(",\"unstuck\":").append(match.getUnstuck());
			json.append(",\"tickMs\":").append(Math.round(match.getTickMillis() * 10) / 10.0).append(",\"commandMs\":").append(Math.round(match.getCommandMillis() * 10) / 10.0);
			json.append(",\"result\":").append(quoteOrNull(match.getResult()));
			json.append(",\"winner\":").append(match.getWinner() == null ? "null" : SimpleJson.quote(match.getWinner() == Team.BLUE ? "blue" : "red"));
			json.append(",\"teams\":[");
			appendTeam(json, match.getBlue());
			json.append(',');
			appendTeam(json, match.getRed());
			json.append("],\"points\":[");
			first = true;
			for (ConquestCapture capture : match.getCaptures())
			{
				json.append(first ? "" : ",");
				first = false;
				json.append("{\"name\":").append(SimpleJson.quote(capture.getName()));
				json.append(",\"x\":").append(capture.getLocation().getX()).append(",\"y\":").append(capture.getLocation().getY()).append(",\"radius\":").append(capture.getRadius());
				json.append(",\"owner\":").append(SimpleJson.quote(capture.getOwner() == Team.BLUE ? "blue" : capture.getOwner() == Team.RED ? "red" : "none"));
				json.append(",\"progress\":").append(Math.round(capture.getProgress() * 10) / 10.0);
				json.append(",\"blue\":").append(capture.getBlue()).append(",\"red\":").append(capture.getRed()).append(",\"stalemate\":").append(capture.isStalemate()).append('}');
			}
			json.append("],\"log\":[");
			first = true;
			for (ConquestMatch.LogEntry entry : match.getLog())
			{
				json.append(first ? "" : ",").append("{\"t\":").append(entry.time()).append(",\"text\":").append(SimpleJson.quote(entry.text())).append('}');
				first = false;
			}
			json.append("]}");
		}
		json.append('}');
		PassiveTreeApiServer.getInstance().sendJson(exchange, 200, json.toString());
	}

	/**
	 * A team: its score, its next wave, its parties with their orders, and every fighter as a row [id, x, y, alive, hp %, party, role, leader (1/0), name, build, level, kills, deaths].
	 */
	private static void appendTeam(StringBuilder json, ConquestTeam team)
	{
		json.append("{\"team\":").append(SimpleJson.quote(team.getTeam() == Team.BLUE ? "blue" : "red"));
		json.append(",\"name\":").append(SimpleJson.quote(team.getName()));
		json.append(",\"commander\":").append(SimpleJson.quote(team.getCommander().getName()));
		json.append(",\"score\":").append(team.getScore()).append(",\"drain\":").append(team.getDrain()).append(",\"held\":").append(team.getHeld());
		json.append(",\"alive\":").append(team.getAlive()).append(",\"total\":").append(team.getFighters().size()).append(",\"kills\":").append(team.getKills());
		final long now = System.currentTimeMillis();
		final long wave = team.getNextWave(now);
		json.append(",\"wave\":");
		if (wave > 0)
		{
			json.append("{\"in\":").append((int) Math.ceil((wave - now) / 1000.0)).append(",\"waiting\":").append(team.getWaiting()).append(",\"at\":[");
			boolean firstBase = true;
			for (Location base : team.getWaveBases(wave))
			{
				json.append(firstBase ? "" : ",").append('[').append(base.getX()).append(',').append(base.getY()).append(']');
				firstBase = false;
			}
			json.append("]}");
		}
		else
		{
			json.append("null");
		}
		json.append(",\"spawns\":[");
		boolean firstSpawn = true;
		for (ConquestPoint spawn : team.getSpawns())
		{
			json.append(firstSpawn ? "" : ",").append('[').append(spawn.x()).append(',').append(spawn.y()).append(',').append(spawn.radius()).append(']');
			firstSpawn = false;
		}
		json.append(']');
		json.append(",\"parties\":[");
		boolean first = true;
		for (ConquestParty party : team.getParties())
		{
			json.append(first ? "" : ",");
			first = false;
			json.append("{\"id\":").append(party.getId()).append(",\"alive\":").append(party.getAlive());
			final Location at = party.getLocation();
			json.append(",\"leader\":").append(at == null ? "null" : "{\"x\":" + at.getX() + ",\"y\":" + at.getY() + "}");
			final ConquestParty.Order order = party.getOrder();
			if (order != null)
			{
				json.append(",\"order\":{\"kind\":").append(SimpleJson.quote(order.kind().name())).append(",\"point\":").append(quoteOrNull(order.point()));
				json.append(",\"x\":").append(order.target().getX()).append(",\"y\":").append(order.target().getY()).append('}');
			}
			json.append('}');
		}
		json.append("],\"fighters\":[");
		first = true;
		for (ConquestFighter fighter : team.getFighters())
		{
			final Npc npc = fighter.getNpc();
			final ConquestParty party = fighter.getParty();
			final FakePlayerPvpProfile profile = npc.getTemplate().getFakePlayerPvpProfile();
			json.append(first ? "" : ",");
			first = false;
			json.append('[').append(npc.getObjectId()).append(',').append(npc.getX()).append(',').append(npc.getY());
			json.append(',').append(fighter.isAlive() ? 1 : 0);
			json.append(',').append((int) Math.round((100 * npc.getCurrentHp()) / Math.max(1, npc.getMaxHp())));
			json.append(',').append(party != null ? party.getId() : 0);
			json.append(',').append(SimpleJson.quote(fighter.getRole().name().toLowerCase()));
			json.append(',').append((party != null) && (party.getLeader() == npc) ? 1 : 0);
			json.append(',').append(SimpleJson.quote(npc.getName()));
			json.append(',').append(SimpleJson.quote((profile != null) && (profile.getBuild() != null) ? profile.getBuild().getName() : ""));
			json.append(',').append(npc.getLevel()).append(',').append(fighter.getKills()).append(',').append(fighter.getDeaths()).append(']');
		}
		json.append("]}");
	}

	private void handleMatchStart(HttpExchange exchange) throws IOException
	{
		final PassiveTreeApiServer server = PassiveTreeApiServer.getInstance();
		final Player gm = getGm(exchange);
		if (gm == null)
		{
			return;
		}

		final Map<String, String> params = server.readParams(exchange);
		final ConquestMap map = ConquestMapData.getInstance().getMap(params.get("map"));
		if (map == null)
		{
			sendError(exchange, "there is no map " + params.get("map"));
			return;
		}
		final int perTeam = parseInt(params.get("perTeam"), map.maxPlayers());
		final String levelText = params.get("levels");
		final int[] levels = (levelText != null) && !levelText.trim().isEmpty() ? ConquestConfig.parseLevels(levelText) : new int[]
		{
			ConquestConfig.MIN_LEVEL,
			ConquestConfig.MAX_LEVEL
		};
		final List<String> lines = new ArrayList<>();
		lines.add(ConquestManager.getInstance().start(map, perTeam, levels[0], levels[1]));
		LOGGER.info(getClass().getSimpleName() + ": " + gm.getName() + " started a Conquest match on " + map.id() + " from the web page.");
		sendLines(exchange, lines);
	}

	private void handleMatchStop(HttpExchange exchange) throws IOException
	{
		if (getGm(exchange) == null)
		{
			return;
		}
		final List<String> lines = new ArrayList<>();
		lines.add(ConquestManager.getInstance().stop());
		sendLines(exchange, lines);
	}

	private void handleMatchWatch(HttpExchange exchange) throws IOException
	{
		final Player gm = getGm(exchange);
		if (gm == null)
		{
			return;
		}
		final List<String> lines = new ArrayList<>();
		lines.add(ConquestManager.getInstance().watch(gm));
		sendLines(exchange, lines);
	}

	private void handleMatchLeave(HttpExchange exchange) throws IOException
	{
		final Player gm = getGm(exchange);
		if (gm == null)
		{
			return;
		}
		final List<String> lines = new ArrayList<>();
		lines.add(ConquestManager.getInstance().leave(gm));
		sendLines(exchange, lines);
	}

	// ---------------------------------------------------------------------------------------------
	// Helpers
	// ---------------------------------------------------------------------------------------------

	/** @return the GM of the request's token, {@code null} after the error answer was sent */
	private static Player getGm(HttpExchange exchange) throws IOException
	{
		return PassiveTreeApiServer.getInstance().authenticateAdmin(exchange, ADMIN_COMMAND);
	}

	private static boolean inWorld(int x, int y)
	{
		return (x >= World.WORLD_X_MIN) && (y >= World.WORLD_Y_MIN) && (x < World.WORLD_X_MAX) && (y < World.WORLD_Y_MAX);
	}

	private static void sendError(HttpExchange exchange, String message) throws IOException
	{
		PassiveTreeApiServer.getInstance().sendText(exchange, 400, "application/json", "{\"error\":" + SimpleJson.quote(message) + "}");
	}

	private static void sendLines(HttpExchange exchange, List<String> lines) throws IOException
	{
		final StringBuilder json = new StringBuilder("{\"lines\":[");
		for (int i = 0; i < lines.size(); i++)
		{
			json.append(i > 0 ? "," : "").append(SimpleJson.quote(lines.get(i)));
		}
		json.append("]}");
		PassiveTreeApiServer.getInstance().sendJson(exchange, 200, json.toString());
	}

	private static String quoteOrNull(String text)
	{
		return text != null ? SimpleJson.quote(text) : "null";
	}

	private static String asString(Object value)
	{
		return value != null ? String.valueOf(value) : null;
	}

	private static int asInt(Object value)
	{
		return value instanceof Number ? (int) Math.round(((Number) value).doubleValue()) : 0;
	}

	private static int parseInt(String text, int fallback)
	{
		if (text == null)
		{
			return fallback;
		}
		try
		{
			return (int) Math.round(Double.parseDouble(text.trim()));
		}
		catch (NumberFormatException e)
		{
			return fallback;
		}
	}

	public static ConquestWeb getInstance()
	{
		return SingletonHolder.INSTANCE;
	}

	private static class SingletonHolder
	{
		protected static final ConquestWeb INSTANCE = new ConquestWeb();
	}
}
