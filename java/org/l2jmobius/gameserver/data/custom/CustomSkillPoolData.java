package org.l2jmobius.gameserver.data.custom;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;

import org.l2jmobius.commons.database.DatabaseFactory;
import org.l2jmobius.commons.util.Rnd;

public class CustomSkillPoolData
{
	private static final Logger LOGGER = Logger.getLogger(CustomSkillPoolData.class.getName());
	
	// A small container to hold the ID and Max Level from your database
	public static class CustomSkill
	{
		public final int skillId;
		public final int maxLevel;
		private final double hpGrowth;
		private final double dropMultiplier;
		
		// Legacy 2-argument constructor (defaults growth/multiplier to 0.0)
		public CustomSkill(int skillId, int maxLevel)
		{
			this(skillId, maxLevel, 0.0, 0.0);
		}
		
		// Full 4-argument constructor
		public CustomSkill(int skillId, int maxLevel, double hpGrowth, double dropMultiplier)
		{
			this.skillId = skillId;
			this.maxLevel = maxLevel;
			this.hpGrowth = hpGrowth;
			this.dropMultiplier = dropMultiplier;
		}
		
		public int getSkillId()
		{
			return skillId;
		}
		
		public int getMaxLevel()
		{
			return maxLevel;
		}
		
		public double getHpGrowth()
		{
			return hpGrowth;
		}
		
		public double getDropMultiplier()
		{
			return dropMultiplier;
		}
	}
	
	// The memory pool where we store the database rows
	private final List<CustomSkill> _skillPool = new ArrayList<>();
	
	protected CustomSkillPoolData()
	{
		load();
	}
	
	public void load()
	{
		_skillPool.clear();
		String query = "SELECT skill_id, max_level FROM custom_skill_pools";
		
		try (Connection con = DatabaseFactory.getConnection();
			PreparedStatement ps = con.prepareStatement(query);
			ResultSet rs = ps.executeQuery())
		{
			while (rs.next())
			{
				_skillPool.add(new CustomSkill(rs.getInt("skill_id"), rs.getInt("max_level")));
			}
			LOGGER.info("CustomSkillPoolData: Loaded " + _skillPool.size() + " random monster skills.");
		}
		catch (Exception e)
		{
			LOGGER.warning("CustomSkillPoolData: Error loading custom_skill_pools table: " + e.getMessage());
		}
	}
	
	/**
	 * Grabs a random skill from the loaded pool.
	 * @return a random CustomSkill from the pool, or null if the pool is empty.
	 */
	public CustomSkill getRandomSkill()
	{
		if (_skillPool.isEmpty())
		{
			return null;
		}
		return _skillPool.get(Rnd.get(_skillPool.size()));
	}
	
	// Singleton pattern standard for L2J Mobius
	public static CustomSkillPoolData getInstance()
	{
		return SingletonHolder.INSTANCE;
	}
	
	private static class SingletonHolder
	{
		protected static final CustomSkillPoolData INSTANCE = new CustomSkillPoolData();
	}
}