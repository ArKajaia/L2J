package org.l2jmobius.gameserver.handler;

import java.util.ArrayList;
import java.util.List;

import org.l2jmobius.gameserver.config.custom.CustomBuffConfig;
import org.l2jmobius.gameserver.data.xml.SkillData;
import org.l2jmobius.gameserver.model.actor.Playable;
import org.l2jmobius.gameserver.model.actor.Player;
import org.l2jmobius.gameserver.model.item.instance.Item;
import org.l2jmobius.gameserver.model.skill.Skill;
import org.l2jmobius.gameserver.network.serverpackets.ActionFailed;
import org.l2jmobius.gameserver.network.serverpackets.NpcHtmlMessage;

public class CustomBuffBook implements IItemHandler
{
	private static final int PAGE_LIMIT = 15; // Max items per page
	
	@Override
	public boolean onItemUse(Playable playable, Item item, boolean forceUse)
	{
		if (!playable.isPlayer())
		{
			return false;
		}
		
		showPage(playable.asPlayer(), item, 1);
		return true;
	}
	
	public static void showPage(Player player, Item item, int page)
	{
		final int itemObjId = (item != null) ? item.getObjectId() : 0;
		showPage(player, itemObjId, page);
	}
	
	public static void showPage(Player player, int itemObjId, int page)
	{
		// Retrieve item from inventory if called from bypass with objectId
		final Item item = (itemObjId > 0) ? player.getInventory().getItemByObjectId(itemObjId) : null;
		
		// 1. Gather all unlearned skills
		List<Skill> unlearnedSkills = new ArrayList<>();
		
		for (Object skillObj : CustomBuffConfig.SKILLS)
		{
			if (skillObj == null)
			{
				continue;
			}
			
			int skillId;
			if (skillObj instanceof Number)
			{
				skillId = ((Number) skillObj).intValue();
			}
			else
			{
				try
				{
					skillId = Integer.parseInt(skillObj.toString().trim());
				}
				catch (NumberFormatException e)
				{
					continue;
				}
			}
			
			final int maxLevel = SkillData.getInstance().getMaxLevel(skillId);
			final Skill skill = SkillData.getInstance().getSkill(skillId, maxLevel);
			
			if ((skill != null) && (player.getKnownSkill(skillId) == null))
			{
				unlearnedSkills.add(skill);
			}
		}
		
		// 2. Calculate Pages
		int totalSkills = unlearnedSkills.size();
		int maxPages = Math.max(1, (int) Math.ceil((double) totalSkills / PAGE_LIMIT));
		page = Math.max(1, Math.min(page, maxPages));
		
		int start = (page - 1) * PAGE_LIMIT;
		int end = Math.min(start + PAGE_LIMIT, totalSkills);
		
		// 3. Build HTML Window
		final NpcHtmlMessage html = new NpcHtmlMessage(0);
		final StringBuilder sb = new StringBuilder();
		
		sb.append("<html><body><center>");
		
		// Header
		sb.append("<table width=270 height=25 bgcolor=000000>");
		sb.append("<tr><td align=center><font color=\"LEVEL\"><b>SKILL MASTER</b></font></td></tr>");
		sb.append("</table><br>");
		
		if (item != null)
		{
			sb.append("<font color=\"LEVEL\">Coins Available:</font> <font color=\"00FFFF\"><b>").append(item.getCount()).append("</b></font><br>");
		}
		
		if (totalSkills == 0)
		{
			sb.append("<br><br><font color=\"00FF00\"><b>Mastery Achieved!</b></font><br>");
			sb.append("<font color=\"AAAAAA\">You have learned all custom skills.</font>");
		}
		else
		{
			sb.append("<table width=270 border=0 cellspacing=2 cellpadding=2 bgcolor=111111>");
			
			// Render skills for current page without descriptions
			for (int i = start; i < end; i++)
			{
				Skill skill = unlearnedSkills.get(i);
				int skillId = skill.getId();
				
				String icon = skill.getIcon();
				if ((icon == null) || icon.isEmpty())
				{
					icon = "icon.skill0000";
				}
				
				sb.append("<tr>");
				// Icon
				sb.append("<td width=35 align=center valign=middle>");
				sb.append("<img src=\"").append(icon).append("\" width=32 height=32>");
				sb.append("</td>");
				// Clean Skill Name & Level (No descriptions/modes)
				sb.append("<td width=155 align=left valign=middle>");
				sb.append("<font color=\"LEVEL\"><b>").append(skill.getName()).append("</b></font> <font color=\"A2A2A2\">Lvl ").append(skill.getLevel()).append("</font>");
				sb.append("</td>");
				// Learn Button
				sb.append("<td width=80 align=right valign=middle>");
				sb.append("<button value=\"Learn\" action=\"bypass -h LearnCustomBuff_").append(skillId).append("\" width=65 height=22 back=\"L2UI_CT1.Button_DF_Down\" fore=\"L2UI_CT1.Button_DF\">");
				sb.append("</td>");
				sb.append("</tr>");
				sb.append("<tr><td colspan=3 height=1 bgcolor=222222></td></tr>");
			}
			
			sb.append("</table><br>");
			
			// Page Navigation Buttons (Includes -h and item objectId)
			sb.append("<table width=270><tr>");
			sb.append("<td align=left width=80>");
			if (page > 1)
			{
				sb.append("<button value=\"Prev\" action=\"bypass -h custombuffbook ").append(page - 1).append(" ").append(itemObjId).append("\" width=65 height=20 back=\"L2UI_CT1.Button_DF_Down\" fore=\"L2UI_CT1.Button_DF\">");
			}
			sb.append("</td>");
			
			sb.append("<td align=center width=110><font color=\"A2A2A2\">Page ").append(page).append(" / ").append(maxPages).append("</font></td>");
			
			sb.append("<td align=right width=80>");
			if (page < maxPages)
			{
				sb.append("<button value=\"Next\" action=\"bypass -h custombuffbook ").append(page + 1).append(" ").append(itemObjId).append("\" width=65 height=20 back=\"L2UI_CT1.Button_DF_Down\" fore=\"L2UI_CT1.Button_DF\">");
			}
			sb.append("</td>");
			sb.append("</tr></table>");
		}
		
		sb.append("</center></body></html>");
		
		html.setHtml(sb.toString());
		player.sendPacket(html);
		player.sendPacket(ActionFailed.STATIC_PACKET);
	}
	
}