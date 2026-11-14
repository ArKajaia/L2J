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
package org.l2jmobius.gameserver.model.actor.status;

import org.l2jmobius.gameserver.model.actor.Creature;
import org.l2jmobius.gameserver.model.actor.Npc;
import org.l2jmobius.gameserver.model.actor.Player;
import org.l2jmobius.gameserver.model.actor.holders.player.Duel;
import org.l2jmobius.gameserver.model.passivetree.PassiveMechanics;
import org.l2jmobius.gameserver.model.skill.enums.SkillFinishType;
import org.l2jmobius.gameserver.model.stats.Stat;

public class NpcStatus extends CreatureStatus
{
	private static final int ARCANE_SHIELD = 1556;
	
	public NpcStatus(Npc activeChar)
	{
		super(activeChar);
	}
	
	@Override
	public void reduceHp(double value, Creature attacker)
	{
		reduceHp(value, attacker, true, false, false);
	}
	
	@Override
	public void reduceHp(double value, Creature attacker, boolean awake, boolean isDOT, boolean isHpConsumption)
	{
		final Npc npc = getActiveChar();
		if (npc.isDead())
		{
			return;
		}
		
		if (attacker != null)
		{
			final Player attackerPlayer = attacker.asPlayer();
			if ((attackerPlayer != null) && attackerPlayer.isInDuel())
			{
				attackerPlayer.setDuelState(Duel.DUELSTATE_INTERRUPTED);
			}
			
			// Add attackers to npc's attacker list
			npc.addAttackerToAttackByList(attacker);
		}
		
		double damage = value;
		if (npc.isPvpFakePlayer() && !isHpConsumption)
		{
			// Arcane Shield of a fake player, like a player's (PlayerStatus): the hit costs 30% of its damage in MP and no HP, until MP runs out. Before, only a player's status read the stat and a fake wizard's shield did nothing.
			if ((attacker != null) && (attacker != npc))
			{
				final int shield = (int) npc.getStat().calcStat(Stat.MANA_SHIELD_PERCENT, 0, null, null);
				if (shield > 0)
				{
					final double mpDamage = damage - ((damage * shield) / 100);
					if (mpDamage > npc.getCurrentMp())
					{
						npc.stopSkillEffects(SkillFinishType.REMOVED, ARCANE_SHIELD);
						damage = mpDamage - npc.getCurrentMp();
						npc.setCurrentMp(0);
					}
					else
					{
						npc.reduceCurrentMp(mpDamage);
						return;
					}
				}
			}
			// Passive tree Mind Over Matter of a roaming fake player: part of the damage is taken from MP first, like a player's.
			damage = PassiveMechanics.absorbDamageWithMp(npc, damage);
		}
		super.reduceHp(damage, attacker, awake, isDOT, isHpConsumption);
	}
	
	@Override
	public Npc getActiveChar()
	{
		return super.getActiveChar().asNpc();
	}
}
