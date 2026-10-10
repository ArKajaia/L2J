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
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;

import org.l2jmobius.commons.threads.ThreadPool;
import org.l2jmobius.gameserver.config.custom.OraclesWarchiefsConfig;
import org.l2jmobius.gameserver.data.xml.SkillData;
import org.l2jmobius.gameserver.model.Location;
import org.l2jmobius.gameserver.model.actor.Creature;
import org.l2jmobius.gameserver.model.events.EventType;
import org.l2jmobius.gameserver.model.events.holders.actor.creature.OnCreatureDamageReceived;
import org.l2jmobius.gameserver.model.events.holders.actor.creature.OnCreatureSkillUse;
import org.l2jmobius.gameserver.model.events.listeners.ConsumerEventListener;
import org.l2jmobius.gameserver.model.skill.BuffInfo;
import org.l2jmobius.gameserver.model.skill.EffectScope;
import org.l2jmobius.gameserver.model.skill.Skill;
import org.l2jmobius.gameserver.model.skill.enums.SkillFinishType;
import org.l2jmobius.gameserver.model.stats.Formulas;
import org.l2jmobius.gameserver.network.SystemMessageId;
import org.l2jmobius.gameserver.network.serverpackets.MagicSkillUse;
import org.l2jmobius.gameserver.network.serverpackets.SystemMessage;

/**
 * The Oracles: the Prophet line (Prophet, Hierophant). They don't buff any more, they cast prophecies (data/stats/skills/custom/oracle_skills.xml).<br>
 * A prophecy does nothing when it lands: it comes true when its time runs out (the Prophecy effect), and it fizzles if it is cancelled or its target dies first. Each one that comes true gives its Oracle a Foresight (up to 5), and Fulfilment spends 5 to make every prophecy
 * of the Oracle come true at once, stronger.
 * <ul>
 * <li>Doom: magic damage, more if the target is still near where it stood when the prophecy landed. With Inevitable Doom (Hierophant) it is cast faster, stacks up to {@link OraclesWarchiefsConfig#DOOM_MAX_STACKS} times on its target (each stack adds the damage once more) and spreads to the
 * enemies close to its target, who can't resist it.</li>
 * <li>Salvation: heals back the damage the ally took while the prophecy was on.</li>
 * <li>Ruin: stuns a target that cast a skill while the prophecy was on, silences one that didn't.</li>
 * <li>Reversal: the ally's HP, MP, CP and place are written down when it lands, and it comes back to them (never lower than it is).</li>
 * </ul>
 * @author Mobius
 */
public class Prophecies
{
	/** Foresight: one level per prophecy that came true, up to {@link #FORESIGHT_MAX}. */
	public static final int FORESIGHT_SKILL_ID = 27537;
	public static final int FORESIGHT_MAX = 5;
	/** Prophecy of Doom, and the Hierophant passive that makes it stack and spread. */
	public static final int DOOM_SKILL_ID = 27530;
	public static final int INEVITABLE_DOOM_SKILL_ID = 27562;

	public enum Kind
	{
		DOOM,
		SALVATION,
		RUIN,
		REVERSAL
	}

	/** A prophecy that hasn't come true yet. */
	private static class Prophecy
	{
		final Creature oracle;
		final Creature target;
		final Skill skill;
		final BuffInfo info;
		final Kind kind;
		final int resolveSkillId;
		final int resolveSkillLevel;
		final int altSkillId;
		final Location start;
		final double hp;
		final double mp;
		final double cp;
		volatile double damageTaken;
		volatile boolean castSkill;
		int stacks = 1;
		ScheduledFuture<?> task;

		Prophecy(Creature oracle, Creature target, Skill skill, BuffInfo info, Kind kind, int resolveSkillId, int resolveSkillLevel, int altSkillId)
		{
			this.oracle = oracle;
			this.target = target;
			this.skill = skill;
			this.info = info;
			this.kind = kind;
			this.resolveSkillId = resolveSkillId;
			this.resolveSkillLevel = resolveSkillLevel;
			this.altSkillId = altSkillId;
			start = target.getLocation();
			hp = target.getCurrentHp();
			mp = target.getCurrentMp();
			cp = target.getCurrentCp();
		}
	}

	/** How many times a Doom is stacked on its target, and when it comes true. */
	private static class DoomStack
	{
		final Creature oracle;
		final int stacks;
		final long resolveAt;

		DoomStack(Creature oracle, int stacks, long resolveAt)
		{
			this.oracle = oracle;
			this.stacks = stacks;
			this.resolveAt = resolveAt;
		}
	}

	/** The prophecies that haven't come true, by target and prophecy skill. */
	private static final Map<Long, Prophecy> PROPHECIES = new ConcurrentHashMap<>();

