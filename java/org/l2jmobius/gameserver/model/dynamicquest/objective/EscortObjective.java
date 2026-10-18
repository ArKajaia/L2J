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
package org.l2jmobius.gameserver.model.dynamicquest.objective;

import java.util.ArrayList;
import java.util.List;

import org.l2jmobius.gameserver.ai.Intention;
import org.l2jmobius.gameserver.data.xml.DynamicQuestData;
import org.l2jmobius.gameserver.model.Location;
import org.l2jmobius.gameserver.model.actor.Npc;
import org.l2jmobius.gameserver.model.actor.Player;
import org.l2jmobius.gameserver.model.actor.instance.QuestGuard;
import org.l2jmobius.gameserver.model.dynamicquest.NoticeBoardSite;
import org.l2jmobius.gameserver.model.dynamicquest.ObjectiveTemplate;
import org.l2jmobius.gameserver.model.dynamicquest.QuestInstance;
import org.l2jmobius.gameserver.util.QuestSpawnUtil;

/**
 * ESCORT: an npc ({@code escortee}, a QuestGuard) follows the owner over {@code legs} legs. With {@code dest="TOWN"} the route heads for the nearest town with a Notice Board (each leg {@code legMin}-{@code legMax} long); with {@code "ROUTE"} it wanders the wilds.
 * <p>
 * At the end of every leg but the last an ambush ({@code ambushCount}, the monsters living there) attacks it, and it waits until the ambush is beaten. The escortee itself can't die: its meter ({@code meter}, lasting longer for bigger parties) drops by {@code drain} a second for every attacker next to it.
 * Leaving it more than {@code abandonRange} away for {@code lostTime} seconds fails, so does an empty meter. Starts at {@code at} (LAST: where the previous step ended).
 * </p>
 * @author Mobius
 */
public class EscortObjective extends AbstractObjective
{
	private static final int ARRIVE_RANGE = 200;
	private static final int TOUCH_RANGE = 200;

	private final List<Location> _waypoints = new ArrayList<>();
	private final List<Npc> _ambush = new ArrayList<>();
	private Npc _escortee;
	private int _leg;
	private boolean _holding;
	private double _meter;
	private double _maxMeter;
	private int _away;

	public EscortObjective(QuestInstance quest, ObjectiveTemplate template, int index)
	{
		super(quest, template, index);
	}

	@Override
	public String start(boolean restored)
	{
		final Player owner = _quest.getOwner();
		if (owner == null)
		{
			return "nobody to lead it";
		}

		final Location start = resolve(_params.getString("at", "LAST"));
		_escortee = _quest.spawn(this, _params.getInt("escortee", 0), point(start, 30, 120), 0);
		if (_escortee == null)
		{
			return "the escort could not join you";
		}
		if (_escortee instanceof QuestGuard)
		{
			((QuestGuard) _escortee).setPassive(true);
		}
		_escortee.setRunning();
		_maxMeter = _params.getInt("meter", 100) * _quest.getDifficulty().meterMultiplier();
		_meter = _maxMeter;

		buildRoute(start);
		follow(owner);
		_quest.setMarker(_waypoints.get(0));
		_quest.say(_escortee, text("startText", "Lead on. I'll be right behind you."));
		return null;
	}

	private void buildRoute(Location start)
	{
		final int legs = Math.max(1, _params.getInt("legs", 3));
		final int legMin = _params.getInt("legMin", 900);
		final int legMax = _params.getInt("legMax", 1500);
		Location destination = null;
		if ("TOWN".equalsIgnoreCase(_params.getString("dest", "TOWN")))
		{
			final NoticeBoardSite site = DynamicQuestData.getInstance().getNearestBoard(start, 0);
			if (site != null)
			{
				destination = site.getLocation();
				_quest.putContext("dest", site.getTown());
			}
		}

		Location from = start;
		for (int i = 0; i < legs; i++)
		{
			Location next;
			if (destination != null)
			{
				final double dx = destination.getX() - from.getX();
				final double dy = destination.getY() - from.getY();
				final double distance = Math.max(1, Math.sqrt((dx * dx) + (dy * dy)));
				final double step = Math.min(distance, legMax);
				final Location ahead = new Location(from.getX() + (int) ((dx / distance) * step), from.getY() + (int) ((dy / distance) * step), from.getZ());
				next = QuestSpawnUtil.findPoint(ahead, 0, 200, i == (legs - 1), 0);
			}
			else
			{
				next = point(from, legMin, legMax);
			}
			_waypoints.add(next);
			from = next;
		}
	}

