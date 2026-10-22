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
package org.l2jmobius.gameserver.managers;

import java.util.List;

import org.l2jmobius.gameserver.config.custom.VillageSiegeConfig;
import org.l2jmobius.gameserver.data.xml.MultisellData;
import org.l2jmobius.gameserver.managers.VillageSiegeManager.Contributor;
import org.l2jmobius.gameserver.managers.VillageSiegeManager.EntranceState;
import org.l2jmobius.gameserver.managers.VillageSiegeManager.RankRow;
import org.l2jmobius.gameserver.managers.VillageSiegeManager.Siege;
import org.l2jmobius.gameserver.managers.VillageSiegeManager.SiegeResult;
import org.l2jmobius.gameserver.managers.VillageSiegeManager.Slot;
import org.l2jmobius.gameserver.model.Location;
import org.l2jmobius.gameserver.model.actor.Npc;
import org.l2jmobius.gameserver.model.actor.Player;
import org.l2jmobius.gameserver.model.villagesiege.SiegeVillage;

/**
 * The pages of the Defence Force Board (and of {@code .siege}): the siege going on, the day's schedule, the rankings, the exchange of Defence Medals and how it all works.
 */
public final class VillageSiegeBoard
{
	/** Bypass of the board npc. */
	public static final String NPC_PREFIX = "bypass -h Script VillageSiege ";
	/** Bypass of the .siege window. */
	public static final String WINDOW_PREFIX = "bypass voiced_siege ";

	/** The exchange lists, grade by grade (see {@link VillageSiegeManager#FIRST_MULTISELL_ID}). */
	private static final String[] GRADES =
	{
		"No-grade",
		"D-grade",
		"C-grade"
	};
	private static final String[] KINDS =
	{
		"Weapons",
		"Armor",
		"Jewelry"
	};

	private VillageSiegeBoard()
	{
	}

	/**
	 * @param player the player
	 * @param npc the board, {@code null} for the .siege window
	 * @param command the page wanted ("main", "invite", "join", "return", "live", "schedule", "rank", "today", "exchange", "ms n", "radar n", "help")
	 * @return the page, {@code null} if the command opened something else
	 */
	public static String handle(Player player, Npc npc, String command)
	{
		final VillageSiegeManager manager = VillageSiegeManager.getInstance();
		final String prefix = npc != null ? NPC_PREFIX : WINDOW_PREFIX;
		SiegeVillage village = manager.getBoardVillage(npc);
		if (village == null)
		{
			village = manager.getNearestVillage(player);
		}

		final String[] args = (command == null) || command.trim().isEmpty() ? new String[]
		{
			"main"
		} : command.trim().split("\\s+");
		switch (args[0].toLowerCase())
		{
			case "invite":
			{
				return wrap(invite(player));
			}
			case "join":
			{
				final String refusal = manager.join(player);
				if (refusal == null)
				{
					return null;
				}
				player.sendMessage(refusal);
				return wrap(main(player, village, prefix, npc != null));
			}
			case "return":
			{
				final String refusal = manager.returnHome(player);
				if (refusal == null)
				{
					return null;
				}
				player.sendMessage(refusal);
				return wrap(main(player, village, prefix, npc != null));
			}
			case "live":
			{
				return wrap(live(player, prefix));
			}
			case "schedule":
			{
				return wrap(schedule(prefix));
			}
			case "rank":
			{
				return wrap(rank(player, village, prefix));
			}
			case "today":
			{
				return wrap(today(player, prefix));
			}
			case "help":
			{
				return wrap(help(village, prefix));
			}
			case "exchange":
			{
				return npc != null ? wrap(exchange(player, prefix)) : wrap(main(player, village, prefix, false));
			}
			case "ms":
			{
				if ((npc != null) && (args.length > 1))
				{
					try
					{
						final int index = Integer.parseInt(args[1]);
						if ((index >= 0) && (index < VillageSiegeManager.MULTISELL_COUNT))
						{
							MultisellData.getInstance().separateAndSend(VillageSiegeManager.FIRST_MULTISELL_ID + index, player, npc, false);
							return null;
						}
					}
					catch (NumberFormatException e)
					{
						// The main page.
					}
				}
				return wrap(main(player, village, prefix, npc != null));
			}
			case "radar":
			{
				final Siege siege = manager.getSiege();
				if ((siege != null) && (args.length > 1))
				{
					try
					{
						final int index = Integer.parseInt(args[1]);
						if ((index >= 0) && (index < siege.getEntrances().size()))
						{
							final Location gate = siege.getEntrances().get(index).getGate();
							player.getRadar().addMarker(gate.getX(), gate.getY(), gate.getZ());
						}
					}
					catch (NumberFormatException e)
					{
						// The live page.
					}
				}
				return wrap(live(player, prefix));
			}
			default:
			{
				return wrap(main(player, village, prefix, npc != null));
			}
		}
	}

