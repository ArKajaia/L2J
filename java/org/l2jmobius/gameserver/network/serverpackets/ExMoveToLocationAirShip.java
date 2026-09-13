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
import org.l2jmobius.gameserver.model.actor.Creature;
import org.l2jmobius.gameserver.network.GameClient;
import org.l2jmobius.gameserver.network.ServerPackets;

public class ExMoveToLocationAirShip extends ServerPacket
{
	private final int _objId;
	private final int _tx;
	private final int _ty;
	private final int _tz;
	private final int _x;
	private final int _y;
	private final int _z;
	
	public ExMoveToLocationAirShip(Creature creature)
	{
		_objId = creature.getObjectId();
		_tx = creature.getXdestination();
		_ty = creature.getYdestination();
		_tz = creature.getZdestination();
		_x = creature.getX();
		_y = creature.getY();
		_z = creature.getZ();
	}
	
	@Override
	public void writeImpl(GameClient client, WritableBuffer buffer)
	{
		ServerPackets.EX_MOVE_TO_LOCATION_AIRSHIP.writeId(this, buffer);
		buffer.writeInt(_objId);
		buffer.writeInt(_tx);
		buffer.writeInt(_ty);
		buffer.writeInt(_tz);
		buffer.writeInt(_x);
		buffer.writeInt(_y);
		buffer.writeInt(_z);
	}
}
