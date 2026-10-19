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

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.l2jmobius.commons.threads.ThreadPool;
import org.l2jmobius.commons.util.Rnd;
import org.l2jmobius.gameserver.ai.Intention;
import org.l2jmobius.gameserver.config.custom.DynamicQuestConfig;
import org.l2jmobius.gameserver.data.SpawnTable;
import org.l2jmobius.gameserver.data.sql.DynamicQuestTable;
import org.l2jmobius.gameserver.data.sql.DynamicQuestTable.QuestRow;
import org.l2jmobius.gameserver.data.sql.DynamicQuestTable.UsageRow;
import org.l2jmobius.gameserver.data.xml.DynamicQuestData;
import org.l2jmobius.gameserver.data.xml.DynamicQuestData.RequesterRole;
import org.l2jmobius.gameserver.data.xml.ItemData;
import org.l2jmobius.gameserver.data.xml.NpcData;
import org.l2jmobius.gameserver.model.Location;
import org.l2jmobius.gameserver.model.World;
import org.l2jmobius.gameserver.model.actor.Attackable;
import org.l2jmobius.gameserver.model.actor.Creature;
import org.l2jmobius.gameserver.model.actor.Npc;
import org.l2jmobius.gameserver.model.actor.Player;
import org.l2jmobius.gameserver.model.actor.templates.NpcTemplate;
import org.l2jmobius.gameserver.model.dynamicquest.DifficultyContext;
import org.l2jmobius.gameserver.model.dynamicquest.DynamicQuestState;
import org.l2jmobius.gameserver.model.dynamicquest.HuntingGround;
import org.l2jmobius.gameserver.model.dynamicquest.NoticeBoardSite;
import org.l2jmobius.gameserver.model.dynamicquest.ObjectiveTemplate;
import org.l2jmobius.gameserver.model.dynamicquest.QuestInstance;
import org.l2jmobius.gameserver.model.dynamicquest.QuestOffer;
import org.l2jmobius.gameserver.model.dynamicquest.QuestScope;
import org.l2jmobius.gameserver.model.dynamicquest.QuestTemplate;
import org.l2jmobius.gameserver.model.dynamicquest.QuestTier;
import org.l2jmobius.gameserver.model.dynamicquest.QuestTrigger;
import org.l2jmobius.gameserver.model.dynamicquest.RewardCalculator;
import org.l2jmobius.gameserver.model.dynamicquest.RewardCalculator.Payout;
import org.l2jmobius.gameserver.model.dynamicquest.StageTemplate;
import org.l2jmobius.gameserver.model.dynamicquest.Transition;
import org.l2jmobius.gameserver.model.dynamicquest.TransitionAction;
import org.l2jmobius.gameserver.model.dynamicquest.TransitionEvent;
import org.l2jmobius.gameserver.model.dynamicquest.TriggerType;
import org.l2jmobius.gameserver.model.dynamicquest.WorldMonsterIndex;
import org.l2jmobius.gameserver.model.dynamicquest.objective.AbstractObjective;
import org.l2jmobius.gameserver.model.dynamicquest.objective.ObjectiveFactory;
import org.l2jmobius.gameserver.model.events.Containers;
import org.l2jmobius.gameserver.model.events.EventType;
import org.l2jmobius.gameserver.model.events.holders.OnServerStart;
import org.l2jmobius.gameserver.model.events.holders.actor.creature.OnCreatureDeath;
import org.l2jmobius.gameserver.model.events.holders.actor.npc.OnNpcCanBeSeen;
import org.l2jmobius.gameserver.model.events.holders.actor.npc.attackable.OnAttackableKill;
import org.l2jmobius.gameserver.model.events.holders.actor.player.OnPlayerLevelChanged;
import org.l2jmobius.gameserver.model.events.holders.actor.player.OnPlayerLogin;
import org.l2jmobius.gameserver.model.events.holders.actor.player.OnPlayerLogout;
import org.l2jmobius.gameserver.model.events.holders.actor.player.OnPlayerPressTutorialMark;
import org.l2jmobius.gameserver.model.events.listeners.ConsumerEventListener;
import org.l2jmobius.gameserver.model.events.listeners.FunctionEventListener;
import org.l2jmobius.gameserver.model.events.returns.TerminateReturn;
import org.l2jmobius.gameserver.model.groups.Party;
import org.l2jmobius.gameserver.model.guide.GuideLesson;
import org.l2jmobius.gameserver.model.item.ItemTemplate;
import org.l2jmobius.gameserver.model.item.enums.ItemProcessType;
import org.l2jmobius.gameserver.model.script.Quest;
import org.l2jmobius.gameserver.model.siege.Castle;
import org.l2jmobius.gameserver.model.spawns.Spawn;
import org.l2jmobius.gameserver.model.zone.ZoneId;
import org.l2jmobius.gameserver.model.zone.type.TownZone;
import org.l2jmobius.gameserver.network.serverpackets.NpcHtmlMessage;
import org.l2jmobius.gameserver.network.serverpackets.TutorialShowQuestionMark;
import org.l2jmobius.gameserver.taskmanagers.GameTimeTaskManager;
import org.l2jmobius.gameserver.util.Broadcast;
import org.l2jmobius.gameserver.util.LocationUtil;
import org.l2jmobius.gameserver.util.QuestSpawnUtil;

/**
 * Dynamic Quests: procedurally generated quests built from {@code data/DynamicQuests.xml} (config/Custom/DynamicQuests.ini).
 * <p>
 * Templates declare triggers, eligibility, a sequence of stages made of reusable objectives, the transitions between them (success, failure, timeout, escalation, follow-up) and a reward formula. Each offer is rolled from a seed for the party that
 * takes it: its hunting ground and monsters come from the retail spawns at the party's level, its counts, champion tiers, meters and time limits from the party's size and the tier chosen.
 * </p>
 * <p>
 * Quests are offered on the Notice Board of every town (daily board offers and town requests), on the Community Board, by heralds who find players in the world (TIMER, WORLD_STATE and PLAYER_ACTION triggers) and as follow-ups. World quests
 * (outbreaks) belong to everyone near them.
 * </p>
 * @author Mobius
 */
public class DynamicQuestManager
{
	private static final Logger LOGGER = Logger.getLogger(DynamicQuestManager.class.getName());

	/** The tutorial question mark raised by heralds and follow-ups. */
	public static final int QUESTION_MARK_ID = 9320;

	/** Where the bypasses of a page go. */
	public enum Channel
	{
		NPC("bypass -h Script DynamicQuests "),
		BOARD("bypass _bbsquests "),
		WINDOW("bypass voiced_quests ");

		private final String _prefix;

		private Channel(String prefix)
		{
			_prefix = prefix;
		}

		public String getPrefix()
		{
			return _prefix;
		}
	}

	private static final int[] TIER_WEIGHTS =
	{
		25,
		45,
		22,
		8
	};
	private static final int REQUESTER_RANGE = 3500;
	private static final int AREA_CELL = 4096;
	private static final int HERALD_LEAVE_RANGE = 2500;
	/** Player variable: the character wants no unasked offers (heralds, finds). */
	public static final String VAR_NO_HERALDS = "DQ_NO_HERALDS";

	private final Map<Long, QuestInstance> _quests = new ConcurrentHashMap<>();
	private final Map<Integer, Set<QuestInstance>> _byPlayer = new ConcurrentHashMap<>();
	private final Map<Integer, QuestOffer> _personalOffers = new ConcurrentHashMap<>();
	private final Map<Integer, Integer> _heraldOwners = new ConcurrentHashMap<>();
	private final Map<Integer, Long> _nextOffer = new ConcurrentHashMap<>();
	private final Map<Integer, Set<Integer>> _turnedDown = new ConcurrentHashMap<>();
	private final Map<String, Map<Integer, UsageRow>> _usage = new ConcurrentHashMap<>();
	private final Map<String, Set<String>> _taken = new ConcurrentHashMap<>();
	private final Map<String, List<QuestOffer>> _boardOffers = new ConcurrentHashMap<>();
	private final Map<String, QuestOffer> _offerIndex = new ConcurrentHashMap<>();
	private final Map<String, List<Integer>> _townNpcs = new ConcurrentHashMap<>();
	private final Map<Long, List<Long>> _areaKills = new ConcurrentHashMap<>();
	private final List<Npc> _boardNpcs = new ArrayList<>();
	private final AtomicLong _nextId = new AtomicLong();
	private volatile int _offerDay = -1;
	private volatile int _usageDay = -1;
	private volatile long _rerollSalt;

	protected DynamicQuestManager()
	{
		if (!DynamicQuestConfig.ENABLED)
		{
			return;
		}

		DynamicQuestData.getInstance().validate();
		DynamicQuestTable.createTables();
		DynamicQuestTable.purgeTaken(getDay());
		_nextId.set(Math.max(System.currentTimeMillis(), DynamicQuestTable.getMaxInstanceId() + 1));

		Containers.Players().addListener(new ConsumerEventListener(Containers.Players(), EventType.ON_PLAYER_LOGIN, (OnPlayerLogin event) -> ThreadPool.schedule(() -> onLogin(event.getPlayer()), 3000), this));
		Containers.Players().addListener(new ConsumerEventListener(Containers.Players(), EventType.ON_PLAYER_LOGOUT, (OnPlayerLogout event) -> onLogout(event.getPlayer()), this));
		Containers.Players().addListener(new ConsumerEventListener(Containers.Players(), EventType.ON_CREATURE_DEATH, (OnCreatureDeath event) -> onPlayerDeath(event), this));
		Containers.Players().addListener(new ConsumerEventListener(Containers.Players(), EventType.ON_PLAYER_PRESS_TUTORIAL_MARK, (OnPlayerPressTutorialMark event) -> onQuestionMark(event.getPlayer(), event.getMarkId()), this));
		Containers.Players().addListener(new ConsumerEventListener(Containers.Players(), EventType.ON_PLAYER_LEVEL_CHANGED, (OnPlayerLevelChanged event) -> onLevelChanged(event), this));
		Containers.Monsters().addListener(new ConsumerEventListener(Containers.Monsters(), EventType.ON_ATTACKABLE_KILL, (OnAttackableKill event) -> onMonsterKill(event), this));
		Containers.Global().addListener(new ConsumerEventListener(Containers.Global(), EventType.ON_SERVER_START, (OnServerStart event) -> ThreadPool.schedule(this::spawnBoards, 5000), this));

		// Heralds are seen only by the player they came for (and GMs).
		final NpcTemplate herald = NpcData.getInstance().getTemplate(DynamicQuestData.getInstance().getHeraldNpcId());
		if (herald != null)
		{
			herald.addListener(new FunctionEventListener(herald, EventType.ON_NPC_CAN_BE_SEEN, (OnNpcCanBeSeen event) -> new TerminateReturn(canSeeHerald(event.getNpc(), event.getPlayer()), false, false), this));
		}

		ThreadPool.scheduleAtFixedRate(this::tickAll, 1000, 1000);
		ThreadPool.scheduleAtFixedRate(this::runDirector, DynamicQuestConfig.EVENT_INTERVAL * 1000L, DynamicQuestConfig.EVENT_INTERVAL * 1000L);
		ThreadPool.scheduleAtFixedRate(this::saveDirty, 60000, 60000);
		LOGGER.info(getClass().getSimpleName() + ": Dynamic quests are enabled (" + DynamicQuestData.getInstance().getTemplates().size() + " templates).");
	}

