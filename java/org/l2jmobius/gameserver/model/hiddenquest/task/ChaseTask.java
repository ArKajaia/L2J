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
import org.l2jmobius.gameserver.geoengine.GeoEngine;
import org.l2jmobius.gameserver.model.Location;
import org.l2jmobius.gameserver.model.actor.Npc;
import org.l2jmobius.gameserver.model.actor.Player;
import org.l2jmobius.gameserver.model.hiddenquest.HiddenQuestSession;

/**
 * CHASE: catch {@code count} runaways one after another. The radar follows the current one.
 * <ul>
 * <li>{@code mode="WISP"}: a wisp NPC ({@code npcId}) drifts away when the player comes near; touching it ({@code catchRange}) catches it. It fades after {@code fadeTime} seconds. Role {@code catch}, if present, attacks the player at each catch.</li>
 * <li>{@code mode="COURIER"}: a courier (role {@code courier}, with role {@code guard}) walks to an escape point {@code escapeMin}-{@code escapeMax} away; killing it catches it, reaching the point lets it escape.</li>
 * <li>{@code maxMisses} - wisps faded or couriers escaped that are forgiven (1)</li>
 * </ul>
 * @author Mobius
 */
public class ChaseTask extends AbstractHiddenTask
{
	private final boolean _courierMode;
	private final List<Npc> _guards = new ArrayList<>();
	private Npc _target;
	private Location _escape;
	private int _caught;
	private int _misses;
	private int _second;
	private int _spawnedAt;
	private int _nextAt;

	public ChaseTask(HiddenQuestSession session)
	{
		super(session);
		_courierMode = "COURIER".equalsIgnoreCase(_params.getString("mode", "WISP"));
	}

	@Override
	public String start(Player player)
	{
		if (_courierMode ? _definition.getRole("courier") == null : _params.getInt("npcId", 0) <= 0)
		{
			return "the quest has nothing to chase";
		}
		_nextAt = 5;
		announce(player, text("start", "Watch your radar. Be quick."));
		return null;
	}

	private int getCount()
	{
		return Math.max(1, _params.getInt("count", 5));
	}

	@Override
	protected void onTick(Player player, int second)
	{
		_second = second;
		if (_target == null)
		{
			if (second >= _nextAt)
			{
				if (_courierMode)
				{
					spawnCourier(player);
				}
				else
				{
					spawnWisp(player);
				}
			}
			return;
		}

		if (_courierMode)
		{
			tickCourier(player, second);
		}
		else
		{
			tickWisp(player, second);
		}
	}

	// Wisps.

	private void spawnWisp(Player player)
	{
		final Location location = randomPoint(player, _params.getInt("spawnMin", 900), _params.getInt("spawnMax", 1600), false);
		_target = spawnNpc(_params.getInt("npcId", 0), location, 0);
		if (_target == null)
		{
			fail("The trail went cold.");
			return;
		}
		_target.setWalking();
		_spawnedAt = _second;
		setMarker(player, location);
		screen(player, text("appears", "Something flickers in the distance. Your radar marks it.") + " (" + (_caught + 1) + "/" + getCount() + ")");
	}

