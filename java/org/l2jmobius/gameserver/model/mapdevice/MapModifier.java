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
 * The first {@value #STAT_MODIFIER_COUNT} only change numbers, on the monsters or on the players inside. The rest change how the map plays: what kind of monsters it holds and how many, how they fight, what happens when they die, and what the map itself does to the players.
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
	ARCANE_PRECISION("Arcane Precision", "Monsters land magic critical hits twice as often.")
	{
		{
			monsterMCritRate = 2.0;
		}
	},
	UNSHAKABLE("Unshakable", "Monsters resist stun, sleep, root and fear 50% better.")
	{
		{
			monsterControlChance = 0.50;
		}
	},
	HEXPROOF("Hexproof", "Debuffs land on monsters 40% less often.")
	{
		{
			monsterDebuffChance = 0.60;
		}
	},
	SHIELDBEARERS("Shieldbearers", "Monsters block 25% of attacks with a shield.")
	{
		{
			monsterShieldPct = 25;
		}
	},
	THICK_SKINNED("Thick-Skinned", "Monsters take 30% less damage from physical critical hits.")
	{
		{
			monsterCritDamageTaken = 0.70;
		}
	},
	SPELL_WARDED("Spell-Warded", "Monsters take 25% less damage from skills.")
	{
		{
			monsterSkillDamageTaken = 0.75;
		}
	},
	WEAKENED_WILL("Weakened Will", "Players' debuffs land 40% less often.")
	{
		{
			playerDebuffChance = 0.60;
		}
	},
	GLASS_CANNON("Glass Cannon", "Players deal 20% more damage, but monsters deal 25% more.")
	{
		{
			playerDamage = 1.20;
			monsterPAtk = 1.25;
			monsterMAtk = 1.25;
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
	MANA_SIPHON("Mana Siphon", "Monster attacks burn 2% of your max MP.")
	{
		{
			manaSiphonPct = 2;
		}
	},
	VENOMOUS("Venomous", "Monster attacks have a 10% chance to poison you.")
	{
		{
			venomPct = 10;
		}
	},
	LACERATING("Lacerating", "Monster critical hits make you bleed.")
	{
		{
			lacerating = true;
		}
	},
	LINKED_SOULS("Linked Souls", "Monsters come in pairs that share the damage they take, while they stand near each other.")
	{
		{
			linkedSouls = true;
		}
	},
	SUMMONERS("Summoners", "20% of the monsters call 2 minions when the fight starts.")
	{
		{
			summonerPct = 20;
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
	SPLITTERS("Splitters", "A slain monster splits into two copies with half its HP, once.")
	{
		{
			splitters = true;
		}
	},
	DEATH_CURSE("Death Curse", "A slain monster curses its killer: 5% weaker for 10 seconds, stacking up to 6 times.")
	{
		{
			deathCurse = true;
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
	BOUNTY_MARK("Bounty Mark", "One monster at a time is marked: kill it within 2 minutes for triple drops.")
	{
		{
			bounty = true;
		}
	},
	MAP_GUARDIAN("Map Guardian", "A Guardian holds the middle of the map: a tier 3 Champion that rises twice. It drops a bonus map.")
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
	},
	SPEEDRUN("Speedrun", "Clear the map within 10 minutes for a bonus map.")
	{
		{
			speedrunMinutes = 10;
		}
	},

	// =========================================================================
	// Big moments: they pay off a fast clear, each at a price
	// =========================================================================
	CHAIN_REACTION("Chain Reaction", "Slain monsters burst: monsters within 300 take 20% of their max HP, players within 300 take 4% of theirs.")
	{
		{
			chainReaction = true;
		}
	},
	CULLING_STRIKE("Culling Strike", "A monster below 12% HP dies from any hit you land. Monsters deal 10% more damage.")
	{
		{
			cullingStrike = true;
			monsterPAtk = 1.10;
			monsterMAtk = 1.10;
		}
	},
	RISING_STAKES("Rising Stakes", "Every 10 kills: +4% experience and drops (up to +60%), but monsters deal 2% more damage (up to +30%).")
	{
		{
			risingStakes = true;
		}
	},
	RAMPAGE("Rampage", "Every 25 kills: 15 seconds of double damage and +50% run speed for everyone inside. Monsters have 20% more HP.")
	{
		{
			rampage = true;
			monsterHp = 1.20;
		}
	},
	MOMENTUM("Momentum", "Each kill within 8 seconds of the last: +4% run speed and +3% attack and cast speed, up to 10 stacks. Monsters attack 10% faster.")
	{
		{
			momentum = true;
			monsterAtkSpd = 1.10;
		}
	},

	// =========================================================================
	// Less walking, less searching
	// =========================================================================
	CARTOGRAPHERS_LENS("Cartographer's Lens", "Your radar works from the start and points at the best pack nearby. Aggressive monsters notice you from 25% farther.")
	{
		{
			lens = true;
		}
	},
	GRAVITY_WELL("Gravity Well", "Hitting a monster pulls up to 6 monsters within 600 to its side, and they join the fight.")
	{
		{
			gravityWell = true;
		}
	},
	WAYSTONES("Waystones", "4 waystones stand on the field: touch one to travel to another or to the entry. Monsters move 15% faster.")
	{
		{
			waystones = true;
			monsterMoveSpeed = 1.15;
		}
	},
	NESTS("Nests", "The monsters stand in 6 nests round a Nest Heart. While its heart stands a nest takes 20% less damage; break the heart and the nest frenzies (+30% damage) but drops double.")
	{
		{
			nests = true;
		}
	},
	SEALED_COFFERS("Sealed Coffers", "3 Sealed Coffers stand on the field. Breaking a seal calls two waves of guards, the second led by a Champion; then the coffer pays Champion Medals, Gemstones and maybe a map.")
	{
		{
			coffers = true;
		}
	};

	/** How many of these only change numbers: the first ones of the enum. The rest change how the map plays. */
	public static final int STAT_MODIFIER_COUNT = 33;

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
	protected double monsterMCritRate = 1.0;
	protected double monsterControlChance = 1.0;
	protected double monsterDebuffChance = 1.0;
	protected int monsterShieldPct = 0;
	protected double monsterCritDamageTaken = 1.0;
	protected double monsterSkillDamageTaken = 1.0;

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
	protected double playerDebuffChance = 1.0;
	protected double playerDamage = 1.0;

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
	protected int manaSiphonPct = 0;
	protected int venomPct = 0;
	protected boolean lacerating = false;
	protected boolean linkedSouls = false;
	protected int summonerPct = 0;
	protected boolean splitters = false;
	protected boolean deathCurse = false;
	protected boolean bounty = false;

	// What the map does.
	protected boolean storm = false;
	protected boolean burningGround = false;
	protected boolean flaskless = false;
	/** How many portals the device opens at most, 0 for no limit. */
	protected int portalLimit = 0;
	/** How long the map stays open at most, 0 for the configured time. */
	protected int timeLimitMinutes = 0;
	protected int speedrunMinutes = 0;

	// Big moments.
	protected boolean chainReaction = false;
	protected boolean cullingStrike = false;
	protected boolean risingStakes = false;
	protected boolean rampage = false;
	protected boolean momentum = false;

	// Less walking.
	protected boolean lens = false;
	protected boolean gravityWell = false;
	protected boolean waystones = false;
	protected boolean nests = false;
	protected boolean coffers = false;

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

	public double getMonsterMCritRate()
	{
		return monsterMCritRate;
	}

	public double getMonsterControlChance()
	{
		return monsterControlChance;
	}

	public double getMonsterDebuffChance()
	{
		return monsterDebuffChance;
	}

	public int getMonsterShieldPct()
	{
		return monsterShieldPct;
	}

	public double getMonsterCritDamageTaken()
	{
		return monsterCritDamageTaken;
	}

	public double getMonsterSkillDamageTaken()
	{
		return monsterSkillDamageTaken;
	}

	public double getPlayerDebuffChance()
	{
		return playerDebuffChance;
	}

	public double getPlayerDamage()
	{
		return playerDamage;
	}

	public int getManaSiphonPct()
	{
		return manaSiphonPct;
	}

	public int getVenomPct()
	{
		return venomPct;
	}

	public boolean isLacerating()
	{
		return lacerating;
	}

	public boolean isLinkedSouls()
	{
		return linkedSouls;
	}

	public int getSummonerPct()
	{
		return summonerPct;
	}

	public boolean isSplitters()
	{
		return splitters;
	}

	public boolean isDeathCurse()
	{
		return deathCurse;
	}

	public boolean isBounty()
	{
		return bounty;
	}

	public int getSpeedrunMinutes()
	{
		return speedrunMinutes;
	}

	public boolean isChainReaction()
	{
		return chainReaction;
	}

	public boolean isCullingStrike()
	{
		return cullingStrike;
	}

	public boolean isRisingStakes()
	{
		return risingStakes;
	}

	public boolean isRampage()
	{
		return rampage;
	}

	public boolean isMomentum()
	{
		return momentum;
	}

	public boolean isLens()
	{
		return lens;
	}

	public boolean isGravityWell()
	{
		return gravityWell;
	}

	public boolean isWaystones()
	{
		return waystones;
	}

	public boolean isNests()
	{
		return nests;
	}

	public boolean isCoffers()
	{
		return coffers;
	}
}
