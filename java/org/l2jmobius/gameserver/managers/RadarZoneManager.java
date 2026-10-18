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

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

import org.l2jmobius.gameserver.config.custom.RadarZonesConfig;
import org.l2jmobius.gameserver.model.actor.Player;
import org.l2jmobius.gameserver.model.events.Containers;
import org.l2jmobius.gameserver.model.events.EventType;
import org.l2jmobius.gameserver.model.events.holders.actor.player.OnPlayerLogin;
import org.l2jmobius.gameserver.model.events.listeners.ConsumerEventListener;
import org.l2jmobius.gameserver.model.zone.RadarZoneStyle;
import org.l2jmobius.gameserver.model.zone.ZoneType;
import org.l2jmobius.gameserver.network.serverpackets.Ex2RadarZoneClear;
import org.l2jmobius.gameserver.network.serverpackets.Ex2RadarZoneHide;
import org.l2jmobius.gameserver.network.serverpackets.Ex2RadarZoneShow;
import org.l2jmobius.gameserver.util.Broadcast;

/**
 * Zones drawn on every client's radar. Any zone can be published with a {@link RadarZoneStyle}; the client draws cuboid and cylinder forms (polygons are sent, but the client does not draw them yet).
 * <ul>
 * <li>{@link #publish(ZoneType, RadarZoneStyle)} shows the zone to everyone online, replacing an earlier style of the same zone.</li>
 * <li>{@link #unpublish(int)} takes it off every radar.</li>
 * <li>On login the client gets {@link Ex2RadarZoneClear} and then every published zone, so a relog or a character switch never keeps stale zones.</li>
 * </ul>
 * The first user is {@code custom.RotatingHotZones}, which publishes its active hotzones. Wire layouts: client/RadarZones/README.txt.
 */
public class RadarZoneManager
{
	private static final Logger LOGGER = Logger.getLogger(RadarZoneManager.class.getName());

	/** The client keeps at most this many zones. */
	public static final int CLIENT_CAPACITY = 128;

	/** zone id -> the packet that shows it. Packets are written per client, so one is shared by every send. */
	private final Map<Integer, Ex2RadarZoneShow> _published = new ConcurrentHashMap<>();

	protected RadarZoneManager()
	{
		if (!RadarZonesConfig.ENABLED)
		{
			return;
		}

		Containers.Players().addListener(new ConsumerEventListener(Containers.Players(), EventType.ON_PLAYER_LOGIN, (OnPlayerLogin event) -> sendAll(event.getPlayer()), this));
	}

	/**
	 * Shows {@code zone} on every radar, or changes its style.
	 * @param zone the zone
	 * @param style how the radar draws it
	 * @return whether it was published: radar zones are enabled, the zone has a positive id and a form the client accepts, and there is room on the client
	 */
	public boolean publish(ZoneType zone, RadarZoneStyle style)
	{
		if (!RadarZonesConfig.ENABLED || (zone == null) || (zone.getId() <= 0) || !Ex2RadarZoneShow.canShow(zone.getZone()))
		{
			return false;
		}

		if (!_published.containsKey(zone.getId()) && (_published.size() >= CLIENT_CAPACITY))
		{
			LOGGER.warning(getClass().getSimpleName() + ": Zone " + zone.getId() + " not shown, " + CLIENT_CAPACITY + " zones are already on the radar.");
			return false;
		}

		final Ex2RadarZoneShow packet = new Ex2RadarZoneShow(zone.getId(), zone.getZone(), style, zone.getName());
		_published.put(zone.getId(), packet);
		Broadcast.toAllOnlinePlayers(packet);
		return true;
	}

	/**
	 * Takes the zone off every radar. Nothing happens for a zone that is not published.
	 * @param zoneId the zone's id
	 */
	public void unpublish(int zoneId)
	{
		if (_published.remove(zoneId) != null)
		{
			Broadcast.toAllOnlinePlayers(new Ex2RadarZoneHide(zoneId));
		}
	}

	/**
	 * @param zoneId a zone's id
	 * @return whether it is on the radar
	 */
	public boolean isPublished(int zoneId)
	{
		return _published.containsKey(zoneId);
	}

	/**
	 * Replaces whatever {@code player}'s radar shows with the published zones.
	 * @param player the player
	 */
	public void sendAll(Player player)
	{
		if (!RadarZonesConfig.ENABLED || (player == null))
		{
			return;
		}

		player.sendPacket(Ex2RadarZoneClear.STATIC_PACKET);
		for (Ex2RadarZoneShow packet : _published.values())
		{
			player.sendPacket(packet);
		}
	}

	public static RadarZoneManager getInstance()
	{
		return SingletonHolder.INSTANCE;
	}

	private static class SingletonHolder
	{
		protected static final RadarZoneManager INSTANCE = new RadarZoneManager();
	}
}
