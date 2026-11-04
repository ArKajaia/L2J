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
package custom.RepeatableHunts;

import org.l2jmobius.gameserver.data.xml.RepeatableHuntData;
import org.l2jmobius.gameserver.managers.RepeatableHuntManager;
import org.l2jmobius.gameserver.model.actor.Npc;
import org.l2jmobius.gameserver.model.actor.Player;
import org.l2jmobius.gameserver.model.script.Quest;

/**
 * Dialogs of the repeatable hunts: their givers in the towns and their couriers in the field. Everything else is in the RepeatableHuntManager.
 * @author Mobius
 */
public class RepeatableHunts extends Quest
{
	public RepeatableHunts()
	{
		super(-1);
		final RepeatableHuntData data = RepeatableHuntData.getInstance();
		if (!data.isEnabled())
		{
			return;
		}
		for (int npcId : new int[]
		{
			data.getGiverNpcId(),
			data.getCourierNpcId()
		})
		{
			addStartNpc(npcId);
			addFirstTalkId(npcId);
			addTalkId(npcId);
		}
	}

	@Override
	public String onEvent(String event, Npc npc, Player player)
	{
		if (player == null)
		{
			return null;
		}
		return RepeatableHuntManager.getInstance().handleCommand(player, npc, event);
	}

	@Override
	public String onFirstTalk(Npc npc, Player player)
	{
		return RepeatableHuntManager.getInstance().onTalk(player, npc);
	}

	public static void main(String[] args)
	{
		new RepeatableHunts();
	}
}
