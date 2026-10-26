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

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.l2jmobius.gameserver.ai.FakeBrain;
import org.l2jmobius.gameserver.data.xml.FakePlayerPvpData;
import org.l2jmobius.gameserver.model.actor.holders.npc.FakePlayerPvpBuild;

/**
 * A class-vs-class win rate matrix: every pair of builds fights {@link #getBattles()} 1v1 battles, many at once. Every battle has its own instance at the same place, so they don't see or hit each other (see {@link FakeSimulationManager#startMatrix}).
 * <ul>
 * <li>The battles go pair by pair, one battle of every pair before the second of any, so a matrix stopped early still covers every pair evenly.</li>
 * <li>The two builds of a pair change spots every battle.</li>
 * <li>Every battle is a line of results.csv; matrix.csv and matrix.txt (the win rates) are written again every {@link #WRITE_EVERY} battles and at the end.</li>
 * <li>A matrix stopped or cut by a restart goes on from its results.csv ({@link #load}).</li>
 * </ul>
 */
public class SimulationMatrix
{
	private static final Logger LOGGER = Logger.getLogger(SimulationMatrix.class.getName());

	public static final String SETTINGS = "matrix.properties";
	public static final String RESULTS = "results.csv";
	private static final String RESULTS_HEADER = "battle,a,b,round,winner,reason,duration_ms,a_hp,b_hp,a_spot";
	/** The win rates are written again every this many battles. */
	private static final int WRITE_EVERY = 50;

	/**
	 * One battle to fight.
	 * @param number its number in this matrix (from 1, the order it started in)
	 * @param a the first build of the pair
	 * @param b the second build of the pair
	 * @param round which battle of the pair (from 0)
	 * @param seed the seed of both fake players (gear, names, personality)
	 */
	public record Battle(int number, String a, String b, int round, long seed)
	{
		/**
		 * @return {@code true} if {@link #a} takes the first spot (the builds change spots every battle)
		 */
		public boolean aFirst()
		{
			return (round % 2) == 0;
		}
	}

	private final String _name;
	private final Path _dir;
	private final List<String> _builds;
	private final int _battles;
	private final int _parallel;
	private final int _level;
	private final int _timeLimit;
	private final int _distance;
	private final FakeBrain _brain;
	private final long _seed;
	private final int _x;
	private final int _y;
	private final int _z;
	private final int _heading;
	private final int _starterId;

	private final Deque<Battle> _queue = new ArrayDeque<>();
	private final AtomicInteger _numbers = new AtomicInteger();
	private final AtomicInteger _running = new AtomicInteger();
	private final Map<String, int[]> _pairs = new HashMap<>();
	private final long _startTime = System.currentTimeMillis();
	private int _total;
	private int _done;
	private int _doneBefore;
	private volatile boolean _stopped;
	private volatile boolean _finished;
	/** Finished battle folders, oldest first: only the newest are kept. */
	private final Deque<Path> _keptLogs = new ArrayDeque<>();

	private SimulationMatrix(String name, Path dir, List<String> builds, int battles, int parallel, int level, int timeLimit, int distance, FakeBrain brain, long seed, int x, int y, int z, int heading, int starterId)
	{
		_name = name;
		_dir = dir;
		_builds = builds;
		_battles = battles;
		_parallel = parallel;
		_level = level;
		_timeLimit = timeLimit;
		_distance = distance;
		_brain = brain;
		_seed = seed;
		_x = x;
		_y = y;
		_z = z;
		_heading = heading;
		_starterId = starterId;
	}

