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
package org.l2jmobius.gameserver.model.actor.instance;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Future;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.l2jmobius.commons.threads.ThreadPool;
import org.l2jmobius.commons.util.Rnd;
import org.l2jmobius.gameserver.ai.Action;
import org.l2jmobius.gameserver.config.PlayerConfig;
import org.l2jmobius.gameserver.data.xml.SkillData;
import org.l2jmobius.gameserver.managers.DuelManager;
import org.l2jmobius.gameserver.model.WorldObject;
import org.l2jmobius.gameserver.model.actor.Attackable;
import org.l2jmobius.gameserver.model.actor.Creature;
import org.l2jmobius.gameserver.model.actor.Player;
import org.l2jmobius.gameserver.model.actor.Summon;
import org.l2jmobius.gameserver.model.actor.enums.creature.Team;
import org.l2jmobius.gameserver.model.actor.holders.player.Totems;
import org.l2jmobius.gameserver.model.actor.tasks.cubics.CubicAction;
import org.l2jmobius.gameserver.model.actor.tasks.cubics.CubicDisappear;
import org.l2jmobius.gameserver.model.actor.tasks.cubics.CubicHeal;
import org.l2jmobius.gameserver.model.effects.EffectType;
import org.l2jmobius.gameserver.model.groups.Party;
import org.l2jmobius.gameserver.model.skill.Skill;
import org.l2jmobius.gameserver.model.stats.Formulas;
import org.l2jmobius.gameserver.model.stats.Stat;
import org.l2jmobius.gameserver.model.zone.ZoneId;
import org.l2jmobius.gameserver.network.SystemMessageId;
import org.l2jmobius.gameserver.network.serverpackets.CombatInfo;

public class Cubic
{
	private static final Logger LOGGER = Logger.getLogger(Cubic.class.getName());
	
	// Type of Cubics
	public static final int STORM_CUBIC = 1;
	public static final int VAMPIRIC_CUBIC = 2;
	public static final int LIFE_CUBIC = 3;
	public static final int VIPER_CUBIC = 4;
	public static final int POLTERGEIST_CUBIC = 5;
	public static final int BINDING_CUBIC = 6;
	public static final int AQUA_CUBIC = 7;
	public static final int SPARK_CUBIC = 8;
	public static final int ATTRACT_CUBIC = 9;
	public static final int SMART_CUBIC_EVATEMPLAR = 10;
	public static final int SMART_CUBIC_SHILLIENTEMPLAR = 11;
	public static final int SMART_CUBIC_ARCANALORD = 12;
	public static final int SMART_CUBIC_ELEMENTALMASTER = 13;
	public static final int SMART_CUBIC_SPECTRALMASTER = 14;
	
	// Max range of cubic skills
	// TODO: Check/fix the max range
	public static final int MAX_MAGIC_RANGE = 900;
	
	// Cubic skills
	public static final int SKILL_CUBIC_HEAL = 4051;
	public static final int SKILL_CUBIC_CURE = 5579;
	
	/** A player, or a fake player (an Npc): see {@link #getOwner()}. */
	private final Creature _owner;
	private Creature _target;
	
	private final int _cubicId;
	private final int _cubicPower;
	private final int _cubicDelay;
	private final int _cubicSkillChance;
	private final int _cubicMaxCount;
	private final boolean _givenByOther;
	
	private final List<Skill> _skills = new ArrayList<>();
	
	private Future<?> _disappearTask;
	private Future<?> _actionTask;
	
