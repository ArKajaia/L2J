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
package custom.Simulation;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

import org.l2jmobius.commons.util.Rnd;
import org.l2jmobius.commons.util.SimpleJson;
import org.l2jmobius.gameserver.config.custom.FakeSimulationConfig;
import org.l2jmobius.gameserver.managers.simulation.FakeSimulationManager;
import org.l2jmobius.gameserver.managers.simulation.SimulationHistory;
import org.l2jmobius.gameserver.managers.simulation.SimulationLog;
import org.l2jmobius.gameserver.managers.simulation.SimulationMatrix;
import org.l2jmobius.gameserver.managers.simulation.SimulationMember;
import org.l2jmobius.gameserver.managers.simulation.SimulationPoint;
import org.l2jmobius.gameserver.managers.simulation.SimulationRun;
import org.l2jmobius.gameserver.managers.simulation.SimulationTeam;
import org.l2jmobius.gameserver.model.WorldObject;
import org.l2jmobius.gameserver.model.actor.Creature;
import org.l2jmobius.gameserver.model.actor.Player;

import com.sun.net.httpserver.HttpExchange;

import custom.PassiveSkillTree.PassiveTreeApiServer;

/**
 * The GM page of the fake player simulations ({@link FakeSimulationManager}), served by the passive tree's web server. A GM opens it with //SimulationWeb; the link works while that GM is online and allowed to use //Simulate.
 * With SimulationWebOpenAccess (FakeSimulation.ini) anyone may use it without a link, for testing: only drawing the points in game still needs a GM's link.
 * <ul>
 * <li>GET /simulation.html - the page</li>
 * <li>GET /api/simulation/status - the running round, its teams and where everyone is</li>
 * <li>GET /api/simulation/runs - the simulations in the log folder, newest first</li>
 * <li>GET /api/simulation/round?series=&amp;round= - run.json, summary.json and the anomalies of a round</li>
 * <li>GET /api/simulation/positions?series=&amp;round= - everyone's positions over the round, in columns</li>
 * <li>GET /api/simulation/member?series=&amp;round=&amp;id= - the decisions, events and anomalies of one character</li>
 * <li>POST /api/simulation/start, /rerun, /stop - like //Simulate and //SimulationStop (start: size, level, mirror, rounds, time, seed, points, swapPoints, brains like A:B, swap for an A/B test)</li>
 * <li>GET /api/simulation/points, POST /api/simulation/points/clear, /points/draw - the saved spawn points</li>
 * <li>POST /api/simulation/matrix/start, /matrix/resume, /matrix/stop, GET /matrix/status - the class-vs-class matrix, like //SimulationMatrix (start: battles, parallel, level, time, distance, brain, builds, seed, and x, y, z, heading for the place: the GM's own
 * place by default, else the simulation field by Cruma Marshlands)</li>
 * </ul>
 * Everything is read from the files the simulations leave (see {@link SimulationLog}), so past runs survive restarts until SimulationKeepRuns prunes them.
 * @author Claude
 */
public class SimulationWeb
{
	private static final Logger LOGGER = Logger.getLogger(SimulationWeb.class.getName());

	private static final Path HTML_FILE = Path.of("data/html/custom/simulation.html");
	/** The command a GM's access level must allow. */
	private static final String ADMIN_COMMAND = "admin_simulate";
	/** The level of the fake players when the request names none and no GM is behind it. */
	private static final int DEFAULT_LEVEL = 80;
	/** At most this many lines of one character's decisions (or events) are sent. */
	private static final int MAX_MEMBER_LINES = 30000;

	/** The list entry of a finished simulation, which no longer changes: name -> JSON. */
	private final Map<String, String> _finished = new ConcurrentHashMap<>();
	/** The last rounds whose positions were asked for: path -> columns. */
	private final Map<Path, Positions> _positions = new LinkedHashMap<Path, Positions>(4, 0.75f, true)
	{
		private static final long serialVersionUID = 1L;

		@Override
		protected boolean removeEldestEntry(Map.Entry<Path, Positions> eldest)
		{
			return size() > 2;
		}
	};

	private static class Positions
	{
		private final long _size;
		private final long _modified;
		private final String _json;

		Positions(long size, long modified, String json)
		{
			_size = size;
			_modified = modified;
			_json = json;
		}

		long getSize()
		{
			return _size;
		}

		long getModified()
		{
			return _modified;
		}

		String getJson()
		{
			return _json;
		}
	}

	/** The positions of one character over a round, as columns. */
	private static class Track
	{
		final String name;
		final int team;
		final StringBuilder t = new StringBuilder();
		final StringBuilder x = new StringBuilder();
		final StringBuilder y = new StringBuilder();
		final StringBuilder z = new StringBuilder();
		final StringBuilder hp = new StringBuilder();
		final StringBuilder cp = new StringBuilder();
		final StringBuilder alive = new StringBuilder();
		final StringBuilder target = new StringBuilder();
		final StringBuilder decision = new StringBuilder();
		final StringBuilder casting = new StringBuilder();
		boolean first = true;

		Track(String name, int team)
		{
			this.name = name;
			this.team = team;
		}
	}

	private SimulationWeb()
	{
	}

