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
package org.l2jmobius.gameserver.model.mapdevice;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.atomic.AtomicInteger;

import org.l2jmobius.gameserver.config.custom.MapDeviceConfig;
import org.l2jmobius.gameserver.model.Location;
import org.l2jmobius.gameserver.model.actor.Npc;
import org.l2jmobius.gameserver.model.actor.enums.npc.MonsterArchetype;
import org.l2jmobius.gameserver.model.actor.instance.Fence;
import org.l2jmobius.gameserver.model.actor.templates.NpcTemplate;
import org.l2jmobius.gameserver.model.hotzone.HotzoneModifier;

/**
 * An open map: the instance a Map Device opened from one map item, with its tier, its modifiers and hot zone effect, its fence, its monsters and the portals left in the hideout. The combined effect of its modifiers is worked out once here, so the stat hooks that read it on every
 * hit stay cheap.
 * @author Mobius
 */
public class MapRun
{
	/** What a run is doing. */
	public enum Status
	{
		/** Portals are open, the monsters wait. */
		OPEN,
		/** Every monster is slain: the map closes a little later. */
		COMPLETED,
		/** Torn down. */
		CLOSED
	}

	/** The monster stats a run changes, read by {@code Monster}. */
	public enum MonsterStat
	{
		HP,
		P_ATK,
		M_ATK,
		P_DEF,
		M_DEF,
		ATK_SPD,
		CAST_SPD
	}

	/** The monster stat changes of one {@link MonsterStat}, in its order. */
	private final double[] _monsterMults = new double[MonsterStat.values().length];

	private final int _id;
	private final int _ownerId;
	private final String _ownerName;
	private final Hideout _hideout;
	private final MapTier _tier;
	private final int _medals;
	private final List<MapModifier> _modifiers;
	private final HotzoneModifier _hotzoneEffect;
	private final MapArena _arena;
	private final int _instanceId;
	private final long _openedAt;
	private volatile long _closesAt;
	/** The unique map this run is, {@code null} for a plain map. */
	private final UniqueMap _unique;
	private volatile Status _status = Status.OPEN;
	private volatile long _completedAt;

	private final List<Fence> _fences = new ArrayList<>();
	private final List<Npc> _portals = new CopyOnWriteArrayList<>();
	private volatile Npc _returnPortal;
	/** Object ids of the map's monsters still alive (the ones it counts: not the ones that rose again or ambushed). */
	private final Set<Integer> _monsters = ConcurrentHashMap.newKeySet();
	private volatile int _monsterTotal;
	private final AtomicInteger _kills = new AtomicInteger();
	/** Maps (plain and unique) this run has dropped so far, at most {@link MapDeviceConfig#MAX_MAPS_PER_RUN}. */
	private final AtomicInteger _mapsDropped = new AtomicInteger();
	private volatile boolean _ambushed;
	/** Object ids of the players inside right now. */
	private final Set<Integer> _playersInside = ConcurrentHashMap.newKeySet();
	/** The last hunt: player object id -> the monster their radar points at, and where. */
	private final Map<Integer, RadarMark> _radarMarks = new ConcurrentHashMap<>();
	/** Owners of the stat functions a run adds to its monsters and players. */
	private final Object _monsterFuncOwner = new Object();
	/** Owns the stat functions of Death Curse on the players (apart from the map's own, which are put back on every arrival). */
	private final Object _curseFuncOwner = new Object();
	/** Death Curse: player object id -> {stacks, when it wears off (ms)}. */
	private final Map<Integer, long[]> _curses = new ConcurrentHashMap<>();
	/** Linked Souls: monster object id -> its partner's. */
	private final Map<Integer, Integer> _partners = new ConcurrentHashMap<>();
	/** Bounty Mark: the marked monster (0 for none) and when its bounty runs out. */
	private volatile int _bountyId;
	private volatile long _bountyUntil;
	private volatile boolean _speedrunOver;
	private final Object _playerFuncOwner = new Object();
	private volatile ScheduledFuture<?> _tickTask;
	/** The kinds of monster the map was filled with (its theme), for the monsters that join later (Ambush). */
	private volatile List<NpcTemplate> _monsterKinds = List.of();
	/** Rampage: when the running rampage ends (ms), 0 for none. */
	private volatile long _rampageUntil;
	/** Owns Rampage's run speed on the players. */
	private final Object _rampageFuncOwner = new Object();
	/** Momentum: player object id -> {stacks, time of their last kill (ms)}. */
	private final Map<Integer, long[]> _momentumStacks = new ConcurrentHashMap<>();
	/** Owns Momentum's stat functions on the players. */
	private final Object _momentumFuncOwner = new Object();
	/** Gravity Well: monster object id -> when it may pull again (ms). */
	private final Map<Integer, Long> _gravityCooldowns = new ConcurrentHashMap<>();
	/** Gravity Well: monsters pulled so far. */
	private final AtomicInteger _pulled = new AtomicInteger();
	/** Waystones: the waystones of the field, in order (the entry is destination 0, these 1 and up). */
	private final List<Npc> _waystoneNpcs = new CopyOnWriteArrayList<>();
	/** Nests: nest index -> its heart's object id. */
	private final Map<Integer, Integer> _nestHearts = new ConcurrentHashMap<>();
	/** Nests: the nests whose heart is broken. */
	private final Set<Integer> _brokenNests = ConcurrentHashMap.newKeySet();
	/** Sealed Coffers: coffer npc object id -> its state. */
	private final Map<Integer, Coffer> _cofferStates = new ConcurrentHashMap<>();
	/** Chain Reaction: monsters slain by a burst. */
	private final AtomicInteger _chainKills = new AtomicInteger();
	/** A test run of the web API's map probe (no hideout, no portals): it keeps a log of what its map says. */
	private volatile boolean _probe;
	private final List<String> _probeLog = Collections.synchronizedList(new ArrayList<>());

