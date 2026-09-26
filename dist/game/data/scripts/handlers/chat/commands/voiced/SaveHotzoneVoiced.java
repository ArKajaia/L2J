package handlers.chat.commands.voiced;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.l2jmobius.gameserver.data.xml.MapRegionData;
import org.l2jmobius.gameserver.handler.IVoicedCommandHandler;
import org.l2jmobius.gameserver.model.World;
import org.l2jmobius.gameserver.model.actor.Player;
import org.l2jmobius.gameserver.model.actor.instance.GrandBoss;
import org.l2jmobius.gameserver.model.actor.instance.Monster;
import org.l2jmobius.gameserver.model.actor.instance.RaidBoss;

/**
 * ".savehotzone [description]" - lets any player (no admin access needed) drop their current coordinates into a shared log file as a ready-to-paste {@code <zone type="HotZone">} block, in the exact format used by dist/game/data/zones/custom_hotzones.xml. Meant for scouting candidate hotzone
 * spots while playing normally, instead of alt-tabbing to note coordinates and monster levels down by hand - the zone still needs a real, unused id assigned to it (left as a placeholder here) and to be pasted into the actual zone file by an admin.
 * <p>
 * Both the location name and the average level are fetched automatically:
 * <ul>
 * <li>Name: the nearest town/region name from {@link MapRegionData} (there's no server-side source for the flavour names used in the existing hotzone list, e.g. "Iris Lake" - a plain description typed as this command's argument overrides it when you want something nicer).
 * <li>Average level: every {@link Monster} within {@link #SCAN_RADIUS} units, raid/grand bosses excluded so one boss standing nearby can't skew a farming-ground reading.
 * </ul>
 */
public class SaveHotzoneVoiced implements IVoicedCommandHandler
{
	private static final Logger LOGGER = Logger.getLogger(SaveHotzoneVoiced.class.getName());

	private static final String[] COMMANDS =
	{
		"savehotzone"
	};

	private static final File LOG_FILE = new File("log/HotzoneSuggestions.txt");
	static
	{
		LOG_FILE.getParentFile().mkdirs();
	}

	private static final int SCAN_RADIUS = 3000;

	@Override
	public boolean useVoicedCommand(String command, Player player, String params)
	{
		if (player == null)
		{
			return false;
		}

		final String override = (params == null) ? "" : params.trim();
		final String description = !override.isEmpty() ? override : MapRegionData.getInstance().getClosestTownName(player);

		final List<Monster> nearby = World.getInstance().getVisibleObjectsInRange(player, Monster.class, SCAN_RADIUS, m -> !(m instanceof RaidBoss) && !(m instanceof GrandBoss) && !m.isDead());

		final int avgLevel;
		final String levelNote;
		if (nearby.isEmpty())
		{
			avgLevel = 0;
			levelNote = "no monsters found within " + SCAN_RADIUS + " - check manually";
		}
		else
		{
			int sum = 0;
			for (Monster m : nearby)
			{
				sum += m.getLevel();
			}
			avgLevel = Math.round((float) sum / nearby.size());
			levelNote = nearby.size() + " monster(s) scanned within " + SCAN_RADIUS;
		}

		// Never let the description close the XML comment early or break the name attribute.
		final String safeComment = description.replace("-->", "- >");
		final String safeName = description.replace("\"", "'");

		final String timestamp = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date());

		final StringBuilder sb = new StringBuilder();
		sb.append(System.lineSeparator());
		sb.append("<!-- ").append(safeComment).append(" | Avg Level: ").append(avgLevel).append(" (").append(levelNote).append(") | Marked by ").append(player.getName()).append(" at ").append(timestamp).append(" -->").append(System.lineSeparator());
		sb.append("<zone id=\"ASSIGN_ID\" name=\"").append(safeName).append("\" type=\"HotZone\" shape=\"Cylinder\" minZ=\"-10000\" maxZ=\"10000\" rad=\"10000\">").append(System.lineSeparator());
		sb.append("\t<node X=\"").append(player.getX()).append("\" Y=\"").append(player.getY()).append("\" /><!--Z=").append(player.getZ()).append("-->").append(System.lineSeparator());
		sb.append("</zone>").append(System.lineSeparator());

		try (FileWriter writer = new FileWriter(LOG_FILE, true))
		{
			writer.write(sb.toString());
		}
		catch (IOException e)
		{
			LOGGER.log(Level.WARNING, "SaveHotzoneVoiced: failed to write to " + LOG_FILE.getPath(), e);
			player.sendMessage("Could not save the hotzone suggestion - see server log.");
			return false;
		}

		player.sendMessage("Saved hotzone suggestion \"" + description + "\" (avg level " + avgLevel + ", " + levelNote + ") to " + LOG_FILE.getPath() + ".");
		return true;
	}

	@Override
	public String[] getVoicedCommandList()
	{
		return COMMANDS;
	}

	@Override
	public boolean onCommand(String command, Player player, String params)
	{
		return useVoicedCommand(command, player, params);
	}

	@Override
	public String[] getCommandList()
	{
		return getVoicedCommandList();
	}
}
