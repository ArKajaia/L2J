package org.l2jmobius.gameserver.managers;

import org.l2jmobius.commons.util.Rnd;
import org.l2jmobius.gameserver.config.custom.LuckyLootConfig;
import org.l2jmobius.gameserver.model.actor.Attackable;
import org.l2jmobius.gameserver.model.actor.Creature;
import org.l2jmobius.gameserver.model.actor.Player;
import org.l2jmobius.gameserver.model.item.holders.ItemHolder;
import org.l2jmobius.gameserver.network.serverpackets.ExShowScreenMessage;
import org.l2jmobius.gameserver.util.Broadcast;

/**
 * Lucky loot: three kill rewards that share one per-player Luck counter (see {@link LuckyLootConfig}).
 * <ul>
 * <li><b>Lucky Streak</b> - every kill has a chance (lower the higher the Luck) to add a Luck stack ({@link Player#getLuckStacks()}), each raising the kill drop chance (read by {@code NpcTemplate} through {@link #getDropMultiplier(Creature)}). Dying or logging out resets it. The stacks are shown in front of the title by
 * {@link #decorateTitle(Player, String)}, which only the title-carrying packets call, so the stored/saved title never changes.</li>
 * <li><b>Jackpot</b> - {@link #rollJackpot(Attackable, Player)}, called from {@code Attackable#doItemDrop(Creature)}, which re-rolls the drop table on a hit and then calls {@link #onJackpotPaid(Attackable, Player)} to reset the Luck. The chance grows with the
 * Luck, from Luck 1 upwards.</li>
 * <li><b>Sealed Cache</b> - a Common/Rare/Epic extractable box dropped on top of the normal loot.</li>
 * </ul>
 * Called from {@code Attackable#doDie()} alongside {@link HotzoneCoinDropManager}, guarded the same way (only kills that would actually reward exp/sp count).
 */
public class LuckyLootManager
{
	/** Messages are sent every this many stacks. */
	private static final int MILESTONE_STEP = 5;
	/** Level at which {@link LuckyLootConfig#SEALED_CACHE_HIGH_LEVEL_BONUS} reaches its full effect (Rare/Epic weights doubled). */
	private static final int CACHE_BONUS_MAX_LEVEL = 85;
	/** Set on a victim that paid out a jackpot, so its own post-kill hook doesn't hand a fresh stack straight back. */
	private static final String JACKPOT_VARIABLE = "LUCKY_LOOT_JACKPOT";

	protected LuckyLootManager()
	{
	}

	/**
	 * @param victim the attackable that just died
	 * @param killer the player credited with the kill
	 */
	public void onAttackableKilled(Attackable victim, Player killer)
	{
		if ((victim == null) || (killer == null) || victim.isRaid() || victim.isRaidMinion())
		{
			return;
		}

		if (victim.getVariables().getBoolean(JACKPOT_VARIABLE, false))
		{
			victim.getVariables().remove(JACKPOT_VARIABLE);
		}
		else
		{
			addLuckStack(victim, killer);
		}
		rollSealedCache(victim, killer);
	}

	/**
	 * Resets the kill streak.
	 * @param player the player who just died
	 */
	public void onPlayerDied(Player player)
	{
		if ((player == null) || (player.getLuckStacks() == 0))
		{
			return;
		}

		player.setLuckStacks(0);
		if (LuckyLootConfig.LUCK_ENABLED)
		{
			player.sendMessage("Your luck has run out.");
		}
		refreshTitle(player);
	}

	/**
	 * @param killer the drop owner passed to {@code NpcTemplate.calculateDrops()}
	 * @return the kill drop chance multiplier from the owner's Luck stacks, or 1.0 if the owner isn't a player
	 */
	public double getDropMultiplier(Creature killer)
	{
		if (!LuckyLootConfig.LUCK_ENABLED || (killer == null))
		{
			return 1.0;
		}

		final Player player = killer.asPlayer();
		if (player == null)
		{
			return 1.0;
		}

		return 1.0 + ((player.getLuckStacks() * LuckyLootConfig.LUCK_DROP_BONUS_PER_STACK) / 100.0);
	}