	/**
	 * The Doom stacks, by target and prophecy skill. Kept apart from the prophecy: when a Doom is cast again, the old one ends before the new one lands.
	 */
	private static final Map<Long, DoomStack> DOOM_STACKS = new ConcurrentHashMap<>();

	/** While a Doom spreads: the stacks and the milliseconds left of the Doom it spreads from. */
	private static final ThreadLocal<long[]> SPREADING = new ThreadLocal<>();

	private Prophecies()
	{
	}

	private static long key(Creature target, Skill skill)
	{
		return (((long) target.getObjectId()) << 32) | skill.getId();
	}

	/**
	 * A prophecy landed: it will come true when its time runs out.
	 * @param oracle the caster
	 * @param target the character it landed on
	 * @param skill the prophecy
	 * @param kind what it does
	 * @param resolveSkillId the skill it comes true with (Doom: the damage, Ruin: the stun), 0 for none
	 * @param resolveSkillLevel its level
	 * @param altSkillId Ruin: the silence
	 */
	public static void begin(Creature oracle, Creature target, Skill skill, Kind kind, int resolveSkillId, int resolveSkillLevel, int altSkillId)
	{
		final BuffInfo info = target.getEffectList().getBuffInfoBySkillId(skill.getId());
		final Prophecy prophecy = new Prophecy(oracle, target, skill, info, kind, resolveSkillId, resolveSkillLevel, altSkillId);
		final long key = key(target, skill);
		final Prophecy old = PROPHECIES.put(key, prophecy);
		if (old != null)
		{
			stop(old);
		}

		// Comes true just before the buff would wear off (it is checked once a second).
		final int seconds = info != null ? info.getAbnormalTime() : skill.getAbnormalTime();
		long delay = Math.max(500, (seconds * 1000L) - 250);

		switch (kind)
		{
			case SALVATION:
			{
				target.addListener(new ConsumerEventListener(target, EventType.ON_CREATURE_DAMAGE_RECEIVED, (OnCreatureDamageReceived event) -> prophecy.damageTaken += event.getDamage(), prophecy));
				break;
			}
			case RUIN:
			{
				target.addListener(new ConsumerEventListener(target, EventType.ON_CREATURE_SKILL_USE, (OnCreatureSkillUse event) ->
				{
					if (!event.getSkill().isPassive() && !event.getSkill().isTriggeredSkill())
					{
						prophecy.castSkill = true;
					}
				}, prophecy));
				break;
			}
			default:
			{
				break;
			}
		}

		if (kind == Kind.DOOM)
		{
			delay = stackDoom(prophecy, key, delay);
		}

		prophecy.task = ThreadPool.schedule(() -> resolve(prophecy, 1), delay);
	}

	/**
	 * Inevitable Doom (Hierophant): a Doom cast again on its target by the same Oracle adds a stack, up to {@link OraclesWarchiefsConfig#DOOM_MAX_STACKS}, and comes true later; once full it keeps the time it had. It also spreads to the enemies close to its target.
	 * @param prophecy the Doom that landed
	 * @param key its key
	 * @param delay milliseconds before a fresh Doom comes true
	 * @return milliseconds before this one comes true
	 */
	private static long stackDoom(Prophecy prophecy, long key, long delay)
	{
		final Creature oracle = prophecy.oracle;
		final long now = System.currentTimeMillis();
		final DoomStack previous = DOOM_STACKS.get(key);
		final int before = (previous != null) && (previous.oracle == oracle) && (previous.resolveAt > now) ? previous.stacks : 0;
		final long[] spreading = SPREADING.get();
		final boolean spread = spreading == null;
		long result = delay;
		if (spreading != null)
		{
			// Spread from another target: as many stacks as there, as much time left.
			prophecy.stacks = (int) Math.max(spreading[0], before);
			result = spreading[1];
		}
		else if (!knowsInevitableDoom(oracle) || (before == 0))
		{
			prophecy.stacks = 1;
		}
		else if (before >= OraclesWarchiefsConfig.DOOM_MAX_STACKS)
		{
			prophecy.stacks = OraclesWarchiefsConfig.DOOM_MAX_STACKS;
			result = Math.max(500, previous.resolveAt - now);
		}
		else
		{
			prophecy.stacks = before + 1;
		}

		DOOM_STACKS.put(key, new DoomStack(oracle, prophecy.stacks, now + result));
		if ((result != delay) && (prophecy.info != null))
		{
			// The icon shows the time left.
			prophecy.info.setAbnormalTime((int) Math.max(1, (result + 999) / 1000));
			prophecy.target.getEffectList().updateEffectIcons(false);
		}

		if (spread && knowsInevitableDoom(oracle))
		{
			if ((prophecy.stacks > 1) && oracle.isPlayer())
			{
				oracle.asPlayer().sendMessage(prophecy.skill.getName() + " x" + prophecy.stacks + ": " + prophecy.target.getName() + ".");
			}

			spreadDoom(prophecy, result);
		}

		return result;
	}

