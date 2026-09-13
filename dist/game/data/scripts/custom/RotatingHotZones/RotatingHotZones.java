package custom.RotatingHotZones;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.l2jmobius.commons.threads.ThreadPool;
import org.l2jmobius.commons.util.Rnd;
import org.l2jmobius.gameserver.data.xml.SkillData;
import org.l2jmobius.gameserver.geoengine.GeoEngine;
import org.l2jmobius.gameserver.managers.ZoneManager;
import org.l2jmobius.gameserver.model.Location;
import org.l2jmobius.gameserver.model.World;
import org.l2jmobius.gameserver.model.actor.Creature;
import org.l2jmobius.gameserver.model.actor.Npc;
import org.l2jmobius.gameserver.model.actor.Player;
import org.l2jmobius.gameserver.model.script.Quest;
import org.l2jmobius.gameserver.model.skill.Skill;
import org.l2jmobius.gameserver.model.skill.enums.SkillFinishType;
import org.l2jmobius.gameserver.model.zone.ZoneType;
import org.l2jmobius.gameserver.network.serverpackets.NpcHtmlMessage;

public class RotatingHotZones extends Quest
{
	private static final int TELEPORTER_NPC_ID = 900004; // Your custom NPC ID
	private static final int MONSTER_BUFF_ID = 90000;
	private static final int PLAYER_BUFF_ID = 90001;
	private static final int ROTATION_HOURS = 1;
	
	private static class LevelBracket
	{
		private final String name;
		private final int minLevel;
		private final int maxLevel;
		private final int[] zoneIds;
		
		public LevelBracket(String name, int minLevel, int maxLevel, int[] zoneIds)
		{
			this.name = name;
			this.minLevel = minLevel;
			this.maxLevel = maxLevel;
			this.zoneIds = zoneIds;
		}
		
		public String getName()
		{
			return name;
		}
		
		public boolean isInRange(int level)
		{
			return (level >= minLevel) && (level <= maxLevel);
		}
	}
	
	private static int[] createIdRange(int startId, int endId)
	{
		int[] ids = new int[(endId - startId) + 1];
		for (int i = 0; i < ids.length; i++)
		{
			ids[i] = startId + i;
		}
		return ids;
	}
	
	private static final List<LevelBracket> BRACKETS = new ArrayList<>();
	static
	{
		BRACKETS.add(new LevelBracket("Lv 1-10", 1, 10, createIdRange(90001, 90010)));
		BRACKETS.add(new LevelBracket("Lv 11-20", 11, 20, createIdRange(90011, 90020)));
		BRACKETS.add(new LevelBracket("Lv 21-30", 21, 30, createIdRange(90021, 90030)));
		BRACKETS.add(new LevelBracket("Lv 31-40", 31, 40, createIdRange(90031, 90040)));
		BRACKETS.add(new LevelBracket("Lv 41-50", 41, 50, createIdRange(90041, 90050)));
		BRACKETS.add(new LevelBracket("Lv 51-60", 51, 60, createIdRange(90051, 90060)));
		BRACKETS.add(new LevelBracket("Lv 61-70", 61, 70, createIdRange(90061, 90070)));
		BRACKETS.add(new LevelBracket("Lv 71-80", 71, 80, createIdRange(90071, 90080)));
		BRACKETS.add(new LevelBracket("Lv 81+", 81, 999, createIdRange(90081, 90090)));
	}
	
	private final Set<Integer> _activeZoneIds = new HashSet<>();
	private final Map<LevelBracket, Integer> _activeBracketZones = new HashMap<>();
	
