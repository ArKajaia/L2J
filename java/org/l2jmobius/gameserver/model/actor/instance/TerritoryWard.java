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

import org.l2jmobius.gameserver.ai.Intention;
import org.l2jmobius.gameserver.managers.TerritoryWarManager;
import org.l2jmobius.gameserver.model.actor.Attackable;
import org.l2jmobius.gameserver.model.actor.Creature;
import org.l2jmobius.gameserver.model.actor.Player;
import org.l2jmobius.gameserver.model.actor.templates.NpcTemplate;
import org.l2jmobius.gameserver.model.item.enums.ItemProcessType;
import org.l2jmobius.gameserver.model.skill.Skill;
import org.l2jmobius.gameserver.network.SystemMessageId;
import org.l2jmobius.gameserver.network.serverpackets.ActionFailed;
import org.l2jmobius.gameserver.network.serverpackets.SystemMessage;

public class TerritoryWard extends Attackable
{
	/**
	 * Creates territory ward.
	 * @param template the territory ward NPC template
	 */
	public TerritoryWard(NpcTemplate template)
	{
		super(template);
		
		disableCoreAI(true);
	}
	
	@Override
	public boolean isAutoAttackable(Creature attacker)
	{
		if (isInvul())
		{
			return false;
		}
		
		if ((getCastle() == null) || !getCastle().getZone().isActive())
		{
			return false;
		}
		
		final Player actingPlayer = attacker.asPlayer();
		if (actingPlayer == null)
		{
			return false;
		}
		
		if (actingPlayer.getSiegeSide() == 0)
		{
			return false;
		}
		
		if (TerritoryWarManager.getInstance().isAllyField(actingPlayer, getCastle().getResidenceId()))
		{
			return false;
		}
		
		return true;
	}
	
	@Override
	public boolean hasRandomAnimation()
	{
		return false;
	}
	
	@Override
	public void onSpawn()
	{
		super.onSpawn();
		
		if (getCastle() == null)
		{
			LOGGER.warning("TerritoryWard(" + getName() + ") spawned outside Castle Zone!");
		}
	}
	
	@Override
	public void reduceCurrentHp(double damage, Creature attacker, boolean awake, boolean isDOT, Skill skill)
	{
		if ((skill != null) || !TerritoryWarManager.getInstance().isTWInProgress())
		{
			return;
		}
		
		final Player actingPlayer = attacker.asPlayer();
		if (actingPlayer == null)
		{
			return;
		}
		
		if (actingPlayer.isCombatFlagEquipped())
		{
			return;
		}
		
		if (actingPlayer.getSiegeSide() == 0)
		{
			return;
		}
		
		if (getCastle() == null)
		{
			return;
		}
		
		if (TerritoryWarManager.getInstance().isAllyField(actingPlayer, getCastle().getResidenceId()))
		{
			return;
		}
		
		super.reduceCurrentHp(damage, attacker, awake, isDOT, skill);
	}
	
	@Override
	public void reduceCurrentHpByDOT(double i, Creature attacker, Skill skill)
	{
		// wards can't be damaged by DOTs
	}
	
	@Override
	public boolean doDie(Creature killer)
	{
		// Kill the Npc (the corpse disappeared after 7 seconds)
		if (!super.doDie(killer) || (getCastle() == null) || !TerritoryWarManager.getInstance().isTWInProgress())
		{
			return false;
		}
		
		if (killer.isPlayer())
		{
			final Player player = killer.asPlayer();
			if ((player.getSiegeSide() > 0) && !player.isCombatFlagEquipped())
			{
				player.addItem(ItemProcessType.PICKUP, getId() - 23012, 1, null, false);
			}
			else
			{
				TerritoryWarManager.getInstance().getTerritoryWard(getId() - 36491).spawnMe();
			}
			
			final SystemMessage sm = new SystemMessage(SystemMessageId.THE_S1_WARD_HAS_BEEN_DESTROYED_C2_NOW_HAS_THE_TERRITORY_WARD);
			sm.addString(getName().replace(" Ward", ""));
			sm.addPcName(player);
			TerritoryWarManager.getInstance().announceToParticipants(sm, 0, 0);
		}
		else
		{
			TerritoryWarManager.getInstance().getTerritoryWard(getId() - 36491).spawnMe();
		}
		
		decayMe();
		return true;
	}
	
	@Override
	public void onForcedAttack(Player player)
	{
		onAction(player);
	}
	
	@Override
	public void onAction(Player player, boolean interact)
	{
		if ((player == null) || !canTarget(player))
		{
			return;
		}
		
		// Check if the Player already target the Npc
		if (this != player.getTarget())
		{
			// Set the target of the Player player
			player.setTarget(this);
		}
		else if (interact)
		{
			if (isAutoAttackable(player) && (Math.abs(player.getZ() - getZ()) < 100))
			{
				player.getAI().setIntention(Intention.ATTACK, this);
			}
			else
			{
				// Send a Server->Client ActionFailed to the Player in order to avoid that the client wait another packet
				player.sendPacket(ActionFailed.STATIC_PACKET);
			}
		}
	}
}
