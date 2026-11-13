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
package org.l2jmobius.gameserver.managers;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.l2jmobius.commons.util.Rnd;
import org.l2jmobius.gameserver.config.custom.FakePlayerPvpConfig;
import org.l2jmobius.gameserver.data.xml.EnchantSkillGroupsData;
import org.l2jmobius.gameserver.data.xml.SkillData;
import org.l2jmobius.gameserver.model.actor.holders.npc.FakePlayerPvpBuild;
import org.l2jmobius.gameserver.model.actor.holders.npc.FakePlayerPvpBuild.SkillCategory;
import org.l2jmobius.gameserver.model.skill.Skill;
import org.l2jmobius.gameserver.model.skill.holders.EnchantSkillGroup;
import org.l2jmobius.gameserver.model.skill.holders.EnchantSkillLearn;

/**
 * Enchants the skills of a fake player of level 76 or more (FakePvpSkillEnchant* in config/Custom/FakePlayerPvp.ini), as far as its wealth (see {@link FakePlayerPvpEnchant#getWealth()}) paid for, like a player that enchants what matters most to it first:
 * <ul>
 * <li>its wealth gives it a number of enchant attempts, more at higher levels: none for a poor one (FakePvpSkillEnchantMinWealth or less), twice the attempts of wealth 1 for the richest;</li>
 * <li>it works through the &lt;enchant&gt; list of its build (data/FakePlayerPvp.xml), most important first: the damage skills it uses most and the chance of its stuns and debuffs (Bluff, Stun Shot, Shield Stun...). An entry with alternatives ("263:1|263:7", Deadly
 * Blow Power or Duel) is one of them, picked for each fake player. A build without a list enchants the power of its first attack skills and the chance of its debuffs;</li>
 * <li>it goes in rounds: a third of the way on every skill of the list, then half, two thirds, five sixths and all the way, so its first skills are the most enchanted and a rich one has all of them high. The first three of the list, its main targets, are
 * two rounds ahead (two thirds of the way in the first round);</li>
 * <li>every attempt succeeds with the retail rate of that enchant level for its level (data/EnchantSkillGroups.xml); a failed one only costs the attempt. An enchant with a rate of 1% or less at its level is out of its reach, so a level 76 enchants
 * little;</li>
 * <li>like players, it can only enchant a skill it has learned at its last level (the one the enchant routes start from), and only one route of each skill.</li>
 * </ul>
 * The skills it uses are then the enchanted ones (power, chance and magic level), see {@link FakePlayerPvpFactory}.
 */
public class FakePlayerPvpSkillEnchant
{
	/** The rounds: how far every skill of the list is taken in each, a share of its enchant levels. */
	private static final double[] ROUNDS =
	{
		1 / 3.0,
		1 / 2.0,
		2 / 3.0,
		5 / 6.0,
		1
	};
	/** At or below this rate (in %) an enchant is out of reach at the fake player's level. */
	private static final int MIN_RATE = 1;
	/** The first skills of the list are its main targets: they are a round ahead of the others (two thirds of the way in the first round, all the way in the third). */
	private static final int MAIN_TARGETS = 3;
	private static final int MAIN_ROUNDS_AHEAD = 2;
	/** How many attack skills of a build without an enchant list get their power enchanted. */
	private static final int AUTO_ATTACK_SKILLS = 2;

	/**
	 * A skill being enchanted on one route.
	 */
	private static class Target
	{
		private final int _skillId;
		private final int _route;
		private final EnchantSkillGroup _group;
		private final int _max;
		private int _enchant;
		private boolean _stuck;

		private Target(int skillId, int route, EnchantSkillGroup group, int max)
		{
			_skillId = skillId;
			_route = route;
			_group = group;
			_max = max;
		}
	}

	private FakePlayerPvpSkillEnchant()
	{
	}

