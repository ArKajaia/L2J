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

import org.l2jmobius.gameserver.model.actor.Player;
import org.l2jmobius.gameserver.model.actor.Summon;
import org.l2jmobius.gameserver.model.actor.templates.NpcTemplate;
import org.l2jmobius.gameserver.model.krateisCube.KrateiArena;
import org.l2jmobius.gameserver.model.olympiad.OlympiadManager;
import org.l2jmobius.gameserver.network.SystemMessageId;

/**
 * @author LordWinter
 */
public class KrateisCubeManager extends Folk
{
	public KrateisCubeManager(NpcTemplate template)
	{
		super(template);
	}
	
	@Override
	public void onBypassFeedback(Player player, String command)
	{
		if (command.startsWith("Register"))
		{
			if ((player.getInventoryLimit() * 0.8) <= player.getInventory().getSize())
			{
				player.sendPacket(SystemMessageId.YOU_CAN_PROCEED_ONLY_WHEN_THE_INVENTORY_WEIGHT_IS_BELOW_80_PERCENT_AND_THE_QUANTITY_IS_BELOW_90_PERCENT);
				showChatWindow(player, "data/html/krateisCube/32503-08.htm");
				return;
			}
			
			if ((player.getKrateiArena() == null) && (OlympiadManager.getInstance().isRegistered(player) || player.isInOlympiadMode() || player.isOnEvent() || player.isRegisteredOnEvent()))
			{
				player.sendPacket(SystemMessageId.YOU_CANNOT_BE_SIMULTANEOUSLY_REGISTERED_FOR_PVP_MATCHES_SUCH_AS_THE_OLYMPIAD_UNDERGROUND_COLISEUM_AERIAL_CLEFT_KRATEI_S_CUBE_AND_HANDY_S_BLOCK_CHECKERS);
				return;
			}
			
			if (((player.getParty() != null) && (player.getParty().getUCState() != null)) || (player.getUCState() > 0))
			{
				player.sendPacket(SystemMessageId.YOU_CANNOT_BE_SIMULTANEOUSLY_REGISTERED_FOR_PVP_MATCHES_SUCH_AS_THE_OLYMPIAD_UNDERGROUND_COLISEUM_AERIAL_CLEFT_KRATEI_S_CUBE_AND_HANDY_S_BLOCK_CHECKERS);
				return;
			}
			
			if (player.isCursedWeaponEquipped())
			{
				player.sendPacket(SystemMessageId.YOU_CANNOT_REGISTER_WHILE_IN_POSSESSION_OF_A_CURSED_WEAPON);
				return;
			}
			
			final int id = Integer.parseInt(command.substring(9, 10).trim());
			final KrateiArena arena = org.l2jmobius.gameserver.managers.games.KrateisCubeManager.getInstance().getArenaId(id);
			if (arena != null)
			{
				if ((player.getLevel() < arena.getMinLevel()) || (player.getLevel() > arena.getMaxLevel()))
				{
					showChatWindow(player, "data/html/krateisCube/32503-06.htm");
					return;
				}
			}
			else
			{
				showChatWindow(player, "data/html/krateisCube/32503-09.htm");
				return;
			}
			
			if (org.l2jmobius.gameserver.managers.games.KrateisCubeManager.getInstance().isRegisterTime())
			{
				if (arena.addRegisterPlayer(player))
				{
					showChatWindow(player, "data/html/krateisCube/32503-03.htm");
				}
				else
				{
					showChatWindow(player, "data/html/krateisCube/32503-04.htm");
				}
			}
			else
			{
				showChatWindow(player, "data/html/krateisCube/32503-07.htm");
			}
		}
		else if (command.startsWith("SeeList"))
		{
			if (player.getLevel() < 70)
			{
				showChatWindow(player, "data/html/krateisCube/32503-09.htm");
			}
			else
			{
				showChatWindow(player, "data/html/krateisCube/32503-02.htm");
			}
		}
		else if (command.startsWith("Cancel"))
		{
			for (KrateiArena arena : org.l2jmobius.gameserver.managers.games.KrateisCubeManager.getInstance().getArenas().values())
			{
				if ((arena != null) && arena.removePlayer(player))
				{
					showChatWindow(player, "data/html/krateisCube/32503-05.htm");
					break;
				}
			}
		}
		else if (command.startsWith("TeleportToFI"))
		{
			player.teleToLocation(-59193, -56893, -2034, true);
			final Summon pet = player.getSummon();
			if (pet != null)
			{
				pet.teleToLocation(-59193, -56893, -2034, true);
			}
		}
		else
		{
			super.onBypassFeedback(player, command);
		}
	}
	
	@Override
	public String getHtmlPath(int npcId, int val)
	{
		String pom = "";
		
		if (val == 0)
		{
			pom = "" + npcId;
		}
		else
		{
			pom = npcId + "-0" + val;
		}
		
		return "data/html/krateisCube/" + pom + ".htm";
	}
}
