package org.l2jmobius.gameserver.handler;

import java.util.ArrayList;
import java.util.List;

import org.l2jmobius.gameserver.config.custom.CustomBuffConfig;
import org.l2jmobius.gameserver.data.xml.ItemData;
import org.l2jmobius.gameserver.data.xml.SkillData;
import org.l2jmobius.gameserver.managers.AdventurersGuideManager;
import org.l2jmobius.gameserver.model.actor.Playable;
import org.l2jmobius.gameserver.model.actor.Player;
import org.l2jmobius.gameserver.model.guide.GuideLesson;
import org.l2jmobius.gameserver.model.item.ItemTemplate;
import org.l2jmobius.gameserver.model.item.enums.ItemProcessType;
import org.l2jmobius.gameserver.model.item.instance.Item;
import org.l2jmobius.gameserver.model.skill.Skill;
import org.l2jmobius.gameserver.network.serverpackets.ActionFailed;
import org.l2jmobius.gameserver.network.serverpackets.ShowBoard;
import org.l2jmobius.gameserver.util.HtmlUtil;

/**
 * The Champion Medal's Skill Master: double-clicking the medal opens it in the community board window.<br>
 * Every buff of {@link CustomBuffConfig#SKILLS} keeps its place on its page; the ones the player already knows are grayed out instead of removed, so learning one doesn't move the others.<br>
 * A medal always teaches the highest level, so a buff the player's class taught at a lower level can be improved to it (Upgrade) for one medal too.<br>
 * The window stays open after learning while the player has medals left, on the same page.
 */
public class CustomBuffBook implements IItemHandler
{
	private static final int COLUMNS = 2;
	private static final int ROWS = 7;
	private static final int PAGE_LIMIT = COLUMNS * ROWS;

	private static final String BYPASS = "custombuffbook";

	@Override
	public boolean onItemUse(Playable playable, Item item, boolean forceUse)
	{
		if (!playable.isPlayer())
		{
			return false;
		}

		showPage(playable.asPlayer(), 1);
		AdventurersGuideManager.getInstance().discover(playable.asPlayer(), GuideLesson.BUFFS);
		return true;
	}

	/**
	 * Teaches the player a buff for one medal, then shows the same page again, or closes the window when that was the last medal.
	 * @param player the player
	 * @param skillId the buff, which must be one of {@link CustomBuffConfig#SKILLS}
	 * @param page the page the player was on
	 */
	public static void learn(Player player, int skillId, int page)
	{
		if (!CustomBuffConfig.SKILLS.contains(skillId))
		{
			return;
		}

		final Skill skill = getSkill(skillId);
		if (skill == null)
		{
			return;
		}

		// A double click on Learn mustn't take a second medal for a buff already known.
		final Skill known = player.getKnownSkill(skillId);
		if (isLearned(known, skill))
		{
			player.sendMessage("You already know " + skill.getName() + ".");
			showPage(player, page);
			return;
		}

		if (!player.destroyItemByItemId(ItemProcessType.FEE, CustomBuffConfig.ITEM_ID, 1, player, true))
		{
			player.sendMessage("You need a " + getMedalName() + " to learn this skill.");
			closePage(player);
			return;
		}

		player.addSkill(skill, true);
		player.sendSkillList();
		if (known != null)
		{
			player.updateShortcuts(skillId, skill.getLevel());
			player.sendMessage("You improved " + skill.getName() + " to level " + skill.getLevel() + "!");
		}
		else
		{
			player.sendMessage("You successfully learned " + skill.getName() + "!");
		}

		if (getMedalCount(player) > 0)
		{
			showPage(player, page);
		}
		else
		{
			player.sendMessage("That was your last " + getMedalName() + ".");
			closePage(player);
		}
	}

