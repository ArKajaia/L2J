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
package org.l2jmobius.gameserver.model.zone;

/**
 * How the client's radar draws a zone shown with {@link org.l2jmobius.gameserver.managers.RadarZoneManager}.
 * @param fillArgb fill color, alpha in the top byte
 * @param borderArgb border color, alpha in the top byte
 * @param flags {@link #PULSE} and/or {@link #HATCH}
 */
public record RadarZoneStyle(int fillArgb, int borderArgb, int flags)
{
	/** The border breathes. */
	public static final int PULSE = 1;
	/** Diagonal stripes over the fill. */
	public static final int HATCH = 2;

	/** Active farming hotzones: translucent orange with a pulsing border. */
	public static final RadarZoneStyle HOTZONE = new RadarZoneStyle(0x40FF8C00, 0xC0FFA040, PULSE);

	public RadarZoneStyle
	{
		if ((flags & ~(PULSE | HATCH)) != 0)
		{
			throw new IllegalArgumentException("Unknown radar zone flags: " + flags);
		}
	}
}
