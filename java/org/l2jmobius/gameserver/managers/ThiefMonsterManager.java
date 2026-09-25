package org.l2jmobius.gameserver.managers;

import org.l2jmobius.commons.util.Rnd;
import org.l2jmobius.gameserver.config.custom.ThiefMonsterConfig;
import org.l2jmobius.gameserver.model.World;
import org.l2jmobius.gameserver.model.actor.Attackable;
import org.l2jmobius.gameserver.model.actor.Npc;
import org.l2jmobius.gameserver.model.actor.instance.Monster;
import org.l2jmobius.gameserver.model.spawns.Spawn;

/**
 * Thief monsters (see {@link ThiefMonsterConfig}): like a champion, a regular monster has a small chance every time it spawns or respawns to become a Thief ({@link #tryConvert(Npc, Spawn)}, called from {@code Spawn} right after the wave challenge roll). A spawn point that just produced a
 * Thief skips the roll for its next {@link ThiefMonsterConfig#RESPAWN_COOLDOWN} spawns, so the same monster doesn't come straight back as a Thief. Every monster a player kills near a living Thief fills its loot bag
 * ({@link #onAttackableKilled(Attackable)}, called from {@code Attackable#doDie()} alongside the other post-kill hooks), and a fuller bag multiplies the Thief's own adena drop (read in {@code NpcTemplate.calculateDrops()} through {@link Monster#getThiefAdenaMultiplier()}).
 */
public class ThiefMonsterManager
{
	protected ThiefMonsterManager()
	{
	}

	/**
	 * Rolls {@link ThiefMonsterConfig#SPAWN_CHANCE} for a freshly spawned npc and, if it hits, turns it into a Thief with an empty bag.
	 * @param npc the npc that just spawned or respawned
	 * @param spawn the spawn point it came from
	 */
	public void tryConvert(Npc npc, Spawn spawn)
	{
		if (!ThiefMonsterConfig.ENABLED || (npc == null) || !npc.isMonster() || (spawn == null))
		{
			return;
		}

		// This spawn point was a Thief recently - sit this spawn out.
		final int cooldown = spawn.getThiefCooldown();
		if (cooldown > 0)
		{
			spawn.setThiefCooldown(cooldown - 1);
			return;
		}

		final int instanceId = spawn.getInstanceId();

		final Monster monster = npc.asMonster();
		if (monster.isQuestMonster() || monster.getTemplate().isUndying() || monster.isRaid() || monster.isRaidMinion() || (monster.getLeader() != null) || monster.isFakePlayer())
		{
			return;
		}

		if (!ThiefMonsterConfig.ALLOW_IN_INSTANCES && (instanceId != 0))
		{
			return;
		}

		if (!ThiefMonsterConfig.ALLOW_CHAMPIONS && (monster.getChampionTier() > 0))
		{
			return;
		}

		// A wave challenge drops no adena, so there would be nothing to multiply.
		if (ThiefMonsterConfig.EXCLUDED_NPC_IDS.contains(monster.getId()) || monster.isWaveChallenge() || monster.getVariables().getBoolean("IS_ARENA_CHALLENGER", false) || monster.isHotzoneMiniboss())
		{
			return;
		}

		final int level = monster.getLevel();
		if ((level < ThiefMonsterConfig.MIN_LEVEL) || (level > ThiefMonsterConfig.MAX_LEVEL))
		{
			return;
		}

		if ((Rnd.nextDouble() * 100) >= ThiefMonsterConfig.SPAWN_CHANCE)
		{
			return;
		}

		monster.startThief();
		spawn.setThiefCooldown(ThiefMonsterConfig.RESPAWN_COOLDOWN);
	}

	/**
	 * Fills the bag of every living Thief within {@link ThiefMonsterConfig#DETECTION_RADIUS} of {@code victim}.
	 * @param victim the attackable a player just killed
	 */
	public void onAttackableKilled(Attackable victim)
	{
		if (!ThiefMonsterConfig.ENABLED || (victim == null) || !victim.isMonster())
		{
			return;
		}

		World.getInstance().forEachVisibleObjectInRange(victim, Monster.class, ThiefMonsterConfig.DETECTION_RADIUS, monster ->
		{
			if ((monster != victim) && monster.isThief() && !monster.isDead() && (monster.getInstanceId() == victim.getInstanceId()))
			{
				monster.addThiefKill();
			}
		});
	}

	public static ThiefMonsterManager getInstance()
	{
		return SingletonHolder.INSTANCE;
	}

	private static class SingletonHolder
	{
		protected static final ThiefMonsterManager INSTANCE = new ThiefMonsterManager();
	}
}
