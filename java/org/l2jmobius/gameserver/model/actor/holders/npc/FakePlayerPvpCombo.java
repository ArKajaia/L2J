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
package org.l2jmobius.gameserver.model.actor.holders.npc;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import org.l2jmobius.gameserver.data.xml.SkillData;
import org.l2jmobius.gameserver.model.skill.Skill;

/**
 * A skill chain a roaming fake player plays like a practiced player would (see data/FakePlayerPvp.xml), e.g. a dagger's Bluff, then Backstab from behind, then Lethal Blow.
 */
public class FakePlayerPvpCombo
{
	/**
	 * The weapon a combo is played with, for a build that carries a magic weapon besides its own (hybrid Soul Hounds).
	 */
	public enum Stance
	{
		/** With whatever it holds. */
		ANY,
		/** Only with its own weapon out (the rapier skills). */
		MAIN,
		/** Only with its magic weapon out (the nukes and debuffs it casts from a distance). */
		CASTER
	}
	
	/**
	 * One skill of a combo.
	 */
	public static class Step
	{
		private final int[] _skillIds;
		private final boolean _optional;
		private final boolean _self;
		private final boolean _behind;
		private final boolean _disabledTarget;
		private final boolean _mageTarget;
		
		/**
		 * @param skillIds alternatives, the first one the fake player has learned is used
		 * @param optional skipped when it can't be used right now, instead of waiting for it
		 * @param self cast on itself (a buff before the burst)
		 * @param behind get behind the target before using it (blows)
		 * @param disabledTarget only used while the target is stunned, rooted, asleep or paralyzed
		 * @param mageTarget only used against a target that fights with magic (a silence is wasted on a warrior)
		 */
		public Step(int[] skillIds, boolean optional, boolean self, boolean behind, boolean disabledTarget, boolean mageTarget)
		{
			_skillIds = skillIds;
			_optional = optional;
			_self = self;
			_behind = behind;
			_disabledTarget = disabledTarget;
			_mageTarget = mageTarget;
		}
		
		public int[] getSkillIds()
		{
			return _skillIds;
		}
		
		public boolean isOptional()
		{
			return _optional;
		}
		
		public boolean isSelf()
		{
			return _self;
		}
		
		public boolean isBehind()
		{
			return _behind;
		}
		
		public boolean needsDisabledTarget()
		{
			return _disabledTarget;
		}
		
		public boolean needsMageTarget()
		{
			return _mageTarget;
		}
	}
	
	/**
	 * A combo resolved for one fake player: every step with the skill (at the level) it has learned, {@code null} for an optional step it hasn't.
	 */
	public static class Chain
	{
		private final FakePlayerPvpCombo _combo;
		private final List<Skill> _skills;
		
		protected Chain(FakePlayerPvpCombo combo, List<Skill> skills)
		{
			_combo = combo;
			_skills = skills;
		}
		
		public String getName()
		{
			return _combo.getName();
		}
		
		public boolean isPvp()
		{
			return _combo.isPvp();
		}
		
		public int getChance()
		{
			return _combo.getChance();
		}
		
		public Stance getStance()
		{
			return _combo.getStance();
		}
		
		public int size()
		{
			return _skills.size();
		}
		
		public Step getStep(int index)
		{
			return _combo.getSteps().get(index);
		}
		
		/**
		 * @param index the step index
		 * @return the skill of that step, {@code null} if the fake player doesn't have it (optional step)
		 */
		public Skill getSkill(int index)
		{
			return _skills.get(index);
		}
	}
	
	private final String _name;
	private final boolean _pvp;
	private final int _chance;
	private final Stance _stance;
	private final List<Step> _steps = new ArrayList<>();
	
	/**
	 * @param name a name, for reading the data file
	 * @param pvp only used against players
	 * @param chance the chance (in %) to start it whenever it is ready
	 * @param stance the weapon it is played with
	 */
	public FakePlayerPvpCombo(String name, boolean pvp, int chance, Stance stance)
	{
		_name = name;
		_pvp = pvp;
		_chance = chance;
		_stance = stance;
	}
	
	public void addStep(Step step)
	{
		_steps.add(step);
	}
	
	public String getName()
	{
		return _name;
	}
	
	public boolean isPvp()
	{
		return _pvp;
	}
	
	public int getChance()
	{
		return _chance;
	}
	
	public Stance getStance()
	{
		return _stance;
	}
	
	public List<Step> getSteps()
	{
		return Collections.unmodifiableList(_steps);
	}
	
	/**
	 * @param learned skill id to skill level the fake player has learned
	 * @return the combo with its skills, or {@code null} if one of its required steps isn't learned yet
	 */
	public Chain resolve(Map<Integer, Integer> learned)
	{
		final List<Skill> skills = new ArrayList<>(_steps.size());
		for (Step step : _steps)
		{
			Skill skill = null;
			for (int skillId : step.getSkillIds())
			{
				final Integer skillLevel = learned.get(skillId);
				if (skillLevel != null)
				{
					skill = SkillData.getInstance().getSkill(skillId, skillLevel);
					break;
				}
			}
			
			if ((skill == null) && !step.isOptional())
			{
				return null;
			}
			skills.add(skill);
		}
		
		return new Chain(this, skills);
	}
}
