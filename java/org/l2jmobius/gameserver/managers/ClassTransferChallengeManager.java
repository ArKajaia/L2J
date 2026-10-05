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
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.l2jmobius.commons.threads.ThreadPool;
import org.l2jmobius.commons.util.Rnd;
import org.l2jmobius.gameserver.ai.AttackableAI;
import org.l2jmobius.gameserver.config.RatesConfig;
import org.l2jmobius.gameserver.config.custom.ClassTransferChallengeConfig;
import org.l2jmobius.gameserver.config.custom.ClassTransferChallengeConfig.DeathBehavior;
import org.l2jmobius.gameserver.config.custom.ClassTransferConfig;
import org.l2jmobius.gameserver.data.sql.ClassTransferChallengeCompletionTable;
import org.l2jmobius.gameserver.data.sql.ClassTransferData;
import org.l2jmobius.gameserver.data.sql.ClassTransferHolder;
import org.l2jmobius.gameserver.data.xml.ClassListData;
import org.l2jmobius.gameserver.data.xml.ClassTransferChallengeData;
import org.l2jmobius.gameserver.data.xml.FakePlayerPvpData;
import org.l2jmobius.gameserver.data.xml.SkillData;
import org.l2jmobius.gameserver.geoengine.GeoEngine;
import org.l2jmobius.gameserver.model.Location;
import org.l2jmobius.gameserver.model.World;
import org.l2jmobius.gameserver.model.actor.Creature;
import org.l2jmobius.gameserver.model.actor.Npc;
import org.l2jmobius.gameserver.model.actor.Player;
import org.l2jmobius.gameserver.model.actor.enums.npc.MonsterArchetype;
import org.l2jmobius.gameserver.model.actor.enums.player.PlayerClass;
import org.l2jmobius.gameserver.model.actor.holders.npc.FakePlayerPvpBuild;
import org.l2jmobius.gameserver.model.actor.holders.npc.FakePlayerPvpBuild.Role;
import org.l2jmobius.gameserver.model.actor.holders.player.ClassInfoHolder;
import org.l2jmobius.gameserver.model.actor.instance.Monster;
import org.l2jmobius.gameserver.model.classtransfer.ChallengeDefinition;
import org.l2jmobius.gameserver.model.classtransfer.ChallengeReward;
import org.l2jmobius.gameserver.model.classtransfer.ChallengeSession;
import org.l2jmobius.gameserver.model.classtransfer.ChallengeSession.Status;
import org.l2jmobius.gameserver.model.classtransfer.ChallengeSpawnHolder;
import org.l2jmobius.gameserver.model.classtransfer.ObjectiveDefinition;
import org.l2jmobius.gameserver.model.classtransfer.TrackedNpc;
import org.l2jmobius.gameserver.model.classtransfer.TrackedNpc.NpcRole;
import org.l2jmobius.gameserver.model.classtransfer.TransferStage;
import org.l2jmobius.gameserver.model.classtransfer.TwistDefinition;
import org.l2jmobius.gameserver.model.classtransfer.objective.AbstractChallengeObjective;
import org.l2jmobius.gameserver.model.classtransfer.objective.ObjectiveFactory;
import org.l2jmobius.gameserver.model.events.Containers;
import org.l2jmobius.gameserver.model.events.EventType;
import org.l2jmobius.gameserver.model.events.holders.actor.creature.OnCreatureDeath;
import org.l2jmobius.gameserver.model.events.holders.actor.player.OnPlayerLogin;
import org.l2jmobius.gameserver.model.events.holders.actor.player.OnPlayerLogout;
import org.l2jmobius.gameserver.model.events.holders.actor.player.OnPlayerSelect;
import org.l2jmobius.gameserver.model.events.listeners.ConsumerEventListener;
import org.l2jmobius.gameserver.model.events.listeners.FunctionEventListener;
import org.l2jmobius.gameserver.model.events.returns.TerminateReturn;
import org.l2jmobius.gameserver.model.hotzone.HotzoneModifier;
import org.l2jmobius.gameserver.model.instancezone.Instance;
import org.l2jmobius.gameserver.model.item.enums.ItemProcessType;
import org.l2jmobius.gameserver.model.script.Quest;
import org.l2jmobius.gameserver.model.skill.Skill;
import org.l2jmobius.gameserver.model.skill.enums.SkillFinishType;
import org.l2jmobius.gameserver.model.variables.PlayerVariables;
import org.l2jmobius.gameserver.network.serverpackets.ExSendUIEvent;
import org.l2jmobius.gameserver.network.serverpackets.ExShowScreenMessage;
import org.l2jmobius.gameserver.network.serverpackets.MagicSkillUse;
import org.l2jmobius.gameserver.taskmanagers.AttackStanceTaskManager;

/**
 * Runs the Alternative Class Transfer Challenges: a solo trial in a private dynamic instance whose completion unlocks the Class Master transfer of its tier (see {@code custom.ClassTransferMaster}). The NPC dialogs live in the {@code custom.ClassTransferChallenge} script; everything that
 * decides progress lives here, in core, so a script reload never orphans a running trial.
 * <p>
 * Every progress event is validated server side: the session must be the challenger's current one and still active, the instance must be the session's own (dynamic instance ids are reused), and the NPC must be one this session spawned and still tracks - so kills in the open world, in
 * another player's instance, or repeated death events never count. Completion and failure are compare-and-set transitions, so each runs once. Timers never keep a {@link Player}.
 * <p>
 * The trials reuse the server's custom systems: the attempt's Omen is a Hot Zone modifier applied to the whole instance, bosses can be multi-phase Wave Challenge monsters with the Survival Arena buffs and enrage, spawn lines can be forced into Thief, Mage or champion monsters,
 * DUEL objectives and the Invader twist are roaming fake players, and rewards pay Survival Arena currency and a Sealed Cache.
 * @author Mobius
 */
public class ClassTransferChallengeManager
{
	private static final Logger LOGGER = Logger.getLogger(ClassTransferChallengeManager.class.getName());

	/** Where the challenger returns to ("x;y;z;heading"), saved at entry so a relog or restart after the instance is gone still gets them out. */
	public static final String RETURN_LOC_VAR = "CTC_RETURN_LOC";
	private static final String FAILS_VAR = "CTC_FAILS_";
	private static final String COOLDOWN_VAR = "CTC_COOLDOWN_";

	private static final String TIMER_TEXT = "Trial Time";
	private static final int ENRAGE_SKILL_ID = 7029; // Survival Arena challenger enrage
	private static final int COMPLETED_RETURN_DELAY = 120000; // Automatic return after clearing, in ms.
	private static final long OMEN_PREVIEW_TIME = 600000; // How long a previewed Omen is kept, in ms.
	private static final int OMEN_DRAIN_TICKS = HotzoneModifierManager.PLAYER_DRAIN_INTERVAL_MS / 1000;
	private static final int INSTANCE_MARGIN = 300; // Extra instance lifetime (s) on top of the time limit and grace period - a backstop, the session timer is authoritative.

	/** instance id -> the session running in it. Static so core hooks ({@code Spawn}, {@code Monster}...) can ask without creating the manager. */
	private static final Map<Integer, ChallengeSession> SESSIONS_BY_INSTANCE = new ConcurrentHashMap<>();

	/** player object id -> the player's session. */
	private final Map<Integer, ChallengeSession> _sessions = new ConcurrentHashMap<>();

	/** player object id -> the Omen shown on the details page, kept so reopening the dialog doesn't reroll it. */
	private final Map<Integer, OmenPreview> _omenPreviews = new ConcurrentHashMap<>();

	/** Players this manager made invulnerable (knockout), so the flag is always given back. */
	private final Set<Integer> _invulGranted = ConcurrentHashMap.newKeySet();

	/** Players whose trial is being created right now. */
	private final Set<Integer> _starting = ConcurrentHashMap.newKeySet();

	private static class OmenPreview
	{
		final String _challengeId;
		final HotzoneModifier _omen;
		final long _expires;

		OmenPreview(String challengeId, HotzoneModifier omen)
		{
			_challengeId = challengeId;
			_omen = omen;
			_expires = System.currentTimeMillis() + OMEN_PREVIEW_TIME;
		}
	}

