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
package org.l2jmobius.gameserver.model.zone.type;

import org.l2jmobius.gameserver.config.PvpConfig;
import org.l2jmobius.gameserver.config.custom.PvpSpotsConfig;
import org.l2jmobius.gameserver.managers.PvpSpotManager;
import org.l2jmobius.gameserver.managers.ScriptManager;
import org.l2jmobius.gameserver.model.actor.Creature;
import org.l2jmobius.gameserver.model.actor.Npc;
import org.l2jmobius.gameserver.model.actor.Player;
import org.l2jmobius.gameserver.model.script.Quest;
import org.l2jmobius.gameserver.model.zone.ZoneId;
import org.l2jmobius.gameserver.model.zone.ZoneType;
import org.l2jmobius.gameserver.network.serverpackets.ExShowScreenMessage;

/**
 * A PvP spot (see data/zones/pvp_spots.xml and {@link PvpSpotManager}): an open-world area where players come to fight. Unlike an arena (ArenaZone) it is a real fight: kills count as PvP kills and go to the PvP rewards, but nobody ever gets karma for one.
 * <ul>
 * <li>Everyone inside is flagged (purple), so attacked without Ctrl, from the moment they step in until they leave; the flag then runs out like after any fight.</li>
 * <li>A kill inside is a PvP, never a PK (see {@code Player#onKillUpdatePvPKarma} and {@code Npc#doDie}).</li>
 * <li>With PvpSpotNoDeathPenalty, dying there costs no exp and gives no death penalty.</li>
 * </ul>
 * Parameters: {@code spotMinLevel} and {@code spotMaxLevel}, the levels of the fake players that come to fight there.
 */
public class PvpSpotZone extends ZoneType
{
	private int _spotMinLevel = 1;
	private int _spotMaxLevel = 85;

	public PvpSpotZone(int id)
	{
		super(id);
	}

	@Override
	public void setParameter(String name, String value)
	{
		switch (name)
		{
			case "spotMinLevel":
			{
				_spotMinLevel = Math.max(1, Math.min(85, Integer.parseInt(value)));
				break;
			}
			case "spotMaxLevel":
			{
				_spotMaxLevel = Math.max(1, Math.min(85, Integer.parseInt(value)));
				break;
			}
			default:
			{
				super.setParameter(name, value);
			}
		}
	}

	/**
	 * @return the lowest level of the fake players that come to fight here
	 */
	public int getSpotMinLevel()
	{
		return Math.min(_spotMinLevel, _spotMaxLevel);
	}

	/**
	 * @return the highest level of the fake players that come to fight here
	 */
	public int getSpotMaxLevel()
	{
		return Math.max(_spotMinLevel, _spotMaxLevel);
	}

	@Override
	protected void onEnter(Creature creature)
	{
		if (!PvpSpotsConfig.ENABLED)
		{
			return;
		}

		final boolean wasInside = creature.isInsideZone(ZoneId.PVP_SPOT);
		creature.setInsideZone(ZoneId.PVP_SPOT, true);
		if (creature.isPlayer())
		{
			final Player player = creature.asPlayer();
			keepFlag(player);
			if (!wasInside)
			{
				player.sendPacket(new ExShowScreenMessage("You have entered the PvP spot " + getName() + ".", 4000));
				player.sendMessage("PvP spot " + getName() + ": you stay flagged while you are here, and kills here never give karma.");
			}
		}
		else if (creature.isPvpFakePlayer())
		{
			// A fake player that walks in (chasing someone, or come to fight) is flagged like a player.
			final Npc fake = creature.asNpc();
			if (!fake.isScriptValue(1))
			{
				fake.setScriptValue(1);
				fake.broadcastInfo();
			}
		}
	}

	@Override
	protected void onExit(Creature creature)
	{
		if (!creature.isInsideZone(ZoneId.PVP_SPOT))
		{
			return; // Entered while the spots were off.
		}

		creature.setInsideZone(ZoneId.PVP_SPOT, false);
		if (creature.isInsideZone(ZoneId.PVP_SPOT))
		{
			return; // Still in another spot.
		}

		if (creature.isPlayer())
		{
			// The flag runs out like after any fight.
			final Player player = creature.asPlayer();
			if (player.getPvpFlag() > 0)
			{
				player.setPvpFlagLasts(System.currentTimeMillis() + PvpConfig.PVP_NORMAL_TIME);
			}
			player.sendMessage("You have left the PvP spot " + getName() + ".");
		}
		else if (creature.isPvpFakePlayer() && !creature.isDead())
		{
			// Its flag runs out a moment later, like a player's (a spot fighter keeps it, it doesn't stay outside).
			final Npc fake = creature.asNpc();
			if (!PvpSpotManager.isSpotFighter(fake) && !fake.isTrialDuelist() && fake.isScriptValue(1))
			{
				final Quest flagTask = ScriptManager.getInstance().getScript("PvpFlaggingStopTask");
				if (flagTask != null)
				{
					flagTask.notifyEvent("FINISH_FLAG", fake, null);
				}
			}
		}
	}

	/**
	 * Keeps the flag of a player inside a spot up until they leave it.
	 * @param player the player
	 */
	public static void keepFlag(Player player)
	{
		player.setPvpFlagLasts(Long.MAX_VALUE);
		if (player.getPvpFlag() == 0)
		{
			player.startPvPFlag();
		}
		else
		{
			player.updatePvPFlag(1);
		}
	}
}
