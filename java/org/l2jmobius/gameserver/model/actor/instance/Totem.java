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

import java.util.List;
import java.util.Set;
import java.util.concurrent.ScheduledFuture;

import org.l2jmobius.commons.threads.ThreadPool;
import org.l2jmobius.gameserver.ai.Intention;
import org.l2jmobius.gameserver.model.World;
import org.l2jmobius.gameserver.model.actor.Attackable;
import org.l2jmobius.gameserver.model.actor.Creature;
import org.l2jmobius.gameserver.model.actor.Npc;
import org.l2jmobius.gameserver.model.actor.Player;
import org.l2jmobius.gameserver.model.actor.enums.creature.InstanceType;
import org.l2jmobius.gameserver.model.actor.holders.player.Totems;
import org.l2jmobius.gameserver.model.actor.templates.NpcTemplate;
import org.l2jmobius.gameserver.network.serverpackets.ActionFailed;

/**
 * A totem planted by a Totem Warchief (Warcryer line): it stands where it was planted and pulses to everyone around every few seconds, see {@link Totems}.<br>
 * Monsters and the Warchief's enemies can break it; the Great Totem can't be targeted or hurt.
 * @author Mobius
 */
public class Totem extends Npc
{
	private final Creature _owner;
	private final Set<Totems.Kind> _kinds;
	private final int _range;
	private final long _plantedAt;
	private final long _expiresAt;
	private final boolean _great;
	private final int _skillLevel;
	private ScheduledFuture<?> _pulseTask;

	/**
	 * @param template the totem NPC template
	 * @param owner the Warchief (a player or a fake player)
	 * @param kinds what it pulses
	 * @param range how far the pulse reaches
	 * @param lifetime how long it stands, in milliseconds
	 * @param great {@code true} for the Great Totem of the Horde-Father
	 * @param skillLevel the level of the skill that planted it (the level of its echoes)
	 */
	public Totem(NpcTemplate template, Creature owner, Set<Totems.Kind> kinds, int range, long lifetime, boolean great, int skillLevel)
	{
		super(template);
		setInstanceType(InstanceType.Totem);
		_owner = owner;
		_kinds = kinds;
		_range = range;
		_great = great;
		_skillLevel = skillLevel;
		_plantedAt = System.currentTimeMillis();
		_expiresAt = _plantedAt + lifetime;
		setInstanceId(owner.getInstanceId());
		setRandomAnimationEnabled(false);
		setRandomWalking(false);
		setInvul(great);
	}

	/**
	 * Starts pulsing.
	 * @param period milliseconds between two pulses
	 */
	public void startPulse(long period)
	{
		_pulseTask = ThreadPool.scheduleAtFixedRate(() -> Totems.pulse(this), period, period);
	}

	/**
	 * Stops pulsing and removes the totem from the world. The monsters that were fighting it go back to the fight, see {@link #releaseAggro}.
	 */
	public void unsummon()
	{
		if (_pulseTask != null)
		{
			_pulseTask.cancel(false);
			_pulseTask = null;
		}

		Totems.forget(this);
		if (isSpawned())
		{
			final List<Attackable> monsters = World.getInstance().getVisibleObjects(this, Attackable.class);
			deleteMe();
			releaseAggro(monsters);
		}
	}

	/**
	 * The totem is gone (broken, shattered, spent or its time is up): the monsters that hated it forget it and attack the one they hate most now. One that hated nobody else turns on the Warchief, like a summon's aggro going to its owner. Otherwise a monster drawn by the Totem
	 * of the Horde could stand idle once the totem was gone.
	 * @param monsters the monsters that could see it
	 */
	private void releaseAggro(List<Attackable> monsters)
	{
		for (Attackable monster : monsters)
		{
			if (monster.isDead() || (monster.getAggroList().remove(this) == null))
			{
				continue;
			}

			if (monster.getTarget() == this)
			{
				monster.setTarget(null);
			}

			if (monster.isFakePlayer() || monster.isCoreAIDisabled() || !monster.hasAI())
			{
				continue;
			}

			Creature next = monster.getMostHated();
			if ((next == null) && !_owner.isDead() && _owner.isSpawned() && (_owner.getInstanceId() == monster.getInstanceId()) && _owner.isAutoAttackable(monster))
			{
				monster.addDamageHate(_owner, 0, 1);
				next = monster.getMostHated();
			}

			if (next != null)
			{
				monster.getAI().setIntention(Intention.ATTACK, next);
			}
		}
	}

	@Override
	public boolean doDie(Creature killer)
	{
		if (!super.doDie(killer))
		{
			return false;
		}

		unsummon();
		return true;
	}

	/**
	 * @return the Warchief that planted it
	 */
	public Creature getOwner()
	{
		return _owner;
	}

	/**
	 * @return what it pulses
	 */
	public Set<Totems.Kind> getKinds()
	{
		return _kinds;
	}

	/**
	 * @return how far its pulse reaches
	 */
	public int getRange()
	{
		return _range;
	}

	/**
	 * @return when it was planted
	 */
	public long getPlantedAt()
	{
		return _plantedAt;
	}

	/**
	 * @return when it falls
	 */
	public long getExpiresAt()
	{
		return _expiresAt;
	}

	/**
	 * @return {@code true} for the Great Totem of the Horde-Father
	 */
	public boolean isGreat()
	{
		return _great;
	}

	/**
	 * @return the level of the skill that planted it
	 */
	public int getSkillLevel()
	{
		return _skillLevel;
	}
	
	@Override
	public boolean isAutoAttackable(Creature attacker)
	{
		if (_great || (attacker == null) || (attacker == _owner) || isAlikeDead())
		{
			return false;
		}
		
		// Players and fake players who may fight its Warchief.
		if (attacker.isPlayable() || attacker.isFakePlayer())
		{
			return _owner.isAutoAttackable(attacker);
		}
		
		// Monsters break it (the Totem of the Horde draws them to it).
		if (attacker.isAttackable())
		{
			return true;
		}
		
		return false;
	}

	@Override
	public boolean isTargetable()
	{
		return !_great && super.isTargetable();
	}

	@Override
	public void showChatWindow(Player player)
	{
		player.sendPacket(ActionFailed.STATIC_PACKET);
	}

	@Override
	public boolean hasRandomAnimation()
	{
		return false;
	}
}
