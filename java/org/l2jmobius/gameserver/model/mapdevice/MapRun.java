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
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.atomic.AtomicInteger;

import org.l2jmobius.gameserver.config.custom.MapDeviceConfig;
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
	private final long _closesAt;
	private volatile Status _status = Status.OPEN;
	private volatile long _completedAt;

	private final List<Fence> _fences = new ArrayList<>();
	private final List<Npc> _portals = new CopyOnWriteArrayList<>();
	private volatile Npc _returnPortal;
	/** Object ids of the map's monsters still alive (the ones it counts: not the ones that rose again or ambushed). */
	private final Set<Integer> _monsters = ConcurrentHashMap.newKeySet();
	private volatile int _monsterTotal;
	private final AtomicInteger _kills = new AtomicInteger();
	private volatile boolean _ambushed;
	/** Object ids of the players inside right now. */
	private final Set<Integer> _playersInside = ConcurrentHashMap.newKeySet();
	/** Owners of the stat functions a run adds to its monsters and players. */
	private final Object _monsterFuncOwner = new Object();
	private final Object _playerFuncOwner = new Object();
	private volatile ScheduledFuture<?> _tickTask;
	/** The kinds of monster the map was filled with (its theme), for the monsters that join later (Ambush). */
	private volatile List<NpcTemplate> _monsterKinds = List.of();

	// Combined effect of the modifiers.
	private final double _monsterMoveSpeed;
	private final double _monsterCritRate;
	private final double _monsterCritDamage;
	private final double _monsterEvasion;
	private final double _monsterLeechPct;
	private final double _monsterElementRes;
	private final double _monsterReflectPct;
	private final double _monsterHpRegen;
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
	private final int _portalCount;
	private final double _xpMultiplier;
	private final double _dropMultiplier;

	public MapRun(int id, int ownerId, String ownerName, Hideout hideout, MapTier tier, int medals, List<MapModifier> modifiers, HotzoneModifier hotzoneEffect, MapArena arena, int instanceId)
	{
		_id = id;
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

		final double titanReward = titans ? 2.0 : 1.0;
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
}
