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

import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import org.l2jmobius.gameserver.model.actor.Npc;
import org.l2jmobius.gameserver.model.actor.enums.player.PlayerClass;
import org.l2jmobius.gameserver.model.actor.holders.npc.FakePlayerPvpBuild.Role;
import org.l2jmobius.gameserver.model.actor.holders.npc.FakePlayerPvpBuild.SkillCategory;
import org.l2jmobius.gameserver.model.item.holders.ItemEnchantHolder;
import org.l2jmobius.gameserver.model.skill.Skill;
import org.l2jmobius.gameserver.model.skill.holders.SkillHolder;
import org.l2jmobius.gameserver.model.spawns.Spawn;

/**
 * Everything a single roaming fake player needs at runtime (see {@link org.l2jmobius.gameserver.managers.FakePlayerPvpManager}). One instance per spawned fake player, attached to its own {@link org.l2jmobius.gameserver.model.actor.templates.NpcTemplate}.
 */
public class FakePlayerPvpProfile
{
	private final FakePlayerPvpBuild _build;
	private final PlayerClass _playerClass;
	private final int _level;
	private final Map<SkillCategory, List<Skill>> _skills = new EnumMap<>(SkillCategory.class);
	private final List<SkillHolder> _buffs;
	private final int _wornMask;
	private final int _armorMask;
	private final int _charges;
	private final List<ItemEnchantHolder> _equipment;
	
	// The monster this fake player replaced and the spawn it came from (null for admin spawns).
	private Npc _replacedMonster;
	private Spawn _replacedSpawn;
	
	private long _spawnTime;
	private volatile long _nextPotionTime;
	
	/**
	 * @param build the build
	 * @param playerClass the class for this level
	 * @param level the level
	 * @param buffs the buffs it keeps up
	 * @param wornMask the item mask of everything it wears (like {@code Inventory#getWearedMask()})
	 * @param armorMask the item mask of its body armor, 0 if it wears none
	 * @param charges the energy charges it fights with
	 * @param equipment every item it wears, with its enchant level
	 */
	public FakePlayerPvpProfile(FakePlayerPvpBuild build, PlayerClass playerClass, int level, List<SkillHolder> buffs, int wornMask, int armorMask, int charges, List<ItemEnchantHolder> equipment)
	{
		_build = build;
		_playerClass = playerClass;
		_level = level;
		_buffs = buffs;
		_wornMask = wornMask;
		_armorMask = armorMask;
		_charges = charges;
		_equipment = equipment;
	}
	
	public void setSkills(SkillCategory category, List<Skill> skills)
	{
		_skills.put(category, skills);
	}
	
	public List<Skill> getSkills(SkillCategory category)
	{
		return _skills.getOrDefault(category, Collections.emptyList());
	}
	
	public FakePlayerPvpBuild getBuild()
	{
		return _build;
	}
	
	public Role getRole()
	{
		return _build.getRole();
	}
	
	public PlayerClass getPlayerClass()
	{
		return _playerClass;
	}
	
	public int getLevel()
	{
		return _level;
	}
	
	/**
	 * @return the buffs (from other players) this fake player keeps on itself
	 */
	public List<SkillHolder> getBuffs()
	{
		return _buffs;
	}
	
	/**
	 * @return the item mask of every item it wears, used for skill conditions
	 */
	public int getWornMask()
	{
		return _wornMask;
	}
	
	/**
	 * @return the item mask of its body armor (light, heavy or robe), 0 if none
	 */
	public int getArmorMask()
	{
		return _armorMask;
	}
	
	/**
	 * @return the energy charges (Sonic/Force) it is considered to have
	 */
	public int getCharges()
	{
		return _charges;
	}
	
	/**
	 * @return every item it wears, with its enchant level
	 */
	public List<ItemEnchantHolder> getEquipment()
	{
		return _equipment;
	}
	
	public void setReplacedMonster(Npc monster, Spawn spawn)
	{
		_replacedMonster = monster;
		_replacedSpawn = spawn;
	}
	
	/**
	 * Hands back the replaced monster once, so its spawn is only released one time.
	 * @return the replaced monster, or {@code null} if there is none or it was already taken
	 */
	public synchronized Npc takeReplacedMonster()
	{
		final Npc monster = _replacedMonster;
		_replacedMonster = null;
		return monster;
	}
	
	public Npc getReplacedMonster()
	{
		return _replacedMonster;
	}
	
	public Spawn getReplacedSpawn()
	{
		return _replacedSpawn;
	}
	
	public long getSpawnTime()
	{
		return _spawnTime;
	}
	
	public void setSpawnTime(long spawnTime)
	{
		_spawnTime = spawnTime;
	}
	
	public long getNextPotionTime()
	{
		return _nextPotionTime;
	}
	
	public void setNextPotionTime(long nextPotionTime)
	{
		_nextPotionTime = nextPotionTime;
	}
}