	protected ClassTransferChallengeManager()
	{
		Containers.Players().addListener(new ConsumerEventListener(Containers.Players(), EventType.ON_PLAYER_LOGIN, (OnPlayerLogin event) -> onPlayerLogin(event.getPlayer()), this));
		Containers.Players().addListener(new ConsumerEventListener(Containers.Players(), EventType.ON_PLAYER_LOGOUT, (OnPlayerLogout event) -> onPlayerLogout(event.getPlayer().getObjectId()), this));
		Containers.Players().addListener(new ConsumerEventListener(Containers.Players(), EventType.ON_PLAYER_SELECT, (OnPlayerSelect event) -> onPlayerSelect(event.getPlayer()), this));
		Containers.Players().addListener(new FunctionEventListener(Containers.Players(), EventType.ON_CREATURE_DEATH, (OnCreatureDeath event) -> onPlayerDeath(event), this));
		LOGGER.info(getClass().getSimpleName() + ": Alternative class transfer challenges " + (ClassTransferChallengeConfig.ENABLED ? "enabled" : "disabled") + ".");
	}

	/**
	 * @param instanceId an instance id
	 * @return {@code true} if a class transfer challenge runs in that instance
	 */
	public static boolean isChallengeInstance(int instanceId)
	{
		return (instanceId > 0) && SESSIONS_BY_INSTANCE.containsKey(instanceId);
	}

	// =========================================================================
	// Eligibility and entry
	// =========================================================================

	/**
	 * @param tier a Class Master tier
	 * @return the level the Class Masters require for it
	 */
	public static int getTierMinLevel(int tier)
	{
		switch (tier)
		{
			case 1:
			{
				return ClassTransferConfig.TIER1_MIN_LEVEL;
			}
			case 2:
			{
				return ClassTransferConfig.TIER2_MIN_LEVEL;
			}
			case 3:
			{
				return ClassTransferConfig.TIER3_MIN_LEVEL;
			}
			default:
			{
				return Integer.MAX_VALUE;
			}
		}
	}

	/**
	 * @param npcId an NPC id
	 * @return the stage of that Class Master NPC, {@code null} if it is none
	 */
	public static TransferStage getClassMasterStage(int npcId)
	{
		if (npcId == ClassTransferConfig.CLASS_MASTER_TIER1_NPC_ID)
		{
			return TransferStage.FIRST_TRANSFER;
		}
		if (npcId == ClassTransferConfig.CLASS_MASTER_TIER2_NPC_ID)
		{
			return TransferStage.SECOND_TRANSFER;
		}
		if (npcId == ClassTransferConfig.CLASS_MASTER_TIER3_NPC_ID)
		{
			return TransferStage.THIRD_TRANSFER;
		}
		return null;
	}

	/**
	 * @param player the player
	 * @param stage the stage
	 * @return the challenge the player takes at that stage, {@code null} if none
	 */
	public ChallengeDefinition resolveChallenge(Player player, TransferStage stage)
	{
		return ClassTransferChallengeData.getInstance().resolve(player, stage);
	}

	/**
	 * Server-side entry check, run before anything is created. The Class Master rules themselves (level per tier, which class transfers at which tier) are reused from {@link ClassTransferConfig} and {@link ClassTransferData}, not duplicated.
	 * @param player the player
	 * @param stage the stage
	 * @return why the player can't start the challenge, or {@code null} if they can
	 */
	public String checkEligibility(Player player, TransferStage stage)
	{
		if (!ClassTransferChallengeConfig.ENABLED)
		{
			return "The trials are closed.";
		}
		if (!ClassTransferChallengeConfig.ENTRY_ENABLED)
		{
			return "No new trials can be started right now. Please come back later.";
		}

		final String stateProblem = checkPlayerState(player);
		if (stateProblem != null)
		{
			return stateProblem;
		}

		final ChallengeDefinition challenge = resolveChallenge(player, stage);
		final int minLevel = Math.max(getTierMinLevel(stage.getTier()), challenge != null ? challenge.getMinLevel() : 0);
		if (player.getLevel() < minLevel)
		{
			return "You must be at least level " + minLevel + " to take this trial.";
		}
		if (ClassTransferData.getInstance().getEligibleTransfers(player.getPlayerClass().getId(), stage.getTier()).isEmpty())
		{
			return "Your current class has no " + stage.getDisplayName() + " here.";
		}
		if (ClassTransferChallengeCompletionTable.getInstance().hasValidCompletion(player, stage))
		{
			return "You have already passed this trial. Speak with the Class Master to transfer.";
		}

		final PlayerVariables vars = player.getVariables();
		final int fails = vars.getInt(FAILS_VAR + stage.getTier(), 0);
		if ((fails > 0) && !ClassTransferChallengeConfig.ALLOW_RESTART)
		{
			return "This trial can't be retried.";
		}
		if ((ClassTransferChallengeConfig.MAXIMUM_ATTEMPTS > 0) && (fails >= ClassTransferChallengeConfig.MAXIMUM_ATTEMPTS))
		{
			return "You have no attempts left for this trial.";
		}
		final long cooldown = vars.getLong(COOLDOWN_VAR + stage.getTier(), 0) - System.currentTimeMillis();
		if (cooldown > 0)
		{
			return "You must rest " + ((cooldown / 1000) + 1) + " more seconds before trying again.";
		}

		if (challenge == null)
		{
			return "There is no trial for your class at this stage.";
		}
		return null;
	}

	/**
	 * @param player the player
	 * @return why the player's current state doesn't allow entering a trial, or {@code null}
	 */
	private String checkPlayerState(Player player)
	{
		if (_sessions.containsKey(player.getObjectId()))
		{
			return "You are already taking a trial.";
		}
		if (player.isDead())
		{
			return "You can't do that while dead.";
		}
		if (player.getKarma() > 0)
		{
			return "The trials refuse those who carry karma.";
		}
		if ((player.getInstanceId() != 0) || (InstanceManager.getInstance().getPlayerWorld(player) != null))
		{
			return "You are already inside an instance.";
		}
		if (player.isInOlympiadMode() || player.isOnEvent())
		{
			return "You can't take a trial during an Olympiad match or event.";
		}
		if (player.isInStoreMode())
		{
			return "You can't take a trial while running a private store.";
		}
		if (AttackStanceTaskManager.getInstance().hasAttackStanceTask(player))
		{
			return "You can't take a trial during combat.";
		}
		if (player.isCursedWeaponEquipped() || player.isFlying() || player.isMounted())
		{
			return "You can't take a trial right now.";
		}
		return null;
	}

	/**
	 * The Omen shown on the details page. Rolled once and kept for a while, so reopening the dialog doesn't reroll it; entering uses it and the next attempt rolls a new one.
	 * @param player the player
	 * @param challenge the challenge
	 * @return the Omen, or {@code null} for none
	 */
	public HotzoneModifier previewOmen(Player player, ChallengeDefinition challenge)
	{
		if (!ClassTransferChallengeConfig.OMENS_ENABLED || challenge.getOmens().isEmpty())
		{
			return null;
		}

		final OmenPreview preview = _omenPreviews.get(player.getObjectId());
		if ((preview != null) && preview._challengeId.equals(challenge.getId()) && (preview._expires > System.currentTimeMillis()))
		{
			return preview._omen;
		}

		final HotzoneModifier omen = challenge.getOmens().get(Rnd.get(challenge.getOmens().size()));
		_omenPreviews.put(player.getObjectId(), new OmenPreview(challenge.getId(), omen));
		return omen;
	}

	/**
	 * Starts the player's challenge of {@code stage}: validates, creates the private instance, teleports the player in and starts the first objective.
	 * @param player the player
	 * @param stage the stage
	 * @return why it could not start, or {@code null} if it started
	 */
	public String startChallenge(Player player, TransferStage stage)
	{
		final String problem = checkEligibility(player, stage);
		if (problem != null)
		{
			return problem;
		}
		return start(player, resolveChallenge(player, stage));
	}

	/**
	 * Starts a challenge skipping the level, completion and attempt checks (GM command).
	 * @param player the player
	 * @param challenge the challenge
	 * @return why it could not start, or {@code null} if it started
	 */
	public String adminStartChallenge(Player player, ChallengeDefinition challenge)
	{
		final String problem = checkPlayerState(player);
		return problem != null ? problem : start(player, challenge);
	}

	private String start(Player player, ChallengeDefinition challenge)
	{
		if (challenge == null)
		{
			return "There is no trial for your class at this stage.";
		}

		// One start at a time per player (a double click must not open two trials).
		if (!_starting.add(player.getObjectId()))
		{
			return "Your trial is already being prepared.";
		}
		try
		{
			if (_sessions.containsKey(player.getObjectId()))
			{
				return "You are already taking a trial.";
			}
			return createSession(player, challenge);
		}
		finally
		{
			_starting.remove(player.getObjectId());
		}
	}

