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

/**
 * A rule of a stage: on an event, maybe (a {@code chance}), do an action.
 * @author Mobius
 */
public class Transition
{
	private final TransitionEvent _event;
	private final TransitionAction _action;
	private final int _target;
	private final double _chance;
	private final int _max;
	private final int _extraTime;
	private final String _text;
	private final int _maxChain;

	public Transition(TransitionEvent event, TransitionAction action, int target, double chance, int max, int extraTime, String text, int maxChain)
	{
		_event = event;
		_action = action;
		_target = target;
		_chance = chance;
		_max = max;
		_extraTime = extraTime;
		_text = text;
		_maxChain = maxChain;
	}

	/**
	 * @return the chain length from which this transition is skipped, 0 for never (lets a quest follow up on itself a few times)
	 */
	public int getMaxChain()
	{
		return _maxChain;
	}

	public TransitionEvent getEvent()
	{
		return _event;
	}

	public TransitionAction getAction()
	{
		return _action;
	}

	/**
	 * @return the stage (STAGE, 1 = the first) or template id (FOLLOW_UP)
	 */
	public int getTarget()
	{
		return _target;
	}

	/**
	 * @return the chance in percent that this transition is the one taken
	 */
	public double getChance()
	{
		return _chance;
	}

	/**
	 * @return how many times ESCALATE can happen before the quest fails
	 */
	public int getMax()
	{
		return _max;
	}

	/**
	 * @return the seconds ESCALATE adds to the stage, 0 for half its time limit
	 */
	public int getExtraTime()
	{
		return _extraTime;
	}

	/**
	 * @return what the players are told, may be {@code null}
	 */
	public String getText()
	{
		return _text;
	}
}
