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
package org.l2jmobius.gameserver.model.actor.holders.npc;

import org.l2jmobius.commons.util.Rnd;
import org.l2jmobius.gameserver.config.custom.FakePlayerPvpConfig;
import org.l2jmobius.gameserver.model.actor.Npc;

/**
 * The temper of a single roaming fake player: how its behaviour values differ from the ones in FakePlayerPvp.ini, so no two fake players play exactly alike.<br>
 * Every fake player rolls four traits, and each behaviour value follows one of them, with a bit of its own randomness on top:
 * <ul>
 * <li>aggression: picks fights with flagged and karma players, taunts lower levels, takes revenge for a stolen kill, comes back for round two and chases further; the opposite runs away sooner, refuses to hit back more often and drinks potions earlier;</li>
 * <li>skill: uses its skills and debuffs more, notices a player's defensive buffs sooner, keeps a better distance and takes out its bow sooner;</li>
 * <li>chattiness: talks more (or less) in general chat;</li>
 * <li>roaming: hunts further from its spawn point.</li>
 * </ul>
 * Only the scales are rolled, the values are read from the config on every use, so a config reload applies to fake players already out. A value set to 0 in the config stays 0 (off).
 */
public class FakePlayerPvpPersonality
{
	/** Everybody plays exactly by the config. */
	public static final FakePlayerPvpPersonality NEUTRAL = new FakePlayerPvpPersonality(false);

	// How much a value follows its trait, the rest is its own randomness.
	private static final double TRAIT_WEIGHT = 0.7;

	// Traits, from -1 to 1.
	private final double _aggression;
	private final double _skill;
	private final double _chattiness;
	private final double _roaming;

	// Chances, from -1 to 1 of FakePvpPersonalityVariance.
	private final double _skillChance;
	private final double _pvpSkillChance;
	private final double _autoAttackSkillChance;
	private final double _autoAttackPvpSkillChance;
	private final double _pvpDebuffChance;
	private final double _defenseDetectChance;
	private final double _revengeChance;
	private final double _attackFlaggedChance;
	private final double _attackKarmaChance;
	private final double _fleeChance;
	private final double _returnChance;
	private final double _tauntChance;
	private final double _greetChance;
	private final double _pokeChance;
	private final double _refuseChance;
	private final double _meetChance;
	private final double _rivalryChance;
	private final double _fakeKillStealChance;
	private final double _joinFightChance;

	// Distances, times and thresholds, from -1 to 1 of FakePvpPersonalityRangeVariance.
	private final double _huntRange;
	private final double _leashRange;
	private final double _chaseRange;
	private final double _kiteDistance;
	private final double _kiteStep;
	private final double _defenseKeepDistance;
	private final double _weaponSwapChaseTime;
	private final double _returnRevengeTime;
	private final double _potionHpPercent;

	/**
	 * @return a new random personality
	 */
	public static FakePlayerPvpPersonality random()
	{
		return new FakePlayerPvpPersonality(true);
	}
	
	/**
	 * @return the temper of a strong player (the stronger fake players of the PvP spots, see {@link org.l2jmobius.gameserver.managers.PvpSpotManager}): skilled and aggressive, the rest random
	 */
	public static FakePlayerPvpPersonality elite()
	{
		return new FakePlayerPvpPersonality(0.5 + (Rnd.nextDouble() * 0.5), 0.7 + (Rnd.nextDouble() * 0.3), roll(), roll());
	}

	/**
	 * @param npc a roaming fake player
	 * @return its personality, {@link #NEUTRAL} if it has none
	 */
	public static FakePlayerPvpPersonality of(Npc npc)
	{
		final FakePlayerPvpProfile profile = npc.getTemplate().getFakePlayerPvpProfile();
		return profile != null ? profile.getPersonality() : NEUTRAL;
	}
	
	private FakePlayerPvpPersonality(boolean random)
	{
		this(random, random ? roll() : 0, random ? roll() : 0, random ? roll() : 0, random ? roll() : 0);
	}
	
