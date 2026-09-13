package handlers.chat.commands.voiced;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;

import org.l2jmobius.commons.threads.ThreadPool;
import org.l2jmobius.gameserver.handler.IVoicedCommandHandler;
import org.l2jmobius.gameserver.model.actor.Player;
import org.l2jmobius.gameserver.model.actor.stat.PlayerStatsTracker;
import org.l2jmobius.gameserver.network.serverpackets.ActionFailed;
import org.l2jmobius.gameserver.network.serverpackets.NpcHtmlMessage;

public class VoiceDpsMeter implements IVoicedCommandHandler
{
	private static final String[] VOICED_COMMANDS =
	{
		"dps",
		"meter",
		"dps_stop"
	};
	
	// Stores active 1-second update threads per player ID
	private static final Map<Integer, ScheduledFuture<?>> ACTIVE_TASKS = new ConcurrentHashMap<>();
	
	@Override
	public boolean onCommand(String command, Player player, String params)
	{
		if (player == null)
		{
			return false;
		}
		
		if ("dps_stop".equalsIgnoreCase(command))
		{
			stopAutoRefresh(player);
			return true;
		}
		
		// Render window immediately
		showWindow(player);
		
		// Start background 1-second auto-refresh
		startAutoRefresh(player);
		
		return true;
	}
	
	private void startAutoRefresh(Player player)
	{
		final int objectId = player.getObjectId();
		
		if (ACTIVE_TASKS.containsKey(objectId))
		{
			return;
		}
		
		ScheduledFuture<?> task = ThreadPool.scheduleAtFixedRate(() ->
		{
			if (!player.isOnline())
			{
				stopAutoRefresh(player);
				return;
			}
			
			showWindow(player);
		}, 1000, 1000);
		
		ACTIVE_TASKS.put(objectId, task);
	}
	
	public static void stopAutoRefresh(Player player)
	{
		if (player == null)
		{
			return;
		}
		
		ScheduledFuture<?> task = ACTIVE_TASKS.remove(player.getObjectId());
		if (task != null)
		{
			task.cancel(false);
		}
	}
	
	private void showWindow(Player player)
	{
		final NpcHtmlMessage html = new NpcHtmlMessage(0);
		final StringBuilder sb = new StringBuilder();
		
		final PlayerStatsTracker tracker = player.getStatsTracker();
		final double dpsRaw = tracker != null ? tracker.getDps() : 0.0;
		final long expPerHourRaw = tracker != null ? tracker.getExpPerHour() : 0L;
		
		final String currentDps = String.format("%,.1f", dpsRaw);
		final String expPerHour = String.format("%,d", expPerHourRaw);
		final String targetName = player.getTarget() != null ? player.getTarget().getName() : "None";
		final String timeToLevel = calculateTimeToLevel(player, expPerHourRaw);
		
		sb.append("<html><body><center>");
		
		// Header Row
		sb.append("<table width=250 height=30 bgcolor=000000>");
		sb.append("<tr><td align=center valign=middle color=\"LEVEL\"><b>DPS & EXP METER</b></td></tr>");
		sb.append("</table><br>");
		
		// Main Statistics Grid
		sb.append("<table width=250 border=0 cellspacing=2 cellpadding=3 bgcolor=111111>");
		sb.append("<tr><td width=110 align=left><font color=\"FFFFFF\">Target Name:</font></td><td width=140 align=right><font color=\"00FFFF\">").append(targetName).append("</font></td></tr>");
		sb.append("<tr><td width=110 align=left><font color=\"FFFFFF\">Current DPS:</font></td><td width=140 align=right><font color=\"FF9900\"><b>").append(currentDps).append("</b></font> /s</td></tr>");
		sb.append("<tr><td width=110 align=left><font color=\"FFFFFF\">EXP / Hour:</font></td><td width=140 align=right><font color=\"00FF00\"><b>").append(expPerHour).append("</b></font></td></tr>");
		sb.append("<tr><td width=110 align=left><font color=\"FFFFFF\">Time to Level:</font></td><td width=140 align=right><font color=\"FFFF00\"><b>").append(timeToLevel).append("</b></font></td></tr>");
		sb.append("</table><br>");
		
		// Action Buttons
		sb.append("<table width=250 border=0>");
		sb.append("<tr>");
		sb.append("<td align=center><button value=\"Refresh\" action=\"bypass voiced_dps\" width=90 height=22 back=\"L2UI_CT1.Button_DF_Down\" fore=\"L2UI_CT1.Button_DF\"></td>");
		sb.append("<td align=center><button value=\"Stop Auto\" action=\"bypass voiced_dps_stop\" width=90 height=22 back=\"L2UI_CT1.Button_DF_Down\" fore=\"L2UI_CT1.Button_DF\"></td>");
		sb.append("</tr>");
		sb.append("</table>");
		
		sb.append("</center></body></html>");
		
		html.setHtml(sb.toString());
		player.sendPacket(html);
		player.sendPacket(ActionFailed.STATIC_PACKET);
	}
	
	/**
	 * Helper method to estimate the remaining time to reach the next level.
	 * @param player
	 * @param expPerHour
	 * @return
	 */
	private static String calculateTimeToLevel(Player player, long expPerHour)
	{
		if (expPerHour <= 0)
		{
			return "N/A";
		}
		
		final long currentExp = player.getStat().getExp();
		final long nextLevelExp = player.getStat().getExpForLevel(player.getLevel() + 1);
		final long expNeeded = nextLevelExp - currentExp;
		
		if (expNeeded <= 0)
		{
			return "Max Level";
		}
		
		// Calculate total seconds needed
		final double hoursNeeded = (double) expNeeded / expPerHour;
		final long totalSeconds = (long) (hoursNeeded * 3600);
		
		if (totalSeconds < 60)
		{
			return "< 1m";
		}
		
		final long days = totalSeconds / 86400;
		final long hours = (totalSeconds % 86400) / 3600;
		final long minutes = (totalSeconds % 3600) / 60;
		
		if (days > 99)
		{
			return "> 99d";
		}
		else if (days > 0)
		{
			return String.format("%dd %dh", days, hours);
		}
		else if (hours > 0)
		{
			return String.format("%dh %dm", hours, minutes);
		}
		else
		{
			return String.format("%dm", minutes);
		}
	}
	
	@Override
	public String[] getCommandList()
	{
		return VOICED_COMMANDS;
	}
	
	@Override
	public boolean useVoicedCommand(String command, Player activeChar, String params)
	{
		return onCommand(command, activeChar, params);
	}
	
	@Override
	public String[] getVoicedCommandList()
	{
		return VOICED_COMMANDS;
	}
}