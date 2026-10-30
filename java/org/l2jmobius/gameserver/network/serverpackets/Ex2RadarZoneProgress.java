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
package org.l2jmobius.gameserver.network.serverpackets;

import org.l2jmobius.commons.network.WritableBuffer;
import org.l2jmobius.gameserver.network.GameClient;
import org.l2jmobius.gameserver.network.ServerPackets;

/**
 * A sweep over a zone already on the client's radar, such as a capture filling up (layout: client/RadarZones/README.txt).<br>
 * {@code int zoneId, int sweepArgb, short fraction, short rate, byte flags}.
 */
public class Ex2RadarZoneProgress extends ServerPacket
{
	/** The border pulses and the zone is hatched: both teams are on it. */
	public static final int CONTESTED = 1;

	private final int _zoneId;
	private final int _sweepArgb;
	private final int _fraction;
	private final int _rate;
	private final int _flags;

	/**
	 * @param zoneId a zone shown with {@link Ex2RadarZoneShow}
	 * @param sweepArgb the sweep's color
	 * @param fraction 0 to 1000 of the zone swept, 0 takes the sweep off
	 * @param rate per-mille per second the client moves the fraction by until the next packet
	 * @param flags {@link #CONTESTED}
	 */
	public Ex2RadarZoneProgress(int zoneId, int sweepArgb, int fraction, int rate, int flags)
	{
		_zoneId = zoneId;
		_sweepArgb = sweepArgb;
		_fraction = Math.max(0, Math.min(1000, fraction));
		_rate = Math.max(-1000, Math.min(1000, rate));
		_flags = flags;
	}

	@Override
	public void writeImpl(GameClient client, WritableBuffer buffer)
	{
		ServerPackets.EX2_RADAR_ZONE_PROGRESS.writeId(this, buffer);
		buffer.writeInt(_zoneId);
		buffer.writeInt(_sweepArgb);
		buffer.writeShort(_fraction);
		buffer.writeShort(_rate);
		buffer.writeByte(_flags);
	}
}
