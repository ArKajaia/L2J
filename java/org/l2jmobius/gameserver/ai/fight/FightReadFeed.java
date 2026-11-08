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
package org.l2jmobius.gameserver.ai.fight;

import java.util.Collection;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import org.l2jmobius.commons.threads.ThreadPool;
import org.l2jmobius.gameserver.model.actor.Attackable;
import org.l2jmobius.gameserver.model.actor.Creature;
import org.l2jmobius.gameserver.model.actor.Player;
import org.l2jmobius.gameserver.model.actor.enums.creature.Race;
import org.l2jmobius.gameserver.model.actor.holders.npc.FakePlayerPvpBuild.Role;
import org.l2jmobius.gameserver.model.actor.holders.npc.FakePlayerPvpPersonality;
import org.l2jmobius.gameserver.model.actor.holders.npc.FakePlayerPvpProfile;
import org.l2jmobius.gameserver.model.events.EventType;
import org.l2jmobius.gameserver.model.events.holders.actor.creature.OnCreatureSkillUse;
import org.l2jmobius.gameserver.model.events.listeners.ConsumerEventListener;
import org.l2jmobius.gameserver.model.item.enums.ShotType;
import org.l2jmobius.gameserver.model.item.instance.Item;
import org.l2jmobius.gameserver.model.item.type.ArmorType;
import org.l2jmobius.gameserver.model.itemcontainer.Inventory;
import org.l2jmobius.gameserver.model.skill.BuffInfo;
import org.l2jmobius.gameserver.model.skill.Skill;
import org.l2jmobius.gameserver.model.stats.Formulas;

/**
 * Feeds a fake player's {@link FightRead} from the server: the cast bars of its enemies (a skill use listener on each enemy of its fight), the icons on itself and its target, and HP. It adds listeners as enemies come into the fight and removes them as they leave,
 * and all of them when the fight ends or the fake player dies.
 */
public class FightReadFeed
{
	/** After a crowd control skill lands, how long it waits to see whether the target got it. */
	private static final long CC_CHECK_DELAY = 300;
	private static final Set<Integer> INVULNERABLE_SKILLS = Set.of(442, 443, 1418);
	private static final Set<Integer> REFLECT_SKILLS = Set.of(351, 916, 6282);
	/** Tenacity's buff (passive tree keystone): no stun, sleep or root for a moment. */
	private static final int TENACITY_SKILL = 27479;
	
	private final Attackable _self;
	private final FightRead _read;
	/** Object id -> the enemy it listens to. */
	private final Map<Integer, Creature> _listened = new HashMap<>();
	
	/**
	 * @param self the fake player
	 * @param personality its personality (its knowledge sets how much it notices)
	 */
	public FightReadFeed(Attackable self, FakePlayerPvpPersonality personality)
	{
		_self = self;
		_read = new FightRead(personality != null ? personality.getKnowledge() : 0, self.getObjectId());
	}
	
	public FightRead read()
	{
		return _read;
	}
	
	/**
	 * Brings the fight read up to date: listens to new enemies, forgets the ones that left, reads the icons on itself and its target, and samples HP.
	 * @param target its target, {@code null} out of a fight
	 * @param enemies the enemies of its fight (its target included or not)
	 * @param now the current time
	 */
	public synchronized void update(Creature target, Collection<Creature> enemies, long now)
	{
		final Set<Integer> wanted = new HashSet<>();
		for (Creature enemy : enemies)
		{
			if ((enemy != null) && !enemy.isDead())
			{
				wanted.add(enemy.getObjectId());
				listen(enemy);
			}
		}
		if ((target != null) && !target.isDead())
		{
			wanted.add(target.getObjectId());
			listen(target);
		}
		for (Integer id : new HashSet<>(_listened.keySet()))
		{
			if (!wanted.contains(id))
			{
				unlisten(id);
			}
		}
		
		_read.onEffects(_self.getObjectId(), meanings(_self, now), now);
		if (target != null)
		{
			_read.onEffects(target.getObjectId(), meanings(target, now), now);
			_read.onHp(now, _self.getObjectId(), _self.getCurrentHp(), _self.getMaxHp(), target.getObjectId(), target.getCurrentHp(), target.getMaxHp());
		}
	}
	
	/**
	 * Removes every listener it added (the fight is over, or the fake player died or left).
	 */
	public synchronized void close()
	{
		for (Integer id : new HashSet<>(_listened.keySet()))
		{
			unlisten(id);
		}
	}
	
	/**
	 * Its own crowd control skill was cast on a target: once it lands, notes whether the target got it.
	 * @param target the target
	 * @param skill the skill
	 */
	public void noteOwnCc(Creature target, Skill skill)
	{
		if (!SkillMeanings.classify(SkillFactsReader.of(skill)).contains(SkillMeaning.CC))
		{
			return;
		}
		ThreadPool.schedule(() ->
		{
			if (!target.isDead())
			{
				_read.onCcAttempt(target.getObjectId(), skill.getId(), target.isAffectedBySkill(skill.getId()));
			}
		}, castTime(_self, skill) + CC_CHECK_DELAY);
	}
	
	private void listen(Creature enemy)
	{
		if (_listened.putIfAbsent(enemy.getObjectId(), enemy) == null)
		{
			enemy.addListener(new ConsumerEventListener(enemy, EventType.ON_CREATURE_SKILL_USE, (OnCreatureSkillUse event) -> onEnemySkill(event), this));
		}
	}
	
	private void unlisten(int id)
	{
		final Creature enemy = _listened.remove(id);
		if (enemy != null)
		{
			enemy.removeListenerIf(EventType.ON_CREATURE_SKILL_USE, listener -> listener.getOwner() == this);
		}
		_read.forget(id);
	}
	
