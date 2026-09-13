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
package org.l2jmobius.gameserver.model.siege;

import org.l2jmobius.gameserver.managers.ItemManager;
import org.l2jmobius.gameserver.managers.TerritoryWarManager;
import org.l2jmobius.gameserver.model.Location;
import org.l2jmobius.gameserver.model.actor.Npc;
import org.l2jmobius.gameserver.model.actor.Player;
import org.l2jmobius.gameserver.model.item.enums.BodyPart;
import org.l2jmobius.gameserver.model.item.enums.ItemProcessType;
import org.l2jmobius.gameserver.model.item.instance.Item;
import org.l2jmobius.gameserver.network.SystemMessageId;
import org.l2jmobius.gameserver.network.serverpackets.InventoryUpdate;
import org.l2jmobius.gameserver.network.serverpackets.SystemMessage;

public class TerritoryWard
{
	protected Player _player = null;
	public int playerId = 0;
	private Item _item = null;
	private Npc _npc = null;
	
	private Location _location;
	private Location _oldLocation;
	
	private final int _itemId;
	private int _ownerCastleId;
	
	private final int _territoryId;
	
	public TerritoryWard(int territoryId, int x, int y, int z, int heading, int itemId, int castleId, Npc npc)
	{
		_territoryId = territoryId;
		_location = new Location(x, y, z, heading);
		_itemId = itemId;
		_ownerCastleId = castleId;
		_npc = npc;
	}
	
	public int getTerritoryId()
	{
		return _territoryId;
	}
	
	public int getOwnerCastleId()
	{
		return _ownerCastleId;
	}
	
	public void setOwnerCastleId(int newOwner)
	{
		_ownerCastleId = newOwner;
	}
	
	public Npc getNpc()
	{
		return _npc;
	}
	
	public void setNpc(Npc npc)
	{
		_npc = npc;
	}
	
	public Player getPlayer()
	{
		return _player;
	}
	
	public synchronized void spawnBack()
	{
		if (_player != null)
		{
			dropIt();
		}
		
		// Init the dropped WardInstance and add it in the world as a visible object at the position where last Pc got it
		_npc = TerritoryWarManager.getInstance().spawnNPC(36491 + _territoryId, _oldLocation);
	}
	
	public synchronized void spawnMe()
	{
		if (_player != null)
		{
			dropIt();
		}
		
		// Init the dropped WardInstance and add it in the world as a visible object at the position where Pc was last
		_npc = TerritoryWarManager.getInstance().spawnNPC(36491 + _territoryId, _location);
	}
	
	public synchronized void unSpawnMe()
	{
		if (_player != null)
		{
			dropIt();
		}
		
		if ((_npc != null) && !_npc.isDecayed())
		{
			_npc.deleteMe();
		}
	}
	
	public boolean activate(Player player, Item item)
	{
		if (player.isMounted())
		{
			player.sendPacket(SystemMessageId.YOU_DO_NOT_MEET_THE_REQUIRED_CONDITION_TO_EQUIP_THAT_ITEM);
			player.destroyItem(ItemProcessType.DESTROY, item, null, true);
			spawnMe();
			return false;
		}
		else if (TerritoryWarManager.getInstance().getRegisteredTerritoryId(player) == 0)
		{
			player.sendMessage("Non participants can't pickup Territory Wards!");
			player.destroyItem(ItemProcessType.DESTROY, item, null, true);
			spawnMe();
			return false;
		}
		
		// Player holding it data
		_player = player;
		playerId = _player.getObjectId();
		_oldLocation = new Location(_npc.getX(), _npc.getY(), _npc.getZ(), _npc.getHeading());
		_npc = null;
		
		// Equip with the weapon
		if (item == null)
		{
			_item = ItemManager.createItem(ItemProcessType.PICKUP, _itemId, 1, null, null);
		}
		else
		{
			_item = item;
		}
		
		_player.getInventory().equipItem(_item);
		final SystemMessage sm = new SystemMessage(SystemMessageId.YOU_HAVE_EQUIPPED_YOUR_S1);
		sm.addItemName(_item);
		_player.sendPacket(sm);
		
		// Refresh inventory
		final InventoryUpdate iu = new InventoryUpdate();
		iu.addItem(_item);
		_player.sendInventoryUpdate(iu);
		
		// Refresh player stats
		_player.broadcastUserInfo();
		_player.setCombatFlagEquipped(true);
		_player.sendPacket(SystemMessageId.YOU_VE_ACQUIRED_THE_WARD_MOVE_QUICKLY_TO_YOUR_FORCES_OUTPOST);
		TerritoryWarManager.getInstance().giveTWPoint(player, _territoryId, 5);
		return true;
	}
	
	public void dropIt()
	{
		// Reset player stats
		_player.setCombatFlagEquipped(false);
		final BodyPart bodyPart = BodyPart.fromItem(_item);
		_player.getInventory().unEquipItemInBodySlot(bodyPart);
		_player.destroyItem(ItemProcessType.DESTROY, _item, null, true);
		_item = null;
		_player.broadcastUserInfo();
		_location = new Location(_player.getX(), _player.getY(), _player.getZ(), _player.getHeading());
		_player = null;
		playerId = 0;
	}
}
