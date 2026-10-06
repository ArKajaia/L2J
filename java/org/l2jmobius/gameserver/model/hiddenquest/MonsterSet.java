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
package org.l2jmobius.gameserver.model.hiddenquest;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * A themed list of retail monsters by level band (orcs, undead, bandits...). Quests spawn the band that fits the player's level.
 * @author Mobius
 */
public class MonsterSet
{
	private final String _name;
	private final List<MonsterBand> _bands = new ArrayList<>();

	public MonsterSet(String name, List<MonsterBand> bands)
	{
		_name = name;
		_bands.addAll(bands);
		_bands.sort(Comparator.comparingInt(MonsterBand::getMinLevel));
	}

	public String getName()
	{
		return _name;
	}

	public List<MonsterBand> getBands()
	{
		return Collections.unmodifiableList(_bands);
	}

	/**
	 * @param level a player level
	 * @return the highest band the level reaches, else the lowest band, {@code null} if the set is empty
	 */
	public MonsterBand getBand(int level)
	{
		MonsterBand result = _bands.isEmpty() ? null : _bands.get(0);
		for (MonsterBand band : _bands)
		{
			if (band.getMinLevel() <= level)
			{
				result = band;
			}
		}
		return result;
	}
}
