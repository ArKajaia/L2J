package org.l2jmobius.gameserver.managers;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;

import org.l2jmobius.commons.threads.ThreadPool;
import org.l2jmobius.commons.util.Rnd;
import org.l2jmobius.gameserver.ai.Intention;
import org.l2jmobius.gameserver.config.PlayerConfig;
import org.l2jmobius.gameserver.config.RatesConfig;
import org.l2jmobius.gameserver.config.custom.FakePlayerPvpConfig;
import org.l2jmobius.gameserver.config.custom.NightCycleConfig;
import org.l2jmobius.gameserver.config.custom.ResonantMonsterConfig;
import org.l2jmobius.gameserver.model.Location;
import org.l2jmobius.gameserver.model.World;
import org.l2jmobius.gameserver.model.actor.Attackable;
import org.l2jmobius.gameserver.model.actor.Creature;
import org.l2jmobius.gameserver.model.actor.Npc;
import org.l2jmobius.gameserver.model.actor.Player;
import org.l2jmobius.gameserver.model.actor.holders.npc.AggroInfo;
import org.l2jmobius.gameserver.model.actor.instance.FakePlayerPvpServitor;
import org.l2jmobius.gameserver.model.actor.instance.Monster;
import org.l2jmobius.gameserver.model.groups.Party;
import org.l2jmobius.gameserver.model.hiddenquest.task.AbstractHiddenTask;
import org.l2jmobius.gameserver.model.item.enums.ItemProcessType;

/**
 * Fake players that turn up in a big fight: a Resonant monster ({@link ResonantMonsterManager}) or a Nightlord ({@link NightlordManager}), see {@link Fight}. Each time the fight drops past one of its HP percents
 * ({@code Monster#tryFakeJoin()}), a fake player of about the monster's level may turn up near it and run in. The HP percents are rolled for each life of the monster: each one moves by up to the fight's variance and is skipped with
 * the fight's no-show chance ({@link #rollThresholds(Fight)}).
 * <ul>
 * <li>A helper fights the monster on the side of the player who has hurt it most and of that player's party, without joining the party: it doesn't hit them, its area skills spare them, and its damage and kills on the monster count
 * for them ({@link #getHelpedPlayer(Creature, Attackable)}), so it never takes the kill away. It tells that player it can be invited, and accepts an invite from that party's leader ({@link #isHelperFor(Npc, Player)}). It never
 * takes kill-steal revenge and never comes back to a party from town.</li>
 * <li>A rival wants the kill for itself, may open on that player, and killing it is never a PK and pays ({@link #onRivalKilled(Attackable, Player)}).</li>
 * </ul>
 * When the monster is gone, or the players gave up, the joiners go their way and later log off like the other fake players.
 */
public class FakeJoinerManager
{
	/** Set for good on a fake player that came to help: it never comes back to a party from town and never takes kill-steal revenge. */
	private static final String HELPER_VAR = "FIGHT_HELPER";
	/** How far from the monster a fake player turns up, so it runs into the fight. */
	private static final int JOIN_MIN_DISTANCE = 600;
	private static final int JOIN_MAX_DISTANCE = 900;
	/** How often a fight with joiners is checked for being given up ({@link #checkFight(Monster, Watch)}). */
	private static final int WATCH_INTERVAL_MS = 15000;
	/** A fight is given up once no living player of the monster's aggro list is this close to it, twice in a row. */
	private static final int ABANDON_RANGE = 2000;

	private static final String[] HELPER_OFFERS =
	{
		"Need a hand? I'll help you with this one. Invite me if you like.",
		"I'm with you on this one. Send me an invite if you want me in the party.",
		"Looks tough, I'll help. Invite me if you want.",
		"Don't mind me, just helping. Party invite is welcome.",
	};

