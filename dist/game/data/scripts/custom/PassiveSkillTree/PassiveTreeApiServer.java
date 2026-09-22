package custom.PassiveSkillTree;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.logging.Logger;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import org.l2jmobius.gameserver.config.custom.PassiveTreeConfig;
import org.l2jmobius.gameserver.data.custom.PassiveTreeData;
import org.l2jmobius.gameserver.managers.PassiveTreeManager;
import org.l2jmobius.gameserver.model.World;
import org.l2jmobius.gameserver.model.actor.Player;
import org.l2jmobius.gameserver.model.passivetree.PassiveNode;

/**
 * Minimal, dependency-free (JDK-only) HTTP API backing the web visual tree planner, plus static hosting for the planner page itself.
 * <p>
 * ".treelink" hands out a direct clickable link with a signed token baked into the URL - the client can open web pages in-game, so there's no need for the player to type anything. A short-PIN fallback (/resolve) is still here underneath for anyone whose client can't do that: the same token this
 * class mints for a direct link is what a PIN eventually resolves to as well, so both paths lead to the exact same signed credential.
 * <p>
 * Endpoints:
 * <ul>
 * <li>GET /passive-tree.html - the visual tree page itself (static file on disk)</li>
 * <li>GET /api/passivetree/nodes - full node list, public, no auth needed</li>
 * <li>GET /api/passivetree/resolve?pin=... - PIN fallback: exchanges a short PIN for a real token</li>
 * <li>GET /api/passivetree/character?token=... - one player's LIVE allocation state</li>
 * <li>GET /api/passivetree/allocate?token=...&amp;nodeId=... - allocates one node</li>
 * </ul>
 */
public class PassiveTreeApiServer
{
	private static final Logger LOGGER = Logger.getLogger(PassiveTreeApiServer.class.getName());
	
	// CHANGE THIS to a long random secret unique to your server before going
	// live - anyone who has it can mint valid tokens for any character id.
	private static final String SECRET = "CHANGE_ME_TO_A_LONG_RANDOM_SECRET_STRING";
	
	private static final int PORT = 8788;
	private static final long TOKEN_VALID_MS = 15 * 60 * 1000L; // 15 minutes, per resolved session
	private static final long PIN_VALID_MS = 10 * 60 * 1000L; // 10 minutes to actually type the PIN in
	
	private static final Path HTML_FILE = Path.of("data/html/custom/passive-tree.html");
	
	private final Map<String, long[]> pins = new ConcurrentHashMap<>(); // pin -> {charId, classIndex, expiry}
	private final SecureRandom random = new SecureRandom();
	
	private HttpServer server;
	
