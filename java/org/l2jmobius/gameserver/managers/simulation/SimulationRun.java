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

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ScheduledFuture;

import org.l2jmobius.gameserver.model.actor.Creature;

/**
 * One round of a simulation: its teams, its members by object id, its log and its monitor.
 */
public class SimulationRun
{
	public enum Phase
	{
		/** Spawned: supports buff, nobody marches yet. */
		PREP,
		/** The teams look for each other and fight. */
		FIGHT,
		/** Over: the bodies wait for the cleanup. */
		ENDED
	}

	private final int _id;
	private final int _round;
	private final SimulationSettings _settings;
	private final long _start = System.currentTimeMillis();
	private final List<SimulationTeam> _teams = new CopyOnWriteArrayList<>();
	private final Map<Integer, SimulationMember> _members = new ConcurrentHashMap<>();
	private final List<JsonLine> _kills = new CopyOnWriteArrayList<>();
	private final List<String> _killTexts = new CopyOnWriteArrayList<>();
	private final SimulationLog _log;
	private final SimulationMonitor _monitor;
	private volatile Phase _phase = Phase.PREP;
	private volatile long _fightStart;
	private volatile long _lastClash;
	private volatile long _decidedAt;
	private volatile long _end;
	private volatile int _winner;
	private volatile String _endReason;
	private volatile ScheduledFuture<?> _task;
	/** The battle of a class matrix this round plays, {@code null} for a round of //Simulate. */
	private volatile SimulationMatrix.Battle _battle;
	private volatile SimulationArena.Battle _arenaBattle;
	private volatile SimulationWar.Battle _warBattle;
	/** Kills by team: killer team * 1000 + victim team -> kills (killer team 0 for a death no team dealt). */
	private final Map<Integer, Integer> _teamKills = new ConcurrentHashMap<>();

	public SimulationRun(int id, int round, SimulationSettings settings, SimulationLog log)
	{
		_id = id;
		_round = round;
		_settings = settings;
		_log = log;
		_monitor = new SimulationMonitor(this);
	}

	public int getId()
	{
		return _id;
	}

	public int getRound()
	{
		return _round;
	}

	public SimulationSettings getSettings()
	{
		return _settings;
	}

	/**
	 * @return when it started (ms since epoch)
	 */
	public long getStart()
	{
		return _start;
	}

	/**
	 * @return the milliseconds since it started: the "t" of every log line
	 */
	public long time()
	{
		return System.currentTimeMillis() - _start;
	}

	public List<SimulationTeam> getTeams()
	{
		return _teams;
	}

	/**
	 * @param id a team id (its point number)
	 * @return the team, {@code null} if none
	 */
	public SimulationTeam getTeam(int id)
	{
		for (SimulationTeam team : _teams)
		{
			if (team.getId() == id)
			{
				return team;
			}
		}
		return null;
	}

	/**
	 * @return its members by object id (the ones that left or were replaced included)
	 */
	public Map<Integer, SimulationMember> getMembers()
	{
		return _members;
	}

	/**
	 * @param creature a creature
	 * @return its member, {@code null} if it isn't in this run
	 */
	public SimulationMember getMember(Creature creature)
	{
		return creature != null ? _members.get(creature.getObjectId()) : null;
	}

	public void addMember(SimulationTeam team, SimulationMember member)
	{
		team.getMembers().add(member);
		_members.put(member.getObjectId(), member);
	}

	/**
	 * @return the kills so far, in order
	 */
	public List<JsonLine> getKills()
	{
		return _kills;
	}
	
	/**
	 * @return the kills so far, in order, as text
	 */
	public List<String> getKillTexts()
	{
		return _killTexts;
	}
	
	public void addKill(JsonLine line, String text)
	{
		_kills.add(line);
		_killTexts.add(text);
	}

	public SimulationLog getLog()
	{
		return _log;
	}

	public SimulationMonitor getMonitor()
	{
		return _monitor;
	}

