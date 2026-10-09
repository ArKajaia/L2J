package org.l2jmobius.gameserver.managers;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;

import org.l2jmobius.commons.threads.ThreadPool;
import org.l2jmobius.commons.util.Rnd;
import org.l2jmobius.gameserver.config.RatesConfig;
import org.l2jmobius.gameserver.config.custom.NightCycleConfig;
import org.l2jmobius.gameserver.data.xml.MapRegionData;
import org.l2jmobius.gameserver.model.Location;
import org.l2jmobius.gameserver.model.World;
import org.l2jmobius.gameserver.model.actor.Attackable;
import org.l2jmobius.gameserver.model.actor.Creature;
import org.l2jmobius.gameserver.model.actor.Npc;
import org.l2jmobius.gameserver.model.actor.Player;
import org.l2jmobius.gameserver.model.actor.holders.npc.FakePlayerHolder;
import org.l2jmobius.gameserver.model.clan.Clan;
import org.l2jmobius.gameserver.model.hiddenquest.task.AbstractHiddenTask;
import org.l2jmobius.gameserver.model.item.enums.ItemProcessType;
import org.l2jmobius.gameserver.model.zone.ZoneId;

/**
 * Shadow Raids: at night, warbands of a fake clan raid the open world near a player who hunts there (see {@link NightCycleManager}). The raiders are roaming fake players of one clan - one at war with another fake clan if there is one - and they attack every player and roaming
 * fake player nearby who isn't of their clan or alliance. Killing a raider is never a PK, pays hot zone coins and a Sealed Cache, and counts for the Night Watch. A raid ends when its warband falls, after {@link NightCycleConfig#NIGHT_RAID_DURATION}, or at dawn, when the
 * raiders still standing melt into the night.
 */
public class ShadowRaidManager
{
	private static final Logger LOGGER = Logger.getLogger(ShadowRaidManager.class.getName());

	/** What {@link #startRaid} says when nobody hunts in the open world. */
	public static final String NO_PLAYER = "nobody hunts in the open world";

	/** Set on a raider: its kill is never a PK, and pays the raid reward. */
	public static final String RAIDER_VAR = "SHADOW_RAIDER";

	/** How often (ms) the raiders look for someone to attack, and the raids check their end. */
	private static final long TICK_MS = 3000;
	/** The first raid of a night comes this long (s) after nightfall, at the earliest and the latest. */
	private static final int FIRST_RAID_MIN = 180;
	private static final int FIRST_RAID_MAX = 300;
	/** How far (game units) from the player the warband lands. */
	private static final int LAND_MIN = 800;
	private static final int LAND_MAX = 1500;
	/** Lowest level of a player a raid comes for. */
	private static final int MIN_PLAYER_LEVEL = 20;

	private final AtomicInteger _nextId = new AtomicInteger(1);
	private final Map<Integer, Raid> _raids = new ConcurrentHashMap<>();
	private final List<ScheduledFuture<?>> _scheduled = new CopyOnWriteArrayList<>();
	private ScheduledFuture<?> _tickTask;

	/**
	 * One raid: its warband, its clan and where it struck.
	 */
	public static class Raid
	{
		private final int _id;
		private final Clan _clan;
		private final String _place;
		private final long _endsAt;
		private final List<Npc> _raiders = new CopyOnWriteArrayList<>();

		Raid(int id, Clan clan, String place, long endsAt)
		{
			_id = id;
			_clan = clan;
			_place = place;
			_endsAt = endsAt;
		}

		public int getId()
		{
			return _id;
		}

		public String getClanName()
		{
			return _clan.getName();
		}

		public String getPlace()
		{
			return _place;
		}

		/**
		 * @return the raiders still standing
		 */
		public List<Npc> getAliveRaiders()
		{
			final List<Npc> alive = new ArrayList<>();
			for (Npc raider : _raiders)
			{
				if (raider.isSpawned() && !raider.isDead())
				{
					alive.add(raider);
				}
			}
			return alive;
		}
	}

