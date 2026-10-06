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
package custom.ClassTransferChallenge;

import java.util.List;

import org.l2jmobius.gameserver.config.RatesConfig;
import org.l2jmobius.gameserver.config.custom.ClassTransferChallengeConfig;
import org.l2jmobius.gameserver.data.xml.ItemData;
import org.l2jmobius.gameserver.managers.ClassTransferChallengeManager;
import org.l2jmobius.gameserver.model.actor.Npc;
import org.l2jmobius.gameserver.model.actor.Player;
import org.l2jmobius.gameserver.model.classtransfer.ChallengeDefinition;
import org.l2jmobius.gameserver.model.classtransfer.ChallengeReward;
import org.l2jmobius.gameserver.model.classtransfer.ChallengeSession;
import org.l2jmobius.gameserver.model.classtransfer.ObjectiveDefinition;
import org.l2jmobius.gameserver.model.classtransfer.TrackedNpc.NpcRole;
import org.l2jmobius.gameserver.model.classtransfer.TransferStage;
import org.l2jmobius.gameserver.model.classtransfer.TwistDefinition;
import org.l2jmobius.gameserver.model.hotzone.HotzoneModifier;
import org.l2jmobius.gameserver.model.item.ItemTemplate;
import org.l2jmobius.gameserver.model.script.Quest;

/**
 * Dialogs of the Alternative Class Transfer Challenges: the challenge details offered by the Class Master (its HTML links here), and the NPCs inside a trial (Trial Guide, Trial Master, Trial Seals, Trial Circle). Every decision is made - and validated - by the core
 * {@link ClassTransferChallengeManager}; this script only renders pages and forwards requests. The Class Master's own first-talk stays with {@code custom.ClassTransferMaster}.
 * @author Mobius
 */
public class ClassTransferChallenge extends Quest
{
	// NPCs
	private static final int TRIAL_GUIDE = 900240;
	private static final int TRIAL_MASTER = 900241;
	private static final int TRIAL_SEAL = 900242;
	private static final int TRIAL_CIRCLE = 900243;
	private static final int TRIAL_WARD = 900244;

	public ClassTransferChallenge()
	{
		super(-1);
		addStartNpc(TRIAL_GUIDE, TRIAL_MASTER, TRIAL_SEAL, TRIAL_CIRCLE, TRIAL_WARD);
		addFirstTalkId(TRIAL_GUIDE, TRIAL_MASTER, TRIAL_SEAL, TRIAL_CIRCLE, TRIAL_WARD);
		addTalkId(TRIAL_GUIDE, TRIAL_MASTER, TRIAL_SEAL, TRIAL_CIRCLE, TRIAL_WARD);
	}

	@Override
	public String onEvent(String event, Npc npc, Player player)
	{
		if ((npc == null) || (player == null))
		{
			return null;
		}

		final ClassTransferChallengeManager manager = ClassTransferChallengeManager.getInstance();
		switch (event)
		{
			// From the Class Master: the stage always comes from the NPC and the player's current class, never from the link.
			case "info":
			{
				final TransferStage stage = ClassTransferChallengeManager.getClassMasterStage(npc.getId(), player);
				return stage == null ? null : showDetails(player, stage);
			}
			case "enter":
			{
				final TransferStage stage = ClassTransferChallengeManager.getClassMasterStage(npc.getId(), player);
				if (stage == null)
				{
					return null;
				}
				final String problem = manager.startChallenge(player, stage);
				return problem == null ? null : notEligible(player, problem);
			}
			// Inside a trial.
			case "progress":
			{
				return manager.getNpcRole(player, npc) == NpcRole.GUIDE ? showProgress(player) : null;
			}
			case "abandon":
			{
				return manager.getNpcRole(player, npc) == NpcRole.GUIDE ? getHtm(player, "abandon.html") : null;
			}
			case "abandon_confirm":
			{
				if (manager.getNpcRole(player, npc) == NpcRole.GUIDE)
				{
					manager.abandon(player);
				}
				return null;
			}
			case "activate":
			{
				if ((manager.getNpcRole(player, npc) == NpcRole.SEAL) && !manager.activate(player, npc))
				{
					player.sendMessage("The seal does not answer you now.");
				}
				return null;
			}
			case "return":
			{
				if (manager.getNpcRole(player, npc) == NpcRole.TALK)
				{
					manager.returnFromChallenge(player);
				}
				return null;
			}
		}
		return null;
	}

	@Override
	public String onFirstTalk(Npc npc, Player player)
	{
		final ClassTransferChallengeManager manager = ClassTransferChallengeManager.getInstance();
		final NpcRole role = manager.getNpcRole(player, npc);
		if (role == null)
		{
			return getHtm(player, "not_yours.html");
		}

		switch (role)
		{
			case GUIDE:
			{
				return showProgress(player);
			}
			case TALK:
			{
				manager.talk(player, npc);
				final ChallengeSession session = manager.getSession(player);
				if ((session != null) && (session.getStatus() == ChallengeSession.Status.COMPLETED))
				{
					return getHtm(player, "master_complete.html").replace("%name%", session.getDefinition().getName());
				}
				final String current = (session != null) && (session.getCurrentObjective() != null) ? session.getCurrentObjective().getText() : "";
				return getHtm(player, "master_pending.html").replace("%objective%", current);
			}
			case SEAL:
			{
				return getHtm(player, "seal.html");
			}
			case MARKER:
			{
				return getHtm(player, "marker.html");
			}
			case WARD:
			{
				return getHtm(player, "ward.html");
			}
			default:
			{
				return getHtm(player, "not_yours.html");
			}
		}
	}

