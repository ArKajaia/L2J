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
package custom.VillageSiege;

import org.l2jmobius.gameserver.config.custom.VillageSiegeConfig;
import org.l2jmobius.gameserver.managers.VillageSiegeBoard;
import org.l2jmobius.gameserver.managers.VillageSiegeManager;
import org.l2jmobius.gameserver.model.actor.Npc;
import org.l2jmobius.gameserver.model.actor.Player;
import org.l2jmobius.gameserver.model.script.Quest;

/**
 * Dialogs of the Defence Force Board of Village Under Siege. Everything else is in the VillageSiegeManager and VillageSiegeBoard.
 */
public class VillageSiege extends Quest
{
	public VillageSiege()
	{
		super(-1);
		if (!VillageSiegeConfig.ENABLED)
		{
			return;
		}

		addStartNpc(VillageSiegeManager.BOARD_NPC_ID);
		addFirstTalkId(VillageSiegeManager.BOARD_NPC_ID);
		addTalkId(VillageSiegeManager.BOARD_NPC_ID);
	}

	@Override
	public String onEvent(String event, Npc npc, Player player)
	{
		if ((player == null) || (npc == null))
		{
			return null;
		}
		return VillageSiegeBoard.handle(player, npc, event);
	}

	@Override
	public String onFirstTalk(Npc npc, Player player)
	{
		return VillageSiegeBoard.handle(player, npc, "main");
	}

	public static void main(String[] args)
	{
		new VillageSiege();
	}
}
