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
package org.l2jmobius.gameserver.model.hiddenquest;

import java.util.concurrent.ScheduledFuture;

import org.l2jmobius.gameserver.model.World;
import org.l2jmobius.gameserver.model.actor.Player;
import org.l2jmobius.gameserver.model.hiddenquest.task.AbstractHiddenTask;

/**
 * A hidden quest task in progress for one player.
 * @author Mobius
 */
public class HiddenQuestSession
{
	private final int _playerObjectId;
	private final String _playerName;
	private final HiddenQuestDefinition _definition;
	private final long _startTime;
	private final long _deadline;
	private AbstractHiddenTask _task;
	private ScheduledFuture<?> _tickTask;
	private volatile boolean _finished;
	private int _elapsed;

	public HiddenQuestSession(Player player, HiddenQuestDefinition definition, int timeLimit)
	{
		_playerObjectId = player.getObjectId();
		_playerName = player.getName();
		_definition = definition;
		_startTime = System.currentTimeMillis();
		_deadline = _startTime + (timeLimit * 1000L);
	}

	public int getPlayerObjectId()
	{
		return _playerObjectId;
	}

	public String getPlayerName()
	{
		return _playerName;
	}

	/**
	 * @return the player if online, else {@code null}
	 */
	public Player getPlayer()
	{
		final Player player = World.getInstance().getPlayer(_playerObjectId);
		return (player != null) && player.isOnline() ? player : null;
	}

	public HiddenQuestDefinition getDefinition()
	{
		return _definition;
	}

	public AbstractHiddenTask getTask()
	{
		return _task;
	}

	public void setTask(AbstractHiddenTask task)
	{
		_task = task;
	}

	public long getStartTime()
	{
		return _startTime;
	}

	public long getDeadline()
	{
		return _deadline;
	}

	/**
	 * @return the seconds left before the time limit
	 */
	public int getSecondsLeft()
	{
		return (int) Math.max(0, (_deadline - System.currentTimeMillis()) / 1000);
	}

	public ScheduledFuture<?> getTickTask()
	{
		return _tickTask;
	}

	public void setTickTask(ScheduledFuture<?> tickTask)
	{
		_tickTask = tickTask;
	}

	/**
	 * @return the number of ticks (seconds) since the task started
	 */
	public int nextTick()
	{
		return ++_elapsed;
	}

	public boolean isFinished()
	{
		return _finished;
	}

	/**
	 * Marks the session finished.
	 * @return {@code true} if it was still running (so only one caller completes or fails it)
	 */
	public synchronized boolean finish()
	{
		if (_finished)
		{
			return false;
		}
		_finished = true;
		return true;
	}
}
