package org.l2jmobius.gameserver.managers;

import org.l2jmobius.commons.util.Rnd;
import org.l2jmobius.gameserver.config.RatesConfig;
import org.l2jmobius.gameserver.config.custom.HotzoneCoinDropConfig;
import org.l2jmobius.gameserver.model.actor.Attackable;
import org.l2jmobius.gameserver.model.actor.Player;
import org.l2jmobius.gameserver.model.hotzone.HotzoneModifier;
import org.l2jmobius.gameserver.model.item.enums.ItemProcessType;

/**
 * Pays out {@link RatesConfig#ARENA_CURRENCY_ITEM_ID} - the same currency the Survival Arena awards - for player kills inside a hotzone (see {@link org.l2jmobius.gameserver.model.zone.type.HotZone}), scaled by the victim's level: {@link HotzoneCoinDropConfig#MIN_AMOUNT} at level 1, rising
 * linearly to {@link HotzoneCoinDropConfig#MAX_AMOUNT} at {@link HotzoneCoinDropConfig#LEVEL_FOR_MAX_AMOUNT} and beyond. Called from {@code Attackable#doDie()} alongside {@link HotZoneMinibossManager}, guarded the same way (only kills that would actually reward exp/sp count).
 */
public class HotzoneCoinDropManager
{
	protected HotzoneCoinDropManager()
	{
	}

	/**
	 * @param victim the attackable that just died
	 * @param killer the player credited with the kill
	 */
	public void onAttackableKilled(Attackable victim, Player killer)
	{
		if (!HotzoneCoinDropConfig.ENABLED || (victim == null) || (killer == null))
		{
			return;
		}

		// A class transfer challenge's Omen is a hotzone modifier too, but trials pay their own reward instead of farmable coins.
		if (ClassTransferChallengeManager.isChallengeInstance(victim.getInstanceId()))
		{
			return;
		}

		// Only a hotzone the rotation currently has active pays out - every hotzone stays flagged HOTZONE, but only active ones have a modifier rolled.
		final HotzoneModifier modifier = HotzoneModifierManager.getInstance().getModifierFor(victim);
		if (modifier == null)
		{
			return;
		}

		// GOLD_RUSH multiplies every payout; MINIBOSS_FRENZY makes a miniboss always pay, and pay more - as do a BOUNTY_HUNT bounty and a CONTESTED_GROUND fake player.
		double multiplier = modifier.getCoinMult();
		final boolean frenzyMiniboss = (modifier.getMinibossCoinMult() > 1.0) && victim.isMonster() && victim.asMonster().isHotzoneMiniboss();
		final boolean bounty = victim.isMonster() && victim.asMonster().isHotzoneBounty();
		final boolean contestedFake = (modifier.getFakePlayerCoinMult() > 1.0) && victim.isPvpFakePlayer();
		if (frenzyMiniboss)
		{
			multiplier *= modifier.getMinibossCoinMult();
		}
		else if (bounty)
		{
			multiplier *= HotzoneModifierManager.BOUNTY_COIN_MULT;
		}
		else if (contestedFake)
		{
			multiplier *= modifier.getFakePlayerCoinMult();
		}
		else if ((Rnd.nextDouble() * 100) >= HotzoneCoinDropConfig.DROP_CHANCE)
		{
			return;
		}

		final int amount = (int) Math.round(calculateAmount(victim.getLevel()) * multiplier);
		if (amount > 0)
		{
			killer.addItem(ItemProcessType.REWARD, RatesConfig.ARENA_CURRENCY_ITEM_ID, amount, victim, true);
		}
	}

	private int calculateAmount(int victimLevel)
	{
		final int min = HotzoneCoinDropConfig.MIN_AMOUNT;
		final int max = HotzoneCoinDropConfig.MAX_AMOUNT;
		final int levelForMax = Math.max(1, HotzoneCoinDropConfig.LEVEL_FOR_MAX_AMOUNT);

		final double progress = Math.min(1.0, victimLevel / (double) levelForMax);
		final int amount = min + (int) Math.round((max - min) * progress);

		return Math.max(Math.min(amount, max), min);
	}

	public static HotzoneCoinDropManager getInstance()
	{
		return SingletonHolder.INSTANCE;
	}

	private static class SingletonHolder
	{
		protected static final HotzoneCoinDropManager INSTANCE = new HotzoneCoinDropManager();
	}
}