	protected ShadowRaidManager()
	{
	}

	private static boolean isEnabled()
	{
		return NightCycleConfig.ENABLED && NightCycleConfig.NIGHT_RAID_ENABLED && FakePlayerPvpManager.getInstance().isEnabled() && FakeClanManager.getInstance().isEnabled();
	}

	/**
	 * Night fell: the night's raids are set for random times before the Witching Hour.
	 * @param secondsToDawn the real seconds until dawn; passed in because {@link NightCycleManager} calls this from its constructor at a night boot, before {@link NightCycleManager#getInstance()} can return it
	 */
	public synchronized void onNightfall(int secondsToDawn)
	{
		cancelScheduled();
		if (!isEnabled() || (NightCycleConfig.NIGHT_RAID_COUNT <= 0))
		{
			return;
		}

		// Before the Witching Hour, which has its own dead to deal with.
		final int nightSeconds = secondsToDawn - (NightCycleConfig.WITCHING_HOUR_MINUTES * 10);
		final int last = Math.max(FIRST_RAID_MAX, nightSeconds - 300);
		for (int i = 0; i < NightCycleConfig.NIGHT_RAID_COUNT; i++)
		{
			final int delay = i == 0 ? Rnd.get(FIRST_RAID_MIN, FIRST_RAID_MAX) : Rnd.get(FIRST_RAID_MAX, last);
			_scheduled.add(ThreadPool.schedule(() -> startRaid(null), delay * 1000L));
		}
	}

	/**
	 * Dawn: the raiders still standing melt into the night.
	 */
	public synchronized void onDawn()
	{
		cancelScheduled();
		int fled = 0;
		for (Raid raid : _raids.values())
		{
			fled += endRaid(raid);
		}
		if (fled > 0)
		{
			NightCycleManager.announce("The sun rises. The Shadow Raiders melt into the night.");
		}
	}

	private void cancelScheduled()
	{
		for (ScheduledFuture<?> task : _scheduled)
		{
			task.cancel(false);
		}
		_scheduled.clear();
	}

	/**
	 * Starts a raid near {@code anchor}, or near a random player hunting in the open world.
	 * @param anchor the player the raid comes for, {@code null} for a random one
	 * @return {@code null} if the raid started, else why not
	 */
	public synchronized String startRaid(Player anchor)
	{
		if (!isEnabled())
		{
			return "Shadow Raids are disabled, or the fake players or their clans are";
		}

		final Player target = anchor != null ? anchor : pickPlayer();
		if (target == null)
		{
			return NO_PLAYER;
		}

		final Clan clan = pickClan();
		if (clan == null)
		{
			return "there is no clan of fake players";
		}

		final String place = MapRegionData.getInstance().getClosestTownName(target);
		final Raid raid = new Raid(_nextId.getAndIncrement(), clan, place, System.currentTimeMillis() + (NightCycleConfig.NIGHT_RAID_DURATION * 60000L));
		final Location landing = AbstractHiddenTask.findPoint(target, LAND_MIN, LAND_MAX, false, target.getInstanceId());
		final int size = Rnd.get(NightCycleConfig.NIGHT_RAID_SIZE_MIN, NightCycleConfig.NIGHT_RAID_SIZE_MAX);
		for (int i = 0; i < size; i++)
		{
			final Location spot = AbstractHiddenTask.findPoint(landing, 30, 200, false, target.getInstanceId());
			final int level = Math.max(1, Math.min(85, target.getLevel() + Rnd.get(-2, 2)));
			final Npc raider = FakePlayerPvpManager.getInstance().spawnFakePlayer(null, level, spot.getX(), spot.getY(), spot.getZ(), target.getInstanceId(), null, null);
			if (raider == null)
			{
				continue;
			}

			final FakePlayerHolder info = raider.getTemplate().getFakePlayerInfo();
			if (info != null)
			{
				info.setClan(clan.getId(), "Shadow Raider");
			}
			raider.getVariables().set(RAIDER_VAR, true);
			raider.broadcastInfo();
			raid._raiders.add(raider);
		}

		if (raid._raiders.isEmpty())
		{
			return "no raider could be spawned";
		}

		_raids.put(raid._id, raid);
		if (_tickTask == null)
		{
			_tickTask = ThreadPool.scheduleAtFixedRate(this::tick, TICK_MS, TICK_MS);
		}

		NightCycleManager.announce("Shadow Raid! " + raid._raiders.size() + " warriors of " + clan.getName() + " are raiding near " + place + ". Type .night to find them.");
		LOGGER.info(getClass().getSimpleName() + ": " + clan.getName() + " raids near " + place + " (" + target.getName() + ") with " + raid._raiders.size() + " raiders.");
		return null;
	}

