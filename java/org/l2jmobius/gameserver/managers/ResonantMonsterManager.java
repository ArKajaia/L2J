package org.l2jmobius.gameserver.managers;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;

import org.l2jmobius.commons.threads.ThreadPool;
import org.l2jmobius.commons.util.Rnd;
import org.l2jmobius.gameserver.config.NpcConfig;
import org.l2jmobius.gameserver.config.PlayerConfig;
import org.l2jmobius.gameserver.config.RatesConfig;
import org.l2jmobius.gameserver.config.custom.FakePlayerPvpConfig;
import org.l2jmobius.gameserver.config.custom.ResonantMonsterConfig;
import org.l2jmobius.gameserver.data.enums.AbsorbCrystalType;
import org.l2jmobius.gameserver.data.holders.LevelingSoulCrystalInfo;
import org.l2jmobius.gameserver.data.holders.SoulCrystal;
import org.l2jmobius.gameserver.data.xml.LevelUpCrystalData;
import org.l2jmobius.gameserver.data.xml.NpcData;
import org.l2jmobius.gameserver.model.Location;
import org.l2jmobius.gameserver.model.actor.Attackable;
import org.l2jmobius.gameserver.model.actor.Creature;
import org.l2jmobius.gameserver.model.actor.Npc;
import org.l2jmobius.gameserver.model.actor.Player;
import org.l2jmobius.gameserver.model.actor.holders.npc.AggroInfo;
import org.l2jmobius.gameserver.model.actor.instance.Chest;
import org.l2jmobius.gameserver.model.actor.instance.Monster;
import org.l2jmobius.gameserver.model.actor.templates.NpcTemplate;
import org.l2jmobius.gameserver.model.hiddenquest.task.AbstractHiddenTask;
import org.l2jmobius.gameserver.model.hotzone.HotzoneModifier;
import org.l2jmobius.gameserver.model.item.enums.ItemProcessType;
import org.l2jmobius.gameserver.model.item.instance.Item;
import org.l2jmobius.gameserver.model.script.QuestState;
import org.l2jmobius.gameserver.model.spawns.Spawn;
import org.l2jmobius.gameserver.network.SystemMessageId;
import org.l2jmobius.gameserver.network.serverpackets.InventoryUpdate;
import org.l2jmobius.gameserver.network.serverpackets.SystemMessage;

/**
 * Resonant monsters (see {@link ResonantMonsterConfig}): like a champion, a regular monster has a small chance every time it spawns or respawns to become Resonant ({@link #tryConvert(Npc, Spawn)}, called from {@code Spawn} right after the Infused roll). A spawn point that just
 * produced one skips the roll for its next {@link ResonantMonsterConfig#RESPAWN_COOLDOWN} spawns. Scripted spawns that never respawn don't roll, and neither does a monster with soul crystal rules of its own
 * ({@link #hasOwnSoulCrystalRules(int, int)}).
 * <ul>
 * <li>Combat: it has more HP, and the first time it drops low its soul flees ({@code Monster#tryResonantFlight()}). From {@link ResonantMonsterConfig#RAID_STAGES} on it raises the stages retail only gives on raid bosses, and is as strong as
 * {@link ResonantMonsterConfig#RAID_STRENGTH_PERCENT}% of a raid boss of its level ({@link #isRaidTier(int)}, {@link #getRaidReference(int)}).</li>
 * <li>Joiners: at {@link ResonantMonsterConfig#FAKE_JOIN_HP_PERCENTS} a fake player turns up ({@link #onFakeJoinThreshold(Monster)}): a helper joins the party of the player fighting it, a rival fights for the soul itself and is fair game ({@link #isRival(Creature)}).</li>
 * <li>Reward: {@link #onAttackableKilled(Attackable, Player)} (called from {@code Attackable#doDie()} alongside the other post-kill hooks) raises the soul crystal of the killer and of every party member nearby by one stage, with the retail rules of quest 350 Enhance Your Weapon (the
 * quest started, exactly one soul crystal carried) and the retail messages. No crystal use on the monster is needed. A crystal only grows up to {@link #getStageCap(int)}: what the regular monsters of the Resonant monster's level could raise, or the
 * {@link ResonantMonsterConfig#RAID_STAGES} of its level. Quest 350 itself skips Resonant monsters, so a crystal never grows twice from one kill.</li>
 * </ul>
 */
