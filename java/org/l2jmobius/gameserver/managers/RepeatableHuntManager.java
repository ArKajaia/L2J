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

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

import org.l2jmobius.commons.threads.ThreadPool;
import org.l2jmobius.commons.util.Rnd;
import org.l2jmobius.gameserver.config.RatesConfig;
import org.l2jmobius.gameserver.data.SpawnTable;
import org.l2jmobius.gameserver.data.xml.RepeatableHuntData;
import org.l2jmobius.gameserver.geoengine.GeoEngine;
import org.l2jmobius.gameserver.model.Location;
import org.l2jmobius.gameserver.model.World;
import org.l2jmobius.gameserver.model.actor.Attackable;
import org.l2jmobius.gameserver.model.actor.Npc;
import org.l2jmobius.gameserver.model.actor.Player;
import org.l2jmobius.gameserver.model.events.Containers;
import org.l2jmobius.gameserver.model.events.EventType;
import org.l2jmobius.gameserver.model.events.holders.OnServerStart;
import org.l2jmobius.gameserver.model.events.holders.actor.npc.attackable.OnAttackableKill;
import org.l2jmobius.gameserver.model.events.listeners.ConsumerEventListener;
import org.l2jmobius.gameserver.model.groups.Party;
import org.l2jmobius.gameserver.model.item.enums.ItemProcessType;
import org.l2jmobius.gameserver.model.repeatablehunt.RepeatableHunt;
import org.l2jmobius.gameserver.model.script.Quest;
import org.l2jmobius.gameserver.model.script.QuestSound;
import org.l2jmobius.gameserver.model.spawns.Spawn;
import org.l2jmobius.gameserver.model.zone.ZoneId;
import org.l2jmobius.gameserver.util.LocationUtil;
import org.l2jmobius.gameserver.util.QuestSpawnUtil;

/**
 * Repeatable hunts (data/RepeatableHunts.xml): a giver in a town pays, as often as the player comes back, for every kill of a region's monsters or every quest item they dropped.
 * <ul>
 * <li>The givers stand next to their town's gatekeeper. A hunt is taken at its giver, or from a courier: a player who keeps killing a hunt's monsters without it gets a courier with the
 * offer, once out of the fight.</li>
 * <li>Kills count for every party member near the kill who has the hunt; a dropped item goes to one of them. Players more than {@code countLevelGap} levels above the hunt's range count
 * nothing.</li>
 * <li>Reporting pays adena per unit, plus a bonus for a big report, times the server's quest adena rate, and the hunt goes on.</li>
 * </ul>
 * Progress is kept in player variables (kills) and the inventory (items).
 * @author Mobius
 */
public class RepeatableHuntManager
{
	private static final Logger LOGGER = Logger.getLogger(RepeatableHuntManager.class.getName());

	private static final String BYPASS = "bypass -h Script RepeatableHunts ";
	private static final String VAR_ACTIVE = "RH_";
	private static final String VAR_KILLS = "RH_K_";
	private static final int PARTY_RANGE = 1500;
	private static final int TALK_RANGE = 300;
	private static final int GIVER_DISTANCE = 260;

	private static class Courier
	{
		final Npc _npc;
		final RepeatableHunt _hunt;
		final long _expires;

		Courier(Npc npc, RepeatableHunt hunt, long expires)
		{
			_npc = npc;
			_hunt = hunt;
			_expires = expires;
		}
	}

	private final Map<Integer, RepeatableHunt> _givers = new ConcurrentHashMap<>();
	private final Map<Integer, Map<Integer, Integer>> _unaskedKills = new ConcurrentHashMap<>();
	private final Map<String, Long> _cooldowns = new ConcurrentHashMap<>();
	private final Map<Integer, Integer> _pendingCouriers = new ConcurrentHashMap<>();
	private final Map<Integer, Courier> _couriers = new ConcurrentHashMap<>();

