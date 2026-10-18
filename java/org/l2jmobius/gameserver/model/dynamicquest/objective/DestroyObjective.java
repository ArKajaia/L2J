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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import org.l2jmobius.gameserver.model.Location;
import org.l2jmobius.gameserver.model.actor.Creature;
import org.l2jmobius.gameserver.model.actor.Npc;
import org.l2jmobius.gameserver.model.actor.Player;
import org.l2jmobius.gameserver.model.dynamicquest.HuntingGround;
import org.l2jmobius.gameserver.model.dynamicquest.ObjectiveTemplate;
import org.l2jmobius.gameserver.model.dynamicquest.QuestInstance;
import org.l2jmobius.gameserver.model.dynamicquest.WorldMonsterIndex;
import org.l2jmobius.gameserver.util.QuestSpawnUtil;

/**
 * DESTROY: burn {@code nests} nests ({@code nestNpc}) spread within {@code radius} of the hunting ground. While a player is near, each nest spawns one of the place's monsters every {@code spawnEvery} seconds, up to {@code maxAlive} at a time.
 * A nest burns after a player stands next to it for {@code burnTime} seconds. On escalation the nests spawn twice as fast and a brood mother ({@code broodTitle}, {@code broodTierBonus}) appears, who must die too.
 * With {@code foreign="true"} the brood comes from a hunting ground of the same level far away (the context's {@code foreignLand} names its nearest town): rifts pouring out another land.
 * @author Mobius
 */
public class DestroyObjective extends AbstractObjective
{
	private static class Nest
	{
		final Npc _npc;
		final List<Npc> _brood = new ArrayList<>();
		int _nextSpawn;
		int _burn;

		Nest(Npc npc)
		{
			_npc = npc;
		}
	}

	private final List<Nest> _nests = new ArrayList<>();
	private final Map<Npc, Nest> _byNpc = new HashMap<>();
	private int _total;
	private int _destroyed;
	private int _spawnEvery;
	private final List<Integer> _foreign = new ArrayList<>();
	private Npc _mother;
	private boolean _motherSlain = true;

	public DestroyObjective(QuestInstance quest, ObjectiveTemplate template, int index)
	{
		super(quest, template, index);
	}

	@Override
	public String start(boolean restored)
	{
		final Location area = _quest.getArea();
		if (area == null)
		{
			return "no nests to find";
		}
		if (_params.getBoolean("foreign", false))
		{
			pickForeignLand(area);
		}
		_total = _quest.getDifficulty().count(_params.getInt("nests", 3), false);
		_spawnEvery = Math.max(5, _params.getInt("spawnEvery", 20));
		for (int i = 0; i < _total; i++)
		{
			final Npc npc = _quest.spawn(this, _params.getInt("nestNpc", 0), point(area, 200, _params.getInt("radius", 1200)), 0);
			if (npc != null)
			{
				final Nest nest = new Nest(npc);
				nest._nextSpawn = 5 + (i * 3);
				_nests.add(nest);
				_byNpc.put(npc, nest);
			}
		}
		if (_nests.isEmpty())
		{
			return "no nests to find";
		}
		_total = _nests.size();
		_quest.putContext("nests" + _index, String.valueOf(_total));
		_quest.setMarker(_nests.get(0)._npc.getLocation());
		return null;
	}

	/**
	 * Picks (from the quest's seed) a hunting ground of the quest's level far from here, whose monsters the rifts pour out.
	 */
	private void pickForeignLand(Location area)
	{
		final Random random = random("foreign");
		HuntingGround best = null;
		double bestDistance = -1;
		for (int attempt = 0; attempt < 6; attempt++)
		{
			final HuntingGround ground = WorldMonsterIndex.getInstance().pick(_quest.getDifficulty().getQuestLevel(), random, null, 0);
			if ((ground == null) || ground.getNpcIds().isEmpty())
			{
				continue;
			}
			final double distance = Math.hypot(area.getX() - ground.getCenter().getX(), area.getY() - ground.getCenter().getY());
			if (distance > bestDistance)
			{
				best = ground;
				bestDistance = distance;
			}
			if (distance > 30000)
			{
				break;
			}
		}
		if (best != null)
		{
			_foreign.addAll(best.getNpcIds());
			_quest.putContext("foreignLand", best.getTown());
		}
	}

