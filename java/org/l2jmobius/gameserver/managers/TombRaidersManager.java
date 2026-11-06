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
package org.l2jmobius.gameserver.managers;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.logging.Logger;

import org.l2jmobius.commons.threads.ThreadPool;
import org.l2jmobius.commons.util.Rnd;
import org.l2jmobius.gameserver.ai.Intention;
import org.l2jmobius.gameserver.data.xml.FakePlayerPvpData;
import org.l2jmobius.gameserver.data.xml.NpcData;
import org.l2jmobius.gameserver.geoengine.GeoEngine;
import org.l2jmobius.gameserver.model.Location;
import org.l2jmobius.gameserver.model.World;
import org.l2jmobius.gameserver.model.WorldObject;
import org.l2jmobius.gameserver.model.actor.Creature;
import org.l2jmobius.gameserver.model.actor.Npc;
import org.l2jmobius.gameserver.model.actor.Player;
import org.l2jmobius.gameserver.model.actor.holders.npc.FakePlayerParty;
import org.l2jmobius.gameserver.model.actor.holders.npc.FakePlayerPvpBuild;
import org.l2jmobius.gameserver.model.actor.holders.npc.FakePlayerPvpProfile;
import org.l2jmobius.gameserver.model.actor.instance.Monster;
import org.l2jmobius.gameserver.model.actor.templates.NpcTemplate;
import org.l2jmobius.gameserver.model.mapdevice.MapArena;
import org.l2jmobius.gameserver.model.mapdevice.MapRun;
import org.l2jmobius.gameserver.model.mapdevice.MapRun.Status;
import org.l2jmobius.gameserver.model.mapdevice.UniqueMap;
import org.l2jmobius.gameserver.model.script.Quest;
import org.l2jmobius.gameserver.network.serverpackets.ExShowScreenMessage;
import org.l2jmobius.gameserver.network.serverpackets.MagicSkillCanceled;
import org.l2jmobius.gameserver.network.serverpackets.MagicSkillUse;
import org.l2jmobius.gameserver.network.serverpackets.NpcHtmlMessage;
import org.l2jmobius.gameserver.network.serverpackets.SetupGauge;
import org.l2jmobius.gameserver.taskmanagers.GameTimeTaskManager;

/**
 * The Tomb Raiders, a unique map of the Map Device ({@link UniqueMap.Type#TOMB}), worked from {@link UniqueMapManager}'s hooks.
 * <ul>
 * <li><b>The Race</b>: a rival party of fake players ({@link FakePlayerPvpManager#spawnMapRival}, driven by {@code FakePlayerPvpAI} through {@link #findTarget} and {@link #getGoal}) comes in from the far side when the first player does, and races the players for 5
 * Sealed Reliquaries. Breaking a seal takes {@value #CHANNEL_SECONDS} seconds of standing still, and any hit breaks it. The rivals carry the relics they open and drop them to the players when slain; they hunt the tomb, and ambush the players when they fall behind.</li>
 * <li><b>Truce or Rivalry</b>: when the second reliquary opens the rivals' leader parleys. A Truce puts the rivals in the players' party and shares the relics; Rivalry (or no answer) is winner takes all.</li>
 * <li><b>The Undying Emperor</b>: 4 Honour Guards stand at 4 Relic Pedestals round him. While one stands he takes half damage, and a slain guard rises again unless a relic seals its pedestal. If a rival lands his last blow his spoils are halved.</li>
 * <li><b>The reward of its own</b>: one roll of the special drops for every relic the players hold at the end; all five add a Map (Tier 3).</li>
 * </ul>
 * @author Mobius
 */
public class TombRaidersManager
{
	private static final Logger LOGGER = Logger.getLogger(TombRaidersManager.class.getName());

	/** The players' command, {@code .tomb}. */
	public static final String COMMAND = "tomb";

	// Npcs (data/stats/npcs/custom/MapDevice.xml).
	public static final int RELIQUARY = 900716;
	public static final int PEDESTAL = 900717;
	private static final int HONOUR_GUARD_NPC = 21418; // Imperial Royal Guard

	// The reliquaries.
	private static final int RELIQUARIES = 5;
	private static final double RELIQUARY_SPREAD = 0.75;
	private static final int RELIQUARY_SPACING = 2500;
	private static final int CHANNEL_SECONDS = 30;
	private static final int CHANNEL_REACH = 200;
	private static final int CHANNEL_MOVE = 50;
	private static final int CHANNEL_SKILL_ID = 1050; // Return (visual only)

	// The rivals.
	private static final int RIVALS = 4;
	private static final int RIVAL_LEVEL_CAP = 80;
	private static final int RIVAL_LEVEL_BELOW = 2;
	private static final String RIVAL_TITLE = "Tomb Raider";
	private static final int RIVAL_REACH = 150;
	private static final int HUNT_RANGE = 700;
	private static final int TRUCE_RANGE = 900;
	private static final int INTERRUPT_RANGE = 900;
	private static final int AMBUSH_RANGE = 1000;
	private static final int PARLEY_SECONDS = 30;
	private static final int FINALE_AMBUSH_INTERVAL = 15;

	// The Undying Emperor.
	private static final int PEDESTALS = 4;
	private static final int PEDESTAL_DISTANCE = 700;
	private static final int PEDESTAL_REACH = 250;
	private static final long GUARD_RISE_DELAY = 10000;
	private static final double GUARDED_DAMAGE = 0.5;
	private static final double STOLEN_DROPS = 0.5;
	private static final int BONUS_MAP_TIER = 3;

	/** What the players and the rivals agreed. */
	public enum Pact
	{
		NONE,
		TRUCE,
		RIVALRY
	}

	private static class Reliquary
	{
		final int _index;
		final Location _spot;
		volatile int _npcId;
		volatile boolean _opened;

		Reliquary(int index, Location spot)
		{
			_index = index;
			_spot = spot;
		}
	}

	/** Someone breaking a seal: who, which reliquary, until when, and their HP, CP and spot when it started (a hit or a step breaks it). */
	private static class Channel
	{
		final Reliquary _reliquary;
		final long _end;
		final int _x;
		final int _y;
		double _hp;
		double _cp;

		Channel(Reliquary reliquary, long end, Creature creature)
		{
			_reliquary = reliquary;
			_end = end;
			_x = creature.getX();
			_y = creature.getY();
			_hp = creature.getCurrentHp();
			_cp = creature.getCurrentCp();
		}
	}