	/**
	 * A new matrix.
	 * @param name its folder name
	 * @param dir its folder
	 * @param builds the builds, at least 2
	 * @param battles battles per pair
	 * @param parallel battles at once
	 * @param level the level of the fake players
	 * @param timeLimit the time limit of a battle, in seconds
	 * @param distance how far apart the two spawn
	 * @param brain the AI of both
	 * @param seed the seed
	 * @param x the middle of the place
	 * @param y the middle of the place
	 * @param z the middle of the place
	 * @param heading the two spawn along this heading
	 * @param starterId the admin that started it
	 * @return the matrix, its settings saved
	 */
	public static SimulationMatrix create(String name, Path dir, List<String> builds, int battles, int parallel, int level, int timeLimit, int distance, FakeBrain brain, long seed, int x, int y, int z, int heading, int starterId)
	{
		final SimulationMatrix matrix = new SimulationMatrix(name, dir, builds, battles, parallel, level, timeLimit, distance, brain, seed, x, y, z, heading, starterId);
		matrix.saveSettings();
		matrix.fillQueue(Map.of());
		return matrix;
	}

	/**
	 * A matrix that stopped, going on from its results.
	 * @param dir its folder
	 * @param parallel battles at once, 0 for as before
	 * @param starterId the admin that resumes it
	 * @return the matrix, {@code null} if its settings can't be read
	 */
	public static SimulationMatrix load(Path dir, int parallel, int starterId)
	{
		final Properties settings = new Properties();
		try (Reader reader = Files.newBufferedReader(dir.resolve(SETTINGS), StandardCharsets.UTF_8))
		{
			settings.load(reader);
			final List<String> builds = List.of(settings.getProperty("builds").split("\\|"));
			final FakeBrain brain = FakeBrain.parse(settings.getProperty("brain", "A"));
			final SimulationMatrix matrix = new SimulationMatrix(dir.getFileName().toString(), dir, builds, Integer.parseInt(settings.getProperty("battles")), parallel > 0 ? parallel : Integer.parseInt(settings.getProperty("parallel")), Integer.parseInt(settings.getProperty("level")), Integer.parseInt(settings.getProperty("timeLimit")), Integer.parseInt(settings.getProperty("distance")), brain != null ? brain : FakeBrain.A, Long.parseLong(settings.getProperty("seed")), Integer.parseInt(settings.getProperty("x")), Integer.parseInt(settings.getProperty("y")), Integer.parseInt(settings.getProperty("z")), Integer.parseInt(settings.getProperty("heading")), starterId);
			final Map<String, Integer> played = matrix.readResults();
			matrix.fillQueue(played);
			return matrix;
		}
		catch (Exception e)
		{
			LOGGER.log(Level.WARNING, "SimulationMatrix: Could not read " + dir.resolve(SETTINGS) + ".", e);
			return null;
		}
	}

	/**
	 * @param filter build names separated by commas, "all" or {@code null} for every class
	 * @param unknown filled with the names that match no build
	 * @return the builds of the matrix: every build (not the gear variants), in data/FakePlayerPvp.xml order
	 */
	public static List<String> resolveBuilds(String filter, List<String> unknown)
	{
		final List<String> builds = new ArrayList<>();
		if ((filter == null) || filter.isEmpty() || filter.equalsIgnoreCase("all"))
		{
			for (FakePlayerPvpBuild build : FakePlayerPvpData.getInstance().getBuilds())
			{
				if (!build.isVariant())
				{
					builds.add(build.getName());
				}
			}
			return builds;
		}

		for (String name : filter.split(","))
		{
			final FakePlayerPvpBuild build = FakePlayerPvpData.getInstance().getBuild(name.trim().replace('_', ' '));
			if (build == null)
			{
				unknown.add(name.trim());
			}
			else if (!builds.contains(build.getName()))
			{
				builds.add(build.getName());
			}
		}
		return builds;
	}

