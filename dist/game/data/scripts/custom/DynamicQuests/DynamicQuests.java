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
package custom.DynamicQuests;

import java.util.HashSet;
import java.util.Set;

import org.l2jmobius.gameserver.config.custom.DynamicQuestConfig;
import org.l2jmobius.gameserver.data.xml.DynamicQuestData;
import org.l2jmobius.gameserver.managers.DynamicQuestManager;
import org.l2jmobius.gameserver.managers.DynamicQuestManager.Channel;
import org.l2jmobius.gameserver.model.actor.Npc;
import org.l2jmobius.gameserver.model.actor.Player;
import org.l2jmobius.gameserver.model.script.Quest;

/**
 * Dialogs of the Dynamic Quests: the Notice Boards, the heralds and the npcs the quests spawn. Everything else is in the DynamicQuestManager.
 * @author Mobius
 */
public class DynamicQuests extends Quest
{
	public DynamicQuests()
	{
		super(-1);
		if (!DynamicQuestConfig.ENABLED)
		{
			return;
		}

		final DynamicQuestData data = DynamicQuestData.getInstance();
		final Set<Integer> npcIds = new HashSet<>(data.getQuestNpcIds());
		npcIds.add(data.getNoticeBoardNpcId());
		npcIds.add(data.getHeraldNpcId());
		for (int npcId : npcIds)
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
		return DynamicQuestManager.getInstance().handleCommand(player, npc, Channel.NPC, event);
	}

	@Override
	public String onFirstTalk(Npc npc, Player player)
	{
		return DynamicQuestManager.getInstance().onTalk(player, npc);
	}

	public static void main(String[] args)
	{
		new DynamicQuests();
	}
}