	// ---------------------------------------------------------------------------------------------
	// Days, usage and cooldowns
	// ---------------------------------------------------------------------------------------------

	/**
	 * @return today's number; a day starts at DynamicQuestBoardResetHour
	 */
	public static int getDay()
	{
		return (int) LocalDateTime.now().minusHours(DynamicQuestConfig.BOARD_RESET_HOUR).toLocalDate().toEpochDay();
	}

	private void checkDay()
	{
		final int day = getDay();
		if (_usageDay != day)
		{
			_usageDay = day;
			_taken.clear();
			_turnedDown.clear();
		}
	}

	private Map<Integer, UsageRow> getUsage(String account)
	{
		checkDay();
		return _usage.computeIfAbsent(account, DynamicQuestTable::loadUsage);
	}

	private Set<String> getTaken(String account)
	{
		checkDay();
		return _taken.computeIfAbsent(account, key ->
		{
			final Set<String> taken = ConcurrentHashMap.newKeySet();
			taken.addAll(DynamicQuestTable.loadTaken(key, getDay()));
			return taken;
		});
	}

	/**
	 * @param account an account
	 * @param templateId a template
	 * @return how many times the account completed the template today
	 */
	public int getDailyCount(String account, int templateId)
	{
		final UsageRow row = getUsage(account).get(templateId);
		return (row != null) && (row._day == getDay()) ? row._dailyCount : 0;
	}

	/**
	 * @param account an account
	 * @return how many dynamic quests the account completed today
	 */
	public int getAccountDailyTotal(String account)
	{
		final int day = getDay();
		int total = 0;
		for (UsageRow row : getUsage(account).values())
		{
			if (row._day == day)
			{
				total += row._dailyCount;
			}
		}
		return total;
	}

	private long getCooldownUntil(String account, int templateId)
	{
		final UsageRow row = getUsage(account).get(templateId);
		return row != null ? row._cooldownUntil : 0;
	}

	private void recordCompletion(String account, QuestTemplate template)
	{
		final int day = getDay();
		final UsageRow row = getUsage(account).computeIfAbsent(template.getId(), key -> new UsageRow());
		if (row._day != day)
		{
			row._day = day;
			row._dailyCount = 0;
		}
		row._dailyCount++;
		if (template.getEligibility().getCooldown() > 0)
		{
			row._cooldownUntil = System.currentTimeMillis() + (template.getEligibility().getCooldown() * 1000L);
		}
		ThreadPool.execute(() -> DynamicQuestTable.saveUsage(account, template.getId(), row));
	}

	/**
	 * @param player a player
	 * @param template a template
	 * @return {@code null} if the player's account may take it now, else why not
	 */
	private String checkLimits(Player player, QuestTemplate template)
	{
		final String account = player.getAccountName();
		if (getAccountDailyTotal(account) >= DynamicQuestConfig.ACCOUNT_DAILY_LIMIT)
		{
			return "Your account has done its " + DynamicQuestConfig.ACCOUNT_DAILY_LIMIT + " dynamic quests for today.";
		}
		final int dailyLimit = template.getEligibility().getDailyLimit();
		if ((dailyLimit > 0) && (getDailyCount(account, template.getId()) >= dailyLimit))
		{
			return "Your account has done this quest " + dailyLimit + " times today.";
		}
		final long cooldown = getCooldownUntil(account, template.getId());
		if (cooldown > System.currentTimeMillis())
		{
			return "Come back for this one in " + (((cooldown - System.currentTimeMillis()) / 60000) + 1) + " minutes.";
		}
		return null;
	}

	// ---------------------------------------------------------------------------------------------
	// Notice boards and daily offers
	// ---------------------------------------------------------------------------------------------

	private void spawnBoards()
	{
		for (NoticeBoardSite site : DynamicQuestData.getInstance().getBoards())
		{
			final Spawn spawn = SpawnTable.getInstance().getAnySpawn(site.getAnchorNpcId());
			if (spawn == null)
			{
				LOGGER.warning(getClass().getSimpleName() + ": No spawn of npc " + site.getAnchorNpcId() + " for the Notice Board of " + site.getTown() + ".");
				continue;
			}
			Location anchor = spawn.getLastSpawn() != null ? spawn.getLastSpawn().getLocation() : new Location(spawn.getX(), spawn.getY(), spawn.getZ());
			if ((anchor.getX() == 0) && (anchor.getY() == 0))
			{
				continue;
			}
			final Location location = QuestSpawnUtil.findPoint(anchor, 70, 140, true, 0);
			site.setLocation(location);
			if (DynamicQuestConfig.NOTICE_BOARDS)
			{
				final Npc npc = Quest.addSpawn(DynamicQuestData.getInstance().getNoticeBoardNpcId(), location.getX(), location.getY(), location.getZ(), LocationUtil.calculateHeadingFrom(location, anchor), false, 0, false, 0);
				if (npc != null)
				{
					npc.setRandomWalking(false);
					_boardNpcs.add(npc);
				}
			}
		}
		LOGGER.info(getClass().getSimpleName() + ": " + _boardNpcs.size() + " Notice Boards placed.");
	}

	/**
	 * @param npc a Notice Board
	 * @return its town, {@code null} if it is none
	 */
	public String getBoardTown(Npc npc)
	{
		if ((npc == null) || !DynamicQuestData.getInstance().isNoticeBoard(npc.getId()))
		{
			return null;
		}
		final NoticeBoardSite site = DynamicQuestData.getInstance().getNearestBoard(npc, 0);
		return site != null ? site.getTown() : null;
	}

	/**
	 * @param player a player
	 * @return the town of the nearest Notice Board
	 */
	public String getNearestTown(Player player)
	{
		final NoticeBoardSite site = DynamicQuestData.getInstance().getNearestBoard(player, 0);
		if (site != null)
		{
			return site.getTown();
		}
		final List<NoticeBoardSite> boards = DynamicQuestData.getInstance().getBoards();
		return boards.isEmpty() ? null : boards.get(0).getTown();
	}

	/**
	 * @param town a town
	 * @return today's offers of its board: board offers first, then requests
	 */
	public List<QuestOffer> getBoardOffers(String town)
	{
		if (town == null)
		{
			return Collections.emptyList();
		}
		final int day = getDay();
		if (_offerDay != day)
		{
			_offerDay = day;
			_boardOffers.clear();
			_offerIndex.clear();
		}
		return _boardOffers.computeIfAbsent(town, key -> rollOffers(town, day));
	}

	private List<QuestOffer> rollOffers(String town, int day)
	{
		final List<NoticeBoardSite> boards = DynamicQuestData.getInstance().getBoards();
		int townIndex = 0;
		for (int i = 0; i < boards.size(); i++)
		{
			if (boards.get(i).getTown().equals(town))
			{
				townIndex = i;
			}
		}

		final Random random = new Random((day * 1000003L) + (town.hashCode() * 31L) + _rerollSalt);
		final List<QuestOffer> offers = new ArrayList<>();
		final List<QuestTemplate> board = new ArrayList<>();
		final List<QuestTemplate> requests = new ArrayList<>();
		final List<QuestTemplate> night = new ArrayList<>();
		for (QuestTemplate template : DynamicQuestData.getInstance().getTemplates())
		{
			if ((template.getScope() != QuestScope.PERSONAL) || !template.getEligibility().isForTown(town))
			{
				continue;
			}
			final QuestTrigger boardTrigger = template.getTrigger(TriggerType.BOARD_DAILY);
			if (boardTrigger != null)
			{
				if ("NIGHT".equals(boardTrigger.getRequires()))
				{
					night.add(template);
				}
				else if (isTownConditionMet(boardTrigger, town))
				{
					board.add(template);
				}
			}
			final QuestTrigger requestTrigger = template.getTrigger(TriggerType.NPC_INTERACTION);
			if ((requestTrigger != null) && (template.getRequester() != null) && isTownConditionMet(requestTrigger, town) && hasRequester(town, template.getRequester()))
			{
				requests.add(template);
			}
		}

		int slot = 0;
		for (QuestTemplate template : pickWeighted(board, TriggerType.BOARD_DAILY, DynamicQuestConfig.BOARD_OFFERS, random))
		{
			offers.add(new QuestOffer("B" + day + "-" + townIndex + "-" + slot++, template, town, random.nextLong(), null, null, 0, null));
		}
		// One offer of the night: shown only while it is night.
		for (QuestTemplate template : pickWeighted(night, TriggerType.BOARD_DAILY, 1, random))
		{
			offers.add(new QuestOffer("B" + day + "-" + townIndex + "-" + slot++, template, town, random.nextLong(), null, null, 0, null));
		}
		for (QuestTemplate template : pickWeighted(requests, TriggerType.NPC_INTERACTION, DynamicQuestConfig.REQUEST_OFFERS, random))
		{
			offers.add(new QuestOffer("R" + day + "-" + townIndex + "-" + slot++, template, town, random.nextLong(), findRequester(town, template.getRequester(), random), null, 0, null));
		}
		for (QuestOffer offer : offers)
		{
			_offerIndex.put(offer.getKey(), offer);
		}
		return Collections.unmodifiableList(offers);
	}

	/**
	 * @return {@code true} while it is night (the Night Cycle's night when it runs, else the game clock's)
	 */
	public static boolean isNightNow()
	{
		return NightCycleManager.isStarted() ? NightCycleManager.getInstance().isNight() : GameTimeTaskManager.getInstance().isNight();
	}

	/**
	 * @param trigger a trigger
	 * @param town the town it would be offered in, may be {@code null}
	 * @return {@code true} if the world is as the trigger requires (NIGHT: night; CASTLE_LORD: the town's castle has a lord)
	 */
	public boolean isTownConditionMet(QuestTrigger trigger, String town)
	{
		final String requires = trigger.getRequires();
		if (requires == null)
		{
			return true;
		}
		switch (requires)
		{
			case "NIGHT":
			{
				return isNightNow();
			}
			case "CASTLE_LORD":
			{
				final Castle castle = getTownCastle(town);
				return (castle != null) && (castle.getOwnerId() > 0) && (castle.getOwner() != null);
			}
			default:
			{
				return true;
			}
		}
	}

	/**
	 * @param offer a board offer
	 * @return {@code true} if it can be seen and taken now (an offer of the night only at night)
	 */
	public boolean isAvailable(QuestOffer offer)
	{
		final QuestTrigger trigger = offer.getTemplate().getTrigger(offer.isRequest() ? TriggerType.NPC_INTERACTION : TriggerType.BOARD_DAILY);
		return (trigger == null) || !"NIGHT".equals(trigger.getRequires()) || isNightNow();
	}

	/**
	 * @param town a town with a Notice Board
	 * @return the castle whose lands the town is in, {@code null} if none
	 */
	public Castle getTownCastle(String town)
	{
		final NoticeBoardSite site = town != null ? DynamicQuestData.getInstance().getBoard(town) : null;
		if ((site == null) || (site.getLocation() == null))
		{
			return null;
		}
		final Location location = site.getLocation();
		final TownZone zone = TownManager.getTown(location.getX(), location.getY(), location.getZ());
		if (zone == null)
		{
			return null;
		}
		final int index = CastleManager.getInstance().getCastleIndex(zone.getTaxById());
		return (index >= 0) && (index < CastleManager.getInstance().getCastles().size()) ? CastleManager.getInstance().getCastles().get(index) : null;
	}

