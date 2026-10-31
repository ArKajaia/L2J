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
import org.l2jmobius.gameserver.model.zone.RadarZoneStyle;
import org.l2jmobius.gameserver.model.zone.ZoneForm;
import org.l2jmobius.gameserver.model.zone.form.ZoneCuboid;
import org.l2jmobius.gameserver.model.zone.form.ZoneCylinder;
import org.l2jmobius.gameserver.model.zone.form.ZoneNPoly;
import org.l2jmobius.gameserver.network.GameClient;
import org.l2jmobius.gameserver.network.ServerPackets;

/**
 * Shows a zone on the client's radar, or replaces the one with the same id (layout: client/RadarZones/README.txt).<br>
 * {@code int zoneId, byte form, int minZ, int maxZ}, the form's fields, {@code int fill, int border, byte flags, String name}.
 */
public class Ex2RadarZoneShow extends ServerPacket
{
	public static final int CUBOID = 0;
	public static final int CYLINDER = 1;
	public static final int NPOLY = 2;
	/** The client's limits: polygon nodes and name length. */
	public static final int MAX_NODES = 256;
	public static final int MAX_NAME = 64;

	private final int _zoneId;
	/** {@code null} for an ellipse ({@link #_ellipse}). */
	private final ZoneForm _form;
	/** An ellipse no server zone form has: x, y, radius x, radius y, low z, high z. */
	private final int[] _ellipse;
	private final RadarZoneStyle _style;
	private final String _name;

	/**
	 * @param zoneId the zone's id, the key of later hide packets
	 * @param form a form {@link #canShow(ZoneForm)} accepts
	 * @param style how the radar draws it
	 * @param name the zone's name (cut to {@link #MAX_NAME} characters)
	 */
	public Ex2RadarZoneShow(int zoneId, ZoneForm form, RadarZoneStyle style, String name)
	{
		if (!canShow(form))
		{
			throw new IllegalArgumentException("Zone " + zoneId + " cannot be shown on the radar");
		}

		_zoneId = zoneId;
		_form = form;
		_ellipse = null;
		_style = style;
		final String text = name == null ? "" : name;
		_name = text.length() > MAX_NAME ? text.substring(0, MAX_NAME) : text;
	}

	/**
	 * An ellipse, which the wire's cylinder carries (a server cylinder is only ever round).
	 * @param zoneId the zone's id, the key of later hide packets
	 * @param x the middle's x
	 * @param y the middle's y
	 * @param radiusX the radius along x, above 0
	 * @param radiusY the radius along y, above 0
	 * @param lowZ the lowest z it is drawn at
	 * @param highZ the highest
	 * @param style how the radar draws it
	 * @param name the zone's name (cut to {@link #MAX_NAME} characters)
	 */
	public Ex2RadarZoneShow(int zoneId, int x, int y, int radiusX, int radiusY, int lowZ, int highZ, RadarZoneStyle style, String name)
	{
		if ((radiusX <= 0) || (radiusY <= 0) || (lowZ > highZ))
		{
			throw new IllegalArgumentException("Zone " + zoneId + " cannot be shown on the radar");
		}

		_zoneId = zoneId;
		_form = null;
		_ellipse = new int[]
		{
			x,
			y,
			radiusX,
			radiusY,
			lowZ,
			highZ
		};
		_style = style;
		final String text = name == null ? "" : name;
		_name = text.length() > MAX_NAME ? text.substring(0, MAX_NAME) : text;
	}

	/**
	 * @param form a zone's form
	 * @return whether the client accepts it: a non-empty cuboid, a cylinder, or a polygon of 3 to {@link #MAX_NODES} nodes
	 */
	public static boolean canShow(ZoneForm form)
	{
		if (form instanceof ZoneCuboid cuboid)
		{
			return (cuboid.getX1() < cuboid.getX2()) && (cuboid.getY1() < cuboid.getY2());
		}

		if (form instanceof ZoneCylinder cylinder)
		{
			return cylinder.getRadius() > 0;
		}

		if (form instanceof ZoneNPoly polygon)
		{
			return (polygon.getX().length >= 3) && (polygon.getX().length <= MAX_NODES);
		}

		return false;
	}

	@Override
	public void writeImpl(GameClient client, WritableBuffer buffer)
	{
		ServerPackets.EX2_RADAR_ZONE_SHOW.writeId(this, buffer);
		buffer.writeInt(_zoneId);
		if (_ellipse != null)
		{
			buffer.writeByte(CYLINDER);
			buffer.writeInt(_ellipse[4]);
			buffer.writeInt(_ellipse[5]);
			buffer.writeInt(_ellipse[0]);
			buffer.writeInt(_ellipse[1]);
			buffer.writeInt(_ellipse[2]);
			buffer.writeInt(_ellipse[3]);
		}
		else if (_form instanceof ZoneCuboid cuboid)
		{
			buffer.writeByte(CUBOID);
			buffer.writeInt(_form.getLowZ());
			buffer.writeInt(_form.getHighZ());
			buffer.writeInt(cuboid.getX1());
			buffer.writeInt(cuboid.getY1());
			buffer.writeInt(cuboid.getX2());
			buffer.writeInt(cuboid.getY2());
		}
		else if (_form instanceof ZoneCylinder cylinder)
		{
			buffer.writeByte(CYLINDER);
			buffer.writeInt(_form.getLowZ());
			buffer.writeInt(_form.getHighZ());
			buffer.writeInt(cylinder.getX());
			buffer.writeInt(cylinder.getY());
			// The wire carries an ellipse; a server cylinder is round.
			buffer.writeInt(cylinder.getRadius());
			buffer.writeInt(cylinder.getRadius());
		}
		else
		{
			final ZoneNPoly polygon = (ZoneNPoly) _form;
			final int[] x = polygon.getX();
			final int[] y = polygon.getY();
			buffer.writeByte(NPOLY);
			buffer.writeInt(_form.getLowZ());
			buffer.writeInt(_form.getHighZ());
			buffer.writeInt(x.length);
			for (int i = 0; i < x.length; i++)
			{
				buffer.writeInt(x[i]);
				buffer.writeInt(y[i]);
			}
		}

		buffer.writeInt(_style.fillArgb());
		buffer.writeInt(_style.borderArgb());
		buffer.writeByte(_style.flags());
		buffer.writeString(_name);
	}
}
