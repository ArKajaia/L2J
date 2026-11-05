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

import java.util.List;
import java.util.Map;

import org.l2jmobius.gameserver.config.custom.MapDeviceConfig;
import org.l2jmobius.gameserver.data.xml.MapDeviceData;
import org.l2jmobius.gameserver.managers.MapDeviceManager;
import org.l2jmobius.gameserver.managers.MapQuestManager;
import org.l2jmobius.gameserver.managers.MapQuestManager.Step;
import org.l2jmobius.gameserver.model.actor.Player;
import org.l2jmobius.gameserver.model.actor.templates.NpcTemplate;
import org.l2jmobius.gameserver.model.hotzone.HotzoneModifier;
import org.l2jmobius.gameserver.model.mapdevice.Hideout;
import org.l2jmobius.gameserver.model.mapdevice.MapModifier;
import org.l2jmobius.gameserver.model.mapdevice.MapRun;
import org.l2jmobius.gameserver.model.mapdevice.MapTier;
import org.l2jmobius.gameserver.model.mapdevice.MonsterStrength;
import org.l2jmobius.gameserver.network.serverpackets.ActionFailed;
import org.l2jmobius.gameserver.network.serverpackets.NpcHtmlMessage;

/**
 * The windows of the Map Device: the maps a player carries, how many medals to feed one, the confirmation, and the state of an open map. Shown by the device itself ({@link MapDevice}), by {@code .hideout} and by double-clicking a map; every button goes through {@code .hideout}
 * (a voiced bypass), so they work wherever the window was opened. The manager checks every action again.
 * @author Mobius
 */
public final class MapDeviceWindow
{
	private static final int WINDOW_WIDTH = 420;
	private static final int WINDOW_HEIGHT = 640;
	private static final int CONTENT_WIDTH = 390;
	private static final String BG_BAR = "1A1A1A";
	private static final String BG_CARD = "111111";
	private static final String COLOR_TITLE = "FFFFFF";
	private static final String COLOR_VALUE = "E6C35C";
	private static final String COLOR_CREATIVE = "FF9955";
	private static final String COLOR_STAT = "99CCFF";
	private static final String COLOR_POSITIVE = "66CC66";
	private static final String COLOR_NEGATIVE = "FF6666";
	private static final String COLOR_MUTED = "A0A0A0";
	private static final String COLOR_HINT = "707070";
	private static final String COMMAND = "bypass voiced_hideout";

	private MapDeviceWindow()
	{
	}

	/**
	 * The device: the open map if there is one, else the owner's maps, else whose device it is.
	 * @param player the player
	 */
	public static void showDevice(Player player)
	{
		final MapDeviceManager manager = MapDeviceManager.getInstance();
		final MapRun inMap = MapDeviceManager.getRun(player.getInstanceId());
		if (inMap != null)
		{
			showRun(player, inMap);
			return;
		}

		final Hideout hideout = manager.getCurrentHideout(player);
		if (hideout == null)
		{
			showNotInHideout(player);
			return;
		}

		final MapRun run = hideout.getRun();
		if ((run != null) && !run.isClosed())
		{
			showRun(player, run);
			return;
		}

		if (hideout.getOwnerId() != player.getObjectId())
		{
			final StringBuilder sb = start("Map Device");
			sb.append(text(COLOR_MUTED, "This is " + hideout.getOwnerName() + "'s Map Device. No map is open.")).append("<br1>");
			sb.append(text(COLOR_HINT, "When " + hideout.getOwnerName() + " opens a map, you may take its portals.")).append("<br>");
			button(sb, "Leave", "leave", 120);
			send(player, sb);
			return;
		}

		showMaps(player);
	}

	private static void showNotInHideout(Player player)
	{
		final StringBuilder sb = start("Map Device");
		sb.append(text("CCCCCC", "Your Map Device stands in your hideout.")).append("<br1>");
		sb.append(text(COLOR_MUTED, "Type .hideout in a town (or another peaceful place) to go there, and double-click a map in the hideout to put it in.")).append("<br1>");
		sb.append(text(COLOR_HINT, "Maps drop from monsters of level 78 and up: the higher their level, and the stronger they are, the higher the tier.")).append("<br>");
		button(sb, "Go to my hideout", "", 140);
		if (player.getParty() != null)
		{
			button(sb, "Visit my party's hideout", "party", 180);
		}
		send(player, sb);
	}

