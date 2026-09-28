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
package handlers.bypass.communityboard;

import java.util.ArrayList;
import java.util.List;

import org.l2jmobius.gameserver.cache.HtmCache;
import org.l2jmobius.gameserver.config.custom.CommunityBoardConfig;
import org.l2jmobius.gameserver.data.xml.ItemData;
import org.l2jmobius.gameserver.data.xml.SkillData;
import org.l2jmobius.gameserver.handler.CommunityBoardHandler;
import org.l2jmobius.gameserver.handler.IParseBoardHandler;
import org.l2jmobius.gameserver.managers.BuffTemplateManager;
import org.l2jmobius.gameserver.managers.BuffTemplateManager.BuffTemplate;
import org.l2jmobius.gameserver.managers.BuffTemplateManager.UseResult;
import org.l2jmobius.gameserver.model.actor.Player;
import org.l2jmobius.gameserver.model.item.ItemTemplate;
import org.l2jmobius.gameserver.model.skill.Skill;

/**
 * Community Board page for buff templates: lists the character's template slots, lets it fill them with buffs from its own skill tree and applies a template to the character or its summon.<br>
 * The client resets the scroll position whenever a Community Board page is redrawn, so every page is laid out to fit the window without scrolling: the editor shows the template and the available buffs side by side, each paged separately, and every action returns to the same pages.<br>
 * The page only builds bypasses; every bypass is validated again by {@link BuffTemplateManager}, since Community Board bypasses are not bound to the HTML that was sent.
 */
public class BuffTemplateBoard implements IParseBoardHandler
{
	private static final String FRAME_PATH = "data/html/CommunityBoard/Custom/bufftemplate/main.html";
	private static final String NAVIGATION_PATH = "data/html/CommunityBoard/Custom/navigation.html";
	
	/** Rows per editor column; sized so the whole editor fits the board window without a scroll bar. */
	private static final int ROWS_PER_PAGE = 8;
	/** Longest skill name shown before it is shortened, so a row never wraps onto a second line. */
	private static final int MAX_NAME_CHARS = 24;
	/** Icons previewed per template on the main page. */
	private static final int PREVIEW_ICONS = 16;
	
	private static final String COLOR_OK = "55FF55";
	private static final String COLOR_ERROR = "FF6060";
	
	private static final String[] COMMANDS =
	{
		"_bbsselfbuff"
	};
	
	@Override
	public String[] getCommandList()
	{
		return COMMANDS;
	}
	
	/**
	 * Bypasses:
	 * <ul>
	 * <li>_bbsselfbuff - main page</li>
	 * <li>_bbsselfbuff_use slot self|summon</li>
	 * <li>_bbsselfbuff_edit slot templatePage availablePage</li>
	 * <li>_bbsselfbuff_add slot skillId templatePage availablePage</li>
	 * <li>_bbsselfbuff_remove slot skillId templatePage availablePage</li>
	 * <li>_bbsselfbuff_addall slot templatePage availablePage</li>
	 * <li>_bbsselfbuff_clear slot</li>
	 * <li>_bbsselfbuff_rename slot templatePage availablePage name...</li>
	 * </ul>
	 */
	@Override
	public boolean onCommand(String command, Player player)
	{
		final BuffTemplateManager manager = BuffTemplateManager.getInstance();
		if (!manager.isEnabled())
		{
			player.sendMessage("Buff templates are disabled.");
			return false;
		}
		
		final String[] args = command.trim().split(" +");
		final String action = args[0];
		final int slot = parseInt(args, 1);
		switch (action)
		{
			case "_bbsselfbuff_use":
			{
				final boolean onSummon = (args.length > 2) && args[2].equals("summon");
				final UseResult result = manager.useTemplate(player, slot, onSummon);
				showMain(player, result.message(), result.applied() ? COLOR_OK : COLOR_ERROR);
				break;
			}
			case "_bbsselfbuff_edit":
			{
				showEdit(player, slot, parseInt(args, 2), parseInt(args, 3), null, null);
				break;
			}
			case "_bbsselfbuff_add":
			{
				final String error = manager.addBuff(player, slot, parseInt(args, 2));
				showEdit(player, slot, parseInt(args, 3), parseInt(args, 4), error, COLOR_ERROR);
				break;
			}
			case "_bbsselfbuff_remove":
			{
				manager.removeBuff(player, slot, parseInt(args, 2));
				showEdit(player, slot, parseInt(args, 3), parseInt(args, 4), null, null);
				break;
			}
			case "_bbsselfbuff_addall":
			{
				final int added = manager.addAllBuffs(player, slot);
				showEdit(player, slot, parseInt(args, 2), parseInt(args, 3), added > 0 ? "Added " + added + " buff(s)." : "Nothing to add.", added > 0 ? COLOR_OK : COLOR_ERROR);
				break;
			}
			case "_bbsselfbuff_clear":
			{
				manager.clear(player, slot);
				showEdit(player, slot, 0, 0, "Template cleared.", COLOR_OK);
				break;
			}
			case "_bbsselfbuff_rename":
			{
				// The name is everything after the fourth token and may contain spaces.
				String name = "";
				final String trimmed = command.trim();
				int index = 0;
				for (int i = 0; (i < 4) && (index >= 0); i++)
				{
					index = trimmed.indexOf(' ', index + 1);
				}
				
				if (index > 0)
				{
					name = trimmed.substring(index + 1);
				}
				
				final String error = manager.rename(player, slot, name);
				showEdit(player, slot, parseInt(args, 2), parseInt(args, 3), error != null ? error : "Template renamed.", error != null ? COLOR_ERROR : COLOR_OK);
				break;
			}
			default:
			{
				showMain(player, null, null);
				break;
			}
		}
		
		return true;
	}
	
