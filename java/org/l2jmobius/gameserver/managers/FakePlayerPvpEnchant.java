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
package org.l2jmobius.gameserver.managers;

import java.util.List;

import org.l2jmobius.commons.util.Rnd;
import org.l2jmobius.gameserver.config.custom.FakePlayerPvpConfig;
import org.l2jmobius.gameserver.config.custom.PvpSpotsConfig;
import org.l2jmobius.gameserver.data.xml.EnchantItemGroupsData;
import org.l2jmobius.gameserver.model.item.ItemTemplate;
import org.l2jmobius.gameserver.model.item.enchant.EnchantItemGroup;
import org.l2jmobius.gameserver.model.item.enums.BodyPart;

/**
 * Enchants the gear of a fake player piece by piece, like a player enchanting what it can afford (see FakePvp*EnchantOdds in config/Custom/FakePlayerPvp.ini), so its weapon, armor pieces and jewels don't all end up alike:
 * <ul>
 * <li>a piece was enchanted at all with the enchant chance of its kind, otherwise it is +0. Items that can't be enchanted (no grade, cloaks) stay +0;</li>
 * <li>an enchanted piece is at its safe level (what normal scrolls enchant without risk on this server: +3, +4 for a full body armor in High Five), now and then a bit below it;</li>
 * <li>past the safe level every step is reached with the step chance of its kind, so each step is rarer than the one before;</li>
 * <li>its wealth works like that many tries at both chances: a rich fake player enchants nearly everything, and further. The strong fake players of the PvP spots are the richest, keep the better of two rolls and add PvpSpotEliteEnchantBonus;</li>
 * <li>some went for a +6 armor set: every piece of their set is +6 or more.</li>
 * </ul>
 */
public class FakePlayerPvpEnchant
{
	/** Chance (in %) an enchanted piece stopped short of its safe level (out of scrolls). */
	private static final int BELOW_SAFE_CHANCE = 15;
	/** The enchant of every piece of a +6 armor set, which gives the set's +6 bonus. */
	private static final int ARMOR_SET_ENCHANT = 6;
	/** The highest enchant of a strong fake player of the PvP spots. */
	private static final int ELITE_MAX_ENCHANT = 20;
	/** The scroll group of the normal enchant scrolls (data/EnchantItemGroups.xml). */
	private static final int SCROLL_GROUP = 0;

	/**
	 * What a piece of gear is, for its enchant odds.
	 */
	public enum Kind
	{
		/** A weapon. */
		WEAPON,
		/** An armor piece, a shield, a shirt or a belt. */
		ARMOR,
		/** An earring, a necklace or a ring. */
		JEWEL
	}

	private final int _level;
	private final boolean _elite;
	private final double _wealth;
	private final boolean _armorSet;

	/**
	 * @param level the fake player's level
	 * @param elite {@code true} for one of the strong fake players of the PvP spots
	 */
	public FakePlayerPvpEnchant(int level, boolean elite)
	{
		_level = level;
		_elite = elite;
		_wealth = elite ? FakePlayerPvpConfig.GEAR_WEALTH_MAX : Rnd.get(FakePlayerPvpConfig.GEAR_WEALTH_MIN, FakePlayerPvpConfig.GEAR_WEALTH_MAX);
		_armorSet = roll(FakePlayerPvpConfig.ARMOR_SET_ENCHANT_CHANCE);
	}

	/**
	 * @param chances {minLevel, chance} rows (FakePvpShirtChance, FakePvpBeltChance...)
	 * @return {@code true} with the chance of the fake player's level, grown by its wealth
	 */
	public boolean roll(List<int[]> chances)
	{
		return Rnd.get(100.0) < scale(FakePlayerPvpConfig.getTierChance(chances, _level));
	}

	/**
	 * @param item a piece of gear, {@code null} for none
	 * @param kind what it is
	 * @return its enchant level
	 */
	public int roll(ItemTemplate item, Kind kind)
	{
		return roll(item, kind, false);
	}

	/**
	 * @param item a piece of gear, {@code null} for none
	 * @param kind what it is
	 * @param setPiece {@code true} for a piece of the full armor set it wears (chest, legs, helmet, gloves and boots): +6 or more if it went for a +6 set
	 * @return its enchant level
	 */
	public int roll(ItemTemplate item, Kind kind, boolean setPiece)
	{
		if ((item == null) || !item.isEnchantable())
		{
			return 0;
		}

		final int[] bounds = FakePlayerPvpConfig.getTier(kind == Kind.WEAPON ? FakePlayerPvpConfig.WEAPON_ENCHANT : FakePlayerPvpConfig.ARMOR_ENCHANT, _level);
		if (bounds == null)
		{
			return 0;
		}

		final int max = bounds[2];
		int enchant = _elite ? Math.max(rollOnce(item, kind, max), rollOnce(item, kind, max)) : rollOnce(item, kind, max);
		if (setPiece && _armorSet && (max >= ARMOR_SET_ENCHANT))
		{
			enchant = Math.max(enchant, ARMOR_SET_ENCHANT);
		}

		enchant = Math.max(bounds[1], Math.min(max, enchant));
		return _elite ? Math.min(ELITE_MAX_ENCHANT, enchant + PvpSpotsConfig.ELITE_ENCHANT_BONUS) : enchant;
	}

	private int rollOnce(ItemTemplate item, Kind kind, int max)
	{
		final int[] odds = FakePlayerPvpConfig.getTier(kind == Kind.WEAPON ? FakePlayerPvpConfig.WEAPON_ENCHANT_ODDS : kind == Kind.ARMOR ? FakePlayerPvpConfig.ARMOR_ENCHANT_ODDS : FakePlayerPvpConfig.JEWEL_ENCHANT_ODDS, _level);
		if ((odds == null) || (max <= 0) || (Rnd.get(100.0) >= scale(odds[1])))
		{
			return 0;
		}

		final int safe = Math.min(max, getSafeEnchant(item));
		if ((safe > 1) && (Rnd.get(100) < BELOW_SAFE_CHANCE))
		{
			return Rnd.get(1, safe - 1);
		}

		int enchant = safe;
		final double stepChance = scale(odds[2]);
		while ((enchant < max) && (Rnd.get(100.0) < stepChance))
		{
			enchant++;
		}

		return enchant;
	}

	/**
	 * @param chance a chance (in %)
	 * @return {@code chance} for a fake player of this wealth: as if it had wealth tries at it (0.5 = about half the chance, 2 = two tries)
	 */
	private double scale(double chance)
	{
		if (chance <= 0)
		{
			return 0;
		}

		return chance >= 100 ? 100 : 100 * (1 - Math.pow(1 - (chance / 100), _wealth));
	}

	/**
	 * @param item an item
	 * @return the level up to which normal enchant scrolls never fail on it (data/EnchantItemGroups.xml), +3 (+4 for a full body armor) when the server has no rates for it
	 */
	private static int getSafeEnchant(ItemTemplate item)
	{
		final EnchantItemGroupsData data = EnchantItemGroupsData.getInstance();
		final EnchantItemGroup group = data.getScrollGroup(SCROLL_GROUP) != null ? data.getItemGroup(item, SCROLL_GROUP) : null;
		int safe = 0;
		if (group != null)
		{
			while ((safe < ELITE_MAX_ENCHANT) && (group.getChance(safe) >= 100))
			{
				safe++;
			}
		}

		return safe > 0 ? safe : item.getBodyPart() == BodyPart.FULL_ARMOR ? 4 : 3;
	}
}
