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

import java.util.List;

import org.l2jmobius.commons.network.WritableBuffer;
import org.l2jmobius.gameserver.model.actor.Creature;
import org.l2jmobius.gameserver.model.actor.Npc;
import org.l2jmobius.gameserver.model.actor.Player;
import org.l2jmobius.gameserver.model.actor.holders.npc.FakePlayerParty;
import org.l2jmobius.gameserver.model.groups.Party;
import org.l2jmobius.gameserver.model.groups.PartyDistributionType;
import org.l2jmobius.gameserver.network.GameClient;
import org.l2jmobius.gameserver.network.ServerPackets;

/**
 * The whole party window of a party with fake players (see {@link FakePlayerParty}): its players, like {@link PartySmallWindowAll}, and its fake players.
 */
public class FakePartySmallWindowAll extends ServerPacket
{
	private final int _leaderObjectId;
	private final int _distributionType;
	private final List<Creature> _members;
	
	/**
	 * @param viewer the player the window is for (not listed in it)
	 * @param party the party
	 */
	public FakePartySmallWindowAll(Player viewer, FakePlayerParty party)
	{
		_leaderObjectId = party.getLeaderObjectId();
		final Player host = party.getHost();
		final Party realParty = host != null ? host.getParty() : null;
		final PartyDistributionType distribution = realParty != null ? realParty.getDistributionType() : host != null ? host.getPartyDistributionType() : null;
		_distributionType = distribution != null ? distribution.getId() : PartyDistributionType.FINDERS_KEEPERS.getId();
		_members = party.getMembers();
		_members.remove(viewer);
	}
	
	@Override
	public void writeImpl(GameClient client, WritableBuffer buffer)
	{
		ServerPackets.PARTY_SMALL_WINDOW_ALL.writeId(this, buffer);
		buffer.writeInt(_leaderObjectId);
		buffer.writeInt(_distributionType);
		buffer.writeInt(_members.size());
		for (Creature member : _members)
		{
			if (member.isPlayer())
			{
				final Player player = member.asPlayer();
				buffer.writeInt(player.getObjectId());
				buffer.writeString(player.getName());
				buffer.writeInt((int) player.getCurrentCp());
				buffer.writeInt(player.getMaxCp());
				buffer.writeInt((int) player.getCurrentHp());
				buffer.writeInt(player.getMaxHp());
				buffer.writeInt((int) player.getCurrentMp());
				buffer.writeInt(player.getMaxMp());
				buffer.writeInt(player.getLevel());
				buffer.writeInt(player.getPlayerClass().getId());
				buffer.writeInt(0);
				buffer.writeInt(player.getRace().ordinal());
				buffer.writeInt(0);
				buffer.writeInt(0);
				if (player.hasSummon())
				{
					buffer.writeInt(player.getSummon().getObjectId());
					buffer.writeInt(player.getSummon().getId() + 1000000);
					buffer.writeInt(player.getSummon().getSummonType());
					buffer.writeString(player.getSummon().getName());
					buffer.writeInt((int) player.getSummon().getCurrentHp());
					buffer.writeInt(player.getSummon().getMaxHp());
					buffer.writeInt((int) player.getSummon().getCurrentMp());
					buffer.writeInt(player.getSummon().getMaxMp());
					buffer.writeInt(player.getSummon().getLevel());
				}
				else
				{
					buffer.writeInt(0);
				}
			}
			else
			{
				final Npc fake = member.asNpc();
				buffer.writeInt(fake.getObjectId());
				buffer.writeString(fake.getName());
				buffer.writeInt(0); // CP
				buffer.writeInt(0); // Max CP
				buffer.writeInt((int) fake.getCurrentHp());
				buffer.writeInt(fake.getMaxHp());
				buffer.writeInt((int) fake.getCurrentMp());
				buffer.writeInt(fake.getMaxMp());
				buffer.writeInt(fake.getLevel());
				buffer.writeInt(FakePartySmallWindowAdd.getClassId(fake));
				buffer.writeInt(0);
				buffer.writeInt(FakePartySmallWindowAdd.getRace(fake));
				buffer.writeInt(0);
				buffer.writeInt(0);
				buffer.writeInt(0); // No summon.
			}
		}
	}
}
