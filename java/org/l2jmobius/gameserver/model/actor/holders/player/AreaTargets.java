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

import java.util.ArrayList;
import java.util.List;

import org.l2jmobius.gameserver.ai.Intention;
import org.l2jmobius.gameserver.geoengine.GeoEngine;
import org.l2jmobius.gameserver.managers.FakePartyManager;
import org.l2jmobius.gameserver.model.Location;
import org.l2jmobius.gameserver.model.World;
import org.l2jmobius.gameserver.model.WorldObject;
import org.l2jmobius.gameserver.model.actor.Creature;
import org.l2jmobius.gameserver.model.actor.Player;
import org.l2jmobius.gameserver.model.actor.holders.npc.FakePlayerParty;
import org.l2jmobius.gameserver.model.skill.BuffInfo;
import org.l2jmobius.gameserver.model.skill.Skill;
import org.l2jmobius.gameserver.model.skill.enums.FlyType;
import org.l2jmobius.gameserver.model.zone.ZoneId;
import org.l2jmobius.gameserver.network.serverpackets.FlyToLocation;
import org.l2jmobius.gameserver.network.serverpackets.ValidateLocation;

/**
 * Who an aura of the Oracles and Totem Warchiefs reaches: the caster's party (players and fake players, with their summons) and the enemies it could hit with an area skill, the same rules as the performers' auras.
 * @author Mobius
 */
public class AreaTargets
{
	private AreaTargets()
	{
	}

	/**
	 * @param caster the caster
	 * @return the caster, its party members (players and fake players) and every summon among them
	 */
	public static List<Creature> getParty(Creature caster)
	{
		final List<? extends Creature> members;
		final FakePlayerParty fakeParty = FakePartyManager.getInstance().getParty(caster);
		final Player player = caster.isPlayer() ? caster.asPlayer() : null;
		if (fakeParty != null)
		{
			members = fakeParty.getMembers(); // The players of the party and its fake players.
		}
		else if ((player != null) && player.isInParty())
		{
			members = player.getParty().getMembers();
		}
		else
		{
			members = List.of(caster);
		}

		final List<Creature> result = new ArrayList<>(members.size() + 2);
		for (Creature member : members)
		{
			result.add(member);
			if (member.isPlayer() && member.asPlayer().hasSummon())
			{
				result.add(member.asPlayer().getSummon());
			}
		}

		if (!result.contains(caster))
		{
			result.add(caster);
		}

		return result;
	}

	/**
	 * @param caster the caster
	 * @param target a character
	 * @return {@code true} if the target is the caster, one of its party members or their summon
	 */
	public static boolean isPartyMember(Creature caster, Creature target)
	{
		return (target == caster) || getParty(caster).contains(target);
	}

	/**
	 * @param caster the caster (a player or a fake player)
	 * @param target a character near it
	 * @param skill the skill that would reach it
	 * @return {@code true} for an enemy the caster could hit without forcing it: a monster, or a player (or fake player) it may fight, in sight, outside a peace zone, never its own party, clan or alliance
	 */
	public static boolean isEnemy(Creature caster, Creature target, Skill skill)
	{
		if ((target == null) || (target == caster) || target.isAlikeDead() || target.isInvul() || !(target.isAttackable() || target.isPlayable()) || target.isInsideZone(ZoneId.PEACE) || caster.isInsideZone(ZoneId.PEACE))
		{
			return false;
		}

		// A fake player: like its area skills.
		if (caster.isPvpFakePlayer())
		{
			return Skill.checkForAreaOffensiveSkills(caster, target, skill, false);
		}

		if (target.isPlayable())
		{
			final Player player = caster.asPlayer();
			final Player other = target.asPlayer();
			if ((player == null) || (other == null) || (player == other))
			{
				return false;
			}

			// An Olympiad or duel opponent is fair game even from the same clan.
			final boolean olympiad = player.isInOlympiadMode() && other.isInOlympiadMode() && (player.getOlympiadGameId() == other.getOlympiadGameId());
			final boolean duel = player.isInDuel() && other.isInDuel() && (player.getDuelId() == other.getDuelId());
			if (!olympiad && !duel)
			{
				if (player.isInParty() && (player.getParty() == other.getParty()))
				{
					return false;
				}

				if ((player.getClanId() > 0) && (player.getClanId() == other.getClanId()))
				{
					return false;
				}

				if ((player.getAllyId() > 0) && (player.getAllyId() == other.getAllyId()))
				{
					return false;
				}
			}
		}

		return target.isAutoAttackable(caster) && GeoEngine.getInstance().canSeeTarget(caster, target);
	}

	/**
	 * @param caster the caster
	 * @param center where the area is
	 * @param range how far it reaches around the center
	 * @param skill the skill that would reach them
	 * @return the caster's enemies around the center, see {@link #isEnemy}
	 */
	public static List<Creature> getEnemies(Creature caster, WorldObject center, int range, Skill skill)
	{
		final List<Creature> result = new ArrayList<>();
		for (Creature target : World.getInstance().getVisibleObjectsInRange(center, Creature.class, range))
		{
			if (isEnemy(caster, target, skill))
			{
				result.add(target);
			}
		}

		return result;
	}

	/**
	 * Puts a short echo buff or hex on a target, or extends the one this caster already put there, so a pulse every few seconds doesn't send a message each time.
	 * @param caster the caster the echo comes from
	 * @param target the character in range
	 * @param echo the echo skill
	 */
	public static void keepEcho(Creature caster, Creature target, Skill echo)
	{
		final BuffInfo info = target.getEffectList().getBuffInfoByAbnormalType(echo.getAbnormalType());
		if (info != null)
		{
			if ((info.getSkill().getId() == echo.getId()) && (info.getSkill().getLevel() == echo.getLevel()) && (info.getEffector() == caster))
			{
				// Extend it when it's half gone; the icon gets its full time back.
				final int remaining = info.getTime();
				if (remaining <= (echo.getAbnormalTime() / 2))
				{
					info.setAbnormalTime((info.getAbnormalTime() - remaining) + echo.getAbnormalTime());
					target.getEffectList().updateEffectIcons(false);
				}
				return;
			}

			// Another caster's echo of the same kind, as strong or stronger, keeps the slot.
			if (info.getSkill().getAbnormalLevel() >= echo.getAbnormalLevel())
			{
				return;
			}
		}

		echo.applyEffects(caster, target);
	}

	/**
	 * Moves a character a short way at once, like Blink: it flies there, stopping at a wall.
	 * @param creature the character
	 * @param x where to
	 * @param y where to
	 * @param z where to
	 */
	public static void flyTo(Creature creature, int x, int y, int z)
	{
		final Location destination = GeoEngine.getInstance().getValidLocation(creature.getX(), creature.getY(), creature.getZ(), x, y, z, creature.getInstanceId());
		creature.getAI().setIntention(Intention.IDLE);
		creature.broadcastPacket(new FlyToLocation(creature, destination, FlyType.DUMMY));
		creature.abortAttack();
		creature.abortCast();
		creature.setXYZ(destination);
		creature.broadcastPacket(new ValidateLocation(creature));
	}
}
