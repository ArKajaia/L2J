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
package ai.areas.MonasteryOfSilence;

import org.l2jmobius.gameserver.ai.Intention;
import org.l2jmobius.gameserver.model.World;
import org.l2jmobius.gameserver.model.WorldObject;
import org.l2jmobius.gameserver.model.actor.Creature;
import org.l2jmobius.gameserver.model.actor.Npc;
import org.l2jmobius.gameserver.model.actor.Player;
import org.l2jmobius.gameserver.model.script.Script;
import org.l2jmobius.gameserver.model.skill.Skill;
import org.l2jmobius.gameserver.model.skill.holders.SkillHolder;

/**
 * Etis van Etina's Phantom AI (Seven Signs SSQ2 boss decoy).
 * @author Altur
 */
public class EtisVanEtinaPhantom extends Script
{
	// NPCs
	private static final int PHANTOM_REAL = 18950;
	private static final int PHANTOM_FAKE = 18951;
	// Skills
	private static final SkillHolder PETRIFY = new SkillHolder(6735, 1);
	private static final SkillHolder DF_SELF_DESTRUCTION = new SkillHolder(6708, 1);
	private static final int SELF_DESTRUCTION_DR_ID = 6707;
	private static final int SELF_DESTRUCTION_DF_ID = 6708;
	// Events
	private static final String EVT_PHANTOM_REAL_DEAD = "ETIS_PHANTOM_REAL_DEAD";
	private static final String EVT_BOSS_DEAD = "ETIS_BOSS_DEAD";
	// Timers
	private static final String CAST_PETRIFY = "CAST_PETRIFY";
	private static final String DELAYED_ATTACK = "DELAYED_ATTACK";
	private static final String DF_THINK = "DF_THINK";
	// Misc
	private static final int BROADCAST_RADIUS = 5000;
	private static final int PETRIFY_DELAY = 1000;
	private static final int PETRIFY_DURATION = 5000;
	private static final int POST_PETRIFY_BUFFER = 500;
	private static final int DF_THINK_INTERVAL = 500;
	private static final int DF_BLAST_RANGE = 100;
	private static final int TARGET_SCAN_RANGE = 1500;
	
	private EtisVanEtinaPhantom()
	{
		addSpawnId(PHANTOM_REAL, PHANTOM_FAKE);
		addKillId(PHANTOM_REAL);
		addEventReceivedId(PHANTOM_REAL, PHANTOM_FAKE);
		addSpellFinishedId(PHANTOM_REAL, PHANTOM_FAKE);
	}
	
	@Override
	public String onEvent(String event, Npc npc, Player player)
	{
		if ((npc == null) || npc.isDead())
		{
			return super.onEvent(event, npc, player);
		}
		
		if (event.equals(CAST_PETRIFY))
		{
			if (!npc.isCastingNow())
			{
				npc.doCast(PETRIFY.getSkill());
			}
		}
		else if (event.equals(DELAYED_ATTACK))
		{
			final Creature mostHated = npc.asAttackable().getMostHated();
			if ((mostHated != null) && !mostHated.isDead())
			{
				npc.setRunning();
				npc.getAI().setIntention(Intention.ATTACK, mostHated);
			}
		}
		else if (event.equals(DF_THINK) && (npc.getId() == PHANTOM_FAKE))
		{
			final WorldObject currentTarget = npc.getTarget();
			Player nearestPlayer = null;
			if ((currentTarget != null) && currentTarget.isPlayer() && !currentTarget.asPlayer().isDead())
			{
				nearestPlayer = currentTarget.asPlayer();
			}
			else
			{
				final Player[] found = new Player[1];
				World.getInstance().forEachVisibleObjectInRange(npc, Player.class, DF_BLAST_RANGE, p ->
				{
					if ((found[0] == null) && !p.isDead() && !p.isInvisible())
					{
						found[0] = p;
					}
				});
				nearestPlayer = found[0];
				
				if (nearestPlayer != null)
				{
					npc.setTarget(nearestPlayer);
				}
			}
			
			if ((nearestPlayer != null) && (npc.calculateDistance3D(nearestPlayer) <= DF_BLAST_RANGE) && !npc.isCastingNow())
			{
				npc.doCast(DF_SELF_DESTRUCTION.getSkill());
			}
			
			startQuestTimer(DF_THINK, DF_THINK_INTERVAL, npc, null);
		}
		
		return super.onEvent(event, npc, player);
	}
	
	@Override
	public void onSpellFinished(Npc npc, Player player, Skill skill)
	{
		if ((npc == null) || npc.isDead() || (skill == null))
		{
			return;
		}
		
		final int skillId = skill.getId();
		if ((skillId == SELF_DESTRUCTION_DR_ID) || (skillId == SELF_DESTRUCTION_DF_ID))
		{
			npc.doDie(null);
		}
	}
	
	@Override
	public String onEventReceived(String eventName, Npc sender, Npc receiver, WorldObject reference)
	{
		if ((receiver == null) || receiver.isDead())
		{
			return super.onEventReceived(eventName, sender, receiver, reference);
		}
		
		if (eventName.equals(EVT_BOSS_DEAD))
		{
			receiver.deleteMe();
		}
		else if (eventName.equals(EVT_PHANTOM_REAL_DEAD) && (receiver.getId() == PHANTOM_FAKE))
		{
			receiver.deleteMe();
		}
		
		return super.onEventReceived(eventName, sender, receiver, reference);
	}
	
	@Override
	public void onSpawn(Npc npc)
	{
		final Player[] target = new Player[1];
		final int targetId = npc.getVariables().getInt("etisTargetId", 0);
		if (targetId > 0)
		{
			final Player p = World.getInstance().getPlayer(targetId);
			if ((p != null) && !p.isDead())
			{
				target[0] = p;
			}
		}
		
		if (target[0] == null)
		{
			World.getInstance().forEachVisibleObjectInRange(npc, Player.class, TARGET_SCAN_RANGE, p ->
			{
				if ((target[0] == null) && !p.isDead() && !p.isInvisible())
				{
					target[0] = p;
				}
			});
		}
		
		if (target[0] != null)
		{
			npc.asAttackable().addDamageHate(target[0], 0, 999);
			npc.setTarget(target[0]);
		}
		
		startQuestTimer(CAST_PETRIFY, PETRIFY_DELAY, npc, null);
		startQuestTimer(DELAYED_ATTACK, PETRIFY_DELAY + PETRIFY_DURATION + POST_PETRIFY_BUFFER, npc, null);
		if (npc.getId() == PHANTOM_FAKE)
		{
			startQuestTimer(DF_THINK, PETRIFY_DELAY + PETRIFY_DURATION + POST_PETRIFY_BUFFER + DF_THINK_INTERVAL, npc, null);
		}
	}
	
	@Override
	public void onKill(Npc npc, Player killer, boolean isSummon)
	{
		if (npc.getId() == PHANTOM_REAL)
		{
			npc.broadcastEvent(EVT_PHANTOM_REAL_DEAD, BROADCAST_RADIUS, null);
		}
	}
	
	public static void main(String[] args)
	{
		new EtisVanEtinaPhantom();
	}
}
