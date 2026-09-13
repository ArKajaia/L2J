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
package org.l2jmobius.gameserver.managers.games;

import java.io.File;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ScheduledFuture;
import java.util.logging.Logger;

import org.w3c.dom.Document;
import org.w3c.dom.NamedNodeMap;
import org.w3c.dom.Node;

import org.l2jmobius.commons.database.DatabaseFactory;
import org.l2jmobius.commons.threads.ThreadPool;
import org.l2jmobius.commons.time.SchedulingPattern;
import org.l2jmobius.commons.time.TimeUtil;
import org.l2jmobius.commons.util.IXmlReader;
import org.l2jmobius.gameserver.config.UndergroundColiseumConfig;
import org.l2jmobius.gameserver.data.xml.DoorData;
import org.l2jmobius.gameserver.managers.GlobalVariablesManager;
import org.l2jmobius.gameserver.model.Location;
import org.l2jmobius.gameserver.model.actor.instance.Door;
import org.l2jmobius.gameserver.model.undergroundColiseum.UCArena;
import org.l2jmobius.gameserver.model.undergroundColiseum.UCBestTeam;
import org.l2jmobius.gameserver.model.undergroundColiseum.UCPoint;
import org.l2jmobius.gameserver.model.undergroundColiseum.UCReward;
import org.l2jmobius.gameserver.model.undergroundColiseum.UCTeam;
import org.l2jmobius.gameserver.util.Broadcast;

public class UndergroundColiseumManager implements IXmlReader
{
	private static final Logger LOGGER = Logger.getLogger(UndergroundColiseumManager.class.getName());
	
	private final Map<Integer, UCArena> _arenas = new HashMap<>(5);
	private boolean _isStarted = false;
	private long _periodStartTime;
	private long _periodEndTime;
	private ScheduledFuture<?> _regTask = null;
	private final Map<Integer, UCBestTeam> _bestTeams = new HashMap<>(5);
	
	protected UndergroundColiseumManager()
	{
		load();
	}
	
	@Override
	public void load()
	{
		_periodStartTime = GlobalVariablesManager.getInstance().getLong("UC_START_TIME", 0);
		_periodEndTime = GlobalVariablesManager.getInstance().getLong("UC_STOP_TIME", 0);
		
		final long curerntTime = System.currentTimeMillis();
		if ((_periodStartTime < curerntTime) && (_periodEndTime < curerntTime))
		{
			generateNewDate();
		}
		
		parseDatapackFile("data/UndergroundColiseum.xml");
		
		LOGGER.info(getClass().getSimpleName() + ": Loaded " + _arenas.size() + " coliseum arenas.");
		
		if ((_periodStartTime < curerntTime) && (_periodEndTime > curerntTime))
		{
			switchStatus(true);
		}
		else
		{
			final long nextTime = _periodStartTime - curerntTime;
			_regTask = ThreadPool.schedule(new UCRegistrationTask(true), nextTime);
			LOGGER.info(getClass().getSimpleName() + ": Starts at " + TimeUtil.getDateTimeString(_periodStartTime));
		}
		
		restoreBestTeams();
	}
	
