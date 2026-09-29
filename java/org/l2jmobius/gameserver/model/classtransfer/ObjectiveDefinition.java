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
package org.l2jmobius.gameserver.model.classtransfer;

import java.util.Collections;
import java.util.List;

import org.l2jmobius.gameserver.model.StatSet;

/**
 * One {@code <objective>} of a challenge. Type-specific settings (count, itemId, seconds, location, npcId...) are kept as the element's attributes in {@link #getParams()}; monsters are {@link #getSpawns()}, and a WAVES objective lists its waves in {@link #getWaves()}.
 * @author Mobius
 */
public class ObjectiveDefinition
{
	private final int _index;
	private final ObjectiveType _type;
	private final String _text;
	private final StatSet _params;
	private final List<ChallengeSpawnHolder> _spawns;
	private final List<List<ChallengeSpawnHolder>> _waves;

	public ObjectiveDefinition(int index, ObjectiveType type, String text, StatSet params, List<ChallengeSpawnHolder> spawns, List<List<ChallengeSpawnHolder>> waves)
	{
		_index = index;
		_type = type;
		_text = text;
		_params = params;
		_spawns = Collections.unmodifiableList(spawns);
		_waves = Collections.unmodifiableList(waves);
	}

	/**
	 * @return zero-based position of this objective in its challenge
	 */
	public int getIndex()
	{
		return _index;
	}

	public ObjectiveType getType()
	{
		return _type;
	}

	/**
	 * @return the player-facing objective text, e.g. "Defeat the Trial Recruits"
	 */
	public String getText()
	{
		return _text;
	}

	public StatSet getParams()
	{
		return _params;
	}

	public List<ChallengeSpawnHolder> getSpawns()
	{
		return _spawns;
	}

	public List<List<ChallengeSpawnHolder>> getWaves()
	{
		return _waves;
	}
}
