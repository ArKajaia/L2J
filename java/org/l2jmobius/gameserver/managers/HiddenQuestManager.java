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
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.l2jmobius.commons.threads.ThreadPool;
import org.l2jmobius.commons.util.Rnd;
import org.l2jmobius.gameserver.ai.Intention;
import org.l2jmobius.gameserver.cache.HtmCache;
import org.l2jmobius.gameserver.config.custom.HiddenQuestConfig;
import org.l2jmobius.gameserver.data.xml.HiddenQuestData;
import org.l2jmobius.gameserver.data.xml.ItemData;
import org.l2jmobius.gameserver.data.xml.NpcData;
import org.l2jmobius.gameserver.model.Location;
import org.l2jmobius.gameserver.model.World;
import org.l2jmobius.gameserver.model.actor.Attackable;
import org.l2jmobius.gameserver.model.actor.Creature;
import org.l2jmobius.gameserver.model.actor.Npc;
import org.l2jmobius.gameserver.model.actor.Player;
import org.l2jmobius.gameserver.model.actor.templates.NpcTemplate;
import org.l2jmobius.gameserver.model.events.Containers;
import org.l2jmobius.gameserver.model.events.EventType;
import org.l2jmobius.gameserver.model.events.holders.actor.creature.OnCreatureDeath;
import org.l2jmobius.gameserver.model.events.holders.actor.npc.OnNpcCanBeSeen;
import org.l2jmobius.gameserver.model.events.holders.actor.npc.attackable.OnAttackableKill;
import org.l2jmobius.gameserver.model.events.holders.actor.player.OnPlayerFameChanged;
import org.l2jmobius.gameserver.model.events.holders.actor.player.OnPlayerLevelChanged;
import org.l2jmobius.gameserver.model.events.holders.actor.player.OnPlayerLogin;
import org.l2jmobius.gameserver.model.events.holders.actor.player.OnPlayerLogout;
import org.l2jmobius.gameserver.model.events.holders.actor.player.OnPlayerPKChanged;
import org.l2jmobius.gameserver.model.events.holders.actor.player.OnPlayerPressTutorialMark;
import org.l2jmobius.gameserver.model.events.listeners.ConsumerEventListener;
import org.l2jmobius.gameserver.model.events.listeners.FunctionEventListener;
import org.l2jmobius.gameserver.model.events.returns.TerminateReturn;
import org.l2jmobius.gameserver.model.hiddenquest.HiddenQuestDefinition;
import org.l2jmobius.gameserver.model.hiddenquest.HiddenQuestSession;
import org.l2jmobius.gameserver.model.hiddenquest.HiddenReward;
import org.l2jmobius.gameserver.model.hiddenquest.HiddenTrigger;
import org.l2jmobius.gameserver.model.hiddenquest.HiddenTriggerType;
import org.l2jmobius.gameserver.model.hiddenquest.task.AbstractHiddenTask;
import org.l2jmobius.gameserver.model.hiddenquest.task.ChaseTask;
import org.l2jmobius.gameserver.model.hiddenquest.task.EchoGauntletTask;
import org.l2jmobius.gameserver.model.hiddenquest.task.EscortTask;
import org.l2jmobius.gameserver.model.hiddenquest.task.HuntedTask;
import org.l2jmobius.gameserver.model.hiddenquest.task.PilgrimageTask;
import org.l2jmobius.gameserver.model.hiddenquest.task.RiddleTask;
import org.l2jmobius.gameserver.model.hiddenquest.task.VigilTask;
import org.l2jmobius.gameserver.model.item.ItemTemplate;
import org.l2jmobius.gameserver.model.item.enums.ItemProcessType;
import org.l2jmobius.gameserver.model.script.Quest;
import org.l2jmobius.gameserver.model.skill.AbnormalVisualEffect;
import org.l2jmobius.gameserver.model.variables.PlayerVariables;
import org.l2jmobius.gameserver.model.zone.ZoneId;
import org.l2jmobius.gameserver.model.zone.type.TownZone;
import org.l2jmobius.gameserver.network.enums.ChatType;
import org.l2jmobius.gameserver.network.serverpackets.CreatureSay;
import org.l2jmobius.gameserver.network.serverpackets.ExShowScreenMessage;
import org.l2jmobius.gameserver.network.serverpackets.NpcHtmlMessage;
import org.l2jmobius.gameserver.network.serverpackets.TutorialShowQuestionMark;
import org.l2jmobius.gameserver.taskmanagers.GameTimeTaskManager;
import org.l2jmobius.gameserver.util.LocationUtil;

/**
 * Hidden Quests: secret conditions that send a messenger NPC to the player with a one-off quest.
 * <ol>
 * <li><b>Conditions</b> - listeners keep hidden counters in player variables ({@code HQ_C_*}); a periodic check reads the state conditions (PK, PvP, fame, adena, towns). A met condition only queues the quest ({@code HQ_PENDING}); nothing is shown.</li>
 * <li><b>Visit</b> - when the player is safe and idle, the quest's messenger spawns nearby (visible to that player only), walks up, greets them and raises the tutorial question mark.</li>
 * <li><b>Task</b> - accepting starts a timed {@link HiddenQuestSession}. Failing, dying (by default), logging out or running out of time ends it; the messenger comes back later.</li>
 * <li><b>Reward</b> - completion pays the quest's rewards for the player's level and records it ({@code HQ_DONE_<id>}); each quest happens once per character.</li>
 * </ol>
 * @author Mobius
 */
public class HiddenQuestManager
{
	private static final Logger LOGGER = Logger.getLogger(HiddenQuestManager.class.getName());

	/** The tutorial question mark id raised by messengers and tasks. */
	public static final int QUESTION_MARK_ID = 9300;

	private static final String HTML_PATH = "data/scripts/custom/HiddenQuests/";

	// Player variables.
	private static final String VAR_PENDING = "HQ_PENDING";
	private static final String VAR_DONE = "HQ_DONE_";
	private static final String VAR_NEXT_VISIT = "HQ_NEXT_VISIT";
	private static final String VAR_TOWNS = "HQ_TOWNS";
	private static final String VAR_MAX_LEVEL = "HQ_MAX_LEVEL";
	private static final String VAR_NAME_COLOR = "HQ_NAME_COLOR";