	public Phase getPhase()
	{
		return _phase;
	}

	public void setPhase(Phase phase)
	{
		_phase = phase;
	}

	/**
	 * @return when the fight started (ms since the run started), 0 while getting ready
	 */
	public long getFightStart()
	{
		return _fightStart;
	}

	public void setFightStart(long fightStart)
	{
		_fightStart = fightStart;
	}

	/**
	 * @return when (ms since the start) a fake player last hurt a fake player of another team, or the round was last pushed back into the fight; 0 if never
	 */
	/**
	 * @return when it started (ms since epoch)
	 */
	public long getStartTime()
	{
		return _start;
	}

	/**
	 * @return when (ms since the start) a team was first down to its last member, 0 if none yet: the round was decided then, whatever a last tank under Ultimate Defense does after
	 */
	public long getDecidedAt()
	{
		return _decidedAt;
	}

	public void setDecidedAt(long time)
	{
		_decidedAt = time;
	}

	public long getLastClash()
	{
		return _lastClash;
	}

	public void setLastClash(long time)
	{
		_lastClash = time;
	}

	/**
	 * @return when it ended (ms since the run started), 0 while it goes on
	 */
	public long getEnd()
	{
		return _end;
	}

	/**
	 * @return the winning team, 0 for none
	 */
	public int getWinner()
	{
		return _winner;
	}

	public String getEndReason()
	{
		return _endReason;
	}

	public void setResult(long end, int winner, String reason)
	{
		_end = end;
		_winner = winner;
		_endReason = reason;
	}

	public ScheduledFuture<?> getTask()
	{
		return _task;
	}

	public void setTask(ScheduledFuture<?> task)
	{
		_task = task;
	}

	/**
	 * @param member a member
	 * @return its team
	 */
	public SimulationMatrix.Battle getBattle()
	{
		return _battle;
	}

	public void setBattle(SimulationMatrix.Battle battle)
	{
		_battle = battle;
	}

	/**
	 * @return the A/B arena battle this round is (see {@link SimulationArena}), {@code null} for a round of //Simulate or the matrix
	 */
	public SimulationArena.Battle getArenaBattle()
	{
		return _arenaBattle;
	}

	public void setArenaBattle(SimulationArena.Battle battle)
	{
		_arenaBattle = battle;
	}

	/**
	 * @return the race war battle this run is, {@code null} if it isn't one
	 */
	public SimulationWar.Battle getWarBattle()
	{
		return _warBattle;
	}

	public void setWarBattle(SimulationWar.Battle battle)
	{
		_warBattle = battle;
	}

	/**
	 * Counts a death by who dealt it.
	 * @param killerTeam the team of the killer, 0 for none
	 * @param victimTeam the team of the dead
	 */
	public void countTeamKill(int killerTeam, int victimTeam)
	{
		_teamKills.merge((killerTeam * 1000) + victimTeam, 1, Integer::sum);
	}

	/**
	 * @param killerTeam a team, 0 for deaths no team dealt
	 * @param victimTeam another team
	 * @return how many of {@code victimTeam} {@code killerTeam} killed
	 */
	public int getTeamKills(int killerTeam, int victimTeam)
	{
		return _teamKills.getOrDefault((killerTeam * 1000) + victimTeam, 0);
	}

	public SimulationTeam getTeamOf(SimulationMember member)
	{
		return getTeam(member.getTeamId());
	}

	/**
	 * @param member a member
	 * @return the living members of the other teams
	 */
	public List<SimulationMember> getEnemiesOf(SimulationMember member)
	{
		final List<SimulationMember> enemies = new ArrayList<>();
		for (SimulationTeam team : _teams)
		{
			if (team.getId() == member.getTeamId())
			{
				continue;
			}

			for (SimulationMember other : team.getMembers())
			{
				if (team.isActive(other) && other.isAlive())
				{
					enemies.add(other);
				}
			}
		}
		return enemies;
	}
}
