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
package handlers.items;

import java.util.ArrayList;
import java.util.List;

import org.l2jmobius.gameserver.data.xml.ItemData;
import org.l2jmobius.gameserver.data.xml.OptionData;
import org.l2jmobius.gameserver.handler.IItemHandler;
import org.l2jmobius.gameserver.model.actor.Playable;
import org.l2jmobius.gameserver.model.actor.Player;
import org.l2jmobius.gameserver.model.item.ItemTemplate;
import org.l2jmobius.gameserver.model.item.instance.Item;
import org.l2jmobius.gameserver.model.options.Augmentation;
import org.l2jmobius.gameserver.model.options.OptionSkillHolder;
import org.l2jmobius.gameserver.model.options.Options;
import org.l2jmobius.gameserver.network.SystemMessageId;
import org.l2jmobius.gameserver.network.clientpackets.AbstractRefinePacket;
import org.l2jmobius.gameserver.network.serverpackets.ActionFailed;
import org.l2jmobius.gameserver.network.serverpackets.NpcHtmlMessage;

/**
 * Using a Life Stone opens a list of the weapons/accessories it can augment, already augmented ones included.<br>
 * Selecting one shows what the augmentation costs and asks for confirmation; confirming takes the Life Stone, the Gemstones and, for an already augmented item, the augmentation removal fee straight from the inventory, and replaces the old augmentation with a new one in one action
 * (see handlers.bypass.npc.LifeStoneAugment).
 */
public class LifeStone implements IItemHandler
{
	public static final String BYPASS = "lifestone_augment";
	public static final String CONFIRM = "confirm";
	private static final int PAGE_LIMIT = 6;
	private static final String GREEN = "88CC88";
	private static final String RED = "CC6666";
	private static final String GREY = "B09878";
	
	@Override
	public boolean onItemUse(Playable playable, Item item, boolean forceUse)
	{
		if (!playable.isPlayer())
		{
			playable.sendPacket(SystemMessageId.YOUR_PET_CANNOT_CARRY_THIS_ITEM);
			return false;
		}
		
		final Player player = playable.asPlayer();
		if (player.isCastingNow() || !AbstractRefinePacket.canAugment(player))
		{
			return false;
		}
		
		// Don't open an empty list: only when there is something this life stone can augment.
		if (!AbstractRefinePacket.hasAugmentableItem(player, item))
		{
			player.sendPacket(SystemMessageId.THIS_IS_NOT_A_SUITABLE_ITEM);
			return false;
		}
		
		showList(player, item, 1, null);
		return true;
	}
	
	/**
	 * Shows the items the life stone can augment, with what each one costs.
	 * @param player the player
	 * @param lifeStone the life stone, {@code null} if it was used up
	 * @param page the page to show
	 * @param notice a result line shown on top (already colored), or {@code null}
	 */
	public static void showList(Player player, Item lifeStone, int page, String notice)
	{
		final StringBuilder sb = start(notice);
		if (!appendLifeStone(sb, lifeStone))
		{
			finish(player, sb);
			return;
		}
		
		final List<Item> targets = new ArrayList<>();
		for (Item item : player.getInventory().getItems())
		{
			if (AbstractRefinePacket.isAugmentableWith(player, item, lifeStone))
			{
				targets.add(item);
			}
		}
		
		if (targets.isEmpty())
		{
			sb.append("<font color=\"").append(GREY).append("\">Nothing can be augmented with this Life Stone.</font>");
			finish(player, sb);
			return;
		}
		
		final int maxPages = Math.max(1, (targets.size() + PAGE_LIMIT - 1) / PAGE_LIMIT);
		final int currentPage = Math.max(1, Math.min(page, maxPages));
		sb.append("<font color=\"").append(GREY).append("\">Choose the equipment to augment:</font><br>");
		sb.append("<table width=280>");
		for (int i = (currentPage - 1) * PAGE_LIMIT; i < Math.min(currentPage * PAGE_LIMIT, targets.size()); i++)
		{
			final Item target = targets.get(i);
			final int gemStoneId = AbstractRefinePacket.getRequiredGemStoneId(target);
			final ItemTemplate gemStone = ItemData.getInstance().getTemplate(gemStoneId);
			
			sb.append("<tr>");
			sb.append("<td width=36 height=40><img src=\"").append(target.getTemplate().getIcon()).append("\" width=32 height=32></td>");
			sb.append("<td width=174>").append(itemName(target));
			sb.append("<br1><font color=\"").append(GREY).append("\">").append(gemStone == null ? "Gemstones" : gemStone.getName()).append(" x").append(AbstractRefinePacket.getRequiredGemStoneCount(target, lifeStone));
			if (target.isAugmented())
			{
				sb.append(" + ").append(format(AbstractRefinePacket.getAugmentRemovalPrice(target))).append(" Adena</font>");
				sb.append("<br1><font color=\"").append(RED).append("\">Already augmented</font>");
			}
			else
			{
				sb.append("</font>");
			}
			sb.append("</td>");
			sb.append("<td width=70>").append(button("Select", lifeStone.getObjectId() + " " + currentPage + " " + target.getObjectId())).append("</td>");
			sb.append("</tr>");
		}
		sb.append("</table>");
		
		if (maxPages > 1)
		{
			sb.append("<br><table width=280><tr><td width=90 align=center>");
			if (currentPage > 1)
			{
				sb.append(button("Prev", lifeStone.getObjectId() + " " + (currentPage - 1)));
			}
			sb.append("</td><td width=100 align=center>Page ").append(currentPage).append(" / ").append(maxPages).append("</td><td width=90 align=center>");
			if (currentPage < maxPages)
			{
				sb.append(button("Next", lifeStone.getObjectId() + " " + (currentPage + 1)));
			}
			sb.append("</td></tr></table>");
		}
		
		finish(player, sb);
	}
	
