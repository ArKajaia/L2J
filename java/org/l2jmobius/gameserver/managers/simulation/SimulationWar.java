/*
 * This file is part of the L2J Mobius project.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>.
 */
package org.l2jmobius.gameserver.managers.simulation;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Deque;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.l2jmobius.gameserver.data.xml.FakePlayerPvpData;
import org.l2jmobius.gameserver.model.actor.enums.creature.Race;
import org.l2jmobius.gameserver.model.actor.holders.npc.FakePlayerPvpBuild;

/**
 * A race war: armies of one race against armies of other races, each battle in an instance of its own (see {@link FakeSimulationManager#startWar}).
 * <ul>
 * <li>An army is {@code size} fake players of one race in parties of up to 9, each party filled like a simulation party (tank, archer, healer, melee, nuker, buffer...) from its race's own builds only. A race without a class for a slot takes the closest one it has
 * (an orc party's healer is a Doomcryer, a dwarf army is all Fortune Seekers and Maestros).</li>
 * <li>In pairs mode every pair of the races fights {@code repeats} times, the races changing sides every time. In free-for-all mode every battle has an army of each race, all fighting each other at once, {@code repeats} battles.</li>
 * <li>The dead come back at their army's base after {@code respawn} seconds, with their buffs again, and fight on. A battle lasts {@code timeLimit} seconds; the army with the most kills wins it.</li>
 * <li>A race may get a power: its fighters' attack (P.Atk, and M.Atk squared, as magic damage goes with its square root) and defence (P.Def, M.Def) multiplied, for this war only.</li>
 * <li>results.csv has a line per army per battle, members.csv a line per fighter; summary.txt (rewritten after every battle) has the standings, who killed whom and the builds.</li>
 * </ul>
 */
public class SimulationWar
{
	private static final Logger LOGGER = Logger.getLogger(SimulationWar.class.getName());

	public static final String RESULTS = "results.csv";
	public static final String MEMBERS = "members.csv";
	public static final String SUMMARY = "summary.txt";
	private static final String RESULTS_HEADER = "battle,repeat,armies,team,race,attack,defence,kills,deaths,kd,place,won,reason,duration_ms,damage,heal";
	private static final String MEMBERS_HEADER = "battle,team,race,build,role,kills,deaths,damage,heal,won";

	/** The races of the war, in the order they are listed. */
	public static final List<Race> PLAYABLE = List.of(Race.HUMAN, Race.ELF, Race.DARK_ELF, Race.ORC, Race.DWARF, Race.KAMAEL);

	/** Builds a race can field that no simulation slot lists ({@link SimRole}): the Overlord line, an orc nuker. */
	private static final Map<String, SimRole> EXTRA_BUILDS = Map.of("Dominator", SimRole.NUKER);

	/** Where a slot looks when the race has no build for it, in order. */
	private static final Map<SimRole, List<SimRole>> FALLBACKS = new EnumMap<>(SimRole.class);
	static
	{
		FALLBACKS.put(SimRole.TANK, List.of(SimRole.TANK, SimRole.MELEE));
		FALLBACKS.put(SimRole.MELEE, List.of(SimRole.MELEE));
		FALLBACKS.put(SimRole.ARCHER, List.of(SimRole.ARCHER, SimRole.NUKER, SimRole.MELEE));
		FALLBACKS.put(SimRole.NUKER, List.of(SimRole.NUKER, SimRole.ARCHER, SimRole.MELEE));
		FALLBACKS.put(SimRole.HEALER, List.of(SimRole.HEALER, SimRole.BUFFER, SimRole.NUKER, SimRole.MELEE));
		FALLBACKS.put(SimRole.BUFFER, List.of(SimRole.BUFFER, SimRole.HEALER, SimRole.MELEE));
	}

	/**
	 * One battle to fight.
	 * @param number its number in this war (from 1, the order it started in)
	 * @param races the race of each team, team 1 first
	 * @param repeat which time these races are fought (from 0)
	 * @param seed the seed of the battle: builds, gear, names, personalities
	 */
	public record Battle(int number, List<Race> races, int repeat, long seed)
	{
		public Race raceOf(int team)
		{
			return races.get(team - 1);
		}

