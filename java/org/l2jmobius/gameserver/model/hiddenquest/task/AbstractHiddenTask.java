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
package org.l2jmobius.gameserver.model.hiddenquest.task;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

import org.l2jmobius.commons.threads.ThreadPool;
import org.l2jmobius.commons.util.Rnd;
import org.l2jmobius.gameserver.ai.Intention;
import org.l2jmobius.gameserver.geoengine.GeoEngine;
import org.l2jmobius.gameserver.managers.HiddenQuestManager;
import org.l2jmobius.gameserver.managers.ZoneManager;
import org.l2jmobius.gameserver.model.Location;
import org.l2jmobius.gameserver.model.StatSet;
import org.l2jmobius.gameserver.model.actor.Attackable;
import org.l2jmobius.gameserver.model.actor.Creature;
import org.l2jmobius.gameserver.model.actor.Npc;
import org.l2jmobius.gameserver.model.actor.Player;
import org.l2jmobius.gameserver.model.events.EventType;
import org.l2jmobius.gameserver.model.events.holders.actor.creature.OnCreatureDeath;
import org.l2jmobius.gameserver.model.events.listeners.ConsumerEventListener;
import org.l2jmobius.gameserver.model.hiddenquest.HiddenQuestDefinition;
import org.l2jmobius.gameserver.model.hiddenquest.HiddenQuestSession;
import org.l2jmobius.gameserver.model.hiddenquest.MonsterBand;
import org.l2jmobius.gameserver.model.hiddenquest.SpawnRole;
import org.l2jmobius.gameserver.model.interfaces.ILocational;
import org.l2jmobius.gameserver.model.script.Quest;
import org.l2jmobius.gameserver.model.zone.type.PeaceZone;
import org.l2jmobius.gameserver.model.zone.type.TownZone;
import org.l2jmobius.gameserver.network.enums.ChatType;
import org.l2jmobius.gameserver.network.serverpackets.CreatureSay;
import org.l2jmobius.gameserver.network.serverpackets.ExShowScreenMessage;
import org.l2jmobius.gameserver.util.LocationUtil;

/**
 * Base of the hidden quest task types. A task is driven by its session: {@link #start(Player)} once, then {@link #onTick(Player, int)} every second until it calls {@link #complete()} or {@link #fail(String)}. Everything it spawns is tracked and removed by {@link #cleanup()}.
 * <p>
 * Callbacks come from the tick thread, NPC deaths and player actions; the session dispatches them through the {@code handle*} methods, which serialise them on the task.
 * </p>
 * @author Mobius
 */
public abstract class AbstractHiddenTask
{
	protected static final Logger LOGGER = Logger.getLogger(AbstractHiddenTask.class.getName());

	/** NPC variable set on everything a task spawns, so its kills don't count for the hidden conditions. */
	public static final String TASK_NPC_VAR = "HQ_TASK_NPC";

	protected final HiddenQuestSession _session;
	protected final HiddenQuestDefinition _definition;
	protected final StatSet _params;
	private final Set<Npc> _npcs = ConcurrentHashMap.newKeySet();
	private Location _marker;

	protected AbstractHiddenTask(HiddenQuestSession session)
	{
		_session = session;
		_definition = session.getDefinition();
		_params = _definition.getTaskParams();
	}

	/**
	 * Sets the task up (spawns, first instructions).
	 * @param player the player
	 * @return {@code null} if the task started, else why it could not
	 */
	public abstract String start(Player player);

	/**
	 * Called every second while the task runs.
	 * @param player the player (online)
	 * @param second the seconds since the start
	 */
	protected abstract void onTick(Player player, int second);

	/**
	 * Called when an NPC spawned by this task dies.
	 * @param npc the NPC
	 * @param player the player (may be {@code null} if offline)
	 */
	protected void onNpcDeath(Npc npc, Player player)
	{
	}

	/**
	 * Called when the player dies. By default the task fails unless {@code failOnDeath="false"}.
	 * @param player the player
	 */
	protected void onPlayerDeath(Player player)
	{
		if (_params.getBoolean("failOnDeath", true))
		{
			fail(text("failDeath", "You fell, and the moment has passed."));
		}
	}

	/**
	 * Called when the player clicks the question mark during the task. By default shows the progress.
	 * @param player the player
	 */
	protected void onQuestionMark(Player player)
	{
		screen(player, getProgress());
	}

	/**
	 * @return a short progress line for the player and GMs
	 */
	public abstract String getProgress();

	// Serialised entry points.

	public synchronized void handleTick(Player player, int second)
	{
		if (!_session.isFinished())
		{
			onTick(player, second);
		}
	}

	public synchronized void handlePlayerDeath(Player player)
	{
		if (!_session.isFinished())
		{
			onPlayerDeath(player);
		}
	}

	public synchronized void handleQuestionMark(Player player)
	{
		if (!_session.isFinished())
		{
			onQuestionMark(player);
		}
	}