	// Combined effect of the modifiers.
	private final double _monsterMoveSpeed;
	private final double _monsterCritRate;
	private final double _monsterCritDamage;
	private final double _monsterEvasion;
	private final double _monsterLeechPct;
	private final double _monsterElementRes;
	private final double _monsterReflectPct;
	private final double _monsterHpRegen;
	private final double _monsterMCritRate;
	private final double _monsterControlChance;
	private final double _monsterDebuffChance;
	private final int _monsterShieldPct;
	private final double _monsterCritDamageTaken;
	private final double _monsterSkillDamageTaken;
	private final double _playerMaxHp;
	private final double _playerDef;
	private final double _playerMpRegen;
	private final double _playerHpRegen;
	private final double _playerMpCost;
	private final double _playerMoveSpeed;
	private final double _playerReuse;
	private final double _playerElementRes;
	private final double _playerAccuracy;
	private final double _playerCritRate;
	private final double _playerCritDamageTaken;
	private final double _playerHealReceived;
	private final double _playerDebuffChance;
	private final double _playerDamage;
	private final int _magePct;
	private final int _championPct;
	private final int _thiefPct;
	private final int _infusedPct;
	private final int _waveMonsters;
	private final double _packSize;
	private final boolean _packs;
	private final boolean _titans;
	private final List<MapModifier> _archetypeModifiers;
	private final boolean _noLeech;
	private final boolean _allRanged;
	private final boolean _aggressive;
	private final boolean _berserkers;
	private final boolean _volatileDeath;
	private final int _risePct;
	private final boolean _soulEaters;
	private final boolean _risingDread;
	private final boolean _ambush;
	private final boolean _guardian;
	private final boolean _storm;
	private final boolean _burningGround;
	private final boolean _flaskless;
	private final int _manaSiphonPct;
	private final int _venomPct;
	private final boolean _lacerating;
	private final boolean _linkedSouls;
	private final int _summonerPct;
	private final boolean _splitters;
	private final boolean _deathCurse;
	private final boolean _bounty;
	private final int _speedrunMinutes;
	private final boolean _chainReaction;
	private final boolean _cullingStrike;
	private final boolean _risingStakes;
	private final boolean _rampage;
	private final boolean _momentum;
	private final boolean _lens;
	private final boolean _gravityWell;
	private final boolean _waystones;
	private final boolean _nests;
	private final boolean _coffers;
	private final int _portalCount;
	private final double _xpMultiplier;
	private final double _dropMultiplier;

	public MapRun(int id, int ownerId, String ownerName, Hideout hideout, MapTier tier, int medals, List<MapModifier> modifiers, HotzoneModifier hotzoneEffect, MapArena arena, int instanceId)
	{
		this(id, ownerId, ownerName, hideout, tier, medals, modifiers, hotzoneEffect, arena, instanceId, null);
	}

