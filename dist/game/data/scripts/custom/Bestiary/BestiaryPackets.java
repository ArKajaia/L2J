package custom.Bestiary;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

import org.l2jmobius.commons.network.WritableBuffer;
import org.l2jmobius.gameserver.config.RatesConfig;
import org.l2jmobius.gameserver.config.custom.BestiaryConfig;
import org.l2jmobius.gameserver.data.sql.CharInfoTable;
import org.l2jmobius.gameserver.data.xml.ItemData;
import org.l2jmobius.gameserver.data.xml.NpcData;
import org.l2jmobius.gameserver.managers.BestiaryManager;
import org.l2jmobius.gameserver.managers.CraftingGoalManager;
import org.l2jmobius.gameserver.model.actor.Player;
import org.l2jmobius.gameserver.model.actor.holders.npc.DropGroupHolder;
import org.l2jmobius.gameserver.model.actor.holders.npc.DropHolder;
import org.l2jmobius.gameserver.model.actor.templates.NpcTemplate;
import org.l2jmobius.gameserver.model.craftinggoal.CraftingGoal;
import org.l2jmobius.gameserver.model.craftinggoal.CraftingGoalLine;
import org.l2jmobius.gameserver.model.events.Containers;
import org.l2jmobius.gameserver.model.events.EventType;
import org.l2jmobius.gameserver.model.events.holders.actor.player.OnPlayerLogin;
import org.l2jmobius.gameserver.model.events.listeners.ConsumerEventListener;
import org.l2jmobius.gameserver.model.item.ItemTemplate;
import org.l2jmobius.gameserver.model.itemcontainer.Inventory;
import org.l2jmobius.gameserver.model.skill.Skill;
import org.l2jmobius.gameserver.model.stats.TraitType;
import org.l2jmobius.gameserver.network.GameClient;
import org.l2jmobius.gameserver.network.ServerPackets;
import org.l2jmobius.gameserver.network.clientpackets.ClientPacket;
import org.l2jmobius.gameserver.network.clientpackets.custom.CustomClientPackets;
import org.l2jmobius.gameserver.network.serverpackets.ServerPacket;

/**
 * The game client's bestiary window (l2DecompileTest): the same collection, profiles, ranking and farming as the web page ({@link BestiaryWeb}), over custom packets (layouts: client/Bestiary/PROTOCOL.txt).
 * <ul>
 * <li>The catalog, the same for everyone, is {@link BestiaryBlob}: its version goes out at world entry, its chunks only when a client asks.</li>
 * <li>Everything else is built on request from what the bestiary already keeps in memory, and nothing is kept per player but the request limits: a State the client already has is answered "unchanged" from the account's revision.</li>
 * </ul>
 * Every answer is written into a byte array first, so its size is known: pages and goals stay under {@link #PAGE_BYTES}, which the client's receive buffer takes.
 * @author Claude
 */
public final class BestiaryPackets
{
	/** Client request sub-opcodes after D0:00FE. */
	public static final int REQUEST_CHUNK = 7;
	public static final int REQUEST_STATE = 8;
	public static final int REQUEST_MONSTER = 9;
	public static final int REQUEST_RANKING = 10;
	public static final int REQUEST_FARMING = 11;
	public static final int REQUEST_TRACK = 12;
	public static final int REQUEST_PORTRAITS = 13;
	public static final int REQUEST_PORTRAIT_LOOKS = 14;

	/** The client's copies of the portraits, made by tools/bestiary/portraits.py: &lt;look&gt;.l2bp (8 hex digits), 00000000 the field behind them. */
	static final Path PORTRAITS = Path.of("data/html/custom/bestiary/client/portraits");
	static final int PORTRAITS_PER_REQUEST = 16;
	static final int LOOKS_PER_REQUEST = 8;
	static final int PORTRAIT_MAX_BYTES = 64 * 1024;
	static final int PORTRAIT_NONE = 0;
	static final int PORTRAIT_LOOK = 1;
	static final int PORTRAIT_DATA = 2;

	/** Most bytes of one State page or Farming goal. */
	static final int PAGE_BYTES = 24 * 1024;
	/** Longest name or text sent, in characters. */
	static final int MAX_TEXT = 64;

	static final int PART_SUMMARY = 0;
	static final int PART_ENTRIES = 1;
	static final int PART_ELSEWHERE = 2;

	static final int ACTION_TRACK = 1;
	static final int ACTION_UNTRACK = 2;

	/** Owner of the login listener. */
	private static final Object LISTENER_OWNER = new Object();

	private BestiaryPackets()
	{
	}

	/**
	 * Registers the client requests and sends the catalog's version at world entry. Called once the scripts are loaded.
	 */
	public static void register()
	{
		if (!BestiaryConfig.ENABLED)
		{
			return;
		}
		CustomClientPackets.register(REQUEST_CHUNK, RequestChunk::new);
		CustomClientPackets.register(REQUEST_STATE, RequestState::new);
		CustomClientPackets.register(REQUEST_MONSTER, RequestMonster::new);
		CustomClientPackets.register(REQUEST_RANKING, RequestRanking::new);
		CustomClientPackets.register(REQUEST_FARMING, RequestFarming::new);
		CustomClientPackets.register(REQUEST_TRACK, RequestTrack::new);
		CustomClientPackets.register(REQUEST_PORTRAITS, RequestPortraits::new);
		CustomClientPackets.register(REQUEST_PORTRAIT_LOOKS, RequestPortraitLooks::new);
		Containers.Players().addListener(new ConsumerEventListener(Containers.Players(), EventType.ON_PLAYER_LOGIN, (OnPlayerLogin event) -> event.getPlayer().sendPacket(version()), LISTENER_OWNER));
	}

	// ---------------------------------------------------------------------------------------------
	// Server packets
	// ---------------------------------------------------------------------------------------------

	/**
	 * A custom packet whose body was written beforehand.
	 */
	static final class Packet extends ServerPacket
	{
		private final ServerPackets _id;
		private final byte[] _body;

		Packet(ServerPackets id, byte[] body)
		{
			_id = id;
			_body = body;
		}

		int size()
		{
			return _body.length;
		}

		@Override
		public void writeImpl(GameClient client, WritableBuffer buffer)
		{
			_id.writeId(this, buffer);
			buffer.writeBytes(_body);
		}
	}

	/** @return Ex2BestiaryVersion of the live catalog */
	public static ServerPacket version()
	{
		final BestiaryBlob.Blob blob = BestiaryBlob.get();
		final Out out = new Out();
		out.bytes(blob.getHash());
		out.i32(blob.getData().length);
		out.i16(blob.getChunkCount());
		return new Packet(ServerPackets.EX2_BESTIARY_VERSION, out.toByteArray());
	}

