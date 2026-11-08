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
package org.l2jmobius.gameserver.managers;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLongArray;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.l2jmobius.gameserver.ai.FakeTvtTactics;
import org.l2jmobius.gameserver.managers.TeamVsTeamManager.Fighter;
import org.l2jmobius.gameserver.managers.TeamVsTeamManager.Match;
import org.l2jmobius.gameserver.managers.TeamVsTeamManager.Side;
import org.l2jmobius.gameserver.managers.simulation.SimRole;
import org.l2jmobius.gameserver.model.WorldObject;
import org.l2jmobius.gameserver.model.actor.Creature;
import org.l2jmobius.gameserver.model.actor.holders.npc.FakePlayerParty;

/**
 * A batch of simulated Team vs Team matches played at once ({@code //tvt batch}, {@code POST /api/tvt/batch}), each in an instance of its own, to measure two tactics ({@link FakeTvtTactics}) against each
 * other: both teams of a match get the same builds at the same levels, the tactics change sides every match, and every two matches share a map. While the matches fight it counts, for each team:
 * <ul>
 * <li>the damage it dealt to each role of the other team, by the role that dealt it (healer focus: the share that went into the enemy healers);</li>
 * <li>once a second, whom each of its members targets (the share of members on an enemy healer);</li>
 * <li>its deaths by role, and when its first healer died;</li>
 * <li>where its archers and mages stand: how far from the closest enemy, and how often ahead of their party's front (closer to that enemy than their closest melee team mate).</li>
 * </ul>
 * Output: {@code log/simulation-tvt/batch-<time>/} with {@code results.csv} (a line per team per match), {@code roles.csv} (damage and target time by attacker role and target role) and
 * {@code summary.txt} (by tactics, rewritten after every match). {@code tools/simulation/tvt_report.py} reports on one or more batches.
 */
public class TeamVsTeamBatch
{
	private static final Logger LOGGER = Logger.getLogger(TeamVsTeamBatch.class.getName());
	private static final Path ROOT = Paths.get("log", "simulation-tvt");
	private static final SimRole[] ROLES = SimRole.values();
	private static final int R = ROLES.length;
	/** An archer or mage closer to the enemy than its closest melee team mate by more than this is ahead of its front. */
	private static final int AHEAD_MARGIN = 100;
	/** Only enemies this close count for where an archer or mage stands (farther, it isn't in a fight). */
	private static final int POSITION_SCAN = 1600;

	private final String _name;
	private final Path _dir;
	private final FakeTvtTactics _a;
	private final FakeTvtTactics _b;
	private final int _total;
	private final AtomicInteger _finished = new AtomicInteger();
	private final List<String> _results = new ArrayList<>();

	/**
	 * What one team of one match did.
	 */
	static class TeamStats
	{
		final FakeTvtTactics _tactics;
		/** Damage dealt, by attacker role * R + target role. */
		final AtomicLongArray _damage = new AtomicLongArray(R * R);
		/** Seconds a member of an attacker role targeted an enemy of a target role. */
		final AtomicLongArray _targeting = new AtomicLongArray(R * R);
		final AtomicLongArray _deaths = new AtomicLongArray(R);
		volatile long _firstHealerDeath = -1;
		long _rangedSamples;
		long _rangedAhead;
		double _rangedDistance;

		TeamStats(FakeTvtTactics tactics)
		{
			_tactics = tactics;
		}

		long damageTo(int target)
		{
			long sum = 0;
			for (int a = 0; a < R; a++)
			{
				sum += _damage.get((a * R) + target);
			}
			return sum;
		}

		long damageTotal()
		{
			long sum = 0;
			for (int i = 0; i < (R * R); i++)
			{
				sum += _damage.get(i);
			}
			return sum;
		}

		long targetingTo(int target)
		{
			long sum = 0;
			for (int a = 0; a < R; a++)
			{
				sum += _targeting.get((a * R) + target);
			}
			return sum;
		}

		long targetingTotal()
		{
			long sum = 0;
			for (int i = 0; i < (R * R); i++)
			{
				sum += _targeting.get(i);
			}
			return sum;
		}

		long deathsTotal()
		{
			long sum = 0;
			for (int i = 0; i < R; i++)
			{
				sum += _deaths.get(i);
			}
			return sum;
		}
	}

