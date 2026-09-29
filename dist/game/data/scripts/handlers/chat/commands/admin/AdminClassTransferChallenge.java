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
package handlers.chat.commands.admin;

import java.util.List;
import java.util.StringTokenizer;
import java.util.logging.Logger;

import org.l2jmobius.gameserver.data.sql.ClassTransferChallengeCompletionTable;
import org.l2jmobius.gameserver.handler.IAdminCommandHandler;
import org.l2jmobius.gameserver.managers.ClassTransferChallengeManager;
import org.l2jmobius.gameserver.model.World;
import org.l2jmobius.gameserver.model.WorldObject;
import org.l2jmobius.gameserver.model.actor.Player;
import org.l2jmobius.gameserver.model.classtransfer.ChallengeDefinition;
import org.l2jmobius.gameserver.model.classtransfer.ChallengeSession;
import org.l2jmobius.gameserver.model.classtransfer.TransferStage;

/**
 * GM tools of the Alternative Class Transfer Challenges. Each command works on the named player, else the targeted player, else yourself.
 * <ul>
 * <li>{@code //challenge_status [name]} - the running trial and the cleared stages.</li>
 * <li>{@code //challenge_start <1|2|3> [name]} - starts the stage's trial, skipping the level, completion and attempt checks.</li>
 * <li>{@code //challenge_complete [objective] [name]} - completes the running trial (or only its current objective).</li>
 * <li>{@code //challenge_complete grant <1|2|3> [name]} - records the stage as cleared without running the trial.</li>
 * <li>{@code //challenge_abort [name]} - fails the running trial and sends the player back.</li>
 * <li>{@code //challenge_reset <1|2|3> [name]} - clears the stage's completion, failed attempts and cooldown.</li>
 * </ul>
 * @author Mobius
 */
public class AdminClassTransferChallenge implements IAdminCommandHandler
{
	private static final Logger LOGGER = Logger.getLogger(AdminClassTransferChallenge.class.getName());

	private static final String[] ADMIN_COMMANDS =
	{
		"admin_challenge_status",
		"admin_challenge_start",
		"admin_challenge_complete",
		"admin_challenge_abort",
		"admin_challenge_reset"
	};

	@Override
	public boolean onCommand(String command, Player activeChar)
	{
		final StringTokenizer st = new StringTokenizer(command);
		final String action = st.nextToken();
		final ClassTransferChallengeManager manager = ClassTransferChallengeManager.getInstance();
		switch (action)
		{
			case "admin_challenge_status":
			{
				final Player player = getPlayer(activeChar, st);
				if (player == null)
				{
					return false;
				}
				showStatus(activeChar, player);
				return true;
			}
			case "admin_challenge_start":
			{
				final TransferStage stage = getStage(activeChar, st, "//challenge_start <1|2|3> [name]");
				final Player player = stage != null ? getPlayer(activeChar, st) : null;
				if (player == null)
				{
					return false;
				}
				final ChallengeDefinition challenge = manager.resolveChallenge(player, stage);
				final String problem = manager.adminStartChallenge(player, challenge);
				activeChar.sendSysMessage(problem == null ? "Started " + challenge.getName() + " (" + challenge.getId() + ") for " + player.getName() + "." : "Could not start: " + problem);
				log(activeChar, "started " + stage + " for " + player.getName() + (problem == null ? "" : " (refused: " + problem + ")"));
				return problem == null;
			}
			case "admin_challenge_complete":
			{
				final String first = st.hasMoreTokens() ? st.nextToken() : null;
				if ("grant".equalsIgnoreCase(first))
				{
					final TransferStage stage = getStage(activeChar, st, "//challenge_complete grant <1|2|3> [name]");
					final Player player = stage != null ? getPlayer(activeChar, st) : null;
					if (player == null)
					{
						return false;
					}
					final boolean granted = manager.adminGrant(player, stage);
					activeChar.sendSysMessage(granted ? player.getName() + " now counts as having cleared the " + stage.getDisplayName() + " trial as " + player.getPlayerClass().name() + "." : "Could not record the completion.");
					log(activeChar, "granted " + stage + " to " + player.getName());
					return granted;
				}

				final boolean objectiveOnly = "objective".equalsIgnoreCase(first);
				final Player player = ((first == null) || objectiveOnly) ? getPlayer(activeChar, st) : getPlayer(activeChar, first);
				if (player == null)
				{
					return false;
				}
				final boolean done = objectiveOnly ? manager.adminCompleteObjective(player) : manager.adminCompleteChallenge(player);
				activeChar.sendSysMessage(done ? "Completed the " + (objectiveOnly ? "current objective" : "trial") + " of " + player.getName() + "." : player.getName() + " has no trial in progress.");
				log(activeChar, "completed the " + (objectiveOnly ? "current objective" : "trial") + " of " + player.getName());
				return done;
			}
			case "admin_challenge_abort":
			{
				final Player player = getPlayer(activeChar, st);
				if (player == null)
				{
					return false;
				}
				final boolean aborted = manager.adminAbort(player);
				activeChar.sendSysMessage(aborted ? "Aborted the trial of " + player.getName() + "." : player.getName() + " has no trial in progress.");
				log(activeChar, "aborted the trial of " + player.getName());
				return aborted;
			}
			case "admin_challenge_reset":
			{
				final TransferStage stage = getStage(activeChar, st, "//challenge_reset <1|2|3> [name]");
				final Player player = stage != null ? getPlayer(activeChar, st) : null;
				if (player == null)
				{
					return false;
				}
				manager.adminReset(player, stage);
				activeChar.sendSysMessage("Reset the " + stage.getDisplayName() + " trial of " + player.getName() + ".");
				log(activeChar, "reset " + stage + " of " + player.getName());
				return true;
			}
		}
		return false;
	}

