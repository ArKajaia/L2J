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
package org.l2jmobius.gameserver.model.classtransfer;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import org.l2jmobius.gameserver.managers.ClassTransferChallengeManager;
import org.l2jmobius.gameserver.model.Location;
import org.l2jmobius.gameserver.model.World;
import org.l2jmobius.gameserver.model.actor.Npc;
import org.l2jmobius.gameserver.model.actor.Player;
import org.l2jmobius.gameserver.model.classtransfer.TrackedNpc.NpcRole;
import org.l2jmobius.gameserver.model.classtransfer.objective.AbstractChallengeObjective;
import org.l2jmobius.gameserver.model.hotzone.HotzoneModifier;
import org.l2jmobius.gameserver.model.instancezone.Instance;

/**
 * The runtime state of one challenger's attempt. Every mutation happens while holding the session's monitor ({@code synchronized (session)}), since kills, timers, logins and dialogs arrive on different threads. Timers never keep a {@link Player}: they look the challenger up by object id.
 * @author Mobius
 */
public class ChallengeSession
{
	public enum Status
	{
		/** Objectives in progress. */
		ACTIVE,
		/** Cleared: waiting for the challenger to return (or for the automatic return). */
		COMPLETED,
		/** Failed, timed out or abandoned. */
		FAILED,
		/** Cleaned up. */
		ENDED
	}

	private static final AtomicLong TOKENS = new AtomicLong();

	private final long _token = TOKENS.incrementAndGet();
	private final int _playerObjectId;
	private final String _playerName;
	private final ChallengeDefinition _definition;
	private final int _classIndex;
	private final int _fromClassId;
	private final Instance _instance;
	private final int _instanceId;
	private final HotzoneModifier _omen;
	private final Location _returnLoc;
	private final long _startTime;
	private final long _endTime;
	private final AtomicReference<Status> _status = new AtomicReference<>(Status.ACTIVE);

	private final List<AbstractChallengeObjective> _objectives = new ArrayList<>();
	private int _currentObjective;

	private final Map<Integer, TrackedNpc> _tracked = new HashMap<>();
	private final Set<Integer> _defeated = new HashSet<>();
	private final Map<Integer, Long> _givenItems = new HashMap<>();

	private boolean _offline;
	private boolean _knockedOut;
	private int _knockouts;
	private TwistDefinition _invaderTwist;
	private boolean _invaderSpawned;
	private Npc _lastDefeatedBoss;
	private long _tickCount;
	private long _completedTime;

	private ScheduledFuture<?> _timeoutTask;
	private ScheduledFuture<?> _tickTask;
	private ScheduledFuture<?> _graceTask;
	private ScheduledFuture<?> _returnTask;

	public ChallengeSession(Player player, ChallengeDefinition definition, Instance instance, HotzoneModifier omen, Location returnLoc, int durationSeconds)
	{
		_playerObjectId = player.getObjectId();
		_playerName = player.getName();
		_definition = definition;
		_classIndex = player.getClassIndex();
		_fromClassId = player.getPlayerClass().getId();
		_instance = instance;
		_instanceId = instance.getId();
		_omen = omen;
		_returnLoc = returnLoc;
		_startTime = System.currentTimeMillis();
		_endTime = _startTime + (durationSeconds * 1000L);
	}

