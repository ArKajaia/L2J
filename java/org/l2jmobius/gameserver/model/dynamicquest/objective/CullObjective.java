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

import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

import org.l2jmobius.gameserver.config.custom.DynamicQuestConfig;
import org.l2jmobius.gameserver.managers.DynamicQuestManager;
import org.l2jmobius.gameserver.model.Location;
import org.l2jmobius.gameserver.model.actor.Creature;
import org.l2jmobius.gameserver.model.actor.Npc;
import org.l2jmobius.gameserver.model.actor.Player;
import org.l2jmobius.gameserver.model.dynamicquest.ObjectiveTemplate;
import org.l2jmobius.gameserver.model.dynamicquest.QuestInstance;
import org.l2jmobius.gameserver.util.QuestSpawnUtil;

/**
 * CULL: the world spawns a surge of the hunting ground's own monsters around its centre that never respawn: {@code count} plus {@code perPlayer} for every player near it (at most {@code max}), within {@code radius}. Slaying {@code percent} percent of them ends it.
 * Everyone who kills one takes part. On escalation an alpha ({@code alphaTitle}, {@code alphaTierBonus}) rises and must die too, and the surge spreads {@code spreadMin}-{@code spreadMax} away with half as many again.
 * @author Mobius
 */
public class CullObjective extends AbstractObjective
{
	private final Set<Npc> _surge = new HashSet<>();
	private int _total;
	private int _killed;
	private Npc _alpha;
	private boolean _alphaSlain;

	public CullObjective(QuestInstance quest, ObjectiveTemplate template, int index)
	{
		super(quest, template, index);
	}

	@Override
	public String start(boolean restored)
	{
		final Location area = _quest.getArea();
		if (area == null)
		{
			return "no place for the surge";
		}
		final int players = QuestInstance.playersNear(area, DynamicQuestConfig.WORLD_EVENT_RANGE).size();
		final int amount = Math.min(_params.getInt("max", 60), _params.getInt("count", 15) + (_params.getInt("perPlayer", 4) * players));
		spawnSurge(area, amount);
		if (_surge.isEmpty())
		{
			return "the surge could not be spawned";
		}
		return null;
	}

	private void spawnSurge(Location around, int amount)
	{
		final Random random = random("surge" + _total);
		final int radius = _params.getInt("radius", 800);
		for (int i = 0; i < amount; i++)
		{
			final Npc npc = _quest.spawnMonster(this, pickMonster(random), point(around, 50, radius), _params.getInt("tierBonus", 0), title("title"), null);
			if (npc != null)
			{
				npc.setRandomWalking(true);
				_surge.add(npc);
				_total++;
			}
		}
	}

	private int getNeeded()
	{
		return (int) Math.ceil((_total * _params.getInt("percent", 80)) / 100.0);
	}

	@Override
	public void onNpcDeath(Npc npc, Creature killer)
	{
		final Player player = killer != null ? killer.asPlayer() : null;
		if (npc == _alpha)
		{
			_alphaSlain = true;
			if (player != null)
			{
				join(player, 5);
			}
			_quest.announce(text("alphaSlainText", "The alpha has fallen!"));
		}
		else if (_surge.remove(npc))
		{
			_killed++;
			if (player != null)
			{
				join(player, 1);
			}
			if ((_killed % 5) == 0)
			{
				_quest.screen(getProgress());
			}
		}
		else
		{
			return;
		}

		if ((_killed >= getNeeded()) && ((_alpha == null) || _alphaSlain))
		{
			complete();
		}
	}

	private void join(Player player, int contribution)
	{
		if (_quest.addParticipant(player.getObjectId()))
		{
			DynamicQuestManager.getInstance().onParticipantAdded(_quest, player);
		}
		_quest.addContribution(player.getObjectId(), contribution);
	}

	@Override
	public void onEscalate(int level)
	{
		final Location area = _quest.getArea();
		if (area == null)
		{
			return;
		}
		if ((_alpha == null) || !QuestSpawnUtil.isAlive(_alpha))
		{
			_alpha = _quest.spawnMonster(this, pickMonster(random("alpha" + level)), point(area, 100, 400), _params.getInt("alphaTierBonus", 3), text("alphaTitle", "Alpha"), null);
			_alphaSlain = _alpha == null;
		}
		final Location spread = point(area, _params.getInt("spreadMin", 1500), _params.getInt("spreadMax", 2500));
		spawnSurge(spread, Math.max(5, _surge.size() / 2));
		_quest.putLocation("spread", spread);
		_quest.announce(text("spreadText", "The outbreak spreads, and an alpha leads it!"));
	}

	@Override
	public void onTick(int second, List<Player> near)
	{
		// The surge doesn't wander off: stragglers are called back.
		if ((second % 30) == 0)
		{
			final Location area = _quest.getArea();
			for (Npc npc : _surge)
			{
				if (QuestSpawnUtil.isAlive(npc) && !npc.isInCombat() && (area != null) && (npc.calculateDistance2D(area) > (_params.getInt("radius", 800) * 3)))
				{
					npc.teleToLocation(point(area, 50, _params.getInt("radius", 800)), false);
				}
			}
		}
	}

	@Override
	public String getProgress()
	{
		return "Trimmed: " + _killed + "/" + getNeeded() + ((_alpha != null) && !_alphaSlain ? ", the alpha lives" : "");
	}

	@Override
	protected String defaultDescription()
	{
		return "Trim the surge of {monster} near {near} before it spreads.";
	}
}
