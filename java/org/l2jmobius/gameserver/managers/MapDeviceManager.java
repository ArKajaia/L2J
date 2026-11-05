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
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.l2jmobius.commons.threads.ThreadPool;
import org.l2jmobius.commons.util.Rnd;
import org.l2jmobius.gameserver.ai.AttackableAI;
import org.l2jmobius.gameserver.ai.Intention;
import org.l2jmobius.gameserver.config.custom.ChampionMonstersConfig;
import org.l2jmobius.gameserver.config.custom.InfusedMonsterConfig;
import org.l2jmobius.gameserver.config.custom.MageMonsterConfig;
import org.l2jmobius.gameserver.config.custom.MapDeviceConfig;
import org.l2jmobius.gameserver.config.custom.ResonantMonsterConfig;
import org.l2jmobius.gameserver.config.custom.ThiefMonsterConfig;
import org.l2jmobius.gameserver.config.custom.WaveChallengeConfig;
import org.l2jmobius.gameserver.data.SpawnTable;
import org.l2jmobius.gameserver.data.xml.ExperienceData;
import org.l2jmobius.gameserver.data.xml.ItemData;
import org.l2jmobius.gameserver.data.xml.MapDeviceData;
import org.l2jmobius.gameserver.data.xml.MapRegionData;
import org.l2jmobius.gameserver.data.xml.NpcData;
import org.l2jmobius.gameserver.data.xml.SkillData;
import org.l2jmobius.gameserver.geoengine.GeoEngine;
import org.l2jmobius.gameserver.model.Location;
import org.l2jmobius.gameserver.model.World;
import org.l2jmobius.gameserver.model.WorldObject;
import org.l2jmobius.gameserver.model.actor.Attackable;
import org.l2jmobius.gameserver.model.actor.Creature;
import org.l2jmobius.gameserver.model.actor.Npc;
import org.l2jmobius.gameserver.model.actor.Player;
import org.l2jmobius.gameserver.model.actor.enums.npc.AIType;
import org.l2jmobius.gameserver.model.actor.enums.npc.MonsterArchetype;
import org.l2jmobius.gameserver.model.actor.enums.player.TeleportWhereType;
import org.l2jmobius.gameserver.model.actor.instance.Chest;
import org.l2jmobius.gameserver.model.actor.instance.Monster;
import org.l2jmobius.gameserver.model.actor.templates.NpcTemplate;
import org.l2jmobius.gameserver.model.eventmap.EventMapDevice;
import org.l2jmobius.gameserver.model.events.Containers;
import org.l2jmobius.gameserver.model.events.EventType;
import org.l2jmobius.gameserver.model.events.holders.actor.player.OnPlayerLogin;
import org.l2jmobius.gameserver.model.events.holders.actor.player.OnPlayerSelect;
import org.l2jmobius.gameserver.model.events.listeners.ConsumerEventListener;
import org.l2jmobius.gameserver.model.groups.Party;
import org.l2jmobius.gameserver.model.hotzone.HotzoneModifier;
import org.l2jmobius.gameserver.model.instancezone.Instance;
import org.l2jmobius.gameserver.model.item.enums.ItemProcessType;
import org.l2jmobius.gameserver.model.item.holders.ItemHolder;
import org.l2jmobius.gameserver.model.item.instance.Item;
import org.l2jmobius.gameserver.model.item.type.EtcItemType;
import org.l2jmobius.gameserver.model.mapdevice.Hideout;
import org.l2jmobius.gameserver.model.mapdevice.MapArena;
import org.l2jmobius.gameserver.model.mapdevice.MapModifier;
import org.l2jmobius.gameserver.model.mapdevice.MapRun;
import org.l2jmobius.gameserver.model.mapdevice.MapRun.MonsterStat;
import org.l2jmobius.gameserver.model.mapdevice.MapRun.Status;
import org.l2jmobius.gameserver.model.mapdevice.MapTier;
import org.l2jmobius.gameserver.model.mapdevice.UniqueMap;
import org.l2jmobius.gameserver.model.mapdevice.MonsterStrength;
import org.l2jmobius.gameserver.model.script.Quest;
import org.l2jmobius.gameserver.model.skill.AbnormalVisualEffect;
import org.l2jmobius.gameserver.model.skill.Skill;
import org.l2jmobius.gameserver.model.skill.enums.SkillFinishType;
import org.l2jmobius.gameserver.model.spawns.Spawn;
import org.l2jmobius.gameserver.model.stats.Stat;
import org.l2jmobius.gameserver.model.stats.TraitType;
import org.l2jmobius.gameserver.model.stats.functions.AbstractFunction;
import org.l2jmobius.gameserver.model.stats.functions.FuncAdd;
import org.l2jmobius.gameserver.model.stats.functions.FuncMul;
import org.l2jmobius.gameserver.model.variables.PlayerVariables;
import org.l2jmobius.gameserver.model.zone.ZoneId;
import org.l2jmobius.gameserver.network.serverpackets.ExShowScreenMessage;
import org.l2jmobius.gameserver.network.serverpackets.MagicSkillUse;
import org.l2jmobius.gameserver.taskmanagers.AttackStanceTaskManager;

/**
 * Maps and the Map Device, after the atlas of Path of Exile.
 * <ul>
 * <li><b>Maps</b>: monsters of level 78 and up may drop a "Map (Tier N)". The tier follows the monster's level, and stronger monsters (champions, the special kinds, raid bosses) drop higher tiers (data/MapDevice.xml). The monsters of a map drop maps of its tier, sometimes one or two
 * higher.</li>
 * <li><b>Hideout</b>: {@code .hideout} in a peaceful place takes a player to their own hideout, a private instance with a Map Device. Party members can visit it ({@code .hideout party}).</li>
 * <li><b>Map Device</b>: its owner puts in a map and feeds it up to {@link MapDeviceConfig#MAX_MEDALS} Champion Medals. Each medal rolls one {@link MapModifier} (never twice the same) and raises the experience and drop rate of the map. The map also rolls a hot zone effect
 * ({@link HotzoneModifier}) for its whole instance. The device then opens portals in a ring round it.</li>
 * <li><b>The map</b>: a fenced field ({@link MapArena}) in its own instance, filled with monsters of the tier's levels, made stronger by the tier, all carrying every modifier. Nothing respawns. A portal takes one player in and is gone. A player who dies inside wakes up in the hideout
 * and may take another portal. Slaying every monster completes the map.</li>
 * </ul>
 * Everything is checked on the server: only the owner works the device, only the owner and their party take the portals, and a portal or a map is only used while its run is still open.
 * @author Mobius
 */
public class MapDeviceManager
{
	private static final Logger LOGGER = Logger.getLogger(MapDeviceManager.class.getName());

	/** Where a player goes back to when leaving the hideout ("x;y;z;heading"), saved when they enter it from the world. */
	public static final String RETURN_LOC_VAR = "MAPDEVICE_RETURN_LOC";

	/** Set on the monster a slain one rose again as (Undying Legion): it never rises twice. */
	private static final String RISEN_VAR = "MAP_RISEN";
	/** Set on the Map Guardian. */
	private static final String GUARDIAN_VAR = "MAP_GUARDIAN";
	/** Splitters: a copy of a split monster (half HP, half rewards, never splits again). */
	private static final String SPLIT_VAR = "MAP_SPLIT";
	/** Summoners: a monster that calls its minions when first hit. */
	private static final String SUMMONER_VAR = "MAP_SUMMONER";
	/** Summoners: a summoned minion (half HP, 70% damage, half rewards). */
	private static final String MINION_VAR = "MAP_MINION";
	/** Bounty Mark: the tag the marked monster wore before. */
	private static final String BOUNTY_TAG_VAR = "MAP_BOUNTY_TAG";
	/** Where {@code Monster.setEventTitleTag} keeps the tag. */
	private static final String EVENT_TITLE_TAG_VAR = "EVENT_TITLE_TAG";
	/** Set on a character once it got its guaranteed first map ({@link MapDeviceConfig#FIRST_MAP_LEVEL}). */
	public static final String FIRST_MAP_VAR = "MAP_FIRST_GIVEN";
	/** Soul Eaters: how many souls a monster has devoured. */
	private static final String SOULS_VAR = "MAP_SOULS";

	private static final int ATTACK_RANGE_RANGED = 600;
	private static final double AGGRO_RANGE_MULT = 1.5;
	private static final int FORCED_AGGRO_RANGE = 400;
	private static final double BERSERK_HP = 0.35;
	private static final int SOUL_RANGE = 600;
	private static final int SOUL_MAX = 5;
	private static final int VOLATILE_DELAY = 1500;
	private static final int VOLATILE_RADIUS = 250;
	private static final double VOLATILE_DAMAGE = 0.10;
	private static final int VOLATILE_SKILL_ID = 4529; // Quest - Summoned Explosion (visual only)
	private static final int STORM_INTERVAL = 20;
	private static final double STORM_DAMAGE = 0.12;
	private static final int STORM_SKILL_ID = 4130; // Striking of Thunderbolt (visual only)
	private static final int BURNING_INTERVAL = 3;
	private static final double BURNING_DAMAGE = 0.015;
	private static final int AMBUSH_SIZE = 20;
	private static final int SPLIT_COUNT = 2;
	private static final int SUMMON_COUNT = 2;
	private static final int SUMMON_SKILL_ID = 4526; // Quest - BOSS Summon (visual only)
	private static final double MINION_ATTACK = 0.70;
	/** Splitters and Summoners: the extra monsters give half the experience and drops. */
	private static final double SPAWNLING_REWARD = 0.5;
	/** Linked Souls: partners share damage only this close to each other, and stand this close when spawned. */
	private static final int LINK_RANGE = 1200;
	private static final int LINK_SPAWN_RANGE = 220;
	private static final int CURSE_SECONDS = 10;
	private static final int CURSE_MAX_STACKS = 6;
	private static final double CURSE_PER_STACK = 0.05;
	private static final Stat[] CURSED_STATS =
	{
		Stat.POWER_ATTACK,
		Stat.MAGIC_ATTACK,
		Stat.POWER_DEFENCE,
		Stat.MAGIC_DEFENCE,
		Stat.POWER_ATTACK_SPEED,
		Stat.MAGIC_ATTACK_SPEED,
		Stat.MOVE_SPEED
	};
	private static final int VENOM_SKILL_ID = 4182; // Poison (NPC), 30 s
	private static final int BLEED_SKILL_ID = 4188; // Bleed (NPC), 20 s
	private static final int BOUNTY_SECONDS = 120;
	private static final double BOUNTY_DROP = 3.0;
	/** Shieldbearers: a monster's shield adds this share of its P. Def. when it blocks. */
	private static final double SHIELD_DEF_SHARE = 0.5;
	/** Linked Souls: true while damage is being passed on to a partner, so it isn't shared (or reduced) twice. */
	private static final ThreadLocal<Boolean> SHARING = ThreadLocal.withInitial(() -> Boolean.FALSE);
	/** When this few monsters are left, they hunt the players down (none stays hidden in a corner of the field). */
	private static final int LAST_HUNT_COUNT = 10;
	private static final int LAST_HUNT_AGGRO_RANGE = 2500;
	private static final int PACK_MIN = 6;
	private static final int PACK_MAX = 10;
	private static final int PACK_RADIUS = 420;
	/** How close two monsters of one Pack Hunters pack may stand. */
	private static final int PACK_SPACING = 140;
	/** How many spots are tried for a monster before the one farthest from the others is taken. */
	private static final int SPACING_ATTEMPTS = 20;
	private static final int ENTRY_SPREAD = 80;
	/** How long (s) a map whose portals are all used stays open once nobody is inside. */
	private static final int NO_PORTALS_CLOSE_DELAY = 20;
	private static final int HIDEOUT_CHECK_INTERVAL = 10000;

	/** map instance id -> its run. Static so the hooks of core classes can read it without making the manager. */
	private static final Map<Integer, MapRun> RUNS_BY_INSTANCE = new ConcurrentHashMap<>();
	/** hideout instance id -> the hideout. */
	private static final Map<Integer, Hideout> HIDEOUTS_BY_INSTANCE = new ConcurrentHashMap<>();

	/** owner object id -> their hideout. */
	private final Map<Integer, Hideout> _hideouts = new ConcurrentHashMap<>();
	/** tier -> the monsters a map of that tier may hold. */
	private final Map<Integer, List<NpcTemplate>> _monsterPools = new ConcurrentHashMap<>();
	/** Players working the device right now (a double click must not open two maps). */
	private final Set<Integer> _opening = ConcurrentHashMap.newKeySet();
	private final AtomicInteger _runIds = new AtomicInteger();

	protected MapDeviceManager()
	{
		Containers.Players().addListener(new ConsumerEventListener(Containers.Players(), EventType.ON_PLAYER_SELECT, (OnPlayerSelect event) -> onPlayerSelect(event.getPlayer()), this));
		Containers.Players().addListener(new ConsumerEventListener(Containers.Players(), EventType.ON_PLAYER_LOGIN, (OnPlayerLogin event) -> onPlayerLogin(event.getPlayer()), this));
		ThreadPool.scheduleAtFixedRate(this::checkHideouts, HIDEOUT_CHECK_INTERVAL, HIDEOUT_CHECK_INTERVAL);
		LOGGER.info(getClass().getSimpleName() + ": Map Device " + (MapDeviceConfig.ENABLED ? "enabled" : "disabled") + ", " + MapModifier.values().length + " map modifiers.");
	}

	// =========================================================================
	// Lookups (static: read by core hooks on every hit)
	// =========================================================================

	/**
	 * @param instanceId an instance id
	 * @return the map run in that instance, {@code null} for none
	 */
	public static MapRun getRun(int instanceId)
	{
		return (instanceId > 0) && !RUNS_BY_INSTANCE.isEmpty() ? RUNS_BY_INSTANCE.get(instanceId) : null;
	}

	/**
	 * @param instanceId an instance id
	 * @return {@code true} if a map runs in it
	 */
	public static boolean isMapInstance(int instanceId)
	{
		return getRun(instanceId) != null;
	}

	/**
	 * @param instanceId an instance id
	 * @return {@code true} if it is a hideout
	 */
	public static boolean isHideoutInstance(int instanceId)
	{
		return (instanceId > 0) && HIDEOUTS_BY_INSTANCE.containsKey(instanceId);
	}

	/**
	 * @param creature a monster
	 * @return the run whose monster stat changes apply to it, {@code null} for none (outside a map, or a roaming fake player)
	 */
	private static MapRun getMonsterRun(Creature creature)
	{
		if ((creature == null) || !creature.isMonster() || creature.isPvpFakePlayer())
		{
			return null;
		}
		return getRun(creature.getInstanceId());
	}

	/**
	 * Read by the stat getters of {@link Monster}.
	 * @param monster a monster
	 * @param stat one of its stats
	 * @return what its map multiplies the stat by: the tier and modifiers, plus Berserkers, Rising Dread and Soul Eaters; 1.0 outside a map
	 */
	public static double getMonsterMultiplier(Monster monster, MonsterStat stat)
	{
		final MapRun run = getMonsterRun(monster);
		if (run == null)
		{
			return 1.0;
		}

		double multiplier = run.getMonsterMultiplier(stat);
		if (run.isUnique())
		{
			multiplier *= UniqueMapManager.getEncounterMultiplier(run, monster, stat);
		}
		if ((run.isSplitters() || (run.getSummonerPct() > 0)) && ((stat == MonsterStat.HP) || (stat == MonsterStat.P_ATK) || (stat == MonsterStat.M_ATK)))
		{
			if (monster.getVariables().getBoolean(SPLIT_VAR, false))
			{
				multiplier *= stat == MonsterStat.HP ? 0.5 : 1.0;
			}
			else if (monster.getVariables().getBoolean(MINION_VAR, false))
			{
				multiplier *= stat == MonsterStat.HP ? 0.5 : MINION_ATTACK;
			}
		}
		switch (stat)
		{
			case P_ATK, M_ATK ->
			{
				if (run.isRisingDread())
				{
					multiplier *= 1.0 + Math.min(0.5, (run.getKills() / 10) * 0.05);
				}
				if (run.isSoulEaters())
				{
					multiplier *= 1.0 + (0.10 * monster.getVariables().getInt(SOULS_VAR, 0));
				}
				if (run.isBerserkers() && isBerserk(monster))
				{
					multiplier *= 1.5;
				}
			}
			case ATK_SPD ->
			{
				if (run.isBerserkers() && isBerserk(monster))
				{
					multiplier *= 1.3;
				}
			}
			default ->
			{
			}
		}
		return multiplier;
	}

