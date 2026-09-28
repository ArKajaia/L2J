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
import org.l2jmobius.gameserver.handler.IItemHandler;
import org.l2jmobius.gameserver.model.actor.Playable;
import org.l2jmobius.gameserver.model.actor.Player;
import org.l2jmobius.gameserver.model.item.ItemTemplate;
import org.l2jmobius.gameserver.model.item.instance.Item;
import org.l2jmobius.gameserver.network.SystemMessageId;
import org.l2jmobius.gameserver.network.clientpackets.AbstractRefinePacket;
import org.l2jmobius.gameserver.network.serverpackets.ActionFailed;
import org.l2jmobius.gameserver.network.serverpackets.NpcHtmlMessage;

/**
 * Using a Life Stone opens a list of the weapons/accessories it can augment.<br>
 * Picking one augments it right away, taking the Life Stone and the Gemstones straight from the inventory (see handlers.bypass.npc.LifeStoneAugment).
 */
public class LifeStone implements IItemHandler
{
	public static final String BYPASS = "lifestone_augment";
	private static final int PAGE_LIMIT = 6;

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
	 * Shows the items the life stone can augment, with the Gemstones each one costs.
	 * @param player the player
	 * @param lifeStone the life stone, {@code null} if it was used up
	 * @param page the page to show
	 * @param notice a result line shown on top (already colored), or {@code null}
	 */
	public static void showList(Player player, Item lifeStone, int page, String notice)
	{
		final StringBuilder sb = new StringBuilder(4096);
		sb.append("<html><title>Augmentation</title><body><center>");
		if (notice != null)
		{
			sb.append("<table width=280><tr><td align=center>").append(notice).append("</td></tr></table><br>");
		}

		if (lifeStone == null)
		{
			sb.append("<font color=\"B09878\">You have no more of this Life Stone.</font>");
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

		sb.append("<table width=280><tr>");
		sb.append("<td width=36><img src=\"").append(lifeStone.getTemplate().getIcon()).append("\" width=32 height=32></td>");
		sb.append("<td width=244><font color=\"LEVEL\">").append(lifeStone.getName()).append("</font><br1>");
		sb.append("<font color=\"B09878\">You have: ").append(lifeStone.getCount()).append("</font></td>");
		sb.append("</tr></table>");
		sb.append("<img src=\"L2UI.SquareGray\" width=280 height=1><br>");

		if (targets.isEmpty())
		{
			sb.append("<font color=\"B09878\">Nothing else can be augmented with this Life Stone.</font>");
			finish(player, sb);
			return;
		}

		final int maxPages = Math.max(1, (targets.size() + PAGE_LIMIT - 1) / PAGE_LIMIT);
		final int currentPage = Math.max(1, Math.min(page, maxPages));
		sb.append("<font color=\"B09878\">Choose the equipment to augment:</font><br>");
		sb.append("<table width=280>");
		for (int i = (currentPage - 1) * PAGE_LIMIT; i < Math.min(currentPage * PAGE_LIMIT, targets.size()); i++)
		{
			final Item target = targets.get(i);
			final int gemStoneId = AbstractRefinePacket.getRequiredGemStoneId(target);
			final int gemStoneCount = AbstractRefinePacket.getRequiredGemStoneCount(target, lifeStone);
			final ItemTemplate gemStone = ItemData.getInstance().getTemplate(gemStoneId);
			final boolean enoughGemStones = player.getInventory().getInventoryItemCount(gemStoneId, -1) >= gemStoneCount;

			sb.append("<tr>");
			sb.append("<td width=36 height=40><img src=\"").append(target.getTemplate().getIcon()).append("\" width=32 height=32></td>");
			sb.append("<td width=174>");
			if (target.getEnchantLevel() > 0)
			{
				sb.append("<font color=\"LEVEL\">+").append(target.getEnchantLevel()).append("</font> ");
			}
			sb.append(target.getName());
			if (target.isEquipped())
			{
				sb.append(" <font color=\"808080\">(equipped)</font>");
			}
			sb.append("<br1><font color=\"").append(enoughGemStones ? "88CC88" : "CC6666").append("\">");
			sb.append(gemStone == null ? "Gemstones" : gemStone.getName()).append(" x").append(gemStoneCount).append("</font></td>");
			sb.append("<td width=70><button value=\"Augment\" action=\"bypass -h ").append(BYPASS).append(' ').append(lifeStone.getObjectId()).append(' ').append(currentPage).append(' ').append(target.getObjectId());
			sb.append("\" width=65 height=22 back=\"L2UI_CT1.Button_DF_Down\" fore=\"L2UI_CT1.Button_DF\"></td>");
			sb.append("</tr>");
		}
		sb.append("</table>");

		if (maxPages > 1)
		{
			sb.append("<br><table width=280><tr>");
			sb.append("<td width=90 align=center>");
			if (currentPage > 1)
			{
				sb.append("<button value=\"Prev\" action=\"bypass -h ").append(BYPASS).append(' ').append(lifeStone.getObjectId()).append(' ').append(currentPage - 1).append("\" width=65 height=20 back=\"L2UI_CT1.Button_DF_Down\" fore=\"L2UI_CT1.Button_DF\">");
			}
			sb.append("</td><td width=100 align=center>Page ").append(currentPage).append(" / ").append(maxPages).append("</td><td width=90 align=center>");
			if (currentPage < maxPages)
			{
				sb.append("<button value=\"Next\" action=\"bypass -h ").append(BYPASS).append(' ').append(lifeStone.getObjectId()).append(' ').append(currentPage + 1).append("\" width=65 height=20 back=\"L2UI_CT1.Button_DF_Down\" fore=\"L2UI_CT1.Button_DF\">");
			}
			sb.append("</td></tr></table>");
		}

		finish(player, sb);
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
