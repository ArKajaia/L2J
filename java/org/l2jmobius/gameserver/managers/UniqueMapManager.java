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

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.l2jmobius.commons.threads.ThreadPool;
import org.l2jmobius.commons.util.Rnd;
import org.l2jmobius.gameserver.ai.AttackableAI;
import org.l2jmobius.gameserver.ai.Intention;
import org.l2jmobius.gameserver.config.RatesConfig;
import org.l2jmobius.gameserver.config.custom.ChampionMonstersConfig;
import org.l2jmobius.gameserver.config.custom.MapDeviceConfig;
import org.l2jmobius.gameserver.config.custom.TeamVsTeamConfig;
import org.l2jmobius.gameserver.config.custom.ThiefMonsterConfig;
import org.l2jmobius.gameserver.data.xml.ItemData;
import org.l2jmobius.gameserver.data.xml.MapDeviceData;
import org.l2jmobius.gameserver.data.xml.NpcData;
import org.l2jmobius.gameserver.model.Location;
import org.l2jmobius.gameserver.model.World;
import org.l2jmobius.gameserver.model.WorldObject;
import org.l2jmobius.gameserver.model.actor.Attackable;
import org.l2jmobius.gameserver.model.actor.Creature;
import org.l2jmobius.gameserver.model.actor.Npc;
import org.l2jmobius.gameserver.model.actor.Player;
import org.l2jmobius.gameserver.model.actor.enums.npc.MonsterArchetype;
import org.l2jmobius.gameserver.model.actor.holders.npc.AggroInfo;
import org.l2jmobius.gameserver.model.actor.instance.Monster;
import org.l2jmobius.gameserver.model.actor.templates.NpcTemplate;
import org.l2jmobius.gameserver.model.item.holders.ItemHolder;
import org.l2jmobius.gameserver.model.mapdevice.MapArena;
import org.l2jmobius.gameserver.model.mapdevice.MapRun;
import org.l2jmobius.gameserver.model.mapdevice.MapRun.MonsterStat;
import org.l2jmobius.gameserver.model.mapdevice.MapRun.Status;
import org.l2jmobius.gameserver.model.mapdevice.UniqueMap;
import org.l2jmobius.gameserver.model.script.Quest;
import org.l2jmobius.gameserver.model.skill.AbnormalVisualEffect;
import org.l2jmobius.gameserver.network.serverpackets.ExShowScreenMessage;
import org.l2jmobius.gameserver.network.serverpackets.MagicSkillUse;

/**
 * The unique maps of the Map Device (data/MapDevice.xml {@code <uniqueMap>}, {@link UniqueMap}): their twists while the field is cleared, their final encounters once it is, and their drops. {@link MapDeviceManager} opens them like any map and calls in here at its hooks
 * (spawning, damage, deaths, the field cleared, the run's clock, players coming and going, the run closing).
 * Unique maps only drop inside maps: on completing a plain map of tier {@link MapDeviceConfig#UNIQUE_MAP_MIN_TIER} and up, and from the unique bosses.
 * <ul>
 * <li><b>The Crimson Coliseum</b> ({@link UniqueMap.Type#COLISEUM}): Deathmatch: Mayhem against monsters. Every kill (or assist) offers the killer a strong passive, stacked on its own tree until the map closes ({@link PassiveDeathmatchManager#joinMap}). Its 30 monsters come in
 * waves of {@link #COLISEUM_WAVES}; then the Crimson Champion, who roars at 75/50/25% of its HP (+10% attack each time) and calls 3 Challengers.</li>
 * <li><b>The Gilded Vault</b> ({@link UniqueMap.Type#VAULT}): Borrowed Time (5 minutes, +12 s per kill, +60 s per bounty) and Greed (+1% drop chance per kill, up to +100%); then the Gilded Mimic, a coward that spills coins when struck and vanishes elsewhere at
 * 80/60/40/20% of its HP, leaving thieves behind. If the vault seals first, it escapes.</li>
 * <li><b>The Ossuary of Echoes</b> ({@link UniqueMap.Type#OSSUARY}): a quarter of the slain leave an Echo (half HP, uncounted, its own drops); every Echo still standing at the end feeds the Hollow King (+5% HP). He is shielded while his Bone Phylacteries stand (4, then 3
 * at 60% HP, then 2 at 30%), which raise servants; from 30% a Death Nova bursts from him every 15 seconds.</li>
 * <li><b>The Gambler's Den</b> ({@link UniqueMap.Type#DEN}): every {@link #DEN_KILLS_PER_SPIN} kills the Wheel of Fate turns up one of 24 {@link Fortune}s (a shuffled bag: no repeat until all have come) and a bet; at the Dealer a bet doubles or halves the Pot, which
 * multiplies the Den's drops (past 100% they grow bigger). The Den's drops follow the server rates. Then The House, who draws a {@link Card} at 80/60/40/20% of its HP.</li>
 * </ul>
 * @author Mobius
 */
public class UniqueMapManager
{
	private static final Logger LOGGER = Logger.getLogger(UniqueMapManager.class.getName());

	/** What a monster of a unique map's twist or final encounter is (an int of {@code ROLE_*}); a plain monster of the map has none. */
	public static final String ENCOUNTER_VAR = "UNIQUE_MAP_ROLE";
	private static final int ROLE_BOSS = 1;
	private static final int ROLE_CHALLENGER = 2;
	private static final int ROLE_PHYLACTERY = 3;
	private static final int ROLE_SERVANT = 4;
	private static final int ROLE_ECHO = 5;
	private static final int ROLE_THIEF = 6;
	private static final int ROLE_JACKPOT = 7;
	private static final int ROLE_BLUFF = 8;
	private static final int ROLE_CARD_GUARD = 9;
	private static final int ROLE_PICKPOCKET = 10;

	// The Crimson Coliseum.
	private static final int[] COLISEUM_WAVES =
	{
		6,
		10,
		14
	};
	private static final double[] COLISEUM_THRESHOLDS =
	{
		0.75,
		0.50,
		0.25
	};
	private static final int CHALLENGERS = 3;
	private static final double CHALLENGER_HP = 2.0;
	private static final double ROAR_ATTACK = 0.10;
	/** How far into the field (share of its radius) the waves enter. */
	private static final double WAVE_SPREAD = 0.35;
	private static final int WAVE_SPACING = 300;

	// The Gilded Vault.
	private static final int VAULT_START_SECONDS = 300;
	private static final int VAULT_SECONDS_PER_KILL = 12;
	private static final int VAULT_SECONDS_PER_BOUNTY = 60;
	private static final int VAULT_FINALE_MIN_SECONDS = 120;
	private static final int GREED_MAX = 100;
	private static final double[] VAULT_THRESHOLDS =
	{
		0.80,
		0.60,
		0.40,
		0.20
	};
	private static final int VAULT_THIEVES = 4;
	private static final int SPILL_CHANCE = 8;
	private static final long SPILL_COOLDOWN = 1000;
	private static final int VANISH_SKILL_ID = 4671; // Teleport visual (NPC)

	// The Ossuary of Echoes.
	private static final int ECHO_CHANCE = 25;
	private static final int ECHO_MAX = 25;
	private static final double ECHO_HP = 0.5;
	private static final double ECHO_SPEED = 1.3;
	private static final double ECHO_ABSORB = 0.05;
	private static final double ECHO_ABSORB_MAX = 1.0;
	private static final int PHYLACTERY_NPC_ID = 18667; // Ward of Death
	private static final double PHYLACTERY_HP = 12;
	private static final double[] OSSUARY_THRESHOLDS =
	{
		0.60,
		0.30
	};
	private static final int[] PHYLACTERIES =
	{
		4,
		3,
		2
	};
	private static final double PHYLACTERY_SPREAD = 0.55;
	private static final int SERVANT_INTERVAL = 20;
	private static final int SERVANT_MAX = 10;
	private static final double SERVANT_HP = 0.5;
	private static final int NOVA_INTERVAL = 15;
	private static final int NOVA_RANGE = 900;
	private static final double NOVA_DAMAGE = 0.12;
	private static final int NOVA_SKILL_ID = 4529; // Quest - Summoned Explosion (visual only)
	private static final int RAISE_SKILL_ID = 4526; // Quest - BOSS Summon (visual only)

	// The Gambler's Den.
	private static final int DEN_KILLS_PER_SPIN = 12;
	private static final double POT_START = 1.0;
	private static final double POT_MIN = 0.25;
	private static final double POT_MAX = 4.0;
	private static final double[] DEN_THRESHOLDS =
	{
		0.80,
		0.60,
		0.40,
		0.20
	};
	private static final int JACKPOT_NPC_ID = 18822; // Mutant Treasure Chest
	private static final double JACKPOT_HP = 3;
	private static final int JACKPOT_SECONDS = 30;
	private static final int JACKPOT_ROLLS = 3;
	private static final int STAKES_CHAMPIONS = 6;
	private static final int PICKPOCKET_COUNT = 3;
	private static final int PICKPOCKET_SECONDS = 45;
	private static final double PICKPOCKET_POT = 0.05;
	private static final int LUCKY_SEVEN = 7;
	private static final double CARD_COUNTER_POT = 0.02;
	private static final int PULSE_INTERVAL = 5;
	private static final double COMPED_HEAL = 0.05;
	private static final double HANGOVER_DAMAGE = 0.04;
	private static final double HOUSE_RULES_HEAL = 0.03;
	private static final double BUST_DAMAGE = 0.30;
	private static final int FREE_SPIN_DELAY = 3000;
	private static final int CARD_GUARDS = 3;
	private static final double CARD_GUARD_HP = 2.0;
	private static final int BLUFFS = 2;
	private static final double BLUFF_HP = 0.15;
	private static final double BLUFF_ATTACK = 0.5;
	private static final double BLUFF_POT = 0.10;
	private static final int DEN_THIEVES = 4;
	private static final int DEALER_OFFSET = 150;

