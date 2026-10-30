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
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.l2jmobius.gameserver.ai.FakeBrain;

/**
 * An A/B test of two fake player AIs ({@link FakeBrain}) in party fights, many battles at once: every battle has an instance of its own at the same field, so they don't see or hit each other (see {@link FakeSimulationManager#startArena}).
 * <ul>
 * <li>Every composition (the same builds, gear and personalities in both teams) is fought twice, the brains on swapped sides, so only the brain differs.</li>
 * <li>The battles go composition by composition through the levels, so an arena stopped early still covers every level about evenly.</li>
 * <li>Every battle is a line of results.csv; summary.txt (wins by brain with their 95% interval, by level, by side) is written again every {@link #WRITE_EVERY} battles and at the end.</li>
 * </ul>
 */
public class SimulationArena
{
	private static final Logger LOGGER = Logger.getLogger(SimulationArena.class.getName());

	public static final String RESULTS = "results.csv";
	public static final String SUMMARY = "summary.txt";
	private static final String RESULTS_HEADER = "battle,level,composition,side,brain1,brain2,winner,winner_brain,reason,duration_ms,decided_ms,hp1,hp2,first_death_brain,first_death_role";
	/** The summary is written again every this many battles. */
	private static final int WRITE_EVERY = 20;

	/**
	 * One battle to fight.
	 * @param number its number in this arena (from 1, the order it started in)
	 * @param level the level of the fake players
	 * @param composition which composition of the level (from 0): both battles of a composition field the same teams
	 * @param side 0 for the first brain on point 1, 1 for the brains swapped
	 * @param seed the seed of the composition: builds, gear, names, personalities
	 */
	public record Battle(int number, int level, int composition, int side, long seed)
	{
	}

	private final String _name;
	private final Path _dir;
	private final List<Integer> _levels;
	private final int _roundsPerLevel;
	private final int _size;
	private final FakeBrain[] _brains;
	private final int _parallel;
	private final int _timeLimit;
	private final int _distance;
	private final long _seed;
	private final int _x;
	private final int _y;
	private final int _z;
	private final int _heading;
	private final int _starterId;

	private final Deque<Battle> _queue = new ArrayDeque<>();
	private final AtomicInteger _numbers = new AtomicInteger();
	private final AtomicInteger _running = new AtomicInteger();
	private final long _startTime = System.currentTimeMillis();
	private final int _total;
	private int _done;
	private volatile boolean _stopped;
	private volatile boolean _finished;
	/** Finished battle folders, oldest first: only the newest are kept. */
	private final Deque<Path> _keptLogs = new ArrayDeque<>();

	// Results: brain -> {wins, games, first deaths, healer first deaths}; level -> brain -> {wins, games}; point -> wins; draws; decided-at times.
	private final Map<String, int[]> _brainStats = new TreeMap<>();
	private final Map<Integer, Map<String, int[]>> _levelStats = new TreeMap<>();
	private final int[] _sideWins = new int[3];
	private int _draws;
	private final Map<String, Integer> _reasons = new TreeMap<>();
	private long _decidedSum;
	private int _decidedCount;

