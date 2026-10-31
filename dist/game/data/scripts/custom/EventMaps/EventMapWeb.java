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

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.logging.Logger;

import org.l2jmobius.commons.threads.ThreadPool;
import org.l2jmobius.commons.util.SimpleJson;
import org.l2jmobius.gameserver.config.custom.ConquestConfig;
import org.l2jmobius.gameserver.config.custom.RaceWarsConfig;
import org.l2jmobius.gameserver.config.custom.TeamVsTeamConfig;
import org.l2jmobius.gameserver.data.xml.DoorData;
import org.l2jmobius.gameserver.data.xml.EventMapData;
import org.l2jmobius.gameserver.geoengine.GeoEngine;
import org.l2jmobius.gameserver.geoengine.geodata.Cell;
import org.l2jmobius.gameserver.managers.EventMapScatter;
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

import com.sun.net.httpserver.HttpExchange;

import custom.Conquest.ConquestMarkers;
import custom.Conquest.ConquestWeb;
import custom.PassiveSkillTree.PassiveTreeApiServer;

/**
 * The GM page of the event maps ({@link EventMapData}, data/maps), shared by Team vs Team, Conquest and Deathmatch, served by the passive tree's web server. A GM opens it with //tvt map or //conquest; the link works while that GM is online and allowed to use
 * //tvt. A map is ticked for the events that may use it, and holds each event's own points.
 * <ul>
 * <li>GET /eventmaps.html - the page</li>
 * <li>GET /api/maps/maps - every map with every event's points, the limits and radii of the events, and which map is drawn in game for the GM</li>
 * <li>GET /api/maps/gm - where the GM is now</li>
 * <li>GET /api/maps/doors?minX=&amp;minY=&amp;maxX=&amp;maxY= - the doors in an area (data/Doors.xml), with their shape and whether they are open by default</li>
 * <li>GET /api/maps/terrain?minX=&amp;minY=&amp;maxX=&amp;maxY=&amp;z= - a picture of the ground from the geodata, seen from above</li>
 * <li>GET /api/maps/ground?x=&amp;y=&amp;z= - the height of the ground at a spot (the layer nearest z), for a point the page placed or moved</li>
 * <li>POST /api/maps/scatter (JSON: the map, count, gap) - spots of open ground picked by chance for Deathmatch spawn points (see {@link EventMapScatter})</li>
 * <li>POST /api/maps/save (JSON: the map, previousId when it was renamed, saveTo for a file to write a copy to) - checks the map, puts every point on the ground, writes data/maps/&lt;id&gt;.xml (and the copy) and draws it again for the GMs who show it</li>
 * <li>POST /api/maps/delete (id) - takes a map out (its file becomes &lt;id&gt;.xml.deleted)</li>
 * <li>POST /api/maps/teleport (x, y, z, heading) - takes the GM there</li>
 * <li>POST /api/maps/show (id, event), /hide - draws a saved map in game for the GM (see {@link EventMapMarkers}), or takes it away</li>
 * <li>POST /api/maps/reload - reads the map files again, after they were edited by hand</li>
 * </ul>
 * @author Claude
 */
public class EventMapWeb
{
	private static final Logger LOGGER = Logger.getLogger(EventMapWeb.class.getName());

	private static final Path HTML_FILE = Path.of("data/html/custom/eventmaps.html");
	/** The command a GM's access level must allow. */
	private static final String ADMIN_COMMAND = "admin_tvt";
	private static final int MAX_BODY_BYTES = 256 * 1024;

	/** A battlefield is at least this wide and at most this wide (about two geodata regions). */
	private static final int MIN_AREA = 1000;
	private static final int MAX_AREA = 65536;
	private static final int MIN_RADIUS = 50;
	private static final int MAX_RADIUS = 5000;
	private static final int MAX_NAME = 60;
	private static final int MAX_POINT_NAME = 12;
	private static final int MAX_DESCRIPTION = 2000;
	/** Deathmatch spawn points closer than this are worth a warning: a fighter may spawn next to another. */
	private static final int DEATHMATCH_CLOSE = 300;
	/** Race Wars start points closer than this are worth a warning: the armies would see each other before the fight (a fake player sees 1200 far). */
	private static final int RACEWARS_CLOSE = 3000;
	/** A scatter asks for at most so many spots. */
	private static final int MAX_SCATTER = 60;

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

