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
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

import org.w3c.dom.Document;
import org.w3c.dom.NamedNodeMap;
import org.w3c.dom.Node;

import org.l2jmobius.commons.util.IXmlReader;
import org.l2jmobius.commons.util.Rnd;
import org.l2jmobius.gameserver.model.StatSet;
import org.l2jmobius.gameserver.model.actor.enums.player.PlayerClass;
import org.l2jmobius.gameserver.model.actor.holders.npc.FakePlayerPvpBuild;
import org.l2jmobius.gameserver.model.actor.holders.npc.FakePlayerPvpBuild.Role;
import org.l2jmobius.gameserver.model.actor.holders.npc.FakePlayerPvpBuild.SkillCategory;
import org.l2jmobius.gameserver.model.actor.holders.npc.FakePlayerPvpCombo;
import org.l2jmobius.gameserver.model.actor.holders.npc.FakePlayerPvpGearTier;
import org.l2jmobius.gameserver.model.skill.holders.SkillHolder;

/**
 * Loads the roaming fake player gear kits and class builds from data/FakePlayerPvp.xml.
 */
public class FakePlayerPvpData implements IXmlReader
{
	private static final Logger LOGGER = Logger.getLogger(FakePlayerPvpData.class.getName());
	
	private final Map<String, List<FakePlayerPvpGearTier>> _kits = new HashMap<>();
	private final List<FakePlayerPvpBuild> _builds = new ArrayList<>();
	/** Buff list name -> (tier min level -> buffs), tiers sorted by level. */
	private final Map<String, List<Map.Entry<Integer, List<SkillHolder>>>> _buffs = new HashMap<>();
	private int _totalWeight;
	
	protected FakePlayerPvpData()
	{
		load();
	}
	
	@Override
	public synchronized void load()
	{
		_kits.clear();
		_builds.clear();
		_buffs.clear();
		_totalWeight = 0;
		parseDatapackFile("data/FakePlayerPvp.xml");
		
		// Drop builds that reference a kit that does not exist.
		_builds.removeIf(build ->
		{
			for (String kit : new String[]
			{
				build.getWeaponKit(),
				build.getArmorKit(),
				build.getJewelKit()
			})
			{
				if (!_kits.containsKey(kit))
				{
					LOGGER.warning(getClass().getSimpleName() + ": Build " + build.getName() + " uses unknown kit " + kit + ".");
					return true;
				}
			}
			
			if ((build.getBowKit() != null) && !_kits.containsKey(build.getBowKit()))
			{
				LOGGER.warning(getClass().getSimpleName() + ": Build " + build.getName() + " uses unknown bow kit " + build.getBowKit() + ".");
				return true;
			}
			
			if ((build.getPolearmKit() != null) && !_kits.containsKey(build.getPolearmKit()))
			{
				LOGGER.warning(getClass().getSimpleName() + ": Build " + build.getName() + " uses unknown polearm kit " + build.getPolearmKit() + ".");
				return true;
			}
			return false;
		});
		
		for (FakePlayerPvpBuild build : _builds)
		{
			_totalWeight += build.getWeight();
		}
		
		LOGGER.info(getClass().getSimpleName() + ": Loaded " + _kits.size() + " gear kits, " + _buffs.size() + " buff lists and " + _builds.size() + " builds.");
	}
	
