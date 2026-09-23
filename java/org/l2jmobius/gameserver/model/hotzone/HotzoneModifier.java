package org.l2jmobius.gameserver.model.hotzone;

/**
 * A rotating hotzone modifier: one of these is rolled fresh for each hotzone every time {@code custom.RotatingHotZones} rotates (see {@link org.l2jmobius.gameserver.managers.HotzoneModifierManager}), and stays active for that zone until the next rotation.
 * <p>
 * Every field defaults to "no effect" (1.0 for a multiplier, 0/false otherwise), so a modifier only needs to set the handful of fields it actually cares about. Adding a new modifier is just adding a new enum constant - every read site (Monster's stat/reward overrides, the champion spawn
 * roll in Spawn, the archetype skill grant in AttackableAI, the stun-immunity check in the Stun effect) already knows how to consult these generic knobs, so nothing else needs to change.
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
	};

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
}