	/**
	 * Rolls the jackpot chance for this kill, announcing it on a hit. The caller performs the extra drop rolls, then calls {@link #onJackpotPaid(Attackable, Player)}.
	 * @param victim the attackable whose drops are being handed out
	 * @param player the player who owns the drops
	 * @return {@code true} if this kill is a jackpot
	 */
	public boolean rollJackpot(Attackable victim, Player player)
	{
		if (!LuckyLootConfig.JACKPOT_ENABLED || (victim == null) || (player == null) || victim.isRaid() || victim.isRaidMinion() || (victim.getLevel() < LuckyLootConfig.JACKPOT_MIN_LEVEL))
		{
			return false;
		}

		if ((Rnd.nextDouble() * 100) >= getJackpotChance(player))
		{
			return false;
		}

		player.sendPacket(new ExShowScreenMessage("JACKPOT! " + victim.getName() + " bursts with loot!", 5000));
		if (LuckyLootConfig.JACKPOT_ANNOUNCE)
		{
			Broadcast.toAllOnlinePlayers(player.getName() + " hit a JACKPOT on " + victim.getName() + "!");
		}
		return true;
	}

	/**
	 * Called once the jackpot's extra drops have been handed out (so they still benefit from the Luck drop bonus): spends the player's Luck.
	 * @param victim the attackable that paid out the jackpot
	 * @param player the player who owns the drops
	 */
	public void onJackpotPaid(Attackable victim, Player player)
	{
		if (!LuckyLootConfig.JACKPOT_RESETS_LUCK || (victim == null) || (player == null))
		{
			return;
		}

		victim.getVariables().set(JACKPOT_VARIABLE, true);
		if (player.getLuckStacks() > 0)
		{
			player.setLuckStacks(0);
			player.sendMessage("The jackpot used up all your luck.");
			refreshTitle(player);
		}
	}

	/**
	 * @param player the player whose Luck is checked
	 * @return the jackpot chance in percent: 0 at Luck 0, then linear from {@link LuckyLootConfig#JACKPOT_CHANCE_AT_MIN_LUCK} at Luck 1 to {@link LuckyLootConfig#JACKPOT_CHANCE_AT_MAX_LUCK} at {@link LuckyLootConfig#LUCK_MAX_STACKS}
	 */
	private double getJackpotChance(Player player)
	{
		final int stacks = LuckyLootConfig.LUCK_ENABLED ? player.getLuckStacks() : 0;
		if (stacks < 1)
		{
			return 0;
		}

		final int maxStacks = LuckyLootConfig.LUCK_MAX_STACKS;
		final double progress = maxStacks <= 1 ? 1.0 : Math.min(1.0, (stacks - 1) / (double) (maxStacks - 1));
		return LuckyLootConfig.JACKPOT_CHANCE_AT_MIN_LUCK + ((LuckyLootConfig.JACKPOT_CHANCE_AT_MAX_LUCK - LuckyLootConfig.JACKPOT_CHANCE_AT_MIN_LUCK) * progress);
	}

	/**
	 * @param player the player whose title is being sent to a client
	 * @param baseTitle the title that would otherwise be sent
	 * @return {@code baseTitle} with the Luck prefix in front of it, or {@code baseTitle} unchanged if the player has no stacks
	 */
	public String decorateTitle(Player player, String baseTitle)
	{
		if (!LuckyLootConfig.LUCK_ENABLED || !LuckyLootConfig.LUCK_TITLE_ENABLED || (player == null) || (player.getLuckStacks() <= 0))
		{
			return baseTitle;
		}

		final String prefix = String.format(LuckyLootConfig.LUCK_TITLE_FORMAT, player.getLuckStacks());
		return ((baseTitle == null) || baseTitle.isEmpty()) ? prefix : (prefix + " " + baseTitle);
	}

	private void addLuckStack(Attackable victim, Player killer)
	{
		if (!LuckyLootConfig.LUCK_ENABLED || (LuckyLootConfig.LUCK_MAX_STACKS <= 0))
		{
			return;
		}

		if ((LuckyLootConfig.LUCK_MAX_LEVEL_DIFFERENCE >= 0) && ((killer.getLevel() - victim.getLevel()) > LuckyLootConfig.LUCK_MAX_LEVEL_DIFFERENCE))
		{
			return;
		}

		final int stacks = killer.getLuckStacks();
		if (stacks >= LuckyLootConfig.LUCK_MAX_STACKS)
		{
			return;
		}

		if ((Rnd.nextDouble() * 100) >= getLuckGainChance(stacks))
		{
			return;
		}

		final int newStacks = stacks + 1;
		killer.setLuckStacks(newStacks);
		refreshTitle(killer);

		if (LuckyLootConfig.LUCK_MILESTONE_MESSAGE)
		{
			if (newStacks == LuckyLootConfig.LUCK_MAX_STACKS)
			{
				killer.sendMessage("Your luck is at its peak! (" + newStacks + " stacks, +" + formatPercent(newStacks * LuckyLootConfig.LUCK_DROP_BONUS_PER_STACK) + "% drop chance)");
			}
			else if ((newStacks % MILESTONE_STEP) == 0)
			{
				killer.sendMessage("Your luck is growing! (" + newStacks + " stacks, +" + formatPercent(newStacks * LuckyLootConfig.LUCK_DROP_BONUS_PER_STACK) + "% drop chance)");
			}
		}
	}

