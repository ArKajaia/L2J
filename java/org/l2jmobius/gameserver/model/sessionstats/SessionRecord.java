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
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;

/**
 * One session of a character, finished or in progress: when it started and ended, the counted {@link SessionStat}s and where each class played in it started and ended.<br>
 * Saved as {@code s=<start>,e=<end>,<key>=<value>...,cl=<class>:<level>:<exp>:<level>:<exp>/...}.
 */
public class SessionRecord
{
	/**
	 * A class played in the session, with its level and experience at the start and at the end.
	 */
	public static class ClassLine
	{
		private final int _classId;
		private final int _startLevel;
		private final long _startExp;
		private final int _endLevel;
		private final long _endExp;
		
		public ClassLine(int classId, int startLevel, long startExp, int endLevel, long endExp)
		{
			_classId = classId;
			_startLevel = startLevel;
			_startExp = startExp;
			_endLevel = endLevel;
			_endExp = endExp;
		}
		
		public int getClassId()
		{
			return _classId;
		}
		
		public int getStartLevel()
		{
			return _startLevel;
		}
		
		public long getStartExp()
		{
			return _startExp;
		}
		
		public int getEndLevel()
		{
			return _endLevel;
		}
		
		public long getEndExp()
		{
			return _endExp;
		}
	}
	
	private final long _start;
	private final long _end;
	private final Map<SessionStat, Long> _values;
	private final List<ClassLine> _classes;
	
	public SessionRecord(long start, long end, Map<SessionStat, Long> values, List<ClassLine> classes)
	{
		_start = start;
		_end = end;
		_values = new EnumMap<>(SessionStat.class);
		_values.putAll(values);
		_classes = Collections.unmodifiableList(new ArrayList<>(classes));
	}
	
	public long getStart()
	{
		return _start;
	}
	
	public long getEnd()
	{
		return _end;
	}
	
	public long getDurationMillis()
	{
		return Math.max(0, _end - _start);
	}
	
	public long get(SessionStat stat)
	{
		final Long value = _values.get(stat);
		return value == null ? 0 : value;
	}
	
	public List<ClassLine> getClasses()
	{
		return _classes;
	}
	
	/**
	 * @return the levels gained (or lost) by all the classes played
	 */
	public int getLevelsGained()
	{
		int levels = 0;
		for (ClassLine line : _classes)
		{
			levels += line.getEndLevel() - line.getStartLevel();
		}
		return levels;
	}
	
	/**
	 * @return how much adena the inventory holds more (or less) than at the start
	 */
	public long getAdenaChange()
	{
		return get(SessionStat.ADENA_END) - get(SessionStat.ADENA_START);
	}
	
	/**
	 * @return {@code true} if anything was achieved: experience, a level, a kill, adena, a quest, an item...
	 */
	public boolean hasProgress()
	{
		if (getLevelsGained() != 0)
		{
			return true;
		}
		for (Entry<SessionStat, Long> entry : _values.entrySet())
		{
			if ((entry.getKey() != SessionStat.ADENA_START) && (entry.getKey() != SessionStat.ADENA_END) && (entry.getValue() != 0))
			{
				return true;
			}
		}
		return getAdenaChange() != 0;
	}
	
	public String serialize()
	{
		final StringBuilder sb = new StringBuilder();
		sb.append("s=").append(_start).append(",e=").append(_end);
		for (Entry<SessionStat, Long> entry : _values.entrySet())
		{
			if (entry.getValue() != 0)
			{
				sb.append(',').append(entry.getKey().getKey()).append('=').append(entry.getValue());
			}
		}
		if (!_classes.isEmpty())
		{
			sb.append(",cl=");
			for (int i = 0; i < _classes.size(); i++)
			{
				final ClassLine line = _classes.get(i);
				sb.append(i > 0 ? "/" : "").append(line.getClassId()).append(':').append(line.getStartLevel()).append(':').append(line.getStartExp()).append(':').append(line.getEndLevel()).append(':').append(line.getEndExp());
			}
		}
		return sb.toString();
	}
	
	/**
	 * @param text a session saved by {@link #serialize()}
	 * @return the session, or {@code null} if the text can't be read
	 */
	public static SessionRecord parse(String text)
	{
		try
		{
			long start = 0;
			long end = 0;
			final Map<SessionStat, Long> values = new EnumMap<>(SessionStat.class);
			final List<ClassLine> classes = new ArrayList<>();
			for (String part : text.split(","))
			{
				final int eq = part.indexOf('=');
				if (eq <= 0)
				{
					continue;
				}
				
				final String key = part.substring(0, eq);
				final String value = part.substring(eq + 1);
				switch (key)
				{
					case "s":
					{
						start = Long.parseLong(value);
						break;
					}
					case "e":
					{
						end = Long.parseLong(value);
						break;
					}
					case "cl":
					{
						for (String line : value.split("/"))
						{
							final String[] fields = line.split(":");
							if (fields.length == 5)
							{
								classes.add(new ClassLine(Integer.parseInt(fields[0]), Integer.parseInt(fields[1]), Long.parseLong(fields[2]), Integer.parseInt(fields[3]), Long.parseLong(fields[4])));
							}
						}
						break;
					}
					default:
					{
						final SessionStat stat = SessionStat.fromKey(key);
						if (stat != null)
						{
							values.put(stat, Long.parseLong(value));
						}
						break;
					}
				}
			}
			return start > 0 ? new SessionRecord(start, end, values, classes) : null;
		}
		catch (NumberFormatException e)
		{
			return null;
		}
	}
}