	protected RepeatableHuntManager()
	{
		if (!RepeatableHuntData.getInstance().isEnabled() || RepeatableHuntData.getInstance().getHunts().isEmpty())
		{
			return;
		}
		Containers.Monsters().addListener(new ConsumerEventListener(Containers.Monsters(), EventType.ON_ATTACKABLE_KILL, (OnAttackableKill event) -> onKill(event), this));
		Containers.Global().addListener(new ConsumerEventListener(Containers.Global(), EventType.ON_SERVER_START, (OnServerStart event) -> ThreadPool.schedule(this::spawnGivers, 6000), this));
		ThreadPool.scheduleAtFixedRate(this::tick, 5000, 5000);
	}

	// ---------------------------------------------------------------------------------------------
	// Givers
	// ---------------------------------------------------------------------------------------------

	private void spawnGivers()
	{
		final Map<Integer, List<RepeatableHunt>> byAnchor = new LinkedHashMap<>();
		for (RepeatableHunt hunt : RepeatableHuntData.getInstance().getHunts())
		{
			byAnchor.computeIfAbsent(hunt.getAnchorNpcId(), key -> new ArrayList<>()).add(hunt);
		}
		for (Map.Entry<Integer, List<RepeatableHunt>> entry : byAnchor.entrySet())
		{
			final Spawn spawn = SpawnTable.getInstance().getAnySpawn(entry.getKey());
			if (spawn == null)
			{
				LOGGER.warning(getClass().getSimpleName() + ": No spawn of npc " + entry.getKey() + " for the hunt givers of " + entry.getValue().get(0).getTown() + ".");
				continue;
			}
			final Location anchor = spawn.getLastSpawn() != null ? new Location(spawn.getLastSpawn().getX(), spawn.getLastSpawn().getY(), spawn.getLastSpawn().getZ()) : new Location(spawn.getX(), spawn.getY(), spawn.getZ());
			final List<RepeatableHunt> hunts = entry.getValue();
			for (int i = 0; i < hunts.size(); i++)
			{
				// Around the gatekeeper, a little further out than the Notice Board, spread so the givers don't stand on each other.
				final double angle = 0.6 + ((i * 2 * Math.PI) / Math.max(4, hunts.size()));
				final int x = anchor.getX() + (int) (Math.cos(angle) * GIVER_DISTANCE);
				final int y = anchor.getY() + (int) (Math.sin(angle) * GIVER_DISTANCE);
				final int z = GeoEngine.getInstance().getHeight(x, y, anchor.getZ());
				Location location = new Location(x, y, z);
				if ((Math.abs(z - anchor.getZ()) > 200) || !GeoEngine.getInstance().canMoveToTarget(anchor.getX(), anchor.getY(), anchor.getZ(), x, y, z, 0))
				{
					location = QuestSpawnUtil.findPoint(anchor, 200, 320, true, 0);
				}
				final Npc npc = Quest.addSpawn(RepeatableHuntData.getInstance().getGiverNpcId(), location.getX(), location.getY(), location.getZ(), LocationUtil.calculateHeadingFrom(location, anchor), false, 0, false, 0);
				if (npc != null)
				{
					npc.setRandomWalking(false);
					npc.setName(hunts.get(i).getGiverName());
					npc.setTitle(hunts.get(i).getGiverTitle());
					npc.broadcastInfo();
					_givers.put(npc.getObjectId(), hunts.get(i));
				}
			}
		}
		LOGGER.info(getClass().getSimpleName() + ": " + _givers.size() + " hunt givers placed.");
	}

	// ---------------------------------------------------------------------------------------------
	// Progress
	// ---------------------------------------------------------------------------------------------

	public boolean isActive(Player player, RepeatableHunt hunt)
	{
		return player.getVariables().getBoolean(VAR_ACTIVE + hunt.getId(), false);
	}

	/**
	 * @param player a player
	 * @param hunt a hunt
	 * @return the kills or items the player can report
	 */
	public long getCount(Player player, RepeatableHunt hunt)
	{
		if (hunt.getType() == RepeatableHunt.Type.KILL)
		{
			return player.getVariables().getInt(VAR_KILLS + hunt.getId(), 0);
		}
		return player.getInventory().getInventoryItemCount(hunt.getItemId(), -1);
	}

