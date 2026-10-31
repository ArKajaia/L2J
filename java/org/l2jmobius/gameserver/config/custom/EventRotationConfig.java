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
package org.l2jmobius.gameserver.config.custom;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.logging.Logger;

import org.l2jmobius.commons.util.ConfigReader;

/**
 * Loads the event rotation configuration (see {@link org.l2jmobius.gameserver.managers.EventRotationManager}).
 */
public class EventRotationConfig
{
	private static final Logger LOGGER = Logger.getLogger(EventRotationConfig.class.getName());

	// File
	private static final String EVENT_ROTATION_CONFIG_FILE = "./config/Custom/EventRotation.ini";

	/** The events a rotation can take turns between, as the config names them. */
	public static final List<String> EVENTS = List.of("tvt", "conquest", "deathmatch", "racewars", "passivedeathmatch");

	public static boolean ENABLED;
	public static String SCHEDULE;
	/** The events in turn, each one of {@link #EVENTS}. */
	public static List<String> ORDER;

	public static void load()
	{
		final ConfigReader config = new ConfigReader(EVENT_ROTATION_CONFIG_FILE);
		ENABLED = config.getBoolean("EventRotationEnabled", true);
		SCHEDULE = config.getString("EventRotationSchedule", "0 * * * *");
		final List<String> order = new ArrayList<>();
		for (String part : config.getString("EventRotationOrder", "TvT,Conquest,Deathmatch,RaceWars,PassiveDeathmatch").split(","))
		{
			final String trimmed = part.trim().toLowerCase();
			final String event = trimmed.equals("pdm") ? "passivedeathmatch" : trimmed;
			if (event.isEmpty())
			{
				continue;
			}
			if (EVENTS.contains(event))
			{
				order.add(event);
			}
			else
			{
				LOGGER.warning(EventRotationConfig.class.getSimpleName() + ": Unknown event \"" + part.trim() + "\" in EventRotationOrder (TvT, Conquest, Deathmatch, RaceWars, PassiveDeathmatch).");
			}
		}
		if (order.isEmpty())
		{
			order.addAll(EVENTS);
		}
		ORDER = Collections.unmodifiableList(order);
	}
}
