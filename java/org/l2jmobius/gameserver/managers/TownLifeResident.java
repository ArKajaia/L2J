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

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.l2jmobius.commons.util.Rnd;
import org.l2jmobius.gameserver.ai.Intention;
import org.l2jmobius.gameserver.config.custom.TownLifeConfig;
import org.l2jmobius.gameserver.model.Location;
import org.l2jmobius.gameserver.model.World;
import org.l2jmobius.gameserver.model.WorldObject;
import org.l2jmobius.gameserver.model.actor.Npc;
import org.l2jmobius.gameserver.model.actor.Player;
import org.l2jmobius.gameserver.model.interfaces.ILocational;
import org.l2jmobius.gameserver.model.nightcycle.NightPhase;
import org.l2jmobius.gameserver.model.spawns.Spawn;
import org.l2jmobius.gameserver.network.enums.ChatType;
import org.l2jmobius.gameserver.network.serverpackets.MagicSkillUse;
import org.l2jmobius.gameserver.network.serverpackets.StopRotation;
import org.l2jmobius.gameserver.util.LocationUtil;

/**
 * One npc of the town life (see {@link TownLifeManager}): what it is, the house it lives in, when it is out, and what it does while it is. Only changed by the manager's thread.
 */
final class TownLifeResident
{
	private static final Logger LOGGER = Logger.getLogger(TownLifeResident.class.getName());
	
	/** Close enough to where it was going. */
	private static final int ARRIVED = 60;
	/** Real time a walk home may take before the npc just goes in. */
	private static final long HOME_WALK_TIMEOUT = 180_000;
	/** A player is greeted again by the same npc only after this. */
	private static final long GREET_AGAIN = 300_000;
	/** Skill whose visual the lamplighter shows at a lamp (Star Shard: a little sparkle). */
	private static final int LAMP_SPARK_SKILL = 2023;
	
	/** What a town life npc is. */
	enum Kind
	{
		WANDERER(Schedule.DAY),
		PORTER(Schedule.DAY),
		ERRAND_RUNNER(Schedule.DAY),
		SWEEPER(Schedule.DAY),
		CHILD(Schedule.DAY),
		PATROL(Schedule.DAY),
		LAMPLIGHTER(Schedule.DUSK),
		NIGHT_WATCH(Schedule.NIGHT),
		TAVERN(Schedule.EVENING),
		CRIER(Schedule.DAY),
		FISHERMAN(Schedule.DAY),
		DOCK_WORKER(Schedule.DAY),
		DECORATION(Schedule.FESTIVAL);
		
		private final Schedule _schedule;
		
		Kind(Schedule schedule)
		{
			_schedule = schedule;
		}
		
		Schedule getSchedule()
		{
			return _schedule;
		}
	}
	
	/** When a town life npc is out of its house. */
	enum Schedule
	{
		/** From dawn (each one gets up at its own time) until dusk (each one goes in at its own time). */
		DAY,
		/** During the dusk. */
		DUSK,
		/** At night and in the Witching Hour. */
		NIGHT,
		/** At night until the Witching Hour. */
		EVENING,
		/** All day long on festival day. */
		FESTIVAL
	}
	
	private enum State
	{
		/** In its house (not in the world). */
		HOME,
		/** Out, about its business. */
		OUT,
		/** Walking to its house. */
		GOING_HOME
	}
	
	final TownLifeTown town;
	final Kind kind;
	final int npcId;
	/** The house it goes into at dusk and comes out of at dawn. */
	Location home;
	/** Only out on festival day (the extra townsfolk). */
	final boolean festivalOnly;
	/** When in the dusk it goes in, 0-1. */
	private final double _bedtime;
	/** When in the dawn it comes out, 0-1. */
	private final double _wakeTime;
	
	private Npc _npc;
	private State _state = State.HOME;
	private boolean _everOut;
	long nextThink;
	
	// Walking.
	private Location _destination;
	private Location _lastPosition;
	private Location _lastLeg;
	private int _stuck;
	private long _homeDeadline;
	
