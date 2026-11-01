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

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import org.l2jmobius.gameserver.data.xml.EventMapData;
import org.l2jmobius.gameserver.config.custom.EventRotationConfig;
import org.l2jmobius.gameserver.handler.IAdminCommandHandler;
import org.l2jmobius.gameserver.managers.EventRotationManager;
import org.l2jmobius.gameserver.managers.TeamVsTeamManager;
import org.l2jmobius.gameserver.managers.TeamVsTeamManager.Match;
import org.l2jmobius.gameserver.managers.TeamVsTeamManager.Mode;
import org.l2jmobius.gameserver.model.Location;
import org.l2jmobius.gameserver.model.actor.Player;
import org.l2jmobius.gameserver.model.eventmap.EventBarrier;
import org.l2jmobius.gameserver.model.eventmap.EventMap;
import org.l2jmobius.gameserver.model.eventmap.EventMapType;
import org.l2jmobius.gameserver.network.serverpackets.ExLinkOpen;
import org.l2jmobius.gameserver.network.serverpackets.NpcHtmlMessage;

import custom.Conquest.ConquestMarkers;
import custom.EventMaps.EventMapMarkers;
import custom.EventMaps.EventMapWeb;

/**
 * Team vs Team (see {@link TeamVsTeamManager}):
 * <ul>
 * <li>//tvt [status|detail]: registration and the matches going on (detail: every fighter).</li>
 * <li>//tvt open [map] | close: opens the registration now (on that map, else one of TvTMaps by chance), or closes it now (the matches start).</li>
 * <li>//tvt simulate [level] [map]: a match of fake players only, of the bracket of that level (the GM's by default), to watch.</li>
 * <li>//tvt stop [match]: ends a match (all of them by default), without rewards.</li>
 * <li>//tvt watch [match]: goes into a match's instance, between the bases, to watch it (the fighters leave a GM outside the match alone).</li>
 * <li>//tvt dm open [map] | simulate [level] [map]: the same for a Deathmatch (every one for themselves); close, stop and watch work for both.</li>
 * <li>//tvt mayhem open [map] | simulate [level] [map] (or pdm): the same for Deathmatch: Mayhem (blank passive trees, a passive picked on each kill).</li>
 * <li>//tvt rotation [next &lt;tvt|conquest|deathmatch|racewars&gt;]: the hourly rotation of the events (see {@link EventRotationManager}), and which event takes the next turn.</li>
 * </ul>
 * And the event maps ({@link EventMapData}, data/maps), shared with Conquest:
 * <ul>
 * <li>//tvt map [tvt|conquest|dm|racewars] - the event map page (web, a link that works while the GM is online), on that event's points</li>
 * <li>//tvt maps - the maps and the events they are used in</li>
 * <li>//tvt show &lt;map&gt; [tvt|conquest|dm|racewars] | hide - draw a map's waypoints in game (one event's, or every event's), or take them away</li>
 * <li>//tvt tp &lt;map&gt; &lt;blue|red|dm|duel|final&gt; &lt;number&gt; - go to a Team vs Team base, a Deathmatch spawn point or a Race Wars start (duel: of a race-vs-race match, final: of the final)</li>
 * <li>//tvt set &lt;map&gt; &lt;blue|red|dm|duel|final&gt; &lt;number|new&gt; - your position (and heading) becomes that point (new adds one); the height is the ground's</li>
 * <li>//tvt remove &lt;map&gt; &lt;blue|red|dm|duel|final&gt; &lt;number&gt; - take a point away</li>
 * <li>//tvt fence &lt;map&gt; [radius x] [radius y] | off - the fence round the field (Team vs Team and Deathmatch): an ellipse round where you stand</li>
 * <li>//tvt reload - read data/maps again</li>
 * </ul>
 */
public class AdminTeamVsTeam implements IAdminCommandHandler
{
	private static final String[] ADMIN_COMMANDS =
	{
		"admin_tvt"
	};

