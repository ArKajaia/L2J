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
package org.l2jmobius.gameserver.managers;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ScheduledFuture;
import java.util.logging.Logger;

import org.l2jmobius.commons.threads.ThreadPool;
import org.l2jmobius.commons.time.SchedulingPattern;
import org.l2jmobius.commons.time.TimeUtil;
import org.l2jmobius.gameserver.config.custom.EventRotationConfig;
import org.l2jmobius.gameserver.managers.TeamVsTeamManager.Match;
import org.l2jmobius.gameserver.managers.TeamVsTeamManager.Mode;
import org.l2jmobius.gameserver.managers.conquest.ConquestManager;
import org.l2jmobius.gameserver.managers.conquest.ConquestMatch;
import org.l2jmobius.gameserver.managers.conquest.ConquestRegistration;

/**
 * The PvP events take turns: every {@link EventRotationConfig#SCHEDULE} (every hour by default) the next event of {@link EventRotationConfig#ORDER} opens its registration: Team vs Team, Conquest, Deathmatch, and again. They never overlap: while one still goes on
 * (its registration is open, or a match with players runs) the turn is skipped, and the same event opens at the next one. An event that can't open (disabled, no map) gives its turn to the next one.<br>
 * With the rotation on, the events' own schedules (TvTSchedule, ConquestSchedule) are off; a GM can still open one by hand, as long as no other goes on.
 */
public class EventRotationManager
{
	private static final Logger LOGGER = Logger.getLogger(EventRotationManager.class.getName());

	/** The index in {@link EventRotationConfig#ORDER} of the event whose turn comes next. */
	private int _next;
	private ScheduledFuture<?> _task;
	private long _nextTime;
	private volatile String _last = "none yet";

	protected EventRotationManager()
	{
		if (!EventRotationConfig.ENABLED)
		{
			return;
		}

		// Without a saved turn, the hour of the day says where it starts: a restart doesn't always begin with the same event.
		_next = (int) ((System.currentTimeMillis() / 3600000L) % EventRotationConfig.ORDER.size());
		scheduleNext();
	}

	private synchronized void scheduleNext()
	{
		if (_task != null)
		{
			_task.cancel(false);
			_task = null;
		}

		final long delay;
		try
		{
			delay = new SchedulingPattern(EventRotationConfig.SCHEDULE).getDelayToNextFromNow() + 5000; // 5 seconds late, so it never runs twice for one time.
		}
		catch (Exception e)
		{
			LOGGER.warning(getClass().getSimpleName() + ": Bad EventRotationSchedule \"" + EventRotationConfig.SCHEDULE + "\": the events don't start by themselves.");
			return;
		}

		_nextTime = System.currentTimeMillis() + delay;
		_task = ThreadPool.schedule(() ->
		{
			rotate();
			scheduleNext();
		}, delay);
		LOGGER.info(getClass().getSimpleName() + ": Next event (" + getTitle(EventRotationConfig.ORDER.get(_next)) + ") at " + TimeUtil.getDateTimeString(_nextTime) + ".");
	}

	/**
	 * One turn: the next event opens its registration, unless another still goes on.
	 */
	private synchronized void rotate()
	{
		final String busy = getBusyEvent();
		if (busy != null)
		{
			_last = "skipped at " + TimeUtil.getDateTimeString(System.currentTimeMillis()) + ": " + busy + " was still going on";
			LOGGER.info(getClass().getSimpleName() + ": " + busy + " still goes on: " + getTitle(EventRotationConfig.ORDER.get(_next)) + " waits for the next turn.");
			return;
		}

		final List<String> refusals = new ArrayList<>();
		final int size = EventRotationConfig.ORDER.size();
		for (int i = 0; i < size; i++)
		{
			final int index = (_next + i) % size;
			final String event = EventRotationConfig.ORDER.get(index);
			final String refusal = open(event);
			if (refusal == null)
			{
				_next = (index + 1) % size;
				_last = getTitle(event) + " at " + TimeUtil.getDateTimeString(System.currentTimeMillis());
				LOGGER.info(getClass().getSimpleName() + ": " + getTitle(event) + " opened its registration" + (refusals.isEmpty() ? "." : " (" + String.join("; ", refusals) + ")."));
				return;
			}
			refusals.add(getTitle(event) + " could not open: " + refusal);
		}
		_last = "nothing at " + TimeUtil.getDateTimeString(System.currentTimeMillis()) + ": " + String.join("; ", refusals);
		LOGGER.info(getClass().getSimpleName() + ": No event opened: " + String.join("; ", refusals) + ".");
	}

