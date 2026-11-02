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

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

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
	private final List<String> _variants;
	private final List<ObjectiveTemplate> _objectives;
	private final List<Transition> _transitions;

	public StageTemplate(String name, int timeLimit, boolean failOnDeath, String text, List<ObjectiveTemplate> objectives, List<Transition> transitions)
	{
		this(name, timeLimit, failOnDeath, text == null ? Collections.emptyList() : Collections.singletonList(text), objectives, transitions);
	}

	/**
	 * @param texts what the players may be told when the stage starts: one of them, picked by the quest's seed
	 */
	public StageTemplate(String name, int timeLimit, boolean failOnDeath, List<String> texts, List<ObjectiveTemplate> objectives, List<Transition> transitions)
	{
		_variants = Collections.unmodifiableList(new ArrayList<>(texts));
		final String text = texts.isEmpty() ? null : texts.get(0);
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

	/**
	 * @param seed the quest's seed
	 * @return what the players are told when the stage starts, one of its variants for this quest, may be {@code null}
	 */
	public String getText(long seed)
	{
		if (_variants.size() < 2)
		{
			return _text;
		}
		return _variants.get(new Random(seed ^ (_name.hashCode() * 0x9E3779B97F4A7C15L)).nextInt(_variants.size()));
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
