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
import org.l2jmobius.gameserver.model.actor.Creature;
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
 * The GM pages of Conquest, served by the passive tree's web server. The maps are edited on the event map page (custom.EventMaps.EventMapWeb, //conquest), shared with Team vs Team and Deathmatch; this is the terrain picture it draws them on:
 * <ul>
 * <li>GET /api/conquest/terrain?minX=&amp;minY=&amp;maxX=&amp;maxY=&amp;z= - a picture of the ground from the geodata, seen from above: heights, slopes, and where it can't be walked</li>
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

	private static final Path LIVE_HTML_FILE = Path.of("data/html/custom/conquest-live.html");
	/** The command a GM's access level must allow. */
	private static final String ADMIN_COMMAND = "admin_conquest";

	/** A battlefield is at most this wide (about two geodata regions): the terrain picture is at most twice that. */
	private static final int MAX_AREA = 65536;

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
		server.addRoute("/api/conquest/terrain", "GET", false, this::handleTerrain);
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

	// ---------------------------------------------------------------------------------------------
	// Reading
	// ---------------------------------------------------------------------------------------------

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
	public static byte[] renderTerrain(int minX, int minY, int maxX, int maxY, int z) throws IOException
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
		final ConquestMatch match = manager.getMatch(gm);
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
			json.append(",\"pending\":").append(manager.getPendingSpawns(match));
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
		json.append(",\"commanderStatus\":").append(SimpleJson.quote(team.getCommander().getStatus()));
		json.append(",\"score\":").append(team.getScore()).append(",\"drain\":").append(team.getDrain()).append(",\"held\":").append(team.getHeld());
		json.append(",\"alive\":").append(team.getAlive()).append(",\"total\":").append(team.getFighters().size()).append(",\"kills\":").append(team.getKills());
		final long now = System.currentTimeMillis();
		final String brain = team.getCommander().getReport(now);
		json.append(",\"brain\":").append(brain != null ? brain : "null");
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
			final Creature npc = fighter.getCreature();
			final ConquestParty party = fighter.getParty();
			final FakePlayerPvpProfile profile = fighter.getNpc() != null ? fighter.getNpc().getTemplate().getFakePlayerPvpProfile() : null;
			json.append(first ? "" : ",");
			first = false;
			json.append('[').append(npc.getObjectId()).append(',').append(npc.getX()).append(',').append(npc.getY());
			json.append(',').append(fighter.isAlive() ? 1 : 0);
			json.append(',').append((int) Math.round((100 * npc.getCurrentHp()) / Math.max(1, npc.getMaxHp())));
			json.append(',').append(party != null ? party.getId() : 0);
			json.append(',').append(SimpleJson.quote(fighter.getRole().name().toLowerCase()));
			json.append(',').append((party != null) && (party.getLeader() == npc) ? 1 : 0);
			json.append(',').append(SimpleJson.quote(npc.getName()));
			json.append(',').append(SimpleJson.quote(fighter.isPlayer() ? "player" : (profile != null) && (profile.getBuild() != null) ? profile.getBuild().getName() : ""));
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
		lines.add(ConquestManager.getInstance().watch(gm, 0));
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
