package org.l2jmobius.gameserver.managers;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;

import org.l2jmobius.commons.util.Rnd;
import org.l2jmobius.gameserver.config.PlayerConfig;
import org.l2jmobius.gameserver.config.custom.WaveChallengeConfig;
import org.l2jmobius.gameserver.model.actor.Attackable;
import org.l2jmobius.gameserver.model.actor.Npc;
import org.l2jmobius.gameserver.model.actor.Player;
import org.l2jmobius.gameserver.model.actor.holders.npc.AggroInfo;
import org.l2jmobius.gameserver.model.actor.instance.Chest;
import org.l2jmobius.gameserver.model.actor.instance.Monster;
import org.l2jmobius.gameserver.model.item.enums.ItemProcessType;

/**
 * Open-world counterpart of the Survival Arena: rolls regular monster spawns into {@link WaveChallengeConfig#WAVE_COUNT}-wave challenges (see the "Wave Challenge System" section of {@link Monster}) and pays out once the final wave dies. The payout is the Survival Arena reward
 * ({@link Monster#calculateArenaReward(int)}) of an equivalent arena wave, {@code (finalLevel - COIN_LEVEL_OFFSET) / COIN_LEVEL_DIVISOR}, split evenly between every player who damaged the monster.
 * <p>
 * {@link #tryConvert} is called from {@code Spawn#initializeNpc()} right after the champion roll, so only regular spawns (not script/quest {@code addSpawn} calls) can convert. {@link #onAttackableKilled} is called from {@code Attackable#doDie()} alongside the hotzone hooks, while the
 * aggro list is still intact.
 */
public class WaveChallengeManager
{
	protected WaveChallengeManager()
	{
	}

	/**
	 * Rolls {@link WaveChallengeConfig#SPAWN_CHANCE} for a freshly spawned npc and, if it hits, turns it into wave 1 of a challenge.
	 * @param npc the npc that just spawned
	 * @param instanceId the instance of the spawn it came from
	 */
	public void tryConvert(Npc npc, int instanceId)
	{
		if (!WaveChallengeConfig.ENABLED || (npc == null) || !npc.isMonster())
		{
			return;
		}

		final Monster monster = npc.asMonster();
		if (monster.isQuestMonster() || monster.getTemplate().isUndying() || monster.isRaid() || monster.isRaidMinion() || (monster.getLeader() != null) || monster.isFakePlayer() || (monster instanceof Chest))
		{
			return;
		}

		if (!WaveChallengeConfig.ALLOW_IN_INSTANCES && (instanceId != 0))
		{
			return;
		}

		if (!WaveChallengeConfig.ALLOW_CHAMPIONS && (monster.getChampionTier() > 0))
		{
			return;
		}

		if (WaveChallengeConfig.EXCLUDED_NPC_IDS.contains(monster.getId()) || monster.getVariables().getBoolean("IS_ARENA_CHALLENGER", false) || monster.isHotzoneMiniboss())
		{
			return;
		}

		final int level = monster.getLevel();
		if ((level < WaveChallengeConfig.MIN_LEVEL) || (level > WaveChallengeConfig.MAX_LEVEL))
		{
			return;
		}

		if ((Rnd.nextDouble() * 100) >= WaveChallengeConfig.SPAWN_CHANCE)
		{
			return;
		}

		monster.startWaveChallenge();
	}

	/**
	 * Pays the Dragon coin reward if {@code victim} was a wave challenge that died on its final wave.
	 * @param victim the attackable that just died
	 */
	public void onAttackableKilled(Attackable victim)
	{
		if (!WaveChallengeConfig.ENABLED || (victim == null) || !victim.isMonster())
		{
			return;
		}

		final Monster monster = victim.asMonster();
		if (!monster.isWaveChallengeFinalWave() || monster.getVariables().getBoolean("WAVE_CHALLENGE_PAID", false))
		{
			return;
		}
		monster.getVariables().set("WAVE_CHALLENGE_PAID", true);

		final int finalLevel = monster.getLevel();
		final int arenaWave = Math.max(WaveChallengeConfig.MIN_ARENA_WAVE, (finalLevel - WaveChallengeConfig.COIN_LEVEL_OFFSET) / WaveChallengeConfig.COIN_LEVEL_DIVISOR);
		final long totalCoins = Monster.calculateArenaReward(arenaWave);
		if (totalCoins <= 0)
		{
			return;
		}

		// Same eligibility rules Attackable#calculateRewards() uses for exp: a player (summon
		// damage counts for its owner) who dealt real damage and is still within party range.
		final Map<Player, Long> damageByPlayer = new LinkedHashMap<>();
		for (AggroInfo info : monster.getAggroList().values())
		{
			if ((info == null) || (info.getAttacker() == null) || (info.getDamage() <= 1))
			{
				continue;
			}

			final Player player = info.getAttacker().asPlayer();
			if ((player == null) || !player.isOnline() || (monster.calculateDistance3D(player) > PlayerConfig.ALT_PARTY_RANGE))
			{
				continue;
			}

			damageByPlayer.merge(player, info.getDamage(), Long::sum);
		}

		if (damageByPlayer.isEmpty())
		{
			return;
		}

		// Even split; any remainder goes one coin each to the top damage dealers.
		final List<Entry<Player, Long>> ranked = new ArrayList<>(damageByPlayer.entrySet());
		ranked.sort((a, b) -> Long.compare(b.getValue(), a.getValue()));

		final long share = totalCoins / ranked.size();
		long remainder = totalCoins % ranked.size();
		for (Entry<Player, Long> entry : ranked)
		{
			final Player player = entry.getKey();
			long amount = share;
			if (remainder > 0)
			{
				amount++;
				remainder--;
			}

			if (amount > 0)
			{
				player.addItem(ItemProcessType.REWARD, WaveChallengeConfig.COIN_ITEM_ID, amount, monster, true);
			}
			player.sendMessage("Wave challenge complete! " + monster.getName() + " (level " + finalLevel + ") paid out an arena wave " + arenaWave + " reward of " + totalCoins + " coins, split between " + ranked.size() + " player(s).");
		}
	}

	public static WaveChallengeManager getInstance()
	{
		return SingletonHolder.INSTANCE;
	}

	private static class SingletonHolder
	{
		protected static final WaveChallengeManager INSTANCE = new WaveChallengeManager();
	}
}