	/**
	 * A personality with the given traits (from -1 to 1), each value following its trait with a bit of its own randomness.
	 */
	private FakePlayerPvpPersonality(double aggression, double skill, double chattiness, double roaming)
	{
		this(true, aggression, skill, chattiness, roaming);
	}
	
	private FakePlayerPvpPersonality(boolean random, double aggression, double skill, double chattiness, double roaming)
	{
		_aggression = aggression;
		_skill = skill;
		_chattiness = chattiness;
		_roaming = roaming;

		_skillChance = follow(random, _skill);
		_pvpSkillChance = follow(random, _skill);
		_autoAttackSkillChance = follow(random, _skill);
		_autoAttackPvpSkillChance = follow(random, _skill);
		_pvpDebuffChance = follow(random, _skill);
		_defenseDetectChance = follow(random, _skill);
		_revengeChance = follow(random, _aggression);
		_attackFlaggedChance = follow(random, _aggression);
		_attackKarmaChance = follow(random, _aggression);
		_fleeChance = follow(random, -_aggression);
		_returnChance = follow(random, _aggression);
		_tauntChance = follow(random, _chattiness);
		_greetChance = follow(random, _chattiness);
		_pokeChance = follow(random, _aggression);
		_refuseChance = follow(random, -_aggression);
		_meetChance = follow(random, _chattiness);
		_rivalryChance = follow(random, _aggression);
		_fakeKillStealChance = follow(random, _aggression);
		_joinFightChance = follow(random, _aggression);

		_huntRange = follow(random, _roaming);
		_leashRange = follow(random, _roaming);
		_chaseRange = follow(random, _aggression);
		_kiteDistance = follow(random, _skill);
		_kiteStep = follow(random, _skill);
		_defenseKeepDistance = follow(random, _skill);
		_weaponSwapChaseTime = follow(random, -_skill);
		_returnRevengeTime = follow(random, _aggression);
		_potionHpPercent = follow(random, -_aggression);
	}

	/**
	 * @return a random value from -1 to 1
	 */
	private static double roll()
	{
		return (Rnd.nextDouble() * 2) - 1;
	}

	/**
	 * @param random {@code false} for no difference at all
	 * @param trait the trait the value follows
	 * @return how far the value is from the config, from -1 to 1
	 */
	private static double follow(boolean random, double trait)
	{
		return random ? Math.max(-1, Math.min(1, (TRAIT_WEIGHT * trait) + ((1 - TRAIT_WEIGHT) * roll()))) : 0;
	}

	/**
	 * @param value a chance (in %) from the config
	 * @param offset how far this fake player is from it, from -1 to 1
	 * @return its own chance, from 0 to 100
	 */
	private static int chance(double value, double offset)
	{
		return Math.max(0, Math.min(100, (int) Math.round(value * (1 + ((offset * FakePlayerPvpConfig.PERSONALITY_VARIANCE) / 100.0)))));
	}

	/**
	 * @param value a distance, time or threshold from the config
	 * @param offset how far this fake player is from it, from -1 to 1
	 * @return its own value
	 */
	private static int range(int value, double offset)
	{
		return (int) Math.round(value * (1 + ((offset * FakePlayerPvpConfig.PERSONALITY_RANGE_VARIANCE) / 100.0)));
	}

	public double getAggression()
	{
		return _aggression;
	}

	public double getSkill()
	{
		return _skill;
	}

	public double getChattiness()
	{
		return _chattiness;
	}

	public double getRoaming()
	{
		return _roaming;
	}

	/**
	 * @return its {@link FakePlayerPvpConfig#SKILL_CHANCE}
	 */
	public int getSkillChance()
	{
		return chance(FakePlayerPvpConfig.SKILL_CHANCE, _skillChance);
	}

	/**
	 * @return its {@link FakePlayerPvpConfig#PVP_SKILL_CHANCE}
	 */
	public int getPvpSkillChance()
	{
		return chance(FakePlayerPvpConfig.PVP_SKILL_CHANCE, _pvpSkillChance);
	}

	/**
	 * @return its {@link FakePlayerPvpConfig#AUTO_ATTACK_SKILL_CHANCE}
	 */
	public int getAutoAttackSkillChance()
	{
		return chance(FakePlayerPvpConfig.AUTO_ATTACK_SKILL_CHANCE, _autoAttackSkillChance);
	}

