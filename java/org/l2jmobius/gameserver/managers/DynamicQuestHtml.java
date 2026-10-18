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
package org.l2jmobius.gameserver.managers;

import java.util.List;
import java.util.Map;

import org.l2jmobius.gameserver.config.custom.DynamicQuestConfig;
import org.l2jmobius.gameserver.data.xml.DynamicQuestData;
import org.l2jmobius.gameserver.managers.DynamicQuestManager.Channel;
import org.l2jmobius.gameserver.model.actor.Npc;
import org.l2jmobius.gameserver.model.actor.Player;
import org.l2jmobius.gameserver.model.dynamicquest.DifficultyContext;
import org.l2jmobius.gameserver.model.dynamicquest.QuestInstance;
import org.l2jmobius.gameserver.model.dynamicquest.QuestOffer;
import org.l2jmobius.gameserver.model.dynamicquest.QuestTemplate;
import org.l2jmobius.gameserver.model.dynamicquest.QuestTier;
import org.l2jmobius.gameserver.model.dynamicquest.QuestTrigger;
import org.l2jmobius.gameserver.model.dynamicquest.RewardCalculator;
import org.l2jmobius.gameserver.model.dynamicquest.RewardCalculator.Payout;
import org.l2jmobius.gameserver.model.dynamicquest.StageTemplate;
import org.l2jmobius.gameserver.model.dynamicquest.TriggerType;
import org.l2jmobius.gameserver.model.dynamicquest.objective.AbstractObjective;
import org.l2jmobius.gameserver.model.dynamicquest.objective.DeliverObjective;
import org.l2jmobius.gameserver.model.siege.Castle;
import org.l2jmobius.gameserver.util.QuestSpawnUtil;

/**
 * The pages of the Dynamic Quests: the board, an offer, a running quest. The same pages serve the Notice Board npc, the Community Board and the .quests window; only their links differ ({@link Channel}).
 * @author Mobius
 */
public final class DynamicQuestHtml
{
	private static final String COLOR_TITLE = "CDB67F";
	private static final String COLOR_TEXT = "B0B0B0";
	private static final String COLOR_MUTED = "808080";
	private static final String COLOR_GOOD = "99CC99";

	private DynamicQuestHtml()
	{
	}

	private static int width(Channel channel)
	{
		return channel == Channel.BOARD ? 500 : 270;
	}

	/**
	 * @param body a page's body with %prefix% links
	 * @param channel where it goes
	 * @return the page for that channel (the Community Board adds its own frame)
	 */
	public static String wrap(String body, Channel channel)
	{
		final String content = body.replace("%prefix%", channel.getPrefix());
		return channel == Channel.BOARD ? content : "<html><body>" + content + "</body></html>";
	}

	private static String title(String text, Channel channel)
	{
		return "<table width=" + width(channel) + " cellpadding=0 cellspacing=0><tr><td align=center><font color=\"" + COLOR_TITLE + "\">" + text + "</font></td></tr></table><img src=\"L2UI.SquareGray\" width=" + width(channel) + " height=1><br>";
	}

	private static String button(String bypass, String label, int buttonWidth)
	{
		return "<button value=\"" + label + "\" action=\"%prefix%" + bypass + "\" width=" + buttonWidth + " height=21 back=\"L2UI_CT1.Button_DF_Down\" fore=\"L2UI_CT1.Button_DF\">";
	}

	private static String link(String bypass, String label)
	{
		return "<a action=\"%prefix%" + bypass + "\">" + label + "</a>";
	}

	private static String backButton()
	{
		return "<br><center>" + button("main", "Back", 100) + "</center>";
	}

	/**
	 * @param template a template
	 * @return its name for lists, before it is rolled
	 */
	public static String getTitle(QuestTemplate template)
	{
		return template.getText("title", template.getName().replaceAll("\\{[a-zA-Z0-9]+\\}", "").replaceAll("[:\\s]+$", "").trim());
	}

