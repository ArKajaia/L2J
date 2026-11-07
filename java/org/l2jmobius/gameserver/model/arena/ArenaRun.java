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

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.atomic.AtomicBoolean;

import org.l2jmobius.gameserver.config.RatesConfig;
import org.l2jmobius.gameserver.model.actor.enums.npc.MonsterArchetype;
import org.l2jmobius.gameserver.model.actor.instance.Monster;

/**
 * One Survival Arena run: who fights, what they staked, the coins earned so far and the omens and boons in play. Owned by {@link org.l2jmobius.gameserver.managers.ArenaSurvivalManager}; the challenger reads its stat multipliers from here.
 * <p>
 * The pot is kept per staked medal: the payout is the pot times the stake. {@link #getPot()} is what the run earned on its own (waves, omens, swift clears, boons, skipped waves) and is what a record - and so a quick run - is based on; the Arena Rush part is kept apart so a quick run
 * can't lock it in.
 */
public class ArenaRun
{
	private final int _playerObjectId;
	private final int _instanceId;
	private final int _stake;
	private final int _startWave;
	private final boolean _daily;
	private final long _startTime = System.currentTimeMillis();
	private final AtomicBoolean _ended = new AtomicBoolean();

	private volatile Monster _challenger;
	private volatile MonsterArchetype _originalArchetype;

	private volatile List<ArenaOmen> _omens = List.of();
	private final Map<ArenaBoon, Integer> _boons = new EnumMap<>(ArenaBoon.class);
	private volatile List<ArenaBoon> _offeredBoons = List.of();
	private volatile boolean _intermission;
	private volatile ScheduledFuture<?> _intermissionTask;

	private double _pot;
	private double _rushPot;
	private int _swiftStreak;
	private int _bestSwiftStreak;
	private volatile long _waveStartTime = System.currentTimeMillis();
	private volatile boolean _enragedThisWave;
	private int _caches;

	public ArenaRun(int playerObjectId, int instanceId, int stake, int startWave, boolean daily, double skippedValue)
	{
		_playerObjectId = playerObjectId;
		_instanceId = instanceId;
		_stake = stake;
		_startWave = startWave;
		_daily = daily;
		_pot = skippedValue;
	}

	public int getPlayerObjectId()
	{
		return _playerObjectId;
	}

	public int getInstanceId()
	{
		return _instanceId;
	}

	public int getStake()
	{
		return _stake;
	}

	public int getStartWave()
	{
		return _startWave;
	}

	public boolean isDaily()
	{
		return _daily;
	}

	public long getStartTime()
	{
		return _startTime;
	}

	public Monster getChallenger()
	{
		return _challenger;
	}

	public void setChallenger(Monster challenger)
	{
		_challenger = challenger;
	}

	/** The archetype the challenger had before an Arcane Surge turned it into a Mage, or {@code null}. */
	public MonsterArchetype getOriginalArchetype()
	{
		return _originalArchetype;
	}

	public void setOriginalArchetype(MonsterArchetype archetype)
	{
		_originalArchetype = archetype;
	}

	/**
	 * @return {@code true} the first time only: the run is over from then on and must not pay again
	 */
	public boolean markEnded()
	{
		return _ended.compareAndSet(false, true);
	}

	public boolean isEnded()
	{
		return _ended.get();
	}

	/**
	 * @return the waves cleared so far (the challenger's wave), or the start wave before it spawned
	 */
	public int getWave()
	{
		final Monster challenger = _challenger;
		return challenger != null ? challenger.getVariables().getInt("ARENA_WAVE", _startWave) : _startWave;
	}

	// Omens

	public List<ArenaOmen> getOmens()
	{
		return _omens;
	}

	public void setOmens(List<ArenaOmen> omens)
	{
		_omens = List.copyOf(omens);
	}

	public int getOmenPayPercent()
	{
		int percent = 0;
		for (ArenaOmen omen : _omens)
		{
			percent += omen.getPayBonusPercent();
		}
		return percent;
	}

	// Boons

	public synchronized int getBoonCount(ArenaBoon boon)
	{
		return _boons.getOrDefault(boon, 0);
	}

	public synchronized void addBoon(ArenaBoon boon)
	{
		_boons.merge(boon, 1, Integer::sum);
	}

	public synchronized Map<ArenaBoon, Integer> getBoons()
	{
		return new EnumMap<>(_boons);
	}

	public List<ArenaBoon> getOfferedBoons()
	{
		return _offeredBoons;
	}

	public void setOfferedBoons(List<ArenaBoon> boons)
	{
		_offeredBoons = List.copyOf(boons);
	}

	// Breather

	public boolean isIntermission()
	{
		return _intermission;
	}

	public void setIntermission(boolean intermission)
	{
		_intermission = intermission;
	}

	public ScheduledFuture<?> getIntermissionTask()
	{
		return _intermissionTask;
	}

	public void setIntermissionTask(ScheduledFuture<?> task)
	{
		_intermissionTask = task;
	}

	// Stat multipliers read by the challenger