	/**
	 * Adds the simulation page's paths to the passive tree's web server. Called once its server is running.
	 */
	public void register()
	{
		final PassiveTreeApiServer server = PassiveTreeApiServer.getInstance();
		server.addRoute("/simulation.html", "GET", false, exchange -> server.sendPage(exchange, HTML_FILE));
		server.addRoute("/api/simulation/status", "GET", false, this::handleStatus);
		server.addRoute("/api/simulation/runs", "GET", false, this::handleRuns);
		server.addRoute("/api/simulation/round", "GET", false, this::handleRound);
		server.addRoute("/api/simulation/positions", "GET", false, this::handlePositions);
		server.addRoute("/api/simulation/member", "GET", false, this::handleMember);
		server.addRoute("/api/simulation/start", "POST", true, this::handleStart);
		server.addRoute("/api/simulation/rerun", "POST", true, this::handleRerun);
		server.addRoute("/api/simulation/stop", "POST", true, this::handleStop);
		server.addRoute("/api/simulation/points/clear", "POST", true, this::handleClearPoint);
		server.addRoute("/api/simulation/points/draw", "POST", true, this::handleDrawPoints);
		server.addRoute("/api/simulation/points", "GET", false, this::handlePoints);
		server.addRoute("/api/simulation/matrix/start", "POST", true, this::handleMatrixStart);
		server.addRoute("/api/simulation/matrix/resume", "POST", true, this::handleMatrixResume);
		server.addRoute("/api/simulation/matrix/stop", "POST", true, this::handleMatrixStop);
		server.addRoute("/api/simulation/matrix/status", "GET", false, this::handleMatrixStatus);
		if (FakeSimulationConfig.WEB_OPEN_ACCESS)
		{
			LOGGER.warning("SimulationWeb: SimulationWebOpenAccess is on - anyone who reaches " + server.getPageUrl("simulation.html") + " can start and stop simulations.");
		}
	}

	/**
	 * @param gm the GM
	 * @return the address of the simulation page for that GM
	 */
	public static String getUrl(Player gm)
	{
		final PassiveTreeApiServer server = PassiveTreeApiServer.getInstance();
		return server.getPageUrl("simulation.html") + "?admin=" + server.generateAdminToken(gm.getObjectId());
	}

	// ---------------------------------------------------------------------------------------------
	// Status
	// ---------------------------------------------------------------------------------------------

	private void handleStatus(HttpExchange exchange) throws IOException
	{
		final PassiveTreeApiServer server = PassiveTreeApiServer.getInstance();
		final Player gm = getGm(exchange);
		if ((gm == null) && !FakeSimulationConfig.WEB_OPEN_ACCESS)
		{
			return;
		}

		final FakeSimulationManager manager = FakeSimulationManager.getInstance();
		final SimulationRun run = manager.getCurrentRun();
		final String series = manager.getCurrentSeriesName();
		final StringBuilder json = new StringBuilder(4096);
		json.append("{\"admin\":").append(quoteOrNull(gm != null ? gm.getName() : null));
		json.append(",\"running\":").append((run != null) || (series != null));
		json.append(",\"series\":").append(quoteOrNull(series));
		json.append(",\"stopping\":").append(manager.isStopping());
		json.append(",\"savedPoints\":").append(manager.getPoints().size());
		json.append(",\"defaults\":{\"level\":").append(gm != null ? gm.getLevel() : DEFAULT_LEVEL).append(",\"timeLimit\":").append(FakeSimulationConfig.TIME_LIMIT).append(",\"prepTime\":").append(FakeSimulationConfig.PREP_TIME).append('}');
		if (run != null)
		{
			json.append(",\"round\":").append(run.getRound());
			json.append(",\"rounds\":").append(run.getSettings().rounds());
			json.append(",\"phase\":").append(SimpleJson.quote(run.getPhase().name()));
			json.append(",\"time\":").append(run.time());
			json.append(",\"fightStart\":").append(run.getFightStart());
			json.append(",\"timeLimit\":").append(run.getSettings().timeLimit());
			json.append(",\"seed\":").append(SimpleJson.quote(String.valueOf(run.getSettings().seed())));
			json.append(",\"teams\":[");
			boolean firstTeam = true;
			for (SimulationTeam team : run.getTeams())
			{
				json.append(firstTeam ? "" : ",");
				firstTeam = false;
				int alive = 0;
				int total = 0;
				final StringBuilder members = new StringBuilder();
				for (SimulationMember member : team.getMembers())
				{
					if (!team.isActive(member))
					{
						continue;
					}
					total++;
					if (member.isAlive())
					{
						alive++;
					}
					final Creature creature = member.getCreature();
					final WorldObject target = creature.getTarget();
					members.append(members.length() > 0 ? "," : "");
					members.append("{\"id\":").append(member.getObjectId());
					members.append(",\"name\":").append(SimpleJson.quote(member.getName()));
					members.append(",\"role\":").append(SimpleJson.quote(member.getRole().name()));
					members.append(",\"build\":").append(SimpleJson.quote(member.getBuild()));
					members.append(",\"alive\":").append(member.isAlive());
					members.append(",\"hp\":").append(ratio(creature.getCurrentHp(), creature.getMaxHp()));
					members.append(",\"cp\":").append(ratio(creature.getCurrentCp(), creature.getMaxCp()));
					members.append(",\"x\":").append(creature.getX()).append(",\"y\":").append(creature.getY()).append(",\"z\":").append(creature.getZ());
					members.append(",\"targetId\":").append((target != null) && (target != creature) ? target.getObjectId() : 0);
					members.append(",\"decision\":").append(quoteOrNull(member.getLastReason() != null ? member.getLastReason().name() : null));
					members.append('}');
				}
				json.append("{\"id\":").append(team.getId());
				json.append(",\"brain\":").append(SimpleJson.quote(team.getBrain().name()));
				json.append(",\"alive\":").append(alive).append(",\"total\":").append(total);
				json.append(",\"health\":").append(ratio(team.getHealthLeft(), 1));
				json.append(",\"point\":{\"x\":").append(team.getPoint().x()).append(",\"y\":").append(team.getPoint().y()).append('}');
				json.append(",\"members\":[").append(members).append("]}");
			}
			json.append(']');
		}
		json.append('}');
		server.sendJson(exchange, 200, json.toString());
	}