		public int teams()
		{
			return races.size();
		}

		public String describe()
		{
			final StringBuilder sb = new StringBuilder();
			for (Race race : races)
			{
				sb.append(sb.length() > 0 ? " vs " : "").append(name(race));
			}
			return sb.toString();
		}
	}

	/**
	 * A fighter of an army: its build, and the slot it fills.
	 */
	public record Recruit(FakePlayerPvpBuild build, SimRole role)
	{
	}

	/**
	 * How a fighter did in a battle.
	 */
	public record Line(int team, String build, String role, int kills, int deaths, double damage, double heal)
	{
	}

	private final String _name;
	private final Path _dir;
	private final List<Race> _races;
	private final boolean _ffa;
	private final Map<Race, double[]> _power;
	private final int _repeats;
	private final int _size;
	private final int _level;
	private final int _parallel;
	private final int _timeLimit;
	private final int _respawn;
	private final int _distance;
	private final long _seed;
	private final int _x;
	private final int _y;
	private final int _z;
	private final int _heading;
	private final int _starterId;

	private final Deque<Battle> _queue = new ArrayDeque<>();
	private final AtomicInteger _numbers = new AtomicInteger();
	private final AtomicInteger _running = new AtomicInteger();
	private final long _startTime = System.currentTimeMillis();
	private final int _total;
	private int _done;
	private volatile boolean _stopped;
	private volatile boolean _finished;

	/** Race -> {battles, wins, draws, kills, deaths}. */
	private final Map<Race, long[]> _raceStats = new EnumMap<>(Race.class);
	/** Killer race -> victim race -> kills. */
	private final Map<Race, Map<Race, long[]>> _killStats = new EnumMap<>(Race.class);
	/** Race -> other race -> {battles, wins, draws} of the pairs mode. */
	private final Map<Race, Map<Race, long[]>> _pairStats = new EnumMap<>(Race.class);
	/** "Race / build" -> {fighter-battles, kills, deaths, damage, heal}. */
	private final Map<String, double[]> _buildStats = new TreeMap<>();
	private final Map<String, Integer> _reasons = new TreeMap<>();

	public SimulationWar(String name, Path dir, List<Race> races, boolean ffa, Map<Race, double[]> power, int repeats, int size, int level, int parallel, int timeLimit, int respawn, int distance, long seed, int x, int y, int z, int heading, int starterId)
	{
		_name = name;
		_dir = dir;
		_races = races;
		_ffa = ffa;
		_power = power;
		_repeats = repeats;
		_size = size;
		_level = level;
		_parallel = parallel;
		_timeLimit = timeLimit;
		_respawn = respawn;
		_distance = distance;
		_seed = seed;
		_x = x;
		_y = y;
		_z = z;
		_heading = heading;
		_starterId = starterId;

		for (int repeat = 0; repeat < repeats; repeat++)
		{
			if (ffa)
			{
				// Every army in one battle; where each one stands around the field changes every battle.
				final long battleSeed = SimulationSettings.mix(seed + (repeat * 1000003L));
				final List<Race> order = new ArrayList<>(races);
				Collections.shuffle(order, new Random(battleSeed));
				_queue.add(new Battle(0, order, repeat, battleSeed));
				continue;
			}

			// Round after round of every pair, so a war stopped early still covers every pair about evenly; the races change sides every repeat.
			for (int i = 0; i < races.size(); i++)
			{
				for (int j = i + 1; j < races.size(); j++)
				{
					final Race a = races.get(i);
					final Race b = races.get(j);
					final long battleSeed = SimulationSettings.mix(seed + (repeat * 1000003L) + (a.ordinal() * 131L) + b.ordinal());
					_queue.add(new Battle(0, (repeat % 2) == 0 ? List.of(a, b) : List.of(b, a), repeat, battleSeed));
				}
			}
		}
		_total = _queue.size();

		try
		{
			Files.createDirectories(dir);
			Files.writeString(dir.resolve(RESULTS), RESULTS_HEADER + System.lineSeparator(), StandardCharsets.UTF_8);
			Files.writeString(dir.resolve(MEMBERS), MEMBERS_HEADER + System.lineSeparator(), StandardCharsets.UTF_8);
			Files.writeString(dir.resolve("war.properties"), "races=" + races + "\nmode=" + (ffa ? "ffa" : "pairs") + "\npower=" + describePower() + "\nrepeats=" + repeats + "\nsize=" + size + "\nlevel=" + level + "\nparallel=" + parallel + "\ntimeLimit=" + timeLimit + "\nrespawn=" + respawn + "\ndistance=" + distance + "\nseed=" + seed + "\nplace=" + x + "," + y + "," + z + "," + heading + "\n", StandardCharsets.UTF_8);
		}
		catch (IOException e)
		{
			LOGGER.log(Level.WARNING, "SimulationWar: Could not create " + dir + ".", e);
		}
	}