	// What it does.
	long waitUntil;
	/** An npc it faces once it gets where it is going (a shop it looks at), {@code null} for none. */
	private WorldObject _faceOnArrival;
	/** A heading it takes once it gets where it is going, -1 for none. */
	private int _headingOnArrival = -1;
	/** The two ends of an errand (porters, dock workers) or the spot it keeps (crier, fisherman, tavern), {@code null} if none. */
	Location workA;
	Location workB;
	int workHeading = -1;
	private boolean _towardsB;
	/** The patrol leader a follower walks behind, {@code null} for none. */
	TownLifeResident leader;
	private int _routeIndex;
	/** The conversation it is in, {@code null} for none. */
	TownLifeTown.Conversation conversation;
	private final Map<Integer, Long> _greeted = new HashMap<>();
	
	// Children.
	long frozenUntil;
	/** The player a child asked for a sweet, 0 for none. */
	int askedPlayerId;
	
	TownLifeResident(TownLifeTown town, Kind kind, int npcId, Location home, boolean festivalOnly)
	{
		this.town = town;
		this.kind = kind;
		this.npcId = npcId;
		this.home = home;
		this.festivalOnly = festivalOnly;
		if (kind == Kind.CHILD)
		{
			// The children are called in first and come out late.
			_bedtime = Rnd.get(0, 20) / 100.0;
			_wakeTime = Rnd.get(40, 90) / 100.0;
		}
		else
		{
			_bedtime = Rnd.get(20, 85) / 100.0;
			_wakeTime = Rnd.get(0, 60) / 100.0;
		}
	}
	
	/**
	 * @return the npc, {@code null} while it is in its house
	 */
	Npc getNpc()
	{
		final Npc npc = _npc;
		return (npc != null) && npc.isSpawned() ? npc : null;
	}
	
	/**
	 * @return {@code true} while it is out and not on its way home
	 */
	boolean isOut()
	{
		return (_state == State.OUT) && (getNpc() != null);
	}
	
	/**
	 * @return {@code true} if it is out with nothing to do right now (not walking, not talking)
	 */
	boolean isIdle(long now)
	{
		return isOut() && (_destination == null) && (conversation == null) && (waitUntil <= now);
	}
	
	/**
	 * @param clock the time of day
	 * @param festival {@code true} on festival day
	 * @return {@code true} if it should be out of its house now
	 */
	boolean wantsOut(TownLifeManager.Clock clock, boolean festival)
	{
		if (festivalOnly && !festival)
		{
			return false;
		}
		
		final NightPhase phase = clock.phase();
		switch (kind.getSchedule())
		{
			case DAY:
			{
				switch (phase)
				{
					case DAY:
					{
						return true;
					}
					case DAWN:
					{
						return !TownLifeConfig.GO_HOME || (clock.dawnProgress() >= _wakeTime);
					}
					case DUSK:
					{
						return !TownLifeConfig.GO_HOME || (clock.duskProgress() < _bedtime);
					}
					default:
					{
						return !TownLifeConfig.GO_HOME;
					}
				}
			}
			case DUSK:
			{
				return (phase == NightPhase.DUSK) && (clock.duskProgress() < 0.95);
			}
			case NIGHT:
			{
				return phase.isNight();
			}
			case EVENING:
			{
				return phase == NightPhase.NIGHT;
			}
			case FESTIVAL:
			{
				return festival;
			}
		}
		return false;
	}
	
	/**
	 * Comes out, goes in, or gets on with what it does.
	 * @param clock the time of day
	 * @param festival {@code true} on festival day
	 * @param now the current time
	 */
	void update(TownLifeManager.Clock clock, boolean festival, long now)
	{
		final boolean out = wantsOut(clock, festival);
		final Npc npc = getNpc();
		if (out)
		{
			if (npc == null)
			{
				// The first time out (server start in the middle of the day) it is already somewhere about its business, afterwards it comes out of its house.
				spawn((kind != Kind.DECORATION) && (_everOut || (clock.phase() != NightPhase.DAY)) ? home : town.startLocation(this));
			}
			else if (_state == State.GOING_HOME)
			{
				// A GM turned the clock back: it turns around.
				_state = State.OUT;
				_destination = null;
			}
			else if (now >= nextThink)
			{
				nextThink = now + thinkInterval();
				if (TownLifeManager.isWatched(npc))
				{
					try
					{
						think(npc, clock, now);
					}
					catch (Exception e)
					{
						LOGGER.log(Level.WARNING, getClass().getSimpleName() + ": " + kind + " in " + town.name + " failed to think.", e);
					}
				}
			}
		}
		else if (npc != null)
		{
			if (_state != State.GOING_HOME)
			{
				startGoingHome(npc, now);
			}
			else
			{
				continueGoingHome(npc, now);
			}
		}
	}
	
