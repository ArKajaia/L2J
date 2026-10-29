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
package org.l2jmobius.gameserver.managers.conquest;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.l2jmobius.gameserver.config.custom.ConquestConfig;
import org.l2jmobius.gameserver.model.Location;
import org.l2jmobius.gameserver.model.actor.Npc;
import org.l2jmobius.gameserver.model.conquest.ConquestMap;

/**
 * A plain commander: it sees the whole field and shares its parties out among the capture points.
 * <ol>
 * <li>Each capture point gets a need: one the team doesn't hold enough parties to outnumber the enemies seen at it by a quarter (the capture rule's majority), at least one; one it holds under threat enough to hold it; one it holds in peace a party to guard it,
 * if any are left over.</li>
 * <li>The points are served by priority: the threatened ones of its own, then the enemy's, then the neutral ones, then the guards. Every point gets a first party before any gets a second.</li>
 * <li>A party keeps the point it was sent to while that point still needs it; the others go to the nearest point in need (by the way they would walk).</li>
 * <li>Parties left over go for the biggest enemy group near them.</li>
 * </ol>
 * The parties are spread around each point so they don't stand on each other.
 */
public class GenericConquestCommander implements ConquestCommander
{
	/** Enemies (and allies) this far around a point's radius count as at it. */
	private static final int THREAT_RANGE = 1500;
	/** Enemy groups: the enemies are counted in cells this wide. */
	private static final int GROUP_CELL = 1500;
	/** An attack order follows its enemy group when the group moved this far. */
	private static final int ATTACK_UPDATE = 600;
	/** The priorities. */
	private static final int DEFEND = 4;
	private static final int TAKE_ENEMY = 3;
	private static final int TAKE_NEUTRAL = 2;
	private static final int GUARD = 1;

	/** A capture point and what it needs from the team. */
	private static class Objective
	{
		final ConquestCapture capture;
		final ConquestParty.OrderKind kind;
		final int priority;
		final int need;
		final List<ConquestParty> assigned = new ArrayList<>();

		Objective(ConquestCapture capture, ConquestParty.OrderKind kind, int priority, int need)
		{
			this.capture = capture;
			this.kind = kind;
			this.priority = priority;
			this.need = need;
		}
	}

	@Override
	public String getName()
	{
		return "generic";
	}

	@Override
	public void command(ConquestMatch match, ConquestTeam team, long now)
	{
		final ConquestTeam enemy = match.getOther(team);
		final List<ConquestParty> free = new ArrayList<>();
		for (ConquestParty party : team.getParties())
		{
			if (party.getLocation() != null)
			{
				free.add(party);
			}
		}
		if (free.isEmpty())
		{
			return;
		}

		// What each point needs.
		final List<Objective> objectives = new ArrayList<>();
		for (ConquestCapture capture : match.getCaptures())
		{
			final Location at = capture.getLocation();
			final int range = capture.getRadius() + THREAT_RANGE;
			final int enemies = match.getNear(enemy, at.getX(), at.getY(), range).size();
			final int outnumber = partiesFor((int) Math.ceil((enemies * (100 + ConquestConfig.CAPTURE_MAJORITY_PERCENT)) / 100.0) + 1);
			if (capture.getOwner() == team.getTeam())
			{
				if (enemies > 0)
				{
					objectives.add(new Objective(capture, ConquestParty.OrderKind.DEFEND, DEFEND, outnumber));
				}
				else
				{
					objectives.add(new Objective(capture, ConquestParty.OrderKind.DEFEND, GUARD, 1));
				}
			}
			else
			{
				objectives.add(new Objective(capture, ConquestParty.OrderKind.CAPTURE, capture.getOwner() == enemy.getTeam() ? TAKE_ENEMY : TAKE_NEUTRAL, Math.max(1, outnumber)));
			}
		}
		objectives.sort((a, b) -> Integer.compare(b.priority, a.priority));

		// The parties keep their point while it still needs them.
		final Map<String, Objective> byPoint = new HashMap<>();
		for (Objective objective : objectives)
		{
			byPoint.put(objective.capture.getName(), objective);
		}
		for (ConquestParty party : new ArrayList<>(free))
		{
			final ConquestParty.Order order = party.getOrder();
			final Objective objective = order != null && order.point() != null ? byPoint.get(order.point()) : null;
			if ((objective != null) && (objective.assigned.size() < objective.need))
			{
				objective.assigned.add(party);
				free.remove(party);
			}
		}

		// A first party for every point before a second for any, guards last.
		for (int round = 0; round < 2; round++)
		{
			for (Objective objective : objectives)
			{
				final int wanted = round == 0 ? 1 : objective.need;
				if ((round == 0) && (objective.priority == GUARD))
				{
					continue;
				}
				while ((objective.assigned.size() < wanted) && !free.isEmpty())
				{
					final ConquestParty closest = closest(match, free, objective.capture.getLocation());
					objective.assigned.add(closest);
					free.remove(closest);
				}
			}
		}

		for (Objective objective : objectives)
		{
			for (ConquestParty party : objective.assigned)
			{
				order(match, party, objective.kind, objective.capture, now);
			}
		}

		// The rest go for the biggest enemy group near them, else help at the nearest point the team doesn't hold.
		final List<int[]> groups = enemyGroups(enemy);
		for (ConquestParty party : free)
		{
			final Location at = party.getLocation();
			int[] best = null;
			double bestScore = Double.MAX_VALUE;
			for (int[] group : groups)
			{
				final double score = Math.hypot(group[0] - at.getX(), group[1] - at.getY()) / group[3];
				if (score < bestScore)
				{
					best = group;
					bestScore = score;
				}
			}
			if (best != null)
			{
				attack(match, party, new Location(best[0], best[1], best[2]), now);
			}
			else if (!objectives.isEmpty())
			{
				order(match, party, objectives.get(0).kind, objectives.get(0).capture, now);
			}
		}
	}