	/**
	 * @param event one of {@link EventRotationConfig#EVENTS}
	 * @return {@code null} if its registration opened, else why not
	 */
	private static String open(String event)
	{
		switch (event)
		{
			case "tvt":
			{
				return TeamVsTeamManager.getInstance().openRegistration(Mode.TVT, null);
			}
			case "deathmatch":
			{
				return TeamVsTeamManager.getInstance().openRegistration(Mode.DEATHMATCH, null);
			}
			case "passivedeathmatch":
			{
				return TeamVsTeamManager.getInstance().openRegistration(Mode.PASSIVE_DEATHMATCH, null);
			}
			case "conquest":
			{
				return ConquestRegistration.getInstance().open(null);
			}
			case "racewars":
			{
				return RaceWarsManager.getInstance().openRegistration();
			}
			default:
			{
				return "unknown event";
			}
		}
	}

	/**
	 * @param event one of {@link EventRotationConfig#EVENTS}
	 * @return its name for the players
	 */
	public static String getTitle(String event)
	{
		switch (event)
		{
			case "tvt":
			{
				return Mode.TVT.getTitle();
			}
			case "deathmatch":
			{
				return Mode.DEATHMATCH.getTitle();
			}
			case "passivedeathmatch":
			{
				return Mode.PASSIVE_DEATHMATCH.getTitle();
			}
			case "conquest":
			{
				return "Conquest";
			}
			case "racewars":
			{
				return RaceWarsManager.TITLE;
			}
			default:
			{
				return event;
			}
		}
	}

	/**
	 * What keeps another event from starting: an open registration, or a match with players going on (a GM's match of fake players only doesn't count).
	 * @return the name of the event going on, {@code null} if none
	 */
	public static String getBusyEvent()
	{
		final TeamVsTeamManager tvt = TeamVsTeamManager.getInstance();
		if (tvt.isRegistrationOpen())
		{
			return tvt.getRegistrationMode().getTitle() + " registration";
		}
		for (Match match : tvt.getMatches())
		{
			if ((match.getPhase() != TeamVsTeamManager.Phase.ENDED) && !match.getPlayers().isEmpty())
			{
				return match.getMode().getTitle();
			}
		}
		if (ConquestRegistration.getInstance().isOpen())
		{
			return "Conquest registration";
		}
		for (ConquestMatch match : ConquestManager.getInstance().getMatches())
		{
			if ((match.getPhase() != ConquestMatch.Phase.ENDED) && match.hasPlayers())
			{
				return "Conquest";
			}
		}
		final RaceWarsManager raceWars = RaceWarsManager.getInstance();
		if (raceWars.isRegistrationOpen())
		{
			return RaceWarsManager.TITLE + " registration";
		}
		if (raceWars.hasPlayersInEvent())
		{
			return RaceWarsManager.TITLE;
		}
		return null;
	}

	/**
	 * Makes an event take the next turn.
	 * @param event one of {@link EventRotationConfig#EVENTS}
	 * @return {@code false} if it is not in {@link EventRotationConfig#ORDER}
	 */
	public synchronized boolean setNext(String event)
	{
		final int index = EventRotationConfig.ORDER.indexOf(event);
		if (index < 0)
		{
			return false;
		}
		_next = index;
		return true;
	}

	/**
	 * @return the rotation as lines for a GM: on or off, the order, the next turn, the last one, and what goes on now
	 */
	public synchronized List<String> getStatus()
	{
		final List<String> lines = new ArrayList<>();
		final List<String> titles = new ArrayList<>();
		for (String event : EventRotationConfig.ORDER)
		{
			titles.add(getTitle(event));
		}
		if (!EventRotationConfig.ENABLED)
		{
			lines.add("The event rotation is off (EventRotationEnabled): each event follows its own schedule.");
			return lines;
		}
		lines.add("Event rotation (" + EventRotationConfig.SCHEDULE + "): " + String.join(" > ", titles) + ".");
		lines.add("Next turn: " + getTitle(EventRotationConfig.ORDER.get(_next)) + (_task != null ? " at " + TimeUtil.getDateTimeString(_nextTime) : " (not scheduled: check EventRotationSchedule)") + ".");
		lines.add("Last turn: " + _last + ".");
		final String busy = getBusyEvent();
		lines.add(busy != null ? "Going on now: " + busy + "." : "No event goes on now.");
		return lines;
	}

	public static EventRotationManager getInstance()
	{
		return SingletonHolder.INSTANCE;
	}

	private static class SingletonHolder
	{
		protected static final EventRotationManager INSTANCE = new EventRotationManager();
	}
}
