package org.l2jmobius.gameserver.data.custom;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.l2jmobius.gameserver.config.custom.PassiveTreeConfig;
import org.l2jmobius.gameserver.managers.PassiveTreeManager;
import org.l2jmobius.gameserver.model.passivetree.PassiveMechanics;
import org.l2jmobius.gameserver.model.passivetree.PassiveNode;
import org.l2jmobius.gameserver.model.passivetree.PassiveStatBonusCache;

/**
 * The passive tree as the game client caches it: one binary definition of every node, the costs, the stat caps and the planner page's glyphs (layout: client/PassiveTree/PROTOCOL.txt). The client keeps it in System\PassiveTree.cache and fetches it again, in {@link #CHUNK_SIZE} chunks, only when the version or the SHA-256 the
 * server sends at world entry differ from its copy.
 * <p>
 * Built on first use after every tree load, so the item names of the reset cost are known by then.
 */
public final class PassiveTreeBlob
{
	private static final Logger LOGGER = Logger.getLogger(PassiveTreeBlob.class.getName());

	public static final int FORMAT = 1;
	/** Bytes per Ex2PassiveTreeChunk. The client's receive buffer takes a frame of about 32 KB, so a chunk stays well below that. */
	public static final int CHUNK_SIZE = 16384;
	/** The planner page holds the glyphs; the client draws the same ones. */
	private static final Path GLYPH_PAGE = Path.of("data/html/custom/passive-tree.html");
	private static final Pattern SYMBOL = Pattern.compile("<symbol id=\"icon-([a-z0-9-]+)\" viewBox=\"0 0 24 24\">(.*?)</symbol>", Pattern.DOTALL);

	/** The client's limits (shared/passive_tree_blob.h): more is refused there, so it is refused here too. */
	public static final int MAX_NODES = 2048;
	public static final int MAX_PARENTS = 32;
	public static final int MAX_GLYPHS = 256;
	public static final int MAX_STRING = 4096;

	/** Node types on the wire, in this order. */
	private static final List<PassiveNode.NodeType> TYPES = List.of(PassiveNode.NodeType.START, PassiveNode.NodeType.SMALL, PassiveNode.NodeType.NOTABLE, PassiveNode.NodeType.KEYSTONE, PassiveNode.NodeType.MASTER, PassiveNode.NodeType.FUNCTION, PassiveNode.NodeType.ACTIVE_SKILL);

	/**
	 * One built definition.
	 * @param version the tree's version (PassiveTree.xml)
	 * @param data the definition
	 * @param hash SHA-256 of {@code data}
	 */
	public record Blob(int version, byte[] data, byte[] hash)
	{
		public int chunkCount()
		{
			return (data.length + CHUNK_SIZE - 1) / CHUNK_SIZE;
		}

		/**
		 * @param index a chunk, from 0
		 * @return its bytes, or {@code null} past the last chunk
		 */
		public byte[] chunk(int index)
		{
			if ((index < 0) || (index >= chunkCount()))
			{
				return null;
			}
			final int from = index * CHUNK_SIZE;
			return Arrays.copyOfRange(data, from, Math.min(data.length, from + CHUNK_SIZE));
		}
	}

	private static volatile Blob _blob;
	private static volatile Map<Integer, PassiveNode> _nodes = Map.of();
	private static volatile int _version;

	private PassiveTreeBlob()
	{
	}

	/**
	 * Called by {@link PassiveTreeData#load()}: the next {@link #get()} builds the definition of this tree.
	 * @param nodes the tree
	 * @param version its version
	 */
	static synchronized void rebuild(Map<Integer, PassiveNode> nodes, int version)
	{
		_nodes = nodes;
		_version = version;
		_blob = null;
	}

