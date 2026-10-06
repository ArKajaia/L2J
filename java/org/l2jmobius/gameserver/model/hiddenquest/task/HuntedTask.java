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

import org.l2jmobius.commons.util.Rnd;
import org.l2jmobius.gameserver.ai.Intention;
import org.l2jmobius.gameserver.model.Location;
import org.l2jmobius.gameserver.model.actor.Npc;
import org.l2jmobius.gameserver.model.actor.Player;
import org.l2jmobius.gameserver.model.hiddenquest.HiddenQuestSession;
import org.l2jmobius.gameserver.model.hiddenquest.SpawnRole;
import org.l2jmobius.gameserver.model.zone.ZoneId;

/**
 * HUNTED: the player goes on with their day while ambushes (role {@code ambush}) find them; after the last one their leader (role {@code leader}) comes. Ambushes wait while the player is in a town.
 * <ul>
 * <li>{@code ambushes} - number of ambushes before the leader (3)</li>
 * <li>{@code ambushMin}/{@code ambushMax} - seconds between ambushes (120/240)</li>
 * <li>{@code leaderFlee} - at {@code fleeAt}% HP the leader flees to a lair 1800-2800 away, regains health while the player is far, and is joined by role {@code guard} there (false)</li>
 * </ul>
 * @author Mobius
 */
public class HuntedTask extends AbstractHiddenTask
{
	private static final int WARNING = 20;

	private final List<Npc> _ambush = new ArrayList<>();
	private int _wave;
	private int _nextAt;
	private boolean _warned;
	private Npc _leader;
	private boolean _fled;
	private boolean _guardsUp;
	private Location _lair;

	public HuntedTask(HiddenQuestSession session)
	{
		super(session);
	}

	@Override
	public String start(Player player)
	{
		if ((_definition.getRole("ambush") == null) || (_definition.getRole("leader") == null))
		{
			return "the quest has no hunters";
		}
		_nextAt = Rnd.get(40, 70);
		announce(player, text("start", "Go about your business. They will find you."));
		return null;
	}

	@Override
	protected void onTick(Player player, int second)
	{
		if (_wave < _params.getInt("ambushes", 3))
		{
			tickAmbush(player, second);
		}
		else
		{
			tickLeader(player, second);
		}
	}

	private void tickAmbush(Player player, int second)
	{
		if (!_ambush.isEmpty())
		{
			if (countAlive(_ambush) == 0)
			{
				_ambush.clear();
				_wave++;
				_warned = false;
				if (_wave >= _params.getInt("ambushes", 3))
				{
					_nextAt = second + 25;
					screen(player, text("ambushBeaten", "They lie still. But someone sent them..."));
				}
				else
				{
					_nextAt = second + Rnd.get(_params.getInt("ambushMin", 120), _params.getInt("ambushMax", 240));
					screen(player, text("ambushBeaten", "They lie still. But someone sent them..."));
				}
			}
			else if (farFrom(player, _ambush))
			{
				// The player got away (teleport): this group loses the trail, the next one is sent sooner.
				for (Npc npc : _ambush)
				{
					npc.deleteMe();
				}
				_ambush.clear();
				_nextAt = second + 30;
				_warned = false;
			}
			return;
		}

		if (!_warned && (second >= (_nextAt - WARNING)))
		{
			_warned = true;
			screen(player, text("warning", "You feel eyes on your back..."));
		}

		if ((second >= _nextAt) && canBeAttacked(player))
		{
			_ambush.addAll(spawnRole("ambush", player, 450, 650, player, 0));
			announce(player, text("ambush", "Ambush!"));
		}
	}

	private void tickLeader(Player player, int second)
	{
		if (_leader == null)
		{
			if (!_warned && (second >= (_nextAt - WARNING)))
			{
				_warned = true;
				screen(player, text("leaderWarning", "The ground trembles under heavy steps..."));
			}
			if ((second >= _nextAt) && canBeAttacked(player))
			{
				final SpawnRole role = _definition.getRole("leader");
				_leader = spawnMonster(role, randomPoint(player, 400, 600, false), player, null, -1);
				if (_leader == null)
				{
					fail("The hunt went cold.");
					return;
				}
				say(_leader, text("leaderLine", "So you are the one."));
				announce(player, text("leaderArrives", "The leader of the hunt has come for you!"));
			}
			return;
		}

		if (!isAlive(_leader))
		{
			return;
		}

		final int fleeAt = _params.getInt("fleeAt", 50);
		if (_params.getBoolean("leaderFlee", false) && !_fled && (_leader.getCurrentHp() <= ((_leader.getMaxHp() * fleeAt) / 100.0)))
		{
			_fled = true;
			_lair = randomPoint(_leader, _params.getInt("lairMin", 1800), _params.getInt("lairMax", 2800), false);
			say(_leader, text("fleeLine", "Not here. Not like this!"));
			_leader.asAttackable().clearAggroList();
			_leader.teleToLocation(_lair);
			_leader.getAI().setIntention(Intention.ACTIVE);
			setMarker(player, _lair);
			announce(player, text("flee", "It fled to its lair! Follow your radar before its wounds close."));
			return;
		}

		if (_fled)
		{
			final double distance = player.calculateDistance2D(_leader);
			if (!_guardsUp && (distance <= 1200))
			{
				_guardsUp = true;
				spawnRole("guard", _leader, 100, 250, player, 0);
				attack(_leader, player);
				say(_leader, text("lairLine", "You followed me here? Then die here."));
			}
			if ((distance > 1500) && (_leader.getCurrentHp() < _leader.getMaxHp()))
			{
				_leader.setCurrentHp(Math.min(_leader.getMaxHp(), _leader.getCurrentHp() + (_leader.getMaxHp() * 0.01)));
			}
			return;
		}

		// Not fled yet: the hunter does not let the player walk away.
		if ((player.calculateDistance2D(_leader) > 2500) && canBeAttacked(player))
		{
			_leader.teleToLocation(randomPoint(player, 300, 500, false));
			attack(_leader, player);
			say(_leader, text("leaderFollows", "You cannot run from me."));
		}
		else if (!_leader.isInCombat())
		{
			attack(_leader, player);
		}
	}

	private static boolean farFrom(Player player, List<Npc> npcs)
	{
		for (Npc npc : npcs)
		{
			if (isAlive(npc) && (player.calculateDistance2D(npc) < 2500))
			{
				return false;
			}
		}
		return true;
	}

	private static boolean canBeAttacked(Player player)
	{
		return !player.isInsideZone(ZoneId.PEACE) && !player.isInsideZone(ZoneId.TOWN) && !player.isDead() && !player.isTeleporting();
	}

	@Override
	protected void onNpcDeath(Npc npc, Player player)
	{
		if (npc == _leader)
		{
			complete();
		}
	}

	@Override
	public String getProgress()
	{
		final int ambushes = _params.getInt("ambushes", 3);
		if (_wave < ambushes)
		{
			return "Ambushes survived: " + _wave + "/" + ambushes;
		}
		return _leader == null ? "The leader is coming" : _fled ? "Hunt the leader down in its lair" : "Defeat the leader";
	}
}
