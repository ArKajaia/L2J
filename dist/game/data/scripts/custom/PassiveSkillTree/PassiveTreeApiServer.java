package custom.PassiveSkillTree;

import java.io.IOException;
import java.io.OutputStream;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.net.URLDecoder;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Logger;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import org.l2jmobius.commons.util.SimpleJson;
import org.l2jmobius.gameserver.config.custom.PassiveTreeConfig;
import org.l2jmobius.gameserver.data.custom.PassiveTreeData;
import org.l2jmobius.gameserver.data.custom.PassiveTreeEditor;
import org.l2jmobius.gameserver.data.xml.AdminData;
import org.l2jmobius.gameserver.data.xml.SkillData;
import org.l2jmobius.gameserver.managers.PassiveTreeManager;
import org.l2jmobius.gameserver.model.World;
import org.l2jmobius.gameserver.model.actor.Player;
import org.l2jmobius.gameserver.model.passivetree.PassiveMechanics;
import org.l2jmobius.gameserver.model.passivetree.PassiveNode;
import org.l2jmobius.gameserver.model.passivetree.PassiveStatBonusCache;
import org.l2jmobius.gameserver.model.passivetree.WebRateLimiter;
import org.l2jmobius.gameserver.model.skill.Skill;

/**
 * Minimal, dependency-free (JDK-only) HTTP API backing the web visual tree planner, plus static hosting for the planner page itself.
 * <p>
 * ".treelink" hands out a direct clickable link with a signed token baked into the URL - the client can open web pages in-game, so there's no need for the player to type anything. A short-PIN fallback (/resolve) is still here underneath for anyone whose client can't do that: the same token this
 * class mints for a direct link is what a PIN eventually resolves to as well, so both paths lead to the exact same signed credential.
 * <p>
 * Endpoints (everything that changes something is POST; the token travels in an "Authorization: Bearer" header, never in a URL, and a POST from another site is refused):
 * <ul>
 * <li>GET /passive-tree.html - the visual tree page itself (static file on disk)</li>
 * <li>GET /api/passivetree/nodes - full node list, public, no auth needed</li>
 * <li>GET /api/passivetree/config - respec and reset costs and the stat caps, public</li>
 * <li>POST /api/passivetree/resolve (pin=...) - PIN fallback: exchanges a short PIN for a real token, with a lockout after wrong guesses</li>
 * <li>GET /api/passivetree/character - one player's LIVE allocation state</li>
 * <li>POST /api/passivetree/allocate (nodeId=...) - allocates one node</li>
 * <li>POST /api/passivetree/deallocate (nodeId=...) - refunds one node for the configured Adena per point</li>
 * <li>POST /api/passivetree/reset - clears the whole tree for the configured reset cost</li>
 * <li>POST /api/passivetree/template (id=...) - switches to another template (peace zone only, with a wait between switches)</li>
 * <li>GET /api/passivetree/inspect?id=... - the build (gear, stats, passive tree) of the player or fake player someone used ".gear" on</li>
 * <li>POST /api/passivetree/import (nodes=...) - allocates a copied build's nodes (comma-separated ids, the ones to take first first) on top of the current tree</li>
 * </ul>
 * The tree editor for GMs (opened with //passivetree) has its own page and endpoints. Its token is made by {@link #generateAdminToken}, can't be mistaken for a player's, and only works while that GM is online and still allowed to use //passivetree:
 * <ul>
 * <li>GET /passive-tree-admin.html - the editor page</li>
 * <li>GET /api/passivetree/admin/tree - every node as the files hold it, the files' version, and how many characters have each node</li>
 * <li>GET /api/passivetree/admin/skill?id=...&amp;level=... - a skill's name and highest level</li>
 * <li>POST /api/passivetree/admin/save (JSON: version, dryRun, nodes) - checks the tree, and unless dryRun, writes the files (after a backup) and makes the tree live</li>
 * <li>POST /api/passivetree/admin/reload - loads the files again, after they were edited by hand</li>
 * </ul>
 */
public class PassiveTreeApiServer
{
	private static final Logger LOGGER = Logger.getLogger(PassiveTreeApiServer.class.getName());
	
	/** The placeholder that used to be committed here. It is treated as "no secret set", since anyone can read it. */
	private static final String OLD_PLACEHOLDER_SECRET = "CHANGE_ME_TO_A_LONG_RANDOM_SECRET_STRING";
	
	private static final int MAX_BODY_BYTES = 32 * 1024; // a full import is ~2000 ids, so this is generous
	private static final long RATE_WINDOW_MS = 60 * 1000L;
	private static final long CLEANUP_INTERVAL_MS = 60 * 1000L;
	
	private static final String CSP = "default-src 'self'; script-src 'self' 'unsafe-inline'; style-src 'self' 'unsafe-inline'; img-src 'self' data: blob:; connect-src 'self'; object-src 'none'; base-uri 'none'; form-action 'none'; frame-ancestors 'none'";
	
	private static final long INSPECT_VALID_MS = 30 * 60 * 1000L; // how long a ".gear" snapshot stays viewable
	private static final int MAX_INSPECTS = 2000; // snapshots kept at once, so a flood of ".gear" can't eat the memory
	private static final int MAX_IMPORT_NODES = 2000; // more ids than the tree has nodes in one import is never a real build
	
	private static final Path HTML_FILE = Path.of("data/html/custom/passive-tree.html");
	private static final Path ADMIN_HTML_FILE = Path.of("data/html/custom/passive-tree-admin.html");
	private static final String ADMIN_PAGE = "passive-tree-admin.html";
	
	/** What a GM's access level must allow for the tree editor to work: the command that opens it. */
	private static final String ADMIN_COMMAND = "admin_passivetree";
	private static final String ADMIN_TOKEN_PREFIX = "A";
	private static final int MAX_ADMIN_BODY_BYTES = 8 * 1024 * 1024; // the whole tree as JSON is ~400 KB
	
