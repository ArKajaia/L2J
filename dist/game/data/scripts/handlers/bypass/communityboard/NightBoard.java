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

import java.util.List;

import org.l2jmobius.gameserver.cache.HtmCache;
import org.l2jmobius.gameserver.config.custom.NightCycleConfig;
import org.l2jmobius.gameserver.handler.CommunityBoardHandler;
import org.l2jmobius.gameserver.handler.IParseBoardHandler;
import org.l2jmobius.gameserver.managers.HotzoneModifierManager;
import org.l2jmobius.gameserver.managers.NightCycleManager;
import org.l2jmobius.gameserver.managers.NightCycleManager.NightRecord;
import org.l2jmobius.gameserver.managers.NightlordManager;
import org.l2jmobius.gameserver.managers.NightlordManager.Nightlord;
import org.l2jmobius.gameserver.model.actor.Player;
import org.l2jmobius.gameserver.model.hotzone.HotzoneModifier;
import org.l2jmobius.gameserver.model.nightcycle.NightPhase;

/**
 * The night board: the phase of the day, tonight's Omen, the Nightlords (with a radar button) and the Night Watch (see {@link NightCycleManager}). {@code .night} shows the same in a window.
 */
public class NightBoard implements IParseBoardHandler
{
	private static final String NAVIGATION_PATH = "data/html/CommunityBoard/Custom/navigation.html";
	private static final String PAGE_PATH = "data/html/CommunityBoard/Custom/night/main.html";

	private static final String COLOR_TITLE = "CDB67F";
	private static final String COLOR_VALUE = "FFCC33";
	private static final String COLOR_TEXT = "B0B0B0";
	private static final String COLOR_MUTED = "707070";
	private static final String COLOR_NIGHT = "8888FF";
	private static final String COLOR_RED = "FF6666";

	private static final String[] COMMANDS =
	{
		"_bbsnight"
	};

	@Override
	public String[] getCommandList()
	{
		return COMMANDS;
	}

	@Override
	public boolean onCommand(String command, Player player)
	{
		CommunityBoardHandler.getInstance().addBypass(player, "Night", command);

		String html = HtmCache.getInstance().getHtm(player, PAGE_PATH);
		html = html.replace("%navigation%", HtmCache.getInstance().getHtm(player, NAVIGATION_PATH));
		html = html.replace("%content%", buildContent(player, 480, "_bbsnight"));
		CommunityBoardHandler.separateAndSend(html, player);
		return true;
	}