	private long thinkInterval()
	{
		switch (kind)
		{
			case CHILD:
			{
				return 600;
			}
			case PATROL:
			{
				return leader != null ? 700 : 1000;
			}
			default:
			{
				return 1000;
			}
		}
	}
	
	/**
	 * Comes into the world.
	 * @param location where
	 */
	private void spawn(Location location)
	{
		if (location == null)
		{
			return;
		}
		
		try
		{
			final Spawn spawn = new Spawn(npcId);
			spawn.setXYZ(location.getX(), location.getY(), location.getZ());
			spawn.setHeading((workHeading >= 0) && (location == workA) ? workHeading : Rnd.get(65536));
			spawn.setAmount(1);
			spawn.stopRespawn();
			final Npc npc = spawn.doSpawn(false);
			if (npc == null)
			{
				return;
			}
			
			npc.setRandomWalking(false);
			final String title = town.titleFor(kind);
			if (title != null)
			{
				npc.setTitle(title);
				npc.broadcastInfo();
			}
			
			if ((kind == Kind.CHILD) || (kind == Kind.ERRAND_RUNNER))
			{
				npc.setRunning();
			}
			else
			{
				npc.setWalking();
			}
			
			_npc = npc;
			_state = State.OUT;
			_everOut = true;
			_destination = null;
			_stuck = 0;
			conversation = null;
			askedPlayerId = 0;
			waitUntil = System.currentTimeMillis() + Rnd.get(1000, 8000);
			nextThink = 0;
		}
		catch (Exception e)
		{
			LOGGER.log(Level.WARNING, getClass().getSimpleName() + ": Could not spawn " + kind + " " + npcId + " in " + town.name + ".", e);
		}
	}
	
	/**
	 * Goes back into its house: away from the world.
	 */
	void despawn()
	{
		final Npc npc = _npc;
		_npc = null;
		_state = State.HOME;
		_destination = null;
		town.leaveConversation(this);
		askedPlayerId = 0;
		if ((npc != null) && npc.isSpawned())
		{
			npc.deleteMe();
		}
	}
	
	private void startGoingHome(Npc npc, long now)
	{
		town.leaveConversation(this);
		if ((home == null) || !TownLifeManager.isWatched(npc) || (kind == Kind.DECORATION))
		{
			despawn();
			return;
		}
		
		switch (kind)
		{
			case CHILD:
			{
				if (Rnd.get(3) == 0)
				{
					say(npc, TownLifeLines.random(TownLifeLines.CHILD_HOME), null);
				}
				break;
			}
			case TAVERN:
			{
				if (Rnd.get(3) == 0)
				{
					say(npc, TownLifeLines.random(TownLifeLines.TAVERN_LEAVE), null);
				}
				break;
			}
		}
		
		if (kind != Kind.CHILD)
		{
			npc.setWalking();
		}
		_state = State.GOING_HOME;
		_destination = home;
		_faceOnArrival = null;
		_headingOnArrival = -1;
		_stuck = 0;
		_homeDeadline = now + HOME_WALK_TIMEOUT;
		askedPlayerId = 0;
	}
	
	private void continueGoingHome(Npc npc, long now)
	{
		if ((now >= _homeDeadline) || !TownLifeManager.isWatched(npc) || moveToward(npc, home))
		{
			despawn();
		}
	}
	
