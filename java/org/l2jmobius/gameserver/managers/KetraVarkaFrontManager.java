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
package org.l2jmobius.gameserver.managers;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

import org.l2jmobius.commons.util.Rnd;
import org.l2jmobius.gameserver.ai.Intention;
import org.l2jmobius.gameserver.config.custom.MapDeviceConfig;
import org.l2jmobius.gameserver.data.xml.NpcData;
import org.l2jmobius.gameserver.geoengine.GeoEngine;
import org.l2jmobius.gameserver.managers.conquest.ConquestCapture;
import org.l2jmobius.gameserver.model.Location;
import org.l2jmobius.gameserver.model.World;
import org.l2jmobius.gameserver.model.WorldObject;
import org.l2jmobius.gameserver.model.actor.Creature;
import org.l2jmobius.gameserver.model.actor.Npc;
import org.l2jmobius.gameserver.model.actor.Player;
import org.l2jmobius.gameserver.model.actor.enums.creature.Team;
import org.l2jmobius.gameserver.model.actor.enums.npc.AIType;
import org.l2jmobius.gameserver.model.actor.instance.Monster;
import org.l2jmobius.gameserver.model.actor.instance.QuestGuard;
import org.l2jmobius.gameserver.model.actor.templates.NpcTemplate;
import org.l2jmobius.gameserver.model.mapdevice.MapArena;
import org.l2jmobius.gameserver.model.mapdevice.MapRun;
import org.l2jmobius.gameserver.model.mapdevice.MapRun.MonsterStat;
import org.l2jmobius.gameserver.model.mapdevice.MapRun.Status;
import org.l2jmobius.gameserver.model.mapdevice.UniqueMap;
import org.l2jmobius.gameserver.model.script.Quest;
import org.l2jmobius.gameserver.network.serverpackets.MagicSkillUse;
import org.l2jmobius.gameserver.network.serverpackets.NpcHtmlMessage;

/**
 * The Ketra-Varka Front, a unique map of the Map Device ({@link UniqueMap.Type#FRONT}), worked from {@link UniqueMapManager}'s hooks.
 * <ul>
 * <li><b>The field</b>: both factions, in three sectors round three war totems on the line from the entry to the far side of the field.</li>
 * <li><b>The oath</b>: at the War Banner by the Return Portal (or {@code .front}) the first player to swear picks the side for the whole run. Every monster of that side in the field becomes one of its warriors ({@link QuestGuard}s: they fight the other side's
 * monsters, players can't hit them) and no longer counts toward the clear; the side's Prophet stands at the camp by the entry.</li>
 * <li><b>The totems</b>: the players and their warriors take a totem by outnumbering the enemy on it ({@link ConquestCapture#step}). The enemy of a sector the players hold is weaker, the warriors gather at the totems the players hold, and from the first
 * capture the enemy counterattacks one of them every few minutes.</li>
 * <li><b>Betrayal</b>: once 2 totems are held the enemy's envoy offers a deal. Taken, the warriors turn on the players and the enemy's monsters join them, for half again the drops.</li>
 * <li><b>The Siege of the Camp</b>: the enemy Warlord marches on the Prophet behind 3 Siege Totems (each one standing makes him stronger) and calls escorts at 75/50/25% of his HP. A fallen Prophet makes him stronger and his spoils smaller.</li>
 * <li><b>The reward of its own</b>: everyone inside at the Warlord's death is named Hero of their side (or the Twice-Sworn) for 7 days, a title shown over an empty one ({@link #decorateTitle}).</li>
 * </ul>
 * @author Mobius
 */
public class KetraVarkaFrontManager
{
	private static final Logger LOGGER = Logger.getLogger(KetraVarkaFrontManager.class.getName());

	/** The players' command, {@code .front}. */
	public static final String COMMAND = "front";

	// Npcs (data/stats/npcs/custom/MapDevice.xml).
	public static final int KETRA_WARRIOR = 900704;
	public static final int KETRA_ARCHER = 900705;
	public static final int VARKA_WARRIOR = 900706;
	public static final int VARKA_HUNTER = 900707;
	public static final int KETRA_PROPHET = 900708;
	public static final int VARKA_PROPHET = 900709;
	public static final int BANNER = 900710;
	public static final int KETRA_ENVOY = 900711;
	public static final int VARKA_ENVOY = 900712;
	public static final int TOTEM_NEUTRAL = 900713;
	public static final int TOTEM_KETRA = 900714;
	public static final int TOTEM_VARKA = 900715;
	private static final int KETRA_WARLORD = 21339; // Ketra Orc General
	private static final int VARKA_WARLORD = 21366; // Varka Silenos General
	private static final int SIEGE_TOTEM_NPC = 18667; // Ward of Death

	// The field.
	private static final double[] TOTEM_SHARES =
	{
		0.25,
		0.55,
		0.85
	};
	private static final String[] TOTEM_NAMES =
	{
		"the Near Totem",
		"the Middle Totem",
		"the Far Totem"
	};
	/** How far from its totem a sector's monsters stand, at most: on a smaller field (UniqueMapDensity), half the gap between two totems. */
	private static final int SECTOR_RADIUS = 2000;
	private static final int MIN_SECTOR_RADIUS = 800;
	private static final int MONSTER_SPACING = 250;
	private static final int CAMP_DISTANCE = 700;
	private static final int BANNER_OFFSET = 150;

	// The totems: Conquest's capture rule, players and their warriors against the enemy, within the radius.
	private static final int TOTEM_RADIUS = 600;
	private static final double CAPTURE_RATE = 5;
	private static final double CAPTURE_PER_EXTRA = 2.5;
	private static final double CAPTURE_MAX = 15;
	private static final int CAPTURE_MAJORITY = 25;
	private static final int CAPTURE_STALEMATE = 10;
	/** The enemy of a sector the players hold: attack and defence. */
	private static final double SECTOR_WEAKNESS = 0.8;

	// The warriors.
	private static final int SOLDIER_THINK = 3;
	private static final int SOLDIER_RANGE = 1200;
	private static final int REINFORCE_INTERVAL = 30;
	private static final int REINFORCE_PER_TOTEM = 2;
	private static final int SOLDIER_MAX = 14;

	// Counterattacks.
	private static final int COUNTER_INTERVAL = 180;
	private static final int COUNTER_SIZE = 6;
	private static final int COUNTER_MAX = 4;
	private static final int COUNTER_DISTANCE = 1500;

	// Betrayal.
	private static final int ENVOY_SECONDS = 120;
	private static final double BETRAYAL_DROPS = 1.5;

	// The Siege of the Camp.
	private static final int SIEGE_TOTEMS = 3;
	private static final double SIEGE_TOTEM_ATTACK = 0.15;
	private static final int ESCORTS = 3;
	private static final double PROPHET_FALLEN_ATTACK = 1.3;
	private static final double PROPHET_FALLEN_DROPS = 0.5;
	private static final int WARLORD_THINK = 2;