	private static String wrap(String body)
	{
		return "<html><title>Defence Force Board</title><body>" + body + "</body></html>";
	}

	private static String button(String text, String action, int width)
	{
		return "<button value=\"" + text + "\" action=\"" + action + "\" width=" + width + " height=22 back=\"L2UI_CT1.Button_DF_Down\" fore=\"L2UI_CT1.Button_DF\">";
	}

	private static String back(String prefix)
	{
		return "<br><center>" + button("Back", prefix + "main", 80) + "</center>";
	}

	private static String minutesLeft(long until)
	{
		final long minutes = Math.max(0, (until - System.currentTimeMillis()) / 60000);
		return minutes >= 60 ? (minutes / 60) + "h " + (minutes % 60) + "m" : minutes + "m";
	}

	private static String main(Player player, SiegeVillage village, String prefix, boolean atBoard)
	{
		final VillageSiegeManager manager = VillageSiegeManager.getInstance();
		final StringBuilder sb = new StringBuilder();
		sb.append("<center><font color=\"LEVEL\">").append(village != null ? village.getName() : "Village").append(" Defence Force</font></center><br>");

		final Siege siege = manager.getSiege();
		if (siege != null)
		{
			sb.append("<font color=\"FF6666\">").append(siege.getVillage().getName()).append(" is under siege!</font> <font color=\"FFCC66\">").append(siege.getStageName()).append("</font><br1>");
			sb.append("Monsters attack until ").append(VillageSiegeManager.formatTime(siege.getEnd())).append(" (").append(minutesLeft(siege.getEnd())).append(" left). ").append(siege.getKills()).append(" slain, ").append(siege.getPlayersPresent()).append(" defenders there.<br1>");
			for (EntranceState entrance : siege.getEntrances())
			{
				sb.append(entrance.getName()).append(": ").append(gateState(entrance)).append("<br1>");
			}
			final Contributor contributor = siege.getContributor(player);
			if ((contributor != null) && (contributor.getPoints() > 0))
			{
				sb.append("<br1>Your contribution: <font color=\"LEVEL\">").append(contributor.getPoints()).append("</font> points, ").append(contributor.getMedals()).append(" medals, ").append(contributor.getActiveMinutes()).append(" minutes of fighting.<br1>");
			}
			sb.append("<br>");
			appendJoin(sb, player, prefix);
		}
		else
		{
			sb.append("All is quiet at the gates.<br1>");
			final Slot next = VillageSiegeManager.getNextSlot(null);
			if (next != null)
			{
				sb.append("Next siege: <font color=\"LEVEL\">").append(next.getVillage().getName()).append("</font> at ").append(VillageSiegeManager.formatTime(next.getStart())).append(" (in ").append(minutesLeft(next.getStart())).append(").<br>");
			}
			if (manager.canReturn(player))
			{
				sb.append("<center>").append(button("Go Back", prefix + "return", 125)).append("</center><br>");
			}
		}

		if ((village != null) && ((siege == null) || (siege.getVillage() != village)))
		{
			final Slot slot = VillageSiegeManager.getNextSlot(village);
			if (slot != null)
			{
				sb.append("This village's next siege: ").append(VillageSiegeManager.formatTime(slot.getStart())).append("-").append(VillageSiegeManager.formatTime(slot.getEnd())).append(" (in ").append(minutesLeft(slot.getStart())).append(").<br>");
			}
		}

		final long medals = player.getInventory().getInventoryItemCount(VillageSiegeConfig.MEDAL_ITEM_ID, -1);
		sb.append("You carry <font color=\"LEVEL\">").append(medals).append("</font> Defence Medals.<br><center>");
		sb.append("<table width=270><tr><td align=center>").append(button("Live Siege", prefix + "live", 125)).append("</td><td align=center>").append(button("Schedule", prefix + "schedule", 125)).append("</td></tr>");
		sb.append("<tr><td align=center>").append(button("Village Ranking", prefix + "rank", 125)).append("</td><td align=center>").append(button("Today's Ranking", prefix + "today", 125)).append("</td></tr>");
		sb.append("<tr><td align=center>").append(atBoard ? button("Exchange Medals", prefix + "exchange", 125) : "").append("</td><td align=center>").append(button("How It Works", prefix + "help", 125)).append("</td></tr></table></center>");
		return sb.toString();
	}