	private static String fill(DynamicQuestManager manager, String text, QuestOffer offer)
	{
		String result = text;
		if ((result.contains("{lord}") || result.contains("{castle}")) && (offer.getTown() != null))
		{
			final Castle castle = manager.getTownCastle(offer.getTown());
			if ((castle != null) && (castle.getOwner() != null))
			{
				result = result.replace("{lord}", castle.getOwner().getName()).replace("{castle}", castle.getName());
			}
		}
		result = result.replace("{town}", offer.getTown() != null ? offer.getTown() : "town");
		result = result.replace("{requester}", offer.getRequester() != null ? offer.getRequester() : "someone");
		for (Map.Entry<String, String> entry : offer.getCarry().entrySet())
		{
			result = result.replace("{" + entry.getKey() + "}", entry.getValue());
		}
		return result.replaceAll("\\{[a-zA-Z0-9]+\\}", "someone");
	}

	public static String message(String text, Channel channel)
	{
		return wrap(title("Notice", channel) + "<font color=\"" + COLOR_TEXT + "\">" + text + "</font><br>" + backButton(), channel);
	}

	// ---------------------------------------------------------------------------------------------
	// Main page
	// ---------------------------------------------------------------------------------------------

	public static String main(DynamicQuestManager manager, Player player, Npc npc, Channel channel)
	{
		final StringBuilder sb = new StringBuilder();
		final String boardTown = manager.getBoardTown(npc);
		final String town = boardTown != null ? boardTown : manager.getNearestTown(player);
		if (boardTown != null)
		{
			sb.append(title("Notice Board of " + boardTown, channel));
		}
		else
		{
			sb.append(title(channel == Channel.WINDOW ? "Your Quests" : "Bounties & Requests", channel));
		}
		if (!DynamicQuestConfig.ENABLED)
		{
			sb.append("<font color=\"").append(COLOR_TEXT).append("\">The board is empty.</font>");
			return wrap(sb.toString(), channel);
		}

		// Running quests.
		final List<QuestInstance> quests = manager.getQuests(player);
		sb.append("<font color=\"LEVEL\">Your quests</font> <font color=\"").append(COLOR_MUTED).append("\">(").append(manager.getOwnedQuests(player).size()).append("/").append(DynamicQuestConfig.MAX_ACTIVE).append(")</font><br1>");
		if (quests.isEmpty())
		{
			sb.append("<font color=\"").append(COLOR_MUTED).append("\">None yet.</font><br1>");
		}
		for (QuestInstance quest : quests)
		{
			sb.append("- ").append(link("quest " + quest.getId(), quest.getName())).append(" <font color=\"").append(quest.getDifficulty().getTier().getColor()).append("\">").append(quest.getDifficulty().getTier().getDisplayName()).append("</font>");
			if (quest.getOwnerId() != player.getObjectId())
			{
				sb.append(" <font color=\"").append(COLOR_MUTED).append("\">(").append(quest.isWorld() ? "world" : quest.getOwnerName()).append(")</font>");
			}
			sb.append("<br1>");
		}

		final QuestOffer personal = manager.getPersonalOffer(player);
		if (personal != null)
		{
			sb.append("<br><font color=\"LEVEL\">Offered to you</font><br1>- ").append(link("offer " + personal.getKey(), getTitle(personal.getTemplate()))).append("<br1>");
		}

		// Today's board.
		if (town != null)
		{
			final List<QuestOffer> offers = manager.getBoardOffers(town);
			sb.append("<br><font color=\"LEVEL\">Today in ").append(town).append("</font><br1>");
			boolean requests = false;
			for (QuestOffer offer : offers)
			{
				if (!manager.isAvailable(offer))
				{
					continue;
				}
				if (offer.isRequest() && !requests)
				{
					requests = true;
					sb.append("<br><font color=\"LEVEL\">Requests</font>");
					if (boardTown == null)
					{
						sb.append(" <font color=\"").append(COLOR_MUTED).append("\">(taken at the Notice Board)</font>");
					}
					sb.append("<br1>");
				}
				final boolean taken = manager.isTaken(player, offer);
				sb.append("- ");
				if (taken)
				{
					sb.append("<font color=\"").append(COLOR_MUTED).append("\">").append(getTitle(offer.getTemplate())).append(" (taken)</font>");
				}
				else
				{
					sb.append(link("offer " + offer.getKey(), getTitle(offer.getTemplate())));
					sb.append(" <font color=\"").append(COLOR_MUTED).append("\">").append(offer.getRequester() != null ? offer.getRequester() : offer.getTemplate().getCategory()).append("</font>");
					final QuestTrigger trigger = offer.getTemplate().getTrigger(TriggerType.BOARD_DAILY);
					if (!offer.isRequest() && (trigger != null) && "NIGHT".equals(trigger.getRequires()))
					{
						sb.append(" <font color=\"8888FF\">(tonight)</font>");
					}
				}
				sb.append("<br1>");
			}
			if (offers.isEmpty())
			{
				sb.append("<font color=\"").append(COLOR_MUTED).append("\">Nothing today.</font><br1>");
			}
		}

		// World events.
		final List<QuestInstance> world = manager.getWorldQuests();
		if (!world.isEmpty())
		{
			sb.append("<br><font color=\"LEVEL\">World events</font><br1>");
			for (QuestInstance quest : world)
			{
				sb.append("- ").append(link("quest " + quest.getId(), quest.getName())).append(" <font color=\"").append(COLOR_MUTED).append("\">near ").append(quest.getContext("near", "?")).append(", level ").append(quest.getDifficulty().getQuestLevel()).append("</font><br1>");
			}
		}

		if (channel != Channel.NPC)
		{
			final boolean heralds = !player.getVariables().getBoolean(DynamicQuestManager.VAR_NO_HERALDS, false);
			sb.append("<br><font color=\"").append(COLOR_MUTED).append("\">Heralds bringing you quests: ").append(heralds ? "on" : "off").append("</font> ").append(link("heralds " + (heralds ? "off" : "on"), heralds ? "[turn off]" : "[turn on]")).append("<br1>");
		}
		sb.append("<br><font color=\"").append(COLOR_MUTED).append("\">Done today by your account: ").append(manager.getAccountDailyTotal(player.getAccountName())).append("/").append(DynamicQuestConfig.ACCOUNT_DAILY_LIMIT).append(". The board changes every day at ").append(DynamicQuestConfig.BOARD_RESET_HOUR).append(":00.</font>");
		return wrap(sb.toString(), channel);
	}

