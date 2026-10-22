package custom.Bestiary;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.l2jmobius.gameserver.config.custom.BestiaryConfig;
import org.l2jmobius.gameserver.managers.BestiaryManager;
import org.l2jmobius.gameserver.model.actor.enums.creature.Race;
import org.l2jmobius.gameserver.model.actor.templates.NpcTemplate;

/**
 * The bestiary's catalog as the game client's bestiary window caches it: the tiers and bonuses, the regions and hunting grounds, every monster of the collection with what anyone may see of it, and the land of the map (layout: client/Bestiary/PROTOCOL.txt). It is the same for every player, so it is built once, kept as one
 * byte array and never per player. What an account found, and what a tier shows (stats, skills, drops), are not in it.
 * <p>
 * The client keeps it in System\Bestiary.cache. {@link #export(Path)} (//bestiary_export) writes that file, to ship with the client so the window opens without fetching anything; a client whose copy has another SHA-256 than the server's fetches it in {@link #CHUNK_SIZE} chunks.
 * @author Claude
 */
public final class BestiaryBlob
{
	private static final Logger LOGGER = Logger.getLogger(BestiaryBlob.class.getName());

	public static final int FORMAT = 1;
	/** Bytes per chunk: the client's receive buffer takes a frame of about 32 KB. */
	public static final int CHUNK_SIZE = 16384;
	/** The cache file: "L2BE", int format, byte[32] sha256, int size, then the definition. */
	public static final byte[] CACHE_MAGIC =
	{
		'L',
		'2',
		'B',
		'E'
	};
	public static final int CACHE_HEADER = 44;

	/** The client's limits (shared/bestiary_blob.h): more is refused there, so it is warned about here. */
	public static final int MAX_MONSTERS = 8192;
	public static final int MAX_AREAS = 4096;
	public static final int MAX_REGIONS = 1024;
	public static final int MAX_FORMS = 32;
	public static final int MAX_STRING = 256;
	public static final int MAX_BLOB = 2 << 20;
	public static final int MAX_LAND = 0xFFFF;
	public static final int MAX_GLYPHS = 64;
	public static final int MAX_MARKUP = 4096;

	/** The web page holds the race emblems and icons; the client draws the same ones. */
	private static final Path GLYPH_PAGE = Path.of("data/html/custom/bestiary.html");
	private static final Pattern SYMBOL = Pattern.compile("<symbol id=\"([a-zA-Z0-9_-]+)\" viewBox=\"([0-9 .]+)\">(.*?)</symbol>", Pattern.DOTALL);
	/** Spawn points are sent in units of this many world units, as shorts. */
	public static final int POINT_UNIT = 16;

	public static final int FLAG_SHOW_NAMES = 1;
	public static final int FLAG_SHOW_AREAS = 2;
	public static final int FLAG_CRAFT_GOALS = 4;

	/**
	 * One built definition.
	 */
	public static final class Blob
	{
		private final byte[] _data;
		private final byte[] _hash;

		Blob(byte[] data, byte[] hash)
		{
			_data = data;
			_hash = hash;
		}

		public byte[] getData()
		{
			return _data;
		}

		/** @return SHA-256 of the definition */
		public byte[] getHash()
		{
			return _hash;
		}

		public int getChunkCount()
		{
			return (_data.length + CHUNK_SIZE - 1) / CHUNK_SIZE;
		}

		/**
		 * @param index a chunk, from 0
		 * @return its bytes, or {@code null} past the last chunk
		 */
		public byte[] getChunk(int index)
		{
			if ((index < 0) || (index >= getChunkCount()))
			{
				return null;
			}
			final int from = index * CHUNK_SIZE;
			return Arrays.copyOfRange(_data, from, Math.min(_data.length, from + CHUNK_SIZE));
		}
	}

	private static volatile Blob _blob;

	private BestiaryBlob()
	{
	}

	/**
	 * @return the definition of the collection, built on first use
	 */
	public static Blob get()
	{
		final Blob blob = _blob;
		return blob != null ? blob : build();
	}