	public static void showPage(Player player, int page)
	{
		// All the configured buffs, learned ones included: they keep their slot and only gray out.
		final List<Skill> skills = new ArrayList<>();
		int learned = 0;
		for (int skillId : CustomBuffConfig.SKILLS)
		{
			final Skill skill = getSkill(skillId);
			if (skill != null)
			{
				skills.add(skill);
				if (isLearned(player.getKnownSkill(skillId), skill))
				{
					learned++;
				}
			}
		}

		final int maxPages = Math.max(1, (skills.size() + PAGE_LIMIT - 1) / PAGE_LIMIT);
		final int currentPage = Math.max(1, Math.min(page, maxPages));
		final int start = (currentPage - 1) * PAGE_LIMIT;
		final int end = Math.min(start + PAGE_LIMIT, skills.size());
		final long medals = getMedalCount(player);

		final StringBuilder sb = new StringBuilder(16000);
		sb.append("<html><body><br><center>");

		// Title.
		sb.append("<table width=720 border=0 cellspacing=0 cellpadding=0>");
		sb.append("<tr><td height=25 align=center><font name=\"hs12\" color=\"CDB67F\">Skill Master</font></td></tr>");
		sb.append("<tr><td align=center><font color=\"A2A2A2\">Each ").append(getMedalName()).append(" teaches you one buff of another class for good.</font></td></tr>");
		sb.append("</table>");
		sb.append("<img src=\"L2UI.SquareGray\" width=720 height=1><br>");

		// Status bar: medals, progress and paging.
		// The widths add up to exactly 720 with no padding, otherwise the client stretches the columns past the window.
		sb.append("<table width=720 height=40 border=0 cellspacing=0 cellpadding=0 bgcolor=1A1A1A><tr>");
		sb.append("<td fixwidth=44 align=center><img src=\"").append(getMedalIcon()).append("\" width=32 height=32></td>");
		sb.append("<td fixwidth=196 align=left>Medals: <font color=\"").append(medals > 0 ? "00FF66" : "FF6666").append("\">").append(medals).append("</font></td>");
		sb.append("<td fixwidth=200 align=center>Learned: <font color=\"LEVEL\">").append(learned).append("</font> / ").append(skills.size()).append("</td>");
		sb.append("<td fixwidth=280 align=right>");
		appendPaging(sb, currentPage, maxPages);
		sb.append("</td></tr></table>");

		if (skills.isEmpty())
		{
			sb.append("<br><br><font color=\"A2A2A2\">There are no buffs to learn.</font>");
		}
		else
		{
			sb.append("<table width=720 border=0 cellspacing=0 cellpadding=0><tr><td fixwidth=720 align=center><font color=\"7F7F7F\">");
			if (learned == skills.size())
			{
				sb.append("<font color=\"00FF66\">Mastery achieved!</font> You have learned every buff.");
			}
			else
			{
				sb.append("Learned buffs stay in their place, grayed out. A buff you know at a lower level can be upgraded to the highest one.");
				sb.append("<br1>Every song or dance already in effect makes the next one cost more MP.");
			}
			sb.append("</font></td></tr></table>");

			// The buffs, two per row.
			// Two 356 wide cards with an 8 wide gap between them. Each row is its own table, so a long
			// description in one row cannot shift the columns of the others.
			for (int i = start; i < end; i += COLUMNS)
			{
				sb.append("<table width=720 border=0 cellspacing=0 cellpadding=0><tr>");
				for (int column = 0; column < COLUMNS; column++)
				{
					if (column > 0)
					{
						sb.append("<td fixwidth=8></td>");
					}
					sb.append("<td fixwidth=356 valign=top>");
					if ((i + column) < end)
					{
						appendSkill(sb, player, skills.get(i + column), medals, currentPage);
					}
					sb.append("</td>");
				}
				sb.append("</tr></table>");
				sb.append("<img src=\"L2UI.SquareBlank\" width=720 height=6>");
			}
		}

		sb.append("</center></body></html>");

		HtmlUtil.sendCBHtml(player, sb.toString());
		player.sendPacket(ActionFailed.STATIC_PACKET);
	}

