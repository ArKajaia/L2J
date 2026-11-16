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
package org.l2jmobius.gameserver.managers.simulation;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Date;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.l2jmobius.commons.threads.ThreadPool;
import org.l2jmobius.gameserver.ai.AttackableAI;
import org.l2jmobius.gameserver.ai.Intention;
import org.l2jmobius.gameserver.config.RatesConfig;
import org.l2jmobius.gameserver.config.custom.ChampionMonstersConfig;
import org.l2jmobius.gameserver.data.xml.FakePlayerPvpData;
import org.l2jmobius.gameserver.data.xml.ItemData;
import org.l2jmobius.gameserver.data.xml.MapDeviceData;
import org.l2jmobius.gameserver.geoengine.GeoEngine;
import org.l2jmobius.gameserver.managers.FakePlayerPvpManager;
import org.l2jmobius.gameserver.managers.MapDeviceManager;
import org.l2jmobius.gameserver.model.Location;
import org.l2jmobius.gameserver.model.World;
import org.l2jmobius.gameserver.model.WorldRegion;
import org.l2jmobius.gameserver.model.actor.Attackable;
import org.l2jmobius.gameserver.model.actor.Creature;
import org.l2jmobius.gameserver.model.actor.Npc;
import org.l2jmobius.gameserver.model.actor.enums.npc.DropType;
import org.l2jmobius.gameserver.model.actor.holders.npc.FakePlayerPvpBuild;
import org.l2jmobius.gameserver.model.actor.holders.npc.FakePlayerPvpProfile;
import org.l2jmobius.gameserver.model.actor.instance.FakePlayerPvpServitor;
import org.l2jmobius.gameserver.model.actor.instance.Monster;
import org.l2jmobius.gameserver.model.item.ItemTemplate;
import org.l2jmobius.gameserver.model.item.holders.ItemHolder;
import org.l2jmobius.gameserver.model.mapdevice.MapArena;
import org.l2jmobius.gameserver.model.mapdevice.MapModifier;
import org.l2jmobius.gameserver.model.mapdevice.MapRun;
import org.l2jmobius.gameserver.model.mapdevice.MapRun.Status;
import org.l2jmobius.gameserver.model.mapdevice.MapRunListener;
import org.l2jmobius.gameserver.taskmanagers.DecayTaskManager;

/**
 * The map simulation ({@code POST /api/mapdevice/simulate}): fake players of one build run Map Device maps alone, each map in an instance of its own, many at once, the way a player runs them, and every map is logged for balancing the maps' difficulty and
 * rewards.
 * <p>
 * Each map is opened as at the device (its medals roll its modifiers, their tiers and its hot zone effect, in an arena of its tier picked at random) but with no hideout and no portals. One fake player (a map rival of the map, driven by
 * {@code FakePlayerPvpAI#thinkMapRunner}) comes in at the entry with its buffs and servitor, and fights the nearest monster, resting between fights when hurt. It takes the players' side of the modifiers (stat modifiers, the hot zone effect, Burning Ground, Storm Caller,
 * Volatile, Chain Reaction, Death Curse, Momentum, Rampage, Ambush, Venomous, Lacerating, Flaskless) and the map's aggressive monsters go for it. When it dies it wakes at the entry {@code respawn} seconds later (a player wakes up in the hideout and takes another
 * portal). A map ends when it is complete, when it collapses (60 minutes, 15 with Hourglass), after {@code minutes}, or when nothing was slain for {@code stuck} minutes.
 * <p>
 * What a player would have earned is worked out at each kill credited to the fake player, without giving anything: experience and SP at its level (its map's multipliers, Champion and random passive bonuses, the level gap), the items its drop lists roll for it
 * (with the map's drop multipliers), their adena and reference price, and the maps the run drops (a counted share of the map's budget, as for a player).
 * <p>
 * Parameters: {@code build} (Soultaker), {@code level} ({@code auto}: 80 at tiers 1-2 up to 85 at 9-10, or a level), {@code tiers} (1-10), {@code medals} (1-10: the maps of a tier take each of these in turn), {@code repeat} (maps per tier and medal count, 1),
 * {@code parallel} (maps open at once, 100), {@code minutes} (60), {@code stuck} (6), {@code respawn} (10 seconds), {@code arena} (all of the tier's).
 * <p>
 * Output: {@code log/simulation-maps/maps-<time>/}: {@code runs.csv} and {@code runs.jsonl} (a line per map), {@code deaths.csv} (a line per death of the fake player), {@code kinds.csv} (each kind of monster in each map: kills, deaths it caused, experience),
 * {@code summary.txt} (by tier, by medals, and the modifiers), rewritten as maps end. {@code tools/simulation/map_report.py} reads them.
 * @author Mobius
 */
public final class MapSimulation
{
	private static final Logger LOGGER = Logger.getLogger(MapSimulation.class.getName());
	private static final Path ROOT = Paths.get("log", "simulation-maps");

	/** The runner fights a monster at once within this, else it runs toward it. */
	public static final int ENGAGE_RANGE = 1100;
	/** The runner sits down to rest below this share of its HP when nothing is on it, and stands up again at the other. */
	public static final double REST_BELOW = 0.45;
	public static final double REST_UNTIL = 0.9;
	/** The same for its MP: below this it rests (nothing on it) until the other. */
	public static final double MP_REST_BELOW = 0.12;
	public static final double MP_REST_UNTIL = 0.7;
	/** It rests at most this long for MP alone (fake players have no mana potions, and their MP comes back slowly). */
	public static final long MP_REST_MAX_MILLIS = 30000;
	/** A monster it gets no closer to and doesn't hurt for this long is out of its reach (across water, a cliff, the fence): it leaves it aside for a while. */
	private static final long NO_PROGRESS_MILLIS = 30000;
	private static final long SKIP_MILLIS = 180000;

	private static final int WATCH_INTERVAL = 5000;
	private static final int DEFAULT_PARALLEL = 100;

	/** The batch going on, {@code null} for none. */
	private static volatile Batch CURRENT;
	/** Every runner (fake player) and its servitor -> its job. */
	private static final Map<Integer, Job> FIGHTERS = new ConcurrentHashMap<>();
	/** Runners only (not their servitors) -> their job. */
	private static final Map<Integer, Job> RUNNERS = new ConcurrentHashMap<>();
	/** The last batch, for its status once it is over. */
	private static volatile Batch LAST;

	private MapSimulation()
	{
	}

	// =========================================================================
	// Hooks
	// =========================================================================

