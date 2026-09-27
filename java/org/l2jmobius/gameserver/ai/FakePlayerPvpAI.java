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

import org.l2jmobius.commons.util.Rnd;
import org.l2jmobius.gameserver.config.custom.FakePlayerPvpConfig;
import org.l2jmobius.gameserver.geoengine.GeoEngine;
import org.l2jmobius.gameserver.managers.FakePlayerPvpManager;
import org.l2jmobius.gameserver.model.Location;
import org.l2jmobius.gameserver.model.World;
import org.l2jmobius.gameserver.model.WorldObject;
import org.l2jmobius.gameserver.model.WorldRegion;
import org.l2jmobius.gameserver.model.actor.Attackable;
import org.l2jmobius.gameserver.model.actor.Creature;
import org.l2jmobius.gameserver.model.actor.holders.npc.FakePlayerPvpBuild.Role;
import org.l2jmobius.gameserver.model.actor.holders.npc.FakePlayerPvpBuild.SkillCategory;
import org.l2jmobius.gameserver.model.actor.holders.npc.FakePlayerPvpCombo;
import org.l2jmobius.gameserver.model.actor.holders.npc.FakePlayerPvpProfile;
import org.l2jmobius.gameserver.model.actor.instance.Chest;
import org.l2jmobius.gameserver.model.actor.instance.Monster;
import org.l2jmobius.gameserver.model.effects.AbstractEffect;
import org.l2jmobius.gameserver.model.skill.EffectScope;
import org.l2jmobius.gameserver.model.skill.Skill;
import org.l2jmobius.gameserver.model.skill.targets.TargetType;
import org.l2jmobius.gameserver.model.spawns.Spawn;
import org.l2jmobius.gameserver.model.zone.ZoneId;