public class ResonantMonsterManager
{
	/** The quest whose rules a soul crystal follows. */
	private static final String SOUL_CRYSTAL_QUEST = "Q00350_EnhanceYourWeapon";

	/** See {@link #getCapTable()}. */
	private static volatile TreeMap<Integer, Integer> _capTable = null;

	/** The first crystal stage retail only raises on raid bosses: a Resonant monster that raises it is a raid strength fight. */
	public static final int RAID_ONLY_STAGE = 10;

	/** Indexes of the stats in {@link #getRaidReference(int)}. */
	public static final int STAT_HP = 0;
	public static final int STAT_PATK = 1;
	public static final int STAT_MATK = 2;
	public static final int STAT_PDEF = 3;
	public static final int STAT_MDEF = 4;
	public static final int STAT_COUNT = 5;

	/** Raid boss template id -&gt; its runtime stats, measured once (see {@link #measureRaid(NpcTemplate)}). */
	private static final Map<Integer, double[]> RAID_TEMPLATE_STATS = new ConcurrentHashMap<>();
	/** Level -&gt; the median raid boss stats around that level (see {@link #getRaidReference(int)}). */
	private static final Map<Integer, double[]> RAID_REFERENCE = new ConcurrentHashMap<>();

	/** Set on a rival fake player, to the object id of the Resonant monster it came for. */
	private static final String RIVAL_VAR = "RESONANT_RIVAL";
	/** How far from the Resonant monster a fake player turns up, so it runs into the fight. */
	private static final int JOIN_MIN_DISTANCE = 600;
	private static final int JOIN_MAX_DISTANCE = 900;

	/** Resonant monster object id -&gt; the fake players that joined its fight. */
	private final Map<Integer, Set<Npc>> _joiners = new ConcurrentHashMap<>();

	protected ResonantMonsterManager()
	{
	}

	/**
	 * Rolls {@link ResonantMonsterConfig#SPAWN_CHANCE} for a freshly spawned npc and, if it hits, makes it Resonant.
	 * @param npc the npc that just spawned or respawned
	 * @param spawn the spawn point it came from
	 */
	public void tryConvert(Npc npc, Spawn spawn)
	{
		if (!ResonantMonsterConfig.ENABLED || (npc == null) || !npc.isMonster() || (spawn == null))
		{
			return;
		}

		// Scripted spawns (boss guards, quest and hidden quest monsters) never respawn: a soul that fled would be gone for good, and their fights are made by hand.
		if (!spawn.isRespawnEnabled() || (spawn.getRespawnMinDelay() <= 0))
		{
			return;
		}

		// This spawn point was Resonant recently - sit this spawn out.
		final int cooldown = spawn.getResonantCooldown();
		if (cooldown > 0)
		{
			spawn.setResonantCooldown(cooldown - 1);
			return;
		}

		final Monster monster = npc.asMonster();
		if (!isEligible(monster, spawn.getInstanceId()))
		{
			return;
		}

		// A SOUL_TIDE-style hotzone modifier draws more of them.
		final HotzoneModifier modifier = HotzoneModifierManager.getInstance().getModifierFor(monster);
		final double chance = ResonantMonsterConfig.SPAWN_CHANCE * (modifier != null ? modifier.getResonantSpawnMult() : 1.0);
		if ((Rnd.nextDouble() * 100) >= chance)
		{
			return;
		}

		monster.startResonant();
		spawn.setResonantCooldown(ResonantMonsterConfig.RESPAWN_COOLDOWN);
	}

