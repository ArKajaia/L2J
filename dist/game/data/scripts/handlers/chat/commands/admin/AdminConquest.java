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
package handlers.chat.commands.admin;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.StringTokenizer;

import org.l2jmobius.gameserver.config.custom.ConquestConfig;
import org.l2jmobius.gameserver.data.xml.ConquestMapData;
import org.l2jmobius.gameserver.handler.IAdminCommandHandler;
import org.l2jmobius.gameserver.managers.conquest.ConquestCapture;
import org.l2jmobius.gameserver.managers.conquest.ConquestManager;
import org.l2jmobius.gameserver.managers.conquest.ConquestMatch;
import org.l2jmobius.gameserver.managers.conquest.ConquestRegistration;
import org.l2jmobius.gameserver.managers.conquest.ConquestTeam;
import org.l2jmobius.gameserver.model.actor.Player;
import org.l2jmobius.gameserver.model.actor.enums.creature.Team;
import org.l2jmobius.gameserver.model.conquest.ConquestMap;
import org.l2jmobius.gameserver.model.conquest.ConquestPoint;
import org.l2jmobius.gameserver.model.conquest.ConquestPointType;
import org.l2jmobius.gameserver.model.eventmap.EventMapType;
import org.l2jmobius.gameserver.network.serverpackets.ExLinkOpen;

import custom.Conquest.ConquestMarkers;
import custom.Conquest.ConquestWeb;
import custom.EventMaps.EventMapMarkers;
import custom.EventMaps.EventMapWeb;

/**
 * The maps of the Conquest event ({@link ConquestMapData}: the event maps of data/maps, shared with Team vs Team and Deathmatch): //conquest opens the event map page on the Conquest points, where the battlefields, their spawn points and capture points are edited.
 * The same can be done in game:
 * <ul>
 * <li>//conquest - the event map page (web, a link that works while the GM is online), on the Conquest points</li>
 * <li>//conquest list - the maps</li>
 * <li>//conquest show &lt;map&gt; | hide - draw a map's waypoints in game, or take them away</li>
 * <li>//conquest tp &lt;map&gt; &lt;blue|red|capture&gt; &lt;number|name&gt; - go to a point</li>
 * <li>//conquest set &lt;map&gt; &lt;blue|red|capture&gt; &lt;number|name|new&gt; - your position becomes that point (new adds one)</li>
 * <li>//conquest remove &lt;map&gt; &lt;blue|red|capture&gt; &lt;number|name&gt; - take a point away</li>
 * <li>//conquest reload - read data/maps again</li>
 * </ul>
 * And the matches (see {@link ConquestManager}), of fake players only for now:
 * <ul>
 * <li>//conquest &lt;map&gt; start [players per team] [levels, e.g. 40-50] (or //conquest start &lt;map&gt; ...) - start a match</li>
 * <li>//conquest register [map] - open the players' registration now (the map by chance from ConquestMaps when none is named)</li>
 * <li>//conquest stop - end it</li>
 * <li>//conquest status - the scores and points, in chat</li>
 * <li>//conquest brain [&lt;blue&gt; &lt;red&gt; | reset] - the brain of each team in the next matches (generic, jev), and how each pairing did</li>
 * <li>//conquest live - the live page of the match (web)</li>
 * <li>//conquest watch | leave - go into the match's copy of the map to watch, or back</li>
 * </ul>
 */
public class AdminConquest implements IAdminCommandHandler
{
	private static final String[] ADMIN_COMMANDS =
	{
		"admin_conquest"
	};

	private static final String USAGE = "Usage: //conquest [web | list | show <map> | hide | tp <map> <blue|red|capture> <n|name> | set <map> <blue|red|capture> <n|name|new> | remove <map> <blue|red|capture> <n|name> | reload | <map> start [players per team] [min-max level] | register [map] | stop [id] | status | live | watch [id] | leave | brain [<blue> <red> | reset]]";

