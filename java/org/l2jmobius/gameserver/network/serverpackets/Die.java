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
package org.l2jmobius.gameserver.network.serverpackets;

import org.l2jmobius.commons.network.WritableBuffer;
import org.l2jmobius.gameserver.data.holders.AccessLevel;
import org.l2jmobius.gameserver.data.xml.AdminData;
import org.l2jmobius.gameserver.managers.CHSiegeManager;
import org.l2jmobius.gameserver.managers.CastleManager;
import org.l2jmobius.gameserver.managers.FortManager;
import org.l2jmobius.gameserver.managers.TerritoryWarManager;
import org.l2jmobius.gameserver.model.actor.Creature;
import org.l2jmobius.gameserver.model.actor.Player;
import org.l2jmobius.gameserver.model.clan.Clan;
import org.l2jmobius.gameserver.model.olympiad.OlympiadManager;
import org.l2jmobius.gameserver.model.siege.Castle;
import org.l2jmobius.gameserver.model.siege.Fort;
import org.l2jmobius.gameserver.model.siege.SiegeClan;
import org.l2jmobius.gameserver.model.siege.clanhalls.SiegableHall;
import org.l2jmobius.gameserver.network.GameClient;
import org.l2jmobius.gameserver.network.ServerPackets;

public class Die extends ServerPacket
{
	private final int _objectId;
	private final boolean _canTeleport;
	private final boolean _sweepable;
	private AccessLevel _access = AdminData.getInstance().getAccessLevel(0);
	private Clan _clan;
	private final Creature _creature;
	private boolean _isJailed;
	private boolean _staticRes = false;
	
	public Die(Creature creature)
	{
		_objectId = creature.getObjectId();
		_creature = creature;
		if (creature.isPlayer())
		{
			final Player player = creature.asPlayer();
			_access = player.getAccessLevel();
			_clan = player.getClan();
			_isJailed = player.isJailed();
		}
		
		_canTeleport = creature.canRevive() && !creature.isPendingRevive();
		_sweepable = creature.isSweepActive();
	}
	
	@Override
	public void writeImpl(GameClient client, WritableBuffer buffer)
	{
		ServerPackets.DIE.writeId(this, buffer);
		buffer.writeInt(_objectId);
		buffer.writeInt(_canTeleport);
		if (_creature.isPlayer())
		{
			final Player player = _creature.asPlayer();
			if (!OlympiadManager.getInstance().isRegistered(player) && !player.isOnEvent())
			{
				_staticRes = _creature.getInventory().haveItemForSelfResurrection();
			}
			
			// Verify if player can use fixed resurrection without Feather
			if (_access.allowFixedRes())
			{
				_staticRes = true;
			}
		}
		
		if (_canTeleport && (_clan != null) && !_isJailed)
		{
			boolean isInCastleDefense = false;
			boolean isInFortDefense = false;
			SiegeClan siegeClan = null;
			final Castle castle = CastleManager.getInstance().getCastle(_creature);
			final Fort fort = FortManager.getInstance().getFort(_creature);
			final SiegableHall hall = CHSiegeManager.getInstance().getNearbyClanHall(_creature);
			if ((castle != null) && castle.getSiege().isInProgress())
			{
				// siege in progress
				siegeClan = castle.getSiege().getAttackerClan(_clan);
				if ((siegeClan == null) && castle.getSiege().checkIsDefender(_clan))
				{
					isInCastleDefense = true;
				}
			}
			else if ((fort != null) && fort.getSiege().isInProgress())
			{
				// siege in progress
				siegeClan = fort.getSiege().getAttackerClan(_clan);
				if ((siegeClan == null) && fort.getSiege().checkIsDefender(_clan))
				{
					isInFortDefense = true;
				}
			}
			
			buffer.writeInt(_clan.getHideoutId() > 0); // 6d 01 00 00 00 - to hide away
			buffer.writeInt((_clan.getCastleId() > 0) || isInCastleDefense); // 6d 02 00 00 00 - to castle
			buffer.writeInt((TerritoryWarManager.getInstance().getHQForClan(_clan) != null) || ((siegeClan != null) && !isInCastleDefense && !isInFortDefense && !siegeClan.getFlag().isEmpty()) || ((hall != null) && (hall.getSiege() != null) && hall.getSiege().checkIsAttacker(_clan))); // siege HQ
			buffer.writeInt(_sweepable); // sweepable (blue glow)
			buffer.writeInt(_staticRes); // 6d 04 00 00 00 - to FIXED
			buffer.writeInt((_clan.getFortId() > 0) || isInFortDefense); // 6d 05 00 00 00 - to fortress
		}
		else
		{
			buffer.writeInt(0); // 6d 01 00 00 00 - to hide away
			buffer.writeInt(0); // 6d 02 00 00 00 - to castle
			buffer.writeInt(0); // 6d 03 00 00 00 - to siege HQ
			buffer.writeInt(_sweepable); // sweepable (blue glow)
			buffer.writeInt(_staticRes); // 6d 04 00 00 00 - to FIXED
			buffer.writeInt(0); // 6d 05 00 00 00 - to fortress
		}
		
		// TODO: protocol 152
		// buffer.writeByte(0); // show die animation
		// buffer.writeInt(0); // agathion ress button
		// buffer.writeInt(0); // additional free space
	}
}
