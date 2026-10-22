package custom.Bestiary;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.logging.Logger;
import java.util.regex.Pattern;

import com.sun.net.httpserver.HttpExchange;

import org.l2jmobius.commons.util.SimpleJson;
import org.l2jmobius.gameserver.data.xml.NpcData;
import org.l2jmobius.gameserver.managers.BestiaryManager;
import org.l2jmobius.gameserver.model.actor.Player;
import org.l2jmobius.gameserver.model.actor.templates.NpcTemplate;

import custom.PassiveSkillTree.PassiveTreeApiServer;

/**
 * The look of the monsters: their 3D models and textures (taken from the client by client/Bestiary/extract_models.py) and their portraits (made by the bake page in a GM's browser).
 * <ul>
 * <li>GET /bestiary/models/&lt;file&gt; - a model (.glb), the manifest (npc id -&gt; mesh and skins) and the view (how the models are turned), from data/html/custom/bestiary/models</li>
 * <li>GET /bestiary/textures/&lt;file&gt; - a skin (.png), from data/html/custom/bestiary/textures</li>
 * <li>GET /bestiary/img?k=... (a look) or ?id=... (a monster) - a portrait, data/html/custom/bestiary/looks/&lt;look&gt;.webp (or .png, .jpg, .gif)</li>
 * <li>GET /bestiary/lib/&lt;path&gt; - the page's scripts (the 3D viewer and three.js) and the backgrounds behind portraits and models, from data/html/custom/bestiary-lib</li>
 * <li>GET /bestiary-bake.html - the bake page, opened with //bestiary</li>
 * <li>GET /api/bestiary/admin/models - (GM) the monsters to bake, with their models and portraits</li>
 * <li>POST /bestiary/admin/portrait?k=... - (GM) saves the portrait the page rendered of a look (the body is the picture)</li>
 * <li>POST /bestiary/admin/view - (GM) saves how the models are turned to face the camera</li>
 * </ul>
 * Pictures, models and scripts may be kept by the browser; a portrait's address carries its version, so a new bake is seen at once.
 * <p>
 * Portraits belong to looks, not to monsters: monsters of the same mesh and skins (the manifest's npcs entry) look the same, so one picture serves them all (4592 monsters, 701 looks), baked once and
 * downloaded once. A look is named by {@link #lookId}, the first 31 bits of the SHA-1 of "mesh|skin|skin...", written as 8 hex digits (tools/bestiary/portraits.py and the client use the same).
 * @author Claude
 */
public class BestiaryArt
{
	private static final Logger LOGGER = Logger.getLogger(BestiaryArt.class.getName());

	/** The command a GM's access level must allow to open the bake page and save portraits. */
	public static final String ADMIN_COMMAND = "admin_bestiary";

	private static final Path ART = Path.of("data/html/custom/bestiary");
	private static final Path LOOKS = ART.resolve("looks");
	private static final Pattern LOOK_NAME = Pattern.compile("[0-9a-f]{8}");
	private static final Path MODELS = ART.resolve("models");
	private static final Path TEXTURES = ART.resolve("textures");
	private static final Path LIB = Path.of("data/html/custom/bestiary-lib");
	private static final Path BAKE_PAGE = Path.of("data/html/custom/bestiary-bake.html");
	private static final Path MANIFEST = MODELS.resolve("manifest.json");
	private static final Path VIEW = MODELS.resolve("view.json");
	private static final String DEFAULT_VIEW = "{\"yaw\":0,\"pitch\":12}";

	private static final String[] PORTRAIT_TYPES =
	{
		"webp",
		"png",
		"jpg",
		"gif"
	};
	private static final Pattern FILE_NAME = Pattern.compile("[a-z0-9_.-]+");
	private static final Pattern LIB_SEGMENT = Pattern.compile("[A-Za-z0-9_][A-Za-z0-9_.-]*");
	private static final int MAX_PORTRAIT_BYTES = 1024 * 1024;
	private static final int MAX_FILE_BYTES = 32 * 1024 * 1024;
	private static final long PORTRAIT_LIST_TIME = 5 * 60 * 1000L;
	private static final long MANIFEST_CHECK_TIME = 30 * 1000L;
	private static final int CACHE_DAY = 86400;
	private static final int CACHE_HOUR = 3600;