	/** @return the tag of a catalog: the first 4 bytes of its SHA-256, little endian */
	static int tag(BestiaryBlob.Blob blob)
	{
		final byte[] hash = blob.getHash();
		return (hash[0] & 0xFF) | ((hash[1] & 0xFF) << 8) | ((hash[2] & 0xFF) << 16) | ((hash[3] & 0xFF) << 24);
	}

	static ServerPacket chunk(BestiaryBlob.Blob blob, int index)
	{
		final byte[] bytes = blob.getChunk(index);
		final Out out = new Out();
		out.i32(tag(blob));
		out.i16(index);
		out.i16(bytes.length);
		out.bytes(bytes);
		return new Packet(ServerPackets.EX2_BESTIARY_CHUNK, out.toByteArray());
	}

	/**
	 * @param npcId the monster to open the window on, 0 for the collection
	 * @return Ex2BestiaryOpen
	 */
	public static ServerPacket open(int npcId)
	{
		final Out out = new Out();
		out.i32(npcId);
		return new Packet(ServerPackets.EX2_BESTIARY_OPEN, out.toByteArray());
	}

	/**
	 * A look's .l2bp file, whole, when it keeps the layout ("L2BP", int format 1, int version, short side, byte kind, byte 0, int length, the blocks).
	 * @param look the look, 0 for the field behind every portrait
	 * @param headerOnly only the first 12 bytes (the version)
	 * @return the bytes, or {@code null} when there is no such file or it breaks the layout
	 */
	private static byte[] lookFile(int look, boolean headerOnly)
	{
		final Path path = PORTRAITS.resolve(BestiaryArt.lookName(look) + ".l2bp");
		byte[] file;
		try
		{
			if (!Files.isRegularFile(path) || (Files.size(path) > PORTRAIT_MAX_BYTES))
			{
				return null;
			}
			if (headerOnly)
			{
				file = new byte[12];
				try (java.io.InputStream in = Files.newInputStream(path))
				{
					if (in.readNBytes(file, 0, 12) != 12)
					{
						return null;
					}
				}
			}
			else
			{
				file = Files.readAllBytes(path);
			}
		}
		catch (IOException e)
		{
			return null;
		}
		if ((file.length < 12) || (file[0] != 'L') || (file[1] != '2') || (file[2] != 'B') || (file[3] != 'P') || (le32(file, 4) != 1) || (le32(file, 8) <= 0))
		{
			return null;
		}
		if (headerOnly)
		{
			return file;
		}
		if (file.length < 20)
		{
			return null;
		}
		final int side = (file[12] & 0xFF) | ((file[13] & 0xFF) << 8);
		final int kind = file[14] & 0xFF;
		final int length = le32(file, 16);
		final int expected = kind == 1 ? (side * side) / 2 : side * side;
		if ((side < 4) || (side > 256) || ((side % 4) != 0) || ((kind != 1) && (kind != 5)) || (length != expected) || (file.length != (20 + length)))
		{
			return null;
		}
		return file;
	}

	/**
	 * Which look a monster's portrait is (monsters of the same mesh and skins share one), and its version.
	 * @param npcId the monster, 0 for the field behind every portrait
	 * @return Ex2BestiaryPortrait: none (no model, or no copy of its look's portrait), or the look and its version
	 */
	static ServerPacket portrait(int npcId)
	{
		final Out out = new Out();
		out.i32(npcId);
		final int look = npcId == 0 ? 0 : BestiaryArt.getInstance().getLook(npcId);
		final byte[] header = (npcId == 0) || (look != 0) ? lookFile(look, true) : null;
		if (header == null)
		{
			out.i8(PORTRAIT_NONE);
		}
		else
		{
			out.i8(PORTRAIT_LOOK);
			out.i32(look);
			out.i32(le32(header, 8));
		}
		return new Packet(ServerPackets.EX2_BESTIARY_PORTRAIT, out.toByteArray());
	}

	/**
	 * A look's picture, read from its .l2bp file each time (16 KB; nothing is kept).
	 * @param look the look
	 * @return Ex2BestiaryPortraitLook: none, or the picture
	 */
	static ServerPacket portraitLook(int look)
	{
		final Out out = new Out();
		out.i32(look);
		final byte[] file = look >= 0 ? lookFile(look, false) : null;
		if (file == null)
		{
			out.i8(PORTRAIT_NONE);
			return new Packet(ServerPackets.EX2_BESTIARY_PORTRAIT_LOOK, out.toByteArray());
		}
		out.i8(PORTRAIT_DATA);
		out.i32(le32(file, 8));
		out.write(file, 12, 2); // side
		out.i8(file[14] & 0xFF);
		out.write(file, 16, 4); // length
		out.write(file, 20, file.length - 20);
		return new Packet(ServerPackets.EX2_BESTIARY_PORTRAIT_LOOK, out.toByteArray());
	}

	private static int le32(byte[] bytes, int at)
	{
		return (bytes[at] & 0xFF) | ((bytes[at + 1] & 0xFF) << 8) | ((bytes[at + 2] & 0xFF) << 16) | ((bytes[at + 3] & 0xFF) << 24);
	}

	static ServerPacket result(int action, boolean ok, int npcId, String message)
	{
		final Out out = new Out();
		out.i8(action);
		out.bool(ok);
		out.i32(npcId);
		out.str(message, 256);
		return new Packet(ServerPackets.EX2_BESTIARY_RESULT, out.toByteArray());
	}

	// ---------------------------------------------------------------------------------------------
	// State
	// ---------------------------------------------------------------------------------------------

	/**
	 * Sends the player's whole collection at once, as an answer to a State request with nothing held: an open window shows a change made for the account (//bestiary_unlock) without waiting for its next ask.
	 * @param player the player
	 */
	public static void sendState(Player player)
	{
		for (ServerPacket packet : state(player, -1, 0, 0))
		{
			player.sendPacket(packet);
		}
	}

