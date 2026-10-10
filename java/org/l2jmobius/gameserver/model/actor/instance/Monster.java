package org.l2jmobius.gameserver.model.actor.instance;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

import org.l2jmobius.commons.threads.ThreadPool;
import org.l2jmobius.commons.util.Rnd;
import org.l2jmobius.gameserver.ai.AttackableAI;
import org.l2jmobius.gameserver.ai.Intention;
import org.l2jmobius.gameserver.config.NpcConfig;
import org.l2jmobius.gameserver.config.RatesConfig;
import org.l2jmobius.gameserver.config.custom.ChampionMonstersConfig;
import org.l2jmobius.gameserver.config.custom.FakePlayersConfig;
import org.l2jmobius.gameserver.config.custom.HotzoneMinibossConfig;
import org.l2jmobius.gameserver.config.custom.InfusedMonsterConfig;
import org.l2jmobius.gameserver.config.custom.MageMonsterConfig;
import org.l2jmobius.gameserver.config.custom.MonsterRageConfig;
import org.l2jmobius.gameserver.config.custom.NightCycleConfig;
import org.l2jmobius.gameserver.config.custom.ResonantMonsterConfig;
import org.l2jmobius.gameserver.config.custom.ThiefMonsterConfig;
import org.l2jmobius.gameserver.config.custom.WaveChallengeConfig;
import org.l2jmobius.gameserver.data.custom.CustomSkillPoolData;
import org.l2jmobius.gameserver.data.custom.CustomSkillPoolData.CustomSkill;
import org.l2jmobius.gameserver.data.xml.SkillData;
import org.l2jmobius.gameserver.geoengine.GeoEngine;
import org.l2jmobius.gameserver.managers.ClassTransferChallengeManager;
import org.l2jmobius.gameserver.managers.FakeClanManager;
import org.l2jmobius.gameserver.managers.FakePartyManager;
import org.l2jmobius.gameserver.managers.HotzoneModifierManager;
import org.l2jmobius.gameserver.managers.NightCycleManager;
import org.l2jmobius.gameserver.model.Location;
import org.l2jmobius.gameserver.model.World;
import org.l2jmobius.gameserver.model.WorldObject;
import org.l2jmobius.gameserver.model.actor.Attackable;
import org.l2jmobius.gameserver.model.actor.Creature;
import org.l2jmobius.gameserver.model.actor.Player;
import org.l2jmobius.gameserver.model.actor.enums.creature.InstanceType;
import org.l2jmobius.gameserver.model.actor.holders.npc.MinionList;
import org.l2jmobius.gameserver.model.actor.templates.NpcTemplate;
import org.l2jmobius.gameserver.model.effects.EffectFlag;
import org.l2jmobius.gameserver.model.effects.EffectType;
import org.l2jmobius.gameserver.model.hotzone.HotzoneModifier;
import org.l2jmobius.gameserver.model.item.holders.Elementals;
import org.l2jmobius.gameserver.model.skill.AbnormalVisualEffect;
import org.l2jmobius.gameserver.model.skill.Skill;
import org.l2jmobius.gameserver.model.skill.enums.SkillFinishType;
import org.l2jmobius.gameserver.model.stats.Stat;
import org.l2jmobius.gameserver.model.stats.functions.AbstractFunction;
import org.l2jmobius.gameserver.model.stats.functions.FuncAdd;
import org.l2jmobius.gameserver.model.stats.functions.FuncMul;
import org.l2jmobius.gameserver.network.NpcStringId;
import org.l2jmobius.gameserver.network.enums.ChatType;
import org.l2jmobius.gameserver.network.serverpackets.ExShowScreenMessage;
import org.l2jmobius.gameserver.network.serverpackets.MagicSkillUse;
import org.l2jmobius.gameserver.util.LocationUtil;

public class Monster extends Attackable
{
	protected boolean _enableMinions = true;
	
	private Monster _master = null;
	private MinionList _minionList = null;
	private ScheduledFuture<?> _arenaEnrageTask = null;
	private ScheduledFuture<?> _arenaWaveReminderTask;
	private double championHpMult;
	
	// Owns every stat Func a rage adds, so removeStatsOwner() strips them all at once.
	private static final Object RAGE_FUNC_OWNER = new Object();
	private final AtomicInteger _rageDisables = new AtomicInteger();
	private volatile boolean _raging = false;
	private ScheduledFuture<?> _rageTask = null;

	// A fleeing monster runs away from the nearest player and gets away if nobody catches it in time: a THIEVES_DEN Thief with a full bag, or the soul of a Resonant monster.
	private static final int THIEF_ESCAPE_TIME_MS = 60000;
	private static final int ESCAPE_STEP_MS = 3000;
	private static final int ESCAPE_DISTANCE = 600;
	private static final int ESCAPE_SCAN_RANGE = 1500;
	private volatile ScheduledFuture<?> _escapeTask = null;
	private volatile long _escapeEndsAt;
	/** What the fleeing monster shouts when it gets away. */
	private volatile String _escapeGoneText;
	/** Thieves' Night: the players who see the fleeing Thief on their radar (object id -> the marker shown). */
	private final Map<Integer, Location> _thiefRadarMarkers = new ConcurrentHashMap<>();
	private volatile boolean _thiefRadar;

	// METAMORPHOSIS hotzone modifier: the retail polymorph shouts (see ai.others.PolymorphingOnAttack), one row per champion tier reached.
	private static final NpcStringId[][] METAMORPHOSIS_TEXTS =
	{
		{
			NpcStringId.ENOUGH_FOOLING_AROUND_GET_READY_TO_DIE,
			NpcStringId.YOU_IDIOT_I_VE_JUST_BEEN_TOYING_WITH_YOU,
			NpcStringId.NOW_THE_FUN_STARTS
		},
		{
			NpcStringId.I_MUST_ADMIT_NO_ONE_MAKES_MY_BLOOD_BOIL_QUITE_LIKE_YOU_DO,
			NpcStringId.NOW_THE_BATTLE_BEGINS,
			NpcStringId.WITNESS_MY_TRUE_POWER
		},
		{
			NpcStringId.PREPARE_TO_DIE,
			NpcStringId.I_LL_DOUBLE_MY_STRENGTH,
			NpcStringId.YOU_HAVE_MORE_SKILL_THAN_I_THOUGHT
		}
	};
	/** Set once a monster rolled its METAMORPHOSIS chance, so it only ever rolls once per life. */
	private static final String METAMORPH_ROLLED_VAR = "HOTZONE_METAMORPH_ROLLED";
	/** Set on a monster that evolved under METAMORPHOSIS: it is worth double XP/SP. */
	private static final String EVOLVED_VAR = "HOTZONE_EVOLVED";
	/** Set on the monster a BOUNTY_HUNT zone currently has marked. */
	private static final String BOUNTY_VAR = "HOTZONE_BOUNTY";
	/** Aggro range given to a monster with none of its own, before a HORNETS_NEST-style multiplier. */
	private static final int FORCED_AGGRO_RANGE = 300;

	// Random passives (and their visual) granted to this object. Kept outside getVariables() because Spawn.initializeNpc() wipes the variables before a
	// respawn's onSpawn() runs - the ids stored there were lost, so clearRandomPassiveSkills() couldn't remove the previous life's passives and they piled up.
	private final List<Integer> _randomPassiveSkillIds = new ArrayList<>();
	private AbnormalVisualEffect _randomPassiveAve = null;

	// Infused and Resonant monsters (see InfusedMonsterManager, ResonantMonsterManager). Kept outside getVariables() for the same reason as the random passives: onSpawn() must strip the
	// previous life's stat Funcs and visuals before the spawn rolls run again.
	private static final Object INFUSED_FUNC_OWNER = new Object();
	private volatile byte _infusedElement = Elementals.NONE;
	private AbnormalVisualEffect _infusedAve = null;
	private volatile ScheduledFuture<?> _infusedNovaTask = null;
	/** When the next nova may start (an attackable can't start a cast while it moves, so the task checks often and casts once this time has come). */
	private volatile long _infusedNextNovaAt;
	private static final int INFUSED_NOVA_CHECK_MS = 2000;
	private volatile boolean _resonant = false;
	/** Set once a Resonant monster's soul tried to flee, so it only ever flees once per life. */
	private volatile boolean _resonantFled = false;
	private AbnormalVisualEffect _resonantAve = null;

	public Monster(NpcTemplate template)
	{
		super(template);
		setInstanceType(InstanceType.Monster);
		setAutoAttackable(true);
	}
	
	@Override
	public boolean isAutoAttackable(Creature attacker)
	{
		if (isFakePlayer())
		{
			// A member of the attacker's party.
			if (FakePartyManager.getInstance().isSameGroup(this, attacker))
			{
				return false;
			}
			
			// Like a player: at war with the attacker's clan (both clans declared it) it is, of its clan or alliance it isn't.
			if (FakeClanManager.getInstance().isWarEnemy(this, attacker))
			{
				return true;
			}
			if (FakeClanManager.getInstance().isFriend(this, attacker) && (getKarma() <= 0))
			{
				return false;
			}
			
			// Like a player: attackable without Ctrl while fighting, while flagged, or with karma (a PK).
			return FakePlayersConfig.FAKE_PLAYER_AUTO_ATTACKABLE || isInCombat() || attacker.isMonster() || (getScriptValue() > 0) || (getKarma() > 0);
		}
		
		if (NpcConfig.GUARD_ATTACK_AGGRO_MOB && isAggressive() && (attacker instanceof Guard))
		{
			return true;
		}
		
		if (attacker.isMonster())
		{
			return attacker.isFakePlayer();
		}
		
		if (!attacker.isPlayable() && !attacker.isAttackable() && !(attacker instanceof Trap) && !(attacker instanceof EffectPoint))
		{
			return false;
		}
		
		return super.isAutoAttackable(attacker);
	}
	
	@Override
	public boolean isAggressive()
	{
		if (isAffected(EffectFlag.PASSIVE))
		{
			return false;
		}

		if (getTemplate().isAggressive())
		{
			return true;
		}

		// HORNETS_NEST-style hotzone modifiers make every regular monster aggressive.
		final HotzoneModifier hotzoneModifier = getHotzoneStatModifier();
		return (hotzoneModifier != null) && hotzoneModifier.isForceAggressive() && !isFakePlayer() && !isQuestMonster() && !(this instanceof Chest);
	}