	/**
	 * @param build the build
	 * @param level the fake player's level
	 * @param wealth its wealth
	 * @param learned the skills it has learned (id -> level)
	 * @return the skills it enchanted (id -> enchanted level, e.g. 358 -> 112 for Bluff +12 Chance), in the order of its list; empty for none
	 */
	public static Map<Integer, Integer> roll(FakePlayerPvpBuild build, int level, double wealth, Map<Integer, Integer> learned)
	{
		if (!FakePlayerPvpConfig.SKILL_ENCHANT_ENABLED || (level < FakePlayerPvpConfig.SKILL_ENCHANT_MIN_LEVEL))
		{
			return Collections.emptyMap();
		}

		int attempts = getAttempts(level, wealth);
		if (attempts <= 0)
		{
			return Collections.emptyMap();
		}

		final List<Target> targets = build.getEnchants().isEmpty() ? getAutoTargets(build, learned) : getTargets(build, learned);
		if (targets.isEmpty())
		{
			return Collections.emptyMap();
		}

		ROUNDS: for (int round = 0; round < ROUNDS.length; round++)
		{
			for (int i = 0; i < targets.size(); i++)
			{
				final Target target = targets.get(i);
				final int goal = (int) Math.round(target._max * ROUNDS[Math.min(ROUNDS.length - 1, i < MAIN_TARGETS ? round + MAIN_ROUNDS_AHEAD : round)]);
				while (!target._stuck && (target._enchant < goal))
				{
					final int rate = target._group.getEnchantGroupDetails().get(target._enchant).getRate(level);
					if ((rate <= MIN_RATE) || (SkillData.getInstance().getSkill(target._skillId, getLevel(target._route, target._enchant + 1)) == null))
					{
						target._stuck = true;
						break;
					}

					if (attempts-- <= 0)
					{
						break ROUNDS;
					}

					if (Rnd.get(100) < rate)
					{
						target._enchant++;
					}
				}
			}
		}

		final Map<Integer, Integer> result = new LinkedHashMap<>();
		for (Target target : targets)
		{
			if (target._enchant > 0)
			{
				result.put(target._skillId, getLevel(target._route, target._enchant));
			}
		}

		return result;
	}

	/**
	 * @param level a fake player level
	 * @param wealth its wealth
	 * @return its enchant attempts: FakePvpSkillEnchantAttempts for its level, scaled by how much richer than FakePvpSkillEnchantMinWealth it is (as much at wealth 1, none at the minimum)
	 */
	public static int getAttempts(int level, double wealth)
	{
		final double minWealth = FakePlayerPvpConfig.SKILL_ENCHANT_MIN_WEALTH;
		return (int) Math.round(FakePlayerPvpConfig.getTierChance(FakePlayerPvpConfig.SKILL_ENCHANT_ATTEMPTS, level) * Math.max(0, (wealth - minWealth) / (1 - minWealth)));
	}

	/**
	 * @param route an enchant route
	 * @param enchant an enchant level on it
	 * @return the skill level of that enchant (route 1 +12 = 112)
	 */
	private static int getLevel(int route, int enchant)
	{
		return (route * 100) + enchant;
	}

	/**
	 * @param build the build
	 * @param learned the skills the fake player has learned
	 * @return the skills of the build's enchant list it can enchant, one alternative picked for each entry
	 */
	private static List<Target> getTargets(FakePlayerPvpBuild build, Map<Integer, Integer> learned)
	{
		final List<Target> result = new ArrayList<>();
		final Set<Integer> taken = new HashSet<>();
		for (int[][] alternatives : build.getEnchants())
		{
			final List<Target> usable = new ArrayList<>(alternatives.length);
			for (int[] enchant : alternatives)
			{
				if (!taken.contains(enchant[0]))
				{
					final Target target = getTarget(enchant[0], enchant[1], learned);
					if (target != null)
					{
						usable.add(target);
					}
				}
			}

			if (!usable.isEmpty())
			{
				final Target target = usable.get(Rnd.get(usable.size()));
				taken.add(target._skillId);
				result.add(target);
			}
		}

		return result;
	}

	/**
	 * For a build without an enchant list: the power of its first attack skills, and the chance of its debuffs and of the skills that stop a player (peel).
	 * @param build the build
	 * @param learned the skills the fake player has learned
	 * @return the skills it can enchant that way
	 */
	private static List<Target> getAutoTargets(FakePlayerPvpBuild build, Map<Integer, Integer> learned)
	{
		final List<Target> result = new ArrayList<>();
		final Set<Integer> taken = new HashSet<>();
		int attackSkills = 0;
		for (int[] alternatives : build.getSkills(SkillCategory.ATTACK))
		{
			final int skillId = getLearned(alternatives, learned);
			if ((skillId > 0) && taken.add(skillId))
			{
				final Target target = getBestRoute(skillId, learned, true);
				if (target != null)
				{
					result.add(target);
					if (++attackSkills >= AUTO_ATTACK_SKILLS)
					{
						break;
					}
				}
			}
		}

		for (SkillCategory category : new SkillCategory[]
		{
			SkillCategory.DEBUFF,
			SkillCategory.PEEL
		})
		{
			for (int[] alternatives : build.getSkills(category))
			{
				final int skillId = getLearned(alternatives, learned);
				if ((skillId > 0) && taken.add(skillId))
				{
					final Target target = getBestRoute(skillId, learned, false);
					if (target != null)
					{
						result.add(target);
					}
				}
			}
		}

		return result;
	}