	/**
	 * A look's portrait on disk.
	 */
	private static final class Portrait
	{
		final Path _path;
		final long _version;

		Portrait(Path path, long version)
		{
			_path = path;
			_version = version;
		}
	}

	private volatile Map<Integer, Portrait> _portraits = Collections.emptyMap(); // by look
	private volatile long _portraitsTime;
	private volatile Set<Integer> _models = Collections.emptySet();
	private volatile Map<Integer, Integer> _lookOf = Collections.emptyMap(); // npc id -> look
	private volatile long _manifestModified = -1;
	private volatile long _manifestChecked;

	private BestiaryArt()
	{
	}

	/**
	 * Adds the paths of the monsters' look to the web server.
	 * @param server the passive tree's web server
	 */
	public void register(PassiveTreeApiServer server)
	{
		server.addRoute("/bestiary-bake.html", "GET", false, exchange -> server.sendPage(exchange, BAKE_PAGE));
		server.addRoute("/bestiary/lib/", "GET", false, this::handleLib);
		server.addRoute("/bestiary/models/", "GET", false, this::handleModels);
		server.addRoute("/bestiary/textures/", "GET", false, this::handleTextures);
		server.addRoute("/bestiary/img", "GET", false, this::handlePortrait);
		server.addRoute("/api/bestiary/admin/models", "GET", false, this::handleAdminModels);
		server.addRoute("/bestiary/admin/portrait", "POST", true, this::handleAdminPortrait);
		server.addRoute("/bestiary/admin/view", "POST", true, this::handleAdminView);
	}

	/**
	 * @param npcId a monster
	 * @return the version of its portrait (when it was made, in seconds), or 0 if it has none
	 */
	public long getPortraitVersion(int npcId)
	{
		final Portrait portrait = getPortraits().get(getLook(npcId));
		return portrait != null ? portrait._version : 0;
	}

	/**
	 * @param npcId a monster
	 * @return its look (see {@link #lookId}), or 0 when the manifest gives it no model
	 */
	public int getLook(int npcId)
	{
		final Integer look = getLookMap().get(npcId);
		return look != null ? look : 0;
	}

	/**
	 * @param look a look
	 * @return its name: 8 lowercase hex digits
	 */
	public static String lookName(int look)
	{
		return String.format("%08x", look);
	}

	/**
	 * @param mesh the manifest's mesh of a monster
	 * @param skins its skins, in slot order (a missing one as "")
	 * @return the look of that mesh with those skins: the first 4 bytes of the SHA-1 of "mesh|skin|skin..." (without trailing empty skins), big endian, without the sign bit; never 0
	 */
	public static int lookId(String mesh, List<String> skins)
	{
		// A missing skin and an empty one both take the mesh's own: trailing ones do not make another look.
		int count = skins.size();
		while ((count > 0) && ((skins.get(count - 1) == null) || skins.get(count - 1).isEmpty()))
		{
			count--;
		}
		final StringBuilder key = new StringBuilder(mesh);
		for (int i = 0; i < count; i++)
		{
			key.append('|').append(skins.get(i) == null ? "" : skins.get(i));
		}
		try
		{
			final byte[] sha = MessageDigest.getInstance("SHA-1").digest(key.toString().getBytes(StandardCharsets.UTF_8));
			final int id = (((sha[0] & 0xFF) << 24) | ((sha[1] & 0xFF) << 16) | ((sha[2] & 0xFF) << 8) | (sha[3] & 0xFF)) & 0x7FFFFFFF;
			return id == 0 ? 1 : id;
		}
		catch (NoSuchAlgorithmException e)
		{
			throw new IllegalStateException(e);
		}
	}

