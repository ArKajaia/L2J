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

import org.l2jmobius.gameserver.config.custom.VillageSiegeConfig;
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
	private final int _minLevel;
	private final int _maxLevel;
	private final int _minDefenderLevel;
	private final int _maxDefenderLevel;
	private final List<FixedEntrance> _entrances;

	public SiegeVillage(String id, String name, String town, int gatekeeperId, int guardLookId, int minLevel, int maxLevel, int minDefenderLevel, int maxDefenderLevel, List<FixedEntrance> entrances)
	{
		_id = id;
		_name = name;
		_town = town;
		_gatekeeperId = gatekeeperId;
		_guardLookId = guardLookId;
		_minLevel = Math.min(minLevel, maxLevel);
		_maxLevel = Math.max(minLevel, maxLevel);
		_minDefenderLevel = minDefenderLevel;
		_maxDefenderLevel = maxDefenderLevel;
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
	 * @return the lowest level of the siege's monsters, 0 to follow the monsters living around the village
	 */
	public int getMinLevel()
	{
		return _minLevel;
	}

	/**
	 * @return the highest level of the siege's monsters, 0 to follow the monsters living around the village
	 */
	public int getMaxLevel()
	{
		return _maxLevel;
	}

	/**
	 * @return {@code true} if the data sets the levels of the siege's monsters
	 */
	public boolean hasLevels()
	{
		return _maxLevel > 0;
	}

	/**
	 * @return the lowest level of the characters the siege is for (called, and paid for their contribution)
	 */
	public int getMinDefenderLevel()
	{
		return Math.max(1, Math.min(_minDefenderLevel, getMaxDefenderLevel()));
	}

	/**
	 * @return the highest level of the characters the siege is for, {@link VillageSiegeConfig#MAX_PLAYER_LEVEL} unless the data sets it
	 */
	public int getMaxDefenderLevel()
	{
		return _maxDefenderLevel > 0 ? _maxDefenderLevel : VillageSiegeConfig.MAX_PLAYER_LEVEL;
	}

	/**
	 * @param level a character's level
	 * @return {@code true} if the siege is for characters of that level
	 */
	public boolean isDefenderLevel(int level)
	{
		return (level >= getMinDefenderLevel()) && (level <= getMaxDefenderLevel());
	}

	/**
	 * @return the highest level of the characters who hear of the siege: its defenders' if the data sets them, else {@link VillageSiegeConfig#ANNOUNCE_MAX_LEVEL}
	 */
	public int getAnnounceMaxLevel()
	{
		return _maxDefenderLevel > 0 ? _maxDefenderLevel : VillageSiegeConfig.ANNOUNCE_MAX_LEVEL;
	}

	/**
	 * @return the levels the siege is for, to follow "Defenders": "up to level 40", or "of level 40 to 85"
	 */
	public String describeDefenderLevels()
	{
		return getMinDefenderLevel() <= 1 ? "up to level " + getMaxDefenderLevel() : "of level " + getMinDefenderLevel() + " to " + getMaxDefenderLevel();
	}

	/**
	 * @return the fixed entrances, empty to find them on their own
	 */
	public List<FixedEntrance> getEntrances()
	{
		return _entrances;
	}
}
