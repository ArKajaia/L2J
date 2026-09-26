package org.l2jmobius.gameserver.managers;

import org.l2jmobius.commons.util.Rnd;
import org.l2jmobius.gameserver.config.custom.MonsterRageConfig;
import org.l2jmobius.gameserver.model.actor.Creature;
import org.l2jmobius.gameserver.model.actor.instance.Monster;
import org.l2jmobius.gameserver.model.skill.Skill;

/**
 * Monster Rage (see {@link MonsterRageConfig}): every stun or hold a player (or their summon) lands on a monster is counted ({@link #onDisableLanded(Creature, Creature)}, called from the Stun, Root and Paralyze effect handlers). Past
 * {@link MonsterRageConfig#DISABLES_REQUIRED} each further one rolls {@link MonsterRageConfig#CHANCE} to make the monster rage ({@link Monster#startRage()}). While raging, {@link #resistsNegativeEffect(Creature, Creature, Skill)} (checked in {@code Formulas.calcEffectSuccess()}) makes it
 * shrug off negative effects.
 */
public class MonsterRageManager
{
	protected MonsterRageManager()
	{
	}

	/**
	 * A stun or hold just took hold of {@code effected}: count it and, past the threshold, roll for a rage.
	 * @param effector whoever applied the stun/hold
	 * @param effected the creature that got stunned/held
	 */
	public void onDisableLanded(Creature effector, Creature effected)
	{
		if (!MonsterRageConfig.ENABLED || (effector == null) || (effected == null) || !effected.isMonster() || effected.isDead())
		{
			return;
		}

		// Only players and their summons count - monsters disabling each other don't.
		if (effector.asPlayer() == null)
		{
			return;
		}

		final Monster monster = effected.asMonster();
		if (!canRage(monster))
		{
			return;
		}

		final int disables = monster.addRageDisable();
		if ((disables <= MonsterRageConfig.DISABLES_REQUIRED) || monster.isRaging())
		{
			return;
		}

		if ((Rnd.nextDouble() * 100) >= MonsterRageConfig.CHANCE)
		{
			return;
		}

		monster.startRage();
	}

	/**
	 * @param effector whoever is applying the effect
	 * @param effected the creature it would land on
	 * @param skill the skill carrying the effect
	 * @return {@code true} if {@code effected} is a raging monster and resists this negative effect
	 */
	public boolean resistsNegativeEffect(Creature effector, Creature effected, Skill skill)
	{
		if (!MonsterRageConfig.ENABLED || (effected == null) || (effector == effected) || !effected.isMonster() || !effected.asMonster().isRaging())
		{
			return false;
		}

		if (!skill.isDebuff() && !skill.hasNegativeEffect())
		{
			return false;
		}

		return (Rnd.nextDouble() * 100) < MonsterRageConfig.DEBUFF_RESISTANCE;
	}

	private boolean canRage(Monster monster)
	{
		if (MonsterRageConfig.EXCLUDED_NPC_IDS.contains(monster.getId()) || monster.isFakePlayer())
		{
			return false;
		}

		return MonsterRageConfig.ALLOW_RAIDS || !monster.isRaid();
	}

	public static MonsterRageManager getInstance()
	{
		return SingletonHolder.INSTANCE;
	}

	private static class SingletonHolder
	{
		protected static final MonsterRageManager INSTANCE = new MonsterRageManager();
	}
}