	/**
	 * @param hunt a hunt
	 * @param count the units reported
	 * @return the adena paid for them, the server's quest adena rate included
	 */
	public static long getPayout(RepeatableHunt hunt, long count)
	{
		if (count <= 0)
		{
			return 0;
		}
		final long base = (count * hunt.getPerUnit()) + (((hunt.getBonusMin() > 0) && (count >= hunt.getBonusMin())) ? hunt.getBonus() : 0);
		return (long) (base * RatesConfig.RATE_QUEST_REWARD_ADENA);
	}

	private static boolean canCount(Player player, RepeatableHunt hunt)
	{
		return player.getLevel() <= (hunt.getMaxLevel() + RepeatableHuntData.getInstance().getCountLevelGap());
	}

	private static boolean canTake(Player player, RepeatableHunt hunt)
	{
		return (player.getLevel() >= hunt.getMinLevel()) && (player.getLevel() <= hunt.getMaxLevel());
	}

	private void onKill(OnAttackableKill event)
	{
		final Player killer = event.getAttacker();
		final Attackable monster = event.getTarget();
		if ((killer == null) || (monster == null) || killer.isFakePlayer())
		{
			return;
		}
		final List<RepeatableHunt> hunts = RepeatableHuntData.getInstance().getHuntsOf(monster.getId());
		if (hunts.isEmpty())
		{
			return;
		}

		final List<Player> near = new ArrayList<>();
		final Party party = killer.getParty();
		if (party != null)
		{
			for (Player member : party.getMembers())
			{
				if (!member.isDead() && !member.isFakePlayer() && (member.getInstanceId() == monster.getInstanceId()) && (member.calculateDistance2D(monster) <= PARTY_RANGE))
				{
					near.add(member);
				}
			}
		}
		else
		{
			near.add(killer);
		}

		for (RepeatableHunt hunt : hunts)
		{
			final List<Player> hunters = new ArrayList<>();
			for (Player player : near)
			{
				if (isActive(player, hunt) && canCount(player, hunt))
				{
					hunters.add(player);
				}
			}
			if (hunters.isEmpty())
			{
				if (!isActive(killer, hunt))
				{
					countUnasked(killer, hunt);
				}
				continue;
			}

			if (hunt.getType() == RepeatableHunt.Type.KILL)
			{
				for (Player hunter : hunters)
				{
					final int kills = hunter.getVariables().getInt(VAR_KILLS + hunt.getId(), 0) + 1;
					hunter.getVariables().set(VAR_KILLS + hunt.getId(), kills);
					if ((kills % 10) == 0)
					{
						QuestSpawnUtil.screen(hunter, hunt.getName() + ": " + kills + " kills");
						hunter.sendPacket(QuestSound.ITEMSOUND_QUEST_MIDDLE.getPacket());
					}
				}
			}
			else if (Rnd.get(100.0) < hunt.getMonsters().getOrDefault(monster.getId(), 0d))
			{
				final Player hunter = hunters.get(Rnd.get(hunters.size()));
				final long amount = Math.max(1, Math.round(RatesConfig.QUEST_ITEM_DROP_AMOUNT_MULTIPLIER));
				hunter.addItem(ItemProcessType.QUEST, hunt.getItemId(), amount, monster, true);
				hunter.sendPacket(QuestSound.ITEMSOUND_QUEST_ITEMGET.getPacket());
			}
		}
	}

	// ---------------------------------------------------------------------------------------------
	// Couriers
	// ---------------------------------------------------------------------------------------------

	private static String key(Player player, RepeatableHunt hunt)
	{
		return player.getObjectId() + ":" + hunt.getId();
	}

	/**
	 * A player kills a hunt's monsters without the hunt: after enough of them a courier brings it.
	 */
	private void countUnasked(Player player, RepeatableHunt hunt)
	{
		final RepeatableHuntData data = RepeatableHuntData.getInstance();
		if (!canTake(player, hunt) || player.getVariables().getBoolean(DynamicQuestManager.VAR_NO_HERALDS, false) /* .quests heralds off: no couriers either */ || _pendingCouriers.containsKey(player.getObjectId()) || _couriers.containsKey(player.getObjectId()))
		{
			return;
		}
		final Long until = _cooldowns.get(key(player, hunt));
		if ((until != null) && (until > System.currentTimeMillis()))
		{
			return;
		}
		final Map<Integer, Integer> kills = _unaskedKills.computeIfAbsent(player.getObjectId(), id -> new HashMap<>());
		synchronized (kills)
		{
			final int count = kills.merge(hunt.getId(), 1, Integer::sum);
			if (count < data.getCourierKills())
			{
				return;
			}
			kills.remove(hunt.getId());
		}
		_pendingCouriers.put(player.getObjectId(), hunt.getId());
	}

