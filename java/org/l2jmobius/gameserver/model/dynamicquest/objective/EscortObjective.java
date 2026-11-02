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
import java.util.Collections;
import java.util.List;
import java.util.Random;

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
 * ESCORT: an npc ({@code escortee}, a QuestGuard, named {@code escorteeName} and titled {@code escorteeTitle} if set) follows the owner along a route of legs of {@code legMin}-{@code legMax}.
 * With {@code dest="TOWN"} the route heads for the nearest town with a Notice Board, with {@code "HOME"} for the board the quest was taken at: it takes as many legs as the road needs, from {@code legs}
 * to {@code maxLegs}, and ends at the town's board when that is enough (else it stops short, where the town's guards take over). With {@code "ROUTE"} it wanders the wilds for {@code legs} legs.
 * A {@code skittish="true"} escortee stops following when the owner gets more than {@code skittishRange} away and wanders off, until the owner comes back to it.
 * <p>
 * {@code ambushes} ambushes ({@code ambushCount} monsters each, the ones living there) wait somewhere along legs picked by the quest's seed, never on the first leg of a longer road; the
 * ambushers come from ahead, and the escortee stops until they are beaten. Each ambush attacks one way of {@code tactics} (RUSH: all at the escortee, SPLIT: half at the owner,
 * HARASS: all at the owner, and at the escortee once free), with {@code ambushText}, {@code splitText} or {@code harassText}. The escortee itself can't die: its meter ({@code meter}, lasting longer for bigger parties) drops by {@code drain} a
 * second for every attacker next to it. On the road it now and then says a line of the text pool {@code chatter}. Leaving it more than {@code abandonRange} away for {@code lostTime} seconds
 * fails, so does an empty meter. Starts at {@code at} (LAST: where the previous step ended).
 * </p>
 * @author Mobius
 */
public class EscortObjective extends AbstractObjective
{
	private static final int ARRIVE_RANGE = 200;
	private static final int TOUCH_RANGE = 200;
	private static final int AMBUSH_RANGE = 350;

	private final List<Location> _waypoints = new ArrayList<>();
	/** Where on each leg an ambush waits ({@code null}: a quiet leg). */
	private final List<Location> _ambushSpots = new ArrayList<>();
	private final List<Npc> _ambush = new ArrayList<>();
	private List<String> _chatter;
	private int _chatterIndex;
	private int _nextChatter;
	private Npc _escortee;
	private int _leg;
	private boolean _holding;
	private double _meter;
	private double _maxMeter;
	private int _away;
	private boolean _wandering;

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
		dress(_escortee, "escorteeName", "escorteeTitle");
		_escortee.setRunning();
		_maxMeter = _params.getInt("meter", 100) * _quest.getDifficulty().meterMultiplier();
		_meter = _maxMeter;

