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
package org.l2jmobius.gameserver.model.classtransfer.objective;

import org.l2jmobius.gameserver.ai.Intention;
import org.l2jmobius.gameserver.model.Location;
import org.l2jmobius.gameserver.model.StatSet;
import org.l2jmobius.gameserver.model.actor.Creature;
import org.l2jmobius.gameserver.model.actor.Npc;
import org.l2jmobius.gameserver.model.actor.Player;
import org.l2jmobius.gameserver.model.classtransfer.ChallengeSession;
import org.l2jmobius.gameserver.model.classtransfer.ChallengeSpawnHolder;
import org.l2jmobius.gameserver.model.classtransfer.ObjectiveDefinition;
import org.l2jmobius.gameserver.model.classtransfer.TrackedNpc;
import org.l2jmobius.gameserver.model.classtransfer.TrackedNpc.NpcRole;

/**
 * One objective of a running challenge. The manager calls these methods while holding the session's monitor, and only for events it already validated (the challenger, in the right instance, on an NPC this objective spawned). A new objective type only needs a subclass and a case in
 * {@link ObjectiveFactory}.
 * @author Mobius
 */
public abstract class AbstractChallengeObjective
{
	protected final ObjectiveDefinition _definition;
	private boolean _forced;
	private boolean _failed;
	private String _failReason;

	protected AbstractChallengeObjective(ObjectiveDefinition definition)
	{
		_definition = definition;
	}

	public ObjectiveDefinition getDefinition()
	{
		return _definition;
	}

	public int getIndex()
	{
		return _definition.getIndex();
	}

	public String getText()
	{
		return _definition.getText();
	}

	/**
	 * Called once, when the objective becomes the current one: spawn its NPCs, give its items.
	 * @param session the session
	 */
	public abstract void start(ChallengeSession session);

	/**
	 * A tracked NPC of this objective died.
	 * @param session the session
	 * @param tracked the NPC
	 * @param credited {@code true} if the challenger (or its summon, or a damage-over-time effect) killed it, {@code false} if something else did
	 */
	public void onKill(ChallengeSession session, TrackedNpc tracked, boolean credited)
	{
	}

	/**
	 * The challenger talked to a tracked NPC of this objective.
	 * @param session the session
	 * @param tracked the NPC
	 * @return {@code true} if the objective used the talk
	 */
	public boolean onTalk(ChallengeSession session, TrackedNpc tracked)
	{
		return false;
	}

	/**
	 * The challenger activated a tracked NPC of this objective.
	 * @param session the session
	 * @param tracked the NPC
	 * @return {@code true} if the objective used the activation
	 */
	public boolean onActivate(ChallengeSession session, TrackedNpc tracked)
	{
		return false;
	}

	/**
	 * The challenger used a challenge item.
	 * @param session the session
	 * @param player the challenger
	 * @param itemId the item
	 * @return {@code true} if the objective handled the item
	 */
	public boolean onItemUse(ChallengeSession session, Player player, int itemId)
	{
		return false;
	}

	/**
	 * Called every second while the objective is current.
	 * @param session the session
	 * @param player the challenger if online, inside the instance and on its feet, {@code null} otherwise
	 */
	public void onTick(ChallengeSession session, Player player)
	{
	}

	/**
	 * The challenger was knocked out (and will return to the entrance).
	 * @param session the session
	 */
	public void onPlayerKnockout(ChallengeSession session)
	{
	}

	public abstract boolean isComplete();

	/**
	 * @return the progress shown next to the objective text, e.g. {@code "7/20"} (may be empty)
	 */
	public abstract String getProgress();

	/**
	 * Called once the objective is complete, before the next one starts. Removes what it spawned by default.
	 * @param session the session
	 */
	public void finish(ChallengeSession session)
	{
		session.despawnObjective(getIndex());
	}

	/**
	 * @return {@code true} if the objective is done, on its own or forced by a GM
	 */
	public boolean isDone()
	{
		return _forced || isComplete();
	}

	/**
	 * Marks the objective done whatever its progress (GM command).
	 */
	public void forceComplete()
	{
		_forced = true;
	}

	public boolean isFailed()
	{
		return _failed;
	}

	public String getFailReason()
	{
		return _failReason;
	}

	protected void fail(String reason)
	{
		_failed = true;
		_failReason = reason;
	}

	// ---------------------------------------------------------------------
	// Helpers
	// ---------------------------------------------------------------------

	protected StatSet params()
	{
		return _definition.getParams();
	}

	/**
	 * @return the objective's own {@code x/y/z/heading} attributes, or {@code null} if it has none
	 */
	protected Location getLocation()
	{
		final StatSet params = params();
		if (!params.contains("x") || !params.contains("y") || !params.contains("z"))
		{
			return null;
		}
		return new Location(params.getInt("x"), params.getInt("y"), params.getInt("z"), params.getInt("heading", 0));
	}

	/**
	 * Spawns every line of {@link ObjectiveDefinition#getSpawns()} (its full count) with the given role.
	 * @param session the session
	 * @param role the role
	 */
	protected void spawnAll(ChallengeSession session, NpcRole role)
	{
		for (ChallengeSpawnHolder holder : _definition.getSpawns())
		{
			for (int i = 0; i < holder.getCount(); i++)
			{
				session.spawn(holder, getIndex(), role);
			}
		}
	}

	/**
	 * @return how many NPCs {@link #spawnAll} spawns
	 */
	protected int getSpawnTotal()
	{
		int total = 0;
		for (ChallengeSpawnHolder holder : _definition.getSpawns())
		{
			total += holder.getCount();
		}
		return total;
	}

	/**
	 * Keeps a slain monster's spawn line populated: spawns a replacement from the same line while fewer than {@code wanted} monsters of this objective are alive.
	 * @param session the session
	 * @param tracked the slain monster
	 * @param wanted how many should be alive
	 */
	protected void replenish(ChallengeSession session, TrackedNpc tracked, int wanted)
	{
		if ((tracked.getHolder() == null) || (wanted <= 0))
		{
			return;
		}
		if (session.getAlive(getIndex(), NpcRole.MOB).size() < wanted)
		{
			session.spawn(tracked.getHolder(), getIndex(), NpcRole.MOB);
		}
	}

	/**
	 * Sends a monster straight at {@code target}.
	 * @param npc the monster
	 * @param target what it attacks
	 */
	protected static void attack(Npc npc, Creature target)
	{
		if ((npc == null) || (target == null) || !npc.isAttackable() || npc.isDead() || target.isDead())
		{
			return;
		}
		npc.asAttackable().addDamageHate(target, 0, 1000);
		npc.getAI().setIntention(Intention.ATTACK, target);
	}

	/**
	 * @param seconds a duration in seconds
	 * @return {@code m:ss}
	 */
	protected static String formatTime(int seconds)
	{
		final int safe = Math.max(0, seconds);
		return (safe / 60) + ":" + ((safe % 60) < 10 ? "0" : "") + (safe % 60);
	}
}