	// Messenger approach.
	private static final int APPROACH_STOP = 100;
	private static final int FOLLOW_DISTANCE = 300;
	private static final int LOST_DISTANCE = 2500;

	private final Map<Integer, HiddenQuestSession> _sessions = new ConcurrentHashMap<>();
	private final Map<Integer, MessengerVisit> _visits = new ConcurrentHashMap<>();
	private final Map<Integer, Integer> _messengerOwners = new ConcurrentHashMap<>();

	/** A messenger on its way to (or waiting next to) a player. */
	private static class MessengerVisit
	{
		final int _playerObjectId;
		final HiddenQuestDefinition _definition;
		final Npc _npc;
		boolean _arrived;
		long _waitUntil;
		ScheduledFuture<?> _task;

		MessengerVisit(int playerObjectId, HiddenQuestDefinition definition, Npc npc)
		{
			_playerObjectId = playerObjectId;
			_definition = definition;
			_npc = npc;
		}
	}

	protected HiddenQuestManager()
	{
		if (!HiddenQuestConfig.ENABLED)
		{
			return;
		}

		HiddenQuestData.getInstance().validate();

		Containers.Players().addListener(new ConsumerEventListener(Containers.Players(), EventType.ON_PLAYER_LOGIN, (OnPlayerLogin event) -> onLogin(event.getPlayer()), this));
		Containers.Players().addListener(new ConsumerEventListener(Containers.Players(), EventType.ON_PLAYER_LOGOUT, (OnPlayerLogout event) -> onLogout(event.getPlayer()), this));
		Containers.Players().addListener(new ConsumerEventListener(Containers.Players(), EventType.ON_PLAYER_PK_CHANGED, (OnPlayerPKChanged event) -> scheduleStateCheck(event.getPlayer()), this));
		Containers.Players().addListener(new ConsumerEventListener(Containers.Players(), EventType.ON_PLAYER_FAME_CHANGED, (OnPlayerFameChanged event) -> scheduleStateCheck(event.getPlayer()), this));
		Containers.Players().addListener(new ConsumerEventListener(Containers.Players(), EventType.ON_PLAYER_LEVEL_CHANGED, (OnPlayerLevelChanged event) -> onLevelChanged(event), this));
		Containers.Players().addListener(new ConsumerEventListener(Containers.Players(), EventType.ON_CREATURE_DEATH, (OnCreatureDeath event) -> onPlayerDeath(event), this));
		Containers.Players().addListener(new ConsumerEventListener(Containers.Players(), EventType.ON_PLAYER_PRESS_TUTORIAL_MARK, (OnPlayerPressTutorialMark event) -> onQuestionMark(event.getPlayer(), event.getMarkId()), this));
		Containers.Monsters().addListener(new ConsumerEventListener(Containers.Monsters(), EventType.ON_ATTACKABLE_KILL, (OnAttackableKill event) -> onMonsterKill(event), this));

		// Messengers are seen only by the player they came for (and GMs).
		final Set<Integer> messengerIds = new HashSet<>();
		for (HiddenQuestDefinition quest : HiddenQuestData.getInstance().getQuests())
		{
			messengerIds.add(quest.getMessengerNpcId());
		}
		for (int npcId : messengerIds)
		{
			final NpcTemplate template = NpcData.getInstance().getTemplate(npcId);
			if (template != null)
			{
				template.addListener(new FunctionEventListener(template, EventType.ON_NPC_CAN_BE_SEEN, (OnNpcCanBeSeen event) -> new TerminateReturn(canSeeMessenger(event.getNpc(), event.getPlayer()), false, false), this));
			}
		}

		final long interval = HiddenQuestConfig.CHECK_INTERVAL * 1000L;
		ThreadPool.scheduleAtFixedRate(this::checkPlayers, interval, interval);
		LOGGER.info(getClass().getSimpleName() + ": Hidden quests are enabled (" + HiddenQuestData.getInstance().getQuests().size() + " quests).");
	}

	// ---------------------------------------------------------------------------------------------
	// Conditions
	// ---------------------------------------------------------------------------------------------

	private void onLogin(Player player)
	{
		final int nameColor = player.getVariables().getInt(VAR_NAME_COLOR, -1);
		if ((nameColor >= 0) && !player.isGM())
		{
			player.getAppearance().setNameColor(nameColor);
			player.broadcastUserInfo();
		}
		scheduleStateCheck(player);
	}

	private void onLogout(Player player)
	{
		final HiddenQuestSession session = _sessions.get(player.getObjectId());
		if (session != null)
		{
			endSession(session, null, "logged out");
		}
		final MessengerVisit visit = _visits.get(player.getObjectId());
		if (visit != null)
		{
			dismiss(visit, null, HiddenQuestConfig.MESSENGER_RETRY_DELAY);
		}
	}

	private void scheduleStateCheck(Player player)
	{
		// The PK/fame events fire before the new value is stored.
		ThreadPool.schedule(() -> checkStateTriggers(player), 1000);
	}

	private void onLevelChanged(OnPlayerLevelChanged event)
	{
		final Player player = event.getPlayer();
		if (event.getNewLevel() <= event.getOldLevel())
		{
			return;
		}

		final PlayerVariables vars = player.getVariables();
		final int maxLevel = Math.max(vars.getInt(VAR_MAX_LEVEL, 0), event.getOldLevel());
		if (event.getNewLevel() > maxLevel)
		{
			vars.set(VAR_MAX_LEVEL, event.getNewLevel());
			addToCounters(player, HiddenTriggerType.LEVEL_STREAK, null, event.getNewLevel() - maxLevel);
		}
	}