	// The title.
	private static final String TITLE_VAR = "FRONT_TITLE";
	private static final String TITLE_END_VAR = "FRONT_TITLE_END";
	private static final long TITLE_DAYS = 7;

	/** Which sector (1-3) a monster of the field was spawned in; 0 for none (counterattacks, the siege). */
	private static final String SECTOR_VAR = "FRONT_SECTOR";

	private static final int RAISE_SKILL_ID = 4526; // Quest - BOSS Summon (visual only)

	/** A side of the war. */
	public enum Side
	{
		NONE("nobody"),
		KETRA("Ketra"),
		VARKA("Varka");

		private final String _name;

		Side(String name)
		{
			_name = name;
		}

		public String getName()
		{
			return _name;
		}

		Side other()
		{
			return this == KETRA ? VARKA : this == VARKA ? KETRA : NONE;
		}
	}

	/** One war totem: the players are {@link Team#BLUE}, the enemy {@link Team#RED}, whatever side either is on. */
	private static class Totem
	{
		final int _index;
		final Location _spot;
		volatile int _npcId;
		double _progress;
		volatile Team _owner = Team.NONE;
		int _shownProgress = Integer.MIN_VALUE;

		Totem(int index, Location spot)
		{
			_index = index;
			_spot = spot;
		}
	}

	/** One Front: everything a run of the map remembers. */
	private static class Front
	{
		volatile Side _side = Side.NONE;
		volatile boolean _betrayed;
		final List<Totem> _totems = new ArrayList<>();
		final Set<Integer> _soldiers = ConcurrentHashMap.newKeySet();
		/** The counterattackers, with the index of the totem they march on. */
		final Map<Integer, Integer> _counterattackers = new ConcurrentHashMap<>();
		final Set<Integer> _siegeTotems = ConcurrentHashMap.newKeySet();
		final Set<Integer> _greeted = ConcurrentHashMap.newKeySet();
		Location _camp;
		volatile int _bannerId;
		volatile int _prophetId;
		volatile boolean _prophetFallen;
		volatile int _envoyId;
		volatile long _envoyUntil;
		volatile boolean _envoyCame;
		volatile long _nextCounter;
		volatile int _counters;
		volatile boolean _siege;
	}

	private static final Map<Integer, Front> FRONTS = new ConcurrentHashMap<>();

	protected KetraVarkaFrontManager()
	{
	}

	private static Front front(MapRun run)
	{
		return FRONTS.computeIfAbsent(run.getInstanceId(), k -> new Front());
	}

	/**
	 * @return the Front of the map the player stands in, {@code null} if it is no Front
	 */
	private static MapRun frontRun(Player player)
	{
		final MapRun run = MapDeviceManager.getRun(player.getInstanceId());
		return (run != null) && run.isUnique() && (run.getUnique().type() == UniqueMap.Type.FRONT) && !run.isClosed() ? run : null;
	}

	/**
	 * @param npc an npc
	 * @return its side of the war, by its clan
	 */
	private static Side sideOf(Npc npc)
	{
		return sideOf(npc.getTemplate());
	}

	private static Side sideOf(NpcTemplate template)
	{
		if (template.isClan("KETRA_ORC"))
		{
			return Side.KETRA;
		}
		if (template.isClan("VARKA_SILENOS"))
		{
			return Side.VARKA;
		}
		return Side.NONE;
	}

	private static Npc findNpc(int objectId)
	{
		if (objectId == 0)
		{
			return null;
		}
		final WorldObject object = World.getInstance().findObject(objectId);
		return (object instanceof Npc npc) && !npc.isDead() && npc.isSpawned() ? npc : null;
	}

	// =========================================================================
	// Stats and drops
	// =========================================================================

	/**
	 * @param run the run
	 * @param monster one of its monsters
	 * @param boss {@code true} for the Warlord
	 * @param stat the stat
	 * @return what the Front multiplies it by: the enemy of a sector the players hold is weaker, the Warlord is stronger for each Siege Totem standing and once the Prophet fell
	 */
	static double getStatMultiplier(MapRun run, Monster monster, boolean boss, MonsterStat stat)
	{
		final Front front = FRONTS.get(run.getInstanceId());
		if (front == null)
		{
			return 1.0;
		}
		final boolean attack = (stat == MonsterStat.P_ATK) || (stat == MonsterStat.M_ATK);
		if (boss)
		{
			return attack ? (1 + (front._siegeTotems.size() * SIEGE_TOTEM_ATTACK)) * (front._prophetFallen ? PROPHET_FALLEN_ATTACK : 1) : 1.0;
		}
		if (!attack && (stat != MonsterStat.P_DEF) && (stat != MonsterStat.M_DEF))
		{
			return 1.0;
		}
		final int sector = monster.getVariables().getInt(SECTOR_VAR, 0);
		if ((sector < 1) || (sector > front._totems.size()))
		{
			return 1.0;
		}
		return front._totems.get(sector - 1)._owner == Team.BLUE ? SECTOR_WEAKNESS : 1.0;
	}

	/**
	 * @param run the run
	 * @param boss {@code true} for the Warlord's own drops
	 * @return what the Front multiplies the drop chances by: half again after a betrayal, the Warlord's halved once the Prophet fell
	 */
	static double getDropFactor(MapRun run, boolean boss)
	{
		final Front front = FRONTS.get(run.getInstanceId());
		if (front == null)
		{
			return 1.0;
		}
		return (front._betrayed ? BETRAYAL_DROPS : 1.0) * (boss && front._prophetFallen ? PROPHET_FALLEN_DROPS : 1.0);
	}

	// =========================================================================
	// The field
	// =========================================================================

