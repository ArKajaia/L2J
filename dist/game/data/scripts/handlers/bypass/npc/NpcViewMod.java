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
package handlers.bypass.npc;

import java.text.DecimalFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.StringTokenizer;
import java.util.concurrent.TimeUnit;

import org.l2jmobius.commons.util.StringUtil;
import org.l2jmobius.gameserver.ai.AttackableAI;
import org.l2jmobius.gameserver.cache.HtmCache;
import org.l2jmobius.gameserver.config.NpcConfig;
import org.l2jmobius.gameserver.config.RatesConfig;
import org.l2jmobius.gameserver.config.custom.PremiumSystemConfig;
import org.l2jmobius.gameserver.data.xml.ClassListData;
import org.l2jmobius.gameserver.data.xml.ItemData;
import org.l2jmobius.gameserver.handler.IBypassHandler;
import org.l2jmobius.gameserver.model.World;
import org.l2jmobius.gameserver.model.WorldObject;
import org.l2jmobius.gameserver.model.actor.Creature;
import org.l2jmobius.gameserver.model.actor.Npc;
import org.l2jmobius.gameserver.model.actor.Player;
import org.l2jmobius.gameserver.model.actor.enums.npc.DropType;
import org.l2jmobius.gameserver.model.actor.enums.npc.MonsterArchetype;
import org.l2jmobius.gameserver.model.actor.enums.player.PlayerClass;
import org.l2jmobius.gameserver.model.actor.holders.npc.DropGroupHolder;
import org.l2jmobius.gameserver.model.actor.holders.npc.DropHolder;
import org.l2jmobius.gameserver.model.actor.holders.npc.FakePlayerHolder;
import org.l2jmobius.gameserver.model.actor.holders.npc.FakePlayerPvpProfile;
import org.l2jmobius.gameserver.model.actor.holders.npc.FakePlayerPvpWeapon;
import org.l2jmobius.gameserver.model.actor.holders.player.ClassInfoHolder;
import org.l2jmobius.gameserver.model.actor.stat.PlayerStat;
import org.l2jmobius.gameserver.model.item.ItemTemplate;
import org.l2jmobius.gameserver.model.item.Weapon;
import org.l2jmobius.gameserver.model.item.enums.BodyPart;
import org.l2jmobius.gameserver.model.item.holders.Elementals;
import org.l2jmobius.gameserver.model.item.holders.ItemEnchantHolder;
import org.l2jmobius.gameserver.model.itemcontainer.Inventory;
import org.l2jmobius.gameserver.model.skill.Skill;
import org.l2jmobius.gameserver.model.spawns.Spawn;
import org.l2jmobius.gameserver.network.serverpackets.NpcHtmlMessage;
import org.l2jmobius.gameserver.util.HtmlUtil;

/**
 * @author NosBit, Mobius
 */
public class NpcViewMod implements IBypassHandler
{
	private static final String[] COMMANDS =
	{
		"NpcViewMod"
	};
	
	private static final int DROP_LIST_ITEMS_PER_PAGE = 10;
	
	/** Window sizes (client side) of the NPC view and of the fake player view. */
	public static final int VIEW_WIDTH = 340;
	public static final int VIEW_HEIGHT = 640;
	private static final int FAKE_VIEW_WIDTH = 450;
	private static final int FAKE_VIEW_HEIGHT = 470;
	
	// The NPC variables listing the skills the mods grant (see the .skills command).
	private static final String VAR_PASSIVE_SKILL_IDS = "PASSIVE_SKILL_IDS";
	private static final String VAR_ARCHETYPE_SKILL_IDS = "AI_ARCHETYPE_GRANTED_SKILL_IDS";
	
	/** Empty inventory slot, like the client's paperdoll. */
	private static final String SLOT_BACKGROUND = "L2UI_CT1.ItemWindow_DF_SlotBox_Default";
	/** The paperdoll of the inventory window, row by row (-1 = no slot there). */
	private static final int[][] PAPERDOLL_LAYOUT =
	{
		{
			Inventory.PAPERDOLL_HAIR,
			Inventory.PAPERDOLL_HEAD,
			Inventory.PAPERDOLL_HAIR2
		},
		{
			Inventory.PAPERDOLL_GLOVES,
			Inventory.PAPERDOLL_CHEST,
			Inventory.PAPERDOLL_FEET
		},
		{
			Inventory.PAPERDOLL_CLOAK,
			Inventory.PAPERDOLL_LEGS,
			-1
		},
		{
			Inventory.PAPERDOLL_RHAND,
			-1,
			Inventory.PAPERDOLL_LHAND
		},
		{
			Inventory.PAPERDOLL_LEAR,
			Inventory.PAPERDOLL_REAR,
			Inventory.PAPERDOLL_NECK
		},
		{
			Inventory.PAPERDOLL_LFINGER,
			Inventory.PAPERDOLL_RFINGER,
			-1
		}
	};
	/** The order equipment is listed in under the paperdoll. */
	private static final int[] EQUIPMENT_LIST_ORDER =
	{
		Inventory.PAPERDOLL_RHAND,
		Inventory.PAPERDOLL_LHAND,
		Inventory.PAPERDOLL_HEAD,
		Inventory.PAPERDOLL_CHEST,
		Inventory.PAPERDOLL_LEGS,
		Inventory.PAPERDOLL_GLOVES,
		Inventory.PAPERDOLL_FEET,
		Inventory.PAPERDOLL_CLOAK,
		Inventory.PAPERDOLL_HAIR,
		Inventory.PAPERDOLL_HAIR2,
		Inventory.PAPERDOLL_LEAR,
		Inventory.PAPERDOLL_REAR,
		Inventory.PAPERDOLL_NECK,
		Inventory.PAPERDOLL_LFINGER,
		Inventory.PAPERDOLL_RFINGER
	};
	
