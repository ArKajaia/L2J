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

import java.util.StringTokenizer;
import java.util.stream.Collectors;

import org.l2jmobius.gameserver.config.custom.NightCycleConfig;
import org.l2jmobius.gameserver.handler.IAdminCommandHandler;
import org.l2jmobius.gameserver.managers.HotzoneModifierManager;
import org.l2jmobius.gameserver.managers.NightCycleManager;
import org.l2jmobius.gameserver.managers.NightlordManager;
import org.l2jmobius.gameserver.managers.NightlordManager.Nightlord;
import org.l2jmobius.gameserver.managers.ShadowRaidManager;
import org.l2jmobius.gameserver.managers.ShadowRaidManager.Raid;
import org.l2jmobius.gameserver.model.actor.Npc;
import org.l2jmobius.gameserver.model.actor.Player;
import org.l2jmobius.gameserver.model.hotzone.HotzoneModifier;
import org.l2jmobius.gameserver.model.nightcycle.NightPhase;
import org.l2jmobius.gameserver.taskmanagers.GameTimeTaskManager;

/**
 * GM tools of the Night Cycle (see {@link NightCycleManager}).
 * <ul>
 * <li>{@code //night} or {@code //night status} - the phase, the game time, the night's modifier and the Nightlords.</li>
 * <li>{@code //night day|dusk|night|witching|dawn} - forces a phase until the clock reaches its next one.</li>
 * <li>{@code //night auto} - follows the clock again.</li>
 * <li>{@code //night omen <name>} - sets tonight's Omen (a hot zone modifier name).</li>
 * <li>{@code //night nightlord} - raises the missing Nightlords now.</li>
 * <li>{@code //night raid} - starts a Shadow Raid near the targeted player, or near a random player hunting in the open world (or the GM, if nobody is).</li>
 * </ul>
 */
public class AdminNight implements IAdminCommandHandler
{
	private static final String[] ADMIN_COMMANDS =
	{
		"admin_night"
	};

	@Override
	public boolean onCommand(String command, Player activeChar)
	{
		if (!NightCycleConfig.ENABLED)
		{
			activeChar.sendSysMessage("The Night Cycle is disabled (NightCycleEnabled in NightCycle.ini).");
			return false;
		}

		final StringTokenizer st = new StringTokenizer(command);
		st.nextToken();
		final String action = st.hasMoreTokens() ? st.nextToken().toLowerCase() : "status";
		final NightCycleManager manager = NightCycleManager.getInstance();
		switch (action)
		{
			case "status":
			{
				showStatus(activeChar);
				return true;
			}
			case "day":
			case "dusk":
			case "night":
			case "witching":
			case "dawn":
			{
				final NightPhase phase = action.equals("witching") ? NightPhase.WITCHING_HOUR : NightPhase.valueOf(action.toUpperCase());
				manager.forcePhase(phase);
				activeChar.sendSysMessage("Forced " + phase.getDisplayName() + " until the clock reaches its next phase (//night auto to follow the clock now).");
				return true;
			}
			case "auto":
			{
				manager.forcePhase(null);
				activeChar.sendSysMessage("The night follows the clock again: " + manager.getPhase().getDisplayName() + ".");
				return true;
			}
			case "omen":
			{
				if (!st.hasMoreTokens())
				{
					activeChar.sendSysMessage("Usage: //night omen <name>, one of: " + NightCycleConfig.OMEN_POOL.stream().map(Enum::name).collect(Collectors.joining(", ")));
					return false;
				}

				final String name = st.nextToken().toUpperCase();
				final HotzoneModifier omen;
				try
				{
					omen = HotzoneModifier.valueOf(name);
				}
				catch (IllegalArgumentException e)
				{
					activeChar.sendSysMessage("Unknown modifier " + name + ".");
					return false;
				}

				manager.setOmen(omen);
				activeChar.sendSysMessage("Tonight's Omen is " + NightCycleManager.getOmenName(omen) + (NightCycleConfig.OMEN_POOL.contains(omen) ? "." : " (not in NightOmenPool: hot zone-only parts of it do nothing in the open world)."));
				return true;
			}
			case "nightlord":
			{
				final int risen = NightlordManager.getInstance().riseAll();
				activeChar.sendSysMessage(risen > 0 ? risen + " Nightlord(s) rose." : "No Nightlord rose: every bracket has one, or no monster fits (see the server log).");
				return true;
			}
			case "raid":
			{
				final Player anchor = (activeChar.getTarget() != null) && activeChar.getTarget().isPlayer() && (activeChar.getTarget() != activeChar) ? activeChar.getTarget().asPlayer() : null;
				String problem = ShadowRaidManager.getInstance().startRaid(anchor);
				if ((problem != null) && (anchor == null) && ShadowRaidManager.NO_PLAYER.equals(problem))
				{
					problem = ShadowRaidManager.getInstance().startRaid(activeChar); // Nobody else out there: the raid comes for the GM.
				}
				activeChar.sendSysMessage(problem == null ? "A Shadow Raid started (//night status shows where)." : "No raid: " + problem + ".");
				return problem == null;
			}
			default:
			{
				activeChar.sendSysMessage("Usage: //night [status|day|dusk|night|witching|dawn|auto|omen <name>|nightlord|raid]");
				return false;
			}
		}
	}

	private void showStatus(Player activeChar)
	{
		final NightCycleManager manager = NightCycleManager.getInstance();
		final GameTimeTaskManager clock = GameTimeTaskManager.getInstance();
		activeChar.sendSysMessage("Phase: " + manager.getPhase().getDisplayName() + (manager.isForced() ? " (forced)" : "") + ", game time " + String.format("%02d:%02d", clock.getGameHour(), clock.getGameMinute()) + ", clock phase ends in about " + manager.getRealMinutesLeft() + " min.");

		final HotzoneModifier omen = manager.getOmen();
		final HotzoneModifier nightModifier = HotzoneModifierManager.getInstance().getNightModifier();
		activeChar.sendSysMessage("Omen: " + (omen != null ? NightCycleManager.getOmenName(omen) : "none") + ", in effect now: " + (nightModifier != null ? NightCycleManager.getOmenName(nightModifier) : "none") + ".");

		for (Nightlord nightlord : NightlordManager.getInstance().getNightlords())
		{
			activeChar.sendSysMessage("Nightlord " + nightlord.getMonster().getName() + " (" + nightlord.getBracket() + ", " + nightlord.getZoneName() + ") at " + nightlord.getMonster().getX() + " " + nightlord.getMonster().getY() + " " + nightlord.getMonster().getZ() + ", wave " + nightlord.getMonster().getWaveChallengeWave() + "/" + nightlord.getMonster().getWaveChallengeTotal() + ".");
		}

		for (Raid raid : ShadowRaidManager.getInstance().getRaids())
		{
			final StringBuilder sb = new StringBuilder("Shadow Raid #").append(raid.getId()).append(": ").append(raid.getClanName()).append(" near ").append(raid.getPlace()).append(", raiders");
			for (Npc raider : raid.getAliveRaiders())
			{
				sb.append(' ').append(raider.getName()).append(" (").append(raider.getX()).append(' ').append(raider.getY()).append(' ').append(raider.getZ()).append(')');
			}
			activeChar.sendSysMessage(sb.append('.').toString());
		}
	}

	@Override
	public String[] getCommandList()
	{
		return ADMIN_COMMANDS;
	}
}