	/**
	 * @param monster the monster
	 * @param instanceId the instance it spawned in
	 * @return {@code true} if {@code monster} may become Resonant: a plain monster of a level whose regular monsters raise soul crystals, none of the other special kinds
	 */
	private static boolean isEligible(Monster monster, int instanceId)
	{
		if (monster.isQuestMonster() || monster.getTemplate().isUndying() || monster.isRaid() || monster.isRaidMinion() || (monster.getLeader() != null) || monster.isFakePlayer() || (monster instanceof Chest))
		{
			return false;
		}

		if (!ResonantMonsterConfig.ALLOW_IN_INSTANCES && (instanceId != 0))
		{
			return false;
		}

		if (!ResonantMonsterConfig.ALLOW_CHAMPIONS && (monster.getChampionTier() > 0))
		{
			return false;
		}

		// Keep the special monster types apart.
		if (ResonantMonsterConfig.EXCLUDED_NPC_IDS.contains(monster.getId()) || monster.isThief() || monster.isMageMonster() || monster.isInfused() || monster.isWaveChallenge() || monster.getVariables().getBoolean("IS_ARENA_CHALLENGER", false) || monster.isHotzoneMiniboss())
		{
			return false;
		}

		final int level = monster.getLevel();
		if ((level < ResonantMonsterConfig.MIN_LEVEL) || (level > ResonantMonsterConfig.MAX_LEVEL))
		{
			return false;
		}

		final int cap = getStageCap(level);
		return (cap >= 0) && !hasOwnSoulCrystalRules(monster.getId(), cap);
	}

	/**
	 * A monster that raises soul crystal stages above the Resonant cap keeps its own quest 350 roll instead: quest 350 skips Resonant monsters, so as one it would give those crystals nothing. A stage at or below the cap is no reason: the Resonant
	 * reward raises it for the whole party, which beats any roll of its own (the Tyrannosaurus' stages 10 and 11 for a random party member are covered from level 80).
	 * @param npcId the monster's npc id
	 * @param cap the Resonant stage cap at its level
	 * @return {@code true} if its own soul crystal data raises a stage above {@code cap}
	 */
	private static boolean hasOwnSoulCrystalRules(int npcId, int cap)
	{
		final Map<Integer, LevelingSoulCrystalInfo> own = LevelUpCrystalData.getInstance().getNpcSoulInfo(npcId);
		if (own == null)
		{
			return false;
		}

		for (int stage : own.keySet())
		{
			if (stage > cap)
			{
				return true;
			}
		}
		return false;
	}

	/**
	 * The highest soul crystal stage a Resonant monster of {@code level} can raise: the highest stage any regular (non-raid, last hit) monster of that level or lower raises in {@code data/LevelUpCrystalData.xml}, or the
	 * {@link ResonantMonsterConfig#RAID_STAGES} of its level if higher, but no higher than {@link ResonantMonsterConfig#MAX_CRYSTAL_STAGE}. With the retail data and the default raid stages that is stage 1 at level 40, 4 at 50, 7 at 60, 9 from 63, 10
	 * from 76 and 11 from 80 (stage 11 grows to stage 12).
	 * @param level the monster level
	 * @return the highest stage that can still grow, or -1 if no regular monster of that level or lower raises a crystal
	 */
	public static int getStageCap(int level)
	{
		final Map.Entry<Integer, Integer> regular = getCapTable().floorEntry(level);
		final Map.Entry<Integer, Integer> raid = ResonantMonsterConfig.RAID_STAGES.floorEntry(level);
		final int cap = Math.max(regular != null ? regular.getValue() : -1, raid != null ? raid.getValue() : -1);
		return cap >= 0 ? Math.min(cap, ResonantMonsterConfig.MAX_CRYSTAL_STAGE) : -1;
	}

	/**
	 * @param level the monster level
	 * @return {@code true} if a Resonant monster of {@code level} raises a stage retail only gives on raid bosses ({@link #RAID_ONLY_STAGE} or higher): it is a raid strength fight
	 */
	public static boolean isRaidTier(int level)
	{
		return getStageCap(level) >= RAID_ONLY_STAGE;
	}