	@Override
	public int getAggroRange()
	{
		final int aggroRange = super.getAggroRange();
		if (hasAIValue("aggroRange"))
		{
			return aggroRange;
		}

		final HotzoneModifier hotzoneModifier = getHotzoneStatModifier();
		if ((hotzoneModifier == null) || (!hotzoneModifier.isForceAggressive() && (hotzoneModifier.getAggroRangeMult() == 1.0)))
		{
			return aggroRange;
		}

		final int baseRange = (aggroRange <= 0) && hotzoneModifier.isForceAggressive() ? FORCED_AGGRO_RANGE : aggroRange;
		return (int) (baseRange * hotzoneModifier.getAggroRangeMult());
	}
	
	public double getCustomPassiveDropMultiplier()
	{
		// Formula: 1 + ((5.0 / 100) * passiveCount)
		final int passiveCount = getVariables().getInt("PASSIVE_COUNT", 0);
		double passiveMultiplier = 1.0 + ((RatesConfig.RANDOM_PASSIVE_DROP_PER_SKILL / 100.0) * passiveCount);
		if (isHotzoneMiniboss())
		{
			passiveMultiplier *= HotzoneMinibossConfig.DROP_MULTIPLIER;
		}

		final HotzoneModifier hotzoneModifier = getActiveHotzoneModifier();
		if (hotzoneModifier != null)
		{
			passiveMultiplier *= hotzoneModifier.getDropRateMult() * getHotzoneHeatRewardMultiplier(hotzoneModifier);

			// HAIR_TRIGGER: finishing a monster off while it rages pays more. The rage only ends after the drops (see doDie()).
			if (isRaging())
			{
				passiveMultiplier *= hotzoneModifier.getRageKillDropMult();
			}
		}

		if (isHotzoneSplit())
		{
			passiveMultiplier *= 0.5;
		}

		return passiveMultiplier;
	}

	/**
	 * Read at the SPOIL-only drop-chance call site in {@code NpcTemplate.calculateDrops()}, kept separate from {@link #getCustomPassiveDropMultiplier()} since a hotzone modifier like UNBREAKABLE means to double spoil specifically, not the regular kill-drop table too.
	 * @return the active hotzone modifier's spoil rate multiplier, or 1.0 if none is active
	 */
	public double getCustomSpoilMultiplier()
	{
		final HotzoneModifier hotzoneModifier = getActiveHotzoneModifier();
		return hotzoneModifier != null ? hotzoneModifier.getSpoilRateMult() : 1.0;
	}

	/**
	 * @return whichever {@link HotzoneModifier} is currently active for the hotzone this monster is standing in, or {@code null} if it isn't in one or that hotzone has no modifier rolled.
	 */
	private HotzoneModifier getActiveHotzoneModifier()
	{
		return HotzoneModifierManager.getInstance().getModifierFor(this);
	}
	
	/**
	 * @return the modifier whose monster stat changes (HP, attack, defence, speed) apply to this monster: none for a roaming fake player, which is no monster and gets the player side of the hotzone instead (see {@code custom.RotatingHotZones}). Loot still follows the zone.
	 */
	private HotzoneModifier getHotzoneStatModifier()
	{
		return isPvpFakePlayer() ? null : getActiveHotzoneModifier();
	}

	@Override
	public void onSpawn()
	{
		if (!isTeleporting() && (_master != null))
		{
			setRandomWalking(false);
			setIsRaidMinion(_master.isRaid());
			_master.getMinionList().onMinionSpawn(this);
		}
		
		super.onSpawn();

		// Every spawn starts calm with a clean stun/hold count.
		endRage(false);
		_rageDisables.set(0);

		// The respawn reuses this object: drop what the last life was infused or resonant with before the spawn rolls run again.
		clearInfused(false);
		clearResonant(false);

		// Raid bosses and grand bosses are already hand-tuned; skip the random passive pool for
		// them. isRaid() is checked here (before the arena branch below sets it for unrelated
		// reasons) because at this point it's only ever true for a genuine RaidBoss/GrandBoss -
		// their constructors set it before onSpawn() ever runs.
		// Roaming fake players only have the skills of their class.
		if (!isRaid() && !isPvpFakePlayer() && canRollRandomPassives())
		{
			addRandomPassiveSkill();
		}

		if (isArenaChallenger())
		{
			applyArenaBuffs();
			rerollArenaActiveSkills();
			getVariables().set("ARENA_WAVE", 0);
			startArenaWaveReminderTask();
			setIsRaid(true);
		}

		if (isHotzoneMiniboss())
		{
			// addRandomPassiveSkill() above only rebuilds the title itself when it actually
			// granted a passive - do it unconditionally here so the tag always shows.
			this.setCurrentHp(this.getMaxHp());
			rebuildFullTitle();
			broadcastInfo();
		}
	}

	/**
	 * Re-rolls which 1-3 active AI skills (from {@code npc_archetype_skills}, via the existing MonsterArchetype/AttackableAI system) this challenger actually knows and can cast in combat. Keeps its current archetype identity (AGGRESSIVE, MAGE, SUPPORTER...) so its general combat
	 * personality stays consistent - only WHICH specific skills within that archetype's eligible pool it was granted gets re-shuffled. {@link AttackableAI#setArchetype} already strips whatever was granted last time before granting a fresh set, so this is safe to call repeatedly without
	 * skills piling up.
	 */
	private void rerollArenaActiveSkills()
	{
		if (getAI() instanceof AttackableAI attackableAi)
		{
			attackableAi.setArchetype(attackableAi.getArchetype());
		}
	}
	
	private void addRandomPassiveSkill()
	{
		addRandomPassiveSkill(this.getLevel());
	}

	/**
	 * Class transfer challenge monsters are hand-tuned: every modifier they carry is chosen by the challenge and shown to the player, so the random passive pool stays out of them.
	 * @return {@code true} if this monster may roll random passive skills
	 */
	private boolean canRollRandomPassives()
	{
		return !ClassTransferChallengeManager.isChallengeInstance(getInstanceId());
	}
	
	// Random Passive skills
	
	private void addRandomPassiveSkill(int effectiveLevel)
	{
		if (!RatesConfig.RANDOM_PASSIVE_SKILLS_ENABLED)
		{
			return;
		}
		
		// 1. Force a complete wipe of all previous custom passives and visual effects
		clearRandomPassiveSkills();
		
		final int maxCap = Math.max(1, RatesConfig.RANDOM_PASSIVE_SKILLS_MAX_COUNT);
		
		// 2. We use the virtual level passed from onArenaWaveCleared to calculate the roll.
		// Since we overrode getLevel() to return 85+, we MUST use the effectiveLevel argument
		// passed in from the wave logic, not the mob's level.
		final double maxMonsterLevel = 90.0;
		final double levelRatio = Math.min(1.0, effectiveLevel / maxMonsterLevel);
		final double randomFactor = Rnd.get(0, 1000) / 500.0;
		final double weightedRoll = Math.min(maxCap, randomFactor * levelRatio);
		
		int maxSkillsToAssign = 1 + (int) Math.round(weightedRoll * (maxCap - 1 - (10 - Math.floor(Math.random() * (effectiveLevel / 8)))));
		maxSkillsToAssign = Math.max(1, Math.min(maxCap, maxSkillsToAssign));
		
		boolean isOverloaded = Rnd.get(100) < RatesConfig.RANDOM_PASSIVE_OVERLOAD_CHANCE;
		if (isOverloaded)
		{
			maxSkillsToAssign = maxCap;
		}
		
		int skillsSuccessfullyAdded = 0;
		int maxAttempts = maxSkillsToAssign * 5;
		int attempts = 0;
		final List<Integer> addedSkillIds = new ArrayList<>();
		
		while ((skillsSuccessfullyAdded < maxSkillsToAssign) && (attempts < maxAttempts))
		{
			attempts++;
			
			CustomSkill randomDbSkill = CustomSkillPoolData.getInstance().getRandomSkill();
			if (randomDbSkill == null)
			{
				break;
			}
			
			if (this.getKnownSkill(randomDbSkill.skillId) != null)
			{
				continue;
			}
			
			int calculatedLevel = (int) Math.round(levelRatio * randomDbSkill.maxLevel);
			int safeLevel = Math.max(1, Math.min(calculatedLevel, randomDbSkill.maxLevel));
			
			Skill randomPassive = SkillData.getInstance().getSkill(randomDbSkill.skillId, safeLevel);
			if (randomPassive != null)
			{
				this.addSkill(randomPassive);
				addedSkillIds.add(randomPassive.getId());
				skillsSuccessfullyAdded++;
			}
			
		}
		
		synchronized (_randomPassiveSkillIds)
		{
			_randomPassiveSkillIds.addAll(addedSkillIds);
		}

		if (skillsSuccessfullyAdded > 0)
		{
			this.getVariables().set("PASSIVE_COUNT", skillsSuccessfullyAdded);
			final String idsAsString = addedSkillIds.stream().map(String::valueOf).collect(Collectors.joining(","));
			this.getVariables().set("PASSIVE_SKILL_IDS", idsAsString);
			
			// Refill HP to match newly recalculated Max HP
			this.setCurrentHp(this.getMaxHp());
			
			AbnormalVisualEffect appliedAve = null;
			
			updateSkillCountTitle();
			
			if (isOverloaded || (skillsSuccessfullyAdded == RatesConfig.RANDOM_PASSIVE_SKILLS_MAX_COUNT))
			{
				appliedAve = AbnormalVisualEffect.INVINCIBILITY;
			}
			else if (skillsSuccessfullyAdded >= (RatesConfig.RANDOM_PASSIVE_SKILLS_MAX_COUNT * 0.8))
			{
				appliedAve = AbnormalVisualEffect.NAVIT_ADVENT;
			}
			else if (skillsSuccessfullyAdded >= (RatesConfig.RANDOM_PASSIVE_SKILLS_MAX_COUNT * 0.5))
			{
				appliedAve = AbnormalVisualEffect.VP_UP;
			}
			
			if (appliedAve != null)
			{
				this.startAbnormalVisualEffect(true, appliedAve);
				this.getVariables().set("PASSIVE_AVE", appliedAve.name());
				_randomPassiveAve = appliedAve;
			}
			this.setCurrentHp(this.getMaxHp());
			this.broadcastInfo();
		}
		getVariables().set("PASSIVE_TITLE_TAG", "[P:" + skillsSuccessfullyAdded + "]");
	}
	