	private String createSession(Player player, ChallengeDefinition challenge)
	{
		final HotzoneModifier omen = previewOmen(player, challenge);
		_omenPreviews.remove(player.getObjectId());

		final int duration = challenge.getDuration() > 0 ? challenge.getDuration() : ClassTransferChallengeConfig.DEFAULT_DURATION;
		final Location returnLoc = new Location(player.getX(), player.getY(), player.getZ(), player.getHeading());

		final Instance instance = InstanceManager.getInstance().createDynamicInstance(0);
		final int instanceId = instance.getId();
		instance.setExitLoc(returnLoc); // Scroll of Escape, To Village, unstuck, and the instance's own destruction all lead back here.
		instance.setAllowSummon(ClassTransferChallengeConfig.ALLOW_SUMMONS);
		instance.setDuration((duration + ClassTransferChallengeConfig.DISCONNECT_GRACE_PERIOD + INSTANCE_MARGIN) * 1000);

		final ChallengeSession session = new ChallengeSession(player, challenge, instance, omen, returnLoc, duration);
		synchronized (session)
		{
			// Registered before anything spawns, so the spawn hooks already see a challenge instance.
			SESSIONS_BY_INSTANCE.put(instanceId, session);
			_sessions.put(player.getObjectId(), session);
			try
			{
				setUpSession(player, session, challenge, omen, returnLoc, duration);
			}
			catch (Exception e)
			{
				LOGGER.log(Level.WARNING, getClass().getSimpleName() + ": Could not start '" + challenge.getId() + "' for " + player.getName() + ".", e);
				session.changeStatus(Status.ACTIVE, Status.FAILED);
				endSession(session, true);
				return "The trial could not be prepared. Please contact an administrator.";
			}
		}

		LOGGER.info(getClass().getSimpleName() + ": " + player.getName() + " started '" + challenge.getId() + "' (" + challenge.getStage() + ") in instance " + instanceId + (omen != null ? " with omen " + omen : "") + ".");
		return null;
	}

	/**
	 * Fills a freshly registered session: Omen, objectives, twists, return point, teleport, timers, first objective. Caller holds the session's monitor.
	 */
	private void setUpSession(Player player, ChallengeSession session, ChallengeDefinition challenge, HotzoneModifier omen, Location returnLoc, int duration)
	{
		final int instanceId = session.getInstanceId();
		if (omen != null)
		{
			HotzoneModifierManager.getInstance().setInstanceModifier(instanceId, omen);
		}

		for (ObjectiveDefinition objective : challenge.getObjectives())
		{
			session.getObjectives().add(ObjectiveFactory.create(objective));
		}

		if (ClassTransferChallengeConfig.TWISTS_ENABLED)
		{
			for (TwistDefinition twist : challenge.getTwists())
			{
				if ((twist.getType() == TwistDefinition.TwistType.INVADER) && (Rnd.get(100) < twist.getChance()))
				{
					session.setInvaderTwist(twist);
					break;
				}
			}
		}

		final PlayerVariables vars = player.getVariables();
		vars.set(RETURN_LOC_VAR, returnLoc.getX() + ";" + returnLoc.getY() + ";" + returnLoc.getZ() + ";" + returnLoc.getHeading());
		vars.saveNow();

		final Location entry = challenge.getEntry();
		player.teleToLocation(entry.getX(), entry.getY(), entry.getZ(), entry.getHeading(), instanceId);

		if ((challenge.getGuideNpcId() > 0) && (challenge.getGuideLoc() != null))
		{
			session.spawnAt(challenge.getGuideNpcId(), challenge.getGuideLoc(), -1, NpcRole.GUIDE);
		}

		session.setTimeoutTask(ThreadPool.schedule(() -> onTimeout(session), duration * 1000L));
		session.setTickTask(ThreadPool.scheduleAtFixedRate(() -> onTick(session), 1000, 1000));

		applyOmenBuff(player, omen);
		player.sendPacket(new ExSendUIEvent(player, false, false, duration, 0, TIMER_TEXT));
		player.sendMessage("The " + challenge.getName() + " begins. Time limit: " + (duration / 60) + " minutes.");
		if (omen != null)
		{
			player.sendMessage("Omen of this trial - " + formatOmen(omen) + ": " + omen.getDescription());
		}

		startObjective(session, 0);
	}

	// =========================================================================
	// Objective flow
	// =========================================================================

	private void startObjective(ChallengeSession session, int index)
	{
		session.setCurrentObjectiveIndex(index);
		final AbstractChallengeObjective objective = session.getCurrentObjective();
		if (objective == null)
		{
			completeChallenge(session);
			return;
		}

		objective.start(session);
		announce(session, "Objective " + (index + 1) + "/" + session.getObjectives().size() + ": " + objective.getText());
		checkProgress(session);
	}

	/**
	 * Moves the session on after any change: fails it, advances to the next objective, or completes it. Caller holds the session's monitor.
	 * @param session the session
	 */
	private void checkProgress(ChallengeSession session)
	{
		final AbstractChallengeObjective objective = session.getCurrentObjective();
		if ((objective == null) || !session.isActive())
		{
			return;
		}

		if (objective.isFailed())
		{
			failChallenge(session, objective.getFailReason());
			return;
		}

		if (!objective.isDone())
		{
			return;
		}

		objective.finish(session);
		final int completed = objective.getIndex() + 1;
		if (ClassTransferChallengeConfig.LOGGING)
		{
			LOGGER.info(getClass().getSimpleName() + ": " + session.getPlayerName() + " completed objective " + completed + " (" + objective.getDefinition().getType() + ") of '" + session.getDefinition().getId() + "'.");
		}

		final TwistDefinition invader = session.getInvaderTwist();
		if ((invader != null) && !session.isInvaderSpawned() && (completed >= invader.getAfterObjective()) && (completed < session.getObjectives().size()))
		{
			spawnInvader(session);
		}

		startObjective(session, completed);
	}

	/**
	 * Shows the current objective and its progress at the top of the challenger's screen.
	 * @param session the session
	 */
	public void showProgress(ChallengeSession session)
	{
		final Player player = session.getPlayer();
		final AbstractChallengeObjective objective = session.getCurrentObjective();
		if ((player == null) || (objective == null))
		{
			return;
		}

		final String progress = objective.getProgress();
		player.sendPacket(new ExShowScreenMessage(objective.getText() + (progress.isEmpty() ? "" : " (" + progress + ")"), ExShowScreenMessage.TOP_CENTER, 4000));
	}

	/**
	 * Shows a big message to the challenger and writes it to their chat.
	 * @param session the session
	 * @param text the message
	 */
	public void announce(ChallengeSession session, String text)
	{
		final Player player = session.getPlayer();
		if (player != null)
		{
			player.sendPacket(new ExShowScreenMessage(text, ExShowScreenMessage.MIDDLE_CENTER, 5000));
			player.sendMessage(text);
		}
	}

	// =========================================================================
	// Spawning
	// =========================================================================

	/**
	 * Spawns one NPC of a spawn line into the session's instance, forces the line's monster modifiers on it and tracks it. Caller holds the session's monitor.
	 * @param session the session
	 * @param holder the spawn line
	 * @param objectiveIndex the objective it belongs to (-1 for the whole challenge)
	 * @param role its role
	 * @return the NPC, or {@code null} if it could not be spawned
	 */
	public Npc spawn(ChallengeSession session, ChallengeSpawnHolder holder, int objectiveIndex, NpcRole role)
	{
		int x = holder.getX();
		int y = holder.getY();
		int z = holder.getZ();
		if (holder.getRadius() > 0)
		{
			final double angle = Rnd.nextDouble() * 2 * Math.PI;
			final int distance = Rnd.get(holder.getRadius());
			final int tx = x + (int) (Math.cos(angle) * distance);
			final int ty = y + (int) (Math.sin(angle) * distance);
			final GeoEngine geo = GeoEngine.getInstance();
			if (geo.canMoveToTarget(x, y, z, tx, ty, z, session.getInstanceId()))
			{
				x = tx;
				y = ty;
				z = geo.getHeight(tx, ty, z);
			}
		}

		final Npc npc = Quest.addSpawn(holder.getNpcId(), x, y, z, holder.getHeading(), false, 0, false, session.getInstanceId());
		if (npc == null)
		{
			LOGGER.warning(getClass().getSimpleName() + ": Could not spawn NPC " + holder.getNpcId() + " for challenge '" + session.getDefinition().getId() + "'.");
			return null;
		}

		if (npc.isMonster())
		{
			applySpawnModifiers(npc.asMonster(), holder);
		}

		track(session, npc, objectiveIndex, holder, role);
		return npc;
	}

