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

import org.l2jmobius.gameserver.model.Location;
import org.l2jmobius.gameserver.model.actor.Npc;
import org.l2jmobius.gameserver.model.actor.Player;
import org.l2jmobius.gameserver.model.actor.instance.QuestGuard;
import org.l2jmobius.gameserver.model.dynamicquest.ObjectiveTemplate;
import org.l2jmobius.gameserver.model.dynamicquest.QuestInstance;
import org.l2jmobius.gameserver.util.QuestSpawnUtil;

/**
 * DEFEND: keep {@code target} (a QuestGuard) standing through {@code waves} waves of {@code waveCount} monsters of the place, each coming {@code waveInterval} seconds after the last or as soon as it is beaten, after {@code prep} seconds to get ready.
 * With {@code boss="true"} the last wave brings a champion ({@code bossTierBonus}, {@code bossTitle}). The target can't die: its meter ({@code meter}) drops by {@code drain} a second for every attacker next to it, and the defence fails when it is empty.
 * @author Mobius
 */
public class DefendObjective extends AbstractObjective
{
	private static final int TOUCH_RANGE = 200;

	private final List<Npc> _attackers = new ArrayList<>();
	private Npc _target;
	private int _wave;
	private int _nextWave;
	private double _meter;
	private double _maxMeter;

	public DefendObjective(QuestInstance quest, ObjectiveTemplate template, int index)
	{
		super(quest, template, index);
	}

	private int getWaves()
	{
		return Math.max(1, _params.getInt("waves", 4));
	}

	@Override
	public String start(boolean restored)
	{
		final Location at = point(resolve(_params.getString("at", "LAST")), 50, 150);
		_target = _quest.spawn(this, _params.getInt("target", 0), at, 0);
		if (_target == null)
		{
			return "there is nothing to defend";
		}
		if (_target instanceof QuestGuard)
		{
			((QuestGuard) _target).setPassive(true);
		}
		_quest.putLocation("last", at);
		_maxMeter = _params.getInt("meter", 100) * _quest.getDifficulty().meterMultiplier();
		_meter = _maxMeter;
		_nextWave = _params.getInt("prep", 15);
		_quest.setMarker(at);
		_quest.say(_target, text("startText", "Here they come. Hold the line!"));
		return null;
	}

	@Override
	public void onTick(int second, List<Player> near)
	{
		if (!QuestSpawnUtil.isAlive(_target))
		{
			fail(text("failText", "It has fallen."));
			return;
		}
		_target.setCurrentHp(_target.getMaxHp());

		for (Npc attacker : _attackers)
		{
			if (!QuestSpawnUtil.isAlive(attacker))
			{
				continue;
			}
			if (attacker.calculateDistance2D(_target) <= TOUCH_RANGE)
			{
				_meter -= _params.getDouble("drain", 1);
			}
			else if (!attacker.isInCombat())
			{
				QuestSpawnUtil.attack(attacker, _target);
			}
		}
		if (_meter <= 0)
		{
			fail(text("failText", "It has fallen."));
			return;
		}

		final int alive = QuestSpawnUtil.countAlive(_attackers);
		if (_wave >= getWaves())
		{
			if (alive == 0)
			{
				_quest.say(_target, text("winText", "We held! They are broken."));
				complete();
			}
			return;
		}

		// A beaten wave brings the next one sooner.
		if ((alive == 0) && (_wave > 0) && (_nextWave > 5))
		{
			_nextWave = 5;
		}
		if (--_nextWave <= 0)
		{
			spawnWave();
		}
		else if ((second % 10) == 0)
		{
			_quest.screen(getProgress());
		}
	}

	private void spawnWave()
	{
		_wave++;
		_attackers.removeIf(npc -> !QuestSpawnUtil.isAlive(npc));
		final Random random = random("wave" + _wave);
		final Location center = _target.getLocation();
		final int amount = count("waveCount", 3) + (_wave / 2);
		for (int i = 0; i < amount; i++)
		{
			final Npc monster = _quest.spawnMonster(this, pickMonsterNear(center, random), point(center, 600, 900), _params.getInt("tierBonus", 0), title("title"), _target);
			if (monster != null)
			{
				_attackers.add(monster);
			}
		}
		if ((_wave == getWaves()) && _params.getBoolean("boss", true))
		{
			final Npc boss = _quest.spawnMonster(this, pickMonsterNear(center, random), point(center, 600, 900), _params.getInt("bossTierBonus", 2), text("bossTitle", "Warband Leader"), _target);
			if (boss != null)
			{
				_attackers.add(boss);
			}
			_quest.announce(text("bossText", "The last wave - and their leader with it!"));
		}
		else
		{
			_quest.announce("Wave " + _wave + " of " + getWaves() + "!");
		}
		_nextWave = _params.getInt("waveInterval", 45);
	}

	@Override
	public int getMeterPercent()
	{
		return _maxMeter <= 0 ? -1 : (int) Math.max(0, Math.round((_meter * 100) / _maxMeter));
	}

	@Override
	public String getProgress()
	{
		return "Wave " + Math.min(Math.max(_wave, 1), getWaves()) + "/" + getWaves() + ", " + text("meterName", "standing") + " " + Math.max(0, getMeterPercent()) + "%";
	}

	@Override
	protected String defaultDescription()
	{
		return "Hold the line for " + getWaves() + " waves.";
	}
}
