/*
 * This file is part of the L2J Mobius project.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>.
 */
package handlers.bypass.communityboard;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import org.l2jmobius.gameserver.cache.HtmCache;
import org.l2jmobius.gameserver.config.custom.SessionStatsConfig;
import org.l2jmobius.gameserver.data.xml.ClassListData;
import org.l2jmobius.gameserver.handler.CommunityBoardHandler;
import org.l2jmobius.gameserver.handler.IParseBoardHandler;
import org.l2jmobius.gameserver.managers.SessionStatsManager;
import org.l2jmobius.gameserver.model.actor.Player;
import org.l2jmobius.gameserver.model.actor.holders.player.ClassInfoHolder;
import org.l2jmobius.gameserver.model.sessionstats.SessionRecord;
import org.l2jmobius.gameserver.model.sessionstats.SessionRecord.ClassLine;
import org.l2jmobius.gameserver.model.sessionstats.SessionStat;
import org.l2jmobius.gameserver.model.sessionstats.SessionStats;
import org.l2jmobius.gameserver.util.FormatUtil;

/**
 * The session log (see {@link SessionStatsManager}): what the player achieved since logging in, next to their previous session, and the history of their last sessions.
 * <ul>
 * <li>{@code _bbssession} - this session and the last one, side by side</li>
 * <li>{@code _bbssession history} - the last sessions</li>
 * <li>{@code _bbssession view <n>} - one session of the history (0 = the newest)</li>
 * </ul>
 */
public class SessionBoard implements IParseBoardHandler
{
	private static final String BYPASS = "_bbssession";
	
	private static final String NAVIGATION_PATH = "data/html/CommunityBoard/Custom/navigation.html";
	private static final String PAGE_PATH = "data/html/CommunityBoard/Custom/session/main.html";
	
	private static final int WIDTH = 530;
	private static final int LABEL_WIDTH = 160;
	
	private static final String COLOR_TITLE = "CDB67F";
	private static final String COLOR_SECTION = "LEVEL";
	private static final String COLOR_VALUE = "FFCC33";
	private static final String COLOR_TEXT = "B0B0B0";
	private static final String COLOR_MUTED = "707070";
	private static final String COLOR_GOOD = "88DD66";
	private static final String COLOR_BAD = "FF6666";
	
	private static final String[] COMMANDS =
	{
		BYPASS
	};
	
	@Override
	public String[] getCommandList()
	{
		return COMMANDS;
	}
	
	@Override
	public boolean onCommand(String command, Player player)
	{
		CommunityBoardHandler.getInstance().addBypass(player, "Session Log", command);
		
		final String[] args = command.substring(BYPASS.length()).trim().split("\\s+");
		final String content;
		if (!SessionStatsConfig.ENABLED)
		{
			content = "<table width=" + WIDTH + "><tr><td align=center><font color=\"" + COLOR_TEXT + "\">The session log is disabled.</font></td></tr></table>";
		}
		else if (args[0].equalsIgnoreCase("history"))
		{
			content = buildHistory(player);
		}
		else if (args[0].equalsIgnoreCase("view") && (args.length > 1))
		{
			content = buildView(player, parseIndex(args[1]));
		}
		else
		{
			content = buildCurrent(player);
		}
		
		String html = HtmCache.getInstance().getHtm(player, PAGE_PATH);
		html = html.replace("%navigation%", HtmCache.getInstance().getHtm(player, NAVIGATION_PATH));
		html = html.replace("%content%", content);
		CommunityBoardHandler.separateAndSend(html, player);
		return true;
	}
	
	// ---------------------------------------------------------------------------------------------
	// Pages
	// ---------------------------------------------------------------------------------------------
	
	/**
	 * The session in progress next to the previous one.
	 */
	private static String buildCurrent(Player player)
	{
		final List<SessionRecord> history = SessionStatsManager.getInstance().getHistory(player);
		final SessionStats stats = player.getSessionStats();
		
		final List<String> titles = new ArrayList<>();
		final List<SessionRecord> records = new ArrayList<>();
		if (stats != null)
		{
			final SessionRecord current = stats.toRecord(player);
			titles.add("This session<br1><font color=\"" + COLOR_MUTED + "\">since " + formatDate(current.getStart()) + "</font>");
			records.add(current);
		}
		if (!history.isEmpty())
		{
			final SessionRecord last = history.get(0);
			titles.add("Last session<br1><font color=\"" + COLOR_MUTED + "\">" + formatDate(last.getStart()) + "</font>");
			records.add(last);
		}
		
		final StringBuilder sb = new StringBuilder();
		appendTabs(sb, false, history.size());
		if (records.isEmpty())
		{
			sb.append("<table width=").append(WIDTH).append("><tr><td align=center><font color=\"").append(COLOR_TEXT).append("\">Nothing recorded yet. Your sessions are counted from your next login.</font></td></tr></table>");
			return sb.toString();
		}
		
		appendComparison(sb, titles, records);
		if (history.isEmpty())
		{
			sb.append("<br><table width=").append(WIDTH).append("><tr><td align=center><font color=\"").append(COLOR_MUTED).append("\">Your session is saved when you log out; next time it shows here next to the new one.</font></td></tr></table>");
		}
		return sb.toString();
	}
	