	private static boolean isBerserk(Monster monster)
	{
		final int maxHp = monster.getMaxHp();
		return (maxHp > 0) && (monster.getCurrentHp() < (maxHp * BERSERK_HP));
	}

	/**
	 * Read by the experience and SP getters of {@link Monster}.
	 * @param monster a monster
	 * @return its map's experience multiplier, 1.0 outside a map
	 */
	public static double getXpMultiplier(Monster monster)
	{
		final MapRun run = getMonsterRun(monster);
		return run != null ? run.getXpMultiplier() * getSpawnlingReward(run, monster) : 1.0;
	}

	/**
	 * Read by the drop chance multiplier of {@link Monster}.
	 * @param monster a monster
	 * @return its map's drop chance multiplier, 1.0 outside a map
	 */
	public static double getDropMultiplier(Monster monster)
	{
		final MapRun run = getMonsterRun(monster);
		if (run == null)
		{
			return 1.0;
		}
		final double bounty = isBountyTarget(run, monster) ? BOUNTY_DROP : 1.0;
		return run.getDropMultiplier() * getSpawnlingReward(run, monster) * bounty;
	}

	/**
	 * @return {@link #SPAWNLING_REWARD} for a copy of Splitters or a minion of Summoners, else 1.0
	 */
	private static double getSpawnlingReward(MapRun run, Monster monster)
	{
		if ((run.isSplitters() || (run.getSummonerPct() > 0)) && (monster.getVariables().getBoolean(SPLIT_VAR, false) || monster.getVariables().getBoolean(MINION_VAR, false)))
		{
			return SPAWNLING_REWARD;
		}
		return 1.0;
	}

	/**
	 * @return {@code true} if the monster carries Bounty Mark's bounty and it hasn't run out
	 */
	private static boolean isBountyTarget(MapRun run, Monster monster)
	{
		return run.isBounty() && (run.getBountyId() == monster.getObjectId()) && (System.currentTimeMillis() <= run.getBountyUntil());
	}

	/**
	 * Hook of {@code Monster.reduceCurrentHp}, before the damage is taken: Spell-Warded, Glass Cannon, Summoners (the first hit calls the minions) and Linked Souls (half goes to the partner).
	 * @param monster the monster hit
	 * @param amount the damage
	 * @param attacker who deals it, may be {@code null}
	 * @param isDOT {@code true} for a damage over time tick
	 * @param skill the skill, {@code null} for an attack
	 * @return the damage the monster itself takes
	 */
	public static double onMonsterDamaged(Monster monster, double amount, Creature attacker, boolean isDOT, Skill skill)
	{
		if (SHARING.get())
		{
			return amount; // A partner's share: already worked out.
		}
		final MapRun run = getMonsterRun(monster);
		if ((run == null) || (amount <= 0) || monster.isDead())
		{
			return amount;
		}

		double damage = amount;
		if (skill != null)
		{
			damage *= run.getMonsterSkillDamageTaken();
		}
		if ((attacker != null) && attacker.isPlayable())
		{
			damage *= run.getPlayerDamage();
		}
		if (run.isUnique())
		{
			damage = UniqueMapManager.getInstance().onMonsterDamaged(run, monster, damage, attacker);
			if (damage <= 0)
			{
				return 0;
			}
		}

		if ((attacker != null) && (run.getSummonerPct() > 0) && monster.getVariables().getBoolean(SUMMONER_VAR, false))
		{
			monster.getVariables().remove(SUMMONER_VAR);
			ThreadPool.schedule(() -> getInstance().summonMinions(run, monster, attacker), 300);
		}

		if (run.isLinkedSouls() && (attacker != null))
		{
			final Integer partnerId = run.getPartners().get(monster.getObjectId());
			final Creature partner = partnerId != null ? World.getInstance().findObject(partnerId) instanceof Creature creature ? creature : null : null;
			if ((partner != null) && partner.isMonster() && !partner.isDead() && (partner.getInstanceId() == monster.getInstanceId()) && (monster.calculateDistance2D(partner) <= LINK_RANGE))
			{
				final double share = damage / 2;
				SHARING.set(Boolean.TRUE);
				try
				{
					partner.reduceCurrentHp(share, attacker, true, isDOT, skill);
				}
				finally
				{
					SHARING.set(Boolean.FALSE);
				}
				damage -= share;
			}
		}
		return damage;
	}

	/**
	 * Hook of {@code Creature.onHitTimer}: a map monster's attack landed on a player (Mana Siphon, Venomous, Lacerating).
	 * @param monster the monster
	 * @param target the player hit
	 * @param crit {@code true} for a critical hit
	 */
	public static void onMonsterHit(Monster monster, Player target, boolean crit)
	{
		final MapRun run = getMonsterRun(monster);
		if ((run == null) || target.isDead() || target.isInvul())
		{
			return;
		}
		if (run.getManaSiphonPct() > 0)
		{
			target.reduceCurrentMp(target.getMaxMp() * (run.getManaSiphonPct() / 100.0));
		}
		if ((run.getVenomPct() > 0) && (Rnd.get(100) < run.getVenomPct()))
		{
			applyNpcDebuff(monster, target, VENOM_SKILL_ID);
		}
		if (crit && run.isLacerating())
		{
			applyNpcDebuff(monster, target, BLEED_SKILL_ID);
		}
	}

	/**
	 * Lands an NPC damage over time debuff on a player, at the level that fits the monster (the skill's levels step every 10 levels, from 14).
	 */
	private static void applyNpcDebuff(Monster monster, Player target, int skillId)
	{
		final int level = Math.max(1, Math.min(12, ((monster.getLevel() - 14) / 10) + 2));
		final Skill skill = SkillData.getInstance().getSkill(skillId, level);
		if (skill != null)
		{
			skill.applyEffects(monster, target);
		}
	}

	/**
	 * Hook of {@code Formulas.calcEffectSuccess}: Hexproof and Unshakable on the map's monsters, Weakened Will on its players.
	 * @param attacker who casts
	 * @param target who it lands on
	 * @param skill the skill
	 * @return what the chance is multiplied by, 1.0 outside a map
	 */
	public static double getEffectChanceMultiplier(Creature attacker, Creature target, Skill skill)
	{
		if (RUNS_BY_INSTANCE.isEmpty() || !skill.isDebuff())
		{
			return 1.0;
		}
		double multiplier = 1.0;
		final MapRun run = getMonsterRun(target);
		if (run != null)
		{
			multiplier *= run.getMonsterDebuffChance();
			switch (skill.getTraitType())
			{
				case SHOCK, SLEEP, HOLD, ROOT_PHYSICALLY, ROOT_MAGICALLY, DERANGEMENT ->
				{
					multiplier *= run.getMonsterControlChance();
				}
				default ->
				{
				}
			}
		}
		if ((attacker != null) && attacker.isPlayable())
		{
			final MapRun attackerRun = getRun(attacker.getInstanceId());
			if (attackerRun != null)
			{
				multiplier *= attackerRun.getPlayerDebuffChance();
			}
		}
		return multiplier;
	}

	/**
	 * Hook of {@code Formulas.calcShldUse}: Shieldbearers.
	 * @param target the creature attacked
	 * @return the chance (%) it blocks with its map's shield, 0 for none (its own shield rules then)
	 */
	public static int getShieldRate(Creature target)
	{
		final MapRun run = getMonsterRun(target);
		return run != null ? run.getMonsterShieldPct() : 0;
	}

	/**
	 * Hail of Arrows: read by {@code Monster.getAiType()} and {@code Monster.getPhysicalAttackRange()}.
	 * @param monster a monster
	 * @return {@code true} if its map makes every monster fight from range
	 */
	public static boolean isRanged(Monster monster)
	{
		final MapRun run = getMonsterRun(monster);
		return (run != null) && run.isAllRanged();
	}

	/**
	 * @return the attack range of a monster of a Hail of Arrows map
	 */
	public static int getRangedAttackRange()
	{
		return ATTACK_RANGE_RANGED;
	}

	/**
	 * Hunters: read by {@code Monster.isAggressive()}.
	 * @param monster a monster
	 * @return {@code true} if its map makes every monster aggressive
	 */
	public static boolean isForcedAggressive(Monster monster)
	{
		final MapRun run = getMonsterRun(monster);
		return (run != null) && (run.isAggressive() || isLastHunt(run)) && !monster.isFakePlayer() && !(monster instanceof Chest);
	}
	
	/**
	 * @param run a run
	 * @return {@code true} once so few of its monsters are left that they hunt the players down
	 */
	private static boolean isLastHunt(MapRun run)
	{
		return (run.getStatus() == Status.OPEN) && (run.getMonsters().size() <= LAST_HUNT_COUNT);
	}

	/**
	 * Hunters: read by {@code Monster.getAggroRange()}.
	 * @param monster a monster
	 * @param aggroRange its aggro range so far
	 * @return its aggro range in its map
	 */
	public static int getAggroRange(Monster monster, int aggroRange)
	{
		if (!isForcedAggressive(monster))
		{
			return aggroRange;
		}
		final MapRun run = getMonsterRun(monster);
		if (isLastHunt(run))
		{
			return Math.max(aggroRange, LAST_HUNT_AGGRO_RANGE);
		}
		return (int) ((aggroRange > 0 ? aggroRange : FORCED_AGGRO_RANGE) * AGGRO_RANGE_MULT);
	}

	/**
	 * Bloodless: read by {@code Totems.getLeechRate}, which every life and mana leech goes through (auto-attack absorb, drain skills, the passive tree, cubics).
	 * @param target what was hit
	 * @return 0 if it is a monster of a Bloodless map, 1 otherwise
	 */
	public static double getLeechRate(WorldObject target)
	{
		if ((target == null) || !target.isMonster())
		{
			return 1;
		}
		final MapRun run = getRun(target.getInstanceId());
		return (run != null) && run.isNoLeech() ? 0 : 1;
	}

	/**
	 * Flaskless: read by the item skill handler potions and elixirs go through (by hand, auto potions or auto use).
	 * @param user who uses the item
	 * @param item the item
	 * @return {@code true} if the item can't be used here (and the user was told why)
	 */
	public static boolean isItemSealed(Creature user, Item item)
	{
		if ((user == null) || (item == null) || (item.getEtcItem() == null))
		{
			return false;
		}
		final MapRun run = getRun(user.getInstanceId());
		if ((run == null) || !run.isFlaskless())
		{
			return false;
		}
		final EtcItemType type = item.getEtcItem().getItemType();
		if ((type != EtcItemType.POTION) && (type != EtcItemType.ELIXIR))
		{
			return false;
		}
		if (user.isPlayer())
		{
			user.sendMessage("Flaskless: potions and elixirs can't be used in this map.");
		}
		return true;
	}

	/**
	 * Random passives are the open world's surprise; a map already rolls its own difficulty, so they follow {@link MapDeviceConfig#RANDOM_PASSIVES} there.
	 * @param instanceId the monster's instance
	 * @return {@code true} if a monster of that instance may roll random passives
	 */
	public static boolean allowsRandomPassives(int instanceId)
	{
		return MapDeviceConfig.RANDOM_PASSIVES || !isMapInstance(instanceId);
	}

	// =========================================================================
	// Hideout
	// =========================================================================

	/**
	 * @param ownerId a player's object id
	 * @return their hideout, {@code null} if they have none open
	 */
	public Hideout getHideout(int ownerId)
	{
		return _hideouts.get(ownerId);
	}

	/**
	 * @param instanceId an instance id
	 * @return the hideout of that instance, {@code null} for none
	 */
	public Hideout getHideoutByInstance(int instanceId)
	{
		return instanceId > 0 ? HIDEOUTS_BY_INSTANCE.get(instanceId) : null;
	}

	/**
	 * @param player a player
	 * @return the hideout the player stands in, {@code null} if they are in none
	 */
	public Hideout getCurrentHideout(Player player)
	{
		return getHideoutByInstance(player.getInstanceId());
	}

	/**
	 * @param player a player
	 * @return the run of the map the player stands in, or of the device of the hideout they stand in; {@code null} for none
	 */
	public MapRun getCurrentRun(Player player)
	{
		final MapRun run = getRun(player.getInstanceId());
		if (run != null)
		{
			return run;
		}
		final Hideout hideout = getCurrentHideout(player);
		return hideout != null ? hideout.getRun() : null;
	}

	/**
	 * Takes a player from the world to their own hideout, making it first if needed.
	 * @param player the player
	 * @return why they can't go, {@code null} if they went
	 */
	public String enterHideout(Player player)
	{
		final String denied = checkCanLeaveWorld(player);
		if (denied != null)
		{
			return denied;
		}

		Hideout hideout = _hideouts.get(player.getObjectId());
		if ((hideout == null) || (InstanceManager.getInstance().getInstance(hideout.getInstanceId()) == null))
		{
			hideout = createHideout(player);
			if (hideout == null)
			{
				return "Your hideout could not be prepared. Please contact an administrator.";
			}
		}

		saveReturnLocation(player);
		teleportToHideout(player, hideout);
		player.sendMessage("Welcome to your hideout. Talk to the Map Device to open a map, or type .hideout leave to go back.");
		return null;
	}

	/**
	 * Takes a player from the world to the hideout of a member of their party (the leader's first).
	 * @param player the player
	 * @return why they can't go, {@code null} if they went
	 */
	public String visitPartyHideout(Player player)
	{
		if (!MapDeviceConfig.HIDEOUT_ALLOW_PARTY)
		{
			return "Hideouts can't be visited on this server.";
		}

		final Party party = player.getParty();
		if (party == null)
		{
			return "You are not in a party.";
		}

		Hideout hideout = null;
		final Player leader = party.getLeader();
		if ((leader != null) && (leader != player))
		{
			hideout = _hideouts.get(leader.getObjectId());
		}
		if (hideout == null)
		{
			for (Player member : party.getMembers())
			{
				if ((member != null) && (member != player) && _hideouts.containsKey(member.getObjectId()))
				{
					hideout = _hideouts.get(member.getObjectId());
					break;
				}
			}
		}
		if ((hideout == null) || (InstanceManager.getInstance().getInstance(hideout.getInstanceId()) == null))
		{
			return "Nobody in your party has a hideout open.";
		}

		if (player.getInstanceId() == hideout.getInstanceId())
		{
			return "You are already there.";
		}

		final MapRun run = getRun(player.getInstanceId());
		if (run == null)
		{
			final String denied = checkCanLeaveWorld(player);
			if (denied != null)
			{
				return denied;
			}
			saveReturnLocation(player);
		}
		else if (AttackStanceTaskManager.getInstance().hasAttackStanceTask(player))
		{
			return "You can't leave the map while fighting.";
		}

		teleportToHideout(player, hideout);
		player.sendMessage("Welcome to " + hideout.getOwnerName() + "'s hideout.");
		return null;
	}

	/**
	 * Takes a player from a map back to the hideout it was opened from. The portal they came through is gone: going back in takes another one.
	 * @param player the player
	 * @return why they can't go, {@code null} if they went
	 */
	public String returnToHideout(Player player)
	{
		final MapRun run = getRun(player.getInstanceId());
		if (run == null)
		{
			return "You are not in a map.";
		}
		if (player.isDead())
		{
			return "You are dead.";
		}
		if (AttackStanceTaskManager.getInstance().hasAttackStanceTask(player))
		{
			return "You can't leave the map while fighting.";
		}
		sendOut(player, run);
		return null;
	}

	/**
	 * Takes a player from a hideout (or a map) back to where they entered from.
	 * @param player the player
	 * @return why they can't go, {@code null} if they went
	 */
	public String leaveHideout(Player player)
	{
		final int instanceId = player.getInstanceId();
		if (!isHideoutInstance(instanceId) && !isMapInstance(instanceId))
		{
			return "You are not in a hideout.";
		}
		if (AttackStanceTaskManager.getInstance().hasAttackStanceTask(player))
		{
			return "You can't leave while fighting.";
		}

		final Location loc = getReturnLocation(player);
		player.teleToLocation(loc.getX(), loc.getY(), loc.getZ(), loc.getHeading(), 0);
		return null;
	}