	/**
	 * The Doom spreads to the Oracle's enemies close to its target. They can't resist it.
	 * @param prophecy the Doom it spreads from
	 * @param delay milliseconds before it comes true
	 */
	private static void spreadDoom(Prophecy prophecy, long delay)
	{
		if (OraclesWarchiefsConfig.DOOM_SPREAD_RANGE <= 0)
		{
			return;
		}

		final Creature oracle = prophecy.oracle;
		final Skill skill = prophecy.skill;
		SPREADING.set(new long[]
		{
			prophecy.stacks,
			delay
		});
		try
		{
			for (Creature enemy : AreaTargets.getEnemies(oracle, prophecy.target, OraclesWarchiefsConfig.DOOM_SPREAD_RANGE, skill))
			{
				if ((enemy == prophecy.target) || enemy.isInvulAgainst(skill.getId(), skill.getLevel()))
				{
					continue;
				}

				// Lands like Skill#applyEffects, without the roll to resist.
				final BuffInfo info = new BuffInfo(oracle, enemy, skill);
				skill.applyEffectScope(EffectScope.GENERAL, info, true, true);
				skill.applyEffectScope(oracle.isPlayable() && enemy.isAttackable() ? EffectScope.PVE : oracle.isPlayable() && enemy.isPlayable() ? EffectScope.PVP : null, info, true, true);
				enemy.getEffectList().add(info);
			}
		}
		finally
		{
			SPREADING.remove();
		}
	}

	/**
	 * @param oracle the caster
	 * @return {@code true} if it knows Inevitable Doom (Hierophant)
	 */
	private static boolean knowsInevitableDoom(Creature oracle)
	{
		return (oracle != null) && (oracle.getKnownSkill(INEVITABLE_DOOM_SKILL_ID) != null);
	}

	/**
	 * Inevitable Doom (Hierophant): Prophecy of Doom comes back sooner.
	 * @param caster the caster
	 * @param skill the skill cast
	 * @param reuseDelay its reuse, milliseconds
	 * @return the reuse
	 */
	public static int getReuseDelay(Creature caster, Skill skill, int reuseDelay)
	{
		if ((skill.getId() == DOOM_SKILL_ID) && knowsInevitableDoom(caster))
		{
			return (int) (reuseDelay * OraclesWarchiefsConfig.DOOM_HIEROPHANT_REUSE);
		}

		return reuseDelay;
	}

	/**
	 * A prophecy ended before it came true (cancelled, its target died...): it fizzles.
	 * @param target the character it was on
	 * @param skill the prophecy
	 */
	public static void end(Creature target, Skill skill)
	{
		final long key = key(target, skill);
		final Prophecy prophecy = PROPHECIES.get(key);
		if (prophecy == null)
		{
			return;
		}

		// Cast again: the new one has already taken the place of the old one.
		if ((prophecy.info != null) && (target.getEffectList().getBuffInfoBySkillId(skill.getId()) == prophecy.info))
		{
			return;
		}

		if (PROPHECIES.remove(key, prophecy))
		{
			stop(prophecy);
		}

		// The stacks go with it, unless it is being cast again right now (the new one takes them).
		final DoomStack stack = DOOM_STACKS.get(key);
		if (stack != null)
		{
			if (target.isDead())
			{
				DOOM_STACKS.remove(key, stack);
			}
			else
			{
				ThreadPool.schedule(() -> DOOM_STACKS.remove(key, stack), 1000);
			}
		}
	}

	private static void stop(Prophecy prophecy)
	{
		if (prophecy.task != null)
		{
			prophecy.task.cancel(false);
		}

		prophecy.target.removeListenerIf(listener -> listener.getOwner() == prophecy);
	}

	/**
	 * Fulfilment: every prophecy of this Oracle comes true now, stronger.
	 * @param oracle the Oracle
	 * @return how many came true
	 */
	public static int fulfil(Creature oracle)
	{
		final List<Prophecy> mine = new ArrayList<>();
		for (Prophecy prophecy : PROPHECIES.values())
		{
			if (prophecy.oracle == oracle)
			{
				mine.add(prophecy);
			}
		}

		// Foresight is spent first, so these don't give any back.
		oracle.getEffectList().stopSkillEffects(SkillFinishType.SILENT, FORESIGHT_SKILL_ID);
		for (Prophecy prophecy : mine)
		{
			resolve(prophecy, OraclesWarchiefsConfig.FULFILMENT_MULTIPLIER, false);
		}

		return mine.size();
	}

	/**
	 * @param oracle the Oracle
	 * @return its Foresight, 0 to {@link #FORESIGHT_MAX}
	 */
	public static int getForesight(Creature oracle)
	{
		final BuffInfo info = oracle.getEffectList().getBuffInfoBySkillId(FORESIGHT_SKILL_ID);
		return info == null ? 0 : info.getSkill().getLevel();
	}

