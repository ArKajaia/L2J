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
package org.l2jmobius.gameserver.model.actor.instance;

import org.l2jmobius.gameserver.ai.CreatureAI;
import org.l2jmobius.gameserver.ai.FakePlayerPvpServitorAI;
import org.l2jmobius.gameserver.managers.FakePlayerPvpManager;
import org.l2jmobius.gameserver.model.actor.Attackable;
import org.l2jmobius.gameserver.model.actor.Creature;
import org.l2jmobius.gameserver.model.actor.Npc;
import org.l2jmobius.gameserver.model.actor.templates.NpcTemplate;

/**
 * The servitor of a roaming fake player (a necromancer's Reanimated Man...), made from the servitor's own template like a player's summon. A real {@link Servitor} needs a player owner, so this is an npc that stays by its owner and fights what it fights (see
 * {@link FakePlayerPvpServitorAI}). Players and monsters can hit it like the fake player itself, it takes part of its owner's damage while Transfer Pain is on, and killing it gives nothing.
 */
public class FakePlayerPvpServitor extends Attackable
{
	private final Npc _owner;

	public FakePlayerPvpServitor(NpcTemplate template, Npc owner)
	{
		super(template);
		_owner = owner;
	}

	/**
	 * @return the fake player it belongs to
	 */
	public Npc getOwner()
	{
		return _owner;
	}

	@Override
	protected CreatureAI initAI()
	{
		return new FakePlayerPvpServitorAI(this);
	}

	@Override
	public boolean isAutoAttackable(Creature attacker)
	{
		// Monsters fight it back; players can hit it whenever they can hit its owner.
		if ((attacker == _owner) || (attacker == this))
		{
			return false;
		}

		return attacker.isMonster() || _owner.isAutoAttackable(attacker);
	}

	@Override
	public synchronized boolean getMustRewardExpSP()
	{
		return false;
	}

	@Override
	public boolean hasRandomAnimation()
	{
		return false;
	}

	@Override
	public boolean doDie(Creature killer)
	{
		if (!super.doDie(killer))
		{
			return false;
		}

		FakePlayerPvpManager.getInstance().onServitorDeath(this);
		return true;
	}
}
