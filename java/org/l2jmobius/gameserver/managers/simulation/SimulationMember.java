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

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.EnumMap;
import java.util.Map;
import java.util.TreeMap;

import org.l2jmobius.gameserver.model.actor.Creature;

/**
 * A member of a simulation team: a fake player, or the admin that joined the team. Holds what the summary reports (damage, heals, kills, time spent on each AI decision, distance) and what the anomaly rules of {@link SimulationMonitor} look back on.<br>
 * Combat hooks and the AI update it from many threads, so everything that changes goes through its monitor.
 */
public class SimulationMember
{
	/** A hit it took: when (ms), from whom (object id of the player or fake player behind it), how much. */
	public record Hit(long time, int attackerId, double amount)
	{
	}

	/** A position sample of the monitor: when, where, and what it was doing. */
	public record Sample(long time, int x, int y, int z, boolean moving, boolean casting, boolean attacking, boolean disabled, double damageDealt)
	{
	}

	private final Creature _creature;
	private final int _teamId;
	private final SimRole _role;
	private final String _build;
	private final boolean _admin;
	private final long _seed;
	private final long _joined;

	private double _damageDealt;
	private double _damageTaken;
	private double _healDone;
	private double _healReceived;
	private int _kills;
	private int _deaths;
	private int _resisted;
	private int _pathFailures;
	private long _diedAt = -1;
	private long _aliveTime;
	private double _distance;

	private Reason _lastReason;
	private long _lastWait = Long.MIN_VALUE;
	private long _lastReasonAt;
	private final Map<Reason, Long> _reasonTime = new EnumMap<>(Reason.class);
	private final Map<String, Integer> _anomalies = new TreeMap<>();

	private final Deque<Hit> _hits = new ArrayDeque<>();
	private final Deque<Sample> _samples = new ArrayDeque<>();
	private final Deque<Long> _targetChanges = new ArrayDeque<>();

	// Anomaly episodes in progress (an anomaly is written once per episode), and the last path failure written.
	private final Map<String, Boolean> _episodes = new TreeMap<>();
	private final Map<String, Deque<long[]>> _windows = new TreeMap<>();
	private long _lastPathFailure;
	private long _pressureQuietUntil;
	private int _lastTargetId;
	private String _lastIntention;

	public SimulationMember(Creature creature, int teamId, SimRole role, String build, boolean admin, long seed, long joined)
	{
		_creature = creature;
		_teamId = teamId;
		_role = role;
		_build = build;
		_admin = admin;
		_seed = seed;
		_joined = joined;
	}

	public Creature getCreature()
	{
		return _creature;
	}

	public int getObjectId()
	{
		return _creature.getObjectId();
	}

	public String getName()
	{
		return _creature.getName();
	}

	public int getTeamId()
	{
		return _teamId;
	}

	public SimRole getRole()
	{
		return _role;
	}

	public String getBuild()
	{
		return _build;
	}

	/**
	 * @return {@code true} for the admin that joined the team
	 */
	public boolean isAdmin()
	{
		return _admin;
	}

	public long getSeed()
	{
		return _seed;
	}

	/**
	 * @return {@code true} if it is in the world and alive
	 */
	public boolean isAlive()
	{
		return !_creature.isDead() && (_creature.isPlayer() ? _creature.asPlayer().isOnline() : _creature.isSpawned());
	}

	// ---------------------------------------------------------------------------------------------
	// Combat
	// ---------------------------------------------------------------------------------------------

	public synchronized void addDealt(double damage)
	{
		_damageDealt += damage;
	}

	public synchronized void addTaken(long time, int attackerId, double damage)
	{
		_damageTaken += damage;
		_hits.addLast(new Hit(time, attackerId, damage));
		while (!_hits.isEmpty() && ((time - _hits.peekFirst().time()) > 10000))
		{
			_hits.pollFirst();
		}
	}

	public synchronized void addHealDone(double amount)
	{
		_healDone += amount;
	}

	public synchronized void addHealReceived(double amount)
	{
		_healReceived += amount;
	}

	public synchronized void addKill()
	{
		_kills++;
	}

	public synchronized void addResisted()
	{
		_resisted++;
	}

	public synchronized void onDeath(long time)
	{
		_deaths++;
		if (_diedAt < 0)
		{
			_diedAt = time;
		}
	}

	/**
	 * @param time when (ms)
	 * @return {@code true} if a path failure is worth writing (not more than one every 2 seconds)
	 */
	public synchronized boolean onPathFailure(long time)
	{
		_pathFailures++;
		if ((time - _lastPathFailure) < 2000)
		{
			return false;
		}
		_lastPathFailure = time;
		return true;
	}

	/**
	 * @param since from when (ms)
	 * @return the hits it took since then
	 */
	public synchronized Hit[] getHitsSince(long since)
	{
		return _hits.stream().filter(hit -> hit.time() >= since).toArray(Hit[]::new);
	}

	public synchronized double getDamageDealt()
	{
		return _damageDealt;
	}

	public synchronized double getDamageTaken()
	{
		return _damageTaken;
	}

	public synchronized double getHealDone()
	{
		return _healDone;
	}

	public synchronized double getHealReceived()
	{
		return _healReceived;
	}

	public synchronized int getKills()
	{
		return _kills;
	}

	public synchronized int getDeaths()
	{
		return _deaths;
	}

	public synchronized int getResisted()
	{
		return _resisted;
	}

	public synchronized int getPathFailures()
	{
		return _pathFailures;
	}

	/**
	 * @return when it first died (ms since the run started), -1 if it never did
	 */
	public synchronized long getDiedAt()
	{
		return _diedAt;
	}