	private void showStatus(Player activeChar, Player player)
	{
		final ClassTransferChallengeManager manager = ClassTransferChallengeManager.getInstance();
		activeChar.sendSysMessage("== Class transfer trials of " + player.getName() + " (" + player.getPlayerClass().name() + ", class index " + player.getClassIndex() + ") ==");
		for (TransferStage stage : TransferStage.values())
		{
			activeChar.sendSysMessage(stage.getDisplayName() + ": " + (ClassTransferChallengeCompletionTable.getInstance().hasValidCompletion(player, stage) ? "cleared (transfer unlocked)" : "not cleared") + ", transfer " + (manager.isTransferUnlocked(player, stage) ? "allowed" : "locked"));
		}

		final ChallengeSession session = manager.getSession(player);
		if (session == null)
		{
			activeChar.sendSysMessage("No trial in progress.");
			return;
		}

		activeChar.sendSysMessage("Trial: " + session.getDefinition().getId() + " [" + session.getStatus() + "] instance " + session.getInstanceId() + ", " + session.getRemainingSeconds() + "s left, " + session.getKnockouts() + " knockout(s)" + (session.isOffline() ? ", OFFLINE" : "") + (session.getOmen() != null ? ", omen " + session.getOmen() : "") + (session.getInvaderTwist() != null ? ", invader rolled" : ""));
		final List<String[]> rows = manager.describeObjectives(session);
		for (int i = 0; i < rows.size(); i++)
		{
			final String[] row = rows.get(i);
			activeChar.sendSysMessage((i + 1) + ". [" + row[0] + "] " + row[1] + (row[2].isEmpty() ? "" : " (" + row[2] + ")"));
		}
	}

	private TransferStage getStage(Player activeChar, StringTokenizer st, String usage)
	{
		if (st.hasMoreTokens())
		{
			try
			{
				final TransferStage stage = TransferStage.fromTier(Integer.parseInt(st.nextToken()));
				if (stage != null)
				{
					return stage;
				}
			}
			catch (NumberFormatException e)
			{
				// Handled below.
			}
		}
		activeChar.sendSysMessage("Usage: " + usage);
		return null;
	}

	private Player getPlayer(Player activeChar, StringTokenizer st)
	{
		return getPlayer(activeChar, st.hasMoreTokens() ? st.nextToken() : null);
	}

	/**
	 * @param activeChar the GM
	 * @param name a player name, {@code null} for the GM's target (or the GM)
	 * @return the player, {@code null} if the named one is not online
	 */
	private Player getPlayer(Player activeChar, String name)
	{
		if (name != null)
		{
			final Player player = World.getInstance().getPlayer(name);
			if (player == null)
			{
				activeChar.sendSysMessage("Player " + name + " is not online.");
			}
			return player;
		}

		final WorldObject target = activeChar.getTarget();
		return (target != null) && target.isPlayer() ? target.asPlayer() : activeChar;
	}

	private void log(Player activeChar, String what)
	{
		LOGGER.info("GM " + activeChar.getName() + " " + what + ".");
	}

	@Override
	public String[] getCommandList()
	{
		return ADMIN_COMMANDS;
	}
}