	/**
	 * Fills the field: the monsters of both sides in three sectors round three neutral war totems, and the War Banner by the Return Portal.
	 * @param run the run, its monster kinds chosen
	 * @return {@code true} (the Front always fills itself)
	 */
	static boolean spawnField(MapRun run)
	{
		final Front front = front(run);
		final MapArena arena = run.getArena();

		// The line from the entry through the middle to the far side of the field.
		final double dx = arena.x() - arena.getEntryX();
		final double dy = arena.y() - arena.getEntryY();
		final double toMiddle = Math.max(1, Math.hypot(dx, dy));
		final double ux = dx / toMiddle;
		final double uy = dy / toMiddle;
		final double radiusAlong = Math.hypot(arena.radiusX() * ux, arena.radiusY() * uy);
		final double length = toMiddle + (radiusAlong * 0.6);
		for (int i = 0; i < TOTEM_SHARES.length; i++)
		{
			final int x = arena.getEntryX() + (int) (ux * length * TOTEM_SHARES[i]);
			final int y = arena.getEntryY() + (int) (uy * length * TOTEM_SHARES[i]);
			front._totems.add(new Totem(i, MapDeviceManager.getGroundLocation(new Location(x, y, arena.z()))));
		}
		front._camp = MapDeviceManager.getGroundLocation(new Location(arena.getEntryX() + (int) (ux * CAMP_DISTANCE), arena.getEntryY() + (int) (uy * CAMP_DISTANCE), arena.z()));

		// The monsters: as many of each side, shared out among the sectors.
		final List<NpcTemplate> ketra = kindsOf(run, Side.KETRA);
		final List<NpcTemplate> varka = kindsOf(run, Side.VARKA);
		final List<NpcTemplate> any = run.getMonsterKinds();
		final int count = Math.max(TOTEM_SHARES.length, (int) Math.round(run.getUnique().monsterCount() * run.getPackSize()));
		final int sectorRadius = Math.max(MIN_SECTOR_RADIUS, Math.min(SECTOR_RADIUS, (int) ((length * (TOTEM_SHARES[1] - TOTEM_SHARES[0])) / 2)));
		final List<Location> taken = new ArrayList<>();
		for (int i = 0; i < count; i++)
		{
			final Totem totem = front._totems.get(i % front._totems.size());
			final List<NpcTemplate> kinds = (i / front._totems.size()) % 2 == 0 ? (!ketra.isEmpty() ? ketra : any) : (!varka.isEmpty() ? varka : any);
			if (kinds.isEmpty())
			{
				break;
			}
			final Location spot = MapDeviceManager.spacedSpot(() -> around(run, totem._spot, sectorRadius, true), taken, MONSTER_SPACING);
			taken.add(spot);
			final Monster monster = MapDeviceManager.getInstance().spawnMapMonster(run, kinds.get(Rnd.get(kinds.size())), spot);
			if (monster != null)
			{
				monster.getVariables().set(SECTOR_VAR, totem._index + 1);
				MapDeviceManager.applyVariants(run, monster, false);
			}
		}
		run.setMonsterTotal(run.getMonsters().size());

		for (Totem totem : front._totems)
		{
			showTotem(run, totem);
		}

		// The War Banner beside the Return Portal, facing the field like it.
		final Location entry = arena.entry();
		final double side = ((entry.getHeading() * 2 * Math.PI) / 65536) + (Math.PI / 2);
		final Location spot = MapDeviceManager.getGroundLocation(new Location(entry.getX() + (int) (Math.cos(side) * BANNER_OFFSET), entry.getY() + (int) (Math.sin(side) * BANNER_OFFSET), entry.getZ(), entry.getHeading()));
		final Npc banner = spawnNpc(run, BANNER, spot, entry.getHeading());
		front._bannerId = banner != null ? banner.getObjectId() : 0;
		return true;
	}

	/**
	 * @return the monster kinds of the map of a side
	 */
	private static List<NpcTemplate> kindsOf(MapRun run, Side side)
	{
		final List<NpcTemplate> kinds = new ArrayList<>();
		for (NpcTemplate template : run.getMonsterKinds())
		{
			if (sideOf(template) == side)
			{
				kinds.add(template);
			}
		}
		return kinds;
	}

	/**
	 * @return a spot on the ground within {@code range} of a spot, inside the field
	 */
	private static Location around(MapRun run, Location center, int range)
	{
		return around(run, center, range, false);
	}

	/**
	 * @param awayFromEntry {@code true} to keep {@link MapDeviceConfig#SAFE_RADIUS} away from where the players come in, too
	 * @return a spot on the ground within {@code range} of a spot, inside the field
	 */
	private static Location around(MapRun run, Location center, int range, boolean awayFromEntry)
	{
		final MapArena arena = run.getArena();
		final GeoEngine geo = GeoEngine.getInstance();
		int x = center.getX();
		int y = center.getY();
		for (int attempt = 0; attempt < 8; attempt++)
		{
			final double angle = Rnd.nextDouble() * 2 * Math.PI;
			final double distance = Math.sqrt(Rnd.nextDouble()) * range;
			x = center.getX() + (int) (Math.cos(angle) * distance);
			y = center.getY() + (int) (Math.sin(angle) * distance);
			final double ex = (x - arena.x()) / (double) arena.radiusX();
			final double ey = (y - arena.y()) / (double) arena.radiusY();
			if ((((ex * ex) + (ey * ey)) <= 0.8) && (!awayFromEntry || (Math.hypot(x - arena.getEntryX(), y - arena.getEntryY()) >= MapDeviceConfig.SAFE_RADIUS)))
			{
				break;
			}
		}
		return geo.getValidLocation(center.getX(), center.getY(), center.getZ(), x, y, geo.getHeight(x, y, center.getZ()), run.getInstanceId());
	}

	private static Npc spawnNpc(MapRun run, int npcId, Location spot, int heading)
	{
		if (NpcData.getInstance().getTemplate(npcId) == null)
		{
			LOGGER.warning(KetraVarkaFrontManager.class.getSimpleName() + ": No npc " + npcId + " (data/stats/npcs/custom/MapDevice.xml): the Front opens without it.");
			return null;
		}
		final Npc npc = Quest.addSpawn(npcId, spot.getX(), spot.getY(), spot.getZ(), heading, false, 0, false, run.getInstanceId());
		if (npc != null)
		{
			npc.setRandomWalking(false);
		}
		return npc;
	}

	/**
	 * Puts up the totem's flag of its owner (Ketra's red, Varka's blue, the neutral purple) and its progress as its title.
	 */
	private static void showTotem(MapRun run, Totem totem)
	{
		final Front front = front(run);
		final Side owner = totem._owner == Team.BLUE ? front._side : totem._owner == Team.RED ? front._side.other() : Side.NONE;
		final int npcId = owner == Side.KETRA ? TOTEM_KETRA : owner == Side.VARKA ? TOTEM_VARKA : TOTEM_NEUTRAL;
		Npc npc = findNpc(totem._npcId);
		if ((npc == null) || (npc.getId() != npcId))
		{
			if (npc != null)
			{
				npc.deleteMe();
			}
			npc = spawnNpc(run, npcId, totem._spot, 0);
			totem._npcId = npc != null ? npc.getObjectId() : 0;
			totem._shownProgress = Integer.MIN_VALUE;
		}
		final int shown = (int) Math.round(totem._progress);
		if ((npc != null) && (Math.abs(shown - totem._shownProgress) >= 5))
		{
			totem._shownProgress = shown;
			npc.setTitle(shown > 0 ? "Yours " + shown + "%" : shown < 0 ? "Enemy " + (-shown) + "%" : "Unclaimed");
			npc.broadcastInfo();
		}
	}

	// =========================================================================
	// The oath and betrayal
	// =========================================================================