	/** The fortunes of the Den's Wheel of Fate. The lasting ones hold until the next spin (or, from the Joker, the next card); the others happen once, when the wheel stops. */
	private enum Fortune
	{
		LUCKY_STREAK("Lucky Streak", true, "You deal 30% more damage."),
		GOLDEN_TOUCH("Golden Touch", true, "The Pot grows by 25%."),
		SECOND_WIND("Second Wind", true, "You are healed to full."),
		JACKPOT("Jackpot", true, "A Jackpot Chest appears: break it within " + JACKPOT_SECONDS + " seconds!"),
		SLEIGHT_OF_HAND("Sleight of Hand", true, "Monsters have 25% less P. Def. and M. Def."),
		FOLD("Fold", true, "Monsters deal 20% less damage."),
		FREE_SPIN("Free Spin", true, "The Pot grows by 10%, and the wheel spins again!"),
		HOT_TABLE("Hot Table", true, "The Den's drops come twice as often."),
		CARD_COUNTER("Card Counter", true, "Every kill adds 2% to the Pot."),
		LUCKY_SEVEN("Lucky Seven", true, "Every 7th kill also rolls the Jackpot's drops."),
		COMPED_DRINKS("Comp'd Drinks", true, "You heal 5% HP and MP every " + PULSE_INTERVAL + " seconds."),
		HIGH_ROLLER("High Roller", true, "One more bet at the Dealer."),
		COLD_DECK("Cold Deck", false, "You deal 20% less damage."),
		BAD_BEAT("Bad Beat", false, "The Pot shrinks by 15%."),
		BUST("Bust", false, "You lose 30% of your HP."),
		RAISE_THE_STAKES("Raise the Stakes", false, STAKES_CHAMPIONS + " Champions join the game!"),
		IRON_SKIN("Iron Skin", false, "Monsters have 40% more P. Def. and M. Def."),
		LOADED_DICE("Loaded Dice", false, "Monsters deal 30% more damage."),
		CRACKED_DICE("Cracked Dice", false, "Monsters attack and cast 25% faster."),
		COLD_STREAK("Cold Streak", false, "The Den's drops come half as often."),
		SHORT_STACK("Short Stack", false, "You lose a bet."),
		PICKPOCKETS("Pickpockets", false, PICKPOCKET_COUNT + " Pickpockets grab for the Pot: catch them within " + PICKPOCKET_SECONDS + " seconds, or each takes 5%!"),
		HANGOVER("Hangover", false, "You lose 4% HP every " + PULSE_INTERVAL + " seconds."),
		HOUSE_RULES("House Rules", false, "Monsters heal 3% HP every " + PULSE_INTERVAL + " seconds.");

		final String _name;
		final boolean _blessing;
		final String _text;

		Fortune(String name, boolean blessing, String text)
		{
			_name = name;
			_blessing = blessing;
			_text = text;
		}
	}

	/** The House's deck: one card drawn at each HP threshold, no repeats. A card holds until the next one. */
	private enum Card
	{
		ACE("Ace of Blades", "The House hits 40% harder and attacks 30% faster."),
		KING("King of Shields", "The House is invulnerable until its " + CARD_GUARDS + " Card Guards fall."),
		QUEEN("Queen of Mirrors", "Two Bluffs join the House: each one slain adds 10% to the Pot."),
		JACK("Jack of Thieves", "The House vanishes, leaving thieves behind."),
		JOKER("Joker", "The wheel spins for the House.");

		final String _name;
		final String _text;

		Card(String name, String text)
		{
			_name = name;
			_text = text;
		}
	}

	/** How long after the field is cleared the next wave or the final encounter comes (ms). */
	private static final int FINALE_DELAY = 5000;

	/** The state of each open unique map, by its instance id. */
	private static final Map<Integer, State> STATES = new ConcurrentHashMap<>();

	/** What a unique map is doing. */
	private static class State
	{
		/** 0 while the field is cleared, 1 once the final encounter is called. */
		int _phase;
		/** The Coliseum's wave (0 based). */
		int _wave;
		/** {@code true} while a wave or the final encounter is on its way (the field is empty but not done). */
		volatile boolean _pending;
		volatile int _bossId;
		volatile boolean _bossDead;
		/** HP thresholds of the boss already passed. */
		int _thresholds;
		/** The Coliseum: how often the crowd roared. */
		volatile int _roars;
		/** The Vault: kills so far. */
		volatile int _greed;
		volatile long _lastSpill;
		boolean _sealWarned;
		int _clockWarned = Integer.MAX_VALUE;
		/** The Ossuary. */
		volatile boolean _shielded;
		volatile boolean _nova;
		volatile double _bossHpBonus = 1;
		final Set<Integer> _phylacteries = ConcurrentHashMap.newKeySet();
		final Set<Integer> _echoes = ConcurrentHashMap.newKeySet();
		final Set<Integer> _servants = ConcurrentHashMap.newKeySet();
		/** The Den. */
		volatile double _pot = POT_START;
		volatile int _bets;
		volatile Fortune _fortune;
		final List<Fortune> _fortuneBag = new ArrayList<>();
		/** Counted kills of the field. */
		int _denKills;
		/** Kills since the fortune came (Lucky Seven). */
		int _fortuneKills;
		volatile Card _card;
		final List<Card> _deck = new ArrayList<>();
		volatile int _jackpotId;
		volatile long _jackpotUntil;
		final Set<Integer> _bluffs = ConcurrentHashMap.newKeySet();
		final Set<Integer> _cardGuards = ConcurrentHashMap.newKeySet();
		/** Pickpockets by object id, with the time they escape. */
		final Map<Integer, Long> _pickpockets = new ConcurrentHashMap<>();
	}

	protected UniqueMapManager()
	{
	}

	private static State state(MapRun run)
	{
		return STATES.computeIfAbsent(run.getInstanceId(), k -> new State());
	}

	/**
	 * @param creature a creature
	 * @return {@code true} for a monster of a unique map's twist or final encounter (the boss, Challengers, phylacteries, Echoes...): never split, never rises again
	 */
	public static boolean isEncounterMonster(Creature creature)
	{
		return (creature != null) && creature.isMonster() && (creature.asMonster().getVariables().getInt(ENCOUNTER_VAR, 0) != 0);
	}

	private static int roleOf(Monster monster)
	{
		return monster.getVariables().getInt(ENCOUNTER_VAR, 0);
	}

	// =========================================================================
	// Stats
	// =========================================================================

	/**
	 * Read with every stat of a unique map's monster ({@link MapDeviceManager#getMonsterMultiplier}).
	 * @param run its run
	 * @param monster the monster
	 * @param stat the stat
	 * @return what its role multiplies the stat by, on top of the map's
	 */
	public static double getEncounterMultiplier(MapRun run, Monster monster, MonsterStat stat)
	{
		final int role = roleOf(monster);
		final double den = run.getUnique().type() == UniqueMap.Type.DEN ? getDenMultiplier(run, role, stat) : 1.0;
		return role == 0 ? den : den * getRoleMultiplier(run, role, stat);
	}

	/**
	 * @return what the Den's fortune (all its monsters but the Jackpot Chest) and the House's card (the House alone) multiply a stat by
	 */
	private static double getDenMultiplier(MapRun run, int role, MonsterStat stat)
	{
		final State state = STATES.get(run.getInstanceId());
		if ((state == null) || (role == ROLE_JACKPOT))
		{
			return 1.0;
		}
		final boolean attack = (stat == MonsterStat.P_ATK) || (stat == MonsterStat.M_ATK);
		final boolean defence = (stat == MonsterStat.P_DEF) || (stat == MonsterStat.M_DEF);
		final boolean speed = (stat == MonsterStat.ATK_SPD) || (stat == MonsterStat.CAST_SPD);
		double multiplier = 1.0;
		final Fortune fortune = state._fortune;
		if (fortune != null)
		{
			switch (fortune)
			{
				case SLEIGHT_OF_HAND -> multiplier *= defence ? 0.75 : 1.0;
				case IRON_SKIN -> multiplier *= defence ? 1.4 : 1.0;
				case FOLD -> multiplier *= attack ? 0.8 : 1.0;
				case LOADED_DICE -> multiplier *= attack ? 1.3 : 1.0;
				case CRACKED_DICE -> multiplier *= speed ? 1.25 : 1.0;
				default ->
				{
				}
			}
		}
		if ((role == ROLE_BOSS) && (state._card == Card.ACE))
		{
			multiplier *= attack ? 1.4 : stat == MonsterStat.ATK_SPD ? 1.3 : 1.0;
		}
		return multiplier;
	}

	/**
	 * @return what a twist's or final encounter's role multiplies a stat by
	 */
	private static double getRoleMultiplier(MapRun run, int role, MonsterStat stat)
	{
		final UniqueMap unique = run.getUnique();
		switch (role)
		{
			case ROLE_BOSS ->
			{
				final State state = STATES.get(run.getInstanceId());
				return switch (stat)
				{
					case HP -> unique.bossHp() * (state != null ? state._bossHpBonus : 1);
					case P_ATK, M_ATK -> unique.bossAttack() * (1 + ((state != null ? state._roars : 0) * ROAR_ATTACK));
					case P_DEF, M_DEF -> unique.bossDefence();
					default -> 1.0;
				};
			}
			case ROLE_CHALLENGER ->
			{
				return stat == MonsterStat.HP ? CHALLENGER_HP : 1.0;
			}
			case ROLE_PHYLACTERY ->
			{
				return stat == MonsterStat.HP ? PHYLACTERY_HP : 1.0;
			}
			case ROLE_ECHO ->
			{
				return stat == MonsterStat.HP ? ECHO_HP : stat == MonsterStat.ATK_SPD ? ECHO_SPEED : 1.0;
			}
			case ROLE_SERVANT ->
			{
				return stat == MonsterStat.HP ? SERVANT_HP : 1.0;
			}
			case ROLE_JACKPOT ->
			{
				return stat == MonsterStat.HP ? JACKPOT_HP : 1.0;
			}
			case ROLE_CARD_GUARD ->
			{
				return stat == MonsterStat.HP ? CARD_GUARD_HP : 1.0;
			}
			case ROLE_BLUFF ->
			{
				return switch (stat)
				{
					case HP -> unique.bossHp() * BLUFF_HP;
					case P_ATK, M_ATK -> unique.bossAttack() * BLUFF_ATTACK;
					case P_DEF, M_DEF -> unique.bossDefence();
					default -> 1.0;
				};
			}
			default ->
			{
				return 1.0;
			}
		}
	}