	private static class Pedestal
	{
		final Location _spot;
		volatile int _npcId;
		volatile boolean _sealed;
		volatile int _guardId;
		volatile long _guardDiedAt;

		Pedestal(Location spot)
		{
			_spot = spot;
		}
	}

	private static class Tomb
	{
		final List<Reliquary> _reliquaries = new ArrayList<>();
		final List<Integer> _rivals = new CopyOnWriteArrayList<>();
		/** The relics each rival carries. */
		final Map<Integer, Integer> _carried = new ConcurrentHashMap<>();
		final Map<Integer, Channel> _playerChannels = new ConcurrentHashMap<>();
		final Map<Integer, Channel> _rivalChannels = new ConcurrentHashMap<>();
		final List<Pedestal> _pedestals = new CopyOnWriteArrayList<>();
		Location _rivalStart;
		volatile int _playerRelics;
		volatile int _spent;
		volatile boolean _rivalsSpawned;
		volatile Pact _pact = Pact.NONE;
		volatile long _parleyUntil;
		volatile boolean _parleyOffered;
		volatile FakePlayerParty _warband;
		volatile FakePlayerParty _truceParty;
		volatile boolean _finale;
		volatile boolean _stolen;
	}

	private static final Map<Integer, Tomb> TOMBS = new ConcurrentHashMap<>();

	protected TombRaidersManager()
	{
	}

	private static Tomb tomb(MapRun run)
	{
		return TOMBS.computeIfAbsent(run.getInstanceId(), k -> new Tomb());
	}

	private static MapRun tombRun(Player player)
	{
		final MapRun run = MapDeviceManager.getRun(player.getInstanceId());
		return (run != null) && run.isUnique() && (run.getUnique().type() == UniqueMap.Type.TOMB) && !run.isClosed() ? run : null;
	}

	/**
	 * @return the Tomb a rival belongs to, {@code null} for none
	 */
	private static Tomb tombOf(Npc npc)
	{
		if ((npc == null) || !npc.isPvpFakePlayer())
		{
			return null;
		}
		final FakePlayerPvpProfile profile = npc.getTemplate().getFakePlayerPvpProfile();
		return profile.isMapRival() ? TOMBS.get(profile.getMapRivalInstance()) : null;
	}

	private static Npc findNpc(int objectId)
	{
		if (objectId == 0)
		{
			return null;
		}
		final WorldObject object = World.getInstance().findObject(objectId);
		return (object instanceof Npc npc) && !npc.isDead() && npc.isSpawned() ? npc : null;
	}

	private static int rivalRelics(Tomb tomb)
	{
		int relics = 0;
		for (int carried : tomb._carried.values())
		{
			relics += carried;
		}
		return relics;
	}

	/**
	 * @return the relics the players hold: their own, or in a Truce half of all of them (rounded up)
	 */
	private static int ownedRelics(Tomb tomb)
	{
		return tomb._pact == Pact.TRUCE ? (tomb._playerRelics + rivalRelics(tomb) + 1) / 2 : tomb._playerRelics;
	}

	private static int openedReliquaries(Tomb tomb)
	{
		int opened = 0;
		for (Reliquary reliquary : tomb._reliquaries)
		{
			if (reliquary._opened)
			{
				opened++;
			}
		}
		return opened;
	}

	private static boolean isChanneled(Tomb tomb, Reliquary reliquary)
	{
		for (Channel channel : tomb._playerChannels.values())
		{
			if (channel._reliquary == reliquary)
			{
				return true;
			}
		}
		for (Channel channel : tomb._rivalChannels.values())
		{
			if (channel._reliquary == reliquary)
			{
				return true;
			}
		}
		return false;
	}

	private static List<Npc> aliveRivals(Tomb tomb)
	{
		final List<Npc> rivals = new ArrayList<>();
		for (int objectId : tomb._rivals)
		{
			final Npc rival = findNpc(objectId);
			if (rival != null)
			{
				rivals.add(rival);
			}
		}
		return rivals;
	}

	// =========================================================================
	// Opening
	// =========================================================================

	/**
	 * The Tomb opened: five Sealed Reliquaries stand spread over the field, and the rivals' way in is chosen (the far side, from the entry).
	 * @param run the run
	 */
	static void onRunOpened(MapRun run)
	{
		final Tomb tomb = tomb(run);
		final MapArena arena = run.getArena();
		final List<Location> taken = new ArrayList<>();
		taken.add(new Location(arena.getEntryX(), arena.getEntryY(), arena.z()));
		for (int i = 0; i < RELIQUARIES; i++)
		{
			final Location spot = MapDeviceManager.spacedSpot(() -> MapDeviceManager.randomFieldSpot(run, RELIQUARY_SPREAD, true), taken, RELIQUARY_SPACING);
			taken.add(spot);
			final Reliquary reliquary = new Reliquary(i, spot);
			final Npc npc = spawnNpc(run, RELIQUARY, spot);
			reliquary._npcId = npc != null ? npc.getObjectId() : 0;
			tomb._reliquaries.add(reliquary);
		}

		final double dx = arena.x() - arena.getEntryX();
		final double dy = arena.y() - arena.getEntryY();
		final double toMiddle = Math.max(1, Math.hypot(dx, dy));
		final double ux = dx / toMiddle;
		final double uy = dy / toMiddle;
		final double radiusAlong = Math.hypot(arena.radiusX() * ux, arena.radiusY() * uy);
		tomb._rivalStart = MapDeviceManager.getGroundLocation(new Location(arena.x() + (int) (ux * radiusAlong * 0.7), arena.y() + (int) (uy * radiusAlong * 0.7), arena.z()));
	}

	private static Npc spawnNpc(MapRun run, int npcId, Location spot)
	{
		if (NpcData.getInstance().getTemplate(npcId) == null)
		{
			LOGGER.warning(TombRaidersManager.class.getSimpleName() + ": No npc " + npcId + " (data/stats/npcs/custom/MapDevice.xml).");
			return null;
		}
		final Npc npc = Quest.addSpawn(npcId, spot.getX(), spot.getY(), spot.getZ(), Rnd.get(65536), false, 0, false, run.getInstanceId());
		if (npc != null)
		{
			npc.setRandomWalking(false);
		}
		return npc;
	}

