package org.l2jmobius.gameserver.managers;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;
import java.util.logging.Logger;
import java.util.stream.IntStream;

import org.l2jmobius.commons.threads.ThreadPool;
import org.l2jmobius.commons.util.Rnd;
import org.l2jmobius.gameserver.ai.AttackableAI;
import org.l2jmobius.gameserver.config.PlayerConfig;
import org.l2jmobius.gameserver.config.custom.NightCycleConfig;
import org.l2jmobius.gameserver.model.actor.Creature;
import org.l2jmobius.gameserver.model.actor.Player;
import org.l2jmobius.gameserver.model.actor.enums.npc.MonsterArchetype;
import org.l2jmobius.gameserver.model.actor.holders.npc.AggroInfo;
import org.l2jmobius.gameserver.model.actor.instance.Chest;
import org.l2jmobius.gameserver.model.actor.instance.Monster;
import org.l2jmobius.gameserver.model.zone.ZoneType;
import org.l2jmobius.gameserver.network.enums.ChatType;
import org.l2jmobius.gameserver.util.Broadcast;

/**
 * Nightlords: soon after nightfall, {@link NightCycleManager} has one monster in each level bracket crowned Nightlord - a champion that must be beaten in several waves (the open-world Wave Challenge), found in one of the bracket's hot zones that the rotation hasn't made
 * active right now. Everyone who fought it gets a Sealed Cache on top of the wave challenge coins, and its killer is announced. At dawn the Nightlords still alive flee, and their spawns bring back an ordinary monster.
 * <p>
 * The brackets follow the hot zone ids of {@code custom.RotatingHotZones} (see {@code data/zones/custom_hotzones.xml}); from level 80 on, the rotation's one-level tiers are one bracket here.
 */
public class NightlordManager
{
	private static final Logger LOGGER = Logger.getLogger(NightlordManager.class.getName());

	/** Set on a crowned Nightlord. The spawn clears its variables when it brings the monster back, so a respawn is an ordinary monster again. */
	public static final String NIGHTLORD_VAR = "IS_NIGHTLORD";

	private static final List<Bracket> BRACKETS = new ArrayList<>();
	static
	{
		BRACKETS.add(new Bracket("Lv 1-10", 90001, 90010));
		BRACKETS.add(new Bracket("Lv 11-20", 90011, 90020));
		BRACKETS.add(new Bracket("Lv 21-30", 90021, 90030));
		BRACKETS.add(new Bracket("Lv 31-40", 90031, 90040));
		BRACKETS.add(new Bracket("Lv 41-50", 90041, 90050));
		BRACKETS.add(new Bracket("Lv 51-60", 90051, 90060));
		BRACKETS.add(new Bracket("Lv 61-70", 90061, 90070));
		BRACKETS.add(new Bracket("Lv 71-79", 90071, 90077));
		BRACKETS.add(new Bracket("Lv 80+", 90078, 90097));
	}

	/** monster object id -> the Nightlord, while it is crowned. */
	private final Map<Integer, Nightlord> _nightlords = new ConcurrentHashMap<>();

	/** How often (ms) after dawn a Nightlord still in a fight is checked again. */
	private static final long FLEE_CHECK_DELAY = 30000;

	private ScheduledFuture<?> _riseTask;
	private ScheduledFuture<?> _fleeTask;

	/**
	 * A level bracket: its name and the ids of its hot zones.
	 */
	private static class Bracket
	{
		private final String _name;
		private final int[] _zoneIds;

		Bracket(String name, int firstZoneId, int lastZoneId)
		{
			_name = name;
			_zoneIds = IntStream.rangeClosed(firstZoneId, lastZoneId).toArray();
		}
	}

	/**
	 * A crowned Nightlord: the monster, where it rose, and the archetype it gets back when the night is over for it.
	 */
	public static class Nightlord
	{
		private final Monster _monster;
		private final String _bracket;
		private final String _zoneName;
		private final MonsterArchetype _originalArchetype;

