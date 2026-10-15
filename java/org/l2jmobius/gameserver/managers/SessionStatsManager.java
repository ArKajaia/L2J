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
package org.l2jmobius.gameserver.managers;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

import org.l2jmobius.commons.threads.ThreadPool;
import org.l2jmobius.gameserver.config.PlayerConfig;
import org.l2jmobius.gameserver.config.custom.SessionStatsConfig;
import org.l2jmobius.gameserver.data.xml.ExperienceData;
import org.l2jmobius.gameserver.model.actor.Attackable;
import org.l2jmobius.gameserver.model.actor.Creature;
import org.l2jmobius.gameserver.model.actor.Player;
import org.l2jmobius.gameserver.model.sessionstats.SessionRecord;
import org.l2jmobius.gameserver.model.sessionstats.SessionStat;
import org.l2jmobius.gameserver.model.sessionstats.SessionStats;
import org.l2jmobius.gameserver.util.FormatUtil;
import org.l2jmobius.gameserver.util.LocationUtil;

/**
 * The session log: everything a character achieves between logging in and logging out (experience, levels, monsters, adena, quests...).<br>
 * A session starts when the player enters the world and ends when they log out or leave a private store behind. The finished sessions are kept in the {@link #VAR_HISTORY} player variable, newest first. The Community Board shows them ({@code _bbssession}).
 */
public class SessionStatsManager
{
	public static final String VAR_HISTORY = "SESSION_HISTORY";
	
	private static final long LOGIN_SUMMARY_DELAY = 5000;
	
	protected SessionStatsManager()
	{
	}
	
	/**
	 * Starts the session of a player entering the world.
	 * @param player the player
	 */
	public void onEnter(Player player)
	{
		if (!SessionStatsConfig.ENABLED || (player.getSessionStats() != null))
		{
			return;
		}
		
		player.setSessionStats(new SessionStats(player));
		
		if (SessionStatsConfig.LOGIN_SUMMARY)
		{
			final List<SessionRecord> history = getHistory(player);
			if (!history.isEmpty())
			{
				final String summary = getLoginSummary(history.get(0));
				ThreadPool.schedule(() ->
				{
					if (player.isOnline())
					{
						player.sendMessage(summary);
					}
				}, LOGIN_SUMMARY_DELAY);
			}
		}
	}
	
	/**
	 * Ends the session of a player who logs out (or leaves an offline store) and saves it in the history. Call it before the player is stored.
	 * @param player the player
	 */
	public void onLeave(Player player)
	{
		final SessionStats stats = player.getSessionStats();
		if (stats == null)
		{
			return;
		}
		player.setSessionStats(null);
		
		final SessionRecord record = stats.toRecord(player);
		if (!record.hasProgress() && (record.getDurationMillis() < (SessionStatsConfig.MIN_MINUTES * 60000L)))
		{
			return;
		}
		
		final List<SessionRecord> history = new ArrayList<>(getHistory(player));
		history.add(0, record);
		final StringBuilder sb = new StringBuilder();
		for (int i = 0; (i < history.size()) && (i < SessionStatsConfig.HISTORY_SIZE); i++)
		{
			sb.append(i > 0 ? ";" : "").append(history.get(i).serialize());
		}
		player.getVariables().set(VAR_HISTORY, sb.toString());
	}
	
	/**
	 * @param player the player
	 * @return the finished sessions of the player, newest first
	 */
	public List<SessionRecord> getHistory(Player player)
	{
		final String saved = player.getVariables().getString(VAR_HISTORY, "");
		if (saved.isEmpty())
		{
			return Collections.emptyList();
		}
		
		final List<SessionRecord> history = new ArrayList<>();
		for (String text : saved.split(";"))
		{
			final SessionRecord record = SessionRecord.parse(text);
			if (record != null)
			{
				history.add(record);
			}
		}
		return history;
	}
	
	/**
	 * Remembers where a class stands when the player switches to it.
	 * @param player the player
	 */
	public static void onClassChanged(Player player)
	{
		final SessionStats stats = player.getSessionStats();
		if (stats != null)
		{
			stats.touchClass(player);
		}
	}
	
	/**
	 * Counts something for the session of a player.
	 * @param player the player, may be {@code null}
	 * @param stat what to count
	 * @param value how much
	 */
	public static void add(Player player, SessionStat stat, long value)
	{
		if (player != null)
		{
			final SessionStats stats = player.getSessionStats();
			if (stats != null)
			{
				stats.add(stat, value);
			}
		}
	}
	
