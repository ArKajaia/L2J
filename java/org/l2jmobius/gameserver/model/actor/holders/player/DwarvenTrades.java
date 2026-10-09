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
import org.l2jmobius.gameserver.model.actor.Summon;
import org.l2jmobius.gameserver.model.actor.enums.creature.Race;
import org.l2jmobius.gameserver.model.actor.enums.player.PlayerClass;
import org.l2jmobius.gameserver.model.actor.instance.Monster;
import org.l2jmobius.gameserver.model.effects.EffectType;
import org.l2jmobius.gameserver.model.item.instance.Item;
import org.l2jmobius.gameserver.model.itemcontainer.Inventory;
import org.l2jmobius.gameserver.model.skill.AbnormalType;
import org.l2jmobius.gameserver.model.skill.Skill;

/**
 * The trades only a dwarf masters. The passive tree lets any character take Spoil, Sweeper Festival, Crystallize and Dwarven Craft, so the dwarven classes get what the tree doesn't give:
 * <ul>
 * <li>Spoilers (Scavenger, Bounty Hunter, Fortune Seeker): their Spoil always lands, and what they sweep has a better chance and bigger amounts, more with each class transfer.</li>
 * <li>Crafters (Artisan, Warsmith, Maestro): a better success rate, more masterworks and a chance to make twice as many stackable items, for themselves and in their workshops. The tree's Create Item stops at level 5 by default.</li>
 * <li>Every dwarven class gets more crystals from Crystallize, crafters more still.</li>
 * </ul>
 * And they fight like dwarves, with blunts, stuns and golems:
 * <ul>
 * <li>Skullcrusher (both lines): their stun skills land more often.</li>
 * <li>Plunderer's Mark (spoilers): more damage to the monsters they spoiled. Spoils of War: every corpse they sweep restores some of their HP and MP.</li>
 * <li>Golem Engineering (crafters): their golems and cannons are stronger. Forged Gear: enchant levels above the safe enchant on their weapon and armour give extra P.Atk and P.Def.</li>
 * </ul>
 * Everything follows the active class, so a dwarven subclass counts and a dwarf on a non-dwarven subclass doesn't.
 * @author Mobius
 */
public class DwarvenTrades
{
	/** The equipment slots Forged Gear counts for P.Def. */
	private static final int[] ARMOR_SLOTS =
	{
		Inventory.PAPERDOLL_HEAD,
		Inventory.PAPERDOLL_CHEST,
		Inventory.PAPERDOLL_LEGS,
		Inventory.PAPERDOLL_GLOVES,
		Inventory.PAPERDOLL_FEET,
		Inventory.PAPERDOLL_LHAND
	};
	
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
	
	/**
	 * @param player the player
	 * @return 1-3 for any 1st, 2nd or 3rd class dwarf, 0 otherwise (the Dwarven Fighter too)
	 */
	public static int getDwarvenTier(Player player)
	{
		return isDwarvenClass(player) ? player.getPlayerClass().level() : 0;
	}
	
	/**
	 * Skullcrusher: a dwarf's stun skills land more often. The skill's own highest chance still applies.
	 * @param attacker the character casting the skill
	 * @param skill the skill
	 * @return how much the chance of the skill's effects is multiplied
	 */
	public static double getEffectChanceMultiplier(Creature attacker, Skill skill)
	{
		if (!attacker.isPlayer() || ((skill.getAbnormalType() != AbnormalType.STUN) && !skill.hasEffectType(EffectType.STUN)))
		{
			return 1;
		}
		return tier(DwarvenTradesConfig.SKULLCRUSHER_CHANCE_MULTIPLIER, getDwarvenTier(attacker.asPlayer()), 1);
	}
	
	/**
	 * Plunderer's Mark: a dwarven spoiler hits the monsters they spoiled harder.
	 * @param attacker the attacker
	 * @param target the target
	 * @return how much the attacker's physical damage on the target is multiplied
	 */
	public static double getPlunderMultiplier(Creature attacker, Creature target)
	{
		if (!attacker.isPlayer() || !target.isMonster() || (target.asMonster().getSpoilerObjectId() != attacker.getObjectId()))
		{
			return 1;
		}
		return 1 + (tier(DwarvenTradesConfig.PLUNDER_DAMAGE_BONUS, getSpoilerTier(attacker.asPlayer()), 0) / 100);
	}
	
	/**
	 * Spoils of War: a dwarven spoiler gets back some HP and MP for every corpse they sweep.
	 * @param player the player who swept a corpse
	 */
	public static void onSweep(Player player)
	{
		final double percent = tier(DwarvenTradesConfig.SPOILS_OF_WAR_RESTORE, getSpoilerTier(player), 0);
		if ((percent <= 0) || player.isDead())
		{
			return;
		}
		
		player.setCurrentHp(Math.min(player.getMaxHp(), player.getCurrentHp() + ((player.getMaxHp() * percent) / 100)));
		player.setCurrentMp(Math.min(player.getMaxMp(), player.getCurrentMp() + ((player.getMaxMp() * percent) / 100)));
	}
	
	/**
	 * Golem Engineering: a dwarven crafter's golems and cannons are stronger.
	 * @param summon the summon
	 * @return how much the summon's P.Atk, M.Atk, P.Def, M.Def and max HP are multiplied
	 */
	public static double getGolemMultiplier(Summon summon)
	{
		if (!summon.isServitor())
		{
			return 1;
		}
		return 1 + (tier(DwarvenTradesConfig.GOLEM_BONUS, getCrafterTier(summon.getOwner()), 0) / 100);
	}
	
	/**
	 * Forged Gear: each enchant level above the safe enchant on a dwarven crafter's weapon adds P.Atk.
	 * @param player the player
	 * @return how much P.Atk is multiplied
	 */
	public static double getForgedWeaponMultiplier(Player player)
	{
		final int tier = getCrafterTier(player);
		if ((tier == 0) || (player.getInventory() == null))
		{
			return 1;
		}
		
		final int levels = enchantAboveSafe(player.getInventory().getPaperdollItem(Inventory.PAPERDOLL_RHAND));
		return 1 + (Math.min(DwarvenTradesConfig.FORGED_MAX_BONUS, levels * tier(DwarvenTradesConfig.FORGED_WEAPON_BONUS, tier, 0)) / 100);
	}
	
	/**
	 * Forged Gear: each enchant level above the safe enchant on each piece of a dwarven crafter's armour (and shield) adds P.Def.
	 * @param player the player
	 * @return how much P.Def is multiplied
	 */
	public static double getForgedArmorMultiplier(Player player)
	{
		final int tier = getCrafterTier(player);
		if ((tier == 0) || (player.getInventory() == null))
		{
			return 1;
		}
		
		final Inventory inventory = player.getInventory();
		int levels = 0;
		int lastObjectId = 0;
		for (int slot : ARMOR_SLOTS)
		{
			final Item item = inventory.getPaperdollItem(slot);
			if ((item != null) && item.isArmor() && (item.getObjectId() != lastObjectId)) // A full body armour can show in two slots.
			{
				levels += enchantAboveSafe(item);
				lastObjectId = item.getObjectId();
			}
		}
		return 1 + (Math.min(DwarvenTradesConfig.FORGED_MAX_BONUS, levels * tier(DwarvenTradesConfig.FORGED_ARMOR_BONUS, tier, 0)) / 100);
	}
	
	private static int enchantAboveSafe(Item item)
	{
		return item == null ? 0 : Math.max(0, item.getEnchantLevel() - DwarvenTradesConfig.FORGED_SAFE_ENCHANT);
	}
}
