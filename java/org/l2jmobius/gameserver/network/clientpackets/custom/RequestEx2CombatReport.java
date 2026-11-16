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

import org.l2jmobius.gameserver.managers.CombatLogManager;
import org.l2jmobius.gameserver.model.actor.Player;
import org.l2jmobius.gameserver.network.clientpackets.ClientPacket;

/**
 * D0:00FE:15 - the death recap's "Full details". No body. Answered with Ex2DeathDetails, the rest of the last PvP death's fight (the ".combatlog" window when the recap packets are off).
 */
public class RequestEx2CombatReport extends ClientPacket
{
	private static final long INTERVAL_MS = 1000;

	private boolean _whole;

	@Override
	protected void readImpl()
	{
		_whole = remaining() == 0;
	}

	@Override
	protected void runImpl()
	{
		final Player player = getPlayer();
		if ((player == null) || !_whole)
		{
			return;
		}

		// The window is built from the whole fight: once a second is plenty.
		final long now = System.currentTimeMillis();
		if ((now - player.getLastCombatReportRequest()) < INTERVAL_MS)
		{
			return;
		}
		player.setLastCombatReportRequest(now);
		CombatLogManager.showDetails(player);
	}
}