	private static int parseLook(String value)
	{
		if ((value == null) || !LOOK_NAME.matcher(value).matches())
		{
			return 0;
		}
		return (int) Long.parseLong(value, 16);
	}

	/**
	 * @param npcId a monster
	 * @return {@code true} if the manifest gives it a 3D model
	 */
	public boolean hasModel(int npcId)
	{
		return getModels().contains(npcId);
	}

	// ---------------------------------------------------------------------------------------------
	// Files
	// ---------------------------------------------------------------------------------------------

	private void handleLib(HttpExchange exchange) throws IOException
	{
		final String path = exchange.getRequestURI().getPath().substring("/bestiary/lib/".length());
		Path file = LIB;
		for (String segment : path.split("/"))
		{
			if (!LIB_SEGMENT.matcher(segment).matches())
			{
				notFound(exchange);
				return;
			}
			file = file.resolve(segment);
		}
		final String type = contentType(file.getFileName().toString());
		if ((type == null) || !Files.isRegularFile(file))
		{
			notFound(exchange);
			return;
		}
		PassiveTreeApiServer.getInstance().sendStatic(exchange, type, Files.readAllBytes(file), CACHE_HOUR);
	}

	private void handleModels(HttpExchange exchange) throws IOException
	{
		final String name = exchange.getRequestURI().getPath().substring("/bestiary/models/".length());
		final Path file = MODELS.resolve(FILE_NAME.matcher(name).matches() ? name : "-");
		if ("view.json".equals(name) && !Files.isRegularFile(file))
		{
			// Not set on the bake page yet: the models as the client has them, seen from a little above.
			PassiveTreeApiServer.getInstance().sendJson(exchange, 200, DEFAULT_VIEW);
			return;
		}
		if (!(name.endsWith(".glb") || name.endsWith(".json")) || !Files.isRegularFile(file) || (Files.size(file) > MAX_FILE_BYTES))
		{
			notFound(exchange);
			return;
		}
		final PassiveTreeApiServer server = PassiveTreeApiServer.getInstance();
		if (name.endsWith(".json"))
		{
			// The manifest and the view change when the art is made again: never kept.
			server.sendJson(exchange, 200, new String(Files.readAllBytes(file), StandardCharsets.UTF_8));
			return;
		}
		server.sendStatic(exchange, "model/gltf-binary", Files.readAllBytes(file), CACHE_DAY);
	}

	private void handleTextures(HttpExchange exchange) throws IOException
	{
		final String name = exchange.getRequestURI().getPath().substring("/bestiary/textures/".length());
		final Path file = TEXTURES.resolve(FILE_NAME.matcher(name).matches() ? name : "-");
		if (!name.endsWith(".png") || !Files.isRegularFile(file) || (Files.size(file) > MAX_FILE_BYTES))
		{
			notFound(exchange);
			return;
		}
		PassiveTreeApiServer.getInstance().sendStatic(exchange, "image/png", Files.readAllBytes(file), CACHE_DAY);
	}

	private void handlePortrait(HttpExchange exchange) throws IOException
	{
		final PassiveTreeApiServer server = PassiveTreeApiServer.getInstance();
		final Map<String, String> params = server.readParams(exchange);
		final int look = params.containsKey("k") ? parseLook(params.get("k")) : getLook(parseInt(params.get("id")));
		final Portrait portrait = getPortraits().get(look);
		if ((portrait == null) || !Files.isRegularFile(portrait._path) || (Files.size(portrait._path) > MAX_FILE_BYTES))
		{
			notFound(exchange);
			return;
		}
		final String name = portrait._path.getFileName().toString();
		final String type = name.substring(name.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT);
		server.sendStatic(exchange, "image/" + ("jpg".equals(type) ? "jpeg" : type), Files.readAllBytes(portrait._path), CACHE_DAY);
	}