	private final Map<String, long[]> pins = new ConcurrentHashMap<>(); // pin -> {charId, classIndex, expiry}
	private final Map<String, Inspect> inspects = new ConcurrentHashMap<>(); // random id -> ".gear" snapshot
	private final SecureRandom random = new SecureRandom();
	private final AtomicLong lastCleanup = new AtomicLong();
	
	private volatile byte[] secret;
	private WebRateLimiter apiLimiter;
	private WebRateLimiter pinLimiter;
	
	private HttpServer server;
	
	public void start()
	{
		try
		{
			apiLimiter = new WebRateLimiter(PassiveTreeConfig.WEB_RATE_LIMIT_PER_MINUTE, RATE_WINDOW_MS);
			pinLimiter = new WebRateLimiter(PassiveTreeConfig.WEB_PIN_RATE_LIMIT_PER_MINUTE, RATE_WINDOW_MS);
			getSecret(); // log the warning at start, not at the first link
			
			server = HttpServer.create(new InetSocketAddress(PassiveTreeConfig.WEB_PORT), 0);
			route("/passive-tree.html", "GET", false, this::handleStaticPage);
			route("/api/passivetree/nodes", "GET", false, this::handleNodes);
			route("/api/passivetree/config", "GET", false, this::handleConfig);
			route("/api/passivetree/inspect", "GET", false, this::handleInspect);
			route("/api/passivetree/character", "GET", false, this::handleCharacter);
			route("/api/passivetree/resolve", "POST", true, this::handleResolve);
			route("/api/passivetree/allocate", "POST", true, this::handleAllocate);
			route("/api/passivetree/deallocate", "POST", true, this::handleDeallocate);
			route("/api/passivetree/reset", "POST", true, this::handleReset);
			route("/api/passivetree/template", "POST", true, this::handleTemplate);
			route("/api/passivetree/import", "POST", true, this::handleImport);
			route("/" + ADMIN_PAGE, "GET", false, this::handleAdminPage);
			route("/api/passivetree/admin/tree", "GET", false, this::handleAdminTree);
			route("/api/passivetree/admin/skill", "GET", false, this::handleAdminSkill);
			route("/api/passivetree/admin/save", "POST", true, this::handleAdminSave);
			route("/api/passivetree/admin/reload", "POST", true, this::handleAdminReload);
			server.setExecutor(Executors.newFixedThreadPool(2));
			server.start();
			LOGGER.info("PassiveTreeApiServer: listening on port " + PassiveTreeConfig.WEB_PORT);
		}
		catch (IOException e)
		{
			LOGGER.warning("PassiveTreeApiServer: failed to start - " + e.getMessage());
		}
	}
	
	@FunctionalInterface
	private interface Handler
	{
		void handle(HttpExchange exchange) throws IOException;
	}
	
	/**
	 * Registers a path with the checks every request goes through: the method, the rate limit, and for anything that changes state, that it comes from this page and not from another site.
	 */
	private void route(String path, String method, boolean stateChanging, Handler handler)
	{
		server.createContext(path, exchange ->
		{
			try
			{
				if (!method.equals(exchange.getRequestMethod()))
				{
					exchange.getResponseHeaders().add("Allow", method);
					sendText(exchange, 405, "application/json", "{\"error\":\"use " + method + "\"}");
					return;
				}
				
				final long now = System.currentTimeMillis();
				cleanupLimitersIfDue(now);
				final String ip = remoteAddress(exchange);
				if (path.startsWith("/api/") && !apiLimiter.tryAcquire(ip, now))
				{
					tooManyRequests(exchange, apiLimiter.retryAfterSeconds(ip, now));
					return;
				}
				
				if (stateChanging && !isSameOrigin(exchange))
				{
					sendText(exchange, 403, "application/json", "{\"error\":\"cross-site request refused\"}");
					return;
				}
				
				handler.handle(exchange);
			}
			catch (RuntimeException e)
			{
				LOGGER.warning("PassiveTreeApiServer: " + path + " failed - " + e);
				sendText(exchange, 500, "application/json", "{\"error\":\"server error\"}");
			}
			finally
			{
				exchange.close();
			}
		});
	}
	
	private void cleanupLimitersIfDue(long now)
	{
		final long last = lastCleanup.get();
		if (((now - last) >= CLEANUP_INTERVAL_MS) && lastCleanup.compareAndSet(last, now))
		{
			apiLimiter.cleanup(now);
			pinLimiter.cleanup(now);
		}
	}
	
	private String remoteAddress(HttpExchange exchange)
	{
		// Deliberately the socket address: X-Forwarded-For is whatever the client says it is. Behind a reverse proxy every request shares the proxy's address, so raise the limits (see PassiveTree.ini).
		return exchange.getRemoteAddress().getAddress().getHostAddress();
	}
	
	/**
	 * A browser on another site can send a POST here (it just can't read the answer), so a request that names an Origin is only accepted when that origin is this server's own page.
	 * Requests with no Origin at all (curl, other tools) can't be forged by a web page, and still have to carry the token in a header.
	 */
	private boolean isSameOrigin(HttpExchange exchange)
	{
		final String origin = exchange.getRequestHeaders().getFirst("Origin");
		if (origin == null)
		{
			return true;
		}
		
		try
		{
			final String authority = new URI(origin).getRawAuthority();
			if (authority == null)
			{
				return false;
			}
			final String host = exchange.getRequestHeaders().getFirst("Host");
			if (authority.equalsIgnoreCase(host))
			{
				return true;
			}
			final String configured = new URI(PassiveTreeConfig.WEB_BASE_URL).getRawAuthority();
			return authority.equalsIgnoreCase(configured);
		}
		catch (Exception e)
		{
			return false;
		}
	}
	
