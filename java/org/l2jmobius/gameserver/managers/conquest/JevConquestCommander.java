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
package org.l2jmobius.gameserver.managers.conquest;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Logger;

import org.l2jmobius.commons.util.SimpleJson;
import org.l2jmobius.gameserver.config.custom.ConquestConfig;
import org.l2jmobius.gameserver.managers.conquest.GenericConquestCommander.Plan;
import org.l2jmobius.gameserver.model.Location;
import org.l2jmobius.gameserver.model.actor.enums.creature.Team;
import org.l2jmobius.gameserver.model.conquest.ConquestPoint;

/**
 * Brain B of Conquest: JEV, TypeSafe's decision model (https://docs.typesafe.ai), decides how the team's force is shared out, and code gives the parties their orders by it.
 * <ul>
 * <li>Every {@link ConquestConfig#JEV_INTERVAL} ms (5 seconds), and as soon as something happens that matters (a point starts to move toward a team, a point changes hands; at most once a second; a wave coming back is not news, it seldom changed anything), it asks JEV, in one request, one Choice
 * question per capture point and one for hunting the enemy groups: how much of our force should go there (most, much, little or none), and which enemy groups the hunters go for. The state it sends is the whole field: what just happened, the scores, the
 * points with who holds them, who stands near them and how far each team's spawn walks to them, the biggest enemy groups, and what the plain commander ({@link GenericConquestCommander}) would send where.</li>
 * <li>JEV's probabilities make each option's weight (the levels weighted by how likely JEV finds each). The living parties are shared out by those weights: a party keeps its point
 * while the point's share still holds it, and the rest go to the points short of their share, the nearest first. Parties sent to take a point finish the job unless JEV loses most of its interest.</li>
 * <li>Questions in a request are answered each on its own, so no question chooses for a party; that is why the sharing is left to the code (one question per party sent them all to the same point).</li>
 * <li>Every JEV commander (both teams, if both have one) shares {@link ConquestConfig#JEV_MAX_REQUESTS_PER_SECOND} requests a second, under the account's 40.</li>
 * <li>When it can't ask (the limit is reached, TypeSafe said too many or is overloaded, a request failed or took too long, no API key) or JEV wants the parties nowhere, the plain commander leads at its own pace until JEV answers again. After a 429 or 529
 * every JEV commander waits, longer each time, before asking again.</li>
 * </ul>
 * The answers come back on the HTTP client's threads and are only read at the commander's next turn, so the orders are always given on the command thread.
 */
public class JevConquestCommander implements ConquestCommander
{
	private static final Logger LOGGER = Logger.getLogger(JevConquestCommander.class.getName());

	private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
	/** The option of the parties that hunt the enemy groups away from the points (the points' are "point:" and their name). */
	private static final String FIGHT = "fight";
	/** The question of which enemy groups the hunters go for, and how many groups (the biggest) it offers. */
	private static final String HUNT = "hunt_target";
	private static final int HUNT_OPTIONS = 5;
	/** The answers to each question, and what each is worth to the option's weight. */
	private static final String[] LEVELS =
	{
		"most",
		"much",
		"little",
		"none"
	};
	private static final double[] LEVEL_WEIGHTS =
	{
		1,
		0.6,
		0.25,
		0
	};
	/** The least time between two questions when something happens. */
	private static final long EVENT_GAP = 1000;
	/** A point that starts to move toward a team again within this long of its last move toward it is not news. */
	private static final long MOVE_QUIET = 5000;
	/** Parties sent to take a point keep going while its weight stays above this share of {@link ConquestConfig#JEV_MIN_CONFIDENCE} (0.15 of 0.2), until it is ours. */
	private static final double FINISH = 0.75;
	/** The odd places left after each option's whole parties go first to options that have a party there already, by this much of a party. */
	private static final double STICK = 0.25;
	/** After a 429 or 529, no JEV commander asks for this long, doubled each time up to the most. */
	private static final long BACKOFF_FIRST = 1000;
	private static final long BACKOFF_MOST = 30000;

	private static final String RULES = "Conquest: two teams of parties of 5 fight over capture points. Every second the team that holds fewer points loses score by how many more the other team holds, and the team that holds more loses nothing; when they hold as many, each loses 1. The team whose score reaches 0, or that has less when the time is up, loses. A point moves toward the team with more fighters on it, but only while it has a quarter more than the other team; an empty point stays as it is. A dead fighter comes back at its base with the next wave.";

	// The requests of every JEV commander in the last second, and the wait after TypeSafe said too many: one account.
	private static final Deque<Long> SENT = new ArrayDeque<>();
	private static long _backoffUntil;
	private static long _backoff;

	/** A turn out at JEV: its request, the option each question is about, and the enemy groups offered to the hunters. */
	private record Turn(long sent, int time, CompletableFuture<HttpResponse<String>> request, String body, Map<String, String> options, List<Hunted> hunted)
	{
	}

	/**
	 * An enemy group as JEV is told of it.
	 * @param group {x, y, z, fighters}
	 * @param point the capture point nearest it by the way it would walk
	 * @param walk that walk, from the nav grid's distance field of the point
	 */
	private record Hunted(int[] group, ConquestCapture point, long walk)
	{
		Location location()
		{
			return new Location(group[0], group[1], group[2]);
		}
	}

	/**
	 * JEV's last answer as it was used, for the live page.
	 * @param answeredAt when it was read
	 * @param latency how long after the question
	 * @param why what the question was asked about
	 * @param options each option's probabilities (as {@link #LEVELS}), weight, and parties before and after
	 * @param groups the enemy groups offered to the hunters
	 * @param moved the parties sent elsewhere
	 */
	private record Report(long answeredAt, long latency, String why, List<ReportOption> options, List<ReportGroup> groups, int moved)
	{
	}

	private record ReportOption(String name, double[] probabilities, double weight, int before, int after)
	{
	}

	private record ReportGroup(int fighters, String point, double probability, int hunters)
	{
	}