	// =========================================================================
	// Opening and filling
	// =========================================================================

	/**
	 * A unique map just opened (its monsters stand, its portals are open).
	 * @param run the run
	 */
	public void onRunOpened(MapRun run)
	{
		state(run);
		if (run.getUnique().type() == UniqueMap.Type.VAULT)
		{
			run.setClosesAt(Math.min(maxClose(run), System.currentTimeMillis() + (VAULT_START_SECONDS * 1000L)));
		}
		else if (run.getUnique().type() == UniqueMap.Type.DEN)
		{
			spawnDealer(run);
		}
	}

	/**
	 * The Den: the Dealer stands beside the Return Portal, facing the field like it.
	 */
	private static void spawnDealer(MapRun run)
	{
		if (NpcData.getInstance().getTemplate(MapDeviceConfig.DEALER_NPC_ID) == null)
		{
			LOGGER.warning(UniqueMapManager.class.getSimpleName() + ": No Dealer npc " + MapDeviceConfig.DEALER_NPC_ID + ": the Gambler's Den opens without its bets.");
			return;
		}
		final Location entry = run.getArena().entry();
		final double side = ((entry.getHeading() * 2 * Math.PI) / 65536) + (Math.PI / 2);
		final Location spot = MapDeviceManager.getGroundLocation(new Location(entry.getX() + (int) (Math.cos(side) * DEALER_OFFSET), entry.getY() + (int) (Math.sin(side) * DEALER_OFFSET), entry.getZ(), entry.getHeading()));
		final Npc dealer = Quest.addSpawn(MapDeviceConfig.DEALER_NPC_ID, spot.getX(), spot.getY(), spot.getZ(), entry.getHeading(), false, 0, false, run.getInstanceId());
		if (dealer != null)
		{
			dealer.setRandomWalking(false);
		}
	}

	/**
	 * Fills a unique map that fills itself.
	 * @param run the run, its monster kinds chosen
	 * @return {@code true} if it filled it, {@code false} to fill it like any map
	 */
	public boolean spawnMonsters(MapRun run)
	{
		if (run.getUnique().type() != UniqueMap.Type.COLISEUM)
		{
			return false;
		}
		final State state = state(run);
		run.setMonsterTotal(run.getUnique().monsterCount());
		spawnWave(run, state);
		return true;
	}

	/**
	 * @return how many monsters the Coliseum's wave holds: the waves of {@link #COLISEUM_WAVES}, scaled to the map's monster count, the last one taking the rest
	 */
	private static int waveSize(MapRun run, int wave)
	{
		int total = 0;
		for (int size : COLISEUM_WAVES)
		{
			total += size;
		}
		final int count = run.getUnique().monsterCount();
		if (wave == (COLISEUM_WAVES.length - 1))
		{
			int before = 0;
			for (int i = 0; i < wave; i++)
			{
				before += Math.max(1, Math.round((COLISEUM_WAVES[i] * count) / (float) total));
			}
			return Math.max(1, count - before);
		}
		return Math.max(1, Math.round((COLISEUM_WAVES[wave] * count) / (float) total));
	}

	private static void spawnWave(MapRun run, State state)
	{
		final List<NpcTemplate> kinds = run.getMonsterKinds();
		if (kinds.isEmpty())
		{
			return;
		}
		final int size = waveSize(run, state._wave);
		final List<Location> taken = new ArrayList<>();
		final List<Player> players = MapDeviceManager.getPlayersInside(run);
		for (int i = 0; i < size; i++)
		{
			final Location spot = MapDeviceManager.spacedSpot(() -> MapDeviceManager.randomFieldSpot(run, WAVE_SPREAD, true), taken, WAVE_SPACING);
			taken.add(spot);
			final Monster monster = MapDeviceManager.getInstance().spawnMapMonster(run, kinds.get(Rnd.get(kinds.size())), spot);
			if (monster == null)
			{
				continue;
			}
			MapDeviceManager.applyVariants(run, monster, false);
			attackSomeone(monster, players);
		}
		if (state._wave > 0)
		{
			MapDeviceManager.sendScreenToMap(run, "Wave " + (state._wave + 1) + " of " + COLISEUM_WAVES.length + ": " + size + " fighters enter the ring!");
		}
	}

	/**
	 * Sends a monster at one of the players inside, at random (the arena's fighters and the final encounters don't wait to be found).
	 */
	private static void attackSomeone(Monster monster, List<Player> players)
	{
		final List<Player> alive = new ArrayList<>(players);
		alive.removeIf(Player::isDead);
		if (!alive.isEmpty() && monster.hasAI())
		{
			final Player target = alive.get(Rnd.get(alive.size()));
			monster.addDamageHate(target, 0, 100);
			monster.getAI().setIntention(Intention.ATTACK, target);
		}
	}

	// =========================================================================
	// The field cleared: next wave or the final encounter
	// =========================================================================

	/**
	 * Every counted monster of a unique map is slain.
	 * @param run the run
	 * @return {@code true} if a wave or the final encounter comes (the map is not done), {@code false} if that was its end
	 */
	public boolean onFieldCleared(MapRun run)
	{
		final State state = state(run);
		synchronized (state)
		{
			if (state._pending)
			{
				return true;
			}
			final boolean nextWave = (run.getUnique().type() == UniqueMap.Type.COLISEUM) && (state._phase == 0) && (state._wave < (COLISEUM_WAVES.length - 1));
			if (!nextWave && (state._phase != 0))
			{
				return false;
			}
			state._pending = true;
			if (nextWave)
			{
				state._wave++;
				MapDeviceManager.sendScreenToMap(run, "The crowd roars for more! The next wave comes...");
			}
			else
			{
				state._phase = 1;
				MapDeviceManager.sendScreenToMap(run, announceFinale(run.getUnique()));
			}
		}

		ThreadPool.schedule(() ->
		{
			try
			{
				if (run.isClosed())
				{
					return;
				}
				if (state._phase == 0)
				{
					spawnWave(run, state);
				}
				else
				{
					startFinale(run, state);
				}
			}
			catch (Exception e)
			{
				LOGGER.log(Level.WARNING, getClass().getSimpleName() + ": The final encounter of " + run.getUnique().name() + " (instance " + run.getInstanceId() + ") failed.", e);
			}
			finally
			{
				state._pending = false;
			}
			if (!run.isClosed() && run.getMonsters().isEmpty() && (run.getStatus() == Status.OPEN) && !onFieldCleared(run))
			{
				MapDeviceManager.getInstance().completeRun(run, null, null);
			}
		}, FINALE_DELAY);
		return true;
	}

	private static String announceFinale(UniqueMap unique)
	{
		return switch (unique.type())
		{
			case COLISEUM -> "The gates grind open... the " + unique.bossName() + " enters the ring!";
			case VAULT -> "The hoard stirs... the " + unique.bossName() + " wakes!";
			case OSSUARY -> "The valley falls silent... " + unique.bossName() + " rises!";
			case DEN -> "The tables clear... " + unique.bossName() + " takes the table!";
		};
	}

	private void startFinale(MapRun run, State state)
	{
		final UniqueMap unique = run.getUnique();
		final NpcTemplate template = NpcData.getInstance().getTemplate(unique.bossNpcId());
		if (template == null)
		{
			LOGGER.warning(getClass().getSimpleName() + ": " + unique.name() + " has no boss npc " + unique.bossNpcId() + ".");
			return;
		}
		final MapArena arena = run.getArena();
		final Location middle = MapDeviceManager.getGroundLocation(arena.guardian() != null ? arena.guardian() : new Location(arena.x(), arena.y(), arena.z()));
		final List<Player> players = MapDeviceManager.getPlayersInside(run);

		switch (unique.type())
		{
			case COLISEUM ->
			{
				final Monster boss = spawnEncounter(run, template, middle, ROLE_BOSS, "[" + unique.bossName() + "]", true);
				if (boss != null)
				{
					state._bossId = boss.getObjectId();
					if (boss.getAI() instanceof AttackableAI ai)
					{
						ai.setArchetype(MonsterArchetype.RAGER);
					}
					attackSomeone(boss, players);
				}
			}
			case VAULT ->
			{
				final long now = System.currentTimeMillis();
				run.setClosesAt(Math.min(maxClose(run), Math.max(run.getClosesAt(), now + (VAULT_FINALE_MIN_SECONDS * 1000L))));
				final Monster boss = spawnEncounter(run, template, MapDeviceManager.randomFieldSpot(run, 0.6, true), ROLE_BOSS, "[" + unique.bossName() + "]", true);
				if (boss != null)
				{
					state._bossId = boss.getObjectId();
					if (boss.getAI() instanceof AttackableAI ai)
					{
						ai.setArchetype(MonsterArchetype.COWARD);
					}
					boss.startAbnormalVisualEffect(true, AbnormalVisualEffect.VP_UP);
					MapDeviceManager.sendToMap(run, "The " + unique.bossName() + " is somewhere in the vault, with " + (run.getRemainingSeconds() / 60) + "m " + (run.getRemainingSeconds() % 60) + "s on the clock. Catch it before the vault seals!");
				}
			}
			case OSSUARY ->
			{
				// Every Echo still standing is drawn into the king.
				int absorbed = 0;
				for (int objectId : state._echoes)
				{
					final WorldObject object = World.getInstance().findObject(objectId);
					if ((object instanceof Monster echo) && !echo.isDead())
					{
						echo.broadcastPacket(new MagicSkillUse(echo, echo, RAISE_SKILL_ID, 1, 0, 0));
						echo.deleteMe();
						absorbed++;
					}
				}
				state._echoes.clear();
				state._bossHpBonus = 1 + Math.min(ECHO_ABSORB_MAX, absorbed * ECHO_ABSORB);
				if (absorbed > 0)
				{
					MapDeviceManager.sendToMap(run, absorbed + " Echo" + (absorbed == 1 ? " is" : "es are") + " drawn into " + unique.bossName() + ": +" + Math.round((state._bossHpBonus - 1) * 100) + "% HP.");
				}

				final Monster boss = spawnEncounter(run, template, middle, ROLE_BOSS, "[" + unique.bossName() + "]", true);
				if (boss != null)
				{
					state._bossId = boss.getObjectId();
					if (boss.getAI() instanceof AttackableAI ai)
					{
						ai.setArchetype(MonsterArchetype.MAGE);
					}
					shield(run, state, boss, PHYLACTERIES[0]);
				}
			}
			case DEN ->
			{
				final int bets;
				synchronized (state)
				{
					state._fortune = null; // The fortunes end at the table.
					state._bets++;
					bets = state._bets;
					state._deck.clear();
					Collections.addAll(state._deck, Card.values());
					Collections.shuffle(state._deck);
				}
				final Monster boss = spawnEncounter(run, template, middle, ROLE_BOSS, "[" + unique.bossName() + "]", true);
				if (boss != null)
				{
					state._bossId = boss.getObjectId();
					attackSomeone(boss, players);
				}
				MapDeviceManager.sendToMap(run, "The wheel stops: no more fortunes. One last bet at the Dealer (" + bets + " in hand), Pot " + formatPot(state._pot) + ". " + unique.bossName() + " draws a card at 80%, 60%, 40% and 20% of its HP.");
			}
		}
	}

