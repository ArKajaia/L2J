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

import org.l2jmobius.gameserver.model.actor.Creature;
import org.l2jmobius.gameserver.model.actor.enums.creature.Team;
import org.l2jmobius.gameserver.model.actor.templates.NpcTemplate;
import org.l2jmobius.gameserver.model.skill.Skill;
import org.l2jmobius.gameserver.model.undergroundColiseum.UCTeam;

public class UCTower extends Folk
{
	private UCTeam _team;
	
	public UCTower(UCTeam team, NpcTemplate template)
	{
		super(template);
		_team = team;
	}
	
	@Override
	public boolean canBeAttacked()
	{
		return true;
	}
	
	@Override
	public boolean isAutoAttackable(Creature creature)
	{
		return true;
	}
	
	@Override
	public void reduceCurrentHp(double damage, Creature attacker, boolean awake, boolean isDOT, Skill skill)
	{
		if (attacker.getTeam() == getTeam())
		{
			return;
		}
		
		if (damage < getStatus().getCurrentHp())
		{
			getStatus().setCurrentHp(getStatus().getCurrentHp() - damage);
		}
		else
		{
			doDie(attacker);
		}
	}
	
	@Override
	public boolean doDie(Creature killer)
	{
		if (!super.doDie(killer))
		{
			return false;
		}
		
		if (_team != null)
		{
			_team.deleteTower();
			_team = null;
		}
		
		return true;
	}
	
	@Override
	public Team getTeam()
	{
		return _team.getIndex() == 1 ? Team.BLUE : Team.RED;
	}
}
