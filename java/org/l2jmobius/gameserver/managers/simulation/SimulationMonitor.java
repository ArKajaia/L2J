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
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;

import org.l2jmobius.gameserver.ai.Intention;
import org.l2jmobius.gameserver.config.custom.FakeSimulationConfig;
import org.l2jmobius.gameserver.geoengine.GeoEngine;
import org.l2jmobius.gameserver.model.WorldObject;
import org.l2jmobius.gameserver.model.actor.Creature;
import org.l2jmobius.gameserver.model.actor.Npc;
import org.l2jmobius.gameserver.model.actor.holders.npc.FakePlayerParty;
import org.l2jmobius.gameserver.model.skill.BuffInfo;
import org.l2jmobius.gameserver.model.skill.Skill;

/**
 * Watches a simulation run: every {@link FakeSimulationConfig#POSITION_INTERVAL} ms it writes where every member is and what it does (positions.jsonl), and checks the anomaly rules on those samples and on the events; each anomaly found goes to anomalies.jsonl
 * with its time window, the members involved and their last AI decisions.
 * <ul>
 * <li>STUCK: has a target, moved less than 20 and dealt no damage for 3 s.</li>
 * <li>NO_LOS_IDLE: has a target it can't see, and stands there (not moving, casting or attacking) for 1 s.</li>
 * <li>PATH_FAIL: a path search found nothing (see {@link FakeSimulationManager#onPathFail}).</li>
 * <li>SPLIT: more than 1200 from its leader while enemies are around.</li>
 * <li>FLEE_ALONE: ran away while its team fights on.</li>
 * <li>PRESSURE_UNANSWERED: lost more than 30% of its max HP in 3 s and no teammate healed it, targeted its attackers or cast on them.</li>
 * <li>TARGET_FLIP: changed target more than 3 times in 5 s.</li>
 * <li>WASTED_CAST: cast on an invincible enemy.</li>
 * <li>IDLE_IN_FIGHT: did nothing for 2 s while an enemy close by fights its team.</li>
 * <li>HEALER_DIED_FIRST: the healer was the first of its team to die, with nobody on its attackers.</li>
 * <li>TANK_OVERCOMMIT: a tank more than 900 from its squishies (mages, archers, healers) while an enemy dives one of them.</li>
 * <li>HEALER_IGNORED: an enemy healer within 1200 of a team for 20 s without anyone of that team targeting it.</li>
 * <li>HOLD_OFF_LONG: spent 10 of the last 15 s keeping away from an enemy that is neither invincible nor under Ultimate Defense or Vengeance (worth waiting out), whatever else it did in between (totems, buffs...).</li>
 * </ul>
 * STUCK, NO_LOS_IDLE and IDLE_IN_FIGHT leave out deliberate waits (waiting out Ultimate Defense or an invincible player, a tank holding by its squishies), a fake player that was stunned, rooted or asleep meanwhile, and a healer with nobody to heal.
 */
public class SimulationMonitor
{
	public static final String STUCK = "STUCK";
	public static final String NO_LOS_IDLE = "NO_LOS_IDLE";
	public static final String PATH_FAIL = "PATH_FAIL";
	public static final String SPLIT = "SPLIT";
	public static final String FLEE_ALONE = "FLEE_ALONE";
	public static final String PRESSURE_UNANSWERED = "PRESSURE_UNANSWERED";
	public static final String TARGET_FLIP = "TARGET_FLIP";
	public static final String WASTED_CAST = "WASTED_CAST";
	public static final String IDLE_IN_FIGHT = "IDLE_IN_FIGHT";
	public static final String HEALER_DIED_FIRST = "HEALER_DIED_FIRST";
	public static final String TANK_OVERCOMMIT = "TANK_OVERCOMMIT";
	public static final String HOLD_OFF_LONG = "HOLD_OFF_LONG";
	public static final String HEALER_IGNORED = "HEALER_IGNORED";

	/** The anomalies, worst first (the order of the summary). */
	public static final List<String> SEVERITY = List.of(HEALER_DIED_FIRST, PRESSURE_UNANSWERED, HEALER_IGNORED, TANK_OVERCOMMIT, HOLD_OFF_LONG, FLEE_ALONE, STUCK, NO_LOS_IDLE, SPLIT, IDLE_IN_FIGHT, PATH_FAIL, TARGET_FLIP, WASTED_CAST);

