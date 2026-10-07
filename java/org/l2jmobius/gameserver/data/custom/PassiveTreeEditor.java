package org.l2jmobius.gameserver.data.custom;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;

import org.l2jmobius.commons.database.DatabaseFactory;
import org.l2jmobius.commons.util.SimpleJson;
import org.l2jmobius.gameserver.config.custom.PassiveTreeConfig;
import org.l2jmobius.gameserver.data.xml.SkillData;
import org.l2jmobius.gameserver.managers.FakePlayerPvpPassiveTree;
import org.l2jmobius.gameserver.managers.PassiveTreeManager;
import org.l2jmobius.gameserver.model.passivetree.PassiveMechanics;
import org.l2jmobius.gameserver.model.passivetree.PassiveNode;
import org.l2jmobius.gameserver.model.passivetree.PassiveNode.NodeType;

/**
 * Reads and writes the passive tree files (data/passivetree/*.xml) for the admin tree editor, then loads the tree again so the change is live.
 * <p>
 * The editor works on the files, not on {@link PassiveTreeData}: it needs what the loader fills in or throws away (which file a node is in, a description left out so it is made from the effect, the order of the links). A file saved without changes comes out byte for byte the same.
 * <p>
 * Links are saved the way the loader expects them: every node lists all its neighbours, except START nodes, which list none (an empty list is what makes a node a starting point).
 * <p>
 * Every save copies the files it replaces to data/passivetree_backup/&lt;time&gt;/ first.
 */
public final class PassiveTreeEditor
{
	private static final Logger LOGGER = Logger.getLogger(PassiveTreeEditor.class.getName());

	public static final Path TREE_DIR = Path.of("data/passivetree");
	public static final Path BACKUP_DIR = Path.of("data/passivetree_backup");
	private static final int MAX_BACKUPS = 30;

	private static final String LIST_LINE = "<list xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\" xsi:noNamespaceSchemaLocation=\"../xsd/passivetree.xsd\">";
	private static final Pattern FILE_NAME = Pattern.compile("[A-Za-z0-9_-]{1,64}\\.(?i:xml)");
	private static final Pattern EFFECT_KEY = Pattern.compile("[A-Z][A-Z0-9_]*(@[A-Z]+)?");
	private static final Pattern ICON = Pattern.compile("[a-z0-9-]{0,40}");
	private static final Pattern NODE_COUNT = Pattern.compile("^(.*?: )\\d+( nodes.*)$", Pattern.DOTALL);
	private static final DateTimeFormatter BACKUP_NAME = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS");

	private static final int MAX_NAME = 80;
	private static final int MAX_SECTOR = 40;
	private static final int MAX_DESCRIPTION = 600;
	private static final int MAX_COST = 100;
	private static final double MAX_COORD = 100000;
	private static final int LISTED = 8; // nodes named in one warning, the rest are counted

	private PassiveTreeEditor()
	{
	}

	/**
	 * One node as the files hold it.
	 */
	public static final class EditNode
	{
		public int id;
		public String file = "";
		public String name = "";
		public String sector = "";
		public String type = "SMALL";
		public int tier;
		public int cost;
		public double x;
		public double y;
		/** Centre of the circle the node sits on, {@code null} for none. */
		public Double orbitX;
		public Double orbitY;
		public String effect = "";
		public int skillId;
		/** A level, "auto", or empty when the node grants no skill. */
		public String skillLevel = "";
		public String icon = "";
		/** Empty: the loader makes one from the effect. */
		public String description = "";
		/** Neighbour ids in file order. */
		public List<Integer> requires = new ArrayList<>();
	}

	/**
	 * The tree as it is on disk.
	 * @param nodes every node, file by file in name order, each file's nodes in file order
	 * @param version a hash of the files, sent back with a save so it can't overwrite a change it has not seen
	 */
	public record Snapshot(List<EditNode> nodes, String version)
	{
	}

	/**
	 * What a save did, or would do.
	 * @param saved {@code true} if the files were written and the tree loaded again
	 * @param errors what stops the save
	 * @param warnings what the save allows but the admin should know
	 * @param version the version of the files now
	 * @param changedFiles files written or removed (or that would be)
	 */
	public record SaveResult(boolean saved, List<String> errors, List<String> warnings, String version, List<String> changedFiles)
	{
	}

	// ------------------------------------------------------------------ reading