	private int pickBrood(Random random)
	{
		return _foreign.isEmpty() ? pickMonster(random) : _foreign.get(random.nextInt(_foreign.size()));
	}

	@Override
	public String onTalk(Player player, Npc npc)
	{
		if (!_byNpc.containsKey(npc))
		{
			return null;
		}
		return "<font color=\"LEVEL\">" + npc.getName() + "</font><br>" + text("nestText", "It pulses with a foul life. Stand next to it and it will burn.") + "<br><br>Nests burned: " + _destroyed + " of " + _total + ".";
	}

	@Override
	public void onNpcDeath(Npc npc, Creature killer)
	{
		if (npc == _mother)
		{
			_motherSlain = true;
			_quest.announce(text("motherSlainText", "The brood mother is dead!"));
			checkDone();
		}
		else if ((killer != null) && (killer.asPlayer() != null))
		{
			_quest.addContribution(killer.asPlayer().getObjectId(), 1);
		}
	}

	@Override
	public void onTick(int second, List<Player> near)
	{
		final int burnTime = _params.getInt("burnTime", 6);
		final int maxAlive = _params.getInt("maxAlive", 3);
		Nest closest = null;
		double closestDistance = Double.MAX_VALUE;
		final Player owner = _quest.getOwner();
		for (Nest nest : new ArrayList<>(_nests))
		{
			Player burner = null;
			Player watcher = null;
			for (Player player : near)
			{
				final double distance = player.calculateDistance2D(nest._npc);
				if ((distance <= 200) && !player.isDead())
				{
					burner = player;
				}
				if (distance <= 2500)
				{
					watcher = player;
				}
			}

			if (burner != null)
			{
				if (++nest._burn >= burnTime)
				{
					_nests.remove(nest);
					_byNpc.remove(nest._npc);
					_quest.despawn(nest._npc);
					_destroyed++;
					_quest.addContribution(burner.getObjectId(), 3);
					_quest.screen(getProgress());
					checkDone();
					continue;
				}
				if ((second % 2) == 0)
				{
					QuestSpawnUtil.screen(burner, "Burning... " + ((nest._burn * 100) / burnTime) + "%");
				}
			}

			if ((watcher != null) && (second >= nest._nextSpawn))
			{
				nest._nextSpawn = second + _spawnEvery;
				nest._brood.removeIf(npc -> !QuestSpawnUtil.isAlive(npc));
				if (nest._brood.size() < maxAlive)
				{
					final Npc add = _quest.spawnMonster(this, pickBrood(random("brood" + second)), point(nest._npc, 60, 200), _params.getInt("tierBonus", 0), title("broodName"), watcher);
					if (add != null)
					{
						nest._brood.add(add);
					}
				}
			}

			if (owner != null)
			{
				final double distance = owner.calculateDistance2D(nest._npc);
				if (distance < closestDistance)
				{
					closestDistance = distance;
					closest = nest;
				}
			}
		}

		if ((closest != null) && ((second % 10) == 0))
		{
			_quest.setMarker(closest._npc.getLocation());
		}
	}

	private void checkDone()
	{
		if ((_destroyed >= _total) && _motherSlain)
		{
			_quest.setMarker(null);
			complete();
		}
	}

	@Override
	public void onEscalate(int level)
	{
		_spawnEvery = Math.max(5, _spawnEvery / 2);
		if (!_nests.isEmpty() && ((_mother == null) || !QuestSpawnUtil.isAlive(_mother)))
		{
			final Random random = random("mother" + level);
			final Nest nest = _nests.get(random.nextInt(_nests.size()));
			_mother = _quest.spawnMonster(this, pickBrood(random), point(nest._npc, 80, 200), _params.getInt("broodTierBonus", 2), text("broodTitle", "Brood Mother"), null);
			_motherSlain = _mother == null;
		}
		_quest.announce(text("swarmText", "The nests are swarming, and their mother has come!"));
	}

	@Override
	public String getProgress()
	{
		return "Nests burned: " + _destroyed + "/" + _total + (!_motherSlain ? ", the brood mother lives" : "");
	}

	@Override
	protected String defaultDescription()
	{
		return "Burn the nests near {near} before they overrun the land.";
	}
}
