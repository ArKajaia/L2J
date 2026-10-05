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
import org.l2jmobius.gameserver.model.actor.holders.npc.FakePlayerPvpProfile;
import org.l2jmobius.gameserver.network.GameClient;
import org.l2jmobius.gameserver.network.ServerPackets;

/**
 * A fake player that joined the party, like {@link PartySmallWindowAdd} for a player (see {@link org.l2jmobius.gameserver.model.actor.holders.npc.FakePlayerParty}).
 */
public class FakePartySmallWindowAdd extends ServerPacket
{
	private final Npc _fake;
	private final int _leaderObjectId;
	private final int _distributionType;
	
	public FakePartySmallWindowAdd(Npc fake, int leaderObjectId, int distributionType)
	{
		_fake = fake;
		_leaderObjectId = leaderObjectId;
		_distributionType = distributionType;
	}
	
	@Override
	public void writeImpl(GameClient client, WritableBuffer buffer)
	{
		ServerPackets.PARTY_SMALL_WINDOW_ADD.writeId(this, buffer);
		buffer.writeInt(_leaderObjectId);
		buffer.writeInt(_distributionType);
		buffer.writeInt(_fake.getObjectId());
		buffer.writeString(_fake.getName());
		buffer.writeInt(0); // CP
		buffer.writeInt(0); // Max CP
		buffer.writeInt((int) _fake.getCurrentHp());
		buffer.writeInt(_fake.getMaxHp());
		buffer.writeInt((int) _fake.getCurrentMp());
		buffer.writeInt(_fake.getMaxMp());
		buffer.writeInt(_fake.getLevel());
		buffer.writeInt(getClassId(_fake));
		buffer.writeInt(0);
		buffer.writeInt(0);
	}
	
	/**
	 * @param fake a fake player
	 * @return the class it shows
	 */
	static int getClassId(Npc fake)
	{
		final FakePlayerPvpProfile profile = fake.getTemplate().getFakePlayerPvpProfile();
		return profile != null ? profile.getPlayerClass().getId() : 0;
	}
	
	/**
	 * @param fake a fake player
	 * @return the race of its class
	 */
	static int getRace(Npc fake)
	{
		final FakePlayerPvpProfile profile = fake.getTemplate().getFakePlayerPvpProfile();
		return profile != null ? profile.getPlayerClass().getRace().ordinal() : 0;
	}
}