	private static synchronized Blob build()
	{
		if (_blob != null)
		{
			return _blob;
		}

		final long start = System.currentTimeMillis();
		final byte[] data = write(BestiaryCatalog.getInstance());
		final byte[] hash;
		try
		{
			hash = MessageDigest.getInstance("SHA-256").digest(data);
		}
		catch (NoSuchAlgorithmException e)
		{
			throw new IllegalStateException(e);
		}
		_blob = new Blob(data, hash);
		LOGGER.info(BestiaryBlob.class.getSimpleName() + ": Client catalog of " + BestiaryCatalog.getInstance().getMonsters().size() + " monsters: " + data.length + " bytes in " + _blob.getChunkCount() + " chunks, SHA-256 " + hex(hash).substring(0, 16) + "... (" + (System.currentTimeMillis() - start) + " ms)");
		return _blob;
	}

	/**
	 * Writes the definition as the client's cache file, through a temporary file moved over the old one.
	 * @param file where to write it (System\Bestiary.cache of a client, or anywhere to copy it from)
	 * @return the definition written
	 * @throws IOException when the file cannot be written
	 */
	public static Blob export(Path file) throws IOException
	{
		final Blob blob = get();
		final Writer out = new Writer();
		out.write(CACHE_MAGIC, 0, CACHE_MAGIC.length);
		out.int32(FORMAT);
		out.write(blob.getHash(), 0, blob.getHash().length);
		out.int32(blob.getData().length);
		out.write(blob.getData(), 0, blob.getData().length);

		final Path parent = file.toAbsolutePath().getParent();
		if (parent != null)
		{
			Files.createDirectories(parent);
		}
		final Path temporary = file.resolveSibling(file.getFileName() + ".tmp");
		Files.write(temporary, out.toByteArray());
		Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
		return blob;
	}

