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

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import org.l2jmobius.commons.util.Rnd;
import org.l2jmobius.gameserver.config.custom.ConquestConfig;
import org.l2jmobius.gameserver.model.Location;
import org.l2jmobius.gameserver.model.actor.Creature;
import org.l2jmobius.gameserver.model.actor.enums.creature.Team;
import org.l2jmobius.gameserver.model.conquest.ConquestPoint;

/**
 * Blue or Red of a Conquest match: its spawn points, parties and score.
 */
public class ConquestTeam
{
	private final Team _team;
	private final String _name;
	private final int _nameColor;
	private final List<ConquestPoint> _spawns;
	private final List<ConquestFighter> _fighters = new CopyOnWriteArrayList<>();
	private final List<ConquestParty> _parties = new CopyOnWriteArrayList<>();
	private final ConquestCommander _commander;
	private volatile int _score;
	private volatile int _drain;
	private volatile int _held;
	private volatile int _kills;
	// When the next wave of the dead comes back.
	private long _nextWave;

	ConquestTeam(Team team, String name, int nameColor, List<ConquestPoint> spawns, ConquestCommander commander)
	{
		_team = team;
		_name = name;
		_nameColor = nameColor;
		_spawns = spawns;
		_commander = commander;
		_score = ConquestConfig.START_SCORE;
	}

	public Team getTeam()
	{
		return _team;
	}

	public String getName()
	{
		return _name;
	}

	public int getNameColor()
	{
		return _nameColor;
	}

	public List<ConquestPoint> getSpawns()
	{
		return _spawns;
	}

	public List<ConquestFighter> getFighters()
	{
		return _fighters;
	}

	public List<ConquestParty> getParties()
	{
		return _parties;
	}

	public ConquestCommander getCommander()
	{
		return _commander;
	}

	public int getScore()
	{
		return _score;
	}

	void setScore(int score)
	{
		_score = score;
	}

	/**
	 * @return what it lost at the last tick
	 */
	public int getDrain()
	{
		return _drain;
	}

	void setDrain(int drain)
	{
		_drain = drain;
	}

	/**
	 * @return how many capture points it held at the last tick
	 */
	public int getHeld()
	{
		return _held;
	}

	void setHeld(int held)
	{
		_held = held;
	}

	public int getKills()
	{
		return _kills;
	}

	void addKill()
	{
		_kills++;
	}

	/**
	 * @return how many of its fighters are alive
	 */
	public int getAlive()
	{
		int alive = 0;
		for (ConquestFighter fighter : _fighters)
		{
			if (fighter.isAlive())
			{
				alive++;
			}
		}
		return alive;
	}

	/**
	 * @param x a spot's x
	 * @param y a spot's y
	 * @return {@code true} if the spot lies in one of its bases (around a spawn point)
	 */
	public boolean isInBase(int x, int y)
	{
		for (ConquestPoint spawn : _spawns)
		{
			final double reach = spawn.radius() + ConquestConfig.BASE_RADIUS;
			if (Math.hypot(spawn.x() - x, spawn.y() - y) <= reach)
			{
				return true;
			}
		}
		return false;
	}

	/**
	 * @param creature a creature
	 * @return its spawn point closest to the creature
	 */
	public ConquestPoint getClosestSpawn(Creature creature)
	{
		ConquestPoint closest = _spawns.get(0);
		for (ConquestPoint spawn : _spawns)
		{
			if (Math.hypot(spawn.x() - creature.getX(), spawn.y() - creature.getY()) < Math.hypot(closest.x() - creature.getX(), closest.y() - creature.getY()))
			{
				closest = spawn;
			}
		}
		return closest;
	}

	/**
	 * Puts one that died into a wave. A party's dead come back together: one whose party already waits for a wave joins it. Otherwise the team's wave already waiting if it comes in at least half the wave time, else a new wave after the full time; each party of
	 * a wave picks its own spawn point by chance.
	 * @param dead the one that died
	 * @param now the time of its death
	 */
	synchronized void joinWave(ConquestFighter dead, long now)
	{
		final ConquestParty party = dead.getParty();
		if ((party != null) && (party._waveAt > now) && (party._waveBase != null))
		{
			dead._respawnAt = party._waveAt;
			dead._respawnBase = party._waveBase;
			return;
		}

		final long delay = ConquestConfig.RESPAWN_WAVE * 1000L;
		if ((_nextWave - now) < (delay / 2))
		{
			_nextWave = now + delay;
		}
		final ConquestPoint spawn = _spawns.get(Rnd.get(_spawns.size()));
		final Location base = new Location(spawn.x(), spawn.y(), spawn.z(), spawn.heading());
		dead._respawnAt = _nextWave;
		dead._respawnBase = base;
		if (party != null)
		{
			party._waveAt = _nextWave;
			party._waveBase = base;
			party._waveSpot = null;
		}
	}

	/**
	 * @param now the time
	 * @return when its next wave comes back (0: none waiting)
	 */
	public long getNextWave(long now)
	{
		long next = 0;
		for (ConquestFighter fighter : _fighters)
		{
			final long at = fighter._respawnAt;
			if ((at > now) && ((next == 0) || (at < next)) && fighter.getNpc().isDead())
			{
				next = at;
			}
		}
		return next;
	}

	/**
	 * @return how many of its dead wait for a wave
	 */
	public int getWaiting()
	{
		int waiting = 0;
		for (ConquestFighter fighter : _fighters)
		{
			if ((fighter._respawnAt > 0) && fighter.getNpc().isDead())
			{
				waiting++;
			}
		}
		return waiting;
	}

	/**
	 * @param at the time of a wave
	 * @return the spawn points its parties come back at with that wave
	 */
	public List<Location> getWaveBases(long at)
	{
		final List<Location> bases = new ArrayList<>();
		for (ConquestFighter fighter : _fighters)
		{
			final Location base = fighter._respawnBase;
			if ((fighter._respawnAt == at) && (base != null) && fighter.getNpc().isDead() && !bases.contains(base))
			{
				bases.add(base);
			}
		}
		return bases;
	}
}
