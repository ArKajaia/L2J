package org.l2jmobius.gameserver.model.passivetree;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Per-key request limiter for the web planner: a fixed window counter, plus a failure lockout used for PIN guessing. Every method takes the time as an argument so it can be tested without waiting.
 */
public class WebRateLimiter
{
	private static final int MAX_KEYS = 20000; // a flood of different addresses can't grow the maps without end

	private static final class Window
	{
		long _start;
		int _count;
	}

	private static final class Failures
	{
		int _count;
		long _lockedUntil;
	}

	private final int _maxPerWindow;
	private final long _windowMs;
	private final Map<String, Window> _windows = new ConcurrentHashMap<>();
	private final Map<String, Failures> _failures = new ConcurrentHashMap<>();

	public WebRateLimiter(int maxPerWindow, long windowMs)
	{
		_maxPerWindow = maxPerWindow;
		_windowMs = windowMs;
	}

	/**
	 * Counts one request.
	 * @param key who is asking, normally the remote address
	 * @param now current time in milliseconds
	 * @return {@code true} if it is within the limit, {@code false} if it must be refused
	 */
	public boolean tryAcquire(String key, long now)
	{
		if (_windows.size() >= MAX_KEYS)
		{
			_windows.values().removeIf(w -> (now - w._start) >= _windowMs);
			if (_windows.size() >= MAX_KEYS)
			{
				return false;
			}
		}

		final boolean[] allowed = new boolean[1];
		_windows.compute(key, (k, w) ->
		{
			if ((w == null) || ((now - w._start) >= _windowMs))
			{
				w = new Window();
				w._start = now;
			}
			w._count++;
			allowed[0] = w._count <= _maxPerWindow;
			return w;
		});
		return allowed[0];
	}

	/**
	 * @param key who is asking
	 * @param now current time in milliseconds
	 * @return seconds until the next request is allowed, never below 1
	 */
	public long retryAfterSeconds(String key, long now)
	{
		final Window w = _windows.get(key);
		final long untilWindowEnds = w == null ? 0 : (w._start + _windowMs) - now;
		return Math.max(1, (untilWindowEnds + 999) / 1000);
	}

	/**
	 * @param key who is asking
	 * @param now current time in milliseconds
	 * @return {@code true} while the key is locked out after too many failures
	 */
	public boolean isLockedOut(String key, long now)
	{
		final Failures f = _failures.get(key);
		return (f != null) && (f._lockedUntil > now);
	}

	/**
	 * @param key who is asking
	 * @param now current time in milliseconds
	 * @return seconds left of the lockout, 0 if there is none
	 */
	public long lockoutSeconds(String key, long now)
	{
		final Failures f = _failures.get(key);
		return (f == null) || (f._lockedUntil <= now) ? 0 : ((f._lockedUntil - now) + 999) / 1000;
	}

	/**
	 * Records a failed attempt. When maxFailures is reached the key is locked out and its failure count starts over.
	 * @param key who failed
	 * @param now current time in milliseconds
	 * @param maxFailures failures that trigger the lockout
	 * @param lockoutMs how long the lockout lasts
	 */
	public void recordFailure(String key, long now, int maxFailures, long lockoutMs)
	{
		if (_failures.size() >= MAX_KEYS)
		{
			_failures.values().removeIf(f -> f._lockedUntil <= now);
		}
		_failures.compute(key, (k, f) ->
		{
			if (f == null)
			{
				f = new Failures();
			}
			f._count++;
			if (f._count >= maxFailures)
			{
				f._count = 0;
				f._lockedUntil = now + lockoutMs;
			}
			return f;
		});
	}

	/**
	 * Forgets the failures of a key, after it got something right.
	 * @param key who succeeded
	 */
	public void clearFailures(String key)
	{
		_failures.remove(key);
	}

	/** Drops entries that can no longer matter. */
	public void cleanup(long now)
	{
		_windows.values().removeIf(w -> (now - w._start) >= _windowMs);
		_failures.values().removeIf(f -> (f._lockedUntil <= now) && (f._count == 0));
	}
}
