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
package org.l2jmobius.gameserver.ai;

import static org.l2jmobius.gameserver.ai.FakePlayerPvpAI.FOCUS_SCAN_RANGE;
import static org.l2jmobius.gameserver.ai.FakePlayerPvpAI.HEALER_DIVE_RANGE;
import static org.l2jmobius.gameserver.ai.FakePlayerPvpAI.MAX_HUNT_LEVEL_DIFFERENCE;
import static org.l2jmobius.gameserver.ai.FakePlayerPvpAI.getHealth;
import static org.l2jmobius.gameserver.ai.FakePlayerPvpAI.getThreatReach;
import static org.l2jmobius.gameserver.ai.FakePlayerPvpAI.hates;
import static org.l2jmobius.gameserver.ai.FakePlayerPvpAI.isDisabled;
import static org.l2jmobius.gameserver.ai.FakePlayerPvpAI.isEnemyAround;
import static org.l2jmobius.gameserver.ai.FakePlayerPvpAI.isHealer;
import static org.l2jmobius.gameserver.ai.FakePlayerPvpAI.isPureHealer;
import static org.l2jmobius.gameserver.ai.FakePlayerPvpAI.isPvpEnemy;
import static org.l2jmobius.gameserver.ai.FakePlayerPvpAI.isSquishy;
import static org.l2jmobius.gameserver.ai.FakePlayerPvpAI.isTank;
import static org.l2jmobius.gameserver.ai.FakePlayerPvpAI.isValidTarget;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import org.l2jmobius.gameserver.config.custom.FakePartyConfig;
import org.l2jmobius.gameserver.geoengine.GeoEngine;
import org.l2jmobius.gameserver.managers.FakePartyManager;
import org.l2jmobius.gameserver.managers.FakePlayerPvpManager;
import org.l2jmobius.gameserver.managers.TeamVsTeamManager;
import org.l2jmobius.gameserver.managers.simulation.FakeAiTrace;
import org.l2jmobius.gameserver.managers.simulation.FakeSimulationManager;
import org.l2jmobius.gameserver.managers.simulation.SimulationRun;
import org.l2jmobius.gameserver.model.Location;
import org.l2jmobius.gameserver.model.World;
import org.l2jmobius.gameserver.model.WorldObject;
import org.l2jmobius.gameserver.model.actor.Attackable;
import org.l2jmobius.gameserver.model.actor.Creature;
import org.l2jmobius.gameserver.model.actor.Npc;
import org.l2jmobius.gameserver.model.actor.holders.npc.AggroInfo;
import org.l2jmobius.gameserver.model.actor.holders.npc.FakePlayerParty;
import org.l2jmobius.gameserver.model.actor.holders.npc.FakePlayerPvpBuild.Role;
import org.l2jmobius.gameserver.model.actor.holders.npc.FakePlayerPvpBuild.SkillCategory;
import org.l2jmobius.gameserver.model.actor.holders.npc.FakePlayerPvpProfile;
import org.l2jmobius.gameserver.model.actor.instance.Monster;
import org.l2jmobius.gameserver.model.skill.Skill;

/**
 * The party tier of the fake players' AI: one per party, shared by its members (see {@link FakePlayerParty#getBrain}). It makes the party's general decisions, and each member's own AI ({@link FakePlayerPvpAI}, the player tier) carries them out with its
 * micro (skills, combos, kiting, peels, weapons):
 * <ul>
 * <li>Out of a fight: what the party wants of a member ({@link #orderFor}): help with what the party fights, follow the leader, a healer holding where it heals from, or nothing (it leads, or plays alone).</li>
 * <li>Where the party goes: the march of a simulation team toward the other team ({@link #getMarchTarget}).</li>
 * <li>Who to focus: the party's call (an enemy healer, an enemy about to fall, the healer that would heal it back), and dropping a call that doesn't bring its target down. Its share of every member's target score: focus fire, enemies on our healer and
 * squishies, the damage an enemy dealt to the party.</li>
 * <li>The party's picture of the fight: who the tanks and supports guard, who dives our squishies, who is on our healers, whether anyone fights the party, and where the party is (its anchor: the closest tank, otherwise its middle).</li>
 * </ul>
 * Many of these depend on who asks (the tank closest to it, the members within its reach), so every method takes the asking member, and works it out when asked. The party's call is kept by {@link FakePlayerParty}.
 * <p>
 * It also thinks on its own ({@link #update}, at most every {@link #UPDATE_INTERVAL} ms, run by whichever member thinks first): the party's <b>stance</b> ({@link Stance}) and the picture that goes with it. While it is engaged, every member fights, not only
 * the ones that saw the enemy: a member that sees no enemy yet joins the fight of the member closest to the party ({@link #getEngageTarget}). And its healer takes cover ({@link #getCover}, #30):
 * as far back behind the front as its heals still reach the members that fight. An enemy archer or mage that shoots it then has to come deep into the party, which answers it (#31): the tanks go for it ({@link #getShooterOnHealer}), and the
 * archers and mages stop it from where they stand. The party isn't called on it: a call on the shooter took the damage dealers off the enemy healer and didn't save its own (see docs/fake-player-simulation-issues.md, #31).
 */