	private static final int SPLIT_DISTANCE = 1200;
	private static final int SPLIT_ENEMY_RANGE = 1500;
	private static final int IDLE_ENEMY_RANGE = 1200;
	private static final long STUCK_TIME = 3000;
	private static final long IDLE_TIME = 2000;
	private static final long NO_LOS_TIME = 1000;
	private static final long PRESSURE_WINDOW = 3000;
	private static final double PRESSURE_RATIO = 0.3;
	private static final long FLIP_WINDOW = 5000;
	private static final int FLIP_COUNT = 3;
	private static final long RECENT_TIME = 10000;
	private static final int OVERCOMMIT_DISTANCE = 900;
	private static final long HOLD_OFF_WINDOW = 15000;
	private static final int HEALER_NEAR_RANGE = 1200;
	private static final long HEALER_IGNORED_TIME = 20000;
	private static final long HOLD_OFF_LONG_TIME = 10000;
	private static final int ULTIMATE_DEFENSE = 110;
	private static final int VENGEANCE = 368;
	/** Defensive buffs fake players keep away from (Ultimate Defense, Vengeance, Guts, Zealot, Angelic Icon, the magic mirrors). */
	private static final Set<Integer> DEFENSES = Set.of(110, 368, 139, 420, 406, 351, 916, 6282);
	private static final int DIVE_RANGE = 450;
	/** Decisions that are a deliberate wait, not a stuck or idle fake player. */
	private static final Set<Reason> WAITING = EnumSet.of(Reason.HOLD_OFF, Reason.WAIT_INVUL, Reason.GUARD_HOLD, Reason.KITE_MOVING, Reason.HEALER_DEFEND, Reason.HOLD_FOR_TANK);

	/** A heal or a cast of a member: when (ms), by whom, on whom (object ids). */
	private record Act(long time, int actorId, int targetId)
	{
	}

	private final SimulationRun _run;
	private final Deque<Act> _heals = new ArrayDeque<>();
	private final Deque<Act> _casts = new ArrayDeque<>();
	private final Map<String, Integer> _counts = new TreeMap<>();
	private final List<JsonLine> _found = new ArrayList<>();
	private final List<String> _foundTexts = new ArrayList<>();
	/** When a team last had someone on an enemy, and since when an enemy healer is near a team, and which ignored healers were written (team id &lt;&lt; 32 | object id). */
	private final Map<Long, Long> _lastTargeted = new ConcurrentHashMap<>();
	private final Map<Long, Long> _healerNearSince = new ConcurrentHashMap<>();
	private final Set<Long> _ignoredHealers = ConcurrentHashMap.newKeySet();
	/** Who led each team at the last tick (team id -> object id). */
	private final Map<Integer, Integer> _leaders = new ConcurrentHashMap<>();
	private long _lastTick;

	public SimulationMonitor(SimulationRun run)
	{
		_run = run;
	}

	/**
	 * @return how many of each anomaly were found
	 */
	public synchronized Map<String, Integer> getCounts()
	{
		return new TreeMap<>(_counts);
	}

	/**
	 * @return the anomalies found, worst kinds first, as text
	 */
	public synchronized List<String> getWorstTexts(int max)
	{
		final List<String> worst = new ArrayList<>();
		for (String type : SEVERITY)
		{
			for (String text : _foundTexts)
			{
				if (worst.size() >= max)
				{
					return worst;
				}
				if (text.contains(" " + type + " "))
				{
					worst.add(text);
				}
			}
		}
		return worst;
	}
	
	/**
	 * @return the anomalies found, in order
	 */
	public synchronized List<JsonLine> getFound()
	{
		return new ArrayList<>(_found);
	}

	// ---------------------------------------------------------------------------------------------
	// Events
	// ---------------------------------------------------------------------------------------------

	public synchronized void recordHeal(long time, Creature healer, Creature target)
	{
		_heals.addLast(new Act(time, healer.getObjectId(), target.getObjectId()));
		prune(_heals, time);
	}

	/**
	 * A member started a cast: kept for the pressure rule, and a cast on an invincible enemy is a wasted one.
	 */
	public void onCast(SimulationMember member, Creature target, Skill skill)
	{
		final long time = _run.time();
		synchronized (this)
		{
			_casts.addLast(new Act(time, member.getObjectId(), target != null ? target.getObjectId() : 0));
			prune(_casts, time);
		}

		final SimulationMember victim = _run.getMember(target);
		if ((victim != null) && (victim.getTeamId() != member.getTeamId()) && target.isInvul() && !member.isAdmin())
		{
			anomaly(WASTED_CAST, member, time, time, new JsonLine().put("skill", skill.getName() + " (" + skill.getId() + ")").put("target", target.getName()), List.of(member));
		}
	}

