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
package ai.areas.CryptsOfDisgrace;

import org.l2jmobius.gameserver.model.actor.Npc;
import org.l2jmobius.gameserver.model.actor.Player;
import org.l2jmobius.gameserver.model.script.Script;
import org.l2jmobius.gameserver.network.NpcStringId;
import org.l2jmobius.gameserver.network.enums.ChatType;

/**
 * Turka Commander's Ghost AI.
 * @author Altur
 */
public class TurkaCommanderGhost extends Script
{
	// NPC
	private static final int TURKA_COMMANDER_GHOST = 22707;
	
	// Misc
	private static final String SHOUT_TIMER = "shout";
	
	private TurkaCommanderGhost()
	{
		addSpawnId(TURKA_COMMANDER_GHOST);
		addKillId(TURKA_COMMANDER_GHOST);
	}
	
	@Override
	public void onSpawn(Npc npc)
	{
		npc.broadcastSay(ChatType.NPC_SHOUT, NpcStringId.WHO_HAS_AWAKENED_US_FROM_OUR_SLUMBER);
		startQuestTimer(SHOUT_TIMER, 5000, npc, null);
	}
	
	@Override
	public String onEvent(String event, Npc npc, Player player)
	{
		if (event.equals(SHOUT_TIMER) && (npc != null) && !npc.isDead())
		{
			npc.broadcastSay(ChatType.NPC_SHOUT, NpcStringId.ALL_WILL_PAY_A_SEVERE_PRICE_TO_ME_AND_THESE_HERE);
		}
		
		return super.onEvent(event, npc, player);
	}
	
	@Override
	public void onKill(Npc npc, Player killer, boolean isSummon)
	{
		npc.broadcastSay(ChatType.NPC_SHOUT, NpcStringId.ALL_IS_VANITY_BUT_THIS_CANNOT_BE_THE_END);
	}
	
	public static void main(String[] args)
	{
		new TurkaCommanderGhost();
	}
}