public class FakePartyBrain
{
	/** An enemy this low is called for the party to finish. */
	private static final double FINISH_CALL_HEALTH = 0.3;
	/** An enemy healer this close to a low enemy can heal it back up: it is called instead. */
	private static final int ENEMY_HEAL_REACH = 900;
	/** An enemy called for the party (see {@link FakePlayerParty#getFocus}) stays called this long, and calling it again extends it. */
	private static final long PARTY_FOCUS_TIME = 10000;
	/** A call gets this long to bring its target down by this much of its HP and CP; otherwise it is dropped, and that target isn't called again (and is worth less) for this long. */
	private static final long CALL_PROGRESS_TIME = 8000;
	private static final double CALL_MIN_LOSS = 0.2;
	private static final long CALL_DROP_TIME = 10000;
	private static final double DROPPED_CALL_PENALTY = 0.5;
	/** How much more a target is worth for each party member already on it (focus fire), up to {@link #FOCUS_FIRE_MAX}. */
	private static final double FOCUS_FIRE_BONUS = 0.2;
	private static final double FOCUS_FIRE_MAX = 0.6;
	/** The enemy that hurt the party most is worth up to this much more; it gets all of it once it dealt this many times its own max HP to the party. */
	private static final double THREAT_MAX = 0.4;
	private static final double THREAT_HP_SCALE = 0.75; // #7: it was 2, which only counted once a fight was decided.
	/** #4: an enemy on the party's healer, or on another squishy of it, is worth this much more (answered at any range). */
	private static final double PRESSURE_ON_HEALER = 0.35;
	private static final double PRESSURE_ON_SQUISHY = 0.15;

	/** An enemy this close to a squishy party member and targeting it is diving it. */
	private static final int GUARD_THREAT_RANGE = 450;
	/** Party members farther than this are not guarded (they ran off). */
	private static final int GUARD_SCAN_RANGE = 1500;
	/** A diver a tank is on still counts as one this far from the squishy it targets. */
	private static final double DIVER_KEEP_FACTOR = 1.5;
	/** How much more a diver on a healer counts, and how much less an archer or mage that dives (it only shoots from there). */
	private static final double DIVER_HEALER_BONUS = 450;
	private static final double DIVER_RANGED_MALUS = 300;
	/** A melee damage dealer goes in with its tank once the tank is this close to the enemy. */
	private static final int MELEE_TANK_LEAD = 400;
	/** #23: a healer doesn't follow its leader while enemies are this close and its leader this close (its heals reach). */
	private static final int HEALER_FIGHT_RANGE = 1200;
	private static final int HEALER_FOLLOW_REACH = 550;
	/** An archer or mage looks after party healers this close. */
	private static final int ALLY_PEEL_SCAN = 1200;
	/** With {@link FakePartyConfig#HUNT_WHEN_IDLE}, the party pulls the monsters this close to its idle leader. */
	private static final int IDLE_HUNT_RANGE = 700;

	/** How often the party thinks, at most. */
	private static final long UPDATE_INTERVAL = 250;
	/** The party stays engaged this long after its last member stopped fighting (between two enemies, a target dying...). */
	private static final long ENGAGE_LINGER = 3000;
	/** #30: the enemies this close to the party's front are weighed for its healer's cover. */
	private static final int COVER_SCAN_RANGE = 2000;
	/** #30: from its cover, its healer's heals reach this share of their range. */
	private static final double COVER_HEAL_SHARE = 0.9;
	/** #30: the cover is at least this far behind the front, and is looked for in steps of this much. */
	private static final int COVER_MIN = 150;
	private static final int COVER_STEP = 50;
	/** #30: members more than this ahead of the front (deep in the enemy) don't hold its healer within their heal reach. */
	private static final int COVER_AHEAD = 200;
	/** #30: a healer whose heals reach nothing farther than this (no party heal skill with a range). */
	private static final int COVER_DEFAULT_HEAL_RANGE = 600;
	/** #31: an enemy archer or mage this close to the party's healer, targeting it and within its reach (plus this margin), is shooting it. */
	private static final int SHOOTER_SCAN_RANGE = 1500;
	private static final int SHOOTER_MARGIN = 100;

	/**
	 * What the party as a whole is doing.
	 */
	public enum Stance
	{
		/** A simulation team getting ready (buffs, servitors). */
		PREP,
		/** No enemy: it goes where its leader takes it (a simulation team toward the other team). */
		MARCH,
		/** A member fights an enemy: they all do. */
		ENGAGE
	}

	/**
	 * What the party brain worked out on its last think.
	 * @param stance what the party does
	 * @param engageTarget while engaged, the enemy fought by the member closest to the middle of the party ({@code null} between two)
	 * @param anchor the middle of its living members
	 * @param cover while engaged, where its healer stands (#30), {@code null} if nowhere in particular
	 * @param shooter while engaged, the enemy archer or mage shooting its healer (#31), {@code null} if none
	 */
	public record Picture(Stance stance, Creature engageTarget, Location anchor, Location cover, Creature shooter)
	{
	}

	/**
	 * What the party wants of a member out of a fight.
	 */
	public enum OrderKind
	{
		/** No leader, or it leads: it goes its own way (a simulation leader marches). */
		LEAD,
		/** It helps with what the party fights: the target. */
		ASSIST,
		/** #23: a healer in a fight stays where its heals still reach its leader. */
		HEALER_HOLD,
		/** It is far from its leader (it teleported): FakePartyManager brings it there a while later. */
		WAIT_FAR,
		/** It is far from the leader of a party of fake players only: it leaves, and goes its own way. */
		LEAVE_PARTY,
		/** It runs after its leader. */
		FOLLOW_RUN,
		/** It walks after its leader. */
		FOLLOW_WALK,
		/** It is by its leader: it rests or idles with the party. */
		STAY
	}

	/**
	 * @param kind what the party wants
	 * @param target the enemy to help with ({@link OrderKind#ASSIST}), otherwise the leader
	 */
	public record PartyOrder(OrderKind kind, Creature target)
	{
	}

	/**
	 * @param members the party members a tank or support guards
	 * @param anchor their middle
	 */
	public record Guard(List<Creature> members, Location anchor)
	{
	}

	/**
	 * @param enemy the enemy diving a guarded squishy, {@code null} if none
	 * @param score how urgent it is to peel it (see {@link FakePartyBrain#getDiverScore}), {@link Double#MAX_VALUE} without one
	 */
	public record Diver(Creature enemy, double score)
	{
	}