	// =========================================================================
	// The Gambler's Den: the Pot, the Wheel of Fate, the Dealer, the House's cards
	// =========================================================================

	private static String formatPot(double pot)
	{
		return "x" + String.format("%.2f", pot);
	}

	/**
	 * Adds to the Pot (or takes from it), within {@link #POT_MIN} and {@link #POT_MAX}.
	 * @return the Pot now
	 */
	private static double addPot(State state, double amount)
	{
		synchronized (state)
		{
			state._pot = Math.max(POT_MIN, Math.min(POT_MAX, state._pot + amount));
			return state._pot;
		}
	}

	/**
	 * @return what the Den's drop chances are multiplied by: the Pot, and Hot Table or Cold Streak
	 */
	private static double denDropFactor(State state)
	{
		final Fortune fortune = state._fortune;
		return state._pot * (fortune == Fortune.HOT_TABLE ? 2.0 : fortune == Fortune.COLD_STREAK ? 0.5 : 1.0);
	}

	/**
	 * The Wheel of Fate turns: the next fortune of the bag (refilled and shuffled when empty), which holds until the next spin.
	 * @param joker {@code true} when the House's Joker spins it (no bet comes with it)
	 */
	private static void spin(MapRun run, State state, boolean joker)
	{
		if (run.isClosed())
		{
			return;
		}
		final Fortune fortune;
		final int bets;
		synchronized (state)
		{
			if (state._fortuneBag.isEmpty())
			{
				Collections.addAll(state._fortuneBag, Fortune.values());
				Collections.shuffle(state._fortuneBag);
			}
			fortune = state._fortuneBag.remove(0);
			state._fortune = fortune;
			state._fortuneKills = 0;
			if (!joker)
			{
				state._bets++;
			}
			switch (fortune)
			{
				case HIGH_ROLLER -> state._bets++;
				case SHORT_STACK -> state._bets = Math.max(0, state._bets - 1);
				default ->
				{
				}
			}
			bets = state._bets;
		}

		final List<Player> players = MapDeviceManager.getPlayersInside(run);
		switch (fortune)
		{
			case GOLDEN_TOUCH -> addPot(state, 0.25);
			case BAD_BEAT -> addPot(state, -0.15);
			case FREE_SPIN -> addPot(state, 0.10);
			case SECOND_WIND ->
			{
				for (Player player : players)
				{
					if (!player.isDead())
					{
						player.setCurrentCp(player.getMaxCp());
						player.setCurrentHpMp(player.getMaxHp(), player.getMaxMp());
					}
				}
			}
			case BUST ->
			{
				for (Player player : players)
				{
					if (!player.isDead() && !player.isInvul())
					{
						player.reduceCurrentHp(player.getCurrentHp() * BUST_DAMAGE, null, false, false, null);
					}
				}
			}
			case JACKPOT -> spawnJackpot(run, state);
			case RAISE_THE_STAKES -> raiseTheStakes(run, players);
			case PICKPOCKETS -> spawnPickpockets(run, state, players);
			default ->
			{
			}
		}

		final String kind = fortune._blessing ? "Blessing" : "Curse";
		MapDeviceManager.sendScreenToMap(run, "Wheel of Fate - " + kind + ": " + fortune._name + "!");
		MapDeviceManager.sendToMap(run, "Wheel of Fate: " + fortune._name + " (" + kind.toLowerCase() + "). " + fortune._text + " Pot " + formatPot(state._pot) + ", " + bets + (bets == 1 ? " bet" : " bets") + " in hand.");

		if (fortune == Fortune.FREE_SPIN)
		{
			ThreadPool.schedule(() ->
			{
				// Only if nothing else turned the wheel meanwhile.
				if (!run.isClosed() && (state._fortune == Fortune.FREE_SPIN) && (joker || (state._phase == 0)))
				{
					spin(run, state, joker);
				}
			}, FREE_SPIN_DELAY);
		}
	}

	/**
	 * Jackpot: a chest that doesn't count toward the clear, gone after {@link #JACKPOT_SECONDS} seconds, with the special drops {@link #JACKPOT_ROLLS} times.
	 */
	private static void spawnJackpot(MapRun run, State state)
	{
		final NpcTemplate template = NpcData.getInstance().getTemplate(JACKPOT_NPC_ID);
		if (template == null)
		{
			return;
		}
		removeJackpot(state);
		final Monster chest = spawnEncounter(run, template, MapDeviceManager.randomFieldSpot(run, 0.5, true), ROLE_JACKPOT, "[Jackpot]", false);
		if (chest != null)
		{
			chest.setImmobilized(true);
			chest.setRandomWalking(false);
			chest.startAbnormalVisualEffect(true, AbnormalVisualEffect.VP_UP);
			state._jackpotUntil = System.currentTimeMillis() + (JACKPOT_SECONDS * 1000L);
			state._jackpotId = chest.getObjectId();
			MapDeviceManager.sendToMap(run, "The Jackpot Chest stands " + describeWay(new Location(run.getArena().entry().getX(), run.getArena().entry().getY(), 0), new Location(chest.getX(), chest.getY(), 0)) + " of the entry.");
		}
	}

	/**
	 * @return {@code true} if a Jackpot Chest was still standing (it is gone now)
	 */
	private static boolean removeJackpot(State state)
	{
		final int objectId = state._jackpotId;
		state._jackpotId = 0;
		if (objectId != 0)
		{
			final WorldObject object = World.getInstance().findObject(objectId);
			if ((object instanceof Monster chest) && !chest.isDead())
			{
				chest.deleteMe();
				return true;
			}
		}
		return false;
	}

	/**
	 * Raise the Stakes: Champions of the map's kinds rush one of the players. They count toward the clear.
	 */
	private static void raiseTheStakes(MapRun run, List<Player> players)
	{
		final List<NpcTemplate> kinds = run.getMonsterKinds();
		final List<Player> alive = new ArrayList<>(players);
		alive.removeIf(Player::isDead);
		if (kinds.isEmpty() || alive.isEmpty())
		{
			return;
		}
		final Player target = alive.get(Rnd.get(alive.size()));
		int spawned = 0;
		for (int i = 0; i < STAKES_CHAMPIONS; i++)
		{
			final Monster champion = MapDeviceManager.getInstance().spawnMapMonster(run, kinds.get(Rnd.get(kinds.size())), MapDeviceManager.nearSpot(run, target, 700));
			if (champion == null)
			{
				continue;
			}
			spawned++;
			if (ChampionMonstersConfig.CHAMPION_ENABLE)
			{
				champion.setChampionTier(Rnd.get(1, 2));
				champion.setCurrentHpMp(champion.getMaxHp(), champion.getMaxMp());
				champion.broadcastInfo();
			}
			attackSomeone(champion, List.of(target));
		}
		run.setMonsterTotal(run.getMonsterTotal() + spawned);
	}

	/**
	 * Pickpockets: thieves that don't count toward the clear. Each one slain adds to the Pot, each that gets away takes from it.
	 */
	private static void spawnPickpockets(MapRun run, State state, List<Player> players)
	{
		final List<NpcTemplate> kinds = run.getMonsterKinds();
		if (kinds.isEmpty())
		{
			return;
		}
		final long escapes = System.currentTimeMillis() + (PICKPOCKET_SECONDS * 1000L);
		for (int i = 0; i < PICKPOCKET_COUNT; i++)
		{
			final Monster thief = spawnEncounter(run, kinds.get(Rnd.get(kinds.size())), MapDeviceManager.randomFieldSpot(run, 0.6, true), ROLE_PICKPOCKET, "[Pickpocket]", false);
			if (thief != null)
			{
				if (ThiefMonsterConfig.ENABLED)
				{
					thief.startThief();
				}
				state._pickpockets.put(thief.getObjectId(), escapes);
				attackSomeone(thief, players);
			}
		}
	}

