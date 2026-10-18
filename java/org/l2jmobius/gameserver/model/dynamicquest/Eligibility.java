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
package org.l2jmobius.gameserver.model.dynamicquest;

import java.util.List;
import java.util.Set;

import org.l2jmobius.gameserver.model.actor.Player;
import org.l2jmobius.gameserver.model.actor.enums.creature.Race;
import org.l2jmobius.gameserver.taskmanagers.GameTimeTaskManager;

/**
 * Who can take a dynamic quest. Cooldowns and daily limits are kept by the manager.
 * @author Mobius
 */
public class Eligibility
{
	private final int _minLevel;
	private final int _maxLevel;
	private final int _partyMin;
	private final int _partyMax;
	private final Set<Integer> _classes;
	private final Set<Race> _races;
	private final Set<String> _towns;
	private final boolean _nightOnly;
	private final int _cooldown;
	private final int _dailyLimit;

	public Eligibility(int minLevel, int maxLevel, int partyMin, int partyMax, Set<Integer> classes, Set<Race> races, Set<String> towns, boolean nightOnly, int cooldown, int dailyLimit)
	{
		_minLevel = minLevel;
		_maxLevel = maxLevel;
		_partyMin = partyMin;
		_partyMax = partyMax;
		_classes = classes;
		_races = races;
		_towns = towns;
		_nightOnly = nightOnly;
		_cooldown = cooldown;
		_dailyLimit = dailyLimit;
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
	 * @return seconds before the same account can take the quest again after finishing it, 0 for none
	 */
	public int getCooldown()
	{
		return _cooldown;
	}

	/**
	 * @return how many times an account can complete it per day, 0 for no limit of its own
	 */
	public int getDailyLimit()
	{
		return _dailyLimit;
	}

	public boolean isNightOnly()
	{
		return _nightOnly;
	}

	/**
	 * @param town a town name
	 * @return {@code true} if the quest can be offered in that town
	 */
	public boolean isForTown(String town)
	{
		return _towns.isEmpty() || ((town != null) && _towns.contains(town.toLowerCase()));
	}

	/**
	 * @param player the player taking the quest
	 * @param members the party members taking part (the player included)
	 * @return {@code null} if they can take it, else why not
	 */
	public String test(Player player, List<Player> members)
	{
		if (player.getLevel() < _minLevel)
		{
			return "You must be at least level " + _minLevel + ".";
		}
		if (player.getLevel() > _maxLevel)
		{
			return "This is meant for those of level " + _maxLevel + " or lower.";
		}
		if (members.size() < _partyMin)
		{
			return "You need a party of at least " + _partyMin + " nearby.";
		}
		if (members.size() > _partyMax)
		{
			return "No more than " + _partyMax + " can take part.";
		}
		if (!_classes.isEmpty() && !_classes.contains(player.getPlayerClass().getId()))
		{
			return "This is not work for your profession.";
		}
		if (!_races.isEmpty() && !_races.contains(player.getRace()))
		{
			return "This is not work for your people.";
		}
		if (_nightOnly && !GameTimeTaskManager.getInstance().isNight())
		{
			return "Come back after nightfall.";
		}
		return null;
	}
}