	/**
	 * The strength of a raid boss of {@code level}: the median of the {@code RaidBoss} templates within 2 levels of it (5 or 10 if there are fewer than 3). Each raid boss is measured once at runtime ({@link #measureRaid(NpcTemplate)}), so the
	 * NpcStatMultipliers, the base stat bonuses and the NPC.ini Raid*Multiplier values all count, as they do for the real raid boss.
	 * @param level the monster level
	 * @return the stats indexed by {@link #STAT_HP} ... {@link #STAT_MDEF}, or {@code null} if no raid boss is near that level
	 */
	public static double[] getRaidReference(int level)
	{
		final double[] cached = RAID_REFERENCE.get(level);
		if (cached != null)
		{
			return cached.length == 0 ? null : cached;
		}

		double[] reference = new double[0];
		for (int spread : new int[]
		{
			2,
			5,
			10
		})
		{
			final List<NpcTemplate> raids = NpcData.getInstance().getTemplates(template -> template.isType("RaidBoss") && (Math.abs(template.getLevel() - level) <= spread));
			if ((raids.size() < 3) && (spread < 10))
			{
				continue;
			}

			if (!raids.isEmpty())
			{
				reference = new double[STAT_COUNT];
				for (int stat = 0; stat < STAT_COUNT; stat++)
				{
					final double[] values = new double[raids.size()];
					for (int i = 0; i < raids.size(); i++)
					{
						values[i] = measureRaid(raids.get(i))[stat];
					}
					Arrays.sort(values);
					reference[stat] = (values.length % 2) == 1 ? values[values.length / 2] : (values[(values.length / 2) - 1] + values[values.length / 2]) / 2;
				}
			}
			break;
		}

		RAID_REFERENCE.put(level, reference);
		return reference.length == 0 ? null : reference;
	}

	/**
	 * Measures a raid boss's runtime stats on a throwaway monster of its template, never spawned (its object id is given back at once). It isn't a raid boss instance, so the NPC.ini Raid*Multiplier values on attack and defence are applied here,
	 * as {@code CreatureStat} applies them to a raid boss.
	 * @param template a raid boss template
	 * @return its stats indexed by {@link #STAT_HP} ... {@link #STAT_MDEF}
	 */
	private static double[] measureRaid(NpcTemplate template)
	{
		final double[] cached = RAID_TEMPLATE_STATS.get(template.getId());
		if (cached != null)
		{
			return cached;
		}

		final Monster probe = new Monster(template);
		try
		{
			// The stat layer's values: the Monster overrides would add whatever hot zone or night Omen holds the probe's (empty) spot right now, and this is cached.
			final double[] stats = new double[STAT_COUNT];
			stats[STAT_HP] = probe.getStat().getMaxHp();
			stats[STAT_PATK] = probe.getStat().getPAtk(null) * NpcConfig.RAID_PATTACK_MULTIPLIER;
			stats[STAT_MATK] = probe.getStat().getMAtk(null, null) * NpcConfig.RAID_MATTACK_MULTIPLIER;
			stats[STAT_PDEF] = probe.getStat().getPDef(null) * NpcConfig.RAID_PDEFENCE_MULTIPLIER;
			stats[STAT_MDEF] = probe.getStat().getMDef(null, null) * NpcConfig.RAID_MDEFENCE_MULTIPLIER;
			RAID_TEMPLATE_STATS.put(template.getId(), stats);
			return stats;
		}
		finally
		{
			IdManager.getInstance().releaseId(probe.getObjectId());
		}
	}