	private boolean hasRequester(String town, String roleName)
	{
		final RequesterRole role = DynamicQuestData.getInstance().getRequester(roleName);
		if ((role == null) || !role.isRequired())
		{
			return true;
		}
		for (int npcId : getTownNpcs(town))
		{
			final NpcTemplate template = NpcData.getInstance().getTemplate(npcId);
			if ((template != null) && role.matches(template.getTitle(), template.getType()))
			{
				return true;
			}
		}
		return false;
	}

	/**
	 * Picks templates by weight; a template comes again only once every other one was picked.
	 */
	private static List<QuestTemplate> pickWeighted(List<QuestTemplate> templates, TriggerType type, int amount, Random random)
	{
		final List<QuestTemplate> result = new ArrayList<>();
		final List<QuestTemplate> pool = new ArrayList<>();
		while ((result.size() < amount) && !templates.isEmpty())
		{
			if (pool.isEmpty())
			{
				pool.addAll(templates);
			}
			int total = 0;
			for (QuestTemplate template : pool)
			{
				total += template.getTrigger(type).getWeight();
			}
			int roll = random.nextInt(Math.max(1, total));
			for (QuestTemplate template : pool)
			{
				roll -= template.getTrigger(type).getWeight();
				if (roll < 0)
				{
					result.add(template);
					pool.remove(template);
					break;
				}
			}
		}
		return result;
	}

	private String findRequester(String town, String roleName, Random random)
	{
		final RequesterRole role = DynamicQuestData.getInstance().getRequester(roleName);
		if (role == null)
		{
			return null;
		}
		final List<String> names = new ArrayList<>();
		for (int npcId : getTownNpcs(town))
		{
			final NpcTemplate template = NpcData.getInstance().getTemplate(npcId);
			if ((template != null) && role.matches(template.getTitle(), template.getType()))
			{
				names.add(template.getName());
			}
		}
		Collections.sort(names);
		String name;
		if (!names.isEmpty())
		{
			name = names.get(random.nextInt(names.size()));
		}
		else
		{
			final List<String> pool = DynamicQuestData.getInstance().getTextPool("townsfolk");
			name = (pool == null) || pool.isEmpty() ? "a townsman" : pool.get(random.nextInt(pool.size()));
		}
		return role.getLabel().isEmpty() ? name : name + ", " + role.getLabel();
	}

	private List<Integer> getTownNpcs(String town)
	{
		return _townNpcs.computeIfAbsent(town, key ->
		{
			final List<Integer> npcIds = new ArrayList<>();
			final NoticeBoardSite site = DynamicQuestData.getInstance().getBoard(town);
			if ((site == null) || (site.getLocation() == null))
			{
				return npcIds;
			}
			final long rangeSq = (long) REQUESTER_RANGE * REQUESTER_RANGE;
			for (Map.Entry<Integer, Set<Spawn>> entry : SpawnTable.getInstance().getSpawnTable().entrySet())
			{
				final NpcTemplate template = NpcData.getInstance().getTemplate(entry.getKey());
				if ((template == null) || template.isType("Monster") || template.isFakePlayer() || (template.getId() >= 900000))
				{
					continue;
				}
				for (Spawn spawn : entry.getValue())
				{
					final long dx = spawn.getX() - site.getLocation().getX();
					final long dy = spawn.getY() - site.getLocation().getY();
					if ((spawn.getInstanceId() == 0) && (((dx * dx) + (dy * dy)) <= rangeSq))
					{
						npcIds.add(template.getId());
						break;
					}
				}
			}
			return npcIds;
		});
	}

	/**
	 * @param key an offer key
	 * @return today's board offer, or the player's personal offer, with that key; {@code null} if none
	 */
	public QuestOffer getOffer(Player player, String key)
	{
		final QuestOffer personal = _personalOffers.get(player.getObjectId());
		if ((personal != null) && personal.getKey().equals(key))
		{
			return personal;
		}
		// Make sure the offers of the key's town are rolled.
		final String[] split = key.substring(1).split("-");
		if (split.length == 3)
		{
			try
			{
				final int townIndex = Integer.parseInt(split[1]);
				final List<NoticeBoardSite> boards = DynamicQuestData.getInstance().getBoards();
				if ((townIndex >= 0) && (townIndex < boards.size()))
				{
					getBoardOffers(boards.get(townIndex).getTown());
				}
			}
			catch (NumberFormatException e)
			{
				return null;
			}
		}
		return _offerIndex.get(key);
	}

	/**
	 * @param player a player
	 * @param offer an offer
	 * @return {@code true} if the player's account already took it today
	 */
	public boolean isTaken(Player player, QuestOffer offer)
	{
		return offer.isBoardOffer() && getTaken(player.getAccountName()).contains(offer.getKey());
	}

	/**
	 * @return the player's herald or follow-up offer, {@code null} if none
	 */
	public QuestOffer getPersonalOffer(Player player)
	{
		return _personalOffers.get(player.getObjectId());
	}

	// ---------------------------------------------------------------------------------------------
	// Taking a quest
	// ---------------------------------------------------------------------------------------------

	/**
	 * @param player a player
	 * @return the player and its party members near it
	 */
	public static List<Player> getMembers(Player player)
	{
		final List<Player> members = new ArrayList<>();
		members.add(player);
		final Party party = player.getParty();
		if (party != null)
		{
			for (Player member : party.getMembers())
			{
				if ((member != player) && member.isOnline() && (member.calculateDistance2D(player) <= DynamicQuestConfig.PARTY_RANGE))
				{
					members.add(member);
				}
			}
		}
		return members;
	}

	/**
	 * @param player the player
	 * @return the personal quests it owns
	 */
	public List<QuestInstance> getOwnedQuests(Player player)
	{
		final List<QuestInstance> result = new ArrayList<>();
		for (QuestInstance quest : getQuests(player))
		{
			if (quest.getOwnerId() == player.getObjectId())
			{
				result.add(quest);
			}
		}
		return result;
	}

	/**
	 * @param player a player
	 * @return the quests it takes part in
	 */
	public List<QuestInstance> getQuests(Player player)
	{
		final Set<QuestInstance> quests = _byPlayer.get(player.getObjectId());
		if (quests == null)
		{
			return Collections.emptyList();
		}
		final List<QuestInstance> result = new ArrayList<>();
		for (QuestInstance quest : quests)
		{
			if (quest.isActive())
			{
				result.add(quest);
			}
		}
		return result;
	}

	/**
	 * @return the world quests running
	 */
	public List<QuestInstance> getWorldQuests()
	{
		final List<QuestInstance> result = new ArrayList<>();
		for (QuestInstance quest : _quests.values())
		{
			if (quest.isWorld() && quest.isActive())
			{
				result.add(quest);
			}
		}
		return result;
	}

	public QuestInstance getQuest(long id)
	{
		return _quests.get(id);
	}

	public Collection<QuestInstance> getAllQuests()
	{
		return _quests.values();
	}

	private String checkCanTake(Player player)
	{
		if (!DynamicQuestConfig.ENABLED)
		{
			return "Dynamic quests are disabled.";
		}
		if (player.getLevel() < DynamicQuestConfig.MIN_LEVEL)
		{
			return "Come back when you are level " + DynamicQuestConfig.MIN_LEVEL + ".";
		}
		if (player.isDead() || player.isInOlympiadMode() || (player.getInstanceId() != 0) || player.isOnEvent())
		{
			return "Not now.";
		}
		if (getOwnedQuests(player).size() >= DynamicQuestConfig.MAX_ACTIVE)
		{
			return "You already carry " + DynamicQuestConfig.MAX_ACTIVE + " quests. Finish or abandon one first.";
		}
		return null;
	}

	/**
	 * Takes a board offer, a town request or a personal offer.
	 * @param player the player
	 * @param npc the npc talked to ({@code null} from the Community Board)
	 * @param offer the offer
	 * @param chosenTier the tier chosen (a personal offer keeps its own)
	 * @return the new quest's id as text, or why it could not start (prefixed with "!")
	 */
	public String accept(Player player, Npc npc, QuestOffer offer, QuestTier chosenTier)
	{
		String problem = checkCanTake(player);
		if (problem == null)
		{
			problem = checkLimits(player, offer.getTemplate());
		}
		if ((problem == null) && isTaken(player, offer))
		{
			problem = "Your account already took this one today.";
		}
		if ((problem == null) && offer.isBoardOffer() && !isAvailable(offer))
		{
			problem = "This one is only taken at night.";
		}
		if ((problem == null) && offer.isRequest())
		{
			final String town = getBoardTown(npc);
			if ((town == null) || !town.equals(offer.getTown()))
			{
				problem = "Requests are taken at the Notice Board of " + offer.getTown() + ".";
			}
		}
		final List<Player> members = getMembers(player);
		if (problem == null)
		{
			problem = offer.getTemplate().getEligibility().test(player, members);
		}
		if (problem != null)
		{
			return "!" + problem;
		}

		final QuestTier tier = offer.getTier() != null ? offer.getTier() : chosenTier;
		final long seed = offer.getSeed() ^ ((long) player.getAccountName().hashCode() << 20);
		final QuestInstance quest = new QuestInstance(_nextId.incrementAndGet(), offer.getTemplate(), seed, DifficultyContext.of(player, members, tier), player.getObjectId(), player.getName(), player.getAccountName(), offer.isBoardOffer() ? offer.getKey() : "", System.currentTimeMillis());
		quest.setChain(offer.getChain());
		for (Map.Entry<String, String> entry : offer.getCarry().entrySet())
		{
			quest.putContext(entry.getKey(), entry.getValue());
		}
		if (offer.getTown() != null)
		{
			quest.putContext("town", offer.getTown());
		}
		if (offer.getRequester() != null)
		{
			quest.putContext("requester", offer.getRequester());
		}
		else if (offer.getTemplate().getRequester() != null)
		{
			// A request brought by a herald or a GM: someone of the nearest town asks.
			final String town = getNearestTown(player);
			if (town != null)
			{
				quest.putContext("requester", findRequester(town, offer.getTemplate().getRequester(), quest.random("requester")));
			}
		}
		fillContext(quest);
		if (offer.getTemplate().getReward().getTreasuryPercent() > 0)
		{
			final Castle castle = getTownCastle(offer.getTown() != null ? offer.getTown() : getNearestTown(player));
			if ((castle != null) && (castle.getOwner() != null))
			{
				quest.putContext("castleId", String.valueOf(castle.getResidenceId()));
				quest.putContext("castle", castle.getName());
				quest.putContext("lord", castle.getOwner().getName());
			}
		}
		if (quest.getArea() == null)
		{
			final HuntingGround ground = WorldMonsterIndex.getInstance().pick(quest.getDifficulty().getQuestLevel(), quest.random("area"), player, offer.isBoardOffer() ? 25000 : 6000);
			if (ground == null)
			{
				return "!There is nowhere to send you.";
			}
			quest.setArea(ground);
		}
		for (Player member : members)
		{
			quest.addParticipant(member.getObjectId());
		}

		_quests.put(quest.getId(), quest);
		for (int objectId : quest.getParticipants())
		{
			_byPlayer.computeIfAbsent(objectId, key -> ConcurrentHashMap.newKeySet()).add(quest);
		}

		final List<Integer> entries = quest.getTemplate().getEntryStages();
		final int entry = entries.isEmpty() ? 0 : entries.get(quest.random("entry").nextInt(entries.size()));
		synchronized (quest)
		{
			startStage(quest, entry, null);
		}
		if (!quest.isActive())
		{
			return "!" + quest.format(quest.getTemplate().getText("failStart", "The trail is cold. Try another quest."));
		}

		if (offer.isBoardOffer())
		{
			getTaken(player.getAccountName()).add(offer.getKey());
			ThreadPool.execute(() -> DynamicQuestTable.saveTaken(player.getAccountName(), offer.getKey(), getDay()));
		}
		else
		{
			offer.setAccepted(true);
			dismissPersonalOffer(player, null);
		}
		save(quest);

		for (Player member : members)
		{
			if (member != player)
			{
				member.sendMessage(player.getName() + " took the quest " + quest.getName() + ". You take part while you stay near.");
			}
		}
		AdventurersGuideManager.getInstance().trigger(player, GuideLesson.QUEST_TAKEN, "You have taken the quest " + quest.getName() + ".");
		log(player.getName() + " took " + quest.getName() + " (template " + quest.getTemplate().getId() + ", " + tier + ", level " + quest.getDifficulty().getQuestLevel() + ", party " + quest.getDifficulty().getPartySize() + ").");
		return String.valueOf(quest.getId());
	}