	private final FakePlayerParty _party;
	/** When it may think again. */
	private final AtomicLong _nextUpdate = new AtomicLong();
	private volatile Picture _picture = new Picture(Stance.MARCH, null, null, null, null);
	/** When a member last fought. */
	private volatile long _lastEngaged;

	public FakePartyBrain(FakePlayerParty party)
	{
		_party = party;
	}

	public FakePlayerParty getParty()
	{
		return _party;
	}

	// ---------------------------------------------------------------------------------------------
	// The party's own think: its stance
	// ---------------------------------------------------------------------------------------------

	/**
	 * The party thinks, at most every {@link #UPDATE_INTERVAL} ms: whichever member thinks first does it for all.
	 * @param npc the member thinking now
	 * @param profile its profile
	 * @param now the current time
	 */
	public void update(Attackable npc, FakePlayerPvpProfile profile, long now)
	{
		final long next = _nextUpdate.get();
		if ((now < next) || !_nextUpdate.compareAndSet(next, now + UPDATE_INTERVAL))
		{
			return;
		}

		// Its living members, and their middle.
		final List<Creature> members = new ArrayList<>();
		long x = 0;
		long y = 0;
		long z = 0;
		for (Creature member : _party.getMembers())
		{
			if (member.isDead() || !member.isSpawned() || (member.getInstanceId() != npc.getInstanceId()))
			{
				continue;
			}
			members.add(member);
			x += member.getX();
			y += member.getY();
			z += member.getZ();
		}
		final Location anchor = members.isEmpty() ? null : new Location((int) (x / members.size()), (int) (y / members.size()), (int) (z / members.size()));

		// Engaged: a member fights an enemy. The fight the party joins is the one of the member closest to its middle.
		Creature engageTarget = null;
		double closest = Double.MAX_VALUE;
		for (Creature member : members)
		{
			final Creature target = getFightTarget(member);
			if ((target != null) && (anchor != null) && (member.calculateDistance2D(anchor) < closest))
			{
				engageTarget = target;
				closest = member.calculateDistance2D(anchor);
			}
		}
		if (engageTarget != null)
		{
			_lastEngaged = now;
		}

		final Picture previous = _picture;
		final Stance stance;
		final String why;
		final SimulationRun.Phase phase = profile.isSimulant() ? FakeSimulationManager.getInstance().getPhase(profile.getSimRunId()) : null;
		if (phase == SimulationRun.Phase.PREP)
		{
			stance = Stance.PREP;
			why = "PREP";
		}
		else if ((engageTarget != null) || ((previous.stance() == Stance.ENGAGE) && ((now - _lastEngaged) < ENGAGE_LINGER)))
		{
			stance = Stance.ENGAGE;
			why = "ENEMY_FOUGHT";
		}
		else
		{
			stance = Stance.MARCH;
			why = previous.stance() == Stance.PREP ? "FIGHT_START" : "NO_FIGHT";
		}

		// #30: its healer's cover. #31: an enemy archer or mage shooting its healer, which the party answers.
		final Creature healer = stance == Stance.ENGAGE ? findHealer(members) : null;
		final Location cover = healer != null ? findCover(npc, members, healer) : null;
		final Creature shooter = healer != null ? findShooterOnHealer(npc, members, healer) : null;

		_picture = new Picture(stance, engageTarget, anchor, cover, shooter);
		if (stance != previous.stance())
		{
			FakeAiTrace.onPartyStance(npc, previous.stance().name(), stance.name(), why, engageTarget);
		}
	}

	/**
	 * @param members the living members
	 * @return the party's healer (a fake player), {@code null} if none
	 */
	private static Creature findHealer(List<Creature> members)
	{
		for (Creature member : members)
		{
			if (isHealer(member) && member.isNpc())
			{
				return member;
			}
		}
		return null;
	}

	/**
	 * #30: where the party's healer stands while the party fights: on the line from the enemy to the party's front (its tank closest to the enemy, otherwise the member closest to it), as far behind the front as its heals still reach every member that fights
	 * from there. Its heals reach 600 and its party stands a few hundred apart: standing among them, it doesn't use that reach, and an enemy archer or mage that wants it has to come deep into the party for it, where the party answers it (see
	 * {@link #findShooterOnHealer}). A member deep in the enemy (more than {@link #COVER_AHEAD} ahead of the front) doesn't count: its healer doesn't follow it in.
	 * @param npc the member thinking for the party
	 * @param members its living members
	 * @param healer its healer
	 * @return the spot, {@code null} if the party has no front, no enemy is around, or its members are too far apart to all be healed from behind the front
	 */
	private static Location findCover(Attackable npc, List<Creature> members, Creature healer)
	{
		// How far its heals reach.
		int healRange = 0;
		final FakePlayerPvpProfile healerProfile = healer.asNpc().getTemplate().getFakePlayerPvpProfile();
		if (healerProfile != null)
		{
			for (Skill skill : healerProfile.getSkills(SkillCategory.PARTY_HEAL))
			{
				healRange = Math.max(healRange, skill.getCastRange());
			}
		}
		final double healReach = (healRange > 0 ? healRange : COVER_DEFAULT_HEAL_RANGE) * COVER_HEAL_SHARE;

		// The enemies around, and their middle.
		int count = 0;
		long x = 0;
		long y = 0;
		for (Creature enemy : World.getInstance().getVisibleObjectsInRange(healer, Creature.class, COVER_SCAN_RANGE))
		{
			if (isPvpEnemy(enemy) && !enemy.isAlikeDead() && (enemy.getInstanceId() == healer.getInstanceId()) && isValidTarget(npc, enemy))
			{
				count++;
				x += enemy.getX();
				y += enemy.getY();
			}
		}
		if (count == 0)
		{
			return null;
		}
		final double enemyX = (double) x / count;
		final double enemyY = (double) y / count;

		// The front: its tank closest to the enemy, otherwise the member (not a healer) closest to it.
		Creature front = null;
		double frontDistance = Double.MAX_VALUE;
		boolean frontIsTank = false;
		for (Creature member : members)
		{
			if (isHealer(member))
			{
				continue;
			}
			final boolean tank = isTank(member);
			final double distance = Math.hypot(member.getX() - enemyX, member.getY() - enemyY);
			if ((tank && !frontIsTank) || ((tank == frontIsTank) && (distance < frontDistance)))
			{
				front = member;
				frontDistance = distance;
				frontIsTank = tank;
			}
		}
		if ((front == null) || (frontDistance < 1))
		{
			return null;
		}

		// Behind the front, away from the enemy: the farthest spot from which its heals still reach the members that fight.
		final double backX = (front.getX() - enemyX) / frontDistance;
		final double backY = (front.getY() - enemyY) / frontDistance;
		Location cover = null;
		for (int back = COVER_MIN; back <= COVER_SCAN_RANGE; back += COVER_STEP)
		{
			final int spotX = front.getX() + (int) (backX * back);
			final int spotY = front.getY() + (int) (backY * back);
			for (Creature member : members)
			{
				// Not one deep in the enemy (a dagger on their healer): its healer doesn't follow it in.
				final double ahead = ((member.getX() - front.getX()) * -backX) + ((member.getY() - front.getY()) * -backY);
				if ((member != healer) && (ahead <= COVER_AHEAD) && (Math.hypot(member.getX() - spotX, member.getY() - spotY) > healReach))
				{
					return cover;
				}
			}
			cover = new Location(spotX, spotY, front.getZ());
		}
		return cover;
	}

