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
package handlers.chat.commands.admin;

import java.util.List;
import java.util.Locale;
import java.util.StringTokenizer;

import org.l2jmobius.commons.util.Rnd;
import org.l2jmobius.gameserver.config.custom.FakeSimulationConfig;
import org.l2jmobius.gameserver.handler.IAdminCommandHandler;
import org.l2jmobius.gameserver.managers.simulation.FakeAiTrace;
import org.l2jmobius.gameserver.managers.simulation.FakeSimulationManager;
import org.l2jmobius.gameserver.model.WorldObject;
import org.l2jmobius.gameserver.model.actor.Npc;
import org.l2jmobius.gameserver.model.actor.Player;

/**
 * Party-vs-party simulations of fake players, to test and debug their AI (see {@link FakeSimulationManager}). Command names are case-insensitive (//SetSimulationPoint or //setsimulationpoint).
 */
public class AdminSimulation implements IAdminCommandHandler
{
	private static final String[] ADMIN_COMMANDS =
	{
		"admin_setsimulationpoint",
		"admin_simulationpoints",
		"admin_clearsimulationpoint",
		"admin_simulate",
		"admin_simulationstatus",
		"admin_simulationstop",
		"admin_joinparty",
		"admin_leaveparty",
		"admin_simulationtrace",
		"admin_simulationdraw",
		"admin_simulationhelp"
	};

	@Override
	public boolean onCommand(String commandLine, Player activeChar)
	{
		final StringTokenizer st = new StringTokenizer(commandLine);
		final String command = st.nextToken().toLowerCase(Locale.ROOT);
		final FakeSimulationManager manager = FakeSimulationManager.getInstance();
		switch (command)
		{
			case "admin_setsimulationpoint":
			{
				final Integer id = nextInt(st);
				if (id == null)
				{
					activeChar.sendSysMessage("Usage: //SetSimulationPoint <number>");
					return false;
				}
				activeChar.sendSysMessage(manager.setPoint(activeChar, id));
				break;
			}
			case "admin_simulationpoints":
			{
				send(activeChar, manager.listPoints(activeChar));
				break;
			}
			case "admin_clearsimulationpoint":
			{
				if (!st.hasMoreTokens())
				{
					activeChar.sendSysMessage("Usage: //ClearSimulationPoint <number|all>");
					return false;
				}
				activeChar.sendSysMessage(manager.clearPoint(st.nextToken()));
				break;
			}
			case "admin_simulate":
			{
				return simulate(activeChar, st, manager);
			}
			case "admin_simulationstatus":
			{
				send(activeChar, manager.getStatus());
				break;
			}
			case "admin_simulationstop":
			{
				activeChar.sendSysMessage(manager.stop(activeChar));
				break;
			}
			case "admin_joinparty":
			{
				final Integer team = nextInt(st);
				if (team == null)
				{
					activeChar.sendSysMessage("Usage: //JoinParty <team> [tank|melee|ranged|healer|buffer] [lead]");
					return false;
				}
				String role = null;
				boolean lead = false;
				while (st.hasMoreTokens())
				{
					final String token = st.nextToken();
					if (token.equalsIgnoreCase("lead"))
					{
						lead = true;
					}
					else
					{
						role = token;
					}
				}
				activeChar.sendSysMessage(manager.joinParty(activeChar, team, role, lead));
				break;
			}
			case "admin_leaveparty":
			{
				activeChar.sendSysMessage(manager.leaveParty(activeChar));
				break;
			}
			case "admin_simulationtrace":
			{
				final Npc fake = getTargetFake(activeChar);
				if (fake == null)
				{
					activeChar.sendSysMessage("Target a roaming fake player first.");
					return false;
				}
				final List<String> recent = FakeAiTrace.getRecent(fake);
				final boolean watched = FakeAiTrace.toggleWatch(fake);
				activeChar.sendSysMessage(fake.getName() + ": last " + recent.size() + " decision(s):");
				send(activeChar, recent);
				activeChar.sendSysMessage(watched ? "Tracing " + fake.getName() + " (a simulation fake player is always traced). //SimulationTrace again prints its decisions and stops." : "Stopped tracing " + fake.getName() + ".");
				break;
			}
			case "admin_simulationdraw":
			{
				final Npc fake = getTargetFake(activeChar);
				activeChar.sendSysMessage(manager.toggleDraw(activeChar, fake));
				break;
			}
			default:
			{
				showHelp(activeChar);
				break;
			}
		}
		return true;
	}