	@Override
	public boolean onCommand(String commandLine, Player activeChar)
	{
		final StringTokenizer st = new StringTokenizer(commandLine);
		st.nextToken();
		final String action = st.hasMoreTokens() ? st.nextToken().toLowerCase(Locale.ROOT) : "web";
		final ConquestMapData data = ConquestMapData.getInstance();
		switch (action)
		{
			case "web":
			{
				// The event maps are shared with Team vs Team and Deathmatch: the page opens on the Conquest points.
				final String url = EventMapWeb.getUrl(activeChar, EventMapType.CONQUEST);
				activeChar.sendSysMessage("Event map page, Conquest (works while you stay online):");
				activeChar.sendMessage(url);
				activeChar.sendPacket(new ExLinkOpen(url));
				break;
			}
			case "list":
			{
				final List<ConquestMap> maps = data.getMaps();
				if (maps.isEmpty())
				{
					activeChar.sendSysMessage("No Conquest maps. Make one on the map page: //conquest");
					break;
				}
				final List<ConquestMap> enabled = data.getEnabledMaps();
				for (ConquestMap map : maps)
				{
					activeChar.sendSysMessage(map.id() + " - " + map.name() + ": up to " + map.maxPlayers() + " a team, " + map.getPoints(ConquestPointType.BLUE).size() + " blue, " + map.getPoints(ConquestPointType.RED).size() + " red, " + map.getPoints(ConquestPointType.CAPTURE).size() + " capture point(s)" + (enabled.contains(map) ? "" : map.enabled() ? " (incomplete)" : " (disabled)"));
				}
				break;
			}
			case "show":
			{
				final ConquestMap map = findMap(activeChar, st);
				if (map == null)
				{
					return false;
				}
				ConquestMarkers.getInstance().show(activeChar, map);
				activeChar.sendSysMessage(map.name() + " is drawn: blue and red rings are the spawn points, gold double rings the capture points, the white line the edge. //conquest hide takes it away.");
				break;
			}
			case "hide":
			{
				ConquestMarkers.getInstance().hide(activeChar);
				activeChar.sendSysMessage("The Conquest waypoints are gone.");
				break;
			}
			case "tp":
			case "teleport":
			{
				final ConquestMap map = findMap(activeChar, st);
				final ConquestPointType type = map != null ? findType(activeChar, st) : null;
				if (type == null)
				{
					return false;
				}
				final ConquestPoint point = st.hasMoreTokens() ? map.findPoint(type, st.nextToken()) : null;
				if (point == null)
				{
					activeChar.sendSysMessage("No such point. " + describePoints(map, type));
					return false;
				}
				activeChar.teleToLocation(point.x(), point.y(), point.z(), point.heading(), 0);
				activeChar.sendSysMessage(map.name() + ": " + map.label(point) + ".");
				break;
			}
			case "set":
			{
				return setPoint(activeChar, st, data);
			}
			case "remove":
			{
				return removePoint(activeChar, st, data);
			}
			case "start":
			{
				final ConquestMap map = findMap(activeChar, st);
				return (map != null) && startMatch(activeChar, map, st);
			}
			case "register":
			{
				ConquestMap map = null;
				if (st.hasMoreTokens())
				{
					map = findMap(activeChar, st);
					if (map == null)
					{
						return false;
					}
				}
				final String refusal = ConquestRegistration.getInstance().open(map);
				activeChar.sendSysMessage(refusal == null ? "Conquest registration is open on " + ConquestRegistration.getInstance().getMap().name() + " for " + ConquestConfig.REGISTRATION_TIME + " minutes. //conquest stop cancels it." : "Registration did not open: " + refusal + ".");
				break;
			}
			case "stop":
			{
				// An open registration is canceled, a running match stopped.
				if (ConquestRegistration.getInstance().cancel())
				{
					activeChar.sendSysMessage("Conquest registration canceled.");
					if (ConquestManager.getInstance().getMatches().isEmpty())
					{
						break;
					}
				}
				// //conquest stop [match id]
				final String id = st.hasMoreTokens() ? st.nextToken() : null;
				if ((id != null) && id.matches("\\d+"))
				{
					activeChar.sendSysMessage(ConquestManager.getInstance().stop(Integer.parseInt(id)));
				}
				else
				{
					activeChar.sendSysMessage(ConquestManager.getInstance().stop());
				}
				break;
			}
			case "status":
			{
				showStatus(activeChar);
				break;
			}
			case "brain":
			case "brains":
			{
				// //conquest brain [<blue> <red> | reset]: which brain leads each team in the next matches (A/B).
				final ConquestManager manager = ConquestManager.getInstance();
				if (st.countTokens() >= 2)
				{
					activeChar.sendSysMessage(manager.setBrains(st.nextToken(), st.nextToken()));
				}
				else if (st.hasMoreTokens() && st.nextToken().equalsIgnoreCase("reset"))
				{
					activeChar.sendSysMessage(manager.setBrains(null, null));
				}
				else
				{
					for (String line : manager.getBrainReport())
					{
						activeChar.sendSysMessage(line);
					}
					activeChar.sendSysMessage("//conquest brain <blue> <red> sets them (" + String.join(", ", ConquestManager.BRAINS) + "), //conquest brain reset goes back to Conquest.ini.");
				}
				break;
			}
			case "live":
			{
				final String url = ConquestWeb.getLiveUrl(activeChar);
				activeChar.sendSysMessage("Conquest live page (works while you stay online):");
				activeChar.sendMessage(url);
				activeChar.sendPacket(new ExLinkOpen(url));
				break;
			}
			case "watch":
			{
				// //conquest watch [match id]: the next match without one.
				final String id = st.hasMoreTokens() ? st.nextToken() : "";
				activeChar.sendSysMessage(ConquestManager.getInstance().watch(activeChar, id.matches("\\d+") ? Integer.parseInt(id) : 0));
				break;
			}
			case "leave":
			{
				activeChar.sendSysMessage(ConquestManager.getInstance().leave(activeChar));
				break;
			}
			case "reload":
			{
				data.load();
				ConquestMarkers.getInstance().refreshAll();
				EventMapMarkers.getInstance().refreshAll();
				activeChar.sendSysMessage("Read the event maps again: " + data.getMaps().size() + " Conquest map(s), " + data.getEnabledMaps().size() + " ready for the event.");
				break;
			}
			default:
			{
				// //conquest <map> start ...
				final ConquestMap map = data.getMap(action);
				if ((map != null) && st.hasMoreTokens() && st.nextToken().equalsIgnoreCase("start"))
				{
					return startMatch(activeChar, map, st);
				}
				activeChar.sendSysMessage(USAGE);
				return false;
			}
		}
		return true;
	}

