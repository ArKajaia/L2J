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
package org.l2jmobius.gameserver.ai;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.l2jmobius.commons.threads.ThreadPool;
import org.l2jmobius.commons.util.Rnd;
import org.l2jmobius.gameserver.config.GeneralConfig;
import org.l2jmobius.gameserver.config.custom.FakeClanConfig;
import org.l2jmobius.gameserver.config.custom.FakePartyConfig;
import org.l2jmobius.gameserver.config.custom.FakePlayerPvpConfig;
import org.l2jmobius.gameserver.config.custom.PvpSpotsConfig;
import org.l2jmobius.gameserver.data.xml.HitConditionBonusData;
import org.l2jmobius.gameserver.data.xml.SkillData;
import org.l2jmobius.gameserver.geoengine.GeoEngine;
import org.l2jmobius.gameserver.managers.FakeClanManager;
import org.l2jmobius.gameserver.managers.FakePartyManager;
import org.l2jmobius.gameserver.managers.FakePlayerPvpManager;
import org.l2jmobius.gameserver.managers.ItemsOnGroundManager;
import org.l2jmobius.gameserver.managers.PvpSpotManager;
import org.l2jmobius.gameserver.managers.VillageSiegeManager;
import org.l2jmobius.gameserver.managers.simulation.FakeAiTrace;
import org.l2jmobius.gameserver.managers.simulation.FakeSimulationManager;
import org.l2jmobius.gameserver.managers.simulation.Reason;
import org.l2jmobius.gameserver.managers.simulation.SimulationRun;
import org.l2jmobius.gameserver.model.Location;
import org.l2jmobius.gameserver.model.World;
import org.l2jmobius.gameserver.model.WorldObject;
import org.l2jmobius.gameserver.model.WorldRegion;
import org.l2jmobius.gameserver.model.actor.Attackable;
import org.l2jmobius.gameserver.model.actor.Creature;
import org.l2jmobius.gameserver.model.actor.Npc;
import org.l2jmobius.gameserver.data.enums.CategoryType;
import org.l2jmobius.gameserver.model.actor.Player;
import org.l2jmobius.gameserver.model.actor.holders.npc.AggroInfo;
import org.l2jmobius.gameserver.model.actor.holders.npc.DropHolder;
import org.l2jmobius.gameserver.model.actor.holders.npc.FakePlayerPvpBuild.Role;
import org.l2jmobius.gameserver.model.actor.holders.npc.FakePlayerPvpBuild.SkillCategory;
import org.l2jmobius.gameserver.model.actor.holders.npc.FakePlayerHolder;
import org.l2jmobius.gameserver.model.actor.holders.npc.FakePlayerParty;
import org.l2jmobius.gameserver.model.actor.holders.npc.FakePlayerPvpCombo;
import org.l2jmobius.gameserver.model.actor.holders.npc.FakePlayerPvpPersonality;
import org.l2jmobius.gameserver.model.actor.holders.npc.FakePlayerPvpProfile;
import org.l2jmobius.gameserver.model.actor.holders.player.Totems;
import org.l2jmobius.gameserver.model.actor.instance.Chest;
import org.l2jmobius.gameserver.model.actor.instance.FakePlayerPvpServitor;
import org.l2jmobius.gameserver.model.actor.instance.Monster;
import org.l2jmobius.gameserver.model.actor.instance.Totem;
import org.l2jmobius.gameserver.model.clan.Clan;
import org.l2jmobius.gameserver.model.effects.AbstractEffect;
import org.l2jmobius.gameserver.model.item.Weapon;
import org.l2jmobius.gameserver.model.item.instance.Item;
import org.l2jmobius.gameserver.model.item.type.WeaponType;
import org.l2jmobius.gameserver.model.passivetree.PassiveMechanics;
import org.l2jmobius.gameserver.model.skill.BuffInfo;
import org.l2jmobius.gameserver.model.skill.EffectScope;
import org.l2jmobius.gameserver.model.skill.Skill;
import org.l2jmobius.gameserver.model.skill.holders.SkillHolder;
import org.l2jmobius.gameserver.model.skill.targets.TargetType;
import org.l2jmobius.gameserver.model.spawns.Spawn;
import org.l2jmobius.gameserver.model.stats.Formulas;
import org.l2jmobius.gameserver.model.zone.ZoneId;
import org.l2jmobius.gameserver.model.zone.type.PvpSpotZone;
import org.l2jmobius.gameserver.network.serverpackets.ChangeWaitType;
import org.l2jmobius.gameserver.util.LocationUtil;

/**
 * AI of a roaming fake player (see {@link FakePlayerPvpManager}). It plays like a character of its class: hunts the monsters around its spawn point, keeps its buffs up, uses the class skills it has learned (best ones first), drinks potions, archers and mages keep their
 * distance, and it fights any player that attacks it or steals its kill. In a PvP it plays like a player: it focuses the weakest enemy, closes the gap (Rush, Shadow Step, Dash), stops a melee attacker before stepping back (roots, stuns), waits out an invincible enemy,
 * cleanses roots and bleeds, and some fake players run from a fight they are losing and read a Scroll of Escape once they got away (and out of every player's sight). After a few hits it notices a player's Ultimate Defense, Guts, Zealot, Angelic Icon or magic mirror, stops
 * wasting what doesn't get through and keeps its distance while it can. It sits down to rest after a hard fight, and may go after a flagged or karma player passing by, or walk up to a lower level player (or fake player), hit them once and see whether they
 * want a fight (see {@link #lookForPoke}). Unflagged, it may not hit back a higher level (see {@link FakePlayerPvpManager#onFakePlayerAttacked}). Warriors surrounded by monsters take out a polearm to hit several of them at once. Necromancers keep their servitor out
 * with Transfer Pain on, and when it dies in a PvP they run off to summon a new one and come back (see {@link #thinkRegroup}).
 * <p>
 * Changes tested in party-vs-party simulations as brain B ({@link FakeBrain}) before they moved here, see docs/fake-player-simulation-issues.md for the issues and the results. First, B won 37 of 57 rounds against the version without them:
 * <ul>
 * <li>#1: it is never its own target ({@link #isValidTarget}).</li>
 * <li>#4, #7: target choice ({@link #focusScore}): an enemy on a squishy of the party (its healer first) is answered at any range, an archer or mage weighs only the distance beyond its reach and prefers squishies, and the damage an enemy did to the party counts sooner.</li>
 * <li>#5, #6: a healer defends itself against enemies shooting or casting at it from range too, heals itself first when low and before the hit while one casts at it, drinks potions while it defends, and silenced it takes cover behind its tank ({@link #defendHealer}).</li>
 * <li>#16: a tank keeps its bow out only while its target gets away (or while it is rooted itself) ({@link #chooseWeapon}).</li>
 * </ul>
 * Then B won 41 of 60 rounds (levels 40, 61 and 80) against the version without these:
 * <ul>
 * <li>#8: no self-heal longer than 2 seconds to cast while a melee enemy is on it.</li>
 * <li>#10: no debuffs on an enemy that just reflected one.</li>
 * <li>#20: stepping back and running away pick, of a fan of points, the one farthest from all the enemies around, not just straight away from one ({@link #pickRetreat}).</li>
 * <li>#21: an archer with an enemy on it within 600 (one coming at it too) steps aside once after each shot (an arrow or a skill), keeping its target in range and sight: shoot, step, shoot.</li>
 * <li>#23: a tank that leads its party, while nobody is on the party, holds further ahead so the party moves up with it, until its target is within reach (holding by its squishies froze the whole team); a healer in a fight doesn't chase its leader while its heals still reach it.</li>
 * <li>#25: a melee doesn't leave a target it is already on to hold for its tank, holds by the tank rather than the middle of the party, and for 5 seconds at most.</li>
 * <li>#27: a healer under ranged burst steps out of the shooters' reach, toward its party.</li>
 * <li>#28: an enemy tank on a squishy of the party gets no pressure bonus (it is answered with peels, not damage).</li>
 * <li>#29: an archer in a PvP shoots plain arrows (they crit far more often once buffed) and keeps its skills, combos and debuffs for when its arrows would miss (hit chance below 75%); peeling with Stun Shot stays as it was.</li>
 * </ul>
 * <p>
 * Brain B ({@link FakeBrain#B}): the experimental copy of {@link FakePlayerPvpAI}, which simulation teams can run against it. Changes go here first and are moved into {@link FakePlayerPvpAI} once simulations show they win more (see docs/fake-player-simulation-issues.md). It starts
 * as a copy of {@link FakePlayerPvpAI}: nothing changed yet (its last changes were promoted, see docs/fake-player-simulation-issues.md; the archer kiting of docs/class-balance-85.md round 11 and the hybrid Soul Hound mace swap went into both).
 */
public class FakePlayerPvpAIExperimental extends AttackableAI implements FakePlayerPvpBrain
{
	/** One in this many idle ticks the fake player walks somewhere else around its spawn point. */
	private static final int WANDER_CHANCE = 8;
	/** Monsters more levels away than this are not worth hunting. */
	private static final int MAX_HUNT_LEVEL_DIFFERENCE = 10;
	/** The range a mage tries to fight from. */
	private static final int MAGE_RANGE = 600;
	/** An archer or mage is pressed when a melee after it would reach it within this many milliseconds. */
	private static final long PRESSED_TIME = 2500;
	/** A pressed archer or mage steps back while the melee is closer than this. */
	private static final int PRESSED_KITE_DISTANCE = 350;
	/** A cast that ends this long after the melee reaches it still goes (the hit lands as the cast ends). */
	private static final long CAST_MARGIN = 300;
	/** A cast this short always goes. */
	private static final long QUICK_CAST = 600;
	/** An archer steps back from a melee closer than this (and that would reach it before its next shot is off). */
	private static final int ARCHER_KITE_DISTANCE = 600;
	/** The shortest step back of an archer: about one shot's worth of distance from a melee chasing it. */
	private static final int ARCHER_KITE_STEP = 350;
	/** An archer whose way back is blocked tries these angles (degrees) off it, one side then the other. */
	private static final int[] ARCHER_KITE_ANGLES = { 45, -45, 90, -90 };
	/** #21: an archer steps aside after a shot when an enemy this close is on it (one coming at it too: inside melee reach the peel and kite step take over), this far, within this long after the shot. */
	private static final int RELOAD_STEP_THREAT = 600;
	private static final int RELOAD_STEP = 150;
	private static final long SHOT_STEP_WINDOW = 1500;
	/** A Totem Warchief stepping back around its totem stops this far beyond it. */
	private static final int TOTEM_KITE_OFFSET = 150;
	
	/** How long a combo waits for a required step (cooldown, casting range...) before giving up. */
	private static final long COMBO_STEP_TIMEOUT = 3000;
	/** How long a combo may take in total. */
	private static final long COMBO_TIMEOUT = 15000;
	/** How long it tries to get behind its target before using the skill from where it is. */
	private static final long BEHIND_TIMEOUT = 2500;
	
	/** How long it goes on with a monster it can't hit (no path, a ledge...) before giving it up. */
	private static final long MONSTER_STUCK_TIMEOUT = 15000;
	/** How long it goes on with a player it can't hit before giving up (the player can pick the fight again). */
	private static final long PLAYER_STUCK_TIMEOUT = 30000;
	/** How long a monster it gave up on is left alone. */
	private static final long UNREACHABLE_IGNORE_TIME = 60000;
	
	/** Scroll of Escape (20 seconds, only interrupted by stuns and the like). */
	private static final int SCROLL_OF_ESCAPE = 2013;
	/** Blessed Scroll of Escape (0.2 seconds), which some high levels carry. */
	private static final int BLESSED_SCROLL_OF_ESCAPE = 2036;
	/** How far it runs in one go when it runs away. */
	private static final int FLEE_STEP = 700;
	/** Turns (in radians) it tries, from straight away from its pursuer, when a wall is in the way. */
	private static final double[] FLEE_TURNS =
	{
		0,
		0.5,
		-0.5,
		1.0,
		-1.0,
		1.6,
		-1.6
	};
	/** It reads its Scroll of Escape once its pursuer is this far away. */
	private static final int ESCAPE_DISTANCE = 900;
	/** A pursuer this close is shaken off (root, stun...) before running on. */
	private static final int PEEL_DISTANCE = 300;
	/** It doesn't sit down to rest with a player it fights (or that comes for it) this close: a little farther than it runs before it stops running from them ({@link #ESCAPE_DISTANCE}). */
	private static final int THREAT_RANGE = 1200;
	/** After running this long with a pursuer on its heels, it turns around and fights to the end. */
	private static final long FLEE_TIMEOUT = 45000;
	/** A necromancer summons its new servitor (15 seconds) once the players it fights are this far, and runs on when one comes this close. */
	private static final int SUMMON_SAFE_DISTANCE = 900;
	private static final int SUMMON_ABORT_DISTANCE = 300;
	/** It gives up running off to summon after this long (it fights on without a servitor), and tries again after this long. */
	private static final long REGROUP_TIMEOUT = 60000;
	private static final long REGROUP_RETRY = 30000;
	/** Transfer Pain never takes a servitor below 1 HP: below this HP ratio in a PvP it is spent, and sent away to summon a fresh one. */
	private static final double SPENT_SERVITOR_HP = 0.1;
	/** How long it sticks to the player it chose to focus in a fight against several. */
	private static final long FOCUS_TIME = 6000;
	/** It chases a player getting away for this long before using a speed buff or a gap closer. */
	private static final long CHASE_BEFORE_SPRINT = 1500;
	/** Flagged and karma players are spotted within this range, looked for this often, and each one is only considered once in this time. */
	private static final int OPPORTUNITY_RANGE = 900;
	private static final long OPPORTUNITY_SCAN_INTERVAL = 3000;
	private static final long OPPORTUNITY_MEMORY = 180000;
	/** Milliseconds between two looks for players it hasn't seen yet. */
	private static final long GREET_SCAN_INTERVAL = 4000;
	/** It doesn't go after a flagged or karma player more than this many levels above it. */
	private static final int OPPORTUNITY_MAX_LEVEL_ABOVE = 3;
	/** A taunt (see {@link #lookForPoke}): how close it stands to the one it taunts, how far they may walk off before it follows, how long it waits for an answer after its hit, and how long it may take in total. */
	private static final int POKE_STAND_DISTANCE = 60;
	private static final int POKE_FOLLOW_DISTANCE = 250;
	private static final int POKE_WATCH_MIN = 4000;
	private static final int POKE_WATCH_MAX = 8000;
	private static final long POKE_TIMEOUT = 30000;
	/** Meeting another fake player (see {@link #lookForMeeting}): how often it looks, how long it remembers whom it thought about, how close it stands to talk, and how long it may take to get there. */
	private static final long MEET_SCAN_INTERVAL = 5000;
	private static final long MEET_MEMORY = 600000;
	private static final int MEET_TALK_DISTANCE = 80;
	private static final long MEET_TIMEOUT = 40000;
	/** How far apart two fake players may drift while they talk before the talk is over. */
	private static final int TALK_MAX_DISTANCE = 600;
	/** How long it sticks to its choice to take (or leave) a monster another fake player is fighting. */
	private static final long STEAL_MEMORY = 60000;
	/** Milliseconds between two looks for monsters to hunt while the last look found none. */
	private static final long PREY_SCAN_INTERVAL = 2000;
	/** A player chains the next skill a moment after the last one ends, not a whole AI tick later. */
	private static final int SKILL_CHAIN_MIN_DELAY = 150;
	private static final int SKILL_CHAIN_MAX_DELAY = 400;
	/** Skill ids of the stuns, roots, sleeps... which a disabled target doesn't need again. */
	private static final Map<Integer, Boolean> HARD_DISABLES = new ConcurrentHashMap<>();
	
	/** What a defensive buff of a player keeps from working (bit flags, see {@link Defense}). */
	private static final int PHYSICAL_DAMAGE = 1;
	private static final int MAGIC_DAMAGE = 2;
	private static final int PHYSICAL_DEBUFFS = 4;
	private static final int MAGIC_DEBUFFS = 8;
	/** The player hits hard meanwhile: better stay away from it. */
	private static final int DANGEROUS = 16;
	
	/**
	 * The defensive buffs of players a fake player learns to play around. It doesn't know right away: each of its attacks or skills on the player may make it notice (see {@link #noticeDefenses}).
	 */
	private enum Defense
	{
		/** Ultimate Defense, Vengeance: huge P. Def. and M. Def., but it can't move. */
		ULTIMATE_DEFENSE(PHYSICAL_DAMAGE | MAGIC_DAMAGE, true, true, 110, 368),
		/** Guts: P. Def. tripled at low HP, only physical hits notice it. */
		GUTS(PHYSICAL_DAMAGE, true, false, 139),
		/** Zealot: resists debuffs, and hits hard and fast. */
		ZEALOT(PHYSICAL_DEBUFFS | MAGIC_DEBUFFS | DANGEROUS, true, true, 420),
		/** Angelic Icon: resists debuffs, P. Def. and M. Def. up by half, and hits hard and fast. */
		ANGELIC_ICON(PHYSICAL_DEBUFFS | MAGIC_DEBUFFS | DANGEROUS, true, true, 406),
		/** Magical Mirror (5 minutes): magic debuffs come back 30% of the time, damage doesn't. Only magic skills notice it. */
		MAGIC_MIRROR(MAGIC_DEBUFFS, false, true, 351),
		/** Shield Deflect Magic (8 seconds), Reflect Magic (10 seconds): magic damage comes back. Only magic skills notice it. */
		DEFLECT_MAGIC(MAGIC_DAMAGE | MAGIC_DEBUFFS, false, true, 916, 6282);
		
		private static final Map<Integer, Defense> BY_SKILL_ID = new ConcurrentHashMap<>();
		static
		{
			for (Defense defense : values())
			{
				for (int skillId : defense._skillIds)
				{
					BY_SKILL_ID.put(skillId, defense);
				}
			}
		}
		
		private final int _flags;
		private final boolean _noticedByPhysical;
		private final boolean _noticedByMagic;
		private final int[] _skillIds;
		
		Defense(int flags, boolean noticedByPhysical, boolean noticedByMagic, int... skillIds)
		{
			_flags = flags;
			_noticedByPhysical = noticedByPhysical;
			_noticedByMagic = noticedByMagic;
			_skillIds = skillIds;
		}
		
		/**
		 * @param skill a skill
		 * @return the defense {@code skill} gives, {@code null} if none
		 */
		static Defense of(Skill skill)
		{
			return skill == null ? null : BY_SKILL_ID.get(skill.getId());
		}
	}
	
	private long _kiteEndTime = 0;
	private long _nextKiteTime = 0;
	// #21: when it last shot (an arrow or a skill on an enemy), and whether it stepped aside since.
	private long _lastShotTime = 0;
	private boolean _shotStepped = true;
	// #25: the enemy it holds for its tank on, and since when.
	private Creature _holdTarget;
	private long _holdSince;
	// #10: who it last debuffed and when, and the enemies that reflected one: object id -> until when it leaves their debuffs alone.
	private Creature _lastDebuffTarget;
	private long _lastDebuffTime;
	private final Map<Integer, Long> _reflectors = new ConcurrentHashMap<>();
	private volatile boolean _resting = false;
	
	// Running away from a PvP it is losing: since when, when it runs on and may read its scroll, and whether it gave up running (then it fights to the end).
	private boolean _fleeing = false;
	private long _fleeStart = 0;
	private long _nextFleeStep = 0;
	private long _nextEscapeTime = 0;
	private boolean _lastStand = false;
	
	// A necromancer running off to summon a new servitor: since when, when it may try again after giving up, and whether it is on its way back to the fight.
	private boolean _regrouping = false;
	private long _regroupStart = 0;
	private long _nextRegroupTime = 0;
	private boolean _returning = false;
	
	// The player it focuses in a fight against several (the weakest one), and until when.
	private Creature _focus = null;
	private long _focusUntil = 0;
	
	// Flagged and karma players passing by: when it looks next, and whom it already thought about (object id -> time).
	private long _nextOpportunityScan = 0;
	private final Map<Integer, Long> _consideredPlayers = new ConcurrentHashMap<>();
	private long _nextRevengeScan;
	/** Players it has already seen (and maybe said hello to), so it doesn't greet anyone twice. */
	private final Set<Integer> _seenPlayers = ConcurrentHashMap.newKeySet();
	private long _nextGreetScan;
	// When it looks for monsters to hunt next, after a look that found none.
	private long _nextPreyScanTime;
	
	/**
	 * The steps of a taunt: walk up to them, stand there a moment, hit them once, then see whether they hit back.
	 */
	private enum PokePhase
	{
		APPROACH,
		LOITER,
		HIT,
		WATCH
	}
	
	// Taunting a lower level for a PvP: whom, which step and until when, since when, when it looks next and whom it already thought about (object id -> time).
	private Creature _pokeTarget = null;
	private PokePhase _pokePhase = null;
	private long _pokePhaseEnd = 0;
	private long _pokeStart = 0;
	private long _nextPokeScan = 0;
	private final Map<Integer, Long> _pokeConsidered = new ConcurrentHashMap<>();
	
	// Whether the taunt follows a talk about the spot (see {@link #startRivalry}): then it may go for anyone up to a few levels above it.
	private boolean _pokeRival = false;
	
	// Walking over to another fake player at the hunting ground: whom, since when, when it looks next and whom it already thought about (object id -> time).
	private Npc _meetTarget = null;
	private long _meetStart = 0;
	private long _nextMeetScan = 0;
	private final Map<Integer, Long> _meetConsidered = new ConcurrentHashMap<>();
	
	// Another fake player that walked over to talk and waits for it to finish its monster, and until when.
	private volatile Npc _visitor = null;
	private volatile long _visitorUntil = 0;
	
	// The fake player it talks with (see FakePlayerPvpManager#converse), and until when.
	private volatile Npc _talkPartner = null;
	private volatile long _talkUntil = 0;
	
	// Monsters other fake players fight that it decided to take (object id -> until when) or to leave alone (minus until when).
	private final Map<Integer, Long> _stealDecisions = new ConcurrentHashMap<>();
	
	// The player it is fighting, to notice when that player goes down (the aggro list drops dead attackers by itself).
	private Creature _pvpTarget = null;
	
	// Its current target and when it last attacked it or cast on it, to give up on a target it can't reach.
	private Creature _progressTarget = null;
	private long _progressTime = 0;
	private Creature _unreachable = null;
	private long _unreachableUntil = 0;
	
	// The player it is chasing out of melee reach, and since when (it sprints, rushes in, or takes out its bow when it takes too long).
	private Creature _chaseTarget = null;
	private long _chaseStart = 0;
	private long _nextWeaponSwapTime = 0;
	
	// The combo being played: which one, on whom, which step, and since when.
	private FakePlayerPvpCombo.Chain _combo = null;
	private Creature _comboTarget = null;
	private int _comboStep = 0;
	private long _comboStepStart = 0;
	private long _comboEnd = 0;
	private long _nextComboTime = 0;
	
	// A class whose damage is its normal attack (tanks, archers, most warriors), not its skills: it plays its combos less often.
	private boolean _autoAttacker = false;
	
	/** The defensive buffs (Ultimate Defense, Guts, Zealot, Angelic Icon, magic mirror) it noticed on the players it fights. */
	private final Set<BuffInfo> _noticedDefenses = ConcurrentHashMap.newKeySet();
	
	// In a party (see FakePartyManager): the support skills it cast lately on whom (skill id << 32 | object id -> time).
	private final Map<Long, Long> _recentSupport = new ConcurrentHashMap<>();

	// The decision trace (see FakeAiTrace): whether this think already noted why it acted (the first, most precise reason wins).
	private boolean _traced;
	// In a party: when it may stop someone on its healer again (see peelForHealer).
	private long _nextAllyPeel;
	// Where it goes to see its target past something in the way (see moveToSee): the spot, for which target, and until when it keeps it.
	private Location _firingSpot;
	private Creature _firingTarget;
	private long _firingUntil;
	// In a simulation: where it last marched to, and when it may set off again.
	private Location _marchDestination;
	private long _nextMarchTime;
	
	public FakePlayerPvpAIExperimental(Attackable creature)
	{
		super(creature);
	}
	
	@Override
	protected void thinkActive()
	{
		final WorldRegion region = _actor.getWorldRegion();
		if ((region == null) || !region.areNeighborsActive())
		{
			return;
		}
		
		final Attackable npc = getActiveChar();
		if (npc.isDead() || npc.isCoreAIDisabled() || npc.isCastingNow())
		{
			return;
		}
		_traced = false;

		// Someone picked a fight (a player, or a monster that hit first).
		final Creature hated = npc.getMostHated();
		checkPvpTarget(npc, hated);
		if ((hated != null) && isValidTarget(npc, hated))
		{
			npc.setRunning();
			setIntention(Intention.ATTACK, hated);
			return;
		}
		
		// Fight over: the bow or polearm goes back in the bag.
		_chaseTarget = null;
		_progressTarget = null;
		_focus = null;
		_fleeing = false;
		_lastStand = false;
		_regrouping = false;
		_returning = false;
		_noticedDefenses.clear();
		final FakePlayerPvpProfile profile = npc.getTemplate().getFakePlayerPvpProfile();
		if ((profile != null) && (profile.getHeldWeapon() != profile.getMainWeapon()))
		{
			FakePlayerPvpManager.getInstance().equipWeapon(npc, profile.getMainWeapon());
		}
		
		// A class transfer challenge opponent does nothing but wait for its challenger: no roaming, poking, greeting or hunting.
		if ((profile != null) && profile.isTrialDuelist())
		{
			FakePlayerPvpManager.getInstance().engageTrialTarget(npc);
			return;
		}

		// A fake player of a party-vs-party simulation only looks for the other teams: no hunting, poking, greeting, talks or leaving.
		if ((profile != null) && profile.isSimulant())
		{
			thinkSimulation(npc, profile);
			return;
		}
		
		// A fake player of a PvP spot came only to fight: no hunting, poking, greeting or talks.
		if ((profile != null) && profile.isSpotFighter())
		{
			thinkSpot(npc, profile, System.currentTimeMillis());
			return;
		}
		
		// A fake player defending a village under siege fights its monsters at its entrance, and nothing else.
		if ((profile != null) && profile.isSiegeDefender())
		{
			thinkSiege(npc, profile, System.currentTimeMillis());
			return;
		}
		
		// In a party: it looks after the party, follows its leader and fights what the party fights.
		final FakePlayerParty party = profile != null ? profile.getParty() : null;
		if ((party != null) && thinkParty(npc, profile, party))
		{
			return;
		}
		
		// Its hotzone rotated out a while ago: off to town, and it logs off.
		if (thinkLeave(npc, profile, System.currentTimeMillis()))
		{
			return;
		}
		
		// After a hard fight, sit down and rest like a player until HP is back before doing anything else. They don't use MP.
		final double hpRatio = npc.getCurrentHp() / npc.getMaxHp();
		if (hpRatio < (_resting ? 0.9 : 0.5))
		{
			endPoke(npc);
			
			// Not with a fight still going on around it (someone or a monster hitting it, one it chose not to hit back, a pursuer...): it stays on its feet and drinks a potion.
			if (isFightNear(npc))
			{
				_resting = false;
				if (!standUp(npc))
				{
					FakePlayerPvpManager.getInstance().tryPotion(npc);
					
					// A monster on it: it hits back rather than stand there.
					final Creature monster = getMonsterOnIt(npc);
					if (monster != null)
					{
						npc.addDamageHate(monster, 0, 1);
						setIntention(Intention.ATTACK, monster);
					}
				}
				return;
			}
			
			_resting = true;
			if (!npc.isMoving() && !npc.isMovementDisabled())
			{
				sitDown(npc);
			}
			return;
		}
		_resting = false;
		
		// Rested: up again (that takes a moment, like for a player).
		if (standUp(npc))
		{
			return;
		}
		
		// Talking with another fake player it met: it stands there facing them.
		if (thinkTalk(npc, System.currentTimeMillis()))
		{
			return;
		}
		
		// A necromancer keeps its servitor out: a new one once the fight is over.
		if ((profile != null) && profile.needsServitor() && mayCastSummon(npc, profile, System.currentTimeMillis()) && castOnSelf(npc, null, profile.getSkills(SkillCategory.SUMMON), true, false))
		{
			return;
		}
		
		// Loot of its kills (FakePlayerCanDropItems), picked up like a player does.
		if (pickUpDrops(npc))
		{
			return;
		}
		
		// Duelists and tyrants keep their energy full between fights, like players do.
		if ((profile != null) && (profile.getCharges() < profile.getMaxCharges()) && castOnSelf(npc, null, profile.getSkills(SkillCategory.CHARGE), true, false))
		{
			return;
		}
		
		final long now = System.currentTimeMillis();
		lookForNewFaces(npc, now);
		
		// Taunting a lower level: walking up to them, standing there, hitting them once.
		if (thinkPoke(npc, now))
		{
			return;
		}
		
		// Walking over to another fake player to talk, or someone came over and waits for it.
		if (thinkMeet(npc, now) || waitForVisitor(npc, now))
		{
			return;
		}
		
		// Back from town for its killer, a flagged or karma player (or fake player) passing by, a lower level to taunt, or another fake player to talk to.
		if (lookForRevenge(npc, now) || lookForPvp(npc, now) || lookForPoke(npc, now) || lookForMeeting(npc, now))
		{
			return;
		}
		
		// Hunt the monsters around. After a look that found nothing it waits a little before looking again, instead of scanning every think.
		if (now >= _nextPreyScanTime)
		{
			final Creature prey = findPrey(npc);
			if (prey != null)
			{
				npc.addDamageHate(prey, 0, 1);
				npc.setRunning();
				setIntention(Intention.ATTACK, prey);
				return;
			}
			
			_nextPreyScanTime = now + PREY_SCAN_INTERVAL;
		}
		
		// Nothing to hunt: go back to the hunting ground or look around it.
		final Spawn spawn = npc.getSpawn();
		if ((spawn == null) || npc.isMoving() || npc.isMovementDisabled())
		{
			return;
		}
		
		npc.setRunning();
		final int huntRange = FakePlayerPvpPersonality.of(npc).getHuntRange();
		if (npc.calculateDistance2D(spawn) > huntRange)
		{
			goHome(npc, spawn);
			return;
		}
		
		if (Rnd.get(WANDER_CHANCE) == 0)
		{
			final int radius = Math.max(100, huntRange / 2);
			final int x = (spawn.getX() + Rnd.get(-radius, radius));
			final int y = (spawn.getY() + Rnd.get(-radius, radius));
			final Location destination = GeoEngine.getInstance().getValidLocation(npc.getX(), npc.getY(), npc.getZ(), x, y, npc.getZ(), npc.getInstanceId());
			moveTo(destination.getX(), destination.getY(), destination.getZ());
		}
	}
	
