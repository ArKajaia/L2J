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
package handlers.chat.commands.admin;

import java.util.ArrayList;
import java.util.Map;
import java.util.StringTokenizer;
import java.util.TreeMap;

import org.l2jmobius.gameserver.config.custom.FakePlayerPvpConfig;
import org.l2jmobius.gameserver.data.xml.FakePlayerData;
import org.l2jmobius.gameserver.data.xml.FakePlayerPvpData;
import org.l2jmobius.gameserver.handler.IAdminCommandHandler;
import org.l2jmobius.gameserver.managers.FakePlayerChatManager;
import org.l2jmobius.gameserver.managers.FakePlayerPvpManager;
import org.l2jmobius.gameserver.model.World;
import org.l2jmobius.gameserver.model.actor.Npc;
import org.l2jmobius.gameserver.model.actor.Player;
import org.l2jmobius.gameserver.model.actor.holders.npc.FakePlayerPvpBuild;
import org.l2jmobius.gameserver.model.actor.holders.npc.FakePlayerPvpPersonality;
import org.l2jmobius.gameserver.model.actor.holders.npc.FakePlayerPvpProfile;

/**
 * @author Mobius
 */
public class AdminFakePlayers implements IAdminCommandHandler
{
	private static final String[] ADMIN_COMMANDS =
	{
		"admin_fakechat",
		"admin_fakepvp",
		"admin_fakepvp_list",
		"admin_fakepvp_clear"
	};
	
