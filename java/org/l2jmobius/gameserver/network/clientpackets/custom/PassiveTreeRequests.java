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
package org.l2jmobius.gameserver.network.clientpackets.custom;

import java.util.Map;
import java.util.WeakHashMap;

import org.l2jmobius.gameserver.model.actor.Player;
import org.l2jmobius.gameserver.network.GameClient;
import org.l2jmobius.gameserver.network.serverpackets.Ex2PassiveTreeResult;
import org.l2jmobius.gameserver.network.serverpackets.Ex2PassiveTreeState;

/**
 * What the passive tree requests share: the flood limits per connection, and the answer to an action.
 */
final class PassiveTreeRequests
{
	/** Allocate, respec, reset, template, state: at most one per this many milliseconds; more are dropped. */
	static final long ACTION_INTERVAL_MS = 100;
	/** Chunk requests: at most this many per CHUNK_WINDOW_MS (a whole fetch is about 30). */
	static final int CHUNK_BUDGET = 400;
	static final long CHUNK_WINDOW_MS = 60_000;

	private static final class Limits
	{
		long lastAction;
		long chunkWindow;
		int chunks;
	}

	/** Weak keys: an entry goes with its connection. */
	private static final Map<GameClient, Limits> LIMITS = new WeakHashMap<>();

	private PassiveTreeRequests()
	{
	}

	private static Limits limits(GameClient client)
	{
		return LIMITS.computeIfAbsent(client, k -> new Limits());
	}

	/** @return {@code false} if this client acted less than {@link #ACTION_INTERVAL_MS} ago: drop the request */
	static boolean tryAction(GameClient client)
	{
		final long now = System.currentTimeMillis();
		synchronized (LIMITS)
		{
			final Limits limits = limits(client);
			if ((now - limits.lastAction) < ACTION_INTERVAL_MS)
			{
				return false;
			}
			limits.lastAction = now;
			return true;
		}
	}

	/** @return {@code false} if this client asked for more chunks than a few whole fetches in the last minute: drop the request */
	static boolean tryChunk(GameClient client)
	{
		final long now = System.currentTimeMillis();
		synchronized (LIMITS)
		{
			final Limits limits = limits(client);
			if ((now - limits.chunkWindow) >= CHUNK_WINDOW_MS)
			{
				limits.chunkWindow = now;
				limits.chunks = 0;
			}
			return ++limits.chunks <= CHUNK_BUDGET;
		}
	}

	/** Every action is answered with what became of it and the tree as it is now. */
	static void answer(Player player, int action, int reason, int id)
	{
		player.sendPacket(new Ex2PassiveTreeResult(action, reason, id));
		player.sendPacket(new Ex2PassiveTreeState(player));
	}
}
