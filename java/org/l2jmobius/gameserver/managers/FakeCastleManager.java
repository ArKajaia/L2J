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

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.l2jmobius.commons.threads.ThreadPool;
import org.l2jmobius.commons.util.Rnd;
import org.l2jmobius.gameserver.config.GeneralConfig;
import org.l2jmobius.gameserver.config.custom.FakeClanConfig;
import org.l2jmobius.gameserver.data.sql.ClanTable;
import org.l2jmobius.gameserver.model.clan.Clan;
import org.l2jmobius.gameserver.model.sevensigns.SevenSigns;
import org.l2jmobius.gameserver.model.siege.Castle;
import org.l2jmobius.gameserver.model.siege.Siege;
import org.l2jmobius.gameserver.model.siege.SiegeClan;
import org.l2jmobius.gameserver.model.siege.manor.CropProcure;
import org.l2jmobius.gameserver.model.siege.manor.Seed;
import org.l2jmobius.gameserver.model.siege.manor.SeedProduction;

/**
 * Castles held by the clans of fake players (FakeClans.ini, {@link FakeClanManager}).
 * <ul>
 * <li>A castle without a lord is taken by a clan of FakeClanNames that has no castle yet, right away at the server start and {@link FakeClanConfig#CASTLE_TAKE_DELAY} minutes after it lost its lord otherwise (never during its siege or a territory war).</li>
 * <li>The lord sets the tax rate, shows its crest on the castle npcs, spends the treasury and accepts or refuses the players' clans that register to defend the castle.</li>
 * <li>It runs the manor of the castle: it sells all seeds of the castle and buys all its crops, at prices of its own every period, refilled every {@link FakeClanConfig#MANOR_REFILL_MINUTES} minutes. It pays nothing for it and uses up the crops it bought (see
 * {@link CastleManorManager#changeMode}).</li>
 * <li>Players take the castle in a siege like any other; in it the npc guards of the castle defend it ({@link FakeClanConfig#CASTLE_NPC_GUARDS}).</li>
 * </ul>
 */
public class FakeCastleManager
{
	private static final Logger LOGGER = Logger.getLogger(FakeCastleManager.class.getName());
	
	/** How often the castles are looked after. */
	private static final long CHECK_INTERVAL = 60000;
	/** How long the lord takes to answer a clan that registered to defend its castle, in minutes (random between these). */
	private static final int DEFENDER_DECISION_MIN = 1;
	private static final int DEFENDER_DECISION_MAX = 10;
	
	/** Since when a castle has no lord, by castle id. */
	private final Map<Integer, Long> _withoutLord = new ConcurrentHashMap<>();
	/** The castles whose lord already set the next manor period in this modifiable period. */
	private final Set<Integer> _plannedNextPeriod = ConcurrentHashMap.newKeySet();
	/** Clans waiting for approval to defend a castle (castle id and clan id): when the lord answers, negative when it refused. */
	private final Map<Long, Long> _defenders = new ConcurrentHashMap<>();
	/** When the seeds and crops of the current period were refilled last. */
	private volatile long _lastRefill = System.currentTimeMillis();
	
	protected FakeCastleManager()
	{
		if (!isEnabled())
		{
			final int released = releaseCastles();
			LOGGER.info(getClass().getSimpleName() + ": Disabled" + (released > 0 ? ", " + released + " castles given back to the npcs." : "."));
			return;
		}
		
		check(true);
		ThreadPool.scheduleAtFixedRate(() -> check(false), CHECK_INTERVAL, CHECK_INTERVAL);
		
		int held = 0;
		for (Castle castle : CastleManager.getInstance().getCastles())
		{
			if (isFakeCastle(castle))
			{
				held++;
			}
		}
		LOGGER.info(getClass().getSimpleName() + ": " + held + " castles held by clans of fake players.");
	}
	
	/**
	 * @return {@code true} if the clans of fake players hold castles
	 */
	private static boolean isEnabled()
	{
		return FakeClanConfig.CASTLES_ENABLED && FakeClanManager.getInstance().isEnabled();
	}
	