	/**
	 * @param name what an admin typed: human, elf, darkelf / dark_elf / de, orc, dwarf, kamael, or all
	 * @return the races it names, empty if none
	 */
	public static List<Race> parseRaces(String name)
	{
		final List<Race> races = new ArrayList<>();
		for (String part : name.toLowerCase(Locale.ROOT).split(","))
		{
			final String key = part.trim().replace("_", "").replace(" ", "");
			if (key.equals("all"))
			{
				for (Race race : PLAYABLE)
				{
					if (!races.contains(race))
					{
						races.add(race);
					}
				}
				continue;
			}
			final Race race = parseRace(key);
			if ((race != null) && !races.contains(race))
			{
				races.add(race);
			}
		}
		return races;
	}

	private static Race parseRace(String key)
	{
		for (Race race : PLAYABLE)
		{
			if (race.name().toLowerCase(Locale.ROOT).replace("_", "").equals(key) || (key.equals("de") && (race == Race.DARK_ELF)))
			{
				return race;
			}
		}
		return null;
	}

	/**
	 * @param text race=attack[/defence] pairs, comma separated (orc=1.5, dwarf=2.4/2.6): one number sets both
	 * @return race -> {attack, defence}
	 * @throws IllegalArgumentException for a race or number it can't read
	 */
	public static Map<Race, double[]> parsePower(String text)
	{
		final Map<Race, double[]> power = new EnumMap<>(Race.class);
		if ((text == null) || text.trim().isEmpty())
		{
			return power;
		}
		for (String part : text.toLowerCase(Locale.ROOT).split(","))
		{
			final String[] pair = part.trim().split("=");
			final Race race = pair.length == 2 ? parseRace(pair[0].trim().replace("_", "").replace(" ", "")) : null;
			if (race == null)
			{
				throw new IllegalArgumentException("power: race=attack[/defence], not " + part);
			}
			final String[] values = pair[1].split("/");
			final double attack = Double.parseDouble(values[0]);
			final double defence = values.length > 1 ? Double.parseDouble(values[1]) : attack;
			if ((attack <= 0) || (defence <= 0) || (attack > 20) || (defence > 20))
			{
				throw new IllegalArgumentException("power: the multipliers go from 0 to 20");
			}
			power.put(race, new double[]
			{
				attack,
				defence
			});
		}
		return power;
	}

	/**
	 * @param race a race
	 * @return its {attack, defence} multipliers, 1 if none
	 */
	public double[] getPower(Race race)
	{
		final double[] power = _power.get(race);
		return power != null ? power : new double[]
		{
			1,
			1
		};
	}

	public String describePower()
	{
		if (_power.isEmpty())
		{
			return "none";
		}
		final StringBuilder sb = new StringBuilder();
		for (Map.Entry<Race, double[]> entry : _power.entrySet())
		{
			sb.append(sb.length() > 0 ? ", " : "").append(name(entry.getKey())).append(String.format(Locale.ROOT, " atk x%.2f def x%.2f", entry.getValue()[0], entry.getValue()[1]));
		}
		return sb.toString();
	}

	/**
	 * @param build a build
	 * @return the race of its class
	 */
	public static Race raceOf(FakePlayerPvpBuild build)
	{
		return build.getPlayerClass().getRace();
	}