	private static final String USAGE = "//tvt open [map] | close | simulate [level] [map] | dm open [map] | dm simulate [level] [map] | mayhem open [map] | mayhem simulate [level] [map] | rotation [next <event>] | stop [match] | watch [match] | detail | map [tvt|conquest|dm|racewars] | maps | show <map> [event] | hide | tp <map> <blue|red|dm|duel|final> <n> | set <map> <blue|red|dm|duel|final> <n|new> | remove <map> <blue|red|dm|duel|final> <n> | fence <map> [rx] [ry] | off | reload";

	@Override
	public boolean onCommand(String command, Player activeChar)
	{
		final TeamVsTeamManager manager = TeamVsTeamManager.getInstance();
		String[] args = command.trim().split("\\s+");
		// //tvt dm <open|simulate> ...: the same commands for a Deathmatch.
		Mode mode = Mode.TVT;
		final boolean passive = (args.length > 2) && (args[1].equalsIgnoreCase("mayhem") || args[1].equalsIgnoreCase("pdm") || args[1].equalsIgnoreCase("passivedeathmatch"));
		if ((args.length > 2) && (passive || args[1].equalsIgnoreCase("dm") || args[1].equalsIgnoreCase("deathmatch")))
		{
			mode = passive ? Mode.PASSIVE_DEATHMATCH : Mode.DEATHMATCH;
			final String[] rest = new String[args.length - 1];
			rest[0] = args[0];
			System.arraycopy(args, 2, rest, 1, args.length - 2);
			args = rest;
		}
		final String action = args.length > 1 ? args[1].toLowerCase() : "status";
		switch (action)
		{
			case "open":
			{
				EventMap map = null;
				if (args.length > 2)
				{
					map = findMap(activeChar, args[2]);
					if (map == null)
					{
						return false;
					}
				}
				final String refusal = manager.openRegistration(mode, map);
				activeChar.sendMessage(refusal == null ? mode.getTitle() + " registration opened on " + manager.getRegistrationMap().name() + "." : "Could not open: " + refusal + ".");
				break;
			}
			case "rotation":
			{
				final EventRotationManager rotation = EventRotationManager.getInstance();
				if ((args.length > 3) && args[2].equalsIgnoreCase("next"))
				{
					final String event = args[3].toLowerCase().equals("dm") ? "deathmatch" : (args[3].toLowerCase().equals("pdm") || args[3].toLowerCase().equals("passivedeathmatch")) ? "mayhem" : args[3].toLowerCase();
					activeChar.sendMessage(rotation.setNext(event) ? EventRotationManager.getTitle(event) + " takes the next turn." : "Not in EventRotationOrder: " + args[3] + " (" + String.join(", ", EventRotationConfig.ORDER) + ").");
				}
				for (String line : rotation.getStatus())
				{
					activeChar.sendMessage(line);
				}
				break;
			}
			case "close":
			{
				if (!manager.isRegistrationOpen())
				{
					activeChar.sendMessage("Registration is not open.");
					break;
				}
				manager.closeRegistration();
				activeChar.sendMessage("TvT registration closed.");
				break;
			}
			case "simulate":
			{
				// //tvt simulate [level] [map], in either order.
				int level = activeChar.getLevel();
				EventMap map = null;
				for (int i = 2; i < args.length; i++)
				{
					if (args[i].matches("\\d+"))
					{
						level = Integer.parseInt(args[i]);
					}
					else
					{
						map = findMap(activeChar, args[i]);
						if (map == null)
						{
							return false;
						}
					}
				}
				activeChar.sendMessage(manager.simulate(mode, level, map));
				break;
			}
			case "stop":
			{
				activeChar.sendMessage(manager.stopMatch(args.length > 2 ? parse(args[2], 0) : 0));
				break;
			}
			case "watch":
			{
				final int id = args.length > 2 ? parse(args[2], 0) : 0;
				Match match = null;
				for (Match running : manager.getMatches())
				{
					if ((id == 0) || (running.getId() == id))
					{
						match = running;
						break;
					}
				}
				if (match == null)
				{
					activeChar.sendMessage("No such match.");
					break;
				}
				manager.watch(activeChar, match);
				activeChar.sendMessage("Watching " + match.getMode().getTitle() + " match " + match.getId() + " (levels " + match.getBracketName() + ", " + match.getMap().name() + "). //instance or a teleport takes you back out.");
				break;
			}
			case "map":
			case "web":
			{
				// The event maps are shared with Conquest: //tvt map dm opens the page on the Deathmatch points.
				final EventMapType type = (args.length > 2) && (EventMapType.fromKey(args[2]) != null) ? EventMapType.fromKey(args[2]) : mode.getMapType();
				final String url = EventMapWeb.getUrl(activeChar, type);
				activeChar.sendSysMessage("Event map page, " + type.getTitle() + " (works while you stay online):");
				activeChar.sendMessage(url);
				activeChar.sendPacket(new ExLinkOpen(url));
				break;
			}
			case "maps":
			case "list":
			{
				final List<EventMap> maps = EventMapData.getInstance().getMaps();
				if (maps.isEmpty())
				{
					activeChar.sendSysMessage("No event maps. Make one on the map page: //tvt map");
					break;
				}
				for (EventMap map : maps)
				{
					final StringBuilder uses = new StringBuilder();
					for (EventMapType type : EventMapType.values())
					{
						if (map.belongsTo(type))
						{
							uses.append(uses.length() > 0 ? ", " : "").append(type.getTitle()).append(map.isPlayable(type) ? "" : map.isUsedBy(type) ? " (incomplete)" : " (off)");
						}
					}
					activeChar.sendSysMessage(map.id() + " - " + map.name() + ": " + (uses.length() > 0 ? uses : "no event") + ". TvT " + map.blue().size() + " blue / " + map.red().size() + " red, Deathmatch " + map.deathmatch().size() + " spawn(s), " + (map.barrier() != null ? "a fence" : "no fence") + ", " + map.doors().size() + " door(s).");
				}
				break;
			}
			case "show":
			{
				final EventMap map = args.length > 2 ? findMap(activeChar, args[2]) : null;
				if (map == null)
				{
					if (args.length <= 2)
					{
						activeChar.sendSysMessage("Usage: //tvt show <map> [tvt|conquest|dm|racewars]");
					}
					return false;
				}
				final EventMapType type = args.length > 3 ? EventMapType.fromKey(args[3]) : null;
				EventMapMarkers.getInstance().show(activeChar, map, type);
				activeChar.sendSysMessage(map.name() + " is drawn" + (type != null ? " (" + type.getTitle() + ")" : "") + ": TvT bases blue and red (the small ring where they spawn), Conquest spawns light blue and red, capture points gold, Deathmatch spawns violet, the fence orange, the edge white. //tvt hide takes it away.");
				break;
			}
			case "hide":
			{
				EventMapMarkers.getInstance().hide(activeChar);
				activeChar.sendSysMessage("The event map waypoints are gone.");
				break;
			}
			case "tp":
			case "teleport":
			{
				final EventMap map = args.length > 2 ? findMap(activeChar, args[2]) : null;
				final String kind = map != null ? findKind(activeChar, args, 3) : null;
				if (kind == null)
				{
					return false;
				}
				final List<Location> spawns = getSpawns(map, kind);
				final int index = args.length > 4 ? parse(args[4], 0) - 1 : -1;
				if ((index < 0) || (index >= spawns.size()))
				{
					activeChar.sendSysMessage(describeSpawns(map, kind));
					return false;
				}
				final Location spawn = spawns.get(index);
				activeChar.teleToLocation(spawn.getX(), spawn.getY(), spawn.getZ(), spawn.getHeading(), 0);
				activeChar.sendSysMessage(map.name() + ": " + describe(kind) + " " + (index + 1) + ".");
				break;
			}
			case "set":
			{
				return setSpawn(activeChar, args);
			}
			case "remove":
			{
				return removeSpawn(activeChar, args);
			}
			case "fence":
			case "barrier":
			{
				return setFence(activeChar, args);
			}
			case "reload":
			{
				final EventMapData data = EventMapData.getInstance();
				data.load();
				EventMapMarkers.getInstance().refreshAll();
				ConquestMarkers.getInstance().refreshAll();
				activeChar.sendSysMessage("Read " + data.getMaps().size() + " event map(s) again: " + data.getPlayableMaps(EventMapType.TVT).size() + " ready for Team vs Team, " + data.getPlayableMaps(EventMapType.DEATHMATCH).size() + " for Deathmatch, " + data.getPlayableMaps(EventMapType.CONQUEST).size() + " for Conquest.");
				break;
			}
			case "detail":
			case "status":
			default:
			{
				final StringBuilder sb = new StringBuilder("<html><title>Team vs Team</title><body>");
				for (String line : manager.getStatus(action.equals("detail")))
				{
					sb.append(line.replace("<", "&lt;")).append("<br1>");
				}
				sb.append("<br>").append(USAGE.replace("<", "&lt;")).append("</body></html>");
				activeChar.sendPacket(new NpcHtmlMessage(0, sb.toString()));
				break;
			}
		}
		return true;
	}

