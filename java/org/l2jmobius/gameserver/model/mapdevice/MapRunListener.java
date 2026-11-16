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
package org.l2jmobius.gameserver.model.mapdevice;

import java.util.List;

import org.l2jmobius.gameserver.model.actor.Creature;
import org.l2jmobius.gameserver.model.actor.instance.Monster;

/**
 * Watches a map run that fake players fight in instead of players (the map simulation, {@code MapSimulation}): the run tells it what happens, and asks it who fights in it. A run without one is a players' run.
 * @author Mobius
 */
public interface MapRunListener
{
	/**
	 * @param run the run
	 * @return the fighters inside it that stand in for players (a fake player and its servitor), alive or not
	 */
	List<Creature> getFighters(MapRun run);

	/**
	 * @param creature anyone
	 * @return {@code true} if it fights the run as a player would: one of {@link #getFighters}
	 */
	boolean isFighter(Creature creature);

	/**
	 * @param killer who dealt the killing blow (a fighter, its servitor, a burst...), may be {@code null}
	 * @return the fighter credited with a kill, {@code null} for none
	 */
	Creature getCredited(Creature killer);

	/**
	 * A monster of the run takes damage (after the map's own changes to it).
	 * @param run the run
	 * @param monster the monster
	 * @param attacker who deals it, may be {@code null}
	 * @param damage the damage
	 */
	default void onMonsterDamaged(MapRun run, Monster monster, Creature attacker, double damage)
	{
	}

	/**
	 * A monster of the run died (counted or not), after the run took it into account.
	 * @param run the run
	 * @param victim the monster
	 * @param killer who dealt the killing blow, may be {@code null}
	 * @param counted {@code true} if it counted toward completing the map
	 */
	void onMonsterDied(MapRun run, Monster victim, Creature killer, boolean counted);

	/**
	 * A fighter of the run died.
	 * @param run the run
	 * @param fighter the fighter
	 * @param killer who killed it, may be {@code null}
	 */
	void onFighterDied(MapRun run, Creature fighter, Creature killer);

	/**
	 * The run dropped a map (it counts against the run's map budget).
	 * @param run the run
	 * @param tier the map's tier
	 * @param source what dropped it: monster, completion, guardian, speedrun, coffer
	 */
	void onMapDropped(MapRun run, int tier, String source);

	/**
	 * The run was completed.
	 * @param run the run
	 */
	void onCompleted(MapRun run);

	/**
	 * The run closed.
	 * @param run the run
	 * @param reason why, may be {@code null}
	 */
	void onClosed(MapRun run, String reason);
}