	/**
	 * @param creature anyone
	 * @return {@code true} if it is the fake player of a simulated map
	 */
	public static boolean isRunner(Creature creature)
	{
		return (creature != null) && !RUNNERS.isEmpty() && RUNNERS.containsKey(creature.getObjectId());
	}

	/**
	 * Read by {@code AttackableAI#isAggressiveTowards}: a monster of a simulated map goes for its fake player (and its servitor) as for a player: when it is aggressive (by nature or by the map), within its aggro range, in sight.
	 * @param monster a monster
	 * @param target a creature it sees
	 * @return {@code null} if the target is no fighter of a simulated map (the usual rules decide), else whether the monster goes for it
	 */
	public static Boolean isPrey(Attackable monster, Creature target)
	{
		if (FIGHTERS.isEmpty() || (target == null))
		{
			return null;
		}
		final Job job = FIGHTERS.get(target.getObjectId());
		if (job == null)
		{
			return null;
		}
		if ((monster.getInstanceId() != target.getInstanceId()) || target.isAlikeDead() || !monster.isMonster() || monster.isFakePlayer())
		{
			return false;
		}
		return monster.isAggressive() && monster.isInsideRadius3D(target, monster.getAggroRange()) && GeoEngine.getInstance().canSeeTarget(monster, target);
	}

	/**
	 * @param npc a runner
	 * @return the nearest monster of its map still to slay (the ones that count toward completing it), {@code null} for none
	 */
	public static Creature findTarget(Npc npc)
	{
		final Job job = RUNNERS.get(npc.getObjectId());
		final MapRun run = job != null ? job._run : null;
		if ((run == null) || run.isClosed())
		{
			return null;
		}
		final Monster best = nearest(npc, run, job, true);
		// Every one left aside: the nearest all the same.
		return best != null ? best : nearest(npc, run, job, false);
	}

	private static Monster nearest(Npc npc, MapRun run, Job job, boolean skip)
	{
		Monster best = null;
		double bestDistance = Double.MAX_VALUE;
		final long now = System.currentTimeMillis();
		for (int objectId : run.getMonsters())
		{
			final Long skipUntil = skip ? job._skipped.get(objectId) : null;
			if ((skipUntil != null) && (now < skipUntil))
			{
				continue;
			}
			if ((World.getInstance().findObject(objectId) instanceof Monster monster) && !monster.isDead() && (monster.getInstanceId() == npc.getInstanceId()))
			{
				final double distance = npc.calculateDistance2D(monster);
				if (distance < bestDistance)
				{
					bestDistance = distance;
					best = monster;
				}
			}
		}
		return best;
	}

	/**
	 * A runner sits resting (called on each of its thinks while it does).
	 * @param npc the runner
	 */
	public static void onResting(Npc npc)
	{
		final Job job = RUNNERS.get(npc.getObjectId());
		if (job != null)
		{
			job.onResting();
		}
	}

	/**
	 * A runner drank a potion.
	 * @param npc the runner
	 */
	public static void onPotion(Npc npc)
	{
		final Job job = RUNNERS.isEmpty() ? null : RUNNERS.get(npc.getObjectId());
		if (job != null)
		{
			job._potions.incrementAndGet();
		}
	}

	/**
	 * A fake player took damage (after its own reductions).
	 * @param fake the fake player
	 * @param damage the damage
	 */
	public static void onDamaged(Attackable fake, double damage)
	{
		if (RUNNERS.isEmpty() || (damage <= 0))
		{
			return;
		}
		final Job job = RUNNERS.get(fake.getObjectId());
		if (job != null)
		{
			synchronized (job)
			{
				job._damageTaken += damage;
			}
		}
	}

	// =========================================================================
	// Start, stop, status
	// =========================================================================

	/**
	 * Starts a batch.
	 * @param params the request's parameters (see the class)
	 * @return what happened
	 */
	public static synchronized String start(Map<String, String> params)
	{
		if ((CURRENT != null) && !CURRENT._done)
		{
			return "A map simulation is already going on: " + CURRENT._name + ". Stop it first.";
		}
		final String buildName = params.getOrDefault("build", "Soultaker").trim();
		final FakePlayerPvpBuild build = FakePlayerPvpData.getInstance().getBuild(buildName);
		if (build == null)
		{
			return "No build " + buildName + " (data/FakePlayerPvp.xml).";
		}
		final List<Integer> tiers = parseRange(params.getOrDefault("tiers", "1-10"));
		final List<Integer> medals = parseRange(params.getOrDefault("medals", "1-10"));
		if (tiers.isEmpty() || medals.isEmpty())
		{
			return "Name some tiers and medal counts (tiers=1-10, medals=1-10).";
		}
		for (int tier : tiers)
		{
			if (MapDeviceData.getInstance().getTier(tier) == null)
			{
				return "No tier " + tier + ".";
			}
		}
		final String levelSpec = params.getOrDefault("level", "auto").trim();
		final int fixedLevel = levelSpec.equalsIgnoreCase("auto") ? 0 : parseInt(levelSpec, 0);
		final int repeat = Math.max(1, Math.min(50, parseInt(params.get("repeat"), 1)));
		final int parallel = Math.max(1, Math.min(300, parseInt(params.get("parallel"), DEFAULT_PARALLEL)));
		final int minutes = Math.max(1, Math.min(120, parseInt(params.get("minutes"), 60)));
		final int stuck = Math.max(1, Math.min(60, parseInt(params.get("stuck"), 6)));
		final int respawn = Math.max(1, Math.min(120, parseInt(params.get("respawn"), 10)));
		MapArena arena = null;
		final String arenaName = params.getOrDefault("arena", "").trim();
		if (!arenaName.isEmpty())
		{
			arena = MapDeviceManager.findArena(arenaName);
			if (arena == null)
			{
				return "No arena " + arenaName + ".";
			}
		}

		final Batch batch = new Batch(build, fixedLevel, parallel, minutes, stuck, respawn, arena);
		int index = 0;
		for (int tier : tiers)
		{
			for (int r = 0; r < repeat; r++)
			{
				for (int medal : medals)
				{
					batch._queue.add(new Job(batch, ++index, tier, medal, fixedLevel > 0 ? fixedLevel : autoLevel(tier)));
				}
			}
		}
		batch._total = index;
		try
		{
			batch.open();
		}
		catch (IOException e)
		{
			LOGGER.log(Level.WARNING, MapSimulation.class.getSimpleName() + ": Could not write in " + batch._dir + ".", e);
			return "Could not write in " + batch._dir + ": " + e.getMessage();
		}
		CURRENT = batch;
		LAST = batch;
		batch.fill();
		batch._watch = ThreadPool.scheduleAtFixedRate(batch::watch, WATCH_INTERVAL, WATCH_INTERVAL);
		final String message = "Map simulation " + batch._name + ": " + index + " maps (" + build.getName() + ", tiers " + params.getOrDefault("tiers", "1-10") + ", medals " + params.getOrDefault("medals", "1-10") + ", level " + levelSpec + "), " + parallel + " at once, logs in " + batch._dir.toAbsolutePath() + ".";
		LOGGER.info(MapSimulation.class.getSimpleName() + ": " + message);
		return message;
	}

