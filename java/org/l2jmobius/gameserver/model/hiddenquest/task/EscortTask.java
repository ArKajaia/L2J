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
import org.l2jmobius.gameserver.model.actor.instance.QuestGuard;
import org.l2jmobius.gameserver.model.hiddenquest.HiddenQuestSession;
import org.l2jmobius.gameserver.model.hiddenquest.SpawnRole;

/**
 * ESCORT: an NPC ({@code escorteeId}, a QuestGuard) follows the player to {@code stops} stops ({@code stopId}) and works {@code workTime} seconds at each while role {@code ambush} attacks it.
 * <p>
 * The escortee itself can't die: what can run out is its meter ({@code meter}, 100), drained by {@code drain} per second for every attacker next to it. Leaving it more than {@code abandonRange} away for {@code lostTime} seconds fails the task.
 * </p>
 * @author Mobius
 */
public class EscortTask extends AbstractHiddenTask
{
	private static final int ARRIVE_RANGE = 200;
	private static final int TOUCH_RANGE = 200;

	private final List<Npc> _ambush = new ArrayList<>();
	private Npc _escortee;
	private Npc _stopNpc;
	private Location _stop;
	private int _index;
	private boolean _working;
	private int _workLeft;
	private double _meter;
	private int _away;

	public EscortTask(HiddenQuestSession session)
	{
		super(session);
	}

	private int getStops()
	{
		return Math.max(1, _params.getInt("stops", 4));
	}

	@Override
	public String start(Player player)
	{
		_escortee = spawnNpc(_params.getInt("escorteeId", 0), randomPoint(player, 60, 100, true), player.getHeading());
		if (_escortee == null)
		{
			return "the escort could not join you";
		}
		if (_escortee instanceof QuestGuard)
		{
			((QuestGuard) _escortee).setPassive(true);
		}
		_escortee.setRunning();
		_meter = _params.getInt("meter", 100);
		follow(player);
		say(_escortee, text("escorteeStart", "Lead on. I'll be right behind you."));
		nextStop(player, _escortee);
		announce(player, text("start", "Lead the way to the first stop on your radar."));
		return null;
	}

	private void follow(Player player)
	{
		_escortee.setTarget(player);
		_escortee.getAI().setIntention(Intention.FOLLOW, player);
	}

	private void nextStop(Player player, Npc from)
	{
		_stop = randomPoint(from, _params.getInt("stopMin", 900), _params.getInt("stopMax", 1500), false);
		_stopNpc = spawnNpc(_params.getInt("stopId", 0), _stop, 0);
		setMarker(player, _stop);
	}

	@Override
	protected void onTick(Player player, int second)
	{
		if (!isAlive(_escortee))
		{
			fail(text("failMeter", "Your charge is lost."));
			return;
		}
		_escortee.setCurrentHp(_escortee.getMaxHp());

		if (player.calculateDistance2D(_escortee) > _params.getInt("abandonRange", 2500))
		{
			if (++_away == 1)
			{
				screen(player, text("lost", "You left your charge behind! Go back."));
			}
			if (_away > _params.getInt("lostTime", 30))
			{
				fail(text("failAbandoned", "You abandoned your charge."));
				return;
			}
		}
		else
		{
			_away = 0;
		}

		if (_working)
		{
			tickWork(player, second);
			return;
		}

		if (_escortee.calculateDistance2D(_stop) <= ARRIVE_RANGE)
		{
			_working = true;
			_workLeft = Math.max(10, _params.getInt("workTime", 45));
			_escortee.getAI().setIntention(Intention.MOVE_TO, _stop);
			say(_escortee, text("work", "Here. Keep them off me while I work!"));
			final SpawnRole role = _definition.getRole("ambush");
			if (role != null)
			{
				_ambush.addAll(spawnRole("ambush", _stop, 500, 700, _escortee, role.getCount() + (_index / 2)));
			}
			announce(player, text("ambush", "They are coming for your charge!"));
		}
		else if (_escortee.getAI().getIntention() != Intention.FOLLOW)
		{
			follow(player);
		}
	}

	private void tickWork(Player player, int second)
	{
		_workLeft--;
		for (Npc attacker : _ambush)
		{
			if (!isAlive(attacker))
			{
				continue;
			}
			if (attacker.calculateDistance2D(_escortee) <= TOUCH_RANGE)
			{
				_meter -= _params.getDouble("drain", 1);
			}
			else if (!attacker.isInCombat())
			{
				attack(attacker, _escortee);
			}
		}

		if (_meter <= 0)
		{
			fail(text("failMeter", "Your charge is lost."));
			return;
		}

		if ((second % 10) == 0)
		{
			screen(player, getProgress());
		}

		if (_workLeft > 0)
		{
			return;
		}

		if (countAlive(_ambush) > 0)
		{
			if ((second % 5) == 0)
			{
				screen(player, text("clear", "Drive them off first!"));
			}
			return;
		}

		_ambush.clear();
		_working = false;
		_index++;
		final String litTitle = text("stopDoneTitle", null);
		if ((_stopNpc != null) && (litTitle != null))
		{
			_stopNpc.setTitle(litTitle);
			_stopNpc.broadcastInfo();
		}
		say(_escortee, text("stopDone", "Done. On to the next!"));
		if (_index >= getStops())
		{
			complete();
			return;
		}

		_meter = Math.min(_params.getInt("meter", 100), _meter + 10);
		nextStop(player, _escortee);
		follow(player);
		announce(player, text("next", "Lead on to the next stop.") + " (" + (_index + 1) + "/" + getStops() + ")");
	}

	@Override
	public String getProgress()
	{
		return "Stop " + Math.min(_index + 1, getStops()) + "/" + getStops() + ", " + text("meterName", "strength") + " " + Math.max(0, (int) _meter) + "%" + (_working ? ", " + Math.max(0, _workLeft) + "s" : "");
	}
}