	private void showMain(Player player, String status, String statusColor)
	{
		final BuffTemplateManager manager = BuffTemplateManager.getInstance();
		final StringBuilder sb = new StringBuilder();
		appendTitle(sb, "Buff Templates");
		sb.append("<table width=520><tr><td align=center><font color=\"B09878\">Put buffs from your own skill tree into a template and apply them all at once, instantly.<br1>");
		sb.append("Only the server's extended-duration buffs qualify.");
		final int price = CommunityBoardConfig.COMMUNITYBOARD_BUFF_TEMPLATE_PRICE;
		if (price > 0)
		{
			final ItemTemplate currency = ItemData.getInstance().getTemplate(CommunityBoardConfig.COMMUNITYBOARD_CURRENCY);
			sb.append(" Price per use: ").append(price).append(' ').append(currency != null ? currency.getName() : "").append('.');
		}
		sb.append("</font></td></tr></table>");
		appendStatus(sb, status, statusColor);
		
		for (int slot = 1; slot <= manager.getSlotCount(); slot++)
		{
			final BuffTemplate template = manager.getTemplate(player, slot);
			sb.append("<table width=520 cellspacing=0 cellpadding=2 bgcolor=\"000000\">");
			sb.append("<tr>");
			sb.append("<td width=190><font color=\"CDB67F\">").append(template.name()).append("</font> <font color=\"777777\">(").append(template.skillIds().size()).append(" / ").append(manager.getMaxBuffs()).append(")</font></td>");
			sb.append("<td width=110>").append(button("Buff Me", "_bbsselfbuff_use " + slot + " self", 100, 25)).append("</td>");
			sb.append("<td width=110>").append(button("Buff Summon", "_bbsselfbuff_use " + slot + " summon", 100, 25)).append("</td>");
			sb.append("<td width=110>").append(button("Edit", "_bbsselfbuff_edit " + slot + " 0 0", 100, 25)).append("</td>");
			sb.append("</tr>");
			sb.append("</table>");
			
			// Icon preview of the template's buffs.
			sb.append("<table width=520 cellspacing=0 cellpadding=1><tr>");
			int shown = 0;
			for (int skillId : template.skillIds())
			{
				if (shown >= PREVIEW_ICONS)
				{
					break;
				}
				
				final Skill skill = displaySkill(player, skillId);
				if (skill != null)
				{
					sb.append("<td width=26><img src=\"").append(skill.getIcon()).append("\" width=24 height=24></td>");
					shown++;
				}
			}
			if (shown == 0)
			{
				sb.append("<td height=26><font color=\"777777\">Empty - press Edit to add buffs.</font></td>");
			}
			else if (template.skillIds().size() > shown)
			{
				sb.append("<td><font color=\"777777\">+").append(template.skillIds().size() - shown).append("</font></td>");
			}
			sb.append("</tr></table><br>");
		}
		
		sb.append("<font color=\"777777\">Usable out of combat");
		if (CommunityBoardConfig.COMMUNITYBOARD_BUFF_TEMPLATE_PEACE_ONLY)
		{
			sb.append(", inside a peace zone");
		}
		sb.append(". No MP, items or reuse needed.</font>");
		
		send(player, sb.toString());
	}
	