	private void clearRandomPassiveSkills()
	{
		// Stop any visual effects applied from the previous wave
		final String previousAve = getVariables().getString("PASSIVE_AVE", "");
		if (!previousAve.isEmpty())
		{
			try
			{
				AbnormalVisualEffect ave = AbnormalVisualEffect.valueOf(previousAve);
				this.stopAbnormalVisualEffect(true, ave);
			}
			catch (IllegalArgumentException e)
			{
				// ignored
			}
			getVariables().remove("PASSIVE_AVE");
		}
		if (_randomPassiveAve != null)
		{
			this.stopAbnormalVisualEffect(true, _randomPassiveAve);
			_randomPassiveAve = null;
		}

		// Remove the actual skills from the monster's known skill list
		final List<Integer> oldIds;
		synchronized (_randomPassiveSkillIds)
		{
			oldIds = new ArrayList<>(_randomPassiveSkillIds);
			_randomPassiveSkillIds.clear();
		}
		for (int skillId : oldIds)
		{
			final Skill oldSkill = getKnownSkill(skillId);
			if (oldSkill != null)
			{
				this.removeSkill(oldSkill, true);
			}
		}
		
		getVariables().remove("PASSIVE_COUNT");
		getVariables().remove("PASSIVE_SKILL_IDS");
		
		this.setCurrentHp(this.getMaxHp());
		this.setCurrentMp(this.getMaxMp());
		rebuildFullTitle();
		this.broadcastInfo();
	}
	
	/**
	 * Rebuilds this NPC's title to show its current passive and active skill counts, preserving whatever base title/name suffix it originally had (minus any previous count tag we added).
	 */
	private void updateSkillCountTitle()
	{
		// getVariables().set("PASSIVE_TITLE_TAG", "[P:" + skillsSuccessfullyAdded + " / A:" + activeCount + "]");
		rebuildFullTitle();
	}
	
	/** A tag an event puts first in the name plate (a Nightlord's, see {@link org.l2jmobius.gameserver.managers.NightlordManager}). */
	private static final String EVENT_TITLE_TAG_VAR = "EVENT_TITLE_TAG";

	/**
	 * Puts {@code tag} first in this monster's name plate, or removes it. Set it after {@link #setChampionTier(int)}, whose own title rebuild doesn't know the tag.
	 * @param tag the tag, or {@code null} to remove it
	 */
	public void setEventTitleTag(String tag)
	{
		if ((tag == null) || tag.isEmpty())
		{
			getVariables().remove(EVENT_TITLE_TAG_VAR);
		}
		else
		{
			getVariables().set(EVENT_TITLE_TAG_VAR, tag);
		}
		rebuildFullTitle();
		broadcastInfo();
	}

	private void rebuildFullTitle()
	{
		final String championTag = getVariables().getString("CHAMPION_TITLE_TAG", "");
		final String passiveTag = getVariables().getString("PASSIVE_TITLE_TAG", "");
		final String baseTitle = getTemplate().getTitle() == null ? "" : getTemplate().getTitle();

		final StringBuilder sb = new StringBuilder();
		final String eventTag = getVariables().getString(EVENT_TITLE_TAG_VAR, "");
		if (!eventTag.isEmpty())
		{
			sb.append(eventTag).append(' ');
		}
		if (isArenaChallenger())
		{
			final int arenaWave = getVariables().getInt("ARENA_WAVE", 0);
			if (arenaWave > 0)
			{
				sb.append("Wave ").append(arenaWave).append(" -").append(' ');
			}
		}
		if (isRaging())
		{
			sb.append(MonsterRageConfig.TITLE_TAG).append(' ');
		}
		if (isThief())
		{
			sb.append(String.format(ThiefMonsterConfig.TITLE_TAG, getThiefPercent())).append(' ');
		}
		if (isMageMonster())
		{
			sb.append(MageMonsterConfig.TITLE_TAG).append(' ');
		}
		if (isInfused())
		{
			sb.append(String.format(InfusedMonsterConfig.TITLE_TAG, Elementals.getElementName(_infusedElement))).append(' ');
		}
		if (isResonant())
		{
			sb.append(ResonantMonsterConfig.TITLE_TAG).append(' ');
		}
		if (isWaveChallenge())
		{
			sb.append(String.format(WaveChallengeConfig.TITLE_TAG, getWaveChallengeWave(), getWaveChallengeTotal())).append(' ');
		}
		if (isHotzoneMiniboss())
		{
			sb.append(HotzoneMinibossConfig.TITLE_TAG).append(' ');
		}
		if (isHotzoneBounty())
		{
			sb.append("[Bounty]").append(' ');
		}
		if (!championTag.isEmpty())
		{
			sb.append(championTag).append(' ');
		}
		if (!passiveTag.isEmpty())
		{
			sb.append(passiveTag).append(' ');
		}
		sb.append(baseTitle);

		setTitle(sb.toString().trim());
	}

	// =======================================================================
	// Hotzone Miniboss System
	// =======================================================================

	/**
	 * @return {@code true} if {@link org.l2jmobius.gameserver.managers.HotZoneMinibossManager} spawned this specific instance as a hotzone miniboss. Drives the stat/reward multipliers below and the title tag in {@link #rebuildFullTitle()}.
	 */
	public boolean isHotzoneMiniboss()
	{
		return getVariables().getBoolean("IS_HOTZONE_MINIBOSS", false);
	}

	private double getHotzoneMinibossMultiplier()
	{
		return isHotzoneMiniboss() ? HotzoneMinibossConfig.STAT_MULTIPLIER : 1.0;
	}

	/** Set on the clone a RESTLESS_DEAD hotzone modifier raises from a slain monster (see {@link HotzoneModifierManager#onAttackableKilled}). */
	public static final String HOTZONE_RISEN_VAR = "IS_HOTZONE_RISEN";

	/**
	 * @return {@code true} if this monster was raised again by a RESTLESS_DEAD hotzone modifier - it is worth double XP/SP and never rises a second time.
	 */
	public boolean isHotzoneRisen()
	{
		return getVariables().getBoolean(HOTZONE_RISEN_VAR, false);
	}

	/**
	 * @return {@code true} if a RESTLESS_DEAD hotzone modifier may raise this monster again after it dies: plain monsters only - not raids, minions, hotzone minibosses, wave/arena challengers, thieves, or anything that already rose once.
	 */
	public boolean canHotzoneRise()
	{
		return !isRaid() && !isMinion() && !isHotzoneMiniboss() && !isHotzoneRisen() && !isHotzoneSplit() && !isWaveChallenge() && !isArenaChallenger() && !isThief();
	}

	/** Set on the two copies a SPLITTING_GROUND hotzone modifier splits a slain monster into (see {@link HotzoneModifierManager#onAttackableKilled}). */
	public static final String HOTZONE_SPLIT_VAR = "IS_HOTZONE_SPLIT";

	/**
	 * @return {@code true} if this monster is one of the copies a SPLITTING_GROUND hotzone modifier split a slain monster into - it is worth half the XP/SP and drops, and never rises or splits again.
	 */
	public boolean isHotzoneSplit()
	{
		return getVariables().getBoolean(HOTZONE_SPLIT_VAR, false);
	}

	/**
	 * @return {@code true} if this monster evolved under a METAMORPHOSIS hotzone modifier - it is worth double XP/SP.
	 */
	public boolean isHotzoneEvolved()
	{
		return getVariables().getBoolean(EVOLVED_VAR, false);
	}

	/**
	 * @return {@code true} while a BOUNTY_HUNT hotzone has this monster marked: its kill pays the bounty (see {@link HotzoneModifierManager}).
	 */
	public boolean isHotzoneBounty()
	{
		return getVariables().getBoolean(BOUNTY_VAR, false);
	}

	/**
	 * Marks or unmarks this monster as a BOUNTY_HUNT bounty, and shows it in its name plate.
	 * @param bounty {@code true} to mark it
	 */
	public void setHotzoneBounty(boolean bounty)
	{
		if (bounty)
		{
			getVariables().set(BOUNTY_VAR, true);
		}
		else
		{
			getVariables().remove(BOUNTY_VAR);
		}
		rebuildFullTitle();
		broadcastInfo();
	}

	/**
	 * RISING_HEAT: players earn more as the zone heats up.
	 * @param hotzoneModifier the modifier active where this monster stands
	 * @return the XP/SP and drop multiplier of the zone's Heat, 1.0 if the modifier builds none
	 */
	private double getHotzoneHeatRewardMultiplier(HotzoneModifier hotzoneModifier)
	{
		if (!hotzoneModifier.isHeat())
		{
			return 1.0;
		}
		return 1.0 + ((HotzoneModifierManager.getInstance().getHeatStacks(this) * HotzoneModifier.HEAT_REWARD_PCT_PER_STACK) / 100.0);
	}

	/**
	 * RISING_HEAT: monsters hit harder as the zone heats up.
	 * @param hotzoneModifier the modifier active where this monster stands, or {@code null}
	 * @return the P.Atk/M.Atk multiplier of the zone's Heat, 1.0 if there is none
	 */
	private double getHotzoneHeatAttackMultiplier(HotzoneModifier hotzoneModifier)
	{
		if ((hotzoneModifier == null) || !hotzoneModifier.isHeat())
		{
			return 1.0;
		}
		return 1.0 + ((HotzoneModifierManager.getInstance().getHeatStacks(this) * HotzoneModifier.HEAT_ATTACK_PCT_PER_STACK) / 100.0);
	}

