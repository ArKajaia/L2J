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
package custom.TownLife;

import java.time.LocalDate;
import java.util.List;

import org.l2jmobius.commons.util.Rnd;
import org.l2jmobius.gameserver.config.custom.TownLifeConfig;
import org.l2jmobius.gameserver.data.xml.ItemData;
import org.l2jmobius.gameserver.data.xml.NpcData;
import org.l2jmobius.gameserver.managers.TownLifeManager;
import org.l2jmobius.gameserver.model.actor.Npc;
import org.l2jmobius.gameserver.model.actor.Player;
import org.l2jmobius.gameserver.model.item.ItemTemplate;
import org.l2jmobius.gameserver.model.item.enums.ItemProcessType;
import org.l2jmobius.gameserver.model.item.holders.ItemHolder;
import org.l2jmobius.gameserver.model.script.Script;

/**
 * Dialogs of the town life npcs (see {@link TownLifeManager}): what the townsfolk, workers, guards and harbor folk say when talked to, the news of the town crier, and the children's sweets: once a day a character can give a child a sweet (for
 * TownLifeTreatPrice adena) and gets one of TownLifeTreatGifts as a thank-you.
 */
public class TownLife extends Script
{
	private static final int FIRST_NPC = 900400;
	private static final int LAST_NPC = 900439;
	private static final String TREAT_VARIABLE = "TOWN_LIFE_TREAT_DAY";
	
	private TownLife()
	{
		for (int id = FIRST_NPC; id <= LAST_NPC; id++)
		{
			if (NpcData.getInstance().getTemplate(id) == null)
			{
				continue;
			}
			
			addStartNpc(id);
			addFirstTalkId(id);
			addTalkId(id);
		}
	}
	
	@Override
	public String onFirstTalk(Npc npc, Player player)
	{
		final TownLifeManager manager = TownLifeManager.getInstance();
		if (manager.isCrier(npc))
		{
			return crierPage(npc, manager.getNews(npc));
		}
		
		if (manager.isChild(npc))
		{
			return childPage(npc, player);
		}
		
		final String talk = manager.getTalk(npc, player);
		return page(npc, talk != null ? talk : "...");
	}
	
	@Override
	public String onEvent(String event, Npc npc, Player player)
	{
		if ((npc == null) || (player == null) || !event.equals("treat") || !TownLifeManager.getInstance().isChild(npc))
		{
			return null;
		}
		
		if (!TownLifeConfig.KIDS_TREATS || (npc.calculateDistance3D(player) > 250))
		{
			return null;
		}
		
		if (hasTreatedToday(player))
		{
			return page(npc, "Mom says only one sweet a day... but thank you anyway! Come and play tomorrow!");
		}
		
		if (TownLifeConfig.TREAT_GIFTS.isEmpty())
		{
			return null;
		}
		
		if ((TownLifeConfig.TREAT_PRICE > 0) && !player.reduceAdena(ItemProcessType.FEE, TownLifeConfig.TREAT_PRICE, npc, true))
		{
			return page(npc, "Aww, you don't have enough for a sweet? That's okay. Grown-ups are always poor.");
		}
		
		player.getVariables().set(TREAT_VARIABLE, (int) LocalDate.now().toEpochDay());
		final ItemHolder gift = TownLifeConfig.TREAT_GIFTS.get(Rnd.get(TownLifeConfig.TREAT_GIFTS.size()));
		giveItems(player, gift.getId(), gift.getCount());
		TownLifeManager.getInstance().onTreat(npc, player);
		
		final ItemTemplate item = ItemData.getInstance().getTemplate(gift.getId());
		final String giftName = item != null ? item.getName() : "a present";
		return page(npc, "Mmm, a sweet! Thank you! Here, I found this - you can have it!<br><font color=\"LEVEL\">" + (gift.getCount() > 1 ? gift.getCount() + " x " : "") + giftName + "</font>");
	}
	
	private static boolean hasTreatedToday(Player player)
	{
		return player.getVariables().getInt(TREAT_VARIABLE, -1) == (int) LocalDate.now().toEpochDay();
	}
	
	private String childPage(Npc npc, Player player)
	{
		final TownLifeManager manager = TownLifeManager.getInstance();
		final StringBuilder sb = new StringBuilder();
		if (manager.hasAsked(npc, player))
		{
			sb.append("You came! Do you have a sweet for me? Pleeease? Just one!");
		}
		else
		{
			sb.append(manager.getTalk(npc, player)).append(" We're playing tag! Do you want to play too? You'd be it!");
		}
		
		if (TownLifeConfig.KIDS_TREATS && !TownLifeConfig.TREAT_GIFTS.isEmpty())
		{
			if (hasTreatedToday(player))
			{
				sb.append("<br><br>Thank you for the sweet today!");
			}
			else
			{
				sb.append("<br><br><a action=\"bypass -h Quest ").append(getClass().getSimpleName()).append(" treat\">Give a sweet");
				if (TownLifeConfig.TREAT_PRICE > 0)
				{
					sb.append(" (").append(TownLifeConfig.TREAT_PRICE).append(" adena)");
				}
				sb.append("</a>");
			}
		}
		return page(npc, sb.toString());
	}
	
	private static String crierPage(Npc npc, List<String> news)
	{
		final StringBuilder sb = new StringBuilder("Hear ye, hear ye! The news of the day:<br>");
		for (String line : news)
		{
			sb.append("<br>").append(line.replace("Hear ye, hear ye! ", "").replace("Hear ye! ", ""));
		}
		return page(npc, sb.toString());
	}
	
	private static String page(Npc npc, String body)
	{
		return "<html><body>" + npc.getName() + ":<br>" + body + "</body></html>";
	}
	
	public static void main(String[] args)
	{
		new TownLife();
	}
}
