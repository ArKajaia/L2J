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

import java.util.function.ToDoubleFunction;

import org.l2jmobius.gameserver.config.custom.DwarvenTradesConfig;
import org.l2jmobius.gameserver.data.xml.ItemData;
import org.l2jmobius.gameserver.model.World;
import org.l2jmobius.gameserver.model.WorldObject;
import org.l2jmobius.gameserver.model.actor.Creature;
import org.l2jmobius.gameserver.model.actor.Npc;
import org.l2jmobius.gameserver.model.actor.Player;
import org.l2jmobius.gameserver.model.actor.Summon;
import org.l2jmobius.gameserver.model.actor.enums.creature.Race;
import org.l2jmobius.gameserver.model.actor.enums.player.PlayerClass;
import org.l2jmobius.gameserver.model.actor.holders.npc.FakePlayerHolder;
import org.l2jmobius.gameserver.model.actor.instance.Monster;
import org.l2jmobius.gameserver.model.effects.EffectType;
import org.l2jmobius.gameserver.model.item.Armor;
import org.l2jmobius.gameserver.model.item.instance.Item;
import org.l2jmobius.gameserver.model.itemcontainer.Inventory;
import org.l2jmobius.gameserver.model.skill.AbnormalType;
import org.l2jmobius.gameserver.model.skill.Skill;
import org.l2jmobius.gameserver.model.stats.Stat;
import org.l2jmobius.gameserver.model.stats.functions.AbstractFunction;
import org.l2jmobius.gameserver.model.stats.functions.FuncMul;

/**
 * The trades only a dwarf masters. The passive tree lets any character take Spoil, Sweeper Festival, Crystallize and Dwarven Craft, so the dwarven classes get what the tree doesn't give:
 * <ul>
 * <li>Spoilers (Scavenger, Bounty Hunter, Fortune Seeker): their Spoil always lands, and what they sweep has a better chance and bigger amounts, more with each class transfer.</li>
 * <li>Crafters (Artisan, Warsmith, Maestro): a better success rate, more masterworks and a chance to make twice as many stackable items, for themselves and in their workshops. Everyone else crafts only through the tree's Dwarven Craft, whose Create Item stops at level 5 by default.</li>
 * <li>Every dwarven class gets more crystals from Crystallize, crafters more still.</li>
 * </ul>
 * And they fight like dwarves, with blunts, stuns and golems:
 * <ul>
 * <li>Skullcrusher (both lines): their stun skills land more often.</li>
 * <li>Plunderer's Mark (spoilers): more damage to the monsters they spoiled. Spoils of War: every corpse they sweep restores some of their HP and MP.</li>
 * <li>Golem Engineering (crafters): their golems and cannons are stronger. Forged Gear: enchant levels above the safe enchant on their weapon and armour give extra P.Atk and P.Def.</li>
 * </ul>
 * Everything follows the active class, so a dwarven subclass counts and a dwarf on a non-dwarven subclass doesn't. A roaming fake player of a dwarven class fights the same way (its stuns, Spoil, Plunderer's Mark, golem and Forged Gear), see {@link #applyToFakePlayer}.
 * @author Mobius
 */
public class DwarvenTrades
{
	/** Owner of the stat functions a fake player or its servitor gets from here. */
	private static final Object FUNC_OWNER = new Object();
	/** Applied on top of the stat like the Player and Summon getter overrides (after the fake player's passive tree, see PassiveTreeManager#applyToFakePlayer). */
	private static final int FAKE_GETTER_ORDER = 0x51;
	/** The stats Golem Engineering raises. */
	private static final Stat[] GOLEM_STATS =
	{
		Stat.POWER_ATTACK,
		Stat.MAGIC_ATTACK,
		Stat.POWER_DEFENCE,
		Stat.MAGIC_DEFENCE,
		Stat.MAX_HP
	};
	
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
	 * @param creature a player or a roaming fake player
	 * @return its active class, {@code null} for anything else
	 */
	private static PlayerClass getPlayerClass(Creature creature)
	{
		if (creature == null)
		{
			return null;
		}
		
		if (creature.isPlayer())
		{
			return creature.asPlayer().getPlayerClass();
		}
		
		return creature.isPvpFakePlayer() ? creature.asNpc().getTemplate().getFakePlayerPvpProfile().getPlayerClass() : null;
	}
	
	/**
	 * @param playerClass a class, can be {@code null}
	 * @return {@code true} if it is a dwarven class
	 */
	private static boolean isDwarvenClass(PlayerClass playerClass)
	{
		return DwarvenTradesConfig.DWARVEN_TRADES_ENABLED && (playerClass != null) && (playerClass.getRace() == Race.DWARF);
	}
	
	/**
	 * @param creature a player or a roaming fake player
	 * @return {@code true} if the active class is a dwarven class
	 */
	public static boolean isDwarvenClass(Creature creature)
	{
		return isDwarvenClass(getPlayerClass(creature));
	}
	
