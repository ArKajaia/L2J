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
package org.l2jmobius.gameserver.model.arena;

import org.l2jmobius.gameserver.config.custom.InfusedMonsterConfig;
import org.l2jmobius.gameserver.config.custom.MageMonsterConfig;
import org.l2jmobius.gameserver.config.custom.MonsterRageConfig;

/**
 * A one-wave twist on the Survival Arena challenger (see {@link org.l2jmobius.gameserver.managers.ArenaSurvivalManager}). Every omen makes the wave harder in its own way and pays {@link #getPayBonusPercent()} more for it.
 * <p>
 * The stat parts are read by the arena branches of {@code Monster}'s stat getters and damage hooks; the variant part reuses an existing monster variant (champion tier, infusion, rage, Mage) for the length of the wave.
 */
public enum ArenaOmen
{
	CHAMPIONS_MANTLE("Champion's Mantle", "It fights as an Elite champion.", 30, Variant.CHAMPION, 1, 1, 1, 1, 1, 0, 0),
	ELEMENTAL_INFUSION("Elemental Infusion", "Infused with an element: it strikes with it and pulses novas.", 25, Variant.INFUSED, 1, 1, 1, 1, 1, 0, 0),
	FRENZY("Frenzy", "It flies into a rage.", 20, Variant.RAGE, 1, 1, 1, 1, 1, 0, 0),
	ARCANE_SURGE("Arcane Surge", "It fights as a Mage, casting faster.", 25, Variant.MAGE, 1, 1, 1, 1, 1, 0, 0),
	GLASS_CANNON("Glass Cannon", "It deals and takes 40% more damage.", 25, Variant.NONE, 1.4, 1.4, 1, 1, 1, 0, 0),
	SPELL_WARDED("Spell-Warded", "Skills and spells deal half damage to it.", 20, Variant.NONE, 1, 1, 0.5, 1, 1, 0, 0),
	IRON_HIDE("Iron Hide", "Its P. Def. and M. Def. are 50% higher.", 20, Variant.NONE, 1, 1, 1, 1.5, 1, 0, 0),
	HASTE("Haste", "It attacks and casts 30% faster and enrages 30 seconds sooner.", 25, Variant.NONE, 1, 1, 1, 1, 1.3, -30, 0),
	VAMPIRIC("Vampiric", "It heals 10% of the damage it deals.", 25, Variant.NONE, 1, 1, 1, 1, 1, 0, 10);

	/** The existing monster variant an omen turns on for its wave. */
	public enum Variant
	{
		NONE,
		CHAMPION,
		INFUSED,
		RAGE,
		MAGE
	}

	private final String _name;
	private final String _description;
	private final int _payBonusPercent;
	private final Variant _variant;
	private final double _attackMultiplier;
	private final double _damageTakenMultiplier;
	private final double _skillDamageTakenMultiplier;
	private final double _defenseMultiplier;
	private final double _speedMultiplier;
	private final int _enrageSecondsDelta;
	private final int _lifeStealPercent;

	ArenaOmen(String name, String description, int payBonusPercent, Variant variant, double attackMultiplier, double damageTakenMultiplier, double skillDamageTakenMultiplier, double defenseMultiplier, double speedMultiplier, int enrageSecondsDelta, int lifeStealPercent)
	{
		_name = name;
		_description = description;
		_payBonusPercent = payBonusPercent;
		_variant = variant;
		_attackMultiplier = attackMultiplier;
		_damageTakenMultiplier = damageTakenMultiplier;
		_skillDamageTakenMultiplier = skillDamageTakenMultiplier;
		_defenseMultiplier = defenseMultiplier;
		_speedMultiplier = speedMultiplier;
		_enrageSecondsDelta = enrageSecondsDelta;
		_lifeStealPercent = lifeStealPercent;
	}

	public String getName()
	{
		return _name;
	}

	public String getDescription()
	{
		return _description;
	}

	public int getPayBonusPercent()
	{
		return _payBonusPercent;
	}

	public Variant getVariant()
	{
		return _variant;
	}

	/** Multiplies the challenger's P. Atk. and M. Atk. */
	public double getAttackMultiplier()
	{
		return _attackMultiplier;
	}

	/** Multiplies all damage the challenger takes. */
	public double getDamageTakenMultiplier()
	{
		return _damageTakenMultiplier;
	}

	/** Multiplies the damage the challenger takes from skills (including their damage over time). */
	public double getSkillDamageTakenMultiplier()
	{
		return _skillDamageTakenMultiplier;
	}

	/** Multiplies the challenger's P. Def. and M. Def. */
	public double getDefenseMultiplier()
	{
		return _defenseMultiplier;
	}

	/** Multiplies the challenger's attack and cast speed. */
	public double getSpeedMultiplier()
	{
		return _speedMultiplier;
	}

	/** Seconds added to (or, when negative, taken from) the wave's enrage timer. */
	public int getEnrageSecondsDelta()
	{
		return _enrageSecondsDelta;
	}

	/** Percent of the damage the challenger deals that heals it. */
	public int getLifeStealPercent()
	{
		return _lifeStealPercent;
	}

	/**
	 * @return {@code true} if the server runs the system this omen's variant needs (an omen whose variant system is turned off is never rolled)
	 */
	public boolean isAvailable()
	{
		switch (_variant)
		{
			case INFUSED:
			{
				return InfusedMonsterConfig.ENABLED;
			}
			case RAGE:
			{
				return MonsterRageConfig.ENABLED;
			}
			case MAGE:
			{
				return MageMonsterConfig.ENABLED;
			}
			default:
			{
				return true;
			}
		}
	}
}
