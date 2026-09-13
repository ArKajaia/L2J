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
package org.l2jmobius.gameserver.model.actor.instance;

import java.util.StringTokenizer;

import org.l2jmobius.gameserver.model.actor.Player;
import org.l2jmobius.gameserver.model.actor.enums.creature.InstanceType;
import org.l2jmobius.gameserver.model.actor.templates.NpcTemplate;
import org.l2jmobius.gameserver.network.serverpackets.ActionFailed;
import org.l2jmobius.gameserver.network.serverpackets.NpcHtmlMessage;

public class FortDoorman extends Doorman
{
	/**
	 * Creates a fort doorman.
	 * @param template the fort doorman NPC template
	 */
	public FortDoorman(NpcTemplate template)
	{
		super(template);
		setInstanceType(InstanceType.FortDoorman);
	}
	
	@Override
	public void showChatWindow(Player player)
	{
		player.sendPacket(ActionFailed.STATIC_PACKET);
		
		final NpcHtmlMessage html = new NpcHtmlMessage(getObjectId());
		if (!isOwnerClan(player))
		{
			html.setFile(player, "data/html/doorman/" + getTemplate().getId() + "-no.htm");
		}
		else if (isUnderSiege())
		{
			html.setFile(player, "data/html/doorman/" + getTemplate().getId() + "-busy.htm");
		}
		else
		{
			html.setFile(player, "data/html/doorman/" + getTemplate().getId() + ".htm");
		}
		
		html.replace("%objectId%", String.valueOf(getObjectId()));
		player.sendPacket(html);
	}
	
	@Override
	protected void openDoors(Player player, String command)
	{
		final StringTokenizer st = new StringTokenizer(command.substring(10), ", ");
		st.nextToken();
		
		while (st.hasMoreTokens())
		{
			getFort().openDoor(player, Integer.parseInt(st.nextToken()));
		}
	}
	
	@Override
	protected void closeDoors(Player player, String command)
	{
		final StringTokenizer st = new StringTokenizer(command.substring(11), ", ");
		st.nextToken();
		
		while (st.hasMoreTokens())
		{
			getFort().closeDoor(player, Integer.parseInt(st.nextToken()));
		}
	}
	
	@Override
	protected final boolean isOwnerClan(Player player)
	{
		return (player.getClan() != null) && (getFort() != null) && (getFort().getOwnerClan() != null) && (player.getClanId() == getFort().getOwnerClan().getId());
	}
	
	@Override
	protected final boolean isUnderSiege()
	{
		return getFort().getZone().isActive();
	}
}
