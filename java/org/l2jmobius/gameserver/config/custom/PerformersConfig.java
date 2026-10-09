package org.l2jmobius.gameserver.config.custom;

import org.l2jmobius.commons.util.ConfigReader;

/**
 * Loads the performers configuration: what the Swordsinger and Bladedancer lines (Swordsinger, Sword Muse, Bladedancer, Spectral Dancer) do with their own songs and dances that a character who learned them with Glittering Medals doesn't (see {@link org.l2jmobius.gameserver.model.actor.holders.player.Performers}).
 * @author Mobius
 */
public class PerformersConfig
{
	// File
	private static final String PERFORMERS_CONFIG_FILE = "./config/Custom/Performers.ini";

	public static boolean PERFORMERS_ENABLED;

	// Virtuoso: index 0 = 2nd class (Swordsinger, Bladedancer), 1 = 3rd class (Sword Muse, Spectral Dancer).
	public static double[] DANCE_DURATION_MULTIPLIER;
	public static boolean NO_DANCE_STACKING_MP_COST;

	// Performances
	public static boolean HEX_AFFECTS_PLAYERS;

	public static void load()
	{
		final ConfigReader config = new ConfigReader(PERFORMERS_CONFIG_FILE);
		PERFORMERS_ENABLED = config.getBoolean("PerformersEnabled", true);

		DANCE_DURATION_MULTIPLIER = getTiers(config, "DanceDurationMultiplier", "1.5,2.0", 1);
		NO_DANCE_STACKING_MP_COST = config.getBoolean("NoDanceStackingMpCost", true);

		HEX_AFFECTS_PLAYERS = config.getBoolean("HexAffectsPlayers", true);
	}

	/**
	 * @param config the reader
	 * @param key the setting
	 * @param defaultValue two comma separated values, for the 2nd and 3rd class
	 * @param min the lowest value allowed
	 * @return the two values; a missing one repeats the last one given
	 */
	private static double[] getTiers(ConfigReader config, String key, String defaultValue, double min)
	{
		final double[] defaults = parse(defaultValue, new double[2], min);
		return parse(config.getString(key, defaultValue), defaults, min);
	}

	private static double[] parse(String text, double[] fallback, double min)
	{
		final double[] result = fallback.clone();
		final String[] parts = text.split(",");
		for (int i = 0; i < result.length; i++)
		{
			try
			{
				final String part = parts[Math.min(i, parts.length - 1)].trim();
				result[i] = Math.max(min, Double.parseDouble(part));
			}
			catch (NumberFormatException e)
			{
				// Keep the default for this class.
			}
		}
		return result;
	}
}
