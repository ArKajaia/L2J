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
import java.util.Comparator;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Stream;

/**
 * The log files of one simulation round, in JSON lines (one object per line): events, AI decisions, positions and anomalies, plus whole files (run.json, summary). Lines are queued and written by one background thread, so the AI and combat threads never touch the
 * disk.
 */
public class SimulationLog
{
	private static final Logger LOGGER = Logger.getLogger(SimulationLog.class.getName());

	public static final String EVENTS = "events.jsonl";
	public static final String DECISIONS = "decisions.jsonl";
	public static final String POSITIONS = "positions.jsonl";
	public static final String ANOMALIES = "anomalies.jsonl";

	private final Path _dir;
	private final ExecutorService _writer;
	private final Map<String, BufferedWriter> _files = new HashMap<>();
	private volatile boolean _closed;

	/**
	 * @param dir the folder of the round, created if missing
	 */
	public SimulationLog(Path dir)
	{
		_dir = dir;
		_writer = Executors.newSingleThreadExecutor(runnable ->
		{
			final Thread thread = new Thread(runnable, "SimulationLog");
			thread.setDaemon(true);
			return thread;
		});
		try
		{
			Files.createDirectories(dir);
		}
		catch (IOException e)
		{
			LOGGER.log(Level.WARNING, "SimulationLog: Could not create " + dir + ".", e);
		}
	}

	/**
	 * @return the folder of the round
	 */
	public Path getDir()
	{
		return _dir;
	}

	/**
	 * Queues a line for one of the JSON lines files.
	 * @param file the file ({@link #EVENTS}, {@link #DECISIONS}...)
	 * @param line the line
	 */
	public void write(String file, JsonLine line)
	{
		if (_closed)
		{
			return;
		}

		final String text = line.toString();
		submit(() ->
		{
			try
			{
				BufferedWriter writer = _files.get(file);
				if (writer == null)
				{
					writer = Files.newBufferedWriter(_dir.resolve(file), StandardCharsets.UTF_8);
					_files.put(file, writer);
				}
				writer.write(text);
				writer.newLine();
			}
			catch (IOException e)
			{
				LOGGER.log(Level.WARNING, "SimulationLog: Could not write " + file + ".", e);
			}
		});
	}

	/**
	 * Queues a whole file (run.json, summary.txt...), replacing it.
	 * @param file the file name
	 * @param content its content
	 */
	public void writeFile(String file, String content)
	{
		writeFile(_dir.resolve(file), content);
	}

	/**
	 * Queues a whole file anywhere (the summary of several rounds goes to the folder above).
	 * @param path the file
	 * @param content its content
	 */
	public void writeFile(Path path, String content)
	{
		if (_closed)
		{
			return;
		}

		submit(() ->
		{
			try
			{
				Files.createDirectories(path.getParent());
				Files.writeString(path, content, StandardCharsets.UTF_8);
			}
			catch (IOException e)
			{
				LOGGER.log(Level.WARNING, "SimulationLog: Could not write " + path + ".", e);
			}
		});
	}

	private void submit(Runnable task)
	{
		try
		{
			_writer.execute(task);
		}
		catch (RejectedExecutionException e)
		{
			// Closed meanwhile: the line is dropped.
		}
	}
	
	/**
	 * Writes what is queued, closes the files and stops the writer thread.
	 */
	public void close()
	{
		if (_closed)
		{
			return;
		}

		_writer.execute(() ->
		{
			for (BufferedWriter writer : _files.values())
			{
				try
				{
					writer.close();
				}
				catch (IOException e)
				{
					LOGGER.log(Level.WARNING, "SimulationLog: Could not close a file of " + _dir + ".", e);
				}
			}
			_files.clear();
		});
		_closed = true;
		_writer.shutdown();
		try
		{
			_writer.awaitTermination(10, TimeUnit.SECONDS);
		}
		catch (InterruptedException e)
		{
			Thread.currentThread().interrupt();
		}
	}

	/**
	 * Deletes the oldest run folders of {@code root} beyond {@code keep}.
	 * @param root the folder of all runs
	 * @param keep how many to keep
	 */
	public static void prune(Path root, int keep)
	{
		if (!Files.isDirectory(root))
		{
			return;
		}

		try (Stream<Path> runs = Files.list(root))
		{
			runs.filter(Files::isDirectory).sorted(Comparator.comparing(Path::getFileName).reversed()).skip(keep).forEach(SimulationLog::deleteTree);
		}
		catch (IOException e)
		{
			LOGGER.log(Level.WARNING, "SimulationLog: Could not prune " + root + ".", e);
		}
	}

	private static void deleteTree(Path dir)
	{
		try (Stream<Path> paths = Files.walk(dir))
		{
			paths.sorted(Comparator.reverseOrder()).forEach(path ->
			{
				try
				{
					Files.delete(path);
				}
				catch (IOException e)
				{
					LOGGER.log(Level.WARNING, "SimulationLog: Could not delete " + path + ".", e);
				}
			});
		}
		catch (IOException e)
		{
			LOGGER.log(Level.WARNING, "SimulationLog: Could not delete " + dir + ".", e);
		}
	}
}
