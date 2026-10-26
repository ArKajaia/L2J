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
package org.l2jmobius.gameserver.managers.simulation;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * The summary of a simulation round (summary.json and summary.txt in its folder) and of all the rounds of a simulation (series.json and series.txt in the folder above).
 */
public class SimulationSummary
{
	private static final Logger LOGGER = Logger.getLogger(SimulationSummary.class.getName());
	private static final int WORST_ANOMALIES = 15;

	private SimulationSummary()
	{
	}

	/**
	 * Writes the summary of a round that ended.
	 * @param run the round
	 * @param rounds how many rounds the simulation has
	 * @return the text summary (shown to the admins)
	 */
	public static String write(SimulationRun run, int rounds)
	{
		final SimulationSettings settings = run.getSettings();
		final long duration = run.getFightStart() > 0 ? run.getEnd() - run.getFightStart() : 0;
		final List<JsonLine> worst = getWorst(run.getMonitor().getFound());

		final JsonLine json = new JsonLine();
		json.put("round", run.getRound());
		json.put("rounds", rounds);
		json.put("seed", settings.seed());
		json.put("compositionSeed", settings.compositionSeed(run.getRound()));
		json.put("winner", run.getWinner());
		json.put("reason", run.getEndReason());
		json.put("fightDuration", duration);
		json.put("decidedAt", run.getDecidedAt() > 0 ? run.getDecidedAt() - run.getFightStart() : null);
		final List<JsonLine> teams = new ArrayList<>();
		for (SimulationTeam team : run.getTeams())
		{
			final List<JsonLine> members = new ArrayList<>();
			for (SimulationMember member : team.getMembers())
			{
				final Map<String, Long> decisions = new LinkedHashMap<>();
				for (Map.Entry<Reason, Long> entry : FakeSimulationManager.sortedReasons(member))
				{
					decisions.put(entry.getKey().name(), entry.getValue());
				}
				members.add(new JsonLine().put("id", member.getObjectId()).put("name", member.getName()).put("role", member.getRole()).put("build", member.getBuild()).put("admin", member.isAdmin()).put("active", team.isActive(member)).put("alive", member.isAlive()).put("damageDealt", (long) member.getDamageDealt()).put("damageTaken", (long) member.getDamageTaken()).put("healDone", (long) member.getHealDone()).put("healReceived", (long) member.getHealReceived()).put("kills", member.getKills()).put("deaths", member.getDeaths()).put("diedAt", member.getDiedAt() >= 0 ? member.getDiedAt() : null).put("aliveTime", member.getAliveTime()).put("distance", (long) member.getDistance()).put("resisted", member.getResisted()).put("pathFailures", member.getPathFailures()).put("decisionTime", decisions).put("anomalies", member.getAnomalies()));
			}
			// When its first fake player made its first decision (ms since the start): late thinking shows here (issue #24).
			long firstDecision = 0;
			for (SimulationMember member : team.getMembers())
			{
				final long at = member.getFirstDecisionAt();
				if ((at > 0) && ((firstDecision == 0) || (at < firstDecision)))
				{
					firstDecision = at;
				}
			}
			teams.add(new JsonLine().put("id", team.getId()).put("brain", team.getBrain().name()).put("alive", team.isAlive()).ratio("healthLeft", team.getHealthLeft()).put("firstDecision", firstDecision > 0 ? firstDecision - run.getStartTime() : null).put("members", members));
		}
		json.put("teams", teams);
		json.put("kills", run.getKills());
		json.put("anomalyCounts", run.getMonitor().getCounts());
		json.put("worstAnomalies", worst);
		run.getLog().writeFile("summary.json", json.toString());

		final StringBuilder sb = new StringBuilder();
		sb.append("Round ").append(run.getRound()).append('/').append(rounds).append(", seed ").append(settings.seed()).append(", ").append(run.getTeams().size()).append(" teams of ").append(settings.size()).append(", level ").append(settings.level()).append(settings.mirror() ? ", mirror" : "").append('\n');
		sb.append("Result: ").append(run.getWinner() > 0 ? "team " + run.getWinner() + " wins" : "no winner").append(" (").append(run.getEndReason()).append(") after ").append(String.format("%.1f", duration / 1000.0)).append(" s of fighting.\n\n");
		for (SimulationTeam team : run.getTeams())
		{
			sb.append("Team ").append(team.getId()).append(", brain ").append(team.getBrain()).append(team.getId() == run.getWinner() ? " (winner)" : "").append(", ").append(Math.round(team.getHealthLeft() * 100)).append("% HP+CP left\n");
			for (SimulationMember member : team.getMembers())
			{
				sb.append("  ").append(member.getName()).append(" - ").append(member.getRole().name().toLowerCase()).append(", ").append(member.getBuild()).append(member.isAdmin() ? ", admin" : "").append(team.isActive(member) ? "" : ", replaced/left").append('\n');
				sb.append("    dealt ").append((long) member.getDamageDealt()).append(", took ").append((long) member.getDamageTaken()).append(", healed ").append((long) member.getHealDone()).append(", K/D ").append(member.getKills()).append('/').append(member.getDeaths()).append(member.getDiedAt() >= 0 ? ", died at " + String.format("%.1f", member.getDiedAt() / 1000.0) + " s" : "").append(", ran ").append((long) member.getDistance()).append('\n');
				final List<Map.Entry<Reason, Long>> reasons = FakeSimulationManager.sortedReasons(member);
				if (!reasons.isEmpty())
				{
					sb.append("    time on: ");
					for (int i = 0; i < Math.min(4, reasons.size()); i++)
					{
						sb.append(i > 0 ? ", " : "").append(reasons.get(i).getKey()).append(' ').append(String.format("%.1f", reasons.get(i).getValue() / 1000.0)).append(" s");
					}
					sb.append('\n');
				}
				if (!member.getAnomalies().isEmpty())
				{
					sb.append("    anomalies: ").append(member.getAnomalies()).append('\n');
				}
			}
		}

		sb.append("\nKills:\n");
		for (String kill : run.getKillTexts())
		{
			sb.append("  ").append(kill).append('\n');
		}

		final Map<String, Integer> counts = run.getMonitor().getCounts();
		sb.append("\nAnomalies, worst first: ");
		boolean first = true;
		for (String type : SimulationMonitor.SEVERITY)
		{
			final Integer count = counts.get(type);
			if (count != null)
			{
				sb.append(first ? "" : ", ").append(type).append(' ').append(count);
				first = false;
			}
		}
		sb.append(first ? "none" : "").append('\n');
		for (String anomaly : run.getMonitor().getWorstTexts(WORST_ANOMALIES))
		{
			sb.append("  ").append(anomaly).append('\n');
		}
		sb.append("\nLog: ").append(run.getLog().getDir().toAbsolutePath()).append('\n');

		final String text = sb.toString();
		run.getLog().writeFile("summary.txt", text);
		return text;
	}