	private void onPlayerDeath(OnCreatureDeath event)
	{
		if (!event.getTarget().isPlayer())
		{
			return;
		}

		final Player player = event.getTarget().asPlayer();
		final HiddenQuestSession session = _sessions.get(player.getObjectId());
		if ((session != null) && (session.getTask() != null))
		{
			ThreadPool.execute(() -> session.getTask().handlePlayerDeath(player));
		}

		// Deaths in arenas, Olympiad, duels, events and instances don't count.
		if (player.isInsideZone(ZoneId.PVP) || player.isInsideZone(ZoneId.PVP_SPOT) || player.isInOlympiadMode() || player.isInDuel() || player.isOnEvent() || (player.getInstanceId() != 0))
		{
			return;
		}

		addToCounters(player, HiddenTriggerType.DEATHS, null, 1);
		final String streakKey = "HQ_C_" + HiddenTriggerType.LEVEL_STREAK.name();
		if (player.getVariables().getLong(streakKey, 0) > 0)
		{
			player.getVariables().set(streakKey, 0L);
		}

		// Bounty: the killer of a player with karma.
		final Creature killer = event.getAttacker();
		final Player killerPlayer = killer != null ? killer.asPlayer() : null;
		if ((killerPlayer != null) && (killerPlayer != player) && (player.getKarma() > 0))
		{
			addToCounters(killerPlayer, HiddenTriggerType.KILL_KARMA_PLAYER, null, 1);
		}
	}

	private void onMonsterKill(OnAttackableKill event)
	{
		final Player player = event.getAttacker();
		final Attackable monster = event.getTarget();
		if ((player == null) || (monster == null) || monster.getVariables().getBoolean(AbstractHiddenTask.TASK_NPC_VAR, false))
		{
			return;
		}

		final boolean raid = monster.isRaid() && !monster.isMinion();
		if (!raid && (monster.getLevel() < (player.getLevel() - HiddenQuestConfig.KILL_LEVEL_GAP)))
		{
			return;
		}

		if (raid)
		{
			addToCounters(player, HiddenTriggerType.KILL_RAID, null, 1);
		}
		if (monster.isUndead())
		{
			addToCounters(player, HiddenTriggerType.KILL_UNDEAD, null, 1);
		}
		if (GameTimeTaskManager.getInstance().isNight())
		{
			addToCounters(player, HiddenTriggerType.KILL_NIGHT, null, 1);
		}
		if (!player.isInParty())
		{
			addToCounters(player, HiddenTriggerType.KILL_SOLO, null, 1);
		}
		addToCounters(player, HiddenTriggerType.KILL_NAMED, monster.getName(), 1);
	}

	/**
	 * Adds to the counters of every quest of a type the player can still get, then queues the quests whose counter is reached.
	 * @param player the player
	 * @param type the counter type
	 * @param name for {@link HiddenTriggerType#KILL_NAMED}, the monster name
	 * @param amount the amount to add
	 */
	private void addToCounters(Player player, HiddenTriggerType type, String name, long amount)
	{
		final PlayerVariables vars = player.getVariables();
		final Set<String> updated = new HashSet<>();
		for (HiddenQuestDefinition quest : HiddenQuestData.getInstance().getQuests())
		{
			final HiddenTrigger trigger = quest.getTrigger();
			if ((trigger.getType() != type) || isDone(player, quest.getId()) || isPending(player, quest.getId()))
			{
				continue;
			}
			if ((type == HiddenTriggerType.KILL_NAMED) && !trigger.matchesName(name))
			{
				continue;
			}

			final String key = trigger.getCounterKey();
			if (updated.add(key))
			{
				vars.set(key, vars.getLong(key, 0) + amount);
			}
			if (vars.getLong(key, 0) >= trigger.getCount())
			{
				addPending(player, quest, false);
			}
		}
	}

	/**
	 * @param player the player
	 * @param trigger a condition
	 * @return how far the player is towards the condition
	 */
	public long getProgress(Player player, HiddenTrigger trigger)
	{
		switch (trigger.getType())
		{
			case PK_COUNT:
			{
				return player.getPkKills();
			}
			case PVP_COUNT:
			{
				return player.getPvpKills();
			}
			case FAME:
			{
				return player.getFame();
			}
			case ADENA:
			{
				return player.getAdena();
			}
			case TOWNS_VISITED:
			{
				final long visited = player.getVariables().getLong(VAR_TOWNS, 0);
				int count = 0;
				for (int town : trigger.getTowns())
				{
					if ((town >= 0) && (town < 64) && ((visited & (1L << town)) != 0))
					{
						count++;
					}
				}
				return count;
			}
			default:
			{
				return player.getVariables().getLong(trigger.getCounterKey(), 0);
			}
		}
	}

	private void checkStateTriggers(Player player)
	{
		if ((player == null) || !player.isOnline())
		{
			return;
		}

		for (HiddenQuestDefinition quest : HiddenQuestData.getInstance().getQuests())
		{
			final HiddenTrigger trigger = quest.getTrigger();
			if (!trigger.getType().isCounter() && (getProgress(player, trigger) >= trigger.getCount()) && !isDone(player, quest.getId()) && !isPending(player, quest.getId()))
			{
				addPending(player, quest, false);
			}
		}
	}

	private void checkPlayers()
	{
		for (Player player : World.getInstance().getPlayers())
		{
			try
			{
				if (!player.isOnline() || player.isInOfflineMode() || player.isOfflinePlay())
				{
					continue;
				}

				final TownZone town = ZoneManager.getInstance().getZone(player, TownZone.class);
				if ((town != null) && (town.getTownId() >= 0) && (town.getTownId() < 64))
				{
					final long visited = player.getVariables().getLong(VAR_TOWNS, 0);
					final long bit = 1L << town.getTownId();
					if ((visited & bit) == 0)
					{
						player.getVariables().set(VAR_TOWNS, visited | bit);
					}
				}

				checkStateTriggers(player);
				trySendMessenger(player);
			}
			catch (Exception e)
			{
				LOGGER.log(Level.WARNING, getClass().getSimpleName() + ": Error while checking " + player.getName() + ".", e);
			}
		}
	}

	// ---------------------------------------------------------------------------------------------
	// Player state
	// ---------------------------------------------------------------------------------------------

	public boolean isDone(Player player, int questId)
	{
		return player.getVariables().getInt(VAR_DONE + questId, 0) != 0;
	}

	public boolean isPending(Player player, int questId)
	{
		return getPending(player).contains(questId);
	}

	/**
	 * @param player the player
	 * @return the queued quest ids, oldest first
	 */
	public List<Integer> getPending(Player player)
	{
		final List<Integer> result = new ArrayList<>();
		final String value = player.getVariables().getString(VAR_PENDING, "");
		for (String id : value.split(","))
		{
			if (!id.isEmpty())
			{
				try
				{
					result.add(Integer.parseInt(id));
				}
				catch (NumberFormatException e)
				{
					// Ignore garbage.
				}
			}
		}
		return result;
	}