	@Override
	public boolean onCommand(String command, Player activeChar)
	{
		if (command.startsWith("admin_fakechat"))
		{
			final String[] words = command.substring(15).split(" ");
			if (words.length < 3)
			{
				activeChar.sendSysMessage("Usage: //fakechat playername fpcname message");
				return false;
			}
			
			final Player player = World.getInstance().getPlayer(words[0]);
			if (player == null)
			{
				activeChar.sendSysMessage("Player not found.");
				return false;
			}
			
			final String fpcName = FakePlayerData.getInstance().getProperName(words[1]);
			if (fpcName == null)
			{
				activeChar.sendSysMessage("Fake player not found.");
				return false;
			}
			
			String message = "";
			for (int i = 0; i < words.length; i++)
			{
				if (i < 2)
				{
					continue;
				}
				
				message += (words[i] + " ");
			}
			
			FakePlayerChatManager.getInstance().sendChat(player, fpcName, message);
			activeChar.sendSysMessage("Your message has been sent.");
		}
		else if (command.startsWith("admin_fakepvp_list"))
		{
			final StringBuilder sb = new StringBuilder();
			for (FakePlayerPvpBuild build : FakePlayerPvpData.getInstance().getBuilds())
			{
				sb.append(sb.length() > 0 ? ", " : "").append(build.getName().replace(" ", "").replace("'", ""));
			}
			activeChar.sendSysMessage("Builds: " + sb);
			activeChar.sendSysMessage("Roaming fake players alive: " + FakePlayerPvpManager.getInstance().getFakePlayers().size() + (FakePlayerPvpManager.getInstance().isEnabled() ? "" : " (spawning disabled)"));
			
			// How many of each build are around right now.
			final Map<String, Integer> alive = new TreeMap<>();
			for (Npc fake : FakePlayerPvpManager.getInstance().getFakePlayers())
			{
				alive.merge(fake.getTemplate().getFakePlayerPvpProfile().getBuild().getName(), 1, Integer::sum);
			}
			if (!alive.isEmpty())
			{
				activeChar.sendSysMessage("Alive by build: " + alive);
			}

			// The temper of the targeted one.
			if ((activeChar.getTarget() instanceof Npc) && (((Npc) activeChar.getTarget()).getTemplate().getFakePlayerPvpProfile() != null))
			{
				final Npc target = (Npc) activeChar.getTarget();
				final FakePlayerPvpPersonality personality = target.getTemplate().getFakePlayerPvpProfile().getPersonality();
				activeChar.sendSysMessage(target.getName() + ": " + personality);
				activeChar.sendSysMessage("Skill " + personality.getSkillChance() + "%/" + personality.getPvpSkillChance() + "% pvp, auto attacker skill " + personality.getAutoAttackSkillChance() + "%/" + personality.getAutoAttackPvpSkillChance() + "% pvp, debuff " + personality.getPvpDebuffChance() + "%");
				activeChar.sendSysMessage("Revenge " + personality.getRevengeChance() + "%, flagged " + personality.getAttackFlaggedChance() + "%, karma " + personality.getAttackKarmaChance() + "%, return " + personality.getReturnChance() + "%, runner " + target.getTemplate().getFakePlayerPvpProfile().isRunner());
				activeChar.sendSysMessage("Hunt " + personality.getHuntRange() + ", leash " + personality.getLeashRange() + ", chase " + personality.getChaseRange() + ", taunt " + personality.getTauntChance() + "%, greet " + personality.getGreetChance() + "%");
				final FakePlayerPvpProfile targetProfile = target.getTemplate().getFakePlayerPvpProfile();
				final long leaveIn = targetProfile.getLeaveTime() > 0 ? Math.max(0, (targetProfile.getLeaveTime() - System.currentTimeMillis()) / 1000) : -1;
				activeChar.sendSysMessage("Blessed SoE " + (targetProfile.hasBlessedEscape() ? "yes" : "no") + ", hotzone " + (targetProfile.getHotzoneId() > 0 ? targetProfile.getHotzoneId() + (leaveIn >= 0 ? " (leaving in " + leaveIn + "s)" : "") : "none"));
				activeChar.sendSysMessage("PvP taunt " + personality.getPokeChance(1) + "%-" + personality.getPokeChance(FakePlayerPvpConfig.POKE_MAX_CHANCE_LEVEL_DIFF) + "%, refuse to hit back " + personality.getRefuseChance(1) + "%-" + personality.getRefuseChance(FakePlayerPvpConfig.REFUSE_MAX_CHANCE_LEVEL_DIFF) + "% (1-" + FakePlayerPvpConfig.POKE_MAX_CHANCE_LEVEL_DIFF + "/" + FakePlayerPvpConfig.REFUSE_MAX_CHANCE_LEVEL_DIFF + " levels)");
			}
		}
		else if (command.startsWith("admin_fakepvp_clear"))
		{
			int count = 0;
			for (Npc fake : new ArrayList<>(FakePlayerPvpManager.getInstance().getFakePlayers()))
			{
				fake.deleteMe();
				count++;
			}
			// After the deletes: a dead one removed here would otherwise still come back.
			final int returns = FakePlayerPvpManager.getInstance().clearPendingReturns();
			activeChar.sendSysMessage("Removed " + count + " roaming fake players and " + returns + " on their way back.");
		}
		else if (command.startsWith("admin_fakepvp"))
		{
			final StringTokenizer st = new StringTokenizer(command);
			st.nextToken();
			FakePlayerPvpBuild build = null;
			int level = activeChar.getLevel();
			if (st.hasMoreTokens())
			{
				final String buildName = st.nextToken();
				if (!buildName.equalsIgnoreCase("random"))
				{
					build = FakePlayerPvpData.getInstance().getBuild(buildName);
					if (build == null)
					{
						activeChar.sendSysMessage("Unknown build. Use //fakepvp_list to see them.");
						return false;
					}
				}
			}
			
			if (st.hasMoreTokens())
			{
				try
				{
					level = Math.max(1, Math.min(85, Integer.parseInt(st.nextToken())));
				}
				catch (NumberFormatException e)
				{
					activeChar.sendSysMessage("Usage: //fakepvp [build|random] [level]");
					return false;
				}
			}
			
			final Npc fake = FakePlayerPvpManager.getInstance().spawnFakePlayer(build, level, activeChar.getX(), activeChar.getY(), activeChar.getZ(), activeChar.getInstanceId(), null, null);
			if (fake == null)
			{
				activeChar.sendSysMessage("Could not spawn a roaming fake player.");
				return false;
			}
			
			final FakePlayerPvpBuild usedBuild = fake.getTemplate().getFakePlayerPvpProfile().getBuild();
			activeChar.sendSysMessage("Spawned " + fake.getName() + ": level " + level + " " + usedBuild.getName() + " (" + fake.getTemplate().getFakePlayerPvpProfile().getPlayerClass().name() + ") HP " + (int) fake.getMaxHp() + " P.Atk " + (int) fake.getPAtk(null) + " M.Atk " + (int) fake.getMAtk(null, null) + " P.Def " + (int) fake.getPDef(null) + " M.Def " + (int) fake.getMDef(null, null) + ".");
		}
		
		return true;
	}
	
	@Override
	public String[] getCommandList()
	{
		return ADMIN_COMMANDS;
	}
}