	private boolean simulate(Player activeChar, StringTokenizer st, FakeSimulationManager manager)
	{
		final Integer size = nextInt(st);
		if (size == null)
		{
			activeChar.sendSysMessage("Usage: //Simulate <size 1-9> [level] [mirror] [seed=<n>] [rounds=<n>] [time=<seconds>]");
			return false;
		}

		int level = activeChar.getLevel();
		boolean mirror = false;
		long seed = Rnd.get(1, Integer.MAX_VALUE);
		int rounds = 1;
		int time = FakeSimulationConfig.TIME_LIMIT;
		try
		{
			while (st.hasMoreTokens())
			{
				final String token = st.nextToken().toLowerCase(Locale.ROOT);
				if (token.equals("mirror"))
				{
					mirror = true;
				}
				else if (token.startsWith("seed="))
				{
					seed = Long.parseLong(token.substring(5));
				}
				else if (token.startsWith("rounds="))
				{
					rounds = Math.max(1, Math.min(100, Integer.parseInt(token.substring(7))));
				}
				else if (token.startsWith("time="))
				{
					time = Math.max(10, Integer.parseInt(token.substring(5)));
				}
				else
				{
					level = Math.max(1, Math.min(85, Integer.parseInt(token)));
				}
			}
		}
		catch (NumberFormatException e)
		{
			activeChar.sendSysMessage("Usage: //Simulate <size 1-9> [level] [mirror] [seed=<n>] [rounds=<n>] [time=<seconds>]");
			return false;
		}

		send(activeChar, manager.simulate(activeChar, size, level, mirror, seed, rounds, time));
		return true;
	}

	private static Integer nextInt(StringTokenizer st)
	{
		if (!st.hasMoreTokens())
		{
			return null;
		}
		try
		{
			return Integer.parseInt(st.nextToken());
		}
		catch (NumberFormatException e)
		{
			return null;
		}
	}

	private static Npc getTargetFake(Player activeChar)
	{
		final WorldObject target = activeChar.getTarget();
		if ((target instanceof Npc) && (((Npc) target).getTemplate().getFakePlayerPvpProfile() != null))
		{
			return (Npc) target;
		}
		return null;
	}

	private static void send(Player activeChar, List<String> lines)
	{
		for (String line : lines)
		{
			activeChar.sendSysMessage(line);
		}
	}

	private static void showHelp(Player activeChar)
	{
		activeChar.sendSysMessage("//SetSimulationPoint <n> - your position becomes spawn point n (a team spawns at each point).");
		activeChar.sendSysMessage("//SimulationPoints - list and draw the points. //ClearSimulationPoint <n|all> - remove points.");
		activeChar.sendSysMessage("//Simulate <size 1-9> [level] [mirror] [seed=<n>] [rounds=<n>] [time=<s>] - one balanced party per point; they fight until one team is left.");
		activeChar.sendSysMessage("//SimulationStatus - teams, HP, targets and anomalies. //SimulationStop - end it now.");
		activeChar.sendSysMessage("//JoinParty <team> [tank|melee|ranged|healer|buffer] [lead] - take the place of a fake player of that team. //LeaveParty - leave it.");
		activeChar.sendSysMessage("//SimulationTrace - the last AI decisions of your target (and trace it). //SimulationDraw - draw its ranges and targets.");
		activeChar.sendSysMessage("Logs: " + FakeSimulationConfig.LOG_DIR + "/<time>-<seed>/round-NN/ (run.json, events, decisions, positions, anomalies, summary).");
	}

	@Override
	public String[] getCommandList()
	{
		return ADMIN_COMMANDS;
	}
}
