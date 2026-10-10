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
import org.l2jmobius.gameserver.config.custom.FakePlayersConfig;
import org.l2jmobius.gameserver.geoengine.GeoEngine;
import org.l2jmobius.gameserver.managers.FakeClanManager;
import org.l2jmobius.gameserver.managers.FakePartyManager;
import org.l2jmobius.gameserver.model.Location;
import org.l2jmobius.gameserver.model.World;
import org.l2jmobius.gameserver.model.WorldObject;
import org.l2jmobius.gameserver.model.actor.Creature;
import org.l2jmobius.gameserver.model.actor.Player;
import org.l2jmobius.gameserver.model.actor.holders.npc.FakePlayerParty;
import org.l2jmobius.gameserver.model.actor.instance.FakePlayerPvpServitor;
import org.l2jmobius.gameserver.model.skill.BuffInfo;
import org.l2jmobius.gameserver.model.skill.Skill;
import org.l2jmobius.gameserver.model.skill.enums.FlyType;
import org.l2jmobius.gameserver.model.skill.holders.SkillUseHolder;
import org.l2jmobius.gameserver.model.zone.ZoneId;
import org.l2jmobius.gameserver.network.serverpackets.FlyToLocation;
import org.l2jmobius.gameserver.network.serverpackets.ValidateLocation;
import org.l2jmobius.gameserver.taskmanagers.AttackStanceTaskManager;

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
		return isEnemy(caster, target, skill, caster);
	}

	/**
	 * @param caster the caster (a player or a fake player)
	 * @param target a character near it
	 * @param skill the skill that would reach it
	 * @param origin where the area is (a totem, the caster...): the target must be in its sight, not the caster's
	 * @return {@code true} for an enemy the caster could hit without forcing it, see {@link #isEnemy(Creature, Creature, Skill)}
	 */
	public static boolean isEnemy(Creature caster, Creature target, Skill skill, WorldObject origin)
	{
		return isEnemy(caster, target, skill, origin, null);
	}

	/**
	 * Who a player's totem hits (its pulses and Shatter): monsters and the players and fake players with PvP status (flagged, PK, clan war), and the other players and fake players too when it was planted (or Shatter cast) with Ctrl held, see {@link #isEnemy(Creature, Creature, Skill, WorldObject, Boolean)}.
	 * @param caster the totem's Warchief
	 * @param target a character near the totem
	 * @param skill the skill that would reach it
	 * @param origin the totem
	 * @param forced {@code true} with Ctrl held
	 * @return {@code true} for an enemy the totem hits
	 */
	public static boolean isTotemEnemy(Creature caster, Creature target, Skill skill, WorldObject origin, boolean forced)
	{
		return isEnemy(caster, target, skill, origin, forced);
	}

	/**
	 * @param caster the caster (a player or a fake player)
	 * @param target a character near it
	 * @param skill the skill that would reach it
	 * @param origin where the area is: the target must be in its sight
	 * @param forced {@code null} for an area of the caster's own (the characters it may fight without Ctrl), {@code FALSE} for a player's totem planted without Ctrl (monsters and the players and fake players with PvP status), {@code TRUE} for one planted with Ctrl (also the white
	 *            players and fake players it could force-attack). A fake player's areas always hit what its area skills hit.
	 * @return {@code true} for an enemy
	 */
	private static boolean isEnemy(Creature caster, Creature target, Skill skill, WorldObject origin, Boolean forced)
	{
		if ((target == null) || (target == caster) || target.isAlikeDead() || target.isInvul() || !(target.isAttackable() || target.isPlayable()) || target.isInsideZone(ZoneId.PEACE) || caster.isInsideZone(ZoneId.PEACE))
		{
			return false;
		}

		// A fake player: like its area skills.
		if (caster.isPvpFakePlayer())
		{
			return Skill.checkForAreaOffensiveSkills(caster, target, skill, false, origin);
		}

		boolean force = false;
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
				// A totem planted with Ctrl hits a player without PvP status too, as a force attack. Without Ctrl, only one it may fight anyway (flagged, PK, clan war).
				if ((forced != null) && forced && !target.isAutoAttackable(caster))
				{
					if (player.isInOlympiadMode() || other.inObserverMode() || other.isInvisible())
					{
						return false;
					}
					force = true;
				}

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
		else if ((forced != null) && isFakePerson(target))
		{
			// A totem: a fake player (and its servitor) with PvP status (flagged, PK, clan war), or any with Ctrl as a force attack; never its own party or clan.
			final Creature fake = target instanceof FakePlayerPvpServitor ? ((FakePlayerPvpServitor) target).getOwner() : target;
			if (FakePartyManager.getInstance().isSameGroup(caster, fake) || (FakeClanManager.getInstance().isFriend(fake, caster) && (fake.getKarma() <= 0)))
			{
				return false;
			}

			if (!forced && !hasPvpStatus(caster, fake))
			{
				return false;
			}
			force = true;
		}

		return (force || target.isAutoAttackable(caster)) && GeoEngine.getInstance().canSeeTarget(origin, target);
	}

	/**
	 * A white fake player in a fight is fair game for a hit of its own (see Monster#isAutoAttackable), not for a totem planted without Ctrl.
	 * @param caster the totem's Warchief
	 * @param fake a fake player
	 * @return {@code true} if it is flagged, a PK or at war with the caster's clan (or fake players are always fair game)
	 */
	private static boolean hasPvpStatus(Creature caster, Creature fake)
	{
		return FakePlayersConfig.FAKE_PLAYER_AUTO_ATTACKABLE || (fake.asNpc().getScriptValue() > 0) || (fake.getKarma() > 0) || FakeClanManager.getInstance().isWarEnemy(fake, caster);
	}

	/**
	 * @param target a character
	 * @return {@code true} for a fake player or a fake player's servitor, who are fought like players
	 */
	public static boolean isFakePerson(Creature target)
	{
		return target.isFakePlayer() || (target instanceof FakePlayerPvpServitor);
	}

	/**
	 * A player's totem hit a player or a fake player: the player is flagged as if it had hit them itself.
	 * @param caster the totem's Warchief
	 * @param target what it hit
	 */
	public static void flagFor(Creature caster, Creature target)
	{
		if (!caster.isPlayer() || caster.isInsideZone(ZoneId.PVP))
		{
			return;
		}

		final Player player = caster.asPlayer();
		if (target.isPlayable())
		{
			AttackStanceTaskManager.getInstance().addAttackStanceTask(player);
			if (player.getSummon() != target)
			{
				player.updatePvPStatus(target);
			}
		}
		else if (isFakePerson(target) && !FakePlayersConfig.FAKE_PLAYER_AUTO_ATTACKABLE)
		{
			AttackStanceTaskManager.getInstance().addAttackStanceTask(player);
			player.updatePvPStatus();
		}
	}

	/**
	 * @param caster the caster
	 * @param skill the skill it is casting
	 * @return {@code true} if a player is casting {@code skill} with Ctrl held
	 */
	public static boolean isCtrlCast(Creature caster, Skill skill)
	{
		if (!caster.isPlayer())
		{
			return false;
		}

		final SkillUseHolder current = caster.asPlayer().getCurrentSkill();
		return (current != null) && (current.getSkillId() == skill.getId()) && current.isCtrlPressed();
	}

	/**
	 * @param caster the caster
	 * @param center where the area is
	 * @param range how far it reaches around the center
	 * @param skill the skill that would reach them
	 * @return the caster's enemies around the center and in its sight, see {@link #isEnemy}
	 */
	public static List<Creature> getEnemies(Creature caster, WorldObject center, int range, Skill skill)
	{
		return getEnemies(caster, center, range, skill, null);
	}

	/**
	 * @param caster the totem's Warchief
	 * @param totem the totem
	 * @param range how far it reaches around the totem
	 * @param skill the skill that would reach them
	 * @param forced {@code true} if planted (or Shatter cast) with Ctrl held
	 * @return the enemies the totem hits, see {@link #isTotemEnemy}
	 */
	public static List<Creature> getTotemEnemies(Creature caster, WorldObject totem, int range, Skill skill, boolean forced)
	{
		return getEnemies(caster, totem, range, skill, forced);
	}

	private static List<Creature> getEnemies(Creature caster, WorldObject center, int range, Skill skill, Boolean forced)
	{
		final List<Creature> result = new ArrayList<>();
		for (Creature target : World.getInstance().getVisibleObjectsInRange(center, Creature.class, range))
		{
			if (isEnemy(caster, target, skill, center, forced))
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