		buildRoute(start);
		placeAmbushes(start);
		_chatter = shuffledLines(_params.getString("chatter", null), "chatter");
		_nextChatter = 25 + random("chatterStart").nextInt(20);
		follow(owner);
		_quest.setMarker(_waypoints.get(0));
		_quest.say(_escortee, text("startText", "Lead on. I'll be right behind you."));
		return null;
	}

	private void buildRoute(Location start)
	{
		final Random random = random("route");
		final int minLegs = Math.max(1, _params.getInt("legs", 3));
		final int maxLegs = Math.max(minLegs, _params.getInt("maxLegs", minLegs + 4));
		final int legMin = _params.getInt("legMin", 2000);
		final int legMax = Math.max(legMin, _params.getInt("legMax", 3000));
		Location destination = null;
		final String dest = _params.getString("dest", "TOWN").toUpperCase();
		if (dest.equals("HOME"))
		{
			destination = getHome();
			_quest.putContext("dest", _quest.getContext("town", "town"));
		}
		else if (dest.equals("TOWN"))
		{
			final NoticeBoardSite site = DynamicQuestData.getInstance().getNearestBoard(start, 0);
			if (site != null)
			{
				destination = site.getLocation();
				_quest.putContext("dest", site.getTown());
			}
		}

		if (destination == null)
		{
			Location from = start;
			for (int i = 0; i < minLegs; i++)
			{
				from = point(from, legMin, legMax);
				_waypoints.add(from);
			}
			return;
		}

		// As many legs as the road needs: the last one ends at the town's board if the road is short enough.
		final double dx = destination.getX() - start.getX();
		final double dy = destination.getY() - start.getY();
		final double distance = Math.max(1, Math.sqrt((dx * dx) + (dy * dy)));
		final int legs = Math.max(minLegs, Math.min(maxLegs, (int) Math.round(distance / ((legMin + legMax) / 2.0))));
		final double step = Math.min(legMax, distance / legs);
		final boolean reachesTown = (step * legs) >= (distance - 1);
		final double ux = dx / distance;
		final double uy = dy / distance;
		for (int i = 0; i < legs; i++)
		{
			final boolean last = i == (legs - 1);
			if (last && reachesTown)
			{
				_waypoints.add(QuestSpawnUtil.findPoint(destination, 0, 150, true, 0));
				break;
			}
			// The road bends: each waypoint is a little to one side of the straight line.
			final double along = step * (i + 1);
			final double side = (random.nextDouble() - 0.5) * 0.5 * step;
			final Location ahead = new Location(start.getX() + (int) ((ux * along) - (uy * side)), start.getY() + (int) ((uy * along) + (ux * side)), start.getZ());
			_waypoints.add(QuestSpawnUtil.findPoint(ahead, 0, 300, last, 0));
		}
	}

	/**
	 * Picks the legs with an ambush, and where on each it waits.
	 */
	private void placeAmbushes(Location start)
	{
		final Random random = random("ambushes");
		final int legs = _waypoints.size();
		final List<Integer> candidates = new ArrayList<>();
		for (int i = legs > 1 ? 1 : 0; i < legs; i++)
		{
			candidates.add(i);
		}
		Collections.shuffle(candidates, random);
		final int amount = Math.max(0, Math.min(candidates.size(), _params.getInt("ambushes", Math.max(1, legs - 1))));
		for (int i = 0; i < legs; i++)
		{
			_ambushSpots.add(null);
		}
		for (int i = 0; i < amount; i++)
		{
			final int leg = candidates.get(i);
			final Location from = leg == 0 ? start : _waypoints.get(leg - 1);
			final Location to = _waypoints.get(leg);
			final double fraction = 0.3 + (random.nextDouble() * 0.45);
			_ambushSpots.set(leg, new Location(from.getX() + (int) ((to.getX() - from.getX()) * fraction), from.getY() + (int) ((to.getY() - from.getY()) * fraction), to.getZ()));
		}
	}

	/**
	 * The ambush of the current leg comes out, from ahead.
	 */
	private void springAmbush()
	{
		final Location spot = _ambushSpots.get(_leg);
		_ambushSpots.set(_leg, null);
		_holding = true;
		_escortee.getAI().setIntention(Intention.MOVE_TO, new Location(_escortee.getX(), _escortee.getY(), _escortee.getZ()));
		final Location waypoint = _waypoints.get(_leg);
		final double dx = waypoint.getX() - _escortee.getX();
		final double dy = waypoint.getY() - _escortee.getY();
		final double distance = Math.max(1, Math.sqrt((dx * dx) + (dy * dy)));
		final Location ahead = new Location(_escortee.getX() + (int) ((dx / distance) * 600), _escortee.getY() + (int) ((dy / distance) * 600), _escortee.getZ());
		final Random random = random("ambush" + _leg);
		final int amount = count("ambushCount", 3);
		// How they attack: all at the charge (RUSH), half at the leader (SPLIT), or all at the leader, to draw it away (HARASS).
		final Player owner = _quest.getOwner();
		final String[] tactics = _params.getString("tactics", "RUSH,SPLIT,HARASS").toUpperCase().split(",");
		final String tactic = owner != null ? tactics[random.nextInt(tactics.length)].trim() : "RUSH";
		for (int i = 0; i < amount; i++)
		{
			final boolean atLeader = tactic.equals("HARASS") || (tactic.equals("SPLIT") && ((i % 2) == 1));
			final Npc monster = _quest.spawnMonster(this, pickMonsterNear(spot != null ? spot : waypoint, random), point(ahead, 0, 350), _params.getInt("tierBonus", 0), title("ambushTitle"), atLeader ? owner : _escortee);
			if (monster != null)
			{
				_ambush.add(monster);
			}
		}
		switch (tactic)
		{
			case "SPLIT":
			{
				_quest.announce(text("splitText", "Ambush! Some go for you, the rest for your charge!"));
				break;
			}
			case "HARASS":
			{
				_quest.announce(text("harassText", "Ambush! They're after you - keep them off your charge!"));
				break;
			}
			default:
			{
				_quest.announce(text("ambushText", "Ambush! Protect your charge!"));
				break;
			}
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
			_meter = Math.min(_maxMeter, _meter + (_maxMeter / 10));
			_quest.say(_escortee, text("clearText", "They're gone. Let's move on!"));
			follow(owner);
			return;
		}

		// A skittish charge doesn't follow a leader who runs off: it wanders until the owner comes back.
		if (_params.getBoolean("skittish", false))
		{
			final double distance = owner.calculateDistance2D(_escortee);
			if (_wandering)
			{
				if (distance > 150)
				{
					if (!_escortee.isMoving() && ((second % 4) == 0))
					{
						_escortee.getAI().setIntention(Intention.MOVE_TO, point(_escortee, 80, 200));
					}
					return;
				}
				_wandering = false;
				_escortee.setRunning();
				_quest.say(_escortee, text("calmText", "Don't leave me again!"));
				follow(owner);
			}
			else if (distance > _params.getInt("skittishRange", 400))
			{
				_wandering = true;
				_escortee.setWalking();
				_quest.say(_escortee, text("scaredText", "Wait... where did you go? I'm scared!"));
				QuestSpawnUtil.screen(owner, text("wanderText", "Your charge has wandered off. Go back for it."));
				return;
			}
		}

		// The ambush of this leg, when the charge comes near its spot (or reaches the waypoint without passing it).
		final Location waypoint = _waypoints.get(_leg);
		final Location spot = _ambushSpots.get(_leg);
		if ((spot != null) && ((_escortee.calculateDistance2D(spot) <= AMBUSH_RANGE) || (_escortee.calculateDistance2D(waypoint) <= ARRIVE_RANGE)))
		{
			springAmbush();
			return;
		}

		if (_escortee.calculateDistance2D(waypoint) <= ARRIVE_RANGE)
		{
			if (_leg >= (_waypoints.size() - 1))
			{
				_quest.say(_escortee, text("arriveText", "We made it. Thank you!"));
				complete();
				return;
			}
			_leg++;
			_quest.setMarker(_waypoints.get(_leg));
			_quest.screen(getProgress());
		}
		else if (_escortee.getAI().getIntention() != Intention.FOLLOW)
		{
			follow(owner);
		}

		// Talk on the road.
		if (!_chatter.isEmpty() && (--_nextChatter <= 0))
		{
			_nextChatter = 45 + random("chatter" + second).nextInt(35);
			_quest.say(_escortee, _chatter.get(_chatterIndex++ % _chatter.size()));
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
		final String dest = _params.getString("dest", "TOWN").toUpperCase();
		return dest.equals("TOWN") || dest.equals("HOME") ? "Lead your charge safely towards {dest}." : "Lead your charge safely along the route on your radar.";
	}
}
