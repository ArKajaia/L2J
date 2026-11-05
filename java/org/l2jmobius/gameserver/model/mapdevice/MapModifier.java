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
package org.l2jmobius.gameserver.model.mapdevice;

import org.l2jmobius.gameserver.config.custom.ChampionMonstersConfig;
import org.l2jmobius.gameserver.config.custom.InfusedMonsterConfig;
import org.l2jmobius.gameserver.config.custom.MageMonsterConfig;
import org.l2jmobius.gameserver.config.custom.ThiefMonsterConfig;
import org.l2jmobius.gameserver.config.custom.WaveChallengeConfig;
import org.l2jmobius.gameserver.model.actor.enums.npc.MonsterArchetype;

/**
 * A difficulty modifier of a map run (see {@link org.l2jmobius.gameserver.managers.MapDeviceManager}): every Champion Medal fed to the Map Device rolls one of these, never twice the same, and every monster of the map carries all of them.
 * <p>
 * The first half only changes numbers, on the monsters or on the players inside. The second half changes how the map plays: what kind of monsters it holds and how many, how they fight, what happens when they die, and what the map itself does to the players.
 * <p>
 * Every field defaults to "no effect" (1.0 for a multiplier, 0/false otherwise), so a modifier only sets what it changes. Every read site lives in {@code MapDeviceManager}, {@link MapRun} and the hooks they feed ({@code Monster}, {@code Totems.getLeechRate}, {@code ItemSkillsTemplate}).
 * @author Mobius
 */
public enum MapModifier
{
	// =========================================================================
	// Numbers: the monsters
	// =========================================================================
	BRUTAL("Brutal", "Monsters deal 25% more physical damage.")
	{
		{
			monsterPAtk = 1.25;
		}
	},
	EMPOWERED("Empowered", "Monsters deal 25% more magic damage.")
	{
		{
			monsterMAtk = 1.25;
		}
	},
	ARMOURED("Armoured", "Monsters have 30% more P. Def.")
	{
		{
			monsterPDef = 1.30;
		}
	},
	WARDED("Warded", "Monsters have 30% more M. Def.")
	{
		{
			monsterMDef = 1.30;
		}
	},
	FORTIFIED("Fortified", "Monsters have 40% more HP.")
	{
		{
			monsterHp = 1.40;
		}
	},
	FLEET("Fleet", "Monsters move 25% faster.")
	{
		{
			monsterMoveSpeed = 1.25;
		}
	},
	FRENZIED("Frenzied", "Monsters attack 20% faster.")
	{
		{
			monsterAtkSpd = 1.20;
		}
	},
	QUICKENED("Quickened", "Monsters cast spells 30% faster.")
	{
		{
			monsterCastSpd = 1.30;
		}
	},
	DEADLY("Deadly", "Monsters land critical hits 50% more often, for 30% more damage.")
	{
		{
			monsterCritRate = 1.50;
			monsterCritDamage = 1.30;
		}
	},
	ELUSIVE("Elusive", "Monsters have +8 evasion.")
	{
		{
			monsterEvasion = 8;
		}
	},
	VAMPIRIC("Vampiric", "Monsters heal for 15% of the melee damage they deal.")
	{
		{
			monsterLeechPct = 15;
		}
	},
	ELEMENTAL_AEGIS("Elemental Aegis", "Monsters have +80 to every attribute resistance.")
	{
		{
			monsterElementRes = 80;
		}
	},
	SPIKED("Spiked", "Monsters reflect 8% of the melee damage they take.")
	{
		{
			monsterReflectPct = 8;
		}
	},
	REGENERATIVE("Regenerative", "Monsters regenerate HP five times as fast.")
	{
		{
			monsterHpRegen = 5.0;
		}
	},