	/**
	 * The AI of a member picked another target.
	 */
	public void onTargetChange(SimulationMember member, Creature previous, Creature target)
	{
		final long time = _run.time();
		_run.getLog().write(SimulationLog.EVENTS, new JsonLine().put("t", time).put("type", "target_change").put("id", member.getObjectId()).put("name", member.getName()).put("team", member.getTeamId()).put("from", previous != null ? previous.getName() : null).put("to", target != null ? target.getName() : null));
		if (target == null)
		{
			return;
		}

		final int changes = member.addTargetChange(time, FLIP_WINDOW);
		if (changes > FLIP_COUNT)
		{
			member.clearTargetChanges();
			anomaly(TARGET_FLIP, member, time - FLIP_WINDOW, time, new JsonLine().put("changes", changes), List.of(member));
		}
	}

	/**
	 * A member started running away: alone, while the rest of its team fights on, it is an anomaly.
	 */
	public void onFleeStart(SimulationMember member)
	{
		final long time = _run.time();
		_run.getLog().write(SimulationLog.EVENTS, new JsonLine().put("t", time).put("type", "flee_start").put("id", member.getObjectId()).put("name", member.getName()).put("team", member.getTeamId()).ratio("hp", hpRatio(member.getCreature())));

		final SimulationTeam team = _run.getTeamOf(member);
		if (team == null)
		{
			return;
		}

		final List<SimulationMember> fighting = new ArrayList<>();
		for (SimulationMember other : team.getMembers())
		{
			if ((other != member) && team.isActive(other) && other.isAlive() && (other.getLastReason() != Reason.FLEE))
			{
				fighting.add(other);
			}
		}
		if (!fighting.isEmpty())
		{
			final List<SimulationMember> involved = new ArrayList<>(fighting);
			involved.add(0, member);
			anomaly(FLEE_ALONE, member, time, time, new JsonLine().put("teammatesFighting", fighting.size()), involved);
		}
	}

	/**
	 * A member died: the first death of a team that is its healer, with nobody on its attackers, is an anomaly.
	 */
	public void onDeath(SimulationMember member)
	{
		final SimulationTeam team = _run.getTeamOf(member);
		if ((team == null) || !team.takeFirstDeath() || (member.getRole() != SimRole.HEALER))
		{
			return;
		}

		final long time = _run.time();
		final Set<Integer> attackers = new HashSet<>();
		for (SimulationMember.Hit hit : member.getHitsSince(time - 5000))
		{
			attackers.add(hit.attackerId());
		}

		for (SimulationMember other : team.getMembers())
		{
			if ((other != member) && team.isActive(other) && other.isAlive() && isTargeting(other.getCreature(), attackers))
			{
				return;
			}
		}
		anomaly(HEALER_DIED_FIRST, member, time - 5000, time, new JsonLine().put("attackers", names(attackers)), List.of(member));
	}

	/**
	 * A path search of a member found nothing.
	 */
	public void onPathFail(SimulationMember member, int x, int y, int z, boolean fallback)
	{
		final long time = _run.time();
		if (member.onPathFailure(time))
		{
			anomaly(PATH_FAIL, member, time, time, new JsonLine().put("from", List.of(member.getCreature().getX(), member.getCreature().getY(), member.getCreature().getZ())).put("to", List.of(x, y, z)).put("fallbackRoute", fallback), List.of(member));
		}
	}

	// ---------------------------------------------------------------------------------------------
	// Every tick
	// ---------------------------------------------------------------------------------------------