	/**
	 * Stops the batch: every open map closes and is logged as stopped.
	 * @return what happened
	 */
	public static synchronized String stop()
	{
		final Batch batch = CURRENT;
		if ((batch == null) || batch._done)
		{
			return "No map simulation going on.";
		}
		batch._queue.clear();
		for (Job job : new ArrayList<>(batch._active))
		{
			job.end("stopped");
		}
		return "Map simulation " + batch._name + " stopped.";
	}

	/**
	 * @return the batch going on (or the last one): its progress and summary
	 */
	public static String status()
	{
		final Batch batch = CURRENT != null ? CURRENT : LAST;
		if (batch == null)
		{
			return "No map simulation yet.";
		}
		final StringBuilder sb = new StringBuilder();
		sb.append(batch._name).append(batch._done ? " (done)" : " (running)").append(": ").append(batch._finished.size()).append('/').append(batch._total).append(" maps done, ").append(batch._active.size()).append(" open, ").append(batch._queue.size()).append(" waiting. Logs: ").append(batch._dir.toAbsolutePath()).append('\n');
		for (Job job : batch._active)
		{
			sb.append(job.progressLine()).append('\n');
		}
		sb.append('\n').append(batch.summary());
		return sb.toString();
	}

	private static int autoLevel(int tier)
	{
		// Roughly where a player stands when it runs maps of the tier: tier 1 at 80, tier 10 at the cap.
		return Math.min(85, 79 + ((tier + 1) / 2));
	}

	// =========================================================================
	// A batch
	// =========================================================================

	private static final class Batch
	{
		final String _name;
		final Path _dir;
		final FakePlayerPvpBuild _build;
		final int _fixedLevel;
		final int _parallel;
		final int _minutes;
		final int _stuck;
		final int _respawn;
		final MapArena _arena;
		final Deque<Job> _queue = new ArrayDeque<>();
		final Set<Job> _active = ConcurrentHashMap.newKeySet();
		final List<Job> _finished = new ArrayList<>();
		final Set<WorldRegion> _keptRegions = ConcurrentHashMap.newKeySet();
		final long _startedAt = System.currentTimeMillis();
		int _total;
		volatile boolean _done;
		ScheduledFuture<?> _watch;

		Batch(FakePlayerPvpBuild build, int fixedLevel, int parallel, int minutes, int stuck, int respawn, MapArena arena)
		{
			_name = "maps-" + new SimpleDateFormat("yyyyMMdd-HHmmss").format(new Date());
			_dir = ROOT.resolve(_name);
			_build = build;
			_fixedLevel = fixedLevel;
			_parallel = parallel;
			_minutes = minutes;
			_stuck = stuck;
			_respawn = respawn;
			_arena = arena;
		}

		void open() throws IOException
		{
			Files.createDirectories(_dir);
			Files.writeString(_dir.resolve("runs.csv"), Job.CSV_HEADER + "\n", StandardCharsets.UTF_8);
			Files.writeString(_dir.resolve("deaths.csv"), "batch,map,tier,medals,level,arena,minute,progress_pct,killer,killer_level,killer_champion,killer_kind,killer_variant,modifiers\n", StandardCharsets.UTF_8);
			Files.writeString(_dir.resolve("kinds.csv"), "batch,map,tier,medals,arena,kind,npc_id,own_level,level,lifted,max_hp,killed,deaths_caused,exp\n", StandardCharsets.UTF_8);
			Files.writeString(_dir.resolve("runs.jsonl"), "", StandardCharsets.UTF_8);
			Files.writeString(_dir.resolve("settings.txt"), "build=" + _build.getName() + "\nlevel=" + (_fixedLevel > 0 ? _fixedLevel : "auto") + "\nparallel=" + _parallel + "\nminutes=" + _minutes + "\nstuck=" + _stuck + "\nrespawn=" + _respawn + "\narena=" + (_arena != null ? _arena.id() : "any") + "\n", StandardCharsets.UTF_8);
		}

		/**
		 * Opens maps from the queue until {@link #_parallel} are open.
		 */
		synchronized void fill()
		{
			while (!_queue.isEmpty() && (_active.size() < _parallel))
			{
				final Job job = _queue.poll();
				_active.add(job);
				if (!job.begin())
				{
					_active.remove(job);
				}
			}
			if (_queue.isEmpty() && _active.isEmpty() && !_done)
			{
				finish();
			}
		}

		synchronized void onJobEnded(Job job)
		{
			_active.remove(job);
			_finished.add(job);
			append("runs.csv", job.csvLine() + "\n");
			append("runs.jsonl", job.json() + "\n");
			append("deaths.csv", job.deathLines());
			append("kinds.csv", job.kindLines());
			writeSummary();
			if (!_done)
			{
				ThreadPool.execute(this::fill);
			}
		}

		void finish()
		{
			_done = true;
			if (_watch != null)
			{
				_watch.cancel(false);
			}
			for (WorldRegion region : _keptRegions)
			{
				region.releaseKeepActive();
			}
			_keptRegions.clear();
			writeSummary();
			LOGGER.info(MapSimulation.class.getSimpleName() + ": " + _name + " done: " + _finished.size() + " maps in " + ((System.currentTimeMillis() - _startedAt) / 60000) + " minutes.");
		}

		/**
		 * Every few seconds: keeps the regions round each runner awake (the AI only runs in active regions), and ends the maps that ran out of time or got stuck.
		 */
		void watch()
		{
			try
			{
				final long now = System.currentTimeMillis();
				for (Job job : _active)
				{
					job.watch(now, this);
				}
			}
			catch (Exception e)
			{
				LOGGER.log(Level.WARNING, MapSimulation.class.getSimpleName() + ": Watch failed.", e);
			}
		}

		void keepAwake(Creature creature)
		{
			final int step = 1 << World.SHIFT_BY;
			for (int dx = -2; dx <= 2; dx++)
			{
				for (int dy = -2; dy <= 2; dy++)
				{
					final WorldRegion region = World.getInstance().getRegion(creature.getX() + (dx * step), creature.getY() + (dy * step), creature.getZ());
					if ((region != null) && !_keptRegions.contains(region) && !region.isKeptActive())
					{
						region.keepActive();
						_keptRegions.add(region);
					}
				}
			}
		}

