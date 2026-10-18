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
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.logging.Logger;

import org.w3c.dom.Document;
import org.w3c.dom.NamedNodeMap;
import org.w3c.dom.Node;

import org.l2jmobius.commons.util.IXmlReader;
import org.l2jmobius.gameserver.model.StatSet;
import org.l2jmobius.gameserver.model.actor.enums.creature.Race;
import org.l2jmobius.gameserver.model.dynamicquest.Eligibility;
import org.l2jmobius.gameserver.model.dynamicquest.NoticeBoardSite;
import org.l2jmobius.gameserver.model.dynamicquest.ObjectiveTemplate;
import org.l2jmobius.gameserver.model.dynamicquest.ObjectiveType;
import org.l2jmobius.gameserver.model.dynamicquest.QuestScope;
import org.l2jmobius.gameserver.model.dynamicquest.QuestTemplate;
import org.l2jmobius.gameserver.model.dynamicquest.QuestTrigger;
import org.l2jmobius.gameserver.model.dynamicquest.RewardDefinition;
import org.l2jmobius.gameserver.model.dynamicquest.RewardEntry;
import org.l2jmobius.gameserver.model.dynamicquest.StageTemplate;
import org.l2jmobius.gameserver.model.dynamicquest.Transition;
import org.l2jmobius.gameserver.model.dynamicquest.TransitionAction;
import org.l2jmobius.gameserver.model.dynamicquest.TransitionEvent;
import org.l2jmobius.gameserver.model.dynamicquest.TriggerType;
import org.l2jmobius.gameserver.model.hiddenquest.MonsterBand;
import org.l2jmobius.gameserver.model.hiddenquest.MonsterSet;
import org.l2jmobius.gameserver.model.interfaces.ILocational;

/**
 * Loads the Dynamic Quests from {@code data/DynamicQuests.xml}: the Notice Board towns, monster pools, reward and item tables, text pools and requester kinds first, then the quest templates built from them.
 * @author Mobius
 */
public class DynamicQuestData implements IXmlReader
{
	private static final Logger LOGGER = Logger.getLogger(DynamicQuestData.class.getName());

	/** The npc parameters of objectives, checked at startup and given dialogs by the script. */
	private static final String[] NPC_PARAMS =
	{
		"npc",
		"clueNpc",
		"nestNpc",
		"nodeNpc",
		"escortee",
		"target"
	};

	/** A kind of town npc that can ask for something: matched by its title or its type. */
	public static class RequesterRole
	{
		private final String _name;
		private final String _title;
		private final Set<String> _types;
		private final String _label;

		public RequesterRole(String name, String title, Set<String> types, String label)
		{
			_name = name;
			_title = title;
			_types = types;
			_label = label;
		}

		public String getName()
		{
			return _name;
		}

		/**
		 * @param title an npc's title
		 * @param type an npc's type
		 * @return {@code true} if such an npc has this role
		 */
		public boolean matches(String title, String type)
		{
			if ((_title != null) && (title != null) && title.toLowerCase().contains(_title.toLowerCase()))
			{
				return true;
			}
			return (_title == null) && _types.contains(type);
		}

		/**
		 * @return what such an npc is called next to its name ("Blacksmith")
		 */
		public String getLabel()
		{
			return _label;
		}
	}

	private volatile Map<Integer, QuestTemplate> _templates = Collections.emptyMap();
	private volatile Map<String, MonsterSet> _monsterPools = Collections.emptyMap();
	private volatile Map<String, List<RewardEntry>> _itemTables = Collections.emptyMap();
	private volatile Map<String, List<String>> _textPools = Collections.emptyMap();
	private volatile Map<String, RequesterRole> _requesters = Collections.emptyMap();
	private volatile List<NoticeBoardSite> _boards = Collections.emptyList();
	private volatile Set<Integer> _questNpcIds = Collections.emptySet();
	private int _noticeBoardNpcId = 900500;
	private int _heraldNpcId = 900501;

