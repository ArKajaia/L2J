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

import java.util.List;

import org.l2jmobius.gameserver.config.custom.RaceWarsConfig;
import org.l2jmobius.gameserver.config.custom.TeamVsTeamConfig;
import org.l2jmobius.gameserver.config.custom.TeamVsTeamConfig.TopReward;
import org.l2jmobius.gameserver.data.xml.ItemData;
import org.l2jmobius.gameserver.managers.RaceWarsManager;
import org.l2jmobius.gameserver.managers.TeamVsTeamManager;
import org.l2jmobius.gameserver.managers.TeamVsTeamManager.Mode;
import org.l2jmobius.gameserver.managers.simulation.SimulationWar;
import org.l2jmobius.gameserver.model.actor.Npc;
import org.l2jmobius.gameserver.model.actor.Player;
import org.l2jmobius.gameserver.model.actor.enums.creature.Race;
import org.l2jmobius.gameserver.model.item.ItemTemplate;
import org.l2jmobius.gameserver.model.item.holders.ItemHolder;
import org.l2jmobius.gameserver.model.script.Event;
import org.l2jmobius.gameserver.model.eventmap.EventMap;
import org.l2jmobius.gameserver.network.serverpackets.NpcHtmlMessage;

/**
 * Team vs Team event: the dialogs of the TvT Event Manager (for a Team vs Team, a Deathmatch or a Race Wars registration, see RaceWarsManager), and //event start/stop. The event itself (schedule, brackets, teams, fake players, the fight) is in the TeamVsTeamManager, see config/Custom/TeamVsTeam.ini.
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
			case "rw_register":
			{
				showRaceWars(npc, player, RaceWarsManager.getInstance().register(player));
				return null;
			}
			case "rw_registerParty":
			{
				showRaceWars(npc, player, RaceWarsManager.getInstance().registerParty(player));
				return null;
			}
			case "rw_cancel":
			{
				showRaceWars(npc, player, RaceWarsManager.getInstance().unregister(player));
				return null;
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
		// Race Wars takes its turn in the rotation at this manager too.
		if (RaceWarsManager.getInstance().isRegistrationOpen())
		{
			showRaceWars(npc, player, message);
			return;
		}

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
		// The registration open now is for Team vs Team or a Deathmatch (they take turns, see EventRotationManager).
		final boolean deathmatch = manager.getRegistrationMode() == Mode.DEATHMATCH;
		final NpcHtmlMessage html = new NpcHtmlMessage(npc.getObjectId());
		html.setFile(player, HTML_PATH + (deathmatch ? "deathmatch.html" : "manager.html"));
		html.replace("%message%", manager.isRegistrationOpen() ? message : "Registration is closed.");
		html.replace("%size%", String.valueOf(deathmatch ? TeamVsTeamConfig.DM_SIZE : TeamVsTeamConfig.TEAM_SIZE));
		final EventMap map = manager.getRegistrationMap();
		html.replace("%map%", map != null ? map.name() : "battlefield picked when registration opens");
		html.replace("%fight%", String.valueOf(deathmatch ? TeamVsTeamConfig.DM_FIGHT_TIME : TeamVsTeamConfig.FIGHT_TIME));
		html.replace("%respawn%", String.valueOf(TeamVsTeamConfig.DM_RESPAWN_DELAY));
		html.replace("%reward%", describeReward(deathmatch ? TeamVsTeamConfig.DM_WIN_REWARD : TeamVsTeamConfig.WIN_REWARD));
		html.replace("%top%", describeTopRewards(deathmatch));
		html.replace("%brackets%", TeamVsTeamManager.describeBrackets());
		html.replace("%time%", (seconds / 60) + " min " + (seconds % 60) + " s");
		html.replace("%count%", String.valueOf(manager.getRegisteredCount()));
		html.replace("%bracket_count%", String.valueOf(bracket < 0 ? 0 : manager.getRegisteredCount(bracket)));
		html.replace("%you%", you);
		player.sendPacket(html);
	}

	/**
	 * The Race Wars page: the rules, the bracket and race the player would fight for, and how many of its race registered in its bracket.
	 */
	private void showRaceWars(Npc npc, Player player, String message)
	{
		final RaceWarsManager manager = RaceWarsManager.getInstance();
		final int bracket = RaceWarsManager.getBracket(player.getLevel());
		final Race race = RaceWarsManager.getRaceOf(player);
		final String you;
		if (bracket < 0)
		{
			you = "your level is outside every bracket.";
		}
		else
		{
			final int[] levels = RaceWarsConfig.BRACKETS.get(bracket);
			you = (manager.isRegistered(player) ? "registered" : "not registered") + ", levels " + levels[0] + "-" + levels[1] + ", for the " + SimulationWar.name(race) + " race (the race of the class you play when registration closes).";
		}

		final int seconds = manager.getRegistrationSecondsLeft();
		final NpcHtmlMessage html = new NpcHtmlMessage(npc.getObjectId());
		html.setFile(player, HTML_PATH + "racewars.html");
		html.replace("%message%", manager.isRegistrationOpen() ? message : "Registration is closed.");
		html.replace("%size%", String.valueOf(RaceWarsConfig.ARMY_SIZE));
		html.replace("%monsters%", String.valueOf(RaceWarsConfig.MONSTER_COUNT));
		html.replace("%monster_time%", String.valueOf(RaceWarsConfig.MONSTER_TIME));
		html.replace("%hard_limit%", String.valueOf(RaceWarsConfig.HARD_LIMIT));
		html.replace("%reward%", describeReward(RaceWarsConfig.WIN_REWARD));
		html.replace("%brackets%", RaceWarsManager.describeBrackets());
		html.replace("%time%", (seconds / 60) + " min " + (seconds % 60) + " s");
		html.replace("%count%", String.valueOf(manager.getRegisteredCount()));
		html.replace("%race_count%", String.valueOf(bracket < 0 ? 0 : manager.getRegisteredCount(race, bracket)));
		html.replace("%race%", SimulationWar.name(race));
		html.replace("%you%", you);
		player.sendPacket(html);
	}

	private static String describeReward(List<ItemHolder> rewards)
	{
		final StringBuilder sb = new StringBuilder();
		for (ItemHolder reward : rewards)
		{
			final ItemTemplate item = ItemData.getInstance().getTemplate(reward.getId());
			sb.append(sb.length() > 0 ? ", " : "").append(reward.getCount()).append(' ').append(item != null ? item.getName() : String.valueOf(reward.getId()));
		}
		return sb.length() > 0 ? sb.toString() : "nothing";
	}

	private static String describeTopRewards(boolean deathmatch)
	{
		final String places = TopReward.describe(deathmatch ? TeamVsTeamConfig.DM_TOP_REWARDS : TeamVsTeamConfig.TOP_REWARDS);
		if (places.isEmpty())
		{
			return "";
		}
		return deathmatch ? " The best players (by kills, the fake players left out) also get Crystal Scrolls of their grade: " + places + "." : " The best players of the whole winning team (by kills) also get Crystal Scrolls of their grade: " + places + ".";
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