	@Override
	public void parseDocument(Document document, File file)
	{
		NamedNodeMap map;
		for (Node n = document.getFirstChild(); n != null; n = n.getNextSibling())
		{
			if ("list".equalsIgnoreCase(n.getNodeName()))
			{
				for (Node d = n.getFirstChild(); d != null; d = d.getNextSibling())
				{
					if ("arena".equalsIgnoreCase(d.getNodeName()))
					{
						map = d.getAttributes();
						final int id = Integer.parseInt(map.getNamedItem("id").getNodeValue());
						final int min_level = Integer.parseInt(map.getNamedItem("minLvl").getNodeValue());
						final int max_level = Integer.parseInt(map.getNamedItem("maxLvl").getNodeValue());
						final int curator = Integer.parseInt(map.getNamedItem("curator").getNodeValue());
						
						final UCArena arena = new UCArena(id, curator, min_level, max_level);
						int index = 0;
						int index2 = 0;
						
						for (Node und = d.getFirstChild(); und != null; und = und.getNextSibling())
						{
							if ("tower".equalsIgnoreCase(und.getNodeName()))
							{
								map = und.getAttributes();
								
								final int npcId = Integer.parseInt(map.getNamedItem("id").getNodeValue());
								final int x = Integer.parseInt(map.getNamedItem("x").getNodeValue());
								final int y = Integer.parseInt(map.getNamedItem("y").getNodeValue());
								final int z = Integer.parseInt(map.getNamedItem("z").getNodeValue());
								
								final UCTeam team = new UCTeam(index, arena, x, y, z, npcId);
								arena.setUCTeam(index, team);
								
								index++;
							}
							else if ("spawn".equalsIgnoreCase(und.getNodeName()))
							{
								map = und.getAttributes();
								final List<Door> doors = new ArrayList<>();
								final String doorList = map.getNamedItem("doors") != null ? map.getNamedItem("doors").getNodeValue() : "";
								if (!doorList.isEmpty())
								{
									final String[] doorSplint = doorList.split(",");
									for (String doorId : doorSplint)
									{
										final Door door = DoorData.getInstance().getDoor(Integer.parseInt(doorId));
										if (door != null)
										{
											doors.add(door);
										}
									}
								}
								
								final int x = Integer.parseInt(map.getNamedItem("x").getNodeValue());
								final int y = Integer.parseInt(map.getNamedItem("y").getNodeValue());
								final int z = Integer.parseInt(map.getNamedItem("z").getNodeValue());
								
								final UCPoint point = new UCPoint(doors, new Location(x, y, z));
								arena.setUCPoint(index2, point);
								
								index2++;
							}
							else if ("rewards".equalsIgnoreCase(und.getNodeName()))
							{
								for (Node c = und.getFirstChild(); c != null; c = c.getNextSibling())
								{
									if ("item".equalsIgnoreCase(c.getNodeName()))
									{
										final int itemId = Integer.parseInt(c.getAttributes().getNamedItem("id").getNodeValue());
										final long amount = Long.parseLong(c.getAttributes().getNamedItem("amount").getNodeValue());
										final boolean useModifier = c.getAttributes().getNamedItem("useModifers") != null ? Boolean.parseBoolean(c.getAttributes().getNamedItem("useModifers").getNodeValue()) : false;
										arena.setReward(new UCReward(itemId, amount, useModifier));
									}
								}
							}
						}
						
						_arenas.put(arena.getId(), arena);
					}
				}
			}
		}
	}
	
	private void restoreBestTeams()
	{
		_bestTeams.clear();
		try (Connection con = DatabaseFactory.getConnection();
			PreparedStatement statement = con.prepareStatement("SELECT * FROM underground_coliseum ORDER BY arenaId");
			ResultSet rset = statement.executeQuery())
		{
			while (rset.next())
			{
				final int arenaId = rset.getInt("arenaId");
				final String leader = rset.getString("leader");
				final int wins = rset.getInt("wins");
				_bestTeams.put(arenaId, new UCBestTeam(arenaId, leader, wins));
			}
		}
		catch (SQLException e)
		{
			LOGGER.warning(getClass().getSimpleName() + ": Could not load underground_coliseum table");
		}
		catch (Exception e)
		{
			LOGGER.warning(getClass().getSimpleName() + ": Error while initializing UndergroundColiseumManager: " + e.getMessage());
		}
	}
	