	/**
	 * The owner's maps, each with a button to put it in.
	 * @param player the owner
	 */
	public static void showMaps(Player player)
	{
		final Map<MapTier, Long> maps = MapDeviceManager.getInstance().getCarriedMaps(player);
		final long medals = player.getInventory().getInventoryItemCount(MapDeviceConfig.MEDAL_ITEM_ID, -1);

		final StringBuilder sb = start("Map Device");
		sb.append(text(COLOR_MUTED, "Put a map in the device and feed it " + medalName() + "s: each one adds a modifier and raises the experience and drop rate.")).append("<br>");

		section(sb, "Wallet");
		table(sb);
		row(sb, medalName(), text(COLOR_VALUE, format(medals)), false);
		sb.append("</table><br>");

		section(sb, "Your Maps");
		if (maps.isEmpty())
		{
			sb.append(text(COLOR_HINT, "You carry no map. Maps drop from monsters of level 78 and up.")).append("<br>");
		}
		else
		{
			sb.append("<table width=").append(CONTENT_WIDTH).append(" cellpadding=3 cellspacing=0>");
			boolean shade = false;
			for (Map.Entry<MapTier, Long> entry : maps.entrySet())
			{
				final MapTier tier = entry.getKey();
				final String bg = shade ? " bgcolor=\"" + BG_CARD + "\"" : "";
				sb.append("<tr><td width=150").append(bg).append(">").append(text(COLOR_VALUE, "Tier " + tier.tier())).append(text(COLOR_MUTED, " x" + entry.getValue())).append("</td>");
				sb.append("<td width=130").append(bg).append(">").append(text(COLOR_MUTED, "Lv. " + tier.minLevel() + "-" + tier.maxLevel())).append("<br1>").append(text(strengthColor(tier), MonsterStrength.describe(tier.minScore()))).append("</td>");
				sb.append("<td width=110").append(bg).append(" align=right><button value=\"Insert\" action=\"").append(COMMAND).append(" insert ").append(tier.tier()).append("\" width=80 height=21 back=\"L2UI_CT1.Button_DF_Down\" fore=\"L2UI_CT1.Button_DF\"></td></tr>");
				shade = !shade;
			}
			sb.append("</table><br>");
		}

		button(sb, "Leave the hideout", "leave", 140);
		send(player, sb);
	}

	/**
	 * A map put in: what it holds, and how many medals to feed it.
	 * @param player the owner
	 * @param tierId the map's tier
	 */
	public static void showInsert(Player player, int tierId)
	{
		final MapTier tier = MapDeviceData.getInstance().getTier(tierId);
		if (tier == null)
		{
			showDevice(player);
			return;
		}

		final long medals = player.getInventory().getInventoryItemCount(MapDeviceConfig.MEDAL_ITEM_ID, -1);
		final StringBuilder sb = start("Map (Tier " + tier.tier() + ")");

		section(sb, "The Map");
		table(sb);
		row(sb, "Monsters", text(COLOR_VALUE, "Lv. " + tier.minLevel() + "-" + tier.maxLevel()), false);
		row(sb, "Their strength", strength(tier), true);
		row(sb, "Their HP", "x" + two(tier.hp()), false);
		row(sb, "Their attack", "x" + two(tier.attack()), true);
		row(sb, "Their defence", "x" + two(tier.defence()), false);
		row(sb, "Experience and drops", text(COLOR_POSITIVE, "x" + two(tier.reward())), true);
		row(sb, "Portals", String.valueOf(MapDeviceConfig.PORTAL_COUNT), false);
		sb.append("</table>");
		sb.append(text(COLOR_HINT, "Strength 1.0 is an ordinary monster of its level, 2.0 twice as strong (a Spiked Stakato); party monsters start near 3, the Seed of Annihilation elites are 10. The tier's multipliers come on top.")).append("<br><br>");

		section(sb, "Feed it " + medalName() + "s");
		sb.append(text(COLOR_MUTED, "You have " + format(medals) + ". Each one rolls a modifier (out of " + MapModifier.values().length + ") and adds +" + MapDeviceConfig.XP_BONUS_PER_MEDAL + "% experience and +" + MapDeviceConfig.DROP_BONUS_PER_MEDAL + "% drop rate.")).append("<br1>");
		sb.append("<table width=").append(CONTENT_WIDTH).append(" cellpadding=2 cellspacing=0>");
		for (int count = 0; count <= MapDeviceConfig.MAX_MEDALS; count++)
		{
			final String bg = (count % 2) == 1 ? " bgcolor=\"" + BG_CARD + "\"" : "";
			sb.append("<tr><td width=280").append(bg).append(">");
			if (count == 0)
			{
				sb.append(text("CCCCCC", "No medal")).append(text(COLOR_MUTED, " - a plain map"));
			}
			else
			{
				sb.append(text("CCCCCC", count + " medal" + (count == 1 ? "" : "s"))).append(text(COLOR_MUTED, " - " + count + " modifier" + (count == 1 ? "" : "s") + ", +" + (count * MapDeviceConfig.XP_BONUS_PER_MEDAL) + "% exp/drop"));
			}
			sb.append("</td><td width=110").append(bg).append(" align=right>");
			if (medals >= count)
			{
				sb.append("<button value=\"Choose\" action=\"").append(COMMAND).append(" medals ").append(tier.tier()).append(' ').append(count).append("\" width=70 height=20 back=\"L2UI_CT1.Button_DF_Down\" fore=\"L2UI_CT1.Button_DF\">");
			}
			else
			{
				sb.append(text(COLOR_HINT, "not enough"));
			}
			sb.append("</td></tr>");
		}
		sb.append("</table><br>");
		button(sb, "Back", "device", 100);
		send(player, sb);
	}