	/**
	 * @param race a race
	 * @param role a slot
	 * @return the builds of that race listed for that slot
	 */
	private static List<FakePlayerPvpBuild> pool(Race race, SimRole role)
	{
		final List<FakePlayerPvpBuild> builds = new ArrayList<>();
		final List<String> names = new ArrayList<>(role.getBuilds());
		for (Map.Entry<String, SimRole> extra : EXTRA_BUILDS.entrySet())
		{
			if (extra.getValue() == role)
			{
				names.add(extra.getKey());
			}
		}
		for (String name : names)
		{
			final FakePlayerPvpBuild build = FakePlayerPvpData.getInstance().getBuild(name);
			if ((build != null) && (raceOf(build) == race))
			{
				builds.add(build);
			}
		}
		return builds;
	}

	/**
	 * Fills an army of one race: parties of up to {@link SimulationComposer#MAX_SIZE}, as even as they go, each with the slots of a simulation party of its size.
	 * @param race the race
	 * @param size how many fighters
	 * @param random the random source of the battle (seeded)
	 * @return the parties, each a list of fighters (its leader first), empty if the race has no build at all
	 */
	public static List<List<Recruit>> compose(Race race, int size, Random random)
	{
		final List<List<Recruit>> parties = new ArrayList<>();
		final int count = (size + SimulationComposer.MAX_SIZE - 1) / SimulationComposer.MAX_SIZE;
		for (int p = 0; p < count; p++)
		{
			final int partySize = (size / count) + (p < (size % count) ? 1 : 0);
			final List<Recruit> party = new ArrayList<>(partySize);
			final Set<String> supports = new HashSet<>();
			for (SimRole slot : SimulationComposer.getRoles(partySize))
			{
				FakePlayerPvpBuild picked = null;
				SimRole pickedRole = null;
				for (SimRole role : FALLBACKS.get(slot))
				{
					final List<FakePlayerPvpBuild> builds = pool(race, role);
					// A party takes each support class once (an orc party gets one Doomcryer for its healer and buffer slots, not three): a slot left without one falls back further.
					if ((role == SimRole.HEALER) || (role == SimRole.BUFFER))
					{
						builds.removeIf(build -> supports.contains(build.getName()));
					}
					if (!builds.isEmpty())
					{
						picked = builds.get(random.nextInt(builds.size()));
						pickedRole = role;
						break;
					}
				}
				if (picked == null)
				{
					return List.of();
				}
				if ((pickedRole == SimRole.HEALER) || (pickedRole == SimRole.BUFFER))
				{
					supports.add(picked.getName());
				}
				party.add(new Recruit(picked, pickedRole));
			}
			parties.add(party);
		}
		return parties;
	}

	/**
	 * @return the next battle to start (counted as running), {@code null} if there is none, every slot is taken or the war stopped
	 */
	public synchronized Battle next()
	{
		if (_stopped || _queue.isEmpty() || (_running.get() >= _parallel))
		{
			return null;
		}
		final Battle battle = _queue.poll();
		_running.incrementAndGet();
		return new Battle(_numbers.incrementAndGet(), battle.races(), battle.repeat(), battle.seed());
	}