	/**
	 * The fights fake players join.
	 */
	public enum Fight
	{
		RESONANT
		{
			@Override
			public int[] getHpPercents()
			{
				return ResonantMonsterConfig.FAKE_JOIN_HP_PERCENTS;
			}

			@Override
			public int getHpVariance()
			{
				return ResonantMonsterConfig.FAKE_JOIN_HP_VARIANCE;
			}

			@Override
			public int getNoShowChance()
			{
				return ResonantMonsterConfig.FAKE_JOIN_NO_SHOW_CHANCE;
			}

			@Override
			public int getHelperChance()
			{
				return ResonantMonsterConfig.FAKE_HELPER_CHANCE;
			}

			@Override
			public int getRivalAttackPlayerChance()
			{
				return ResonantMonsterConfig.RIVAL_ATTACK_PLAYER_CHANCE;
			}

			@Override
			public long getRivalKillCoins()
			{
				return ResonantMonsterConfig.RIVAL_KILL_COINS;
			}

			@Override
			public boolean isRivalKillCache()
			{
				return ResonantMonsterConfig.RIVAL_KILL_CACHE;
			}

			@Override
			public int getLeaveDelay()
			{
				return ResonantMonsterConfig.FAKE_LEAVE_DELAY;
			}

			@Override
			public String getStolenMessage(String rival, String monster)
			{
				return rival + " stole the soul of " + monster + "!";
			}
		},
		NIGHTLORD
		{
			@Override
			public int[] getHpPercents()
			{
				return NightCycleConfig.NIGHTLORD_FAKE_JOIN_HP_PERCENTS;
			}

			@Override
			public int getHpVariance()
			{
				return NightCycleConfig.NIGHTLORD_FAKE_JOIN_HP_VARIANCE;
			}

			@Override
			public int getNoShowChance()
			{
				return NightCycleConfig.NIGHTLORD_FAKE_JOIN_NO_SHOW_CHANCE;
			}

			@Override
			public int getHelperChance()
			{
				return NightCycleConfig.NIGHTLORD_FAKE_HELPER_CHANCE;
			}

			@Override
			public int getRivalAttackPlayerChance()
			{
				return NightCycleConfig.NIGHTLORD_RIVAL_ATTACK_PLAYER_CHANCE;
			}

			@Override
			public long getRivalKillCoins()
			{
				return NightCycleConfig.NIGHTLORD_RIVAL_KILL_COINS;
			}

			@Override
			public boolean isRivalKillCache()
			{
				return NightCycleConfig.NIGHTLORD_RIVAL_KILL_CACHE;
			}

			@Override
			public int getLeaveDelay()
			{
				return NightCycleConfig.NIGHTLORD_FAKE_LEAVE_DELAY;
			}

			@Override
			public String getStolenMessage(String rival, String monster)
			{
				return rival + " stole the Nightlord " + monster + " from you!";
			}
		};

		/**
		 * @return the HP percents at which a fake player joins, highest first
		 */
		public abstract int[] getHpPercents();

		/**
		 * @return by how many HP percent points each of {@link #getHpPercents()} moves, either way, for each life of the monster
		 */
		public abstract int getHpVariance();

		/**
		 * @return the chance (%) that nobody turns up at one of {@link #getHpPercents()}, rolled for each life of the monster
		 */
		public abstract int getNoShowChance();

		/**
		 * @return the chance (%) that a joiner is a helper, otherwise it is a rival
		 */
		public abstract int getHelperChance();

		/**
		 * @return the chance (%) that a rival opens on the player who has hurt the monster most
		 */
		public abstract int getRivalAttackPlayerChance();

		/**
		 * @return the hot zone coins killing a rival pays
		 */
		public abstract long getRivalKillCoins();

		/**
		 * @return {@code true} if killing a rival drops a Sealed Cache
		 */
		public abstract boolean isRivalKillCache();

		/**
		 * @return seconds after the fight before the joiners go their way
		 */
		public abstract int getLeaveDelay();

		/**
		 * @param rival the rival's name
		 * @param monster the monster's name
		 * @return what the players are told when a rival lands the killing blow
		 */
		public abstract String getStolenMessage(String rival, String monster);
	}