/**
 * AI of a roaming fake player (see {@link FakePlayerPvpManager}). It plays like a character of its class: hunts the monsters around its spawn point, keeps its buffs up, uses the class skills it has learned (best ones first), drinks potions, archers and mages keep their
 * distance, and it fights any player that attacks it or steals its kill until one of them dies.
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
	
	private long _kiteEndTime = 0;
	private long _nextKiteTime = 0;
	private boolean _resting = false;
	
	// The combo being played: which one, on whom, which step, and since when.
	private FakePlayerPvpCombo.Chain _combo = null;
	private Creature _comboTarget = null;
	private int _comboStep = 0;
	private long _comboStepStart = 0;
	private long _comboEnd = 0;
	private long _nextComboTime = 0;
	
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
		if ((hated != null) && isValidTarget(npc, hated))
		{
			npc.setRunning();
			setIntention(Intention.ATTACK, hated);
			return;
		}
		
		// Duelists and tyrants keep their energy full between fights, like players do.
		final FakePlayerPvpProfile profile = npc.getTemplate().getFakePlayerPvpProfile();
		if ((profile != null) && (profile.getCharges() < profile.getMaxCharges()) && castOnSelf(npc, null, profile.getSkills(SkillCategory.CHARGE), true, false))
		{
			return;
		}
		
		// After a hard fight, rest (like a player sitting down) until HP is back before looking for more monsters. They don't use MP.
		final double hpRatio = npc.getCurrentHp() / npc.getMaxHp();
		_resting = hpRatio < (_resting ? 0.9 : 0.5);
		
		if (_resting)
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
		if (npc.calculateDistance2D(spawn) > FakePlayerPvpConfig.HUNT_RANGE)
		{
			final Location home = GeoEngine.getInstance().getValidLocation(npc.getX(), npc.getY(), npc.getZ(), spawn.getX(), spawn.getY(), spawn.getZ(), npc.getInstanceId());
			moveTo(home.getX(), home.getY(), home.getZ());
			return;
		}
		
		if (Rnd.get(WANDER_CHANCE) == 0)
		{
			final int radius = Math.max(100, FakePlayerPvpConfig.HUNT_RANGE / 2);
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
		if (npc.isDead() || npc.isCastingNow() || npc.isCoreAIDisabled())
		{
			return;
		}
		
		final FakePlayerPvpProfile profile = npc.getTemplate().getFakePlayerPvpProfile();
		if (profile == null)
		{
			super.thinkAttack();
			return;
		}
		
		// Drop targets that are dead, gone or out of reach.
		Creature target = npc.getMostHated();
		for (int i = 0; (target != null) && !isValidTarget(npc, target) && (i < 10); i++)
		{
			if (target.isPlayer() && target.isAlikeDead() && (npc.getHating(target) >= FakePlayerPvpManager.PVP_HATE))
			{
				FakePlayerPvpManager.getInstance().onPlayerDefeated(npc);
			}
			
			npc.stopHating(target);
			target = npc.getMostHated();
		}
		
		if ((target == null) || !isValidTarget(npc, target))
		{
			setAttackTarget(null);
			npc.setTarget(null);
			setIntention(Intention.ACTIVE);
			return;
		}
		
		if (getAttackTarget() != target)
		{
			setAttackTarget(target);
		}
		
		if (npc.getTarget() != target)
		{
			npc.setTarget(target);
		}
		
		// Monsters are only hunted around the spawn point, players are chased further.
		final Spawn spawn = npc.getSpawn();
		if ((spawn != null) && (npc.calculateDistance2D(spawn) > (target.isPlayable() ? FakePlayerPvpConfig.CHASE_RANGE : FakePlayerPvpConfig.LEASH_RANGE)))
		{
			npc.stopHating(target);
			setAttackTarget(null);
			npc.setTarget(null);
			npc.setRunning();
			setIntention(Intention.MOVE_TO, spawn.getLocation());
			return;
		}
		
		final long now = System.currentTimeMillis();
		if (npc.isMoving() && (now < _kiteEndTime))
		{
			return; // Let the step back finish.
		}
		
		FakePlayerPvpManager.getInstance().tryPotion(npc);
		
		final Role role = profile.getRole();
		final boolean mage = role == Role.MAGE;
		final boolean pvp = target.isPlayable();
		final boolean canMove = !npc.isMovementDisabled();
		final double hpRatio = npc.getCurrentHp() / npc.getMaxHp();
		final double distance = npc.calculateDistance2D(target);
		final int collision = npc.getTemplate().getCollisionRadius() + target.getTemplate().getCollisionRadius();
		
		// Take care of itself first: emergency skills, heals, buffs.
		if ((hpRatio < 0.3) && castOnSelf(npc, target, profile.getSkills(SkillCategory.EMERGENCY), true, true))
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
		
		// Archers and mages step back from melee (unless a combo is finishing a stunned target).
		if (canMove && role.isRanged() && (_combo == null) && ((distance - collision) < FakePlayerPvpConfig.KITE_DISTANCE) && (now >= _nextKiteTime) && kiteStep(npc, target, now))
		{
			return;
		}
		
		// Duelists and tyrants recharge their energy when they are running low (big energy skills need 2-4 charges).
		if ((profile.getMaxCharges() > 0) && (profile.getCharges() < profile.getMaxCharges()) && ((profile.getCharges() < Math.min(4, profile.getMaxCharges())) || (Rnd.get(100) < 20)) && castOnSelf(npc, target, profile.getSkills(SkillCategory.CHARGE), true, pvp))
		{
			return;
		}
		
		// Combos: the skill chains a practiced player of this class plays.
		if (playCombo(npc, profile, target, pvp, distance, collision, canMove, now))
		{
			return;
		}
		
		// Stuns, roots and debuffs are mostly for players.
		final double reach = distance - collision;
		if ((Rnd.get(100) < (pvp ? FakePlayerPvpConfig.PVP_DEBUFF_CHANCE : 5)) && useSkill(npc, target, pickSkill(npc, target, profile.getSkills(SkillCategory.DEBUFF), true, role.isRanged() ? reach : -1, pvp), distance, collision, canMove))
		{
			return;
		}
		
		// Attack skills, best first. Mages cast whenever they can, and everyone spams skills against players.
		if ((mage || (Rnd.get(100) < (pvp ? FakePlayerPvpConfig.PVP_SKILL_CHANCE : FakePlayerPvpConfig.SKILL_CHANCE))) && useSkill(npc, target, pickSkill(npc, target, profile.getSkills(SkillCategory.ATTACK), false, role.isRanged() ? reach : -1, pvp), distance, collision, canMove))
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
		
		_actor.doAttack(target);
	}
	
	@Override
	protected void onActionAttacked(Creature attacker)
	{
		FakePlayerPvpManager.getInstance().onFakePlayerAttacked(getActiveChar(), attacker);
		super.onActionAttacked(attacker);
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
		
		if (npc.calculateDistance2D(target) > FakePlayerPvpConfig.CHASE_RANGE)
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
	private static Creature findPrey(Attackable npc)
	{
		final Spawn spawn = npc.getSpawn();
		Monster prey = null;
		double preyDistance = Double.MAX_VALUE;
		for (Monster monster : World.getInstance().getVisibleObjectsInRange(npc, Monster.class, FakePlayerPvpConfig.HUNT_RANGE))
		{
			if (monster.isDead() || monster.isFakePlayer() || monster.isRaid() || (monster instanceof Chest) || monster.isInvul() || !monster.isTargetable() || (monster.getInstanceId() != npc.getInstanceId()))
			{
				continue;
			}
			
			if (Math.abs(monster.getLevel() - npc.getLevel()) > MAX_HUNT_LEVEL_DIFFERENCE)
			{
				continue;
			}
			
			if ((spawn != null) && (monster.calculateDistance2D(spawn) > FakePlayerPvpConfig.LEASH_RANGE))
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
			if ((attacker != npc) && (attacker.isPlayable() || attacker.isFakePlayer()) && (monster.getHating(attacker) > 0))
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
		
		return skill.checkPreConditions(npc, target);
	}
	
	/**
	 * @param npc the fake player
	 * @param target its target
	 * @param skills skills in order of preference
	 * @param debuff {@code true} to skip the ones already on the target
	 * @param reach the distance to the target for a ranged fighter, which prefers skills that reach it from where it stands, -1 for melee
	 * @param pvp {@code true} against a player, the only time long cooldown skills are used
	 * @return a skill it can cast now, or {@code null}
	 */
	private static Skill pickSkill(Attackable npc, Creature target, List<Skill> skills, boolean debuff, double reach, boolean pvp)
	{
		if (skills.isEmpty() || (debuff && (target.isStunned() || target.isRooted())))
		{
			return null;
		}
		
		final List<Skill> ready = new ArrayList<>(3);
		Skill outOfReach = null;
		for (Skill skill : skills)
		{
			if ((debuff && target.isAffectedBySkill(skill.getId())) || (!pvp && isPvpOnly(skill)) || !canCast(npc, skill, target) || !isWorthCasting(npc, target, skill))
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
	 * Casts {@code skill} on {@code target}, or walks into range first.
	 * @return {@code true} if something was done this tick
	 */
	private boolean useSkill(Attackable npc, Creature target, Skill skill, double distance, int collision, boolean canMove)
	{
		return (skill != null) && (castOrApproach(npc, target, skill, distance, collision, canMove) != CastResult.FAILED);
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
		int range = skill.getCastRange();
		if (range <= 0)
		{
			range = skill.getAffectRange() > 0 ? skill.getAffectRange() : 80;
		}
		
		if (((distance - collision) > range) || !GeoEngine.getInstance().canSeeTarget(npc, target))
		{
			if (!canMove)
			{
				return CastResult.FAILED;
			}
			
			moveToPawn(target, Math.max(20, range - 20));
			return CastResult.MOVING;
		}
		
		clientStopMoving(null);
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
		_nextComboTime = now + 1000 + Rnd.get(1500);
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
	 * Area skills are only worth it with at least two enemies in their area.
	 * @return {@code true} if {@code skill} is a single target skill or would hit two or more enemies
	 */
	private static boolean isWorthCasting(Attackable npc, Creature target, Skill skill)
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
		
		final int radius = skill.getAffectRange() > 0 ? skill.getAffectRange() : 150;
		int enemies = center == target ? 1 : 0;
		for (Creature creature : World.getInstance().getVisibleObjectsInRange(center, Creature.class, radius))
		{
			if ((creature != npc) && !creature.isDead() && ((creature == target) || (npc.getHating(creature) > 0)))
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
	 * Steps back from {@code target}, onto a point geodata allows.
	 * @return {@code true} if it started moving
	 */
	private boolean kiteStep(Attackable npc, Creature target, long now)
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
		
		final int step = FakePlayerPvpConfig.KITE_STEP;
		final int x = npc.getX() + (int) ((dx / length) * step);
		final int y = npc.getY() + (int) ((dy / length) * step);
		final Location destination = GeoEngine.getInstance().getValidLocation(npc.getX(), npc.getY(), npc.getZ(), x, y, npc.getZ() + 30, npc.getInstanceId());
		if ((npc.calculateDistance2D(destination) < 50) || !GeoEngine.getInstance().canMoveToTarget(npc.getX(), npc.getY(), npc.getZ(), destination.getX(), destination.getY(), destination.getZ(), npc.getInstanceId()))
		{
			_nextKiteTime = now + 3000; // Cornered - fight for a while.
			return false;
		}
		
		moveTo(destination.getX(), destination.getY(), destination.getZ());
		_kiteEndTime = now + Math.min(3000, (long) ((step * 1000.0) / Math.max(1, npc.getMoveSpeed())));
		_nextKiteTime = now + 3000 + Rnd.get(2000);
		return true;
	}
}