	/**
	 * METAMORPHOSIS: the first time this monster drops below half HP, it may evolve one champion tier up and heal to full, with a retail polymorph shout.
	 */
	private void tryMetamorphosis()
	{
		if (isDead() || !ChampionMonstersConfig.CHAMPION_ENABLE || (getCurrentHp() >= (getMaxHp() * 0.5)) || getVariables().getBoolean(METAMORPH_ROLLED_VAR, false))
		{
			return;
		}

		final HotzoneModifier hotzoneModifier = getHotzoneStatModifier();
		if ((hotzoneModifier == null) || (hotzoneModifier.getMetamorphChancePct() <= 0))
		{
			return;
		}

		// Only plain monsters - the special kinds already have their own rules, and a tier 3 champion has nowhere left to go.
		if (isRaid() || isMinion() || isFakePlayer() || isQuestMonster() || (this instanceof Chest) || isHotzoneMiniboss() || isWaveChallenge() || isArenaChallenger() || isThief() || isMageMonster() || isInfused() || isResonant() || (getChampionTier() >= 3))
		{
			return;
		}

		getVariables().set(METAMORPH_ROLLED_VAR, true);
		if (Rnd.get(100) >= hotzoneModifier.getMetamorphChancePct())
		{
			return;
		}

		final int tier = getChampionTier() + 1;
		setChampionTier(tier);
		getVariables().set(EVOLVED_VAR, true);
		rebuildFullTitle();
		setCurrentHp(getMaxHp());
		setCurrentMp(getMaxMp());
		broadcastInfo();

		final NpcStringId[] texts = METAMORPHOSIS_TEXTS[tier - 1];
		broadcastSay(ChatType.NPC_GENERAL, texts[Rnd.get(texts.length)]);
	}

	/**
	 * Read next to the thief adena scaling in {@code NpcTemplate.calculateDrops()}.
	 * @return the active hotzone modifier's adena multiplier (GOLD_RUSH), or 1.0 if none is active
	 */
	public double getHotzoneAdenaMultiplier()
	{
		final HotzoneModifier hotzoneModifier = getActiveHotzoneModifier();
		return hotzoneModifier != null ? hotzoneModifier.getAdenaMult() : 1.0;
	}

	@Override
	public long getExpReward(int level)
	{
		double multiplier = isHotzoneMiniboss() ? HotzoneMinibossConfig.XP_SP_MULTIPLIER : 1.0;
		if (isHotzoneRisen())
		{
			multiplier *= 2;
		}
		if (isWaveChallenge())
		{
			multiplier *= WaveChallengeConfig.XP_MULTIPLIER;
		}
		multiplier *= getHotzoneVariantRewardMultiplier();
		final HotzoneModifier hotzoneModifier = getActiveHotzoneModifier();
		if (hotzoneModifier != null)
		{
			multiplier *= hotzoneModifier.getXpSpMult() * getHotzoneHeatRewardMultiplier(hotzoneModifier);
		}
		return (long) (super.getExpReward(level) * multiplier);
	}

	@Override
	public int getSpReward(int level)
	{
		double multiplier = isHotzoneMiniboss() ? HotzoneMinibossConfig.XP_SP_MULTIPLIER : 1.0;
		if (isHotzoneRisen())
		{
			multiplier *= 2;
		}
		if (isWaveChallenge())
		{
			multiplier *= WaveChallengeConfig.SP_MULTIPLIER;
		}
		multiplier *= getHotzoneVariantRewardMultiplier();
		final HotzoneModifier hotzoneModifier = getActiveHotzoneModifier();
		if (hotzoneModifier != null)
		{
			multiplier *= hotzoneModifier.getXpSpMult() * getHotzoneHeatRewardMultiplier(hotzoneModifier);
		}
		return (int) (super.getSpReward(level) * multiplier);
	}

	/**
	 * @return the XP/SP multiplier of a monster a hotzone modifier changed: x2 if it evolved (METAMORPHOSIS), x0.5 if it is a split copy (SPLITTING_GROUND)
	 */
	private double getHotzoneVariantRewardMultiplier()
	{
		double multiplier = isHotzoneEvolved() ? 2.0 : 1.0;
		if (isHotzoneSplit())
		{
			multiplier *= 0.5;
		}
		return multiplier;
	}

	// =======================================================================
	// Wave Challenge System (open world)
	// =======================================================================

	/**
	 * @return {@code true} if {@link org.l2jmobius.gameserver.managers.WaveChallengeManager} converted this spawn into an open-world wave challenge.
	 */
	public boolean isWaveChallenge()
	{
		return WaveChallengeConfig.ENABLED && getVariables().getBoolean("IS_WAVE_CHALLENGE", false);
	}

	/**
	 * @return the wave this challenge is currently on (1-based), or 0 if this isn't a wave challenge.
	 */
	public int getWaveChallengeWave()
	{
		return getVariables().getInt("WAVE_CHALLENGE_WAVE", 0);
	}

	/**
	 * @return {@code true} once the final wave has been defeated, i.e. this death is the one that pays out.
	 */
	public boolean isWaveChallengeFinalWave()
	{
		return isWaveChallenge() && (getWaveChallengeWave() >= getWaveChallengeTotal());
	}

	/**
	 * @return how many waves this challenge has: its own count when started with {@link #startWaveChallenge(int)}, otherwise the configured {@code WaveChallengeWaveCount}.
	 */
	public int getWaveChallengeTotal()
	{
		return getVariables().getInt("WAVE_CHALLENGE_TOTAL", WaveChallengeConfig.WAVE_COUNT);
	}

	/**
	 * Turns this freshly spawned monster into wave 1 of a wave challenge with its own number of waves (a class transfer challenge's multi-phase boss).
	 * @param totalWaves how many times it must be defeated
	 */
	public void startWaveChallenge(int totalWaves)
	{
		getVariables().set("WAVE_CHALLENGE_TOTAL", Math.max(1, totalWaves));
		startWaveChallenge();
	}

	/**
	 * Turns this freshly spawned monster into wave 1 of an open-world wave challenge. Called by {@link org.l2jmobius.gameserver.managers.WaveChallengeManager#tryConvert}.
	 */
	public void startWaveChallenge()
	{
		getVariables().set("IS_WAVE_CHALLENGE", true);
		getVariables().set("WAVE_CHALLENGE_WAVE", 1);

		if (WaveChallengeConfig.APPLY_ARENA_BUFFS)
		{
			applyArenaBuffs();
		}

		rebuildFullTitle();
		setCurrentHp(getMaxHp());
		setCurrentMp(getMaxMp());
		broadcastInfo();
	}

	/**
	 * Same compounding growth as the arena challenger, except wave 1 is the monster's base strength (arena wave 0).
	 */
	private double getWaveChallengeMultiplier(int growthPercent)
	{
		if (!isWaveChallenge())
		{
			return 1.0;
		}
		return Math.pow(1 + (growthPercent / 100.0), Math.max(0, getWaveChallengeWave() - 1));
	}

	private double getWaveChallengeStatMultiplier()
	{
		return getWaveChallengeMultiplier(WaveChallengeConfig.STAT_GROWTH_PER_WAVE);
	}

	private double getWaveChallengeOffenseMultiplier()
	{
		return getWaveChallengeMultiplier(WaveChallengeConfig.OFFENSE_GROWTH_PER_WAVE);
	}

	private double getWaveChallengeDefenseMultiplier()
	{
		return getWaveChallengeMultiplier(WaveChallengeConfig.DEFENSE_GROWTH_PER_WAVE);
	}

	/**
	 * A lethal blow before the final wave instead heals the monster and moves it to the next, stronger wave - the open-world counterpart of {@link #onArenaWaveCleared(Creature, double)}. Synchronized and re-checked so two simultaneous lethal hits (e.g. a DOT tick racing a skill) can't skip a wave.
	 * @param killer whoever landed the lethal blow
	 * @return {@code true} if the wave advanced (the death must be cancelled), {@code false} if this is the final wave and the monster should die normally
	 */
	private synchronized boolean advanceWaveChallenge(Creature killer)
	{
		if (isWaveChallengeFinalWave() || isDead())
		{
			return false;
		}

		final int wave = getWaveChallengeWave() + 1;
		final int totalWaves = getWaveChallengeTotal();
		getVariables().set("WAVE_CHALLENGE_WAVE", wave);

		if (WaveChallengeConfig.REROLL_PASSIVES_PER_WAVE && canRollRandomPassives())
		{
			addRandomPassiveSkill(getLevel()); // getLevel() already includes this wave's virtual levels
		}
		if (WaveChallengeConfig.APPLY_ARENA_BUFFS)
		{
			applyArenaBuffs();
		}
		rerollArenaActiveSkills();

		rebuildFullTitle();
		setCurrentHp(getMaxHp());
		setCurrentMp(getMaxMp());
		broadcastInfo();

		final String message = (wave >= totalWaves) ? "Final wave " + wave + "/" + totalWaves + "! Defeat " + getName() + " once more to claim the reward." : "Wave " + (wave - 1) + " cleared! " + getName() + " grows stronger... (wave " + wave + "/" + totalWaves + ")";
		sendMessageToAttackers(killer, message);
		return true;
	}

	/**
	 * Resets a partially cleared challenge back to wave 1 once nobody is fighting it any more, so players can't whittle it down across separate attempts.
	 */
	private void resetWaveChallenge()
	{
		getVariables().set("WAVE_CHALLENGE_WAVE", 1);

		if (WaveChallengeConfig.REROLL_PASSIVES_PER_WAVE && canRollRandomPassives())
		{
			addRandomPassiveSkill(getLevel());
		}

		rebuildFullTitle();
		setCurrentHp(getMaxHp());
		setCurrentMp(getMaxMp());
		broadcastInfo();
	}

	/**
	 * Sends {@code message} to every player on this monster's aggro list, plus the killer if they somehow aren't on it.
	 */
	private void sendMessageToAttackers(Creature killer, String message)
	{
		final List<Player> recipients = new ArrayList<>();
		for (Creature attacker : getAggroList().keySet())
		{
			final Player player = attacker.asPlayer();
			if ((player != null) && !recipients.contains(player))
			{
				recipients.add(player);
			}
		}

		final Player killerPlayer = killer != null ? killer.asPlayer() : null;
		if ((killerPlayer != null) && !recipients.contains(killerPlayer))
		{
			recipients.add(killerPlayer);
		}

		for (Player player : recipients)
		{
			player.sendMessage(message);
		}
	}

	@Override
	public void clearAggroList()
	{
		super.clearAggroList();

		// Everyone stopped fighting (leashed home, forgot its attackers, ...) - start over from wave 1.
		if (WaveChallengeConfig.RESET_ON_LEASH && isWaveChallenge() && !isDead() && (getWaveChallengeWave() > 1))
		{
			resetWaveChallenge();
		}
	}

