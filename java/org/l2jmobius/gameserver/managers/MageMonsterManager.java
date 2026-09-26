package org.l2jmobius.gameserver.managers;

import java.util.ArrayList;
import java.util.List;

import org.l2jmobius.commons.util.Rnd;
import org.l2jmobius.gameserver.config.custom.MageMonsterConfig;
import org.l2jmobius.gameserver.model.actor.Attackable;
import org.l2jmobius.gameserver.model.actor.Npc;
import org.l2jmobius.gameserver.model.actor.Player;
import org.l2jmobius.gameserver.model.actor.instance.Monster;
import org.l2jmobius.gameserver.model.effects.EffectType;
import org.l2jmobius.gameserver.model.skill.Skill;
import org.l2jmobius.gameserver.model.spawns.Spawn;

/**
 * Mage monsters (see {@link MageMonsterConfig}): like a champion, a regular monster that knows at least one magic damage spell has a small chance every time it spawns or respawns to become a Mage ({@link #tryConvert(Npc, Spawn)}, called from {@code Spawn} right after the Thief roll). A spawn
 * point that just produced a Mage skips the roll for its next {@link MageMonsterConfig#RESPAWN_COOLDOWN} spawns.
 * <ul>
 * <li>Combat: {@code AttackableAI#thinkMageAttack} casts the spells from {@link #getMagicDamageSkills(Npc)} back to back and kites, until the Mage's MP drops to {@link MageMonsterConfig#LOW_MANA_PERCENT}% ({@link #isOutOfMana(Npc)}).</li>
 * <li>Stats: max MP and cast speed are boosted in {@code Monster#getMaxMp()} / {@code Monster#getMAtkSpd()}.</li>
 * <li>Loot: drop and spoil amounts are multiplied in {@code NpcTemplate.calculateDrops()}, and {@link #onAttackableKilled(Attackable, Player)} (called from {@code Attackable#doDie()} alongside the other post-kill hooks) drops one Sealed Cache.</li>
 * </ul>
 */
public class MageMonsterManager
{
	protected MageMonsterManager()
	{
	}

	/**
	 * @param skill the skill to check
	 * @return {@code true} if {@code skill} is an active, targeted magic spell that deals damage
	 */
	public static boolean isMagicDamageSkill(Skill skill)
	{
		return (skill != null) && !skill.isPassive() && skill.isMagic() && !skill.isSuicideAttack() && (skill.getCastRange() > 0) && skill.hasEffectType(EffectType.MAGICAL_ATTACK, EffectType.HP_DRAIN, EffectType.DEATH_LINK);
	}

	/**
	 * @param npc the npc whose skills are checked
	 * @return every magic damage spell {@code npc} knows (template skills and skills granted by its AI archetype)
	 */
	public static List<Skill> getMagicDamageSkills(Npc npc)
	{
		final List<Skill> result = new ArrayList<>();
		for (Skill skill : npc.getAllSkills())
		{
			if (isMagicDamageSkill(skill))
			{
				result.add(skill);
			}
		}
		return result;
	}

	/**
	 * @param npc the Mage
	 * @return {@code true} once the Mage's MP is at or below {@link MageMonsterConfig#LOW_MANA_PERCENT} of its max MP: it stops casting and kiting and melees instead
	 */
	public static boolean isOutOfMana(Npc npc)
	{
		return npc.getCurrentMp() <= ((npc.getMaxMp() * MageMonsterConfig.LOW_MANA_PERCENT) / 100.0);
	}

	/**
	 * Rolls {@link MageMonsterConfig#SPAWN_CHANCE} for a freshly spawned npc and, if it hits, turns it into a Mage.
	 * @param npc the npc that just spawned or respawned
	 * @param spawn the spawn point it came from
	 */
	public void tryConvert(Npc npc, Spawn spawn)
	{
		if (!MageMonsterConfig.ENABLED || (npc == null) || !npc.isMonster() || (spawn == null))
		{
			return;
		}

		// This spawn point was a Mage recently - sit this spawn out.
		final int cooldown = spawn.getMageCooldown();
		if (cooldown > 0)
		{
			spawn.setMageCooldown(cooldown - 1);
			return;
		}

		final Monster monster = npc.asMonster();
		if (monster.isQuestMonster() || monster.getTemplate().isUndying() || monster.isRaid() || monster.isRaidMinion() || (monster.getLeader() != null) || monster.isFakePlayer())
		{
			return;
		}

		if (!MageMonsterConfig.ALLOW_IN_INSTANCES && (spawn.getInstanceId() != 0))
		{
			return;
		}

		if (!MageMonsterConfig.ALLOW_CHAMPIONS && (monster.getChampionTier() > 0))
		{
			return;
		}

		// Keep the special monster types apart.
		if (MageMonsterConfig.EXCLUDED_NPC_IDS.contains(monster.getId()) || monster.isThief() || monster.isWaveChallenge() || monster.getVariables().getBoolean("IS_ARENA_CHALLENGER", false) || monster.isHotzoneMiniboss())
		{
			return;
		}

		final int level = monster.getLevel();
		if ((level < MageMonsterConfig.MIN_LEVEL) || (level > MageMonsterConfig.MAX_LEVEL))
		{
			return;
		}

		// A Mage that can't cast anything would just be a normal monster with a tag.
		if (getMagicDamageSkills(monster).isEmpty())
		{
			return;
		}

		if ((Rnd.nextDouble() * 100) >= MageMonsterConfig.SPAWN_CHANCE)
		{
			return;
		}

		monster.startMage();
		spawn.setMageCooldown(MageMonsterConfig.RESPAWN_COOLDOWN);
	}

	/**
	 * Drops the Mage's guaranteed Sealed Cache.
	 * @param victim the attackable a player just killed
	 * @param killer the player credited with the kill
	 */
	public void onAttackableKilled(Attackable victim, Player killer)
	{
		if (!MageMonsterConfig.ENABLED || !MageMonsterConfig.CACHE_ON_DEATH || (victim == null) || (killer == null) || !victim.isMonster() || !victim.asMonster().isMageMonster())
		{
			return;
		}

		LuckyLootManager.getInstance().dropGuaranteedCache(victim, killer);
	}

	public static MageMonsterManager getInstance()
	{
		return SingletonHolder.INSTANCE;
	}

	private static class SingletonHolder
	{
		protected static final MageMonsterManager INSTANCE = new MageMonsterManager();
	}
}