	private static String gateColor(int percent)
	{
		final String color = percent >= 60 ? "00FF00" : percent >= 30 ? "FFFF00" : "FF6600";
		return "<font color=\"" + color + "\">" + percent + "%</font>";
	}

	/**
	 * @return how a gate stands: its HP, or fallen and when it is raised again
	 */
	private static String gateState(EntranceState entrance)
	{
		if (entrance.isFallen())
		{
			final long rebuild = entrance.getRebuildTime();
			return "<font color=\"FF0000\">fallen</font>" + (rebuild > System.currentTimeMillis() ? " (raised again in " + minutesLeft(rebuild + 59999) + ")" : rebuild > 0 ? " (raised when the monsters are pushed back)" : "");
		}
		return gateColor(entrance.getMeterPercent()) + " <font color=\"B0B0B0\">(" + entrance.getGateHp() + " / " + entrance.getGateMaxHp() + " HP)</font>";
	}

	/**
	 * The buttons to join the siege, or to go back where the player came from.
	 */
	private static void appendJoin(StringBuilder sb, Player player, String prefix)
	{
		final VillageSiegeManager manager = VillageSiegeManager.getInstance();
		final Siege siege = manager.getSiege();
		sb.append("<center>");
		if ((siege != null) && VillageSiegeConfig.TELEPORT && (player.getLevel() <= VillageSiegeConfig.MAX_PLAYER_LEVEL) && !manager.isAtSiege(player))
		{
			sb.append(button("Join the Defence", prefix + "join", 150)).append("<br1><font color=\"B0B0B0\">Free teleport to the gate that needs you most.</font><br>");
		}
		if (manager.canReturn(player))
		{
			sb.append(button("Go Back", prefix + "return", 125)).append("<br>");
		}
		sb.append("</center>");
	}

	/**
	 * The window the players up to {@link VillageSiegeConfig#MAX_PLAYER_LEVEL} get when a siege begins.
	 */
	private static String invite(Player player)
	{
		final Siege siege = VillageSiegeManager.getInstance().getSiege();
		final StringBuilder sb = new StringBuilder();
		if (siege == null)
		{
			return sb.append("No village is under siege now.").toString();
		}

		sb.append("<center><font color=\"FF6666\">").append(siege.getVillage().getName()).append(" is under siege!</font></center><br>");
		sb.append("The monsters of the hunting grounds march on its gates until ").append(VillageSiegeManager.formatTime(siege.getEnd())).append(". The Defence Force calls on every adventurer up to level ").append(VillageSiegeConfig.MAX_PLAYER_LEVEL).append(" - the monsters come at your own level.<br><br>");
		sb.append("<font color=\"LEVEL\">The defenders get</font><br1>");
		sb.append("- Defence Medals as they fight, for gear up to C grade<br1>");
		if (VillageSiegeConfig.EXP_BONUS > 0)
		{
			sb.append("- ").append(VillageSiegeConfig.EXP_BONUS).append("% more experience from the siege's monsters<br1>");
		}
		if (VillageSiegeConfig.REWARD_EXP_PERCENT > 0)
		{
			sb.append("- up to ").append(VillageSiegeConfig.REWARD_EXP_PERCENT).append("% of a level when the siege ends<br1>");
		}
		sb.append("- a place on the ranking of the best defenders<br><br>");
		appendJoin(sb, player, WINDOW_PREFIX);
		return sb.toString();
	}

