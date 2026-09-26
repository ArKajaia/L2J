package custom.RotatingHotZones;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.IntStream;

import org.l2jmobius.commons.threads.ThreadPool;
import org.l2jmobius.commons.util.Rnd;
import org.l2jmobius.gameserver.data.xml.SkillData;
import org.l2jmobius.gameserver.geoengine.GeoEngine;
import org.l2jmobius.gameserver.managers.HotzoneModifierManager;
import org.l2jmobius.gameserver.managers.ZoneManager;
import org.l2jmobius.gameserver.model.Location;
import org.l2jmobius.gameserver.model.World;
import org.l2jmobius.gameserver.model.actor.Creature;
import org.l2jmobius.gameserver.model.actor.Npc;
import org.l2jmobius.gameserver.model.actor.Player;
import org.l2jmobius.gameserver.model.events.Containers;
import org.l2jmobius.gameserver.model.events.EventType;
import org.l2jmobius.gameserver.model.events.holders.actor.player.OnPlayerLogin;
import org.l2jmobius.gameserver.model.events.listeners.ConsumerEventListener;
import org.l2jmobius.gameserver.model.hotzone.HotzoneModifier;
import org.l2jmobius.gameserver.model.script.Quest;
import org.l2jmobius.gameserver.model.skill.Skill;
import org.l2jmobius.gameserver.model.skill.enums.SkillFinishType;
import org.l2jmobius.gameserver.model.zone.ZoneId;
import org.l2jmobius.gameserver.model.zone.ZoneType;
import org.l2jmobius.gameserver.network.serverpackets.NpcHtmlMessage;

public class RotatingHotZones extends Quest
{
	private static final int TELEPORTER_NPC_ID = 900004;
	private static final int MONSTER_BUFF_ID = 90000;
	private static final int PLAYER_BUFF_ID = 90001;
	private static final int ROTATION_HOURS = 1;
	
	/** How long a party member has to accept a teleport offer. */
	private static final long TELEPORT_OFFER_TIMEOUT_MS = 30000;
	
	/**
	 * Static handle so EnterWorld can reconcile hot zone buffs on login. Set in the constructor, which the script loader runs once at boot.
	 */
	private static RotatingHotZones _instance;
	
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
		
		public int[] getZoneIds()
		{
			return zoneIds;
		}
		
