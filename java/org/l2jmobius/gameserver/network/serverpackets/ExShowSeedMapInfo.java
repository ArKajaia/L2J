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
import org.l2jmobius.gameserver.managers.SeedOfDestructionManager;
import org.l2jmobius.gameserver.managers.SeedOfInfinityManager;
import org.l2jmobius.gameserver.model.Location;
import org.l2jmobius.gameserver.network.GameClient;
import org.l2jmobius.gameserver.network.ServerPackets;

public class ExShowSeedMapInfo extends ServerPacket
{
	public static final ExShowSeedMapInfo STATIC_PACKET = new ExShowSeedMapInfo();
	
	private static final Location[] ENTRANCES =
	{
		new Location(-246857, 251960, 4331, 1),
		new Location(-213770, 210760, 4400, 2),
	};
	
	private ExShowSeedMapInfo()
	{
	}
	
	@Override
	public void writeImpl(GameClient client, WritableBuffer buffer)
	{
		ServerPackets.EX_SHOW_SEED_MAP_INFO.writeId(this, buffer);
		buffer.writeInt(ENTRANCES.length);
		for (Location loc : ENTRANCES)
		{
			buffer.writeInt(loc.getX());
			buffer.writeInt(loc.getY());
			buffer.writeInt(loc.getZ());
			switch (loc.getHeading())
			{
				case 1: // Seed of Destruction
				{
					buffer.writeInt(2770 + SeedOfDestructionManager.getInstance().getSoDState());
					break;
				}
				case 2: // Seed of Immortality
				{
					buffer.writeInt(SeedOfInfinityManager.getCurrentStage() + 2765);
					break;
				}
			}
		}
	}
}