	/**
	 * Writes the positions and checks the rules on them.
	 * @param fighting {@code true} once the teams fight (the rules wait until then)
	 */
	public void tick(boolean fighting)
	{
		final long time = _run.time();
		final long interval = _lastTick > 0 ? time - _lastTick : 0;
		_lastTick = time;

		for (SimulationTeam team : _run.getTeams())
		{
			for (SimulationMember member : team.getMembers())
			{
				if (!team.isActive(member))
				{
					continue;
				}

				try
				{
					sample(team, member, time, interval, fighting);
				}
				catch (Exception e)
				{
					_run.getLog().write(SimulationLog.EVENTS, new JsonLine().put("t", time).put("type", "monitor_error").put("id", member.getObjectId()).put("error", e.toString()));
				}
			}
		}

		// Who leads each team right now (a leader stunned or casting something long hands over).
		for (SimulationTeam team : _run.getTeams())
		{
			final FakePlayerParty party = team.getParty();
			final Creature leader = party != null ? party.getActiveLeader() : null;
			final int leaderId = leader != null ? leader.getObjectId() : 0;
			final Integer previous = _leaders.put(team.getId(), leaderId);
			if ((previous != null) && (previous != leaderId))
			{
				final Creature main = party.getLeader();
				_run.getLog().write(SimulationLog.EVENTS, new JsonLine().put("t", time).put("type", "leader_change").put("team", team.getId()).put("leader", leader != null ? leader.getName() : null).put("mainLeader", main != null ? main.getName() : null).put("mainLeaderCasting", (main != null) && main.isCastingNow() && (main.getLastSkillCast() != null) ? main.getLastSkillCast().getName() : null).put("mainLeaderDisabled", (main != null) && main.isMovementDisabled()));
			}
		}

		if (fighting)
		{
			checkPressure(time);
			checkIgnoredHealers(time);
		}
	}