	/**
	 * The account's collection: the summary, then its pages; only the summary, saying "unchanged", when the client has it all.
	 * @param player the player
	 * @param revision the revision the client has, -1 for none
	 * @param statsTime the server-wide count the client has
	 * @param bountyReset the bounty change the client has
	 * @return the packets to send, in order
	 */
	static List<ServerPacket> state(Player player, int revision, long statsTime, long bountyReset)
	{
		final BestiaryManager manager = BestiaryManager.getInstance();
		final BestiaryCatalog catalog = BestiaryCatalog.getInstance();
		final BestiaryManager.ServerStats serverStats = manager.getServerStats();
		final BestiaryManager.BountyBoard board = manager.getBountyBoard(player.getObjectId());
		final int current = manager.getRevision(player);
		final List<ServerPacket> packets = new ArrayList<>();

		if ((current >= 0) && (current == revision) && (serverStats.time() == statsTime) && (board.resetAt() == bountyReset))
		{
			final Out out = new Out();
			out.i8(PART_SUMMARY);
			out.i32(current);
			out.bool(true);
			packets.add(new Packet(ServerPackets.EX2_BESTIARY_STATE, out.toByteArray()));
			return packets;
		}

		final Map<Integer, BestiaryManager.Entry> entries = manager.getEntries(player.getObjectId());
		long kills = 0;
		int found = 0;
		final List<byte[]> entryRecords = new ArrayList<>();
		for (BestiaryCatalog.Kind kind : catalog.getMonsters().values())
		{
			final NpcTemplate template = kind.getTemplate();
			final BestiaryManager.Entry entry = entries.get(template.getId());
			if ((entry == null) || (entry.kills() <= 0))
			{
				continue;
			}
			final int tier = BestiaryManager.getTier(template, entry.kills());
			kills += entry.kills();
			found += tier > 0 ? 1 : 0;
			final Out record = new Out();
			writeEntryCore(record, template, entry, tier);
			record.str((tier > 0) || BestiaryConfig.SHOW_UNDISCOVERED_NAMES ? template.getName() : "", MAX_TEXT);
			// Where a found monster lives, when the catalog leaves it out (BestiaryShowUndiscoveredAreas off).
			final List<Integer> areas = new ArrayList<>();
			if (!BestiaryConfig.SHOW_UNDISCOVERED_AREAS && (tier > 0))
			{
				for (int area : kind.getAreas())
				{
					final Integer index = areaIndex().get(area);
					if (index != null)
					{
						areas.add(index);
					}
				}
			}
			record.i8(Math.min(areas.size(), 255));
			for (int i = 0; i < Math.min(areas.size(), 255); i++)
			{
				record.i16(areas.get(i));
			}
			entryRecords.add(record.toByteArray());
		}

		final List<byte[]> elsewhereRecords = new ArrayList<>();
		for (Map.Entry<Integer, BestiaryManager.Entry> e : entries.entrySet())
		{
			if (catalog.getMonsters().containsKey(e.getKey()) || (e.getValue().kills() <= 0))
			{
				continue;
			}
			final NpcTemplate template = NpcData.getInstance().getTemplate(e.getKey());
			if (template == null)
			{
				continue;
			}
			final int tier = BestiaryManager.getTier(template, e.getValue().kills());
			final Out record = new Out();
			record.i32(template.getId());
			record.i32(template.getDisplayId() != template.getId() ? template.getDisplayId() : 0);
			record.i8(clamp(template.getLevel(), 0, 255));
			record.i8(template.getRace().ordinal());
			record.i8(BestiaryWeb.typeCode(template));
			record.i16(clamp(Math.round(template.getFCollisionHeight()), 0, 0xFFFF));
			record.i32(e.getValue().kills());
			record.i8(tier);
			record.i8(e.getValue().variants() & BestiaryManager.getEnabledVariants());
			record.i8((tier > 0) && template.isAggressive() ? 1 : 0);
			record.i8(tier >= 2 ? elementByte(catalog.getAttackElement(template)) : 0xFF);
			record.i64(e.getValue().firstKill());
			record.str(template.getName(), MAX_TEXT);
			elsewhereRecords.add(record.toByteArray());
		}

		final List<byte[]> entryPages = pages(PART_ENTRIES, current, entryRecords);
		final List<byte[]> elsewherePages = pages(PART_ELSEWHERE, current, elsewhereRecords);

		final Out out = new Out();
		out.i8(PART_SUMMARY);
		out.i32(current);
		out.bool(false);
		out.i64(serverStats.time());
		out.i64(board.resetAt());
		out.str(player.getName(), 32);
		out.i32(player.getLevel());
		out.str(BestiaryWeb.className(player.getPlayerClass().getId()), MAX_TEXT);
		out.i32(found);
		out.i32(catalog.getMonsters().size());
		out.i64(kills);
		out.i32(serverStats.getRank(manager.getAccountName(player.getObjectId())));
		out.i32(serverStats.hunters().size());
		out.i16(entryPages.size());
		out.i16(elsewherePages.size());
		out.i32(entryRecords.size());
		out.i32(elsewhereRecords.size());

		final Map<String, Double> regionExp = manager.getRegionExp(entries);
		out.i16(catalog.getRegions().size());
		for (String region : catalog.getRegions())
		{
			out.i32((int) Math.round(regionExp.getOrDefault(region, 1.0) * 1000));
		}

		final List<BestiaryManager.Slayer> slayers = new ArrayList<>(manager.getSlayer(entries).values());
		out.i8(Math.min(slayers.size(), 255));
		for (int i = 0; i < Math.min(slayers.size(), 255); i++)
		{
			final BestiaryManager.Slayer slayer = slayers.get(i);
			out.i8(slayer.race().ordinal());
			out.i16(clamp(slayer.monsters(), 0, 0xFFFF));
			out.i16(clamp(slayer.found(), 0, 0xFFFF));
			out.i32((int) Math.round(slayer.damage() * 100));
			out.i32((int) Math.round(slayer.defense() * 100));
		}

		out.bool(BestiaryConfig.BOUNTY_ENABLED);
		out.i32(BestiaryConfig.BOUNTY_KILL_MULTIPLIER);
		out.i32(BestiaryConfig.BOUNTY_ADENA_PER_LEVEL);
		out.i8(Math.min(board.bounties().size(), 6));
		for (int i = 0; i < Math.min(board.bounties().size(), 6); i++)
		{
			final BestiaryManager.Bounty bounty = board.bounties().get(i);
			out.i32(bounty.npcId());
			out.i32(bounty.progress());
			out.i32(bounty.goal());
		}

		packets.add(new Packet(ServerPackets.EX2_BESTIARY_STATE, out.toByteArray()));
		for (byte[] page : entryPages)
		{
			packets.add(new Packet(ServerPackets.EX2_BESTIARY_STATE, page));
		}
		for (byte[] page : elsewherePages)
		{
			packets.add(new Packet(ServerPackets.EX2_BESTIARY_STATE, page));
		}
		return packets;
	}

	/** npcId, kills, tier, variants, flags and element of an entry of the collection, then firstKill. */
	private static void writeEntryCore(Out out, NpcTemplate template, BestiaryManager.Entry entry, int tier)
	{
		out.i32(template.getId());
		out.i32(entry.kills());
		out.i8(tier);
		out.i8(entry.variants() & BestiaryManager.getEnabledVariants());
		out.i8((tier > 0) && template.isAggressive() ? 1 : 0);
		out.i8(tier >= 2 ? elementByte(BestiaryCatalog.getInstance().getAttackElement(template)) : 0xFF);
		out.i64(entry.firstKill());
	}