	private static String live(Player player, String prefix)
	{
		final Siege siege = VillageSiegeManager.getInstance().getSiege();
		final StringBuilder sb = new StringBuilder();
		if (siege == null)
		{
			sb.append("<center><font color=\"LEVEL\">Live Siege</font></center><br>No village is under siege now.");
			return sb.append(back(prefix)).toString();
		}

		sb.append("<center><font color=\"LEVEL\">").append(siege.getVillage().getName()).append("</font> - <font color=\"FFCC66\">").append(siege.getStageName()).append("</font><br1>until ").append(VillageSiegeManager.formatTime(siege.getEnd())).append(" (").append(minutesLeft(siege.getEnd())).append(" left)</center><br>");
		sb.append("<table width=270>");
		final List<EntranceState> entrances = siege.getEntrances();
		for (int i = 0; i < entrances.size(); i++)
		{
			final EntranceState entrance = entrances.get(i);
			sb.append("<tr><td width=200>").append(entrance.getName()).append(": ").append(gateState(entrance)).append("</td><td width=70>").append(button("Radar", prefix + "radar " + i, 60)).append("</td></tr>");
		}
		sb.append("</table><br1>");
		final int[] levels = VillageSiegeManager.getInstance().getLevelRange(siege.getVillage());
		sb.append("Monster levels: ").append(levels[0]).append("-").append(levels[1]);
		if (VillageSiegeConfig.SCALE_TO_DEFENDERS)
		{
			sb.append(", and the defenders' own");
		}
		sb.append(". Slain: ").append(siege.getKills()).append(". Attacking now: ").append(siege.getMonstersAlive()).append(". Great waves: ").append(siege.getWaves()).append(".<br1>");
		sb.append("Defenders: ").append(siege.getPlayersPresent()).append(" players and ").append(siege.getFakeDefenders()).append(" adventurers.<br1>");
		if (siege.hasGateFallen())
		{
			sb.append("<font color=\"FF6666\">A gate fell: the end bonus is halved and the reward cut.</font><br1>");
		}
		appendJoin(sb, player, prefix);
		sb.append("<br><font color=\"LEVEL\">Best defenders</font><br1>");
		appendLiveRanking(sb, player, siege, 10);
		return sb.append(back(prefix)).toString();
	}

	private static void appendLiveRanking(StringBuilder sb, Player player, Siege siege, int size)
	{
		final List<Contributor> ranking = siege.getRanking();
		if (ranking.isEmpty())
		{
			sb.append("Nobody has fought yet.<br1>");
			return;
		}

		sb.append("<table width=270><tr><td width=30>#</td><td width=130>Defender</td><td width=40>Lv</td><td width=70>Points</td></tr>");
		int mine = -1;
		for (int i = 0; i < ranking.size(); i++)
		{
			final Contributor contributor = ranking.get(i);
			final boolean me = !contributor.isFake() && (contributor.getObjectId() == player.getObjectId());
			if (me)
			{
				mine = i;
			}
			if (i < size)
			{
				appendRow(sb, i + 1, contributor.getName(), contributor.getLevel(), contributor.getPoints(), me);
			}
		}
		if (mine >= size)
		{
			final Contributor contributor = ranking.get(mine);
			appendRow(sb, mine + 1, contributor.getName(), contributor.getLevel(), contributor.getPoints(), true);
		}
		sb.append("</table>");
	}

	private static void appendRow(StringBuilder sb, int rank, String name, int level, int points, boolean me)
	{
		final String color = me ? "LEVEL" : rank <= 3 ? "FFCC66" : "FFFFFF";
		sb.append("<tr><td><font color=\"").append(color).append("\">").append(rank).append("</font></td><td><font color=\"").append(color).append("\">").append(name).append("</font></td><td>").append(level).append("</td><td>").append(points).append("</td></tr>");
	}

	private static String schedule(String prefix)
	{
		final VillageSiegeManager manager = VillageSiegeManager.getInstance();
		final long now = System.currentTimeMillis();
		final Siege siege = manager.getSiege();
		final StringBuilder sb = new StringBuilder();
		sb.append("<center><font color=\"LEVEL\">Coming Sieges</font></center><br1>One village at a time, ").append(VillageSiegeConfig.DURATION).append(" minutes each with ").append(VillageSiegeConfig.BREAK).append(" minutes of rest between two (server time, the next 24 hours).<br>");
		sb.append("<table width=270><tr><td width=80>Time</td><td width=130>Village</td><td width=60></td></tr>");
		for (Slot slot : VillageSiegeManager.getSlots(now))
		{
			final String state;
			if ((siege != null) && (siege.getVillage() == slot.getVillage()) && (now >= slot.getStart()) && (now < slot.getEnd()))
			{
				state = "<font color=\"FF6666\">now</font>";
			}
			else if (now >= slot.getEnd())
			{
				state = "<font color=\"808080\">over</font>";
			}
			else
			{
				state = "in " + minutesLeft(slot.getStart());
			}
			sb.append("<tr><td>").append(VillageSiegeManager.formatTime(slot.getStart())).append("-").append(VillageSiegeManager.formatTime(slot.getEnd())).append("</td><td>").append(slot.getVillage().getName()).append("</td><td>").append(state).append("</td></tr>");
		}
		sb.append("</table>");
		return sb.append(back(prefix)).toString();
	}

