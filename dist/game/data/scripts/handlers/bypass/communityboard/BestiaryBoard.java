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

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.l2jmobius.gameserver.cache.HtmCache;
import org.l2jmobius.gameserver.config.custom.BestiaryConfig;
import org.l2jmobius.gameserver.config.custom.PassiveTreeConfig;
import org.l2jmobius.gameserver.data.xml.NpcData;
import org.l2jmobius.gameserver.handler.CommunityBoardHandler;
import org.l2jmobius.gameserver.handler.IParseBoardHandler;
import org.l2jmobius.gameserver.managers.BestiaryManager;
import org.l2jmobius.gameserver.model.actor.Player;
import org.l2jmobius.gameserver.model.actor.enums.creature.Race;
import org.l2jmobius.gameserver.model.actor.templates.NpcTemplate;
import org.l2jmobius.gameserver.network.serverpackets.ExLinkOpen;
import org.l2jmobius.gameserver.util.FormatUtil;

import custom.Bestiary.BestiaryCatalog;
import custom.Bestiary.BestiaryTracker;
import custom.Bestiary.BestiaryWeb;
import custom.PassiveSkillTree.PassiveTreeApiServer;

/**
 * The bestiary on the Community Board (see {@link BestiaryManager}), for who doesn't open the web page: how much of the collection the account found, today's bounties, the entries closest to their next tier, and the slayer bonus against each race. Each monster can be tracked on the radar.
 * <ul>
 * <li>{@code _bbsbestiary} - the overview</li>
 * <li>{@code _bbsbestiary track <npc id>} - points the radar at the nearest one</li>
 * <li>{@code _bbsbestiary untrack} - clears the radar</li>
 * <li>{@code _bbsbestiary web} - sends the link of the web page</li>
 * </ul>
 */
public class BestiaryBoard implements IParseBoardHandler
{
	private static final String BYPASS = "_bbsbestiary";

	private static final String NAVIGATION_PATH = "data/html/CommunityBoard/Custom/navigation.html";
	private static final String PAGE_PATH = "data/html/CommunityBoard/Custom/bestiary/main.html";

	/** The content is 480 wide, inside a 700 wide page: the board scrolls, and its scroll bar takes some of the width. */
	private static final int WIDTH = 480;
	/** The bounty and "almost there" columns: monster, progress, reward or next tier, and the track button. They add up to {@link #WIDTH}. */
	private static final int[] LIST_COLUMNS =
	{
		210,
		80,
		120,
		70
	};
	/** The slayer columns: race, found, damage, defense. */
	private static final int[] SLAYER_COLUMNS =
	{
		160,
		120,
		100,
		100
	};
	private static final int NEAR_SHOWN = 6;
	private static final int SLAYER_SHOWN = 8;

	private static final String COLOR_SECTION = "LEVEL";
	private static final String COLOR_VALUE = "FFCC33";
	private static final String COLOR_TEXT = "B0B0B0";
	private static final String COLOR_MUTED = "707070";
	private static final String COLOR_GOOD = "88DD66";
	/** The colors of the tiers, as the web page has them. */
	private static final String[] TIER_COLORS =
	{
		"8FA3B5",
		"4AA8FF",
		"B07CFF",
		"F2C94C"
	};

	/** Race names as the web page reads them. */
	private static final Map<Race, String> RACE_NAMES = new HashMap<>();
	static
	{
		RACE_NAMES.put(Race.BUG, "Insects");
		RACE_NAMES.put(Race.DEMONIC, "Demons");
		RACE_NAMES.put(Race.DIVINE, "Angels");
		RACE_NAMES.put(Race.ETC, "Creatures");
		RACE_NAMES.put(Race.CASTLE_GUARD, "Guards");
	}

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
		final String[] args = command.substring(BYPASS.length()).trim().split("\\s+");
		String notice = null;
		if (BestiaryConfig.ENABLED)
		{
			switch (args[0].toLowerCase(Locale.ROOT))
			{
				case "track":
				{
					notice = args.length > 1 ? BestiaryTracker.track(player, parseInt(args[1])).message() : null;
					if (notice != null)
					{
						player.sendMessage(notice);
					}
					break;
				}
				case "untrack":
				{
					BestiaryTracker.untrack(player);
					notice = "Radar cleared.";
					break;
				}
				case "web":
				{
					final String url = BestiaryWeb.getUrl(player, 0);
					player.sendMessage("Your bestiary (click to open, valid " + PassiveTreeApiServer.describeLifetime(PassiveTreeConfig.WEB_TOKEN_LIFETIME) + "):");
					player.sendMessage(url);
					player.sendPacket(new ExLinkOpen(url));
					notice = "The link of your bestiary is in your chat.";
					break;
				}
			}
		}