	/**
	 * @param castle a castle
	 * @return {@code true} if a clan of fake players holds it and runs it (taxes, manor, defenders)
	 */
	public static boolean isFakeCastle(Castle castle)
	{
		if ((castle == null) || (castle.getOwnerId() <= 0) || !isEnabled())
		{
			return false;
		}
		
		return FakeClanManager.getInstance().isFakeClan(ClanTable.getInstance().getClan(castle.getOwnerId()));
	}
	
	/**
	 * With the castles of fake clans turned off: their castles go back to the npcs.
	 * @return how many castles were given back
	 */
	private static int releaseCastles()
	{
		int released = 0;
		for (Castle castle : CastleManager.getInstance().getCastles())
		{
			final Clan owner = castle.getOwner();
			if ((owner != null) && FakeClanManager.getInstance().isFakeClan(owner) && !castle.getSiege().isInProgress())
			{
				castle.removeOwner(owner);
				released++;
			}
		}
		return released;
	}
	
	// ---------------------------------------------------------------------------------------------
	// Castles
	// ---------------------------------------------------------------------------------------------
	
	/**
	 * Every {@link #CHECK_INTERVAL} ms: castles without a lord are taken, and the lords run their castles.
	 * @param startup {@code true} at the server start: castles without a lord are taken right away
	 */
	private void check(boolean startup)
	{
		try
		{
			final long now = System.currentTimeMillis();
			final boolean refill = (FakeClanConfig.MANOR_REFILL_MINUTES > 0) && ((now - _lastRefill) >= (FakeClanConfig.MANOR_REFILL_MINUTES * 60000L));
			if (refill)
			{
				_lastRefill = now;
			}
			
			final CastleManorManager manor = CastleManorManager.getInstance();
			if (!manor.isModifiablePeriod())
			{
				_plannedNextPeriod.clear();
			}
			
			final Set<Long> waiting = new HashSet<>();
			for (Castle castle : CastleManager.getInstance().getCastles())
			{
				final int castleId = castle.getResidenceId();
				if (castle.getOwnerId() > 0)
				{
					_withoutLord.remove(castleId);
					if (isFakeCastle(castle))
					{
						runCastle(castle, refill, waiting);
					}
					continue;
				}
				
				if (!FakeClanConfig.CASTLE_IDS.contains(castleId) || castle.getSiege().isInProgress() || TerritoryWarManager.getInstance().isTWInProgress())
				{
					continue;
				}
				
				final long since = _withoutLord.computeIfAbsent(castleId, id -> startup ? 0 : now);
				if ((now - since) < (FakeClanConfig.CASTLE_TAKE_DELAY * 60000L))
				{
					continue;
				}
				
				final Clan clan = findClan();
				if (clan != null)
				{
					take(castle, clan);
					runCastle(castle, false, waiting);
				}
			}
			_defenders.keySet().retainAll(waiting);
		}
		catch (Exception e)
		{
			LOGGER.log(Level.WARNING, getClass().getSimpleName() + ": Could not look after the castles of fake clans.", e);
		}
	}
	
	/**
	 * @return a clan of FakeClanNames without a castle or fortress, at random; {@code null} if there is none
	 */
	private static Clan findClan()
	{
		final List<Clan> clans = new ArrayList<>();
		for (Clan clan : FakeClanManager.getInstance().getActiveClans())
		{
			if ((clan.getCastleId() == 0) && (clan.getFortId() == 0))
			{
				clans.add(clan);
			}
		}
		return clans.isEmpty() ? null : clans.get(Rnd.get(clans.size()));
	}
	
	/**
	 * A clan of fake players takes a castle without a lord.
	 * @param castle the castle
	 * @param clan the clan
	 */
	private void take(Castle castle, Clan clan)
	{
		castle.setOwner(clan);
		castle.setShowNpcCrest(true);
		_withoutLord.remove(castle.getResidenceId());
		
		// A lord that comes in the middle of a period sets its manor at once, the current period and the next one.
		final CastleManorManager manor = CastleManorManager.getInstance();
		if (GeneralConfig.ALLOW_MANOR && !manor.isUnderMaintenance())
		{
			final int castleId = castle.getResidenceId();
			manor.setCurrentSeedProduction(planSeeds(castleId), castleId);
			manor.setCurrentCropProcure(planCrops(castleId), castleId);
			planNextPeriod(castleId);
		}
		
		LOGGER.info(getClass().getSimpleName() + ": " + clan.getName() + " took the castle of " + castle.getName() + ".");
	}
	
