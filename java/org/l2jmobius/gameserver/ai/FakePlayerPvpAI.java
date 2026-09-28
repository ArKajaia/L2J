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
import org.l2jmobius.gameserver.config.custom.FakePlayerPvpConfig;
import org.l2jmobius.gameserver.data.xml.SkillData;
import org.l2jmobius.gameserver.geoengine.GeoEngine;
import org.l2jmobius.gameserver.managers.FakePlayerPvpManager;
import org.l2jmobius.gameserver.managers.ItemsOnGroundManager;
import org.l2jmobius.gameserver.model.Location;
import org.l2jmobius.gameserver.model.World;
import org.l2jmobius.gameserver.model.WorldObject;
import org.l2jmobius.gameserver.model.WorldRegion;
import org.l2jmobius.gameserver.model.actor.Attackable;
import org.l2jmobius.gameserver.model.actor.Creature;
import org.l2jmobius.gameserver.model.actor.Npc;
import org.l2jmobius.gameserver.model.actor.Player;
import org.l2jmobius.gameserver.model.actor.holders.npc.AggroInfo;
import org.l2jmobius.gameserver.model.actor.holders.npc.FakePlayerPvpBuild.Role;
import org.l2jmobius.gameserver.model.actor.holders.npc.FakePlayerPvpBuild.SkillCategory;
import org.l2jmobius.gameserver.model.actor.holders.npc.FakePlayerHolder;
import org.l2jmobius.gameserver.model.actor.holders.npc.FakePlayerPvpCombo;
import org.l2jmobius.gameserver.model.actor.holders.npc.FakePlayerPvpPersonality;
import org.l2jmobius.gameserver.model.actor.holders.npc.FakePlayerPvpProfile;
import org.l2jmobius.gameserver.model.actor.instance.Chest;
import org.l2jmobius.gameserver.model.actor.instance.Monster;
import org.l2jmobius.gameserver.model.effects.AbstractEffect;
import org.l2jmobius.gameserver.model.item.instance.Item;
import org.l2jmobius.gameserver.model.skill.BuffInfo;
import org.l2jmobius.gameserver.model.skill.EffectScope;
import org.l2jmobius.gameserver.model.skill.Skill;
import org.l2jmobius.gameserver.model.skill.holders.SkillHolder;
import org.l2jmobius.gameserver.model.skill.targets.TargetType;
import org.l2jmobius.gameserver.model.spawns.Spawn;
import org.l2jmobius.gameserver.model.zone.ZoneId;
import org.l2jmobius.gameserver.network.serverpackets.ChangeWaitType;
import org.l2jmobius.gameserver.util.LocationUtil;