	/**
	 * What both teams of one match did.
	 */
	static class MatchStats
	{
		final TeamVsTeamBatch _batch;
		final int _index;
		final TeamStats _blue;
		final TeamStats _red;
		volatile long _fightStart;

		MatchStats(TeamVsTeamBatch batch, int index, FakeTvtTactics blue, FakeTvtTactics red)
		{
			_batch = batch;
			_index = index;
			_blue = new TeamStats(blue);
			_red = new TeamStats(red);
		}

		TeamStats of(Side side, Match match)
		{
			return side == match.getBlue() ? _blue : _red;
		}
	}

	TeamVsTeamBatch(FakeTvtTactics a, FakeTvtTactics b, int total)
	{
		_a = a;
		_b = b;
		_total = total;
		_name = "batch-" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
		_dir = ROOT.resolve(_name);
		try
		{
			Files.createDirectories(_dir);
			Files.writeString(_dir.resolve("results.csv"), "batch,match,map,level_min,level_max,side,tactics,opponent,score,opponent_score,result,damage,damage_to_healer,healer_damage_pct,healer_target_pct,deaths,healer_deaths,first_healer_death_s,ranged_distance,ranged_ahead_pct,deaths_by_role\n", StandardCharsets.UTF_8);
			Files.writeString(_dir.resolve("roles.csv"), "batch,match,side,tactics,attacker_role,target_role,damage,target_seconds\n", StandardCharsets.UTF_8);
			Files.writeString(_dir.resolve("tactics.txt"), "A " + a.toSpec() + "\nB " + b.toSpec() + "\n", StandardCharsets.UTF_8);
		}
		catch (IOException e)
		{
			LOGGER.log(Level.WARNING, "TeamVsTeamBatch: can't write " + _dir, e);
		}
	}

	public String getName()
	{
		return _name;
	}

	public Path getDirectory()
	{
		return _dir;
	}

	public int getFinished()
	{
		return _finished.get();
	}

	public int getTotal()
	{
		return _total;
	}

	/**
	 * @param index the match's place in the batch, from 0
	 * @return the tactics of Blue in that match: A in even matches, B in odd ones (Red plays the other)
	 */
	FakeTvtTactics blueOf(int index)
	{
		return (index % 2) == 0 ? _a : _b;
	}

	FakeTvtTactics redOf(int index)
	{
		return (index % 2) == 0 ? _b : _a;
	}

	static int roleIndex(Fighter fighter)
	{
		final SimRole role = fighter.getRole();
		return role != null ? role.ordinal() : SimRole.MELEE.ordinal();
	}

	// ---------------------------------------------------------------------------------------------
	// Counting (called by TeamVsTeamManager)
	// ---------------------------------------------------------------------------------------------

	static void onDamage(Fighter attacker, Fighter target, double damage)
	{
		final MatchStats stats = attacker.getMatch().getBatchStats();
		if ((stats == null) || (damage <= 0))
		{
			return;
		}
		stats.of(attacker.getSide(), attacker.getMatch())._damage.addAndGet((roleIndex(attacker) * R) + roleIndex(target), (long) damage);
	}

	static void onDeath(Fighter dead)
	{
		final MatchStats stats = dead.getMatch().getBatchStats();
		if (stats == null)
		{
			return;
		}
		final TeamStats team = stats.of(dead.getSide(), dead.getMatch());
		team._deaths.incrementAndGet(roleIndex(dead));
		if ((dead.getRole() == SimRole.HEALER) && (team._firstHealerDeath < 0) && (stats._fightStart > 0))
		{
			team._firstHealerDeath = System.currentTimeMillis() - stats._fightStart;
		}
	}

	/**
	 * Once a second while a match fights: whom each fake player targets, and where the archers and mages stand.
	 */
	static void sample(Match match)
	{
		final MatchStats stats = match.getBatchStats();
		if (stats == null)
		{
			return;
		}
		if (stats._fightStart == 0)
		{
			stats._fightStart = System.currentTimeMillis();
		}
		for (Fighter fighter : match.getAllFighters())
		{
			final Creature creature = fighter.getCreature();
			if (creature.isDead() || creature.isPlayer())
			{
				continue;
			}
			final TeamStats team = stats.of(fighter.getSide(), match);
			final WorldObject target = creature.getTarget();
			if ((target instanceof Creature enemy) && !enemy.isDead())
			{
				final Fighter other = TeamVsTeamManager.getFighter(enemy);
				if ((other != null) && (other.getMatch() == match) && (other.getSide() != fighter.getSide()))
				{
					team._targeting.incrementAndGet((roleIndex(fighter) * R) + roleIndex(other));
				}
			}

			if ((fighter.getRole() == SimRole.ARCHER) || (fighter.getRole() == SimRole.NUKER))
			{
				samplePosition(match, fighter, team);
			}
		}
	}

