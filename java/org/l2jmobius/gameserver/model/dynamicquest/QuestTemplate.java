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
import java.util.Map;

/**
 * A dynamic quest as written in {@code data/DynamicQuests.xml}: its triggers, who can take it, its stages and how it pays. Every offer of it is rolled from a seed into a concrete quest.
 * @author Mobius
 */
public class QuestTemplate
{
	/** Texts under this prefix name the pool of a context pick. */
	public static final String CONTEXT_PREFIX = "context.";

	private final int _id;
	private final String _name;
	private final String _category;
	private final QuestScope _scope;
	private final String _requester;
	private final List<QuestTrigger> _triggers;
	private final Eligibility _eligibility;
	private final List<StageTemplate> _stages;
	private final RewardDefinition _reward;
	private final Map<String, String> _texts;
	private final List<Integer> _entryStages;
	private final boolean _contact;

	public QuestTemplate(int id, String name, String category, QuestScope scope, String requester, List<QuestTrigger> triggers, Eligibility eligibility, List<StageTemplate> stages, RewardDefinition reward, Map<String, String> texts, List<Integer> entryStages)
	{
		this(id, name, category, scope, requester, triggers, eligibility, stages, reward, texts, entryStages, false);
	}

	public QuestTemplate(int id, String name, String category, QuestScope scope, String requester, List<QuestTrigger> triggers, Eligibility eligibility, List<StageTemplate> stages, RewardDefinition reward, Map<String, String> texts, List<Integer> entryStages, boolean contact)
	{
		_contact = contact;
		_id = id;
		_name = name;
		_category = category;
		_scope = scope;
		_requester = requester;
		_triggers = Collections.unmodifiableList(triggers);
		_eligibility = eligibility;
		_stages = Collections.unmodifiableList(stages);
		_reward = reward;
		_texts = Collections.unmodifiableMap(texts);
		_entryStages = Collections.unmodifiableList(entryStages);
	}

	/**
	 * @return the stages (0 = the first) a quest can start at, one picked by its seed; empty to start at the first
	 */
	public List<Integer> getEntryStages()
	{
		return _entryStages;
	}

	/**
	 * @return {@code true} if its first stage is the meeting with a contact at the hunting ground ({@code <contact>}): the quest's real work and its clock start
	 *         once the owner got there and talked to them
	 */
	public boolean hasContact()
	{
		return _contact;
	}

	/**
	 * @return the stage a quest starts its work at when no entry stage is picked: the first after the meeting with the contact, if any
	 */
	public int getFirstWorkStage()
	{
		return _contact ? 1 : 0;
	}

	public int getId()
	{
		return _id;
	}

	/**
	 * @return the name, with placeholders
	 */
	public String getName()
	{
		return _name;
	}

	public String getCategory()
	{
		return _category;
	}

	public QuestScope getScope()
	{
		return _scope;
	}

	/**
	 * @return the kind of town npc whose request this is (blacksmith, merchant, warehouse, townsfolk), {@code null} if none
	 */
	public String getRequester()
	{
		return _requester;
	}

	public List<QuestTrigger> getTriggers()
	{
		return _triggers;
	}

	/**
	 * @param type a trigger type
	 * @return the quest's trigger of that type, {@code null} if it has none
	 */
	public QuestTrigger getTrigger(TriggerType type)
	{
		for (QuestTrigger trigger : _triggers)
		{
			if (trigger.getType() == type)
			{
				return trigger;
			}
		}
		return null;
	}

	public Eligibility getEligibility()
	{
		return _eligibility;
	}

	public List<StageTemplate> getStages()
	{
		return _stages;
	}

	public RewardDefinition getReward()
	{
		return _reward;
	}

	/**
	 * @return the keys of its {@code <context key pool>} picks (each drawn from its own seed, so their order doesn't matter)
	 */
	public List<String> getContextKeys()
	{
		final List<String> keys = new ArrayList<>();
		for (String key : _texts.keySet())
		{
			if (key.startsWith(CONTEXT_PREFIX))
			{
				keys.add(key.substring(CONTEXT_PREFIX.length()));
			}
		}
		return keys;
	}

	/**
	 * @param key a text key
	 * @param defaultValue the text when the quest has none
	 * @return the text, with placeholders
	 */
	public String getText(String key, String defaultValue)
	{
		final String text = _texts.get(key);
		return text != null ? text : defaultValue;
	}
}
