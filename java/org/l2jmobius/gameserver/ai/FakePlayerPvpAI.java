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
import org.l2jmobius.gameserver.data.xml.SkillData;
import org.l2jmobius.gameserver.geoengine.GeoEngine;
import org.l2jmobius.gameserver.managers.FakeClanManager;
import org.l2jmobius.gameserver.managers.FakePartyManager;
import org.l2jmobius.gameserver.managers.FakePlayerPvpManager;
import org.l2jmobius.gameserver.managers.ItemsOnGroundManager;
import org.l2jmobius.gameserver.managers.PvpSpotManager;
import org.l2jmobius.gameserver.model.Location;
import org.l2jmobius.gameserver.model.World;
import org.l2jmobius.gameserver.model.WorldObject;
import org.l2jmobius.gameserver.model.WorldRegion;
import org.l2jmobius.gameserver.model.actor.Attackable;
import org.l2jmobius.gameserver.model.actor.Creature;
import org.l2jmobius.gameserver.model.actor.Npc;
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
import org.l2jmobius.gameserver.model.actor.instance.Chest;
import org.l2jmobius.gameserver.model.actor.instance.Monster;
import org.l2jmobius.gameserver.model.clan.Clan;
import org.l2jmobius.gameserver.model.effects.AbstractEffect;
import org.l2jmobius.gameserver.model.item.instance.Item;
import org.l2jmobius.gameserver.model.skill.BuffInfo;
import org.l2jmobius.gameserver.model.skill.EffectScope;
import org.l2jmobius.gameserver.model.skill.Skill;
import org.l2jmobius.gameserver.model.skill.holders.SkillHolder;
import org.l2jmobius.gameserver.model.skill.targets.TargetType;
import org.l2jmobius.gameserver.model.spawns.Spawn;
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
 */
public class FakePlayerPvpAI extends AttackableAI
{
	/** One in this many idle ticks the fake player walks somewhere else around its spawn point. */
	private static final int WANDER_CHANCE = 8;
	/** Monsters more levels away than this are not worth hunting. */
	private static final int MAX_HUNT_LEVEL_DIFFERENCE = 10;
	/** The range a mage tries to fight from. */
	private static final int MAGE_RANGE = 600;
	
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
		/** Magical Mirror, Shield Deflect Magic, Reflect Magic: magic skills come back, only magic skills notice it. */
		MAGIC_MIRROR(MAGIC_DAMAGE | MAGIC_DEBUFFS, false, true, 351, 916, 6282);
		
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
	
	public FakePlayerPvpAI(Attackable creature)
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
		