	private void saveSettings()
	{
		final Properties settings = new Properties();
		settings.setProperty("builds", String.join("|", _builds));
		settings.setProperty("battles", String.valueOf(_battles));
		settings.setProperty("parallel", String.valueOf(_parallel));
		settings.setProperty("level", String.valueOf(_level));
		settings.setProperty("timeLimit", String.valueOf(_timeLimit));
		settings.setProperty("distance", String.valueOf(_distance));
		settings.setProperty("brain", _brain.name());
		settings.setProperty("seed", String.valueOf(_seed));
		settings.setProperty("x", String.valueOf(_x));
		settings.setProperty("y", String.valueOf(_y));
		settings.setProperty("z", String.valueOf(_z));
		settings.setProperty("heading", String.valueOf(_heading));
		try
		{
			Files.createDirectories(_dir);
			try (Writer writer = Files.newBufferedWriter(_dir.resolve(SETTINGS), StandardCharsets.UTF_8))
			{
				settings.store(writer, "Class-vs-class matrix: //SimulationMatrix resume " + _name + " goes on from results.csv");
			}
			Files.writeString(_dir.resolve(RESULTS), RESULTS_HEADER + System.lineSeparator(), StandardCharsets.UTF_8);
		}
		catch (IOException e)
		{
			LOGGER.log(Level.WARNING, "SimulationMatrix: Could not write the settings of " + _dir + ".", e);
		}
	}

	/**
	 * Reads results.csv back into the win counts.
	 * @return battles already played, by pair key
	 */
	private Map<String, Integer> readResults() throws IOException
	{
		final Map<String, Integer> played = new HashMap<>();
		final Path file = _dir.resolve(RESULTS);
		if (!Files.exists(file))
		{
			Files.writeString(file, RESULTS_HEADER + System.lineSeparator(), StandardCharsets.UTF_8);
			return played;
		}

		int highest = 0;
		for (String line : Files.readAllLines(file, StandardCharsets.UTF_8))
		{
			final String[] parts = line.split(",");
			if ((parts.length < 10) || parts[0].equals("battle"))
			{
				continue;
			}
			final String a = parts[1];
			final String b = parts[2];
			played.merge(key(a, b), 1, Integer::sum);
			count(a, b, parts[4]);
			highest = Math.max(highest, Integer.parseInt(parts[0]));
			_doneBefore++;
		}
		_numbers.set(highest);
		return played;
	}

	private void fillQueue(Map<String, Integer> played)
	{
		for (int round = 0; round < _battles; round++)
		{
			for (int i = 0; i < _builds.size(); i++)
			{
				for (int j = i + 1; j < _builds.size(); j++)
				{
					final String a = _builds.get(i);
					final String b = _builds.get(j);
					if (round < played.getOrDefault(key(a, b), 0))
					{
						continue;
					}
					_queue.add(new Battle(0, a, b, round, mix(_seed + (((long) i * 1000) + j) * 100003L + round)));
				}
			}
		}
		_total = _doneBefore + _queue.size();
	}

	private static String key(String a, String b)
	{
		return a + "|" + b;
	}

	/**
	 * @param value a number
	 * @return a well spread number made from it (SplitMix64)
	 */
	private static long mix(long value)
	{
		long z = value + 0x9E3779B97F4A7C15L;
		z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
		z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
		return z ^ (z >>> 31);
	}

	/**
	 * @return the next battle to start (counted as running), {@code null} if there is none, every slot is taken or the matrix stopped
	 */
	public synchronized Battle next()
	{
		if (_stopped || _queue.isEmpty() || (_running.get() >= _parallel))
		{
			return null;
		}
		final Battle battle = _queue.poll();
		_running.incrementAndGet();
		return new Battle(_numbers.incrementAndGet(), battle.a(), battle.b(), battle.round(), battle.seed());
	}

