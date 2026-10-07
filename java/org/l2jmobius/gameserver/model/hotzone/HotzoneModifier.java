package org.l2jmobius.gameserver.model.hotzone;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Set;

import org.l2jmobius.gameserver.model.actor.enums.creature.Race;

/**
 * A rotating hotzone modifier: one of these is rolled fresh for each hotzone every time {@code custom.RotatingHotZones} rotates (see {@link org.l2jmobius.gameserver.managers.HotzoneModifierManager}), and stays active for that zone until the next rotation.
 * <p>
 * Every field defaults to "no effect" (1.0 for a multiplier, 0/false otherwise, -1 for an override), so a modifier only needs to set the handful of fields it actually cares about. Adding a new modifier is just adding a new enum constant - every read site (Monster's stat/reward/aggro
 * overrides, the champion spawn roll in Spawn, the Thief/Mage/wave challenge/fake player spawn rolls, the rage threshold in MonsterRageManager, the Luck rolls in LuckyLootManager, the party XP in Attackable, the archetype skill grant in AttackableAI, the stun-immunity check in the Stun
 * effect, the kill hook and the bounty/Heat tracking in HotzoneModifierManager, the modifier buffs in RotatingHotZones) already knows how to consult these generic knobs, so nothing else needs to change.
 * <p>
 * Deliberately a mix of purely positive, purely negative, and risk/reward modifiers, matching how the base game's own hotzone system already trades a combat bonus for being flagged PvP-able.
 * <p>
 * The same modifiers are also a night's Omen: {@link org.l2jmobius.gameserver.managers.NightCycleManager} sets one for the whole open world while it is night (see {@link org.l2jmobius.gameserver.managers.HotzoneModifierManager#getModifierFor}). The {@link #isNightOnly()} ones are only
 * ever used that way.
 */