		Nightlord(Monster monster, String bracket, String zoneName, MonsterArchetype originalArchetype)
		{
			_monster = monster;
			_bracket = bracket;
			_zoneName = zoneName;
			_originalArchetype = originalArchetype;
		}

		public Monster getMonster()
		{
			return _monster;
		}

		public String getBracket()
		{
			return _bracket;
		}

		public String getZoneName()
		{
			return _zoneName;
		}

		/**
		 * @return {@code true} while the monster is still this Nightlord: alive, in the world and not respawned as an ordinary monster
		 */
		public boolean isAlive()
		{
			return _monster.isSpawned() && !_monster.isDead() && _monster.getVariables().getBoolean(NIGHTLORD_VAR, false);
		}
	}

	protected NightlordManager()
	{
	}

	/**
	 * Night fell: the Nightlords rise after {@link NightCycleConfig#NIGHTLORD_SPAWN_DELAY}.
	 */
	public synchronized void onNightfall()
	{
		if (!NightCycleConfig.NIGHTLORD_ENABLED)
		{
			return;
		}

		cancelRise();
		_riseTask = ThreadPool.schedule(this::riseAll, NightCycleConfig.NIGHTLORD_SPAWN_DELAY * 1000L);
	}

	/**
	 * Crowns a Nightlord in every bracket that has none alive.
	 * @return how many rose
	 */
	public synchronized int riseAll()
	{
		_riseTask = null;
		if (!NightCycleConfig.NIGHTLORD_ENABLED)
		{
			return 0;
		}

		int risen = 0;
		final List<String> announced = new ArrayList<>();
		for (Bracket bracket : BRACKETS)
		{
			if (hasNightlord(bracket._name))
			{
				continue;
			}

			final Nightlord nightlord = rise(bracket);
			if (nightlord != null)
			{
				risen++;
				announced.add(nightlord._monster.getName() + " (" + bracket._name + ", " + nightlord._zoneName + ")");
			}
		}

		if (!announced.isEmpty())
		{
			NightCycleManager.announce("Nightlords rise: " + String.join(", ", announced) + ". Type .night to find them.");
		}
		return risen;
	}

	/**
	 * Crowns a monster of {@code bracket}: one in a hot zone the rotation hasn't made active, so the Nightlord doesn't pile onto a hot zone.
	 * @param bracket the level bracket
	 * @return the new Nightlord, or {@code null} if no fitting monster was found
	 */
	private Nightlord rise(Bracket bracket)
	{
		final List<Integer> zoneIds = new ArrayList<>();
		for (int zoneId : bracket._zoneIds)
		{
			zoneIds.add(zoneId);
		}
		Collections.shuffle(zoneIds);

		for (int zoneId : zoneIds)
		{
			if (HotzoneModifierManager.getInstance().isActive(zoneId))
			{
				continue;
			}

			final ZoneType zone = ZoneManager.getInstance().getZoneById(zoneId);
			if (zone == null)
			{
				continue;
			}

			final List<Monster> candidates = new ArrayList<>();
			for (Creature creature : zone.getCharactersInside())
			{
				if (creature.isMonster() && isCandidate(creature.asMonster()))
				{
					candidates.add(creature.asMonster());
				}
			}

			if (!candidates.isEmpty())
			{
				return crown(candidates.get(Rnd.get(candidates.size())), bracket._name, zone.getName());
			}
		}

		LOGGER.info(getClass().getSimpleName() + ": No monster to crown in bracket " + bracket._name + ".");
		return null;
	}

	/**
	 * @param monster the monster
	 * @return {@code true} if {@code monster} is a plain open-world monster that can be crowned
	 */
	private static boolean isCandidate(Monster monster)
	{
		return !monster.isDead() && monster.isSpawned() && (monster.getInstanceId() == 0) && (monster.getChampionTier() == 0) && !monster.isRaid() && !monster.isRaidMinion() && !monster.isMinion() && !monster.hasMinions() && !monster.isInCombat() && !monster.isHotzoneMiniboss() && !monster.isWaveChallenge() && !monster.getVariables().getBoolean("IS_ARENA_CHALLENGER", false) && !monster.isThief() && !monster.isMageMonster() && !monster.isHotzoneBounty() && !monster.isHotzoneRisen() && !monster.isHotzoneSplit() && !monster.isFakePlayer() && !monster.isPvpFakePlayer() && !monster.isQuestMonster() && !(monster instanceof Chest) && HotzoneModifierManager.isOpenWorld(monster);
	}