	/**
	 * @param name its folder name
	 * @param dir its folder
	 * @param levels the levels to fight at
	 * @param roundsPerLevel battles per level (an odd number is rounded up: every composition is played from both sides)
	 * @param size the party size of both teams
	 * @param brains the two brains
	 * @param parallel battles at once
	 * @param timeLimit the time limit of a battle, in seconds
	 * @param distance how far apart the two teams spawn
	 * @param seed the seed
	 * @param x the middle of the field
	 * @param y the middle of the field
	 * @param z the middle of the field
	 * @param heading the teams spawn along this heading
	 * @param starterId the admin that started it (0 for none)
	 */
	public SimulationArena(String name, Path dir, List<Integer> levels, int roundsPerLevel, int size, FakeBrain[] brains, int parallel, int timeLimit, int distance, long seed, int x, int y, int z, int heading, int starterId)
	{
		_name = name;
		_dir = dir;
		_levels = levels;
		_roundsPerLevel = roundsPerLevel + (roundsPerLevel % 2);
		_size = size;
		_brains = brains;
		_parallel = parallel;
		_timeLimit = timeLimit;
		_distance = distance;
		_seed = seed;
		_x = x;
		_y = y;
		_z = z;
		_heading = heading;
		_starterId = starterId;

		final int compositions = _roundsPerLevel / 2;
		for (int composition = 0; composition < compositions; composition++)
		{
			for (int level : levels)
			{
				final long compositionSeed = SimulationSettings.mix(seed + (level * 100003L) + composition);
				_queue.add(new Battle(0, level, composition, 0, compositionSeed));
				_queue.add(new Battle(0, level, composition, 1, compositionSeed));
			}
		}
		_total = _queue.size();

		try
		{
			Files.createDirectories(dir);
			Files.writeString(dir.resolve(RESULTS), RESULTS_HEADER + System.lineSeparator(), StandardCharsets.UTF_8);
			Files.writeString(dir.resolve("arena.properties"), "levels=" + levels + "\nroundsPerLevel=" + _roundsPerLevel + "\nsize=" + size + "\nbrains=" + brains[0] + ":" + brains[1] + "\nparallel=" + parallel + "\ntimeLimit=" + timeLimit + "\ndistance=" + distance + "\nseed=" + seed + "\nplace=" + x + "," + y + "," + z + "," + heading + "\n", StandardCharsets.UTF_8);
		}
		catch (IOException e)
		{
			LOGGER.log(Level.WARNING, "SimulationArena: Could not create " + dir + ".", e);
		}
	}

	/**
	 * @param battle a battle
	 * @return the brains of team 1 (point 1) and team 2: the first brain on point 1, swapped on side 1
	 */
	public FakeBrain[] brainsOf(Battle battle)
	{
		return battle.side() == 0 ? new FakeBrain[]
		{
			_brains[0],
			_brains[1]
		} : new FakeBrain[]
		{
			_brains[1],
			_brains[0]
		};
	}

	/**
	 * @return the next battle to start (counted as running), {@code null} if there is none, every slot is taken or the arena stopped
	 */
	public synchronized Battle next()
	{
		if (_stopped || _queue.isEmpty() || (_running.get() >= _parallel))
		{
			return null;
		}
		final Battle battle = _queue.poll();
		_running.incrementAndGet();
		return new Battle(_numbers.incrementAndGet(), battle.level(), battle.composition(), battle.side(), battle.seed());
	}

	/**
	 * A battle ended: its line goes to results.csv.
	 * @param battle the battle
	 * @param winnerTeam the winning team (1 or 2), 0 for a draw
	 * @param reason victory, timeout, rout, draw or stopped
	 * @param duration how long the fight lasted, in milliseconds
	 * @param decided when it was decided (a team down to its last member), in milliseconds into the fight, 0 if never
	 * @param hp1 HP and CP left of team 1, a ratio
	 * @param hp2 HP and CP left of team 2, a ratio
	 * @param firstDeathTeam the team of the first death, 0 for none
	 * @param firstDeathRole the role of the first death, {@code null} for none
	 */
	public void record(Battle battle, int winnerTeam, String reason, long duration, long decided, double hp1, double hp2, int firstDeathTeam, String firstDeathRole)
	{
		// A battle cut by a stop doesn't count.
		if (reason.equals("stopped"))
		{
			return;
		}

		final FakeBrain[] brains = brainsOf(battle);
		final String winnerBrain = winnerTeam > 0 ? brains[winnerTeam - 1].name() : "";
		final String deathBrain = firstDeathTeam > 0 ? brains[firstDeathTeam - 1].name() : "";
		final String line = battle.number() + "," + battle.level() + "," + battle.composition() + "," + battle.side() + "," + brains[0] + "," + brains[1] + "," + winnerTeam + "," + winnerBrain + "," + reason + "," + duration + "," + decided + "," + String.format(Locale.ROOT, "%.3f", hp1) + "," + String.format(Locale.ROOT, "%.3f", hp2) + "," + deathBrain + "," + (firstDeathRole != null ? firstDeathRole : "");
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
				LOGGER.log(Level.WARNING, "SimulationArena: Could not write " + _dir.resolve(RESULTS) + ".", e);
			}

