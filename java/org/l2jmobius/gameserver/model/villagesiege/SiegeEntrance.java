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
package org.l2jmobius.gameserver.model.villagesiege;

import java.util.Collections;
import java.util.List;

import org.l2jmobius.gameserver.model.Location;

/**
 * An entrance of a besieged village (see {@link SiegeEntranceFinder}): the gate standard the defenders hold, where the waves form and the road they take to it, and the line behind it where the guards stand.
 */
public class SiegeEntrance
{
	private final String _name;
	private final Location _gate;
	private final Location _rally;
	private final List<Location> _guardPosts;
	private final int _weight;

	public SiegeEntrance(String name, Location gate, Location rally, List<Location> guardPosts, int weight)
	{
		_name = name;
		_gate = gate;
		_rally = rally;
		_guardPosts = Collections.unmodifiableList(guardPosts);
		_weight = weight;
	}

	/**
	 * @return its name, from the side of the village it is on ("North Gate")
	 */
	public String getName()
	{
		return _name;
	}

	/**
	 * @return where the gate standard stands
	 */
	public Location getGate()
	{
		return _gate;
	}

	/**
	 * @return where the waves form, out on the road
	 */
	public Location getRally()
	{
		return _rally;
	}

	/**
	 * @return where the guards stand, behind the gate, facing it
	 */
	public List<Location> getGuardPosts()
	{
		return _guardPosts;
	}

	/**
	 * @return how many monsters live on the hunting grounds whose road takes this entrance
	 */
	public int getWeight()
	{
		return _weight;
	}
}