	/**
	 * @param stacks the player's current stacks
	 * @return the chance in percent to gain the next stack, sliding linearly from {@link LuckyLootConfig#LUCK_GAIN_CHANCE_AT_ZERO} at 0 stacks to {@link LuckyLootConfig#LUCK_GAIN_CHANCE_AT_MAX} one stack below the maximum
	 */
	private double getLuckGainChance(int stacks)
	{
		final int lastStep = LuckyLootConfig.LUCK_MAX_STACKS - 1;
		final double progress = lastStep <= 0 ? 0 : Math.min(1.0, stacks / (double) lastStep);
		return LuckyLootConfig.LUCK_GAIN_CHANCE_AT_ZERO + ((LuckyLootConfig.LUCK_GAIN_CHANCE_AT_MAX - LuckyLootConfig.LUCK_GAIN_CHANCE_AT_ZERO) * progress);
	}

	private void rollSealedCache(Attackable victim, Player killer)
	{
		if (!LuckyLootConfig.SEALED_CACHE_ENABLED || (victim.getLevel() < LuckyLootConfig.SEALED_CACHE_MIN_LEVEL))
		{
			return;
		}

		if ((LuckyLootConfig.SEALED_CACHE_MAX_LEVEL_DIFFERENCE >= 0) && ((killer.getLevel() - victim.getLevel()) > LuckyLootConfig.SEALED_CACHE_MAX_LEVEL_DIFFERENCE))
		{
			return;
		}

		final double chance = LuckyLootConfig.SEALED_CACHE_CHANCE * getLuckChanceMultiplier(killer, LuckyLootConfig.SEALED_CACHE_CHANCE_BONUS_PER_STACK);
		if ((Rnd.nextDouble() * 100) >= chance)
		{
			return;
		}

		final int itemId = pickCacheTier(victim.getLevel());
		if (itemId > 0)
		{
			victim.dropOrAutoLoot(killer, new ItemHolder(itemId, 1));
		}
	}

	/**
	 * @param level the victim's level
	 * @return the item id of the cache tier picked by weight, or 0 if every weight is 0
	 */
	private int pickCacheTier(int level)
	{
		final double highTierBonus = LuckyLootConfig.SEALED_CACHE_HIGH_LEVEL_BONUS ? 1.0 + Math.min(1.0, Math.max(0, level) / (double) CACHE_BONUS_MAX_LEVEL) : 1.0;
		final double common = LuckyLootConfig.SEALED_CACHE_COMMON_WEIGHT;
		final double rare = LuckyLootConfig.SEALED_CACHE_RARE_WEIGHT * highTierBonus;
		final double epic = LuckyLootConfig.SEALED_CACHE_EPIC_WEIGHT * highTierBonus;
		final double total = common + rare + epic;
		if (total <= 0)
		{
			return 0;
		}

		final double roll = Rnd.nextDouble() * total;
		if (roll < epic)
		{
			return LuckyLootConfig.SEALED_CACHE_EPIC_ITEM_ID;
		}
		if (roll < (epic + rare))
		{
			return LuckyLootConfig.SEALED_CACHE_RARE_ITEM_ID;
		}
		return LuckyLootConfig.SEALED_CACHE_COMMON_ITEM_ID;
	}

	/**
	 * @param player the player whose stacks raise the chance
	 * @param bonusPerStack relative bonus in percent per stack
	 * @return the multiplier to apply to a base chance
	 */
	private double getLuckChanceMultiplier(Player player, double bonusPerStack)
	{
		if (!LuckyLootConfig.LUCK_ENABLED)
		{
			return 1.0;
		}
		return 1.0 + ((player.getLuckStacks() * bonusPerStack) / 100.0);
	}

	private void refreshTitle(Player player)
	{
		if (LuckyLootConfig.LUCK_TITLE_ENABLED && player.isOnline())
		{
			player.broadcastTitleInfo();
		}
	}

	private static String formatPercent(double value)
	{
		return (value == Math.rint(value)) ? String.valueOf((long) value) : String.format("%.1f", value);
	}

	public static LuckyLootManager getInstance()
	{
		return SingletonHolder.INSTANCE;
	}

	private static class SingletonHolder
	{
		protected static final LuckyLootManager INSTANCE = new LuckyLootManager();
	}
}
