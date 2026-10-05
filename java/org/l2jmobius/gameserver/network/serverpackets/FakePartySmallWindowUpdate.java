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
import org.l2jmobius.gameserver.model.actor.Npc;
import org.l2jmobius.gameserver.network.GameClient;
import org.l2jmobius.gameserver.network.ServerPackets;

/**
 * HP/MP of a fake player in the party window, like {@link PartySmallWindowUpdate} for a player.
 */
public class FakePartySmallWindowUpdate extends ServerPacket
{
	private final Npc _fake;
	
	public FakePartySmallWindowUpdate(Npc fake)
	{
		_fake = fake;
	}
	
	@Override
	public void writeImpl(GameClient client, WritableBuffer buffer)
	{
		ServerPackets.PARTY_SMALL_WINDOW_UPDATE.writeId(this, buffer);
		buffer.writeInt(_fake.getObjectId());
		buffer.writeString(_fake.getName());
		buffer.writeInt(0); // CP
		buffer.writeInt(0); // Max CP
		buffer.writeInt(_fake.isDead() ? 0 : (int) _fake.getCurrentHp());
		buffer.writeInt(_fake.getMaxHp());
		buffer.writeInt((int) _fake.getCurrentMp());
		buffer.writeInt(_fake.getMaxMp());
		buffer.writeInt(_fake.getLevel());
		buffer.writeInt(FakePartySmallWindowAdd.getClassId(_fake));
	}
}