	/**
	 * @param player a player about to leave the world for a hideout
	 * @return why they can't, {@code null} if they can
	 */
	private String checkCanLeaveWorld(Player player)
	{
		if (!MapDeviceConfig.ENABLED)
		{
			return "Hideouts and maps are disabled.";
		}
		if (player.getLevel() < MapDeviceConfig.HIDEOUT_MIN_LEVEL)
		{
			return "You must be level " + MapDeviceConfig.HIDEOUT_MIN_LEVEL + " or higher to have a hideout.";
		}
		if (isHideoutInstance(player.getInstanceId()))
		{
			return "You are already in a hideout.";
		}
		if (player.getInstanceId() != 0)
		{
			return "You can't go to a hideout from here.";
		}
		if (player.isDead() || player.isAlikeDead())
		{
			return "You are dead.";
		}
		if (MapDeviceConfig.HIDEOUT_PEACE_ZONE_ONLY && !player.isInsideZone(ZoneId.PEACE))
		{
			return "You can only go to your hideout from a peaceful place, like a town.";
		}
		if (AttackStanceTaskManager.getInstance().hasAttackStanceTask(player) || player.isInCombat())
		{
			return "You can't go to your hideout while fighting.";
		}
		if (player.isInOlympiadMode() || player.isOnEvent() || player.isInDuel() || player.isInSiege() || player.isJailed() || player.inObserverMode() || (player.getKrateiArena() != null))
		{
			return "You can't go to your hideout right now.";
		}
		if (player.isCursedWeaponEquipped() || player.isFlyingMounted() || player.isInStoreMode() || player.isFishing() || player.isTeleporting() || (player.getKarma() > 0))
		{
			return "You can't go to your hideout right now.";
		}
		return null;
	}

	private Hideout createHideout(Player player)
	{
		try
		{
			final Instance instance = InstanceManager.getInstance().createDynamicInstance(0);
			instance.setAllowSummon(false);
			instance.setEjectTime(0);
			final Hideout hideout = new Hideout(player.getObjectId(), player.getName(), instance.getId());
			HIDEOUTS_BY_INSTANCE.put(instance.getId(), hideout);
			_hideouts.put(player.getObjectId(), hideout);

			final Location device = getGroundLocation(MapDeviceData.getInstance().getDevicePosition());
			final Npc deviceNpc = Quest.addSpawn(MapDeviceConfig.DEVICE_NPC_ID, device.getX(), device.getY(), device.getZ(), device.getHeading(), false, 0, false, instance.getId());
			hideout.setDevice(deviceNpc);
			if (deviceNpc != null)
			{
				deviceNpc.setTitle(player.getName() + "'s Map Device");
				deviceNpc.broadcastInfo();
			}
			return hideout;
		}
		catch (Exception e)
		{
			LOGGER.log(Level.WARNING, getClass().getSimpleName() + ": Could not make the hideout of " + player.getName() + ".", e);
			return null;
		}
	}

	private void teleportToHideout(Player player, Hideout hideout)
	{
		final Location entry = getGroundLocation(MapDeviceData.getInstance().getHideoutEntry());
		player.teleToLocation(entry.getX(), entry.getY(), entry.getZ(), entry.getHeading(), hideout.getInstanceId(), ENTRY_SPREAD / 2);
		hideout.setEmptySince(0);
	}

	/**
	 * Every few seconds: closes the hideouts nobody has used for {@link MapDeviceConfig#HIDEOUT_EMPTY_MINUTES}, and those whose instance went away.
	 */
	private void checkHideouts()
	{
		final long now = System.currentTimeMillis();
		for (Hideout hideout : _hideouts.values())
		{
			try
			{
				final Instance instance = InstanceManager.getInstance().getInstance(hideout.getInstanceId());
				if (instance == null)
				{
					destroyHideout(hideout);
					continue;
				}

				final MapRun run = hideout.getRun();
				if ((run != null) && !run.isClosed())
				{
					hideout.setEmptySince(0);
					continue;
				}

				if (hasPlayersInside(instance))
				{
					hideout.setEmptySince(0);
					continue;
				}

				if (hideout.getEmptySince() == 0)
				{
					hideout.setEmptySince(now);
				}
				else if ((now - hideout.getEmptySince()) >= (MapDeviceConfig.HIDEOUT_EMPTY_MINUTES * 60000L))
				{
					destroyHideout(hideout);
				}
			}
			catch (Exception e)
			{
				LOGGER.log(Level.WARNING, getClass().getSimpleName() + ": Hideout check failed.", e);
			}
		}
	}

	private void destroyHideout(Hideout hideout)
	{
		_hideouts.remove(hideout.getOwnerId(), hideout);
		HIDEOUTS_BY_INSTANCE.remove(hideout.getInstanceId());
		final MapRun run = hideout.getRun();
		if ((run != null) && !run.isClosed())
		{
			closeRun(run, null);
		}
		final Npc device = hideout.getDevice();
		if (device != null)
		{
			device.deleteMe();
		}
		InstanceManager.getInstance().destroyInstance(hideout.getInstanceId());
	}

	private static boolean hasPlayersInside(Instance instance)
	{
		for (int objectId : instance.getPlayers())
		{
			final Player player = World.getInstance().getPlayer(objectId);
			if ((player != null) && player.isOnline() && (player.getInstanceId() == instance.getId()))
			{
				return true;
			}
		}
		return false;
	}

	// =========================================================================
	// Opening a map
	// =========================================================================

	/**
	 * @param player a player
	 * @return {@code true} if they stand in their own hideout and may work its device
	 */
	public boolean isDeviceOwner(Player player)
	{
		final Hideout hideout = getCurrentHideout(player);
		return (hideout != null) && (hideout.getOwnerId() == player.getObjectId());
	}

	/**
	 * @param player a player
	 * @return the map tiers they carry a map of, lowest first, with how many
	 */
	public Map<MapTier, Long> getCarriedMaps(Player player)
	{
		final Map<MapTier, Long> carried = new java.util.LinkedHashMap<>();
		for (MapTier tier : MapDeviceData.getInstance().getTiers().values())
		{
			final long count = player.getInventory().getInventoryItemCount(tier.itemId(), -1);
			if (count > 0)
			{
				carried.put(tier, count);
			}
		}
		return carried;
	}

	/**
	 * Opens a map at the device of the player's own hideout: takes the map and the medals, rolls the modifiers and the hot zone effect, builds the field and its monsters, and opens the portals.
	 * @param player the hideout's owner
	 * @param tierId the tier of the map they put in
	 * @param medals how many Champion Medals they feed it
	 * @return why it didn't open, {@code null} if it opened
	 */
	public String openMap(Player player, int tierId, int medals)
	{
		return openMap(player, tierId, medals, null, false);
	}
	
	/**
	 * A GM tries a map out (//mapdevice test): it opens at the device of their own hideout in the place they name, for nothing (no map item, no medal taken). A map of the event map page needs its fence and entry, ticked for the Map Device or not.
	 * @param gm the GM, in their own hideout
	 * @param arenaId the place: an event map's id, or the name of an arena of data/MapDevice.xml
	 * @param tierId the tier
	 * @param medals how many modifiers to roll
	 * @return why it didn't open, {@code null} if it opened
	 */
	public String openTestMap(Player gm, String arenaId, int tierId, int medals)
	{
		final MapArena arena = findArena(arenaId);
		if (arena == null)
		{
			return "No place " + arenaId + ": name an event map with a fence and an entry (//mapdevice list), or an arena of data/MapDevice.xml.";
		}
		return openMap(gm, tierId, medals, arena, true);
	}
	
	/**
	 * Opens a unique map at the device of the player's own hideout: takes the unique map item, and opens its map with its own modifiers (no medal, no hot zone effect), in its own arena.
	 * @param player the hideout's owner
	 * @param type the unique map
	 * @param free {@code true} for a GM's test (nothing is taken)
	 * @return why it didn't open, {@code null} if it opened
	 */
	public String openUniqueMap(Player player, UniqueMap.Type type, boolean free)
	{
		if (!MapDeviceConfig.ENABLED || !MapDeviceConfig.UNIQUE_MAPS_ENABLED)
		{
			return "Unique maps are disabled.";
		}
		final UniqueMap unique = MapDeviceData.getInstance().getUniqueMap(type);
		if (unique == null)
		{
			return "That unique map is unknown.";
		}
		if (!isDeviceOwner(player))
		{
			return "Only the owner of this hideout can work its Map Device.";
		}
		if (!_opening.add(player.getObjectId()))
		{
			return "The Map Device is already working.";
		}
		try
		{
			final Hideout hideout = getCurrentHideout(player);
			if ((hideout.getRun() != null) && !hideout.getRun().isClosed())
			{
				return "A map is already open. Finish it, or let it close, first.";
			}
			final MapTier tier = MapDeviceData.getInstance().getTier(unique.tier());
			if (tier == null)
			{
				return "The tier of " + unique.name() + " is unknown. Please contact an administrator.";
			}
			if (!free && (player.getInventory().getInventoryItemCount(unique.itemId(), -1) < 1))
			{
				return "You don't have a " + getItemName(unique.itemId()) + ".";
			}

			MapArena arena = findArena(unique.arena());
			if (arena == null)
			{
				final List<MapArena> arenas = MapDeviceData.getInstance().getArenas(tier.tier());
				if (arenas.isEmpty())
				{
					return "The Map Device has nowhere to open " + unique.name() + ". Please contact an administrator.";
				}
				arena = arenas.get(Rnd.get(arenas.size()));
			}
			final List<NpcTemplate> pool = getArenaMonsters(arena, tier);
			if (pool.isEmpty() || (NpcData.getInstance().getTemplate(unique.bossNpcId()) == null))
			{
				return "No monster fits " + unique.name() + ". Please contact an administrator.";
			}

			if (!free && !player.destroyItemByItemId(ItemProcessType.FEE, unique.itemId(), 1, player, true))
			{
				return "You don't have a " + getItemName(unique.itemId()) + ".";
			}

			final List<MapModifier> modifiers = new ArrayList<>();
			for (MapModifier modifier : unique.modifiers())
			{
				if (modifier.isAvailable())
				{
					modifiers.add(modifier);
				}
			}
			try
			{
				final MapRun run = createRun(player, hideout, tier, 0, modifiers, null, arena, pool, unique);
				UniqueMapManager.getInstance().onRunOpened(run);
				announceOpened(player, run);
				if (!free)
				{
					MapQuestManager.getInstance().onMapOpened(player);
				}
				LOGGER.info(getClass().getSimpleName() + ": " + player.getName() + (free ? " test-opened " : " opened ") + unique.name() + " in " + arena.name() + " (" + arena.id() + ", instance " + run.getInstanceId() + ") with " + modifiers + ".");
				return null;
			}
			catch (Exception e)
			{
				LOGGER.log(Level.WARNING, getClass().getSimpleName() + ": Could not open " + unique.name() + " in " + arena.id() + " for " + player.getName() + ".", e);
				if (free)
				{
					return "The map could not be opened: " + e.getMessage() + " (see the server log).";
				}
				player.addItem(ItemProcessType.REFUND, unique.itemId(), 1, player, true);
				return "The map could not be opened. Your map was given back.";
			}
		}
		finally
		{
			_opening.remove(player.getObjectId());
		}
	}

	/**
	 * @param player a player
	 * @return the unique maps they carry, with how many
	 */
	public Map<UniqueMap, Long> getCarriedUniqueMaps(Player player)
	{
		final Map<UniqueMap, Long> carried = new java.util.LinkedHashMap<>();
		for (UniqueMap unique : MapDeviceData.getInstance().getUniqueMaps().values())
		{
			final long count = player.getInventory().getInventoryItemCount(unique.itemId(), -1);
			if (count > 0)
			{
				carried.put(unique, count);
			}
		}
		return carried;
	}

	/**
	 * @param arenaId an event map's id, or the name of an arena of data/MapDevice.xml (with or without "xml:")
	 * @return that place, {@code null} if there is none (or the event map lacks its fence or entry)
	 */
	public static MapArena findArena(String arenaId)
	{
		if (arenaId == null)
		{
			return null;
		}
		final org.l2jmobius.gameserver.model.eventmap.EventMap map = org.l2jmobius.gameserver.data.xml.EventMapData.getInstance().getMap(arenaId);
		if (map != null)
		{
			return MapArena.of(map);
		}
		final String name = arenaId.startsWith("xml:") ? arenaId.substring(4) : arenaId;
		for (MapArena arena : MapDeviceData.getInstance().getArenas())
		{
			if (arena.name().equalsIgnoreCase(name) || arena.name().replace(' ', '_').equalsIgnoreCase(name))
			{
				return arena;
			}
		}
		return null;
	}
	
	/**
	 * @return the maps open right now
	 */
	public static Collection<MapRun> getRuns()
	{
		return Collections.unmodifiableCollection(RUNS_BY_INSTANCE.values());
	}
	
	private String openMap(Player player, int tierId, int medals, MapArena forcedArena, boolean free)
	{
		if (!MapDeviceConfig.ENABLED)
		{
			return "Maps are disabled.";
		}
		if (!isDeviceOwner(player))
		{
			return "Only the owner of this hideout can work its Map Device.";
		}
		if (!_opening.add(player.getObjectId()))
		{
			return "The Map Device is already working.";
		}
		try
		{
			final Hideout hideout = getCurrentHideout(player);
			if ((hideout.getRun() != null) && !hideout.getRun().isClosed())
			{
				return "A map is already open. Finish it, or let it close, first.";
			}

			final MapTier tier = MapDeviceData.getInstance().getTier(tierId);
			if (tier == null)
			{
				return "That map is unknown.";
			}
			if ((medals < 0) || (medals > MapDeviceConfig.MAX_MEDALS))
			{
				return "You can feed the device 0 to " + MapDeviceConfig.MAX_MEDALS + " " + getItemName(MapDeviceConfig.MEDAL_ITEM_ID) + "s.";
			}
			if (!free && (player.getInventory().getInventoryItemCount(tier.itemId(), -1) < 1))
			{
				return "You don't have a " + getItemName(tier.itemId()) + ".";
			}
			if (!free && (medals > 0) && (player.getInventory().getInventoryItemCount(MapDeviceConfig.MEDAL_ITEM_ID, -1) < medals))
			{
				return "You don't have " + medals + " " + getItemName(MapDeviceConfig.MEDAL_ITEM_ID) + "s.";
			}

			final MapArena arena;
			if (forcedArena != null)
			{
				arena = forcedArena;
			}
			else
			{
				final List<MapArena> arenas = MapDeviceData.getInstance().getArenas(tier.tier());
				if (arenas.isEmpty())
				{
					return "The Map Device has nowhere to open a tier " + tier.tier() + " map. Please contact an administrator.";
				}
				arena = arenas.get(Rnd.get(arenas.size()));
			}
			final List<NpcTemplate> pool = getArenaMonsters(arena, tier);
			if (pool.isEmpty())
			{
				return "No monster fits a tier " + tier.tier() + " map. Please contact an administrator.";
			}

			// Everything is checked: now take what the map costs.
			if (!free && !player.destroyItemByItemId(ItemProcessType.FEE, tier.itemId(), 1, player, true))
			{
				return "You don't have a " + getItemName(tier.itemId()) + ".";
			}
			if (!free && (medals > 0) && !player.destroyItemByItemId(ItemProcessType.FEE, MapDeviceConfig.MEDAL_ITEM_ID, medals, player, true))
			{
				player.addItem(ItemProcessType.REFUND, tier.itemId(), 1, player, true);
				return "You don't have " + medals + " " + getItemName(MapDeviceConfig.MEDAL_ITEM_ID) + "s.";
			}

			final List<MapModifier> modifiers = rollModifiers(medals);
			final HotzoneModifier hotzone = rollHotzoneEffect(tier);
			try
			{
				final MapRun run = createRun(player, hideout, tier, medals, modifiers, hotzone, arena, pool);
				announceOpened(player, run);
				if (!free)
				{
					MapQuestManager.getInstance().onMapOpened(player);
				}
				LOGGER.info(getClass().getSimpleName() + ": " + player.getName() + (free ? " test-opened" : " opened") + " a tier " + tier.tier() + " map in " + arena.name() + " (" + arena.id() + ", instance " + run.getInstanceId() + ") with " + medals + " medals " + modifiers + ", hot zone effect " + hotzone + ".");
				return null;
			}
			catch (Exception e)
			{
				LOGGER.log(Level.WARNING, getClass().getSimpleName() + ": Could not open a tier " + tier.tier() + " map in " + arena.id() + " for " + player.getName() + ".", e);
				if (free)
				{
					return "The map could not be opened: " + e.getMessage() + " (see the server log).";
				}
				player.addItem(ItemProcessType.REFUND, tier.itemId(), 1, player, true);
				if (medals > 0)
				{
					player.addItem(ItemProcessType.REFUND, MapDeviceConfig.MEDAL_ITEM_ID, medals, player, true);
				}
				return "The map could not be opened. Your map and medals were given back.";
			}
		}
		finally
		{
			_opening.remove(player.getObjectId());
		}
	}