	/** Multiplies the challenger's P. Atk. and M. Atk.: omens, and the player's Iron Skin. */
	public double getAttackMultiplier()
	{
		double multiplier = Math.pow(ArenaBoon.IRON_SKIN_ATTACK, getBoonCount(ArenaBoon.IRON_SKIN));
		for (ArenaOmen omen : _omens)
		{
			multiplier *= omen.getAttackMultiplier();
		}
		return multiplier;
	}

	public double getDefenseMultiplier()
	{
		double multiplier = 1;
		for (ArenaOmen omen : _omens)
		{
			multiplier *= omen.getDefenseMultiplier();
		}
		return multiplier;
	}

	public double getSpeedMultiplier()
	{
		double multiplier = 1;
		for (ArenaOmen omen : _omens)
		{
			multiplier *= omen.getSpeedMultiplier();
		}
		return multiplier;
	}

	/**
	 * @param fromSkill {@code true} if the damage comes from a skill (or its damage over time)
	 * @param hpRatio the challenger's current HP share, 0 to 1
	 * @return what the damage the challenger takes is multiplied by: omens, and the player's Bloodlust and Executioner
	 */
	public double getDamageTakenMultiplier(boolean fromSkill, double hpRatio)
	{
		double multiplier = 1 + (ArenaBoon.BLOODLUST_DAMAGE * getBoonCount(ArenaBoon.BLOODLUST));
		if (hpRatio < ArenaBoon.EXECUTIONER_THRESHOLD)
		{
			multiplier *= 1 + (ArenaBoon.EXECUTIONER_DAMAGE * getBoonCount(ArenaBoon.EXECUTIONER));
		}
		for (ArenaOmen omen : _omens)
		{
			multiplier *= omen.getDamageTakenMultiplier();
			if (fromSkill)
			{
				multiplier *= omen.getSkillDamageTakenMultiplier();
			}
		}
		return multiplier;
	}

	/** Percent of the damage it deals that heals the challenger (Vampiric). */
	public int getLifeStealPercent()
	{
		int percent = 0;
		for (ArenaOmen omen : _omens)
		{
			percent += omen.getLifeStealPercent();
		}
		return percent;
	}

	/** Share of the damage the player deals to the challenger that heals the player (Leech). */
	public double getLeechShare()
	{
		return ArenaBoon.LEECH_SHARE * getBoonCount(ArenaBoon.LEECH);
	}

	/** Seconds this wave lasts before the challenger enrages: the config, the omens and Patience. Never less than 20. */
	public int getEnrageSeconds()
	{
		int seconds = RatesConfig.ARENA_ENRAGE_TIME + (ArenaBoon.PATIENCE_SECONDS * getBoonCount(ArenaBoon.PATIENCE));
		for (ArenaOmen omen : _omens)
		{
			seconds += omen.getEnrageSecondsDelta();
		}
		return Math.max(20, seconds);
	}

	// Pay

	public long getWaveStartTime()
	{
		return _waveStartTime;
	}

	/** Starts the clock of a new wave (swift clears and the enrage are measured from here). */
	public void startWaveClock()
	{
		_waveStartTime = System.currentTimeMillis();
		_enragedThisWave = false;
	}

	public void setEnragedThisWave()
	{
		_enragedThisWave = true;
	}

	public boolean isEnragedThisWave()
	{
		return _enragedThisWave;
	}

	/**
	 * Counts a wave cleared toward the swift streak.
	 * @param swift {@code true} if it was cleared in time
	 * @return the swift bonus percent this wave pays (0 if it was not swift)
	 */
	public synchronized int recordClear(boolean swift)
	{
		if (!swift)
		{
			if (_enragedThisWave || (getBoonCount(ArenaBoon.MOMENTUM) == 0))
			{
				_swiftStreak = 0;
			}
			return 0;
		}

		_swiftStreak++;
		_bestSwiftStreak = Math.max(_bestSwiftStreak, _swiftStreak);
		return RatesConfig.ARENA_SWIFT_BONUS_PERCENT + Math.min(RatesConfig.ARENA_SWIFT_STREAK_MAX_PERCENT, RatesConfig.ARENA_SWIFT_STREAK_STEP_PERCENT * (_swiftStreak - 1));
	}

	public synchronized int getSwiftStreak()
	{
		return _swiftStreak;
	}

	public synchronized int getBestSwiftStreak()
	{
		return _bestSwiftStreak;
	}

	/**
	 * @param earned what the run itself earned per medal this wave
	 * @param rush the Arena Rush extra per medal this wave
	 */
	public synchronized void addToPot(double earned, double rush)
	{
		_pot += earned;
		_rushPot += rush;
	}

	/** What the run earned on its own per medal (no Rush or daily bonus): the base of a record and of a quick run. */
	public synchronized double getPot()
	{
		return _pot;
	}

	/** The Arena Rush extra per medal. */
	public synchronized double getRushPot()
	{
		return _rushPot;
	}

	/** Everything earned per medal so far, before the daily bonus. */
	public synchronized double getTotalPot()
	{
		return _pot + _rushPot;
	}

	public synchronized void addCache()
	{
		_caches++;
	}

	public synchronized int getCaches()
	{
		return _caches;
	}
}