	// ---------------------------------------------------------------------------------------------
	// Offer
	// ---------------------------------------------------------------------------------------------

	public static String offer(DynamicQuestManager manager, Player player, Npc npc, QuestOffer offer, Channel channel)
	{
		final QuestTemplate template = offer.getTemplate();
		final StringBuilder sb = new StringBuilder();
		sb.append(title(getTitle(template), channel));
		if (offer.getRequester() != null)
		{
			sb.append("<font color=\"LEVEL\">").append(offer.getRequester()).append("</font> asks:<br1>");
		}
		sb.append("<font color=\"").append(COLOR_TEXT).append("\">").append(fill(manager, template.getText("offer", "There is work to be done."), offer)).append("</font><br><br>");

		final List<Player> members = DynamicQuestManager.getMembers(player);
		sb.append("<font color=\"").append(COLOR_MUTED).append("\">It adjusts to you").append(members.size() > 1 ? " and the " + (members.size() - 1) + " party members near you" : "").append(". Choose how hard:</font><br1>");
		sb.append("<table width=").append(width(channel)).append(">");
		for (QuestTier tier : QuestTier.values())
		{
			if ((offer.getTier() != null) && (offer.getTier() != tier))
			{
				continue;
			}
			final DifficultyContext context = DifficultyContext.of(player, members, tier);
			final Payout preview = RewardCalculator.compute(template.getReward(), context.getQuestLevel(), tier.getRewardMultiplier() * (1 + ((offer.getChain() * DynamicQuestConfig.CHAIN_BONUS_PERCENT) / 100.0)), false);
			sb.append("<tr><td width=70><font color=\"").append(tier.getColor()).append("\">").append(tier.getDisplayName()).append("</font></td>");
			sb.append("<td><font color=\"").append(COLOR_TEXT).append("\">Lv. ").append(context.getQuestLevel()).append(" - ").append(String.format("%,d", preview._exp)).append(" EXP, ").append(String.format("%,d", preview._adena)).append(" Adena</font></td></tr>");
		}
		sb.append("</table><br>");

		String problem = null;
		if (manager.isTaken(player, offer))
		{
			problem = "Your account already took this one today.";
		}
		else if (offer.isBoardOffer() && !manager.isAvailable(offer))
		{
			problem = "This one can only be taken at night.";
		}
		else if (offer.isRequest() && ((manager.getBoardTown(npc) == null) || !manager.getBoardTown(npc).equals(offer.getTown())))
		{
			problem = "Take this request at the Notice Board of " + offer.getTown() + ".";
		}
		else
		{
			problem = template.getEligibility().test(player, members);
		}

		if (problem != null)
		{
			sb.append("<font color=\"FF6666\">").append(problem).append("</font><br>");
		}
		else
		{
			sb.append("<center>");
			for (QuestTier tier : QuestTier.values())
			{
				if ((offer.getTier() == null) || (offer.getTier() == tier))
				{
					sb.append(button("accept " + offer.getKey() + " " + tier.name(), offer.getTier() != null ? "Accept" : tier.getDisplayName(), 120)).append("<br1>");
				}
			}
			sb.append("</center>");
		}
		if (!offer.isBoardOffer())
		{
			sb.append("<center>").append(button("decline", "Not interested", 120)).append("</center>");
		}
		else
		{
			sb.append(backButton());
		}
		return wrap(sb.toString(), channel);
	}