	/**
	 * What it does while it is out (only while a player is around to see it).
	 */
	private void think(Npc npc, TownLifeManager.Clock clock, long now)
	{
		if (kind == Kind.CHILD)
		{
			town.thinkChild(this, npc, now);
			return;
		}
		
		if (conversation != null)
		{
			// Walking up to the one it talks to; the town runs the talk itself.
			if (conversation.isApproaching() && (conversation.first == this) && moveToward(npc, conversation.meetingPoint))
			{
				town.beginTalk(conversation);
			}
			return;
		}
		
		// Walking somewhere.
		if (_destination != null)
		{
			if (moveToward(npc, _destination))
			{
				arrive(npc, now);
			}
			return;
		}
		
		// Standing around.
		if (waitUntil > now)
		{
			maybeGreet(npc, now);
			return;
		}
		
		switch (kind)
		{
			case WANDERER:
			{
				thinkWanderer(npc, now);
				break;
			}
			case PORTER:
			case ERRAND_RUNNER:
			case DOCK_WORKER:
			{
				thinkErrand(npc, now);
				break;
			}
			case SWEEPER:
			{
				goTo(town.nearbyHub(npc, 700), null, -1);
				break;
			}
			case PATROL:
			{
				thinkPatrol(npc, now);
				break;
			}
			case NIGHT_WATCH:
			{
				goTo(nextRoutePoint(town.patrolRoute), null, -1);
				break;
			}
			case LAMPLIGHTER:
			{
				if (_routeIndex < town.lampRoute.size())
				{
					goTo(town.lampRoute.get(_routeIndex++), null, -1);
				}
				else
				{
					// Round done: a last look around.
					goTo(town.nearbyHub(npc, 500), null, -1);
				}
				break;
			}
			case TAVERN:
			{
				thinkTavern(npc, now);
				break;
			}
			case CRIER:
			{
				if ((workA != null) && (npc.calculateDistance2D(workA) > ARRIVED))
				{
					goTo(workA, null, workHeading);
				}
				else
				{
					waitUntil = now + 5000;
				}
				break;
			}
			case FISHERMAN:
			{
				if ((workA != null) && (npc.calculateDistance2D(workA) > ARRIVED))
				{
					goTo(workA, null, workHeading);
				}
				else
				{
					if (Rnd.get(3) == 0)
					{
						npc.onRandomAnimation(Rnd.get(1, 3));
					}
					if (Rnd.get(8) == 0)
					{
						say(npc, TownLifeLines.random(TownLifeLines.FISHERMAN), null);
					}
					waitUntil = now + Rnd.get(20_000, 60_000);
				}
				break;
			}
		}
	}
	
	private void thinkWanderer(Npc npc, long now)
	{
		final int roll = Rnd.get(100);
		if (TownLifeConfig.CHATTER && (roll < 20) && town.startConversation(this, now))
		{
			return;
		}
		
		if (roll < 50)
		{
			// Have a look at a shop.
			final TownLifeTown.Spot spot = town.randomShopSpot();
			if (spot != null)
			{
				goTo(spot.location(), spot.npc(), -1);
				return;
			}
		}
		
		goTo(town.randomHub(), null, -1);
	}
	
	private void thinkErrand(Npc npc, long now)
	{
		if ((workA == null) || (workB == null))
		{
			goTo(town.nearbyHub(npc, 900), null, -1);
			return;
		}
		
		_towardsB = !_towardsB;
		goTo(_towardsB ? workB : workA, null, -1);
	}
	
	private void thinkPatrol(Npc npc, long now)
	{
		if (leader == null)
		{
			goTo(nextRoutePoint(town.patrolRoute), null, -1);
			return;
		}
		
		// A follower keeps a step behind its leader.
		final Npc leaderNpc = leader.getNpc();
		if (leaderNpc == null)
		{
			goTo(town.nearbyHub(npc, 600), null, -1);
			return;
		}
		
		final double behind = Math.toRadians(LocationUtil.convertHeadingToDegree(leaderNpc.getHeading()) + 180 + 25);
		final int x = leaderNpc.getX() + (int) (Math.cos(behind) * 55);
		final int y = leaderNpc.getY() + (int) (Math.sin(behind) * 55);
		final Location spot = TownLifeManager.groundAt(x, y, leaderNpc.getZ(), leaderNpc);
		if (npc.calculateDistance2D(spot) > 70)
		{
			if (npc.calculateDistance2D(leaderNpc) > 1500)
			{
				npc.teleToLocation(spot);
			}
			else
			{
				npc.getAI().setIntention(Intention.MOVE_TO, spot);
			}
		}
		else if (!npc.isMoving() && !leaderNpc.isMoving() && (npc.getHeading() != leaderNpc.getHeading()))
		{
			face(npc, leaderNpc.getHeading());
		}
	}
	
