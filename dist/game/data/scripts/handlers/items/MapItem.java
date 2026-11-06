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
package handlers.items;

import org.l2jmobius.gameserver.data.xml.MapDeviceData;
import org.l2jmobius.gameserver.handler.IItemHandler;
import org.l2jmobius.gameserver.managers.MapDeviceManager;
import org.l2jmobius.gameserver.model.actor.Playable;
import org.l2jmobius.gameserver.model.actor.Player;
import org.l2jmobius.gameserver.model.item.instance.Item;
import org.l2jmobius.gameserver.model.mapdevice.MapRun;
import org.l2jmobius.gameserver.model.mapdevice.MapTier;
import org.l2jmobius.gameserver.model.mapdevice.UniqueMap;
import org.l2jmobius.gameserver.network.SystemMessageId;

import custom.MapDevice.MapDeviceWindow;

/**
 * A "Map (Tier N)" or a unique map: double-clicked in its owner's hideout, it goes in the Map Device (the medal choice window opens); anywhere else, it tells the player how to use it. The map is only taken when the map opens.
 * @author Mobius
 */
public class MapItem implements IItemHandler
{
	@Override
	public boolean onItemUse(Playable playable, Item item, boolean forceUse)
	{
		if (!playable.isPlayer())
		{
			playable.sendPacket(SystemMessageId.YOUR_PET_CANNOT_CARRY_THIS_ITEM);
			return false;
		}

		final Player player = playable.asPlayer();
		final MapTier tier = MapDeviceData.getInstance().getTierByItem(item.getId());
		final UniqueMap unique = tier == null ? MapDeviceData.getInstance().getUniqueMapByItem(item.getId()) : null;
		if ((tier == null) && (unique == null))
		{
			return false;
		}

		final MapDeviceManager manager = MapDeviceManager.getInstance();
		if (!manager.isDeviceOwner(player))
		{
			player.sendMessage("Put this map in the Map Device of your hideout: type .hideout in a town, then double-click it there.");
			MapDeviceWindow.showDevice(player);
			return false;
		}

		final MapRun run = manager.getCurrentRun(player);
		if ((run != null) && !run.isClosed())
		{
			player.sendMessage("A map is already open in your Map Device.");
			MapDeviceWindow.showDevice(player);
			return false;
		}

		if (unique != null)
		{
			MapDeviceWindow.showUniqueInsert(player, unique);
		}
		else
		{
			MapDeviceWindow.showInsert(player, tier.tier());
		}
		return true;
	}
}