	/**
	 * The last look before the map opens.
	 * @param player the owner
	 * @param tierId the map's tier
	 * @param medals how many medals
	 */
	public static void showConfirm(Player player, int tierId, int medals)
	{
		final MapTier tier = MapDeviceData.getInstance().getTier(tierId);
		if ((tier == null) || (medals < 0) || (medals > MapDeviceConfig.MAX_MEDALS))
		{
			showDevice(player);
			return;
		}

		final StringBuilder sb = start("Open the Map?");
		table(sb);
		row(sb, "Map", text(COLOR_VALUE, "Tier " + tier.tier()) + text(COLOR_MUTED, " (Lv. " + tier.minLevel() + "-" + tier.maxLevel() + ")"), false);
		row(sb, "Monster strength", strength(tier), true);
		row(sb, "It costs", "1 " + MapDeviceManager.getItemName(tier.itemId()) + (medals > 0 ? ", " + medals + " " + medalName() + (medals == 1 ? "" : "s") : ""), false);
		row(sb, "Modifiers", medals > 0 ? text(COLOR_CREATIVE, medals + " at random") : text(COLOR_HINT, "none"), true);
		row(sb, "Hot zone effect", "1 at random", false);
		row(sb, "Experience", text(COLOR_POSITIVE, "+" + (medals * MapDeviceConfig.XP_BONUS_PER_MEDAL) + "%") + text(COLOR_MUTED, " (and the tier's x" + two(tier.reward()) + ")"), true);
		row(sb, "Drop rate", text(COLOR_POSITIVE, "+" + (medals * MapDeviceConfig.DROP_BONUS_PER_MEDAL) + "%"), false);
		row(sb, "Monsters", "about " + MapDeviceConfig.MONSTER_COUNT + ", none respawn", true);
		row(sb, "Portals", MapDeviceConfig.PORTAL_COUNT + ", one player each", false);
		sb.append("</table><br>");
		sb.append(text(COLOR_MUTED, "If you die inside, you wake up here and may take another portal. Slay every monster to complete the map.")).append("<br>");
		sb.append("<table width=").append(CONTENT_WIDTH).append("><tr><td align=center>");
		sb.append("<button value=\"Open the Map\" action=\"").append(COMMAND).append(" open ").append(tier.tier()).append(' ').append(medals).append("\" width=130 height=25 back=\"L2UI_CT1.Button_DF_Down\" fore=\"L2UI_CT1.Button_DF\">");
		sb.append("</td><td align=center>");
		sb.append("<button value=\"Back\" action=\"").append(COMMAND).append(" insert ").append(tier.tier()).append("\" width=100 height=25 back=\"L2UI_CT1.Button_DF_Down\" fore=\"L2UI_CT1.Button_DF\">");
		sb.append("</td></tr></table>");
		send(player, sb);
	}