	/**
	 * Counts a monster killed: for the player who killed it (or whose summon did), and for the members of their party who were around.
	 * @param monster the monster
	 * @param killer the creature that killed it
	 * @param player the player credited with the kill (the killer, the owner of the summon, or the player in party with the fake player who killed it)
	 */
	public static void onMonsterKilled(Attackable monster, Creature killer, Player player)
	{
		final boolean raid = monster.isRaid() && !monster.isRaidMinion();
		if (killer.asPlayer() == player)
		{
			add(player, SessionStat.KILLS, 1);
			if (monster.getChampionTier() > 0)
			{
				add(player, SessionStat.CHAMPION_KILLS, 1);
			}
		}
		else
		{
			add(player, SessionStat.PARTY_KILLS, 1);
		}
		if (raid)
		{
			add(player, SessionStat.RAID_KILLS, 1);
		}
		
		if (player.isInParty())
		{
			for (Player member : player.getParty().getMembers())
			{
				if ((member != player) && LocationUtil.checkIfInRange(PlayerConfig.ALT_PARTY_RANGE, monster, member, true))
				{
					add(member, SessionStat.PARTY_KILLS, 1);
					if (raid)
					{
						add(member, SessionStat.RAID_KILLS, 1);
					}
				}
			}
		}
	}
	
	// ---------------------------------------------------------------------------------------------
	// Text
	// ---------------------------------------------------------------------------------------------
	
	private static String getLoginSummary(SessionRecord record)
	{
		final List<String> parts = new ArrayList<>();
		final int levels = record.getLevelsGained();
		if (levels != 0)
		{
			parts.add((levels > 0 ? "+" : "") + levels + (Math.abs(levels) == 1 ? " level" : " levels"));
		}
		if (record.get(SessionStat.EXP_GAINED) > 0)
		{
			parts.add(formatShort(record.get(SessionStat.EXP_GAINED)) + " XP");
		}
		final long kills = record.get(SessionStat.KILLS);
		if (kills > 0)
		{
			parts.add(FormatUtil.formatAdena(kills) + (kills == 1 ? " monster" : " monsters"));
		}
		if (record.get(SessionStat.ADENA_EARNED) > 0)
		{
			parts.add(formatShort(record.get(SessionStat.ADENA_EARNED)) + " adena earned");
		}
		final long quests = record.get(SessionStat.QUESTS);
		if (quests > 0)
		{
			parts.add(quests + (quests == 1 ? " quest" : " quests"));
		}
		
		final StringBuilder sb = new StringBuilder("Your last session (").append(formatDuration(record.getDurationMillis())).append("): ");
		sb.append(parts.isEmpty() ? "nothing new" : String.join(", ", parts)).append(". Open the Community Board > Session Log to see it all.");
		return sb.toString();
	}
	
	/**
	 * @param millis a duration
	 * @return the duration as "2h 14m", "35m" or "40s"
	 */
	public static String formatDuration(long millis)
	{
		final long minutes = millis / 60000;
		if (minutes <= 0)
		{
			return (millis / 1000) + "s";
		}
		if (minutes < 60)
		{
			return minutes + "m";
		}
		final long hours = minutes / 60;
		if (hours < 24)
		{
			return hours + "h " + (minutes % 60) + "m";
		}
		return (hours / 24) + "d " + (hours % 24) + "h";
	}
	
	/**
	 * @param value a number
	 * @return the number as "1.23M", "45.6K" or "987"
	 */
	public static String formatShort(long value)
	{
		final long abs = Math.abs(value);
		if (abs >= 1_000_000_000L)
		{
			return String.format(Locale.ENGLISH, "%.2fB", value / 1_000_000_000d);
		}
		if (abs >= 1_000_000L)
		{
			return String.format(Locale.ENGLISH, "%.2fM", value / 1_000_000d);
		}
		if (abs >= 10_000L)
		{
			return String.format(Locale.ENGLISH, "%.1fK", value / 1_000d);
		}
		return FormatUtil.formatAdena(value);
	}
	
	/**
	 * @param level a level
	 * @param exp the experience
	 * @return how far into the level the experience is, in percent
	 */
	public static double getLevelPercent(int level, long exp)
	{
		final ExperienceData data = ExperienceData.getInstance();
		if ((level + 1) >= data.getMaxLevel())
		{
			return 0;
		}
		final long floor = data.getExpForLevel(level);
		final long next = data.getExpForLevel(level + 1);
		return next > floor ? Math.max(0, Math.min(99.99, ((exp - floor) * 100d) / (next - floor))) : 0;
	}
	
	public static SessionStatsManager getInstance()
	{
		return SingletonHolder.INSTANCE;
	}
	
	private static class SingletonHolder
	{
		protected static final SessionStatsManager INSTANCE = new SessionStatsManager();
	}
}