		// A fake player of a PvP spot came only to fight: no hunting, poking, greeting or talks.
		if ((profile != null) && profile.isSpotFighter())
		{
			thinkSpot(npc, profile, System.currentTimeMillis());
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
			
			// Not with a fight still going on around it (someone hitting it, one it chose not to hit back, a pursuer...): it stays on its feet and drinks a potion.
			if (isPvpThreatNear(npc))
			{
				_resting = false;
				if (!standUp(npc))
				{
					FakePlayerPvpManager.getInstance().tryPotion(npc);
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
		if ((profile != null) && profile.needsServitor() && castOnSelf(npc, null, profile.getSkills(SkillCategory.SUMMON), true, false))
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
		
		// Hunt the monsters around.
		final Creature prey = findPrey(npc);
		if (prey != null)
		{
			npc.addDamageHate(prey, 0, 1);
			npc.setRunning();
			setIntention(Intention.ATTACK, prey);
			return;
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
		
		// Casting (a spell, a Scroll of Escape...): a player still drinks potions meanwhile.
		if (npc.isCastingNow())
		{
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
			return;
		}
		
		// A healer or buffer looks after its party first, even in a fight.
		final FakePlayerParty party = profile.getParty();
		final boolean withPlayers = (party != null) && !party.isFakeOnly();
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
		}
		
		if (getAttackTarget() != target)
		{
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
				npc.stopHating(target);
				dropTarget(npc);
				return;
			}
		}
		// Monsters are only hunted around the spawn point, players are chased further (and it runs away as far as it needs to).
		else if ((npc.getSpawn() != null) && !_fleeing && !_regrouping && !_returning && (npc.calculateDistance2D(npc.getSpawn()) > (isPvpEnemy(target) ? profile.getPersonality().getChaseRange() : profile.getPersonality().getLeashRange())))
		{
			final Spawn spawn = npc.getSpawn();
			// Back to its hunting ground, staying ACTIVE: a MOVE_TO that finds no path never arrives, and the AI would stop thinking.
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
			if (!isPvpEnemy(target) && !withPlayers)
			{
				lookForNewFaces(npc, now);
			}
			if (!isPvpEnemy(target) && !withPlayers && (lookForRevenge(npc, now) || lookForPvp(npc, now)))
			{
				return;
			}
		}
		
		if (npc.isMoving() && (now < _kiteEndTime))
		{
			return; // Let the step back finish.
		}
		
		FakePlayerPvpManager.getInstance().tryPotion(npc);
		
		// In Final Form a Kamael fights up close with the transformation's skills, whatever its class.
		final Role role = profile.isTransformed() ? Role.FIGHTER : profile.getRole();
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
		
		// Some players run from a fight they are losing.
		if (!pvp)
		{
			_fleeing = false;
			_regrouping = false;
			_returning = false;
		}
		else if (!withPlayers && (thinkFlee(npc, profile, target, hpRatio, now) || thinkRegroup(npc, profile, target, hpRatio, now)))
		{
			return;
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
		
		// Take care of itself first: emergency skills, cleansing, heals, buffs.
		if ((hpRatio < 0.3) && castOnSelf(npc, target, profile.getSkills(SkillCategory.EMERGENCY), true, true))
		{
			return;
		}
		
		if (castCleanse(npc, target, profile))
		{
			return;
		}
		
		if ((hpRatio < 0.5) && (Rnd.get(100) < 50) && castOnSelf(npc, target, profile.getSkills(SkillCategory.HEAL), true, true))
		{
			return;
		}
		
		// Long cooldown buffs (Frenzy, Zealot, Focus Power...) are saved for players.
		if (castOnSelf(npc, target, profile.getSkills(SkillCategory.BUFF), false, pvp))
		{
			return;
		}
		
		// A Kamael goes into Final Form (once an hour) when a PvP gets serious: hurt, or against several players.
		if (pvp && !profile.isTransformed() && ((hpRatio < 0.7) || (countPvpEnemies(npc) >= 2)) && castOnSelf(npc, target, profile.getSkills(SkillCategory.TRANSFORM), true, true))
		{
			return;
		}
		
		// Monsters can't bring it down: a necromancer summons a new servitor right away.
		if (!pvp && profile.needsServitor() && castOnSelf(npc, target, profile.getSkills(SkillCategory.SUMMON), true, false))
		{
			return;
		}
		
		// Nothing gets through an invincible player (Sonic Barrier, Force Barrier...): wait it out close by, like a player does.
		if (pvp && target.isInvul())
		{
			final int range = npc.getPhysicalAttackRange();
			if (canMove && !mage && ((distance - collision) > (range + 100)))
			{
				moveToPawn(target, range);
			}
			return;
		}
		
		// A player under Ultimate Defense, Guts, Zealot, Angelic Icon or a magic mirror: once it noticed, it leaves aside what doesn't get through and keeps its distance while it can.
		final int defenses = pvp ? getKnownDefenses(target) : 0;
		if (defenses != 0)
		{
			if (_combo != null)
			{
				endCombo(now);
			}
			
			if (holdOff(npc, target, defenses, mage, role.isRanged() || bowHeld, distance - collision, canMove, now))
			{
				return;
			}
		}
		
		// Archers and mages stop a player in melee range (root, stun, Aura Flash...), then step back (unless a combo is finishing a stunned target).
		if (canMove && role.isRanged() && (_combo == null) && !npc.isAttackingNow() && ((distance - collision) < profile.getPersonality().getKiteDistance()) && (now >= _nextKiteTime))
		{
			if (pvp && !isDisabled(target) && useSkill(npc, target, pickPeel(npc, target, profile, defenses, distance - collision), distance, collision, false))
			{
				return;
			}
			
			if (kiteStep(npc, target, now))
			{
				return;
			}
		}
		
		// Catching up: a speed buff on a player getting away, then a gap closer (Rush, Shadow Step).
		if (canMove && !role.isRanged() && !bowHeld)
		{
			if (pvp && isChasing(target, now) && castOnSelf(npc, target, profile.getSkills(SkillCategory.MOVE), false, true))
			{
				return;
			}
			
			if ((pvp ? isChasing(target, now) : (Rnd.get(100) < 20)) && useRush(npc, profile, target, distance, collision, pvp))
			{
				return;
			}
		}
		
		// Duelists and tyrants recharge their energy when they are running low (big energy skills need 2-4 charges).
		if ((profile.getMaxCharges() > 0) && (profile.getCharges() < profile.getMaxCharges()) && ((profile.getCharges() < Math.min(4, profile.getMaxCharges())) || (Rnd.get(100) < 20)) && castOnSelf(npc, target, profile.getSkills(SkillCategory.CHARGE), true, pvp))
		{
			return;
		}
		
		// A spoiler spoils the monster it fights first (Spoil, Spoil Crush), so it can be swept.
		if (!pvp && isSpoilable(target) && useSkill(npc, target, pickSkill(npc, target, profile.getSkills(SkillCategory.SPOIL), false, -1, false, 0), distance, collision, canMove))
		{
			return;
		}
		
		// Combos: the skill chains a practiced player of this class plays (with its weapon, not with the spare bow or polearm).
		if (!bowHeld && !polearmHeld && (defenses == 0) && playCombo(npc, profile, target, pvp, distance, collision, canMove, now))
		{
			return;
		}
		
		// Stuns, roots and debuffs are mostly for players (and half as often for a class that fights with its normal attack).
		final double reach = distance - collision;
		final FakePlayerPvpPersonality personality = profile.getPersonality();
		final int debuffChance = pvp ? (_autoAttacker ? (personality.getPvpDebuffChance() / 2) : personality.getPvpDebuffChance()) : 5;
		// With the bow out it only uses what reaches the target from where it stands, instead of running in.
		if ((Rnd.get(100) < debuffChance) && useSkill(npc, target, inReach(pickSkill(npc, target, profile.getSkills(SkillCategory.DEBUFF), true, (role.isRanged() || bowHeld) ? reach : -1, pvp, defenses), bowHeld, reach), distance, collision, canMove))
		{
			return;
		}
		
		// Attack skills, best first. Mages cast whenever they can, Gladiators, Tyrants and daggers fight with their skills, and the other classes (tanks, archers, most warriors) auto attack and use a skill now and then, like players.
		final int skillChance = _autoAttacker ? (pvp ? personality.getAutoAttackPvpSkillChance() : personality.getAutoAttackSkillChance()) : (pvp ? personality.getPvpSkillChance() : personality.getSkillChance());
		if ((mage || (Rnd.get(100) < skillChance)) && useSkill(npc, target, inReach(pickSkill(npc, target, profile.getSkills(SkillCategory.ATTACK), false, (role.isRanged() || bowHeld) ? reach : -1, pvp, defenses), bowHeld, reach), distance, collision, canMove))
		{
			return;
		}
		
		// Mages never melee: between spells they stay in casting range.
		if (mage)
		{
			if (canMove && (distance > (MAGE_RANGE + collision)))
			{
				moveToPawn(target, MAGE_RANGE);
			}
			return;
		}
		
		// Normal attack.
		final int range = npc.getPhysicalAttackRange();
		if ((distance > (range + collision)) || !GeoEngine.getInstance().canSeeTarget(npc, target))
		{
			if (canMove)
			{
				moveToPawn(target, range);
			}
			return;
		}
		
		_progressTime = now;
		if (!npc.isAttackingNow())
		{
			noticeDefenses(target, false);
		}
		_actor.doAttack(target);
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
		
		final FakePlayerPvpProfile profile = npc.getTemplate().getFakePlayerPvpProfile();
		if (profile != null)
		{
			profile.setEscapeInView(inView);
		}
		
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
	
	/**
	 * In a fight against several players, a player goes for the one it can finish first: low on HP, close by, and never an invincible one. It sticks to its choice for a while.
	 * @param npc the fake player
	 * @param mostHated the player it hates most
	 * @param now the current time
	 * @return the player to fight
	 */
	private Creature chooseFocus(Attackable npc, Creature mostHated, long now)
	{
		final Creature focus = _focus;
		if ((focus != null) && (now < _focusUntil) && !focus.isInvul() && !isPlayedAround(npc, focus) && isPvpEnemy(focus) && hates(npc, focus, FakePlayerPvpManager.PVP_HATE) && isValidTarget(npc, focus))
		{
			return focus;
		}
		
		_focus = null;
		Creature best = mostHated;
		double bestScore = focusScore(npc, mostHated);
		for (AggroInfo info : npc.getAggroList().values())
		{
			final Creature enemy = info.getAttacker();
			if ((enemy == null) || (enemy == mostHated) || !isPvpEnemy(enemy) || (info.getHate() < FakePlayerPvpManager.PVP_HATE) || !isValidTarget(npc, enemy))
			{
				continue;
			}
			
			// Clearly better only, so it doesn't flip between two.
			final double score = focusScore(npc, enemy);
			if (score < (bestScore - 0.25))
			{
				best = enemy;
				bestScore = score;
			}
		}
		
		if (best != mostHated)
		{
			_focus = best;
			_focusUntil = now + FOCUS_TIME;
		}
		return best;
	}
	
	/**
	 * @return how good a target {@code enemy} is, lower is better: never an invincible one, nor one whose Ultimate Defense, Guts, Zealot or Angelic Icon it noticed if there is another
	 */
	private double focusScore(Attackable npc, Creature enemy)
	{
		return (enemy.getCurrentHp() / Math.max(1, enemy.getMaxHp())) + (npc.calculateDistance2D(enemy) / 2000.0) + (enemy.isInvul() ? 2 : 0) + (isPlayedAround(npc, enemy) ? 1.5 : 0);
	}
	
	/**
	 * @return {@code true} if it noticed a defensive buff on {@code enemy} that keeps its damage from getting through, or that makes it keep away (see {@link #holdOff})
	 */
	private boolean isPlayedAround(Attackable npc, Creature enemy)
	{
		final FakePlayerPvpProfile profile = npc.getTemplate().getFakePlayerPvpProfile();
		final boolean mage = (profile != null) && !profile.isTransformed() && (profile.getRole() == Role.MAGE);
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
		if (_noticedDefenses.isEmpty() || !isPvpEnemy(target))
		{
			return 0;
		}
		
		int flags = 0;
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
		
		// Waiting on purpose, not stuck.
		_progressTime = now;
		
		final FakePlayerPvpPersonality personality = FakePlayerPvpPersonality.of(npc);
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
		for (Skill skill : profile.getSkills(SkillCategory.PEEL))
		{
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
			if ((skill.getCastRange() >= gap) && (pvp || !isPvpOnly(skill)) && canCast(npc, skill, target) && (castOrApproach(npc, target, skill, distance, collision, false) == CastResult.CAST))
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
				((FakePlayerPvpAI) other.getAI())._seenPlayers.add(npc.getObjectId());
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
		final FakePlayerPvpAI targetAI = (FakePlayerPvpAI) target.getAI();
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
		if ((partner != null) && partner.hasAI() && (partner.getAI() instanceof FakePlayerPvpAI))
		{
			final FakePlayerPvpAI ai = (FakePlayerPvpAI) partner.getAI();
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
		if (other.isTrialDuelist() || other.isInsideZone(ZoneId.PEACE) || FakePlayerPvpManager.isInPvp(other) || !other.hasAI() || !(other.getAI() instanceof FakePlayerPvpAI) || FakeClanManager.getInstance().isWarEnemy(npc, other))
		{
			return false;
		}
		
		// Busy with players: in their party.
		if (FakePartyManager.getInstance().isInPlayerParty(other) || FakePartyManager.getInstance().isSameGroup(npc, other))
		{
			return false;
		}
		
		final FakePlayerPvpAI ai = (FakePlayerPvpAI) other.getAI();
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
		if ((holder == null) || holder.isSitting())
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
		if (target.isAlikeDead() || !target.isSpawned() || target.isInvisible() || (target.getInstanceId() != npc.getInstanceId()))
		{
			return false;
		}
		
		// Never a member of its own party.
		if (FakePartyManager.getInstance().isSameGroup(npc, target))
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
		if (profile.isBowHeld())
		{
			if ((!isPvpEnemy(target) || close) && manager.equipWeapon(npc, profile.getMainWeapon()))
			{
				_nextWeaponSwapTime = now + FakePlayerPvpConfig.WEAPON_SWAP_INTERVAL;
			}
			return;
		}
		
		// Not worth it unless the player is at bow range, or almost.
		if ((_chaseTarget == null) || (gap > (profile.getBow().getAttackRange() + 300)))
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
		if (((distance - collision) > range) || !GeoEngine.getInstance().canSeeTarget(npc, target))
		{
			if (!canMove)
			{
				return CastResult.FAILED;
			}
			
			moveToPawn(target, Math.max(20, range - 20));
			return CastResult.MOVING;
		}
		
		if (target == _progressTarget)
		{
			_progressTime = System.currentTimeMillis();
		}
		
		noticeDefenses(target, skill.isMagic());
		clientStopMoving(null);
		
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
				if ((!combo.isPvp() || pvp) && (Rnd.get(100) < combo.getChance()) && isComboReady(npc, profile, combo, target))
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
			
			// Follow-ups that only make sense on a stunned/held target.
			if (step.needsDisabledTarget() && !isDisabled(target))
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
			
			if (!canCast(npc, skill, target))
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
				if (!step.isSelf() && ((step.needsDisabledTarget() && !isDisabled(target)) || !canCast(npc, skill, target)))
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
			if ((!recast && FakePlayerPvpManager.isBuffActive(npc, skill)) || (!pvp && isPvpOnly(skill)))
			{
				continue;
			}
			
			final TargetType targetType = skill.getTargetType();
			if ((targetType != TargetType.SELF) && (targetType != TargetType.ONE) && (targetType != TargetType.PARTY) && (targetType != TargetType.AURA))
			{
				continue;
			}
			
			if (!canCast(npc, skill, npc))
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
		
		final int x = npc.getX() + (int) ((dx / length) * step);
		final int y = npc.getY() + (int) ((dy / length) * step);
		final Location destination = GeoEngine.getInstance().getValidLocation(npc.getX(), npc.getY(), npc.getZ(), x, y, npc.getZ() + 30, npc.getInstanceId());
		if ((npc.calculateDistance2D(destination) < 50) || !GeoEngine.getInstance().canMoveToTarget(npc.getX(), npc.getY(), npc.getZ(), destination.getX(), destination.getY(), destination.getZ(), npc.getInstanceId()))
		{
			_nextKiteTime = now + 3000; // Cornered - fight for a while.
			return false;
		}
		
		npc.setRunning();
		moveTo(destination.getX(), destination.getY(), destination.getZ());
		_kiteEndTime = now + Math.min(3000, (long) ((step * 1000.0) / Math.max(1, npc.getMoveSpeed())));
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
		_resting = (hpRatio < (_resting ? 0.9 : 0.5)) && !isSpotThreatNear(npc, zone) && !isPvpThreatNear(npc);
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
		if ((profile.needsServitor() && castOnSelf(npc, null, profile.getSkills(SkillCategory.SUMMON), true, false)) || ((profile.getCharges() < profile.getMaxCharges()) && castOnSelf(npc, null, profile.getSkills(SkillCategory.CHARGE), true, false)))
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
			if (!isSpotEnemy(npc, creature, zone, true))
			{
				continue;
			}
			
			final double score = spotScore(npc, profile, creature, leaderId, grudgeId, now);
			if (score < bestScore)
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
		final double current = isSpotEnemy(npc, target, zone, false) ? spotScore(npc, profile, target, leaderId, grudgeId, now) : Double.MAX_VALUE;
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
		
		if (!zone.isInsideZone(creature) || FakeClanManager.getInstance().isFriend(npc, creature) || FakePartyManager.getInstance().isSameGroup(npc, creature))
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
		if ((profile.needsServitor() && castOnSelf(npc, null, profile.getSkills(SkillCategory.SUMMON), true, false)) || ((profile.getCharges() < profile.getMaxCharges()) && castOnSelf(npc, null, profile.getSkills(SkillCategory.CHARGE), true, false)))
		{
			return true;
		}
		
		final Creature leader = party.getLeader();
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
		
		if (followLeader(npc, party, leader))
		{
			return true;
		}
		
		// It rests when the party stands around (and sits down with its leader), like players. Not with a fight still going on around it: it stays on its feet and drinks a potion.
		final boolean leaderSits = leader.isPlayer() && leader.asPlayer().isSitting();
		final double hpRatio = npc.getCurrentHp() / npc.getMaxHp();
		_resting = !leader.isMoving() && (leaderSits || (hpRatio < (_resting ? 0.9 : 0.5)));
		if (_resting && isPvpThreatNear(npc))
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
			if (!npc.isMoving() && !npc.isMovementDisabled())
			{
				sitDown(npc);
			}
			return true;
		}
		
		if (standUp(npc))
		{
			return true;
		}
		
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
		// Far away (it teleported): FakePartyManager brings it there a while later. Fake players hunting together just go their own way.
		if (FakePartyManager.isFarFrom(npc, leader))
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
			
			if (!GeoEngine.getInstance().canSeeTarget(leader, monster))
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
					return castSupport(npc, npc, skill);
				}
			}
			
			if ((lowest != null) && (lowestRatio < threshold))
			{
				final Skill heal = pickHeal(npc, partyHeals, lowest, lowestRatio < 0.4);
				if ((heal != null) && castSupport(npc, lowest, heal))
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
				
				for (Skill skill : resurrects)
				{
					if (canCast(npc, skill, member) && castSupport(npc, member, skill))
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
					if (canCast(npc, skill, lowest) && castSupport(npc, lowest, skill))
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
					
					if (!canCast(npc, skill, selfCentered ? npc : member))
					{
						break;
					}
					
					// A party buff from where it stands, a buff on one member from up close.
					if (castSupport(npc, selfCentered ? npc : member, skill))
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
	
	/**
	 * Casts a support skill on a party member (or itself), or walks into range first.
	 * @param npc the fake player
	 * @param target the member
	 * @param skill the skill
	 * @return {@code true} if it cast or walks over to cast
	 */
	private boolean castSupport(Attackable npc, Creature target, Skill skill)
	{
		if (target != npc)
		{
			final int range = skill.getCastRange() > 0 ? skill.getCastRange() : 600;
			final int collision = npc.getTemplate().getCollisionRadius() + target.getTemplate().getCollisionRadius();
			if (((npc.calculateDistance2D(target) - collision) > range) || !GeoEngine.getInstance().canSeeTarget(npc, target))
			{
				if (npc.isMovementDisabled())
				{
					return false;
				}
				
				if (!standUp(npc))
				{
					npc.setRunning();
					moveToPawn(target, Math.max(40, range - 60));
				}
				return true;
			}
		}
		
		if (standUp(npc))
		{
			return true;
		}
		
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