	/**
	 * @param catalog the collection
	 * @return the definition (layout: client/Bestiary/PROTOCOL.txt)
	 */
	static byte[] write(BestiaryCatalog catalog)
	{
		final Writer out = new Writer();
		out.int32(FORMAT);
		out.int32((BestiaryConfig.SHOW_UNDISCOVERED_NAMES ? FLAG_SHOW_NAMES : 0) | (BestiaryConfig.SHOW_UNDISCOVERED_AREAS ? FLAG_SHOW_AREAS : 0) | (BestiaryFarming.isAvailable() ? FLAG_CRAFT_GOALS : 0));

		// Tiers: their names, the kills they need and what each gives.
		out.int8(BestiaryConfig.TIER_NAMES.length);
		for (int i = 0; i < BestiaryConfig.TIER_NAMES.length; i++)
		{
			out.string(BestiaryConfig.TIER_NAMES[i]);
			out.int32(BestiaryConfig.TIERS[i]);
			out.int32(BestiaryConfig.BOSS_TIERS[i]);
			out.int32(BestiaryConfig.DROP_BONUS[i]);
			out.int32(BestiaryConfig.BOSS_DROP_BONUS[i]);
			out.int32(BestiaryConfig.REGION_EXP_BONUS[i]);
			out.int32(BestiaryConfig.SLAYER_DAMAGE_BONUS[i]);
			out.int32(BestiaryConfig.SLAYER_DEFENSE_BONUS[i]);
		}
		out.int32(BestiaryConfig.SLAYER_MIN_MONSTERS);

		// The rare forms of the systems that are enabled.
		final List<Integer> forms = new ArrayList<>();
		final int enabled = BestiaryManager.getEnabledVariants();
		for (int i = 0; i < BestiaryManager.VARIANT_NAMES.length; i++)
		{
			if ((enabled & (1 << i)) != 0)
			{
				forms.add(i);
			}
		}
		out.int8(forms.size());
		for (int i : forms)
		{
			out.int32(1 << i);
			out.string(BestiaryManager.VARIANT_NAMES[i]);
		}

		// Every race, so a monster refers to its race by index.
		final Race[] races = Race.values();
		out.int8(races.length);
		for (Race race : races)
		{
			out.string(race.name());
		}

		final List<String> regions = catalog.getRegions();
		out.int16(regions.size());
		for (String region : regions)
		{
			out.string(region);
		}

		final List<BestiaryCatalog.Area> areas = catalog.getAreas();
		final Map<Integer, Integer> areaIndex = new HashMap<>();
		out.int16(areas.size());
		for (BestiaryCatalog.Area area : areas)
		{
			areaIndex.put(area.getId(), areaIndex.size());
			out.int32(area.getId());
			out.int16(Math.max(0, regions.indexOf(area.getRegion())));
			out.int8(clamp((int) Math.round(area.getAverageLevel()), 0, 255));
			out.string(area.getName());
		}

		// The monsters in the collection's order. Names and where they live only when the server shows them before they are found;
		// otherwise the client names a found monster from its own NpcName data and gets its areas with its details.
		final boolean showAreas = BestiaryConfig.SHOW_UNDISCOVERED_AREAS;
		int farPoints = 0;
		out.int16(catalog.getMonsters().size());
		for (BestiaryCatalog.Kind kind : catalog.getMonsters().values())
		{
			final NpcTemplate template = kind.getTemplate();
			out.int32(template.getId());
			out.int32(template.getDisplayId() != template.getId() ? template.getDisplayId() : 0);
			out.int8(clamp(template.getLevel(), 0, 255));
			out.int8(template.getRace().ordinal());
			out.int8(template.isType("GrandBoss") ? 2 : template.isType("RaidBoss") ? 1 : 0);
			out.int16(clamp(Math.round(template.getFCollisionHeight()), 0, 0xFFFF));
			out.int16(clamp(Math.round(template.getFCollisionRadius()), 0, 0xFFFF));
			out.string(BestiaryConfig.SHOW_UNDISCOVERED_NAMES ? template.getName() : "");

			final List<Integer> monsterAreas = new ArrayList<>();
			if (showAreas)
			{
				for (int area : kind.getAreas())
				{
					final Integer index = areaIndex.get(area);
					if (index != null)
					{
						monsterAreas.add(index);
					}
				}
			}
			out.int8(Math.min(monsterAreas.size(), 255));
			for (int i = 0; i < Math.min(monsterAreas.size(), 255); i++)
			{
				out.int16(monsterAreas.get(i));
			}

			final List<int[]> points = showAreas ? kind.getPoints() : new ArrayList<>();
			out.int8(Math.min(points.size(), 255));
			for (int i = 0; i < Math.min(points.size(), 255); i++)
			{
				final int x = Math.round(points.get(i)[0] / (float) POINT_UNIT);
				final int y = Math.round(points.get(i)[1] / (float) POINT_UNIT);
				if ((x < Short.MIN_VALUE) || (x > Short.MAX_VALUE) || (y < Short.MIN_VALUE) || (y > Short.MAX_VALUE))
				{
					farPoints++;
				}
				out.int16(clamp(x, Short.MIN_VALUE, Short.MAX_VALUE));
				out.int16(clamp(y, Short.MIN_VALUE, Short.MAX_VALUE));
			}
		}

		// The land of the map: every cell with a spawn point of the collection.
		final List<int[]> land = catalog.getLand();
		out.int16(BestiaryCatalog.MAP_CELL);
		out.int16(Math.min(land.size(), MAX_LAND));
		for (int i = 0; i < Math.min(land.size(), MAX_LAND); i++)
		{
			final int[] cell = land.get(i);
			out.int16(clamp(cell[0], Short.MIN_VALUE, Short.MAX_VALUE));
			out.int16(clamp(cell[1], Short.MIN_VALUE, Short.MAX_VALUE));
			out.int16(clamp(cell[2], 0, 0xFFFF));
		}

		// The page's race emblems and icons, as it draws them.
		final Map<String, String[]> glyphs = glyphs();
		out.int16(Math.min(glyphs.size(), MAX_GLYPHS));
		int glyphCount = 0;
		for (Map.Entry<String, String[]> glyph : glyphs.entrySet())
		{
			if (glyphCount++ >= MAX_GLYPHS)
			{
				break;
			}
			out.string(glyph.getKey());
			out.string(glyph.getValue()[0]);
			out.text(glyph.getValue()[1], MAX_MARKUP);
		}

		final byte[] data = out.toByteArray();
		final List<String> problems = new ArrayList<>();
		if (land.size() > MAX_LAND)
		{
			problems.add(land.size() + " land cells (the client takes " + MAX_LAND + "; the rest are left out)");
		}
		if (catalog.getMonsters().size() > MAX_MONSTERS)
		{
			problems.add(catalog.getMonsters().size() + " monsters (the client takes " + MAX_MONSTERS + ")");
		}
		if (areas.size() > MAX_AREAS)
		{
			problems.add(areas.size() + " hunting grounds (the client takes " + MAX_AREAS + ")");
		}
		if (regions.size() > MAX_REGIONS)
		{
			problems.add(regions.size() + " regions (the client takes " + MAX_REGIONS + ")");
		}
		if (out.longest > MAX_STRING)
		{
			problems.add("a name of " + out.longest + " characters (the client takes " + MAX_STRING + ")");
		}
		if (data.length > MAX_BLOB)
		{
			problems.add(data.length + " bytes (the client takes " + MAX_BLOB + ")");
		}
		if (!problems.isEmpty())
		{
			LOGGER.warning(BestiaryBlob.class.getSimpleName() + ": Game clients will refuse this catalog: " + String.join("; ", problems) + ".");
		}
		if (farPoints > 0)
		{
			LOGGER.warning(BestiaryBlob.class.getSimpleName() + ": " + farPoints + " spawn points are outside the map the client can be sent; they are put on its edge.");
		}
		return data;
	}