	// Filled while parsing.
	private Map<Integer, QuestTemplate> _loadingTemplates;
	private Map<String, MonsterSet> _loadingPools;
	private Map<String, List<RewardEntry>> _loadingTables;
	private Map<String, List<String>> _loadingTexts;
	private Map<String, RequesterRole> _loadingRequesters;
	private List<NoticeBoardSite> _loadingBoards;

	protected DynamicQuestData()
	{
		load();
	}

	@Override
	public synchronized void load()
	{
		_loadingTemplates = new LinkedHashMap<>();
		_loadingPools = new HashMap<>();
		_loadingTables = new HashMap<>();
		_loadingTexts = new HashMap<>();
		_loadingRequesters = new HashMap<>();
		_loadingBoards = new ArrayList<>();
		parseDatapackFile("data/DynamicQuests.xml");

		final Set<Integer> npcIds = new HashSet<>();
		for (QuestTemplate template : _loadingTemplates.values())
		{
			for (StageTemplate stage : template.getStages())
			{
				for (ObjectiveTemplate objective : stage.getObjectives())
				{
					for (String key : NPC_PARAMS)
					{
						final int npcId = objective.getParams().getInt(key, 0);
						if (npcId > 0)
						{
							npcIds.add(npcId);
						}
					}
				}
			}
		}

		_templates = Collections.unmodifiableMap(_loadingTemplates);
		_monsterPools = Collections.unmodifiableMap(_loadingPools);
		_itemTables = Collections.unmodifiableMap(_loadingTables);
		_textPools = Collections.unmodifiableMap(_loadingTexts);
		_requesters = Collections.unmodifiableMap(_loadingRequesters);
		_boards = Collections.unmodifiableList(_loadingBoards);
		_questNpcIds = Collections.unmodifiableSet(npcIds);
		_loadingTemplates = null;
		_loadingPools = null;
		_loadingTables = null;
		_loadingTexts = null;
		_loadingRequesters = null;
		_loadingBoards = null;
		LOGGER.info(getClass().getSimpleName() + ": Loaded " + _templates.size() + " dynamic quest templates, " + _monsterPools.size() + " monster pools, " + _itemTables.size() + " item tables and " + _boards.size() + " notice board towns.");
	}