	// =======================================================================
	// Thief Monster System
	// =======================================================================

	/**
	 * @return {@code true} if {@link org.l2jmobius.gameserver.managers.ThiefMonsterManager} turned this spawn into a Thief.
	 */
	public boolean isThief()
	{
		return ThiefMonsterConfig.ENABLED && getVariables().getBoolean("IS_THIEF", false);
	}

	/**
	 * @return how many nearby kills this Thief has stolen from, capped at {@link ThiefMonsterConfig#KILLS_FOR_MAX}
	 */
	public int getThiefKills()
	{
		return Math.min(getVariables().getInt("THIEF_KILLS", 0), ThiefMonsterConfig.KILLS_FOR_MAX);
	}

	/**
	 * @return how full this Thief's bag is, 0-100
	 */
	public int getThiefPercent()
	{
		return (getThiefKills() * 100) / ThiefMonsterConfig.KILLS_FOR_MAX;
	}

	/**
	 * @return the adena drop multiplier for this monster: 1.0 unless it is a Thief, otherwise linear from {@link ThiefMonsterConfig#MIN_ADENA_MULTIPLIER} at an empty bag to {@link ThiefMonsterConfig#MAX_ADENA_MULTIPLIER} at a full one
	 */
	public double getThiefAdenaMultiplier()
	{
		if (!isThief())
		{
			return 1.0;
		}

		final double progress = getThiefKills() / (double) ThiefMonsterConfig.KILLS_FOR_MAX;
		return ThiefMonsterConfig.MIN_ADENA_MULTIPLIER + ((ThiefMonsterConfig.MAX_ADENA_MULTIPLIER - ThiefMonsterConfig.MIN_ADENA_MULTIPLIER) * progress);
	}

	/**
	 * Turns this freshly spawned monster into a Thief with an empty bag. Called by {@link org.l2jmobius.gameserver.managers.ThiefMonsterManager#tryConvert}.
	 */
	public void startThief()
	{
		getVariables().set("IS_THIEF", true);
		getVariables().set("THIEF_KILLS", 0);
		rebuildFullTitle();
		broadcastInfo();
	}

	/**
	 * A monster died nearby: add one kill to the bag (up to {@link ThiefMonsterConfig#KILLS_FOR_MAX}) and refresh the name plate. Synchronized so two kills landing at once can't lose a count.
	 */
	public synchronized void addThiefKill()
	{
		if (!isThief() || isDead())
		{
			return;
		}

		final int kills = getVariables().getInt("THIEF_KILLS", 0);
		if (kills >= ThiefMonsterConfig.KILLS_FOR_MAX)
		{
			return;
		}

		// THIEVES_DEN-style hotzone modifiers fill the bag faster, and make a full Thief run for it.
		final HotzoneModifier hotzoneModifier = getHotzoneStatModifier();
		final int weight = hotzoneModifier != null ? Math.max(1, hotzoneModifier.getThiefKillWeight()) : 1;
		final int newKills = Math.min(ThiefMonsterConfig.KILLS_FOR_MAX, kills + weight);
		getVariables().set("THIEF_KILLS", newKills);
		rebuildFullTitle();
		broadcastInfo();

		// Thieves' Night: in the open world at night it runs off into the dark too.
		final boolean thievesNight = NightCycleManager.getInstance().isThievesNight(this);
		if ((newKills >= ThiefMonsterConfig.KILLS_FOR_MAX) && (((hotzoneModifier != null) && hotzoneModifier.isThiefFlees()) || thievesNight))
		{
			_thiefRadar = thievesNight;
			startThiefEscape();
		}
	}

	/**
	 * THIEVES_DEN: the Thief's bag is full, so it runs away from the nearest player and, if it is still alive after {@link #THIEF_ESCAPE_TIME_MS}, gets away with its loot.
	 */
	private void startThiefEscape()
	{
		startEscape(THIEF_ESCAPE_TIME_MS, _thiefRadar ? "My bag is full - the night will hide me!" : "My bag is full - so long, suckers!", "Ha! You'll never see this loot again!");
	}

	/**
	 * Makes this monster run away from the nearest player (the retail ai.others.FleeMonsters move) and, if it is still alive after {@code durationMs}, get away: it is removed, and its spawn respawns it as usual. Does nothing if it is already fleeing.
	 * @param durationMs how long it runs before it gets away
	 * @param startText what it shouts when it starts running
	 * @param goneText what it shouts when it gets away
	 */
	private synchronized void startEscape(long durationMs, String startText, String goneText)
	{
		if ((_escapeTask != null) || isDead())
		{
			return;
		}

		_escapeEndsAt = System.currentTimeMillis() + durationMs;
		_escapeGoneText = goneText;
		broadcastSay(ChatType.NPC_GENERAL, startText);
		disableCoreAI(true);
		setRunning();
		_escapeTask = ThreadPool.scheduleAtFixedRate(this::escapeStep, 0, ESCAPE_STEP_MS);
	}

	private void escapeStep()
	{
		if (isDead() || !isSpawned())
		{
			stopEscape();
			return;
		}

		if (System.currentTimeMillis() >= _escapeEndsAt)
		{
			stopEscape();
			broadcastSay(ChatType.NPC_GENERAL, _escapeGoneText);
			deleteMe(); // Npc.onDecay() hands the spawn back, which respawns it after its normal delay.
			return;
		}

		// Away from the nearest player, or any direction if nobody is chasing it.
		Player nearest = null;
		double nearestDistance = Double.MAX_VALUE;
		for (Player player : World.getInstance().getVisibleObjectsInRange(this, Player.class, ESCAPE_SCAN_RANGE))
		{
			final double distance = calculateDistance2D(player);
			if (distance < nearestDistance)
			{
				nearestDistance = distance;
				nearest = player;
			}
		}

		final double radians = nearest != null ? Math.toRadians(LocationUtil.calculateAngleFrom(nearest, this)) : Rnd.nextDouble() * 2 * Math.PI;
		final int x = (int) (getX() + (ESCAPE_DISTANCE * Math.cos(radians)));
		final int y = (int) (getY() + (ESCAPE_DISTANCE * Math.sin(radians)));
		final Location destination = GeoEngine.getInstance().getValidLocation(getX(), getY(), getZ(), x, y, getZ(), getInstanceId());
		getAI().setIntention(Intention.MOVE_TO, destination);

		if (_thiefRadar)
		{
			updateThiefRadar();
		}
	}

	/**
	 * Thieves' Night: the players near a fleeing Thief see where it runs on their radar.
	 */
	private void updateThiefRadar()
	{
		final int range = NightCycleConfig.NIGHT_THIEF_RADAR_RANGE;
		final List<Player> nearby = range > 0 ? World.getInstance().getVisibleObjectsInRange(this, Player.class, range) : List.of();
		final Location here = new Location(getX(), getY(), getZ());
		for (Player player : nearby)
		{
			final Location previous = _thiefRadarMarkers.put(player.getObjectId(), here);
			if (previous != null)
			{
				player.getRadar().removeMarker(previous.getX(), previous.getY(), previous.getZ());
			}
			else
			{
				player.sendPacket(new ExShowScreenMessage("A Thief runs off into the dark with its bag! It is on your radar.", ExShowScreenMessage.TOP_CENTER, 4000));
			}
			player.getRadar().addMarker(here.getX(), here.getY(), here.getZ());
		}

		// Those it left behind lose it.
		for (Map.Entry<Integer, Location> entry : _thiefRadarMarkers.entrySet())
		{
			if (entry.getValue() != here)
			{
				removeThiefMarker(entry.getKey(), entry.getValue());
			}
		}
	}

	private void removeThiefMarker(int objectId, Location marker)
	{
		if (!_thiefRadarMarkers.remove(objectId, marker))
		{
			return;
		}

		final Player player = World.getInstance().getPlayer(objectId);
		if (player != null)
		{
			player.getRadar().removeMarker(marker.getX(), marker.getY(), marker.getZ());
		}
	}

	private void stopEscape()
	{
		final ScheduledFuture<?> task = _escapeTask;
		if (task == null)
		{
			return;
		}

		_escapeTask = null;
		task.cancel(false);
		disableCoreAI(false);

		_thiefRadar = false;
		for (Map.Entry<Integer, Location> entry : _thiefRadarMarkers.entrySet())
		{
			removeThiefMarker(entry.getKey(), entry.getValue());
		}
	}

	// =======================================================================
	// Mage Monster System
	// =======================================================================

	/**
	 * @return {@code true} if {@link org.l2jmobius.gameserver.managers.MageMonsterManager} turned this spawn into a Mage.
	 */
	public boolean isMageMonster()
	{
		return MageMonsterConfig.ENABLED && getVariables().getBoolean("IS_MAGE", false);
	}

	/**
	 * Turns this freshly spawned monster into a Mage. Called by {@link org.l2jmobius.gameserver.managers.MageMonsterManager#tryConvert}.
	 */
	public void startMage()
	{
		getVariables().set("IS_MAGE", true);
		rebuildFullTitle();
		broadcastInfo();
	}

	// =======================================================================
	// Infused Monster System
	// =======================================================================

	/**
	 * @return {@code true} if {@link org.l2jmobius.gameserver.managers.InfusedMonsterManager} infused this spawn with an element.
	 */
	public boolean isInfused()
	{
		return InfusedMonsterConfig.ENABLED && (_infusedElement != Elementals.NONE);
	}

	/**
	 * @return the element this monster is infused with ({@link Elementals#FIRE} ... {@link Elementals#DARK}), or {@link Elementals#NONE}
	 */
	public byte getInfusedElement()
	{
		return _infusedElement;
	}

