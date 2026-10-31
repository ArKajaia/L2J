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

import org.l2jmobius.gameserver.data.xml.TvTMapData;
import org.l2jmobius.gameserver.config.custom.EventRotationConfig;
import org.l2jmobius.gameserver.handler.IAdminCommandHandler;
import org.l2jmobius.gameserver.managers.EventRotationManager;
import org.l2jmobius.gameserver.managers.TeamVsTeamManager;
import org.l2jmobius.gameserver.managers.TeamVsTeamManager.Match;
import org.l2jmobius.gameserver.managers.TeamVsTeamManager.Mode;
import org.l2jmobius.gameserver.model.Location;
import org.l2jmobius.gameserver.model.actor.Player;
import org.l2jmobius.gameserver.model.tvt.TvTBarrier;
import org.l2jmobius.gameserver.model.tvt.TvTMap;
import org.l2jmobius.gameserver.network.serverpackets.ExLinkOpen;
import org.l2jmobius.gameserver.network.serverpackets.NpcHtmlMessage;

import custom.TeamVsTeam.TvTMarkers;
import custom.TeamVsTeam.TvTWeb;

/**
 * Team vs Team (see {@link TeamVsTeamManager}):
 * <ul>
 * <li>//tvt [status|detail]: registration and the matches going on (detail: every fighter).</li>
 * <li>//tvt open [map] | close: opens the registration now (on that map, else one of TvTMaps by chance), or closes it now (the matches start).</li>
 * <li>//tvt simulate [level] [map]: a match of fake players only, of the bracket of that level (the GM's by default), to watch.</li>
 * <li>//tvt stop [match]: ends a match (all of them by default), without rewards.</li>
 * <li>//tvt watch [match]: goes into a match's instance, between the bases, to watch it (the fighters leave a GM outside the match alone).</li>
 * <li>//tvt dm open [map] | simulate [level] [map]: the same for a Deathmatch (every one for themselves); close, stop and watch work for both.</li>
 * <li>//tvt rotation [next &lt;tvt|conquest|deathmatch&gt;]: the hourly rotation of the events (see {@link EventRotationManager}), and which event takes the next turn.</li>
 * </ul>
 * And the maps ({@link TvTMapData}, data/tvt), like the Conquest ones:
 * <ul>
 * <li>//tvt map - the map page (web, a link that works while the GM is online)</li>
 * <li>//tvt maps - the maps</li>
 * <li>//tvt show &lt;map&gt; | hide - draw a map's waypoints in game, or take them away</li>
 * <li>//tvt tp &lt;map&gt; &lt;blue|red&gt; &lt;number&gt; - go to a spawn point</li>
 * <li>//tvt set &lt;map&gt; &lt;blue|red&gt; &lt;number|new&gt; - your position (and heading) becomes that spawn point (new adds one)</li>
 * <li>//tvt remove &lt;map&gt; &lt;blue|red&gt; &lt;number&gt; - take a spawn point away</li>
 * <li>//tvt fence &lt;map&gt; [radius x] [radius y] | off - the fence round the field: an ellipse round where you stand</li>
 * <li>//tvt reload - read data/tvt again</li>
 * </ul>
 */
public class AdminTeamVsTeam implements IAdminCommandHandler
{
	private static final String[] ADMIN_COMMANDS =
	{
		"admin_tvt"
	};

	private static final String USAGE = "//tvt open [map] | close | simulate [level] [map] | dm open [map] | dm simulate [level] [map] | rotation [next <event>] | stop [match] | watch [match] | detail | map | maps | show <map> | hide | tp <map> <blue|red> <n> | set <map> <blue|red> <n|new> | remove <map> <blue|red> <n> | fence <map> [rx] [ry] | off | reload";

