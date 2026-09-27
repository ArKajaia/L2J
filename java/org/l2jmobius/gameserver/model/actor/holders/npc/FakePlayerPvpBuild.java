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
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import org.l2jmobius.gameserver.model.actor.enums.player.PlayerClass;

/**
 * A class a roaming fake player can be (see data/FakePlayerPvp.xml): its final class, how it fights, which gear kits it wears and which of its class skills it uses.
 */
public class FakePlayerPvpBuild
{
	/**
	 * How a build fights.
	 */
	public enum Role
	{
		/** Melee damage dealer. */
		FIGHTER,
		/** Melee with a shield, heals itself. */
		TANK,
		/** Melee blow skills. */
		DAGGER,
		/** Shoots from range and steps back from melee. */
		ARCHER,
		/** Casts from range and steps back from melee. */
		MAGE;
		
		public boolean isRanged()
		{
			return (this == ARCHER) || (this == MAGE);
		}
	}
	
	/**
	 * The skill lists of a build.
	 */
	public enum SkillCategory
	{
		ATTACK,
		DEBUFF,
		BUFF,
		HEAL,
		EMERGENCY,
		TOGGLE,
		/** Skills that build Sonic/Force energy (Sonic Focus, Maximum Focus Sonic...). */
		CHARGE,
		/** Speed buffs used to catch a player that runs away, or to run away (Dash, Sprint, Sonic Move). */
		MOVE,
		/** Gap closers used on a target out of reach (Rush, Shadow Step). */
		RUSH,
		/** Skills that stop a player in melee range before stepping back or running away (roots, stuns, Aura Flash, Trick). */
		PEEL,
		/** Skills that remove what holds it back (Break Duress against roots, Remedy against bleeding...). */
		CLEANSE
	}
	
	private final String _name;
	private final PlayerClass _playerClass;
	private final Role _role;
	private final String _weaponKit;
	private final String _armorKit;
	private final String _jewelKit;
	private final String _bowKit;
	private final String _buffList;
	private final int _weight;
	private final boolean _skillFighter;
	private final Map<SkillCategory, List<int[]>> _skills = new EnumMap<>(SkillCategory.class);
	private final List<FakePlayerPvpCombo> _combos = new ArrayList<>();
	
	public FakePlayerPvpBuild(String name, PlayerClass playerClass, Role role, String weaponKit, String armorKit, String jewelKit, String bowKit, String buffList, int weight, boolean skillFighter)
	{
		_name = name;
		_playerClass = playerClass;
		_role = role;
		_weaponKit = weaponKit;
		_armorKit = armorKit;
		_jewelKit = jewelKit;
		_bowKit = bowKit;
		_buffList = buffList;
		_weight = weight;
		_skillFighter = skillFighter;
	}
	
	/**
	 * @param category the list to add to
	 * @param alternatives skill ids of which only the first one the fake player has learned is used
	 */
	public void addSkill(SkillCategory category, int[] alternatives)
	{
		_skills.computeIfAbsent(category, _ -> new ArrayList<>()).add(alternatives);
	}
	
	/**
	 * @param category the list to get
	 * @return the skill entries of {@code category}, each one a list of alternative skill ids
	 */
	public List<int[]> getSkills(SkillCategory category)
	{
		return _skills.getOrDefault(category, Collections.emptyList());
	}
	
	public void addCombo(FakePlayerPvpCombo combo)
	{
		_combos.add(combo);
	}
	
	/**
	 * @return its combos, most preferred first
	 */
	public List<FakePlayerPvpCombo> getCombos()
	{
		return _combos;
	}
	
	public String getName()
	{
		return _name;
	}
	
	/**
	 * @return the final (3rd) class of this build
	 */
	public PlayerClass getPlayerClass()
	{
		return _playerClass;
	}
	
	/**
	 * @param level a character level
	 * @return the class of this build a character of {@code level} has: base class below 20, 1st class below 40, 2nd class below 76, 3rd class from 76
	 */
	public PlayerClass getPlayerClass(int level)
	{
		final int classLevel = level >= 76 ? 3 : level >= 40 ? 2 : level >= 20 ? 1 : 0;
		PlayerClass playerClass = _playerClass;
		while ((playerClass.level() > classLevel) && (playerClass.getParent() != null))
		{
			playerClass = playerClass.getParent();
		}
		
		return playerClass;
	}
	
	public Role getRole()
	{
		return _role;
	}
	
	public String getWeaponKit()
	{
		return _weaponKit;
	}
	
	public String getArmorKit()
	{
		return _armorKit;
	}
	
	public String getJewelKit()
	{
		return _jewelKit;
	}
	
	/**
	 * @return the kit of the bow it carries besides its weapon and takes out against a player it can't catch, {@code null} for none
	 */
	public String getBowKit()
	{
		return _bowKit;
	}
	
	/**
	 * @return the name of the buff list it keeps up (see data/FakePlayerPvp.xml)
	 */
	public String getBuffList()
	{
		return _buffList;
	}
	
	public int getWeight()
	{
		return _weight;
	}
	
	/**
	 * @return {@code true} for a class that deals its damage with skills (Gladiator/Duelist, Tyrant, daggers), {@code false} for one whose damage is its normal attack and uses skills now and then (tanks, archers, most warriors)
	 */
	public boolean isSkillFighter()
	{
		return _skillFighter;
	}
}
