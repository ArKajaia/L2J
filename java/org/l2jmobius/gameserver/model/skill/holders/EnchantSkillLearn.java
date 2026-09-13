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

import java.util.Set;
import java.util.TreeMap;

import org.l2jmobius.gameserver.data.xml.EnchantSkillGroupsData;
import org.l2jmobius.gameserver.model.skill.holders.EnchantSkillGroup.EnchantSkillHolder;

public class EnchantSkillLearn
{
	private final int _id;
	private final int _baseLevel;
	private final TreeMap<Integer, Integer> _enchantRoutes = new TreeMap<>();
	
	public EnchantSkillLearn(int id, int baseLevel)
	{
		_id = id;
		_baseLevel = baseLevel;
	}
	
	public void addNewEnchantRoute(int route, int group)
	{
		_enchantRoutes.put(route, group);
	}
	
	/**
	 * @return Returns the id.
	 */
	public int getId()
	{
		return _id;
	}
	
	/**
	 * @return Returns the minLevel.
	 */
	public int getBaseLevel()
	{
		return _baseLevel;
	}
	
	public static int getEnchantRoute(int level)
	{
		return (int) Math.floor(level / 100);
	}
	
	public static int getEnchantIndex(int level)
	{
		return (level % 100) - 1;
	}
	
	public static int getEnchantType(int level)
	{
		return ((level - 1) / 100) - 1;
	}
	
	public EnchantSkillGroup getFirstRouteGroup()
	{
		return EnchantSkillGroupsData.getInstance().getEnchantSkillGroupById(_enchantRoutes.firstEntry().getValue());
	}
	
	public Set<Integer> getAllRoutes()
	{
		return _enchantRoutes.keySet();
	}
	
	public int getMinSkillLevel(int level)
	{
		return (level % 100) == 1 ? _baseLevel : level - 1;
	}
	
	public boolean isMaxEnchant(int level)
	{
		final int enchantType = getEnchantRoute(level);
		if ((enchantType < 1) || !_enchantRoutes.containsKey(enchantType))
		{
			return false;
		}
		
		final int index = getEnchantIndex(level);
		return (index + 1) >= EnchantSkillGroupsData.getInstance().getEnchantSkillGroupById(_enchantRoutes.get(enchantType)).getEnchantGroupDetails().size();
	}
	
	public EnchantSkillHolder getEnchantSkillHolder(int level)
	{
		final int enchantType = getEnchantRoute(level);
		if ((enchantType < 1) || !_enchantRoutes.containsKey(enchantType))
		{
			return null;
		}
		
		final int index = getEnchantIndex(level);
		final EnchantSkillGroup group = EnchantSkillGroupsData.getInstance().getEnchantSkillGroupById(_enchantRoutes.get(enchantType));
		if (index < 0)
		{
			return group.getEnchantGroupDetails().get(0);
		}
		else if (index >= group.getEnchantGroupDetails().size())
		{
			return group.getEnchantGroupDetails().get(EnchantSkillGroupsData.getInstance().getEnchantSkillGroupById(_enchantRoutes.get(enchantType)).getEnchantGroupDetails().size() - 1);
		}
		
		return group.getEnchantGroupDetails().get(index);
	}
}
