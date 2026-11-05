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
package handlers.chat.commands.admin;

import java.util.List;
import java.util.Locale;
import java.util.StringTokenizer;

import org.l2jmobius.gameserver.data.xml.EventMapData;
import org.l2jmobius.gameserver.data.xml.MapDeviceData;
import org.l2jmobius.gameserver.handler.IAdminCommandHandler;
import org.l2jmobius.gameserver.managers.MapDeviceManager;
import org.l2jmobius.gameserver.model.WorldObject;
import org.l2jmobius.gameserver.model.actor.Player;
import org.l2jmobius.gameserver.model.actor.templates.NpcTemplate;
import org.l2jmobius.gameserver.model.eventmap.EventMap;
import org.l2jmobius.gameserver.model.eventmap.EventMapDevice;
import org.l2jmobius.gameserver.model.eventmap.EventMapType;
import org.l2jmobius.gameserver.model.item.enums.ItemProcessType;
import org.l2jmobius.gameserver.model.mapdevice.MapArena;
import org.l2jmobius.gameserver.model.mapdevice.MapRun;
import org.l2jmobius.gameserver.model.mapdevice.MapTier;
import org.l2jmobius.gameserver.model.mapdevice.MonsterStrength;
import org.l2jmobius.gameserver.network.serverpackets.ExLinkOpen;

import custom.Conquest.ConquestMarkers;
import custom.EventMaps.EventMapMarkers;
import custom.EventMaps.EventMapWeb;

/**
 * GM tools of the Map Device (see {@link MapDeviceManager}). The places maps open in are made on the event map page, like the battlefields of the events.
 * <ul>
 * <li>{@code //mapdevice} or {@code //mapdevice editor} - the event map page, on the Map Device points: tick a map for the Map Device, give it a fence, an entry, monster spots, a Map Guardian spot, its tiers and monsters.</li>
 * <li>{@code //mapdevice list} - every place a map can open in, from the page and from data/MapDevice.xml, and whether it is ready.</li>
 * <li>{@code //mapdevice show <map>} - draws a map's Map Device points in game ({@code //mapdevice hide} takes them away).</li>
 * <li>{@code //mapdevice test <map> [tier] [medals]} - opens a map there at the device of your own hideout, for nothing, to try it out.</li>
 * <li>{@code //mapdevice give <tier> [count]} - gives maps to your target (or you).</li>
 * <li>{@code //mapdevice pool <tier>} - the monsters a map of that tier may hold, with their strength score; {@code //mapdevice score} - the strength of your target.</li>
 * <li>{@code //mapdevice runs} - the maps open right now; {@code //mapdevice close <instance>} closes one.</li>
 * <li>{@code //mapdevice reload} - reads data/MapDevice.xml and the event maps again, and works the monster pools out again.</li>
 * </ul>
 * @author Mobius
 */
public class AdminMapDevice implements IAdminCommandHandler
{
	private static final String[] ADMIN_COMMANDS =
	{
		"admin_mapdevice"
	};

