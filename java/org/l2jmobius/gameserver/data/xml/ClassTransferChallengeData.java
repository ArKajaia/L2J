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
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.logging.Logger;

import org.w3c.dom.Document;
import org.w3c.dom.NamedNodeMap;
import org.w3c.dom.Node;

import org.l2jmobius.commons.util.IXmlReader;
import org.l2jmobius.gameserver.model.Location;
import org.l2jmobius.gameserver.model.StatSet;
import org.l2jmobius.gameserver.model.actor.Player;
import org.l2jmobius.gameserver.model.actor.enums.player.PlayerClass;
import org.l2jmobius.gameserver.model.classtransfer.ChallengeDefinition;
import org.l2jmobius.gameserver.model.classtransfer.ChallengeMapping;
import org.l2jmobius.gameserver.model.classtransfer.ChallengeMapping.MappingType;
import org.l2jmobius.gameserver.model.classtransfer.ChallengeReward;
import org.l2jmobius.gameserver.model.classtransfer.ChallengeReward.RewardType;
import org.l2jmobius.gameserver.model.classtransfer.ChallengeSpawnHolder;
import org.l2jmobius.gameserver.model.classtransfer.ObjectiveDefinition;
import org.l2jmobius.gameserver.model.classtransfer.ObjectiveType;
import org.l2jmobius.gameserver.model.classtransfer.SpawnVariant;
import org.l2jmobius.gameserver.model.classtransfer.TransferStage;
import org.l2jmobius.gameserver.model.classtransfer.TwistDefinition;
import org.l2jmobius.gameserver.model.classtransfer.TwistDefinition.TwistType;
import org.l2jmobius.gameserver.model.hotzone.HotzoneModifier;

/**
 * Loads the Alternative Class Transfer Challenges from {@code data/ClassTransferChallenges/*.xml} and resolves which challenge a player takes at a stage.
 * @author Mobius
 */
public class ClassTransferChallengeData implements IXmlReader
{
	private static final Logger LOGGER = Logger.getLogger(ClassTransferChallengeData.class.getName());

	private volatile Map<String, ChallengeDefinition> _challenges = new ConcurrentHashMap<>();
	private volatile List<ChallengeMapping> _mappings = new CopyOnWriteArrayList<>();
	private volatile Set<Integer> _challengeItemIds = Collections.emptySet();

	// Filled while parsing, swapped in when the whole directory is loaded, so a reload never exposes a half-loaded set.
	private Map<String, ChallengeDefinition> _loadingChallenges;
	private List<ChallengeMapping> _loadingMappings;

	protected ClassTransferChallengeData()
	{
		load();
	}

	@Override
	public synchronized void load()
	{
		_loadingChallenges = new ConcurrentHashMap<>();
		_loadingMappings = new CopyOnWriteArrayList<>();
		parseDatapackDirectory("data/ClassTransferChallenges", false);

		// Drop mappings that point to an unknown challenge or one of another stage.
		final List<ChallengeMapping> validMappings = new CopyOnWriteArrayList<>();
		for (ChallengeMapping mapping : _loadingMappings)
		{
			final ChallengeDefinition challenge = _loadingChallenges.get(mapping.getChallengeId());
			if (challenge == null)
			{
				LOGGER.warning(getClass().getSimpleName() + ": Mapping points to unknown challenge '" + mapping.getChallengeId() + "' - ignored.");
			}
			else if (challenge.getStage() != mapping.getStage())
			{
				LOGGER.warning(getClass().getSimpleName() + ": Mapping for " + mapping.getStage() + " points to challenge '" + mapping.getChallengeId() + "' of stage " + challenge.getStage() + " - ignored.");
			}
			else
			{
				validMappings.add(mapping);
			}
		}

		final Set<Integer> itemIds = new HashSet<>();
		for (ChallengeDefinition challenge : _loadingChallenges.values())
		{
			for (ObjectiveDefinition objective : challenge.getObjectives())
			{
				if (((objective.getType() == ObjectiveType.COLLECT) || (objective.getType() == ObjectiveType.USE_ITEM)) && (objective.getParams().getInt("itemId", 0) > 0))
				{
					itemIds.add(objective.getParams().getInt("itemId"));
				}
			}
		}

		_challenges = _loadingChallenges;
		_mappings = validMappings;
		_challengeItemIds = Collections.unmodifiableSet(itemIds);
		_loadingChallenges = null;
		_loadingMappings = null;
		LOGGER.info(getClass().getSimpleName() + ": Loaded " + _challenges.size() + " class transfer challenges and " + _mappings.size() + " mappings.");
	}