	/**
	 * A battle ended: its line goes to results.csv.
	 * @param battle the battle
	 * @param winner the winning build, {@code null} for a draw
	 * @param reason victory, timeout, rout, draw or stopped
	 * @param duration how long the fight lasted, in milliseconds
	 * @param aHp HP and CP left of {@link Battle#a}, a ratio
	 * @param bHp HP and CP left of {@link Battle#b}, a ratio
	 */
	public void record(Battle battle, String winner, String reason, long duration, double aHp, double bHp)
	{
		// A battle cut by //SimulationMatrix stop is played again on resume.
		if (reason.equals("stopped"))
		{
			return;
		}

		final String result = winner == null ? "draw" : winner.equals(battle.a()) ? "a" : "b";
		final String line = battle.number() + "," + battle.a() + "," + battle.b() + "," + battle.round() + "," + result + "," + reason + "," + duration + "," + String.format(Locale.ROOT, "%.3f", aHp) + "," + String.format(Locale.ROOT, "%.3f", bHp) + "," + (battle.aFirst() ? 1 : 2);
		final boolean write;
		synchronized (this)
		{
			try (BufferedWriter writer = Files.newBufferedWriter(_dir.resolve(RESULTS), StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND))
			{
				writer.write(line);
				writer.newLine();
			}
			catch (IOException e)
			{
				LOGGER.log(Level.WARNING, "SimulationMatrix: Could not write " + _dir.resolve(RESULTS) + ".", e);
			}
			count(battle.a(), battle.b(), result);
			_done++;
			write = (_done % WRITE_EVERY) == 0;
		}
		if (write)
		{
			writeMatrix();
		}
	}

	private synchronized void count(String a, String b, String result)
	{
		// {wins of a, wins of b, draws}
		final int[] counts = _pairs.computeIfAbsent(key(a, b), _ -> new int[3]);
		counts[result.equals("a") ? 0 : result.equals("b") ? 1 : 2]++;
	}

	/**
	 * A battle was cleaned up, or could not start (it is not played again): its slot is free.
	 * @param logDir its log folder, removed once newer battles push it out ({@code null} for none)
	 * @param keep how many finished battle folders to keep
	 * @return {@code true} if it was the last one and the matrix is over (stopped, or no battle left)
	 */
	public boolean onFreed(Path logDir, int keep)
	{
		final List<Path> drop = new ArrayList<>();
		final boolean over;
		synchronized (this)
		{
			if (logDir != null)
			{
				_keptLogs.add(logDir);
			}
			while (_keptLogs.size() > keep)
			{
				drop.add(_keptLogs.poll());
			}
			over = (_running.decrementAndGet() <= 0) && (_stopped || _queue.isEmpty()) && !_finished;
			if (over)
			{
				_finished = true;
			}
		}
		for (Path dir : drop)
		{
			SimulationLog.deleteTree(dir);
		}
		return over;
	}

	/**
	 * @param a a build
	 * @param b another build
	 * @return {wins of a, games} between both, draws counting half a win each
	 */
	private synchronized double[] score(String a, String b)
	{
		final int[] ab = _pairs.get(key(a, b));
		if (ab != null)
		{
			return new double[]
			{
				ab[0] + (ab[2] / 2.0),
				ab[0] + ab[1] + ab[2]
			};
		}
		final int[] ba = _pairs.get(key(b, a));
		if (ba != null)
		{
			return new double[]
			{
				ba[1] + (ba[2] / 2.0),
				ba[0] + ba[1] + ba[2]
			};
		}
		return new double[2];
	}