public enum HotzoneModifier
{
	ARCHETYPE_HUNTER("Monsters know 3 extra combat skills. Drop rate +20%.")
	{
		{
			extraArchetypeSkills = 3;
			dropRateMult = 1.20;
		}
	},
	CHAMPION_SURGE("Champion monster spawn rate doubled.")
	{
		{
			championSpawnMult = 2.0;
		}
	},
	BLOODY_HARVEST("Drop rate +30%, but monsters hit 20% harder.")
	{
		{
			dropRateMult = 1.30;
			monsterAtkMult = 1.20;
		}
	},
	VETERAN_GROUNDS("+20% XP/SP, but monsters are 20% faster (attack and cast speed).")
	{
		{
			xpSpMult = 1.20;
			monsterSpdMult = 1.20;
		}
	},
	UNBREAKABLE("Monsters cannot be stunned. Spoil amount doubled.")
	{
		{
			monsterStunImmune = true;
			spoilRateMult = 2.0;
		}
	},
	FRAGILE_GROUND("Monsters have half HP, but players slowly lose HP while inside.")
	{
		{
			monsterHpMult = 0.5;
			playerHpDrainPctPerTick = 0.5;
		}
	},
	IRONCLAD("Monsters' P.Def and M.Def are increased by 50%.")
	{
		{
			monsterDefMult = 1.5;
		}
	},
	GENEROUS_SPIRITS("Drop rate and XP/SP both +15% - no downside.")
	{
		{
			dropRateMult = 1.15;
			xpSpMult = 1.15;
		}
	},
	GOLD_RUSH("Hotzone coins x2, adena drops +50%.")
	{
		{
			coinMult = 2.0;
			adenaMult = 1.5;
		}
	},
	KILL_STREAK("Chain kills within 15s: +3% XP/SP per kill, up to +30%.")
	{
		{
			killStreak = true;
		}
	},
	GLASS_CANNON("You deal 25% more damage, but also take 25% more.")
	{
		{
			playerBuffSkillId = GLASS_CANNON_SKILL_ID;
		}
	},
	VAMPIRIC_HUNT("Every kill restores 5% of your max HP and MP.")
	{
		{
			killHealPct = 5;
		}
	},
	ARCANE_SURGE("Skill reuse -20%, MP cost -30%, but monsters' M.Atk +20%.")
	{
		{
			playerBuffSkillId = ARCANE_SURGE_SKILL_ID;
			monsterMAtkMult = 1.2;
		}
	},
	BLOOD_MOON("Monsters respawn twice as fast and hit 10% harder.")
	{
		{
			respawnDelayMult = 0.5;
			monsterAtkMult = 1.1;
		}
	},
	RESTLESS_DEAD("Slain monsters may rise once more (15%) at half HP, for double XP/SP.")
	{
		{
			riseChancePct = 15;
		}
	},
	MINIBOSS_FRENZY("Miniboss appears after half the kills and always drops double coins.")
	{
		{
			minibossKillsMult = 0.5;
			minibossCoinMult = 2.0;
		}
	},
	THIEVES_DEN("Thieves are 5x as common and fill their bag twice as fast. A full Thief flees!")
	{
		{
			thiefSpawnMult = 5.0;
			thiefKillWeight = 2;
			thiefFlees = true;
		}
	},
	PROVING_GROUNDS("Wave challenges are 5x as common, with one more wave and 50% more coins.")
	{
		{
			waveSpawnMult = 5.0;
			extraWaves = 1;
			waveCoinMult = 1.5;
		}
	},
	METAMORPHOSIS("Wounded monsters may evolve into champions. Evolved ones give double XP/SP.")
	{
		{
			metamorphChancePct = 20;
		}
	},
	HAIR_TRIGGER("Monsters may rage from the first stun. A raging kill drops double and adds Luck.")
	{
		{
			rageDisablesOverride = 0;
			rageKillDropMult = 2.0;
			rageKillLuckStack = true;
		}
	},
	LUCKY_STARS("Luck builds twice as fast, dying costs only half of it, jackpots +50%.")
	{
		{
			luckGainMult = 2.0;
			luckDeathKeepsHalf = true;
			jackpotMult = 1.5;
		}
	},
	CONTESTED_GROUND("Rival adventurers swarm here. Each one slain pays 5x coins and a Sealed Cache.")
	{
		{
			fakePvpSpawnMult = 3.0;
			fakePlayerCoinMult = 5.0;
			fakePlayerCache = true;
		}
	},
	BOUNTY_HUNT("Every 5 minutes a monster is marked [Bounty]: 20x coins and a Sealed Cache.")
	{
		{
			bounty = true;
		}
	},
	RISING_HEAT("Every 100 kills raise the Heat (max 5): monsters +4% Atk, you +6% XP and drops.")
	{
		{
			heat = true;
		}
	},
	HORNETS_NEST("Every monster is aggressive, with double aggro range. XP/SP +25%.")
	{
		{
			forceAggressive = true;
			aggroRangeMult = 2.0;
			xpSpMult = 1.25;
		}
	},
	COVEN("Mage monsters are 5x as common. Bring M.Def.")
	{
		{
			mageSpawnMult = 5.0;
		}
	},
	KINSHIP("XP/SP +5% per party member also here (max +40%). Solo hunters -10%.")
	{
		{
			kinship = true;
		}
	},
	LAST_STAND("Below 30% HP: P.Atk/M.Atk +30% and 10% of the damage you deal heals you.")
	{
		{
			playerBuffSkillId = LAST_STAND_SKILL_ID;
		}
	},
	SPLITTING_GROUND("Slain monsters may split (15%) into two weaker copies worth half rewards.")
	{
		{
			splitChancePct = 15;
		}
	},
	// Night-only omens (see NightCycleManager): never rolled for a hotzone.
	NEW_MOON("A moonless night: monsters notice you from half as far. XP/SP +10%.")
	{
		{
			nightOnly = true;
			aggroRangeMult = 0.5;
			xpSpMult = 1.10;
		}
	},
	FULL_MOON("The full moon maddens beasts and animals: they hit 25% harder. Monsters may rage from the first stun. Drop rate +20%.")
	{
		{
			nightOnly = true;
			favoredRaces = EnumSet.of(Race.BEAST, Race.ANIMAL);
			favoredRaceAtkMult = 1.25;
			rageDisablesOverride = 0;
			dropRateMult = 1.20;
		}
	},
	STARFALL("Stars fall all night: Luck builds 50% faster and jackpots are twice as likely.")
	{
		{
			nightOnly = true;
			luckGainMult = 1.5;
			jackpotMult = 2.0;
		}
	},
	WITCHING_HOUR("The dead walk: slain monsters may rise again (20%), undead hit 30% harder, wave challenges are 3x as common. XP/SP +20%.")
	{
		{
			nightOnly = true;
			riseChancePct = 20;
			favoredRaces = EnumSet.of(Race.UNDEAD);
			favoredRaceAtkMult = 1.30;
			waveSpawnMult = 3.0;
			xpSpMult = 1.20;
		}
	};