	/**
	 * Checks every npc and item id the data references. Called once npc and item data are loaded.
	 */
	public void validate()
	{
		final NpcData npcData = NpcData.getInstance();
		final ItemData itemData = ItemData.getInstance();
		final Set<Integer> npcIds = new HashSet<>(_questNpcIds);
		npcIds.add(_noticeBoardNpcId);
		npcIds.add(_heraldNpcId);
		for (NoticeBoardSite site : _boards)
		{
			npcIds.add(site.getAnchorNpcId());
		}
		for (MonsterSet pool : _monsterPools.values())
		{
			for (MonsterBand band : pool.getBands())
			{
				npcIds.add(band.getNpcId());
			}
		}
		for (int npcId : npcIds)
		{
			if (npcData.getTemplate(npcId) == null)
			{
				LOGGER.warning(getClass().getSimpleName() + ": Missing npc " + npcId + ".");
			}
		}

		final Set<Integer> itemIds = new HashSet<>();
		for (List<RewardEntry> table : _itemTables.values())
		{
			for (RewardEntry entry : table)
			{
				itemIds.add(entry.getId());
			}
		}
		for (QuestTemplate template : _templates.values())
		{
			for (RewardEntry entry : template.getReward().getItems())
			{
				itemIds.add(entry.getId());
			}
		}
		for (int itemId : itemIds)
		{
			if (itemData.getTemplate(itemId) == null)
			{
				LOGGER.warning(getClass().getSimpleName() + ": Missing item " + itemId + ".");
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

			// Shared data first, so templates can use what is defined anywhere in the file.
			for (Node node = list.getFirstChild(); node != null; node = node.getNextSibling())
			{
				final NamedNodeMap attrs = node.getAttributes();
				switch (node.getNodeName())
				{
					case "settings":
					{
						_noticeBoardNpcId = parseInteger(attrs, "noticeBoardNpc", 900500);
						_heraldNpcId = parseInteger(attrs, "heraldNpc", 900501);
						break;
					}
					case "board":
					{
						_loadingBoards.add(new NoticeBoardSite(parseString(attrs, "town"), parseInteger(attrs, "anchor")));
						break;
					}
					case "monsterPool":
					{
						final List<MonsterBand> bands = new ArrayList<>();
						forEach(node, "band", band ->
						{
							final NamedNodeMap bandAttrs = band.getAttributes();
							bands.add(new MonsterBand(parseInteger(bandAttrs, "minLevel"), parseInteger(bandAttrs, "npcId"), parseInteger(bandAttrs, "tier", 0)));
						});
						final String name = parseString(attrs, "name");
						_loadingPools.put(name, new MonsterSet(name, bands));
						break;
					}
					case "rewardTable":
					case "itemTable":
					{
						_loadingTables.put(parseString(attrs, "name"), parseEntries(node));
						break;
					}
					case "textPool":
					{
						final List<String> lines = new ArrayList<>();
						forEach(node, "entry", entry -> lines.add(clean(entry.getTextContent())));
						_loadingTexts.put(parseString(attrs, "name"), lines);
						break;
					}
					case "requester":
					{
						final Set<String> types = new HashSet<>();
						for (String type : parseString(attrs, "types", "").split(","))
						{
							if (!type.trim().isEmpty())
							{
								types.add(type.trim());
							}
						}
						final String name = parseString(attrs, "name");
						_loadingRequesters.put(name, new RequesterRole(name, parseString(attrs, "title", null), types, parseString(attrs, "label", "")));
						break;
					}
				}
			}

			for (Node node = list.getFirstChild(); node != null; node = node.getNextSibling())
			{
				if (!"template".equalsIgnoreCase(node.getNodeName()))
				{
					continue;
				}
				try
				{
					final QuestTemplate template = parseTemplate(node);
					if ((template != null) && (_loadingTemplates.putIfAbsent(template.getId(), template) != null))
					{
						LOGGER.warning(getClass().getSimpleName() + ": Duplicate template id " + template.getId() + " - ignoring the duplicate.");
					}
				}
				catch (Exception e)
				{
					LOGGER.warning(getClass().getSimpleName() + ": Could not load a template from " + file.getName() + ": " + e.getMessage());
				}
			}
		}
	}

	private static String clean(String text)
	{
		return text.trim().replaceAll("\\s+", " ");
	}

	private List<RewardEntry> parseEntries(Node node)
	{
		final List<RewardEntry> entries = new ArrayList<>();
		forEach(node, child -> "reward".equals(child.getNodeName()) || "item".equals(child.getNodeName()), entry ->
		{
			final NamedNodeMap attrs = entry.getAttributes();
			entries.add(new RewardEntry(parseInteger(attrs, "id"), parseLong(attrs, "count", 1L), parseInteger(attrs, "minLevel", 1), parseInteger(attrs, "maxLevel", 999), parseDouble(attrs, "chance", 100d), parseBoolean(attrs, "scaled", true)));
		});
		return entries;
	}

	private QuestTemplate parseTemplate(Node node)
	{
		final NamedNodeMap attrs = node.getAttributes();
		if (!parseBoolean(attrs, "enabled", true))
		{
			return null;
		}
		final int id = parseInteger(attrs, "id");
		final String name = parseString(attrs, "name");
		final String category = parseString(attrs, "category", "Quest");
		final QuestScope scope = parseEnum(attrs, QuestScope.class, "scope", QuestScope.PERSONAL);
		final String requester = parseString(attrs, "requester", null);
		if ((requester != null) && !_loadingRequesters.containsKey(requester))
		{
			throw new IllegalArgumentException("template " + id + ": unknown requester '" + requester + "'");
		}

		final List<QuestTrigger> triggers = new ArrayList<>();
		Eligibility eligibility = new Eligibility(1, 999, 1, 9, Collections.emptySet(), Collections.emptySet(), Collections.emptySet(), false, 0, 0);
		final List<StageTemplate> stages = new ArrayList<>();
		RewardDefinition reward = new RewardDefinition(0, 0, 0, Collections.emptyList());
		final Map<String, String> texts = new HashMap<>();

		for (Node child = node.getFirstChild(); child != null; child = child.getNextSibling())
		{
			final NamedNodeMap childAttrs = child.getAttributes();
			switch (child.getNodeName())
			{
				case "trigger":
				{
					triggers.add(new QuestTrigger(parseEnum(childAttrs, TriggerType.class, "type"), new StatSet(parseAttributes(child))));
					break;
				}
				case "eligibility":
				{
					final Set<Integer> classes = new HashSet<>();
					for (String value : parseString(childAttrs, "classes", "").split(","))
					{
						if (!value.trim().isEmpty())
						{
							classes.add(Integer.parseInt(value.trim()));
						}
					}
					final Set<Race> races = new HashSet<>();
					for (String value : parseString(childAttrs, "races", "").split(","))
					{
						if (!value.trim().isEmpty())
						{
							races.add(Race.valueOf(value.trim().toUpperCase()));
						}
					}
					final Set<String> towns = new HashSet<>();
					for (String value : parseString(childAttrs, "towns", "").split(","))
					{
						if (!value.trim().isEmpty())
						{
							towns.add(value.trim().toLowerCase());
						}
					}
					eligibility = new Eligibility(parseInteger(childAttrs, "minLevel", 1), parseInteger(childAttrs, "maxLevel", 999), parseInteger(childAttrs, "partyMin", 1), parseInteger(childAttrs, "partyMax", 9), classes, races, towns, parseBoolean(childAttrs, "nightOnly", false), parseInteger(childAttrs, "cooldown", 0), parseInteger(childAttrs, "dailyLimit", 0));
					break;
				}
				case "stage":
				{
					stages.add(parseStage(id, child));
					break;
				}
				case "reward":
				{
					final List<RewardEntry> items = new ArrayList<>();
					final String tableName = parseString(childAttrs, "table", null);
					if (tableName != null)
					{
						final List<RewardEntry> table = _loadingTables.get(tableName);
						if (table == null)
						{
							throw new IllegalArgumentException("template " + id + ": unknown reward table '" + tableName + "'");
						}
						items.addAll(table);
					}
					items.addAll(parseEntries(child));
					reward = new RewardDefinition(parseDouble(childAttrs, "exp", 0d), parseDouble(childAttrs, "sp", 0.1), parseLong(childAttrs, "adenaPerLevel", 0L), items);
					break;
				}
				case "text":
				{
					texts.put(parseString(childAttrs, "key"), clean(child.getTextContent()));
					break;
				}
				case "context":
				{
					// Seeded picks of the quest, stored as texts "context.<key>" = pool name.
					texts.put("context." + parseString(childAttrs, "key"), parseString(childAttrs, "pool"));
					break;
				}
			}
		}

		if (triggers.isEmpty() || stages.isEmpty())
		{
			throw new IllegalArgumentException("template " + id + " needs a trigger and a stage");
		}
		final List<Integer> entryStages = new ArrayList<>();
		for (String value : parseString(attrs, "entryStages", "").split(","))
		{
			if (!value.trim().isEmpty())
			{
				final int stage = Integer.parseInt(value.trim()) - 1;
				if ((stage < 0) || (stage >= stages.size()))
				{
					throw new IllegalArgumentException("template " + id + ": entry stage " + value.trim() + " doesn't exist");
				}
				entryStages.add(stage);
			}
		}
		return new QuestTemplate(id, name, category, scope, requester, triggers, eligibility, stages, reward, texts, entryStages);
	}

	private StageTemplate parseStage(int templateId, Node node)
	{
		final NamedNodeMap attrs = node.getAttributes();
		final List<ObjectiveTemplate> objectives = new ArrayList<>();
		final List<Transition> transitions = new ArrayList<>();
		String text = null;
		for (Node child = node.getFirstChild(); child != null; child = child.getNextSibling())
		{
			final NamedNodeMap childAttrs = child.getAttributes();
			switch (child.getNodeName())
			{
				case "text":
				{
					text = clean(child.getTextContent());
					break;
				}
				case "objective":
				{
					final Map<String, Object> params = parseAttributes(child);
					final ObjectiveType type = parseEnum(childAttrs, ObjectiveType.class, "type");
					objectives.add(new ObjectiveTemplate(type, new StatSet(params), parseBoolean(childAttrs, "optional", false), parseBoolean(childAttrs, "hidden", false), parseString(childAttrs, "text", null)));
					break;
				}
				case "on":
				{
					transitions.add(new Transition(parseEnum(childAttrs, TransitionEvent.class, "event"), parseEnum(childAttrs, TransitionAction.class, "action"), parseInteger(childAttrs, "target", 0), parseDouble(childAttrs, "chance", 100d), parseInteger(childAttrs, "max", 1), parseInteger(childAttrs, "extraTime", 0), parseString(childAttrs, "text", null)));
					break;
				}
			}
		}
		if (objectives.isEmpty())
		{
			throw new IllegalArgumentException("template " + templateId + ": a stage without objectives");
		}
		return new StageTemplate(parseString(attrs, "name", "Stage"), parseInteger(attrs, "timeLimit", 0), parseBoolean(attrs, "failOnDeath", false), text, objectives, transitions);
	}

	// ---------------------------------------------------------------------------------------------
	// Getters
	// ---------------------------------------------------------------------------------------------

	public QuestTemplate getTemplate(int id)
	{
		return _templates.get(id);
	}

	public Collection<QuestTemplate> getTemplates()
	{
		return _templates.values();
	}

	public MonsterSet getMonsterPool(String name)
	{
		return _monsterPools.get(name);
	}

	public List<RewardEntry> getItemTable(String name)
	{
		return _itemTables.get(name);
	}

	public List<String> getTextPool(String name)
	{
		return _textPools.get(name);
	}

	public RequesterRole getRequester(String name)
	{
		return _requesters.get(name);
	}

	public List<NoticeBoardSite> getBoards()
	{
		return _boards;
	}

	/**
	 * @param town a town name
	 * @return its board, {@code null} if it has none
	 */
	public NoticeBoardSite getBoard(String town)
	{
		for (NoticeBoardSite site : _boards)
		{
			if (site.getTown().equalsIgnoreCase(town))
			{
				return site;
			}
		}
		return null;
	}

	/**
	 * @param location a place
	 * @param minDistance boards closer than this are skipped
	 * @return the nearest placed board, {@code null} if none
	 */
	public NoticeBoardSite getNearestBoard(ILocational location, int minDistance)
	{
		NoticeBoardSite nearest = null;
		double nearestDistance = Double.MAX_VALUE;
		for (NoticeBoardSite site : _boards)
		{
			if (site.getLocation() == null)
			{
				continue;
			}
			final double dx = site.getLocation().getX() - location.getX();
			final double dy = site.getLocation().getY() - location.getY();
			final double distance = Math.sqrt((dx * dx) + (dy * dy));
			if ((distance >= minDistance) && (distance < nearestDistance))
			{
				nearest = site;
				nearestDistance = distance;
			}
		}
		return nearest;
	}

	public int getNoticeBoardNpcId()
	{
		return _noticeBoardNpcId;
	}

	public boolean isNoticeBoard(int npcId)
	{
		return npcId == _noticeBoardNpcId;
	}

	public int getHeraldNpcId()
	{
		return _heraldNpcId;
	}

	/**
	 * @return every npc id the objectives spawn
	 */
	public Set<Integer> getQuestNpcIds()
	{
		return _questNpcIds;
	}

	public static DynamicQuestData getInstance()
	{
		return SingletonHolder.INSTANCE;
	}

	private static class SingletonHolder
	{
		protected static final DynamicQuestData INSTANCE = new DynamicQuestData();
	}
}
