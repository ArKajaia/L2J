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
package org.l2jmobius.gameserver.model.actor.instance;

import java.util.concurrent.Future;

import org.l2jmobius.commons.threads.ThreadPool;
import org.l2jmobius.gameserver.managers.IdManager;
import org.l2jmobius.gameserver.model.actor.Player;
import org.l2jmobius.gameserver.model.actor.enums.creature.InstanceType;
import org.l2jmobius.gameserver.model.actor.stat.ControllableAirShipStat;
import org.l2jmobius.gameserver.model.actor.templates.CreatureTemplate;
import org.l2jmobius.gameserver.network.SystemMessageId;
import org.l2jmobius.gameserver.network.serverpackets.DeleteObject;
import org.l2jmobius.gameserver.network.serverpackets.SystemMessage;

public class ControllableAirShip extends AirShip
{
	private static final int HELM = 13556;
	private static final int LOW_FUEL = 40;
	
	private int _fuel = 0;
	private int _maxFuel = 0;
	
	private final int _ownerId;
	private int _helmId;
	private Player _captain = null;
	
	private Future<?> _consumeFuelTask;
	private Future<?> _checkTask;
	
	/**
	 * Creates a controllable air ship.
	 * @param template the controllable air ship template
	 * @param ownerId the owner ID
	 */
	public ControllableAirShip(CreatureTemplate template, int ownerId)
	{
		super(template);
		setInstanceType(InstanceType.ControllableAirShip);
		_ownerId = ownerId;
		_helmId = IdManager.getInstance().getNextId(); // not forget to release !
	}
	
	@Override
	public ControllableAirShipStat getStat()
	{
		return (ControllableAirShipStat) super.getStat();
	}
	
	@Override
	public void initCharStat()
	{
		setStat(new ControllableAirShipStat(this));
	}
	
	@Override
	public boolean canBeControlled()
	{
		return super.canBeControlled() && !isInDock();
	}
	
	@Override
	public boolean isOwner(Player player)
	{
		if (_ownerId == 0)
		{
			return false;
		}
		
		return (player.getClanId() == _ownerId) || (player.getObjectId() == _ownerId);
	}
	
	@Override
	public int getOwnerId()
	{
		return _ownerId;
	}
	
	@Override
	public boolean isCaptain(Player player)
	{
		return (_captain != null) && (player == _captain);
	}
	
	@Override
	public int getCaptainId()
	{
		return _captain != null ? _captain.getObjectId() : 0;
	}
	
	@Override
	public int getHelmObjectId()
	{
		return _helmId;
	}
	
	@Override
	public int getHelmItemId()
	{
		return HELM;
	}
	
	@Override
	public boolean setCaptain(Player player)
	{
		if (player == null)
		{
			_captain = null;
		}
		else
		{
			if ((_captain == null) && (player.getAirShip() == this))
			{
				final int x = player.getInVehiclePosition().getX() - 0x16e;
				final int y = player.getInVehiclePosition().getY();
				final int z = player.getInVehiclePosition().getZ() - 0x6b;
				if (((x * x) + (y * y) + (z * z)) > 2500)
				{
					player.sendPacket(SystemMessageId.YOU_CANNOT_CONTROL_BECAUSE_YOU_ARE_TOO_FAR);
					return false;
				}
				// TODO: Missing message ID: 2739 Message: You cannot control the helm because you do not meet the requirements.
				else if (player.isInCombat())
				{
					player.sendPacket(SystemMessageId.YOU_CANNOT_CONTROL_THE_HELM_WHILE_IN_A_BATTLE);
					return false;
				}
				else if (player.isSitting())
				{
					player.sendPacket(SystemMessageId.YOU_CANNOT_CONTROL_THE_HELM_WHILE_IN_A_SITTING_POSITION);
					return false;
				}
				else if (player.isParalyzed())
				{
					player.sendPacket(SystemMessageId.YOU_CANNOT_CONTROL_THE_HELM_WHILE_YOU_ARE_PETRIFIED);
					return false;
				}
				else if (player.isCursedWeaponEquipped())
				{
					player.sendPacket(SystemMessageId.YOU_CANNOT_CONTROL_THE_HELM_WHILE_A_CURSED_WEAPON_IS_EQUIPPED);
					return false;
				}
				else if (player.isFishing())
				{
					player.sendPacket(SystemMessageId.YOU_CANNOT_CONTROL_THE_HELM_WHILE_FISHING);
					return false;
				}
				else if (player.isDead() || player.isFakeDeath())
				{
					player.sendPacket(SystemMessageId.YOU_CANNOT_CONTROL_THE_HELM_WHEN_YOU_ARE_DEAD);
					return false;
				}
				else if (player.isCastingNow())
				{
					player.sendPacket(SystemMessageId.YOU_CANNOT_CONTROL_THE_HELM_WHILE_USING_A_SKILL);
					return false;
				}
				else if (player.isTransformed())
				{
					player.sendPacket(SystemMessageId.YOU_CANNOT_CONTROL_THE_HELM_WHILE_TRANSFORMED);
					return false;
				}
				else if (player.isCombatFlagEquipped())
				{
					player.sendPacket(SystemMessageId.YOU_CANNOT_CONTROL_THE_HELM_WHILE_HOLDING_A_FLAG);
					return false;
				}
				else if (player.isInDuel())
				{
					player.sendPacket(SystemMessageId.YOU_CANNOT_CONTROL_THE_HELM_WHILE_IN_A_DUEL);
					return false;
				}
				
				_captain = player;
				player.broadcastUserInfo();
			}
			else
			{
				return false;
			}
		}
		
		updateAbnormalEffect();
		return true;
	}
	
