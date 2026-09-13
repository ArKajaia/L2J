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
package org.l2jmobius.gameserver.model.script;

import java.util.Calendar;
import java.util.List;

import org.l2jmobius.gameserver.config.GeneralConfig;
import org.l2jmobius.gameserver.data.holders.InstanceReenterTimeHolder;
import org.l2jmobius.gameserver.managers.InstanceManager;
import org.l2jmobius.gameserver.model.actor.Player;
import org.l2jmobius.gameserver.model.actor.Summon;
import org.l2jmobius.gameserver.model.instancezone.Instance;
import org.l2jmobius.gameserver.model.instancezone.InstanceReenterType;
import org.l2jmobius.gameserver.model.instancezone.InstanceWorld;
import org.l2jmobius.gameserver.model.skill.BuffInfo;
import org.l2jmobius.gameserver.model.skill.enums.SkillFinishType;
import org.l2jmobius.gameserver.network.SystemMessageId;
import org.l2jmobius.gameserver.network.serverpackets.SystemMessage;

/**
 * Abstract base class for instance zone management and player entry handling.<br>
 * Provides core functionality for creating, entering and managing dungeon instances.
 * <ul>
 * <li>Player entry validation and instance creation.</li>
 * <li>Re-entry time management and restrictions.</li>
 * <li>Buff removal handling based on instance configuration.</li>
 * <li>Instance lifecycle management and cleanup.</li>
 * </ul>
 * @author FallenAngel, Mobius
 */
public abstract class InstanceScript extends Script
{
	// Constants.
	private static final int MIDNIGHT_HOUR = 12;
	private static final int CALENDAR_DAY_OFFSET = 1;
	private static final long NO_TIME_SET = -1;
	
	protected InstanceScript()
	{
	}
	
	/**
	 * Handles player entry into an instance zone with validation and creation.<br>
	 * Creates new instance if player doesn't have one, or enters existing instance.
	 * @param player the player attempting to enter
	 * @param templateId the instance template identifier
	 */
	protected void enterInstance(Player player, int templateId)
	{
		final InstanceWorld existingWorld = InstanceManager.getInstance().getPlayerWorld(player);
		if (existingWorld != null)
		{
			if (existingWorld.getTemplateId() != templateId)
			{
				if (existingWorld.getPlayersCount() > 0)
				{
					player.sendPacket(SystemMessageId.YOU_HAVE_ENTERED_ANOTHER_INSTANCE_ZONE_THEREFORE_YOU_CANNOT_ENTER_CORRESPONDING_DUNGEON);
					return;
				}
				
				existingWorld.destroy();
			}
			
			onEnterInstance(player, existingWorld, false);
			
			final Instance instance = InstanceManager.getInstance().getInstance(existingWorld.getInstanceId());
			if (instance.isRemoveBuffEnabled())
			{
				handleRemoveBuffs(player, existingWorld);
			}
			
			if (!instance.getEnterLocs().isEmpty())
			{
				teleportPlayer(player, instance.getEnterLocs().stream().findAny().get(), existingWorld.getInstanceId(), false);
			}
		}
		else if (checkConditions(player))
		{
			final InstanceWorld newWorld = new InstanceWorld();
			newWorld.setInstance(InstanceManager.getInstance().createDynamicInstance(templateId));
			InstanceManager.getInstance().addWorld(newWorld);
			onEnterInstance(player, newWorld, true);
			
			final Instance instance = InstanceManager.getInstance().getInstance(newWorld.getInstanceId());
			if (instance.getReenterType() == InstanceReenterType.ON_ENTER)
			{
				handleReenterTime(newWorld);
			}
			
			if (instance.isRemoveBuffEnabled())
			{
				handleRemoveBuffs(newWorld);
			}
			
			if (!instance.getEnterLocs().isEmpty())
			{
				teleportPlayer(player, instance.getEnterLocs().stream().findAny().get(), newWorld.getInstanceId(), false);
			}
		}
	}
	
	/**
	 * Finishes the instance with default duration from configuration.
	 * @param world the instance world to finish
	 */
	protected void finishInstance(InstanceWorld world)
	{
		finishInstance(world, GeneralConfig.INSTANCE_FINISH_TIME);
	}
	
	/**
	 * Finishes the instance and sets cleanup duration.<br>
	 * Handles re-entry time restrictions and instance destruction scheduling.
	 * @param world the instance world to finish
	 * @param duration the time in seconds before instance destruction
	 */
	protected void finishInstance(InstanceWorld world, int duration)
	{
		final Instance instance = InstanceManager.getInstance().getInstance(world.getInstanceId());
		if (instance.getReenterType() == InstanceReenterType.ON_FINISH)
		{
			handleReenterTime(world);
		}
		
		if (duration == 0)
		{
			InstanceManager.getInstance().destroyInstance(instance.getId());
		}
		else if (duration > 0)
		{
			instance.setDuration(duration);
			instance.setEmptyDestroyTime(0);
		}
	}
	
