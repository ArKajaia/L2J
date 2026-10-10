package org.l2jmobius.gameserver.managers;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;

import org.l2jmobius.commons.threads.ThreadPool;
import org.l2jmobius.commons.util.Rnd;
import org.l2jmobius.gameserver.config.RatesConfig;
import org.l2jmobius.gameserver.config.custom.NightCycleConfig;
import org.l2jmobius.gameserver.config.custom.PvpSpotsConfig;
import org.l2jmobius.gameserver.config.custom.WaveChallengeConfig;
import org.l2jmobius.gameserver.managers.PvpSpotManager.SpotInfo;
import org.l2jmobius.gameserver.model.World;
import org.l2jmobius.gameserver.model.actor.Attackable;
import org.l2jmobius.gameserver.model.actor.Creature;
import org.l2jmobius.gameserver.model.actor.Player;
import org.l2jmobius.gameserver.model.actor.instance.Chest;
import org.l2jmobius.gameserver.model.actor.instance.Monster;
import org.l2jmobius.gameserver.model.events.Containers;
import org.l2jmobius.gameserver.model.events.EventDispatcher;
import org.l2jmobius.gameserver.model.events.EventType;
import org.l2jmobius.gameserver.model.events.holders.OnNightPhaseChange;
import org.l2jmobius.gameserver.model.events.holders.actor.creature.OnCreatureDeath;
import org.l2jmobius.gameserver.model.events.holders.actor.player.OnPlayerLogin;
import org.l2jmobius.gameserver.model.events.listeners.ConsumerEventListener;
import org.l2jmobius.gameserver.model.hotzone.HotzoneModifier;
import org.l2jmobius.gameserver.model.item.enums.ItemProcessType;
import org.l2jmobius.gameserver.model.nightcycle.NightPhase;
import org.l2jmobius.gameserver.model.nightcycle.NightTraits;
import org.l2jmobius.gameserver.network.enums.ChatType;
import org.l2jmobius.gameserver.network.serverpackets.ExRedSky;
import org.l2jmobius.gameserver.network.serverpackets.ExShowScreenMessage;
import org.l2jmobius.gameserver.network.serverpackets.SunRise;
import org.l2jmobius.gameserver.network.serverpackets.SunSet;
import org.l2jmobius.gameserver.taskmanagers.GameTimeTaskManager;
import org.l2jmobius.gameserver.util.Broadcast;

/**
 * Makes the day/night cycle something players notice. The game clock itself is unchanged ({@link GameTimeTaskManager}: night is game hours 0:00-5:59, about one real hour in four), but the night now runs through phases ({@link NightPhase}), each announced on screen:
 * <ul>
 * <li><b>Dusk</b>: tonight's Omen is told.</li>
 * <li><b>Night</b>: the Omen, a {@link HotzoneModifier}, lies over the whole open world (see {@link HotzoneModifierManager#getModifierFor}), and soon after nightfall the {@link NightlordManager Nightlords} rise.</li>
 * <li><b>Witching Hour</b>: the last minutes of the night. The Omen gives way to {@link HotzoneModifier#WITCHING_HOUR}, and kills may call a wave challenger nearby.</li>
 * <li><b>Dawn</b>: the Omen lifts, the Nightlords and Shadow Raiders flee, the sun burns the undead still fighting, the best of the Night Watch (the players with the most night kills) are rewarded, and the leader of each PvP spot is crowned its Night King.</li>
 * </ul>
 * At night {@link ShadowRaidManager Shadow Raids} strike the open world, the PvP spots draw more fighters (Moonlit Melee, see {@link PvpSpotManager}), and each race feels the night ({@link NightTraits}).
 * A red Omen and the Witching Hour turn the sky red for players outside towns. GMs can force a phase with {@code //night}; the forced phase lasts until the clock reaches its next phase.
 */
public class NightCycleManager
{
	private static final Logger LOGGER = Logger.getLogger(NightCycleManager.class.getName());