	/** //tvt set &lt;map&gt; &lt;blue|red|dm|duel|final&gt; &lt;n|new&gt;: the Team vs Team bases, or the Deathmatch spawn points */
	private static boolean setSpawn(Player activeChar, String[] args)
	{
		final EventMap map = args.length > 2 ? findMap(activeChar, args[2]) : null;
		final String kind = map != null ? findKind(activeChar, args, 3) : null;
		if (kind == null)
		{
			return false;
		}
		final String key = args.length > 4 ? args[4] : "new";
		final List<Location> spawns = new ArrayList<>(getSpawns(map, kind));
		final Location here = new Location(activeChar.getX(), activeChar.getY(), activeChar.getZ(), activeChar.getHeading());
		final int limit = kind.equals("dm") ? EventMapData.MAX_DEATHMATCH_SPAWNS : kind.equals("duel") ? EventMapData.MAX_RACEWARS_DUEL_SPAWNS : kind.equals("final") ? EventMapData.MAX_RACEWARS_FINAL_SPAWNS : EventMapData.MAX_TVT_SPAWNS;
		final int number;
		if (key.equalsIgnoreCase("new"))
		{
			if (spawns.size() >= limit)
			{
				activeChar.sendSysMessage("At most " + limit + " " + describe(kind) + "s.");
				return false;
			}
			spawns.add(here);
			number = spawns.size();
		}
		else
		{
			final int index = parse(key, 0) - 1;
			if ((index < 0) || (index >= spawns.size()))
			{
				activeChar.sendSysMessage("No such point (new adds one). " + describeSpawns(map, kind));
				return false;
			}
			spawns.set(index, here);
			number = index + 1;
		}

		if (!save(activeChar, withSpawns(map, kind, spawns)))
		{
			return false;
		}
		activeChar.sendSysMessage(map.name() + ": " + describe(kind) + " " + number + " set at " + here.getX() + ", " + here.getY() + ", " + here.getZ() + " (heading " + here.getHeading() + ").");
		final EventMapType event = getEvent(kind);
		if (!map.isUsedBy(event))
		{
			activeChar.sendSysMessage("The map is not ticked for " + event.getTitle() + " yet: tick it on the map page (//tvt map " + event.getKey() + ").");
		}
		if ((here.getX() < map.minX()) || (here.getX() > map.maxX()) || (here.getY() < map.minY()) || (here.getY() > map.maxY()))
		{
			activeChar.sendSysMessage("It is outside the map's area: widen the area on the map page (//tvt map).");
		}
		if ((map.barrier() != null) && !map.barrier().contains(here, 0))
		{
			activeChar.sendSysMessage("It is beyond the map's fence: move the fence (//tvt fence " + map.id() + ") or the point.");
		}
		if (activeChar.getInstanceId() != 0)
		{
			activeChar.sendSysMessage("You are in an instance: the point is kept as a place in the open world.");
		}
		return true;
	}