	/**
	 * The House draws the next card of its deck. The card before it ends (the Joker's fortune with it).
	 */
	private static void drawCard(MapRun run, State state, Monster boss)
	{
		final Card card;
		synchronized (state)
		{
			if (state._deck.isEmpty())
			{
				Collections.addAll(state._deck, Card.values());
				Collections.shuffle(state._deck);
			}
			card = state._deck.remove(0);
			state._card = card;
			state._fortune = null;
		}
		final UniqueMap unique = run.getUnique();
		boss.broadcastPacket(new MagicSkillUse(boss, boss, RAISE_SKILL_ID, 1, 0, 0));
		MapDeviceManager.sendScreenToMap(run, unique.bossName() + " draws the " + card._name + "!");
		MapDeviceManager.sendToMap(run, unique.bossName() + " draws the " + card._name + ": " + card._text);
		final List<NpcTemplate> kinds = run.getMonsterKinds();
		final List<Player> players = MapDeviceManager.getPlayersInside(run);
		switch (card)
		{
			case KING ->
			{
				int spawned = 0;
				for (int i = 0; (i < CARD_GUARDS) && !kinds.isEmpty(); i++)
				{
					final Monster guard = spawnEncounter(run, kinds.get(Rnd.get(kinds.size())), MapDeviceManager.nearSpot(run, boss, 600), ROLE_CARD_GUARD, "[Card Guard]", true);
					if (guard != null)
					{
						state._cardGuards.add(guard.getObjectId());
						attackSomeone(guard, players);
						spawned++;
					}
				}
				if (spawned > 0)
				{
					run.setMonsterTotal(run.getMonsterTotal() + spawned);
					state._shielded = true;
					boss.setInvul(true);
					boss.startAbnormalVisualEffect(true, AbnormalVisualEffect.INVINCIBILITY);
				}
			}
			case QUEEN ->
			{
				for (int i = 0; i < BLUFFS; i++)
				{
					final Monster bluff = spawnEncounter(run, boss.getTemplate(), MapDeviceManager.nearSpot(run, boss, 500), ROLE_BLUFF, "[" + unique.bossName() + "]", false);
					if (bluff != null)
					{
						bluff.startAbnormalVisualEffect(true, AbnormalVisualEffect.BIG_BODY);
						state._bluffs.add(bluff.getObjectId());
						attackSomeone(bluff, players);
					}
				}
			}
			case JACK ->
			{
				final String way = vanishWithThieves(run, boss, DEN_THIEVES, "[Card Sharp]");
				MapDeviceManager.sendToMap(run, unique.bossName() + " vanishes " + way + ".");
			}
			case JOKER -> spin(run, state, true);
			default ->
			{
			}
		}
	}

	/**
	 * The Dealer: a bet doubles the Pot or halves it, at even odds. The bets are the map's, shared by everyone inside.
	 * @param player who bets
	 * @return what the player is told, {@code null} if the whole map was told
	 */
	public String placeBet(Player player)
	{
		final MapRun run = MapDeviceManager.getRun(player.getInstanceId());
		if ((run == null) || !run.isUnique() || (run.getUnique().type() != UniqueMap.Type.DEN))
		{
			return "The Dealer only takes bets in the Gambler's Den.";
		}
		final State state = STATES.get(run.getInstanceId());
		if ((state == null) || (run.getStatus() != Status.OPEN) || state._bossDead)
		{
			return "The tables are closed.";
		}
		final boolean won;
		final double pot;
		synchronized (state)
		{
			if (state._bets <= 0)
			{
				return "You have no bet in hand. The Wheel of Fate gives one at every spin, and the House one more.";
			}
			state._bets--;
			won = Rnd.nextBoolean();
			state._pot = won ? Math.min(POT_MAX, state._pot * 2) : Math.max(POT_MIN, state._pot / 2);
			pot = state._pot;
		}
		MapDeviceManager.sendScreenToMap(run, player.getName() + (won ? " wins the bet! Pot " : " loses the bet. Pot ") + formatPot(pot));
		MapDeviceManager.sendToMap(run, player.getName() + " bets the Pot at the Dealer and " + (won ? "wins: it doubles" : "loses: it halves") + " to " + formatPot(pot) + ". " + state._bets + (state._bets == 1 ? " bet" : " bets") + " left.");
		return null;
	}

	/**
	 * @param run a run
	 * @return the Pot of a Gambler's Den, 0 for any other map
	 */
	public double getPot(MapRun run)
	{
		final State state = denState(run);
		return state != null ? state._pot : 0;
	}

	/**
	 * @param run a run
	 * @return the bets in hand of a Gambler's Den
	 */
	public int getBets(MapRun run)
	{
		final State state = denState(run);
		return state != null ? state._bets : 0;
	}

	/**
	 * @param run a run
	 * @return the Den's fortune ("Lucky Streak (blessing): You deal 30% more damage."), {@code null} for none
	 */
	public String getFortuneText(MapRun run)
	{
		final State state = denState(run);
		final Fortune fortune = state != null ? state._fortune : null;
		return fortune != null ? fortune._name + " (" + (fortune._blessing ? "blessing" : "curse") + "): " + fortune._text : null;
	}

	/**
	 * @param run a run
	 * @return {@code true} if the Den's fortune is a blessing
	 */
	public boolean isFortuneBlessing(MapRun run)
	{
		final State state = denState(run);
		final Fortune fortune = state != null ? state._fortune : null;
		return (fortune != null) && fortune._blessing;
	}

	/**
	 * @param run a run
	 * @return the House's card ("Ace of Blades: ..."), {@code null} for none
	 */
	public String getCardText(MapRun run)
	{
		final State state = denState(run);
		final Card card = state != null ? state._card : null;
		return card != null ? card._name + ": " + card._text : null;
	}

	/**
	 * @return how many kills until the next spin of the wheel, 0 once the field is cleared
	 */
	public int getKillsToSpin(MapRun run)
	{
		final State state = denState(run);
		return (state == null) || (state._phase != 0) ? 0 : DEN_KILLS_PER_SPIN - (state._denKills % DEN_KILLS_PER_SPIN);
	}

	private static State denState(MapRun run)
	{
		return (run != null) && run.isUnique() && (run.getUnique().type() == UniqueMap.Type.DEN) ? STATES.get(run.getInstanceId()) : null;
	}

	/**
	 * Spawns a monster of a twist or final encounter.
	 * @param counted {@code true} if it counts toward the clear
	 */
	private static Monster spawnEncounter(MapRun run, NpcTemplate template, Location spot, int role, String tag, boolean counted)
	{
		final Monster monster = MapDeviceManager.getInstance().spawnMapMonster(run, template, spot);
		if (monster == null)
		{
			return null;
		}
		if (!counted)
		{
			run.getMonsters().remove(monster.getObjectId());
		}
		monster.getVariables().set(ENCOUNTER_VAR, role);
		if (role == ROLE_BOSS)
		{
			monster.startAbnormalVisualEffect(true, AbnormalVisualEffect.BIG_BODY);
		}
		if (tag != null)
		{
			monster.setEventTitleTag(tag);
		}
		monster.setCurrentHpMp(monster.getMaxHp(), monster.getMaxMp());
		monster.broadcastInfo();
		return monster;
	}

	// =========================================================================
	// Damage
	// =========================================================================

	/**
	 * A monster of a unique map is about to take damage ({@link MapDeviceManager#onMonsterDamaged}).
	 * @return the damage it takes, 0 for none
	 */
	public double onMonsterDamaged(MapRun run, Monster monster, double damage, Creature attacker)
	{
		final State state = STATES.get(run.getInstanceId());
		if (state == null)
		{
			return damage;
		}
		final UniqueMap unique = run.getUnique();
		if ((unique.type() == UniqueMap.Type.DEN) && (attacker != null) && attacker.isPlayable())
		{
			final Fortune fortune = state._fortune;
			if (fortune == Fortune.LUCKY_STREAK)
			{
				damage *= 1.3;
			}
			else if (fortune == Fortune.COLD_DECK)
			{
				damage *= 0.8;
			}
		}
		if (roleOf(monster) != ROLE_BOSS)
		{
			return damage;
		}
		if (state._shielded)
		{
			if ((attacker != null) && attacker.isPlayer() && (Rnd.get(10) == 0))
			{
				attacker.sendPacket(new ExShowScreenMessage(unique.type() == UniqueMap.Type.DEN ? unique.bossName() + " is shielded: kill its Card Guards!" : unique.bossName() + " is shielded: break his Bone Phylacteries!", 2000));
			}
			return 0;
		}

		final double[] thresholds = switch (unique.type())
		{
			case COLISEUM -> COLISEUM_THRESHOLDS;
			case VAULT -> VAULT_THRESHOLDS;
			case OSSUARY -> OSSUARY_THRESHOLDS;
			case DEN -> DEN_THRESHOLDS;
		};
		final double maxHp = monster.getMaxHp();
		final double after = monster.getCurrentHp() - damage;
		double taken = damage;
		int passed = -1;
		synchronized (state)
		{
			if ((state._thresholds < thresholds.length) && (after <= (maxHp * thresholds[state._thresholds])) && (after > 0))
			{
				passed = state._thresholds++;
				if (unique.type() == UniqueMap.Type.OSSUARY)
				{
					// He shields again at the threshold, not below it.
					taken = Math.max(0, monster.getCurrentHp() - (maxHp * thresholds[passed]));
					state._shielded = true;
					monster.setInvul(true);
				}
			}
		}
		if (passed >= 0)
		{
			final int index = passed;
			ThreadPool.execute(() -> onThreshold(run, state, monster, index));
		}

		// The Mimic spills coins when struck.
		if ((unique.type() == UniqueMap.Type.VAULT) && (attacker != null) && (attacker.asPlayer() != null) && (Rnd.get(100) < SPILL_CHANCE))
		{
			final long now = System.currentTimeMillis();
			if ((now - state._lastSpill) >= SPILL_COOLDOWN)
			{
				state._lastSpill = now;
				drop(monster, attacker.asPlayer(), unique.specialDrops(), 1);
			}
		}
		return taken;
	}