	private void tooManyRequests(HttpExchange exchange, long retryAfterSeconds) throws IOException
	{
		exchange.getResponseHeaders().add("Retry-After", String.valueOf(retryAfterSeconds));
		sendText(exchange, 429, "application/json", "{\"error\":\"too many requests, try again in " + retryAfterSeconds + "s\"}");
	}
	
	public void stop()
	{
		if (server != null)
		{
			server.stop(0);
		}
	}
	
	// ------------------------------------------------------------------
	// PIN generation (called by the .treelink voiced command)
	// ------------------------------------------------------------------
	/** @return a fresh 6-digit PIN, valid for the configured PIN lifetime, tied to this character+class. */
	public String generatePin(int charId, int classIndex)
	{
		cleanupExpiredPins();
		
		String pin;
		do
		{
			pin = String.format("%06d", random.nextInt(1_000_000));
		}
		while (pins.containsKey(pin));
		
		pins.put(pin, new long[]
		{
			charId,
			classIndex,
			System.currentTimeMillis() + (PassiveTreeConfig.WEB_PIN_LIFETIME * 1000L)
		});
		return pin;
	}
	
	private void cleanupExpiredPins()
	{
		final long now = System.currentTimeMillis();
		pins.entrySet().removeIf(e -> e.getValue()[2] < now);
	}
	
	// ------------------------------------------------------------------
	// ".gear" snapshots
	// ------------------------------------------------------------------
	/**
	 * A build as it was when ".gear" was used (see {@link BuildSnapshot}).
	 * @param json the build
	 * @param expiry when it stops being viewable
	 */
	private static final class Inspect
	{
		private final String _json;
		private final long _expiry;
		
		Inspect(String json, long expiry)
		{
			_json = json;
			_expiry = expiry;
		}
		
		String json()
		{
			return _json;
		}
		
		long expiry()
		{
			return _expiry;
		}
	}
	
	/**
	 * Keeps a build for INSPECT_VALID_MS. The id is random and long, so it can't be guessed: only who used ".gear" (and whoever they share the link with) can see it.
	 * @param json the build, from {@link BuildSnapshot}
	 * @return the id the page reads it back with
	 */
	public String registerInspect(String json)
	{
		final long now = System.currentTimeMillis();
		inspects.values().removeIf(inspect -> inspect.expiry() < now);
		while (inspects.size() >= MAX_INSPECTS)
		{
			inspects.entrySet().stream().min((a, b) -> Long.compare(a.getValue().expiry(), b.getValue().expiry())).ifPresent(oldest -> inspects.remove(oldest.getKey()));
		}
		
		final byte[] raw = new byte[18];
		random.nextBytes(raw);
		final String id = Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
		inspects.put(id, new Inspect(json, now + INSPECT_VALID_MS));
		return id;
	}
	
	// ------------------------------------------------------------------
	// Token sign/verify (the real credential, once resolved from a PIN)
	// ------------------------------------------------------------------
	// Public now: .treelink calls this directly to hand out a clickable
	// link, rather than only being reachable indirectly through a PIN.
	public String generateToken(int charId, int classIndex)
	{
		final long expiry = System.currentTimeMillis() + (PassiveTreeConfig.WEB_TOKEN_LIFETIME * 1000L);
		final String payload = charId + "." + classIndex + "." + expiry;
		final String signature = sign(payload);
		final String encodedPayload = Base64.getUrlEncoder().withoutPadding().encodeToString(payload.getBytes(StandardCharsets.UTF_8));
		return encodedPayload + "." + signature;
	}
	
	/** The signing secret: the configured one, or a random one for this run when none was set (or the old committed placeholder was left in). */
	private byte[] getSecret()
	{
		byte[] value = secret;
		if (value == null)
		{
			synchronized (this)
			{
				value = secret;
				if (value == null)
				{
					final String configured = PassiveTreeConfig.WEB_SECRET;
					if (configured.isEmpty() || configured.equals(OLD_PLACEHOLDER_SECRET))
					{
						value = new byte[32];
						random.nextBytes(value);
						LOGGER.warning("PassiveTreeApiServer: PassiveTreeWebSecret is not set in PassiveTree.ini - using a random secret for this run, so links stop working at a restart. Set a long random one.");
					}
					else
					{
						if (configured.length() < 16)
						{
							LOGGER.warning("PassiveTreeApiServer: PassiveTreeWebSecret is short - use at least 32 random characters.");
						}
						value = configured.getBytes(StandardCharsets.UTF_8);
					}
					secret = value;
				}
			}
		}
		return value;
	}
	
	private String sign(String payload)
	{
		try
		{
			final Mac mac = Mac.getInstance("HmacSHA256");
			mac.init(new SecretKeySpec(getSecret(), "HmacSHA256"));
			final byte[] raw = mac.doFinal(payload.getBytes(StandardCharsets.UTF_8));
			final StringBuilder hex = new StringBuilder();
			for (byte b : raw)
			{
				hex.append(String.format("%02x", b));
			}
			return hex.toString();
		}
		catch (Exception e)
		{
			throw new RuntimeException(e);
		}
	}
	
	/**
	 * A link to the tree editor for a GM, the same kind of signed token as a player's but marked as an admin one, so neither can be used as the other.
	 * @param charId the GM's character
	 * @return the token, valid for PassiveTreeWebAdminTokenLifetime seconds
	 */
	public String generateAdminToken(int charId)
	{
		final long expiry = System.currentTimeMillis() + (PassiveTreeConfig.WEB_ADMIN_TOKEN_LIFETIME * 1000L);
		final String payload = ADMIN_TOKEN_PREFIX + "." + charId + "." + expiry;
		return Base64.getUrlEncoder().withoutPadding().encodeToString(payload.getBytes(StandardCharsets.UTF_8)) + "." + sign(payload);
	}
	