	/** How often (ms) the phase is checked: one game minute. */
	private static final long TICK_MS = 10000;
	/** How often (ticks) the red sky is sent again, so it follows players as they leave or enter towns. */
	private static final int RED_SKY_TICKS = 6;
	/** How long (seconds) one red sky packet lasts: a little longer than {@link #RED_SKY_TICKS}. */
	private static final int RED_SKY_SECONDS = 70;
	/** How far (game units) around each player the dawn looks for undead to burn. */
	private static final int DAWN_BURN_RADIUS = 2000;

	/** Set once the manager runs: before that (data loading at boot) the night is the game clock's. */
	private static volatile boolean _started;

	private volatile NightPhase _phase = NightPhase.DAY;
	/** A phase a GM forced, kept until the clock reaches another phase. */
	private volatile NightPhase _forcedPhase;
	private volatile NightPhase _clockPhaseAtForce;
	/** Tonight's Omen, rolled at dusk (or nightfall), {@code null} by day. */
	private volatile HotzoneModifier _omen;
	private int _ticks;

	/** player object id -> what they did tonight. Cleared at nightfall. */
	private final Map<Integer, NightRecord> _nightWatch = new ConcurrentHashMap<>();

	/**
	 * What one player did tonight, for the Night Watch.
	 */
	public static class NightRecord
	{
		private final int _objectId;
		private volatile String _name;
		private final AtomicInteger _kills = new AtomicInteger();
		private final AtomicInteger _nightlords = new AtomicInteger();
		private final AtomicInteger _raiders = new AtomicInteger();
		private volatile boolean _died;

		NightRecord(Player player)
		{
			_objectId = player.getObjectId();
			_name = player.getName();
		}

		public int getObjectId()
		{
			return _objectId;
		}

		public String getName()
		{
			return _name;
		}

		public int getKills()
		{
			return _kills.get();
		}

		public int getNightlords()
		{
			return _nightlords.get();
		}

		public int getRaiders()
		{
			return _raiders.get();
		}

		public int getPoints()
		{
			return (_kills.get() * NightCycleConfig.NIGHT_WATCH_KILL_POINTS) + (_nightlords.get() * NightCycleConfig.NIGHT_WATCH_NIGHTLORD_POINTS) + (_raiders.get() * NightCycleConfig.NIGHT_WATCH_RAIDER_POINTS);
		}

		/**
		 * @return {@code true} if the player died tonight
		 */
		public boolean hasDied()
		{
			return _died;
		}
	}

	protected NightCycleManager()
	{
		if (!NightCycleConfig.ENABLED)
		{
			LOGGER.info(getClass().getSimpleName() + ": Disabled.");
			return;
		}

		// Whatever the time at boot, the current phase starts quietly: nobody is online to see it, and night content still has to be set up.
		_phase = getClockPhase();
		if (_phase == NightPhase.DUSK)
		{
			rollOmen();
		}
		else if (_phase.isNight())
		{
			nightfall(false);
			if (_phase == NightPhase.WITCHING_HOUR)
			{
				witchingHour(false);
			}
		}

		Containers.Players().addListener(new ConsumerEventListener(Containers.Players(), EventType.ON_PLAYER_LOGIN, (OnPlayerLogin event) -> onLogin(event.getPlayer()), this));
		Containers.Players().addListener(new ConsumerEventListener(Containers.Players(), EventType.ON_CREATURE_DEATH, (OnCreatureDeath event) -> onPlayerDeath(event), this));
		ThreadPool.scheduleAtFixedRate(this::tick, TICK_MS, TICK_MS);
		_started = true;
		LOGGER.info(getClass().getSimpleName() + ": Started at " + _phase.getDisplayName() + (_omen != null ? ", tonight's Omen " + getOmenName(_omen) : "") + ".");
	}

	/**
	 * @return the phase the game clock is in
	 */
	private static NightPhase getClockPhase()
	{
		return NightPhase.at(GameTimeTaskManager.getInstance().getGameTime());
	}

