package org.l2jmobius.gameserver.model.actor.instance;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

import org.l2jmobius.commons.threads.ThreadPool;
import org.l2jmobius.commons.util.Rnd;
import org.l2jmobius.gameserver.ai.AttackableAI;
import org.l2jmobius.gameserver.config.NpcConfig;
import org.l2jmobius.gameserver.config.RatesConfig;
import org.l2jmobius.gameserver.config.custom.ChampionMonstersConfig;
import org.l2jmobius.gameserver.config.custom.FakePlayersConfig;
import org.l2jmobius.gameserver.config.custom.HotzoneMinibossConfig;
import org.l2jmobius.gameserver.config.custom.MageMonsterConfig;
import org.l2jmobius.gameserver.config.custom.MonsterRageConfig;
import org.l2jmobius.gameserver.config.custom.ThiefMonsterConfig;
import org.l2jmobius.gameserver.config.custom.WaveChallengeConfig;
import org.l2jmobius.gameserver.data.custom.CustomSkillPoolData;
import org.l2jmobius.gameserver.data.custom.CustomSkillPoolData.CustomSkill;
import org.l2jmobius.gameserver.data.xml.SkillData;
import org.l2jmobius.gameserver.managers.HotzoneModifierManager;
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
import org.l2jmobius.gameserver.model.skill.AbnormalVisualEffect;
import org.l2jmobius.gameserver.model.skill.Skill;
import org.l2jmobius.gameserver.model.skill.enums.SkillFinishType;
import org.l2jmobius.gameserver.model.stats.Stat;
import org.l2jmobius.gameserver.model.stats.functions.AbstractFunction;
import org.l2jmobius.gameserver.model.stats.functions.FuncMul;
import org.l2jmobius.gameserver.network.serverpackets.MagicSkillUse;

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
			return FakePlayersConfig.FAKE_PLAYER_AUTO_ATTACKABLE || isInCombat() || attacker.isMonster() || (getScriptValue() > 0);
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
		return getTemplate().isAggressive() && !isAffected(EffectFlag.PASSIVE);
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
			passiveMultiplier *= hotzoneModifier.getDropRateMult();
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

		// Raid bosses and grand bosses are already hand-tuned; skip the random passive pool for
		// them. isRaid() is checked here (before the arena branch below sets it for unrelated
		// reasons) because at this point it's only ever true for a genuine RaidBoss/GrandBoss -
		// their constructors set it before onSpawn() ever runs.
		if (!isRaid())
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
		
		// Remove the actual skills from the monster's known skill list
		final String idsAsString = getVariables().getString("PASSIVE_SKILL_IDS", "");
		if (!idsAsString.isEmpty())
		{
			for (String idStr : idsAsString.split(","))
			{
				try
				{
					int skillId = Integer.parseInt(idStr.trim());
					Skill oldSkill = getKnownSkill(skillId);
					if (oldSkill != null)
					{
						this.removeSkill(oldSkill, true);
					}
				}
				catch (NumberFormatException e)
				{
					// ignored
				}
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
	
	private void rebuildFullTitle()
	{
		final String championTag = getVariables().getString("CHAMPION_TITLE_TAG", "");
		final String passiveTag = getVariables().getString("PASSIVE_TITLE_TAG", "");
		final String baseTitle = getTemplate().getTitle() == null ? "" : getTemplate().getTitle();

		final StringBuilder sb = new StringBuilder();
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
		if (isWaveChallenge())
		{
			sb.append(String.format(WaveChallengeConfig.TITLE_TAG, getWaveChallengeWave(), WaveChallengeConfig.WAVE_COUNT)).append(' ');
		}
		if (isHotzoneMiniboss())
		{
			sb.append(HotzoneMinibossConfig.TITLE_TAG).append(' ');
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

	@Override
	public long getExpReward(int level)
	{
		double multiplier = isHotzoneMiniboss() ? HotzoneMinibossConfig.XP_SP_MULTIPLIER : 1.0;
		if (isWaveChallenge())
		{
			multiplier *= WaveChallengeConfig.XP_MULTIPLIER;
		}
		final HotzoneModifier hotzoneModifier = getActiveHotzoneModifier();
		if (hotzoneModifier != null)
		{
			multiplier *= hotzoneModifier.getXpSpMult();
		}
		return (long) (super.getExpReward(level) * multiplier);
	}

	@Override
	public int getSpReward(int level)
	{
		double multiplier = isHotzoneMiniboss() ? HotzoneMinibossConfig.XP_SP_MULTIPLIER : 1.0;
		if (isWaveChallenge())
		{
			multiplier *= WaveChallengeConfig.SP_MULTIPLIER;
		}
		final HotzoneModifier hotzoneModifier = getActiveHotzoneModifier();
		if (hotzoneModifier != null)
		{
			multiplier *= hotzoneModifier.getXpSpMult();
		}
		return (int) (super.getSpReward(level) * multiplier);
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
		return isWaveChallenge() && (getWaveChallengeWave() >= WaveChallengeConfig.WAVE_COUNT);
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
	 * A lethal blow before the final wave instead heals the monster and moves it to the next, stronger wave - the open-world counterpart of {@link #onArenaWaveCleared(Creature)}. Synchronized and re-checked so two simultaneous lethal hits (e.g. a DOT tick racing a skill) can't skip a wave.
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
		getVariables().set("WAVE_CHALLENGE_WAVE", wave);

		if (WaveChallengeConfig.REROLL_PASSIVES_PER_WAVE)
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

		final String message = (wave >= WaveChallengeConfig.WAVE_COUNT) ? "Final wave " + wave + "/" + WaveChallengeConfig.WAVE_COUNT + "! Defeat " + getName() + " once more to claim the reward." : "Wave " + (wave - 1) + " cleared! " + getName() + " grows stronger... (wave " + wave + "/" + WaveChallengeConfig.WAVE_COUNT + ")";
		sendMessageToAttackers(killer, message);
		return true;
	}

	/**
	 * Resets a partially cleared challenge back to wave 1 once nobody is fighting it any more, so players can't whittle it down across separate attempts.
	 */
	private void resetWaveChallenge()
	{
		getVariables().set("WAVE_CHALLENGE_WAVE", 1);

		if (WaveChallengeConfig.REROLL_PASSIVES_PER_WAVE)
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

		getVariables().set("THIEF_KILLS", kills + 1);
		rebuildFullTitle();
		broadcastInfo();
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
		if (isArenaChallenger() && ((getCurrentHp() - amount) <= 0))
		{
			onArenaWaveCleared(attacker);
			return;
		}
		
		if (isWaveChallenge() && ((getCurrentHp() - amount) <= 0) && advanceWaveChallenge(attacker))
		{
			return;
		}
		
		super.reduceCurrentHp(amount, attacker, awake, isDOT, skill);
	}
	
	private void onArenaWaveCleared(Creature killer)
	{
		final int wave = getVariables().getInt("ARENA_WAVE", 0) + 1;
		getVariables().set("ARENA_WAVE", wave);

		final int virtualLevel = Math.min(100, this.getLevel() + (wave * RatesConfig.ARENA_VIRTUAL_LEVEL_GROWTH_PER_WAVE));
		addRandomPassiveSkill(virtualLevel);
		applyArenaBuffs();
		rerollArenaActiveSkills();
		
		this.setCurrentHp(this.getMaxHp());
		this.setCurrentMp(this.getMaxMp());
		
		String currentTitle = this.getTitle() == null ? "" : this.getTitle();
		// Remove previous "Wave X -" tags to avoid infinite stacking
		currentTitle = currentTitle.replaceAll("Wave \\d+ - ", "");
		this.setTitle("Wave " + wave + " - " + currentTitle);
		
		this.broadcastInfo();
		
		if ((killer != null) && killer.isPlayer())
		{
			killer.sendMessage("Wave " + wave + " cleared! The challenger grows stronger...");
		}
		
		this.getEffectList().stopSkillEffects(SkillFinishType.REMOVED, 7029);
		
		startEnrageTimer();
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
		final HotzoneModifier hotzoneModifierHp = getActiveHotzoneModifier();
		final double hotzoneHpMultiplier = hotzoneModifierHp != null ? hotzoneModifierHp.getMonsterHpMult() : 1.0;

		return (int) (baseMaxHp * hpMultiplier * arenaMultiplier * championHpMult * getHotzoneMinibossMultiplier() * hotzoneHpMultiplier * getWaveChallengeStatMultiplier());

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
		final HotzoneModifier hotzoneModifier = getActiveHotzoneModifier();
		return basePAtk * multiplier * getWaveChallengeOffenseMultiplier() * (hotzoneModifier != null ? hotzoneModifier.getMonsterAtkMult() : 1.0);
	}

	@Override
	public double getMAtk(Creature target, Skill skill)
	{
		final double baseMAtk = super.getMAtk(target, skill);
		final double multiplier = isArenaChallenger() ? getArenaOffenseMultiplier() : getHotzoneMinibossMultiplier();
		final HotzoneModifier hotzoneModifier = getActiveHotzoneModifier();
		return baseMAtk * multiplier * getWaveChallengeOffenseMultiplier() * (hotzoneModifier != null ? hotzoneModifier.getMonsterAtkMult() : 1.0);
	}

	@Override
	public double getPDef(Creature target)
	{
		final double basePDef = super.getPDef(target);
		final double multiplier = isArenaChallenger() ? getArenaDefenseMultiplier() : getHotzoneMinibossMultiplier();
		final HotzoneModifier hotzoneModifier = getActiveHotzoneModifier();
		return basePDef * multiplier * getWaveChallengeDefenseMultiplier() * (hotzoneModifier != null ? hotzoneModifier.getMonsterDefMult() : 1.0);
	}

	@Override
	public double getMDef(Creature target, Skill skill)
	{
		final double baseMDef = super.getMDef(target, skill);
		final double multiplier = isArenaChallenger() ? getArenaDefenseMultiplier() : getHotzoneMinibossMultiplier();
		final HotzoneModifier hotzoneModifier = getActiveHotzoneModifier();
		return baseMDef * multiplier * getWaveChallengeDefenseMultiplier() * (hotzoneModifier != null ? hotzoneModifier.getMonsterDefMult() : 1.0);
	}

	@Override
	public double getPAtkSpd()
	{
		final double basePAtkSpd = super.getPAtkSpd();
		final double multiplier = (isArenaChallenger() ? getArenaOffenseMultiplier() : 1.0) * getWaveChallengeOffenseMultiplier();
		final HotzoneModifier hotzoneModifier = getActiveHotzoneModifier();
		final double hotzoneMultiplier = hotzoneModifier != null ? hotzoneModifier.getMonsterSpdMult() : 1.0;
		return basePAtkSpd * Math.sqrt(multiplier * hotzoneMultiplier);
	}

	@Override
	public int getMAtkSpd()
	{
		final int baseMAtkSpd = super.getMAtkSpd();
		final double multiplier = (isArenaChallenger() ? getArenaOffenseMultiplier() : 1.0) * getWaveChallengeOffenseMultiplier();
		final HotzoneModifier hotzoneModifier = getActiveHotzoneModifier();
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
			onArenaWaveCleared(killer);
			
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
		return true;
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
	
	@Override
	public void onActionShift(org.l2jmobius.gameserver.model.actor.Player player)
	{
		if (player.isGM())
		{
			super.onActionShift(player);
		}
		else
		{
			org.l2jmobius.gameserver.network.serverpackets.NpcHtmlMessage html = new org.l2jmobius.gameserver.network.serverpackets.NpcHtmlMessage(this.getObjectId());
			StringBuilder sb = new StringBuilder();
			
			sb.append("<html><body>");
			sb.append("<center>");
			sb.append("<font color=\"LEVEL\">").append(this.getName()).append("</font><br>");
			sb.append("<font color=\"AAAAAA\">Level ").append(this.getLevel()).append("</font><br>");
			sb.append("<img src=\"L2UI.SquareWhite\" width=260 height=1><br>");
			sb.append("</center>");
			
			sb.append("<font color=\"LEVEL\">Active Passives:</font><br>");
			
			int skillCount = 0;
			for (Skill skill : this.getSkills().values())
			{
				if (skill.isPassive())
				{
					sb.append("<font color=\"00FFFF\">").append(skill.getName()).append("</font> Lv. ").append(skill.getLevel()).append("<br>");
					skillCount++;
				}
			}
			
			if (skillCount == 0)
			{
				sb.append("<font color=\"777777\">No passives active.</font>");
			}
			
			sb.append("</body></html>");
			
			html.setHtml(sb.toString());
			player.sendPacket(html);
			
			player.sendPacket(org.l2jmobius.gameserver.network.serverpackets.ActionFailed.STATIC_PACKET);
		}
	}
}