	private void tickWisp(Player player, int second)
	{
		final double distance = player.calculateDistance2D(_target);
		if (distance <= _params.getInt("catchRange", 120))
		{
			_caught++;
			say(_target, text("caught", "Ah... you have me."));
			_target.deleteMe();
			_target = null;
			if (_definition.getRole("catch") != null)
			{
				spawnRole("catch", player, 250, 450, player, 0);
				screen(player, text("catchAmbush", "Something comes to take it back!"));
			}
			if (_caught >= getCount())
			{
				complete();
				return;
			}
			_nextAt = second + 8;
			screen(player, "Caught " + _caught + "/" + getCount());
			return;
		}

		if ((second - _spawnedAt) >= _params.getInt("fadeTime", 300))
		{
			say(_target, text("faded", "Too slow..."));
			_target.deleteMe();
			_target = null;
			if (++_misses > _params.getInt("maxMisses", 1))
			{
				fail(text("failMissed", "Too many slipped through your fingers."));
				return;
			}
			_nextAt = second + 8;
			screen(player, text("missed", "It faded away. Another will come."));
			return;
		}

		if (distance > 4000)
		{
			// The player went far away: it waits for them somewhere new.
			final Location location = randomPoint(player, _params.getInt("spawnMin", 900), _params.getInt("spawnMax", 1600), false);
			_target.teleToLocation(location);
			setMarker(player, location);
			return;
		}

		if ((distance <= _params.getInt("fleeRange", 450)) && !_target.isMoving())
		{
			final double dx = _target.getX() - player.getX();
			final double dy = _target.getY() - player.getY();
			final double length = Math.max(1, Math.sqrt((dx * dx) + (dy * dy)));
			final int tx = _target.getX() + (int) ((dx / length) * 350);
			final int ty = _target.getY() + (int) ((dy / length) * 350);
			final Location away = GeoEngine.getInstance().getValidLocation(_target.getX(), _target.getY(), _target.getZ(), tx, ty, _target.getZ(), _target.getInstanceId());
			_target.getAI().setIntention(Intention.MOVE_TO, away);
		}

		if ((second % 3) == 0)
		{
			setMarker(player, _target.getLocation());
		}
	}

	// Couriers.

	private void spawnCourier(Player player)
	{
		final Location location = randomPoint(player, _params.getInt("spawnMin", 1200), _params.getInt("spawnMax", 1800), false);
		_escape = randomPoint(location, _params.getInt("escapeMin", 3000), _params.getInt("escapeMax", 4000), false);
		_target = spawnMonster(_definition.getRole("courier"), location, null, null, -1);
		if (_target == null)
		{
			fail("The trail went cold.");
			return;
		}
		_guards.clear();
		_guards.addAll(spawnRole("guard", location, 60, 150, null, 0));
		_target.setWalking();
		for (Npc guard : _guards)
		{
			guard.setWalking();
		}
		run();
		setMarker(player, location);
		announce(player, text("appears", "A courier is on the move! Your radar marks him.") + " (" + (_caught + 1) + "/" + getCount() + ")");
	}

	private void run()
	{
		_target.getAI().setIntention(Intention.MOVE_TO, _escape);
		for (Npc guard : _guards)
		{
			if (isAlive(guard) && !guard.isInCombat())
			{
				guard.getAI().setIntention(Intention.MOVE_TO, randomPoint(_escape, 50, 120, true));
			}
		}
	}

	private void tickCourier(Player player, int second)
	{
		if (!isAlive(_target))
		{
			return;
		}

		if (_target.calculateDistance2D(_escape) <= 200)
		{
			_target.deleteMe();
			_target = null;
			for (Npc guard : _guards)
			{
				guard.deleteMe();
			}
			_guards.clear();
			if (++_misses > _params.getInt("maxMisses", 1))
			{
				fail(text("failEscaped", "Too many got away."));
				return;
			}
			_nextAt = second + 15;
			screen(player, text("escaped", "He got away! The next one won't."));
			return;
		}

		if (!_target.isInCombat() && !_target.isMoving())
		{
			run();
		}

		if ((second % 3) == 0)
		{
			setMarker(player, _target.getLocation());
		}
	}

	@Override
	protected void onNpcDeath(Npc npc, Player player)
	{
		if (!_courierMode || (npc != _target))
		{
			return;
		}

		_caught++;
		_target = null;
		if (_caught >= getCount())
		{
			complete();
			return;
		}
		_nextAt = _second + 15;
		screen(player, text("courierCaught", "One less courier.") + " (" + _caught + "/" + getCount() + ")");
	}

	@Override
	public String getProgress()
	{
		return "Caught " + _caught + "/" + getCount() + (_misses > 0 ? ", lost " + _misses : "");
	}
}