	/**
	 * Infuses this monster with {@code element}: it attacks with that element (it becomes its strongest one), resists it and is weak to the opposite one, shows the element's visual and pulses the element's nova in combat. Called by
	 * {@link org.l2jmobius.gameserver.managers.InfusedMonsterManager}. An earlier infusion is replaced.
	 * @param element the element ({@link Elementals#FIRE} ... {@link Elementals#DARK})
	 */
	public synchronized void startInfused(byte element)
	{
		if ((element < 0) || (element >= InfusedMonsterConfig.ELEMENT_COUNT) || isDead())
		{
			return;
		}

		clearInfused(false);

		// Damage only compares an attack element with the same element's resistance (Formulas.calcAttributeBonus), and an npc attacks with its strongest element - so the infused element is
		// raised above every element the template already has, and "weak to the opposite element" is a low resistance to it.
		int strongestOther = 0;
		for (byte other = 0; other < InfusedMonsterConfig.ELEMENT_COUNT; other++)
		{
			if (other != element)
			{
				strongestOther = Math.max(strongestOther, getStat().getAttackElementValue(other));
			}
		}
		final int power = InfusedMonsterConfig.ATTACK_POWER + Math.max(0, strongestOther - getStat().getAttackElementValue(element));

		final List<AbstractFunction> functions = new ArrayList<>(3);
		functions.add(new FuncAdd(getElementPowerStat(element), 0x40, INFUSED_FUNC_OWNER, power, null));
		functions.add(new FuncAdd(getElementResStat(element), 0x40, INFUSED_FUNC_OWNER, InfusedMonsterConfig.OWN_RESIST, null));
		functions.add(new FuncAdd(getElementResStat(Elementals.getOppositeElement(element)), 0x40, INFUSED_FUNC_OWNER, InfusedMonsterConfig.OPPOSITE_RESIST, null));
		addStatFuncs(functions);
		_infusedElement = element;

		final AbnormalVisualEffect ave = InfusedMonsterConfig.VISUAL_EFFECTS[element];
		if ((ave != null) && (ave != AbnormalVisualEffect.NONE))
		{
			_infusedAve = ave;
			startAbnormalVisualEffect(false, ave);
		}

		startInfusedNova();
		rebuildFullTitle();
		broadcastInfo();
	}

	/**
	 * Takes the infusion away again (used by GMs).
	 */
	public void stopInfused()
	{
		clearInfused(true);
	}

	/**
	 * Removes the infusion's stat Funcs, visual, nova and title tag, if any.
	 * @param broadcast {@code true} to refresh the monster for nearby players
	 */
	private synchronized void clearInfused(boolean broadcast)
	{
		stopInfusedNova();
		if ((_infusedElement == Elementals.NONE) && (_infusedAve == null))
		{
			return;
		}

		_infusedElement = Elementals.NONE;
		removeStatsOwner(INFUSED_FUNC_OWNER);
		if (_infusedAve != null)
		{
			stopAbnormalVisualEffect(false, _infusedAve);
			_infusedAve = null;
		}

		rebuildFullTitle();
		if (broadcast)
		{
			broadcastInfo();
		}
	}

	/**
	 * @return the P.Atk/M.Atk multiplier of an Infused monster, 1.0 otherwise
	 */
	private double getInfusedAttackMultiplier()
	{
		return isInfused() ? InfusedMonsterConfig.ATTACK_MULTIPLIER : 1.0;
	}

	private void startInfusedNova()
	{
		if ((InfusedMonsterConfig.NOVA_INTERVAL <= 0) || (_infusedNovaTask != null))
		{
			return;
		}

		_infusedNextNovaAt = 0;
		_infusedNovaTask = ThreadPool.scheduleAtFixedRate(this::infusedNovaTick, INFUSED_NOVA_CHECK_MS, INFUSED_NOVA_CHECK_MS);
	}

	private void stopInfusedNova()
	{
		final ScheduledFuture<?> task = _infusedNovaTask;
		if (task != null)
		{
			_infusedNovaTask = null;
			task.cancel(false);
		}
	}

	/**
	 * Casts the element's nova (a slow, area-around-the-caster spell players can step away from) at most once every {@link InfusedMonsterConfig#NOVA_INTERVAL} ms, when an enemy it fights is close enough to be hit.
	 */
	private void infusedNovaTick()
	{
		if ((System.currentTimeMillis() < _infusedNextNovaAt) || isDead() || !isSpawned() || !isInfused() || !isInCombat() || isCastingNow() || isAllSkillsDisabled() || isMuted() || isCoreAIDisabled() || isMoving())
		{
			return;
		}

		final Skill nova = getInfusedNovaSkill();
		if ((nova == null) || isSkillDisabled(nova))
		{
			return;
		}

		final int range = Math.max(100, nova.getAffectRange());
		for (Creature attacker : getAggroList().keySet())
		{
			if ((attacker != null) && !attacker.isDead() && isInsideRadius3D(attacker, range))
			{
				doCast(nova);
				if (isCastingNow())
				{
					_infusedNextNovaAt = System.currentTimeMillis() + InfusedMonsterConfig.NOVA_INTERVAL;
				}
				return;
			}
		}
	}

	/**
	 * @return the highest level of the element's nova whose magic level isn't above this monster's level (level 1 for a lower monster), or {@code null} if the element has none
	 */
	private Skill getInfusedNovaSkill()
	{
		final byte element = _infusedElement;
		if ((element < 0) || (element >= InfusedMonsterConfig.ELEMENT_COUNT))
		{
			return null;
		}

		final int skillId = InfusedMonsterConfig.NOVA_SKILLS[element];
		if (skillId <= 0)
		{
			return null;
		}

		Skill best = null;
		final int maxLevel = SkillData.getInstance().getMaxLevel(skillId);
		for (int level = 1; level <= maxLevel; level++)
		{
			final Skill skill = SkillData.getInstance().getSkill(skillId, level);
			if ((skill != null) && ((best == null) || (skill.getMagicLevel() <= getLevel())))
			{
				best = skill;
			}
		}
		return best;
	}

	/**
	 * @param element the element
	 * @return the attack power stat of {@code element}
	 */
	private static Stat getElementPowerStat(byte element)
	{
		switch (element)
		{
			case Elementals.WATER:
			{
				return Stat.WATER_POWER;
			}
			case Elementals.WIND:
			{
				return Stat.WIND_POWER;
			}
			case Elementals.EARTH:
			{
				return Stat.EARTH_POWER;
			}
			case Elementals.HOLY:
			{
				return Stat.HOLY_POWER;
			}
			case Elementals.DARK:
			{
				return Stat.DARK_POWER;
			}
			default:
			{
				return Stat.FIRE_POWER;
			}
		}
	}

	/**
	 * @param element the element
	 * @return the resistance stat of {@code element}
	 */
	private static Stat getElementResStat(byte element)
	{
		switch (element)
		{
			case Elementals.WATER:
			{
				return Stat.WATER_RES;
			}
			case Elementals.WIND:
			{
				return Stat.WIND_RES;
			}
			case Elementals.EARTH:
			{
				return Stat.EARTH_RES;
			}
			case Elementals.HOLY:
			{
				return Stat.HOLY_RES;
			}
			case Elementals.DARK:
			{
				return Stat.DARK_RES;
			}
			default:
			{
				return Stat.FIRE_RES;
			}
		}
	}

	// =======================================================================
	// Resonant Monster System
	// =======================================================================

	/**
	 * @return {@code true} if {@link org.l2jmobius.gameserver.managers.ResonantMonsterManager} made this spawn Resonant: killing it raises the soul crystals of the killer's party.
	 */
	public boolean isResonant()
	{
		return ResonantMonsterConfig.ENABLED && _resonant;
	}

	/**
	 * Makes this monster Resonant. Called by {@link org.l2jmobius.gameserver.managers.ResonantMonsterManager}.
	 */
	public synchronized void startResonant()
	{
		if (isDead())
		{
			return;
		}

		clearResonant(false);
		_resonant = true;

		final AbnormalVisualEffect ave = ResonantMonsterConfig.VISUAL_EFFECT;
		if ((ave != null) && (ave != AbnormalVisualEffect.NONE))
		{
			_resonantAve = ave;
			startAbnormalVisualEffect(false, ave);
		}

		rebuildFullTitle();
		broadcastInfo();
	}

	/**
	 * Makes this monster an ordinary one again (used by GMs). A soul that is fleeing stops running.
	 */
	public void stopResonant()
	{
		if (_resonant)
		{
			stopEscape();
		}
		clearResonant(true);
	}

	/**
	 * Removes the Resonant visual and title tag, if any.
	 * @param broadcast {@code true} to refresh the monster for nearby players
	 */
	private synchronized void clearResonant(boolean broadcast)
	{
		_resonantFled = false;
		if (!_resonant && (_resonantAve == null))
		{
			return;
		}

		_resonant = false;
		if (_resonantAve != null)
		{
			stopAbnormalVisualEffect(false, _resonantAve);
			_resonantAve = null;
		}

		rebuildFullTitle();
		if (broadcast)
		{
			broadcastInfo();
		}
	}

	/**
	 * The first time a Resonant monster drops to {@link ResonantMonsterConfig#FLEE_AT_HP_PERCENT}% HP, its soul runs from the players for {@link ResonantMonsterConfig#FLEE_TIME} seconds and vanishes if it is still alive by then.
	 */
	private synchronized void tryResonantFlight()
	{
		if (_resonantFled || !isResonant() || isDead() || (ResonantMonsterConfig.FLEE_AT_HP_PERCENT <= 0) || (getCurrentHp() > ((getMaxHp() * ResonantMonsterConfig.FLEE_AT_HP_PERCENT) / 100.0)))
		{
			return;
		}

		_resonantFled = true;
		sendMessageToAttackers(null, "The soul of " + getName() + " is fleeing! Catch it within " + ResonantMonsterConfig.FLEE_TIME + " seconds or the resonance fades.");
		startEscape(ResonantMonsterConfig.FLEE_TIME * 1000L, "My soul will not be caught!", "The resonance fades...");
	}

	// =======================================================================
	// Monster Rage System
	// =======================================================================

	/**
	 * @return {@code true} while this monster is raging (see {@link org.l2jmobius.gameserver.managers.MonsterRageManager}).
	 */
	public boolean isRaging()
	{
		return _raging;
	}

	/**
	 * Counts one more stun/hold landed on this monster by a player since it spawned.
	 * @return the new count
	 */
	public int addRageDisable()
	{
		return _rageDisables.incrementAndGet();
	}

