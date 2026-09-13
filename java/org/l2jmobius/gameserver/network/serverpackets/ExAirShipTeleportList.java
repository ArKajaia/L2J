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
import org.l2jmobius.gameserver.model.actor.holders.creature.VehiclePathPoint;
import org.l2jmobius.gameserver.network.GameClient;
import org.l2jmobius.gameserver.network.ServerPackets;

public class ExAirShipTeleportList extends ServerPacket
{
	private final int _dockId;
	private final VehiclePathPoint[][] _teleports;
	private final int[] _fuelConsumption;
	
	public ExAirShipTeleportList(int dockId, VehiclePathPoint[][] teleports, int[] fuelConsumption)
	{
		_dockId = dockId;
		_teleports = teleports;
		_fuelConsumption = fuelConsumption;
	}
	
	@Override
	public void writeImpl(GameClient client, WritableBuffer buffer)
	{
		ServerPackets.EX_AIRSHIP_TELEPORT_LIST.writeId(this, buffer);
		buffer.writeInt(_dockId);
		if (_teleports != null)
		{
			buffer.writeInt(_teleports.length);
			VehiclePathPoint[] path;
			VehiclePathPoint dst;
			for (int i = 0; i < _teleports.length; i++)
			{
				buffer.writeInt(i - 1);
				buffer.writeInt(_fuelConsumption[i]);
				path = _teleports[i];
				dst = path[path.length - 1];
				buffer.writeInt(dst.getX());
				buffer.writeInt(dst.getY());
				buffer.writeInt(dst.getZ());
			}
		}
		else
		{
			buffer.writeInt(0);
		}
	}
}