	/**
	 * @return every node of every tree file
	 * @throws IOException if a file can't be read or isn't valid XML
	 */
	public static synchronized Snapshot read() throws IOException
	{
		final List<EditNode> nodes = new ArrayList<>();
		for (Path file : treeFiles())
		{
			final Element list = parse(file).getDocumentElement();
			for (Node n = list.getFirstChild(); n != null; n = n.getNextSibling())
			{
				if ((n instanceof Element element) && element.getNodeName().equalsIgnoreCase("node"))
				{
					nodes.add(readNode(element, file.getFileName().toString()));
				}
			}
		}
		return new Snapshot(nodes, version());
	}

	private static EditNode readNode(Element element, String file)
	{
		final EditNode node = new EditNode();
		node.file = file;
		node.id = Integer.parseInt(element.getAttribute("id").trim());
		node.name = element.getAttribute("name");
		node.sector = element.getAttribute("sector");
		node.type = element.hasAttribute("type") ? element.getAttribute("type").trim().toUpperCase() : "SMALL";
		node.tier = element.hasAttribute("tier") ? Integer.parseInt(element.getAttribute("tier").trim()) : 0;
		node.cost = element.hasAttribute("cost") ? Integer.parseInt(element.getAttribute("cost").trim()) : defaultCost(node.type);
		node.x = element.hasAttribute("x") ? Double.parseDouble(element.getAttribute("x")) : 0;
		node.y = element.hasAttribute("y") ? Double.parseDouble(element.getAttribute("y")) : 0;
		if (element.hasAttribute("orbitX") && element.hasAttribute("orbitY"))
		{
			node.orbitX = Double.parseDouble(element.getAttribute("orbitX"));
			node.orbitY = Double.parseDouble(element.getAttribute("orbitY"));
		}
		node.effect = element.getAttribute("effect");
		node.skillId = element.hasAttribute("skillId") ? Integer.parseInt(element.getAttribute("skillId").trim()) : 0;
		node.skillLevel = node.skillId > 0 ? (element.hasAttribute("skillLevel") ? element.getAttribute("skillLevel").trim() : "1") : "";
		node.icon = element.getAttribute("icon");
		node.description = element.getAttribute("description");
		for (Node child = element.getFirstChild(); child != null; child = child.getNextSibling())
		{
			if ((child instanceof Element requires) && requires.getNodeName().equalsIgnoreCase("requires"))
			{
				node.requires.add(Integer.parseInt(requires.getAttribute("nodeId").trim()));
			}
		}
		return node;
	}

	/** Same defaults as {@link PassiveTreeData}. */
	private static int defaultCost(String type)
	{
		switch (type)
		{
			case "NOTABLE":
			case "ACTIVE_SKILL":
			case "FUNCTION":
				return 2;
			case "KEYSTONE":
			case "MASTER":
				return 3;
			case "START":
				return 0;
			default:
				return 1;
		}
	}

	private static List<Path> treeFiles() throws IOException
	{
		try (Stream<Path> files = Files.list(TREE_DIR))
		{
			return files.filter(f -> Files.isRegularFile(f) && f.getFileName().toString().toLowerCase().endsWith(".xml")).sorted().toList();
		}
	}

	private static Document parse(Path file) throws IOException
	{
		try (InputStream in = Files.newInputStream(file))
		{
			final DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
			factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
			factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
			factory.setExpandEntityReferences(false);
			final DocumentBuilder builder = factory.newDocumentBuilder();
			return builder.parse(in);
		}
		catch (IOException e)
		{
			throw e;
		}
		catch (Exception e)
		{
			throw new IOException(file.getFileName() + ": " + e.getMessage(), e);
		}
	}

	/** @return the text of the comment above {@code <list>}, or {@code null} if there is none */
	private static String header(Path file) throws IOException
	{
		for (Node n = parse(file).getFirstChild(); n != null; n = n.getNextSibling())
		{
			if (n.getNodeType() == Node.COMMENT_NODE)
			{
				return n.getNodeValue();
			}
			if (n.getNodeType() == Node.ELEMENT_NODE)
			{
				break;
			}
		}
		return null;
	}

	/**
	 * @return a hash of every tree file's name and content
	 * @throws IOException if a file can't be read
	 */
	public static String version() throws IOException
	{
		try
		{
			final MessageDigest digest = MessageDigest.getInstance("SHA-256");
			for (Path file : treeFiles())
			{
				digest.update(file.getFileName().toString().getBytes(StandardCharsets.UTF_8));
				digest.update((byte) 0);
				digest.update(Files.readAllBytes(file));
				digest.update((byte) 0);
			}
			final StringBuilder hex = new StringBuilder();
			for (byte b : digest.digest())
			{
				hex.append(String.format("%02x", b));
			}
			return hex.substring(0, 24);
		}
		catch (NoSuchAlgorithmException e)
		{
			throw new IllegalStateException(e);
		}
	}

