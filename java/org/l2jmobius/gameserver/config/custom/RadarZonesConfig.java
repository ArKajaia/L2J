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
package org.l2jmobius.gameserver.config.custom;

import org.l2jmobius.commons.util.ConfigReader;

/**
 * Radar zones: zones drawn on the client's radar (see {@link org.l2jmobius.gameserver.managers.RadarZoneManager}).
 */
public class RadarZonesConfig
{
	// File
	private static final String RADAR_ZONES_CONFIG_FILE = "./config/Custom/RadarZones.ini";

	// Constants
	public static boolean ENABLED;
	public static boolean HOTZONES;

	public static void load()
	{
		final ConfigReader config = new ConfigReader(RADAR_ZONES_CONFIG_FILE);
		ENABLED = config.getBoolean("RadarZonesEnabled", true);
		HOTZONES = config.getBoolean("RadarZonesHotzones", true);
	}
}