	private void setPending(Player player, List<Integer> pending)
	{
		if (pending.isEmpty())
		{
			player.getVariables().remove(VAR_PENDING);
			return;
		}

		final StringBuilder sb = new StringBuilder();
		for (int id : pending)
		{
			if (sb.length() > 0)
			{
				sb.append(',');
			}
			sb.append(id);
		}
		player.getVariables().set(VAR_PENDING, sb.toString());
	}

	/**
	 * Queues a quest for a messenger visit.
	 * @param player the player
	 * @param quest the quest
	 * @param immediate {@code true} to send the messenger at the next check (GM), else after the configured random delay
	 * @return {@code true} if queued
	 */
	public synchronized boolean addPending(Player player, HiddenQuestDefinition quest, boolean immediate)
	{
		final List<Integer> pending = getPending(player);
		if (pending.contains(quest.getId()) || isDone(player, quest.getId()))
		{
			return false;
		}

		pending.add(quest.getId());
		setPending(player, pending);

		final long now = System.currentTimeMillis();
		final long delay = immediate ? 0 : Rnd.get(HiddenQuestConfig.MESSENGER_DELAY_MIN, HiddenQuestConfig.MESSENGER_DELAY_MAX) * 1000L;
		final long next = player.getVariables().getLong(VAR_NEXT_VISIT, 0);
		player.getVariables().set(VAR_NEXT_VISIT, immediate ? now : Math.max(next, now + delay));
		log(player.getName() + " met the hidden condition of quest " + quest.getId() + " (" + quest.getName() + ", " + quest.getTrigger() + ").");
		return true;
	}

	private void removePending(Player player, int questId)
	{
		final List<Integer> pending = getPending(player);
		if (pending.remove(Integer.valueOf(questId)))
		{
			setPending(player, pending);
		}
	}

	private static void setNextVisit(Player player, int seconds)
	{
		player.getVariables().set(VAR_NEXT_VISIT, System.currentTimeMillis() + (seconds * 1000L));
	}

	// ---------------------------------------------------------------------------------------------
	// Messenger
	// ---------------------------------------------------------------------------------------------

	private boolean isSafe(Player player)
	{
		return !player.isDead() && (player.getLevel() >= HiddenQuestConfig.MIN_LEVEL) && !player.isInCombat() && (player.getPvpFlag() == 0) && (player.getInstanceId() == 0) //
			&& !player.isInOlympiadMode() && !player.isInSiege() && !player.isInDuel() && !player.isOnEvent() && !player.isTeleporting() && !player.isFakeDeath() //
			&& !player.isInsideZone(ZoneId.PVP) && !player.isInsideZone(ZoneId.PVP_SPOT) && !player.isInsideZone(ZoneId.SIEGE) && !player.isInsideZone(ZoneId.JAIL) //
			&& !player.isInBoat() && !player.isInAirShip() && !player.inObserverMode() && !player.isInStoreMode() && !player.isMounted() && !player.isFlying() //
			&& !player.isCursedWeaponEquipped() && !player.isInOfflineMode() && !player.isOfflinePlay();
	}

	private void trySendMessenger(Player player)
	{
		if (_sessions.containsKey(player.getObjectId()) || _visits.containsKey(player.getObjectId()))
		{
			return;
		}

		final List<Integer> pending = getPending(player);
		if (pending.isEmpty() || (System.currentTimeMillis() < player.getVariables().getLong(VAR_NEXT_VISIT, 0)) || !isSafe(player))
		{
			return;
		}

		HiddenQuestDefinition quest = null;
		for (int id : pending)
		{
			quest = HiddenQuestData.getInstance().getQuest(id);
			if (quest != null)
			{
				break;
			}
		}
		if (quest == null)
		{
			return;
		}

		sendMessenger(player, quest);
	}

	private boolean sendMessenger(Player player, HiddenQuestDefinition quest)
	{
		final Location location = AbstractHiddenTask.findPoint(player, 400, 600, true, player.getInstanceId());
		final Npc npc = Quest.addSpawn(quest.getMessengerNpcId(), location.getX(), location.getY(), location.getZ(), LocationUtil.calculateHeadingFrom(location, player), false, 0, false, player.getInstanceId());
		if (npc == null)
		{
			LOGGER.warning(getClass().getSimpleName() + ": Could not spawn messenger " + quest.getMessengerNpcId() + " of quest " + quest.getId() + ".");
			setNextVisit(player, HiddenQuestConfig.MESSENGER_RETRY_DELAY);
			return false;
		}

		// Spawned invisible to everyone; now show it to its player.
		_messengerOwners.put(npc.getObjectId(), player.getObjectId());
		npc.setRandomWalking(false);
		npc.setWalking();
		if (HiddenQuestConfig.MESSENGER_VISUAL_EFFECT != AbnormalVisualEffect.NONE)
		{
			npc.startAbnormalVisualEffect(false, HiddenQuestConfig.MESSENGER_VISUAL_EFFECT);
		}
		npc.broadcastInfo();

		final MessengerVisit visit = new MessengerVisit(player.getObjectId(), quest, npc);
		_visits.put(player.getObjectId(), visit);
		visit._task = ThreadPool.scheduleAtFixedRate(() -> updateVisit(visit), 1000, 1000);
		log("Messenger of quest " + quest.getId() + " sent to " + player.getName() + ".");
		return true;
	}