	/**
	 * @return level -&gt; the highest stage a regular (non-raid, last hit) monster of that level or lower raises, built once from {@code data/LevelUpCrystalData.xml} the first time it is needed
	 */
	private static TreeMap<Integer, Integer> getCapTable()
	{
		TreeMap<Integer, Integer> table = _capTable;
		if (table != null)
		{
			return table;
		}

		final TreeMap<Integer, Integer> capByLevel = new TreeMap<>();
		for (Map.Entry<Integer, Map<Integer, LevelingSoulCrystalInfo>> entry : LevelUpCrystalData.getInstance().getNpcsSoulInfo().entrySet())
		{
			final NpcTemplate template = NpcData.getInstance().getTemplate(entry.getKey());
			if ((template == null) || template.isType("RaidBoss") || template.isType("GrandBoss"))
			{
				continue;
			}

			for (Map.Entry<Integer, LevelingSoulCrystalInfo> stage : entry.getValue().entrySet())
			{
				if (stage.getValue().getAbsorbCrystalType() == AbsorbCrystalType.LAST_HIT)
				{
					capByLevel.merge((int) template.getLevel(), stage.getKey(), Math::max);
				}
			}
		}

		// Running maximum: a level raises everything the levels below it raise.
		table = new TreeMap<>();
		int cap = -1;
		for (Map.Entry<Integer, Integer> entry : capByLevel.entrySet())
		{
			cap = Math.max(cap, entry.getValue());
			table.put(entry.getKey(), cap);
		}
		_capTable = table;
		return table;
	}

	/**
	 * Raises the soul crystal of the killer and of every member of the killer's party within {@link PlayerConfig#ALT_PARTY_RANGE} of the Resonant monster.
	 * @param victim the attackable a player just killed
	 * @param killer the player credited with the kill
	 */
	public void onAttackableKilled(Attackable victim, Player killer)
	{
		if (!ResonantMonsterConfig.ENABLED || (victim == null) || (killer == null) || !victim.isMonster() || !victim.asMonster().isResonant())
		{
			return;
		}

		final List<Player> players = new ArrayList<>();
		if (killer.getParty() != null)
		{
			for (Player member : killer.getParty().getMembers())
			{
				if ((member != null) && member.isOnline() && ((member == killer) || (member.calculateDistance3D(victim) <= PlayerConfig.ALT_PARTY_RANGE)))
				{
					players.add(member);
				}
			}
		}
		if (!players.contains(killer))
		{
			players.add(killer);
		}

		final int cap = getStageCap(victim.getLevel());
		for (Player player : players)
		{
			resonate(player, victim, cap);
		}
	}

	/**
	 * Raises {@code player}'s soul crystal by one stage, with the rules and messages of quest 350.
	 * @param player the player
	 * @param victim the Resonant monster
	 * @param cap the highest stage that can still grow ({@link #getStageCap(int)})
	 */
	private static void resonate(Player player, Attackable victim, int cap)
	{
		final QuestState qs = player.getQuestState(SOUL_CRYSTAL_QUEST);
		SoulCrystal crystal = null;
		int crystals = 0;
		for (Item item : player.getInventory().getItems())
		{
			final SoulCrystal sc = LevelUpCrystalData.getInstance().getSoulCrystal(item.getId());
			if (sc != null)
			{
				crystal = sc;
				crystals++;
			}
		}

		if ((qs == null) || !qs.isStarted() || (crystals == 0))
		{
			if (ResonantMonsterConfig.HINT)
			{
				player.sendMessage("The soul of " + victim.getName() + " resonates, but you hold no soul crystal to catch it (it takes one soul crystal and the quest Enhance Your Weapon).");
			}
			return;
		}

		// Retail: a second crystal in the inventory makes them resonate with each other.
		if (crystals > 1)
		{
			player.sendPacket(SystemMessageId.THE_SOUL_CRYSTAL_CAUSED_RESONATION_AND_FAILED_AT_ABSORBING_A_SOUL);
			return;
		}

		if ((crystal.getLevel() > cap) || (LevelUpCrystalData.getInstance().getSoulCrystal(crystal.getLeveledItemId()) == null))
		{
			player.sendPacket(SystemMessageId.THE_SOUL_CRYSTAL_IS_REFUSING_TO_ABSORB_THE_SOUL);
			return;
		}

		final int chance = crystal.getLevel() >= RAID_ONLY_STAGE ? ResonantMonsterConfig.RAID_STAGE_CHANCE : ResonantMonsterConfig.CHANCE;
		if (Rnd.get(100) >= chance)
		{
			player.sendPacket(SystemMessageId.THE_SOUL_CRYSTAL_WAS_NOT_ABLE_TO_ABSORB_THE_SOUL);
			return;
		}

		final Item taken = player.getInventory().destroyItemByItemId(ItemProcessType.FEE, crystal.getItemId(), 1, player, victim);
		if (taken == null)
		{
			return;
		}

		final InventoryUpdate iu = new InventoryUpdate();
		iu.addRemovedItem(taken);
		final Item given = player.getInventory().addItem(ItemProcessType.REWARD, crystal.getLeveledItemId(), 1, player, victim);
		if (given != null)
		{
			iu.addItem(given);
		}
		player.sendInventoryUpdate(iu);

		player.sendPacket(SystemMessageId.THE_SOUL_CRYSTAL_SUCCEEDED_IN_ABSORBING_A_SOUL);
		final SystemMessage sm = new SystemMessage(SystemMessageId.YOU_HAVE_EARNED_S1);
		sm.addItemName(crystal.getLeveledItemId());
		player.sendPacket(sm);
	}