	/**
	 * Fills the template's {@code <context key pool>} picks from the quest's seed.
	 */
	private static void fillContext(QuestInstance quest)
	{
		final String prefix = "context.";
		for (String key : getContextKeys(quest.getTemplate()))
		{
			if (quest.getContext(key) != null)
			{
				continue;
			}
			final List<String> pool = DynamicQuestData.getInstance().getTextPool(quest.getTemplate().getText(prefix + key, ""));
			if ((pool != null) && !pool.isEmpty())
			{
				quest.putContext(key, quest.format(pool.get(quest.random(prefix + key).nextInt(pool.size()))));
			}
		}
	}

	private static List<String> getContextKeys(QuestTemplate template)
	{
		final List<String> keys = new ArrayList<>();
		for (String key : new String[]
		{
			"target",
			"hostage",
			"leader",
			"gang",
			"caravan",
			"expedition",
			"item",
			"cargo",
			"captain",
			"beast"
		})
		{
			if (template.getText("context." + key, null) != null)
			{
				keys.add(key);
			}
		}
		return keys;
	}

	private boolean isWorldQuestRunning(QuestTemplate template)
	{
		for (QuestInstance running : getWorldQuests())
		{
			if (running.getTemplate() == template)
			{
				return true;
			}
		}
		return false;
	}

	/**
	 * Starts a world quest around a player's hunting ground.
	 * @param template the template
	 * @param near the player it starts near
	 * @return {@code null} if it started, else why not
	 */
	public String startWorldQuest(QuestTemplate template, Player near)
	{
		if (isWorldQuestRunning(template))
		{
			return "one is already running";
		}
		final Random random = new Random(Rnd.nextLong());
		final HuntingGround ground = WorldMonsterIndex.getInstance().pick(near.getLevel(), random, near, 3000);
		if (ground == null)
		{
			return "no hunting ground";
		}
		final int players = QuestInstance.playersNear(ground.getCenter(), DynamicQuestConfig.WORLD_EVENT_RANGE).size();
		final QuestInstance quest = new QuestInstance(_nextId.incrementAndGet(), template, random.nextLong(), new DifficultyContext(DifficultyContext.clampLevel(ground.getLevel()), Math.max(1, players), QuestTier.NORMAL), 0, null, null, "", System.currentTimeMillis());
		quest.setArea(ground);
		fillContext(quest);
		_quests.put(quest.getId(), quest);
		synchronized (quest)
		{
			startStage(quest, 0, null);
		}
		if (!quest.isActive())
		{
			return "it could not start";
		}

		final String announcement = quest.format(template.getText("worldAnnounce", "{name} near {near}!").replace("{name}", quest.getName()));
		if (DynamicQuestConfig.ANNOUNCE_WORLD_EVENTS)
		{
			Broadcast.toAllOnlinePlayers(announcement);
		}
		quest.announce(announcement);
		log("World quest " + quest.getName() + " started near " + ground.getTown() + " (level " + ground.getLevel() + ").");
		return null;
	}

	// ---------------------------------------------------------------------------------------------
	// Stages and transitions
	// ---------------------------------------------------------------------------------------------

	/**
	 * Starts a stage. Called with the quest's lock held.
	 * @param quest the quest
	 * @param index the stage
	 * @param states the objectives' saved states when the quest is restored, {@code null} for a fresh stage
	 */
	private void startStage(QuestInstance quest, int index, String[] states)
	{
		final StageTemplate stage = quest.getTemplate().getStages().get(index);
		quest.setStageIndex(index);
		quest.getObjectives().clear();
		quest.setStageStart(System.currentTimeMillis());
		if (states == null)
		{
			quest.setEscalations(0);
			final int limit = quest.getDifficulty().timeLimit(stage.getTimeLimit());
			quest.setStageDeadline(limit > 0 ? System.currentTimeMillis() + (limit * 1000L) : 0);
		}

		final List<ObjectiveTemplate> templates = stage.getObjectives();
		for (int i = 0; i < templates.size(); i++)
		{
			final AbstractObjective objective = ObjectiveFactory.create(quest, templates.get(i), i);
			if ((states != null) && (i < states.length))
			{
				final String state = states[i];
				final int colon = state.indexOf(':');
				if (colon > 0)
				{
					if (state.charAt(0) == '1')
					{
						objective.setDone();
					}
					objective.loadState(state.substring(colon + 1));
				}
			}
			quest.getObjectives().add(objective);
		}

		quest.setStageEnding(true);
		try
		{
			for (AbstractObjective objective : quest.getObjectives())
			{
				if (objective.isDone())
				{
					continue;
				}
				String problem;
				try
				{
					problem = objective.start(states != null);
				}
				catch (Exception e)
				{
					LOGGER.log(Level.WARNING, getClass().getSimpleName() + ": Could not start an objective of " + quest.getName() + ".", e);
					problem = "error";
				}
				if ((problem != null) && !objective.isOptional())
				{
					log(quest.getName() + " could not start stage " + (index + 1) + ": " + problem + ".");
					quest.setStageEnding(false);
					finish(quest, DynamicQuestState.FAILED, null);
					return;
				}
			}
		}
		finally
		{
			quest.setStageEnding(false);
		}

		if (states == null)
		{
			if (stage.getText() != null)
			{
				quest.message(stage.getText());
				quest.announce(stage.getText());
			}
			else if (index > 0)
			{
				quest.announce(stage.getName());
			}
		}
		quest.setDirty(true);
		quest.setNeedsCheck(true);
	}

	/**
	 * An objective changed; the stage is checked at the end of the current event.
	 * @param quest the quest
	 */
	public void onObjectiveChanged(QuestInstance quest)
	{
		quest.setNeedsCheck(true);
		quest.setDirty(true);
	}

	/**
	 * Checks whether the stage ended. Called with the quest's lock held.
	 */
	private void checkStage(QuestInstance quest)
	{
		quest.setNeedsCheck(false);
		if (!quest.isActive() || quest.isStageEnding())
		{
			return;
		}

		boolean allDone = true;
		for (AbstractObjective objective : quest.getObjectives())
		{
			if (objective.isOptional())
			{
				continue;
			}
			if (objective.isFailed())
			{
				applyTransition(quest, TransitionEvent.FAILURE, objective.getFailReason());
				return;
			}
			if (!objective.isDone())
			{
				allDone = false;
			}
		}
		if (allDone)
		{
			applyTransition(quest, TransitionEvent.SUCCESS, null);
		}
	}

	private void applyTransition(QuestInstance quest, TransitionEvent event, String reason)
	{
		final StageTemplate stage = quest.getStage();
		Transition chosen = null;
		final Random random = quest.random("transition" + event + quest.getEscalations());
		for (Transition transition : stage.getTransitions())
		{
			if ((transition.getMaxChain() > 0) && (quest.getChain() >= transition.getMaxChain()))
			{
				continue;
			}
			if ((transition.getEvent() == event) && ((random.nextDouble() * 100) < transition.getChance()))
			{
				chosen = transition;
				break;
			}
		}

		final TransitionAction action = chosen != null ? chosen.getAction() : (event == TransitionEvent.SUCCESS ? TransitionAction.NEXT : TransitionAction.FAIL);
		if (event == TransitionEvent.SUCCESS)
		{
			int optional = 0;
			for (AbstractObjective objective : quest.getObjectives())
			{
				if (objective.isOptional() && objective.isDone())
				{
					optional++;
				}
				if (objective.isBonusEarned())
				{
					optional++;
				}
			}
			if (optional > 0)
			{
				quest.setOptionalDone(quest.getOptionalDone() + optional);
				quest.message("Optional objectives done: " + optional + ". The reward grows.");
			}
		}
		if ((chosen != null) && (chosen.getText() != null) && (action != TransitionAction.ESCALATE))
		{
			quest.announce(chosen.getText());
		}

		switch (action)
		{
			case NEXT:
			{
				advance(quest, quest.getStageIndex() + 1);
				break;
			}
			case STAGE:
			{
				advance(quest, chosen.getTarget() - 1);
				break;
			}
			case COMPLETE:
			{
				finish(quest, DynamicQuestState.SUCCESS, null);
				break;
			}
			case FOLLOW_UP:
			{
				finish(quest, DynamicQuestState.SUCCESS, null);
				offerFollowUp(quest, chosen.getTarget());
				break;
			}
			case ESCALATE:
			{
				if (quest.getEscalations() >= chosen.getMax())
				{
					finish(quest, DynamicQuestState.FAILED, reason != null ? reason : quest.getTemplate().getText("failEscalate", "It slipped away from you for good."));
					break;
				}
				quest.setEscalations(quest.getEscalations() + 1);
				final int extra = chosen.getExtraTime() > 0 ? chosen.getExtraTime() : Math.max(120, quest.getDifficulty().timeLimit(stage.getTimeLimit()) / 2);
				quest.setStageDeadline(System.currentTimeMillis() + (extra * 1000L));
				if (chosen.getText() != null)
				{
					quest.announce(chosen.getText());
					quest.message(chosen.getText());
				}
				quest.setStageEnding(true);
				try
				{
					for (AbstractObjective objective : quest.getObjectives())
					{
						if (!objective.isDone())
						{
							objective.onEscalate(quest.getEscalations());
						}
					}
				}
				finally
				{
					quest.setStageEnding(false);
				}
				log(quest.getName() + " escalated (" + quest.getEscalations() + ").");
				break;
			}
			case FAIL:
			default:
			{
				String text = reason;
				if (text == null)
				{
					text = event == TransitionEvent.TIMEOUT ? quest.getTemplate().getText("failTime", "Time has run out.") : quest.getTemplate().getText("failText", "You have failed.");
				}
				finish(quest, DynamicQuestState.FAILED, text);
				break;
			}
		}
	}

	private void advance(QuestInstance quest, int index)
	{
		for (AbstractObjective objective : quest.getObjectives())
		{
			objective.cleanup();
		}
		if ((index < 0) || (index >= quest.getTemplate().getStages().size()))
		{
			finish(quest, DynamicQuestState.SUCCESS, null);
			return;
		}
		startStage(quest, index, null);
		save(quest);
	}