	/**
	 * @param element an attack element as the game keeps it (0 fire ... 5 dark; AttributeType.NONE is -2)
	 * @return its byte on the wire: 0..5, or 0xFF for none (the client refuses any other value, and the page with it)
	 */
	private static int elementByte(int element)
	{
		return (element >= 0) && (element < 6) ? element : 0xFF;
	}

	/** Puts the records into pages of at most {@link #PAGE_BYTES}: part, revision, page, count, records. */
	private static List<byte[]> pages(int part, int revision, List<byte[]> records)
	{
		final List<byte[]> pages = new ArrayList<>();
		int index = 0;
		while (index < records.size())
		{
			int bytes = 9;
			int end = index;
			while ((end < records.size()) && ((end == index) || ((bytes + records.get(end).length) <= PAGE_BYTES)) && ((end - index) < 0xFFFF))
			{
				bytes += records.get(end).length;
				end++;
			}
			final Out out = new Out();
			out.i8(part);
			out.i32(revision);
			out.i16(pages.size());
			out.i16(end - index);
			for (int i = index; i < end; i++)
			{
				out.bytes(records.get(i));
			}
			pages.add(out.toByteArray());
			index = end;
		}
		return pages;
	}

	// ---------------------------------------------------------------------------------------------
	// One monster
	// ---------------------------------------------------------------------------------------------

	/**
	 * One profile, as much as the account's tier shows, as /api/bestiary/monster gives it.
	 * @param player the player
	 * @param npcId the monster
	 * @return Ex2BestiaryMonster
	 */
	static ServerPacket monster(Player player, int npcId)
	{
		final int charId = player.getObjectId();
		final BestiaryCatalog catalog = BestiaryCatalog.getInstance();
		final BestiaryCatalog.Kind kind = catalog.getMonsters().get(npcId);
		final BestiaryManager.Entry entry = BestiaryManager.getInstance().getEntries(charId).get(npcId);
		final NpcTemplate template = NpcData.getInstance().getTemplate(npcId);

		final Out out = new Out();
		out.i32(npcId);
		// A monster outside the collection is only shown to who found it, so the window can't be used to look through every npc.
		if ((template == null) || ((kind == null) && (entry == null)))
		{
			out.i8(0xFF);
			return new Packet(ServerPackets.EX2_BESTIARY_MONSTER, out.toByteArray());
		}

		final int kills = entry != null ? entry.kills() : 0;
		final int tier = BestiaryManager.getTier(template, kills);
		final BestiaryCatalog.Stats stats = tier >= 1 ? catalog.getStats(template) : null;
		final int dropBonus = BestiaryManager.getDropBonus(template, tier);
		out.i8(tier);
		out.i32(kills);
		out.bool(kind != null);
		out.bool((stats != null) && stats.live);
		out.i16(dropBonus);
		out.str((tier > 0) || BestiaryConfig.SHOW_UNDISCOVERED_NAMES ? template.getName() : "", MAX_TEXT);
		out.str(tier > 0 ? template.getTitle() : "", MAX_TEXT);
		out.i64(entry != null ? entry.firstKill() : 0);
		out.i64(entry != null ? entry.lastKill() : 0);
		out.i8(entry != null ? entry.variants() & BestiaryManager.getEnabledVariants() : 0);
		out.i32(entry != null ? entry.bestTime() : 0);
		String bestBy = "";
		if ((entry != null) && (entry.bestTime() > 0))
		{
			final String by = CharInfoTable.getInstance().getNameById(entry.bestChar());
			bestBy = by != null ? by : "";
		}
		out.str(bestBy, 32);

		final boolean areas = (kind != null) && ((tier > 0) || BestiaryConfig.SHOW_UNDISCOVERED_AREAS);
		out.bool(areas);
		if (areas)
		{
			final List<Integer> indexes = new ArrayList<>();
			for (int area : kind.getAreas())
			{
				final Integer index = areaIndex().get(area);
				if (index != null)
				{
					indexes.add(index);
				}
			}
			out.i8(Math.min(indexes.size(), 255));
			for (int i = 0; i < Math.min(indexes.size(), 255); i++)
			{
				out.i16(indexes.get(i));
			}
			final List<int[]> points = kind.getPoints();
			out.i8(Math.min(points.size(), 255));
			for (int i = 0; i < Math.min(points.size(), 255); i++)
			{
				out.i16(clamp(Math.round(points.get(i)[0] / (float) BestiaryBlob.POINT_UNIT), Short.MIN_VALUE, Short.MAX_VALUE));
				out.i16(clamp(Math.round(points.get(i)[1] / (float) BestiaryBlob.POINT_UNIT), Short.MIN_VALUE, Short.MAX_VALUE));
			}
		}

		if (tier >= 1)
		{
			out.bool(template.isAggressive());
			out.i64(stats.hp);
			out.i64(stats.mp);
			out.i64(Math.round(template.getExp() * RatesConfig.RATE_XP));
			out.i64(Math.round(template.getSP() * RatesConfig.RATE_SP));
			out.str(template.getBaseAttackType() != null ? template.getBaseAttackType().name() : "", MAX_TEXT);
			out.i32(kind != null ? kind.getSpawnCount() : -1);
			out.i32(kind != null ? kind.getRespawnMin() : 0);
			out.i32(kind != null ? kind.getRespawnMax() : 0);
			final BestiaryManager.ServerStats serverStats = BestiaryManager.getInstance().getServerStats();
			final BestiaryManager.MonsterStats monster = serverStats.monsters().get(template.getId());
			out.i32(serverStats.hunters().size());
			out.i64(monster != null ? monster.kills() : 0);
			out.i32(monster != null ? monster.hunters() : 0);
			out.str((monster != null) && (monster.firstFinder() != null) ? monster.firstFinder() : "", 32);
			out.i64((monster != null) && (monster.firstFinder() != null) ? monster.firstKill() : 0);
			out.i32(monster != null ? monster.bestTime() : 0);
			out.i64(serverStats.time());
		}
		if (tier >= 2)
		{
			out.i32(stats.pAtk);
			out.i32(stats.mAtk);
			out.i32(stats.pDef);
			out.i32(stats.mDef);
			out.i32(stats.pAtkSpd);
			out.i32(stats.mAtkSpd);
			out.i32(stats.runSpeed);
			out.i32(stats.walkSpeed);
			out.i32(stats.accuracy);
			out.i32(stats.evasion);
			out.i32(stats.critical);
			out.i32(stats.attackRange);
			out.i8(elementByte(stats.attackElement));
			out.i16(clamp(stats.attackElementValue, Short.MIN_VALUE, Short.MAX_VALUE));
			for (int i = 0; i < 6; i++)
			{
				out.i16(clamp(stats.defenseElements[i], Short.MIN_VALUE, Short.MAX_VALUE));
			}
			writeTactics(out, template, stats);
			final List<Skill> skills = new ArrayList<>(template.getSkills().values());
			skills.sort((a, b) -> a.isPassive() != b.isPassive() ? (a.isPassive() ? 1 : -1) : a.getName().compareTo(b.getName()));
			out.i16(Math.min(skills.size(), 0xFFFF));
			for (Skill skill : skills)
			{
				out.i32(skill.getId());
				out.i16(skill.getLevel());
				out.i8((skill.isPassive() ? 1 : 0) | (skill.isDebuff() ? 2 : 0));
				out.str(skill.getName(), MAX_TEXT);
			}
		}
		if (tier >= 3)
		{
			writeDrops(out, template, dropBonus);
		}
		final boolean killLog = BestiaryManager.isBoss(template) && (tier > 0);
		out.bool(killLog);
		if (killLog)
		{
			final List<BestiaryManager.BossKill> bossKills = BestiaryManager.getInstance().getBossKills(npcId);
			out.i8(Math.min(bossKills.size(), 255));
			for (int i = 0; i < Math.min(bossKills.size(), 255); i++)
			{
				final BestiaryManager.BossKill kill = bossKills.get(i);
				out.i64(kill.time());
				out.i32(kill.duration());
				out.str(kill.killer(), 32);
				out.str(kill.clan(), 32);
				out.i8(clamp(kill.partySize(), 0, 255));
			}
		}
		return new Packet(ServerPackets.EX2_BESTIARY_MONSTER, out.toByteArray());
	}

