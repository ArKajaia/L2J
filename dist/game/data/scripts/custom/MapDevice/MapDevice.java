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
package custom.MapDevice;

import org.l2jmobius.gameserver.config.custom.MapDeviceConfig;
import org.l2jmobius.gameserver.managers.MapDeviceManager;
import org.l2jmobius.gameserver.managers.UniqueMapManager;
import org.l2jmobius.gameserver.model.actor.Npc;
import org.l2jmobius.gameserver.model.actor.Player;
import org.l2jmobius.gameserver.model.mapdevice.MapRun;
import org.l2jmobius.gameserver.model.script.Quest;
import org.l2jmobius.gameserver.network.serverpackets.NpcHtmlMessage;

/**
 * The NPCs of the Map Device (see {@link MapDeviceManager}): the device in a hideout opens its window, a portal round it takes the player into the map, and the return portal of a map takes them back to the hideout.
 * The Dealer of the unique map The Gambler's Den takes bets on its Pot ({@link UniqueMapManager#placeBet}).
 * @author Mobius
 */
public class MapDevice extends Quest
{
	public MapDevice()
	{
		super(-1);
		addFirstTalkId(MapDeviceConfig.DEVICE_NPC_ID, MapDeviceConfig.PORTAL_NPC_ID, MapDeviceConfig.RETURN_PORTAL_NPC_ID, MapDeviceConfig.DEALER_NPC_ID);
		addTalkId(MapDeviceConfig.DEVICE_NPC_ID, MapDeviceConfig.PORTAL_NPC_ID, MapDeviceConfig.RETURN_PORTAL_NPC_ID, MapDeviceConfig.DEALER_NPC_ID);
	}

	@Override
	public String onEvent(String event, Npc npc, Player player)
	{
		if ((npc != null) && (npc.getId() == MapDeviceConfig.DEALER_NPC_ID) && "den_bet".equals(event))
		{
			final String refused = UniqueMapManager.getInstance().placeBet(player);
			showDealer(npc, player, refused);
		}
		return null;
	}

	/**
	 * The Dealer's table: the Pot, the fortune, the bets in hand and the Bet button.
	 * @param npc the Dealer
	 * @param player who talks to him
	 * @param note a line on top (why a bet was refused), {@code null} for none
	 */
	private void showDealer(Npc npc, Player player, String note)
	{
		final UniqueMapManager den = UniqueMapManager.getInstance();
		final MapRun run = MapDeviceManager.getRun(npc.getInstanceId());
		final StringBuilder sb = new StringBuilder(1024);
		sb.append("<html><body><center><font color=\"LEVEL\">The Dealer</font></center><br>");
		if (note != null)
		{
			sb.append("<font color=\"FF6666\">").append(note).append("</font><br><br>");
		}
		if ((run == null) || run.isClosed())
		{
			sb.append("\"The tables are closed, friend.\"</body></html>");
			sendHtml(npc, player, sb.toString());
			return;
		}
		final int bets = den.getBets(run);
		sb.append("\"Double or half, friend. Even odds, the house's honour.\"<br><br>");
		sb.append("<table width=270>");
		sb.append("<tr><td><font color=\"A0A0A0\">Pot</font></td><td align=right><font color=\"E6C35C\">x").append(String.format("%.2f", den.getPot(run))).append("</font></td></tr>");
		sb.append("<tr><td><font color=\"A0A0A0\">Bets in hand</font></td><td align=right><font color=\"E6C35C\">").append(bets).append("</font></td></tr>");
		final int toSpin = den.getKillsToSpin(run);
		if (toSpin > 0)
		{
			sb.append("<tr><td><font color=\"A0A0A0\">Next spin in</font></td><td align=right>").append(toSpin).append(toSpin == 1 ? " kill" : " kills").append("</td></tr>");
		}
		sb.append("</table><br>");
		final String fortune = den.getFortuneText(run);
		if (fortune != null)
		{
			sb.append("<font color=\"").append(den.isFortuneBlessing(run) ? "66CC66" : "FF6666").append("\">").append(fortune).append("</font><br><br>");
		}
		final String card = den.getCardText(run);
		if (card != null)
		{
			sb.append("<font color=\"FF9955\">The House holds the ").append(card).append("</font><br><br>");
		}
		sb.append("<font color=\"707070\">The Pot multiplies every drop of the Den, x0.25 to x4; past 100% the drops grow bigger. A bet doubles it or halves it. The Wheel of Fate gives a bet every spin, and the House one more.</font><br><br>");
		if (bets > 0)
		{
			sb.append("<center><button value=\"Bet the Pot\" action=\"bypass -h Quest ").append(getClass().getSimpleName()).append(" den_bet\" width=150 height=25 back=\"L2UI_CT1.Button_DF_Down\" fore=\"L2UI_CT1.Button_DF\"></center>");
		}
		sb.append("</body></html>");
		sendHtml(npc, player, sb.toString());
	}

	private static void sendHtml(Npc npc, Player player, String text)
	{
		final NpcHtmlMessage html = new NpcHtmlMessage(npc.getObjectId());
		html.setHtml(text);
		player.sendPacket(html);
	}

	@Override
	public String onFirstTalk(Npc npc, Player player)
	{
		final MapDeviceManager manager = MapDeviceManager.getInstance();
		final int npcId = npc.getId();
		if (npcId == MapDeviceConfig.PORTAL_NPC_ID)
		{
			final String denied = manager.usePortal(player, npc);
			if (denied != null)
			{
				player.sendMessage(denied);
			}
			return null;
		}
		if (npcId == MapDeviceConfig.DEALER_NPC_ID)
		{
			showDealer(npc, player, null);
			return null;
		}
		if (npcId == MapDeviceConfig.RETURN_PORTAL_NPC_ID)
		{
			final String denied = manager.useReturnPortal(player);
			if (denied != null)
			{
				player.sendMessage(denied);
			}
			return null;
		}

		MapDeviceWindow.showDevice(player);
		return null;
	}

	@Override
	public String onTalk(Npc npc, Player player)
	{
		return onFirstTalk(npc, player);
	}

	public static void main(String[] args)
	{
		new MapDevice();
	}
}
