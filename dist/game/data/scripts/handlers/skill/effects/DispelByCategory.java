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
package handlers.skill.effects;

import java.util.List;

import org.l2jmobius.gameserver.managers.CancelReturnManager;
import org.l2jmobius.gameserver.managers.CancelReturnManager.CanceledBuff;
import org.l2jmobius.gameserver.model.StatSet;
import org.l2jmobius.gameserver.model.actor.Creature;
import org.l2jmobius.gameserver.model.conditions.Condition;
import org.l2jmobius.gameserver.model.effects.AbstractEffect;
import org.l2jmobius.gameserver.model.effects.EffectType;
import org.l2jmobius.gameserver.model.skill.BuffInfo;
import org.l2jmobius.gameserver.model.skill.Skill;
import org.l2jmobius.gameserver.model.skill.enums.SkillFinishType;
import org.l2jmobius.gameserver.model.stats.Formulas;

/**
 * Dispel By Category effect implementation.
 * @author DS, Adry_85, Naker
 */
public class DispelByCategory extends AbstractEffect
{
	private final String _slot;
	private final int _rate;
	private final int _max;
	
	public DispelByCategory(Condition attachCond, Condition applyCond, StatSet set, StatSet params)
	{
		super(attachCond, applyCond, set, params);
		
		_slot = params.getString("slot", null);
		_rate = params.getInt("rate", 0);
		_max = params.getInt("max", 0);
	}
	
	@Override
	public EffectType getEffectType()
	{
		return EffectType.DISPEL;
	}
	
	@Override
	public boolean isInstant()
	{
		return true;
	}
	
	@Override
	public void onStart(Creature effector, Creature effected, Skill skill)
	{
		if ((effected == null) || effected.isDead() || effected.isRaid())
		{
			return;
		}
		
		final List<BuffInfo> canceled = Formulas.calcCancelStealEffects(effector, effected, skill, _slot, _rate, _max);
		if (canceled.isEmpty())
		{
			return;
		}

		// Snapshot before removal. Debuffs removed by cleanses (slot=debuff) are filtered out by snapshot() and never come back.
		final CancelReturnManager cancelReturn = CancelReturnManager.getInstance();
		final List<CanceledBuff> toReturn = cancelReturn.isEligible(effector, effected) ? cancelReturn.snapshot(canceled) : null;

		for (BuffInfo info : canceled)
		{
			effected.getEffectList().stopSkillEffects(SkillFinishType.REMOVED, info.getSkill());
		}

		if (toReturn != null)
		{
			cancelReturn.scheduleReturn(effected.asPlayer(), toReturn);
		}
	}
}