	/**
	 * Starts a match: [players per team] [levels], e.g. 50 40-50. Without them, the map's maxPlayers and ConquestLevelRange.
	 */
	private static boolean startMatch(Player activeChar, ConquestMap map, StringTokenizer st)
	{
		int perTeam = map.maxPlayers();
		int minLevel = ConquestConfig.MIN_LEVEL;
		int maxLevel = ConquestConfig.MAX_LEVEL;
		boolean sizeGiven = false;
		while (st.hasMoreTokens())
		{
			final String token = st.nextToken();
			if (!sizeGiven && token.matches("\\d+"))
			{
				perTeam = Integer.parseInt(token);
				sizeGiven = true;
			}
			else if (token.matches("\\d+(-\\d+)?"))
			{
				final int[] levels = ConquestConfig.parseLevels(token);
				minLevel = levels[0];
				maxLevel = levels[1];
			}
			else
			{
				activeChar.sendSysMessage("Usage: //conquest <map> start [players per team] [min-max level], e.g. //conquest " + map.id() + " start 50 40-50");
				return false;
			}
		}
		if (perTeam > map.maxPlayers())
		{
			activeChar.sendSysMessage(map.name() + " takes at most " + map.maxPlayers() + " a team.");
		}
		activeChar.sendSysMessage(ConquestManager.getInstance().start(map, perTeam, minLevel, maxLevel));
		return true;
	}