	/**
	 * @return its {@link FakePlayerPvpConfig#AUTO_ATTACK_PVP_SKILL_CHANCE}
	 */
	public int getAutoAttackPvpSkillChance()
	{
		return chance(FakePlayerPvpConfig.AUTO_ATTACK_PVP_SKILL_CHANCE, _autoAttackPvpSkillChance);
	}

	/**
	 * @return its {@link FakePlayerPvpConfig#PVP_DEBUFF_CHANCE}
	 */
	public int getPvpDebuffChance()
	{
		return chance(FakePlayerPvpConfig.PVP_DEBUFF_CHANCE, _pvpDebuffChance);
	}

	/**
	 * @return its {@link FakePlayerPvpConfig#DEFENSE_DETECT_CHANCE}
	 */
	public int getDefenseDetectChance()
	{
		return chance(FakePlayerPvpConfig.DEFENSE_DETECT_CHANCE, _defenseDetectChance);
	}

	/**
	 * @return its {@link FakePlayerPvpConfig#REVENGE_CHANCE}
	 */
	public int getRevengeChance()
	{
		return chance(FakePlayerPvpConfig.REVENGE_CHANCE, _revengeChance);
	}

	/**
	 * @return its {@link FakePlayerPvpConfig#ATTACK_FLAGGED_CHANCE}
	 */
	public int getAttackFlaggedChance()
	{
		return chance(FakePlayerPvpConfig.ATTACK_FLAGGED_CHANCE, _attackFlaggedChance);
	}

	/**
	 * @return its {@link FakePlayerPvpConfig#ATTACK_KARMA_CHANCE}
	 */
	public int getAttackKarmaChance()
	{
		return chance(FakePlayerPvpConfig.ATTACK_KARMA_CHANCE, _attackKarmaChance);
	}

	/**
	 * @return its {@link FakePlayerPvpConfig#FLEE_CHANCE}
	 */
	public int getFleeChance()
	{
		return chance(FakePlayerPvpConfig.FLEE_CHANCE, _fleeChance);
	}

	/**
	 * @return its {@link FakePlayerPvpConfig#RETURN_CHANCE}
	 */
	public int getReturnChance()
	{
		return chance(FakePlayerPvpConfig.RETURN_CHANCE, _returnChance);
	}

	/**
	 * @return its {@link FakePlayerPvpConfig#TAUNT_CHANCE}
	 */
	public int getTauntChance()
	{
		return chance(FakePlayerPvpConfig.TAUNT_CHANCE, _tauntChance);
	}

	/**
	 * @return its {@link FakePlayerPvpConfig#GREET_CHANCE}
	 */
	public int getGreetChance()
	{
		return chance(FakePlayerPvpConfig.GREET_CHANCE, _greetChance);
	}

	/**
	 * @param levelDiff how many levels it is above the one it may taunt
	 * @return its chance (in %) to walk up to them and hit them once (see {@link FakePlayerPvpConfig#POKE_CHANCE_MIN})
	 */
	public int getPokeChance(int levelDiff)
	{
		return chance(FakePlayerPvpConfig.levelDiffChance(FakePlayerPvpConfig.POKE_CHANCE_MIN, FakePlayerPvpConfig.POKE_CHANCE_MAX, FakePlayerPvpConfig.POKE_MAX_CHANCE_LEVEL_DIFF, levelDiff), _pokeChance);
	}
	
	/**
	 * @param levelDiff how many levels it is below the one that attacks it
	 * @return its chance (in %) not to hit back while it isn't flagged (see {@link FakePlayerPvpConfig#REFUSE_CHANCE_MIN})
	 */
	public int getRefuseChance(int levelDiff)
	{
		return chance(FakePlayerPvpConfig.levelDiffChance(FakePlayerPvpConfig.REFUSE_CHANCE_MIN, FakePlayerPvpConfig.REFUSE_CHANCE_MAX, FakePlayerPvpConfig.REFUSE_MAX_CHANCE_LEVEL_DIFF, levelDiff), _refuseChance);
	}