	private static void resolve(Prophecy prophecy, double multiplier)
	{
		resolve(prophecy, multiplier, true);
	}

	private static void resolve(Prophecy prophecy, double multiplier, boolean foresight)
	{
		final long key = key(prophecy.target, prophecy.skill);
		if (!PROPHECIES.remove(key, prophecy))
		{
			return;
		}

		DOOM_STACKS.remove(key);

		stop(prophecy);

		final Creature oracle = prophecy.oracle;
		final Creature target = prophecy.target;

		// The prophecy is spent.
		target.getEffectList().stopSkillEffects(SkillFinishType.SILENT, prophecy.skill.getId());

		if (target.isDead() || (oracle == null) || (oracle.isPlayer() && !oracle.asPlayer().isOnline()) || (oracle.getInstanceId() != target.getInstanceId()))
		{
			return;
		}

		final Skill resolveSkill = prophecy.resolveSkillId > 0 ? SkillData.getInstance().getSkill(prophecy.resolveSkillId, prophecy.resolveSkillLevel) : null;
		target.broadcastPacket(new MagicSkillUse(oracle, target, resolveSkill != null ? resolveSkill.getDisplayId() : prophecy.skill.getDisplayId(), 1, 0, 0));

		switch (prophecy.kind)
		{
			case DOOM:
			{
				if ((resolveSkill == null) || target.isInvul())
				{
					break;
				}

				final boolean still = target.calculateDistance2D(prophecy.start) < OraclesWarchiefsConfig.DOOM_STILL_RANGE;
				final boolean mcrit = Formulas.calcMCrit(oracle.getMCriticalHit(target, resolveSkill));
				final byte shld = Formulas.calcShldUse(oracle, target, resolveSkill);
				final int damage = (int) (Formulas.calcMagicDam(oracle, target, resolveSkill, shld, false, false, mcrit) * multiplier * (still ? OraclesWarchiefsConfig.DOOM_STILL_BONUS : 1) * prophecy.stacks);
				if (damage > 0)
				{
					target.reduceCurrentHp(damage, oracle, resolveSkill);
					target.notifyDamageReceived(damage, oracle, resolveSkill, mcrit, false);
					oracle.sendDamageMessage(target, damage, mcrit, false, false);
				}
				break;
			}
			case SALVATION:
			{
				final double amount = Math.min(prophecy.damageTaken * OraclesWarchiefsConfig.SALVATION_RETURN * multiplier, target.getMaxHp() - target.getCurrentHp());
				if (amount > 0)
				{
					target.setCurrentHp(target.getCurrentHp() + amount);
					final SystemMessage sm = new SystemMessage(SystemMessageId.S1_HP_HAS_BEEN_RESTORED);
					sm.addInt((int) amount);
					target.sendPacket(sm);
				}
				break;
			}
			case RUIN:
			{
				final Skill ruin = prophecy.castSkill ? resolveSkill : SkillData.getInstance().getSkill(prophecy.altSkillId, prophecy.resolveSkillLevel);
				if ((ruin != null) && !target.isInvul())
				{
					ruin.applyEffects(oracle, target);
				}
				break;
			}
			case REVERSAL:
			{
				if ((OraclesWarchiefsConfig.REVERSAL_MAX_DISTANCE > 0) && (target.calculateDistance3D(prophecy.start) > 30) && (target.calculateDistance3D(prophecy.start) <= OraclesWarchiefsConfig.REVERSAL_MAX_DISTANCE) && !target.isMovementDisabled())
				{
					AreaTargets.flyTo(target, prophecy.start.getX(), prophecy.start.getY(), prophecy.start.getZ());
				}

				target.setCurrentHp(Math.max(target.getCurrentHp(), Math.min(prophecy.hp, target.getMaxHp())));
				target.setCurrentMp(Math.max(target.getCurrentMp(), Math.min(prophecy.mp, target.getMaxMp())));
				if (target.isPlayer())
				{
					target.setCurrentCp(Math.max(target.getCurrentCp(), Math.min(prophecy.cp, target.getMaxCp())));
				}
				break;
			}
		}

		if (foresight)
		{
			addForesight(oracle);
		}
	}

	private static void addForesight(Creature oracle)
	{
		final int level = Math.min(FORESIGHT_MAX, getForesight(oracle) + 1);
		final Skill skill = SkillData.getInstance().getSkill(FORESIGHT_SKILL_ID, level);
		if (skill != null)
		{
			oracle.getEffectList().stopSkillEffects(SkillFinishType.SILENT, FORESIGHT_SKILL_ID);
			skill.applyEffects(oracle, oracle);
		}
	}
}
