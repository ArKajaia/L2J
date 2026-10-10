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
		/** Spoil skills (Spoil, Spoil Crush): a spoiler spoils the monster it fights first, never a player. */
		SPOIL,
		BUFF,
		HEAL,
		EMERGENCY,
		TOGGLE,
		/** Performances of the Swordsinger and Bladedancer lines (one at a time): the first is kept on while it hunts, the second while it fights a player. */
		PERFORM,
		/** Skills that build Sonic/Force energy (Sonic Focus, Maximum Focus Sonic...). */
		CHARGE,
		/** Speed buffs used to catch a player that runs away, or to run away (Dash, Sprint, Sonic Move). */
		MOVE,
		/** Gap closers used on a target out of reach (Rush, Shadow Step). */
		RUSH,
		/** Skills that stop a player in melee range before stepping back or running away (roots, stuns, Aura Flash, Trick). */
		PEEL,
		/** Skills that remove what holds it back (Break Duress against roots, Remedy against bleeding...). */
		CLEANSE,
		/** Servitor summons (Summon Reanimated Man...): it keeps one out, and resummons it away from a PvP when it dies. */
		SUMMON,
		/** Toggles that only make sense with its servitor out (Transfer Pain), switched on with the servitor and off when it dies. */
		LINK,
		/** Combat transformations used when a PvP gets serious (Kamael Final Form): it then fights with the transformation's skills. */
		TRANSFORM,
		/** Heals cast on one party member (Heal, Greater Heal, Major Heal...), see {@link org.l2jmobius.gameserver.managers.FakePartyManager}. */
		PARTY_HEAL,
		/** Heals for the whole party (Group Heal, Chain Heal, Balance Life, Chant of Life...). */
		GROUP_HEAL,
		/** Buffs it keeps up on its party (Might, Shield, chants, songs, dances...), on one member or the whole party. */
		PARTY_BUFF,
		/** MP restored to a party member (Recharge). */
		RECHARGE,
		/** Resurrection of a dead party member. */
		RESURRECT
	}
	
	private final String _name;
	private final PlayerClass _playerClass;
	private final Role _role;
	private final String _weaponKit;
	private final String _armorKit;
	private final String _jewelKit;
	private final String _bowKit;
	private final String _polearmKit;
	private final String _buffList;
	private final int _weight;
	private final boolean _skillFighter;
	private final boolean _support;
	private final String _keystones;
	private final boolean _hybrid;
	private final Map<SkillCategory, List<int[]>> _skills;
	private final List<FakePlayerPvpCombo> _combos;
	
	public FakePlayerPvpBuild(String name, PlayerClass playerClass, Role role, String weaponKit, String armorKit, String jewelKit, String bowKit, String polearmKit, String buffList, int weight, boolean skillFighter, boolean support, String keystones, boolean hybrid)
	{
		this(name, playerClass, role, weaponKit, armorKit, jewelKit, bowKit, polearmKit, buffList, weight, skillFighter, support, keystones, hybrid, new EnumMap<>(SkillCategory.class), new ArrayList<>());
	}
	
	private FakePlayerPvpBuild(String name, PlayerClass playerClass, Role role, String weaponKit, String armorKit, String jewelKit, String bowKit, String polearmKit, String buffList, int weight, boolean skillFighter, boolean support, String keystones, boolean hybrid, Map<SkillCategory, List<int[]>> skills, List<FakePlayerPvpCombo> combos)
	{
		_name = name;
		_playerClass = playerClass;
		_role = role;
		_weaponKit = weaponKit;
		_armorKit = armorKit;
		_jewelKit = jewelKit;
		_bowKit = bowKit;
		_polearmKit = polearmKit;
		_buffList = buffList;
		_weight = weight;
		_skillFighter = skillFighter;
		_support = support;
		_keystones = keystones;
		_hybrid = hybrid;
		_skills = skills;
		_combos = combos;
	}
	
	/**
	 * A second build of the same class: the same skills and combos (shared with this build), other gear, like players of a class that don't all wear the same set.
	 * @param name the name of the variant
	 * @param weaponKit its weapon kit, {@code null} for this build's
	 * @param armorKit its armor kit, {@code null} for this build's
	 * @param jewelKit its jewel kit, {@code null} for this build's
	 * @param bowKit its bow kit, {@code null} for this build's
	 * @param polearmKit its polearm kit, {@code null} for this build's
	 * @param buffList its buff list, {@code null} for this build's
	 * @param weight its relative chance to be picked
	 * @return the variant
	 */
	public FakePlayerPvpBuild createVariant(String name, String weaponKit, String armorKit, String jewelKit, String bowKit, String polearmKit, String buffList, int weight)
	{
		return new FakePlayerPvpBuild(name, _playerClass, _role, weaponKit != null ? weaponKit : _weaponKit, armorKit != null ? armorKit : _armorKit, jewelKit != null ? jewelKit : _jewelKit, bowKit != null ? bowKit : _bowKit, polearmKit != null ? polearmKit : _polearmKit, buffList != null ? buffList : _buffList, weight, _skillFighter, _support, _keystones, _hybrid, _skills, _combos);
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
	 * @return the kit of the polearm it carries besides its weapon and takes out when monsters surround it, {@code null} for none
	 */
	public String getPolearmKit()
	{
		return _polearmKit;
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
	
	/**
	 * @return the keystones its passive tree may head for (names separated by ";", like FakePvpKeystones.* in config/Custom/FakePlayerPvp.ini), {@code null} for the ones of its role
	 */
	public String getKeystones()
	{
		return _keystones;
	}
	
	/**
	 * @return {@code true} for a melee build whose M. Atk. counts too (the Battlemage keystone adds part of it to its P. Atk.): its passive tree wants the magic stats as much as the physical ones
	 */
	public boolean isHybrid()
	{
		return _hybrid;
	}
	
	/**
	 * @return {@code true} for a class players invite to heal or buff (healers, buffers, songs and dances): in a party it looks after the other members before fighting, see {@link org.l2jmobius.gameserver.managers.FakePartyManager}
	 */
	public boolean isSupport()
	{
		return _support;
	}
}
