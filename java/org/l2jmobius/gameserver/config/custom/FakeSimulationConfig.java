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
 * Party-vs-party simulations of fake players (see {@code FakeSimulationManager}).
 */
public class FakeSimulationConfig
{
	// File
	private static final String FAKE_SIMULATION_CONFIG_FILE = "./config/Custom/FakeSimulation.ini";

	public static int TIME_LIMIT;
	public static int PREP_TIME;
	public static int CLEANUP_DELAY;
	public static int POSITION_INTERVAL;
	public static int DECISION_HEARTBEAT;
	public static int SIGHT_RANGE;
	public static int SPAWN_RADIUS;
	public static int CROWD_WARNING_RANGE;
	public static String LOG_DIR;
	public static int KEEP_RUNS;
	public static boolean WEB_OPEN_ACCESS;
	public static int STANDSTILL_TIME;
	public static int ROUT_DISTANCE;

	public static void load()
	{
		final ConfigReader config = new ConfigReader(FAKE_SIMULATION_CONFIG_FILE);
		TIME_LIMIT = Math.max(10, config.getInt("SimulationTimeLimit", 300));
		PREP_TIME = Math.max(0, config.getInt("SimulationPrepTime", 10));
		CLEANUP_DELAY = Math.max(0, config.getInt("SimulationCleanupDelay", 10));
		POSITION_INTERVAL = Math.max(100, config.getInt("SimulationPositionInterval", 500));
		DECISION_HEARTBEAT = Math.max(100, config.getInt("SimulationDecisionHeartbeat", 2000));
		SIGHT_RANGE = Math.max(300, config.getInt("SimulationSightRange", 1200));
		SPAWN_RADIUS = Math.max(0, config.getInt("SimulationSpawnRadius", 120));
		CROWD_WARNING_RANGE = Math.max(0, config.getInt("SimulationCrowdWarningRange", 1500));
		LOG_DIR = config.getString("SimulationLogDir", "log/simulation");
		KEEP_RUNS = Math.max(1, config.getInt("SimulationKeepRuns", 50));
		WEB_OPEN_ACCESS = config.getBoolean("SimulationWebOpenAccess", false);
		STANDSTILL_TIME = Math.max(0, config.getInt("SimulationStandstillTime", 20));
		ROUT_DISTANCE = Math.max(0, config.getInt("SimulationRoutDistance", 1500));
	}
}
