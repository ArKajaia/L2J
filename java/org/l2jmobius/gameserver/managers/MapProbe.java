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
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.l2jmobius.gameserver.data.sql.CharInfoTable;
import org.l2jmobius.gameserver.data.xml.MapDeviceData;
import org.l2jmobius.gameserver.data.xml.PlayerTemplateData;
import org.l2jmobius.gameserver.model.Location;
import org.l2jmobius.gameserver.model.World;
import org.l2jmobius.gameserver.model.WorldObject;
import org.l2jmobius.gameserver.model.actor.Npc;
import org.l2jmobius.gameserver.model.actor.Player;
import org.l2jmobius.gameserver.model.actor.appearance.PlayerAppearance;
import org.l2jmobius.gameserver.model.actor.enums.player.PlayerClass;
import org.l2jmobius.gameserver.model.actor.instance.Monster;
import org.l2jmobius.gameserver.model.mapdevice.MapArena;
import org.l2jmobius.gameserver.model.mapdevice.MapModifier;
import org.l2jmobius.gameserver.model.mapdevice.MapRun;
import org.l2jmobius.gameserver.model.mapdevice.MapRun.Status;

/**
 * The map probe of the web API ({@code POST /api/mapdevice/probe}): tries map modifiers on a running server without a game client. It opens a map with the modifiers named (no hideout, no portals), puts a client-less player of its own inside ("MapProbe", made once in the
 * database), and has it slay the nearest monsters, hit by hit, at a set pace. It answers what the map did, as JSON: kills, chain kills, Rampage, Rising Stakes, Momentum, and everything the map said to the players inside.
 * <p>
 * Parameters: {@code arena} (an arena or event map, default the tier's first), {@code tier} (3), {@code mods} (MapModifier names, comma separated), {@code kills} (30), {@code seconds} (60, the most it runs), {@code pace} (ms between hits, 250), {@code hit} (the share of a monster's
 * max HP each hit deals, 0.3), {@code after} (seconds to wait once done, so timed effects can wear off, 0), {@code invul} (1: the probe takes no damage), {@code keep} (1: leave the map open). Before it fights: {@code waystone=1} tries the waystones (from too far, then from beside one),
 * {@code hearts=N} breaks N Nest Hearts, {@code coffer=1} opens the nearest Sealed Coffer and slays its two waves.
 * @author Mobius
 */
public final class MapProbe
{
	private static final Logger LOGGER = Logger.getLogger(MapProbe.class.getName());
	private static final String NAME = "MapProbe";
	private static final String ACCOUNT = "mapprobe";
	private static final int LEVEL = 85;
	private static final Object LOCK = new Object();

	private MapProbe()
	{
	}

	/**
	 * Runs a probe. It blocks until it is done (at most {@code seconds} plus {@code after}); one probe at a time.
	 * @param params the request's parameters
	 * @return the result, as JSON
	 */
	public static String run(Map<String, String> params)
	{
		synchronized (LOCK)
		{
			try
			{
				return probe(params);
			}
			catch (Exception e)
			{
				LOGGER.log(Level.WARNING, MapProbe.class.getSimpleName() + ": Probe failed.", e);
				return "{\"error\":" + quote(e.toString()) + "}";
			}
		}
	}