	private void tick()
	{
		final RepeatableHuntData data = RepeatableHuntData.getInstance();
		final long now = System.currentTimeMillis();

		// Couriers set out once their player is out of the fight.
		for (Map.Entry<Integer, Integer> entry : _pendingCouriers.entrySet())
		{
			final Player player = World.getInstance().getPlayer(entry.getKey());
			final RepeatableHunt hunt = data.getHunt(entry.getValue());
			if ((player == null) || !player.isOnline() || (hunt == null) || isActive(player, hunt))
			{
				_pendingCouriers.remove(entry.getKey());
				continue;
			}
			if (player.isDead() || player.isInCombat() || player.isInOlympiadMode() || (player.getInstanceId() != 0) || player.isInsideZone(ZoneId.PEACE) || player.isTeleporting())
			{
				continue;
			}
			_pendingCouriers.remove(entry.getKey());
			final Location location = QuestSpawnUtil.findPoint(player, 80, 160, false, 0);
			final Npc npc = Quest.addSpawn(data.getCourierNpcId(), location.getX(), location.getY(), location.getZ(), LocationUtil.calculateHeadingFrom(location, player), false, 0, false, 0);
			if (npc == null)
			{
				continue;
			}
			npc.setRandomWalking(false);
			npc.setTitle("Sent by " + hunt.getGiverName());
			npc.broadcastInfo();
			_couriers.put(player.getObjectId(), new Courier(npc, hunt, now + (data.getCourierStay() * 1000L)));
			_cooldowns.put(key(player, hunt), now + (data.getCourierCooldown() * 1000L));
			QuestSpawnUtil.say(player, npc, player.getName() + "! A word, if you have a moment - " + hunt.getGiverName() + " of " + hunt.getTown() + " sent me.");
			QuestSpawnUtil.screen(player, "A courier from " + hunt.getTown() + " is looking for you.");
		}

		// Couriers leave when they have waited long enough, or their player is gone.
		for (Map.Entry<Integer, Courier> entry : _couriers.entrySet())
		{
			final Courier courier = entry.getValue();
			final Player player = World.getInstance().getPlayer(entry.getKey());
			if ((player == null) || !player.isOnline() || (now > courier._expires) || !courier._npc.isSpawned() || (player.calculateDistance2D(courier._npc) > 3000))
			{
				removeCourier(entry.getKey());
			}
		}
	}

	private void removeCourier(int playerObjectId)
	{
		final Courier courier = _couriers.remove(playerObjectId);
		if ((courier != null) && courier._npc.isSpawned())
		{
			courier._npc.deleteMe();
		}
	}

	private Courier getCourier(Npc npc)
	{
		for (Courier courier : _couriers.values())
		{
			if (courier._npc == npc)
			{
				return courier;
			}
		}
		return null;
	}

	// ---------------------------------------------------------------------------------------------
	// Dialogs
	// ---------------------------------------------------------------------------------------------

	/**
	 * @param player the player
	 * @param npc a giver or a courier
	 * @return the dialog
	 */
	public String onTalk(Player player, Npc npc)
	{
		if (npc.getId() == RepeatableHuntData.getInstance().getCourierNpcId())
		{
			final Courier courier = getCourier(npc);
			if ((courier == null) || (_couriers.get(player.getObjectId()) != courier))
			{
				return page(npc.getName(), "I carry a message for someone else, I'm afraid.");
			}
			return courierPage(player, npc, courier._hunt);
		}
		final RepeatableHunt hunt = _givers.get(npc.getObjectId());
		if (hunt == null)
		{
			return page(npc.getName(), "...");
		}
		return giverPage(player, npc, hunt);
	}

