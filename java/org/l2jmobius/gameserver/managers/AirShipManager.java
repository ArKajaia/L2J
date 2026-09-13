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
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.HashMap;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.l2jmobius.commons.database.DatabaseFactory;
import org.l2jmobius.gameserver.model.StatSet;
import org.l2jmobius.gameserver.model.actor.Player;
import org.l2jmobius.gameserver.model.actor.holders.creature.VehiclePathPoint;
import org.l2jmobius.gameserver.model.actor.instance.AirShip;
import org.l2jmobius.gameserver.model.actor.instance.ControllableAirShip;
import org.l2jmobius.gameserver.model.actor.templates.CreatureTemplate;
import org.l2jmobius.gameserver.network.serverpackets.ExAirShipTeleportList;

public class AirShipManager
{
	private static final Logger LOGGER = Logger.getLogger(AirShipManager.class.getName());
	
	private static final String LOAD_DB = "SELECT * FROM airships";
	private static final String ADD_DB = "INSERT INTO airships (owner_id,fuel) VALUES (?,?)";
	private static final String UPDATE_DB = "UPDATE airships SET fuel=? WHERE owner_id=?";
	
	private static class AirShipTeleportList
	{
		private final int _location;
		private final int[] _fuel;
		private final VehiclePathPoint[][] _routes;
		
		public AirShipTeleportList(int locationValue, int[] fuelValue, VehiclePathPoint[][] routesValue)
		{
			_location = locationValue;
			_fuel = fuelValue;
			_routes = routesValue;
		}
		
		public int getLocation()
		{
			return _location;
		}
		
		public int[] getFuel()
		{
			return _fuel;
		}
		
		public VehiclePathPoint[][] getRoute()
		{
			return _routes;
		}
	}
	
	private CreatureTemplate _airShipTemplate = null;
	private final Map<Integer, StatSet> _airShipsInfo = new HashMap<>();
	private final Map<Integer, AirShip> _airShips = new HashMap<>();
	private final Map<Integer, AirShipTeleportList> _teleports = new HashMap<>();
	
	protected AirShipManager()
	{
		final StatSet npcDat = new StatSet();
		npcDat.set("npcId", 9);
		npcDat.set("level", 0);
		npcDat.set("jClass", "boat");
		npcDat.set("baseShldDef", 0);
		npcDat.set("baseShldRate", 0);
		npcDat.set("baseAccCombat", 38);
		npcDat.set("baseEvasRate", 38);
		npcDat.set("baseCritRate", 38);
		npcDat.set("collisionRadius", 0);
		npcDat.set("collisionHeight", 0);
		npcDat.set("sex", "male");
		npcDat.set("type", "");
		npcDat.set("baseAtkRange", 0);
		npcDat.set("baseMpMax", 0);
		npcDat.set("baseCpMax", 0);
		npcDat.set("rewardExp", 0);
		npcDat.set("rewardSp", 0);
		npcDat.set("basePAtk", 0);
		npcDat.set("baseMAtk", 0);
		npcDat.set("basePAtkSpd", 0);
		npcDat.set("aggroRange", 0);
		npcDat.set("baseMAtkSpd", 0);
		npcDat.set("rhand", 0);
		npcDat.set("lhand", 0);
		npcDat.set("armor", 0);
		npcDat.set("baseWalkSpd", 0);
		npcDat.set("baseRunSpd", 0);
		npcDat.set("name", "AirShip");
		npcDat.set("baseHpMax", 50000);
		npcDat.set("baseHpReg", 3.e-3f);
		npcDat.set("baseMpReg", 3.e-3f);
		npcDat.set("basePDef", 100);
		npcDat.set("baseMDef", 100);
		_airShipTemplate = new CreatureTemplate(npcDat);
		load();
	}
	
	public AirShip getNewAirShip(int x, int y, int z, int heading)
	{
		final AirShip airShip = new AirShip(_airShipTemplate);
		airShip.setHeading(heading);
		airShip.setXYZInvisible(x, y, z);
		airShip.spawnMe();
		airShip.getStat().setMoveSpeed(280);
		airShip.getStat().setRotationSpeed(2000);
		return airShip;
	}
	
	public AirShip getNewAirShip(int x, int y, int z, int heading, int ownerId)
	{
		final StatSet info = _airShipsInfo.get(ownerId);
		if (info == null)
		{
			return null;
		}
		
		final AirShip airShip;
		if (_airShips.containsKey(ownerId))
		{
			airShip = _airShips.get(ownerId);
			airShip.refreshId();
		}
		else
		{
			airShip = new ControllableAirShip(_airShipTemplate, ownerId);
			_airShips.put(ownerId, airShip);
			
			airShip.setMaxFuel(600);
			airShip.setFuel(info.getInt("fuel"));
			airShip.getStat().setMoveSpeed(280);
			airShip.getStat().setRotationSpeed(2000);
		}
		
		airShip.setHeading(heading);
		airShip.setXYZInvisible(x, y, z);
		airShip.spawnMe();
		return airShip;
	}
	