	private static void setTitle(Npc npc, String title)
	{
		if (npc != null)
		{
			npc.setTitle(title);
			npc.broadcastInfo();
		}
	}

	/**
	 * @return a spot on the ground within {@code range} of a spot
	 */
	private static Location around(MapRun run, Location center, int range)
	{
		final GeoEngine geo = GeoEngine.getInstance();
		final double angle = Rnd.nextDouble() * 2 * Math.PI;
		final double distance = Math.sqrt(Rnd.nextDouble()) * range;
		final int x = center.getX() + (int) (Math.cos(angle) * distance);
		final int y = center.getY() + (int) (Math.sin(angle) * distance);
		return geo.getValidLocation(center.getX(), center.getY(), center.getZ(), x, y, geo.getHeight(x, y, center.getZ()), run.getInstanceId());
	}

	/**
	 * The rival party comes in from the far side: one support and the rest fighters, a little below the player's level.
	 */
	private static void spawnRivals(MapRun run, Tomb tomb, Player player)
	{
		final FakePlayerPvpData builds = FakePlayerPvpData.getInstance();
		final int level = Math.max(1, Math.min(player.getLevel(), RIVAL_LEVEL_CAP) - RIVAL_LEVEL_BELOW);
		final List<Npc> rivals = new ArrayList<>();
		for (int i = 0; i < RIVALS; i++)
		{
			FakePlayerPvpBuild build = i == 0 ? builds.getRandomBuild(FakePlayerPvpBuild::isSupport) : builds.getRandomBuild(b -> !b.isSupport());
			if (build == null)
			{
				build = builds.getRandomBuild();
			}
			final Location spot = around(run, tomb._rivalStart, 300);
			final Npc rival = FakePlayerPvpManager.getInstance().spawnMapRival(build, level, RIVAL_TITLE, spot.getX(), spot.getY(), spot.getZ(), run.getInstanceId());
			if (rival != null)
			{
				rivals.add(rival);
				tomb._rivals.add(rival.getObjectId());
				tomb._carried.put(rival.getObjectId(), 0);
			}
		}
		if (rivals.isEmpty())
		{
			LOGGER.warning(TombRaidersManager.class.getSimpleName() + ": No rival party for " + run.getOwnerName() + "'s Tomb (instance " + run.getInstanceId() + "): are the fake players' builds loaded (data/FakePlayerPvp.xml)?");
			return;
		}
		tomb._warband = FakePartyManager.getInstance().createWarbandParty(rivals);
		MapDeviceManager.sendScreenToMap(run, "A rival party of " + rivals.size() + " Tomb Raiders comes in from the far side!");
	}

	// =========================================================================
	// The rivals' brain (FakePlayerPvpAI#thinkMapRival)
	// =========================================================================

	/**
	 * @param npc a rival
	 * @return {@code true} while it breaks a seal (it stands still)
	 */
	public static boolean isChanneling(Npc npc)
	{
		final Tomb tomb = tombOf(npc);
		return (tomb != null) && tomb._rivalChannels.containsKey(npc.getObjectId());
	}

	/**
	 * @param npc a rival
	 * @return what it should fight now, {@code null} for nothing: in a Truce the tomb's monsters near it; in the final encounter the Emperor and his guards; else a player breaking a seal near it, a player near it when the rivals are behind, or a monster on
	 *         its way
	 */
	public static Creature findTarget(Npc npc)
	{
		final Tomb tomb = tombOf(npc);
		final MapRun run = tomb != null ? MapDeviceManager.getRun(npc.getInstanceId()) : null;
		if ((run == null) || (run.getStatus() != Status.OPEN))
		{
			return null;
		}

		if (tomb._pact == Pact.TRUCE)
		{
			return nearestMonster(run, npc, TRUCE_RANGE, true);
		}

		if (tomb._finale)
		{
			final Monster foe = nearestMonster(run, npc, HUNT_RANGE, true);
			return foe != null ? foe : UniqueMapManager.getBoss(run);
		}

		// A player breaking a seal: break their focus.
		for (Map.Entry<Integer, Channel> entry : tomb._playerChannels.entrySet())
		{
			final Player player = World.getInstance().getPlayer(entry.getKey());
			if ((player != null) && !player.isDead() && (player.getInstanceId() == npc.getInstanceId()) && (npc.calculateDistance2D(player) <= INTERRUPT_RANGE))
			{
				return player;
			}
		}

		// Behind in the race: an ambush.
		if (rivalRelics(tomb) < tomb._playerRelics)
		{
			Player best = null;
			double bestDistance = AMBUSH_RANGE;
			for (Player player : MapDeviceManager.getPlayersInside(run))
			{
				final double distance = npc.calculateDistance2D(player);
				if (!player.isDead() && (distance < bestDistance))
				{
					bestDistance = distance;
					best = player;
				}
			}
			if (best != null)
			{
				return best;
			}
		}

		return nearestMonster(run, npc, HUNT_RANGE, false);
	}

	/**
	 * @return the nearest living monster of the map within range (with {@code encounter}, also the final encounter's)
	 */
	private static Monster nearestMonster(MapRun run, Npc npc, int range, boolean encounter)
	{
		Monster best = null;
		double bestDistance = range;
		for (Monster monster : World.getInstance().getVisibleObjectsInRange(npc, Monster.class, range))
		{
			if (monster.isDead() || monster.isFakePlayer() || (monster.getInstanceId() != run.getInstanceId()))
			{
				continue;
			}
			if (!run.getMonsters().contains(monster.getObjectId()) && (!encounter || (UniqueMapManager.role(monster) == 0)))
			{
				continue;
			}
			final double distance = npc.calculateDistance2D(monster);
			if (distance < bestDistance)
			{
				bestDistance = distance;
				best = monster;
			}
		}
		return best;
	}

	/**
	 * @param npc a rival
	 * @return where it should go: the Emperor in the final encounter, the sealed reliquary nearest the party, or nowhere (in a Truce it follows the players' party)
	 */
	public static Location getGoal(Npc npc)
	{
		final Tomb tomb = tombOf(npc);
		final MapRun run = tomb != null ? MapDeviceManager.getRun(npc.getInstanceId()) : null;
		if ((run == null) || (tomb._pact == Pact.TRUCE))
		{
			return null;
		}
		if (tomb._finale)
		{
			final Monster boss = UniqueMapManager.getBoss(run);
			return boss != null ? new Location(boss.getX(), boss.getY(), boss.getZ()) : null;
		}
		final Reliquary reliquary = rivalGoal(tomb, npc);
		return reliquary != null ? reliquary._spot : null;
	}