	private void sample(SimulationTeam team, SimulationMember member, long time, long interval, boolean fighting)
	{
		final Creature creature = member.getCreature();
		final boolean alive = member.isAlive();
		final WorldObject targetObject = creature.getTarget();
		final Creature target = (targetObject instanceof Creature) && (targetObject != creature) ? (Creature) targetObject : null;
		final boolean moving = creature.isMoving();
		final boolean casting = creature.isCastingNow();
		final boolean attacking = creature.isAttackingNow();
		final Intention intention = creature.hasAI() ? creature.getAI().getIntention() : null;
		final Boolean los = (target != null) && alive ? GeoEngine.getInstance().canSeeTarget(creature, target) : null;
		final FakePlayerParty party = team.getParty();
		final Creature leader = party != null ? party.getActiveLeader() : null;
		final double leaderDistance = (leader != null) && (leader != creature) ? creature.calculateDistance2D(leader) : 0;

		final boolean disabled = creature.isMovementDisabled() || creature.isAllSkillsDisabled(); // Stunned, rooted, asleep, paralyzed: its AI can't act.
		member.addSample(new SimulationMember.Sample(time, creature.getX(), creature.getY(), creature.getZ(), moving, casting, attacking, disabled, member.getDamageDealt()), alive, interval);

		final JsonLine line = new JsonLine();
		line.put("t", time);
		line.put("id", member.getObjectId());
		line.put("name", member.getName());
		line.put("team", member.getTeamId());
		line.put("alive", alive);
		line.put("x", creature.getX());
		line.put("y", creature.getY());
		line.put("z", creature.getZ());
		line.put("heading", creature.getHeading());
		line.put("hp", (int) creature.getCurrentHp());
		line.put("maxHp", creature.getMaxHp());
		line.put("cp", (int) creature.getCurrentCp());
		line.put("mp", (int) creature.getCurrentMp());
		line.put("target", target != null ? target.getName() : null);
		line.put("targetId", target != null ? target.getObjectId() : null);
		line.put("gap", target != null ? (int) (creature.calculateDistance2D(target) - creature.getTemplate().getCollisionRadius() - target.getTemplate().getCollisionRadius()) : null);
		line.put("los", los);
		line.put("intention", intention);
		line.put("moving", moving);
		line.put("dest", moving ? List.of(creature.getXdestination(), creature.getYdestination(), creature.getZdestination()) : null);
		line.put("onPath", moving && creature.isOnGeodataPath());
		line.put("casting", casting ? (creature.getLastSkillCast() != null ? creature.getLastSkillCast().getName() : "?") : null);
		line.put("attacking", attacking);
		line.put("rooted", creature.isMovementDisabled());
		line.put("disabled", disabled);
		line.put("leaderDist", leader != null ? (int) leaderDistance : null);
		line.put("decision", member.getLastReason());
		_run.getLog().write(SimulationLog.POSITIONS, line);

		// The admin: only its target changes are noted (a fake player's come from its AI).
		final int targetId = target != null ? target.getObjectId() : 0;
		if (alive && (target != null))
		{
			_lastTargeted.put(((long) member.getTeamId() << 32) | (targetId & 0xFFFFFFFFL), time);
		}
		final int previousTarget = member.swapTarget(targetId);
		if (member.isAdmin() && (previousTarget != targetId))
		{
			_run.getLog().write(SimulationLog.EVENTS, new JsonLine().put("t", time).put("type", "target_change").put("id", member.getObjectId()).put("name", member.getName()).put("team", member.getTeamId()).put("to", target != null ? target.getName() : null).put("admin", true));
		}
		final String intentionName = intention != null ? intention.name() : null;
		final String previousIntention = member.swapIntention(intentionName);
		if (!member.isAdmin() && (previousIntention != null) && !previousIntention.equals(intentionName))
		{
			_run.getLog().write(SimulationLog.EVENTS, new JsonLine().put("t", time).put("type", "intention_change").put("id", member.getObjectId()).put("name", member.getName()).put("from", previousIntention).put("to", intentionName));
		}

		if (!fighting || !alive || member.isAdmin())
		{
			member.episode(STUCK, false);
			member.episode(NO_LOS_IDLE, false);
			member.episode(SPLIT, false);
			member.episode(IDLE_IN_FIGHT, false);
			return;
		}

		final boolean attackingIntention = intention == Intention.ATTACK;
		final boolean liveTarget = (target != null) && !target.isDead();

		// STUCK: a target, but no way forward and no damage for 3 s.
		final SimulationMember.Sample[] stuckWindow = member.getSamplesSince(time - STUCK_TIME);
		boolean stuck = false;
		// A deliberate wait anywhere in the window (holding off, waiting out Ultimate Defense...) is no stuck nor idle fake player.
		if (WAITING.contains(member.getLastReason()))
		{
			member.setLastWait(time);
		}
		final boolean waiting = (time - member.getLastWait()) <= STUCK_TIME;
		if (attackingIntention && liveTarget && !waiting && !creature.isMovementDisabled() && (stuckWindow.length >= 5) && ((time - stuckWindow[0].time()) >= (STUCK_TIME - 600)) && !hasCastSince(member.getObjectId(), time - STUCK_TIME))
		{
			double moved = 0;
			boolean busy = false;
			for (int i = 1; i < stuckWindow.length; i++)
			{
				moved += Math.hypot(stuckWindow[i].x() - stuckWindow[i - 1].x(), stuckWindow[i].y() - stuckWindow[i - 1].y());
				busy |= stuckWindow[i].casting() || stuckWindow[i].attacking() || stuckWindow[i].disabled(); // A slow bow swings less often than the window; a stunned one can't help it.
			}
			stuck = (moved < 20) && !busy && (stuckWindow[stuckWindow.length - 1].damageDealt() <= stuckWindow[0].damageDealt());
		}
		if (member.episode(STUCK, stuck))
		{
			anomaly(STUCK, member, time - STUCK_TIME, time, new JsonLine().put("target", target.getName()).put("gap", (int) creature.calculateDistance2D(target)).put("los", los), List.of(member));
		}

		// NO_LOS_IDLE: in a fight with a target it can't see, standing there.
		final SimulationMember.Sample[] noLosWindow = member.getSamplesSince(time - NO_LOS_TIME);
		boolean noLos = attackingIntention && liveTarget && Boolean.FALSE.equals(los) && (noLosWindow.length >= 2);
		for (SimulationMember.Sample sample : noLosWindow)
		{
			noLos &= !sample.moving() && !sample.casting() && !sample.attacking() && !sample.disabled();
		}
		if (member.episode(NO_LOS_IDLE, noLos))
		{
			anomaly(NO_LOS_IDLE, member, time - NO_LOS_TIME, time, new JsonLine().put("target", target.getName()).put("gap", (int) creature.calculateDistance2D(target)), List.of(member));
		}

		// SPLIT: far from its leader while enemies are around.
		boolean split = false;
		if ((leader != null) && (leader != creature) && !leader.isDead() && (leaderDistance > SPLIT_DISTANCE))
		{
			for (SimulationMember enemy : _run.getEnemiesOf(member))
			{
				if ((enemy.getCreature().calculateDistance2D(creature) < SPLIT_ENEMY_RANGE) || (enemy.getCreature().calculateDistance2D(leader) < SPLIT_ENEMY_RANGE))
				{
					split = true;
					break;
				}
			}
		}
		if (member.episode(SPLIT, split))
		{
			anomaly(SPLIT, member, time, time, new JsonLine().put("leader", leader.getName()).put("leaderDist", (int) leaderDistance), List.of(member));
		}

		// IDLE_IN_FIGHT: doing nothing while an enemy close by fights its team.
		final SimulationMember.Sample[] idleWindow = member.getSamplesSince(time - IDLE_TIME);
		boolean idle = !waiting && (idleWindow.length >= 4) && ((time - idleWindow[0].time()) >= (IDLE_TIME - 600)) && !hasCastSince(member.getObjectId(), time - IDLE_TIME) && (idleWindow[idleWindow.length - 1].damageDealt() <= idleWindow[0].damageDealt());
		for (SimulationMember.Sample sample : idleWindow)
		{
			idle &= !sample.moving() && !sample.casting() && !sample.attacking() && !sample.disabled();
		}
		SimulationMember busyEnemy = null;
		if (idle)
		{
			for (SimulationMember enemy : _run.getEnemiesOf(member))
			{
				final WorldObject enemyTarget = enemy.getCreature().getTarget();
				final SimulationMember victim = enemyTarget instanceof Creature ? _run.getMember((Creature) enemyTarget) : null;
				// A healer with nobody hurt has nothing to do.
				if ((victim != null) && (victim.getTeamId() == member.getTeamId()) && (enemy.getCreature().calculateDistance2D(creature) < IDLE_ENEMY_RANGE) && ((member.getRole() != SimRole.HEALER) || (hpRatio(victim.getCreature()) < 0.8)))
				{
					busyEnemy = enemy;
					break;
				}
			}
		}
		if (member.episode(IDLE_IN_FIGHT, busyEnemy != null))
		{
			anomaly(IDLE_IN_FIGHT, member, time - IDLE_TIME, time, new JsonLine().put("enemy", busyEnemy.getName()).put("enemyTarget", busyEnemy.getCreature().getTarget() != null ? busyEnemy.getCreature().getTarget().getName() : null).put("intention", intention), List.of(member, busyEnemy));
		}

		// HOLD_OFF_LONG: most of the last moments spent keeping away from an enemy that isn't worth waiting out (not invincible, no Ultimate Defense or Vengeance).
		// Waiting out Ultimate Defense, Vengeance or an invincible player is right: that time doesn't count.
		final boolean worthWaiting = liveTarget && (target.isInvul() || target.isAffectedBySkill(ULTIMATE_DEFENSE) || target.isAffectedBySkill(VENGEANCE));
		if (worthWaiting)
		{
			member.resetWindow(HOLD_OFF_LONG);
		}
		final long heldOff = worthWaiting ? 0 : member.windowDelta(HOLD_OFF_LONG, time, member.getReasonTime().getOrDefault(Reason.HOLD_OFF, 0L), HOLD_OFF_WINDOW);
		final boolean holding = liveTarget && (heldOff >= HOLD_OFF_LONG_TIME) && !target.isInvul() && !target.isAffectedBySkill(ULTIMATE_DEFENSE) && !target.isAffectedBySkill(VENGEANCE);
		if (member.episode(HOLD_OFF_LONG, holding))
		{
			anomaly(HOLD_OFF_LONG, member, time - HOLD_OFF_WINDOW, time, new JsonLine().put("heldOffMs", heldOff).put("target", target.getName()).put("gap", (int) creature.calculateDistance2D(target)).ratio("targetHp", target.getCurrentHp() / Math.max(1, target.getMaxHp())).put("targetBuffs", getDefenses(target)), List.of(member));
		}

		// TANK_OVERCOMMIT: a tank far from its squishies while an enemy dives one of them.
		if (member.getRole() == SimRole.TANK)
		{
			checkOvercommit(team, member, time, target);
		}
	}