	private static String probe(Map<String, String> params) throws InterruptedException
	{
		final int tier = parseInt(params.get("tier"), 3);
		if (MapDeviceData.getInstance().getTier(tier) == null)
		{
			return "{\"error\":\"No tier " + tier + ".\"}";
		}
		final String arenaName = params.getOrDefault("arena", "").trim();
		final MapArena arena = arenaName.isEmpty() ? firstArena(tier) : MapDeviceManager.findArena(arenaName);
		if (arena == null)
		{
			return "{\"error\":" + quote("No arena " + arenaName + ".") + "}";
		}
		final List<String> unknown = new ArrayList<>();
		final List<MapModifier> modifiers = MapDeviceManager.parseModifiers(params.getOrDefault("mods", ""), unknown);
		if (!unknown.isEmpty())
		{
			return "{\"error\":" + quote("Unknown modifiers: " + String.join(", ", unknown)) + "}";
		}
		final int killTarget = Math.max(0, Math.min(1000, parseInt(params.get("kills"), 30)));
		final int seconds = Math.max(1, Math.min(900, parseInt(params.get("seconds"), 60)));
		final int pace = Math.max(50, Math.min(5000, parseInt(params.get("pace"), 250)));
		final double hit = Math.max(0.01, Math.min(1.0, parseDouble(params.get("hit"), 0.3)));
		final int after = Math.max(0, Math.min(120, parseInt(params.get("after"), 0)));
		final boolean invul = !"0".equals(params.get("invul"));
		final boolean keep = "1".equals(params.get("keep"));

		final Player probe = getProbePlayer();
		if (probe == null)
		{
			return "{\"error\":\"Could not make the probe player.\"}";
		}
		probe.setInvul(invul);
		if (probe.isDead())
		{
			probe.doRevive();
		}
		probe.setCurrentHpMp(probe.getMaxHp(), probe.getMaxMp());

		final MapDeviceManager manager = MapDeviceManager.getInstance();
		final MapRun run = manager.openProbeRun(probe, arena, tier, modifiers);
		final long start = System.currentTimeMillis();
		final Location entry = MapDeviceManager.getGroundLocation(arena.entry());
		teleport(probe, entry.getX(), entry.getY(), entry.getZ(), run.getInstanceId());

		final int monstersAtStart = run.getMonsterTotal();
		final StringBuilder extra = new StringBuilder();

		// Waystones: from too far, then from beside the first, to the third.
		if ("1".equals(params.get("waystone")) && (run.getWaystones().size() >= 3))
		{
			final Npc first = run.getWaystones().get(0);
			final Npc third = run.getWaystones().get(2);
			final String far = manager.useWaystone(probe, first, 3);
			teleport(probe, first.getX() + 260, first.getY(), first.getZ(), run.getInstanceId());
			final String near = manager.useWaystone(probe, first, 3);
			if ((near == null) && (probe.getClient() == null))
			{
				probe.onTeleported();
			}
			extra.append(",\"waystones\":{\"names\":").append(quote(MapDeviceManager.getWaystoneNames(run).toString()));
			extra.append(",\"fromFar\":").append(far == null ? "null" : quote(far));
			extra.append(",\"fromNear\":").append(near == null ? "null" : quote(near));
			extra.append(",\"distanceToTarget\":").append((int) probe.calculateDistance2D(third));
			extra.append(",\"distanceFromStart\":").append((int) probe.calculateDistance2D(first)).append('}');
		}

		// Nests: break some hearts.
		final int hearts = Math.max(0, parseInt(params.get("hearts"), 0));
		int heartHits = 0;
		int heartsBroken = 0;
		for (Map.Entry<Integer, Integer> nestHeart : run.getNestHearts().entrySet())
		{
			if (heartsBroken >= hearts)
			{
				break;
			}
			if (!(World.getInstance().findObject(nestHeart.getValue()) instanceof Monster heart) || heart.isDead())
			{
				continue;
			}
			teleport(probe, heart.getX() + 60, heart.getY(), heart.getZ(), run.getInstanceId());
			for (int i = 0; (i < 200) && !heart.isDead(); i++)
			{
				heart.reduceCurrentHp(heart.getMaxHp() * hit * 0.25, probe, true, false, null);
				heartHits++;
				Thread.sleep(Math.min(pace, 100));
			}
			heartsBroken++;
		}

		// Sealed Coffers: open the nearest, slay its waves.
		if ("1".equals(params.get("coffer")) && !run.getCoffers().isEmpty())
		{
			MapRun.Coffer coffer = null;
			for (MapRun.Coffer candidate : run.getCoffers().values())
			{
				if ((coffer == null) || (probe.calculateDistance2D(candidate.getNpc()) < probe.calculateDistance2D(coffer.getNpc())))
				{
					coffer = candidate;
				}
			}
			final Npc npc = coffer.getNpc();
			final String far = manager.openCoffer(probe, npc);
			teleport(probe, npc.getX() + 80, npc.getY(), npc.getZ(), run.getInstanceId());
			final String opened = manager.openCoffer(probe, npc);
			final String again = manager.openCoffer(probe, npc);
			int maxWave = coffer.getWave();
			int guardKills = 0;
			final long cofferDeadline = System.currentTimeMillis() + 120000;
			while (run.getCoffers().containsValue(coffer) && (System.currentTimeMillis() < cofferDeadline))
			{
				maxWave = Math.max(maxWave, coffer.getWave());
				Monster guard = null;
				for (int objectId : coffer.getGuards())
				{
					if ((World.getInstance().findObject(objectId) instanceof Monster monster) && !monster.isDead())
					{
						guard = monster;
						break;
					}
				}
				if (guard == null)
				{
					Thread.sleep(200);
					continue;
				}
				if (probe.calculateDistance2D(guard) > 150)
				{
					teleport(probe, guard.getX() + 60, guard.getY(), guard.getZ(), run.getInstanceId());
				}
				while (!guard.isDead())
				{
					guard.reduceCurrentHp(guard.getMaxHp() * hit, probe, true, false, null);
					Thread.sleep(Math.min(pace, 100));
				}
				guardKills++;
			}
			extra.append(",\"coffer\":{\"fromFar\":").append(far == null ? "null" : quote(far));
			extra.append(",\"opened\":").append(opened == null ? "null" : quote(opened));
			extra.append(",\"again\":").append(again == null ? "null" : quote(again));
			extra.append(",\"maxWave\":").append(maxWave);
			extra.append(",\"guardKills\":").append(guardKills);
			extra.append(",\"paid\":").append(!run.getCoffers().containsValue(coffer));
			extra.append(",\"coffersLeft\":").append(run.getCoffers().size()).append('}');
		}
		final int killsBeforeFight = run.getKills();
		int hits = 0;
		int maxMomentum = 0;
		double radarFirstAt = -1;
		final java.util.Set<Integer> radarTargets = new java.util.HashSet<>();
		int deaths = 0;
		final long deadline = start + (seconds * 1000L);
		Monster target = null;
		while ((System.currentTimeMillis() < deadline) && (run.getKills() < killTarget) && (run.getStatus() == Status.OPEN))
		{
			if (probe.isDead())
			{
				deaths++;
				probe.doRevive();
				probe.setCurrentHpMp(probe.getMaxHp(), probe.getMaxMp());
				run.probeLog("The probe died and got up.");
			}
			if (probe.getInstanceId() != run.getInstanceId())
			{
				teleport(probe, entry.getX(), entry.getY(), entry.getZ(), run.getInstanceId());
			}
			if ((target == null) || target.isDead() || (target.getInstanceId() != run.getInstanceId()))
			{
				target = nearestMonster(run, probe);
				if (target == null)
				{
					Thread.sleep(pace);
					continue;
				}
				if (probe.calculateDistance2D(target) > 150)
				{
					teleport(probe, target.getX() + 60, target.getY(), target.getZ(), run.getInstanceId());
				}
			}
			target.reduceCurrentHp(target.getMaxHp() * hit, probe, true, false, null);
			hits++;
			final MapRun.RadarMark mark = run.getRadarMarks().get(probe.getObjectId());
			if (mark != null)
			{
				if (radarFirstAt < 0)
				{
					radarFirstAt = (System.currentTimeMillis() - start) / 1000.0;
				}
				radarTargets.add(mark.monsterId());
			}
			final long[] momentum = run.getMomentum().get(probe.getObjectId());
			if (momentum != null)
			{
				maxMomentum = Math.max(maxMomentum, (int) momentum[0]);
			}
			Thread.sleep(pace);
		}
		Thread.sleep(800); // Let the last bursts and deaths land.
		final long fought = System.currentTimeMillis() - start;
		final boolean rampagingAtEnd = run.isRampaging();
		final long[] momentumAtEnd = run.getMomentum().get(probe.getObjectId());
		final int momentumStacksAtEnd = momentumAtEnd != null ? (int) momentumAtEnd[0] : 0;
		final double runSpeedAtEnd = probe.getRunSpeed();
		final double pAtkSpdAtEnd = probe.getPAtkSpd();
		if (after > 0)
		{
			Thread.sleep(after * 1000L);
		}

		final Monster sample = nearestMonster(run, probe);
		final StringBuilder json = new StringBuilder(4096);
		json.append('{');
		json.append("\"instance\":").append(run.getInstanceId());
		json.append(",\"arena\":").append(quote(arena.name()));
		json.append(",\"tier\":").append(tier);
		json.append(",\"modifiers\":").append(quote(modifiers.toString()));
		json.append(",\"status\":").append(quote(run.getStatus().name()));
		json.append(",\"seconds\":").append(fought / 1000.0);
		json.append(",\"monstersAtStart\":").append(monstersAtStart);
		json.append(",\"monsterTotal\":").append(run.getMonsterTotal());
		json.append(",\"kills\":").append(run.getKills());
		json.append(",\"monstersLeft\":").append(run.getMonsters().size());
		json.append(",\"toGo\":").append(MapDeviceManager.getMonstersToGo(run));
		json.append(",\"hits\":").append(hits);
		json.append(",\"probeDeaths\":").append(deaths);
		json.append(",\"chainKills\":").append(run.getChainKills());
		json.append(",\"stakesSteps\":").append(run.getStakesSteps());
		json.append(",\"rampagingAtEnd\":").append(rampagingAtEnd);
		json.append(",\"rampagingAfterWait\":").append(run.isRampaging());
		json.append(",\"maxMomentum\":").append(maxMomentum);
		json.append(",\"momentumAtEnd\":").append(momentumStacksAtEnd);
		json.append(",\"momentumAfterWait\":").append(run.getMomentum().containsKey(probe.getObjectId()) ? (int) run.getMomentum().get(probe.getObjectId())[0] : 0);
		json.append(",\"probeRunSpeedAtEnd\":").append(runSpeedAtEnd);
		json.append(",\"probePAtkSpdAtEnd\":").append(pAtkSpdAtEnd);
		json.append(",\"probeRunSpeed\":").append(probe.getRunSpeed());
		json.append(",\"probePAtkSpd\":").append(probe.getPAtkSpd());
		json.append(",\"mapsDropped\":").append(run.getMapsDropped());
		json.append(",\"radarFirstAt\":").append(radarFirstAt);
		json.append(",\"radarTargets\":").append(radarTargets.size());
		json.append(",\"pulled\":").append(run.getPulled());
		json.append(",\"killsBeforeFight\":").append(killsBeforeFight);
		json.append(extra);
		if (!run.getNestHearts().isEmpty())
		{
			json.append(",\"heartHits\":").append(heartHits);
			json.append(",\"nests\":[");
			boolean first = true;
			for (Map.Entry<Integer, Integer> nestHeart : run.getNestHearts().entrySet())
			{
				final int nest = nestHeart.getKey();
				final boolean heartAlive = (World.getInstance().findObject(nestHeart.getValue()) instanceof Monster heart) && !heart.isDead();
				int monsters = 0;
				Monster member = null;
				for (int objectId : run.getMonsters())
				{
					if ((World.getInstance().findObject(objectId) instanceof Monster monster) && !monster.isDead() && (monster.getVariables().getInt("MAP_NEST", -1) == nest))
					{
						monsters++;
						member = monster;
					}
				}
				json.append(first ? "" : ",").append("{\"nest\":").append(nest).append(",\"broken\":").append(run.isNestBroken(nest)).append(",\"heartAlive\":").append(heartAlive).append(",\"monsters\":").append(monsters);
				if (member != null)
				{
					json.append(",\"pAtkMultiplier\":").append(MapDeviceManager.getMonsterMultiplier(member, MapRun.MonsterStat.P_ATK)).append(",\"dropMultiplier\":").append(MapDeviceManager.getDropMultiplier(member));
					json.append(",\"memberMaxHp\":").append(member.getMaxHp());
					if (run.getModifiers().size() == 1)
					{
						json.append(",\"damageOf1000\":").append(Math.round(MapDeviceManager.onMonsterDamaged(member, 1000, probe, false, null))); // Nests alone: no side effect.
					}
				}
				if (heartAlive && (World.getInstance().findObject(nestHeart.getValue()) instanceof Monster heart))
				{
					json.append(",\"heartMaxHp\":").append(heart.getMaxHp());
				}
				json.append('}');
				first = false;
			}
			json.append(']');
		}
		if (sample != null)
		{
			json.append(",\"sample\":{\"name\":").append(quote(sample.getName()));
			json.append(",\"xpMultiplier\":").append(MapDeviceManager.getXpMultiplier(sample));
			json.append(",\"dropMultiplier\":").append(MapDeviceManager.getDropMultiplier(sample));
			json.append(",\"pAtkMultiplier\":").append(MapDeviceManager.getMonsterMultiplier(sample, MapRun.MonsterStat.P_ATK));
			json.append(",\"hpMultiplier\":").append(MapDeviceManager.getMonsterMultiplier(sample, MapRun.MonsterStat.HP));
			json.append(",\"atkSpdMultiplier\":").append(MapDeviceManager.getMonsterMultiplier(sample, MapRun.MonsterStat.ATK_SPD));
			json.append('}');
		}
		json.append(",\"log\":[");
		synchronized (run.getProbeLog())
		{
			boolean first = true;
			for (String line : run.getProbeLog())
			{
				json.append(first ? "" : ",").append(quote(line));
				first = false;
			}
		}
		json.append("]}");

		if (!keep)
		{
			manager.closeRun(run, null);
			probe.setInvul(false);
		}
		return json.toString();
	}