		public boolean isInRange(int level)
		{
			return (level >= minLevel) && (level <= maxLevel);
		}
	}
	
	private static final List<LevelBracket> BRACKETS = new ArrayList<>();
	static
	{
		BRACKETS.add(new LevelBracket("Lv 1-10", 1, 10, IntStream.rangeClosed(90001, 90010).toArray()));
		BRACKETS.add(new LevelBracket("Lv 11-20", 11, 20, IntStream.rangeClosed(90011, 90020).toArray()));
		BRACKETS.add(new LevelBracket("Lv 21-30", 21, 30, IntStream.rangeClosed(90021, 90030).toArray()));
		BRACKETS.add(new LevelBracket("Lv 31-40", 31, 40, IntStream.rangeClosed(90031, 90040).toArray()));
		BRACKETS.add(new LevelBracket("Lv 41-50", 41, 50, IntStream.rangeClosed(90041, 90050).toArray()));
		BRACKETS.add(new LevelBracket("Lv 51-60", 51, 60, IntStream.rangeClosed(90051, 90060).toArray()));
		BRACKETS.add(new LevelBracket("Lv 61-70", 61, 70, IntStream.rangeClosed(90061, 90070).toArray()));
		BRACKETS.add(new LevelBracket("Lv 71-80", 71, 80, IntStream.rangeClosed(90071, 90080).toArray()));
		BRACKETS.add(new LevelBracket("Lv 81+", 81, 999, IntStream.rangeClosed(90081, 90090).toArray()));
	}
	
	private static class BracketZone
	{
		private final LevelBracket bracket;
		private final int activeZoneId;
		
		public BracketZone(LevelBracket bracket, int activeZoneId)
		{
			this.bracket = bracket;
			this.activeZoneId = activeZoneId;
		}
		
		public LevelBracket getBracket()
		{
			return bracket;
		}
		
		public int getActiveZoneId()
		{
			return activeZoneId;
		}
	}
	
	/**
	 * A teleport offer awaiting a party member's answer.
	 * <p>
	 * The destination is resolved ONCE by the leader and stored here, so everyone who accepts lands on the same spot instead of each rolling their own random point in the zone.
	 */
	private static class PendingTeleport
	{
		private final int zoneId;
		private final int x;
		private final int y;
		private final int z;
		private final long expiry;
		
		public PendingTeleport(int zoneId, int x, int y, int z)
		{
			this.zoneId = zoneId;
			this.x = x;
			this.y = y;
			this.z = z;
			this.expiry = System.currentTimeMillis() + TELEPORT_OFFER_TIMEOUT_MS;
		}
		
		public boolean isExpired()
		{
			return System.currentTimeMillis() > expiry;
		}
	}
	
	// Replaced wholesale (never mutated in place) on each rotation: zone enter/exit events and teleport bypasses read these from other
	// threads, and the old clear()-then-add() on a plain HashSet/ArrayList could hand them a half-built rotation or throw mid-iteration.
	private volatile Set<Integer> _activeZoneSet = Set.of();
	private volatile List<BracketZone> _activeZones = List.of();
	
	/** objectId -> pending offer. Removed on accept/decline; stale entries swept on rotation. */
	private final Map<Integer, PendingTeleport> _pendingTeleports = new ConcurrentHashMap<>();
	
	public RotatingHotZones()
	{
		super(900004);
		
		_instance = this;
		
		for (LevelBracket bracket : BRACKETS)
		{
			for (int zoneId : bracket.getZoneIds())
			{
				addEnterZoneId(zoneId);
				addExitZoneId(zoneId);
			}
		}
		
		addFirstTalkId(TELEPORTER_NPC_ID);
		addStartNpc(TELEPORTER_NPC_ID);
		addTalkId(TELEPORTER_NPC_ID);
		
		// Hotzone buffs never expire and are saved on logout, so without this a player who logged out inside a zone kept them forever,
		// anywhere. OnPlayerLogin fires after the effect restore and the zone revalidation, so the zone lists are already up to date.
		Containers.Players().addListener(new ConsumerEventListener(Containers.Players(), EventType.ON_PLAYER_LOGIN, (OnPlayerLogin event) -> validateHotZoneBuffs(event.getPlayer()), this));
		
		ThreadPool.scheduleAtFixedRate(this::rotateZone, 10000, ROTATION_HOURS * 60 * 60 * 1000L);
	}
	
	public static RotatingHotZones getInstance()
	{
		return _instance;
	}
	
	private void rotateZone()
	{
		for (BracketZone br : _activeZones)
		{
			ZoneType oldZone = ZoneManager.getInstance().getZoneById(br.getActiveZoneId());
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

			HotzoneModifierManager.getInstance().clearModifier(br.getActiveZoneId());
		}
		
		// Offers pointing at the previous rotation are meaningless now.
		_pendingTeleports.clear();
		
		final List<BracketZone> newZones = new ArrayList<>();
		final Set<Integer> newZoneSet = new HashSet<>();
		for (LevelBracket bracket : BRACKETS)
		{
			if (bracket.getZoneIds().length == 0)
			{
				continue;
			}
			
			int chosenZoneId = bracket.getZoneIds()[Rnd.get(bracket.getZoneIds().length)];
			newZones.add(new BracketZone(bracket, chosenZoneId));
			newZoneSet.add(chosenZoneId);
			HotzoneModifierManager.getInstance().rollModifier(chosenZoneId);
		}
		_activeZones = List.copyOf(newZones);
		_activeZoneSet = Set.copyOf(newZoneSet);
		
		for (Player player : World.getInstance().getPlayers())
		{
			if (player == null)
			{
				continue;
			}
			
			int playerLevel = player.getLevel();
			for (BracketZone bz : _activeZones)
			{
				LevelBracket bracket = bz.getBracket();
				if (bracket.isInRange(playerLevel))
				{
					ZoneType zone = ZoneManager.getInstance().getZoneById(bz.getActiveZoneId());
					if (zone != null)
					{
						final HotzoneModifier modifier = HotzoneModifierManager.getInstance().getModifier(bz.getActiveZoneId());

						player.sendMessage("=================================");
						player.sendMessage(">>> HOT ZONE ROTATED <<<");
						player.sendMessage("Hot Zone for your level (" + bracket.getName() + "): " + zone.getName());
						if (modifier != null)
						{
							player.sendMessage("Modifier: " + modifier.getDescription());
						}
						player.sendMessage("=================================");
					}
					break;
				}
			}
		}
		
		// Modifier buffs (GLASS_CANNON, ARCANE_SURGE...) follow the freshly rolled modifiers - drops stale ones from players in last rotation's zones too.
		for (Player player : World.getInstance().getPlayers())
		{
			if (player != null)
			{
				syncModifierBuff(player);
			}
		}
		
		Skill playerBuff = SkillData.getInstance().getSkill(PLAYER_BUFF_ID, 1);
		Skill monsterBuff = SkillData.getInstance().getSkill(MONSTER_BUFF_ID, 1);
		
		for (BracketZone bz : _activeZones)
		{
			ZoneType activeZone = ZoneManager.getInstance().getZoneById(bz.getActiveZoneId());
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
		// ---------------------------------------------------- offer replies
		if (event.equals("tp_accept"))
		{
			final PendingTeleport pending = _pendingTeleports.remove(player.getObjectId());
			if (pending == null)
			{
				player.sendMessage("You have no pending teleport offer.");
			}
			else if (pending.isExpired())
			{
				player.sendMessage("That teleport offer has expired.");
			}
			else if (!_activeZoneSet.contains(pending.zoneId))
			{
				player.sendMessage("That hot zone is no longer active.");
			}
			else if (!canTeleport(player))
			{
				// The offer window can be clicked from anywhere - hold members to the same rules as a solo teleport.
			}
			else
			{
				player.teleToLocation(new Location(pending.x, pending.y, pending.z));
				player.sendMessage("Teleported to the Hot Zone!");
			}
			return null;
		}
		
		if (event.equals("tp_decline"))
		{
			_pendingTeleports.remove(player.getObjectId());
			player.sendMessage("You declined the teleport.");
			return null;
		}
		
		// ---------------------------------------------------- teleport request
		if (event.startsWith("teleport_"))
		{
			final boolean isPartyTp = event.startsWith("teleport_party_");
			final int zoneId = Integer.parseInt(event.replace(isPartyTp ? "teleport_party_" : "teleport_", ""));
			
			if (!_activeZoneSet.contains(zoneId))
			{
				player.sendMessage("That hotzone is no longer active!");
				showTeleportMenu(player, npc);
				return null;
			}
			
			// Validate party state BEFORE doing anything. These previously
			// sent a message and then fell through, teleporting anyway.
			if (isPartyTp)
			{
				if (!player.isInParty())
				{
					player.sendMessage("You are not in a party.");
					return null;
				}
				
				if (player.getParty().getLeader() != player)
				{
					player.sendMessage("Only the party leader can teleport the party.");
					return null;
				}
			}
			
			final ZoneType zone = ZoneManager.getInstance().getZoneById(zoneId);
			if ((zone == null) || (zone.getZone() == null))
			{
				player.sendMessage("Could not resolve location for the requested zone.");
				return null;
			}
			
			final Location rawPoint = pickTeleportPoint(zone);
			final int z = rawPoint.getZ();
			
			if (!isPartyTp)
			{
				if (!canTeleport(player))
				{
					return null;
				}
				
				player.teleToLocation(new Location(rawPoint.getX(), rawPoint.getY(), z));
				player.sendMessage("Teleported to " + zone.getName() + "!");
				return null;
			}
			
			// Leader goes immediately; everyone else is asked first.
			int offered = 0;
			for (Player member : player.getParty().getMembers())
			{
				if (member == null)
				{
					continue;
				}
				
				if (member == player)
				{
					if (!canTeleport(member))
					{
						continue;
					}
					
					member.teleToLocation(new Location(rawPoint.getX(), rawPoint.getY(), z));
					member.sendMessage("Teleported to " + zone.getName() + "!");
					continue;
				}
				
				_pendingTeleports.put(member.getObjectId(), new PendingTeleport(zoneId, rawPoint.getX(), rawPoint.getY(), z));
				sendTeleportOffer(member, player, zone.getName());
				offered++;
			}
			
			player.sendMessage("Teleport offer sent to " + offered + " party member(s).");
			return null;
		}
		
		return null;
	}
	
	/**
	 * Hot zone teleports are only allowed from a peace zone in the open world, and never while dead, in Olympiad, jailed, in a duel or event, trading, or holding a cursed weapon.
	 * @param player the player about to be teleported
	 * @return {@code true} if they may go; otherwise they were already told why not
	 */
	private boolean canTeleport(Player player)
	{
		if (player.isAlikeDead() || player.isTeleporting())
		{
			player.sendMessage("You cannot teleport right now.");
			return false;
		}
		
		if (player.isInOlympiadMode() || player.isJailed() || player.isInDuel() || player.isOnEvent() || player.isCursedWeaponEquipped() || player.isInStoreMode() || player.isFlyingMounted() || (player.getInstanceId() != 0))
		{
			player.sendMessage("You cannot teleport to a Hot Zone from here.");
			return false;
		}
		
		if (!player.isInsideZone(ZoneId.PEACE))
		{
			player.sendMessage("You can only teleport to a Hot Zone from a peace zone.");
			return false;
		}
		
		return true;
	}
	
	/**
	 * Shows a party member an accept/decline window for a leader's teleport.
	 * @param member
	 * @param leader
	 * @param zoneName
	 */
	private void sendTeleportOffer(Player member, Player leader, String zoneName)
	{
		final NpcHtmlMessage html = new NpcHtmlMessage(0);
		final StringBuilder sb = new StringBuilder();
		
		sb.append("<html><body><center><br>");
		sb.append("<font color=\"LEVEL\">Hot Zone Teleport</font><br><br>");
		sb.append("<font color=\"AAAAAA\">").append(leader.getName());
		sb.append(" wants to teleport the party to</font><br>");
		sb.append("<font color=\"LEVEL\">").append(zoneName).append("</font><br><br>");
		sb.append("<font color=\"777777\">This offer expires in ");
		sb.append(TELEPORT_OFFER_TIMEOUT_MS / 1000).append(" seconds.</font><br><br>");
		
		sb.append("<table width=250 border=0 cellpadding=0 cellspacing=0>");
		sb.append("<tr>");
		sb.append("<td width=125 align=center>");
		sb.append("<button value=\"Accept\" action=\"bypass -h Script RotatingHotZones tp_accept\" ");
		sb.append("width=100 height=25 back=\"L2UI_CT1.Button_DF_Down\" fore=\"L2UI_CT1.Button_DF\">");
		sb.append("</td>");
		sb.append("<td width=125 align=center>");
		sb.append("<button value=\"Decline\" action=\"bypass -h Script RotatingHotZones tp_decline\" ");
		sb.append("width=100 height=25 back=\"L2UI_CT1.Button_DF_Down\" fore=\"L2UI_CT1.Button_DF\">");
		sb.append("</td>");
		sb.append("</tr>");
		sb.append("</table>");
		
		sb.append("</center></body></html>");
		
		html.setHtml(sb.toString());
		member.sendPacket(html);
	}
	
	/**
	 * Reconciles a player's hot zone buff with where they ACTUALLY are.
	 * <p>
	 * Effects persist across a disconnect, so crashing inside a hot zone restores the buff on login no matter where the player reappears - and onExitZone never fired to remove it. Equally, onEnterZone does not fire for a login spawn, so someone relogging inside an active zone would otherwise be
	 * missing a buff they should have. Call this from EnterWorld.
	 * @param player
	 */
	public void validateHotZoneBuffs(Player player)
	{
		if (player == null)
		{
			return;
		}
		
		boolean insideActiveZone = false;
		for (int zoneId : _activeZoneSet)
		{
			final ZoneType zone = ZoneManager.getInstance().getZoneById(zoneId);
			if ((zone != null) && zone.getCharactersInside().contains(player))
			{
				insideActiveZone = true;
				break;
			}
		}
		
		if (insideActiveZone)
		{
			if (!player.isAffectedBySkill(PLAYER_BUFF_ID))
			{
				final Skill buff = getSafeSkill(PLAYER_BUFF_ID, 1);
				if (buff != null)
				{
					buff.applyEffects(player, player);
					player.sendMessage("You are inside an active Hot Zone!");
				}
			}
		}
		else if (player.isAffectedBySkill(PLAYER_BUFF_ID))
		{
			player.stopSkillEffects(SkillFinishType.REMOVED, PLAYER_BUFF_ID);
			player.sendMessage("Your Hot Zone bonus has ended - you are no longer in an active zone.");
		}
		
		syncModifierBuff(player);
	}
	
	/**
	 * Makes {@code player} hold exactly the buff of the modifier active in the hotzone they stand in (see {@link HotzoneModifier#getPlayerBuffSkillId()}) - or none, if they are outside every active zone or its modifier has no buff. Every other modifier buff is removed.
	 * @param player the player to update
	 */
	private void syncModifierBuff(Player player)
	{
		final HotzoneModifier modifier = HotzoneModifierManager.getInstance().getModifierFor(player);
		final int wantedSkillId = modifier != null ? modifier.getPlayerBuffSkillId() : 0;
		for (HotzoneModifier each : HotzoneModifier.values())
		{
			final int skillId = each.getPlayerBuffSkillId();
			if ((skillId > 0) && (skillId != wantedSkillId) && player.isAffectedBySkill(skillId))
			{
				player.stopSkillEffects(SkillFinishType.REMOVED, skillId);
			}
		}
		
		if ((wantedSkillId > 0) && !player.isAffectedBySkill(wantedSkillId))
		{
			final Skill buff = getSafeSkill(wantedSkillId, 1);
			if (buff != null)
			{
				buff.applyEffects(player, player);
			}
		}
	}
	
	/**
	 * Picks a random ground point inside {@code zone}. Zones with a narrowed minZ/maxZ (to leave out a catacomb above/below) only accept a geodata layer within that Z range, so a teleport never lands in the excluded level; after a few misses it falls back to any layer.
	 * @param zone the hotzone to land in
	 * @return the teleport location, Z already resolved from geodata
	 */
	private Location pickTeleportPoint(ZoneType zone)
	{
		final int lowZ = zone.getZone().getLowZ();
		final int highZ = zone.getZone().getHighZ();
		Location fallback = null;
		for (int attempt = 0; attempt < 10; attempt++)
		{
			final Location point = zone.getZone().getRandomPoint();
			final List<Integer> floors = GeoEngine.getInstance().getAllZLayers(GeoEngine.getGeoX(point.getX()), GeoEngine.getGeoY(point.getY()));
			if ((floors == null) || floors.isEmpty())
			{
				if (fallback == null)
				{
					fallback = new Location(point.getX(), point.getY(), 0);
				}
				continue;
			}

			if (fallback == null)
			{
				fallback = new Location(point.getX(), point.getY(), floors.get(Rnd.get(floors.size())) + 20);
			}

			final List<Integer> inRange = new ArrayList<>();
			for (int floor : floors)
			{
				if ((floor >= lowZ) && (floor <= highZ))
				{
					inRange.add(floor);
				}
			}

			if (!inRange.isEmpty())
			{
				return new Location(point.getX(), point.getY(), inRange.get(Rnd.get(inRange.size())) + 20);
			}
		}
		return fallback;
	}

	private boolean isInsideOtherActiveZone(Creature creature, int excludedZoneId)
	{
		for (int zoneId : _activeZoneSet)
		{
			if (zoneId == excludedZoneId)
			{
				continue;
			}

			final ZoneType zone = ZoneManager.getInstance().getZoneById(zoneId);
			if ((zone != null) && zone.isInsideZone(creature))
			{
				return true;
			}
		}
		return false;
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
		NpcHtmlMessage html = new NpcHtmlMessage(npc.getObjectId());
		html.setWindowSize(450, 500);
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
			LevelBracket bracket = bz.getBracket();
			int zoneId = bz.getActiveZoneId();
			
			ZoneType zone = ZoneManager.getInstance().getZoneById(zoneId);
			String zoneName = (zone != null) ? zone.getName() : ("Zone " + zoneId);
			
			hasActiveZone = true;

			sb.append("<tr><td align=\"left\" width=80>").append(zoneName).append(": ").append("<font color=\"LEVEL\">").append(bracket.getName()).append("</font></td></tr>");

			final HotzoneModifier modifier = HotzoneModifierManager.getInstance().getModifier(zoneId);
			if (modifier != null)
			{
				sb.append("<tr><td align=\"left\"><font color=\"999999\">").append(modifier.getDescription()).append("</font></td></tr>");
			}

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
			
			if (player.isInParty() && (player.getParty().getLeader() == player))
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
				syncModifierBuff(character.asPlayer());
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
			// Overlapping active zones share the same buff - keep it while still inside another one.
			if (isInsideOtherActiveZone(character, zone.getId()))
			{
				// ...but their modifiers can differ, so the modifier buff still has to follow.
				if (character.isPlayer())
				{
					syncModifierBuff(character.asPlayer());
				}
				return;
			}

			if (character.isPlayer())
			{
				character.stopSkillEffects(SkillFinishType.REMOVED, PLAYER_BUFF_ID);
				syncModifierBuff(character.asPlayer());
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