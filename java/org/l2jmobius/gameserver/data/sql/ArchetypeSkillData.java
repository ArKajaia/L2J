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

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;

import org.l2jmobius.commons.database.DatabaseFactory;

/**
 * Loads and caches the {@code npc_archetype_skills} table: a pool of active skills that {@link org.l2jmobius.gameserver.ai.AttackableAI} can grant to a monster instance whose rolled archetype needs a capability its template doesn't already provide.
 * <p>
 * Loaded once at server start; call {@link #load()} again (e.g. from a reload admin command) to pick up table changes without a restart.
 * @author Zoey76
 */
public class ArchetypeSkillData
{
	private static final Logger LOGGER = Logger.getLogger(ArchetypeSkillData.class.getName());
	
	private final List<ArchetypeSkillHolder> _skills = new ArrayList<>();
	
	protected ArchetypeSkillData()
	{
		load();
	}
	
	public void load()
	{
		_skills.clear();
		
		final String query = "SELECT skill_id, skill_level, skill_type, min_monster_level, max_monster_level FROM npc_archetype_skills";
		try (Connection con = DatabaseFactory.getConnection();
			PreparedStatement ps = con.prepareStatement(query);
			ResultSet rs = ps.executeQuery())
		{
			while (rs.next())
			{
				_skills.add(new ArchetypeSkillHolder(rs.getInt("skill_id"), rs.getInt("skill_level"), rs.getString("skill_type"), rs.getInt("min_monster_level"), rs.getInt("max_monster_level")));
			}
		}
		catch (Exception e)
		{
			LOGGER.warning("ArchetypeSkillData: Failed loading npc_archetype_skills: " + e.getMessage());
		}
		
		LOGGER.info("ArchetypeSkillData: Loaded " + _skills.size() + " archetype skill pool entries.");
	}
	
	/**
	 * @param types eligible {@code skill_type} values, matched case-insensitively
	 * @param npcLevel the monster's level; only rows whose min/max_monster_level range includes it are returned
	 * @return every pool entry matching one of {@code types} and whose level range includes {@code npcLevel}
	 */
	public List<ArchetypeSkillHolder> getEligibleSkills(List<String> types, int npcLevel)
	{
		final List<ArchetypeSkillHolder> result = new ArrayList<>();
		for (ArchetypeSkillHolder holder : _skills)
		{
			if (!containsIgnoreCase(types, holder.getSkillType()))
			{
				continue;
			}
			
			if ((npcLevel < holder.getMinMonsterLevel()) || (npcLevel > holder.getMaxMonsterLevel()))
			{
				continue;
			}
			
			result.add(holder);
		}
		return result;
	}
	
	private static boolean containsIgnoreCase(List<String> list, String value)
	{
		for (String entry : list)
		{
			if (entry.equalsIgnoreCase(value))
			{
				return true;
			}
		}
		return false;
	}
	
	public static ArchetypeSkillData getInstance()
	{
		return SingletonHolder.INSTANCE;
	}
	
	private static class SingletonHolder
	{
		protected static final ArchetypeSkillData INSTANCE = new ArchetypeSkillData();
	}
}