	private EventMapWeb()
	{
	}

	/**
	 * Adds the event map page's paths to the passive tree's web server. Called once its server is running.
	 */
	public void register()
	{
		final PassiveTreeApiServer server = PassiveTreeApiServer.getInstance();
		server.addRoute("/eventmaps.html", "GET", false, exchange -> server.sendPage(exchange, HTML_FILE));
		server.addRoute("/api/maps/maps", "GET", false, this::handleMaps);
		server.addRoute("/api/maps/gm", "GET", false, this::handleGm);
		server.addRoute("/api/maps/ground", "GET", false, this::handleGround);
		server.addRoute("/api/maps/terrain", "GET", false, this::handleTerrain);
		server.addRoute("/api/maps/doors", "GET", false, this::handleDoors);
		server.addRoute("/api/maps/scatter", "POST", true, this::handleScatter);
		server.addRoute("/api/maps/save", "POST", true, this::handleSave);
		server.addRoute("/api/maps/delete", "POST", true, this::handleDelete);
		server.addRoute("/api/maps/teleport", "POST", true, this::handleTeleport);
		server.addRoute("/api/maps/show", "POST", true, this::handleShow);
		server.addRoute("/api/maps/hide", "POST", true, this::handleHide);
		server.addRoute("/api/maps/reload", "POST", true, this::handleReload);
	}

	/**
	 * @param gm the GM
	 * @param type the event whose points the page opens on, {@code null} for Team vs Team
	 * @return the address of the event map page for that GM
	 */
	public static String getUrl(Player gm, EventMapType type)
	{
		final PassiveTreeApiServer server = PassiveTreeApiServer.getInstance();
		return server.getPageUrl("eventmaps.html") + "?admin=" + server.generateAdminToken(gm.getObjectId()) + "&event=" + (type != null ? type.getKey() : EventMapType.TVT.getKey());
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

		final EventMapData data = EventMapData.getInstance();
		final List<EventMap> maps = data.getMaps();
		final StringBuilder json = new StringBuilder(8192);
		json.append("{\"admin\":").append(SimpleJson.quote(gm.getName()));
		json.append(",\"shown\":").append(quoteOrNull(EventMapMarkers.getInstance().getShown(gm)));
		json.append(",\"limits\":{\"tvt\":").append(EventMapData.MAX_TVT_SPAWNS).append(",\"conquest\":").append(EventMapData.MAX_CONQUEST_POINTS).append(",\"deathmatch\":").append(EventMapData.MAX_DEATHMATCH_SPAWNS);
		json.append(",\"deathmatchMin\":").append(EventMap.MIN_DEATHMATCH_SPAWNS).append(",\"scatterMax\":").append(MAX_SCATTER);
		json.append(",\"racewarsDuel\":").append(EventMap.RACEWARS_DUEL_SPAWNS).append(",\"racewarsFinal\":").append(EventMap.RACEWARS_FINAL_SPAWNS).append('}');
		json.append(",\"tvt\":{\"baseRadius\":").append(TeamVsTeamConfig.BASE_RADIUS).append(",\"spawnRadius\":").append(TeamVsTeamConfig.SPAWN_RADIUS).append(",\"teamSize\":").append(TeamVsTeamConfig.TEAM_SIZE).append('}');
		json.append(",\"deathmatch\":{\"size\":").append(TeamVsTeamConfig.DM_SIZE).append('}');
		json.append(",\"racewars\":{\"baseRadius\":").append(RaceWarsConfig.BASE_RADIUS).append(",\"spawnRadius\":").append(RaceWarsConfig.SPAWN_RADIUS).append(",\"armySize\":").append(RaceWarsConfig.ARMY_SIZE).append('}');
		json.append(",\"players\":{\"default\":").append(EventMapData.DEFAULT_MAX_PLAYERS).append(",\"min\":").append(EventMapData.MIN_PLAYERS).append(",\"max\":").append(EventMapData.MAX_PLAYERS).append('}');
		json.append(",\"minBarrier\":").append(EventBarrier.MIN_RADIUS);
		json.append(",\"world\":[").append(World.WORLD_X_MIN).append(',').append(World.WORLD_Y_MIN).append(',').append(World.WORLD_X_MAX).append(',').append(World.WORLD_Y_MAX).append(']');
		json.append(",\"allowed\":{\"tvt\":");
		appendStrings(json, TeamVsTeamConfig.MAPS);
		json.append(",\"conquest\":");
		appendStrings(json, ConquestConfig.MAPS);
		json.append(",\"deathmatch\":");
		appendStrings(json, TeamVsTeamConfig.DM_MAPS);
		json.append(",\"racewars\":");
		appendStrings(json, RaceWarsConfig.MAPS);
		json.append("},\"maps\":[");
		for (int i = 0; i < maps.size(); i++)
		{
			json.append(i > 0 ? "," : "");
			appendMap(json, maps.get(i));
		}
		json.append("]}");
		PassiveTreeApiServer.getInstance().sendJson(exchange, 200, json.toString());
	}