		void append(String file, String text)
		{
			if (text.isEmpty())
			{
				return;
			}
			try
			{
				Files.writeString(_dir.resolve(file), text, StandardCharsets.UTF_8, StandardOpenOption.APPEND);
			}
			catch (IOException e)
			{
				LOGGER.log(Level.WARNING, MapSimulation.class.getSimpleName() + ": Could not write " + file + ".", e);
			}
		}

		void writeSummary()
		{
			try
			{
				Files.writeString(_dir.resolve("summary.txt"), summary(), StandardCharsets.UTF_8);
			}
			catch (IOException e)
			{
				LOGGER.log(Level.WARNING, MapSimulation.class.getSimpleName() + ": Could not write the summary.", e);
			}
		}

		synchronized String summary()
		{
			final StringBuilder sb = new StringBuilder();
			sb.append("Map simulation ").append(_name).append(": ").append(_build.getName()).append(", ").append(_finished.size()).append('/').append(_total).append(" maps done.\n\n");
			sb.append(table("Tier", _finished, j -> j._tier));
			sb.append('\n').append(table("Medals", _finished, j -> j._medals));
			sb.append('\n').append(modifierTable());
			return sb.toString();
		}

		private static String table(String label, Collection<Job> jobs, java.util.function.ToIntFunction<Job> key)
		{
			final Map<Integer, List<Job>> groups = new TreeMap<>();
			for (Job job : jobs)
			{
				groups.computeIfAbsent(key.applyAsInt(job), _ -> new ArrayList<>()).add(job);
			}
			final StringBuilder sb = new StringBuilder();
			sb.append(String.format(Locale.ROOT, "%-7s %4s %6s %8s %7s %7s %8s %9s %12s %12s %6s%n", label, "maps", "clear%", "minutes", "deaths", "rest%", "potions", "monsters", "exp/min", "adena/min", "maps"));
			for (Map.Entry<Integer, List<Job>> entry : groups.entrySet())
			{
				final List<Job> list = entry.getValue();
				double clear = 0;
				double minutes = 0;
				double deaths = 0;
				double rest = 0;
				double potions = 0;
				double monsters = 0;
				double exp = 0;
				double adena = 0;
				double maps = 0;
				double activeMinutes = 0;
				for (Job job : list)
				{
					clear += job._completedAt > 0 ? 1 : 0;
					minutes += job.minutes();
					deaths += job._deaths.size();
					rest += job.minutes() > 0 ? (job._restMillis / 60000.0) / job.minutes() : 0;
					potions += job._potions.get();
					monsters += job._monsterTotal;
					exp += job._exp;
					adena += job._adena;
					maps += job._mapsDropped.get();
					activeMinutes += job.minutes();
				}
				final int n = list.size();
				sb.append(String.format(Locale.ROOT, "%-7d %4d %5.0f%% %8.1f %7.2f %6.0f%% %8.1f %9.0f %12.0f %12.0f %6.2f%n", entry.getKey(), n, (100 * clear) / n, minutes / n, deaths / n, (100 * rest) / n, potions / n, monsters / n, activeMinutes > 0 ? exp / activeMinutes : 0, activeMinutes > 0 ? adena / activeMinutes : 0, maps / n));
			}
			return sb.toString();
		}

		/**
		 * @return each modifier: how the maps that had it went against the others of the same tiers (the difference of each map from its tier's mean, averaged)
		 */
		private String modifierTable()
		{
			final Map<Integer, double[]> tierMeans = new TreeMap<>();
			for (Job job : _finished)
			{
				final double[] mean = tierMeans.computeIfAbsent(job._tier, _ -> new double[4]);
				mean[0] += job.minutes();
				mean[1] += job._deaths.size();
				mean[2] += job._completedAt > 0 ? 1 : 0;
				mean[3]++;
			}
			final Map<String, double[]> byModifier = new TreeMap<>();
			for (Job job : _finished)
			{
				final double[] mean = tierMeans.get(job._tier);
				for (MapModifier modifier : job._modifiers)
				{
					final double[] row = byModifier.computeIfAbsent(modifier.name(), _ -> new double[4]);
					row[0] += job.minutes() - (mean[0] / mean[3]);
					row[1] += job._deaths.size() - (mean[1] / mean[3]);
					row[2] += (job._completedAt > 0 ? 1 : 0) - (mean[2] / mean[3]);
					row[3]++;
				}
			}
			final StringBuilder sb = new StringBuilder();
			sb.append("Modifiers: maps that had it against their tier's mean (a single map's luck weighs a lot below 10 maps)\n");
			sb.append(String.format(Locale.ROOT, "%-22s %5s %10s %10s %10s%n", "Modifier", "maps", "minutes", "deaths", "clear%"));
			final List<Map.Entry<String, double[]>> rows = new ArrayList<>(byModifier.entrySet());
			rows.sort((a, b) -> Double.compare(b.getValue()[1] / b.getValue()[3], a.getValue()[1] / a.getValue()[3]));
			for (Map.Entry<String, double[]> entry : rows)
			{
				final double[] row = entry.getValue();
				sb.append(String.format(Locale.ROOT, "%-22s %5d %+10.1f %+10.2f %+9.0f%%%n", entry.getKey(), (int) row[3], row[0] / row[3], row[1] / row[3], (100 * row[2]) / row[3]));
			}
			return sb.toString();
		}
	}

	// =========================================================================
	// One map
	// =========================================================================

	private static final class Death
	{
		final double _minute;
		final int _progressPct;
		final String _killer;
		final int _killerLevel;
		final int _killerChampion;
		final String _killerKind;
		final String _killerVariant;

		Death(double minute, int progressPct, String killer, int killerLevel, int killerChampion, String killerKind, String killerVariant)
		{
			_minute = minute;
			_progressPct = progressPct;
			_killer = killer;
			_killerLevel = killerLevel;
			_killerChampion = killerChampion;
			_killerKind = killerKind;
			_killerVariant = killerVariant;
		}
	}

	private static final class KindStats
	{
		final String _name;
		final int _npcId;
		final int _ownLevel;
		boolean _lifted;
		int _level;
		int _maxHp;
		int _killed;
		int _deathsCaused;
		double _exp;

		KindStats(String name, int npcId, int ownLevel)
		{
			_name = name;
			_npcId = npcId;
			_ownLevel = ownLevel;
		}
	}