	/**
	 * Turns {@code monster} into a Nightlord. Order matters: the champion tier first (its title rebuild would drop the other tags), then the archetype, then the wave challenge and the tag.
	 * @param monster the monster
	 * @param bracket the name of its level bracket
	 * @param zoneName the name of the hot zone it rose in
	 * @return the Nightlord
	 */
	private Nightlord crown(Monster monster, String bracket, String zoneName)
	{
		if (NightCycleConfig.NIGHTLORD_CHAMPION_TIER > 0)
		{
			monster.setChampionTier(NightCycleConfig.NIGHTLORD_CHAMPION_TIER);
		}

		MonsterArchetype original = null;
		if ((NightCycleConfig.NIGHTLORD_ARCHETYPE != null) && (monster.getAI() instanceof AttackableAI))
		{
			final AttackableAI ai = (AttackableAI) monster.getAI();
			original = ai.getArchetype();
			if (original != NightCycleConfig.NIGHTLORD_ARCHETYPE)
			{
				ai.setArchetype(NightCycleConfig.NIGHTLORD_ARCHETYPE);
			}
			else
			{
				original = null;
			}
		}

		monster.getVariables().set(NIGHTLORD_VAR, true);
		monster.startWaveChallenge(NightCycleConfig.NIGHTLORD_WAVES);
		monster.setEventTitleTag(NightCycleConfig.NIGHTLORD_TITLE_TAG);
		monster.setCurrentHpMp(monster.getMaxHp(), monster.getMaxMp());
		monster.broadcastInfo();
		monster.broadcastSay(ChatType.NPC_SHOUT, "The night is mine! Come and face me, if you dare!");

		final Nightlord nightlord = new Nightlord(monster, bracket, zoneName, original);
		_nightlords.put(monster.getObjectId(), nightlord);
		return nightlord;
	}

	/**
	 * A Nightlord died on its final wave: everyone who fought it gets a Sealed Cache and a Night Watch credit, and its killer is announced. Called from {@link NightCycleManager#onAttackableKilled} for kills that reward exp/sp.
	 * @param monster the monster that died
	 * @param killer the player credited with the kill
	 */
	public void onKilled(Monster monster, Player killer)
	{
		final Nightlord nightlord = _nightlords.remove(monster.getObjectId());
		if ((nightlord == null) || !monster.getVariables().getBoolean(NIGHTLORD_VAR, false))
		{
			return;
		}

		restoreArchetype(nightlord);

		// Same eligibility as the wave challenge coins: a player (summon damage counts for its owner) who dealt real damage and is still near.
		final List<Player> helpers = new ArrayList<>();
		for (AggroInfo info : monster.getAggroList().values())
		{
			if ((info == null) || (info.getAttacker() == null) || (info.getDamage() <= 1))
			{
				continue;
			}

			final Player player = info.getAttacker().asPlayer();
			if ((player != null) && player.isOnline() && !helpers.contains(player) && (monster.calculateDistance3D(player) <= PlayerConfig.ALT_PARTY_RANGE))
			{
				helpers.add(player);
			}
		}
		if (!helpers.contains(killer))
		{
			helpers.add(killer);
		}

		for (Player player : helpers)
		{
			LuckyLootManager.getInstance().giveGuaranteedCache(player, monster.getLevel());
			NightCycleManager.getInstance().addNightlordCredit(player);
		}

		NightCycleManager.announce(killer.getName() + (helpers.size() > 1 ? " and " + (helpers.size() - 1) + " more" : "") + " slew the Nightlord " + monster.getName() + " (" + nightlord._bracket + ")!");
	}