	public Cubic(Creature owner, int cubicId, int level, int cubicPower, int cubicDelay, int cubicSkillChance, int cubicMaxCount, int cubicDuration, boolean givenByOther)
	{
		_owner = owner;
		_cubicId = cubicId;
		_cubicPower = cubicPower;
		_cubicDelay = cubicDelay * 1000;
		_cubicSkillChance = cubicSkillChance;
		_cubicMaxCount = cubicMaxCount;
		_givenByOther = givenByOther;
		
		switch (_cubicId)
		{
			case STORM_CUBIC:
			{
				_skills.add(SkillData.getInstance().getSkill(4049, level));
				break;
			}
			case VAMPIRIC_CUBIC:
			{
				_skills.add(SkillData.getInstance().getSkill(4050, level));
				break;
			}
			case LIFE_CUBIC:
			{
				_skills.add(SkillData.getInstance().getSkill(4051, level));
				doAction();
				break;
			}
			case VIPER_CUBIC:
			{
				_skills.add(SkillData.getInstance().getSkill(4052, level));
				break;
			}
			case POLTERGEIST_CUBIC:
			{
				_skills.add(SkillData.getInstance().getSkill(4053, level));
				_skills.add(SkillData.getInstance().getSkill(4054, level));
				_skills.add(SkillData.getInstance().getSkill(4055, level));
				break;
			}
			case BINDING_CUBIC:
			{
				_skills.add(SkillData.getInstance().getSkill(4164, level));
				break;
			}
			case AQUA_CUBIC:
			{
				_skills.add(SkillData.getInstance().getSkill(4165, level));
				break;
			}
			case SPARK_CUBIC:
			{
				_skills.add(SkillData.getInstance().getSkill(4166, level));
				break;
			}
			case ATTRACT_CUBIC:
			{
				_skills.add(SkillData.getInstance().getSkill(5115, level));
				_skills.add(SkillData.getInstance().getSkill(5116, level));
				break;
			}
			case SMART_CUBIC_ARCANALORD:
			{
				_skills.add(SkillData.getInstance().getSkill(4051, 7));
				_skills.add(SkillData.getInstance().getSkill(4165, 9));
				break;
			}
			case SMART_CUBIC_ELEMENTALMASTER:
			{
				_skills.add(SkillData.getInstance().getSkill(4049, 8));
				_skills.add(SkillData.getInstance().getSkill(4166, 9));
				break;
			}
			case SMART_CUBIC_SPECTRALMASTER:
			{
				_skills.add(SkillData.getInstance().getSkill(4049, 8));
				_skills.add(SkillData.getInstance().getSkill(4052, 6));
				break;
			}
			case SMART_CUBIC_EVATEMPLAR:
			{
				_skills.add(SkillData.getInstance().getSkill(4053, 8));
				_skills.add(SkillData.getInstance().getSkill(4165, 9));
				break;
			}
			case SMART_CUBIC_SHILLIENTEMPLAR:
			{
				_skills.add(SkillData.getInstance().getSkill(4049, 8));
				_skills.add(SkillData.getInstance().getSkill(5115, 4));
				break;
			}
		}
		
		_disappearTask = ThreadPool.schedule(new CubicDisappear(this), cubicDuration * 1000); // disappear
	}
	
	public void doAction()
	{
		if (_actionTask == null)
		{
			synchronized (this)
			{
				if (_actionTask == null)
				{
					switch (_cubicId)
					{
						case AQUA_CUBIC:
						case BINDING_CUBIC:
						case SPARK_CUBIC:
						case STORM_CUBIC:
						case POLTERGEIST_CUBIC:
						case VAMPIRIC_CUBIC:
						case VIPER_CUBIC:
						case ATTRACT_CUBIC:
						case SMART_CUBIC_ARCANALORD:
						case SMART_CUBIC_ELEMENTALMASTER:
						case SMART_CUBIC_SPECTRALMASTER:
						case SMART_CUBIC_EVATEMPLAR:
						case SMART_CUBIC_SHILLIENTEMPLAR:
						{
							_actionTask = ThreadPool.scheduleAtFixedRate(new CubicAction(this, _cubicSkillChance), 0, _cubicDelay);
							break;
						}
						case LIFE_CUBIC:
						{
							_actionTask = ThreadPool.scheduleAtFixedRate(new CubicHeal(this), 0, _cubicDelay);
							break;
						}
					}
				}
			}
		}
	}
	
	public int getId()
	{
		return _cubicId;
	}
	
	/**
	 * @return the creature it belongs to: a player, or a fake player (an Npc with a fake player profile)
	 */
	public Creature getOwner()
	{
		return _owner;
	}
	