	public RotatingHotZones()
	{
		// Use a positive custom ID (e.g. 990001) instead of -1 so QuestManager registers this script
		super(900004);
		
		for (LevelBracket bracket : BRACKETS)
		{
			for (int zoneId : bracket.zoneIds)
			{
				addEnterZoneId(zoneId);
				addExitZoneId(zoneId);
			}
		}
		
		addFirstTalkId(TELEPORTER_NPC_ID);
		addStartNpc(TELEPORTER_NPC_ID);
		addTalkId(TELEPORTER_NPC_ID);
		
		ThreadPool.scheduleAtFixedRate(this::rotateZone, 10000, ROTATION_HOURS * 60 * 60 * 1000L);
	}
	
	private void rotateZone()
	{
		for (int zoneId : _activeZoneIds)
		{
			ZoneType oldZone = ZoneManager.getInstance().getZoneById(zoneId);
			if (oldZone != null)
			{
				for (Creature creature : oldZone.getCharactersInside())
				{
					if (creature.isPlayer())
					{
						creature.stopSkillEffects(SkillFinishType.REMOVED, PLAYER_BUFF_ID);
					}
					else if (creature.isMonster())
					{
						creature.stopSkillEffects(SkillFinishType.REMOVED, MONSTER_BUFF_ID);
					}
				}
			}
		}
		
		_activeZoneIds.clear();
		_activeBracketZones.clear();
		
		for (LevelBracket bracket : BRACKETS)
		{
			if (bracket.zoneIds.length == 0)
			{
				continue;
			}
			
			int chosenZoneId = bracket.zoneIds[Rnd.get(bracket.zoneIds.length)];
			_activeZoneIds.add(chosenZoneId);
			_activeBracketZones.put(bracket, chosenZoneId);
		}
		
		for (Player player : World.getInstance().getPlayers())
		{
			if (player == null)
			{
				continue;
			}
			
			int playerLevel = player.getLevel();
			for (Map.Entry<LevelBracket, Integer> entry : _activeBracketZones.entrySet())
			{
				LevelBracket bracket = entry.getKey();
				if (bracket.isInRange(playerLevel))
				{
					ZoneType zone = ZoneManager.getInstance().getZoneById(entry.getValue());
					if (zone != null)
					{
						player.sendMessage("=================================");
						player.sendMessage(">>> HOT ZONE ROTATED <<<");
						player.sendMessage("Hot Zone for your level (" + bracket.name + "): " + zone.getName());
						player.sendMessage("=================================");
					}
					break;
				}
			}
		}
		
		Skill playerBuff = SkillData.getInstance().getSkill(PLAYER_BUFF_ID, 1);
		Skill monsterBuff = SkillData.getInstance().getSkill(MONSTER_BUFF_ID, 1);
		
		for (int zoneId : _activeZoneIds)
		{
			ZoneType activeZone = ZoneManager.getInstance().getZoneById(zoneId);
			if (activeZone != null)
			{
				for (Creature creature : activeZone.getCharactersInside())
				{
					if (creature.isPlayer() && (playerBuff != null))
					{
						playerBuff.applyEffects(creature, creature);
					}
					else if (creature.isMonster() && (monsterBuff != null))
					{
						monsterBuff.applyEffects(creature, creature);
					}
				}
			}
		}
	}
	
	@Override
	public String onFirstTalk(Npc npc, Player player)
	{
		showTeleportMenu(player, npc);
		return null;
	}
	
	@Override
	public String onEvent(String event, Npc npc, Player player)
	{
		if (event.startsWith("teleport_"))
		{
			int zoneId = Integer.parseInt(event.replace("teleport_", ""));
			
			if (_activeZoneIds.contains(zoneId))
			{
				ZoneType zone = ZoneManager.getInstance().getZoneById(zoneId);
				if ((zone != null) && (zone.getZone() != null))
				{
					Location rawPoint = zone.getZone().getRandomPoint();
					
					// Fetch the exact floor Z at the world coordinates (X, Y, Z)
					int floorZ = GeoEngine.getInstance().getHeight(rawPoint.getX(), rawPoint.getY(), rawPoint.getZ());
					
					player.teleToLocation(new Location(rawPoint.getX(), rawPoint.getY(), floorZ + 20));
					player.sendMessage("Teleported to " + zone.getName() + "!");
				}
				else
				{
					player.sendMessage("Could not resolve location for the requested zone.");
				}
			}
			else
			{
				player.sendMessage("That hotzone is no longer active!");
				showTeleportMenu(player, npc);
			}
		}
		return null;
	}
	