	/** @return symbol id ("r-BEAST", "i-crown") -> {its viewBox, its inner markup}, as the web page holds them */
	static Map<String, String[]> glyphs()
	{
		final Map<String, String[]> glyphs = new TreeMap<>();
		try
		{
			final Matcher matcher = SYMBOL.matcher(Files.readString(GLYPH_PAGE, StandardCharsets.UTF_8));
			while (matcher.find())
			{
				glyphs.putIfAbsent(matcher.group(1), new String[]
				{
					matcher.group(2),
					matcher.group(3).trim()
				});
			}
		}
		catch (IOException e)
		{
			LOGGER.warning(BestiaryBlob.class.getSimpleName() + ": Could not read the glyphs from " + GLYPH_PAGE + " - game clients draw the bestiary without race emblems. " + e.getMessage());
		}
		return glyphs;
	}

	private static int clamp(int value, int min, int max)
	{
		return Math.max(min, Math.min(max, value));
	}

	public static String hex(byte[] bytes)
	{
		final StringBuilder text = new StringBuilder(bytes.length * 2);
		for (byte b : bytes)
		{
			text.append(String.format("%02x", b));
		}
		return text.toString();
	}

	/** Little-endian, strings as {@code short n} and n UTF-16 units. */
	private static final class Writer extends ByteArrayOutputStream
	{
		int longest;

		Writer()
		{
			super(1 << 18);
		}

		void int8(int value)
		{
			write(value);
		}

		void int16(int value)
		{
			write(value);
			write(value >> 8);
		}

		void int32(int value)
		{
			int16(value);
			int16(value >> 16);
		}

		void string(String value)
		{
			final String text = value == null ? "" : value;
			longest = Math.max(longest, text.length());
			text(text, MAX_STRING);
		}

		/** A string cut to a length, not counted in {@link #longest}. */
		void text(String value, int max)
		{
			final String text = value == null ? "" : value;
			final int length = Math.min(text.length(), max);
			int16(length);
			for (int i = 0; i < length; i++)
			{
				int16(text.charAt(i));
			}
		}
	}
}