	private void tick()
	{
		try
		{
			final NightPhase clockPhase = getClockPhase();
			if ((_forcedPhase != null) && (clockPhase != _clockPhaseAtForce))
			{
				_forcedPhase = null;
			}

			final NightPhase target = _forcedPhase != null ? _forcedPhase : clockPhase;
			if (target != _phase)
			{
				changePhase(target);
			}

			if ((++_ticks % RED_SKY_TICKS) == 0)
			{
				sendRedSky();
			}
		}
		catch (Exception e)
		{
			LOGGER.warning(getClass().getSimpleName() + ": " + e.getMessage());
		}
	}

	/**
	 * Moves to {@code next}: runs whatever the phases between need (a jump from day straight to the Witching Hour still brings the night first), then tells the scripts.
	 * @param next the phase to move to
	 */
	private synchronized void changePhase(NightPhase next)
	{
		final NightPhase previous = _phase;
		if (previous == next)
		{
			return;
		}
		_phase = next;

		if (next.isNight())
		{
			if (!previous.isNight())
			{
				nightfall(true);
			}
			if (next == NightPhase.WITCHING_HOUR)
			{
				witchingHour(true);
			}
			else if (previous == NightPhase.WITCHING_HOUR)
			{
				// Only a GM steps back from the Witching Hour into the night: the Omen comes back.
				HotzoneModifierManager.getInstance().setNightModifier(NightCycleConfig.OMENS_ENABLED ? _omen : null);
			}
		}
		else
		{
			if (previous.isNight())
			{
				daybreak(previous == NightPhase.WITCHING_HOUR);
			}
			if (next == NightPhase.DUSK)
			{
				dusk();
			}
			else if (next == NightPhase.DAY)
			{
				_omen = null;
			}
		}

		if (EventDispatcher.getInstance().hasListener(EventType.ON_NIGHT_PHASE_CHANGE))
		{
			EventDispatcher.getInstance().notifyEventAsync(new OnNightPhaseChange(previous, next));
		}
	}

	/**
	 * Dusk: tonight's Omen is rolled and told.
	 */
	private void dusk()
	{
		rollOmen();
		final StringBuilder sb = new StringBuilder("The sun is setting. Night falls in about ").append(Math.max(1, getRealMinutesToNightfall())).append(" minutes");
		if (_omen != null)
		{
			sb.append(" - tonight's Omen: ").append(getOmenName(_omen));
		}
		announce(sb.append('.').toString());
	}

	/**
	 * Night falls: the Omen lies over the open world, the Night Watch starts afresh and the Nightlords get ready to rise.
	 * @param announce {@code false} at boot, when nobody is online
	 */
	private void nightfall(boolean announce)
	{
		if (_omen == null)
		{
			rollOmen();
		}
		HotzoneModifierManager.getInstance().setNightModifier(NightCycleConfig.OMENS_ENABLED ? _omen : null);
		_nightWatch.clear();
		NightlordManager.getInstance().onNightfall();
		ShadowRaidManager.getInstance().onNightfall(getRealSecondsToDawn());

		if (announce)
		{
			// The client turns its sky by its own clock; this keeps it in step, and shows the night when a GM forces it.
			Broadcast.toAllOnlinePlayers(SunSet.STATIC_PACKET);
			announce(_omen != null ? "Night has fallen. " + getOmenName(_omen) + ": " + _omen.getDescription() : "Night has fallen.");
			sendRedSky();
			for (Player player : World.getInstance().getPlayers())
			{
				NightTraits.onPhaseChange(player, true);
			}
		}
	}

	/**
	 * The Witching Hour: the Omen gives way to {@link HotzoneModifier#WITCHING_HOUR}.
	 * @param announce {@code false} at boot, when nobody is online
	 */
	private void witchingHour(boolean announce)
	{
		if (!NightCycleConfig.WITCHING_HOUR_ENABLED)
		{
			return;
		}

		HotzoneModifierManager.getInstance().setNightModifier(HotzoneModifier.WITCHING_HOUR);
		if (announce)
		{
			announce("The Witching Hour has begun! " + HotzoneModifier.WITCHING_HOUR.getDescription());
			sendRedSky();
		}
	}

