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

import org.l2jmobius.gameserver.managers.conquest.ConquestManager;
import org.l2jmobius.gameserver.model.actor.Creature;
import org.l2jmobius.gameserver.model.actor.Player;
import org.l2jmobius.gameserver.model.actor.enums.creature.Team;

/**
 * The faction rules of the team events ({@link TeamVsTeamManager}, {@link ConquestManager}) for the combat code: the other team is attacked without Ctrl while the fight is on, the own team can't be hurt even with Ctrl, and nobody outside the event is
 * hurt by or hurts its fighters. Cheap when no event runs.
 */
public final class TeamEventRules
{
	private TeamEventRules()
	{
	}

	/**
	 * @return {@code true} while a team event goes on
	 */
	public static boolean isActive()
	{
		return TeamVsTeamManager.ACTIVE || ConquestManager.ACTIVE || RaceWarsManager.ACTIVE;
	}

	/**
	 * @param creature a creature (a summon counts as its owner)
	 * @return {@code true} if it fights in a Conquest match: it keeps its team's look (no PvP flag, no karma name), and killing it is a PvP, never a PK
	 */
	public static boolean isConquestFighter(Creature creature)
	{
		return ConquestManager.ACTIVE && (ConquestManager.getFighter(creature) != null);
	}

	/**
	 * @param a a creature
	 * @param b another creature
	 * @return {@code true} if at least one of them fights in a team event: the event's rules decide whether they fight, not the usual ones
	 */
	public static boolean isInvolved(Creature a, Creature b)
	{
		return (TeamVsTeamManager.ACTIVE && (TeamVsTeamManager.getRelation(a, b) != TeamVsTeamManager.Relation.NONE)) || (ConquestManager.ACTIVE && (ConquestManager.getRelation(a, b) != TeamVsTeamManager.Relation.NONE)) || RaceWarsManager.isInvolved(a, b);
	}

	/**
	 * @param attacker a creature
	 * @param target another creature
	 * @return {@code true} if the attack is not allowed by a team event (the own team, anyone outside it, before or after the fight)
	 */
	public static boolean isForbidden(Creature attacker, Creature target)
	{
		return TeamVsTeamManager.isForbidden(attacker, target) || ConquestManager.isForbidden(attacker, target) || RaceWarsManager.isForbidden(attacker, target);
	}

	/**
	 * @param attacker a creature
	 * @param target another creature
	 * @return {@code true} if they are enemies of a team event whose fight goes on: attacked without Ctrl
	 */
	public static boolean isEnemy(Creature attacker, Creature target)
	{
		return TeamVsTeamManager.isEnemy(attacker, target) || ConquestManager.isEnemy(attacker, target) || RaceWarsManager.isEnemy(attacker, target);
	}

	/**
	 * Whether a kill counts as a PvP of an event (see {@code Player#onKillUpdatePvPKarma} and {@code Npc#doDie}): the event fields are PvP zones, where a kill counts nothing otherwise.
	 * <ul>
	 * <li>Team vs Team, Deathmatch, Conquest and Race Wars: the victim (a player or fake player) is an enemy of the killer in the same match, whatever the phase (the fight may have just been decided by this kill).</li>
	 * <li>The scripted events (Capture the Flag, the Deathmatch script...): two players on the event, not of the same team.</li>
	 * </ul>
	 * @param killer who killed (a summon counts as its owner)
	 * @param victim who died
	 * @return {@code true} if the killer earns a PvP for it
	 */
	public static boolean isEventKill(Creature killer, Creature victim)
	{
		if ((killer == null) || (victim == null) || (killer == victim))
		{
			return false;
		}
		if ((TeamVsTeamManager.getRelation(killer, victim) == TeamVsTeamManager.Relation.ENEMY) || (ConquestManager.getRelation(killer, victim) == TeamVsTeamManager.Relation.ENEMY) || (RaceWarsManager.getRelation(killer, victim) == TeamVsTeamManager.Relation.ENEMY))
		{
			return true;
		}
		if (isInvolved(killer, victim))
		{
			return false; // Its own team, or someone outside its match.
		}
		final Player killerPlayer = killer.asPlayer();
		final Player victimPlayer = victim.isPlayer() ? victim.asPlayer() : null;
		return (killerPlayer != null) && (victimPlayer != null) && (killerPlayer != victimPlayer) && killerPlayer.isOnEvent() && victimPlayer.isOnEvent() && ((killerPlayer.getTeam() == Team.NONE) || (killerPlayer.getTeam() != victimPlayer.getTeam()));
	}

	/**
	 * @param a a creature
	 * @param b another creature
	 * @return {@code true} if both are on an event: a kill between them is never a PK
	 */
	public static boolean isEventFight(Creature a, Creature b)
	{
		if (isInvolved(a, b))
		{
			return true;
		}
		final Player pa = a != null ? a.asPlayer() : null;
		final Player pb = b != null ? b.asPlayer() : null;
		return (pa != null) && (pb != null) && pa.isOnEvent() && pb.isOnEvent();
	}

	/**
	 * Called by the combat log when a creature hurt another: a fighter under spawn protection loses it.
	 * @param attacker who dealt the damage
	 */
	public static void onDamageDealt(Creature attacker)
	{
		if (TeamVsTeamManager.ACTIVE)
		{
			TeamVsTeamManager.getInstance().onDamageDealt(attacker);
		}
		if (ConquestManager.ACTIVE)
		{
			ConquestManager.getInstance().onDamageDealt(attacker);
		}
	}

	/**
	 * Called by {@code Creature#doDie}.
	 * @param victim who died
	 * @param killer who killed it
	 */
	public static void onDeath(Creature victim, Creature killer)
	{
		if (TeamVsTeamManager.ACTIVE)
		{
			TeamVsTeamManager.getInstance().onDeath(victim, killer);
		}
		if (ConquestManager.ACTIVE)
		{
			ConquestManager.getInstance().onDeath(victim, killer);
		}
		if (RaceWarsManager.ACTIVE)
		{
			RaceWarsManager.getInstance().onDeath(victim, killer);
		}
	}
}