	private void thinkTavern(Npc npc, long now)
	{
		if ((workA != null) && (npc.calculateDistance2D(workA) > ARRIVED))
		{
			goTo(workA, null, -1);
			return;
		}
		
		face(npc, town.tavernCenter);
		final int roll = Rnd.get(100);
		if (roll < 45)
		{
			npc.onRandomAnimation(Rnd.get(1, 3));
		}
		else if ((roll < 70) && town.mayTalkInTavern(now))
		{
			say(npc, TownLifeLines.random(TownLifeLines.TAVERN), null);
		}
		waitUntil = now + Rnd.get(6000, 18000);
	}
	
	/**
	 * Got where it was going.
	 */
	private void arrive(Npc npc, long now)
	{
		_destination = null;
		if (_faceOnArrival != null)
		{
			face(npc, _faceOnArrival);
			_faceOnArrival = null;
		}
		else if (_headingOnArrival >= 0)
		{
			face(npc, _headingOnArrival);
			_headingOnArrival = -1;
		}
		
		switch (kind)
		{
			case WANDERER:
			{
				if (Rnd.get(3) == 0)
				{
					npc.onRandomAnimation(Rnd.get(1, 3));
				}
				if (TownLifeConfig.CHATTER && (Rnd.get(10) == 0))
				{
					say(npc, TownLifeLines.random(TownLifeLines.MUSINGS), null);
				}
				waitUntil = now + Rnd.get(6000, 25000);
				break;
			}
			case PORTER:
			case ERRAND_RUNNER:
			case DOCK_WORKER:
			{
				// Picking up or putting down the load.
				npc.onRandomAnimation(Rnd.get(1, 3));
				if (TownLifeConfig.CHATTER && (Rnd.get(4) == 0))
				{
					say(npc, TownLifeLines.random(kind == Kind.DOCK_WORKER ? TownLifeLines.DOCK_WORKER : TownLifeLines.WORKER), null);
				}
				waitUntil = now + Rnd.get(6000, 12000);
				break;
			}
			case SWEEPER:
			{
				npc.onRandomAnimation(Rnd.get(1, 3));
				if (TownLifeConfig.CHATTER && (Rnd.get(6) == 0))
				{
					say(npc, TownLifeLines.random(TownLifeLines.SWEEPER), null);
				}
				waitUntil = now + Rnd.get(10000, 20000);
				break;
			}
			case PATROL:
			{
				if (TownLifeConfig.CHATTER && (Rnd.get(8) == 0))
				{
					say(npc, TownLifeLines.random(TownLifeLines.PATROL), null);
				}
				waitUntil = now + Rnd.get(3000, 7000);
				break;
			}
			case NIGHT_WATCH:
			{
				if (Rnd.get(5) < 2)
				{
					callTheHour(npc);
				}
				waitUntil = now + Rnd.get(3000, 6000);
				break;
			}
			case LAMPLIGHTER:
			{
				npc.broadcastPacket(new MagicSkillUse(npc, npc, LAMP_SPARK_SKILL, 1, 1500, 0));
				if (Rnd.get(4) == 0)
				{
					say(npc, TownLifeLines.random(TownLifeLines.LAMPLIGHTER), null);
				}
				waitUntil = now + 3500;
				break;
			}
			default:
			{
				waitUntil = now + Rnd.get(4000, 10000);
				break;
			}
		}
	}
	
	private void callTheHour(Npc npc)
	{
		final TownLifeManager.Clock clock = TownLifeManager.getInstance().getClock();
		if (clock.phase() == NightPhase.WITCHING_HOUR)
		{
			npc.broadcastSay(ChatType.NPC_SHOUT, TownLifeLines.random(TownLifeLines.NIGHT_WATCH_WITCHING), 1500);
			return;
		}
		
		final int hour = clock.gameHour();
		final String time = (hour >= 0) && (hour < TownLifeLines.HOURS.length) ? TownLifeLines.HOURS[hour] : "Night";
		npc.broadcastSay(ChatType.NPC_SHOUT, time + ", and all's well in " + town.name + "!", 1500);
	}
	
	private Location nextRoutePoint(List<Location> route)
	{
		if (route.isEmpty())
		{
			return town.randomHub();
		}
		
		_routeIndex = (_routeIndex + 1) % route.size();
		return route.get(_routeIndex);
	}
	