	// ---------------------------------------------------------------------------------------------
	// History
	// ---------------------------------------------------------------------------------------------

	private void handleRuns(HttpExchange exchange) throws IOException
	{
		final PassiveTreeApiServer server = PassiveTreeApiServer.getInstance();
		if (!authorize(exchange))
		{
			return;
		}

		final String current = FakeSimulationManager.getInstance().getCurrentSeriesName();
		final List<Path> dirs = SimulationHistory.listSeries();
		final StringBuilder json = new StringBuilder(dirs.size() * 600);
		json.append("{\"current\":").append(quoteOrNull(current)).append(",\"keep\":").append(FakeSimulationConfig.KEEP_RUNS).append(",\"runs\":[");
		boolean first = true;
		_finished.keySet().retainAll(names(dirs));
		for (Path dir : dirs)
		{
			final String name = dir.getFileName().toString();
			String entry = _finished.get(name);
			if (entry == null)
			{
				final boolean finished = !name.equals(current) && Files.isRegularFile(dir.resolve(SimulationHistory.SERIES_FILE));
				entry = describeSeries(dir, name.equals(current));
				if (entry == null)
				{
					continue;
				}
				if (finished)
				{
					_finished.put(name, entry);
				}
			}
			json.append(first ? "" : ",").append(entry);
			first = false;
		}
		json.append("]}");
		server.sendJson(exchange, 200, json.toString());
	}

	private static List<String> names(List<Path> dirs)
	{
		final List<String> names = new ArrayList<>(dirs.size());
		for (Path dir : dirs)
		{
			names.add(dir.getFileName().toString());
		}
		return names;
	}

	/** @return the list entry of one simulation, {@code null} if its run.json can't be read */
	private static String describeSeries(Path dir, boolean running)
	{
		final Map<?, ?> run = SimulationHistory.readRunFile(dir);
		if (run == null)
		{
			return null;
		}

		final String name = dir.getFileName().toString();
		final StringBuilder json = new StringBuilder(800);
		json.append("{\"name\":").append(SimpleJson.quote(name));
		json.append(",\"start\":").append(quoteOrNull(asString(run.get("start"))));
		json.append(",\"size\":").append(asInt(run.get("size")));
		json.append(",\"level\":").append(asInt(run.get("level")));
		json.append(",\"mirror\":").append(Boolean.TRUE.equals(run.get("mirror")));
		json.append(",\"brains\":").append(quoteOrNull(run.get("brains") instanceof String ? (String) run.get("brains") : "A"));
		json.append(",\"swap\":").append(Boolean.TRUE.equals(run.get("swap")));
		json.append(",\"seed\":").append(SimpleJson.quote(name.substring(name.lastIndexOf('-') + 1)));
		json.append(",\"rounds\":").append(asInt(run.get("rounds")));
		json.append(",\"timeLimit\":").append(asInt(run.get("timeLimit")));
		json.append(",\"pointsFrom\":").append(quoteOrNull(asString(run.get("pointsFrom"))));
		json.append(",\"rerunOf\":").append(quoteOrNull(asString(run.get("rerunOf"))));
		json.append(",\"running\":").append(running);

		json.append(",\"points\":[");
		final List<SimulationPoint> points = SimulationHistory.readPoints(dir);
		for (int i = 0; i < points.size(); i++)
		{
			final SimulationPoint point = points.get(i);
			json.append(i > 0 ? "," : "").append("{\"id\":").append(point.id()).append(",\"x\":").append(point.x()).append(",\"y\":").append(point.y()).append(",\"z\":").append(point.z()).append(",\"instance\":").append(point.instanceId()).append('}');
		}
		json.append(']');

		// The builds of the first round (every round fields the same teams).
		json.append(",\"teams\":{");
		final Map<Integer, StringBuilder> teams = new LinkedHashMap<>();
		if (run.get("members") instanceof List)
		{
			for (Object item : (List<?>) run.get("members"))
			{
				if (item instanceof Map)
				{
					final Map<?, ?> member = (Map<?, ?>) item;
					final StringBuilder team = teams.computeIfAbsent(asInt(member.get("team")), k -> new StringBuilder());
					team.append(team.length() > 0 ? "," : "").append(SimpleJson.quote(asString(member.get("build"))));
				}
			}
		}
		boolean firstTeam = true;
		for (Map.Entry<Integer, StringBuilder> team : teams.entrySet())
		{
			json.append(firstTeam ? "" : ",").append('"').append(team.getKey()).append("\":[").append(team.getValue()).append(']');
			firstTeam = false;
		}
		json.append('}');

		// The results, from series.json once it is over, otherwise from what rounds are done.
		final List<Integer> rounds = SimulationHistory.listRounds(dir);
		json.append(",\"roundsDone\":").append(rounds.size());
		final Map<?, ?> series = parseObject(SimulationHistory.readText(dir.resolve(SimulationHistory.SERIES_FILE)));
		json.append(",\"finished\":").append(series != null);
		json.append(",\"results\":[");
		boolean firstResult = true;
		// Brain -> {wins, games}, from the brains each round's teams ran.
		final Map<String, int[]> brainWins = new LinkedHashMap<>();
		if ((series != null) && (series.get("results") instanceof List))
		{
			for (Object item : (List<?>) series.get("results"))
			{
				if (item instanceof Map)
				{
					final Map<?, ?> result = (Map<?, ?>) item;
					json.append(firstResult ? "" : ",");
					firstResult = false;
					final String winnerBrain = countBrains(brainWins, result.get("teamBrains"), asInt(result.get("winner")));
					appendResult(json, asInt(result.get("round")), asInt(result.get("winner")), asString(result.get("reason")), asLong(result.get("duration")), winnerBrain);
				}
			}
		}
		else
		{
			for (int round : rounds)
			{
				final Map<?, ?> summary = parseObject(SimulationHistory.readText(SimulationHistory.resolveRound(dir, round).resolve(SimulationHistory.SUMMARY_FILE)));
				if (summary != null)
				{
					json.append(firstResult ? "" : ",");
					firstResult = false;
					final Map<String, Object> teamBrains = new LinkedHashMap<>();
					if (summary.get("teams") instanceof List)
					{
						for (Object team : (List<?>) summary.get("teams"))
						{
							if ((team instanceof Map) && (((Map<?, ?>) team).get("brain") != null))
							{
								teamBrains.put(String.valueOf(asInt(((Map<?, ?>) team).get("id"))), ((Map<?, ?>) team).get("brain"));
							}
						}
					}
					final String winnerBrain = countBrains(brainWins, teamBrains, asInt(summary.get("winner")));
					appendResult(json, round, asInt(summary.get("winner")), asString(summary.get("reason")), asLong(summary.get("fightDuration")), winnerBrain);
				}
			}
		}
		json.append("],\"brainWins\":{");
		boolean firstBrain = true;
		for (Map.Entry<String, int[]> entry : brainWins.entrySet())
		{
			json.append(firstBrain ? "" : ",").append(SimpleJson.quote(entry.getKey())).append(":{\"wins\":").append(entry.getValue()[0]).append(",\"games\":").append(entry.getValue()[1]).append('}');
			firstBrain = false;
		}
		json.append("}}");
		return json.toString();
	}

