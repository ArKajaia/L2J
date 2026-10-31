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
package org.l2jmobius.gameserver.managers.conquest;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ScheduledFuture;
import java.util.logging.Logger;

import org.l2jmobius.commons.threads.ThreadPool;
import org.l2jmobius.commons.time.SchedulingPattern;
import org.l2jmobius.commons.time.TimeUtil;
import org.l2jmobius.commons.util.Rnd;
import org.l2jmobius.gameserver.config.custom.ConquestConfig;
import org.l2jmobius.gameserver.config.custom.EventRotationConfig;
import org.l2jmobius.gameserver.config.custom.DualboxCheckConfig;
import org.l2jmobius.gameserver.data.xml.ConquestMapData;
import org.l2jmobius.gameserver.managers.AntiFeedManager;
import org.l2jmobius.gameserver.managers.EventRotationManager;
import org.l2jmobius.gameserver.managers.FakePlayerPvpManager;
import org.l2jmobius.gameserver.model.Location;
import org.l2jmobius.gameserver.model.World;
import org.l2jmobius.gameserver.model.actor.Npc;
import org.l2jmobius.gameserver.model.actor.Player;
import org.l2jmobius.gameserver.model.conquest.ConquestMap;
import org.l2jmobius.gameserver.model.conquest.ConquestPointType;
import org.l2jmobius.gameserver.model.groups.Party;
import org.l2jmobius.gameserver.model.olympiad.OlympiadManager;
import org.l2jmobius.gameserver.model.script.Quest;
import org.l2jmobius.gameserver.model.zone.ZoneId;
import org.l2jmobius.gameserver.util.Broadcast;

/**
 * The registration of players for a Conquest match, like the Team vs Team one: every {@link ConquestConfig#SCHEDULE} the Conquest Event Manager stands in Giran for {@link ConquestConfig#REGISTRATION_TIME} minutes, players register alone or their party leader
 * registers the party, then every level bracket ({@link ConquestConfig#BRACKETS}) with players gets its own match on a map picked from {@link ConquestConfig#MAPS} (see {@link ConquestManager#start(ConquestMap, int, int, int, List, List)}). A party plays in
 * one team, led by its leader; one with members in several brackets is broken up.
 */
public class ConquestRegistration
{
	private static final Logger LOGGER = Logger.getLogger(ConquestRegistration.class.getName());

	/** data/stats/npcs/custom/conquest_event.xml */
	public static final int MANAGER_NPC_ID = 70013;
	/** Minutes before the end of registration it is announced again. */
	private static final int[] WARNINGS =
	{
		5,
		1
	};

	private final Map<Integer, Long> _registered = new ConcurrentHashMap<>();
	private final List<Npc> _managers = new CopyOnWriteArrayList<>();
	private final List<ScheduledFuture<?>> _registrationTasks = new CopyOnWriteArrayList<>();
	private volatile boolean _open;
	private volatile long _registrationEnd;
	private volatile ConquestMap _map;
	private ScheduledFuture<?> _scheduleTask;

	protected ConquestRegistration()
	{
		// With the rotation on, it opens the registrations in turn with Team vs Team and Deathmatch (see EventRotationManager).
		if (ConquestConfig.REGISTRATION_ENABLED && !EventRotationConfig.ENABLED)
		{
			scheduleNext();
		}
	}

	private synchronized void scheduleNext()
	{
		if (_scheduleTask != null)
		{
			_scheduleTask.cancel(false);
			_scheduleTask = null;
		}

		final long delay;
		try
		{
			delay = new SchedulingPattern(ConquestConfig.SCHEDULE).getDelayToNextFromNow() + 5000; // 5 seconds late, so it never runs twice for one time.
		}
		catch (Exception e)
		{
			LOGGER.warning(getClass().getSimpleName() + ": Bad ConquestSchedule \"" + ConquestConfig.SCHEDULE + "\": registration doesn't open by itself.");
			return;
		}

		_scheduleTask = ThreadPool.schedule(() ->
		{
			final String refusal = open(null);
			if (refusal != null)
			{
				LOGGER.info(getClass().getSimpleName() + ": Registration did not open: " + refusal + ".");
			}
			scheduleNext();
		}, delay);
		LOGGER.info(getClass().getSimpleName() + ": Next registration at " + TimeUtil.getDateTimeString(System.currentTimeMillis() + delay) + ".");
	}