	/**
	 * Forces the spawn line's existing monster modifiers. Order matters: the champion tier first (its title rebuild would drop the Thief/Mage tag), then Mage/Thief, then the Wave Challenge.
	 */
	private void applySpawnModifiers(Monster monster, ChallengeSpawnHolder holder)
	{
		if (holder.getChampionTier() > 0)
		{
			monster.setChampionTier(holder.getChampionTier());
		}

		switch (holder.getVariant())
		{
			case MAGE:
			{
				// A caster archetype first, so the Mage has spells to cast.
				if (monster.getAI() instanceof AttackableAI)
				{
					((AttackableAI) monster.getAI()).setArchetype(MonsterArchetype.MAGE);
				}
				monster.startMage();
				break;
			}
			case THIEF:
			{
				monster.startThief();
				break;
			}
			default:
			{
				break;
			}
		}

		if (holder.getWaves() > 1)
		{
			monster.startWaveChallenge(holder.getWaves());
			monster.getVariables().set("WAVE_CHALLENGE_PAID", true); // The trial pays its own reward instead of the open-world coin split.
		}

		if (holder.isArenaBuffs())
		{
			applyArenaBuffs(monster);
		}

		monster.setCurrentHpMp(monster.getMaxHp(), monster.getMaxMp());
		monster.broadcastInfo();
	}

	/**
	 * Tracks an NPC for a session and listens for its death.
	 */
	private void track(ChallengeSession session, Npc npc, int objectiveIndex, ChallengeSpawnHolder holder, NpcRole role)
	{
		session.track(new TrackedNpc(npc, objectiveIndex, holder, role));
		npc.addListener(new ConsumerEventListener(npc, EventType.ON_CREATURE_DEATH, (OnCreatureDeath event) -> onTrackedNpcDeath(session, event), this));
	}

	/**
	 * Casts the Survival Arena challenger buffs ({@code ArenaBuffSkillIds} in Rates.ini) on a trial monster.
	 * @param monster the monster
	 */
	public void applyArenaBuffs(Monster monster)
	{
		if (!RatesConfig.ARENA_CHALLENGER_BUFFS_ENABLED || (RatesConfig.ARENA_BUFF_SKILL_IDS == null))
		{
			return;
		}

		final int count = Math.min(RatesConfig.ARENA_BUFF_SKILL_IDS.size(), RatesConfig.ARENA_BUFF_SKILL_LEVELS.size());
		for (int i = 0; i < count; i++)
		{
			final Skill buff = SkillData.getInstance().getSkill(RatesConfig.ARENA_BUFF_SKILL_IDS.get(i), RatesConfig.ARENA_BUFF_SKILL_LEVELS.get(i));
			if (buff != null)
			{
				buff.applyEffects(monster, monster);
			}
		}
	}

	/**
	 * A multi-phase boss entered a new phase: the enrage of the last phase ends, and an Arena Champion gets its buffs again.
	 * @param boss the boss
	 * @param holder its spawn line
	 */
	public void onBossPhase(Monster boss, ChallengeSpawnHolder holder)
	{
		boss.getEffectList().stopSkillEffects(SkillFinishType.REMOVED, ENRAGE_SKILL_ID);
		if ((holder != null) && holder.isArenaBuffs())
		{
			applyArenaBuffs(boss);
		}
	}

	/**
	 * Casts the Survival Arena enrage on a boss whose phase lasted too long.
	 * @param boss the boss
	 */
	public void enrage(Monster boss)
	{
		final Skill enrage = SkillData.getInstance().getSkill(ENRAGE_SKILL_ID, 1);
		if (enrage != null)
		{
			enrage.applyEffects(boss, boss);
			boss.broadcastPacket(new MagicSkillUse(boss, boss, ENRAGE_SKILL_ID, 1, 0, 0));
		}
	}

	/**
	 * Spawns the Rival Shade of a DUEL objective: a fake player built as one of the challenger's target classes at the challenger's level. Classes without a build get a build of the same role, then any build; without any build, {@code fallbackNpcId} stands in.
	 * @param session the session
	 * @param objectiveIndex the DUEL objective
	 * @param loc where it appears
	 * @param title the title it shows
	 * @param fallbackNpcId the NPC used when no fake player can be built
	 * @return the rival, or {@code null}
	 */
	public Npc spawnRival(ChallengeSession session, int objectiveIndex, Location loc, String title, int fallbackNpcId)
	{
		final Player player = session.getPlayer();
		if (player != null)
		{
			final List<FakePlayerPvpBuild> builds = FakePlayerPvpData.getInstance().getBuilds();
			final int level = player.getLevel();

			// "Face the path you seek": a build of one of the classes this transfer leads to, forced into that class.
			final List<PlayerClass> targets = new ArrayList<>();
			for (ClassTransferHolder transfer : ClassTransferData.getInstance().getEligibleTransfers(session.getFromClassId(), session.getStage().getTier()))
			{
				final PlayerClass target = PlayerClass.getPlayerClass(transfer.getToClassId());
				if (target != null)
				{
					targets.add(target);
				}
			}
			Collections.shuffle(targets);
			for (PlayerClass target : targets)
			{
				final List<FakePlayerPvpBuild> candidates = new ArrayList<>();
				for (FakePlayerPvpBuild build : builds)
				{
					if ((build.getPlayerClass() != null) && build.getPlayerClass().equalsOrChildOf(target) && !build.isSupport())
					{
						candidates.add(build);
					}
				}
				if (!candidates.isEmpty())
				{
					final Npc rival = FakePlayerPvpManager.getInstance().spawnTrialDuelist(candidates.get(Rnd.get(candidates.size())), level, target, title, loc.getX(), loc.getY(), loc.getZ(), session.getInstanceId(), player);
					if (rival != null)
					{
						track(session, rival, objectiveIndex, null, NpcRole.RIVAL);
						announce(session, "Face the path you seek: " + rival.getName() + ", a " + getClassName(target) + "!");
						return rival;
					}
				}
			}

			// No build for any target class: a sparring partner of the same role.
			final PlayerClass currentClass = PlayerClass.getPlayerClass(session.getFromClassId());
			final boolean mage = (currentClass != null) && currentClass.isMage();
			final List<FakePlayerPvpBuild> sameRole = new ArrayList<>();
			for (FakePlayerPvpBuild build : builds)
			{
				if (((build.getRole() == Role.MAGE) == mage) && !build.isSupport())
				{
					sameRole.add(build);
				}
			}
			final FakePlayerPvpBuild build = !sameRole.isEmpty() ? sameRole.get(Rnd.get(sameRole.size())) : FakePlayerPvpData.getInstance().getRandomBuild();
			if (build != null)
			{
				final Npc rival = FakePlayerPvpManager.getInstance().spawnTrialDuelist(build, level, null, title, loc.getX(), loc.getY(), loc.getZ(), session.getInstanceId(), player);
				if (rival != null)
				{
					track(session, rival, objectiveIndex, null, NpcRole.RIVAL);
					announce(session, rival.getName() + " answers your challenge!");
					return rival;
				}
			}
		}

		if (fallbackNpcId > 0)
		{
			final Npc shade = session.spawnAt(fallbackNpcId, loc, objectiveIndex, NpcRole.RIVAL);
			if (shade != null)
			{
				announce(session, shade.getName() + " answers your challenge!");
			}
			return shade;
		}
		return null;
	}

	/**
	 * Twist: a rival fake player invades the trial and hunts the challenger. It pays bonus Arena currency when defeated.
	 */
	private void spawnInvader(ChallengeSession session)
	{
		session.setInvaderSpawned(true);
		final Player player = session.getPlayerInside();
		final FakePlayerPvpBuild build = FakePlayerPvpData.getInstance().getRandomBuild();
		if ((player == null) || (build == null))
		{
			return;
		}

		final Location entry = session.getDefinition().getEntry();
		final Npc invader = FakePlayerPvpManager.getInstance().spawnTrialDuelist(build, player.getLevel(), null, "Invader", entry.getX(), entry.getY(), entry.getZ(), session.getInstanceId(), player);
		if (invader != null)
		{
			track(session, invader, -1, null, NpcRole.INVADER);
			announce(session, "An Invader has breached your trial: " + invader.getName() + " hunts you!");
			LOGGER.info(getClass().getSimpleName() + ": Invader " + invader.getName() + " entered " + session.getPlayerName() + "'s trial.");
		}
	}

