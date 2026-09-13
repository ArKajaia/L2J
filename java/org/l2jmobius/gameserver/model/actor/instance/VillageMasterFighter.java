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
package org.l2jmobius.gameserver.model.actor.instance;

import org.l2jmobius.gameserver.data.enums.CategoryType;
import org.l2jmobius.gameserver.data.xml.CategoryData;
import org.l2jmobius.gameserver.model.actor.enums.creature.Race;
import org.l2jmobius.gameserver.model.actor.enums.player.PlayerClass;
import org.l2jmobius.gameserver.model.actor.templates.NpcTemplate;

public class VillageMasterFighter extends VillageMaster
{
	/**
	 * Creates a village master.
	 * @param template the village master NPC template
	 */
	public VillageMasterFighter(NpcTemplate template)
	{
		super(template);
	}
	
	@Override
	protected final boolean checkVillageMasterRace(PlayerClass pClass)
	{
		if (pClass == null)
		{
			return false;
		}
		
		return (pClass.getRace() == Race.HUMAN) || (pClass.getRace() == Race.ELF);
	}
	
	@Override
	protected final boolean checkVillageMasterTeachType(PlayerClass pClass)
	{
		if (pClass == null)
		{
			return false;
		}
		
		return CategoryData.getInstance().isInCategory(CategoryType.FIGHTER_GROUP, pClass.getId());
	}
}
