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
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.l2jmobius.gameserver.config.custom.FakeSimulationConfig;
import org.l2jmobius.gameserver.geoengine.GeoEngine;
import org.l2jmobius.gameserver.model.actor.Creature;
import org.l2jmobius.gameserver.model.actor.Npc;
import org.l2jmobius.gameserver.model.actor.holders.npc.FakePlayerParty;
import org.l2jmobius.gameserver.model.actor.holders.npc.FakePlayerPvpProfile;
import org.l2jmobius.gameserver.model.skill.Skill;

/**
 * The AI decision trace of fake players: why each one did what it did (see {@link Reason}). The AI calls {@link #trace} at each way out of its think methods; it returns at once unless the fake player is in a simulation run or watched by an admin
 * (//SimulationTrace), so roaming fake players pay nothing.<br>
 * A decision is written when it changes (reason, target or skill), and again every {@link FakeSimulationConfig#DECISION_HEARTBEAT} ms while it stays the same. The last ones are kept for //SimulationTrace.
 */
public class FakeAiTrace
{
	private static final int RECENT_LINES = 20;

	/** Fake players an admin watches with //SimulationTrace (object ids). */
	private static final Set<Integer> WATCHED = ConcurrentHashMap.newKeySet();
	/** What was last written for each traced fake player (object id). */
	private static final Map<Integer, State> STATES = new ConcurrentHashMap<>();

	private static class State
	{
		Reason reason;
		int targetId;
		int skillId;
		long written;
		final Deque<String> recent = new ArrayDeque<>();
	}

	private FakeAiTrace()
	{
	}

	/**
	 * @param npc a fake player
	 * @return {@code true} if its decisions are traced
	 */
	public static boolean isTraced(Npc npc)
	{
		final FakePlayerPvpProfile profile = npc.getTemplate().getFakePlayerPvpProfile();
		return ((profile != null) && profile.isSimulant()) || (!WATCHED.isEmpty() && WATCHED.contains(npc.getObjectId()));
	}

	/**
	 * @param npc a fake player
	 * @return {@code true} if it is watched now, {@code false} if it no longer is
	 */
	public static boolean toggleWatch(Npc npc)
	{
		if (WATCHED.remove(npc.getObjectId()))
		{
			final FakePlayerPvpProfile profile = npc.getTemplate().getFakePlayerPvpProfile();
			if ((profile == null) || !profile.isSimulant())
			{
				STATES.remove(npc.getObjectId());
			}
			return false;
		}
		WATCHED.add(npc.getObjectId());
		return true;
	}

	/**
	 * @param npc a fake player
	 * @return its last decisions, oldest first (empty if it isn't traced)
	 */
	public static List<String> getRecent(Npc npc)
	{
		final State state = STATES.get(npc.getObjectId());
		if (state == null)
		{
			return List.of();
		}
		synchronized (state)
		{
			return new ArrayList<>(state.recent);
		}
	}

	/**
	 * Forgets a fake player that left the world.
	 * @param objectId its object id
	 */
	public static void forget(int objectId)
	{
		WATCHED.remove(objectId);
		STATES.remove(objectId);
	}

	/**
	 * @see #trace(Npc, Reason, Creature, Skill, String)
	 */
	public static void trace(Npc npc, Reason reason, Creature target)
	{
		trace(npc, reason, target, null, null);
	}

