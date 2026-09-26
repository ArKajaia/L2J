package org.l2jmobius.gameserver.model.hotzone;

/**
 * A rotating hotzone modifier: one of these is rolled fresh for each hotzone every time {@code custom.RotatingHotZones} rotates (see {@link org.l2jmobius.gameserver.managers.HotzoneModifierManager}), and stays active for that zone until the next rotation.
 * <p>
 * Every field defaults to "no effect" (1.0 for a multiplier, 0/false otherwise), so a modifier only needs to set the handful of fields it actually cares about. Adding a new modifier is just adding a new enum constant - every read site (Monster's stat/reward overrides, the champion spawn
 * roll in Spawn, the archetype skill grant in AttackableAI, the stun-immunity check in the Stun effect, the kill hook in HotzoneModifierManager, the modifier buffs in RotatingHotZones) already knows how to consult these generic knobs, so nothing else needs to change.
 * <p>
 * Deliberately a mix of purely positive, purely negative, and risk/reward modifiers, matching how the base game's own hotzone system already trades a combat bonus for being flagged PvP-able.
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
	};

	/** Stacking XP/SP buff (levels 1-{@link #KILL_STREAK_MAX_LEVEL}) re-applied one level higher on every kill under {@link #KILL_STREAK}; its 15s abnormal time is the chain window. */
	public static final int KILL_STREAK_SKILL_ID = 90002;
	public static final int KILL_STREAK_MAX_LEVEL = 10;
	/** Player buff held while inside a {@link #GLASS_CANNON} zone. */
	public static final int GLASS_CANNON_SKILL_ID = 90003;
	/** Player buff held while inside an {@link #ARCANE_SURGE} zone. */
	public static final int ARCANE_SURGE_SKILL_ID = 90004;

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
}
