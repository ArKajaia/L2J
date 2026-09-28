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
import org.l2jmobius.gameserver.handler.BypassHandler;
import org.l2jmobius.gameserver.handler.IBypassHandler;
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
	
	/** Offer reply bypasses, handled by {@link OfferBypassHandler}. */
	private static final String BYPASS_ACCEPT = "hotzone_tp_accept";
	private static final String BYPASS_DECLINE = "hotzone_tp_decline";
	
	// Teleporter window layout and palette.
	private static final int MENU_WIDTH = 460;
	private static final int OFFER_WIDTH = 270;
	private static final String BG_BAR = "1A1A1A";
	private static final String BG_CARD = "111111";
	private static final String BG_CARD_HIGHLIGHT = "2B2410";
	private static final String COLOR_TITLE = "FFFFFF";
	private static final String COLOR_VALUE = "E6C35C";
	private static final String COLOR_MODIFIER = "FF9955";
	private static final String COLOR_POSITIVE = "66CC66";
	private static final String COLOR_MUTED = "A0A0A0";
	private static final String COLOR_HINT = "707070";
	
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
		BRACKETS.add(new LevelBracket("Lv 71-79", 71, 79, IntStream.rangeClosed(90071, 90077).toArray()));
		// One tier per level from 80 on: these zones are packed with mobs, so a zone that suits a level 84 is far too much for a level 80.
		// Each tier's range only picks which zone the rotation message announces - the teleporter still lists every active zone.
		BRACKETS.add(new LevelBracket("Lv 80+", 80, 80, IntStream.rangeClosed(90078, 90081).toArray()));
		BRACKETS.add(new LevelBracket("Lv 81+", 81, 81, IntStream.rangeClosed(90082, 90085).toArray()));
		BRACKETS.add(new LevelBracket("Lv 82+", 82, 82, IntStream.rangeClosed(90086, 90087).toArray()));
		BRACKETS.add(new LevelBracket("Lv 83+", 83, 83, IntStream.rangeClosed(90088, 90095).toArray()));
		BRACKETS.add(new LevelBracket("Lv 84+", 84, 999, IntStream.rangeClosed(90096, 90097).toArray()));
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
	/** When the next rotation is due, shown as a countdown in the teleporter. */
	private volatile long _nextRotationAt;
	
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
		
		BypassHandler.getInstance().registerHandler(new OfferBypassHandler());
		
		// Hotzone buffs never expire and are saved on logout, so without this a player who logged out inside a zone kept them forever,
		// anywhere. OnPlayerLogin fires after the effect restore and the zone revalidation, so the zone lists are already up to date.
		Containers.Players().addListener(new ConsumerEventListener(Containers.Players(), EventType.ON_PLAYER_LOGIN, (OnPlayerLogin event) -> validateHotZoneBuffs(event.getPlayer()), this));
		
		_nextRotationAt = System.currentTimeMillis() + 10000;
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
					else if (creature.isPvpFakePlayer())
					{
						// Roaming fake players are treated as players: they lose the player buffs (their modifier buff follows the new rotation below).
						creature.stopSkillEffects(SkillFinishType.REMOVED, PLAYER_BUFF_ID);
						removeModifierBuffs(creature, 0);
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
		_nextRotationAt = System.currentTimeMillis() + (ROTATION_HOURS * 60 * 60 * 1000L);
		
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
					else if (creature.isPvpFakePlayer())
					{
						if (playerBuff != null)
						{
							playerBuff.applyEffects(creature, creature);
						}
						syncModifierBuff(creature);
					}
					else if (creature.isMonster() && (monsterBuff != null))
					{
						monsterBuff.applyEffects(creature, creature);
					}
				}
			}
		}
	}
	
	/**
	 * Accept/decline replies to a party teleport offer.
	 * <p>
	 * These must NOT go through "bypass Script RotatingHotZones ...": script bypasses are only delivered when the player is standing next to the last NPC they talked to, and a party member receives the offer wherever they are - so the click was silently dropped unless they happened to be beside an NPC.
	 */
	private class OfferBypassHandler implements IBypassHandler
	{
		@Override
		public boolean onCommand(String command, Player player, Creature bypassOrigin)
		{
			if (command.equals(BYPASS_ACCEPT))
			{
				acceptOffer(player);
			}
			else if (command.equals(BYPASS_DECLINE))
			{
				declineOffer(player);
			}
			return true;
		}
		
		@Override
		public String[] getCommandList()
		{
			return new String[]
			{
				BYPASS_ACCEPT,
				BYPASS_DECLINE
			};
		}
	}
	
	private void acceptOffer(Player player)
	{
		final PendingTeleport pending = _pendingTeleports.get(player.getObjectId());
		if (pending == null)
		{
			player.sendMessage("You have no pending teleport offer.");
			return;
		}
		
		if (pending.isExpired())
		{
			_pendingTeleports.remove(player.getObjectId(), pending);
			player.sendMessage("That teleport offer has expired.");
			return;
		}
		
		if (!_activeZoneSet.contains(pending.zoneId))
		{
			_pendingTeleports.remove(player.getObjectId(), pending);
			player.sendMessage("That hot zone is no longer active.");
			return;
		}
		
		// The offer window can be clicked from anywhere - hold members to the same rules as a solo teleport.
		// The offer is kept on failure, so they can fix the problem (e.g. walk into town) and accept again before it expires.
		if (!canTeleport(player))
		{
			return;
		}
		
		// Only the click that actually consumes the offer teleports - guards against a double click.
		if (_pendingTeleports.remove(player.getObjectId(), pending))
		{
			player.teleToLocation(new Location(pending.x, pending.y, pending.z));
			player.sendMessage("Teleported to the Hot Zone!");
		}
	}
	
	private void declineOffer(Player player)
	{
		if (_pendingTeleports.remove(player.getObjectId()) != null)
		{
			player.sendMessage("You declined the teleport.");
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
				sendTeleportOffer(member, player, zone);
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
	 * @param member the party member being asked
	 * @param leader the party leader who is teleporting
	 * @param zone the destination hotzone
	 */
	private void sendTeleportOffer(Player member, Player leader, ZoneType zone)
	{
		final NpcHtmlMessage html = new NpcHtmlMessage(0);
		final HotzoneModifier modifier = HotzoneModifierManager.getInstance().getModifier(zone.getId());
		final StringBuilder sb = new StringBuilder(2000);
		
		sb.append("<html><body><center>");
		sb.append("<br><font color=\"LEVEL\">Hot Zone Teleport</font><br1>");
		sb.append("<img src=\"L2UI_CH3.herotower_deco\" width=256 height=32><br>");
		
		sb.append("<table width=").append(OFFER_WIDTH).append(" cellpadding=6 cellspacing=0 bgcolor=\"").append(BG_CARD).append("\"><tr><td align=center>");
		sb.append("<font color=\"").append(COLOR_VALUE).append("\">").append(leader.getName()).append("</font>");
		sb.append("<font color=\"").append(COLOR_MUTED).append("\"> invites you to</font><br1>");
		sb.append("<font color=\"").append(COLOR_TITLE).append("\">").append(zone.getName()).append("</font>");
		final LevelBracket bracket = getBracketFor(zone.getId());
		if (bracket != null)
		{
			sb.append("<br1><font color=\"LEVEL\">").append(bracket.getName()).append("</font>");
		}
		if (modifier != null)
		{
			sb.append("<br><font color=\"").append(COLOR_MODIFIER).append("\">").append(getModifierName(modifier)).append("</font>");
			sb.append("<br1><font color=\"").append(COLOR_MUTED).append("\">").append(modifier.getDescription()).append("</font>");
		}
		sb.append("</td></tr></table>");
		appendDivider(sb, OFFER_WIDTH);
		sb.append("<br>");
		
		sb.append("<table width=").append(OFFER_WIDTH).append(" cellpadding=0 cellspacing=0><tr>");
		sb.append("<td width=135 align=center>").append(button("Accept", "bypass -h " + BYPASS_ACCEPT, 100)).append("</td>");
		sb.append("<td width=135 align=center>").append(button("Decline", "bypass -h " + BYPASS_DECLINE, 100)).append("</td>");
		sb.append("</tr></table><br>");
		
		sb.append("<font color=\"").append(COLOR_HINT).append("\">Expires in ").append(TELEPORT_OFFER_TIMEOUT_MS / 1000).append(" seconds. You must be in a peace zone to accept.</font>");
		sb.append("</center></body></html>");
		
		html.setHtml(sb.toString());
		member.sendPacket(html);
	}
	
	/**
	 * @param zoneId an active hotzone
	 * @return the level bracket that zone is active for, or {@code null} if it is not active
	 */
	private LevelBracket getBracketFor(int zoneId)
	{
		for (BracketZone bz : _activeZones)
		{
			if (bz.getActiveZoneId() == zoneId)
			{
				return bz.getBracket();
			}
		}
		return null;
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
	 * Removes every modifier buff from {@code creature} but {@code keepSkillId}.
	 * @param creature the player or roaming fake player
	 * @param keepSkillId the modifier buff to keep, 0 for none
	 */
	private void removeModifierBuffs(Creature creature, int keepSkillId)
	{
		for (HotzoneModifier each : HotzoneModifier.values())
		{
			final int skillId = each.getPlayerBuffSkillId();
			if ((skillId > 0) && (skillId != keepSkillId) && creature.isAffectedBySkill(skillId))
			{
				creature.stopSkillEffects(SkillFinishType.REMOVED, skillId);
			}
		}
	}
	
	/**
	 * Makes {@code player} hold exactly the buff of the modifier active in the hotzone they stand in (see {@link HotzoneModifier#getPlayerBuffSkillId()}) - or none, if they are outside every active zone or its modifier has no buff. Every other modifier buff is removed.
	 * @param player the player (or roaming fake player, which gets the player side of a hotzone) to update
	 */
	private void syncModifierBuff(Creature player)
	{
		final HotzoneModifier modifier = HotzoneModifierManager.getInstance().getModifierFor(player);
		final int wantedSkillId = modifier != null ? modifier.getPlayerBuffSkillId() : 0;
		removeModifierBuffs(player, wantedSkillId);
		
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
		final NpcHtmlMessage html = new NpcHtmlMessage(npc.getObjectId());
		html.setWindowSize(500, 850);
		
		final boolean isPartyLeader = player.isInParty() && (player.getParty().getLeader() == player);
		final List<BracketZone> activeZones = _activeZones;
		final StringBuilder sb = new StringBuilder(12000);
		
		sb.append("<html><body><center>");
		
		// Header
		sb.append("<br><font color=\"LEVEL\">Hotzone Teleporter</font><br1>");
		sb.append("<img src=\"L2UI_CH3.herotower_deco\" width=256 height=32><br1>");
		sb.append("<font color=\"").append(COLOR_MUTED).append("\">Boosted hunting grounds rotate every ").append(ROTATION_HOURS == 1 ? "hour" : ROTATION_HOURS + " hours").append(".</font><br>");
		
		// Status bar
		sb.append("<table width=").append(MENU_WIDTH).append(" cellpadding=4 cellspacing=0 bgcolor=\"").append(BG_BAR).append("\"><tr>");
		sb.append("<td width=150 align=left>Active: <font color=\"").append(COLOR_VALUE).append("\">").append(activeZones.size()).append("</font></td>");
		sb.append("<td width=160 align=center>Next rotation: <font color=\"").append(COLOR_VALUE).append("\">").append(formatTimeLeft(_nextRotationAt - System.currentTimeMillis())).append("</font></td>");
		sb.append("<td width=150 align=right>Your level: <font color=\"").append(COLOR_VALUE).append("\">").append(player.getLevel()).append("</font></td>");
		sb.append("</tr></table>");
		appendDivider(sb, MENU_WIDTH);
		sb.append("<br>");
		
		if (activeZones.isEmpty())
		{
			sb.append("<table width=").append(MENU_WIDTH).append(" cellpadding=12 cellspacing=0 bgcolor=\"").append(BG_CARD).append("\"><tr>");
			sb.append("<td align=center><font color=\"").append(COLOR_MUTED).append("\">No hotzones are active right now.<br1>Check back after the next rotation.</font></td>");
			sb.append("</tr></table>");
		}
		
		for (BracketZone bz : activeZones)
		{
			final LevelBracket bracket = bz.getBracket();
			final int zoneId = bz.getActiveZoneId();
			final ZoneType zone = ZoneManager.getInstance().getZoneById(zoneId);
			final String zoneName = (zone != null) ? zone.getName() : ("Zone " + zoneId);
			final HotzoneModifier modifier = HotzoneModifierManager.getInstance().getModifier(zoneId);
			final boolean isYourLevel = bracket.isInRange(player.getLevel());
			
			sb.append("<table width=").append(MENU_WIDTH).append(" cellpadding=3 cellspacing=0 bgcolor=\"").append(isYourLevel ? BG_CARD_HIGHLIGHT : BG_CARD).append("\"><tr>");
			
			// Level bracket badge
			sb.append("<td width=64 align=center valign=top><font color=\"LEVEL\">").append(bracket.getName()).append("</font>");
			if (isYourLevel)
			{
				sb.append("<br1><font color=\"").append(COLOR_POSITIVE).append("\">Your level</font>");
			}
			sb.append("</td>");
			
			// Zone name and modifier
			sb.append("<td width=276 align=left valign=top>");
			sb.append("<font color=\"").append(COLOR_TITLE).append("\">").append(zoneName).append("</font>");
			if (modifier != null)
			{
				sb.append("<br1><font color=\"").append(COLOR_MODIFIER).append("\">").append(getModifierName(modifier)).append("</font>");
				sb.append("<br1><font color=\"").append(COLOR_MUTED).append("\">").append(modifier.getDescription()).append("</font>");
			}
			sb.append("</td>");
			
			// Actions
			sb.append("<td width=60 align=right valign=top>");
			sb.append(button("Solo", "bypass -h Script RotatingHotZones teleport_" + zoneId, 56));
			sb.append("</td>");
			sb.append("<td width=60 align=right valign=top>");
			if (isPartyLeader)
			{
				sb.append(button("Party", "bypass -h Script RotatingHotZones teleport_party_" + zoneId, 56));
			}
			sb.append("</td>");
			
			sb.append("</tr></table>");
			appendDivider(sb, MENU_WIDTH);
		}
		
		// Footer
		sb.append("<br><font color=\"").append(COLOR_HINT).append("\">Teleports work from peace zones only.<br1>");
		if (isPartyLeader)
		{
			sb.append("Party sends your members an invite to join you.");
		}
		else if (player.isInParty())
		{
			sb.append("Only your party leader can teleport the party.");
		}
		else
		{
			sb.append("Form a party and lead it to teleport everyone together.");
		}
		sb.append("</font>");
		
		sb.append("</center></body></html>");
		
		html.setHtml(sb.toString());
		player.sendPacket(html);
	}
	
	/**
	 * @param modifier the modifier to name
	 * @return the modifier's enum name in title case, e.g. {@code BLOODY_HARVEST} becomes "Bloody Harvest"
	 */
	private static String getModifierName(HotzoneModifier modifier)
	{
		final StringBuilder name = new StringBuilder();
		for (String word : modifier.name().split("_"))
		{
			if (name.length() > 0)
			{
				name.append(' ');
			}
			name.append(word.charAt(0)).append(word.substring(1).toLowerCase());
		}
		return name.toString();
	}
	
	/**
	 * @param millis time left, clamped to zero
	 * @return a compact "1h 05m" / "42m" / "under 1m" countdown
	 */
	private static String formatTimeLeft(long millis)
	{
		final long minutes = Math.max(0, millis) / 60000;
		if (minutes >= 60)
		{
			return (minutes / 60) + "h " + String.format("%02d", minutes % 60) + "m";
		}
		return minutes > 0 ? minutes + "m" : "under 1m";
	}
	
	private static String button(String value, String action, int width)
	{
		return "<button value=\"" + value + "\" action=\"" + action + "\" width=" + width + " height=25 back=\"L2UI_CT1.Button_DF_Down\" fore=\"L2UI_CT1.Button_DF\">";
	}
	
	private static void appendDivider(StringBuilder sb, int width)
	{
		sb.append("<img src=\"L2UI.SquareGray\" width=").append(width).append(" height=1>");
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
			else if (character.isPvpFakePlayer())
			{
				// A roaming fake player is no monster: it gets the player side of the hotzone.
				Skill buff = getSafeSkill(PLAYER_BUFF_ID, 1);
				if (buff != null)
				{
					buff.applyEffects(character, character);
				}
				syncModifierBuff(character);
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
				if (character.isPlayer() || character.isPvpFakePlayer())
				{
					syncModifierBuff(character);
				}
				return;
			}

			if (character.isPlayer())
			{
				character.stopSkillEffects(SkillFinishType.REMOVED, PLAYER_BUFF_ID);
				syncModifierBuff(character.asPlayer());
				character.asPlayer().sendMessage("You have left the active Hot Zone.");
			}
			else if (character.isPvpFakePlayer())
			{
				character.stopSkillEffects(SkillFinishType.REMOVED, PLAYER_BUFF_ID);
				syncModifierBuff(character);
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