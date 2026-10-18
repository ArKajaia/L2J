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
package org.l2jmobius.gameserver.util;

import org.l2jmobius.gameserver.config.custom.FakePlayerPvpConfig;
import org.l2jmobius.gameserver.data.xml.FakePlayerPvpData;
import org.l2jmobius.gameserver.managers.FakePlayerPvpManager;
import org.l2jmobius.gameserver.model.actor.Npc;
import org.l2jmobius.gameserver.model.actor.Player;
import org.l2jmobius.gameserver.model.actor.holders.npc.FakePlayerPvpBuild;
import org.l2jmobius.gameserver.model.interfaces.ILocational;

/**
 * Spawns a fake player that hunts one player for a scripted quest: a trial duelist of a random build. It stays flagged, fights only its target, pays no PvP count, karma or reward, and never walks back after dying.
 */
public final class FakeRivalUtil
{
	private FakeRivalUtil()
	{
	}

	/**
	 * @param level its level
	 * @param location where
	 * @param target the player it hunts
	 * @param title its title, may be {@code null}
	 * @return the fake player, {@code null} if fake players are disabled or it could not be created
	 */
	public static Npc spawnRival(int level, ILocational location, Player target, String title)
	{
		if (!FakePlayerPvpConfig.ENABLED || (target == null))
		{
			return null;
		}
		final FakePlayerPvpBuild build = FakePlayerPvpData.getInstance().getRandomBuild();
		if (build == null)
		{
			return null;
		}
		return FakePlayerPvpManager.getInstance().spawnTrialDuelist(build, level, null, title, location.getX(), location.getY(), location.getZ(), 0, target);
	}
}
