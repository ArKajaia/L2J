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

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.util.Calendar;
import java.util.logging.Logger;

import org.l2jmobius.commons.database.DatabaseFactory;
import org.l2jmobius.commons.threads.ThreadPool;
import org.l2jmobius.commons.time.TimeUtil;
import org.l2jmobius.gameserver.config.PlayerConfig;
import org.l2jmobius.gameserver.data.sql.ClanTable;
import org.l2jmobius.gameserver.data.xml.SkillData;
import org.l2jmobius.gameserver.model.World;
import org.l2jmobius.gameserver.model.actor.Player;
import org.l2jmobius.gameserver.model.clan.Clan;
import org.l2jmobius.gameserver.model.clan.ClanMember;
import org.l2jmobius.gameserver.model.events.EventDispatcher;
import org.l2jmobius.gameserver.model.events.EventType;
import org.l2jmobius.gameserver.model.events.holders.OnDailyReset;
import org.l2jmobius.gameserver.model.olympiad.Olympiad;
import org.l2jmobius.gameserver.model.sevensigns.SevenSigns;
import org.l2jmobius.gameserver.model.sevensigns.SevenSignsFestival;
import org.l2jmobius.gameserver.model.skill.Skill;
import org.l2jmobius.gameserver.network.serverpackets.ExNevitAdventTimeChange;
import org.l2jmobius.gameserver.network.serverpackets.ExVoteSystemInfo;

/**
 * @author Mobius
 */
public class DailyResetManager
{
	private static final Logger LOGGER = Logger.getLogger(DailyResetManager.class.getName());
	
	private static final int[] RESET_SKILLS =
	{
		2510, // Wondrous Cubic
		22180, // Wondrous Cubic - 1 time use
	};
	
	protected DailyResetManager()
	{
		// Schedule reset everyday at 6:30.
		final long nextResetTime = TimeUtil.getNextTime(6, 30).getTimeInMillis();
		final long currentTime = System.currentTimeMillis();
		
		// Get today's 6:30 AM timestamp.
		final Calendar calendar = Calendar.getInstance();
		calendar.set(Calendar.HOUR_OF_DAY, 6);
		calendar.set(Calendar.MINUTE, 30);
		calendar.set(Calendar.SECOND, 0);
		calendar.set(Calendar.MILLISECOND, 0);
		final long currentResetTime = calendar.getTimeInMillis();
		
		// Check if 24 hours have passed since the last daily reset.
		if ((currentTime < currentResetTime) || (GlobalVariablesManager.getInstance().getLong(GlobalVariablesManager.DAILY_TASK_RESET, 0) > currentResetTime))
		{
			LOGGER.info(getClass().getSimpleName() + ": Next schedule at " + TimeUtil.getDateTimeString(nextResetTime) + ".");
		}
		else
		{
			LOGGER.info(getClass().getSimpleName() + ": Daily task will run now.");
			onReset();
		}
		
		// Daily reset task.
		final long startDelay = Math.max(0, nextResetTime - currentTime);
		ThreadPool.scheduleAtFixedRate(this::onReset, startDelay, 86400000); // 86400000 = 1 day
		
		// Global save task.
		ThreadPool.scheduleAtFixedRate(this::onSave, 1800000, 1800000); // 1800000 = 30 minutes
	}
	
	private void onReset()
	{
		LOGGER.info("Starting reset of daily tasks...");
		
		// Store last reset time.
		GlobalVariablesManager.getInstance().set(GlobalVariablesManager.DAILY_TASK_RESET, System.currentTimeMillis());
		
		// Wednesday weekly tasks.
		final Calendar calendar = Calendar.getInstance();
		if (calendar.get(Calendar.DAY_OF_WEEK) == Calendar.WEDNESDAY)
		{
			clanLeaderApply();
		}
		
		// Daily tasks.
		resetDailySkills();
		resetNevit();
		resetRecommends();
		
		// Trigger daily reset event.
		if (EventDispatcher.getInstance().hasListener(EventType.ON_DAILY_RESET))
		{
			EventDispatcher.getInstance().notifyEvent(new OnDailyReset());
		}
		
		// Store player variables.
		for (Player player : World.getInstance().getPlayers())
		{
			player.getVariables().storeMe();
			player.getAccountVariables().storeMe();
		}
		
		LOGGER.info("Daily tasks reset completed.");
	}
	
