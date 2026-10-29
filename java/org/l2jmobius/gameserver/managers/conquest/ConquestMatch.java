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

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ScheduledFuture;

import org.l2jmobius.gameserver.config.custom.ConquestConfig;
import org.l2jmobius.gameserver.model.WorldRegion;
import org.l2jmobius.gameserver.model.actor.enums.creature.Team;
import org.l2jmobius.gameserver.model.conquest.ConquestMap;
import org.l2jmobius.gameserver.model.conquest.ConquestPoint;
import org.l2jmobius.gameserver.model.conquest.ConquestPointType;

/**
 * A running Conquest match: its map, its copy of the map's place (an instance), both teams and the capture points.
 */
public class ConquestMatch
{
	/** Where a match is at. */
	public enum Phase
	{
		/** The fake players are spawned and get ready at their spawn points. */
		PREP,
		/** The fight. */
		FIGHT,
		/** The result is shown, everyone goes shortly. */
		ENDED
	}

	/**
	 * A line of the match's log.
	 * @param time seconds into the fight (negative before it)
	 * @param text what happened
	 */
	public record LogEntry(int time, String text)
	{
	}

	/** The cells in which the fighters are sorted each tick, to find the enemies near one quickly. */
	static final int GRID_CELL = 1200;
	private static final int MAX_LOG = 100;

	private final int _id;
	private final ConquestMap _map;
	private final int _instanceId;
	private final int _perTeam;
	private final int _minLevel;
	private final int _maxLevel;
	private final ConquestTeam _blue;
	private final ConquestTeam _red;
	private final List<ConquestCapture> _captures = new ArrayList<>();
	private final ConquestNavGrid _nav;
	private final Deque<LogEntry> _log = new ArrayDeque<>();
	final List<WorldRegion> _keptRegions = new ArrayList<>();
	volatile Phase _phase = Phase.PREP;
	volatile long _prepEnd;
	volatile long _fightStart;
	volatile long _fightEnd;
	volatile Team _winner;
	volatile String _result;
	volatile int _returned;
	volatile int _unstuck;
	volatile int _ticks;
	volatile long _lastTickNanos;
	volatile long _lastCommandNanos;
	ScheduledFuture<?> _task;
	ScheduledFuture<?> _spawnTask;
	// The living fighters of each team by grid cell, rebuilt each tick.
	private volatile Map<Long, List<ConquestFighter>> _blueGrid = Collections.emptyMap();
	private volatile Map<Long, List<ConquestFighter>> _redGrid = Collections.emptyMap();

	ConquestMatch(int id, ConquestMap map, int instanceId, int perTeam, int minLevel, int maxLevel, ConquestCommander blueCommander, ConquestCommander redCommander)
	{
		_id = id;
		_map = map;
		_instanceId = instanceId;
		_perTeam = perTeam;
		_minLevel = minLevel;
		_maxLevel = maxLevel;
		_blue = new ConquestTeam(Team.BLUE, "Blue", ConquestConfig.BLUE_NAME_COLOR, map.getPoints(ConquestPointType.BLUE), blueCommander);
		_red = new ConquestTeam(Team.RED, "Red", ConquestConfig.RED_NAME_COLOR, map.getPoints(ConquestPointType.RED), redCommander);
		for (ConquestPoint point : map.getPoints(ConquestPointType.CAPTURE))
		{
			_captures.add(new ConquestCapture(point));
		}
		_nav = new ConquestNavGrid(map);
	}

	public int getId()
	{
		return _id;
	}

	public ConquestMap getMap()
	{
		return _map;
	}

	public int getInstanceId()
	{
		return _instanceId;
	}

	public int getPerTeam()
	{
		return _perTeam;
	}

	public int getMinLevel()
	{
		return _minLevel;
	}

	public int getMaxLevel()
	{
		return _maxLevel;
	}

	public Phase getPhase()
	{
		return _phase;
	}

	public ConquestTeam getBlue()
	{
		return _blue;
	}

	public ConquestTeam getRed()
	{
		return _red;
	}

	/**
	 * @param team a team
	 * @return the other team
	 */
	public ConquestTeam getOther(ConquestTeam team)
	{
		return team == _blue ? _red : _blue;
	}

	public List<ConquestCapture> getCaptures()
	{
		return _captures;
	}

	/**
	 * @return the ways across the map (may still be being worked out in the first seconds)
	 */
	public ConquestNavGrid getNav()
	{
		return _nav;
	}

	/**
	 * @return the winner once it ended, {@code null} before or for a draw
	 */
	public Team getWinner()
	{
		return _winner;
	}

