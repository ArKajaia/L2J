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
package org.l2jmobius.gameserver.model.classtransfer.objective;

import java.util.List;

import org.l2jmobius.gameserver.model.actor.Npc;
import org.l2jmobius.gameserver.model.actor.Player;
import org.l2jmobius.gameserver.model.classtransfer.ChallengeSession;
import org.l2jmobius.gameserver.model.classtransfer.ChallengeSpawnHolder;
import org.l2jmobius.gameserver.model.classtransfer.ObjectiveDefinition;
import org.l2jmobius.gameserver.model.classtransfer.TrackedNpc;
import org.l2jmobius.gameserver.model.classtransfer.TrackedNpc.NpcRole;

/**
 * WAVES: clear every {@code <wave>}. The next wave comes {@code delay} seconds after the current one is cleared, or as soon as the current one has lasted {@code waveSeconds} (0 = no limit), stacking on whatever is left. Wave monsters head straight for the challenger.
 * @author Mobius
 */
public class WavesObjective extends AbstractChallengeObjective
{
	private final List<List<ChallengeSpawnHolder>> _waves;
	private final int _waveSeconds;
	private final int _delay;
	private int _spawnedWaves;
	private int _waveElapsed;
	private int _countdown = -1;
	private boolean _cleared;

	public WavesObjective(ObjectiveDefinition definition)
	{
		super(definition);
		_waves = definition.getWaves();
		_waveSeconds = Math.max(0, params().getInt("waveSeconds", 0));
		_delay = Math.max(1, params().getInt("delay", 5));
	}

	@Override
	public void start(ChallengeSession session)
	{
		if (_waves.isEmpty())
		{
			fail("The trial is misconfigured (WAVES without a <wave>).");
			return;
		}
		spawnNextWave(session);
	}

	private void spawnNextWave(ChallengeSession session)
	{
		final Player player = session.getPlayerInside();
		for (ChallengeSpawnHolder holder : _waves.get(_spawnedWaves))
		{
			for (int i = 0; i < holder.getCount(); i++)
			{
				final Npc npc = session.spawn(holder, getIndex(), NpcRole.MOB);
				if ((player != null) && !session.isKnockedOut())
				{
					attack(npc, player);
				}
			}
		}
		_spawnedWaves++;
		_waveElapsed = 0;
		_countdown = -1;
		_cleared = session.getAlive(getIndex(), NpcRole.MOB).isEmpty();
		session.announce("Wave " + _spawnedWaves + "/" + _waves.size() + "!");
	}

	@Override
	public void onTick(ChallengeSession session, Player player)
	{
		_cleared = session.getAlive(getIndex(), NpcRole.MOB).isEmpty();
		if (isComplete() || (_spawnedWaves >= _waves.size()))
		{
			return;
		}

		_waveElapsed++;
		if (_cleared && (_countdown < 0))
		{
			_countdown = _delay;
		}

		if (_countdown > 0)
		{
			_countdown--;
		}

		if ((_countdown == 0) || ((_waveSeconds > 0) && (_waveElapsed >= _waveSeconds)))
		{
			spawnNextWave(session);
		}
	}

	@Override
	public void onKill(ChallengeSession session, TrackedNpc tracked, boolean credited)
	{
		_cleared = session.getAlive(getIndex(), NpcRole.MOB).isEmpty();
	}

	@Override
	public boolean isComplete()
	{
		return (_spawnedWaves >= _waves.size()) && _cleared;
	}

	@Override
	public String getProgress()
	{
		return "wave " + Math.max(1, _spawnedWaves) + "/" + _waves.size();
	}
}