	/**
	 * Dawn: the Nightlords still alive flee, and their spawns bring back an ordinary monster. One still in a fight finishes it first: it flees once nobody fights it any more.
	 */
	public synchronized void onDawn()
	{
		cancelRise();

		int fled = 0;
		for (Nightlord nightlord : _nightlords.values())
		{
			if (!nightlord.isAlive())
			{
				_nightlords.remove(nightlord._monster.getObjectId());
				continue;
			}

			if (nightlord._monster.isInCombat())
			{
				continue;
			}

			flee(nightlord);
			fled++;
		}

		if (fled > 0)
		{
			NightCycleManager.announce(fled == 1 ? "A Nightlord fled into the shadows before the sun." : fled + " Nightlords fled into the shadows before the sun.");
		}

		if (!_nightlords.isEmpty())
		{
			_fleeTask = ThreadPool.schedule(this::fleeAfterFight, FLEE_CHECK_DELAY);
		}
	}

	/**
	 * After dawn, a Nightlord that was still fighting flees once its fight is over - unless the night has come back.
	 */
	private synchronized void fleeAfterFight()
	{
		_fleeTask = null;
		if (NightCycleManager.getInstance().getPhase().isNight())
		{
			return;
		}

		for (Nightlord nightlord : _nightlords.values())
		{
			if (!nightlord.isAlive())
			{
				_nightlords.remove(nightlord._monster.getObjectId());
			}
			else if (!nightlord._monster.isInCombat())
			{
				flee(nightlord);
			}
		}

		if (!_nightlords.isEmpty())
		{
			_fleeTask = ThreadPool.schedule(this::fleeAfterFight, FLEE_CHECK_DELAY);
		}
	}

	private void flee(Nightlord nightlord)
	{
		_nightlords.remove(nightlord._monster.getObjectId());
		restoreArchetype(nightlord);
		nightlord._monster.broadcastSay(ChatType.NPC_SHOUT, "The sun... I will return with the night!");
		nightlord._monster.deleteMe();
	}

	private void restoreArchetype(Nightlord nightlord)
	{
		if ((nightlord._originalArchetype != null) && (nightlord._monster.getAI() instanceof AttackableAI))
		{
			((AttackableAI) nightlord._monster.getAI()).setArchetype(nightlord._originalArchetype);
		}
	}

	private void cancelRise()
	{
		if (_riseTask != null)
		{
			_riseTask.cancel(false);
			_riseTask = null;
		}
		if (_fleeTask != null)
		{
			_fleeTask.cancel(false);
			_fleeTask = null;
		}
	}

	private boolean hasNightlord(String bracket)
	{
		for (Nightlord nightlord : _nightlords.values())
		{
			if (nightlord._bracket.equals(bracket) && nightlord.isAlive())
			{
				return true;
			}
		}
		return false;
	}

	/**
	 * @return the Nightlords alive now, lowest bracket first
	 */
	public List<Nightlord> getNightlords()
	{
		final List<Nightlord> alive = new ArrayList<>();
		for (Bracket bracket : BRACKETS)
		{
			for (Nightlord nightlord : _nightlords.values())
			{
				if (nightlord._bracket.equals(bracket._name) && nightlord.isAlive())
				{
					alive.add(nightlord);
				}
			}
		}
		return alive;
	}

	/**
	 * @param objectId the monster's object id
	 * @return the Nightlord with that object id if it is alive, otherwise {@code null}
	 */
	public Nightlord getNightlord(int objectId)
	{
		final Nightlord nightlord = _nightlords.get(objectId);
		return (nightlord != null) && nightlord.isAlive() ? nightlord : null;
	}

	/**
	 * @param monster the monster
	 * @return {@code true} if {@code monster} is a crowned Nightlord
	 */
	public static boolean isNightlord(Monster monster)
	{
		return (monster != null) && monster.getVariables().getBoolean(NIGHTLORD_VAR, false);
	}

	public static NightlordManager getInstance()
	{
		return SingletonHolder.INSTANCE;
	}

	private static class SingletonHolder
	{
		protected static final NightlordManager INSTANCE = new NightlordManager();
	}
}