	/**
	 * @return its chance (in %) to walk over to another fake player it sees (see {@link FakePlayerPvpConfig#MEET_CHANCE}), higher for a chatty one
	 */
	public int getMeetChance()
	{
		return chance(FakePlayerPvpConfig.MEET_CHANCE, _meetChance);
	}

	/**
	 * @return its chance (in %) that meeting another fake player turns into a fight over the spot (see {@link FakePlayerPvpConfig#RIVALRY_CHANCE})
	 */
	public int getRivalryChance()
	{
		return chance(FakePlayerPvpConfig.RIVALRY_CHANCE, _rivalryChance);
	}

	/**
	 * @return its chance (in %) to take a monster another fake player is fighting (see {@link FakePlayerPvpConfig#FAKE_KILL_STEAL_CHANCE})
	 */
	public int getFakeKillStealChance()
	{
		return chance(FakePlayerPvpConfig.FAKE_KILL_STEAL_CHANCE, _fakeKillStealChance);
	}

	/**
	 * @return its chance (in %) to join a fight of other fake players (see {@link FakePlayerPvpConfig#JOIN_FIGHT_CHANCE})
	 */
	public int getJoinFightChance()
	{
		return chance(FakePlayerPvpConfig.JOIN_FIGHT_CHANCE, _joinFightChance);
	}

	/**
	 * @return its {@link FakePlayerPvpConfig#HUNT_RANGE}
	 */
	public int getHuntRange()
	{
		return Math.max(100, range(FakePlayerPvpConfig.HUNT_RANGE, _huntRange));
	}

	/**
	 * @return its {@link FakePlayerPvpConfig#LEASH_RANGE}
	 */
	public int getLeashRange()
	{
		return Math.max(500, range(FakePlayerPvpConfig.LEASH_RANGE, _leashRange));
	}

	/**
	 * @return its {@link FakePlayerPvpConfig#CHASE_RANGE}, never shorter than its leash range (it chases a player at least as far as a monster)
	 */
	public int getChaseRange()
	{
		return Math.max(getLeashRange(), Math.max(500, range(FakePlayerPvpConfig.CHASE_RANGE, _chaseRange)));
	}

	/**
	 * @return its {@link FakePlayerPvpConfig#KITE_DISTANCE}
	 */
	public int getKiteDistance()
	{
		return Math.max(0, range(FakePlayerPvpConfig.KITE_DISTANCE, _kiteDistance));
	}

	/**
	 * @return its {@link FakePlayerPvpConfig#KITE_STEP}
	 */
	public int getKiteStep()
	{
		return Math.max(50, range(FakePlayerPvpConfig.KITE_STEP, _kiteStep));
	}

	/**
	 * @return its {@link FakePlayerPvpConfig#DEFENSE_KEEP_DISTANCE}
	 */
	public int getDefenseKeepDistance()
	{
		return Math.max(0, range(FakePlayerPvpConfig.DEFENSE_KEEP_DISTANCE, _defenseKeepDistance));
	}

	/**
	 * @return its {@link FakePlayerPvpConfig#WEAPON_SWAP_CHASE_TIME}
	 */
	public int getWeaponSwapChaseTime()
	{
		return Math.max(0, range(FakePlayerPvpConfig.WEAPON_SWAP_CHASE_TIME, _weaponSwapChaseTime));
	}

	/**
	 * @return its {@link FakePlayerPvpConfig#RETURN_REVENGE_TIME}
	 */
	public int getReturnRevengeTime()
	{
		return Math.max(0, range(FakePlayerPvpConfig.RETURN_REVENGE_TIME, _returnRevengeTime));
	}

	/**
	 * @return its {@link FakePlayerPvpConfig#POTION_HP_PERCENT}
	 */
	public int getPotionHpPercent()
	{
		return Math.max(0, Math.min(100, range(FakePlayerPvpConfig.POTION_HP_PERCENT, _potionHpPercent)));
	}

	@Override
	public String toString()
	{
		return String.format("aggression %+.2f, skill %+.2f, chattiness %+.2f, roaming %+.2f", _aggression, _skill, _chattiness, _roaming);
	}
}