	/**
	 * A battle ended: its lines go to results.csv and members.csv, and summary.txt is written again.
	 * @param battle the battle
	 * @param kills who killed whom: [killer team][victim team], team 0 for a death no army dealt
	 * @param reason timeout, draw or stopped
	 * @param duration how long the fight lasted, in milliseconds
	 * @param lines how each fighter did
	 */
	public void record(Battle battle, int[][] kills, String reason, long duration, List<Line> lines)
	{
		// A battle cut by a stop doesn't count.
		if (reason.equals("stopped"))
		{
			return;
		}

		final int teams = battle.teams();
		final int[] teamKills = new int[teams + 1];
		final int[] teamDeaths = new int[teams + 1];
		for (int killer = 0; killer <= teams; killer++)
		{
			for (int victim = 1; victim <= teams; victim++)
			{
				if (killer > 0)
				{
					teamKills[killer] += kills[killer][victim];
				}
				teamDeaths[victim] += kills[killer][victim];
			}
		}
		// The most kills wins; places by kills.
		int best = -1;
		int winner = 0;
		for (int team = 1; team <= teams; team++)
		{
			if (teamKills[team] > best)
			{
				best = teamKills[team];
				winner = team;
			}
			else if (teamKills[team] == best)
			{
				winner = 0;
			}
		}
		final double[] damage = new double[teams + 1];
		final double[] heal = new double[teams + 1];
		for (Line line : lines)
		{
			damage[line.team()] += line.damage();
			heal[line.team()] += line.heal();
		}

		synchronized (this)
		{
			final StringBuilder members = new StringBuilder();
			for (Line line : lines)
			{
				final Race race = battle.raceOf(line.team());
				members.append(battle.number()).append(',').append(line.team()).append(',').append(race).append(',').append(line.build()).append(',').append(line.role()).append(',').append(line.kills()).append(',').append(line.deaths()).append(',').append(Math.round(line.damage())).append(',').append(Math.round(line.heal())).append(',').append(winner == line.team() ? 1 : winner == 0 ? 0.5 : 0).append(System.lineSeparator());

				final double[] stats = _buildStats.computeIfAbsent(name(race) + " / " + line.build(), _ -> new double[5]);
				stats[0]++;
				stats[1] += line.kills();
				stats[2] += line.deaths();
				stats[3] += line.damage();
				stats[4] += line.heal();
			}
			append(MEMBERS, members.toString());

			final StringBuilder results = new StringBuilder();
			for (int team = 1; team <= teams; team++)
			{
				final Race race = battle.raceOf(team);
				final double[] power = getPower(race);
				int place = 1;
				for (int other = 1; other <= teams; other++)
				{
					if (teamKills[other] > teamKills[team])
					{
						place++;
					}
				}
				results.append(battle.number()).append(',').append(battle.repeat()).append(',').append(teams).append(',').append(team).append(',').append(race).append(',').append(String.format(Locale.ROOT, "%.3f,%.3f", power[0], power[1])).append(',').append(teamKills[team]).append(',').append(teamDeaths[team]).append(',').append(String.format(Locale.ROOT, "%.3f", teamDeaths[team] > 0 ? (double) teamKills[team] / teamDeaths[team] : teamKills[team])).append(',').append(place).append(',').append(winner == team ? 1 : winner == 0 ? 0.5 : 0).append(',').append(reason).append(',').append(duration).append(',').append(Math.round(damage[team])).append(',').append(Math.round(heal[team])).append(System.lineSeparator());

				final long[] stats = _raceStats.computeIfAbsent(race, _ -> new long[5]);
				stats[0]++;
				stats[1] += winner == team ? 1 : 0;
				stats[2] += winner == 0 ? 1 : 0;
				stats[3] += teamKills[team];
				stats[4] += teamDeaths[team];

				for (int victim = 1; victim <= teams; victim++)
				{
					if (victim != team)
					{
						_killStats.computeIfAbsent(race, _ -> new EnumMap<>(Race.class)).computeIfAbsent(battle.raceOf(victim), _ -> new long[1])[0] += kills[team][victim];
					}
				}
				if (teams == 2)
				{
					final long[] pair = _pairStats.computeIfAbsent(race, _ -> new EnumMap<>(Race.class)).computeIfAbsent(battle.raceOf(3 - team), _ -> new long[3]);
					pair[0]++;
					pair[1] += winner == team ? 1 : 0;
					pair[2] += winner == 0 ? 1 : 0;
				}
			}
			append(RESULTS, results.toString());
			_reasons.merge(reason, 1, Integer::sum);
			_done++;
		}
		writeSummary();
	}

	private void append(String file, String text)
	{
		try (BufferedWriter writer = Files.newBufferedWriter(_dir.resolve(file), StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND))
		{
			writer.write(text);
		}
		catch (IOException e)
		{
			LOGGER.log(Level.WARNING, "SimulationWar: Could not write " + _dir.resolve(file) + ".", e);
		}
	}

	/**
	 * A battle was cleaned up, or could not start: its slot is free.
	 * @return {@code true} if it was the last one and the war is over (stopped, or no battle left)
	 */
	public synchronized boolean onFreed()
	{
		final boolean over = (_running.decrementAndGet() <= 0) && (_stopped || _queue.isEmpty()) && !_finished;
		if (over)
		{
			_finished = true;
		}
		return over;
	}