		// The page is the same after an action: the history keeps the overview only.
		CommunityBoardHandler.getInstance().addBypass(player, "Bestiary", BYPASS);
		final String content = BestiaryConfig.ENABLED ? buildOverview(player, notice) : "<table width=" + WIDTH + "><tr><td align=center><font color=\"" + COLOR_TEXT + "\">The bestiary is disabled.</font></td></tr></table>";
		String html = HtmCache.getInstance().getHtm(player, PAGE_PATH);
		html = html.replace("%navigation%", HtmCache.getInstance().getHtm(player, NAVIGATION_PATH));
		html = html.replace("%content%", content);
		CommunityBoardHandler.separateAndSend(html, player);
		return true;
	}

	// ---------------------------------------------------------------------------------------------
	// Page
	// ---------------------------------------------------------------------------------------------

	private static String buildOverview(Player player, String notice)
	{
		final BestiaryManager manager = BestiaryManager.getInstance();
		final BestiaryCatalog catalog = BestiaryCatalog.getInstance();
		final Map<Integer, BestiaryManager.Entry> entries = manager.getEntries(player.getObjectId());

		int found = 0;
		int legendary = 0;
		long kills = 0;
		int forms = 0;
		final int enabledForms = BestiaryManager.getEnabledVariants();
		final List<NpcTemplate> near = new ArrayList<>();
		for (BestiaryCatalog.Kind kind : catalog.getMonsters().values())
		{
			final NpcTemplate template = kind.getTemplate();
			final BestiaryManager.Entry entry = entries.get(template.getId());
			if (entry == null)
			{
				continue;
			}

			final int tier = BestiaryManager.getTier(template, entry.kills());
			found += tier > 0 ? 1 : 0;
			legendary += tier >= BestiaryConfig.TIER_NAMES.length ? 1 : 0;
			kills += entry.kills();
			forms += Integer.bitCount(entry.variants() & enabledForms);
			if ((tier > 0) && (tier < BestiaryConfig.TIER_NAMES.length))
			{
				near.add(template);
			}
		}
		near.sort((a, b) -> Integer.compare(remaining(a, entries), remaining(b, entries)));

		final StringBuilder sb = new StringBuilder();
		if (notice != null)
		{
			sb.append("<table width=").append(WIDTH).append(" bgcolor=111111><tr><td align=center height=24><font color=\"").append(COLOR_VALUE).append("\">").append(notice).append("</font></td></tr></table><br>");
		}

		// Summary.
		final int total = catalog.getMonsters().size();
		final int rank = manager.getServerStats().getRank(manager.getAccountName(player.getObjectId()));
		sb.append("<table width=").append(WIDTH).append(" cellpadding=0 cellspacing=0><tr>");
		appendStat(sb, "Found", FormatUtil.formatAdena(found) + " <font color=\"" + COLOR_MUTED + "\">/ " + FormatUtil.formatAdena(total) + "</font>");
		appendStat(sb, "Legendary", FormatUtil.formatAdena(legendary));
		appendStat(sb, "Kills", FormatUtil.formatAdena(kills));
		appendStat(sb, "Rank", rank > 0 ? "#" + FormatUtil.formatAdena(rank) : "-");
		if (enabledForms != 0)
		{
			appendStat(sb, "Rare forms", FormatUtil.formatAdena(forms));
		}
		sb.append("</tr></table><br>");

		appendBounties(sb, player);

		// Almost there.
		appendSection(sb, "Almost there");
		if (near.isEmpty())
		{
			appendNote(sb, "Kill a monster to start your bestiary.");
		}
		for (int i = 0; i < Math.min(NEAR_SHOWN, near.size()); i++)
		{
			final NpcTemplate template = near.get(i);
			final int tier = BestiaryManager.getTier(template, entries.get(template.getId()).kills());
			final int left = remaining(template, entries);
			appendLine(sb, i, template, FormatUtil.formatAdena(left) + " more", "<font color=\"" + TIER_COLORS[tier] + "\">" + BestiaryConfig.TIER_NAMES[tier] + "</font>");
		}
		sb.append("<br>");

		appendSlayer(sb, entries);

		sb.append("<br><table width=").append(WIDTH).append("><tr>");
		sb.append("<td align=center>").append(button("Open on the web", BYPASS + " web", 150)).append("</td>");
		sb.append("<td align=center>").append(button("Clear the radar", BYPASS + " untrack", 150)).append("</td>");
		sb.append("</tr></table>");
		appendNote(sb, "The web page has every monster, its stats, drops and map. Your bestiary is your account's: every character adds to it and has its bonuses.");
		return sb.toString();
	}

	private static void appendBounties(StringBuilder sb, Player player)
	{
		if (!BestiaryConfig.BOUNTY_ENABLED)
		{
			return;
		}

		final BestiaryManager.BountyBoard board = BestiaryManager.getInstance().getBountyBoard(player.getObjectId());
		final long minutes = Math.max(1, (board.resetAt() - System.currentTimeMillis()) / 60000);
		appendSection(sb, "Today's bounties <font color=\"" + COLOR_MUTED + "\">- new ones in " + (minutes >= 60 ? (minutes / 60) + " h" : minutes + " min") + ", kills count x" + BestiaryConfig.BOUNTY_KILL_MULTIPLIER + "</font>");
		if (board.bounties().isEmpty())
		{
			appendNote(sb, "No bounty yet today: they come when you log in or make your first kill.");
		}
		for (int i = 0; i < board.bounties().size(); i++)
		{
			final BestiaryManager.Bounty bounty = board.bounties().get(i);
			final NpcTemplate template = NpcData.getInstance().getTemplate(bounty.npcId());
			if (template == null)
			{
				continue;
			}

			final String progress = bounty.isDone() ? "<font color=\"" + COLOR_GOOD + "\">Done</font>" : bounty.progress() + " / " + bounty.goal();
			final long adena = (long) BestiaryConfig.BOUNTY_ADENA_PER_LEVEL * template.getLevel();
			appendLine(sb, i, template, progress, adena > 0 ? FormatUtil.formatAdena(adena) + " adena" : "");
		}
		sb.append("<br>");
	}

	private static void appendSlayer(StringBuilder sb, Map<Integer, BestiaryManager.Entry> entries)
	{
		final List<BestiaryManager.Slayer> races = new ArrayList<>(BestiaryManager.getInstance().getSlayer(entries).values());
		if (races.isEmpty())
		{
			return;
		}

		races.sort((a, b) -> a.damage() != b.damage() ? Double.compare(b.damage(), a.damage()) : Integer.compare(b.monsters(), a.monsters()));
		appendSection(sb, "Slayer <font color=\"" + COLOR_MUTED + "\">- up to +" + BestiaryConfig.SLAYER_DAMAGE_BONUS[BestiaryConfig.SLAYER_DAMAGE_BONUS.length - 1] + "% damage when a race is all " + BestiaryConfig.TIER_NAMES[BestiaryConfig.TIER_NAMES.length - 1] + "</font>");
		sb.append("<table width=").append(WIDTH).append(" cellpadding=0 cellspacing=0><tr>");
		appendHeader(sb, "Race", SLAYER_COLUMNS[0], false);
		appendHeader(sb, "Found", SLAYER_COLUMNS[1], true);
		appendHeader(sb, "Damage", SLAYER_COLUMNS[2], true);
		appendHeader(sb, "Defense", SLAYER_COLUMNS[3], true);
		sb.append("</tr></table>");
		for (int i = 0; i < Math.min(SLAYER_SHOWN, races.size()); i++)
		{
			final BestiaryManager.Slayer slayer = races.get(i);
			sb.append("<table width=").append(WIDTH).append(" height=22 cellpadding=0 cellspacing=0").append((i % 2) == 0 ? " bgcolor=111111" : "").append("><tr>");
			sb.append("<td width=").append(SLAYER_COLUMNS[0]).append("><font color=\"").append(COLOR_TEXT).append("\">").append(raceName(slayer.race())).append("</font></td>");
			sb.append("<td width=").append(SLAYER_COLUMNS[1]).append(" align=center><font color=\"").append(COLOR_TEXT).append("\">").append(slayer.found()).append(" / ").append(slayer.monsters()).append("</font></td>");
			sb.append("<td width=").append(SLAYER_COLUMNS[2]).append(" align=center>").append(percent("+", slayer.damage())).append("</td>");
			sb.append("<td width=").append(SLAYER_COLUMNS[3]).append(" align=center>").append(percent("-", slayer.defense())).append("</td>");
			sb.append("</tr></table>");
		}
		if (races.size() > SLAYER_SHOWN)
		{
			appendNote(sb, "The other " + (races.size() - SLAYER_SHOWN) + " races are on the web page.");
		}
	}

	// ---------------------------------------------------------------------------------------------
	// Building blocks
	// ---------------------------------------------------------------------------------------------

	/** A monster on a line of its own (the client doesn't color a table row, only a table), with a button to track it. */
	private static void appendLine(StringBuilder sb, int index, NpcTemplate template, String middle, String right)
	{
		sb.append("<table width=").append(WIDTH).append(" height=26 cellpadding=0 cellspacing=0").append((index % 2) == 0 ? " bgcolor=111111" : "").append("><tr>");
		sb.append("<td width=").append(LIST_COLUMNS[0]).append("><font color=\"").append(COLOR_TEXT).append("\">").append(template.getName()).append("</font> <font color=\"").append(COLOR_MUTED).append("\">Lv. ").append(template.getLevel()).append("</font></td>");
		sb.append("<td width=").append(LIST_COLUMNS[1]).append(" align=center><font color=\"").append(COLOR_VALUE).append("\">").append(middle).append("</font></td>");
		sb.append("<td width=").append(LIST_COLUMNS[2]).append(" align=center><font color=\"").append(COLOR_TEXT).append("\">").append(right).append("</font></td>");
		sb.append("<td width=").append(LIST_COLUMNS[3]).append(" align=center>").append(button("Track", BYPASS + " track " + template.getId(), 60)).append("</td>");
		sb.append("</tr></table>");
	}

	private static void appendStat(StringBuilder sb, String label, String value)
	{
		sb.append("<td align=center><font color=\"").append(COLOR_MUTED).append("\">").append(label).append("</font><br1><font color=\"").append(COLOR_VALUE).append("\">").append(value).append("</font></td>");
	}

	private static void appendSection(StringBuilder sb, String title)
	{
		sb.append("<table width=").append(WIDTH).append("><tr><td><font color=\"").append(COLOR_SECTION).append("\">").append(title).append("</font></td></tr></table>");
		sb.append("<img src=\"L2UI.SquareGray\" width=").append(WIDTH).append(" height=1><br1>");
	}

	private static void appendHeader(StringBuilder sb, String text, int width, boolean center)
	{
		sb.append("<td width=").append(width).append(center ? " align=center" : "").append("><font color=\"").append(COLOR_MUTED).append("\">").append(text).append("</font></td>");
	}

	private static void appendNote(StringBuilder sb, String text)
	{
		sb.append("<table width=").append(WIDTH).append("><tr><td align=center><font color=\"").append(COLOR_MUTED).append("\">").append(text).append("</font></td></tr></table>");
	}

	private static String percent(String sign, double value)
	{
		return value >= 0.005 ? "<font color=\"" + COLOR_GOOD + "\">" + sign + String.format(Locale.US, "%.1f", value) + "%</font>" : "<font color=\"" + COLOR_MUTED + "\">-</font>";
	}

	/** @return kills the entry needs for its next tier */
	private static int remaining(NpcTemplate template, Map<Integer, BestiaryManager.Entry> entries)
	{
		final int kills = entries.get(template.getId()).kills();
		final int[] tiers = BestiaryManager.getTiers(template);
		final int tier = BestiaryManager.getTier(template, kills);
		return tier < tiers.length ? tiers[tier] - kills : Integer.MAX_VALUE;
	}

	/** "DARK_ELF" reads "Dark Elf", "BUG" reads "Insects". */
	private static String raceName(Race race)
	{
		final String name = RACE_NAMES.get(race);
		if (name != null)
		{
			return name;
		}

		final StringBuilder sb = new StringBuilder();
		for (String word : race.name().toLowerCase(Locale.ROOT).split("_"))
		{
			sb.append(sb.length() > 0 ? " " : "").append(Character.toUpperCase(word.charAt(0))).append(word.substring(1));
		}
		return sb.toString();
	}

	private static int parseInt(String value)
	{
		try
		{
			return Integer.parseInt(value.trim());
		}
		catch (NumberFormatException e)
		{
			return 0;
		}
	}

	private static String button(String text, String action, int width)
	{
		return "<button value=\"" + text + "\" action=\"bypass " + action + "\" width=" + width + " height=22 back=\"L2UI_CT1.Button_DF_Down\" fore=\"L2UI_CT1.Button_DF\">";
	}
}
