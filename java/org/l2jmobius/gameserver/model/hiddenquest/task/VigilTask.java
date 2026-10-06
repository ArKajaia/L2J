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

import org.l2jmobius.gameserver.model.actor.Npc;
import org.l2jmobius.gameserver.model.actor.Player;
import org.l2jmobius.gameserver.model.actor.instance.QuestGuard;
import org.l2jmobius.gameserver.model.hiddenquest.HiddenQuestSession;
import org.l2jmobius.gameserver.model.hiddenquest.SpawnRole;

/**
 * VIGIL: guard a totem ({@code totemId}, a QuestGuard) for {@code duration} seconds while role {@code wave} comes every {@code waveInterval} seconds, a little bigger every other wave. Role {@code boss}, if present, comes {@code bossAt} seconds before the end and must fall too.
 * <p>
 * Like the escort, the totem can't die but its meter ({@code meter}) drains for each attacker touching it, and faster while the player is more than {@code leaveRange} away.
 * </p>
 * @author Mobius
 */
public class VigilTask extends AbstractHiddenTask
{
	private static final int TOUCH_RANGE = 200;

	private final List<Npc> _attackers = new ArrayList<>();
	private Npc _totem;
	private Npc _boss;
	private int _elapsed;
	private int _waves;
	private double _meter;

	public VigilTask(HiddenQuestSession session)
	{
		super(session);
	}

	private int getDuration()
	{
		return Math.max(60, _params.getInt("duration", 600));
	}

	@Override
	public String start(Player player)
	{
		if (_definition.getRole("wave") == null)
		{
			return "the quest has no attackers";
		}
		_totem = spawnNpc(_params.getInt("totemId", 0), randomPoint(player, 50, 100, true), player.getHeading());
		if (_totem == null)
		{
			return "the totem could not be raised";
		}
		if (_totem instanceof QuestGuard)
		{
			((QuestGuard) _totem).setPassive(true);
		}
		_meter = _params.getInt("meter", 100);
		say(_totem, text("totemLine", null));
		announce(player, text("start", "Guard it until the vigil ends."));
		return null;
	}

	@Override
	protected void onTick(Player player, int second)
	{
		if (!isAlive(_totem))
		{
			fail(text("failMeter", "The vigil is broken."));
			return;
		}
		_totem.setCurrentHp(_totem.getMaxHp());
		_elapsed++;

		final int duration = getDuration();
		final int interval = Math.max(20, _params.getInt("waveInterval", 60));
		if ((_elapsed < duration) && ((_elapsed % interval) == 15))
		{
			final SpawnRole role = _definition.getRole("wave");
			_attackers.addAll(spawnRole("wave", _totem, 600, 800, _totem, role.getCount() + (_waves / 2)));
			_waves++;
			announce(player, text("wave", "They come!"));
		}

		final int bossAt = _params.getInt("bossAt", 120);
		if ((_boss == null) && (_definition.getRole("boss") != null) && (_elapsed >= (duration - bossAt)))
		{
			_boss = spawnMonster(_definition.getRole("boss"), randomPoint(_totem, 600, 800, false), _totem, null, -1);
			if (_boss != null)
			{
				_attackers.add(_boss);
				say(_boss, text("bossLine", null));
				announce(player, text("boss", "Their champion has come!"));
			}
		}

		for (Npc attacker : _attackers)
		{
			if (!isAlive(attacker))
			{
				continue;
			}
			if (attacker.calculateDistance2D(_totem) <= TOUCH_RANGE)
			{
				_meter -= _params.getDouble("drain", 1);
			}
			else if (!attacker.isInCombat())
			{
				attack(attacker, _totem);
			}
		}

		if (player.calculateDistance2D(_totem) > _params.getInt("leaveRange", 1500))
		{
			_meter -= 2;
			if ((_elapsed % 5) == 0)
			{
				screen(player, text("leave", "You left your post! Go back!"));
			}
		}

		if (_meter <= 0)
		{
			fail(text("failMeter", "The vigil is broken."));
			return;
		}

		if ((_elapsed % 15) == 0)
		{
			screen(player, getProgress());
		}

		if ((_elapsed >= duration) && ((_definition.getRole("boss") == null) || ((_boss != null) && !isAlive(_boss))))
		{
			complete();
		}
	}

	@Override
	public String getProgress()
	{
		final int left = getDuration() - _elapsed;
		return text("meterName", "Ward") + " " + Math.max(0, (int) _meter) + "%, " + (left > 0 ? formatTime(left) + " left" : "defeat their champion!");
	}
}