	private static void appendSkill(StringBuilder sb, Player player, Skill skill, long medals, int page)
	{
		final Skill knownSkill = player.getKnownSkill(skill.getId());
		final boolean known = isLearned(knownSkill, skill);
		final boolean upgrade = (knownSkill != null) && !known;
		final String description = CustomBuffConfig.DESCRIPTIONS.get(skill.getId());

		String icon = skill.getIcon();
		if ((icon == null) || icon.isEmpty())
		{
			icon = "icon.skill0000";
		}

		sb.append("<table width=356 height=64 border=0 cellspacing=0 cellpadding=0 bgcolor=").append(known ? "0E0E0E" : "222222").append("><tr>");
		sb.append("<td fixwidth=44 align=center valign=center><img src=\"").append(icon).append("\" width=32 height=32></td>");
		sb.append("<td fixwidth=226 align=left valign=center>");
		sb.append("<font color=\"").append(known ? "6A6A6A" : "LEVEL").append("\">").append(skill.getName()).append("</font> ");
		if (upgrade)
		{
			sb.append("<font color=\"8A8A8A\">Lv ").append(knownSkill.getLevel()).append("</font> <font color=\"00FF66\">to Lv ").append(skill.getLevel()).append("</font>");
		}
		else
		{
			sb.append("<font color=\"").append(known ? "4A4A4A" : "8A8A8A").append("\">Lv ").append(skill.getLevel()).append("</font>");
		}
		if ((description != null) && !description.isEmpty())
		{
			sb.append("<br1><font color=\"").append(known ? "555555" : "B09B79").append("\">").append(description).append("</font>");
		}
		sb.append("</td>");
		sb.append("<td fixwidth=86 align=center valign=center>");
		if (known)
		{
			sb.append("<font color=\"5A5A5A\">Learned</font>");
		}
		else if (medals > 0)
		{
			sb.append("<button value=\"").append(upgrade ? "Upgrade" : "Learn").append("\" action=\"bypass ").append(BYPASS).append(" learn ").append(skill.getId()).append(" ").append(page).append("\" width=70 height=25 back=\"L2UI_CT1.Button_DF_Down\" fore=\"L2UI_CT1.Button_DF\">");
		}
		else
		{
			sb.append("<font color=\"7F5050\">No medals</font>");
		}
		sb.append("</td></tr></table>");
	}

	private static void appendPaging(StringBuilder sb, int page, int maxPages)
	{
		sb.append("<table border=0 cellspacing=0 cellpadding=0><tr>");
		sb.append("<td fixwidth=60 align=center>");
		if (page > 1)
		{
			sb.append("<button value=\"Prev\" action=\"bypass ").append(BYPASS).append(" ").append(page - 1).append("\" width=55 height=22 back=\"L2UI_CT1.Button_DF_Down\" fore=\"L2UI_CT1.Button_DF\">");
		}
		sb.append("</td>");
		for (int i = 1; i <= maxPages; i++)
		{
			sb.append("<td fixwidth=18 align=center>");
			if (i == page)
			{
				sb.append("<font color=\"LEVEL\">").append(i).append("</font>");
			}
			else
			{
				sb.append("<a action=\"bypass ").append(BYPASS).append(" ").append(i).append("\">").append(i).append("</a>");
			}
			sb.append("</td>");
		}
		sb.append("<td fixwidth=60 align=center>");
		if (page < maxPages)
		{
			sb.append("<button value=\"Next\" action=\"bypass ").append(BYPASS).append(" ").append(page + 1).append("\" width=55 height=22 back=\"L2UI_CT1.Button_DF_Down\" fore=\"L2UI_CT1.Button_DF\">");
		}
		sb.append("</td></tr></table>");
	}

	/**
	 * @param known the player's own level of the buff, or {@code null}
	 * @param taught the level a medal teaches
	 * @return {@code true} when a medal has nothing left to teach: the player knows the buff at that level, or enchanted
	 */
	private static boolean isLearned(Skill known, Skill taught)
	{
		return (known != null) && (known.getLevel() >= taught.getLevel());
	}
	
	private static void closePage(Player player)
	{
		player.sendPacket(new ShowBoard());
		player.sendPacket(ActionFailed.STATIC_PACKET);
	}

	/**
	 * @param skillId the buff
	 * @return the level a medal teaches: the highest one, or {@code null} for an unknown skill id
	 */
	private static Skill getSkill(int skillId)
	{
		return SkillData.getInstance().getSkill(skillId, SkillData.getInstance().getMaxLevel(skillId));
	}

	private static long getMedalCount(Player player)
	{
		return player.getInventory().getInventoryItemCount(CustomBuffConfig.ITEM_ID, -1);
	}

	private static String getMedalName()
	{
		final ItemTemplate template = ItemData.getInstance().getTemplate(CustomBuffConfig.ITEM_ID);
		return template != null ? template.getName() : "medal";
	}

	private static String getMedalIcon()
	{
		final ItemTemplate template = ItemData.getInstance().getTemplate(CustomBuffConfig.ITEM_ID);
		return (template != null) && (template.getIcon() != null) ? template.getIcon() : "icon.skill0000";
	}
}