	/**
	 * A fake player in a fight: the monster it came for, and for a helper the player it came to help.
	 */
	private static final class Joiner
	{
		private final Fight _fight;
		private final int _monsterId;
		private final int _hostId;

		Joiner(Fight fight, int monsterId, int hostId)
		{
			_fight = fight;
			_monsterId = monsterId;
			_hostId = hostId;
		}

		boolean isHelper()
		{
			return _hostId != 0;
		}
	}

	private static final class Watch
	{
		private volatile ScheduledFuture<?> _task;
		private int _idleChecks;
	}

	/** Monster object id -&gt; the fake players that joined its fight. */
	private final Map<Integer, Set<Npc>> _joiners = new ConcurrentHashMap<>();
	/** Monster object id -&gt; the check of its fight while it has joiners. */
	private final Map<Integer, Watch> _watches = new ConcurrentHashMap<>();
	/** Fake player object id -&gt; the fight it is in, until it goes its way (or, for a rival, is killed). */
	private final Map<Integer, Joiner> _active = new ConcurrentHashMap<>();

	protected FakeJoinerManager()
	{
	}

	// ---------------------------------------------------------------------------------------------
	// Thresholds
	// ---------------------------------------------------------------------------------------------

	/**
	 * @param monster a monster
	 * @return the fight fake players join on {@code monster}, {@code null} if none
	 */
	public static Fight getFight(Monster monster)
	{
		if (monster.isResonant())
		{
			return Fight.RESONANT;
		}
		if (NightCycleConfig.NIGHTLORD_ENABLED && monster.hasVariables() && NightlordManager.isNightlord(monster))
		{
			return Fight.NIGHTLORD;
		}
		return null;
	}

	/**
	 * @param monster a monster of a fight (see {@link #getFight(Monster)})
	 * @param fight its fight
	 * @return how much of the fight is left, in percent: its HP, or for a Nightlord its HP over all its waves
	 */
	public static double getFightHpPercent(Monster monster, Fight fight)
	{
		final double hpPercent = (monster.getCurrentHp() * 100.0) / monster.getMaxHp();
		if ((fight != Fight.NIGHTLORD) || !monster.isWaveChallenge())
		{
			return hpPercent;
		}

		final int total = Math.max(1, monster.getWaveChallengeTotal());
		final int wave = Math.max(1, Math.min(total, monster.getWaveChallengeWave()));
		return (((total - wave) * 100.0) + hpPercent) / total;
	}

	/**
	 * Rolls the HP percents of one life (or attempt) of a fight: each of {@link Fight#getHpPercents()} moves by up to {@link Fight#getHpVariance()} either way, and is left out with {@link Fight#getNoShowChance()}.
	 * @param fight the fight
	 * @return the HP percents, highest first, maybe none
	 */
	public static int[] rollThresholds(Fight fight)
	{
		final int[] base = fight.getHpPercents();
		final int variance = Math.max(0, fight.getHpVariance());
		final List<Integer> rolled = new ArrayList<>(base.length);
		for (int percent : base)
		{
			if (Rnd.get(100) < fight.getNoShowChance())
			{
				continue;
			}
			rolled.add(Math.max(1, Math.min(99, percent + (variance > 0 ? Rnd.get(-variance, variance) : 0))));
		}
		return rolled.stream().sorted((a, b) -> Integer.compare(b, a)).limit(30).mapToInt(Integer::intValue).toArray();
	}

	/**
	 * @param passed the thresholds of {@code thresholds} already passed this life, one bit each
	 * @param hpPercent how much of the fight is left now, in percent
	 * @param thresholds the HP percents ({@link #rollThresholds(Fight)})
	 * @return {@code passed} with the bit of every threshold {@code hpPercent} is at or below
	 */
	public static int passThresholds(int passed, double hpPercent, int[] thresholds)
	{
		int result = passed;
		for (int i = 0; (i < thresholds.length) && (i < 31); i++)
		{
			if (hpPercent <= thresholds[i])
			{
				result |= 1 << i;
			}
		}
		return result;
	}