	private static final class Job implements MapRunListener
	{
		static final String CSV_HEADER = "batch,map,tier,medals,level,build,arena,hotzone,modifiers,monsters,result,minutes,minutes_to_50,minutes_to_90,kills,kills_all,kills_uncredited,deaths,potions,rest_minutes,damage_taken,damage_dealt,servitor_damage_pct,exp,sp,adena,item_value,items,maps,maps_by_tier,maps_by_source,chain_kills";

		final Batch _batch;
		final int _index;
		final int _tier;
		final int _medals;
		final int _level;
		volatile MapRun _run;
		volatile Npc _runner;
		List<MapModifier> _modifiers = List.of();
		String _modifierText = "";
		int _monsterTotal;
		long _startedAt;
		long _completedAt;
		long _endedAt;
		long _lastKillAt;
		long _restMillis;
		long _lastRestAt;
		long _diedAt;
		boolean _respawnScheduled;
		String _result;
		final AtomicInteger _potions = new AtomicInteger();
		final AtomicInteger _mapsDropped = new AtomicInteger();
		final Map<Integer, Integer> _mapsByTier = new TreeMap<>();
		final Map<String, Integer> _mapsBySource = new TreeMap<>();
		final List<Death> _deaths = new ArrayList<>();
		final List<Long> _killTimes = new ArrayList<>();
		final Map<Integer, KindStats> _kinds = new LinkedHashMap<>();
		final Map<Integer, Long> _items = new TreeMap<>();
		/** Monsters out of its reach: object id -> until when it leaves them aside. */
		final Map<Integer, Long> _skipped = new ConcurrentHashMap<>();
		int _watchTargetId;
		double _watchDistance;
		double _watchHp;
		long _watchSince;
		int _skips;
		int _killsAll;
		int _uncredited;
		double _exp;
		double _sp;
		long _adena;
		double _itemValue;
		double _damageTaken;
		double _damageDealt;
		double _servitorDamage;

		Job(Batch batch, int index, int tier, int medals, int level)
		{
			_batch = batch;
			_index = index;
			_tier = tier;
			_medals = medals;
			_level = level;
		}

		/**
		 * Opens its map and puts its fake player in.
		 * @return {@code false} if it could not (logged as failed)
		 */
		boolean begin()
		{
			try
			{
				_startedAt = System.currentTimeMillis();
				_lastKillAt = _startedAt;
				_run = MapDeviceManager.getInstance().openSimulationRun(-_index, "MapSim" + _index, _batch._arena, _tier, _medals, null, this);
				_modifiers = new ArrayList<>(_run.getModifiers());
				final StringBuilder text = new StringBuilder();
				for (MapModifier modifier : _modifiers)
				{
					text.append(text.length() > 0 ? ";" : "").append(modifier.name()).append(':').append(_run.getModifierTier(modifier));
				}
				_modifierText = text.toString();
				_monsterTotal = _run.getMonsterTotal();
				spawnRunner();
				if (_runner == null)
				{
					end("failed: no fake player");
					return true;
				}
				return true;
			}
			catch (Exception e)
			{
				LOGGER.log(Level.WARNING, MapSimulation.class.getSimpleName() + ": Could not open map " + _index + " (tier " + _tier + ", " + _medals + " medals).", e);
				_result = "failed: " + e.getMessage();
				_endedAt = System.currentTimeMillis();
				ThreadPool.execute(() -> _batch.onJobEnded(this));
				return true;
			}
		}

		private void spawnRunner()
		{
			final Location entry = MapDeviceManager.getGroundLocation(_run.getArena().entry());
			final Npc fake = FakePlayerPvpManager.getInstance().spawnMapRival(_batch._build, _level, "Map " + _index + " T" + _tier + "+" + _medals, entry.getX(), entry.getY(), entry.getZ(), _run.getInstanceId());
			if (fake == null)
			{
				return;
			}
			fake.setHeading(entry.getHeading());
			_runner = fake;
			FIGHTERS.put(fake.getObjectId(), this);
			RUNNERS.put(fake.getObjectId(), this);
			FakePlayerPvpManager.getInstance().prepareSimulant(fake);
			trackServitor();
			MapDeviceManager.addFighterEffects(fake, _run);
			_batch.keepAwake(fake);
		}

		private void trackServitor()
		{
			final Npc runner = _runner;
			final FakePlayerPvpProfile profile = runner != null ? runner.getTemplate().getFakePlayerPvpProfile() : null;
			final Npc servitor = profile != null ? profile.getServitor() : null;
			if ((servitor != null) && !FIGHTERS.containsKey(servitor.getObjectId()))
			{
				FIGHTERS.put(servitor.getObjectId(), this);
			}
		}

		void onResting()
		{
			final long now = System.currentTimeMillis();
			synchronized (this)
			{
				if ((_lastRestAt > 0) && ((now - _lastRestAt) < 3000))
				{
					_restMillis += now - _lastRestAt;
				}
				_lastRestAt = now;
			}
		}

		void watch(long now, Batch batch)
		{
			final Npc runner = _runner;
			final MapRun run = _run;
			if ((run == null) || (_result != null))
			{
				return;
			}
			if (run.isClosed())
			{
				end(_completedAt > 0 ? "completed" : "closed");
				return;
			}
			if (runner != null)
			{
				batch.keepAwake(runner);
				trackServitor();
				checkProgress(runner, now);
				// A dead runner whose wake-up was lost (it decayed): back at the entry.
				if (runner.isDead() && !_respawnScheduled && ((now - _diedAt) > ((batch._respawn + 10) * 1000L)))
				{
					respawn();
				}
			}
			if ((now - _startedAt) >= (batch._minutes * 60000L))
			{
				end("timeout");
				return;
			}
			if ((now - _lastKillAt) >= (batch._stuck * 60000L))
			{
				end("stuck");
			}
		}

		/**
		 * The monster it goes for: when it got no closer and the monster lost no HP for a while, the monster is out of its reach; it leaves it aside and turns to another.
		 */
		private void checkProgress(Npc runner, long now)
		{
			if (runner.isDead())
			{
				_watchTargetId = 0;
				return;
			}
			// Resting is no lack of progress.
			if ((now - _lastRestAt) < 5000)
			{
				_watchTargetId = 0;
				return;
			}
			final Creature next = findTarget(runner);
			if (next == null)
			{
				_watchTargetId = 0;
				return;
			}
			final double distance = runner.calculateDistance2D(next);
			if ((next.getObjectId() != _watchTargetId) || (distance < (_watchDistance - 150)) || (next.getCurrentHp() < (_watchHp - 1)))
			{
				_watchTargetId = next.getObjectId();
				_watchDistance = distance;
				_watchHp = next.getCurrentHp();
				_watchSince = now;
				return;
			}
			if ((now - _watchSince) >= NO_PROGRESS_MILLIS)
			{
				_skipped.put(next.getObjectId(), now + SKIP_MILLIS);
				_skips++;
				_watchTargetId = 0;
				if ((runner.getTarget() == next) && runner.hasAI())
				{
					runner.setTarget(null);
					runner.getAI().setIntention(Intention.ACTIVE);
				}
			}
		}