	private static void showStatus(Player activeChar)
	{
		final ConquestManager manager = ConquestManager.getInstance();
		final List<ConquestMatch> matches = manager.getMatches();
		if (matches.isEmpty())
		{
			activeChar.sendSysMessage("No Conquest match is running. //conquest <map> start starts one.");
			return;
		}
		if (matches.size() > 1)
		{
			for (ConquestMatch other : matches)
			{
				activeChar.sendSysMessage("Match " + other.getId() + ": " + other.getMap().name() + ", levels " + other.getMinLevel() + "-" + other.getMaxLevel() + ", " + other.getPhase().name().toLowerCase(Locale.ROOT) + ", " + other.getPlayers().size() + " players, Blue " + other.getBlue().getScore() + " : " + other.getRed().getScore() + " Red.");
			}
		}

		// The details of the one the GM watches, else the newest.
		final ConquestMatch match = manager.getMatch(activeChar);
		final int time = match.getTime();
		activeChar.sendSysMessage("Conquest match " + match.getId() + " on " + match.getMap().name() + " (levels " + match.getMinLevel() + "-" + match.getMaxLevel() + "): " + match.getPhase().name().toLowerCase(Locale.ROOT) + ", " + (time < 0 ? "starts in " + -time + "s" : (time / 60) + ":" + String.format("%02d", time % 60)) + (match.getResult() != null ? " - " + match.getResult() : ""));
		for (ConquestTeam team : new ConquestTeam[]
		{
			match.getBlue(),
			match.getRed()
		})
		{
			activeChar.sendSysMessage(team.getName() + " (" + team.getCommander().getName() + "): score " + team.getScore() + " (-" + team.getDrain() + "/s), " + team.getHeld() + " point(s), " + team.getAlive() + "/" + team.getFighters().size() + " alive, " + team.getKills() + " kills.");
		}
		final StringBuilder points = new StringBuilder();
		for (ConquestCapture capture : match.getCaptures())
		{
			points.append(points.length() > 0 ? ", " : "").append(capture.getName()).append(' ').append(capture.getOwner() == Team.BLUE ? "Blue" : capture.getOwner() == Team.RED ? "Red" : "neutral").append(" (").append((int) Math.round(capture.getProgress())).append(')');
		}
		activeChar.sendSysMessage("Points: " + points + ".");
	}

	/** //conquest set &lt;map&gt; &lt;type&gt; &lt;n|name|new&gt; */
	private boolean setPoint(Player activeChar, StringTokenizer st, ConquestMapData data)
	{
		final ConquestMap map = findMap(activeChar, st);
		final ConquestPointType type = map != null ? findType(activeChar, st) : null;
		if (type == null)
		{
			return false;
		}
		final String key = st.hasMoreTokens() ? st.nextToken() : "new";
		final List<ConquestPoint> points = new ArrayList<>(map.points());
		final ConquestPoint old = key.equalsIgnoreCase("new") ? null : map.findPoint(type, key);
		final ConquestPoint point;
		if (old != null)
		{
			point = new ConquestPoint(type, old.name(), activeChar.getX(), activeChar.getY(), activeChar.getZ(), type == ConquestPointType.CAPTURE ? old.heading() : activeChar.getHeading(), old.radius());
			points.set(points.indexOf(old), point);
		}
		else if (key.equalsIgnoreCase("new") || (type == ConquestPointType.CAPTURE))
		{
			if (map.getPoints(type).size() >= ConquestMapData.MAX_POINTS)
			{
				activeChar.sendSysMessage("A map has at most " + ConquestMapData.MAX_POINTS + " points of a type.");
				return false;
			}
			// A new capture point is called what was asked (//conquest set map capture F), or by the next free letter.
			final String name = type != ConquestPointType.CAPTURE ? "" : !key.equalsIgnoreCase("new") ? key.toUpperCase(Locale.ROOT) : nextLetter(map);
			point = new ConquestPoint(type, name, activeChar.getX(), activeChar.getY(), activeChar.getZ(), type == ConquestPointType.CAPTURE ? 0 : activeChar.getHeading(), type.getDefaultRadius());
			points.add(point);
		}
		else
		{
			activeChar.sendSysMessage("No such point (new adds one). " + describePoints(map, type));
			return false;
		}

		final ConquestMap changed = map.withPoints(points);
		if (!save(activeChar, data, changed))
		{
			return false;
		}
		activeChar.sendSysMessage(map.name() + ": " + changed.label(point) + (old != null ? " moved to " : " set at ") + point.x() + ", " + point.y() + ", " + point.z() + ".");
		if ((point.x() < map.minX()) || (point.x() > map.maxX()) || (point.y() < map.minY()) || (point.y() > map.maxY()))
		{
			activeChar.sendSysMessage("It is outside the map's area: widen the area on the map page (//conquest).");
		}
		if (activeChar.getInstanceId() != 0)
		{
			activeChar.sendSysMessage("You are in an instance: the point is kept as a place in the open world.");
		}
		return true;
	}

