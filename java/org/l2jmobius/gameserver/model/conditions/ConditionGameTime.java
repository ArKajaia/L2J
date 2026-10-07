/*
 * This file is part of the L2J Mobius project.
 * 
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 * 
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * General Public License for more details.
 * 
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>.
 */
package org.l2jmobius.gameserver.model.conditions;

import org.l2jmobius.gameserver.config.custom.NightCycleConfig;
import org.l2jmobius.gameserver.managers.NightCycleManager;
import org.l2jmobius.gameserver.model.actor.Creature;
import org.l2jmobius.gameserver.model.item.ItemTemplate;
import org.l2jmobius.gameserver.model.skill.Skill;
import org.l2jmobius.gameserver.taskmanagers.GameTimeTaskManager;

/**
 * The Class ConditionGameTime.
 * @author mkizub
 */
public class ConditionGameTime extends Condition
{
	private final boolean _required;
	
	/**
	 * Instantiates a new condition game time.
	 * @param required the required
	 */
	public ConditionGameTime(boolean required)
	{
		_required = required;
	}
	
	/**
	 * Test impl. With the Night Cycle on, the night is its night: the same hours as the game clock, but a GM can force it (see {@link NightCycleManager}).
	 * @return true, if successful
	 */
	@Override
	public boolean testImpl(Creature effector, Creature effected, Skill skill, ItemTemplate item)
	{
		if (NightCycleConfig.ENABLED && NightCycleManager.isStarted())
		{
			return NightCycleManager.getInstance().isNight() == _required;
		}
		return GameTimeTaskManager.getInstance().isNight() == _required;
	}
}