	@Override
	public boolean onCommand(String command, Player player, Creature bypassOrigin)
	{
		final StringTokenizer st = new StringTokenizer(command);
		st.nextToken();
		
		if (!st.hasMoreTokens())
		{
			LOGGER.warning("Bypass[NpcViewMod] used without enough parameters.");
			return false;
		}
		
		final String actualCommand = st.nextToken();
		switch (actualCommand.toLowerCase())
		{
			case "view":
			{
				final WorldObject target;
				if (st.hasMoreElements())
				{
					try
					{
						target = World.getInstance().findObject(Integer.parseInt(st.nextToken()));
					}
					catch (NumberFormatException e)
					{
						return false;
					}
				}
				else
				{
					target = player.getTarget();
				}
				
				final Npc npc = target instanceof Npc ? target.asNpc() : null;
				if (npc == null)
				{
					return false;
				}
				
				sendNpcView(player, npc);
				break;
			}
			case "fakeview":
			{
				final WorldObject target = st.hasMoreElements() ? World.getInstance().findObject(parseInt(st.nextToken())) : player.getTarget();
				if ((target instanceof Npc) && target.isFakePlayer())
				{
					sendFakePlayerView(player, target.asNpc());
				}
				break;
			}
			case "droplist":
			{
				if (st.countTokens() < 2)
				{
					LOGGER.warning("Bypass[NpcViewMod] used without enough parameters.");
					return false;
				}
				
				final String dropListTypeString = st.nextToken();
				try
				{
					final DropType dropListType = Enum.valueOf(DropType.class, dropListTypeString);
					final WorldObject target = World.getInstance().findObject(Integer.parseInt(st.nextToken()));
					final Npc npc = target instanceof Npc ? target.asNpc() : null;
					if (npc == null)
					{
						return false;
					}
					
					final int page = st.hasMoreElements() ? Integer.parseInt(st.nextToken()) : 0;
					sendNpcDropList(player, npc, dropListType, page);
				}
				catch (NumberFormatException e)
				{
					return false;
				}
				catch (IllegalArgumentException e)
				{
					LOGGER.warning("Bypass[NpcViewMod] unknown drop list scope: " + dropListTypeString);
					return false;
				}
				break;
			}
			case "skills":
			{
				final WorldObject target;
				if (st.hasMoreElements())
				{
					try
					{
						target = World.getInstance().findObject(Integer.parseInt(st.nextToken()));
					}
					catch (NumberFormatException e)
					{
						return false;
					}
				}
				else
				{
					target = player.getTarget();
				}
				
				final Npc npc = target instanceof Npc ? target.asNpc() : null;
				if (npc == null)
				{
					return false;
				}
				
				sendNpcSkillView(player, npc);
				break;
			}
			case "aggrolist":
			{
				final WorldObject target;
				if (st.hasMoreElements())
				{
					try
					{
						target = World.getInstance().findObject(Integer.parseInt(st.nextToken()));
					}
					catch (NumberFormatException e)
					{
						return false;
					}
				}
				else
				{
					target = player.getTarget();
				}
				
				final Npc npc = target instanceof Npc ? target.asNpc() : null;
				if (npc == null)
				{
					return false;
				}
				
				sendAggroListView(player, npc);
				break;
			}
		}
		
		return true;
	}
	
	@Override
	public String[] getCommandList()
	{
		return COMMANDS;
	}
	