	@Override
	protected void thinkAttack()
	{
		final Attackable npc = getActiveChar();
		if (npc.isDead() || npc.isCoreAIDisabled())
		{
			return;
		}
		_traced = false;

		// Casting (a spell, a Scroll of Escape...): a player still drinks potions meanwhile.
		if (npc.isCastingNow())
		{
			trace(Reason.CASTING, getAttackTarget(), npc.getLastSkillCast(), null);
			// A player comes into sight while it reads its Scroll of Escape to get away from a fight: it doesn't teleport in front of them, it runs on. Leaving a hotzone that rotated out, it goes anyway.
			final Skill casting = npc.getLastSkillCast();
			final FakePlayerPvpProfile castingProfile = npc.getTemplate().getFakePlayerPvpProfile();
			if ((casting != null) && (casting.getId() == SCROLL_OF_ESCAPE) && ((castingProfile == null) || !castingProfile.isEscapeInView()) && FakePlayerPvpManager.isSeenByPlayer(npc))
			{
				npc.abortCast();
				_nextEscapeTime = System.currentTimeMillis() + 3000;
				return;
			}
			
			// Summoning takes 15 seconds: a player that catches up doesn't find it standing there, it runs on (and summons further away).
			if ((casting != null) && isSummonSkill(npc, casting) && isPvpEnemyWithin(npc, SUMMON_ABORT_DISTANCE))
			{
				npc.abortCast();
				return;
			}
			
			if (castingProfile != null)
			{
				FakePlayerPvpManager.getInstance().tryPotion(npc);
				if (castingProfile.isSpotFighter())
				{
					FakePlayerPvpManager.getInstance().tryCpPotion(npc);
				}
			}
			return;
		}
		
		final FakePlayerPvpProfile profile = npc.getTemplate().getFakePlayerPvpProfile();
		if (profile == null)
		{
			super.thinkAttack();
			return;
		}
		
		// A real fight (they hit back, or a monster came) ends a taunt, a walk over to someone and a talk.
		endPoke(npc);
		_meetTarget = null;
		endTalk();
		
		// A sitting player stands up first (and no longer regenerates like one).
		_resting = false;
		if (standUp(npc))
		{
			trace(Reason.STAND_UP, getAttackTarget());
			return;
		}

		// A healer or buffer looks after its party first, even in a fight.
		final FakePlayerParty party = profile.getParty();
		final boolean withPlayers = (party != null) && !party.isFakeOnly();

		// A healer someone is on defends itself first: stops them, heals itself, runs to its tank. It never spends that moment hitting someone.
		if ((party != null) && isPureHealer(profile) && defendHealer(npc, profile, party, System.currentTimeMillis()))
		{
			return;
		}

		if ((party != null) && supportParty(npc, profile, party, true))
		{
			return;
		}
		
		// Drop targets that are dead, gone or out of reach.
		Creature target = npc.getMostHated();
		checkPvpTarget(npc, target);
		for (int i = 0; (target != null) && !isValidTarget(npc, target) && (i < 10); i++)
		{
			npc.stopHating(target);
			target = npc.getMostHated();
			checkPvpTarget(npc, target);
		}
		
		if ((target == null) || !isValidTarget(npc, target))
		{
			trace(Reason.DROP_TARGET_INVALID, target);
			dropTarget(npc);
			return;
		}
		
		// Against several players, the one it can finish first. In a PvP spot, also anyone around that is a better target (someone low, someone after it or its clan mates, its killer, the leader...).
		final long now = System.currentTimeMillis();
		if (isPvpEnemy(target))
		{
			target = chooseFocus(npc, target, now);
			if (profile.isSpotFighter())
			{
				target = chooseSpotFocus(npc, profile, target, now);
			}

			// A tank or support of a party looks after its squishies first: it peels whoever dives them, and doesn't chase into the enemy backline.
			if (party != null)
			{
				final Creature guarded = guardBackline(npc, profile, party, target, now);
				if (guarded == null)
				{
					return;
				}
				target = guarded;

				// A melee damage dealer doesn't run ahead of its party into the enemy (a dagger does: diving is its job).
				if (holdForTank(npc, profile, party, target, now))
				{
					return;
				}
			}

			// An archer or mage that can't see its target shoots another enemy of the fight it sees and reaches, rather than walk around for a line of sight (the party's call still comes first).
			final Creature visible = findVisibleTarget(npc, profile, party, target, now);
			if (visible != null)
			{
				target = visible;
			}
		}

		if (getAttackTarget() != target)
		{
			FakeAiTrace.onTargetChange(npc, getAttackTarget(), target);
			setAttackTarget(target);
		}
		
		if (npc.getTarget() != target)
		{
			npc.setTarget(target);
		}
		
		if (isPvpEnemy(target))
		{
			_pvpTarget = target;
		}
		
		// In a party it stays with its leader: monsters are only fought around the leader.
		final Creature partyLeader = party != null ? party.getLeader() : null;
		if ((partyLeader != null) && (partyLeader != npc))
		{
			if (!isPvpEnemy(target) && ((partyLeader.getInstanceId() != npc.getInstanceId()) || (partyLeader.calculateDistance2D(target) > FakePartyConfig.LEASH_RANGE)))
			{
				trace(Reason.DROP_TARGET_LEASH, target);
				npc.stopHating(target);
				dropTarget(npc);
				return;
			}
		}
		// A PvP spot fighter stays in its spot: it chases someone a little way out of it (PvpSpotChaseOutside), then turns back.
		else if (profile.isSpotFighter())
		{
			if (!_fleeing && !_regrouping && !_returning && isBeyondSpot(profile, target))
			{
				trace(Reason.DROP_TARGET_LEASH, target);
				npc.stopHating(target);
				dropTarget(npc);
				return;
			}
		}
		// Monsters are only hunted around the spawn point, players are chased further (and it runs away as far as it needs to). A fake player of a simulation has no hunting ground.
		else if ((npc.getSpawn() != null) && !profile.isSimulant() && !_fleeing && !_regrouping && !_returning && (npc.calculateDistance2D(npc.getSpawn()) > (isPvpEnemy(target) ? profile.getPersonality().getChaseRange() : profile.getPersonality().getLeashRange())))
		{
			final Spawn spawn = npc.getSpawn();
			// Back to its hunting ground, staying ACTIVE: a MOVE_TO that finds no path never arrives, and the AI would stop thinking.
			trace(Reason.DROP_TARGET_LEASH, target);
			npc.stopHating(target);
			dropTarget(npc);
			goHome(npc, spawn);
			return;
		}
		
		// Give up on a target it hasn't been able to hit for a while (no way to it, a ledge...), but not on the one it runs from.
		if ((_progressTarget != target) || _fleeing || _regrouping)
		{
			_progressTarget = target;
			_progressTime = now;
		}
		else if ((now - _progressTime) > (isPvpEnemy(target) ? PLAYER_STUCK_TIMEOUT : MONSTER_STUCK_TIMEOUT))
		{
			trace(Reason.DROP_TARGET_STUCK, target);
			npc.stopHating(target);
			if (!isPvpEnemy(target))
			{
				_unreachable = target;
				_unreachableUntil = now + UNREACHABLE_IGNORE_TIME;
			}
			dropTarget(npc);
			return;
		}
		
		// A flagged or karma player passing by is better game than a monster (not for one in a party with players: it plays with its party). In a PvP spot, anyone to fight is.
		if (profile.isSpotFighter())
		{
			if (!isPvpEnemy(target) && engageSpotEnemy(npc, profile, now))
			{
				return;
			}
		}
		else
		{
			if (!isPvpEnemy(target) && !withPlayers && !profile.isSimulant())
			{
				lookForNewFaces(npc, now);
			}
			if (!isPvpEnemy(target) && !withPlayers && !profile.isSimulant() && (lookForRevenge(npc, now) || lookForPvp(npc, now)))
			{
				return;
			}
		}

		if (npc.isMoving() && (now < _kiteEndTime))
		{
			trace(Reason.KITE_MOVING, target);
			return; // Let the step back finish.
		}
		
		FakePlayerPvpManager.getInstance().tryPotion(npc);
		
		// A hybrid Soul Hound takes out its mace to fight a melee from a distance, or for a burst of casts from afar, and fights like a mage while it holds it.
		if (profile.getCaster() != null)
		{
			chooseCaster(npc, profile, target, now);
		}
		
		// In Final Form a Kamael fights up close with the transformation's skills, whatever its class.
		final Role role = profile.isTransformed() ? Role.FIGHTER : profile.isCasterHeld() ? Role.MAGE : profile.getRole();
		final boolean mage = role == Role.MAGE;
		_autoAttacker = !mage && !profile.getBuild().isSkillFighter() && !profile.isTransformed();
		final boolean pvp = isPvpEnemy(target);
		final boolean canMove = !npc.isMovementDisabled();
		
		// In a PvP spot it drinks CP potions all fight long, like players.
		if (pvp && profile.isSpotFighter())
		{
			FakePlayerPvpManager.getInstance().tryCpPotion(npc);
		}
		final double hpRatio = npc.getCurrentHp() / npc.getMaxHp();
		final double distance = npc.calculateDistance2D(target);
		final int collision = npc.getTemplate().getCollisionRadius() + target.getTemplate().getCollisionRadius();
		trackChase(target, distance - collision, now);

		// How long an archer or mage may cast before the melee after it reaches it; pressed, it doesn't start what it couldn't finish.
		final long safeCast = pvp && role.isRanged() && !profile.isTransformed() ? getSafeCastTime(npc, target, distance - collision) : Long.MAX_VALUE;
		final boolean pressed = safeCast < PRESSED_TIME;
		
		// Some players run from a fight they are losing.
		if (!pvp)
		{
			_fleeing = false;
			_regrouping = false;
			_returning = false;
		}
		else if (!withPlayers)
		{
			if (thinkFlee(npc, profile, target, hpRatio, now))
			{
				trace(Reason.FLEE, target);
				return;
			}
			if (thinkRegroup(npc, profile, target, hpRatio, now))
			{
				trace(Reason.REGROUP, target);
				return;
			}
		}
		
		// Back from summoning: until it is back in range, it doesn't give up on the fight for being far from its hunting ground.
		if (_returning && ((distance - collision) <= MAGE_RANGE))
		{
			_returning = false;
		}
		
		// Warriors take out their polearm when monsters surround them, tanks and tyrants their bow against a player they can't catch.
		if (profile.getPolearm() != null)
		{
			choosePolearm(npc, profile, target, now);
		}
		if (profile.getBow() != null)
		{
			chooseWeapon(npc, profile, target, distance - collision, now);
		}
		final boolean bowHeld = profile.isBowHeld();
		final boolean polearmHeld = profile.isPolearmHeld();
		
		// With the polearm out its damage is its normal attack, which hits several monsters at once, whatever its class.
		if (polearmHeld)
		{
			_autoAttacker = true;
		}
		
		// A performer plays its PvP performance while it fights a player, its hunting one otherwise. Switching is instant, like a player's toggle.
		if (!profile.getSkills(SkillCategory.PERFORM).isEmpty())
		{
			FakePlayerPvpManager.getInstance().keepPerformance(npc, profile, pvp || FakePlayerPvpManager.isInPvp(npc));
		}
		
		// Take care of itself first: emergency skills, cleansing, heals, buffs. A Totem Warchief with an enemy on it swaps places with a totem away from it first.
		if ((hpRatio < 0.3) && ((pvp && trySpiritWalk(npc, profile, target)) || castOnSelf(npc, target, profile.getSkills(SkillCategory.EMERGENCY), true, true)))
		{
			trace(Reason.SELF_SKILL, target, npc.getLastSkillCast(), "EMERGENCY");
			return;
		}

		if (castCleanse(npc, target, profile))
		{
			trace(Reason.CLEANSE, target, npc.getLastSkillCast(), null);
			return;
		}

		if ((hpRatio < 0.5) && (Rnd.get(100) < 50) && castOnSelf(npc, target, quickHeals(npc, affordable(npc, profile.getSkills(SkillCategory.HEAL), safeCast)), true, true))
		{
			trace(Reason.SELF_SKILL, target, npc.getLastSkillCast(), "HEAL");
			return;
		}

		// Long cooldown buffs (Frenzy, Zealot, Focus Power...) are saved for players. Prophecies on itself and totems only where they pay off (see FakePlayerPvpOraclesWarchiefs).
		if (castOnSelf(npc, target, affordable(npc, profile.getSkills(SkillCategory.BUFF), safeCast), false, pvp))
		{
			trace(Reason.SELF_SKILL, target, npc.getLastSkillCast(), "BUFF");
			return;
		}

		// An archer or mage stops whoever is on its party's healer (root, stun, sleep), from where it stands.
		if (pvp && (party != null) && role.isRanged() && !isPureHealer(profile) && peelForHealer(npc, profile, party, now))
		{
			return;
		}

		// A Totem Warchief fights where its Totem of Flames burns: with it ready, it walks up to plant it by its target.
		if (canMove && !npc.isAttackingNow() && FakePlayerPvpOraclesWarchiefs.wantsCloserForFlames(npc, profile, target, distance - collision, hpRatio))
		{
			trace(Reason.TOTEM_APPROACH, target);
			moveToPawn(target, FakePlayerPvpOraclesWarchiefs.getFlamePlantGap() - 50);
			return;
		}

		// A Kamael goes into Final Form (once an hour) when a PvP gets serious: hurt, or against several players.
		if (pvp && !profile.isTransformed() && ((hpRatio < 0.7) || (countPvpEnemies(npc) >= 2)) && castOnSelf(npc, target, profile.getSkills(SkillCategory.TRANSFORM), true, true))
		{
			trace(Reason.SELF_SKILL, target, npc.getLastSkillCast(), "TRANSFORM");
			return;
		}

		// Monsters can't bring it down: a necromancer summons a new servitor right away.
		if (!pvp && profile.needsServitor() && mayCastSummon(npc, profile, now) && castOnSelf(npc, target, profile.getSkills(SkillCategory.SUMMON), true, false))
		{
			trace(Reason.SELF_SKILL, target, npc.getLastSkillCast(), "SUMMON");
			return;
		}

		// Nothing gets through an invincible player (Sonic Barrier, Force Barrier...): wait it out close by, like a player does.
		if (pvp && target.isInvul())
		{
			trace(Reason.WAIT_INVUL, target);
			final int range = npc.getPhysicalAttackRange();
			if (canMove && !mage && ((distance - collision) > (range + 100)))
			{
				moveToPawn(target, range);
			}
			return;
		}
		
		// A player under Ultimate Defense, Guts, Zealot, Angelic Icon or a magic mirror: once it noticed, it leaves aside what doesn't get through and keeps its distance while it can.
		int defenses = pvp ? getKnownDefenses(target) : 0;
		if (defenses != 0)
		{
			if (_combo != null)
			{
				endCombo(now);
			}

			if (holdOff(npc, target, defenses, mage, role.isRanged() || bowHeld, distance - collision, canMove, now))
			{
				trace(Reason.HOLD_OFF, target);
				return;
			}

			// From out of the reach of one that can't move, an archer or mage uses its damage even though little gets through.
			if ((role.isRanged() || bowHeld) && target.isMovementDisabled())
			{
				defenses &= ~(PHYSICAL_DAMAGE | MAGIC_DAMAGE);
			}
		}

		// An archer against a melee after it shoots while the melee can't reach it before the shot is off, and otherwise steps back as soon as the last shot is off (no pause between steps), at an
		// angle where the way straight back is blocked. So it keeps the melee near the edge of its reach instead of letting it in.
		final boolean archerKite = pvp && canMove && (role == Role.ARCHER) && !bowHeld && isMeleeThreat(target);
		if (archerKite && !npc.isAttackingNow() && ((distance - collision) < ARCHER_KITE_DISTANCE) && (safeCast < (getShotTime(npc) + CAST_MARGIN)))
		{
			if (_combo != null)
			{
				endCombo(now);
			}
			if (pressed && useSkill(npc, target, pickPeel(npc, target, profile, defenses, distance - collision, safeCast, mage), distance, collision, false))
			{
				return;
			}
			if (archerKiteStep(npc, target, now))
			{
				return;
			}
		}
		
		// A pressed archer or mage stops the melee (with what lands before it arrives), even in the middle of a combo, rather than stand through a cast. An archer then steps back and shoots in between; a mage
		// casts what it can finish first (a nuker wins by out-damaging the melee) and only steps back with nothing short enough (below).
		if (!archerKite && pressed && canMove && !npc.isAttackingNow() && ((distance - collision) < PRESSED_KITE_DISTANCE))
		{
			// An archer's combos are its long shots; a mage's are its burst (the Soul Hounds' Soul Vortex chain): it keeps it.
			if ((_combo != null) && !mage)
			{
				endCombo(now);
			}
			if (useSkill(npc, target, pickPeel(npc, target, profile, defenses, distance - collision, safeCast, mage), distance, collision, false))
			{
				return;
			}
			if (!mage && kiteStep(npc, target, now))
			{
				return;
			}
		}

		// Archers and mages stop a player in melee range (root, stun, Aura Flash...), then step back (unless a combo is finishing a stunned target).
		if (!archerKite && canMove && role.isRanged() && (_combo == null) && !npc.isAttackingNow() && ((distance - collision) < profile.getPersonality().getKiteDistance()) && (now >= _nextKiteTime))
		{
			if (pvp && !isDisabled(target) && useSkill(npc, target, pickPeel(npc, target, profile, defenses, distance - collision, safeCast, mage), distance, collision, false))
			{
				return;
			}

			// A Totem Warchief swaps places with a totem away from a player on it, or steps back around its totem so the enemy chases it through the pulses.
			if (((pvp || (hpRatio < 0.5)) && trySpiritWalk(npc, profile, target)) || kiteAroundTotem(npc, target, now))
			{
				trace(Reason.KITE_STEP, target, null, "TOTEM");
				return;
			}

			if (kiteStep(npc, target, now))
			{
				return;
			}
		}

		// #21: an archer with an enemy on it (coming at it, or already there) steps aside once after each shot, keeping its target in range and sight, before the next one: shoot, step, shoot (standing still between shots, it took every hit).
		if (canMove && ((role == Role.ARCHER) || bowHeld) && (_combo == null) && !npc.isAttackingNow() && !_shotStepped && ((now - _lastShotTime) < SHOT_STEP_WINDOW) && isEnemyOnMe(npc, RELOAD_STEP_THREAT))
		{
			_shotStepped = true;
			final Location point = pickRetreat(npc, target, RELOAD_STEP, target, (npc.getPhysicalAttackRange() + collision) - 50, null);
			if (point != null)
			{
				_progressTime = now;
				trace(Reason.KITE_STEP, target, null, "RELOAD");
				npc.setRunning();
				moveTo(point.getX(), point.getY(), point.getZ());
				_kiteEndTime = now + Math.min(800, (long) ((npc.calculateDistance2D(point) * 1000.0) / Math.max(1, npc.getMoveSpeed())));
				return;
			}
		}

		// Catching up: a speed buff on a player getting away, then a gap closer (Rush, Shadow Step).
		if (canMove && !role.isRanged() && !bowHeld)
		{
			if (pvp && isChasing(target, now) && isWorthGapCloser(npc, target) && castOnSelf(npc, target, profile.getSkills(SkillCategory.MOVE), false, true))
			{
				trace(Reason.SPRINT, target, npc.getLastSkillCast(), null);
				return;
			}

			if ((pvp ? isChasing(target, now) && isWorthGapCloser(npc, target) : (Rnd.get(100) < 20)) && useRush(npc, profile, target, distance, collision, pvp))
			{
				trace(Reason.RUSH, target);
				return;
			}
		}

		// Duelists and tyrants recharge their energy when they are running low (big energy skills need 2-4 charges).
		if ((profile.getMaxCharges() > 0) && (profile.getCharges() < profile.getMaxCharges()) && ((profile.getCharges() < Math.min(4, profile.getMaxCharges())) || (Rnd.get(100) < 20)) && castOnSelf(npc, target, profile.getSkills(SkillCategory.CHARGE), true, pvp))
		{
			trace(Reason.SELF_SKILL, target, npc.getLastSkillCast(), "CHARGE");
			return;
		}
		
		// A spoiler spoils the monster it fights first (Spoil, Spoil Crush), so it can be swept.
		if (!pvp && isSpoilable(target) && useSkill(npc, target, pickSkill(npc, target, profile.getSkills(SkillCategory.SPOIL), false, -1, false, 0), distance, collision, canMove))
		{
			return;
		}
		
		// #29: an archer in a PvP shoots plain arrows while they land, buffed archers deal most of their damage that way; its skills are for a target its arrows miss (peeling with Stun Shot is above, in the step back).
		final boolean arrowsFirst = pvp && (role == Role.ARCHER) && (getHitChance(npc, target) >= ARCHER_ARROW_HIT_CHANCE);

		// Combos: the skill chains a practiced player of this class plays (with its weapon, not with the spare bow or polearm).
		if (!bowHeld && !polearmHeld && !arrowsFirst && (defenses == 0) && (!pressed || mage) && playCombo(npc, profile, target, pvp, distance, collision, canMove, now))
		{
			trace(Reason.COMBO_STEP, target, null, _combo != null ? _combo.getName() : null);
			return;
		}
		
		// Stuns, roots and debuffs are mostly for players (and half as often for a class that fights with its normal attack).
		final double reach = distance - collision;
		final FakePlayerPvpPersonality personality = profile.getPersonality();
		final int debuffChance = pvp ? (_autoAttacker ? (personality.getPvpDebuffChance() / 2) : personality.getPvpDebuffChance()) : 5;
		// With the bow out it only uses what reaches the target from where it stands, instead of running in.
		if (!arrowsFirst && (Rnd.get(100) < debuffChance) && useSkill(npc, target, inReach(pickSkill(npc, target, affordable(npc, profile.getSkills(SkillCategory.DEBUFF), safeCast), true, (role.isRanged() || bowHeld) ? reach : -1, pvp, defenses), bowHeld, reach), distance, collision, canMove))
		{
			return;
		}
		
		// Attack skills, best first. Mages cast whenever they can, Gladiators, Tyrants and daggers fight with their skills, and the other classes (tanks, archers, most warriors) auto attack and use a skill now and then, like players.
		final int skillChance = _autoAttacker ? (pvp ? personality.getAutoAttackPvpSkillChance() : personality.getAutoAttackSkillChance()) : (pvp ? personality.getPvpSkillChance() : personality.getSkillChance());
		// #29: an archer whose arrows would miss uses its skills every time (they don't miss), one whose arrows land none.
		final boolean archerSkills = pvp && (role == Role.ARCHER);
		if ((archerSkills ? !arrowsFirst : (mage || (Rnd.get(100) < skillChance))) && useSkill(npc, target, inReach(pickSkill(npc, target, affordable(npc, profile.getSkills(SkillCategory.ATTACK), safeCast), false, (role.isRanged() || bowHeld) ? reach : -1, pvp, defenses), bowHeld, reach), distance, collision, canMove))
		{
			return;
		}
		
		// Mages never melee: between spells they stay in casting range.
		if (mage)
		{
			// Pressed with nothing short enough to cast, it steps back; cornered, it casts anyway.
			if (pressed)
			{
				if (canMove && kiteStep(npc, target, now))
				{
					return;
				}
				if (useSkill(npc, target, inReach(pickSkill(npc, target, profile.getSkills(SkillCategory.ATTACK), false, reach, pvp, defenses), false, reach), distance, collision, false))
				{
					return;
				}
			}

			if (canMove && (distance > (MAGE_RANGE + collision)))
			{
				trace(Reason.MAGE_POSITION, target);
				moveToPawn(target, MAGE_RANGE);
			}
			else if (canMove && !GeoEngine.getInstance().canSeeTarget(npc, target))
			{
				trace(Reason.FIRING_POSITION, target, null, "MAGE");
				moveToSee(npc, target, MAGE_RANGE);
			}
			else
			{
				trace(Reason.MAGE_HOLD, target);
			}
			return;
		}

		// Normal attack.
		final int range = npc.getPhysicalAttackRange();
		final boolean inRange = distance <= (range + collision);
		if (!inRange || !GeoEngine.getInstance().canSeeTarget(npc, target))
		{
			trace(inRange ? Reason.FIRING_POSITION : Reason.APPROACH_ATTACK, target, null, inRange ? "ATTACK" : null);
			if (canMove)
			{
				if (inRange)
				{
					moveToSee(npc, target, range);
				}
				else
				{
					moveToPawn(target, range);
				}
			}
			return;
		}

		trace(Reason.AUTO_ATTACK, target);
		_progressTime = now;
		if (!npc.isAttackingNow())
		{
			noticeDefenses(target, false);
		}
		_actor.doAttack(target);
		if ((role == Role.ARCHER) || bowHeld)
		{
			markShot(); // #21.
		}
	}
	
	@Override
	protected void onActionAttacked(Creature attacker)
	{
		// A party member's stray hit: no fight over it.
		if (FakePartyManager.getInstance().isSameGroup(getActiveChar(), attacker))
		{
			return;
		}
		
		FakePlayerPvpManager.getInstance().onFakePlayerAttacked(getActiveChar(), attacker);
		
		// It chose not to hit them back: it goes on with what it was doing.
		if (FakePlayerPvpManager.isRefusing(getActiveChar(), attacker))
		{
			return;
		}

		// Already fighting the one it chose (the weakest of several, a diver on its healer...): a hit from someone else is noted, but it keeps its target, like a player does. The base AI would turn it around on every hit.
		final Creature current = getAttackTarget();
		if ((getIntention() == Intention.ATTACK) && (current != null) && (current != attacker) && !current.isAlikeDead() && isValidTarget(getActiveChar(), current))
		{
			getActiveChar().addDamageHate(attacker, 0, 1);
			return;
		}

		super.onActionAttacked(attacker);
	}
	
	/**
	 * @return {@code true} while it rests after a hard fight (it regenerates like a sitting player)
	 */
	public boolean isResting()
	{
		return _resting;
	}
	
	@Override
	protected void onActionFinishCasting()
	{
		super.onActionFinishCasting();
		
		// A player chains the next skill a moment after the last one, not a whole AI tick later (Shadow Step leaves it ACTIVE).
		if ((getIntention() == Intention.ATTACK) || (getIntention() == Intention.ACTIVE))
		{
			ThreadPool.schedule(this::onActionThink, Rnd.get(SKILL_CHAIN_MIN_DELAY, SKILL_CHAIN_MAX_DELAY));
		}
	}
	
	@Override
	protected void onActionDeath()
	{
		endPoke(getActiveChar());
		_meetTarget = null;
		endTalk();
		_fleeing = false;
		_resting = false;
		_regrouping = false;
		_returning = false;
		
		// Its servitor goes away with it.
		FakePlayerPvpManager.getInstance().unsummonServitor(getActiveChar());
		
		// Its party hears about it.
		FakePartyManager.getInstance().onFakeDeath(getActiveChar());
		
		// Whoever comes by later sees a body lying down, not sitting.
		final FakePlayerHolder holder = getActiveChar().getTemplate().getFakePlayerInfo();
		if (holder != null)
		{
			holder.setSitting(false);
		}
		
		super.onActionDeath();
	}
	
	/**
	 * Some players run from a fight they are losing (see {@link FakePlayerPvpProfile#isRunner()}): they use their emergency and speed skills, stop a pursuer on their heels (root, stun, Trick...), drink potions and run, then read a Scroll of Escape once they got away
	 * and no player sees them (see {@link FakePlayerPvpManager#isSeenByPlayer}). Held
	 * in place they fight back, and caught after running too long (or cornered) they turn around and fight to the end.
	 * @param npc the fake player
	 * @param profile its profile
	 * @param target the player it fights
	 * @param hpRatio its HP ratio
	 * @param now the current time
	 * @return {@code true} if it acted this tick
	 */
	private boolean thinkFlee(Attackable npc, FakePlayerPvpProfile profile, Creature target, double hpRatio, long now)
	{
		if (!_fleeing)
		{
			if (!shouldFlee(npc, profile, target, hpRatio))
			{
				return false;
			}
			
			_fleeing = true;
			_fleeStart = now;
			_nextFleeStep = 0;
			_nextEscapeTime = 0;
			endCombo(now);
			FakePlayerPvpManager.getInstance().onFakePlayerFlee(npc);
		}
		
		final Creature closest = getClosestPvpEnemy(npc);
		final Creature pursuer = closest != null ? closest : target;
		final double pursuerDistance = npc.calculateDistance2D(pursuer);
		final int pursuerCollision = npc.getTemplate().getCollisionRadius() + pursuer.getTemplate().getCollisionRadius();
		final double pursuerGap = pursuerDistance - pursuerCollision;
		
		// Held in place (root, stun...): cleanse it if it can, otherwise fight back this tick.
		if (npc.isMovementDisabled())
		{
			return castCleanse(npc, target, profile);
		}
		
		// Caught after running too long: turn around and fight to the end.
		if ((pursuerGap < PEEL_DISTANCE) && ((now - _fleeStart) > FLEE_TIMEOUT))
		{
			_fleeing = false;
			_lastStand = true;
			return false;
		}
		
		// Survive on the run: emergency skill, speed buff.
		if ((hpRatio < 0.3) && castOnSelf(npc, target, profile.getSkills(SkillCategory.EMERGENCY), true, true))
		{
			return true;
		}
		
		if (castOnSelf(npc, target, profile.getSkills(SkillCategory.MOVE), false, true))
		{
			return true;
		}
		
		// A pursuer on its heels: stop it before running on.
		if ((pursuerGap < PEEL_DISTANCE) && !isDisabled(pursuer) && useSkill(npc, pursuer, pickPeel(npc, pursuer, profile, getKnownDefenses(pursuer), pursuerGap), pursuerDistance, pursuerCollision, false))
		{
			return true;
		}
		
		// In a PvP spot it doesn't leave: once it got away it gets its breath back (potions, then it rests if nobody is around) and comes back to the fight.
		if (profile.isSpotFighter())
		{
			if (pursuerGap > ESCAPE_DISTANCE)
			{
				giveUpPvp(npc);
				return true;
			}
		}
		// A Blessed Scroll of Escape (0.2 seconds): read as soon as it has a little room, seen or not, like a player getting out of a fight.
		else if (FakePlayerPvpConfig.ESCAPE_SCROLL && profile.hasBlessedEscape() && (pursuerGap > FakePlayerPvpConfig.BLESSED_ESCAPE_DISTANCE) && (now >= _nextEscapeTime) && readEscapeScroll(npc, target, now, true, true))
		{
			return true;
		}
		
		// Got away.
		if ((pursuerGap > ESCAPE_DISTANCE) && !profile.isSpotFighter())
		{
			// A Scroll of Escape: 20 seconds, a pursuer that catches up can still stun it or finish it. Never in front of a player: it runs on until nobody sees it.
			if (FakePlayerPvpConfig.ESCAPE_SCROLL)
			{
				if (!profile.hasBlessedEscape() && (now >= _nextEscapeTime) && !FakePlayerPvpManager.isSeenByPlayer(npc) && readEscapeScroll(npc, target, now, false, false))
				{
					return true;
				}
			}
			else
			{
				// No scroll: the fight is over, it rests (the player can start it again).
				giveUpPvp(npc);
				return true;
			}
		}
		
		// Keep running.
		if (!npc.isMoving() || (now >= _nextFleeStep))
		{
			if (!runAway(npc, pursuer))
			{
				// Cornered: nothing left but to fight.
				_fleeing = false;
				_lastStand = true;
				return false;
			}
			_nextFleeStep = now + 1200;
		}
		return true;
	}
	
	/**
	 * A necromancer whose servitor died (or is worn down to nothing by Transfer Pain) in a PvP does what a player does: it runs off, summons a new one (15 seconds) once the players it fights are far enough, switches Transfer Pain back on and goes back to the fight. A player catching up interrupts the summon
	 * (see {@link #thinkAttack}) and it runs on. Held in place it fights back, and cornered or still chased after {@link #REGROUP_TIMEOUT} it fights on without a servitor for a while.
	 * @param npc the fake player
	 * @param profile its profile
	 * @param target the player it fights
	 * @param hpRatio its HP ratio
	 * @param now the current time
	 * @return {@code true} if it acted this tick
	 */
	private boolean thinkRegroup(Attackable npc, FakePlayerPvpProfile profile, Creature target, double hpRatio, long now)
	{
		// Only a servitor that takes its damage (Transfer Pain) is worth leaving a fight for: another one (a Dark Avenger's panther) is summoned again once the fight is over.
		if (profile.getSkills(SkillCategory.LINK).isEmpty())
		{
			_regrouping = false;
			return false;
		}
		
		// A servitor Transfer Pain has worn down takes no more of its damage: sent away, a fresh one is summoned like for a dead one.
		final Npc servitor = profile.getServitor();
		if (!_regrouping && (now >= _nextRegroupTime) && (servitor != null) && !servitor.isDead() && (servitor.getCurrentHp() < (servitor.getMaxHp() * SPENT_SERVITOR_HP)))
		{
			FakePlayerPvpManager.getInstance().unsummonServitor(npc);
		}
		
		if (!profile.needsServitor())
		{
			// Summoned: Transfer Pain on, back to the fight.
			if (_regrouping)
			{
				_regrouping = false;
				_returning = true;
				FakePlayerPvpManager.getInstance().switchLink(npc, profile, true);
			}
			return false;
		}
		
		if (!_regrouping)
		{
			if (now < _nextRegroupTime)
			{
				return false;
			}
			
			_regrouping = true;
			_regroupStart = now;
			_nextFleeStep = 0;
			endCombo(now);
		}
		
		final Creature closest = getClosestPvpEnemy(npc);
		final Creature pursuer = closest != null ? closest : target;
		final double pursuerDistance = npc.calculateDistance2D(pursuer);
		final int pursuerCollision = npc.getTemplate().getCollisionRadius() + pursuer.getTemplate().getCollisionRadius();
		final double pursuerGap = pursuerDistance - pursuerCollision;
		
		// Held in place (root, stun...): cleanse it if it can, otherwise fight back this tick.
		if (npc.isMovementDisabled())
		{
			return castCleanse(npc, target, profile);
		}
		
		// Can't get away: it fights on without a servitor for a while.
		if ((now - _regroupStart) > REGROUP_TIMEOUT)
		{
			stopRegroup(now);
			return false;
		}
		
		if ((hpRatio < 0.3) && castOnSelf(npc, target, profile.getSkills(SkillCategory.EMERGENCY), true, true))
		{
			return true;
		}
		
		// Far enough: the new servitor.
		if ((pursuerGap > SUMMON_SAFE_DISTANCE) && castOnSelf(npc, target, profile.getSkills(SkillCategory.SUMMON), true, true))
		{
			return true;
		}
		
		if (castOnSelf(npc, target, profile.getSkills(SkillCategory.MOVE), false, true))
		{
			return true;
		}
		
		// A pursuer on its heels: stop it before running on.
		if ((pursuerGap < PEEL_DISTANCE) && !isDisabled(pursuer) && useSkill(npc, pursuer, pickPeel(npc, pursuer, profile, getKnownDefenses(pursuer), pursuerGap), pursuerDistance, pursuerCollision, false))
		{
			return true;
		}
		
		// Not far enough yet: keep running.
		if ((pursuerGap <= SUMMON_SAFE_DISTANCE) && (!npc.isMoving() || (now >= _nextFleeStep)))
		{
			if (!runAway(npc, pursuer))
			{
				// Cornered.
				stopRegroup(now);
				return false;
			}
			_nextFleeStep = now + 1200;
		}
		return true;
	}
	
	private void stopRegroup(long now)
	{
		_regrouping = false;
		_nextRegroupTime = now + REGROUP_RETRY;
	}
	
	/** It arrives with its servitor (see FakePlayerPvpManager#spawnFromTemplate): no summon cast this long after it spawned. */
	private static final long SPAWN_SUMMON_GRACE = 3000;
	/** A summon cast longer than this (Summon Dark Panther: 15 seconds) isn't started with an enemy this close. */
	private static final int LONG_SUMMON_CAST = 5000;
	private static final int LONG_SUMMON_SAFE_DISTANCE = 2000;

	/**
	 * @param npc the fake player
	 * @param profile its profile
	 * @param now the current time
	 * @return {@code false} while its spawn is still bringing its servitor, or for a long summon cast with an enemy close: a player doesn't stand still summoning for seconds as a fight starts
	 */
	private static boolean mayCastSummon(Attackable npc, FakePlayerPvpProfile profile, long now)
	{
		if ((now - profile.getSpawnTime()) < SPAWN_SUMMON_GRACE)
		{
			return false;
		}

		final List<Skill> summons = profile.getSkills(SkillCategory.SUMMON);
		if (summons.isEmpty() || (summons.get(0).getHitTime() <= LONG_SUMMON_CAST))
		{
			return true;
		}
		return !isPvpEnemyWithin(npc, LONG_SUMMON_SAFE_DISTANCE) && !FakeSimulationManager.isRunEnemyWithin(npc, LONG_SUMMON_SAFE_DISTANCE);
	}

	/**
	 * @return {@code true} if {@code skill} is one of its servitor summons
	 */
	private static boolean isSummonSkill(Attackable npc, Skill skill)
	{
		final FakePlayerPvpProfile profile = npc.getTemplate().getFakePlayerPvpProfile();
		return (profile != null) && profile.getSkills(SkillCategory.SUMMON).contains(skill);
	}
	
	/**
	 * @return {@code true} if a player (or summon) it is fighting is within {@code range} of it, collisions excluded
	 */
	private static boolean isPvpEnemyWithin(Attackable npc, int range)
	{
		final Creature enemy = getClosestPvpEnemy(npc);
		return (enemy != null) && ((npc.calculateDistance2D(enemy) - npc.getTemplate().getCollisionRadius() - enemy.getTemplate().getCollisionRadius()) < range);
	}
	
	/**
	 * A player doesn't sit down to rest in the middle of a fight. Its target isn't all there is to it: one it chose not to hit back (see {@link FakePlayerPvpManager#onFakePlayerAttacked}), one it lost (out of chase range, couldn't hit for a while) or a pursuer it
	 * stopped running from still fights it.
	 * @param npc the fake player
	 * @return {@code true} if a player (or summon, or fake player) within {@link #THREAT_RANGE} fights it: it hates them (they hit it, or it fights them), or they have it as target in a fight
	 */
	private static boolean isPvpThreatNear(Attackable npc)
	{
		if (npc.isInsideZone(ZoneId.PEACE))
		{
			return false;
		}
		
		for (AggroInfo info : npc.getAggroList().values())
		{
			if ((info.getHate() > 0) && isThreat(npc, info.getAttacker()))
			{
				return true;
			}
		}
		
		for (Creature creature : World.getInstance().getVisibleObjectsInRange(npc, Creature.class, THREAT_RANGE))
		{
			if ((creature.getTarget() == npc) && creature.isInCombat() && isThreat(npc, creature))
			{
				return true;
			}
		}
		return false;
	}
	