	// ---------------------------------------------------------------------------------------------
	// Running quest
	// ---------------------------------------------------------------------------------------------

	public static String quest(DynamicQuestManager manager, Player player, Npc npc, QuestInstance quest, Channel channel)
	{
		final StringBuilder sb = new StringBuilder();
		sb.append(title(quest.getName(), channel));
		final QuestTier tier = quest.getDifficulty().getTier();
		sb.append("<font color=\"").append(tier.getColor()).append("\">").append(tier.getDisplayName()).append("</font> <font color=\"").append(COLOR_MUTED).append("\">- level ").append(quest.getDifficulty().getQuestLevel());
		if (!quest.isWorld())
		{
			sb.append(", party of ").append(quest.getDifficulty().getPartySize()).append(", taken by ").append(quest.getOwnerName());
		}
		else
		{
			sb.append(", near ").append(quest.getContext("near", "?"));
		}
		sb.append("</font><br>");

		final StageTemplate stage = quest.getStage();
		synchronized (quest)
		{
			if (stage != null)
			{
				sb.append("<font color=\"LEVEL\">").append(quest.getStageIndex() + 1).append("/").append(quest.getTemplate().getStages().size()).append(" - ").append(quest.format(stage.getName())).append("</font><br1>");
				if (stage.getText() != null)
				{
					sb.append("<font color=\"").append(COLOR_TEXT).append("\">").append(quest.format(stage.getText())).append("</font><br1>");
				}
				sb.append("<br1>");
				boolean deliverable = false;
				for (AbstractObjective objective : quest.getObjectives())
				{
					final String color = objective.isDone() ? COLOR_GOOD : (objective.isOptional() ? COLOR_MUTED : "FFFFFF");
					sb.append("<font color=\"").append(color).append("\">").append("- ").append(objective.describe());
					if (objective.isOptional())
					{
						sb.append(" (optional)");
					}
					sb.append("</font><br1>");
					sb.append("<font color=\"").append(COLOR_MUTED).append("\">&nbsp;&nbsp;").append(quest.format(objective.getProgress()));
					final String bonus = objective.describeBonus();
					if (bonus != null)
					{
						sb.append(" - bonus if you ").append(bonus);
					}
					sb.append("</font><br1>");
					if ((objective instanceof DeliverObjective) && !objective.isDone())
					{
						deliverable = true;
					}
				}
				final int left = quest.getSecondsLeft();
				if (left >= 0)
				{
					sb.append("<br1><font color=\"").append(COLOR_MUTED).append("\">Time left in this stage: ").append(QuestSpawnUtil.formatTime(left)).append("</font><br1>");
				}

				sb.append("<br><center>");
				if (deliverable && (quest.getOwnerId() == player.getObjectId()))
				{
					if ((npc != null) && DynamicQuestData.getInstance().isNoticeBoard(npc.getId()))
					{
						for (AbstractObjective objective : quest.getObjectives())
						{
							if ((objective instanceof DeliverObjective) && !objective.isDone())
							{
								sb.append(button("act " + quest.getId() + " " + objective.getIndex() + " deliver", "Hand it over", 140)).append("<br1>");
							}
						}
					}
					else
					{
						sb.append("<font color=\"").append(COLOR_MUTED).append("\">Hand it over at any Notice Board.</font><br1>");
					}
				}
			}
		}
		sb.append(button("track " + quest.getId(), "Track on radar", 140)).append("<br1>");
		if (quest.getOwnerId() == player.getObjectId())
		{
			sb.append(button("abandon " + quest.getId(), "Abandon", 140)).append("<br1>");
		}
		sb.append(button("main", "Back", 140)).append("</center>");
		return wrap(sb.toString(), channel);
	}

