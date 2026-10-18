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
package org.l2jmobius.gameserver.model.dynamicquest;

import java.util.Collections;
import java.util.List;

/**
 * A stage of a dynamic quest: objectives done together, then transitions.
 * @author Mobius
 */
public class StageTemplate
{
	private final String _name;
	private final int _timeLimit;
	private final boolean _failOnDeath;
	private final String _text;
	private final List<ObjectiveTemplate> _objectives;
	private final List<Transition> _transitions;

	public StageTemplate(String name, int timeLimit, boolean failOnDeath, String text, List<ObjectiveTemplate> objectives, List<Transition> transitions)
	{
		_name = name;
		_timeLimit = timeLimit;
		_failOnDeath = failOnDeath;
		_text = text;
		_objectives = Collections.unmodifiableList(objectives);
		_transitions = Collections.unmodifiableList(transitions);
	}

	public String getName()
	{
		return _name;
	}

	/**
	 * @return the time limit in seconds at the Normal tier, 0 for none
	 */
	public int getTimeLimit()
	{
		return _timeLimit;
	}

	/**
	 * @return {@code true} if the owner's death fails the stage
	 */
	public boolean isFailOnDeath()
	{
		return _failOnDeath;
	}

	/**
	 * @return what the players are told when the stage starts, may be {@code null}
	 */
	public String getText()
	{
		return _text;
	}

	public List<ObjectiveTemplate> getObjectives()
	{
		return _objectives;
	}

	public List<Transition> getTransitions()
	{
		return _transitions;
	}

	/**
	 * @return {@code true} if all its objectives keep their progress over a logout
	 */
	public boolean isPersistent()
	{
		for (ObjectiveTemplate objective : _objectives)
		{
			if (!objective.getType().isPersistent())
			{
				return false;
			}
		}
		return true;
	}
}
