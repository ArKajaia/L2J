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
package org.l2jmobius.gameserver.util;

import java.util.Collection;

import org.l2jmobius.commons.util.Rnd;
import org.l2jmobius.gameserver.ai.Intention;
import org.l2jmobius.gameserver.geoengine.GeoEngine;
import org.l2jmobius.gameserver.managers.ZoneManager;
import org.l2jmobius.gameserver.model.Location;
import org.l2jmobius.gameserver.model.actor.Attackable;
import org.l2jmobius.gameserver.model.actor.Creature;
import org.l2jmobius.gameserver.model.actor.Npc;
import org.l2jmobius.gameserver.model.actor.Player;
import org.l2jmobius.gameserver.model.interfaces.ILocational;
import org.l2jmobius.gameserver.model.script.Quest;
import org.l2jmobius.gameserver.model.zone.type.PeaceZone;
import org.l2jmobius.gameserver.model.zone.type.TownZone;
import org.l2jmobius.gameserver.network.enums.ChatType;
import org.l2jmobius.gameserver.network.serverpackets.CreatureSay;
import org.l2jmobius.gameserver.network.serverpackets.ExShowScreenMessage;

/**
 * Spawning, placement and message helpers shared by the scripted quest engines (Hidden Quests, Dynamic Quests).
 */
public final class QuestSpawnUtil
{
	private QuestSpawnUtil()
	{
	}

	/**
	 * Finds a reachable point at a distance from a location: walkable in a straight line from it, at about the same height and outside towns.
	 * @param from the origin
	 * @param minDistance the minimum distance
	 * @param maxDistance the maximum distance
	 * @param allowTown {@code true} to accept points inside towns
	 * @param instanceId the instance
	 * @return the point; if none is found, the farthest walkable point in a random direction
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

	/**
	 * @param x the x
	 * @param y the y
	 * @param z the z
	 * @return {@code true} if the point is in a town or a peace zone
	 */
	public static boolean isTown(int x, int y, int z)
	{
		final ZoneManager zones = ZoneManager.getInstance();
		return (zones.getZone(x, y, z, PeaceZone.class) != null) || (zones.getZone(x, y, z, TownZone.class) != null);
	}

	/**
	 * Spawns an NPC for a scripted task: it doesn't walk around, doesn't go back to its spawn point and carries a marker variable.
	 * @param npcId the NPC id
	 * @param location where
	 * @param heading the heading
	 * @param instanceId the instance
	 * @param markerVar the NPC variable set to {@code true} on it, {@code null} for none
	 * @return the NPC, {@code null} if it could not be spawned
	 */
	public static Npc spawnTaskNpc(int npcId, ILocational location, int heading, int instanceId, String markerVar)
	{
		final Npc npc = Quest.addSpawn(npcId, location.getX(), location.getY(), location.getZ(), heading, false, 0, false, instanceId);
		if (npc == null)
		{
			return null;
		}

		if (markerVar != null)
		{
			npc.getVariables().set(markerVar, true);
		}
		npc.setRandomWalking(false);
		if (npc.isAttackable())
		{
			npc.asAttackable().setCanReturnToSpawnPoint(false);
		}
		return npc;
	}

	/**
	 * Makes a spawned monster a champion of a tier (0 for none), restores its HP/MP and sets its title.
	 * @param npc the monster
	 * @param tier the champion tier (clamped to 0-3)
	 * @param title the title, {@code null} to keep it
	 */
	public static void applyTier(Npc npc, int tier, String title)
	{
		if (npc.isAttackable())
		{
			final Attackable monster = npc.asAttackable();
			final int championTier = Math.max(0, Math.min(3, tier));
			if (championTier > 0)
			{
				monster.setChampionTier(championTier);
			}
			monster.setCurrentHpMp(monster.getMaxHp(), monster.getMaxMp());
		}
		if (title != null)
		{
			npc.setTitle(title);
		}
		npc.broadcastInfo();
	}

	/**
	 * Makes a monster attack a target.
	 * @param npc the monster
	 * @param target the target
	 */
	public static void attack(Npc npc, Creature target)
	{
		if ((npc == null) || (target == null) || !npc.isAttackable() || npc.isDead() || target.isDead())
		{
			return;
		}
		npc.asAttackable().addDamageHate(target, 0, 1000);
		npc.getAI().setIntention(Intention.ATTACK, target);
	}

	public static boolean isAlive(Npc npc)
	{
		return (npc != null) && npc.isSpawned() && !npc.isDead();
	}

	public static int countAlive(Collection<Npc> npcs)
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

	public static void screen(Player player, String message)
	{
		if ((player != null) && (message != null) && !message.isEmpty())
		{
			player.sendPacket(new ExShowScreenMessage(message, ExShowScreenMessage.TOP_CENTER, 5000));
		}
	}

	public static void announce(Player player, String message)
	{
		if ((player != null) && (message != null) && !message.isEmpty())
		{
			player.sendPacket(new ExShowScreenMessage(message, ExShowScreenMessage.MIDDLE_CENTER, 6000));
		}
	}

	/**
	 * An NPC line only one player hears.
	 * @param player the listener
	 * @param npc the speaker
	 * @param message the line
	 */
	public static void say(Player player, Npc npc, String message)
	{
		if ((player != null) && (npc != null) && (message != null) && !message.isEmpty())
		{
			player.sendPacket(new CreatureSay(npc, ChatType.NPC_GENERAL, npc.getName(), message));
		}
	}

	/**
	 * Moves a player's radar marker.
	 * @param player the player
	 * @param previous the marker to remove, may be {@code null}
	 * @param location the new marker, {@code null} to only clear
	 */
	public static void moveMarker(Player player, Location previous, Location location)
	{
		if (player == null)
		{
			return;
		}
		if (previous != null)
		{
			player.getRadar().removeMarker(previous.getX(), previous.getY(), previous.getZ());
		}
		if (location != null)
		{
			player.getRadar().addMarker(location.getX(), location.getY(), location.getZ());
		}
	}

	public static String formatTime(int seconds)
	{
		final int safe = Math.max(0, seconds);
		return (safe / 60) + ":" + ((safe % 60) < 10 ? "0" : "") + (safe % 60);
	}
}