	/**
	 * @return node id -> how many characters (counting each class slot) have it allocated; empty if the database can't be read
	 */
	public static Map<Integer, Integer> allocationCounts()
	{
		final Map<Integer, Integer> counts = new TreeMap<>();
		try (Connection con = DatabaseFactory.getConnection();
			PreparedStatement ps = con.prepareStatement("SELECT node_id, COUNT(*) AS c FROM character_passive_tree GROUP BY node_id");
			ResultSet rs = ps.executeQuery())
		{
			while (rs.next())
			{
				counts.put(rs.getInt("node_id"), rs.getInt("c"));
			}
		}
		catch (Exception e)
		{
			LOGGER.warning(PassiveTreeEditor.class.getSimpleName() + ": Could not count allocated nodes - " + e.getMessage());
		}
		return counts;
	}
	
	// ------------------------------------------------------------------ JSON

	/**
	 * @param node a node
	 * @return it as a JSON object, as the editor page reads it
	 */
	public static String toJson(EditNode node)
	{
		final StringBuilder json = new StringBuilder(256);
		json.append("{\"id\":").append(node.id);
		json.append(",\"file\":").append(SimpleJson.quote(node.file));
		json.append(",\"name\":").append(SimpleJson.quote(node.name));
		json.append(",\"sector\":").append(SimpleJson.quote(node.sector));
		json.append(",\"type\":").append(SimpleJson.quote(node.type));
		json.append(",\"tier\":").append(node.tier);
		json.append(",\"cost\":").append(node.cost);
		json.append(",\"x\":").append(node.x);
		json.append(",\"y\":").append(node.y);
		if (node.orbitX != null)
		{
			json.append(",\"ox\":").append(node.orbitX).append(",\"oy\":").append(node.orbitY);
		}
		json.append(",\"effect\":").append(SimpleJson.quote(node.effect));
		json.append(",\"skillId\":").append(node.skillId);
		json.append(",\"skillLevel\":").append(SimpleJson.quote(node.skillLevel));
		json.append(",\"icon\":").append(SimpleJson.quote(node.icon));
		json.append(",\"description\":").append(SimpleJson.quote(node.description));
		json.append(",\"requires\":[");
		for (int i = 0; i < node.requires.size(); i++)
		{
			json.append(i > 0 ? "," : "").append(node.requires.get(i));
		}
		return json.append("]}").toString();
	}

	/**
	 * @param json the nodes the editor page sent, parsed by {@link SimpleJson}: a list of objects like {@link #toJson} makes
	 * @return the nodes
	 * @throws IllegalArgumentException if it isn't a list of nodes; the message says what is wrong
	 */
	public static List<EditNode> fromJson(Object json)
	{
		if (!(json instanceof List<?> list))
		{
			throw new IllegalArgumentException("expected a list of nodes");
		}

		final List<EditNode> nodes = new ArrayList<>(list.size());
		for (Object item : list)
		{
			if (!(item instanceof Map<?, ?> map))
			{
				throw new IllegalArgumentException("every node must be an object");
			}

			final EditNode node = new EditNode();
			node.id = integer(map, "id", 0);
			node.file = string(map, "file");
			node.name = string(map, "name");
			node.sector = string(map, "sector");
			node.type = string(map, "type").toUpperCase();
			node.tier = integer(map, "tier", 0);
			node.cost = integer(map, "cost", 1);
			node.x = number(map, "x");
			node.y = number(map, "y");
			if ((map.get("ox") != null) || (map.get("oy") != null))
			{
				node.orbitX = number(map, "ox");
				node.orbitY = number(map, "oy");
			}
			node.effect = string(map, "effect");
			node.skillId = integer(map, "skillId", 0);
			node.skillLevel = string(map, "skillLevel").toLowerCase();
			node.icon = string(map, "icon");
			node.description = string(map, "description");
			if (map.get("requires") instanceof List<?> requires)
			{
				for (Object id : requires)
				{
					if (!(id instanceof Double value) || (value != Math.rint(value)))
					{
						throw new IllegalArgumentException("node " + node.id + ": links must be node ids");
					}
					node.requires.add(value.intValue());
				}
			}
			nodes.add(node);
		}
		return nodes;
	}

	private static String string(Map<?, ?> map, String key)
	{
		final Object value = map.get(key);
		if (value == null)
		{
			return "";
		}
		if (value instanceof Double number)
		{
			return number == Math.rint(number) ? String.valueOf(number.longValue()) : String.valueOf(number);
		}
		return String.valueOf(value).trim();
	}