	private final GenericConquestCommander _generic = new GenericConquestCommander();
	// The plain commander's plan, worked out every COMMANDER_INTERVAL seconds.
	private Map<ConquestParty, Plan> _plain = Collections.emptyMap();
	private long _plainAt;
	// When the plain commander last led for want of JEV.
	private long _fallbackAt;
	private volatile Turn _turn;
	// JEV's weight for each option, from its last answer.
	private final Map<String, Double> _weights = new LinkedHashMap<>();
	// When it last asked and why, and what happened since that it should ask about (null: nothing).
	private volatile long _askedAt;
	private volatile String _askedWhy = "";
	private String _news;
	private volatile Report _report;
	// What it saw at its last look, to tell what happened: each point's holder, and which way each point last moved (1 toward us, -1 toward them) and when.
	private final Map<String, Team> _holders = new HashMap<>();
	private final Map<String, Integer> _moving = new HashMap<>();
	private final Map<String, Long> _movedAt = new HashMap<>();
	// The walk from our nearest spawn and from theirs to each point, by its name: read once from the nav grid's distance fields, null until it is built.
	private Map<String, long[]> _spawnWalks;
	// For the GM page.
	private volatile int _turns;
	private volatile int _newsTurns;
	private volatile int _requests;
	private volatile int _moves;
	private volatile int _limited;
	private volatile int _failed;
	private volatile int _fallbacks;
	private volatile long _latency;
	private volatile String _shares = "";
	private volatile String _problem = "";
	// The log of its questions and answers (ConquestJevLog), null before the first turn or when it can't be written.
	private Path _log;
	private boolean _logFailed;

	@Override
	public String getName()
	{
		return "jev";
	}

	@Override
	public long getInterval()
	{
		return ConquestManager.COMMAND_STEP_MS; // It looks every step for news; it asks JEV far less often.
	}

	@Override
	public String getStatus()
	{
		final StringBuilder sb = new StringBuilder();
		sb.append(_turns).append(" turns (").append(_newsTurns).append(" on news), ").append(_requests).append(" requests, ").append(_moves).append(" parties moved, ");
		sb.append(_limited).append(" limited, ").append(_failed).append(" failed, ").append(_fallbacks).append(" plain turns, ").append(_latency).append(" ms");
		if (!_shares.isEmpty())
		{
			sb.append("; parties: ").append(_shares);
		}
		if (!_problem.isEmpty())
		{
			sb.append("; plain now: ").append(_problem);
		}
		return sb.toString();
	}

	@Override
	public String getReport(long now)
	{
		final long askedAt = _askedAt;
		final StringBuilder json = new StringBuilder(1024);
		json.append("{\"askedAgo\":").append(askedAt > 0 ? now - askedAt : -1).append(",\"why\":").append(SimpleJson.quote(_askedWhy)).append(",\"waiting\":").append(_turn != null);
		json.append(",\"nextIn\":").append(askedAt > 0 ? Math.max(0, (askedAt + ConquestConfig.JEV_INTERVAL) - now) : 0);
		json.append(",\"turns\":").append(_turns).append(",\"newsTurns\":").append(_newsTurns).append(",\"plain\":").append(SimpleJson.quote(_problem));
		final Report report = _report;
		json.append(",\"last\":");
		if (report == null)
		{
			return json.append("null}").toString();
		}
		json.append("{\"answeredAgo\":").append(now - report.answeredAt()).append(",\"latency\":").append(report.latency()).append(",\"why\":").append(SimpleJson.quote(report.why())).append(",\"moved\":").append(report.moved());
		json.append(",\"options\":[");
		for (int i = 0; i < report.options().size(); i++)
		{
			final ReportOption option = report.options().get(i);
			json.append(i > 0 ? "," : "").append("{\"name\":").append(SimpleJson.quote(option.name())).append(",\"p\":[");
			for (int j = 0; j < option.probabilities().length; j++)
			{
				json.append(j > 0 ? "," : "").append(String.format(Locale.ROOT, "%.3f", option.probabilities()[j]));
			}
			json.append("],\"weight\":").append(String.format(Locale.ROOT, "%.3f", option.weight())).append(",\"before\":").append(option.before()).append(",\"after\":").append(option.after()).append('}');
		}
		json.append("],\"groups\":[");
		for (int i = 0; i < report.groups().size(); i++)
		{
			final ReportGroup group = report.groups().get(i);
			json.append(i > 0 ? "," : "").append("{\"fighters\":").append(group.fighters()).append(",\"point\":").append(SimpleJson.quote(group.point()));
			json.append(",\"p\":").append(String.format(Locale.ROOT, "%.3f", group.probability())).append(",\"hunters\":").append(group.hunters()).append('}');
		}
		return json.append("]}}").toString();
	}

	@Override
	public void command(ConquestMatch match, ConquestTeam team, long now)
	{
		if ((now - _plainAt) >= (ConquestConfig.COMMANDER_INTERVAL * 1000L))
		{
			_plain = _generic.plan(match, team);
			_plainAt = now;
		}

		// What happened since the last look: kept until JEV is asked.
		final String news = news(match, team, now);
		if (news != null)
		{
			_news = _news == null ? news : _news + "; " + news;
		}

		// The last turn's answers, once they are back.
		final Turn turn = _turn;
		if (turn != null)
		{
			if (turn.request().isDone())
			{
				_turn = null;
				finish(match, team, turn, now);
			}
			else if ((now - turn.sent()) > (ConquestConfig.JEV_TIMEOUT + 500L))
			{
				_turn = null;
				turn.request().cancel(true);
				_failed++;
				fallback(match, team, "no answer in " + ConquestConfig.JEV_TIMEOUT + " ms", now);
				return;
			}
			else
			{
				return; // Still out: the orders stand.
			}
		}

		if (ConquestConfig.JEV_API_KEY.isEmpty())
		{
			fallback(match, team, "no API key (ConquestJevApiKey or TYPESAFE_API_KEY)", now);
			return;
		}

		// Every JEV_INTERVAL, or on news (not twice within EVENT_GAP).
		final long since = now - _askedAt;
		if ((since < ConquestConfig.JEV_INTERVAL) && ((_news == null) || (since < EVENT_GAP)))
		{
			return;
		}
		final String why = _news != null ? _news : "nothing new, " + (ConquestConfig.JEV_INTERVAL / 1000.0) + " seconds since the last look";
		if (_news != null)
		{
			_newsTurns++;
		}
		_news = null;
		_askedAt = now;
		_askedWhy = why;
		ask(match, team, why, now);
	}