	/**
	 * Starts a {@link MonsterRageConfig#DURATION_SECONDS}-second rage: the monster grows larger, gains attack/cast power and speed, resists negative effects (checked in {@code Formulas.calcEffectSuccess()}) and, if configured, breaks out of its current stuns/holds. Does nothing
	 * if it is already raging.
	 */
	public synchronized void startRage()
	{
		if (_raging || isDead())
		{
			return;
		}

		_raging = true;

		final double attackMultiplier = 1.0 + (MonsterRageConfig.ATTACK_BONUS / 100.0);
		final double speedMultiplier = 1.0 + (MonsterRageConfig.SPEED_BONUS / 100.0);
		final List<AbstractFunction> functions = new ArrayList<>(4);
		functions.add(new FuncMul(Stat.POWER_ATTACK, 0x30, RAGE_FUNC_OWNER, attackMultiplier, null));
		functions.add(new FuncMul(Stat.MAGIC_ATTACK, 0x30, RAGE_FUNC_OWNER, attackMultiplier, null));
		functions.add(new FuncMul(Stat.POWER_ATTACK_SPEED, 0x30, RAGE_FUNC_OWNER, speedMultiplier, null));
		functions.add(new FuncMul(Stat.MAGIC_ATTACK_SPEED, 0x30, RAGE_FUNC_OWNER, speedMultiplier, null));
		addStatFuncs(functions);

		startAbnormalVisualEffect(false, AbnormalVisualEffect.BIG_BODY);
		rebuildFullTitle();
		broadcastInfo();

		if (MonsterRageConfig.BREAKS_DISABLES)
		{
			// The rage is rolled from inside the stun/hold effect's onStart(), so that effect isn't
			// in the effect list yet - break free on the next tick, once it is.
			ThreadPool.execute(() ->
			{
				if (_raging && !isDead())
				{
					getEffectList().stopEffects(EffectType.STUN);
					getEffectList().stopEffects(EffectType.ROOT);
					getEffectList().stopEffects(EffectType.PARALYZE);
				}
			});
		}

		if (!MonsterRageConfig.MESSAGE.isEmpty())
		{
			sendMessageToAttackers(null, String.format(MonsterRageConfig.MESSAGE, getName()));
		}

		_rageTask = ThreadPool.schedule(() -> endRage(true), MonsterRageConfig.DURATION_SECONDS * 1000L);
	}

	/**
	 * Ends the current rage, if any, and removes its bonuses and visuals.
	 * @param broadcast {@code true} to refresh the monster for nearby players
	 */
	private synchronized void endRage(boolean broadcast)
	{
		if (_rageTask != null)
		{
			_rageTask.cancel(false);
			_rageTask = null;
		}

		if (!_raging)
		{
			return;
		}

		_raging = false;
		removeStatsOwner(RAGE_FUNC_OWNER);
		stopAbnormalVisualEffect(false, AbnormalVisualEffect.BIG_BODY);
		rebuildFullTitle();
		if (broadcast)
		{
			broadcastInfo();
		}
	}

	// =======================================================================
	// Survival Arena System
	// =======================================================================
	
	private boolean isArenaChallenger()
	{
		return RatesConfig.ARENA_SYSTEM_ENABLED && getVariables().getBoolean("IS_ARENA_CHALLENGER", false);
	}
	
	private double getArenaStatMultiplier()
	{
		int wave = getVariables().getInt("ARENA_WAVE", 0);
		return Math.pow(1 + (RatesConfig.ARENA_STAT_GROWTH_PER_WAVE / 100.0), wave);
	}

	private double getArenaOffenseMultiplier()
	{
		int wave = getVariables().getInt("ARENA_WAVE", 0);
		return Math.pow(1 + (RatesConfig.ARENA_OFFENSE_GROWTH_PER_WAVE / 100.0), wave);
	}

	private double getArenaDefenseMultiplier()
	{
		int wave = getVariables().getInt("ARENA_WAVE", 0);
		return Math.pow(1 + (RatesConfig.ARENA_DEFENSE_GROWTH_PER_WAVE / 100.0), wave);
	}
	
	/**
	 * Applies the configured "player-equivalent" buff package to the challenger. Reuses the same skill list style established in the Nemesis system.
	 */
	private void applyArenaBuffs()
	{
		if (!RatesConfig.ARENA_CHALLENGER_BUFFS_ENABLED)
		{
			return;
		}
		
		final List<Integer> ids = RatesConfig.ARENA_BUFF_SKILL_IDS;
		final List<Integer> levels = RatesConfig.ARENA_BUFF_SKILL_LEVELS;
		final int count = Math.min(ids.size(), levels.size());
		
		int applied = 0;
		for (int i = 0; i < count; i++)
		{
			Skill buff = SkillData.getInstance().getSkill(ids.get(i), levels.get(i));
			if (buff != null)
			{
				buff.applyEffects(this, this);
				applied++;
			}
		}
		
		System.out.println("ArenaChallenger: Applied " + applied + "/" + count + " configured buffs.");
	}
	
	@Override
	public void reduceCurrentHp(double amount, Creature attacker, boolean awake, boolean isDOT, Skill skill)
	{
		if (isArenaChallenger() && ((getCurrentHp() - amount) <= 0) && onArenaWaveCleared(attacker, amount))
		{
			return;
		}
		
		if (isWaveChallenge() && ((getCurrentHp() - amount) <= 0) && advanceWaveChallenge(attacker))
		{
			return;
		}
		
		super.reduceCurrentHp(amount, attacker, awake, isDOT, skill);
		
		tryMetamorphosis();
		tryResonantFlight();
	}
	
	/**
	 * Synchronized and re-checked so two simultaneous lethal hits (e.g. a summon and its owner, or a DOT tick racing a skill) can't clear two waves at once - same guard as {@link #advanceWaveChallenge(Creature)}.
	 * @param killer whoever landed the lethal blow
	 * @param amount the damage of that blow (0 from the doDie() safety net)
	 * @return {@code true} if a wave was cleared (the damage is consumed), {@code false} if the blow is no longer lethal because a racing hit already cleared the wave and refilled HP - the caller then applies it as normal damage
	 */
	private synchronized boolean onArenaWaveCleared(Creature killer, double amount)
	{
		if (isDead() || ((getCurrentHp() - amount) > 0))
		{
			return false;
		}

		final int wave = getVariables().getInt("ARENA_WAVE", 0) + 1;
		getVariables().set("ARENA_WAVE", wave);

		// getLevel() already adds this wave's virtual levels for an arena challenger - adding them again here double-counted them.
		addRandomPassiveSkill(getLevel());
		applyArenaBuffs();
		rerollArenaActiveSkills();

		this.setCurrentHp(this.getMaxHp());
		this.setCurrentMp(this.getMaxMp());

		// The "Wave N" tag lives in rebuildFullTitle(), so a later rebuild (rage, passive reroll...) no longer wipes it.
		rebuildFullTitle();

		this.broadcastInfo();
		
		if ((killer != null) && killer.isPlayer())
		{
			killer.sendMessage("Wave " + wave + " cleared! The challenger grows stronger...");
		}
		
		this.getEffectList().stopSkillEffects(SkillFinishType.REMOVED, 7029);
		
		startEnrageTimer();
		return true;
	}
	
	@Override
	public int getLevel()
	{
		if (isArenaChallenger())
		{
			// Override the level to be at least 85.
			// This completely destroys the Level-Gap Penalty, allowing the Level 35
			// monster to accurately hit high-level players and land Stuns/Fears.
			int wave = getVariables().getInt("ARENA_WAVE", 0);
			int virtualLevel = super.getLevel() + (wave * RatesConfig.ARENA_VIRTUAL_LEVEL_GROWTH_PER_WAVE);
			return Math.min(85, virtualLevel);
		}
		
		if (isWaveChallenge())
		{
			final int baseLevel = super.getLevel();
			final int virtualLevel = baseLevel + (Math.max(0, getWaveChallengeWave() - 1) * WaveChallengeConfig.LEVEL_GROWTH_PER_WAVE);
			return Math.max(baseLevel, Math.min(WaveChallengeConfig.MAX_VIRTUAL_LEVEL, virtualLevel));
		}
		
		return super.getLevel();
	}
	
	@Override
	public int getMaxHp()
	{
		final int baseMaxHp = super.getMaxHp();
		
		switch (getChampionTier())
		{
			case 1:
				championHpMult = ChampionMonstersConfig.CHAMPION_T1_HP;
				break;
			case 2:
				championHpMult = ChampionMonstersConfig.CHAMPION_T2_HP;
				break;
			case 3:
				championHpMult = ChampionMonstersConfig.CHAMPION_T3_HP;
				break;
			default:
				championHpMult = 1;
		}
		
		// Formula: 1 + ((5.0 / 100) * passiveCount)
		final int passiveCount = getVariables().getInt("PASSIVE_COUNT", 0);
		final double hpMultiplier = 1.0 + ((RatesConfig.RANDOM_PASSIVE_HP_PER_SKILL / 100.0) * passiveCount);
		final double arenaMultiplier = isArenaChallenger() ? getArenaStatMultiplier() : 1.0;
		final HotzoneModifier hotzoneModifierHp = getHotzoneStatModifier();
		final double hotzoneHpMultiplier = hotzoneModifierHp != null ? hotzoneModifierHp.getMonsterHpMult() : 1.0;

		final double variantHpMultiplier = (isInfused() ? InfusedMonsterConfig.HP_MULTIPLIER : 1.0) * (isResonant() ? ResonantMonsterConfig.HP_MULTIPLIER : 1.0);

		return (int) (baseMaxHp * hpMultiplier * arenaMultiplier * championHpMult * getHotzoneMinibossMultiplier() * hotzoneHpMultiplier * getWaveChallengeStatMultiplier() * variantHpMultiplier);

	}

	@Override
	public int getMaxMp()
	{
		final int baseMaxMp = super.getMaxMp();
		return isMageMonster() ? (int) (baseMaxMp * MageMonsterConfig.MP_MULTIPLIER) : baseMaxMp;
	}

	/**
	 * Regeneration stops at this value, and the stat layer computes it from the unboosted max MP - scale it too so a Mage regenerates its whole pool.
	 */
	@Override
	public int getMaxRecoverableMp()
	{
		final int baseMaxRecoverableMp = super.getMaxRecoverableMp();
		return isMageMonster() ? (int) (baseMaxRecoverableMp * MageMonsterConfig.MP_MULTIPLIER) : baseMaxRecoverableMp;
	}