	/**
	 * @param map a map
	 * @return {@code true} if a match can be played on it: enabled, with spawn points for both teams and a capture point
	 */
	private static boolean isPlayable(ConquestMap map)
	{
		return map.enabled() && !map.getPoints(ConquestPointType.BLUE).isEmpty() && !map.getPoints(ConquestPointType.RED).isEmpty() && !map.getPoints(ConquestPointType.CAPTURE).isEmpty();
	}

	/**
	 * Opens the registration (the schedule, or a GM).
	 * @param map the map of the match, {@code null} for one of {@link ConquestConfig#MAPS} by chance
	 * @return {@code null} if it opened, else why not
	 */
	public synchronized String open(ConquestMap map)
	{
		if (_open)
		{
			return "registration is already open";
		}
		final String busy = EventRotationManager.getBusyEvent();
		if (busy != null)
		{
			return busy + " is going on (the events never overlap)";
		}
		if (ConquestConfig.BRACKETS.isEmpty())
		{
			return "there is no level bracket (ConquestBrackets)";
		}
		if (!FakePlayerPvpManager.getInstance().isEnabled())
		{
			return "Conquest needs the fake players (EnableFakePlayers, FakePvpEnabled)";
		}

		ConquestMap picked = map;
		if (picked == null)
		{
			final List<ConquestMap> maps = new ArrayList<>();
			for (ConquestMap candidate : ConquestMapData.getInstance().getMaps())
			{
				if (isPlayable(candidate) && (ConquestConfig.MAPS.isEmpty() || ConquestConfig.MAPS.contains(candidate.id())))
				{
					maps.add(candidate);
				}
			}
			if (maps.isEmpty())
			{
				return "no map to play on (ConquestMaps)";
			}
			picked = maps.get(Rnd.get(maps.size()));
		}
		else if (!isPlayable(picked))
		{
			return picked.name() + " is disabled or lacks spawn points or a capture point";
		}

		_open = true;
		_map = picked;
		_registered.clear();
		_registrationEnd = System.currentTimeMillis() + (ConquestConfig.REGISTRATION_TIME * 60000L);
		if (DualboxCheckConfig.DUALBOX_CHECK_MAX_L2EVENT_PARTICIPANTS_PER_IP > 0)
		{
			AntiFeedManager.getInstance().registerEvent(AntiFeedManager.CONQUEST_ID);
			AntiFeedManager.getInstance().clear(AntiFeedManager.CONQUEST_ID);
		}

		for (Location location : ConquestConfig.MANAGER_LOCATIONS)
		{
			final Npc manager = Quest.addSpawn(MANAGER_NPC_ID, location, false, 0);
			if (manager != null)
			{
				manager.setRandomWalking(false);
				_managers.add(manager);
			}
		}

		for (int warning : WARNINGS)
		{
			if (ConquestConfig.REGISTRATION_TIME > warning)
			{
				_registrationTasks.add(ThreadPool.schedule(() -> Broadcast.toAllOnlinePlayers("Conquest: Registration closes in " + warning + " minute" + (warning > 1 ? "s" : "") + ". " + _registered.size() + " registered so far."), (ConquestConfig.REGISTRATION_TIME - warning) * 60000L));
			}
		}
		_registrationTasks.add(ThreadPool.schedule(this::close, ConquestConfig.REGISTRATION_TIME * 60000L));

		Broadcast.toAllOnlinePlayers("Conquest: Registration is open for " + ConquestConfig.REGISTRATION_TIME + " minutes at the Conquest Event Manager in Giran. Blue against Red over the capture points of " + picked.name() + ", levels " + describeBrackets() + ".");
		LOGGER.info(getClass().getSimpleName() + ": Registration opened for " + picked.name() + ".");
		return null;
	}

	/**
	 * @return the brackets as text: 41-45, 46-50...
	 */
	public static String describeBrackets()
	{
		final StringBuilder sb = new StringBuilder();
		for (int[] bracket : ConquestConfig.BRACKETS)
		{
			sb.append(sb.length() > 0 ? ", " : "").append(describe(bracket));
		}
		return sb.toString();
	}

	/**
	 * @param bracket a bracket of {@link ConquestConfig#BRACKETS}
	 * @return it as text: 41-45, or 85
	 */
	public static String describe(int[] bracket)
	{
		return bracket[0] == bracket[1] ? String.valueOf(bracket[0]) : bracket[0] + "-" + bracket[1];
	}

