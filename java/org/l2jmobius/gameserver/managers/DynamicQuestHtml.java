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

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.l2jmobius.gameserver.config.custom.DynamicQuestConfig;
import org.l2jmobius.gameserver.config.custom.HiddenQuestConfig;
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
import org.l2jmobius.gameserver.model.hiddenquest.HiddenQuestSession;
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

	// The Community Board pages: wider, with cards and gauges. Only what the High Five client draws: colored tables (never rows or cells), cell backgrounds and gauge textures. No item icons: the client crops them to the cell instead of scaling them, and they overlap the text.
	private static final int BOARD_WIDTH = 510;
	private static final int HALF_WIDTH = (BOARD_WIDTH - 6) / 2;
	private static final String COLOR_NAME = "EDE3C8";
	private static final String COLOR_LABEL = "7E7664";
	private static final String COLOR_DIM = "8C8576";
	private static final String COLOR_VALUE = "FFCC33";
	private static final String COLOR_BAD = "FF6666";
	private static final String COLOR_NIGHT = "8888FF";
	private static final String BG_PANEL = "15130F";
	private static final String BG_CARD = "14120E";
	private static final String BG_CARD_DIM = "0F0E0B";
	private static final String GAUGE_PROGRESS = "EXP";
	private static final String GAUGE_TIME = "MP";
	/** "3/15" in an objective's progress line. */
	private static final Pattern COUNT = Pattern.compile("(\\d+)\\s*/\\s*(\\d+)");

	private DynamicQuestHtml()
	{
	}

	private static int width(Channel channel)
	{
		return channel == Channel.BOARD ? BOARD_WIDTH : 270;
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
		if (channel == Channel.BOARD)
		{
			return boardHeader(text, null, null, 0) + spacer(10);
		}
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
		final String boardTown = manager.getBoardTown(npc);
		final String town = boardTown != null ? boardTown : manager.getNearestTown(player);
		if ((channel == Channel.BOARD) && DynamicQuestConfig.ENABLED)
		{
			return wrap(boardMain(manager, player, town), channel);
		}
		final StringBuilder sb = new StringBuilder();
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

		// The hidden quest under way (some clients never show its question mark).
		if ((channel != Channel.NPC) && HiddenQuestConfig.ENABLED)
		{
			final HiddenQuestSession hidden = HiddenQuestManager.getInstance().getSession(player);
			if (hidden != null)
			{
				sb.append("<br><font color=\"LEVEL\">Hidden quest</font><br1>- ").append(link("hidden", hidden.getDefinition().getName())).append(" <font color=\"").append(COLOR_MUTED).append("\">").append(hidden.getTask() != null ? hidden.getTask().getProgress() : "").append("</font><br1>");
			}
			else if (HiddenQuestManager.getInstance().getVisitingQuest(player) != null)
			{
				sb.append("<br><font color=\"LEVEL\">Hidden quest</font><br1>- ").append(link("hidden", "A messenger is looking for you")).append("<br1>");
			}
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
		if (channel == Channel.BOARD)
		{
			return wrap(boardOffer(manager, player, npc, offer), channel);
		}
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

		final String problem = getProblem(manager, player, npc, offer, members);
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

	/**
	 * @return why the player can't take the offer here, {@code null} if they can
	 */
	private static String getProblem(DynamicQuestManager manager, Player player, Npc npc, QuestOffer offer, List<Player> members)
	{
		if (manager.isTaken(player, offer))
		{
			return "Your account already took this one today.";
		}
		if (offer.isBoardOffer() && !manager.isAvailable(offer))
		{
			return "This one can only be taken at night.";
		}
		if (offer.isRequest() && ((manager.getBoardTown(npc) == null) || !manager.getBoardTown(npc).equals(offer.getTown())))
		{
			return "Take this request at the Notice Board of " + offer.getTown() + ".";
		}
		return offer.getTemplate().getEligibility().test(player, members);
	}

	// ---------------------------------------------------------------------------------------------
	// Running quest
	// ---------------------------------------------------------------------------------------------

	public static String quest(DynamicQuestManager manager, Player player, Npc npc, QuestInstance quest, Channel channel)
	{
		if (channel == Channel.BOARD)
		{
			return wrap(boardQuest(player, npc, quest), channel);
		}
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

	// ---------------------------------------------------------------------------------------------
	// Community Board pages
	// ---------------------------------------------------------------------------------------------

	private static String boardMain(DynamicQuestManager manager, Player player, String town)
	{
		final StringBuilder sb = new StringBuilder();
		final int owned = manager.getOwnedQuests(player).size();
		final String chips = "<table cellpadding=0 cellspacing=0><tr><td>" + chip("ACTIVE", owned, DynamicQuestConfig.MAX_ACTIVE) + "</td><td width=6></td><td>" + chip("TODAY", manager.getAccountDailyTotal(player.getAccountName()), DynamicQuestConfig.ACCOUNT_DAILY_LIMIT) + "</td></tr></table>";
		sb.append(boardHeader("Bounties &amp; Requests", (town != null ? town + " - " : "") + "new board at " + DynamicQuestConfig.BOARD_RESET_HOUR + ":00", chips, 150));

		// Running quests.
		final List<QuestInstance> quests = manager.getQuests(player);
		sb.append(spacer(8)).append(sectionHeader("Your quests", null));
		if (quests.isEmpty())
		{
			sb.append(note("None yet. Take one from today's board below."));
		}
		for (QuestInstance quest : quests)
		{
			final QuestTier tier = quest.getDifficulty().getTier();
			String top = link("quest " + quest.getId(), quest.getName()) + " <font color=\"" + tier.getColor() + "\">" + tier.getDisplayName() + "</font>";
			if (quest.getOwnerId() != player.getObjectId())
			{
				top += " <font color=\"" + COLOR_LABEL + "\">(" + (quest.isWorld() ? "world" : quest.getOwnerName()) + ")</font>";
			}
			final StageTemplate stage = quest.getStage();
			final String stageText = "Stage " + (quest.getStageIndex() + 1) + " of " + quest.getTemplate().getStages().size() + (stage != null ? " - " + quest.format(stage.getName()) : "");
			final String bottom = "<table cellpadding=0 cellspacing=0><tr><td width=110>" + gauge(110, getProgress(quest), GAUGE_PROGRESS) + "</td><td width=8></td><td><font color=\"" + COLOR_DIM + "\">" + stageText + "</font></td></tr></table>";
			sb.append(card(BOARD_WIDTH, BG_CARD, top, bottom, button("quest " + quest.getId(), "Open", 60), 64)).append(spacer(2));
		}

		// The hidden quest under way and the offer a herald brought.
		final List<String> special = new ArrayList<>();
		if (HiddenQuestConfig.ENABLED)
		{
			final HiddenQuestSession hidden = HiddenQuestManager.getInstance().getSession(player);
			if (hidden != null)
			{
				special.add(card(HALF_WIDTH, BG_CARD, "<font color=\"LEVEL\">Hidden quest</font>", link("hidden", hidden.getDefinition().getName()) + (hidden.getTask() != null ? " <font color=\"" + COLOR_LABEL + "\">" + hidden.getTask().getProgress() + "</font>" : ""), null, 0));
			}
			else if (HiddenQuestManager.getInstance().getVisitingQuest(player) != null)
			{
				special.add(card(HALF_WIDTH, BG_CARD, "<font color=\"LEVEL\">Hidden quest</font>", link("hidden", "A messenger is looking for you"), null, 0));
			}
		}
		final QuestOffer personal = manager.getPersonalOffer(player);
		if (personal != null)
		{
			special.add(card(HALF_WIDTH, BG_PANEL, "<font color=\"LEVEL\">Offered to you</font>", link("offer " + personal.getKey(), getTitle(personal.getTemplate())), button("offer " + personal.getKey(), "View", 50), 54));
		}
		if (!special.isEmpty())
		{
			sb.append(spacer(8));
			appendGrid(sb, special);
		}

		// Today's board.
		if (town != null)
		{
			final List<String> offers = new ArrayList<>();
			final List<String> requests = new ArrayList<>();
			for (QuestOffer offer : manager.getBoardOffers(town))
			{
				if (!manager.isAvailable(offer))
				{
					continue;
				}
				final boolean taken = manager.isTaken(player, offer);
				final String title = taken ? "<font color=\"" + COLOR_LABEL + "\">" + getTitle(offer.getTemplate()) + "</font>" : link("offer " + offer.getKey(), getTitle(offer.getTemplate()));
				final String who = offer.getRequester() != null ? offer.getRequester() : offer.getTemplate().getCategory();
				if (offer.isRequest())
				{
					requests.add(card(BOARD_WIDTH, taken ? BG_CARD_DIM : BG_CARD, title, null, taken ? "<font color=\"" + COLOR_GOOD + "\">Taken</font>" : "<font color=\"" + COLOR_LABEL + "\">" + who + "</font>", 150));
					continue;
				}
				String line = title + " <font color=\"" + COLOR_LABEL + "\">" + who + "</font>";
				final QuestTrigger trigger = offer.getTemplate().getTrigger(TriggerType.BOARD_DAILY);
				if (!taken && (trigger != null) && "NIGHT".equals(trigger.getRequires()))
				{
					line += " <font color=\"" + COLOR_NIGHT + "\">tonight</font>";
				}
				offers.add(card(HALF_WIDTH, taken ? BG_CARD_DIM : BG_CARD, line, null, taken ? "<font color=\"" + COLOR_GOOD + "\">Taken</font>" : null, 44));
			}

			sb.append(spacer(8)).append(sectionHeader("Today in " + town, null));
			if (offers.isEmpty())
			{
				sb.append(note("Nothing today."));
			}
			appendGrid(sb, offers);

			if (!requests.isEmpty())
			{
				sb.append(spacer(8)).append(sectionHeader("Requests", "Taken at the Notice Board"));
				for (String request : requests)
				{
					sb.append(request).append(spacer(2));
				}
			}
		}

		// World events.
		final List<QuestInstance> world = manager.getWorldQuests();
		if (!world.isEmpty())
		{
			sb.append(spacer(8)).append(sectionHeader("World events", "Anyone nearby can join"));
			for (QuestInstance quest : world)
			{
				final String bottom = "<font color=\"" + COLOR_LABEL + "\">Near " + quest.getContext("near", "?") + ", level " + quest.getDifficulty().getQuestLevel() + "</font>";
				sb.append(card(BOARD_WIDTH, BG_CARD, link("quest " + quest.getId(), quest.getName()), bottom, button("track " + quest.getId(), "Track", 60), 64)).append(spacer(2));
			}
		}

		// Heralds.
		final boolean heralds = !player.getVariables().getBoolean(DynamicQuestManager.VAR_NO_HERALDS, false);
		final String state = "<font color=\"" + COLOR_DIM + "\">Heralds bring you quests while you hunt:</font> <font color=\"" + (heralds ? COLOR_GOOD : COLOR_BAD) + "\">" + (heralds ? "on" : "off") + "</font>";
		sb.append(spacer(8)).append(card(BOARD_WIDTH, BG_CARD_DIM, state, null, button("heralds " + (heralds ? "off" : "on"), heralds ? "Turn off" : "Turn on", 70), 74));
		return sb.toString();
	}

	private static String boardOffer(DynamicQuestManager manager, Player player, Npc npc, QuestOffer offer)
	{
		final QuestTemplate template = offer.getTemplate();
		final StringBuilder sb = new StringBuilder();
		sb.append(breadcrumb(getTitle(template)));
		sb.append(boardHeader(getTitle(template), template.getCategory() + (offer.getTown() != null ? " - " + offer.getTown() : ""), null, 0));

		final StringBuilder text = new StringBuilder();
		if (offer.getRequester() != null)
		{
			text.append("<font color=\"LEVEL\">").append(offer.getRequester()).append("</font> <font color=\"").append(COLOR_DIM).append("\">asks:</font><br1>");
		}
		text.append("<font color=\"").append(COLOR_TEXT).append("\">").append(fill(manager, template.getText("offer", "There is work to be done."), offer)).append("</font>");
		sb.append(spacer(6)).append(panel(BG_CARD, text.toString()));

		final List<Player> members = DynamicQuestManager.getMembers(player);
		final String problem = getProblem(manager, player, npc, offer, members);
		sb.append(spacer(8)).append(sectionHeader("Choose how hard", "Adjusts to you" + (members.size() > 1 ? " and " + (members.size() - 1) + " party members near you" : "")));

		final List<QuestTier> tiers = new ArrayList<>();
		for (QuestTier tier : QuestTier.values())
		{
			if ((offer.getTier() == null) || (offer.getTier() == tier))
			{
				tiers.add(tier);
			}
		}
		final int cardWidth = Math.min(200, (BOARD_WIDTH - ((tiers.size() - 1) * 6)) / tiers.size());
		sb.append("<table cellpadding=0 cellspacing=0><tr>");
		for (int i = 0; i < tiers.size(); i++)
		{
			final QuestTier tier = tiers.get(i);
			final DifficultyContext context = DifficultyContext.of(player, members, tier);
			final Payout preview = RewardCalculator.compute(template.getReward(), context.getQuestLevel(), tier.getRewardMultiplier() * (1 + ((offer.getChain() * DynamicQuestConfig.CHAIN_BONUS_PERCENT) / 100.0)), false);
			final int inner = cardWidth - 16;
			if (i > 0)
			{
				sb.append("<td width=6></td>");
			}
			sb.append("<td width=").append(cardWidth).append(" valign=top>");
			sb.append("<table width=").append(cardWidth).append(" cellpadding=0 cellspacing=0 bgcolor=").append(BG_CARD).append("><tr><td height=8 colspan=3></td></tr><tr><td width=8></td><td width=").append(inner).append(">");
			sb.append("<table width=").append(inner).append(" cellpadding=0 cellspacing=0>");
			sb.append("<tr><td><font color=\"").append(tier.getColor()).append("\">").append(tier.getDisplayName()).append("</font></td><td align=right><font color=\"").append(COLOR_DIM).append("\">Lv. ").append(context.getQuestLevel()).append("</font></td></tr>");
			sb.append("<tr><td height=6 colspan=2></td></tr>");
			sb.append("<tr><td colspan=2>").append(valueLine(String.format("%,d", preview._exp), "EXP")).append("</td></tr>");
			sb.append("<tr><td height=3 colspan=2></td></tr>");
			sb.append("<tr><td colspan=2>").append(valueLine(String.format("%,d", preview._adena), "Adena")).append("</td></tr>");
			if (problem == null)
			{
				sb.append("<tr><td height=8 colspan=2></td></tr>");
				sb.append("<tr><td colspan=2 align=center>").append(button("accept " + offer.getKey() + " " + tier.name(), "Accept", inner)).append("</td></tr>");
			}
			sb.append("</table></td><td width=8></td></tr><tr><td height=8 colspan=3></td></tr></table></td>");
		}
		sb.append("</tr></table>");

		if (problem != null)
		{
			sb.append(spacer(8)).append(panel(BG_CARD_DIM, "<font color=\"" + COLOR_BAD + "\">" + problem + "</font>"));
		}
		sb.append(spacer(8));
		sb.append(offer.isBoardOffer() ? button("main", "Back to the board", 140) : button("decline", "Not interested", 140));
		return sb.toString();
	}

	private static String boardQuest(Player player, Npc npc, QuestInstance quest)
	{
		final StringBuilder sb = new StringBuilder();
		sb.append(breadcrumb(quest.getName()));
		final QuestTier tier = quest.getDifficulty().getTier();
		final String subtitle = "<font color=\"" + tier.getColor() + "\">" + tier.getDisplayName() + "</font> <font color=\"" + COLOR_DIM + "\">- level " + quest.getDifficulty().getQuestLevel() + (quest.isWorld() ? ", near " + quest.getContext("near", "?") : ", party of " + quest.getDifficulty().getPartySize() + ", taken by " + (quest.getOwnerId() == player.getObjectId() ? "you" : quest.getOwnerName())) + "</font>";
		sb.append(boardHeader(quest.getName(), subtitle, null, 0));

		final StringBuilder actions = new StringBuilder();
		synchronized (quest)
		{
			final StageTemplate stage = quest.getStage();
			if (stage != null)
			{
				final int stages = quest.getTemplate().getStages().size();
				final int left = quest.getSecondsLeft();
				final StringBuilder body = new StringBuilder();
				body.append("<table width=").append(BOARD_WIDTH - 24).append(" cellpadding=0 cellspacing=0><tr><td><font color=\"LEVEL\">Stage ").append(quest.getStageIndex() + 1).append(" of ").append(stages).append(" - ").append(quest.format(stage.getName())).append("</font></td>");
				body.append("<td align=right><font color=\"").append(COLOR_NAME).append("\">").append(left >= 0 ? QuestSpawnUtil.formatTime(left) + " left" : "").append("</font></td></tr></table>");
				body.append(spacer(6)).append(stageGauge(quest, stages, BOARD_WIDTH - 24));
				if (stage.getText() != null)
				{
					body.append(spacer(8)).append("<font color=\"").append(COLOR_TEXT).append("\">").append(quest.format(stage.getText())).append("</font>");
				}

				boolean deliverable = false;
				for (AbstractObjective objective : quest.getObjectives())
				{
					body.append(spacer(8)).append(objectiveBlock(quest, objective, BOARD_WIDTH - 24));
					if ((objective instanceof DeliverObjective) && !objective.isDone())
					{
						deliverable = true;
					}
				}
				if ((left >= 0) && (stage.getTimeLimit() > 0))
				{
					body.append(spacer(10)).append("<font color=\"").append(COLOR_DIM).append("\">Time left in this stage: ").append(QuestSpawnUtil.formatTime(left)).append("</font>");
					body.append(spacer(3)).append(gauge(BOARD_WIDTH - 24, left / (double) stage.getTimeLimit(), GAUGE_TIME));
				}
				sb.append(spacer(8)).append(panel(BG_CARD, body.toString()));

				if (deliverable && (quest.getOwnerId() == player.getObjectId()))
				{
					if ((npc != null) && DynamicQuestData.getInstance().isNoticeBoard(npc.getId()))
					{
						for (AbstractObjective objective : quest.getObjectives())
						{
							if ((objective instanceof DeliverObjective) && !objective.isDone())
							{
								actions.append("<td>").append(button("act " + quest.getId() + " " + objective.getIndex() + " deliver", "Hand it over", 120)).append("</td><td width=6></td>");
							}
						}
					}
					else
					{
						sb.append(spacer(6)).append(note("Hand it over at any Notice Board."));
					}
				}
			}
		}

		sb.append(spacer(8)).append("<table width=").append(BOARD_WIDTH).append(" cellpadding=0 cellspacing=0><tr>");
		sb.append(actions);
		sb.append("<td>").append(button("track " + quest.getId(), "Track on radar", 120)).append("</td><td width=6></td>");
		sb.append("<td>").append(button("main", "Back", 80)).append("</td>");
		sb.append("<td align=right>").append(quest.getOwnerId() == player.getObjectId() ? button("abandon " + quest.getId(), "Abandon", 90) : "").append("</td>");
		sb.append("</tr></table>");
		return sb.toString();
	}

	/**
	 * @return an objective: its box, its line, its progress with a gauge when it counts something, and its bonus
	 */
	private static String objectiveBlock(QuestInstance quest, AbstractObjective objective, int width)
	{
		final String color = objective.isDone() ? COLOR_GOOD : (objective.isOptional() ? COLOR_MUTED : "FFFFFF");
		final String progress = getProgressLine(quest, objective);
		final double ratio = getRatio(objective, progress);
		final StringBuilder sb = new StringBuilder();
		sb.append("<table width=").append(width).append(" cellpadding=0 cellspacing=0 bgcolor=").append(BG_CARD_DIM).append(">");
		sb.append("<tr><td height=6 colspan=4></td></tr>");
		sb.append("<tr><td width=8></td><td width=22 valign=top><img src=\"").append(objective.isDone() ? "L2UI.CheckBox_checked" : "L2UI.CheckBox").append("\" width=14 height=14></td>");
		sb.append("<td width=").append(width - 38).append("><font color=\"").append(color).append("\">").append(objective.describe()).append(objective.isOptional() ? " (optional)" : "").append("</font></td><td width=8></td></tr>");
		sb.append("<tr><td></td><td></td><td><font color=\"").append(COLOR_DIM).append("\">").append(progress);
		final String bonus = objective.describeBonus();
		if (bonus != null)
		{
			sb.append(" - bonus if you ").append(bonus);
		}
		sb.append("</font></td><td></td></tr>");
		if ((ratio >= 0) && !objective.isDone())
		{
			sb.append("<tr><td height=4 colspan=4></td></tr><tr><td></td><td></td><td>").append(gauge(Math.min(260, width - 38), ratio, GAUGE_PROGRESS)).append("</td><td></td></tr>");
		}
		sb.append("<tr><td height=6 colspan=4></td></tr></table>");
		return sb.toString();
	}

	/**
	 * @return one gauge per stage: the stages done are full, the current one fills with its objectives
	 */
	private static String stageGauge(QuestInstance quest, int stages, int width)
	{
		if ((stages < 2) || (stages > 8))
		{
			return gauge(width, getProgress(quest), GAUGE_PROGRESS);
		}
		final int segment = (width - ((stages - 1) * 4)) / stages;
		final double current = getStageRatio(quest);
		final StringBuilder sb = new StringBuilder("<table cellpadding=0 cellspacing=0><tr>");
		for (int i = 0; i < stages; i++)
		{
			if (i > 0)
			{
				sb.append("<td width=4></td>");
			}
			final double ratio = i < quest.getStageIndex() ? 1 : (i == quest.getStageIndex() ? current : 0);
			sb.append("<td width=").append(segment).append(">").append(gauge(segment, ratio, GAUGE_PROGRESS)).append("</td>");
		}
		return sb.append("</tr></table>").toString();
	}

	/**
	 * @return how far the whole quest is, from 0 to 1: the stages done and the share of the current one
	 */
	private static double getProgress(QuestInstance quest)
	{
		synchronized (quest)
		{
			final int stages = Math.max(1, quest.getTemplate().getStages().size());
			return Math.min(1, (quest.getStageIndex() + getStageRatio(quest)) / stages);
		}
	}

	/**
	 * @return the share of the current stage's required objectives done, from 0 to 1
	 */
	private static double getStageRatio(QuestInstance quest)
	{
		int required = 0;
		double done = 0;
		for (AbstractObjective objective : quest.getObjectives())
		{
			if (!objective.isOptional())
			{
				required++;
				done += Math.max(0, getRatio(objective, getProgressLine(quest, objective)));
			}
		}
		return required > 0 ? done / required : 0;
	}

	private static String getProgressLine(QuestInstance quest, AbstractObjective objective)
	{
		final String progress = objective.getProgress();
		return progress != null ? quest.format(progress) : "";
	}

	/**
	 * @return the objective's count in its progress line as a ratio ("3/15"), 1 when it is done, -1 when it counts nothing
	 */
	private static double getRatio(AbstractObjective objective, String progress)
	{
		if (objective.isDone())
		{
			return 1;
		}
		final Matcher matcher = COUNT.matcher(progress);
		if (matcher.find())
		{
			final double total = Double.parseDouble(matcher.group(2));
			if (total > 0)
			{
				return Math.min(1, Double.parseDouble(matcher.group(1)) / total);
			}
		}
		return -1;
	}

	// ---------------------------------------------------------------------------------------------
	// Community Board building blocks
	// ---------------------------------------------------------------------------------------------

	private static String spacer(int height)
	{
		return "<table width=" + BOARD_WIDTH + " cellpadding=0 cellspacing=0><tr><td height=" + height + "></td></tr></table>";
	}

	private static String note(String text)
	{
		return "<table width=" + BOARD_WIDTH + " cellpadding=0 cellspacing=0><tr><td height=22><font color=\"" + COLOR_MUTED + "\">" + text + "</font></td></tr></table>";
	}

	/**
	 * @return the page's title box: title, a line under it and something on the right
	 */
	private static String boardHeader(String title, String subtitle, String right, int rightWidth)
	{
		final StringBuilder sb = new StringBuilder();
		sb.append("<table width=").append(BOARD_WIDTH).append(" cellpadding=0 cellspacing=0 bgcolor=").append(BG_PANEL).append(">");
		final int columns = right != null ? 4 : 3;
		sb.append("<tr><td height=8 colspan=").append(columns).append("></td></tr><tr><td width=10></td>");
		sb.append("<td width=").append(BOARD_WIDTH - 20 - rightWidth).append(" valign=top><font name=\"hs12\" color=\"").append(COLOR_TITLE).append("\">").append(title).append("</font>");
		if (subtitle != null)
		{
			sb.append("<br1><font color=\"").append(COLOR_DIM).append("\">").append(subtitle).append("</font>");
		}
		sb.append("</td>");
		if (right != null)
		{
			sb.append("<td width=").append(rightWidth).append(" align=right valign=top>").append(right).append("</td>");
		}
		sb.append("<td width=10></td></tr><tr><td height=8 colspan=").append(columns).append("></td></tr></table>");
		return sb.toString();
	}

	private static String chip(String label, int value, int max)
	{
		return "<table width=70 cellpadding=0 cellspacing=0 bgcolor=" + BG_CARD_DIM + "><tr><td height=4></td></tr><tr><td align=center><font color=\"" + COLOR_LABEL + "\">" + label + "</font></td></tr><tr><td align=center><font color=\"" + COLOR_NAME + "\">" + value + "</font><font color=\"" + COLOR_LABEL + "\"> / " + max + "</font></td></tr><tr><td height=4></td></tr></table>";
	}

	private static String breadcrumb(String current)
	{
		return "<table width=" + BOARD_WIDTH + " cellpadding=0 cellspacing=0><tr><td height=20>" + link("main", "Bounties &amp; Requests") + " <font color=\"" + COLOR_LABEL + "\">/</font> <font color=\"" + COLOR_NAME + "\">" + current + "</font></td></tr></table>";
	}

	private static String sectionHeader(String title, String note)
	{
		return "<table width=" + BOARD_WIDTH + " cellpadding=0 cellspacing=0><tr><td width=" + (BOARD_WIDTH - 260) + " height=18><font color=\"LEVEL\">" + title + "</font></td><td width=260 align=right><font color=\"" + COLOR_LABEL + "\">" + (note != null ? note : "") + "</font></td></tr></table><img src=\"L2UI.SquareGray\" width=" + BOARD_WIDTH + " height=1>" + spacer(5);
	}

	/**
	 * @return a box with a line or two of text and something on the right
	 */
	private static String card(int width, String bgcolor, String top, String bottom, String right, int rightWidth)
	{
		final int columns = right != null ? 4 : 3;
		final int textWidth = width - 16 - (right != null ? rightWidth : 0);
		final StringBuilder sb = new StringBuilder();
		sb.append("<table width=").append(width).append(" cellpadding=0 cellspacing=0 bgcolor=").append(bgcolor).append(">");
		sb.append("<tr><td height=4 colspan=").append(columns).append("></td></tr><tr><td width=8></td>");
		sb.append("<td width=").append(textWidth).append(" valign=top><table width=").append(textWidth).append(" cellpadding=0 cellspacing=0>");
		sb.append("<tr><td height=18>").append(top).append("</td></tr>");
		if (bottom != null)
		{
			sb.append("<tr><td height=16>").append(bottom).append("</td></tr>");
		}
		sb.append("</table></td>");
		if (right != null)
		{
			sb.append("<td width=").append(rightWidth).append(" align=right>").append(right).append("</td>");
		}
		sb.append("<td width=8></td></tr><tr><td height=4 colspan=").append(columns).append("></td></tr></table>");
		return sb.toString();
	}

	private static String panel(String bgcolor, String body)
	{
		return "<table width=" + BOARD_WIDTH + " cellpadding=0 cellspacing=0 bgcolor=" + bgcolor + "><tr><td height=10 colspan=3></td></tr><tr><td width=12></td><td width=" + (BOARD_WIDTH - 24) + ">" + body + "</td><td width=12></td></tr><tr><td height=10 colspan=3></td></tr></table>";
	}

	/**
	 * Lays half-width cards out two by two.
	 */
	private static void appendGrid(StringBuilder sb, List<String> cells)
	{
		for (int i = 0; i < cells.size(); i += 2)
		{
			sb.append("<table width=").append(BOARD_WIDTH).append(" cellpadding=0 cellspacing=0><tr><td width=").append(HALF_WIDTH).append(" valign=top>").append(cells.get(i)).append("</td><td width=6></td><td width=").append(HALF_WIDTH).append(" valign=top>");
			sb.append((i + 1) < cells.size() ? cells.get(i + 1) : "").append("</td></tr></table>").append(spacer(3));
		}
	}

	private static String valueLine(String value, String unit)
	{
		return "<font color=\"" + COLOR_VALUE + "\">" + value + "</font> <font color=\"" + COLOR_LABEL + "\">" + unit + "</font>";
	}

	/**
	 * @param width the gauge's width
	 * @param ratio how full it is, from 0 to 1
	 * @param kind the client's gauge: EXP, MP, HP...
	 * @return a thin gauge drawn with the client's own gauge textures
	 */
	private static String gauge(int width, double ratio, String kind)
	{
		final int fill = (int) Math.round(Math.max(0, Math.min(1, ratio)) * width);
		final String bar = fill > 0 ? "<img src=\"L2UI_CT1.Gauges.Gauge_DF_Large_" + kind + "_Center\" width=" + fill + " height=8>" : "<img src=\"L2UI.SquareBlank\" width=1 height=8>";
		return "<table width=" + width + " cellpadding=0 cellspacing=0><tr><td width=" + width + " height=8 background=\"L2UI_CT1.Gauges.Gauge_DF_Large_" + kind + "_bg_Center\">" + bar + "</td></tr></table>";
	}
}