	public static String name(Race race)
	{
		return switch (race)
		{
			case HUMAN -> "Human";
			case ELF -> "Elf";
			case DARK_ELF -> "Dark Elf";
			case ORC -> "Orc";
			case DWARF -> "Dwarf";
			case KAMAEL -> "Kamael";
			default -> race.name();
		};
	}

	/**
	 * Writes summary.txt: the standings, who killed whom, the pairs' records, and the builds.
	 * @return its lines
	 */
	public synchronized List<String> writeSummary()
	{
		final List<String> lines = new ArrayList<>();
		lines.add("Race war " + _name + " (" + (_ffa ? "free-for-all" : "pairs") + "): " + _done + "/" + _total + " battles, " + _size + " per army, level " + _level + ", " + _timeLimit + " s each, respawn after " + _respawn + " s" + (_stopped ? " (stopped)" : ""));
		lines.add("Power: " + describePower());
		lines.add("");
		lines.add("Standings (a battle is won by the army with the most kills):");
		lines.add(String.format(Locale.ROOT, "  %-9s %7s %5s %5s %6s %8s %8s %6s %7s %13s", "race", "battles", "won", "drew", "lost", "kills", "deaths", "K/D", "kills%", "atk/def"));
		final List<Race> ranked = new ArrayList<>(_raceStats.keySet());
		ranked.sort(Comparator.comparingDouble((Race race) -> kd(_raceStats.get(race))).reversed());
		for (Race race : ranked)
		{
			final long[] s = _raceStats.get(race);
			final double[] power = getPower(race);
			lines.add(String.format(Locale.ROOT, "  %-9s %7d %5d %5d %6d %8d %8d %6.2f %6.1f%% %6.2f/%-6.2f", name(race), s[0], s[1], s[2], s[0] - s[1] - s[2], s[3], s[4], kd(s), (s[3] + s[4]) > 0 ? (100.0 * s[3]) / (s[3] + s[4]) : 50, power[0], power[1]));
		}

		lines.add("");
		lines.add("Kills, row killed column:");
		final StringBuilder header = new StringBuilder(String.format(Locale.ROOT, "  %-9s", ""));
		for (Race race : _races)
		{
			header.append(String.format(Locale.ROOT, " %9s", name(race)));
		}
		lines.add(header.toString());
		for (Race row : _races)
		{
			final StringBuilder sb = new StringBuilder(String.format(Locale.ROOT, "  %-9s", name(row)));
			for (Race column : _races)
			{
				final long[] k = row == column ? null : _killStats.getOrDefault(row, Map.of()).get(column);
				sb.append(String.format(Locale.ROOT, " %9s", row == column ? "-" : k == null ? "0" : String.valueOf(k[0])));
			}
			lines.add(sb.toString());
		}

		lines.add("");
		lines.add("Share of the kills between each two races, row against column" + (_pairStats.isEmpty() ? ":" : " (battles won-drawn-lost below):"));
		lines.add(header.toString());
		for (Race row : _races)
		{
			final StringBuilder share = new StringBuilder(String.format(Locale.ROOT, "  %-9s", name(row)));
			final StringBuilder record = new StringBuilder(String.format(Locale.ROOT, "  %-9s", ""));
			for (Race column : _races)
			{
				final long[] mine = row == column ? null : _killStats.getOrDefault(row, Map.of()).get(column);
				final long[] theirs = row == column ? null : _killStats.getOrDefault(column, Map.of()).get(row);
				final long a = mine != null ? mine[0] : 0;
				final long b = theirs != null ? theirs[0] : 0;
				share.append(String.format(Locale.ROOT, " %9s", (row == column) || ((a + b) == 0) ? "-" : String.format(Locale.ROOT, "%.0f%%", (100.0 * a) / (a + b))));
				final long[] s = row == column ? null : _pairStats.getOrDefault(row, Map.of()).get(column);
				record.append(String.format(Locale.ROOT, " %9s", s == null ? "" : s[1] + "-" + s[2] + "-" + (s[0] - s[1] - s[2])));
			}
			lines.add(share.toString());
			if (!_pairStats.isEmpty())
			{
				lines.add(record.toString());
			}
		}

		lines.add("");
		lines.add("Builds, by kills per fighter per battle (K/D, damage and healing per fighter per battle):");
		final List<Map.Entry<String, double[]>> builds = new ArrayList<>(_buildStats.entrySet());
		builds.sort(Comparator.comparingDouble((Map.Entry<String, double[]> entry) -> entry.getValue()[1] / entry.getValue()[0]).reversed());
		lines.add(String.format(Locale.ROOT, "  %-34s %7s %7s %7s %6s %10s %10s", "race / build", "fielded", "kills", "deaths", "K/D", "damage", "healing"));
		for (Map.Entry<String, double[]> entry : builds)
		{
			final double[] s = entry.getValue();
			lines.add(String.format(Locale.ROOT, "  %-34s %7.0f %7.2f %7.2f %6s %10.0f %10.0f", entry.getKey(), s[0], s[1] / s[0], s[2] / s[0], s[2] > 0 ? String.format(Locale.ROOT, "%.2f", s[1] / s[2]) : "-", s[3] / s[0], s[4] / s[0]));
		}
		lines.add("");
		lines.add("Ended: " + _reasons);

		try
		{
			Files.writeString(_dir.resolve(SUMMARY), String.join(System.lineSeparator(), lines) + System.lineSeparator(), StandardCharsets.UTF_8);
		}
		catch (IOException e)
		{
			LOGGER.log(Level.WARNING, "SimulationWar: Could not write " + _dir.resolve(SUMMARY) + ".", e);
		}
		return lines;
	}