	private void onThreshold(MapRun run, State state, Monster boss, int index)
	{
		if (run.isClosed() || boss.isDead())
		{
			return;
		}
		final UniqueMap unique = run.getUnique();
		switch (unique.type())
		{
			case COLISEUM ->
			{
				state._roars++;
				boss.broadcastPacket(new MagicSkillUse(boss, boss, RAISE_SKILL_ID, 1, 0, 0));
				MapDeviceManager.sendScreenToMap(run, "The crowd roars! The " + unique.bossName() + " hits " + Math.round(state._roars * ROAR_ATTACK * 100) + "% harder, and " + CHALLENGERS + " Challengers jump in!");
				final List<NpcTemplate> kinds = run.getMonsterKinds();
				final List<Player> players = MapDeviceManager.getPlayersInside(run);
				for (int i = 0; (i < CHALLENGERS) && !kinds.isEmpty(); i++)
				{
					final Monster challenger = spawnEncounter(run, kinds.get(Rnd.get(kinds.size())), MapDeviceManager.nearSpot(run, boss, 500), ROLE_CHALLENGER, null, true);
					if (challenger != null)
					{
						if (ChampionMonstersConfig.CHAMPION_ENABLE)
						{
							challenger.setChampionTier(2);
						}
						challenger.setEventTitleTag("[Challenger]");
						challenger.setCurrentHpMp(challenger.getMaxHp(), challenger.getMaxMp());
						attackSomeone(challenger, players);
					}
				}
				run.setMonsterTotal(run.getMonsterTotal() + CHALLENGERS);
			}
			case VAULT ->
			{
				final String way = vanishWithThieves(run, boss, VAULT_THIEVES, "[Vault Thief]");
				MapDeviceManager.sendScreenToMap(run, "The " + unique.bossName() + " vanishes " + way + ", leaving its thieves behind!");
			}
			case DEN -> drawCard(run, state, boss);
			case OSSUARY ->
			{
				shield(run, state, boss, PHYLACTERIES[Math.min(PHYLACTERIES.length - 1, index + 1)]);
				if (index == (OSSUARY_THRESHOLDS.length - 1))
				{
					state._nova = true;
					MapDeviceManager.sendToMap(run, unique.bossName() + " is desperate: a Death Nova will burst from him every " + NOVA_INTERVAL + " seconds. Keep your distance!");
				}
			}
		}
	}

	/**
	 * A boss vanishes to another corner of the field (2,000 away if it can), leaving thieves where it stood (the Vault's Mimic, the Den's Jack of Thieves).
	 * @return which way it went ("to the north-east")
	 */
	private static String vanishWithThieves(MapRun run, Monster boss, int thieves, String tag)
	{
		final Location from = new Location(boss.getX(), boss.getY(), boss.getZ());
		Location to = null;
		for (int attempt = 0; attempt < 10; attempt++)
		{
			to = MapDeviceManager.randomFieldSpot(run, 0.85, true);
			if (Math.hypot(to.getX() - from.getX(), to.getY() - from.getY()) > 2000)
			{
				break;
			}
		}
		boss.broadcastPacket(new MagicSkillUse(boss, boss, VANISH_SKILL_ID, 1, 0, 0));
		boss.clearAggroList();
		boss.teleToLocation(to.getX(), to.getY(), to.getZ(), boss.getHeading(), run.getInstanceId());
		if (boss.hasAI())
		{
			boss.getAI().setIntention(Intention.ACTIVE);
		}
		final List<NpcTemplate> kinds = run.getMonsterKinds();
		final List<Player> players = MapDeviceManager.getPlayersInside(run);
		for (int i = 0; (i < thieves) && !kinds.isEmpty(); i++)
		{
			final Monster thief = spawnEncounter(run, kinds.get(Rnd.get(kinds.size())), MapDeviceManager.getGroundLocation(new Location(from.getX() + Rnd.get(-250, 250), from.getY() + Rnd.get(-250, 250), from.getZ())), ROLE_THIEF, null, false);
			if (thief != null)
			{
				if (ThiefMonsterConfig.ENABLED)
				{
					thief.startThief();
				}
				thief.setEventTitleTag(tag);
				attackSomeone(thief, players);
			}
		}
		return describeWay(from, to);
	}

	private static String describeWay(Location from, Location to)
	{
		final double angle = Math.toDegrees(Math.atan2(to.getY() - from.getY(), to.getX() - from.getX()));
		// The client's y grows to the south.
		final String[] ways =
		{
			"east",
			"south-east",
			"south",
			"south-west",
			"west",
			"north-west",
			"north",
			"north-east"
		};
		final int index = (int) Math.floorMod(Math.round(angle / 45), 8);
		return "to the " + ways[index];
	}

	/**
	 * The Hollow King shields himself behind new Bone Phylacteries at the edges of the field.
	 */
	private static void shield(MapRun run, State state, Monster boss, int count)
	{
		final NpcTemplate template = NpcData.getInstance().getTemplate(PHYLACTERY_NPC_ID);
		if (template == null)
		{
			return; // No phylactery: no shield.
		}
		state._shielded = true;
		boss.setInvul(true);
		boss.startAbnormalVisualEffect(true, AbnormalVisualEffect.INVINCIBILITY);
		boss.broadcastPacket(new MagicSkillUse(boss, boss, RAISE_SKILL_ID, 1, 0, 0));
		final MapArena arena = run.getArena();
		final double start = Rnd.nextDouble() * 2 * Math.PI;
		for (int i = 0; i < count; i++)
		{
			final double angle = start + ((i * 2 * Math.PI) / count);
			final int x = arena.x() + (int) (Math.cos(angle) * arena.radiusX() * PHYLACTERY_SPREAD);
			final int y = arena.y() + (int) (Math.sin(angle) * arena.radiusY() * PHYLACTERY_SPREAD);
			final Location spot = MapDeviceManager.getGroundLocation(new Location(x, y, arena.z()));
			final Monster phylactery = spawnEncounter(run, template, spot, ROLE_PHYLACTERY, "[Bone Phylactery]", true);
			if (phylactery != null)
			{
				phylactery.setImmobilized(true);
				phylactery.setRandomWalking(false);
				state._phylacteries.add(phylactery.getObjectId());
			}
		}
		if (state._phylacteries.isEmpty())
		{
			unshield(run, state, boss);
			return;
		}
		run.setMonsterTotal(run.getMonsterTotal() + count);
		MapDeviceManager.sendScreenToMap(run, run.getUnique().bossName() + " hides behind " + count + " Bone Phylacteries: break them all!");
	}

	private static void unshield(MapRun run, State state, Monster boss)
	{
		state._shielded = false;
		boss.setInvul(false);
		boss.stopAbnormalVisualEffect(true, AbnormalVisualEffect.INVINCIBILITY);
		MapDeviceManager.sendScreenToMap(run, "The shield of " + run.getUnique().bossName() + " shatters: strike now!");
	}

	// =========================================================================
	// Deaths
	// =========================================================================

	/**
	 * A monster of a unique map died ({@link MapDeviceManager}'s death hook, before its completion check).
	 * @param run the run
	 * @param victim the monster
	 * @param player the player credited, {@code null} for none
	 * @param counted {@code true} if it counted toward the clear
	 */
	public void onMonsterDied(MapRun run, Monster victim, Player player, boolean counted)
	{
		final State state = state(run);
		final UniqueMap unique = run.getUnique();
		final int role = roleOf(victim);
		final boolean den = unique.type() == UniqueMap.Type.DEN;
		// The Vault's Greed, the Den's Pot (and Hot Table / Cold Streak). The Den's drops also follow the server rates, and past 100% they grow bigger.
		final double greed = unique.type() == UniqueMap.Type.VAULT ? 1 + (Math.min(GREED_MAX, state._greed) / 100.0) : den ? denDropFactor(state) : 1;

		// Drops.
		if (player != null)
		{
			switch (role)
			{
				case 0, ROLE_THIEF, ROLE_CARD_GUARD, ROLE_PICKPOCKET ->
				{
					final boolean spawnling = victim.getVariables().getBoolean("MAP_SPLIT", false) || victim.getVariables().getBoolean("MAP_MINION", false);
					drop(victim, player, unique.drops(), greed * (spawnling ? 0.5 : 1), den);
				}
				case ROLE_CHALLENGER, ROLE_ECHO, ROLE_BLUFF -> drop(victim, player, unique.specialDrops(), den ? greed : 1, den);
				case ROLE_JACKPOT ->
				{
					for (int i = 0; i < JACKPOT_ROLLS; i++)
					{
						drop(victim, player, unique.specialDrops(), greed, true);
					}
				}
				case ROLE_BOSS ->
				{
					drop(victim, player, unique.bossDrops(), greed, den);
					if ((Rnd.nextDouble() * 100) < MapDeviceConfig.UNIQUE_MAP_BOSS_CHANCE)
					{
						dropUniqueMap(run, victim, player);
					}
				}
				default ->
				{
				}
			}
		}

		if (role == ROLE_BOSS)
		{
			state._bossDead = true;
			state._nova = false;
			MapDeviceManager.sendScreenToMap(run, "The " + unique.bossName() + " has fallen!");
			LOGGER.info(getClass().getSimpleName() + ": The " + unique.bossName() + " of " + run.getOwnerName() + "'s " + unique.name() + " (instance " + run.getInstanceId() + ") fell" + (player != null ? " to " + player.getName() : "") + ".");
			return;
		}

		switch (unique.type())
		{
			case COLISEUM ->
			{
				if (player != null)
				{
					grantPicks(run, victim, player);
				}
			}
			case VAULT ->
			{
				if (state._phase == 0)
				{
					state._greed++;
					addTime(run, VAULT_SECONDS_PER_KILL);
					if ((player != null) && ((state._greed % 5) == 0))
					{
						player.sendPacket(new ExShowScreenMessage("Greed " + Math.min(GREED_MAX, state._greed) + "%  -  vault seals in " + formatTime(run.getRemainingSeconds()), ExShowScreenMessage.BOTTOM_RIGHT, 3000));
					}
				}
			}
			case OSSUARY ->
			{
				if (role == ROLE_PHYLACTERY)
				{
					state._phylacteries.remove(victim.getObjectId());
					if (state._phylacteries.isEmpty() && state._shielded)
					{
						final WorldObject object = World.getInstance().findObject(state._bossId);
						if ((object instanceof Monster boss) && !boss.isDead())
						{
							unshield(run, state, boss);
						}
					}
				}
				else if (role == ROLE_ECHO)
				{
					state._echoes.remove(victim.getObjectId());
				}
				else if (role == ROLE_SERVANT)
				{
					state._servants.remove(victim.getObjectId());
				}
				else if (counted && (state._phase == 0) && (state._echoes.size() < ECHO_MAX) && (Rnd.get(100) < ECHO_CHANCE))
				{
					final NpcTemplate template = victim.getTemplate();
					final Location corpse = new Location(victim.getX(), victim.getY(), victim.getZ(), victim.getHeading());
					ThreadPool.schedule(() -> raiseEcho(run, state, template, corpse, player), 1500);
				}
			}
			case DEN -> onDenDeath(run, state, victim, role, player, counted);
		}
	}

