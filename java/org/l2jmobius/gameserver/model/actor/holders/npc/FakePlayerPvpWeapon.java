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

import org.l2jmobius.gameserver.model.item.Armor;
import org.l2jmobius.gameserver.model.item.Weapon;
import org.l2jmobius.gameserver.model.item.type.WeaponType;

/**
 * A weapon (and shield) a roaming fake player can hold, with the stats it gives, like the weapon "set" functions of a real character. A fake player holds its main weapon, and tanks and tyrants also carry a bow they switch to.
 */
public class FakePlayerPvpWeapon
{
	private final Weapon _weapon;
	private final Armor _shield;
	private final int _enchant;
	private final int _pAtk;
	private final int _mAtk;
	private final int _pAtkSpd;
	private final int _critRate;
	private final int _attackRange;
	private final int _randomDamage;
	private final WeaponType _attackType;
	private final int _shieldDefence;
	private final int _shieldRate;
	
	public FakePlayerPvpWeapon(Weapon weapon, Armor shield, int enchant, int pAtk, int mAtk, int pAtkSpd, int critRate, int attackRange, int randomDamage, WeaponType attackType, int shieldDefence, int shieldRate)
	{
		_weapon = weapon;
		_shield = shield;
		_enchant = enchant;
		_pAtk = pAtk;
		_mAtk = mAtk;
		_pAtkSpd = pAtkSpd;
		_critRate = critRate;
		_attackRange = attackRange;
		_randomDamage = randomDamage;
		_attackType = attackType;
		_shieldDefence = shieldDefence;
		_shieldRate = shieldRate;
	}
	
	/**
	 * @return the weapon, {@code null} for bare hands
	 */
	public Weapon getWeapon()
	{
		return _weapon;
	}
	
	/**
	 * @return the shield, {@code null} for none
	 */
	public Armor getShield()
	{
		return _shield;
	}
	
	public int getWeaponId()
	{
		return _weapon != null ? _weapon.getId() : 0;
	}
	
	public int getShieldId()
	{
		return _shield != null ? _shield.getId() : 0;
	}
	
	public int getEnchant()
	{
		return _enchant;
	}
	
	public int getPAtk()
	{
		return _pAtk;
	}
	
	public int getMAtk()
	{
		return _mAtk;
	}
	
	public int getPAtkSpd()
	{
		return _pAtkSpd;
	}
	
	public int getCritRate()
	{
		return _critRate;
	}
	
	public int getAttackRange()
	{
		return _attackRange;
	}
	
	public int getRandomDamage()
	{
		return _randomDamage;
	}
	
	public WeaponType getAttackType()
	{
		return _attackType;
	}
	
	public int getShieldDefence()
	{
		return _shieldDefence;
	}
	
	public int getShieldRate()
	{
		return _shieldRate;
	}
	
	/**
	 * @return the item mask of the weapon and shield, for skill conditions
	 */
	public int getItemMask()
	{
		return (_weapon != null ? _weapon.getItemMask() : 0) | (_shield != null ? _shield.getItemMask() : 0);
	}
}
