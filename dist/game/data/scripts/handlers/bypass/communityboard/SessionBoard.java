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
	
	/** The content column of the page, right of the menu: 6 + 200 + 12 + 510 = 728, which leaves room for the board's scroll bar. Nothing in it is wider. */
	private static final int WIDTH = 510;
	private static final int LABEL_WIDTH = 180;
	/** The padding right of the numbers of the comparison. */
	private static final int PAD = 10;
	private static final int TILE_WIDTH = (WIDTH - 18) / 4;
	/** The history columns: date, time played, levels, experience, monsters, adena and the details button. They add up to {@link #WIDTH}. */
	private static final int[] HISTORY_COLUMNS =
	{
		116,
		56,
		46,
		70,
		70,
		108,
		44
	};
	
	private static final String COLOR_TITLE = "CDB67F";
	private static final String COLOR_SECTION = "LEVEL";
	private static final String COLOR_NAME = "EDE3C8";
	private static final String COLOR_VALUE = "FFCC33";
	private static final String COLOR_TEXT = "B0B0B0";
	private static final String COLOR_DIM = "8C8576";
	private static final String COLOR_MUTED = "707070";
	private static final String COLOR_GOOD = "88DD66";
	private static final String COLOR_BAD = "FF6666";
	
	// Only colored tables: the client doesn't color a table row or cell.
	private static final String BG_PANEL = "15130F";
	private static final String BG_HEAD = "1A1712";
	private static final String BG_ROW = "14120E";
	
	// Client icons.
	private static final String ICON_PAGE = "icon.etc_exp_point_i00";
	private static final String ICON_TIME = "icon.etc_torch_on_i00";
	private static final String ICON_LEVEL = "icon.etc_badge_gold_i00";
	private static final String ICON_EXP = "icon.etc_exp_point_i00";
	private static final String ICON_ADENA = "icon.etc_adena_i00";
	private static final String ICON_HUNT = "icon.weapon_long_sword_i00";
	private static final String ICON_ACTIVITY = "icon.etc_scroll_gray_i00";
	
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
			content = message("The session log is disabled.");
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
			titles.add("This session<br1><font color=\"" + COLOR_DIM + "\">" + formatDate(current.getStart()) + "</font>");
			records.add(current);
		}
		if (!history.isEmpty())
		{
			final SessionRecord last = history.get(0);
			titles.add("Last session<br1><font color=\"" + COLOR_DIM + "\">" + formatDate(last.getStart()) + "</font>");
			records.add(last);
		}
		
		final StringBuilder sb = new StringBuilder();
		appendHeader(sb, stats != null ? "Since " + formatDate(records.get(0).getStart()) : "Your last session", false, history.size());
		if (records.isEmpty())
		{
			sb.append(message("Nothing recorded yet. Your sessions are counted from your next login."));
			return sb.toString();
		}
		
		appendTiles(sb, records.get(0), records.size() > 1 ? records.get(1) : null);
		appendClasses(sb, records.get(0));
		appendComparison(sb, titles, records);
		if (history.isEmpty())
		{
			sb.append(message("Your session is saved when you log out; next time it shows here next to the new one."));
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
		appendHeader(sb, history.isEmpty() ? "No finished session yet" : "Your last " + history.size() + (history.size() == 1 ? " session" : " sessions"), true, history.size());
		if (history.isEmpty())
		{
			sb.append(message("No finished session yet. A session is saved when you log out."));
			return sb.toString();
		}
		
		// Totals.
		long played = 0;
		long levels = 0;
		long kills = 0;
		long adena = 0;
		for (SessionRecord record : history)
		{
			played += record.getDurationMillis();
			levels += record.getLevelsGained();
			kills += record.get(SessionStat.KILLS);
			adena += record.getAdenaChange();
		}
		sb.append("<table width=").append(WIDTH).append(" cellpadding=0 cellspacing=0><tr>");
		sb.append("<td>").append(tile(ICON_TIME, "Played", plainText(SessionStatsManager.formatDuration(played)), null)).append("</td><td width=6></td>");
		sb.append("<td>").append(tile(ICON_LEVEL, "Levels", signed(levels, false), null)).append("</td><td width=6></td>");
		sb.append("<td>").append(tile(ICON_HUNT, "Monsters", plain(FormatUtil.formatAdena(kills), kills), null)).append("</td><td width=6></td>");
		sb.append("<td>").append(tile(ICON_ADENA, "Adena", signedShort(adena), null)).append("</td>");
		sb.append("</tr></table>").append(spacer(12));
		
		// Each line is a table of its own: the client doesn't color a table row, only a table.
		sb.append("<table width=").append(WIDTH).append(" height=24 cellpadding=0 cellspacing=0 bgcolor=").append(BG_HEAD).append("><tr>");
		sb.append("<td width=").append(HISTORY_COLUMNS[0]).append(">&nbsp;&nbsp;<font color=\"").append(COLOR_TITLE).append("\">Started</font></td>");
		appendColumnTitle(sb, "Played", HISTORY_COLUMNS[1]);
		appendColumnTitle(sb, "Levels", HISTORY_COLUMNS[2]);
		appendColumnTitle(sb, "EXP", HISTORY_COLUMNS[3]);
		appendColumnTitle(sb, "Monsters", HISTORY_COLUMNS[4]);
		appendColumnTitle(sb, "Adena", HISTORY_COLUMNS[5]);
		sb.append("<td width=").append(HISTORY_COLUMNS[6]).append("></td>");
		sb.append("</tr></table>");
		for (int i = 0; i < history.size(); i++)
		{
			final SessionRecord record = history.get(i);
			sb.append("<table width=").append(WIDTH).append(" height=32 cellpadding=0 cellspacing=0").append((i % 2) == 0 ? " bgcolor=" + BG_ROW : "").append("><tr>");
			sb.append("<td width=").append(HISTORY_COLUMNS[0]).append(">&nbsp;&nbsp;<font color=\"").append(COLOR_NAME).append("\">").append(formatDay(record.getStart())).append("</font> <font color=\"").append(COLOR_DIM).append("\">").append(formatTime(record.getStart())).append("</font></td>");
			sb.append("<td width=").append(HISTORY_COLUMNS[1]).append(" align=right><font color=\"").append(COLOR_TEXT).append("\">").append(SessionStatsManager.formatDuration(record.getDurationMillis())).append("</font></td>");
			sb.append("<td width=").append(HISTORY_COLUMNS[2]).append(" align=right>").append(signed(record.getLevelsGained(), false)).append("</td>");
			sb.append("<td width=").append(HISTORY_COLUMNS[3]).append(" align=right>").append(plain(SessionStatsManager.formatShort(record.get(SessionStat.EXP_GAINED)), record.get(SessionStat.EXP_GAINED))).append("</td>");
			sb.append("<td width=").append(HISTORY_COLUMNS[4]).append(" align=right>").append(plain(FormatUtil.formatAdena(record.get(SessionStat.KILLS)), record.get(SessionStat.KILLS))).append("</td>");
			sb.append("<td width=").append(HISTORY_COLUMNS[5]).append(" align=right>").append(signed(record.getAdenaChange(), true)).append("</td>");
			sb.append("<td width=").append(HISTORY_COLUMNS[6]).append(" align=right>").append(button("View", BYPASS + " view " + i, 38)).append("</td>");
			sb.append("</tr></table>");
		}
		sb.append(message("The last " + SessionStatsConfig.HISTORY_SIZE + " sessions are kept. Adena is how much more (or less) you carried when you logged out."));
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
		titles.add("This one<br1><font color=\"" + COLOR_DIM + "\">" + formatDate(record.getStart()) + "</font>");
		records.add(record);
		final SessionRecord before = (index + 1) < history.size() ? history.get(index + 1) : null;
		if (before != null)
		{
			titles.add("The one before<br1><font color=\"" + COLOR_DIM + "\">" + formatDate(before.getStart()) + "</font>");
			records.add(before);
		}
		
		final StringBuilder sb = new StringBuilder();
		appendHeader(sb, "Session of " + formatDate(record.getStart()), true, history.size());
		appendTiles(sb, record, before);
		appendClasses(sb, record);
		appendComparison(sb, titles, records);
		sb.append(spacer(6)).append(button("Back to history", BYPASS + " history", 120));
		return sb.toString();
	}
	
	// ---------------------------------------------------------------------------------------------
	// Building blocks
	// ---------------------------------------------------------------------------------------------
	
	/**
	 * The title box with the two tabs. The open tab's button is drawn pressed.
	 */
	private static void appendHeader(StringBuilder sb, String subtitle, boolean historyTab, int sessions)
	{
		sb.append("<table width=").append(WIDTH).append(" cellpadding=0 cellspacing=0 bgcolor=").append(BG_PANEL).append(">");
		sb.append("<tr><td height=10 colspan=5></td></tr><tr><td width=10></td>");
		sb.append("<td width=42 valign=top><img src=\"").append(ICON_PAGE).append("\" width=32 height=32></td>");
		sb.append("<td width=").append(WIDTH - 62 - 190).append(" valign=top><font name=\"hs12\" color=\"").append(COLOR_TITLE).append("\">Session Log</font><br1><font color=\"").append(COLOR_DIM).append("\">").append(subtitle).append("</font></td>");
		sb.append("<td width=190 align=right valign=top><table cellpadding=0 cellspacing=0><tr><td height=5></td></tr><tr>");
		sb.append("<td>").append(tab("This session", BYPASS, 94, !historyTab)).append("</td><td width=4></td>");
		sb.append("<td>").append(tab("History (" + sessions + ")", BYPASS + " history", 90, historyTab)).append("</td>");
		sb.append("</tr></table></td><td width=10></td></tr><tr><td height=10 colspan=5></td></tr></table>");
		sb.append(spacer(10));
	}
	
	/**
	 * Four tiles: time played, levels, experience and adena, each with the other session's value under it.
	 */
	private static void appendTiles(StringBuilder sb, SessionRecord record, SessionRecord other)
	{
		sb.append("<table width=").append(WIDTH).append(" cellpadding=0 cellspacing=0><tr>");
		sb.append("<td>").append(tile(ICON_TIME, "Played", plainText(SessionStatsManager.formatDuration(record.getDurationMillis())), other != null ? SessionStatsManager.formatDuration(other.getDurationMillis()) : null)).append("</td><td width=6></td>");
		sb.append("<td>").append(tile(ICON_LEVEL, "Levels", signed(record.getLevelsGained(), false), other != null ? signedText(other.getLevelsGained(), false) : null)).append("</td><td width=6></td>");
		sb.append("<td>").append(tile(ICON_EXP, "Experience", plain(SessionStatsManager.formatShort(record.get(SessionStat.EXP_GAINED)), record.get(SessionStat.EXP_GAINED)), other != null ? SessionStatsManager.formatShort(other.get(SessionStat.EXP_GAINED)) : null)).append("</td><td width=6></td>");
		sb.append("<td>").append(tile(ICON_ADENA, "Adena", signedShort(record.getAdenaChange()), other != null ? signedShortText(other.getAdenaChange()) : null)).append("</td>");
		sb.append("</tr></table>").append(spacer(8));
	}
	
	private static String tile(String icon, String label, String value, String other)
	{
		final StringBuilder sb = new StringBuilder();
		sb.append("<table width=").append(TILE_WIDTH).append(" cellpadding=0 cellspacing=0 bgcolor=").append(BG_ROW).append(">");
		sb.append("<tr><td height=7 colspan=3></td></tr><tr><td width=8></td><td width=").append(TILE_WIDTH - 16).append(">");
		sb.append("<table cellpadding=0 cellspacing=0><tr><td width=20><img src=\"").append(icon).append("\" width=16 height=16></td><td><font color=\"").append(COLOR_DIM).append("\">").append(label).append("</font></td></tr></table>");
		sb.append("</td><td width=8></td></tr><tr><td height=4 colspan=3></td></tr>");
		sb.append("<tr><td></td><td height=18>").append(value.replace("<font ", "<font name=\"hs12\" ")).append("</td><td></td></tr>");
		sb.append("<tr><td></td><td><font color=\"").append(COLOR_MUTED).append("\">").append(other != null ? "Last: " + other : "&nbsp;").append("</font></td><td></td></tr>");
		sb.append("<tr><td height=7 colspan=3></td></tr></table>");
		return sb.toString();
	}
	
	/**
	 * Each class played in the session: from where to where, with the level's gauge.
	 */
	private static void appendClasses(StringBuilder sb, SessionRecord record)
	{
		for (ClassLine line : record.getClasses())
		{
			final ClassInfoHolder info = ClassListData.getInstance().getClass(line.getClassId());
			final double percent = SessionStatsManager.getLevelPercent(line.getEndLevel(), line.getEndExp());
			sb.append("<table width=").append(WIDTH).append(" cellpadding=0 cellspacing=0 bgcolor=").append(BG_ROW).append(">");
			sb.append("<tr><td height=8 colspan=4></td></tr><tr><td width=10></td>");
			sb.append("<td width=150 valign=top><font color=\"").append(COLOR_DIM).append("\">Class</font><br1><font color=\"").append(COLOR_NAME).append("\">").append(info != null ? info.getClassName() : "Class " + line.getClassId()).append("</font></td>");
			sb.append("<td width=").append(WIDTH - 170).append(" valign=top>").append(formatClass(record, line.getClassId(), true)).append("<br1>").append(gauge(WIDTH - 170, percent / 100)).append("</td>");
			sb.append("<td width=10></td></tr><tr><td height=8 colspan=4></td></tr></table>").append(spacer(6));
		}
	}
	
	/**
	 * A table with a column for each session: progress of each class played, experience, hunting, adena and the rest.
	 */
	private static void appendComparison(StringBuilder sb, List<String> titles, List<SessionRecord> records)
	{
		final int columnWidth = (WIDTH - LABEL_WIDTH - PAD) / records.size();
		sb.append(spacer(4));
		sb.append("<table width=").append(WIDTH).append(" height=34 cellpadding=0 cellspacing=0 bgcolor=").append(BG_HEAD).append("><tr><td width=").append(LABEL_WIDTH).append(">&nbsp;&nbsp;<font color=\"").append(COLOR_DIM).append("\">").append(records.size() > 1 ? "Side by side" : "In detail").append("</font></td>");
		for (String title : titles)
		{
			sb.append("<td width=").append(columnWidth).append(" align=right><font color=\"").append(COLOR_TITLE).append("\">").append(title).append("</font></td>");
		}
		sb.append("<td width=").append(PAD).append("></td></tr></table>");
		
		// Character.
		final List<Row> rows = new ArrayList<>();
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
			rows.add(row(info != null ? info.getClassName() : "Class " + classId, records, true, r -> formatClass(r, classId, false)));
		}
		rows.add(row("Levels gained", records, true, r -> signed(r.getLevelsGained(), false)));
		rows.add(row("Experience gained", records, true, r -> stat(r, SessionStat.EXP_GAINED)));
		rows.add(row("Experience lost", records, false, r -> lost(r.get(SessionStat.EXP_LOST))));
		rows.add(row("SP gained", records, true, r -> stat(r, SessionStat.SP_GAINED)));
		rows.add(row("Fame", records, false, r -> signed(r.get(SessionStat.FAME), false)));
		appendSection(sb, ICON_EXP, "Character", rows, columnWidth);
		
		// Hunting.
		rows.clear();
		rows.add(row("Monsters killed", records, true, r -> stat(r, SessionStat.KILLS)));
		rows.add(row("Killed by your party", records, false, r -> stat(r, SessionStat.PARTY_KILLS)));
		rows.add(row("Champions", records, false, r -> stat(r, SessionStat.CHAMPION_KILLS)));
		rows.add(row("Raid bosses", records, false, r -> stat(r, SessionStat.RAID_KILLS)));
		rows.add(row("PvP kills", records, false, r -> stat(r, SessionStat.PVP_KILLS)));
		rows.add(row("PK kills", records, false, r -> stat(r, SessionStat.PK_KILLS)));
		rows.add(row("Deaths", records, false, r -> lost(r.get(SessionStat.DEATHS))));
		appendSection(sb, ICON_HUNT, "Hunting", rows, columnWidth);
		
		// Adena.
		rows.clear();
		rows.add(row("Adena earned", records, true, r -> stat(r, SessionStat.ADENA_EARNED)));
		rows.add(row("Adena spent", records, true, r -> lost(r.get(SessionStat.ADENA_SPENT))));
		rows.add(row("Adena carried", records, true, r -> signed(r.getAdenaChange(), true)));
		appendSection(sb, ICON_ADENA, "Adena", rows, columnWidth);
		
		// Activity.
		rows.clear();
		rows.add(row("Quests completed", records, true, r -> stat(r, SessionStat.QUESTS)));
		rows.add(row("Drops picked up", records, false, r -> stat(r, SessionStat.ITEMS_LOOTED)));
		rows.add(row("Spoils swept", records, false, r -> stat(r, SessionStat.SPOILS)));
		rows.add(row("Items crafted", records, false, r -> stat(r, SessionStat.ITEMS_CRAFTED)));
		appendSection(sb, ICON_ACTIVITY, "Activity", rows, columnWidth);
		
		sb.append(message("Adena earned counts adena from monsters, quests, shops and your private store, not from trades, mail or the warehouse. Adena carried is how much more (or less) you hold than at login."));
	}
	
	private interface Cell
	{
		String get(SessionRecord record);
	}
	
	/**
	 * A row of the comparison: its label and a cell for each session, {@code null} when it isn't always shown and is empty in every session.
	 */
	private record Row(String label, List<String> cells)
	{
	}
	
	private static Row row(String label, List<SessionRecord> records, boolean always, Cell cell)
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
		return empty && !always ? null : new Row(label, cells);
	}
	
	/**
	 * A section of the comparison: its icon and title over a line, then its rows on alternating backgrounds.
	 */
	private static void appendSection(StringBuilder sb, String icon, String title, List<Row> rows, int columnWidth)
	{
		sb.append("<table width=").append(WIDTH).append(" height=26 cellpadding=0 cellspacing=0><tr><td width=8></td><td width=22><img src=\"").append(icon).append("\" width=16 height=16></td>");
		sb.append("<td width=").append(WIDTH - 30).append("><font color=\"").append(COLOR_SECTION).append("\">").append(title).append("</font></td></tr></table>");
		sb.append("<img src=\"L2UI.SquareGray\" width=").append(WIDTH).append(" height=1>");
		int line = 0;
		for (Row row : rows)
		{
			if (row == null)
			{
				continue;
			}
			sb.append("<table width=").append(WIDTH).append(" height=20 cellpadding=0 cellspacing=0").append((line++ % 2) == 0 ? " bgcolor=" + BG_ROW : "").append("><tr>");
			sb.append("<td width=30></td><td width=").append(LABEL_WIDTH - 30).append("><font color=\"").append(COLOR_TEXT).append("\">").append(row.label()).append("</font></td>");
			for (String text : row.cells())
			{
				sb.append("<td width=").append(columnWidth).append(" align=right>").append(text).append("</td>");
			}
			sb.append("<td width=").append(PAD).append("></td></tr></table>");
		}
	}
	
	private static void appendColumnTitle(StringBuilder sb, String title, int width)
	{
		sb.append("<td width=").append(width).append(" align=right><font color=\"").append(COLOR_TITLE).append("\">").append(title).append("</font></td>");
	}
	
	private static String spacer(int height)
	{
		return "<table width=" + WIDTH + " cellpadding=0 cellspacing=0><tr><td height=" + height + "></td></tr></table>";
	}
	
	private static String message(String text)
	{
		return "<table width=" + WIDTH + " cellpadding=0 cellspacing=0><tr><td height=10></td></tr><tr><td><font color=\"" + COLOR_MUTED + "\">" + text + "</font></td></tr></table>";
	}
	
	/**
	 * @param width the gauge's width
	 * @param ratio how full it is, from 0 to 1
	 * @return a thin experience gauge drawn with the client's own gauge textures
	 */
	private static String gauge(int width, double ratio)
	{
		final int fill = (int) Math.round(Math.max(0, Math.min(1, ratio)) * width);
		final String bar = fill > 0 ? "<img src=\"L2UI_CT1.Gauges.Gauge_DF_Large_EXP_Center\" width=" + fill + " height=8>" : "<img src=\"L2UI.SquareBlank\" width=1 height=8>";
		return "<table width=" + width + " cellpadding=0 cellspacing=0><tr><td width=" + width + " height=8 background=\"L2UI_CT1.Gauges.Gauge_DF_Large_EXP_bg_Center\">" + bar + "</td></tr></table>";
	}
	
	/**
	 * @param detailed with the percent of each level
	 * @return "76 (12.4%) to 77 (3.1%)" for a class played in the session ("76 to 77" when not detailed), a dash if it wasn't
	 */
	private static String formatClass(SessionRecord record, int classId, boolean detailed)
	{
		for (ClassLine line : record.getClasses())
		{
			if (line.getClassId() == classId)
			{
				final String start = line.getStartLevel() + (detailed ? " (" + formatPercent(line.getStartLevel(), line.getStartExp()) + ")" : "");
				final String end = line.getEndLevel() + (detailed ? " (" + formatPercent(line.getEndLevel(), line.getEndExp()) + ")" : "");
				final String color = line.getEndLevel() > line.getStartLevel() ? COLOR_GOOD : line.getEndLevel() < line.getStartLevel() ? COLOR_BAD : COLOR_VALUE;
				return "<font color=\"" + COLOR_TEXT + "\">" + start + "</font> <font color=\"" + COLOR_DIM + "\">to</font> <font color=\"" + color + "\">" + end + "</font>";
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
	
	private static String plainText(String text)
	{
		return "<font color=\"" + COLOR_NAME + "\">" + text + "</font>";
	}
	
	private static String lost(long value)
	{
		return "<font color=\"" + (value == 0 ? COLOR_MUTED : COLOR_BAD) + "\">" + FormatUtil.formatAdena(value) + "</font>";
	}
	
	private static String signed(long value, boolean full)
	{
		if (value == 0)
		{
			return "<font color=\"" + COLOR_MUTED + "\">0</font>";
		}
		return "<font color=\"" + (value > 0 ? COLOR_GOOD : COLOR_BAD) + "\">" + signedText(value, full) + "</font>";
	}
	
	private static String signedText(long value, boolean full)
	{
		final String number = full ? FormatUtil.formatAdena(Math.abs(value)) : String.valueOf(Math.abs(value));
		return value == 0 ? "0" : (value > 0 ? "+" : "-") + number;
	}
	
	private static String signedShort(long value)
	{
		if (value == 0)
		{
			return "<font color=\"" + COLOR_MUTED + "\">0</font>";
		}
		return "<font color=\"" + (value > 0 ? COLOR_GOOD : COLOR_BAD) + "\">" + signedShortText(value) + "</font>";
	}
	
	private static String signedShortText(long value)
	{
		return value == 0 ? "0" : (value > 0 ? "+" : "-") + SessionStatsManager.formatShort(Math.abs(value));
	}
	
	private static String formatDate(long time)
	{
		return new SimpleDateFormat("MMM d, HH:mm", Locale.ENGLISH).format(new Date(time));
	}
	
	private static String formatDay(long time)
	{
		return new SimpleDateFormat("MMM d", Locale.ENGLISH).format(new Date(time));
	}
	
	private static String formatTime(long time)
	{
		return new SimpleDateFormat("HH:mm", Locale.ENGLISH).format(new Date(time));
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
	
	/**
	 * @return a tab's button, drawn pressed when it is the open one
	 */
	private static String tab(String text, String action, int width, boolean open)
	{
		return "<button value=\"" + text + "\" action=\"bypass " + action + "\" width=" + width + " height=22 back=\"L2UI_CT1.Button_DF_Down\" fore=\"" + (open ? "L2UI_CT1.Button_DF_Down" : "L2UI_CT1.Button_DF") + "\">";
	}
}
