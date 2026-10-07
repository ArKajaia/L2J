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
package org.l2jmobius.gameserver.data.xml;

import java.io.File;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

import org.w3c.dom.Document;
import org.w3c.dom.NamedNodeMap;
import org.w3c.dom.Node;

import org.l2jmobius.commons.util.IXmlReader;
import org.l2jmobius.gameserver.model.Location;
import org.l2jmobius.gameserver.model.StatSet;
import org.l2jmobius.gameserver.model.hiddenquest.EchoDefinition;
import org.l2jmobius.gameserver.model.hiddenquest.EchoDefinition.EchoGimmick;
import org.l2jmobius.gameserver.model.hiddenquest.HiddenQuestDefinition;
import org.l2jmobius.gameserver.model.hiddenquest.HiddenReward;
import org.l2jmobius.gameserver.model.hiddenquest.HiddenReward.RewardType;
import org.l2jmobius.gameserver.model.hiddenquest.HiddenTaskType;
import org.l2jmobius.gameserver.model.hiddenquest.HiddenTrigger;
import org.l2jmobius.gameserver.model.hiddenquest.HiddenTriggerType;
import org.l2jmobius.gameserver.model.hiddenquest.Landmark;
import org.l2jmobius.gameserver.model.hiddenquest.MonsterBand;
import org.l2jmobius.gameserver.model.hiddenquest.MonsterSet;
import org.l2jmobius.gameserver.model.hiddenquest.SpawnRole;

/**
 * Loads the Hidden Quests from {@code data/HiddenQuests.xml}: the monster sets and reward sets first, then the quests that use them.
 * @author Mobius
 */
public class HiddenQuestData implements IXmlReader
{
	private static final Logger LOGGER = Logger.getLogger(HiddenQuestData.class.getName());

	private volatile Map<Integer, HiddenQuestDefinition> _quests = Collections.emptyMap();

	// Filled while parsing.
	private Map<Integer, HiddenQuestDefinition> _loadingQuests;
	private Map<String, MonsterSet> _monsterSets;
	private Map<String, List<HiddenReward>> _rewardSets;

	protected HiddenQuestData()
	{
		load();
	}

	@Override
	public synchronized void load()
	{
		_loadingQuests = new LinkedHashMap<>();
		_monsterSets = new HashMap<>();
		_rewardSets = new HashMap<>();
		parseDatapackFile("data/HiddenQuests.xml");
		_quests = Collections.unmodifiableMap(_loadingQuests);
		_loadingQuests = null;
		_monsterSets = null;
		_rewardSets = null;
		LOGGER.info(getClass().getSimpleName() + ": Loaded " + _quests.size() + " hidden quests.");
	}

	/**
	 * Checks every NPC and item id the quests reference. Called once NPC and item data are loaded.
	 */
	public void validate()
	{
		final NpcData npcData = NpcData.getInstance();
		final ItemData itemData = ItemData.getInstance();
		for (HiddenQuestDefinition quest : _quests.values())
		{
			final List<Integer> npcIds = new ArrayList<>();
			npcIds.add(quest.getMessengerNpcId());
			for (String key : new String[]
			{
				"siteNpcId",
				"npcId",
				"escorteeId",
				"stopId",
				"totemId",
				"apparitionId",
				"wispId",
				"cacheId"
			})
			{
				final int npcId = quest.getTaskParams().getInt(key, 0);
				if (npcId > 0)
				{
					npcIds.add(npcId);
				}
			}
			for (SpawnRole role : quest.getRoles().values())
			{
				for (MonsterBand band : role.getSet().getBands())
				{
					npcIds.add(band.getNpcId());
				}
			}
			for (int npcId : npcIds)
			{
				if (npcData.getTemplate(npcId) == null)
				{
					LOGGER.warning(getClass().getSimpleName() + ": Quest " + quest.getId() + " references missing NPC " + npcId + ".");
				}
			}
			for (HiddenReward reward : quest.getRewards())
			{
				if ((reward.getType() == RewardType.ITEM) && (itemData.getTemplate(reward.getId()) == null))
				{
					LOGGER.warning(getClass().getSimpleName() + ": Quest " + quest.getId() + " rewards missing item " + reward.getId() + ".");
				}
			}
		}
	}

	@Override
	public void parseDocument(Document document, File file)
	{
		for (Node list = document.getFirstChild(); list != null; list = list.getNextSibling())
		{
			if (!"list".equalsIgnoreCase(list.getNodeName()))
			{
				continue;
			}

			// Sets first, so quests can use sets defined anywhere in the file.
			for (Node node = list.getFirstChild(); node != null; node = node.getNextSibling())
			{
				if ("monsterSet".equalsIgnoreCase(node.getNodeName()))
				{
					final String name = parseString(node.getAttributes(), "name");
					final List<MonsterBand> bands = new ArrayList<>();
					forEach(node, "band", band ->
					{
						final NamedNodeMap attrs = band.getAttributes();
						bands.add(new MonsterBand(parseInteger(attrs, "minLevel"), parseInteger(attrs, "npcId"), parseInteger(attrs, "tier", 0)));
					});
					_monsterSets.put(name, new MonsterSet(name, bands));
				}
				else if ("rewardSet".equalsIgnoreCase(node.getNodeName()))
				{
					_rewardSets.put(parseString(node.getAttributes(), "name"), parseRewards(node));
				}
			}

			for (Node node = list.getFirstChild(); node != null; node = node.getNextSibling())
			{
				if (!"quest".equalsIgnoreCase(node.getNodeName()))
				{
					continue;
				}

				try
				{
					final HiddenQuestDefinition quest = parseQuest(node);
					if (quest != null)
					{
						if (_loadingQuests.putIfAbsent(quest.getId(), quest) != null)
						{
							LOGGER.warning(getClass().getSimpleName() + ": Duplicate hidden quest id " + quest.getId() + " - ignoring the duplicate.");
						}
					}
				}
				catch (Exception e)
				{
					LOGGER.warning(getClass().getSimpleName() + ": Could not load a hidden quest from " + file.getName() + ": " + e.getMessage());
				}
			}
		}
	}

