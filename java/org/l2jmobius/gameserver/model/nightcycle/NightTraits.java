package org.l2jmobius.gameserver.model.nightcycle;

import org.l2jmobius.gameserver.config.custom.NightCycleConfig;
import org.l2jmobius.gameserver.model.actor.Player;
import org.l2jmobius.gameserver.model.actor.enums.creature.Race;
import org.l2jmobius.gameserver.model.conditions.ConditionGameTime;
import org.l2jmobius.gameserver.model.stats.Stat;
import org.l2jmobius.gameserver.model.stats.functions.FuncAdd;
import org.l2jmobius.gameserver.model.stats.functions.FuncMul;

/**
 * Children of the Night: each race feels the night, like the Dark Elves' retail Shadow Sense (see {@link org.l2jmobius.gameserver.managers.NightCycleManager}).
 * <ul>
 * <li>Human - Watchfire: HP regeneration and Accuracy.</li>
 * <li>Elf - Starlight: MP regeneration and Casting speed.</li>
 * <li>Orc - Blood Moon Fury: P.Atk.</li>
 * <li>Dwarf - Forgelight: P.Def and Accuracy.</li>
 * <li>Kamael - Dusk Wings: Speed.</li>
 * </ul>
 * The bonuses are stat functions with a night condition ({@link ConditionGameTime}, which follows the Night Cycle, a GM-forced night too), so they are added once and work by themselves. No skill is needed, so nothing unknown shows in the client's skill window.
 */
public class NightTraits
{
	/** Owns every stat function of the traits: {@code removeStatsOwner} takes them all away at once. */
	private static final Object OWNER = new Object();
	private static final ConditionGameTime AT_NIGHT = new ConditionGameTime(true);

	private NightTraits()
	{
	}

	/**
	 * Gives {@code player} the night trait of its race, taking away the old one first: safe to repeat (login, nightfall, a config reload).
	 * @param player the player
	 */
	public static void apply(Player player)
	{
		player.removeStatsOwner(OWNER);
		if (!isEnabled())
		{
			return;
		}

		switch (player.getRace())
		{
			case HUMAN:
			{
				mul(player, Stat.REGENERATE_HP_RATE, NightCycleConfig.NIGHT_TRAIT_HUMAN_HP_REGEN);
				add(player, Stat.ACCURACY_COMBAT, NightCycleConfig.NIGHT_TRAIT_HUMAN_ACCURACY);
				break;
			}
			case ELF:
			{
				mul(player, Stat.REGENERATE_MP_RATE, NightCycleConfig.NIGHT_TRAIT_ELF_MP_REGEN);
				mul(player, Stat.MAGIC_ATTACK_SPEED, NightCycleConfig.NIGHT_TRAIT_ELF_CAST_SPEED);
				break;
			}
			case ORC:
			{
				mul(player, Stat.POWER_ATTACK, NightCycleConfig.NIGHT_TRAIT_ORC_PATK);
				break;
			}
			case DWARF:
			{
				mul(player, Stat.POWER_DEFENCE, NightCycleConfig.NIGHT_TRAIT_DWARF_PDEF);
				add(player, Stat.ACCURACY_COMBAT, NightCycleConfig.NIGHT_TRAIT_DWARF_ACCURACY);
				break;
			}
			case KAMAEL:
			{
				add(player, Stat.MOVE_SPEED, NightCycleConfig.NIGHT_TRAIT_KAMAEL_SPEED);
				break;
			}
			default:
			{
				break;
			}
		}
	}

	private static void add(Player player, Stat stat, double value)
	{
		if (value != 0)
		{
			player.addStatFunc(new FuncAdd(stat, 0x30, OWNER, value, AT_NIGHT));
		}
	}

	private static void mul(Player player, Stat stat, double percent)
	{
		if (percent != 0)
		{
			player.addStatFunc(new FuncMul(stat, 0x30, OWNER, 1.0 + (percent / 100.0), AT_NIGHT));
		}
	}

	private static boolean isEnabled()
	{
		return NightCycleConfig.ENABLED && NightCycleConfig.NIGHT_RACE_TRAITS_ENABLED;
	}

	/**
	 * @param race a race
	 * @return the name of its night trait, {@code null} if it has none here (Dark Elves keep the retail Shadow Sense)
	 */
	public static String getName(Race race)
	{
		switch (race)
		{
			case HUMAN:
			{
				return "Watchfire";
			}
			case ELF:
			{
				return "Starlight";
			}
			case ORC:
			{
				return "Blood Moon Fury";
			}
			case DWARF:
			{
				return "Forgelight";
			}
			case KAMAEL:
			{
				return "Dusk Wings";
			}
			default:
			{
				return null;
			}
		}
	}

	/**
	 * @param player the player
	 * @return its night trait as players read it, e.g. "Watchfire: HP regeneration +20%, Accuracy +3", {@code null} if it has none or the traits are off
	 */
	public static String describe(Player player)
	{
		if (!isEnabled())
		{
			return null;
		}

		switch (player.getRace())
		{
			case HUMAN:
			{
				return "Watchfire: HP regeneration +" + format(NightCycleConfig.NIGHT_TRAIT_HUMAN_HP_REGEN) + "%, Accuracy +" + format(NightCycleConfig.NIGHT_TRAIT_HUMAN_ACCURACY);
			}
			case ELF:
			{
				return "Starlight: MP regeneration +" + format(NightCycleConfig.NIGHT_TRAIT_ELF_MP_REGEN) + "%, Casting speed +" + format(NightCycleConfig.NIGHT_TRAIT_ELF_CAST_SPEED) + "%";
			}
			case DARK_ELF:
			{
				return "Shadow Sense: Accuracy +3";
			}
			case ORC:
			{
				return "Blood Moon Fury: P.Atk +" + format(NightCycleConfig.NIGHT_TRAIT_ORC_PATK) + "%";
			}
			case DWARF:
			{
				return "Forgelight: P.Def +" + format(NightCycleConfig.NIGHT_TRAIT_DWARF_PDEF) + "%, Accuracy +" + format(NightCycleConfig.NIGHT_TRAIT_DWARF_ACCURACY);
			}
			case KAMAEL:
			{
				return "Dusk Wings: Speed +" + format(NightCycleConfig.NIGHT_TRAIT_KAMAEL_SPEED);
			}
			default:
			{
				return null;
			}
		}
	}

	private static String format(double value)
	{
		return value == Math.rint(value) ? Long.toString((long) value) : Double.toString(value);
	}

	/**
	 * Nightfall or dawn: the player feels its trait come or go, and sees its stats change.
	 * @param player the player
	 * @param night {@code true} at nightfall
	 */
	public static void onPhaseChange(Player player, boolean night)
	{
		if (night)
		{
			apply(player); // Picks up a config reload.
		}

		final String name = isEnabled() ? getName(player.getRace()) : null;
		if (name != null)
		{
			player.sendMessage(night ? "Night falls, and you feel the " + name + " in you: " + describe(player) + "." : "Dawn breaks, and the " + name + " in you fades.");
		}
		player.broadcastUserInfo();
	}
}