	/** //conquest remove &lt;map&gt; &lt;type&gt; &lt;n|name&gt; */
	private boolean removePoint(Player activeChar, StringTokenizer st, ConquestMapData data)
	{
		final ConquestMap map = findMap(activeChar, st);
		final ConquestPointType type = map != null ? findType(activeChar, st) : null;
		if (type == null)
		{
			return false;
		}
		final ConquestPoint point = st.hasMoreTokens() ? map.findPoint(type, st.nextToken()) : null;
		if (point == null)
		{
			activeChar.sendSysMessage("No such point. " + describePoints(map, type));
			return false;
		}
		final List<ConquestPoint> points = new ArrayList<>(map.points());
		points.remove(point);
		if (!save(activeChar, data, map.withPoints(points)))
		{
			return false;
		}
		activeChar.sendSysMessage(map.name() + ": removed " + map.label(point) + ".");
		return true;
	}

	private static boolean save(Player activeChar, ConquestMapData data, ConquestMap map)
	{
		try
		{
			data.save(map);
		}
		catch (IOException | IllegalArgumentException e)
		{
			activeChar.sendSysMessage("Could not save " + map.id() + ": " + e.getMessage());
			return false;
		}
		ConquestMarkers.getInstance().refresh(map.id());
		EventMapMarkers.getInstance().refresh(map.id());
		return true;
	}

	private static String nextLetter(ConquestMap map)
	{
		for (char letter = 'A'; letter <= 'Z'; letter++)
		{
			if (map.findPoint(ConquestPointType.CAPTURE, String.valueOf(letter)) == null)
			{
				return String.valueOf(letter);
			}
		}
		return String.valueOf(map.getPoints(ConquestPointType.CAPTURE).size() + 1);
	}

	private static String describePoints(ConquestMap map, ConquestPointType type)
	{
		final List<ConquestPoint> points = map.getPoints(type);
		if (points.isEmpty())
		{
			return map.name() + " has no " + type.getKey() + " point.";
		}
		if (type == ConquestPointType.CAPTURE)
		{
			final StringBuilder names = new StringBuilder();
			for (ConquestPoint point : points)
			{
				names.append(names.length() > 0 ? ", " : "").append(point.name());
			}
			return "Capture points: " + names + ".";
		}
		return type.getKey() + " spawn points: 1 to " + points.size() + ".";
	}

	private static ConquestMap findMap(Player activeChar, StringTokenizer st)
	{
		if (!st.hasMoreTokens())
		{
			activeChar.sendSysMessage(USAGE);
			return null;
		}
		final String id = st.nextToken();
		final ConquestMap map = ConquestMapData.getInstance().getMap(id);
		if (map == null)
		{
			final StringBuilder ids = new StringBuilder();
			for (ConquestMap other : ConquestMapData.getInstance().getMaps())
			{
				ids.append(ids.length() > 0 ? ", " : "").append(other.id());
			}
			activeChar.sendSysMessage("No map " + id + ". Maps: " + (ids.length() > 0 ? ids : "none") + ".");
		}
		return map;
	}

	private static ConquestPointType findType(Player activeChar, StringTokenizer st)
	{
		final ConquestPointType type = st.hasMoreTokens() ? ConquestPointType.fromKey(st.nextToken()) : null;
		if (type == null)
		{
			activeChar.sendSysMessage("Which kind of point: blue, red or capture?");
		}
		return type;
	}

	@Override
	public String[] getCommandList()
	{
		return ADMIN_COMMANDS;
	}
}
