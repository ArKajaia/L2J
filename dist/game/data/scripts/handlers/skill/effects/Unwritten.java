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

import org.l2jmobius.gameserver.data.xml.SkillData;
import org.l2jmobius.gameserver.model.StatSet;
import org.l2jmobius.gameserver.model.actor.Creature;
import org.l2jmobius.gameserver.model.actor.holders.player.AreaTargets;
import org.l2jmobius.gameserver.model.conditions.Condition;
import org.l2jmobius.gameserver.model.effects.AbstractEffect;
import org.l2jmobius.gameserver.model.skill.Skill;

/**
 * Unwritten (Hierophant finale): a prophecy for everyone around. Every enemy in range gets the {@code enemySkillId} prophecy (Doom) and every party member in range the {@code partySkillId} one (Salvation), at the level the Oracle knows them (1 if it doesn't).
 * @author Mobius
 */
public class Unwritten extends AbstractEffect
{
	private final int _enemySkillId;
	private final int _partySkillId;
	private final int _range;
	
	public Unwritten(Condition attachCond, Condition applyCond, StatSet set, StatSet params)
	{
		super(attachCond, applyCond, set, params);
		
		_enemySkillId = params.getInt("enemySkillId");
		_partySkillId = params.getInt("partySkillId");
		_range = params.getInt("range", 600);
	}
	
	@Override
	public boolean isInstant()
	{
		return true;
	}
	
	private static Skill getProphecy(Creature oracle, int skillId)
	{
		final Skill known = oracle.getKnownSkill(skillId);
		return known != null ? known : SkillData.getInstance().getSkill(skillId, 1);
	}
	
	@Override
	public void onStart(Creature effector, Creature effected, Skill skill)
	{
		final Skill doom = getProphecy(effector, _enemySkillId);
		if (doom != null)
		{
			for (Creature enemy : AreaTargets.getEnemies(effector, effector, _range, doom))
			{
				doom.applyEffects(effector, enemy);
			}
		}
		
		final Skill salvation = getProphecy(effector, _partySkillId);
		if (salvation != null)
		{
			for (Creature member : AreaTargets.getParty(effector))
			{
				if (!member.isDead() && ((member == effector) || member.isInsideRadius3D(effector, _range)))
				{
					salvation.applyEffects(effector, member);
				}
			}
		}
	}
}