	/**
	 * #31: an enemy archer or mage that targets the party's healer and has it within its reach: of those, the one closest to the party (the easiest to get at).
	 * @param npc the member thinking for the party
	 * @param members its living members
	 * @param healer its healer
	 * @return the shooter, {@code null} if none
	 */
	private static Creature findShooterOnHealer(Attackable npc, List<Creature> members, Creature healer)
	{
		Creature shooter = null;
		double closest = Double.MAX_VALUE;
		for (Creature enemy : World.getInstance().getVisibleObjectsInRange(healer, Creature.class, SHOOTER_SCAN_RANGE))
		{
			if ((enemy.getTarget() != healer) || !isPvpEnemy(enemy) || enemy.isAlikeDead() || isHealer(enemy) || !(enemy.isPlayer() ? isSquishy(enemy) : isRangedFake(enemy)) || !isValidTarget(npc, enemy) || (healer.calculateDistance2D(enemy) > (getThreatReach(enemy) + SHOOTER_MARGIN)))
			{
				continue;
			}
			for (Creature member : members)
			{
				if ((member != healer) && (member.calculateDistance2D(enemy) < closest))
				{
					shooter = enemy;
					closest = member.calculateDistance2D(enemy);
				}
			}
		}
		return shooter;
	}

	/**
	 * @param creature an enemy fake player
	 * @return {@code true} for an archer or a mage (not transformed)
	 */
	private static boolean isRangedFake(Creature creature)
	{
		final FakePlayerPvpProfile profile = creature.isNpc() ? creature.asNpc().getTemplate().getFakePlayerPvpProfile() : null;
		return (profile != null) && !profile.isTransformed() && ((profile.getRole() == Role.ARCHER) || (profile.getRole() == Role.MAGE));
	}

	/**
	 * #30: while the party fights, where its healer stands.
	 * @return the spot, {@code null} if nowhere in particular (not engaged, no enemy around...)
	 */
	public Location getCover()
	{
		final Picture picture = _picture;
		return picture.stance() == Stance.ENGAGE ? picture.cover() : null;
	}

	/**
	 * #31: while the party fights, the enemy archer or mage shooting its healer.
	 * @param npc the member that asks
	 * @return the shooter, {@code null} if none
	 */
	public Creature getShooterOnHealer(Attackable npc)
	{
		final Picture picture = _picture;
		final Creature shooter = picture.shooter();
		return (picture.stance() == Stance.ENGAGE) && (shooter != null) && !shooter.isAlikeDead() && (shooter.getInstanceId() == npc.getInstanceId()) && isValidTarget(npc, shooter) ? shooter : null;
	}

	/**
	 * @param member a member
	 * @return the enemy (player or fake player) it fights, {@code null} if none
	 */
	private static Creature getFightTarget(Creature member)
	{
		if (!member.isAttackable() || (member.getAI().getIntention() != Intention.ATTACK))
		{
			return null;
		}
		final WorldObject target = member.getTarget();
		return (target instanceof Creature enemy) && isPvpEnemy(enemy) && !enemy.isAlikeDead() ? enemy : null;
	}

	/**
	 * @return what the party does now
	 */
	public Stance getStance()
	{
		return _picture.stance();
	}

	/**
	 * @param npc a member that sees no enemy of its own
	 * @return while the party is engaged, the enemy it should help fight (the one the member closest to the party fights), {@code null} if none
	 */
	public Creature getEngageTarget(Attackable npc)
	{
		final Picture picture = _picture;
		final Creature target = picture.engageTarget();
		return (picture.stance() == Stance.ENGAGE) && (target != null) && !target.isAlikeDead() && (target.getInstanceId() == npc.getInstanceId()) && isValidTarget(npc, target) ? target : null;
	}

	// ---------------------------------------------------------------------------------------------
	// Out of a fight: following, helping, marching
	// ---------------------------------------------------------------------------------------------

