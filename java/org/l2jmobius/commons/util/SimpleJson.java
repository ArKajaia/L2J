package org.l2jmobius.commons.util;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A small JSON reader for the few places that take JSON from a web page (the admin passive tree editor). Objects become {@link LinkedHashMap}s, arrays {@link ArrayList}s, numbers {@link Double}s, and {@code true}/{@code false}/{@code null} what they say.
 * <p>
 * Nesting is limited, so a hostile body can't overflow the stack.
 */
public final class SimpleJson
{
	private static final int MAX_DEPTH = 64;

	private final String _text;
	private int _pos;

	private SimpleJson(String text)
	{
		_text = text;
	}

	/**
	 * @param text JSON text
	 * @return the value it holds
	 * @throws IllegalArgumentException if the text is not valid JSON
	 */
	public static Object parse(String text)
	{
		final SimpleJson reader = new SimpleJson(text);
		final Object value = reader.readValue(0);
		reader.skipSpace();
		if (reader._pos != text.length())
		{
			throw reader.error("unexpected text after the value");
		}
		return value;
	}

	/**
	 * @param text a string
	 * @return it as a JSON string literal, quotes included
	 */
	public static String quote(String text)
	{
		final StringBuilder sb = new StringBuilder((text == null ? 0 : text.length()) + 2).append('"');
		if (text != null)
		{
			for (int i = 0; i < text.length(); i++)
			{
				final char c = text.charAt(i);
				switch (c)
				{
					case '"':
						sb.append("\\\"");
						break;
					case '\\':
						sb.append("\\\\");
						break;
					case '\n':
						sb.append("\\n");
						break;
					case '\r':
						sb.append("\\r");
						break;
					case '\t':
						sb.append("\\t");
						break;
					case '<':
						sb.append("\\u003c"); // never a "</script>" in a page that embeds the JSON
						break;
					default:
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
		return sb.append('"').toString();
	}

	private Object readValue(int depth)
	{
		if (depth > MAX_DEPTH)
		{
			throw error("nested too deep");
		}

		skipSpace();
		if (_pos >= _text.length())
		{
			throw error("unexpected end");
		}

		final char c = _text.charAt(_pos);
		switch (c)
		{
			case '{':
				return readObject(depth);
			case '[':
				return readArray(depth);
			case '"':
				return readString();
			case 't':
				expectWord("true");
				return Boolean.TRUE;
			case 'f':
				expectWord("false");
				return Boolean.FALSE;
			case 'n':
				expectWord("null");
				return null;
			default:
				if ((c == '-') || ((c >= '0') && (c <= '9')))
				{
					return readNumber();
				}
				throw error("unexpected character '" + c + "'");
		}
	}

	private Map<String, Object> readObject(int depth)
	{
		final Map<String, Object> map = new LinkedHashMap<>();
		_pos++; // {
		skipSpace();
		if (peek() == '}')
		{
			_pos++;
			return map;
		}

		while (true)
		{
			skipSpace();
			if (peek() != '"')
			{
				throw error("expected a key");
			}
			final String key = readString();
			skipSpace();
			if (peek() != ':')
			{
				throw error("expected ':'");
			}
			_pos++;
			map.put(key, readValue(depth + 1));
			skipSpace();
			final char next = peek();
			_pos++;
			if (next == '}')
			{
				return map;
			}
			if (next != ',')
			{
				throw error("expected ',' or '}'");
			}
		}
	}

	private List<Object> readArray(int depth)
	{
		final List<Object> list = new ArrayList<>();
		_pos++; // [
		skipSpace();
		if (peek() == ']')
		{
			_pos++;
			return list;
		}

		while (true)
		{
			list.add(readValue(depth + 1));
			skipSpace();
			final char next = peek();
			_pos++;
			if (next == ']')
			{
				return list;
			}
			if (next != ',')
			{
				throw error("expected ',' or ']'");
			}
		}
	}

	private String readString()
	{
		_pos++; // opening quote
		final StringBuilder sb = new StringBuilder();
		while (true)
		{
			if (_pos >= _text.length())
			{
				throw error("unterminated string");
			}

			final char c = _text.charAt(_pos++);
			if (c == '"')
			{
				return sb.toString();
			}
			if (c != '\\')
			{
				sb.append(c);
				continue;
			}

			if (_pos >= _text.length())
			{
				throw error("unterminated string");
			}
			final char escaped = _text.charAt(_pos++);
			switch (escaped)
			{
				case '"':
				case '\\':
				case '/':
					sb.append(escaped);
					break;
				case 'b':
					sb.append('\b');
					break;
				case 'f':
					sb.append('\f');
					break;
				case 'n':
					sb.append('\n');
					break;
				case 'r':
					sb.append('\r');
					break;
				case 't':
					sb.append('\t');
					break;
				case 'u':
					if ((_pos + 4) > _text.length())
					{
						throw error("bad \\u escape");
					}
					try
					{
						sb.append((char) Integer.parseInt(_text.substring(_pos, _pos + 4), 16));
					}
					catch (NumberFormatException e)
					{
						throw error("bad \\u escape");
					}
					_pos += 4;
					break;
				default:
					throw error("bad escape \\" + escaped);
			}
		}
	}

	private Double readNumber()
	{
		final int start = _pos;
		while (_pos < _text.length())
		{
			final char c = _text.charAt(_pos);
			if (((c >= '0') && (c <= '9')) || (c == '-') || (c == '+') || (c == '.') || (c == 'e') || (c == 'E'))
			{
				_pos++;
			}
			else
			{
				break;
			}
		}

		try
		{
			return Double.valueOf(_text.substring(start, _pos));
		}
		catch (NumberFormatException e)
		{
			throw error("bad number");
		}
	}

	private void expectWord(String word)
	{
		if (!_text.startsWith(word, _pos))
		{
			throw error("expected " + word);
		}
		_pos += word.length();
	}

	private char peek()
	{
		if (_pos >= _text.length())
		{
			throw error("unexpected end");
		}
		return _text.charAt(_pos);
	}

	private void skipSpace()
	{
		while ((_pos < _text.length()) && Character.isWhitespace(_text.charAt(_pos)))
		{
			_pos++;
		}
	}

	private IllegalArgumentException error(String message)
	{
		return new IllegalArgumentException("JSON: " + message + " at position " + _pos);
	}
}