	public static void sendNpcView(Player player, Npc npc)
	{
		final NpcHtmlMessage html = new NpcHtmlMessage(npc.getObjectId());
		html.setWindowSize(VIEW_WIDTH, VIEW_HEIGHT);
		html.setFile(player, "data/html/mods/NpcView/Info.htm");
		html.replace("%name%", npc.getName());
		html.replace("%level%", npc.getLevel());
		html.replace("%type%", getNpcType(npc));
		html.replace("%archetype%", getArchetypeRow(npc));
		html.replace("%hpGauge%", HtmlUtil.getHpGauge(270, (long) npc.getCurrentHp(), npc.getMaxHp(), false));
		html.replace("%mpGauge%", HtmlUtil.getMpGauge(270, (long) npc.getCurrentMp(), npc.getMaxMp(), false));
		
		final Spawn npcSpawn = npc.getSpawn();
		if ((npcSpawn == null) || (npcSpawn.getRespawnMinDelay() == 0))
		{
			html.replace("%respawn%", "None");
		}
		else
		{
			long minRespawnDelay = (long) (npcSpawn.getRespawnMinDelay() * NpcConfig.RAID_MIN_RESPAWN_MULTIPLIER);
			long maxRespawnDelay = (long) (npcSpawn.getRespawnMaxDelay() * NpcConfig.RAID_MAX_RESPAWN_MULTIPLIER);
			TimeUnit timeUnit = TimeUnit.MILLISECONDS;
			
			long min = Long.MAX_VALUE;
			for (TimeUnit tu : TimeUnit.values())
			{
				final long minTimeFromMillis = tu.convert(npcSpawn.getRespawnMinDelay(), TimeUnit.MILLISECONDS);
				final long maxTimeFromMillis = tu.convert(npcSpawn.getRespawnMaxDelay(), TimeUnit.MILLISECONDS);
				if ((TimeUnit.MILLISECONDS.convert(minTimeFromMillis, tu) == npcSpawn.getRespawnMinDelay()) && (TimeUnit.MILLISECONDS.convert(maxTimeFromMillis, tu) == npcSpawn.getRespawnMaxDelay()) && (min > minTimeFromMillis))
				{
					min = minTimeFromMillis;
					timeUnit = tu;
				}
			}
			
			minRespawnDelay = timeUnit.convert(minRespawnDelay, TimeUnit.MILLISECONDS);
			maxRespawnDelay = timeUnit.convert(maxRespawnDelay, TimeUnit.MILLISECONDS);
			
			final String timeUnitName = timeUnit.name().charAt(0) + timeUnit.name().toLowerCase().substring(1);
			if (npcSpawn.hasRespawnRandom())
			{
				html.replace("%respawn%", minRespawnDelay + "-" + maxRespawnDelay + " " + timeUnitName);
			}
			else
			{
				html.replace("%respawn%", minRespawnDelay + " " + timeUnitName);
			}
		}
		
		html.replace("%atktype%", StringUtil.capitalizeFirst(npc.getAttackType().name().toLowerCase()));
		html.replace("%atkrange%", npc.getStat().getPhysicalAttackRange());
		html.replace("%patk%", (int) npc.getPAtk(player));
		html.replace("%pdef%", (int) npc.getPDef(player));
		html.replace("%matk%", (int) npc.getMAtk(player, null));
		html.replace("%mdef%", (int) npc.getMDef(player, null));
		html.replace("%atkspd%", Math.round(npc.getPAtkSpd()));
		html.replace("%castspd%", npc.getMAtkSpd());
		html.replace("%critrate%", npc.getStat().getCriticalHit(player, null));
		html.replace("%evasion%", npc.getEvasionRate(player));
		html.replace("%accuracy%", npc.getStat().getAccuracy());
		html.replace("%speed%", (int) npc.getStat().getMoveSpeed());
		html.replace("%attributeatktype%", Elementals.getElementName(npc.getStat().getAttackElement()));
		html.replace("%attributeatkvalue%", npc.getStat().getAttackElementValue(npc.getStat().getAttackElement()));
		html.replace("%attributefire%", npc.getStat().getDefenseElementValue(Elementals.FIRE));
		html.replace("%attributewater%", npc.getStat().getDefenseElementValue(Elementals.WATER));
		html.replace("%attributewind%", npc.getStat().getDefenseElementValue(Elementals.WIND));
		html.replace("%attributeearth%", npc.getStat().getDefenseElementValue(Elementals.EARTH));
		html.replace("%attributedark%", npc.getStat().getDefenseElementValue(Elementals.DARK));
		html.replace("%attributeholy%", npc.getStat().getDefenseElementValue(Elementals.HOLY));
		html.replace("%str%", npc.getSTR());
		html.replace("%dex%", npc.getDEX());
		html.replace("%con%", npc.getCON());
		html.replace("%int%", npc.getINT());
		html.replace("%wit%", npc.getWIT());
		html.replace("%men%", npc.getMEN());
		html.replace("%passives%", getPassives(npc));
		html.replace("%dropListButtons%", getViewButtons(npc));
		player.sendPacket(html);
	}
	
	/**
	 * @param npc an NPC
	 * @return what it is: raid boss, minion, monster or NPC
	 */
	private static String getNpcType(Npc npc)
	{
		if (npc.isRaid())
		{
			return "Raid Boss";
		}
		
		if (npc.isMinion())
		{
			return "Minion";
		}
		
		if (npc.isMonster() && npc.asMonster().isHotzoneMiniboss())
		{
			return "Hotzone Menace";
		}
		
		return npc.isMonster() ? "Monster" : "NPC";
	}
	
	/**
	 * @param npc an NPC
	 * @return the header row showing its combat personality (see {@link MonsterArchetype}), empty for NPCs that don't fight
	 */
	private static String getArchetypeRow(Npc npc)
	{
		if (!npc.isAttackable() || !npc.hasAI() || !(npc.getAI() instanceof AttackableAI))
		{
			return "";
		}
		
		final MonsterArchetype archetype = ((AttackableAI) npc.getAI()).getArchetype();
		if (archetype == null)
		{
			return "";
		}
		
		final String name = archetype.name().charAt(0) + archetype.name().substring(1).toLowerCase();
		return "<tr><td height=6></td></tr><tr><td align=center><font color=\"A0A0A0\">Archetype:</font> <font color=\"FF9955\">" + name + "</font></td></tr><tr><td align=center><font color=\"707070\">" + getArchetypeHint(archetype) + "</font></td></tr>";
	}
	