	/**
	 * @return the sealed reliquary nearest a rival that nobody else is breaking
	 */
	private static Reliquary rivalGoal(Tomb tomb, Npc npc)
	{
		Reliquary best = null;
		double bestDistance = Double.MAX_VALUE;
		for (Reliquary reliquary : tomb._reliquaries)
		{
			if (reliquary._opened || tomb._playerChannels.values().stream().anyMatch(channel -> channel._reliquary == reliquary))
			{
				continue;
			}
			final double distance = npc.calculateDistance2D(reliquary._spot);
			if (distance < bestDistance)
			{
				bestDistance = distance;
				best = reliquary;
			}
		}
		return best;
	}

	// =========================================================================
	// Breaking seals
	// =========================================================================

	/**
	 * A player starts breaking the seal of the reliquary next to them: {@value #CHANNEL_SECONDS} seconds of standing still, which any hit breaks.
	 * @param player the player
	 * @return why not, {@code null} if started
	 */
	public String breakSeal(Player player)
	{
		final MapRun run = tombRun(player);
		if (run == null)
		{
			return "There is no reliquary here.";
		}
		final Tomb tomb = tomb(run);
		Reliquary nearest = null;
		double bestDistance = CHANNEL_REACH;
		for (Reliquary reliquary : tomb._reliquaries)
		{
			final double distance = player.calculateDistance2D(reliquary._spot);
			if (!reliquary._opened && (distance <= bestDistance))
			{
				bestDistance = distance;
				nearest = reliquary;
			}
		}
		if (nearest == null)
		{
			return "Stand by a sealed reliquary to break its seal.";
		}
		if (tomb._playerChannels.containsKey(player.getObjectId()))
		{
			return "You are already breaking a seal.";
		}
		if (isChanneled(tomb, nearest))
		{
			return "Someone is already breaking this seal.";
		}
		if (player.isDead() || player.isCastingNow() || player.isMovementDisabled() || player.isAttackingNow())
		{
			return "You can't do that now.";
		}

		final int millis = CHANNEL_SECONDS * 1000;
		final Channel channel = new Channel(nearest, System.currentTimeMillis() + millis, player);
		tomb._playerChannels.put(player.getObjectId(), channel);
		player.stopMove(null);
		player.getAI().setIntention(Intention.IDLE);
		player.forceIsCasting(GameTimeTaskManager.getInstance().getGameTicks() + (millis / GameTimeTaskManager.MILLIS_IN_TICK));
		player.disableAllSkills();
		player.broadcastSkillPacket(new MagicSkillUse(player, CHANNEL_SKILL_ID, 1, millis, 0), player);
		player.sendPacket(new SetupGauge(player.getObjectId(), 0, millis));
		player.setSkillCast(ThreadPool.schedule(() -> finishPlayerChannel(run, tomb, player, channel), millis));
		player.sendMessage("You break the seal of the reliquary. Hold still: a hit breaks your focus.");
		return null;
	}

	private static void finishPlayerChannel(MapRun run, Tomb tomb, Player player, Channel channel)
	{
		if (!tomb._playerChannels.remove(player.getObjectId(), channel))
		{
			return;
		}
		player.enableAllSkills();
		player.setCastingNow(false);
		if (run.isClosed() || player.isDead() || (player.getInstanceId() != run.getInstanceId()) || channel._reliquary._opened)
		{
			return;
		}
		openReliquary(run, tomb, channel._reliquary, player.getName(), true);
		tomb._playerRelics++;
		announceScore(run, tomb, player.getName() + " breaks the seal of a reliquary!");
	}

	/**
	 * Breaks off a player's channel (a hit, a step, death, leaving).
	 */
	private static void interruptPlayer(Tomb tomb, Player player, String reason)
	{
		if (tomb._playerChannels.remove(player.getObjectId()) == null)
		{
			return;
		}
		player.abortCast();
		player.enableAllSkills();
		if (reason != null)
		{
			player.sendPacket(new ExShowScreenMessage(reason, 2500));
		}
	}

	private static void openReliquary(MapRun run, Tomb tomb, Reliquary reliquary, String by, boolean players)
	{
		reliquary._opened = true;
		final Npc npc = findNpc(reliquary._npcId);
		if (npc != null)
		{
			npc.broadcastPacket(new MagicSkillUse(npc, npc, 4526, 1, 0, 0));
			setTitle(npc, "Opened" + (players ? " by " + by : " by the rivals"));
		}
		if ((openedReliquaries(tomb) == 2) && !tomb._parleyOffered && !aliveRivals(tomb).isEmpty())
		{
			offerParley(run, tomb);
		}
	}

	private static void announceScore(MapRun run, Tomb tomb, String what)
	{
		final String score = tomb._pact == Pact.TRUCE ? "Shared relics: " + (tomb._playerRelics + rivalRelics(tomb)) : "Relics: you " + tomb._playerRelics + ", the rivals " + rivalRelics(tomb);
		MapDeviceManager.sendScreenToMap(run, what + "  " + score + " (" + openedReliquaries(tomb) + " of " + tomb._reliquaries.size() + " open)");
	}

	// =========================================================================
	// The parley
	// =========================================================================

	private static void offerParley(MapRun run, Tomb tomb)
	{
		tomb._parleyOffered = true;
		tomb._parleyUntil = System.currentTimeMillis() + (PARLEY_SECONDS * 1000L);
		MapDeviceManager.sendScreenToMap(run, "The Tomb Raiders' leader calls a parley: a Truce, or Rivalry?");
		for (Player player : MapDeviceManager.getPlayersInside(run))
		{
			showWindow(player);
		}
	}