	/**
	 * @param level a level
	 * @return the index of its bracket in {@link ConquestConfig#BRACKETS}, -1 for none
	 */
	public static int getBracket(int level)
	{
		for (int i = 0; i < ConquestConfig.BRACKETS.size(); i++)
		{
			final int[] bracket = ConquestConfig.BRACKETS.get(i);
			if ((level >= bracket[0]) && (level <= bracket[1]))
			{
				return i;
			}
		}
		return -1;
	}

	/**
	 * @param map a map
	 * @return the places of each team in a match players registered for on it (see {@link ConquestConfig#PLAYERS_PER_TEAM})
	 */
	public static int getPerTeam(ConquestMap map)
	{
		return Math.max(ConquestManager.PARTY_SIZE, ((ConquestConfig.PLAYERS_PER_TEAM > 0 ? Math.min(ConquestConfig.PLAYERS_PER_TEAM, map.maxPlayers()) : map.maxPlayers()) / ConquestManager.PARTY_SIZE) * ConquestManager.PARTY_SIZE);
	}

	public boolean isOpen()
	{
		return _open;
	}

	/**
	 * @return the map of the match registration is open for, {@code null} when it is closed
	 */
	public ConquestMap getMap()
	{
		return _open ? _map : null;
	}

	/**
	 * @return the seconds of registration left, 0 if it is closed
	 */
	public int getSecondsLeft()
	{
		return _open ? (int) Math.max(0, (_registrationEnd - System.currentTimeMillis()) / 1000) : 0;
	}

	public boolean isRegistered(Player player)
	{
		return _registered.containsKey(player.getObjectId());
	}

	public int getRegisteredCount()
	{
		return _registered.size();
	}

	/**
	 * @param bracket a bracket index
	 * @return how many registered players are in it now
	 */
	public int getRegisteredCount(int bracket)
	{
		int count = 0;
		for (int objectId : _registered.keySet())
		{
			final Player player = World.getInstance().getPlayer(objectId);
			if ((player != null) && (getBracket(player.getLevel()) == bracket))
			{
				count++;
			}
		}
		return count;
	}

	/**
	 * @param player a player
	 * @return why it can't play now, {@code null} if it can
	 */
	public static String getJoinRefusal(Player player)
	{
		if (getBracket(player.getLevel()) < 0)
		{
			return "Level " + player.getLevel() + " is outside every Conquest bracket (" + describeBrackets() + ").";
		}
		if (player.isOnEvent() || (player.getBlockCheckerArena() > -1))
		{
			return "You are already on an event.";
		}
		if (player.isInOlympiadMode() || OlympiadManager.getInstance().isRegistered(player))
		{
			return "You are registered on the Olympiad.";
		}
		if (player.isInDuel())
		{
			return "You are in a duel.";
		}
		if (player.getInstanceId() > 0)
		{
			return "You are in an instance.";
		}
		if (player.isInSiege() || player.isInsideZone(ZoneId.SIEGE))
		{
			return "You are on a siege.";
		}
		if (player.isCursedWeaponEquipped() || (player.getKarma() > 0))
		{
			return "Players with karma can't take part.";
		}
		if (player.isJailed() || player.isInOfflineMode())
		{
			return "You can't take part now.";
		}
		if (player.isFlyingMounted())
		{
			return "You can't take part while flying.";
		}
		if (player.isFishing())
		{
			return "You can't take part while fishing.";
		}
		return null;
	}

	/**
	 * Registers a player.
	 * @param player the player
	 * @return what to tell it
	 */
	public String register(Player player)
	{
		if (!_open)
		{
			return "Registration is closed.";
		}
		if (isRegistered(player))
		{
			return "You are already registered.";
		}
		if (player.isRegisteredOnEvent())
		{
			return "You are already registered on another event.";
		}

		final String refusal = getJoinRefusal(player);
		if (refusal != null)
		{
			return refusal;
		}

		if ((DualboxCheckConfig.DUALBOX_CHECK_MAX_L2EVENT_PARTICIPANTS_PER_IP > 0) && !AntiFeedManager.getInstance().tryAddPlayer(AntiFeedManager.CONQUEST_ID, player, DualboxCheckConfig.DUALBOX_CHECK_MAX_L2EVENT_PARTICIPANTS_PER_IP))
		{
			return "Too many players from your computer are registered already.";
		}

		_registered.put(player.getObjectId(), System.currentTimeMillis());
		player.setRegisteredOnEvent(true);
		return "You are registered for the levels " + describe(ConquestConfig.BRACKETS.get(getBracket(player.getLevel()))) + " match on " + _map.name() + ".";
	}