	/**
	 * An open map: what is left of it, its modifiers and hot zone effect.
	 * @param player the player
	 * @param run the map
	 */
	public static void showRun(Player player, MapRun run)
	{
		final boolean inMap = player.getInstanceId() == run.getInstanceId();
		final boolean owner = player.getObjectId() == run.getOwnerId();
		final StringBuilder sb = start("Tier " + run.getTier().tier() + " Map");

		sb.append("<table width=").append(CONTENT_WIDTH).append(" cellpadding=0 cellspacing=0><tr><td align=center>");
		sb.append(text(COLOR_VALUE, run.getArena().name())).append(text(COLOR_MUTED, " - opened by " + run.getOwnerName()));
		sb.append("</td></tr></table><br>");

		section(sb, "State");
		table(sb);
		final String state = run.getStatus() == MapRun.Status.COMPLETED ? text(COLOR_POSITIVE, "Complete!") : run.isClosed() ? text(COLOR_NEGATIVE, "Closed") : text(COLOR_TITLE, "Open");
		row(sb, "State", state, false);
		row(sb, "Monsters left", text(COLOR_VALUE, run.getMonsters().size() + " / " + run.getMonsterTotal()), true);
		row(sb, "Portals left", run.getPortals().size() + " / " + run.getPortalCount(), false);
		final int seconds = run.getRemainingSeconds();
		row(sb, "Collapses in", (seconds / 60) + "m " + (seconds % 60) + "s", true);
		row(sb, "Monsters", "Lv. " + run.getTier().minLevel() + "-" + run.getTier().maxLevel() + text(COLOR_MUTED, ", strength " + kindsStrength(run)), false);
		row(sb, "Experience / drops", text(COLOR_POSITIVE, "x" + two(run.getXpMultiplier()) + " / x" + two(run.getDropMultiplier())), true);
		sb.append("</table><br>");

		section(sb, "Modifiers (" + run.getModifiers().size() + ")");
		if (run.getModifiers().isEmpty())
		{
			sb.append(text(COLOR_HINT, "None: no medal was fed to this map.")).append("<br>");
		}
		else
		{
			sb.append("<table width=").append(CONTENT_WIDTH).append(" cellpadding=2 cellspacing=0>");
			boolean shade = false;
			for (MapModifier modifier : run.getModifiers())
			{
				final String bg = shade ? " bgcolor=\"" + BG_CARD + "\"" : "";
				sb.append("<tr><td width=").append(CONTENT_WIDTH).append(bg).append(">");
				sb.append(text(modifier.isStatModifier() ? COLOR_STAT : COLOR_CREATIVE, modifier.getDisplayName())).append("<br1>");
				sb.append(text(COLOR_MUTED, modifier.getDescription()));
				sb.append("</td></tr>");
				shade = !shade;
			}
			sb.append("</table><br>");
		}

		section(sb, "Hot Zone Effect");
		final HotzoneModifier hotzone = run.getHotzoneEffect();
		if (hotzone == null)
		{
			sb.append(text(COLOR_HINT, "None.")).append("<br>");
		}
		else
		{
			sb.append(text(COLOR_VALUE, MapDeviceManager.getHotzoneEffectName(run))).append("<br1>");
			sb.append(text(COLOR_MUTED, hotzone.getDescription())).append("<br>");
		}

		sb.append("<table width=").append(CONTENT_WIDTH).append("><tr><td align=center>");
		sb.append("<button value=\"Refresh\" action=\"").append(COMMAND).append(" device\" width=90 height=22 back=\"L2UI_CT1.Button_DF_Down\" fore=\"L2UI_CT1.Button_DF\">");
		sb.append("</td><td align=center>");
		if (inMap)
		{
			sb.append("<button value=\"Back to the Hideout\" action=\"").append(COMMAND).append(" back\" width=150 height=22 back=\"L2UI_CT1.Button_DF_Down\" fore=\"L2UI_CT1.Button_DF\">");
		}
		else if (owner && !run.isClosed())
		{
			sb.append("<button value=\"Close the Map\" action=\"").append(COMMAND).append(" close\" width=120 height=22 back=\"L2UI_CT1.Button_DF_Down\" fore=\"L2UI_CT1.Button_DF\">");
		}
		else
		{
			sb.append("<button value=\"Leave\" action=\"").append(COMMAND).append(" leave\" width=90 height=22 back=\"L2UI_CT1.Button_DF_Down\" fore=\"L2UI_CT1.Button_DF\">");
		}
		sb.append("</td></tr></table>");
		if (!inMap)
		{
			sb.append("<br>").append(text(COLOR_HINT, "Take a portal round the device to enter. Each one takes one player in and is gone."));
		}
		send(player, sb);
	}