	/**
	 * Counts a round in the brains' wins and games.
	 * @param brainWins brain -&gt; {wins, games}
	 * @param teamBrains the round's team id -&gt; brain ({@code null} for a run made before brains: no count)
	 * @param winner the winning team, 0 for none
	 * @return the brain of the winner, {@code null} if unknown
	 */
	private static String countBrains(Map<String, int[]> brainWins, Object teamBrains, int winner)
	{
		if (!(teamBrains instanceof Map))
		{
			return null;
		}

		String winnerBrain = null;
		for (Map.Entry<?, ?> entry : ((Map<?, ?>) teamBrains).entrySet())
		{
			final String brain = String.valueOf(entry.getValue());
			final int[] counts = brainWins.computeIfAbsent(brain, k -> new int[2]);
			counts[1]++;
			if (String.valueOf(winner).equals(String.valueOf(entry.getKey())))
			{
				counts[0]++;
				winnerBrain = brain;
			}
		}
		return winnerBrain;
	}

	private static void appendResult(StringBuilder json, int round, int winner, String reason, long duration, String winnerBrain)
	{
		json.append("{\"round\":").append(round).append(",\"winner\":").append(winner).append(",\"winnerBrain\":").append(quoteOrNull(winnerBrain)).append(",\"reason\":").append(quoteOrNull(reason)).append(",\"duration\":").append(duration).append('}');
	}

	// ---------------------------------------------------------------------------------------------
	// One round
	// ---------------------------------------------------------------------------------------------

	private void handleRound(HttpExchange exchange) throws IOException
	{
		final PassiveTreeApiServer server = PassiveTreeApiServer.getInstance();
		if (!authorize(exchange))
		{
			return;
		}

		final Map<String, String> params = server.readParams(exchange);
		final Path round = getRound(exchange, params);
		if (round == null)
		{
			return;
		}

		final String run = SimulationHistory.readText(round.resolve(SimulationHistory.RUN_FILE));
		if ((run == null) || (parseObject(run) == null))
		{
			server.sendText(exchange, 404, "application/json", "{\"error\":\"this round has no run.json yet\"}");
			return;
		}

		final String summary = SimulationHistory.readText(round.resolve(SimulationHistory.SUMMARY_FILE));
		final StringBuilder json = new StringBuilder(run.length() + 65536);
		json.append("{\"run\":").append(run);
		json.append(",\"summary\":").append((summary != null) && (parseObject(summary) != null) ? summary : "null");
		json.append(",\"running\":").append(isRunning(params.get("series"), round));
		json.append(",\"anomalies\":[");
		final List<String> anomalies = new ArrayList<>();
		readLines(round.resolve(SimulationLog.ANOMALIES), null, anomalies, MAX_MEMBER_LINES);
		json.append(String.join(",", anomalies));
		json.append("]}");
		server.sendJson(exchange, 200, json.toString());
	}

	/** @return {@code true} if {@code round} is the round of the running simulation */
	private static boolean isRunning(String series, Path round)
	{
		final FakeSimulationManager manager = FakeSimulationManager.getInstance();
		final SimulationRun run = manager.getCurrentRun();
		return (series != null) && series.equals(manager.getCurrentSeriesName()) && (run != null) && round.getFileName().toString().equals(String.format("round-%02d", run.getRound()));
	}

	private void handlePositions(HttpExchange exchange) throws IOException
	{
		final PassiveTreeApiServer server = PassiveTreeApiServer.getInstance();
		if (!authorize(exchange))
		{
			return;
		}

		final Path round = getRound(exchange, server.readParams(exchange));
		if (round == null)
		{
			return;
		}

		final Path file = round.resolve(SimulationLog.POSITIONS);
		if (!Files.isRegularFile(file))
		{
			server.sendJson(exchange, 200, "{\"strings\":[],\"members\":{}}");
			return;
		}

		final long size = Files.size(file);
		final long modified = Files.getLastModifiedTime(file).toMillis();
		Positions positions;
		synchronized (_positions)
		{
			positions = _positions.get(file);
		}
		if ((positions == null) || (positions.getSize() != size) || (positions.getModified() != modified))
		{
			positions = new Positions(size, modified, buildPositions(file));
			synchronized (_positions)
			{
				_positions.put(file, positions);
			}
		}
		server.sendJson(exchange, 200, positions.getJson());
	}