	/**
	 * A player answers the rivals' parley.
	 * @param player the player
	 * @param truce {@code true} for a Truce, {@code false} for Rivalry
	 * @return why not, {@code null} if answered
	 */
	public String choosePact(Player player, boolean truce)
	{
		final MapRun run = tombRun(player);
		if (run == null)
		{
			return "Nobody here wants a word with you.";
		}
		final Tomb tomb = tomb(run);
		synchronized (tomb)
		{
			if ((tomb._pact != Pact.NONE) || !tomb._parleyOffered)
			{
				return tomb._pact == Pact.NONE ? "The rivals have not called a parley yet." : "The pact is made: " + (tomb._pact == Pact.TRUCE ? "a Truce." : "Rivalry.");
			}
			tomb._pact = truce ? Pact.TRUCE : Pact.RIVALRY;
		}
		if (truce)
		{
			makeTruce(run, tomb, player);
		}
		else
		{
			MapDeviceManager.sendScreenToMap(run, player.getName() + " answers: Rivalry! Winner takes all.");
		}
		LOGGER.info(TombRaidersManager.class.getSimpleName() + ": " + player.getName() + " chose " + tomb._pact + " in " + run.getOwnerName() + "'s Tomb (instance " + run.getInstanceId() + ").");
		return null;
	}

	/**
	 * The rivals join the players' party: they heal, buff and fight beside them, and the relics are shared.
	 */
	private static void makeTruce(MapRun run, Tomb tomb, Player player)
	{
		final List<Npc> rivals = aliveRivals(tomb);
		final FakePartyManager parties = FakePartyManager.getInstance();
		if (tomb._warband != null)
		{
			parties.disbandEventParty(tomb._warband);
			tomb._warband = null;
		}
		for (Npc rival : rivals)
		{
			rival.setScriptValue(0);
			if (rival.isAttackable())
			{
				rival.asAttackable().clearAggroList();
			}
			rival.broadcastInfo();
		}
		for (Player inside : MapDeviceManager.getPlayersInside(run))
		{
			if ((inside.getTarget() instanceof Npc target) && tomb._rivals.contains(target.getObjectId()))
			{
				inside.abortAttack();
				inside.setTarget(null);
			}
		}
		if (!rivals.isEmpty())
		{
			final Player host = (player.getParty() != null) && (player.getParty().getLeader() != null) ? player.getParty().getLeader() : player;
			tomb._truceParty = parties.createEventParty(host, rivals);
		}
		MapDeviceManager.sendScreenToMap(run, player.getName() + " shakes hands: a Truce! The Tomb Raiders fight beside you, and the relics are shared.");
	}

	// =========================================================================
	// The Undying Emperor
	// =========================================================================

	/**
	 * The field is cleared: the Undying Emperor rises with his Honour Guards at their pedestals ({@link UniqueMapManager}'s final encounter).
	 * @param run the run
	 * @param template the Emperor's npc
	 * @param middle where he rises
	 */
	static void startFinale(MapRun run, NpcTemplate template, Location middle)
	{
		final Tomb tomb = tomb(run);
		tomb._finale = true;
		final Monster boss = UniqueMapManager.spawnBoss(run, template, middle, "[" + run.getUnique().bossName() + "]");
		if (boss == null)
		{
			return;
		}
		final NpcTemplate guardTemplate = NpcData.getInstance().getTemplate(HONOUR_GUARD_NPC);
		final double start = Rnd.nextDouble() * 2 * Math.PI;
		for (int i = 0; i < PEDESTALS; i++)
		{
			final double angle = start + ((i * 2 * Math.PI) / PEDESTALS);
			final Location spot = MapDeviceManager.getGroundLocation(new Location(middle.getX() + (int) (Math.cos(angle) * PEDESTAL_DISTANCE), middle.getY() + (int) (Math.sin(angle) * PEDESTAL_DISTANCE), middle.getZ()));
			final Pedestal pedestal = new Pedestal(spot);
			final Npc npc = spawnNpc(run, PEDESTAL, spot);
			pedestal._npcId = npc != null ? npc.getObjectId() : 0;
			tomb._pedestals.add(pedestal);
			if (guardTemplate != null)
			{
				raiseGuard(run, pedestal, guardTemplate);
			}
		}
		UniqueMapManager.attack(boss, MapDeviceManager.getPlayersInside(run));
		MapDeviceManager.sendToMap(run, "The " + run.getUnique().bossName() + " takes half damage while one of his " + PEDESTALS + " Honour Guards stands, and a slain guard rises again at its pedestal " + (GUARD_RISE_DELAY / 1000) + " seconds later. Seal a pedestal with a relic (talk to it, or ." + COMMAND + ") to keep its guard down: " + Math.max(0, ownedRelics(tomb) - tomb._spent) + " relics to spare, each one a roll of the spoils.");
	}

	private static void raiseGuard(MapRun run, Pedestal pedestal, NpcTemplate template)
	{
		final Monster guard = UniqueMapManager.spawnEncounter(run, template, around(run, pedestal._spot, 100), UniqueMapManager.ROLE_HONOUR_GUARD, "[Honour Guard]", false);
		if (guard != null)
		{
			pedestal._guardId = guard.getObjectId();
			pedestal._guardDiedAt = 0;
			guard.broadcastPacket(new MagicSkillUse(guard, guard, 4526, 1, 0, 0));
			UniqueMapManager.attack(guard, MapDeviceManager.getPlayersInside(run));
		}
	}

	/**
	 * @return the damage the Emperor takes: half while an Honour Guard stands
	 */
	static double onBossDamaged(MapRun run, Monster boss, double damage, Creature attacker)
	{
		final Tomb tomb = TOMBS.get(run.getInstanceId());
		if (tomb == null)
		{
			return damage;
		}
		for (Pedestal pedestal : tomb._pedestals)
		{
			if (findNpc(pedestal._guardId) != null)
			{
				if ((attacker != null) && attacker.isPlayer() && (Rnd.get(15) == 0))
				{
					attacker.sendPacket(new ExShowScreenMessage("The Honour Guards shield their Emperor: he takes half damage!", 2000));
				}
				return damage * GUARDED_DAMAGE;
			}
		}
		return damage;
	}

	/**
	 * At half HP the Emperor calls every fallen guard of an unsealed pedestal back at once.
	 */
	static void onBossThreshold(MapRun run, Monster boss, int index)
	{
		final Tomb tomb = TOMBS.get(run.getInstanceId());
		final NpcTemplate template = NpcData.getInstance().getTemplate(HONOUR_GUARD_NPC);
		if ((tomb == null) || (template == null))
		{
			return;
		}
		int risen = 0;
		for (Pedestal pedestal : tomb._pedestals)
		{
			if (!pedestal._sealed && (findNpc(pedestal._guardId) == null))
			{
				raiseGuard(run, pedestal, template);
				risen++;
			}
		}
		boss.broadcastPacket(new MagicSkillUse(boss, boss, 4526, 1, 0, 0));
		MapDeviceManager.sendScreenToMap(run, risen > 0 ? "The " + run.getUnique().bossName() + " calls his guard: " + risen + " Honour Guards rise!" : "The " + run.getUnique().bossName() + " calls his guard, but the sealed pedestals stay silent.");
	}

