package org.l2jmobius.gameserver.managers;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import org.l2jmobius.commons.threads.ThreadPool;
import org.l2jmobius.gameserver.config.custom.CancelReturnConfig;
import org.l2jmobius.gameserver.model.actor.Creature;
import org.l2jmobius.gameserver.model.actor.Player;
import org.l2jmobius.gameserver.model.skill.AbnormalType;
import org.l2jmobius.gameserver.model.skill.BuffInfo;
import org.l2jmobius.gameserver.model.skill.Skill;

/**
 * Single home for the "cancelled buffs come back after {@link CancelReturnConfig#TIME_TO_RETURN}" rule, shared by every effect handler that strips buffs (DispelAll - NPC/BOSS Cancel Magic -, DispelByCategory, DispelBySlotProbability) so they can't drift apart again.
 * <p>
 * Usage from a handler: check {@link #isEligible}, {@link #snapshot} the buffs about to be removed, remove them as normal, then {@link #scheduleReturn}.
 */
public class CancelReturnManager
{
	/** A buff as it was at the moment it got cancelled. */
	public static class CanceledBuff
	{
		final Skill skill;
		final int secondsLeft;

		CanceledBuff(Skill skill, int secondsLeft)
		{
			this.skill = skill;
			this.secondsLeft = secondsLeft;
		}
	}

	protected CancelReturnManager()
	{
	}

	/**
	 * @param effector whoever cast the cancel (may be null for environment effects)
	 * @param effected whoever lost the buffs
	 * @return {@code true} if buffs removed from {@code effected} by {@code effector} should be given back
	 */
	public boolean isEligible(Creature effector, Creature effected)
	{
		if (!CancelReturnConfig.CANCEL_RETURN_ON || (effected == null) || !effected.isPlayer())
		{
			return false;
		}

		if (effector != null)
		{
			if (effector.isPlayable() ? !CancelReturnConfig.CANCEL_RETURN_PLAYER : !CancelReturnConfig.CANCEL_RETURN_MOB)
			{
				return false;
			}
		}

		return CancelReturnConfig.CANCEL_RETURN_PLAYER_OLYS || !effected.asPlayer().isInOlympiadMode();
	}

	/**
	 * Captures skill + remaining time of every returnable buff. Must run before or right after removal - {@link BuffInfo#getTime()} keeps counting from the original start either way. Debuffs, toggles and passives are never returned (cleanse skills also go through the dispel handlers, and handing a
	 * cleansed debuff back would be the opposite of what the player wanted).
	 * @param infos buffs about to be (or just) removed
	 * @return the returnable subset, possibly empty
	 */
	public List<CanceledBuff> snapshot(Collection<BuffInfo> infos)
	{
		final List<CanceledBuff> result = new ArrayList<>();
		for (BuffInfo info : infos)
		{
			final Skill skill = info.getSkill();
			if ((skill == null) || skill.isPassive() || skill.isToggle() || skill.isDebuff() || (skill.getEffectPoint() < 0) || (info.getAbnormalTime() <= 0))
			{
				continue;
			}

			final int secondsLeft = info.getTime();
			if (secondsLeft > 0)
			{
				result.add(new CanceledBuff(skill, secondsLeft));
			}
		}
		return result;
	}

	/**
	 * Re-applies the snapshotted buffs after {@link CancelReturnConfig#TIME_TO_RETURN}, each with the time it had left when cancelled. Skips anything the player has already re-acquired (same skill, or an equal/stronger buff in the same abnormal slot), and skips everything if the player died or logged
	 * out meanwhile.
	 * @param player the player who lost the buffs
	 * @param buffs result of {@link #snapshot}
	 */
	public void scheduleReturn(Player player, List<CanceledBuff> buffs)
	{
		if ((player == null) || buffs.isEmpty())
		{
			return;
		}

		ThreadPool.schedule(() ->
		{
			if (!player.isOnline() || player.isDead())
			{
				return;
			}

			boolean restored = false;
			for (CanceledBuff buff : buffs)
			{
				if (player.getEffectList().getBuffInfoBySkillId(buff.skill.getId()) != null)
				{
					continue;
				}

				final AbnormalType type = buff.skill.getAbnormalType();
				if ((type != null) && (type != AbnormalType.NONE))
				{
					final BuffInfo current = player.getEffectList().getBuffInfoByAbnormalType(type);
					if ((current != null) && (current.getSkill().getAbnormalLevel() >= buff.skill.getAbnormalLevel()))
					{
						continue;
					}
				}

				buff.skill.applyEffects(player, player);

				final BuffInfo newInfo = player.getEffectList().getBuffInfoBySkillId(buff.skill.getId());
				if (newInfo != null)
				{
					newInfo.setAbnormalTime(buff.secondsLeft);
					restored = true;
				}
			}

			if (restored)
			{
				// Icons were sent with the full duration on apply - resend with the real remaining time.
				player.getEffectList().updateEffectIcons(false);
				player.sendMessage("Your cancelled buffs have been restored.");
			}
		}, CancelReturnConfig.TIME_TO_RETURN);
	}

	public static CancelReturnManager getInstance()
	{
		return SingletonHolder.INSTANCE;
	}

	private static class SingletonHolder
	{
		protected static final CancelReturnManager INSTANCE = new CancelReturnManager();
	}
}