	/**
	 * Dawn: the night's modifier lifts, the Nightlords flee, the sun burns the undead still fighting, and the Night Watch is rewarded.
	 * @param fromWitchingHour {@code true} if the night ended in the Witching Hour
	 */
	private void daybreak(boolean fromWitchingHour)
	{
		HotzoneModifierManager.getInstance().setNightModifier(null);
		NightlordManager.getInstance().onDawn();
		ShadowRaidManager.getInstance().onDawn();

		Broadcast.toAllOnlinePlayers(SunRise.STATIC_PACKET);
		announce("Dawn breaks. The night is over.");
		for (Player player : World.getInstance().getPlayers())
		{
			NightTraits.onPhaseChange(player, false);
		}

		if (fromWitchingHour && NightCycleConfig.WITCHING_HOUR_ENABLED)
		{
			burnUndead();
		}

		rewardNightWatch();
		rewardSurvivors();
		crownNightKings();

		// The black market sellers pack up before the sun finds them.
		if (NightCycleConfig.NIGHT_MARKET_ENABLED)
		{
			FakePlayerTownManager.getInstance().onDawn();
		}
	}

	/**
	 * Moonlit Melee: the leader of each PvP spot (the longest kill streak) is its Night King. A player Night King is rewarded.
	 */
	private void crownNightKings()
	{
		if (!PvpSpotsConfig.ENABLED)
		{
			return;
		}

		final PvpSpotManager spots = PvpSpotManager.getInstance();
		for (SpotInfo info : spots.getSpotInfos())
		{
			if ((info.getLeader() == null) || (info.getLeaderStreak() <= 0))
			{
				continue;
			}

			announce(info.getLeader() + " is the Night King of " + info.getName() + " (" + info.getLeaderStreak() + " kills in a row)!");
			final Player king = World.getInstance().getPlayer(spots.getLeaderId(info.getZoneId()));
			if ((king != null) && king.isOnline())
			{
				if (NightCycleConfig.NIGHT_KING_COINS > 0)
				{
					king.addItem(ItemProcessType.REWARD, RatesConfig.ARENA_CURRENCY_ITEM_ID, NightCycleConfig.NIGHT_KING_COINS, null, true);
				}
				LuckyLootManager.getInstance().giveGuaranteedCache(king, king.getLevel());
			}
		}
	}

	/**
	 * The players who hunted the night through outside towns without dying and are still out there at dawn have survived the night (a hidden quest condition).
	 */
	private void rewardSurvivors()
	{
		for (NightRecord record : _nightWatch.values())
		{
			if (record._died || (record.getKills() < NightCycleConfig.NIGHT_WATCH_SURVIVOR_MIN_KILLS))
			{
				continue;
			}

			final Player player = World.getInstance().getPlayer(record.getObjectId());
			if ((player != null) && player.isOnline() && !player.isDead() && HotzoneModifierManager.isOpenWorld(player))
			{
				player.sendPacket(new ExShowScreenMessage("You survived the night.", ExShowScreenMessage.TOP_CENTER, 5000));
				HiddenQuestManager.getInstance().onNightSurvived(player);
			}
		}
	}

	/**
	 * A player died: at night it no longer survives this night.
	 * @param event the death
	 */
	private void onPlayerDeath(OnCreatureDeath event)
	{
		if (_phase.isNight() && event.getTarget().isPlayer())
		{
			getRecord(event.getTarget().asPlayer())._died = true;
		}
	}

	/**
	 * Picks tonight's Omen from the pool, if Omens are on.
	 */
	private void rollOmen()
	{
		final List<HotzoneModifier> pool = NightCycleConfig.OMEN_POOL;
		_omen = NightCycleConfig.OMENS_ENABLED && !pool.isEmpty() ? pool.get(Rnd.get(pool.size())) : null;
	}