	private synchronized void handleNpcDeath(Npc npc)
	{
		if (!_session.isFinished() && _npcs.contains(npc))
		{
			onNpcDeath(npc, _session.getPlayer());
		}
	}

	/**
	 * Removes everything the task spawned and its radar marker.
	 */
	public synchronized void cleanup()
	{
		for (Npc npc : _npcs)
		{
			if (npc.isSpawned())
			{
				npc.deleteMe();
			}
		}
		_npcs.clear();

		final Player player = _session.getPlayer();
		if ((player != null) && (_marker != null))
		{
			player.getRadar().removeMarker(_marker.getX(), _marker.getY(), _marker.getZ());
		}
		_marker = null;
	}

	// Outcome.

	protected void complete()
	{
		HiddenQuestManager.getInstance().completeTask(_session);
	}

	protected void fail(String reason)
	{
		HiddenQuestManager.getInstance().failTask(_session, reason);
	}

	// Texts and messages.

	/**
	 * @param key a text key of the quest
	 * @param defaultValue the text to use when the quest has none
	 * @return the quest's text
	 */
	protected String text(String key, String defaultValue)
	{
		return _definition.getText(key, defaultValue);
	}

	protected static void screen(Player player, String message)
	{
		if ((player != null) && (message != null) && !message.isEmpty())
		{
			player.sendPacket(new ExShowScreenMessage(message, ExShowScreenMessage.TOP_CENTER, 5000));
		}
	}

	protected static void announce(Player player, String message)
	{
		if ((player != null) && (message != null) && !message.isEmpty())
		{
			player.sendPacket(new ExShowScreenMessage(message, ExShowScreenMessage.MIDDLE_CENTER, 6000));
		}
	}

	/**
	 * An NPC line only the task's player hears.
	 * @param npc the speaker
	 * @param message the line
	 */
	protected void say(Npc npc, String message)
	{
		final Player player = _session.getPlayer();
		if ((player != null) && (npc != null) && (message != null) && !message.isEmpty())
		{
			player.sendPacket(new CreatureSay(npc, ChatType.NPC_GENERAL, npc.getName(), message));
		}
	}

	/**
	 * Points the player's radar at a location (replacing the task's previous marker).
	 * @param player the player
	 * @param location the location, {@code null} to only clear the marker
	 */
	protected void setMarker(Player player, Location location)
	{
		if (player == null)
		{
			return;
		}
		if (_marker != null)
		{
			player.getRadar().removeMarker(_marker.getX(), _marker.getY(), _marker.getZ());
		}
		_marker = location;
		if (location != null)
		{
			player.getRadar().addMarker(location.getX(), location.getY(), location.getZ());
		}
	}

	// Spawning.

	/**
	 * Spawns and tracks an NPC of this task.
	 * @param npcId the NPC id
	 * @param location where
	 * @param heading the heading
	 * @return the NPC, {@code null} if it could not be spawned
	 */
	protected Npc spawnNpc(int npcId, ILocational location, int heading)
	{
		final Player player = _session.getPlayer();
		final Npc npc = Quest.addSpawn(npcId, location.getX(), location.getY(), location.getZ(), heading, false, 0, false, player != null ? player.getInstanceId() : 0);
		if (npc == null)
		{
			LOGGER.warning(getClass().getSimpleName() + ": Could not spawn NPC " + npcId + " for hidden quest " + _definition.getId() + ".");
			return null;
		}

		npc.getVariables().set(TASK_NPC_VAR, true);
		npc.setRandomWalking(false);
		if (npc.isAttackable())
		{
			npc.asAttackable().setCanReturnToSpawnPoint(false);
		}
		_npcs.add(npc);
		npc.addListener(new ConsumerEventListener(npc, EventType.ON_CREATURE_DEATH, (OnCreatureDeath event) -> ThreadPool.execute(() -> handleNpcDeath(npc)), this));
		return npc;
	}

	/**
	 * Spawns one monster of a role, picked for the player's level.
	 * @param role the role
	 * @param location where
	 * @param target who it attacks at once, may be {@code null}
	 * @param title the title, {@code null} for the role's
	 * @param tier the champion tier, -1 for the band's tier plus the role's bonus
	 * @return the monster, {@code null} if it could not be spawned
	 */
	protected Npc spawnMonster(SpawnRole role, ILocational location, Creature target, String title, int tier)
	{
		final Player player = _session.getPlayer();
		final MonsterBand band = role.getSet().getBand(player != null ? player.getLevel() : 1);
		if (band == null)
		{
			return null;
		}

		final Npc npc = spawnNpc(band.getNpcId(), location, target != null ? LocationUtil.calculateHeadingFrom(location, target) : 0);
		if (npc == null)
		{
			return null;
		}

		if (npc.isAttackable())
		{
			final Attackable monster = npc.asAttackable();
			final int championTier = tier >= 0 ? Math.min(3, tier) : Math.max(0, Math.min(3, band.getTier() + role.getTierBonus()));
			if (championTier > 0)
			{
				monster.setChampionTier(championTier);
			}
			monster.setCurrentHpMp(monster.getMaxHp(), monster.getMaxMp());
		}

		final String newTitle = title != null ? title : role.getTitle();
		if (newTitle != null)
		{
			npc.setTitle(newTitle);
		}
		npc.broadcastInfo();
		attack(npc, target);
		return npc;
	}