	/** Aggro and help ranges, how it moves, its AI, the weapons that hurt it more or less, the debuffs it resists (as BestiaryWeb.appendTactics). */
	private static void writeTactics(Out out, NpcTemplate template, BestiaryCatalog.Stats stats)
	{
		out.i32(template.isAggressive() ? template.getAggroRange() : 0);
		// A template without clans keeps null (NpcTemplate.getClans), not an empty set.
		out.i32((template.getClans() != null) && !template.getClans().isEmpty() ? template.getClanHelpRange() : 0);
		out.bool(template.canMove());
		out.bool(template.isRandomWalkEnabled());
		out.str(template.getAIType() != null ? template.getAIType().name() : "", MAX_TEXT);

		final List<TraitType> weapons = new ArrayList<>();
		final List<Integer> weaponPercents = new ArrayList<>();
		for (TraitType trait : BestiaryWeb.WEAPON_TRAITS)
		{
			// A weapon's damage is multiplied by 2 minus the defence against it (Formulas.calcWeaponTraitBonus).
			final int percent = Math.round((1 - stats.defenceTraits[trait.ordinal()]) * 100);
			if (Math.abs(percent) >= 5)
			{
				weapons.add(trait);
				weaponPercents.add(percent);
			}
		}
		out.i8(weapons.size());
		for (int i = 0; i < weapons.size(); i++)
		{
			out.str(weapons.get(i).name(), MAX_TEXT);
			out.i16(weaponPercents.get(i));
		}

		final List<TraitType> resists = new ArrayList<>();
		for (TraitType trait : BestiaryWeb.RESIST_TRAITS)
		{
			final boolean immune = stats.traitsInvul[trait.ordinal()] > 0;
			final int percent = Math.round((stats.defenceTraits[trait.ordinal()] - 1) * 100);
			if (immune || (Math.abs(percent) >= 5))
			{
				resists.add(trait);
			}
		}
		out.i8(resists.size());
		for (TraitType trait : resists)
		{
			out.str(trait.name(), MAX_TEXT);
			out.i16(Math.round((stats.defenceTraits[trait.ordinal()] - 1) * 100));
			out.bool(stats.traitsInvul[trait.ordinal()] > 0);
		}
	}

	/** Drops and spoils at the server's rates with the account's bonus, and what was observed (as BestiaryWeb.appendMastered). */
	private static void writeDrops(Out out, NpcTemplate template, int dropBonus)
	{
		final boolean boss = BestiaryManager.isBoss(template);
		final BestiaryManager.ObservedDrops observed = BestiaryConfig.DROP_STATS_ENABLED ? BestiaryManager.getInstance().getObservedDrops(template.getId()) : null;
		final Map<Integer, BestiaryManager.ObservedItem> observedDrops = byItem(observed != null ? observed.drops() : null);
		final Map<Integer, BestiaryManager.ObservedItem> observedSpoils = byItem(observed != null ? observed.spoils() : null);

		final List<byte[]> drops = new ArrayList<>();
		if (template.getDropGroups() != null)
		{
			int group = 0;
			for (DropGroupHolder dropGroup : template.getDropGroups())
			{
				group++;
				for (DropHolder drop : dropGroup.getDropList())
				{
					addDrop(drops, drop, drop.getChance() * (dropGroup.getChance() / 100), boss, group, dropBonus, observedDrops);
				}
			}
		}
		if (template.getDropList() != null)
		{
			for (DropHolder drop : template.getDropList())
			{
				addDrop(drops, drop, drop.getChance(), boss, 0, dropBonus, observedDrops);
			}
		}
		addObservedOnly(drops, observedDrops);

		final List<byte[]> spoils = new ArrayList<>();
		if (template.getSpoilList() != null)
		{
			for (DropHolder drop : template.getSpoilList())
			{
				addDrop(spoils, drop, drop.getChance(), boss, 0, 0, observedSpoils);
			}
		}
		addObservedOnly(spoils, observedSpoils);

		out.i16(drops.size());
		for (byte[] drop : drops)
		{
			out.bytes(drop);
		}
		out.i16(spoils.size());
		for (byte[] spoil : spoils)
		{
			out.bytes(spoil);
		}
		out.bool(observed != null);
		if (observed != null)
		{
			out.i64(observed.dropRolls());
			out.i64(observed.spoilRolls());
			out.i32(BestiaryConfig.DROP_STATS_MIN_KILLS);
		}
	}

	private static Map<Integer, BestiaryManager.ObservedItem> byItem(List<BestiaryManager.ObservedItem> items)
	{
		final Map<Integer, BestiaryManager.ObservedItem> byItem = new LinkedHashMap<>();
		if (items != null)
		{
			for (BestiaryManager.ObservedItem item : items)
			{
				byItem.put(item.itemId(), item);
			}
		}
		return byItem;
	}

	private static void writeItem(Out out, ItemTemplate item)
	{
		out.i32(item.getId());
		out.str(item.getName(), MAX_TEXT);
		out.str(item.getCrystalType() != null ? item.getCrystalType().name() : "NONE", 16);
		out.str(BestiaryWeb.itemKind(item), 16);
	}

