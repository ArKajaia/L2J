package custom.RotatingHotZones;

import java.util.*;
import java.util.function.Predicate;
import java.util.stream.IntStream;

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
import org.l2jmobius.gameserver.model.zone.ZoneId;
import org.l2jmobius.gameserver.model.zone.ZoneType;
import org.l2jmobius.gameserver.network.serverpackets.NpcHtmlMessage;

public class RotatingHotZones extends Quest
{
	private static final int TELEPORTER_NPC_ID = 900004; // Your custom NPC ID
	private static final int MONSTER_BUFF_ID = 90000;
	private static final int PLAYER_BUFF_ID = 90001;
	private static final int ROTATION_HOURS = 1;

	private record LevelBracket(String name, int minLevel, int maxLevel, int[] zoneIds)
	{
		public boolean isInRange(int level) {
			return (level >= minLevel) && (level <= maxLevel);
		}
	}

	private static final List<LevelBracket> BRACKETS = new ArrayList<>();
	static
	{
		BRACKETS.add(new LevelBracket("Lv 1-10", 1, 10, IntStream.range(90000, 90010).toArray()));
		BRACKETS.add(new LevelBracket("Lv 11-20", 11, 20, IntStream.range(90011, 90020).toArray()));
		BRACKETS.add(new LevelBracket("Lv 21-30", 21, 30, IntStream.range(90021, 90030).toArray()));
		BRACKETS.add(new LevelBracket("Lv 31-40", 31, 40, IntStream.range(90031, 90040).toArray()));
		BRACKETS.add(new LevelBracket("Lv 41-50", 41, 50, IntStream.range(90041, 90050).toArray()));
		BRACKETS.add(new LevelBracket("Lv 51-60", 51, 60, IntStream.range(90051, 90060).toArray()));
		BRACKETS.add(new LevelBracket("Lv 61-70", 61, 70, IntStream.range(90061, 90070).toArray()));
		BRACKETS.add(new LevelBracket("Lv 71-80", 71, 80, IntStream.range(90071, 90080).toArray()));
		BRACKETS.add(new LevelBracket("Lv 81+", 81, 999, IntStream.range(90081, 90090).toArray()));
	}

	private record BracketZone(LevelBracket bracket, int activeZoneId) {
	}

	private final Set<Integer> _activeZoneSet = new HashSet<>();
	private final List<BracketZone> _activeZones = new ArrayList<BracketZone>();
	
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
		for (BracketZone br : _activeZones)
		{
			ZoneType oldZone = ZoneManager.getInstance().getZoneById(br.activeZoneId);
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

		_activeZones.clear();
		_activeZoneSet.clear();

		for (LevelBracket bracket : BRACKETS)
		{
			if (bracket.zoneIds.length == 0)
			{
				continue;
			}
			
			int chosenZoneId = bracket.zoneIds[Rnd.get(bracket.zoneIds.length)];
			_activeZones.add(new BracketZone(bracket, chosenZoneId));
			_activeZoneSet.add(chosenZoneId);
		}
		
		for (Player player : World.getInstance().getPlayers())
		{
			if (player == null)
			{
				continue;
			}
			
			int playerLevel = player.getLevel();
			for (BracketZone bz : _activeZones)
			{
				LevelBracket bracket = bz.bracket;
				if (bracket.isInRange(playerLevel))
				{
					ZoneType zone = ZoneManager.getInstance().getZoneById(bz.activeZoneId);
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
		
		for (BracketZone bz : _activeZones)
		{
			ZoneType activeZone = ZoneManager.getInstance().getZoneById(bz.activeZoneId);
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
			final boolean isPartyTp = event.startsWith("teleport_party_");
			int zoneId = Integer.parseInt(event.replace(isPartyTp ? "teleport_party_" : "teleport_", ""));

			if (_activeZoneSet.contains(zoneId))
			{
				if (isPartyTp)
				{
					if (!player.isInParty())
						player.sendMessage("You are not in the party");
					else if (player.getParty().getLeader() != player)
						player.sendMessage("Only Party leader can teleport the party");
				}

				ZoneType zone = ZoneManager.getInstance().getZoneById(zoneId);
				if ((zone != null) && (zone.getZone() != null))
				{
					Location rawPoint = zone.getZone().getRandomPoint();
					List<Integer> floors = GeoEngine.getInstance().getAllZLayers(GeoEngine.getGeoX(rawPoint.getX()),
							GeoEngine.getGeoY((rawPoint.getY())));
					int z = 0;
					if (floors != null && !floors.isEmpty()) {
						final int idx = Rnd.get(floors.size());
						z = floors.get(idx) + 20;
					}

					List<Player> playersToTp = List.of(player);
					if (isPartyTp && player.getParty() != null)
						playersToTp = player.getParty().getMembers();

					for (Player tpPlayer : playersToTp)
					{
						if (!tpPlayer.isInsideZone(ZoneId.PEACE))
							continue;

						tpPlayer.teleToLocation(new Location(rawPoint.getX(), rawPoint.getY(), z));
						tpPlayer.sendMessage("Teleported to " + zone.getName() + "!");
					}
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
		
		sb.append("<table width=380 border=0 cellpadding=2 cellspacing=1>");
		
		boolean hasActiveZone = false;
		
		for (BracketZone bz : _activeZones)
		{
			LevelBracket bracket = bz.bracket;
			int zoneId = bz.activeZoneId;
			
			ZoneType zone = ZoneManager.getInstance().getZoneById(zoneId);
			String zoneName = (zone != null) ? zone.getName() : ("Zone " + zoneId);
			
			hasActiveZone = true;

            sb.append("<tr><td align=\"left\" width=80>").append(zoneName)
					.append(": ").append("<font color=\"LEVEL\">")
					.append(bracket.name()).append("</font></td></tr>");

			sb.append("<tr>");
			sb.append("<td>");

			sb.append("<table width=160>");
			sb.append("<tr>");

			sb.append("<td width=80 align=left>");
			sb.append("<button value=\"Solo\" ");
			sb.append("action=\"bypass -h Script RotatingHotZones teleport_").append(zoneId).append("\" ");
			sb.append("width=70 height=25 ");
			sb.append("back=\"L2UI_CT1.Button_DF_Down\" ");
			sb.append("fore=\"L2UI_CT1.Button_DF\">");
			sb.append("</td>");

			if (player.isInParty() && player.getParty().getLeader() == player)
			{
				sb.append("<td width=80 align=left>");
				sb.append("<button value=\"Party\" ");
				sb.append("action=\"bypass -h Script RotatingHotZones teleport_party_").append(zoneId).append("\" ");
				sb.append("width=70 height=25 ");
				sb.append("back=\"L2UI_CT1.Button_DF_Down\" ");
				sb.append("fore=\"L2UI_CT1.Button_DF\">");
				sb.append("</td>");
			}

			sb.append("</tr>");
			sb.append("</table>");

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
		if (_activeZoneSet.contains(zone.getId()))
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
		if (_activeZoneSet.contains(zone.getId()))
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