	private void follow(Player owner)
	{
		_escortee.setTarget(owner);
		_escortee.getAI().setIntention(Intention.FOLLOW, owner);
	}

	@Override
	public void onTick(int second, List<Player> near)
	{
		final Player owner = _quest.getOwner();
		if (owner == null)
		{
			return;
		}
		if (!QuestSpawnUtil.isAlive(_escortee))
		{
			fail(text("failText", "Your charge is lost."));
			return;
		}
		_escortee.setCurrentHp(_escortee.getMaxHp());

		if (owner.calculateDistance2D(_escortee) > _params.getInt("abandonRange", 2500))
		{
			if (++_away == 1)
			{
				QuestSpawnUtil.screen(owner, text("lostText", "You left your charge behind! Go back."));
			}
			if (_away > _params.getInt("lostTime", 40))
			{
				fail(text("abandonText", "You abandoned your charge."));
				return;
			}
		}
		else
		{
			_away = 0;
		}

		// Attackers next to the escortee drain its meter.
		for (Npc attacker : _ambush)
		{
			if (!QuestSpawnUtil.isAlive(attacker))
			{
				continue;
			}
			if (attacker.calculateDistance2D(_escortee) <= TOUCH_RANGE)
			{
				_meter -= _params.getDouble("drain", 1);
			}
			else if (!attacker.isInCombat())
			{
				QuestSpawnUtil.attack(attacker, _escortee);
			}
		}
		if (_meter <= 0)
		{
			fail(text("failText", "Your charge is lost."));
			return;
		}

		if (_holding)
		{
			if ((second % 10) == 0)
			{
				_quest.screen(getProgress());
			}
			if (QuestSpawnUtil.countAlive(_ambush) > 0)
			{
				return;
			}
			_ambush.clear();
			_holding = false;
			_leg++;
			_meter = Math.min(_maxMeter, _meter + (_maxMeter / 10));
			_quest.say(_escortee, text("clearText", "They're gone. Let's move on!"));
			_quest.setMarker(_waypoints.get(_leg));
			follow(owner);
			return;
		}

		final Location waypoint = _waypoints.get(_leg);
		if (_escortee.calculateDistance2D(waypoint) <= ARRIVE_RANGE)
		{
			if (_leg >= (_waypoints.size() - 1))
			{
				_quest.say(_escortee, text("arriveText", "We made it. Thank you!"));
				complete();
				return;
			}

			_holding = true;
			_escortee.getAI().setIntention(Intention.MOVE_TO, waypoint);
			final int amount = count("ambushCount", 3);
			for (int i = 0; i < amount; i++)
			{
				final Npc monster = _quest.spawnMonster(this, pickMonsterNear(waypoint, random("ambush" + _leg)), point(waypoint, 500, 700), _params.getInt("tierBonus", 0), title("ambushTitle"), _escortee);
				if (monster != null)
				{
					_ambush.add(monster);
				}
			}
			_quest.announce(text("ambushText", "Ambush! Protect your charge!"));
		}
		else if (_escortee.getAI().getIntention() != Intention.FOLLOW)
		{
			follow(owner);
		}
	}

	@Override
	public int getMeterPercent()
	{
		return _maxMeter <= 0 ? -1 : (int) Math.max(0, Math.round((_meter * 100) / _maxMeter));
	}

	@Override
	public String getProgress()
	{
		return "Leg " + Math.min(_leg + 1, Math.max(1, _waypoints.size())) + "/" + Math.max(1, _waypoints.size()) + ", " + text("meterName", "safety") + " " + Math.max(0, getMeterPercent()) + "%" + (_holding ? ", ambush!" : "");
	}

	@Override
	protected String defaultDescription()
	{
		return "TOWN".equalsIgnoreCase(_params.getString("dest", "TOWN")) ? "Lead your charge safely towards {dest}." : "Lead your charge safely along the route on your radar.";
	}
}