	/** Stacking XP/SP buff (levels 1-{@link #KILL_STREAK_MAX_LEVEL}) re-applied one level higher on every kill under {@link #KILL_STREAK}; its 15s abnormal time is the chain window. */
	public static final int KILL_STREAK_SKILL_ID = 27002;
	public static final int KILL_STREAK_MAX_LEVEL = 10;
	/** Player buff held while inside a {@link #GLASS_CANNON} zone. */
	public static final int GLASS_CANNON_SKILL_ID = 27003;
	/** Player buff held while inside an {@link #ARCANE_SURGE} zone. */
	public static final int ARCANE_SURGE_SKILL_ID = 27004;
	/** Player buff held while inside a {@link #LAST_STAND} zone: its bonuses only apply below 30% HP. */
	public static final int LAST_STAND_SKILL_ID = 27005;
	/** Most Heat stacks a {@link #RISING_HEAT} zone can build. */
	public static final int HEAT_MAX_STACKS = 5;
	/** Kills a {@link #RISING_HEAT} zone needs for each Heat stack. */
	public static final int HEAT_KILLS_PER_STACK = 100;
	/** Monster P.Atk/M.Atk bonus (%) per Heat stack. */
	public static final int HEAT_ATTACK_PCT_PER_STACK = 4;
	/** Player XP/SP and drop bonus (%) per Heat stack. */
	public static final int HEAT_REWARD_PCT_PER_STACK = 6;

	private final String description;