	/**
	 * Sets tonight's Omen (a GM's choice). During the night it applies at once, unless it is the Witching Hour, which keeps its own.
	 * @param omen the Omen
	 */
	public synchronized void setOmen(HotzoneModifier omen)
	{
		_omen = omen;
		if (_phase == NightPhase.NIGHT)
		{
			HotzoneModifierManager.getInstance().setNightModifier(NightCycleConfig.OMENS_ENABLED ? omen : null);
			announce("The Omen changes: " + getOmenName(omen) + ": " + omen.getDescription());
			sendRedSky();
		}
	}

	/**
	 * At dawn the sun burns the undead still fighting a player in the open world.
	 */
	private void burnUndead()
	{
		if (NightCycleConfig.WITCHING_HOUR_DAWN_BURN_PERCENT <= 0)
		{
			return;
		}

		final Set<Monster> burnt = new HashSet<>();
		for (Player player : World.getInstance().getPlayers())
		{
			if (!player.isOnline() || !HotzoneModifierManager.isOpenWorld(player))
			{
				continue;
			}

			boolean any = false;
			for (Monster monster : World.getInstance().getVisibleObjectsInRange(player, Monster.class, DAWN_BURN_RADIUS))
			{
				if (!monster.isUndead() || monster.isDead() || !monster.isInCombat() || monster.isRaid() || monster.isRaidMinion() || !burnt.add(monster))
				{
					continue;
				}

				final double burn = monster.getCurrentHp() * (NightCycleConfig.WITCHING_HOUR_DAWN_BURN_PERCENT / 100.0);
				monster.setCurrentHp(Math.max(1, monster.getCurrentHp() - burn));
				any = true;
			}

			if (any)
			{
				player.sendPacket(new ExShowScreenMessage("The rising sun burns the undead!", ExShowScreenMessage.TOP_CENTER, 5000));
			}
		}
	}

	/**
	 * Announces the best of the Night Watch and rewards the online ones.
	 */
	private void rewardNightWatch()
	{
		if (!NightCycleConfig.NIGHT_WATCH_ENABLED || _nightWatch.isEmpty())
		{
			return;
		}

		final List<NightRecord> best = getNightWatch(3);
		final StringBuilder sb = new StringBuilder();
		for (int i = 0; i < best.size(); i++)
		{
			final NightRecord record = best.get(i);
			if (sb.length() > 0)
			{
				sb.append(", ");
			}
			sb.append(i + 1).append(". ").append(record.getName()).append(" (").append(record.getPoints()).append(')');

			final Player player = World.getInstance().getPlayer(record.getObjectId());
			if ((player == null) || !player.isOnline())
			{
				continue;
			}

			final int coins = i < NightCycleConfig.NIGHT_WATCH_COIN_REWARDS.length ? NightCycleConfig.NIGHT_WATCH_COIN_REWARDS[i] : 0;
			if (coins > 0)
			{
				player.addItem(ItemProcessType.REWARD, RatesConfig.ARENA_CURRENCY_ITEM_ID, coins, null, true);
			}

			final int caches = i < NightCycleConfig.NIGHT_WATCH_CACHE_REWARDS.length ? NightCycleConfig.NIGHT_WATCH_CACHE_REWARDS[i] : 0;
			for (int c = 0; c < caches; c++)
			{
				LuckyLootManager.getInstance().giveGuaranteedCache(player, player.getLevel());
			}
		}

		if (sb.length() > 0)
		{
			announce("The Night Watch: " + sb + ".");
		}
	}