	/**
	 * @param npc a fake player
	 * @return {@code true} if a fight goes on around it: a PvP threat ({@link #isPvpThreatNear}), or a monster that has it as its target and fights. A fake player never sits down to rest then.
	 */
	private static boolean isFightNear(Attackable npc)
	{
		if (isPvpThreatNear(npc))
		{
			return true;
		}
		
		return getMonsterOnIt(npc) != null;
	}
	
	/**
	 * @param npc a fake player
	 * @return a monster (not a fake player) that has it as its target and fights, {@code null} if none
	 */
	private static Creature getMonsterOnIt(Attackable npc)
	{
		for (Creature creature : World.getInstance().getVisibleObjectsInRange(npc, Creature.class, THREAT_RANGE))
		{
			if ((creature.getTarget() == npc) && creature.isMonster() && !creature.isFakePlayer() && !creature.isAlikeDead() && (creature.isInCombat() || creature.isAttackingNow() || creature.isCastingNow()))
			{
				return creature;
			}
		}
		return null;
	}
	
	/**
	 * @param npc the fake player
	 * @param creature a creature that fights it
	 * @return {@code true} if {@code creature} is a player (or summon, or fake player) within {@link #THREAT_RANGE} of it that can still get at it: alive, visible, in its instance, out of town, and neither of its party nor of its clan
	 */
	private static boolean isThreat(Attackable npc, Creature creature)
	{
		if ((creature == null) || (creature == npc) || !isPvpEnemy(creature) || creature.isAlikeDead() || !creature.isSpawned() || creature.isInvisible() || (creature.getInstanceId() != npc.getInstanceId()) || creature.isInsideZone(ZoneId.PEACE))
		{
			return false;
		}
		
		return npc.isInsideRadius2D(creature, THREAT_RANGE) && !FakePartyManager.getInstance().isSameGroup(npc, creature) && !FakeClanManager.getInstance().isFriend(npc, creature);
	}
	
	/**
	 * @param npc the fake player
	 * @param profile its profile
	 * @param target the player it fights
	 * @param hpRatio its HP ratio
	 * @return {@code true} if it should leave this fight: any fake player against a player {@link FakePlayerPvpConfig#OUTLEVELED_DIFFERENCE} levels above it, a runner when low on HP while its enemy is still in good shape, or outnumbered
	 */
	private boolean shouldFlee(Attackable npc, FakePlayerPvpProfile profile, Creature target, double hpRatio)
	{
		// A class transfer challenge opponent fights to the end.
		if (_lastStand || profile.isTrialDuelist())
		{
			return false;
		}
		
		// Every fake player tries to get away from a player (or fake player) far above its level (who gets nothing for the kill anyway).
		final Creature player = target.isPvpFakePlayer() ? target : target.asPlayer();
		if ((FakePlayerPvpConfig.OUTLEVELED_DIFFERENCE > 0) && (player != null) && (player.getLevel() >= (npc.getLevel() + FakePlayerPvpConfig.OUTLEVELED_DIFFERENCE)))
		{
			return true;
		}
		
		// Only runners leave a fight they are losing.
		if (!profile.isRunner())
		{
			return false;
		}
		
		final double targetHpRatio = target.getCurrentHp() / Math.max(1, target.getMaxHp());
		if ((hpRatio < 0.25) && (targetHpRatio > (hpRatio + 0.25)))
		{
			return true;
		}
		
		return (hpRatio < 0.5) && (countPvpEnemies(npc) >= 2);
	}
	
	/**
	 * Runs straight away from {@code pursuer}, or at an angle when a wall is in the way.
	 * @return {@code true} if it found somewhere to run to
	 */
	private boolean runAway(Attackable npc, Creature pursuer)
	{
		// In a PvP spot it runs around inside it, not off into the wild.
		final FakePlayerPvpProfile profile = npc.getTemplate().getFakePlayerPvpProfile();
		final PvpSpotZone spot = (profile != null) && profile.isSpotFighter() ? PvpSpotManager.getZone(profile.getPvpSpotId()) : null;
		// #20: out in the open, away from all the enemies around, not only from its pursuer.
		if (spot == null)
		{
			final Location destination = pickRetreat(npc, pursuer, FLEE_STEP, null, 0, null);
			if ((destination != null) && (npc.calculateDistance2D(destination) >= (FLEE_STEP / 3)))
			{
				npc.setRunning();
				moveTo(destination.getX(), destination.getY(), destination.getZ());
				return true;
			}
		}

		final double away = Math.atan2(npc.getY() - pursuer.getY(), npc.getX() - pursuer.getX());
		for (int pass = spot != null ? 0 : 1; pass < 2; pass++)
		{
			for (double turn : FLEE_TURNS)
			{
				final double angle = away + turn;
				final int x = npc.getX() + (int) (Math.cos(angle) * FLEE_STEP);
				final int y = npc.getY() + (int) (Math.sin(angle) * FLEE_STEP);
				final Location destination = GeoEngine.getInstance().getValidLocation(npc.getX(), npc.getY(), npc.getZ(), x, y, npc.getZ(), npc.getInstanceId());
				if ((pass == 0) && !spot.isInsideZone(destination.getX(), destination.getY(), destination.getZ()) && (spot.getDistanceToZone(destination.getX(), destination.getY()) > SPOT_FLEE_OUTSIDE))
				{
					continue;
				}
				
				if (npc.calculateDistance2D(destination) >= (FLEE_STEP / 3))
				{
					npc.setRunning();
					moveTo(destination.getX(), destination.getY(), destination.getZ());
					return true;
				}
			}
		}
		
		return false;
	}
	
	/**
	 * Starts reading a Scroll of Escape. When it finishes, the fake player teleports away (see {@link FakePlayerPvpManager#onFakePlayerEscaped}).
	 * @param npc the fake player
	 * @param target what it targets afterwards ({@code null} for nothing)
	 * @param now the current time
	 * @param blessed {@code true} for a Blessed Scroll of Escape (0.2 seconds), {@code false} for a normal one (20 seconds)
	 * @param inView {@code true} if it goes even in front of players (a blessed scroll, leaving a hotzone), {@code false} if a player seeing it stops it
	 * @return {@code true} if it started reading
	 */
	private boolean readEscapeScroll(Attackable npc, Creature target, long now, boolean blessed, boolean inView)
	{
		final Skill scroll = SkillData.getInstance().getSkill(blessed ? BLESSED_SCROLL_OF_ESCAPE : SCROLL_OF_ESCAPE, 1);
		if ((scroll == null) || npc.isSkillDisabled(scroll))
		{
			return false;
		}

		// A fake player of a simulation stays in it until its run ends.
		final FakePlayerPvpProfile profile = npc.getTemplate().getFakePlayerPvpProfile();
		if ((profile != null) && profile.isSimulant())
		{
			return false;
		}

		if (profile != null)
		{
			profile.setEscapeInView(inView);
		}
		trace(Reason.ESCAPE, target);

		_nextEscapeTime = now + 5000; // Another try soon if a stun stops this one.
		clientStopMoving(null);
		npc.setTarget(npc);
		npc.doCast(scroll);
		npc.setTarget(target);
		return true;
	}
	
	/**
	 * Ends the PvP it ran away from: it forgets the players it was fighting and goes back to its own business.
	 */
	private void giveUpPvp(Attackable npc)
	{
		for (AggroInfo info : npc.getAggroList().values())
		{
			final Creature enemy = info.getAttacker();
			if ((enemy != null) && isPvpEnemy(enemy))
			{
				info.stopHate();
			}
		}
		
		_fleeing = false;
		_focus = null;
		dropTarget(npc);
	}
	
	/**
	 * @return the closest player (or summon) it is fighting, {@code null} if there is none
	 */
	private static Creature getClosestPvpEnemy(Attackable npc)
	{
		Creature closest = null;
		double closestDistance = Double.MAX_VALUE;
		for (AggroInfo info : npc.getAggroList().values())
		{
			final Creature enemy = info.getAttacker();
			if ((enemy == null) || !isPvpEnemy(enemy) || (info.getHate() < FakePlayerPvpManager.PVP_HATE) || !isValidTarget(npc, enemy))
			{
				continue;
			}
			
			final double distance = npc.calculateDistance2D(enemy);
			if (distance < closestDistance)
			{
				closest = enemy;
				closestDistance = distance;
			}
		}
		
		return closest;
	}
	
	/**
	 * @return how many players it is fighting close by
	 */
	private static int countPvpEnemies(Attackable npc)
	{
		int count = 0;
		for (AggroInfo info : npc.getAggroList().values())
		{
			final Creature enemy = info.getAttacker();
			if ((enemy != null) && isPvpEnemy(enemy) && (info.getHate() >= FakePlayerPvpManager.PVP_HATE) && isValidTarget(npc, enemy) && (npc.calculateDistance2D(enemy) < 1200))
			{
				count++;
			}
		}
		
		return count;
	}
	
	/** Enemies of the fight this far away are weighed as targets (in a party, not only the ones that hit it). */
	private static final int FOCUS_SCAN_RANGE = 1500;
	/** How much more an enemy healer is worth as a target, and an enemy buffer, than a damage dealer (on a score where 1 is full HP). */
	private static final double HEALER_PRIORITY = 0.6;
	private static final double SUPPORT_PRIORITY = 0.25;
	/** How much more a target is worth for each party member already on it (focus fire), up to {@link #FOCUS_FIRE_MAX}. */
	private static final double FOCUS_FIRE_BONUS = 0.2;
	private static final double FOCUS_FIRE_MAX = 0.6;
	/** An enemy called for the party (see {@link FakePlayerParty#getFocus}) stays called this long, and calling it again extends it. */
	private static final long PARTY_FOCUS_TIME = 10000;
	/** An enemy tank is worth this much less as a target (a dagger's own penalty is bigger). */
	private static final double TANK_PENALTY = 0.35;
	/** An archer or mage weighs the enemies of the fight this far away (to see the backline behind a tank). */
	private static final int RANGED_FOCUS_SCAN_RANGE = 1800;
	/** A dagger weighs an enemy healer this many times more, a mage or archer this much more, and a tank this much less. */
	private static final double ASSASSIN_HEALER_FACTOR = 2;
	private static final double ASSASSIN_SQUISHY_PRIORITY = 0.3;
	private static final double ASSASSIN_TANK_PENALTY = 0.5;
	/** An enemy this low is called for the party to finish. */
	private static final double FINISH_CALL_HEALTH = 0.3;
	/** A call gets this long to bring its target down by this much of its HP and CP; otherwise it is dropped, and that target isn't called again (and is worth less) for this long. */
	private static final long CALL_PROGRESS_TIME = 8000;
	private static final double CALL_MIN_LOSS = 0.2;
	private static final long CALL_DROP_TIME = 10000;
	private static final double DROPPED_CALL_PENALTY = 0.5;
	/** The enemy that hurt the party most is worth up to this much more; it gets all of it once it dealt this many times its own max HP to the party. */
	private static final double THREAT_MAX = 0.4;
	private static final double THREAT_HP_SCALE = 0.75; // #7: it was 2, which only counted once a fight was decided.
	/** #4: an enemy on the party's healer, or on another squishy of it, is worth this much more (answered at any range). */
	private static final double PRESSURE_ON_HEALER = 0.35;
	private static final double PRESSURE_ON_SQUISHY = 0.15;
	/** #7: an archer or mage weighs an enemy mage or archer this much more. */
	private static final double RANGED_SQUISHY_PRIORITY = 0.15;
	/** An enemy healer this close to a low enemy can heal it back up: it is called instead. */
	private static final int ENEMY_HEAL_REACH = 900;

	/**
	 * In a fight against several players, a player goes for the one it can finish first: low on HP, close by, never an invincible one. In a party it plays like a party:
	 * <ul>
	 * <li>It goes for the enemy its party was called to kill (see {@link FakePlayerParty#getFocus}), unless it can't hurt it now (Ultimate Defense and the like) or it is far.</li>
	 * <li>It weighs every enemy of the fight, not only the ones that hit it: a healer first, then a buffer, and the ones its party already hits (focus fire).</li>
	 * <li>The enemy healer it goes for, or an enemy about to fall, is called for the whole party.</li>
	 * </ul>
	 * It sticks to its choice for a while.
	 * @param npc the fake player
	 * @param mostHated the player it hates most
	 * @param now the current time
	 * @return the player to fight
	 */
	private Creature chooseFocus(Attackable npc, Creature mostHated, long now)
	{
		final FakePlayerPvpProfile profile = npc.getTemplate().getFakePlayerPvpProfile();
		final FakePlayerParty party = profile != null ? profile.getParty() : null;

		// A call that doesn't bring its target down (a healer heals it through) is dropped: the party turns to someone it can kill.
		if (party != null)
		{
			final double startHealth = party.getFocusStartHealth();
			final Creature dropped = party.dropFailedFocus(now, CALL_PROGRESS_TIME, CALL_MIN_LOSS, CALL_DROP_TIME);
			if (dropped != null)
			{
				FakeAiTrace.onCallDropped(npc, dropped, startHealth, getHealth(dropped));
				if (_focus == dropped)
				{
					_focus = null;
				}
			}
		}

		// The party's call first: everyone on the same enemy. Not a healer: it keeps healing, and only hits what is close to it. Not a tank either: it guards its squishies (see guardBackline), and only calls for the others.
		final boolean healer = (profile != null) && isPureHealer(profile);
		final boolean tank = (profile != null) && !profile.isTransformed() && (profile.getRole() == Role.TANK);
		final Creature called = (party != null) && !healer && !tank ? party.getFocus(now) : null;
		if ((called != null) && isPvpEnemy(called) && !called.isInvul() && !isPlayedAround(npc, called) && (npc.calculateDistance2D(called) <= getFocusScanRange(npc)) && isValidTarget(npc, called))
		{
			if (!hates(npc, called, FakePlayerPvpManager.PVP_HATE))
			{
				npc.addDamageHate(called, 0, FakePlayerPvpManager.PVP_HATE);
			}
			_focus = called;
			_focusUntil = now + FOCUS_TIME;
			return called;
		}

		final Creature focus = _focus;
		if ((focus != null) && (now < _focusUntil) && !focus.isInvul() && !isPlayedAround(npc, focus) && isPvpEnemy(focus) && hates(npc, focus, FakePlayerPvpManager.PVP_HATE) && isValidTarget(npc, focus))
		{
			return focus;
		}

		_focus = null;
		Creature best = mostHated;
		double bestScore = focusScore(npc, party, mostHated);
		for (Creature enemy : getFightEnemies(npc, party))
		{
			if (enemy == mostHated)
			{
				continue;
			}

			// Clearly better only, so it doesn't flip between two.
			final double score = focusScore(npc, party, enemy);
			if (score < (bestScore - 0.25))
			{
				best = enemy;
				bestScore = score;
			}
		}

		if (best != mostHated)
		{
			if (!hates(npc, best, FakePlayerPvpManager.PVP_HATE))
			{
				npc.addDamageHate(best, 0, FakePlayerPvpManager.PVP_HATE);
			}
			_focus = best;
			_focusUntil = now + FOCUS_TIME;
		}

		// An enemy healer, or one about to fall: the whole party on it. Not one its healer can heal back up: the healer first.
		if ((party != null) && !healer)
		{
			Creature call = null;
			if (isHealer(best))
			{
				call = best;
			}
			else if (getHealth(best) < FINISH_CALL_HEALTH)
			{
				final Creature enemyHealer = findEnemyHealerNear(npc, best, ENEMY_HEAL_REACH);
				call = (enemyHealer != null) && (npc.calculateDistance2D(enemyHealer) <= FOCUS_SCAN_RANGE) ? enemyHealer : (enemyHealer == null ? best : null);
			}

			if ((call != null) && !call.isInvul() && !isPlayedAround(npc, call) && !party.isCallDropped(call, now))
			{
				if (party.getFocus(now) != call)
				{
					FakeAiTrace.onFocusCall(npc, call, isHealer(call) ? "HEALER" : "FINISH");
				}
				party.setFocus(call, now, now + PARTY_FOCUS_TIME);
				if ((call != best) && !tank)
				{
					if (!hates(npc, call, FakePlayerPvpManager.PVP_HATE))
					{
						npc.addDamageHate(call, 0, FakePlayerPvpManager.PVP_HATE);
					}
					_focus = call;
					_focusUntil = now + FOCUS_TIME;
					return call;
				}
			}
		}
		return best;
	}

	/**
	 * @param npc a fake player
	 * @return how far it weighs the enemies of its fight: farther for an archer or mage, which hits the backline from behind its tank
	 */
	private static int getFocusScanRange(Attackable npc)
	{
		final FakePlayerPvpProfile profile = npc.getTemplate().getFakePlayerPvpProfile();
		return (profile != null) && !profile.isTransformed() && profile.getRole().isRanged() ? RANGED_FOCUS_SCAN_RANGE : FOCUS_SCAN_RANGE;
	}

	/**
	 * @return the enemies of its fight: the players it fights, and in a party also the ones close by that fight the party or heal someone it fights (all the other teams in a simulation)
	 */
	private List<Creature> getFightEnemies(Attackable npc, FakePlayerParty party)
	{
		final List<Creature> enemies = new ArrayList<>();
		for (AggroInfo info : npc.getAggroList().values())
		{
			final Creature enemy = info.getAttacker();
			if ((enemy != null) && isPvpEnemy(enemy) && (info.getHate() >= FakePlayerPvpManager.PVP_HATE) && isValidTarget(npc, enemy))
			{
				enemies.add(enemy);
			}
		}

		if (party == null)
		{
			return enemies;
		}

		for (Creature enemy : World.getInstance().getVisibleObjectsInRange(npc, Creature.class, getFocusScanRange(npc)))
		{
			if (enemies.contains(enemy) || !isPvpEnemy(enemy) || enemy.isAlikeDead() || !isValidTarget(npc, enemy))
			{
				continue;
			}

			final WorldObject itsTarget = enemy.getTarget();
			final boolean fightsParty = (itsTarget == npc) || ((itsTarget instanceof Creature) && party.isMember((Creature) itsTarget));
			final boolean healsEnemy = isHealer(enemy) && (itsTarget instanceof Creature) && hates(npc, (Creature) itsTarget, FakePlayerPvpManager.PVP_HATE);
			if (fightsParty || healsEnemy || FakeSimulationManager.isRunEnemy(npc, enemy))
			{
				enemies.add(enemy);
			}
		}
		return enemies;
	}

	/**
	 * @return how good a target {@code enemy} is, lower is better: low on HP and close by, a healer (then a buffer) first, one its party already hits; never an invincible one, nor one whose Ultimate Defense, Guts, Zealot or Angelic Icon it noticed if there is another
	 */
	private double focusScore(Attackable npc, FakePlayerParty party, Creature enemy)
	{
		final FakePlayerPvpProfile profile = npc.getTemplate().getFakePlayerPvpProfile();
		final boolean ranged = (profile != null) && !profile.isTransformed() && (profile.getRole().isRanged() || profile.isBowHeld() || profile.isCasterHeld());

		// #7: an archer or mage hits anyone within its reach as easily, so only the walk beyond its reach counts (counting the whole distance kept the backline safe).
		final double distance = npc.calculateDistance2D(enemy);
		final double walk = ranged ? Math.max(0, distance - getThreatReach(npc)) : distance;
		double score = (enemy.getCurrentHp() / Math.max(1, enemy.getMaxHp())) + (walk / 2000.0) + (enemy.isInvul() ? 2 : 0) + (isPlayedAround(npc, enemy) ? 1.5 : 0);

		// A dagger is an assassin: it goes for the backline (a healer above all, then mages and archers) and leaves the tanks alone. A tank guards rather than hunts healers.
		final boolean assassin = isAssassin(npc);
		final boolean tank = (profile != null) && !profile.isTransformed() && (profile.getRole() == Role.TANK);
		if (isHealer(enemy))
		{
			score -= tank ? 0 : assassin ? HEALER_PRIORITY * ASSASSIN_HEALER_FACTOR : HEALER_PRIORITY;
		}
		else if (isSupport(enemy))
		{
			score -= SUPPORT_PRIORITY;
		}
		else if (assassin && isSquishy(enemy))
		{
			score -= ASSASSIN_SQUISHY_PRIORITY;
		}
		else if (ranged && isSquishy(enemy))
		{
			score -= RANGED_SQUISHY_PRIORITY; // #7.
		}
		else if (isTank(enemy))
		{
			// The one with the most HP and defense is the worst target: everyone leaves it alone while there is another (it still goes down once low).
			score += assassin ? ASSASSIN_TANK_PENALTY : TANK_PENALTY;
		}

		if (party != null)
		{
			double focusFire = 0;
			for (Creature member : party.getMembers())
			{
				if ((member != npc) && !member.isDead() && (member.getTarget() == enemy))
				{
					focusFire += FOCUS_FIRE_BONUS;
				}
			}
			score -= Math.min(FOCUS_FIRE_MAX, focusFire);

			// #4: one that is on a squishy of the party is answered, from wherever it shoots (it used to react only to divers within a few hundred).
			if ((enemy.getTarget() instanceof Creature victim) && (victim != enemy) && !victim.isDead() && party.isMember(victim) && !isTank(enemy)) // #28: not an enemy tank, which peels rather than kills.
			{
				if (isHealer(victim))
				{
					score -= PRESSURE_ON_HEALER;
				}
				else if (isSquishy(victim))
				{
					score -= PRESSURE_ON_SQUISHY;
				}
			}

			// The one that hurts the party most (its damage to the party's fake players so far), and not one a call on just failed (it is healed through).
			score -= Math.min(THREAT_MAX, (getPartyDamage(party, enemy) / Math.max(1.0, npc.getMaxHp() * THREAT_HP_SCALE)) * THREAT_MAX);
			if (party.isCallDropped(enemy, System.currentTimeMillis()))
			{
				score += DROPPED_CALL_PENALTY;
			}
		}
		return score;
	}

	/**
	 * @param party a party
	 * @param enemy an enemy
	 * @return the damage {@code enemy} dealt to the party's fake players so far (their aggro lists)
	 */
	private static long getPartyDamage(FakePlayerParty party, Creature enemy)
	{
		long damage = 0;
		for (Npc fake : party.getFakes())
		{
			if (fake.isAttackable())
			{
				final AggroInfo info = fake.asAttackable().getAggroList().get(enemy);
				if (info != null)
				{
					damage += info.getDamage();
				}
			}
		}
		return damage;
	}

	/**
	 * @param npc the fake player
	 * @param target an enemy
	 * @param range how close to {@code target}
	 * @return a living enemy healer within {@code range} of {@code target} that it may fight (the closest), {@code null} if none
	 */
	private static Creature findEnemyHealerNear(Attackable npc, Creature target, int range)
	{
		Creature closest = null;
		double closestDistance = Double.MAX_VALUE;
		for (Creature creature : World.getInstance().getVisibleObjectsInRange(target, Creature.class, range))
		{
			if ((creature == npc) || creature.isAlikeDead() || !isHealer(creature) || !isPvpEnemy(creature) || !isValidTarget(npc, creature))
			{
				continue;
			}
			final double distance = creature.calculateDistance2D(target);
			if (distance < closestDistance)
			{
				closest = creature;
				closestDistance = distance;
			}
		}
		return closest;
	}

	/**
	 * @param npc a fake player
	 * @return {@code true} for a dagger (not transformed): it plays as an assassin
	 */
	private static boolean isAssassin(Attackable npc)
	{
		final FakePlayerPvpProfile profile = npc.getTemplate().getFakePlayerPvpProfile();
		return (profile != null) && !profile.isTransformed() && (profile.getRole() == Role.DAGGER);
	}

	/**
	 * @param npc a fake player
	 * @param target its target
	 * @return {@code true} if it may use a gap closer or speed buff (Shadow Step, Dash) on {@code target}: anyone, except for a dagger, which saves them for a healer, a mage or an archer
	 */
	private static boolean isWorthGapCloser(Attackable npc, Creature target)
	{
		return !isAssassin(npc) || isHealer(target) || isSquishy(target);
	}

	/**
	 * @param creature an enemy
	 * @return {@code true} for a tank: a fake player's tank build, a player of the knight classes
	 */
	private static boolean isTank(Creature creature)
	{
		if (creature.isPlayer())
		{
			return creature.asPlayer().isInCategory(CategoryType.TANKER_GROUP) || creature.asPlayer().isInCategory(CategoryType.KNIGHT_GROUP);
		}

		final FakePlayerPvpProfile profile = creature.isNpc() ? creature.asNpc().getTemplate().getFakePlayerPvpProfile() : null;
		return (profile != null) && !profile.isTransformed() && (profile.getRole() == Role.TANK);
	}

	/**
	 * @return its HP and CP left, as a ratio of their maximum
	 */
	private static double getHealth(Creature creature)
	{
		return (creature.getCurrentHp() + creature.getCurrentCp()) / Math.max(1, creature.getMaxHp() + creature.getMaxCp());
	}

	/**
	 * @param creature an enemy
	 * @return {@code true} for a support that isn't a healer (a fake player's support build, a player of the buffer classes)
	 */
	private static boolean isSupport(Creature creature)
	{
		if (creature.isPlayer())
		{
			return creature.asPlayer().isInCategory(CategoryType.ASSIST_MAGIC_GROUP);
		}

		final FakePlayerPvpProfile profile = creature.isNpc() ? creature.asNpc().getTemplate().getFakePlayerPvpProfile() : null;
		return (profile != null) && profile.getBuild().isSupport();
	}
	
	/**
	 * @return {@code true} if it noticed a defensive buff on {@code enemy} that keeps its damage from getting through, or that makes it keep away (see {@link #holdOff})
	 */
	private boolean isPlayedAround(Attackable npc, Creature enemy)
	{
		final FakePlayerPvpProfile profile = npc.getTemplate().getFakePlayerPvpProfile();
		final boolean mage = (profile != null) && !profile.isTransformed() && ((profile.getRole() == Role.MAGE) || profile.isCasterHeld());
		return (getKnownDefenses(enemy) & ((mage ? MAGIC_DAMAGE : PHYSICAL_DAMAGE) | DANGEROUS)) != 0;
	}
	
	/**
	 * Like a player who sees their hits do nothing, each attack or skill on a player under Ultimate Defense, Guts, Zealot, Angelic Icon or a magic mirror has its own {@link FakePlayerPvpConfig#DEFENSE_DETECT_CHANCE}% chance (see {@link FakePlayerPvpPersonality}) to make it notice: it doesn't stop attacking right away.
	 * Guts is only noticed by physical hits, a magic mirror by magic skills.
	 * @param target what it attacks
	 * @param magic {@code true} for a magic skill
	 */
	private void noticeDefenses(Creature target, boolean magic)
	{
		final int detectChance = FakePlayerPvpPersonality.of(getActiveChar()).getDefenseDetectChance();
		if (!isPvpEnemy(target) || (detectChance <= 0))
		{
			return;
		}
		
		// Forget the ones that ended.
		_noticedDefenses.removeIf(info -> info.isRemoved() || (info.getEffected() == null) || (info.getEffected().getEffectList().getBuffInfoBySkillId(info.getSkill().getId()) != info));
		
		for (BuffInfo info : target.getEffectList().getBuffs())
		{
			final Defense defense = Defense.of(info.getSkill());
			if ((defense != null) && (magic ? defense._noticedByMagic : defense._noticedByPhysical) && !info.isRemoved() && !_noticedDefenses.contains(info) && (Rnd.get(100) < detectChance))
			{
				_noticedDefenses.add(info);
			}
		}
	}
	
	/**
	 * @param target a creature
	 * @return what the defensive buffs it noticed on {@code target} keep from working (see {@link Defense}), 0 if none
	 */
	private int getKnownDefenses(Creature target)
	{
		// #10: an enemy that reflected one of its debuffs gets none for a while.
		final int reflected = isReflector(target) ? (MAGIC_DEBUFFS | PHYSICAL_DEBUFFS) : 0;
		if (_noticedDefenses.isEmpty() || !isPvpEnemy(target))
		{
			return reflected;
		}
		
		int flags = reflected;
		for (BuffInfo info : target.getEffectList().getBuffs())
		{
			if (!info.isRemoved() && _noticedDefenses.contains(info))
			{
				final Defense defense = Defense.of(info.getSkill());
				if (defense != null)
				{
					flags |= defense._flags;
				}
			}
		}
		return flags;
	}
	
	/** #10: a debuff of its own on itself this soon after it debuffed someone came back from them; it leaves that one's debuffs alone this long. */
	private static final long REFLECT_NOTICE_TIME = 5000;
	private static final long REFLECT_MEMORY = 30000;

	/**
	 * #10: {@code true} if {@code target} reflected one of its debuffs lately. Notices a new one first: a debuff of its own on itself, soon after it debuffed someone.
	 */
	private boolean isReflector(Creature target)
	{
		final long now = System.currentTimeMillis();
		final Creature last = _lastDebuffTarget;
		if ((last != null) && ((now - _lastDebuffTime) <= REFLECT_NOTICE_TIME))
		{
			final Attackable npc = getActiveChar();
			for (BuffInfo info : npc.getEffectList().getDebuffs())
			{
				if ((info.getEffector() == npc) && !info.isRemoved())
				{
					_reflectors.put(last.getObjectId(), now + REFLECT_MEMORY);
					_lastDebuffTarget = null;
					break;
				}
			}
		}
		if (_reflectors.isEmpty())
		{
			return false;
		}
		final Long until = _reflectors.get(target.getObjectId());
		return (until != null) && (until > now);
	}

	/**
	 * @param defenses what the defensive buffs it noticed on the target keep from working
	 * @param skill a skill
	 * @param debuff {@code true} if it is used as a debuff, {@code false} for its damage
	 * @return {@code true} if {@code skill} wouldn't get through
	 */
	private static boolean isBlocked(int defenses, Skill skill, boolean debuff)
	{
		if (defenses == 0)
		{
			return false;
		}
		
		if (debuff)
		{
			return (defenses & (skill.isMagic() ? MAGIC_DEBUFFS : PHYSICAL_DEBUFFS)) != 0;
		}
		return (defenses & (skill.isMagic() ? MAGIC_DAMAGE : PHYSICAL_DAMAGE)) != 0;
	}
	
	/**
	 * Plays around a player's defensive buff it noticed, like a player would: when its damage doesn't get through (Ultimate Defense, Guts against a fighter, a magic mirror against a mage) or the player hits hard (Zealot, Angelic Icon), it steps away to
	 * {@link FakePlayerPvpConfig#DEFENSE_KEEP_DISTANCE} and waits it out there, drinking potions and keeping its buffs up. Archers, mages and a tank with its bow out keep shooting from there when their damage still gets through. A player that catches up
	 * anyway is fought back (with what gets through) until it can step away again.
	 * @param npc the fake player
	 * @param target the player it fights
	 * @param defenses what the defensive buffs it noticed on {@code target} keep from working
	 * @param mage {@code true} for a mage, whose damage is magic
	 * @param ranged {@code true} if it fights from range (archer, mage, bow out)
	 * @param gap the distance to the target, collisions excluded
	 * @param canMove {@code true} if it can move
	 * @param now the current time
	 * @return {@code true} if it acted (or waits) this tick
	 */
	private boolean holdOff(Attackable npc, Creature target, int defenses, boolean mage, boolean ranged, double gap, boolean canMove, long now)
	{
		final boolean wasted = (defenses & (mage ? MAGIC_DAMAGE : PHYSICAL_DAMAGE)) != 0;
		if (!wasted && ((defenses & DANGEROUS) == 0))
		{
			return false; // A magic mirror against a fighter: it only leaves its magic skills aside.
		}

		// It only hits hard (Zealot, Angelic Icon), nothing is blocked: a player keeps away from it alone against it, not to finish its last stand with friends.
		if (!wasted && isWorthFinishing(npc, target))
		{
			return false;
		}
		
		// Waiting on purpose, not stuck.
		_progressTime = now;

		// An archer or mage against one that can't move (Ultimate Defense, Vengeance, a root): what little gets through is still damage, and it costs nothing out of its reach. Too close, it steps out of that reach first.
		final FakePlayerPvpPersonality personality = FakePlayerPvpPersonality.of(npc);
		if (ranged && target.isMovementDisabled())
		{
			final int reach = getThreatReach(target) + IMMOBILE_REACH_MARGIN;
			if (gap > reach)
			{
				return false;
			}
			return canMove && (now >= _nextKiteTime) && kiteStep(npc, target, now, Math.max(personality.getKiteStep(), (int) (reach - gap) + 50));
		}

		final int keep = personality.getDefenseKeepDistance();
		if (gap < keep)
		{
			// Too close: step away when it can, otherwise fight back meanwhile.
			return canMove && (now >= _nextKiteTime) && kiteStep(npc, target, now, Math.max(personality.getKiteStep(), (int) (keep - gap) + 50));
		}
		
		// Far enough: shoot what still gets through, or wait it out.
		if (!wasted && ranged)
		{
			return false;
		}
		
		if (npc.isMoving())
		{
			clientStopMoving(null);
		}
		return true;
	}
	
	/** An archer or mage keeps this much beyond the reach of an enemy that can't move. */
	private static final int IMMOBILE_REACH_MARGIN = 50;

	/**
	 * @param creature an enemy
	 * @return how far it can hurt or hold someone from where it stands: its auto attack range, or its longest damage, debuff or peel skill (a fake player's build, a player's skills)
	 */
	private static int getThreatReach(Creature creature)
	{
		int reach = creature.getPhysicalAttackRange();
		final FakePlayerPvpProfile profile = creature.isNpc() ? creature.asNpc().getTemplate().getFakePlayerPvpProfile() : null;
		if (profile != null)
		{
			for (SkillCategory category : THREAT_SKILLS)
			{
				for (Skill skill : profile.getSkills(category))
				{
					reach = Math.max(reach, getSkillReach(skill));
				}
			}

			// A tank or tyrant takes out its bow against one it can't reach.
			if (profile.getBow() != null)
			{
				reach = Math.max(reach, profile.getBow().getAttackRange());
			}
			return reach + creature.getTemplate().getCollisionRadius();
		}

		for (Skill skill : creature.getAllSkills())
		{
			if (!skill.isPassive() && !skill.isToggle() && skill.hasNegativeEffect() && (skill.getCastRange() > 0))
			{
				reach = Math.max(reach, skill.getCastRange());
			}
		}
		return reach + creature.getTemplate().getCollisionRadius();
	}

	/** The skills of an enemy that reach its foes. */
	private static final SkillCategory[] THREAT_SKILLS =
	{
		SkillCategory.ATTACK,
		SkillCategory.DEBUFF,
		SkillCategory.PEEL
	};

	/** A player under a "hits hard" buff is finished off below this much of its HP and CP. */
	private static final double FINISH_HEALTH = 0.35;