	/**
	 * @param count how many modifiers
	 * @return that many different modifiers, picked at random among the ones the server can run
	 */
	public static List<MapModifier> rollModifiers(int count)
	{
		final List<MapModifier> pool = new ArrayList<>();
		for (MapModifier modifier : MapModifier.values())
		{
			if (modifier.isAvailable())
			{
				pool.add(modifier);
			}
		}
		Collections.shuffle(pool);
		final List<MapModifier> rolled = new ArrayList<>(pool.subList(0, Math.min(count, pool.size())));
		rolled.sort(null);
		return rolled;
	}

	/**
	 * @param tier the map's tier
	 * @return a hot zone effect for the whole map, {@code null} if none can apply
	 */
	private static HotzoneModifier rollHotzoneEffect(MapTier tier)
	{
		final List<HotzoneModifier> pool = new ArrayList<>();
		for (HotzoneModifier modifier : HotzoneModifier.values())
		{
			if (!modifier.isNightOnly() && !MapDeviceConfig.EXCLUDED_HOTZONE_EFFECTS.contains(modifier) && HotzoneModifierManager.canApplyAt(modifier, tier.maxLevel()))
			{
				pool.add(modifier);
			}
		}
		return pool.isEmpty() ? null : pool.get(Rnd.get(pool.size()));
	}

	private MapRun createRun(Player player, Hideout hideout, MapTier tier, int medals, List<MapModifier> modifiers, HotzoneModifier hotzone, MapArena arena, List<NpcTemplate> pool)
	{
		return createRun(player, hideout, tier, medals, modifiers, hotzone, arena, pool, null);
	}

	private MapRun createRun(Player player, Hideout hideout, MapTier tier, int medals, List<MapModifier> modifiers, HotzoneModifier hotzone, MapArena arena, List<NpcTemplate> pool, UniqueMap unique)
	{
		final Instance instance = InstanceManager.getInstance().createDynamicInstance(0);
		final MapRun run = new MapRun(_runIds.incrementAndGet(), player.getObjectId(), player.getName(), hideout, tier, medals, modifiers, hotzone, arena, instance.getId(), unique);
		instance.setExitLoc(getReturnLocation(player));
		instance.setEjectTime(0);
		instance.setAllowSummon(false);
		instance.setDuration((int) Math.min(Integer.MAX_VALUE, (run.getClosesAt() - System.currentTimeMillis()) + 120000L));

		// Registered before anything spawns, so the stat hooks already see a map.
		RUNS_BY_INSTANCE.put(instance.getId(), run);
		if (hotzone != null)
		{
			HotzoneModifierManager.getInstance().setInstanceModifier(instance.getId(), hotzone);
		}
		hideout.setRun(run);

		try
		{
			run.getFences().addAll(TeamVsTeamBarrier.build(arena.toBarrier(), arena.z(), instance.getId(), "Map Barrier"));
			if ((arena.source() != null) && !arena.source().doors().isEmpty())
			{
				TeamVsTeamManager.setUpDoors(arena.source(), instance);
			}
			spawnMonsters(run, pool);
			spawnReturnPortal(run);
			spawnPortals(run, hideout);
		}
		catch (RuntimeException e)
		{
			closeRun(run, null);
			throw e;
		}

		run.setTickTask(ThreadPool.scheduleAtFixedRate(new RunTick(run), 1000, 1000));
		return run;
	}

	private void announceOpened(Player player, MapRun run)
	{
		final UniqueMap unique = run.getUnique();
		if (unique != null)
		{
			player.sendPacket(new ExShowScreenMessage("Unique map opened: " + unique.name(), 5000));
			player.sendMessage("The Map Device burns crimson and gold: " + unique.name() + " is open, with " + run.getPortalCount() + " portals and " + unique.monsterCount() + " monsters before its end.");
			player.sendMessage("Twist - " + unique.twist());
			player.sendMessage("Final encounter - " + unique.finale());
		}
		else
		{
			player.sendPacket(new ExShowScreenMessage("Tier " + run.getTier().tier() + " map opened: " + run.getArena().name(), 5000));
			player.sendMessage("The Map Device hums: a tier " + run.getTier().tier() + " map to " + run.getArena().name() + " is open, with " + run.getPortalCount() + " portals and " + run.getMonsterTotal() + " monsters.");
		}
		for (MapModifier modifier : run.getModifiers())
		{
			player.sendMessage("Modifier - " + modifier.getDisplayName() + ": " + modifier.getDescription());
		}
		if (run.getHotzoneEffect() != null)
		{
			player.sendMessage("Hot zone effect - " + ClassTransferChallengeManager.formatOmen(run.getHotzoneEffect()) + ": " + run.getHotzoneEffect().getDescription());
		}
		if (run.getMedals() > 0)
		{
			player.sendMessage("Experience +" + (run.getMedals() * MapDeviceConfig.XP_BONUS_PER_MEDAL) + "%, drop rate +" + (run.getMedals() * MapDeviceConfig.DROP_BONUS_PER_MEDAL) + "% from the medals.");
		}

		// The party sees it too.
		final Party party = player.getParty();
		if ((party != null) && MapDeviceConfig.HIDEOUT_ALLOW_PARTY)
		{
			for (Player member : party.getMembers())
			{
				if ((member != null) && (member != player))
				{
					member.sendMessage(player.getName() + " opened " + (unique != null ? unique.name() : "a tier " + run.getTier().tier() + " map") + ". Type .hideout party to join through their portals.");
				}
			}
		}
	}

	// =========================================================================
	// Monsters
	// =========================================================================

	/**
	 * The monsters a map of a tier may hold: plain monsters of the tier's levels and strength ({@link MonsterStrength}) that live somewhere in the open world (a spawn that respawns), so no quest, event, instance or raid monster.
	 * @param tier a tier
	 * @return its monsters, empty if none fits
	 */
	public List<NpcTemplate> getMonsterPool(MapTier tier)
	{
		return _monsterPools.computeIfAbsent(tier.tier(), _ -> buildMonsterPool(tier));
	}

	private static List<NpcTemplate> buildMonsterPool(MapTier tier)
	{
		final List<NpcTemplate> ofLevel = new ArrayList<>();
		final List<NpcTemplate> pool = new ArrayList<>();
		for (NpcTemplate template : getWorldMonsters())
		{
			if (tier.allowsLevel(template.getLevel()))
			{
				ofLevel.add(template);
				if (tier.allowsScore(MonsterStrength.getScore(template)))
				{
					pool.add(template);
				}
			}
		}

		if (pool.isEmpty() && !ofLevel.isEmpty())
		{
			// Nothing of the tier's levels is that strong (or that weak): the closest in strength rather than no map at all.
			ofLevel.sort(Comparator.comparingDouble(template -> tier.scoreDistance(MonsterStrength.getScore(template))));
			pool.addAll(ofLevel.subList(0, Math.min(MapDeviceConfig.MONSTER_TYPES, ofLevel.size())));
			LOGGER.warning(MapDeviceManager.class.getSimpleName() + ": No monster of levels " + tier.minLevel() + "-" + tier.maxLevel() + " has a strength of " + tier.scoreRange() + " for tier " + tier.tier() + "; it takes the " + pool.size() + " closest (" + describeScores(pool) + "). Check minScore/maxScore in data/MapDevice.xml.");
		}
		else
		{
			LOGGER.info(MapDeviceManager.class.getSimpleName() + ": " + pool.size() + " of " + ofLevel.size() + " monsters can fill a tier " + tier.tier() + " map (levels " + tier.minLevel() + "-" + tier.maxLevel() + ", strength " + tier.scoreRange() + (pool.isEmpty() ? "" : ": " + describeScores(pool)) + ").");
		}
		pool.sort(Comparator.comparingDouble(MonsterStrength::getScore));
		return Collections.unmodifiableList(pool);
	}

	/**
	 * @return every monster that may fill a map of some tier: a plain monster with a respawning open world spawn, that moves, fights and gives experience
	 */
	private static List<NpcTemplate> getWorldMonsters()
	{
		final Set<Integer> excluded = MapDeviceData.getInstance().getExcludedNpcIds();
		final List<NpcTemplate> monsters = new ArrayList<>();
		for (Map.Entry<Integer, Set<Spawn>> entry : SpawnTable.getInstance().getSpawnTable().entrySet())
		{
			final NpcTemplate template = NpcData.getInstance().getTemplate(entry.getKey());
			if ((template == null) || !template.isType("Monster") || excluded.contains(template.getId()))
			{
				continue;
			}
			// No quest, event or decoration monster: those that give no experience or lie still (cocoons, crops, the Kratei's Cube maids).
			if (template.isUndying() || !template.canMove() || !template.isAttackable() || template.isQuestMonster() || template.isFakePlayer() || (template.getFakePlayerPvpProfile() != null) || (template.getExp() <= 0) || (template.getAIType() == AIType.CORPSE))
			{
				continue;
			}

			for (Spawn spawn : entry.getValue())
			{
				if ((spawn.getInstanceId() == 0) && (spawn.getRespawnMinDelay() > 0))
				{
					monsters.add(template);
					break;
				}
			}
		}
		return monsters;
	}

	/**
	 * @return the lowest and highest strength of these monsters, such as "3.8-4.1"
	 */
	private static String describeScores(List<NpcTemplate> monsters)
	{
		double low = Double.MAX_VALUE;
		double high = 0;
		for (NpcTemplate template : monsters)
		{
			final double score = MonsterStrength.getScore(template);
			low = Math.min(low, score);
			high = Math.max(high, score);
		}
		return "strength " + MonsterStrength.format(low) + "-" + MonsterStrength.format(high);
	}

	/**
	 * @param arena where the map opens
	 * @param tier the map's tier
	 * @return the monsters the arena names (whatever their level: the event map page chose them), or else those of the tier
	 */
	public List<NpcTemplate> getArenaMonsters(MapArena arena, MapTier tier)
	{
		if (arena.monsters().isEmpty())
		{
			return getMonsterPool(tier);
		}
		final List<NpcTemplate> monsters = new ArrayList<>();
		for (int npcId : arena.monsters())
		{
			final NpcTemplate template = NpcData.getInstance().getTemplate(npcId);
			if ((template != null) && template.isType("Monster"))
			{
				monsters.add(template);
			}
			else
			{
				LOGGER.warning(getClass().getSimpleName() + ": The map " + arena.id() + " names " + npcId + ", which is no monster: it is left out.");
			}
		}
		return monsters.isEmpty() ? getMonsterPool(tier) : monsters;
	}
	
	/**
	 * @param npcId an npc id
	 * @return {@code true} if it can fill a map: a plain monster ({@code type="Monster"})
	 */
	public static boolean isMapMonster(int npcId)
	{
		final NpcTemplate template = NpcData.getInstance().getTemplate(npcId);
		return (template != null) && template.isType("Monster");
	}
	
	/**
	 * Clears the monster pools and the strength scores, so they are worked out again (after a reload of the spawns or of data/MapDevice.xml).
	 */
	public void clearMonsterPools()
	{
		MonsterStrength.clear();
		_monsterPools.clear();
	}

	private void spawnMonsters(MapRun run, List<NpcTemplate> pool)
	{
		// A map has a theme: a few kinds of monster, not a hundred. A map of the event map page that names its monsters keeps them all.
		final MapArena arena = run.getArena();
		final List<NpcTemplate> shuffled = new ArrayList<>(pool);
		Collections.shuffle(shuffled);
		final List<NpcTemplate> kinds = arena.monsters().isEmpty() ? new ArrayList<>(shuffled.subList(0, Math.min(MapDeviceConfig.MONSTER_TYPES, shuffled.size()))) : shuffled;
		run.setMonsterKinds(kinds);
		
		// A unique map that fills itself (the Coliseum's waves).
		if (run.isUnique() && UniqueMapManager.getInstance().spawnMonsters(run))
		{
			return;
		}

		final int baseCount = run.isUnique() ? run.getUnique().monsterCount() : arena.monsterCount() > 0 ? arena.monsterCount() : MapDeviceConfig.MONSTER_COUNT;
		final int count = Math.max(1, (int) Math.round(baseCount * run.getPackSize()));
		final List<Location> spots = !arena.packs().isEmpty() ? arenaPackSpots(run, count) : run.isPacks() ? packSpots(run, count) : scatteredSpots(run, count);

		// Gauntlet: which monsters are wave challenges.
		final Set<Integer> waveIndexes = new HashSet<>();
		final int waves = Math.min(run.getWaveMonsters(), spots.size());
		while (waveIndexes.size() < waves)
		{
			waveIndexes.add(Rnd.get(spots.size()));
		}

		Monster previous = null;
		for (int i = 0; i < spots.size(); i++)
		{
			// Linked Souls: every second monster is its predecessor's twin, at its side.
			final boolean twin = run.isLinkedSouls() && ((i % 2) == 1) && (previous != null);
			final NpcTemplate template = twin ? previous.getTemplate() : kinds.get(Rnd.get(kinds.size()));
			final Location spot = twin ? nearSpot(run, previous, LINK_SPAWN_RANGE) : spots.get(i);
			final Monster monster = spawnMapMonster(run, template, spot);
			if (monster != null)
			{
				applyVariants(run, monster, waveIndexes.contains(i));
				if (twin)
				{
					run.getPartners().put(monster.getObjectId(), previous.getObjectId());
					run.getPartners().put(previous.getObjectId(), monster.getObjectId());
					monster.setEventTitleTag("[Linked]");
					previous.setEventTitleTag("[Linked]");
				}
			}
			previous = twin ? null : monster;
		}

		if (run.isGuardian())
		{
			spawnGuardian(run, kinds);
		}

		run.setMonsterTotal(run.getMonsters().size());
	}

	/**
	 * The monster spots of a map of the event map page: the monsters are shared out among them in turn, each somewhere within its spot's spread (Pack Hunters draws them in closer).
	 */
	private static List<Location> arenaPackSpots(MapRun run, int count)
	{
		final List<EventMapDevice.Pack> packs = new ArrayList<>(run.getArena().packs());
		Collections.shuffle(packs);
		final GeoEngine geo = GeoEngine.getInstance();
		final List<Location> spots = new ArrayList<>(count);
		for (int i = 0; i < count; i++)
		{
			final EventMapDevice.Pack pack = packs.get(i % packs.size());
			final int radius = run.isPacks() ? Math.max(100, pack.radius() / 3) : pack.radius();
			final int centerZ = geo.getHeight(pack.x(), pack.y(), pack.z());
			spots.add(spacedSpot(() ->
			{
				final double angle = Rnd.nextDouble() * 2 * Math.PI;
				final double distance = Math.sqrt(Rnd.nextDouble()) * radius;
				final int x = pack.x() + (int) (Math.cos(angle) * distance);
				final int y = pack.y() + (int) (Math.sin(angle) * distance);
				return geo.getValidLocation(pack.x(), pack.y(), centerZ, x, y, geo.getHeight(x, y, centerZ), run.getInstanceId());
			}, spots, run.isPacks() ? PACK_SPACING : MapDeviceConfig.MONSTER_SPACING));
		}
		return spots;
	}
	