	/**
	 * Ends a quest. Called with the quest's lock held.
	 * @param quest the quest
	 * @param state how it ended
	 * @param message what the players are told when it didn't succeed, may be {@code null}
	 */
	private void finish(QuestInstance quest, DynamicQuestState state, String message)
	{
		if (!quest.isActive())
		{
			return;
		}
		quest.setState(state);
		for (AbstractObjective objective : quest.getObjectives())
		{
			objective.cleanup();
		}
		quest.despawnAll(null);
		quest.setMarker(null);
		_quests.remove(quest.getId());
		for (int objectId : quest.getParticipants())
		{
			final Set<QuestInstance> quests = _byPlayer.get(objectId);
			if (quests != null)
			{
				quests.remove(quest);
			}
		}
		if (!quest.isWorld())
		{
			ThreadPool.execute(() -> DynamicQuestTable.deleteQuest(quest.getId()));
			if (state != DynamicQuestState.SUCCESS)
			{
				turnDown(quest.getOwnerId(), quest.getTemplate().getId());
			}
		}

		if (state == DynamicQuestState.SUCCESS)
		{
			final String success = quest.getTemplate().getText("success", "It is done.");
			quest.announce(quest.getName() + " - complete!");
			distributeRewards(quest, success);
			if (quest.isWorld() && DynamicQuestConfig.ANNOUNCE_WORLD_EVENTS)
			{
				Broadcast.toAllOnlinePlayers(quest.format(quest.getTemplate().getText("worldSuccess", "{name} near {near} is over. Well fought!").replace("{name}", quest.getName())));
			}
			log(quest.getName() + " completed" + (quest.getOwnerName() != null ? " by " + quest.getOwnerName() : "") + ".");
		}
		else
		{
			final String text = quest.format(message != null ? message : "The quest is over.");
			for (Player player : quest.getAudience())
			{
				QuestSpawnUtil.announce(player, text);
				player.sendMessage(quest.getName() + ": " + text);
			}
			if (quest.isWorld() && DynamicQuestConfig.ANNOUNCE_WORLD_EVENTS)
			{
				Broadcast.toAllOnlinePlayers(quest.format(quest.getTemplate().getText("worldFail", "{name} near {near} could not be stopped.").replace("{name}", quest.getName())));
			}
			log(quest.getName() + " ended (" + state + ")" + (message != null ? ": " + quest.format(message) : "") + ".");
		}
	}

	private void offerFollowUp(QuestInstance quest, int templateId)
	{
		final QuestTemplate template = DynamicQuestData.getInstance().getTemplate(templateId);
		final Player owner = quest.getOwner();
		if ((template == null) || (owner == null) || isTurnedDown(owner.getObjectId(), templateId) || _personalOffers.containsKey(owner.getObjectId()))
		{
			return;
		}
		final Map<String, String> carry = new LinkedHashMap<>();
		for (String key : new String[]
		{
			"area",
			"areaNpcs",
			"near",
			"monster",
			"town",
			"leader",
			"gang",
			"hostage",
			"caravan",
			"expedition",
			"last"
		})
		{
			final String value = quest.getContext(key);
			if (value != null)
			{
				carry.put(key, value);
			}
		}
		final QuestOffer offer = new QuestOffer("F" + quest.getId(), template, null, Rnd.nextLong(), null, quest.getDifficulty().getTier(), quest.getChain() + 1, carry);
		offer.setExpires(System.currentTimeMillis() + (DynamicQuestConfig.HERALD_WAIT_TIME * 2000L));
		_personalOffers.put(owner.getObjectId(), offer);
		owner.sendPacket(new TutorialShowQuestionMark(QUESTION_MARK_ID));
		ThreadPool.schedule(() -> sendWindow(owner, DynamicQuestHtml.offer(this, owner, null, offer, Channel.WINDOW)), 2000);
	}

	// ---------------------------------------------------------------------------------------------
	// Rewards
	// ---------------------------------------------------------------------------------------------

	private void distributeRewards(QuestInstance quest, String successText)
	{
		final List<Player> candidates = new ArrayList<>(quest.getOnlineParticipants());
		// The owner first, then by presence: they keep the reward when two characters share a computer.
		candidates.sort((a, b) ->
		{
			if (a.getObjectId() == quest.getOwnerId())
			{
				return -1;
			}
			if (b.getObjectId() == quest.getOwnerId())
			{
				return 1;
			}
			return Double.compare(quest.getPresence(b.getObjectId()), quest.getPresence(a.getObjectId()));
		});

		int contributors = 0;
		int contributionSum = 0;
		for (int value : quest.getContributions().values())
		{
			if (value > 0)
			{
				contributors++;
				contributionSum += value;
			}
		}
		final double fairShare = contributors > 0 ? contributionSum / (double) contributors : 1;

		final Set<String> computers = new HashSet<>();
		for (Player player : candidates)
		{
			final String refusal = getRewardRefusal(quest, player);
			if (refusal != null)
			{
				player.sendMessage(quest.getName() + ": no reward - " + refusal);
				continue;
			}
			if (DynamicQuestConfig.ONE_REWARD_PER_COMPUTER && !computers.add(getComputer(player)))
			{
				player.sendMessage(quest.getName() + ": no reward - another character of your computer took it.");
				continue;
			}

			double factor = RewardCalculator.getRepeatFactor(getDailyCount(player.getAccountName(), quest.getTemplate().getId()));
			factor *= RewardCalculator.getLevelShare(player.getLevel(), quest.getDifficulty().getQuestLevel());
			if (quest.isWorld())
			{
				factor *= 0.5 + (0.5 * Math.min(1, quest.getContribution(player.getObjectId()) / fairShare));
			}
			final Payout payout = RewardCalculator.compute(quest, factor, true);
			if (player.getObjectId() == quest.getOwnerId())
			{
				payTreasuryShare(quest, payout);
			}
			give(player, payout);
			if ((player.getObjectId() == quest.getOwnerId()) && (quest.getTemplate().getReward().getTitle() != null) && (quest.getChain() >= quest.getTemplate().getReward().getMinChain()))
			{
				player.setTitle(quest.format(quest.getTemplate().getReward().getTitle()));
				player.broadcastTitleInfo();
				player.sendMessage(quest.getName() + ": you earned the title \"" + player.getTitle() + "\".");
			}
			recordCompletion(player.getAccountName(), quest.getTemplate());

			final NpcHtmlMessage html = new NpcHtmlMessage(0, DynamicQuestHtml.complete(quest, payout, successText));
			player.sendPacket(html);
		}
	}

	/**
	 * The lord of the quest's castle pays a share on top, from the treasury, when it can.
	 */
	private static void payTreasuryShare(QuestInstance quest, Payout payout)
	{
		final int percent = quest.getTemplate().getReward().getTreasuryPercent();
		final int castleId = quest.getContextInt("castleId", 0);
		if ((percent <= 0) || (castleId <= 0) || (payout._adena <= 0))
		{
			return;
		}
		final Castle castle = CastleManager.getInstance().getCastleById(castleId);
		final long share = (payout._adena * percent) / 100;
		if ((castle != null) && (castle.getOwnerId() > 0) && (castle.getTreasury() >= share) && castle.addToTreasuryNoTax(-share))
		{
			payout._adena += share;
			quest.message("The lord of " + castle.getName() + " adds " + String.format("%,d", share) + " Adena from the treasury.");
		}
	}

	/**
	 * @return why the player gets nothing, {@code null} if it is rewarded
	 */
	private String getRewardRefusal(QuestInstance quest, Player player)
	{
		if (player.getKarma() > 0)
		{
			return "your hands are not clean.";
		}
		if (player.isInOlympiadMode())
		{
			return "you are in the Olympiad.";
		}
		if (quest.isWorld())
		{
			if (quest.getContribution(player.getObjectId()) <= 0)
			{
				return "you did not take part.";
			}
		}
		else if ((player.getObjectId() != quest.getOwnerId()) && ((quest.getPresence(player.getObjectId()) * 100) < DynamicQuestConfig.MIN_PRESENCE_PERCENT))
		{
			return "you were not there for most of it.";
		}
		if (RewardCalculator.getLevelShare(player.getLevel(), quest.getDifficulty().getQuestLevel()) <= 0)
		{
			return "it was too easy for you.";
		}
		if (getAccountDailyTotal(player.getAccountName()) >= DynamicQuestConfig.ACCOUNT_DAILY_LIMIT)
		{
			return "your account has done its quests for today.";
		}
		return null;
	}

	private static String getComputer(Player player)
	{
		if ((player.getClient() != null) && (player.getClient().getHardwareInfo() != null) && (player.getClient().getHardwareInfo().getMacAddress() != null))
		{
			return "hw:" + player.getClient().getHardwareInfo().getMacAddress();
		}
		return (player.getClient() != null) && (player.getClient().getIp() != null) ? "ip:" + player.getClient().getIp() : "char:" + player.getObjectId();
	}

	private static void give(Player player, Payout payout)
	{
		if ((payout._exp > 0) || (payout._sp > 0))
		{
			player.addExpAndSp(payout._exp, payout._sp);
		}
		if (payout._adena > 0)
		{
			player.addItem(ItemProcessType.REWARD, RewardCalculator.ADENA_ID, payout._adena, null, true);
		}
		for (long[] item : payout._items)
		{
			final ItemTemplate template = ItemData.getInstance().getTemplate((int) item[0]);
			if (template != null)
			{
				player.addItem(ItemProcessType.REWARD, (int) item[0], item[1], null, true);
			}
		}
	}

	// ---------------------------------------------------------------------------------------------
	// Events
	// ---------------------------------------------------------------------------------------------

	private void onLogin(Player player)
	{
		if ((player == null) || !player.isOnline())
		{
			return;
		}

		for (QuestInstance quest : getQuests(player))
		{
			quest.showMarker(player);
		}

		final List<String> restored = new ArrayList<>();
		for (QuestRow row : DynamicQuestTable.loadQuests(player.getObjectId()))
		{
			if (_quests.containsKey(row._instanceId))
			{
				continue;
			}
			final QuestTemplate template = DynamicQuestData.getInstance().getTemplate(row._templateId);
			if ((template == null) || (row._stage >= template.getStages().size()))
			{
				ThreadPool.execute(() -> DynamicQuestTable.deleteQuest(row._instanceId));
				continue;
			}
			if ((row._expireAt > 0) && (row._expireAt < System.currentTimeMillis()))
			{
				ThreadPool.execute(() -> DynamicQuestTable.deleteQuest(row._instanceId));
				player.sendMessage("Dynamic quest: " + template.getName().replaceAll("\\{[a-zA-Z0-9]+\\}", "").trim() + " - its time ran out while you were away.");
				continue;
			}

			final QuestInstance quest = new QuestInstance(row._instanceId, template, row._seed, new DifficultyContext(row._questLevel, row._partySize, QuestTier.parse(row._tier, QuestTier.NORMAL)), row._charId, player.getName(), player.getAccountName(), row._offerKey, row._createdAt);
			quest.loadContext(row._context);
			quest.setChain(row._chain);
			quest.setOptionalDone(row._optionalDone);
			quest.setEscalations(row._escalations);
			quest.setStageDeadline(row._expireAt);
			for (String id : row._participants.split(","))
			{
				if (!id.trim().isEmpty())
				{
					try
					{
						quest.addParticipant(Integer.parseInt(id.trim()));
					}
					catch (NumberFormatException e)
					{
						// Skipped.
					}
				}
			}

			_quests.put(quest.getId(), quest);
			for (int objectId : quest.getParticipants())
			{
				_byPlayer.computeIfAbsent(objectId, key -> ConcurrentHashMap.newKeySet()).add(quest);
			}
			synchronized (quest)
			{
				startStage(quest, row._stage, row._objectiveState.isEmpty() ? new String[0] : row._objectiveState.split("\\|", -1));
			}
			if (quest.isActive())
			{
				restored.add(quest.getName());
			}
		}
		if (!restored.isEmpty())
		{
			player.sendMessage("Your dynamic quests: " + String.join(", ", restored) + ". See .quests for their progress.");
		}
	}