	/**
	 * @param npc the fake player
	 * @param target a player that hits hard meanwhile (Zealot, Angelic Icon), whose damage still gets through
	 * @return {@code true} if it is better to go in than to keep away: the player isn't after it, it is low (those buffs are a last stand), or friends fight it too
	 */
	private static boolean isWorthFinishing(Attackable npc, Creature target)
	{
		if (target.getTarget() != npc)
		{
			return true;
		}

		final double health = (target.getCurrentHp() + target.getCurrentCp()) / Math.max(1, target.getMaxHp() + target.getMaxCp());
		if (health < FINISH_HEALTH)
		{
			return true;
		}

		final FakePlayerParty party = FakePartyManager.getInstance().getParty(npc);
		if (party != null)
		{
			for (Creature member : party.getMembers())
			{
				if ((member != npc) && !member.isDead() && (member.getTarget() == target))
				{
					return true;
				}
			}
		}
		return false;
	}

	/**
	 * Notes since when it chases a player out of melee reach.
	 */
	private void trackChase(Creature target, double gap, long now)
	{
		if (!isPvpEnemy(target) || (gap <= FakePlayerPvpConfig.WEAPON_SWAP_MELEE_DISTANCE))
		{
			_chaseTarget = null;
		}
		else if (_chaseTarget != target)
		{
			_chaseTarget = target;
			_chaseStart = now;
		}
	}
	
	/**
	 * @return {@code true} if {@code target} has been getting away from it for a moment
	 */
	private boolean isChasing(Creature target, long now)
	{
		return (_chaseTarget == target) && ((now - _chaseStart) >= CHASE_BEFORE_SPRINT);
	}
	
	/**
	 * @param gap the distance to {@code target}, collisions excluded: it is used without moving, so a melee one (Hammer Crush) is left for when the player is on it
	 * @return the first skill of its peel list (in order of preference) it can use on {@code target} now from where it stands and that gets through its defenses, {@code null} if none: used for its effect on that one player, an area peel like Aura Flash is fine against a single one
	 */
	private static Skill pickPeel(Attackable npc, Creature target, FakePlayerPvpProfile profile, int defenses, double gap)
	{
		return pickPeel(npc, target, profile, defenses, gap, Long.MAX_VALUE, false);
	}

	/**
	 * {@link #pickPeel(Attackable, Creature, FakePlayerPvpProfile, int, double)} for an archer or mage a melee is after.
	 * @param safeCast how long it may cast (see {@link #getSafeCastTime})
	 * @param mage {@code true} for a mage: it doesn't use a melee skill to peel
	 */
		private static Skill pickPeel(Attackable npc, Creature target, FakePlayerPvpProfile profile, int defenses, double gap, long safeCast, boolean mage)
	{
		for (Skill skill : profile.getSkills(SkillCategory.PEEL))
		{
			// Only what lands before the melee arrives, and no physical melee skill for a mage (Hammer Crush, Stun Attack: it would stay in melee to use them). Its spells around itself (Aura Flash, Blink) are kept.
			if ((mage && skill.isPhysical() && (getSkillReach(skill) < 150)) || (getCastTime(npc, skill) > Math.max(QUICK_CAST, safeCast + CAST_MARGIN)))
			{
				continue;
			}
			if ((gap <= getSkillReach(skill)) && !target.isAffectedBySkill(skill.getId()) && !isBlocked(defenses, skill, true) && canCast(npc, skill, target))
			{
				return skill;
			}
		}
		
		return null;
	}
	
	/**
	 * Closes the gap to {@code target} with a skill that reaches it from where it stands (Rush, Shadow Step).
	 * @return {@code true} if it used one
	 */
	private boolean useRush(Attackable npc, FakePlayerPvpProfile profile, Creature target, double distance, int collision, boolean pvp)
	{
		final double gap = distance - collision;
		if (gap <= FakePlayerPvpConfig.WEAPON_SWAP_MELEE_DISTANCE)
		{
			return false;
		}
		
		for (Skill skill : profile.getSkills(SkillCategory.RUSH))
		{
			if ((skill.getCastRange() >= gap) && (pvp || !isPvpOnly(skill)) && !isAlreadyOn(skill, target) && canCast(npc, skill, target) && (castOrApproach(npc, target, skill, distance, collision, false) == CastResult.CAST))
			{
				return true;
			}
		}
		
		return false;
	}
	
	/**
	 * Removes what holds it back (Break Duress against a root, Remedy against bleeding...), only when it has it.
	 * @return {@code true} if it cast one
	 */
	private boolean castCleanse(Attackable npc, Creature target, FakePlayerPvpProfile profile)
	{
		for (Skill skill : profile.getSkills(SkillCategory.CLEANSE))
		{
			if (removesSomething(skill, npc) && canCast(npc, skill, npc))
			{
				clientStopMoving(null);
				npc.setTarget(npc);
				npc.doCast(skill);
				npc.setTarget(target);
				return true;
			}
		}
		
		return false;
	}
	
	/**
	 * @return {@code true} if casting {@code skill} would remove one of the effects on {@code creature}
	 */
	private static boolean removesSomething(Skill skill, Creature creature)
	{
		final List<AbstractEffect> effects = skill.getEffects(EffectScope.GENERAL);
		if (effects != null)
		{
			for (AbstractEffect effect : effects)
			{
				if ("DispelBySlot".equals(effect.getClass().getSimpleName()) && effect.checkCondition(creature))
				{
					return true;
				}
				
				// Soul Cleanse removes debuffs, whatever they are.
				if ("DispelByCategory".equals(effect.getClass().getSimpleName()) && creature.getEffectList().hasDebuffs())
				{
					return true;
				}
			}
		}
		
		return false;
	}
	
	/**
	 * Like a player meeting someone at its hunting ground, it may say hello to a player it sees for the first time (see {@link FakePlayerPvpConfig#GREET_CHANCE}). Not while fighting a player.
	 * @param npc the fake player
	 * @param now the current time
	 */
	private void lookForNewFaces(Attackable npc, long now)
	{
		if ((FakePlayerPvpConfig.GREET_CHANCE <= 0) || (now < _nextGreetScan))
		{
			return;
		}
		
		_nextGreetScan = now + GREET_SCAN_INTERVAL;
		for (Player player : World.getInstance().getVisibleObjectsInRange(npc, Player.class, OPPORTUNITY_RANGE))
		{
			if (_seenPlayers.contains(player.getObjectId()) || player.isAlikeDead() || player.isInvisible() || (player.getInstanceId() != npc.getInstanceId()) || hates(npc, player, FakePlayerPvpManager.PVP_HATE) || FakeClanManager.getInstance().isWarEnemy(npc, player) || !GeoEngine.getInstance().canSeeTarget(npc, player))
			{
				continue;
			}
			
			// One hello at most per scan, the others are greeted (or not) later.
			if (_seenPlayers.add(player.getObjectId()))
			{
				FakePlayerPvpManager.getInstance().greet(npc);
				return;
			}
		}
		
		// Another fake player farming around: a hello, and it may say hello back (instead of greeting it on its own).
		for (Npc other : World.getInstance().getVisibleObjectsInRange(npc, Npc.class, OPPORTUNITY_RANGE))
		{
			if (_seenPlayers.contains(other.getObjectId()) || !isFreeFakePlayer(npc, other) || !GeoEngine.getInstance().canSeeTarget(npc, other))
			{
				continue;
			}
			
			if (_seenPlayers.add(other.getObjectId()))
			{
				((FakePlayerPvpAIExperimental) other.getAI())._seenPlayers.add(npc.getObjectId());
				FakePlayerPvpManager.getInstance().greetFake(npc, other);
				return;
			}
		}
	}
	
	/**
	 * A fake player that came back to where it died (see {@link FakePlayerPvpManager}) goes after its killer as soon as it sees them: healthy, not a much higher level (then it gives up), not in town, an arena, a siege, the Olympiad or a duel.
	 * @param npc the fake player
	 * @param now the current time
	 * @return {@code true} if it picked the fight
	 */
	private boolean lookForRevenge(Attackable npc, long now)
	{
		final FakePlayerPvpProfile profile = npc.getTemplate().getFakePlayerPvpProfile();
		final int killerId = profile != null ? profile.getRevengeTarget(now) : 0;
		if ((killerId == 0) || (now < _nextRevengeScan))
		{
			return false;
		}
		
		_nextRevengeScan = now + OPPORTUNITY_SCAN_INTERVAL;
		if ((npc.getCurrentHp() < (npc.getMaxHp() * 0.7)) || npc.isInsideZone(ZoneId.PEACE))
		{
			return false;
		}
		
		final Player player = World.getInstance().getPlayer(killerId);
		if ((player == null) || player.isAlikeDead() || player.isInvisible() || (player.isGM() && !player.getAccessLevel().canTakeAggro()) || (player.getInstanceId() != npc.getInstanceId()))
		{
			return false;
		}
		
		// Gained too many levels since: not worth it.
		if ((FakePlayerPvpConfig.OUTLEVELED_DIFFERENCE > 0) && (player.getLevel() >= (npc.getLevel() + FakePlayerPvpConfig.OUTLEVELED_DIFFERENCE)))
		{
			profile.clearRevengeTarget();
			return false;
		}
		
		if (!npc.isInsideRadius3D(player, FakePlayerPvpConfig.REVENGE_RANGE) || player.isInsideZone(ZoneId.PEACE) || player.isInsideZone(ZoneId.PVP) || player.isInsideZone(ZoneId.SIEGE) || player.isInOlympiadMode() || player.isInDuel() || !GeoEngine.getInstance().canSeeTarget(npc, player))
		{
			return false;
		}
		
		FakePlayerPvpManager.getInstance().revenge(npc, player);
		return true;
	}
	
	/**
	 * Like PvPers do, a healthy fake player may go after a flagged (purple) or karma (red) player passing by: not a much higher level, not a player in town or in an arena, and each player is only considered once in a while.
	 * @param npc the fake player
	 * @param now the current time
	 * @return {@code true} if it picked a fight
	 */
	private boolean lookForPvp(Attackable npc, long now)
	{
		final FakePlayerPvpPersonality personality = FakePlayerPvpPersonality.of(npc);
		final boolean atWar = (FakeClanConfig.WAR_ATTACK_CHANCE > 0) && isAtWar(npc);
		if (((personality.getAttackFlaggedChance() <= 0) && (personality.getAttackKarmaChance() <= 0) && (personality.getJoinFightChance() <= 0) && !atWar) || (now < _nextOpportunityScan))
		{
			return false;
		}
		
		_nextOpportunityScan = now + OPPORTUNITY_SCAN_INTERVAL;
		_consideredPlayers.values().removeIf(time -> (now - time) > OPPORTUNITY_MEMORY);
		if ((npc.getCurrentHp() < (npc.getMaxHp() * 0.7)) || npc.isInsideZone(ZoneId.PEACE))
		{
			return false;
		}
		
		for (Player player : World.getInstance().getVisibleObjectsInRange(npc, Player.class, OPPORTUNITY_RANGE))
		{
			// A player of a clan its clan is at war with (both declared it): it goes for them like a clan war enemy.
			final boolean war = atWar && FakeClanManager.getInstance().isWarEnemy(npc, player);
			final boolean karma = player.getKarma() > 0;
			if ((!karma && (player.getPvpFlag() == 0) && !war) || player.isAlikeDead() || player.isInvisible() || (player.isGM() && !player.getAccessLevel().canTakeAggro()))
			{
				continue;
			}
			
			if ((player.getInstanceId() != npc.getInstanceId()) || player.isInsideZone(ZoneId.PEACE) || player.isInsideZone(ZoneId.PVP) || player.isInsideZone(ZoneId.SIEGE) || player.isInOlympiadMode() || player.isInDuel())
			{
				continue;
			}
			
			if ((player.getLevel() > (npc.getLevel() + (war ? FakeClanConfig.WAR_MAX_LEVEL_ABOVE : OPPORTUNITY_MAX_LEVEL_ABOVE))) || hates(npc, player, FakePlayerPvpManager.PVP_HATE) || !GeoEngine.getInstance().canSeeTarget(npc, player))
			{
				continue;
			}
			
			// Not its own clan or alliance, flagged or not.
			if (!war && !karma && FakeClanManager.getInstance().isFriend(npc, player))
			{
				continue;
			}
			
			final int chance = war ? FakeClanConfig.WAR_ATTACK_CHANCE : karma ? personality.getAttackKarmaChance() : personality.getAttackFlaggedChance();
			if ((_consideredPlayers.putIfAbsent(player.getObjectId(), now) == null) && (Rnd.get(100) < chance))
			{
				if (war)
				{
					FakePlayerPvpManager.getInstance().attackWarEnemy(npc, player);
				}
				else
				{
					FakePlayerPvpManager.getInstance().attackPlayer(npc, player, karma);
				}
				return true;
			}
		}
		
		// Other fake players in a fight (flagged) or with karma: it may join in.
		for (Npc other : World.getInstance().getVisibleObjectsInRange(npc, Npc.class, OPPORTUNITY_RANGE))
		{
			if (!other.isPvpFakePlayer() || other.isAlikeDead() || other.isInvisible() || other.isTrialDuelist() || (other.getInstanceId() != npc.getInstanceId()))
			{
				continue;
			}
			
			// A fake player of a clan its clan is at war with.
			final boolean war = atWar && FakeClanManager.getInstance().isWarEnemy(npc, other);
			final boolean karma = other.getKarma() > 0;
			if ((!karma && (other.getScriptValue() == 0) && !war) || other.isInsideZone(ZoneId.PEACE) || other.isInsideZone(ZoneId.PVP) || other.isInsideZone(ZoneId.SIEGE))
			{
				continue;
			}
			
			if ((other.getLevel() > (npc.getLevel() + (war ? FakeClanConfig.WAR_MAX_LEVEL_ABOVE : OPPORTUNITY_MAX_LEVEL_ABOVE))) || hates(npc, other, FakePlayerPvpManager.PVP_HATE) || !GeoEngine.getInstance().canSeeTarget(npc, other))
			{
				continue;
			}
			
			// Not its own clan or alliance.
			if (!war && !karma && FakeClanManager.getInstance().isFriend(npc, other))
			{
				continue;
			}
			
			final int chance = war ? FakeClanConfig.WAR_ATTACK_CHANCE : karma ? personality.getAttackKarmaChance() : personality.getJoinFightChance();
			if ((_consideredPlayers.putIfAbsent(other.getObjectId(), now) == null) && (Rnd.get(100) < chance))
			{
				if (war)
				{
					FakePlayerPvpManager.getInstance().attackWarEnemy(npc, other);
				}
				else
				{
					FakePlayerPvpManager.getInstance().attackFakePlayer(npc, other, karma);
				}
				return true;
			}
		}
		
		return false;
	}
	
	/**
	 * @param npc the fake player
	 * @return {@code true} if its clan is at war with another clan
	 */
	private static boolean isAtWar(Attackable npc)
	{
		final Clan clan = FakeClanManager.getClan(npc);
		return (clan != null) && clan.isAtWar();
	}
	
	/**
	 * Like players that pick on lower levels, a healthy fake player may walk up to a player (or another fake player) of a lower level it sees, stand there a moment, hit them once and see whether they want a fight (see {@link #thinkPoke}). The more levels above
	 * them, the more likely (see {@link FakePlayerPvpPersonality#getPokeChance}); each one is only considered once in a while.
	 * @param npc the fake player
	 * @param now the current time
	 * @return {@code true} if it started a taunt
	 */
	private boolean lookForPoke(Attackable npc, long now)
	{
		if (((FakePlayerPvpConfig.POKE_CHANCE_MIN <= 0) && (FakePlayerPvpConfig.POKE_CHANCE_MAX <= 0)) || (now < _nextPokeScan))
		{
			return false;
		}
		
		_nextPokeScan = now + OPPORTUNITY_SCAN_INTERVAL;
		_pokeConsidered.values().removeIf(time -> (now - time) > OPPORTUNITY_MEMORY);
		if ((npc.getCurrentHp() < (npc.getMaxHp() * 0.7)) || npc.isInsideZone(ZoneId.PEACE))
		{
			return false;
		}
		
		final FakePlayerPvpPersonality personality = FakePlayerPvpPersonality.of(npc);
		for (Creature creature : World.getInstance().getVisibleObjectsInRange(npc, Creature.class, OPPORTUNITY_RANGE))
		{
			if (!canPoke(npc, creature, true, false))
			{
				continue;
			}
			
			// Other fake players are picked on more often (FakePvpFakePokeScale).
			final int chance = personality.getPokeChance(npc.getLevel() - creature.getLevel());
			if ((_pokeConsidered.putIfAbsent(creature.getObjectId(), now) == null) && (Rnd.get(100) < (creature.isPlayer() ? chance : ((chance * FakePlayerPvpConfig.FAKE_POKE_SCALE) / 100))))
			{
				_pokeTarget = creature;
				_pokeRival = false;
				_pokePhase = PokePhase.APPROACH;
				_pokeStart = now;
				_pokePhaseEnd = 0;
				return true;
			}
		}
		
		return false;
	}
	
	/**
	 * @param npc the fake player
	 * @param target a creature it sees
	 * @param starting {@code true} when it thinks about starting a taunt, {@code false} when it goes on with one
	 * @param rival {@code true} for a fake player it argued with about the spot (see {@link #startRivalry}), which may be up to {@link FakePlayerPvpConfig#RIVALRY_MAX_LEVEL_ABOVE} levels above it
	 * @return {@code true} if it may taunt {@code target}: a player or a roaming fake player of a lower level, out of town, arenas, sieges, the Olympiad and duels, and not in a PvP already (a flagged or karma player is fair game anyway, see {@link #lookForPvp})
	 */
	private static boolean canPoke(Attackable npc, Creature target, boolean starting, boolean rival)
	{
		if ((target == null) || (target == npc) || target.isAlikeDead() || !target.isSpawned() || target.isInvisible() || (target.getInstanceId() != npc.getInstanceId()))
		{
			return false;
		}
		
		if (rival ? (target.getLevel() > (npc.getLevel() + FakePlayerPvpConfig.RIVALRY_MAX_LEVEL_ABOVE)) : (target.getLevel() >= npc.getLevel()))
		{
			return false;
		}
		
		if (target.isInsideZone(ZoneId.PEACE) || target.isInsideZone(ZoneId.PVP) || target.isInsideZone(ZoneId.SIEGE) || npc.isInsideZone(ZoneId.PEACE))
		{
			return false;
		}
		
		if (target.isPlayer())
		{
			final Player player = target.asPlayer();
			if ((player.isGM() && !player.getAccessLevel().canTakeAggro()) || player.isInOlympiadMode() || player.isInDuel() || (starting && ((player.getPvpFlag() > 0) || (player.getKarma() > 0))))
			{
				return false;
			}
		}
		else if (!target.isPvpFakePlayer() || target.asNpc().isTrialDuelist() || (!rival && !FakePlayerPvpConfig.POKE_FAKE_PLAYERS) || (starting && FakePlayerPvpManager.isInPvp(target.asNpc())))
		{
			return false;
		}
		
		if (hates(npc, target, FakePlayerPvpManager.PVP_HATE))
		{
			return false;
		}
		
		// Never its own clan or alliance.
		if (FakeClanManager.getInstance().isFriend(npc, target))
		{
			return false;
		}
		
		if (starting)
		{
			return GeoEngine.getInstance().canSeeTarget(npc, target);
		}
		
		return npc.calculateDistance2D(target) <= FakePlayerPvpPersonality.of(npc).getChaseRange();
	}
	
	/**
	 * Goes on with a taunt (see {@link #lookForPoke}): it runs up to them, stands there targeting them for a few seconds (and may say "pvp?"), hits them once with its normal attack, which flags it, then waits a moment. If they hit back, the fight starts like any
	 * other ({@link FakePlayerPvpManager#onFakePlayerAttacked}); if not, it goes back to hunting.
	 * @param npc the fake player
	 * @param now the current time
	 * @return {@code true} if it is busy with it this tick
	 */
	private boolean thinkPoke(Attackable npc, long now)
	{
		final Creature target = _pokeTarget;
		if (target == null)
		{
			return false;
		}
		
		if (!canPoke(npc, target, false, _pokeRival) || ((_pokePhase != PokePhase.WATCH) && ((now - _pokeStart) > POKE_TIMEOUT)))
		{
			endPoke(npc);
			return false;
		}
		
		final int collision = npc.getTemplate().getCollisionRadius() + target.getTemplate().getCollisionRadius();
		final double gap = npc.calculateDistance2D(target) - collision;
		switch (_pokePhase)
		{
			case APPROACH:
			{
				if (gap > POKE_STAND_DISTANCE)
				{
					walkUpTo(npc, target, collision + (POKE_STAND_DISTANCE / 2));
					return true;
				}
				
				// There: it stops in front of them and targets them, which they see.
				clientStopMoving(null);
				npc.setTarget(target);
				_pokePhase = PokePhase.LOITER;
				_pokePhaseEnd = now + Rnd.get(FakePlayerPvpConfig.POKE_LOITER_MIN, FakePlayerPvpConfig.POKE_LOITER_MAX);
				FakePlayerPvpManager.getInstance().onPoke(npc);
				return true;
			}
			case LOITER:
			{
				// They walk off: it follows.
				if (gap > POKE_FOLLOW_DISTANCE)
				{
					walkUpTo(npc, target, collision + (POKE_STAND_DISTANCE / 2));
					return true;
				}
				
				if (now < _pokePhaseEnd)
				{
					return true;
				}
				
				_pokePhase = PokePhase.HIT;
				return true;
			}
			case HIT:
			{
				final int range = npc.getPhysicalAttackRange();
				if (gap > range)
				{
					walkUpTo(npc, target, collision + Math.max(10, Math.min(range, POKE_STAND_DISTANCE) - 10));
					return true;
				}
				
				if (npc.isAttackingNow() || npc.isAttackDisabled())
				{
					return true;
				}
				
				// One hit with its normal attack, no more.
				clientStopMoving(null);
				npc.setTarget(target);
				_actor.doAttack(target);
				_pokePhase = PokePhase.WATCH;
				_pokePhaseEnd = now + Rnd.get(POKE_WATCH_MIN, POKE_WATCH_MAX);
				return true;
			}
			case WATCH:
			{
				// No answer: back to hunting.
				if (now < _pokePhaseEnd)
				{
					return true;
				}
				
				endPoke(npc);
				return false;
			}
		}
		
		return false;
	}
	
	/**
	 * Runs to {@code target}, stopping {@code offset} from them.
	 */
	private void walkUpTo(Attackable npc, Creature target, int offset)
	{
		if (npc.isMovementDisabled())
		{
			return;
		}
		
		npc.setRunning();
		moveToPawn(target, offset);
	}
	
	/**
	 * Ends a taunt, if it is taunting someone.
	 * @param npc the fake player
	 */
	private void endPoke(Attackable npc)
	{
		final Creature target = _pokeTarget;
		if (target == null)
		{
			return;
		}
		
		_pokeTarget = null;
		_pokePhase = null;
		_pokeRival = false;
		if ((npc.getTarget() == target) && (getIntention() != Intention.ATTACK))
		{
			npc.setTarget(null);
		}
	}
	
	/**
	 * Like players at a hunting ground checking out who else farms there, a healthy fake player may walk over to another fake player it sees to talk (see {@link #thinkMeet}), more likely for a chatty one; each one is only considered once in a while.
	 * @param npc the fake player
	 * @param now the current time
	 * @return {@code true} if it set off to meet someone
	 */
	private boolean lookForMeeting(Attackable npc, long now)
	{
		final FakePlayerPvpPersonality personality = FakePlayerPvpPersonality.of(npc);
		if ((FakePlayerPvpConfig.MEET_RANGE <= 0) || (personality.getMeetChance() <= 0) || (now < _nextMeetScan))
		{
			return false;
		}
		
		_nextMeetScan = now + MEET_SCAN_INTERVAL;
		_meetConsidered.values().removeIf(time -> (now - time) > MEET_MEMORY);
		if ((npc.getCurrentHp() < (npc.getMaxHp() * 0.7)) || npc.isInsideZone(ZoneId.PEACE) || npc.isTrialDuelist())
		{
			return false;
		}
		
		final Spawn spawn = npc.getSpawn();
		for (Npc other : World.getInstance().getVisibleObjectsInRange(npc, Npc.class, FakePlayerPvpConfig.MEET_RANGE))
		{
			if (_meetConsidered.containsKey(other.getObjectId()) || !isFreeFakePlayer(npc, other))
			{
				continue;
			}
			
			// Not so far from its own hunting ground that it would turn back on the way.
			if ((spawn != null) && (other.calculateDistance2D(spawn) > personality.getLeashRange()))
			{
				continue;
			}
			
			_meetConsidered.put(other.getObjectId(), now);
			if (Rnd.get(100) < personality.getMeetChance())
			{
				_meetTarget = other;
				_meetStart = now;
				return true;
			}
		}
		
		return false;
	}
	
	/**
	 * Goes on walking over to another fake player (see {@link #lookForMeeting}): once there it waits for them to finish their monster, then they talk ({@link FakePlayerPvpManager#converse}). Sometimes the talk is about the spot and ends in a fight (see
	 * {@link FakePlayerPvpConfig#RIVALRY_CHANCE}).
	 * @param npc the fake player
	 * @param now the current time
	 * @return {@code true} if it is busy with it this tick
	 */
	private boolean thinkMeet(Attackable npc, long now)
	{
		final Npc target = _meetTarget;
		if (target == null)
		{
			return false;
		}
		
		final FakePlayerPvpPersonality personality = FakePlayerPvpPersonality.of(npc);
		final Spawn spawn = npc.getSpawn();
		if (!isFreeFakePlayer(npc, target) || ((now - _meetStart) > MEET_TIMEOUT) || ((spawn != null) && (npc.calculateDistance2D(spawn) > personality.getLeashRange())))
		{
			_meetTarget = null;
			return false;
		}
		
		final int collision = npc.getTemplate().getCollisionRadius() + target.getTemplate().getCollisionRadius();
		if ((npc.calculateDistance2D(target) - collision) > MEET_TALK_DISTANCE)
		{
			walkUpTo(npc, target, collision + (MEET_TALK_DISTANCE / 2));
			return true;
		}
		
		// There: it waits for them to finish their monster.
		if (npc.isMoving())
		{
			clientStopMoving(null);
		}
		npc.setTarget(target);
		final FakePlayerPvpAIExperimental targetAI = (FakePlayerPvpAIExperimental) target.getAI();
		if ((targetAI.getIntention() == Intention.ATTACK) || target.isCastingNow())
		{
			// It doesn't pick its next monster meanwhile.
			targetAI._visitor = npc;
			targetAI._visitorUntil = now + 3000;
			return true;
		}
		
		_meetTarget = null;
		targetAI._meetConsidered.put(npc.getObjectId(), now);
		final boolean rivalry = (target.getLevel() <= (npc.getLevel() + FakePlayerPvpConfig.RIVALRY_MAX_LEVEL_ABOVE)) && (Rnd.get(100) < personality.getRivalryChance());
		FakePlayerPvpManager.getInstance().converse(npc, target, rivalry);
		return true;
	}
	
	/**
	 * Another fake player walked over to talk (see {@link #thinkMeet}) while it fought a monster: it doesn't pick its next one, so they can talk.
	 * @param npc the fake player
	 * @param now the current time
	 * @return {@code true} if it waits for them
	 */
	private boolean waitForVisitor(Attackable npc, long now)
	{
		final Npc visitor = _visitor;
		if (visitor == null)
		{
			return false;
		}
		
		if ((now >= _visitorUntil) || visitor.isDead() || !visitor.isSpawned() || (npc.calculateDistance2D(visitor) > TALK_MAX_DISTANCE))
		{
			_visitor = null;
			return false;
		}
		
		if (npc.isMoving())
		{
			clientStopMoving(null);
		}
		return true;
	}
	
	/**
	 * While it talks with another fake player (see {@link #holdForTalk}) it stands there targeting them.
	 * @param npc the fake player
	 * @param now the current time
	 * @return {@code true} if it is talking
	 */
	private boolean thinkTalk(Attackable npc, long now)
	{
		final Npc partner = _talkPartner;
		if (partner == null)
		{
			return false;
		}
		
		if ((now >= _talkUntil) || partner.isDead() || !partner.isSpawned() || (npc.calculateDistance2D(partner) > TALK_MAX_DISTANCE))
		{
			endTalk();
			return false;
		}
		
		if (npc.isMoving())
		{
			clientStopMoving(null);
		}
		if (npc.getTarget() != partner)
		{
			npc.setTarget(partner);
		}
		return true;
	}
	
	/**
	 * Makes it stand and talk with another fake player until {@code until} (see {@link FakePlayerPvpManager#converse}).
	 * @param partner the one it talks with
	 * @param until when the talk is over at the latest
	 */
	public void holdForTalk(Npc partner, long until)
	{
		_meetTarget = null;
		_visitor = null;
		endPoke(getActiveChar());
		_talkPartner = partner;
		_talkUntil = until;
	}
	
	/**
	 * @param partner a fake player
	 * @return {@code true} if it is talking with {@code partner}
	 */
	public boolean isTalkingWith(Npc partner)
	{
		return (_talkPartner == partner) && (System.currentTimeMillis() < _talkUntil);
	}
	
	/**
	 * Ends a talk, if it is talking with someone, for both of them.
	 */
	public void endTalk()
	{
		final Npc partner = _talkPartner;
		_talkPartner = null;
		_talkUntil = 0;
		if ((partner != null) && partner.hasAI() && (partner.getAI() instanceof FakePlayerPvpAIExperimental))
		{
			final FakePlayerPvpAIExperimental ai = (FakePlayerPvpAIExperimental) partner.getAI();
			if (ai._talkPartner == _actor)
			{
				ai.endTalk();
			}
		}
	}
	
	/**
	 * At the end of a talk about the spot (see {@link FakePlayerPvpManager#converse}) it hits the other fake player once, like a taunt ({@link #thinkPoke}), whatever their level up to {@link FakePlayerPvpConfig#RIVALRY_MAX_LEVEL_ABOVE} levels above it.
	 * @param rival the other fake player
	 */
	public void startRivalry(Npc rival)
	{
		final Attackable npc = getActiveChar();
		if (npc.isDead() || !canPoke(npc, rival, false, true))
		{
			return;
		}
		
		_pokeTarget = rival;
		_pokeRival = true;
		_pokePhase = PokePhase.HIT;
		_pokeStart = System.currentTimeMillis();
		_pokePhaseEnd = 0;
	}
	
	/**
	 * @param npc the fake player
	 * @param creature a creature it sees
	 * @return {@code true} if {@code creature} is another roaming fake player free to talk: not a class transfer challenge opponent, not in town, not in a PvP, not resting, not already talking to, taunting or walking over to someone
	 */
	private static boolean isFreeFakePlayer(Attackable npc, Creature creature)
	{
		if ((creature == null) || (creature == npc) || !creature.isPvpFakePlayer() || creature.isAlikeDead() || !creature.isSpawned() || creature.isInvisible() || (creature.getInstanceId() != npc.getInstanceId()))
		{
			return false;
		}
		
		final Npc other = creature.asNpc();
		if (other.isTrialDuelist() || other.isInsideZone(ZoneId.PEACE) || FakePlayerPvpManager.isInPvp(other) || !other.hasAI() || !(other.getAI() instanceof FakePlayerPvpAIExperimental) || FakeClanManager.getInstance().isWarEnemy(npc, other))
		{
			return false;
		}
		
		// Busy with players: in their party.
		if (FakePartyManager.getInstance().isInPlayerParty(other) || FakePartyManager.getInstance().isSameGroup(npc, other))
		{
			return false;
		}
		
		final FakePlayerPvpAIExperimental ai = (FakePlayerPvpAIExperimental) other.getAI();
		return !ai.isResting() && (ai._talkPartner == null) && (ai._pokeTarget == null) && (ai._meetTarget == null);
	}
	
	/**
	 * @param creature a creature
	 * @return {@code true} if fighting {@code creature} is a PvP: a player, a player's summon, or another roaming fake player
	 */
	private static boolean isPvpEnemy(Creature creature)
	{
		return FakePlayerPvpManager.isPvpEnemy(creature);
	}
	
	/**
	 * Leaves a hotzone the rotation moved on from (see {@link FakePlayerPvpManager}): once its time is up and it isn't fighting, it stops and reads a Scroll of Escape (the blessed one if it carries it), in front of whoever watches, like a player going to town,
	 * and logs off when it finishes. Stopped (stun, silence...), it tries again a moment later.
	 * @param npc the fake player
	 * @param profile its profile
	 * @param now the current time
	 * @return {@code true} if it is busy leaving this tick
	 */
	private boolean thinkLeave(Attackable npc, FakePlayerPvpProfile profile, long now)
	{
		if ((profile == null) || (profile.getLeaveTime() <= 0) || (now < profile.getLeaveTime()))
		{
			return false;
		}
		
		endPoke(npc);
		_resting = false;
		if (standUp(npc))
		{
			return true;
		}
		
		if (now < _nextEscapeTime)
		{
			return true;
		}
		
		if (!readEscapeScroll(npc, null, now, profile.hasBlessedEscape(), true))
		{
			// No scroll to read (should not happen): it just logs off once nobody watches.
			if (!FakePlayerPvpManager.isSeenByPlayer(npc))
			{
				npc.deleteMe();
			}
			_nextEscapeTime = now + 5000;
		}
		return true;
	}
	
	/**
	 * Sits down to rest, like a player (it regenerates faster that way, see {@link #isResting()}).
	 */
	private void sitDown(Attackable npc)
	{
		final FakePlayerHolder holder = npc.getTemplate().getFakePlayerInfo();
		if ((holder == null) || holder.isSitting() || isFightNear(npc))
		{
			return;
		}
		
		clientStopMoving(null);
		holder.setSitting(true);
		npc.broadcastPacket(new ChangeWaitType(npc, ChangeWaitType.WT_SITTING));
	}
	
	/**
	 * Stands up if it sits.
	 * @return {@code true} if it was sitting (getting up takes this tick, like for a player)
	 */
	private boolean standUp(Attackable npc)
	{
		final FakePlayerHolder holder = npc.getTemplate().getFakePlayerInfo();
		if ((holder == null) || !holder.isSitting())
		{
			return false;
		}
		
		holder.setSitting(false);
		npc.broadcastPacket(new ChangeWaitType(npc, ChangeWaitType.WT_STANDING));
		return true;
	}
	
	/**
	 * Runs back to its hunting ground.
	 */
	private void goHome(Attackable npc, Spawn spawn)
	{
		final Location home = GeoEngine.getInstance().getValidLocation(npc.getX(), npc.getY(), npc.getZ(), spawn.getX(), spawn.getY(), spawn.getZ(), npc.getInstanceId());
		npc.setRunning();
		moveTo(home.getX(), home.getY(), home.getZ());
	}
	