	private void onSave()
	{
		GlobalVariablesManager.getInstance().storeMe();
		
		if (Olympiad.getInstance().inCompPeriod())
		{
			Olympiad.getInstance().saveOlympiadStatus();
			LOGGER.info("Olympiad System: Data updated.");
		}
		
		SevenSigns.getInstance().saveSevenSignsStatus();
		if (!SevenSigns.getInstance().isSealValidationPeriod())
		{
			SevenSignsFestival.getInstance().saveFestivalData(false);
		}
		LOGGER.info("SevenSigns: Data updated.");
	}
	
	private void clanLeaderApply()
	{
		for (Clan clan : ClanTable.getInstance().getClans())
		{
			if (clan.getNewLeaderId() != 0)
			{
				final ClanMember member = clan.getClanMember(clan.getNewLeaderId());
				if (member == null)
				{
					continue;
				}
				
				clan.setNewLeader(member);
			}
		}
		
		LOGGER.info("Clan leaders have been updated.");
	}
	
	private void resetDailySkills()
	{
		try (Connection con = DatabaseFactory.getConnection())
		{
			for (int skillId : RESET_SKILLS)
			{
				try (PreparedStatement ps = con.prepareStatement("DELETE FROM character_skills_save WHERE skill_id=? AND charId IN (SELECT charId FROM characters WHERE online = 0)"))
				{
					ps.setInt(1, skillId);
					ps.execute();
				}
			}
		}
		catch (Exception e)
		{
			LOGGER.severe(getClass().getSimpleName() + ": Could not reset daily skill reuse: " + e);
		}
		
		// Update data for online players.
		for (int skillId : RESET_SKILLS)
		{
			final Skill skill = SkillData.getInstance().getSkill(skillId, 1 /* No known need for more levels */);
			if (skill != null)
			{
				for (Player player : World.getInstance().getPlayers())
				{
					if (player.hasSkillReuse(skill.getReuseHashCode()))
					{
						player.removeTimeStamp(skill);
					}
				}
			}
		}
		
		LOGGER.info("Daily skill reuse cleaned.");
	}
	
	private void resetNevit()
	{
		if (!PlayerConfig.NEVIT_ENABLED)
		{
			return;
		}
		
		for (Player player : World.getInstance().getPlayers())
		{
			if ((player == null) || !player.isOnline())
			{
				continue;
			}
			
			player.getVariables().set("hunting_time", 0);
			player.sendPacket(new ExNevitAdventTimeChange(0, true));
		}
	}
	
	private void resetRecommends()
	{
		try (Connection con = DatabaseFactory.getConnection())
		{
			try (PreparedStatement ps = con.prepareStatement("UPDATE character_reco_bonus SET rec_left=?, time_left=?, rec_have=0 WHERE rec_have <=  20"))
			{
				ps.setInt(1, 20); // Rec left = 20
				ps.setInt(2, 3600000); // Timer = 1 hour
				ps.execute();
			}
			
			try (PreparedStatement ps = con.prepareStatement("UPDATE character_reco_bonus SET rec_left=?, time_left=?, rec_have=GREATEST(rec_have-20,0) WHERE rec_have > 20"))
			{
				ps.setInt(1, 20); // Rec left = 20
				ps.setInt(2, 3600000); // Timer = 1 hour
				ps.execute();
			}
		}
		catch (Exception e)
		{
			LOGGER.severe(getClass().getSimpleName() + ": Could not reset Recommendations System: " + e);
		}
		
		for (Player player : World.getInstance().getPlayers())
		{
			player.setRecomLeft(0);
			player.setRecomHave(player.getRecomHave() - 20);
			player.sendPacket(new ExVoteSystemInfo(player));
			player.broadcastUserInfo();
		}
		
		// Refresh reco bonus for online players.
		if (PlayerConfig.NEVIT_ENABLED)
		{
			for (Player player : World.getInstance().getPlayers())
			{
				if (player != null)
				{
					player.stopNevitHourglassTask();
					player.startNevitHourglassTask();
				}
			}
		}
	}
	
	public static DailyResetManager getInstance()
	{
		return SingletonHolder.INSTANCE;
	}
	
	private static class SingletonHolder
	{
		protected static final DailyResetManager INSTANCE = new DailyResetManager();
	}
}