	/**
	 * A Restless Dead omen raised a trial monster again: track the risen one so killing it counts for the current objective.
	 * @param victim the monster that died
	 * @param risen its risen copy
	 */
	public void onMonsterRisen(Npc victim, Npc risen)
	{
		final ChallengeSession session = SESSIONS_BY_INSTANCE.get(risen.getInstanceId());
		if (session == null)
		{
			return;
		}

		synchronized (session)
		{
			final AbstractChallengeObjective objective = session.getCurrentObjective();
			if (!session.isActive() || (session.getInstance() != InstanceManager.getInstance().getInstance(session.getInstanceId())) || !session.wasDefeated(victim.getObjectId()) || (objective == null))
			{
				return;
			}
			track(session, risen, objective.getIndex(), null, NpcRole.MOB);
		}
	}

	// =========================================================================
	// Events
	// =========================================================================

	private void onTrackedNpcDeath(ChallengeSession session, OnCreatureDeath event)
	{
		final Creature target = event.getTarget();
		if ((target == null) || !target.isNpc())
		{
			return;
		}

		final Npc npc = target.asNpc();
		final Creature killer = event.getAttacker();
		synchronized (session)
		{
			final TrackedNpc tracked = session.untrackDefeated(npc);
			if ((tracked == null) || !session.isActive())
			{
				return;
			}

			if (!isValidSessionInstance(session) || (npc.getInstanceId() != session.getInstanceId()))
			{
				LOGGER.warning(getClass().getSimpleName() + ": Ignored death of " + npc.getName() + " outside " + session.getPlayerName() + "'s trial instance.");
				return;
			}

			// The challenger, its summon, or nobody (damage over time). Any other player's kill never counts.
			final Player killerPlayer = killer != null ? killer.asPlayer() : null;
			final boolean credited = (killer == null) || ((killerPlayer != null) && (killerPlayer.getObjectId() == session.getPlayerObjectId()));
			if ((killerPlayer != null) && !credited)
			{
				LOGGER.warning(getClass().getSimpleName() + ": " + killerPlayer.getName() + " killed " + npc.getName() + " in " + session.getPlayerName() + "'s trial - not counted.");
			}

			if (tracked.getRole() == NpcRole.INVADER)
			{
				onInvaderDefeated(session, credited);
				return;
			}

			final AbstractChallengeObjective objective = session.getCurrentObjective();
			if ((objective == null) || (tracked.getObjectiveIndex() != objective.getIndex()))
			{
				return;
			}

			if (ClassTransferChallengeConfig.LOGGING)
			{
				LOGGER.info(getClass().getSimpleName() + ": " + session.getPlayerName() + " - " + tracked.getRole() + " " + npc.getName() + " died (credited: " + credited + ").");
			}

			objective.onKill(session, tracked, credited);
			if (!objective.isDone() && !objective.isFailed())
			{
				showProgress(session);
			}
			checkProgress(session);
		}
	}

	private void onInvaderDefeated(ChallengeSession session, boolean credited)
	{
		final TwistDefinition twist = session.getInvaderTwist();
		final Player player = session.getPlayer();
		if (!credited || (player == null) || (twist == null))
		{
			return;
		}

		announce(session, "You repelled the Invader!");
		if (ClassTransferChallengeConfig.REWARDS_ENABLED && (twist.getArenaCoins() > 0))
		{
			player.addItem(ItemProcessType.REWARD, RatesConfig.ARENA_CURRENCY_ITEM_ID, twist.getArenaCoins(), null, true);
		}
	}

	/**
	 * The challenger talked to a challenge NPC.
	 * @param player the player
	 * @param npc the NPC
	 * @return the NPC's role in the player's trial, or {@code null} if it is not part of it
	 */
	public NpcRole getNpcRole(Player player, Npc npc)
	{
		final ChallengeSession session = getSessionInside(player, npc);
		if (session == null)
		{
			return null;
		}
		synchronized (session)
		{
			final TrackedNpc tracked = session.getTracked(npc);
			return tracked != null ? tracked.getRole() : null;
		}
	}

	/**
	 * The challenger spoke with the NPC of a TALK objective.
	 * @param player the player
	 * @param npc the NPC
	 * @return {@code true} if it was the current objective's NPC
	 */
	public boolean talk(Player player, Npc npc)
	{
		final ChallengeSession session = getSessionInside(player, npc);
		if (session == null)
		{
			return false;
		}

		synchronized (session)
		{
			final TrackedNpc tracked = session.getTracked(npc);
			final AbstractChallengeObjective objective = session.getCurrentObjective();
			if (!session.isActive() || (tracked == null) || (objective == null) || (tracked.getObjectiveIndex() != objective.getIndex()) || !objective.onTalk(session, tracked))
			{
				return false;
			}
			checkProgress(session);
			return true;
		}
	}

	/**
	 * The challenger activated a Trial Seal.
	 * @param player the player
	 * @param npc the seal
	 * @return {@code true} if it was activated
	 */
	public boolean activate(Player player, Npc npc)
	{
		final ChallengeSession session = getSessionInside(player, npc);
		if ((session == null) || !player.isInsideRadius3D(npc, Npc.INTERACTION_DISTANCE * 2))
		{
			return false;
		}

		synchronized (session)
		{
			final TrackedNpc tracked = session.getTracked(npc);
			final AbstractChallengeObjective objective = session.getCurrentObjective();
			if (!session.isActive() || session.isKnockedOut() || (tracked == null) || (objective == null) || (tracked.getObjectiveIndex() != objective.getIndex()) || !objective.onActivate(session, tracked))
			{
				return false;
			}
			if (!objective.isDone())
			{
				showProgress(session);
			}
			checkProgress(session);
			return true;
		}
	}

	/**
	 * The challenger used a challenge item (see the {@code ClassTransferChallengeItem} item handler).
	 * @param player the player
	 * @param itemId the item
	 * @return {@code true} if the current objective took it
	 */
	public boolean useItem(Player player, int itemId)
	{
		final ChallengeSession session = _sessions.get(player.getObjectId());
		if ((session == null) || (player.getInstanceId() != session.getInstanceId()))
		{
			return false;
		}

		synchronized (session)
		{
			final AbstractChallengeObjective objective = session.getCurrentObjective();
			if (!session.isActive() || session.isKnockedOut() || (objective == null) || !objective.onItemUse(session, player, itemId))
			{
				return false;
			}
			if (!objective.isDone())
			{
				showProgress(session);
			}
			checkProgress(session);
			return true;
		}
	}

	private ChallengeSession getSessionInside(Player player, Npc npc)
	{
		final ChallengeSession session = _sessions.get(player.getObjectId());
		if ((session == null) || (player.getInstanceId() != session.getInstanceId()) || (npc == null) || (npc.getInstanceId() != session.getInstanceId()))
		{
			return null;
		}
		return session;
	}

	private boolean isValidSessionInstance(ChallengeSession session)
	{
		return InstanceManager.getInstance().getInstance(session.getInstanceId()) == session.getInstance();
	}

	private void onTick(ChallengeSession session)
	{
		synchronized (session)
		{
			if (!session.isActive())
			{
				return;
			}

			if (!isValidSessionInstance(session))
			{
				failChallenge(session, "The trial grounds collapsed.");
				return;
			}

			final long tick = session.nextTick();
			final Player player = session.getPlayer();
			if ((player != null) && !session.isOffline() && !session.isKnockedOut() && !player.isTeleporting() && (player.getInstanceId() != session.getInstanceId()))
			{
				failChallenge(session, "You left the trial grounds.");
				return;
			}

			// Objective timers wait for a disconnected challenger (the trial's own time limit doesn't).
			if (session.isOffline())
			{
				return;
			}

			final Player inside = session.isKnockedOut() ? null : session.getPlayerInside();
			if ((inside != null) && ((tick % OMEN_DRAIN_TICKS) == 0))
			{
				drainOmen(session, inside);
			}
			if ((inside != null) && ((tick % 10) == 0))
			{
				applyOmenBuff(inside, session.getOmen()); // Put back if something (death cleanse, hotzone login check...) removed it.
			}

			final AbstractChallengeObjective objective = session.getCurrentObjective();
			if (objective != null)
			{
				objective.onTick(session, inside);
				checkProgress(session);
			}
		}
	}

	private void drainOmen(ChallengeSession session, Player player)
	{
		final HotzoneModifier omen = session.getOmen();
		if ((omen == null) || (omen.getPlayerHpDrainPctPerTick() <= 0) || player.isDead() || player.isInvul())
		{
			return;
		}

		final double drain = (player.getMaxHp() * omen.getPlayerHpDrainPctPerTick()) / 100.0;
		if (drain > 0)
		{
			player.reduceCurrentHp(drain, null, false, false, null);
		}
	}