	/**
	 * Turns positions.jsonl into one set of columns per character. Decisions and skills being cast are numbers into a shared list of strings, which keeps a 9 vs 9 round small.
	 */
	private static String buildPositions(Path file) throws IOException
	{
		final Map<Integer, Track> tracks = new LinkedHashMap<>();
		final Map<String, Integer> strings = new LinkedHashMap<>();
		final List<String> lines = new ArrayList<>();
		readLines(file, null, lines, Integer.MAX_VALUE);
		for (String text : lines)
		{
			final Map<?, ?> line = parseObject(text);
			if (line == null)
			{
				continue;
			}

			final int id = asInt(line.get("id"));
			Track track = tracks.get(id);
			if (track == null)
			{
				track = new Track(asString(line.get("name")), asInt(line.get("team")));
				tracks.put(id, track);
			}

			final String separator = track.first ? "" : ",";
			track.first = false;
			track.t.append(separator).append(asLong(line.get("t")));
			track.x.append(separator).append(asInt(line.get("x")));
			track.y.append(separator).append(asInt(line.get("y")));
			track.z.append(separator).append(asInt(line.get("z")));
			final int maxHp = Math.max(1, asInt(line.get("maxHp")));
			track.hp.append(separator).append(Math.round((asInt(line.get("hp")) * 100.0) / maxHp));
			track.cp.append(separator).append(asInt(line.get("cp")));
			track.alive.append(separator).append(Boolean.TRUE.equals(line.get("alive")) ? 1 : 0);
			track.target.append(separator).append(asInt(line.get("targetId")));
			track.decision.append(separator).append(index(strings, asString(line.get("decision"))));
			track.casting.append(separator).append(index(strings, asString(line.get("casting"))));
		}

		final StringBuilder json = new StringBuilder(64 * 1024);
		json.append("{\"strings\":[");
		boolean first = true;
		for (String string : strings.keySet())
		{
			json.append(first ? "" : ",").append(SimpleJson.quote(string));
			first = false;
		}
		json.append("],\"members\":{");
		first = true;
		for (Map.Entry<Integer, Track> entry : tracks.entrySet())
		{
			final Track track = entry.getValue();
			json.append(first ? "" : ",").append('"').append(entry.getKey()).append("\":{");
			first = false;
			json.append("\"name\":").append(quoteOrNull(track.name)).append(",\"team\":").append(track.team);
			json.append(",\"t\":[").append(track.t).append("],\"x\":[").append(track.x).append("],\"y\":[").append(track.y).append("],\"z\":[").append(track.z);
			json.append("],\"hp\":[").append(track.hp).append("],\"cp\":[").append(track.cp).append("],\"alive\":[").append(track.alive);
			json.append("],\"target\":[").append(track.target).append("],\"d\":[").append(track.decision).append("],\"c\":[").append(track.casting).append("]}");
		}
		json.append("}}");
		return json.toString();
	}

	/** @return the number of {@code string} in {@code strings} (added if new), -1 for {@code null} */
	private static int index(Map<String, Integer> strings, String string)
	{
		if (string == null)
		{
			return -1;
		}
		Integer index = strings.get(string);
		if (index == null)
		{
			index = strings.size();
			strings.put(string, index);
		}
		return index;
	}

	private void handleMember(HttpExchange exchange) throws IOException
	{
		final PassiveTreeApiServer server = PassiveTreeApiServer.getInstance();
		if (!authorize(exchange))
		{
			return;
		}

		final Map<String, String> params = server.readParams(exchange);
		final Path round = getRound(exchange, params);
		if (round == null)
		{
			return;
		}
		final int id = parseInt(params.get("id"), 0);
		if (id <= 0)
		{
			server.sendText(exchange, 400, "application/json", "{\"error\":\"bad id\"}");
			return;
		}

		final String idText = String.valueOf(id);
		final List<String> decisions = new ArrayList<>();
		final List<String> events = new ArrayList<>();
		final List<String> anomalies = new ArrayList<>();
		final boolean decisionsCut = readLines(round.resolve(SimulationLog.DECISIONS), idText, decisions, MAX_MEMBER_LINES);
		keepOwn(decisions, id, false);
		final boolean eventsCut = readLines(round.resolve(SimulationLog.EVENTS), idText, events, MAX_MEMBER_LINES);
		keepOwn(events, id, true);
		readLines(round.resolve(SimulationLog.ANOMALIES), idText, anomalies, MAX_MEMBER_LINES);
		keepOwn(anomalies, id, false);

		final StringBuilder json = new StringBuilder(decisions.size() * 300 + events.size() * 200 + 256);
		json.append("{\"id\":").append(id);
		json.append(",\"cut\":").append(decisionsCut || eventsCut);
		json.append(",\"decisions\":[").append(String.join(",", decisions));
		json.append("],\"events\":[").append(String.join(",", events));
		json.append("],\"anomalies\":[").append(String.join(",", anomalies));
		json.append("]}");
		server.sendJson(exchange, 200, json.toString());
	}

	/**
	 * Keeps the lines about character {@code id}: its own ({@code "id"}), or with {@code anyRole}, also those naming it as attacker, target, healer, caster... ({@code "...Id"}).
	 */
	private static void keepOwn(List<String> lines, int id, boolean anyRole)
	{
		lines.removeIf(text ->
		{
			final Map<?, ?> line = parseObject(text);
			if (line == null)
			{
				return true;
			}
			for (Map.Entry<?, ?> entry : line.entrySet())
			{
				final String key = String.valueOf(entry.getKey());
				if ((key.equals("id") || (anyRole && key.endsWith("Id") && !key.equals("runId"))) && (entry.getValue() instanceof Number) && (((Number) entry.getValue()).intValue() == id))
				{
					return false;
				}
			}
			return true;
		});
	}

