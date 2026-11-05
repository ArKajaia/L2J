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
package custom.MapDevice;

import org.l2jmobius.gameserver.config.custom.MapDeviceConfig;
import org.l2jmobius.gameserver.managers.MapDeviceManager;
import org.l2jmobius.gameserver.model.actor.Npc;
import org.l2jmobius.gameserver.model.actor.Player;
import org.l2jmobius.gameserver.model.script.Quest;

/**
 * The NPCs of the Map Device (see {@link MapDeviceManager}): the device in a hideout opens its window, a portal round it takes the player into the map, and the return portal of a map takes them back to the hideout.
 * @author Mobius
 */
public class MapDevice extends Quest
{
	public MapDevice()
	{
		super(-1);
		addFirstTalkId(MapDeviceConfig.DEVICE_NPC_ID, MapDeviceConfig.PORTAL_NPC_ID, MapDeviceConfig.RETURN_PORTAL_NPC_ID);
		addTalkId(MapDeviceConfig.DEVICE_NPC_ID, MapDeviceConfig.PORTAL_NPC_ID, MapDeviceConfig.RETURN_PORTAL_NPC_ID);
	}

	@Override
	public String onFirstTalk(Npc npc, Player player)
	{
		final MapDeviceManager manager = MapDeviceManager.getInstance();
		final int npcId = npc.getId();
		if (npcId == MapDeviceConfig.PORTAL_NPC_ID)
		{
			final String denied = manager.usePortal(player, npc);
			if (denied != null)
			{
				player.sendMessage(denied);
			}
			return null;
		}
		if (npcId == MapDeviceConfig.RETURN_PORTAL_NPC_ID)
		{
			final String denied = manager.useReturnPortal(player);
			if (denied != null)
			{
				player.sendMessage(denied);
			}
			return null;
		}

		MapDeviceWindow.showDevice(player);
		return null;
	}

	@Override
	public String onTalk(Npc npc, Player player)
	{
		return onFirstTalk(npc, player);
	}

	public static void main(String[] args)
	{
		new MapDevice();
	}
}