	private static int integer(Map<?, ?> map, String key, int defaultValue)
	{
		final Object value = map.get(key);
		if ((value == null) || "".equals(value))
		{
			return defaultValue;
		}
		try
		{
			final double number = value instanceof Double d ? d : Double.parseDouble(String.valueOf(value).trim());
			if ((number != Math.rint(number)) || (Math.abs(number) > Integer.MAX_VALUE))
			{
				throw new NumberFormatException();
			}
			return (int) number;
		}
		catch (NumberFormatException e)
		{
			throw new IllegalArgumentException("node " + map.get("id") + ": " + key + " must be a whole number");
		}
	}

	private static double number(Map<?, ?> map, String key)
	{
		final Object value = map.get(key);
		try
		{
			final double number = value instanceof Double d ? d : Double.parseDouble(String.valueOf(value).trim());
			if (!Double.isFinite(number))
			{
				throw new NumberFormatException();
			}
			return number;
		}
		catch (NumberFormatException e)
		{
			throw new IllegalArgumentException("node " + map.get("id") + ": " + key + " must be a number");
		}
	}

	// ------------------------------------------------------------------ saving

	/**
	 * Checks the nodes, and unless {@code dryRun}, writes them to the tree files and loads the tree again.
	 * @param nodes the whole tree, as the editor has it
	 * @param baseVersion the {@link #version()} the editor started from; the save is refused if the files have changed since
	 * @param dryRun only check, and say what a save would do
	 * @return what was done
	 * @throws IOException if the files can't be read or written
	 */
	public static synchronized SaveResult save(List<EditNode> nodes, String baseVersion, boolean dryRun) throws IOException
	{
		final List<String> errors = new ArrayList<>();
		final List<String> warnings = new ArrayList<>();

		final String current = version();
		if (!current.equals(baseVersion))
		{
			errors.add("The tree files changed since you opened the editor (another save, or a hand edit). Reload the editor to get them; your unsaved changes are kept in this browser as a draft.");
			return new SaveResult(false, errors, warnings, current, List.of());
		}

		check(nodes, errors, warnings);
		if (!errors.isEmpty())
		{
			return new SaveResult(false, errors, warnings, current, List.of());
		}

		normalizeLinks(nodes);
		reportRefunds(nodes, warnings);

		// The new content of every file, and the files to remove.
		final Map<String, List<EditNode>> byFile = new TreeMap<>();
		for (EditNode node : nodes)
		{
			byFile.computeIfAbsent(node.file, k -> new ArrayList<>()).add(node);
		}
		final Map<String, String> contents = new LinkedHashMap<>();
		for (Map.Entry<String, List<EditNode>> entry : byFile.entrySet())
		{
			final Path path = TREE_DIR.resolve(entry.getKey());
			final String oldHeader = Files.exists(path) ? header(path) : null;
			contents.put(entry.getKey(), toXml(oldHeader, entry.getValue()));
		}

		final List<String> changed = new ArrayList<>();
		final List<Path> removed = new ArrayList<>();
		for (Map.Entry<String, String> entry : contents.entrySet())
		{
			final Path path = TREE_DIR.resolve(entry.getKey());
			if (!Files.exists(path) || !Files.readString(path, StandardCharsets.UTF_8).equals(entry.getValue()))
			{
				changed.add(entry.getKey());
			}
		}
		for (Path file : treeFiles())
		{
			if (!contents.containsKey(file.getFileName().toString()))
			{
				removed.add(file);
				changed.add(file.getFileName() + " (removed: it has no nodes left)");
			}
		}

		if (changed.isEmpty())
		{
			warnings.add("Nothing changed.");
			return new SaveResult(false, errors, warnings, current, changed);
		}
		if (dryRun)
		{
			return new SaveResult(false, errors, warnings, current, changed);
		}

		final Path backup = backup();
		for (Map.Entry<String, String> entry : contents.entrySet())
		{
			final Path path = TREE_DIR.resolve(entry.getKey());
			final Path temp = TREE_DIR.resolve(entry.getKey() + ".tmp");
			Files.writeString(temp, entry.getValue(), StandardCharsets.UTF_8);
			Files.move(temp, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
		}
		for (Path file : removed)
		{
			Files.delete(file);
		}
		LOGGER.info(PassiveTreeEditor.class.getSimpleName() + ": Saved " + nodes.size() + " passive tree nodes (" + changed.size() + " file(s) changed, backup in " + backup + ").");

		reload();
		warnings.add("The old files are backed up in " + backup.toString().replace('\\', '/') + ".");
		return new SaveResult(true, errors, warnings, version(), changed);
	}

	/**
	 * Loads the tree files again and makes the new tree live: online players get their trees rebuilt, and the fake players' prepared trees are grown again.
	 */
	public static synchronized void reload()
	{
		PassiveTreeData.getInstance().load();
		PassiveTreeData.getInstance().validate();
		PassiveTreeManager.getInstance().onTreeReloaded();
		FakePlayerPvpPassiveTree.reload();
	}

	private static void check(List<EditNode> nodes, List<String> errors, List<String> warnings)
	{
		final Map<Integer, EditNode> byId = new HashMap<>();
		for (EditNode node : nodes)
		{
			final String label = "Node " + node.id;
			if (node.id <= 0)
			{
				errors.add(label + ": the id must be above 0.");
			}
			else if (byId.putIfAbsent(node.id, node) != null)
			{
				errors.add(label + ": the id is used twice.");
			}

			if (!FILE_NAME.matcher(node.file).matches())
			{
				errors.add(label + ": the file \"" + node.file + "\" must be a plain name ending in .xml (letters, digits, - and _).");
			}
			checkText(label, "name", node.name, MAX_NAME, true, errors);
			checkText(label, "sector", node.sector, MAX_SECTOR, true, errors);
			checkText(label, "description", node.description, MAX_DESCRIPTION, false, errors);

			NodeType type = null;
			try
			{
				type = NodeType.valueOf(node.type);
			}
			catch (IllegalArgumentException e)
			{
				errors.add(label + ": unknown type \"" + node.type + "\".");
			}
			if ((node.cost < 0) || (node.cost > MAX_COST))
			{
				errors.add(label + ": the cost must be 0-" + MAX_COST + ".");
			}
			if ((type != null) && (type != NodeType.START) && (node.cost == 0))
			{
				warnings.add(label + " (" + node.name + ") costs 0 points.");
			}
			if (node.tier < 0)
			{
				errors.add(label + ": the tier can't be negative.");
			}
			for (double value : new double[]
			{
				node.x,
				node.y,
				node.orbitX != null ? node.orbitX : 0,
				node.orbitY != null ? node.orbitY : 0
			})
			{
				if (!Double.isFinite(value) || (Math.abs(value) > MAX_COORD))
				{
					errors.add(label + ": a position is out of range.");
					break;
				}
			}
			if ((node.orbitX == null) != (node.orbitY == null))
			{
				errors.add(label + ": the orbit needs both X and Y.");
			}
			if (!ICON.matcher(node.icon).matches())
			{
				errors.add(label + ": the icon must be a glyph name like \"hammer\".");
			}

			checkEffect(node, errors, warnings);
			checkSkill(node, errors);
		}

		// Links: every id must exist, and a link between two starting points can't be saved (a START node lists no links, so neither end would keep it).
		boolean hasStart = false;
		for (EditNode node : nodes)
		{
			if ("START".equals(node.type))
			{
				hasStart = true;
			}
			for (int id : node.requires)
			{
				final EditNode other = byId.get(id);
				if ((other == null) && (id != node.id))
				{
					errors.add("Node " + node.id + " is linked to node " + id + ", which doesn't exist.");
				}
				else if ((other != null) && (other != node) && "START".equals(node.type) && "START".equals(other.type))
				{
					errors.add("Nodes " + node.id + " and " + id + " are both starting points: two starting points can't be linked.");
				}
			}
		}
		if (!hasStart)
		{
			errors.add("The tree needs at least one START node.");
		}
		if (!errors.isEmpty())
		{
			return;
		}

		// Nodes no starting point leads to can never be taken.
		final Map<Integer, Set<Integer>> links = links(nodes);
		final Set<Integer> reached = new HashSet<>();
		final Deque<Integer> queue = new ArrayDeque<>();
		for (EditNode node : nodes)
		{
			if ("START".equals(node.type))
			{
				reached.add(node.id);
				queue.add(node.id);
			}
		}
		while (!queue.isEmpty())
		{
			for (int next : links.getOrDefault(queue.poll(), Collections.emptySet()))
			{
				if (reached.add(next))
				{
					queue.add(next);
				}
			}
		}
		final List<String> unreachable = new ArrayList<>();
		for (EditNode node : nodes)
		{
			if (!reached.contains(node.id))
			{
				unreachable.add(node.id + " (" + node.name + ")");
			}
		}
		if (!unreachable.isEmpty())
		{
			warnings.add(unreachable.size() + " node(s) can't be reached from any starting point, so nobody can take them: " + list(unreachable) + ".");
		}
	}

	private static void checkText(String label, String field, String value, int max, boolean required, List<String> errors)
	{
		if (required && value.isBlank())
		{
			errors.add(label + ": the " + field + " can't be empty.");
		}
		if (value.length() > max)
		{
			errors.add(label + ": the " + field + " is longer than " + max + " characters.");
		}
		if (value.chars().anyMatch(c -> c < 0x20))
		{
			errors.add(label + ": the " + field + " has a line break or control character.");
		}
	}

	private static void checkEffect(EditNode node, List<String> errors, List<String> warnings)
	{
		if (node.effect.isEmpty())
		{
			return;
		}

		final Set<String> known = knownEffectKeys();
		for (String part : node.effect.split(";", -1))
		{
			final String[] kv = part.split(":", -1);
			if ((kv.length != 2) || !EFFECT_KEY.matcher(kv[0].trim()).matches())
			{
				errors.add("Node " + node.id + ": \"" + part + "\" is not an effect like PATK_PCT:1.5.");
				continue;
			}
			try
			{
				if (!Double.isFinite(Double.parseDouble(kv[1].trim())))
				{
					throw new NumberFormatException();
				}
			}
			catch (NumberFormatException e)
			{
				errors.add("Node " + node.id + ": the value of " + kv[0].trim() + " must be a number.");
				continue;
			}

			final String key = kv[0].trim();
			if ((key.indexOf('@') > 0) && (PassiveMechanics.parseConditional(key) == null))
			{
				errors.add("Node " + node.id + ": " + key + " is not a known conditional effect (conditions: " + String.join(", ", PassiveMechanics.CONDITION_TOKENS) + "; HP, MP, CP and keystone keys can't be conditional).");
				continue;
			}
			final String base = key.indexOf('@') > 0 ? key.substring(0, key.indexOf('@')) : key;
			if (!known.contains(base))
			{
				warnings.add("Node " + node.id + " (" + node.name + "): no other node uses the effect " + base + ". Make sure the server knows it, or it does nothing.");
			}
		}
	}

	/** Effect keys the live tree uses, plus the ones the ini can cap: what the server is known to read. */
	private static Set<String> knownEffectKeys()
	{
		final Set<String> keys = new HashSet<>(PassiveTreeConfig.STAT_CAPS.keySet());
		for (PassiveNode node : PassiveTreeData.getInstance().getAllNodes().values())
		{
			for (String key : node.getEffects().keySet())
			{
				keys.add(key.indexOf('@') > 0 ? key.substring(0, key.indexOf('@')) : key);
			}
		}
		return keys;
	}

	private static void checkSkill(EditNode node, List<String> errors)
	{
		if (node.skillId < 0)
		{
			errors.add("Node " + node.id + ": the skill id can't be negative.");
			return;
		}
		if (node.skillId == 0)
		{
			node.skillLevel = "";
			return;
		}

		if (node.skillLevel.isEmpty())
		{
			node.skillLevel = "1";
		}
		int level = 1;
		if (!node.skillLevel.equals("auto"))
		{
			try
			{
				level = Integer.parseInt(node.skillLevel);
			}
			catch (NumberFormatException e)
			{
				level = 0;
			}
			if (level < 1)
			{
				errors.add("Node " + node.id + ": the skill level must be 1 or more, or \"auto\".");
				return;
			}
		}
		final int maxLevel = SkillData.getInstance().getMaxLevel(node.skillId);
		if (maxLevel <= 0)
		{
			errors.add("Node " + node.id + ": skill " + node.skillId + " doesn't exist.");
		}
		else if (level > maxLevel)
		{
			errors.add("Node " + node.id + ": skill " + node.skillId + " only has levels 1-" + maxLevel + ".");
		}
	}

	/** @return node id -> ids it is linked to, both directions */
	private static Map<Integer, Set<Integer>> links(List<EditNode> nodes)
	{
		final Set<Integer> ids = new HashSet<>();
		for (EditNode node : nodes)
		{
			ids.add(node.id);
		}
		final Map<Integer, Set<Integer>> links = new HashMap<>();
		for (EditNode node : nodes)
		{
			for (int other : node.requires)
			{
				if ((other != node.id) && ids.contains(other))
				{
					links.computeIfAbsent(node.id, k -> new TreeSet<>()).add(other);
					links.computeIfAbsent(other, k -> new TreeSet<>()).add(node.id);
				}
			}
		}
		return links;
	}

	/**
	 * Makes every link two-way, the way the loader reads them: a node lists all its neighbours, in the order it had them with any missing ones after, and a START node lists none.
	 */
	private static void normalizeLinks(List<EditNode> nodes)
	{
		final Map<Integer, Set<Integer>> links = links(nodes);
		for (EditNode node : nodes)
		{
			final Set<Integer> neighbours = links.getOrDefault(node.id, Collections.emptySet());
			if ("START".equals(node.type))
			{
				node.requires = new ArrayList<>();
				continue;
			}
			final Set<Integer> ordered = new LinkedHashSet<>();
			for (int id : node.requires)
			{
				if (neighbours.contains(id))
				{
					ordered.add(id);
				}
			}
			ordered.addAll(neighbours);
			node.requires = new ArrayList<>(ordered);
		}
	}

	/**
	 * Says how many allocated nodes the new tree takes from characters: nodes that are removed, or no longer connect to the character's starting point. They are refunded when the character is next loaded, as at login after any tree change.
	 */
	private static void reportRefunds(List<EditNode> nodes, List<String> warnings)
	{
		// The tree as it is now, and as it will be.
		final Map<Integer, Set<Integer>> oldLinks = new HashMap<>();
		final Set<Integer> oldStarts = new HashSet<>();
		for (PassiveNode node : PassiveTreeData.getInstance().getAllNodes().values())
		{
			oldLinks.computeIfAbsent(node.getId(), k -> new HashSet<>());
			for (int parent : node.getParents())
			{
				oldLinks.computeIfAbsent(node.getId(), k -> new HashSet<>()).add(parent);
				oldLinks.computeIfAbsent(parent, k -> new HashSet<>()).add(node.getId());
			}
			if (node.getType() == NodeType.START)
			{
				oldStarts.add(node.getId());
			}
		}
		final Map<Integer, Set<Integer>> newLinks = links(nodes);
		final Set<Integer> newStarts = new HashSet<>();
		final Map<Integer, String> names = new HashMap<>();
		for (EditNode node : nodes)
		{
			newLinks.computeIfAbsent(node.id, k -> new HashSet<>());
			if ("START".equals(node.type))
			{
				newStarts.add(node.id);
			}
			names.put(node.id, node.name);
		}

		final Map<String, Set<Integer>> allocations = new HashMap<>();
		try (Connection con = DatabaseFactory.getConnection();
			PreparedStatement ps = con.prepareStatement("SELECT char_id, class_index, node_id FROM character_passive_tree");
			ResultSet rs = ps.executeQuery())
		{
			while (rs.next())
			{
				allocations.computeIfAbsent(rs.getInt("char_id") + "#" + rs.getInt("class_index"), k -> new HashSet<>()).add(rs.getInt("node_id"));
			}
		}
		catch (Exception e)
		{
			warnings.add("Could not check the characters' allocated nodes: " + e.getMessage());
			return;
		}

		final Set<Integer> characters = new HashSet<>();
		final Map<Integer, Integer> lostNodes = new TreeMap<>();
		int lost = 0;
		for (Map.Entry<String, Set<Integer>> entry : allocations.entrySet())
		{
			final Set<Integer> before = connected(entry.getValue(), oldLinks, oldStarts);
			before.removeAll(connected(entry.getValue(), newLinks, newStarts));
			if (!before.isEmpty())
			{
				characters.add(Integer.parseInt(entry.getKey().substring(0, entry.getKey().indexOf('#'))));
				lost += before.size();
				for (int id : before)
				{
					lostNodes.merge(id, 1, Integer::sum);
				}
			}
		}
		if (lost > 0)
		{
			final List<String> listed = new ArrayList<>();
			for (Map.Entry<Integer, Integer> entry : lostNodes.entrySet())
			{
				listed.add(entry.getKey() + (names.containsKey(entry.getKey()) ? " (" + names.get(entry.getKey()) + ")" : " (removed)") + " x" + entry.getValue());
			}
			warnings.add(characters.size() + " character(s) lose " + lost + " allocated node(s) that are removed or no longer connect to their starting point. The points are refunded: online players at once, the rest at their next login. Nodes: " + list(listed) + ".");
		}
	}

	/** @return the nodes of {@code allocated} a START node of it leads to, through other nodes of it */
	private static Set<Integer> connected(Set<Integer> allocated, Map<Integer, Set<Integer>> links, Set<Integer> starts)
	{
		final Set<Integer> seen = new HashSet<>();
		final Deque<Integer> queue = new ArrayDeque<>();
		for (int id : allocated)
		{
			if (starts.contains(id))
			{
				seen.add(id);
				queue.add(id);
			}
		}
		while (!queue.isEmpty())
		{
			for (int next : links.getOrDefault(queue.poll(), Collections.emptySet()))
			{
				if (allocated.contains(next) && seen.add(next))
				{
					queue.add(next);
				}
			}
		}
		return seen;
	}

	private static String list(List<String> items)
	{
		return items.size() <= LISTED ? String.join(", ", items) : String.join(", ", items.subList(0, LISTED)) + " and " + (items.size() - LISTED) + " more";
	}

	/** Copies every tree file to a new folder under {@link #BACKUP_DIR}, and drops the oldest backups past {@link #MAX_BACKUPS}. */
	private static Path backup() throws IOException
	{
		final Path folder = BACKUP_DIR.resolve(LocalDateTime.now().format(BACKUP_NAME));
		Files.createDirectories(folder);
		for (Path file : treeFiles())
		{
			Files.copy(file, folder.resolve(file.getFileName()), StandardCopyOption.REPLACE_EXISTING);
		}

		try (Stream<Path> stream = Files.list(BACKUP_DIR))
		{
			final List<Path> backups = stream.filter(Files::isDirectory).sorted().toList();
			for (int i = 0; i < (backups.size() - MAX_BACKUPS); i++)
			{
				try (Stream<Path> files = Files.list(backups.get(i)))
				{
					for (Path file : files.toList())
					{
						Files.delete(file);
					}
				}
				Files.delete(backups.get(i));
			}
		}
		catch (IOException e)
		{
			LOGGER.warning(PassiveTreeEditor.class.getSimpleName() + ": Could not remove an old backup - " + e.getMessage());
		}
		return folder;
	}

	// ------------------------------------------------------------------ XML

	/**
	 * @param oldHeader the comment the file had above {@code <list>}, {@code null} for a new file
	 * @param nodes the file's nodes, in order
	 * @return the file's content, in the layout the generator writes
	 */
	static String toXml(String oldHeader, List<EditNode> nodes)
	{
		final StringBuilder xml = new StringBuilder(nodes.size() * 400);
		xml.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");

		String header = oldHeader;
		if (header == null)
		{
			header = " " + (nodes.isEmpty() ? "Passive tree" : nodes.get(0).sector) + ": " + nodes.size() + " nodes. x/y are authoritative layout coords. ";
		}
		else
		{
			final Matcher matcher = NODE_COUNT.matcher(header);
			if (matcher.matches())
			{
				header = matcher.group(1) + nodes.size() + matcher.group(2);
			}
		}
		xml.append("<!--").append(header.replace("--", "- -")).append("-->\n");
		xml.append(LIST_LINE).append('\n');

		for (EditNode node : nodes)
		{
			xml.append("\t<node");
			attribute(xml, "id", String.valueOf(node.id));
			attribute(xml, "name", node.name);
			attribute(xml, "sector", node.sector);
			attribute(xml, "type", node.type);
			attribute(xml, "tier", String.valueOf(node.tier));
			attribute(xml, "cost", String.valueOf(node.cost));
			attribute(xml, "x", coordinate(node.x));
			attribute(xml, "y", coordinate(node.y));
			if ((node.orbitX != null) && (node.orbitY != null))
			{
				attribute(xml, "orbitX", coordinate(node.orbitX));
				attribute(xml, "orbitY", coordinate(node.orbitY));
			}
			if (!node.effect.isEmpty())
			{
				attribute(xml, "effect", node.effect);
			}
			if (node.skillId > 0)
			{
				attribute(xml, "skillId", String.valueOf(node.skillId));
				attribute(xml, "skillLevel", node.skillLevel.isEmpty() ? "1" : node.skillLevel);
			}
			if (!node.icon.isEmpty())
			{
				attribute(xml, "icon", node.icon);
			}
			if (!node.description.isEmpty())
			{
				attribute(xml, "description", node.description);
			}

			if (node.requires.isEmpty())
			{
				xml.append(" />\n");
				continue;
			}
			xml.append(">\n");
			for (int id : node.requires)
			{
				xml.append("\t\t<requires nodeId=\"").append(id).append("\" />\n");
			}
			xml.append("\t</node>\n");
		}
		return xml.append("</list>").toString();
	}

	/** One decimal, never in exponent form (the XSD wants a plain decimal). */
	private static String coordinate(double value)
	{
		return BigDecimal.valueOf(value).setScale(1, RoundingMode.HALF_UP).toPlainString();
	}

	private static void attribute(StringBuilder xml, String name, String value)
	{
		xml.append(' ').append(name).append("=\"");
		for (int i = 0; i < value.length(); i++)
		{
			final char c = value.charAt(i);
			switch (c)
			{
				case '&':
					xml.append("&amp;");
					break;
				case '<':
					xml.append("&lt;");
					break;
				case '>':
					xml.append("&gt;");
					break;
				case '"':
					xml.append("&quot;");
					break;
				default:
					if (c >= 0x20)
					{
						xml.append(c);
					}
			}
		}
		xml.append('"');
	}
}