	/**
	 * Kill hook, called from {@code Attackable.doDie} for kills that reward exp/sp: the Night Watch counts the kill, a Nightlord pays out, and in the Witching Hour the kill may call a wave challenger.
	 * @param victim the attackable that just died
	 * @param killer the player credited with the kill
	 */
	public void onAttackableKilled(Attackable victim, Player killer)
	{
		if (!NightCycleConfig.ENABLED || (victim == null) || (killer == null) || !victim.isMonster())
		{
			return;
		}

		final Monster monster = victim.asMonster();
		if (NightlordManager.isNightlord(monster))
		{
			NightlordManager.getInstance().onKilled(monster, killer);
		}

		final NightPhase phase = _phase;
		if (!phase.isNight() || !HotzoneModifierManager.isOpenWorld(victim))
		{
			return;
		}

		// Counted even with the Night Watch rewards off: surviving the night needs them.
		if (monster.getLevel() >= (killer.getLevel() - NightCycleConfig.NIGHT_WATCH_KILL_LEVEL_GAP))
		{
			getRecord(killer)._kills.incrementAndGet();
		}

		if ((phase == NightPhase.WITCHING_HOUR) && NightCycleConfig.WITCHING_HOUR_ENABLED && WaveChallengeConfig.ENABLED && ((Rnd.nextDouble() * 100) < NightCycleConfig.WITCHING_HOUR_WAVE_CHANCE))
		{
			callChallenger(monster, killer);
		}
	}

	/**
	 * The Witching Hour calls a challenger: a plain monster near the kill starts a wave challenge.
	 * @param victim the monster that just died
	 * @param killer the player who killed it
	 */
	private void callChallenger(Monster victim, Player killer)
	{
		final List<Monster> candidates = World.getInstance().getVisibleObjectsInRange(victim, Monster.class, NightCycleConfig.WITCHING_HOUR_WAVE_RADIUS, NightCycleManager::isChallengerCandidate);
		if (candidates.isEmpty())
		{
			return;
		}

		final Monster challenger = candidates.get(Rnd.get(candidates.size()));
		challenger.startWaveChallenge(NightCycleConfig.WITCHING_HOUR_WAVE_COUNT);
		challenger.broadcastSay(ChatType.NPC_GENERAL, "The Witching Hour calls me... fight me, " + killer.getName() + "!");
		killer.sendPacket(new ExShowScreenMessage("The Witching Hour calls a challenger: " + challenger.getName() + "!", ExShowScreenMessage.TOP_CENTER, 5000));
	}

	private static boolean isChallengerCandidate(Monster monster)
	{
		return !monster.isDead() && (monster.getChampionTier() == 0) && !monster.isRaid() && !monster.isRaidMinion() && !monster.isMinion() && !monster.isInCombat() && !monster.isHotzoneMiniboss() && !monster.isWaveChallenge() && !monster.getVariables().getBoolean("IS_ARENA_CHALLENGER", false) && !monster.isThief() && !monster.isMageMonster() && !monster.isInfused() && !monster.isResonant() && !monster.isHotzoneBounty() && !monster.isFakePlayer() && !monster.isPvpFakePlayer() && !monster.isQuestMonster() && !(monster instanceof Chest) && !NightlordManager.isNightlord(monster) && HotzoneModifierManager.isOpenWorld(monster);
	}

	/**
	 * A player helped slay a Nightlord.
	 * @param player the player
	 */
	public void addNightlordCredit(Player player)
	{
		if (NightCycleConfig.NIGHT_WATCH_ENABLED && (player != null))
		{
			getRecord(player)._nightlords.incrementAndGet();
		}
	}

	/**
	 * A player killed a Shadow Raider.
	 * @param player the player
	 */
	public void addRaiderCredit(Player player)
	{
		if (NightCycleConfig.NIGHT_WATCH_ENABLED && (player != null))
		{
			getRecord(player)._raiders.incrementAndGet();
		}
	}

	private NightRecord getRecord(Player player)
	{
		final NightRecord record = _nightWatch.computeIfAbsent(player.getObjectId(), id -> new NightRecord(player));
		record._name = player.getName();
		return record;
	}

