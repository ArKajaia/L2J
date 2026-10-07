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
package custom.NightMarket;

import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;

import org.l2jmobius.gameserver.config.custom.NightCycleConfig;
import org.l2jmobius.gameserver.data.SpawnTable;
import org.l2jmobius.gameserver.data.xml.MultisellData;
import org.l2jmobius.gameserver.managers.NightCycleManager;
import org.l2jmobius.gameserver.model.actor.Npc;
import org.l2jmobius.gameserver.model.actor.Player;
import org.l2jmobius.gameserver.model.events.EventType;
import org.l2jmobius.gameserver.model.events.ListenerRegisterType;
import org.l2jmobius.gameserver.model.events.annotations.RegisterEvent;
import org.l2jmobius.gameserver.model.events.annotations.RegisterType;
import org.l2jmobius.gameserver.model.events.holders.OnNightPhaseChange;
import org.l2jmobius.gameserver.model.script.Script;
import org.l2jmobius.gameserver.model.spawns.Spawn;

/**
 * The Night Market: Varro the Moonmonger opens his stalls in Giran at nightfall and packs up at dawn (see {@link NightCycleManager}). He sells Sealed Caches, the low and high grade luminous lures fishermen don't stock, and other night wares for hot zone coins (multisells 900360 and 900361).
 * <p>
 * His spawn is kept in the spawn table, so a reload of this script during the night finds him instead of bringing a second Varro.
 */
public class NightMarket extends Script
{
	private static final int MERCHANT = 900360;
	// In Giran, at the end of the row of custom npcs (Class Master, Arena Master, Arena Merchant, Master Blacksmith, Hotzone Teleporter).
	private static final int X = 82208;
	private static final int Y = 148778;
	private static final int Z = -3493;
	private static final int HEADING = 64000;
	private static final int[] LISTS =
	{
		900360,
		900361
	};

	private NightMarket()
	{
		addStartNpc(MERCHANT);
		addFirstTalkId(MERCHANT);
		addTalkId(MERCHANT);

		// The night-phase event that opens the market is missed when the server starts (or this script reloads) during the night.
		if (isNightMarketTime())
		{
			open(false);
		}
	}

	private static boolean isNightMarketTime()
	{
		return NightCycleConfig.ENABLED && NightCycleConfig.NIGHT_MARKET_ENABLED && NightCycleManager.getInstance().isNight();
	}

	@RegisterEvent(EventType.ON_NIGHT_PHASE_CHANGE)
	@RegisterType(ListenerRegisterType.GLOBAL)
	public void onNightPhaseChange(OnNightPhaseChange event)
	{
		if (event.getPhase().isNight())
		{
			if (!event.getPreviousPhase().isNight() && isNightMarketTime())
			{
				open(true);
			}
		}
		else
		{
			close();
		}
	}

	/**
	 * Varro sets up his stalls, unless he is already there.
	 * @param announce {@code true} to tell everyone
	 */
	private void open(boolean announce)
	{
		for (Spawn spawn : SpawnTable.getInstance().getSpawns(MERCHANT))
		{
			final Npc npc = spawn.getLastSpawn();
			if ((npc != null) && npc.isSpawned())
			{
				return;
			}
		}

		try
		{
			final Spawn spawn = new Spawn(MERCHANT);
			spawn.setXYZ(X, Y, Z);
			spawn.setHeading(HEADING);
			spawn.setAmount(1);
			spawn.stopRespawn();
			SpawnTable.getInstance().addSpawn(spawn);
			if (spawn.doSpawn(false) == null)
			{
				SpawnTable.getInstance().removeSpawn(spawn);
				return;
			}
		}
		catch (Exception e)
		{
			LOGGER.log(Level.WARNING, getClass().getSimpleName() + ": Could not spawn the Night Market merchant.", e);
			return;
		}

		if (announce)
		{
			NightCycleManager.announce("The Night Market has opened in Giran. Varro the Moonmonger sells his wares until dawn.");
		}
	}

	/**
	 * Dawn: Varro packs up.
	 */
	private void close()
	{
		for (Spawn spawn : new ArrayList<>(SpawnTable.getInstance().getSpawns(MERCHANT)))
		{
			spawn.stopRespawn();
			final Npc npc = spawn.getLastSpawn();
			if ((npc != null) && npc.isSpawned())
			{
				npc.deleteMe();
			}
			SpawnTable.getInstance().removeSpawn(spawn);
		}
	}

	@Override
	public String onEvent(String event, Npc npc, Player player)
	{
		if ((npc == null) || (player == null) || (npc.getId() != MERCHANT))
		{
			return null;
		}

		if (event.startsWith("multisell "))
		{
			try
			{
				final int listId = Integer.parseInt(event.substring("multisell ".length()).trim());
				for (int allowed : LISTS)
				{
					if (allowed == listId)
					{
						MultisellData.getInstance().separateAndSend(listId, player, npc, false);
						break;
					}
				}
			}
			catch (NumberFormatException e)
			{
				// Not a list id.
			}
			return null;
		}

		final List<String> pages = List.of("900360.htm", "900360-1.htm");
		return pages.contains(event) ? event : null;
	}

	@Override
	public String onFirstTalk(Npc npc, Player player)
	{
		return "900360.htm";
	}

	public static void main(String[] args)
	{
		new NightMarket();
	}
}
