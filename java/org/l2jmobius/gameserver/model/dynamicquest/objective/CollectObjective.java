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
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.l2jmobius.commons.util.Rnd;
import org.l2jmobius.gameserver.model.Location;
import org.l2jmobius.gameserver.model.actor.Attackable;
import org.l2jmobius.gameserver.model.actor.Npc;
import org.l2jmobius.gameserver.model.actor.Player;
import org.l2jmobius.gameserver.model.dynamicquest.ObjectiveTemplate;
import org.l2jmobius.gameserver.model.dynamicquest.QuestInstance;
import org.l2jmobius.gameserver.model.item.enums.ItemProcessType;
import org.l2jmobius.gameserver.util.QuestSpawnUtil;

/**
 * COLLECT: gather {@code count} {@code name}.
 * <ul>
 * <li>{@code source="AREA"} (the hunting ground's monsters) or {@code "LEVEL"} (any of the quest's level): each kill gives one with {@code chance} percent.</li>
 * <li>{@code source="NODE"}: {@code nodes} nodes ({@code nodeNpc}) are spawned within {@code radius} of the hunting ground; each is gathered by standing next to it for {@code gatherTime} seconds.</li>
 * </ul>
 * What is gathered is counted by the quest, so it can't be traded, dropped or stored. With {@code itemId}, the gatherer also gets that real item.
 * @author Mobius
 */
public class CollectObjective extends AbstractObjective
{
	private final List<Npc> _nodes = new ArrayList<>();
	private final Set<Integer> _npcIds = new HashSet<>();
	private int _total;
	private int _collected;
	private Npc _gathering;
	private int _gatherer;
	private int _progress;

	public CollectObjective(QuestInstance quest, ObjectiveTemplate template, int index)
	{
		super(quest, template, index);
	}

	private String getSource()
	{
		return _params.getString("source", "AREA").toUpperCase();
	}

	private String getName()
	{
		return text("name", "trophies");
	}

	@Override
	public String start(boolean restored)
	{
		_total = count("count", 8);
		_quest.putContext("count" + _index, String.valueOf(_total));
		_quest.putContext("item" + _index, getName());
		_npcIds.addAll(_quest.getAreaNpcIds());
		if ("NODE".equals(getSource()))
		{
			final Location area = _quest.getArea();
			if (area == null)
			{
				return "no place to gather";
			}
			final int nodes = Math.max(0, _total - _collected);
			for (int i = 0; i < nodes; i++)
			{
				final Npc node = _quest.spawn(this, _params.getInt("nodeNpc", 0), point(area, 100, _params.getInt("radius", 900)), 0);
				if (node != null)
				{
					_nodes.add(node);
				}
			}
			if (_nodes.isEmpty() && (nodes > 0))
			{
				return "nothing to gather could be found";
			}
			_quest.setMarker(area);
		}
		else if (!_template.isOptional() && (_quest.getArea() != null) && "AREA".equals(getSource()))
		{
			_quest.setMarker(_quest.getArea());
		}
		return null;
	}

	@Override
	public void onKill(Player killer, Attackable monster)
	{
		final String source = getSource();
		if ("NODE".equals(source) || !isWorthy(monster))
		{
			return;
		}
		if ("AREA".equals(source) && !_npcIds.contains(monster.getId()))
		{
			return;
		}
		if (Rnd.get(100.0) < _params.getDouble("chance", 40))
		{
			gather(killer);
		}
	}

	private void gather(Player player)
	{
		_collected++;
		_quest.setDirty(true);
		if (player != null)
		{
			_quest.addContribution(player.getObjectId(), 1);
			final int itemId = _params.getInt("itemId", 0);
			if (itemId > 0)
			{
				player.addItem(ItemProcessType.QUEST, itemId, 1, null, true);
			}
		}
		_quest.screen(getProgress());
		if (_collected >= _total)
		{
			complete();
		}
	}

	@Override
	public String onTalk(Player player, Npc npc)
	{
		if (!_nodes.contains(npc))
		{
			return null;
		}
		return "<font color=\"LEVEL\">" + npc.getName() + "</font><br>" + text("nodeText", "There is something worth taking here.") + "<br><br><center>" + button("gather " + npc.getObjectId(), "Gather") + "</center>";
	}

	@Override
	public String onAction(Player player, Npc npc, String action)
	{
		if (action.startsWith("gather") && (npc != null) && _nodes.contains(npc))
		{
			if (!isClose(npc, player))
			{
				return "Walk up to it first.";
			}
			_gathering = npc;
			_gatherer = player.getObjectId();
			_progress = 0;
			QuestSpawnUtil.screen(player, "Gathering... stay close.");
		}
		return null;
	}

	@Override
	public void onTick(int second, List<Player> near)
	{
		if (_gathering == null)
		{
			return;
		}

		Player gatherer = null;
		for (Player player : near)
		{
			if (player.getObjectId() == _gatherer)
			{
				gatherer = player;
			}
		}
		if (!isClose(_gathering, gatherer))
		{
			_gathering = null;
			return;
		}
		if (++_progress >= _params.getInt("gatherTime", 3))
		{
			_nodes.remove(_gathering);
			_quest.despawn(_gathering);
			_gathering = null;
			gather(gatherer);
		}
	}

	@Override
	public String getProgress()
	{
		return getName() + ": " + Math.min(_collected, _total) + "/" + _total;
	}

	@Override
	protected String defaultDescription()
	{
		return "NODE".equals(getSource()) ? "Gather " + _total + " " + getName() + " near {near}." : "Take " + _total + " " + getName() + " from the {monster} and their kin near {near}.";
	}

	@Override
	public String saveState()
	{
		return String.valueOf(_collected);
	}

	@Override
	public void loadState(String state)
	{
		_collected = parseInt(state, 0);
	}
}