	/**
	 * Calculates and sets re-entry time restrictions for the instance.<br>
	 * Supports both fixed time delays and scheduled daily/weekly reset times.
	 * @param world the instance world to set re-entry restrictions for
	 */
	protected void handleReenterTime(InstanceWorld world)
	{
		final Instance instance = InstanceManager.getInstance().getInstance(world.getInstanceId());
		final List<InstanceReenterTimeHolder> reenterDataList = instance.getReenterData();
		
		long earliestReenterTime = NO_TIME_SET;
		
		for (InstanceReenterTimeHolder reenterData : reenterDataList)
		{
			if (reenterData.getTime() > 0)
			{
				earliestReenterTime = System.currentTimeMillis() + reenterData.getTime();
				break;
			}
			
			final Calendar calendar = Calendar.getInstance();
			calendar.set(Calendar.AM_PM, reenterData.getHour() >= MIDNIGHT_HOUR ? 1 : 0);
			calendar.set(Calendar.HOUR_OF_DAY, reenterData.getHour());
			calendar.set(Calendar.MINUTE, reenterData.getMinute());
			calendar.set(Calendar.SECOND, 0);
			
			if (calendar.getTimeInMillis() <= System.currentTimeMillis())
			{
				calendar.add(Calendar.DAY_OF_MONTH, CALENDAR_DAY_OFFSET);
			}
			
			if (reenterData.getDay() != null)
			{
				while (calendar.get(Calendar.DAY_OF_WEEK) != (reenterData.getDay().getValue() + CALENDAR_DAY_OFFSET))
				{
					calendar.add(Calendar.DAY_OF_MONTH, CALENDAR_DAY_OFFSET);
				}
			}
			
			if (earliestReenterTime == NO_TIME_SET)
			{
				earliestReenterTime = calendar.getTimeInMillis();
			}
			else if (calendar.getTimeInMillis() < earliestReenterTime)
			{
				earliestReenterTime = calendar.getTimeInMillis();
			}
		}
		
		if (earliestReenterTime > 0)
		{
			setReenterTime(world, earliestReenterTime);
		}
	}
	
	/**
	 * Removes buffs from all players in the instance world.
	 * @param world the instance world containing players to process
	 */
	protected void handleRemoveBuffs(InstanceWorld world)
	{
		for (Player player : world.getAllowed())
		{
			if (player != null)
			{
				handleRemoveBuffs(player, world);
			}
		}
	}
	
	/**
	 * Called when a player enters an instance zone.<br>
	 * Must be implemented by subclasses to handle specific instance behavior.
	 * @param player the player entering the instance
	 * @param world the instance world being entered
	 * @param firstEntrance true if this is the first time entering this instance
	 */
	protected abstract void onEnterInstance(Player player, InstanceWorld world, boolean firstEntrance);
	
	/**
	 * Validates if a player meets the requirements to enter the instance.
	 * @param player the player to validate
	 * @return true if the player can enter the instance
	 */
	protected boolean checkConditions(Player player)
	{
		return true;
	}
	
	/**
	 * Sets re-entry time restrictions for all players in the instance.<br>
	 * Notifies online players about the restriction and next available entry time.
	 * @param world the instance world
	 * @param timeInMilliseconds the restriction time in milliseconds since epoch
	 */
	protected void setReenterTime(InstanceWorld world, long timeInMilliseconds)
	{
		for (Player player : world.getAllowed())
		{
			if (player != null)
			{
				InstanceManager.getInstance().setInstanceTime(player.getObjectId(), world.getTemplateId(), timeInMilliseconds);
				if (player.isOnline())
				{
					final String instanceName = InstanceManager.getInstance().getInstanceIdName(world.getTemplateId());
					player.sendPacket(new SystemMessage(SystemMessageId.INSTANT_ZONE_S1_S_ENTRY_HAS_BEEN_RESTRICTED_YOU_CAN_CHECK_THE_NEXT_POSSIBLE_ENTRY_TIME_BY_USING_THE_COMMAND_INSTANCEZONE).addString(instanceName));
				}
			}
		}
	}
	
	/**
	 * Removes buffs from a player and their summon based on instance configuration.<br>
	 * Supports whitelist, blacklist, and complete buff removal modes.
	 * @param player the player to remove buffs from
	 * @param world the instance world containing buff removal settings
	 */
	private void handleRemoveBuffs(Player player, InstanceWorld world)
	{
		final Instance instance = InstanceManager.getInstance().getInstance(world.getInstanceId());
		switch (instance.getRemoveBuffType())
		{
			case ALL:
			{
				player.stopAllEffectsExceptThoseThatLastThroughDeath();
				
				final Summon summon = player.getSummon();
				if (summon != null)
				{
					summon.stopAllEffectsExceptThoseThatLastThroughDeath();
				}
				break;
			}
			case WHITELIST:
			{
				for (BuffInfo buffInfo : player.getEffectList().getBuffs())
				{
					if (!instance.getBuffExceptionList().contains(buffInfo.getSkill().getId()))
					{
						buffInfo.getEffected().getEffectList().stopSkillEffects(SkillFinishType.REMOVED, buffInfo.getSkill());
					}
				}
				
				final Summon summon = player.getSummon();
				if (summon != null)
				{
					for (BuffInfo buffInfo : summon.getEffectList().getBuffs())
					{
						if (!instance.getBuffExceptionList().contains(buffInfo.getSkill().getId()))
						{
							buffInfo.getEffected().getEffectList().stopSkillEffects(SkillFinishType.REMOVED, buffInfo.getSkill());
						}
					}
				}
				break;
			}
			case BLACKLIST:
			{
				for (BuffInfo buffInfo : player.getEffectList().getBuffs())
				{
					if (instance.getBuffExceptionList().contains(buffInfo.getSkill().getId()))
					{
						buffInfo.getEffected().getEffectList().stopSkillEffects(SkillFinishType.REMOVED, buffInfo.getSkill());
					}
				}
				
				final Summon summon = player.getSummon();
				if (summon != null)
				{
					for (BuffInfo buffInfo : summon.getEffectList().getBuffs())
					{
						if (instance.getBuffExceptionList().contains(buffInfo.getSkill().getId()))
						{
							buffInfo.getEffected().getEffectList().stopSkillEffects(SkillFinishType.REMOVED, buffInfo.getSkill());
						}
					}
				}
				break;
			}
		}
	}
}
