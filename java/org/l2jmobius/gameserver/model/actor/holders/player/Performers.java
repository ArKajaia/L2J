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
package org.l2jmobius.gameserver.model.actor.holders.player;

import org.l2jmobius.gameserver.config.custom.PerformersConfig;
import org.l2jmobius.gameserver.model.actor.Creature;
import org.l2jmobius.gameserver.model.actor.Player;
import org.l2jmobius.gameserver.model.actor.enums.player.PlayerClass;
import org.l2jmobius.gameserver.model.skill.Skill;

/**
 * The Swordsinger and Bladedancer lines. Glittering Medals (CustomBuffBook) teach every class the songs and dances, so the performers get what a medal doesn't:
 * <ul>
 * <li>Performances, Stances and the 3rd class finales: their own skills (data/stats/skills/custom/performer_skills.xml, in their class skill trees), see the PerformanceAura effect.</li>
 * <li>Virtuoso: songs and dances they cast last longer, and singing one more doesn't cost them extra MP.</li>
 * </ul>
 * Everything follows the active class, so a performer subclass counts and a performer playing another subclass doesn't. A roaming fake player of a performer class counts too.
 * @author Mobius
 */
public class Performers
{
	private Performers()
	{
	}

	/**
	 * @param player the player
	 * @return 1 for a Swordsinger or Bladedancer, 2 for a Sword Muse or Spectral Dancer, 0 otherwise
	 */
	public static int getPerformerTier(Player player)
	{
		return player != null ? getPerformerTier(player.getPlayerClass()) : 0;
	}

	/**
	 * @param playerClass the active class
	 * @return 1 for a Swordsinger or Bladedancer, 2 for a Sword Muse or Spectral Dancer, 0 otherwise
	 */
	private static int getPerformerTier(PlayerClass playerClass)
	{
		if (!PerformersConfig.PERFORMERS_ENABLED || (playerClass == null))
		{
			return 0;
		}

		if (playerClass.equalsOrChildOf(PlayerClass.SWORDSINGER) || playerClass.equalsOrChildOf(PlayerClass.BLADEDANCER))
		{
			return playerClass.level() - 1;
		}

		return 0;
	}

	private static int getPerformerTier(Creature creature)
	{
		if (creature == null)
		{
			return 0;
		}

		if (creature.isPlayer())
		{
			return getPerformerTier(creature.asPlayer());
		}

		return creature.isPvpFakePlayer() ? getPerformerTier(creature.asNpc().getTemplate().getFakePlayerPvpProfile().getPlayerClass()) : 0;
	}

	/**
	 * Virtuoso: how much longer a song or dance cast by a performer lasts.
	 * @param caster the character casting the skill
	 * @param skill the skill
	 * @return the duration multiplier, 1 if the skill isn't a song or dance or the caster isn't a performer
	 */
	public static double getDanceDurationMultiplier(Creature caster, Skill skill)
	{
		if (!skill.isDance())
		{
			return 1;
		}

		final int tier = getPerformerTier(caster);
		return tier > 0 ? PerformersConfig.DANCE_DURATION_MULTIPLIER[Math.min(tier, PerformersConfig.DANCE_DURATION_MULTIPLIER.length) - 1] : 1;
	}

	/**
	 * Virtuoso: a performer doesn't pay extra MP for a song or dance while others are in effect.
	 * @param caster the character casting the song or dance
	 * @return {@code true} if the extra MP is waived
	 */
	public static boolean ignoresDanceStackingCost(Creature caster)
	{
		return PerformersConfig.NO_DANCE_STACKING_MP_COST && (getPerformerTier(caster) > 0);
	}
}