	private static String contentType(String name)
	{
		final String lower = name.toLowerCase(Locale.ROOT);
		if (lower.endsWith(".js"))
		{
			return "text/javascript; charset=UTF-8";
		}
		if (lower.endsWith(".json"))
		{
			return "application/json";
		}
		if (lower.endsWith(".css"))
		{
			return "text/css; charset=UTF-8";
		}
		if (lower.endsWith(".webp"))
		{
			return "image/webp";
		}
		if (lower.endsWith(".png"))
		{
			return "image/png";
		}
		if (lower.endsWith(".jpg") || lower.endsWith(".jpeg"))
		{
			return "image/jpeg";
		}
		if (lower.endsWith(".txt") || lower.endsWith("license"))
		{
			return "text/plain; charset=UTF-8";
		}
		return null;
	}

	private static void notFound(HttpExchange exchange) throws IOException
	{
		PassiveTreeApiServer.getInstance().sendText(exchange, 404, "text/plain", "not found");
	}

	// ---------------------------------------------------------------------------------------------
	// Portraits and manifest
	// ---------------------------------------------------------------------------------------------

	/** @return the portraits by look, looked at again every few minutes and after each save */
	private Map<Integer, Portrait> getPortraits()
	{
		final long now = System.currentTimeMillis();
		if ((now - _portraitsTime) < PORTRAIT_LIST_TIME)
		{
			return _portraits;
		}

		final Map<Integer, Portrait> portraits = new HashMap<>();
		if (Files.isDirectory(LOOKS))
		{
			try (DirectoryStream<Path> files = Files.newDirectoryStream(LOOKS))
			{
				for (Path file : files)
				{
					final String name = file.getFileName().toString();
					final int dot = name.lastIndexOf('.');
					final int rank = dot > 0 ? typeRank(name.substring(dot + 1)) : -1;
					final int look = dot > 0 ? parseLook(name.substring(0, dot)) : 0;
					if ((rank < 0) || (look <= 0))
					{
						continue;
					}
					final Portrait old = portraits.get(look);
					if ((old == null) || (rank < typeRank(old._path.getFileName().toString().replaceFirst(".*\\.", ""))))
					{
						portraits.put(look, new Portrait(file, Files.getLastModifiedTime(file).toMillis() / 1000));
					}
				}
			}
			catch (IOException e)
			{
				return _portraits;
			}
		}
		_portraits = portraits;
		_portraitsTime = now;
		return portraits;
	}

	/** @return the place of a picture type among PORTRAIT_TYPES (the first one wins when a monster has two), or -1 */
	private static int typeRank(String extension)
	{
		for (int i = 0; i < PORTRAIT_TYPES.length; i++)
		{
			if (PORTRAIT_TYPES[i].equalsIgnoreCase(extension))
			{
				return i;
			}
		}
		return -1;
	}

	/** @return the npc ids of the manifest, read again when the file changed */
	private Set<Integer> getModels()
	{
		readManifest();
		return _models;
	}

	/** @return the look of each npc of the manifest, read again when the file changed */
	private Map<Integer, Integer> getLookMap()
	{
		readManifest();
		return _lookOf;
	}

	private synchronized void readManifest()
	{
		final long now = System.currentTimeMillis();
		if ((now - _manifestChecked) < MANIFEST_CHECK_TIME)
		{
			return;
		}
		_manifestChecked = now;

		try
		{
			final long modified = Files.isRegularFile(MANIFEST) ? Files.getLastModifiedTime(MANIFEST).toMillis() : 0;
			if (modified == _manifestModified)
			{
				return;
			}

			final Set<Integer> models = new HashSet<>();
			final Map<Integer, Integer> lookOf = new HashMap<>();
			if (modified > 0)
			{
				final Object manifest = SimpleJson.parse(new String(Files.readAllBytes(MANIFEST), StandardCharsets.UTF_8));
				final Object npcs = manifest instanceof Map ? ((Map<?, ?>) manifest).get("npcs") : null;
				if (npcs instanceof Map)
				{
					for (Map.Entry<?, ?> npc : ((Map<?, ?>) npcs).entrySet())
					{
						final int npcId = parseInt(String.valueOf(npc.getKey()));
						if ((npcId <= 0) || !(npc.getValue() instanceof Map))
						{
							continue;
						}
						final Map<?, ?> entry = (Map<?, ?>) npc.getValue();
						final Object mesh = entry.get("mesh");
						if (mesh == null)
						{
							continue;
						}
						final List<String> skins = new ArrayList<>();
						if (entry.get("textures") instanceof List)
						{
							for (Object skin : (List<?>) entry.get("textures"))
							{
								skins.add(skin == null ? "" : String.valueOf(skin));
							}
						}
						models.add(npcId);
						lookOf.put(npcId, lookId(String.valueOf(mesh), skins));
					}
				}
				LOGGER.info(getClass().getSimpleName() + ": " + models.size() + " monsters have a 3D model, " + new HashSet<>(lookOf.values()).size() + " looks.");
			}
			_lookOf = Collections.unmodifiableMap(lookOf);
			_models = Collections.unmodifiableSet(models);
			_manifestModified = modified;
		}
		catch (IOException | IllegalArgumentException e)
		{
			LOGGER.warning(getClass().getSimpleName() + ": Could not read " + MANIFEST + " - " + e.getMessage());
			_manifestModified = -1;
		}
	}