	private static void addDrop(List<byte[]> rows, DropHolder drop, double baseChance, boolean boss, int group, int dropBonus, Map<Integer, BestiaryManager.ObservedItem> observed)
	{
		final ItemTemplate item = ItemData.getInstance().getTemplate(drop.getItemId());
		if (item == null)
		{
			return;
		}
		final double[] rates = BestiaryWeb.rates(drop, item, boss);
		// The bestiary's bonus is for the items, as NpcTemplate rolls it: not adena or herbs, nor spoils (the caller gives them none).
		final boolean boosted = (dropBonus > 0) && (item.getId() != Inventory.ADENA_ID) && !item.hasExImmediateEffect();
		// An item in two groups: its count is the item's, so it goes on its first row.
		final BestiaryManager.ObservedItem seen = observed.remove(item.getId());
		final Out out = new Out();
		writeItem(out, item);
		out.i8((boosted ? 1 : 0) | (seen != null ? 4 : 0));
		out.i8(clamp(group, 0, 255));
		out.i64(Math.max(1, Math.round(drop.getMin() * rates[1])));
		out.i64(Math.max(1, Math.round(drop.getMax() * rates[1])));
		out.i32(chance(Math.min(100, baseChance * rates[0] * (boosted ? 1 + (dropBonus / 100.0) : 1))));
		out.i64(seen != null ? seen.times() : 0);
		out.i64(seen != null ? seen.amount() : 0);
		rows.add(out.toByteArray());
	}

	/** Items that came out of the drops or spoil although the lists don't have them: no chance, only how often. */
	private static void addObservedOnly(List<byte[]> rows, Map<Integer, BestiaryManager.ObservedItem> observed)
	{
		for (BestiaryManager.ObservedItem seen : observed.values())
		{
			final ItemTemplate item = ItemData.getInstance().getTemplate(seen.itemId());
			if (item == null)
			{
				continue;
			}
			final Out out = new Out();
			writeItem(out, item);
			out.i8(2 | 4);
			out.i8(0);
			out.i64(0);
			out.i64(0);
			out.i32(0);
			out.i64(seen.times());
			out.i64(seen.amount());
			rows.add(out.toByteArray());
		}
	}

	/** @return a chance in percent as percent x 10000 */
	private static int chance(double percent)
	{
		return (int) Math.max(0, Math.min(Integer.MAX_VALUE, Math.round(percent * 10000)));
	}

	private static volatile Map<Integer, Integer> _areaIndex;

	/** @return the catalog's area ids -> their index in it, as BestiaryBlob lists them */
	private static Map<Integer, Integer> areaIndex()
	{
		Map<Integer, Integer> index = _areaIndex;
		if (index == null)
		{
			index = new HashMap<>();
			for (BestiaryCatalog.Area area : BestiaryCatalog.getInstance().getAreas())
			{
				index.put(area.getId(), index.size());
			}
			_areaIndex = index;
		}
		return index;
	}

	// ---------------------------------------------------------------------------------------------
	// Ranking
	// ---------------------------------------------------------------------------------------------

	static ServerPacket ranking(Player player)
	{
		final BestiaryManager manager = BestiaryManager.getInstance();
		final BestiaryManager.ServerStats serverStats = manager.getServerStats();
		final String account = manager.getAccountName(player.getObjectId());
		final List<BestiaryManager.Hunter> hunters = serverStats.hunters();
		final Out out = new Out();
		out.i64(serverStats.time());
		out.i32(BestiaryCatalog.getInstance().getMonsters().size());
		out.i32(hunters.size());
		final int top = Math.min(Math.min(BestiaryConfig.RANKING_SIZE, hunters.size()), 255);
		out.i8(top);
		for (int i = 0; i < top; i++)
		{
			writeHunter(out, hunters.get(i), i + 1, account);
		}
		final int rank = serverStats.getRank(account);
		final boolean me = rank > top;
		out.bool(me);
		if (me)
		{
			writeHunter(out, hunters.get(rank - 1), rank, account);
		}
		return new Packet(ServerPackets.EX2_BESTIARY_RANKING, out.toByteArray());
	}

	private static void writeHunter(Out out, BestiaryManager.Hunter hunter, int rank, String account)
	{
		out.i32(rank);
		out.str(hunter.name(), 32);
		out.i8(clamp(hunter.level(), 0, 255));
		out.str(BestiaryWeb.className(hunter.classId()), MAX_TEXT);
		out.i32(hunter.found());
		out.i64(hunter.kills());
		out.bool((account != null) && hunter.account().equalsIgnoreCase(account));
	}

	// ---------------------------------------------------------------------------------------------
	// Farming
	// ---------------------------------------------------------------------------------------------

	/**
	 * Where to farm what each crafting goal still misses, one packet a goal (as /api/bestiary/farming).
	 * @param player the player
	 * @param spoilMode 0 without spoils, 1 with them, 2 as the character (with them when it knows Spoil)
	 * @return the packets to send
	 */
	static List<ServerPacket> farming(Player player, int spoilMode)
	{
		final List<ServerPacket> packets = new ArrayList<>();
		final boolean on = BestiaryFarming.isAvailable();
		final boolean spoiler = on && BestiaryFarming.canSpoil(player);
		final boolean spoils = spoilMode == 2 ? spoiler : spoilMode == 1;
		final List<CraftingGoal> goals = on ? new ArrayList<>(CraftingGoalManager.getInstance().getGoals(player)) : new ArrayList<>();
		final int count = Math.min(goals.size(), 255);
		for (int g = 0; g < Math.max(1, count); g++)
		{
			final Out header = new Out();
			header.bool(on);
			if (on)
			{
				// The request comes from a player in the world: always online.
				header.bool(true);
				header.i32(player.getLevel());
				header.bool(spoiler);
				header.bool(spoils);
				header.i32(BestiaryConfig.FARMING_LEVELS_ABOVE);
				header.i8(g);
				header.i8(count);
			}
			if (!on || (count == 0))
			{
				packets.add(new Packet(ServerPackets.EX2_BESTIARY_FARMING, header.toByteArray()));
				break;
			}
			final Out goal = new Out();
			goal.bytes(header.toByteArray());
			writeGoal(goal, BestiaryFarming.getInstance().plan(player, goals.get(g), spoils));
			packets.add(new Packet(ServerPackets.EX2_BESTIARY_FARMING, goal.toByteArray()));
		}
		return packets;
	}