	/**
	 * The last sessions, one line each.
	 */
	private static String buildHistory(Player player)
	{
		final List<SessionRecord> history = SessionStatsManager.getInstance().getHistory(player);
		final StringBuilder sb = new StringBuilder();
		appendTabs(sb, true, history.size());
		if (history.isEmpty())
		{
			sb.append("<table width=").append(WIDTH).append("><tr><td align=center><font color=\"").append(COLOR_TEXT).append("\">No finished session yet. A session is saved when you log out.</font></td></tr></table>");
			return sb.toString();
		}
		
		sb.append("<table width=").append(WIDTH).append(" cellpadding=2 cellspacing=0>");
		sb.append("<tr>");
		appendHeader(sb, "Date", 115);
		appendHeader(sb, "Played", 65);
		appendHeader(sb, "Levels", 55);
		appendHeader(sb, "XP", 75);
		appendHeader(sb, "Monsters", 65);
		appendHeader(sb, "Adena", 85);
		appendHeader(sb, "", 70);
		sb.append("</tr>");
		for (int i = 0; i < history.size(); i++)
		{
			final SessionRecord record = history.get(i);
			sb.append("<tr").append((i % 2) == 0 ? " bgcolor=\"000000\"" : "").append(">");
			sb.append("<td width=115><font color=\"").append(COLOR_TEXT).append("\">").append(formatDate(record.getStart())).append("</font></td>");
			sb.append("<td width=65 align=center><font color=\"").append(COLOR_TEXT).append("\">").append(SessionStatsManager.formatDuration(record.getDurationMillis())).append("</font></td>");
			sb.append("<td width=55 align=center>").append(signed(record.getLevelsGained(), false)).append("</td>");
			sb.append("<td width=75 align=center>").append(plain(SessionStatsManager.formatShort(record.get(SessionStat.EXP_GAINED)), record.get(SessionStat.EXP_GAINED))).append("</td>");
			sb.append("<td width=65 align=center>").append(plain(FormatUtil.formatAdena(record.get(SessionStat.KILLS)), record.get(SessionStat.KILLS))).append("</td>");
			sb.append("<td width=85 align=center>").append(signed(record.getAdenaChange(), true)).append("</td>");
			sb.append("<td width=70 align=center>").append(button("Details", BYPASS + " view " + i, 60)).append("</td>");
			sb.append("</tr>");
		}
		sb.append("</table>");
		sb.append("<br><table width=").append(WIDTH).append("><tr><td align=center><font color=\"").append(COLOR_MUTED).append("\">The last ").append(SessionStatsConfig.HISTORY_SIZE).append(" sessions are kept. Adena is how much more (or less) you carried when you logged out.</font></td></tr></table>");
		return sb.toString();
	}
	
	/**
	 * One session of the history, compared with the one before it.
	 */
	private static String buildView(Player player, int index)
	{
		final List<SessionRecord> history = SessionStatsManager.getInstance().getHistory(player);
		if ((index < 0) || (index >= history.size()))
		{
			return buildHistory(player);
		}
		
		final List<String> titles = new ArrayList<>();
		final List<SessionRecord> records = new ArrayList<>();
		final SessionRecord record = history.get(index);
		titles.add("Session<br1><font color=\"" + COLOR_MUTED + "\">" + formatDate(record.getStart()) + "</font>");
		records.add(record);
		if ((index + 1) < history.size())
		{
			final SessionRecord before = history.get(index + 1);
			titles.add("The one before<br1><font color=\"" + COLOR_MUTED + "\">" + formatDate(before.getStart()) + "</font>");
			records.add(before);
		}
		
		final StringBuilder sb = new StringBuilder();
		appendTabs(sb, true, history.size());
		appendComparison(sb, titles, records);
		return sb.toString();
	}
	
	// ---------------------------------------------------------------------------------------------
	// Building blocks
	// ---------------------------------------------------------------------------------------------
	