	protected double dropRateMult = 1.0;
	protected double spoilRateMult = 1.0;
	protected double xpSpMult = 1.0;
	protected double monsterAtkMult = 1.0;
	protected double monsterDefMult = 1.0;
	protected double monsterSpdMult = 1.0;
	protected double monsterHpMult = 1.0;
	protected double championSpawnMult = 1.0;
	protected int extraArchetypeSkills = 0;
	protected boolean monsterStunImmune = false;
	/** Percent of max HP a player loses per {@link org.l2jmobius.gameserver.managers.HotzoneModifierManager#PLAYER_DRAIN_INTERVAL_MS} while inside. 0 = no drain. */
	protected double playerHpDrainPctPerTick = 0.0;
	/** Multiplier on monster M.Atk only, on top of {@link #monsterAtkMult} (which already covers both P.Atk and M.Atk). */
	protected double monsterMAtkMult = 1.0;
	/** Multiplier on the hotzone coin amount (see HotzoneCoinDropManager). */
	protected double coinMult = 1.0;
	/** Multiplier on adena dropped by monsters inside. */
	protected double adenaMult = 1.0;
	/** Multiplier on the respawn delay of monsters that die inside. */
	protected double respawnDelayMult = 1.0;
	/** Multiplier on how many kills it takes to spawn the hotzone miniboss. */
	protected double minibossKillsMult = 1.0;
	/** When above 1.0, a hotzone miniboss always drops coins, this many times the normal amount. */
	protected double minibossCoinMult = 1.0;
	/** Percent of max HP/MP restored to the killer on every kill. 0 = none. */
	protected int killHealPct = 0;
	/** Percent chance a slain monster rises once more at half HP (worth double XP/SP). 0 = never. */
	protected int riseChancePct = 0;
	/** Whether kills stack the {@link #KILL_STREAK_SKILL_ID} buff. */
	protected boolean killStreak = false;
	/** Buff skill every player inside holds while this modifier is active. 0 = none. */
	protected int playerBuffSkillId = 0;
	/** Multiplier on the Thief spawn chance (see ThiefMonsterManager). */
	protected double thiefSpawnMult = 1.0;
	/** How much one nearby kill fills a Thief's bag (in kills). */
	protected int thiefKillWeight = 1;
	/** Whether a Thief with a full bag runs away, and escapes with its loot if nobody catches it. */
	protected boolean thiefFlees = false;
	/** Multiplier on the open-world wave challenge spawn chance (see WaveChallengeManager). */
	protected double waveSpawnMult = 1.0;
	/** Waves added to every wave challenge that starts inside. */
	protected int extraWaves = 0;
	/** Multiplier on the coins a wave challenge pays out. */
	protected double waveCoinMult = 1.0;
	/** Percent chance a monster evolves (one champion tier up, full heal) the first time it drops below half HP. 0 = never. */
	protected int metamorphChancePct = 0;
	/** Stuns/holds a monster must take before the next ones can make it rage, in place of RageDisablesRequired. -1 = use the config value. */
	protected int rageDisablesOverride = -1;
	/** Drop multiplier for a monster killed while raging. */
	protected double rageKillDropMult = 1.0;
	/** Whether killing a raging monster grants a Luck stack. */
	protected boolean rageKillLuckStack = false;
	/** Multiplier on the chance that a kill adds a Luck stack (see LuckyLootManager). */
	protected double luckGainMult = 1.0;
	/** Whether dying inside only costs half the Luck stacks instead of all of them. */
	protected boolean luckDeathKeepsHalf = false;
	/** Multiplier on the jackpot chance. */
	protected double jackpotMult = 1.0;
	/** Multiplier on the roaming fake player spawn chance (see FakePlayerPvpManager). */
	protected double fakePvpSpawnMult = 1.0;
	/** When above 1.0, a roaming fake player killed inside always pays coins, this many times the normal amount. */
	protected double fakePlayerCoinMult = 1.0;
	/** Whether a roaming fake player killed inside drops a Sealed Cache. */
	protected boolean fakePlayerCache = false;
	/** Whether a monster inside is marked as a bounty every few minutes (see HotzoneModifierManager). */
	protected boolean bounty = false;
	/** Whether kills inside build Heat stacks (see HotzoneModifierManager). */
	protected boolean heat = false;
	/** Whether every monster inside is aggressive. */
	protected boolean forceAggressive = false;
	/** Multiplier on the aggro range of monsters inside. */
	protected double aggroRangeMult = 1.0;
	/** Multiplier on the Mage monster spawn chance (see MageMonsterManager). */
	protected double mageSpawnMult = 1.0;
	/** Whether the XP/SP of a kill depends on how many party members hunt inside together. */
	protected boolean kinship = false;
	/** Percent chance a slain monster splits into two weaker copies. 0 = never. */
	protected int splitChancePct = 0;
	/** Monster races that hit harder, by {@link #favoredRaceAtkMult}. Empty = none. */
	protected Set<Race> favoredRaces = Collections.emptySet();
	/** P.Atk/M.Atk multiplier of monsters of a {@link #favoredRaces} race, on top of {@link #monsterAtkMult}. */
	protected double favoredRaceAtkMult = 1.0;
	/** Whether this is a night omen (see {@code NightCycleManager}) that the hotzone rotation never rolls. */
	protected boolean nightOnly = false;

	HotzoneModifier(String description)
	{
		this.description = description;
	}