	/**
	 * @param player the player
	 * @return what {@code player} did tonight, or {@code null} if nothing yet
	 */
	public NightRecord getNightRecord(Player player)
	{
		return _nightWatch.get(player.getObjectId());
	}

	/**
	 * @param count how many to return
	 * @return the best of tonight's Night Watch with at least {@link NightCycleConfig#NIGHT_WATCH_MIN_POINTS}, most points first
	 */
	public List<NightRecord> getNightWatch(int count)
	{
		final List<NightRecord> records = new ArrayList<>();
		for (NightRecord record : _nightWatch.values())
		{
			if (record.getPoints() >= NightCycleConfig.NIGHT_WATCH_MIN_POINTS)
			{
				records.add(record);
			}
		}
		records.sort(Comparator.comparingInt(NightRecord::getPoints).reversed());
		return records.size() > count ? new ArrayList<>(records.subList(0, count)) : records;
	}

	/**
	 * A player logged in: tell them about the night and show the red sky.
	 * @param player the player
	 */
	private void onLogin(Player player)
	{
		NightTraits.apply(player);

		final NightPhase phase = _phase;
		if (phase.isNight())
		{
			final HotzoneModifier modifier = HotzoneModifierManager.getInstance().getNightModifier();
			player.sendMessage("It is " + phase.getDisplayName().toLowerCase() + "." + (modifier != null ? " " + getOmenName(modifier) + ": " + modifier.getDescription() : "") + " Type .night to learn more.");
			if (isRedSky() && HotzoneModifierManager.isOpenWorld(player))
			{
				player.sendPacket(new ExRedSky(RED_SKY_SECONDS));
			}
		}
		else if ((phase == NightPhase.DUSK) && (_omen != null))
		{
			player.sendMessage("The sun is setting. Tonight's Omen: " + getOmenName(_omen) + ". Type .night to learn more.");
		}
	}

	/**
	 * @return {@code true} while the sky should be red: the Witching Hour, or a night with a red Omen
	 */
	private boolean isRedSky()
	{
		if (!NightCycleConfig.RED_SKY)
		{
			return false;
		}

		final NightPhase phase = _phase;
		if ((phase == NightPhase.WITCHING_HOUR) && NightCycleConfig.WITCHING_HOUR_ENABLED)
		{
			return true;
		}
		return (phase == NightPhase.NIGHT) && (_omen != null) && NightCycleConfig.OMENS_ENABLED && NightCycleConfig.OMEN_RED_SKY.contains(_omen);
	}

	private void sendRedSky()
	{
		if (!isRedSky())
		{
			return;
		}

		final ExRedSky packet = new ExRedSky(RED_SKY_SECONDS);
		for (Player player : World.getInstance().getPlayers())
		{
			if (player.isOnline() && HotzoneModifierManager.isOpenWorld(player))
			{
				player.sendPacket(packet);
			}
		}
	}

	/**
	 * Forces a phase (GM command). It lasts until the clock reaches its next phase.
	 * @param phase the phase, or {@code null} to follow the clock again
	 */
	public void forcePhase(NightPhase phase)
	{
		if (!NightCycleConfig.ENABLED)
		{
			return;
		}

		_clockPhaseAtForce = getClockPhase();
		_forcedPhase = phase;
		changePhase(phase != null ? phase : _clockPhaseAtForce);
	}

	/**
	 * @return {@code true} while a GM-forced phase overrides the clock
	 */
	public boolean isForced()
	{
		return _forcedPhase != null;
	}

	public NightPhase getPhase()
	{
		return _phase;
	}

	/**
	 * @return {@code true} once the manager runs, so {@link #isNight()} can be asked without starting it (see {@link org.l2jmobius.gameserver.model.conditions.ConditionGameTime})
	 */
	public static boolean isStarted()
	{
		return _started;
	}

	/**
	 * @return {@code true} while the Night Cycle runs a phase of the night (a GM-forced one too)
	 */
	public boolean isNight()
	{
		return NightCycleConfig.ENABLED && _phase.isNight();
	}