	/**
	 * @param player the player
	 * @param npc the npc talked to
	 * @param command the bypass after the script's name
	 * @return the dialog to show, {@code null} for none
	 */
	public String handleCommand(Player player, Npc npc, String command)
	{
		if ((npc == null) || (player.calculateDistance2D(npc) > TALK_RANGE))
		{
			return null;
		}
		final String[] args = command.trim().split(" ");
		final RepeatableHunt hunt = args.length > 1 ? RepeatableHuntData.getInstance().getHunt(parseInt(args[1])) : null;
		final Courier courier = getCourier(npc);
		final boolean isGiver = (hunt != null) && (_givers.get(npc.getObjectId()) == hunt);
		final boolean isCourier = (courier != null) && (_couriers.get(player.getObjectId()) == courier) && ((hunt == null) || (courier._hunt == hunt));
		switch (args[0])
		{
			case "main":
			{
				return isGiver ? giverPage(player, npc, hunt) : null;
			}
			case "accept":
			{
				if ((hunt == null) || (!isGiver && !isCourier))
				{
					return null;
				}
				if (isActive(player, hunt))
				{
					return isGiver ? giverPage(player, npc, hunt) : null;
				}
				if (!canTake(player, hunt))
				{
					return page(npc.getName(), "This work is for hunters of level " + hunt.getMinLevel() + " to " + hunt.getMaxLevel() + ".");
				}
				player.getVariables().set(VAR_ACTIVE + hunt.getId(), true);
				player.getVariables().set(VAR_KILLS + hunt.getId(), 0);
				player.sendPacket(QuestSound.ITEMSOUND_QUEST_MIDDLE.getPacket());
				player.sendMessage(hunt.getName() + ": taken. Report to " + hunt.getGiverName() + " in " + hunt.getTown() + " whenever you like.");
				if (isGiver)
				{
					showGround(player, hunt);
				}
				if (isCourier)
				{
					QuestSpawnUtil.say(player, npc, "Good. " + hunt.getGiverName() + " will be glad. Report in " + hunt.getTown() + " when you're done.");
					final int objectId = player.getObjectId();
					ThreadPool.schedule(() -> removeCourier(objectId), 4000);
				}
				return page(npc.getName(), hunt.getText("accepted", "Good. Hunt in {place} and come back whenever you like - I pay for every {unit}."));
			}
			case "map":
			{
				if (!isGiver && !isCourier)
				{
					return null;
				}
				showGround(player, hunt != null ? hunt : courier._hunt);
				return null;
			}
			case "report":
			{
				if (!isGiver || !isActive(player, hunt))
				{
					return null;
				}
				return report(player, npc, hunt);
			}
			case "abandon":
			{
				if (!isGiver || !isActive(player, hunt))
				{
					return null;
				}
				return page(npc.getName(), "Give up the hunt in " + hunt.getPlace() + "?" + (hunt.getType() == RepeatableHunt.Type.COLLECT ? " What you gathered and didn't report is lost." : " Kills you didn't report are lost.") + "<br><br><center>" + button("abandon_yes " + hunt.getId(), "Give up") + "</center>");
			}
			case "abandon_yes":
			{
				if (!isGiver || !isActive(player, hunt))
				{
					return null;
				}
				finish(player, hunt, npc);
				return page(npc.getName(), "As you wish. If you change your mind, I'll be here.");
			}
			case "decline":
			{
				if ((courier == null) || (_couriers.get(player.getObjectId()) != courier))
				{
					return null;
				}
				_cooldowns.put(key(player, courier._hunt), System.currentTimeMillis() + (RepeatableHuntData.getInstance().getDeclineCooldown() * 1000L));
				QuestSpawnUtil.say(player, npc, "Another time, then.");
				final int objectId = player.getObjectId();
				ThreadPool.schedule(() -> removeCourier(objectId), 3000);
				return null;
			}
		}
		return null;
	}