	@Override
	public int getFuel()
	{
		return _fuel;
	}
	
	@Override
	public void setFuel(int f)
	{
		final int old = _fuel;
		if (f < 0)
		{
			_fuel = 0;
		}
		else if (f > _maxFuel)
		{
			_fuel = _maxFuel;
		}
		else
		{
			_fuel = f;
		}
		
		if ((_fuel == 0) && (old > 0))
		{
			broadcastToPassengers(new SystemMessage(SystemMessageId.THE_AIRSHIP_S_FUEL_EP_HAS_RUN_OUT_THE_AIRSHIP_S_SPEED_WILL_BE_GREATLY_DECREASED_IN_THIS_CONDITION));
		}
		else if (_fuel < LOW_FUEL)
		{
			broadcastToPassengers(new SystemMessage(SystemMessageId.THE_AIRSHIP_S_FUEL_EP_WILL_SOON_RUN_OUT));
		}
	}
	
	@Override
	public int getMaxFuel()
	{
		return _maxFuel;
	}
	
	@Override
	public void setMaxFuel(int mf)
	{
		_maxFuel = mf;
	}
	
	@Override
	public void oustPlayer(Player player)
	{
		if (player == _captain)
		{
			setCaptain(null); // no need to broadcast userinfo here
		}
		
		super.oustPlayer(player);
	}
	
	@Override
	public void onSpawn()
	{
		super.onSpawn();
		_checkTask = ThreadPool.scheduleAtFixedRate(new CheckTask(), 60000, 10000);
		_consumeFuelTask = ThreadPool.scheduleAtFixedRate(new ConsumeFuelTask(), 60000, 60000);
	}
	
	@Override
	public boolean deleteMe()
	{
		if (!super.deleteMe())
		{
			return false;
		}
		
		if (_checkTask != null)
		{
			_checkTask.cancel(false);
			_checkTask = null;
		}
		
		if (_consumeFuelTask != null)
		{
			_consumeFuelTask.cancel(false);
			_consumeFuelTask = null;
		}
		
		broadcastPacket(new DeleteObject(_helmId));
		return true;
	}
	
	@Override
	public void refreshId()
	{
		super.refreshId();
		IdManager.getInstance().releaseId(_helmId);
		_helmId = IdManager.getInstance().getNextId();
	}
	
	@Override
	public void sendInfo(Player player)
	{
		super.sendInfo(player);
		if (_captain != null)
		{
			_captain.sendInfo(player);
		}
	}
	
	protected class ConsumeFuelTask implements Runnable
	{
		@Override
		public void run()
		{
			int fuel = getFuel();
			if (fuel > 0)
			{
				fuel -= 10;
				if (fuel < 0)
				{
					fuel = 0;
				}
				
				setFuel(fuel);
				updateAbnormalEffect();
			}
		}
	}
	
	protected class CheckTask implements Runnable
	{
		@Override
		public void run()
		{
			if (isSpawned() && isEmpty() && !isInDock())
			{
				// deleteMe() can't be called from CheckTask because task should not cancel itself
				ThreadPool.execute(new DecayTask());
			}
		}
	}
	
	protected class DecayTask implements Runnable
	{
		@Override
		public void run()
		{
			deleteMe();
		}
	}
}
