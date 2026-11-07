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

/**
 * A blessing the Survival Arena challenger picks after each milestone wave (see {@link org.l2jmobius.gameserver.managers.ArenaSurvivalManager}). Boons last until the run ends; the stackable ones add up when picked again.
 */
public enum ArenaBoon
{
	BLOODLUST("Bloodlust", "+15% damage to the challenger (summons included).", true),
	IRON_SKIN("Iron Skin", "The challenger's P. Atk. and M. Atk. -12%.", true),
	SECOND_WIND("Second Wind", "Full HP, MP and CP every time you clear a wave.", false),
	LEECH("Leech", "Heal 5% of the damage you deal to the challenger.", true),
	EXECUTIONER("Executioner", "+40% damage while the challenger is below 30% HP.", true),
	PATIENCE("Patience", "+30 seconds before the challenger enrages.", true),
	GREED("Greed", "+20% pay for every wave from now on.", true),
	MOMENTUM("Momentum", "A slow wave no longer breaks your swift streak (an enrage still does).", false);

	/** Bloodlust: extra damage to the challenger per stack. */
	public static final double BLOODLUST_DAMAGE = 0.15;
	/** Iron Skin: the challenger's attack is multiplied by this once per stack. */
	public static final double IRON_SKIN_ATTACK = 0.88;
	/** Leech: share of the damage dealt that heals the player, per stack. */
	public static final double LEECH_SHARE = 0.05;
	/** Executioner: extra damage per stack while the challenger is low. */
	public static final double EXECUTIONER_DAMAGE = 0.40;
	/** Executioner: the challenger counts as low below this share of its HP. */
	public static final double EXECUTIONER_THRESHOLD = 0.30;
	/** Patience: enrage seconds added per stack. */
	public static final int PATIENCE_SECONDS = 30;
	/** Greed: pay percent added per stack. */
	public static final int GREED_PERCENT = 20;

	private final String _name;
	private final String _description;
	private final boolean _stackable;

	ArenaBoon(String name, String description, boolean stackable)
	{
		_name = name;
		_description = description;
		_stackable = stackable;
	}

	public String getName()
	{
		return _name;
	}

	public String getDescription()
	{
		return _description;
	}

	/**
	 * @return {@code true} if picking this boon again adds another stack; a non-stackable boon is no longer offered once owned
	 */
	public boolean isStackable()
	{
		return _stackable;
	}
}
