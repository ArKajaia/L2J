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
package org.l2jmobius.gameserver.managers.simulation;

import java.util.Collection;
import java.util.Map;

/**
 * Builds one JSON object for the simulation logs, in the order the fields are put. Values are strings, numbers, booleans, {@code null}, nested {@link JsonLine}s, maps and collections of those.
 */
public class JsonLine
{
	private final StringBuilder _sb = new StringBuilder(160).append('{');
	private boolean _empty = true;

	public JsonLine put(String key, Object value)
	{
		if (!_empty)
		{
			_sb.append(',');
		}
		_empty = false;
		quote(_sb, key);
		_sb.append(':');
		write(_sb, value);
		return this;
	}

	/**
	 * Puts {@code value} rounded to 3 decimals (ratios), or {@code null}.
	 */
	public JsonLine ratio(String key, double value)
	{
		return put(key, Double.isNaN(value) ? null : Math.round(value * 1000) / 1000.0);
	}

	@Override
	public String toString()
	{
		return _sb.toString() + '}';
	}

	private static void write(StringBuilder sb, Object value)
	{
		if (value == null)
		{
			sb.append("null");
		}
		else if ((value instanceof Number) || (value instanceof Boolean))
		{
			sb.append(value);
		}
		else if (value instanceof JsonLine line)
		{
			sb.append(line);
		}
		else if (value instanceof Map<?, ?> map)
		{
			sb.append('{');
			boolean first = true;
			for (Map.Entry<?, ?> entry : map.entrySet())
			{
				if (!first)
				{
					sb.append(',');
				}
				first = false;
				quote(sb, String.valueOf(entry.getKey()));
				sb.append(':');
				write(sb, entry.getValue());
			}
			sb.append('}');
		}
		else if (value instanceof Collection<?> list)
		{
			sb.append('[');
			boolean first = true;
			for (Object item : list)
			{
				if (!first)
				{
					sb.append(',');
				}
				first = false;
				write(sb, item);
			}
			sb.append(']');
		}
		else
		{
			quote(sb, value.toString());
		}
	}

	private static void quote(StringBuilder sb, String text)
	{
		sb.append('"');
		for (int i = 0; i < text.length(); i++)
		{
			final char c = text.charAt(i);
			switch (c)
			{
				case '"' -> sb.append("\\\"");
				case '\\' -> sb.append("\\\\");
				case '\n' -> sb.append("\\n");
				case '\r' -> sb.append("\\r");
				case '\t' -> sb.append("\\t");
				default ->
				{
					if (c < 0x20)
					{
						sb.append(String.format("\\u%04x", (int) c));
					}
					else
					{
						sb.append(c);
					}
				}
			}
		}
		sb.append('"');
	}
}