	private void updateVisit(MessengerVisit visit)
	{
		try
		{
			final Player player = World.getInstance().getPlayer(visit._playerObjectId);
			if ((player == null) || !player.isOnline() || !visit._npc.isSpawned())
			{
				dismiss(visit, null, HiddenQuestConfig.MESSENGER_RETRY_DELAY);
				return;
			}

			final Npc npc = visit._npc;
			final double distance = npc.calculateDistance2D(player);
			if ((distance > LOST_DISTANCE) || (player.getInstanceId() != npc.getInstanceId()) || player.isDead())
			{
				dismiss(visit, null, HiddenQuestConfig.MESSENGER_RETRY_DELAY);
				return;
			}

			if (!visit._arrived)
			{
				if (distance <= APPROACH_STOP)
				{
					visit._arrived = true;
					visit._waitUntil = System.currentTimeMillis() + (HiddenQuestConfig.MESSENGER_WAIT_TIME * 1000L);
					npc.getAI().setIntention(Intention.ACTIVE);
					npc.setHeading(LocationUtil.calculateHeadingFrom(npc, player));
					npc.broadcastInfo();
					say(player, npc, visit._definition.getText("greeting", "You there. A word, if you please."));
					player.sendPacket(new TutorialShowQuestionMark(QUESTION_MARK_ID));
				}
				else
				{
					walkTo(npc, player);
				}
				return;
			}

			if (System.currentTimeMillis() > visit._waitUntil)
			{
				dismiss(visit, visit._definition.getText("ignored", "Another time, then."), HiddenQuestConfig.MESSENGER_RETRY_DELAY);
				return;
			}

			if (distance > FOLLOW_DISTANCE)
			{
				walkTo(npc, player);
			}
		}
		catch (Exception e)
		{
			LOGGER.log(Level.WARNING, getClass().getSimpleName() + ": Messenger error.", e);
			dismiss(visit, null, HiddenQuestConfig.MESSENGER_RETRY_DELAY);
		}
	}

	/**
	 * Walks a messenger up to the player, stopping a step short.
	 * @param npc the messenger
	 * @param player the player
	 */
	private static void walkTo(Npc npc, Player player)
	{
		final double dx = npc.getX() - player.getX();
		final double dy = npc.getY() - player.getY();
		final double length = Math.max(1, Math.sqrt((dx * dx) + (dy * dy)));
		final int x = player.getX() + (int) ((dx / length) * 60);
		final int y = player.getY() + (int) ((dy / length) * 60);

		// Walks up with dignity, runs to keep up with a player who walks off.
		if (length > 700)
		{
			npc.setRunning();
		}
		else
		{
			npc.setWalking();
		}
		npc.getAI().setIntention(Intention.MOVE_TO, new Location(x, y, player.getZ()));
	}

	/**
	 * Sends a messenger away.
	 * @param visit the visit
	 * @param line what it says as it leaves, may be {@code null}
	 * @param retryDelay seconds before a messenger comes again, 0 to leave the next visit as it is
	 */
	private void dismiss(MessengerVisit visit, String line, int retryDelay)
	{
		if (!_visits.remove(visit._playerObjectId, visit))
		{
			return;
		}
		if (visit._task != null)
		{
			visit._task.cancel(false);
		}

		final Player player = World.getInstance().getPlayer(visit._playerObjectId);
		if ((player != null) && (retryDelay > 0))
		{
			setNextVisit(player, retryDelay);
		}

		final Npc npc = visit._npc;
		if (!npc.isSpawned())
		{
			_messengerOwners.remove(npc.getObjectId());
			return;
		}

		if ((player != null) && (line != null))
		{
			say(player, npc, line);
		}
		npc.getAI().setIntention(Intention.MOVE_TO, AbstractHiddenTask.findPoint(npc, 300, 400, true, npc.getInstanceId()));
		ThreadPool.schedule(() ->
		{
			npc.deleteMe();
			_messengerOwners.remove(npc.getObjectId());
		}, 5000);
	}

	private boolean canSeeMessenger(Npc npc, Player player)
	{
		final Integer owner = _messengerOwners.get(npc.getObjectId());
		return (owner != null) && ((owner == player.getObjectId()) || player.isGM());
	}

	private static void say(Player player, Npc npc, String text)
	{
		if ((text != null) && !text.isEmpty())
		{
			player.sendPacket(new CreatureSay(npc, ChatType.NPC_GENERAL, npc.getName(), text));
		}
	}

	/**
	 * @param player the player
	 * @param npc a messenger
	 * @return the quest the messenger offers to this player, {@code null} if it is not theirs (or no longer offering)
	 */
	public HiddenQuestDefinition getOffer(Player player, Npc npc)
	{
		final MessengerVisit visit = getVisit(player, npc);
		return visit != null ? visit._definition : null;
	}

	/**
	 * @param player the player
	 * @param npc the NPC the player's dialog came from (it may be another NPC standing nearby when the dialog was opened from the question mark)
	 * @return the player's messenger visit if it is that NPC or close enough to talk, else {@code null}
	 */
	private MessengerVisit getVisit(Player player, Npc npc)
	{
		final MessengerVisit visit = _visits.get(player.getObjectId());
		if ((visit == null) || !visit._npc.isSpawned())
		{
			return null;
		}
		return (visit._npc == npc) || player.isInsideRadius2D(visit._npc, Npc.INTERACTION_DISTANCE) ? visit : null;
	}

	/**
	 * Shows the messenger's offer.
	 * @param player the player
	 * @param npc the messenger
	 * @return {@code true} if the messenger is theirs
	 */
	public boolean showOffer(Player player, Npc npc)
	{
		final HiddenQuestDefinition quest = getOffer(player, npc);
		if (quest == null)
		{
			return false;
		}

		// Talking resets the patience.
		final MessengerVisit visit = getVisit(player, npc);
		visit._arrived = true;
		visit._waitUntil = System.currentTimeMillis() + (HiddenQuestConfig.MESSENGER_WAIT_TIME * 1000L);

		final Npc messenger = visit._npc;
		final NpcHtmlMessage html = new NpcHtmlMessage(messenger.getObjectId(), getHtml(player, "offer.html"));
		html.replace("%objectId%", String.valueOf(messenger.getObjectId()));
		html.replace("%name%", messenger.getName());
		html.replace("%quest%", quest.getName());
		html.replace("%text%", quest.getText("offer", "I have a task for you."));
		html.replace("%forfeit%", HiddenQuestConfig.ALLOW_FORFEIT ? getHtml(player, "offer_forfeit.html") : "");
		player.sendPacket(html);
		return true;
	}