	// =========================================================================
	// Numbers: the players inside
	// =========================================================================
	ENFEEBLED("Enfeebled", "Players have 20% less max HP.")
	{
		{
			playerMaxHp = 0.80;
		}
	},
	EXPOSED("Exposed", "Players have 15% less P. Def. and M. Def.")
	{
		{
			playerDef = 0.85;
		}
	},
	MANA_STARVED("Mana Starved", "Players regenerate MP 50% slower.")
	{
		{
			playerMpRegen = 0.50;
		}
	},
	WITHERED("Withered", "Players regenerate HP 60% slower.")
	{
		{
			playerHpRegen = 0.40;
		}
	},
	TAXING("Taxing", "Players' skills cost 40% more MP.")
	{
		{
			playerMpCost = 1.40;
		}
	},
	SLUGGISH("Sluggish", "Players move 15% slower.")
	{
		{
			playerMoveSpeed = 0.85;
		}
	},
	LETHARGIC("Lethargic", "Players' skills take 25% longer to come back.")
	{
		{
			playerReuse = 1.25;
		}
	},
	ELEMENTAL_WEAKNESS("Elemental Weakness", "Players have -50 to every attribute resistance.")
	{
		{
			playerElementRes = -50;
		}
	},
	BLURRED_VISION("Blurred Vision", "Players have -6 accuracy and land critical hits 30% less often.")
	{
		{
			playerAccuracy = -6;
			playerCritRate = 0.70;
		}
	},
	FRAGILE("Fragile", "Players take 30% more damage from critical hits.")
	{
		{
			playerCritDamageTaken = 1.30;
		}
	},
	DESECRATED("Desecrated", "Healing received by players is halved.")
	{
		{
			playerHealReceived = 0.50;
		}
	},

	// =========================================================================
	// How the map plays
	// =========================================================================
	COVEN("Coven", "Mage infestation: 40% of the monsters are Mages that cast without pause and keep their distance.")
	{
		{
			magePct = 40;
		}

		@Override
		public boolean isAvailable()
		{
			return MageMonsterConfig.ENABLED;
		}
	},
	CHAMPIONS_RALLY("Champions' Rally", "30% of the monsters are Champions, some of them of the highest tier.")
	{
		{
			championPct = 30;
		}

		@Override
		public boolean isAvailable()
		{
			return ChampionMonstersConfig.CHAMPION_ENABLE;
		}
	},
	BLOODLESS("Bloodless", "The monsters have no blood: you can't leech life or mana from them.")
	{
		{
			noLeech = true;
		}
	},
	HAIL_OF_ARROWS("Hail of Arrows", "Every monster fights from range and backs away when you close in.")
	{
		{
			allRanged = true;
		}
	},
	THIEVES_GUILD("Thieves' Guild", "25% of the monsters are Thieves that loot the fallen and run once their bag is full.")
	{
		{
			thiefPct = 25;
		}

		@Override
		public boolean isAvailable()
		{
			return ThiefMonsterConfig.ENABLED;
		}
	},
	ELEMENTAL_CHAOS("Elemental Chaos", "40% of the monsters are Infused with a random attribute.")
	{
		{
			infusedPct = 40;
		}

		@Override
		public boolean isAvailable()
		{
			return InfusedMonsterConfig.ENABLED;
		}
	},
	GAUNTLET("Gauntlet", "8 monsters are wave challenges: they rise twice more, stronger each time.")
	{
		{
			waveMonsters = 8;
		}

		@Override
		public boolean isAvailable()
		{
			return WaveChallengeConfig.ENABLED;
		}
	},
	HORDE("Horde", "The map holds 30% more monsters.")
	{
		{
			packSize = 1.30;
		}
	},
	PACK_HUNTERS("Pack Hunters", "The monsters gather in tight packs of 6 to 10.")
	{
		{
			packs = true;
		}
	},
	TITANS("Titans", "Half as many monsters, but giants: triple HP, 30% more damage, double experience and drops.")
	{
		{
			packSize = 0.50;
			titans = true;
		}
	},
	VOLATILE("Volatile", "Slain monsters explode a moment later, hitting players nearby for 10% of their max HP.")
	{
		{
			volatileDeath = true;
		}
	},
	UNDYING_LEGION("Undying Legion", "25% of the slain monsters rise once more at half HP.")
	{
		{
			risePct = 25;
		}
	},
	SOUL_EATERS("Soul Eaters", "When a monster dies, the monsters nearby devour its soul: they heal 20% and hit 10% harder (up to 5 times).")
	{
		{
			soulEaters = true;
		}
	},
	RISING_DREAD("Rising Dread", "Every 10 monsters slain make the survivors 5% stronger, up to 50%.")
	{
		{
			risingDread = true;
		}
	},
	BERSERKERS("Berserkers", "Below 35% HP, monsters deal 50% more damage and attack 30% faster.")
	{
		{
			berserkers = true;
		}
	},
	HUNTERS("Hunters", "Every monster is aggressive and notices you from 50% farther.")
	{
		{
			aggressive = true;
		}
	},
	SKITTISH("Skittish", "The monsters run when they're hurt, and lead you into their friends.")
	{
		{
			archetype = MonsterArchetype.COWARD;
			archetypePct = 100;
		}
	},
	WAR_PRIESTS("War Priests", "30% of the monsters are supporters that heal and empower their allies.")
	{
		{
			archetype = MonsterArchetype.SUPPORTER;
			archetypePct = 30;
		}
	},
	AMBUSH("Ambush", "Once half the map is cleared, 20 monsters ambush one of you.")
	{
		{
			ambush = true;
		}
	},
	MAP_GUARDIAN("Map Guardian", "A Guardian holds the middle of the map: a tier 3 Champion that rises twice. It drops two more maps.")
	{
		{
			guardian = true;
		}
	},
	STORM_CALLER("Storm Caller", "Every 20 seconds lightning strikes one of you for 12% of max HP.")
	{
		{
			storm = true;
		}
	},
	BURNING_GROUND("Burning Ground", "The ground burns: players lose 1.5% of max HP every 3 seconds.")
	{
		{
			burningGround = true;
		}
	},
	FLASKLESS("Flaskless", "Potions and elixirs can't be used.")
	{
		{
			flaskless = true;
		}
	},
	FRAGILE_PORTALS("Fragile Portals", "The Map Device opens only 4 portals.")
	{
		{
			portalLimit = 4;
		}
	},
	HOURGLASS("Hourglass", "The map collapses after 15 minutes.")
	{
		{
			timeLimitMinutes = 15;
		}
	};