	/**
	 * @return a random online player of level {@link #MIN_PLAYER_LEVEL}+ hunting in the open world, {@code null} if none
	 */
	private static Player pickPlayer()
	{
		final List<Player> candidates = new ArrayList<>();
		for (Player player : World.getInstance().getPlayers())
		{
			if (player.isOnline() && !player.isInOfflineMode() && !player.isDead() && (player.getLevel() >= MIN_PLAYER_LEVEL) && !player.isGM() && HotzoneModifierManager.isOpenWorld(player) && !player.isInsideZone(ZoneId.WATER) && !player.isFlying())
			{
				candidates.add(player);
			}
		}
		return candidates.isEmpty() ? null : candidates.get(Rnd.get(candidates.size()));
	}

	/**
	 * @return a clan of fake players to raid with: one at war with another one if there is one
	 */
	private static Clan pickClan()
	{
		final List<Clan> clans = FakeClanManager.getInstance().getActiveClans();
		if (clans.isEmpty())
		{
			return null;
		}

		final List<Clan> atWar = new ArrayList<>();
		for (Clan clan : clans)
		{
			for (Clan other : clans)
			{
				if ((clan != other) && clan.isAtWarWith(other.getId()))
				{
					atWar.add(clan);
					break;
				}
			}
		}
		final List<Clan> pool = atWar.isEmpty() ? clans : atWar;
		return pool.get(Rnd.get(pool.size()));
	}

	/**
	 * The raiders go for whoever is near, and the raids that are over end.
	 */
	private void tick()
	{
		try
		{
			final long now = System.currentTimeMillis();
			for (Raid raid : _raids.values())
			{
				final List<Npc> alive = raid.getAliveRaiders();
				if (alive.isEmpty())
				{
					_raids.remove(raid._id);
					continue;
				}

				if (now >= raid._endsAt)
				{
					endRaid(raid);
					NightCycleManager.announce("The " + raid._clan.getName() + " raid near " + raid._place + " is over. The raiders melt into the night.");
					continue;
				}

				for (Npc raider : alive)
				{
					if (!FakePlayerPvpManager.isInPvp(raider) && raider.isAttackable())
					{
						final Creature target = findTarget(raider);
						if (target != null)
						{
							FakePlayerPvpManager.getInstance().attackWarEnemy(raider.asAttackable(), target);
						}
					}
				}
			}

			synchronized (this)
			{
				if (_raids.isEmpty() && (_tickTask != null))
				{
					_tickTask.cancel(false);
					_tickTask = null;
				}
			}
		}
		catch (Exception e)
		{
			LOGGER.warning(getClass().getSimpleName() + ": " + e.getMessage());
		}
	}

	/**
	 * @param raider a raider
	 * @return the nearest player (or else roaming fake player) it goes for, {@code null} if none
	 */
	private static Creature findTarget(Npc raider)
	{
		Creature best = null;
		double bestDistance = Double.MAX_VALUE;
		boolean bestIsPlayer = false;
		for (Creature creature : World.getInstance().getVisibleObjectsInRange(raider, Creature.class, NightCycleConfig.NIGHT_RAID_AGGRO_RANGE))
		{
			final boolean player = creature.isPlayer();
			if ((!player && !creature.isPvpFakePlayer()) || !isTarget(raider, creature))
			{
				continue;
			}

			// Players first.
			final double distance = raider.calculateDistance3D(creature);
			if ((best == null) || (player && !bestIsPlayer) || ((player == bestIsPlayer) && (distance < bestDistance)))
			{
				best = creature;
				bestDistance = distance;
				bestIsPlayer = player;
			}
		}
		return best;
	}

