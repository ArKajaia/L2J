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
package custom.events.TeamVsTeam;

import org.l2jmobius.gameserver.config.custom.TeamVsTeamConfig;
import org.l2jmobius.gameserver.config.custom.TeamVsTeamConfig.TopReward;
import org.l2jmobius.gameserver.data.xml.ItemData;
import org.l2jmobius.gameserver.managers.TeamVsTeamManager;
import org.l2jmobius.gameserver.model.actor.Npc;
import org.l2jmobius.gameserver.model.actor.Player;
import org.l2jmobius.gameserver.model.item.ItemTemplate;
import org.l2jmobius.gameserver.model.item.holders.ItemHolder;
import org.l2jmobius.gameserver.model.script.Event;
import org.l2jmobius.gameserver.model.tvt.TvTMap;
import org.l2jmobius.gameserver.network.serverpackets.NpcHtmlMessage;

/**
 * Team vs Team event: the dialogs of the TvT Event Manager, and //event start/stop. The event itself (schedule, brackets, teams, fake players, the fight) is in the TeamVsTeamManager, see config/Custom/TeamVsTeam.ini.
 * @author Mobius
 */
public class TvT extends Event
{
	private static final String HTML_PATH = "data/scripts/custom/events/TeamVsTeam/";

	private TvT()
	{
		addStartNpc(TeamVsTeamManager.MANAGER_NPC_ID);
		addFirstTalkId(TeamVsTeamManager.MANAGER_NPC_ID);
		addTalkId(TeamVsTeamManager.MANAGER_NPC_ID);
	}

	@Override
	public String onEvent(String event, Npc npc, Player player)
	{
		if ((npc == null) || (player == null))
		{
			return null;
		}

		final TeamVsTeamManager manager = TeamVsTeamManager.getInstance();
		final String message;
		switch (event)
		{
			case "register":
			{
				message = manager.register(player);
				break;
			}
			case "registerParty":
			{
				message = manager.registerParty(player);
				break;
			}
			case "cancel":
			{
				message = manager.unregister(player);
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
		final TeamVsTeamManager manager = TeamVsTeamManager.getInstance();
		final int bracket = TeamVsTeamManager.getBracket(player.getLevel());
		final String you;
		if (manager.isRegistered(player))
		{
			you = "registered, levels " + TeamVsTeamConfig.BRACKETS.get(bracket)[0] + "-" + TeamVsTeamConfig.BRACKETS.get(bracket)[1] + ".";
		}
		else if (bracket < 0)
		{
			you = "your level is outside every bracket.";
		}
		else
		{
			you = "not registered.";
		}

		final int seconds = manager.getRegistrationSecondsLeft();
		final NpcHtmlMessage html = new NpcHtmlMessage(npc.getObjectId());
		html.setFile(player, HTML_PATH + "manager.html");
		html.replace("%message%", manager.isRegistrationOpen() ? message : "Registration is closed.");
		html.replace("%size%", String.valueOf(TeamVsTeamConfig.TEAM_SIZE));
		final TvTMap map = manager.getRegistrationMap();
		html.replace("%map%", map != null ? map.name() : "battlefield picked when registration opens");
		html.replace("%fight%", String.valueOf(TeamVsTeamConfig.FIGHT_TIME));
		html.replace("%reward%", describeReward());
		html.replace("%top%", describeTopRewards());
		html.replace("%brackets%", TeamVsTeamManager.describeBrackets());
		html.replace("%time%", (seconds / 60) + " min " + (seconds % 60) + " s");
		html.replace("%count%", String.valueOf(manager.getRegisteredCount()));
		html.replace("%bracket_count%", String.valueOf(bracket < 0 ? 0 : manager.getRegisteredCount(bracket)));
		html.replace("%you%", you);
		player.sendPacket(html);
	}

	private static String describeReward()
	{
		final StringBuilder sb = new StringBuilder();
		for (ItemHolder reward : TeamVsTeamConfig.WIN_REWARD)
		{
			final ItemTemplate item = ItemData.getInstance().getTemplate(reward.getId());
			sb.append(sb.length() > 0 ? ", " : "").append(reward.getCount()).append(' ').append(item != null ? item.getName() : String.valueOf(reward.getId()));
		}
		return sb.length() > 0 ? sb.toString() : "nothing";
	}

	private static String describeTopRewards()
	{
		final String places = TopReward.describe(TeamVsTeamConfig.TOP_REWARDS);
		return places.isEmpty() ? "" : " The best players of the whole winning team (by kills) also get Crystal Scrolls of their grade: " + places + ".";
	}

	@Override
	public boolean eventStart(Player eventMaker)
	{
		final String refusal = TeamVsTeamManager.getInstance().openRegistration();
		if ((refusal != null) && (eventMaker != null))
		{
			eventMaker.sendMessage("TvT: " + refusal + ".");
		}
		return refusal == null;
	}

	@Override
	public boolean eventStop()
	{
		TeamVsTeamManager.getInstance().stop();
		return true;
	}

	@Override
	public boolean eventBypass(Player player, String bypass)
	{
		return false;
	}

	public static void main(String[] args)
	{
		new TvT();
	}
}