	public static String confirmAbandon(QuestInstance quest, Channel channel)
	{
		final StringBuilder sb = new StringBuilder();
		sb.append(title("Abandon", channel));
		sb.append("<font color=\"").append(COLOR_TEXT).append("\">Abandon ").append(quest.getName()).append("? Everything it spawned is gone, and a board offer can't be taken again today.</font><br><br><center>");
		sb.append(button("abandon_confirm " + quest.getId(), "Abandon it", 120)).append("<br1>");
		sb.append(button("quest " + quest.getId(), "Keep it", 120)).append("</center>");
		return wrap(sb.toString(), channel);
	}

	/**
	 * @param quest the quest
	 * @param body an objective's dialog
	 * @param channel where it goes
	 * @return the dialog with the quest's name and a link to its page
	 */
	public static String wrapObjective(QuestInstance quest, String body, Channel channel)
	{
		return wrap(title(quest.getName(), channel) + body + "<br><br><center>" + button("quest " + quest.getId(), "Quest", 100) + "</center>", channel);
	}

	/**
	 * @param quest the quest
	 * @param payout what the player got
	 * @param successText the quest's success text
	 * @return the completion window
	 */
	public static String complete(QuestInstance quest, Payout payout, String successText)
	{
		final StringBuilder sb = new StringBuilder();
		sb.append(title(quest.getName() + " - complete!", Channel.WINDOW));
		sb.append("<font color=\"").append(COLOR_TEXT).append("\">").append(quest.format(successText)).append("</font><br><br>");
		sb.append("<font color=\"LEVEL\">Your reward</font><br1>").append(RewardCalculator.describe(payout, false));
		if (quest.getOptionalDone() > 0)
		{
			sb.append("<br1><font color=\"").append(COLOR_MUTED).append("\">Optional objectives: ").append(quest.getOptionalDone()).append("</font>");
		}
		if (quest.getChain() > 0)
		{
			sb.append("<br1><font color=\"").append(COLOR_MUTED).append("\">Chain: ").append(quest.getChain() + 1).append(" in a row</font>");
		}
		return wrap(sb.toString(), Channel.WINDOW);
	}
}