	/**
	 * @param npc a member
	 * @param profile its profile
	 * @return what the party wants of it now, out of a fight of its own
	 */
	public PartyOrder orderFor(Attackable npc, FakePlayerPvpProfile profile)
	{
		// The one that leads right now: a leader stunned or casting something long hands over for the moment.
		final Creature leader = _party.getActiveLeader();
		if ((leader == null) || (leader == npc))
		{
			return new PartyOrder(OrderKind.LEAD, null);
		}

		// The party fights: the leader's target, or a monster that attacks a member.
		final Creature target = findPartyTarget(npc, profile, leader);
		if (target != null)
		{
			return new PartyOrder(OrderKind.ASSIST, target);
		}

		// #23: a healer in a fight doesn't chase its leader while its heals still reach it: it stays where it heals from.
		if (isPureHealer(profile) && (npc.calculateDistance2D(leader) <= HEALER_FOLLOW_REACH) && isEnemyAround(npc, HEALER_FIGHT_RANGE))
		{
			return new PartyOrder(OrderKind.HEALER_HOLD, leader);
		}

		return new PartyOrder(getFollow(npc, leader), leader);
	}

	/**
	 * @param npc a member
	 * @param leader its party leader
	 * @return how it follows its leader: {@link OrderKind#WAIT_FAR} or {@link OrderKind#LEAVE_PARTY} far from it, {@link OrderKind#STAY} by it, else {@link OrderKind#FOLLOW_RUN} or {@link OrderKind#FOLLOW_WALK}
	 */
	public OrderKind getFollow(Attackable npc, Creature leader)
	{
		// Far away (it teleported): FakePartyManager brings it there a while later. Fake players hunting together just go their own way; a simulation team stays together and runs back.
		if (FakePartyManager.isFarFrom(npc, leader) && !_party.isKeptTogether())
		{
			return _party.isFakeOnly() ? OrderKind.LEAVE_PARTY : OrderKind.WAIT_FAR;
		}

		final double distance = npc.calculateDistance2D(leader);
		final int followDistance = FakePartyConfig.FOLLOW_DISTANCE;
		if (distance <= followDistance)
		{
			return OrderKind.STAY;
		}
		return leader.isRunning() || (distance > (followDistance * 3)) ? OrderKind.FOLLOW_RUN : OrderKind.FOLLOW_WALK;
	}

	/**
	 * @param npc the fake player
	 * @param profile its profile
	 * @param leader the party leader
	 * @return what the party fights and it should join: the leader's target once the leader (or a member) fights it, else the closest monster attacking a member, {@code null} if none. A healer stays out of fights.
	 */
	private Creature findPartyTarget(Attackable npc, FakePlayerPvpProfile profile, Creature leader)
	{
		if (isPureHealer(profile))
		{
			return null;
		}

		final WorldObject leaderTarget = leader.getTarget();
		if ((leaderTarget instanceof Creature) && isPartyPrey(npc, (Creature) leaderTarget, leader))
		{
			return (Creature) leaderTarget;
		}

		final Creature[] closest = new Creature[1];
		final double[] closestDistance =
		{
			Double.MAX_VALUE
		};
		World.getInstance().forEachVisibleObjectInRange(leader, Monster.class, FakePartyConfig.LEASH_RANGE, monster ->
		{
			if (monster.isFakePlayer() || monster.isDead() || !monster.isSpawned() || (monster.getInstanceId() != npc.getInstanceId()) || !isAttackingParty(monster))
			{
				return;
			}

			final double distance = npc.calculateDistance2D(monster);
			if (distance < closestDistance[0])
			{
				closest[0] = monster;
				closestDistance[0] = distance;
			}
		});
		return closest[0];
	}

	/**
	 * @param npc the fake player
	 * @param target the leader's target
	 * @param leader the party leader
	 * @return {@code true} if the party fights {@code target} and it should help
	 */
	private boolean isPartyPrey(Attackable npc, Creature target, Creature leader)
	{
		if ((target == npc) || target.isAlikeDead() || !target.isSpawned() || target.isInvisible() || (target.getInstanceId() != npc.getInstanceId()) || _party.isMember(target) || (leader.calculateDistance2D(target) > FakePartyConfig.LEASH_RANGE))
		{
			return false;
		}

		// The leader of fake players hunting together: what it fights.
		if (leader.isAttackable())
		{
			return leader.asAttackable().getMostHated() == target;
		}

		// A player: a monster once the party fights it.
		if (target.isMonster() && !target.isFakePlayer())
		{
			return leader.isAttackingNow() || leader.isCastingNow() || isAttackingParty(target.asAttackable());
		}

		// A player or fake player the leader fights: only one that can be fought without becoming a PK (flagged, karma), or one that attacks the party.
		if ((target.isPlayer() || target.isPvpFakePlayer()) && (leader.isAttackingNow() || leader.isCastingNow()))
		{
			final boolean flagged = target.isPlayer() ? ((target.asPlayer().getPvpFlag() > 0) || (target.getKarma() > 0)) : ((target.asNpc().getScriptValue() > 0) || (target.getKarma() > 0));
			final WorldObject itsTarget = target.getTarget();
			return flagged || ((itsTarget instanceof Creature) && _party.isMember((Creature) itsTarget));
		}

		return false;
	}

	/**
	 * @param monster a monster
	 * @return {@code true} if {@code monster} fights a member of the party (it hates a member, or a member hit it)
	 */
	private boolean isAttackingParty(Attackable monster)
	{
		for (Creature attacker : monster.getAggroList().keySet())
		{
			if (_party.isMember(attacker) || (attacker.isSummon() && _party.isMember(attacker.asPlayer())))
			{
				return true;
			}
		}

		final WorldObject target = monster.getTarget();
		return monster.isInCombat() && (target instanceof Creature) && _party.isMember((Creature) target);
	}