	private static String getArchetypeHint(MonsterArchetype archetype)
	{
		switch (archetype)
		{
			case AGGRESSIVE:
			{
				return "Presses the attack, rarely flees";
			}
			case COWARD:
			{
				return "Flees when hurt, avoids melee";
			}
			case SKILLFUL:
			{
				return "Uses its skills often and well";
			}
			case MAGE:
			{
				return "Keeps its distance and casts";
			}
			case FIGHTER:
			{
				return "Rushes in, fights up close";
			}
			case SUPPORTER:
			{
				return "Heals and buffs itself and its allies";
			}
			case RAGER:
			{
				return "Gets angrier as it loses HP, never flees";
			}
			case AVENGER:
			{
				return "Sticks to whoever hurt it last";
			}
			default:
			{
				return "No strong habits";
			}
		}
	}
	
	/**
	 * @param npc an NPC
	 * @return its passive skills, in a compact section, empty if it has none
	 */
	private static String getPassives(Npc npc)
	{
		final StringBuilder sb = new StringBuilder();
		for (Skill skill : npc.getSkills().values())
		{
			if (skill.isPassive())
			{
				sb.append(sb.length() > 0 ? "<font color=\"707070\">, </font>" : "").append("<font color=\"66CCFF\">").append(skill.getName()).append("</font>");
			}
		}
		
		if (sb.length() == 0)
		{
			return "";
		}
		
		return "<br1><table width=300 cellpadding=3 cellspacing=0 bgcolor=\"111111\"><tr><td><font color=\"LEVEL\">Passives</font></td></tr></table><table width=300 cellpadding=2 cellspacing=0><tr><td width=300>" + sb + "</td></tr></table>";
	}
	
	/**
	 * @param npc an NPC
	 * @return the Drop, Spoil and Skills (the .skills command: the skills the mods gave it) buttons it has
	 */
	private static String getViewButtons(Npc npc)
	{
		final StringBuilder sb = new StringBuilder();
		final List<DropGroupHolder> dropListGroups = npc.getTemplate().getDropGroups();
		final List<DropHolder> dropListDeath = npc.getTemplate().getDropList();
		final List<DropHolder> dropListSpoil = npc.getTemplate().getSpoilList();
		if ((dropListGroups != null) || (dropListDeath != null))
		{
			sb.append("<td align=center><button value=\"Drop\" width=90 height=25 action=\"bypass NpcViewMod dropList DROP ").append(npc.getObjectId()).append("\" back=\"L2UI_CT1.Button_DF_Calculator_Down\" fore=\"L2UI_CT1.Button_DF_Calculator\"></td>");
		}
		
		if (dropListSpoil != null)
		{
			sb.append("<td align=center><button value=\"Spoil\" width=90 height=25 action=\"bypass NpcViewMod dropList SPOIL ").append(npc.getObjectId()).append("\" back=\"L2UI_CT1.Button_DF_Calculator_Down\" fore=\"L2UI_CT1.Button_DF_Calculator\"></td>");
		}
		
		if (!npc.getVariables().getString(VAR_PASSIVE_SKILL_IDS, "").isEmpty() || !npc.getVariables().getString(VAR_ARCHETYPE_SKILL_IDS, "").isEmpty())
		{
			sb.append("<td align=center><button value=\"Skills\" width=90 height=25 action=\"bypass voice .skills\" back=\"L2UI_CT1.Button_DF_Calculator_Down\" fore=\"L2UI_CT1.Button_DF_Calculator\"></td>");
		}
		
		return sb.length() == 0 ? "" : "<table width=300 cellpadding=0 cellspacing=0><tr>" + sb + "</tr></table>";
	}
	
