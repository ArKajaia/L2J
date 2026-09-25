package custom.ArenaShop;

import java.util.Locale;

import org.l2jmobius.gameserver.data.xml.MultisellData;
import org.l2jmobius.gameserver.model.actor.Npc;
import org.l2jmobius.gameserver.model.actor.Player;
import org.l2jmobius.gameserver.model.multisell.Entry;
import org.l2jmobius.gameserver.model.multisell.Ingredient;
import org.l2jmobius.gameserver.model.multisell.ListContainer;
import org.l2jmobius.gameserver.model.script.Quest;
import org.l2jmobius.gameserver.network.serverpackets.NpcHtmlMessage;

/**
 * Search box for the Arena Merchant (html/default/90000.htm). The HighFive multisell window has no search and every recipe shares one icon, so "search &lt;words&gt;" collects every entry from the merchant's category lists whose product name contains ALL the words, and opens them as one filtered
 * multisell.
 * <p>
 * The filtered list is a runtime {@link ListContainer} sent via {@link MultisellData#separateAndSend(ListContainer, Player, Npc)}. Buying from it goes through the normal MultiSellChoose path, which only reads the player's prepared list - same NPC, distance and ingredient checks as the real lists.
 */
public class ArenaShop extends Quest
{
	private static final int SHOP_NPC_ID = 90000;
	/** Every list the merchant's category buttons open, in button order (results keep this order). */
	private static final int[] SOURCE_LISTS =
	{
		90001, 90002, 90003, 90004, 90005, 90006, 90007, 90008, 90009, 90010, 90011, 90012
	};
	/** Not backed by any XML file - only ever exists as the player's prepared search result. */
	private static final int SEARCH_LIST_ID = 90099;
	private static final int MIN_QUERY_LENGTH = 2;
	private static final int MAX_QUERY_LENGTH = 40;
	private static final int MAX_RESULTS = 200;

	public ArenaShop()
	{
		super(-1);
	}

	@Override
	public String onEvent(String event, Npc npc, Player player)
	{
		if ((npc == null) || (player == null) || (npc.getId() != SHOP_NPC_ID) || !event.startsWith("search"))
		{
			return null;
		}

		String query = event.substring("search".length()).trim().toLowerCase(Locale.ROOT);
		// An empty edit box can arrive as the literal variable name on some clients.
		if (query.equals("$q"))
		{
			query = "";
		}
		if (query.length() > MAX_QUERY_LENGTH)
		{
			query = query.substring(0, MAX_QUERY_LENGTH);
		}

		if (query.length() < MIN_QUERY_LENGTH)
		{
			showPage(player, npc, "Type at least " + MIN_QUERY_LENGTH + " letters of the item name.");
			return null;
		}

		final String[] words = query.split("\\s+");
		final ListContainer result = new ListContainer(SEARCH_LIST_ID);
		result.allowNpc(SHOP_NPC_ID);

		int matches = 0;
		for (int listId : SOURCE_LISTS)
		{
			final ListContainer source = MultisellData.getInstance().getList(listId);
			if (source == null)
			{
				continue;
			}

			for (Entry entry : source.getEntries())
			{
				if (!matches(entry, words))
				{
					continue;
				}

				matches++;
				if (result.getEntries().size() >= MAX_RESULTS)
				{
					continue; // keep counting so the message can say how many were left out
				}

				// Original entry ids restart at 1 in every file - renumber so ids are unique within this list,
				// otherwise MultiSellChoose (which matches by entry id) could hand out the wrong item.
				final Entry copy = new Entry(result.getEntries().size() + 1);
				for (Ingredient product : entry.getProducts())
				{
					copy.addProduct(product.getCopy());
				}
				for (Ingredient ingredient : entry.getIngredients())
				{
					copy.addIngredient(ingredient.getCopy());
				}
				result.getEntries().add(copy);
			}
		}

		if (matches == 0)
		{
			showPage(player, npc, "No items match \"" + escape(query) + "\".");
			return null;
		}

		player.sendMessage("Arena shop: found " + matches + " item(s) for \"" + query + "\"" + (matches > MAX_RESULTS ? " - showing the first " + MAX_RESULTS + ", refine your search." : "."));
		MultisellData.getInstance().separateAndSend(result, player, npc);
		return null;
	}

	/**
	 * @return true if every word appears in the name of at least one product (a numeric word also matches the product's item id)
	 */
	private static boolean matches(Entry entry, String[] words)
	{
		for (String word : words)
		{
			boolean found = false;
			for (Ingredient product : entry.getProducts())
			{
				if ((product.getTemplate() != null) && product.getTemplate().getName().toLowerCase(Locale.ROOT).contains(word))
				{
					found = true;
					break;
				}
				if (word.equals(String.valueOf(product.getItemId())))
				{
					found = true;
					break;
				}
			}
			if (!found)
			{
				return false;
			}
		}
		return true;
	}

	private static void showPage(Player player, Npc npc, String notice)
	{
		final StringBuilder sb = new StringBuilder();
		sb.append("<html><body><center>");
		sb.append("<font color=\"LEVEL\" name=\"hs12\">Master Crafter's Market</font><br><br>");
		sb.append("<font color=\"ff8866\">").append(notice).append("</font><br><br>");
		sb.append("<font color=\"aaaaaa\">Search all categories by item name:</font>");
		sb.append("<table width=260><tr><td width=180><edit var=\"q\" width=175 height=15></td>");
		sb.append("<td width=80><button value=\"Search\" action=\"bypass -h Script ArenaShop search $q\" width=75 height=21 back=\"L2UI_CT1.Button_DF_Down\" fore=\"L2UI_CT1.Button_DF\"></td></tr></table><br>");
		sb.append("<button value=\"Back\" action=\"bypass -h npc_%objectId%_Chat 0\" width=120 height=21 back=\"L2UI_CT1.Button_DF_Down\" fore=\"L2UI_CT1.Button_DF\">");
		sb.append("</center></body></html>");

		final NpcHtmlMessage html = new NpcHtmlMessage(npc.getObjectId(), sb.toString());
		html.replace("%objectId%", String.valueOf(npc.getObjectId()));
		player.sendPacket(html);
	}

	private static String escape(String text)
	{
		return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
	}

	public static void main(String[] args)
	{
		new ArenaShop();
	}
}
