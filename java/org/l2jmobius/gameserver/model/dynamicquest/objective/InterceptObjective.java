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
import java.util.Random;

import org.l2jmobius.gameserver.ai.Intention;
import org.l2jmobius.gameserver.model.Location;
import org.l2jmobius.gameserver.model.actor.Creature;
import org.l2jmobius.gameserver.model.actor.Npc;
import org.l2jmobius.gameserver.model.actor.Player;
import org.l2jmobius.gameserver.model.dynamicquest.ObjectiveTemplate;
import org.l2jmobius.gameserver.model.dynamicquest.QuestInstance;
import org.l2jmobius.gameserver.util.QuestSpawnUtil;

/**
 * INTERCEPT: a band of the place's monsters - a leader ({@code leaderTitle}, {@code leaderTierBonus}) and {@code guards} guards ({@code guardTitle}) - sets out from {@code at} and walks {@code legs} legs of {@code legMin}-{@code legMax} towards a meeting
 * point. Killing the leader before it arrives does it; its arrival fails it ({@code arriveText}). The band only walks while it isn't fighting, so a player who catches it holds it up.
 * @author Mobius
 */
public class InterceptObjective extends AbstractObjective
{
	private static final int ARRIVE_RANGE = 200;

	private final List<Location> _route = new ArrayList<>();
	private final List<Npc> _guards = new ArrayList<>();
	private Npc _leader;
	private int _leg;

	public InterceptObjective(QuestInstance quest, ObjectiveTemplate template, int index)
	{
		super(quest, template, index);
	}

	@Override
	public String start(boolean restored)
	{
		final Location start = point(resolve(_params.getString("at", "AREA")), _params.getInt("spawnMin", 0), _params.getInt("spawnMax", 400));
		final int legs = Math.max(1, _params.getInt("legs", 4));
		Location from = start;
		for (int i = 0; i < legs; i++)
		{
			from = point(from, _params.getInt("legMin", 900), _params.getInt("legMax", 1400));
			_route.add(from);
		}

		final Random random = random("band");
		_leader = _quest.spawnMonster(this, pickMonster(random), start, _params.getInt("leaderTierBonus", 2), text("leaderTitle", "Smuggler Boss"), null);
		if (_leader == null)
		{
			return "the band could not be found";
		}
		_leader.setWalking();
		final int guards = count("guards", 3);
		for (int i = 0; i < guards; i++)
		{
			final Npc guard = _quest.spawnMonster(this, pickMonster(random), point(start, 60, 200), _params.getInt("tierBonus", 0), title("guardTitle"), null);
			if (guard != null)
			{
				guard.setWalking();
				_guards.add(guard);
			}
		}
		_quest.putLocation("last", start);
		_quest.putContext("leaderName", _leader.getTitle());
		_quest.setMarker(start);
		return null;
	}

	@Override
	public void onNpcDeath(Npc npc, Creature killer)
	{
		if (npc != _leader)
		{
			return;
		}
		if ((killer != null) && (killer.asPlayer() != null))
		{
			_quest.addContribution(killer.asPlayer().getObjectId(), 3);
		}
		_quest.putLocation("last", npc.getLocation());
		_quest.announce(text("killText", "The band is broken!"));
		complete();
	}

	@Override
	public void onTick(int second, List<Player> near)
	{
		if (!QuestSpawnUtil.isAlive(_leader))
		{
			return;
		}

		// The radar follows the band.
		if ((second % 5) == 0)
		{
			_quest.setMarker(_leader.getLocation());
		}

		final Location waypoint = _route.get(_leg);
		if (_leader.calculateDistance2D(waypoint) <= ARRIVE_RANGE)
		{
			if (_leg >= (_route.size() - 1))
			{
				fail(text("arriveText", "The band reached the meeting point and vanished. Too late."));
				return;
			}
			_leg++;
			_quest.screen(getProgress());
		}

		if (_leader.isInCombat())
		{
			return;
		}
		if (!_leader.isMoving())
		{
			_leader.getAI().setIntention(Intention.MOVE_TO, _route.get(_leg));
		}
		for (Npc guard : _guards)
		{
			if (QuestSpawnUtil.isAlive(guard) && !guard.isInCombat() && !guard.isMoving() && (guard.calculateDistance2D(_leader) > 150))
			{
				guard.getAI().setIntention(Intention.MOVE_TO, point(_leader, 40, 120));
			}
		}
	}

	@Override
	public String getProgress()
	{
		return QuestSpawnUtil.isAlive(_leader) ? "The band is on leg " + Math.min(_leg + 1, _route.size()) + " of " + _route.size() : "The band is broken";
	}

	@Override
	protected String defaultDescription()
	{
		return "Stop {leaderName} and the band before they reach their meeting point.";
	}
}