	/** How many of these only change numbers: the first ones of the enum. The rest change how the map plays. */
	public static final int STAT_MODIFIER_COUNT = 25;

	private final String _displayName;
	private final String _description;

	// The monsters.
	protected double monsterPAtk = 1.0;
	protected double monsterMAtk = 1.0;
	protected double monsterPDef = 1.0;
	protected double monsterMDef = 1.0;
	protected double monsterHp = 1.0;
	protected double monsterMoveSpeed = 1.0;
	protected double monsterAtkSpd = 1.0;
	protected double monsterCastSpd = 1.0;
	protected double monsterCritRate = 1.0;
	protected double monsterCritDamage = 1.0;
	protected double monsterEvasion = 0;
	protected double monsterLeechPct = 0;
	protected double monsterElementRes = 0;
	protected double monsterReflectPct = 0;
	protected double monsterHpRegen = 1.0;

	// The players inside.
	protected double playerMaxHp = 1.0;
	protected double playerDef = 1.0;
	protected double playerMpRegen = 1.0;
	protected double playerHpRegen = 1.0;
	protected double playerMpCost = 1.0;
	protected double playerMoveSpeed = 1.0;
	protected double playerReuse = 1.0;
	protected double playerElementRes = 0;
	protected double playerAccuracy = 0;
	protected double playerCritRate = 1.0;
	protected double playerCritDamageTaken = 1.0;
	protected double playerHealReceived = 1.0;

	// What the map holds.
	/** Percent of the monsters made Mages. */
	protected int magePct = 0;
	/** Percent of the monsters made Champions. */
	protected int championPct = 0;
	/** Percent of the monsters made Thieves. */
	protected int thiefPct = 0;
	/** Percent of the monsters Infused with an attribute. */
	protected int infusedPct = 0;
	/** How many monsters are made wave challenges. */
	protected int waveMonsters = 0;
	/** Multiplier on the number of monsters. */
	protected double packSize = 1.0;
	/** Whether the monsters stand in packs instead of alone. */
	protected boolean packs = false;
	/** Whether the monsters are giants (more HP and damage, more rewards). */
	protected boolean titans = false;
	/** A combat personality forced on {@link #archetypePct}% of the monsters, {@code null} for none. */
	protected MonsterArchetype archetype = null;
	protected int archetypePct = 0;

	// How they fight and die.
	protected boolean noLeech = false;
	protected boolean allRanged = false;
	protected boolean aggressive = false;
	protected boolean berserkers = false;
	protected boolean volatileDeath = false;
	/** Percent chance a slain monster rises again. */
	protected int risePct = 0;
	protected boolean soulEaters = false;
	protected boolean risingDread = false;
	protected boolean ambush = false;
	protected boolean guardian = false;

	// What the map does.
	protected boolean storm = false;
	protected boolean burningGround = false;
	protected boolean flaskless = false;
	/** How many portals the device opens at most, 0 for no limit. */
	protected int portalLimit = 0;
	/** How long the map stays open at most, 0 for the configured time. */
	protected int timeLimitMinutes = 0;

	MapModifier(String displayName, String description)
	{
		_displayName = displayName;
		_description = description;
	}