/**
 * AI of a roaming fake player (see {@link FakePlayerPvpManager}). It plays like a character of its class: hunts the monsters around its spawn point, keeps its buffs up, uses the class skills it has learned (best ones first), drinks potions, archers and mages keep their
 * distance, and it fights any player that attacks it or steals its kill. In a PvP it plays like a player: it focuses the weakest enemy, closes the gap (Rush, Shadow Step, Dash), stops a melee attacker before stepping back (roots, stuns), waits out an invincible enemy,
 * cleanses roots and bleeds, and some fake players run from a fight they are losing and read a Scroll of Escape once they got away (and out of every player's sight). After a few hits it notices a player's Ultimate Defense, Guts, Zealot, Angelic Icon or magic mirror, stops
 * wasting what doesn't get through and keeps its distance while it can. It sits down to rest after a hard fight, and may go after a flagged or karma player passing by. Warriors surrounded by monsters take out a polearm to hit several of them at once. Necromancers keep their servitor out
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
		
		// After a hard fight, sit down and rest like a player until HP is back before doing anything else. They don't use MP.
		final double hpRatio = npc.getCurrentHp() / npc.getMaxHp();
		_resting = hpRatio < (_resting ? 0.9 : 0.5);
		if (_resting)
		{
			if (!npc.isMoving() && !npc.isMovementDisabled())
			{
				sitDown(npc);
			}
			return;
		}
		
		// Rested: up again (that takes a moment, like for a player).
		if (standUp(npc))
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
		
		lookForNewFaces(npc, System.currentTimeMillis());
		
		// Back from town for its killer, or a flagged or karma player passing by.
		if (lookForRevenge(npc, System.currentTimeMillis()) || lookForPvp(npc, System.currentTimeMillis()))
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
			// A player comes into sight while it reads its Scroll of Escape: it doesn't teleport in front of them, it runs on.
			final Skill casting = npc.getLastSkillCast();
			if ((casting != null) && (casting.getId() == SCROLL_OF_ESCAPE) && FakePlayerPvpManager.isSeenByPlayer(npc))
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
			
			if (npc.getTemplate().getFakePlayerPvpProfile() != null)
			{
				FakePlayerPvpManager.getInstance().tryPotion(npc);
			}
			return;
		}
		
		final FakePlayerPvpProfile profile = npc.getTemplate().getFakePlayerPvpProfile();
		if (profile == null)
		{
			super.thinkAttack();
			return;
		}
		
		// A sitting player stands up first.
		if (standUp(npc))
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
		
		// Against several players, the one it can finish first.
		final long now = System.currentTimeMillis();
		if (target.isPlayable())
		{
			target = chooseFocus(npc, target, now);
		}
		
		if (getAttackTarget() != target)
		{
			setAttackTarget(target);
		}
		
		if (npc.getTarget() != target)
		{
			npc.setTarget(target);
		}
		
		if (target.isPlayable())
		{
			_pvpTarget = target;
		}
		
		// Monsters are only hunted around the spawn point, players are chased further (and it runs away as far as it needs to).
		final Spawn spawn = npc.getSpawn();
		if ((spawn != null) && !_fleeing && !_regrouping && !_returning && (npc.calculateDistance2D(spawn) > (target.isPlayable() ? profile.getPersonality().getChaseRange() : profile.getPersonality().getLeashRange())))
		{
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
		else if ((now - _progressTime) > (target.isPlayable() ? PLAYER_STUCK_TIMEOUT : MONSTER_STUCK_TIMEOUT))
		{
			npc.stopHating(target);
			if (!target.isPlayable())
			{
				_unreachable = target;
				_unreachableUntil = now + UNREACHABLE_IGNORE_TIME;
			}
			dropTarget(npc);
			return;
		}
		
		// A flagged or karma player passing by is better game than a monster.
		if (!target.isPlayable())
		{
			lookForNewFaces(npc, now);
		}
		if (!target.isPlayable() && (lookForRevenge(npc, now) || lookForPvp(npc, now)))
		{
			return;
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
		final boolean pvp = target.isPlayable();
		final boolean canMove = !npc.isMovementDisabled();
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
		else if (thinkFlee(npc, profile, target, hpRatio, now) || thinkRegroup(npc, profile, target, hpRatio, now))
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
		FakePlayerPvpManager.getInstance().onFakePlayerAttacked(getActiveChar(), attacker);
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
		_fleeing = false;
		_resting = false;
		_regrouping = false;
		_returning = false;
		
		// Its servitor goes away with it.
		FakePlayerPvpManager.getInstance().unsummonServitor(getActiveChar());
		
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
		
		// Got away.
		if (pursuerGap > ESCAPE_DISTANCE)
		{
			// A Scroll of Escape: 20 seconds, a pursuer that catches up can still stun it or finish it. Never in front of a player: it runs on until nobody sees it.
			if (FakePlayerPvpConfig.ESCAPE_SCROLL)
			{
				if ((now >= _nextEscapeTime) && !FakePlayerPvpManager.isSeenByPlayer(npc) && readEscapeScroll(npc, target, now))
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
	 * @param npc the fake player
	 * @param profile its profile
	 * @param target the player it fights
	 * @param hpRatio its HP ratio
	 * @return {@code true} if it should leave this fight: any fake player against a player {@link FakePlayerPvpConfig#OUTLEVELED_DIFFERENCE} levels above it, a runner when low on HP while its enemy is still in good shape, or outnumbered
	 */
	private boolean shouldFlee(Attackable npc, FakePlayerPvpProfile profile, Creature target, double hpRatio)
	{
		if (_lastStand)
		{
			return false;
		}
		
		// Every fake player tries to get away from a player far above its level (who gets nothing for the kill anyway).
		final Player player = target.asPlayer();
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
		final double away = Math.atan2(npc.getY() - pursuer.getY(), npc.getX() - pursuer.getX());
		for (double turn : FLEE_TURNS)
		{
			final double angle = away + turn;
			final int x = npc.getX() + (int) (Math.cos(angle) * FLEE_STEP);
			final int y = npc.getY() + (int) (Math.sin(angle) * FLEE_STEP);
			final Location destination = GeoEngine.getInstance().getValidLocation(npc.getX(), npc.getY(), npc.getZ(), x, y, npc.getZ(), npc.getInstanceId());
			if (npc.calculateDistance2D(destination) >= (FLEE_STEP / 3))
			{
				npc.setRunning();
				moveTo(destination.getX(), destination.getY(), destination.getZ());
				return true;
			}
		}
		
		return false;
	}
	
	/**
	 * Starts reading a Scroll of Escape. When it finishes, the fake player teleports away (see {@link FakePlayerPvpManager#onFakePlayerEscaped}).
	 * @return {@code true} if it started
	 */
	private boolean readEscapeScroll(Attackable npc, Creature target, long now)
	{
		final Skill scroll = SkillData.getInstance().getSkill(SCROLL_OF_ESCAPE, 1);
		if ((scroll == null) || npc.isSkillDisabled(scroll))
		{
			return false;
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
			if ((enemy != null) && enemy.isPlayable())
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
			if ((enemy == null) || !enemy.isPlayable() || (info.getHate() < FakePlayerPvpManager.PVP_HATE) || !isValidTarget(npc, enemy))
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
			if ((enemy != null) && enemy.isPlayable() && (info.getHate() >= FakePlayerPvpManager.PVP_HATE) && isValidTarget(npc, enemy) && (npc.calculateDistance2D(enemy) < 1200))
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
		if ((focus != null) && (now < _focusUntil) && !focus.isInvul() && !isPlayedAround(npc, focus) && focus.isPlayable() && hates(npc, focus, FakePlayerPvpManager.PVP_HATE) && isValidTarget(npc, focus))
		{
			return focus;
		}
		
		_focus = null;
		Creature best = mostHated;
		double bestScore = focusScore(npc, mostHated);
		for (AggroInfo info : npc.getAggroList().values())
		{
			final Creature enemy = info.getAttacker();
			if ((enemy == null) || (enemy == mostHated) || !enemy.isPlayable() || (info.getHate() < FakePlayerPvpManager.PVP_HATE) || !isValidTarget(npc, enemy))
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
		if (!target.isPlayable() || (detectChance <= 0))
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
		if (_noticedDefenses.isEmpty() || !target.isPlayable())
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
		if (!target.isPlayable() || (gap <= FakePlayerPvpConfig.WEAPON_SWAP_MELEE_DISTANCE))
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
			if (_seenPlayers.contains(player.getObjectId()) || player.isAlikeDead() || player.isInvisible() || (player.getInstanceId() != npc.getInstanceId()) || hates(npc, player, FakePlayerPvpManager.PVP_HATE) || !GeoEngine.getInstance().canSeeTarget(npc, player))
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
		if (((personality.getAttackFlaggedChance() <= 0) && (personality.getAttackKarmaChance() <= 0)) || (now < _nextOpportunityScan))
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
			final boolean karma = player.getKarma() > 0;
			if ((!karma && (player.getPvpFlag() == 0)) || player.isAlikeDead() || player.isInvisible() || (player.isGM() && !player.getAccessLevel().canTakeAggro()))
			{
				continue;
			}
			
			if ((player.getInstanceId() != npc.getInstanceId()) || player.isInsideZone(ZoneId.PEACE) || player.isInsideZone(ZoneId.PVP) || player.isInsideZone(ZoneId.SIEGE) || player.isInOlympiadMode() || player.isInDuel())
			{
				continue;
			}
			
			if ((player.getLevel() > (npc.getLevel() + OPPORTUNITY_MAX_LEVEL_ABOVE)) || hates(npc, player, FakePlayerPvpManager.PVP_HATE) || !GeoEngine.getInstance().canSeeTarget(npc, player))
			{
				continue;
			}
			
			if ((_consideredPlayers.putIfAbsent(player.getObjectId(), now) == null) && (Rnd.get(100) < (karma ? personality.getAttackKarmaChance() : personality.getAttackFlaggedChance())))
			{
				FakePlayerPvpManager.getInstance().attackPlayer(npc, player, karma);
				return true;
			}
		}
		
		return false;
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
		if (pvpTarget.isPlayer() && pvpTarget.isAlikeDead())
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
		
		if (npc.calculateDistance2D(target) > FakePlayerPvpPersonality.of(npc).getChaseRange())
		{
			return false;
		}
		
		// Players are safe in town.
		return !target.isPlayable() || !target.isInsideZone(ZoneId.PEACE);
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

		final Creature unreachable = System.currentTimeMillis() < _unreachableUntil ? _unreachable : null;
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
			
			// Don't take a monster someone else is already fighting.
			if (FakePlayerPvpConfig.AVOID_PLAYER_MONSTERS && isFoughtByOthers(monster, npc))
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
			if ((!target.isPlayable() || close) && manager.equipWeapon(npc, profile.getMainWeapon()))
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
			if ((target.isPlayable() || (countSurroundingMonsters(npc) <= FakePlayerPvpConfig.POLEARM_PUT_AWAY_MONSTERS)) && manager.equipWeapon(npc, profile.getMainWeapon()))
			{
				_nextWeaponSwapTime = now + FakePlayerPvpConfig.WEAPON_SWAP_INTERVAL;
			}
			return;
		}
		
		// Only against monsters, and not with the bow out (it goes back to its weapon first).
		if (target.isPlayable() || profile.isBowHeld())
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
		int enemies = center == target ? 1 : 0;
		for (Creature creature : World.getInstance().getVisibleObjectsInRange(center, Creature.class, radius))
		{
			if ((creature != npc) && !creature.isDead() && ((creature == target) || hates(npc, creature, 1)))
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
}