	private void checkOvercommit(SimulationTeam team, SimulationMember tank, long time, Creature tankTarget)
	{
		final Creature creature = tank.getCreature();
		long x = 0;
		long y = 0;
		int count = 0;
		final List<SimulationMember> squishies = new ArrayList<>();
		for (SimulationMember other : team.getMembers())
		{
			if ((other != tank) && team.isActive(other) && other.isAlive() && (other.getRole().isRanged() || (other.getRole() == SimRole.HEALER)))
			{
				squishies.add(other);
				x += other.getCreature().getX();
				y += other.getCreature().getY();
				count++;
			}
		}

		SimulationMember diver = null;
		SimulationMember victim = null;
		double backlineDistance = 0;
		if (count > 0)
		{
			backlineDistance = Math.hypot(creature.getX() - (x / (double) count), creature.getY() - (y / (double) count));
			if (backlineDistance > OVERCOMMIT_DISTANCE)
			{
				for (SimulationMember enemy : _run.getEnemiesOf(tank))
				{
					final WorldObject enemyTarget = enemy.getCreature().getTarget();
					for (SimulationMember squishy : squishies)
					{
						if ((enemyTarget == squishy.getCreature()) && (enemy.getCreature().calculateDistance2D(squishy.getCreature()) < DIVE_RANGE))
						{
							diver = enemy;
							victim = squishy;
							break;
						}
					}
					if (diver != null)
					{
						break;
					}
				}
			}
		}

		if (tank.episode(TANK_OVERCOMMIT, diver != null))
		{
			anomaly(TANK_OVERCOMMIT, tank, time, time, new JsonLine().put("backlineDist", (int) backlineDistance).put("diver", diver.getName()).put("victim", victim.getName()).put("tankTarget", tankTarget != null ? tankTarget.getName() : null), List.of(tank, diver, victim));
		}
	}