	private void showEdit(Player player, int slot, int templatePage, int availablePage, String status, String statusColor)
	{
		final BuffTemplateManager manager = BuffTemplateManager.getInstance();
		if (!manager.isValidSlot(slot))
		{
			showMain(player, null, null);
			return;
		}
		
		final BuffTemplate template = manager.getTemplate(player, slot);
		final List<Integer> inTemplate = template.skillIds();
		final List<Skill> available = new ArrayList<>();
		for (Skill skill : manager.getEligibleBuffs(player))
		{
			if (!inTemplate.contains(skill.getId()))
			{
				available.add(skill);
			}
		}
		
		// Clamp both pages, e.g. after the last entry of a page was added or removed.
		final int tPages = pageCount(inTemplate.size());
		final int aPages = pageCount(available.size());
		final int tp = Math.max(0, Math.min(templatePage, tPages - 1));
		final int ap = Math.max(0, Math.min(availablePage, aPages - 1));
		final String pages = " " + tp + " " + ap;
		
		final StringBuilder sb = new StringBuilder();
		appendTitle(sb, "Edit: " + template.name());
		
		// Rename + back.
		sb.append("<table width=540><tr>");
		sb.append("<td width=160><edit var=\"tplname\" width=150 height=15 length=").append(BuffTemplateManager.MAX_NAME_LENGTH).append("></td>");
		sb.append("<td width=90>").append(button("Rename", "_bbsselfbuff_rename " + slot + pages + " $tplname", 85, 22)).append("</td>");
		sb.append("<td width=200></td>");
		sb.append("<td width=90>").append(button("Back", "_bbsselfbuff", 85, 22)).append("</td>");
		sb.append("</tr></table>");
		appendStatus(sb, status, statusColor);
		
		// Two columns: template (remove) | available (add).
		sb.append("<table width=540 cellspacing=0 cellpadding=0><tr>");
		
		// Left column.
		sb.append("<td width=270 valign=top>");
		sb.append("<table width=266 cellspacing=0 cellpadding=1 bgcolor=\"000000\"><tr>");
		sb.append("<td width=176><font color=\"LEVEL\">In template</font> <font color=\"777777\">").append(inTemplate.size()).append(" / ").append(manager.getMaxBuffs()).append("</font></td>");
		sb.append("<td width=90 align=right>").append(inTemplate.isEmpty() ? "" : button("Remove All", "_bbsselfbuff_clear " + slot, 85, 20)).append("</td>");
		sb.append("</tr></table>");
		sb.append("<table width=266 cellspacing=0 cellpadding=1>");
		if (inTemplate.isEmpty())
		{
			sb.append("<tr><td height=36><font color=\"777777\">Empty - add buffs from the right.</font></td></tr>");
		}
		for (int skillId : inTemplate.subList(tp * ROWS_PER_PAGE, Math.min(inTemplate.size(), (tp + 1) * ROWS_PER_PAGE)))
		{
			final Skill known = player.getKnownSkill(skillId);
			final boolean usable = manager.isEligible(player, known);
			final Skill shown = displaySkill(player, skillId);
			final String name = shown != null ? shown.getName() : "Skill " + skillId;
			final String detail = usable ? level(known) : "<font color=\"" + COLOR_ERROR + "\">not usable by this character</font>";
			appendRow(sb, shown, name, detail, usable, button("Remove", "_bbsselfbuff_remove " + slot + " " + skillId + pages, 60, 22));
		}
		sb.append("</table>");
		appendPager(sb, tp, tPages, "_bbsselfbuff_edit " + slot + " %d " + ap);
		sb.append("</td>");
		
		// Right column.
		sb.append("<td width=270 valign=top>");
		sb.append("<table width=266 cellspacing=0 cellpadding=1 bgcolor=\"000000\"><tr>");
		sb.append("<td width=176><font color=\"LEVEL\">Your buffs</font> <font color=\"777777\">").append(available.size()).append(" more</font></td>");
		sb.append("<td width=90 align=right>").append(available.isEmpty() ? "" : button("Add All", "_bbsselfbuff_addall " + slot + pages, 85, 20)).append("</td>");
		sb.append("</tr></table>");
		sb.append("<table width=266 cellspacing=0 cellpadding=1>");
		if (available.isEmpty())
		{
			sb.append("<tr><td height=36><font color=\"777777\">No other buffs you know can be added.</font></td></tr>");
		}
		for (Skill skill : available.subList(ap * ROWS_PER_PAGE, Math.min(available.size(), (ap + 1) * ROWS_PER_PAGE)))
		{
			appendRow(sb, skill, skill.getName(), level(skill), true, button("Add", "_bbsselfbuff_add " + slot + " " + skill.getId() + pages, 60, 22));
		}
		sb.append("</table>");
		appendPager(sb, ap, aPages, "_bbsselfbuff_edit " + slot + " " + tp + " %d");
		sb.append("</td>");
		
		sb.append("</tr></table>");
		send(player, sb.toString());
	}
	