	/**
	 * @param player the player reading it
	 * @param width the width of the page
	 * @param refreshBypass the bypass of the refresh button
	 * @return the night's status as html
	 */
	public static String buildContent(Player player, int width, String refreshBypass)
	{
		final StringBuilder sb = new StringBuilder();
		if (!NightCycleConfig.ENABLED)
		{
			sb.append("<table width=").append(width).append("><tr><td align=center><font color=\"").append(COLOR_TEXT).append("\">The night is like any other.</font></td></tr></table>");
			return sb.toString();
		}

		final NightCycleManager manager = NightCycleManager.getInstance();
		final NightPhase phase = manager.getPhase();

		// Phase and time.
		sb.append("<table width=").append(width).append(" bgcolor=111111><tr><td align=center height=30>");
		sb.append("<font color=\"").append(phase.isNight() ? COLOR_NIGHT : COLOR_VALUE).append("\">").append(phase.getDisplayName().toUpperCase()).append("</font>");
		sb.append("<br1><font color=\"").append(COLOR_TEXT).append("\">");
		switch (phase)
		{
			case NIGHT:
			{
				sb.append(NightCycleConfig.WITCHING_HOUR_ENABLED ? "The Witching Hour begins in about " : "Dawn breaks in about ").append(manager.getRealMinutesLeft()).append(" min.");
				break;
			}
			case WITCHING_HOUR:
			{
				sb.append("Dawn breaks in about ").append(manager.getRealMinutesLeft()).append(" min.");
				break;
			}
			default:
			{
				sb.append("Night falls in about ").append(manager.getRealMinutesToNightfall()).append(" min.");
				break;
			}
		}
		if (manager.isForced())
		{
			sb.append(" <font color=\"").append(COLOR_MUTED).append("\">(set by a GM)</font>");
		}
		sb.append("</font></td></tr></table><br>");

		// Omen.
		final HotzoneModifier nightModifier = HotzoneModifierManager.getInstance().getNightModifier();
		final HotzoneModifier omen = phase.isNight() ? nightModifier : manager.getOmen();
		sb.append("<table width=").append(width).append("><tr><td><font color=\"").append(COLOR_TITLE).append("\">");
		sb.append(phase == NightPhase.WITCHING_HOUR ? "The Witching Hour" : (phase.isNight() || (phase == NightPhase.DUSK)) ? "Tonight's Omen" : "Omens");
		sb.append("</font></td></tr><tr><td><font color=\"").append(COLOR_TEXT).append("\">");
		if (omen != null)
		{
			final boolean red = NightCycleConfig.OMEN_RED_SKY.contains(omen) || (omen == HotzoneModifier.WITCHING_HOUR);
			sb.append("<font color=\"").append(red ? COLOR_RED : COLOR_VALUE).append("\">").append(NightCycleManager.getOmenName(omen)).append("</font>: ").append(omen.getDescription());
			if (phase.isNight())
			{
				sb.append("<br1><font color=\"").append(COLOR_MUTED).append("\">In the open world, outside towns and active hot zones.</font>");
			}
		}
		else if (NightCycleConfig.OMENS_ENABLED)
		{
			sb.append("Each night brings an Omen over the open world. It is told at dusk.");
		}
		else
		{
			sb.append("No Omen tonight.");
		}
		sb.append("</font></td></tr></table><br>");

		// Nightlords.
		if (NightCycleConfig.NIGHTLORD_ENABLED)
		{
			sb.append("<table width=").append(width).append("><tr><td><font color=\"").append(COLOR_TITLE).append("\">Nightlords</font></td></tr></table>");
			final List<Nightlord> nightlords = NightlordManager.getInstance().getNightlords();
			if (nightlords.isEmpty())
			{
				sb.append("<table width=").append(width).append("><tr><td><font color=\"").append(COLOR_TEXT).append("\">");
				if (phase.isNight())
				{
					sb.append("No Nightlord walks right now.");
				}
				else
				{
					sb.append("Soon after nightfall a Nightlord rises in each level bracket. Beat it in ").append(NightCycleConfig.NIGHTLORD_WAVES).append(" waves: everyone who fought it gets a Sealed Cache.");
				}
				sb.append("</font></td></tr></table>");
			}
			else
			{
				int row = 0;
				for (Nightlord nightlord : nightlords)
				{
					sb.append("<table width=").append(width).append(" height=24").append((row++ % 2) == 0 ? " bgcolor=111111" : "").append("><tr>");
					sb.append("<td width=").append(width - 70).append("><font color=\"").append(COLOR_RED).append("\">").append(nightlord.getMonster().getName()).append("</font> <font color=\"").append(COLOR_TEXT).append("\">Lv ").append(nightlord.getMonster().getLevel()).append(" - ").append(nightlord.getZoneName()).append("</font></td>");
					sb.append("<td width=70 align=right><button value=\"Radar\" action=\"bypass voiced_night radar ").append(nightlord.getMonster().getObjectId()).append("\" width=60 height=20 back=\"L2UI_CT1.Button_DF_Down\" fore=\"L2UI_CT1.Button_DF\"></td>");
					sb.append("</tr></table>");
				}
			}
			sb.append("<br>");
		}

		// Night Watch.
		if (NightCycleConfig.NIGHT_WATCH_ENABLED)
		{
			sb.append("<table width=").append(width).append("><tr><td><font color=\"").append(COLOR_TITLE).append("\">Night Watch</font></td></tr><tr><td><font color=\"").append(COLOR_TEXT).append("\">");
			final NightRecord record = manager.getNightRecord(player);
			if (record != null)
			{
				sb.append("Tonight: ").append(record.getKills()).append(" kills, ").append(record.getNightlords()).append(" Nightlords - <font color=\"").append(COLOR_VALUE).append("\">").append(record.getPoints()).append(" points</font>.");
			}
			else
			{
				sb.append("Hunt in the open world at night. At dawn the best 3 (").append(NightCycleConfig.NIGHT_WATCH_MIN_POINTS).append("+ points) are rewarded.");
			}
			sb.append("</font></td></tr></table>");

			final List<NightRecord> best = manager.getNightWatch(3);
			int rank = 0;
			for (NightRecord entry : best)
			{
				rank++;
				final boolean self = entry.getObjectId() == player.getObjectId();
				sb.append("<table width=").append(width).append("><tr>");
				sb.append("<td width=30 align=center><font color=\"").append(rank == 1 ? COLOR_VALUE : COLOR_TEXT).append("\">").append(rank).append("</font></td>");
				sb.append("<td width=").append(width - 130).append("><font color=\"").append(self ? "LEVEL" : COLOR_TEXT).append("\">").append(entry.getName()).append("</font></td>");
				sb.append("<td width=100 align=right><font color=\"").append(COLOR_TEXT).append("\">").append(entry.getPoints()).append("</font></td>");
				sb.append("</tr></table>");
			}
			sb.append("<br>");
		}

		sb.append("<table width=").append(width).append("><tr><td align=center><button value=\"Refresh\" action=\"bypass ").append(refreshBypass).append("\" width=80 height=22 back=\"L2UI_CT1.Button_DF_Down\" fore=\"L2UI_CT1.Button_DF\"></td></tr></table>");
		return sb.toString();
	}
}
