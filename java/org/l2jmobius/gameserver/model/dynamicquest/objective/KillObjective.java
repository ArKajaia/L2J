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
import java.util.Random;
import java.util.Set;

import org.l2jmobius.gameserver.model.Location;
import org.l2jmobius.gameserver.model.actor.Attackable;
import org.l2jmobius.gameserver.model.actor.Creature;
import org.l2jmobius.gameserver.model.actor.Npc;
import org.l2jmobius.gameserver.model.actor.Player;
import org.l2jmobius.gameserver.model.dynamicquest.ObjectiveTemplate;
import org.l2jmobius.gameserver.model.dynamicquest.QuestInstance;
import org.l2jmobius.gameserver.util.QuestSpawnUtil;

/**
 * KILL: kill {@code count} monsters.
 * <ul>
 * <li>{@code source="QUEST"}: the quest spawns them ({@code at}, {@code spawnMin}-{@code spawnMax} around it), from the hunting ground or {@code npcId}, at the quest's champion tier plus {@code tierBonus}, with {@code guards} more that don't need to die. {@code named="true"}: one target, titled with the context's {@code target}.</li>
 * <li>{@code source="AREA"}: kills of the hunting ground's monsters count. {@code source="LEVEL"}: any monster of the quest's level. {@code source="NPC"}: the monsters {@code npcIds}.</li>
 * </ul>
 * On escalation, {@code escalate="FLEE"} moves the living targets {@code fleeMin}-{@code fleeMax} away with {@code fleeGuards} new guards, {@code "LEADER"} adds a leader ({@code leaderTitle}, {@code leaderTierBonus}) that must die too, {@code "FLEE_LEADER"} does both.
 * @author Mobius
 */
public class KillObjective extends AbstractObjective
{
	private final List<Npc> _targets = new ArrayList<>();
	private final Set<Integer> _npcIds = new HashSet<>();
	private int _total;
	private int _killed;
	private Location _spot;

	public KillObjective(QuestInstance quest, ObjectiveTemplate template, int index)
	{
		super(quest, template, index);
	}

	private String getSource()
	{
		return _params.getString("source", "QUEST").toUpperCase();
	}

	private boolean isNamed()
	{
		return _params.getBoolean("named", false);
	}

	@Override
	public String start(boolean restored)
	{
		_total = isNamed() ? 1 : count("count", 5);
		for (String id : _params.getString("npcIds", "").split(","))
		{
			if (!id.trim().isEmpty())
			{
				_npcIds.add(parseInt(id, 0));
			}
		}

		switch (getSource())
		{
			case "QUEST":
			{
				_spot = restored && (_quest.getLocation("kill" + _index) != null) ? _quest.getLocation("kill" + _index) : point(resolve(_params.getString("at", "AREA")), _params.getInt("spawnMin", 0), _params.getInt("spawnMax", 500));
				_quest.putLocation("kill" + _index, _spot);
				_quest.putLocation("last", _spot);
				final int left = Math.max(0, _total - _killed);
				spawnTargets(_spot, left, _params.getInt("tierBonus", 0), isNamed() ? _quest.getContext("target", null) : title("title"));
				spawnGuards(_spot, _params.getInt("guards", 0));
				if (_targets.isEmpty() && (left > 0))
				{
					return "its monsters could not be found";
				}
				final String npcName = !_targets.isEmpty() ? _targets.get(0).getName() : "monster";
				_quest.putContext("targetKind", npcName);
				_quest.setMarker(_spot);
				break;
			}
			case "AREA":
			{
				_npcIds.addAll(_quest.getAreaNpcIds());
				if (_quest.getArea() != null)
				{
					_quest.setMarker(_quest.getArea());
				}
				break;
			}
		}
		_quest.putContext("count" + _index, String.valueOf(_total));
		return null;
	}

	private void spawnTargets(Location around, int amount, int tierBonus, String title)
	{
		final Random random = random("kill");
		for (int i = 0; i < amount; i++)
		{
			final int npcId = pickMonster(random);
			final Npc npc = _quest.spawnMonster(this, npcId, i == 0 ? around : point(around, 50, 300), tierBonus, title, null);
			if (npc != null)
			{
				_targets.add(npc);
			}
		}
	}