	private static void samplePosition(Match match, Fighter fighter, TeamStats team)
	{
		final Creature creature = fighter.getCreature();
		Creature closest = null;
		double closestDistance = POSITION_SCAN;
		for (Fighter enemy : match.getOther(fighter.getSide()).getFighters())
		{
			final Creature other = enemy.getCreature();
			if (other.isDead() || TeamVsTeamManager.isAtOwnBase(other))
			{
				continue;
			}
			final double distance = creature.calculateDistance2D(other);
			if (distance < closestDistance)
			{
				closest = other;
				closestDistance = distance;
			}
		}
		if (closest == null)
		{
			return; // Not in a fight.
		}

		// Its front: the closest melee member of its party (a tank or a melee).
		final FakePlayerParty party = FakePartyManager.getInstance().getParty(creature);
		double front = Double.MAX_VALUE;
		if (party != null)
		{
			for (Creature member : party.getMembers())
			{
				final Fighter mate = TeamVsTeamManager.getFighter(member);
				if ((member != creature) && !member.isDead() && (mate != null) && ((mate.getRole() == SimRole.TANK) || (mate.getRole() == SimRole.MELEE)) && (member.calculateDistance2D(creature) < 1500))
				{
					front = Math.min(front, member.calculateDistance2D(closest));
				}
			}
		}
		synchronized (team)
		{
			team._rangedSamples++;
			team._rangedDistance += closestDistance;
			if ((front < Double.MAX_VALUE) && (closestDistance < (front - AHEAD_MARGIN)))
			{
				team._rangedAhead++;
			}
		}
	}

	// ---------------------------------------------------------------------------------------------
	// Results
	// ---------------------------------------------------------------------------------------------

	/**
	 * A match of the batch ended: its lines go to the results, and the summary is written again.
	 */
	synchronized void onMatchEnd(Match match, boolean abandoned)
	{
		final MatchStats stats = match.getBatchStats();
		if (stats == null)
		{
			return;
		}
		final StringBuilder results = new StringBuilder();
		final StringBuilder roles = new StringBuilder();
		if (!abandoned)
		{
			for (Side side : new Side[]
			{
				match.getBlue(),
				match.getRed()
			})
			{
				final Side other = match.getOther(side);
				final TeamStats team = stats.of(side, match);
				final TeamStats enemy = stats.of(other, match);
				final int score = side.getScore();
				final int otherScore = other.getScore();
				final long damage = team.damageTotal();
				final long toHealer = team.damageTo(SimRole.HEALER.ordinal());
				final long targeting = team.targetingTotal();
				final StringBuilder byRole = new StringBuilder();
				for (SimRole role : ROLES)
				{
					byRole.append(byRole.length() > 0 ? " " : "").append(role.name().toLowerCase(Locale.ROOT)).append('=').append(team._deaths.get(role.ordinal()));
				}
				final String line = String.join(",", _name, Integer.toString(stats._index), match.getMap().id(), Integer.toString(match.getMinLevel()), Integer.toString(match.getMaxLevel()), side.getName(), team._tactics.name(), enemy._tactics.name(), Integer.toString(score), Integer.toString(otherScore), score > otherScore ? "1" : (score < otherScore ? "0" : "0.5"), Long.toString(damage), Long.toString(toHealer), pct(toHealer, damage), pct(team.targetingTo(SimRole.HEALER.ordinal()), targeting), Long.toString(team.deathsTotal()), Long.toString(team._deaths.get(SimRole.HEALER.ordinal())), team._firstHealerDeath < 0 ? "" : Long.toString(team._firstHealerDeath / 1000), team._rangedSamples == 0 ? "" : String.format(Locale.ROOT, "%.0f", team._rangedDistance / team._rangedSamples), pct(team._rangedAhead, team._rangedSamples), byRole.toString());
				results.append(line).append('\n');
				_results.add(line);
				for (SimRole attacker : ROLES)
				{
					for (SimRole target : ROLES)
					{
						final int i = (attacker.ordinal() * R) + target.ordinal();
						if ((team._damage.get(i) > 0) || (team._targeting.get(i) > 0))
						{
							roles.append(String.join(",", _name, Integer.toString(stats._index), side.getName(), team._tactics.name(), attacker.name(), target.name(), Long.toString(team._damage.get(i)), Long.toString(team._targeting.get(i)))).append('\n');
						}
					}
				}
			}
		}
		try
		{
			Files.writeString(_dir.resolve("results.csv"), results.toString(), StandardCharsets.UTF_8, StandardOpenOption.APPEND);
			Files.writeString(_dir.resolve("roles.csv"), roles.toString(), StandardCharsets.UTF_8, StandardOpenOption.APPEND);
		}
		catch (IOException e)
		{
			LOGGER.log(Level.WARNING, "TeamVsTeamBatch: can't write " + _dir, e);
		}
		final int finished = _finished.incrementAndGet();
		writeSummary();
		LOGGER.info("TeamVsTeamBatch: " + _name + " match " + stats._index + " ended (" + finished + "/" + _total + ")" + (abandoned ? ", stopped" : "") + ".");
	}