	/**
	 * Reads the aggro list directly: {@link Attackable#getHating(Creature)} also removes invulnerable and invisible players from it, which would end a PvP in the middle.
	 * @param attackable the one that hates
	 * @param creature the one it may hate
	 * @param minHate the hate needed
	 * @return {@code true} if {@code attackable} hates {@code creature} at least {@code minHate}
	 */
	private static boolean hates(Attackable attackable, Creature creature, long minHate)
	{
		final AggroInfo info = attackable.getAggroList().get(creature);
		return (info != null) && (info.getHate() >= minHate);
	}
	
	/**
	 * @param skill a skill
	 * @return {@code true} if it stuns, roots, puts to sleep, paralyzes or fears, which a target already held doesn't need again
	 */
	private static boolean isHardDisable(Skill skill)
	{
		return HARD_DISABLES.computeIfAbsent(skill.getId(), _ ->
		{
			final List<AbstractEffect> effects = skill.getEffects(EffectScope.GENERAL);
			if (effects != null)
			{
				for (AbstractEffect effect : effects)
				{
					switch (effect.getClass().getSimpleName())
					{
						case "Stun":
						case "Root":
						case "Sleep":
						case "Paralyze":
						case "Fear":
						{
							return true;
						}
					}
				}
			}
			return false;
		});
	}
	
	/**
	 * Notices the end of a fight with a player: when the player it was fighting is no longer the one it hates most, and that is because the player went down, it says so ("gg"...). A dead player can't be caught in the aggro list itself, which forgets dead attackers.
	 * @param npc the fake player
	 * @param mostHated the creature it hates most now, can be {@code null}
	 */
	private void checkPvpTarget(Attackable npc, Creature mostHated)
	{
		final Creature pvpTarget = _pvpTarget;
		if ((pvpTarget == null) || (pvpTarget == mostHated))
		{
			return;
		}
		
		_pvpTarget = null;
		if ((pvpTarget.isPlayer() || pvpTarget.isPvpFakePlayer()) && pvpTarget.isAlikeDead())
		{
			npc.stopHating(pvpTarget);
			FakePlayerPvpManager.getInstance().onPlayerDefeated(npc);
		}
	}
	
	/**
	 * Stops fighting: no target, back to hunting.
	 * @param npc the fake player
	 */
	private void dropTarget(Attackable npc)
	{
		_progressTarget = null;
		setAttackTarget(null);
		npc.setTarget(null);
		setIntention(Intention.ACTIVE);
	}
	
	/**
	 * Picks up the items its kills dropped for it (only when FakePlayerCanDropItems is on), last one first, like the regular fake player AI.
	 * @param npc the fake player
	 * @return {@code true} if it is busy with it this tick
	 */
	private boolean pickUpDrops(Attackable npc)
	{
		final List<Item> drops = npc.getFakePlayerDrops();
		while (!drops.isEmpty())
		{
			final int index = drops.size() - 1;
			final Item item = drops.get(index);
			if ((item == null) || !item.isSpawned() || (item.getInstanceId() != npc.getInstanceId()) || (npc.calculateDistance2D(item) > FakePlayerPvpPersonality.of(npc).getHuntRange()))
			{
				drops.remove(index); // Taken by someone else, or too far to bother.
				continue;
			}
			
			if (npc.calculateDistance2D(item) > 50)
			{
				if (!npc.isMovementDisabled())
				{
					npc.setRunning();
					moveTo(item.getX(), item.getY(), item.getZ());
				}
				return true;
			}
			
			drops.remove(index);
			item.pickupMe(npc);
			if (GeneralConfig.SAVE_DROPPED_ITEM)
			{
				ItemsOnGroundManager.getInstance().removeObject(item);
			}
			
			if (item.getTemplate().hasExImmediateEffect() && (item.getTemplate().getSkills() != null))
			{
				for (SkillHolder holder : item.getTemplate().getSkills())
				{
					npc.doSimultaneousCast(holder.getSkill());
				}
			}
			return true;
		}
		
		return false;
	}
	
	/**
	 * @param npc the fake player
	 * @param target a creature it hates
	 * @return {@code true} if it can keep fighting {@code target}
	 */
	private static boolean isValidTarget(Attackable npc, Creature target)
	{
		// #1: never itself (isSameGroup says no for one creature, so a support targeting an ally passed as a diver on it and rooted itself).
		if ((target == npc) || target.isAlikeDead() || !target.isSpawned() || target.isInvisible() || (target.getInstanceId() != npc.getInstanceId()))
		{
			return false;
		}
		
		// Never a member of its own party, nor its own servitor.
		if (FakePartyManager.getInstance().isSameGroup(npc, target) || ((target instanceof FakePlayerPvpServitor) && (((FakePlayerPvpServitor) target).getOwner() == npc)))
		{
			return false;
		}

		// The teams of a simulation only fight each other.
		if (!FakeSimulationManager.canFight(npc, target))
		{
			return false;
		}

		if (npc.calculateDistance2D(target) > FakePlayerPvpPersonality.of(npc).getChaseRange())
		{
			return false;
		}
		
		// Someone it chose not to hit back.
		if (FakePlayerPvpManager.isRefusing(npc, target))
		{
			return false;
		}
		
		// Players are safe in town.
		return !isPvpEnemy(target) || !target.isInsideZone(ZoneId.PEACE);
	}
	
	/**
	 * @param npc the fake player
	 * @return the closest monster worth hunting, or {@code null} if there is none
	 */
	private Creature findPrey(Attackable npc)
	{
		final Spawn spawn = npc.getSpawn();
		final FakePlayerPvpPersonality personality = FakePlayerPvpPersonality.of(npc);
		final int leashRange = personality.getLeashRange();

		// Off its hunting ground (back from a chase, or walking back from town): home first, it would drop any monster right away.
		if ((spawn != null) && (npc.calculateDistance2D(spawn) > leashRange))
		{
			return null;
		}

		final long now = System.currentTimeMillis();
		final Creature unreachable = now < _unreachableUntil ? _unreachable : null;
		_stealDecisions.values().removeIf(until -> Math.abs(until) < now);
		Monster prey = null;
		double preyDistance = Double.MAX_VALUE;
		for (Monster monster : World.getInstance().getVisibleObjectsInRange(npc, Monster.class, personality.getHuntRange()))
		{
			if ((monster == unreachable) || monster.isDead() || monster.isFakePlayer() || monster.isRaid() || (monster instanceof Chest) || monster.isInvul() || !monster.isTargetable() || (monster.getInstanceId() != npc.getInstanceId()))
			{
				continue;
			}
			
			if (Math.abs(monster.getLevel() - npc.getLevel()) > MAX_HUNT_LEVEL_DIFFERENCE)
			{
				continue;
			}
			
			if ((spawn != null) && (monster.calculateDistance2D(spawn) > leashRange))
			{
				continue;
			}
			
			final double distance = npc.calculateDistance2D(monster);
			if ((distance >= preyDistance) || !monster.isAutoAttackable(npc))
			{
				continue;
			}
			
			// Don't take a monster someone else is already fighting, but some take the monsters of other fake players (FakePvpFakeKillStealChance).
			if (FakePlayerPvpConfig.AVOID_PLAYER_MONSTERS && isFoughtByOthers(monster, npc) && !(isFoughtByFakePlayersOnly(monster, npc) && wantsToSteal(npc, monster, now)))
			{
				continue;
			}
			
			if (!GeoEngine.getInstance().canSeeTarget(npc, monster))
			{
				continue;
			}
			
			prey = monster;
			preyDistance = distance;
		}
		
		return prey;
	}
	
	/**
	 * @param monster a monster
	 * @param npc the fake player
	 * @return {@code true} if the ones fighting {@code monster} (besides {@code npc}) are roaming fake players, no player nor summon
	 */
	private static boolean isFoughtByFakePlayersOnly(Monster monster, Attackable npc)
	{
		final WorldObject monsterTarget = monster.getTarget();
		if ((monsterTarget != null) && monsterTarget.isPlayable())
		{
			return false;
		}
		
		boolean fakePlayers = false;
		for (Creature attacker : monster.getAggroList().keySet())
		{
			if ((attacker == npc) || !hates(monster, attacker, 1))
			{
				continue;
			}
			
			if (!attacker.isPvpFakePlayer())
			{
				return false;
			}
			fakePlayers = true;
		}
		
		return fakePlayers;
	}
	
	/**
	 * @param npc the fake player
	 * @param monster a monster another fake player fights
	 * @param now the current time
	 * @return {@code true} if it takes {@code monster} anyway, decided once for a while per monster (see {@link FakePlayerPvpPersonality#getFakeKillStealChance})
	 */
	private boolean wantsToSteal(Attackable npc, Monster monster, long now)
	{
		final int chance = FakePlayerPvpPersonality.of(npc).getFakeKillStealChance();
		if ((chance <= 0) || npc.isTrialDuelist())
		{
			return false;
		}
		
		return _stealDecisions.computeIfAbsent(monster.getObjectId(), id -> Rnd.get(100) < chance ? now + STEAL_MEMORY : -(now + STEAL_MEMORY)) > 0;
	}
	
	private static boolean isFoughtByOthers(Monster monster, Attackable npc)
	{
		final WorldObject monsterTarget = monster.getTarget();
		if ((monsterTarget != null) && (monsterTarget != npc) && (monsterTarget.isPlayable() || monsterTarget.isFakePlayer()) && monster.isInCombat())
		{
			return true;
		}
		
		for (Creature attacker : monster.getAggroList().keySet())
		{
			if ((attacker != npc) && (attacker.isPlayable() || attacker.isFakePlayer()) && hates(monster, attacker, 1))
			{
				return true;
			}
		}
		
		return false;
	}
	
	/**
	 * @param npc the caster
	 * @param skill the skill
	 * @param target its target
	 * @return {@code true} if {@code npc} could cast {@code skill} right now like a player would (reuse, HP, silence, and the skill's own conditions such as the weapon or being behind the target). Fake players don't use MP.
	 */
	private static boolean canCast(Attackable npc, Skill skill, Creature target)
	{
		if (npc.isSkillDisabled(skill))
		{
			return false;
		}
		
		if ((skill.getHpConsume() > 0) && (npc.getCurrentHp() <= skill.getHpConsume()))
		{
			return false;
		}
		
		if (!skill.isStatic() && (skill.isMagic() ? npc.isMuted() : npc.isPhysicalMuted()))
		{
			return false;
		}
		
		// Backstab fails from the front: never waste it there.
		if ((target != npc) && isBackstab(skill) && npc.isInFrontOf(target))
		{
			return false;
		}
		
		// Disarm only takes a player's weapon (monsters have none to lose), and not while one is already gone.
		if ((target != npc) && hasEffect(skill, "Disarm") && ((!target.isPlayer() && !target.isPvpFakePlayer()) || target.isDisarmed()))
		{
			return false;
		}
		
		return skill.checkPreConditions(npc, target);
	}
	
	/**
	 * @param npc the fake player
	 * @param target its target
	 * @param skills skills in order of preference
	 * @param debuff {@code true} to skip the ones already on the target
	 * @param reach the distance to the target for a ranged fighter, which prefers skills that reach it from where it stands, -1 for melee
	 * @param pvp {@code true} against a player, the only time long cooldown skills are used
	 * @param defenses what the defensive buffs it noticed on the target keep from working (see {@link Defense}), its skills that wouldn't get through are left aside
	 * @return a skill it can cast now, or {@code null}
	 */
	private static Skill pickSkill(Attackable npc, Creature target, List<Skill> skills, boolean debuff, double reach, boolean pvp, int defenses)
	{
		if (skills.isEmpty())
		{
			return null;
		}
		
		// A stunned or rooted target doesn't need another stun or root yet, but it is the moment for the other debuffs (Hex, Cancel...).
		final boolean disabled = debuff && isDisabled(target);
		final List<Skill> ready = new ArrayList<>(3);
		Skill outOfReach = null;
		for (Skill skill : skills)
		{
			if ((debuff && (target.isAffectedBySkill(skill.getId()) || (disabled && isHardDisable(skill)))) || (!pvp && isPvpOnly(skill)) || isBlocked(defenses, skill, debuff) || !canCast(npc, skill, target) || !isWorthCasting(npc, target, skill, pvp))
			{
				continue;
			}
			
			if ((reach >= 0) && (skill.getCastRange() > 0) && (skill.getCastRange() < reach))
			{
				if (outOfReach == null)
				{
					outOfReach = skill;
				}
				continue;
			}
			
			ready.add(skill);
			if (ready.size() == 3)
			{
				break;
			}
		}
		
		if (ready.isEmpty())
		{
			return outOfReach;
		}
		
		// Mostly the best one, sometimes another ready one.
		return Rnd.get(100) < 70 ? ready.get(0) : ready.get(Rnd.get(ready.size()));
	}
	
	/**
	 * @param skill a skill, or {@code null}
	 * @param onlyInReach {@code true} to only keep a skill that reaches the target from where the fake player stands
	 * @param reach the distance to the target
	 * @return {@code skill}, or {@code null} if it has to be dropped
	 */
	private static Skill inReach(Skill skill, boolean onlyInReach, double reach)
	{
		if ((skill == null) || !onlyInReach)
		{
			return skill;
		}
		
		final int range = skill.getCastRange() > 0 ? skill.getCastRange() : skill.getAffectRange();
		return range >= reach ? skill : null;
	}
	
	/**
	 * Tanks and tyrants switch to their bow when a player keeps out of melee reach: after chasing for a while, sooner when there is no straight way to the player, right away when they can't move. They switch back to their weapon (and shield) when the player comes close, or
	 * when they fight a monster again. A real player swaps weapons instantly, so it doesn't cost a tick.
	 * @param npc the fake player
	 * @param profile its profile, with a bow
	 * @param target its target
	 * @param gap the distance to the target, collisions excluded
	 * @param now the current time
	 */
	private void chooseWeapon(Attackable npc, FakePlayerPvpProfile profile, Creature target, double gap, long now)
	{
		final boolean close = gap <= FakePlayerPvpConfig.WEAPON_SWAP_MELEE_DISTANCE;
		if ((now < _nextWeaponSwapTime) || npc.isAttackingNow() || npc.isCastingNow() || npc.isStunned() || npc.isSleeping() || npc.isParalyzed())
		{
			return;
		}
		
		final FakePlayerPvpManager manager = FakePlayerPvpManager.getInstance();
		// #16: the bow is for one that gets away. Against one that stands (a tank under Ultimate Defense, a fighter coming at it) it fights with its weapon, or two tanks end up shooting and healing at bow range until the time runs out.
		final boolean worthBow = npc.isMovementDisabled() || isGettingAway(npc, target);
		if (profile.isBowHeld())
		{
			if ((!isPvpEnemy(target) || close || !worthBow) && manager.equipWeapon(npc, profile.getMainWeapon()))
			{
				_nextWeaponSwapTime = now + FakePlayerPvpConfig.WEAPON_SWAP_INTERVAL;
			}
			return;
		}
		
		// Not worth it unless the player is at bow range, or almost, and getting away.
		if ((_chaseTarget == null) || (gap > (profile.getBow().getAttackRange() + 300)) || !worthBow)
		{
			return;
		}
		
		// Rooted: right away. No straight way to the player (a ledge, a wall): a shorter chase, pathfinding may still get there.
		long chaseTime = profile.getPersonality().getWeaponSwapChaseTime();
		if (npc.isMovementDisabled())
		{
			chaseTime = 0;
		}
		else if (!GeoEngine.getInstance().canMoveToTarget(npc.getX(), npc.getY(), npc.getZ(), target.getX(), target.getY(), target.getZ(), npc.getInstanceId()))
		{
			chaseTime /= 2;
		}
		
		if (((now - _chaseStart) >= chaseTime) && manager.equipWeapon(npc, profile.getBow()))
		{
			_nextWeaponSwapTime = now + FakePlayerPvpConfig.WEAPON_SWAP_INTERVAL;
		}
	}
	
	/**
	 * @param npc the fake player
	 * @param target its target
	 * @return {@code true} if {@code target} moves somewhere farther from {@code npc} than where it is
	 */
	private static boolean isGettingAway(Attackable npc, Creature target)
	{
		if (!target.isMoving())
		{
			return false;
		}
		final double now = Math.hypot(target.getX() - npc.getX(), target.getY() - npc.getY());
		final double then = Math.hypot(target.getXdestination() - npc.getX(), target.getYdestination() - npc.getY());
		return then > now;
	}

	/**
	 * Warriors switch to their polearm when more than {@link FakePlayerPvpConfig#POLEARM_SWAP_MONSTERS} monsters surround them, so their normal attacks hit several of them at once. They switch back to their weapon once only
	 * {@link FakePlayerPvpConfig#POLEARM_PUT_AWAY_MONSTERS} or fewer are left, or when they fight a player. A real player swaps weapons instantly, so it doesn't cost a tick.
	 * @param npc the fake player
	 * @param profile its profile, with a polearm
	 * @param target its target
	 * @param now the current time
	 */
	private void choosePolearm(Attackable npc, FakePlayerPvpProfile profile, Creature target, long now)
	{
		if ((now < _nextWeaponSwapTime) || npc.isAttackingNow() || npc.isCastingNow() || npc.isStunned() || npc.isSleeping() || npc.isParalyzed())
		{
			return;
		}
		
		final FakePlayerPvpManager manager = FakePlayerPvpManager.getInstance();
		if (profile.isPolearmHeld())
		{
			if ((isPvpEnemy(target) || (countSurroundingMonsters(npc) <= FakePlayerPvpConfig.POLEARM_PUT_AWAY_MONSTERS)) && manager.equipWeapon(npc, profile.getMainWeapon()))
			{
				_nextWeaponSwapTime = now + FakePlayerPvpConfig.WEAPON_SWAP_INTERVAL;
			}
			return;
		}
		
		// Only against monsters, and not with the bow out (it goes back to its weapon first).
		if (isPvpEnemy(target) || profile.isBowHeld())
		{
			return;
		}
		
		if ((countSurroundingMonsters(npc) > FakePlayerPvpConfig.POLEARM_SWAP_MONSTERS) && manager.equipWeapon(npc, profile.getPolearm()))
		{
			_nextWeaponSwapTime = now + FakePlayerPvpConfig.WEAPON_SWAP_INTERVAL;
			
			// The combo it was playing needs its weapon.
			if (_combo != null)
			{
				endCombo(now);
			}
		}
	}
	
	/**
	 * A hybrid Soul Hound (a rapier with Health, a mace with Acumen) swaps like a player that keeps its mace on a hotkey: against a melee player it takes the mace out and fights like a mage, keeping its distance; against anyone else it takes it out
	 * from {@link FakePlayerPvpConfig#CASTER_SWAP_DISTANCE} or farther when one of its mace combos (weapon="CASTER") is ready, casts it, and goes back to its rapier once it is done, for its HP and its physical skills. Monsters, and Final Form,
	 * get the rapier. A real player swaps weapons instantly, so it doesn't cost a tick, but it doesn't break off a combo for it.
	 * @param npc the fake player
	 * @param profile its profile, with a magic weapon
	 * @param target its target
	 * @param now the current time
	 */
	private void chooseCaster(Attackable npc, FakePlayerPvpProfile profile, Creature target, long now)
	{
		if ((now < _nextWeaponSwapTime) || npc.isAttackingNow() || npc.isCastingNow() || npc.isStunned() || npc.isSleeping() || npc.isParalyzed())
		{
			return;
		}
		
		final boolean held = profile.isCasterHeld();
		final boolean comboOn = (_combo != null) && (_combo.getStance() != FakePlayerPvpCombo.Stance.ANY);
		final boolean wantsCaster;
		if (!isPvpEnemy(target) || profile.isTransformed() || profile.isBowHeld() || profile.isPolearmHeld())
		{
			wantsCaster = false;
		}
		else if (comboOn)
		{
			return; // It finishes the combo with the weapon it started it with.
		}
		else if (isMeleeThreat(target))
		{
			wantsCaster = true;
		}
		else
		{
			// From afar, for a mace combo; once it is cast, back to the rapier (unless another one is ready and the target still keeps away).
			wantsCaster = (npc.calculateDistance2D(target) >= FakePlayerPvpConfig.CASTER_SWAP_DISTANCE) && (now >= _nextComboTime) && isStanceComboReady(npc, profile, target, FakePlayerPvpCombo.Stance.CASTER);
		}
		
		if (wantsCaster == held)
		{
			return;
		}
		
		if (FakePlayerPvpManager.getInstance().equipWeapon(npc, wantsCaster ? profile.getCaster() : profile.getMainWeapon()))
		{
			_nextWeaponSwapTime = now + FakePlayerPvpConfig.WEAPON_SWAP_INTERVAL;
		}
	}
	
	/**
	 * @return {@code true} if one of its PvP combos played with {@code stance} could start now
	 */
	private static boolean isStanceComboReady(Attackable npc, FakePlayerPvpProfile profile, Creature target, FakePlayerPvpCombo.Stance stance)
	{
		for (FakePlayerPvpCombo.Chain combo : profile.getCombos())
		{
			if ((combo.getStance() == stance) && isComboReady(npc, profile, combo, target))
			{
				return true;
			}
		}
		return false;
	}
	
	/**
	 * @param profile its profile
	 * @param combo a combo
	 * @return {@code true} if {@code combo} is played with the weapon it holds now
	 */
	private static boolean isStanceHeld(FakePlayerPvpProfile profile, FakePlayerPvpCombo.Chain combo)
	{
		switch (combo.getStance())
		{
			case MAIN:
			{
				return !profile.isCasterHeld();
			}
			case CASTER:
			{
				return profile.isCasterHeld();
			}
			default:
			{
				return true;
			}
		}
	}
	
	/**
	 * @param npc the fake player
	 * @return how many monsters fight it close by (the ones its polearm sweeps)
	 */
	private static int countSurroundingMonsters(Attackable npc)
	{
		int count = 0;
		for (Monster monster : World.getInstance().getVisibleObjectsInRange(npc, Monster.class, FakePlayerPvpConfig.POLEARM_SURROUND_RANGE))
		{
			if (!monster.isAlikeDead() && !monster.isFakePlayer() && hates(monster, npc, 1))
			{
				count++;
			}
		}
		
		return count;
	}
	
	/**
	 * Casts {@code skill} on {@code target}, or walks into range first.
	 * @return {@code true} if something was done this tick
	 */
	private boolean useSkill(Attackable npc, Creature target, Skill skill, double distance, int collision, boolean canMove)
	{
		return (skill != null) && (castOrApproach(npc, target, skill, distance, collision, canMove) != CastResult.FAILED);
	}
	
	/**
	 * @param skill a skill
	 * @return how far from the target (collisions excluded) it can be used: its cast range, or its area for one centered on the caster
	 */
	private static int getSkillReach(Skill skill)
	{
		if (skill.getCastRange() > 0)
		{
			return skill.getCastRange();
		}
		
		return skill.getAffectRange() > 0 ? skill.getAffectRange() : 80;
	}
	
	private enum CastResult
	{
		CAST,
		MOVING,
		FAILED
	}
	
	/**
	 * Casts {@code skill} on {@code target}, or walks into range first.
	 * @return what happened
	 */
	private CastResult castOrApproach(Attackable npc, Creature target, Skill skill, double distance, int collision, boolean canMove)
	{
		final int range = getSkillReach(skill);
		final boolean inRange = (distance - collision) <= range;
		if (!inRange || !GeoEngine.getInstance().canSeeTarget(npc, target))
		{
			if (!canMove)
			{
				return CastResult.FAILED;
			}

			// In range but something is in the way (a rock, the crest of a slope): it goes where it sees its target; walking "into range" would get it nowhere.
			if (inRange)
			{
				trace(Reason.FIRING_POSITION, target, skill, getSkillKind(skill));
				moveToSee(npc, target, range);
				return CastResult.MOVING;
			}

			trace(Reason.APPROACH, target, skill, getSkillKind(skill));
			moveToPawn(target, Math.max(20, range - 20));
			return CastResult.MOVING;
		}

		if (target == _progressTarget)
		{
			_progressTime = System.currentTimeMillis();
		}

		trace(Reason.CAST, target, skill, getSkillKind(skill));
		noticeDefenses(target, skill.isMagic());
		clientStopMoving(null);

		// #10: who it last debuffed, to tell when a debuff comes back.
		if (skill.isDebuff() && (target != npc))
		{
			_lastDebuffTarget = target;
			_lastDebuffTime = System.currentTimeMillis();
		}

		// #21: an archer's skill on an enemy is a shot too.
		final FakePlayerPvpProfile shooter = npc.getTemplate().getFakePlayerPvpProfile();
		if ((shooter != null) && (target != npc) && skill.hasNegativeEffect() && ((shooter.getRole() == Role.ARCHER) || shooter.isBowHeld()))
		{
			markShot();
		}
		
		// Blink and Warp jump back from where it faces: it faces the player it gets away from.
		if (hasEffect(skill, "Blink"))
		{
			npc.setHeading(LocationUtil.calculateHeadingFrom(npc, target));
		}
		
		npc.setTarget(target);
		npc.doCast(skill);
		return CastResult.CAST;
	}
	
	/**
	 * Starts or continues a combo (see data/FakePlayerPvp.xml). Steps are played in order; an optional step is skipped when it can't be used right now, a required one is waited for up to {@link #COMBO_STEP_TIMEOUT}, and blows marked "behind" first try to get behind
	 * the target.
	 * @return {@code true} if the combo acted this tick
	 */
	private boolean playCombo(Attackable npc, FakePlayerPvpProfile profile, Creature target, boolean pvp, double distance, int collision, boolean canMove, long now)
	{
		if ((_combo != null) && ((_comboTarget != target) || (now > _comboEnd)))
		{
			endCombo(now);
		}
		
		if (_combo == null)
		{
			if (now < _nextComboTime)
			{
				return false;
			}
			
			for (FakePlayerPvpCombo.Chain combo : profile.getCombos())
			{
				if ((!combo.isPvp() || pvp) && isStanceHeld(profile, combo) && (Rnd.get(100) < combo.getChance()) && isComboReady(npc, profile, combo, target))
				{
					_combo = combo;
					_comboTarget = target;
					_comboStep = 0;
					_comboStepStart = now;
					_comboEnd = now + COMBO_TIMEOUT;
					break;
				}
			}
			
			if (_combo == null)
			{
				return false;
			}
		}
		
		while (_comboStep < _combo.size())
		{
			final FakePlayerPvpCombo.Step step = _combo.getStep(_comboStep);
			final Skill skill = _combo.getSkill(_comboStep);
			if (skill == null)
			{
				nextComboStep(now);
				continue;
			}
			
			// A buff before the burst.
			if (step.isSelf())
			{
				final boolean active = FakePlayerPvpManager.isBuffActive(npc, skill);
				if (!active && canCast(npc, skill, npc))
				{
					clientStopMoving(null);
					npc.setTarget(npc);
					npc.doCast(skill);
					npc.setTarget(target);
					nextComboStep(now);
					return true;
				}
				
				if (step.isOptional() || active)
				{
					nextComboStep(now);
					continue;
				}
				return waitForStep(now);
			}
			
			// The debuff is already on: nothing to do.
			if (isAlreadyOn(skill, target))
			{
				nextComboStep(now);
				continue;
			}
			
			// Follow-ups that only make sense on a stunned/held target, or on one that casts (a silence).
			if ((step.needsDisabledTarget() && !isDisabled(target)) || (step.needsMageTarget() && !castsMagic(target)))
			{
				if (step.isOptional())
				{
					nextComboStep(now);
					continue;
				}
				return waitForStep(now);
			}
			
			// Blows: get behind the target first.
			if (step.isBehind() && canMove && !npc.isBehind(target) && ((now - _comboStepStart) < BEHIND_TIMEOUT))
			{
				moveBehind(npc, target, collision);
				return true;
			}
			
			if (!canCast(npc, skill, target) || !FakePlayerPvpOraclesWarchiefs.isWorthCasting(npc, target, skill))
			{
				if (step.isOptional())
				{
					nextComboStep(now);
					continue;
				}
				return waitForStep(now);
			}
			
			switch (castOrApproach(npc, target, skill, distance, collision, canMove))
			{
				case CAST:
				{
					nextComboStep(now);
					return true;
				}
				case MOVING:
				{
					return true;
				}
				default:
				{
					if (step.isOptional())
					{
						nextComboStep(now);
						continue;
					}
					endCombo(now);
					return false;
				}
			}
		}
		
		endCombo(now);
		return false;
	}
	
	/**
	 * A combo can start when every required step is learned and off cooldown, its energy is there, and its first step can be used now.
	 */
	private static boolean isComboReady(Attackable npc, FakePlayerPvpProfile profile, FakePlayerPvpCombo.Chain combo, Creature target)
	{
		int chargesNeeded = 0;
		boolean firstChecked = false;
		for (int i = 0; i < combo.size(); i++)
		{
			final FakePlayerPvpCombo.Step step = combo.getStep(i);
			final Skill skill = combo.getSkill(i);
			if ((skill == null) || step.isOptional())
			{
				continue;
			}
			
			if (npc.isSkillDisabled(skill))
			{
				return false;
			}
			
			chargesNeeded += skill.getChargeConsumeCount();
			if (!firstChecked && !step.isSelf() && isAlreadyOn(skill, target))
			{
				continue; // Its opening debuff is still on the target, the combo starts at the next step.
			}
			
			if (!firstChecked)
			{
				firstChecked = true;
				if (!step.isSelf() && ((step.needsDisabledTarget() && !isDisabled(target)) || (step.needsMageTarget() && !castsMagic(target)) || !canCast(npc, skill, target) || !FakePlayerPvpOraclesWarchiefs.isWorthCasting(npc, target, skill)))
				{
					return false;
				}
			}
		}
		
		return firstChecked && (profile.getCharges() >= chargesNeeded);
	}
	
	private void nextComboStep(long now)
	{
		_comboStep++;
		_comboStepStart = now;
	}
	
	/**
	 * A required step isn't usable yet: keep fighting normally meanwhile, and give up on the combo if it takes too long.
	 * @return always {@code false}, so the regular logic acts this tick
	 */
	private boolean waitForStep(long now)
	{
		if ((now - _comboStepStart) > COMBO_STEP_TIMEOUT)
		{
			endCombo(now);
		}
		return false;
	}
	
	private void endCombo(long now)
	{
		_combo = null;
		_comboTarget = null;
		_comboStep = 0;
		
		// A class that fights with its normal attack goes back to it for a while between two combos.
		_nextComboTime = _autoAttacker ? (now + 8000 + Rnd.get(7000)) : (now + 1000 + Rnd.get(1500));
	}
	
	/**
	 * @return {@code true} if {@code skill} is a debuff that is already on {@code target}
	 */
	private static boolean isAlreadyOn(Skill skill, Creature target)
	{
		return skill.isContinuous() && skill.hasNegativeEffect() && target.isAffectedBySkill(skill.getId());
	}
	
	/**
	 * @param target a creature
	 * @return {@code true} if it can't act or move freely (stun, sleep, paralysis, root)
	 */
	/**
	 * @param npc an archer or mage
	 * @param target its target
	 * @param gap the distance between them, collisions excluded
	 * @return how many milliseconds it may cast before {@code target}, a melee after it, reaches it; {@link Long#MAX_VALUE} if the target isn't a melee coming at it or can't move
	 */
	private static long getSafeCastTime(Attackable npc, Creature target, double gap)
	{
		if ((target.getTarget() != npc) || !isMeleeThreat(target) || isDisabled(target) || target.isMovementDisabled())
		{
			return Long.MAX_VALUE;
		}
		return (long) ((Math.max(0, gap - target.getPhysicalAttackRange()) * 1000) / Math.max(1, target.getMoveSpeed()));
	}

	/**
	 * @param target a creature
	 * @return {@code true} if it fights in melee (not with a bow or spells)
	 */
	private static boolean isMeleeThreat(Creature target)
	{
		if (target.getPhysicalAttackRange() > 300)
		{
			return false; // A bow.
		}
		if (target.isPlayer())
		{
			return !target.asPlayer().isMageClass();
		}
		final FakePlayerPvpProfile profile = target.isNpc() ? target.asNpc().getTemplate().getFakePlayerPvpProfile() : null;
		return (profile == null) || profile.isTransformed() || (!profile.getRole().isRanged() && !profile.isCasterHeld());
	}

	/**
	 * @param npc the caster
	 * @param skill a skill
	 * @return how long casting it takes, in milliseconds, with its attack or casting speed
	 */
	private static long getCastTime(Attackable npc, Skill skill)
	{
		final int time = skill.getHitTime() + skill.getCoolTime();
		return skill.isStatic() ? time : Formulas.calcAtkSpd(npc, skill, time);
	}

	/**
	 * @param npc the caster
	 * @param skills skills
	 * @param safeCast how long it may cast (see {@link #getSafeCastTime})
	 * @return those it can finish in that time, all of them when time doesn't press
	 */
	private static List<Skill> affordable(Attackable npc, List<Skill> skills, long safeCast)
	{
		if (safeCast == Long.MAX_VALUE)
		{
			return skills;
		}

		final List<Skill> affordable = new ArrayList<>(skills.size());
		for (Skill skill : skills)
		{
			final long time = getCastTime(npc, skill);
			if ((time <= QUICK_CAST) || (time <= (safeCast + CAST_MARGIN)))
			{
				affordable.add(skill);
			}
		}
		return affordable;
	}

	private static boolean isDisabled(Creature target)
	{
		return target.isStunned() || target.isSleeping() || target.isParalyzed() || target.isRooted();
	}
	
	/**
	 * Runs to the point right behind {@code target} (it faces its heading), for blows like Backstab.
	 */
	private void moveBehind(Attackable npc, Creature target, int collision)
	{
		final double angle = (target.getHeading() * 2 * Math.PI) / 65536.0;
		final int offset = collision + 25;
		final int x = target.getX() - (int) (Math.cos(angle) * offset);
		final int y = target.getY() - (int) (Math.sin(angle) * offset);
		final Location destination = GeoEngine.getInstance().getValidLocation(npc.getX(), npc.getY(), npc.getZ(), x, y, target.getZ(), npc.getInstanceId());
		npc.setRunning();
		moveTo(destination.getX(), destination.getY(), destination.getZ());
	}
	