	/**
	 * The lord runs its castle: tax rate, crest on the castle npcs, treasury, manor and the clans that want to defend it.
	 * @param castle the castle
	 * @param refill {@code true} to refill the seeds and crops of the current period
	 * @param waiting where the clans waiting for approval to defend a castle are put (castle id and clan id)
	 */
	private void runCastle(Castle castle, boolean refill, Set<Long> waiting)
	{
		final int tax = Math.min(FakeClanConfig.CASTLE_TAX_PERCENT, getTaxLimit());
		if (castle.getTaxPercent() != tax)
		{
			castle.setTaxPercent(tax);
		}
		castle.setShowNpcCrest(true);
		
		// The lord spends what its castle earns (taxes, seeds), so the clan that takes the castle doesn't find a treasury that grew for months, like at a castle without a lord.
		if (castle.getTreasury() > 0)
		{
			castle.addToTreasuryNoTax(-castle.getTreasury());
		}
		
		runManor(castle.getResidenceId(), refill);
		answerDefenders(castle, waiting);
	}
	
	/**
	 * @return the highest tax rate a lord can set now: by the owner of the Seal of Strife, like at the chamberlain
	 */
	private static int getTaxLimit()
	{
		switch (SevenSigns.getInstance().getSealOwner(SevenSigns.SEAL_STRIFE))
		{
			case SevenSigns.CABAL_DAWN:
			{
				return 25;
			}
			case SevenSigns.CABAL_DUSK:
			{
				return 5;
			}
			default:
			{
				return 15;
			}
		}
	}
	
	/**
	 * The lord decides on the players' clans that registered to defend its castle, a few minutes after they did. A clan refused stays waiting for approval.
	 * @param castle the castle
	 * @param waiting where the clans still waiting are put (castle id and clan id)
	 */
	private void answerDefenders(Castle castle, Set<Long> waiting)
	{
		final Siege siege = castle.getSiege();
		if (siege.isInProgress())
		{
			return;
		}
		
		final long now = System.currentTimeMillis();
		for (SiegeClan siegeClan : new ArrayList<>(siege.getDefenderWaitingClans()))
		{
			final long key = ((long) castle.getResidenceId() << 32) | (siegeClan.getClanId() & 0xFFFFFFFFL);
			waiting.add(key);
			
			final Long answer = _defenders.get(key);
			if (answer == null)
			{
				_defenders.put(key, now + (Rnd.get(DEFENDER_DECISION_MIN, DEFENDER_DECISION_MAX) * 60000L));
			}
			else if ((answer > 0) && (now >= answer))
			{
				if (Rnd.get(100) < FakeClanConfig.CASTLE_DEFENDER_ACCEPT_CHANCE)
				{
					siege.approveSiegeDefenderClan(siegeClan.getClanId());
					_defenders.remove(key);
				}
				else
				{
					_defenders.put(key, -1L);
				}
			}
		}
	}
	
	// ---------------------------------------------------------------------------------------------
	// Manor
	// ---------------------------------------------------------------------------------------------
	
