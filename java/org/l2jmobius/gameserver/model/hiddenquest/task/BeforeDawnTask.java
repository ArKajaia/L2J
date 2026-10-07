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
package org.l2jmobius.gameserver.model.hiddenquest.task;

import java.util.ArrayList;
import java.util.List;

import org.l2jmobius.gameserver.ai.Intention;
import org.l2jmobius.gameserver.model.Location;
import org.l2jmobius.gameserver.model.actor.Npc;
import org.l2jmobius.gameserver.model.actor.Player;
import org.l2jmobius.gameserver.model.hiddenquest.HiddenQuestSession;

/**
 * BEFORE_DAWN: follow a will-o'-the-wisp ({@code wispId}) from place to place to what it guards, before the sun rises (a Midnight quest's task ends at dawn).
 * <ul>
 * <li>The wisp drifts {@code legMin}-{@code legMax} away, {@code legs} times, and waits for the player at each stop ({@code followRange}). The radar follows it.</li>
 * <li>Every {@code ambushEvery} stops, role {@code ambush} jumps the player; the wisp waits until they are beaten.</li>
 * <li>At the last stop the wisp sinks into the cache ({@code cacheId}) and role {@code guardian} rises around it. Once they are beaten, walking up to the cache ({@code openRange}) ends the task.</li>
 * <li>Falling more than {@code lostRange} behind for {@code lostTime} seconds loses the wisp.</li>
 * </ul>
 * @author Mobius
 */
public class BeforeDawnTask extends AbstractHiddenTask
{
	/** The wisp counts as at its stop this close to it. */
	private static final int STOP_RANGE = 120;
	/** Seconds a wisp may stand still short of its stop before it is put there. */
	private static final int STUCK_TIME = 4;

	private final List<Npc> _ambush = new ArrayList<>();
	private final List<Npc> _guardians = new ArrayList<>();
	private Npc _wisp;
	private Npc _cache;
	private Location _stop;
	private int _leg;
	private boolean _arrived;
	private boolean _ambushed;
	private int _stuck;
	private int _lost;

	public BeforeDawnTask(HiddenQuestSession session)
	{
		super(session);
	}

	private int getLegs()
	{
		return Math.max(1, _params.getInt("legs", 5));
	}

	@Override
	public String start(Player player)
	{
		if ((_params.getInt("wispId", 0) <= 0) || (_params.getInt("cacheId", 0) <= 0))
		{
			return "the quest has no wisp to follow";
		}

		_wisp = spawnNpc(_params.getInt("wispId", 0), randomPoint(player, 150, 300, true), 0);
		if (_wisp == null)
		{
			return "the wisp would not come";
		}
		_wisp.setRunning();
		announce(player, text("start", "Follow the wisp before the sun rises. Your radar shows it."));
		nextLeg(player);
		return null;
	}

	/**
	 * The wisp drifts on to its next stop.
	 * @param player the player
	 */
	private void nextLeg(Player player)
	{
		_leg++;
		_arrived = false;
		_ambushed = false;
		_stuck = 0;
		_stop = randomPoint(_wisp, _params.getInt("legMin", 700), _params.getInt("legMax", 1100), false);
		_wisp.getAI().setIntention(Intention.MOVE_TO, _stop);
		say(_wisp, text("wispLine", null));
		if (_leg > 1)
		{
			screen(player, text("next", "The wisp drifts on. Follow it!") + " (" + _leg + "/" + getLegs() + ")");
		}
		setMarker(player, _wisp.getLocation());
	}

	@Override
	protected void onTick(Player player, int second)
	{
		if (_cache != null)
		{
			tickCache(player, second);
			return;
		}

		if (!isAlive(_wisp))
		{
			fail(text("failLost", "The wisp's light is gone."));
			return;
		}

		// Too far behind for too long: the wisp is lost.
		final double distance = player.calculateDistance2D(_wisp);
		if (distance > _params.getInt("lostRange", 2500))
		{
			_lost++;
			if (_lost >= _params.getInt("lostTime", 40))
			{
				fail(text("failLost", "You lost the wisp in the dark."));
				return;
			}
			if ((_lost % 10) == 1)
			{
				screen(player, text("lost", "The wisp's light fades in the distance! Follow your radar."));
			}
		}
		else
		{
			_lost = 0;
		}

		if ((second % 3) == 0)
		{
			setMarker(player, _wisp.getLocation());
		}

		// The wisp waits while its ambush is fought.
		if (!_ambush.isEmpty())
		{
			if (countAlive(_ambush) > 0)
			{
				return;
			}
			_ambush.clear();
			screen(player, text("ambushBeaten", "The wisp flickers again."));
		}

		if (!_arrived)
		{
			if (_wisp.calculateDistance2D(_stop) <= STOP_RANGE)
			{
				_arrived = true;
			}
			else if (!_wisp.isMoving())
			{
				// Stuck on something: it slips to its stop, as wisps do.
				if (++_stuck >= STUCK_TIME)
				{
					_wisp.teleToLocation(_stop);
					_arrived = true;
				}
				else
				{
					_wisp.getAI().setIntention(Intention.MOVE_TO, _stop);
				}
			}
			return;
		}

		if (distance > _params.getInt("followRange", 350))
		{
			return;
		}

		final int ambushEvery = _params.getInt("ambushEvery", 2);
		if (!_ambushed && (ambushEvery > 0) && ((_leg % ambushEvery) == 0) && (_definition.getRole("ambush") != null))
		{
			_ambushed = true;
			_ambush.addAll(spawnRole("ambush", player, 300, 500, player, 0));
			if (!_ambush.isEmpty())
			{
				announce(player, text("ambush", "Something in the dark wants the wisp!"));
				return;
			}
		}

		if (_leg >= getLegs())
		{
			reachCache(player);
		}
		else
		{
			nextLeg(player);
		}
	}

	/**
	 * The wisp sinks into what it guards, and its guardians rise.
	 * @param player the player
	 */
	private void reachCache(Player player)
	{
		final Location location = _wisp.getLocation();
		_cache = spawnNpc(_params.getInt("cacheId", 0), location, 0);
		if (_cache == null)
		{
			fail("The wisp went out.");
			return;
		}

		say(_wisp, text("wispEnd", null));
		_wisp.deleteMe();
		_guardians.addAll(spawnRole("guardian", _cache, 150, 300, player, 0));
		setMarker(player, location);
		announce(player, text(_guardians.isEmpty() ? "cache" : "guardians", "The wisp sinks into the ground. Something guards this place!"));
	}

	private void tickCache(Player player, int second)
	{
		if (countAlive(_guardians) > 0)
		{
			if ((second % 15) == 0)
			{
				screen(player, getProgress());
			}
			return;
		}

		if (player.calculateDistance2D(_cache) <= _params.getInt("openRange", 150))
		{
			say(_cache, text("cacheLine", null));
			complete();
			return;
		}

		if ((second % 10) == 0)
		{
			screen(player, text("openCache", "Its guardians are gone. Go to it."));
		}
	}

	@Override
	public String getProgress()
	{
		if (_cache != null)
		{
			final int guardians = countAlive(_guardians);
			return guardians > 0 ? "Guardians left: " + guardians : text("openCache", "Its guardians are gone. Go to it.");
		}
		return "Following the wisp: " + Math.min(_leg, getLegs()) + "/" + getLegs() + (!_ambush.isEmpty() ? ", ambushed!" : "");
	}
}
