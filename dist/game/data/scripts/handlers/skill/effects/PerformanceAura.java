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
package handlers.skill.effects;

import java.util.ArrayList;
import java.util.List;

import org.l2jmobius.gameserver.config.custom.PerformersConfig;
import org.l2jmobius.gameserver.data.xml.SkillData;
import org.l2jmobius.gameserver.geoengine.GeoEngine;
import org.l2jmobius.gameserver.managers.FakePartyManager;
import org.l2jmobius.gameserver.model.StatSet;
import org.l2jmobius.gameserver.model.World;
import org.l2jmobius.gameserver.model.actor.Creature;
import org.l2jmobius.gameserver.model.actor.Player;
import org.l2jmobius.gameserver.model.actor.holders.npc.FakePlayerParty;
import org.l2jmobius.gameserver.model.conditions.Condition;
import org.l2jmobius.gameserver.model.effects.AbstractEffect;
import org.l2jmobius.gameserver.model.skill.BuffInfo;
import org.l2jmobius.gameserver.model.skill.Skill;
import org.l2jmobius.gameserver.model.skill.enums.SkillFinishType;
import org.l2jmobius.gameserver.model.zone.ZoneId;
import org.l2jmobius.gameserver.util.LocationUtil;

/**
 * Performance aura: while the toggle is on, every tick keeps an "echo" skill on everyone in range, and the echo fades a few seconds after they leave it.
 * <ul>
 * <li>{@code affects} PARTY: the performer, the party members and their summons (a buff).</li>
 * <li>{@code affects} ENEMY: monsters and players the performer could attack (a debuff, rolled once with the echo's own land chance, then held while they stay in range).</li>
 * </ul>
 * The echo is extended rather than cast again, so nobody gets an "effect can be felt" message every tick. The same echo from two performers doesn't stack: the higher level holds the slot, and on a tie whoever got there first.<br>
 * A roaming fake player performs too: its party is the one it hunts with (fake players and players, see {@link FakePartyManager}), and its hexes reach what its area skills reach.<br>
 * A stunned, sleeping or paralyzed performer stops performing until it ends. Turning the toggle off takes its echoes away at once, so switching performances can't keep two going.
 * @author Mobius
 */
public class PerformanceAura extends AbstractEffect
{
	private final int _echoId;
	private final int _echoLevel;
	private final int _range;
	private final boolean _enemies;

	public PerformanceAura(Condition attachCond, Condition applyCond, StatSet set, StatSet params)
	{
		super(attachCond, applyCond, set, params);

		_echoId = params.getInt("skillId");
		_echoLevel = params.getInt("skillLevel", 1);
		_range = params.getInt("range", 900);
		_enemies = params.getString("affects", "PARTY").equalsIgnoreCase("ENEMY");
	}

	private Skill getEcho()
	{
		return SkillData.getInstance().getSkill(_echoId, _echoLevel);
	}

	@Override
	public void onStart(Creature effector, Creature effected, Skill skill)
	{
		perform(effected);
	}

	@Override
	public boolean onActionTime(Creature effector, Creature effected, Skill skill)
	{
		if (effected.isDead())
		{
			return false;
		}

		perform(effected);
		return skill.isToggle();
	}

	@Override
	public void onExit(Creature effector, Creature effected, Skill skill)
	{
		final Skill echo = getEcho();
		if (echo == null)
		{
			return;
		}

		// Everyone who may still hold one of this performer's echoes: the party anywhere, anyone near for a hex.
		final List<Creature> holders = _enemies ? World.getInstance().getVisibleObjectsInRange(effected, Creature.class, _range * 2) : getParty(effected);
		for (Creature holder : holders)
		{
			final BuffInfo info = holder.getEffectList().getBuffInfoByAbnormalType(echo.getAbnormalType());
			if ((info != null) && (info.getSkill().getId() == echo.getId()) && (info.getEffector() == effected))
			{
				holder.getEffectList().remove(SkillFinishType.NORMAL, info);
			}
		}
	}

