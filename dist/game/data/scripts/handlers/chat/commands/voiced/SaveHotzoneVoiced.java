package handlers.chat.commands.voiced;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.l2jmobius.gameserver.handler.IVoicedCommandHandler;
import org.l2jmobius.gameserver.model.actor.Player;

/**
 * ".savehotzone &lt;average level&gt; &lt;description&gt;" - lets any player (no admin access needed) drop their current coordinates into a shared log file as a ready-to-paste {@code <zone type="HotZone">} block, in the exact format used by dist/game/data/zones/custom_hotzones.xml. Meant
 * for scouting candidate hotzone spots while playing normally, instead of alt-tabbing to note coordinates down by hand - the zone still needs a real, unused id assigned to it (left as a placeholder here) and to be pasted into the actual zone file by an admin.
 * <p>
 * Usage: {@code .savehotzone 45 Cruma Marshlands} - the average level MUST come first (a single
 * number), everything after it is taken as the description, so a multi-word location name works
 * without extra quoting.
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

	private static final String USAGE = "Usage: .savehotzone <average level> <description> (e.g. .savehotzone 45 Cruma Marshlands)";

	@Override
	public boolean useVoicedCommand(String command, Player player, String params)
	{
		if (player == null)
		{
			return false;
		}

		if ((params == null) || params.isBlank())
		{
			player.sendMessage(USAGE);
			return false;
		}

		final String trimmed = params.trim();
		final int firstSpace = trimmed.indexOf(' ');
		if (firstSpace < 0)
		{
			player.sendMessage(USAGE);
			return false;
		}

		final int avgLevel;
		try
		{
			avgLevel = Integer.parseInt(trimmed.substring(0, firstSpace));
		}
		catch (NumberFormatException e)
		{
			player.sendMessage("The average level must be a plain number, and must come first. " + USAGE);
			return false;
		}

		final String description = trimmed.substring(firstSpace + 1).trim();
		if (description.isEmpty())
		{
			player.sendMessage(USAGE);
			return false;
		}

		// Never let the description close the XML comment early or break the name attribute.
		final String safeComment = description.replace("-->", "- >");
		final String safeName = description.replace("\"", "'");

		final String timestamp = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date());

		final StringBuilder sb = new StringBuilder();
		sb.append(System.lineSeparator());
		sb.append("<!-- ").append(safeComment).append(" | Avg Level: ").append(avgLevel).append(" | Marked by ").append(player.getName()).append(" at ").append(timestamp).append(" -->").append(System.lineSeparator());
		sb.append("<zone id=\"ASSIGN_ID\" name=\"").append(safeName).append("\" type=\"HotZone\" shape=\"Cylinder\" minZ=\"-10000\" maxZ=\"10000\" rad=\"5000\">").append(System.lineSeparator());
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

		player.sendMessage("Saved hotzone suggestion \"" + description + "\" (avg level " + avgLevel + ") to " + LOG_FILE.getPath() + ".");
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