	/**
	 * @param stats {battles, wins, draws, kills, deaths}
	 * @return kills per death (the kills when it never died)
	 */
	private static double kd(long[] stats)
	{
		return stats[4] > 0 ? (double) stats[3] / stats[4] : stats[3];
	}

	/**
	 * @return the lines of a status: progress, then the summary so far
	 */
	public List<String> getStatus()
	{
		final List<String> lines = new ArrayList<>();
		final long minutes = (System.currentTimeMillis() - _startTime) / 60000;
		lines.add("Race war " + _name + ": " + getDone() + "/" + _total + " battles done, " + getRunning() + " running, " + minutes + " min so far" + (_stopped ? ", stopping" : "") + ". " + _dir.toAbsolutePath());
		final List<String> summary = writeSummary();
		lines.addAll(summary.subList(1, summary.size()));
		return lines;
	}

	/**
	 * @return the composition of each race's army (one draw), for the start message: race -> build -> count
	 */
	public Map<Race, Map<String, Integer>> sampleArmies()
	{
		final Map<Race, Map<String, Integer>> armies = new LinkedHashMap<>();
		for (Race race : _races)
		{
			final Map<String, Integer> counts = new TreeMap<>();
			for (List<Recruit> party : compose(race, _size, new Random(_seed)))
			{
				for (Recruit recruit : party)
				{
					counts.merge(recruit.build().getName(), 1, Integer::sum);
				}
			}
			armies.put(race, counts);
		}
		return armies;
	}

	public void stop()
	{
		_stopped = true;
	}

	public boolean isStopped()
	{
		return _stopped;
	}

	public synchronized int getDone()
	{
		return _done;
	}

	public int getRunning()
	{
		return _running.get();
	}

	public int getTotal()
	{
		return _total;
	}

	public String getName()
	{
		return _name;
	}

	public Path getDir()
	{
		return _dir;
	}

	public List<Race> getRaces()
	{
		return _races;
	}

	public boolean isFreeForAll()
	{
		return _ffa;
	}

	public int getRepeats()
	{
		return _repeats;
	}

	public int getSize()
	{
		return _size;
	}

	public int getLevel()
	{
		return _level;
	}

	public int getParallel()
	{
		return _parallel;
	}

	public int getTimeLimit()
	{
		return _timeLimit;
	}

	public int getRespawn()
	{
		return _respawn;
	}

	public int getDistance()
	{
		return _distance;
	}

	public long getSeed()
	{
		return _seed;
	}

	public int getX()
	{
		return _x;
	}

	public int getY()
	{
		return _y;
	}

	public int getZ()
	{
		return _z;
	}

	public int getHeading()
	{
		return _heading;
	}

	public int getStarterId()
	{
		return _starterId;
	}
}