	/**
	 * What a player sees of a fake player, like inspecting another player: its equipment laid out like the inventory paperdoll (item icons, enchant levels), its name, class, level and base stats. Nothing a player couldn't know (no combat stats, drops or
	 * behaviour).
	 * @param player the player looking
	 * @param npc the fake player
	 */
	public static void sendFakePlayerView(Player player, Npc npc)
	{
		final ItemEnchantHolder[] paperdoll = new ItemEnchantHolder[Inventory.PAPERDOLL_TOTALSLOTS];
		PlayerClass playerClass = null;
		final FakePlayerPvpProfile profile = npc.getTemplate().getFakePlayerPvpProfile();
		final FakePlayerHolder holder = npc.getTemplate().getFakePlayerInfo();
		if (profile != null)
		{
			playerClass = profile.getPlayerClass();
			
			// Armor and jewels as it wears them (its spare bow or polearm stays in the bag).
			int shieldEnchant = 0;
			for (ItemEnchantHolder item : profile.getEquipment())
			{
				final ItemTemplate template = ItemData.getInstance().getTemplate(item.getId());
				if ((template == null) || (template instanceof Weapon))
				{
					continue;
				}
				
				if (template.getBodyPart() == BodyPart.L_HAND)
				{
					shieldEnchant = item.getEnchantLevel();
					continue;
				}
				
				equip(paperdoll, template, item.getEnchantLevel());
			}
			
			// What it holds right now: its weapon (and shield), its bow, or nothing when disarmed.
			final FakePlayerPvpWeapon held = profile.getHeldWeapon();
			if (held != null)
			{
				if (held.getWeaponId() > 0)
				{
					paperdoll[Inventory.PAPERDOLL_RHAND] = new ItemEnchantHolder(held.getWeaponId(), 1, held.getEnchant());
				}
				if (held.getShieldId() > 0)
				{
					paperdoll[Inventory.PAPERDOLL_LHAND] = new ItemEnchantHolder(held.getShieldId(), 1, shieldEnchant);
				}
			}
		}
		else if (holder != null)
		{
			playerClass = holder.getPlayerClass();
			final int armorEnchant = holder.getArmorEnchantLevel();
			setSlot(paperdoll, Inventory.PAPERDOLL_RHAND, holder.getEquipRHand(), holder.getWeaponEnchantLevel());
			setSlot(paperdoll, Inventory.PAPERDOLL_LHAND, holder.getEquipLHand(), armorEnchant);
			setSlot(paperdoll, Inventory.PAPERDOLL_HEAD, holder.getEquipHead(), armorEnchant);
			setSlot(paperdoll, Inventory.PAPERDOLL_CHEST, holder.getEquipChest(), armorEnchant);
			setSlot(paperdoll, Inventory.PAPERDOLL_LEGS, holder.getEquipLegs(), armorEnchant);
			setSlot(paperdoll, Inventory.PAPERDOLL_GLOVES, holder.getEquipGloves(), armorEnchant);
			setSlot(paperdoll, Inventory.PAPERDOLL_FEET, holder.getEquipFeet(), armorEnchant);
			setSlot(paperdoll, Inventory.PAPERDOLL_CLOAK, holder.getEquipCloak(), 0);
			setSlot(paperdoll, Inventory.PAPERDOLL_HAIR, holder.getEquipHair(), 0);
			setSlot(paperdoll, Inventory.PAPERDOLL_HAIR2, holder.getEquipHair2(), 0);
		}
		
		// The paperdoll: icons in their slots, the enchant level under each.
		final StringBuilder grid = new StringBuilder();
		for (int[] row : PAPERDOLL_LAYOUT)
		{
			grid.append("<tr>");
			for (int slot : row)
			{
				final ItemTemplate item = (slot >= 0) && (paperdoll[slot] != null) ? ItemData.getInstance().getTemplate(paperdoll[slot].getId()) : null;
				if (slot < 0)
				{
					grid.append("<td width=38 height=38></td>");
				}
				else if (item == null)
				{
					grid.append("<td width=38 height=38 background=\"").append(SLOT_BACKGROUND).append("\"></td>");
				}
				else
				{
					grid.append("<td width=38 height=38 align=center valign=middle background=\"").append(SLOT_BACKGROUND).append("\"><img src=\"").append(item.getIcon()).append("\" width=32 height=32></td>");
				}
			}
			grid.append("</tr><tr>");
			for (int slot : row)
			{
				final int enchant = (slot >= 0) && (paperdoll[slot] != null) ? paperdoll[slot].getEnchantLevel() : 0;
				grid.append("<td width=38 height=12 align=center>").append(enchant > 0 ? "<font color=\"E6C35C\">+" + enchant + "</font>" : "").append("</td>");
			}
			grid.append("</tr>");
		}
		
		// Names, since icons have no tooltip here.
		final StringBuilder list = new StringBuilder();
		boolean shade = false;
		for (int slot : EQUIPMENT_LIST_ORDER)
		{
			final ItemTemplate item = paperdoll[slot] != null ? ItemData.getInstance().getTemplate(paperdoll[slot].getId()) : null;
			if (item == null)
			{
				continue;
			}
			
			final int enchant = paperdoll[slot].getEnchantLevel();
			list.append("<tr><td width=410 height=16").append(shade ? " bgcolor=\"111111\"" : "").append(">").append(enchant > 0 ? "<font color=\"E6C35C\">+" + enchant + "</font> " : "").append(item.getName()).append("</td></tr>");
			shade = !shade;
		}
		
		final ClassInfoHolder classInfo = playerClass != null ? ClassListData.getInstance().getClass(playerClass) : null;
		final NpcHtmlMessage html = new NpcHtmlMessage(npc.getObjectId());
		html.setWindowSize(FAKE_VIEW_WIDTH, FAKE_VIEW_HEIGHT);
		html.setFile(player, "data/html/mods/NpcView/FakePlayer.htm");
		html.replace("%name%", npc.getName());
		html.replace("%level%", npc.getLevel());
		html.replace("%class%", classInfo != null ? classInfo.getClassName() : "-");
		html.replace("%paperdoll%", grid.toString());
		html.replace("%equipment%", list.length() > 0 ? list.toString() : "<tr><td width=410><font color=\"707070\">Nothing equipped.</font></td></tr>");
		html.replace("%str%", npc.getSTR());
		html.replace("%dex%", npc.getDEX());
		html.replace("%con%", npc.getCON());
		html.replace("%int%", npc.getINT());
		html.replace("%wit%", npc.getWIT());
		html.replace("%men%", npc.getMEN());
		html.replace("%objectId%", npc.getObjectId());
		player.sendPacket(html);
	}
	
