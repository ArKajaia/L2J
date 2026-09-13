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
package org.l2jmobius.gameserver.model.undergroundColiseum;

import java.util.ArrayList;
import java.util.List;

import org.l2jmobius.gameserver.model.Location;
import org.l2jmobius.gameserver.model.actor.Player;
import org.l2jmobius.gameserver.model.actor.instance.Door;
import org.l2jmobius.gameserver.model.variables.PlayerVariables;

public class UCPoint
{
	private final Location _loc;
	private final List<Door> _doors;
	private final List<Player> _players = new ArrayList<>();
	
	public UCPoint(List<Door> doors, Location loc)
	{
		_doors = doors;
		_loc = loc;
	}
	
	public void teleportPlayer(Player player)
	{
		if (player == null)
		{
			return;
		}
		
		player.getVariables().set(PlayerVariables.RESTORE_LOCATION, player.getLocation().getX() + ";" + player.getLocation().getY() + ";" + player.getLocation().getZ());
		
		if (player.isDead())
		{
			UCTeam.resPlayer(player);
		}
		
		player.teleToLocation(_loc, true);
		_players.add(player);
	}
	
	public void actionDoors(boolean open)
	{
		if (_doors.isEmpty())
		{
			return;
		}
		
		for (Door door : _doors)
		{
			if (open)
			{
				door.openMe();
			}
			else
			{
				door.closeMe();
			}
		}
	}
	
	public Location getLocation()
	{
		return _loc;
	}
	
	public List<Player> getPlayers()
	{
		return _players;
	}
	
	public boolean checkPlayer(Player player)
	{
		if (_players.contains(player))
		{
			actionDoors(true);
			for (Player pl : _players)
			{
				if (pl != null)
				{
					pl.setUCState(Player.UC_STATE_ARENA);
				}
			}
			
			return true;
		}
		
		return false;
	}
}
