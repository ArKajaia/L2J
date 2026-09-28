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
package handlers.bypass.npc;

import org.l2jmobius.gameserver.handler.IBypassHandler;
import org.l2jmobius.gameserver.model.actor.Creature;
import org.l2jmobius.gameserver.model.actor.Player;
import org.l2jmobius.gameserver.model.item.instance.Item;
import org.l2jmobius.gameserver.network.clientpackets.AbstractRefinePacket;

import handlers.items.LifeStone;

/**
 * Buttons of the Life Stone augmentation window (see {@link LifeStone}). Format:
 * <ul>
 * <li>{@code lifestone_augment <lifeStoneObjId> <page>} shows a page of the equipment list</li>
 * <li>{@code lifestone_augment <lifeStoneObjId> <page> <targetObjId>} shows the costs of augmenting an item and asks for confirmation</li>
 * <li>{@code lifestone_augment <lifeStoneObjId> <page> <targetObjId> confirm} augments it (replacing its augmentation if it has one)</li>
 * </ul>
 */
public class LifeStoneAugment implements IBypassHandler
{
	private static final String[] COMMANDS =
	{
		LifeStone.BYPASS
	};
	
	@Override
	public boolean onCommand(String command, Player player, Creature target)
	{
		final String[] parts = command.trim().split("\\s+");
		if (parts.length < 3)
		{
			return false;
		}
		
		final int lifeStoneObjId;
		final int page;
		final int targetObjId;
		try
		{
			lifeStoneObjId = Integer.parseInt(parts[1]);
			page = Integer.parseInt(parts[2]);
			targetObjId = parts.length > 3 ? Integer.parseInt(parts[3]) : 0;
		}
		catch (NumberFormatException e)
		{
			return false;
		}
		
		final Item lifeStone = player.getInventory().getItemByObjectId(lifeStoneObjId);
		if ((lifeStone == null) || !AbstractRefinePacket.canAugment(player))
		{
			return true;
		}
		
		if (targetObjId == 0)
		{
			LifeStone.showList(player, lifeStone, page, null);
			return true;
		}
		
		final Item item = player.getInventory().getItemByObjectId(targetObjId);
		if ((parts.length < 5) || !LifeStone.CONFIRM.equals(parts[4]) || (item == null))
		{
			LifeStone.showItem(player, lifeStone, page, item, null);
			return true;
		}
		
		final String failure = AbstractRefinePacket.augmentFromInventory(player, item, lifeStone);
		final String notice = failure == null ? "<font color=\"88CC88\">The item was successfully augmented!</font><br1>New augment: " + LifeStone.describe(item.getAugmentation()) : "<font color=\"CC6666\">" + failure + "</font>";
		
		// Back to the same item so it can be augmented again; the stack object is gone once the last life stone is used.
		LifeStone.showItem(player, player.getInventory().getItemByObjectId(lifeStoneObjId), page, item, notice);
		return true;
	}
	
	@Override
	public String[] getCommandList()
	{
		return COMMANDS;
	}
}
