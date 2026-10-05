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
import org.l2jmobius.gameserver.model.actor.Player;
import org.l2jmobius.gameserver.model.clan.ClanMember;
import org.l2jmobius.gameserver.network.GameClient;
import org.l2jmobius.gameserver.network.ServerPackets;

public class PledgeShowMemberListAdd extends ServerPacket
{
	private final String _name;
	private final int _level;
	private final int _classId;
	private final int _isOnline;
	private final int _pledgeType;
	
	public PledgeShowMemberListAdd(Player player)
	{
		_name = player.getName();
		_level = player.getLevel();
		_classId = player.getPlayerClass().getId();
		_isOnline = (player.isOnline() ? player.getObjectId() : 0);
		_pledgeType = player.getPledgeType();
	}
	
	public PledgeShowMemberListAdd(ClanMember cm)
	{
		_name = cm.getName();
		_level = cm.getLevel();
		_classId = cm.getClassId();
		_isOnline = (cm.isOnline() ? cm.getObjectId() : 0);
		_pledgeType = cm.getPledgeType();
	}
	
	/**
	 * A fake player that joined a players' clan (see {@link org.l2jmobius.gameserver.managers.FakeClanManager}): a member of the main clan or of its academy.
	 * @param name its name
	 * @param level its level
	 * @param classId its class
	 * @param objectId the object id of its fake player while it is online, 0 while it is offline
	 * @param pledgeType 0 for the main clan, {@link org.l2jmobius.gameserver.model.clan.Clan#SUBUNIT_ACADEMY} for the academy
	 */
	public PledgeShowMemberListAdd(String name, int level, int classId, int objectId, int pledgeType)
	{
		_name = name;
		_level = level;
		_classId = classId;
		_isOnline = objectId;
		_pledgeType = pledgeType;
	}
	
	@Override
	public void writeImpl(GameClient client, WritableBuffer buffer)
	{
		ServerPackets.PLEDGE_SHOW_MEMBER_LIST_ADD.writeId(this, buffer);
		buffer.writeString(_name);
		buffer.writeInt(_level);
		buffer.writeInt(_classId);
		buffer.writeInt(0);
		buffer.writeInt(1);
		buffer.writeInt(_isOnline); // 1 = online 0 = offline
		buffer.writeInt(_pledgeType);
	}
}