	/**
	 * Puts {@code item} in its paperdoll slot, like equipping it: a full armor in the chest slot, the second earring or ring in the other ear or finger.
	 */
	private static void equip(ItemEnchantHolder[] paperdoll, ItemTemplate item, int enchant)
	{
		final int slot;
		switch (item.getBodyPart())
		{
			case LR_EAR:
			{
				slot = paperdoll[Inventory.PAPERDOLL_LEAR] == null ? Inventory.PAPERDOLL_LEAR : Inventory.PAPERDOLL_REAR;
				break;
			}
			case LR_FINGER:
			{
				slot = paperdoll[Inventory.PAPERDOLL_LFINGER] == null ? Inventory.PAPERDOLL_LFINGER : Inventory.PAPERDOLL_RFINGER;
				break;
			}
			default:
			{
				slot = BodyPart.getPaperdollIndex(item.getBodyPart());
				break;
			}
		}
		
		if ((slot >= 0) && (slot < paperdoll.length))
		{
			paperdoll[slot] = new ItemEnchantHolder(item.getId(), 1, enchant);
		}
	}
	
	private static void setSlot(ItemEnchantHolder[] paperdoll, int slot, int itemId, int enchant)
	{
		if ((itemId > 0) && (ItemData.getInstance().getTemplate(itemId) != null))
		{
			paperdoll[slot] = new ItemEnchantHolder(itemId, 1, enchant);
		}
	}
	
	private static int parseInt(String value)
	{
		try
		{
			return Integer.parseInt(value);
		}
		catch (NumberFormatException e)
		{
			return 0;
		}
	}
	
	private void sendNpcSkillView(Player player, Npc npc)
	{
		final NpcHtmlMessage html = new NpcHtmlMessage(npc.getObjectId());
		html.setWindowSize(VIEW_WIDTH, VIEW_HEIGHT);
		html.setFile(player, "data/html/mods/NpcView/Skills.htm");
		
		final StringBuilder sb = new StringBuilder();
		npc.getSkills().values().forEach(s ->
		{
			sb.append("<table width=277 height=32 cellspacing=0 background=\"L2UI_CT1.Windows.Windows_DF_TooltipBG\">");
			sb.append("<tr><td width=32>");
			sb.append("<img src=\"");
			sb.append(s.getIcon());
			sb.append("\" width=32 height=32>");
			sb.append("</td><td width=110>");
			sb.append(s.getName());
			sb.append("</td>");
			sb.append("<td width=45 align=center>");
			sb.append(s.getId());
			sb.append("</td>");
			sb.append("<td width=35 align=center>");
			sb.append(s.getLevel());
			sb.append("</td></tr></table>");
		});
		
		html.replace("%skills%", sb.toString());
		html.replace("%npc_name%", npc.getName());
		html.replace("%npcId%", npc.getId());
		html.replace("%objid%", npc.getObjectId());
		player.sendPacket(html);
	}
	
	private void sendAggroListView(Player player, Npc npc)
	{
		final NpcHtmlMessage html = new NpcHtmlMessage(npc.getObjectId());
		html.setWindowSize(VIEW_WIDTH, VIEW_HEIGHT);
		html.setFile(player, "data/html/mods/NpcView/AggroList.htm");
		
		final StringBuilder sb = new StringBuilder();
		if (npc.isAttackable())
		{
			npc.asAttackable().getAggroList().values().forEach(a ->
			{
				sb.append("<table width=277 height=32 cellspacing=0 background=\"L2UI_CT1.Windows.Windows_DF_TooltipBG\">");
				sb.append("<tr><td width=110>");
				sb.append(a.getAttacker() != null ? a.getAttacker().getName() : "NULL");
				sb.append("</td>");
				sb.append("<td width=60 align=center>");
				sb.append(a.getHate());
				sb.append("</td>");
				sb.append("<td width=60 align=center>");
				sb.append(a.getDamage());
				sb.append("</td></tr></table>");
			});
		}
		
		html.replace("%aggrolist%", sb.toString());
		html.replace("%npc_name%", npc.getName());
		html.replace("%npcId%", npc.getId());
		html.replace("%objid%", npc.getObjectId());
		player.sendPacket(html);
	}
	
	private static String getDropListButtons(Npc npc)
	{
		final StringBuilder sb = new StringBuilder();
		final List<DropGroupHolder> dropListGroups = npc.getTemplate().getDropGroups();
		final List<DropHolder> dropListDeath = npc.getTemplate().getDropList();
		final List<DropHolder> dropListSpoil = npc.getTemplate().getSpoilList();
		if ((dropListGroups != null) || (dropListDeath != null) || (dropListSpoil != null))
		{
			sb.append("<table width=275 cellpadding=0 cellspacing=0><tr>");
			if ((dropListGroups != null) || (dropListDeath != null))
			{
				sb.append("<td align=center><button value=\"Show Drop\" width=100 height=25 action=\"bypass NpcViewMod dropList DROP " + npc.getObjectId() + "\" back=\"L2UI_CT1.Button_DF_Calculator_Down\" fore=\"L2UI_CT1.Button_DF_Calculator\"></td>");
			}
			
			if (dropListSpoil != null)
			{
				sb.append("<td align=center><button value=\"Show Spoil\" width=100 height=25 action=\"bypass NpcViewMod dropList SPOIL " + npc.getObjectId() + "\" back=\"L2UI_CT1.Button_DF_Calculator_Down\" fore=\"L2UI_CT1.Button_DF_Calculator\"></td>");
			}
			
			sb.append("</tr></table>");
		}
		
		return sb.toString();
	}
	