	// ---------------------------------------------------------------------------------------------
	// Bake page (GMs)
	// ---------------------------------------------------------------------------------------------

	private void handleAdminModels(HttpExchange exchange) throws IOException
	{
		final PassiveTreeApiServer server = PassiveTreeApiServer.getInstance();
		final Player gm = server.authenticateAdmin(exchange, ADMIN_COMMAND);
		if (gm == null)
		{
			return;
		}

		// The collection, then the other monsters of the manifest (met elsewhere).
		final Set<Integer> ids = new LinkedHashSet<>(BestiaryCatalog.getInstance().getMonsters().keySet());
		ids.addAll(getModels());
		final StringBuilder json = new StringBuilder(128 * 1024);
		json.append("{\"gm\":").append(SimpleJson.quote(gm.getName())).append(",\"monsters\":[");
		boolean first = true;
		for (int npcId : ids)
		{
			final NpcTemplate template = NpcData.getInstance().getTemplate(npcId);
			if (template == null)
			{
				continue;
			}
			json.append(first ? "" : ",").append("{\"id\":").append(npcId);
			json.append(",\"n\":").append(SimpleJson.quote(template.getName()));
			json.append(",\"l\":").append(template.getLevel());
			json.append(",\"r\":").append(SimpleJson.quote(template.getRace().name()));
			json.append(",\"t\":").append(BestiaryManager.isBoss(template) ? 1 : 0);
			json.append(",\"m\":").append(hasModel(npcId));
			if (getLook(npcId) != 0)
			{
				json.append(",\"k\":\"").append(lookName(getLook(npcId))).append('"');
			}
			json.append(",\"img\":").append(getPortraitVersion(npcId)).append('}');
			first = false;
		}
		json.append("]}");
		server.sendJson(exchange, 200, json.toString());
	}

	private void handleAdminPortrait(HttpExchange exchange) throws IOException
	{
		final PassiveTreeApiServer server = PassiveTreeApiServer.getInstance();
		final Player gm = server.authenticateAdmin(exchange, ADMIN_COMMAND);
		if (gm == null)
		{
			return;
		}

		// The look from the address only: readParams would read the body (the picture) as a form and close it.
		final String lookParam = queryParam(exchange, "k");
		final int look = lookParam != null ? parseLook(lookParam) : getLook(parseInt(queryParam(exchange, "id")));
		if ((look == 0) || !getLookMap().containsValue(look))
		{
			server.sendText(exchange, 400, "application/json", "{\"error\":\"no such look\"}");
			return;
		}
		final byte[] picture = server.readBody(exchange, MAX_PORTRAIT_BYTES);
		final String type = picture == null ? null : pictureType(picture);
		if (type == null)
		{
			server.sendText(exchange, 400, "application/json", "{\"error\":\"send a png or webp picture of at most 1 MB\"}");
			return;
		}

		// Written beside, then moved in place, so the page never reads half a picture; the other types of this look's portrait go.
		Files.createDirectories(LOOKS);
		final String name = lookName(look);
		final Path target = LOOKS.resolve(name + "." + type);
		final Path temp = LOOKS.resolve(name + "." + type + ".tmp");
		Files.write(temp, picture);
		Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
		for (String other : PORTRAIT_TYPES)
		{
			if (!other.equals(type))
			{
				Files.deleteIfExists(LOOKS.resolve(name + "." + other));
			}
		}
		_portraitsTime = 0;
		server.sendText(exchange, 200, "application/json", "{\"saved\":true}");
	}