	/**
	 * The party leader registers its whole party: it plays in one team, led by its leader.
	 * @param leader the leader
	 * @return what to tell it
	 */
	public String registerParty(Player leader)
	{
		final Party party = leader.getParty();
		if ((party == null) || !party.isLeader(leader))
		{
			return "Only a party leader can register a party.";
		}
		if (!_open)
		{
			return "Registration is closed.";
		}

		final StringBuilder sb = new StringBuilder();
		int registered = 0;
		for (Player member : party.getMembers())
		{
			if (isRegistered(member))
			{
				registered++;
				continue;
			}

			final String result = register(member);
			if (isRegistered(member))
			{
				registered++;
				if (member != leader)
				{
					member.sendMessage("Conquest: " + leader.getName() + " registered the party. " + result);
				}
			}
			else
			{
				sb.append(member.getName()).append(": ").append(result).append(" ");
				member.sendMessage("Conquest: You could not be registered: " + result);
			}
		}
		return registered + " of " + party.getMemberCount() + " members are registered." + (sb.length() > 0 ? " " + sb.toString().trim() : "");
	}

	/**
	 * @param player a player that cancels its registration
	 * @return what to tell it
	 */
	public String unregister(Player player)
	{
		if (_registered.remove(player.getObjectId()) == null)
		{
			return "You are not registered.";
		}

		player.setRegisteredOnEvent(false);
		if (DualboxCheckConfig.DUALBOX_CHECK_MAX_L2EVENT_PARTICIPANTS_PER_IP > 0)
		{
			AntiFeedManager.getInstance().removePlayer(AntiFeedManager.CONQUEST_ID, player);
		}
		return "Your registration is canceled.";
	}

	private void cancelTasks()
	{
		for (ScheduledFuture<?> task : _registrationTasks)
		{
			task.cancel(false);
		}
		_registrationTasks.clear();
		for (Npc manager : _managers)
		{
			manager.deleteMe();
		}
		_managers.clear();
	}