	private HiddenQuestDefinition parseQuest(Node node)
	{
		final NamedNodeMap attrs = node.getAttributes();
		final int id = parseInteger(attrs, "id");
		final String name = parseString(attrs, "name");
		final int messenger = parseInteger(attrs, "messenger");
		final boolean nightOnly = parseBoolean(attrs, "nightOnly", false);
		if (!parseBoolean(attrs, "enabled", true))
		{
			return null;
		}

		HiddenTrigger trigger = null;
		HiddenTaskType taskType = null;
		StatSet taskParams = new StatSet();
		final Map<String, SpawnRole> roles = new HashMap<>();
		final List<EchoDefinition> echoes = new ArrayList<>();
		final List<Landmark> landmarks = new ArrayList<>();
		final Map<String, String> texts = new HashMap<>();
		final List<HiddenReward> rewards = new ArrayList<>();

		for (Node child = node.getFirstChild(); child != null; child = child.getNextSibling())
		{
			final NamedNodeMap childAttrs = child.getAttributes();
			switch (child.getNodeName())
			{
				case "trigger":
				{
					final HiddenTriggerType type = parseEnum(childAttrs, HiddenTriggerType.class, "type");
					final String towns = parseString(childAttrs, "towns", null);
					int[] townIds = null;
					if (towns != null)
					{
						final String[] split = towns.split(",");
						townIds = new int[split.length];
						for (int i = 0; i < split.length; i++)
						{
							townIds[i] = Integer.parseInt(split[i].trim());
						}
					}
					trigger = new HiddenTrigger(type, parseLong(childAttrs, "count", 1L), parseString(childAttrs, "word", null), townIds);
					if ((type == HiddenTriggerType.KILL_NAMED) && (parseString(childAttrs, "word", null) == null))
					{
						throw new IllegalArgumentException("quest " + id + ": KILL_NAMED needs a word");
					}
					break;
				}
				case "task":
				{
					taskParams = new StatSet(parseAttributes(child));
					taskType = parseEnum(childAttrs, HiddenTaskType.class, "type");
					break;
				}
				case "role":
				{
					final String roleName = parseString(childAttrs, "name");
					final String setName = parseString(childAttrs, "set");
					final MonsterSet set = _monsterSets.get(setName);
					if (set == null)
					{
						throw new IllegalArgumentException("quest " + id + ": unknown monster set '" + setName + "'");
					}
					roles.put(roleName, new SpawnRole(roleName, set, parseInteger(childAttrs, "count", 1), parseInteger(childAttrs, "tierBonus", 0), parseString(childAttrs, "title", null)));
					break;
				}
				case "echo":
				{
					echoes.add(new EchoDefinition(parseString(childAttrs, "name"), parseEnum(childAttrs, EchoGimmick.class, "gimmick", EchoGimmick.NONE), parseInteger(childAttrs, "tier", -1), parseString(childAttrs, "line", null)));
					break;
				}
				case "landmark":
				{
					final Location location = new Location(parseInteger(childAttrs, "x"), parseInteger(childAttrs, "y"), parseInteger(childAttrs, "z"));
					landmarks.add(new Landmark(parseString(childAttrs, "name"), location, parseString(childAttrs, "riddle"), parseString(childAttrs, "hint", "")));
					break;
				}
				case "text":
				{
					texts.put(parseString(childAttrs, "key"), child.getTextContent().trim().replaceAll("\\s+", " "));
					break;
				}
				case "rewards":
				{
					final String setName = parseString(childAttrs, "set", null);
					if (setName != null)
					{
						final List<HiddenReward> set = _rewardSets.get(setName);
						if (set == null)
						{
							throw new IllegalArgumentException("quest " + id + ": unknown reward set '" + setName + "'");
						}
						rewards.addAll(set);
					}
					rewards.addAll(parseRewards(child));
					break;
				}
			}
		}

		if ((trigger == null) || (taskType == null))
		{
			throw new IllegalArgumentException("quest " + id + " needs a trigger and a task");
		}
		return new HiddenQuestDefinition(id, name, messenger, nightOnly, trigger, taskType, taskParams, roles, echoes, landmarks, texts, rewards);
	}

	private List<HiddenReward> parseRewards(Node node)
	{
		final List<HiddenReward> rewards = new ArrayList<>();
		forEach(node, "reward", reward ->
		{
			final NamedNodeMap attrs = reward.getAttributes();
			rewards.add(new HiddenReward(parseEnum(attrs, RewardType.class, "type"), parseInteger(attrs, "minLevel", 1), parseInteger(attrs, "maxLevel", 999), parseInteger(attrs, "id", 0), parseLong(attrs, "count", 1L), parseString(attrs, "value", null)));
		});
		return rewards;
	}

	/**
	 * @param id a quest id
	 * @return the quest, {@code null} if unknown or disabled
	 */
	public HiddenQuestDefinition getQuest(int id)
	{
		return _quests.get(id);
	}

	public Collection<HiddenQuestDefinition> getQuests()
	{
		return _quests.values();
	}

	public static HiddenQuestData getInstance()
	{
		return SingletonHolder.INSTANCE;
	}

	private static class SingletonHolder
	{
		protected static final HiddenQuestData INSTANCE = new HiddenQuestData();
	}
}