	private static String rank(Player player, SiegeVillage village, String prefix)
	{
		final StringBuilder sb = new StringBuilder();
		if (village == null)
		{
			return sb.append("No village.").append(back(prefix)).toString();
		}

		final VillageSiegeManager manager = VillageSiegeManager.getInstance();
		final Siege siege = manager.getSiege();
		sb.append("<center><font color=\"LEVEL\">").append(village.getName()).append("</font></center><br>");
		if ((siege != null) && (siege.getVillage() == village))
		{
			sb.append("The siege going on:<br1>");
			appendLiveRanking(sb, player, siege, VillageSiegeConfig.RANKING_SIZE);
			return sb.append(back(prefix)).toString();
		}

		final SiegeResult result = manager.getResult(village.getId());
		if (result == null)
		{
			sb.append("This village has not been besieged yet.");
			return sb.append(back(prefix)).toString();
		}

		final int day = result.getDay();
		sb.append("Its last siege (").append(day / 10000).append("-").append(String.format("%02d", (day / 100) % 100)).append("-").append(String.format("%02d", day % 100)).append("): ").append(result.hasGateFallen() ? "<font color=\"FF6666\">a gate fell</font>" : "<font color=\"00FF00\">every gate held</font>").append(".<br>");
		appendRows(sb, player, result.getRows());
		return sb.append(back(prefix)).toString();
	}

	private static String today(Player player, String prefix)
	{
		final StringBuilder sb = new StringBuilder();
		sb.append("<center><font color=\"LEVEL\">Today's Defenders</font></center><br1>Every siege of today added up.<br>");
		final List<RankRow> rows = VillageSiegeManager.getInstance().getTodayRanking();
		if (rows.isEmpty())
		{
			sb.append("Nobody has defended a village today.");
		}
		else
		{
			appendRows(sb, player, rows);
		}
		return sb.append(back(prefix)).toString();
	}

	private static void appendRows(StringBuilder sb, Player player, List<RankRow> rows)
	{
		sb.append("<table width=270><tr><td width=30>#</td><td width=130>Defender</td><td width=40>Lv</td><td width=70>Points</td></tr>");
		int mine = -1;
		for (int i = 0; i < rows.size(); i++)
		{
			final RankRow row = rows.get(i);
			final boolean me = !row.isFake() && row.getName().equals(player.getName());
			if (me)
			{
				mine = i;
			}
			if (i < VillageSiegeConfig.RANKING_SIZE)
			{
				appendRow(sb, i + 1, row.getName(), row.getLevel(), row.getPoints(), me);
			}
		}
		if (mine >= VillageSiegeConfig.RANKING_SIZE)
		{
			final RankRow row = rows.get(mine);
			appendRow(sb, mine + 1, row.getName(), row.getLevel(), row.getPoints(), true);
		}
		sb.append("</table>");
	}

	private static String exchange(Player player, String prefix)
	{
		final StringBuilder sb = new StringBuilder();
		final long medals = player.getInventory().getInventoryItemCount(VillageSiegeConfig.MEDAL_ITEM_ID, -1);
		sb.append("<center><font color=\"LEVEL\">Exchange Defence Medals</font></center><br>");
		sb.append("The Defence Force pays its defenders in gear. An item costs a medal for every ").append(VillageSiegeConfig.ADENA_PER_MEDAL).append(" adena of its shop price.<br1>You carry <font color=\"LEVEL\">").append(medals).append("</font> medals.<br><center><table width=270>");
		for (int grade = 0; grade < GRADES.length; grade++)
		{
			sb.append("<tr><td width=60>").append(GRADES[grade]).append("</td>");
			for (int kind = 0; kind < KINDS.length; kind++)
			{
				sb.append("<td align=center>").append(button(KINDS[kind], prefix + "ms " + ((grade * KINDS.length) + kind), 68)).append("</td>");
			}
			sb.append("</tr>");
		}
		sb.append("</table></center>");
		return sb.append(back(prefix)).toString();
	}