	/**
	 * @return the definition of the live tree
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

		final byte[] data = write(new TreeMap<>(_nodes), _version, PassiveTreeManager.getInstance().getResetCostText(), PassiveStatBonusCache.getCaps(), glyphs(), PassiveMechanics.getExclusiveGroups());
		final byte[] hash;
		try
		{
			hash = MessageDigest.getInstance("SHA-256").digest(data);
		}
		catch (NoSuchAlgorithmException e)
		{
			throw new IllegalStateException(e);
		}
		_blob = new Blob(_version, data, hash);
		LOGGER.info(PassiveTreeBlob.class.getSimpleName() + ": Client definition of version " + _version + ": " + data.length + " bytes in " + _blob.chunkCount() + " chunks, SHA-256 " + hex(hash).substring(0, 16) + "...");
		return _blob;
	}

	/**
	 * @param nodes the tree, in id order
	 * @param version its version
	 * @param resetCostText the full reset's price as players read it
	 * @param statCaps stat key -> the most the tree adds to it
	 * @param glyphs glyph id -> its markup
	 * @param groups keys that cancel each other out
	 * @return the definition (layout: client/PassiveTree/PROTOCOL.txt)
	 */
	static byte[] write(Map<Integer, PassiveNode> nodes, int version, String resetCostText, Map<String, Double> statCaps, Map<String, String> glyphs, List<List<String>> groups)
	{
		final Writer out = new Writer();
		out.int32(FORMAT);
		out.int32(version);
		out.int64(PassiveTreeConfig.RESPEC_ADENA_PER_POINT);
		out.int32(PassiveTreeConfig.RESET_ITEM_ID);
		out.int64(PassiveTreeConfig.RESET_ITEM_COUNT);
		out.string(resetCostText);
		out.int8(PassiveTreeConfig.TEMPLATE_COUNT);

		final Map<String, Double> caps = new TreeMap<>(statCaps);
		out.int16(caps.size());
		for (Map.Entry<String, Double> cap : caps.entrySet())
		{
			out.string(cap.getKey());
			out.float64(cap.getValue());
		}

		// Sectors by first appearance in id order.
		final Map<String, Integer> sectors = new LinkedHashMap<>();
		for (PassiveNode node : nodes.values())
		{
			sectors.putIfAbsent(node.getSector(), sectors.size());
		}
		out.int8(sectors.size());
		for (String sector : sectors.keySet())
		{
			out.string(sector);
		}

		out.int8(groups.size());
		for (List<String> group : groups)
		{
			out.int8(group.size());
			for (String key : group)
			{
				out.string(key);
			}
		}

		out.int16(glyphs.size());
		for (Map.Entry<String, String> glyph : glyphs.entrySet())
		{
			out.string(glyph.getKey());
			out.string(glyph.getValue());
		}

		out.int16(nodes.size());
		for (PassiveNode node : nodes.values())
		{
			out.int32(node.getId());
			out.int8(TYPES.indexOf(node.getType()));
			out.int8(node.getCost());
			out.int8(sectors.get(node.getSector()));
			out.int32(tenths(node.getX()));
			out.int32(tenths(node.getY()));
			out.int8(node.hasOrbit() ? 1 : 0);
			if (node.hasOrbit())
			{
				out.int32(tenths(node.getOrbitX()));
				out.int32(tenths(node.getOrbitY()));
			}
			out.string(node.getName());
			out.string(node.getIcon());
			out.string(node.getEffectSpec());
			out.string(node.getDescription());
			out.int32(node.getSkillId());
			out.int8(node.getParents().size());
			for (int parent : node.getParents())
			{
				out.int32(parent);
			}
		}

		final List<String> problems = new ArrayList<>();
		if (nodes.size() > MAX_NODES)
		{
			problems.add(nodes.size() + " nodes (the client takes " + MAX_NODES + ")");
		}
		if (glyphs.size() > MAX_GLYPHS)
		{
			problems.add(glyphs.size() + " glyphs (the client takes " + MAX_GLYPHS + ")");
		}
		if (sectors.size() > 255)
		{
			problems.add(sectors.size() + " sectors (the client takes 255)");
		}
		for (PassiveNode node : nodes.values())
		{
			if ((node.getParents().size() > MAX_PARENTS) || (node.getCost() > 255))
			{
				problems.add("node " + node.getId() + " has " + node.getParents().size() + " links and costs " + node.getCost() + " (the client takes " + MAX_PARENTS + " links and a cost up to 255)");
			}
		}
		if (out.longest > MAX_STRING)
		{
			problems.add("a text of " + out.longest + " characters (the client takes " + MAX_STRING + ")");
		}
		if (!problems.isEmpty())
		{
			LOGGER.warning(PassiveTreeBlob.class.getSimpleName() + ": Game clients will refuse this tree: " + String.join("; ", problems) + ".");
		}
		return out.toByteArray();
	}

	/** @return glyph id (without "icon-") -> the inner markup of its 24x24 {@code <symbol>}, as the planner page holds them */
	static Map<String, String> glyphs()
	{
		final Map<String, String> glyphs = new TreeMap<>();
		try
		{
			final Matcher matcher = SYMBOL.matcher(Files.readString(GLYPH_PAGE, StandardCharsets.UTF_8));
			while (matcher.find())
			{
				glyphs.putIfAbsent(matcher.group(1), matcher.group(2).trim());
			}
		}
		catch (IOException e)
		{
			LOGGER.warning(PassiveTreeBlob.class.getSimpleName() + ": Could not read the glyphs from " + GLYPH_PAGE + " - game clients draw the tree without icons. " + e.getMessage());
		}
		return glyphs;
	}

	private static int tenths(double value)
	{
		return (int) Math.round(value * 10);
	}

	private static String hex(byte[] bytes)
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
			super(1 << 19);
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

		void int64(long value)
		{
			int32((int) value);
			int32((int) (value >> 32));
		}

		void float64(double value)
		{
			int64(Double.doubleToLongBits(value));
		}

		void string(String value)
		{
			final String text = value == null ? "" : value;
			longest = Math.max(longest, text.length());
			int16(Math.min(text.length(), Short.MAX_VALUE));
			for (int i = 0; i < Math.min(text.length(), Short.MAX_VALUE); i++)
			{
				int16(text.charAt(i));
			}
		}
	}
}