	/**
	 * Pulls the monsters around while the leader stands there (FakePartyHuntWhenIdle), with players only: not a healer.
	 * @param npc the fake player
	 * @param profile its profile
	 * @param leader the party leader
	 * @return the closest monster around the leader nobody fights and worth hunting, {@code null} if none (or the party doesn't hunt now)
	 */
	public Creature findIdlePrey(Attackable npc, FakePlayerPvpProfile profile, Creature leader)
	{
		if (!FakePartyConfig.HUNT_WHEN_IDLE || leader.isMoving() || isPureHealer(profile) || _party.isFakeOnly())
		{
			return null;
		}

		final Creature[] closest = new Creature[1];
		final double[] closestDistance =
		{
			Double.MAX_VALUE
		};
		World.getInstance().forEachVisibleObjectInRange(leader, Monster.class, IDLE_HUNT_RANGE, monster ->
		{
			if (monster.isFakePlayer() || monster.isDead() || !monster.isSpawned() || monster.isRaid() || monster.isInCombat() || !monster.getAggroList().isEmpty() || (monster.getInstanceId() != npc.getInstanceId()) || (Math.abs(monster.getLevel() - leader.getLevel()) > MAX_HUNT_LEVEL_DIFFERENCE))
			{
				return;
			}

			// The line of sight (the costly check) only for one closer than the closest so far.
			final double distance = npc.calculateDistance2D(monster);
			if ((distance < closestDistance[0]) && GeoEngine.getInstance().canSeeTarget(leader, monster))
			{
				closest[0] = monster;
				closestDistance[0] = distance;
			}
		});
		return closest[0];
	}

	/**
	 * @param npc a member of a simulation team that leads (or is on its own)
	 * @return where it marches: the nearest other team, {@code null} if none is left
	 */
	public Location getMarchTarget(Npc npc)
	{
		return FakeSimulationManager.getInstance().getMarchTarget(npc);
	}

	// ---------------------------------------------------------------------------------------------
	// Who to focus: the party's call, and its share of the target choice
	// ---------------------------------------------------------------------------------------------

	/**
	 * A call that doesn't bring its target down (a healer heals it through) is dropped: the party turns to someone it can kill.
	 * @param npc the member that looks at it
	 * @param now the current time
	 * @return the call dropped, {@code null} if none
	 */
	public Creature dropFailedCall(Attackable npc, long now)
	{
		final double startHealth = _party.getFocusStartHealth();
		final Creature dropped = _party.dropFailedFocus(now, CALL_PROGRESS_TIME, CALL_MIN_LOSS, CALL_DROP_TIME);
		if (dropped != null)
		{
			FakeAiTrace.onCallDropped(npc, dropped, startHealth, getHealth(dropped));
		}
		return dropped;
	}

	/**
	 * @param healer {@code true} for a healer: it keeps healing, and only hits what is close to it
	 * @param tank {@code true} for a tank: it guards its squishies, and only calls for the others
	 * @param now the current time
	 * @return the party's call it should follow, {@code null} if none (or it doesn't follow calls)
	 */
	public Creature getCallFor(boolean healer, boolean tank, long now)
	{
		return !healer && !tank ? _party.getFocus(now) : null;
	}

	/**
	 * What the party should all go for, from the target a member chose: an enemy healer, or one about to fall (its healer first, when one is close enough to heal it back up and to reach).
	 * @param npc the member
	 * @param best the target it chose
	 * @param now the current time
	 * @return the call to make, {@code null} if none (an invincible one, or one a call on just failed)
	 */
	public Creature pickCall(Attackable npc, Creature best, long now)
	{
		Creature call = null;
		if (isHealer(best))
		{
			call = best;
		}
		else if (getHealth(best) < FINISH_CALL_HEALTH)
		{
			final Creature enemyHealer = findEnemyHealerNear(npc, best, ENEMY_HEAL_REACH);
			call = (enemyHealer != null) && (npc.calculateDistance2D(enemyHealer) <= FOCUS_SCAN_RANGE) ? enemyHealer : (enemyHealer == null ? best : null);
		}
		return (call != null) && !call.isInvul() && !_party.isCallDropped(call, now) ? call : null;
	}

	/**
	 * The whole party on {@code call}.
	 * @param npc the member that calls it
	 * @param call the enemy
	 * @param now the current time
	 */
	public void makeCall(Attackable npc, Creature call, long now)
	{
		if (_party.getFocus(now) != call)
		{
			FakeAiTrace.onFocusCall(npc, call, isHealer(call) ? "HEALER" : "FINISH");
		}
		_party.setFocus(call, now, now + PARTY_FOCUS_TIME);
	}

	/**
	 * @param npc the fake player
	 * @param target an enemy
	 * @param range how close
	 * @return a living enemy healer within {@code range} of {@code target} that it may fight (the closest), {@code null} if none
	 */
	private static Creature findEnemyHealerNear(Attackable npc, Creature target, int range)
	{
		Creature closest = null;
		double closestDistance = Double.MAX_VALUE;
		for (Creature creature : World.getInstance().getVisibleObjectsInRange(target, Creature.class, range))
		{
			if ((creature == npc) || creature.isAlikeDead() || !isHealer(creature) || !isPvpEnemy(creature) || !isValidTarget(npc, creature))
			{
				continue;
			}
			final double distance = creature.calculateDistance2D(target);
			if (distance < closestDistance)
			{
				closest = creature;
				closestDistance = distance;
			}
		}
		return closest;
	}

	/**
	 * The party's share of how good a target {@code enemy} is for a member (lower is better): the members already on it, one on a healer or squishy of the party, the damage it dealt to the party, a call on it that just failed.
	 * @param npc the member
	 * @param enemy the enemy
	 * @param score its own score of {@code enemy}
	 * @return the score with the party's share
	 */
	public double adjustScore(Attackable npc, Creature enemy, double score)
	{
		double focusFire = 0;
		for (Creature member : _party.getMembers())
		{
			if ((member != npc) && !member.isDead() && (member.getTarget() == enemy))
			{
				focusFire += FOCUS_FIRE_BONUS;
			}
		}
		score -= Math.min(FOCUS_FIRE_MAX, focusFire);
		// #4: one that is on a squishy of the party is answered, from wherever it shoots (it used to react only to divers within a few hundred).
		if ((enemy.getTarget() instanceof Creature victim) && (victim != enemy) && !victim.isDead() && _party.isMember(victim) && !isTank(enemy)) // #28: not an enemy tank, which peels rather than kills.
		{
			if (isHealer(victim))
			{
				score -= PRESSURE_ON_HEALER;
			}
			else if (isSquishy(victim))
			{
				score -= PRESSURE_ON_SQUISHY;
			}
		}
		// The one that hurts the party most (its damage to the party's fake players so far), and not one a call on just failed (it is healed through).
		score -= Math.min(THREAT_MAX, (getPartyDamage(enemy) / Math.max(1.0, npc.getMaxHp() * THREAT_HP_SCALE)) * THREAT_MAX);
		if (_party.isCallDropped(enemy, System.currentTimeMillis()))
		{
			score += DROPPED_CALL_PENALTY;
		}
		return score;
	}