		// ---- the map tells it ----

		@Override
		public List<Creature> getFighters(MapRun run)
		{
			final List<Creature> fighters = new ArrayList<>(2);
			final Npc runner = _runner;
			if (runner != null)
			{
				fighters.add(runner);
				final FakePlayerPvpProfile profile = runner.getTemplate().getFakePlayerPvpProfile();
				final Npc servitor = profile != null ? profile.getServitor() : null;
				if ((servitor != null) && !servitor.isDead())
				{
					fighters.add(servitor);
				}
			}
			return fighters;
		}

		@Override
		public boolean isFighter(Creature creature)
		{
			if (creature == null)
			{
				return false;
			}
			final Npc runner = _runner;
			if ((runner != null) && (creature == runner))
			{
				return true;
			}
			return (creature instanceof FakePlayerPvpServitor servitor) && (runner != null) && (servitor.getOwner() == runner);
		}

		@Override
		public Creature getCredited(Creature killer)
		{
			if (killer == null)
			{
				return null;
			}
			return isFighter(killer) ? _runner : null;
		}

		@Override
		public void onMonsterDamaged(MapRun run, Monster monster, Creature attacker, double damage)
		{
			if ((attacker == null) || (damage <= 0) || !isFighter(attacker))
			{
				return;
			}
			synchronized (this)
			{
				_damageDealt += damage;
				if (attacker != _runner)
				{
					_servitorDamage += damage;
				}
			}
		}

		@Override
		public void onMonsterDied(MapRun run, Monster victim, Creature killer, boolean counted)
		{
			final long now = System.currentTimeMillis();
			final Npc runner = _runner;
			final boolean credited = (runner != null) && (getCredited(killer) != null);
			double exp = 0;
			double sp = 0;
			long adena = 0;
			double value = 0;
			List<ItemHolder> drops = null;
			if (credited)
			{
				try
				{
					final double[] expSp = victim.calculateExpAndSp(_level, 1, 1);
					final double bonus = rewardBonus(victim);
					exp = expSp[0] * bonus;
					sp = expSp[1] * bonus;
					drops = victim.getDropTemplate().calculateDrops(DropType.DROP, victim, runner);
				}
				catch (Exception e)
				{
					LOGGER.log(Level.FINE, MapSimulation.class.getSimpleName() + ": Rewards of " + victim.getName() + " failed.", e);
				}
			}
			synchronized (this)
			{
				_killsAll++;
				if (counted)
				{
					_killTimes.add(now - _startedAt);
					_lastKillAt = now;
				}
				if (!credited)
				{
					_uncredited++;
					return;
				}
				_exp += exp;
				_sp += sp;
				if (drops != null)
				{
					for (ItemHolder drop : drops)
					{
						_items.merge(drop.getId(), drop.getCount(), Long::sum);
						if (drop.getId() == 57)
						{
							adena += drop.getCount();
						}
						final ItemTemplate item = ItemData.getInstance().getTemplate(drop.getId());
						if (item != null)
						{
							value += (drop.getId() == 57 ? 1.0 : item.getReferencePrice()) * drop.getCount();
						}
					}
				}
				_adena += adena;
				_itemValue += value;
				final KindStats kind = kind(victim);
				kind._killed++;
				kind._exp += exp;
			}
		}

		private KindStats kind(Monster monster)
		{
			final KindStats kind = _kinds.computeIfAbsent(monster.getId(), _ -> new KindStats(monster.getTemplate().getName(), monster.getId(), monster.getTemplate().getLevel()));
			kind._lifted |= monster.getMapLift() != null;
			kind._level = monster.getLevel();
			if ((monster.getChampionTier() == 0) && !monster.isWaveChallenge())
			{
				kind._maxHp = Math.max(kind._maxHp, monster.getMaxHp());
			}
			return kind;
		}

		private static double rewardBonus(Monster victim)
		{
			double bonus = 1.0;
			if (ChampionMonstersConfig.CHAMPION_ENABLE)
			{
				switch (victim.getChampionTier())
				{
					case 1:
						bonus *= ChampionMonstersConfig.CHAMPION_T1_REWARD;
						break;
					case 2:
						bonus *= ChampionMonstersConfig.CHAMPION_T2_REWARD;
						break;
					case 3:
						bonus *= ChampionMonstersConfig.CHAMPION_T3_REWARD;
						break;
					default:
						break;
				}
			}
			if (RatesConfig.RANDOM_PASSIVE_SKILLS_ENABLED)
			{
				final int passives = victim.getVariables().getInt("PASSIVE_COUNT", 0);
				if (passives > 0)
				{
					bonus *= 1.0 + (passives * (RatesConfig.RANDOM_PASSIVE_SKILLS_XP_SP_BONUS_PER_SKILL / 100.0));
				}
			}
			return bonus;
		}

		@Override
		public void onFighterDied(MapRun run, Creature fighter, Creature killer)
		{
			if (fighter != _runner)
			{
				return; // Its servitor: it calls another.
			}
			final long now = System.currentTimeMillis();
			final Creature attacker = killer instanceof FakePlayerPvpServitor servitor ? servitor.getOwner() : killer;
			String kindName = "";
			String variant = "";
			int champion = 0;
			int level = 0;
			if (attacker != null)
			{
				level = attacker.getLevel();
				champion = attacker.getChampionTier();
				if (attacker.isMonster())
				{
					final Monster monster = attacker.asMonster();
					kindName = monster.getTemplate().getName();
					variant = monster.isMageMonster() ? "mage" : monster.isThief() ? "thief" : monster.isInfused() ? "infused" : monster.isResonant() ? "resonant" : monster.isWaveChallenge() ? "wave" : "";
					synchronized (this)
					{
						kind(monster)._deathsCaused++;
					}
				}
			}
			synchronized (this)
			{
				final int progress = _monsterTotal > 0 ? (int) Math.round((100.0 * _killTimes.size()) / Math.max(_monsterTotal, run.getMonsterTotal())) : 0;
				_deaths.add(new Death((now - _startedAt) / 60000.0, progress, attacker != null ? attacker.getName() : "(the map)", level, champion, kindName, variant));
				_diedAt = now;
				_respawnScheduled = true;
			}
			ThreadPool.schedule(this::respawn, _batch._respawn * 1000L);
		}