	/**
	 * The Den: the deaths of the Jackpot Chest, Bluffs, Card Guards and Pickpockets, and the field's kills that turn the wheel.
	 */
	private static void onDenDeath(MapRun run, State state, Monster victim, int role, Player player, boolean counted)
	{
		final UniqueMap unique = run.getUnique();
		switch (role)
		{
			case ROLE_JACKPOT ->
			{
				state._jackpotId = 0;
				MapDeviceManager.sendToMap(run, "The Jackpot Chest bursts open" + (player != null ? " for " + player.getName() : "") + "!");
			}
			case ROLE_BLUFF ->
			{
				state._bluffs.remove(victim.getObjectId());
				MapDeviceManager.sendToMap(run, "A Bluff of " + unique.bossName() + " falls: the Pot grows to " + formatPot(addPot(state, BLUFF_POT)) + ".");
			}
			case ROLE_CARD_GUARD ->
			{
				state._cardGuards.remove(victim.getObjectId());
				if (state._cardGuards.isEmpty() && state._shielded)
				{
					final WorldObject object = World.getInstance().findObject(state._bossId);
					if ((object instanceof Monster boss) && !boss.isDead())
					{
						unshield(run, state, boss);
					}
				}
			}
			case ROLE_PICKPOCKET ->
			{
				if (state._pickpockets.remove(victim.getObjectId()) != null)
				{
					MapDeviceManager.sendToMap(run, "A Pickpocket is caught: the Pot grows to " + formatPot(addPot(state, PICKPOCKET_POT)) + ".");
				}
			}
			case 0 ->
			{
				if (!counted || (state._phase != 0))
				{
					return;
				}
				final Fortune fortune = state._fortune;
				final boolean spin;
				final boolean seven;
				synchronized (state)
				{
					state._denKills++;
					state._fortuneKills++;
					spin = (state._denKills % DEN_KILLS_PER_SPIN) == 0;
					seven = (fortune == Fortune.LUCKY_SEVEN) && ((state._fortuneKills % LUCKY_SEVEN) == 0);
				}
				if (fortune == Fortune.CARD_COUNTER)
				{
					addPot(state, CARD_COUNTER_POT);
				}
				if (seven && (player != null))
				{
					drop(victim, player, unique.specialDrops(), denDropFactor(state), true);
					player.sendPacket(new ExShowScreenMessage("Lucky Seven!", 2000));
				}
				if (spin && !run.getMonsters().isEmpty())
				{
					spin(run, state, false);
				}
			}
			default ->
			{
			}
		}
	}

	/**
	 * The Coliseum: a pick for the killer, and one for everyone inside who dealt a fair share of the damage ({@link TeamVsTeamConfig#PDM_ASSIST_SHARE} %).
	 */
	private static void grantPicks(MapRun run, Attackable victim, Player killer)
	{
		final PassiveDeathmatchManager mayhem = PassiveDeathmatchManager.getInstance();
		if (killer.getInstanceId() == run.getInstanceId())
		{
			mayhem.onMapKill(killer, false);
		}
		if (TeamVsTeamConfig.PDM_ASSIST_SHARE <= 0)
		{
			return;
		}
		final Map<Player, Long> dealt = new java.util.HashMap<>();
		long total = 0;
		for (AggroInfo info : victim.getAggroList().values())
		{
			final Creature attacker = info.getAttacker();
			final Player owner = attacker == null ? null : attacker.asPlayer() != null ? attacker.asPlayer() : attacker.isSummon() ? attacker.asSummon().getOwner() : null;
			if (owner != null)
			{
				dealt.merge(owner, info.getDamage(), Long::sum);
			}
			total += Math.max(0, info.getDamage());
		}
		if (total <= 0)
		{
			return;
		}
		for (Map.Entry<Player, Long> entry : dealt.entrySet())
		{
			final Player helper = entry.getKey();
			if ((helper != killer) && (helper.getInstanceId() == run.getInstanceId()) && (((entry.getValue() * 100) / total) >= TeamVsTeamConfig.PDM_ASSIST_SHARE))
			{
				mayhem.onMapKill(helper, true);
			}
		}
	}

	/**
	 * The Ossuary: an Echo of the slain rises where it fell, and hunts its killer.
	 */
	private static void raiseEcho(MapRun run, State state, NpcTemplate template, Location corpse, Player killer)
	{
		if (run.isClosed() || (state._phase != 0))
		{
			return;
		}
		final Monster echo = spawnEncounter(run, template, corpse, ROLE_ECHO, "[Echo]", false);
		if (echo == null)
		{
			return;
		}
		state._echoes.add(echo.getObjectId());
		echo.startAbnormalVisualEffect(true, AbnormalVisualEffect.STEALTH);
		echo.broadcastPacket(new MagicSkillUse(echo, echo, RAISE_SKILL_ID, 1, 0, 0));
		if ((killer != null) && !killer.isDead() && (killer.getInstanceId() == run.getInstanceId()))
		{
			attackSomeone(echo, List.of(killer));
		}
	}

	/**
	 * The Vault: a bounty claimed in time buys more time.
	 * @param run the run
	 */
	public void onBountyClaimed(MapRun run)
	{
		if (run.getUnique().type() == UniqueMap.Type.VAULT)
		{
			addTime(run, VAULT_SECONDS_PER_BOUNTY - VAULT_SECONDS_PER_KILL);
			MapDeviceManager.sendToMap(run, "The bounty buys the vault " + VAULT_SECONDS_PER_BOUNTY + " more seconds: it seals in " + formatTime(run.getRemainingSeconds()) + ".");
		}
	}

	private static void addTime(MapRun run, int seconds)
	{
		run.setClosesAt(Math.min(maxClose(run), run.getClosesAt() + (seconds * 1000L)));
	}

	/**
	 * @return the latest a run may collapse: its instance lasts {@code MapDuration} plus 2 minutes from its opening
	 */
	private static long maxClose(MapRun run)
	{
		return run.getOpenedAt() + (MapDeviceConfig.MAP_DURATION * 60000L);
	}

	private static String formatTime(int seconds)
	{
		return (seconds / 60) + "m " + String.format("%02d", seconds % 60) + "s";
	}

	// =========================================================================
	// The run's clock
	// =========================================================================

	/**
	 * Once a second, while players are inside or not ({@code MapDeviceManager.RunTick}).
	 * @param run the run
	 * @param tick seconds since it opened
	 * @param now the time (ms)
	 */
	public void onTick(MapRun run, int tick, long now)
	{
		final State state = STATES.get(run.getInstanceId());
		if ((state == null) || (run.getStatus() != Status.OPEN))
		{
			return;
		}
		switch (run.getUnique().type())
		{
			case VAULT ->
			{
				final int remaining = run.getRemainingSeconds();
				for (int mark : new int[]
				{
					120,
					60,
					30,
					10
				})
				{
					if ((remaining <= mark) && (remaining > (mark - 5)) && (state._clockWarned > mark))
					{
						state._clockWarned = mark;
						MapDeviceManager.sendScreenToMap(run, "The vault seals in " + formatTime(remaining) + "!");
					}
				}
				if (remaining > 120)
				{
					state._clockWarned = Integer.MAX_VALUE; // The clock was pushed back: warn again.
				}
				if (!state._sealWarned && (remaining <= 1))
				{
					state._sealWarned = true;
					MapDeviceManager.sendScreenToMap(run, state._phase == 1 ? "The vault seals! The " + run.getUnique().bossName() + " escapes with the hoard..." : "The vault seals!");
				}
			}
			case OSSUARY ->
			{
				if ((state._phase != 1) || state._bossDead)
				{
					return;
				}
				final WorldObject object = World.getInstance().findObject(state._bossId);
				final Monster boss = (object instanceof Monster monster) && !monster.isDead() ? monster : null;
				if (boss == null)
				{
					return;
				}
				if (((tick % SERVANT_INTERVAL) == 0) && !state._phylacteries.isEmpty())
				{
					raiseServants(run, state);
				}
				if (state._nova && ((tick % NOVA_INTERVAL) == 0))
				{
					boss.broadcastPacket(new MagicSkillUse(boss, boss, NOVA_SKILL_ID, 1, 0, 0));
					final List<Player> struck = new ArrayList<>();
					for (Player player : MapDeviceManager.getPlayersInside(run))
					{
						if (!player.isDead() && (player.calculateDistance2D(boss) <= NOVA_RANGE))
						{
							struck.add(player);
						}
					}
					MapDeviceManager.hurt(struck, NOVA_DAMAGE, "The Death Nova tears through you!");
				}
			}
			case DEN -> denTick(run, state, tick, now);
			default ->
			{
			}
		}
	}

