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
 * A village that can be besieged, from {@code data/VillageSiege.xml}.
 */
public class SiegeVillage
{
	/**
	 * An entrance set in the data: where its gate standard stands, and optionally where its waves form.
	 */
	public static class FixedEntrance
	{
		private final Location _gate;
		private final Location _rally;

		public FixedEntrance(Location gate, Location rally)
		{
			_gate = gate;
			_rally = rally;
		}

		public Location getGate()
		{
			return _gate;
		}

		/**
		 * @return where the waves form, {@code null} for straight out from the village
		 */
		public Location getRally()
		{
			return _rally;
		}
	}

	private final String _id;
	private final String _name;
	private final String _town;
	private final int _gatekeeperId;
	private final int _guardLookId;
	private final int _referenceItemId;
	private final int _level;
	private final List<FixedEntrance> _entrances;

	public SiegeVillage(String id, String name, String town, int gatekeeperId, int guardLookId, int referenceItemId, int level, List<FixedEntrance> entrances)
	{
		_id = id;
		_name = name;
		_town = town;
		_gatekeeperId = gatekeeperId;
		_guardLookId = guardLookId;
		_referenceItemId = referenceItemId;
		_level = level;
		_entrances = Collections.unmodifiableList(entrances);
	}

	/**
	 * @return the key of the village ({@code VillageSiegeOrder}, //villagesiege)
	 */
	public String getId()
	{
		return _id;
	}

	public String getName()
	{
		return _name;
	}

	/**
	 * @return the name of the village's TownZone
	 */
	public String getTown()
	{
		return _town;
	}

	public int getGatekeeperId()
	{
		return _gatekeeperId;
	}

	/**
	 * @return the npc whose look the siege guards borrow
	 */
	public int getGuardLookId()
	{
		return _guardLookId;
	}

	/**
	 * @return the item a defender's {@code VillageSiegeHoursPerReferenceItem} hours of fighting pay for
	 */
	public int getReferenceItemId()
	{
		return _referenceItemId;
	}

	/**
	 * @return the level of the siege's monsters, 0 for the average level of the monsters living around the village
	 */
	public int getLevel()
	{
		return _level;
	}

	/**
	 * @return the fixed entrances, empty to find them on their own
	 */
	public List<FixedEntrance> getEntrances()
	{
		return _entrances;
	}
}