	/**
	 * @return {@code true} if its owner is gone: a player offline, a fake player despawned, or either dead
	 */
	public boolean isOwnerGone()
	{
		return _owner.isDead() || (_owner.isPlayer() ? !_owner.asPlayer().isOnline() : !_owner.isSpawned());
	}
	
	/**
	 * Shows its owner's cubics again, after one appeared or went away.
	 */
	public void refreshOwner()
	{
		if (_owner.isPlayer())
		{
			_owner.asPlayer().broadcastUserInfo();
		}
		else
		{
			_owner.broadcastInfo();
		}
	}
	
	public int getCubicPower()
	{
		return _cubicPower;
	}
	
	public Creature getTarget()
	{
		return _target;
	}
	
	public void setTarget(Creature target)
	{
		_target = target;
	}
	
	public List<Skill> getSkills()
	{
		return _skills;
	}
	
	public int getCubicMaxCount()
	{
		return _cubicMaxCount;
	}
	
	public void stopAction()
	{
		_target = null;
		if (_actionTask != null)
		{
			_actionTask.cancel(true);
			_actionTask = null;
		}
	}
	
	public void cancelDisappear()
	{
		if (_disappearTask != null)
		{
			_disappearTask.cancel(true);
			_disappearTask = null;
		}
	}
	