	/**
	 * @return spots at random over the field, away from the entry and {@link MapDeviceConfig#MONSTER_SPACING} apart where the field has room
	 */
	private static List<Location> scatteredSpots(MapRun run, int count)
	{
		final List<Location> spots = new ArrayList<>(count);
		for (int i = 0; i < count; i++)
		{
			spots.add(spacedSpot(() -> randomFieldSpot(run, 0.92, true), spots, MapDeviceConfig.MONSTER_SPACING));
		}
		return spots;
	}

	/**
	 * Pack Hunters: the monsters stand in packs of {@value #PACK_MIN}-{@value #PACK_MAX} round a few spots.
	 */
	private static List<Location> packSpots(MapRun run, int count)
	{
		final List<Location> spots = new ArrayList<>(count);
		final List<Location> centers = new ArrayList<>();
		final GeoEngine geo = GeoEngine.getInstance();
		final int packGap = (2 * PACK_RADIUS) + MapDeviceConfig.MONSTER_SPACING;
		while (spots.size() < count)
		{
			final Location center = spacedSpot(() -> randomFieldSpot(run, 0.85, true), centers, packGap);
			centers.add(center);
			final int size = Math.min(count - spots.size(), Rnd.get(PACK_MIN, PACK_MAX));
			final List<Location> pack = new ArrayList<>(size);
			for (int i = 0; i < size; i++)
			{
				pack.add(spacedSpot(() ->
				{
					final double angle = Rnd.nextDouble() * 2 * Math.PI;
					final double distance = Math.sqrt(Rnd.nextDouble()) * PACK_RADIUS;
					final int x = center.getX() + (int) (Math.cos(angle) * distance);
					final int y = center.getY() + (int) (Math.sin(angle) * distance);
					return geo.getValidLocation(center.getX(), center.getY(), center.getZ(), x, y, geo.getHeight(x, y, center.getZ()), run.getInstanceId());
				}, pack, PACK_SPACING));
			}
			spots.addAll(pack);
		}
		return spots;
	}

	/**
	 * @return a spot on the ground within {@code range} of a creature, in its run's instance
	 */
	static Location nearSpot(MapRun run, Creature center, int range)
	{
		final GeoEngine geo = GeoEngine.getInstance();
		final double angle = Rnd.nextDouble() * 2 * Math.PI;
		final int distance = Rnd.get(range / 3, range);
		final int x = center.getX() + (int) (Math.cos(angle) * distance);
		final int y = center.getY() + (int) (Math.sin(angle) * distance);
		return geo.getValidLocation(center.getX(), center.getY(), center.getZ(), x, y, geo.getHeight(x, y, center.getZ()), run.getInstanceId());
	}

	/**
	 * Picks a spot that keeps its distance from the spots already taken: the first of up to {@value #SPACING_ATTEMPTS} tries that is at least {@code spacing} from all of them, or, if the field is too full for that, the try farthest from its nearest neighbour.
	 * @param candidates makes a random spot
	 * @param taken the spots already taken
	 * @param spacing how close two spots may be
	 * @return the spot
	 */
	static Location spacedSpot(Supplier<Location> candidates, List<Location> taken, int spacing)
	{
		Location best = null;
		double bestGap = -1;
		for (int attempt = 0; attempt < SPACING_ATTEMPTS; attempt++)
		{
			final Location candidate = candidates.get();
			double gap = Double.MAX_VALUE;
			for (Location other : taken)
			{
				gap = Math.min(gap, Math.hypot(candidate.getX() - other.getX(), candidate.getY() - other.getY()));
				if (gap < spacing)
				{
					break;
				}
			}
			if (gap >= spacing)
			{
				return candidate;
			}
			if (gap > bestGap)
			{
				best = candidate;
				bestGap = gap;
			}
		}
		return best;
	}

	/**
	 * @param run a run
	 * @param share how far towards the fence the spot may be, as a share of the field's radius
	 * @param awayFromEntry {@code true} to keep {@link MapDeviceConfig#SAFE_RADIUS} away from where the players come in
	 * @return a spot on the ground of the field, inside the fence
	 */
	static Location randomFieldSpot(MapRun run, double share, boolean awayFromEntry)
	{
		final MapArena arena = run.getArena();
		final GeoEngine geo = GeoEngine.getInstance();
		final int centerZ = geo.getHeight(arena.x(), arena.y(), arena.z());
		int x = arena.x();
		int y = arena.y();
		for (int attempt = 0; attempt < 12; attempt++)
		{
			final double angle = Rnd.nextDouble() * 2 * Math.PI;
			final double distance = Math.sqrt(Rnd.nextDouble()) * share;
			x = arena.x() + (int) (Math.cos(angle) * arena.radiusX() * distance);
			y = arena.y() + (int) (Math.sin(angle) * arena.radiusY() * distance);
			if (!awayFromEntry || (Math.hypot(x - arena.getEntryX(), y - arena.getEntryY()) >= MapDeviceConfig.SAFE_RADIUS))
			{
				break;
			}
		}
		return geo.getValidLocation(arena.x(), arena.y(), centerZ, x, y, geo.getHeight(x, y, arena.z()), run.getInstanceId());
	}

	/**
	 * Spawns one monster of a map: no spawn point (nothing respawns), its map's stat functions, tracked by the run.
	 */
	Monster spawnMapMonster(MapRun run, NpcTemplate template, Location spot)
	{
		try
		{
			final Monster monster = new Monster(template);
			monster.setInstanceId(run.getInstanceId());
			monster.setHeading(Rnd.get(65536));
			monster.spawnMe(spot.getX(), spot.getY(), spot.getZ());
			addMonsterFunctions(run, monster);
			run.getMonsters().add(monster.getObjectId());
			return monster;
		}
		catch (Exception e)
		{
			LOGGER.log(Level.WARNING, getClass().getSimpleName() + ": Could not spawn " + template.getId() + " in map instance " + run.getInstanceId() + ".", e);
			return null;
		}
	}

	/**
	 * The monster stats that don't change during a fight are plain stat functions, owned by the run.
	 */
	private static void addMonsterFunctions(MapRun run, Monster monster)
	{
		final Object owner = run.getMonsterFuncOwner();
		final List<AbstractFunction> functions = new ArrayList<>();
		mul(functions, Stat.MOVE_SPEED, run.getMonsterMoveSpeed(), owner);
		mul(functions, Stat.CRITICAL_RATE, run.getMonsterCritRate(), owner);
		mul(functions, Stat.CRITICAL_DAMAGE, run.getMonsterCritDamage(), owner);
		mul(functions, Stat.REGENERATE_HP_RATE, run.getMonsterHpRegen(), owner);
		add(functions, Stat.EVASION_RATE, run.getMonsterEvasion(), owner);
		add(functions, Stat.ABSORB_DAMAGE_PERCENT, run.getMonsterLeechPct(), owner);
		add(functions, Stat.REFLECT_DAMAGE_PERCENT, run.getMonsterReflectPct(), owner);
		mul(functions, Stat.MCRITICAL_RATE, run.getMonsterMCritRate(), owner);
		mul(functions, Stat.DEFENCE_CRITICAL_DAMAGE, run.getMonsterCritDamageTaken(), owner);
		if (run.getMonsterShieldPct() > 0)
		{
			add(functions, Stat.SHIELD_DEFENCE, Math.round(monster.getPDef(null) * SHIELD_DEF_SHARE), owner);
		}
		for (Stat stat : ELEMENT_RESISTANCES)
		{
			add(functions, stat, run.getMonsterElementRes(), owner);
		}
		if (!functions.isEmpty())
		{
			monster.addStatFuncs(functions);
		}
	}

	private static final Stat[] ELEMENT_RESISTANCES =
	{
		Stat.FIRE_RES,
		Stat.WATER_RES,
		Stat.WIND_RES,
		Stat.EARTH_RES,
		Stat.HOLY_RES,
		Stat.DARK_RES
	};

	private static void mul(List<AbstractFunction> functions, Stat stat, double value, Object owner)
	{
		if (value != 1.0)
		{
			functions.add(new FuncMul(stat, 0x30, owner, value, null));
		}
	}

	private static void add(List<AbstractFunction> functions, Stat stat, double value, Object owner)
	{
		if (value != 0)
		{
			functions.add(new FuncAdd(stat, 0x40, owner, value, null));
		}
	}

	/**
	 * Makes a freshly spawned map monster one of the server's special monsters, as its map's modifiers and hot zone effect ask (or by the open world's own chances), and gives it its forced personality. Order matters: the champion tier first (its title rebuild drops the other
	 * tags), then Mage or Thief, Infused, Resonant, the wave challenge.
	 */
	static void applyVariants(MapRun run, Monster monster, boolean wave)
	{
		final HotzoneModifier hotzone = run.getHotzoneEffect();

		// Champion.
		if (ChampionMonstersConfig.CHAMPION_ENABLE)
		{
			int tier = 0;
			if (run.getChampionPct() > 0)
			{
				if (Rnd.get(100) < run.getChampionPct())
				{
					final int roll = Rnd.get(100);
					tier = roll < 10 ? 3 : roll < 40 ? 2 : 1;
				}
			}
			else
			{
				final double mult = hotzone != null ? hotzone.getChampionSpawnMult() : 1.0;
				if ((Rnd.nextDouble() * 100) < (ChampionMonstersConfig.CHAMPION_T3_CHANCE * mult))
				{
					tier = 3;
				}
				else if ((Rnd.nextDouble() * 100) < (ChampionMonstersConfig.CHAMPION_T2_CHANCE * mult))
				{
					tier = 2;
				}
				else if ((Rnd.nextDouble() * 100) < (ChampionMonstersConfig.CHAMPION_T1_CHANCE * mult))
				{
					tier = 1;
				}
			}
			monster.setChampionTier(tier);
		}

		// Personality first: a supporter or a coward keeps its kit, and a Mage needs spells to cast.
		final MonsterArchetype archetype = run.rollArchetype(Rnd.get(100));

		// Mage or Thief.
		final double mageChance = run.getMagePct() > 0 ? run.getMagePct() : MageMonsterConfig.SPAWN_CHANCE * (hotzone != null ? hotzone.getMageSpawnMult() : 1.0);
		final double thiefChance = run.getThiefPct() > 0 ? run.getThiefPct() : ThiefMonsterConfig.SPAWN_CHANCE * (hotzone != null ? hotzone.getThiefSpawnMult() : 1.0);
		if (MageMonsterConfig.ENABLED && ((Rnd.nextDouble() * 100) < mageChance))
		{
			if ((archetype == null) && MageMonsterManager.getMagicDamageSkills(monster).isEmpty() && (monster.getAI() instanceof AttackableAI ai))
			{
				ai.setArchetype(MonsterArchetype.MAGE);
			}
			monster.startMage();
		}
		else if (ThiefMonsterConfig.ENABLED && ((Rnd.nextDouble() * 100) < thiefChance))
		{
			monster.startThief();
		}

		if ((archetype != null) && (monster.getAI() instanceof AttackableAI ai))
		{
			ai.setArchetype(archetype);
		}

		// Infused.
		final double infusedChance = run.getInfusedPct() > 0 ? run.getInfusedPct() : InfusedMonsterConfig.SPAWN_CHANCE * (hotzone != null ? hotzone.getInfusedSpawnMult() : 1.0);
		if (InfusedMonsterConfig.ENABLED && ((Rnd.nextDouble() * 100) < infusedChance))
		{
			monster.startInfused((byte) Rnd.get(InfusedMonsterConfig.ELEMENT_COUNT));
		}

		// Resonant: only at the open world's chance, never with an attribute too.
		if (ResonantMonsterConfig.ENABLED && !monster.isInfused() && ((Rnd.nextDouble() * 100) < (ResonantMonsterConfig.SPAWN_CHANCE * (hotzone != null ? hotzone.getResonantSpawnMult() : 1.0))))
		{
			monster.startResonant();
		}

		// Wave challenge: the Gauntlet's, or at the open world's chance.
		if (WaveChallengeConfig.ENABLED && !monster.isThief() && (wave || ((Rnd.nextDouble() * 100) < (WaveChallengeConfig.SPAWN_CHANCE * (hotzone != null ? hotzone.getWaveSpawnMult() : 1.0)))))
		{
			monster.startWaveChallenge(wave ? 3 : WaveChallengeConfig.WAVE_COUNT);
		}

		if (run.isTitans())
		{
			monster.startAbnormalVisualEffect(true, AbnormalVisualEffect.BIG_BODY);
		}

		// Summoners: calls its minions when first hit.
		if ((run.getSummonerPct() > 0) && (Rnd.get(100) < run.getSummonerPct()))
		{
			monster.getVariables().set(SUMMONER_VAR, true);
			monster.setEventTitleTag("[Summoner]");
		}

		monster.setCurrentHpMp(monster.getMaxHp(), monster.getMaxMp());
		monster.broadcastInfo();
	}

	/**
	 * Map Guardian: the strongest kind of the map, in the middle of the field, a tier 3 Champion that rises twice.
	 */
	private void spawnGuardian(MapRun run, List<NpcTemplate> kinds)
	{
		NpcTemplate strongest = kinds.get(0);
		for (NpcTemplate template : kinds)
		{
			if ((template.getLevel() > strongest.getLevel()) || ((template.getLevel() == strongest.getLevel()) && (MonsterStrength.getScore(template) > MonsterStrength.getScore(strongest))))
			{
				strongest = template;
			}
		}

		final MapArena arena = run.getArena();
		final Location middle = getGroundLocation(arena.guardian() != null ? arena.guardian() : new Location(arena.x(), arena.y(), arena.z()));
		final Monster guardian = spawnMapMonster(run, strongest, middle);
		if (guardian == null)
		{
			return;
		}

		guardian.getVariables().set(GUARDIAN_VAR, true);
		if (ChampionMonstersConfig.CHAMPION_ENABLE)
		{
			guardian.setChampionTier(3);
		}
		if (WaveChallengeConfig.ENABLED)
		{
			guardian.startWaveChallenge(3);
		}
		guardian.setEventTitleTag("[Map Guardian]");
		guardian.startAbnormalVisualEffect(true, AbnormalVisualEffect.BIG_BODY);
		guardian.setCurrentHpMp(guardian.getMaxHp(), guardian.getMaxMp());
		guardian.broadcastInfo();
	}

	// =========================================================================
	// Portals
	// =========================================================================

	private static void spawnPortals(MapRun run, Hideout hideout)
	{
		final Location device = getGroundLocation(MapDeviceData.getInstance().getDevicePosition());
		final GeoEngine geo = GeoEngine.getInstance();
		final int count = run.getPortalCount();
		for (int i = 0; i < count; i++)
		{
			final double angle = (i * 2 * Math.PI) / count;
			final int x = device.getX() + (int) Math.round(Math.cos(angle) * MapDeviceConfig.PORTAL_RADIUS);
			final int y = device.getY() + (int) Math.round(Math.sin(angle) * MapDeviceConfig.PORTAL_RADIUS);
			final Location spot = geo.getValidLocation(device.getX(), device.getY(), device.getZ(), x, y, geo.getHeight(x, y, device.getZ()), hideout.getInstanceId());
			final int heading = (int) Math.round((Math.atan2(device.getY() - spot.getY(), device.getX() - spot.getX()) * 32768) / Math.PI) & 0xFFFF;
			final Npc portal = Quest.addSpawn(MapDeviceConfig.PORTAL_NPC_ID, spot.getX(), spot.getY(), spot.getZ(), heading, false, 0, false, hideout.getInstanceId());
			if (portal != null)
			{
				portal.setTitle("Tier " + run.getTier().tier() + " - " + run.getArena().name());
				portal.broadcastInfo();
				run.getPortals().add(portal);
			}
		}
	}

	private static void spawnReturnPortal(MapRun run)
	{
		final MapArena arena = run.getArena();
		final Location entry = getGroundLocation(arena.entry());
		final Npc portal = Quest.addSpawn(MapDeviceConfig.RETURN_PORTAL_NPC_ID, entry.getX(), entry.getY(), entry.getZ(), entry.getHeading(), false, 0, false, run.getInstanceId());
		run.setReturnPortal(portal);
	}

