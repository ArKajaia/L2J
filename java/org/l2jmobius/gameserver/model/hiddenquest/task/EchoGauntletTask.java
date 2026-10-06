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

import org.l2jmobius.gameserver.model.Location;
import org.l2jmobius.gameserver.model.actor.Npc;
import org.l2jmobius.gameserver.model.actor.Player;
import org.l2jmobius.gameserver.model.hiddenquest.EchoDefinition;
import org.l2jmobius.gameserver.model.hiddenquest.EchoDefinition.EchoGimmick;
import org.l2jmobius.gameserver.model.hiddenquest.HiddenQuestSession;
import org.l2jmobius.gameserver.model.hiddenquest.SpawnRole;

/**
 * ECHO_GAUNTLET: defeat the quest's echoes one after another where the duel began. Each echo is a monster of role {@code echo} with its own title, tier and trick ({@link EchoGimmick}). Shards and wards use role {@code shard}/{@code ward} if present, else {@code echo}.
 * <ul>
 * <li>{@code arenaRadius} - leaving this far from the starting point fails the duel (1500)</li>
 * <li>{@code pause} - seconds between echoes (8)</li>
 * </ul>
 * @author Mobius
 */
public class EchoGauntletTask extends AbstractHiddenTask
{
	private Location _center;
	private int _index;
	private int _second;
	private int _nextSpawnAt;
	private Npc _echo;
	private int _echoSince;
	private double _lastHp;
	private boolean _split;
	private boolean _regenSaid;
	private final List<Npc> _wards = new ArrayList<>();
	private boolean _wardsUp;

	public EchoGauntletTask(HiddenQuestSession session)
	{
		super(session);
	}

	@Override
	public String start(Player player)
	{
		if (_definition.getEchoes().isEmpty() || (_definition.getRole("echo") == null))
		{
			return "the quest has no echoes";
		}
		_center = player.getLocation();
		_nextSpawnAt = 5;
		announce(player, text("start", "Stand your ground. The echoes are coming."));
		return null;
	}

	private void spawnEcho(Player player)
	{
		final EchoDefinition echo = _definition.getEchoes().get(_index);
		_echo = spawnMonster(_definition.getRole("echo"), randomPoint(player, 250, 400, true), player, echo.getName(), echo.getTier());
		if (_echo == null)
		{
			fail("The echo could not take shape.");
			return;
		}

		_echoSince = _second;
		_lastHp = _echo.getCurrentHp();
		_split = false;
		_regenSaid = false;
		_wards.clear();
		_wardsUp = false;
		say(_echo, echo.getLine());
		announce(player, echo.getName() + " (" + (_index + 1) + "/" + _definition.getEchoes().size() + ")");

		if (echo.getGimmick() == EchoGimmick.WARDS)
		{
			final SpawnRole role = _definition.getRole("ward") != null ? _definition.getRole("ward") : _definition.getRole("echo");
			for (int i = 0; i < 2; i++)
			{
				final Npc ward = spawnMonster(role, randomPoint(_echo, 100, 200, true), player, "Ward of " + echo.getName(), 0);
				if (ward != null)
				{
					_wards.add(ward);
				}
			}
			if (!_wards.isEmpty())
			{
				_wardsUp = true;
				_echo.setInvul(true);
				screen(player, text("wardsUp", "It cannot be harmed while its wards stand!"));
			}
		}
	}

	@Override
	protected void onTick(Player player, int second)
	{
		_second = second;
		if (player.calculateDistance2D(_center) > _params.getInt("arenaRadius", 1500))
		{
			fail(text("failFled", "You turned your back on the duel."));
			return;
		}

		if (_echo == null)
		{
			if (second >= _nextSpawnAt)
			{
				spawnEcho(player);
			}
			return;
		}

		if (!isAlive(_echo))
		{
			return;
		}

		final EchoGimmick gimmick = _definition.getEchoes().get(_index).getGimmick();
		final int age = second - _echoSince;
		switch (gimmick)
		{
			case REGEN:
			{
				if ((age % 3) == 0)
				{
					final double hp = _echo.getCurrentHp();
					if ((hp >= _lastHp) && (hp < _echo.getMaxHp()))
					{
						_echo.setCurrentHp(Math.min(_echo.getMaxHp(), hp + (_echo.getMaxHp() * 0.06)));
						if (!_regenSaid)
						{
							_regenSaid = true;
							say(_echo, text("regenLine", "Every breath you hold, I take back."));
						}
					}
					_lastHp = _echo.getCurrentHp();
				}
				break;
			}
			case BLINK:
			{
				if ((age > 0) && ((age % 9) == 0))
				{
					_echo.teleToLocation(randomPoint(player, 80, 160, true));
					attack(_echo, player);
				}
				break;
			}
			case SPLIT:
			{
				if (!_split && (_echo.getCurrentHp() < (_echo.getMaxHp() * 0.5)))
				{
					_split = true;
					final SpawnRole role = _definition.getRole("shard") != null ? _definition.getRole("shard") : _definition.getRole("echo");
					final String name = _definition.getEchoes().get(_index).getName();
					for (int i = 0; i < 2; i++)
					{
						spawnMonster(role, randomPoint(_echo, 80, 160, true), player, "Shard of " + name, 0);
					}
					screen(player, text("splitLine", "The echo shatters into shards!"));
				}
				break;
			}
			case WARDS:
			{
				if (_wardsUp && (countAlive(_wards) == 0))
				{
					_wardsUp = false;
					_echo.setInvul(false);
					screen(player, text("wardsDown", "The wards are broken!"));
				}
				break;
			}
			default:
			{
				break;
			}
		}

		// Keep it on the player if it lost interest.
		if (!_echo.isInCombat())
		{
			attack(_echo, player);
		}
	}

	@Override
	protected void onNpcDeath(Npc npc, Player player)
	{
		if (npc != _echo)
		{
			return;
		}

		_echo = null;
		_index++;
		if (_index >= _definition.getEchoes().size())
		{
			complete();
			return;
		}
		_nextSpawnAt = _second + Math.max(3, _params.getInt("pause", 8));
		screen(player, text("echoFallen", "The echo fades... another stirs."));
	}

	@Override
	public String getProgress()
	{
		return "Echo " + Math.min(_index + 1, _definition.getEchoes().size()) + "/" + _definition.getEchoes().size();
	}
}