	private void onLogout(Player player)
	{
		dismissPersonalOffer(player, null);
		for (QuestInstance quest : getQuests(player))
		{
			if (quest.getOwnerId() != player.getObjectId())
			{
				continue;
			}
			synchronized (quest)
			{
				final StageTemplate stage = quest.getStage();
				if ((stage == null) || !stage.isPersistent())
				{
					finish(quest, DynamicQuestState.FAILED, quest.getTemplate().getText("failLogout", "You left, and the moment has passed."));
					continue;
				}
				save(quest);
				for (AbstractObjective objective : quest.getObjectives())
				{
					objective.cleanup();
				}
				quest.despawnAll(null);
				_quests.remove(quest.getId());
				for (int objectId : quest.getParticipants())
				{
					final Set<QuestInstance> quests = _byPlayer.get(objectId);
					if (quests != null)
					{
						quests.remove(quest);
					}
				}
			}
		}
	}

	/**
	 * A party member came near the owner and takes part from now on.
	 * @param quest the quest
	 * @param player the player
	 */
	public void onParticipantAdded(QuestInstance quest, Player player)
	{
		_byPlayer.computeIfAbsent(player.getObjectId(), key -> ConcurrentHashMap.newKeySet()).add(quest);
		quest.showMarker(player);
		if (!quest.isWorld())
		{
			player.sendMessage("You take part in " + quest.getName() + " while you stay near " + quest.getOwnerName() + ".");
		}
	}

	private void onMonsterKill(OnAttackableKill event)
	{
		final Player player = event.getAttacker();
		final Attackable monster = event.getTarget();
		if ((player == null) || (monster == null) || player.isFakePlayer())
		{
			return;
		}
		if (monster.getVariables().getString(QuestInstance.NPC_VAR, null) != null)
		{
			return; // The quest's own monsters are counted by its death listener.
		}

		for (QuestInstance quest : getQuests(player))
		{
			if (quest.isWorld())
			{
				continue;
			}
			synchronized (quest)
			{
				if (!quest.isActive() || quest.isStageEnding())
				{
					continue;
				}
				for (AbstractObjective objective : new ArrayList<>(quest.getObjectives()))
				{
					if (!objective.isDone() && !objective.isFailed())
					{
						objective.onKill(player, monster);
					}
				}
				if (quest.isNeedsCheck())
				{
					checkStage(quest);
				}
			}
		}

		if (DynamicQuestConfig.ENABLED && !monster.isRaid() && (monster.getInstanceId() == 0))
		{
			countAreaKill(player, monster);
			checkPlayerAction(player, "KILL");
		}
	}

	/**
	 * An npc spawned by a quest died.
	 * @param quest the quest
	 * @param npc the npc
	 * @param killer its killer, may be {@code null}
	 */
	public void onQuestNpcDeath(QuestInstance quest, Npc npc, Creature killer)
	{
		synchronized (quest)
		{
			final AbstractObjective objective = quest.getSpawner(npc);
			if ((objective == null) || !quest.isActive() || quest.isStageEnding() || !quest.getObjectives().contains(objective))
			{
				return;
			}
			objective.onNpcDeath(npc, killer);
			if (quest.isNeedsCheck())
			{
				checkStage(quest);
			}
		}
	}

	private void onPlayerDeath(OnCreatureDeath event)
	{
		if (!event.getTarget().isPlayer())
		{
			return;
		}
		final Player player = event.getTarget().asPlayer();
		for (QuestInstance quest : getQuests(player))
		{
			synchronized (quest)
			{
				if (!quest.isActive())
				{
					continue;
				}
				quest.setDeathOccurred();
				for (AbstractObjective objective : new ArrayList<>(quest.getObjectives()))
				{
					objective.onPlayerDeath(player);
				}
				final StageTemplate stage = quest.getStage();
				if ((stage != null) && stage.isFailOnDeath() && (player.getObjectId() == quest.getOwnerId()))
				{
					applyTransition(quest, TransitionEvent.FAILURE, quest.getTemplate().getText("failDeath", "You fell, and the moment has passed."));
				}
				else if (quest.isNeedsCheck())
				{
					checkStage(quest);
				}
			}
		}
	}

	/**
	 * A player caught a fish (called by the fishing code).
	 * @param player the player
	 * @param fishId the fish item
	 * @param lureId the lure used
	 */
	public void onFishCaught(Player player, int fishId, int lureId)
	{
		if (!DynamicQuestConfig.ENABLED || (player == null))
		{
			return;
		}
		for (QuestInstance quest : getQuests(player))
		{
			synchronized (quest)
			{
				if (!quest.isActive() || quest.isStageEnding())
				{
					continue;
				}
				for (AbstractObjective objective : new ArrayList<>(quest.getObjectives()))
				{
					if (!objective.isDone() && !objective.isFailed())
					{
						objective.onFish(player, fishId, lureId);
					}
				}
				if (quest.isNeedsCheck())
				{
					checkStage(quest);
				}
			}
		}
	}

	private void onLevelChanged(OnPlayerLevelChanged event)
	{
		if (event.getNewLevel() > event.getOldLevel())
		{
			checkPlayerAction(event.getPlayer(), "LEVEL_UP");
		}
	}

	private void onQuestionMark(Player player, int markId)
	{
		if (markId != QUESTION_MARK_ID)
		{
			return;
		}
		final QuestOffer offer = _personalOffers.get(player.getObjectId());
		if (offer != null)
		{
			if ((offer.getHerald() != null) && offer.getHerald().isSpawned() && (player.calculateDistance2D(offer.getHerald()) > (Npc.INTERACTION_DISTANCE - 50)))
			{
				player.sendMessage(offer.getHerald().getName() + " is waiting for you. Walk up to them.");
				return;
			}
			sendWindow(player, DynamicQuestHtml.offer(this, player, null, offer, Channel.WINDOW));
			return;
		}
		sendWindow(player, DynamicQuestHtml.main(this, player, null, Channel.WINDOW));
	}

	// ---------------------------------------------------------------------------------------------
	// Tick
	// ---------------------------------------------------------------------------------------------

	private void tickAll()
	{
		for (QuestInstance quest : _quests.values())
		{
			try
			{
				synchronized (quest)
				{
					tick(quest);
				}
			}
			catch (Exception e)
			{
				LOGGER.log(Level.WARNING, getClass().getSimpleName() + ": Error in " + quest.getName() + ".", e);
				synchronized (quest)
				{
					finish(quest, DynamicQuestState.FAILED, "Something went wrong.");
				}
			}
		}

		for (Map.Entry<Integer, QuestOffer> entry : _personalOffers.entrySet())
		{
			try
			{
				updatePersonalOffer(entry.getKey(), entry.getValue());
			}
			catch (Exception e)
			{
				LOGGER.log(Level.WARNING, getClass().getSimpleName() + ": Error in a herald visit.", e);
			}
		}
	}

	private void tick(QuestInstance quest)
	{
		if (!quest.isActive())
		{
			return;
		}
		if (!quest.isWorld() && (quest.getOwner() == null))
		{
			return; // Being saved by the logout.
		}

		final List<Player> near = quest.updatePresence();
		if (quest.getTemplate().getEligibility().isNightOnly() && !isNightNow())
		{
			finish(quest, DynamicQuestState.FAILED, quest.getTemplate().getText("failDawn", "Dawn breaks, and the night's work is over."));
			return;
		}
		if ((quest.getStageDeadline() > 0) && (System.currentTimeMillis() > quest.getStageDeadline()))
		{
			applyTransition(quest, TransitionEvent.TIMEOUT, null);
			return;
		}

		final int second = quest.nextSecond();
		for (AbstractObjective objective : new ArrayList<>(quest.getObjectives()))
		{
			objective.tick(second, near);
			if (!quest.isActive() || quest.isNeedsCheck())
			{
				break;
			}
		}
		if (quest.isNeedsCheck())
		{
			checkStage(quest);
		}

		final int left = quest.getSecondsLeft();
		if (quest.isActive() && ((left == 300) || (left == 60)))
		{
			quest.screen("Time left: " + (left / 60) + " min");
		}
	}

	private void saveDirty()
	{
		for (QuestInstance quest : _quests.values())
		{
			if (quest.isDirty() && quest.isActive() && !quest.isWorld())
			{
				save(quest);
			}
		}
	}

	/**
	 * Saves every running quest (shutdown).
	 */
	public void storeAll()
	{
		for (QuestInstance quest : _quests.values())
		{
			if (quest.isActive() && !quest.isWorld())
			{
				final StageTemplate stage = quest.getStage();
				if ((stage != null) && stage.isPersistent())
				{
					DynamicQuestTable.saveQuest(toRow(quest));
				}
				else
				{
					DynamicQuestTable.deleteQuest(quest.getId());
				}
			}
		}
	}

	private void save(QuestInstance quest)
	{
		if (quest.isWorld())
		{
			return;
		}
		quest.setDirty(false);
		final QuestRow row = toRow(quest);
		ThreadPool.execute(() -> DynamicQuestTable.saveQuest(row));
	}

	private static QuestRow toRow(QuestInstance quest)
	{
		final QuestRow row = new QuestRow();
		row._instanceId = quest.getId();
		row._charId = quest.getOwnerId();
		row._templateId = quest.getTemplate().getId();
		row._seed = quest.getSeed();
		row._tier = quest.getDifficulty().getTier().name();
		row._questLevel = quest.getDifficulty().getQuestLevel();
		row._partySize = quest.getDifficulty().getPartySize();
		row._stage = quest.getStageIndex();
		row._escalations = quest.getEscalations();
		row._chain = quest.getChain();
		row._optionalDone = quest.getOptionalDone();
		row._expireAt = quest.getStageDeadline();
		row._context = quest.serializeContext();
		row._objectiveState = quest.serializeObjectives();
		final StringBuilder sb = new StringBuilder();
		for (int objectId : quest.getParticipants())
		{
			sb.append(sb.length() > 0 ? "," : "").append(objectId);
		}
		row._participants = sb.toString();
		row._offerKey = quest.getOfferKey() != null ? quest.getOfferKey() : "";
		row._createdAt = quest.getCreated();
		return row;
	}

	// ---------------------------------------------------------------------------------------------
	// World-event director and heralds
	// ---------------------------------------------------------------------------------------------