	/**
	 * Swears the players of a Front to a side: that side's monsters in the field become its warriors, and its Prophet holds the camp.
	 * @param player who swears
	 * @param sideName {@code ketra} or {@code varka}
	 * @return why not, {@code null} if sworn
	 */
	public String swear(Player player, String sideName)
	{
		final MapRun run = frontRun(player);
		if (run == null)
		{
			return "Swear at the War Banner of the Ketra-Varka Front.";
		}
		final Side side = "ketra".equalsIgnoreCase(sideName) ? Side.KETRA : "varka".equalsIgnoreCase(sideName) ? Side.VARKA : Side.NONE;
		if (side == Side.NONE)
		{
			return "Swear to Ketra or to Varka.";
		}
		final Front front = front(run);
		synchronized (front)
		{
			if (front._side != Side.NONE)
			{
				return "The oath is sworn: you fight for " + front._side.getName() + ".";
			}
			if (front._siege || (run.getStatus() != Status.OPEN))
			{
				return "Too late for oaths: the siege has begun.";
			}
			front._side = side;
		}

		UniqueMapManager.keepAwake(run); // The warriors and the counterattacks fight on where no player is.
		final int joined = convertToSoldiers(run, front, side);
		spawnProphet(run, front);
		for (Totem totem : front._totems)
		{
			showTotem(run, totem);
		}
		MapDeviceManager.sendScreenToMap(run, player.getName() + " swears to " + side.getName() + "! " + joined + " of its warriors join you.");
		MapDeviceManager.sendToMap(run, "Take the 3 war totems: stand on them with your warriors until they are yours. " + side.other().getName() + " in a sector you hold is " + Math.round((1 - SECTOR_WEAKNESS) * 100) + "% weaker. Your Prophet holds the camp by the portal.");
		LOGGER.info(KetraVarkaFrontManager.class.getSimpleName() + ": " + player.getName() + " swore " + run.getOwnerName() + "'s Front (instance " + run.getInstanceId() + ") to " + side.getName() + ".");
		MapDeviceManager.getInstance().checkCompleted(run);
		return null;
	}

	/**
	 * Turns the players' coat: their warriors become monsters of the field again, the other side's monsters their warriors, its Prophet theirs.
	 * @param player who takes the envoy's deal
	 * @return why not, {@code null} if done
	 */
	public String betray(Player player)
	{
		final MapRun run = frontRun(player);
		if (run == null)
		{
			return "There is nobody to betray here.";
		}
		final Front front = front(run);
		final Side old;
		synchronized (front)
		{
			if (front._betrayed)
			{
				return "You have betrayed one side already. Nobody else will have you.";
			}
			if ((front._envoyId == 0) || (System.currentTimeMillis() > front._envoyUntil) || front._siege)
			{
				return "No envoy waits for your answer.";
			}
			old = front._side;
			front._betrayed = true;
			front._side = old.other();
		}
		removeEnvoy(front);

		// Your warriors turn on you.
		final List<NpcTemplate> oldKinds = kindsOf(run, old);
		int turned = 0;
		for (int objectId : List.copyOf(front._soldiers))
		{
			front._soldiers.remove(objectId);
			final Npc soldier = findNpc(objectId);
			if (soldier == null)
			{
				continue;
			}
			final Location spot = new Location(soldier.getX(), soldier.getY(), soldier.getZ());
			soldier.deleteMe();
			if (oldKinds.isEmpty())
			{
				continue;
			}
			final Monster monster = MapDeviceManager.getInstance().spawnMapMonster(run, oldKinds.get(Rnd.get(oldKinds.size())), spot);
			if (monster != null)
			{
				MapDeviceManager.applyVariants(run, monster, false);
				attackNearest(monster, run, front, 1500);
				turned++;
			}
		}
		// And your old enemy's monsters join you.
		final int joined = convertToSoldiers(run, front, front._side);
		run.setMonsterTotal(Math.max(run.getMonsters().size(), run.getMonsterTotal() + turned));

		// The Prophets change places.
		final Npc oldProphet = findNpc(front._prophetId);
		if (oldProphet != null)
		{
			oldProphet.broadcastPacket(new MagicSkillUse(oldProphet, oldProphet, RAISE_SKILL_ID, 1, 0, 0));
			oldProphet.deleteMe();
		}
		front._prophetId = 0;
		spawnProphet(run, front);
		for (Totem totem : front._totems)
		{
			showTotem(run, totem);
		}

		MapDeviceManager.sendScreenToMap(run, player.getName() + " betrays " + old.getName() + "! " + turned + " warriors turn on you; " + joined + " of " + front._side.getName() + " join you.");
		MapDeviceManager.sendToMap(run, "Turncoats: the Front's drops come " + Math.round((BETRAYAL_DROPS - 1) * 100) + "% more often. The totems you hold stay yours, under " + front._side.getName() + "'s flag.");
		LOGGER.info(KetraVarkaFrontManager.class.getSimpleName() + ": " + player.getName() + " betrayed " + old.getName() + " in " + run.getOwnerName() + "'s Front (instance " + run.getInstanceId() + ").");
		MapDeviceManager.getInstance().checkCompleted(run);
		return null;
	}

	/**
	 * Every plain monster of the field of a side becomes one of its warriors, where it stood: it no longer counts toward the clear.
	 * @return how many
	 */
	private static int convertToSoldiers(MapRun run, Front front, Side side)
	{
		int converted = 0;
		for (int objectId : List.copyOf(run.getMonsters()))
		{
			final WorldObject object = World.getInstance().findObject(objectId);
			if (!(object instanceof Monster monster) || monster.isDead() || (UniqueMapManager.role(monster) != 0) || (sideOf(monster) != side) || !run.getMonsters().remove(objectId))
			{
				continue;
			}
			front._counterattackers.remove(objectId);
			final boolean archer = (monster.getTemplate().getAIType() == AIType.ARCHER) || (monster.getTemplate().getAIType() == AIType.MAGE);
			final Location spot = new Location(monster.getX(), monster.getY(), monster.getZ(), monster.getHeading());
			monster.deleteMe();
			if (spawnSoldier(run, front, side, archer, spot) != null)
			{
				converted++;
			}
		}
		run.setMonsterTotal(Math.max(run.getMonsters().size(), run.getMonsterTotal() - converted));
		return converted;
	}

	private static Npc spawnSoldier(MapRun run, Front front, Side side, boolean archer, Location spot)
	{
		final int npcId = side == Side.KETRA ? (archer ? KETRA_ARCHER : KETRA_WARRIOR) : (archer ? VARKA_HUNTER : VARKA_WARRIOR);
		final Npc soldier = spawnNpc(run, npcId, spot, spot.getHeading());
		if (soldier != null)
		{
			front._soldiers.add(soldier.getObjectId());
		}
		return soldier;
	}

	private static void spawnProphet(MapRun run, Front front)
	{
		if ((front._side == Side.NONE) || (front._camp == null) || (findNpc(front._prophetId) != null))
		{
			return;
		}
		final Npc prophet = spawnNpc(run, front._side == Side.KETRA ? KETRA_PROPHET : VARKA_PROPHET, front._camp, run.getArena().entry().getHeading());
		if (prophet != null)
		{
			// Safe until the siege: no stray blow ends the war before it is fought.
			prophet.setInvul(!front._siege);
			front._prophetId = prophet.getObjectId();
			front._prophetFallen = false;
		}
	}