	/**
	 * Opens the player's map with the nearest place the hunt's monsters live flagged on it.
	 */
	private static void showGround(Player player, RepeatableHunt hunt)
	{
		Location best = null;
		double bestDistance = Double.MAX_VALUE;
		for (int npcId : hunt.getMonsterIds())
		{
			for (Spawn spawn : SpawnTable.getInstance().getSpawns(npcId))
			{
				if (spawn.getInstanceId() != 0)
				{
					continue;
				}
				// A territory spawn has no point of its own: where its monster stands.
				final Npc last = spawn.getLastSpawn();
				final int x = (spawn.getX() != 0) || (spawn.getY() != 0) ? spawn.getX() : (last != null ? last.getX() : 0);
				final int y = (spawn.getX() != 0) || (spawn.getY() != 0) ? spawn.getY() : (last != null ? last.getY() : 0);
				final int z = (spawn.getX() != 0) || (spawn.getY() != 0) ? spawn.getZ() : (last != null ? last.getZ() : 0);
				if ((x == 0) && (y == 0))
				{
					continue;
				}
				final double distance = player.calculateDistance2D(x, y, z);
				if (distance < bestDistance)
				{
					bestDistance = distance;
					best = new Location(x, y, z);
				}
			}
		}
		if (best != null)
		{
			QuestSpawnUtil.showOnMap(player, best);
			player.sendMessage(hunt.getName() + ": the hunting ground in " + hunt.getPlace() + " is flagged on your map.");
		}
	}

	private String report(Player player, Npc npc, RepeatableHunt hunt)
	{
		final long count = getCount(player, hunt);
		if (count <= 0)
		{
			return page(npc.getName(), hunt.getText("nothing", "Nothing yet? The work is in {place}. Come back when you have something for me."));
		}
		final long adena = getPayout(hunt, count);
		if (hunt.getType() == RepeatableHunt.Type.COLLECT)
		{
			if (!player.destroyItemByItemId(ItemProcessType.QUEST, hunt.getItemId(), count, npc, true))
			{
				return null;
			}
		}
		else
		{
			player.getVariables().set(VAR_KILLS + hunt.getId(), 0);
		}
		player.addAdena(ItemProcessType.QUEST, adena, npc, true);
		player.sendPacket(QuestSound.ITEMSOUND_QUEST_FINISH.getPacket());
		final boolean bonus = (hunt.getBonusMin() > 0) && (count >= hunt.getBonusMin());
		return page(npc.getName(), hunt.getText("report", "Well done.") + "<br><br><font color=\"LEVEL\">" + count + " " + unitLabel(hunt, count) + "</font>: " + String.format("%,d", adena) + " adena" + (bonus ? " (a big report: a bonus included)" : "") + ".<br>The hunt goes on - come back with more whenever you like.<br><br><center>" + button("main " + hunt.getId(), "Back") + "</center>");
	}

	private void finish(Player player, RepeatableHunt hunt, Npc npc)
	{
		player.getVariables().remove(VAR_ACTIVE + hunt.getId());
		player.getVariables().remove(VAR_KILLS + hunt.getId());
		if (hunt.getType() == RepeatableHunt.Type.COLLECT)
		{
			final long count = player.getInventory().getInventoryItemCount(hunt.getItemId(), -1);
			if (count > 0)
			{
				player.destroyItemByItemId(ItemProcessType.QUEST, hunt.getItemId(), count, npc, true);
			}
		}
	}