	private void runDirector()
	{
		try
		{
			final List<Player> candidates = new ArrayList<>();
			for (Player player : World.getInstance().getPlayers())
			{
				if (canBeVisited(player, false))
				{
					candidates.add(player);
				}
			}
			if (candidates.isEmpty())
			{
				return;
			}

			for (QuestTemplate template : DynamicQuestData.getInstance().getTemplates())
			{
				final QuestTrigger trigger = template.getTrigger(TriggerType.TIMER);
				if ((trigger == null) || (Rnd.get(100.0) >= trigger.getChance()))
				{
					continue;
				}

				if ((template.getScope() == QuestScope.WORLD) && isWorldQuestRunning(template))
				{
					continue;
				}

				Collections.shuffle(candidates);
				for (Player player : candidates)
				{
					if (template.getScope() == QuestScope.WORLD)
					{
						if ((player.getLevel() >= template.getEligibility().getMinLevel()) && (player.getLevel() <= template.getEligibility().getMaxLevel()) && (startWorldQuest(template, player) == null))
						{
							break;
						}
						continue;
					}
					if (offerByHerald(player, template))
					{
						candidates.remove(player);
						break;
					}
				}
				if (candidates.isEmpty())
				{
					break;
				}
			}
		}
		catch (Exception e)
		{
			LOGGER.log(Level.WARNING, getClass().getSimpleName() + ": Error in the world-event director.", e);
		}
	}

	/**
	 * @param player a player
	 * @param duringCombat {@code true} if the player may be fighting (the herald waits for it)
	 * @return {@code true} if a herald or a world event can come to it now
	 */
	private boolean canBeVisited(Player player, boolean duringCombat)
	{
		if ((player == null) || !player.isOnline() || player.isFakePlayer() || player.isInOfflineMode() || player.isDead() || (!duringCombat && player.isInCombat()) || player.isInOlympiadMode() || player.isOnEvent() || player.isInStoreMode() || (player.getInstanceId() != 0))
		{
			return false;
		}
		if ((player.getLevel() < DynamicQuestConfig.MIN_LEVEL) || _personalOffers.containsKey(player.getObjectId()) || player.getVariables().getBoolean(VAR_NO_HERALDS, false))
		{
			return false;
		}
		if (player.isInsideZone(ZoneId.PEACE) || player.isInsideZone(ZoneId.TOWN) || player.isInsideZone(ZoneId.SIEGE) || player.isInsideZone(ZoneId.PVP) || player.isInsideZone(ZoneId.PVP_SPOT))
		{
			return false;
		}
		final Long next = _nextOffer.get(player.getObjectId());
		return ((next == null) || (next < System.currentTimeMillis())) && (getOwnedQuests(player).size() < DynamicQuestConfig.MAX_ACTIVE);
	}

	private void countAreaKill(Player player, Attackable monster)
	{
		final long cell = ((long) Math.floorDiv(monster.getX(), AREA_CELL) << 32) | (Math.floorDiv(monster.getY(), AREA_CELL) & 0xFFFFFFFFL);
		final long now = System.currentTimeMillis();
		final List<Long> kills = _areaKills.computeIfAbsent(cell, key -> new ArrayList<>());
		int count;
		synchronized (kills)
		{
			kills.add(now);
			kills.removeIf(time -> time < (now - (DynamicQuestConfig.AREA_KILL_WINDOW * 1000L)));
			count = kills.size();
		}

		for (QuestTemplate template : DynamicQuestData.getInstance().getTemplates())
		{
			final QuestTrigger trigger = template.getTrigger(TriggerType.WORLD_STATE);
			if ((trigger == null) || (count < trigger.getParams().getInt("threshold", 100)) || (Rnd.get(100.0) >= trigger.getChance()))
			{
				continue;
			}
			final boolean started = template.getScope() == QuestScope.WORLD ? startWorldQuest(template, player) == null : (canBeVisited(player, true) && offerByHerald(player, template));
			if (started)
			{
				synchronized (kills)
				{
					kills.clear();
				}
				return;
			}
		}
	}

	private void checkPlayerAction(Player player, String action)
	{
		if (!canBeVisited(player, true))
		{
			return;
		}
		for (QuestTemplate template : DynamicQuestData.getInstance().getTemplates())
		{
			final QuestTrigger trigger = template.getTrigger(TriggerType.PLAYER_ACTION);
			if ((trigger != null) && action.equalsIgnoreCase(trigger.getParams().getString("action", "KILL")) && (Rnd.get(100.0) < trigger.getChance()) && (trigger.isDirect() ? offerDirect(player, template) : offerByHerald(player, template)))
			{
				return;
			}
		}
	}

	/**
	 * Sends a herald to a player with a quest.
	 * @param player the player
	 * @param template the quest
	 * @return {@code true} if the herald is on its way
	 */
	public boolean offerByHerald(Player player, QuestTemplate template)
	{
		if (!canOffer(player, template))
		{
			return false;
		}

		final Location location = QuestSpawnUtil.findPoint(player, 300, 450, true, player.getInstanceId());
		final Npc herald = Quest.addSpawn(DynamicQuestData.getInstance().getHeraldNpcId(), location.getX(), location.getY(), location.getZ(), LocationUtil.calculateHeadingFrom(location, player), false, 0, false, player.getInstanceId());
		if (herald == null)
		{
			return false;
		}
		herald.setRandomWalking(false);
		herald.setRunning();
		_heraldOwners.put(herald.getObjectId(), player.getObjectId());
		herald.broadcastInfo();

		final QuestOffer offer = new QuestOffer("H" + herald.getObjectId(), template, null, Rnd.nextLong(), null, rollTier(), 0, null);
		offer.setHerald(herald);
		offer.setExpires(System.currentTimeMillis() + (DynamicQuestConfig.HERALD_WAIT_TIME * 1000L) + 30000);
		_personalOffers.put(player.getObjectId(), offer);
		_nextOffer.put(player.getObjectId(), System.currentTimeMillis() + (DynamicQuestConfig.OFFER_RETRY_DELAY * 1000L));
		herald.getAI().setIntention(Intention.MOVE_TO, QuestSpawnUtil.findPoint(player, 60, 100, true, player.getInstanceId()));
		AdventurersGuideManager.getInstance().trigger(player, GuideLesson.NOTICE_BOARD, "A herald is running up to you with a quest.");
		log("A herald brings " + template.getName() + " to " + player.getName() + ".");
		return true;
	}

	/**
	 * @param player a player
	 * @param template a template
	 * @return {@code true} if it may be offered to the player unasked: never one turned down today or already running
	 */
	private boolean canOffer(Player player, QuestTemplate template)
	{
		if ((template.getScope() != QuestScope.PERSONAL) || isTurnedDown(player.getObjectId(), template.getId()))
		{
			return false;
		}
		for (QuestInstance quest : getOwnedQuests(player))
		{
			if (quest.getTemplate() == template)
			{
				return false;
			}
		}
		final QuestTrigger trigger = template.getTrigger(TriggerType.PLAYER_ACTION) != null ? template.getTrigger(TriggerType.PLAYER_ACTION) : template.getTrigger(TriggerType.TIMER);
		if ((trigger != null) && !isTownConditionMet(trigger, getNearestTown(player)))
		{
			return false;
		}
		return (template.getEligibility().test(player, getMembers(player)) == null) && (checkLimits(player, template) == null);
	}

	/**
	 * Gives a player an offer that needs no herald (a find: a torn map, a note).
	 * @param player the player
	 * @param template the quest
	 * @return {@code true} if the offer was made
	 */
	public boolean offerDirect(Player player, QuestTemplate template)
	{
		if (!canOffer(player, template))
		{
			return false;
		}
		final QuestOffer offer = new QuestOffer("D" + System.nanoTime(), template, null, Rnd.nextLong(), null, rollTier(), 0, null);
		offer.setExpires(System.currentTimeMillis() + (DynamicQuestConfig.HERALD_WAIT_TIME * 3000L));
		_personalOffers.put(player.getObjectId(), offer);
		_nextOffer.put(player.getObjectId(), System.currentTimeMillis() + (DynamicQuestConfig.OFFER_RETRY_DELAY * 1000L));
		final String line = template.getText("findText", "You found something.");
		QuestSpawnUtil.announce(player, line);
		player.sendMessage(line + " Type .quests to look at it again.");
		player.sendPacket(new TutorialShowQuestionMark(QUESTION_MARK_ID));
		sendWindow(player, DynamicQuestHtml.offer(this, player, null, offer, Channel.WINDOW));
		log(player.getName() + " found " + template.getName() + ".");
		return true;
	}

	private void turnDown(int playerObjectId, int templateId)
	{
		if (playerObjectId > 0)
		{
			checkDay();
			_turnedDown.computeIfAbsent(playerObjectId, key -> ConcurrentHashMap.newKeySet()).add(templateId);
		}
	}

	private boolean isTurnedDown(int playerObjectId, int templateId)
	{
		checkDay();
		final Set<Integer> turnedDown = _turnedDown.get(playerObjectId);
		return (turnedDown != null) && turnedDown.contains(templateId);
	}

	private static QuestTier rollTier()
	{
		int roll = Rnd.get(100);
		final QuestTier[] tiers = QuestTier.values();
		for (int i = 0; i < tiers.length; i++)
		{
			roll -= TIER_WEIGHTS[i];
			if (roll < 0)
			{
				return tiers[i];
			}
		}
		return QuestTier.NORMAL;
	}

	private void updatePersonalOffer(int playerObjectId, QuestOffer offer)
	{
		final Player player = World.getInstance().getPlayer(playerObjectId);
		if ((player == null) || !player.isOnline())
		{
			dismissPersonalOffer(playerObjectId, offer, null);
			return;
		}
		if (System.currentTimeMillis() > offer.getExpires())
		{
			dismissPersonalOffer(playerObjectId, offer, offer.getHerald() != null ? "I can't wait any longer. Perhaps another time." : null);
			return;
		}

		final Npc herald = offer.getHerald();
		if (herald == null)
		{
			return;
		}
		if (!herald.isSpawned() || (player.calculateDistance2D(herald) > HERALD_LEAVE_RANGE) || (player.getInstanceId() != herald.getInstanceId()))
		{
			dismissPersonalOffer(playerObjectId, offer, null);
			return;
		}
		if (!offer.isArrived())
		{
			if (herald.calculateDistance2D(player) <= 150)
			{
				offer.setArrived(true);
				offer.setExpires(System.currentTimeMillis() + (DynamicQuestConfig.HERALD_WAIT_TIME * 1000L));
				herald.getAI().setIntention(Intention.IDLE);
				herald.setHeading(LocationUtil.calculateHeadingFrom(herald, player));
				herald.broadcastInfo();
				QuestSpawnUtil.say(player, herald, offer.getTemplate().getText("heraldLine", "You there! I have work for someone like you."));
				QuestSpawnUtil.screen(player, herald.getName() + " wants a word with you. Talk to them, or type .quests.");
				player.sendPacket(new TutorialShowQuestionMark(QUESTION_MARK_ID));
			}
			else if ((herald.getAI().getIntention() != Intention.MOVE_TO) || ((System.currentTimeMillis() / 1000) % 2) == 0)
			{
				herald.getAI().setIntention(Intention.MOVE_TO, QuestSpawnUtil.findPoint(player, 60, 100, true, player.getInstanceId()));
			}
		}
	}

	private void dismissPersonalOffer(Player player, String line)
	{
		final QuestOffer offer = _personalOffers.get(player.getObjectId());
		if (offer != null)
		{
			dismissPersonalOffer(player.getObjectId(), offer, line);
		}
	}