	private void onTimeout(ChallengeSession session)
	{
		synchronized (session)
		{
			if (session.getStatus() == Status.COMPLETED)
			{
				returnPlayer(session);
				return;
			}
			failChallenge(session, "Time is up.");
		}
	}

	/**
	 * A challenger's HP reached zero. In REVIVE mode the death is cancelled: the challenger is knocked out, then sent back to the entrance with progress kept - no death penalty, no drops, no "To Village", and none of the server's death hooks (Nemesis...) run.
	 */
	private TerminateReturn onPlayerDeath(OnCreatureDeath event)
	{
		final Creature target = event.getTarget();
		if ((target == null) || !target.isPlayer())
		{
			return null;
		}

		final Player player = target.asPlayer();
		final ChallengeSession session = _sessions.get(player.getObjectId());
		if ((session == null) || (player.getInstanceId() != session.getInstanceId()))
		{
			return null;
		}

		synchronized (session)
		{
			final Status status = session.getStatus();
			if ((status != Status.ACTIVE) && (status != Status.COMPLETED))
			{
				return null;
			}

			if (ClassTransferChallengeConfig.DEATH_BEHAVIOR == DeathBehavior.FAIL)
			{
				if (status == Status.ACTIVE)
				{
					ThreadPool.schedule(() ->
					{
						synchronized (session)
						{
							failChallenge(session, "You fell in battle.");
						}
					}, 2000);
				}
				return null;
			}

			player.setCurrentHp(1);
			if (!session.isKnockedOut())
			{
				knockout(session, player);
			}
			return new TerminateReturn(true, true, false);
		}
	}

	private void knockout(ChallengeSession session, Player player)
	{
		session.setKnockedOut(true);
		session.increaseKnockouts();

		if (!player.isInvul() && _invulGranted.add(player.getObjectId()))
		{
			player.setInvul(true);
		}
		player.abortAttack();
		player.abortCast();
		player.setTarget(null);

		// Everything that was fighting the challenger lets go.
		for (TrackedNpc tracked : session.getAllTracked())
		{
			final Npc npc = tracked.getNpc();
			if (npc.isAttackable() && !npc.isDead())
			{
				npc.asAttackable().stopHating(player);
				if (player.hasSummon())
				{
					npc.asAttackable().stopHating(player.getSummon());
				}
			}
		}

		final AbstractChallengeObjective objective = session.getCurrentObjective();
		if (objective != null)
		{
			objective.onPlayerKnockout(session);
		}

		announce(session, "You have been defeated! Returning to the entrance...");
		LOGGER.info(getClass().getSimpleName() + ": " + player.getName() + " was knocked out in '" + session.getDefinition().getId() + "' (" + session.getKnockouts() + ").");
		ThreadPool.schedule(() -> finishKnockout(session), ClassTransferChallengeConfig.KNOCKOUT_DELAY * 1000L);
	}

	private void finishKnockout(ChallengeSession session)
	{
		synchronized (session)
		{
			final Status status = session.getStatus();
			if (!session.isKnockedOut() || ((status != Status.ACTIVE) && (status != Status.COMPLETED)))
			{
				return;
			}

			final Player player = session.getPlayerInside();
			if (player == null)
			{
				return; // Logged out meanwhile: finished at login.
			}

			restoreAfterKnockout(session, player);
		}
	}

	private void restoreAfterKnockout(ChallengeSession session, Player player)
	{
		session.setKnockedOut(false);
		final Location entry = session.getDefinition().getEntry();
		player.teleToLocation(entry.getX(), entry.getY(), entry.getZ(), entry.getHeading(), session.getInstanceId());
		player.setCurrentHpMp(player.getMaxHp(), player.getMaxMp());
		player.setCurrentCp(player.getMaxCp());
		player.sendPacket(new ExSendUIEvent(player, false, false, session.getRemainingSeconds(), 0, TIMER_TEXT));

		final int objectId = player.getObjectId();
		ThreadPool.schedule(() -> releaseInvul(objectId), ClassTransferChallengeConfig.REVIVE_INVULNERABILITY * 1000L);
	}

	private void releaseInvul(int objectId)
	{
		if (_invulGranted.remove(objectId))
		{
			final Player player = World.getInstance().getPlayer(objectId);
			if (player != null)
			{
				player.setInvul(false);
			}
		}
	}

	// =========================================================================
	// Disconnect and reconnect
	// =========================================================================

	private void onPlayerLogout(int objectId)
	{
		_omenPreviews.remove(objectId);
		final ChallengeSession session = _sessions.get(objectId);
		if (session == null)
		{
			return;
		}

		synchronized (session)
		{
			session.setOffline(true);
			if (session.getStatus() == Status.COMPLETED)
			{
				// Cleared already: the completion is saved, the relog lands back at the Class Masters.
				endSession(session, false);
				return;
			}
			if (!session.isActive())
			{
				return;
			}

			if (ClassTransferChallengeConfig.DISCONNECT_GRACE_PERIOD <= 0)
			{
				failChallenge(session, "You disconnected.");
				return;
			}
			session.setGraceTask(ThreadPool.schedule(() -> onGraceExpired(session), ClassTransferChallengeConfig.DISCONNECT_GRACE_PERIOD * 1000L));
			LOGGER.info(getClass().getSimpleName() + ": " + session.getPlayerName() + " disconnected during '" + session.getDefinition().getId() + "' - trial kept for " + ClassTransferChallengeConfig.DISCONNECT_GRACE_PERIOD + " seconds.");
		}
	}

	private void onGraceExpired(ChallengeSession session)
	{
		synchronized (session)
		{
			if (session.isActive() && session.isOffline())
			{
				failChallenge(session, "You were away for too long.");
			}
		}
	}

	/**
	 * Character selection, before the player enters the world: a live trial keeps its instance (the grace timer stops here, before EnterWorld restores the player into it); otherwise a player whose trial is gone - after the grace period or a server restart - is moved back to where they
	 * entered from, instead of spawning at the trial coordinates in the open world.
	 */
	private void onPlayerSelect(Player player)
	{
		final ChallengeSession session = _sessions.get(player.getObjectId());
		if (session != null)
		{
			synchronized (session)
			{
				if (session.isActive() && isValidSessionInstance(session))
				{
					session.cancelGraceTask();
					return;
				}
			}
		}

		final PlayerVariables vars = player.getVariables();
		final Location returnLoc = parseLocation(vars.getString(RETURN_LOC_VAR, ""));
		if (returnLoc != null)
		{
			player.setXYZ(returnLoc.getX(), returnLoc.getY(), returnLoc.getZ());
			vars.remove(RETURN_LOC_VAR);
			vars.saveNow();
		}
	}

	private void onPlayerLogin(Player player)
	{
		final ChallengeSession session = _sessions.get(player.getObjectId());
		if (session == null)
		{
			// Challenge items only exist inside a trial: remove any a trial that ended while offline left behind.
			for (int itemId : ClassTransferChallengeData.getInstance().getChallengeItemIds())
			{
				final long count = player.getInventory().getInventoryItemCount(itemId, -1);
				if (count > 0)
				{
					player.destroyItemByItemId(ItemProcessType.QUEST, itemId, count, null, false);
				}
			}

			// Safety net if selection could not relocate the player.
			final PlayerVariables vars = player.getVariables();
			final Location returnLoc = parseLocation(vars.getString(RETURN_LOC_VAR, ""));
			if ((returnLoc != null) && !isChallengeInstance(player.getInstanceId()))
			{
				vars.remove(RETURN_LOC_VAR);
				vars.saveNow();
				player.teleToLocation(returnLoc.getX(), returnLoc.getY(), returnLoc.getZ(), returnLoc.getHeading(), 0);
			}
			return;
		}

		synchronized (session)
		{
			if (!session.isActive())
			{
				return;
			}

			session.cancelGraceTask();
			final Location entry = session.getDefinition().getEntry();
			if (player.getInstanceId() != session.getInstanceId())
			{
				// RestorePlayerInstance = False put the player outside: bring them back.
				player.teleToLocation(entry.getX(), entry.getY(), entry.getZ(), entry.getHeading(), session.getInstanceId());
			}
			session.setOffline(false);

			if (session.isKnockedOut())
			{
				_invulGranted.add(player.getObjectId());
				player.setInvul(true);
				restoreAfterKnockout(session, player);
			}

			applyOmenBuff(player, session.getOmen());
			player.sendPacket(new ExSendUIEvent(player, false, false, session.getRemainingSeconds(), 0, TIMER_TEXT));
			player.sendMessage("You resume the " + session.getDefinition().getName() + ".");
			showProgress(session);
		}
	}