	/**
	 * @param unique the unique map this run is, {@code null} for a plain map: its modifiers are the ones given, its reward multiplies the tier's
	 */
	public MapRun(int id, int ownerId, String ownerName, Hideout hideout, MapTier tier, int medals, List<MapModifier> modifiers, HotzoneModifier hotzoneEffect, MapArena arena, int instanceId, UniqueMap unique)
	{
		_id = id;
		_unique = unique;
		_ownerId = ownerId;
		_ownerName = ownerName;
		_hideout = hideout;
		_tier = tier;
		_medals = medals;
		_modifiers = List.copyOf(modifiers);
		_hotzoneEffect = hotzoneEffect;
		_arena = arena;
		_instanceId = instanceId;
		_openedAt = System.currentTimeMillis();

		double hp = tier.hp();
		double pAtk = tier.attack();
		double mAtk = tier.attack();
		double pDef = tier.defence();
		double mDef = tier.defence();
		double atkSpd = 1;
		double castSpd = 1;
		double monsterMoveSpeed = 1;
		double monsterCritRate = 1;
		double monsterCritDamage = 1;
		double monsterEvasion = 0;
		double monsterLeechPct = 0;
		double monsterElementRes = 0;
		double monsterReflectPct = 0;
		double monsterHpRegen = 1;
		double monsterMCritRate = 1;
		double monsterControlChance = 1;
		double monsterDebuffChance = 1;
		int monsterShieldPct = 0;
		double monsterCritDamageTaken = 1;
		double monsterSkillDamageTaken = 1;
		double playerDebuffChance = 1;
		double playerDamage = 1;
		int manaSiphonPct = 0;
		int venomPct = 0;
		boolean lacerating = false;
		boolean linkedSouls = false;
		int summonerPct = 0;
		boolean splitters = false;
		boolean deathCurse = false;
		boolean bounty = false;
		int speedrunMinutes = 0;
		boolean chainReaction = false;
		boolean cullingStrike = false;
		boolean risingStakes = false;
		boolean rampage = false;
		boolean momentum = false;
		boolean lens = false;
		boolean gravityWell = false;
		boolean waystones = false;
		boolean nests = false;
		boolean coffers = false;
		double playerMaxHp = 1;
		double playerDef = 1;
		double playerMpRegen = 1;
		double playerHpRegen = 1;
		double playerMpCost = 1;
		double playerMoveSpeed = 1;
		double playerReuse = 1;
		double playerElementRes = 0;
		double playerAccuracy = 0;
		double playerCritRate = 1;
		double playerCritDamageTaken = 1;
		double playerHealReceived = 1;
		int magePct = 0;
		int championPct = 0;
		int thiefPct = 0;
		int infusedPct = 0;
		int waveMonsters = 0;
		double packSize = 1;
		boolean packs = false;
		boolean titans = false;
		final List<MapModifier> archetypes = new ArrayList<>();
		boolean noLeech = false;
		boolean allRanged = false;
		boolean aggressive = false;
		boolean berserkers = false;
		boolean volatileDeath = false;
		int risePct = 0;
		boolean soulEaters = false;
		boolean risingDread = false;
		boolean ambush = false;
		boolean guardian = false;
		boolean storm = false;
		boolean burningGround = false;
		boolean flaskless = false;
		int portalCount = MapDeviceConfig.PORTAL_COUNT;
		int timeLimit = MapDeviceConfig.MAP_DURATION;
		for (MapModifier modifier : _modifiers)
		{
			hp *= modifier.getMonsterHp();
			pAtk *= modifier.getMonsterPAtk();
			mAtk *= modifier.getMonsterMAtk();
			pDef *= modifier.getMonsterPDef();
			mDef *= modifier.getMonsterMDef();
			atkSpd *= modifier.getMonsterAtkSpd();
			castSpd *= modifier.getMonsterCastSpd();
			monsterMoveSpeed *= modifier.getMonsterMoveSpeed();
			monsterCritRate *= modifier.getMonsterCritRate();
			monsterCritDamage *= modifier.getMonsterCritDamage();
			monsterEvasion += modifier.getMonsterEvasion();
			monsterLeechPct += modifier.getMonsterLeechPct();
			monsterElementRes += modifier.getMonsterElementRes();
			monsterReflectPct += modifier.getMonsterReflectPct();
			monsterHpRegen *= modifier.getMonsterHpRegen();
			monsterMCritRate *= modifier.getMonsterMCritRate();
			monsterControlChance *= modifier.getMonsterControlChance();
			monsterDebuffChance *= modifier.getMonsterDebuffChance();
			monsterShieldPct = Math.max(monsterShieldPct, modifier.getMonsterShieldPct());
			monsterCritDamageTaken *= modifier.getMonsterCritDamageTaken();
			monsterSkillDamageTaken *= modifier.getMonsterSkillDamageTaken();
			playerDebuffChance *= modifier.getPlayerDebuffChance();
			playerDamage *= modifier.getPlayerDamage();
			manaSiphonPct = Math.max(manaSiphonPct, modifier.getManaSiphonPct());
			venomPct = Math.max(venomPct, modifier.getVenomPct());
			lacerating |= modifier.isLacerating();
			linkedSouls |= modifier.isLinkedSouls();
			summonerPct = Math.max(summonerPct, modifier.getSummonerPct());
			splitters |= modifier.isSplitters();
			deathCurse |= modifier.isDeathCurse();
			bounty |= modifier.isBounty();
			chainReaction |= modifier.isChainReaction();
			cullingStrike |= modifier.isCullingStrike();
			risingStakes |= modifier.isRisingStakes();
			rampage |= modifier.isRampage();
			momentum |= modifier.isMomentum();
			lens |= modifier.isLens();
			gravityWell |= modifier.isGravityWell();
			waystones |= modifier.isWaystones();
			nests |= modifier.isNests();
			coffers |= modifier.isCoffers();
			if (modifier.getSpeedrunMinutes() > 0)
			{
				speedrunMinutes = speedrunMinutes > 0 ? Math.min(speedrunMinutes, modifier.getSpeedrunMinutes()) : modifier.getSpeedrunMinutes();
			}
			playerMaxHp *= modifier.getPlayerMaxHp();
			playerDef *= modifier.getPlayerDef();
			playerMpRegen *= modifier.getPlayerMpRegen();
			playerHpRegen *= modifier.getPlayerHpRegen();
			playerMpCost *= modifier.getPlayerMpCost();
			playerMoveSpeed *= modifier.getPlayerMoveSpeed();
			playerReuse *= modifier.getPlayerReuse();
			playerElementRes += modifier.getPlayerElementRes();
			playerAccuracy += modifier.getPlayerAccuracy();
			playerCritRate *= modifier.getPlayerCritRate();
			playerCritDamageTaken *= modifier.getPlayerCritDamageTaken();
			playerHealReceived *= modifier.getPlayerHealReceived();
			magePct = Math.max(magePct, modifier.getMagePct());
			championPct = Math.max(championPct, modifier.getChampionPct());
			thiefPct = Math.max(thiefPct, modifier.getThiefPct());
			infusedPct = Math.max(infusedPct, modifier.getInfusedPct());
			waveMonsters += modifier.getWaveMonsters();
			packSize *= modifier.getPackSize();
			packs |= modifier.isPacks();
			titans |= modifier.isTitans();
			if ((modifier.getArchetype() != null) && (modifier.getArchetypePct() > 0))
			{
				archetypes.add(modifier);
			}
			noLeech |= modifier.isNoLeech();
			allRanged |= modifier.isAllRanged();
			aggressive |= modifier.isAggressive();
			berserkers |= modifier.isBerserkers();
			volatileDeath |= modifier.isVolatileDeath();
			risePct = Math.max(risePct, modifier.getRisePct());
			soulEaters |= modifier.isSoulEaters();
			risingDread |= modifier.isRisingDread();
			ambush |= modifier.isAmbush();
			guardian |= modifier.isGuardian();
			storm |= modifier.isStorm();
			burningGround |= modifier.isBurningGround();
			flaskless |= modifier.isFlaskless();
			if (modifier.getPortalLimit() > 0)
			{
				portalCount = Math.min(portalCount, modifier.getPortalLimit());
			}
			if (modifier.getTimeLimitMinutes() > 0)
			{
				timeLimit = Math.min(timeLimit, modifier.getTimeLimitMinutes());
			}
		}

		if (titans)
		{
			hp *= 3;
			pAtk *= 1.3;
			mAtk *= 1.3;
		}

		_monsterMults[MonsterStat.HP.ordinal()] = hp;
		_monsterMults[MonsterStat.P_ATK.ordinal()] = pAtk;
		_monsterMults[MonsterStat.M_ATK.ordinal()] = mAtk;
		_monsterMults[MonsterStat.P_DEF.ordinal()] = pDef;
		_monsterMults[MonsterStat.M_DEF.ordinal()] = mDef;
		_monsterMults[MonsterStat.ATK_SPD.ordinal()] = atkSpd;
		_monsterMults[MonsterStat.CAST_SPD.ordinal()] = castSpd;
		_monsterMoveSpeed = monsterMoveSpeed;
		_monsterCritRate = monsterCritRate;
		_monsterCritDamage = monsterCritDamage;
		_monsterEvasion = monsterEvasion;
		_monsterLeechPct = monsterLeechPct;
		_monsterElementRes = monsterElementRes;
		_monsterReflectPct = monsterReflectPct;
		_monsterHpRegen = monsterHpRegen;
		_monsterMCritRate = monsterMCritRate;
		_monsterControlChance = monsterControlChance;
		_monsterDebuffChance = monsterDebuffChance;
		_monsterShieldPct = monsterShieldPct;
		_monsterCritDamageTaken = monsterCritDamageTaken;
		_monsterSkillDamageTaken = monsterSkillDamageTaken;
		_playerDebuffChance = playerDebuffChance;
		_playerDamage = playerDamage;
		_manaSiphonPct = manaSiphonPct;
		_venomPct = venomPct;
		_lacerating = lacerating;
		_linkedSouls = linkedSouls;
		_summonerPct = summonerPct;
		_splitters = splitters;
		_deathCurse = deathCurse;
		_bounty = bounty;
		_speedrunMinutes = speedrunMinutes;
		_chainReaction = chainReaction;
		_cullingStrike = cullingStrike;
		_risingStakes = risingStakes;
		_rampage = rampage;
		_momentum = momentum;
		_lens = lens;
		_gravityWell = gravityWell;
		_waystones = waystones;
		_nests = nests;
		_coffers = coffers;
		_playerMaxHp = playerMaxHp;
		_playerDef = playerDef;
		_playerMpRegen = playerMpRegen;
		_playerHpRegen = playerHpRegen;
		_playerMpCost = playerMpCost;
		_playerMoveSpeed = playerMoveSpeed;
		_playerReuse = playerReuse;
		_playerElementRes = playerElementRes;
		_playerAccuracy = playerAccuracy;
		_playerCritRate = playerCritRate;
		_playerCritDamageTaken = playerCritDamageTaken;
		_playerHealReceived = playerHealReceived;
		_magePct = magePct;
		_championPct = championPct;
		_thiefPct = thiefPct;
		_infusedPct = infusedPct;
		_waveMonsters = waveMonsters;
		_packSize = packSize;
		_packs = packs;
		_titans = titans;
		// The rarest personality is rolled first, so 30% supporters still show up among 100% cowards.
		archetypes.sort(Comparator.comparingInt(MapModifier::getArchetypePct));
		_archetypeModifiers = Collections.unmodifiableList(archetypes);
		_noLeech = noLeech;
		_allRanged = allRanged;
		_aggressive = aggressive;
		_berserkers = berserkers;
		_volatileDeath = volatileDeath;
		_risePct = risePct;
		_soulEaters = soulEaters;
		_risingDread = risingDread;
		_ambush = ambush;
		_guardian = guardian;
		_storm = storm;
		_burningGround = burningGround;
		_flaskless = flaskless;
		_portalCount = portalCount;
		_closesAt = _openedAt + (timeLimit * 60000L);

		final double titanReward = (titans ? 2.0 : 1.0) * (unique != null ? unique.reward() : 1.0);
		_xpMultiplier = tier.reward() * (1.0 + ((medals * MapDeviceConfig.XP_BONUS_PER_MEDAL) / 100.0)) * titanReward;
		_dropMultiplier = tier.reward() * (1.0 + ((medals * MapDeviceConfig.DROP_BONUS_PER_MEDAL) / 100.0)) * titanReward;
	}