	/**
	 * The player accepts the messenger's quest: the task starts.
	 * @param player the player
	 * @param npc the messenger
	 * @return {@code null} if started, else why not
	 */
	public String accept(Player player, Npc npc)
	{
		final HiddenQuestDefinition quest = getOffer(player, npc);
		if (quest == null)
		{
			return "This offer is not for you.";
		}
		if (_sessions.containsKey(player.getObjectId()))
		{
			return "You are already bound to another task.";
		}
		if (!isSafe(player))
		{
			return "Not now - finish what you are doing first.";
		}

		final MessengerVisit visit = getVisit(player, npc);
		if (needsOpenGround(quest) && (player.isInsideZone(ZoneId.PEACE) || player.isInsideZone(ZoneId.TOWN)))
		{
			// The messenger follows the player out of town.
			if (visit != null)
			{
				visit._waitUntil = System.currentTimeMillis() + (HiddenQuestConfig.MESSENGER_WAIT_TIME * 1000L);
				say(player, visit._npc, quest.getText("notInTown", "Not here, among all these people. Walk out of town with me, then talk to me again."));
			}
			return "Leave the town first; the messenger will follow you.";
		}

		final String problem = startSession(player, quest);
		if (problem != null)
		{
			if (visit != null)
			{
				dismiss(visit, quest.getText("ignored", "Another time, then."), HiddenQuestConfig.FAIL_RETRY_DELAY);
			}
			return "The task could not begin (" + problem + ").";
		}

		if (visit != null)
		{
			dismiss(visit, quest.getText("accepted", "Go, then. I will know when it is done."), 0);
		}

		final Npc messenger = visit != null ? visit._npc : npc;
		final NpcHtmlMessage html = new NpcHtmlMessage(messenger.getObjectId(), getHtml(player, "briefing.html"));
		html.replace("%name%", messenger.getName());
		html.replace("%quest%", quest.getName());
		html.replace("%text%", quest.getText("briefing", ""));
		html.replace("%time%", String.valueOf(getTimeLimit(quest) / 60));
		player.sendPacket(html);
		return null;
	}

	/**
	 * The player asks the messenger to come back later.
	 * @param player the player
	 * @param npc the messenger
	 */
	public void postpone(Player player, Npc npc)
	{
		final MessengerVisit visit = getVisit(player, npc);
		if (visit != null)
		{
			dismiss(visit, visit._definition.getText("postponed", "As you wish. I will find you again."), HiddenQuestConfig.MESSENGER_RETRY_DELAY);
		}
	}

	/**
	 * The player refuses the messenger's quest forever.
	 * @param player the player
	 * @param npc the messenger
	 * @return {@code true} if refused
	 */
	public boolean forfeit(Player player, Npc npc)
	{
		final MessengerVisit visit = getVisit(player, npc);
		if (!HiddenQuestConfig.ALLOW_FORFEIT || (visit == null))
		{
			return false;
		}

		player.getVariables().set(VAR_DONE + visit._definition.getId(), -1);
		removePending(player, visit._definition.getId());
		dismiss(visit, visit._definition.getText("forfeited", "So be it. You will not see me again."), HiddenQuestConfig.MESSENGER_RETRY_DELAY);
		log(player.getName() + " refused hidden quest " + visit._definition.getId() + ".");
		return true;
	}

	private void onQuestionMark(Player player, int markId)
	{
		if (markId != QUESTION_MARK_ID)
		{
			return;
		}

		final HiddenQuestSession session = _sessions.get(player.getObjectId());
		if ((session != null) && (session.getTask() != null))
		{
			session.getTask().handleQuestionMark(player);
			return;
		}

		final MessengerVisit visit = _visits.get(player.getObjectId());
		if ((visit != null) && visit._npc.isSpawned())
		{
			if (player.calculateDistance2D(visit._npc) > (Npc.INTERACTION_DISTANCE - 50))
			{
				player.sendMessage(visit._npc.getName() + " is waiting for you. Walk up to them.");
				return;
			}
			player.setLastQuestNpcObject(visit._npc.getObjectId());
			showOffer(player, visit._npc);
		}
	}

	// ---------------------------------------------------------------------------------------------
	// Sessions
	// ---------------------------------------------------------------------------------------------

	/**
	 * @param quest a quest
	 * @return {@code true} if its task fights where it starts, so it can't start inside a town
	 */
	private static boolean needsOpenGround(HiddenQuestDefinition quest)
	{
		switch (quest.getTaskType())
		{
			case ECHO_GAUNTLET:
			case ESCORT:
			case VIGIL:
			{
				return true;
			}
			default:
			{
				return false;
			}
		}
	}

	private static int getTimeLimit(HiddenQuestDefinition quest)
	{
		return quest.getTimeLimit() > 0 ? quest.getTimeLimit() : HiddenQuestConfig.TASK_TIME_LIMIT;
	}

	private static AbstractHiddenTask createTask(HiddenQuestSession session)
	{
		switch (session.getDefinition().getTaskType())
		{
			case PILGRIMAGE:
			{
				return new PilgrimageTask(session);
			}
			case ECHO_GAUNTLET:
			{
				return new EchoGauntletTask(session);
			}
			case HUNTED:
			{
				return new HuntedTask(session);
			}
			case CHASE:
			{
				return new ChaseTask(session);
			}
			case ESCORT:
			{
				return new EscortTask(session);
			}
			case VIGIL:
			{
				return new VigilTask(session);
			}
			case RIDDLE:
			{
				return new RiddleTask(session);
			}
		}
		return null;
	}

	private String startSession(Player player, HiddenQuestDefinition quest)
	{
		final HiddenQuestSession session = new HiddenQuestSession(player, quest, getTimeLimit(quest));
		final AbstractHiddenTask task = createTask(session);
		if (task == null)
		{
			return "unknown task";
		}
		session.setTask(task);
		_sessions.put(player.getObjectId(), session);

		String problem;
		try
		{
			problem = task.start(player);
		}
		catch (Exception e)
		{
			LOGGER.log(Level.WARNING, getClass().getSimpleName() + ": Could not start hidden quest " + quest.getId() + ".", e);
			problem = "error";
		}

		if (problem != null)
		{
			session.finish();
			task.cleanup();
			_sessions.remove(player.getObjectId());
			log("Hidden quest " + quest.getId() + " could not start for " + player.getName() + ": " + problem + ".");
			return problem;
		}

		session.setTickTask(ThreadPool.scheduleAtFixedRate(() -> tick(session), 1000, 1000));
		log(player.getName() + " started hidden quest " + quest.getId() + " (" + quest.getName() + ").");
		return null;
	}