	// =========================================================================
	// Completion, failure and cleanup
	// =========================================================================

	private void completeChallenge(ChallengeSession session)
	{
		if (!session.changeStatus(Status.ACTIVE, Status.COMPLETED))
		{
			return;
		}
		session.setCompletedTime(System.currentTimeMillis());

		final Player player = session.getPlayer();
		final ChallengeDefinition challenge = session.getDefinition();
		if ((player == null) || (player.getPlayerClass().getId() != session.getFromClassId()) || (player.getClassIndex() != session.getClassIndex()))
		{
			LOGGER.warning(getClass().getSimpleName() + ": '" + challenge.getId() + "' of " + session.getPlayerName() + " completed while the challenger was offline or changed class - completion not recorded.");
			endSession(session, true);
			return;
		}

		if (!ClassTransferChallengeCompletionTable.getInstance().storeCompletion(player, session.getStage(), challenge.getId()))
		{
			player.sendMessage("Your trial result could not be recorded. Please contact an administrator.");
			endSession(session, true);
			return;
		}

		final PlayerVariables vars = player.getVariables();
		vars.remove(FAILS_VAR + session.getStage().getTier());
		vars.remove(COOLDOWN_VAR + session.getStage().getTier());

		grantRewards(session, player);

		// Only the NPCs the challenger may still talk to stay.
		for (TrackedNpc tracked : session.getAllTracked())
		{
			if ((tracked.getRole() != NpcRole.TALK) && (tracked.getRole() != NpcRole.GUIDE))
			{
				session.untrack(tracked.getNpc());
				tracked.getNpc().deleteMe();
			}
		}

		player.sendPacket(new ExSendUIEvent(player, true, false, 0, 0, ""));
		announce(session, challenge.getName() + " cleared! Return to the Class Master to complete your transfer.");
		session.setReturnTask(ThreadPool.schedule(() ->
		{
			synchronized (session)
			{
				returnPlayer(session);
			}
		}, COMPLETED_RETURN_DELAY));

		final long seconds = (session.getCompletedTime() - session.getStartTime()) / 1000;
		LOGGER.info(getClass().getSimpleName() + ": " + player.getName() + " completed '" + challenge.getId() + "' in " + seconds + "s with " + session.getKnockouts() + " knockout(s).");
	}

	private void grantRewards(ChallengeSession session, Player player)
	{
		if (!ClassTransferChallengeConfig.REWARDS_ENABLED || session.getDefinition().getRewards().isEmpty())
		{
			return;
		}

		final int parTime = session.getDefinition().getParTime();
		final boolean withinPar = (parTime > 0) && ((session.getCompletedTime() - session.getStartTime()) <= (parTime * 1000L));
		if (withinPar)
		{
			player.sendMessage("Cleared within the par time of " + (parTime / 60) + " minutes: bonus reward!");
		}

		for (ChallengeReward reward : session.getDefinition().getRewards())
		{
			final long amount = reward.getCount() + (withinPar ? reward.getParBonus() : 0);
			switch (reward.getType())
			{
				case ITEM:
				{
					player.addItem(ItemProcessType.REWARD, reward.getItemId(), amount, null, true);
					break;
				}
				case ARENA_COINS:
				{
					player.addItem(ItemProcessType.REWARD, RatesConfig.ARENA_CURRENCY_ITEM_ID, amount, null, true);
					break;
				}
				case SEALED_CACHE:
				{
					// Straight into the inventory (the trial despawns everything, a drop could be lost), the tier rolled like a Mage monster's cache.
					final Npc boss = session.getLastDefeatedBoss();
					LuckyLootManager.getInstance().giveGuaranteedCache(player, boss != null ? boss.getLevel() : player.getLevel());
					break;
				}
				case EXP:
				{
					player.addExpAndSp(amount, 0);
					break;
				}
				case SP:
				{
					player.addExpAndSp(0, amount);
					break;
				}
			}
		}
	}

	/**
	 * Fails the session (time out, abandon, disconnect, a failed objective...), once. Caller holds the session's monitor.
	 * @param session the session
	 * @param reason what the challenger is told
	 */
	private void failChallenge(ChallengeSession session, String reason)
	{
		if (!session.changeStatus(Status.ACTIVE, Status.FAILED))
		{
			return;
		}

		final Player player = session.getPlayer();
		if (player != null)
		{
			player.sendPacket(new ExShowScreenMessage("Trial failed: " + reason, ExShowScreenMessage.MIDDLE_CENTER, 6000));
			player.sendMessage("Trial failed: " + reason + " You may try again at the Class Master.");

			final PlayerVariables vars = player.getVariables();
			final int tier = session.getStage().getTier();
			vars.set(FAILS_VAR + tier, vars.getInt(FAILS_VAR + tier, 0) + 1);
			if (ClassTransferChallengeConfig.RETRY_COOLDOWN > 0)
			{
				vars.set(COOLDOWN_VAR + tier, System.currentTimeMillis() + (ClassTransferChallengeConfig.RETRY_COOLDOWN * 1000L));
			}
		}

		LOGGER.info(getClass().getSimpleName() + ": " + session.getPlayerName() + " failed '" + session.getDefinition().getId() + "' at objective " + (session.getCurrentObjectiveIndex() + 1) + ": " + reason);
		endSession(session, true);
	}

	/**
	 * Sends a challenger who cleared the trial back and ends the session. Caller holds the session's monitor.
	 */
	private void returnPlayer(ChallengeSession session)
	{
		if (session.getStatus() == Status.COMPLETED)
		{
			endSession(session, true);
		}
	}

	/**
	 * Cleans a session up, once: timers, the Omen, challenge items, the invulnerability and PvP flag it caused, its fake players and its instance. Caller holds the session's monitor.
	 * @param session the session
	 * @param teleportOut {@code true} to send an online challenger back to where they entered from
	 */
	private void endSession(ChallengeSession session, boolean teleportOut)
	{
		if (!session.markEnded())
		{
			return;
		}

		session.cancelTasks();
		final int instanceId = session.getInstanceId();
		HotzoneModifierManager.getInstance().clearInstanceModifier(instanceId);

		// A player who is logging out is being stored: leave it alone, leftovers are cleaned at the next login.
		final Player player = session.isOffline() ? null : session.getPlayer();
		if (player != null)
		{
			removeOmenBuff(player, session.getOmen());
			player.sendPacket(new ExSendUIEvent(player, true, false, 0, 0, ""));
			for (Map.Entry<Integer, Long> given : session.getGivenItems().entrySet())
			{
				if (given.getValue() > 0)
				{
					takeChallengeItem(player, given.getKey(), given.getValue());
				}
			}
			if (_invulGranted.remove(player.getObjectId()))
			{
				player.setInvul(false);
			}
			if (player.getPvpFlag() > 0)
			{
				player.stopPvPFlag();
			}
			if (session.isKnockedOut() || player.isDead())
			{
				if (player.isDead())
				{
					player.doRevive();
				}
				player.setCurrentHpMp(player.getMaxHp(), player.getMaxMp());
			}

			if (teleportOut && (player.getInstanceId() == instanceId))
			{
				final Location returnLoc = session.getReturnLoc();
				player.teleToLocation(returnLoc.getX(), returnLoc.getY(), returnLoc.getZ(), returnLoc.getHeading(), 0);
			}

			if (player.getInstanceId() != instanceId)
			{
				final PlayerVariables vars = player.getVariables();
				vars.remove(RETURN_LOC_VAR);
				vars.saveNow();
			}
		}

		for (TrackedNpc tracked : session.getAllTracked())
		{
			if ((tracked.getRole() == NpcRole.RIVAL) || (tracked.getRole() == NpcRole.INVADER))
			{
				tracked.getNpc().deleteMe();
			}
		}

		if (isValidSessionInstance(session))
		{
			InstanceManager.getInstance().destroyInstance(instanceId);
		}
		SESSIONS_BY_INSTANCE.remove(instanceId, session);
		_sessions.remove(session.getPlayerObjectId(), session);
		LOGGER.info(getClass().getSimpleName() + ": Trial instance " + instanceId + " of " + session.getPlayerName() + " destroyed (" + session.getDefinition().getId() + ").");
	}

	// =========================================================================
	// Player requests (dialogs, voiced command)
	// =========================================================================

	/**
	 * The challenger asked the Trial Master to go back after clearing the trial.
	 * @param player the player
	 * @return {@code true} if they were sent back
	 */
	public boolean returnFromChallenge(Player player)
	{
		final ChallengeSession session = _sessions.get(player.getObjectId());
		if (session == null)
		{
			return false;
		}
		synchronized (session)
		{
			if (session.getStatus() != Status.COMPLETED)
			{
				return false;
			}
			returnPlayer(session);
			return true;
		}
	}