	/**
	 * @param creature a player or a roaming fake player
	 * @return 1-3 for a Scavenger, Bounty Hunter or Fortune Seeker, 0 otherwise
	 */
	public static int getSpoilerTier(Creature creature)
	{
		final PlayerClass playerClass = getPlayerClass(creature);
		return isDwarvenClass(playerClass) && playerClass.equalsOrChildOf(PlayerClass.SCAVENGER) ? playerClass.level() : 0;
	}
	
	/**
	 * @param creature a player or a roaming fake player
	 * @return 1-3 for an Artisan, Warsmith or Maestro, 0 otherwise
	 */
	public static int getCrafterTier(Creature creature)
	{
		final PlayerClass playerClass = getPlayerClass(creature);
		return isDwarvenClass(playerClass) && playerClass.equalsOrChildOf(PlayerClass.ARTISAN) ? playerClass.level() : 0;
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
		return DwarvenTradesConfig.SPOIL_ALWAYS_LANDS && (getSpoilerTier(effector) > 0);
	}
	
	/**
	 * @param monster a spoiled monster
	 * @return the player (or roaming fake player) who spoiled it, if still in the world
	 */
	private static Creature getSpoiler(Monster monster)
	{
		final WorldObject spoiler = monster.isSpoiled() ? World.getInstance().findObject(monster.getSpoilerObjectId()) : null;
		return (spoiler != null) && spoiler.isCreature() ? spoiler.asCreature() : null;
	}
	
	/**
	 * @param monster a spoiled monster
	 * @return how much the chance of each sweep item is multiplied, from the class of the player (or fake player) who spoiled it
	 */
	public static double getSpoilChanceMultiplier(Monster monster)
	{
		return tier(DwarvenTradesConfig.SPOIL_CHANCE_MULTIPLIER, getSpoilerTier(getSpoiler(monster)), 1);
	}
	
	/**
	 * @param monster a spoiled monster
	 * @return how much the amount of each sweep item is multiplied, from the class of the player (or fake player) who spoiled it
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
	 * @param creature a player or a roaming fake player
	 * @return 1-3 for any 1st, 2nd or 3rd class dwarf, 0 otherwise (the Dwarven Fighter too)
	 */
	public static int getDwarvenTier(Creature creature)
	{
		final PlayerClass playerClass = getPlayerClass(creature);
		return isDwarvenClass(playerClass) ? playerClass.level() : 0;
	}
	
	/**
	 * Skullcrusher: a dwarf's stun skills land more often. The skill's own highest chance still applies.
	 * @param attacker the character casting the skill
	 * @param skill the skill
	 * @return how much the chance of the skill's effects is multiplied
	 */
	public static double getEffectChanceMultiplier(Creature attacker, Skill skill)
	{
		if ((skill.getAbnormalType() != AbnormalType.STUN) && !skill.hasEffectType(EffectType.STUN))
		{
			return 1;
		}
		return tier(DwarvenTradesConfig.SKULLCRUSHER_CHANCE_MULTIPLIER, getDwarvenTier(attacker), 1);
	}
	
	/**
	 * Plunderer's Mark: a dwarven spoiler hits the monsters they spoiled harder.
	 * @param attacker the attacker
	 * @param target the target
	 * @return how much the attacker's physical damage on the target is multiplied
	 */
	public static double getPlunderMultiplier(Creature attacker, Creature target)
	{
		if (!target.isMonster() || (target.asMonster().getSpoilerObjectId() != attacker.getObjectId()))
		{
			return 1;
		}
		return 1 + (tier(DwarvenTradesConfig.PLUNDER_DAMAGE_BONUS, getSpoilerTier(attacker), 0) / 100);
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
		return summon.isServitor() ? getServitorMultiplier(summon.getOwner()) : 1;
	}
	
	/**
	 * Golem Engineering for the servitor of {@code owner}.
	 * @param owner a player or a roaming fake player
	 * @return how much the P.Atk, M.Atk, P.Def, M.Def and max HP of its servitor are multiplied
	 */
	private static double getServitorMultiplier(Creature owner)
	{
		return 1 + (tier(DwarvenTradesConfig.GOLEM_BONUS, getCrafterTier(owner), 0) / 100);
	}
	
	/**
	 * Forged Gear: each enchant level above the safe enchant on a dwarven crafter's weapon adds P.Atk.
	 * @param creature a player or a roaming fake player
	 * @return how much P.Atk is multiplied
	 */
	public static double getForgedWeaponMultiplier(Creature creature)
	{
		final int tier = getCrafterTier(creature);
		if (tier == 0)
		{
			return 1;
		}
		
		final int levels;
		if (creature.isPlayer())
		{
			final Inventory inventory = creature.asPlayer().getInventory();
			if (inventory == null)
			{
				return 1;
			}
			levels = enchantAboveSafe(inventory.getPaperdollItem(Inventory.PAPERDOLL_RHAND));
		}
		else
		{
			// The weapon a fake player holds now (none while disarmed).
			final FakePlayerHolder fake = creature.asNpc().getTemplate().getFakePlayerInfo();
			levels = fake.getEquipRHand() > 0 ? enchantAboveSafe(fake.getWeaponEnchantLevel()) : 0;
		}
		return 1 + (Math.min(DwarvenTradesConfig.FORGED_MAX_BONUS, levels * tier(DwarvenTradesConfig.FORGED_WEAPON_BONUS, tier, 0)) / 100);
	}
	