	/**
	 * Shows the equipment placed for augmentation: what it costs, its current augmentation with a warning if it has one, and the button to confirm.
	 * @param player the player
	 * @param lifeStone the life stone, {@code null} if it was used up
	 * @param page the list page to go back to
	 * @param target the equipment to augment
	 * @param notice a result line shown on top (already colored), or {@code null}
	 */
	public static void showItem(Player player, Item lifeStone, int page, Item target, String notice)
	{
		final StringBuilder sb = start(notice);
		if (!appendLifeStone(sb, lifeStone))
		{
			finish(player, sb);
			return;
		}
		
		if ((target == null) || !AbstractRefinePacket.isAugmentableWith(player, target, lifeStone))
		{
			sb.append("<font color=\"").append(RED).append("\">This item can't be augmented with this Life Stone.</font><br><br>");
			sb.append(button("Back", lifeStone.getObjectId() + " " + page));
			finish(player, sb);
			return;
		}
		
		// The equipment.
		sb.append("<table width=280><tr>");
		sb.append("<td width=36><img src=\"").append(target.getTemplate().getIcon()).append("\" width=32 height=32></td>");
		sb.append("<td width=244>").append(itemName(target)).append("</td>");
		sb.append("</tr></table><br>");
		
		// The costs.
		final int gemStoneId = AbstractRefinePacket.getRequiredGemStoneId(target);
		final ItemTemplate gemStone = ItemData.getInstance().getTemplate(gemStoneId);
		sb.append("<table width=280>");
		sb.append("<tr><td width=150><font color=\"LEVEL\">Augment cost</font></td><td width=65 align=right><font color=\"").append(GREY).append("\">Required</font></td><td width=65 align=right><font color=\"").append(GREY).append("\">You have</font></td></tr>");
		appendCost(sb, lifeStone.getName(), 1, lifeStone.getCount());
		appendCost(sb, gemStone == null ? "Gemstones" : gemStone.getName(), AbstractRefinePacket.getRequiredGemStoneCount(target, lifeStone), player.getInventory().getInventoryItemCount(gemStoneId, -1));
		if (target.isAugmented())
		{
			appendCost(sb, "Adena (removal fee)", AbstractRefinePacket.getAugmentRemovalPrice(target), player.getAdena());
		}
		sb.append("</table><br>");
		
		final String action = lifeStone.getObjectId() + " " + page + " " + target.getObjectId() + " " + CONFIRM;
		if (target.isAugmented())
		{
			sb.append("<table width=280><tr><td><font color=\"").append(GREY).append("\">Current augment: </font>").append(describe(target.getAugmentation())).append("</td></tr></table><br>");
			sb.append("<table width=280><tr><td align=center><font color=\"").append(RED).append("\">This item is already augmented. Its current augmentation will be removed and replaced with a new one.</font></td></tr></table><br>");
			sb.append("<table width=280><tr><td align=center>").append(button("Replace", action)).append("</td><td align=center>").append(button("Back", lifeStone.getObjectId() + " " + page)).append("</td></tr></table>");
		}
		else
		{
			sb.append("<table width=280><tr><td align=center>").append(button("Augment", action)).append("</td><td align=center>").append(button("Back", lifeStone.getObjectId() + " " + page)).append("</td></tr></table>");
		}
		
		finish(player, sb);
	}
	
