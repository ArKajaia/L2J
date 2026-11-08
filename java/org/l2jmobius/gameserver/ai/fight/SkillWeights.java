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
package org.l2jmobius.gameserver.ai.fight;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;

/**
 * How much each part of a skill's value counts for a role (config/Custom/FakeSkillWeights.ini). The scorer and the reflexes read them; the self-play tuning only changes this table.
 * @param damage weight of damage per second
 * @param control weight of crowd control (land chance x time held x what that time is worth)
 * @param setup weight of debuffs that raise later damage
 * @param cooldownCost weight of the cost of spending a long cooldown now
 * @param risk weight of the chance of being interrupted
 * @param raceMargin the kill race is won when time to kill is below this share of time to die
 * @param burstReflexTtd the burst reflex fires when time to die is below this many seconds
 * @param noise how far values stray at skill level 0 (none at level 1)
 * @param planDepth how many steps ahead a skilled fake player plans (0 for none)
 */
public record SkillWeights(double damage, double control, double setup, double cooldownCost, double risk, double raceMargin, double burstReflexTtd, double noise, int planDepth)
{
	public static final SkillWeights DEFAULT = new SkillWeights(1.0, 1.0, 0.6, 1.0, 1.0, 0.75, 3.0, 0.35, 3);
	/** The roles of fake player builds (FakePlayerPvpBuild.Role). */
	public static final List<String> ROLES = List.of("FIGHTER", "TANK", "DAGGER", "ARCHER", "MAGE");
	
	private static volatile Map<String, SkillWeights> _table = parse(List.of());
	private static volatile Map<String, SkillWeights> _tableB = _table;
	
	/**
	 * @param lines the lines of a weights file: ROLE.key = value, # for comments
	 * @return the weights of every role: its defaults, with the keys the lines set
	 */
	public static Map<String, SkillWeights> parse(List<String> lines)
	{
		final Map<String, SkillWeights> table = new HashMap<>();
		for (String role : ROLES)
		{
			table.put(role, switch (role)
			{
				case "MAGE", "ARCHER" -> DEFAULT.with("risk", 1.3);
				case "TANK" -> DEFAULT.with("raceMargin", 0.6);
				default -> DEFAULT;
			});
		}
		return apply(table, lines, false);
	}
	
	/**
	 * @param base the weights to start from
	 * @param lines the lines of a weights file
	 * @param brainB {@code true} to apply only the lines starting with "B." (without it), {@code false} for only the others
	 * @return the weights with those lines applied
	 */
	private static Map<String, SkillWeights> apply(Map<String, SkillWeights> base, List<String> lines, boolean brainB)
	{
		final Map<String, SkillWeights> table = new HashMap<>(base);
		for (String raw : lines)
		{
			String line = raw.trim();
			if (line.startsWith("B.") != brainB)
			{
				continue;
			}
			if (brainB)
			{
				line = line.substring(2);
			}
			final int eq = line.indexOf('=');
			final int dot = line.indexOf('.');
			if (line.startsWith("#") || (eq < 0) || (dot < 0) || (dot > eq))
			{
				continue;
			}
			final String role = line.substring(0, dot).trim().toUpperCase();
			final String key = line.substring(dot + 1, eq).trim();
			final SkillWeights weights = table.get(role);
			if (weights == null)
			{
				continue;
			}
			try
			{
				table.put(role, weights.with(key, Double.parseDouble(line.substring(eq + 1).trim())));
			}
			catch (NumberFormatException e)
			{
				// Not a number: the line is skipped.
			}
		}
		return Map.copyOf(table);
	}
	
	/**
	 * Replaces the weights in use.
	 * @param lines the lines of the weights file
	 */
	public static void load(List<String> lines)
	{
		final Map<String, SkillWeights> table = parse(lines);
		_tableB = apply(table, lines, true);
		_table = table;
	}
	
	/**
	 * @param role a role name
	 * @return its weights, {@link #DEFAULT} for an unknown role
	 */
	public static SkillWeights of(String role)
	{
		return of(role, false);
	}
	
	/**
	 * @param role a role name
	 * @param brainB {@code true} for brain B's weights: brain A's with the "B." lines on top (the self-play tuning tries a change on brain B against brain A)
	 * @return its weights, {@link #DEFAULT} for an unknown role
	 */
	public static SkillWeights of(String role, boolean brainB)
	{
		return (brainB ? _tableB : _table).getOrDefault(role, DEFAULT);
	}
	
	/**
	 * @param key a weight name (damage, control, setup, cooldownCost, risk, raceMargin, burstReflexTtd, noise, planDepth)
	 * @param value its new value
	 * @return these weights with that one changed, the same weights for an unknown key
	 */
	public SkillWeights with(String key, double value)
	{
		final BiFunction<SkillWeights, Double, SkillWeights> change = switch (key)
		{
			case "damage" -> (w, v) -> new SkillWeights(v, w.control, w.setup, w.cooldownCost, w.risk, w.raceMargin, w.burstReflexTtd, w.noise, w.planDepth);
			case "control" -> (w, v) -> new SkillWeights(w.damage, v, w.setup, w.cooldownCost, w.risk, w.raceMargin, w.burstReflexTtd, w.noise, w.planDepth);
			case "setup" -> (w, v) -> new SkillWeights(w.damage, w.control, v, w.cooldownCost, w.risk, w.raceMargin, w.burstReflexTtd, w.noise, w.planDepth);
			case "cooldownCost" -> (w, v) -> new SkillWeights(w.damage, w.control, w.setup, v, w.risk, w.raceMargin, w.burstReflexTtd, w.noise, w.planDepth);
			case "risk" -> (w, v) -> new SkillWeights(w.damage, w.control, w.setup, w.cooldownCost, v, w.raceMargin, w.burstReflexTtd, w.noise, w.planDepth);
			case "raceMargin" -> (w, v) -> new SkillWeights(w.damage, w.control, w.setup, w.cooldownCost, w.risk, v, w.burstReflexTtd, w.noise, w.planDepth);
			case "burstReflexTtd" -> (w, v) -> new SkillWeights(w.damage, w.control, w.setup, w.cooldownCost, w.risk, w.raceMargin, v, w.noise, w.planDepth);
			case "noise" -> (w, v) -> new SkillWeights(w.damage, w.control, w.setup, w.cooldownCost, w.risk, w.raceMargin, w.burstReflexTtd, v, w.planDepth);
			case "planDepth" -> (w, v) -> new SkillWeights(w.damage, w.control, w.setup, w.cooldownCost, w.risk, w.raceMargin, w.burstReflexTtd, w.noise, (int) Math.round(v));
			default -> (w, v) -> w;
		};
		return change.apply(this, value);
	}
}
