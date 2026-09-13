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
package org.l2jmobius.gameserver.taskmanagers;

import java.util.Iterator;
import java.util.Map;
import java.util.Map.Entry;
import java.util.concurrent.ConcurrentHashMap;

import org.l2jmobius.commons.threads.ThreadPool;
import org.l2jmobius.gameserver.managers.MailManager;
import org.l2jmobius.gameserver.model.World;
import org.l2jmobius.gameserver.model.actor.Player;
import org.l2jmobius.gameserver.network.SystemMessageId;
import org.l2jmobius.gameserver.network.holders.MailMessage;
import org.l2jmobius.gameserver.network.serverpackets.SystemMessage;

/**
 * @author Mobius
 */
public class MessageDeletionTaskManager implements Runnable
{
	private static final Map<Integer, Long> PENDING_MESSAGES = new ConcurrentHashMap<>();
	private static boolean _working = false;
	
	protected MessageDeletionTaskManager()
	{
		ThreadPool.scheduleAtFixedRate(this, 10000, 10000);
	}
	
	@Override
	public void run()
	{
		if (_working)
		{
			return;
		}
		
		_working = true;
		
		if (!PENDING_MESSAGES.isEmpty())
		{
			final long currentTime = System.currentTimeMillis();
			final Iterator<Entry<Integer, Long>> iterator = PENDING_MESSAGES.entrySet().iterator();
			Entry<Integer, Long> entry;
			Integer messageId;
			MailMessage message;
			
			while (iterator.hasNext())
			{
				entry = iterator.next();
				if (currentTime > entry.getValue())
				{
					messageId = entry.getKey();
					message = MailManager.getInstance().getMessage(messageId);
					if (message == null)
					{
						iterator.remove();
						continue;
					}
					
					if (message.hasAttachments())
					{
						final Player sender = World.getInstance().getPlayer(message.getSenderId());
						final Player receiver = World.getInstance().getPlayer(message.getReceiverId());
						if (sender != null)
						{
							message.getAttachments().returnToWh(sender.getWarehouse());
							sender.sendPacket(SystemMessageId.THE_MAIL_WAS_RETURNED_DUE_TO_THE_EXCEEDED_WAITING_TIME);
						}
						else if (message.getSenderId() == -1) // Action House / Custom Mail
						{
							if (receiver != null)
							{
								message.getAttachments().returnToWh(receiver.getWarehouse());
							}
						}
						else
						{
							message.getAttachments().returnToWh(null);
						}
						
						message.getAttachments().deleteMe();
						message.removeAttachments();
						
						if (receiver != null)
						{
							receiver.sendPacket(new SystemMessage(SystemMessageId.THE_MAIL_WAS_RETURNED_DUE_TO_THE_EXCEEDED_WAITING_TIME));
						}
					}
					
					MailManager.getInstance().deleteMessageInDb(messageId);
					iterator.remove();
				}
			}
		}
		
		_working = false;
	}
	
	public void add(int msgId, long deletionTime)
	{
		PENDING_MESSAGES.put(msgId, deletionTime);
	}
	
	public static MessageDeletionTaskManager getInstance()
	{
		return SingletonHolder.INSTANCE;
	}
	
	private static class SingletonHolder
	{
		protected static final MessageDeletionTaskManager INSTANCE = new MessageDeletionTaskManager();
	}
}
