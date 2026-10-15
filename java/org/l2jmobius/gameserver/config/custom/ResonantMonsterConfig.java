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
package org.l2jmobius.gameserver.config.custom;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeMap;
import java.util.logging.Logger;

import org.l2jmobius.commons.util.ConfigReader;
import org.l2jmobius.gameserver.model.skill.AbnormalVisualEffect;

/**
 * Loads the Resonant monster configuration: a regular monster has {@link #SPAWN_CHANCE}% chance on spawn to become Resonant. Killing it raises the soul crystal of the killer and of every party member nearby by one stage, up to the stage the regular monsters of its level can
 * raise, or up to the {@link #RAID_STAGES} of the high levels, where it is a raid boss strength fight (see {@link org.l2jmobius.gameserver.managers.ResonantMonsterManager}). Low on HP, its soul tries to flee, and fake players join the fight at
 * {@link #FAKE_JOIN_HP_PERCENTS}.
 * @author Mobius
 */
public class ResonantMonsterConfig
{
	private static final Logger LOGGER = Logger.getLogger(ResonantMonsterConfig.class.getName());

	// File
	private static final String RESONANT_MONSTER_CONFIG_FILE = "./config/Custom/ResonantMonsters.ini";

	// Constants
	public static boolean ENABLED;
	public static double SPAWN_CHANCE;
	public static int RESPAWN_COOLDOWN;
	public static int MIN_LEVEL;
	public static int MAX_LEVEL;
	public static boolean ALLOW_IN_INSTANCES;
	public static boolean ALLOW_CHAMPIONS;
	public static Set<Integer> EXCLUDED_NPC_IDS = new HashSet<>();
	public static double HP_MULTIPLIER;
	public static int CHANCE;
	public static int MAX_CRYSTAL_STAGE;
	public static int FLEE_AT_HP_PERCENT;
	public static int FLEE_TIME;
	public static boolean HINT;
	public static AbnormalVisualEffect VISUAL_EFFECT;
	public static String TITLE_TAG;
	/** From this monster level on: the highest crystal stage that still grows, above what the regular monsters of that level raise. */
	public static TreeMap<Integer, Integer> RAID_STAGES = new TreeMap<>();
	public static int RAID_STAGE_CHANCE;
	public static int RAID_STRENGTH_PERCENT;
	public static int RAID_FLEE_TIME;
	public static String RAID_TITLE_TAG;
	/** HP percents at which a fake player joins the fight, highest first. */
	public static int[] FAKE_JOIN_HP_PERCENTS = new int[0];
	/** By how many HP percent points each of {@link #FAKE_JOIN_HP_PERCENTS} moves either way, rolled for each life. */
	public static int FAKE_JOIN_HP_VARIANCE;
	/** Chance (%) that nobody turns up at one of {@link #FAKE_JOIN_HP_PERCENTS}, rolled for each life. */
	public static int FAKE_JOIN_NO_SHOW_CHANCE;
	public static int FAKE_HELPER_CHANCE;
	public static int RIVAL_ATTACK_PLAYER_CHANCE;
	public static long RIVAL_KILL_COINS;
	public static boolean RIVAL_KILL_CACHE;
	public static int FAKE_LEAVE_DELAY;