	/**
	 * Looks at what happened since the last look: a point that starts to move toward a team, a point that changed hands.
	 * @return what happened, {@code null} for nothing (and at the first look)
	 */
	private String news(ConquestMatch match, ConquestTeam team, long now)
	{
		final List<String> news = new ArrayList<>();
		final ConquestTeam enemy = match.getOther(team);

		for (ConquestCapture capture : match.getCaptures())
		{
			final String name = capture.getName();

			// Changed hands (or went neutral).
			final Team holder = capture.getOwner();
			final Team before = _holders.put(name, holder);
			if ((before != null) && (before != holder))
			{
				if (holder == team.getTeam())
				{
					news.add("we took point " + name);
				}
				else if (holder == enemy.getTeam())
				{
					news.add("they took point " + name + (before == team.getTeam() ? " from us" : ""));
				}
				else
				{
					news.add("point " + name + " is neutral now, " + (before == team.getTeam() ? "taken from us" : "taken from them"));
				}
			}

			// Starts to move toward a team: where the capture rule takes it in the next second with the fighters on it now.
			final double progress = capture.getProgress();
			final double next = ConquestCapture.step(progress, capture.getBlue(), capture.getRed(), ConquestConfig.CAPTURE_BASE_RATE, ConquestConfig.CAPTURE_RATE_PER_EXTRA, ConquestConfig.CAPTURE_MAX_RATE, ConquestConfig.CAPTURE_MAJORITY_PERCENT, ConquestConfig.CAPTURE_STALEMATE_COUNT);
			final int toward = next == progress ? 0 : ((next > progress) == (team.getTeam() == Team.BLUE)) ? 1 : -1;
			if (toward != 0)
			{
				final Integer last = _moving.get(name);
				final Long lastAt = _movedAt.get(name);
				if ((last == null) || (last != toward) || ((now - lastAt) > MOVE_QUIET))
				{
					news.add(toward > 0 ? "we are taking point " + name : "they are taking point " + name + (holder == team.getTeam() ? " from us" : ""));
				}
				_moving.put(name, toward);
				_movedAt.put(name, now);
			}
		}
		return news.isEmpty() ? null : String.join("; ", news);
	}