	public String getDescription()
	{
		return description;
	}

	public double getDropRateMult()
	{
		return dropRateMult;
	}

	public double getSpoilRateMult()
	{
		return spoilRateMult;
	}

	public double getXpSpMult()
	{
		return xpSpMult;
	}

	public double getMonsterAtkMult()
	{
		return monsterAtkMult;
	}

	public double getMonsterDefMult()
	{
		return monsterDefMult;
	}

	public double getMonsterSpdMult()
	{
		return monsterSpdMult;
	}

	public double getMonsterHpMult()
	{
		return monsterHpMult;
	}

	public double getChampionSpawnMult()
	{
		return championSpawnMult;
	}

	public int getExtraArchetypeSkills()
	{
		return extraArchetypeSkills;
	}

	public boolean isMonsterStunImmune()
	{
		return monsterStunImmune;
	}

	public double getPlayerHpDrainPctPerTick()
	{
		return playerHpDrainPctPerTick;
	}

	public double getMonsterMAtkMult()
	{
		return monsterMAtkMult;
	}

	public double getCoinMult()
	{
		return coinMult;
	}

	public double getAdenaMult()
	{
		return adenaMult;
	}

	public double getRespawnDelayMult()
	{
		return respawnDelayMult;
	}

	public double getMinibossKillsMult()
	{
		return minibossKillsMult;
	}

	public double getMinibossCoinMult()
	{
		return minibossCoinMult;
	}

	public int getKillHealPct()
	{
		return killHealPct;
	}

	public int getRiseChancePct()
	{
		return riseChancePct;
	}

	public boolean isKillStreak()
	{
		return killStreak;
	}

	public int getPlayerBuffSkillId()
	{
		return playerBuffSkillId;
	}

	public double getThiefSpawnMult()
	{
		return thiefSpawnMult;
	}

	public int getThiefKillWeight()
	{
		return thiefKillWeight;
	}

	public boolean isThiefFlees()
	{
		return thiefFlees;
	}

	public double getWaveSpawnMult()
	{
		return waveSpawnMult;
	}

	public int getExtraWaves()
	{
		return extraWaves;
	}

	public double getWaveCoinMult()
	{
		return waveCoinMult;
	}

	public int getMetamorphChancePct()
	{
		return metamorphChancePct;
	}

	public int getRageDisablesOverride()
	{
		return rageDisablesOverride;
	}

	public double getRageKillDropMult()
	{
		return rageKillDropMult;
	}

	public boolean isRageKillLuckStack()
	{
		return rageKillLuckStack;
	}

	public double getLuckGainMult()
	{
		return luckGainMult;
	}

	public boolean isLuckDeathKeepsHalf()
	{
		return luckDeathKeepsHalf;
	}

	public double getJackpotMult()
	{
		return jackpotMult;
	}

	public double getFakePvpSpawnMult()
	{
		return fakePvpSpawnMult;
	}

	public double getFakePlayerCoinMult()
	{
		return fakePlayerCoinMult;
	}

	public boolean isFakePlayerCache()
	{
		return fakePlayerCache;
	}

	public boolean isBounty()
	{
		return bounty;
	}

	public boolean isHeat()
	{
		return heat;
	}

	public boolean isForceAggressive()
	{
		return forceAggressive;
	}

	public double getAggroRangeMult()
	{
		return aggroRangeMult;
	}

	public double getMageSpawnMult()
	{
		return mageSpawnMult;
	}

	public boolean isKinship()
	{
		return kinship;
	}

	public int getSplitChancePct()
	{
		return splitChancePct;
	}

	/**
	 * @param race the monster's race
	 * @return the attack multiplier of a monster of {@code race}: {@link #favoredRaceAtkMult} for a favored race, 1.0 otherwise
	 */
	public double getRaceAtkMult(Race race)
	{
		return favoredRaces.contains(race) ? favoredRaceAtkMult : 1.0;
	}

	public boolean isNightOnly()
	{
		return nightOnly;
	}
}