	/**
	 * @param enemy an enemy
	 * @return the damage {@code enemy} dealt to the party's fake players so far (their aggro lists)
	 */
	private long getPartyDamage(Creature enemy)
	{
		long damage = 0;
		for (Npc fake : _party.getFakes())
		{
			if (fake.isAttackable())
			{
				final AggroInfo info = fake.asAttackable().getAggroList().get(enemy);
				if (info != null)
				{
					damage += info.getDamage();
				}
			}
		}
		return damage;
	}

	/**
	 * Adds to a member's enemies the ones of the party's fight around it: those that fight the party, and enemy healers healing someone it fights (all the other teams in a simulation).
	 * @param npc the member
	 * @param enemies the enemies of its own fight, added to
	 * @param range how far it looks
	 */
	public void addFightEnemies(Attackable npc, List<Creature> enemies, int range)
	{
		for (Creature enemy : World.getInstance().getVisibleObjectsInRange(npc, Creature.class, range))
		{
			if (enemies.contains(enemy) || !isPvpEnemy(enemy) || enemy.isAlikeDead() || !isValidTarget(npc, enemy))
			{
				continue;
			}
			final WorldObject itsTarget = enemy.getTarget();
			final boolean fightsParty = (itsTarget == npc) || ((itsTarget instanceof Creature) && _party.isMember((Creature) itsTarget));
			final boolean healsEnemy = isHealer(enemy) && (itsTarget instanceof Creature) && hates(npc, (Creature) itsTarget, FakePlayerPvpManager.PVP_HATE);
			if (fightsParty || healsEnemy || FakeSimulationManager.isRunEnemy(npc, enemy) || TeamVsTeamManager.isEnemy(npc, enemy))
			{
				enemies.add(enemy);
			}
		}
	}

	// ---------------------------------------------------------------------------------------------
	// The party's picture of the fight
	// ---------------------------------------------------------------------------------------------

	/**
	 * @param npc a tank or support of the party
	 * @param support {@code true} for a support: it stays with the whole party, a tank with its squishies
	 * @return who it guards, close enough to matter, and their middle; {@code null} if nobody
	 */
	public Guard getGuarded(Attackable npc, boolean support)
	{
		final List<Creature> guarded = new ArrayList<>();
		long x = 0;
		long y = 0;
		long z = 0;
		for (Creature member : _party.getMembers())
		{
			if ((member == npc) || member.isDead() || !member.isSpawned() || (member.getInstanceId() != npc.getInstanceId()) || (npc.calculateDistance2D(member) > GUARD_SCAN_RANGE) || (!support && !isSquishy(member)))
			{
				continue;
			}
			guarded.add(member);
			x += member.getX();
			y += member.getY();
			z += member.getZ();
		}
		if (guarded.isEmpty())
		{
			return null;
		}
		return new Guard(guarded, new Location((int) (x / guarded.size()), (int) (y / guarded.size()), (int) (z / guarded.size())));
	}

	/**
	 * Someone dives a squishy: the one closest to its victim, on a healer first, a melee one before an archer or mage that only shoots from there.
	 * @param npc the tank or support that guards them
	 * @param guarded the members it guards
	 * @return the diver and how urgent it is
	 */
	public Diver findDiver(Attackable npc, List<Creature> guarded)
	{
		Creature diver = null;
		double best = Double.MAX_VALUE;
		for (Creature member : guarded)
		{
			if (!isSquishy(member))
			{
				continue;
			}
			for (Creature enemy : World.getInstance().getVisibleObjectsInRange(member, Creature.class, GUARD_THREAT_RANGE))
			{
				if ((enemy.getTarget() != member) || !isPvpEnemy(enemy) || enemy.isAlikeDead() || !isValidTarget(npc, enemy))
				{
					continue;
				}
				final double score = getDiverScore(enemy, member);
				if (score < best)
				{
					diver = enemy;
					best = score;
				}
			}
		}
		return new Diver(diver, best);
	}

	/**
	 * @param enemy an enemy diving a squishy
	 * @param victim the squishy
	 * @return how urgent it is to peel {@code enemy}, lower first: close to its victim, on a healer, a melee one (a dagger on the healer) before an archer or mage that shoots from where it stands
	 */
	public static double getDiverScore(Creature enemy, Creature victim)
	{
		return enemy.calculateDistance2D(victim) - (isHealer(victim) ? DIVER_HEALER_BONUS : 0) + (isSquishy(enemy) ? DIVER_RANGED_MALUS : 0);
	}

	/**
	 * @param enemy an enemy
	 * @param guarded the party members a tank guards
	 * @return {@code true} if {@code enemy} still targets one of the squishies among them, close to it
	 */
	public static boolean isDiving(Creature enemy, List<Creature> guarded)
	{
		final WorldObject itsTarget = enemy.getTarget();
		if (enemy.isAlikeDead() || !(itsTarget instanceof Creature) || !guarded.contains(itsTarget) || !isSquishy((Creature) itsTarget))
		{
			return false;
		}
		return enemy.calculateDistance2D(itsTarget) <= (GUARD_THREAT_RANGE * DIVER_KEEP_FACTOR);
	}