	/**
	 * The lord runs the manor of its castle: the seeds and crops of the current period are there (refilled when {@code refill}) and, in the modifiable period, the next period is set once.
	 * @param castleId the castle id
	 * @param refill {@code true} to bring the seeds and crops of the current period back to the amounts of the period
	 */
	private void runManor(int castleId, boolean refill)
	{
		final CastleManorManager manor = CastleManorManager.getInstance();
		if (!GeneralConfig.ALLOW_MANOR || manor.isUnderMaintenance())
		{
			return;
		}
		
		// Nothing on sale (a castle taken before this, or the manor data was lost): the current period is set at once.
		final List<SeedProduction> production = manor.getSeedProduction(castleId, false);
		final List<CropProcure> procure = manor.getCropProcure(castleId, false);
		if ((production == null) || (procure == null))
		{
			return;
		}
		
		if (production.isEmpty() && procure.isEmpty())
		{
			manor.setCurrentSeedProduction(planSeeds(castleId), castleId);
			manor.setCurrentCropProcure(planCrops(castleId), castleId);
		}
		else if (refill)
		{
			refill(castleId, production, procure);
		}
		
		// The next period: set every modifiable period (new prices), and at once if it is empty.
		final List<SeedProduction> next = manor.getSeedProduction(castleId, true);
		if ((manor.isModifiablePeriod() && _plannedNextPeriod.add(castleId)) || (next == null) || (next.isEmpty() && manor.getCropProcure(castleId, true).isEmpty()))
		{
			planNextPeriod(castleId);
		}
	}
	
	/**
	 * Sets the next period of the manor of a castle.
	 * @param castleId the castle id
	 */
	private static void planNextPeriod(int castleId)
	{
		final CastleManorManager manor = CastleManorManager.getInstance();
		manor.setNextSeedProduction(planSeeds(castleId), castleId);
		manor.setNextCropProcure(planCrops(castleId), castleId);
	}
	
	/**
	 * Brings the seeds on sale and the crops bought back to the amounts of the period.
	 * @param castleId the castle id
	 * @param production the seeds of the current period
	 * @param procure the crops of the current period
	 */
	private static void refill(int castleId, List<SeedProduction> production, List<CropProcure> procure)
	{
		final List<SeedProduction> seeds = new ArrayList<>();
		for (SeedProduction seed : production)
		{
			if (seed.getAmount() < seed.getStartAmount())
			{
				seed.setAmount(seed.getStartAmount());
				seeds.add(seed);
			}
		}
		
		final List<CropProcure> crops = new ArrayList<>();
		for (CropProcure crop : procure)
		{
			if (crop.getAmount() < crop.getStartAmount())
			{
				crop.setAmount(crop.getStartAmount());
				crops.add(crop);
			}
		}
		
		if (GeneralConfig.ALT_MANOR_SAVE_ALL_ACTIONS)
		{
			if (!seeds.isEmpty())
			{
				CastleManorManager.getInstance().updateCurrentProduction(castleId, seeds);
			}
			if (!crops.isEmpty())
			{
				CastleManorManager.getInstance().updateCurrentProcure(castleId, crops);
			}
		}
	}
	
	/**
	 * @param castleId the castle id
	 * @return the seeds a lord sells for a period: all seeds of the castle, FakeClanManorSeedAmount % of their limit, at a price between FakeClanManorSeedPriceMin and FakeClanManorSeedPriceMax % of their base price
	 */
	private static List<SeedProduction> planSeeds(int castleId)
	{
		final List<SeedProduction> list = new ArrayList<>();
		for (Seed seed : getSeeds(castleId))
		{
			final long amount = ((long) seed.getSeedLimit() * FakeClanConfig.MANOR_SEED_AMOUNT) / 100;
			if (amount <= 0)
			{
				continue;
			}
			
			final long price = price(seed.getSeedReferencePrice(), FakeClanConfig.MANOR_SEED_PRICE_MIN, FakeClanConfig.MANOR_SEED_PRICE_MAX, seed.getSeedMinPrice(), seed.getSeedMaxPrice());
			list.add(new SeedProduction(seed.getSeedId(), amount, price, amount));
		}
		return list;
	}
	