	/**
	 * Sets off somewhere.
	 * @param destination where
	 * @param faceOnArrival what it faces there, {@code null} for nothing
	 * @param headingOnArrival the heading it takes there, -1 for any
	 */
	void goTo(Location destination, WorldObject faceOnArrival, int headingOnArrival)
	{
		_destination = destination;
		_faceOnArrival = faceOnArrival;
		_headingOnArrival = headingOnArrival;
		_stuck = 0;
		_lastLeg = null;
	}
	
	/**
	 * One step of a walk: the next leg once the last one is done (long walks go from street point to street point).
	 * @param npc the npc
	 * @param destination where it goes
	 * @return {@code true} once it is there (or can't get any closer)
	 */
	boolean moveToward(Npc npc, Location destination)
	{
		if (destination == null)
		{
			return true;
		}
		
		final double distance = npc.calculateDistance2D(destination);
		if (distance <= ARRIVED)
		{
			return true;
		}
		
		if (npc.isMoving())
		{
			return false;
		}
		
		// Not moving and not there: the last leg is done, or it got stuck.
		if ((_lastPosition != null) && (npc.calculateDistance2D(_lastPosition) < 20))
		{
			_stuck++;
		}
		_lastPosition = new Location(npc.getX(), npc.getY(), npc.getZ());
		if (_stuck > 4)
		{
			// Can't get there: close enough.
			_stuck = 0;
			return true;
		}
		
		final Location leg = town.nextLeg(npc, destination, _stuck > 1 ? _lastLeg : null);
		if (leg == null)
		{
			_stuck++;
			return _stuck > 4;
		}
		
		_lastLeg = leg;
		npc.getAI().setIntention(Intention.MOVE_TO, leg);
		return false;
	}
	
	/**
	 * Greets a player walking by, now and then.
	 */
	private void maybeGreet(Npc npc, long now)
	{
		if (!TownLifeConfig.GREET_PLAYERS || (TownLifeConfig.GREET_CHANCE <= 0) || npc.isMoving())
		{
			return;
		}
		
		switch (kind)
		{
			case WANDERER:
			case PORTER:
			case SWEEPER:
			case PATROL:
			case FISHERMAN:
			case CRIER:
			case TAVERN:
			{
				break;
			}
			default:
			{
				return;
			}
		}
		
		for (Player player : World.getInstance().getVisibleObjectsInRange(npc, Player.class, 200))
		{
			if (player.isInvisible() || player.isAlikeDead() || player.isInStoreMode() || player.isFakePlayer())
			{
				continue;
			}
			
			final Long last = _greeted.get(player.getObjectId());
			if ((last != null) && ((now - last) < GREET_AGAIN))
			{
				continue;
			}
			
			_greeted.put(player.getObjectId(), now);
			if ((Rnd.get(100) < TownLifeConfig.GREET_CHANCE) && town.mayGreet(now))
			{
				face(npc, player);
				npc.onRandomAnimation(Rnd.get(1, 3));
				say(npc, TownLifeLines.random(TownLifeLines.GREETINGS), player.getName());
				waitUntil = Math.max(waitUntil, now + 3000);
			}
			break;
		}
		
		if (_greeted.size() > 50)
		{
			_greeted.values().removeIf(time -> (now - time) > GREET_AGAIN);
		}
	}
	
	/**
	 * @param npc the npc
	 * @param text what it says (%town% and %name% are filled in)
	 * @param playerName the name of the player spoken to, {@code null} for none
	 */
	void say(Npc npc, String text, String playerName)
	{
		npc.broadcastSay(ChatType.NPC_GENERAL, TownLifeLines.fill(text, town.name, playerName));
	}
	
	static void face(Npc npc, ILocational target)
	{
		if (target != null)
		{
			face(npc, LocationUtil.calculateHeadingFrom(npc, target));
		}
	}
	
	static void face(Npc npc, int heading)
	{
		npc.setHeading(heading);
		npc.broadcastPacket(new StopRotation(npc.getObjectId(), heading, 0));
	}
	
	/**
	 * @return what it is doing, for //townlife
	 */
	String describe()
	{
		final Npc npc = getNpc();
		final StringBuilder sb = new StringBuilder(kind.name().toLowerCase());
		if (festivalOnly)
		{
			sb.append(" (festival)");
		}
		sb.append(": ").append(_state.name().toLowerCase());
		if (npc != null)
		{
			sb.append(npc.isMoving() ? ", walking" : "").append(conversation != null ? ", talking" : "");
		}
		return sb.toString();
	}
}
