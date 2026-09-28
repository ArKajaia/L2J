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
package org.l2jmobius.gameserver.ai;

import org.l2jmobius.gameserver.geoengine.GeoEngine;
import org.l2jmobius.gameserver.managers.FakePlayerPvpManager;
import org.l2jmobius.gameserver.model.actor.Attackable;
import org.l2jmobius.gameserver.model.actor.Creature;
import org.l2jmobius.gameserver.model.actor.Npc;
import org.l2jmobius.gameserver.model.actor.instance.FakePlayerPvpServitor;
import org.l2jmobius.gameserver.model.zone.ZoneId;

/**
 * AI of a roaming fake player's servitor (see {@link FakePlayerPvpServitor}): like a player's summon on its owner's orders, it follows its owner, attacks what its owner attacks, and fights back whoever hits it while its owner has nothing better for it. It
 * goes away when its owner is gone or left it far behind.
 */
public class FakePlayerPvpServitorAI extends AttackableAI
{
	/** It walks back to its owner once this far away, and stops this close. */
	private static final int FOLLOW_DISTANCE = 200;
	private static final int FOLLOW_OFFSET = 80;
	/** It doesn't wander off further than this from its owner for a target of its own. */
	private static final int OWN_TARGET_RANGE = 1200;
	/** Left this far behind, it is unsummoned (its owner summons a new one). */
	private static final int LOST_DISTANCE = 3000;

	public FakePlayerPvpServitorAI(Attackable servitor)
	{
		super(servitor);
	}

	@Override
	synchronized void changeIntention(Intention newIntention, Object arg0, Object arg1)
	{
		// It keeps up with its owner even with no player around (an idle AttackableAI would stop thinking there).
		final Npc owner = getOwner();
		if ((newIntention == Intention.IDLE) && (owner != null) && !owner.isDead() && owner.isSpawned())
		{
			super.changeIntention(Intention.ACTIVE, arg0, arg1);
			return;
		}

		super.changeIntention(newIntention, arg0, arg1);
	}

	@Override
	protected void thinkActive()
	{
		final Attackable servitor = getActiveChar();
		if (servitor.isDead() || servitor.isCastingNow() || !checkOwner(servitor))
		{
			return;
		}

		final Creature target = pickTarget(servitor);
		if (target != null)
		{
			servitor.setRunning();
			setIntention(Intention.ATTACK, target);
			return;
		}

		follow(servitor);
	}

	@Override
	protected void thinkAttack()
	{
		final Attackable servitor = getActiveChar();
		if (servitor.isDead() || servitor.isCastingNow() || !checkOwner(servitor))
		{
			return;
		}

		final Creature target = pickTarget(servitor);
		if (target == null)
		{
			setAttackTarget(null);
			servitor.setTarget(null);
			setIntention(Intention.ACTIVE);
			follow(servitor);
			return;
		}

		if (getAttackTarget() != target)
		{
			setAttackTarget(target);
		}
		if (servitor.getTarget() != target)
		{
			servitor.setTarget(target);
		}

		servitor.setRunning();
		final int range = servitor.getPhysicalAttackRange();
		final int collision = servitor.getTemplate().getCollisionRadius() + target.getTemplate().getCollisionRadius();
		if ((servitor.calculateDistance2D(target) > (range + collision)) || !GeoEngine.getInstance().canSeeTarget(servitor, target))
		{
			if (!servitor.isMovementDisabled())
			{
				moveToPawn(target, range);
			}
			return;
		}

		_actor.doAttack(target);
	}

	@Override
	protected void onActionAttacked(Creature attacker)
	{
		final Attackable servitor = getActiveChar();
		if (servitor.isDead() || (attacker == null))
		{
			return;
		}

		// A player (or another fake player) hitting its servitor picks a fight with the fake player, like hitting a player's summon.
		final Npc owner = getOwner();
		if ((owner != null) && !owner.isDead() && owner.isAttackable() && FakePlayerPvpManager.isPvpEnemy(attacker))
		{
			FakePlayerPvpManager.getInstance().onFakePlayerAttacked(owner.asAttackable(), attacker);
			
			// Its owner doesn't want that fight: neither does it.
			if (FakePlayerPvpManager.isRefusing(owner.asAttackable(), attacker))
			{
				return;
			}
		}

		if (getIntention() != Intention.ATTACK)
		{
			servitor.setRunning();
			setIntention(Intention.ATTACK, attacker);
		}
	}

	@Override
	protected void onActionAggression(Creature target, int aggro)
	{
		// It only takes orders from its owner.
	}

	/**
	 * @return {@code false} (and it is unsummoned) if its owner is dead, gone, or left it far behind
	 */
	private boolean checkOwner(Attackable servitor)
	{
		final Npc owner = getOwner();
		if ((owner == null) || owner.isDead() || !owner.isSpawned() || (owner.getInstanceId() != servitor.getInstanceId()) || (servitor.calculateDistance2D(owner) > LOST_DISTANCE))
		{
			if (owner != null)
			{
				FakePlayerPvpManager.getInstance().unsummonServitor(owner);
			}
			else
			{
				servitor.deleteMe();
			}
			return false;
		}

		return true;
	}

	/**
	 * @return what its owner is attacking, otherwise the closest one still hitting it, {@code null} if nothing
	 */
	private Creature pickTarget(Attackable servitor)
	{
		final Npc owner = getOwner();
		if (owner.hasAI() && (owner.getAI().getIntention() == Intention.ATTACK))
		{
			final Creature ownerTarget = owner.getAI().getAttackTarget();
			if (isValidTarget(servitor, owner, ownerTarget))
			{
				return ownerTarget;
			}
		}

		Creature best = null;
		double bestDistance = Double.MAX_VALUE;
		for (Creature attacker : servitor.getAggroList().keySet())
		{
			if (!isValidTarget(servitor, owner, attacker) || (owner.calculateDistance2D(attacker) > OWN_TARGET_RANGE))
			{
				continue;
			}

			final double distance = servitor.calculateDistance2D(attacker);
			if (distance < bestDistance)
			{
				best = attacker;
				bestDistance = distance;
			}
		}

		return best;
	}

	private static boolean isValidTarget(Attackable servitor, Npc owner, Creature target)
	{
		if ((target == null) || (target == servitor) || (target == owner) || target.isAlikeDead() || !target.isSpawned() || target.isInvisible() || (target.getInstanceId() != servitor.getInstanceId()))
		{
			return false;
		}

		// Not someone its owner chose not to hit back.
		if (owner.isAttackable() && FakePlayerPvpManager.isRefusing(owner.asAttackable(), target))
		{
			return false;
		}

		return !FakePlayerPvpManager.isPvpEnemy(target) || !target.isInsideZone(ZoneId.PEACE);
	}

	private void follow(Attackable servitor)
	{
		final Npc owner = getOwner();
		if (!servitor.isMovementDisabled() && (servitor.calculateDistance2D(owner) > FOLLOW_DISTANCE))
		{
			servitor.setRunning();
			moveToPawn(owner, FOLLOW_OFFSET);
		}
	}

	private Npc getOwner()
	{
		return _actor instanceof FakePlayerPvpServitor ? ((FakePlayerPvpServitor) _actor).getOwner() : null;
	}
}
