/*
 * This file is part of the L2J Mobius project.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>.
 */
package org.l2jmobius.gameserver.model.sessionstats;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.concurrent.atomic.AtomicLongArray;

import org.l2jmobius.gameserver.model.actor.Player;
import org.l2jmobius.gameserver.model.actor.holders.player.SubClassHolder;

/**
 * The session in progress of a player: the counters and where the character stood when it logged in.<br>
 * The counters are added to from any thread (combat, loot, quests); pvp, pk, fame and adena are read from the character when the session is looked at or finished.
 */
public class SessionStats
{
	private final long _start = System.currentTimeMillis();
	private final AtomicLongArray _counters = new AtomicLongArray(SessionStat.values().length);
	private final long _adenaStart;
	private final int _pvpStart;
	private final int _pkStart;
	private final int _fameStart;
	/** Class index -> class id, level and experience when the class was first played in this session. */
	private final Map<Integer, long[]> _classStarts = new ConcurrentSkipListMap<>();
	
	public SessionStats(Player player)
	{
		_adenaStart = player.getAdena();
		_pvpStart = player.getPvpKills();
		_pkStart = player.getPkKills();
		_fameStart = player.getFame();
		touchClass(player);
	}
	
	public long getStart()
	{
		return _start;
	}
	
	public void add(SessionStat stat, long value)
	{
		if (value > 0)
		{
			_counters.addAndGet(stat.ordinal(), value);
		}
	}
	
	/**
	 * Remembers where the active class stands, the first time it is played in this session.
	 * @param player the player
	 */
	public void touchClass(Player player)
	{
		final int classIndex = player.getClassIndex();
		if (!_classStarts.containsKey(classIndex))
		{
			final long[] state = getClassState(player, classIndex);
			if (state != null)
			{
				_classStarts.put(classIndex, state);
			}
		}
	}
	
	/**
	 * @param player the player
	 * @return the session so far, as it would be saved if the player logged out now
	 */
	public SessionRecord toRecord(Player player)
	{
		final Map<SessionStat, Long> values = new EnumMap<>(SessionStat.class);
		for (SessionStat stat : SessionStat.values())
		{
			values.put(stat, _counters.get(stat.ordinal()));
		}
		values.put(SessionStat.ADENA_START, _adenaStart);
		values.put(SessionStat.ADENA_END, player.getAdena());
		values.put(SessionStat.PVP_KILLS, (long) Math.max(0, player.getPvpKills() - _pvpStart));
		values.put(SessionStat.PK_KILLS, (long) Math.max(0, player.getPkKills() - _pkStart));
		values.put(SessionStat.FAME, (long) (player.getFame() - _fameStart));
		
		final List<SessionRecord.ClassLine> classes = new ArrayList<>();
		for (Map.Entry<Integer, long[]> entry : _classStarts.entrySet())
		{
			final long[] start = entry.getValue();
			long[] end = getClassState(player, entry.getKey());
			if ((end == null) || (end[0] != start[0]))
			{
				// The subclass was removed or replaced during the session.
				end = start;
			}
			classes.add(new SessionRecord.ClassLine((int) start[0], (int) start[1], start[2], (int) end[1], end[2]));
		}
		return new SessionRecord(_start, System.currentTimeMillis(), values, classes);
	}
	
	/**
	 * @return the class id, level and experience of a class of the player, or {@code null} if it has no such class
	 */
	private static long[] getClassState(Player player, int classIndex)
	{
		if (classIndex == 0)
		{
			return new long[]
			{
				player.getBaseClass(),
				player.getStat().getBaseLevel(),
				player.getStat().getBaseExp()
			};
		}
		
		final SubClassHolder subClass = player.getSubClasses().get(classIndex);
		if (subClass == null)
		{
			return null;
		}
		return new long[]
		{
			subClass.getId(),
			subClass.getLevel(),
			subClass.getExp()
		};
	}
}