	private void perform(Creature performer)
	{
		final Skill echo = getEcho();
		if ((echo == null) || performer.isStunned() || performer.isSleeping() || performer.isParalyzed())
		{
			return;
		}

		if (_enemies)
		{
			if (performer.isInsideZone(ZoneId.PEACE))
			{
				return;
			}

			for (Creature target : World.getInstance().getVisibleObjectsInRange(performer, Creature.class, _range))
			{
				if (isHexTarget(performer, target, echo))
				{
					keepEcho(performer, target, echo);
				}
			}
		}
		else
		{
			for (Creature target : getParty(performer))
			{
				if (!target.isDead() && ((target == performer) || LocationUtil.checkIfInRange(_range, performer, target, true)))
				{
					keepEcho(performer, target, echo);
				}
			}
		}
	}

	/**
	 * Puts the echo on the target, or extends the one this performer already put there.
	 * @param performer the performer
	 * @param target the character in range
	 * @param echo the echo skill
	 */
	private static void keepEcho(Creature performer, Creature target, Skill echo)
	{
		final BuffInfo info = target.getEffectList().getBuffInfoByAbnormalType(echo.getAbnormalType());
		if (info != null)
		{
			if ((info.getSkill().getId() == echo.getId()) && (info.getSkill().getLevel() == echo.getLevel()) && (info.getEffector() == performer))
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

			// Another performer's echo of the same kind, as strong or stronger, keeps the slot.
			if (info.getSkill().getAbnormalLevel() >= echo.getAbnormalLevel())
			{
				return;
			}
		}

		echo.applyEffects(performer, target);
	}

	/**
	 * @param performer the performer
	 * @return the performer, the party members (players and fake players) and every summon among them
	 */
	private static List<Creature> getParty(Creature performer)
	{
		final List<? extends Creature> members;
		final FakePlayerParty fakeParty = FakePartyManager.getInstance().getParty(performer);
		final Player player = performer.asPlayer();
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
			members = List.of(performer);
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

		if (!result.contains(performer))
		{
			result.add(performer);
		}

		return result;
	}

	/**
	 * @param performer the performer
	 * @param target a character in range
	 * @param echo the hex
	 * @return {@code true} if a hex can reach the target: an enemy the performer could attack without forcing it, in sight, outside a peace zone
	 */
	private static boolean isHexTarget(Creature performer, Creature target, Skill echo)
	{
		if ((target == performer) || target.isAlikeDead() || target.isInvul() || !(target.isAttackable() || target.isPlayable()) || target.isInsideZone(ZoneId.PEACE))
		{
			return false;
		}

		// Fake players count as players.
		if (!PerformersConfig.HEX_AFFECTS_PLAYERS && (target.isPlayable() || target.isFakePlayer()))
		{
			return false;
		}

		// A fake performer: like its area skills, monsters, and the players (and fake players) it fights or that are flagged, never its party, clan or alliance.
		if (performer.isPvpFakePlayer())
		{
			return Skill.checkForAreaOffensiveSkills(performer, target, echo, false);
		}

		if (target.isPlayable())
		{
			// Never the performer's own party, clan or alliance (or their summons), whatever the zone.
			final Player player = performer.asPlayer();
			final Player other = target.asPlayer();
			if ((player == null) || (other == null) || (player == other))
			{
				return false;
			}

			// An Olympiad or duel opponent is fair game even from the same clan.
			final boolean olympiad = player.isInOlympiadMode() && other.isInOlympiadMode() && (player.getOlympiadGameId() == other.getOlympiadGameId());
			final boolean duel = player.isInDuel() && other.isInDuel() && (player.getDuelId() == other.getDuelId());
			if (olympiad || duel)
			{
				return target.isAutoAttackable(performer) && GeoEngine.getInstance().canSeeTarget(performer, target);
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

		return target.isAutoAttackable(performer) && GeoEngine.getInstance().canSeeTarget(performer, target);
	}
}