	/** @return "png" or "webp" when the bytes are such a picture, otherwise {@code null} */
	private static String pictureType(byte[] bytes)
	{
		if ((bytes.length > 8) && ((bytes[0] & 0xFF) == 0x89) && (bytes[1] == 'P') && (bytes[2] == 'N') && (bytes[3] == 'G'))
		{
			return "png";
		}
		if ((bytes.length > 12) && (bytes[0] == 'R') && (bytes[1] == 'I') && (bytes[2] == 'F') && (bytes[3] == 'F') && (bytes[8] == 'W') && (bytes[9] == 'E') && (bytes[10] == 'B') && (bytes[11] == 'P'))
		{
			return "webp";
		}
		return null;
	}

	private void handleAdminView(HttpExchange exchange) throws IOException
	{
		final PassiveTreeApiServer server = PassiveTreeApiServer.getInstance();
		final Player gm = server.authenticateAdmin(exchange, ADMIN_COMMAND);
		if (gm == null)
		{
			return;
		}

		final byte[] body = server.readBody(exchange, 1024);
		final double yaw;
		final double pitch;
		try
		{
			final Object parsed = body == null ? null : SimpleJson.parse(new String(body, StandardCharsets.UTF_8));
			if (!(parsed instanceof Map))
			{
				throw new IllegalArgumentException("expected {\"yaw\":..,\"pitch\":..}");
			}
			yaw = number(((Map<?, ?>) parsed).get("yaw"), 0, -360, 360);
			pitch = number(((Map<?, ?>) parsed).get("pitch"), 12, -45, 60);
		}
		catch (IllegalArgumentException e)
		{
			server.sendText(exchange, 400, "application/json", "{\"error\":" + SimpleJson.quote(e.getMessage()) + "}");
			return;
		}

		Files.createDirectories(MODELS);
		final String json = "{\"yaw\":" + yaw + ",\"pitch\":" + pitch + "}";
		Files.write(VIEW, json.getBytes(StandardCharsets.UTF_8));
		LOGGER.info(getClass().getSimpleName() + ": " + gm.getName() + " turned the bestiary models: yaw " + yaw + ", pitch " + pitch + ".");
		server.sendText(exchange, 200, "application/json", json);
	}

	private static double number(Object value, double defaultValue, double min, double max)
	{
		if (value == null)
		{
			return defaultValue;
		}
		if (!(value instanceof Double))
		{
			throw new IllegalArgumentException("not a number: " + value);
		}
		return Math.max(min, Math.min(max, (Double) value));
	}

	/** @return a value of the query string as it is written there (not decoded), or {@code null} */
	private static String queryParam(HttpExchange exchange, String name)
	{
		final String query = exchange.getRequestURI().getRawQuery();
		if (query == null)
		{
			return null;
		}
		for (String part : query.split("&"))
		{
			if (part.startsWith(name + "="))
			{
				return part.substring(name.length() + 1);
			}
		}
		return null;
	}

	private static int parseInt(String value)
	{
		if (value == null)
		{
			return 0;
		}
		try
		{
			return Integer.parseInt(value.trim());
		}
		catch (NumberFormatException e)
		{
			return 0;
		}
	}

	public static BestiaryArt getInstance()
	{
		return SingletonHolder.INSTANCE;
	}

	private static class SingletonHolder
	{
		protected static final BestiaryArt INSTANCE = new BestiaryArt();
	}
}