	/**
	 * @param npc a tank of the party
	 * @param anchor the middle of the squishies it guards
	 * @param leash how far from them it goes
	 * @return the closest enemy within {@code leash} of its squishies that fights the party (or that it fights), {@code null} if none
	 */
	public Creature findGuardTarget(Attackable npc, Location anchor, int leash)
	{
		Creature closest = null;
		double closestDistance = Double.MAX_VALUE;
		for (Creature enemy : World.getInstance().getVisibleObjectsInRange(npc, Creature.class, GUARD_SCAN_RANGE))
		{
			if (!isPvpEnemy(enemy) || enemy.isAlikeDead() || !isValidTarget(npc, enemy))
			{
				continue;
			}
			final double distance = enemy.calculateDistance2D(anchor);
			final WorldObject itsTarget = enemy.getTarget();
			if ((distance < closestDistance) && (distance <= leash) && (hates(npc, enemy, FakePlayerPvpManager.PVP_HATE) || ((itsTarget instanceof Creature) && _party.isMember((Creature) itsTarget))))
			{
				closest = enemy;
				closestDistance = distance;
			}
		}
		return closest;
	}

	/**
	 * #23: {@code true} if an enemy close to {@code npc} targets a member of the party.
	 */
	public boolean isPartyFought(Attackable npc)
	{
		for (Creature enemy : World.getInstance().getVisibleObjectsInRange(npc, Creature.class, GUARD_SCAN_RANGE))
		{
			if ((enemy.getTarget() instanceof Creature victim) && _party.isMember(victim) && isPvpEnemy(enemy) && !enemy.isAlikeDead() && isValidTarget(npc, enemy))
			{
				return true;
			}
		}
		return false;
	}

	/**
	 * Where a melee damage dealer holds rather than charge {@code target} alone (#25): by its closest tank when it has one, otherwise the middle of the party around it.
	 * @param npc the melee member
	 * @param target the enemy it would go for
	 * @return the place, {@code null} if it doesn't hold for the party: its tank is on that enemy already (it follows it in), or nobody is around
	 */
	public Location getHoldAnchor(Attackable npc, Creature target)
	{
		Creature tankMember = null;
		long x = 0;
		long y = 0;
		long z = 0;
		int others = 0;
		for (Creature member : _party.getMembers())
		{
			if ((member == npc) || member.isDead() || !member.isSpawned() || (member.getInstanceId() != npc.getInstanceId()) || (npc.calculateDistance2D(member) > GUARD_SCAN_RANGE))
			{
				continue;
			}

			// Its tank is on that enemy already: it follows it in.
			if (isTank(member) && (member.calculateDistance2D(target) <= MELEE_TANK_LEAD))
			{
				return null;
			}
			if (isTank(member) && ((tankMember == null) || (npc.calculateDistance2D(member) < npc.calculateDistance2D(tankMember))))
			{
				tankMember = member;
			}
			x += member.getX();
			y += member.getY();
			z += member.getZ();
			others++;
		}
		if (others == 0)
		{
			return null;
		}
		return tankMember != null ? new Location(tankMember.getX(), tankMember.getY(), tankMember.getZ()) : new Location((int) (x / others), (int) (y / others), (int) (z / others));
	}

	/**
	 * #27: where the party is for {@code npc}: its closest tank, otherwise the middle of the others.
	 * @return the place, {@code null} if it is alone
	 */
	public Location getAnchor(Attackable npc)
	{
		Creature tank = null;
		long x = 0;
		long y = 0;
		long z = 0;
		int others = 0;
		for (Creature member : _party.getMembers())
		{
			if ((member == npc) || member.isDead() || !member.isSpawned() || (member.getInstanceId() != npc.getInstanceId()))
			{
				continue;
			}
			if (isTank(member) && ((tank == null) || (npc.calculateDistance2D(member) < npc.calculateDistance2D(tank))))
			{
				tank = member;
			}
			x += member.getX();
			y += member.getY();
			z += member.getZ();
			others++;
		}
		if (tank != null)
		{
			return new Location(tank.getX(), tank.getY(), tank.getZ());
		}
		return others > 0 ? new Location((int) (x / others), (int) (y / others), (int) (z / others)) : null;
	}

	/**
	 * @param npc a member
	 * @return the tank of the party closest to it, {@code null} if none
	 */
	public Creature getClosestTank(Attackable npc)
	{
		Creature tank = null;
		double tankDistance = Double.MAX_VALUE;
		for (Creature member : _party.getMembers())
		{
			if ((member == npc) || member.isDead() || !member.isSpawned() || (member.getInstanceId() != npc.getInstanceId()))
			{
				continue;
			}
			final double distance = npc.calculateDistance2D(member);
			if (isTank(member) && (distance < tankDistance))
			{
				tank = member;
				tankDistance = distance;
			}
		}
		return tank;
	}

	/**
	 * @param npc an archer or mage of the party
	 * @return the enemies on a healer of the party close to {@code npc} (close to the healer and targeting it) that it sees and may stop, healer by healer
	 */
	public List<Creature> findEnemiesOnHealers(Attackable npc)
	{
		final List<Creature> enemies = new ArrayList<>();
		for (Creature member : _party.getMembers())
		{
			if ((member == npc) || member.isDead() || !isHealer(member) || (npc.calculateDistance2D(member) > ALLY_PEEL_SCAN))
			{
				continue;
			}

			for (Creature enemy : World.getInstance().getVisibleObjectsInRange(member, Creature.class, HEALER_DIVE_RANGE))
			{
				if ((enemy.getTarget() != member) || !isPvpEnemy(enemy) || enemy.isAlikeDead() || isDisabled(enemy) || !isValidTarget(npc, enemy) || !GeoEngine.getInstance().canSeeTarget(npc, enemy))
				{
					continue;
				}
				enemies.add(enemy);
			}
		}
		return enemies;
	}
}
