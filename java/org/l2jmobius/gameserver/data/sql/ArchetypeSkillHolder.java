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
package org.l2jmobius.gameserver.data.sql;

/**
 * One row from the {@code npc_archetype_skills} table: a skill that can be granted to a monster
 * instance whose archetype needs it, gated by {@code skill_type} and the monster's level.
 * @author Zoey76
 */
public class ArchetypeSkillHolder
{
	private final int _skillId;
	private final int _skillLevel;
	private final String _skillType;
	private final int _minMonsterLevel;
	private final int _maxMonsterLevel;
	
	public ArchetypeSkillHolder(int skillId, int skillLevel, String skillType, int minMonsterLevel, int maxMonsterLevel)
	{
		_skillId = skillId;
		_skillLevel = skillLevel;
		_skillType = skillType;
		_minMonsterLevel = minMonsterLevel;
		_maxMonsterLevel = maxMonsterLevel;
	}
	
	public int getSkillId()
	{
		return _skillId;
	}
	
	public int getSkillLevel()
	{
		return _skillLevel;
	}
	
	public String getSkillType()
	{
		return _skillType;
	}
	
	public int getMinMonsterLevel()
	{
		return _minMonsterLevel;
	}
	
	public int getMaxMonsterLevel()
	{
		return _maxMonsterLevel;
	}
}