	public int getId()
	{
		return _id;
	}

	public int getOwnerId()
	{
		return _ownerId;
	}

	public String getOwnerName()
	{
		return _ownerName;
	}

	public Hideout getHideout()
	{
		return _hideout;
	}

	public MapTier getTier()
	{
		return _tier;
	}

	public int getMedals()
	{
		return _medals;
	}

	public List<MapModifier> getModifiers()
	{
		return _modifiers;
	}

	public boolean hasModifier(MapModifier modifier)
	{
		return _modifiers.contains(modifier);
	}

	public HotzoneModifier getHotzoneEffect()
	{
		return _hotzoneEffect;
	}

	public MapArena getArena()
	{
		return _arena;
	}

	public int getInstanceId()
	{
		return _instanceId;
	}

	public long getOpenedAt()
	{
		return _openedAt;
	}

	public long getClosesAt()
	{
		return _closesAt;
	}

	/**
	 * Moves the time the map collapses (the Gilded Vault's clock). The map's instance lasts {@code MapDuration} plus 2 minutes from its opening: keep it before that.
	 * @param closesAt when it collapses (ms)
	 */
	public void setClosesAt(long closesAt)
	{
		_closesAt = closesAt;
	}

	/**
	 * @return the unique map this run is, {@code null} for a plain map
	 */
	public UniqueMap getUnique()
	{
		return _unique;
	}

