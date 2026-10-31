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
package custom.events.Conquest;

import org.l2jmobius.gameserver.config.custom.ConquestConfig;
import org.l2jmobius.gameserver.config.custom.TeamVsTeamConfig.TopReward;
import org.l2jmobius.gameserver.data.xml.ItemData;
import org.l2jmobius.gameserver.managers.conquest.ConquestManager;
import org.l2jmobius.gameserver.managers.conquest.ConquestRegistration;
import org.l2jmobius.gameserver.model.actor.Npc;
import org.l2jmobius.gameserver.model.actor.Player;
import org.l2jmobius.gameserver.model.conquest.ConquestMap;
import org.l2jmobius.gameserver.model.item.ItemTemplate;
import org.l2jmobius.gameserver.model.item.holders.ItemHolder;
import org.l2jmobius.gameserver.model.script.Event;
import org.l2jmobius.gameserver.network.serverpackets.NpcHtmlMessage;

/**
 * Conquest event: the dialogs of the Conquest Event Manager, and //event start/stop. The registration is in the ConquestRegistration, the match in the ConquestManager, see config/Custom/Conquest.ini.
 * @author Mobius
 */
public class Conquest extends Event
{
	private static final String HTML_PATH = "data/scripts/custom/events/Conquest/";

	private Conquest()
	{
		addStartNpc(ConquestRegistration.MANAGER_NPC_ID);
		addFirstTalkId(ConquestRegistration.MANAGER_NPC_ID);
		addTalkId(ConquestRegistration.MANAGER_NPC_ID);
	}

	@Override
	public String onEvent(String event, Npc npc, Player player)
	{
		if ((npc == null) || (player == null))
		{
			return null;
		}

		final ConquestRegistration registration = ConquestRegistration.getInstance();
		final String message;
		switch (event)
		{
			case "register":
			{
				message = registration.register(player);
				break;
			}
			case "registerParty":
			{
				message = registration.registerParty(player);
				break;
			}
			case "cancel":
			{
				message = registration.unregister(player);
				break;
			}
			default:
			{
				message = "";
				break;
			}
		}

		showMain(npc, player, message);
		return null;
	}

	@Override
	public String onFirstTalk(Npc npc, Player player)
	{
		showMain(npc, player, "");
		return null;
	}

	private void showMain(Npc npc, Player player, String message)
	{
		final ConquestRegistration registration = ConquestRegistration.getInstance();
		final ConquestMap map = registration.getMap();
		final int bracket = ConquestRegistration.getBracket(player.getLevel());
		final String you;
		if (registration.isRegistered(player) && (bracket >= 0))
		{
			you = "registered, levels " + ConquestRegistration.describe(ConquestConfig.BRACKETS.get(bracket)) + ".";
		}
		else if (bracket < 0)
		{
			you = "your level is outside every bracket.";
		}
		else
		{
			you = "not registered.";
		}

		final int seconds = registration.getSecondsLeft();
		final NpcHtmlMessage html = new NpcHtmlMessage(npc.getObjectId());
		html.setFile(player, HTML_PATH + "manager.html");
		html.replace("%message%", registration.isOpen() ? message : "Registration is closed.");
		html.replace("%map%", map != null ? map.name() : "the map");
		html.replace("%size%", map != null ? String.valueOf(ConquestRegistration.getPerTeam(map)) : "?");
		html.replace("%fight%", String.valueOf(ConquestConfig.TIME_LIMIT));
		html.replace("%reward%", describeReward());
		html.replace("%top%", describeTopRewards());
		html.replace("%brackets%", ConquestRegistration.describeBrackets());
		html.replace("%time%", (seconds / 60) + " min " + (seconds % 60) + " s");
		html.replace("%count%", String.valueOf(registration.getRegisteredCount()));
		html.replace("%bracket_count%", String.valueOf(bracket < 0 ? 0 : registration.getRegisteredCount(bracket)));
		html.replace("%you%", you);
		player.sendPacket(html);
	}

	private static String describeReward()
	{
		final StringBuilder sb = new StringBuilder();
		for (ItemHolder reward : ConquestConfig.WIN_REWARD)
		{
			final ItemTemplate item = ItemData.getInstance().getTemplate(reward.getId());
			sb.append(sb.length() > 0 ? ", " : "").append(reward.getCount()).append(' ').append(item != null ? item.getName() : String.valueOf(reward.getId()));
		}
		return sb.length() > 0 ? sb.toString() : "nothing";
	}

	private static String describeTopRewards()
	{
		final String places = TopReward.describe(ConquestConfig.TOP_REWARDS);
		return places.isEmpty() ? "" : " The best players of the whole winning team by contribution (kills and capture points taken) also get Crystal Scrolls of their grade: " + places + ".";
	}

	@Override
	public boolean eventStart(Player eventMaker)
	{
		final String refusal = ConquestRegistration.getInstance().open(null);
		if ((refusal != null) && (eventMaker != null))
		{
			eventMaker.sendMessage("Conquest: " + refusal + ".");
		}
		return refusal == null;
	}

	@Override
	public boolean eventStop()
	{
		ConquestRegistration.getInstance().cancel();
		ConquestManager.getInstance().stop();
		return true;
	}

	@Override
	public boolean eventBypass(Player player, String bypass)
	{
		return false;
	}

	public static void main(String[] args)
	{
		new Conquest();
	}
}