	public void start()
	{
		try
		{
			server = HttpServer.create(new InetSocketAddress(PORT), 0);
			server.createContext("/passive-tree.html", this::handleStaticPage);
			server.createContext("/api/passivetree/nodes", this::handleNodes);
			server.createContext("/api/passivetree/resolve", this::handleResolve);
			server.createContext("/api/passivetree/character", this::handleCharacter);
			server.createContext("/api/passivetree/allocate", this::handleAllocate);
			server.createContext("/api/passivetree/deallocate", this::handleDeallocate);
			server.createContext("/api/passivetree/config", this::handleConfig);
			server.setExecutor(Executors.newFixedThreadPool(2));
			server.start();
			LOGGER.info("PassiveTreeApiServer: listening on port " + PORT);
		}
		catch (IOException e)
		{
			LOGGER.warning("PassiveTreeApiServer: failed to start - " + e.getMessage());
		}
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
	/** @return a fresh 6-digit PIN, valid for PIN_VALID_MS, tied to this character+class. */
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
			System.currentTimeMillis() + PIN_VALID_MS
		});
		return pin;
	}
	
	private void cleanupExpiredPins()
	{
		final long now = System.currentTimeMillis();
		pins.entrySet().removeIf(e -> e.getValue()[2] < now);
	}
	
	// ------------------------------------------------------------------
	// Token sign/verify (the real credential, once resolved from a PIN)
	// ------------------------------------------------------------------
	// Public now: .treelink calls this directly to hand out a clickable
	// link, rather than only being reachable indirectly through a PIN.
	public String generateToken(int charId, int classIndex)
	{
		final long expiry = System.currentTimeMillis() + TOKEN_VALID_MS;
		final String payload = charId + "." + classIndex + "." + expiry;
		final String signature = sign(payload);
		final String encodedPayload = Base64.getUrlEncoder().withoutPadding().encodeToString(payload.getBytes(StandardCharsets.UTF_8));
		return encodedPayload + "." + signature;
	}
	
	private String sign(String payload)
	{
		try
		{
			final Mac mac = Mac.getInstance("HmacSHA256");
			mac.init(new SecretKeySpec(SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
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
	
	/** @return {@code [charId, classIndex]} if valid and unexpired, otherwise {@code null}. */
	private int[] verifyToken(String token)
	{
		try
		{
			final int lastDot = token.lastIndexOf('.');
			final String encodedPayload = token.substring(0, lastDot);
			final String signature = token.substring(lastDot + 1);
			final String payload = new String(Base64.getUrlDecoder().decode(encodedPayload), StandardCharsets.UTF_8);
			
			if (!sign(payload).equals(signature))
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
		if (!Files.exists(HTML_FILE))
		{
			sendText(exchange, 404, "text/plain", "passive-tree.html not found at " + HTML_FILE.toAbsolutePath());
			return;
		}
		
		final byte[] bytes = Files.readAllBytes(HTML_FILE);
		exchange.getResponseHeaders().add("Content-Type", "text/html; charset=UTF-8");
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
			json.append("{").append("\"id\":").append(node.getId()).append(",").append("\"name\":\"").append(escape(node.getName())).append("\",").append("\"sector\":\"").append(escape(node.getSector())).append("\",").append("\"type\":\"").append(node.getType()).append("\",").append("\"cost\":").append(node.getCost()).append(",").append("\"x\":").append(node.getX()).append(",").append("\"y\":").append(node.getY()).append(",").append("\"effect\":\"").append(escape(node.getEffectSpec())).append("\",").append("\"description\":\"").append(escape(node.getDescription())).append("\",").append("\"parents\":[").append(joinInts(node.getParents())).append("]").append("}");
		}
		json.append("]");
		sendText(exchange, 200, "application/json", json.toString());
	}
	
	private void handleResolve(HttpExchange exchange) throws IOException
	{
		final String query = exchange.getRequestURI().getQuery();
		final String pin = parseQueryParam(query, "pin");
		
		if (pin == null)
		{
			sendText(exchange, 400, "application/json", "{\"error\":\"missing pin\"}");
			return;
		}
		
		cleanupExpiredPins();
		final long[] entry = pins.get(pin);
		if (entry == null)
		{
			sendText(exchange, 401, "application/json", "{\"error\":\"invalid or expired code\"}");
			return;
		}
		
		final String token = generateToken((int) entry[0], (int) entry[1]);
		sendText(exchange, 200, "application/json", "{\"token\":\"" + token + "\"}");
	}
	
	private void handleCharacter(HttpExchange exchange) throws IOException
	{
		final String query = exchange.getRequestURI().getQuery();
		final String token = parseQueryParam(query, "token");
		
		if (token == null)
		{
			sendText(exchange, 400, "application/json", "{\"error\":\"missing token\"}");
			return;
		}
		
		final int[] verified = verifyToken(token);
		if (verified == null)
		{
			sendText(exchange, 401, "application/json", "{\"error\":\"invalid or expired token\"}");
			return;
		}
		
		final Player player = resolvePlayer(verified);
		if (player == null)
		{
			sendText(exchange, 404, "application/json", "{\"error\":\"character not online on this class\"}");
			return;
		}
		
		sendText(exchange, 200, "application/json", buildCharacterJson(player));
	}
	
	private void handleAllocate(HttpExchange exchange) throws IOException
	{
		final String query = exchange.getRequestURI().getQuery();
		final String token = parseQueryParam(query, "token");
		final String nodeIdStr = parseQueryParam(query, "nodeId");
		
		if ((token == null) || (nodeIdStr == null))
		{
			sendText(exchange, 400, "application/json", "{\"error\":\"missing token or nodeId\"}");
			return;
		}
		
		final int[] verified = verifyToken(token);
		if (verified == null)
		{
			sendText(exchange, 401, "application/json", "{\"error\":\"invalid or expired token\"}");
			return;
		}
		
		final Player player = resolvePlayer(verified);
		if (player == null)
		{
			sendText(exchange, 404, "application/json", "{\"error\":\"character not online on this class\"}");
			return;
		}
		
		int nodeId;
		try
		{
			nodeId = Integer.parseInt(nodeIdStr);
		}
		catch (NumberFormatException e)
		{
			sendText(exchange, 400, "application/json", "{\"error\":\"bad nodeId\"}");
			return;
		}
		
		final boolean success = PassiveTreeManager.getInstance().allocate(player, nodeId);
		
		final StringBuilder json = new StringBuilder();
		json.append("{\"success\":").append(success).append(",").append("\"character\":").append(buildCharacterJson(player)).append("}");
		
		sendText(exchange, success ? 200 : 409, "application/json", json.toString());
	}
	
	// ------------------------------------------------------------------
	// Helpers
	// ------------------------------------------------------------------
	private void handleDeallocate(HttpExchange exchange) throws IOException
	{
		final String query = exchange.getRequestURI().getQuery();
		final String token = parseQueryParam(query, "token");
		final String nodeIdStr = parseQueryParam(query, "nodeId");
		
		if ((token == null) || (nodeIdStr == null))
		{
			sendText(exchange, 400, "application/json", "{\"error\":\"missing token or nodeId\"}");
			return;
		}
		
		final int[] verified = verifyToken(token);
		if (verified == null)
		{
			sendText(exchange, 401, "application/json", "{\"error\":\"invalid or expired token\"}");
			return;
		}
		
		final Player player = resolvePlayer(verified);
		if (player == null)
		{
			sendText(exchange, 404, "application/json", "{\"error\":\"character not online on this class\"}");
			return;
		}
		
		int nodeId;
		try
		{
			nodeId = Integer.parseInt(nodeIdStr);
		}
		catch (NumberFormatException e)
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
	
	/** Exposes the respec cost so the web page never has to hardcode it. */
	private void handleConfig(HttpExchange exchange) throws IOException
	{
		final StringBuilder json = new StringBuilder();
		json.append("{\"respecAdenaPerPoint\":").append(PassiveTreeConfig.RESPEC_ADENA_PER_POINT).append("}");
		sendText(exchange, 200, "application/json", json.toString());
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
		json.append("{").append("\"name\":\"").append(escape(player.getName())).append("\",").append("\"level\":").append(player.getLevel()).append(",").append("\"earnedPoints\":").append(mgr.getEarnedPoints(player)).append(",").append("\"spentPoints\":").append(mgr.getSpentPoints(player)).append(",").append("\"availablePoints\":").append(mgr.getAvailablePoints(player)).append(",").append("\"allocatedNodeIds\":[").append(joinInts(new ArrayList<>(allocated))).append("]").append("}");
		return json.toString();
	}
	
	private void sendText(HttpExchange exchange, int status, String contentType, String body) throws IOException
	{
		exchange.getResponseHeaders().add("Content-Type", contentType);
		exchange.getResponseHeaders().add("Access-Control-Allow-Origin", "*");
		final byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
		exchange.sendResponseHeaders(status, bytes.length);
		try (OutputStream os = exchange.getResponseBody())
		{
			os.write(bytes);
		}
	}
	
	private String parseQueryParam(String query, String key)
	{
		if (query == null)
		{
			return null;
		}
		for (String part : query.split("&"))
		{
			final String[] kv = part.split("=", 2);
			if ((kv.length == 2) && kv[0].equals(key))
			{
				return kv[1];
			}
		}
		return null;
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