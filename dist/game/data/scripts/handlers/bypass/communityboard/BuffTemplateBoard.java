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
import org.l2jmobius.gameserver.model.actor.Player;
import org.l2jmobius.gameserver.model.item.ItemTemplate;
import org.l2jmobius.gameserver.model.skill.Skill;

/**
 * Community Board page for buff templates: lists the character's template slots, lets it fill them with buffs from its own skill tree and applies a template to the character or its summon.<br>
 * The page only builds bypasses; every bypass is validated again by {@link BuffTemplateManager}, since Community Board bypasses are not bound to the HTML that was sent.
 */
public class BuffTemplateBoard implements IParseBoardHandler
{
	private static final String FRAME_PATH = "data/html/CommunityBoard/Custom/bufftemplate/main.html";
	private static final String NAVIGATION_PATH = "data/html/CommunityBoard/Custom/navigation.html";
	private static final int BUFFS_PER_PAGE = 8;
	
	private static final String[] COMMANDS =
	{
		"_bbsselfbuff"
	};
	
	@Override
	public String[] getCommandList()
	{
		return COMMANDS;
	}
	
	@Override
	public boolean onCommand(String command, Player player)
	{
		final BuffTemplateManager manager = BuffTemplateManager.getInstance();
		if (!manager.isEnabled())
		{
			player.sendMessage("Buff templates are disabled.");
			return false;
		}
		
		final String[] args = command.trim().split(" ");
		final String action = args[0];
		final int slot = parseInt(args, 1);
		final int page = Math.max(0, parseInt(args, 3));
		switch (action)
		{
			case "_bbsselfbuff_use":
			{
				manager.useTemplate(player, slot, (args.length > 2) && args[2].equals("summon"));
				showMain(player);
				break;
			}
			case "_bbsselfbuff_edit":
			{
				showEdit(player, slot, Math.max(0, parseInt(args, 2)));
				break;
			}
			case "_bbsselfbuff_add":
			{
				manager.addBuff(player, slot, parseInt(args, 2));
				showEdit(player, slot, page);
				break;
			}
			case "_bbsselfbuff_remove":
			{
				manager.removeBuff(player, slot, parseInt(args, 2));
				showEdit(player, slot, page);
				break;
			}
			case "_bbsselfbuff_rename":
			{
				final String prefix = action + " " + (args.length > 1 ? args[1] : "");
				final String name = command.trim().length() > prefix.length() ? command.trim().substring(prefix.length()) : "";
				manager.rename(player, slot, name);
				showEdit(player, slot, 0);
				break;
			}
			case "_bbsselfbuff_clear":
			{
				manager.clear(player, slot);
				showEdit(player, slot, 0);
				break;
			}
			default:
			{
				showMain(player);
				break;
			}
		}
		
		return true;
	}
	
	private void showMain(Player player)
	{
		final BuffTemplateManager manager = BuffTemplateManager.getInstance();
		final StringBuilder sb = new StringBuilder();
		appendTitle(sb, "Buff Templates");
		sb.append("<table width=520><tr><td align=center><font color=\"B09878\">Put buffs from your own skill tree into a template and apply them all at once (only the server's extended-duration buffs qualify).<br1>");
		sb.append("Each buff still costs its MP and items and starts its reuse, exactly as if you cast it.</font></td></tr></table><br>");
		
		final int price = CommunityBoardConfig.COMMUNITYBOARD_BUFF_TEMPLATE_PRICE;
		if (price > 0)
		{
			final ItemTemplate currency = ItemData.getInstance().getTemplate(CommunityBoardConfig.COMMUNITYBOARD_CURRENCY);
			sb.append("<font color=\"LEVEL\">Price per use: ").append(price).append(' ').append(currency != null ? currency.getName() : "").append("</font><br><br>");
		}
		
		sb.append("<table width=520 cellpadding=4>");
		for (int slot = 1; slot <= manager.getSlotCount(); slot++)
		{
			final BuffTemplate template = manager.getTemplate(player, slot);
			sb.append("<tr>");
			sb.append("<td width=190><font color=\"CDB67F\">").append(template.name()).append("</font><br1><font color=\"777777\">").append(template.skillIds().size()).append(" / ").append(manager.getMaxBuffs()).append(" buffs</font></td>");
			sb.append("<td width=110>").append(button("Buff Me", "_bbsselfbuff_use " + slot + " self", 100)).append("</td>");
			sb.append("<td width=110>").append(button("Buff Summon", "_bbsselfbuff_use " + slot + " summon", 100)).append("</td>");
			sb.append("<td width=110>").append(button("Edit", "_bbsselfbuff_edit " + slot + " 0", 100)).append("</td>");
			sb.append("</tr>");
		}
		sb.append("</table><br>");
		
		sb.append("<font color=\"777777\">Only usable out of combat");
		if (CommunityBoardConfig.COMMUNITYBOARD_BUFF_TEMPLATE_PEACE_ONLY)
		{
			sb.append(", inside a peace zone");
		}
		sb.append(". Buffs you no longer know, that are on reuse or that you cannot afford are skipped.</font>");
		
		send(player, sb.toString());
	}
	