	@Override
	public boolean onCommand(String command, Player activeChar)
	{
		final StringTokenizer st = new StringTokenizer(command);
		st.nextToken();
		final String action = st.hasMoreTokens() ? st.nextToken().toLowerCase(Locale.ROOT) : "editor";
		final MapDeviceManager manager = MapDeviceManager.getInstance();
		switch (action)
		{
			case "editor":
			case "web":
			case "map":
			{
				final String url = EventMapWeb.getUrl(activeChar, EventMapType.MAPDEVICE);
				activeChar.sendSysMessage("Event map page, Map Device (works while you stay online):");
				activeChar.sendMessage(url);
				activeChar.sendPacket(new ExLinkOpen(url));
				break;
			}
			case "list":
			{
				activeChar.sendSysMessage("== Places a map can open in ==");
				for (EventMap map : EventMapData.getInstance().getMaps(EventMapType.MAPDEVICE))
				{
					final EventMapDevice device = map.mapDevice();
					final String state = map.isPlayable(EventMapType.MAPDEVICE) ? "ready" : !map.isUsedBy(EventMapType.MAPDEVICE) ? "off" : (map.barrier() == null ? "needs a fence" : "needs an entry");
					activeChar.sendSysMessage(map.id() + " - " + map.name() + " (page): " + state + ", tiers " + tiers(device.minTier(), device.maxTier()) + ", " + (device.monsters().isEmpty() ? "monsters of the tier" : device.monsters().size() + " monster kind(s)") + ", " + (device.packs().isEmpty() ? "spread over the field" : device.packs().size() + " monster spot(s)") + (device.monsterCount() > 0 ? ", " + device.monsterCount() + " monsters" : "") + ".");
				}
				for (MapArena arena : MapDeviceData.getInstance().getArenas())
				{
					activeChar.sendSysMessage(arena.name().replace(' ', '_') + " - " + arena.name() + " (MapDevice.xml): ready, tiers " + tiers(arena.minTier(), arena.maxTier()) + ".");
				}
				break;
			}
			case "show":
			{
				final EventMap map = st.hasMoreTokens() ? EventMapData.getInstance().getMap(st.nextToken()) : null;
				if (map == null)
				{
					activeChar.sendSysMessage("Usage: //mapdevice show <map> (a map of the event map page, see //mapdevice list)");
					return false;
				}
				EventMapMarkers.getInstance().show(activeChar, map, EventMapType.MAPDEVICE);
				activeChar.sendSysMessage(map.name() + " is drawn for you (again after every save). //mapdevice hide takes it away.");
				break;
			}
			case "hide":
			{
				EventMapMarkers.getInstance().hide(activeChar);
				break;
			}
			case "test":
			{
				if (!st.hasMoreTokens())
				{
					activeChar.sendSysMessage("Usage: //mapdevice test <map> [tier] [medals] - in your own hideout (.hideout)");
					return false;
				}
				final String arenaId = st.nextToken();
				final int tier = st.hasMoreTokens() ? parse(st.nextToken(), 1) : 1;
				final int medals = st.hasMoreTokens() ? parse(st.nextToken(), 0) : 0;
				final String denied = manager.openTestMap(activeChar, arenaId, tier, medals);
				activeChar.sendSysMessage(denied != null ? denied : "Opened a tier " + tier + " test map in " + arenaId + ": take a portal round your device.");
				break;
			}
			case "give":
			{
				final MapTier tier = st.hasMoreTokens() ? MapDeviceData.getInstance().getTier(parse(st.nextToken(), -1)) : null;
				if (tier == null)
				{
					activeChar.sendSysMessage("Usage: //mapdevice give <tier 1-" + MapDeviceData.getInstance().getMaxTier() + "> [count]");
					return false;
				}
				final int count = st.hasMoreTokens() ? Math.max(1, Math.min(1000, parse(st.nextToken(), 1))) : 1;
				final Player target = (activeChar.getTarget() != null) && activeChar.getTarget().isPlayer() ? activeChar.getTarget().asPlayer() : activeChar;
				target.addItem(ItemProcessType.REWARD, tier.itemId(), count, activeChar, true);
				activeChar.sendSysMessage("Gave " + count + " " + MapDeviceManager.getItemName(tier.itemId()) + " to " + target.getName() + ".");
				break;
			}
			case "pool":
			{
				final MapTier tier = st.hasMoreTokens() ? MapDeviceData.getInstance().getTier(parse(st.nextToken(), -1)) : null;
				if (tier == null)
				{
					activeChar.sendSysMessage("Usage: //mapdevice pool <tier 1-" + MapDeviceData.getInstance().getMaxTier() + ">");
					return false;
				}
				final List<NpcTemplate> pool = manager.getMonsterPool(tier);
				activeChar.sendSysMessage("== Tier " + tier.tier() + ": levels " + tier.minLevel() + "-" + tier.maxLevel() + ", strength " + tier.scoreRange() + " (" + MonsterStrength.describe(tier.minScore()) + "), " + pool.size() + " kind(s) ==");
				for (NpcTemplate template : pool)
				{
					activeChar.sendSysMessage(template.getId() + " " + template.getName() + " Lv. " + template.getLevel() + ": strength " + MonsterStrength.format(MonsterStrength.getScore(template)) + " (" + relativeStats(template) + ")");
				}
				break;
			}
			case "score":
			{
				final WorldObject target = activeChar.getTarget();
				if ((target == null) || !target.isNpc())
				{
					activeChar.sendSysMessage("Usage: target a monster, then //mapdevice score");
					return false;
				}
				final NpcTemplate template = target.asNpc().getTemplate();
				final double score = MonsterStrength.getScore(template);
				activeChar.sendSysMessage(template.getName() + " (" + template.getId() + ") Lv. " + template.getLevel() + ": strength " + MonsterStrength.format(score) + ", " + MonsterStrength.describe(score) + " (" + relativeStats(template) + ").");
				final StringBuilder tiers = new StringBuilder();
				for (MapTier tier : MapDeviceData.getInstance().getTiers().values())
				{
					if (tier.allowsLevel(template.getLevel()) && tier.allowsScore(score))
					{
						tiers.append(tiers.length() == 0 ? "" : ", ").append(tier.tier());
					}
				}
				activeChar.sendSysMessage("Its level and strength fit tier(s): " + (tiers.length() == 0 ? "none" : tiers) + ". It drops tier " + MapDeviceData.getInstance().getDropTier(template.getLevel(), score) + " maps (before champion and other bonuses).");
				break;
			}
			case "runs":
			{
				if (MapDeviceManager.getRuns().isEmpty())
				{
					activeChar.sendSysMessage("No map is open.");
					break;
				}
				for (MapRun run : MapDeviceManager.getRuns())
				{
					activeChar.sendSysMessage("Instance " + run.getInstanceId() + ": " + run.getOwnerName() + ", tier " + run.getTier().tier() + " in " + run.getArena().name() + " (" + run.getArena().id() + "), " + run.getStatus() + ", " + run.getMonsters().size() + "/" + run.getMonsterTotal() + " monsters, " + run.getPortals().size() + " portal(s), " + run.getPlayersInside().size() + " inside, " + (run.getRemainingSeconds() / 60) + " min left.");
				}
				break;
			}
			case "close":
			{
				final int instanceId = st.hasMoreTokens() ? parse(st.nextToken(), 0) : activeChar.getInstanceId();
				final MapRun run = MapDeviceManager.getRun(instanceId);
				if (run == null)
				{
					activeChar.sendSysMessage("Usage: //mapdevice close <instance> (see //mapdevice runs), or stand in the map.");
					return false;
				}
				manager.closeRun(run, "The map was closed by a GM.");
				activeChar.sendSysMessage("Closed the map of instance " + instanceId + ".");
				break;
			}
			case "reload":
			{
				MapDeviceData.getInstance().load();
				EventMapData.getInstance().load();
				EventMapMarkers.getInstance().refreshAll();
				ConquestMarkers.getInstance().refreshAll();
				manager.clearMonsterPools();
				activeChar.sendSysMessage("Read data/MapDevice.xml (" + MapDeviceData.getInstance().getTiers().size() + " tiers, " + MapDeviceData.getInstance().getArenas().size() + " arenas) and " + EventMapData.getInstance().getMaps().size() + " event map(s) again; " + MapDeviceData.getInstance().getEditorArenas().size() + " of them are ready for the Map Device. Maps already open keep their place.");
				break;
			}
			default:
			{
				activeChar.sendSysMessage("Usage: //mapdevice [editor|list|show <map>|hide|test <map> [tier] [medals]|give <tier> [count]|pool <tier>|score|runs|close <instance>|reload]");
				return false;
			}
		}
		return true;
	}

	/**
	 * @return each stat against the standard monster of the level, such as "HP x13.9, P. Atk. x9.9, ..."
	 */
	private static String relativeStats(NpcTemplate template)
	{
		final double[] relative = MonsterStrength.getRelativeStats(template);
		final StringBuilder sb = new StringBuilder();
		for (MonsterStrength.Stat stat : MonsterStrength.Stat.values())
		{
			final double ratio = relative[stat.ordinal()];
			if (!Double.isNaN(ratio))
			{
				sb.append(sb.length() == 0 ? "" : ", ").append(stat.getDisplayName()).append(" x").append(MonsterStrength.format(ratio));
			}
		}
		return sb.toString();
	}

	private static String tiers(int min, int max)
	{
		return (min <= 0 ? "1" : String.valueOf(min)) + "-" + (max <= 0 ? String.valueOf(MapDeviceData.getInstance().getMaxTier()) : String.valueOf(max));
	}

	private static int parse(String text, int fallback)
	{
		try
		{
			return Integer.parseInt(text);
		}
		catch (NumberFormatException e)
		{
			return fallback;
		}
	}

	@Override
	public String[] getCommandList()
	{
		return ADMIN_COMMANDS;
	}
}
