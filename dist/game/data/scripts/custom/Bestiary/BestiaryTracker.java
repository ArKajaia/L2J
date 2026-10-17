package custom.Bestiary;

import java.util.Locale;
import java.util.Map;

import org.l2jmobius.gameserver.config.custom.BestiaryConfig;
import org.l2jmobius.gameserver.data.SpawnTable;
import org.l2jmobius.gameserver.managers.BestiaryManager;
import org.l2jmobius.gameserver.model.actor.Npc;
import org.l2jmobius.gameserver.model.actor.Player;
import org.l2jmobius.gameserver.model.actor.templates.NpcTemplate;
import org.l2jmobius.gameserver.model.spawns.Spawn;
import org.l2jmobius.gameserver.network.serverpackets.RadarControl;
import org.l2jmobius.gameserver.util.FormatUtil;

/**
 * Points a player's radar at a monster of the bestiary: the nearest one alive in the player's world, or else the nearest place it spawns. Used by ".bestiary track", the Community Board's bestiary and the Track button of the web page.
 * <p>
 * A monster can be tracked when the account found it, or when the page shows where monsters not found yet live (BestiaryShowUndiscoveredAreas), so it tells no more than the page.
 * @author Claude
 */
public class BestiaryTracker
{
	/**
	 * What tracking did.
	 * @param tracking {@code true} if the radar now points at the monster
	 * @param message what to tell the player
	 */
	public static class Result
	{
		private final boolean _tracking;
		private final String _message;

		public Result(boolean tracking, String message)
		{
			_tracking = tracking;
			_message = message;
		}

		/**
		 * @return {@code true} if the radar now points at the monster
		 */
		public boolean tracking()
		{
			return _tracking;
		}

		/**
		 * @return what to tell the player
		 */
		public String message()
		{
			return _message;
		}
	}

	private BestiaryTracker()
	{
	}

	/**
	 * @param player the player
	 * @param npcId the monster
	 * @return what happened
	 */
	public static Result track(Player player, int npcId)
	{
		final BestiaryCatalog.Kind kind = BestiaryCatalog.getInstance().getMonsters().get(npcId);
		if (kind == null)
		{
			return new Result(false, "This monster has no hunting ground to track.");
		}

		final NpcTemplate template = kind.getTemplate();
		final BestiaryManager.Entry entry = BestiaryManager.getInstance().getEntries(player.getObjectId()).get(npcId);
		final boolean found = (entry != null) && (BestiaryManager.getTier(template, entry.kills()) > 0);
		if (!found && !BestiaryConfig.SHOW_UNDISCOVERED_AREAS)
		{
			return new Result(false, "You have not found this monster yet.");
		}

		final String name = found || BestiaryConfig.SHOW_UNDISCOVERED_NAMES ? template.getName() : "an unknown monster";
		int[] target = null;
		long best = Long.MAX_VALUE;
		boolean alive = false;
		for (Spawn spawn : SpawnTable.getInstance().getSpawns(npcId))
		{
			for (Npc npc : spawn.getSpawnedNpcs())
			{
				if (!npc.isDead() && npc.isSpawned() && (npc.getInstanceId() == player.getInstanceId()))
				{
					final long distance = distance(player, npc.getX(), npc.getY());
					if (!alive || (distance < best))
					{
						alive = true;
						best = distance;
						target = new int[]
						{
							npc.getX(),
							npc.getY(),
							npc.getZ()
						};
					}
				}
			}
			if (!alive && ((spawn.getX() != 0) || (spawn.getY() != 0)))
			{
				final long distance = distance(player, spawn.getX(), spawn.getY());
				if (distance < best)
				{
					best = distance;
					target = new int[]
					{
						spawn.getX(),
						spawn.getY(),
						spawn.getZ()
					};
				}
			}
		}

		// Spawned over a territory: the middle of the nearest one, at the player's height (the radar goes by x and y).
		if (target == null)
		{
			for (int[] point : kind.getPoints())
			{
				final long distance = distance(player, point[0], point[1]);
				if (distance < best)
				{
					best = distance;
					target = new int[]
					{
						point[0],
						point[1],
						player.getZ()
					};
				}
			}
		}
		if (target == null)
		{
			return new Result(false, "No place is known where " + name + " lives.");
		}

		player.getRadar().removeAllMarkers();
		player.getRadar().addMarker(target[0], target[1], target[2]);
		player.sendPacket(new RadarControl(0, 2, target[0], target[1], target[2]));
		final String where = kind.getAreas().isEmpty() ? "" : " in " + BestiaryCatalog.getInstance().getAreas().get(kind.getAreas().iterator().next()).getName();
		return new Result(true, "Bestiary: tracking " + name + (alive ? ", one alive " : ", where it spawns ") + FormatUtil.formatAdena(Math.round(Math.sqrt(best))) + " away" + where + ". Your radar points to it (.bestiary untrack to clear it).");
	}

	/**
	 * Clears the radar.
	 * @param player the player
	 */
	public static void untrack(Player player)
	{
		player.getRadar().removeAllMarkers();
	}

	/**
	 * @param player who looks
	 * @param text a monster's id or name (the whole name, or a part of it)
	 * @return the monster of the collection it names that the player may track, or 0
	 */
	public static int find(Player player, String text)
	{
		final String query = text.trim().toLowerCase(Locale.ROOT);
		if (query.isEmpty())
		{
			return 0;
		}

		final BestiaryCatalog catalog = BestiaryCatalog.getInstance();
		try
		{
			final int id = Integer.parseInt(query);
			return catalog.getMonsters().containsKey(id) ? id : 0;
		}
		catch (NumberFormatException e)
		{
			// a name
		}

		// Names of monsters not found yet only match when the page shows them.
		final Map<Integer, BestiaryManager.Entry> entries = BestiaryManager.getInstance().getEntries(player.getObjectId());
		int partial = 0;
		int partialLevelGap = Integer.MAX_VALUE;
		for (BestiaryCatalog.Kind kind : catalog.getMonsters().values())
		{
			final NpcTemplate template = kind.getTemplate();
			if (!BestiaryConfig.SHOW_UNDISCOVERED_NAMES && !entries.containsKey(template.getId()))
			{
				continue;
			}

			final String name = template.getName().toLowerCase(Locale.ROOT);
			if (name.equals(query))
			{
				return template.getId();
			}
			if (name.contains(query))
			{
				// Several match: the one nearest the player's level.
				final int gap = Math.abs(template.getLevel() - player.getLevel());
				if (gap < partialLevelGap)
				{
					partial = template.getId();
					partialLevelGap = gap;
				}
			}
		}
		return partial;
	}

	private static long distance(Player player, int x, int y)
	{
		final long dx = x - player.getX();
		final long dy = y - player.getY();
		return (dx * dx) + (dy * dy);
	}
}