	/**
	 * @param augmentation the augmentation
	 * @return the skills the augmentation gives, or that it only gives stats
	 */
	public static String describe(Augmentation augmentation)
	{
		if (augmentation == null)
		{
			return "none";
		}
		
		final List<String> skills = new ArrayList<>();
		final int augmentationId = augmentation.getAugmentationId();
		for (int optionId : new int[]
		{
			0x0000FFFF & augmentationId,
			augmentationId >> 16
		})
		{
			final Options option = OptionData.getInstance().getOptions(optionId);
			if (option == null)
			{
				continue;
			}
			
			if (option.hasActiveSkill())
			{
				skills.add("Active: " + option.getActiveSkill().getName() + " Lv." + option.getActiveSkill().getLevel());
			}
			if (option.hasPassiveSkill())
			{
				skills.add("Passive: " + option.getPassiveSkill().getName() + " Lv." + option.getPassiveSkill().getLevel());
			}
			for (OptionSkillHolder holder : option.getActivationSkills())
			{
				skills.add("Chance: " + holder.getSkill().getName() + " Lv." + holder.getSkill().getLevel());
			}
		}
		
		return skills.isEmpty() ? "<font color=\"LEVEL\">stats only</font>" : "<font color=\"LEVEL\">" + String.join(", ", skills) + "</font>";
	}
	
	private static StringBuilder start(String notice)
	{
		final StringBuilder sb = new StringBuilder(4096);
		sb.append("<html><title>Augmentation</title><body><center>");
		if (notice != null)
		{
			sb.append("<table width=280><tr><td align=center>").append(notice).append("</td></tr></table><br>");
		}
		return sb;
	}
	
	/**
	 * Appends the life stone header.
	 * @param sb the page
	 * @param lifeStone the life stone
	 * @return {@code false} if the life stone was used up
	 */
	private static boolean appendLifeStone(StringBuilder sb, Item lifeStone)
	{
		if (lifeStone == null)
		{
			sb.append("<font color=\"").append(GREY).append("\">You have no more of this Life Stone.</font>");
			return false;
		}
		
		sb.append("<table width=280><tr>");
		sb.append("<td width=36><img src=\"").append(lifeStone.getTemplate().getIcon()).append("\" width=32 height=32></td>");
		sb.append("<td width=244><font color=\"LEVEL\">").append(lifeStone.getName()).append("</font><br1>");
		sb.append("<font color=\"").append(GREY).append("\">You have: ").append(lifeStone.getCount()).append("</font></td>");
		sb.append("</tr></table>");
		sb.append("<img src=\"L2UI.SquareGray\" width=280 height=1><br>");
		return true;
	}
	
	private static void appendCost(StringBuilder sb, String name, long required, long owned)
	{
		sb.append("<tr><td width=150>").append(name).append("</td><td width=65 align=right>").append(format(required)).append("</td>");
		sb.append("<td width=65 align=right><font color=\"").append(owned >= required ? GREEN : RED).append("\">").append(format(owned)).append("</font></td></tr>");
	}
	
	private static String itemName(Item item)
	{
		final StringBuilder sb = new StringBuilder();
		if (item.getEnchantLevel() > 0)
		{
			sb.append("<font color=\"LEVEL\">+").append(item.getEnchantLevel()).append("</font> ");
		}
		sb.append(item.getName());
		if (item.isEquipped())
		{
			sb.append(" <font color=\"808080\">(equipped)</font>");
		}
		return sb.toString();
	}
	
	private static String button(String value, String params)
	{
		return "<button value=\"" + value + "\" action=\"bypass -h " + BYPASS + " " + params + "\" width=65 height=22 back=\"L2UI_CT1.Button_DF_Down\" fore=\"L2UI_CT1.Button_DF\">";
	}
	
	private static String format(long value)
	{
		return String.format("%,d", value);
	}
	
	private static void finish(Player player, StringBuilder sb)
	{
		sb.append("</center></body></html>");
		final NpcHtmlMessage html = new NpcHtmlMessage(0);
		html.setHtml(sb.toString());
		player.sendPacket(html);
		player.sendPacket(ActionFailed.STATIC_PACKET);
	}
}