	/**
	 * A fight dropped past one of its HP percents: a fake player turns up near it (spawned off the combat thread).
	 * @param monster the monster
	 * @param fight its fight
	 */
	public void onThreshold(Monster monster, Fight fight)
	{
		ThreadPool.execute(() -> spawnJoiner(monster, fight, null));
	}

	// ---------------------------------------------------------------------------------------------
	// Joining
	// ---------------------------------------------------------------------------------------------

	/**
	 * Spawns a fake player near a fight's monster, of about its level, that joins the fight: a helper ({@link Fight#getHelperChance()}%) on the side of the player who has hurt the monster most, otherwise a rival. The side isn't told.
	 * @param monster the monster
	 * @param fight its fight
	 * @param helper {@code true} for a helper, {@code false} for a rival, {@code null} to roll
	 * @return the fake player, or {@code null} if none could come (fake players off or at their limit, nobody fighting the monster, no place to stand)
	 */
	public Npc spawnJoiner(Monster monster, Fight fight, Boolean helper)
	{
		if ((monster == null) || (fight == null) || (getFight(monster) != fight) || monster.isDead() || !monster.isSpawned())
		{
			return null;
		}

		final FakePlayerPvpManager fakes = FakePlayerPvpManager.getInstance();
		if (!fakes.isEnabled() || ((FakePlayerPvpConfig.MAX_ALIVE > 0) && (fakes.getFakePlayers().size() >= FakePlayerPvpConfig.MAX_ALIVE)))
		{
			return null;
		}

		final Player host = getTopAttacker(monster);
		if (host == null)
		{
			return null;
		}

		final Location spot = AbstractHiddenTask.findPoint(monster, JOIN_MIN_DISTANCE, JOIN_MAX_DISTANCE, false, monster.getInstanceId());
		if (spot == null)
		{
			return null;
		}

		final int level = Math.max(Math.max(1, FakePlayerPvpConfig.MIN_LEVEL), Math.min(FakePlayerPvpConfig.MAX_LEVEL, monster.getLevel() + Rnd.get(-2, 2)));
		final Npc fake = fakes.spawnFakePlayer(null, level, spot.getX(), spot.getY(), spot.getZ(), monster.getInstanceId(), null, null);
		if (fake == null)
		{
			return null;
		}

		_joiners.computeIfAbsent(monster.getObjectId(), _ -> ConcurrentHashMap.newKeySet()).add(fake);
		watch(monster);
		sendToAttackers(monster, fake.getName() + " joins the fight against " + monster.getName() + "!");

		final boolean wantsToHelp = helper != null ? helper : (Rnd.get(100) < fight.getHelperChance());
		if (wantsToHelp)
		{
			fake.getVariables().set(HELPER_VAR, true);
			_active.put(fake.getObjectId(), new Joiner(fight, monster.getObjectId(), host.getObjectId()));
			fakes.assistFight(fake.asAttackable(), monster);
			if (FakePartyManager.getInstance().isEnabled())
			{
				ThreadPool.schedule(() -> offerHelp(fake, host), Rnd.get(2000, 6000));
			}
		}
		else
		{
			_active.put(fake.getObjectId(), new Joiner(fight, monster.getObjectId(), 0));
			fakes.assistFight(fake.asAttackable(), Rnd.get(100) < fight.getRivalAttackPlayerChance() ? host : monster);
		}

		// The fight may have ended while it was on its way.
		if (monster.isDead() || !monster.isSpawned())
		{
			onFightGone(monster);
		}
		return fake;
	}

	/**
	 * A helper tells the player it came for that it can be invited.
	 * @param fake the helper
	 * @param host the player
	 */
	private void offerHelp(Npc fake, Player host)
	{
		if (!fake.isDead() && fake.isSpawned() && host.isOnline() && isHelperFor(fake, host) && !FakePartyManager.getInstance().isInPlayerParty(fake))
		{
			FakePartyManager.whisper(fake, host, HELPER_OFFERS[Rnd.get(HELPER_OFFERS.length)]);
		}
	}