	// ---------------------------------------------------------------------------------------------
	// Fake players joining the fight
	// ---------------------------------------------------------------------------------------------

	/**
	 * @param passed the thresholds of {@code thresholds} already passed this life, one bit each
	 * @param hpPercent the monster's HP now, in percent
	 * @param thresholds the HP percents ({@link ResonantMonsterConfig#FAKE_JOIN_HP_PERCENTS})
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
	 * A Resonant monster dropped to one of {@link ResonantMonsterConfig#FAKE_JOIN_HP_PERCENTS}: a fake player turns up near the fight (spawned off the combat thread).
	 * @param monster the Resonant monster
	 */
	public void onFakeJoinThreshold(Monster monster)
	{
		ThreadPool.execute(() -> spawnJoiner(monster, null));
	}

	/**
	 * Spawns a fake player near a Resonant monster, of about its level, that joins the fight. A helper ({@link ResonantMonsterConfig#FAKE_HELPER_CHANCE}%) joins the party of the player who has hurt the monster most - the party code then credits
	 * its kills to the party and keeps it friendly. Otherwise, or if that party has no room, it is a rival: it wants the soul for itself, may open on that player ({@link ResonantMonsterConfig#RIVAL_ATTACK_PLAYER_CHANCE}%), and killing it is
	 * never a PK. The side isn't told.
	 * @param monster the Resonant monster
	 * @param helper {@code true} for a helper, {@code false} for a rival, {@code null} to roll
	 * @return the fake player, or {@code null} if none could come (fake players off or at their limit, nobody fighting the monster, no place to stand)
	 */
	public Npc spawnJoiner(Monster monster, Boolean helper)
	{
		if ((monster == null) || !monster.isResonant() || monster.isDead() || !monster.isSpawned())
		{
			return null;
		}

		final FakePlayerPvpManager fakes = FakePlayerPvpManager.getInstance();
		if (!fakes.isEnabled() || (fakes.getFakePlayers().size() >= FakePlayerPvpConfig.MAX_ALIVE))
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
		sendToAttackers(monster, fake.getName() + " joins the fight against " + monster.getName() + "!");

		final boolean wantsToHelp = helper != null ? helper : (Rnd.get(100) < ResonantMonsterConfig.FAKE_HELPER_CHANCE);
		if (wantsToHelp && FakePartyManager.getInstance().joinAsHelper(host, fake))
		{
			fakes.assistFight(fake.asAttackable(), monster);
		}
		else
		{
			fake.getVariables().set(RIVAL_VAR, monster.getObjectId());
			fakes.assistFight(fake.asAttackable(), Rnd.get(100) < ResonantMonsterConfig.RIVAL_ATTACK_PLAYER_CHANCE ? host : monster);
		}

		// The fight may have ended while it was on its way.
		if (monster.isDead() || !monster.isSpawned())
		{
			onResonantGone(monster);
		}
		return fake;
	}

