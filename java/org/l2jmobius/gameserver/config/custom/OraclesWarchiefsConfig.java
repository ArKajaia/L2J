package org.l2jmobius.gameserver.config.custom;

import java.util.HashSet;
import java.util.Set;

import org.l2jmobius.commons.util.ConfigReader;

/**
 * Loads the Oracles and Totem Warchiefs configuration: the Prophet line (Prophet, Hierophant) and the Warcryer line (Warcryer, Doomcryer) no longer buff, they cast prophecies and plant totems (see {@link org.l2jmobius.gameserver.model.actor.holders.player.Prophecies} and
 * {@link org.l2jmobius.gameserver.model.actor.holders.player.Totems}).
 * @author Mobius
 */
public class OraclesWarchiefsConfig
{
	// File
	private static final String ORACLES_WARCHIEFS_CONFIG_FILE = "./config/Custom/OraclesWarchiefs.ini";

	// Oracles
	public static double DOOM_STILL_BONUS;
	public static int DOOM_STILL_RANGE;
	public static double SALVATION_RETURN;
	public static double FULFILMENT_MULTIPLIER;
	public static int REVERSAL_MAX_DISTANCE;
	public static double GLIMPSE_HP_THRESHOLD;
	public static int GLIMPSE_COOLDOWN;
	public static int GLIMPSE_DISTANCE;
	public static int DOOM_MAX_STACKS;
	public static int DOOM_SPREAD_RANGE;
	public static double DOOM_HIEROPHANT_REUSE;

	// Totem Warchiefs
	public static int TOTEM_MAX_COUNT;
	public static int TOTEM_LIFETIME;
	public static int GREAT_TOTEM_LIFETIME;
	public static int TOTEM_PULSE;
	public static int TOTEM_RANGE;
	public static int GREAT_TOTEM_RANGE;
	public static int TOTEM_LEASH;
	public static double ANCESTORS_REVIVE_HP;
	public static int SHATTER_RANGE;
	public static int WAR_DRUMS_REUSE_CUT;
	public static int FLAME_TOTEM_RANGE;
	public static int FLAME_TOTEM_MAX_TARGETS;

	// Retired buffs
	public static boolean RETIRED_SKILLS_REMOVE;
	public static Set<Integer> RETIRED_SKILLS = new HashSet<>();

	public static void load()
	{
		final ConfigReader config = new ConfigReader(ORACLES_WARCHIEFS_CONFIG_FILE);

		DOOM_STILL_BONUS = Math.max(1, config.getDouble("DoomStillBonus", 1.5));
		DOOM_STILL_RANGE = Math.max(0, config.getInt("DoomStillRange", 300));
		SALVATION_RETURN = Math.max(0, config.getDouble("SalvationReturn", 1.0));
		FULFILMENT_MULTIPLIER = Math.max(1, config.getDouble("FulfilmentMultiplier", 1.5));
		REVERSAL_MAX_DISTANCE = Math.max(0, config.getInt("ReversalMaxDistance", 3000));
		GLIMPSE_HP_THRESHOLD = Math.min(1, Math.max(0.01, config.getDouble("GlimpseHpThreshold", 0.3)));
		GLIMPSE_COOLDOWN = Math.max(0, config.getInt("GlimpseCooldown", 60)) * 1000;
		GLIMPSE_DISTANCE = Math.max(50, config.getInt("GlimpseDistance", 300));
		DOOM_MAX_STACKS = Math.max(1, config.getInt("DoomMaxStacks", 5));
		DOOM_SPREAD_RANGE = Math.max(0, config.getInt("DoomSpreadRange", 200));
		DOOM_HIEROPHANT_REUSE = Math.min(1, Math.max(0, config.getDouble("DoomHierophantReuse", 0.5)));

		TOTEM_MAX_COUNT = Math.max(1, config.getInt("TotemMaxCount", 3));
		TOTEM_LIFETIME = Math.max(5, config.getInt("TotemLifetime", 30)) * 1000;
		GREAT_TOTEM_LIFETIME = Math.max(5, config.getInt("GreatTotemLifetime", 20)) * 1000;
		TOTEM_PULSE = Math.max(500, config.getInt("TotemPulse", 2000));
		TOTEM_RANGE = Math.max(100, config.getInt("TotemRange", 600));
		GREAT_TOTEM_RANGE = Math.max(100, config.getInt("GreatTotemRange", 900));
		TOTEM_LEASH = Math.max(TOTEM_RANGE, config.getInt("TotemLeash", 2500));
		ANCESTORS_REVIVE_HP = Math.min(1, Math.max(0.01, config.getDouble("AncestorsReviveHp", 0.3)));
		SHATTER_RANGE = Math.max(50, config.getInt("ShatterRange", 300));
		WAR_DRUMS_REUSE_CUT = Math.max(0, config.getInt("WarDrumsReuseCut", 1000));
		FLAME_TOTEM_RANGE = Math.max(50, config.getInt("FlameTotemRange", 400));
		FLAME_TOTEM_MAX_TARGETS = Math.max(1, config.getInt("FlameTotemMaxTargets", 10));

		RETIRED_SKILLS_REMOVE = config.getBoolean("RetiredSkillsRemove", true);
		RETIRED_SKILLS.clear();
		for (String id : config.getString("RetiredSkills", "").split(","))
		{
			try
			{
				if (!id.isBlank())
				{
					RETIRED_SKILLS.add(Integer.parseInt(id.trim()));
				}
			}
			catch (NumberFormatException e)
			{
				// Skip a bad id.
			}
		}
	}
}