	/**
	 * @return the alive monster of the run nearest the probe, counted or not (Ambush's, Rampage's... all count as targets), {@code null} for none
	 */
	private static Monster nearestMonster(MapRun run, Player probe)
	{
		Monster nearest = null;
		double best = Double.MAX_VALUE;
		for (int objectId : run.getMonsters())
		{
			final WorldObject object = World.getInstance().findObject(objectId);
			if ((object instanceof Monster monster) && !monster.isDead() && (monster.getInstanceId() == run.getInstanceId()))
			{
				final double distance = probe.calculateDistance2D(monster);
				if (distance < best)
				{
					best = distance;
					nearest = monster;
				}
			}
		}
		return nearest;
	}

	/**
	 * A client-less player never answers a teleport, so it is finished here.
	 */
	static void teleport(Player player, int x, int y, int z, int instanceId)
	{
		player.teleToLocation(x, y, z, 0, instanceId, 0);
		if (player.getClient() == null)
		{
			player.onTeleported();
		}
	}

	private static MapArena firstArena(int tier)
	{
		final List<MapArena> arenas = MapDeviceData.getInstance().getArenas(tier);
		return arenas.isEmpty() ? null : arenas.get(0);
	}

	/**
	 * @return the probe player, online and in the world: loaded, or made once in the database
	 */
	private static Player getProbePlayer()
	{
		Player player = World.getInstance().getPlayer(NAME);
		if (player != null)
		{
			return player;
		}
		final int objectId = CharInfoTable.getInstance().getIdByName(NAME);
		if (objectId > 0)
		{
			player = Player.load(objectId);
		}
		else
		{
			player = Player.create(PlayerTemplateData.getInstance().getTemplate(PlayerClass.DUELIST), ACCOUNT, NAME, new PlayerAppearance((byte) 0, (byte) 0, (byte) 0, false));
			if (player != null)
			{
				CharInfoTable.getInstance().addName(player);
			}
		}
		if (player == null)
		{
			return null;
		}
		player.setOnlineStatus(true, false);
		player.setXYZInvisible(83400, 147943, -3404); // Giran.
		player.spawnMe(83400, 147943, -3404);
		if (player.getLevel() < LEVEL)
		{
			player.getStat().addLevel((byte) (LEVEL - player.getLevel()));
		}
		player.setCurrentHpMp(player.getMaxHp(), player.getMaxMp());
		LOGGER.info(MapProbe.class.getSimpleName() + ": The probe player " + NAME + " is online.");
		return player;
	}

	private static int parseInt(String text, int fallback)
	{
		try
		{
			return text == null ? fallback : Integer.parseInt(text.trim());
		}
		catch (NumberFormatException e)
		{
			return fallback;
		}
	}

	private static double parseDouble(String text, double fallback)
	{
		try
		{
			return text == null ? fallback : Double.parseDouble(text.trim());
		}
		catch (NumberFormatException e)
		{
			return fallback;
		}
	}

	private static String quote(String text)
	{
		final StringBuilder sb = new StringBuilder(text.length() + 2).append('"');
		for (char c : text.toCharArray())
		{
			switch (c)
			{
				case '"' -> sb.append("\\\"");
				case '\\' -> sb.append("\\\\");
				case '\n' -> sb.append("\\n");
				case '\r' -> sb.append("\\r");
				case '\t' -> sb.append("\\t");
				default -> sb.append(c < 0x20 ? String.format("\\u%04x", (int) c) : String.valueOf(c));
			}
		}
		return sb.append('"').toString();
	}
}