	/**
	 * The Resonant monster died, fled or was removed: the fake players that joined its fight go their way {@link ResonantMonsterConfig#FAKE_LEAVE_DELAY} seconds later (see {@link #release(Npc, int)}).
	 * @param monster the Resonant monster
	 */
	public void onResonantGone(Monster monster)
	{
		final Set<Npc> joiners = _joiners.remove(monster.getObjectId());
		if ((joiners == null) || joiners.isEmpty())
		{
			return;
		}

		ThreadPool.schedule(() ->
		{
			for (Npc fake : joiners)
			{
				release(fake, 0);
			}
		}, ResonantMonsterConfig.FAKE_LEAVE_DELAY * 1000L);
	}

	/**
	 * A joiner is done with the fight: a helper leaves the party, a rival is no longer one, and both hunt where they are for a while and then log off like the other fake players ({@code FakePlayerPvpManager#onLeaveParty}). One still fighting
	 * a player finishes that first.
	 * @param fake the fake player
	 * @param attempts how many times it was already put off
	 */
	private void release(Npc fake, int attempts)
	{
		if (fake.isDead() || !fake.isSpawned())
		{
			clearRival(fake);
			return;
		}

		if (FakePlayerPvpManager.isFightingPvp(fake) && (attempts < 10))
		{
			ThreadPool.schedule(() -> release(fake, attempts + 1), Math.max(10, ResonantMonsterConfig.FAKE_LEAVE_DELAY) * 1000L);
			return;
		}

		clearRival(fake);
		if (FakePartyManager.getInstance().isInPlayerParty(fake))
		{
			FakePartyManager.getInstance().leave(fake, false, false);
		}
		else
		{
			FakePlayerPvpManager.getInstance().onLeaveParty(fake);
		}
	}

	private static void clearRival(Npc fake)
	{
		if (fake.hasVariables())
		{
			fake.getVariables().remove(RIVAL_VAR);
		}
	}

	/**
	 * @param creature a creature
	 * @return {@code true} if {@code creature} is a rival fake player that came for a Resonant monster's soul: killing it is never a PK, and pays ({@link #onRivalKilled(Attackable, Player)})
	 */
	public static boolean isRival(Creature creature)
	{
		return (creature != null) && creature.isNpc() && creature.asNpc().hasVariables() && (creature.asNpc().getVariables().getInt(RIVAL_VAR, 0) != 0);
	}

	/**
	 * A player killed a rival: {@link ResonantMonsterConfig#RIVAL_KILL_COINS} hot zone coins and a Sealed Cache, once. Called from {@code Attackable#doDie()} next to the Shadow Raider reward.
	 * @param victim the fake player killed
	 * @param killer the player credited with the kill
	 */
	public void onRivalKilled(Attackable victim, Player killer)
	{
		if ((victim == null) || (killer == null) || !isRival(victim))
		{
			return;
		}

		clearRival(victim);
		if (ResonantMonsterConfig.RIVAL_KILL_COINS > 0)
		{
			killer.addItem(ItemProcessType.REWARD, RatesConfig.ARENA_CURRENCY_ITEM_ID, ResonantMonsterConfig.RIVAL_KILL_COINS, victim, true);
		}
		if (ResonantMonsterConfig.RIVAL_KILL_CACHE)
		{
			LuckyLootManager.getInstance().dropGuaranteedCache(victim, killer);
		}
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

	public static ResonantMonsterManager getInstance()
	{
		return SingletonHolder.INSTANCE;
	}

	private static class SingletonHolder
	{
		protected static final ResonantMonsterManager INSTANCE = new ResonantMonsterManager();
	}
}