	private void onEnemySkill(OnCreatureSkillUse event)
	{
		// A fake player that died or left stops listening.
		if (_self.isDead() || !_self.isSpawned())
		{
			ThreadPool.execute(this::close);
			return;
		}
		final Creature caster = event.getCaster();
		final Skill skill = event.getSkill();
		if ((caster == null) || (skill == null) || skill.isPassive() || skill.isToggle())
		{
			return;
		}
		final Creature target = event.getTarget() instanceof Creature creature ? creature : null;
		final long now = System.currentTimeMillis();
		_read.onEnemyCast(caster.getObjectId(), SkillFactsReader.of(skill), target != null ? target.getObjectId() : 0, now, now + castTime(caster, skill));
	}
	
	/**
	 * @param caster who casts
	 * @param skill the skill
	 * @return how long its cast bar runs, as {@link Creature#doCast} works it out
	 */
	public static int castTime(Creature caster, Skill skill)
	{
		final int base = skill.getHitTime() + skill.getCoolTime();
		int time = base;
		if (!skill.isChanneling() || (skill.getChannelingSkillId() == 0))
		{
			if (!skill.isStatic())
			{
				time = Formulas.calcAtkSpd(caster, skill, time);
			}
			if (skill.isMagic() && (caster.isChargedShot(ShotType.SPIRITSHOTS) || caster.isChargedShot(ShotType.BLESSED_SPIRITSHOTS)))
			{
				time = (int) (0.6 * time);
			}
		}
		if (skill.isMagic() && (base > 550) && (time < 550))
		{
			time = 550;
		}
		else if (!skill.isStatic() && (base >= 500) && (time < 500))
		{
			time = 500;
		}
		return time;
	}
	
	/**
	 * @param creature a creature
	 * @param now the current time
	 * @return the meanings of the icons on it and when each ends
	 */
	private static Map<EffectMeaning, Long> meanings(Creature creature, long now)
	{
		final Map<EffectMeaning, Long> meanings = new EnumMap<>(EffectMeaning.class);
		for (BuffInfo info : creature.getEffectList().getEffects())
		{
			final Skill skill = info.getSkill();
			if (skill == null)
			{
				continue;
			}
			final long end = now + (Math.max(0, info.getTime()) * 1000L);
			final SkillFacts facts = SkillFactsReader.of(skill);
			final String abnormal = facts.abnormalType();
			if ("INVINCIBILITY".equals(abnormal) || INVULNERABLE_SKILLS.contains(skill.getId()))
			{
				meanings.merge(EffectMeaning.INVULNERABLE, end, Math::max);
			}
			else if (REFLECT_SKILLS.contains(skill.getId()))
			{
				meanings.merge(EffectMeaning.REFLECT, end, Math::max);
			}
			else if (SkillMeanings.DEFENSIVE_SKILL_IDS.contains(skill.getId()))
			{
				meanings.merge(EffectMeaning.DEFENDING, end, Math::max);
			}
			if ("RESIST_SHOCK".equals(abnormal) || (skill.getId() == TENACITY_SKILL))
			{
				meanings.merge(EffectMeaning.CC_IMMUNE, end, Math::max);
			}
			if ("STUN".equals(abnormal) || "PARALYZE".equals(abnormal) || "TURN_STONE".equals(abnormal) || facts.effectTypes().contains("STUN") || facts.effectTypes().contains("PARALYZE"))
			{
				meanings.merge(EffectMeaning.STUNNED, end, Math::max);
			}
			if (abnormal.startsWith("ROOT") || facts.effectTypes().contains("ROOT"))
			{
				meanings.merge(EffectMeaning.ROOTED, end, Math::max);
			}
			if ("SLEEP".equals(abnormal) || facts.effectTypes().contains("SLEEP"))
			{
				meanings.merge(EffectMeaning.SLEEPING, end, Math::max);
			}
			if (abnormal.startsWith("SILENCE") || facts.effectTypes().contains("MUTE"))
			{
				meanings.merge(EffectMeaning.SILENCED, end, Math::max);
			}
			if ("SPEED_DOWN".equals(abnormal))
			{
				meanings.merge(EffectMeaning.SLOWED, end, Math::max);
			}
			if (SkillMeanings.classify(facts).contains(SkillMeaning.BURST_BUFF))
			{
				meanings.merge(EffectMeaning.BURSTING, end, Math::max);
			}
		}
		return meanings;
	}
	
	/**
	 * @param creature an enemy
	 * @return the rough profile of its resistances that a player guesses from its class
	 */
	public static ClassGroup groupOf(Creature creature)
	{
		if (creature instanceof Player player)
		{
			if (player.isMageClass())
			{
				return ClassGroup.ROBE_MAGE;
			}
			if (player.getRace() == Race.KAMAEL)
			{
				return ClassGroup.LIGHT_FIGHTER;
			}
			final Item chest = player.getInventory().getPaperdollItem(Inventory.PAPERDOLL_CHEST);
			return (chest != null) && (chest.getItemType() == ArmorType.HEAVY) ? ClassGroup.HEAVY_FIGHTER : ClassGroup.LIGHT_FIGHTER;
		}
		final FakePlayerPvpProfile profile = creature.isNpc() ? creature.asNpc().getTemplate().getFakePlayerPvpProfile() : null;
		if (profile != null)
		{
			final Role role = profile.getRole();
			if (role == Role.MAGE)
			{
				return ClassGroup.ROBE_MAGE;
			}
			if ((role == Role.ARCHER) || (role == Role.DAGGER) || (creature.getRace() == Race.KAMAEL))
			{
				return ClassGroup.LIGHT_FIGHTER;
			}
			return ClassGroup.HEAVY_FIGHTER;
		}
		return ClassGroup.HEAVY_FIGHTER;
	}
}