	/**
	 * @return a number unique to this attempt, so a late timer never acts on a newer attempt
	 */
	public long getToken()
	{
		return _token;
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
	 * @return the challenger if online, {@code null} otherwise
	 */
	public Player getPlayer()
	{
		final Player player = World.getInstance().getPlayer(_playerObjectId);
		return (player != null) && player.isOnline() ? player : null;
	}

	/**
	 * @return the challenger if online and inside this attempt's instance, {@code null} otherwise
	 */
	public Player getPlayerInside()
	{
		final Player player = getPlayer();
		return (player != null) && (player.getInstanceId() == _instanceId) ? player : null;
	}

	public ChallengeDefinition getDefinition()
	{
		return _definition;
	}

	public TransferStage getStage()
	{
		return _definition.getStage();
	}

	public int getClassIndex()
	{
		return _classIndex;
	}

	public int getFromClassId()
	{
		return _fromClassId;
	}

	public Instance getInstance()
	{
		return _instance;
	}

	public int getInstanceId()
	{
		return _instanceId;
	}

	/**
	 * @return the Hot Zone modifier rolled for this attempt, {@code null} for none
	 */
	public HotzoneModifier getOmen()
	{
		return _omen;
	}

	public Location getReturnLoc()
	{
		return _returnLoc;
	}

	public long getStartTime()
	{
		return _startTime;
	}

	public long getEndTime()
	{
		return _endTime;
	}

	/**
	 * @return seconds left before the time limit, never negative
	 */
	public int getRemainingSeconds()
	{
		return (int) Math.max(0, (_endTime - System.currentTimeMillis()) / 1000);
	}

	public Status getStatus()
	{
		return _status.get();
	}

	public boolean isActive()
	{
		return _status.get() == Status.ACTIVE;
	}

	/**
	 * Moves the status on only from {@code expected}, so completion and failure each run once even when their triggers race.
	 * @param expected the status the session must still have
	 * @param next the new status
	 * @return {@code true} if this call made the change
	 */
	public boolean changeStatus(Status expected, Status next)
	{
		return _status.compareAndSet(expected, next);
	}

	/**
	 * @return {@code true} if this call ended the session, {@code false} if it was already ended
	 */
	public boolean markEnded()
	{
		return _status.getAndSet(Status.ENDED) != Status.ENDED;
	}

	public List<AbstractChallengeObjective> getObjectives()
	{
		return _objectives;
	}

	public int getCurrentObjectiveIndex()
	{
		return _currentObjective;
	}

	public void setCurrentObjectiveIndex(int index)
	{
		_currentObjective = index;
	}

	/**
	 * @return the objective in progress, {@code null} once every objective is done
	 */
	public AbstractChallengeObjective getCurrentObjective()
	{
		return _currentObjective < _objectives.size() ? _objectives.get(_currentObjective) : null;
	}

	// ---------------------------------------------------------------------
	// Tracked NPCs
	// ---------------------------------------------------------------------

	public void track(TrackedNpc tracked)
	{
		_tracked.put(tracked.getNpc().getObjectId(), tracked);
	}

	public TrackedNpc getTracked(Npc npc)
	{
		return npc == null ? null : _tracked.get(npc.getObjectId());
	}

	/**
	 * Stops tracking {@code npc} and remembers it was defeated. Returns {@code null} if it was not (or no longer) tracked, which is how duplicate death events are dropped.
	 * @param npc the NPC
	 * @return what it was, or {@code null}
	 */
	public TrackedNpc untrackDefeated(Npc npc)
	{
		final TrackedNpc tracked = _tracked.remove(npc.getObjectId());
		if (tracked != null)
		{
			_defeated.add(npc.getObjectId());
		}
		return tracked;
	}

	/**
	 * @param objectId an NPC object id
	 * @return {@code true} if this attempt spawned that NPC and it was defeated
	 */
	public boolean wasDefeated(int objectId)
	{
		return _defeated.contains(objectId);
	}

	public TrackedNpc untrack(Npc npc)
	{
		return _tracked.remove(npc.getObjectId());
	}

	public Collection<TrackedNpc> getAllTracked()
	{
		return Collections.unmodifiableCollection(new ArrayList<>(_tracked.values()));
	}

	/**
	 * @param objectiveIndex an objective index
	 * @param role a role, {@code null} for any
	 * @return the living tracked NPCs of that objective and role
	 */
	public List<TrackedNpc> getAlive(int objectiveIndex, NpcRole role)
	{
		final List<TrackedNpc> result = new ArrayList<>();
		for (TrackedNpc tracked : _tracked.values())
		{
			if ((tracked.getObjectiveIndex() == objectiveIndex) && ((role == null) || (tracked.getRole() == role)) && !tracked.getNpc().isDead() && tracked.getNpc().isSpawned())
			{
				result.add(tracked);
			}
		}
		return result;
	}

	/**
	 * Spawns and tracks one NPC of a spawn line for an objective. See {@link ClassTransferChallengeManager#spawn}.
	 * @param holder the spawn line
	 * @param objectiveIndex the objective
	 * @param role its role
	 * @return the NPC, or {@code null} if it could not be spawned
	 */
	public Npc spawn(ChallengeSpawnHolder holder, int objectiveIndex, NpcRole role)
	{
		return ClassTransferChallengeManager.getInstance().spawn(this, holder, objectiveIndex, role);
	}

	/**
	 * Spawns and tracks a single NPC at a location for an objective.
	 * @param npcId the NPC id
	 * @param loc the location
	 * @param objectiveIndex the objective
	 * @param role its role
	 * @return the NPC, or {@code null} if it could not be spawned
	 */
	public Npc spawnAt(int npcId, Location loc, int objectiveIndex, NpcRole role)
	{
		return ClassTransferChallengeManager.getInstance().spawn(this, new ChallengeSpawnHolder(npcId, 1, loc.getX(), loc.getY(), loc.getZ(), loc.getHeading(), 0, 0, SpawnVariant.NONE, 1, false, 0), objectiveIndex, role);
	}

	/**
	 * Removes every living NPC an objective spawned.
	 * @param objectiveIndex the objective
	 */
	public void despawnObjective(int objectiveIndex)
	{
		for (TrackedNpc tracked : new ArrayList<>(_tracked.values()))
		{
			if (tracked.getObjectiveIndex() == objectiveIndex)
			{
				_tracked.remove(tracked.getNpc().getObjectId());
				tracked.getNpc().deleteMe();
			}
		}
	}

	// ---------------------------------------------------------------------
	// Player feedback and challenge items
	// ---------------------------------------------------------------------

	/**
	 * Shows a big message in the middle of the challenger's screen.
	 * @param text the message
	 */
	public void announce(String text)
	{
		ClassTransferChallengeManager.getInstance().announce(this, text);
	}

	/**
	 * Shows the current objective and its progress at the top of the challenger's screen.
	 */
	public void showProgress()
	{
		ClassTransferChallengeManager.getInstance().showProgress(this);
	}

	/**
	 * Gives the challenger challenge items and remembers them, so whatever is left is taken back when the attempt ends.
	 * @param itemId the item
	 * @param count how many
	 */
	public void giveItem(int itemId, long count)
	{
		final Player player = getPlayer();
		if ((player == null) || (itemId <= 0) || (count <= 0))
		{
			return;
		}
		ClassTransferChallengeManager.getInstance().giveChallengeItem(player, itemId, count);
		_givenItems.merge(itemId, count, Long::sum);
	}

	/**
	 * Takes back up to {@code count} of the challenge items this attempt gave.
	 * @param itemId the item
	 * @param count how many
	 * @return how many were taken
	 */
	public long takeItem(int itemId, long count)
	{
		final Player player = getPlayer();
		final long given = _givenItems.getOrDefault(itemId, 0L);
		if ((player == null) || (given <= 0) || (count <= 0))
		{
			return 0;
		}
		final long taken = ClassTransferChallengeManager.getInstance().takeChallengeItem(player, itemId, Math.min(given, count));
		if (taken > 0)
		{
			_givenItems.put(itemId, given - taken);
		}
		return taken;
	}

	/**
	 * @return item id -> how many of it this attempt gave and did not take back yet
	 */
	public Map<Integer, Long> getGivenItems()
	{
		return _givenItems;
	}

	// ---------------------------------------------------------------------
	// Flags and timers
	// ---------------------------------------------------------------------

	public boolean isOffline()
	{
		return _offline;
	}

	public void setOffline(boolean offline)
	{
		_offline = offline;
	}

	public boolean isKnockedOut()
	{
		return _knockedOut;
	}

	public void setKnockedOut(boolean knockedOut)
	{
		_knockedOut = knockedOut;
	}

	public int getKnockouts()
	{
		return _knockouts;
	}

	public void increaseKnockouts()
	{
		_knockouts++;
	}

	/**
	 * @return the invader twist rolled for this attempt, {@code null} for none
	 */
	public TwistDefinition getInvaderTwist()
	{
		return _invaderTwist;
	}

	public void setInvaderTwist(TwistDefinition twist)
	{
		_invaderTwist = twist;
	}

	public boolean isInvaderSpawned()
	{
		return _invaderSpawned;
	}

	public void setInvaderSpawned(boolean invaderSpawned)
	{
		_invaderSpawned = invaderSpawned;
	}

	/**
	 * @return the last boss or rival defeated, where the clear's Sealed Cache drops
	 */
	public Npc getLastDefeatedBoss()
	{
		return _lastDefeatedBoss;
	}

	public void setLastDefeatedBoss(Npc npc)
	{
		_lastDefeatedBoss = npc;
	}

	/**
	 * @return how many 1s ticks ran
	 */
	public long nextTick()
	{
		return ++_tickCount;
	}

	public long getCompletedTime()
	{
		return _completedTime;
	}

	public void setCompletedTime(long completedTime)
	{
		_completedTime = completedTime;
	}

	public void setTimeoutTask(ScheduledFuture<?> task)
	{
		_timeoutTask = task;
	}

	public void setTickTask(ScheduledFuture<?> task)
	{
		_tickTask = task;
	}

	public void setGraceTask(ScheduledFuture<?> task)
	{
		cancel(_graceTask);
		_graceTask = task;
	}

	public void cancelGraceTask()
	{
		cancel(_graceTask);
		_graceTask = null;
	}

	public void setReturnTask(ScheduledFuture<?> task)
	{
		cancel(_returnTask);
		_returnTask = task;
	}

	/**
	 * Cancels every timer of this attempt.
	 */
	public void cancelTasks()
	{
		cancel(_timeoutTask);
		cancel(_tickTask);
		cancel(_graceTask);
		cancel(_returnTask);
		_timeoutTask = null;
		_tickTask = null;
		_graceTask = null;
		_returnTask = null;
	}

	private static void cancel(ScheduledFuture<?> task)
	{
		if (task != null)
		{
			task.cancel(false);
		}
	}
}
