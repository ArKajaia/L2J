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

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.logging.Logger;

import org.l2jmobius.commons.threads.ThreadPool;
import org.l2jmobius.commons.util.SimpleJson;
import org.l2jmobius.gameserver.config.custom.TeamVsTeamConfig;
import org.l2jmobius.gameserver.data.xml.DoorData;
import org.l2jmobius.gameserver.data.xml.TvTMapData;
import org.l2jmobius.gameserver.geoengine.GeoEngine;
import org.l2jmobius.gameserver.geoengine.geodata.Cell;
import org.l2jmobius.gameserver.model.Location;
import org.l2jmobius.gameserver.model.World;
import org.l2jmobius.gameserver.model.actor.Player;
import org.l2jmobius.gameserver.model.actor.instance.Door;
import org.l2jmobius.gameserver.model.tvt.TvTBarrier;
import org.l2jmobius.gameserver.model.tvt.TvTDoor;
import org.l2jmobius.gameserver.model.tvt.TvTMap;

import com.sun.net.httpserver.HttpExchange;

import custom.Conquest.ConquestWeb;
import custom.PassiveSkillTree.PassiveTreeApiServer;

/**
 * The GM page of the Team vs Team maps ({@link TvTMapData}), served by the passive tree's web server like the Conquest one. A GM opens it with //tvt map; the link works while that GM is online and allowed to use //tvt.
 * <ul>
 * <li>GET /tvt.html - the page</li>
 * <li>GET /api/tvt/maps - every map with its spawn points, fence and doors, the base and spawn radius of TeamVsTeam.ini, and which map is drawn in game for the GM</li>
 * <li>GET /api/tvt/gm - where the GM is now</li>
 * <li>GET /api/tvt/doors?minX=&amp;minY=&amp;maxX=&amp;maxY= - the doors in an area (data/Doors.xml), with their shape and whether they are open by default</li>
 * <li>GET /api/tvt/terrain?minX=&amp;minY=&amp;maxX=&amp;maxY=&amp;z= - a picture of the ground from the geodata, seen from above (as the Conquest page draws it)</li>
 * <li>GET /api/tvt/ground?x=&amp;y=&amp;z= - the height of the ground at a spot (the layer nearest z), for a point the page moved</li>
 * <li>POST /api/tvt/save (JSON: the map, previousId when it was renamed, saveTo for a file to write a copy to) - checks the map, puts every spawn point on the ground, writes data/tvt/&lt;id&gt;.xml (and the copy) and draws it again for the GMs who show
 * it</li>
 * <li>POST /api/tvt/delete (id) - takes a map out (its file becomes &lt;id&gt;.xml.deleted)</li>
 * <li>POST /api/tvt/teleport (x, y, z, heading) - takes the GM there</li>
 * <li>POST /api/tvt/show (id), /hide - draws a saved map in game for the GM (see {@link TvTMarkers}), or takes it away</li>
 * <li>POST /api/tvt/reload - reads the map files again, after they were edited by hand</li>
 * </ul>
 * @author Claude
 */
public class TvTWeb
{
	private static final Logger LOGGER = Logger.getLogger(TvTWeb.class.getName());

	private static final Path HTML_FILE = Path.of("data/html/custom/tvt.html");
	/** The command a GM's access level must allow. */
	private static final String ADMIN_COMMAND = "admin_tvt";
	private static final int MAX_BODY_BYTES = 128 * 1024;

	/** A battlefield is at least this wide and at most this wide (about two geodata regions). */
	private static final int MIN_AREA = 1000;
	private static final int MAX_AREA = 65536;
	private static final int MAX_NAME = 60;
	private static final int MAX_DESCRIPTION = 2000;

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

	private TvTWeb()
	{
	}

	/**
	 * Adds the TvT map page's paths to the passive tree's web server. Called once its server is running.
	 */
	public void register()
	{
		final PassiveTreeApiServer server = PassiveTreeApiServer.getInstance();
		server.addRoute("/tvt.html", "GET", false, exchange -> server.sendPage(exchange, HTML_FILE));
		server.addRoute("/api/tvt/maps", "GET", false, this::handleMaps);
		server.addRoute("/api/tvt/gm", "GET", false, this::handleGm);
		server.addRoute("/api/tvt/ground", "GET", false, this::handleGround);
		server.addRoute("/api/tvt/terrain", "GET", false, this::handleTerrain);
		server.addRoute("/api/tvt/doors", "GET", false, this::handleDoors);
		server.addRoute("/api/tvt/save", "POST", true, this::handleSave);
		server.addRoute("/api/tvt/delete", "POST", true, this::handleDelete);
		server.addRoute("/api/tvt/teleport", "POST", true, this::handleTeleport);
		server.addRoute("/api/tvt/show", "POST", true, this::handleShow);
		server.addRoute("/api/tvt/hide", "POST", true, this::handleHide);
		server.addRoute("/api/tvt/reload", "POST", true, this::handleReload);
	}