	/**
	 * A player seals the pedestal next to them with a relic: its Honour Guard never rises again.
	 * @param player the player
	 * @return why not, {@code null} if sealed
	 */
	public String seal(Player player)
	{
		final MapRun run = tombRun(player);
		if (run == null)
		{
			return "There is no pedestal here.";
		}
		final Tomb tomb = tomb(run);
		Pedestal nearest = null;
		double bestDistance = PEDESTAL_REACH;
		for (Pedestal pedestal : tomb._pedestals)
		{
			final double distance = player.calculateDistance2D(pedestal._spot);
			if (distance <= bestDistance)
			{
				bestDistance = distance;
				nearest = pedestal;
			}
		}
		if (nearest == null)
		{
			return "Stand by a Relic Pedestal to seal it.";
		}
		synchronized (tomb)
		{
			if (nearest._sealed)
			{
				return "This pedestal is sealed already.";
			}
			if ((ownedRelics(tomb) - tomb._spent) < 1)
			{
				return "You have no relic to spare.";
			}
			tomb._spent++;
			nearest._sealed = true;
		}
		final Npc npc = findNpc(nearest._npcId);
		if (npc != null)
		{
			npc.broadcastPacket(new MagicSkillUse(npc, npc, 4526, 1, 0, 0));
			setTitle(npc, "Sealed");
		}
		MapDeviceManager.sendScreenToMap(run, player.getName() + " seals a pedestal with a relic: its Honour Guard will not rise again.");
		return null;
	}

	/**
	 * @param run the run
	 * @param player the player credited with the Emperor's death, {@code null} if a rival landed the last blow
	 * @return what the Emperor's drop chances are multiplied by: halved when a rival stole his last blow (not in a Truce)
	 */
	static double getBossDropFactor(MapRun run, Player player)
	{
		final Tomb tomb = TOMBS.get(run.getInstanceId());
		if ((tomb == null) || (player != null) || (tomb._pact == Pact.TRUCE))
		{
			return 1.0;
		}
		tomb._stolen = true;
		return STOLEN_DROPS;
	}

	/**
	 * @return who the Emperor's spoils fall for when no player is credited: the owner if inside, else anyone inside
	 */
	static Player getHeir(MapRun run)
	{
		Player heir = null;
		for (Player player : MapDeviceManager.getPlayersInside(run))
		{
			if (player.getObjectId() == run.getOwnerId())
			{
				return player;
			}
			if (heir == null)
			{
				heir = player;
			}
		}
		return heir;
	}

	/**
	 * The Emperor fell: a roll of the special drops for every relic the players hold, and a Map (Tier 3) for all five.
	 */
	static void onBossDied(MapRun run, Monster boss, Player player)
	{
		final Tomb tomb = TOMBS.get(run.getInstanceId());
		if (tomb == null)
		{
			return;
		}
		if (tomb._stolen)
		{
			MapDeviceManager.sendScreenToMap(run, "A Tomb Raider steals the last blow! The Emperor's spoils are halved.");
		}
		final int owned = ownedRelics(tomb);
		final int rolls = Math.max(0, owned - tomb._spent);
		if (player != null)
		{
			for (int i = 0; i < rolls; i++)
			{
				UniqueMapManager.dropSpecial(run, boss, player, 1);
			}
			if ((owned >= tomb._reliquaries.size()) && (tomb._pact != Pact.TRUCE) && MapDeviceManager.dropRunMap(run, boss, player, BONUS_MAP_TIER))
			{
				MapDeviceManager.sendToMap(run, "All five relics are yours: the tomb gives up a Map (Tier " + BONUS_MAP_TIER + ")!");
			}
		}
		MapDeviceManager.sendToMap(run, "The reliquaries' spoils: " + rolls + (rolls == 1 ? " roll" : " rolls") + " (" + owned + " relics held, " + tomb._spent + " spent on the pedestals).");
	}

	/**
	 * A monster of the Tomb died: a slain Honour Guard rises again later, unless its pedestal is sealed.
	 */
	static void onMonsterDied(MapRun run, Monster victim, int role)
	{
		final Tomb tomb = TOMBS.get(run.getInstanceId());
		if ((tomb == null) || (role != UniqueMapManager.ROLE_HONOUR_GUARD))
		{
			return;
		}
		for (Pedestal pedestal : tomb._pedestals)
		{
			if (pedestal._guardId == victim.getObjectId())
			{
				pedestal._guardId = 0;
				pedestal._guardDiedAt = System.currentTimeMillis();
				if (!pedestal._sealed)
				{
					MapDeviceManager.sendToMap(run, "An Honour Guard falls. It rises again in " + (GUARD_RISE_DELAY / 1000) + " seconds, unless you seal its pedestal with a relic.");
				}
			}
		}
	}

	/**
	 * A rival died: the relics it carried go to the players.
	 */
	static void onRivalDied(MapRun run, Monster victim, Player player)
	{
		final Tomb tomb = TOMBS.get(run.getInstanceId());
		if ((tomb == null) || !tomb._rivals.remove(Integer.valueOf(victim.getObjectId())))
		{
			return;
		}
		tomb._rivalChannels.remove(victim.getObjectId());
		final Integer carried = tomb._carried.remove(victim.getObjectId());
		if ((carried != null) && (carried > 0))
		{
			tomb._playerRelics += carried;
			announceScore(run, tomb, victim.getName() + " falls, and drops " + carried + (carried == 1 ? " relic!" : " relics!"));
		}
		else
		{
			MapDeviceManager.sendToMap(run, "The Tomb Raider " + victim.getName() + " falls" + (player != null ? " to " + player.getName() : "") + ".");
		}
		if (tomb._rivals.isEmpty())
		{
			MapDeviceManager.sendScreenToMap(run, "The rival party is no more. The tomb is yours.");
		}
	}

	// =========================================================================
	// The clock
	// =========================================================================