			final Map<String, int[]> level = _levelStats.computeIfAbsent(battle.level(), _ -> new TreeMap<>());
			for (int team = 1; team <= 2; team++)
			{
				final String brain = brains[team - 1].name();
				final int[] stats = _brainStats.computeIfAbsent(brain, _ -> new int[4]);
				final int[] perLevel = level.computeIfAbsent(brain, _ -> new int[2]);
				stats[1]++;
				perLevel[1]++;
				if (team == winnerTeam)
				{
					stats[0]++;
					perLevel[0]++;
				}
				if (team == firstDeathTeam)
				{
					stats[2]++;
					if ("HEALER".equals(firstDeathRole))
					{
						stats[3]++;
					}
				}
			}
			_sideWins[winnerTeam]++;
			if (winnerTeam == 0)
			{
				_draws++;
			}
			_reasons.merge(reason, 1, Integer::sum);
			if (decided > 0)
			{
				_decidedSum += decided;
				_decidedCount++;
			}
			_done++;
			write = (_done % WRITE_EVERY) == 0;
		}
		if (write)
		{
			writeSummary();
		}
	}

	/**
	 * A battle was cleaned up, or could not start (it is not played again): its slot is free.
	 * @param logDir its log folder, removed once newer battles push it out ({@code null} for none)
	 * @param keep how many finished battle folders to keep
	 * @return {@code true} if it was the last one and the arena is over (stopped, or no battle left)
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
	 * Writes summary.txt: wins by brain with their 95% interval, by level, by side, how the battles ended.
	 * @return its lines
	 */
	public synchronized List<String> writeSummary()
	{
		final List<String> lines = new ArrayList<>();
		lines.add("Arena " + _name + ": " + _done + "/" + _total + " battles, " + _size + " vs " + _size + ", levels " + _levels + ", brains " + _brains[0] + " vs " + _brains[1] + (_stopped ? " (stopped)" : ""));
		for (Map.Entry<String, int[]> entry : _brainStats.entrySet())
		{
			final int[] s = entry.getValue();
			final double[] interval = SimulationSummary.wilson(s[0], s[1]);
			lines.add(String.format(Locale.ROOT, "  brain %s: %d/%d wins (%.0f%%, 95%% %.0f-%.0f%%), first death %d, healer died first %d", entry.getKey(), s[0], s[1], s[1] > 0 ? (100.0 * s[0]) / s[1] : 0, 100 * interval[0], 100 * interval[1], s[2], s[3]));
		}
		for (Map.Entry<Integer, Map<String, int[]>> level : _levelStats.entrySet())
		{
			final StringBuilder sb = new StringBuilder("  level " + level.getKey() + ":");
			for (Map.Entry<String, int[]> brain : level.getValue().entrySet())
			{
				final int[] s = brain.getValue();
				final double[] interval = SimulationSummary.wilson(s[0], s[1]);
				sb.append(String.format(Locale.ROOT, " %s %d/%d (%.0f-%.0f%%)", brain.getKey(), s[0], s[1], 100 * interval[0], 100 * interval[1]));
			}
			lines.add(sb.toString());
		}
		lines.add("  wins by side: point 1 " + _sideWins[1] + ", point 2 " + _sideWins[2] + ", draws " + _draws + " | ended: " + _reasons + (_decidedCount > 0 ? " | decided after " + Math.round(_decidedSum / 1000.0 / _decidedCount) + " s on average" : ""));

		try
		{
			Files.writeString(_dir.resolve(SUMMARY), String.join(System.lineSeparator(), lines) + System.lineSeparator(), StandardCharsets.UTF_8);
		}
		catch (IOException e)
		{
			LOGGER.log(Level.WARNING, "SimulationArena: Could not write " + _dir.resolve(SUMMARY) + ".", e);
		}
		return lines;
	}

	/**
	 * @return the lines of a status: progress, then the summary so far
	 */
	public List<String> getStatus()
	{
		final List<String> lines = new ArrayList<>();
		final long minutes = (System.currentTimeMillis() - _startTime) / 60000;
		lines.add("Arena " + _name + ": " + getDone() + "/" + _total + " battles done, " + getRunning() + " running, " + minutes + " min so far" + (_stopped ? ", stopping" : "") + ". " + _dir.toAbsolutePath());
		final List<String> summary = writeSummary();
		lines.addAll(summary.subList(1, summary.size()));
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

	public synchronized int getDone()
	{
		return _done;
	}

	public int getRunning()
	{
		return _running.get();
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

	public int getSize()
	{
		return _size;
	}

	public int getParallel()
	{
		return _parallel;
	}

	public int getTimeLimit()
	{
		return _timeLimit;
	}

	public int getDistance()
	{
		return _distance;
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