		/**
		 * The runner wakes at the entry, buffed, healed, its servitor out.
		 */
		void respawn()
		{
			synchronized (this)
			{
				_respawnScheduled = false;
			}
			final Npc fake = _runner;
			final MapRun run = _run;
			if ((fake == null) || (run == null) || run.isClosed() || (_result != null) || !fake.isDead())
			{
				return;
			}
			try
			{
				DecayTaskManager.getInstance().cancel(fake);
				fake.doRevive();
				final Location entry = MapDeviceManager.getGroundLocation(run.getArena().entry());
				fake.teleToLocation(entry.getX(), entry.getY(), entry.getZ(), entry.getHeading(), run.getInstanceId());
				if (fake.hasAI() && (fake.getAI() instanceof AttackableAI ai))
				{
					ai.startAITask();
				}
				FakePlayerPvpManager.getInstance().prepareSimulant(fake);
				MapDeviceManager.addFighterEffects(fake, run);
				fake.setRunning();
			}
			catch (Exception e)
			{
				LOGGER.log(Level.WARNING, MapSimulation.class.getSimpleName() + ": Could not bring the runner of map " + _index + " back.", e);
			}
		}

		@Override
		public void onMapDropped(MapRun run, int tier, String source)
		{
			_mapsDropped.incrementAndGet();
			synchronized (this)
			{
				_mapsByTier.merge(tier, 1, Integer::sum);
				_mapsBySource.merge(source, 1, Integer::sum);
			}
		}

		@Override
		public void onCompleted(MapRun run)
		{
			synchronized (this)
			{
				_completedAt = System.currentTimeMillis();
			}
			// Done: its maps are counted (the completion's came first). The map closes now instead of 2 minutes later.
			ThreadPool.schedule(() -> end("completed"), 1000);
		}

		@Override
		public void onClosed(MapRun run, String reason)
		{
			ThreadPool.execute(() -> end(_completedAt > 0 ? "completed" : reason != null && reason.contains("time is up") ? "collapsed" : "closed"));
		}

		/**
		 * Ends the map: logs it, takes the fake player away, closes the map, and lets the batch open the next.
		 */
		void end(String result)
		{
			synchronized (this)
			{
				if (_result != null)
				{
					return;
				}
				_result = result;
				_endedAt = System.currentTimeMillis();
			}
			final Npc fake = _runner;
			if (fake != null)
			{
				RUNNERS.remove(fake.getObjectId());
				FIGHTERS.values().removeIf(job -> job == this);
				removeFake(fake);
			}
			final MapRun run = _run;
			if ((run != null) && !run.isClosed())
			{
				MapDeviceManager.getInstance().closeRun(run, null);
			}
			_batch.onJobEnded(this);
		}

		private static void removeFake(Npc fake)
		{
			try
			{
				DecayTaskManager.getInstance().cancel(fake);
				if (!fake.isDead())
				{
					fake.abortAttack();
					fake.abortCast();
					FakePlayerPvpManager.getInstance().unsummonServitor(fake);
				}
				if (!fake.isDecayed())
				{
					fake.deleteMe();
				}
			}
			catch (Exception e)
			{
				LOGGER.log(Level.WARNING, MapSimulation.class.getSimpleName() + ": Could not remove " + fake.getName() + ".", e);
			}
		}

		// ---- its log ----

		double minutes()
		{
			final long end = _completedAt > 0 ? _completedAt : _endedAt > 0 ? _endedAt : System.currentTimeMillis();
			return (end - _startedAt) / 60000.0;
		}

		private double minutesTo(double share)
		{
			final int needed = (int) Math.ceil(_monsterTotal * share);
			if ((needed <= 0) || (_killTimes.size() < needed))
			{
				return -1;
			}
			return _killTimes.get(needed - 1) / 60000.0;
		}

		String progressLine()
		{
			final MapRun run = _run;
			final Npc fake = _runner;
			return String.format(Locale.ROOT, "  map %3d  T%-2d %2d medals  %-26s %5.1f min  %3d/%-3d slain  %d deaths  dealt %.0f  %s", _index, _tier, _medals, run != null ? run.getArena().name() : "-", minutes(), run != null ? run.getKills() : 0, run != null ? run.getMonsterTotal() : 0, _deaths.size(), _damageDealt, fake == null ? "no runner" : fake.isDead() ? "dead" : String.format(Locale.ROOT, "HP %d%%", Math.round((100 * fake.getCurrentHp()) / fake.getMaxHp()))) + (fake != null ? runnerState(fake) : "");
		}

		private static String runnerState(Npc fake)
		{
			final Creature next = findTarget(fake);
			final Object target = fake.getTarget();
			final String targetText = target instanceof Creature creature ? String.format(Locale.ROOT, "%s HP %d/%d", creature.getName(), (int) creature.getCurrentHp(), creature.getMaxHp()) : "none";
			return String.format(Locale.ROOT, "  [%s%s%s, MP %d%%, target %s, next %s at %d]", fake.hasAI() ? fake.getAI().getIntention() : "-", fake.isMoving() ? " moving" : "", fake.isCastingNow() ? " casting " + (fake.getLastSkillCast() != null ? fake.getLastSkillCast().getName() : "") : "", Math.round((100 * fake.getCurrentMp()) / Math.max(1, fake.getMaxMp())), targetText, next != null ? next.getName() : "none", next != null ? (int) fake.calculateDistance2D(next) : -1);
		}

		synchronized String csvLine()
		{
			final MapRun run = _run;
			final StringBuilder items = new StringBuilder();
			for (Map.Entry<Integer, Long> entry : _items.entrySet())
			{
				items.append(items.length() > 0 ? ";" : "").append(entry.getKey()).append(':').append(entry.getValue());
			}
			return String.join(",", _batch._name, String.valueOf(_index), String.valueOf(_tier), String.valueOf(_medals), String.valueOf(_level), _batch._build.getName(), csv(run != null ? run.getArena().name() : ""), run != null && run.getHotzoneEffect() != null ? run.getHotzoneEffect().name() : "", _modifierText, String.valueOf(run != null ? run.getMonsterTotal() : _monsterTotal), csv(_result), fmt(minutes()), fmt(minutesTo(0.5)), fmt(minutesTo(0.9)), String.valueOf(_killTimes.size()), String.valueOf(_killsAll), String.valueOf(_uncredited), String.valueOf(_deaths.size()), String.valueOf(_potions.get()), fmt(_restMillis / 60000.0), String.valueOf(Math.round(_damageTaken)), String.valueOf(Math.round(_damageDealt)), fmt(_damageDealt > 0 ? (100 * _servitorDamage) / _damageDealt : 0), String.valueOf(Math.round(_exp)), String.valueOf(Math.round(_sp)), String.valueOf(_adena), String.valueOf(Math.round(_itemValue)), items.toString(), String.valueOf(_mapsDropped.get()), mapString(_mapsByTier), mapString(_mapsBySource), String.valueOf(run != null ? run.getChainKills() : 0));
		}