	/** @return how many parties of 5 make that many fighters */
	private static int partiesFor(int fighters)
	{
		return (int) Math.ceil(fighters / (double) ConquestManager.PARTY_SIZE);
	}

	private static ConquestParty closest(ConquestMatch match, List<ConquestParty> parties, Location target)
	{
		ConquestParty best = parties.get(0);
		double bestDistance = Double.MAX_VALUE;
		for (ConquestParty party : parties)
		{
			final double distance = match.getNav().walkDistance(party.getLocation(), target);
			if (distance < bestDistance)
			{
				best = party;
				bestDistance = distance;
			}
		}
		return best;
	}

	/**
	 * Sends a party to a capture point, to a spot of its own around it (the same as before if it was already sent there).
	 */
	private static void order(ConquestMatch match, ConquestParty party, ConquestParty.OrderKind kind, ConquestCapture capture, long now)
	{
		final ConquestParty.Order old = party.getOrder();
		if ((old != null) && (old.kind() == kind) && capture.getName().equals(old.point()))
		{
			return;
		}

		// A spot of its own on the point: around it by its number, half way out.
		final Location center = capture.getLocation();
		final double angle = party.getId() * 2.399963; // the golden angle, so any number of parties spread evenly
		final double distance = capture.getRadius() * 0.5;
		final int x = center.getX() + (int) (Math.cos(angle) * distance);
		final int y = center.getY() + (int) (Math.sin(angle) * distance);
		party.setOrder(new ConquestParty.Order(kind, inside(match, x, y, center.getZ()), capture.getName(), now));
	}

	private static void attack(ConquestMatch match, ConquestParty party, Location target, long now)
	{
		final ConquestParty.Order old = party.getOrder();
		if ((old != null) && (old.kind() == ConquestParty.OrderKind.ATTACK) && (Math.hypot(old.target().getX() - target.getX(), old.target().getY() - target.getY()) < ATTACK_UPDATE))
		{
			return;
		}
		party.setOrder(new ConquestParty.Order(ConquestParty.OrderKind.ATTACK, inside(match, target.getX(), target.getY(), target.getZ()), null, now));
	}

	/** @return the walkable ground nearest the spot, inside the map's area */
	private static Location inside(ConquestMatch match, int x, int y, int z)
	{
		final ConquestMap map = match.getMap();
		final int inset = 200;
		final int cx = Math.max(map.minX() + inset, Math.min(map.maxX() - inset, x));
		final int cy = Math.max(map.minY() + inset, Math.min(map.maxY() - inset, y));
		return match.getNav().nearestGround(cx, cy, z);
	}

	/**
	 * @return the enemy groups: {x, y, z, fighters} of each cell with living enemies out of their bases
	 */
	private static List<int[]> enemyGroups(ConquestTeam enemy)
	{
		final Map<Long, long[]> cells = new HashMap<>();
		for (ConquestFighter fighter : enemy.getFighters())
		{
			final Npc npc = fighter.getNpc();
			if (!fighter.isAlive() || enemy.isInBase(npc.getX(), npc.getY()))
			{
				continue;
			}
			final long key = (((long) Math.floorDiv(npc.getX(), GROUP_CELL)) << 32) | (Math.floorDiv(npc.getY(), GROUP_CELL) & 0xFFFFFFFFL);
			final long[] sum = cells.computeIfAbsent(key, _ -> new long[4]);
			sum[0] += npc.getX();
			sum[1] += npc.getY();
			sum[2] += npc.getZ();
			sum[3]++;
		}
		final List<int[]> groups = new ArrayList<>();
		for (long[] sum : cells.values())
		{
			groups.add(new int[]
			{
				(int) (sum[0] / sum[3]),
				(int) (sum[1] / sum[3]),
				(int) (sum[2] / sum[3]),
				(int) sum[3]
			});
		}
		return groups;
	}
}