	/**
	 * HEALER_IGNORED: an enemy healer near a team for long, and nobody of that team went for it.
	 */
	private void checkIgnoredHealers(long time)
	{
		for (SimulationTeam team : _run.getTeams())
		{
			if (!team.isAlive())
			{
				continue;
			}

			for (SimulationTeam enemyTeam : _run.getTeams())
			{
				if (enemyTeam == team)
				{
					continue;
				}

				for (SimulationMember healer : enemyTeam.getMembers())
				{
					if ((healer.getRole() != SimRole.HEALER) || !enemyTeam.isActive(healer))
					{
						continue;
					}

					final long key = ((long) team.getId() << 32) | (healer.getObjectId() & 0xFFFFFFFFL);
					boolean near = false;
					if (healer.isAlive())
					{
						for (SimulationMember member : team.getMembers())
						{
							if (team.isActive(member) && member.isAlive() && (member.getCreature().calculateDistance2D(healer.getCreature()) <= HEALER_NEAR_RANGE))
							{
								near = true;
								break;
							}
						}
					}
					if (!near)
					{
						_healerNearSince.remove(key);
						_ignoredHealers.remove(key);
						continue;
					}

					final long nearSince = _healerNearSince.computeIfAbsent(key, _ -> time);
					final long lastTargeted = Math.max(nearSince, _lastTargeted.getOrDefault(key, Long.MIN_VALUE));
					if ((time - lastTargeted) < HEALER_IGNORED_TIME)
					{
						_ignoredHealers.remove(key);
						continue;
					}

					if (_ignoredHealers.add(key))
					{
						anomaly(HEALER_IGNORED, healer, lastTargeted, time, new JsonLine().put("ignoredBy", team.getId()).put("seconds", (time - lastTargeted) / 1000).put("healDone", (long) healer.getHealDone()), List.of(healer));
					}
				}
			}
		}
	}

	/**
	 * PRESSURE_UNANSWERED: a member that lost a lot of HP in a moment, and no teammate healed it, targeted its attackers or cast on them.
	 */
	private void checkPressure(long time)
	{
		for (SimulationTeam team : _run.getTeams())
		{
			for (SimulationMember member : team.getMembers())
			{
				if (!team.isActive(member) || !member.isAlive())
				{
					continue;
				}

				double damage = 0;
				final Set<Integer> attackers = new HashSet<>();
				for (SimulationMember.Hit hit : member.getHitsSince(time - PRESSURE_WINDOW))
				{
					damage += hit.amount();
					attackers.add(hit.attackerId());
				}
				if (damage < (member.getCreature().getMaxHp() * PRESSURE_RATIO))
				{
					continue;
				}

				if (isAnswered(team, member, attackers, time - PRESSURE_WINDOW) || !member.takePressureTurn(time, 5000))
				{
					continue;
				}

				anomaly(PRESSURE_UNANSWERED, member, time - PRESSURE_WINDOW, time, new JsonLine().put("damage", (int) damage).ratio("ofMaxHp", damage / member.getCreature().getMaxHp()).put("attackers", names(attackers)), List.of(member));
			}
		}
	}

