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

import org.l2jmobius.gameserver.model.StatSet;
import org.l2jmobius.gameserver.model.actor.Creature;
import org.l2jmobius.gameserver.model.actor.holders.player.Prophecies;
import org.l2jmobius.gameserver.model.conditions.Condition;
import org.l2jmobius.gameserver.model.effects.AbstractEffect;
import org.l2jmobius.gameserver.model.skill.Skill;

/**
 * Prophecy (Oracles, the Prophet line): does nothing when it lands, and comes true when its time runs out, see {@link Prophecies}. It fizzles if it is cancelled or its target dies first.
 * <ul>
 * <li>{@code kind}: DOOM, SALVATION, RUIN or REVERSAL.</li>
 * <li>{@code skillId}, {@code skillLevel}: the skill it comes true with (Doom: the damage, Ruin: the stun); the level defaults to the prophecy's.</li>
 * <li>{@code altSkillId}: Ruin, the silence for a target that didn't cast.</li>
 * </ul>
 * @author Mobius
 */
public class Prophecy extends AbstractEffect
{
	private final Prophecies.Kind _kind;
	private final int _skillId;
	private final int _skillLevel;
	private final int _altSkillId;
	
	public Prophecy(Condition attachCond, Condition applyCond, StatSet set, StatSet params)
	{
		super(attachCond, applyCond, set, params);
		
		_kind = params.getEnum("kind", Prophecies.Kind.class, Prophecies.Kind.DOOM);
		_skillId = params.getInt("skillId", 0);
		_skillLevel = params.getInt("skillLevel", 0);
		_altSkillId = params.getInt("altSkillId", 0);
	}
	
	@Override
	public void onStart(Creature effector, Creature effected, Skill skill)
	{
		Prophecies.begin(effector, effected, skill, _kind, _skillId, _skillLevel > 0 ? _skillLevel : skill.getLevel(), _altSkillId);
	}
	
	@Override
	public void onExit(Creature effector, Creature effected, Skill skill)
	{
		Prophecies.end(effected, skill);
	}
}
