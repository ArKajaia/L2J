package handlers.chat.commands.voiced;

import org.l2jmobius.gameserver.handler.IVoicedCommandHandler;
import org.l2jmobius.gameserver.model.actor.Player;
import org.l2jmobius.gameserver.network.serverpackets.ActionFailed;
import org.l2jmobius.gameserver.network.serverpackets.NpcHtmlMessage;

public class VoiceAutoLootFilter implements IVoicedCommandHandler
{
	private static final String[] VOICED_COMMANDS =
	{
		"filter",
		"autoloot"
	};
	
	private static final String VAR_ADENA = "Filter_Adena";
	private static final String VAR_GEAR = "Filter_Gear";
	private static final String VAR_ETCITEM = "Filter_EtcItem";
	
	@Override
	public boolean onCommand(String command, Player activeChar, String params)
	{
		if (activeChar == null)
		{
			return false;
		}
		
		if ((params != null) && !params.isEmpty())
		{
			String paramLower = params.toLowerCase().trim();
			
			switch (paramLower)
			{
				case "adena":
					toggleFilter(activeChar, VAR_ADENA);
					break;
				case "gear":
					toggleFilter(activeChar, VAR_GEAR);
					break;
				case "etcitem":
					toggleFilter(activeChar, VAR_ETCITEM);
					break;
			}
		}
		
		showWindow(activeChar);
		return true;
	}
	
	private void toggleFilter(Player player, String varName)
	{
		boolean currentState = player.getVariables().getBoolean(varName, true);
		player.getVariables().set(varName, !currentState);
	}
	
	private void showWindow(Player player)
	{
		boolean adenaOn = player.getVariables().getBoolean(VAR_ADENA, true);
		boolean gearOn = player.getVariables().getBoolean(VAR_GEAR, true);
		boolean etcItemOn = player.getVariables().getBoolean(VAR_ETCITEM, true);
		
		final NpcHtmlMessage html = new NpcHtmlMessage(0);
		final StringBuilder sb = new StringBuilder();
		
		sb.append("<html><body><center>");
		sb.append("<table width=270 height=35 bgcolor=000000>");
		sb.append("<tr><td align=center valign=middle><font color=\"LEVEL\"><b>AUTO-LOOT FILTER</b></font></td></tr>");
		sb.append("</table><br>");
		
		sb.append("<font color=\"A2A2A2\">Select which items you want to auto-loot.</font><br><br>");
		
		sb.append("<table width=260 border=0 cellspacing=4 cellpadding=4>");
		
		sb.append("<tr>");
		sb.append("<td width=140 align=left><font color=\"FFFFFF\">1. Adena</font></td>");
		sb.append("<td width=120 align=right>");
		sb.append("<button value=\"").append(adenaOn ? "ON" : "OFF").append("\" action=\"bypass voiced_filter adena\" width=60 height=22 back=\"L2UI_CT1.Button_DF_Down\" fore=\"").append(adenaOn ? "L2UI_CT1.Button_DF" : "L2UI_CT1.Button_DF_Down").append("\">");
		sb.append("</td></tr>");
		
		sb.append("<tr>");
		sb.append("<td width=140 align=left><font color=\"FFFFFF\">2. Equipment (Weapons/Armor)</font></td>");
		sb.append("<td width=120 align=right>");
		sb.append("<button value=\"").append(gearOn ? "ON" : "OFF").append("\" action=\"bypass voiced_filter gear\" width=60 height=22 back=\"L2UI_CT1.Button_DF_Down\" fore=\"").append(gearOn ? "L2UI_CT1.Button_DF" : "L2UI_CT1.Button_DF_Down").append("\">");
		sb.append("</td></tr>");
		
		sb.append("<tr>");
		sb.append("<td width=140 align=left><font color=\"FFFFFF\">3. EtcItems (Mats/Recipes/Misc)</font></td>");
		sb.append("<td width=120 align=right>");
		sb.append("<button value=\"").append(etcItemOn ? "ON" : "OFF").append("\" action=\"bypass voiced_filter etcitem\" width=60 height=22 back=\"L2UI_CT1.Button_DF_Down\" fore=\"").append(etcItemOn ? "L2UI_CT1.Button_DF" : "L2UI_CT1.Button_DF_Down").append("\">");
		sb.append("</td></tr>");
		
		sb.append("</table><br>");
		
		sb.append("<font color=\"707070\">Note: Items filtered out will drop on the ground.</font>");
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
		return VOICED_COMMANDS;
	}
}