	private static void removeEnvoy(Front front)
	{
		final Npc envoy = findNpc(front._envoyId);
		if (envoy != null)
		{
			envoy.deleteMe();
		}
		front._envoyId = 0;
		front._envoyUntil = 0;
	}

	// =========================================================================
	// The siege
	// =========================================================================

	/**
	 * The field is cleared: the enemy Warlord marches on the camp behind his Siege Totems ({@link UniqueMapManager}'s final encounter).
	 * @param run the run
	 */
	static void startFinale(MapRun run)
	{
		final Front front = front(run);
		front._siege = true;
		removeEnvoy(front);
		final Side enemy = front._side == Side.NONE ? (Rnd.nextBoolean() ? Side.KETRA : Side.VARKA) : front._side.other();
		final NpcTemplate template = NpcData.getInstance().getTemplate(enemy == Side.KETRA ? KETRA_WARLORD : VARKA_WARLORD);
		if (template == null)
		{
			LOGGER.warning(KetraVarkaFrontManager.class.getSimpleName() + ": No Warlord npc for " + enemy.getName() + ".");
			return;
		}
		final Location start = front._totems.isEmpty() ? MapDeviceManager.randomFieldSpot(run, 0.6, true) : front._totems.get(front._totems.size() - 1)._spot;
		final Monster warlord = UniqueMapManager.spawnBoss(run, template, start, "[" + enemy.getName() + " " + run.getUnique().bossName() + "]");
		if (warlord == null)
		{
			return;
		}

		final NpcTemplate totemTemplate = NpcData.getInstance().getTemplate(SIEGE_TOTEM_NPC);
		if (totemTemplate != null)
		{
			for (int i = 0; i < SIEGE_TOTEMS; i++)
			{
				final Monster totem = UniqueMapManager.spawnEncounter(run, totemTemplate, around(run, start, 500), UniqueMapManager.ROLE_SIEGE_TOTEM, "[Siege Totem]", true);
				if (totem != null)
				{
					totem.setImmobilized(true);
					totem.setRandomWalking(false);
					front._siegeTotems.add(totem.getObjectId());
				}
			}
			run.setMonsterTotal(run.getMonsterTotal() + front._siegeTotems.size());
		}

		final Npc prophet = findNpc(front._prophetId);
		if (prophet != null)
		{
			prophet.setInvul(false);
			march(warlord, prophet);
			MapDeviceManager.sendToMap(run, "The " + enemy.getName() + " " + run.getUnique().bossName() + " marches on your Prophet with " + front._siegeTotems.size() + " Siege Totems behind him: each one standing makes him " + Math.round(SIEGE_TOTEM_ATTACK * 100) + "% stronger. Hold the camp!");
		}
		else
		{
			UniqueMapManager.attack(warlord, MapDeviceManager.getPlayersInside(run));
			MapDeviceManager.sendToMap(run, "The " + enemy.getName() + " " + run.getUnique().bossName() + " comes for you with " + front._siegeTotems.size() + " Siege Totems behind him: each one standing makes him " + Math.round(SIEGE_TOTEM_ATTACK * 100) + "% stronger.");
		}
	}

	/**
	 * The Warlord's HP passed 75%, 50% or 25%: he calls his escorts.
	 */
	static void onBossThreshold(MapRun run, Monster boss, int index)
	{
		final Front front = front(run);
		final Side enemy = sideOf(boss);
		List<NpcTemplate> kinds = kindsOf(run, enemy);
		if (kinds.isEmpty())
		{
			kinds = run.getMonsterKinds();
		}
		if (kinds.isEmpty())
		{
			return;
		}
		boss.broadcastPacket(new MagicSkillUse(boss, boss, RAISE_SKILL_ID, 1, 0, 0));
		int called = 0;
		for (int i = 0; i < ESCORTS; i++)
		{
			final Monster escort = UniqueMapManager.spawnEncounter(run, kinds.get(Rnd.get(kinds.size())), MapDeviceManager.nearSpot(run, boss, 500), UniqueMapManager.ROLE_ESCORT, "[Escort]", true);
			if (escort != null)
			{
				MapDeviceManager.applyVariants(run, escort, false);
				attackNearest(escort, run, front, 2000);
				called++;
			}
		}
		run.setMonsterTotal(run.getMonsterTotal() + called);
		MapDeviceManager.sendScreenToMap(run, "The " + run.getUnique().bossName() + " calls his escorts!");
	}

	/**
	 * The Warlord fell: everyone inside is named a Hero of their side for {@value #TITLE_DAYS} days.
	 */
	static void onBossDied(MapRun run, Monster boss)
	{
		final Front front = front(run);
		if (front._side == Side.NONE)
		{
			MapDeviceManager.sendToMap(run, "The war is over, and you fought for nobody: no side names you its Hero.");
			return;
		}
		final String title = front._betrayed ? "the Twice-Sworn" : "Hero of " + front._side.getName();
		final long end = System.currentTimeMillis() + (TITLE_DAYS * 24 * 60 * 60 * 1000L);
		for (Player player : MapDeviceManager.getPlayersInside(run))
		{
			player.getVariables().set(TITLE_VAR, title);
			player.getVariables().set(TITLE_END_VAR, end);
			player.broadcastUserInfo();
			player.sendMessage("You are named " + title + " for " + TITLE_DAYS + " days (shown while your own title is empty).");
		}
		final Npc prophet = findNpc(front._prophetId);
		if (prophet != null)
		{
			prophet.broadcastPacket(new MagicSkillUse(prophet, prophet, RAISE_SKILL_ID, 1, 0, 0));
		}
		MapDeviceManager.sendScreenToMap(run, front._prophetFallen ? "The camp is lost, but the Warlord is dead. " + front._side.getName() + " will remember you." : front._side.getName() + " is victorious! All hail its Heroes.");
	}

	/**
	 * A monster of the Front died.
	 */
	static void onMonsterDied(MapRun run, Monster victim, int role)
	{
		final Front front = FRONTS.get(run.getInstanceId());
		if (front == null)
		{
			return;
		}
		front._counterattackers.remove(victim.getObjectId());
		if ((role == UniqueMapManager.ROLE_SIEGE_TOTEM) && front._siegeTotems.remove(victim.getObjectId()))
		{
			MapDeviceManager.sendScreenToMap(run, front._siegeTotems.isEmpty() ? "The last Siege Totem falls: the Warlord stands alone!" : "A Siege Totem falls: " + front._siegeTotems.size() + " left.");
		}
	}

	// =========================================================================
	// The clock
	// =========================================================================