	private static void appendTabs(StringBuilder sb, boolean historyTab, int sessions)
	{
		sb.append("<table width=").append(WIDTH).append("><tr>");
		sb.append("<td align=center>").append(button(historyTab ? "This Session" : "[ This Session ]", BYPASS, 150)).append("</td>");
		sb.append("<td align=center>").append(button((historyTab ? "[ History (" : "History (") + sessions + (historyTab ? ") ]" : ")"), BYPASS + " history", 150)).append("</td>");
		sb.append("</tr></table><br>");
	}
	
	/**
	 * A table with a column for each session: time, progress of each class played, experience, hunting, adena and the rest.
	 */
	private static void appendComparison(StringBuilder sb, List<String> titles, List<SessionRecord> records)
	{
		final int columnWidth = (WIDTH - LABEL_WIDTH) / records.size();
		sb.append("<table width=").append(WIDTH).append(" cellpadding=1 cellspacing=0>");
		sb.append("<tr><td width=").append(LABEL_WIDTH).append("></td>");
		for (String title : titles)
		{
			sb.append("<td width=").append(columnWidth).append(" align=center><font color=\"").append(COLOR_TITLE).append("\">").append(title).append("</font></td>");
		}
		sb.append("</tr>");
		
		// Time.
		appendSection(sb, "Time", records.size());
		appendRow(sb, "Time played", records, true, r -> plain(SessionStatsManager.formatDuration(r.getDurationMillis()), 1));
		
		// Character.
		appendSection(sb, "Character", records.size());
		final Set<Integer> classIds = new LinkedHashSet<>();
		for (SessionRecord record : records)
		{
			for (ClassLine line : record.getClasses())
			{
				classIds.add(line.getClassId());
			}
		}
		for (int classId : classIds)
		{
			final ClassInfoHolder info = ClassListData.getInstance().getClass(classId);
			appendRow(sb, info != null ? info.getClassName() : "Class " + classId, records, true, r -> formatClass(r, classId));
		}
		appendRow(sb, "Levels gained", records, true, r -> signed(r.getLevelsGained(), false));
		appendRow(sb, "Experience gained", records, true, r -> stat(r, SessionStat.EXP_GAINED));
		appendRow(sb, "Experience lost", records, false, r -> lost(r.get(SessionStat.EXP_LOST)));
		appendRow(sb, "SP gained", records, true, r -> stat(r, SessionStat.SP_GAINED));
		appendRow(sb, "Fame", records, false, r -> signed(r.get(SessionStat.FAME), false));
		
		// Hunting.
		appendSection(sb, "Hunting", records.size());
		appendRow(sb, "Monsters killed", records, true, r -> stat(r, SessionStat.KILLS));
		appendRow(sb, "Killed by your party", records, false, r -> stat(r, SessionStat.PARTY_KILLS));
		appendRow(sb, "Champions", records, false, r -> stat(r, SessionStat.CHAMPION_KILLS));
		appendRow(sb, "Raid bosses", records, false, r -> stat(r, SessionStat.RAID_KILLS));
		appendRow(sb, "PvP kills", records, false, r -> stat(r, SessionStat.PVP_KILLS));
		appendRow(sb, "PK kills", records, false, r -> stat(r, SessionStat.PK_KILLS));
		appendRow(sb, "Deaths", records, false, r -> lost(r.get(SessionStat.DEATHS)));
		
		// Adena.
		appendSection(sb, "Adena", records.size());
		appendRow(sb, "Adena earned", records, true, r -> stat(r, SessionStat.ADENA_EARNED));
		appendRow(sb, "Adena spent", records, true, r -> lost(r.get(SessionStat.ADENA_SPENT)));
		appendRow(sb, "Adena carried", records, true, r -> signed(r.getAdenaChange(), true));
		
		// Activity.
		appendSection(sb, "Activity", records.size());
		appendRow(sb, "Quests completed", records, true, r -> stat(r, SessionStat.QUESTS));
		appendRow(sb, "Drops picked up", records, false, r -> stat(r, SessionStat.ITEMS_LOOTED));
		appendRow(sb, "Spoils swept", records, false, r -> stat(r, SessionStat.SPOILS));
		appendRow(sb, "Items crafted", records, false, r -> stat(r, SessionStat.ITEMS_CRAFTED));
		sb.append("</table>");
		
		sb.append("<br><table width=").append(WIDTH).append("><tr><td align=center><font color=\"").append(COLOR_MUTED).append("\">Adena earned counts adena from monsters, quests, shops and your private store, not from trades, mail or the warehouse. Adena carried is how much more (or less) you hold than at login.</font></td></tr></table>");
	}
	