	/**
	 * @param player a player
	 * @param run a run
	 * @return {@code true} if the player may take its portals: its owner, or a member of the owner's party
	 */
	public static boolean isAllowed(Player player, MapRun run)
	{
		if (player.getObjectId() == run.getOwnerId())
		{
			return true;
		}
		final Party party = player.getParty();
		if (!MapDeviceConfig.HIDEOUT_ALLOW_PARTY || (party == null))
		{
			return false;
		}
		for (Player member : party.getMembers())
		{
			if ((member != null) && (member.getObjectId() == run.getOwnerId()))
			{
				return true;
			}
		}
		return false;
	}

	/**
	 * Takes a player through a portal into the map. The portal is used up.
	 * @param player the player
	 * @param portal the portal they clicked
	 * @return why they can't go, {@code null} if they went
	 */
	public String usePortal(Player player, Npc portal)
	{
		final Hideout hideout = getCurrentHideout(player);
		final MapRun run = hideout != null ? hideout.getRun() : null;
		if ((run == null) || (portal == null) || (portal.getInstanceId() != hideout.getInstanceId()) || !run.getPortals().contains(portal))
		{
			return "This portal leads nowhere.";
		}
		if (run.getStatus() != Status.OPEN)
		{
			return run.getStatus() == Status.COMPLETED ? "This map is complete: every monster is slain." : "This map is closed.";
		}
		if (!isAllowed(player, run))
		{
			return "Only " + run.getOwnerName() + " and their party may take these portals.";
		}
		if (player.isDead() || player.isAlikeDead())
		{
			return "You are dead.";
		}
		if (player.calculateDistance2D(portal) > 300)
		{
			return "Come closer to the portal.";
		}

		synchronized (run)
		{
			// The check and the removal at once: two players can't take the same portal.
			if (!run.getPortals().remove(portal))
			{
				return "This portal is already used.";
			}
		}
		portal.deleteMe();

		final MapArena arena = run.getArena();
		final Location entry = getGroundLocation(arena.entry());
		player.teleToLocation(entry.getX(), entry.getY(), entry.getZ(), entry.getHeading(), run.getInstanceId(), ENTRY_SPREAD);
		return null;
	}

	/**
	 * The return portal of a map takes a player back to the hideout. It never runs out.
	 * @param player the player
	 * @return why they can't go, {@code null} if they went
	 */
	public String useReturnPortal(Player player)
	{
		final MapRun run = getRun(player.getInstanceId());
		if (run == null)
		{
			return "This portal leads nowhere.";
		}
		if (player.isDead())
		{
			return "You are dead.";
		}
		sendOut(player, run);
		return null;
	}

	/**
	 * The owner closes their open map early: everyone inside is sent back, the portals vanish.
	 * @param player the owner
	 * @return why it didn't close, {@code null} if it did
	 */
	public String abandonMap(Player player)
	{
		if (!isDeviceOwner(player))
		{
			return "Only the owner of this hideout can work its Map Device.";
		}
		final MapRun run = getCurrentHideout(player).getRun();
		if ((run == null) || run.isClosed())
		{
			return "No map is open.";
		}
		closeRun(run, "The map was closed by " + player.getName() + ".");
		return null;
	}

	// =========================================================================
	// Players in a map
	// =========================================================================

	/**
	 * Hook of {@code WorldObject.setInstanceId}: a player who comes into a map gets its player modifiers and hot zone buff, one who leaves it loses them, and one who goes back to the world from a hideout or a map no longer needs their way back.
	 * @param player the player
	 * @param oldInstanceId the instance they were in
	 * @param newInstanceId the instance they are in now
	 */
	public static void onInstanceChanged(Player player, int oldInstanceId, int newInstanceId)
	{
		// A map's passives (the Crimson Coliseum's) never leave its instance, before anything else and whatever else happens.
		PassiveTreeManager.getInstance().onInstanceChanged(player, newInstanceId);

		if (RUNS_BY_INSTANCE.isEmpty() && HIDEOUTS_BY_INSTANCE.isEmpty())
		{
			if ((newInstanceId == 0) && player.getVariables().hasVariable(RETURN_LOC_VAR))
			{
				player.getVariables().remove(RETURN_LOC_VAR);
			}
			return;
		}

		final MapRun oldRun = getRun(oldInstanceId);
		if (oldRun != null)
		{
			oldRun.getPlayersInside().remove(player.getObjectId());
			removePlayerEffects(player, oldRun);
			if (oldRun.isUnique())
			{
				UniqueMapManager.getInstance().onPlayerLeft(player, oldRun);
			}
		}

		final MapRun newRun = getRun(newInstanceId);
		if (newRun != null)
		{
			newRun.getPlayersInside().add(player.getObjectId());
			addPlayerEffects(player, newRun);
			if (newRun.isUnique())
			{
				UniqueMapManager.getInstance().onPlayerEntered(player, newRun);
			}
		}

		if ((newRun != null) || isHideoutInstance(newInstanceId))
		{
			MapQuestManager.getInstance().onArrived(player);
		}

		if ((newInstanceId == 0) && ((oldRun != null) || isHideoutInstance(oldInstanceId) || player.getVariables().hasVariable(RETURN_LOC_VAR)))
		{
			player.getVariables().remove(RETURN_LOC_VAR);
		}
	}

	private static void addPlayerEffects(Player player, MapRun run)
	{
		final Object owner = run.getPlayerFuncOwner();
		player.removeStatsOwner(owner);
		final List<AbstractFunction> functions = new ArrayList<>();
		mul(functions, Stat.MAX_HP, run.getPlayerMaxHp(), owner);
		mul(functions, Stat.POWER_DEFENCE, run.getPlayerDef(), owner);
		mul(functions, Stat.MAGIC_DEFENCE, run.getPlayerDef(), owner);
		mul(functions, Stat.REGENERATE_MP_RATE, run.getPlayerMpRegen(), owner);
		mul(functions, Stat.REGENERATE_HP_RATE, run.getPlayerHpRegen(), owner);
		mul(functions, Stat.MAGICAL_MP_CONSUME_RATE, run.getPlayerMpCost(), owner);
		mul(functions, Stat.PHYSICAL_MP_CONSUME_RATE, run.getPlayerMpCost(), owner);
		mul(functions, Stat.DANCE_MP_CONSUME_RATE, run.getPlayerMpCost(), owner);
		mul(functions, Stat.MOVE_SPEED, run.getPlayerMoveSpeed(), owner);
		mul(functions, Stat.MAGIC_REUSE_RATE, run.getPlayerReuse(), owner);
		mul(functions, Stat.P_REUSE, run.getPlayerReuse(), owner);
		mul(functions, Stat.DANCE_REUSE, run.getPlayerReuse(), owner);
		mul(functions, Stat.CRITICAL_RATE, run.getPlayerCritRate(), owner);
		mul(functions, Stat.MCRITICAL_RATE, run.getPlayerCritRate(), owner);
		mul(functions, Stat.DEFENCE_CRITICAL_DAMAGE, run.getPlayerCritDamageTaken(), owner);
		mul(functions, Stat.HEAL_EFFECT, run.getPlayerHealReceived(), owner);
		add(functions, Stat.ACCURACY_COMBAT, run.getPlayerAccuracy(), owner);
		for (Stat stat : ELEMENT_RESISTANCES)
		{
			add(functions, stat, run.getPlayerElementRes(), owner);
		}
		if (!functions.isEmpty())
		{
			player.addStatFuncs(functions);
		}
		applyHotzoneBuff(player, run.getHotzoneEffect());
		ThreadPool.schedule(() -> refreshPlayer(player), 500);
	}

	private static void removePlayerEffects(Player player, MapRun run)
	{
		player.removeStatsOwner(run.getPlayerFuncOwner());
		if (run.getCurses().remove(player.getObjectId()) != null)
		{
			player.removeStatsOwner(run.getCurseFuncOwner());
		}
		final HotzoneModifier hotzone = run.getHotzoneEffect();
		if (hotzone != null)
		{
			if (hotzone.getPlayerBuffSkillId() > 0)
			{
				player.getEffectList().stopSkillEffects(SkillFinishType.REMOVED, hotzone.getPlayerBuffSkillId());
			}
			if (hotzone.isKillStreak())
			{
				player.getEffectList().stopSkillEffects(SkillFinishType.REMOVED, HotzoneModifier.KILL_STREAK_SKILL_ID);
			}
		}
		ThreadPool.schedule(() -> refreshPlayer(player), 500);
	}

	private static void applyHotzoneBuff(Player player, HotzoneModifier hotzone)
	{
		if ((hotzone == null) || (hotzone.getPlayerBuffSkillId() <= 0) || player.isDead() || (player.getEffectList().getBuffInfoBySkillId(hotzone.getPlayerBuffSkillId()) != null))
		{
			return;
		}
		final Skill buff = SkillData.getInstance().getSkill(hotzone.getPlayerBuffSkillId(), 1);
		if (buff != null)
		{
			buff.applyEffects(player, player);
		}
	}

	private static void refreshPlayer(Player player)
	{
		if (!player.isOnline())
		{
			return;
		}
		if (player.getCurrentHp() > player.getMaxHp())
		{
			player.setCurrentHp(player.getMaxHp());
		}
		player.broadcastUserInfo();
	}

	/**
	 * Hook of {@code Player.doDie}: a player who dies in a map wakes up in the hideout a moment later, and may take another portal.
	 * @param player the player who died
	 */
	public void onPlayerDied(Player player)
	{
		final MapRun run = getRun(player.getInstanceId());
		if (run == null)
		{
			return;
		}

		player.sendMessage(isAllowed(player, run) ? "You have fallen. You will wake up in the hideout." : "You have fallen. You are no longer in " + run.getOwnerName() + "'s party: you will wake up in your own hideout.");
		final int instanceId = run.getInstanceId();
		ThreadPool.schedule(() ->
		{
			try
			{
				if (!player.isOnline() || !player.isDead() || (player.getInstanceId() != instanceId))
				{
					return; // Resurrected, gone, or already out.
				}
				player.doRevive();
				if (MapDeviceConfig.DEATH_REVIVE_FULL)
				{
					player.setCurrentHpMp(player.getMaxHp(), player.getMaxMp());
					player.setCurrentCp(player.getMaxCp());
				}
				final boolean allowed = isAllowed(player, run);
				sendOut(player, run);
				if (allowed)
				{
					final int portals = run.getPortals().size();
					player.sendMessage(portals > 0 ? portals + " portal" + (portals == 1 ? "" : "s") + " to the map remain." : "No portal to the map remains.");
				}
			}
			catch (Exception e)
			{
				LOGGER.log(Level.WARNING, MapDeviceManager.class.getSimpleName() + ": Could not bring " + player.getName() + " back from a map.", e);
			}
		}, MapDeviceConfig.DEATH_RETURN_DELAY * 1000L);
	}

	/**
	 * Sends a player out of a map: to the hideout it was opened from, or where they entered the hideout from if it is gone.
	 */
	private void sendOut(Player player, MapRun run)
	{
		// Someone who left the owner's party (or was removed from it) farms on until they die or leave, then goes to a hideout of their own: the owner's is no longer theirs to wake up in.
		if (!isAllowed(player, run))
		{
			Hideout own = _hideouts.get(player.getObjectId());
			if ((own == null) || (InstanceManager.getInstance().getInstance(own.getInstanceId()) == null))
			{
				own = createHideout(player);
			}
			if (own != null)
			{
				teleportToHideout(player, own);
				player.sendMessage("You are no longer in " + run.getOwnerName() + "'s party: you are in your own hideout. Type .hideout leave to go back where you came from.");
				return;
			}
		}

		final Hideout hideout = run.getHideout();
		if ((hideout != null) && (InstanceManager.getInstance().getInstance(hideout.getInstanceId()) != null) && HIDEOUTS_BY_INSTANCE.containsKey(hideout.getInstanceId()))
		{
			teleportToHideout(player, hideout);
			return;
		}
		final Location loc = getReturnLocation(player);
		player.teleToLocation(loc.getX(), loc.getY(), loc.getZ(), loc.getHeading(), 0);
	}

	/**
	 * @param run a run
	 * @return the players inside its map, alive or not
	 */
	static List<Player> getPlayersInside(MapRun run)
	{
		final List<Player> players = new ArrayList<>();
		for (int objectId : run.getPlayersInside())
		{
			final Player player = World.getInstance().getPlayer(objectId);
			if ((player != null) && player.isOnline() && (player.getInstanceId() == run.getInstanceId()))
			{
				players.add(player);
			}
		}
		return players;
	}

	static void sendToMap(MapRun run, String message)
	{
		for (Player player : getPlayersInside(run))
		{
			player.sendMessage(message);
		}
	}

	static void sendScreenToMap(MapRun run, String message)
	{
		final ExShowScreenMessage packet = new ExShowScreenMessage(message, 4000);
		for (Player player : getPlayersInside(run))
		{
			player.sendPacket(packet);
		}
	}

	// =========================================================================
	// Kills and drops
	// =========================================================================

	/**
	 * Hook of {@code Attackable.doDie}, for every death: counts the monsters of a map, runs its death modifiers, and rolls a map drop.
	 * @param victim what died
	 * @param killer who killed it, may be {@code null}
	 * @param player the player credited with the kill, {@code null} for none
	 * @param rewards {@code true} if the kill rewards experience (no drop otherwise)
	 */
	public void onAttackableDied(Attackable victim, Creature killer, Player player, boolean rewards)
	{
		if (!MapDeviceConfig.ENABLED || (victim == null))
		{
			return;
		}

		final MapRun run = getRun(victim.getInstanceId());
		if ((run != null) && victim.isMonster())
		{
			onMapMonsterDied(run, victim.asMonster(), player);
		}

		if ((player != null) && rewards)
		{
			if ((run == null) && giveFirstMap(victim, player))
			{
				return;
			}
			rollMapDrop(victim, player, run);
		}
	}

	/**
	 * The guaranteed first map: the first monster a character kills once it is at least {@link MapDeviceConfig#FIRST_MAP_LEVEL_PERCENT}% into level {@link MapDeviceConfig#FIRST_MAP_LEVEL} (or above) drops a tier 1 map, once per character.
	 * @param victim the monster slain
	 * @param player the player credited
	 * @return {@code true} if it dropped the first map
	 */
	private static boolean giveFirstMap(Attackable victim, Player player)
	{
		if (!MapDeviceConfig.FIRST_MAP_ENABLED || !victim.isMonster() || victim.isFakePlayer() || victim.isPvpFakePlayer() || (victim instanceof Chest) || (player.getLevel() < MapDeviceConfig.FIRST_MAP_LEVEL) || player.getVariables().getBoolean(FIRST_MAP_VAR, false))
		{
			return false;
		}
		if (player.getLevel() == MapDeviceConfig.FIRST_MAP_LEVEL)
		{
			final long start = ExperienceData.getInstance().getExpForLevel(MapDeviceConfig.FIRST_MAP_LEVEL);
			final long next = ExperienceData.getInstance().getExpForLevel(MapDeviceConfig.FIRST_MAP_LEVEL + 1);
			if ((next > start) && (((player.getExp() - start) * 100) < ((next - start) * MapDeviceConfig.FIRST_MAP_LEVEL_PERCENT)))
			{
				return false;
			}
		}
		final MapTier first = MapDeviceData.getInstance().getTier(1);
		if ((first == null) || (ItemData.getInstance().getTemplate(first.itemId()) == null))
		{
			return false;
		}
		player.getVariables().set(FIRST_MAP_VAR, true);
		dropMap(victim, player, first.tier());
		player.sendPacket(new ExShowScreenMessage("Your first map! Open it at the Map Device: type .hideout in a town.", 6000));
		return true;
	}

	/**
	 * Drops a map of a run, if its map budget ({@link MapDeviceConfig#MAX_MAPS_PER_RUN}) allows.
	 * @return {@code true} if it dropped
	 */
	static boolean dropRunMap(MapRun run, Attackable victim, Player player, int tierId)
	{
		if (!run.claimMapDrop())
		{
			return false;
		}
		dropMap(victim, player, tierId);
		return true;
	}