	/**
	 * @return {@code true} for a unique map
	 */
	public boolean isUnique()
	{
		return _unique != null;
	}

	/**
	 * @return seconds until the map collapses on its own, never below 0
	 */
	public int getRemainingSeconds()
	{
		return (int) Math.max(0, (_closesAt - System.currentTimeMillis()) / 1000);
	}

	public Status getStatus()
	{
		return _status;
	}

	public void setStatus(Status status)
	{
		_status = status;
	}

	public boolean isClosed()
	{
		return _status == Status.CLOSED;
	}

	public long getCompletedAt()
	{
		return _completedAt;
	}

	public void setCompletedAt(long completedAt)
	{
		_completedAt = completedAt;
	}

	public List<Fence> getFences()
	{
		return _fences;
	}

	public List<Npc> getPortals()
	{
		return _portals;
	}

	public Npc getReturnPortal()
	{
		return _returnPortal;
	}

	public void setReturnPortal(Npc returnPortal)
	{
		_returnPortal = returnPortal;
	}

	public Set<Integer> getMonsters()
	{
		return _monsters;
	}

	public int getMonsterTotal()
	{
		return _monsterTotal;
	}

	public void setMonsterTotal(int monsterTotal)
	{
		_monsterTotal = monsterTotal;
	}

	public int getKills()
	{
		return _kills.get();
	}