	/**
	 * @param gm the GM
	 * @return the address of the TvT map page for that GM
	 */
	public static String getUrl(Player gm)
	{
		final PassiveTreeApiServer server = PassiveTreeApiServer.getInstance();
		return server.getPageUrl("tvt.html") + "?admin=" + server.generateAdminToken(gm.getObjectId());
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

		final List<TvTMap> maps = TvTMapData.getInstance().getMaps();
		final StringBuilder json = new StringBuilder(4096);
		json.append("{\"admin\":").append(SimpleJson.quote(gm.getName()));
		json.append(",\"shown\":").append(quoteOrNull(TvTMarkers.getInstance().getShown(gm)));
		json.append(",\"maxPoints\":").append(TvTMapData.MAX_SPAWNS);
		json.append(",\"baseRadius\":").append(TeamVsTeamConfig.BASE_RADIUS).append(",\"spawnRadius\":").append(TeamVsTeamConfig.SPAWN_RADIUS);
		json.append(",\"teamSize\":").append(TeamVsTeamConfig.TEAM_SIZE).append(",\"minBarrier\":").append(TvTBarrier.MIN_RADIUS);
		json.append(",\"world\":[").append(World.WORLD_X_MIN).append(',').append(World.WORLD_Y_MIN).append(',').append(World.WORLD_X_MAX).append(',').append(World.WORLD_Y_MAX).append(']');
		json.append(",\"allowed\":[");
		boolean first = true;
		for (String id : TeamVsTeamConfig.MAPS)
		{
			json.append(first ? "" : ",").append(SimpleJson.quote(id));
			first = false;
		}
		json.append("],\"enabled\":[");
		first = true;
		for (TvTMap map : TvTMapData.getInstance().getEnabledMaps())
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
		json.append(",\"shown\":").append(quoteOrNull(TvTMarkers.getInstance().getShown(gm))).append('}');
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
		final int hint = parseInt(params.get("z"), 0);
		final GeoEngine geo = GeoEngine.getInstance();
		final boolean hasGeo = geo.hasGeo(x, y);
		final int z = hasGeo ? geo.getHeight(x, y, hint) : hint;
		server.sendJson(exchange, 200, "{\"x\":" + x + ",\"y\":" + y + ",\"z\":" + z + ",\"geo\":" + hasGeo + ",\"open\":" + isOpen(x, y, z) + "}");
	}

	private static void appendMap(StringBuilder json, TvTMap map)
	{
		json.append("{\"id\":").append(SimpleJson.quote(map.id()));
		json.append(",\"name\":").append(SimpleJson.quote(map.name()));
		json.append(",\"enabled\":").append(map.enabled());
		json.append(",\"description\":").append(SimpleJson.quote(map.description()));
		json.append(",\"minX\":").append(map.minX()).append(",\"minY\":").append(map.minY()).append(",\"maxX\":").append(map.maxX()).append(",\"maxY\":").append(map.maxY()).append(",\"z\":").append(map.z());
		json.append(",\"points\":[");
		boolean first = true;
		for (boolean blue : new boolean[]
		{
			true,
			false
		})
		{
			for (Location spawn : map.getSpawns(blue))
			{
				json.append(first ? "" : ",");
				first = false;
				json.append("{\"type\":").append(blue ? "\"blue\"" : "\"red\"");
				json.append(",\"x\":").append(spawn.getX()).append(",\"y\":").append(spawn.getY()).append(",\"z\":").append(spawn.getZ()).append(",\"heading\":").append(spawn.getHeading());
				// Whether a player can stand there and walk off in every direction: not in a wall, on a cliff edge or out of the geodata.
				json.append(",\"open\":").append(isOpen(spawn.getX(), spawn.getY(), spawn.getZ())).append('}');
			}
		}
		json.append("],\"barrier\":");
		final TvTBarrier barrier = map.barrier();
		if (barrier == null)
		{
			json.append("null");
		}
		else
		{
			json.append("{\"x\":").append(barrier.x()).append(",\"y\":").append(barrier.y()).append(",\"radiusX\":").append(barrier.radiusX()).append(",\"radiusY\":").append(barrier.radiusY()).append('}');
		}
		json.append(",\"doors\":[");
		first = true;
		for (TvTDoor door : map.doors())
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
			json.append(",\"defaultOpen\":").append(door.getTemplate().isOpenByDefault());
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
			sendError(exchange, "the area must be 100 to " + (MAX_AREA * 2) + " wide");
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
			png = ConquestWeb.renderTerrain(minX, minY, maxX, maxY, z);
			synchronized (_terrain)
			{
				_terrain.put(key, png);
			}
		}
		server.sendStatic(exchange, "image/png", png, 300);
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
		final TvTMap map;
		try
		{
			map = readMap(json, warnings);
		}
		catch (IllegalArgumentException e)
		{
			sendError(exchange, e.getMessage());
			return;
		}