	private static void writeGoal(Out out, BestiaryFarming.GoalPlan plan)
	{
		final CraftingGoal goal = plan.goal();
		out.i32(goal.getRecipeListId());
		out.i32(goal.getProductId());
		out.str(CraftingGoalManager.describe(goal), MAX_TEXT * 2);
		out.i32(CraftingGoal.countCompleteComponents(plan.lines()));
		out.i32(CraftingGoal.countComponents(plan.lines()));
		out.bool(CraftingGoal.isReady(plan.lines()));
		out.i32(plan.farmable());

		// Resources while the packet stays under PAGE_BYTES; the grounds after them need about 1 KB.
		final List<byte[]> resources = new ArrayList<>();
		int bytes = out.size() + 1024;
		for (BestiaryFarming.Resource resource : plan.resources())
		{
			final byte[] row = resourceRow(resource);
			if ((resources.size() >= 255) || ((bytes + row.length) > PAGE_BYTES))
			{
				break;
			}
			resources.add(row);
			bytes += row.length;
		}
		out.i8(resources.size());
		for (byte[] row : resources)
		{
			out.bytes(row);
		}

		final List<BestiaryFarming.Ground> grounds = plan.grounds();
		final int groundCount = Math.min(grounds.size(), 32);
		final List<BestiaryFarming.Ground> kept = new ArrayList<>();
		for (int i = 0; i < groundCount; i++)
		{
			kept.add(grounds.get(i));
		}
		out.i8(kept.size());
		for (BestiaryFarming.Ground ground : kept)
		{
			out.i32(ground.area().getId());
			out.i32((int) Math.round(ground.score() * 100));
			final List<Map.Entry<Integer, BestiaryFarming.Spot>> best = new ArrayList<>();
			for (Map.Entry<Integer, BestiaryFarming.Spot> e : ground.best().entrySet())
			{
				// A best spot of a resource left out of the packet would point at nothing.
				if (e.getKey() < resources.size())
				{
					best.add(e);
				}
			}
			out.i8(Math.min(best.size(), 255));
			for (int i = 0; i < Math.min(best.size(), 255); i++)
			{
				out.i8(best.get(i).getKey());
				out.i32(best.get(i).getValue().template().getId());
				out.i64(best.get(i).getValue().kills());
			}
		}
	}

	private static byte[] resourceRow(BestiaryFarming.Resource resource)
	{
		final CraftingGoalLine line = resource.line();
		final ItemTemplate item = ItemData.getInstance().getTemplate(line.getItemId());
		final Out out = new Out();
		out.i32(line.getItemId());
		out.str(item != null ? item.getName() : "", MAX_TEXT);
		out.str((item != null) && (item.getCrystalType() != null) ? item.getCrystalType().name() : "NONE", 16);
		out.str(item != null ? BestiaryWeb.itemKind(item) : "etc", 16);
		out.i64(line.getHave());
		out.i64(line.getNeeded());
		out.bool(line.isMaterial());
		out.bool(line.isCraftable());
		out.i32(resource.unfound());
		out.i8(Math.min(resource.hints().size(), 255));
		for (BestiaryFarming.Hint hint : resource.hints())
		{
			out.str(hint.region(), MAX_TEXT);
			out.i32(hint.monsters());
			out.i32(hint.level());
			out.bool(hint.higher());
		}
		out.i8(Math.min(resource.spots().size(), 255));
		for (BestiaryFarming.Spot spot : resource.spots())
		{
			out.i32(spot.template().getId());
			out.i64(Math.round(spot.perKill() * 10000));
			out.i64(spot.kills());
			out.bool(spot.counted());
			out.i8((spot.drop() != null ? 1 : 0) | (spot.spoil() != null ? 2 : 0));
			writeYield(out, spot.drop());
			writeYield(out, spot.spoil());
		}
		return out.toByteArray();
	}

	private static void writeYield(Out out, BestiaryFarming.Yield yield)
	{
		if (yield == null)
		{
			return;
		}
		out.i32(chance(yield.chance()));
		out.i64(yield.min());
		out.i64(yield.max());
		out.bool(yield.boosted());
	}

	// ---------------------------------------------------------------------------------------------
	// Client requests
	// ---------------------------------------------------------------------------------------------

	/** Per connection: when each request was last answered. Weak keys: an entry goes with its connection. */
	private static final class Limits
	{
		long chunkWindow;
		int chunks;
		long state;
		final long[] monsters = new long[10];
		int monsterSlot;
		long ranking;
		long farming;
		long track;
		long portraitWindow;
		int portraitRequests;
		long lookWindow;
		int lookRequests;
	}

	private static final Map<GameClient, Limits> LIMITS = new WeakHashMap<>();

	static final int CHUNK_BUDGET = 400;
	static final long CHUNK_WINDOW_MS = 60_000;
	static final long STATE_INTERVAL_MS = 2_000;
	static final long MONSTER_WINDOW_MS = 5_000;
	static final long RANKING_INTERVAL_MS = 10_000;
	static final long FARMING_INTERVAL_MS = 5_000;
	static final long TRACK_INTERVAL_MS = 1_000;
	static final int PORTRAIT_BUDGET = 240; // requests of up to 16 monsters a minute (a few bytes each)
	static final int LOOK_BUDGET = 120; // requests of up to 8 looks a minute: 960 pictures, 16 MB at most
	static final long PORTRAIT_WINDOW_MS = 60_000;

	private static Limits limits(GameClient client)
	{
		return LIMITS.computeIfAbsent(client, k -> new Limits());
	}

	/** @return {@code false} when the request comes too soon after the last one of its kind: drop it */
	static boolean allow(GameClient client, int request)
	{
		final long now = System.currentTimeMillis();
		synchronized (LIMITS)
		{
			final Limits limits = limits(client);
			switch (request)
			{
				case REQUEST_CHUNK:
				{
					if ((now - limits.chunkWindow) >= CHUNK_WINDOW_MS)
					{
						limits.chunkWindow = now;
						limits.chunks = 0;
					}
					return ++limits.chunks <= CHUNK_BUDGET;
				}
				case REQUEST_MONSTER:
				{
					// The oldest of the last ten must be older than the window.
					if ((now - limits.monsters[limits.monsterSlot]) < MONSTER_WINDOW_MS)
					{
						return false;
					}
					limits.monsters[limits.monsterSlot] = now;
					limits.monsterSlot = (limits.monsterSlot + 1) % limits.monsters.length;
					return true;
				}
				case REQUEST_STATE:
				{
					if ((now - limits.state) < STATE_INTERVAL_MS)
					{
						return false;
					}
					limits.state = now;
					return true;
				}
				case REQUEST_RANKING:
				{
					if ((now - limits.ranking) < RANKING_INTERVAL_MS)
					{
						return false;
					}
					limits.ranking = now;
					return true;
				}
				case REQUEST_FARMING:
				{
					if ((now - limits.farming) < FARMING_INTERVAL_MS)
					{
						return false;
					}
					limits.farming = now;
					return true;
				}
				case REQUEST_PORTRAITS:
				{
					if ((now - limits.portraitWindow) >= PORTRAIT_WINDOW_MS)
					{
						limits.portraitWindow = now;
						limits.portraitRequests = 0;
					}
					return ++limits.portraitRequests <= PORTRAIT_BUDGET;
				}
				case REQUEST_PORTRAIT_LOOKS:
				{
					if ((now - limits.lookWindow) >= PORTRAIT_WINDOW_MS)
					{
						limits.lookWindow = now;
						limits.lookRequests = 0;
					}
					return ++limits.lookRequests <= LOOK_BUDGET;
				}
				default:
				{
					if ((now - limits.track) < TRACK_INTERVAL_MS)
					{
						return false;
					}
					limits.track = now;
					return true;
				}
			}
		}
	}