	private boolean isAnswered(SimulationTeam team, SimulationMember member, Set<Integer> attackers, long since)
	{
		final Set<Integer> teammates = new HashSet<>();
		for (SimulationMember other : team.getMembers())
		{
			if ((other == member) || !team.isActive(other) || !other.isAlive())
			{
				continue;
			}

			teammates.add(other.getObjectId());
			if (isTargeting(other.getCreature(), attackers))
			{
				return true;
			}
		}

		synchronized (this)
		{
			for (Act heal : _heals)
			{
				if ((heal.time() >= since) && (heal.targetId() == member.getObjectId()) && teammates.contains(heal.actorId()))
				{
					return true;
				}
			}
			for (Act cast : _casts)
			{
				if ((cast.time() >= since) && attackers.contains(cast.targetId()) && teammates.contains(cast.actorId()))
				{
					return true;
				}
			}
		}
		return false;
	}

	// ---------------------------------------------------------------------------------------------
	// Writing
	// ---------------------------------------------------------------------------------------------

	/**
	 * Writes an anomaly with its time window, the members involved and their last decisions.
	 * @param type the anomaly
	 * @param member the member it is about
	 * @param from the start of its time window (ms since the run started)
	 * @param to the end of its time window
	 * @param details what the rule saw
	 * @param involved the members involved, {@code member} first
	 */
	private void anomaly(String type, SimulationMember member, long from, long to, JsonLine details, List<SimulationMember> involved)
	{
		member.countAnomaly(type);

		final List<JsonLine> members = new ArrayList<>();
		for (SimulationMember other : involved)
		{
			final JsonLine entry = new JsonLine().put("id", other.getObjectId()).put("name", other.getName()).put("team", other.getTeamId()).put("role", other.getRole()).put("build", other.getBuild());
			if (other.getCreature() instanceof Npc npc)
			{
				final List<String> recent = FakeAiTrace.getRecent(npc);
				entry.put("lastDecisions", recent.subList(Math.max(0, recent.size() - 8), recent.size()));
			}
			members.add(entry);
		}

		final JsonLine line = new JsonLine().put("t", to).put("type", type).put("id", member.getObjectId()).put("name", member.getName()).put("team", member.getTeamId()).put("role", member.getRole()).put("build", member.getBuild()).put("from", from).put("to", to).put("details", details).put("members", members);
		synchronized (this)
		{
			_counts.merge(type, 1, Integer::sum);
			_found.add(line);
			_foundTexts.add(String.format("%.1fs", to / 1000.0) + " " + type + " " + member.getName() + " (team " + member.getTeamId() + ", " + member.getBuild() + "): " + details);
		}
		_run.getLog().write(SimulationLog.ANOMALIES, line);
	}

	/**
	 * @return {@code true} if the member started a cast since then
	 */
	private synchronized boolean hasCastSince(int actorId, long since)
	{
		for (Act cast : _casts)
		{
			if ((cast.actorId() == actorId) && (cast.time() >= since))
			{
				return true;
			}
		}
		return false;
	}

	private boolean isTargeting(Creature creature, Set<Integer> targets)
	{
		final WorldObject target = creature.getTarget();
		return (target != null) && targets.contains(target.getObjectId());
	}

	private List<String> names(Set<Integer> objectIds)
	{
		final List<String> names = new ArrayList<>();
		for (int objectId : objectIds)
		{
			final SimulationMember other = _run.getMembers().get(objectId);
			names.add(other != null ? other.getName() : String.valueOf(objectId));
		}
		return names;
	}

	/**
	 * @return the defensive buffs fake players play around that {@code creature} has on, as "name (seconds left)"
	 */
	private static List<String> getDefenses(Creature creature)
	{
		final List<String> names = new ArrayList<>();
		for (BuffInfo info : creature.getEffectList().getBuffs())
		{
			if (DEFENSES.contains(info.getSkill().getId()))
			{
				names.add(info.getSkill().getName() + " (" + info.getTime() + " s)");
			}
		}
		return names;
	}
	
	private static double hpRatio(Creature creature)
	{
		return creature.getCurrentHp() / Math.max(1, creature.getMaxHp());
	}

	private static void prune(Deque<Act> acts, long time)
	{
		while (!acts.isEmpty() && ((time - acts.peekFirst().time()) > RECENT_TIME))
		{
			acts.pollFirst();
		}
	}
}