	@Override
	public double getPAtk(Creature target)
	{
		final double basePAtk = super.getPAtk(target);
		final double multiplier = isArenaChallenger() ? getArenaOffenseMultiplier() : getHotzoneMinibossMultiplier();
		final HotzoneModifier hotzoneModifier = getHotzoneStatModifier();
		return basePAtk * multiplier * getWaveChallengeOffenseMultiplier() * (hotzoneModifier != null ? hotzoneModifier.getMonsterAtkMult() * hotzoneModifier.getRaceAtkMult(getTemplate().getRace()) : 1.0) * getHotzoneHeatAttackMultiplier(hotzoneModifier) * getInfusedAttackMultiplier();
	}

	@Override
	public double getMAtk(Creature target, Skill skill)
	{
		final double baseMAtk = super.getMAtk(target, skill);
		final double multiplier = isArenaChallenger() ? getArenaOffenseMultiplier() : getHotzoneMinibossMultiplier();
		final HotzoneModifier hotzoneModifier = getHotzoneStatModifier();
		return baseMAtk * multiplier * getWaveChallengeOffenseMultiplier() * (hotzoneModifier != null ? hotzoneModifier.getMonsterAtkMult() * hotzoneModifier.getMonsterMAtkMult() * hotzoneModifier.getRaceAtkMult(getTemplate().getRace()) : 1.0) * getHotzoneHeatAttackMultiplier(hotzoneModifier) * getInfusedAttackMultiplier();
	}

	@Override
	public double getPDef(Creature target)
	{
		final double basePDef = super.getPDef(target);
		final double multiplier = isArenaChallenger() ? getArenaDefenseMultiplier() : getHotzoneMinibossMultiplier();
		final HotzoneModifier hotzoneModifier = getHotzoneStatModifier();
		return basePDef * multiplier * getWaveChallengeDefenseMultiplier() * (hotzoneModifier != null ? hotzoneModifier.getMonsterDefMult() : 1.0);
	}

	@Override
	public double getMDef(Creature target, Skill skill)
	{
		final double baseMDef = super.getMDef(target, skill);
		final double multiplier = isArenaChallenger() ? getArenaDefenseMultiplier() : getHotzoneMinibossMultiplier();
		final HotzoneModifier hotzoneModifier = getHotzoneStatModifier();
		return baseMDef * multiplier * getWaveChallengeDefenseMultiplier() * (hotzoneModifier != null ? hotzoneModifier.getMonsterDefMult() : 1.0);
	}

	@Override
	public double getPAtkSpd()
	{
		final double basePAtkSpd = super.getPAtkSpd();
		final double multiplier = (isArenaChallenger() ? getArenaOffenseMultiplier() : 1.0) * getWaveChallengeOffenseMultiplier();
		final HotzoneModifier hotzoneModifier = getHotzoneStatModifier();
		final double hotzoneMultiplier = hotzoneModifier != null ? hotzoneModifier.getMonsterSpdMult() : 1.0;
		return basePAtkSpd * Math.sqrt(multiplier * hotzoneMultiplier);
	}

	@Override
	public int getMAtkSpd()
	{
		final int baseMAtkSpd = super.getMAtkSpd();
		final double multiplier = (isArenaChallenger() ? getArenaOffenseMultiplier() : 1.0) * getWaveChallengeOffenseMultiplier();
		final HotzoneModifier hotzoneModifier = getHotzoneStatModifier();
		final double hotzoneMultiplier = hotzoneModifier != null ? hotzoneModifier.getMonsterSpdMult() : 1.0;
		final double mageMultiplier = isMageMonster() ? 1.0 + (MageMonsterConfig.CAST_SPEED_BONUS / 100.0) : 1.0;
		return (int) (baseMAtkSpd * Math.sqrt(multiplier * hotzoneMultiplier) * mageMultiplier);
	}

	public static long calculateArenaReward(int finalWave)
	{
		if (finalWave <= 0)
		{
			return 0;
		}

		double escalatingTotal = 0;
		for (int w = 1; w <= finalWave; w++)
		{
			escalatingTotal += RatesConfig.ARENA_BASE_CURRENCY_PER_WAVE * Math.pow(1 + (RatesConfig.ARENA_ESCALATION_FACTOR / 100.0), w);
		}

		final long milestonesCrossed = finalWave / Math.max(1, RatesConfig.ARENA_MILESTONE_INTERVAL);
		final long milestoneBonusTotal = milestonesCrossed * RatesConfig.ARENA_MILESTONE_BONUS;

		return Math.round((escalatingTotal + milestoneBonusTotal) * RatesConfig.ARENA_REWARD_MULTIPLIER);
	}
	
	// =========================================================================
	
	@Override
	public synchronized void onTeleported()
	{
		super.onTeleported();
		
		if (hasMinions())
		{
			getMinionList().onMasterTeleported();
		}
	}
	
	@Override
	public boolean deleteMe()
	{
		cancelEnrageTimer();
		endRage(false);
		stopArenaWaveReminderTask();
		stopInfusedNova();
		
		if (hasMinions())
		{
			getMinionList().onMasterDie(true);
		}
		
		if (_master != null)
		{
			_master.getMinionList().onMinionDie(this, 0);
		}
		
		return super.deleteMe();
	}
	
	@Override
	public Monster getLeader()
	{
		return _master;
	}
	
	public void setLeader(Monster leader)
	{
		_master = leader;
	}
	
	public void enableMinions(boolean value)
	{
		_enableMinions = value;
	}
	
	public boolean hasMinions()
	{
		return _minionList != null;
	}
	
	public MinionList getMinionList()
	{
		if (_minionList == null)
		{
			synchronized (this)
			{
				if (_minionList == null)
				{
					_minionList = new MinionList(this);
				}
			}
		}
		
		return _minionList;
	}
	
	@Override
	public boolean isMonster()
	{
		return true;
	}
	
	@Override
	public Monster asMonster()
	{
		return this;
	}
	
	@Override
	public boolean isWalker()
	{
		return ((_master == null) ? super.isWalker() : _master.isWalker());
	}
	
	@Override
	public boolean giveRaidCurse()
	{
		if (isArenaChallenger() || isWaveChallenge())
		{
			return false;
		}
		return (isRaidMinion() && (_master != null)) ? _master.giveRaidCurse() : super.giveRaidCurse();
	}
	
	@Override
	public void doCast(Skill skill, Creature target, List<WorldObject> targets)
	{
		if (!skill.hasNegativeEffect() && (getTarget() != null) && getTarget().isPlayer())
		{
			setCastingNow(false);
			setCastingSimultaneouslyNow(false);
			return;
		}
		
		super.doCast(skill, target, targets);
	}
	
	@Override
	public boolean doDie(Creature killer)
	{
		if (isArenaChallenger())
		{
			// This acts as an ultimate safety net.
			// If a Dagger's Lethal Strike or a reflect bypasses the normal HP check,
			// it hits this wall, triggers the next wave, and fully heals the boss.
			onArenaWaveCleared(killer, 0);
			
			// Returning false tells the core engine to immediately cancel the death sequence
			// (no XP is given, no loot drops, and the monster does not disappear).
			return false;
		}
		
		// Same safety net for open-world wave challenges: anything that bypassed the HP check
		// before the final wave just advances the wave instead of killing the monster.
		if (isWaveChallenge() && advanceWaveChallenge(killer))
		{
			return false;
		}
		
		if (!super.doDie(killer))
		{
			return false;
		}
		
		endRage(true);
		stopEscape();
		stopInfusedNova();
		return true;
	}
	
	@Override
	public void onDecay()
	{
		// A Thief or Resonant soul that escaped (deleteMe()) or died: the respawn reuses this object, so it must not keep running or casting.
		stopEscape();
		stopInfusedNova();
		super.onDecay();
	}
	
	private void startEnrageTimer()
	{
		// Cancel any existing timer to prevent duplicates
		cancelEnrageTimer();
		
		// Schedule the enrage to happen in 120,000 milliseconds (2 minutes)
		_arenaEnrageTask = ThreadPool.schedule(() ->
		{
			// Safety check: if monster is dead, deleted, or not an arena boss, abort.
			if (isDead() || !isSpawned() || !isArenaChallenger())
			{
				return;
			}
			
			// Skill 436 is the standard Raid Boss Enrage (P.Atk, M.Atk, Speed massively boosted)
			Skill enrage = SkillData.getInstance().getSkill(7029, 1);
			if (enrage != null)
			{
				// Apply the buff
				enrage.applyEffects(this, this);
				
				// Show the casting animation to the player
				this.broadcastPacket(new MagicSkillUse(this, this, 7029, 1, 0, 0));
				
				// Change title to warn the player
				this.setTitle("!!! ENRAGED !!!");
				this.broadcastInfo();
			}
		}, RatesConfig.ARENA_ENRAGE_TIME * 1000L); // 120,000 ms = 2 minutes
	}
	
	/**
	 * Periodically nudges the challenging player with the current wave and the challenger's remaining HP percentage, acting as a lightweight live status readout in place of a dedicated overlay.
	 */
	private void startArenaWaveReminderTask()
	{
		final int intervalMs = Math.max(5, RatesConfig.ARENA_WAVE_REMINDER_INTERVAL_SECONDS) * 1000;
		
		_arenaWaveReminderTask = ThreadPool.scheduleAtFixedRate(() ->
		{
			if (isDead() || !isSpawned())
			{
				return;
			}
			
			final int playerObjId = getVariables().getInt("ARENA_PLAYER_OBJ_ID", 0);
			if (playerObjId == 0)
			{
				return;
			}
			
			final Player player = World.getInstance().getPlayer(playerObjId);
			if (player != null)
			{
				final int wave = getVariables().getInt("ARENA_WAVE", 0);
				final int hpPercent = (int) ((getCurrentHp() / getMaxHp()) * 100);
				player.sendMessage("Wave " + wave + " | Challenger HP: " + hpPercent + "%");
			}
		}, intervalMs, intervalMs);
	}
	
	private void stopArenaWaveReminderTask()
	{
		if (_arenaWaveReminderTask != null)
		{
			_arenaWaveReminderTask.cancel(false);
			_arenaWaveReminderTask = null;
		}
	}
	
	private void cancelEnrageTimer()
	{
		if (_arenaEnrageTask != null)
		{
			_arenaEnrageTask.cancel(false);
			_arenaEnrageTask = null;
		}
	}
}