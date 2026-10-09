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
package org.l2jmobius.gameserver.model.actor.holders.player;

import org.l2jmobius.gameserver.config.custom.DwarvenTradesConfig;
import org.l2jmobius.gameserver.model.World;
import org.l2jmobius.gameserver.model.actor.Creature;
import org.l2jmobius.gameserver.model.actor.Player;
import org.l2jmobius.gameserver.model.actor.enums.creature.Race;
import org.l2jmobius.gameserver.model.actor.enums.player.PlayerClass;
import org.l2jmobius.gameserver.model.actor.instance.Monster;

/**
 * The trades only a dwarf masters. The passive tree lets any character take Spoil, Sweeper Festival, Crystallize and Dwarven Craft, so the dwarven classes get what the tree doesn't give:
 * <ul>
 * <li>Spoilers (Scavenger, Bounty Hunter, Fortune Seeker): their Spoil always lands, and what they sweep has a better chance and bigger amounts, more with each class transfer.</li>
 * <li>Crafters (Artisan, Warsmith, Maestro): a better success rate, more masterworks and a chance to make twice as many stackable items, for themselves and in their workshops. The tree's Create Item stops at level 5 by default.</li>
 * <li>Every dwarven class gets more crystals from Crystallize, crafters more still.</li>
 * </ul>
 * Everything follows the active class, so a dwarven subclass counts and a dwarf on a non-dwarven subclass doesn't.
 * @author Mobius
 */
public class DwarvenTrades
{
	private DwarvenTrades()
	{
	}
	
	/**
	 * @param player the player
	 * @return {@code true} if the active class is a dwarven class
	 */
	public static boolean isDwarvenClass(Player player)
	{
		return DwarvenTradesConfig.DWARVEN_TRADES_ENABLED && (player != null) && (player.getPlayerClass().getRace() == Race.DWARF);
	}
	
	/**
	 * @param player the player
	 * @return 1-3 for a Scavenger, Bounty Hunter or Fortune Seeker, 0 otherwise
	 */
	public static int getSpoilerTier(Player player)
	{
		return isDwarvenClass(player) && player.getPlayerClass().equalsOrChildOf(PlayerClass.SCAVENGER) ? player.getPlayerClass().level() : 0;
	}
	
	/**
	 * @param player the player
	 * @return 1-3 for an Artisan, Warsmith or Maestro, 0 otherwise
	 */
	public static int getCrafterTier(Player player)
	{
		return isDwarvenClass(player) && player.getPlayerClass().equalsOrChildOf(PlayerClass.ARTISAN) ? player.getPlayerClass().level() : 0;
	}
	
	private static double tier(double[] values, int tier, double none)
	{
		return tier > 0 ? values[Math.min(tier, values.length) - 1] : none;
	}
	
	/**
	 * @param effector the character casting a Spoil skill
	 * @return {@code true} if the Spoil can't miss
	 */
	public static boolean isSpoilCertain(Creature effector)
	{
		return DwarvenTradesConfig.SPOIL_ALWAYS_LANDS && effector.isPlayer() && (getSpoilerTier(effector.asPlayer()) > 0);
	}
	
	/**
	 * @param monster a spoiled monster
	 * @return the player who spoiled it, if online
	 */
	private static Player getSpoiler(Monster monster)
	{
		return monster.isSpoiled() ? World.getInstance().getPlayer(monster.getSpoilerObjectId()) : null;
	}
	
	/**
	 * @param monster a spoiled monster
	 * @return how much the chance of each sweep item is multiplied, from the class of the player who spoiled it
	 */
	public static double getSpoilChanceMultiplier(Monster monster)
	{
		return tier(DwarvenTradesConfig.SPOIL_CHANCE_MULTIPLIER, getSpoilerTier(getSpoiler(monster)), 1);
	}
	
	/**
	 * @param monster a spoiled monster
	 * @return how much the amount of each sweep item is multiplied, from the class of the player who spoiled it
	 */
	public static double getSpoilAmountMultiplier(Monster monster)
	{
		return tier(DwarvenTradesConfig.SPOIL_AMOUNT_MULTIPLIER, getSpoilerTier(getSpoiler(monster)), 1);
	}
	
	/**
	 * @param crafter the player making the item
	 * @param successRate the recipe's success rate
	 * @return the success rate with the crafter's bonus, at most 100
	 */
	public static double getCraftSuccessRate(Player crafter, int successRate)
	{
		return successRate >= 100 ? successRate : Math.min(100, successRate + tier(DwarvenTradesConfig.CRAFT_SUCCESS_BONUS, getCrafterTier(crafter), 0));
	}
	
	/**
	 * @param crafter the player making the item
	 * @return how much the masterwork chance is multiplied
	 */
	public static double getMasterworkMultiplier(Player crafter)
	{
		return tier(DwarvenTradesConfig.CRAFT_MASTERWORK_MULTIPLIER, getCrafterTier(crafter), 1);
	}
	
	/**
	 * @param crafter the player making the item
	 * @return the chance (%) that a craft of a stackable item makes twice as many
	 */
	public static double getDoubleCraftChance(Player crafter)
	{
		return tier(DwarvenTradesConfig.CRAFT_DOUBLE_CHANCE, getCrafterTier(crafter), 0);
	}
	
	/**
	 * @param player the player crystallizing an item
	 * @return the extra crystals in %, 0 for a non-dwarven class
	 */
	public static double getCrystallizeBonus(Player player)
	{
		return isDwarvenClass(player) ? DwarvenTradesConfig.CRYSTALLIZE_BONUS + tier(DwarvenTradesConfig.CRAFTER_CRYSTALLIZE_BONUS, getCrafterTier(player), 0) : 0;
	}
}