	public void removeAirShip(AirShip ship)
	{
		if (ship.getOwnerId() == 0)
		{
			return;
		}
		
		storeInDb(ship.getOwnerId());
		final StatSet info = _airShipsInfo.get(ship.getOwnerId());
		if (info != null)
		{
			info.set("fuel", ship.getFuel());
		}
	}
	
	public boolean hasAirShipLicense(int ownerId)
	{
		return _airShipsInfo.containsKey(ownerId);
	}
	
	public void registerLicense(int ownerId)
	{
		if (_airShipsInfo.containsKey(ownerId))
		{
			return;
		}
		
		final StatSet info = new StatSet();
		info.set("fuel", 600);
		_airShipsInfo.put(ownerId, info);
		try (Connection con = DatabaseFactory.getConnection();
			PreparedStatement ps = con.prepareStatement(ADD_DB))
		{
			ps.setInt(1, ownerId);
			ps.setInt(2, info.getInt("fuel"));
			ps.executeUpdate();
		}
		catch (SQLException e)
		{
			LOGGER.log(Level.WARNING, getClass().getSimpleName() + ": Could not add new airship license: " + e.getMessage(), e);
		}
		catch (Exception e)
		{
			LOGGER.log(Level.WARNING, getClass().getSimpleName() + ": Error while initializing: " + e.getMessage(), e);
		}
	}
	
	public boolean hasAirShip(int ownerId)
	{
		final AirShip ship = _airShips.get(ownerId);
		return (ship != null) && (ship.isSpawned() || ship.isTeleporting());
	}
	
	public void registerAirShipTeleportList(int dockId, int locationId, VehiclePathPoint[][] tp, int[] fuelConsumption)
	{
		if (tp.length != fuelConsumption.length)
		{
			return;
		}
		
		_teleports.put(dockId, new AirShipTeleportList(locationId, fuelConsumption, tp));
	}
	
	public void sendAirShipTeleportList(Player player)
	{
		if ((player == null) || !player.isInAirShip())
		{
			return;
		}
		
		final AirShip ship = player.getAirShip();
		if (!ship.isCaptain(player) || !ship.isInDock() || ship.isMoving())
		{
			return;
		}
		
		final int dockId = ship.getDockId();
		if (!_teleports.containsKey(dockId))
		{
			return;
		}
		
		final AirShipTeleportList all = _teleports.get(dockId);
		player.sendPacket(new ExAirShipTeleportList(all.getLocation(), all.getRoute(), all.getFuel()));
	}
	
	public VehiclePathPoint[] getTeleportDestination(int dockId, int index)
	{
		final AirShipTeleportList all = _teleports.get(dockId);
		return (all == null) || (index < -1) || (index >= all.getRoute().length) ? null : all.getRoute()[index + 1];
	}
	
	public int getFuelConsumption(int dockId, int index)
	{
		final AirShipTeleportList all = _teleports.get(dockId);
		return (all == null) || (index < -1) || (index >= all.getFuel().length) ? 0 : all.getFuel()[index + 1];
	}
	
	private void load()
	{
		try (Connection con = DatabaseFactory.getConnection();
			Statement s = con.createStatement();
			ResultSet rs = s.executeQuery(LOAD_DB))
		{
			StatSet info;
			while (rs.next())
			{
				info = new StatSet();
				info.set("fuel", rs.getInt("fuel"));
				_airShipsInfo.put(rs.getInt("owner_id"), info);
			}
		}
		catch (SQLException e)
		{
			LOGGER.log(Level.WARNING, getClass().getSimpleName() + ": Could not load airships table: " + e.getMessage(), e);
		}
		catch (Exception e)
		{
			LOGGER.log(Level.WARNING, getClass().getSimpleName() + ": Error while initializing: " + e.getMessage(), e);
		}
		
		LOGGER.info(getClass().getSimpleName() + ": Loaded " + _airShipsInfo.size() + " private airships");
	}
	
	private void storeInDb(int ownerId)
	{
		final StatSet info = _airShipsInfo.get(ownerId);
		if (info == null)
		{
			return;
		}
		
		try (Connection con = DatabaseFactory.getConnection();
			PreparedStatement ps = con.prepareStatement(UPDATE_DB))
		{
			ps.setInt(1, info.getInt("fuel"));
			ps.setInt(2, ownerId);
			ps.executeUpdate();
		}
		catch (SQLException e)
		{
			LOGGER.log(Level.WARNING, getClass().getSimpleName() + ": Could not update airships table: " + e.getMessage(), e);
		}
		catch (Exception e)
		{
			LOGGER.log(Level.WARNING, getClass().getSimpleName() + ": Error while save: " + e.getMessage(), e);
		}
	}
	
	public static AirShipManager getInstance()
	{
		return SingletonHolder.INSTANCE;
	}
	
	private static class SingletonHolder
	{
		protected static final AirShipManager INSTANCE = new AirShipManager();
	}
}