	/**
	 * The Den's clock: the Jackpot Chest closing, Pickpockets getting away, and the pulses of Comp'd Drinks, Hangover and House Rules.
	 */
	private static void denTick(MapRun run, State state, int tick, long now)
	{
		if ((state._jackpotId != 0) && (now >= state._jackpotUntil) && removeJackpot(state))
		{
			MapDeviceManager.sendToMap(run, "The Jackpot Chest snaps shut and vanishes.");
		}
		for (Map.Entry<Integer, Long> entry : state._pickpockets.entrySet())
		{
			if ((now < entry.getValue()) || (state._pickpockets.remove(entry.getKey()) == null))
			{
				continue;
			}
			final WorldObject object = World.getInstance().findObject(entry.getKey());
			if (object instanceof Monster thief)
			{
				if (thief.isDead())
				{
					continue;
				}
				thief.deleteMe();
			}
			MapDeviceManager.sendToMap(run, "A Pickpocket gets away: the Pot shrinks to " + formatPot(addPot(state, -PICKPOCKET_POT)) + ".");
		}

		final Fortune fortune = state._fortune;
		if ((fortune == null) || ((tick % PULSE_INTERVAL) != 0))
		{
			return;
		}
		switch (fortune)
		{
			case COMPED_DRINKS ->
			{
				for (Player player : MapDeviceManager.getPlayersInside(run))
				{
					if (!player.isDead())
					{
						player.setCurrentHpMp(Math.min(player.getMaxHp(), player.getCurrentHp() + (player.getMaxHp() * COMPED_HEAL)), Math.min(player.getMaxMp(), player.getCurrentMp() + (player.getMaxMp() * COMPED_HEAL)));
					}
				}
			}
			case HANGOVER ->
			{
				for (Player player : MapDeviceManager.getPlayersInside(run))
				{
					final double damage = Math.min(player.getMaxHp() * HANGOVER_DAMAGE, player.getCurrentHp() - 1);
					if (!player.isDead() && !player.isInvul() && (damage > 0))
					{
						player.reduceCurrentHp(damage, null, false, false, null);
					}
				}
			}
			case HOUSE_RULES ->
			{
				for (int objectId : run.getMonsters())
				{
					final WorldObject object = World.getInstance().findObject(objectId);
					if ((object instanceof Monster monster) && !monster.isDead() && (monster.getCurrentHp() < monster.getMaxHp()))
					{
						monster.setCurrentHp(Math.min(monster.getMaxHp(), monster.getCurrentHp() + (monster.getMaxHp() * HOUSE_RULES_HEAL)));
					}
				}
			}
			default ->
			{
			}
		}
	}

	private static void raiseServants(MapRun run, State state)
	{
		final List<NpcTemplate> kinds = run.getMonsterKinds();
		if (kinds.isEmpty())
		{
			return;
		}
		final List<Player> players = MapDeviceManager.getPlayersInside(run);
		for (int objectId : state._phylacteries)
		{
			if (state._servants.size() >= SERVANT_MAX)
			{
				return;
			}
			final WorldObject object = World.getInstance().findObject(objectId);
			if (!(object instanceof Monster phylactery) || phylactery.isDead())
			{
				continue;
			}
			phylactery.broadcastPacket(new MagicSkillUse(phylactery, phylactery, RAISE_SKILL_ID, 1, 0, 0));
			final Monster servant = spawnEncounter(run, kinds.get(Rnd.get(kinds.size())), MapDeviceManager.nearSpot(run, phylactery, 300), ROLE_SERVANT, "[Bone Servant]", false);
			if (servant != null)
			{
				state._servants.add(servant.getObjectId());
				attackSomeone(servant, players);
			}
		}
	}

	// =========================================================================
	// Players
	// =========================================================================

	/**
	 * A player came into a unique map.
	 * @param player the player
	 * @param run the run
	 */
	public void onPlayerEntered(Player player, MapRun run)
	{
		final UniqueMap unique = run.getUnique();
		switch (unique.type())
		{
			case COLISEUM -> PassiveDeathmatchManager.getInstance().joinMap(player, run.getInstanceId(), unique.name());
			case VAULT -> player.sendMessage(unique.name() + ": the vault seals in " + formatTime(run.getRemainingSeconds()) + ". Every kill buys " + VAULT_SECONDS_PER_KILL + " seconds and 1% Greed.");
			case OSSUARY -> player.sendMessage(unique.name() + ": a quarter of the slain leave Echoes. Kill them before the end, or they feed the " + unique.bossName() + ".");
			case DEN ->
			{
				final State state = state(run);
				player.sendMessage(unique.name() + ": every " + DEN_KILLS_PER_SPIN + " kills the Wheel of Fate spins a blessing or a curse, and gives a bet. At the Dealer by the portal, a bet doubles or halves the Pot. Pot " + formatPot(state._pot) + ", " + state._bets + " bets in hand.");
			}
		}
	}

	/**
	 * A player left a unique map (died, went back, logged off).
	 * @param player the player
	 * @param run the run
	 */
	public void onPlayerLeft(Player player, MapRun run)
	{
		if (run.getUnique().type() == UniqueMap.Type.COLISEUM)
		{
			PassiveDeathmatchManager.getInstance().leaveMap(player);
		}
	}

	/**
	 * A unique map closed: its state is gone, and with it the Coliseum's passives.
	 * @param run the run
	 */
	public void onRunClosed(MapRun run)
	{
		STATES.remove(run.getInstanceId());
		if (run.getUnique().type() == UniqueMap.Type.COLISEUM)
		{
			PassiveDeathmatchManager.getInstance().forgetMap(run.getInstanceId());
		}
	}

	// =========================================================================
	// Unique map drops
	// =========================================================================

	/**
	 * A map is complete: a plain map of tier {@link MapDeviceConfig#UNIQUE_MAP_MIN_TIER} and up may drop a unique map.
	 * @param run the run
	 * @param lastKill the last monster slain, {@code null} if unknown
	 * @param player who slew it, {@code null} if unknown
	 */
	public void onRunCompleted(MapRun run, Monster lastKill, Player player)
	{
		if (run.isUnique() || (lastKill == null) || (player == null) || !MapDeviceConfig.UNIQUE_MAPS_ENABLED || (run.getTier().tier() < MapDeviceConfig.UNIQUE_MAP_MIN_TIER))
		{
			return;
		}
		final double chance = MapDeviceConfig.UNIQUE_MAP_COMPLETION_CHANCE + (run.getMedals() * MapDeviceConfig.UNIQUE_MAP_CHANCE_PER_MEDAL);
		if ((Rnd.nextDouble() * 100) < chance)
		{
			dropUniqueMap(run, lastKill, player);
		}
	}

	/**
	 * Drops a unique map at random. Unique maps only drop inside maps, and count toward the run's map budget ({@link MapDeviceConfig#MAX_MAPS_PER_RUN}).
	 */
	private static void dropUniqueMap(MapRun run, Attackable victim, Player player)
	{
		final List<UniqueMap> uniques = new ArrayList<>(MapDeviceData.getInstance().getUniqueMaps().values());
		uniques.removeIf(unique -> ItemData.getInstance().getTemplate(unique.itemId()) == null);
		if (uniques.isEmpty())
		{
			return;
		}
		if (!run.claimMapDrop())
		{
			return;
		}
		final UniqueMap unique = uniques.get(Rnd.get(uniques.size()));
		victim.dropOrAutoLoot(player, new ItemHolder(unique.itemId(), 1));
		player.sendMessage(victim.getName() + " left behind a " + MapDeviceManager.getItemName(unique.itemId()) + "!");
		player.sendPacket(new ExShowScreenMessage("Unique map: " + unique.name() + "!", 5000));
	}

	/**
	 * Rolls a list of drops for a kill.
	 * @param chanceMultiplier multiplies every chance (capped at 100%)
	 */
	private static void drop(Attackable victim, Player player, List<UniqueMap.Drop> drops, double chanceMultiplier)
	{
		drop(victim, player, drops, chanceMultiplier, false);
	}

	/**
	 * Rolls a list of drops for a kill.
	 * @param chanceMultiplier multiplies every chance
	 * @param denRules {@code true} for the Gambler's Den: the server rates apply first, as to a monster's own death drop ({@link RatesConfig}: the item's own multipliers, else the death drop ones), and a chance the multiplier lifts past
	 *            100% makes the drop bigger instead
	 */
	private static void drop(Attackable victim, Player player, List<UniqueMap.Drop> drops, double chanceMultiplier, boolean denRules)
	{
		for (UniqueMap.Drop drop : drops)
		{
			if (ItemData.getInstance().getTemplate(drop.itemId()) == null)
			{
				continue;
			}
			double chance = drop.chance();
			double amount = 1;
			if (denRules)
			{
				final Float chanceRate = RatesConfig.RATE_DROP_CHANCE_BY_ID.get(drop.itemId());
				final Float amountRate = RatesConfig.RATE_DROP_AMOUNT_BY_ID.get(drop.itemId());
				chance = Math.min(100, chance * (chanceRate != null ? chanceRate : RatesConfig.RATE_DEATH_DROP_CHANCE_MULTIPLIER)); // The rates never raise the count, as for any monster.
				amount = amountRate != null ? amountRate : RatesConfig.RATE_DEATH_DROP_AMOUNT_MULTIPLIER;
			}
			chance *= chanceMultiplier;
			if (denRules && (chance > 100))
			{
				amount *= chance / 100;
				chance = 100;
			}
			if ((Rnd.nextDouble() * 100) < Math.min(100, chance))
			{
				final long count = drop.max() > drop.min() ? Rnd.get(drop.min(), drop.max() + 1) : drop.min();
				final long rated = Math.max(1, Math.round(count * amount));
				victim.dropOrAutoLoot(player, new ItemHolder(drop.itemId(), rated));
			}
		}
	}

	public static UniqueMapManager getInstance()
	{
		return SingletonHolder.INSTANCE;
	}

	private static class SingletonHolder
	{
		protected static final UniqueMapManager INSTANCE = new UniqueMapManager();
	}
}
