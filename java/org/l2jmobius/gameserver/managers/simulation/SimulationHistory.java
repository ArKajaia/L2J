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

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.l2jmobius.commons.util.SimpleJson;
import org.l2jmobius.gameserver.ai.FakeBrain;
import org.l2jmobius.gameserver.config.custom.FakeSimulationConfig;

/**
 * The simulations that ran, as {@link SimulationLog} left them under SimulationLogDir: one folder per //Simulate (&lt;yyyyMMdd-HHmmss&gt;-&lt;seed&gt;) with a round-NN folder per round. Read by the web page and by {@link FakeSimulationManager#rerun}. Names from outside go through
 * {@link #resolveSeries} and {@link #resolveRound}, so nothing outside the log folder can be reached.
 */
public final class SimulationHistory
{
	private static final Pattern SERIES_NAME = Pattern.compile("^\\d{8}-\\d{6}-\\d{1,19}$");
	public static final String RUN_FILE = "run.json";
	public static final String SUMMARY_FILE = "summary.json";
	public static final String SERIES_FILE = "series.json";

	private SimulationHistory()
	{
	}

	/**
	 * @return the folder of all simulations
	 */
	public static Path getRoot()
	{
		return Path.of(FakeSimulationConfig.LOG_DIR).toAbsolutePath().normalize();
	}

	/**
	 * @return the simulation folders, newest first
	 */
	public static List<Path> listSeries()
	{
		final List<Path> result = new ArrayList<>();
		final Path root = getRoot();
		if (!Files.isDirectory(root))
		{
			return result;
		}

		try (Stream<Path> dirs = Files.list(root))
		{
			dirs.filter(Files::isDirectory).filter(dir -> SERIES_NAME.matcher(dir.getFileName().toString()).matches()).sorted(Comparator.comparing(Path::getFileName).reversed()).forEach(result::add);
		}
		catch (IOException e)
		{
			// Nothing to list.
		}
		return result;
	}

	/**
	 * @param name a simulation name, as the web page sends it
	 * @return its folder, {@code null} if the name is not one or it is gone
	 */
	public static Path resolveSeries(String name)
	{
		if ((name == null) || !SERIES_NAME.matcher(name).matches())
		{
			return null;
		}

		final Path root = getRoot();
		final Path dir = root.resolve(name).normalize();
		return dir.startsWith(root) && Files.isDirectory(dir) ? dir : null;
	}

	/**
	 * @param seriesDir a simulation folder
	 * @param round the round (1-100)
	 * @return the folder of that round, {@code null} if there is none
	 */
	public static Path resolveRound(Path seriesDir, int round)
	{
		if ((seriesDir == null) || (round < 1) || (round > 100))
		{
			return null;
		}

		final Path dir = seriesDir.resolve(String.format("round-%02d", round));
		return Files.isDirectory(dir) ? dir : null;
	}

	/**
	 * @param seriesDir a simulation folder
	 * @return the round numbers it has folders for, in order
	 */
	public static List<Integer> listRounds(Path seriesDir)
	{
		final List<Integer> rounds = new ArrayList<>();
		for (int round = 1; round <= 100; round++)
		{
			if (resolveRound(seriesDir, round) == null)
			{
				break;
			}
			rounds.add(round);
		}
		return rounds;
	}

	/**
	 * @param file a file
	 * @return its text, {@code null} if it can't be read
	 */
	public static String readText(Path file)
	{
		try
		{
			return Files.isRegularFile(file) ? Files.readString(file, StandardCharsets.UTF_8) : null;
		}
		catch (IOException e)
		{
			return null;
		}
	}

	/**
	 * @param seriesDir a simulation folder
	 * @return the run.json of its first round, parsed, {@code null} if missing or broken
	 */
	public static Map<?, ?> readRunFile(Path seriesDir)
	{
		final Path round = resolveRound(seriesDir, 1);
		final String text = round != null ? readText(round.resolve(RUN_FILE)) : null;
		if (text == null)
		{
			return null;
		}

		try
		{
			return SimpleJson.parse(text) instanceof Map<?, ?> map ? map : null;
		}
		catch (IllegalArgumentException e)
		{
			return null;
		}
	}

	/**
	 * The settings a simulation ran with. The seed comes from the folder name, exact where the JSON number might not be.
	 * @param seriesDir a simulation folder
	 * @return its settings (starter 0), {@code null} if its run.json can't be read
	 */
	public static SimulationSettings readSettings(Path seriesDir)
	{
		final Map<?, ?> run = readRunFile(seriesDir);
		if (run == null)
		{
			return null;
		}

		final String name = seriesDir.getFileName().toString();
		final long seed = Long.parseLong(name.substring(name.lastIndexOf('-') + 1));
		// Older runs had no brains: they all ran A.
		final String brains = run.get("brains") instanceof String text ? SimulationSettings.normalizeBrains(text) : null;
		return new SimulationSettings(getInt(run, "size", 1), getInt(run, "level", 1), Boolean.TRUE.equals(run.get("mirror")), seed, getInt(run, "rounds", 1), getInt(run, "timeLimit", FakeSimulationConfig.TIME_LIMIT), 0, brains != null ? brains : FakeBrain.A.name(), Boolean.TRUE.equals(run.get("swap")));
	}

	/**
	 * @param seriesDir a simulation folder
	 * @return the spawn points it used, empty if its run.json can't be read
	 */
	public static List<SimulationPoint> readPoints(Path seriesDir)
	{
		final List<SimulationPoint> points = new ArrayList<>();
		final Map<?, ?> run = readRunFile(seriesDir);
		if ((run == null) || !(run.get("points") instanceof List<?> list))
		{
			return points;
		}

		for (Object item : list)
		{
			if (item instanceof Map<?, ?> point)
			{
				points.add(new SimulationPoint(getInt(point, "id", 0), getInt(point, "x", 0), getInt(point, "y", 0), getInt(point, "z", 0), getInt(point, "heading", 0), getInt(point, "instance", 0)));
			}
		}
		return points;
	}

	private static int getInt(Map<?, ?> map, String key, int fallback)
	{
		return map.get(key) instanceof Number number ? number.intValue() : fallback;
	}
}