	private void tick(HiddenQuestSession session)
	{
		if (session.isFinished())
		{
			return;
		}

		try
		{
			final Player player = session.getPlayer();
			if (player == null)
			{
				endSession(session, null, "player gone");
				return;
			}
			if (System.currentTimeMillis() > session.getDeadline())
			{
				failTask(session, session.getDefinition().getText("failTime", "Time has run out."));
				return;
			}

			final int second = session.nextTick();
			session.getTask().handleTick(player, second);
			if (!session.isFinished() && ((session.getSecondsLeft() == 300) || (session.getSecondsLeft() == 60)))
			{
				player.sendPacket(new ExShowScreenMessage("Time left: " + (session.getSecondsLeft() / 60) + " min", ExShowScreenMessage.TOP_CENTER, 4000));
			}
		}
		catch (Exception e)
		{
			LOGGER.log(Level.WARNING, getClass().getSimpleName() + ": Error in hidden quest " + session.getDefinition().getId() + " of " + session.getPlayerName() + ".", e);
			failTask(session, "Something went wrong.");
		}
	}

	/**
	 * Ends a session without completing it.
	 * @param session the session
	 * @param message what the player is told, {@code null} for nothing
	 * @param reason the log reason
	 */
	private void endSession(HiddenQuestSession session, String message, String reason)
	{
		if (!session.finish())
		{
			return;
		}
		if (session.getTickTask() != null)
		{
			session.getTickTask().cancel(false);
		}
		session.getTask().cleanup();
		_sessions.remove(session.getPlayerObjectId(), session);

		final Player player = World.getInstance().getPlayer(session.getPlayerObjectId());
		if (player != null)
		{
			setNextVisit(player, HiddenQuestConfig.FAIL_RETRY_DELAY);
			if ((message != null) && player.isOnline())
			{
				player.sendPacket(new ExShowScreenMessage(message, ExShowScreenMessage.MIDDLE_CENTER, 6000));
				player.sendMessage(session.getDefinition().getName() + ": " + message + " Perhaps you will be given another chance.");
			}
		}
		log(session.getPlayerName() + " did not finish hidden quest " + session.getDefinition().getId() + " (" + reason + ").");
	}

	/**
	 * Fails a running task. The quest stays queued and its messenger comes back later.
	 * @param session the session
	 * @param reason what the player is told
	 */
	public void failTask(HiddenQuestSession session, String reason)
	{
		endSession(session, reason, reason);
	}

	/**
	 * Completes a running task: rewards and records it.
	 * @param session the session
	 */
	public void completeTask(HiddenQuestSession session)
	{
		if (!session.finish())
		{
			return;
		}
		if (session.getTickTask() != null)
		{
			session.getTickTask().cancel(false);
		}
		session.getTask().cleanup();
		_sessions.remove(session.getPlayerObjectId(), session);

		final HiddenQuestDefinition quest = session.getDefinition();
		final Player player = session.getPlayer();
		if (player == null)
		{
			log(session.getPlayerName() + " completed hidden quest " + quest.getId() + " while offline - not rewarded.");
			return;
		}

		player.getVariables().set(VAR_DONE + quest.getId(), 1);
		removePending(player, quest.getId());
		setNextVisit(player, HiddenQuestConfig.MESSENGER_DELAY_MAX);
		final String rewards = giveRewards(player, quest);

		player.sendPacket(new ExShowScreenMessage(quest.getName() + " - complete!", ExShowScreenMessage.MIDDLE_CENTER, 6000));
		final NpcHtmlMessage html = new NpcHtmlMessage(0, getHtml(player, "complete.html"));
		html.replace("%quest%", quest.getName());
		html.replace("%text%", quest.getText("success", "It is done."));
		html.replace("%rewards%", rewards);
		player.sendPacket(html);

		// The messenger comes to say goodbye.
		final Location location = AbstractHiddenTask.findPoint(player, 80, 140, true, player.getInstanceId());
		final Npc npc = Quest.addSpawn(quest.getMessengerNpcId(), location.getX(), location.getY(), location.getZ(), LocationUtil.calculateHeadingFrom(location, player), false, 0, false, player.getInstanceId());
		if (npc != null)
		{
			_messengerOwners.put(npc.getObjectId(), player.getObjectId());
			npc.setRandomWalking(false);
			npc.setTalkable(false);
			npc.broadcastInfo();
			say(player, npc, quest.getText("farewell", "Well done."));
			ThreadPool.schedule(() ->
			{
				npc.deleteMe();
				_messengerOwners.remove(npc.getObjectId());
			}, 15000);
		}
		log(player.getName() + " completed hidden quest " + quest.getId() + " (" + quest.getName() + ").");
	}

	private String giveRewards(Player player, HiddenQuestDefinition quest)
	{
		final StringBuilder sb = new StringBuilder();
		boolean userInfo = false;
		for (HiddenReward reward : quest.getRewards())
		{
			if (!reward.isFor(player.getLevel()))
			{
				continue;
			}

			switch (reward.getType())
			{
				case ITEM:
				{
					final ItemTemplate item = ItemData.getInstance().getTemplate(reward.getId());
					if (item == null)
					{
						continue;
					}
					player.addItem(ItemProcessType.REWARD, reward.getId(), reward.getCount(), null, true);
					line(sb, item.getName() + (reward.getCount() > 1 ? " x" + reward.getCount() : ""));
					break;
				}
				case EXP:
				{
					player.addExpAndSp(reward.getCount(), 0);
					line(sb, reward.getCount() + " experience");
					break;
				}
				case SP:
				{
					player.addExpAndSp(0, reward.getCount());
					line(sb, reward.getCount() + " SP");
					break;
				}
				case TITLE:
				{
					player.setTitle(reward.getValue());
					player.broadcastTitleInfo();
					line(sb, "The title \"" + reward.getValue() + "\"");
					break;
				}
				case TITLE_COLOR:
				{
					final int[] rgb = parseColor(reward.getValue());
					if (rgb != null)
					{
						player.getAppearance().setTitleColor(rgb[0], rgb[1], rgb[2]);
						userInfo = true;
						line(sb, "<font color=\"" + reward.getValue() + "\">A new title colour</font>");
					}
					break;
				}
				case NAME_COLOR:
				{
					final int[] rgb = parseColor(reward.getValue());
					if (rgb != null)
					{
						player.getAppearance().setNameColor(rgb[0], rgb[1], rgb[2]);
						player.getVariables().set(VAR_NAME_COLOR, player.getAppearance().getNameColor());
						userInfo = true;
						line(sb, "<font color=\"" + reward.getValue() + "\">A new name colour</font>");
					}
					break;
				}
				case CLEAR_KARMA:
				{
					if (player.getKarma() > 0)
					{
						player.setKarma(0);
						line(sb, "Your karma is washed away");
					}
					break;
				}
				case REDUCE_PK:
				{
					if (player.getPkKills() > 0)
					{
						final int reduction = (int) Math.min(player.getPkKills(), reward.getCount());
						player.setPkKills(player.getPkKills() - reduction);
						userInfo = true;
						line(sb, "PK count -" + reduction);
					}
					break;
				}
			}
		}

		if (userInfo)
		{
			player.broadcastUserInfo();
		}
		return sb.length() == 0 ? "Nothing but the memory." : sb.toString();
	}