	/**
	 * Once a second ({@link UniqueMapManager#onTick}).
	 * @param run the run
	 * @param tick seconds since it opened
	 * @param now the time (ms)
	 * @param phase 0 while the field is cleared, 1 once the siege is called
	 */
	static void onTick(MapRun run, int tick, long now, int phase)
	{
		final Front front = FRONTS.get(run.getInstanceId());
		if (front == null)
		{
			return;
		}

		// The Prophet.
		if ((front._side != Side.NONE) && (front._prophetId != 0) && !front._prophetFallen && (findNpc(front._prophetId) == null))
		{
			front._prophetFallen = true;
			front._prophetId = 0;
			MapDeviceManager.sendScreenToMap(run, "Your Prophet has fallen! The " + run.getUnique().bossName() + " grows stronger, and his spoils smaller.");
		}

		if (front._side == Side.NONE)
		{
			return; // Nobody swore: no warriors, no totems, no betrayal.
		}

		front._soldiers.removeIf(objectId -> findNpc(objectId) == null);

		if (phase == 0)
		{
			captureTotems(run, front, now);
			if ((tick % REINFORCE_INTERVAL) == 0)
			{
				reinforce(run, front);
			}
			if ((front._nextCounter > 0) && (now >= front._nextCounter) && !run.getMonsters().isEmpty())
			{
				counterattack(run, front, now);
			}
			if ((tick % 2) == 0)
			{
				driveCounterattackers(run, front);
			}
			offerBetrayal(run, front, now);
		}
		else if ((tick % WARLORD_THINK) == 0)
		{
			driveWarlord(run, front);
		}

		if ((tick % SOLDIER_THINK) == 0)
		{
			driveSoldiers(run, front, phase);
		}
	}

	private static void captureTotems(MapRun run, Front front, long now)
	{
		final List<Player> players = MapDeviceManager.getPlayersInside(run);
		for (Totem totem : front._totems)
		{
			int friends = 0;
			for (Player player : players)
			{
				if (!player.isDead() && (player.calculateDistance2D(totem._spot) <= TOTEM_RADIUS))
				{
					friends++;
				}
			}
			for (int objectId : front._soldiers)
			{
				final Npc soldier = findNpc(objectId);
				if ((soldier != null) && (soldier.calculateDistance2D(totem._spot) <= TOTEM_RADIUS))
				{
					friends++;
				}
			}
			int enemies = 0;
			for (int objectId : run.getMonsters())
			{
				final Npc monster = findNpc(objectId);
				if ((monster != null) && (monster.calculateDistance2D(totem._spot) <= TOTEM_RADIUS))
				{
					enemies++;
				}
			}

			final Team before = totem._owner;
			totem._progress = ConquestCapture.step(totem._progress, friends, enemies, CAPTURE_RATE, CAPTURE_PER_EXTRA, CAPTURE_MAX, CAPTURE_MAJORITY, CAPTURE_STALEMATE);
			totem._owner = ConquestCapture.ownerAt(totem._progress, before);
			if (totem._owner != before)
			{
				final String name = TOTEM_NAMES[totem._index];
				if (totem._owner == Team.BLUE)
				{
					MapDeviceManager.sendScreenToMap(run, front._side.getName() + " takes " + name + "! (" + owned(front) + " of " + front._totems.size() + ")");
					if (front._nextCounter == 0)
					{
						front._nextCounter = now + (COUNTER_INTERVAL * 1000L);
						MapDeviceManager.sendToMap(run, front._side.other().getName() + " will not let it go: expect a counterattack on your totems every " + (COUNTER_INTERVAL / 60) + " minutes.");
					}
				}
				else if (before == Team.BLUE)
				{
					MapDeviceManager.sendScreenToMap(run, "You lose " + name + "!");
				}
				else if (totem._owner == Team.RED)
				{
					MapDeviceManager.sendToMap(run, front._side.other().getName() + " holds " + name + ".");
				}
			}
			showTotem(run, totem);
		}
	}

	private static int owned(Front front)
	{
		int owned = 0;
		for (Totem totem : front._totems)
		{
			if (totem._owner == Team.BLUE)
			{
				owned++;
			}
		}
		return owned;
	}

	/**
	 * Every totem the players hold sends a few more warriors to the front.
	 */
	private static void reinforce(MapRun run, Front front)
	{
		int added = 0;
		for (Totem totem : front._totems)
		{
			if (totem._owner != Team.BLUE)
			{
				continue;
			}
			for (int i = 0; (i < REINFORCE_PER_TOTEM) && (front._soldiers.size() < SOLDIER_MAX); i++)
			{
				if (spawnSoldier(run, front, front._side, Rnd.nextBoolean(), around(run, totem._spot, 300)) != null)
				{
					added++;
				}
			}
		}
		if (added > 0)
		{
			MapDeviceManager.sendToMap(run, added + " warriors of " + front._side.getName() + " join the front at your totems.");
		}
	}

	/**
	 * The enemy marches on a totem the players hold.
	 */
	private static void counterattack(MapRun run, Front front, long now)
	{
		front._nextCounter = front._counters + 1 < COUNTER_MAX ? now + (COUNTER_INTERVAL * 1000L) : -1;
		final List<Totem> held = new ArrayList<>();
		for (Totem totem : front._totems)
		{
			if (totem._owner == Team.BLUE)
			{
				held.add(totem);
			}
		}
		final List<NpcTemplate> kinds = kindsOf(run, front._side.other());
		if (held.isEmpty() || kinds.isEmpty())
		{
			return;
		}
		front._counters++;
		final Totem target = held.get(Rnd.get(held.size()));
		final double angle = Rnd.nextDouble() * 2 * Math.PI;
		final Location from = around(run, new Location(target._spot.getX() + (int) (Math.cos(angle) * COUNTER_DISTANCE), target._spot.getY() + (int) (Math.sin(angle) * COUNTER_DISTANCE), target._spot.getZ()), 200);
		int sent = 0;
		for (int i = 0; i < COUNTER_SIZE; i++)
		{
			final Monster monster = MapDeviceManager.getInstance().spawnMapMonster(run, kinds.get(Rnd.get(kinds.size())), around(run, from, 250));
			if (monster != null)
			{
				MapDeviceManager.applyVariants(run, monster, false);
				front._counterattackers.put(monster.getObjectId(), target._index);
				march(monster, target._spot);
				sent++;
			}
		}
		run.setMonsterTotal(run.getMonsterTotal() + sent);
		MapDeviceManager.sendScreenToMap(run, "Counterattack! " + sent + " of " + front._side.other().getName() + " march on " + TOTEM_NAMES[target._index] + "!");
	}

	/**
	 * The counterattackers march on their totem, and fight whatever holds it once they are there.
	 */
	private static void driveCounterattackers(MapRun run, Front front)
	{
		for (Map.Entry<Integer, Integer> entry : front._counterattackers.entrySet())
		{
			final WorldObject object = World.getInstance().findObject(entry.getKey());
			if (!(object instanceof Monster monster) || monster.isDead())
			{
				front._counterattackers.remove(entry.getKey());
				continue;
			}
			if (monster.isAttackingNow() || (monster.getAI().getIntention() == Intention.ATTACK))
			{
				continue;
			}
			final Totem totem = front._totems.get(entry.getValue());
			if (monster.calculateDistance2D(totem._spot) <= (TOTEM_RADIUS / 2))
			{
				attackNearest(monster, run, front, TOTEM_RADIUS * 2);
			}
			else if (!monster.isMoving())
			{
				march(monster, totem._spot);
			}
		}
	}