	private void dismissPersonalOffer(int playerObjectId, QuestOffer offer, String line)
	{
		if (!_personalOffers.remove(playerObjectId, offer))
		{
			return;
		}
		if (!offer.isAccepted())
		{
			turnDown(playerObjectId, offer.getTemplate().getId());
		}
		final Npc herald = offer.getHerald();
		if (herald == null)
		{
			return;
		}
		final Player player = World.getInstance().getPlayer(playerObjectId);
		if ((player != null) && (line != null))
		{
			QuestSpawnUtil.say(player, herald, line);
		}
		herald.setTalkable(false);
		herald.getAI().setIntention(Intention.MOVE_TO, QuestSpawnUtil.findPoint(herald, 300, 400, true, herald.getInstanceId()));
		ThreadPool.schedule(() ->
		{
			herald.deleteMe();
			_heraldOwners.remove(herald.getObjectId());
		}, 6000);
	}

	/**
	 * The player turned an offer down.
	 * @param player the player
	 */
	public void declinePersonalOffer(Player player)
	{
		dismissPersonalOffer(player, "As you wish.");
	}

	private boolean canSeeHerald(Npc npc, Player player)
	{
		final Integer owner = _heraldOwners.get(npc.getObjectId());
		return (owner == null) || player.isGM() || (owner == player.getObjectId());
	}

	/**
	 * @param npc a herald
	 * @return the player it came for, 0 if none
	 */
	public int getHeraldOwner(Npc npc)
	{
		final Integer owner = _heraldOwners.get(npc.getObjectId());
		return owner != null ? owner : 0;
	}

	// ---------------------------------------------------------------------------------------------
	// Player commands (Notice Board, Community Board, .quests)
	// ---------------------------------------------------------------------------------------------

	/**
	 * Runs a page command.
	 * @param player the player
	 * @param npc the npc talked to, {@code null} if none
	 * @param channel where the page goes
	 * @param command the command
	 * @return the page's body with its links, {@code null} for none
	 */
	public String handleCommand(Player player, Npc npc, Channel channel, String command)
	{
		final String[] args = command.trim().split("\\s+");
		final String action = args[0].isEmpty() ? "main" : args[0];
		switch (action)
		{
			case "offer":
			{
				final QuestOffer offer = args.length > 1 ? getOffer(player, args[1]) : null;
				return offer != null ? DynamicQuestHtml.offer(this, player, npc, offer, channel) : DynamicQuestHtml.main(this, player, npc, channel);
			}
			case "accept":
			{
				final QuestOffer offer = args.length > 1 ? getOffer(player, args[1]) : null;
				if (offer == null)
				{
					return DynamicQuestHtml.message("That offer is gone.", channel);
				}
				final String result = accept(player, npc, offer, QuestTier.parse(args.length > 2 ? args[2] : null, QuestTier.NORMAL));
				if (result.startsWith("!"))
				{
					return DynamicQuestHtml.message(result.substring(1), channel);
				}
				final QuestInstance quest = getQuest(Long.parseLong(result));
				return quest != null ? DynamicQuestHtml.quest(this, player, npc, quest, channel) : DynamicQuestHtml.main(this, player, npc, channel);
			}
			case "decline":
			{
				declinePersonalOffer(player);
				return null;
			}
			case "hidden":
			{
				HiddenQuestManager.getInstance().showQuestionMark(player);
				return null;
			}
			case "heralds":
			{
				final boolean off = (args.length > 1) ? args[1].equalsIgnoreCase("off") : !player.getVariables().getBoolean(VAR_NO_HERALDS, false);
				player.getVariables().set(VAR_NO_HERALDS, off);
				player.sendMessage(off ? "Heralds will leave you alone. Quests stay on the Notice Boards." : "Heralds may bring you quests again.");
				return DynamicQuestHtml.main(this, player, npc, channel);
			}
			case "quest":
			{
				final QuestInstance quest = findOwnQuest(player, args);
				return quest != null ? DynamicQuestHtml.quest(this, player, npc, quest, channel) : DynamicQuestHtml.main(this, player, npc, channel);
			}
			case "track":
			{
				final QuestInstance quest = findOwnQuest(player, args);
				if (quest != null)
				{
					// The current step's place, else the quest's hunting ground.
					final Location target = quest.getMarker() != null ? quest.getMarker() : quest.getArea();
					if (target != null)
					{
						QuestSpawnUtil.moveMarker(player, target, target);
					}
					player.sendMessage(target != null ? "Your radar points the way." : "There is nothing to point at right now.");
					return DynamicQuestHtml.quest(this, player, npc, quest, channel);
				}
				return DynamicQuestHtml.main(this, player, npc, channel);
			}
			case "abandon":
			{
				final QuestInstance quest = findOwnQuest(player, args);
				return (quest != null) && (quest.getOwnerId() == player.getObjectId()) ? DynamicQuestHtml.confirmAbandon(quest, channel) : DynamicQuestHtml.main(this, player, npc, channel);
			}
			case "abandon_confirm":
			{
				final QuestInstance quest = findOwnQuest(player, args);
				if ((quest != null) && (quest.getOwnerId() == player.getObjectId()))
				{
					synchronized (quest)
					{
						finish(quest, DynamicQuestState.ABANDONED, "You abandoned the quest.");
					}
				}
				return DynamicQuestHtml.main(this, player, npc, channel);
			}
			case "act":
			{
				return act(player, npc, channel, args);
			}
			default:
			{
				return DynamicQuestHtml.main(this, player, npc, channel);
			}
		}
	}

	private QuestInstance findOwnQuest(Player player, String[] args)
	{
		if (args.length < 2)
		{
			return null;
		}
		try
		{
			final QuestInstance quest = getQuest(Long.parseLong(args[1]));
			return (quest != null) && (quest.isParticipant(player.getObjectId()) || quest.isWorld() || player.isGM()) ? quest : null;
		}
		catch (NumberFormatException e)
		{
			return null;
		}
	}

	private String act(Player player, Npc npc, Channel channel, String[] args)
	{
		final QuestInstance quest = findOwnQuest(player, args);
		if ((quest == null) || (args.length < 4))
		{
			return null;
		}
		String result;
		synchronized (quest)
		{
			if (!quest.isActive())
			{
				return null;
			}
			final int index;
			try
			{
				index = Integer.parseInt(args[2]);
			}
			catch (NumberFormatException e)
			{
				return null;
			}
			if ((index < 0) || (index >= quest.getObjectives().size()))
			{
				return null;
			}
			final StringBuilder action = new StringBuilder(args[3]);
			for (int i = 4; i < args.length; i++)
			{
				action.append(' ').append(args[i]);
			}
			result = quest.getObjectives().get(index).onAction(player, npc, action.toString());
			if (quest.isNeedsCheck())
			{
				checkStage(quest);
			}
		}
		if (result != null)
		{
			return DynamicQuestHtml.message(result, channel);
		}
		return quest.isActive() ? DynamicQuestHtml.quest(this, player, npc, quest, channel) : null;
	}

	/**
	 * A player talks to a Notice Board, a herald or an npc spawned by a quest.
	 * @param player the player
	 * @param npc the npc
	 * @return the dialog's body (with %prefix% links), {@code null} for nothing
	 */
	public String onTalk(Player player, Npc npc)
	{
		final DynamicQuestData data = DynamicQuestData.getInstance();
		if (data.isNoticeBoard(npc.getId()))
		{
			AdventurersGuideManager.getInstance().discover(player, GuideLesson.NOTICE_BOARD);
			return DynamicQuestHtml.main(this, player, npc, Channel.NPC);
		}
		if (npc.getId() == data.getHeraldNpcId())
		{
			final QuestOffer offer = _personalOffers.get(player.getObjectId());
			if ((offer != null) && (offer.getHerald() == npc))
			{
				return DynamicQuestHtml.offer(this, player, npc, offer, Channel.NPC);
			}
			return DynamicQuestHtml.message(npc.getName() + " has nothing for you.", Channel.NPC);
		}

		final String id = npc.getVariables().getString(QuestInstance.NPC_VAR, null);
		final QuestInstance quest = id != null ? getQuest(Long.parseLong(id)) : null;
		if (quest == null)
		{
			return DynamicQuestHtml.message("...", Channel.NPC);
		}
		if (!quest.isWorld() && !quest.isParticipant(player.getObjectId()))
		{
			return DynamicQuestHtml.message("This is " + quest.getOwnerName() + "'s business, not yours.", Channel.NPC);
		}
		String body;
		synchronized (quest)
		{
			final AbstractObjective objective = quest.getSpawner(npc);
			body = (objective != null) && quest.getObjectives().contains(objective) ? objective.onTalk(player, npc) : null;
		}
		return body != null ? DynamicQuestHtml.wrapObjective(quest, body, Channel.NPC) : DynamicQuestHtml.quest(this, player, npc, quest, Channel.NPC);
	}

	/**
	 * Sends a page in a dialog window that needs no npc.
	 * @param player the player
	 * @param html the page, built for {@link Channel#WINDOW}
	 */
	public void sendWindow(Player player, String html)
	{
		if ((player != null) && (html != null))
		{
			player.sendPacket(new NpcHtmlMessage(0, html));
		}
	}

	// ---------------------------------------------------------------------------------------------
	// GM tools
	// ---------------------------------------------------------------------------------------------

	/**
	 * Starts a template for a player at once.
	 * @param player the player
	 * @param template the template
	 * @param tier the tier
	 * @return {@code null} if it started, else why not
	 */
	public String adminStart(Player player, QuestTemplate template, QuestTier tier)
	{
		if (template.getScope() == QuestScope.WORLD)
		{
			return startWorldQuest(template, player);
		}
		final QuestOffer offer = new QuestOffer("G" + System.nanoTime(), template, null, Rnd.nextLong(), null, tier, 0, null);
		final String result = accept(player, null, offer, tier);
		return result.startsWith("!") ? result.substring(1) : null;
	}

	/**
	 * Sends a herald with a template to a player at once.
	 */
	public boolean adminHerald(Player player, QuestTemplate template)
	{
		dismissPersonalOffer(player, null);
		_nextOffer.remove(player.getObjectId());
		return offerByHerald(player, template);
	}

	/**
	 * Completes or fails a player's quests.
	 * @param player the player
	 * @param success {@code true} to complete them
	 * @return how many
	 */
	public int adminFinish(Player player, boolean success)
	{
		int count = 0;
		for (QuestInstance quest : getOwnedQuests(player))
		{
			synchronized (quest)
			{
				if (success)
				{
					quest.setStageEnding(false);
					finish(quest, DynamicQuestState.SUCCESS, null);
				}
				else
				{
					finish(quest, DynamicQuestState.FAILED, "A higher power ended it.");
				}
			}
			count++;
		}
		return count;
	}

	/**
	 * Forgets what a player's account did today (limits, cooldowns, offers taken).
	 */
	public void adminReset(Player player)
	{
		_usage.remove(player.getAccountName());
		_taken.remove(player.getAccountName());
		DynamicQuestTable.resetAccount(player.getAccountName());
	}

	/**
	 * Rolls every board again.
	 */
	public void adminReroll()
	{
		_rerollSalt = Rnd.nextLong();
		_boardOffers.clear();
		_offerIndex.clear();
	}

	private static void log(String message)
	{
		if (DynamicQuestConfig.LOGGING)
		{
			LOGGER.info("DynamicQuests: " + message);
		}
	}

	public static DynamicQuestManager getInstance()
	{
		return SingletonHolder.INSTANCE;
	}

	private static class SingletonHolder
	{
		protected static final DynamicQuestManager INSTANCE = new DynamicQuestManager();
	}
}
