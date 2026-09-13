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
package org.l2jmobius.gameserver.model.skill.holders;

import java.util.ArrayList;
import java.util.List;

import org.l2jmobius.gameserver.model.StatSet;
import org.l2jmobius.gameserver.model.actor.Player;

public class EnchantSkillGroup
{
	private final int _id;
	private final List<EnchantSkillHolder> _enchantDetails = new ArrayList<>();
	
	public EnchantSkillGroup(int id)
	{
		_id = id;
	}
	
	public void addEnchantDetail(EnchantSkillHolder detail)
	{
		_enchantDetails.add(detail);
	}
	
	public int getId()
	{
		return _id;
	}
	
	public List<EnchantSkillHolder> getEnchantGroupDetails()
	{
		return _enchantDetails;
	}
	
	public static class EnchantSkillHolder
	{
		private final int _level;
		private final int _adenaCost;
		private final int _expCost;
		private final int _spCost;
		private final byte[] _rate;
		
		public EnchantSkillHolder(StatSet set)
		{
			_level = set.getInt("level");
			_adenaCost = set.getInt("adena", 0);
			_expCost = set.getInt("exp", 0);
			_spCost = set.getInt("sp", 0);
			_rate = new byte[24];
			for (int i = 0; i < 24; i++)
			{
				_rate[i] = set.getByte("chance" + (76 + i), (byte) 0);
			}
		}
		
		/**
		 * @return Returns the level.
		 */
		public int getLevel()
		{
			return _level;
		}
		
		/**
		 * @return Returns the spCost.
		 */
		public int getSpCost()
		{
			return _spCost;
		}
		
		public int getExpCost()
		{
			return _expCost;
		}
		
		public int getAdenaCost()
		{
			return _adenaCost;
		}
		
		public byte getRate(Player ply)
		{
			return ply.getLevel() < 76 ? 0 : _rate[ply.getLevel() - 76];
		}
	}
}