	private static String help(SiegeVillage village, String prefix)
	{
		final StringBuilder sb = new StringBuilder();
		sb.append("<center><font color=\"LEVEL\">Village Under Siege</font></center><br>");
		sb.append("The monsters of the hunting grounds attack the starting villages, one village after the other, for ").append(VillageSiegeConfig.DURATION).append(" minutes each, with ").append(VillageSiegeConfig.BREAK).append(" minutes of rest between two.<br1>");
		sb.append("They march on the gates of the village's entrances, more and more of them as the siege goes from the Vanguard to the Main Assault and the Final Assault, with great waves now and then. The defenders and the village guards hold the line at each gate; the monsters that get past them beat on the gate. Target a gate to see its HP. At 0 HP it falls and the monsters run into the streets, until the defenders raise it again. The siege goes on until its time is up.<br>");
		if (VillageSiegeConfig.SCALE_TO_DEFENDERS)
		{
			sb.append("Too strong for the village's monsters? Monsters of your own level come for you.<br>");
		}
		if (VillageSiegeConfig.TELEPORT)
		{
			sb.append("Defenders up to level ").append(VillageSiegeConfig.MAX_PLAYER_LEVEL).append(" can join from anywhere with .siege, for free, and go back where they were afterwards.<br>");
		}
		sb.append("<font color=\"LEVEL\">Contribution</font><br1>");
		sb.append("Defenders up to level ").append(VillageSiegeConfig.MAX_PLAYER_LEVEL).append(" earn points: a kill's points are shared by the damage each defender did, healing other defenders counts, and so does every minute of fighting. Monsters ").append(VillageSiegeConfig.GREY_LEVEL_DIFFERENCE).append(" or more levels below you give nothing.<br1>");
		sb.append("Warband Leaders and the Siege Lord are worth much more. Those still fighting at the end get +").append(VillageSiegeConfig.END_BONUS_PERCENT).append("% - halved if a gate fell.<br>");
		if ((VillageSiegeConfig.EXP_BONUS > 0) || (VillageSiegeConfig.REWARD_EXP_PERCENT > 0))
		{
			sb.append("<font color=\"LEVEL\">Experience</font><br1>");
			if (VillageSiegeConfig.EXP_BONUS > 0)
			{
				sb.append("The siege's monsters give ").append(VillageSiegeConfig.EXP_BONUS).append("% more experience. ");
			}
			if (VillageSiegeConfig.REWARD_EXP_PERCENT > 0)
			{
				sb.append("At the end, the defenders get up to ").append(VillageSiegeConfig.REWARD_EXP_PERCENT).append("% of a level for ").append(VillageSiegeConfig.REWARD_FULL_MINUTES).append(" minutes of fighting (less for less, and less for each gate that fell).");
			}
			sb.append("<br>");
		}
		sb.append("<font color=\"LEVEL\">Defence Medals</font><br1>");
		sb.append("Points are paid in Defence Medals as you earn them");
		if (VillageSiegeConfig.ONE_PER_COMPUTER)
		{
			sb.append(" (one character per computer)");
		}
		sb.append(". Exchange them for gear up to C grade at any Defence Force Board.");
		if (VillageSiegeConfig.MEDAL_RATE > 0)
		{
			// The defenders above the village's levels fight monsters of their own level.
			final int top = VillageSiegeConfig.MAX_PLAYER_LEVEL;
			final int level = village != null ? VillageSiegeManager.getInstance().getLevel(village) : top;
			sb.append(" The stronger the monster, the more a point pays: an hour of fighting pays about ");
			if (level < top)
			{
				sb.append(VillageSiegeManager.getMedalsPerHour(level)).append(" medals against monsters of level ").append(level).append(", ");
			}
			sb.append(VillageSiegeManager.getMedalsPerHour(top)).append(" medals against monsters of level ").append(top).append(".");
		}
		sb.append("<br>Adventurers of every kind come to help, and the village guards stand at the gates.");
		return sb.append(back(prefix)).toString();
	}
}
