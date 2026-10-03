/*
 * This file is part of the L2J Mobius project.
 * 
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 * 
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * General Public License for more details.
 * 
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>.
 */
package ai.others;

import org.l2jmobius.gameserver.model.WorldObject;
import org.l2jmobius.gameserver.model.actor.Npc;
import org.l2jmobius.gameserver.model.actor.Player;
import org.l2jmobius.gameserver.model.script.Script;

/**
 * AI for Kamaloka (36) - Seer Flouros.<br>
 * While in combat he summons a Follower of Flouros every minute, whether or not the earlier ones are still alive, up to 10 in total.
 */
public class SeerFlouros extends Script
{
	// NPCs
	private static final int SEER_FLOUROS = 18559;
	private static final int FOLLOWER_OF_FLOUROS = 18560;
	
	// Misc
	private static final String SUMMON_COUNT = "SUMMON_COUNT";
	private static final int SUMMON_INTERVAL = 60000;
	private static final int SUMMON_LIMIT = 10;
	
	private SeerFlouros()
	{
		addAttackId(SEER_FLOUROS);
		addKillId(SEER_FLOUROS);
	}
	
	@Override
	public String onEvent(String event, Npc npc, Player player)
	{
		if (event.equals("SUMMON") && (npc != null))
		{
			if (npc.isDead() || !npc.isInCombat())
			{
				npc.setScriptValue(0);
				return null;
			}
			
			final int count = npc.getVariables().getInt(SUMMON_COUNT, 0);
			if (count >= SUMMON_LIMIT)
			{
				return null;
			}
			
			final Npc follower = addSpawn(FOLLOWER_OF_FLOUROS, npc.getX(), npc.getY(), npc.getZ(), npc.getHeading(), true, 0, false, npc.getInstanceId());
			npc.getVariables().set(SUMMON_COUNT, count + 1);
			final WorldObject target = npc.getTarget();
			if ((target != null) && target.isPlayable())
			{
				addAttackDesire(follower, target.asPlayable());
			}
			
			startQuestTimer("SUMMON", SUMMON_INTERVAL, npc, null);
		}
		
		return super.onEvent(event, npc, player);
	}
	
	@Override
	public void onAttack(Npc npc, Player attacker, int damage, boolean isSummon)
	{
		if (npc.isScriptValue(0) && (npc.getVariables().getInt(SUMMON_COUNT, 0) < SUMMON_LIMIT))
		{
			npc.setScriptValue(1);
			startQuestTimer("SUMMON", SUMMON_INTERVAL, npc, null);
		}
	}
	
	@Override
	public void onKill(Npc npc, Player player, boolean isSummon)
	{
		cancelQuestTimer("SUMMON", npc, null);
	}
	
	public static void main(String[] args)
	{
		new SeerFlouros();
	}
}