	/**
	 * Holding 2 totems brings the enemy's envoy with an offer, once a run.
	 */
	private static void offerBetrayal(MapRun run, Front front, long now)
	{
		if ((front._envoyId != 0) && (now > front._envoyUntil))
		{
			removeEnvoy(front);
			MapDeviceManager.sendToMap(run, "The envoy of " + front._side.other().getName() + " gives up on you and slips away.");
			return;
		}
		if (front._envoyCame || front._betrayed || (owned(front) < 2))
		{
			return;
		}
		front._envoyCame = true;
		final Npc banner = findNpc(front._bannerId);
		final Location spot = banner != null ? MapDeviceManager.nearSpot(run, banner, 200) : front._camp;
		final Npc envoy = spawnNpc(run, front._side == Side.KETRA ? VARKA_ENVOY : KETRA_ENVOY, spot, run.getArena().entry().getHeading());
		if (envoy == null)
		{
			return;
		}
		front._envoyId = envoy.getObjectId();
		front._envoyUntil = now + (ENVOY_SECONDS * 1000L);
		MapDeviceManager.sendScreenToMap(run, "An envoy of " + front._side.other().getName() + " waits by the portal with an offer...");
		for (Player player : MapDeviceManager.getPlayersInside(run))
		{
			showWindow(player);
		}
	}

	/**
	 * The Warlord goes for the Prophet whenever nothing else holds him.
	 */
	private static void driveWarlord(MapRun run, Front front)
	{
		final Monster warlord = UniqueMapManager.getBoss(run);
		final Npc prophet = findNpc(front._prophetId);
		if ((warlord == null) || (prophet == null) || warlord.isAttackingNow())
		{
			return;
		}
		final WorldObject target = warlord.getTarget();
		if ((warlord.getAI().getIntention() == Intention.ATTACK) && (target instanceof Creature creature) && !creature.isDead() && (warlord.calculateDistance2D(creature) < 1500))
		{
			return;
		}
		march(warlord, prophet);
	}

	/**
	 * The warriors fight the nearest enemy, else make for the nearest totem the players don't hold (in the siege: the Warlord's war).
	 */
	private static void driveSoldiers(MapRun run, Front front, int phase)
	{
		final Monster warlord = phase == 0 ? null : UniqueMapManager.getBoss(run);
		for (int objectId : front._soldiers)
		{
			final Npc soldier = findNpc(objectId);
			if ((soldier == null) || !soldier.hasAI() || soldier.isAttackingNow())
			{
				continue;
			}
			final WorldObject current = soldier.getTarget();
			if ((soldier.getAI().getIntention() == Intention.ATTACK) && (current instanceof Monster target) && !target.isDead())
			{
				continue;
			}
			final Monster foe = nearestFoe(run, soldier, SOLDIER_RANGE);
			if (foe != null)
			{
				if (soldier instanceof QuestGuard guard)
				{
					guard.addDamageHate(foe, 0, 999);
				}
				soldier.getAI().setIntention(Intention.ATTACK, foe);
				continue;
			}
			if (soldier.isMoving())
			{
				continue;
			}
			Location goal = null;
			if (warlord != null)
			{
				goal = new Location(warlord.getX(), warlord.getY(), warlord.getZ());
			}
			else
			{
				double best = Double.MAX_VALUE;
				for (Totem totem : front._totems)
				{
					final double distance = soldier.calculateDistance2D(totem._spot);
					if ((totem._owner != Team.BLUE) && (distance < best))
					{
						best = distance;
						goal = totem._spot;
					}
				}
			}
			if ((goal != null) && (soldier.calculateDistance2D(goal) > 250))
			{
				soldier.getAI().setIntention(Intention.MOVE_TO, around(run, goal, 200));
			}
		}
	}

	/**
	 * @return the nearest living monster within range that the players' side fights: any counted monster of the map, and the siege's
	 */
	private static Monster nearestFoe(MapRun run, Npc soldier, int range)
	{
		Monster best = null;
		double bestDistance = range;
		for (Monster monster : World.getInstance().getVisibleObjectsInRange(soldier, Monster.class, range))
		{
			if (monster.isDead() || monster.isFakePlayer() || (monster.getInstanceId() != run.getInstanceId()))
			{
				continue;
			}
			if (!run.getMonsters().contains(monster.getObjectId()) && (UniqueMapManager.role(monster) == 0))
			{
				continue;
			}
			final double distance = soldier.calculateDistance2D(monster);
			if (distance < bestDistance)
			{
				bestDistance = distance;
				best = monster;
			}
		}
		return best;
	}

	/**
	 * Sends a monster at the nearest player or warrior of the players' side (or their Prophet) within range.
	 */
	private static void attackNearest(Monster monster, MapRun run, Front front, int range)
	{
		Creature best = null;
		double bestDistance = range;
		for (Player player : MapDeviceManager.getPlayersInside(run))
		{
			final double distance = monster.calculateDistance2D(player);
			if (!player.isDead() && (distance < bestDistance))
			{
				bestDistance = distance;
				best = player;
			}
		}
		for (int objectId : front._soldiers)
		{
			final Npc soldier = findNpc(objectId);
			if (soldier == null)
			{
				continue;
			}
			final double distance = monster.calculateDistance2D(soldier);
			if (distance < bestDistance)
			{
				bestDistance = distance;
				best = soldier;
			}
		}
		if (best != null)
		{
			monster.addDamageHate(best, 0, 500);
			monster.getAI().setIntention(Intention.ATTACK, best);
		}
	}

	private static void march(Monster monster, Npc target)
	{
		monster.addDamageHate(target, 0, 500);
		monster.getAI().setIntention(Intention.ATTACK, target);
	}

	private static void march(Monster monster, Location spot)
	{
		monster.getAI().setIntention(Intention.MOVE_TO, spot);
	}

	// =========================================================================
	// Players
	// =========================================================================

	/**
	 * A player came into the Front: the oath window, the first time, while nobody swore.
	 */
	static void onPlayerEntered(Player player, MapRun run)
	{
		final Front front = front(run);
		if (front._side == Side.NONE)
		{
			if (front._greeted.add(player.getObjectId()) && !front._siege)
			{
				showWindow(player);
			}
			player.sendMessage(run.getUnique().name() + ": swear to Ketra or Varka at the War Banner by the portal (or ." + COMMAND + ").");
		}
		else
		{
			player.sendMessage(run.getUnique().name() + ": you fight for " + front._side.getName() + (front._betrayed ? ", turncoats" : "") + ". " + owned(front) + " of " + front._totems.size() + " war totems are yours. ." + COMMAND + " for the front.");
		}
	}