	/**
	 * Area skills are only worth it with at least two enemies in their area, except the big ones in PvP (Flame Hawk, Demolition Impact, Arrow Rain...), which players use as burst skills on a single target.
	 * @return {@code true} if {@code skill} is a single target skill or would hit two or more enemies
	 */
	private static boolean isWorthCasting(Attackable npc, Creature target, Skill skill, boolean pvp)
	{
		// A Prophecy of Doom that would start over, Spell Turning on a target that doesn't cast.
		if (!FakePlayerPvpOraclesWarchiefs.isWorthCasting(npc, target, skill))
		{
			return false;
		}
		
		final WorldObject center;
		switch (skill.getTargetType())
		{
			case AREA:
			case FRONT_AREA:
			case BEHIND_AREA:
			{
				center = target;
				break;
			}
			case AURA:
			case FRONT_AURA:
			case BEHIND_AURA:
			{
				center = npc;
				break;
			}
			default:
			{
				return true;
			}
		}
		
		if (pvp && (skill.getReuseDelay() >= 10000))
		{
			return true;
		}
		
		final int radius = skill.getAffectRange() > 0 ? skill.getAffectRange() : 150;
		final FakePlayerPvpProfile profile = npc.getTemplate().getFakePlayerPvpProfile();
		final boolean spot = (profile != null) && profile.isSpotFighter();
		int enemies = center == target ? 1 : 0;
		for (Creature creature : World.getInstance().getVisibleObjectsInRange(center, Creature.class, radius))
		{
			// In a PvP spot everyone of another clan is flagged, so an area skill catches them all: a crowd is worth it.
			if ((creature != npc) && !creature.isDead() && ((creature == target) || hates(npc, creature, 1) || (spot && isFairAreaEnemy(npc, creature))))
			{
				if (++enemies >= 2)
				{
					return true;
				}
			}
		}
		
		return false;
	}
	
	/**
	 * Casts the first usable skill of {@code skills} on itself.
	 * @param npc the fake player
	 * @param target its current target, restored after the cast
	 * @param skills the skills
	 * @param recast {@code true} to cast even if the effect is already on (heals), {@code false} to only put back missing effects (buffs)
	 * @param pvp {@code true} to also use the long cooldown ones
	 * @return {@code true} if it cast one
	 */
	private boolean castOnSelf(Attackable npc, Creature target, List<Skill> skills, boolean recast, boolean pvp)
	{
		for (Skill skill : skills)
		{
			if ((!recast && FakePlayerPvpManager.isBuffActive(npc, skill)) || (!pvp && isPvpOnly(skill) && !FakePlayerPvpOraclesWarchiefs.ignoresPvpOnly(skill)))
			{
				continue;
			}
			
			final TargetType targetType = skill.getTargetType();
			if ((targetType != TargetType.SELF) && (targetType != TargetType.ONE) && (targetType != TargetType.PARTY) && (targetType != TargetType.AURA))
			{
				continue;
			}
			
			if (!canCast(npc, skill, npc) || !FakePlayerPvpOraclesWarchiefs.isWorthSelfCast(npc, target, skill))
			{
				continue;
			}
			
			clientStopMoving(null);
			npc.setTarget(npc);
			npc.doCast(skill);
			npc.setTarget(target);
			return true;
		}
		
		return false;
	}
	
	/**
	 * @param skill a skill
	 * @param effectName the simple class name of an effect handler (Disarm, Blink...)
	 * @return {@code true} if {@code skill} has that effect, on its targets or on the caster
	 */
	private static boolean hasEffect(Skill skill, String effectName)
	{
		for (EffectScope scope : new EffectScope[]
		{
			EffectScope.GENERAL,
			EffectScope.SELF
		})
		{
			final List<AbstractEffect> effects = skill.getEffects(scope);
			if (effects != null)
			{
				for (AbstractEffect effect : effects)
				{
					if (effectName.equals(effect.getClass().getSimpleName()))
					{
						return true;
					}
				}
			}
		}
		
		return false;
	}
	
	/**
	 * @param skill a skill
	 * @return {@code true} if it has a Backstab effect, which only lands from the side or behind
	 */
	private static boolean isBackstab(Skill skill)
	{
		final List<AbstractEffect> effects = skill.getEffects(EffectScope.GENERAL);
		if (effects != null)
		{
			for (AbstractEffect effect : effects)
			{
				if ("Backstab".equals(effect.getClass().getSimpleName()))
				{
					return true;
				}
			}
		}
		
		return false;
	}
	
	/**
	 * @param target the creature it fights
	 * @return {@code true} for a monster that isn't spoiled yet and has something to sweep
	 */
	private static boolean isSpoilable(Creature target)
	{
		if (!target.isMonster() || target.isFakePlayer() || target.isAlikeDead() || target.asMonster().isSpoiled())
		{
			return false;
		}
		
		final List<DropHolder> spoils = target.asMonster().getTemplate().getSpoilList();
		return (spoils != null) && !spoils.isEmpty();
	}
	
	/**
	 * @param skill a skill
	 * @return {@code true} if its cooldown is long enough that a player would save it for PvP
	 */
	private static boolean isPvpOnly(Skill skill)
	{
		return (FakePlayerPvpConfig.PVP_ONLY_REUSE > 0) && (skill.getReuseDelay() >= FakePlayerPvpConfig.PVP_ONLY_REUSE);
	}
	
	/**
	 * @param npc an archer
	 * @return how long, in milliseconds, it stands still for a shot: until the arrow flies and half the bow's reuse is over
	 */
	private static long getShotTime(Attackable npc)
	{
		return (npc.calculateTimeBetweenAttacks() + npc.calculateReuseTime(npc.getActiveWeaponItem())) / 2;
	}
	
	/**
	 * An archer's step back from a melee: straight away from it, or at an angle when that way is blocked. Unlike {@link #kiteStep}, the next one may follow as soon as this one ends (and a shot is off).
	 * @param npc the archer
	 * @param target the melee after it
	 * @param now the current time
	 * @return {@code true} if it started moving
	 */
	private boolean archerKiteStep(Attackable npc, Creature target, long now)
	{
		if (now < _nextKiteTime)
		{
			return false;
		}
		
		double dx = npc.getX() - target.getX();
		double dy = npc.getY() - target.getY();
		double length = Math.hypot(dx, dy);
		if (length < 1)
		{
			final double angle = Rnd.nextDouble() * 2 * Math.PI;
			dx = Math.cos(angle);
			dy = Math.sin(angle);
			length = 1;
		}
		dx /= length;
		dy /= length;
		
		final int step = Math.max(FakePlayerPvpPersonality.of(npc).getKiteStep(), ARCHER_KITE_STEP);
		final boolean flip = Rnd.nextBoolean(); // Not always the same side first.
		for (int i = -1; i < ARCHER_KITE_ANGLES.length; i++)
		{
			final double angle = i < 0 ? 0 : Math.toRadians(flip ? -ARCHER_KITE_ANGLES[i] : ARCHER_KITE_ANGLES[i]);
			final double cos = Math.cos(angle);
			final double sin = Math.sin(angle);
			final int x = npc.getX() + (int) (((dx * cos) - (dy * sin)) * step);
			final int y = npc.getY() + (int) (((dx * sin) + (dy * cos)) * step);
			final Location destination = GeoEngine.getInstance().getValidLocation(npc.getX(), npc.getY(), npc.getZ(), x, y, npc.getZ() + 30, npc.getInstanceId());
			if ((npc.calculateDistance2D(destination) < (step / 2)) || !GeoEngine.getInstance().canMoveToTarget(npc.getX(), npc.getY(), npc.getZ(), destination.getX(), destination.getY(), destination.getZ(), npc.getInstanceId()))
			{
				continue;
			}
			
			trace(Reason.KITE_STEP, target, null, i < 0 ? null : "ANGLE");
			npc.setRunning();
			moveTo(destination.getX(), destination.getY(), destination.getZ());
			_kiteEndTime = now + Math.min(3000, (long) ((npc.calculateDistance2D(destination) * 1000.0) / Math.max(1, npc.getMoveSpeed())));
			_nextKiteTime = _kiteEndTime;
			return true;
		}
		
		trace(Reason.KITE_CORNERED, target);
		_nextKiteTime = now + 1500; // Cornered every way - shoot back for a moment.
		return false;
	}
	
	/**
	 * Steps back from {@code target} by its {@link FakePlayerPvpConfig#KITE_STEP}, onto a point geodata allows.
	 * @return {@code true} if it started moving
	 */
	private boolean kiteStep(Attackable npc, Creature target, long now)
	{
		return kiteStep(npc, target, now, FakePlayerPvpPersonality.of(npc).getKiteStep());
	}
	
	/**
	 * Steps back from {@code target} by {@code step}, onto a point geodata allows.
	 * @return {@code true} if it started moving
	 */
	private boolean kiteStep(Attackable npc, Creature target, long now, int step)
	{
		// #20: away from all the enemies around, not only from its target (stepping back from a diver used to walk into its friends).
		final Location destination = pickRetreat(npc, target, step, null, 0, null);
		if (destination == null)
		{
			trace(Reason.KITE_CORNERED, target);
			_nextKiteTime = now + 3000; // Cornered - fight for a while.
			return false;
		}

		trace(Reason.KITE_STEP, target);
		npc.setRunning();
		moveTo(destination.getX(), destination.getY(), destination.getZ());
		_kiteEndTime = now + Math.min(3000, (long) ((step * 1000.0) / Math.max(1, npc.getMoveSpeed())));
		_nextKiteTime = now + 3000 + Rnd.get(2000);
		return true;
	}
	
	/** #20: enemies this close count when it picks where to step back to. */
	private static final int RETREAT_SCAN_RANGE = 1200;
	/** #20: a step that stays near its party stays this close to it (or gets closer). */
	private static final int RETREAT_KEEP_NEAR = 400;
	/** #20: the directions it tries, from straight away from the enemies around (radians). */
	private static final double[] RETREAT_TURNS =
	{
		0,
		0.4,
		-0.4,
		0.8,
		-0.8,
		1.2,
		-1.2,
		1.6,
		-1.6,
		2.1,
		-2.1
	};
	/** #20: a step that loses sight of the target it keeps in range scores this much less. */
	private static final double RETREAT_NO_LOS_MALUS = 300;

	/**
	 * #20: where to step back to. Of a fan of points {@code step} away, around straight away from the enemies around (the closest ones weigh most), the one farthest from them all: the distance to the closest enemy counts most, the average distance a little.
	 * @param npc the fake player
	 * @param threat the enemy it steps back from (counted even beyond {@link #RETREAT_SCAN_RANGE})
	 * @param step how far
	 * @param keepWithin a target to keep within {@code range} (an archer's shot), {@code null} for none
	 * @param range how close {@code keepWithin} stays
	 * @param keepNear where its party is: a point farther than {@link #RETREAT_KEEP_NEAR} from it, and farther than now, isn't taken; {@code null} for none
	 * @return the point, {@code null} if none it can walk to
	 */
	private static Location pickRetreat(Attackable npc, Creature threat, int step, Creature keepWithin, int range, Location keepNear)
	{
		final List<Creature> enemies = new ArrayList<>();
		double centerX = 0;
		double centerY = 0;
		double weight = 0;
		for (Creature enemy : World.getInstance().getVisibleObjectsInRange(npc, Creature.class, RETREAT_SCAN_RANGE))
		{
			if (!isPvpEnemy(enemy) || enemy.isAlikeDead() || !isValidTarget(npc, enemy))
			{
				continue;
			}
			enemies.add(enemy);
			final double w = 1.0 / Math.max(50, npc.calculateDistance2D(enemy));
			centerX += enemy.getX() * w;
			centerY += enemy.getY() * w;
			weight += w;
		}
		if ((threat != null) && !enemies.contains(threat))
		{
			enemies.add(threat);
			final double w = 1.0 / Math.max(50, npc.calculateDistance2D(threat));
			centerX += threat.getX() * w;
			centerY += threat.getY() * w;
			weight += w;
		}
		final double away = weight > 0 ? Math.atan2(npc.getY() - (centerY / weight), npc.getX() - (centerX / weight)) : Rnd.nextDouble() * 2 * Math.PI;
		final double nearNow = keepNear != null ? Math.hypot(npc.getX() - keepNear.getX(), npc.getY() - keepNear.getY()) : 0;

		Location best = null;
		double bestScore = -Double.MAX_VALUE;
		for (double turn : RETREAT_TURNS)
		{
			final double angle = away + turn;
			final int x = npc.getX() + (int) (Math.cos(angle) * step);
			final int y = npc.getY() + (int) (Math.sin(angle) * step);
			final Location point = GeoEngine.getInstance().getValidLocation(npc.getX(), npc.getY(), npc.getZ(), x, y, npc.getZ() + 30, npc.getInstanceId());
			if ((npc.calculateDistance2D(point) < 50) || !GeoEngine.getInstance().canMoveToTarget(npc.getX(), npc.getY(), npc.getZ(), point.getX(), point.getY(), point.getZ(), npc.getInstanceId()))
			{
				continue;
			}
			if ((keepWithin != null) && (Math.hypot(point.getX() - keepWithin.getX(), point.getY() - keepWithin.getY()) > range))
			{
				continue;
			}
			if (keepNear != null)
			{
				final double near = Math.hypot(point.getX() - keepNear.getX(), point.getY() - keepNear.getY());
				if ((near > RETREAT_KEEP_NEAR) && (near > nearNow))
				{
					continue;
				}
			}

			double closest = Double.MAX_VALUE;
			double sum = 0;
			for (Creature enemy : enemies)
			{
				final double distance = Math.hypot(point.getX() - enemy.getX(), point.getY() - enemy.getY());
				closest = Math.min(closest, distance);
				sum += distance;
			}
			double score = enemies.isEmpty() ? -Math.abs(turn) : closest + ((0.25 * sum) / enemies.size());
			if ((keepWithin != null) && !GeoEngine.getInstance().canSeeTarget(point.getX(), point.getY(), point.getZ(), keepWithin.getX(), keepWithin.getY(), keepWithin.getZ(), npc.getInstanceId()))
			{
				score -= RETREAT_NO_LOS_MALUS;
			}
			if (score > bestScore)
			{
				bestScore = score;
				best = point;
			}
		}
		return best;
	}

	/** #29: below this chance of landing an arrow, an archer uses its skills. */
	private static final double ARCHER_ARROW_HIT_CHANCE = 0.75;

	/**
	 * #29: the chance its normal attack lands on {@code target}, worked out like {@link org.l2jmobius.gameserver.model.stats.Formulas#calcHitMiss} (accuracy against evasion, the bonus of where they stand, between 20% and 98%) without the roll.
	 */
	private static double getHitChance(Creature attacker, Creature target)
	{
		if (PassiveMechanics.neverMisses(attacker))
		{
			return 1;
		}
		double chance = (80 + (2 * (attacker.getAccuracy() - target.getEvasionRate(attacker)))) * 10;
		chance *= HitConditionBonusData.getInstance().getConditionBonus(attacker, target);
		return Math.max(200, Math.min(980, chance)) / 1000.0;
	}

	/**
	 * @param npc the fake player
	 * @param range how close
	 * @return {@code true} if a living enemy of its fight is this close
	 */
	private static boolean isEnemyAround(Attackable npc, int range)
	{
		for (Creature enemy : World.getInstance().getVisibleObjectsInRange(npc, Creature.class, range))
		{
			if (isPvpEnemy(enemy) && !enemy.isAlikeDead() && isValidTarget(npc, enemy))
			{
				return true;
			}
		}
		return false;
	}

	/**
	 * #21: it just shot; it may step aside once before the next shot.
	 */
	private void markShot()
	{
		_lastShotTime = System.currentTimeMillis();
		_shotStepped = false;
	}

	/**
	 * @param npc the fake player
	 * @param range how close
	 * @return {@code true} if an enemy this close targets it and can act (not stunned, rooted, asleep...)
	 */
	private static boolean isEnemyOnMe(Attackable npc, int range)
	{
		for (Creature enemy : World.getInstance().getVisibleObjectsInRange(npc, Creature.class, range))
		{
			if ((enemy.getTarget() == npc) && isPvpEnemy(enemy) && !enemy.isAlikeDead() && !isDisabled(enemy) && isValidTarget(npc, enemy))
			{
				return true;
			}
		}
		return false;
	}

	/**
	 * Spirit Walk (Doomcryer): swaps places with its totem farthest from {@code enemy}, which is left by the totem, in its pulses.
	 * @return {@code true} if it cast it
	 */
	private boolean trySpiritWalk(Attackable npc, FakePlayerPvpProfile profile, Creature enemy)
	{
		final Skill walk = profile.getListedSkill(Totems.SPIRIT_WALK_SKILL_ID);
		if ((walk == null) || npc.isMovementDisabled() || !canCast(npc, walk, npc) || (npc.calculateDistance2D(enemy) > 400))
		{
			return false;
		}
		
		final Totem totem = FakePlayerPvpOraclesWarchiefs.getSpiritWalkTotem(npc, enemy);
		if (totem == null)
		{
			return false;
		}
		
		// It swaps with what it targets when the spell lands; the next tick targets the enemy again.
		clientStopMoving(null);
		npc.setTarget(totem);
		npc.doCast(walk);
		return true;
	}
	
	/**
	 * Steps back from {@code target} around its Totem of Flames (or Frost-Teeth), to the far side of it, so the target chases it through the pulses. Only away from the target, never past it.
	 * @return {@code true} if it started moving
	 */
	private boolean kiteAroundTotem(Attackable npc, Creature target, long now)
	{
		final Totem anchor = FakePlayerPvpOraclesWarchiefs.getKiteAnchor(npc, target);
		if (anchor == null)
		{
			return false;
		}
		
		final double dx = anchor.getX() - target.getX();
		final double dy = anchor.getY() - target.getY();
		final double length = Math.hypot(dx, dy);
		if (length < 1)
		{
			return false;
		}
		
		final int x = anchor.getX() + (int) ((dx / length) * TOTEM_KITE_OFFSET);
		final int y = anchor.getY() + (int) ((dy / length) * TOTEM_KITE_OFFSET);
		if ((((x - npc.getX()) * (double) (target.getX() - npc.getX())) + ((y - npc.getY()) * (double) (target.getY() - npc.getY()))) >= 0)
		{
			return false; // The target stands between it and the totem.
		}
		
		final Location destination = GeoEngine.getInstance().getValidLocation(npc.getX(), npc.getY(), npc.getZ(), x, y, anchor.getZ() + 30, npc.getInstanceId());
		if ((npc.calculateDistance2D(destination) < 50) || !GeoEngine.getInstance().canMoveToTarget(npc.getX(), npc.getY(), npc.getZ(), destination.getX(), destination.getY(), destination.getZ(), npc.getInstanceId()))
		{
			return false;
		}
		
		npc.setRunning();
		moveTo(destination.getX(), destination.getY(), destination.getZ());
		_kiteEndTime = now + Math.min(3000, (long) ((npc.calculateDistance2D(destination) * 1000.0) / Math.max(1, npc.getMoveSpeed())));
		_nextKiteTime = now + 3000 + Rnd.get(2000);
		return true;
	}
	
	// ---------------------------------------------------------------------------------------------
	// PvP spot (see PvpSpotManager)
	// ---------------------------------------------------------------------------------------------
	
	/** Running away in a PvP spot, it keeps within this distance of it. */
	private static final int SPOT_FLEE_OUTSIDE = 200;
	/** How often it looks around for a better opponent in the middle of a fight. */
	private static final long SPOT_RETARGET_INTERVAL = 1500;
	/** How much better (lower) another opponent's score must be to switch to it, so it doesn't flip between two. */
	private static final double SPOT_SWITCH_MARGIN = 0.6;
	/** An opponent this low and this close is finished off, for the kill. */
	private static final double SPOT_FINISH_HP = 0.2;
	private static final int SPOT_FINISH_RANGE = 500;
	/** Players and fake players this close that go for it (or anyone this close) keep it from resting. */
	private static final int SPOT_THREAT_RANGE = 900;
	private static final int SPOT_DANGER_RANGE = 400;
	/** It walks over to the fights up to this far. */
	private static final int SPOT_ACTION_RANGE = 3000;
	/** How long it sticks to its choice to go for the leader of its spot, or to keep away from it. */
	private static final long LEADER_DECISION_TIME = 60000;
	
	private long _nextSpotRetarget = 0;
	// Whether it goes for the leader of its spot: object id -> until when (negative: it keeps away from it).
	private final Map<Integer, Long> _leaderDecisions = new ConcurrentHashMap<>();
	
	/**
	 * A fake player defending a village under siege (see {@link VillageSiegeManager}): it fights the siege's monsters near its entrance, rests when nothing is close, holds its post by the gate, and reads its scroll when its time is up.
	 * @param npc the fake player
	 * @param profile its profile
	 * @param now the time
	 */
	private void thinkSiege(Attackable npc, FakePlayerPvpProfile profile, long now)
	{
		// Its time is up, or the siege is over: it reads its scroll and leaves.
		if (thinkLeave(npc, profile, now))
		{
			return;
		}
		
		// Resting: only with no monster of the siege close.
		final Creature enemy = VillageSiegeManager.getInstance().findDefenderTarget(npc);
		final double hpRatio = npc.getCurrentHp() / npc.getMaxHp();
		_resting = (hpRatio < (_resting ? 0.9 : 0.5)) && ((enemy == null) || (npc.calculateDistance2D(enemy) > 600)) && !isFightNear(npc);
		if (_resting)
		{
			FakePlayerPvpManager.getInstance().tryPotion(npc);
			if (!npc.isMoving() && !npc.isMovementDisabled())
			{
				sitDown(npc);
			}
			return;
		}
		
		if (standUp(npc))
		{
			return;
		}
		
		// A necromancer keeps its servitor out, duelists and tyrants their energy full.
		if ((profile.needsServitor() && mayCastSummon(npc, profile, System.currentTimeMillis()) && castOnSelf(npc, null, profile.getSkills(SkillCategory.SUMMON), true, false)) || ((profile.getCharges() < profile.getMaxCharges()) && castOnSelf(npc, null, profile.getSkills(SkillCategory.CHARGE), true, false)))
		{
			return;
		}
		
		FakePlayerPvpManager.getInstance().tryPotion(npc);
		
		if (enemy != null)
		{
			npc.setRunning();
			FakePlayerPvpManager.getInstance().assistFight(npc, enemy);
			return;
		}
		
		// Nothing to fight: back to its post by the gate.
		if (npc.isMoving() || npc.isMovementDisabled())
		{
			return;
		}
		
		final Location post = VillageSiegeManager.getInstance().getDefenderPost(npc);
		if ((post != null) && (npc.calculateDistance2D(post) > 150))
		{
			npc.setRunning();
			moveTo(post.getX(), post.getY(), post.getZ());
		}
	}
	
	/**
	 * A fake player of a PvP spot between two fights, like a player in a PvP zone: it goes for its killer first when it comes back from town, otherwise for the best opponent it sees ({@link #findSpotEnemy}); with nobody around it walks over to the fights, and
	 * back into the spot when a chase took it out. It only sits down to rest with nobody around to go for it, and leaves (reading a scroll) once its time is up.
	 * @param npc the fake player
	 * @param profile its profile
	 * @param now the current time
	 */
	private void thinkSpot(Attackable npc, FakePlayerPvpProfile profile, long now)
	{
		final PvpSpotZone zone = PvpSpotManager.getZone(profile.getPvpSpotId());
		
		// The spot is gone (the zones were reloaded without it, the spots switched off): off to town.
		if (((zone == null) || !PvpSpotsConfig.ENABLED) && (profile.getLeaveTime() == 0))
		{
			profile.setLeaveTime(now);
		}
		
		// Its time is up: it reads its scroll and leaves.
		if (thinkLeave(npc, profile, now) || (zone == null))
		{
			return;
		}
		
		// Resting: only with nobody around to come for it, like a player in a PvP zone (a pursuer it just got away from is still coming).
		final double hpRatio = npc.getCurrentHp() / npc.getMaxHp();
		_resting = (hpRatio < (_resting ? 0.9 : 0.5)) && !isSpotThreatNear(npc, zone) && !isFightNear(npc);
		if (_resting)
		{
			FakePlayerPvpManager.getInstance().tryPotion(npc);
			if (!npc.isMoving() && !npc.isMovementDisabled())
			{
				sitDown(npc);
			}
			return;
		}
		
		if (standUp(npc))
		{
			return;
		}
		
		// A necromancer keeps its servitor out, duelists and tyrants their energy full.
		if ((profile.needsServitor() && mayCastSummon(npc, profile, System.currentTimeMillis()) && castOnSelf(npc, null, profile.getSkills(SkillCategory.SUMMON), true, false)) || ((profile.getCharges() < profile.getMaxCharges()) && castOnSelf(npc, null, profile.getSkills(SkillCategory.CHARGE), true, false)))
		{
			return;
		}
		
		FakePlayerPvpManager.getInstance().tryPotion(npc);
		
		// Back from town: its killer first, wherever it is in the spot.
		final Creature grudge = findSpotGrudge(npc, profile, zone, now);
		if (grudge != null)
		{
			profile.clearGrudge();
			npc.setRunning();
			FakePlayerPvpManager.getInstance().spotEngage(npc, grudge, PvpSpotManager.TAUNTS_RETURN);
			return;
		}
		
		// The next one.
		if (engageSpotEnemy(npc, profile, now))
		{
			return;
		}
		
		// Nobody in reach: back into the spot, or over to where the fighting is.
		if (npc.isMoving() || npc.isMovementDisabled())
		{
			return;
		}
		
		npc.setRunning();
		if (!zone.isInsideZone(npc))
		{
			moveIntoSpot(npc, zone);
			return;
		}
		
		// Walking (pathfinding) over to the closest fight it can't see from here.
		final Creature action = findSpotAction(npc, zone);
		if (action != null)
		{
			moveTo(action.getX(), action.getY(), action.getZ());
			return;
		}
		
		if (Rnd.get(WANDER_CHANCE) == 0)
		{
			moveIntoSpot(npc, zone);
		}
	}
	
	/**
	 * Picks the best opponent it sees in its spot and goes for it.
	 * @param npc the fake player
	 * @param profile its profile
	 * @param now the current time
	 * @return {@code true} if it went for someone
	 */
	private boolean engageSpotEnemy(Attackable npc, FakePlayerPvpProfile profile, long now)
	{
		final PvpSpotZone zone = PvpSpotManager.getZone(profile.getPvpSpotId());
		final Creature enemy = zone != null ? findSpotEnemy(npc, profile, zone, now) : null;
		if (enemy == null)
		{
			return false;
		}
		
		npc.setRunning();
		FakePlayerPvpManager.getInstance().spotEngage(npc, enemy, PvpSpotManager.TAUNTS_ENGAGE);
		return true;
	}
	
	/**
	 * @param npc the fake player
	 * @param profile its profile
	 * @param zone its spot
	 * @param now the current time
	 * @return the best opponent it sees within {@link PvpSpotsConfig#TARGET_RANGE} (see {@link #spotScore}), {@code null} if none
	 */
	private Creature findSpotEnemy(Attackable npc, FakePlayerPvpProfile profile, PvpSpotZone zone, long now)
	{
		final int leaderId = PvpSpotManager.getInstance().getLeaderId(zone.getId());
		final int grudgeId = profile.getGrudge(now);
		Creature best = null;
		double bestScore = Double.MAX_VALUE;
		for (Creature creature : World.getInstance().getVisibleObjectsInRange(npc, Creature.class, PvpSpotsConfig.TARGET_RANGE))
		{
			if (!isSpotEnemy(npc, creature, zone, false))
			{
				continue;
			}
			
			// The line of sight (the costly check) only for one that would be the best so far.
			final double score = spotScore(npc, profile, creature, leaderId, grudgeId, now);
			if ((score < bestScore) && GeoEngine.getInstance().canSeeTarget(npc, creature))
			{
				best = creature;
				bestScore = score;
			}
		}
		
		return best;
	}
	
	/**
	 * Like a player in a PvP zone, it looks around in the middle of a fight for a better opponent than the one it fights: someone almost dead close by (for the kill), someone going for it or its clan mates, its killer, the leader... and switches when one is
	 * clearly better.
	 * @param npc the fake player
	 * @param profile its profile
	 * @param target the one it fights now
	 * @param now the current time
	 * @return the one to fight
	 */
	private Creature chooseSpotFocus(Attackable npc, FakePlayerPvpProfile profile, Creature target, long now)
	{
		if ((now < _nextSpotRetarget) || _fleeing || _regrouping || npc.isCastingNow())
		{
			return target;
		}
		_nextSpotRetarget = now + SPOT_RETARGET_INTERVAL;
		
		final PvpSpotZone zone = PvpSpotManager.getZone(profile.getPvpSpotId());
		if (zone == null)
		{
			return target;
		}
		
		final Creature better = findSpotEnemy(npc, profile, zone, now);
		if ((better == null) || (better == target))
		{
			return target;
		}
		
		final int leaderId = PvpSpotManager.getInstance().getLeaderId(zone.getId());
		final int grudgeId = profile.getGrudge(now);
		// One it chases a little way out of the spot (PvpSpotChaseOutside) is still its opponent, not someone to drop for anyone left inside.
		final double current = isSpotEnemy(npc, target, zone, false, PvpSpotsConfig.CHASE_OUTSIDE) ? spotScore(npc, profile, target, leaderId, grudgeId, now) : Double.MAX_VALUE;
		if (spotScore(npc, profile, better, leaderId, grudgeId, now) > (current - SPOT_SWITCH_MARGIN))
		{
			return target;
		}
		
		if (better.getObjectId() == grudgeId)
		{
			profile.clearGrudge();
		}
		
		if (_combo != null)
		{
			endCombo(now);
		}
		
		FakePlayerPvpManager.getInstance().spotEngage(npc, better, null);
		_focus = better;
		_focusUntil = now + FOCUS_TIME;
		return better;
	}
	
	/**
	 * How good an opponent is, lower is better, the way a player picks one in a PvP zone: close, low, going for it or its clan mates, busy with someone else (a free hit), its killer, at war with its clan, about its level. Aggressive fake players go for the
	 * leader of the spot (PvpSpotLeaderFocusChance), cautious ones keep away from it, and few pile on someone that several of them already fight.
	 * @param npc the fake player
	 * @param profile its profile
	 * @param enemy the opponent
	 * @param leaderId the leader of the spot, 0 for none
	 * @param grudgeId the one that killed it last, 0 for none
	 * @param now the current time
	 * @return its score
	 */
	private double spotScore(Attackable npc, FakePlayerPvpProfile profile, Creature enemy, int leaderId, int grudgeId, long now)
	{
		final double distance = npc.calculateDistance2D(enemy);
		final double hpRatio = getHpRatio(enemy);
		double score = (distance / PvpSpotsConfig.TARGET_RANGE) + (hpRatio * 0.8);
		
		// For the kill.
		if ((hpRatio < SPOT_FINISH_HP) && (distance < SPOT_FINISH_RANGE))
		{
			score -= 0.5;
		}
		
		// Someone going for it, or for its clan mates.
		final WorldObject itsTarget = enemy.getTarget();
		if (itsTarget == npc)
		{
			score -= 1.2;
		}
		else if ((itsTarget instanceof Creature) && (itsTarget != enemy) && FakeClanManager.getInstance().isFriend(npc, (Creature) itsTarget))
		{
			score -= 0.8;
		}
		else if ((itsTarget instanceof Creature) && isPvpEnemy((Creature) itsTarget))
		{
			score -= 0.25; // Busy with someone else.
		}
		
		if (enemy.getObjectId() == grudgeId)
		{
			score -= 2;
		}
		
		if (FakeClanManager.getInstance().isWarEnemy(npc, enemy))
		{
			score -= 0.8;
		}
		
		// Much higher: better left alone (unless it comes).
		final int levelDiff = enemy.getLevel() - npc.getLevel();
		if (levelDiff >= 6)
		{
			score += 0.15 * (levelDiff - 5);
		}
		
		// The leader: the aggressive ones go for it, the cautious ones keep away.
		if ((leaderId != 0) && (enemy.getObjectId() == leaderId))
		{
			if (wantsLeader(profile, leaderId, now))
			{
				score -= 1;
			}
			else if (profile.getPersonality().getAggression() < -0.3)
			{
				score += 1.5;
			}
		}
		else
		{
			// Few pile on someone several of them already fight.
			final int attackers = countSpotAttackers(npc, enemy);
			if (attackers >= 3)
			{
				score += 0.3 * (attackers - 2);
			}
		}
		
		return score;
	}
	
	/**
	 * @param creature a player or fake player
	 * @return how much of its health it has left: CP and HP together for a player (a fake player's CP is in its HP)
	 */
	private static double getHpRatio(Creature creature)
	{
		if (creature.isPlayer())
		{
			final double max = creature.getMaxHp() + creature.getMaxCp();
			return max > 0 ? (creature.getCurrentHp() + creature.getCurrentCp()) / max : 1;
		}
		return creature.getCurrentHp() / Math.max(1, creature.getMaxHp());
	}
	
	/**
	 * @param profile its profile
	 * @param leaderId the leader of its spot
	 * @param now the current time
	 * @return {@code true} if it goes for the leader: an aggressive one does with {@link PvpSpotsConfig#LEADER_FOCUS_CHANCE}, decided once for a while
	 */
	private boolean wantsLeader(FakePlayerPvpProfile profile, int leaderId, long now)
	{
		_leaderDecisions.values().removeIf(until -> Math.abs(until) < now);
		if (profile.getPersonality().getAggression() <= 0)
		{
			return false;
		}
		
		return _leaderDecisions.computeIfAbsent(leaderId, id -> Rnd.get(100) < PvpSpotsConfig.LEADER_FOCUS_CHANCE ? now + LEADER_DECISION_TIME : -(now + LEADER_DECISION_TIME)) > 0;
	}
	
	/**
	 * @param npc the fake player
	 * @param enemy an opponent
	 * @return how many other fake players fight {@code enemy} close by
	 */
	private static int countSpotAttackers(Attackable npc, Creature enemy)
	{
		int count = 0;
		for (Npc other : World.getInstance().getVisibleObjectsInRange(enemy, Npc.class, SPOT_THREAT_RANGE))
		{
			if ((other != npc) && other.isPvpFakePlayer() && !other.isDead() && (other.getTarget() == enemy))
			{
				count++;
			}
		}
		return count;
	}
	
	/**
	 * @param npc the fake player
	 * @param creature a creature it sees
	 * @param zone its spot
	 * @param inSight {@code true} to also want it in sight
	 * @return {@code true} if {@code creature} is someone to fight in the spot: a player or a fake player in it, not of its clan, alliance or party, not a GM, nor in the Olympiad, a duel or town
	 */
	private static boolean isSpotEnemy(Attackable npc, Creature creature, PvpSpotZone zone, boolean inSight)
	{
		return isSpotEnemy(npc, creature, zone, inSight, 0);
	}
	