	private void showEdit(Player player, int slot, int page)
	{
		final BuffTemplateManager manager = BuffTemplateManager.getInstance();
		if (!manager.isValidSlot(slot))
		{
			showMain(player);
			return;
		}
		
		final BuffTemplate template = manager.getTemplate(player, slot);
		final StringBuilder sb = new StringBuilder();
		appendTitle(sb, template.name() + " (" + template.skillIds().size() + " / " + manager.getMaxBuffs() + ")");
		
		// Rename / clear / back.
		sb.append("<table width=520><tr>");
		sb.append("<td width=160><edit var=\"tplname\" width=150 height=15 length=").append(BuffTemplateManager.MAX_NAME_LENGTH).append("></td>");
		sb.append("<td width=120>").append(button("Rename", "_bbsselfbuff_rename " + slot + " $tplname", 110)).append("</td>");
		sb.append("<td width=120>").append(button("Clear", "_bbsselfbuff_clear " + slot, 110)).append("</td>");
		sb.append("<td width=120>").append(button("Back", "_bbsselfbuff", 110)).append("</td>");
		sb.append("</tr></table><br>");
		
		// Buffs already in the template, in application order.
		sb.append("<font color=\"LEVEL\">Buffs in this template</font><br1>");
		if (template.skillIds().isEmpty())
		{
			sb.append("<font color=\"777777\">Empty - add buffs from the list below.</font><br>");
		}
		else
		{
			sb.append("<table width=520>");
			for (int skillId : template.skillIds())
			{
				final Skill known = player.getKnownSkill(skillId);
				final boolean usable = manager.isEligible(player, known);
				final Skill shown = known != null ? known : SkillData.getInstance().getSkill(skillId, 1);
				sb.append("<tr>");
				sb.append("<td width=40>").append(icon(shown)).append("</td>");
				sb.append("<td width=360>").append(usable ? "<font color=\"FFFFFF\">" : "<font color=\"777777\">").append(shown != null ? shown.getName() : "Unknown skill " + skillId).append("</font> ");
				sb.append(usable ? level(known) : "<font color=\"FF6060\">(not usable by this character)</font>").append("</td>");
				sb.append("<td width=120>").append(button("Remove", "_bbsselfbuff_remove " + slot + " " + skillId + " " + page, 100)).append("</td>");
				sb.append("</tr>");
			}
			sb.append("</table>");
		}
		sb.append("<br><img src=\"L2UI.SquareGray\" width=520 height=1><br>");
		
		// Buffs the character may still add.
		final List<Skill> available = new ArrayList<>();
		for (Skill skill : manager.getEligibleBuffs(player))
		{
			if (!template.skillIds().contains(skill.getId()))
			{
				available.add(skill);
			}
		}
		
		final int pageCount = Math.max(1, (available.size() + BUFFS_PER_PAGE - 1) / BUFFS_PER_PAGE);
		final int currentPage = Math.min(page, pageCount - 1);
		sb.append("<font color=\"LEVEL\">Your buffs</font><br1>");
		if (available.isEmpty())
		{
			sb.append("<font color=\"777777\">You have no other buffs that can be added to a template.</font><br>");
		}
		else
		{
			sb.append("<table width=520>");
			for (Skill skill : available.subList(currentPage * BUFFS_PER_PAGE, Math.min(available.size(), (currentPage + 1) * BUFFS_PER_PAGE)))
			{
				sb.append("<tr>");
				sb.append("<td width=40>").append(icon(skill)).append("</td>");
				sb.append("<td width=360>").append(skill.getName()).append(' ').append(level(skill)).append("</td>");
				sb.append("<td width=120>").append(button("Add", "_bbsselfbuff_add " + slot + " " + skill.getId() + " " + currentPage, 100)).append("</td>");
				sb.append("</tr>");
			}
			sb.append("</table>");
			
			if (pageCount > 1)
			{
				sb.append("<table width=300><tr>");
				sb.append("<td width=100 align=center>").append(currentPage > 0 ? button("Previous", "_bbsselfbuff_edit " + slot + " " + (currentPage - 1), 90) : "").append("</td>");
				sb.append("<td width=100 align=center>Page ").append(currentPage + 1).append(" / ").append(pageCount).append("</td>");
				sb.append("<td width=100 align=center>").append(currentPage < (pageCount - 1) ? button("Next", "_bbsselfbuff_edit " + slot + " " + (currentPage + 1), 90) : "").append("</td>");
				sb.append("</tr></table>");
			}
		}
		
		send(player, sb.toString());
	}
	
	private static void appendTitle(StringBuilder sb, String title)
	{
		sb.append("<font name=\"hs12\" color=\"CDB67F\">").append(title).append("</font><br1>");
		sb.append("<img src=\"L2UI.SquareGray\" width=200 height=1><br>");
	}
	
	private static String button(String value, String bypass, int width)
	{
		return "<button value=\"" + value + "\" action=\"bypass " + bypass + "\" width=" + width + " height=25 back=\"L2UI_CT1.Button_DF_Down\" fore=\"L2UI_CT1.Button_DF\">";
	}
	
	private static String icon(Skill skill)
	{
		return skill != null ? "<img src=\"" + skill.getIcon() + "\" width=32 height=32>" : "";
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