	private static void appendStrings(StringBuilder json, List<String> strings)
	{
		json.append('[');
		for (int i = 0; i < strings.size(); i++)
		{
			json.append(i > 0 ? "," : "").append(SimpleJson.quote(strings.get(i)));
		}
		json.append(']');
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
		json.append(",\"shown\":").append(quoteOrNull(EventMapMarkers.getInstance().getShown(gm))).append('}');
		PassiveTreeApiServer.getInstance().sendJson(exchange, 200, json.toString());
	}

	/**
	 * The ground at a spot, for a point the page just placed or moved: its height (the layer nearest the height it had) and whether a player can walk off it in every direction.
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

	private static void appendMap(StringBuilder json, EventMap map)
	{
		json.append("{\"id\":").append(SimpleJson.quote(map.id()));
		json.append(",\"name\":").append(SimpleJson.quote(map.name()));
		json.append(",\"description\":").append(SimpleJson.quote(map.description()));
		json.append(",\"minX\":").append(map.minX()).append(",\"minY\":").append(map.minY()).append(",\"maxX\":").append(map.maxX()).append(",\"maxY\":").append(map.maxY()).append(",\"z\":").append(map.z());
		json.append(",\"uses\":{");
		boolean first = true;
		for (EventMapType type : EventMapType.values())
		{
			json.append(first ? "" : ",").append(SimpleJson.quote(type.getKey())).append(':').append(map.isUsedBy(type));
			first = false;
		}
		json.append("},\"conquestMaxPlayers\":").append(map.conquestMaxPlayers());
		json.append(",\"points\":[");
		first = true;
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
				appendPoint(json, "tvt", blue ? "blue" : "red", "", spawn.getX(), spawn.getY(), spawn.getZ(), spawn.getHeading(), 0);
			}
		}
		for (ConquestPoint point : map.conquestPoints())
		{
			json.append(first ? "" : ",");
			first = false;
			appendPoint(json, "conquest", point.type().getKey(), point.name(), point.x(), point.y(), point.z(), point.heading(), point.radius());
		}
		for (Location spawn : map.deathmatch())
		{
			json.append(first ? "" : ",");
			first = false;
			appendPoint(json, "deathmatch", "spawn", "", spawn.getX(), spawn.getY(), spawn.getZ(), spawn.getHeading(), 0);
		}
		for (Location start : map.raceWarsDuel())
		{
			json.append(first ? "" : ",");
			first = false;
			appendPoint(json, "racewars", "duel", "", start.getX(), start.getY(), start.getZ(), start.getHeading(), 0);
		}
		for (Location start : map.raceWarsFinal())
		{
			json.append(first ? "" : ",");
			first = false;
			appendPoint(json, "racewars", "final", "", start.getX(), start.getY(), start.getZ(), start.getHeading(), 0);
		}
		json.append("],\"barrier\":");
		final EventBarrier barrier = map.barrier();
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
		for (EventDoor door : map.doors())
		{
			json.append(first ? "" : ",").append("{\"id\":").append(door.id()).append(",\"open\":").append(door.open()).append('}');
			first = false;
		}
		json.append("]}");
	}

	private static void appendPoint(StringBuilder json, String event, String type, String name, int x, int y, int z, int heading, int radius)
	{
		json.append("{\"event\":").append(SimpleJson.quote(event)).append(",\"type\":").append(SimpleJson.quote(type));
		if (!name.isEmpty())
		{
			json.append(",\"name\":").append(SimpleJson.quote(name));
		}
		json.append(",\"x\":").append(x).append(",\"y\":").append(y).append(",\"z\":").append(z).append(",\"heading\":").append(heading);
		if (radius > 0)
		{
			json.append(",\"radius\":").append(radius);
		}
		// Whether a player can stand there and walk off in every direction: not in a wall, on a cliff edge or out of the geodata.
		json.append(",\"open\":").append(isOpen(x, y, z)).append('}');
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

	/**
	 * Deathmatch spawn points picked by chance on the map as the page has it (saved or not): open ground inside the fence, apart from each other and from the spawn points it has.
	 */
	private void handleScatter(HttpExchange exchange) throws IOException
	{
		final Map<?, ?> json = readJson(exchange);
		if (json == null)
		{
			return;
		}

		final EventMap map;
		try
		{
			map = readMap(json, new ArrayList<>());
		}
		catch (IllegalArgumentException e)
		{
			sendError(exchange, e.getMessage());
			return;
		}
		final int count = Math.max(1, Math.min(MAX_SCATTER, json.get("count") instanceof Number ? asInt(json.get("count")) : 20));
		final int gap = Math.max(100, Math.min(5000, json.get("gap") instanceof Number ? asInt(json.get("gap")) : 700));
		final List<Location> spots = EventMapScatter.scatter(map, Math.min(count, EventMapData.MAX_DEATHMATCH_SPAWNS - map.deathmatch().size()), gap);
		final StringBuilder answer = new StringBuilder(64 * (spots.size() + 1));
		answer.append("{\"spots\":[");
		for (int i = 0; i < spots.size(); i++)
		{
			final Location spot = spots.get(i);
			answer.append(i > 0 ? "," : "");
			appendPoint(answer, "deathmatch", "spawn", "", spot.getX(), spot.getY(), spot.getZ(), spot.getHeading(), 0);
		}
		answer.append("]}");
		PassiveTreeApiServer.getInstance().sendJson(exchange, 200, answer.toString());
	}