	/**
	 * Notes a decision of the AI of {@code npc}.
	 * @param npc the fake player
	 * @param reason what it decided
	 * @param target on whom, {@code null} for none
	 * @param skill with which skill, {@code null} for none
	 * @param kind what kind of skill or action (ATTACK, DEBUFF, PEEL...), {@code null} for none
	 */
	public static void trace(Npc npc, Reason reason, Creature target, Skill skill, String kind)
	{
		if (!isTraced(npc))
		{
			return;
		}

		final long now = System.currentTimeMillis();
		final FakePlayerPvpProfile profile = npc.getTemplate().getFakePlayerPvpProfile();
		if (profile == null)
		{
			return;
		}

		final SimulationRun run = profile.isSimulant() ? FakeSimulationManager.getInstance().getRun(profile.getSimRunId()) : null;
		final SimulationMember member = run != null ? run.getMember(npc) : null;
		final Reason previous = member != null ? member.onDecision(reason, now) : null;

		final State state = STATES.computeIfAbsent(npc.getObjectId(), _ -> new State());
		final int targetId = target != null ? target.getObjectId() : 0;
		final int skillId = skill != null ? skill.getId() : 0;
		synchronized (state)
		{
			if ((reason == state.reason) && (targetId == state.targetId) && (skillId == state.skillId) && ((now - state.written) < FakeSimulationConfig.DECISION_HEARTBEAT))
			{
				return;
			}
			state.reason = reason;
			state.targetId = targetId;
			state.skillId = skillId;
			state.written = now;
		}

		final double gap = target != null ? npc.calculateDistance2D(target) - npc.getTemplate().getCollisionRadius() - target.getTemplate().getCollisionRadius() : Double.NaN;
		final Boolean los = target != null ? GeoEngine.getInstance().canSeeTarget(npc, target) : null;
		final long time = run != null ? run.time() : 0;

		final JsonLine line = new JsonLine();
		line.put("t", time);
		line.put("id", npc.getObjectId());
		line.put("name", npc.getName());
		if (member != null)
		{
			line.put("team", member.getTeamId());
			line.put("role", member.getRole());
		}
		line.put("build", profile.getBuild().getName());
		line.put("reason", reason);
		line.put("kind", kind);
		line.put("skill", skill != null ? skill.getName() + " (" + skill.getId() + ")" : null);
		line.put("target", target != null ? target.getName() : null);
		line.put("targetId", target != null ? targetId : null);
		line.put("gap", Double.isNaN(gap) ? null : (int) gap);
		line.put("los", los);
		line.ratio("hp", npc.getCurrentHp() / npc.getMaxHp());
		line.ratio("targetHp", target != null ? target.getCurrentHp() / Math.max(1, target.getMaxHp()) : Double.NaN);
		line.ratio("targetCp", (target != null) && target.isPlayer() ? target.getCurrentCp() / Math.max(1, target.getMaxCp()) : Double.NaN);
		line.put("moving", npc.isMoving());
		line.put("casting", npc.isCastingNow());
		line.put("intention", npc.hasAI() ? npc.getAI().getIntention() : null);
		line.put("x", npc.getX());
		line.put("y", npc.getY());
		line.put("z", npc.getZ());
		final FakePlayerParty party = profile.getParty();
		final Creature leader = party != null ? party.getLeader() : null;
		line.put("leaderDist", (leader != null) && (leader != npc) ? (int) npc.calculateDistance2D(leader) : null);
		line.put("previous", previous);

		final StringBuilder text = new StringBuilder();
		text.append(run != null ? String.format("%.1fs ", time / 1000.0) : "").append(reason);
		if (kind != null)
		{
			text.append(' ').append(kind);
		}
		if (skill != null)
		{
			text.append(' ').append(skill.getName());
		}
		if (target != null)
		{
			text.append(" -> ").append(target.getName()).append(" gap ").append((int) gap).append(Boolean.TRUE.equals(los) ? "" : " NO LOS");
		}
		synchronized (state)
		{
			state.recent.addLast(text.toString());
			while (state.recent.size() > RECENT_LINES)
			{
				state.recent.pollFirst();
			}
		}

		if (run != null)
		{
			run.getLog().write(SimulationLog.DECISIONS, line);
			if ((member != null) && (reason == Reason.FLEE) && (previous != Reason.FLEE))
			{
				run.getMonitor().onFleeStart(member);
			}
		}
	}

	/**
	 * Notes that {@code npc} called an enemy for its party to kill together.
	 * @param npc the fake player that called it
	 * @param target the enemy
	 * @param why HEALER or FINISH
	 */
	public static void onFocusCall(Npc npc, Creature target, String why)
	{
		final FakePlayerPvpProfile profile = npc.getTemplate().getFakePlayerPvpProfile();
		if ((profile == null) || !profile.isSimulant())
		{
			return;
		}

		final SimulationRun run = FakeSimulationManager.getInstance().getRun(profile.getSimRunId());
		final SimulationMember member = run != null ? run.getMember(npc) : null;
		if (member != null)
		{
			run.getLog().write(SimulationLog.EVENTS, new JsonLine().put("t", run.time()).put("type", "focus_call").put("id", member.getObjectId()).put("name", member.getName()).put("team", member.getTeamId()).put("target", target.getName()).put("targetId", target.getObjectId()).put("why", why).ratio("targetHp", target.getCurrentHp() / Math.max(1, target.getMaxHp())).put("gap", (int) npc.calculateDistance2D(target)));
		}
	}

	/**
	 * Notes that {@code npc}'s party dropped a call that didn't work (its target was healed through).
	 * @param npc the fake player that noticed
	 * @param target the enemy that was called
	 * @param startHealth its HP and CP when it was called (ratio)
	 * @param health its HP and CP now (ratio)
	 */
	public static void onCallDropped(Npc npc, Creature target, double startHealth, double health)
	{
		final FakePlayerPvpProfile profile = npc.getTemplate().getFakePlayerPvpProfile();
		if ((profile == null) || !profile.isSimulant())
		{
			return;
		}

		final SimulationRun run = FakeSimulationManager.getInstance().getRun(profile.getSimRunId());
		final SimulationMember member = run != null ? run.getMember(npc) : null;
		if (member != null)
		{
			run.getLog().write(SimulationLog.EVENTS, new JsonLine().put("t", run.time()).put("type", "call_dropped").put("id", member.getObjectId()).put("name", member.getName()).put("team", member.getTeamId()).put("target", target.getName()).put("targetId", target.getObjectId()).put("why", "HEALED_THROUGH").ratio("startHealth", startHealth).ratio("health", health));
		}
	}

	/**
	 * Notes that the AI of {@code npc} changed its attack target.
	 * @param npc the fake player
	 * @param previous its target before, {@code null} for none
	 * @param target its new target
	 */
	public static void onTargetChange(Npc npc, Creature previous, Creature target)
	{
		final FakePlayerPvpProfile profile = npc.getTemplate().getFakePlayerPvpProfile();
		if ((profile == null) || !profile.isSimulant())
		{
			return;
		}

		final SimulationRun run = FakeSimulationManager.getInstance().getRun(profile.getSimRunId());
		final SimulationMember member = run != null ? run.getMember(npc) : null;
		if (member != null)
		{
			run.getMonitor().onTargetChange(member, previous, target);
		}
	}
}