	/**
	 * Forged Gear: each enchant level above the safe enchant on each piece of a dwarven crafter's armour (and shield) adds P.Def.
	 * @param creature a player or a roaming fake player
	 * @return how much P.Def is multiplied
	 */
	public static double getForgedArmorMultiplier(Creature creature)
	{
		final int tier = getCrafterTier(creature);
		if (tier == 0)
		{
			return 1;
		}
		
		int levels = 0;
		if (creature.isPlayer())
		{
			final Inventory inventory = creature.asPlayer().getInventory();
			if (inventory == null)
			{
				return 1;
			}
			
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
		}
		else
		{
			// A fake player's armour pieces (and shield), each with its own enchant. A full body armour has no legs piece.
			final FakePlayerHolder fake = creature.asNpc().getTemplate().getFakePlayerInfo();
			for (int[] piece : new int[][]
			{
				// @formatter:off
				{Inventory.PAPERDOLL_HEAD, fake.getEquipHead()},
				{Inventory.PAPERDOLL_CHEST, fake.getEquipChest()},
				{Inventory.PAPERDOLL_LEGS, fake.getEquipLegs()},
				{Inventory.PAPERDOLL_GLOVES, fake.getEquipGloves()},
				{Inventory.PAPERDOLL_FEET, fake.getEquipFeet()},
				{Inventory.PAPERDOLL_LHAND, ItemData.getInstance().getTemplate(fake.getEquipLHand()) instanceof Armor ? fake.getEquipLHand() : 0}
				// @formatter:on
			})
			{
				if (piece[1] > 0)
				{
					levels += enchantAboveSafe(fake.getEnchantLevel(piece[0]));
				}
			}
		}
		return 1 + (Math.min(DwarvenTradesConfig.FORGED_MAX_BONUS, levels * tier(DwarvenTradesConfig.FORGED_ARMOR_BONUS, tier, 0)) / 100);
	}
	
	private static int enchantAboveSafe(Item item)
	{
		return item == null ? 0 : enchantAboveSafe(item.getEnchantLevel());
	}
	
	private static int enchantAboveSafe(int enchantLevel)
	{
		return Math.max(0, enchantLevel - DwarvenTradesConfig.FORGED_SAFE_ENCHANT);
	}
	
	/**
	 * A roaming fake player of a crafter class gets Forged Gear like a player, whose Player getters apply it: P.Atk and P.Def functions that follow the weapon it holds. Call when it enters the world.
	 * @param fake the fake player
	 */
	public static void applyToFakePlayer(Npc fake)
	{
		fake.removeStatsOwner(FUNC_OWNER);
		if (getCrafterTier(fake) > 0)
		{
			fake.addStatFunc(new FakePlayerFunc(Stat.POWER_ATTACK, DwarvenTrades::getForgedWeaponMultiplier));
			fake.addStatFunc(new FakePlayerFunc(Stat.POWER_DEFENCE, DwarvenTrades::getForgedArmorMultiplier));
		}
	}
	
	/**
	 * Golem Engineering for the servitor of a roaming fake player (an npc, not a {@link Summon}): its P.Atk, M.Atk, P.Def, M.Def and max HP, like a player's golem. Call before its HP is filled up.
	 * @param servitor the servitor
	 * @param owner the fake player it belongs to
	 */
	public static void applyToServitor(Npc servitor, Creature owner)
	{
		final double multiplier = getServitorMultiplier(owner);
		if (multiplier > 1)
		{
			for (Stat stat : GOLEM_STATS)
			{
				servitor.addStatFunc(new FuncMul(stat, FAKE_GETTER_ORDER, FUNC_OWNER, multiplier, null));
			}
		}
	}
	
	/**
	 * A stat of a roaming fake player multiplied by one of the methods above, worked out each time it is read.
	 */
	private static class FakePlayerFunc extends AbstractFunction
	{
		private final ToDoubleFunction<Creature> _multiplier;
		
		FakePlayerFunc(Stat stat, ToDoubleFunction<Creature> multiplier)
		{
			super(stat, FAKE_GETTER_ORDER, FUNC_OWNER, 0, null);
			_multiplier = multiplier;
		}
		
		@Override
		public double calc(Creature effector, Creature effected, Skill skill, double initVal)
		{
			return initVal * _multiplier.applyAsDouble(effector);
		}
	}
}