	// ---------------------------------------------------------------------------------------------
	// Actions
	// ---------------------------------------------------------------------------------------------

	private void handleStart(HttpExchange exchange) throws IOException
	{
		final PassiveTreeApiServer server = PassiveTreeApiServer.getInstance();
		final Player gm = getGm(exchange);
		if ((gm == null) && !FakeSimulationConfig.WEB_OPEN_ACCESS)
		{
			return;
		}

		final Map<String, String> params = server.readParams(exchange);
		final int size = parseInt(params.get("size"), 0);
		final int level = Math.max(1, Math.min(85, parseInt(params.get("level"), gm != null ? gm.getLevel() : DEFAULT_LEVEL)));
		final boolean mirror = "1".equals(params.get("mirror")) || "true".equals(params.get("mirror"));
		final boolean swap = "1".equals(params.get("swap")) || "true".equals(params.get("swap"));
		final String brains = (params.get("brains") == null) || params.get("brains").trim().isEmpty() ? null : params.get("brains").trim();
		final int rounds = Math.max(1, Math.min(100, parseInt(params.get("rounds"), 1)));
		final int time = Math.max(10, parseInt(params.get("time"), FakeSimulationConfig.TIME_LIMIT));
		long seed;
		try
		{
			final String text = params.get("seed");
			seed = (text == null) || text.trim().isEmpty() ? Rnd.get(1, Integer.MAX_VALUE) : Long.parseLong(text.trim());
		}
		catch (NumberFormatException e)
		{
			server.sendText(exchange, 400, "application/json", "{\"error\":\"the seed must be a whole number\"}");
			return;
		}

		final String source = params.get("points");
		final FakeSimulationManager manager = FakeSimulationManager.getInstance();
		List<SimulationPoint> points = manager.getPointsOf(source);
		if ((source != null) && !source.isEmpty() && !source.equals("saved") && (points == null))
		{
			server.sendText(exchange, 404, "application/json", "{\"error\":" + SimpleJson.quote("No simulation " + source + " to take the points of.") + "}");
			return;
		}
		String pointsFrom = points != null ? source : null;

		// Swapped points: every team keeps its number (so its builds, with the same seed) but spawns at the next team's point, to tell what the place does from what the team does.
		if ("1".equals(params.get("swapPoints")) || "true".equals(params.get("swapPoints")))
		{
			points = swapPoints(points != null ? points : new ArrayList<>(manager.getPoints().values()));
			pointsFrom = (pointsFrom != null ? pointsFrom : "saved") + ", swapped";
		}

		final List<String> lines = manager.simulate(gm, size, level, mirror, seed, rounds, time, brains, swap, points, pointsFrom, null);
		sendLines(exchange, lines);
	}

	/**
	 * @param points spawn points
	 * @return the same points, ordered by id, each with the place of the next one (the last one takes the first one's)
	 */
	private static List<SimulationPoint> swapPoints(List<SimulationPoint> points)
	{
		final List<SimulationPoint> sorted = new ArrayList<>(points);
		sorted.sort((a, b) -> Integer.compare(a.id(), b.id()));
		final List<SimulationPoint> swapped = new ArrayList<>(sorted.size());
		for (int i = 0; i < sorted.size(); i++)
		{
			final SimulationPoint place = sorted.get((i + 1) % sorted.size());
			swapped.add(new SimulationPoint(sorted.get(i).id(), place.x(), place.y(), place.z(), place.heading(), place.instanceId()));
		}
		return swapped;
	}

	private void handleRerun(HttpExchange exchange) throws IOException
	{
		final PassiveTreeApiServer server = PassiveTreeApiServer.getInstance();
		final Player gm = getGm(exchange);
		if ((gm == null) && !FakeSimulationConfig.WEB_OPEN_ACCESS)
		{
			return;
		}

		final Map<String, String> params = server.readParams(exchange);
		sendLines(exchange, FakeSimulationManager.getInstance().rerun(gm, params.get("series"), params.get("points")));
	}

	private void handleStop(HttpExchange exchange) throws IOException
	{
		final Player gm = getGm(exchange);
		if ((gm == null) && !FakeSimulationConfig.WEB_OPEN_ACCESS)
		{
			return;
		}

		final List<String> lines = new ArrayList<>();
		lines.add(FakeSimulationManager.getInstance().stop(gm));
		sendLines(exchange, lines);
	}

	// ---------------------------------------------------------------------------------------------
	// Class matrix
	// ---------------------------------------------------------------------------------------------

	/** Where a matrix started from the web without a GM's place fights: the open field by Cruma Marshlands (also in the GM teleport menu). */
	private static final int[] MATRIX_FIELD =
	{
		20480,
		104448,
		-3700,
		0
	};