	/**
	 * @param npc the fake player
	 * @param creature a creature it sees
	 * @param zone its spot
	 * @param inSight {@code true} to also want it in sight
	 * @param outside how far out of the spot {@code creature} may be
	 * @return {@code true} if {@code creature} is someone to fight in the spot, or at most {@code outside} out of it (see {@link #isSpotEnemy(Attackable, Creature, PvpSpotZone, boolean)})
	 */
	private static boolean isSpotEnemy(Attackable npc, Creature creature, PvpSpotZone zone, boolean inSight, int outside)
	{
		if ((creature == null) || (creature == npc) || creature.isAlikeDead() || !creature.isSpawned() || creature.isInvisible() || (creature.getInstanceId() != npc.getInstanceId()) || creature.isInsideZone(ZoneId.PEACE))
		{
			return false;
		}
		
		if (creature.isPlayer())
		{
			final Player player = creature.asPlayer();
			if ((player.isGM() && !player.getAccessLevel().canTakeAggro()) || player.isInOlympiadMode() || player.isInDuel())
			{
				return false;
			}
		}
		else if (!creature.isPvpFakePlayer() || creature.asNpc().isTrialDuelist())
		{
			return false;
		}
		
		if ((!zone.isInsideZone(creature) && ((outside <= 0) || (zone.getDistanceToZone(creature) > outside))) || FakeClanManager.getInstance().isFriend(npc, creature) || FakePartyManager.getInstance().isSameGroup(npc, creature))
		{
			return false;
		}
		
		return !inSight || GeoEngine.getInstance().canSeeTarget(npc, creature);
	}
	
	/**
	 * @param npc the fake player
	 * @param creature a creature in the area of one of its skills
	 * @return {@code true} if an area skill of it may hit {@code creature} in a PvP spot (see {@link FakePlayerPvpManager#isFairAreaTarget})
	 */
	private static boolean isFairAreaEnemy(Attackable npc, Creature creature)
	{
		if (creature.isPlayer())
		{
			return FakePlayerPvpManager.isFairAreaTarget(npc, creature.asPlayer());
		}
		return creature.isPvpFakePlayer() && FakePlayerPvpManager.isFairAreaTarget(npc, creature.asNpc());
	}
	
	/**
	 * @param npc the fake player
	 * @param profile its profile
	 * @param zone its spot
	 * @param now the current time
	 * @return its killer, back from town for them, when they are in the spot and in sight; {@code null} otherwise
	 */
	private static Creature findSpotGrudge(Attackable npc, FakePlayerPvpProfile profile, PvpSpotZone zone, long now)
	{
		final int grudgeId = profile.getGrudge(now);
		if (grudgeId == 0)
		{
			return null;
		}
		
		final WorldObject object = World.getInstance().findObject(grudgeId);
		if (!(object instanceof Creature) || !npc.isInsideRadius2D(object, SPOT_ACTION_RANGE))
		{
			return null;
		}
		
		final Creature killer = (Creature) object;
		return isSpotEnemy(npc, killer, zone, true) ? killer : null;
	}
	
	/**
	 * @param npc the fake player
	 * @param zone its spot
	 * @return the closest one to fight in the spot, out of sight or reach, to walk over to; {@code null} if none
	 */
	private static Creature findSpotAction(Attackable npc, PvpSpotZone zone)
	{
		Creature closest = null;
		double closestDistance = Double.MAX_VALUE;
		for (Creature creature : World.getInstance().getVisibleObjectsInRange(npc, Creature.class, SPOT_ACTION_RANGE))
		{
			if (!isSpotEnemy(npc, creature, zone, false))
			{
				continue;
			}
			
			final double distance = npc.calculateDistance2D(creature);
			if (distance < closestDistance)
			{
				closest = creature;
				closestDistance = distance;
			}
		}
		
		return closest;
	}
	
	/**
	 * @param npc the fake player
	 * @param zone its spot
	 * @return {@code true} if someone could come for it: an opponent close by going for it, or any opponent very close
	 */
	private static boolean isSpotThreatNear(Attackable npc, PvpSpotZone zone)
	{
		for (Creature creature : World.getInstance().getVisibleObjectsInRange(npc, Creature.class, SPOT_THREAT_RANGE))
		{
			if (isSpotEnemy(npc, creature, zone, false) && ((creature.getTarget() == npc) || npc.isInsideRadius2D(creature, SPOT_DANGER_RANGE)))
			{
				return true;
			}
		}
		return false;
	}
	
	/**
	 * @param profile the profile of a fake player of a PvP spot
	 * @param creature someone it chases
	 * @return {@code true} if {@code creature} is farther out of the spot than it chases ({@link PvpSpotsConfig#CHASE_OUTSIDE})
	 */
	private static boolean isBeyondSpot(FakePlayerPvpProfile profile, Creature creature)
	{
		final PvpSpotZone zone = PvpSpotManager.getZone(profile.getPvpSpotId());
		return (zone != null) && !zone.isInsideZone(creature) && (zone.getDistanceToZone(creature) > PvpSpotsConfig.CHASE_OUTSIDE);
	}
	
	/**
	 * Runs (pathfinding) to a point of its spot.
	 * @param npc the fake player
	 * @param zone its spot
	 */
	private void moveIntoSpot(Attackable npc, PvpSpotZone zone)
	{
		final Location point = zone.getZone().getRandomPoint();
		if (zone.isInsideZone(point.getX(), point.getY(), point.getZ()))
		{
			moveTo(point.getX(), point.getY(), point.getZ());
		}
	}
	
	// ---------------------------------------------------------------------------------------------
	// Party (see FakePartyManager)
	// ---------------------------------------------------------------------------------------------
	
	/** Party members this far away are out of its care. */
	private static final int SUPPORT_RANGE = 1500;
	/** It doesn't cast the same support skill on the same member again within this time (when it didn't take). */
	private static final long SUPPORT_RECAST_DELAY = 10000;
	/** A buff it keeps up on the party is cast again when it has less than this many seconds left. */
	private static final int BUFF_REFRESH_TIME = 60;
	/** With {@link FakePartyConfig#HUNT_WHEN_IDLE}, it pulls the monsters this close to its idle party leader. */
	private static final int IDLE_HUNT_RANGE = 700;
	
	/** Buffs only fighters want (P. Atk., attack speed, critical, accuracy, vampiric...). */
	private static final Set<Integer> FIGHTER_BUFFS = Set.of(1068, 1086, 1077, 1242, 1240, 1268, 1388, 1499, 1502, 1007, 1251, 1253, 1308, 1309, 1310, 1390, 1517, 1518, 1519, 1363, 1003, 1249, 1563, 1536, 1537, 1364, 1414, 271, 275, 274, 310, 765, 272, 269, 364, 1356, 1357);
	/** Buffs only mages want (M. Atk., casting speed, magic critical, MP...). */
	private static final Set<Integer> MAGE_BUFFS = Set.of(1085, 1059, 1303, 1397, 1078, 1048, 1500, 1413, 1002, 1004, 1365, 273, 276, 365, 363, 1355);
	
	/** An enemy this close to a squishy party member and targeting it is diving it. */
	private static final int GUARD_THREAT_RANGE = 450;
	/** A tank doesn't go after an enemy farther than this from the squishies it guards, a support than this. */
	private static final int TANK_GUARD_LEASH = 700;
	private static final int SUPPORT_GUARD_LEASH = 450;
	/** A support still casts on an enemy this much beyond its leash (its spells reach). */
	private static final int SUPPORT_GUARD_REACH = 500;
	/** A healer only hits an enemy this close (its Wind Strike, Vampiric Touch...), it doesn't walk up to one. */
	private static final int HEALER_OFFENSE_REACH = 600;
	/** A diver a tank is on still counts as one this far from the squishy it targets. */
	private static final double DIVER_KEEP_FACTOR = 1.5;
	/** How much worse (in score) than the best diver the one a tank is on may be, and it stays on it. */
	private static final double DIVER_KEEP_MARGIN = 150;
	/** How much more a diver on a healer counts, and how much less an archer or mage that dives (it only shoots from there). */
	private static final double DIVER_HEALER_BONUS = 450;
	private static final double DIVER_RANGED_MALUS = 300;
	/** A tank with nothing close to fight holds this far in front of its squishies, toward the enemy. */
	private static final int GUARD_FRONT = 200;
	/** It walks back to its guard point once it is this far from it. */
	private static final int GUARD_HOLD_TOLERANCE = 150;
	/** Party members farther than this are not guarded (they ran off). */
	private static final int GUARD_SCAN_RANGE = 1500;

	/**
	 * A tank or a support of a party fights like one that looks after its squishies (mages, archers, healers), not like a lone duelist:
	 * <ul>
	 * <li>An enemy dives a squishy (close to it and targeting it): a tank goes for it and stuns or roots it, a support roots or sleeps it from where it stands.</li>
	 * <li>A tank doesn't chase an enemy farther than {@link #TANK_GUARD_LEASH} from its squishies into the enemy backline: it takes on the closest enemy that fights the party within that range, or holds in front of its squishies. A support stays within
	 * {@link #SUPPORT_GUARD_LEASH} of the party.</li>
	 * </ul>
	 * @param npc the fake player
	 * @param profile its profile
	 * @param party its party
	 * @param target the player it chose to fight
	 * @param now the current time
	 * @return the player to fight, {@code null} if it acted (peeled, or holds its ground) this think
	 */
	private Creature guardBackline(Attackable npc, FakePlayerPvpProfile profile, FakePlayerParty party, Creature target, long now)
	{
		final boolean tank = !profile.isTransformed() && (profile.getRole() == Role.TANK);
		final boolean support = profile.getBuild().isSupport();
		if ((!tank && !support) || _fleeing || _regrouping)
		{
			return target;
		}

		// Who it guards: the squishies of its party (a support stays with the whole party), close enough to matter.
		final List<Creature> guarded = new ArrayList<>();
		long x = 0;
		long y = 0;
		long z = 0;
		for (Creature member : party.getMembers())
		{
			if ((member == npc) || member.isDead() || !member.isSpawned() || (member.getInstanceId() != npc.getInstanceId()) || (npc.calculateDistance2D(member) > GUARD_SCAN_RANGE) || (!support && !isSquishy(member)))
			{
				continue;
			}
			guarded.add(member);
			x += member.getX();
			y += member.getY();
			z += member.getZ();
		}
		if (guarded.isEmpty())
		{
			return target;
		}
		final Location anchor = new Location((int) (x / guarded.size()), (int) (y / guarded.size()), (int) (z / guarded.size()));
		

		// Someone dives a squishy: the one closest to its victim, on a healer first, a melee one before an archer or mage that only shoots from there.
		Creature diver = null;
		double best = Double.MAX_VALUE;
		for (Creature member : guarded)
		{
			if (!isSquishy(member))
			{
				continue;
			}
			for (Creature enemy : World.getInstance().getVisibleObjectsInRange(member, Creature.class, GUARD_THREAT_RANGE))
			{
				if ((enemy.getTarget() != member) || !isPvpEnemy(enemy) || enemy.isAlikeDead() || !isValidTarget(npc, enemy))
				{
					continue;
				}
				final double score = getDiverScore(enemy, member);
				if (score < best)
				{
					diver = enemy;
					best = score;
				}
			}
		}

		// A tank on a diver stays on it while it still dives one of its squishies and no clearly worse one is at it (no turning to every enemy that comes close).
		if (tank && (target != diver) && isDiving(target, guarded) && (getDiverScore(target, (Creature) target.getTarget()) <= (best + DIVER_KEEP_MARGIN)))
		{
			return target;
		}

		if (diver != null)
		{
			final double distance = npc.calculateDistance2D(diver);
			final int collision = npc.getTemplate().getCollisionRadius() + diver.getTemplate().getCollisionRadius();
			if (!isDisabled(diver) && useSkill(npc, diver, pickPeel(npc, diver, profile, getKnownDefenses(diver), distance - collision), distance, collision, false))
			{
				return null;
			}

			// A tank goes for it; a support that has nothing to stop it with from where it stands goes on with its own fight.
			if (tank)
			{
				if (diver != target)
				{
					trace(Reason.GUARD_PEEL, diver);
					npc.addDamageHate(diver, 0, FakePlayerPvpManager.PVP_HATE);
					_focus = diver;
					_focusUntil = now + FOCUS_TIME;
				}
				return diver;
			}
		}

		// A healer doesn't walk up to anyone to hit them: only what its spells reach from where it stands, by its party.
		final int leash = tank ? TANK_GUARD_LEASH : SUPPORT_GUARD_LEASH + SUPPORT_GUARD_REACH;
		if (!tank && isPureHealer(profile))
		{
			if ((npc.calculateDistance2D(target) - npc.getTemplate().getCollisionRadius() - target.getTemplate().getCollisionRadius()) <= HEALER_OFFENSE_REACH)
			{
				return target;
			}
		}
		// Not into the enemy backline.
		else
		{
			if (target.calculateDistance2D(anchor) <= leash)
			{
				return target;
			}
		}

		// A tank takes on the closest enemy within reach of its squishies that fights the party.
		if (tank)
		{
			Creature closest = null;
			double closestDistance = Double.MAX_VALUE;
			for (Creature enemy : World.getInstance().getVisibleObjectsInRange(npc, Creature.class, GUARD_SCAN_RANGE))
			{
				if (!isPvpEnemy(enemy) || enemy.isAlikeDead() || !isValidTarget(npc, enemy))
				{
					continue;
				}
				final double distance = enemy.calculateDistance2D(anchor);
				final WorldObject itsTarget = enemy.getTarget();
				if ((distance < closestDistance) && (distance <= leash) && (hates(npc, enemy, FakePlayerPvpManager.PVP_HATE) || ((itsTarget instanceof Creature) && party.isMember((Creature) itsTarget))))
				{
					closest = enemy;
					closestDistance = distance;
				}
			}
			if (closest != null)
			{
				if (closest != target)
				{
					trace(Reason.GUARD_RETARGET, closest);
					npc.addDamageHate(closest, 0, FakePlayerPvpManager.PVP_HATE);
					_focus = closest;
					_focusUntil = now + FOCUS_TIME;
				}
				return closest;
			}
		}

		// #23: a tank that leads its party, while nobody is on the party and its target is still far, holds further ahead: the party follows it, so the team moves up together, as far as it can follow (holding by its squishies froze the whole team; going straight in left its healer chasing it).
		final boolean advance = tank && (party.getActiveLeader() == npc) && (npc.calculateDistance2D(target) > TANK_LEAD_STOP) && !isPartyFought(npc, party);

		// Nothing close: it holds its ground, a tank in front of its squishies, a support with its party.
		_progressTime = now; // Waiting on purpose, not stuck.
		trace(Reason.GUARD_HOLD, target, null, advance ? "ADVANCE" : null);
		int holdX = anchor.getX();
		int holdY = anchor.getY();
		if (tank)
		{
			final double dx = target.getX() - anchor.getX();
			final double dy = target.getY() - anchor.getY();
			final double length = Math.max(1, Math.hypot(dx, dy));
			final int front = advance ? GUARD_FRONT + TANK_LEAD_STEP : GUARD_FRONT;
			holdX += (int) ((dx / length) * front);
			holdY += (int) ((dy / length) * front);
		}

		final int tolerance = tank ? GUARD_HOLD_TOLERANCE : SUPPORT_GUARD_LEASH;
		if ((Math.hypot(npc.getX() - holdX, npc.getY() - holdY) > tolerance) && !npc.isMovementDisabled())
		{
			final Location point = GeoEngine.getInstance().getValidLocation(npc.getX(), npc.getY(), npc.getZ(), holdX, holdY, anchor.getZ(), npc.getInstanceId());
			npc.setRunning();
			moveTo(point.getX(), point.getY(), point.getZ());
		}
		else if (npc.isMoving())
		{
			clientStopMoving(null);
			npc.setHeading(LocationUtil.calculateHeadingFrom(npc, target));
		}
		return null;
	}

	/** A melee damage dealer doesn't go after an enemy farther than this from its party, unless its tank is this close to that enemy. */
	private static final int MELEE_LEASH = 700;
	private static final int MELEE_TANK_LEAD = 400;
	/** It holds within this distance of its party meanwhile. */
	private static final int MELEE_HOLD_DISTANCE = 250;

	/**
	 * A melee damage dealer of a party (not a dagger, whose job is to dive) doesn't charge an enemy far from its party alone, into all of them: it holds by its party until that enemy comes close, its tank engages it, or the enemy goes after the party.
	 * @param npc the fake player
	 * @param profile its profile
	 * @param party its party
	 * @param target the enemy it would go for
	 * @param now the current time
	 * @return {@code true} if it holds this think
	 */
	/** #23: a tank leading its party holds this much further ahead while it advances, and stops advancing once its target is this close. */
	private static final int TANK_LEAD_STEP = 300;
	private static final int TANK_LEAD_STOP = 700;
	/** #23: a healer doesn't follow its leader while enemies are this close and its leader this close (its heals reach). */
	private static final int HEALER_FIGHT_RANGE = 1200;
	private static final int HEALER_FOLLOW_REACH = 550;

	/**
	 * #23: {@code true} if an enemy close by targets a member of {@code party}.
	 */
	private static boolean isPartyFought(Attackable npc, FakePlayerParty party)
	{
		for (Creature enemy : World.getInstance().getVisibleObjectsInRange(npc, Creature.class, GUARD_SCAN_RANGE))
		{
			if ((enemy.getTarget() instanceof Creature victim) && party.isMember(victim) && isPvpEnemy(enemy) && !enemy.isAlikeDead() && isValidTarget(npc, enemy))
			{
				return true;
			}
		}
		return false;
	}

	/** #25: within its attack range and this much more, it is already on its target: no hold. */
	private static final int HOLD_ENGAGED_MARGIN = 150;
	/** #25: it holds on one enemy this long at most. */
	private static final long HOLD_MAX_TIME = 5000;

	private boolean holdForTank(Attackable npc, FakePlayerPvpProfile profile, FakePlayerParty party, Creature target, long now)
	{
		final Role role = profile.isTransformed() ? Role.FIGHTER : profile.getRole();
		if ((role != Role.FIGHTER) || _fleeing || _regrouping)
		{
			return false;
		}

		// The enemy goes after its party: fair game.
		final WorldObject itsTarget = target.getTarget();
		if ((itsTarget == npc) || ((itsTarget instanceof Creature) && party.isMember((Creature) itsTarget)))
		{
			return false;
		}

		// #25: already on it. Holding is for not charging in, not for leaving a fight it is in.
		if ((npc.calculateDistance2D(target) - npc.getTemplate().getCollisionRadius() - target.getTemplate().getCollisionRadius()) <= (npc.getPhysicalAttackRange() + HOLD_ENGAGED_MARGIN))
		{
			_holdTarget = null;
			return false;
		}

		Creature tankMember = null;
		long x = 0;
		long y = 0;
		long z = 0;
		int others = 0;
		for (Creature member : party.getMembers())
		{
			if ((member == npc) || member.isDead() || !member.isSpawned() || (member.getInstanceId() != npc.getInstanceId()) || (npc.calculateDistance2D(member) > GUARD_SCAN_RANGE))
			{
				continue;
			}

			// Its tank is on that enemy already: it follows it in.
			if (isTank(member) && (member.calculateDistance2D(target) <= MELEE_TANK_LEAD))
			{
				return false;
			}
			if (isTank(member) && ((tankMember == null) || (npc.calculateDistance2D(member) < npc.calculateDistance2D(tankMember))))
			{
				tankMember = member;
			}
			x += member.getX();
			y += member.getY();
			z += member.getZ();
			others++;
		}
		if (others == 0)
		{
			return false;
		}

		// #25: by its tank when it has one (the middle of the party sits far back when the backline does).
		final Location anchor = tankMember != null ? new Location(tankMember.getX(), tankMember.getY(), tankMember.getZ()) : new Location((int) (x / others), (int) (y / others), (int) (z / others));
		if (target.calculateDistance2D(anchor) <= MELEE_LEASH)
		{
			return false;
		}

		// #25: a hold on one enemy lasts a moment, then it goes.
		if (_holdTarget != target)
		{
			_holdTarget = target;
			_holdSince = now;
		}
		else if ((now - _holdSince) > HOLD_MAX_TIME)
		{
			return false;
		}

		_progressTime = now; // Waiting on purpose, not stuck.
		trace(Reason.HOLD_FOR_TANK, target);
		if ((npc.calculateDistance2D(anchor) > MELEE_HOLD_DISTANCE) && !npc.isMovementDisabled())
		{
			final Location point = GeoEngine.getInstance().getValidLocation(npc.getX(), npc.getY(), npc.getZ(), anchor.getX(), anchor.getY(), anchor.getZ(), npc.getInstanceId());
			npc.setRunning();
			moveTo(point.getX(), point.getY(), point.getZ());
		}
		else if (npc.isMoving())
		{
			clientStopMoving(null);
			npc.setHeading(LocationUtil.calculateHeadingFrom(npc, target));
		}
		return true;
	}

	/**
	 * @param enemy an enemy diving a squishy
	 * @param victim the squishy
	 * @return how urgent it is to peel {@code enemy}, lower first: close to its victim, on a healer, a melee one (a dagger on the healer) before an archer or mage that shoots from where it stands
	 */
	private static double getDiverScore(Creature enemy, Creature victim)
	{
		return enemy.calculateDistance2D(victim) - (isHealer(victim) ? DIVER_HEALER_BONUS : 0) + (isSquishy(enemy) ? DIVER_RANGED_MALUS : 0);
	}

	/**
	 * @param enemy an enemy
	 * @param guarded the party members a tank guards
	 * @return {@code true} if {@code enemy} still targets one of the squishies among them, close to it
	 */
	private static boolean isDiving(Creature enemy, List<Creature> guarded)
	{
		final WorldObject itsTarget = enemy.getTarget();
		if (enemy.isAlikeDead() || !(itsTarget instanceof Creature) || !guarded.contains(itsTarget) || !isSquishy((Creature) itsTarget))
		{
			return false;
		}
		return enemy.calculateDistance2D(itsTarget) <= (GUARD_THREAT_RANGE * DIVER_KEEP_FACTOR);
	}

	/** An enemy this close to a healer and targeting it is on it. */
	private static final int HEALER_DIVE_RANGE = 200;
	/** A healer someone is on heals itself below this much of its HP. */
	private static final double HEALER_SELF_HEAL = 0.85;
	/** A healer running to its tank stops this close to it. */
	private static final int HEALER_RETREAT_DISTANCE = 150;
	/** #5: enemies this close shooting or casting at a healer are on it too. */
	private static final int HEALER_PRESSURE_RANGE = 1000;
	/** #5: below this much of its HP a healer someone is on heals itself before anything else. */
	private static final double HEALER_LOW_HP = 0.5;
	/** #5: with an enemy casting or shooting at it, a healer heals itself already below this much of its HP. */
	private static final double HEALER_PRE_HEAL = 0.95;
	/** #27: below this much of its HP, a healer under ranged fire steps back this far, out of the shooters' reach. */
	private static final double HEALER_RANGED_RETREAT_HP = 0.7;
	private static final int HEALER_RANGED_RETREAT_STEP = 300;
	/** #6: a silenced healer takes cover this far behind its tank, away from who is on it. */
	private static final int HEALER_COVER_DISTANCE = 150;
	/** An archer or mage looks after party healers this close, and stops someone on one at most this often. */
	private static final int ALLY_PEEL_SCAN = 1200;
	private static final long ALLY_PEEL_INTERVAL = 3000;

	/**
	 * A healer someone is on (close to it and targeting it) defends itself, like a player: it stops them (root, sleep...), heals itself, and runs to its tank. Until they are off it, it doesn't attack anyone.
	 * @param npc the healer
	 * @param profile its profile
	 * @param party its party
	 * @param now the current time
	 * @return {@code true} if someone is on it (it acted, or had nothing better to do than look after its party)
	 */
	private boolean defendHealer(Attackable npc, FakePlayerPvpProfile profile, FakePlayerParty party, long now)
	{
		// Who is on it: the closest enemy close by and targeting it, and (#5) one shooting or casting at it from range.
		Creature attacker = null;
		double closest = Double.MAX_VALUE;
		Creature shooter = null;
		for (Creature enemy : World.getInstance().getVisibleObjectsInRange(npc, Creature.class, HEALER_PRESSURE_RANGE))
		{
			if ((enemy.getTarget() != npc) || !isPvpEnemy(enemy) || enemy.isAlikeDead() || !isValidTarget(npc, enemy))
			{
				continue;
			}
			final double distance = npc.calculateDistance2D(enemy);
			if (distance <= HEALER_DIVE_RANGE)
			{
				if (distance < closest)
				{
					attacker = enemy;
					closest = distance;
				}
			}
			else if (enemy.isCastingNow() || enemy.isAttackingNow())
			{
				shooter = enemy;
			}
		}
		if ((attacker == null) && (shooter == null))
		{
			return false;
		}
		final Creature threat = attacker != null ? attacker : shooter;

		// #6: it drinks potions like any fake player (it used to skip them: this returns before the rest of the fight logic, where it drinks).
		FakePlayerPvpManager.getInstance().tryPotion(npc);

		// #5: low, its heal comes before anything else (it used to root a diver at 6% HP, and die).
		final double hpRatio = npc.getCurrentHp() / npc.getMaxHp();

		// #27: under ranged burst, it steps out of the shooters' reach toward its party before it heals again (healing in place inside their reach lost the race at level 80).
		if ((attacker == null) && (shooter != null) && !npc.isMovementDisabled())
		{
			if (npc.isMoving() && (now < _kiteEndTime))
			{
				trace(Reason.HEALER_RETREAT, shooter);
				return true;
			}
			if ((hpRatio < HEALER_RANGED_RETREAT_HP) && (now >= _nextKiteTime) && (npc.calculateDistance2D(shooter) <= (getThreatReach(shooter) + 50)))
			{
				final Location point = pickRetreat(npc, shooter, HEALER_RANGED_RETREAT_STEP, null, 0, getPartyAnchor(npc, party));
				if (point != null)
				{
					trace(Reason.HEALER_RETREAT, shooter);
					npc.setRunning();
					moveTo(point.getX(), point.getY(), point.getZ());
					_kiteEndTime = now + Math.min(2000, (long) ((npc.calculateDistance2D(point) * 1000.0) / Math.max(1, npc.getMoveSpeed())));
					_nextKiteTime = now + 2500;
					return true;
				}
			}
		}
		if ((hpRatio < HEALER_LOW_HP) && healSelf(npc, profile))
		{
			return true;
		}

		// Stop the one on it.
		if ((attacker != null) && !isDisabled(attacker))
		{
			final int collision = npc.getTemplate().getCollisionRadius() + attacker.getTemplate().getCollisionRadius();
			final Skill peel = pickPeel(npc, attacker, profile, getKnownDefenses(attacker), closest - collision);
			if (peel != null)
			{
				trace(Reason.HEALER_DEFEND, attacker, peel, "PEEL");
				if (useSkill(npc, attacker, peel, closest, collision, false))
				{
					return true;
				}
			}
		}

		// Heal itself, with its fastest heal; (#5) already a little hurt while one casts or shoots at it, so the heal lands before the next hit.
		if (((hpRatio < HEALER_SELF_HEAL) || ((shooter != null) && (hpRatio < HEALER_PRE_HEAL))) && healSelf(npc, profile))
		{
			return true;
		}

		// #5: from range only, it stays where it heals from (running to its tank doesn't take it out of a mage's reach), but it doesn't attack.
		final boolean silenced = npc.isMuted();
		if ((attacker == null) && !silenced)
		{
			if (!supportParty(npc, profile, party, true))
			{
				trace(Reason.HEALER_DEFEND, threat);
			}
			return true;
		}

		// Run to its tank (or the rest of its party).
		Creature guard = null;
		double guardDistance = Double.MAX_VALUE;
		long x = 0;
		long y = 0;
		long z = 0;
		int others = 0;
		for (Creature member : party.getMembers())
		{
			if ((member == npc) || member.isDead() || !member.isSpawned() || (member.getInstanceId() != npc.getInstanceId()))
			{
				continue;
			}
			x += member.getX();
			y += member.getY();
			z += member.getZ();
			others++;
			final double distance = npc.calculateDistance2D(member);
			if (isTank(member) && (distance < guardDistance))
			{
				guard = member;
				guardDistance = distance;
			}
		}

		// #6: silenced, it can't heal nor stop anyone: it takes cover behind its tank, on the far side from who is on it.
		Location shelter = guard != null ? new Location(guard.getX(), guard.getY(), guard.getZ()) : (others > 0 ? new Location((int) (x / others), (int) (y / others), (int) (z / others)) : null);
		if (silenced && (guard != null))
		{
			final double away = Math.hypot(guard.getX() - threat.getX(), guard.getY() - threat.getY());
			if (away > 1)
			{
				shelter = new Location(guard.getX() + (int) (((guard.getX() - threat.getX()) * HEALER_COVER_DISTANCE) / away), guard.getY() + (int) (((guard.getY() - threat.getY()) * HEALER_COVER_DISTANCE) / away), guard.getZ());
			}
		}
		if ((shelter != null) && (npc.calculateDistance2D(shelter) > HEALER_RETREAT_DISTANCE) && !npc.isMovementDisabled())
		{
			trace(Reason.HEALER_RETREAT, threat);
			final Location point = GeoEngine.getInstance().getValidLocation(npc.getX(), npc.getY(), npc.getZ(), shelter.getX(), shelter.getY(), shelter.getZ(), npc.getInstanceId());
			npc.setRunning();
			moveTo(point.getX(), point.getY(), point.getZ());
			return true;
		}

		// By its tank already: it looks after its party, and doesn't attack.
		if (!supportParty(npc, profile, party, true))
		{
			trace(Reason.HEALER_DEFEND, threat);
		}
		return true;
	}

	/**
	 * #27: where its party is: its closest tank, otherwise the middle of the others.
	 * @return the place, {@code null} if it is alone
	 */
	private static Location getPartyAnchor(Attackable npc, FakePlayerParty party)
	{
		Creature tank = null;
		long x = 0;
		long y = 0;
		long z = 0;
		int others = 0;
		for (Creature member : party.getMembers())
		{
			if ((member == npc) || member.isDead() || !member.isSpawned() || (member.getInstanceId() != npc.getInstanceId()))
			{
				continue;
			}
			if (isTank(member) && ((tank == null) || (npc.calculateDistance2D(member) < npc.calculateDistance2D(tank))))
			{
				tank = member;
			}
			x += member.getX();
			y += member.getY();
			z += member.getZ();
			others++;
		}
		if (tank != null)
		{
			return new Location(tank.getX(), tank.getY(), tank.getZ());
		}
		return others > 0 ? new Location((int) (x / others), (int) (y / others), (int) (z / others)) : null;
	}

	/**
	 * A healer heals itself with its fastest heal.
	 * @return {@code true} if it cast one
	 */
	private boolean healSelf(Attackable npc, FakePlayerPvpProfile profile)
	{
		Skill heal = pickHeal(npc, quickHeals(npc, profile.getSkills(SkillCategory.PARTY_HEAL)), npc, true);
		if (heal == null)
		{
			heal = pickHeal(npc, quickHeals(npc, profile.getSkills(SkillCategory.HEAL)), npc, true);
		}
		return (heal != null) && castSupport(npc, npc, heal, Reason.SUPPORT_HEAL);
	}

	/** #8: with a melee enemy this close on it, a self-heal longer than this to cast is left aside (it gets interrupted: Divine Heal 57% of the time). */
	private static final int MELEE_ON_ME_RANGE = 150;
	private static final int LONG_HEAL_TIME = 2000;

	/**
	 * #8: the heals it can cast on itself now: all of them, or only the quick ones while a melee enemy is on it.
	 */
	private static List<Skill> quickHeals(Attackable npc, List<Skill> heals)
	{
		if (heals.isEmpty() || !isEnemyOnMe(npc, MELEE_ON_ME_RANGE))
		{
			return heals;
		}
		final List<Skill> quick = new ArrayList<>(heals.size());
		for (Skill heal : heals)
		{
			if (heal.getHitTime() <= LONG_HEAL_TIME)
			{
				quick.add(heal);
			}
		}
		return quick;
	}

	/**
	 * An archer or mage stops (root, stun, sleep...) an enemy that is on one of its party's healers, from where it stands, without leaving its own target for long.
	 * @param npc the fake player
	 * @param profile its profile
	 * @param party its party
	 * @param now the current time
	 * @return {@code true} if it cast something on such an enemy
	 */
	private boolean peelForHealer(Attackable npc, FakePlayerPvpProfile profile, FakePlayerParty party, long now)
	{
		if ((now < _nextAllyPeel) || profile.getSkills(SkillCategory.PEEL).isEmpty())
		{
			return false;
		}

		for (Creature member : party.getMembers())
		{
			if ((member == npc) || member.isDead() || !isHealer(member) || (npc.calculateDistance2D(member) > ALLY_PEEL_SCAN))
			{
				continue;
			}

			for (Creature enemy : World.getInstance().getVisibleObjectsInRange(member, Creature.class, HEALER_DIVE_RANGE))
			{
				if ((enemy.getTarget() != member) || !isPvpEnemy(enemy) || enemy.isAlikeDead() || isDisabled(enemy) || !isValidTarget(npc, enemy) || !GeoEngine.getInstance().canSeeTarget(npc, enemy))
				{
					continue;
				}

				final double distance = npc.calculateDistance2D(enemy);
				final int collision = npc.getTemplate().getCollisionRadius() + enemy.getTemplate().getCollisionRadius();
				final Skill peel = pickPeel(npc, enemy, profile, getKnownDefenses(enemy), distance - collision);
				if (peel == null)
				{
					continue;
				}

				_nextAllyPeel = now + ALLY_PEEL_INTERVAL;
				trace(Reason.PEEL_FOR_ALLY, enemy, peel, "PEEL");
				if (useSkill(npc, enemy, peel, distance, collision, false))
				{
					return true;
				}
			}
		}
		return false;
	}

	/** How many spots around its target it tries to find one that sees it, and how long it keeps a spot it chose. */
	private static final int FIRING_SPOT_SAMPLES = 12;
	private static final long FIRING_SPOT_TIME = 1500;
	/** The height of the eyes over the ground, for the line of sight from a spot. */
	private static final int EYE_HEIGHT = 40;

	/**
	 * In range of {@code target} but something is in the way (a rock, a tree, the crest of a slope): it goes to a spot around the target, within {@code range}, that sees it, the closest one. With no such spot it heads straight for the target, so the path goes
	 * around what is in the way. "Moving into range" would get it nowhere: it is in range already.
	 * @param npc the fake player
	 * @param target what it wants to hit, heal or buff
	 * @param range the reach of what it wants to use
	 */
	private void moveToSee(Attackable npc, Creature target, int range)
	{
		if (npc.isMovementDisabled())
		{
			return;
		}

		final long now = System.currentTimeMillis();
		Location spot = _firingSpot;
		if ((_firingTarget != target) || (now >= _firingUntil) || ((spot != null) && (target.calculateDistance2D(spot) > range)))
		{
			spot = findFiringSpot(npc, target, range);
			_firingSpot = spot;
			_firingTarget = target;
			_firingUntil = now + FIRING_SPOT_TIME;
		}
		else if (npc.isMoving())
		{
			return; // On its way.
		}

		npc.setRunning();
		if (spot != null)
		{
			moveTo(spot.getX(), spot.getY(), spot.getZ());
		}
		else
		{
			moveTo(target.getX(), target.getY(), target.getZ());
		}
	}