	public int addKill()
	{
		return _kills.incrementAndGet();
	}

	public boolean isAmbushed()
	{
		return _ambushed;
	}

	public void setAmbushed(boolean ambushed)
	{
		_ambushed = ambushed;
	}

	public Set<Integer> getPlayersInside()
	{
		return _playersInside;
	}

	/**
	 * @return player object id -> the monster their last hunt radar points at
	 */
	public Map<Integer, RadarMark> getRadarMarks()
	{
		return _radarMarks;
	}

	/**
	 * A player's last hunt radar mark.
	 * @param monsterId the object id of the monster it points at
	 * @param spot where the marker stands
	 */
	public record RadarMark(int monsterId, Location spot)
	{
	}

	public Object getMonsterFuncOwner()
	{
		return _monsterFuncOwner;
	}

	public Object getPlayerFuncOwner()
	{
		return _playerFuncOwner;
	}

	public ScheduledFuture<?> getTickTask()
	{
		return _tickTask;
	}
	
	public List<NpcTemplate> getMonsterKinds()
	{
		return _monsterKinds;
	}
	
	public void setMonsterKinds(List<NpcTemplate> monsterKinds)
	{
		_monsterKinds = List.copyOf(monsterKinds);
	}

	public void setTickTask(ScheduledFuture<?> tickTask)
	{
		_tickTask = tickTask;
	}

	/**
	 * @param stat a monster stat
	 * @return what the tier and the modifiers multiply it by, before the per-monster effects (Berserkers, Rising Dread, Soul Eaters)
	 */
	public double getMonsterMultiplier(MonsterStat stat)
	{
		return _monsterMults[stat.ordinal()];
	}

	public double getMonsterMoveSpeed()
	{
		return _monsterMoveSpeed;
	}

	public double getMonsterCritRate()
	{
		return _monsterCritRate;
	}

	public double getMonsterCritDamage()
	{
		return _monsterCritDamage;
	}

	public double getMonsterEvasion()
	{
		return _monsterEvasion;
	}

	public double getMonsterLeechPct()
	{
		return _monsterLeechPct;
	}

	public double getMonsterElementRes()
	{
		return _monsterElementRes;
	}

	public double getMonsterReflectPct()
	{
		return _monsterReflectPct;
	}

	public double getMonsterHpRegen()
	{
		return _monsterHpRegen;
	}

	public double getPlayerMaxHp()
	{
		return _playerMaxHp;
	}

	public double getPlayerDef()
	{
		return _playerDef;
	}

	public double getPlayerMpRegen()
	{
		return _playerMpRegen;
	}

	public double getPlayerHpRegen()
	{
		return _playerHpRegen;
	}

	public double getPlayerMpCost()
	{
		return _playerMpCost;
	}

	public double getPlayerMoveSpeed()
	{
		return _playerMoveSpeed;
	}

	public double getPlayerReuse()
	{
		return _playerReuse;
	}

	public double getPlayerElementRes()
	{
		return _playerElementRes;
	}

	public double getPlayerAccuracy()
	{
		return _playerAccuracy;
	}

	public double getPlayerCritRate()
	{
		return _playerCritRate;
	}

	public double getPlayerCritDamageTaken()
	{
		return _playerCritDamageTaken;
	}

	public double getPlayerHealReceived()
	{
		return _playerHealReceived;
	}

	public int getMagePct()
	{
		return _magePct;
	}

	public int getChampionPct()
	{
		return _championPct;
	}

	public int getThiefPct()
	{
		return _thiefPct;
	}

	public int getInfusedPct()
	{
		return _infusedPct;
	}

	public int getWaveMonsters()
	{
		return _waveMonsters;
	}

	public double getPackSize()
	{
		return _packSize;
	}

	public boolean isPacks()
	{
		return _packs;
	}

	public boolean isTitans()
	{
		return _titans;
	}