	// ---------------------------------------------------------------------------------------------
	// Leaving
	// ---------------------------------------------------------------------------------------------

	/**
	 * The fight's monster died, fled or was removed: the fake players that joined its fight go their way {@link Fight#getLeaveDelay()} seconds later (see {@link #release(Npc, int)}).
	 * @param monster the monster
	 */
	public void onFightGone(Monster monster)
	{
		final Watch watch = _watches.remove(monster.getObjectId());
		if ((watch != null) && (watch._task != null))
		{
			watch._task.cancel(false);
		}

		final Set<Npc> joiners = _joiners.remove(monster.getObjectId());
		if ((joiners == null) || joiners.isEmpty())
		{
			return;
		}

		int delay = 0;
		for (Npc fake : joiners)
		{
			final Joiner joiner = _active.get(fake.getObjectId());
			delay = Math.max(delay, joiner != null ? joiner._fight.getLeaveDelay() : 0);
		}

		final int leaveDelay = delay;
		ThreadPool.schedule(() ->
		{
			for (Npc fake : joiners)
			{
				release(fake, leaveDelay, 0);
			}
		}, leaveDelay * 1000L);
	}

	/**
	 * Starts checking a fight for being given up, once, when its first joiner comes.
	 * @param monster the monster
	 */
	private void watch(Monster monster)
	{
		_watches.computeIfAbsent(monster.getObjectId(), _ ->
		{
			final Watch watch = new Watch();
			watch._task = ThreadPool.scheduleAtFixedRate(() -> checkFight(monster, watch), WATCH_INTERVAL_MS, WATCH_INTERVAL_MS);
			return watch;
		});
	}

	/**
	 * A fight with joiners ends without a death too: when the monster is gone, or when no living player of its aggro list has been within {@link #ABANDON_RANGE} of it twice in a row (the party gave up). Then the joiners go their way, and the
	 * monster's fake player thresholds are rolled again for the next attempt ({@code Monster#resetFakeJoins()}).
	 * @param monster the monster
	 * @param watch its check
	 */
	private void checkFight(Monster monster, Watch watch)
	{
		if (monster.isDead() || !monster.isSpawned() || (getFight(monster) == null))
		{
			onFightGone(monster);
			return;
		}

		for (Player player : getAttackingPlayers(monster))
		{
			if (player.isOnline() && !player.isDead() && monster.isInsideRadius3D(player, ABANDON_RANGE))
			{
				watch._idleChecks = 0;
				return;
			}
		}

		if (++watch._idleChecks >= 2)
		{
			onFightGone(monster);
			monster.resetFakeJoins();
		}
	}

	/**
	 * A joiner is done with the fight: a helper is no longer on the players' side (and leaves the party, if it was invited), a rival is no longer one, and both hunt where they are for a while and then log off like the other fake players
	 * ({@code FakePlayerPvpManager#onLeaveParty}). One still fighting (a player, or the party's next monster) finishes that first; one that keeps fighting drops its fight when it goes. A helper that died leaves the party where it lies (it
	 * doesn't come back from town, see {@code FakePartyManager#onFakeDecay}).
	 * @param fake the fake player
	 * @param leaveDelay the fight's leave delay, in seconds
	 * @param attempts how many times it was already put off
	 */
	private void release(Npc fake, int leaveDelay, int attempts)
	{
		if (fake.isDead() || !fake.isSpawned())
		{
			_active.remove(fake.getObjectId());
			if (FakePartyManager.getInstance().isInPlayerParty(fake))
			{
				FakePartyManager.getInstance().leave(fake, false, true);
			}
			return;
		}

		if ((FakePlayerPvpManager.isFightingPvp(fake) || fake.isInCombat()) && (attempts < 10))
		{
			ThreadPool.schedule(() -> release(fake, leaveDelay, attempts + 1), Math.max(10, leaveDelay) * 1000L);
			return;
		}

		_active.remove(fake.getObjectId());
		if (fake.isAttackable())
		{
			fake.asAttackable().clearAggroList();
		}
		if (fake.hasAI())
		{
			fake.getAI().setIntention(Intention.ACTIVE);
		}

		if (FakePartyManager.getInstance().isInPlayerParty(fake))
		{
			FakePartyManager.getInstance().leave(fake, false, false);
		}
		else
		{
			FakePlayerPvpManager.getInstance().onLeaveParty(fake);
		}
	}