	/**
	 * @param token an admin token
	 * @return the tree editor's address with the token, next to the planner page (PassiveTreeWebBaseUrl)
	 */
	public String getAdminUrl(String token)
	{
		String base;
		try
		{
			base = URI.create(PassiveTreeConfig.WEB_BASE_URL).resolve(ADMIN_PAGE).toString();
		}
		catch (IllegalArgumentException e)
		{
			base = "http://127.0.0.1:" + PassiveTreeConfig.WEB_PORT + "/" + ADMIN_PAGE;
		}
		return base + "?admin=" + token;
	}
	
	/** @return the payload of a token whose signature is right, otherwise {@code null} */
	private String verifiedPayload(String token)
	{
		try
		{
			final int lastDot = token.lastIndexOf('.');
			final String encodedPayload = token.substring(0, lastDot);
			final String signature = token.substring(lastDot + 1);
			final String payload = new String(Base64.getUrlDecoder().decode(encodedPayload), StandardCharsets.UTF_8);
			return MessageDigest.isEqual(sign(payload).getBytes(StandardCharsets.UTF_8), signature.getBytes(StandardCharsets.UTF_8)) ? payload : null;
		}
		catch (Exception e)
		{
			return null;
		}
	}
	
	/** @return the GM's character id if this is a valid, unexpired admin token, otherwise -1 */
	private int verifyAdminToken(String token)
	{
		final String payload = verifiedPayload(token);
		if (payload == null)
		{
			return -1;
		}
		try
		{
			final String[] parts = payload.split("\\.");
			if ((parts.length != 3) || !parts[0].equals(ADMIN_TOKEN_PREFIX) || (System.currentTimeMillis() > Long.parseLong(parts[2])))
			{
				return -1;
			}
			return Integer.parseInt(parts[1]);
		}
		catch (NumberFormatException e)
		{
			return -1;
		}
	}
	
	/** @return {@code [charId, classIndex]} if valid and unexpired, otherwise {@code null}. */
	private int[] verifyToken(String token)
	{
		try
		{
			final String payload = verifiedPayload(token);
			if (payload == null)
			{
				return null;
			}
			
			final String[] parts = payload.split("\\.");
			final int charId = Integer.parseInt(parts[0]);
			final int classIndex = Integer.parseInt(parts[1]);
			final long expiry = Long.parseLong(parts[2]);
			
			if (System.currentTimeMillis() > expiry)
			{
				return null;
			}
			
			return new int[]
			{
				charId,
				classIndex
			};
		}
		catch (Exception e)
		{
			return null;
		}
	}
	
	// ------------------------------------------------------------------
	// Handlers
	// ------------------------------------------------------------------
	private void handleStaticPage(HttpExchange exchange) throws IOException
	{
		sendPage(exchange, HTML_FILE);
	}
	
	private void sendPage(HttpExchange exchange, Path file) throws IOException
	{
		if (!Files.exists(file))
		{
			sendText(exchange, 404, "text/plain", file.getFileName() + " not found at " + file.toAbsolutePath());
			return;
		}
		
		final byte[] bytes = Files.readAllBytes(file);
		exchange.getResponseHeaders().add("Content-Type", "text/html; charset=UTF-8");
		addSecurityHeaders(exchange);
		exchange.sendResponseHeaders(200, bytes.length);
		try (OutputStream os = exchange.getResponseBody())
		{
			os.write(bytes);
		}
	}
	
	private void handleNodes(HttpExchange exchange) throws IOException
	{
		final StringBuilder json = new StringBuilder();
		json.append("[");
		boolean first = true;
		for (PassiveNode node : PassiveTreeData.getInstance().getAllNodes().values())
		{
			if (!first)
			{
				json.append(",");
			}
			first = false;
			json.append("{").append("\"id\":").append(node.getId()).append(",").append("\"name\":\"").append(escape(node.getName())).append("\",").append("\"sector\":\"").append(escape(node.getSector())).append("\",").append("\"type\":\"").append(node.getType()).append("\",").append("\"cost\":").append(node.getCost()).append(",").append("\"x\":").append(node.getX()).append(",").append("\"y\":").append(node.getY()).append(",").append("\"effect\":\"").append(escape(node.getEffectSpec())).append("\",").append("\"description\":\"").append(escape(node.getDescription())).append("\",").append("\"parents\":[").append(joinInts(node.getParents())).append("]");
			if (node.hasOrbit())
			{
				json.append(",\"ox\":").append(node.getOrbitX()).append(",\"oy\":").append(node.getOrbitY());
			}
			if (!node.getIcon().isEmpty())
			{
				json.append(",\"icon\":\"").append(escape(node.getIcon())).append("\"");
			}
			json.append("}");
		}
		json.append("]");
		sendText(exchange, 200, "application/json", json.toString());
	}
	
