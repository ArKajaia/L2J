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
package org.l2jmobius.gameserver.network.serverpackets;

import java.util.ArrayList;
import java.util.List;

import org.l2jmobius.commons.network.WritableBuffer;
import org.l2jmobius.gameserver.data.xml.EnchantSkillGroupsData;
import org.l2jmobius.gameserver.model.skill.holders.EnchantSkillLearn;
import org.l2jmobius.gameserver.model.skill.holders.EnchantSkillGroup.EnchantSkillHolder;
import org.l2jmobius.gameserver.network.GameClient;
import org.l2jmobius.gameserver.network.ServerPackets;

public class ExEnchantSkillInfo extends ServerPacket
{
	private final List<Integer> _routes = new ArrayList<>(); // skill levels for each route
	private final int _id;
	private final int _level;
	private boolean _maxEnchanted = false;
	
	public ExEnchantSkillInfo(int id, int level)
	{
		_id = id;
		_level = level;
		final EnchantSkillLearn enchantLearn = EnchantSkillGroupsData.getInstance().getSkillEnchantmentBySkillId(_id);
		
		// do we have this skill?
		if (enchantLearn != null)
		{
			// skill already enchanted?
			if (_level > 100)
			{
				_maxEnchanted = enchantLearn.isMaxEnchant(_level);
				
				// get detail for next level
				final EnchantSkillHolder esd = enchantLearn.getEnchantSkillHolder(_level);
				
				// if it exists add it
				if (esd != null)
				{
					_routes.add(_level); // current enchant add firts
				}
				
				final int skillLevel = (_level % 100);
				for (int route : enchantLearn.getAllRoutes())
				{
					if (((route * 100) + skillLevel) == _level)
					{
						continue;
					}
					
					// add other levels of all routes - same level as enchanted
					// level
					_routes.add((route * 100) + skillLevel);
				}
			}
			else
			// not already enchanted
			{
				for (int route : enchantLearn.getAllRoutes())
				{
					// add first level (+1) of all routes
					_routes.add((route * 100) + 1);
				}
			}
		}
	}
	
	@Override
	public void writeImpl(GameClient client, WritableBuffer buffer)
	{
		ServerPackets.EX_ENCHANT_SKILL_INFO.writeId(this, buffer);
		buffer.writeInt(_id);
		buffer.writeInt(_level);
		buffer.writeInt(!_maxEnchanted);
		buffer.writeInt(_level > 100); // enchanted?
		buffer.writeInt(_routes.size());
		for (int level : _routes)
		{
			buffer.writeInt(level);
		}
	}
}