	private void spawnGuards(Location around, int amount)
	{
		final Random random = random("guard");
		for (int i = 0; i < amount; i++)
		{
			_quest.spawnMonster(this, pickMonster(random), point(around, 80, 250), _params.getInt("guardTierBonus", 0), title("guardTitle"), null);
		}
	}

	@Override
	public void onKill(Player killer, Attackable monster)
	{
		final boolean counts;
		switch (getSource())
		{
			case "AREA":
			case "NPC":
			{
				counts = _npcIds.contains(monster.getId()) && isWorthy(monster);
				break;
			}
			case "LEVEL":
			{
				counts = isWorthy(monster);
				break;
			}
			default:
			{
				counts = false;
				break;
			}
		}
		if (counts)
		{
			addKill(killer);
		}
	}

	@Override
	public void onNpcDeath(Npc npc, Creature killer)
	{
		if (_targets.remove(npc))
		{
			addKill(killer != null ? killer.asPlayer() : null);
		}
	}

	private void addKill(Player killer)
	{
		_killed++;
		_quest.setDirty(true);
		if (killer != null)
		{
			_quest.addContribution(killer.getObjectId(), 1);
		}
		if (_killed >= _total)
		{
			if (isNamed())
			{
				_quest.announce(text("killText", "{target} has fallen!"));
			}
			complete();
		}
		else if (!isNamed())
		{
			_quest.screen(getProgress());
		}
	}

	@Override
	public void onEscalate(int level)
	{
		if (!"QUEST".equals(getSource()))
		{
			return;
		}

		final String mode = _params.getString("escalate", isNamed() ? "FLEE" : "LEADER").toUpperCase();
		if (mode.contains("FLEE"))
		{
			final List<Npc> alive = new ArrayList<>();
			for (Npc npc : _targets)
			{
				if (QuestSpawnUtil.isAlive(npc))
				{
					alive.add(npc);
				}
			}
			if (!alive.isEmpty())
			{
				_spot = point(_spot, _params.getInt("fleeMin", 1500), _params.getInt("fleeMax", 2500));
				_quest.putLocation("kill" + _index, _spot);
				_quest.putLocation("last", _spot);
				for (Npc npc : alive)
				{
					_targets.remove(npc);
					_quest.despawn(npc);
				}
				spawnTargets(_spot, alive.size(), _params.getInt("tierBonus", 0) + 1, isNamed() ? _quest.getContext("target", null) : title("title"));
				spawnGuards(_spot, _params.getInt("fleeGuards", 2));
				_quest.setMarker(_spot);
				_quest.announce(text("fleeText", "They have fled! Follow the radar."));
			}
		}
		if (mode.contains("LEADER"))
		{
			final Npc leader = _quest.spawnMonster(this, pickMonster(random("leader" + level)), point(_spot, 50, 200), _params.getInt("leaderTierBonus", 2), text("leaderTitle", "Leader"), null);
			if (leader != null)
			{
				_targets.add(leader);
				_total++;
				_quest.putContext("leader", leader.getName());
				_quest.announce(text("leaderText", "Their leader has joined them!"));
			}
		}
	}

	@Override
	public String getProgress()
	{
		if (isNamed())
		{
			return _killed >= _total ? _quest.format("{target} is dead") : _quest.format("Hunt down {target}");
		}
		return "Slain: " + Math.min(_killed, _total) + "/" + _total;
	}

	@Override
	protected String defaultDescription()
	{
		switch (getSource())
		{
			case "AREA":
			{
				return "Slay " + _total + " {monster} and their kin near {near}.";
			}
			case "LEVEL":
			{
				return "Slay " + _total + " monsters of level {level} or so.";
			}
			default:
			{
				return isNamed() ? "Slay {target}." : "Slay the " + _total + " monsters on your radar.";
			}
		}
	}

	@Override
	public String saveState()
	{
		return String.valueOf(_killed);
	}

	@Override
	public void loadState(String state)
	{
		_killed = parseInt(state, 0);
	}
}
