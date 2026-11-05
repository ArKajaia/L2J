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
package org.l2jmobius.gameserver.model.eventmap;

import java.util.ArrayList;
import java.util.List;

import org.l2jmobius.gameserver.model.Location;

/**
 * What an event map holds for the Map Device (see {@link org.l2jmobius.gameserver.managers.MapDeviceManager}): a map ticked for it, with a fence and an entry, is one of the places a map item can open in.
 * @param entry where the players come in through a portal (and where the Return Portal stands), {@code null} for none yet
 * @param guardian where the Map Guardian stands, {@code null} for the fence's middle
 * @param packs where the monsters stand, each spot with how far round it they spread; empty to spread them over the whole field
 * @param minTier the lowest map tier that may open here, 0 for any
 * @param maxTier the highest map tier that may open here, 0 for any
 * @param monsterCount how many monsters the map holds before its modifiers, 0 for the server's setting (MonsterCount)
 * @param monsters the monsters (npc ids) the map is filled with, whatever the tier; empty for monsters of the tier's levels picked by chance
 */
public record EventMapDevice(Location entry, Location guardian, List<Pack> packs, int minTier, int maxTier, int monsterCount, List<Integer> monsters)
{
	/** A map without Map Device points or settings. */
	public static final EventMapDevice NONE = new EventMapDevice(null, null, List.of(), 0, 0, 0, List.of());
	/** At most so many monster spots, so many kinds of monster, and so many monsters a map. */
	public static final int MAX_PACKS = 50;
	public static final int MAX_MONSTER_KINDS = 30;
	public static final int MAX_MONSTER_COUNT = 300;
	/** The spread of a monster spot that doesn't say. */
	public static final int DEFAULT_PACK_RADIUS = 600;
	public static final int MIN_PACK_RADIUS = 100;
	public static final int MAX_PACK_RADIUS = 3000;

	/**
	 * A spot monsters stand round.
	 * @param x its x
	 * @param y its y
	 * @param z the height of the ground there
	 * @param radius how far round it they spread
	 */
	public record Pack(int x, int y, int z, int radius)
	{
	}

	public EventMapDevice
	{
		entry = copy(entry);
		guardian = copy(guardian);
		packs = List.copyOf(packs);
		monsters = List.copyOf(new ArrayList<>(monsters));
		minTier = Math.max(0, minTier);
		maxTier = Math.max(0, maxTier);
		monsterCount = Math.max(0, Math.min(MAX_MONSTER_COUNT, monsterCount));
	}

	private static Location copy(Location location)
	{
		return location == null ? null : new Location(location.getX(), location.getY(), location.getZ(), location.getHeading());
	}

	/**
	 * @return {@code true} if it holds no point and no setting
	 */
	public boolean isEmpty()
	{
		return (entry == null) && (guardian == null) && packs.isEmpty() && monsters.isEmpty() && (minTier == 0) && (maxTier == 0) && (monsterCount == 0);
	}

	/**
	 * @param tier a map tier
	 * @return {@code true} if a map of that tier may open here
	 */
	public boolean allowsTier(int tier)
	{
		return ((minTier <= 0) || (tier >= minTier)) && ((maxTier <= 0) || (tier <= maxTier));
	}
}
