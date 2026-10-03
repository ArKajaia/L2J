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
import org.l2jmobius.gameserver.network.NpcStringId;
import org.l2jmobius.gameserver.network.enums.ChatType;

/**
 * AI for Kamaloka (26) - Ol Ariosh.<br>
 * While in combat he calls one Follower of Ariosh every minute, but only when the previous one is dead.
 */
public class OlAriosh extends Script
{
	// NPCs
	private static final int OL_ARIOSH = 18555;
	private static final int FOLLOWER_OF_ARIOSH = 18556;
	
	// Misc
	private static final String FOLLOWER = "FOLLOWER";
	private static final int SUMMON_INTERVAL = 60000;
	
	private OlAriosh()
	{
		addAttackId(OL_ARIOSH);
		addKillId(OL_ARIOSH);
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
			
			final Npc follower = npc.getVariables().getObject(FOLLOWER, Npc.class);
			if ((follower == null) || follower.isDead())
			{
				final Npc newFollower = addSpawn(FOLLOWER_OF_ARIOSH, npc.getX(), npc.getY(), npc.getZ(), npc.getHeading(), true, 0, false, npc.getInstanceId());
				npc.getVariables().set(FOLLOWER, newFollower);
				npc.broadcastSay(ChatType.NPC_GENERAL, NpcStringId.WHAT_ARE_YOU_DOING_HURRY_UP_AND_HELP_ME);
				final WorldObject target = npc.getTarget();
				if ((target != null) && target.isPlayable())
				{
					addAttackDesire(newFollower, target.asPlayable());
				}
			}
			
			startQuestTimer("SUMMON", SUMMON_INTERVAL, npc, null);
		}
		
		return super.onEvent(event, npc, player);
	}
	
	@Override
	public void onAttack(Npc npc, Player attacker, int damage, boolean isSummon)
	{
		if (npc.isScriptValue(0))
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
		new OlAriosh();
	}
}