	private String giverPage(Player player, Npc npc, RepeatableHunt hunt)
	{
		final StringBuilder sb = new StringBuilder();
		sb.append("<font color=\"LEVEL\">").append(hunt.getName()).append("</font> <font color=\"808080\">- level ").append(hunt.getMinLevel()).append("-").append(hunt.getMaxLevel()).append(", repeatable</font><br>");
		if (!isActive(player, hunt))
		{
			sb.append(hunt.getText("offer", "There is work in {place}.")).append("<br><br>").append(terms(hunt)).append("<br><br>");
			if (canTake(player, hunt))
			{
				sb.append("<center>").append(button("accept " + hunt.getId(), "Take the hunt")).append("<br>").append(button("map " + hunt.getId(), "Show on map")).append("</center>");
			}
			else
			{
				sb.append("<font color=\"FF6666\">Come back when you are level ").append(hunt.getMinLevel()).append(" to ").append(hunt.getMaxLevel()).append(".</font>");
			}
			return page(npc.getName(), sb.toString());
		}

		final long count = getCount(player, hunt);
		sb.append(hunt.getText("progress", "How goes the hunt in {place}?")).append("<br><br>");
		sb.append("To report: <font color=\"LEVEL\">").append(count).append(" ").append(unitLabel(hunt, count)).append("</font>");
		if (count > 0)
		{
			sb.append(" - ").append(String.format("%,d", getPayout(hunt, count))).append(" adena");
		}
		sb.append("<br>").append(terms(hunt)).append("<br>");
		if (!canCount(player, hunt))
		{
			sb.append("<font color=\"FF6666\">You have outgrown this hunt: its monsters no longer count for you. Report what you have.</font><br>");
		}
		sb.append("<br><center>");
		if (count > 0)
		{
			sb.append(button("report " + hunt.getId(), "Report")).append("<br>");
		}
		sb.append(button("map " + hunt.getId(), "Show on map")).append("<br>");
		sb.append(button("abandon " + hunt.getId(), "Give up the hunt")).append("</center>");
		return page(npc.getName(), sb.toString());
	}

	private String courierPage(Player player, Npc npc, RepeatableHunt hunt)
	{
		final StringBuilder sb = new StringBuilder();
		sb.append(hunt.getText("courier", "{giver} of {town} saw you hunting in {place} and asked me to find you.")).append("<br><br>");
		sb.append("<font color=\"LEVEL\">").append(hunt.getName()).append("</font> <font color=\"808080\">- level ").append(hunt.getMinLevel()).append("-").append(hunt.getMaxLevel()).append(", repeatable</font><br>");
		sb.append(hunt.getText("offer", "There is work in {place}.")).append("<br><br>").append(terms(hunt));
		sb.append("<br>Report to ").append(hunt.getGiverName()).append(" in ").append(hunt.getTown()).append(".<br><br><center>");
		sb.append(button("accept " + hunt.getId(), "Take the hunt")).append("<br>").append(button("decline", "Not now")).append("</center>");
		return page(npc.getName(), sb.toString());
	}

	private static String terms(RepeatableHunt hunt)
	{
		final long rate = (long) (hunt.getPerUnit() * RatesConfig.RATE_QUEST_REWARD_ADENA);
		final StringBuilder sb = new StringBuilder();
		sb.append("<font color=\"99CC99\">Pays ").append(String.format("%,d", rate)).append(" adena per ").append(hunt.getUnitName());
		if ((hunt.getBonus() > 0) && (hunt.getBonusMin() > 0))
		{
			sb.append(", and ").append(String.format("%,d", (long) (hunt.getBonus() * RatesConfig.RATE_QUEST_REWARD_ADENA))).append(" more for a report of ").append(hunt.getBonusMin()).append(" or more");
		}
		sb.append(".</font>");
		return sb.toString();
	}

	private static String unitLabel(RepeatableHunt hunt, long count)
	{
		if (hunt.getType() == RepeatableHunt.Type.KILL)
		{
			return count == 1 ? "kill" : "kills";
		}
		return hunt.getUnitName();
	}

	private static String button(String bypass, String label)
	{
		return "<button value=\"" + label + "\" action=\"" + BYPASS + bypass + "\" width=160 height=21 back=\"L2UI_CT1.Button_DF_Down\" fore=\"L2UI_CT1.Button_DF\">";
	}

	private static String page(String title, String body)
	{
		return "<html><body><center><font color=\"LEVEL\">" + title + "</font></center><img src=\"L2UI.SquareGray\" width=270 height=1><br>" + body + "</body></html>";
	}

	private static int parseInt(String value)
	{
		try
		{
			return Integer.parseInt(value);
		}
		catch (NumberFormatException e)
		{
			return -1;
		}
	}

	/**
	 * @param npc an npc
	 * @return {@code true} if it is a hunt giver
	 */
	public boolean isGiver(Npc npc)
	{
		return _givers.containsKey(npc.getObjectId());
	}

	public static RepeatableHuntManager getInstance()
	{
		return SingletonHolder.INSTANCE;
	}

	private static class SingletonHolder
	{
		protected static final RepeatableHuntManager INSTANCE = new RepeatableHuntManager();
	}
}