	/**
	 * The challenger gave up.
	 * @param player the player
	 * @return {@code true} if a trial was abandoned
	 */
	public boolean abandon(Player player)
	{
		final ChallengeSession session = _sessions.get(player.getObjectId());
		if (session == null)
		{
			return false;
		}
		synchronized (session)
		{
			if (session.getStatus() == Status.COMPLETED)
			{
				returnPlayer(session);
				return true;
			}
			if (!session.isActive())
			{
				return false;
			}
			failChallenge(session, "You abandoned the trial.");
			return true;
		}
	}

	/**
	 * @param player the player
	 * @return the player's running (or just cleared) trial, {@code null} if none
	 */
	public ChallengeSession getSession(Player player)
	{
		return _sessions.get(player.getObjectId());
	}

	public Collection<ChallengeSession> getSessions()
	{
		return _sessions.values();
	}

	/**
	 * @param session the session
	 * @return one {@code [state, text, progress]} row per objective, state being {@code done}, {@code current} or {@code pending}
	 */
	public List<String[]> describeObjectives(ChallengeSession session)
	{
		final List<String[]> rows = new ArrayList<>();
		synchronized (session)
		{
			final int current = session.getStatus() == Status.ACTIVE ? session.getCurrentObjectiveIndex() : session.getObjectives().size();
			for (AbstractChallengeObjective objective : session.getObjectives())
			{
				final int index = objective.getIndex();
				final String state = index < current ? "done" : index == current ? "current" : "pending";
				rows.add(new String[]
				{
					state,
					objective.getText(),
					index == current ? objective.getProgress() : ""
				});
			}
		}
		return rows;
	}

	// =========================================================================
	// Class Master integration
	// =========================================================================

	/**
	 * Server-side gate of the Class Master transfer, checked right before the class changes.
	 * @param player the player
	 * @param stage the stage
	 * @return {@code true} if the transfer may happen: the challenges are off or not required, or the player cleared this stage's trial as their current class
	 */
	public boolean isTransferUnlocked(Player player, TransferStage stage)
	{
		if (!ClassTransferChallengeConfig.ENABLED || !ClassTransferChallengeConfig.REQUIRED_FOR_TRANSFER)
		{
			return true;
		}
		return ClassTransferChallengeCompletionTable.getInstance().hasValidCompletion(player, stage);
	}

	/**
	 * The Class Master performed the transfer the trial unlocked: the completion is used up.
	 * @param player the player (still on the class slot the transfer happened on)
	 * @param stage the stage
	 */
	public void onClassTransferred(Player player, TransferStage stage)
	{
		ClassTransferChallengeCompletionTable.getInstance().consume(player, stage);
	}

	// =========================================================================
	// GM tools
	// =========================================================================

	/**
	 * Completes the player's current objective (GM command).
	 * @param player the player
	 * @return {@code true} if an objective was completed
	 */
	public boolean adminCompleteObjective(Player player)
	{
		final ChallengeSession session = _sessions.get(player.getObjectId());
		if (session == null)
		{
			return false;
		}
		synchronized (session)
		{
			final AbstractChallengeObjective objective = session.getCurrentObjective();
			if (!session.isActive() || (objective == null))
			{
				return false;
			}
			objective.forceComplete();
			checkProgress(session);
			return true;
		}
	}

	/**
	 * Completes the player's whole trial at once (GM command).
	 * @param player the player
	 * @return {@code true} if a trial was completed
	 */
	public boolean adminCompleteChallenge(Player player)
	{
		final ChallengeSession session = _sessions.get(player.getObjectId());
		if (session == null)
		{
			return false;
		}
		synchronized (session)
		{
			if (!session.isActive())
			{
				return false;
			}
			completeChallenge(session);
			returnPlayer(session);
			return true;
		}
	}

	/**
	 * Fails the player's trial (GM command).
	 * @param player the player
	 * @return {@code true} if a trial was aborted
	 */
	public boolean adminAbort(Player player)
	{
		final ChallengeSession session = _sessions.get(player.getObjectId());
		if (session == null)
		{
			return false;
		}
		synchronized (session)
		{
			if (session.getStatus() == Status.COMPLETED)
			{
				returnPlayer(session);
				return true;
			}
			if (!session.isActive())
			{
				return false;
			}
			failChallenge(session, "Aborted by a Game Master.");
			return true;
		}
	}

	/**
	 * Clears the player's completion, failed attempts and cooldown of a stage (GM command).
	 * @param player the player
	 * @param stage the stage
	 */
	public void adminReset(Player player, TransferStage stage)
	{
		ClassTransferChallengeCompletionTable.getInstance().consume(player, stage);
		final PlayerVariables vars = player.getVariables();
		vars.remove(FAILS_VAR + stage.getTier());
		vars.remove(COOLDOWN_VAR + stage.getTier());
	}

	/**
	 * Records a stage as cleared without running the trial (GM command).
	 * @param player the player
	 * @param stage the stage
	 * @return {@code true} if recorded
	 */
	public boolean adminGrant(Player player, TransferStage stage)
	{
		return ClassTransferChallengeCompletionTable.getInstance().storeCompletion(player, stage, "gm_granted");
	}

	// =========================================================================
	// Helpers
	// =========================================================================

	/**
	 * @param player the player
	 * @param itemId the item
	 * @param count how many
	 */
	public void giveChallengeItem(Player player, int itemId, long count)
	{
		player.addItem(ItemProcessType.QUEST, itemId, count, null, true);
	}

	/**
	 * @param player the player
	 * @param itemId the item
	 * @param count at most how many
	 * @return how many were taken
	 */
	public long takeChallengeItem(Player player, int itemId, long count)
	{
		final long owned = player.getInventory().getInventoryItemCount(itemId, -1);
		final long taken = Math.min(owned, count);
		if ((taken > 0) && player.destroyItemByItemId(ItemProcessType.QUEST, itemId, taken, null, true))
		{
			return taken;
		}
		return 0;
	}

	private void applyOmenBuff(Player player, HotzoneModifier omen)
	{
		if ((omen == null) || (omen.getPlayerBuffSkillId() <= 0) || (player.getEffectList().getBuffInfoBySkillId(omen.getPlayerBuffSkillId()) != null))
		{
			return;
		}
		final Skill buff = SkillData.getInstance().getSkill(omen.getPlayerBuffSkillId(), 1);
		if (buff != null)
		{
			buff.applyEffects(player, player);
		}
	}

	private void removeOmenBuff(Player player, HotzoneModifier omen)
	{
		if (omen == null)
		{
			return;
		}
		if (omen.getPlayerBuffSkillId() > 0)
		{
			player.getEffectList().stopSkillEffects(SkillFinishType.REMOVED, omen.getPlayerBuffSkillId());
		}
		if (omen.isKillStreak())
		{
			player.getEffectList().stopSkillEffects(SkillFinishType.REMOVED, HotzoneModifier.KILL_STREAK_SKILL_ID);
		}
	}

	/**
	 * @param omen an Omen
	 * @return its display name, e.g. "Kill Streak"
	 */
	public static String formatOmen(HotzoneModifier omen)
	{
		final StringBuilder sb = new StringBuilder();
		for (String word : omen.name().split("_"))
		{
			if (sb.length() > 0)
			{
				sb.append(' ');
			}
			sb.append(word.charAt(0)).append(word.substring(1).toLowerCase());
		}
		return sb.toString();
	}

	/**
	 * @param playerClass a class
	 * @return its client name, e.g. "Gladiator"
	 */
	public static String getClassName(PlayerClass playerClass)
	{
		final ClassInfoHolder info = ClassListData.getInstance().getClass(playerClass.getId());
		return info != null ? info.getClassName() : playerClass.name();
	}

	private static Location parseLocation(String value)
	{
		if ((value == null) || value.isEmpty())
		{
			return null;
		}
		try
		{
			final String[] parts = value.split(";");
			return new Location(Integer.parseInt(parts[0]), Integer.parseInt(parts[1]), Integer.parseInt(parts[2]), parts.length > 3 ? Integer.parseInt(parts[3]) : 0);
		}
		catch (Exception e)
		{
			return null;
		}
	}

	public static ClassTransferChallengeManager getInstance()
	{
		return SingletonHolder.INSTANCE;
	}

	private static class SingletonHolder
	{
		protected static final ClassTransferChallengeManager INSTANCE = new ClassTransferChallengeManager();
	}
}