	/** this sets the enemy target for a cubic */
	public void getCubicTarget()
	{
		try
		{
			_target = null;
			final WorldObject ownerTarget = _owner.getTarget();
			if (ownerTarget == null)
			{
				return;
			}
			
			// A fake player's cubic hits what it fights: its target, if that is another living creature not of its team or party.
			if (!_owner.isPlayer())
			{
				if (ownerTarget.isCreature() && (ownerTarget != _owner) && !ownerTarget.asCreature().isDead() && ((_owner.getTeam() == Team.NONE) || (_owner.getTeam() != ownerTarget.asCreature().getTeam())) && !(_owner.isInParty() && ownerTarget.isPlayer() && _owner.getParty().containsPlayer(ownerTarget.asPlayer())))
				{
					_target = ownerTarget.asCreature();
				}
				return;
			}
			
			final Player owner = _owner.asPlayer();
			
			// Custom event targeting
			if (owner.isOnEvent())
			{
				final Player target = ownerTarget.asPlayer();
				if ((target != null) && ((owner.getTeam() != target.getTeam()) || owner.isOnSoloEvent()) && !(target.isDead()))
				{
					_target = ownerTarget.asCreature();
				}
				return;
			}
			
			// Duel targeting
			if (owner.isInDuel())
			{
				final Player playerA = DuelManager.getInstance().getDuel(owner.getDuelId()).getPlayerA();
				final Player playerB = DuelManager.getInstance().getDuel(owner.getDuelId()).getPlayerB();
				if (DuelManager.getInstance().getDuel(owner.getDuelId()).isPartyDuel())
				{
					final Party partyA = playerA.getParty();
					final Party partyB = playerB.getParty();
					Party partyEnemy = null;
					if (partyA != null)
					{
						if (partyA.getMembers().contains(owner))
						{
							if (partyB != null)
							{
								partyEnemy = partyB;
							}
							else
							{
								_target = playerB;
							}
						}
						else
						{
							partyEnemy = partyA;
						}
					}
					else
					{
						if (playerA == owner)
						{
							if (partyB != null)
							{
								partyEnemy = partyB;
							}
							else
							{
								_target = playerB;
							}
						}
						else
						{
							_target = playerA;
						}
					}
					
					if (((_target == playerA) || (_target == playerB)) && (_target == ownerTarget))
					{
						return;
					}
					
					if (partyEnemy != null)
					{
						if (partyEnemy.getMembers().contains(ownerTarget))
						{
							_target = ownerTarget.asCreature();
						}
						return;
					}
				}
				
				if ((playerA != owner) && (ownerTarget == playerA))
				{
					_target = playerA;
					return;
				}
				
				if ((playerB != owner) && (ownerTarget == playerB))
				{
					_target = playerB;
					return;
				}
				
				_target = null;
				return;
			}
			
			// Olympiad targeting
			if (owner.isInOlympiadMode())
			{
				if (owner.isOlympiadStart() && ownerTarget.isPlayable())
				{
					final Player targetPlayer = ownerTarget.asPlayer();
					if ((targetPlayer != null) && (targetPlayer.getOlympiadGameId() == owner.getOlympiadGameId()) && (targetPlayer.getOlympiadSide() != owner.getOlympiadSide()))
					{
						_target = ownerTarget.asCreature();
					}
				}
				return;
			}
			
			// test owners target if it is valid then use it
			if (ownerTarget.isCreature() && (ownerTarget != owner.getSummon()) && (ownerTarget != owner))
			{
				// target mob which has aggro on you or your summon
				if (ownerTarget.isAttackable())
				{
					final Attackable attackable = ownerTarget.asAttackable();
					if (attackable.isInAggroList(owner) && !attackable.isDead())
					{
						_target = ownerTarget.asCreature();
						return;
					}
					
					if (owner.hasSummon() && attackable.isInAggroList(owner.getSummon()) && !attackable.isDead())
					{
						_target = ownerTarget.asCreature();
						return;
					}
				}
				
				// get target in pvp or in siege
				Player enemy = null;
				if (((owner.getPvpFlag() > 0) && !owner.isInsideZone(ZoneId.PEACE)) || owner.isInsideZone(ZoneId.PVP))
				{
					if (!ownerTarget.asCreature().isDead())
					{
						enemy = ownerTarget.asPlayer();
					}
					
					if (enemy != null)
					{
						boolean targetIt = true;
						if (owner.getParty() != null)
						{
							if (owner.getParty().getMembers().contains(enemy))
							{
								targetIt = false;
							}
							else if ((owner.getParty().getCommandChannel() != null) && owner.getParty().getCommandChannel().getMembers().contains(enemy))
							{
								targetIt = false;
							}
						}
						
						if ((owner.getClan() != null) && !owner.isInsideZone(ZoneId.PVP))
						{
							if (owner.getClan().isMember(enemy.getObjectId()))
							{
								targetIt = false;
							}
							
							if ((owner.getAllyId() > 0) && (enemy.getAllyId() > 0) && (owner.getAllyId() == enemy.getAllyId()))
							{
								targetIt = false;
							}
						}
						
						if ((enemy.getPvpFlag() == 0) && !enemy.isInsideZone(ZoneId.PVP))
						{
							targetIt = false;
						}
						
						if (enemy.isInsideZone(ZoneId.PEACE))
						{
							targetIt = false;
						}
						
						if ((owner.getSiegeState() > 0) && (owner.getSiegeState() == enemy.getSiegeState()))
						{
							targetIt = false;
						}
						
						if (!enemy.isSpawned())
						{
							targetIt = false;
						}
						
						if (targetIt)
						{
							_target = enemy;
						}
					}
				}
			}
		}
		catch (Exception e)
		{
			LOGGER.log(Level.SEVERE, "", e);
		}
	}
	
	public void useCubicContinuous(Skill skill, List<Creature> targets)
	{
		for (Creature target : targets)
		{
			if ((target == null) || target.isDead())
			{
				continue;
			}
			
			if (skill.hasNegativeEffect())
			{
				final byte shld = Formulas.calcShldUse(_owner, target, skill);
				final boolean acted = Formulas.calcCubicSkillSuccess(this, target, skill, shld);
				if (!acted)
				{
					_owner.sendPacket(SystemMessageId.YOUR_ATTACK_HAS_FAILED);
					continue;
				}
			}
			
			// Apply effects
			skill.applyEffects(_owner, target, false, false, true, 0);
			
			// If this is a negative effect skill notify the duel manager, so it can be removed after the duel (player & target must be in the same duel).
			if (target.isPlayer())
			{
				final Player player = target.asPlayer();
				if (player.isInDuel() && skill.hasNegativeEffect() && _owner.isPlayer() && (_owner.asPlayer().getDuelId() == player.getDuelId()))
				{
					DuelManager.getInstance().onBuff(player, skill);
				}
			}
		}
	}
	