	private void saveBestTeam(UCBestTeam team, boolean isNew)
	{
		if (isNew)
		{
			try (Connection con = DatabaseFactory.getConnection();
				PreparedStatement ps = con.prepareStatement("INSERT INTO underground_coliseum (`arenaId`, `leader`, `wins`) VALUES (?,?,?) "))
			{
				ps.setInt(1, team.getArenaId());
				ps.setString(2, team.getLeaderName());
				ps.setInt(3, team.getWins());
				ps.executeUpdate();
			}
			catch (SQLException e)
			{
				LOGGER.warning(getClass().getSimpleName() + ": Could not save underground_coliseum: " + e.getMessage());
			}
		}
		else
		{
			try (Connection con = DatabaseFactory.getConnection())
			{
				final PreparedStatement stmt = con.prepareStatement("UPDATE underground_coliseum SET leader = ?, wins = ?  WHERE arenaId = ?");
				stmt.setInt(1, team.getArenaId());
				stmt.setInt(2, team.getWins());
				stmt.setInt(3, team.getArenaId());
				stmt.execute();
				stmt.close();
			}
			catch (Exception e)
			{
				LOGGER.warning(getClass().getSimpleName() + ": could not clean status for underground_coliseum areanaId: " + team.getArenaId() + " in database!");
			}
		}
	}
	
	public UCBestTeam getBestTeam(int arenaId)
	{
		return _bestTeams.get(arenaId);
	}
	
	public void updateBestTeam(int arenaId, String name, int wins)
	{
		if (_bestTeams.containsKey(arenaId))
		{
			final UCBestTeam team = getBestTeam(arenaId);
			if (team != null)
			{
				team.setLeader(name);
				team.setWins(wins);
				saveBestTeam(team, false);
			}
		}
		else
		{
			final UCBestTeam team = new UCBestTeam(arenaId, name, wins);
			_bestTeams.put(arenaId, team);
			saveBestTeam(team, true);
		}
	}
	
	private void generateNewDate()
	{
		final SchedulingPattern timePattern = new SchedulingPattern(UndergroundColiseumConfig.UC_START_TIME);
		_periodStartTime = timePattern.next(System.currentTimeMillis());
		_periodEndTime = _periodStartTime + (UndergroundColiseumConfig.UC_TIME_PERIOD * 3600000);
		GlobalVariablesManager.getInstance().set("UC_START_TIME", _periodStartTime);
		GlobalVariablesManager.getInstance().set("UC_STOP_TIME", _periodEndTime);
	}
	
	public UCArena getArena(int id)
	{
		return _arenas.get(id);
	}
	
	public void setStarted(boolean started)
	{
		_isStarted = started;
		for (UCArena arena : getAllArenas())
		{
			arena.switchStatus(started);
		}
		
		if (UndergroundColiseumConfig.UC_ALLOW_ANNOUNCE)
		{
			if (_isStarted)
			{
				Broadcast.toAllOnlinePlayers("Underground Coliseum has started!");
			}
			else
			{
				Broadcast.toAllOnlinePlayers("Underground Coliseum has stopped!");
			}
		}
	}
	
	public boolean isStarted()
	{
		return _isStarted;
	}
	
	public Collection<UCArena> getAllArenas()
	{
		return _arenas.values();
	}
	
	private void switchStatus(boolean isStart)
	{
		if (_regTask != null)
		{
			_regTask.cancel(false);
			_regTask = null;
		}
		
		setStarted(isStart);
		if (isStart)
		{
			final long nextTime = _periodEndTime - System.currentTimeMillis();
			_regTask = ThreadPool.schedule(new UCRegistrationTask(false), nextTime);
			LOGGER.info(getClass().getSimpleName() + ": Ends at " + TimeUtil.getDateTimeString(_periodEndTime));
		}
		else
		{
			generateNewDate();
			final long nextTime = _periodStartTime - System.currentTimeMillis();
			_regTask = ThreadPool.schedule(new UCRegistrationTask(true), nextTime);
			LOGGER.info(getClass().getSimpleName() + ": Starts at " + TimeUtil.getDateTimeString(_periodStartTime));
		}
	}
	
	public class UCRegistrationTask implements Runnable
	{
		private final boolean _status;
		
		public UCRegistrationTask(boolean status)
		{
			_status = status;
		}
		
		@Override
		public void run()
		{
			switchStatus(_status);
		}
	}
	
	public static UndergroundColiseumManager getInstance()
	{
		return SingletonHolder.INSTANCE;
	}
	
	private static class SingletonHolder
	{
		protected static final UndergroundColiseumManager INSTANCE = new UndergroundColiseumManager();
	}
}
