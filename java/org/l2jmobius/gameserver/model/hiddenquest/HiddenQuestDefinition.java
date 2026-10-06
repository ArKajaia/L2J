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
package org.l2jmobius.gameserver.model.hiddenquest;

import java.util.Collections;
import java.util.List;
import java.util.Map;

import org.l2jmobius.gameserver.model.StatSet;

/**
 * A hidden quest as loaded from {@code data/HiddenQuests.xml}: its secret condition, its messenger, its task and its rewards.
 * @author Mobius
 */
public class HiddenQuestDefinition
{
	private final int _id;
	private final String _name;
	private final int _messengerNpcId;
	private final HiddenTrigger _trigger;
	private final HiddenTaskType _taskType;
	private final StatSet _taskParams;
	private final Map<String, SpawnRole> _roles;
	private final List<EchoDefinition> _echoes;
	private final List<Landmark> _landmarks;
	private final Map<String, String> _texts;
	private final List<HiddenReward> _rewards;

	public HiddenQuestDefinition(int id, String name, int messengerNpcId, HiddenTrigger trigger, HiddenTaskType taskType, StatSet taskParams, Map<String, SpawnRole> roles, List<EchoDefinition> echoes, List<Landmark> landmarks, Map<String, String> texts, List<HiddenReward> rewards)
	{
		_id = id;
		_name = name;
		_messengerNpcId = messengerNpcId;
		_trigger = trigger;
		_taskType = taskType;
		_taskParams = taskParams;
		_roles = roles;
		_echoes = echoes;
		_landmarks = landmarks;
		_texts = texts;
		_rewards = rewards;
	}

	public int getId()
	{
		return _id;
	}

	public String getName()
	{
		return _name;
	}

	public int getMessengerNpcId()
	{
		return _messengerNpcId;
	}

	public HiddenTrigger getTrigger()
	{
		return _trigger;
	}

	public HiddenTaskType getTaskType()
	{
		return _taskType;
	}

	/**
	 * @return the task's own parameters (the attributes of the {@code <task>} element)
	 */
	public StatSet getTaskParams()
	{
		return _taskParams;
	}

	/**
	 * @param name a role name
	 * @return the role, {@code null} if the quest has none of that name
	 */
	public SpawnRole getRole(String name)
	{
		return _roles.get(name);
	}

	public Map<String, SpawnRole> getRoles()
	{
		return Collections.unmodifiableMap(_roles);
	}

	public List<EchoDefinition> getEchoes()
	{
		return Collections.unmodifiableList(_echoes);
	}

	public List<Landmark> getLandmarks()
	{
		return Collections.unmodifiableList(_landmarks);
	}

	/**
	 * @param key a text key ({@code greeting}, {@code offer}, {@code briefing}...)
	 * @param defaultValue the text to use when the quest has none
	 * @return the quest's text
	 */
	public String getText(String key, String defaultValue)
	{
		final String text = _texts.get(key);
		return text == null ? defaultValue : text;
	}

	public List<HiddenReward> getRewards()
	{
		return Collections.unmodifiableList(_rewards);
	}

	/**
	 * @return the task time limit in seconds, 0 for the configured default
	 */
	public int getTimeLimit()
	{
		return _taskParams.getInt("timeLimit", 0);
	}
}
