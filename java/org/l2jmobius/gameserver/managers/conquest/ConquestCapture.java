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

import org.l2jmobius.gameserver.config.custom.ConquestConfig;
import org.l2jmobius.gameserver.model.Location;
import org.l2jmobius.gameserver.model.actor.enums.creature.Team;
import org.l2jmobius.gameserver.model.conquest.ConquestPoint;

/**
 * A capture point of a running Conquest match: who holds it and how far the capture has come.<br>
 * Progress goes from -100 (Red holds it) through 0 (neutral) to +100 (Blue holds it). A point held by a team stays its own until the other team has pushed the progress down to neutral; it goes over to a team once the progress reaches that team's end.
 */
public class ConquestCapture
{
	private final ConquestPoint _point;
	private final Location _location;
	private volatile double _progress;
	private volatile Team _owner = Team.NONE;
	private volatile int _blue;
	private volatile int _red;
	private volatile boolean _stalemate;
	private volatile double _rate;
	private volatile boolean _contested;
	// The capture's sweep the match's players were sent last (Ex2RadarZoneProgress) and the owner their radar shows.
	Team _sentOwner = Team.NONE;
	Team _sentSweep = Team.NONE;
	int _sentFraction;
	int _sentRate;
	int _sentFlags;
	long _sentAt;

	ConquestCapture(ConquestPoint point)
	{
		_point = point;
		_location = new Location(point.x(), point.y(), point.z());
	}

	/**
	 * The capture rule, alone so it can be tried without a server: the progress moves toward the team with more fighters on the point, only while it has more than {@code majorityPercent} % more of them than the other team, and not at all while both have
	 * {@code stalemate} or more there. It moves {@code baseRate} with one fighter more, {@code perExtra} more for each further one, at most {@code maxRate}.
	 * @param progress the progress now, -100 (Red) to 100 (Blue)
	 * @param blue the Blue fighters on the point
	 * @param red the Red fighters on the point
	 * @param baseRate progress per second with one fighter more
	 * @param perExtra progress per second more for each further fighter more
	 * @param maxRate the most progress per second
	 * @param majorityPercent how much more (in %) the team ahead must have
	 * @param stalemate with at least this many of each team, nothing moves
	 * @return the progress a second later
	 */
	public static double step(double progress, int blue, int red, double baseRate, double perExtra, double maxRate, int majorityPercent, int stalemate)
	{
		if ((blue >= stalemate) && (red >= stalemate))
		{
			return progress;
		}

		final int ahead = Math.max(blue, red);
		final int behind = Math.min(blue, red);
		// More than majorityPercent % more: 6 against 4 at 25 %, not 5 against 4.
		if ((ahead == 0) || ((ahead * 100L) <= (behind * (100L + majorityPercent))))
		{
			return progress;
		}

		final double rate = Math.min(maxRate, baseRate + (perExtra * (ahead - behind - 1)));
		return blue > red ? Math.min(100, progress + rate) : Math.max(-100, progress - rate);
	}

	/**
	 * @param progress the progress
	 * @param owner who held the point before
	 * @return who holds it at that progress: a team at its end, nobody once a team's point was pushed down to neutral
	 */
	public static Team ownerAt(double progress, Team owner)
	{
		if (progress >= 100)
		{
			return Team.BLUE;
		}
		if (progress <= -100)
		{
			return Team.RED;
		}
		if (((owner == Team.BLUE) && (progress <= 0)) || ((owner == Team.RED) && (progress >= 0)))
		{
			return Team.NONE;
		}
		return owner;
	}

	/**
	 * One second of capture with these fighters on the point.
	 * @param blue the Blue fighters on it
	 * @param red the Red fighters on it
	 * @return who held it before, if it changed hands (or became neutral), else {@code null}
	 */
	Team update(int blue, int red)
	{
		_blue = blue;
		_red = red;
		_stalemate = (blue >= ConquestConfig.CAPTURE_STALEMATE_COUNT) && (red >= ConquestConfig.CAPTURE_STALEMATE_COUNT);
		// Contested: both teams on it and neither has the majority to move it (from neutral nothing caps the step, so only the rule holds it).
		_contested = (blue > 0) && (red > 0) && (step(0, blue, red, ConquestConfig.CAPTURE_BASE_RATE, ConquestConfig.CAPTURE_RATE_PER_EXTRA, ConquestConfig.CAPTURE_MAX_RATE, ConquestConfig.CAPTURE_MAJORITY_PERCENT, ConquestConfig.CAPTURE_STALEMATE_COUNT) == 0);
		final double progress = _progress;
		_progress = step(progress, blue, red, ConquestConfig.CAPTURE_BASE_RATE, ConquestConfig.CAPTURE_RATE_PER_EXTRA, ConquestConfig.CAPTURE_MAX_RATE, ConquestConfig.CAPTURE_MAJORITY_PERCENT, ConquestConfig.CAPTURE_STALEMATE_COUNT);
		_rate = _progress - progress;
		final Team before = _owner;
		_owner = ownerAt(_progress, before);
		return _owner != before ? before : null;
	}

	public ConquestPoint getPoint()
	{
		return _point;
	}

	public String getName()
	{
		return _point.name();
	}

	public Location getLocation()
	{
		return _location;
	}

	public int getRadius()
	{
		return _point.radius();
	}

	/**
	 * @return the progress, -100 (Red) to 100 (Blue)
	 */
	public double getProgress()
	{
		return _progress;
	}

	public Team getOwner()
	{
		return _owner;
	}

	/**
	 * @return the Blue fighters on it at the last tick
	 */
	public int getBlue()
	{
		return _blue;
	}

	/**
	 * @return the Red fighters on it at the last tick
	 */
	public int getRed()
	{
		return _red;
	}

	/**
	 * @param team a team
	 * @return that team's fighters on it at the last tick
	 */
	public int getCount(Team team)
	{
		return team == Team.BLUE ? _blue : team == Team.RED ? _red : 0;
	}

	/**
	 * @return {@code true} if both teams had enough fighters on it that nothing moved
	 */
	public boolean isStalemate()
	{
		return _stalemate;
	}

	/**
	 * @return how far the progress moved at the last tick (a second), toward Blue if positive
	 */
	public double getRate()
	{
		return _rate;
	}

	/**
	 * @return {@code true} if both teams were on it at the last tick and neither could move it (a stalemate, or no majority)
	 */
	public boolean isContested()
	{
		return _contested;
	}

	/**
	 * @param x a spot's x
	 * @param y a spot's y
	 * @param z a spot's z
	 * @return {@code true} if a fighter standing there counts on the point
	 */
	public boolean contains(int x, int y, int z)
	{
		final long dx = x - _point.x();
		final long dy = y - _point.y();
		return (((dx * dx) + (dy * dy)) <= ((long) _point.radius() * _point.radius())) && (Math.abs(z - _point.z()) <= ConquestConfig.CAPTURE_HEIGHT);
	}
}