	@Override
	public String onTalk(Npc npc, Player player)
	{
		return onFirstTalk(npc, player);
	}

	private String showDetails(Player player, TransferStage stage)
	{
		final ClassTransferChallengeManager manager = ClassTransferChallengeManager.getInstance();
		final String problem = manager.checkEligibility(player, stage);
		if (problem != null)
		{
			return notEligible(player, problem);
		}

		final ChallengeDefinition challenge = manager.resolveChallenge(player, stage);
		final int duration = challenge.getDuration() > 0 ? challenge.getDuration() : ClassTransferChallengeConfig.DEFAULT_DURATION;

		final StringBuilder objectives = new StringBuilder();
		int number = 1;
		for (ObjectiveDefinition objective : challenge.getObjectives())
		{
			objectives.append("<tr><td><font color=\"CCCCCC\">").append(number++).append(". ").append(objective.getText()).append("</font></td></tr>");
		}

		final HotzoneModifier omen = manager.previewOmen(player, challenge);
		final String omenText = omen == null ? "<font color=\"999999\">none</font>" : "<font color=\"FFCC66\">" + ClassTransferChallengeManager.formatOmen(omen) + "</font><br1><font color=\"CCCCCC\">" + omen.getDescription() + "</font>";

		final StringBuilder twists = new StringBuilder();
		if (ClassTransferChallengeConfig.TWISTS_ENABLED)
		{
			for (TwistDefinition twist : challenge.getTwists())
			{
				if (twist.getType() == TwistDefinition.TwistType.INVADER)
				{
					twists.append("<br1><font color=\"FF9966\">Beware:</font> <font color=\"CCCCCC\">a rival may invade your trial (").append(twist.getChance()).append("%).</font>");
				}
			}
		}

		String html = getHtm(player, "details.html");
		html = html.replace("%name%", challenge.getName());
		html = html.replace("%stage%", stage.getDisplayName());
		html = html.replace("%difficulty%", challenge.getDifficulty());
		html = html.replace("%time%", String.valueOf(duration / 60));
		html = html.replace("%description%", challenge.getDescription());
		html = html.replace("%objectives%", objectives.toString());
		html = html.replace("%omen%", omenText);
		html = html.replace("%twists%", twists.toString());
		html = html.replace("%rewards%", describeRewards(challenge));
		return html;
	}

	private String describeRewards(ChallengeDefinition challenge)
	{
		if (!ClassTransferChallengeConfig.REWARDS_ENABLED || challenge.getRewards().isEmpty())
		{
			return "The right to your new class.";
		}

		final StringBuilder sb = new StringBuilder("The right to your new class");
		for (ChallengeReward reward : challenge.getRewards())
		{
			sb.append("<br1>");
			switch (reward.getType())
			{
				case ITEM:
				{
					sb.append(reward.getCount()).append(" ").append(getItemName(reward.getItemId()));
					break;
				}
				case ARENA_COINS:
				{
					sb.append(reward.getCount()).append(" ").append(getItemName(RatesConfig.ARENA_CURRENCY_ITEM_ID));
					break;
				}
				case SEALED_CACHE:
				{
					sb.append("A Sealed Cache");
					break;
				}
				case EXP:
				{
					sb.append(reward.getCount()).append(" XP");
					break;
				}
				case SP:
				{
					sb.append(reward.getCount()).append(" SP");
					break;
				}
			}
			if ((reward.getParBonus() > 0) && (challenge.getParTime() > 0))
			{
				sb.append(" <font color=\"999999\">(+").append(reward.getParBonus()).append(" within ").append(challenge.getParTime() / 60).append(" min)</font>");
			}
		}
		return sb.toString();
	}

	private String showProgress(Player player)
	{
		final ClassTransferChallengeManager manager = ClassTransferChallengeManager.getInstance();
		final ChallengeSession session = manager.getSession(player);
		if (session == null)
		{
			return getHtm(player, "not_yours.html");
		}

		final StringBuilder objectives = new StringBuilder();
		final List<String[]> rows = manager.describeObjectives(session);
		for (int i = 0; i < rows.size(); i++)
		{
			final String[] row = rows.get(i);
			final String color = "done".equals(row[0]) ? "55FF55" : "current".equals(row[0]) ? "LEVEL" : "777777";
			objectives.append("<tr><td><font color=\"").append(color).append("\">").append(i + 1).append(". ").append(row[1]);
			if (!row[2].isEmpty())
			{
				objectives.append(" (").append(row[2]).append(")");
			}
			objectives.append("</font></td></tr>");
		}

		final int seconds = session.getRemainingSeconds();
		final HotzoneModifier omen = session.getOmen();
		String html = getHtm(player, "progress.html");
		html = html.replace("%name%", session.getDefinition().getName());
		html = html.replace("%time_left%", (seconds / 60) + ":" + ((seconds % 60) < 10 ? "0" : "") + (seconds % 60));
		html = html.replace("%knockouts%", String.valueOf(session.getKnockouts()));
		html = html.replace("%objectives%", objectives.toString());
		html = html.replace("%omen%", omen == null ? "none" : "<font color=\"FFCC66\">" + ClassTransferChallengeManager.formatOmen(omen) + "</font> - " + omen.getDescription());
		return html;
	}

	private String notEligible(Player player, String reason)
	{
		return getHtm(player, "not_eligible.html").replace("%reason%", reason);
	}

	private static String getItemName(int itemId)
	{
		final ItemTemplate item = ItemData.getInstance().getTemplate(itemId);
		return item != null ? item.getName() : "Arena currency";
	}

	public static void main(String[] args)
	{
		new ClassTransferChallenge();
	}
}