	// ---------------------------------------------------------------------------------------------
	// Sides
	// ---------------------------------------------------------------------------------------------

	/**
	 * @param creature a creature
	 * @return {@code true} if {@code creature} (or the fake player whose servitor it is) is a rival in a fight now: killing it is never a PK, and pays ({@link #onRivalKilled(Attackable, Player)})
	 */
	public boolean isRival(Creature creature)
	{
		final Joiner joiner = getJoiner(creature);
		return (joiner != null) && !joiner.isHelper();
	}

	/**
	 * @param creature a creature
	 * @return {@code true} if {@code creature} (or the fake player whose servitor it is) ever came to help players in a fight: it never comes back to a party from town and never takes kill-steal revenge
	 */
	public static boolean isHelper(Creature creature)
	{
		final Creature fake = ownerOf(creature);
		return (fake != null) && fake.isNpc() && fake.asNpc().hasVariables() && fake.asNpc().getVariables().getBoolean(HELPER_VAR, false);
	}

	/**
	 * @param fake a fake player
	 * @param player a player
	 * @return {@code true} if {@code fake} is helping {@code player} (or a member of {@code player}'s party) in a fight now: it accepts their party invite
	 */
	public boolean isHelperFor(Npc fake, Player player)
	{
		final Player host = getHost(fake);
		return (host != null) && (player != null) && ((host == player) || (player.isInParty() && player.getParty().containsPlayer(host)));
	}

	/**
	 * @param creature a creature
	 * @param other another creature
	 * @return {@code true} if one of them is a helper in a fight now and the other is (or is a summon or a party fake player of) the player it came for, or of that player's party: they don't hit each other
	 */
	public boolean isAllied(Creature creature, Creature other)
	{
		if (_active.isEmpty() || (creature == null) || (other == null) || (creature == other))
		{
			return false;
		}
		return isAlliedWith(creature, other) || isAlliedWith(other, creature);
	}

	private boolean isAlliedWith(Creature helper, Creature other)
	{
		final Player host = getHost(helper);
		if (host == null)
		{
			return false;
		}

		final Player player = other.asPlayer();
		if (player != null)
		{
			return (player == host) || (host.isInParty() && host.getParty().containsPlayer(player));
		}

		// Another helper of the same side, or a fake player in the party of that side.
		final Creature otherFake = ownerOf(other);
		final Player otherHost = getHost(otherFake);
		if (otherHost != null)
		{
			return (otherHost == host) || (host.isInParty() && host.getParty().containsPlayer(otherHost));
		}

		final FakePartyManager parties = FakePartyManager.getInstance();
		return otherFake.isFakePlayer() && parties.isInPlayerParty(otherFake) && (parties.getParty(otherFake) == parties.getParty(host));
	}

	/**
	 * A helper's damage and kills on the monster it came for count for the player it came for, as if it were in the party - so a helper never takes the kill or the soul away.
	 * @param creature an attacker or killer
	 * @param target the monster
	 * @return the player they count for, {@code null} if {@code creature} isn't a helper fighting {@code target} (or the player isn't around)
	 */
	public Player getHelpedPlayer(Creature creature, Attackable target)
	{
		if (_active.isEmpty() || (target == null))
		{
			return null;
		}

		final Joiner joiner = getJoiner(creature);
		if ((joiner == null) || !joiner.isHelper() || (joiner._monsterId != target.getObjectId()))
		{
			return null;
		}

		final Player host = World.getInstance().getPlayer(joiner._hostId);
		if (isAtFight(host, target))
		{
			return host;
		}

		// The player fell or left: a party member still at the fight.
		final Party party = host != null ? host.getParty() : null;
		if (party != null)
		{
			for (Player member : party.getMembers())
			{
				if (isAtFight(member, target))
				{
					return member;
				}
			}
		}
		return null;
	}