		final TvTMapData data = TvTMapData.getInstance();
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
				TvTMarkers.getInstance().refresh(previousId);
			}
		}
		catch (IOException | IllegalArgumentException e)
		{
			LOGGER.warning(getClass().getSimpleName() + ": Could not save the TvT map " + map.id() + " - " + e.getMessage());
			sendError(exchange, "could not save: " + e.getMessage());
			return;
		}
		TvTMarkers.getInstance().refresh(map.id());
		LOGGER.info(getClass().getSimpleName() + ": " + gm.getName() + " saved the TvT map " + map.id() + (renamed ? " (was " + previousId + ")" : "") + ".");

		// Also to a file of the GM's choice (the live map stays the one in data/tvt).
		String copiedTo = null;
		final String saveTo = asString(json.get("saveTo")) != null ? asString(json.get("saveTo")).trim() : "";
		if (!saveTo.isEmpty())
		{
			try
			{
				copiedTo = data.writeCopy(map, Path.of(saveTo)).toString();
				LOGGER.info(getClass().getSimpleName() + ": " + gm.getName() + " also wrote the TvT map " + map.id() + " to " + copiedTo + ".");
			}
			catch (Exception e)
			{
				warnings.add(0, "Not written to " + saveTo + ": " + e.getMessage() + " (the map itself was saved).");
			}
		}
		if (!TeamVsTeamConfig.MAPS.isEmpty() && !TeamVsTeamConfig.MAPS.contains(map.id()))
		{
			warnings.add("TvTMaps in TeamVsTeam.ini doesn't name " + map.id() + ": registrations don't pick it (//tvt open " + map.id() + " and //tvt simulate still can).");
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
	 * Reads a map sent by the page, and puts every spawn point on the ground of the geodata (the layer nearest the height it had).
	 * @param json the map
	 * @param warnings where to add what is allowed but likely wrong
	 * @return the map
	 * @throws IllegalArgumentException with what is wrong, if it can't be saved
	 */
	private static TvTMap readMap(Map<?, ?> json, List<String> warnings)
	{
		final String id = asString(json.get("id")) != null ? asString(json.get("id")).trim().toLowerCase() : "";
		if (!TvTMapData.isValidId(id))
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

		TvTBarrier barrier = null;
		if (json.get("barrier") instanceof Map)
		{
			final Map<?, ?> b = (Map<?, ?>) json.get("barrier");
			final int bx = asInt(b.get("x"));
			final int by = asInt(b.get("y"));
			final int radiusX = asInt(b.get("radiusX"));
			final int radiusY = asInt(b.get("radiusY"));
			if ((radiusX < TvTBarrier.MIN_RADIUS) || (radiusY < TvTBarrier.MIN_RADIUS) || (radiusX > MAX_AREA) || (radiusY > MAX_AREA))
			{
				throw new IllegalArgumentException("the fence's radii must be " + TvTBarrier.MIN_RADIUS + " to " + MAX_AREA);
			}
			if (!inWorld(bx, by))
			{
				throw new IllegalArgumentException("the fence's middle is outside the world");
			}
			barrier = new TvTBarrier(bx, by, radiusX, radiusY);
			if ((bx - radiusX < minX) || (bx + radiusX > maxX) || (by - radiusY < minY) || (by + radiusY > maxY))
			{
				warnings.add("The fence reaches beyond the area: the radar shows the area as the battlefield's edge.");
			}
		}

		final GeoEngine geo = GeoEngine.getInstance();
		final int hintZ = json.get("z") instanceof Number ? asInt(json.get("z")) : 0;
		final List<Location> blue = new ArrayList<>();
		final List<Location> red = new ArrayList<>();
		if (json.get("points") instanceof List)
		{
			for (Object entry : (List<?>) json.get("points"))
			{
				if (!(entry instanceof Map))
				{
					continue;
				}
				final Map<?, ?> p = (Map<?, ?>) entry;
				final String type = asString(p.get("type")) != null ? asString(p.get("type")).toLowerCase() : "";
				final List<Location> spawns = type.equals("blue") ? blue : type.equals("red") ? red : null;
				if (spawns == null)
				{
					throw new IllegalArgumentException("a spawn point is neither blue nor red");
				}
				if (spawns.size() >= TvTMapData.MAX_SPAWNS)
				{
					throw new IllegalArgumentException("more than " + TvTMapData.MAX_SPAWNS + " " + type + " spawn points");
				}
				final int x = asInt(p.get("x"));
				final int y = asInt(p.get("y"));
				if (!inWorld(x, y))
				{
					throw new IllegalArgumentException("a spawn point is outside the world");
				}
				final int givenZ = p.get("z") instanceof Number ? asInt(p.get("z")) : hintZ;
				final int z = geo.hasGeo(x, y) ? geo.getHeight(x, y, givenZ) : givenZ;
				final int heading = ((asInt(p.get("heading")) % 65536) + 65536) % 65536;
				spawns.add(new Location(x, y, z, heading));

				final String label = (spawns == blue ? "Blue" : "Red") + " base " + spawns.size();
				if ((x < minX) || (x > maxX) || (y < minY) || (y > maxY))
				{
					warnings.add(label + " is outside the area.");
				}
				if (!geo.hasGeo(x, y))
				{
					warnings.add(label + " has no geodata under it.");
				}
				else if (!isOpen(x, y, z))
				{
					warnings.add(label + " is by a wall or a cliff edge: players can't walk off it in every direction.");
				}
				if ((barrier != null) && !barrier.contains(x, y, -Math.min(TeamVsTeamConfig.BASE_RADIUS, Math.min(barrier.radiusX(), barrier.radiusY()) - 1)))
				{
					warnings.add(label + (barrier.contains(x, y, 0) ? " is closer to the fence than its base's radius (" + TeamVsTeamConfig.BASE_RADIUS + "): part of the base is beyond it." : " is beyond the fence: whoever spawns there is brought back inside at once."));
				}
			}
		}
		if (blue.isEmpty())
		{
			warnings.add("Blue has no spawn point: the event won't pick this map.");
		}
		if (red.isEmpty())
		{
			warnings.add("Red has no spawn point: the event won't pick this map.");
		}
		for (int i = 0; i < blue.size(); i++)
		{
			for (int j = 0; j < red.size(); j++)
			{
				final double distance = Math.hypot(blue.get(i).getX() - red.get(j).getX(), blue.get(i).getY() - red.get(j).getY());
				if (distance < (TeamVsTeamConfig.BASE_RADIUS * 2))
				{
					warnings.add("Blue base " + (i + 1) + " and Red base " + (j + 1) + " overlap (" + (int) distance + " apart, a base is " + TeamVsTeamConfig.BASE_RADIUS + " round): whoever spawns there is stunned in the enemy base.");
				}
			}
		}

		final List<TvTDoor> doors = new ArrayList<>();
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
				doors.add(new TvTDoor(doorId, !Boolean.FALSE.equals(((Map<?, ?>) entry).get("open"))));
				if ((door.getX() < minX) || (door.getX() > maxX) || (door.getY() < minY) || (door.getY() > maxY))
				{
					warnings.add("Door " + doorId + " (" + door.getTemplate().getName() + ") is outside the area.");
				}
			}
		}

		final int z = json.get("z") instanceof Number ? asInt(json.get("z")) : TvTMapData.averageZ(blue, red);
		return new TvTMap(id, name, !Boolean.FALSE.equals(json.get("enabled")), description, minX, minY, maxX, maxY, z, blue, red, barrier, doors);
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
			if ((id != null) && TvTMapData.getInstance().delete(id))
			{
				TvTMarkers.getInstance().refresh(id);
				LOGGER.info(getClass().getSimpleName() + ": " + gm.getName() + " deleted the TvT map " + id + ".");
				lines.add("Deleted " + id + " (its file is now data/tvt/" + id + ".xml.deleted).");
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
		final boolean labelled = (label != null) && !label.isEmpty();
		lines.add(gm.getName() + " goes to " + (labelled ? label + " (" : "") + x + ", " + y + ", " + z + (labelled ? ")" : "") + ".");
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

		final TvTMap map = TvTMapData.getInstance().getMap(server.readParams(exchange).get("id"));
		if (map == null)
		{
			sendError(exchange, "save the map first: only saved maps are drawn in game");
			return;
		}
		TvTMarkers.getInstance().show(gm, map);
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

		TvTMarkers.getInstance().hide(gm);
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

		TvTMapData.getInstance().load();
		TvTMarkers.getInstance().refreshAll();
		final List<String> lines = new ArrayList<>();
		lines.add("Read " + TvTMapData.getInstance().getMaps().size() + " map(s) from data/tvt again.");
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

	/** @return {@code true} if a player can stand there and walk off in every direction: not in a wall, on a cliff edge or out of the geodata */
	private static boolean isOpen(int x, int y, int z)
	{
		final GeoEngine geo = GeoEngine.getInstance();
		return inWorld(x, y) && geo.hasGeo(x, y) && geo.checkNearestNswe(GeoEngine.getGeoX(x), GeoEngine.getGeoY(y), z, Cell.NSWE_ALL);
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

	public static TvTWeb getInstance()
	{
		return SingletonHolder.INSTANCE;
	}

	private static class SingletonHolder
	{
		protected static final TvTWeb INSTANCE = new TvTWeb();
	}
}
