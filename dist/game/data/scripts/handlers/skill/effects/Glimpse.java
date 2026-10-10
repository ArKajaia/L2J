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

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.l2jmobius.gameserver.config.custom.OraclesWarchiefsConfig;
import org.l2jmobius.gameserver.model.StatSet;
import org.l2jmobius.gameserver.model.actor.Creature;
import org.l2jmobius.gameserver.model.actor.holders.player.AreaTargets;
import org.l2jmobius.gameserver.model.conditions.Condition;
import org.l2jmobius.gameserver.model.effects.AbstractEffect;
import org.l2jmobius.gameserver.model.events.EventType;
import org.l2jmobius.gameserver.model.events.holders.actor.creature.OnCreatureDamageReceived;
import org.l2jmobius.gameserver.model.events.listeners.ConsumerEventListener;
import org.l2jmobius.gameserver.model.skill.Skill;
import org.l2jmobius.gameserver.network.serverpackets.MagicSkillUse;

/**
 * Glimpse (Hierophant toggle): the Oracle sees the blow coming. A single hit taking a big part of its HP makes it step back, away from the attacker, once in a while (config/Custom/OraclesWarchiefs.ini).
 * @author Mobius
 */
public class Glimpse extends AbstractEffect
{
	/** When each Oracle may step back again, by object id. */
	private static final Map<Integer, Long> READY_AT = new ConcurrentHashMap<>();
	
	public Glimpse(Condition attachCond, Condition applyCond, StatSet set, StatSet params)
	{
		super(attachCond, applyCond, set, params);
	}
	
	@Override
	public void onStart(Creature effector, Creature effected, Skill skill)
	{
		effected.addListener(new ConsumerEventListener(effected, EventType.ON_CREATURE_DAMAGE_RECEIVED, (OnCreatureDamageReceived event) -> onDamageReceived(event, skill), this));
	}
	
	@Override
	public boolean onActionTime(Creature effector, Creature effected, Skill skill)
	{
		return !effected.isDead() && skill.isToggle();
	}
	
	@Override
	public void onExit(Creature effector, Creature effected, Skill skill)
	{
		effected.removeListenerIf(EventType.ON_CREATURE_DAMAGE_RECEIVED, listener -> listener.getOwner() == this);
	}
	
	private void onDamageReceived(OnCreatureDamageReceived event, Skill skill)
	{
		final Creature oracle = event.getTarget();
		final Creature attacker = event.getAttacker();
		if (event.isDamageOverTime() || (attacker == null) || (attacker == oracle) || oracle.isDead() || oracle.isMovementDisabled() || (event.getDamage() < (oracle.getMaxHp() * OraclesWarchiefsConfig.GLIMPSE_HP_THRESHOLD)))
		{
			return;
		}
		
		final long now = System.currentTimeMillis();
		final Long readyAt = READY_AT.get(oracle.getObjectId());
		if ((readyAt != null) && (readyAt > now))
		{
			return;
		}
		READY_AT.put(oracle.getObjectId(), now + OraclesWarchiefsConfig.GLIMPSE_COOLDOWN);
		
		// Away from the attacker (or backwards when it stands on top of the Oracle).
		double dx = oracle.getX() - attacker.getX();
		double dy = oracle.getY() - attacker.getY();
		double length = Math.sqrt((dx * dx) + (dy * dy));
		if (length < 1)
		{
			final double heading = Math.toRadians((oracle.getHeading() / 182.044444444) + 180);
			dx = Math.cos(heading);
			dy = Math.sin(heading);
			length = 1;
		}
		
		final int x = oracle.getX() + (int) ((dx / length) * OraclesWarchiefsConfig.GLIMPSE_DISTANCE);
		final int y = oracle.getY() + (int) ((dy / length) * OraclesWarchiefsConfig.GLIMPSE_DISTANCE);
		oracle.broadcastPacket(new MagicSkillUse(oracle, oracle, skill.getDisplayId(), 1, 0, 0));
		AreaTargets.flyTo(oracle, x, y, oracle.getZ());
	}
}