	/**
	 * @param skill
	 * @param targets
	 */
	public void useCubicMdam(Skill skill, List<Creature> targets)
	{
		for (Creature target : targets)
		{
			if (target == null)
			{
				continue;
			}
			
			if (target.isAlikeDead())
			{
				if (target.isPlayer() && PlayerConfig.FAKE_DEATH_DAMAGE_STAND)
				{
					target.stopFakeDeath(true);
				}
				else
				{
					continue;
				}
			}
			
			final boolean mcrit = Formulas.calcMCrit(_owner.getMCriticalHit(target, skill));
			final byte shld = Formulas.calcShldUse(_owner, target, skill);
			int damage = (int) Formulas.calcMagicDam(this, target, skill, mcrit, shld);
			if (damage > 0)
			{
				// Manage attack or cast break of the target (calculating rate, sending message...)
				if (!target.isRaid() && Formulas.calcAtkBreak(target, damage))
				{
					target.breakAttack();
					target.breakCast();
				}
				
				// Shield Deflect Magic: If target is reflecting the skill then no damage is done.
				if (target.getStat().calcStat(Stat.VENGEANCE_SKILL_MAGIC_DAMAGE, 0, target, skill) > Rnd.get(100))
				{
					damage = 0;
				}
				else
				{
					// The cubic's hits use none of the owner's shots.
					try (CombatInfo.Scope _ = CombatInfo.scope(CombatInfo.KIND_SKILL, skill, 0))
					{
						_owner.sendDamageMessage(target, skill, damage, mcrit, false, false);
						target.reduceCurrentHp(damage, _owner, skill);
					}
				}
			}
		}
	}
	
	public void useCubicDrain(Skill skill, List<Creature> targets)
	{
		for (Creature target : targets)
		{
			if (target.isAlikeDead())
			{
				continue;
			}
			
			final boolean mcrit = Formulas.calcMCrit(_owner.getMCriticalHit(target, skill));
			final byte shld = Formulas.calcShldUse(_owner, target, skill);
			final int damage = (int) Formulas.calcMagicDam(this, target, skill, mcrit, shld);
			
			// TODO: Unhardcode fixed value
			final double hpAdd = (0.4 * damage * Totems.getLeechRate(target));
			final Creature owner = _owner;
			final double hp = ((owner.getCurrentHp() + hpAdd) > owner.getMaxHp() ? owner.getMaxHp() : (owner.getCurrentHp() + hpAdd));
			owner.setCurrentHp(hp);
			
			// Check to see if we should damage the target
			if ((damage > 0) && !target.isDead())
			{
				// The cubic's hits use none of the owner's shots.
				try (CombatInfo.Scope _ = CombatInfo.scope(CombatInfo.KIND_SKILL, skill, 0))
				{
					target.reduceCurrentHp(damage, _owner, skill);
					
					// Manage attack or cast break of the target (calculating rate, sending message...)
					if (!target.isRaid() && Formulas.calcAtkBreak(target, damage))
					{
						target.breakAttack();
						target.breakCast();
					}
					
					owner.sendDamageMessage(target, skill, damage, mcrit, false, false);
				}
			}
		}
	}
	
	public void useCubicDisabler(Skill skill, List<Creature> targets)
	{
		for (Creature target : targets)
		{
			if ((target == null) || target.isDead())
			{
				continue;
			}
			
			final byte shld = Formulas.calcShldUse(_owner, target, skill);
			if (skill.hasEffectType(EffectType.STUN, EffectType.PARALYZE, EffectType.ROOT) && Formulas.calcCubicSkillSuccess(this, target, skill, shld))
			{
				// Apply effects
				skill.applyEffects(_owner, target, false, false, true, 0);
				
				// If this is a negative effect skill notify the duel manager, so it can be removed after the duel (player & target must be in the same duel).
				if (target.isPlayer())
				{
					final Player player = target.asPlayer();
					if (player.isInDuel() && skill.hasNegativeEffect() && _owner.isPlayer() && (_owner.asPlayer().getDuelId() == player.getDuelId()))
					{
						DuelManager.getInstance().onBuff(player, skill);
					}
				}
			}
			
			if (skill.hasEffectType(EffectType.AGGRESSION) && Formulas.calcCubicSkillSuccess(this, target, skill, shld))
			{
				if (target.isAttackable())
				{
					target.getAI().notifyAction(Action.AGGRESSION, _owner, (int) ((150 * skill.getPower()) / (target.getLevel() + 7)));
				}
				
				// Apply effects
				skill.applyEffects(_owner, target, false, false, true, 0);
			}
		}
	}
	
