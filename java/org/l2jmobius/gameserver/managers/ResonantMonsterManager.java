package org.l2jmobius.gameserver.managers;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import org.l2jmobius.commons.util.Rnd;
import org.l2jmobius.gameserver.config.PlayerConfig;
import org.l2jmobius.gameserver.config.custom.ResonantMonsterConfig;
import org.l2jmobius.gameserver.data.enums.AbsorbCrystalType;
import org.l2jmobius.gameserver.data.holders.LevelingSoulCrystalInfo;
import org.l2jmobius.gameserver.data.holders.SoulCrystal;
import org.l2jmobius.gameserver.data.xml.LevelUpCrystalData;
import org.l2jmobius.gameserver.data.xml.NpcData;
import org.l2jmobius.gameserver.model.actor.Attackable;
import org.l2jmobius.gameserver.model.actor.Npc;
import org.l2jmobius.gameserver.model.actor.Player;
import org.l2jmobius.gameserver.model.actor.instance.Chest;
import org.l2jmobius.gameserver.model.actor.instance.Monster;
import org.l2jmobius.gameserver.model.actor.templates.NpcTemplate;
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
 * produced one skips the roll for its next {@link ResonantMonsterConfig#RESPAWN_COOLDOWN} spawns.
 * <ul>
 * <li>Combat: it has more HP, and the first time it drops low its soul flees ({@code Monster#tryResonantFlight()}).</li>
 * <li>Reward: {@link #onAttackableKilled(Attackable, Player)} (called from {@code Attackable#doDie()} alongside the other post-kill hooks) raises the soul crystal of the killer and of every party member nearby by one stage, with the retail rules of quest 350 Enhance Your Weapon (the
 * quest started, exactly one soul crystal carried) and the retail messages. No crystal use on the monster is needed. A crystal only grows if the regular monsters of the Resonant monster's level could raise it ({@link #getStageCap(int)}); stages above
 * {@link ResonantMonsterConfig#MAX_CRYSTAL_STAGE} stay with the raid bosses, as in retail. Quest 350 itself skips Resonant monsters, so a crystal never grows twice from one kill.</li>
 * </ul>
 */
public class ResonantMonsterManager
{
	/** The quest whose rules a soul crystal follows. */
	private static final String SOUL_CRYSTAL_QUEST = "Q00350_EnhanceYourWeapon";

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
		return (level >= ResonantMonsterConfig.MIN_LEVEL) && (level <= ResonantMonsterConfig.MAX_LEVEL) && (getStageCap(level) >= 0);
	}

	/**
	 * The highest soul crystal stage a Resonant monster of {@code level} can raise: the highest stage any regular (non-raid, last hit) monster of that level or lower raises in {@code data/LevelUpCrystalData.xml}, but no higher than
	 * {@link ResonantMonsterConfig#MAX_CRYSTAL_STAGE}. With the retail data that is stage 1 at level 40, 4 at 50, 7 at 60 and 9 from 63 on.
	 * @param level the monster level
	 * @return the highest stage that can still grow, or -1 if no regular monster of that level or lower raises a crystal
	 */
	public static int getStageCap(int level)
	{
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

		int cap = -1;
		for (Map.Entry<Integer, Integer> entry : capByLevel.headMap(level, true).entrySet())
		{
			cap = Math.max(cap, entry.getValue());
		}
		return Math.min(cap, ResonantMonsterConfig.MAX_CRYSTAL_STAGE);
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

		if (Rnd.get(100) >= ResonantMonsterConfig.CHANCE)
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

	public static ResonantMonsterManager getInstance()
	{
		return SingletonHolder.INSTANCE;
	}

	private static class SingletonHolder
	{
		protected static final ResonantMonsterManager INSTANCE = new ResonantMonsterManager();
	}
}