	/** //tvt remove &lt;map&gt; &lt;blue|red|dm|duel|final&gt; &lt;n&gt; */
	private static boolean removeSpawn(Player activeChar, String[] args)
	{
		final EventMap map = args.length > 2 ? findMap(activeChar, args[2]) : null;
		final String kind = map != null ? findKind(activeChar, args, 3) : null;
		if (kind == null)
		{
			return false;
		}
		final List<Location> spawns = new ArrayList<>(getSpawns(map, kind));
		final int index = args.length > 4 ? parse(args[4], 0) - 1 : -1;
		if ((index < 0) || (index >= spawns.size()))
		{
			activeChar.sendSysMessage("No such point. " + describeSpawns(map, kind));
			return false;
		}
		spawns.remove(index);
		if (!save(activeChar, withSpawns(map, kind, spawns)))
		{
			return false;
		}
		activeChar.sendSysMessage(map.name() + ": removed " + describe(kind) + " " + (index + 1) + ".");
		return true;
	}

	/** //tvt fence &lt;map&gt; [radius x] [radius y] | off */
	private static boolean setFence(Player activeChar, String[] args)
	{
		final EventMap map = args.length > 2 ? findMap(activeChar, args[2]) : null;
		if (map == null)
		{
			if (args.length <= 2)
			{
				activeChar.sendSysMessage("Usage: //tvt fence <map> [radius x] [radius y] | off");
			}
			return false;
		}

		final EventBarrier barrier;
		if ((args.length > 3) && args[3].equalsIgnoreCase("off"))
		{
			barrier = null;
		}
		else
		{
			final EventBarrier old = map.barrier();
			final int radiusX = args.length > 3 ? parse(args[3], -1) : old != null ? old.radiusX() : 5000;
			final int radiusY = args.length > 4 ? parse(args[4], -1) : args.length > 3 ? radiusX : old != null ? old.radiusY() : 5000;
			if ((radiusX < EventBarrier.MIN_RADIUS) || (radiusY < EventBarrier.MIN_RADIUS))
			{
				activeChar.sendSysMessage("The fence's radii are at least " + EventBarrier.MIN_RADIUS + ". Usage: //tvt fence <map> [radius x] [radius y] | off");
				return false;
			}
			barrier = new EventBarrier(activeChar.getX(), activeChar.getY(), radiusX, radiusY);
		}

		if (!save(activeChar, map.withBarrier(barrier)))
		{
			return false;
		}
		if (barrier == null)
		{
			activeChar.sendSysMessage(map.name() + " has no fence now.");
			return true;
		}
		activeChar.sendSysMessage(map.name() + ": the fence goes round " + barrier.x() + ", " + barrier.y() + ", " + barrier.radiusX() + " along x and " + barrier.radiusY() + " along y (Team vs Team, Deathmatch and Race Wars).");
		int outside = 0;
		final List<Location> all = new ArrayList<>(map.blue());
		all.addAll(map.red());
		all.addAll(map.deathmatch());
		all.addAll(map.raceWarsDuel());
		all.addAll(map.raceWarsFinal());
		for (Location spawn : all)
		{
			outside += barrier.contains(spawn, 0) ? 0 : 1;
		}
		if (outside > 0)
		{
			activeChar.sendSysMessage(outside + " TvT base(s), Deathmatch spawn point(s) or Race Wars start(s) are beyond it: make it wider or move it.");
		}
		return true;
	}