	private static String pct(long part, long whole)
	{
		return whole <= 0 ? "" : String.format(Locale.ROOT, "%.1f", (100.0 * part) / whole);
	}

	private void writeSummary()
	{
		// By tactics: matches, wins (a draw counts half), the healer focus and the rest, averaged over the matches.
		final Map<String, double[]> sums = new LinkedHashMap<>();
		for (String line : _results)
		{
			final String[] f = line.split(",", -1);
			final double[] s = sums.computeIfAbsent(f[6], k -> new double[10]);
			s[0]++;
			s[1] += Double.parseDouble(f[10]);
			s[2] += Double.parseDouble(f[8]);
			s[3] += Double.parseDouble(f[9]);
			s[4] += f[13].isEmpty() ? 0 : Double.parseDouble(f[13]);
			s[5] += f[14].isEmpty() ? 0 : Double.parseDouble(f[14]);
			s[6] += Double.parseDouble(f[16]);
			s[7] += f[18].isEmpty() ? 0 : Double.parseDouble(f[18]);
			s[8] += f[19].isEmpty() ? 0 : Double.parseDouble(f[19]);
			s[9] += Double.parseDouble(f[11]);
		}
		final StringBuilder out = new StringBuilder();
		out.append(_name).append(": ").append(_finished.get()).append('/').append(_total).append(" matches done.\n");
		out.append("A = ").append(_a.toSpec()).append("\nB = ").append(_b.toSpec()).append("\n\n");
		out.append(String.format(Locale.ROOT, "%-22s %5s %7s %13s %8s %9s %11s %10s %9s %9s%n", "tactics", "games", "win %", "kills-deaths", "heal dmg%", "heal tgt%", "healer dth", "rng dist", "rng ahd%", "damage"));
		for (Map.Entry<String, double[]> entry : sums.entrySet())
		{
			final double[] s = entry.getValue();
			final double n = s[0];
			final double win = s[1] / n;
			final double ci = 1.96 * Math.sqrt((win * (1 - win)) / n);
			out.append(String.format(Locale.ROOT, "%-22s %5.0f %4.0f±%-3.0f %6.1f-%-6.1f %8.1f %9.1f %11.2f %10.0f %9.1f %9.0f%n", entry.getKey(), n, 100 * win, 100 * ci, s[2] / n, s[3] / n, s[4] / n, s[5] / n, s[6] / n, s[7] / n, s[8] / n, s[9] / n));
		}
		try
		{
			Files.writeString(_dir.resolve("summary.txt"), out.toString(), StandardCharsets.UTF_8);
		}
		catch (IOException e)
		{
			LOGGER.log(Level.WARNING, "TeamVsTeamBatch: can't write " + _dir, e);
		}
	}

	/**
	 * @return the summary so far
	 */
	public String readSummary()
	{
		try
		{
			return Files.exists(_dir.resolve("summary.txt")) ? Files.readString(_dir.resolve("summary.txt")) : _name + ": no match done yet.";
		}
		catch (IOException e)
		{
			return _name + ": " + e.getMessage();
		}
	}
}
