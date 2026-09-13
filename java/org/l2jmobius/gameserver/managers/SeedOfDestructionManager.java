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

import java.util.Calendar;
import java.util.logging.Logger;

import org.l2jmobius.commons.threads.ThreadPool;
import org.l2jmobius.gameserver.config.GraciaSeedsConfig;
import org.l2jmobius.gameserver.model.World;
import org.l2jmobius.gameserver.model.actor.Npc;
import org.l2jmobius.gameserver.model.script.Quest;

public class SeedOfDestructionManager
{
	private static final Logger LOGGER = Logger.getLogger(SeedOfDestructionManager.class.getName());
	
	public static final String ENERGY_SEEDS = "EnergySeeds";
	
	private static final byte SOITYPE = 2;
	private static final byte SOATYPE = 3;
	
	// Seed of Destruction
	private static final byte SODTYPE = 1;
	private int _SoDTiatKilled = 0;
	private int _SoDState = 1;
	private final Calendar _SoDLastStateChangeDate;
	
	protected SeedOfDestructionManager()
	{
		_SoDLastStateChangeDate = Calendar.getInstance();
		loadData();
		handleSodStages();
		LOGGER.info("Seed of Destruction Manager: Loaded. Current stage is: " + _SoDState);
	}
	
	public void saveData(byte seedType)
	{
		switch (seedType)
		{
			case SODTYPE:
			{
				// Seed of Destruction
				GlobalVariablesManager.getInstance().set("SoDState", _SoDState);
				GlobalVariablesManager.getInstance().set("SoDTiatKilled", _SoDTiatKilled);
				GlobalVariablesManager.getInstance().set("SoDLSCDate", _SoDLastStateChangeDate.getTimeInMillis());
				break;
			}
			case SOITYPE:
			{
				// Seed of Infinity
				break;
			}
			case SOATYPE:
			{
				// Seed of Annihilation
				break;
			}
			default:
			{
				LOGGER.warning(getClass().getSimpleName() + ": Unknown SeedType in SaveData: " + seedType);
				break;
			}
		}
	}
	
	public void loadData()
	{
		// Seed of Destruction variables
		if (GlobalVariablesManager.getInstance().hasVariable("SoDState"))
		{
			_SoDState = GlobalVariablesManager.getInstance().getInt("SoDState", 0);
			_SoDTiatKilled = GlobalVariablesManager.getInstance().getInt("SoDTiatKilled", 0);
			_SoDLastStateChangeDate.setTimeInMillis(GlobalVariablesManager.getInstance().getLong("SoDLSCDate", 0));
		}
		else
		{
			// save Initial values
			saveData(SODTYPE);
		}
	}
	
	private void handleSodStages()
	{
		switch (_SoDState)
		{
			case 1:
			{
				// do nothing, players should kill Tiat a few times
				break;
			}
			case 2:
			{
				// Conquest Complete state, if too much time is passed than change to defense state
				final long timePast = System.currentTimeMillis() - _SoDLastStateChangeDate.getTimeInMillis();
				if (timePast >= GraciaSeedsConfig.SOD_STAGE_2_LENGTH)
				{
					// change to Attack state because Defend statet is not implemented
					setSoDState(1, true);
				}
				else
				{
					ThreadPool.schedule(() ->
					{
						setSoDState(1, true);
						updateSodState();
					}, GraciaSeedsConfig.SOD_STAGE_2_LENGTH - timePast);
				}
				break;
			}
			case 3:
			{
				// not implemented
				setSoDState(1, true);
				break;
			}
			default:
			{
				LOGGER.warning(getClass().getSimpleName() + ": Unknown Seed of Destruction state(" + _SoDState + ")! ");
			}
		}
		
		manageEdricSpawn();
	}
	
	private void manageEdricSpawn()
	{
		final Npc edric = World.getInstance().getNpc(32527);
		if (_SoDState == 2)
		{
			if (edric == null)
			{
				Quest.addSpawn(32527, -248525, 250048, 4307, 24576);
			}
		}
		else if (edric != null)
		{
			edric.deleteMe();
		}
	}
	
	public void updateSodState()
	{
		final Quest quest = ScriptManager.getInstance().getScript(ENERGY_SEEDS);
		if (quest == null)
		{
			LOGGER.warning(getClass().getSimpleName() + ": missing EnergySeeds Quest!");
		}
		else
		{
			quest.notifyEvent("StopSoDAi", null, null);
		}
	}
	
	public void increaseSoDTiatKilled()
	{
		if (_SoDState == 1)
		{
			_SoDTiatKilled++;
			if (_SoDTiatKilled >= GraciaSeedsConfig.SOD_TIAT_KILL_COUNT)
			{
				setSoDState(2, false);
			}
			
			saveData(SODTYPE);
			final Quest esQuest = ScriptManager.getInstance().getScript(ENERGY_SEEDS);
			if (esQuest == null)
			{
				LOGGER.warning(getClass().getSimpleName() + ": missing EnergySeeds Quest!");
			}
			else
			{
				esQuest.notifyEvent("StartSoDAi", null, null);
			}
		}
	}
	
	public int getSoDTiatKilled()
	{
		return _SoDTiatKilled;
	}
	
	public void setSoDState(int value, boolean doSave)
	{
		LOGGER.info(getClass().getSimpleName() + ": New Seed of Destruction state -> " + value + ".");
		_SoDLastStateChangeDate.setTimeInMillis(System.currentTimeMillis());
		_SoDState = value;
		
		// Reset number of Tiat kills.
		if (_SoDState == 1)
		{
			_SoDTiatKilled = 0;
		}
		
		handleSodStages();
		
		if (doSave)
		{
			saveData(SODTYPE);
		}
	}
	
	public long getSoDTimeForNextStateChange()
	{
		switch (_SoDState)
		{
			case 1:
			{
				return -1;
			}
			case 2:
			{
				return ((_SoDLastStateChangeDate.getTimeInMillis() + GraciaSeedsConfig.SOD_STAGE_2_LENGTH) - System.currentTimeMillis());
			}
			case 3:
			{
				// not implemented yet
				return -1;
			}
			default:
			{
				// this should not happen!
				return -1;
			}
		}
	}
	
	public Calendar getSoDLastStateChangeDate()
	{
		return _SoDLastStateChangeDate;
	}
	
	public int getSoDState()
	{
		return _SoDState;
	}
	
	/**
	 * Gets the single instance of {@code GraciaSeedsManager}.
	 * @return single instance of {@code GraciaSeedsManager}
	 */
	public static SeedOfDestructionManager getInstance()
	{
		return SingletonHolder.INSTANCE;
	}
	
	private static class SingletonHolder
	{
		protected static final SeedOfDestructionManager INSTANCE = new SeedOfDestructionManager();
	}
}