	/**
	 * @return the modifiers that force a combat personality on part of the monsters
	 */
	public List<MapModifier> getArchetypeModifiers()
	{
		return _archetypeModifiers;
	}

	/**
	 * @param roll a roll from 0 to 99
	 * @return the personality the roll forces on a monster, {@code null} for none
	 */
	public MonsterArchetype rollArchetype(int roll)
	{
		for (MapModifier modifier : _archetypeModifiers)
		{
			if (roll < modifier.getArchetypePct())
			{
				return modifier.getArchetype();
			}
		}
		return null;
	}

	public boolean isNoLeech()
	{
		return _noLeech;
	}

	public boolean isAllRanged()
	{
		return _allRanged;
	}

	public boolean isAggressive()
	{
		return _aggressive;
	}

	public boolean isBerserkers()
	{
		return _berserkers;
	}

	public boolean isVolatileDeath()
	{
		return _volatileDeath;
	}

	public int getRisePct()
	{
		return _risePct;
	}

	public boolean isSoulEaters()
	{
		return _soulEaters;
	}

	public boolean isRisingDread()
	{
		return _risingDread;
	}

	public boolean isAmbush()
	{
		return _ambush;
	}

	public boolean isGuardian()
	{
		return _guardian;
	}

	public boolean isStorm()
	{
		return _storm;
	}

	public boolean isBurningGround()
	{
		return _burningGround;
	}

	public boolean isFlaskless()
	{
		return _flaskless;
	}

	/**
	 * @return how many portals the device opens for this run
	 */
	public int getPortalCount()
	{
		return _portalCount;
	}

	/**
	 * @return the experience and SP multiplier of its monsters: the tier, the medals and Titans
	 */
	public double getXpMultiplier()
	{
		return _xpMultiplier;
	}

	/**
	 * @return the drop chance multiplier of its monsters: the tier, the medals and Titans
	 */
	public double getDropMultiplier()
	{
		return _dropMultiplier;
	}

	public double getMonsterMCritRate()
	{
		return _monsterMCritRate;
	}

	public double getMonsterControlChance()
	{
		return _monsterControlChance;
	}

	public double getMonsterDebuffChance()
	{
		return _monsterDebuffChance;
	}

	public int getMonsterShieldPct()
	{
		return _monsterShieldPct;
	}

	public double getMonsterCritDamageTaken()
	{
		return _monsterCritDamageTaken;
	}

	public double getMonsterSkillDamageTaken()
	{
		return _monsterSkillDamageTaken;
	}

	public double getPlayerDebuffChance()
	{
		return _playerDebuffChance;
	}

	public double getPlayerDamage()
	{
		return _playerDamage;
	}

	public int getManaSiphonPct()
	{
		return _manaSiphonPct;
	}

	public int getVenomPct()
	{
		return _venomPct;
	}

	public boolean isLacerating()
	{
		return _lacerating;
	}

	public boolean isLinkedSouls()
	{
		return _linkedSouls;
	}

	public int getSummonerPct()
	{
		return _summonerPct;
	}

	public boolean isSplitters()
	{
		return _splitters;
	}

	public boolean isDeathCurse()
	{
		return _deathCurse;
	}

	public boolean isBounty()
	{
		return _bounty;
	}

	public int getSpeedrunMinutes()
	{
		return _speedrunMinutes;
	}

	public Object getCurseFuncOwner()
	{
		return _curseFuncOwner;
	}

	public Map<Integer, long[]> getCurses()
	{
		return _curses;
	}

	public Map<Integer, Integer> getPartners()
	{
		return _partners;
	}

	public int getBountyId()
	{
		return _bountyId;
	}

	public long getBountyUntil()
	{
		return _bountyUntil;
	}

	public boolean isSpeedrunOver()
	{
		return _speedrunOver;
	}

	/**
	 * @param objectId the marked monster, 0 for none
	 * @param until when its bounty runs out (ms)
	 */
	public void setBounty(int objectId, long until)
	{
		_bountyId = objectId;
		_bountyUntil = until;
	}

	public void setSpeedrunOver(boolean over)
	{
		_speedrunOver = over;
	}

	/**
	 * Takes one map from the run's map budget: a run drops at most {@link MapDeviceConfig#MAX_MAPS_PER_RUN} maps in all (its monsters' drops, the completion map, the Guardian's, Speedrun's, unique maps).
	 * @return {@code true} if the run may drop one more map (it is counted), {@code false} if its budget is spent
	 */
	public boolean claimMapDrop()
	{
		if (_mapsDropped.incrementAndGet() <= MapDeviceConfig.MAX_MAPS_PER_RUN)
		{
			return true;
		}
		_mapsDropped.decrementAndGet();
		return false;
	}