	private static boolean save(Player activeChar, EventMap map)
	{
		try
		{
			EventMapData.getInstance().save(map);
		}
		catch (IOException | IllegalArgumentException e)
		{
			activeChar.sendSysMessage("Could not save " + map.id() + ": " + e.getMessage());
			return false;
		}
		EventMapMarkers.getInstance().refresh(map.id());
		ConquestMarkers.getInstance().refresh(map.id());
		return true;
	}

	/** @return the points of a kind: blue or red (Team vs Team bases), dm (Deathmatch spawn points), duel or final (Race Wars starts) */
	private static List<Location> getSpawns(EventMap map, String kind)
	{
		switch (kind)
		{
			case "dm":
			{
				return map.deathmatch();
			}
			case "duel":
			{
				return map.raceWarsDuel();
			}
			case "final":
			{
				return map.raceWarsFinal();
			}
			default:
			{
				return map.getSpawns(kind.equals("blue"));
			}
		}
	}

	/** @return the event a kind of point belongs to */
	private static EventMapType getEvent(String kind)
	{
		return kind.equals("dm") ? EventMapType.DEATHMATCH : kind.equals("duel") || kind.equals("final") ? EventMapType.RACEWARS : EventMapType.TVT;
	}

	private static EventMap withSpawns(EventMap map, String kind, List<Location> spawns)
	{
		if (kind.equals("dm"))
		{
			return map.withDeathmatch(spawns);
		}
		if (kind.equals("duel"))
		{
			return map.withRaceWars(spawns, map.raceWarsFinal());
		}
		if (kind.equals("final"))
		{
			return map.withRaceWars(map.raceWarsDuel(), spawns);
		}
		return kind.equals("blue") ? map.withSpawns(spawns, map.red()) : map.withSpawns(map.blue(), spawns);
	}

