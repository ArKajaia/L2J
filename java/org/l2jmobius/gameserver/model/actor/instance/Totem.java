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
import org.l2jmobius.gameserver.model.actor.stat.NpcStat;
import org.l2jmobius.gameserver.model.actor.templates.NpcTemplate;
import org.l2jmobius.gameserver.model.skill.Skill;
import org.l2jmobius.gameserver.network.serverpackets.ActionFailed;
import org.l2jmobius.gameserver.network.serverpackets.DeleteObject;

/**
 * A totem planted by a Totem Warchief (Warcryer line): it stands where it was planted and pulses to everyone around every few seconds, see {@link Totems}.<br>
 * Monsters and the Warchief's enemies can break it. The Great Totem can't be targeted or hurt; the totems it raises can't be hurt, but monsters still turn on them (the Totem of the Horde). It is as high level as its Warchief, its max HP is a part of the Warchief's (when planted), and its P. Def. and M. Def. are those of its NPC template scaled to its level.
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
	private final boolean _forced;
	private final int _maxHp;
	private ScheduledFuture<?> _pulseTask;

	/**
	 * @param template the totem NPC template
	 * @param owner the Warchief (a player or a fake player)
	 * @param kinds what it pulses
	 * @param range how far the pulse reaches
	 * @param lifetime how long it stands, in milliseconds
	 * @param great {@code true} for the Great Totem of the Horde-Father and the totems it raises
	 * @param skillLevel the level of the skill that planted it (the level of its echoes)
	 * @param forced {@code true} if it was planted with Ctrl held: its pulses also reach the white players and fake players around, see {@link Totems}
	 * @param maxHp its max HP
	 */
	public Totem(NpcTemplate template, Creature owner, Set<Totems.Kind> kinds, int range, long lifetime, boolean great, int skillLevel, boolean forced, int maxHp)
	{
		super(template);
		setInstanceType(InstanceType.Totem);
		_owner = owner;
		_kinds = kinds;
		_range = range;
		_great = great;
		_skillLevel = skillLevel;
		_forced = forced;
		_maxHp = maxHp;
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
	 * @return {@code true} for the Great Totem of the Horde-Father and the totems it raises (they don't count)
	 */
	public boolean isGreat()
	{
		return _great;
	}

	/**
	 * @return {@code true} for the Great Totem of the Horde-Father itself, which only marks the spot of the totems it raises
	 */
	public boolean isGreatCenter()
	{
		return _great && _kinds.isEmpty();
	}

	/**
	 * @return the level of the skill that planted it
	 */
	public int getSkillLevel()
	{
		return _skillLevel;
	}

	/**
	 * @return {@code true} if it was planted with Ctrl held (its pulses also reach white players and fake players)
	 */
	public boolean isForced()
	{
		return _forced;
	}

	/**
	 * Moves it at once (Spirit Walk). It is drawn again where it stands now for everyone around, since a client can keep showing a standing npc where it was.
	 * @param x where to
	 * @param y where to
	 * @param z where to
	 * @param heading its new heading
	 */
	public void relocate(int x, int y, int z, int heading)
	{
		World.getInstance().forEachVisibleObject(this, Player.class, player -> player.sendPacket(new DeleteObject(this)));
		setXYZ(x, y, z);
		setHeading(heading);
		World.getInstance().forEachVisibleObject(this, Player.class, player ->
		{
			if (isVisibleFor(player))
			{
				sendInfo(player);
			}
		});
	}
	
	/**
	 * @return as high level as its Warchief
	 */
	@Override
	public int getLevel()
	{
		return _owner != null ? _owner.getLevel() : super.getLevel();
	}

	@Override
	public void initCharStat()
	{
		setStat(new TotemStat(this));
	}

	/**
	 * A totem's level, max HP and defences follow its Warchief (see {@link Totem}).
	 */
	private static class TotemStat extends NpcStat
	{
		TotemStat(Totem totem)
		{
			super(totem);
		}

		private Totem getTotem()
		{
			return (Totem) getActiveChar();
		}

		/**
		 * @return its level over that of its NPC template, by which its defences scale
		 */
		private double getLevelScale()
		{
			final int templateLevel = getTotem().getTemplate().getLevel();
			return templateLevel > 0 ? (double) getTotem().getLevel() / templateLevel : 1;
		}

		@Override
		public byte getLevel()
		{
			return (byte) getTotem().getLevel();
		}

		@Override
		public int getMaxHp()
		{
			// Not yet known while the totem is being made.
			final int maxHp = getTotem()._maxHp;
			return maxHp > 0 ? maxHp : super.getMaxHp();
		}

		@Override
		public double getPDef(Creature target)
		{
			return super.getPDef(target) * getLevelScale();
		}

		@Override
		public double getMDef(Creature target, Skill skill)
		{
			return super.getMDef(target, skill) * getLevelScale();
		}
	}

	@Override
	public boolean isAutoAttackable(Creature attacker)
	{
		if (isGreatCenter() || (attacker == null) || (attacker == _owner) || isAlikeDead())
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
		return !isGreatCenter() && super.isTargetable();
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
