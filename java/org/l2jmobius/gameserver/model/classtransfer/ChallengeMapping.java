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

/**
 * A {@code <mapping>} line: which challenge a class, race, archetype or everyone gets at a stage. Resolution order is CLASS (the player's class, then each parent class) -> RACE -> ARCHETYPE -> GENERIC.
 * @author Mobius
 */
public class ChallengeMapping
{
	public enum MappingType
	{
		CLASS,
		RACE,
		/** {@code FIGHTER} or {@code MAGE}, from {@code PlayerClass.isMage()}. */
		ARCHETYPE,
		GENERIC
	}

	private final TransferStage _stage;
	private final MappingType _type;
	private final String _value;
	private final String _challengeId;

	public ChallengeMapping(TransferStage stage, MappingType type, String value, String challengeId)
	{
		_stage = stage;
		_type = type;
		_value = value;
		_challengeId = challengeId;
	}

	public TransferStage getStage()
	{
		return _stage;
	}

	public MappingType getType()
	{
		return _type;
	}

	/**
	 * @return class id, race name or archetype name ({@code null} for GENERIC)
	 */
	public String getValue()
	{
		return _value;
	}

	public String getChallengeId()
	{
		return _challengeId;
	}
}