	private static boolean isTarget(Npc raider, Creature creature)
	{
		if (creature.isAlikeDead() || creature.isInvisible() || (creature.getInstanceId() != raider.getInstanceId()) || isRaider(creature))
		{
			return false;
		}

		final Player player = creature.asPlayer();
		if ((player != null) && ((player.isGM() && !player.getAccessLevel().canTakeAggro()) || player.isInOlympiadMode() || player.isInDuel() || player.isInOfflineMode()))
		{
			return false;
		}

		final int level = creature.getLevel();
		if ((level > (raider.getLevel() + NightCycleConfig.NIGHT_RAID_LEVELS_ABOVE)) || (level < (raider.getLevel() - NightCycleConfig.NIGHT_RAID_LEVELS_BELOW)))
		{
			return false;
		}

		return HotzoneModifierManager.isOpenWorld(creature) && !FakeClanManager.getInstance().isFriend(raider, creature);
	}

	/**
	 * Removes the raiders still standing.
	 * @param raid the raid
	 * @return how many there were
	 */
	private int endRaid(Raid raid)
	{
		_raids.remove(raid._id);
		final List<Npc> alive = raid.getAliveRaiders();
		for (Npc raider : alive)
		{
			raider.deleteMe();
		}
		return alive.size();
	}

	/**
	 * A player killed a raider: hot zone coins, a Sealed Cache and Night Watch points. Called from {@code Attackable.doDie}.
	 * @param victim the fake player that died
	 * @param killer the player credited with the kill
	 */
	public void onRaiderKilled(Attackable victim, Player killer)
	{
		if ((victim == null) || (killer == null) || !isRaider(victim))
		{
			return;
		}

		if (NightCycleConfig.NIGHT_RAID_KILL_COINS > 0)
		{
			killer.addItem(ItemProcessType.REWARD, RatesConfig.ARENA_CURRENCY_ITEM_ID, NightCycleConfig.NIGHT_RAID_KILL_COINS, victim, true);
		}
		LuckyLootManager.getInstance().dropGuaranteedCache(victim, killer);
		NightCycleManager.getInstance().addRaiderCredit(killer);

		for (Raid raid : _raids.values())
		{
			if (raid._raiders.contains(victim) && raid.getAliveRaiders().isEmpty() && (_raids.remove(raid._id) != null))
			{
				NightCycleManager.announce(killer.getName() + " drove off the " + raid._clan.getName() + " raid near " + raid._place + "!");
			}
		}
	}

	/**
	 * @param creature a creature
	 * @return {@code true} if it is a Shadow Raider: killing it is never a PK
	 */
	public static boolean isRaider(Creature creature)
	{
		return (creature != null) && creature.isNpc() && creature.asNpc().hasVariables() && creature.asNpc().getVariables().getBoolean(RAIDER_VAR, false);
	}

	/**
	 * @return the raids going on, oldest first
	 */
	public List<Raid> getRaids()
	{
		final List<Raid> raids = new ArrayList<>(_raids.values());
		raids.sort((a, b) -> Integer.compare(a._id, b._id));
		return raids;
	}

	/**
	 * @param id a raid id
	 * @return the raid, {@code null} if it is over
	 */
	public Raid getRaid(int id)
	{
		return _raids.get(id);
	}

	public static ShadowRaidManager getInstance()
	{
		return SingletonHolder.INSTANCE;
	}

	private static class SingletonHolder
	{
		protected static final ShadowRaidManager INSTANCE = new ShadowRaidManager();
	}
}