		synchronized String json()
		{
			final MapRun run = _run;
			final JsonLine line = new JsonLine();
			line.put("batch", _batch._name).put("map", _index).put("tier", _tier).put("medals", _medals).put("level", _level).put("build", _batch._build.getName());
			line.put("arena", run != null ? run.getArena().name() : null).put("hotzone", run != null && run.getHotzoneEffect() != null ? run.getHotzoneEffect().name() : null);
			final Map<String, Object> modifiers = new LinkedHashMap<>();
			for (MapModifier modifier : _modifiers)
			{
				modifiers.put(modifier.name(), run != null ? run.getModifierTier(modifier) : 2);
			}
			line.put("modifiers", modifiers);
			line.put("monsters", run != null ? run.getMonsterTotal() : _monsterTotal).put("monstersAtOpen", _monsterTotal);
			line.put("result", _result).ratio("minutes", minutes()).ratio("minutesTo50", minutesTo(0.5)).ratio("minutesTo90", minutesTo(0.9));
			line.put("kills", _killTimes.size()).put("killsAll", _killsAll).put("killsUncredited", _uncredited).put("chainKills", run != null ? run.getChainKills() : 0).put("unreachableSkips", _skips);
			line.put("deaths", _deaths.size()).put("potions", _potions.get()).ratio("restMinutes", _restMillis / 60000.0).put("damageTaken", Math.round(_damageTaken)).put("damageDealt", Math.round(_damageDealt)).put("servitorDamage", Math.round(_servitorDamage));
			line.put("exp", Math.round(_exp)).put("sp", Math.round(_sp)).put("adena", _adena).put("itemValue", Math.round(_itemValue));
			final Map<String, Object> items = new LinkedHashMap<>();
			for (Map.Entry<Integer, Long> entry : _items.entrySet())
			{
				final ItemTemplate item = ItemData.getInstance().getTemplate(entry.getKey());
				items.put(entry.getKey() + " " + (item != null ? item.getName() : "?"), entry.getValue());
			}
			line.put("items", items);
			line.put("maps", _mapsDropped.get()).put("mapsByTier", new LinkedHashMap<>(_mapsByTier)).put("mapsBySource", new LinkedHashMap<>(_mapsBySource));
			final List<Object> deaths = new ArrayList<>();
			for (Death death : _deaths)
			{
				deaths.add(new JsonLine().ratio("minute", death._minute).put("progressPct", death._progressPct).put("killer", death._killer).put("killerLevel", death._killerLevel).put("champion", death._killerChampion).put("variant", death._killerVariant));
			}
			line.put("deathList", deaths);
			final List<Object> kinds = new ArrayList<>();
			for (KindStats kind : _kinds.values())
			{
				kinds.add(new JsonLine().put("name", kind._name).put("npcId", kind._npcId).put("ownLevel", kind._ownLevel).put("level", kind._level).put("lifted", kind._lifted).put("maxHp", kind._maxHp).put("killed", kind._killed).put("deathsCaused", kind._deathsCaused).put("exp", Math.round(kind._exp)));
			}
			line.put("kinds", kinds);
			final List<Object> timeline = new ArrayList<>();
			for (int minute = 1; minute <= (int) Math.ceil(minutes()); minute++)
			{
				final long limit = minute * 60000L;
				int count = 0;
				for (long time : _killTimes)
				{
					if (time <= limit)
					{
						count++;
					}
				}
				timeline.add(count);
			}
			line.put("killsByMinute", timeline);
			return line.toString();
		}

		synchronized String deathLines()
		{
			final StringBuilder sb = new StringBuilder();
			final MapRun run = _run;
			for (Death death : _deaths)
			{
				sb.append(String.join(",", _batch._name, String.valueOf(_index), String.valueOf(_tier), String.valueOf(_medals), String.valueOf(_level), csv(run != null ? run.getArena().name() : ""), fmt(death._minute), String.valueOf(death._progressPct), csv(death._killer), String.valueOf(death._killerLevel), String.valueOf(death._killerChampion), csv(death._killerKind), death._killerVariant, _modifierText)).append('\n');
			}
			return sb.toString();
		}

		synchronized String kindLines()
		{
			final StringBuilder sb = new StringBuilder();
			final MapRun run = _run;
			for (KindStats kind : _kinds.values())
			{
				sb.append(String.join(",", _batch._name, String.valueOf(_index), String.valueOf(_tier), String.valueOf(_medals), csv(run != null ? run.getArena().name() : ""), csv(kind._name), String.valueOf(kind._npcId), String.valueOf(kind._ownLevel), String.valueOf(kind._level), String.valueOf(kind._lifted), String.valueOf(kind._maxHp), String.valueOf(kind._killed), String.valueOf(kind._deathsCaused), String.valueOf(Math.round(kind._exp)))).append('\n');
			}
			return sb.toString();
		}

		private static String mapString(Map<?, Integer> map)
		{
			final StringBuilder sb = new StringBuilder();
			for (Map.Entry<?, Integer> entry : map.entrySet())
			{
				sb.append(sb.length() > 0 ? ";" : "").append(entry.getKey()).append(':').append(entry.getValue());
			}
			return sb.toString();
		}
	}

	// =========================================================================
	// Helpers
	// =========================================================================

	private static String fmt(double value)
	{
		return value < 0 ? "" : String.format(Locale.ROOT, "%.2f", value);
	}

	private static String csv(String text)
	{
		if (text == null)
		{
			return "";
		}
		return text.contains(",") || text.contains("\"") ? "\"" + text.replace("\"", "\"\"") + "\"" : text;
	}

	private static List<Integer> parseRange(String spec)
	{
		final List<Integer> values = new ArrayList<>();
		for (String part : spec.split(","))
		{
			final String trimmed = part.trim();
			if (trimmed.isEmpty())
			{
				continue;
			}
			final int dash = trimmed.indexOf('-', 1);
			if (dash > 0)
			{
				final int from = parseInt(trimmed.substring(0, dash), 0);
				final int to = parseInt(trimmed.substring(dash + 1), -1);
				for (int value = from; value <= to; value++)
				{
					values.add(value);
				}
			}
			else
			{
				values.add(parseInt(trimmed, 0));
			}
		}
		return values;
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
}