	private void sendNpcDropList(Player player, Npc npc, DropType dropType, int pageValue)
	{
		List<DropHolder> dropList = null;
		if (dropType == DropType.SPOIL)
		{
			dropList = new ArrayList<>(npc.getTemplate().getSpoilList());
		}
		else
		{
			final List<DropHolder> drops = npc.getTemplate().getDropList();
			if (drops != null)
			{
				dropList = new ArrayList<>(drops);
			}
			
			final List<DropGroupHolder> dropGroups = npc.getTemplate().getDropGroups();
			if (dropGroups != null)
			{
				if (dropList == null)
				{
					dropList = new ArrayList<>();
				}
				
				for (DropGroupHolder dropGroup : dropGroups)
				{
					final double chance = dropGroup.getChance() / 100;
					for (DropHolder dropHolder : dropGroup.getDropList())
					{
						dropList.add(new DropHolder(dropHolder.getDropType(), dropHolder.getItemId(), dropHolder.getMin(), dropHolder.getMax(), dropHolder.getChance() * chance));
					}
				}
			}
		}
		
		if (dropList == null)
		{
			return;
		}
		
		Collections.sort(dropList, (d1, d2) -> Integer.valueOf(d1.getItemId()).compareTo(Integer.valueOf(d2.getItemId())));
		
		int pages = dropList.size() / DROP_LIST_ITEMS_PER_PAGE;
		if ((DROP_LIST_ITEMS_PER_PAGE * pages) < dropList.size())
		{
			pages++;
		}
		
		final StringBuilder pagesSb = new StringBuilder();
		if (pages > 1)
		{
			pagesSb.append("<table><tr>");
			for (int i = 0; i < pages; i++)
			{
				pagesSb.append("<td align=center><button value=\"" + (i + 1) + "\" width=20 height=20 action=\"bypass NpcViewMod dropList " + dropType + " " + npc.getObjectId() + " " + i + "\" back=\"L2UI_CT1.Button_DF_Calculator_Down\" fore=\"L2UI_CT1.Button_DF_Calculator\"></td>");
			}
			pagesSb.append("</tr></table>");
		}
		
		int page = pageValue;
		if (page >= pages)
		{
			page = pages - 1;
		}
		
		final int start = page > 0 ? page * DROP_LIST_ITEMS_PER_PAGE : 0;
		int end = (page * DROP_LIST_ITEMS_PER_PAGE) + DROP_LIST_ITEMS_PER_PAGE;
		if (end > dropList.size())
		{
			end = dropList.size();
		}
		
		final DecimalFormat amountFormat = new DecimalFormat("#,###");
		final DecimalFormat chanceFormat = new DecimalFormat("0.00##");
		int leftHeight = 0;
		int rightHeight = 0;
		final PlayerStat stat = player.getStat();
		final double dropAmountAdenaEffectBonus = stat.getBonusDropAdenaMultiplier();
		final double dropAmountEffectBonus = stat.getBonusDropAmountMultiplier();
		final double dropRateEffectBonus = stat.getBonusDropRateMultiplier();
		final double spoilRateEffectBonus = stat.getBonusSpoilRateMultiplier();
		final StringBuilder leftSb = new StringBuilder();
		final StringBuilder rightSb = new StringBuilder();
		String limitReachedMsg = "";
		for (int i = start; i < end; i++)
		{
			final StringBuilder sb = new StringBuilder();
			final int height = 64;
			final DropHolder dropItem = dropList.get(i);
			final ItemTemplate item = ItemData.getInstance().getTemplate(dropItem.getItemId());
			
			// real time server rate calculations
			double rateChance = 1;
			double rateAmount = 1;
			if (dropType == DropType.SPOIL)
			{
				rateChance = RatesConfig.RATE_SPOIL_DROP_CHANCE_MULTIPLIER;
				rateAmount = RatesConfig.RATE_SPOIL_DROP_AMOUNT_MULTIPLIER;
				
				// also check premium rates if available
				if (PremiumSystemConfig.PREMIUM_SYSTEM_ENABLED && player.hasPremiumStatus())
				{
					rateChance *= PremiumSystemConfig.PREMIUM_RATE_SPOIL_CHANCE;
					rateAmount *= PremiumSystemConfig.PREMIUM_RATE_SPOIL_AMOUNT;
				}
				
				// bonus spoil rate effect
				rateChance *= spoilRateEffectBonus;
			}
			else
			{
				if (RatesConfig.RATE_DROP_CHANCE_BY_ID.get(dropItem.getItemId()) != null)
				{
					rateChance *= RatesConfig.RATE_DROP_CHANCE_BY_ID.get(dropItem.getItemId());
					if ((dropItem.getItemId() == Inventory.ADENA_ID) && (rateChance > 100))
					{
						rateChance = 100;
					}
				}
				else if (item.hasExImmediateEffect())
				{
					rateChance *= RatesConfig.RATE_HERB_DROP_CHANCE_MULTIPLIER;
				}
				else if (npc.isRaid())
				{
					rateChance *= RatesConfig.RATE_RAID_DROP_CHANCE_MULTIPLIER;
				}
				else
				{
					rateChance *= RatesConfig.RATE_DEATH_DROP_CHANCE_MULTIPLIER;
				}
				
				if (RatesConfig.RATE_DROP_AMOUNT_BY_ID.get(dropItem.getItemId()) != null)
				{
					rateAmount *= RatesConfig.RATE_DROP_AMOUNT_BY_ID.get(dropItem.getItemId());
				}
				else if (item.hasExImmediateEffect())
				{
					rateAmount *= RatesConfig.RATE_HERB_DROP_AMOUNT_MULTIPLIER;
				}
				else if (npc.isRaid())
				{
					rateAmount *= RatesConfig.RATE_RAID_DROP_AMOUNT_MULTIPLIER;
				}
				else
				{
					rateAmount *= RatesConfig.RATE_DEATH_DROP_AMOUNT_MULTIPLIER;
				}
				
				// also check premium rates if available
				if (PremiumSystemConfig.PREMIUM_SYSTEM_ENABLED && player.hasPremiumStatus())
				{
					if (PremiumSystemConfig.PREMIUM_RATE_DROP_CHANCE_BY_ID.get(dropItem.getItemId()) != null)
					{
						rateChance *= PremiumSystemConfig.PREMIUM_RATE_DROP_CHANCE_BY_ID.get(dropItem.getItemId());
					}
					else if (item.hasExImmediateEffect())
					{
						// TODO: Premium herb chance? :)
					}
					else if (npc.isRaid())
					{
						// TODO: Premium raid chance? :)
					}
					else
					{
						rateChance *= PremiumSystemConfig.PREMIUM_RATE_DROP_CHANCE;
					}
					
					if (PremiumSystemConfig.PREMIUM_RATE_DROP_AMOUNT_BY_ID.get(dropItem.getItemId()) != null)
					{
						rateAmount *= PremiumSystemConfig.PREMIUM_RATE_DROP_AMOUNT_BY_ID.get(dropItem.getItemId());
					}
					else if (item.hasExImmediateEffect())
					{
						// TODO: Premium herb amount? :)
					}
					else if (npc.isRaid())
					{
						// TODO: Premium raid amount? :)
					}
					else
					{
						rateAmount *= PremiumSystemConfig.PREMIUM_RATE_DROP_AMOUNT;
					}
				}
				
				// bonus drop amount effect
				rateAmount *= dropAmountEffectBonus;
				if (item.getId() == Inventory.ADENA_ID)
				{
					rateAmount *= dropAmountAdenaEffectBonus;
				}
				
				// bonus drop rate effect
				rateChance *= dropRateEffectBonus;
			}
			
			// Do not display zero chance drops.
			if (rateChance == 0d)
			{
				continue;
			}
			
			sb.append("<table width=332 cellpadding=2 cellspacing=0 background=\"L2UI_CT1.Windows.Windows_DF_TooltipBG\">");
			sb.append("<tr><td width=32 valign=top>");
			sb.append("<img src=\"" + (item.getIcon() == null ? "icon.etc_question_mark_i00" : item.getIcon()) + "\" width=32 height=32>");
			sb.append("</td><td fixwidth=300 align=center><font name=\"hs9\" color=\"CD9000\">");
			sb.append(item.getName());
			sb.append("</font></td></tr><tr><td width=32></td><td width=300><table width=295 cellpadding=0 cellspacing=0>");
			sb.append("<tr><td width=48 align=right valign=top><font color=\"LEVEL\">Amount:</font></td>");
			sb.append("<td width=247 align=center>");
			
			final long min = (long) (dropItem.getMin() * rateAmount);
			final long max = (long) (dropItem.getMax() * rateAmount);
			if (min == max)
			{
				sb.append(amountFormat.format(min));
			}
			else
			{
				sb.append(amountFormat.format(min));
				sb.append(" - ");
				sb.append(amountFormat.format(max));
			}
			
			sb.append("</td></tr><tr><td width=48 align=right valign=top><font color=\"LEVEL\">Chance:</font></td>");
			sb.append("<td width=247 align=center>");
			sb.append(chanceFormat.format(Math.min(dropItem.getChance() * rateChance, 100)));
			sb.append("%</td></tr></table></td></tr><tr><td width=32></td><td width=300>&nbsp;</td></tr></table>");
			if ((sb.length() + rightSb.length() + leftSb.length()) < 16000) // limit of 32766?
			{
				if (leftHeight >= (rightHeight + height))
				{
					rightSb.append(sb);
					rightHeight += height;
				}
				else
				{
					leftSb.append(sb);
					leftHeight += height;
				}
			}
			else
			{
				limitReachedMsg = "<br><center>Too many drops! Could not display them all!</center>";
			}
		}
		
		final StringBuilder bodySb = new StringBuilder();
		bodySb.append("<table><tr>");
		bodySb.append("<td>");
		bodySb.append(leftSb.toString());
		bodySb.append("</td><td>");
		bodySb.append(rightSb.toString());
		bodySb.append("</td>");
		bodySb.append("</tr></table>");
		
		String html = HtmCache.getInstance().getHtm(player, "data/html/mods/NpcView/DropList.htm");
		if (html == null)
		{
			LOGGER.warning(NpcViewMod.class.getSimpleName() + ": The html file data/html/mods/NpcView/DropList.htm could not be found.");
			return;
		}
		
		html = html.replace("%name%", npc.getName());
		html = html.replace("%dropListButtons%", getDropListButtons(npc));
		html = html.replace("%pages%", pagesSb.toString());
		html = html.replace("%items%", bodySb.toString() + limitReachedMsg);
		HtmlUtil.sendCBHtml(player, html);
	}
}