	/**
	 * Checks every NPC id the challenges reference against the loaded NPC templates. Called once NPC data is available.
	 */
	public void validateNpcs()
	{
		final NpcData npcData = NpcData.getInstance();
		for (ChallengeDefinition challenge : _challenges.values())
		{
			final Set<Integer> npcIds = new HashSet<>();
			if (challenge.getGuideNpcId() > 0)
			{
				npcIds.add(challenge.getGuideNpcId());
			}
			for (ObjectiveDefinition objective : challenge.getObjectives())
			{
				for (ChallengeSpawnHolder spawn : objective.getSpawns())
				{
					npcIds.add(spawn.getNpcId());
				}
				for (List<ChallengeSpawnHolder> wave : objective.getWaves())
				{
					for (ChallengeSpawnHolder spawn : wave)
					{
						npcIds.add(spawn.getNpcId());
					}
				}
				final int npcId = objective.getParams().getInt("npcId", 0);
				if (npcId > 0)
				{
					npcIds.add(npcId);
				}
			}
			for (int npcId : npcIds)
			{
				if (npcData.getTemplate(npcId) == null)
				{
					LOGGER.warning(getClass().getSimpleName() + ": Challenge '" + challenge.getId() + "' references missing NPC template " + npcId + ".");
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

			for (Node node = list.getFirstChild(); node != null; node = node.getNextSibling())
			{
				try
				{
					if ("challenge".equalsIgnoreCase(node.getNodeName()))
					{
						final ChallengeDefinition challenge = parseChallenge(node);
						if (_loadingChallenges.putIfAbsent(challenge.getId(), challenge) != null)
						{
							LOGGER.warning(getClass().getSimpleName() + ": Duplicate challenge id '" + challenge.getId() + "' in " + file.getName() + " - ignoring the duplicate.");
						}
					}
					else if ("mapping".equalsIgnoreCase(node.getNodeName()))
					{
						final NamedNodeMap attrs = node.getAttributes();
						final TransferStage stage = parseEnum(attrs, TransferStage.class, "stage");
						final MappingType type = parseEnum(attrs, MappingType.class, "type");
						final String value = parseString(attrs, "value", null);
						final String challengeId = parseString(attrs, "challenge");
						if ((stage == null) || (type == null) || (challengeId == null))
						{
							LOGGER.warning(getClass().getSimpleName() + ": Mapping in " + file.getName() + " needs a valid stage, type and challenge - ignored.");
							continue;
						}
						if ((type != MappingType.GENERIC) && (value == null))
						{
							LOGGER.warning(getClass().getSimpleName() + ": " + type + " mapping to '" + challengeId + "' in " + file.getName() + " has no value - ignored.");
							continue;
						}
						_loadingMappings.add(new ChallengeMapping(stage, type, value == null ? null : value.trim().toUpperCase(), challengeId));
					}
				}
				catch (Exception e)
				{
					LOGGER.warning(getClass().getSimpleName() + ": Failed parsing " + node.getNodeName() + " in " + file.getName() + ": " + e);
				}
			}
		}
	}

	private ChallengeDefinition parseChallenge(Node challengeNode)
	{
		final NamedNodeMap attrs = challengeNode.getAttributes();
		final String id = parseString(attrs, "id");
		final String name = parseString(attrs, "name");
		final TransferStage stage = parseEnum(attrs, TransferStage.class, "stage");
		if ((id == null) || (name == null) || (stage == null))
		{
			throw new IllegalArgumentException("challenge needs an id, a name and a valid stage");
		}
		final String difficulty = parseString(attrs, "difficulty", "Normal");
		final int duration = parseInteger(attrs, "duration", 0);
		final int parTime = parseInteger(attrs, "parTime", 0);
		final int minLevel = parseInteger(attrs, "minLevel", 0);
		final Set<Integer> allowedClassIds = new HashSet<>();
		for (String classId : parseString(attrs, "classes", "").split(","))
		{
			if (!classId.isBlank())
			{
				allowedClassIds.add(Integer.parseInt(classId.trim()));
			}
		}

		String description = "";
		Location entry = null;
		int guideNpcId = 0;
		Location guideLoc = null;
		final List<HotzoneModifier> omens = new ArrayList<>();
		final List<ObjectiveDefinition> objectives = new ArrayList<>();
		final List<TwistDefinition> twists = new ArrayList<>();
		final List<ChallengeReward> rewards = new ArrayList<>();

		for (Node child = challengeNode.getFirstChild(); child != null; child = child.getNextSibling())
		{
			final NamedNodeMap childAttrs = child.getAttributes();
			switch (child.getNodeName())
			{
				case "description":
				{
					description = child.getTextContent().trim().replaceAll("\\s+", " ");
					break;
				}
				case "entry":
				{
					entry = parseLocation(childAttrs);
					break;
				}
				case "guide":
				{
					guideNpcId = parseInteger(childAttrs, "npcId");
					guideLoc = parseLocation(childAttrs);
					break;
				}
				case "omens":
				{
					forEach(child, "omen", omenNode ->
					{
						final HotzoneModifier omen = parseEnum(omenNode.getAttributes(), HotzoneModifier.class, "name");
						if (omen != null)
						{
							omens.add(omen);
						}
					});
					break;
				}
				case "objectives":
				{
					forEach(child, "objective", objectiveNode -> objectives.add(parseObjective(objectiveNode, objectives.size())));
					break;
				}
				case "twists":
				{
					forEach(child, "twist", twistNode ->
					{
						final NamedNodeMap twistAttrs = twistNode.getAttributes();
						final TwistType twistType = parseEnum(twistAttrs, TwistType.class, "type");
						if (twistType != null)
						{
							twists.add(new TwistDefinition(twistType, parseInteger(twistAttrs, "chance", 100), parseInteger(twistAttrs, "afterObjective", 1), parseInteger(twistAttrs, "arenaCoins", 0)));
						}
					});
					break;
				}
				case "rewards":
				{
					for (Node rewardNode = child.getFirstChild(); rewardNode != null; rewardNode = rewardNode.getNextSibling())
					{
						final NamedNodeMap rewardAttrs = rewardNode.getAttributes();
						switch (rewardNode.getNodeName())
						{
							case "item":
							{
								rewards.add(new ChallengeReward(RewardType.ITEM, parseInteger(rewardAttrs, "id"), parseLong(rewardAttrs, "count", 1L), parseLong(rewardAttrs, "parBonus", 0L)));
								break;
							}
							case "arenaCoins":
							{
								rewards.add(new ChallengeReward(RewardType.ARENA_COINS, 0, parseLong(rewardAttrs, "count", 1L), parseLong(rewardAttrs, "parBonus", 0L)));
								break;
							}
							case "sealedCache":
							{
								rewards.add(new ChallengeReward(RewardType.SEALED_CACHE, 0, 1, 0));
								break;
							}
							case "exp":
							{
								rewards.add(new ChallengeReward(RewardType.EXP, 0, parseLong(rewardAttrs, "count", 0L), parseLong(rewardAttrs, "parBonus", 0L)));
								break;
							}
							case "sp":
							{
								rewards.add(new ChallengeReward(RewardType.SP, 0, parseLong(rewardAttrs, "count", 0L), parseLong(rewardAttrs, "parBonus", 0L)));
								break;
							}
						}
					}
					break;
				}
			}
		}

		if (entry == null)
		{
			throw new IllegalArgumentException("challenge '" + id + "' has no <entry>");
		}
		if (objectives.isEmpty())
		{
			throw new IllegalArgumentException("challenge '" + id + "' has no objectives");
		}

		return new ChallengeDefinition(id, name, stage, difficulty, description, duration, parTime, minLevel, allowedClassIds, entry, guideNpcId, guideLoc, omens, objectives, twists, rewards);
	}

	private ObjectiveDefinition parseObjective(Node objectiveNode, int index)
	{
		final NamedNodeMap attrs = objectiveNode.getAttributes();
		final ObjectiveType type = parseEnum(attrs, ObjectiveType.class, "type");
		if (type == null)
		{
			throw new IllegalArgumentException("objective " + (index + 1) + " has no valid type");
		}
		final String text = parseString(attrs, "text", type.name());
		final StatSet params = new StatSet(parseAttributes(objectiveNode));
		final List<ChallengeSpawnHolder> spawns = new ArrayList<>();
		final List<List<ChallengeSpawnHolder>> waves = new ArrayList<>();
		for (Node child = objectiveNode.getFirstChild(); child != null; child = child.getNextSibling())
		{
			if ("spawn".equalsIgnoreCase(child.getNodeName()))
			{
				spawns.add(parseSpawn(child));
			}
			else if ("wave".equalsIgnoreCase(child.getNodeName()))
			{
				final List<ChallengeSpawnHolder> wave = new ArrayList<>();
				forEach(child, "spawn", spawnNode -> wave.add(parseSpawn(spawnNode)));
				waves.add(wave);
			}
		}
		return new ObjectiveDefinition(index, type, text, params, spawns, waves);
	}

	private ChallengeSpawnHolder parseSpawn(Node spawnNode)
	{
		final NamedNodeMap attrs = spawnNode.getAttributes();
		return new ChallengeSpawnHolder(parseInteger(attrs, "npcId"), parseInteger(attrs, "count", 1), parseInteger(attrs, "x"), parseInteger(attrs, "y"), parseInteger(attrs, "z"), parseInteger(attrs, "heading", 0), parseInteger(attrs, "radius", 0), parseInteger(attrs, "championTier", 0), parseEnum(attrs, SpawnVariant.class, "variant", SpawnVariant.NONE), parseInteger(attrs, "waves", 1), parseBoolean(attrs, "arenaBuffs", false), parseInteger(attrs, "enrageSeconds", 0));
	}

	private Location parseLocation(NamedNodeMap attrs)
	{
		return new Location(parseInteger(attrs, "x"), parseInteger(attrs, "y"), parseInteger(attrs, "z"), parseInteger(attrs, "heading", 0));
	}

	/**
	 * Resolves the challenge a player takes at a stage: the player's class, then each parent class, then race, then FIGHTER/MAGE, then the stage's generic challenge.
	 * @param player the challenger
	 * @param stage the transfer stage
	 * @return the challenge, or {@code null} when nothing is mapped for this player
	 */
	public ChallengeDefinition resolve(Player player, TransferStage stage)
	{
		final PlayerClass playerClass = player.getPlayerClass();

		// Exact class, then parent classes.
		for (PlayerClass current = playerClass; current != null; current = current.getParent())
		{
			final ChallengeDefinition challenge = find(player, stage, MappingType.CLASS, String.valueOf(current.getId()));
			if (challenge != null)
			{
				return challenge;
			}
		}

		ChallengeDefinition challenge = find(player, stage, MappingType.RACE, playerClass.getRace().name());
		if (challenge != null)
		{
			return challenge;
		}

		challenge = find(player, stage, MappingType.ARCHETYPE, playerClass.isMage() ? "MAGE" : "FIGHTER");
		if (challenge != null)
		{
			return challenge;
		}

		return find(player, stage, MappingType.GENERIC, null);
	}

	private ChallengeDefinition find(Player player, TransferStage stage, MappingType type, String value)
	{
		for (ChallengeMapping mapping : _mappings)
		{
			if ((mapping.getStage() != stage) || (mapping.getType() != type))
			{
				continue;
			}
			if ((value != null) && !value.equals(mapping.getValue()))
			{
				continue;
			}

			final ChallengeDefinition challenge = _challenges.get(mapping.getChallengeId());
			if ((challenge != null) && (challenge.getAllowedClassIds().isEmpty() || challenge.getAllowedClassIds().contains(player.getPlayerClass().getId())))
			{
				return challenge;
			}
		}
		return null;
	}

	public ChallengeDefinition getChallenge(String id)
	{
		return _challenges.get(id);
	}

	/**
	 * @return every item a challenge hands out (COLLECT and USE_ITEM {@code itemId}); these only exist inside a trial
	 */
	public Set<Integer> getChallengeItemIds()
	{
		return _challengeItemIds;
	}

	public Collection<ChallengeDefinition> getChallenges()
	{
		return _challenges.values();
	}

	public static ClassTransferChallengeData getInstance()
	{
		return SingletonHolder.INSTANCE;
	}

	private static class SingletonHolder
	{
		protected static final ClassTransferChallengeData INSTANCE = new ClassTransferChallengeData();
	}
}