	/**
	 * @return its name, as shown to the players
	 */
	public String getDisplayName()
	{
		return _displayName;
	}

	/**
	 * @return what it does, as shown to the players
	 */
	public String getDescription()
	{
		return _description;
	}

	/**
	 * @return {@code true} if it only changes numbers, {@code false} if it changes how the map plays
	 */
	public boolean isStatModifier()
	{
		return ordinal() < STAT_MODIFIER_COUNT;
	}

	/**
	 * A modifier that draws a kind of monster the server has switched off would do nothing: it is never rolled.
	 * @return {@code true} if it may be rolled
	 */
	public boolean isAvailable()
	{
		return true;
	}

	public double getMonsterPAtk()
	{
		return monsterPAtk;
	}

	public double getMonsterMAtk()
	{
		return monsterMAtk;
	}

	public double getMonsterPDef()
	{
		return monsterPDef;
	}

	public double getMonsterMDef()
	{
		return monsterMDef;
	}

	public double getMonsterHp()
	{
		return monsterHp;
	}

	public double getMonsterMoveSpeed()
	{
		return monsterMoveSpeed;
	}

	public double getMonsterAtkSpd()
	{
		return monsterAtkSpd;
	}

	public double getMonsterCastSpd()
	{
		return monsterCastSpd;
	}

	public double getMonsterCritRate()
	{
		return monsterCritRate;
	}

	public double getMonsterCritDamage()
	{
		return monsterCritDamage;
	}

	public double getMonsterEvasion()
	{
		return monsterEvasion;
	}

	public double getMonsterLeechPct()
	{
		return monsterLeechPct;
	}

	public double getMonsterElementRes()
	{
		return monsterElementRes;
	}

	public double getMonsterReflectPct()
	{
		return monsterReflectPct;
	}

	public double getMonsterHpRegen()
	{
		return monsterHpRegen;
	}

	public double getPlayerMaxHp()
	{
		return playerMaxHp;
	}

	public double getPlayerDef()
	{
		return playerDef;
	}

	public double getPlayerMpRegen()
	{
		return playerMpRegen;
	}

	public double getPlayerHpRegen()
	{
		return playerHpRegen;
	}

	public double getPlayerMpCost()
	{
		return playerMpCost;
	}

	public double getPlayerMoveSpeed()
	{
		return playerMoveSpeed;
	}

	public double getPlayerReuse()
	{
		return playerReuse;
	}

	public double getPlayerElementRes()
	{
		return playerElementRes;
	}

	public double getPlayerAccuracy()
	{
		return playerAccuracy;
	}

	public double getPlayerCritRate()
	{
		return playerCritRate;
	}

	public double getPlayerCritDamageTaken()
	{
		return playerCritDamageTaken;
	}

	public double getPlayerHealReceived()
	{
		return playerHealReceived;
	}

	public int getMagePct()
	{
		return magePct;
	}

	public int getChampionPct()
	{
		return championPct;
	}

	public int getThiefPct()
	{
		return thiefPct;
	}

	public int getInfusedPct()
	{
		return infusedPct;
	}

	public int getWaveMonsters()
	{
		return waveMonsters;
	}

	public double getPackSize()
	{
		return packSize;
	}

	public boolean isPacks()
	{
		return packs;
	}

	public boolean isTitans()
	{
		return titans;
	}

	public MonsterArchetype getArchetype()
	{
		return archetype;
	}

	public int getArchetypePct()
	{
		return archetypePct;
	}

	public boolean isNoLeech()
	{
		return noLeech;
	}

	public boolean isAllRanged()
	{
		return allRanged;
	}

	public boolean isAggressive()
	{
		return aggressive;
	}

	public boolean isBerserkers()
	{
		return berserkers;
	}

	public boolean isVolatileDeath()
	{
		return volatileDeath;
	}

	public int getRisePct()
	{
		return risePct;
	}

	public boolean isSoulEaters()
	{
		return soulEaters;
	}

	public boolean isRisingDread()
	{
		return risingDread;
	}

	public boolean isAmbush()
	{
		return ambush;
	}

	public boolean isGuardian()
	{
		return guardian;
	}

	public boolean isStorm()
	{
		return storm;
	}

	public boolean isBurningGround()
	{
		return burningGround;
	}

	public boolean isFlaskless()
	{
		return flaskless;
	}

	public int getPortalLimit()
	{
		return portalLimit;
	}

	public int getTimeLimitMinutes()
	{
		return timeLimitMinutes;
	}
}