	/**
	 * @param castleId the castle id
	 * @return the crops a lord buys for a period: all crops of the castle, FakeClanManorCropAmount % of their limit, at a price between FakeClanManorCropPriceMin and FakeClanManorCropPriceMax % of their base price, for the reward of FakeClanManorCropReward
	 */
	private static List<CropProcure> planCrops(int castleId)
	{
		final List<CropProcure> list = new ArrayList<>();
		final Set<Integer> crops = new HashSet<>();
		for (Seed seed : getSeeds(castleId))
		{
			if (!crops.add(seed.getCropId()))
			{
				continue;
			}
			
			final long amount = ((long) seed.getCropLimit() * FakeClanConfig.MANOR_CROP_AMOUNT) / 100;
			if (amount <= 0)
			{
				continue;
			}
			
			final long price = price(seed.getCropReferencePrice(), FakeClanConfig.MANOR_CROP_PRICE_MIN, FakeClanConfig.MANOR_CROP_PRICE_MAX, seed.getCropMinPrice(), seed.getCropMaxPrice());
			final int reward = FakeClanConfig.MANOR_CROP_REWARD > 0 ? FakeClanConfig.MANOR_CROP_REWARD : Rnd.get(1, 2);
			list.add(new CropProcure(seed.getCropId(), amount, reward, amount, price));
		}
		return list;
	}
	
	/**
	 * @param castleId the castle id
	 * @return the seeds of the castle, by level (a seed before its alternative one)
	 */
	private static List<Seed> getSeeds(int castleId)
	{
		final List<Seed> seeds = new ArrayList<>(CastleManorManager.getInstance().getSeedsForCastle(castleId));
		seeds.sort(Comparator.comparingInt(Seed::getLevel).thenComparing(Seed::isAlternative).thenComparingInt(Seed::getSeedId));
		return seeds;
	}
	
	/**
	 * @param base the base price
	 * @param minPercent the lowest price, in % of the base price
	 * @param maxPercent the highest price, in % of the base price
	 * @param min the lowest price a lord can set
	 * @param max the highest price a lord can set
	 * @return a price between min and max percent of the base price, within what a lord can set (1 at least)
	 */
	private static long price(long base, int minPercent, int maxPercent, long min, long max)
	{
		final long price = (base * Rnd.get(minPercent, maxPercent)) / 100;
		return Math.max(1, Math.max(min, Math.min(max, price)));
	}
	
	// ---------------------------------------------------------------------------------------------
	// Admin
	// ---------------------------------------------------------------------------------------------
	
	/**
	 * @return a line for each castle: its lord, tax rate, manor and next siege (for //fakeclans)
	 */
	public List<String> getInfo()
	{
		final List<String> lines = new ArrayList<>();
		lines.add("Castles of fake clans" + (isEnabled() ? "" : " (disabled)") + ", manor " + CastleManorManager.getInstance().getCurrentModeName().toLowerCase() + ":");
		final SimpleDateFormat format = new SimpleDateFormat("dd/MM HH:mm");
		for (Castle castle : CastleManager.getInstance().getCastles())
		{
			final Clan owner = castle.getOwner();
			final StringBuilder line = new StringBuilder(castle.getName()).append(": ");
			if (owner == null)
			{
				line.append("no lord");
				final Long since = _withoutLord.get(castle.getResidenceId());
				if ((since != null) && isEnabled() && FakeClanConfig.CASTLE_IDS.contains(castle.getResidenceId()))
				{
					line.append(", taken in ").append(Math.max(0, ((since + (FakeClanConfig.CASTLE_TAKE_DELAY * 60000L)) - System.currentTimeMillis()) / 60000)).append(" min");
				}
			}
			else
			{
				line.append(owner.getName()).append(isFakeCastle(castle) ? " (fake)" : "").append(", tax ").append(castle.getTaxPercent()).append('%');
				final List<SeedProduction> seeds = CastleManorManager.getInstance().getSeedProduction(castle.getResidenceId(), false);
				final List<CropProcure> crops = CastleManorManager.getInstance().getCropProcure(castle.getResidenceId(), false);
				if ((seeds != null) && (crops != null))
				{
					line.append(", ").append(seeds.size()).append(" seeds on sale, ").append(crops.size()).append(" crops bought");
				}
			}
			lines.add(line.append(", siege ").append(format.format(castle.getSiegeDate().getTime())).toString());
		}
		return lines;
	}
	
	public static FakeCastleManager getInstance()
	{
		return SingletonHolder.INSTANCE;
	}
	
	private static class SingletonHolder
	{
		protected static final FakeCastleManager INSTANCE = new FakeCastleManager();
	}
}