	private void handleResolve(HttpExchange exchange) throws IOException
	{
		final long now = System.currentTimeMillis();
		final String ip = remoteAddress(exchange);
		
		if (pinLimiter.isLockedOut(ip, now) || pinLimiter.isLockedOut("*", now))
		{
			final long wait = Math.max(pinLimiter.lockoutSeconds(ip, now), pinLimiter.lockoutSeconds("*", now));
			tooManyRequests(exchange, wait);
			return;
		}
		if (!pinLimiter.tryAcquire(ip, now))
		{
			tooManyRequests(exchange, pinLimiter.retryAfterSeconds(ip, now));
			return;
		}
		
		final String pin = readParams(exchange).get("pin");
		if (pin == null)
		{
			sendText(exchange, 400, "application/json", "{\"error\":\"missing pin\"}");
			return;
		}
		
		cleanupExpiredPins();
		final long[] entry = pins.get(pin);
		if (entry == null)
		{
			final long lockoutMs = PassiveTreeConfig.WEB_PIN_LOCKOUT * 1000L;
			pinLimiter.recordFailure(ip, now, PassiveTreeConfig.WEB_PIN_MAX_FAILURES, lockoutMs);
			// Guessing from many addresses at once: when the whole server sees far more wrong PINs than players ever type, stop taking PINs for a while.
			pinLimiter.recordFailure("*", now, PassiveTreeConfig.WEB_PIN_MAX_FAILURES * 20, lockoutMs);
			sendText(exchange, 401, "application/json", "{\"error\":\"invalid or expired code\"}");
			return;
		}
		
		pinLimiter.clearFailures(ip);
		final String token = generateToken((int) entry[0], (int) entry[1]);
		sendText(exchange, 200, "application/json", "{\"token\":\"" + token + "\"}");
	}
	
	/** @return the player the Bearer token names, or {@code null} after the error answer was sent. */
	private Player authenticate(HttpExchange exchange) throws IOException
	{
		final String header = exchange.getRequestHeaders().getFirst("Authorization");
		if ((header == null) || !header.regionMatches(true, 0, "Bearer ", 0, 7))
		{
			sendText(exchange, 401, "application/json", "{\"error\":\"missing token\"}");
			return null;
		}
		
		final int[] verified = verifyToken(header.substring(7).trim());
		if (verified == null)
		{
			sendText(exchange, 401, "application/json", "{\"error\":\"invalid or expired token\"}");
			return null;
		}
		
		final Player player = resolvePlayer(verified);
		if (player == null)
		{
			sendText(exchange, 404, "application/json", "{\"error\":\"character not online on this class\"}");
		}
		return player;
	}
	
	private void handleCharacter(HttpExchange exchange) throws IOException
	{
		final Player player = authenticate(exchange);
		if (player != null)
		{
			sendText(exchange, 200, "application/json", buildCharacterJson(player));
		}
	}
	
	private void handleAllocate(HttpExchange exchange) throws IOException
	{
		final Player player = authenticate(exchange);
		if (player == null)
		{
			return;
		}
		
		final Integer nodeId = parseIntParam(readParams(exchange).get("nodeId"));
		if (nodeId == null)
		{
			sendText(exchange, 400, "application/json", "{\"error\":\"bad nodeId\"}");
			return;
		}
		
		final boolean success = PassiveTreeManager.getInstance().allocate(player, nodeId);
		
		final StringBuilder json = new StringBuilder();
		json.append("{\"success\":").append(success).append(",").append("\"character\":").append(buildCharacterJson(player)).append("}");
		
		sendText(exchange, success ? 200 : 409, "application/json", json.toString());
	}
	
	private void handleDeallocate(HttpExchange exchange) throws IOException
	{
		final Player player = authenticate(exchange);
		if (player == null)
		{
			return;
		}
		
		final Integer nodeId = parseIntParam(readParams(exchange).get("nodeId"));
		if (nodeId == null)
		{
			sendText(exchange, 400, "application/json", "{\"error\":\"bad nodeId\"}");
			return;
		}
		
		final PassiveTreeManager.DeallocateResult result = PassiveTreeManager.getInstance().deallocateNode(player, nodeId);
		final boolean success = result == PassiveTreeManager.DeallocateResult.OK;
		
		final StringBuilder json = new StringBuilder();
		json.append("{\"success\":").append(success).append(",").append("\"reason\":\"").append(result.name()).append("\",").append("\"character\":").append(buildCharacterJson(player)).append("}");
		
		sendText(exchange, success ? 200 : 409, "application/json", json.toString());
	}
	
	/** Full-tree reset - the same PassiveTreeManager.resetTree() the Community Board button calls, so both charge the same cost and clear the same rows. */
	private void handleReset(HttpExchange exchange) throws IOException
	{
		final Player player = authenticate(exchange);
		if (player == null)
		{
			return;
		}
		
		final boolean success = PassiveTreeManager.getInstance().resetTree(player);
		
		final StringBuilder json = new StringBuilder();
		json.append("{\"success\":").append(success).append(",").append("\"character\":").append(buildCharacterJson(player)).append("}");
		
		sendText(exchange, success ? 200 : 409, "application/json", json.toString());
	}
	
	/** Switches the active template. The peace-zone and wait rules live in PassiveTreeManager, so the Community Board and this page can never disagree. */
	private void handleTemplate(HttpExchange exchange) throws IOException
	{
		final Player player = authenticate(exchange);
		if (player == null)
		{
			return;
		}
		
		final Integer templateId = parseIntParam(readParams(exchange).get("id"));
		if (templateId == null)
		{
			sendText(exchange, 400, "application/json", "{\"error\":\"bad id\"}");
			return;
		}
		
		final PassiveTreeManager mgr = PassiveTreeManager.getInstance();
		final PassiveTreeManager.SwitchResult result = mgr.switchTemplate(player, templateId);
		final boolean success = result == PassiveTreeManager.SwitchResult.OK;
		final String message = success ? "Template " + templateId + " is now active." : mgr.getSwitchFailureMessage(player, result);
		
		final StringBuilder json = new StringBuilder();
		json.append("{\"success\":").append(success).append(",").append("\"reason\":\"").append(result.name()).append("\",").append("\"message\":\"").append(escape(message)).append("\",").append("\"character\":").append(buildCharacterJson(player)).append("}");
		
		sendText(exchange, success ? 200 : 409, "application/json", json.toString());
	}
	