	/** The requests share this: a player in the world, the body read whole, the limit kept. */
	private abstract static class Request extends ClientPacket
	{
		private boolean _whole;

		abstract void readBody();

		abstract int kind();

		abstract void run(Player player);

		@Override
		protected void readImpl()
		{
			readBody();
			_whole = remaining() == 0;
		}

		@Override
		protected void runImpl()
		{
			final Player player = getPlayer();
			if ((player == null) || !_whole || !BestiaryConfig.ENABLED || !allow(getClient(), kind()))
			{
				return;
			}
			run(player);
		}
	}

	static final class RequestChunk extends Request
	{
		private int _tag;
		private int _index;

		@Override
		void readBody()
		{
			_tag = readInt();
			_index = Short.toUnsignedInt(readShort());
		}

		@Override
		int kind()
		{
			return REQUEST_CHUNK;
		}

		@Override
		void run(Player player)
		{
			final BestiaryBlob.Blob blob = BestiaryBlob.get();
			if ((_tag != tag(blob)) || (_index >= blob.getChunkCount()))
			{
				player.sendPacket(version());
				return;
			}
			player.sendPacket(chunk(blob, _index));
		}
	}

	static final class RequestState extends Request
	{
		private int _revision;
		private long _statsTime;
		private long _bountyReset;

		@Override
		void readBody()
		{
			_revision = readInt();
			_statsTime = readLong();
			_bountyReset = readLong();
		}

		@Override
		int kind()
		{
			return REQUEST_STATE;
		}

		@Override
		void run(Player player)
		{
			for (ServerPacket packet : state(player, _revision, _statsTime, _bountyReset))
			{
				player.sendPacket(packet);
			}
		}
	}

	static final class RequestMonster extends Request
	{
		private int _npcId;

		@Override
		void readBody()
		{
			_npcId = readInt();
		}

		@Override
		int kind()
		{
			return REQUEST_MONSTER;
		}

		@Override
		void run(Player player)
		{
			player.sendPacket(monster(player, _npcId));
		}
	}

	static final class RequestRanking extends Request
	{
		@Override
		void readBody()
		{
		}

		@Override
		int kind()
		{
			return REQUEST_RANKING;
		}

		@Override
		void run(Player player)
		{
			player.sendPacket(ranking(player));
		}
	}

	static final class RequestFarming extends Request
	{
		private int _spoil;

		@Override
		void readBody()
		{
			_spoil = readUnsignedByte();
		}

		@Override
		int kind()
		{
			return REQUEST_FARMING;
		}

		@Override
		void run(Player player)
		{
			for (ServerPacket packet : farming(player, Math.min(_spoil, 2)))
			{
				player.sendPacket(packet);
			}
		}
	}

	static final class RequestTrack extends Request
	{
		private int _npcId;

		@Override
		void readBody()
		{
			_npcId = readInt();
		}

		@Override
		int kind()
		{
			return REQUEST_TRACK;
		}

		@Override
		void run(Player player)
		{
			if (_npcId == 0)
			{
				BestiaryTracker.untrack(player);
				player.sendMessage("Bestiary: radar cleared.");
				player.sendPacket(result(ACTION_UNTRACK, true, 0, "Bestiary: radar cleared."));
				return;
			}
			final BestiaryTracker.Result result = BestiaryTracker.track(player, _npcId);
			player.sendMessage(result.message());
			player.sendPacket(result(ACTION_TRACK, result.tracking(), _npcId, result.message()));
		}
	}

	/** byte count (1..16), count x int npcId: one Ex2BestiaryPortrait each. */
	static final class RequestPortraits extends Request
	{
		private int[] _npcIds;

		@Override
		void readBody()
		{
			final int count = readByte() & 0xFF;
			if ((count == 0) || (count > PORTRAITS_PER_REQUEST) || (remaining() != (count * 4)))
			{
				_npcIds = null;
				return;
			}
			_npcIds = new int[count];
			for (int i = 0; i < count; i++)
			{
				_npcIds[i] = readInt();
			}
		}

		@Override
		int kind()
		{
			return REQUEST_PORTRAITS;
		}

		@Override
		void run(Player player)
		{
			if (_npcIds == null)
			{
				return;
			}
			for (int npcId : _npcIds)
			{
				player.sendPacket(portrait(npcId));
			}
		}
	}

	/** byte count (1..8), count x int look: one Ex2BestiaryPortraitLook each. */
	static final class RequestPortraitLooks extends Request
	{
		private int[] _looks;

		@Override
		void readBody()
		{
			final int count = readByte() & 0xFF;
			if ((count == 0) || (count > LOOKS_PER_REQUEST) || (remaining() != (count * 4)))
			{
				_looks = null;
				return;
			}
			_looks = new int[count];
			for (int i = 0; i < count; i++)
			{
				_looks[i] = readInt();
			}
		}

		@Override
		int kind()
		{
			return REQUEST_PORTRAIT_LOOKS;
		}

		@Override
		void run(Player player)
		{
			if (_looks == null)
			{
				return;
			}
			for (int look : _looks)
			{
				player.sendPacket(portraitLook(look));
			}
		}
	}

	// ---------------------------------------------------------------------------------------------
	// Writing
	// ---------------------------------------------------------------------------------------------

	private static int clamp(int value, int min, int max)
	{
		return Math.max(min, Math.min(max, value));
	}

	/** Little endian; strings as UTF-16LE with a terminating 0, cut to a length. */
	static final class Out extends ByteArrayOutputStream
	{
		Out()
		{
			super(256);
		}

		void i8(int value)
		{
			write(value);
		}

		void bool(boolean value)
		{
			write(value ? 1 : 0);
		}

		void i16(int value)
		{
			write(value);
			write(value >> 8);
		}

		void i32(int value)
		{
			i16(value);
			i16(value >> 16);
		}

		void i64(long value)
		{
			i32((int) value);
			i32((int) (value >> 32));
		}

		void bytes(byte[] value)
		{
			write(value, 0, value.length);
		}

		void str(String value, int maxLength)
		{
			String text = value == null ? "" : value;
			if (text.length() > maxLength)
			{
				text = text.substring(0, maxLength);
			}
			// Nothing in it may end the string early.
			text = text.replace('\0', ' ');
			bytes(text.getBytes(StandardCharsets.UTF_16LE));
			i16(0);
		}
	}
}