	private void handleMatrixStart(HttpExchange exchange) throws IOException
	{
		final PassiveTreeApiServer server = PassiveTreeApiServer.getInstance();
		final Player gm = getGm(exchange);
		if ((gm == null) && !FakeSimulationConfig.WEB_OPEN_ACCESS)
		{
			return;
		}

		final Map<String, String> params = server.readParams(exchange);
		final boolean here = (gm != null) && (gm.getInstanceId() == 0);
		final int x = parseInt(params.get("x"), here ? gm.getX() : MATRIX_FIELD[0]);
		final int y = parseInt(params.get("y"), here ? gm.getY() : MATRIX_FIELD[1]);
		final int z = parseInt(params.get("z"), here ? gm.getZ() : MATRIX_FIELD[2]);
		final int heading = parseInt(params.get("heading"), here ? gm.getHeading() : MATRIX_FIELD[3]);
		final int battles = Math.max(1, Math.min(1000, parseInt(params.get("battles"), 20)));
		final int parallel = Math.max(1, Math.min(1000, parseInt(params.get("parallel"), 100)));
		final int level = Math.max(1, Math.min(85, parseInt(params.get("level"), 85)));
		final int time = Math.max(10, parseInt(params.get("time"), FakeSimulationConfig.TIME_LIMIT));
		final int distance = Math.max(100, Math.min(3000, parseInt(params.get("distance"), 1000)));
		final String brain = (params.get("brain") == null) || params.get("brain").trim().isEmpty() ? null : params.get("brain").trim();
		final String builds = (params.get("builds") == null) || params.get("builds").trim().isEmpty() ? null : params.get("builds").trim();
		long seed;
		try
		{
			final String text = params.get("seed");
			seed = (text == null) || text.trim().isEmpty() ? Rnd.get(1, Integer.MAX_VALUE) : Long.parseLong(text.trim());
		}
		catch (NumberFormatException e)
		{
			server.sendText(exchange, 400, "application/json", "{\"error\":\"the seed must be a whole number\"}");
			return;
		}
		sendMatrixLines(exchange, FakeSimulationManager.getInstance().startMatrix(gm, x, y, z, heading, battles, parallel, level, time, distance, brain, "1".equals(params.get("mirror")) || "true".equals(params.get("mirror")), builds, seed));
	}

	private void handleMatrixResume(HttpExchange exchange) throws IOException
	{
		final PassiveTreeApiServer server = PassiveTreeApiServer.getInstance();
		final Player gm = getGm(exchange);
		if ((gm == null) && !FakeSimulationConfig.WEB_OPEN_ACCESS)
		{
			return;
		}

		final Map<String, String> params = server.readParams(exchange);
		final String name = (params.get("name") == null) || params.get("name").trim().isEmpty() ? null : params.get("name").trim();
		sendMatrixLines(exchange, FakeSimulationManager.getInstance().resumeMatrix(gm, name, Math.max(0, Math.min(1000, parseInt(params.get("parallel"), 0)))));
	}

	private void handleMatrixStop(HttpExchange exchange) throws IOException
	{
		final Player gm = getGm(exchange);
		if ((gm == null) && !FakeSimulationConfig.WEB_OPEN_ACCESS)
		{
			return;
		}

		final List<String> lines = new ArrayList<>();
		lines.add(FakeSimulationManager.getInstance().stopMatrix());
		sendMatrixLines(exchange, lines);
	}

	private void handleMatrixStatus(HttpExchange exchange) throws IOException
	{
		if (!authorize(exchange))
		{
			return;
		}
		sendMatrixLines(exchange, FakeSimulationManager.getInstance().getMatrixStatus());
	}

	/** Answers with what the manager said, and the matrix running now: its name, folder, battles done, running and in all. */
	private static void sendMatrixLines(HttpExchange exchange, List<String> lines) throws IOException
	{
		final SimulationMatrix matrix = FakeSimulationManager.getInstance().getMatrix();
		final StringBuilder json = new StringBuilder("{\"matrix\":");
		if (matrix != null)
		{
			json.append("{\"name\":").append(SimpleJson.quote(matrix.getName())).append(",\"dir\":").append(SimpleJson.quote(matrix.getDir().toAbsolutePath().toString())).append(",\"done\":").append(matrix.getDone()).append(",\"running\":").append(matrix.getRunning()).append(",\"total\":").append(matrix.getTotal()).append(",\"stopping\":").append(matrix.isStopped()).append('}');
		}
		else
		{
			json.append("null");
		}
		json.append(",\"lines\":[");
		for (int i = 0; i < lines.size(); i++)
		{
			json.append(i > 0 ? "," : "").append(SimpleJson.quote(lines.get(i)));
		}
		json.append("]}");
		PassiveTreeApiServer.getInstance().sendJson(exchange, 200, json.toString());
	}

	/** Answers with what the manager said, and the simulation running now (set if it started). */
	private static void sendLines(HttpExchange exchange, List<String> lines) throws IOException
	{
		final StringBuilder json = new StringBuilder();
		json.append("{\"series\":").append(quoteOrNull(FakeSimulationManager.getInstance().getCurrentSeriesName())).append(",\"lines\":[");
		for (int i = 0; i < lines.size(); i++)
		{
			json.append(i > 0 ? "," : "").append(SimpleJson.quote(lines.get(i)));
		}
		json.append("]}");
		PassiveTreeApiServer.getInstance().sendJson(exchange, 200, json.toString());
	}

	// ---------------------------------------------------------------------------------------------
	// Points
	// ---------------------------------------------------------------------------------------------

	private void handlePoints(HttpExchange exchange) throws IOException
	{
		final PassiveTreeApiServer server = PassiveTreeApiServer.getInstance();
		if (!authorize(exchange))
		{
			return;
		}

		final List<SimulationPoint> points = new ArrayList<>(FakeSimulationManager.getInstance().getPoints().values());
		final StringBuilder json = new StringBuilder(1024);
		json.append("{\"spawnRadius\":").append(FakeSimulationConfig.SPAWN_RADIUS).append(",\"points\":[");
		for (int i = 0; i < points.size(); i++)
		{
			final SimulationPoint point = points.get(i);
			json.append(i > 0 ? "," : "").append("{\"id\":").append(point.id()).append(",\"x\":").append(point.x()).append(",\"y\":").append(point.y()).append(",\"z\":").append(point.z()).append(",\"heading\":").append(point.heading()).append(",\"instance\":").append(point.instanceId()).append('}');
		}
		json.append("],\"links\":[");
		boolean first = true;
		for (SimulationPoint point : points)
		{
			for (SimulationPoint other : points)
			{
				if (other.id() > point.id())
				{
					json.append(first ? "" : ",").append("{\"a\":").append(point.id()).append(",\"b\":").append(other.id()).append(",\"distance\":").append((int) point.distance2D(other)).append(",\"los\":").append(FakeSimulationManager.canSee(point, other)).append('}');
					first = false;
				}
			}
		}
		json.append("]}");
		server.sendJson(exchange, 200, json.toString());
	}