	/** Exposes the respec and reset costs and the stat caps so the web page never has to hardcode them. */
	private void handleConfig(HttpExchange exchange) throws IOException
	{
		final StringBuilder json = new StringBuilder();
		json.append("{\"respecAdenaPerPoint\":").append(PassiveTreeConfig.RESPEC_ADENA_PER_POINT).append(",").append("\"resetCost\":\"").append(escape(PassiveTreeManager.getInstance().getResetCostText())).append("\",\"caps\":{");
		boolean first = true;
		for (Map.Entry<String, Double> cap : PassiveStatBonusCache.getCaps().entrySet())
		{
			if (!first)
			{
				json.append(",");
			}
			first = false;
			json.append("\"").append(escape(cap.getKey())).append("\":").append(cap.getValue());
		}
		json.append("}}");
		sendText(exchange, 200, "application/json", json.toString());
	}
	
	/** The build a ".gear" link points at. Public like /nodes: the random id is what keeps it private. */
	private void handleInspect(HttpExchange exchange) throws IOException
	{
		final String id = readParams(exchange).get("id");
		final Inspect inspect = id != null ? inspects.get(id) : null;
		if ((inspect == null) || (inspect.expiry() < System.currentTimeMillis()))
		{
			sendText(exchange, 404, "application/json", "{\"error\":\"this build link has expired - use .gear again\"}");
			return;
		}
		sendText(exchange, 200, "application/json", inspect.json());
	}
	
	/** Copies a build into the player's tree. The rules are PassiveTreeManager.importNodes(): the same checks as allocating each node by hand. */
	private void handleImport(HttpExchange exchange) throws IOException
	{
		final Player player = authenticate(exchange);
		if (player == null)
		{
			return;
		}
		
		final String nodesParam = readParams(exchange).get("nodes");
		if (nodesParam == null)
		{
			sendText(exchange, 400, "application/json", "{\"error\":\"missing nodes\"}");
			return;
		}
		
		final List<Integer> nodeIds = new ArrayList<>();
		for (String part : nodesParam.split(","))
		{
			if (part.isEmpty())
			{
				continue;
			}
			final Integer id = parseIntParam(part);
			if (id == null)
			{
				sendText(exchange, 400, "application/json", "{\"error\":\"bad node id\"}");
				return;
			}
			nodeIds.add(id);
		}
		if (nodeIds.isEmpty() || (nodeIds.size() > MAX_IMPORT_NODES))
		{
			sendText(exchange, 400, "application/json", "{\"error\":\"no nodes to import\"}");
			return;
		}
		
		final PassiveTreeManager.ImportResult result = PassiveTreeManager.getInstance().importNodes(player, nodeIds);
		if (result.added() > 0)
		{
			player.sendMessage("Passive tree: imported " + result.added() + " node(s) for " + result.points() + " point(s)" + (result.skipped() > 0 ? ", " + result.skipped() + " could not be taken." : "."));
		}
		
		final StringBuilder json = new StringBuilder();
		json.append("{\"success\":").append(result.added() > 0).append(",\"added\":").append(result.added()).append(",\"owned\":").append(result.owned()).append(",\"skipped\":").append(result.skipped()).append(",\"points\":").append(result.points()).append(",\"character\":").append(buildCharacterJson(player)).append("}");
		sendText(exchange, result.added() > 0 ? 200 : 409, "application/json", json.toString());
	}
	
	// ------------------------------------------------------------------
	// Tree editor (GMs)
	// ------------------------------------------------------------------
	private void handleAdminPage(HttpExchange exchange) throws IOException
	{
		sendPage(exchange, ADMIN_HTML_FILE);
	}
	
	/**
	 * @return the GM the admin token names, or {@code null} after the error answer was sent. The GM has to be online and still allowed to use //passivetree, so a link stops working when its GM logs off or loses the right.
	 */
	private Player authenticateAdmin(HttpExchange exchange) throws IOException
	{
		final String header = exchange.getRequestHeaders().getFirst("Authorization");
		final int charId = (header != null) && header.regionMatches(true, 0, "Bearer ", 0, 7) ? verifyAdminToken(header.substring(7).trim()) : -1;
		if (charId < 0)
		{
			sendText(exchange, 401, "application/json", "{\"error\":\"this editor link is invalid or has expired - use //passivetree again\"}");
			return null;
		}
		
		final Player gm = World.getInstance().getPlayer(charId);
		if (gm == null)
		{
			sendText(exchange, 403, "application/json", "{\"error\":\"the GM who opened this editor is not online - log in and use //passivetree again\"}");
			return null;
		}
		if (!AdminData.getInstance().hasAccess(ADMIN_COMMAND, gm.getAccessLevel()))
		{
			sendText(exchange, 403, "application/json", "{\"error\":\"your access level can't use //passivetree\"}");
			return null;
		}
		return gm;
	}
	
	private void handleAdminTree(HttpExchange exchange) throws IOException
	{
		final Player gm = authenticateAdmin(exchange);
		if (gm == null)
		{
			return;
		}
		
		final PassiveTreeEditor.Snapshot snapshot;
		try
		{
			snapshot = PassiveTreeEditor.read();
		}
		catch (IOException e)
		{
			fileError(exchange, "read", e);
			return;
		}
		
		final StringBuilder json = new StringBuilder(snapshot.nodes().size() * 300);
		json.append("{\"version\":").append(SimpleJson.quote(snapshot.version()));
		json.append(",\"admin\":").append(SimpleJson.quote(gm.getName()));
		json.append(",\"types\":[");
		final PassiveNode.NodeType[] types = PassiveNode.NodeType.values();
		for (int i = 0; i < types.length; i++)
		{
			json.append(i > 0 ? "," : "").append(SimpleJson.quote(types[i].name()));
		}
		json.append("],\"conditions\":[");
		for (int i = 0; i < PassiveMechanics.CONDITION_TOKENS.size(); i++)
		{
			json.append(i > 0 ? "," : "").append(SimpleJson.quote(PassiveMechanics.CONDITION_TOKENS.get(i)));
		}
		json.append("],\"allocations\":{");
		boolean first = true;
		for (Map.Entry<Integer, Integer> entry : PassiveTreeEditor.allocationCounts().entrySet())
		{
			json.append(first ? "" : ",").append('"').append(entry.getKey()).append("\":").append(entry.getValue());
			first = false;
		}
		json.append("},\"nodes\":[");
		first = true;
		for (PassiveTreeEditor.EditNode node : snapshot.nodes())
		{
			json.append(first ? "" : ",").append(PassiveTreeEditor.toJson(node));
			first = false;
		}
		json.append("]}");
		sendText(exchange, 200, "application/json", json.toString());
	}
	