	/**
	 * @param npc the fake player
	 * @param target its target
	 * @param range the reach of what it wants to use
	 * @return the closest spot around {@code target}, within {@code range}, it can stand on and that sees {@code target} (the closest it reaches in a straight line, else the closest at all), {@code null} if none
	 */
	private static Location findFiringSpot(Attackable npc, Creature target, int range)
	{
		final GeoEngine geo = GeoEngine.getInstance();
		final int instanceId = npc.getInstanceId();
		final double current = npc.calculateDistance2D(target);
		final double outer = Math.max(60, Math.min(range - 50, current));
		final double[] radii =
		{
			outer,
			Math.max(60, outer * 0.6)
		};
		final double towardIt = Math.atan2(npc.getY() - target.getY(), npc.getX() - target.getX());

		// The spots around it where it can stand.
		final List<Location> spots = new ArrayList<>(FIRING_SPOT_SAMPLES * radii.length);
		for (int i = 0; i < FIRING_SPOT_SAMPLES; i++)
		{
			final double angle = towardIt + ((i * 2 * Math.PI) / FIRING_SPOT_SAMPLES);
			for (double radius : radii)
			{
				final int x = target.getX() + (int) (Math.cos(angle) * radius);
				final int y = target.getY() + (int) (Math.sin(angle) * radius);
				final Location point = geo.getValidLocation(target.getX(), target.getY(), target.getZ(), x, y, target.getZ(), instanceId);
				if (target.calculateDistance2D(point) >= 40)
				{
					spots.add(point);
				}
			}
		}

		// The closest first: the first one that sees the target and that it walks to in a straight line is it (no need to look further). Otherwise the closest one that sees the target, the path going around what is in the way.
		spots.sort(Comparator.comparingDouble(point -> npc.calculateDistance2D(point)));
		Location detour = null;
		for (Location point : spots)
		{
			if (!geo.canSeeTarget(point.getX(), point.getY(), point.getZ() + EYE_HEIGHT, target.getX(), target.getY(), target.getZ() + EYE_HEIGHT, instanceId))
			{
				continue;
			}
			if (geo.canMoveToTarget(npc.getX(), npc.getY(), npc.getZ(), point.getX(), point.getY(), point.getZ(), instanceId))
			{
				return point;
			}
			if (detour == null)
			{
				detour = point;
			}
		}
		return detour;
	}

	/**
	 * An archer or mage that can't see its target: another enemy of the fight it sees and reaches from where it stands (the closest), which it then fights. Not when its target is the party's call.
	 * @param npc the fake player
	 * @param profile its profile
	 * @param party its party, {@code null} for none
	 * @param target its target
	 * @param now the current time
	 * @return the enemy to fight instead, {@code null} to keep its target
	 */
	private Creature findVisibleTarget(Attackable npc, FakePlayerPvpProfile profile, FakePlayerParty party, Creature target, long now)
	{
		final boolean ranged = !profile.isTransformed() && (profile.getRole().isRanged() || profile.isBowHeld() || profile.isCasterHeld());
		if (!ranged || ((party != null) && (party.getFocus(now) == target)) || GeoEngine.getInstance().canSeeTarget(npc, target))
		{
			return null;
		}

		int reach = npc.getPhysicalAttackRange();
		for (Skill skill : profile.getSkills(SkillCategory.ATTACK))
		{
			reach = Math.max(reach, getSkillReach(skill));
		}

		Creature best = null;
		double bestGap = Double.MAX_VALUE;
		for (Creature enemy : getFightEnemies(npc, party))
		{
			if ((enemy == target) || enemy.isInvul())
			{
				continue;
			}

			final double gap = npc.calculateDistance2D(enemy) - npc.getTemplate().getCollisionRadius() - enemy.getTemplate().getCollisionRadius();
			if ((gap <= reach) && (gap < bestGap) && GeoEngine.getInstance().canSeeTarget(npc, enemy))
			{
				best = enemy;
				bestGap = gap;
			}
		}

		if (best != null)
		{
			trace(Reason.RETARGET_VISIBLE, best);
			if (!hates(npc, best, FakePlayerPvpManager.PVP_HATE))
			{
				npc.addDamageHate(best, 0, FakePlayerPvpManager.PVP_HATE);
			}
			_focus = best;
			_focusUntil = now + FOCUS_TIME;
		}
		return best;
	}

	/**
	 * @param creature a party member
	 * @return {@code true} for one that can't take a beating: a mystic (healers included) or an archer
	 */
	private static boolean isSquishy(Creature creature)
	{
		if (creature.isPlayer())
		{
			final Weapon weapon = creature.getActiveWeaponItem();
			return creature.asPlayer().isMageClass() || ((weapon != null) && ((weapon.getItemType() == WeaponType.BOW) || (weapon.getItemType() == WeaponType.CROSSBOW)));
		}

		final FakePlayerPvpProfile profile = creature.isNpc() ? creature.asNpc().getTemplate().getFakePlayerPvpProfile() : null;
		if ((profile == null) || profile.isTransformed())
		{
			return false;
		}
		return (profile.getRole() == Role.MAGE) || (profile.getRole() == Role.ARCHER);
	}

	/**
	 * @param creature a party member
	 * @return {@code true} for a healer, the first one to keep alive
	 */
	private static boolean isHealer(Creature creature)
	{
		if (creature.isPlayer())
		{
			return creature.asPlayer().isInCategory(CategoryType.CLERIC_GROUP);
		}

		final FakePlayerPvpProfile profile = creature.isNpc() ? creature.asNpc().getTemplate().getFakePlayerPvpProfile() : null;
		return (profile != null) && isPureHealer(profile);
	}

	// ---------------------------------------------------------------------------------------------
	// Party-vs-party simulation (see FakeSimulationManager)
	// ---------------------------------------------------------------------------------------------

	/** In a simulation, a march destination closer than this to the last one doesn't make it set off again. */
	private static final int MARCH_UPDATE_DISTANCE = 250;
	/** How often it may set off again toward the enemy. */
	private static final long MARCH_INTERVAL = 1000;

	/**
	 * A fake player of a simulation run, not fighting yet: it gets ready while its team does, then engages an enemy it sees, helps its party, or (the leader, or one on its own) marches toward the nearest other team.
	 * @param npc the fake player
	 * @param profile its profile
	 */
	private void thinkSimulation(Attackable npc, FakePlayerPvpProfile profile)
	{
		final SimulationRun.Phase phase = FakeSimulationManager.getInstance().getPhase(profile.getSimRunId());
		final FakePlayerParty party = profile.getParty();
		if ((phase == null) || (phase == SimulationRun.Phase.ENDED))
		{
			trace(Reason.SIM_WAIT, null);
			return;
		}

		// Its spawn follows it: nothing sends it back to where it appeared.
		final Spawn spawn = npc.getSpawn();
		if (spawn != null)
		{
			spawn.setXYZ(npc.getX(), npc.getY(), npc.getZ());
		}

		if (phase == SimulationRun.Phase.PREP)
		{
			// Supports buff the team, necromancers summon, duelists charge up.
			// (Its servitor is summoned at once when it spawns and when the fight starts, see FakePlayerPvpManager#prepareSimulant.)
			if (((party != null) && supportParty(npc, profile, party, false)) || ((profile.getCharges() < profile.getMaxCharges()) && castOnSelf(npc, null, profile.getSkills(SkillCategory.CHARGE), true, false)))
			{
				return;
			}
			trace(Reason.SIM_PREP, null);
			return;
		}

		// An enemy in sight: the fight is on.
		final Creature enemy = FakeSimulationManager.getInstance().findEnemyInSight(npc);
		if (enemy != null)
		{
			if (standUp(npc))
			{
				trace(Reason.STAND_UP, enemy);
				return;
			}
			trace(Reason.SIM_ENGAGE, enemy);
			npc.setRunning();
			FakePlayerPvpManager.getInstance().assistFight(npc, enemy);
			return;
		}

		// With its party: support, its fights, following its leader.
		if ((party != null) && thinkParty(npc, profile, party))
		{
			return;
		}

		// The leader (or one on its own) marches toward the nearest other team.
		final Location destination = FakeSimulationManager.getInstance().getMarchTarget(npc);
		if (destination == null)
		{
			trace(Reason.SIM_WAIT, null);
			return;
		}

		trace(Reason.SIM_MARCH, null);
		final long now = System.currentTimeMillis();
		if (npc.isMovementDisabled() || standUp(npc) || (now < _nextMarchTime))
		{
			return;
		}
		if (npc.isMoving() && (_marchDestination != null) && (Math.hypot(_marchDestination.getX() - destination.getX(), _marchDestination.getY() - destination.getY()) < MARCH_UPDATE_DISTANCE))
		{
			return;
		}

		_marchDestination = destination;
		_nextMarchTime = now + MARCH_INTERVAL;
		npc.setRunning();
		moveTo(destination.getX(), destination.getY(), destination.getZ());
	}

	/**
	 * Notes why it acted this think (see {@link FakeAiTrace}), unless it already did: the first reason noted in a think is the most precise one.
	 */
	private void trace(Reason reason, Creature target)
	{
		trace(reason, target, null, null);
	}

	private void trace(Reason reason, Creature target, Skill skill, String kind)
	{
		if (_traced)
		{
			return;
		}
		_traced = true;
		FakeAiTrace.trace(getActiveChar(), reason, target, skill, kind);
	}

	/**
	 * @param skill a skill it uses on its target
	 * @return what kind of skill it is for it (COMBO while it plays one, then PEEL, RUSH, DEBUFF, SPOIL, ATTACK), {@code null} if none of these
	 */
	private String getSkillKind(Skill skill)
	{
		if (_combo != null)
		{
			return "COMBO";
		}

		final FakePlayerPvpProfile profile = getActiveChar().getTemplate().getFakePlayerPvpProfile();
		if (profile == null)
		{
			return null;
		}
		for (SkillCategory category : SKILL_KINDS)
		{
			if (profile.getSkills(category).contains(skill))
			{
				return category.name();
			}
		}
		return null;
	}

	/**
	 * @param skill a class buff of a fake player
	 * @return {@code true} for a totem or prophecy it plays as a fight goes, not a buff to have on before it (see {@link FakePlayerPvpOraclesWarchiefs})
	 */
	public static boolean isPlayedInFight(Skill skill)
	{
		return FakePlayerPvpOraclesWarchiefs.isPlayedInFight(skill);
	}
	
	/** The kinds of skills used on a target, in the order a skill is told apart (a peel skill may also be a debuff). */
	private static final SkillCategory[] SKILL_KINDS =
	{
		SkillCategory.PEEL,
		SkillCategory.RUSH,
		SkillCategory.DEBUFF,
		SkillCategory.SPOIL,
		SkillCategory.ATTACK
	};

	/**
	 * Called when it joins a party: whatever it was up to on its own is over.
	 */
	public void onJoinParty()
	{
		final Attackable npc = getActiveChar();
		endPoke(npc);
		_meetTarget = null;
		endTalk();
		_visitor = null;
		_fleeing = false;
		_lastStand = false;
		_regrouping = false;
		_returning = false;
		_recentSupport.clear();
		npc.getTemplate().getFakePlayerPvpProfile().clearRevengeTarget();
		if (!npc.isInCombat())
		{
			setIntention(Intention.ACTIVE);
		}
	}
	
	/**
	 * Called when it leaves its party: it hunts on its own again.
	 */
	public void onLeaveParty()
	{
		_recentSupport.clear();
		_resting = false;
	}
	
	
	/**
	 * In a party: it looks after the party (healers and buffers), fights what the party fights and follows its leader. The leader of a party of fake players hunts like any fake player, the others go with it.
	 * @param npc the fake player
	 * @param profile its profile
	 * @param party its party
	 * @return {@code true} if it acted (or has nothing else to do), {@code false} to go on like a fake player on its own
	 */
	private boolean thinkParty(Attackable npc, FakePlayerPvpProfile profile, FakePlayerParty party)
	{
		if (supportParty(npc, profile, party, false))
		{
			return true;
		}
		
		// A necromancer keeps its servitor out, duelists and tyrants their energy full, like on their own.
		if ((profile.needsServitor() && mayCastSummon(npc, profile, System.currentTimeMillis()) && castOnSelf(npc, null, profile.getSkills(SkillCategory.SUMMON), true, false)) || ((profile.getCharges() < profile.getMaxCharges()) && castOnSelf(npc, null, profile.getSkills(SkillCategory.CHARGE), true, false)))
		{
			return true;
		}
		
		// The one that leads right now: a leader stunned or casting something long hands over for the moment.
		final Creature leader = party.getActiveLeader();
		if ((leader == null) || (leader == npc))
		{
			return false;
		}
		
		// The party fights: the leader's target, or a monster that attacks a member.
		final Creature target = findPartyTarget(npc, profile, party, leader);
		if (target != null)
		{
			_resting = false;
			if (standUp(npc))
			{
				return true;
			}
			
			trace(Reason.PARTY_ASSIST, target);
			if (isPvpEnemy(target))
			{
				FakePlayerPvpManager.getInstance().assistFight(npc, target);
				return true;
			}

			npc.addDamageHate(target, 0, 1);
			npc.setRunning();
			setIntention(Intention.ATTACK, target);
			return true;
		}

		// #23: a healer in a fight doesn't chase its leader while its heals still reach it: it stays where it heals from.
		if (isPureHealer(profile) && (npc.calculateDistance2D(leader) <= HEALER_FOLLOW_REACH) && isEnemyAround(npc, HEALER_FIGHT_RANGE))
		{
			if (npc.isMoving())
			{
				clientStopMoving(null);
			}
			trace(Reason.GUARD_HOLD, leader, null, "HEALER");
			return true;
		}

		if (followLeader(npc, party, leader))
		{
			trace(Reason.FOLLOW_LEADER, leader);
			return true;
		}
		
		// It rests when the party stands around (and sits down with its leader), like players. Not with a fight still going on around it: it stays on its feet and drinks a potion.
		final boolean leaderSits = leader.isPlayer() && leader.asPlayer().isSitting();
		final double hpRatio = npc.getCurrentHp() / npc.getMaxHp();
		_resting = !leader.isMoving() && (leaderSits || (hpRatio < (_resting ? 0.9 : 0.5)));
		if (_resting && isFightNear(npc))
		{
			_resting = false;
			if (!standUp(npc))
			{
				FakePlayerPvpManager.getInstance().tryPotion(npc);
			}
			return true;
		}
		if (_resting)
		{
			trace(Reason.REST, leader);
			if (!npc.isMoving() && !npc.isMovementDisabled())
			{
				sitDown(npc);
			}
			return true;
		}

		if (standUp(npc))
		{
			trace(Reason.STAND_UP, leader);
			return true;
		}

		trace(Reason.IDLE, leader);

		// Pulls the monsters around while the leader stands there (FakePartyHuntWhenIdle).
		if (FakePartyConfig.HUNT_WHEN_IDLE && !leader.isMoving() && !isPureHealer(profile) && !party.isFakeOnly())
		{
			final Creature prey = findIdlePrey(npc, leader);
			if (prey != null)
			{
				npc.addDamageHate(prey, 0, 1);
				npc.setRunning();
				setIntention(Intention.ATTACK, prey);
			}
		}
		return true;
	}
	
	
	/**
	 * Follows the party leader: runs (or walks) after it.
	 * @param npc the fake player
	 * @param party its party
	 * @param leader its party leader
	 * @return {@code true} if it is on its way to the leader
	 */
	private boolean followLeader(Attackable npc, FakePlayerParty party, Creature leader)
	{
		// Far away (it teleported): FakePartyManager brings it there a while later. Fake players hunting together just go their own way; a simulation team stays together and runs back.
		if (FakePartyManager.isFarFrom(npc, leader) && !party.isKeptTogether())
		{
			if (party.isFakeOnly())
			{
				FakePartyManager.getInstance().leave(npc, false, true);
				return false;
			}
			return true;
		}
		
		final double distance = npc.calculateDistance2D(leader);
		final int followDistance = FakePartyConfig.FOLLOW_DISTANCE;
		if (distance <= followDistance)
		{
			return false;
		}
		
		if (standUp(npc) || npc.isMovementDisabled())
		{
			return true;
		}
		
		if (leader.isRunning() || (distance > (followDistance * 3)))
		{
			npc.setRunning();
		}
		else
		{
			npc.setWalking();
		}
		
		moveToPawn(leader, Rnd.get(Math.max(40, followDistance / 3), Math.max(60, (followDistance * 2) / 3)));
		return true;
	}
	
	/**
	 * @param npc the fake player
	 * @param profile its profile
	 * @param party its party
	 * @param leader the party leader
	 * @return what the party fights and it should join: the leader's target once the leader (or a member) fights it, else the closest monster attacking a member, {@code null} if none. A healer stays out of fights.
	 */
	private Creature findPartyTarget(Attackable npc, FakePlayerPvpProfile profile, FakePlayerParty party, Creature leader)
	{
		if (isPureHealer(profile))
		{
			return null;
		}
		
		final WorldObject leaderTarget = leader.getTarget();
		if ((leaderTarget instanceof Creature) && isPartyPrey(npc, party, (Creature) leaderTarget, leader))
		{
			return (Creature) leaderTarget;
		}
		
		final Creature[] closest = new Creature[1];
		final double[] closestDistance =
		{
			Double.MAX_VALUE
		};
		World.getInstance().forEachVisibleObjectInRange(leader, Monster.class, FakePartyConfig.LEASH_RANGE, monster ->
		{
			if (monster.isFakePlayer() || monster.isDead() || !monster.isSpawned() || (monster.getInstanceId() != npc.getInstanceId()) || !isAttackingParty(monster, party))
			{
				return;
			}
			
			final double distance = npc.calculateDistance2D(monster);
			if (distance < closestDistance[0])
			{
				closest[0] = monster;
				closestDistance[0] = distance;
			}
		});
		return closest[0];
	}
	
	/**
	 * @param npc the fake player
	 * @param party its party
	 * @param target the leader's target
	 * @param leader the party leader
	 * @return {@code true} if the party fights {@code target} and it should help
	 */
	private static boolean isPartyPrey(Attackable npc, FakePlayerParty party, Creature target, Creature leader)
	{
		if ((target == npc) || target.isAlikeDead() || !target.isSpawned() || target.isInvisible() || (target.getInstanceId() != npc.getInstanceId()) || party.isMember(target) || (leader.calculateDistance2D(target) > FakePartyConfig.LEASH_RANGE))
		{
			return false;
		}
		
		// The leader of fake players hunting together: what it fights.
		if (leader.isAttackable())
		{
			return leader.asAttackable().getMostHated() == target;
		}
		
		// A player: a monster once the party fights it.
		if (target.isMonster() && !target.isFakePlayer())
		{
			return leader.isAttackingNow() || leader.isCastingNow() || isAttackingParty(target.asAttackable(), party);
		}
		
		// A player or fake player the leader fights: only one that can be fought without becoming a PK (flagged, karma), or one that attacks the party.
		if ((target.isPlayer() || target.isPvpFakePlayer()) && (leader.isAttackingNow() || leader.isCastingNow()))
		{
			final boolean flagged = target.isPlayer() ? ((target.asPlayer().getPvpFlag() > 0) || (target.getKarma() > 0)) : ((target.asNpc().getScriptValue() > 0) || (target.getKarma() > 0));
			final WorldObject itsTarget = target.getTarget();
			return flagged || ((itsTarget instanceof Creature) && party.isMember((Creature) itsTarget));
		}
		
		return false;
	}
	
	/**
	 * @param monster a monster
	 * @param party a party
	 * @return {@code true} if {@code monster} fights a member of {@code party} (it hates a member, or a member hit it)
	 */
	private static boolean isAttackingParty(Attackable monster, FakePlayerParty party)
	{
		for (Creature attacker : monster.getAggroList().keySet())
		{
			if (party.isMember(attacker) || (attacker.isSummon() && party.isMember(attacker.asPlayer())))
			{
				return true;
			}
		}
		
		final WorldObject target = monster.getTarget();
		return monster.isInCombat() && (target instanceof Creature) && party.isMember((Creature) target);
	}
	
	/**
	 * @param npc the fake player
	 * @param leader the party leader
	 * @return the closest monster around the leader nobody fights and worth hunting, {@code null} if none
	 */
	private static Creature findIdlePrey(Attackable npc, Creature leader)
	{
		final Creature[] closest = new Creature[1];
		final double[] closestDistance =
		{
			Double.MAX_VALUE
		};
		World.getInstance().forEachVisibleObjectInRange(leader, Monster.class, IDLE_HUNT_RANGE, monster ->
		{
			if (monster.isFakePlayer() || monster.isDead() || !monster.isSpawned() || monster.isRaid() || monster.isInCombat() || !monster.getAggroList().isEmpty() || (monster.getInstanceId() != npc.getInstanceId()) || (Math.abs(monster.getLevel() - leader.getLevel()) > MAX_HUNT_LEVEL_DIFFERENCE))
			{
				return;
			}
			
			// The line of sight (the costly check) only for one closer than the closest so far.
			final double distance = npc.calculateDistance2D(monster);
			if ((distance < closestDistance[0]) && GeoEngine.getInstance().canSeeTarget(leader, monster))
			{
				closest[0] = monster;
				closestDistance[0] = distance;
			}
		});
		return closest[0];
	}
	
	/**
	 * A healer or buffer looks after its party: heals (a group heal when several members are hurt), resurrection, recharge, then the buffs that are missing.
	 * @param npc the fake player
	 * @param profile its profile
	 * @param party its party
	 * @param inCombat {@code true} while it fights: only a support class keeps buffing then
	 * @return {@code true} if it cast (or walks over to cast) something
	 */
	private boolean supportParty(Attackable npc, FakePlayerPvpProfile profile, FakePlayerParty party, boolean inCombat)
	{
		final List<Skill> partyHeals = profile.getSkills(SkillCategory.PARTY_HEAL);
		final List<Skill> groupHeals = profile.getSkills(SkillCategory.GROUP_HEAL);
		final List<Skill> buffs = profile.getSkills(SkillCategory.PARTY_BUFF);
		final List<Skill> recharges = profile.getSkills(SkillCategory.RECHARGE);
		final List<Skill> resurrects = profile.getSkills(SkillCategory.RESURRECT);
		if ((partyHeals.isEmpty() && groupHeals.isEmpty() && buffs.isEmpty() && recharges.isEmpty() && resurrects.isEmpty()) || npc.isCastingNow() || npc.isDead())
		{
			return false;
		}
		
		final long now = System.currentTimeMillis();
		final List<Creature> members = new ArrayList<>();
		for (Creature member : party.getMembers())
		{
			if ((member != null) && member.isSpawned() && !member.isInvisible() && (member.getInstanceId() == npc.getInstanceId()) && (npc.calculateDistance2D(member) <= SUPPORT_RANGE))
			{
				members.add(member);
			}
		}
		
		final boolean healer = isPureHealer(profile);
		
		// Heals: a group heal when several members are hurt, otherwise the one hurt most.
		if (!partyHeals.isEmpty() || !groupHeals.isEmpty())
		{
			final double threshold = healer ? 0.75 : 0.5;
			Creature lowest = null;
			double lowestRatio = 1;
			for (Creature member : members)
			{
				if (member.isDead())
				{
					continue;
				}
				
				final double ratio = member.getCurrentHp() / member.getMaxHp();
				if (ratio < lowestRatio)
				{
					lowest = member;
					lowestRatio = ratio;
				}
			}
			
			for (Skill skill : groupHeals)
			{
				final int range = skill.getAffectRange() > 0 ? skill.getAffectRange() - 50 : 900;
				int hurt = 0;
				for (Creature member : members)
				{
					if (!member.isDead() && ((member.getCurrentHp() / member.getMaxHp()) < threshold) && (npc.calculateDistance2D(member) <= range))
					{
						hurt++;
					}
				}
				
				if (((hurt >= 2) || ((hurt == 1) && partyHeals.isEmpty())) && canCast(npc, skill, npc))
				{
					return castSupport(npc, npc, skill, Reason.SUPPORT_HEAL);
				}
			}

			if ((lowest != null) && (lowestRatio < threshold))
			{
				final Skill heal = pickHeal(npc, partyHeals, lowest, lowestRatio < 0.4);
				if ((heal != null) && castSupport(npc, lowest, heal, Reason.SUPPORT_HEAL))
				{
					return true;
				}
			}
		}
		
		// Resurrection: a healer at any time, the others once the fight is over.
		if (!resurrects.isEmpty() && (healer || !inCombat))
		{
			for (Creature member : members)
			{
				if (!member.isDead() || (member.isPlayer() && (member.asPlayer().isReviveRequested() || member.isPendingRevive())) || wasRecentlyCast(member, resurrects.get(0), now))
				{
					continue;
				}
				
				// In a fight, not a body among the enemies, nor one far from the party: it would die raising it.
				if (inCombat && !isSafeToResurrect(npc, party, member))
				{
					continue;
				}

				for (Skill skill : resurrects)
				{
					if (canCast(npc, skill, member) && castSupport(npc, member, skill, Reason.SUPPORT_RESURRECT))
					{
						return true;
					}
				}
			}
		}
		
		// Recharge: players running out of MP (fake players don't use any).
		if (!recharges.isEmpty())
		{
			Creature lowest = null;
			double lowestRatio = 0.5;
			for (Creature member : members)
			{
				if (member.isPlayer() && !member.isDead() && (member.getMaxMp() > 0) && ((member.getCurrentMp() / member.getMaxMp()) < lowestRatio))
				{
					lowest = member;
					lowestRatio = member.getCurrentMp() / member.getMaxMp();
				}
			}
			
			if (lowest != null)
			{
				for (Skill skill : recharges)
				{
					if (canCast(npc, skill, lowest) && castSupport(npc, lowest, skill, Reason.SUPPORT_RECHARGE))
					{
						return true;
					}
				}
			}
		}
		
		// Buffs: what a member is missing (or about to lose). A support class keeps at it in a fight, the others wait for it to be over.
		if (!buffs.isEmpty() && (!inCombat || profile.getBuild().isSupport()))
		{
			for (Skill skill : buffs)
			{
				final TargetType targetType = skill.getTargetType();
				final boolean selfCentered = (targetType == TargetType.PARTY) || (targetType == TargetType.PARTY_CLAN) || (targetType == TargetType.AURA) || (targetType == TargetType.SELF);
				final int range = skill.getAffectRange() > 0 ? skill.getAffectRange() - 50 : 900;
				for (Creature member : members)
				{
					if (member.isDead() || (selfCentered && (npc.calculateDistance2D(member) > range)) || !wantsBuff(member, skill) || !needsBuff(member, skill) || wasRecentlyCast(member, skill, now))
					{
						continue;
					}

					// In a fight: no minor buffs (resistances, Regeneration...), and only on a member it reaches from where it stands. It doesn't walk around the fight to buff.
					if (inCombat && (MINOR_BUFFS.contains(skill.getId()) || (!selfCentered && !isInSupportReach(npc, member, skill))))
					{
						continue;
					}
					
					if (!canCast(npc, skill, selfCentered ? npc : member))
					{
						break;
					}
					
					// A party buff from where it stands, a buff on one member from up close.
					if (castSupport(npc, selfCentered ? npc : member, skill, Reason.SUPPORT_BUFF))
					{
						_recentSupport.put(((long) skill.getId() << 32) | (member.getObjectId() & 0xFFFFFFFFL), now);
						return true;
					}
					break;
				}
			}
		}
		
		return false;
	}
	
	/** Buffs not worth a cast in the middle of a fight (resistances, Regeneration, Agility, Bless Shield): they wait until it is over. */
	private static final Set<Integer> MINOR_BUFFS = Set.of(1033, 1044, 1087, 1182, 1189, 1191, 1243, 1259, 1352, 1353, 1354, 1392, 1393);
	/** In a fight a body is only raised with no enemy this close to it, and this close to the rest of the party. */
	private static final int RESURRECT_ENEMY_RANGE = 600;
	private static final int RESURRECT_PARTY_RANGE = 900;

	/**
	 * @param npc the support
	 * @param member a party member
	 * @param skill a buff
	 * @return {@code true} if it can cast {@code skill} on {@code member} from where it stands (in range, in sight)
	 */
	private static boolean isInSupportReach(Attackable npc, Creature member, Skill skill)
	{
		final int range = skill.getCastRange() > 0 ? skill.getCastRange() : 600;
		final int collision = npc.getTemplate().getCollisionRadius() + member.getTemplate().getCollisionRadius();
		return (member == npc) || (((npc.calculateDistance2D(member) - collision) <= range) && GeoEngine.getInstance().canSeeTarget(npc, member));
	}

	/**
	 * @param npc the healer
	 * @param party its party
	 * @param body a dead party member
	 * @return {@code true} if it may raise {@code body} in the middle of a fight: no living enemy close to it, and it lies by the rest of the party (not in the enemy ranks)
	 */
	private static boolean isSafeToResurrect(Attackable npc, FakePlayerParty party, Creature body)
	{
		for (Creature enemy : World.getInstance().getVisibleObjectsInRange(body, Creature.class, RESURRECT_ENEMY_RANGE))
		{
			if (!enemy.isAlikeDead() && isPvpEnemy(enemy) && isValidTarget(npc, enemy))
			{
				return false;
			}
		}

		long x = 0;
		long y = 0;
		int living = 0;
		for (Creature member : party.getMembers())
		{
			if ((member != body) && !member.isDead() && member.isSpawned())
			{
				x += member.getX();
				y += member.getY();
				living++;
			}
		}
		return (living == 0) || (Math.hypot(body.getX() - (x / (double) living), body.getY() - (y / (double) living)) <= RESURRECT_PARTY_RANGE);
	}

	/**
	 * Casts a support skill on a party member (or itself), or walks into range first.
	 * @param npc the fake player
	 * @param target the member
	 * @param skill the skill
	 * @param reason what kind of support it is (for the decision trace)
	 * @return {@code true} if it cast or walks over to cast
	 */
	private boolean castSupport(Attackable npc, Creature target, Skill skill, Reason reason)
	{
		if (target != npc)
		{
			final int range = skill.getCastRange() > 0 ? skill.getCastRange() : 600;
			final int collision = npc.getTemplate().getCollisionRadius() + target.getTemplate().getCollisionRadius();
			final boolean inRange = (npc.calculateDistance2D(target) - collision) <= range;
			if (!inRange || !GeoEngine.getInstance().canSeeTarget(npc, target))
			{
				if (npc.isMovementDisabled())
				{
					return false;
				}

				trace(inRange ? Reason.FIRING_POSITION : Reason.SUPPORT_APPROACH, target, skill, reason.name());
				if (!standUp(npc))
				{
					npc.setRunning();
					if (inRange)
					{
						moveToSee(npc, target, range);
					}
					else
					{
						moveToPawn(target, Math.max(40, range - 60));
					}
				}
				return true;
			}
		}

		if (standUp(npc))
		{
			trace(Reason.STAND_UP, target);
			return true;
		}

		trace(reason, target, skill, null);
		_recentSupport.put(((long) skill.getId() << 32) | (target.getObjectId() & 0xFFFFFFFFL), System.currentTimeMillis());
		clientStopMoving(null);
		npc.setTarget(target);
		npc.doCast(skill);
		return true;
	}
	
	/**
	 * @param target a party member
	 * @param skill a support skill
	 * @param now the current time
	 * @return {@code true} if it cast {@code skill} on {@code target} a moment ago (it didn't take, or it is on its way)
	 */
	private boolean wasRecentlyCast(Creature target, Skill skill, long now)
	{
		final Long time = _recentSupport.get(((long) skill.getId() << 32) | (target.getObjectId() & 0xFFFFFFFFL));
		return (time != null) && ((now - time) < SUPPORT_RECAST_DELAY);
	}
	
	/**
	 * @param npc the healer
	 * @param heals its heals, biggest first
	 * @param target who needs one
	 * @param urgent {@code true} for a member in danger: the fastest one
	 * @return the heal to cast, {@code null} if none is ready
	 */
	private static Skill pickHeal(Attackable npc, List<Skill> heals, Creature target, boolean urgent)
	{
		Skill picked = null;
		for (Skill skill : heals)
		{
			if (!canCast(npc, skill, target))
			{
				continue;
			}
			
			if (!urgent)
			{
				return skill;
			}
			
			if ((picked == null) || (skill.getHitTime() < picked.getHitTime()))
			{
				picked = skill;
			}
		}
		
		return picked;
	}
	
	/**
	 * @param member a party member
	 * @param skill a buff
	 * @return {@code false} for a buff that doesn't help {@code member} (Might for a mage, Acumen for a fighter)
	 */
	private static boolean wantsBuff(Creature member, Skill skill)
	{
		if (FIGHTER_BUFFS.contains(skill.getId()))
		{
			return !isMage(member);
		}
		
		if (MAGE_BUFFS.contains(skill.getId()))
		{
			return isMage(member);
		}
		
		return true;
	}
	
	/**
	 * @param member a party member
	 * @param skill a buff
	 * @return {@code true} if {@code member} doesn't have it (nor anything that stops it), or it runs out soon
	 */
	private static boolean needsBuff(Creature member, Skill skill)
	{
		if (!FakePlayerPvpManager.isBuffActive(member, skill))
		{
			return true;
		}
		
		final BuffInfo info = member.getEffectList().getBuffInfoBySkillId(skill.getId());
		return (info != null) && (info.getSkill().getLevel() <= skill.getLevel()) && (info.getTime() < BUFF_REFRESH_TIME);
	}
	
	/**
	 * @param creature a player or fake player
	 * @return {@code true} for one that fights with magic: a mystic, or a fake player whose soul nukes count as much as its rapier (a hybrid Soul Hound)
	 */
	private static boolean castsMagic(Creature creature)
	{
		final FakePlayerPvpProfile profile = creature.isNpc() ? creature.asNpc().getTemplate().getFakePlayerPvpProfile() : null;
		return isMage(creature) || ((profile != null) && (profile.getBuild().isHybrid() || profile.isCasterHeld()));
	}
	
	/**
	 * @param creature a player or fake player
	 * @return {@code true} for a mystic
	 */
	private static boolean isMage(Creature creature)
	{
		if (creature.isPlayer())
		{
			return creature.asPlayer().isMageClass();
		}
		
		final FakePlayerPvpProfile profile = creature.isNpc() ? creature.asNpc().getTemplate().getFakePlayerPvpProfile() : null;
		return (profile != null) && (profile.getRole() == Role.MAGE);
	}
	
	/**
	 * @param profile a fake player profile
	 * @return {@code true} for a healer: it heals its party and stays out of the fights
	 */
	private static boolean isPureHealer(FakePlayerPvpProfile profile)
	{
		return profile.getBuild().isSupport() && !profile.getSkills(SkillCategory.PARTY_HEAL).isEmpty();
	}
}
