package handlers.chat.commands.voiced;

import org.l2jmobius.gameserver.handler.IVoicedCommandHandler;
import org.l2jmobius.gameserver.model.actor.Player;
import org.l2jmobius.gameserver.network.serverpackets.ActionFailed;
import org.l2jmobius.gameserver.network.serverpackets.NpcHtmlMessage;

public class VoiceHelp implements IVoicedCommandHandler
{
	private static final String[] VOICED_COMMANDS =
	{
		"help",
		"menu",
		"commands"
	};
	
	@Override
	public boolean onCommand(String command, Player activeChar, String params)
	{
		if (activeChar == null)
		{
			return false;
		}
		
		showHelpWindow(activeChar);
		return true;
	}
	
	private void showHelpWindow(Player player)
	{
		final NpcHtmlMessage html = new NpcHtmlMessage(0);
		final StringBuilder sb = new StringBuilder();
		
		sb.append("<html><body><center>");
		
		// Header
		sb.append("<table width=270 height=35 bgcolor=000000>");
		sb.append("<tr><td align=center valign=middle><font color=\"LEVEL\"><b>SERVER COMMANDS</b></font></td></tr>");
		sb.append("</table><br>");
		
		sb.append("<font color=\"A2A2A2\">List of available custom voice commands:</font><br><br>");
		
		// Commands Table
		sb.append("<table width=270 border=0 cellspacing=3 cellpadding=3 bgcolor=111111>");
		
		// Help Command
		sb.append("<tr>");
		sb.append("<td width=100><font color=\"00FFFF\">.help</font></td>");
		sb.append("<td width=170><font color=\"B0B0B0\">Shows this information menu.</font></td>");
		sb.append("</tr>");
		sb.append("<tr><td colspan=2 height=1 bgcolor=222222></td></tr>");
		
		// Filter Command
		sb.append("<tr>");
		sb.append("<td width=100><font color=\"00FFFF\">.filter</font><br1><font color=\"707070\">.autoloot</font></td>");
		sb.append("<td width=170><font color=\"B0B0B0\">Opens the Auto-Loot filter menu to block junk items.</font></td>");
		sb.append("</tr>");
		sb.append("<tr><td colspan=2 align=right><button value=\"Open Filter\" action=\"bypass voiced_filter\" width=80 height=22 back=\"L2UI_CT1.Button_DF_Down\" fore=\"L2UI_CT1.Button_DF\"></td></tr>");
		sb.append("<tr><td colspan=2 height=1 bgcolor=222222></td></tr>");
		
		// DPS Command
		sb.append("<tr>");
		sb.append("<td width=100><font color=\"00FFFF\">.dps</font><br1><font color=\"707070\">.meter</font></td>");
		sb.append("<td width=170><font color=\"B0B0B0\">Displays your current combat damage and EXP/Hour rates.</font></td>");
		sb.append("</tr>");
		sb.append("<tr><td colspan=2 align=right><button value=\"Open Meter\" action=\"bypass voiced_dps\" width=80 height=22 back=\"L2UI_CT1.Button_DF_Down\" fore=\"L2UI_CT1.Button_DF\"></td></tr>");
		sb.append("<tr><td colspan=2 height=1 bgcolor=222222></td></tr>");
		
		// ExpOff / ExpOn Command
		sb.append("<tr>");
		sb.append("<td width=100><font color=\"00FFFF\">.expoff<br1>.expon</font></td>");
		sb.append("<td width=170><font color=\"B0B0B0\">Disables and enables EXP gain.</font></td>");
		sb.append("</tr>");
		sb.append("<tr><td colspan=2 align=right>");
		sb.append("<button value=\"Exp OFF\" action=\"bypass expoff\" width=65 height=20 back=\"L2UI_CT1.Button_DF_Down\" fore=\"L2UI_CT1.Button_DF\"> ");
		sb.append("<button value=\"Exp ON\" action=\"bypass expon\" width=65 height=20 back=\"L2UI_CT1.Button_DF_Down\" fore=\"L2UI_CT1.Button_DF\">");
		sb.append("</td></tr>");
		sb.append("<tr><td colspan=2 height=1 bgcolor=222222></td></tr>");
		
		// Skills Command
		sb.append("<tr>");
		sb.append("<td width=100><font color=\"00FFFF\">.skills</font></td>");
		sb.append("<td width=170><font color=\"B0B0B0\">Displays target's randomly added skills.</font></td>");
		sb.append("</tr>");
		sb.append("<tr><td colspan=2 align=right><button value=\"Check Skills\" action=\"bypass skills\" width=80 height=22 back=\"L2UI_CT1.Button_DF_Down\" fore=\"L2UI_CT1.Button_DF\"></td></tr>");
		sb.append("<tr><td colspan=2 height=1 bgcolor=222222></td></tr>");
		
		// Banking Command
		sb.append("<tr>");
		sb.append("<td width=100><font color=\"00FFFF\">.banking</font></td>");
		sb.append("<td width=170><font color=\"B0B0B0\">Exchange Adena to Gold Bars.</font></td>");
		sb.append("</tr>");
		sb.append("<tr><td colspan=2 height=1 bgcolor=222222></td></tr>");
		
		// Auto Play Commands
		sb.append("<tr>");
		sb.append("<td width=100><font color=\"00FFFF\">.play</font></td>");
		sb.append("<td width=170><font color=\"B0B0B0\">Auto Play options (.playskills, .playitems, .playpotion)</font></td>");
		sb.append("</tr>");
		sb.append("<tr><td colspan=2 height=1 bgcolor=222222></td></tr>");
		
		// Auto Potion Commands
		sb.append("<tr>");
		sb.append("<td width=100><font color=\"00FFFF\">.apon / .apoff</font></td>");
		sb.append("<td width=170><font color=\"B0B0B0\">Toggle Auto Potion (.potionon, .potionoff)</font></td>");
		sb.append("</tr>");
		sb.append("<tr><td colspan=2 height=1 bgcolor=222222></td></tr>");
		
		// Hellbound
		sb.append("<tr>");
		sb.append("<td width=100><font color=\"00FFFF\">.hellbound</font></td>");
		sb.append("<td width=170><font color=\"B0B0B0\">Displays current Hellbound trust level.</font></td>");
		sb.append("</tr>");
		sb.append("<tr><td colspan=2 height=1 bgcolor=222222></td></tr>");
		
		// Offline Shops / Play
		sb.append("<tr>");
		sb.append("<td width=100><font color=\"00FFFF\">.offline</font></td>");
		sb.append("<td width=170><font color=\"B0B0B0\">Enables Offline Shop / Play (.offlineplay)</font></td>");
		sb.append("</tr>");
		sb.append("<tr><td colspan=2 height=1 bgcolor=222222></td></tr>");
		
		// Online Players Count
		sb.append("<tr>");
		sb.append("<td width=100><font color=\"00FFFF\">.online</font></td>");
		sb.append("<td width=170><font color=\"B0B0B0\">Shows currently online players count.</font></td>");
		sb.append("</tr>");
		
		sb.append("</table><br>");
		
		sb.append("</center></body></html>");
		
		html.setHtml(sb.toString());
		player.sendPacket(html);
		player.sendPacket(ActionFailed.STATIC_PACKET);
	}
	
	@Override
	public String[] getCommandList()
	{
		return VOICED_COMMANDS;
	}
	
	/*
	 * (non-Javadoc)
	 * @see org.l2jmobius.gameserver.handler.IVoicedCommandHandler#useVoicedCommand(java.lang.String, org.l2jmobius.gameserver.model.actor.Player, java.lang.String)
	 */
	@Override
	public boolean useVoicedCommand(String command, Player activeChar, String params)
	{
		// TODO Auto-generated method stub
		return false;
	}
	
	/*
	 * (non-Javadoc)
	 * @see org.l2jmobius.gameserver.handler.IVoicedCommandHandler#getVoicedCommandList()
	 */
	@Override
	public String[] getVoicedCommandList()
	{
		// TODO Auto-generated method stub
		return VOICED_COMMANDS;
	}
	
}