	private void handleAdminSkill(HttpExchange exchange) throws IOException
	{
		if (authenticateAdmin(exchange) == null)
		{
			return;
		}
		
		final Map<String, String> params = readParams(exchange);
		final Integer id = parseIntParam(params.get("id"));
		final Integer level = parseIntParam(params.get("level"));
		final int maxLevel = (id != null) && (id > 0) ? SkillData.getInstance().getMaxLevel(id) : 0;
		if (maxLevel <= 0)
		{
			sendText(exchange, 404, "application/json", "{\"error\":\"no such skill\"}");
			return;
		}
		
		final Skill skill = SkillData.getInstance().getSkill(id, (level != null) && (level >= 1) && (level <= maxLevel) ? level : 1);
		final StringBuilder json = new StringBuilder();
		json.append("{\"id\":").append(id).append(",\"maxLevel\":").append(maxLevel);
		json.append(",\"name\":").append(SimpleJson.quote(skill != null ? skill.getName() : ""));
		json.append(",\"passive\":").append((skill != null) && skill.isPassive()).append("}");
		sendText(exchange, 200, "application/json", json.toString());
	}
	
	private void handleAdminSave(HttpExchange exchange) throws IOException
	{
		final Player gm = authenticateAdmin(exchange);
		if (gm == null)
		{
			return;
		}
		
		final String body;
		try (InputStream in = exchange.getRequestBody())
		{
			final byte[] bytes = in.readNBytes(MAX_ADMIN_BODY_BYTES + 1);
			if (bytes.length > MAX_ADMIN_BODY_BYTES)
			{
				sendText(exchange, 413, "application/json", "{\"error\":\"the tree is too large to send\"}");
				return;
			}
			body = new String(bytes, StandardCharsets.UTF_8);
		}
		
		final String version;
		final boolean dryRun;
		final List<PassiveTreeEditor.EditNode> nodes;
		try
		{
			if (!(SimpleJson.parse(body) instanceof Map<?, ?> request))
			{
				throw new IllegalArgumentException("expected a JSON object");
			}
			version = String.valueOf(request.get("version"));
			dryRun = Boolean.TRUE.equals(request.get("dryRun"));
			nodes = PassiveTreeEditor.fromJson(request.get("nodes"));
		}
		catch (IllegalArgumentException e)
		{
			sendText(exchange, 400, "application/json", "{\"error\":" + SimpleJson.quote(e.getMessage()) + "}");
			return;
		}
		
		final PassiveTreeEditor.SaveResult result;
		try
		{
			result = PassiveTreeEditor.save(nodes, version, dryRun);
		}
		catch (IOException e)
		{
			fileError(exchange, "save", e);
			return;
		}
		if (result.saved())
		{
			LOGGER.info("PassiveTreeApiServer: " + gm.getName() + " saved the passive tree: " + String.join(", ", result.changedFiles()) + ".");
			gm.sendMessage("Passive tree saved and live: " + result.changedFiles().size() + " file(s) changed.");
		}
		
		final StringBuilder json = new StringBuilder();
		json.append("{\"saved\":").append(result.saved()).append(",\"dryRun\":").append(dryRun);
		json.append(",\"version\":").append(SimpleJson.quote(result.version()));
		json.append(",\"errors\":").append(jsonStrings(result.errors()));
		json.append(",\"warnings\":").append(jsonStrings(result.warnings()));
		json.append(",\"changedFiles\":").append(jsonStrings(result.changedFiles()));
		json.append("}");
		sendText(exchange, result.errors().isEmpty() ? 200 : 409, "application/json", json.toString());
	}
	
	/** Loads the tree files again: for files edited by hand while the server runs. */
	private void handleAdminReload(HttpExchange exchange) throws IOException
	{
		final Player gm = authenticateAdmin(exchange);
		if (gm == null)
		{
			return;
		}
		
		PassiveTreeEditor.reload();
		LOGGER.info("PassiveTreeApiServer: " + gm.getName() + " reloaded the passive tree from its files.");
		try
		{
			sendText(exchange, 200, "application/json", "{\"reloaded\":true,\"nodes\":" + PassiveTreeData.getInstance().getAllNodes().size() + ",\"version\":" + SimpleJson.quote(PassiveTreeEditor.version()) + "}");
		}
		catch (IOException e)
		{
			fileError(exchange, "read", e);
		}
	}
	
	/** A tree file could not be read or written: say so to the page instead of dropping the connection. */
	private void fileError(HttpExchange exchange, String action, IOException e) throws IOException
	{
		LOGGER.warning("PassiveTreeApiServer: could not " + action + " the passive tree files - " + e.getMessage());
		sendText(exchange, 500, "application/json", "{\"error\":" + SimpleJson.quote("Could not " + action + " the tree files: " + e.getMessage()) + "}");
	}
	
	private String jsonStrings(List<String> values)
	{
		final StringBuilder json = new StringBuilder("[");
		for (int i = 0; i < values.size(); i++)
		{
			json.append(i > 0 ? "," : "").append(SimpleJson.quote(values.get(i)));
		}
		return json.append("]").toString();
	}
	
