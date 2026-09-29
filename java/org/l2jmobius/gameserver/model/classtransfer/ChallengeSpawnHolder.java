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

/**
 * One {@code <spawn>} line of a challenge objective: which NPC, how many at once, where, and which existing monster modifiers it is forced into.
 * @author Mobius
 */
public class ChallengeSpawnHolder
{
	private final int _npcId;
	private final int _count;
	private final int _x;
	private final int _y;
	private final int _z;
	private final int _heading;
	private final int _radius;
	private final int _championTier;
	private final SpawnVariant _variant;
	private final int _waves;
	private final boolean _arenaBuffs;
	private final int _enrageSeconds;

	public ChallengeSpawnHolder(int npcId, int count, int x, int y, int z, int heading, int radius, int championTier, SpawnVariant variant, int waves, boolean arenaBuffs, int enrageSeconds)
	{
		_npcId = npcId;
		_count = Math.max(1, count);
		_x = x;
		_y = y;
		_z = z;
		_heading = heading;
		_radius = Math.max(0, radius);
		_championTier = Math.max(0, Math.min(3, championTier));
		_variant = variant;
		_waves = Math.max(1, waves);
		_arenaBuffs = arenaBuffs;
		_enrageSeconds = Math.max(0, enrageSeconds);
	}

	public int getNpcId()
	{
		return _npcId;
	}

	/**
	 * @return how many of this NPC the line keeps alive at once
	 */
	public int getCount()
	{
		return _count;
	}

	public int getX()
	{
		return _x;
	}

	public int getY()
	{
		return _y;
	}

	public int getZ()
	{
		return _z;
	}

	public int getHeading()
	{
		return _heading;
	}

	/**
	 * @return random scatter radius around the spawn point (0 = exact point)
	 */
	public int getRadius()
	{
		return _radius;
	}

	/**
	 * @return champion tier forced on spawn (0 = none)
	 */
	public int getChampionTier()
	{
		return _championTier;
	}

	public SpawnVariant getVariant()
	{
		return _variant;
	}

	/**
	 * @return Wave Champion phases (1 = a normal monster that dies on its first death)
	 */
	public int getWaves()
	{
		return _waves;
	}

	/**
	 * @return {@code true} to cast the Survival Arena challenger buffs on this monster at every phase
	 */
	public boolean isArenaBuffs()
	{
		return _arenaBuffs;
	}

	/**
	 * @return seconds a phase may last before the arena enrage skill is cast on this monster (0 = never)
	 */
	public int getEnrageSeconds()
	{
		return _enrageSeconds;
	}
}