	/**
	 * @return the first anomalies of the worst kinds (without their members' decisions, which are in anomalies.jsonl)
	 */
	private static List<JsonLine> getWorst(List<JsonLine> found)
	{
		final List<JsonLine> worst = new ArrayList<>();
		for (String type : SimulationMonitor.SEVERITY)
		{
			for (JsonLine line : found)
			{
				if (worst.size() >= WORST_ANOMALIES)
				{
					return worst;
				}
				final String text = line.toString();
				if (text.contains("\"type\":\"" + type + "\""))
				{
					final int members = text.indexOf(",\"members\":");
					worst.add(new JsonLine().put("anomaly", members > 0 ? text.substring(0, members) + "}" : text));
				}
			}
		}
		return worst;
	}

	/**
	 * Writes the summary of all the rounds of a simulation: wins by team (the point it fought from), brain, build and role, and anomalies.
	 * @return the text summary
	 */
	public static String writeSeries(Path dir, String name, SimulationSettings settings, List<JsonLine> results, Map<Integer, Integer> teamWins, Map<String, int[]> brainStats, Map<String, int[]> buildStats, Map<String, int[]> roleStats, Map<String, Integer> anomalies, List<Long> decided)
	{
		// Each brain's win rate, with its 95% interval (Wilson): a change to brain B wins more only if its interval is above 50%.
		final Map<String, JsonLine> brains = new LinkedHashMap<>();
		final StringBuilder brainText = new StringBuilder();
		for (Map.Entry<String, int[]> entry : brainStats.entrySet())
		{
			final int wins = entry.getValue()[0];
			final int games = entry.getValue()[1];
			final double[] interval = wilson(wins, games);
			brains.put(entry.getKey(), new JsonLine().put("wins", wins).put("games", games).ratio("rate", games > 0 ? (double) wins / games : 0).ratio("low", interval[0]).ratio("high", interval[1]));
			brainText.append(brainText.length() > 0 ? ", " : "").append(entry.getKey()).append(' ').append(wins).append('/').append(games).append(String.format(" (%.0f%%, 95%% %.0f-%.0f%%)", games > 0 ? (100.0 * wins) / games : 0, 100 * interval[0], 100 * interval[1]));
		}

		final Map<String, String> builds = new LinkedHashMap<>();
		for (Map.Entry<String, int[]> entry : buildStats.entrySet())
		{
			builds.put(entry.getKey(), entry.getValue()[0] + "/" + entry.getValue()[1]);
		}
		final Map<String, String> roles = new LinkedHashMap<>();
		for (Map.Entry<String, int[]> entry : roleStats.entrySet())
		{
			roles.put(entry.getKey(), entry.getValue()[0] + "/" + entry.getValue()[1]);
		}

		final JsonLine json = new JsonLine().put("name", name).put("size", settings.size()).put("level", settings.level()).put("mirror", settings.mirror()).put("seed", settings.seed()).put("brains", settings.brains()).put("swap", settings.swap()).put("rounds", results.size()).put("results", results).put("teamWins", teamWins).put("brainWins", brains).put("buildWinsPerGames", builds).put("roleWinsPerGames", roles).put("anomalies", anomalies);

		final StringBuilder sb = new StringBuilder();
		sb.append(name).append(": ").append(results.size()).append(" round(s) of ").append(settings.size()).append(" per team, level ").append(settings.level()).append(settings.mirror() ? ", mirror" : "").append(", seed ").append(settings.seed()).append(", brains ").append(settings.brains()).append(settings.swap() ? ", A/B test" : "").append('\n');
		sb.append("Wins by team (side): ").append(teamWins).append('\n');
		sb.append("Wins by brain: ").append(brainText.length() > 0 ? brainText : "none").append('\n');
		if (!decided.isEmpty())
		{
			long sum = 0;
			for (long time : decided)
			{
				sum += time;
			}
			sb.append("Decided (a team down to its last member) after ").append(Math.round(sum / 1000.0 / decided.size())).append(" s on average\n");
		}
		sb.append("Wins/games by role: ").append(roles).append('\n');
		sb.append("Wins/games by build: ").append(builds).append('\n');
		sb.append("Anomalies: ").append(anomalies).append('\n');
		for (JsonLine result : results)
		{
			sb.append("  ").append(result).append('\n');
		}
		final String text = sb.toString();

		try
		{
			Files.createDirectories(dir);
			Files.writeString(dir.resolve("series.json"), json.toString(), StandardCharsets.UTF_8);
			Files.writeString(dir.resolve("series.txt"), text, StandardCharsets.UTF_8);
		}
		catch (Exception e)
		{
			LOGGER.log(Level.WARNING, "SimulationSummary: Could not write the summary of " + name + ".", e);
		}
		return text;
	}

	/**
	 * @param wins how many games were won
	 * @param games how many games were played
	 * @return the 95% Wilson interval of the win rate: {low, high}
	 */
	public static double[] wilson(int wins, int games)
	{
		if (games <= 0)
		{
			return new double[]
			{
				0,
				1
			};
		}

		final double z = 1.96;
		final double p = (double) wins / games;
		final double denominator = 1 + ((z * z) / games);
		final double center = (p + ((z * z) / (2 * games))) / denominator;
		final double margin = (z * Math.sqrt(((p * (1 - p)) / games) + ((z * z) / (4.0 * games * games)))) / denominator;
		return new double[]
		{
			Math.max(0, center - margin),
			Math.min(1, center + margin)
		};
	}
}