	private static void line(StringBuilder sb, String text)
	{
		sb.append("<font color=\"LEVEL\">&#8226;</font> ").append(text).append("<br1>");
	}

	/**
	 * @param value a colour as RRGGBB
	 * @return red, green and blue, {@code null} if invalid
	 */
	private static int[] parseColor(String value)
	{
		if ((value == null) || (value.length() != 6))
		{
			return null;
		}
		try
		{
			final int rgb = Integer.parseInt(value, 16);
			return new int[]
			{
				(rgb >> 16) & 0xFF,
				(rgb >> 8) & 0xFF,
				rgb & 0xFF
			};
		}
		catch (NumberFormatException e)
		{
			return null;
		}
	}

	private static String getHtml(Player player, String file)
	{
		final String html = HtmCache.getInstance().getHtm(player, HTML_PATH + file);
		return html != null ? html : "<html><body>" + file + " is missing.</body></html>";
	}

	private static void log(String message)
	{
		if (HiddenQuestConfig.LOGGING)
		{
			LOGGER.info("HiddenQuests: " + message);
		}
	}

	// ---------------------------------------------------------------------------------------------
	// GM tools
	// ---------------------------------------------------------------------------------------------

	/**
	 * @param player the player
	 * @return the running session, {@code null} if none
	 */
	public HiddenQuestSession getSession(Player player)
	{
		return _sessions.get(player.getObjectId());
	}

	/**
	 * @param player the player
	 * @return the quest whose messenger is with the player, {@code null} if none
	 */
	public HiddenQuestDefinition getVisitingQuest(Player player)
	{
		final MessengerVisit visit = _visits.get(player.getObjectId());
		return visit != null ? visit._definition : null;
	}

	/**
	 * Queues a quest as if its condition were met and sends the messenger at the next check.
	 * @param player the player
	 * @param quest the quest
	 * @return {@code true} if queued
	 */
	public boolean adminTrigger(Player player, HiddenQuestDefinition quest)
	{
		player.getVariables().remove(VAR_DONE + quest.getId());
		final boolean queued = addPending(player, quest, true);
		player.getVariables().set(VAR_NEXT_VISIT, 0L);
		return queued || isPending(player, quest.getId());
	}

	/**
	 * Sends the messenger of a quest right now, ignoring the safety checks and the queue.
	 * @param player the player
	 * @param quest the quest
	 * @return {@code null} if sent, else why not
	 */
	public String adminSendMessenger(Player player, HiddenQuestDefinition quest)
	{
		if (_sessions.containsKey(player.getObjectId()))
		{
			return "the player has a task in progress";
		}
		final MessengerVisit visit = _visits.get(player.getObjectId());
		if (visit != null)
		{
			dismiss(visit, null, 0);
		}
		if (!isPending(player, quest.getId()))
		{
			adminTrigger(player, quest);
		}
		return sendMessenger(player, quest) ? null : "the messenger could not be spawned";
	}

	/**
	 * Forgets a quest (or all of them) for a player: completion, queue and counters.
	 * @param player the player
	 * @param quest the quest, {@code null} for all
	 */
	public void adminReset(Player player, HiddenQuestDefinition quest)
	{
		final Collection<HiddenQuestDefinition> quests = quest != null ? List.of(quest) : HiddenQuestData.getInstance().getQuests();
		for (HiddenQuestDefinition definition : quests)
		{
			player.getVariables().remove(VAR_DONE + definition.getId());
			removePending(player, definition.getId());
			final String key = definition.getTrigger().getCounterKey();
			if (key != null)
			{
				player.getVariables().remove(key);
			}
		}
		if (quest == null)
		{
			player.getVariables().remove(VAR_TOWNS);
			player.getVariables().remove(VAR_NEXT_VISIT);
		}
	}

	/**
	 * Fails the running task, if any.
	 * @param player the player
	 * @return {@code true} if there was one
	 */
	public boolean adminAbort(Player player)
	{
		final HiddenQuestSession session = _sessions.get(player.getObjectId());
		if (session == null)
		{
			return false;
		}
		failTask(session, "A higher power ended your task.");
		return true;
	}

	/**
	 * Completes the running task, if any.
	 * @param player the player
	 * @return {@code true} if there was one
	 */
	public boolean adminComplete(Player player)
	{
		final HiddenQuestSession session = _sessions.get(player.getObjectId());
		if (session == null)
		{
			return false;
		}
		completeTask(session);
		return true;
	}

	/**
	 * @param player the player
	 * @param quest the quest
	 * @return {@code done}, {@code refused}, {@code active}, {@code queued} or {@code -}
	 */
	public String getState(Player player, HiddenQuestDefinition quest)
	{
		final int done = player.getVariables().getInt(VAR_DONE + quest.getId(), 0);
		if (done > 0)
		{
			return "done";
		}
		if (done < 0)
		{
			return "refused";
		}
		final HiddenQuestSession session = _sessions.get(player.getObjectId());
		if ((session != null) && (session.getDefinition() == quest))
		{
			return "active";
		}
		return isPending(player, quest.getId()) ? "queued" : "-";
	}

	public static HiddenQuestManager getInstance()
	{
		return SingletonHolder.INSTANCE;
	}

	private static class SingletonHolder
	{
		protected static final HiddenQuestManager INSTANCE = new HiddenQuestManager();
	}
}