	private Player resolvePlayer(int[] verifiedTokenParts)
	{
		final int charId = verifiedTokenParts[0];
		final int classIndex = verifiedTokenParts[1];
		
		final Player player = World.getInstance().getPlayer(charId);
		if ((player == null) || (player.getClassIndex() != classIndex))
		{
			return null;
		}
		return player;
	}
	
	private String buildCharacterJson(Player player)
	{
		final PassiveTreeManager mgr = PassiveTreeManager.getInstance();
		final Set<Integer> allocated = mgr.getAllocatedNodes(player);
		
		final StringBuilder json = new StringBuilder();
		json.append("{").append("\"name\":\"").append(escape(player.getName())).append("\",").append("\"level\":").append(player.getLevel()).append(",").append("\"earnedPoints\":").append(mgr.getEarnedPoints(player)).append(",").append("\"spentPoints\":").append(mgr.getSpentPoints(player)).append(",").append("\"availablePoints\":").append(mgr.getAvailablePoints(player)).append(",").append("\"allocatedNodeIds\":[").append(joinInts(new ArrayList<>(allocated))).append("],").append("\"templates\":").append(buildTemplatesJson(player)).append("}");
		return json.toString();
	}
	
	/** Template list plus what the page needs to enable or grey out its switch buttons: peace zone, remaining wait, and the rules themselves. */
	private String buildTemplatesJson(Player player)
	{
		final PassiveTreeManager mgr = PassiveTreeManager.getInstance();
		final StringBuilder json = new StringBuilder();
		json.append("{\"active\":").append(mgr.getActiveTemplate(player)).append(",").append("\"peaceOnly\":").append(PassiveTreeConfig.TEMPLATE_PEACE_ZONE_ONLY).append(",").append("\"inPeaceZone\":").append(mgr.canSwitchTemplateHere(player)).append(",").append("\"delaySeconds\":").append(PassiveTreeConfig.TEMPLATE_SWITCH_DELAY).append(",").append("\"cooldownMs\":").append(mgr.getTemplateCooldownRemaining(player)).append(",").append("\"list\":[");
		boolean first = true;
		for (PassiveTreeManager.TemplateInfo info : mgr.getTemplates(player))
		{
			if (!first)
			{
				json.append(",");
			}
			first = false;
			json.append("{\"id\":").append(info.id()).append(",").append("\"active\":").append(info.active()).append(",").append("\"nodes\":").append(info.nodes()).append(",").append("\"points\":").append(info.points()).append("}");
		}
		json.append("]}");
		return json.toString();
	}

	/** Headers every answer carries. No Access-Control-Allow-Origin: only this server's own page is meant to call the API, and the token must not leak through a Referer. */
	private void addSecurityHeaders(HttpExchange exchange)
	{
		exchange.getResponseHeaders().add("X-Content-Type-Options", "nosniff");
		exchange.getResponseHeaders().add("Referrer-Policy", "no-referrer");
		exchange.getResponseHeaders().add("Cache-Control", "no-store");
		exchange.getResponseHeaders().add("Content-Security-Policy", CSP);
		exchange.getResponseHeaders().add("X-Frame-Options", "DENY");
	}
	
	private void sendText(HttpExchange exchange, int status, String contentType, String body) throws IOException
	{
		exchange.getResponseHeaders().add("Content-Type", contentType);
		addSecurityHeaders(exchange);
		final byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
		exchange.sendResponseHeaders(status, bytes.length);
		try (OutputStream os = exchange.getResponseBody())
		{
			os.write(bytes);
		}
	}
	
	/** The query string plus, for a POST, the form-encoded body (name=value&name=value). Never the token: that only travels in the Authorization header. */
	private Map<String, String> readParams(HttpExchange exchange) throws IOException
	{
		final Map<String, String> params = new HashMap<>();
		addParams(params, exchange.getRequestURI().getRawQuery());
		if ("POST".equals(exchange.getRequestMethod()))
		{
			try (InputStream in = exchange.getRequestBody())
			{
				final byte[] body = in.readNBytes(MAX_BODY_BYTES + 1);
				if (body.length <= MAX_BODY_BYTES)
				{
					addParams(params, new String(body, StandardCharsets.UTF_8));
				}
			}
		}
		return params;
	}
	
	private void addParams(Map<String, String> params, String encoded)
	{
		if ((encoded == null) || encoded.isEmpty())
		{
			return;
		}
		for (String part : encoded.split("&"))
		{
			final String[] kv = part.split("=", 2);
			if (kv.length == 2)
			{
				try
				{
					params.put(URLDecoder.decode(kv[0], StandardCharsets.UTF_8), URLDecoder.decode(kv[1], StandardCharsets.UTF_8));
				}
				catch (IllegalArgumentException e)
				{
					// malformed escape: skip this pair
				}
			}
		}
	}
	
	private Integer parseIntParam(String value)
	{
		if (value == null)
		{
			return null;
		}
		try
		{
			return Integer.valueOf(value.trim());
		}
		catch (NumberFormatException e)
		{
			return null;
		}
	}
	
	private String joinInts(List<Integer> ids)
	{
		final StringBuilder sb = new StringBuilder();
		for (int i = 0; i < ids.size(); i++)
		{
			if (i > 0)
			{
				sb.append(",");
			}
			sb.append(ids.get(i));
		}
		return sb.toString();
	}
	
	private String escape(String s)
	{
		return s == null ? "" : s.replace("\\", "\\\\").replace("\"", "\\\"");
	}
	
	public static PassiveTreeApiServer getInstance()
	{
		return SingletonHolder.INSTANCE;
	}
	
	private static class SingletonHolder
	{
		protected static final PassiveTreeApiServer INSTANCE = new PassiveTreeApiServer();
	}
}