	private static boolean isAtFight(Player player, Attackable target)
	{
		return (player != null) && player.isOnline() && !player.isDead() && (player.getInstanceId() == target.getInstanceId()) && (target.calculateDistance3D(player) <= PlayerConfig.ALT_PARTY_RANGE);
	}

	/**
	 * A player killed a rival: the fight's hot zone coins and Sealed Cache, once. Called from {@code Attackable#doDie()} next to the Shadow Raider reward.
	 * @param victim the fake player killed
	 * @param killer the player credited with the kill
	 */
	public void onRivalKilled(Attackable victim, Player killer)
	{
		if ((victim == null) || (killer == null))
		{
			return;
		}

		final Joiner joiner = _active.get(victim.getObjectId());
		if ((joiner == null) || joiner.isHelper())
		{
			return;
		}

		_active.remove(victim.getObjectId());
		if (joiner._fight.getRivalKillCoins() > 0)
		{
			killer.addItem(ItemProcessType.REWARD, RatesConfig.ARENA_CURRENCY_ITEM_ID, joiner._fight.getRivalKillCoins(), victim, true);
		}
		if (joiner._fight.isRivalKillCache())
		{
			LuckyLootManager.getInstance().dropGuaranteedCache(victim, killer);
		}
	}

	/**
	 * @param creature a creature
	 * @return the fight {@code creature} (or the fake player whose servitor it is) is in now, {@code null} if none
	 */
	private Joiner getJoiner(Creature creature)
	{
		if (_active.isEmpty())
		{
			return null;
		}
		final Creature fake = ownerOf(creature);
		return (fake != null) && fake.isNpc() ? _active.get(fake.getObjectId()) : null;
	}

	/**
	 * @param creature a creature
	 * @return the player that {@code creature} (or the fake player whose servitor it is) is helping now, {@code null} if none
	 */
	private Player getHost(Creature creature)
	{
		final Joiner joiner = getJoiner(creature);
		return (joiner != null) && joiner.isHelper() ? World.getInstance().getPlayer(joiner._hostId) : null;
	}

	/**
	 * @param creature a creature
	 * @return the fake player a fake player's servitor belongs to, else {@code creature} itself
	 */
	public static Creature ownerOf(Creature creature)
	{
		return creature instanceof FakePlayerPvpServitor servitor ? servitor.getOwner() : creature;
	}

	/**
	 * @param monster a monster
	 * @return the players on its aggro list (a summon counts for its owner), each once
	 */
	public static List<Player> getAttackingPlayers(Monster monster)
	{
		final List<Player> players = new ArrayList<>();
		for (Creature attacker : monster.getAggroList().keySet())
		{
			final Player player = attacker != null ? attacker.asPlayer() : null;
			if ((player != null) && !players.contains(player))
			{
				players.add(player);
			}
		}
		return players;
	}

	/**
	 * @param monster a monster
	 * @return the online, living player (a summon counts for its owner) who has hurt it most, or {@code null}
	 */
	private static Player getTopAttacker(Monster monster)
	{
		Player top = null;
		long topDamage = -1;
		for (AggroInfo info : monster.getAggroList().values())
		{
			final Creature attacker = info.getAttacker();
			final Player player = attacker != null ? attacker.asPlayer() : null;
			if ((player != null) && player.isOnline() && !player.isDead() && (info.getDamage() > topDamage))
			{
				top = player;
				topDamage = info.getDamage();
			}
		}
		return top;
	}

	private static void sendToAttackers(Monster monster, String message)
	{
		for (Player player : getAttackingPlayers(monster))
		{
			player.sendMessage(message);
		}
	}

	public static FakeJoinerManager getInstance()
	{
		return SingletonHolder.INSTANCE;
	}

	private static class SingletonHolder
	{
		protected static final FakeJoinerManager INSTANCE = new FakeJoinerManager();
	}
}