	/**
	 * Asks the owner whether to close their map early.
	 * @param player the owner
	 */
	public static void showCloseConfirm(Player player)
	{
		final StringBuilder sb = start("Close the Map?");
		sb.append(text("CCCCCC", "Everyone inside is sent back here and the portals vanish. The map and the medals are lost.")).append("<br><br>");
		sb.append("<table width=").append(CONTENT_WIDTH).append("><tr><td align=center>");
		sb.append("<button value=\"Close it\" action=\"").append(COMMAND).append(" close confirm\" width=100 height=25 back=\"L2UI_CT1.Button_DF_Down\" fore=\"L2UI_CT1.Button_DF\">");
		sb.append("</td><td align=center>");
		sb.append("<button value=\"Back\" action=\"").append(COMMAND).append(" device\" width=100 height=25 back=\"L2UI_CT1.Button_DF_Down\" fore=\"L2UI_CT1.Button_DF\">");
		sb.append("</td></tr></table>");
		send(player, sb);
	}

	/**
	 * The quest "A Map of Your Own": its steps, done and to do, and its reward. Opened by its question mark and by {@code .hideout quest}.
	 * @param player the player
	 */
	public static void showQuest(Player player)
	{
		final MapQuestManager quest = MapQuestManager.getInstance();
		quest.markRead(player);
		final Step current = quest.getStep(player);
		final StringBuilder sb = start(MapQuestManager.QUEST_NAME);

		if (current == Step.NONE)
		{
			sb.append(text("CCCCCC", "This quest starts when you find your first map.")).append("<br1>");
			sb.append(text(COLOR_MUTED, "Maps drop from monsters of level 78 and up.")).append("<br>");
			send(player, sb);
			return;
		}

		if (current == Step.DONE)
		{
			sb.append(text(COLOR_POSITIVE, "Quest complete!")).append("<br1>");
			sb.append(text("CCCCCC", "You know the way now: every map you find opens at your Map Device. Clearing a map drops another one, and maps found inside are often of a higher tier.")).append("<br>");
		}
		else
		{
			sb.append(text("CCCCCC", "You found a map! A map opens at the Map Device of your own hideout into a fenced field full of monsters that never respawn. Feed the device Champion Medals for modifiers that make the map harder and its rewards richer.")).append("<br1>");
			sb.append(text(COLOR_MUTED, "Here is how to run your first one.")).append("<br>");
		}

		section(sb, "Steps");
		sb.append("<table width=").append(CONTENT_WIDTH).append(" cellpadding=3 cellspacing=0>");
		boolean shade = false;
		for (Step step : Step.values())
		{
			if (!step.isActive())
			{
				continue;
			}
			final boolean done = step.ordinal() < current.ordinal();
			final boolean now = step == current;
			final String bg = shade ? " bgcolor=\"" + BG_CARD + "\"" : "";
			sb.append("<tr><td width=60 valign=top").append(bg).append(">");
			sb.append(done ? text(COLOR_POSITIVE, "Done") : now ? text(COLOR_VALUE, "Now") : text(COLOR_HINT, "Next"));
			sb.append("</td><td width=330").append(bg).append(">");
			sb.append(text(done ? COLOR_MUTED : now ? COLOR_TITLE : COLOR_HINT, step.getTitle()));
			if (now)
			{
				sb.append("<br1>").append(text(COLOR_MUTED, step.getObjective()));
				if ((step == Step.HIDEOUT) && (player.getLevel() < MapDeviceConfig.HIDEOUT_MIN_LEVEL))
				{
					sb.append("<br1>").append(text(COLOR_NEGATIVE, "Your hideout opens at level " + MapDeviceConfig.HIDEOUT_MIN_LEVEL + "."));
				}
			}
			sb.append("</td></tr>");
			shade = !shade;
		}
		sb.append("</table><br>");

		section(sb, "Reward");
		table(sb);
		final int medals = quest.getRewardMedals();
		row(sb, "For clearing the map", medals > 0 ? text(COLOR_VALUE, medals + " " + medalName() + (medals == 1 ? "" : "s")) + (current == Step.DONE ? text(COLOR_POSITIVE, " - received") : "") : text(COLOR_HINT, "none"), false);
		sb.append("</table><br>");

		final MapDeviceManager manager = MapDeviceManager.getInstance();
		final boolean inHideoutOrMap = MapDeviceManager.isHideoutInstance(player.getInstanceId()) || MapDeviceManager.isMapInstance(player.getInstanceId());
		if (current == Step.HIDEOUT)
		{
			if (!inHideoutOrMap)
			{
				button(sb, "Go to my hideout", "", 140);
			}
		}
		else if (current == Step.OPEN_MAP)
		{
			if (manager.isDeviceOwner(player))
			{
				button(sb, "Open the Map Device", "device", 160);
			}
			else if (!inHideoutOrMap)
			{
				button(sb, "Go to my hideout", "", 140);
			}
		}
		else if ((current == Step.TAKE_PORTAL) || (current == Step.CLEAR_MAP))
		{
			if (inHideoutOrMap)
			{
				button(sb, "The open map", "device", 140);
			}
		}
		sb.append(text(COLOR_HINT, "Type .hideout quest to see this window again.")).append("<br>");
		send(player, sb);
	}

