package org.l2jmobius.gameserver.model.nightcycle;

import org.l2jmobius.gameserver.config.custom.NightCycleConfig;

/**
 * The phases of a game day, as {@link org.l2jmobius.gameserver.managers.NightCycleManager} runs them. Night is game hours 0:00-5:59 (see {@link org.l2jmobius.gameserver.taskmanagers.GameTimeTaskManager#isNight()}); its last minutes are the Witching Hour. Dusk ends the day,
 * dawn starts the next one. The lengths of dusk, the Witching Hour and dawn are set in NightCycle.ini.
 */
public enum NightPhase
{
	DAY("Day", false),
	DUSK("Dusk", false),
	NIGHT("Night", true),
	WITCHING_HOUR("Witching Hour", true),
	DAWN("Dawn", false);

	/** Game minutes in a game day. */
	public static final int MINUTES_PER_DAY = 1440;
	/** Game minute night ends at (6:00). */
	public static final int NIGHT_END = 360;

	private final String _displayName;
	private final boolean _night;

	NightPhase(String displayName, boolean night)
	{
		_displayName = displayName;
		_night = night;
	}

	public String getDisplayName()
	{
		return _displayName;
	}

	/**
	 * @return {@code true} for the phases of the night (Night and the Witching Hour)
	 */
	public boolean isNight()
	{
		return _night;
	}

	/**
	 * @param gameTime the game time, in game minutes since midnight (0-1439)
	 * @return the phase at that time
	 */
	public static NightPhase at(int gameTime)
	{
		final int time = Math.floorMod(gameTime, MINUTES_PER_DAY);
		if (time < NIGHT_END)
		{
			return time < (NIGHT_END - NightCycleConfig.WITCHING_HOUR_MINUTES) ? NIGHT : WITCHING_HOUR;
		}
		if (time < (NIGHT_END + NightCycleConfig.DAWN_MINUTES))
		{
			return DAWN;
		}
		if (time >= (MINUTES_PER_DAY - NightCycleConfig.DUSK_MINUTES))
		{
			return DUSK;
		}
		return DAY;
	}

	/**
	 * @param gameTime the game time, in game minutes since midnight (0-1439)
	 * @return the game minutes until the phase at {@code gameTime} ends
	 */
	public static int minutesLeft(int gameTime)
	{
		final int time = Math.floorMod(gameTime, MINUTES_PER_DAY);
		final NightPhase phase = at(time);
		int minutes = 1;
		while ((minutes < MINUTES_PER_DAY) && (at(time + minutes) == phase))
		{
			minutes++;
		}
		return minutes;
	}

	/**
	 * @param gameTime the game time, in game minutes since midnight (0-1439)
	 * @return the game minutes until the next nightfall (0:00), 0 while it is night
	 */
	public static int minutesToNightfall(int gameTime)
	{
		final int time = Math.floorMod(gameTime, MINUTES_PER_DAY);
		return time < NIGHT_END ? 0 : MINUTES_PER_DAY - time;
	}
}