	private Skill getSafeSkill(int skillId, int level)
	{
		if (skillId <= 0)
		{
			return null;
		}
		return SkillData.getInstance().getSkill(skillId, level);
	}
	
	private void showTeleportMenu(Player player, Npc npc)
	{
		final NpcHtmlMessage html = new NpcHtmlMessage(npc.getObjectId());
		final StringBuilder sb = new StringBuilder();
		
		sb.append("<html><body>");
		sb.append("<center>");
		sb.append("<br>");
		sb.append("<font color=\"LEVEL\">Hotzone Teleporter</font><br1>");
		sb.append("<font color=\"808080\">Select an active Hotzone to teleport:</font><br><br>");
		
		sb.append("<table width=280 border=0 cellpadding=2 cellspacing=1>");
		
		boolean hasActiveZone = false;
		
		for (Map.Entry<LevelBracket, Integer> entry : _activeBracketZones.entrySet())
		{
			LevelBracket bracket = entry.getKey();
			int zoneId = entry.getValue();
			
			ZoneType zone = ZoneManager.getInstance().getZoneById(zoneId);
			String zoneName = (zone != null) ? zone.getName() : ("Zone " + zoneId);
			
			hasActiveZone = true;
			
			sb.append("<tr>");
			sb.append("<td align=\"right\" width=80><font color=\"LEVEL\">").append(bracket.getName()).append(":</font></td>");
			sb.append("<td align=\"center\" width=200>");
			sb.append("<button value=\"").append(zoneName).append("\" ").append("action=\"bypass -h Script RotatingHotZones teleport_").append(zoneId).append("\" ").append("width=190 height=25 back=\"L2UI_CT1.Button_DF_Down\" fore=\"L2UI_CT1.Button_DF\">");
			sb.append("</td>");
			sb.append("</tr>");
		}
		
		if (!hasActiveZone)
		{
			sb.append("<tr>");
			sb.append("<td align=\"center\" colspan=\"2\"><font color=\"808080\">No active hotzones available.</font></td>");
			sb.append("</tr>");
		}
		
		sb.append("</table>");
		sb.append("</center>");
		sb.append("</body></html>");
		
		html.setHtml(sb.toString());
		player.sendPacket(html);
	}
	
	@Override
	public void onEnterZone(Creature character, ZoneType zone)
	{
		if (_activeZoneIds.contains(zone.getId()))
		{
			if (character.isPlayer())
			{
				Skill buff = getSafeSkill(PLAYER_BUFF_ID, 1);
				if (buff != null)
				{
					buff.applyEffects(character, character);
				}
				character.asPlayer().sendMessage("You have entered an active Hot Zone!");
			}
			else if (character.isMonster())
			{
				Skill buff = getSafeSkill(MONSTER_BUFF_ID, 1);
				if (buff != null)
				{
					buff.applyEffects(character, character);
				}
			}
		}
	}
	
	@Override
	public void onExitZone(Creature character, ZoneType zone)
	{
		if (_activeZoneIds.contains(zone.getId()))
		{
			if (character.isPlayer())
			{
				character.stopSkillEffects(SkillFinishType.REMOVED, PLAYER_BUFF_ID);
				character.asPlayer().sendMessage("You have left the active Hot Zone.");
			}
			else if (character.isMonster())
			{
				character.stopSkillEffects(SkillFinishType.REMOVED, MONSTER_BUFF_ID);
			}
		}
	}
	
	public static void main(String[] args)
	{
		new RotatingHotZones();
	}
}