	/**
	 * Registration is over: the players are sorted into groups (a party stays together, its leader first) and spread evenly over the teams, and the match starts.
	 */
	public synchronized void close()
	{
		if (!_open)
		{
			return;
		}

		_open = false;
		cancelTasks();

		// Who is still there and can go. They stay registered on an event until they are in the match (see ConquestManager#spawnParty).
		final List<Player> players = new ArrayList<>();
		for (int objectId : _registered.keySet())
		{
			final Player player = World.getInstance().getPlayer(objectId);
			if ((player == null) || !player.isOnline())
			{
				continue;
			}

			final String refusal = getJoinRefusal(player);
			if (refusal != null)
			{
				player.setRegisteredOnEvent(false);
				player.sendMessage("Conquest: You can't take part: " + refusal);
				continue;
			}
			players.add(player);
		}
		_registered.clear();

		if (players.isEmpty())
		{
			Broadcast.toAllOnlinePlayers("Conquest: Nobody registered, no match this time.");
			return;
		}

		// The groups of each bracket: a party's members of one bracket stay together, the first of them in the party (its leader if it is there) leading them; a party with members in several brackets is broken up.
		final Map<Integer, List<List<Player>>> brackets = new TreeMap<>();
		final Set<Player> placed = new HashSet<>();
		for (Player player : players)
		{
			if (placed.contains(player))
			{
				continue;
			}

			final List<Player> members = new ArrayList<>();
			final Party party = player.getParty();
			if (party != null)
			{
				for (Player member : party.getMembers()) // The leader first.
				{
					if (players.contains(member) && !placed.contains(member))
					{
						members.add(member);
					}
				}
			}
			else
			{
				members.add(player);
			}

			final Map<Integer, List<Player>> byBracket = new LinkedHashMap<>();
			for (Player member : members)
			{
				placed.add(member);
				byBracket.computeIfAbsent(getBracket(member.getLevel()), _ -> new ArrayList<>()).add(member);
			}
			if (byBracket.size() > 1)
			{
				for (Player member : members)
				{
					member.sendMessage("Conquest: Your party has members of different level brackets: it is broken up, and each of you plays in the match of their level.");
				}
			}
			for (Map.Entry<Integer, List<Player>> entry : byBracket.entrySet())
			{
				brackets.computeIfAbsent(entry.getKey(), _ -> new ArrayList<>()).add(entry.getValue());
			}
		}

		final ConquestMap map = _map;
		final int perTeam = getPerTeam(map);
		final StringBuilder started = new StringBuilder();
		for (Map.Entry<Integer, List<List<Player>>> entry : brackets.entrySet())
		{
			final int[] bracket = ConquestConfig.BRACKETS.get(entry.getKey());
			final String name = describe(bracket);
			final List<List<Player>> groups = entry.getValue();
			int count = 0;
			for (List<Player> group : groups)
			{
				count += group.size();
			}
			if (count < ConquestConfig.MIN_PLAYERS)
			{
				for (List<Player> group : groups)
				{
					for (Player player : group)
					{
						player.setRegisteredOnEvent(false);
						player.sendMessage("Conquest: Not enough players registered for the levels " + name + " match.");
					}
				}
				continue;
			}

			// A match holds two teams: more players make more matches. The biggest groups are placed first.
			groups.sort((a, b) -> Integer.compare(b.size(), a.size()));
			final List<List<List<Player>>> matches = new ArrayList<>();
			final List<Integer> sizes = new ArrayList<>();
			for (List<Player> group : groups)
			{
				int index = -1;
				for (int i = 0; i < matches.size(); i++)
				{
					if ((sizes.get(i) + group.size()) <= (perTeam * 2))
					{
						index = i;
						break;
					}
				}
				if (index < 0)
				{
					matches.add(new ArrayList<>());
					sizes.add(0);
					index = matches.size() - 1;
				}
				matches.get(index).add(group);
				sizes.set(index, sizes.get(index) + group.size());
			}

			for (List<List<Player>> matchGroups : matches)
			{
				// Even teams: each group goes where there are fewer players so far.
				final List<List<Player>> blue = new ArrayList<>();
				final List<List<Player>> red = new ArrayList<>();
				int blueCount = 0;
				int redCount = 0;
				for (List<Player> group : matchGroups)
				{
					final boolean toBlue = blueCount < redCount ? true : (redCount < blueCount ? false : Rnd.nextBoolean());
					if (toBlue && ((blueCount + group.size()) <= perTeam))
					{
						blue.add(group);
						blueCount += group.size();
					}
					else if ((redCount + group.size()) <= perTeam)
					{
						red.add(group);
						redCount += group.size();
					}
					else if ((blueCount + group.size()) <= perTeam)
					{
						blue.add(group);
						blueCount += group.size();
					}
					else
					{
						for (Player player : group)
						{
							player.setRegisteredOnEvent(false);
							player.sendMessage("Conquest: There was no room left for your group in the levels " + name + " match.");
						}
					}
				}

				final ConquestMatch match = ConquestManager.getInstance().start(map, perTeam, bracket[0], bracket[1], blue, red);
				if (match == null)
				{
					for (List<Player> group : matchGroups)
					{
						for (Player player : group)
						{
							player.setRegisteredOnEvent(false);
							player.sendMessage("Conquest: The levels " + name + " match could not start, sorry.");
						}
					}
					LOGGER.warning(getClass().getSimpleName() + ": The levels " + name + " match did not start on " + map.name() + ".");
					continue;
				}
				started.append(started.length() > 0 ? ", " : "").append(name);
			}
		}

		if (started.length() > 0)
		{
			Broadcast.toAllOnlinePlayers("Conquest: The matches start on " + map.name() + " (levels " + started + "). Good luck!");
		}
		else
		{
			Broadcast.toAllOnlinePlayers("Conquest: Not enough players registered, no match this time.");
		}
	}

	/**
	 * Called by {@code Player#deleteMe}: a registered player is taken off the list, one in the match leaves it (see {@link ConquestManager#onLogout}).
	 * @param player the player logging off
	 */
	public void onLogout(Player player)
	{
		if (_registered.remove(player.getObjectId()) != null)
		{
			player.setRegisteredOnEvent(false);
		}
		ConquestManager.getInstance().onLogout(player);
	}

	/**
	 * Cancels the registration open now (a GM).
	 * @return {@code true} if it was open
	 */
	public synchronized boolean cancel()
	{
		if (!_open)
		{
			return false;
		}

		_open = false;
		cancelTasks();
		for (int objectId : _registered.keySet())
		{
			final Player player = World.getInstance().getPlayer(objectId);
			if (player != null)
			{
				player.setRegisteredOnEvent(false);
			}
		}
		_registered.clear();
		Broadcast.toAllOnlinePlayers("Conquest: The event was canceled.");
		return true;
	}

	public static ConquestRegistration getInstance()
	{
		return SingletonHolder.INSTANCE;
	}

	private static class SingletonHolder
	{
		protected static final ConquestRegistration INSTANCE = new ConquestRegistration();
	}
}