	@Override
	public void parseDocument(Document document, File file)
	{
		forEach(document, "list", listNode ->
		{
			forEach(listNode, "kit", kitNode ->
			{
				final String name = parseString(kitNode.getAttributes(), "name");
				final List<FakePlayerPvpGearTier> tiers = new ArrayList<>();
				forEach(kitNode, "tier", tierNode -> tiers.add(new FakePlayerPvpGearTier(new StatSet(parseAttributes(tierNode)))));
				tiers.sort(Comparator.comparingInt(FakePlayerPvpGearTier::getMinLevel));
				_kits.put(name, tiers);
			});
			
			forEach(listNode, "buffs", buffsNode ->
			{
				final List<Map.Entry<Integer, List<SkillHolder>>> tiers = new ArrayList<>();
				forEach(buffsNode, "tier", tierNode ->
				{
					final List<SkillHolder> buffs = new ArrayList<>();
					for (String entry : tierNode.getTextContent().split(";"))
					{
						entry = entry.trim();
						if (entry.isEmpty())
						{
							continue;
						}
						
						final String[] idAndLevel = entry.split(",");
						buffs.add(new SkillHolder(Integer.parseInt(idAndLevel[0].trim()), idAndLevel.length > 1 ? Integer.parseInt(idAndLevel[1].trim()) : 1));
					}
					tiers.add(Map.entry(parseInteger(tierNode.getAttributes(), "minLevel"), buffs));
				});
				tiers.sort(Map.Entry.comparingByKey());
				_buffs.put(parseString(buffsNode.getAttributes(), "name"), tiers);
			});
			
			forEach(listNode, "build", buildNode ->
			{
				final NamedNodeMap attrs = buildNode.getAttributes();
				final String name = parseString(attrs, "name");
				final PlayerClass playerClass = PlayerClass.getPlayerClass(parseInteger(attrs, "classId"));
				if (playerClass == null)
				{
					LOGGER.warning(getClass().getSimpleName() + ": Build " + name + " has an unknown class id.");
					return;
				}
				
				final FakePlayerPvpBuild build = new FakePlayerPvpBuild(name, playerClass, parseEnum(attrs, Role.class, "role"), parseString(attrs, "weapon"), parseString(attrs, "armor"), parseString(attrs, "jewels", "JEWELS"), parseString(attrs, "bow", null), parseString(attrs, "polearm", null), parseString(attrs, "buffs", playerClass.isMage() ? "MAGE" : "FIGHTER"), Math.max(0, parseInteger(attrs, "weight", 1)), parseBoolean(attrs, "skillFighter", false));
				for (Node skillsNode = buildNode.getFirstChild(); skillsNode != null; skillsNode = skillsNode.getNextSibling())
				{
					if ("combo".equalsIgnoreCase(skillsNode.getNodeName()))
					{
						final NamedNodeMap comboAttrs = skillsNode.getAttributes();
						final FakePlayerPvpCombo combo = new FakePlayerPvpCombo(parseString(comboAttrs, "name", ""), parseBoolean(comboAttrs, "pvp", false), Math.max(0, Math.min(100, parseInteger(comboAttrs, "chance", 100))));
						forEach(skillsNode, "step", stepNode ->
						{
							final NamedNodeMap stepAttrs = stepNode.getAttributes();
							combo.addStep(new FakePlayerPvpCombo.Step(parseIds(parseString(stepAttrs, "skill")), parseBoolean(stepAttrs, "optional", false), parseBoolean(stepAttrs, "self", false), parseBoolean(stepAttrs, "behind", false), parseBoolean(stepAttrs, "disabledTarget", false)));
						});
						
						if (!combo.getSteps().isEmpty())
						{
							build.addCombo(combo);
						}
						continue;
					}
					
					final SkillCategory category;
					try
					{
						category = SkillCategory.valueOf(skillsNode.getNodeName().toUpperCase());
					}
					catch (IllegalArgumentException e)
					{
						continue;
					}
					
					for (String entry : skillsNode.getTextContent().split(","))
					{
						entry = entry.trim();
						if (entry.isEmpty())
						{
							continue;
						}
						
						build.addSkill(category, parseIds(entry));
					}
				}
				
				_builds.add(build);
			});
		});
	}
	
	/**
	 * @param value skill ids separated by "|"
	 * @return the ids
	 */
	private static int[] parseIds(String value)
	{
		final String[] ids = value.split("\\|");
		final int[] result = new int[ids.length];
		for (int i = 0; i < ids.length; i++)
		{
			result[i] = Integer.parseInt(ids[i].trim());
		}
		
		return result;
	}
	
	/**
	 * @param kit the kit name
	 * @param level a character level
	 * @return the highest tier of {@code kit} usable at {@code level}, or {@code null} if there is none
	 */
	public FakePlayerPvpGearTier getGear(String kit, int level)
	{
		final List<FakePlayerPvpGearTier> tiers = _kits.get(kit);
		if (tiers == null)
		{
			return null;
		}
		
		FakePlayerPvpGearTier result = null;
		for (FakePlayerPvpGearTier tier : tiers)
		{
			if (tier.getMinLevel() > level)
			{
				break;
			}
			result = tier;
		}
		
		return result;
	}
	
	/**
	 * @param name the buff list name
	 * @param level a character level
	 * @return every buff of the list's tiers up to {@code level}
	 */
	public List<SkillHolder> getBuffs(String name, int level)
	{
		final List<SkillHolder> result = new ArrayList<>();
		for (Map.Entry<Integer, List<SkillHolder>> tier : _buffs.getOrDefault(name, Collections.emptyList()))
		{
			if (tier.getKey() > level)
			{
				break;
			}
			result.addAll(tier.getValue());
		}
		
		return result;
	}
	
	/**
	 * @return a build picked at random by weight, or {@code null} if no build is loaded
	 */
	public FakePlayerPvpBuild getRandomBuild()
	{
		if (_totalWeight <= 0)
		{
			return _builds.isEmpty() ? null : _builds.get(Rnd.get(_builds.size()));
		}
		
		int roll = Rnd.get(_totalWeight);
		for (FakePlayerPvpBuild build : _builds)
		{
			roll -= build.getWeight();
			if (roll < 0)
			{
				return build;
			}
		}
		
		return _builds.get(_builds.size() - 1);
	}
	
	/**
	 * @param name a build name, case insensitive, spaces optional
	 * @return the build, or {@code null} if there is none with that name
	 */
	public FakePlayerPvpBuild getBuild(String name)
	{
		final String search = name.replace(" ", "").replace("'", "");
		for (FakePlayerPvpBuild build : _builds)
		{
			if (build.getName().replace(" ", "").replace("'", "").equalsIgnoreCase(search))
			{
				return build;
			}
		}
		
		return null;
	}
	
	public List<FakePlayerPvpBuild> getBuilds()
	{
		return Collections.unmodifiableList(_builds);
	}
	
	public static FakePlayerPvpData getInstance()
	{
		return SingletonHolder.INSTANCE;
	}
	
	private static class SingletonHolder
	{
		protected static final FakePlayerPvpData INSTANCE = new FakePlayerPvpData();
	}
}