	/**
	 * Asks JEV how much of the force each point, and the hunt, should get.
	 */
	private void ask(ConquestMatch match, ConquestTeam team, String why, long now)
	{
		final List<ConquestParty> parties = living(team);
		if (parties.isEmpty())
		{
			return;
		}
		if (!reserve(now))
		{
			_limited++;
			fallback(match, team, "request limit", now);
			return;
		}

		// What the plain plan would send where: a hint in the state and in each question.
		final Map<String, Integer> plain = new HashMap<>();
		for (Map.Entry<ConquestParty, Plan> entry : _plain.entrySet())
		{
			if (entry.getKey().getLocation() != null)
			{
				plain.merge(option(entry.getValue()), 1, Integer::sum);
			}
		}

		final List<ConquestCapture> captures = match.getCaptures();
		final Map<String, String> options = new LinkedHashMap<>();
		final StringBuilder questions = new StringBuilder();
		for (int i = 0; i < captures.size(); i++)
		{
			final ConquestCapture capture = captures.get(i);
			final String name = "point_" + (i + 1);
			final String option = "point:" + capture.getName();
			options.put(name, option);
			final String instructions = "Point " + capture.getName() + " (capture_points[" + i + "], held by " + holder(capture, team) + "): how much of our force should go to it now? Weigh what it does to the score (score_if_we_take_it, score_if_they_take_it), what taking or keeping it needs (a quarter more of our fighters on it than theirs; an empty point needs one party), what the other points need, and the walks: ours to it, and theirs (they reinforce a point near their spawn fast). The plain plan, a simple rule, sends " + plain.getOrDefault(option, 0) + " of our " + parties.size() + " parties there.";
			questions.append(questions.length() > 0 ? "," : "").append(SimpleJson.quote(name)).append(':');
			question(questions, instructions, "Most of our parties: it decides the fight now.", "A good share: two parties or more, to take it from its defenders or hold it against an attack.", "One party: enough to take an empty or lightly held point, or to watch one of ours.", "None: it is ours and nobody comes for it, or we can't take it from here.");
		}
		options.put(FIGHT, FIGHT);
		final String instructions = "How much of our force should hunt the enemy groups (enemy_groups) away from the points, to thin them out before they reach a point? The plain plan sends " + plain.getOrDefault(FIGHT, 0) + " of our " + parties.size() + " parties to hunt.";
		questions.append(',').append(SimpleJson.quote(FIGHT)).append(':');
		question(questions, instructions, "Most of our parties hunt: the points can wait.", "A good share hunts.", "A party or two hunt the enemies nearest our points.", "Nobody hunts: every party is needed at the points.");

		// The biggest enemy groups, for the state and for the hunters to choose from.
		final List<Hunted> hunted = hunted(match, team, captures);
		if (!hunted.isEmpty())
		{
			questions.append(',').append(SimpleJson.quote(HUNT)).append(":{\"type\":\"choice\",\"instructions\":");
			questions.append(SimpleJson.quote("Which enemy group should our hunting parties go for (enemy_groups)? The hunters are shared out by how likely you find each group the best target: one about to take or retake a point, a big one before it reaches our points, or a small one we can wipe out on our way.")).append(",\"criteria\":{");
			for (int i = 0; i < hunted.size(); i++)
			{
				final Hunted target = hunted.get(i);
				final ConquestCapture point = target.point();
				questions.append(i > 0 ? "," : "").append(SimpleJson.quote("group_" + (i + 1))).append(':');
				questions.append(SimpleJson.quote(target.group()[3] + " fighters, " + target.walk() + " from point " + point.getName() + " (held by " + holder(point, team) + "), " + match.getNear(team, target.group()[0], target.group()[1], GenericConquestCommander.GROUP_CELL).size() + " of ours near them"));
			}
			questions.append("}}");
		}

		if ((_spawnWalks == null) && match.getNav().isReady())
		{
			_spawnWalks = spawnWalks(match, team, captures);
		}
		final String body = "{\"model\":" + SimpleJson.quote(ConquestConfig.JEV_MODEL) + ",\"state\":" + state(match, team, match.getOther(team), captures, parties, plain, _spawnWalks, hunted, why, now) + ",\"questions\":{" + questions + "}}";
		final HttpRequest request = HttpRequest.newBuilder(URI.create(ConquestConfig.JEV_URL)).timeout(Duration.ofMillis(ConquestConfig.JEV_TIMEOUT)).header("Authorization", "Bearer " + ConquestConfig.JEV_API_KEY).header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8)).build();
		_turn = new Turn(now, match.getTime(), HTTP.sendAsync(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)), body, options, hunted);
		_requests++;
		_turns++;
		if (!_problem.isEmpty())
		{
			LOGGER.info(getClass().getSimpleName() + ": " + team.getName() + " asks JEV again.");
			_problem = "";
		}
	}

	private static void question(StringBuilder json, String instructions, String most, String much, String little, String none)
	{
		final String[] texts =
		{
			most,
			much,
			little,
			none
		};
		json.append("{\"type\":\"choice\",\"instructions\":").append(SimpleJson.quote(instructions)).append(",\"criteria\":{");
		for (int i = 0; i < LEVELS.length; i++)
		{
			json.append(i > 0 ? "," : "").append(SimpleJson.quote(LEVELS[i])).append(':').append(SimpleJson.quote(texts[i]));
		}
		json.append("}}");
	}

	/**
	 * Reads JEV's answers into the weights and shares the parties out by them.
	 */
	@SuppressWarnings("unchecked")
	private void finish(ConquestMatch match, ConquestTeam team, Turn turn, long now)
	{
		_latency = now - turn.sent();
		Map<String, Object> answers = null;
		int status = 0;
		String reply;
		String problem = null;
		try
		{
			final HttpResponse<String> response = turn.request().join();
			status = response.statusCode();
			reply = response.body();
			if (status == 200)
			{
				answers = (Map<String, Object>) ((Map<String, Object>) SimpleJson.parse(reply)).get("answers");
			}
			else if ((status == 429) || (status == 529))
			{
				throttled(now);
				_limited++;
				problem = status == 429 ? "TypeSafe: too many requests (429)" : "TypeSafe: overloaded (529)";
			}
			else
			{
				_failed++;
				problem = "TypeSafe answered " + status + ": " + (reply.length() > 200 ? reply.substring(0, 200) : reply);
			}
		}
		catch (Exception e)
		{
			_failed++;
			final Throwable cause = e.getCause() != null ? e.getCause() : e;
			problem = "request failed: " + cause;
			reply = cause.toString();
		}

		// Each option's weight: the levels, weighted by how likely JEV finds each (and the probabilities themselves, for the live page).
		final Map<String, Double> raw = new LinkedHashMap<>();
		final Map<String, double[]> probabilities = new HashMap<>();
		if (answers != null)
		{
			for (Map.Entry<String, String> entry : turn.options().entrySet())
			{
				final Object answer = answers.get(entry.getKey());
				if (answer instanceof Map<?, ?> map)
				{
					final Double weight = weight((Map<String, Object>) map);
					if (weight != null)
					{
						raw.put(entry.getValue(), weight);
						final double[] levels = new double[LEVELS.length];
						if (map.get("probabilities") instanceof Map<?, ?> given)
						{
							for (int i = 0; i < LEVELS.length; i++)
							{
								levels[i] = given.get(LEVELS[i]) instanceof Number probability ? probability.doubleValue() : 0;
							}
						}
						probabilities.put(entry.getValue(), levels);
					}
				}
			}
		}

		final StringBuilder line = ConquestConfig.JEV_LOG ? new StringBuilder(16384) : null;
		if (line != null)
		{
			// The request as sent (the key is in a header, not in it), and the reply as it came: as JSON when it was, else as text.
			line.append("{\"time\":").append(turn.time()).append(",\"latency_ms\":").append(_latency).append(",\"why\":").append(SimpleJson.quote(_askedWhy));
			line.append(",\"request\":").append(turn.body()).append(",\"status\":").append(status).append(",\"reply\":").append(answers != null ? reply : SimpleJson.quote(reply));
			line.append(",\"raw\":");
			appendWeights(line, raw);
		}

		if (raw.isEmpty())
		{
			if (line != null)
			{
				log(match, line.append('}'));
			}
			fallback(match, team, problem != null ? problem : "JEV answered no question", now);
			return;
		}
		succeeded();

		_weights.putAll(raw);

		// How likely JEV finds each enemy group the hunters' best target.
		final double[] hunt = new double[turn.hunted().size()];
		if ((answers != null) && (answers.get(HUNT) instanceof Map<?, ?> answer))
		{
			if (answer.get("probabilities") instanceof Map<?, ?> given)
			{
				for (int i = 0; i < hunt.length; i++)
				{
					if (given.get("group_" + (i + 1)) instanceof Number probability)
					{
						hunt[i] = probability.doubleValue();
					}
				}
			}
			else if (answer.get("choice") instanceof String choice)
			{
				for (int i = 0; i < hunt.length; i++)
				{
					hunt[i] = choice.equals("group_" + (i + 1)) ? 1 : 0;
				}
			}
		}
		share(match, team, now, line, turn, probabilities, hunt);
	}

	/**
	 * @return an answer's weight from its probabilities (or its choice alone), {@code null} when it has neither
	 */
	private static Double weight(Map<String, Object> answer)
	{
		if (answer.get("probabilities") instanceof Map<?, ?> probabilities)
		{
			double weight = 0;
			double total = 0;
			for (int i = 0; i < LEVELS.length; i++)
			{
				if (probabilities.get(LEVELS[i]) instanceof Number probability)
				{
					weight += probability.doubleValue() * LEVEL_WEIGHTS[i];
					total += probability.doubleValue();
				}
			}
			if (total > 0)
			{
				return weight / total;
			}
		}
		final Object choice = answer.get("choice");
		for (int i = 0; i < LEVELS.length; i++)
		{
			if (LEVELS[i].equals(choice))
			{
				return LEVEL_WEIGHTS[i];
			}
		}
		return null;
	}

	/**
	 * Shares the living parties out by the weights and gives them their orders.
	 * @param line the log line so far, {@code null} for no log
	 * @param turn the turn answered (its enemy groups offered to the hunters)
	 * @param probabilities JEV's probabilities for each option's levels, for the live page
	 * @param hunt how likely JEV finds each enemy group the best target
	 */
	private void share(ConquestMatch match, ConquestTeam team, long now, StringBuilder line, Turn turn, Map<String, double[]> probabilities, double[] hunt)
	{
		final List<Hunted> hunted = turn.hunted();
		// The group JEV likes best, for choosing who joins the hunt.
		int likeliest = -1;
		for (int i = 0; i < hunt.length; i++)
		{
			if ((hunt[i] > 0) && ((likeliest < 0) || (hunt[i] > hunt[likeliest])))
			{
				likeliest = i;
			}
		}

		final List<ConquestParty> parties = living(team);
		final Map<String, ConquestCapture> points = new LinkedHashMap<>();
		for (ConquestCapture capture : match.getCaptures())
		{
			points.put("point:" + capture.getName(), capture);
		}
		final List<String> keys = new ArrayList<>(points.keySet());
		keys.add(FIGHT);

		// Where the parties are now.
		final Map<String, List<ConquestParty>> on = new HashMap<>();
		for (String key : keys)
		{
			on.put(key, new ArrayList<>());
		}
		final List<ConquestParty> free = new ArrayList<>();
		for (ConquestParty party : parties)
		{
			final String key = option(party.getOrder());
			if ((key != null) && on.containsKey(key))
			{
				on.get(key).add(party);
			}
			else
			{
				free.add(party);
			}
		}

		// Too little weight gets no parties, nor does the hunt when no enemy is out. A point we don't hold yet that has parties on the way keeps them down to a lower weight, so a long walk to take it isn't given up half way.
		final List<int[]> groups = GenericConquestCommander.enemyGroups(match.getOther(team));
		final Map<String, Double> weights = new LinkedHashMap<>();
		double total = 0;
		for (String key : keys)
		{
			final double weight = _weights.getOrDefault(key, 0.0);
			final ConquestCapture capture = points.get(key);
			final boolean taking = (capture != null) && (capture.getOwner() != team.getTeam()) && !on.get(key).isEmpty();
			final double least = ConquestConfig.JEV_MIN_CONFIDENCE * (taking ? FINISH : 1);
			weights.put(key, (weight >= least) && (!key.equals(FIGHT) || !groups.isEmpty()) ? weight : 0);
			total += weights.get(key);
		}
		if (parties.isEmpty() || (total <= 0))
		{
			if (line != null)
			{
				log(match, line.append('}'));
			}
			if (!parties.isEmpty())
			{
				fallback(match, team, "JEV wants our parties nowhere", now);
			}
			return;
		}

		// Each option's share of the parties: its whole parties, then the odd places to the biggest remainders, those that have a party there over the rest.
		final Map<String, Integer> targets = new HashMap<>();
		final Map<String, Double> remainders = new HashMap<>();
		int left = parties.size();
		for (String key : keys)
		{
			final double quota = (parties.size() * weights.get(key)) / total;
			final int whole = (int) quota;
			targets.put(key, whole);
			remainders.put(key, weights.get(key) <= 0 ? -1 : (quota - whole) + (on.get(key).size() > whole ? STICK : 0));
			left -= whole;
		}
		final List<String> byRemainder = new ArrayList<>(keys);
		byRemainder.sort((a, b) -> Double.compare(remainders.get(b), remainders.get(a)));
		for (int i = 0; i < left; i++)
		{
			targets.merge(byRemainder.get(i % byRemainder.size()), 1, Integer::sum);
		}

		// The parties keep their option while its share holds them, the nearest to the point first; the rest go where the share is short, the nearest first.
		final Map<String, List<ConquestParty>> assigned = new LinkedHashMap<>();
		for (String key : keys)
		{
			final List<ConquestParty> there = on.get(key);
			final ConquestCapture capture = points.get(key);
			if (capture != null)
			{
				final Map<ConquestParty, Double> walks = new HashMap<>();
				for (ConquestParty party : there)
				{
					walks.put(party, match.getNav().walkDistance(party.getLocation(), capture.getLocation()));
				}
				there.sort((a, b) -> Double.compare(walks.get(a), walks.get(b)));
			}
			final int keep = Math.min(there.size(), targets.get(key));
			assigned.put(key, new ArrayList<>(there.subList(0, keep)));
			free.addAll(there.subList(keep, there.size()));
		}
		while (!free.isEmpty())
		{
			String neediest = null;
			int need = 0;
			for (String key : keys)
			{
				final int missing = targets.get(key) - assigned.get(key).size();
				if ((missing > need) || ((missing == need) && (missing > 0) && (weights.get(key) > weights.get(neediest))))
				{
					neediest = key;
					need = missing;
				}
			}
			if (neediest == null)
			{
				break; // Can't happen: the shares add up to the parties.
			}
			// A point takes the party with the shortest walk to it; the hunt the one with the shortest walk to the point nearest the group JEV likes best.
			final ConquestCapture capture = ((likeliest >= 0) && neediest.equals(FIGHT)) ? hunted.get(likeliest).point() : points.get(neediest);
			final ConquestParty party = capture != null ? GenericConquestCommander.closest(match, free, capture.getLocation()) : closestToEnemy(free, groups);
			assigned.get(neediest).add(party);
			free.remove(party);
		}

		// The orders. The hunters go for the groups JEV chose, shared out by its probabilities; without its choice, for the biggest enemy group for the way, or to the point JEV wants most when no enemy is out.
		final Map<ConquestParty, Integer> hunters = hunters(match, assigned.get(FIGHT), hunted, hunt);
		String top = null;
		for (String key : points.keySet())
		{
			if ((top == null) || (weights.get(key) > weights.get(top)))
			{
				top = key;
			}
		}
		final StringBuilder moves = new StringBuilder();
		int moved = 0;
		int changed = 0;
		for (Map.Entry<String, List<ConquestParty>> entry : assigned.entrySet())
		{
			for (ConquestParty party : entry.getValue())
			{
				final ConquestParty.Order before = party.getOrder();
				final Plan plan;
				final ConquestCapture capture = points.get(entry.getKey());
				if (capture != null)
				{
					plan = pointPlan(capture, team);
				}
				else if (hunters.containsKey(party))
				{
					plan = Plan.attack(hunted.get(hunters.get(party)).location());
				}
				else
				{
					final int[] group = GenericConquestCommander.bestGroup(groups, party.getLocation());
					plan = group != null ? Plan.attack(new Location(group[0], group[1], group[2])) : pointPlan(points.get(top), team);
				}
				GenericConquestCommander.apply(match, party, plan, now);
				final ConquestParty.Order after = party.getOrder();
				if (after != before)
				{
					changed++;
				}
				if (!entry.getKey().equals(option(before)))
				{
					moved++;
					if (line != null)
					{
						moves.append(moves.length() > 0 ? "," : "").append("{\"party\":").append(party.getId()).append(",\"from\":").append(SimpleJson.quote(describe(before)));
						moves.append(",\"to\":").append(SimpleJson.quote(describe(after))).append('}');
					}
				}
			}
		}
		_moves += moved;

		final StringBuilder shares = new StringBuilder();
		for (String key : keys)
		{
			shares.append(shares.length() > 0 ? ", " : "").append(key.equals(FIGHT) ? "hunt" : points.get(key).getName()).append(' ').append(targets.get(key));
		}
		_shares = shares.toString();

		// For the live page: the answer as it was used. The groups offered to the hunters: JEV's probability for each and how many hunters went for it.
		final int[] hunting = new int[hunted.size()];
		for (int group : hunters.values())
		{
			hunting[group]++;
		}
		final List<ReportOption> options = new ArrayList<>();
		for (String key : keys)
		{
			options.add(new ReportOption(key.equals(FIGHT) ? "hunt" : points.get(key).getName(), probabilities.getOrDefault(key, new double[LEVELS.length]), weights.get(key), on.get(key).size(), targets.get(key)));
		}
		final List<ReportGroup> offered = new ArrayList<>();
		for (int i = 0; i < hunted.size(); i++)
		{
			offered.add(new ReportGroup(hunted.get(i).group()[3], hunted.get(i).point().getName(), hunt[i], hunting[i]));
		}
		_report = new Report(now, now - turn.sent(), _askedWhy, options, offered, moved);

		if (line != null)
		{
			final Map<String, Double> before = new LinkedHashMap<>();
			final Map<String, Double> after = new LinkedHashMap<>();
			for (String key : keys)
			{
				before.put(key, (double) on.get(key).size());
				after.put(key, (double) targets.get(key));
			}
			line.append(",\"weights\":");
			appendWeights(line, weights);
			line.append(",\"parties\":").append(parties.size()).append(",\"before\":");
			appendCounts(line, before);
			line.append(",\"after\":");
			appendCounts(line, after);
			line.append(",\"hunt\":[");
			for (int i = 0; i < hunted.size(); i++)
			{
				line.append(i > 0 ? "," : "").append("{\"group\":").append(i + 1).append(",\"fighters\":").append(hunted.get(i).group()[3]).append(",\"point\":").append(SimpleJson.quote(hunted.get(i).point().getName()));
				line.append(",\"p\":").append(String.format(Locale.ROOT, "%.3f", hunt[i])).append(",\"hunters\":").append(hunting[i]).append('}');
			}
			line.append(']');
			line.append(",\"moved\":").append(moved).append(",\"changed\":").append(changed).append(",\"moves\":[").append(moves).append("]}");
			log(match, line);
		}
	}

	/**
	 * Shares the hunters out over the enemy groups by how likely JEV finds each the best target. A hunter already after a group keeps it while its share holds it; the rest go where the share is short, the one with the shortest walk to the group's nearest
	 * point first.
	 * @return the group (its index in hunted) of each hunter, empty without JEV's choice
	 */
	private static Map<ConquestParty, Integer> hunters(ConquestMatch match, List<ConquestParty> parties, List<Hunted> hunted, double[] hunt)
	{
		final Map<ConquestParty, Integer> hunters = new HashMap<>();
		double total = 0;
		for (double probability : hunt)
		{
			total += probability;
		}
		if (parties.isEmpty() || (total <= 0))
		{
			return hunters;
		}

		final int[] shares = new int[hunt.length];
		final double[] remainders = new double[hunt.length];
		int left = parties.size();
		for (int i = 0; i < hunt.length; i++)
		{
			final double quota = (parties.size() * hunt[i]) / total;
			shares[i] = (int) quota;
			remainders[i] = hunt[i] <= 0 ? -1 : quota - shares[i];
			left -= shares[i];
		}
		for (; left > 0; left--)
		{
			int best = 0;
			for (int i = 1; i < hunt.length; i++)
			{
				if (remainders[i] > remainders[best])
				{
					best = i;
				}
			}
			shares[best]++;
			remainders[best] = -1;
		}

		// Who is after which group already: its attack order within a group's cell of it.
		final int[] taken = new int[hunt.length];
		final List<ConquestParty> free = new ArrayList<>();
		for (ConquestParty party : parties)
		{
			final ConquestParty.Order order = party.getOrder();
			int after = -1;
			if ((order != null) && (order.kind() == ConquestParty.OrderKind.ATTACK))
			{
				double nearest = GenericConquestCommander.GROUP_CELL;
				for (int i = 0; i < hunted.size(); i++)
				{
					final double distance = Math.hypot(hunted.get(i).group()[0] - order.target().getX(), hunted.get(i).group()[1] - order.target().getY());
					if (distance < nearest)
					{
						after = i;
						nearest = distance;
					}
				}
			}
			if ((after >= 0) && (taken[after] < shares[after]))
			{
				hunters.put(party, after);
				taken[after]++;
			}
			else
			{
				free.add(party);
			}
		}
		while (!free.isEmpty())
		{
			int neediest = -1;
			for (int i = 0; i < hunt.length; i++)
			{
				if ((shares[i] > taken[i]) && ((neediest < 0) || ((shares[i] - taken[i]) > (shares[neediest] - taken[neediest]))))
				{
					neediest = i;
				}
			}
			if (neediest < 0)
			{
				break; // Can't happen: the shares add up to the hunters.
			}
			final ConquestParty party = GenericConquestCommander.closest(match, free, hunted.get(neediest).point().getLocation());
			hunters.put(party, neediest);
			taken[neediest]++;
			free.remove(party);
		}
		return hunters;
	}

	/**
	 * @return the biggest enemy groups (at most {@link #HUNT_OPTIONS}), each with the point nearest it by the way it would walk
	 */
	private static List<Hunted> hunted(ConquestMatch match, ConquestTeam team, List<ConquestCapture> captures)
	{
		final List<int[]> groups = new ArrayList<>(GenericConquestCommander.enemyGroups(match.getOther(team)));
		groups.sort((a, b) -> Integer.compare(b[3], a[3]));
		final List<Hunted> hunted = new ArrayList<>();
		for (int[] group : groups.subList(0, Math.min(HUNT_OPTIONS, groups.size())))
		{
			final Location at = new Location(group[0], group[1], group[2]);
			ConquestCapture nearest = null;
			double walk = Double.MAX_VALUE;
			for (ConquestCapture capture : captures)
			{
				// The walk from the group's spot down the point's distance field.
				final double distance = match.getNav().walkDistance(at, capture.getLocation());
				if (distance < walk)
				{
					nearest = capture;
					walk = distance;
				}
			}
			if (nearest != null)
			{
				hunted.add(new Hunted(group, nearest, Math.round(walk)));
			}
		}
		return hunted;
	}

	private static Plan pointPlan(ConquestCapture capture, ConquestTeam team)
	{
		return new Plan(capture.getOwner() == team.getTeam() ? ConquestParty.OrderKind.DEFEND : ConquestParty.OrderKind.CAPTURE, capture, null);
	}

	/**
	 * @return the party nearest an enemy group (the first, when no enemy is out)
	 */
	private static ConquestParty closestToEnemy(List<ConquestParty> parties, List<int[]> groups)
	{
		ConquestParty best = parties.get(0);
		double bestDistance = Double.MAX_VALUE;
		for (ConquestParty party : parties)
		{
			final Location at = party.getLocation();
			for (int[] group : groups)
			{
				final double distance = Math.hypot(group[0] - at.getX(), group[1] - at.getY());
				if (distance < bestDistance)
				{
					best = party;
					bestDistance = distance;
				}
			}
		}
		return best;
	}

	private static List<ConquestParty> living(ConquestTeam team)
	{
		final List<ConquestParty> parties = new ArrayList<>();
		for (ConquestParty party : team.getParties())
		{
			if (party.getLocation() != null)
			{
				parties.add(party);
			}
		}
		return parties;
	}

	/**
	 * @return the option an order is on: "point:" and the point's name, {@link #FIGHT}, or {@code null} for none
	 */
	private static String option(ConquestParty.Order order)
	{
		if (order == null)
		{
			return null;
		}
		if (order.kind() == ConquestParty.OrderKind.ATTACK)
		{
			return FIGHT;
		}
		return order.point() != null ? "point:" + order.point() : null;
	}

	private static String option(Plan plan)
	{
		return plan.capture() != null ? "point:" + plan.capture().getName() : FIGHT;
	}

	private static void appendWeights(StringBuilder json, Map<String, Double> weights)
	{
		json.append('{');
		boolean first = true;
		for (Map.Entry<String, Double> entry : weights.entrySet())
		{
			json.append(first ? "" : ",").append(SimpleJson.quote(entry.getKey())).append(':').append(String.format(Locale.ROOT, "%.3f", entry.getValue()));
			first = false;
		}
		json.append('}');
	}

	private static void appendCounts(StringBuilder json, Map<String, Double> counts)
	{
		json.append('{');
		boolean first = true;
		for (Map.Entry<String, Double> entry : counts.entrySet())
		{
			json.append(first ? "" : ",").append(SimpleJson.quote(entry.getKey())).append(':').append(entry.getValue().intValue());
			first = false;
		}
		json.append('}');
	}

	/**
	 * Adds a line (a JSON object) to the log of its questions and answers: log/conquest/match-&lt;id&gt;-&lt;team&gt;-jev.jsonl, one line a turn.
	 */
	private void log(ConquestMatch match, CharSequence line)
	{
		if (_logFailed)
		{
			return;
		}
		try
		{
			if (_log == null)
			{
				final ConquestTeam team = match.getBlue().getCommander() == this ? match.getBlue() : match.getRed();
				_log = Path.of("log", "conquest", "match-" + match.getId() + "-" + team.getName().toLowerCase(Locale.ROOT) + "-jev.jsonl");
				Files.createDirectories(_log.getParent());
				LOGGER.info(getClass().getSimpleName() + ": " + team.getName() + "'s questions and answers go to " + _log.toAbsolutePath() + ".");
			}
			Files.writeString(_log, line + "\n", StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
		}
		catch (Exception e)
		{
			_logFailed = true;
			LOGGER.warning(getClass().getSimpleName() + ": Could not write " + _log + ", no more JEV log for this match: " + e);
		}
	}

	/**
	 * JEV can't lead now: the plain commander does, at its own pace (as often as it does a team of its own).
	 */
	private void fallback(ConquestMatch match, ConquestTeam team, String why, long now)
	{
		note(why);
		if ((now - _fallbackAt) < (ConquestConfig.COMMANDER_INTERVAL * 1000L))
		{
			return;
		}
		_fallbackAt = now;
		_fallbacks++;
		if (_plainAt != now)
		{
			_plain = _generic.plan(match, team);
			_plainAt = now;
		}
		for (Map.Entry<ConquestParty, Plan> entry : _plain.entrySet())
		{
			GenericConquestCommander.apply(match, entry.getKey(), entry.getValue(), now);
		}
		if (ConquestConfig.JEV_LOG)
		{
			log(match, "{\"time\":" + match.getTime() + ",\"plain\":" + SimpleJson.quote(why) + "}");
		}
	}

	private void note(String problem)
	{
		if (!problem.equals(_problem))
		{
			LOGGER.warning(getClass().getSimpleName() + ": " + problem + ", the plain commander leads.");
			_problem = problem;
		}
	}

	// ---------------------------------------------------------------------------------------------
	// What JEV is told
	// ---------------------------------------------------------------------------------------------

	/**
	 * @return the field as JSON: the scores, the points, and where our parties are and would go by the plain plan
	 */
	private static String state(ConquestMatch match, ConquestTeam team, ConquestTeam enemy, List<ConquestCapture> captures, List<ConquestParty> parties, Map<String, Integer> plain, Map<String, long[]> spawnWalks, List<Hunted> hunted, String why, long now)
	{
		final StringBuilder json = new StringBuilder(2048);
		json.append("{\"rules\":").append(SimpleJson.quote(RULES));
		json.append(",\"what_just_happened\":").append(SimpleJson.quote(why));
		json.append(",\"our_team\":").append(SimpleJson.quote(team.getName()));
		json.append(",\"seconds_left\":").append(Math.max(0, (match._fightEnd - now) / 1000));
		json.append(",\"our_score\":").append(team.getScore()).append(",\"their_score\":").append(enemy.getScore());
		json.append(",\"our_points\":").append(team.getHeld()).append(",\"their_points\":").append(enemy.getHeld());
		json.append(",\"score_now\":").append(SimpleJson.quote(drain(team.getHeld(), enemy.getHeld())));
		json.append(",\"our_fighters_alive\":").append(team.getAlive()).append(",\"their_fighters_alive\":").append(enemy.getAlive());
		int hunting = 0;
		for (ConquestParty party : parties)
		{
			if (FIGHT.equals(option(party.getOrder())))
			{
				hunting++;
			}
		}
		json.append(",\"our_parties_out\":").append(parties.size()).append(",\"our_parties_hunting\":").append(hunting).append(",\"plain_plan_hunting\":").append(plain.getOrDefault(FIGHT, 0));
		json.append(",\"enemy_groups_out\":").append(GenericConquestCommander.enemyGroups(enemy).size());
		json.append(",\"capture_points\":[");
		for (int i = 0; i < captures.size(); i++)
		{
			final ConquestCapture capture = captures.get(i);
			final Location at = capture.getLocation();
			final int range = capture.getRadius() + GenericConquestCommander.THREAT_RANGE;
			double nearest = Double.MAX_VALUE;
			for (ConquestParty party : parties)
			{
				nearest = Math.min(nearest, match.getNav().walkDistance(party.getLocation(), at));
			}
			json.append(i > 0 ? "," : "").append("{\"name\":").append(SimpleJson.quote(capture.getName()));
			json.append(",\"held_by\":").append(SimpleJson.quote(holder(capture, team)));
			// What the point does to the score: taken by us (from them or from nobody), or taken by them (from us or from nobody).
			final Team owner = capture.getOwner();
			final int ours = team.getHeld() - (owner == team.getTeam() ? 1 : 0);
			final int theirs = enemy.getHeld() - (owner == enemy.getTeam() ? 1 : 0);
			json.append(",\"score_if_we_take_it\":").append(SimpleJson.quote(owner == team.getTeam() ? "ours already" : drain(ours + 1, theirs)));
			json.append(",\"score_if_they_take_it\":").append(SimpleJson.quote(owner == enemy.getTeam() ? "theirs already" : drain(ours, theirs + 1)));
			json.append(",\"progress_toward_us\":").append((int) Math.round(team.getTeam() == Team.BLUE ? capture.getProgress() : -capture.getProgress()));
			json.append(",\"our_fighters_on_it\":").append(team.getTeam() == Team.BLUE ? capture.getBlue() : capture.getRed());
			json.append(",\"their_fighters_on_it\":").append(team.getTeam() == Team.BLUE ? capture.getRed() : capture.getBlue());
			json.append(",\"our_fighters_near\":").append(match.getNear(team, at.getX(), at.getY(), range).size());
			json.append(",\"their_fighters_near\":").append(match.getNear(enemy, at.getX(), at.getY(), range).size());
			json.append(",\"our_parties_sent\":").append(sentTo(parties, capture));
			json.append(",\"plain_plan_parties\":").append(plain.getOrDefault("point:" + capture.getName(), 0));
			json.append(",\"walk_of_our_nearest_party\":").append(nearest == Double.MAX_VALUE ? -1 : Math.round(nearest));
			final long[] walks = spawnWalks != null ? spawnWalks.get(capture.getName()) : null;
			if (walks != null)
			{
				json.append(",\"walk_from_our_spawn\":").append(walks[0]).append(",\"walk_from_their_spawn\":").append(walks[1]);
			}
			json.append('}');
		}
		json.append("],\"enemy_groups\":[");
		for (int i = 0; i < hunted.size(); i++)
		{
			final Hunted group = hunted.get(i);
			json.append(i > 0 ? "," : "").append("{\"group\":").append(SimpleJson.quote("group_" + (i + 1))).append(",\"fighters\":").append(group.group()[3]);
			json.append(",\"nearest_point\":").append(SimpleJson.quote(group.point().getName())).append(",\"walk_to_nearest_point\":").append(group.walk());
			json.append(",\"our_fighters_near\":").append(match.getNear(team, group.group()[0], group.group()[1], GenericConquestCommander.GROUP_CELL).size()).append('}');
		}
		json.append("]}");
		return json.toString();
	}

	/**
	 * @return for each point, by its name: the walk to it from our nearest spawn and from theirs (the dead come back there), from the nav grid's distance field of the point
	 */
	private static Map<String, long[]> spawnWalks(ConquestMatch match, ConquestTeam team, List<ConquestCapture> captures)
	{
		final Map<String, long[]> walks = new HashMap<>();
		final ConquestTeam enemy = match.getOther(team);
		for (ConquestCapture capture : captures)
		{
			walks.put(capture.getName(), new long[]
			{
				spawnWalk(match, team, capture),
				spawnWalk(match, enemy, capture)
			});
		}
		return walks;
	}

	private static long spawnWalk(ConquestMatch match, ConquestTeam team, ConquestCapture capture)
	{
		double best = Double.MAX_VALUE;
		for (ConquestPoint spawn : team.getSpawns())
		{
			best = Math.min(best, match.getNav().walkDistance(new Location(spawn.x(), spawn.y(), spawn.z()), capture.getLocation()));
		}
		return best == Double.MAX_VALUE ? -1 : Math.round(best);
	}

	/**
	 * @return what each team loses a second when we hold that many points and they hold that many, as the event counts it
	 */
	private static String drain(int ours, int theirs)
	{
		final int we = ours == theirs ? 1 : Math.max(0, theirs - ours);
		final int they = ours == theirs ? 1 : Math.max(0, ours - theirs);
		return ours + " v " + theirs + " points: we lose " + we + " a second, they lose " + they;
	}

	private static String holder(ConquestCapture capture, ConquestTeam team)
	{
		return capture.getOwner() == team.getTeam() ? "us" : capture.getOwner() == Team.NONE ? "nobody" : "them";
	}

	private static int sentTo(List<ConquestParty> parties, ConquestCapture capture)
	{
		int sent = 0;
		for (ConquestParty party : parties)
		{
			final ConquestParty.Order order = party.getOrder();
			if ((order != null) && capture.getName().equals(order.point()))
			{
				sent++;
			}
		}
		return sent;
	}

	private static String describe(ConquestParty.Order order)
	{
		if (order == null)
		{
			return "none";
		}
		return switch (order.kind())
		{
			case CAPTURE -> "take point " + order.point();
			case DEFEND -> "hold point " + order.point();
			case ATTACK -> "go for an enemy group";
			case HOLD -> "stay";
		};
	}

	// ---------------------------------------------------------------------------------------------
	// The account's limit
	// ---------------------------------------------------------------------------------------------

	/**
	 * Takes a request of the second's share.
	 * @return {@code false} while the share is used up, or while waiting after a 429 or 529
	 */
	private static boolean reserve(long now)
	{
		synchronized (SENT)
		{
			if (now < _backoffUntil)
			{
				return false;
			}
			while (!SENT.isEmpty() && (SENT.peekFirst() <= (now - 1000)))
			{
				SENT.pollFirst();
			}
			if (SENT.size() >= ConquestConfig.JEV_MAX_REQUESTS_PER_SECOND)
			{
				return false;
			}
			SENT.addLast(now);
			return true;
		}
	}

	private static void throttled(long now)
	{
		synchronized (SENT)
		{
			_backoff = _backoff == 0 ? BACKOFF_FIRST : Math.min(BACKOFF_MOST, _backoff * 2);
			_backoffUntil = now + _backoff;
		}
	}

	private static void succeeded()
	{
		synchronized (SENT)
		{
			_backoff = 0;
		}
	}
}