	/**
	 * Once a second ({@link UniqueMapManager#onTick}).
	 * @param run the run
	 * @param tick seconds since it opened
	 * @param now the time (ms)
	 * @param phase 0 while the field is cleared, 1 once the Emperor is called
	 */
	static void onTick(MapRun run, int tick, long now, int phase)
	{
		final Tomb tomb = TOMBS.get(run.getInstanceId());
		if (tomb == null)
		{
			return;
		}

		// The players' channels: a hit or a step breaks them.
		for (Map.Entry<Integer, Channel> entry : tomb._playerChannels.entrySet())
		{
			final Channel channel = entry.getValue();
			final Player player = World.getInstance().getPlayer(entry.getKey());
			if ((player == null) || player.isDead() || (player.getInstanceId() != run.getInstanceId()))
			{
				tomb._playerChannels.remove(entry.getKey());
				if (player != null)
				{
					player.abortCast();
					player.enableAllSkills();
				}
				continue;
			}
			if ((player.getCurrentHp() < (channel._hp - 1)) || (player.getCurrentCp() < (channel._cp - 1)))
			{
				interruptPlayer(tomb, player, "A hit breaks your focus!");
				continue;
			}
			if (Math.hypot(player.getX() - channel._x, player.getY() - channel._y) > CHANNEL_MOVE)
			{
				interruptPlayer(tomb, player, "You moved: the seal holds.");
				continue;
			}
			channel._hp = player.getCurrentHp();
			channel._cp = player.getCurrentCp();
		}

		// The rivals' channels.
		for (Map.Entry<Integer, Channel> entry : tomb._rivalChannels.entrySet())
		{
			final Channel channel = entry.getValue();
			final Npc rival = findNpc(entry.getKey());
			if ((rival == null) || channel._reliquary._opened || (rival.getCurrentHp() < (channel._hp - 1)) || (Math.hypot(rival.getX() - channel._x, rival.getY() - channel._y) > CHANNEL_MOVE))
			{
				tomb._rivalChannels.remove(entry.getKey());
				if (rival != null)
				{
					rival.broadcastPacket(new MagicSkillCanceled(rival.getObjectId()));
				}
				continue;
			}
			channel._hp = rival.getCurrentHp();
			if (now >= channel._end)
			{
				tomb._rivalChannels.remove(entry.getKey());
				tomb._carried.merge(rival.getObjectId(), 1, Integer::sum);
				openReliquary(run, tomb, channel._reliquary, rival.getName(), false);
				announceScore(run, tomb, "The Tomb Raider " + rival.getName() + " breaks the seal of a reliquary!");
			}
		}

		// A rival at a sealed reliquary, out of the fight, starts breaking it.
		if (phase == 0)
		{
			for (Npc rival : aliveRivals(tomb))
			{
				if (tomb._rivalChannels.containsKey(rival.getObjectId()) || rival.isAttackingNow() || rival.isCastingNow() || (rival.getAI().getIntention() == Intention.ATTACK))
				{
					continue;
				}
				final Reliquary goal = rivalGoal(tomb, rival);
				if ((goal == null) || isChanneled(tomb, goal) || (rival.calculateDistance2D(goal._spot) > RIVAL_REACH))
				{
					continue;
				}
				rival.stopMove(null);
				tomb._rivalChannels.put(rival.getObjectId(), new Channel(goal, now + (CHANNEL_SECONDS * 1000L), rival));
				rival.broadcastPacket(new MagicSkillUse(rival, rival, CHANNEL_SKILL_ID, 1, CHANNEL_SECONDS * 1000, 0));
				MapDeviceManager.sendToMap(run, "The Tomb Raider " + rival.getName() + " is breaking the seal of a reliquary. Stop them!");
			}
		}

		// No answer to the parley: Rivalry.
		if (tomb._parleyOffered && (tomb._pact == Pact.NONE) && (now > tomb._parleyUntil))
		{
			tomb._pact = Pact.RIVALRY;
			MapDeviceManager.sendScreenToMap(run, "No answer is an answer: Rivalry! Winner takes all.");
		}

		if (phase == 1)
		{
			// The Honour Guards rise again at their unsealed pedestals.
			final NpcTemplate template = NpcData.getInstance().getTemplate(HONOUR_GUARD_NPC);
			if ((template != null) && (UniqueMapManager.getBoss(run) != null))
			{
				for (Pedestal pedestal : tomb._pedestals)
				{
					if (!pedestal._sealed && (pedestal._guardId == 0) && (pedestal._guardDiedAt > 0) && ((now - pedestal._guardDiedAt) >= GUARD_RISE_DELAY))
					{
						raiseGuard(run, pedestal, template);
					}
				}
			}

			// In Rivalry, the rivals turn on the players now and then: a three-way fight.
			if ((tomb._pact != Pact.TRUCE) && ((tick % FINALE_AMBUSH_INTERVAL) == 0))
			{
				final List<Npc> rivals = aliveRivals(tomb);
				final List<Player> players = MapDeviceManager.getPlayersInside(run);
				players.removeIf(Player::isDead);
				if (!rivals.isEmpty() && !players.isEmpty())
				{
					final Npc rival = rivals.get(Rnd.get(rivals.size()));
					final Player target = players.get(Rnd.get(players.size()));
					if (rival.isAttackable() && (rival.calculateDistance2D(target) <= AMBUSH_RANGE))
					{
						FakePlayerPvpManager.getInstance().assistFight(rival.asAttackable(), target);
					}
				}
			}
		}
	}

	// =========================================================================
	// Players
	// =========================================================================

	/**
	 * A player came into the Tomb: the first one brings the rival party in.
	 */
	static void onPlayerEntered(Player player, MapRun run)
	{
		final Tomb tomb = tomb(run);
		boolean first = false;
		synchronized (tomb)
		{
			if (!tomb._rivalsSpawned && (tomb._rivalStart != null))
			{
				tomb._rivalsSpawned = true;
				first = true;
			}
		}
		if (first)
		{
			UniqueMapManager.keepAwake(run); // The rivals race on where no player is.
			spawnRivals(run, tomb, player);
		}
		player.sendMessage(run.getUnique().name() + ": break the seals of the 5 Sealed Reliquaries (talk to one) before the Tomb Raiders do. A hit breaks your focus. ." + COMMAND + " for the race.");
	}