	/**
	 * @return the result once it ended, {@code null} before
	 */
	public String getResult()
	{
		return _result;
	}

	/**
	 * @return how many times a fighter was put back inside the map's area
	 */
	public int getReturned()
	{
		return _returned;
	}

	/**
	 * @return how many times a fighter was found off the ground (inside a wall, in the rock) and put back on it
	 */
	public int getUnstuck()
	{
		return _unstuck;
	}

	/**
	 * @return how long the last tick took, in ms, and the last commanders' turn
	 */
	public double getTickMillis()
	{
		return _lastTickNanos / 1_000_000.0;
	}

	public double getCommandMillis()
	{
		return _lastCommandNanos / 1_000_000.0;
	}

	/**
	 * @return seconds into the fight, negative while getting ready
	 */
	public int getTime()
	{
		final long now = System.currentTimeMillis();
		if (_phase == Phase.PREP)
		{
			return (int) -Math.max(0, (_prepEnd - now) / 1000);
		}
		final long end = _phase == Phase.ENDED ? Math.min(now, _fightEnd) : now;
		return (int) Math.max(0, (end - _fightStart) / 1000);
	}

	/**
	 * @return the longest the fight may last, in seconds
	 */
	public int getTimeLimit()
	{
		return (int) Math.max(0, (_fightEnd - _fightStart) / 1000);
	}

	public List<ConquestFighter> getAllFighters()
	{
		final List<ConquestFighter> all = new ArrayList<>(_blue.getFighters());
		all.addAll(_red.getFighters());
		return all;
	}

	/**
	 * @param x a spot's x
	 * @param y a spot's y
	 * @param margin how far outside still counts
	 * @return {@code true} if the spot lies in the map's area
	 */
	public boolean isInside(int x, int y, int margin)
	{
		return (x >= (_map.minX() - margin)) && (x <= (_map.maxX() + margin)) && (y >= (_map.minY() - margin)) && (y <= (_map.maxY() + margin));
	}

	/**
	 * Notes something that happened, for the GM page.
	 * @param text what happened
	 */
	void log(String text)
	{
		synchronized (_log)
		{
			_log.addLast(new LogEntry(getTime(), text));
			while (_log.size() > MAX_LOG)
			{
				_log.removeFirst();
			}
		}
	}

	/**
	 * @return the log, oldest first
	 */
	public List<LogEntry> getLog()
	{
		synchronized (_log)
		{
			return new ArrayList<>(_log);
		}
	}

	/**
	 * Sorts the living fighters into the grid cells, for {@link #forEachNear}.
	 */
	void rebuildGrid()
	{
		_blueGrid = buildGrid(_blue);
		_redGrid = buildGrid(_red);
	}

	private static Map<Long, List<ConquestFighter>> buildGrid(ConquestTeam team)
	{
		final Map<Long, List<ConquestFighter>> grid = new HashMap<>();
		for (ConquestFighter fighter : team.getFighters())
		{
			if (fighter.isAlive())
			{
				grid.computeIfAbsent(key(fighter.getNpc().getX(), fighter.getNpc().getY()), _ -> new ArrayList<>()).add(fighter);
			}
		}
		return grid;
	}

	/**
	 * @param team a team
	 * @param x a spot's x
	 * @param y a spot's y
	 * @param range how far
	 * @return that team's living fighters near the spot (from the last tick's grid: some may have moved a little, or died since)
	 */
	public List<ConquestFighter> getNear(ConquestTeam team, int x, int y, int range)
	{
		final Map<Long, List<ConquestFighter>> grid = team == _blue ? _blueGrid : _redGrid;
		final List<ConquestFighter> near = new ArrayList<>();
		final int reach = (range / GRID_CELL) + 1;
		final int cx = Math.floorDiv(x, GRID_CELL);
		final int cy = Math.floorDiv(y, GRID_CELL);
		for (int dx = -reach; dx <= reach; dx++)
		{
			for (int dy = -reach; dy <= reach; dy++)
			{
				final List<ConquestFighter> cell = grid.get((((long) (cx + dx)) << 32) | ((cy + dy) & 0xFFFFFFFFL));
				if (cell == null)
				{
					continue;
				}
				for (ConquestFighter fighter : cell)
				{
					if (Math.hypot(fighter.getNpc().getX() - x, fighter.getNpc().getY() - y) <= range)
					{
						near.add(fighter);
					}
				}
			}
		}
		return near;
	}

	private static long key(int x, int y)
	{
		return (((long) Math.floorDiv(x, GRID_CELL)) << 32) | (Math.floorDiv(y, GRID_CELL) & 0xFFFFFFFFL);
	}
}