	private void handleSave(HttpExchange exchange) throws IOException
	{
		final PassiveTreeApiServer server = PassiveTreeApiServer.getInstance();
		final Player gm = getGm(exchange);
		if (gm == null)
		{
			return;
		}
		final Map<?, ?> json = readBodyJson(exchange);
		if (json == null)
		{
			return;
		}

		final List<String> warnings = new ArrayList<>();
		final EventMap map;
		try
		{
			map = readMap(json, warnings);
		}
		catch (IllegalArgumentException e)
		{
			sendError(exchange, e.getMessage());
			return;
		}

		final EventMapData data = EventMapData.getInstance();
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
				refreshDrawings(previousId);
			}
		}
		catch (IOException | IllegalArgumentException e)
		{
			LOGGER.warning(getClass().getSimpleName() + ": Could not save the event map " + map.id() + " - " + e.getMessage());
			sendError(exchange, "could not save: " + e.getMessage());
			return;
		}
		refreshDrawings(map.id());
		LOGGER.info(getClass().getSimpleName() + ": " + gm.getName() + " saved the event map " + map.id() + (renamed ? " (was " + previousId + ")" : "") + ", used in " + map.uses() + ".");

		// Also to a file of the GM's choice (the live map stays the one in data/maps).
		String copiedTo = null;
		final String saveTo = asString(json.get("saveTo")) != null ? asString(json.get("saveTo")).trim() : "";
		if (!saveTo.isEmpty())
		{
			try
			{
				copiedTo = data.writeCopy(map, Path.of(saveTo)).toString();
				LOGGER.info(getClass().getSimpleName() + ": " + gm.getName() + " also wrote the event map " + map.id() + " to " + copiedTo + ".");
			}
			catch (Exception e)
			{
				warnings.add(0, "Not written to " + saveTo + ": " + e.getMessage() + " (the map itself was saved).");
			}
		}
		addListWarning(warnings, map, EventMapType.TVT, TeamVsTeamConfig.MAPS, "TvTMaps in TeamVsTeam.ini");
		addListWarning(warnings, map, EventMapType.CONQUEST, ConquestConfig.MAPS, "ConquestMaps in Conquest.ini");
		addListWarning(warnings, map, EventMapType.DEATHMATCH, TeamVsTeamConfig.DM_MAPS, "DeathmatchMaps in TeamVsTeam.ini");
		addListWarning(warnings, map, EventMapType.RACEWARS, RaceWarsConfig.MAPS, "RaceWarsMaps in RaceWars.ini");

		final StringBuilder answer = new StringBuilder(4096);
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

	private static void addListWarning(List<String> warnings, EventMap map, EventMapType type, List<String> allowed, String key)
	{
		if (map.isUsedBy(type) && !allowed.isEmpty() && !allowed.contains(map.id()))
		{
			warnings.add(key + " doesn't name " + map.id() + ": " + type.getTitle() + " registrations don't pick it.");
		}
	}

	/** Draws a map again for the GMs showing it, on both drawings (the event maps', and Conquest's own //conquest show). */
	private static void refreshDrawings(String id)
	{
		EventMapMarkers.getInstance().refresh(id);
		ConquestMarkers.getInstance().refresh(id);
	}

	/**
	 * Reads a map sent by the page, and puts every point on the ground of the geodata (the layer nearest the height it had).
	 * @param json the map
	 * @param warnings where to add what is allowed but likely wrong
	 * @return the map
	 * @throws IllegalArgumentException with what is wrong, if it can't be saved
	 */
	private static EventMap readMap(Map<?, ?> json, List<String> warnings)
	{
		final String id = asString(json.get("id")) != null ? asString(json.get("id")).trim().toLowerCase() : "";
		if (!EventMapData.isValidId(id))
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

		final Set<EventMapType> uses = EnumSet.noneOf(EventMapType.class);
		if (json.get("uses") instanceof Map)
		{
			for (EventMapType type : EventMapType.values())
			{
				if (Boolean.TRUE.equals(((Map<?, ?>) json.get("uses")).get(type.getKey())))
				{
					uses.add(type);
				}
			}
		}

		EventBarrier barrier = null;
		if (json.get("barrier") instanceof Map)
		{
			final Map<?, ?> b = (Map<?, ?>) json.get("barrier");
			final int bx = asInt(b.get("x"));
			final int by = asInt(b.get("y"));
			final int radiusX = asInt(b.get("radiusX"));
			final int radiusY = asInt(b.get("radiusY"));
			if ((radiusX < EventBarrier.MIN_RADIUS) || (radiusY < EventBarrier.MIN_RADIUS) || (radiusX > MAX_AREA) || (radiusY > MAX_AREA))
			{
				throw new IllegalArgumentException("the fence's radii must be " + EventBarrier.MIN_RADIUS + " to " + MAX_AREA);
			}
			if (!inWorld(bx, by))
			{
				throw new IllegalArgumentException("the fence's middle is outside the world");
			}
			barrier = new EventBarrier(bx, by, radiusX, radiusY);
			if (((bx - radiusX) < minX) || ((bx + radiusX) > maxX) || ((by - radiusY) < minY) || ((by + radiusY) > maxY))
			{
				warnings.add("The fence reaches beyond the area: the radar shows the area as the battlefield's edge.");
			}
		}

		final GeoEngine geo = GeoEngine.getInstance();
		final int hintZ = json.get("z") instanceof Number ? asInt(json.get("z")) : 0;
		final List<Location> blue = new ArrayList<>();
		final List<Location> red = new ArrayList<>();
		final List<ConquestPoint> conquestPoints = new ArrayList<>();
		final List<Location> deathmatch = new ArrayList<>();
		final List<Location> raceWarsDuel = new ArrayList<>();
		final List<Location> raceWarsFinal = new ArrayList<>();
		final int[] conquestCounts = new int[ConquestPointType.values().length];
		final Set<String> captureNames = new HashSet<>();
		if (json.get("points") instanceof List)
		{
			for (Object entry : (List<?>) json.get("points"))
			{
				if (!(entry instanceof Map))
				{
					continue;
				}
				final Map<?, ?> p = (Map<?, ?>) entry;
				final EventMapType event = EventMapType.fromKey(asString(p.get("event")));
				final String type = asString(p.get("type")) != null ? asString(p.get("type")).toLowerCase() : "";
				if (event == null)
				{
					throw new IllegalArgumentException("a point belongs to no event (tvt, conquest, deathmatch, racewars)");
				}
				final int x = asInt(p.get("x"));
				final int y = asInt(p.get("y"));
				if (!inWorld(x, y))
				{
					throw new IllegalArgumentException("a point is outside the world");
				}
				final int givenZ = p.get("z") instanceof Number ? asInt(p.get("z")) : hintZ;
				final int z = geo.hasGeo(x, y) ? geo.getHeight(x, y, givenZ) : givenZ;
				final int heading = ((asInt(p.get("heading")) % 65536) + 65536) % 65536;

				final String label;
				switch (event)
				{
					case TVT:
					{
						final List<Location> spawns = type.equals("blue") ? blue : type.equals("red") ? red : null;
						if (spawns == null)
						{
							throw new IllegalArgumentException("a Team vs Team base is neither blue nor red");
						}
						if (spawns.size() >= EventMapData.MAX_TVT_SPAWNS)
						{
							throw new IllegalArgumentException("more than " + EventMapData.MAX_TVT_SPAWNS + " " + type + " Team vs Team bases");
						}
						spawns.add(new Location(x, y, z, heading));
						label = "TvT " + (spawns == blue ? "Blue" : "Red") + " base " + spawns.size();
						if ((barrier != null) && !barrier.contains(x, y, -Math.min(TeamVsTeamConfig.BASE_RADIUS, Math.min(barrier.radiusX(), barrier.radiusY()) - 1)))
						{
							warnings.add(label + (barrier.contains(x, y, 0) ? " is closer to the fence than its base's radius (" + TeamVsTeamConfig.BASE_RADIUS + "): part of the base is beyond it." : " is beyond the fence: whoever spawns there is brought back inside at once."));
						}
						break;
					}
					case CONQUEST:
					{
						final ConquestPointType pointType = ConquestPointType.fromKey(type);
						if (pointType == null)
						{
							throw new IllegalArgumentException("a Conquest point is neither blue, red nor capture");
						}
						if (++conquestCounts[pointType.ordinal()] > EventMapData.MAX_CONQUEST_POINTS)
						{
							throw new IllegalArgumentException("more than " + EventMapData.MAX_CONQUEST_POINTS + " Conquest " + pointType.getKey() + " points");
						}
						final int radius = Math.max(MIN_RADIUS, Math.min(MAX_RADIUS, p.get("radius") instanceof Number ? asInt(p.get("radius")) : pointType.getDefaultRadius()));
						String pointName = "";
						if (pointType == ConquestPointType.CAPTURE)
						{
							pointName = asString(p.get("name")) != null ? asString(p.get("name")).trim().toUpperCase() : "";
							if (pointName.isEmpty())
							{
								pointName = nextCaptureName(captureNames, json.get("points"));
							}
							if (pointName.length() > MAX_POINT_NAME)
							{
								throw new IllegalArgumentException("the capture point name " + pointName + " is longer than " + MAX_POINT_NAME + " characters");
							}
							if (!captureNames.add(pointName))
							{
								throw new IllegalArgumentException("two capture points are called " + pointName);
							}
						}
						conquestPoints.add(new ConquestPoint(pointType, pointName, x, y, z, pointType == ConquestPointType.CAPTURE ? 0 : heading, radius));
						label = pointType == ConquestPointType.CAPTURE ? "Capture point " + pointName : "Conquest " + (pointType == ConquestPointType.BLUE ? "Blue" : "Red") + " spawn " + conquestCounts[pointType.ordinal()];
						break;
					}
					case RACEWARS:
					{
						final boolean finals = type.equals("final");
						if (!finals && !type.equals("duel"))
						{
							throw new IllegalArgumentException("a Race Wars start point is neither duel nor final");
						}
						final List<Location> starts = finals ? raceWarsFinal : raceWarsDuel;
						final int max = finals ? EventMapData.MAX_RACEWARS_FINAL_SPAWNS : EventMapData.MAX_RACEWARS_DUEL_SPAWNS;
						if (starts.size() >= max)
						{
							throw new IllegalArgumentException("more than " + max + " Race Wars " + (finals ? "final" : "race-vs-race") + " start points (only so many races start)");
						}
						starts.add(new Location(x, y, z, heading));
						label = "Race Wars " + (finals ? "final" : "race-vs-race") + " start " + starts.size();
						if ((barrier != null) && !barrier.contains(x, y, -Math.min(RaceWarsConfig.BASE_RADIUS, Math.min(barrier.radiusX(), barrier.radiusY()) - 1)))
						{
							warnings.add(label + (barrier.contains(x, y, 0) ? " is closer to the fence than the start radius (" + RaceWarsConfig.BASE_RADIUS + "): the army may stand beyond it while it buffs." : " is beyond the fence: the army is brought back inside at once when the fight starts."));
						}
						break;
					}
					default:
					{
						if (deathmatch.size() >= EventMapData.MAX_DEATHMATCH_SPAWNS)
						{
							throw new IllegalArgumentException("more than " + EventMapData.MAX_DEATHMATCH_SPAWNS + " Deathmatch spawn points");
						}
						deathmatch.add(new Location(x, y, z, heading));
						label = "Deathmatch spawn " + deathmatch.size();
						if ((barrier != null) && !barrier.contains(x, y, 0))
						{
							warnings.add(label + " is beyond the fence: whoever spawns there is brought back inside at once.");
						}
						break;
					}
				}

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
			}
		}

		// What each event ticked for still lacks, and what plays badly.
		if (uses.contains(EventMapType.TVT))
		{
			if (blue.isEmpty() || red.isEmpty())
			{
				warnings.add("Team vs Team: " + (blue.isEmpty() ? "Blue" : "Red") + " has no base, Team vs Team won't pick this map.");
			}
			for (int i = 0; i < blue.size(); i++)
			{
				for (int j = 0; j < red.size(); j++)
				{
					final double distance = Math.hypot(blue.get(i).getX() - red.get(j).getX(), blue.get(i).getY() - red.get(j).getY());
					if (distance < (TeamVsTeamConfig.BASE_RADIUS * 2))
					{
						warnings.add("TvT Blue base " + (i + 1) + " and Red base " + (j + 1) + " overlap (" + (int) distance + " apart, a base is " + TeamVsTeamConfig.BASE_RADIUS + " round): whoever spawns there is stunned in the enemy base.");
					}
				}
			}
		}
		if (uses.contains(EventMapType.CONQUEST))
		{
			for (ConquestPointType type : ConquestPointType.values())
			{
				if (conquestCounts[type.ordinal()] == 0)
				{
					warnings.add("Conquest: " + (type == ConquestPointType.CAPTURE ? "there is no capture point" : (type == ConquestPointType.BLUE ? "Blue" : "Red") + " has no spawn point") + ", Conquest won't pick this map.");
				}
			}
		}
		if (uses.contains(EventMapType.DEATHMATCH))
		{
			if (deathmatch.size() < EventMap.MIN_DEATHMATCH_SPAWNS)
			{
				warnings.add("Deathmatch: fewer than " + EventMap.MIN_DEATHMATCH_SPAWNS + " spawn points, Deathmatch won't pick this map.");
			}
			else if (deathmatch.size() < Math.min(10, TeamVsTeamConfig.DM_SIZE / 2))
			{
				warnings.add("Deathmatch: only " + deathmatch.size() + " spawn points for " + TeamVsTeamConfig.DM_SIZE + " fighters: they often spawn close to each other.");
			}
			int close = 0;
			for (int i = 0; i < deathmatch.size(); i++)
			{
				for (int j = i + 1; j < deathmatch.size(); j++)
				{
					if (Math.hypot(deathmatch.get(i).getX() - deathmatch.get(j).getX(), deathmatch.get(i).getY() - deathmatch.get(j).getY()) < DEATHMATCH_CLOSE)
					{
						close++;
					}
				}
			}
			if (close > 0)
			{
				warnings.add("Deathmatch: " + close + " pair(s) of spawn points closer than " + DEATHMATCH_CLOSE + ".");
			}
		}

		if (uses.contains(EventMapType.RACEWARS))
		{
			if ((raceWarsDuel.size() < EventMap.RACEWARS_DUEL_SPAWNS) || (raceWarsFinal.size() < EventMap.RACEWARS_FINAL_SPAWNS))
			{
				warnings.add("Race Wars: " + raceWarsDuel.size() + " of " + EventMap.RACEWARS_DUEL_SPAWNS + " race-vs-race and " + raceWarsFinal.size() + " of " + EventMap.RACEWARS_FINAL_SPAWNS + " final start points, Race Wars won't pick this map.");
			}
			addStartWarnings(warnings, raceWarsDuel, "race-vs-race");
			addStartWarnings(warnings, raceWarsFinal, "final");
		}

		final List<EventDoor> doors = new ArrayList<>();
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
				doors.add(new EventDoor(doorId, !Boolean.FALSE.equals(((Map<?, ?>) entry).get("open"))));
				if ((door.getX() < minX) || (door.getX() > maxX) || (door.getY() < minY) || (door.getY() > maxY))
				{
					warnings.add("Door " + doorId + " (" + door.getTemplate().getName() + ") is outside the area.");
				}
			}
		}

		final int maxPlayers = json.get("conquestMaxPlayers") instanceof Number ? asInt(json.get("conquestMaxPlayers")) : EventMapData.DEFAULT_MAX_PLAYERS;
		if ((maxPlayers < EventMapData.MIN_PLAYERS) || (maxPlayers > EventMapData.MAX_PLAYERS))
		{
			throw new IllegalArgumentException("the Conquest players per team must be " + EventMapData.MIN_PLAYERS + " to " + EventMapData.MAX_PLAYERS);
		}

		final List<Location> all = new ArrayList<>(blue);
		all.addAll(red);
		all.addAll(deathmatch);
		all.addAll(raceWarsDuel);
		all.addAll(raceWarsFinal);
		final int z = json.get("z") instanceof Number ? asInt(json.get("z")) : EventMapData.averageZ(all);
		return new EventMap(id, name, description, minX, minY, maxX, maxY, z, barrier, doors, uses, blue, red, maxPlayers, conquestPoints, deathmatch, raceWarsDuel, raceWarsFinal);
	}

	/**
	 * Race Wars start points too close together: two armies would start within reach of each other (each stands within {@link RaceWarsConfig#BASE_RADIUS} of its start while it buffs).
	 */
	private static void addStartWarnings(List<String> warnings, List<Location> starts, String stage)
	{
		for (int i = 0; i < starts.size(); i++)
		{
			for (int j = i + 1; j < starts.size(); j++)
			{
				final double distance = Math.hypot(starts.get(i).getX() - starts.get(j).getX(), starts.get(i).getY() - starts.get(j).getY());
				if (distance < RACEWARS_CLOSE)
				{
					warnings.add("Race Wars " + stage + " starts " + (i + 1) + " and " + (j + 1) + " are only " + (int) distance + " apart: the armies see each other while they buff (keep them at least " + RACEWARS_CLOSE + " apart).");
				}
			}
		}
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
			if ((id != null) && EventMapData.getInstance().delete(id))
			{
				refreshDrawings(id);
				LOGGER.info(getClass().getSimpleName() + ": " + gm.getName() + " deleted the event map " + id + ".");
				lines.add("Deleted " + id + " (its file is now data/maps/" + id + ".xml.deleted).");
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

		final Map<String, String> params = server.readParams(exchange);
		final EventMap map = EventMapData.getInstance().getMap(params.get("id"));
		if (map == null)
		{
			sendError(exchange, "save the map first: only saved maps are drawn in game");
			return;
		}
		final EventMapType type = EventMapType.fromKey(params.get("event"));
		EventMapMarkers.getInstance().show(gm, map, type);
		final List<String> lines = new ArrayList<>();
		lines.add(map.name() + (type != null ? " (" + type.getTitle() + ")" : "") + " is drawn in game for " + gm.getName() + " (again after every save).");
		sendLines(exchange, lines);
	}

	private void handleHide(HttpExchange exchange) throws IOException
	{
		final Player gm = getGm(exchange);
		if (gm == null)
		{
			return;
		}

		EventMapMarkers.getInstance().hide(gm);
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

		EventMapData.getInstance().load();
		EventMapMarkers.getInstance().refreshAll();
		ConquestMarkers.getInstance().refreshAll();
		final List<String> lines = new ArrayList<>();
		lines.add("Read " + EventMapData.getInstance().getMaps().size() + " map(s) from data/maps again.");
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

	/** @return the JSON body of a request of an allowed GM, {@code null} after the error answer was sent */
	private static Map<?, ?> readJson(HttpExchange exchange) throws IOException
	{
		if (getGm(exchange) == null)
		{
			return null;
		}
		return readBodyJson(exchange);
	}

	/** @return the JSON body, {@code null} after the error answer was sent */
	private static Map<?, ?> readBodyJson(HttpExchange exchange) throws IOException
	{
		final byte[] body = PassiveTreeApiServer.getInstance().readBody(exchange, MAX_BODY_BYTES);
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
		}
		return json;
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

	public static EventMapWeb getInstance()
	{
		return SingletonHolder.INSTANCE;
	}

	private static class SingletonHolder
	{
		protected static final EventMapWeb INSTANCE = new EventMapWeb();
	}
}
