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
package org.l2jmobius.gameserver.ai.fight;

import java.util.List;

/**
 * A few skills a fake player means to use in a row, like a player's plan: a burst (burst buffs, a crowd control, then the biggest hits while it holds), a crowd control chain (the next one as the last ends), an archer's kite cycle, or one of its build's combos.
 * The scorer gives the next step a bonus, so the plan goes on unless something better comes up.
 */
public class SkillPlan
{
	public enum Type
	{
		BURST(8000),
		CC_CHAIN(3000),
		/** Marks an archer's shoot and step cycle (no skill steps: brain B's own kiting runs it). */
		KITE(Long.MAX_VALUE),
		COMBO(15000);
		
		private final long _duration;
		
		Type(long duration)
		{
			_duration = duration;
		}
	}
	
	private final Type _type;
	private final List<Integer> _steps;
	private final long _startedAt;
	private int _index;
	
	/**
	 * @param type what kind of plan
	 * @param steps the skill ids, in order
	 * @param startedAt when it started
	 */
	public SkillPlan(Type type, List<Integer> steps, long startedAt)
	{
		_type = type;
		_steps = List.copyOf(steps);
		_startedAt = startedAt;
	}
	
	public Type type()
	{
		return _type;
	}
	
	public List<Integer> steps()
	{
		return _steps;
	}
	
	/**
	 * @return the skill id of the next step, -1 when every step is done
	 */
	public int next()
	{
		return _index < _steps.size() ? _steps.get(_index) : -1;
	}
	
	/**
	 * @param castSkillId a skill the fake player just used
	 * @return {@code true} if it was the next step (the plan moves on)
	 */
	public boolean advance(int castSkillId)
	{
		if ((castSkillId > 0) && (castSkillId == next()))
		{
			_index++;
			return true;
		}
		return false;
	}
	
	/**
	 * @param now the current time
	 * @return {@code true} once the plan ran longer than its kind may
	 */
	public boolean expired(long now)
	{
		return (now - _startedAt) > _type._duration;
	}
	
	/**
	 * @return {@code true} once every step is done
	 */
	public boolean done()
	{
		return (_type != Type.KITE) && (_index >= _steps.size());
	}
}