	private void onMapMonsterDied(MapRun run, Monster victim, Player player)
	{
		if (run.isClosed())
		{
			return;
		}

		final boolean counted = run.getMonsters().remove(victim.getObjectId());
		if (counted)
		{
			run.addKill();
		}

		final Location corpse = new Location(victim.getX(), victim.getY(), victim.getZ(), victim.getHeading());
		final boolean encounter = UniqueMapManager.isEncounterMonster(victim);

		if (run.isUnique())
		{
			UniqueMapManager.getInstance().onMonsterDied(run, victim, player, counted);
		}

		if (run.isBounty() && (run.getBountyId() == victim.getObjectId()))
		{
			final boolean inTime = System.currentTimeMillis() <= run.getBountyUntil();
			if (inTime && run.isUnique())
			{
				UniqueMapManager.getInstance().onBountyClaimed(run);
			}
			run.setBounty(0, 0);
			sendScreenToMap(run, inTime ? "Bounty claimed: the " + victim.getName() + " drops triple!" : "The " + victim.getName() + " is slain, too late for its bounty.");
		}

		if (run.isDeathCurse() && (player != null) && (player.getInstanceId() == run.getInstanceId()))
		{
			curse(run, player);
		}

		if (run.isVolatileDeath())
		{
			ThreadPool.schedule(() -> explode(run, victim, corpse), VOLATILE_DELAY);
		}

		if (run.isSoulEaters())
		{
			devourSoul(run, victim);
		}

		if (victim.getVariables().getBoolean(GUARDIAN_VAR, false) && (player != null))
		{
			for (int i = 0; i < MapDeviceConfig.GUARDIAN_EXTRA_MAPS; i++)
			{
				dropRunMap(run, victim, player, rollInMapTier(run));
			}
			sendScreenToMap(run, "The Map Guardian has fallen!");
		}

		// Splitters: a counted monster that is no copy itself splits in two.
		if (counted && !encounter && run.isSplitters() && !victim.getVariables().getBoolean(SPLIT_VAR, false) && !victim.getVariables().getBoolean(MINION_VAR, false) && !victim.getVariables().getBoolean(GUARDIAN_VAR, false))
		{
			ThreadPool.schedule(() -> split(run, victim.getTemplate(), corpse), 800);
			return; // Not cleared yet: its copies come.
		}

		// Undying Legion: a counted monster that never rose before may come back once.
		if (counted && !encounter && (run.getRisePct() > 0) && !victim.getVariables().getBoolean(RISEN_VAR, false) && !victim.getVariables().getBoolean(SPLIT_VAR, false) && !victim.getVariables().getBoolean(GUARDIAN_VAR, false) && (Rnd.get(100) < run.getRisePct()))
		{
			ThreadPool.schedule(() -> riseAgain(run, victim.getTemplate(), corpse), VOLATILE_DELAY);
			return; // Not cleared yet: it comes back.
		}

		if (run.isAmbush() && !run.isAmbushed() && (run.getKills() >= Math.max(1, run.getMonsterTotal() / 2)))
		{
			run.setAmbushed(true);
			ThreadPool.schedule(() -> ambush(run), 1000);
			return;
		}

		if (counted && run.getMonsters().isEmpty() && (run.getStatus() == Status.OPEN))
		{
			// A unique map's field is cleared: its next wave or its final encounter, unless that was its end.
			if (run.isUnique() && UniqueMapManager.getInstance().onFieldCleared(run))
			{
				return;
			}
			completeRun(run, victim, player);
		}
		else if (counted && !run.isUnique() && (run.getMonsters().size() == LAST_HUNT_COUNT))
		{
			sendScreenToMap(run, "Only " + LAST_HUNT_COUNT + " monsters remain: they are coming for you!");
		}
	}

	/**
	 * Volatile: the corpse bursts, hitting the players nearby.
	 */
	private static void explode(MapRun run, Monster victim, Location corpse)
	{
		if (run.isClosed())
		{
			return;
		}
		victim.broadcastPacket(new MagicSkillUse(victim, victim, VOLATILE_SKILL_ID, 1, 0, 0));
		for (Player player : getPlayersInside(run))
		{
			if (!player.isDead() && !player.isInvul() && (player.calculateDistance2D(corpse) <= VOLATILE_RADIUS))
			{
				player.reduceCurrentHp(player.getMaxHp() * VOLATILE_DAMAGE, null, false, false, null);
				player.sendMessage("The " + victim.getName() + " explodes!");
			}
		}
	}

	/**
	 * Soul Eaters: the monsters near a slain one heal and grow stronger.
	 */
	private static void devourSoul(MapRun run, Monster victim)
	{
		for (Monster eater : World.getInstance().getVisibleObjectsInRange(victim, Monster.class, SOUL_RANGE))
		{
			if (eater.isDead() || (eater.getInstanceId() != run.getInstanceId()) || eater.isPvpFakePlayer())
			{
				continue;
			}
			final int souls = eater.getVariables().getInt(SOULS_VAR, 0);
			if (souls < SOUL_MAX)
			{
				eater.getVariables().set(SOULS_VAR, souls + 1);
			}
			eater.setCurrentHp(Math.min(eater.getMaxHp(), eater.getCurrentHp() + (eater.getMaxHp() * 0.20)));
			eater.broadcastPacket(new MagicSkillUse(eater, eater, 4529, 1, 0, 0));
		}
	}

	/**
	 * Undying Legion: the slain monster gets up again at half HP. It counts for the map again.
	 */
	private void riseAgain(MapRun run, NpcTemplate template, Location corpse)
	{
		if (run.isClosed())
		{
			return;
		}
		final Monster risen = spawnMapMonster(run, template, corpse);
		if (risen == null)
		{
			checkCompleted(run);
			return;
		}
		risen.getVariables().set(RISEN_VAR, true);
		risen.setEventTitleTag("[Risen]");
		risen.setCurrentHp(risen.getMaxHp() * 0.5);
		run.setMonsterTotal(run.getMonsterTotal() + 1);
		sendToMap(run, "The " + risen.getName() + " rises again!");
	}

	/**
	 * Splitters: the slain monster splits into copies with half its HP. They count for the map.
	 */
	private void split(MapRun run, NpcTemplate template, Location corpse)
	{
		if (run.isClosed())
		{
			return;
		}
		final GeoEngine geo = GeoEngine.getInstance();
		int spawned = 0;
		for (int i = 0; i < SPLIT_COUNT; i++)
		{
			final double angle = (Rnd.nextDouble() * 2 * Math.PI);
			final int x = corpse.getX() + (int) (Math.cos(angle) * 80);
			final int y = corpse.getY() + (int) (Math.sin(angle) * 80);
			final Location spot = geo.getValidLocation(corpse.getX(), corpse.getY(), corpse.getZ(), x, y, geo.getHeight(x, y, corpse.getZ()), run.getInstanceId());
			final Monster copy = spawnMapMonster(run, template, spot);
			if (copy != null)
			{
				copy.getVariables().set(SPLIT_VAR, true);
				copy.setEventTitleTag("[Split]");
				copy.setCurrentHpMp(copy.getMaxHp(), copy.getMaxMp());
				copy.broadcastInfo();
				spawned++;
			}
		}
		if (spawned == 0)
		{
			checkCompleted(run);
			return;
		}
		run.setMonsterTotal(run.getMonsterTotal() + spawned);
	}

	/**
	 * Summoners: the monster calls its minions on whoever hit it first. They count for the map.
	 */
	private void summonMinions(MapRun run, Monster summoner, Creature attacker)
	{
		if (run.isClosed() || summoner.isDead() || (run.getStatus() != Status.OPEN))
		{
			return;
		}
		final List<NpcTemplate> pool = run.getMonsterKinds().isEmpty() ? List.of(summoner.getTemplate()) : run.getMonsterKinds();
		summoner.broadcastPacket(new MagicSkillUse(summoner, summoner, SUMMON_SKILL_ID, 1, 0, 0));
		int spawned = 0;
		for (int i = 0; i < SUMMON_COUNT; i++)
		{
			final Monster minion = spawnMapMonster(run, pool.get(Rnd.get(pool.size())), nearSpot(run, summoner, 150));
			if (minion == null)
			{
				continue;
			}
			minion.getVariables().set(MINION_VAR, true);
			minion.setEventTitleTag("[Minion]");
			minion.setCurrentHpMp(minion.getMaxHp(), minion.getMaxMp());
			minion.broadcastInfo();
			if (!attacker.isDead() && (attacker.getInstanceId() == run.getInstanceId()))
			{
				minion.addDamageHate(attacker, 0, 500);
				minion.getAI().setIntention(Intention.ATTACK, attacker);
			}
			spawned++;
		}
		run.setMonsterTotal(run.getMonsterTotal() + spawned);
	}

	/**
	 * Death Curse: one more stack on the killer, and the clock starts again.
	 */
	private static void curse(MapRun run, Player player)
	{
		final long[] curse = run.getCurses().computeIfAbsent(player.getObjectId(), _ -> new long[2]);
		final int stacks;
		synchronized (curse)
		{
			curse[0] = Math.min(CURSE_MAX_STACKS, curse[0] + 1);
			curse[1] = System.currentTimeMillis() + (CURSE_SECONDS * 1000L);
			stacks = (int) curse[0];
		}
		applyCurse(run, player, stacks);
		player.sendMessage("Death Curse: you are " + Math.round(stacks * CURSE_PER_STACK * 100) + "% weaker for " + CURSE_SECONDS + " seconds.");
	}

	private static void applyCurse(MapRun run, Player player, int stacks)
	{
		final Object owner = run.getCurseFuncOwner();
		player.removeStatsOwner(owner);
		if (stacks > 0)
		{
			final List<AbstractFunction> functions = new ArrayList<>();
			for (Stat stat : CURSED_STATS)
			{
				mul(functions, stat, 1.0 - (stacks * CURSE_PER_STACK), owner);
			}
			player.addStatFuncs(functions);
		}
		player.broadcastUserInfo();
	}

	/**
	 * Death Curse: lifts the curses whose time is up.
	 */
	private static void liftCurses(MapRun run, long now)
	{
		for (Map.Entry<Integer, long[]> entry : run.getCurses().entrySet())
		{
			if (now < entry.getValue()[1])
			{
				continue;
			}
			run.getCurses().remove(entry.getKey());
			final Player player = World.getInstance().getPlayer(entry.getKey());
			if (player != null)
			{
				applyCurse(run, player, 0);
				player.sendMessage("The Death Curse fades.");
			}
		}
	}

	/**
	 * Bounty Mark: when there is no bounty (or it ran out), another monster is marked, and the players are told where it is.
	 */
	private static void updateBounty(MapRun run, List<Player> players, long now)
	{
		final int bountyId = run.getBountyId();
		Monster marked = null;
		if (bountyId != 0)
		{
			final WorldObject object = World.getInstance().findObject(bountyId);
			marked = (object != null) && object.isMonster() && !((Monster) object).isDead() ? (Monster) object : null;
			if ((marked != null) && (now <= run.getBountyUntil()))
			{
				return; // Still running.
			}
			if (marked != null)
			{
				marked.setEventTitleTag(marked.getVariables().getString(BOUNTY_TAG_VAR, null));
				sendScreenToMap(run, "The bounty on the " + marked.getName() + " has run out.");
			}
			run.setBounty(0, 0);
		}

		final List<Integer> alive = new ArrayList<>(run.getMonsters());
		alive.remove(Integer.valueOf(bountyId)); // Another one.
		Collections.shuffle(alive);
		for (int objectId : alive)
		{
			final WorldObject object = World.getInstance().findObject(objectId);
			if ((object == null) || !object.isMonster() || ((Monster) object).isDead() || ((Monster) object).getVariables().getBoolean(GUARDIAN_VAR, false))
			{
				continue;
			}
			final Monster target = (Monster) object;
			run.setBounty(objectId, now + (BOUNTY_SECONDS * 1000L));
			final String tag = target.getVariables().getString(EVENT_TITLE_TAG_VAR, null);
			if (tag != null)
			{
				target.getVariables().set(BOUNTY_TAG_VAR, tag); // Back on if the bounty runs out.
			}
			target.setEventTitleTag("[Bounty]");
			for (Player player : players)
			{
				player.sendPacket(new ExShowScreenMessage("Bounty on a " + target.getName() + ": " + describeWay(player, target) + ". " + (BOUNTY_SECONDS / 60) + " minutes!", 5000));
			}
			return;
		}
	}

	/**
	 * @return where a creature is from a player, e.g. "1,200 to the north-east"
	 */
	private static String describeWay(Player player, Creature target)
	{
		final int dx = target.getX() - player.getX();
		final int dy = target.getY() - player.getY();
		final String[] directions =
		{
			"east",
			"south-east",
			"south",
			"south-west",
			"west",
			"north-west",
			"north",
			"north-east"
		};
		final int sector = (int) Math.round(Math.toDegrees(Math.atan2(dy, dx)) / 45.0) & 7; // y grows to the south.
		return String.format("%,d to the %s", (int) Math.hypot(dx, dy), directions[sector]);
	}

	/**
	 * Ambush: a pack springs on one of the players inside.
	 */
	private void ambush(MapRun run)
	{
		if (run.isClosed())
		{
			return;
		}
		final List<Player> players = getPlayersInside(run);
		players.removeIf(Player::isDead);
		final List<NpcTemplate> pool = run.getMonsterKinds().isEmpty() ? getMonsterPool(run.getTier()) : run.getMonsterKinds();
		if (players.isEmpty() || pool.isEmpty())
		{
			checkCompleted(run);
			return;
		}

		final Player target = players.get(Rnd.get(players.size()));
		final GeoEngine geo = GeoEngine.getInstance();
		for (int i = 0; i < AMBUSH_SIZE; i++)
		{
			final double angle = Rnd.nextDouble() * 2 * Math.PI;
			final int distance = Rnd.get(350, 650);
			final int x = target.getX() + (int) (Math.cos(angle) * distance);
			final int y = target.getY() + (int) (Math.sin(angle) * distance);
			final Location spot = geo.getValidLocation(target.getX(), target.getY(), target.getZ(), x, y, geo.getHeight(x, y, target.getZ()), run.getInstanceId());
			final Monster monster = spawnMapMonster(run, pool.get(Rnd.get(pool.size())), spot);
			if (monster != null)
			{
				applyVariants(run, monster, false);
				monster.setEventTitleTag("[Ambush]");
				monster.addDamageHate(target, 0, 1000);
				monster.getAI().setIntention(Intention.ATTACK, target);
			}
		}
		run.setMonsterTotal(run.getMonsterTotal() + AMBUSH_SIZE);
		sendScreenToMap(run, "Ambush! The monsters close in on " + target.getName() + "!");
	}

	private void checkCompleted(MapRun run)
	{
		if (run.getMonsters().isEmpty() && (run.getStatus() == Status.OPEN))
		{
			if (run.isUnique() && UniqueMapManager.getInstance().onFieldCleared(run))
			{
				return;
			}
			completeRun(run, null, null);
		}
	}

	void completeRun(MapRun run, Monster lastKill, Player player)
	{
		synchronized (run)
		{
			if (run.getStatus() != Status.OPEN)
			{
				return;
			}
			run.setStatus(Status.COMPLETED);
			run.setCompletedAt(System.currentTimeMillis());
		}

		sendScreenToMap(run, run.isUnique() ? run.getUnique().name() + " is conquered!" : "Map complete!");
		sendToMap(run, "Every monster of the map is slain. The map collapses in " + MapDeviceConfig.COMPLETED_CLOSE_DELAY + " seconds.");
		if (MapDeviceConfig.COMPLETION_REWARD && (lastKill != null) && (player != null))
		{
			dropRunMap(run, lastKill, player, rollInMapTier(run));
		}
		UniqueMapManager.getInstance().onRunCompleted(run, lastKill, player);

		// Speedrun: cleared in time, a bonus map (within the map budget).
		if (run.isSpeedrunMade() && (lastKill != null) && (player != null))
		{
			sendScreenToMap(run, dropRunMap(run, lastKill, player, rollInMapTier(run)) ? "Speedrun! Cleared in time: a bonus map." : "Speedrun! Cleared in time, but the map has given all its maps.");
		}

		for (int objectId : run.getPlayersInside())
		{
			final Player inside = World.getInstance().getPlayer(objectId);
			if ((inside != null) && (inside.getInstanceId() == run.getInstanceId()))
			{
				MapQuestManager.getInstance().onMapCompleted(inside);
			}
		}

		final Player owner = World.getInstance().getPlayer(run.getOwnerId());
		if ((owner != null) && !run.getPlayersInside().contains(owner.getObjectId()))
		{
			owner.sendMessage("Your tier " + run.getTier().tier() + " map is complete.");
		}
		LOGGER.info(getClass().getSimpleName() + ": " + run.getOwnerName() + "'s tier " + run.getTier().tier() + " map (instance " + run.getInstanceId() + ") is complete.");
	}

