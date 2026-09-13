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
import org.l2jmobius.gameserver.model.actor.instance.AirShip;
import org.l2jmobius.gameserver.network.GameClient;
import org.l2jmobius.gameserver.network.ServerPackets;

public class ExAirShipInfo extends ServerPacket
{
	// store some parameters, because they can be changed during broadcast
	private final AirShip _ship;
	private final int _x;
	private final int _y;
	private final int _z;
	private final int _heading;
	private final int _moveSpeed;
	private final int _rotationSpeed;
	private final int _captain;
	private final int _helm;
	
	public ExAirShipInfo(AirShip ship)
	{
		_ship = ship;
		_x = ship.getX();
		_y = ship.getY();
		_z = ship.getZ();
		_heading = ship.getHeading();
		_moveSpeed = (int) ship.getStat().getMoveSpeed();
		_rotationSpeed = (int) ship.getStat().getRotationSpeed();
		_captain = ship.getCaptainId();
		_helm = ship.getHelmObjectId();
	}
	
	@Override
	public void writeImpl(GameClient client, WritableBuffer buffer)
	{
		ServerPackets.EX_AIRSHIP_INFO.writeId(this, buffer);
		buffer.writeInt(_ship.getObjectId());
		buffer.writeInt(_x);
		buffer.writeInt(_y);
		buffer.writeInt(_z);
		buffer.writeInt(_heading);
		buffer.writeInt(_captain);
		buffer.writeInt(_moveSpeed);
		buffer.writeInt(_rotationSpeed);
		buffer.writeInt(_helm);
		if (_helm != 0)
		{
			// TODO: unhardcode these!
			buffer.writeInt(0x16e); // Controller X
			buffer.writeInt(0x00); // Controller Y
			buffer.writeInt(0x6b); // Controller Z
			buffer.writeInt(0x15c); // Captain X
			buffer.writeInt(0x00); // Captain Y
			buffer.writeInt(0x69); // Captain Z
		}
		else
		{
			buffer.writeInt(0);
			buffer.writeInt(0);
			buffer.writeInt(0);
			buffer.writeInt(0);
			buffer.writeInt(0);
			buffer.writeInt(0);
		}
		
		buffer.writeInt(_ship.getFuel());
		buffer.writeInt(_ship.getMaxFuel());
	}
}