	public long getJoined()
	{
		return _joined;
	}

	// ---------------------------------------------------------------------------------------------
	// Decisions
	// ---------------------------------------------------------------------------------------------

	/**
	 * Counts the time since its last decision for that decision.
	 * @param reason what it decides now
	 * @param now the current time (ms since epoch)
	 * @return what it decided before, {@code null} if nothing yet
	 */
	public synchronized Reason onDecision(Reason reason, long now)
	{
		final Reason previous = _lastReason;
		if (previous != null)
		{
			_reasonTime.merge(previous, Math.min(5000, now - _lastReasonAt), Long::sum);
		}
		_lastReason = reason;
		_lastReasonAt = now;
		return previous;
	}

	public synchronized Reason getLastReason()
	{
		return _lastReason;
	}
	
	/**
	 * @param time when it was last seen waiting on purpose (holding off, waiting out an invincible player, holding by its squishies), ms since the run started
	 */
	public synchronized void setLastWait(long time)
	{
		_lastWait = time;
	}
	
	/**
	 * @return when it was last seen waiting on purpose, ms since the run started, {@link Long#MIN_VALUE} if never
	 */
	public synchronized long getLastWait()
	{
		return _lastWait;
	}

	/**
	 * @return milliseconds spent on each decision, most first
	 */
	public synchronized Map<Reason, Long> getReasonTime()
	{
		return new EnumMap<>(_reasonTime);
	}

	// ---------------------------------------------------------------------------------------------
	// Monitor
	// ---------------------------------------------------------------------------------------------

	/**
	 * Keeps a position sample (the last 12) and adds the way it went since the previous one.
	 * @param sample the sample
	 * @param alive {@code true} if it is alive (time alive is counted)
	 * @param interval the time since the previous sample (ms)
	 */
	public synchronized void addSample(Sample sample, boolean alive, long interval)
	{
		final Sample last = _samples.peekLast();
		if ((last != null) && alive)
		{
			_distance += Math.hypot(sample.x() - last.x(), sample.y() - last.y());
		}
		if (alive)
		{
			_aliveTime += interval;
		}
		_samples.addLast(sample);
		while (_samples.size() > 12)
		{
			_samples.pollFirst();
		}
	}

	/**
	 * @param since from when (ms)
	 * @return its samples since then, oldest first
	 */
	public synchronized Sample[] getSamplesSince(long since)
	{
		return _samples.stream().filter(sample -> sample.time() >= since).toArray(Sample[]::new);
	}

	public synchronized double getDistance()
	{
		return _distance;
	}

	public synchronized long getAliveTime()
	{
		return _aliveTime;
	}

	/**
	 * Notes a change of target.
	 * @param time when (ms)
	 * @param window the time looked back on (ms)
	 * @return how many changes it made within {@code window}
	 */
	public synchronized int addTargetChange(long time, long window)
	{
		_targetChanges.addLast(time);
		while (!_targetChanges.isEmpty() && ((time - _targetChanges.peekFirst()) > window))
		{
			_targetChanges.pollFirst();
		}
		return _targetChanges.size();
	}

	public synchronized void clearTargetChanges()
	{
		_targetChanges.clear();
	}

	/**
	 * @param targetId its target now
	 * @return its target before (object id, 0 for none)
	 */
	public synchronized int swapTarget(int targetId)
	{
		final int previous = _lastTargetId;
		_lastTargetId = targetId;
		return previous;
	}

	/**
	 * @param intention its intention now
	 * @return its intention before, {@code null} for none yet
	 */
	public synchronized String swapIntention(String intention)
	{
		final String previous = _lastIntention;
		_lastIntention = intention;
		return previous;
	}

	/**
	 * Tracks an anomaly that lasts (stuck, split...): it is written when it starts, not again until it ended.
	 * @param type the anomaly
	 * @param active {@code true} if the condition holds now
	 * @return {@code true} if it just started
	 */
	public synchronized boolean episode(String type, boolean active)
	{
		final boolean was = _episodes.getOrDefault(type, false);
		_episodes.put(type, active);
		return active && !was;
	}

	/**
	 * Forgets a running total kept by {@link #windowDelta}: it starts over.
	 * @param key the total
	 */
	public synchronized void resetWindow(String key)
	{
		_windows.remove(key);
	}

	/**
	 * Keeps a running total (time spent on a decision...) and tells how much it grew lately.
	 * @param key the total
	 * @param time the current time (ms)
	 * @param value the total now
	 * @param window how far back to look (ms)
	 * @return how much it grew within {@code window}, or since the first value kept when it is younger
	 */
	public synchronized long windowDelta(String key, long time, long value, long window)
	{
		final Deque<long[]> values = _windows.computeIfAbsent(key, _ -> new ArrayDeque<>());
		values.addLast(new long[]
		{
			time,
			value
		});
		while ((values.size() > 1) && ((time - values.peekFirst()[0]) > window))
		{
			values.pollFirst();
		}
		return value - values.peekFirst()[1];
	}

	/**
	 * @param time the current time (ms)
	 * @param quiet how long to keep quiet after this one (ms)
	 * @return {@code true} if a pressure anomaly may be written for it now
	 */
	public synchronized boolean takePressureTurn(long time, long quiet)
	{
		if (time < _pressureQuietUntil)
		{
			return false;
		}
		_pressureQuietUntil = time + quiet;
		return true;
	}

	public synchronized void countAnomaly(String type)
	{
		_anomalies.merge(type, 1, Integer::sum);
	}

	public synchronized Map<String, Integer> getAnomalies()
	{
		return new TreeMap<>(_anomalies);
	}
}