	/**
	 * @param alternatives skill ids of which the first learned one is used
	 * @param learned the skills the fake player has learned
	 * @return the first learned one, 0 for none
	 */
	private static int getLearned(int[] alternatives, Map<Integer, Integer> learned)
	{
		for (int skillId : alternatives)
		{
			if (learned.containsKey(skillId))
			{
				return skillId;
			}
		}

		return 0;
	}

	/**
	 * @param skillId a skill
	 * @param learned the skills the fake player has learned
	 * @param power {@code true} for the route that raises its power the most, {@code false} for the one that raises its success rate the most
	 * @return that route of the skill, {@code null} if none raises it
	 */
	private static Target getBestRoute(int skillId, Map<Integer, Integer> learned, boolean power)
	{
		final EnchantSkillLearn enchantLearn = EnchantSkillGroupsData.getInstance().getSkillEnchantmentBySkillId(skillId);
		final Skill base = SkillData.getInstance().getSkill(skillId, learned.getOrDefault(skillId, 0));
		if ((enchantLearn == null) || (base == null))
		{
			return null;
		}

		Target best = null;
		double bestGain = 0;
		for (int route : enchantLearn.getAllRoutes())
		{
			final Target target = getTarget(skillId, route, learned);
			if (target == null)
			{
				continue;
			}

			final Skill top = SkillData.getInstance().getSkill(skillId, getLevel(route, target._max));
			if (top == null)
			{
				continue;
			}

			// Duel routes raise the power against players only: compare the power in PvP, which is what these fights are about.
			final double gain = power ? top.getPower(true, false) - base.getPower(true, false) : top.getActivateRate() - base.getActivateRate();
			if (gain > bestGain)
			{
				best = target;
				bestGain = gain;
			}
		}

		return best;
	}

	/**
	 * @param skillId a skill
	 * @param route one of its enchant routes
	 * @param learned the skills the fake player has learned
	 * @return the skill to enchant on that route, {@code null} if it hasn't learned it at the level enchanting starts from or the skill has no such route
	 */
	private static Target getTarget(int skillId, int route, Map<Integer, Integer> learned)
	{
		final Integer level = learned.get(skillId);
		final EnchantSkillLearn enchantLearn = EnchantSkillGroupsData.getInstance().getSkillEnchantmentBySkillId(skillId);
		if ((level == null) || (enchantLearn == null) || (level != enchantLearn.getBaseLevel()))
		{
			return null;
		}

		final EnchantSkillGroup group = enchantLearn.getRouteGroup(route);
		if ((group == null) || group.getEnchantGroupDetails().isEmpty() || (SkillData.getInstance().getSkill(skillId, getLevel(route, 1)) == null))
		{
			return null;
		}

		return new Target(skillId, route, group, group.getEnchantGroupDetails().size());
	}

	/**
	 * @param enchanted what {@link #roll} returned
	 * @return the enchanted skills for a GM to read, e.g. "Bluff +12, Deadly Blow +20", "none" for none
	 */
	public static String describe(Map<Integer, Integer> enchanted)
	{
		if (enchanted.isEmpty())
		{
			return "none";
		}

		final StringBuilder sb = new StringBuilder();
		for (Map.Entry<Integer, Integer> entry : enchanted.entrySet())
		{
			final Skill skill = SkillData.getInstance().getSkill(entry.getKey(), entry.getValue());
			if (sb.length() > 0)
			{
				sb.append(", ");
			}
			sb.append(skill != null ? skill.getName() : String.valueOf(entry.getKey())).append(" +").append(entry.getValue() % 100).append(" (route ").append(entry.getValue() / 100).append(')');
		}

		return sb.toString();
	}
}
