/*
 * This file is part of the L2J Mobius project.
 * 
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 * 
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * General Public License for more details.
 * 
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>.
 */
package org.l2jmobius.gameserver.network.clientpackets;

import org.l2jmobius.gameserver.config.GeneralConfig;
import org.l2jmobius.gameserver.config.PlayerConfig;
import org.l2jmobius.gameserver.managers.MailManager;
import org.l2jmobius.gameserver.managers.PunishmentManager;
import org.l2jmobius.gameserver.model.actor.Player;
import org.l2jmobius.gameserver.model.zone.ZoneId;
import org.l2jmobius.gameserver.network.SystemMessageId;
import org.l2jmobius.gameserver.network.holders.MailMessage;
import org.l2jmobius.gameserver.network.serverpackets.ExChangePostState;

/**
 * @author Migi, DS
 */
public class RequestDeleteSentPost extends ClientPacket
{
	private static final int BATCH_LENGTH = 4; // length of the one item
	
	int[] _msgIds = null;
	
	@Override
	protected void readImpl()
	{
		final int count = readInt();
		if ((count <= 0) || (count > PlayerConfig.MAX_ITEM_IN_PACKET) || ((count * BATCH_LENGTH) != remaining()))
		{
			return;
		}
		
		_msgIds = new int[count];
		for (int i = 0; i < count; i++)
		{
			_msgIds[i] = readInt();
		}
	}
	
	@Override
	protected void runImpl()
	{
		final Player player = getPlayer();
		if ((player == null) || (_msgIds == null) || !GeneralConfig.ALLOW_MAIL)
		{
			return;
		}
		
		if (!player.isInsideZone(ZoneId.PEACE))
		{
			player.sendPacket(SystemMessageId.YOU_CANNOT_RECEIVE_OR_SEND_MAIL_WITH_ATTACHED_ITEMS_IN_NON_PEACE_ZONE_REGIONS);
			return;
		}
		
		for (int msgId : _msgIds)
		{
			final MailMessage msg = MailManager.getInstance().getMessage(msgId);
			if (msg == null)
			{
				continue;
			}
			
			if (msg.getSenderId() != player.getObjectId())
			{
				PunishmentManager.handleIllegalPlayerAction(player, player + " tried to delete not own post!", GeneralConfig.DEFAULT_PUNISH);
				return;
			}
			
			if (msg.hasAttachments() || msg.isDeletedBySender())
			{
				return;
			}
			
			msg.setDeletedBySender();
		}
		
		player.sendPacket(new ExChangePostState(false, _msgIds, MailMessage.DELETED));
	}
}
