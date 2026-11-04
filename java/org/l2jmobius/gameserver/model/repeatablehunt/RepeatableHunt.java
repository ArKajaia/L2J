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
package org.l2jmobius.gameserver.model.repeatablehunt;

import java.util.Collections;
import java.util.Map;
import java.util.Set;

/**
 * A repeatable hunt (data/RepeatableHunts.xml): a town npc pays for kills of a region's monsters (KILL) or for quest items they drop (COLLECT), as often as the player reports back.
 * @author Mobius
 */
public class RepeatableHunt
{
	public enum Type
	{
		/** Pays for every kill of its monsters. */
		KILL,
		/** Pays for every quest item its monsters drop. */
		COLLECT
	}

	private final int _id;
	private final String _name;
	private final String _place;
	private final String _town;
	private final int _anchorNpcId;
	private final String _giverName;
	private final String _giverTitle;
	private final Type _type;
	private final int _itemId;
	private final String _unitName;
	private final int _minLevel;
	private final int _maxLevel;
	private final long _perUnit;
	private final long _bonus;
	private final int _bonusMin;
	private final Map<Integer, Double> _monsters;
	private final Map<String, String> _texts;

	public RepeatableHunt(int id, String name, String place, String town, int anchorNpcId, String giverName, String giverTitle, Type type, int itemId, String unitName, int minLevel, int maxLevel, long perUnit, long bonus, int bonusMin, Map<Integer, Double> monsters, Map<String, String> texts)
	{
		_id = id;
		_name = name;
		_place = place;
		_town = town;
		_anchorNpcId = anchorNpcId;
		_giverName = giverName;
		_giverTitle = giverTitle;
		_type = type;
		_itemId = itemId;
		_unitName = unitName;
		_minLevel = minLevel;
		_maxLevel = maxLevel;
		_perUnit = perUnit;
		_bonus = bonus;
		_bonusMin = bonusMin;
		_monsters = Collections.unmodifiableMap(monsters);
		_texts = Collections.unmodifiableMap(texts);
	}

	public int getId()
	{
		return _id;
	}

	public String getName()
	{
		return _name;
	}

	/**
	 * @return the region it is about, as it reads in a sentence ("the Plains of Glory")
	 */
	public String getPlace()
	{
		return _place;
	}

	/**
	 * @return the town its giver stands in
	 */
	public String getTown()
	{
		return _town;
	}

	/**
	 * @return the npc (the town's gatekeeper) its giver stands next to
	 */
	public int getAnchorNpcId()
	{
		return _anchorNpcId;
	}

	public String getGiverName()
	{
		return _giverName;
	}

	public String getGiverTitle()
	{
		return _giverTitle;
	}

	public Type getType()
	{
		return _type;
	}

	/**
	 * @return the quest item a COLLECT hunt pays for, 0 for a KILL hunt
	 */
	public int getItemId()
	{
		return _itemId;
	}

	/**
	 * @return what one unit is called ("kill", "Medal of Victory")
	 */
	public String getUnitName()
	{
		return _unitName;
	}

	public int getMinLevel()
	{
		return _minLevel;
	}

	public int getMaxLevel()
	{
		return _maxLevel;
	}

	/**
	 * @return adena paid per kill or item, before the server's quest adena rate
	 */
	public long getPerUnit()
	{
		return _perUnit;
	}

	/**
	 * @return adena paid on top for a report of at least {@link #getBonusMin()} units, before the server's quest adena rate
	 */
	public long getBonus()
	{
		return _bonus;
	}

	public int getBonusMin()
	{
		return _bonusMin;
	}

	/**
	 * @return its monsters and, for a COLLECT hunt, the chance (percent) that each drops the item
	 */
	public Map<Integer, Double> getMonsters()
	{
		return _monsters;
	}

	public Set<Integer> getMonsterIds()
	{
		return _monsters.keySet();
	}

	/**
	 * @param key a text key (offer, accepted, report, nothing, courier)
	 * @param defaultValue the text when the data has none
	 * @return the text, with {place}, {town}, {giver}, {unit} filled in
	 */
	public String getText(String key, String defaultValue)
	{
		final String text = _texts.getOrDefault(key, defaultValue);
		return text.replace("{place}", _place).replace("{town}", _town).replace("{giver}", _giverName).replace("{unit}", _unitName);
	}
}