	/**
	 * Rolls a map drop for a kill: in the open world from monsters of level {@code dropLevel} and up, a tier by their level and difficulty; in a map, its own tier or a little higher.
	 */
	private void rollMapDrop(Attackable victim, Player player, MapRun run)
	{
		if (victim.isFakePlayer() || victim.isPvpFakePlayer() || (victim instanceof Chest))
		{
			return;
		}
		if ((MapDeviceConfig.DROP_MAX_LEVEL_DIFFERENCE >= 0) && ((player.getLevel() - victim.getLevel()) > MapDeviceConfig.DROP_MAX_LEVEL_DIFFERENCE))
		{
			return;
		}

		final int baseTier = run != null ? run.getTier().tier() : getOpenWorldDropTier(victim);
		if (baseTier <= 0)
		{
			return; // Below the lowest drop level: the common case, so it is checked first.
		}
		
		final int tier;
		final double chance;
		if (run != null)
		{
			// A map's monsters drop MapDropsInMapAverage maps over a full clear, whatever the medals and drop bonuses: with the completion map, about 1.5, never more than MapDropsPerMapMax.
			tier = rollInMapTier(run);
			chance = (MapDeviceConfig.IN_MAP_EXPECTED_DROPS * 100.0) / Math.max(1, run.getMonsterTotal());
		}
		else
		{
			final double rateMultiplier = victim.isMonster() ? victim.asMonster().getCustomPassiveDropMultiplier() : 1.0;

			int bonus = 0;
			double baseChance = MapDeviceConfig.DROP_CHANCE;
			if (victim.isRaid())
			{
				bonus += MapDeviceData.getInstance().getRaidDifficulty();
				baseChance = MapDeviceConfig.DROP_CHANCE_RAID;
			}
			else if (victim.isMonster())
			{
				final Monster monster = victim.asMonster();
				final int championTier = monster.getChampionTier();
				if (championTier > 0)
				{
					bonus += MapDeviceData.getInstance().getChampionDifficulty(championTier);
					baseChance += championTier * MapDeviceConfig.DROP_CHANCE_PER_CHAMPION_TIER;
				}
				if (monster.isMageMonster() || monster.isThief() || monster.isInfused() || monster.isResonant() || monster.isWaveChallenge())
				{
					bonus += MapDeviceData.getInstance().getSpecialDifficulty();
				}
			}
			tier = Math.min(MapDeviceData.getInstance().getMaxTier(), baseTier + bonus);
			chance = baseChance * rateMultiplier;
		}

		if ((Rnd.nextDouble() * 100) < chance)
		{
			if (run != null)
			{
				dropRunMap(run, victim, player, tier);
			}
			else
			{
				dropMap(victim, player, tier);
			}
		}
	}

	/**
	 * @param victim a monster of the open world
	 * @return the tier it drops before its difficulty bonus: the highest whose drop level its level reaches and whose lowest strength its strength reaches; 0 for none
	 */
	private static int getOpenWorldDropTier(Attackable victim)
	{
		final MapDeviceData data = MapDeviceData.getInstance();
		if (data.getDropTier(victim.getLevel(), Double.MAX_VALUE) <= 0)
		{
			return 0; // Below every drop level: no need to score it.
		}
		return data.getDropTier(victim.getLevel(), MonsterStrength.getScore(victim.getTemplate()));
	}

	/**
	 * @param run a run
	 * @return a tier its monsters drop: its own, sometimes one or two higher
	 */
	static int rollInMapTier(MapRun run)
	{
		final int roll = Rnd.get(100);
		int tier = run.getTier().tier();
		if (roll < MapDeviceConfig.IN_MAP_TIER_UP_TWO_CHANCE)
		{
			tier += 2;
		}
		else if (roll < (MapDeviceConfig.IN_MAP_TIER_UP_TWO_CHANCE + MapDeviceConfig.IN_MAP_TIER_UP_CHANCE))
		{
			tier += 1;
		}
		return Math.min(MapDeviceData.getInstance().getMaxTier(), tier);
	}

	static void dropMap(Attackable victim, Player player, int tierId)
	{
		final MapTier tier = MapDeviceData.getInstance().getTier(tierId);
		if ((tier == null) || (ItemData.getInstance().getTemplate(tier.itemId()) == null))
		{
			return;
		}
		victim.dropOrAutoLoot(player, new ItemHolder(tier.itemId(), 1));
		player.sendMessage(victim.getName() + " left behind a " + getItemName(tier.itemId()) + "!");
	}

	// =========================================================================
	// The run's clock
	// =========================================================================

	/**
	 * Once a second: the map's timers (Storm Caller, Burning Ground, the hot zone drain), the hot zone buff, and when the map closes.
	 */
	private class RunTick implements Runnable
	{
		private final MapRun _run;
		private int _tick;
		private long _noPortalsSince;
		private boolean _warned5;
		private boolean _warned1;
		private boolean _speedrunWarned5;
		private boolean _speedrunWarned1;

		RunTick(MapRun run)
		{
			_run = run;
		}

		@Override
		public void run()
		{
			try
			{
				tick();
			}
			catch (Exception e)
			{
				LOGGER.log(Level.WARNING, MapDeviceManager.class.getSimpleName() + ": Map tick failed for instance " + _run.getInstanceId() + ".", e);
			}
		}

		private void tick()
		{
			if (_run.isClosed())
			{
				return;
			}
			_tick++;
			final long now = System.currentTimeMillis();

			// The map collapses: its time is up, or it was completed a while ago.
			if (now >= _run.getClosesAt())
			{
				closeRun(_run, "The map collapses: its time is up.");
				return;
			}
			if ((_run.getStatus() == Status.COMPLETED) && ((now - _run.getCompletedAt()) >= (MapDeviceConfig.COMPLETED_CLOSE_DELAY * 1000L)))
			{
				closeRun(_run, "The completed map collapses.");
				return;
			}
			final Instance instance = InstanceManager.getInstance().getInstance(_run.getInstanceId());
			if (instance == null)
			{
				closeRun(_run, null);
				return;
			}
			if (((_tick % 5) == 0) && (_run.getArena().source() != null) && !_run.getArena().source().doors().isEmpty())
			{
				TeamVsTeamManager.keepDoors(_run.getArena().source(), instance); // A door opened or shut by anything else goes back.
			}

			final List<Player> players = getPlayersInside(_run);

			// Every portal used and nobody left inside: nobody can come back.
			if (_run.getPortals().isEmpty() && players.isEmpty())
			{
				if (_noPortalsSince == 0)
				{
					_noPortalsSince = now;
				}
				else if ((now - _noPortalsSince) >= (NO_PORTALS_CLOSE_DELAY * 1000L))
				{
					closeRun(_run, "Every portal is used and nobody is left inside: the map collapses.");
					return;
				}
			}
			else
			{
				_noPortalsSince = 0;
			}

			final int remaining = _run.getRemainingSeconds();
			if (!_warned5 && (remaining <= 300) && (remaining > 60))
			{
				_warned5 = true;
				sendScreenToMap(_run, "The map collapses in 5 minutes.");
			}
			if (!_warned1 && (remaining <= 60))
			{
				_warned1 = true;
				sendScreenToMap(_run, "The map collapses in 1 minute!");
			}

			if (!_run.getCurses().isEmpty())
			{
				liftCurses(_run, now);
			}

			if (_run.isUnique())
			{
				UniqueMapManager.getInstance().onTick(_run, _tick, now);
				if (_run.isClosed())
				{
					return;
				}
			}

			// Speedrun: its clock.
			if ((_run.getSpeedrunMinutes() > 0) && !_run.isSpeedrunOver() && (_run.getStatus() == Status.OPEN))
			{
				final long left = ((_run.getOpenedAt() + (_run.getSpeedrunMinutes() * 60000L)) - now) / 1000;
				if (left <= 0)
				{
					_run.setSpeedrunOver(true);
					sendScreenToMap(_run, "Speedrun failed: the time is up.");
				}
				else if (!_speedrunWarned5 && (left <= 300) && (left > 60))
				{
					_speedrunWarned5 = true;
					sendScreenToMap(_run, "Speedrun: 5 minutes left to clear the map!");
				}
				else if (!_speedrunWarned1 && (left <= 60))
				{
					_speedrunWarned1 = true;
					sendScreenToMap(_run, "Speedrun: 1 minute left to clear the map!");
				}
			}

			if (players.isEmpty())
			{
				return;
			}

			if (_run.isBounty() && (_run.getStatus() == Status.OPEN))
			{
				updateBounty(_run, players, now);
			}

			final HotzoneModifier hotzone = _run.getHotzoneEffect();
			if ((hotzone != null) && ((_tick % 10) == 0))
			{
				for (Player player : players)
				{
					applyHotzoneBuff(player, hotzone); // Back on if a death or a cleanse took it.
				}
			}
			if ((hotzone != null) && (hotzone.getPlayerHpDrainPctPerTick() > 0) && ((_tick % (HotzoneModifierManager.PLAYER_DRAIN_INTERVAL_MS / 1000)) == 0))
			{
				hurt(players, hotzone.getPlayerHpDrainPctPerTick() / 100.0, null);
			}
			if (_run.isBurningGround() && ((_tick % BURNING_INTERVAL) == 0))
			{
				hurt(players, BURNING_DAMAGE, null);
			}
			if (_run.isStorm() && ((_tick % STORM_INTERVAL) == 0))
			{
				final List<Player> alive = new ArrayList<>(players);
				alive.removeIf(Player::isDead);
				if (!alive.isEmpty())
				{
					final Player struck = alive.get(Rnd.get(alive.size()));
					struck.broadcastPacket(new MagicSkillUse(struck, struck, STORM_SKILL_ID, 1, 0, 0));
					hurt(List.of(struck), STORM_DAMAGE, "Lightning strikes you!");
				}
			}
		}
	}

	static void hurt(Collection<Player> players, double share, String message)
	{
		for (Player player : players)
		{
			if (player.isDead() || player.isInvul())
			{
				continue;
			}
			player.reduceCurrentHp(player.getMaxHp() * share, null, false, false, null);
			if (message != null)
			{
				player.sendMessage(message);
			}
		}
	}

	/**
	 * Tears a run down: everyone inside goes back to the hideout, the portals vanish, the fence and the instance go away.
	 * @param run the run
	 * @param message what the players inside and the owner are told, {@code null} for nothing
	 */
	public void closeRun(MapRun run, String message)
	{
		synchronized (run)
		{
			if (run.isClosed())
			{
				return;
			}
			run.setStatus(Status.CLOSED);
		}

		try
		{
			if (run.getTickTask() != null)
			{
				run.getTickTask().cancel(false);
			}

			for (Player player : getPlayersInside(run))
			{
				if (message != null)
				{
					player.sendMessage(message);
				}
				if (player.isDead())
				{
					player.doRevive();
				}
				sendOut(player, run);
			}

			for (Npc portal : run.getPortals())
			{
				portal.deleteMe();
			}
			run.getPortals().clear();
			if (run.getReturnPortal() != null)
			{
				run.getReturnPortal().deleteMe();
			}
			TeamVsTeamBarrier.remove(run.getFences());
		}
		finally
		{
			if (run.isUnique())
			{
				UniqueMapManager.getInstance().onRunClosed(run);
			}
			RUNS_BY_INSTANCE.remove(run.getInstanceId());
			HotzoneModifierManager.getInstance().clearInstanceModifier(run.getInstanceId());
			InstanceManager.getInstance().destroyInstance(run.getInstanceId());
			final Hideout hideout = run.getHideout();
			if ((hideout != null) && (hideout.getRun() == run))
			{
				hideout.setRun(null);
			}
		}

		final Player owner = World.getInstance().getPlayer(run.getOwnerId());
		if ((owner != null) && (message != null) && !run.getPlayersInside().contains(owner.getObjectId()))
		{
			owner.sendMessage("Your map: " + message);
		}
	}

	// =========================================================================
	// Logging in and out
	// =========================================================================

	private static void saveReturnLocation(Player player)
	{
		final PlayerVariables vars = player.getVariables();
		vars.set(RETURN_LOC_VAR, player.getX() + ";" + player.getY() + ";" + player.getZ() + ";" + player.getHeading());
		vars.saveNow();
	}

	/**
	 * @param player a player
	 * @return where they entered their hideout from, or the nearest town if that is lost
	 */
	public static Location getReturnLocation(Player player)
	{
		final String saved = player.getVariables().getString(RETURN_LOC_VAR, "");
		if (!saved.isEmpty())
		{
			try
			{
				final String[] parts = saved.split(";");
				return new Location(Integer.parseInt(parts[0]), Integer.parseInt(parts[1]), Integer.parseInt(parts[2]), parts.length > 3 ? Integer.parseInt(parts[3]) : 0);
			}
			catch (Exception e)
			{
				// Fall through to town.
			}
		}
		return MapRegionData.getInstance().getTeleToLocation(player, TeleportWhereType.TOWN);
	}

	/**
	 * Character selection, before the player enters the world: one whose hideout or map is gone (or who won't be put back in it) is moved to where they entered from, instead of standing at the hideout's or the map's coordinates in the open world.
	 */
	private void onPlayerSelect(Player player)
	{
		if (!player.getVariables().hasVariable(RETURN_LOC_VAR))
		{
			return;
		}

		final int instanceId = InstanceManager.getInstance().getPlayer(player.getObjectId());
		final boolean restored = org.l2jmobius.gameserver.config.GeneralConfig.RESTORE_PLAYER_INSTANCE && (instanceId > 0) && (isHideoutInstance(instanceId) || isMapInstance(instanceId));
		if (restored)
		{
			return;
		}

		final Location loc = getReturnLocation(player);
		player.setXYZ(loc.getX(), loc.getY(), loc.getZ());
		player.getVariables().remove(RETURN_LOC_VAR);
		player.getVariables().saveNow();
	}

	/**
	 * Safety net if selection could not move the player.
	 */
	private void onPlayerLogin(Player player)
	{
		if (!player.getVariables().hasVariable(RETURN_LOC_VAR))
		{
			return;
		}
		final int instanceId = player.getInstanceId();
		if (isHideoutInstance(instanceId) || isMapInstance(instanceId))
		{
			return;
		}
		final Location loc = getReturnLocation(player);
		player.getVariables().remove(RETURN_LOC_VAR);
		player.teleToLocation(loc.getX(), loc.getY(), loc.getZ(), loc.getHeading(), 0);
	}

	// =========================================================================
	// Helpers
	// =========================================================================

	/**
	 * @param location a spot
	 * @return the same spot on the ground
	 */
	static Location getGroundLocation(Location location)
	{
		return new Location(location.getX(), location.getY(), GeoEngine.getInstance().getHeight(location.getX(), location.getY(), location.getZ()), location.getHeading());
	}

	/**
	 * @param itemId an item
	 * @return its name
	 */
	public static String getItemName(int itemId)
	{
		final org.l2jmobius.gameserver.model.item.ItemTemplate item = ItemData.getInstance().getTemplate(itemId);
		return item != null ? item.getName() : "Item " + itemId;
	}

	/**
	 * @param run a run
	 * @return the name of its hot zone effect, "None" for none
	 */
	public static String getHotzoneEffectName(MapRun run)
	{
		return run.getHotzoneEffect() != null ? ClassTransferChallengeManager.formatOmen(run.getHotzoneEffect()) : "None";
	}

	public static MapDeviceManager getInstance()
	{
		return SingletonHolder.INSTANCE;
	}

	private static class SingletonHolder
	{
		protected static final MapDeviceManager INSTANCE = new MapDeviceManager();
	}
}