	@Override
	public boolean onCommand(String command, Player activeChar)
	{
		final TeamVsTeamManager manager = TeamVsTeamManager.getInstance();
		String[] args = command.trim().split("\\s+");
		// //tvt dm <open|simulate> ...: the same commands for a Deathmatch.
		Mode mode = Mode.TVT;
		if ((args.length > 2) && (args[1].equalsIgnoreCase("dm") || args[1].equalsIgnoreCase("deathmatch")))
		{
			mode = Mode.DEATHMATCH;
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
				TvTMap map = null;
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
					final String event = args[3].toLowerCase().equals("dm") ? "deathmatch" : args[3].toLowerCase();
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
				TvTMap map = null;
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
				final String url = TvTWeb.getUrl(activeChar);
				activeChar.sendSysMessage("TvT map page (works while you stay online):");
				activeChar.sendMessage(url);
				activeChar.sendPacket(new ExLinkOpen(url));
				break;
			}
			case "maps":
			case "list":
			{
				final TvTMapData data = TvTMapData.getInstance();
				final List<TvTMap> maps = data.getMaps();
				if (maps.isEmpty())
				{
					activeChar.sendSysMessage("No TvT maps. Make one on the map page: //tvt map");
					break;
				}
				final List<TvTMap> enabled = data.getEnabledMaps();
				for (TvTMap map : maps)
				{
					activeChar.sendSysMessage(map.id() + " - " + map.name() + ": " + map.blue().size() + " blue, " + map.red().size() + " red spawn point(s), " + (map.barrier() != null ? "a fence" : "no fence") + ", " + map.doors().size() + " door(s)" + (enabled.contains(map) ? "" : map.enabled() ? " (incomplete)" : " (disabled)"));
				}
				break;
			}
			case "show":
			{
				final TvTMap map = args.length > 2 ? findMap(activeChar, args[2]) : null;
				if (map == null)
				{
					if (args.length <= 2)
					{
						activeChar.sendSysMessage("Usage: //tvt show <map>");
					}
					return false;
				}
				TvTMarkers.getInstance().show(activeChar, map);
				activeChar.sendSysMessage(map.name() + " is drawn: blue and red rings are the bases (the small ring where they spawn), orange the fence, white the edge. //tvt hide takes it away.");
				break;
			}
			case "hide":
			{
				TvTMarkers.getInstance().hide(activeChar);
				activeChar.sendSysMessage("The TvT waypoints are gone.");
				break;
			}
			case "tp":
			case "teleport":
			{
				final TvTMap map = args.length > 2 ? findMap(activeChar, args[2]) : null;
				final Boolean blue = map != null ? findTeam(activeChar, args, 3) : null;
				if (blue == null)
				{
					return false;
				}
				final List<Location> spawns = map.getSpawns(blue);
				final int index = args.length > 4 ? parse(args[4], 0) - 1 : -1;
				if ((index < 0) || (index >= spawns.size()))
				{
					activeChar.sendSysMessage(describeSpawns(map, blue));
					return false;
				}
				final Location spawn = spawns.get(index);
				activeChar.teleToLocation(spawn.getX(), spawn.getY(), spawn.getZ(), spawn.getHeading(), 0);
				activeChar.sendSysMessage(map.name() + ": " + (blue ? "Blue" : "Red") + " base " + (index + 1) + ".");
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
				final TvTMapData data = TvTMapData.getInstance();
				data.load();
				TvTMarkers.getInstance().refreshAll();
				activeChar.sendSysMessage("Read " + data.getMaps().size() + " TvT map(s) again, " + data.getEnabledMaps().size() + " ready for the event.");
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

	/** //tvt set &lt;map&gt; &lt;blue|red&gt; &lt;n|new&gt; */
	private static boolean setSpawn(Player activeChar, String[] args)
	{
		final TvTMap map = args.length > 2 ? findMap(activeChar, args[2]) : null;
		final Boolean blue = map != null ? findTeam(activeChar, args, 3) : null;
		if (blue == null)
		{
			return false;
		}
		final String key = args.length > 4 ? args[4] : "new";
		final List<Location> spawns = new ArrayList<>(map.getSpawns(blue));
		final Location here = new Location(activeChar.getX(), activeChar.getY(), activeChar.getZ(), activeChar.getHeading());
		final int number;
		if (key.equalsIgnoreCase("new"))
		{
			if (spawns.size() >= TvTMapData.MAX_SPAWNS)
			{
				activeChar.sendSysMessage("A team has at most " + TvTMapData.MAX_SPAWNS + " spawn points.");
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
				activeChar.sendSysMessage("No such spawn point (new adds one). " + describeSpawns(map, blue));
				return false;
			}
			spawns.set(index, here);
			number = index + 1;
		}

		final TvTMap changed = blue ? map.withSpawns(spawns, map.red()) : map.withSpawns(map.blue(), spawns);
		if (!save(activeChar, changed))
		{
			return false;
		}
		activeChar.sendSysMessage(map.name() + ": " + (blue ? "Blue" : "Red") + " base " + number + " set at " + here.getX() + ", " + here.getY() + ", " + here.getZ() + " (heading " + here.getHeading() + ").");
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

	/** //tvt remove &lt;map&gt; &lt;blue|red&gt; &lt;n&gt; */
	private static boolean removeSpawn(Player activeChar, String[] args)
	{
		final TvTMap map = args.length > 2 ? findMap(activeChar, args[2]) : null;
		final Boolean blue = map != null ? findTeam(activeChar, args, 3) : null;
		if (blue == null)
		{
			return false;
		}
		final List<Location> spawns = new ArrayList<>(map.getSpawns(blue));
		final int index = args.length > 4 ? parse(args[4], 0) - 1 : -1;
		if ((index < 0) || (index >= spawns.size()))
		{
			activeChar.sendSysMessage("No such spawn point. " + describeSpawns(map, blue));
			return false;
		}
		spawns.remove(index);
		if (!save(activeChar, blue ? map.withSpawns(spawns, map.red()) : map.withSpawns(map.blue(), spawns)))
		{
			return false;
		}
		activeChar.sendSysMessage(map.name() + ": removed " + (blue ? "Blue" : "Red") + " base " + (index + 1) + (spawns.isEmpty() ? " - the team has no spawn point left, the event won't pick the map." : "."));
		return true;
	}

	/** //tvt fence &lt;map&gt; [radius x] [radius y] | off */
	private static boolean setFence(Player activeChar, String[] args)
	{
		final TvTMap map = args.length > 2 ? findMap(activeChar, args[2]) : null;
		if (map == null)
		{
			if (args.length <= 2)
			{
				activeChar.sendSysMessage("Usage: //tvt fence <map> [radius x] [radius y] | off");
			}
			return false;
		}

		final TvTBarrier barrier;
		if ((args.length > 3) && args[3].equalsIgnoreCase("off"))
		{
			barrier = null;
		}
		else
		{
			final TvTBarrier old = map.barrier();
			final int radiusX = args.length > 3 ? parse(args[3], -1) : old != null ? old.radiusX() : 5000;
			final int radiusY = args.length > 4 ? parse(args[4], -1) : args.length > 3 ? radiusX : old != null ? old.radiusY() : 5000;
			if ((radiusX < TvTBarrier.MIN_RADIUS) || (radiusY < TvTBarrier.MIN_RADIUS))
			{
				activeChar.sendSysMessage("The fence's radii are at least " + TvTBarrier.MIN_RADIUS + ". Usage: //tvt fence <map> [radius x] [radius y] | off");
				return false;
			}
			barrier = new TvTBarrier(activeChar.getX(), activeChar.getY(), radiusX, radiusY);
		}

		final TvTMap changed = new TvTMap(map.id(), map.name(), map.enabled(), map.description(), map.minX(), map.minY(), map.maxX(), map.maxY(), map.z(), map.blue(), map.red(), barrier, map.doors());
		if (!save(activeChar, changed))
		{
			return false;
		}
		if (barrier == null)
		{
			activeChar.sendSysMessage(map.name() + " has no fence now.");
			return true;
		}
		activeChar.sendSysMessage(map.name() + ": the fence goes round " + barrier.x() + ", " + barrier.y() + ", " + barrier.radiusX() + " along x and " + barrier.radiusY() + " along y.");
		int outside = 0;
		for (Location spawn : map.blue())
		{
			outside += barrier.contains(spawn, 0) ? 0 : 1;
		}
		for (Location spawn : map.red())
		{
			outside += barrier.contains(spawn, 0) ? 0 : 1;
		}
		if (outside > 0)
		{
			activeChar.sendSysMessage(outside + " spawn point(s) are beyond it: make it wider or move it.");
		}
		return true;
	}

	private static boolean save(Player activeChar, TvTMap map)
	{
		try
		{
			TvTMapData.getInstance().save(map);
		}
		catch (IOException | IllegalArgumentException e)
		{
			activeChar.sendSysMessage("Could not save " + map.id() + ": " + e.getMessage());
			return false;
		}
		TvTMarkers.getInstance().refresh(map.id());
		return true;
	}

	private static String describeSpawns(TvTMap map, boolean blue)
	{
		final int count = map.getSpawns(blue).size();
		return count == 0 ? map.name() + " has no " + (blue ? "blue" : "red") + " spawn point." : (blue ? "Blue" : "Red") + " spawn points: 1 to " + count + ".";
	}

	private static TvTMap findMap(Player activeChar, String id)
	{
		final TvTMap map = TvTMapData.getInstance().getMap(id);
		if (map == null)
		{
			final StringBuilder ids = new StringBuilder();
			for (TvTMap other : TvTMapData.getInstance().getMaps())
			{
				ids.append(ids.length() > 0 ? ", " : "").append(other.id());
			}
			activeChar.sendSysMessage("No TvT map " + id + ". Maps: " + (ids.length() > 0 ? ids : "none") + ".");
		}
		return map;
	}

	/** @return {@code true} for blue, {@code false} for red, {@code null} (after telling the GM) for neither */
	private static Boolean findTeam(Player activeChar, String[] args, int index)
	{
		final String key = args.length > index ? args[index].toLowerCase() : "";
		if (key.equals("blue") || key.equals("b"))
		{
			return Boolean.TRUE;
		}
		if (key.equals("red") || key.equals("r"))
		{
			return Boolean.FALSE;
		}
		activeChar.sendSysMessage("Which team: blue or red?");
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