	/**
	 * A player left the Tomb: their channel breaks.
	 */
	static void onPlayerLeft(Player player, MapRun run)
	{
		final Tomb tomb = TOMBS.get(run.getInstanceId());
		if (tomb != null)
		{
			interruptPlayer(tomb, player, null);
		}
	}

	/**
	 * The Tomb closed: the rival party is gone, its party broken up.
	 */
	static void onRunClosed(MapRun run)
	{
		final Tomb tomb = TOMBS.remove(run.getInstanceId());
		if (tomb == null)
		{
			return;
		}
		for (int objectId : tomb._playerChannels.keySet())
		{
			final Player player = World.getInstance().getPlayer(objectId);
			if (player != null)
			{
				interruptPlayer(tomb, player, null);
			}
		}
		final FakePartyManager parties = FakePartyManager.getInstance();
		if (tomb._truceParty != null)
		{
			parties.disbandEventParty(tomb._truceParty);
		}
		if (tomb._warband != null)
		{
			parties.disbandEventParty(tomb._warband);
		}
		for (Npc rival : aliveRivals(tomb))
		{
			if (rival.isAttackable())
			{
				rival.asAttackable().clearAggroList();
			}
			rival.abortAttack();
			rival.abortCast();
			rival.deleteMe();
		}
	}

	// =========================================================================
	// The window (.tomb, the reliquaries, the pedestals)
	// =========================================================================

	/**
	 * The Tomb's window: the race, the reliquaries, the parley, the pedestals.
	 * @param player the player
	 */
	public static void showWindow(Player player)
	{
		final StringBuilder sb = new StringBuilder(2048);
		sb.append("<html><body><center><font name=\"hs12\" color=\"FF9955\">The Tomb Raiders</font></center><br>");
		final MapRun run = tombRun(player);
		if (run == null)
		{
			sb.append("<font color=\"A0A0A0\">You are not the only party down here. Open a Unique Map: The Tomb Raiders at your Map Device.</font></body></html>");
			send(player, sb);
			return;
		}
		final Tomb tomb = tomb(run);
		final Pact pact = tomb._pact;
		sb.append("<table width=270>");
		if (pact == Pact.TRUCE)
		{
			row(sb, "Pact", "<font color=\"66CC66\">Truce</font>");
			row(sb, "Shared relics", (tomb._playerRelics + rivalRelics(tomb)) + " (yours: " + ownedRelics(tomb) + ")");
		}
		else
		{
			row(sb, "Pact", pact == Pact.RIVALRY ? "<font color=\"FF6666\">Rivalry</font>" : "none yet");
			row(sb, "Your relics", "<font color=\"E6C35C\">" + tomb._playerRelics + "</font>");
			row(sb, "The rivals' relics", rivalRelics(tomb) + " (" + aliveRivals(tomb).size() + " rivals standing)");
		}
		for (Reliquary reliquary : tomb._reliquaries)
		{
			final String state = reliquary._opened ? "<font color=\"707070\">opened</font>" : isChanneled(tomb, reliquary) ? "<font color=\"FF9955\">being broken</font>" : "sealed";
			row(sb, "Reliquary " + (reliquary._index + 1), state + " <font color=\"707070\">(" + Math.round(player.calculateDistance2D(reliquary._spot)) + " away)</font>");
		}
		if (!tomb._pedestals.isEmpty())
		{
			int sealed = 0;
			for (Pedestal pedestal : tomb._pedestals)
			{
				sealed += pedestal._sealed ? 1 : 0;
			}
			row(sb, "Sealed pedestals", sealed + " of " + tomb._pedestals.size());
			row(sb, "Relics to spare", String.valueOf(Math.max(0, ownedRelics(tomb) - tomb._spent)));
		}
		sb.append("</table><br>");

		if (tomb._parleyOffered && (pact == Pact.NONE) && (System.currentTimeMillis() <= tomb._parleyUntil))
		{
			sb.append("<font color=\"FF9955\">The Tomb Raiders' leader:</font> \"No sense killing each other over old bones. Split it with us, and we fight together. Or don't, and we'll see who walks out rich.\"<br>");
			sb.append("<font color=\"707070\">A Truce shares every relic; Rivalry is winner takes all. ").append((tomb._parleyUntil - System.currentTimeMillis()) / 1000).append("s to answer.</font><br><br>");
			button(sb, "A Truce", "truce");
			button(sb, "Rivalry", "rivalry");
		}
		else
		{
			boolean nearReliquary = false;
			for (Reliquary reliquary : tomb._reliquaries)
			{
				nearReliquary |= !reliquary._opened && (player.calculateDistance2D(reliquary._spot) <= CHANNEL_REACH);
			}
			boolean nearPedestal = false;
			for (Pedestal pedestal : tomb._pedestals)
			{
				nearPedestal |= !pedestal._sealed && (player.calculateDistance2D(pedestal._spot) <= PEDESTAL_REACH);
			}
			if (nearReliquary)
			{
				button(sb, "Break the Seal (" + CHANNEL_SECONDS + "s)", "break");
			}
			if (nearPedestal)
			{
				button(sb, "Seal the Pedestal (1 relic)", "seal");
			}
			sb.append("<br><font color=\"707070\">Every relic you hold at the end is one more roll of the reliquaries' spoils; all five add a Map (Tier ").append(BONUS_MAP_TIER).append("). A slain rival drops the relics it carries.</font>");
		}
		sb.append("</body></html>");
		send(player, sb);
	}

	private static void row(StringBuilder sb, String label, String value)
	{
		sb.append("<tr><td><font color=\"A0A0A0\">").append(label).append("</font></td><td align=right>").append(value).append("</td></tr>");
	}

	private static void button(StringBuilder sb, String label, String params)
	{
		sb.append("<center><button value=\"").append(label).append("\" action=\"bypass voiced_").append(COMMAND).append(' ').append(params).append("\" width=200 height=25 back=\"L2UI_CT1.Button_DF_Down\" fore=\"L2UI_CT1.Button_DF\"></center>");
	}

	private static void send(Player player, StringBuilder sb)
	{
		final NpcHtmlMessage html = new NpcHtmlMessage(0);
		html.setHtml(sb.toString());
		player.sendPacket(html);
	}

	public static TombRaidersManager getInstance()
	{
		return SingletonHolder.INSTANCE;
	}

	private static class SingletonHolder
	{
		protected static final TombRaidersManager INSTANCE = new TombRaidersManager();
	}
}