	/**
	 * Spawns the monsters of a role around a point.
	 * @param roleName the role name
	 * @param around the centre
	 * @param minDistance the minimum distance from the centre
	 * @param maxDistance the maximum distance from the centre
	 * @param target who they attack, may be {@code null}
	 * @param count how many, 0 for the role's count
	 * @return the monsters spawned (empty if the quest has no such role)
	 */
	protected List<Npc> spawnRole(String roleName, ILocational around, int minDistance, int maxDistance, Creature target, int count)
	{
		final List<Npc> result = new ArrayList<>();
		final SpawnRole role = _definition.getRole(roleName);
		if (role == null)
		{
			return result;
		}

		final int total = count > 0 ? count : role.getCount();
		for (int i = 0; i < total; i++)
		{
			final Npc npc = spawnMonster(role, randomPoint(around, minDistance, maxDistance, false), target, null, -1);
			if (npc != null)
			{
				result.add(npc);
			}
		}
		return result;
	}

	/**
	 * Makes a monster attack a target.
	 * @param npc the monster
	 * @param target the target
	 */
	protected static void attack(Npc npc, Creature target)
	{
		if ((npc == null) || (target == null) || !npc.isAttackable() || npc.isDead() || target.isDead())
		{
			return;
		}
		npc.asAttackable().addDamageHate(target, 0, 1000);
		npc.getAI().setIntention(Intention.ATTACK, target);
	}

	protected static boolean isAlive(Npc npc)
	{
		return (npc != null) && npc.isSpawned() && !npc.isDead();
	}

	protected static int countAlive(List<Npc> npcs)
	{
		int alive = 0;
		for (Npc npc : npcs)
		{
			if (isAlive(npc))
			{
				alive++;
			}
		}
		return alive;
	}

	/**
	 * Finds a reachable point at a distance from a location: walkable in a straight line from it, at about the same height and outside towns.
	 * @param from the origin
	 * @param minDistance the minimum distance
	 * @param maxDistance the maximum distance
	 * @param allowTown {@code true} to accept points inside towns
	 * @return the point; if none is found, the farthest walkable point in a random direction
	 */
	protected Location randomPoint(ILocational from, int minDistance, int maxDistance, boolean allowTown)
	{
		final Player player = _session.getPlayer();
		return findPoint(from, minDistance, maxDistance, allowTown, player != null ? player.getInstanceId() : 0);
	}

	/**
	 * @param from the origin
	 * @param minDistance the minimum distance
	 * @param maxDistance the maximum distance
	 * @param allowTown {@code true} to accept points inside towns
	 * @param instanceId the instance
	 * @return a reachable point (see {@link #randomPoint(ILocational, int, int, boolean)})
	 */
	public static Location findPoint(ILocational from, int minDistance, int maxDistance, boolean allowTown, int instanceId)
	{
		final GeoEngine geo = GeoEngine.getInstance();
		final int attempts = 40;
		for (int attempt = 0; attempt < attempts; attempt++)
		{
			// Later attempts try shorter distances.
			final int max = Math.max(minDistance, maxDistance - (((maxDistance - minDistance) * attempt) / attempts));
			final int distance = Rnd.get(minDistance, max);
			final double angle = Rnd.nextDouble() * 2 * Math.PI;
			final int x = from.getX() + (int) (Math.cos(angle) * distance);
			final int y = from.getY() + (int) (Math.sin(angle) * distance);
			final int z = geo.getHeight(x, y, from.getZ());
			if (Math.abs(z - from.getZ()) > 500)
			{
				continue;
			}
			if (!geo.canMoveToTarget(from.getX(), from.getY(), from.getZ(), x, y, z, instanceId))
			{
				continue;
			}
			if (!allowTown && isTown(x, y, z))
			{
				continue;
			}
			return new Location(x, y, z);
		}

		final double angle = Rnd.nextDouble() * 2 * Math.PI;
		final int x = from.getX() + (int) (Math.cos(angle) * minDistance);
		final int y = from.getY() + (int) (Math.sin(angle) * minDistance);
		final Location valid = geo.getValidLocation(from.getX(), from.getY(), from.getZ(), x, y, geo.getHeight(x, y, from.getZ()), instanceId);
		return valid != null ? valid : new Location(from.getX(), from.getY(), from.getZ());
	}

	private static boolean isTown(int x, int y, int z)
	{
		final ZoneManager zones = ZoneManager.getInstance();
		return (zones.getZone(x, y, z, PeaceZone.class) != null) || (zones.getZone(x, y, z, TownZone.class) != null);
	}

	protected static String formatTime(int seconds)
	{
		final int safe = Math.max(0, seconds);
		return (safe / 60) + ":" + ((safe % 60) < 10 ? "0" : "") + (safe % 60);
	}
}