	private static String describe(String kind)
	{
		return kind.equals("dm") ? "Deathmatch spawn" : kind.equals("duel") ? "Race Wars duel start" : kind.equals("final") ? "Race Wars final start" : kind.equals("blue") ? "TvT Blue base" : "TvT Red base";
	}

	private static String describeSpawns(EventMap map, String kind)
	{
		final int count = getSpawns(map, kind).size();
		return count == 0 ? map.name() + " has no " + describe(kind) + "." : describe(kind) + "s: 1 to " + count + ".";
	}

	private static EventMap findMap(Player activeChar, String id)
	{
		final EventMap map = EventMapData.getInstance().getMap(id);
		if (map == null)
		{
			final StringBuilder ids = new StringBuilder();
			for (EventMap other : EventMapData.getInstance().getMaps())
			{
				ids.append(ids.length() > 0 ? ", " : "").append(other.id());
			}
			activeChar.sendSysMessage("No event map " + id + ". Maps: " + (ids.length() > 0 ? ids : "none") + ".");
		}
		return map;
	}

	/** @return blue, red (Team vs Team bases), dm (Deathmatch spawn points), duel or final (Race Wars starts), {@code null} (after telling the GM) for none */
	private static String findKind(Player activeChar, String[] args, int index)
	{
		final String key = args.length > index ? args[index].toLowerCase() : "";
		if (key.equals("blue") || key.equals("b"))
		{
			return "blue";
		}
		if (key.equals("red") || key.equals("r"))
		{
			return "red";
		}
		if (key.equals("dm") || key.equals("deathmatch"))
		{
			return "dm";
		}
		if (key.equals("duel") || key.equals("final"))
		{
			return key;
		}
		activeChar.sendSysMessage("Which points: blue, red (Team vs Team bases), dm (Deathmatch spawns), duel or final (Race Wars starts of a race-vs-race match, of the final)? Conquest points: //conquest set.");
		return null;
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
