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
package org.l2jmobius.gameserver.model.classtransfer;

import java.util.Collections;
import java.util.List;
import java.util.Set;

import org.l2jmobius.gameserver.model.Location;
import org.l2jmobius.gameserver.model.hotzone.HotzoneModifier;

/**
 * A class transfer challenge loaded from {@code data/ClassTransferChallenges/*.xml}.
 * @author Mobius
 */
public class ChallengeDefinition
{
	private final String _id;
	private final String _name;
	private final TransferStage _stage;
	private final String _difficulty;
	private final String _description;
	private final int _duration;
	private final int _parTime;
	private final int _minLevel;
	private final Set<Integer> _allowedClassIds;
	private final Location _entry;
	private final int _guideNpcId;
	private final Location _guideLoc;
	private final List<HotzoneModifier> _omens;
	private final List<ObjectiveDefinition> _objectives;
	private final List<TwistDefinition> _twists;
	private final List<ChallengeReward> _rewards;

	public ChallengeDefinition(String id, String name, TransferStage stage, String difficulty, String description, int duration, int parTime, int minLevel, Set<Integer> allowedClassIds, Location entry, int guideNpcId, Location guideLoc, List<HotzoneModifier> omens, List<ObjectiveDefinition> objectives, List<TwistDefinition> twists, List<ChallengeReward> rewards)
	{
		_id = id;
		_name = name;
		_stage = stage;
		_difficulty = difficulty;
		_description = description;
		_duration = duration;
		_parTime = parTime;
		_minLevel = minLevel;
		_allowedClassIds = Collections.unmodifiableSet(allowedClassIds);
		_entry = entry;
		_guideNpcId = guideNpcId;
		_guideLoc = guideLoc;
		_omens = Collections.unmodifiableList(omens);
		_objectives = Collections.unmodifiableList(objectives);
		_twists = Collections.unmodifiableList(twists);
		_rewards = Collections.unmodifiableList(rewards);
	}

	public String getId()
	{
		return _id;
	}

	public String getName()
	{
		return _name;
	}

	public TransferStage getStage()
	{
		return _stage;
	}

	public String getDifficulty()
	{
		return _difficulty;
	}

	public String getDescription()
	{
		return _description;
	}

	/**
	 * @return time limit in seconds
	 */
	public int getDuration()
	{
		return _duration;
	}

	/**
	 * @return seconds within which a clear earns the par-time reward bonus (0 = no par time)
	 */
	public int getParTime()
	{
		return _parTime;
	}

	/**
	 * @return extra minimum level on top of the Class Master tier level (0 = tier level only)
	 */
	public int getMinLevel()
	{
		return _minLevel;
	}

	/**
	 * @return class ids allowed to take this challenge (empty = any class the mappings route here)
	 */
	public Set<Integer> getAllowedClassIds()
	{
		return _allowedClassIds;
	}

	/**
	 * @return where the challenger enters, and returns to after a knockout
	 */
	public Location getEntry()
	{
		return _entry;
	}

	/**
	 * @return the Trial Guide NPC spawned at the entrance (0 = none)
	 */
	public int getGuideNpcId()
	{
		return _guideNpcId;
	}

	public Location getGuideLoc()
	{
		return _guideLoc;
	}

	/**
	 * @return the Hot Zone modifiers an attempt may roll as its Omen (empty = no omen)
	 */
	public List<HotzoneModifier> getOmens()
	{
		return _omens;
	}

	public List<ObjectiveDefinition> getObjectives()
	{
		return _objectives;
	}

	public List<TwistDefinition> getTwists()
	{
		return _twists;
	}

	public List<ChallengeReward> getRewards()
	{
		return _rewards;
	}
}