	// =========================================================================
	// Building blocks
	// =========================================================================

	private static StringBuilder start(String title)
	{
		final StringBuilder sb = new StringBuilder(6000);
		sb.append("<html><title>Map Device</title><body><center>");
		sb.append("<table width=").append(CONTENT_WIDTH).append(" cellpadding=0 cellspacing=0 bgcolor=\"").append(BG_BAR).append("\">");
		sb.append("<tr><td height=6></td></tr>");
		sb.append("<tr><td align=center><font name=\"hs12\" color=\"").append(COLOR_TITLE).append("\">").append(title).append("</font></td></tr>");
		sb.append("<tr><td height=6></td></tr></table><br>");
		return sb;
	}

	private static void send(Player player, StringBuilder sb)
	{
		sb.append("</center></body></html>");
		final NpcHtmlMessage html = new NpcHtmlMessage(0);
		html.setWindowSize(WINDOW_WIDTH, WINDOW_HEIGHT);
		html.setHtml(sb.toString());
		player.sendPacket(html);
		player.sendPacket(ActionFailed.STATIC_PACKET);
	}

	private static void section(StringBuilder sb, String title)
	{
		sb.append("<table width=").append(CONTENT_WIDTH).append(" cellpadding=4 cellspacing=0 bgcolor=\"").append(BG_BAR).append("\"><tr><td><font color=\"LEVEL\">").append(title).append("</font></td></tr></table>");
	}

	private static void table(StringBuilder sb)
	{
		sb.append("<table width=").append(CONTENT_WIDTH).append(" cellpadding=3 cellspacing=0>");
	}

	private static void row(StringBuilder sb, String label, String value, boolean shaded)
	{
		final String bg = shaded ? " bgcolor=\"" + BG_CARD + "\"" : "";
		sb.append("<tr><td width=150").append(bg).append(">").append(text(COLOR_MUTED, label)).append("</td>");
		sb.append("<td width=240 align=right").append(bg).append(">").append(value).append("</td></tr>");
	}

	private static void button(StringBuilder sb, String label, String params, int width)
	{
		sb.append("<button value=\"").append(label).append("\" action=\"").append(COMMAND).append(params.isEmpty() ? "" : " " + params).append("\" width=").append(width).append(" height=25 back=\"L2UI_CT1.Button_DF_Down\" fore=\"L2UI_CT1.Button_DF\"><br1>");
	}

	private static String text(String color, String value)
	{
		return "<font color=\"" + color + "\">" + value + "</font>";
	}

	private static String format(long value)
	{
		return String.format("%,d", value);
	}

	private static String two(double value)
	{
		return String.format("%.2f", value);
	}

	/**
	 * @return a tier's strength range and what its monsters are, such as "3.0-4.5 party monsters"
	 */
	private static String strength(MapTier tier)
	{
		return text(strengthColor(tier), tier.scoreRange()) + text(COLOR_MUTED, " " + MonsterStrength.describe(tier.minScore()));
	}

	private static String strengthColor(MapTier tier)
	{
		return tier.minScore() < 1.5 ? COLOR_POSITIVE : tier.minScore() < 2.6 ? COLOR_VALUE : COLOR_NEGATIVE;
	}

	/**
	 * @return the strength of the monster kinds a map was filled with, such as "3.8-4.1"
	 */
	private static String kindsStrength(MapRun run)
	{
		final List<NpcTemplate> kinds = run.getMonsterKinds();
		if (kinds.isEmpty())
		{
			return run.getTier().scoreRange();
		}
		double low = Double.MAX_VALUE;
		double high = 0;
		for (NpcTemplate template : kinds)
		{
			final double score = MonsterStrength.getScore(template);
			low = Math.min(low, score);
			high = Math.max(high, score);
		}
		return MonsterStrength.format(low) + (high - low >= 0.05 ? "-" + MonsterStrength.format(high) : "");
	}

	private static String medalName()
	{
		return MapDeviceManager.getItemName(MapDeviceConfig.MEDAL_ITEM_ID);
	}
}
