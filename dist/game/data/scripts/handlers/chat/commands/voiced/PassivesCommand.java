package handlers.chat.commands.voiced;

import org.l2jmobius.gameserver.config.RatesConfig;
import org.l2jmobius.gameserver.handler.IVoicedCommandHandler;
import org.l2jmobius.gameserver.model.actor.Npc;
import org.l2jmobius.gameserver.model.actor.Player;
import org.l2jmobius.gameserver.model.skill.Skill;
import org.l2jmobius.gameserver.network.serverpackets.NpcHtmlMessage;

/**
 * @author YourName
 */
public class PassivesCommand implements IVoicedCommandHandler
{
	private static final String[] VOICED_COMMANDS =
	{
		"passives"
	};
	
	@Override
	public boolean onCommand(String command, Player player, String params)
	{
		return handleCommand(command, player, params);
	}
	
	@Override
	public boolean useVoicedCommand(String command, Player activeChar, String params)
	{
		return handleCommand(command, activeChar, params);
	}
	
	private boolean handleCommand(String command, Player player, String params)
	{
		if (command.equals("passives"))
		{
			if ((player.getTarget() == null) || !player.getTarget().isNpc())
			{
				player.sendMessage("You must target a monster to view its passive skills.");
				return true;
			}
			
			final Npc npc = (Npc) player.getTarget();
			
			if (!RatesConfig.RANDOM_PASSIVE_SKILLS_ENABLED)
			{
				player.sendMessage("The passive skills system is disabled.");
				return true;
			}
			
			final String idsAsString = npc.getVariables().getString("PASSIVE_SKILL_IDS", "");
			if (idsAsString.isEmpty())
			{
				player.sendMessage(npc.getName() + " has no randomly rolled passive skills.");
				return true;
			}
			
			showPassiveSkillsWindow(player, npc, idsAsString);
		}
		
		return true;
	}
	
	private void showPassiveSkillsWindow(Player player, Npc npc, String idsAsString)
	{
		final StringBuilder sb = new StringBuilder();
		sb.append("<html><body>");
		sb.append("<center><font color=\"LEVEL\">").append(npc.getName()).append("</font><br1>");
		sb.append("<font color=\"AAAAAA\">Rolled Passive Skills</font></center><br>");
		sb.append("<img src=\"L2UI.SquareGray\" width=280 height=1><br>");
		
		int shown = 0;
		sb.append("<table width=280 cellpadding=2 cellspacing=0>");
		
		for (String idStr : idsAsString.split(","))
		{
			try
			{
				int skillId = Integer.parseInt(idStr.trim());
				Skill skill = npc.getKnownSkill(skillId);
				if (skill == null)
				{
					continue;
				}
				
				sb.append("<tr>");
				sb.append("<td width=32>");
				sb.append("<img src=\"").append(skill.getIcon()).append("\" width=32 height=32>");
				sb.append("</td>");
				sb.append("<td valign=top>");
				sb.append("<font color=\"00FFFF\">").append(skill.getName()).append("</font><br1>");
				sb.append("<font color=\"999999\">Level ").append(skill.getLevel()).append("</font>");
				sb.append("</td>");
				sb.append("</tr>");
				
				shown++;
			}
			catch (NumberFormatException e)
			{
				// ignore malformed entry
			}
		}
		
		sb.append("</table>");
		
		if (shown == 0)
		{
			sb.append("<br><center><font color=\"777777\">No passives found.</font></center>");
		}
		
		sb.append("</body></html>");
		
		final NpcHtmlMessage html = new NpcHtmlMessage(0);
		html.setHtml(sb.toString());
		player.sendPacket(html);
	}
	
	@Override
	public String[] getCommandList()
	{
		return VOICED_COMMANDS;
	}
	
	@Override
	public String[] getVoicedCommandList()
	{
		return VOICED_COMMANDS;
	}
}