	public static void load()
	{
		final ConfigReader config = new ConfigReader(RESONANT_MONSTER_CONFIG_FILE);
		ENABLED = config.getBoolean("ResonantEnabled", true);
		SPAWN_CHANCE = config.getDouble("ResonantSpawnChance", 1.0);
		RESPAWN_COOLDOWN = Math.max(0, config.getInt("ResonantRespawnCooldown", 1));
		MIN_LEVEL = config.getInt("ResonantMinLevel", 40);
		MAX_LEVEL = config.getInt("ResonantMaxLevel", 85);
		ALLOW_IN_INSTANCES = config.getBoolean("ResonantAllowInInstances", false);
		ALLOW_CHAMPIONS = config.getBoolean("ResonantAllowChampions", false);

		EXCLUDED_NPC_IDS.clear();
		for (String idStr : config.getString("ResonantExcludedNpcIds", "").split(","))
		{
			idStr = idStr.trim();
			if (!idStr.isEmpty())
			{
				try
				{
					EXCLUDED_NPC_IDS.add(Integer.parseInt(idStr));
				}
				catch (NumberFormatException e)
				{
					LOGGER.warning("Invalid ResonantExcludedNpcIds entry: " + idStr);
				}
			}
		}

		HP_MULTIPLIER = Math.max(0.1, config.getDouble("ResonantHpMultiplier", 2.0));
		CHANCE = Math.max(0, Math.min(100, config.getInt("ResonantChance", 100)));
		MAX_CRYSTAL_STAGE = Math.max(0, config.getInt("ResonantMaxCrystalStage", 11));
		FLEE_AT_HP_PERCENT = Math.max(0, Math.min(99, config.getInt("ResonantFleeAtHpPercent", 25)));
		FLEE_TIME = Math.max(1, config.getInt("ResonantFleeTime", 20));
		HINT = config.getBoolean("ResonantHint", true);

		final String effect = config.getString("ResonantVisualEffect", "MP_SHIELD").trim().toUpperCase();
		try
		{
			VISUAL_EFFECT = AbnormalVisualEffect.valueOf(effect);
		}
		catch (IllegalArgumentException e)
		{
			LOGGER.warning(ResonantMonsterConfig.class.getSimpleName() + ": Unknown ResonantVisualEffect " + effect + ", using NONE.");
			VISUAL_EFFECT = AbnormalVisualEffect.NONE;
		}

		TITLE_TAG = config.getString("ResonantTitleTag", "[Resonant]");

		RAID_STAGES = new TreeMap<>();
		for (String part : config.getString("ResonantRaidStages", "76:10,80:11").split(","))
		{
			final String[] pair = part.trim().split(":");
			if (pair.length != 2)
			{
				if (!part.isBlank())
				{
					LOGGER.warning("Invalid ResonantRaidStages entry: " + part);
				}
				continue;
			}

			try
			{
				RAID_STAGES.put(Integer.parseInt(pair[0].trim()), Math.max(0, Integer.parseInt(pair[1].trim())));
			}
			catch (NumberFormatException e)
			{
				LOGGER.warning("Invalid ResonantRaidStages entry: " + part);
			}
		}

		RAID_STAGE_CHANCE = Math.max(0, Math.min(100, config.getInt("ResonantRaidStageChance", 100)));
		RAID_STRENGTH_PERCENT = Math.max(0, config.getInt("ResonantRaidStrengthPercent", 50));
		RAID_FLEE_TIME = Math.max(1, config.getInt("ResonantRaidFleeTime", 40));
		RAID_TITLE_TAG = config.getString("ResonantRaidTitleTag", "[Resonant Boss]");

		final List<Integer> percents = new ArrayList<>();
		for (String part : config.getString("ResonantFakeJoinAtHpPercent", "70,30").split(","))
		{
			part = part.trim();
			if (part.isEmpty())
			{
				continue;
			}

			try
			{
				final int percent = Integer.parseInt(part);
				if ((percent > 0) && (percent < 100))
				{
					percents.add(percent);
				}
			}
			catch (NumberFormatException e)
			{
				LOGGER.warning("Invalid ResonantFakeJoinAtHpPercent entry: " + part);
			}
		}
		percents.sort(Comparator.reverseOrder());
		FAKE_JOIN_HP_PERCENTS = percents.stream().limit(30).mapToInt(Integer::intValue).toArray();

		FAKE_JOIN_HP_VARIANCE = Math.max(0, Math.min(30, config.getInt("ResonantFakeJoinHpVariance", 8)));
		FAKE_JOIN_NO_SHOW_CHANCE = Math.max(0, Math.min(100, config.getInt("ResonantFakeJoinNoShowChance", 25)));
		FAKE_HELPER_CHANCE = Math.max(0, Math.min(100, config.getInt("ResonantFakeHelperChance", 50)));
		RIVAL_ATTACK_PLAYER_CHANCE = Math.max(0, Math.min(100, config.getInt("ResonantRivalAttackPlayerChance", 30)));
		RIVAL_KILL_COINS = Math.max(0, config.getLong("ResonantRivalKillCoins", 300));
		RIVAL_KILL_CACHE = config.getBoolean("ResonantRivalKillCache", true);
		FAKE_LEAVE_DELAY = Math.max(0, config.getInt("ResonantFakeLeaveDelay", 30));
	}
}
