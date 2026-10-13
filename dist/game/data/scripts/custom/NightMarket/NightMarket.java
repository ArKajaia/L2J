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
import java.util.Map;
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
 * The Night Market: Varro the Moonmonger and Gorm the Moonsmith open their stalls in Giran at nightfall and pack up at dawn (see {@link NightCycleManager}).
 * <ul>
 * <li>Varro trades Sealed Caches up a tier (never sells them: they hold hot zone coins), sells the low and high grade luminous lures fishermen don't stock and other night wares for hot zone coins (multisells 900360 and 900361), and changes hot zone coins into Ancient Adena (multisell 900362) at a little less than the standard way gives.</li>
 * <li>Gorm is the Night Forge: he sells Mammon's Varnish Enhancer for Adena (multisell 900370), finishes Foundation items with it (retail list 311262513) and unseals masterwork items (multisell 900371, the masterwork entries of the retail unseal lists, at their prices).</li>
 * </ul>
 * Their spawns are kept in the spawn table, so a reload of this script during the night finds them instead of bringing a second Varro or Gorm.
 */
public class NightMarket extends Script
{
	private static final int MERCHANT = 900360;
	private static final int SMITH = 900370;

	/** Where a night npc stands. (Scripts compile at Java 8 level, so no records.) */
	private static final class Stall
	{
		final int npcId;
		final int x;
		final int y;
		final int z;
		final int heading;

		Stall(int npcId, int x, int y, int z, int heading)
		{
			this.npcId = npcId;
			this.x = x;
			this.y = y;
			this.z = z;
			this.heading = heading;
		}
	}

	// In Giran, at the end of the row of custom npcs (Class Master, Arena Master, Arena Merchant, Master Blacksmith, Hotzone Teleporter): Varro first, Gorm one step on.
	private static final List<Stall> STALLS = List.of( //
		new Stall(MERCHANT, 82208, 148778, -3493, 64000), //
		new Stall(SMITH, 82200, 148835, -3493, 64000));

	/** The multisells each npc may open. */
	private static final Map<Integer, int[]> LISTS = Map.of( //
		MERCHANT, new int[]
		{
			900360,
			900361,
			900362
		}, //
		SMITH, new int[]
		{
			900370, // Mammon's Varnish Enhancer
			900371, // Unseal masterwork items
			311262513 // Finish a Foundation item (retail list, Gorm is on its npc list)
		});
	/** Unsealing opens inventory-only, so the list shows just the sealed pieces the player carries and the unsealed item keeps its enchant level (like the Master Blacksmith's unseal services). */
	private static final int UNSEAL_LIST = 900371;

	private NightMarket()
	{
		for (Stall stall : STALLS)
		{
			addStartNpc(stall.npcId);
			addFirstTalkId(stall.npcId);
			addTalkId(stall.npcId);
		}

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
	 * Varro and Gorm set up their stalls, unless they are already there.
	 * @param announce {@code true} to tell everyone
	 */
	private void open(boolean announce)
	{
		boolean opened = false;
		for (Stall stall : STALLS)
		{
			if (isUp(stall.npcId))
			{
				continue;
			}

			try
			{
				final Spawn spawn = new Spawn(stall.npcId);
				spawn.setXYZ(stall.x, stall.y, stall.z);
				spawn.setHeading(stall.heading);
				spawn.setAmount(1);
				spawn.stopRespawn();
				SpawnTable.getInstance().addSpawn(spawn);
				if (spawn.doSpawn(false) == null)
				{
					SpawnTable.getInstance().removeSpawn(spawn);
					continue;
				}
				opened = true;
			}
			catch (Exception e)
			{
				LOGGER.log(Level.WARNING, getClass().getSimpleName() + ": Could not spawn the Night Market npc " + stall.npcId + ".", e);
			}
		}

		if (opened && announce)
		{
			NightCycleManager.announce("The Night Market has opened in Giran. Varro the Moonmonger sells his wares and Gorm the Moonsmith has lit the Night Forge until dawn.");
		}
	}

	private boolean isUp(int npcId)
	{
		for (Spawn spawn : SpawnTable.getInstance().getSpawns(npcId))
		{
			final Npc npc = spawn.getLastSpawn();
			if ((npc != null) && npc.isSpawned())
			{
				return true;
			}
		}
		return false;
	}

	/**
	 * Dawn: Varro and Gorm pack up.
	 */
	private void close()
	{
		for (Stall stall : STALLS)
		{
			for (Spawn spawn : new ArrayList<>(SpawnTable.getInstance().getSpawns(stall.npcId)))
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
	}

	@Override
	public String onEvent(String event, Npc npc, Player player)
	{
		if ((npc == null) || (player == null))
		{
			return null;
		}

		final int[] lists = LISTS.get(npc.getId());
		if (lists == null)
		{
			return null;
		}

		if (event.startsWith("multisell "))
		{
			try
			{
				final int listId = Integer.parseInt(event.substring("multisell ".length()).trim());
				for (int allowed : lists)
				{
					if (allowed == listId)
					{
						MultisellData.getInstance().separateAndSend(listId, player, npc, listId == UNSEAL_LIST);
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

		return (event.equals(npc.getId() + ".htm") || event.equals(npc.getId() + "-1.htm")) ? event : null;
	}

	@Override
	public String onFirstTalk(Npc npc, Player player)
	{
		return npc.getId() + ".htm";
	}

	public static void main(String[] args)
	{
		new NightMarket();
	}
}
