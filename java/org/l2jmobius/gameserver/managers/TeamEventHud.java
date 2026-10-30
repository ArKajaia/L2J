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
package org.l2jmobius.gameserver.managers;

import java.util.ArrayList;
import java.util.List;

import org.l2jmobius.gameserver.model.actor.Player;
import org.l2jmobius.gameserver.model.actor.enums.creature.Team;
import org.l2jmobius.gameserver.model.zone.RadarZoneStyle;
import org.l2jmobius.gameserver.model.zone.form.ZoneCuboid;
import org.l2jmobius.gameserver.model.zone.form.ZoneCylinder;
import org.l2jmobius.gameserver.network.serverpackets.Ex2RadarZoneHide;
import org.l2jmobius.gameserver.network.serverpackets.Ex2RadarZoneShow;
import org.l2jmobius.gameserver.network.serverpackets.Ex2TeamEventClose;
import org.l2jmobius.gameserver.network.serverpackets.Ex2TeamEventState;

/**
 * What the team events (TvT, Conquest) show a player of a match: the event strip of the client's combat log ({@link Ex2TeamEventState}) and the match's zones on its radar (client/TeamEvents/PROTOCOL.txt).<br>
 * The zones go to the match's players only, one by one, under ids of their own ({@link #zoneId}), so they never touch the zones {@link RadarZoneManager} shows everyone.
 */
public final class TeamEventHud
{
	/** Kinds of zone ids. */
	public static final int KIND_CAPTURE = 1;
	public static final int KIND_BLUE_BASE = 2;
	public static final int KIND_RED_BASE = 3;
	public static final int KIND_EDGE = 4;

	public static final RadarZoneStyle BLUE = new RadarZoneStyle(0x403C8CFF, 0xE070AAFF, 0);
	public static final RadarZoneStyle RED = new RadarZoneStyle(0x40E8483C, 0xE0FF7060, 0);
	public static final RadarZoneStyle NEUTRAL = new RadarZoneStyle(0x30A0A0A0, 0xA0C8C8C8, 0);
	/** A team's spawn points: its color, fainter. */
	public static final RadarZoneStyle BLUE_SPAWN = new RadarZoneStyle(0x203C8CFF, 0xE070AAFF, 0);
	public static final RadarZoneStyle RED_SPAWN = new RadarZoneStyle(0x20E8483C, 0xE0FF7060, 0);
	/** The bases a player must not enter. */
	public static final RadarZoneStyle ENEMY_BASE = new RadarZoneStyle(0x40E8483C, 0xE0FF7060, RadarZoneStyle.PULSE | RadarZoneStyle.HATCH);
	/** The edge of the battlefield: a border only. */
	public static final RadarZoneStyle EDGE = new RadarZoneStyle(0, 0x90E0C080, 0);

	/** The sweep of a capture going a team's way. */
	public static final int BLUE_SWEEP = 0x803C8CFF;
	public static final int RED_SWEEP = 0x80E8483C;

	/** The spawn points' circles reach this far up and down. */
	public static final int SPAWN_HEIGHT = 300;
	/** The edge reaches this far up and down. */
	public static final int EDGE_HEIGHT = 3000;

	private TeamEventHud()
	{
	}

	/**
	 * @param kind {@link #KIND_CAPTURE}, {@link #KIND_BLUE_BASE}, {@link #KIND_RED_BASE} or {@link #KIND_EDGE}
	 * @param index the zone's index among its kind, 0 to 255
	 * @return the zone's radar id, out of the range of the server's zones
	 */
	public static int zoneId(int kind, int index)
	{
		return 0x7E000000 | (kind << 8) | (index & 0xFF);
	}

	/**
	 * @param captures the capture points
	 * @param blueBases the spawn points (bases) of Blue
	 * @param redBases the spawn points (bases) of Red
	 * @return the ids of every zone a match of that many shows, the edge included
	 */
	public static List<Integer> zoneIds(int captures, int blueBases, int redBases)
	{
		final List<Integer> ids = new ArrayList<>();
		for (int i = 0; i < captures; i++)
		{
			ids.add(zoneId(KIND_CAPTURE, i));
		}
		for (int i = 0; i < blueBases; i++)
		{
			ids.add(zoneId(KIND_BLUE_BASE, i));
		}
		for (int i = 0; i < redBases; i++)
		{
			ids.add(zoneId(KIND_RED_BASE, i));
		}
		ids.add(zoneId(KIND_EDGE, 0));
		return ids;
	}

	/**
	 * @param team a team
	 * @return its code on the wire: 0 none, 1 Blue, 2 Red
	 */
	public static int code(Team team)
	{
		return team == Team.BLUE ? Ex2TeamEventState.TEAM_BLUE : team == Team.RED ? Ex2TeamEventState.TEAM_RED : Ex2TeamEventState.TEAM_NONE;
	}

	/**
	 * @param team who holds a zone
	 * @return its style: the team's color, gray for nobody
	 */
	public static RadarZoneStyle ownerStyle(Team team)
	{
		return team == Team.BLUE ? BLUE : team == Team.RED ? RED : NEUTRAL;
	}

	/**
	 * @param team a team
	 * @return the style of its spawn points
	 */
	public static RadarZoneStyle spawnStyle(Team team)
	{
		return team == Team.RED ? RED_SPAWN : BLUE_SPAWN;
	}

	/**
	 * Shows a circle on the player's radar.
	 * @param player the player
	 * @param zoneId its id ({@link #zoneId})
	 * @param x the center's x
	 * @param y the center's y
	 * @param z the center's z
	 * @param height how far up and down it is drawn
	 * @param radius its radius
	 * @param style how it is drawn
	 * @param name its name
	 */
	public static void showCircle(Player player, int zoneId, int x, int y, int z, int height, int radius, RadarZoneStyle style, String name)
	{
		if (radius > 0)
		{
			player.sendPacket(new Ex2RadarZoneShow(zoneId, new ZoneCylinder(x, y, z - height, z + height, radius), style, name));
		}
	}

	/**
	 * Shows the edge of the battlefield on the player's radar.
	 * @param player the player
	 * @param minX the west edge
	 * @param minY the north edge
	 * @param maxX the east edge
	 * @param maxY the south edge
	 * @param z the ground's height
	 */
	public static void showEdge(Player player, int minX, int minY, int maxX, int maxY, int z)
	{
		if ((minX < maxX) && (minY < maxY))
		{
			player.sendPacket(new Ex2RadarZoneShow(zoneId(KIND_EDGE, 0), new ZoneCuboid(minX, maxX, minY, maxY, z - EDGE_HEIGHT, z + EDGE_HEIGHT), EDGE, "Battlefield"));
		}
	}

	/**
	 * Sends the match's state to a player.
	 * @param player the player
	 * @param state the match's state
	 * @param team the player's team
	 */
	public static void sendState(Player player, Ex2TeamEventState.State state, Team team)
	{
		player.sendPacket(new Ex2TeamEventState(state, code(team)));
	}

	/**
	 * The player left the event: the strip and the match's zones go.
	 * @param player the player
	 * @param zoneIds the match's zones ({@link #zoneIds})
	 */
	public static void close(Player player, List<Integer> zoneIds)
	{
		player.sendPacket(Ex2TeamEventClose.STATIC_PACKET);
		for (int zoneId : zoneIds)
		{
			player.sendPacket(new Ex2RadarZoneHide(zoneId));
		}
	}
}
