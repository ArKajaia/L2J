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
package custom.HiddenQuests;

import java.util.HashSet;
import java.util.Set;

import org.l2jmobius.gameserver.config.custom.HiddenQuestConfig;
import org.l2jmobius.gameserver.data.xml.HiddenQuestData;
import org.l2jmobius.gameserver.managers.HiddenQuestManager;
import org.l2jmobius.gameserver.model.actor.Npc;
import org.l2jmobius.gameserver.model.actor.Player;
import org.l2jmobius.gameserver.model.hiddenquest.HiddenQuestDefinition;
import org.l2jmobius.gameserver.model.script.Quest;

/**
 * Dialogs of the Hidden Quest messengers. Every decision is made by the core {@link HiddenQuestManager}; this script only forwards the talk and the buttons. The pages live next to this script.
 * @author Mobius
 */
public class HiddenQuests extends Quest
{
	public HiddenQuests()
	{
		super(-1);
		if (!HiddenQuestConfig.ENABLED)
		{
			return;
		}

		final Set<Integer> messengers = new HashSet<>();
		for (HiddenQuestDefinition quest : HiddenQuestData.getInstance().getQuests())
		{
			messengers.add(quest.getMessengerNpcId());
		}
		for (int npcId : messengers)
		{
			addStartNpc(npcId);
			addFirstTalkId(npcId);
			addTalkId(npcId);
		}
	}

	@Override
	public String onEvent(String event, Npc npc, Player player)
	{
		if ((npc == null) || (player == null))
		{
			return null;
		}

		final HiddenQuestManager manager = HiddenQuestManager.getInstance();
		switch (event)
		{
			case "offer":
			{
				return manager.showOffer(player, npc) ? null : getHtm(player, "not_yours.html");
			}
			case "accept":
			{
				final String problem = manager.accept(player, npc);
				if (problem != null)
				{
					player.sendMessage(problem);
				}
				return null;
			}
			case "later":
			{
				manager.postpone(player, npc);
				return null;
			}
			case "never":
			{
				return manager.getOffer(player, npc) != null ? getHtm(player, "never.html") : null;
			}
			case "never_confirm":
			{
				manager.forfeit(player, npc);
				return null;
			}
		}
		return null;
	}

	@Override
	public String onFirstTalk(Npc npc, Player player)
	{
		return HiddenQuestManager.getInstance().showOffer(player, npc) ? null : getHtm(player, "not_yours.html");
	}

	public static void main(String[] args)
	{
		new HiddenQuests();
	}
}