	private interface Cell
	{
		String get(SessionRecord record);
	}
	
	/**
	 * A row of the comparison. A row that isn't always shown is left out when it is empty in every session.
	 */
	private static void appendRow(StringBuilder sb, String label, List<SessionRecord> records, boolean always, Cell cell)
	{
		final List<String> cells = new ArrayList<>();
		boolean empty = true;
		for (SessionRecord record : records)
		{
			final String text = cell.get(record);
			cells.add(text);
			if (!text.contains(COLOR_MUTED))
			{
				empty = false;
			}
		}
		if (empty && !always)
		{
			return;
		}
		
		sb.append("<tr><td width=").append(LABEL_WIDTH).append(" height=18><font color=\"").append(COLOR_TEXT).append("\">").append(label).append("</font></td>");
		for (String text : cells)
		{
			sb.append("<td align=center>").append(text).append("</td>");
		}
		sb.append("</tr>");
	}
	
	private static void appendSection(StringBuilder sb, String title, int columns)
	{
		sb.append("<tr><td colspan=").append(columns + 1).append(" height=22 valign=bottom><font color=\"").append(COLOR_SECTION).append("\">").append(title).append("</font></td></tr>");
		sb.append("<tr><td colspan=").append(columns + 1).append("><img src=\"L2UI.SquareGray\" width=").append(WIDTH).append(" height=1></td></tr>");
	}
	
	private static void appendHeader(StringBuilder sb, String title, int width)
	{
		sb.append("<td width=").append(width).append(" align=center><font color=\"").append(COLOR_TITLE).append("\">").append(title).append("</font></td>");
	}
	
	/**
	 * @return "76 (12.4%) - 77 (3.1%)" for a class played in the session, a dash if it wasn't
	 */
	private static String formatClass(SessionRecord record, int classId)
	{
		for (ClassLine line : record.getClasses())
		{
			if (line.getClassId() == classId)
			{
				final String start = line.getStartLevel() + " (" + formatPercent(line.getStartLevel(), line.getStartExp()) + ")";
				final String end = line.getEndLevel() + " (" + formatPercent(line.getEndLevel(), line.getEndExp()) + ")";
				final String color = line.getEndLevel() > line.getStartLevel() ? COLOR_GOOD : line.getEndLevel() < line.getStartLevel() ? COLOR_BAD : COLOR_VALUE;
				return "<font color=\"" + COLOR_TEXT + "\">" + start + "</font> <font color=\"" + COLOR_MUTED + "\">to</font> <font color=\"" + color + "\">" + end + "</font>";
			}
		}
		return "<font color=\"" + COLOR_MUTED + "\">-</font>";
	}
	
	private static String formatPercent(int level, long exp)
	{
		return String.format(Locale.ENGLISH, "%.1f%%", SessionStatsManager.getLevelPercent(level, exp));
	}
	
	private static String stat(SessionRecord record, SessionStat stat)
	{
		final long value = record.get(stat);
		return plain(FormatUtil.formatAdena(value), value);
	}
	
	private static String plain(String text, long value)
	{
		return "<font color=\"" + (value == 0 ? COLOR_MUTED : COLOR_VALUE) + "\">" + text + "</font>";
	}
	
	private static String lost(long value)
	{
		return "<font color=\"" + (value == 0 ? COLOR_MUTED : COLOR_BAD) + "\">" + FormatUtil.formatAdena(value) + "</font>";
	}
	
	private static String signed(long value, boolean full)
	{
		final String number = full ? FormatUtil.formatAdena(Math.abs(value)) : String.valueOf(Math.abs(value));
		if (value == 0)
		{
			return "<font color=\"" + COLOR_MUTED + "\">0</font>";
		}
		return "<font color=\"" + (value > 0 ? COLOR_GOOD : COLOR_BAD) + "\">" + (value > 0 ? "+" : "-") + number + "</font>";
	}
	
	private static String formatDate(long time)
	{
		return new SimpleDateFormat("MMM d, HH:mm", Locale.ENGLISH).format(new Date(time));
	}
	
	private static int parseIndex(String text)
	{
		try
		{
			return Integer.parseInt(text);
		}
		catch (NumberFormatException e)
		{
			return -1;
		}
	}
	
	private static String button(String text, String action, int width)
	{
		return "<button value=\"" + text + "\" action=\"bypass " + action + "\" width=" + width + " height=22 back=\"L2UI_CT1.Button_DF_Down\" fore=\"L2UI_CT1.Button_DF\">";
	}
}