	/**
	 * The Front closed: its state is gone (its npcs go with the instance).
	 */
	static void onRunClosed(MapRun run)
	{
		FRONTS.remove(run.getInstanceId());
	}

	/**
	 * Shows a player's Front title over an empty title of their own, while it lasts (the title-carrying packets, through {@code LuckyLootManager.decorateTitle}). The saved title never changes.
	 * @param player a player
	 * @param baseTitle their title
	 * @return the title to show
	 */
	public static String decorateTitle(Player player, String baseTitle)
	{
		if ((player == null) || ((baseTitle != null) && !baseTitle.isEmpty()))
		{
			return baseTitle;
		}
		final String title = player.getVariables().getString(TITLE_VAR, "");
		if (title.isEmpty() || (player.getVariables().getLong(TITLE_END_VAR, 0) < System.currentTimeMillis()))
		{
			return baseTitle;
		}
		return title;
	}

	// =========================================================================
	// The window (.front, the War Banner, the envoy, the totems)
	// =========================================================================

	/**
	 * The Front's window: the oath, the totems, the envoy's offer; outside the Front, the player's title.
	 * @param player the player
	 */
	public static void showWindow(Player player)
	{
		final StringBuilder sb = new StringBuilder(2048);
		sb.append("<html><body><center><font name=\"hs12\" color=\"FF9955\">The Ketra-Varka Front</font></center><br>");
		final MapRun run = frontRun(player);
		if (run == null)
		{
			sb.append("<font color=\"A0A0A0\">Pick a side. Then maybe pick the other one. Open a Unique Map: The Ketra-Varka Front at your Map Device.</font><br><br>");
			final String title = player.getVariables().getString(TITLE_VAR, "");
			final long end = player.getVariables().getLong(TITLE_END_VAR, 0);
			if (!title.isEmpty() && (end > System.currentTimeMillis()))
			{
				sb.append("Your title: <font color=\"E6C35C\">").append(title).append("</font>, until ").append(new SimpleDateFormat("yyyy-MM-dd HH:mm").format(new Date(end))).append(".<br>");
				sb.append("<font color=\"707070\">It shows while your own title is empty.</font>");
			}
			sb.append("</body></html>");
			send(player, sb);
			return;
		}

		final Front front = front(run);
		final Side side = front._side;
		if (side == Side.NONE)
		{
			if (front._siege)
			{
				sb.append("<font color=\"A0A0A0\">Nobody swore, and now the siege has come. Fight for yourselves.</font>");
			}
			else
			{
				sb.append("\"Two peoples, one war, three totems. Whose banner do you raise?\"<br><br>");
				sb.append("<font color=\"A0A0A0\">The first oath binds everyone inside. That side's monsters in the field become your warriors, its Prophet holds your camp. The other side is your enemy: its monsters count toward the clear.</font><br><br>");
				button(sb, "Swear to Ketra", "swear ketra");
				button(sb, "Swear to Varka", "swear varka");
			}
			sb.append("</body></html>");
			send(player, sb);
			return;
		}

		sb.append("<table width=270>");
		row(sb, "Your side", "<font color=\"" + (side == Side.KETRA ? "FF6666" : "6699FF") + "\">" + side.getName() + "</font>" + (front._betrayed ? " <font color=\"A0A0A0\">(turncoats)</font>" : ""));
		row(sb, "Warriors", String.valueOf(front._soldiers.size()));
		final Npc prophet = findNpc(front._prophetId);
		row(sb, "Prophet", prophet != null ? Math.round((prophet.getCurrentHp() * 100) / prophet.getMaxHp()) + "% HP" + (prophet.isInvul() ? " (safe until the siege)" : "") : "<font color=\"FF6666\">fallen</font>");
		for (Totem totem : front._totems)
		{
			final String owner = totem._owner == Team.BLUE ? "<font color=\"66CC66\">yours</font>" : totem._owner == Team.RED ? "<font color=\"FF6666\">enemy</font>" : "unclaimed";
			row(sb, Character.toUpperCase(TOTEM_NAMES[totem._index].charAt(4)) + TOTEM_NAMES[totem._index].substring(5), owner + " (" + Math.round(Math.abs(totem._progress)) + "%)");
		}
		if (!front._siege)
		{
			row(sb, "Counterattacks", front._counters + " of " + COUNTER_MAX + (front._nextCounter > 0 ? ", next in " + Math.max(0, (front._nextCounter - System.currentTimeMillis()) / 1000) + "s" : ""));
		}
		else
		{
			row(sb, "Siege Totems", String.valueOf(front._siegeTotems.size()));
		}
		row(sb, "Drops", front._betrayed ? "<font color=\"66CC66\">x" + BETRAYAL_DROPS + "</font> (betrayal)" : "x1");
		sb.append("</table><br>");

		if ((front._envoyId != 0) && !front._betrayed && (System.currentTimeMillis() <= front._envoyUntil))
		{
			sb.append("<font color=\"FF9955\">The envoy of ").append(side.other().getName()).append(":</font> \"You hold two totems. Imagine what you could hold with us. Turn on ").append(side.getName()).append(", now, and the spoils of this war are half again what they would be.\"<br>");
			sb.append("<font color=\"707070\">Your warriors turn on you, the enemy's monsters join you, and its Prophet takes the camp. ").append((front._envoyUntil - System.currentTimeMillis()) / 1000).append("s to answer.</font><br><br>");
			button(sb, "Betray " + side.getName(), "betray");
		}
		else
		{
			sb.append("<font color=\"707070\">Stand on a totem with your warriors to take it. Your warriors gather at the totems you hold; the enemy counterattacks them every ").append(COUNTER_INTERVAL / 60).append(" minutes.</font>");
		}
		sb.append("</body></html>");
		send(player, sb);
	}

	private static void row(StringBuilder sb, String label, String value)
	{
		sb.append("<tr><td><font color=\"A0A0A0\">").append(label).append("</font></td><td align=right>").append(value).append("</td></tr>");
	}

	private static void button(StringBuilder sb, String label, String params)
	{
		sb.append("<center><button value=\"").append(label).append("\" action=\"bypass voiced_").append(COMMAND).append(' ').append(params).append("\" width=200 height=25 back=\"L2UI_CT1.Button_DF_Down\" fore=\"L2UI_CT1.Button_DF\"></center>");
	}

	private static void send(Player player, StringBuilder sb)
	{
		final NpcHtmlMessage html = new NpcHtmlMessage(0);
		html.setHtml(sb.toString());
		player.sendPacket(html);
	}

	public static KetraVarkaFrontManager getInstance()
	{
		return SingletonHolder.INSTANCE;
	}

	private static class SingletonHolder
	{
		protected static final KetraVarkaFrontManager INSTANCE = new KetraVarkaFrontManager();
	}
}