	private void handleClearPoint(HttpExchange exchange) throws IOException
	{
		final PassiveTreeApiServer server = PassiveTreeApiServer.getInstance();
		if (!authorize(exchange))
		{
			return;
		}

		final String id = server.readParams(exchange).get("id");
		final List<String> lines = new ArrayList<>();
		lines.add(id != null ? FakeSimulationManager.getInstance().clearPoint(id) : "Which point?");
		sendLines(exchange, lines);
	}

	private void handleDrawPoints(HttpExchange exchange) throws IOException
	{
		final Player gm = getGm(exchange);
		if ((gm == null) && !FakeSimulationConfig.WEB_OPEN_ACCESS)
		{
			return;
		}

		final List<String> lines = new ArrayList<>();
		if (gm == null)
		{
			lines.add("Drawing the points needs a GM in game: open this page with //SimulationWeb.");
			sendLines(exchange, lines);
			return;
		}
		FakeSimulationManager.getInstance().drawPoints(gm);
		lines.add("The points are drawn in game around " + gm.getName() + ".");
		sendLines(exchange, lines);
	}

	// ---------------------------------------------------------------------------------------------
	// Helpers
	// ---------------------------------------------------------------------------------------------

	/** @return {@code true} if the request may go on: it has a GM's token, or SimulationWebOpenAccess lets anyone in (otherwise the error answer was sent) */
	private static boolean authorize(HttpExchange exchange) throws IOException
	{
		return FakeSimulationConfig.WEB_OPEN_ACCESS || (PassiveTreeApiServer.getInstance().authenticateAdmin(exchange, ADMIN_COMMAND) != null);
	}

	/**
	 * @return the GM of the request's token. Without a valid one: with SimulationWebOpenAccess {@code null} (anyone may go on), otherwise {@code null} after the error answer was sent.
	 */
	private static Player getGm(HttpExchange exchange) throws IOException
	{
		final PassiveTreeApiServer server = PassiveTreeApiServer.getInstance();
		return FakeSimulationConfig.WEB_OPEN_ACCESS ? server.findAdmin(exchange, ADMIN_COMMAND) : server.authenticateAdmin(exchange, ADMIN_COMMAND);
	}

	/** @return the round folder named by the series and round parameters, {@code null} after the error answer was sent */
	private static Path getRound(HttpExchange exchange, Map<String, String> params) throws IOException
	{
		final Path series = SimulationHistory.resolveSeries(params.get("series"));
		if (series == null)
		{
			PassiveTreeApiServer.getInstance().sendText(exchange, 404, "application/json", "{\"error\":\"no such simulation\"}");
			return null;
		}
		final Path round = SimulationHistory.resolveRound(series, parseInt(params.get("round"), 1));
		if (round == null)
		{
			PassiveTreeApiServer.getInstance().sendText(exchange, 404, "application/json", "{\"error\":\"no such round\"}");
			return null;
		}
		return round;
	}

	/**
	 * Reads the JSON lines of a file. A round still running may end with half a line, which is left out.
	 * @param file the file
	 * @param contains only the lines with this text, {@code null} for all
	 * @param lines where to add them
	 * @param max at most this many
	 * @return {@code true} if lines were left out because of {@code max}
	 */
	private static boolean readLines(Path file, String contains, List<String> lines, int max) throws IOException
	{
		if (!Files.isRegularFile(file))
		{
			return false;
		}

		try (BufferedReader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8))
		{
			String previous = null;
			String line;
			while ((line = reader.readLine()) != null)
			{
				if (previous != null)
				{
					if (lines.size() >= max)
					{
						return true;
					}
					lines.add(previous);
					previous = null;
				}
				final String text = line.trim();
				if (text.startsWith("{") && text.endsWith("}") && ((contains == null) || text.contains(contains)))
				{
					previous = text;
				}
			}
			// The last line may be cut while the round is written: only if it reads.
			if ((previous != null) && (parseObject(previous) != null) && (lines.size() < max))
			{
				lines.add(previous);
			}
		}
		return false;
	}

	private static Map<?, ?> parseObject(String text)
	{
		if (text == null)
		{
			return null;
		}
		try
		{
			final Object parsed = SimpleJson.parse(text);
			return parsed instanceof Map ? (Map<?, ?>) parsed : null;
		}
		catch (IllegalArgumentException e)
		{
			return null;
		}
	}

	private static String ratio(double value, double max)
	{
		return max > 0 ? String.valueOf(Math.round((value * 1000) / max) / 1000.0) : "0";
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
		return value instanceof Number ? ((Number) value).intValue() : 0;
	}

	private static long asLong(Object value)
	{
		return value instanceof Number ? ((Number) value).longValue() : 0;
	}

	private static int parseInt(String text, int fallback)
	{
		if (text == null)
		{
			return fallback;
		}
		try
		{
			return Integer.parseInt(text.trim());
		}
		catch (NumberFormatException e)
		{
			return fallback;
		}
	}

	public static SimulationWeb getInstance()
	{
		return SingletonHolder.INSTANCE;
	}

	private static class SingletonHolder
	{
		protected static final SimulationWeb INSTANCE = new SimulationWeb();
	}
}