	/**
	 * @return how many maps the run has dropped so far
	 */
	public int getMapsDropped()
	{
		return _mapsDropped.get();
	}

	/**
	 * @return {@code true} if the map was completed within the Speedrun time
	 */
	public boolean isSpeedrunMade()
	{
		return (_speedrunMinutes > 0) && (_completedAt > 0) && ((_completedAt - _openedAt) <= (_speedrunMinutes * 60000L));
	}

	public boolean isChainReaction()
	{
		return _chainReaction;
	}

	public boolean isCullingStrike()
	{
		return _cullingStrike;
	}

	public boolean isRisingStakes()
	{
		return _risingStakes;
	}

	/**
	 * Rising Stakes: one step every 10 kills, up to 15.
	 * @return how many steps the stakes have risen
	 */
	public int getStakesSteps()
	{
		return _risingStakes ? Math.min(15, getKills() / 10) : 0;
	}

	public boolean isRampage()
	{
		return _rampage;
	}

	public long getRampageUntil()
	{
		return _rampageUntil;
	}

	public void setRampageUntil(long until)
	{
		_rampageUntil = until;
	}

	/**
	 * @return {@code true} while a rampage runs
	 */
	public boolean isRampaging()
	{
		return _rampage && (System.currentTimeMillis() < _rampageUntil);
	}

	public Object getRampageFuncOwner()
	{
		return _rampageFuncOwner;
	}

	public boolean isMomentum()
	{
		return _momentum;
	}

	public Map<Integer, long[]> getMomentum()
	{
		return _momentumStacks;
	}

	public Object getMomentumFuncOwner()
	{
		return _momentumFuncOwner;
	}

	public boolean isLens()
	{
		return _lens;
	}

	public boolean isGravityWell()
	{
		return _gravityWell;
	}

	public boolean isWaystones()
	{
		return _waystones;
	}

	public List<Npc> getWaystones()
	{
		return _waystoneNpcs;
	}

	public boolean isNests()
	{
		return _nests;
	}

	public Map<Integer, Integer> getNestHearts()
	{
		return _nestHearts;
	}

	/**
	 * @param nest a nest index
	 * @return {@code true} if its heart is broken
	 */
	public boolean isNestBroken(int nest)
	{
		return _brokenNests.contains(nest);
	}

	/**
	 * @param nest a nest index
	 * @return {@code true} if this broke it, {@code false} if it was already broken
	 */
	public boolean breakNest(int nest)
	{
		return _brokenNests.add(nest);
	}

	public boolean isCoffers()
	{
		return _coffers;
	}

	public Map<Integer, Coffer> getCoffers()
	{
		return _cofferStates;
	}

	/**
	 * Sealed Coffers: one coffer of a run, and the fight its seal started.
	 */
	public static class Coffer
	{
		private final Npc _npc;
		/** 0 sealed, 1 and 2 its waves, 3 paid. */
		private int _wave;
		private int _openerId;
		private final Set<Integer> _guards = ConcurrentHashMap.newKeySet();

		public Coffer(Npc npc)
		{
			_npc = npc;
		}

		public Npc getNpc()
		{
			return _npc;
		}

		public synchronized int getWave()
		{
			return _wave;
		}

		public synchronized void setWave(int wave)
		{
			_wave = wave;
		}

		public synchronized int getOpenerId()
		{
			return _openerId;
		}

		public synchronized void setOpenerId(int openerId)
		{
			_openerId = openerId;
		}

		/**
		 * @return the object ids of the guards of its wave still standing
		 */
		public Set<Integer> getGuards()
		{
			return _guards;
		}
	}

	public Map<Integer, Long> getGravityCooldowns()
	{
		return _gravityCooldowns;
	}

	public int getPulled()
	{
		return _pulled.get();
	}

	public void addPulled(int count)
	{
		_pulled.addAndGet(count);
	}

	public int getChainKills()
	{
		return _chainKills.get();
	}

	public void addChainKill()
	{
		_chainKills.incrementAndGet();
	}

	public boolean isProbe()
	{
		return _probe;
	}

	public void setProbe(boolean probe)
	{
		_probe = probe;
	}

	/**
	 * @return what a probe run's map said, in order (empty for a real run)
	 */
	public List<String> getProbeLog()
	{
		return _probeLog;
	}

	/**
	 * Logs a line of a probe run; nothing for a real run.
	 * @param line what happened
	 */
	public void probeLog(String line)
	{
		if (_probe && (_probeLog.size() < 2000))
		{
			_probeLog.add(((System.currentTimeMillis() - _openedAt) / 100) / 10.0 + "s " + line);
		}
	}
}