	private static void appendRow(StringBuilder sb, Skill icon, String name, String detail, boolean usable, String action)
	{
		final String shortName = name.length() > MAX_NAME_CHARS ? name.substring(0, MAX_NAME_CHARS - 2) + ".." : name;
		sb.append("<tr>");
		sb.append("<td width=36 height=36>").append(icon != null ? "<img src=\"" + icon.getIcon() + "\" width=32 height=32>" : "").append("</td>");
		sb.append("<td width=166><font color=\"").append(usable ? "FFFFFF" : "777777").append("\">").append(shortName).append("</font><br1>").append(detail).append("</td>");
		sb.append("<td width=64>").append(action).append("</td>");
		sb.append("</tr>");
	}
	
	/**
	 * @param sb the page
	 * @param page current page, 0-based
	 * @param pageCount total pages
	 * @param bypass bypass with a %d placeholder for the target page
	 */
	private static void appendPager(StringBuilder sb, int page, int pageCount, String bypass)
	{
		if (pageCount <= 1)
		{
			return;
		}
		
		sb.append("<table width=266 cellspacing=0 cellpadding=2><tr>");
		sb.append("<td width=90 align=center>").append(page > 0 ? button("Prev", String.format(bypass, page - 1), 70, 20) : "").append("</td>");
		sb.append("<td width=86 align=center><font color=\"B09878\">").append(page + 1).append(" / ").append(pageCount).append("</font></td>");
		sb.append("<td width=90 align=center>").append(page < (pageCount - 1) ? button("Next", String.format(bypass, page + 1), 70, 20) : "").append("</td>");
		sb.append("</tr></table>");
	}
	
	private static void appendStatus(StringBuilder sb, String status, String color)
	{
		sb.append("<table width=520><tr><td height=22 align=center>");
		if (status != null)
		{
			sb.append("<font color=\"").append(color).append("\">").append(status).append("</font>");
		}
		sb.append("</td></tr></table>");
	}
	
	private static void appendTitle(StringBuilder sb, String title)
	{
		sb.append("<font name=\"hs12\" color=\"CDB67F\">").append(title).append("</font><br1>");
		sb.append("<img src=\"L2UI.SquareGray\" width=200 height=1>");
	}
	
	/**
	 * @param player the owner
	 * @param skillId the skill
	 * @return the skill as the player knows it, or its first level for display when it is not known
	 */
	private static Skill displaySkill(Player player, int skillId)
	{
		final Skill known = player.getKnownSkill(skillId);
		return known != null ? known : SkillData.getInstance().getSkill(skillId, 1);
	}
	
	private static int pageCount(int size)
	{
		return Math.max(1, (size + ROWS_PER_PAGE - 1) / ROWS_PER_PAGE);
	}
	
	private static String button(String value, String bypass, int width, int height)
	{
		return "<button value=\"" + value + "\" action=\"bypass " + bypass + "\" width=" + width + " height=" + height + " back=\"L2UI_CT1.Button_DF_Down\" fore=\"L2UI_CT1.Button_DF\">";
	}
	
	private static String level(Skill skill)
	{
		if (skill.getLevel() > 100)
		{
			return "<font color=\"777777\">Lv. " + SkillData.getInstance().getMaxLevel(skill.getId()) + "</font> <font color=\"LEVEL\">+" + (skill.getLevel() % 100) + "</font>";
		}
		
		return "<font color=\"777777\">Lv. " + skill.getLevel() + "</font>";
	}
	
	private static void send(Player player, String content)
	{
		String html = HtmCache.getInstance().getHtm(player, FRAME_PATH);
		if (html == null)
		{
			html = "<html><body>%navigation%<br><center>%content%</center></body></html>";
		}
		
		final String navigation = CommunityBoardConfig.CUSTOM_CB_ENABLED ? HtmCache.getInstance().getHtm(player, NAVIGATION_PATH) : null;
		html = html.replace("%navigation%", navigation != null ? navigation : "");
		html = html.replace("%content%", content);
		CommunityBoardHandler.separateAndSend(html, player);
	}
	
	private static int parseInt(String[] args, int index)
	{
		if (index >= args.length)
		{
			return 0;
		}
		
		try
		{
			return Integer.parseInt(args[index]);
		}
		catch (NumberFormatException e)
		{
			return 0;
		}
	}
}