	/**
	 * Writes matrix.csv (the win rate of each row against each column, in percent, a draw counting half) and matrix.txt (the classes by overall win rate).
	 * @return the lines of matrix.txt
	 */
	public List<String> writeMatrix()
	{
		final StringBuilder csv = new StringBuilder("class");
		for (String build : _builds)
		{
			csv.append(',').append(build);
		}
		csv.append(",overall,games").append(System.lineSeparator());

		final Map<String, double[]> overall = new LinkedHashMap<>();
		for (String a : _builds)
		{
			csv.append(a);
			double wins = 0;
			double games = 0;
			for (String b : _builds)
			{
				if (a.equals(b))
				{
					csv.append(",");
					continue;
				}
				final double[] score = score(a, b);
				wins += score[0];
				games += score[1];
				csv.append(',').append(score[1] > 0 ? String.format(Locale.ROOT, "%.1f", (100 * score[0]) / score[1]) : "");
			}
			overall.put(a, new double[]
			{
				wins,
				games
			});
			csv.append(',').append(games > 0 ? String.format(Locale.ROOT, "%.1f", (100 * wins) / games) : "").append(',').append((int) games).append(System.lineSeparator());
		}

		final List<Map.Entry<String, double[]>> ranked = new ArrayList<>(overall.entrySet());
		ranked.sort((x, y) -> Double.compare(rate(y.getValue()), rate(x.getValue())));
		final List<String> lines = new ArrayList<>();
		lines.add("Matrix " + _name + ": " + getDone() + "/" + _total + " battles, level " + _level + ", " + _builds.size() + " classes, " + _battles + " per pair.");
		int rank = 1;
		for (Map.Entry<String, double[]> entry : ranked)
		{
			lines.add(String.format(Locale.ROOT, "%2d. %-20s %5.1f%% (%d games)", rank++, entry.getKey(), 100 * rate(entry.getValue()), (int) entry.getValue()[1]));
		}

		try
		{
			Files.writeString(_dir.resolve("matrix.csv"), csv.toString(), StandardCharsets.UTF_8);
			Files.writeString(_dir.resolve("matrix.txt"), String.join(System.lineSeparator(), lines) + System.lineSeparator(), StandardCharsets.UTF_8);
		}
		catch (IOException e)
		{
			LOGGER.log(Level.WARNING, "SimulationMatrix: Could not write the matrix of " + _dir + ".", e);
		}
		return lines;
	}

	private static double rate(double[] score)
	{
		return score[1] > 0 ? score[0] / score[1] : 0;
	}

	/**
	 * @return the lines of //SimulationMatrix status
	 */
	public List<String> getStatus()
	{
		final List<String> lines = new ArrayList<>();
		final int done;
		final int left;
		synchronized (this)
		{
			done = _done;
			left = _queue.size();
		}
		final long elapsed = Math.max(1, System.currentTimeMillis() - _startTime);
		final double perMinute = (done * 60000.0) / elapsed;
		final long eta = perMinute > 0 ? (long) ((left + _running.get()) / perMinute) : -1;
		lines.add("Matrix " + _name + (_stopped ? " (stopping)" : "") + ": " + (_doneBefore + done) + "/" + _total + " battles done, " + _running.get() + " running, " + left + " waiting.");
		lines.add(String.format(Locale.ROOT, "%d classes, %d per pair, level %d, %d at once, %.1f battles/min", _builds.size(), _battles, _level, _parallel, perMinute) + (eta >= 0 ? ", about " + (eta / 60) + " h " + (eta % 60) + " min left." : "."));
		lines.add("Results: " + _dir.resolve(RESULTS).toAbsolutePath());
		return lines;
	}

	public void stop()
	{
		_stopped = true;
	}

	public boolean isStopped()
	{
		return _stopped;
	}

	/**
	 * @return the battles running (started and not yet cleaned up)
	 */
	public int getRunning()
	{
		return _running.get();
	}

	public synchronized int getDone()
	{
		return _doneBefore + _done;
	}

	public int getTotal()
	{
		return _total;
	}

	public String getName()
	{
		return _name;
	}

	public Path getDir()
	{
		return _dir;
	}

	public List<String> getBuilds()
	{
		return _builds;
	}

	public int getBattles()
	{
		return _battles;
	}

	public int getParallel()
	{
		return _parallel;
	}

	public int getLevel()
	{
		return _level;
	}

	public int getTimeLimit()
	{
		return _timeLimit;
	}

	public int getDistance()
	{
		return _distance;
	}

	public FakeBrain getBrain()
	{
		return _brain;
	}

	public long getSeed()
	{
		return _seed;
	}

	public int getX()
	{
		return _x;
	}

	public int getY()
	{
		return _y;
	}

	public int getZ()
	{
		return _z;
	}

	public int getHeading()
	{
		return _heading;
	}

	public int getStarterId()
	{
		return _starterId;
	}
}
