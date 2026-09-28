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
import java.util.concurrent.atomic.AtomicInteger;

import org.l2jmobius.commons.util.Rnd;
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
	private final int _armorWornMask;
	private final int _armorMask;
	private final FakePlayerPvpWeapon _mainWeapon;
	private final FakePlayerPvpWeapon _bow;
	private final FakePlayerPvpWeapon _polearm;
	private volatile FakePlayerPvpWeapon _heldWeapon;
	// Its servitor (necromancers), null when it has none out.
	private volatile Npc _servitor;
	private final int _maxCharges;
	private final AtomicInteger _charges = new AtomicInteger();
	// Kamael souls, used by the soul skills like a player's (see Creature#getChargedSouls()).
	private final AtomicInteger _souls = new AtomicInteger();
	private final List<FakePlayerPvpCombo.Chain> _combos = new ArrayList<>();
	private final List<ItemEnchantHolder> _equipment;
	
	// The monster this fake player replaced and the spawn it came from (null for admin spawns).
	private Npc _replacedMonster;
	private Spawn _replacedSpawn;
	
	private long _spawnTime;
	// How long it stays compared to FakePvpLifetime, so fake players that arrived together don't all log off together.
	private final double _lifetimeScale = Rnd.get(50, 150) / 100.0;
	private volatile long _nextPotionTime;
	
	// Its temper: whether it runs away from a PvP it is losing, and when it may speak again.
	private final boolean _runner;
	private volatile long _nextChatTime;
	
	// Coming back after a death (see FakePlayerPvpManager#returnFakePlayer): where it died, who killed it, and until when it looks for them.
	private volatile boolean _returnPending;
	private volatile boolean _returned;
	private volatile int _deathX;
	private volatile int _deathY;
	private volatile int _deathZ;
	private volatile int _deathInstanceId;
	private volatile int _killerObjectId;
	private volatile long _revengeUntil;
	
	/**
	 * @param build the build
	 * @param playerClass the class for this level
	 * @param level the level
	 * @param buffs the buffs it keeps up
	 * @param armorWornMask the item mask of the armor it wears (like {@code Inventory#getWearedMask()}, without the weapon and shield)
	 * @param armorMask the item mask of its body armor, 0 if it wears none
	 * @param maxCharges the most Sonic/Force energy charges its class can hold
	 * @param equipment every item it wears or carries, with its enchant level
	 * @param mainWeapon its weapon (and shield)
	 * @param bow the bow it carries besides its weapon, {@code null} for none
	 * @param polearm the polearm it carries besides its weapon, {@code null} for none
	 * @param runner {@code true} if it runs away from a PvP it is losing, {@code false} if it fights to the death
	 */
	public FakePlayerPvpProfile(FakePlayerPvpBuild build, PlayerClass playerClass, int level, List<SkillHolder> buffs, int armorWornMask, int armorMask, int maxCharges, List<ItemEnchantHolder> equipment, FakePlayerPvpWeapon mainWeapon, FakePlayerPvpWeapon bow, FakePlayerPvpWeapon polearm, boolean runner)
	{
		_build = build;
		_playerClass = playerClass;
		_level = level;
		_buffs = buffs;
		_armorWornMask = armorWornMask;
		_armorMask = armorMask;
		_mainWeapon = mainWeapon;
		_bow = bow;
		_polearm = polearm;
		_heldWeapon = mainWeapon;
		_maxCharges = maxCharges;
		_charges.set(maxCharges);
		_equipment = equipment;
		_runner = runner;
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
	 * @return the item mask of every item it wears and holds, used for skill conditions
	 */
	public int getWornMask()
	{
		return _armorWornMask | _heldWeapon.getItemMask();
	}
	
	public FakePlayerPvpWeapon getMainWeapon()
	{
		return _mainWeapon;
	}
	
	/**
	 * @return the bow it carries besides its weapon, {@code null} for none
	 */
	public FakePlayerPvpWeapon getBow()
	{
		return _bow;
	}
	
	/**
	 * @return the polearm it carries besides its weapon, {@code null} for none
	 */
	public FakePlayerPvpWeapon getPolearm()
	{
		return _polearm;
	}
	
	/**
	 * @return the weapon it holds now
	 */
	public FakePlayerPvpWeapon getHeldWeapon()
	{
		return _heldWeapon;
	}
	
	public void setHeldWeapon(FakePlayerPvpWeapon weapon)
	{
		_heldWeapon = weapon;
	}
	
	/**
	 * @return {@code true} if it has switched to its bow
	 */
	public boolean isBowHeld()
	{
		return (_bow != null) && (_heldWeapon == _bow);
	}
	
	/**
	 * @return its servitor, {@code null} if it has none out
	 */
	public Npc getServitor()
	{
		return _servitor;
	}
	
	public void setServitor(Npc servitor)
	{
		_servitor = servitor;
	}
	
	/**
	 * @return {@code true} if it has a class servitor summon but no living servitor out
	 */
	public boolean needsServitor()
	{
		final Npc servitor = _servitor;
		return !getSkills(SkillCategory.SUMMON).isEmpty() && ((servitor == null) || servitor.isDead() || !servitor.isSpawned());
	}
	
	/**
	 * @return {@code true} if it has switched to its polearm
	 */
	public boolean isPolearmHeld()
	{
		return (_polearm != null) && (_heldWeapon == _polearm);
	}
	
	/**
	 * @return the item mask of its body armor (light, heavy or robe), 0 if none
	 */
	public int getArmorMask()
	{
		return _armorMask;
	}
	
	/**
	 * @return the Sonic/Force energy charges it has now
	 */
	public int getCharges()
	{
		return _charges.get();
	}
	
	/**
	 * @return the most Sonic/Force energy charges its class can hold, 0 for classes without energy
	 */
	public int getMaxCharges()
	{
		return _maxCharges;
	}
	
	/**
	 * Adds energy like {@code Player#increaseCharges(int, int)}.
	 * @param count how many
	 * @param max the most this skill can charge up to
	 */
	public void increaseCharges(int count, int max)
	{
		_charges.updateAndGet(charges -> charges >= max ? charges : Math.min(max, charges + count));
	}
	
	/**
	 * @param count how many charges a skill consumes
	 */
	public void decreaseCharges(int count)
	{
		_charges.updateAndGet(charges -> Math.max(0, charges - count));
	}
	
	/**
	 * @return the Kamael souls it has now
	 */
	public int getSouls()
	{
		return _souls.get();
	}
	
	public void setSouls(int souls)
	{
		_souls.set(Math.max(0, souls));
	}
	
	/**
	 * Absorbs souls like {@code Player#increaseSouls(int)}.
	 * @param count how many
	 * @param max the most its class can hold
	 */
	public void increaseSouls(int count, int max)
	{
		_souls.updateAndGet(souls -> souls >= max ? souls : Math.min(max, souls + count));
	}
	
	/**
	 * @param count how many souls a skill consumes
	 */
	public void decreaseSouls(int count)
	{
		_souls.updateAndGet(souls -> Math.max(0, souls - count));
	}
	
	public void addCombo(FakePlayerPvpCombo.Chain combo)
	{
		_combos.add(combo);
	}
	
	/**
	 * @return the combos it can play at its level, most preferred first
	 */
	public List<FakePlayerPvpCombo.Chain> getCombos()
	{
		return _combos;
	}
	
	/**
	 * @return every item it wears or carries, with its enchant level
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
	
	/**
	 * @return its share of {@link org.l2jmobius.gameserver.config.custom.FakePlayerPvpConfig#LIFETIME}, between 0.5 and 1.5
	 */
	public double getLifetimeScale()
	{
		return _lifetimeScale;
	}
	
	public long getNextPotionTime()
	{
		return _nextPotionTime;
	}
	
	public void setNextPotionTime(long nextPotionTime)
	{
		_nextPotionTime = nextPotionTime;
	}
	
	/**
	 * @return {@code true} if it runs away from a PvP it is losing (and reads a Scroll of Escape once it got away), {@code false} if it fights to the death
	 */
	public boolean isRunner()
	{
		return _runner;
	}
	
	public long getNextChatTime()
	{
		return _nextChatTime;
	}
	
	public void setNextChatTime(long nextChatTime)
	{
		_nextChatTime = nextChatTime;
	}
	
	/**
	 * Remembers that this fake player comes back to where it died, after a while, to find its killer.
	 * @param x the death x
	 * @param y the death y
	 * @param z the death z
	 * @param instanceId the death instance
	 * @param killerObjectId the object id of the player that killed it
	 */
	public void setReturn(int x, int y, int z, int instanceId, int killerObjectId)
	{
		_deathX = x;
		_deathY = y;
		_deathZ = z;
		_deathInstanceId = instanceId;
		_killerObjectId = killerObjectId;
		_returnPending = true;
	}
	
	/**
	 * @return {@code true} if it comes back to where it died once its body is gone
	 */
	public boolean isReturnPending()
	{
		return _returnPending;
	}
	
	/**
	 * Called when it comes back: it looks for its killer until {@code revengeUntil}, and won't come back another time.
	 * @param revengeUntil the time until which it looks for its killer
	 */
	public void onReturn(long revengeUntil)
	{
		_returnPending = false;
		_returned = true;
		_revengeUntil = revengeUntil;
		_charges.set(_maxCharges);
		_nextPotionTime = 0;
		_nextChatTime = 0;
	}
	
	/**
	 * @return {@code true} if it already came back once after a death
	 */
	public boolean hasReturned()
	{
		return _returned;
	}
	
	public int getDeathX()
	{
		return _deathX;
	}
	
	public int getDeathY()
	{
		return _deathY;
	}
	
	public int getDeathZ()
	{
		return _deathZ;
	}
	
	public int getDeathInstanceId()
	{
		return _deathInstanceId;
	}
	
	/**
	 * @param now the current time
	 * @return the object id of the player it is looking for, 0 if none (not a returned fake player, window over, or already found)
	 */
	public int getRevengeTarget(long now)
	{
		return now < _revengeUntil ? _killerObjectId : 0;
	}
	
	/**
	 * Its killer was found (or it gave up): it doesn't look for them anymore.
	 */
	public void clearRevengeTarget()
	{
		_killerObjectId = 0;
		_revengeUntil = 0;
	}
}