	/**
	 * @param owner
	 * @param target
	 * @return true if the target is inside of the owner's max Cubic range
	 */
	public static boolean isInCubicRange(Creature owner, Creature target)
	{
		if ((owner == null) || (target == null))
		{
			return false;
		}
		
		int x;
		int y;
		int z;
		
		// temporary range check until real behavior of cubics is known/coded
		final int range = MAX_MAGIC_RANGE;
		x = (owner.getX() - target.getX());
		y = (owner.getY() - target.getY());
		z = (owner.getZ() - target.getZ());
		return (((x * x) + (y * y) + (z * z)) <= (range * range));
	}
	
	/** this sets the friendly target for a cubic */
	public void cubicTargetForHeal()
	{
		Creature target = null;
		double percentleft = 100.0;
		
		// A fake player's life cubic heals the fake player.
		if (!_owner.isPlayer())
		{
			_target = _owner.getCurrentHp() < _owner.getMaxHp() ? _owner : null;
			return;
		}
		
		final Player owner = _owner.asPlayer();
		Party party = owner.getParty();
		
		// if owner is in a duel but not in a party duel, then it is the same as he does not have a party
		if (owner.isInDuel() && !DuelManager.getInstance().getDuel(owner.getDuelId()).isPartyDuel())
		{
			party = null;
		}
		
		if ((party != null) && !owner.isInOlympiadMode())
		{
			// Get all visible objects in a spheric area near the Creature
			// Get a list of Party Members
			for (Creature partyMember : party.getMembers())
			{
				// if party member not dead, check if he is in cast range of heal cubic and member is in cubic casting range, check if he need heal and if he have the lowest HP
				if (!partyMember.isDead() && isInCubicRange(owner, partyMember) && (partyMember.getCurrentHp() < partyMember.getMaxHp()) && (percentleft > (partyMember.getCurrentHp() / partyMember.getMaxHp())))
				{
					percentleft = (partyMember.getCurrentHp() / partyMember.getMaxHp());
					target = partyMember;
				}
				
				final Player player = partyMember.asPlayer();
				if (player != null)
				{
					final Summon summon = player.getSummon();
					if (summon != null)
					{
						if (summon.isDead())
						{
							continue;
						}
						
						// If party member's pet not dead, check if it is in cast range of heal cubic.
						if (!isInCubicRange(owner, summon))
						{
							continue;
						}
						
						// member's pet is in cubic casting range, check if he need heal and if he have the lowest HP
						if ((summon.getCurrentHp() < summon.getMaxHp()) && (percentleft > (summon.getCurrentHp() / summon.getMaxHp())))
						{
							percentleft = (summon.getCurrentHp() / summon.getMaxHp());
							target = summon;
						}
					}
				}
			}
		}
		else
		{
			if (owner.getCurrentHp() < owner.getMaxHp())
			{
				percentleft = (owner.getCurrentHp() / owner.getMaxHp());
				target = owner;
			}
			
			if (owner.hasSummon() && !owner.getSummon().isDead() && (owner.getSummon().getCurrentHp() < owner.getSummon().getMaxHp()) && (percentleft > (owner.getSummon().getCurrentHp() / owner.getSummon().getMaxHp())) && isInCubicRange(owner, owner.getSummon()))
			{
				target = owner.getSummon();
			}
		}
		
		_target = target;
	}
	
	public boolean givenByOther()
	{
		return _givenByOther;
	}
}
