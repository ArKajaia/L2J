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

import java.util.ArrayList;
import java.util.List;

import org.l2jmobius.commons.network.WritableBuffer;
import org.l2jmobius.gameserver.model.actor.Player;
import org.l2jmobius.gameserver.model.undergroundColiseum.UCArena;
import org.l2jmobius.gameserver.model.undergroundColiseum.UCTeam;
import org.l2jmobius.gameserver.network.GameClient;
import org.l2jmobius.gameserver.network.ServerPackets;

public class ExPVPMatchRecord extends ServerPacket
{
	public static final int START = 0;
	public static final int UPDATE = 1;
	public static final int FINISH = 2;
	
	private final int _type;
	private final int _winnerTeam;
	private final int _blueKills;
	private final int _redKills;
	private final List<Member> _blueList;
	private final List<Member> _redList;
	
	public ExPVPMatchRecord(int type, int winnerTeam, UCArena arena)
	{
		_type = type;
		_winnerTeam = winnerTeam;
		
		final UCTeam blueTeam = arena.getTeams()[0];
		_blueKills = blueTeam.getKillCount();
		final UCTeam redTeam = arena.getTeams()[1];
		_redKills = redTeam.getKillCount();
		
		_blueList = new ArrayList<>(9);
		
		if (blueTeam.getParty() != null)
		{
			for (Player memberObject : blueTeam.getParty().getMembers())
			{
				if (memberObject != null)
				{
					_blueList.add(new Member(memberObject.getName(), memberObject.getUCKills(), memberObject.getUCDeaths()));
				}
			}
		}
		
		_redList = new ArrayList<>(9);
		
		if (redTeam.getParty() != null)
		{
			for (Player memberObject : redTeam.getParty().getMembers())
			{
				if (memberObject != null)
				{
					_redList.add(new Member(memberObject.getName(), memberObject.getUCKills(), memberObject.getUCDeaths()));
				}
			}
		}
	}
	
	public ExPVPMatchRecord(int type, int winnerTeam)
	{
		_type = type;
		_winnerTeam = winnerTeam;
		_blueKills = 0;
		_redKills = 0;
		_blueList = new ArrayList<>(9);
		_redList = new ArrayList<>(9);
	}
	
	@Override
	public void writeImpl(GameClient client, WritableBuffer buffer)
	{
		ServerPackets.EX_PVP_MATCH_RECORD.writeId(this, buffer);
		buffer.writeInt(_type);
		buffer.writeInt(_winnerTeam);
		buffer.writeInt(_winnerTeam == 0 ? 0 : _winnerTeam == 1 ? 2 : 1);
		buffer.writeInt(_blueKills);
		buffer.writeInt(_redKills);
		buffer.writeInt(_blueList.size());
		for (Member member : _blueList)
		{
			buffer.writeString(member._name);
			buffer.writeInt(member._kills);
			buffer.writeInt(member._deaths);
		}
		
		buffer.writeInt(_redList.size());
		for (Member member : _redList)
		{
			buffer.writeString(member._name);
			buffer.writeInt(member._kills);
			buffer.writeInt(member._deaths);
		}
	}
	
	public static class Member
	{
		public String _name;
		public int _kills;
		public int _deaths;
		
		public Member(String name, int kills, int deaths)
		{
			_name = name;
			_kills = kills;
			_deaths = deaths;
		}
	}
}