	/**
	 * @return the real seconds until dawn, 0 by day. A GM-forced night lasts until the clock reaches its next phase.
	 */
	public int getRealSecondsToDawn()
	{
		if (!isNight())
		{
			return 0;
		}

		final int gameTime = GameTimeTaskManager.getInstance().getGameTime();
		final int gameMinutes = isForced() ? NightPhase.minutesLeft(gameTime) : Math.max(1, NightPhase.NIGHT_END - Math.floorMod(gameTime, NightPhase.MINUTES_PER_DAY));
		return gameMinutes * 10;
	}

	/**
	 * @return the share of the town fake players that stay in town: lower at night with the Night Market on
	 */
	public double getTownPopulationFactor()
	{
		return isNight() && NightCycleConfig.NIGHT_MARKET_ENABLED ? NightCycleConfig.NIGHT_MARKET_TOWN_POPULATION / 100.0 : 1.0;
	}

	/**
	 * @return how many black market stores each town has on top of its usual stores: some at night with the Night Market on
	 */
	public int getBlackMarketStores()
	{
		return isNight() && NightCycleConfig.NIGHT_MARKET_ENABLED ? NightCycleConfig.NIGHT_MARKET_BLACK_MARKET_STORES : 0;
	}

	/**
	 * @param monster a monster about to be rolled as a Thief
	 * @return the Thieves' Night multiplier on its Thief chance: {@link NightCycleConfig#NIGHT_THIEF_SPAWN_MULTIPLIER} in the open world at night
	 */
	public double getThiefSpawnMultiplier(Creature monster)
	{
		return isNight() && NightCycleConfig.NIGHT_MARKET_ENABLED && HotzoneModifierManager.isOpenWorld(monster) ? NightCycleConfig.NIGHT_THIEF_SPAWN_MULTIPLIER : 1.0;
	}

	/**
	 * @param thief a Thief whose bag is full
	 * @return {@code true} if it runs off into the dark: Thieves' Night in the open world
	 */
	public boolean isThievesNight(Creature thief)
	{
		return isNight() && NightCycleConfig.NIGHT_MARKET_ENABLED && NightCycleConfig.NIGHT_THIEVES_FLEE && HotzoneModifierManager.isOpenWorld(thief);
	}

	/**
	 * @return tonight's Omen, or {@code null} by day or if Omens are off
	 */
	public HotzoneModifier getOmen()
	{
		return _omen;
	}

	/**
	 * @return the real minutes until the clock's phase ends (a forced phase ends with it)
	 */
	public int getRealMinutesLeft()
	{
		return Math.max(1, (int) Math.ceil(NightPhase.minutesLeft(GameTimeTaskManager.getInstance().getGameTime()) / 6.0));
	}

	/**
	 * @return the real minutes until the next nightfall, 0 while it is night
	 */
	public int getRealMinutesToNightfall()
	{
		return (int) Math.ceil(NightPhase.minutesToNightfall(GameTimeTaskManager.getInstance().getGameTime()) / 6.0);
	}

	/**
	 * @param modifier a modifier
	 * @return its name as players read it, e.g. "Blood Moon"
	 */
	public static String getOmenName(HotzoneModifier modifier)
	{
		final StringBuilder sb = new StringBuilder();
		for (String word : modifier.name().split("_"))
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
	 * Tells every online player, on screen and in the announcement chat.
	 * @param text the text
	 */
	public static void announce(String text)
	{
		if (!NightCycleConfig.ANNOUNCE)
		{
			return;
		}

		Broadcast.toAllOnlinePlayers(new ExShowScreenMessage(text, ExShowScreenMessage.TOP_CENTER, 8000));
		Broadcast.toAllOnlinePlayers(text, false);
	}

	public static NightCycleManager getInstance()
	{
		return SingletonHolder.INSTANCE;
	}

	private static class SingletonHolder
	{
		protected static final NightCycleManager INSTANCE = new NightCycleManager();
	}
}
