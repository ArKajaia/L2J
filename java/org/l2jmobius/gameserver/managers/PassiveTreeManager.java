package org.l2jmobius.gameserver.managers;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.l2jmobius.commons.database.DatabaseFactory;
import org.l2jmobius.gameserver.config.custom.PassiveTreeConfig;
import org.l2jmobius.gameserver.data.custom.PassiveTreeData;
import org.l2jmobius.gameserver.data.xml.SkillData;
import org.l2jmobius.gameserver.model.actor.Player;
import org.l2jmobius.gameserver.model.item.enums.ItemProcessType;
import org.l2jmobius.gameserver.model.passivetree.PassiveNode;
import org.l2jmobius.gameserver.model.skill.PassiveTreeArchetypes;
import org.l2jmobius.gameserver.model.skill.Skill;
import org.l2jmobius.gameserver.model.stats.Stat;
import org.l2jmobius.gameserver.model.stats.functions.FuncAdd;
import org.l2jmobius.gameserver.model.stats.functions.FuncMul;

/**
 * Owns: point math (always DERIVED from level, never stored - see note on getEarnedPoints), the allocated-node cache per (character, class_index), DB persistence, and applyAll() which is the single choke point that keeps granted skills, getter-backed stats, and Func-backed stats all in sync with
 * what's actually allocated. Every mutation (allocate, reset, subclass switch, login) routes through applyAll() rather than patching skills or stats incrementally - a rebuild-from-source-of-truth is immune to the kind of state-desync bugs a partial patch can quietly introduce.
 */

public class PassiveTreeManager
{
	// key: "objectId#classIndex" -> allocated node ids for that character+subclass
	private final Map<String, Set<Integer>> _cache = new ConcurrentHashMap<>();
	
	private static final java.util.logging.Logger LOGGER = java.util.logging.Logger.getLogger(PassiveTreeManager.class.getName());
	
	public enum DeallocateResult
	{
		OK,
		NOT_ALLOCATED,
		IS_ORIGIN,
		WOULD_DISCONNECT,
		NOT_ENOUGH_ADENA,
		ITEM_ERROR
	}
	
	/** Lazily-built, cached full symmetric adjacency map. See neighborMap(). */
	private volatile Map<Integer, Set<Integer>> _neighborCache = null;
	
	/**
	 * objectId -> (skillId -> level) for skills the TREE added this session. applyAll() only ever removes what's recorded here, so class-owned skills (a dwarf's own Spoil, Crystallize, Create Item...) are never touched.
	 */
	private final Map<Integer, Map<Integer, Integer>> _treeGranted = new ConcurrentHashMap<>();
	
	/** Player-variable prefix used to remember each class index's level. */
	private static final String VAR_CLASS_LEVEL = "PT_CLASS_LEVEL_";
	
	/** Highest class index to consider (0 = base, 1-3 = subclasses). */
	private static final int MAX_CLASS_INDEX = 3;
	private static final int SUBCLASS_START_LEVEL = 40;
	
	// Identity tag used to own every Func this system adds via addStatFunc().
	// removeStatsOwner(this) strips ALL of them across every stat at once,
	// regardless of how many different Stat values are involved.
	private static final Object PASSIVE_TREE_FUNC_OWNER = new Object();
	
	/**
	 * Effect keys that ride the Calculator/Func system instead of a getter override, because the underlying Stat has no dedicated method on Creature (shield rate, reflect, crit rate/damage, evasion, accuracy, regen rates, drop/spoil/exp rate bonuses, move speed). Adding a new one of these later is
	 * a one-line addition here, not a new method.
	 * <p>
	 * Effect keys NOT in this map (STR/DEX/CON/INT/WIT/MEN, MAXHP/MP/CP, PATK_PCT/PDEF_PCT/MATK_PCT/MDEF_PCT, ATK_SPD_PCT/CAST_SPD_PCT, SHIELD_DEF_PCT) are read directly by getter overrides in Player.java instead - both mechanisms coexist and PassiveStatBonusCache.get() is the single source either
	 * one reads from.
	 */
	
	/** Stats that are a plain ADD onto their base value. */
	private static final Map<String, Stat> FUNC_ADD_EFFECTS = Map.ofEntries(Map.entry("SHIELD_RATE_PCT", Stat.SHIELD_RATE), // calcShldUse: straight % , init 0
		Map.entry("REFLECT_PCT", Stat.REFLECT_DAMAGE_PERCENT), // onHitTimer: straight %, init 0
		Map.entry("CRIT_RATE_ADD", Stat.CRITICAL_RATE), // calcCrit: /1000 scale
		Map.entry("MCRIT_RATE_ADD", Stat.MCRITICAL_RATE), // calcMCrit: /1000 scale
		Map.entry("EVASION_ADD", Stat.EVASION_RATE), // calcHitMiss: 1 pt = 2%
		Map.entry("ACCURACY_ADD", Stat.ACCURACY_COMBAT), // calcHitMiss: 1 pt = 2%
		Map.entry("MOVE_SPEED_ADD", Stat.MOVE_SPEED) // flat onto ~120 base
	);
	
	/**
	 * Stats that are MULTIPLIERS - the value is a percent, applied as (1 + pct/100). Adding to these instead of multiplying is what caused the 200 -> 12,000 crit damage blowout.
	 */
	private static final Map<String, Stat> FUNC_MUL_EFFECTS = Map.ofEntries(Map.entry("CRIT_DMG_PCT", Stat.CRITICAL_DAMAGE), // calcPhysDam: init 1, pure multiplier
		Map.entry("HP_REGEN_PCT", Stat.REGENERATE_HP_RATE), // calcHpRegen: init = base regen
		Map.entry("MP_REGEN_PCT", Stat.REGENERATE_MP_RATE), // calcMpRegen: init = base regen
		Map.entry("DROP_RATE_PCT", Stat.BONUS_DROP_RATE), Map.entry("SPOIL_RATE_PCT", Stat.BONUS_SPOIL_RATE), Map.entry("EXP_RATE_PCT", Stat.BONUS_EXP));
	
	private String key(Player player)
	{
		final int classIndex = PassiveTreeConfig.SEPARATE_SUBCLASS_POINTS ? player.getClassIndex() : 0;
		return player.getObjectId() + "#" + classIndex;
	}
	
	/**
	 * Points are computed from level every time they're needed, never stored as a counter. A stored "points remaining" value can drift from the true source of truth (level + allocated count); deriving it means that class of bug is structurally impossible here.
	 * @param player
	 * @return
	 */
	public int getEarnedPoints(Player player)
	{
		if (!PassiveTreeConfig.PASSIVE_TREE_ENABLED)
		{
			return 0;
		}
		
		// Always refresh the active class's recorded level first, so the
		// stored values the shared-mode sum relies on stay current.
		rememberCurrentClassLevel(player);
		
		final int floor = PassiveTreeConfig.PASSIVE_TREE_START_LEVEL - 1;
		
		if (PassiveTreeConfig.SEPARATE_SUBCLASS_POINTS)
		{
			final int raw = Math.max(0, player.getLevel() - floor);
			return Math.min(raw, PassiveTreeConfig.PASSIVE_TREE_MAX_POINTS);
		}
		
		int total = 0;
		for (int classIndex = 0; classIndex <= MAX_CLASS_INDEX; classIndex++)
		{
			final int level = (classIndex == player.getClassIndex()) ? player.getLevel() : player.getVariables().getInt(VAR_CLASS_LEVEL + classIndex, 0);
			
			// The base class earns from PASSIVE_TREE_START_LEVEL, but a subclass
			// is CREATED at level 40 - counting from the same floor would hand
			// out ~39 points per subclass for doing nothing.
			final int classFloor = (classIndex == 0) ? floor : Math.max(floor, SUBCLASS_START_LEVEL);
			
			total += Math.max(0, level - classFloor);
		}
		
		return Math.min(total, PassiveTreeConfig.PASSIVE_TREE_MAX_POINTS);
	}
	
	/**
	 * Records the active class index's current level. Called from getEarnedPoints(), which runs on every UI render and every allocate, so the figure a class contributes stays fresh without needing a dedicated level-up hook.
	 * @param player
	 */
	private void rememberCurrentClassLevel(Player player)
	{
		final String key = VAR_CLASS_LEVEL + player.getClassIndex();
		final int level = player.getLevel();
		if (player.getVariables().getInt(key, -1) != level)
		{
			player.getVariables().set(key, level);
		}
	}
	
	public int getSpentPoints(Player player)
	{
		int spent = 0;
		for (int nodeId : getAllocatedNodes(player))
		{
			final PassiveNode node = PassiveTreeData.getInstance().getNode(nodeId);
			if (node != null)
			{
				spent += node.getCost();
			}
		}
		return spent;
	}
	
	public int getAvailablePoints(Player player)
	{
		return getEarnedPoints(player) - getSpentPoints(player);
	}
	
	public Set<Integer> getAllocatedNodes(Player player)
	{
		return _cache.computeIfAbsent(key(player), k -> loadFromDb(player));
	}
	
	/**
	 * The sector this player's tree browser should open on by default.
	 * <p>
	 * If this class_index has never spent a point (a freshly-added subclass, or a base class that hasn't touched the tree yet), default to the MAIN (base) class's sector rather than this class_index's own class - so every subclass starts from the same "home" sector until the player actually chooses
	 * to specialize it differently.
	 * <p>
	 * Once any point is spent under this class_index, that choice is respected: the sector with the most points invested wins, so the browser reopens where the player actually left off.
	 * @param player
	 * @return
	 */
	public String getDefaultSector(Player player)
	{
		final Set<Integer> allocated = getAllocatedNodes(player);
		if (allocated.isEmpty())
		{
			return PassiveTreeArchetypes.sectorFor(player.getBaseClass());
		}
		
		final Map<String, Integer> countsBySector = new HashMap<>();
		for (int nodeId : allocated)
		{
			final PassiveNode node = PassiveTreeData.getInstance().getNode(nodeId);
			if (node != null)
			{
				countsBySector.merge(node.getSector(), 1, Integer::sum);
			}
		}
		
		return countsBySector.entrySet().stream().max(Map.Entry.comparingByValue()).map(Map.Entry::getKey).orElse(PassiveTreeArchetypes.sectorFor(player.getBaseClass()));
	}
	
	private Set<Integer> loadFromDb(Player player)
	{
		final Set<Integer> result = new HashSet<>();
		final int classIndex = PassiveTreeConfig.SEPARATE_SUBCLASS_POINTS ? player.getClassIndex() : 0;
		
		try (Connection con = DatabaseFactory.getConnection();
			PreparedStatement ps = con.prepareStatement("SELECT node_id FROM character_passive_tree WHERE char_id = ? AND class_index = ?"))
		{
			ps.setInt(1, player.getObjectId());
			ps.setInt(2, classIndex);
			try (ResultSet rs = ps.executeQuery())
			{
				while (rs.next())
				{
					result.add(rs.getInt("node_id"));
				}
			}
		}
		catch (Exception e)
		{
			e.printStackTrace();
		}
		return result;
	}
	
	public boolean canAllocate(Player player, int nodeId)
	{
		final PassiveNode node = PassiveTreeData.getInstance().getNode(nodeId);
		if (node == null)
		{
			return false;
		}
		
		final Set<Integer> allocated = getAllocatedNodes(player);
		if (allocated.contains(nodeId))
		{
			return false; // already have it
		}
		
		if (getAvailablePoints(player) < node.getCost())
		{
			return false; // not enough points
		}
		
		// A root (START) node is always allocatable; anything else needs at
		// least one already-allocated parent to connect through.
		return node.isRoot() || node.getParents().stream().anyMatch(allocated::contains);
	}
	
	public boolean allocate(Player player, int nodeId)
	{
		if (!canAllocate(player, nodeId))
		{
			return false;
		}
		
		getAllocatedNodes(player).add(nodeId);
		persistInsert(player, nodeId);
		applyAll(player);
		return true;
	}
	
	private void persistInsert(Player player, int nodeId)
	{
		final int classIndex = PassiveTreeConfig.SEPARATE_SUBCLASS_POINTS ? player.getClassIndex() : 0;
		try (Connection con = DatabaseFactory.getConnection();
			PreparedStatement ps = con.prepareStatement("INSERT INTO character_passive_tree (char_id, class_index, node_id) VALUES (?, ?, ?)"))
		{
			ps.setInt(1, player.getObjectId());
			ps.setInt(2, classIndex);
			ps.setInt(3, nodeId);
			ps.execute();
		}
		catch (Exception e)
		{
			e.printStackTrace();
		}
	}
	
	/**
	 * Full rebuild for this player's CURRENT class_index:
	 * <ol>
	 * <li>Strips every skill any tree node could have granted, then regrants exactly what the current allocated set says.</li>
	 * <li>Rebuilds the aggregated stat bonus cache from scratch (read by the getter overrides in Player.java: STR/DEX/CON/INT/WIT/MEN, MAXHP/MP/CP, PATK/PDEF/MATK/MDEF %, attack/cast speed %, shield def %).</li>
	 * <li>Strips and reapplies every Func-backed stat (shield rate, reflect, crit rate/damage, evasion, accuracy, regen rates, drop/spoil/exp rate, move speed) in one pass via {@link #syncPassiveTreeStatFuncs}.</li>
	 * </ol>
	 * Called after allocate(), after resetTree(), on login, and on subclass switch - never call addSkill/removeSkill/addStatFunc for tree nodes anywhere else, or two code paths can disagree about current state.
	 * @param player
	 */
	public void applyAll(Player player)
	{
		final Map<Integer, Integer> granted = _treeGranted.computeIfAbsent(player.getObjectId(), k -> new ConcurrentHashMap<>());
		
		// 1) Remove ONLY what the tree added - and only if it's still our copy.
		// The level check matters: if the character has since gained their own
		// version of the skill, the levels won't match and we leave it alone.
		for (Map.Entry<Integer, Integer> entry : granted.entrySet())
		{
			final Skill known = player.getKnownSkill(entry.getKey());
			if ((known != null) && (known.getLevel() == entry.getValue()))
			{
				player.removeSkill(known, false, true);
			}
		}
		granted.clear();
		
		// 2) Grant allocated skills the character does NOT already have.
		for (int nodeId : getAllocatedNodes(player))
		{
			final PassiveNode node = PassiveTreeData.getInstance().getNode(nodeId);
			if ((node == null) || !node.grantsSkill())
			{
				continue;
			}
			
			// Already owned - by the class, or by another node granting the same
			// skill. Either way, keep what's there and don't record it as ours.
			if (player.getKnownSkill(node.getSkillId()) != null)
			{
				continue;
			}
			
			final Skill skill = SkillData.getInstance().getSkill(node.getSkillId(), node.getSkillLevel());
			if (skill != null)
			{
				player.addSkill(skill, false);
				granted.put(skill.getId(), skill.getLevel());
			}
		}
		
		// 3) Stat bonuses (getter-backed + Func-backed), rebuilt from scratch.
		player.getPassiveStatBonus().recompute(player);
		syncPassiveTreeStatFuncs(player);
		
		// 4) Tell the client. Skill window first, then stats/HP bars.
		player.sendSkillList();
		player.broadcastUserInfo();
	}
	
	/**
	 * Strips every Func this system previously added (across ALL FUNC_BACKED_EFFECTS stats at once, via the shared owner tag), then reapplies exactly the current totals. Same rebuild-whole principle as applyAll() itself - never add/remove one of these incrementally.
	 * @param player
	 */
	private void syncPassiveTreeStatFuncs(Player player)
	{
		player.removeStatsOwner(PASSIVE_TREE_FUNC_OWNER);
		
		for (Map.Entry<String, Stat> entry : FUNC_ADD_EFFECTS.entrySet())
		{
			final double bonus = player.getPassiveStatBonus().get(entry.getKey());
			if (bonus != 0)
			{
				player.addStatFunc(new FuncAdd(entry.getValue(), 0x30, PASSIVE_TREE_FUNC_OWNER, bonus, null));
			}
		}
		
		for (Map.Entry<String, Stat> entry : FUNC_MUL_EFFECTS.entrySet())
		{
			final double pct = player.getPassiveStatBonus().get(entry.getKey());
			if (pct != 0)
			{
				player.addStatFunc(new FuncMul(entry.getValue(), 0x30, PASSIVE_TREE_FUNC_OWNER, 1.0 + (pct / 100.0), null));
			}
		}
	}
	
	/**
	 * Call on player login and immediately after a subclass switch completes.
	 * @param player
	 */
	public void onClassContextChanged(Player player)
	{
		_treeGranted.remove(player.getObjectId());
		applyAll(player);
	}
	
	/**
	 * Clears every allocated node for the player's current class_index in exchange for the configured item cost, then reapplies (which strips the now-empty set's skills/stats). Points aren't "refunded" as a separate step because they were never stored - they're immediately available again since
	 * they're derived from level minus spent.
	 * @param player
	 * @return
	 */
	public boolean resetTree(Player player)
	{
		if (player.getInventory().getInventoryItemCount(PassiveTreeConfig.RESET_ITEM_ID, -1) < PassiveTreeConfig.RESET_ITEM_COUNT)
		{
			player.sendMessage("You don't have enough materials to reset your passive tree.");
			return false;
		}
		
		if (!player.destroyItemByItemId(ItemProcessType.DESTROY, PassiveTreeConfig.RESET_ITEM_ID, PassiveTreeConfig.RESET_ITEM_COUNT, player, true))
		{
			return false;
		}
		
		getAllocatedNodes(player).clear();
		
		final int classIndex = PassiveTreeConfig.SEPARATE_SUBCLASS_POINTS ? player.getClassIndex() : 0;
		try (Connection con = DatabaseFactory.getConnection();
			PreparedStatement ps = con.prepareStatement("DELETE FROM character_passive_tree WHERE char_id = ? AND class_index = ?"))
		{
			ps.setInt(1, player.getObjectId());
			ps.setInt(2, classIndex);
			ps.execute();
		}
		catch (Exception e)
		{
			e.printStackTrace();
		}
		
		applyAll(player);
		player.sendMessage("Your passive tree has been reset.");
		return true;
	}
	
	public static PassiveTreeManager getInstance()
	{
		return SingletonHolder.INSTANCE;
	}
	
	private static class SingletonHolder
	{
		protected static final PassiveTreeManager INSTANCE = new PassiveTreeManager();
	}
	
	/**
	 * Full symmetric adjacency for every node in the tree, built once and cached for the life of the server.
	 * <p>
	 * START nodes deliberately store an EMPTY parents list (see PassiveNode's javadoc from earlier in this build - it's what makes them the sole valid entry points). So a node's true neighbours can't be read off its own getParents() alone; they have to be reconstructed from both directions:
	 * everything in its own parent list, PLUS every node that lists it as a parent.
	 * @return
	 */
	private Map<Integer, Set<Integer>> neighborMap()
	{
		Map<Integer, Set<Integer>> cache = _neighborCache;
		if (cache != null)
		{
			return cache;
		}
		
		synchronized (this)
		{
			if (_neighborCache != null)
			{
				return _neighborCache;
			}
			
			final Map<Integer, Set<Integer>> map = new HashMap<>();
			for (PassiveNode node : PassiveTreeData.getInstance().getAllNodes().values())
			{
				map.computeIfAbsent(node.getId(), k -> new HashSet<>());
				for (int parentId : node.getParents())
				{
					map.computeIfAbsent(parentId, k -> new HashSet<>()).add(node.getId());
					map.computeIfAbsent(node.getId(), k -> new HashSet<>()).add(parentId);
				}
			}
			
			_neighborCache = map;
			return map;
		}
	}
	
	/**
	 * @param allocatedIds a candidate allocation set (e.g. the current allocation minus one node being considered for refund)
	 * @return {@code true} if every node in {@code allocatedIds} is still reachable from SOME allocated START node, walking only through other nodes that are also in {@code allocatedIds}.
	 *         <p>
	 *         This is the real respec rule for a mesh tree (not a strict tree): a node can only be refunded if no OTHER allocated node relies on it to stay connected to its origin. An interior node with a second allocated path around it is safe to remove; one that's the only bridge to a whole
	 *         allocated branch is not.
	 */
	private boolean remainsConnected(Set<Integer> allocatedIds)
	{
		if (allocatedIds.isEmpty())
		{
			return true;
		}
		
		final Map<Integer, Set<Integer>> neighbors = neighborMap();
		final Set<Integer> seen = new HashSet<>();
		final Deque<Integer> queue = new ArrayDeque<>();
		
		for (int id : allocatedIds)
		{
			final PassiveNode node = PassiveTreeData.getInstance().getNode(id);
			if ((node != null) && (node.getType() == PassiveNode.NodeType.START))
			{
				seen.add(id);
				queue.add(id);
			}
		}
		
		while (!queue.isEmpty())
		{
			final int current = queue.poll();
			for (int next : neighbors.getOrDefault(current, Collections.emptySet()))
			{
				if (allocatedIds.contains(next) && seen.add(next))
				{
					queue.add(next);
				}
			}
		}
		
		return seen.containsAll(allocatedIds);
	}
	
	/**
	 * Refunds ONE allocated node for Adena, provided doing so would not disconnect any other allocated node from its START (see remainsConnected()). Charges PassiveTreeConfig.RESPEC_ADENA_PER_POINT * node.getCost() Adena - intentionally separate from whatever your full-tree reset charges.
	 * @param player
	 * @param nodeId
	 * @return
	 */
	public DeallocateResult deallocateNode(Player player, int nodeId)
	{
		final PassiveNode node = PassiveTreeData.getInstance().getNode(nodeId);
		if (node == null)
		{
			return DeallocateResult.NOT_ALLOCATED;
		}
		
		if (node.getType() == PassiveNode.NodeType.START)
		{
			return DeallocateResult.IS_ORIGIN;
		}
		
		final Set<Integer> allocated = new HashSet<>(getAllocatedNodes(player));
		if (!allocated.contains(nodeId))
		{
			return DeallocateResult.NOT_ALLOCATED;
		}
		
		allocated.remove(nodeId);
		if (!remainsConnected(allocated))
		{
			return DeallocateResult.WOULD_DISCONNECT;
		}
		
		final long cost = PassiveTreeConfig.RESPEC_ADENA_PER_POINT * node.getCost();
		if (cost > 0)
		{
			if (!player.destroyItemByItemId(ItemProcessType.FEE, PassiveTreeConfig.RESET_ITEM_ID, cost, player, true))
			{
				return DeallocateResult.NOT_ENOUGH_ADENA;
			}
		}
		
		getAllocatedNodes(player).remove(nodeId);
		persistDelete(player, nodeId);

		// Rebuild stats and skills from the new allocation set - this reuses
		// whatever applyAll() already does (including the _treeGranted
		// skill-ownership tracking, if that patch is already in this file).
		applyAll(player);
		
		return DeallocateResult.OK;
	}
	
	/**
	 * ============================================================================ PERSISTENCE - VERIFY BEFORE USING ============================================================================ This method's job is simple: delete ONE row from whatever table your allocate() path writes a row INTO
	 * when a node is allocated. The table name and column names below (character_passive_tree / object_id / class_index / node_id) are my best inference of a typical layout for this system - they were never confirmed against your actual schema in this conversation. Before using this as-is: look at
	 * whichever method allocate() calls to INSERT a row (it's somewhere in this class, likely named something like persistAllocate() or saveNode()). If one exists, DELETE THIS METHOD and write persistDelete() as its mirror image instead - same table, same column names, same class_index logic - so
	 * both directions of the allocation can never drift apart. Only fall back to the raw SQL below if no such method exists yet. ============================================================================ public boolean allocate(Player player, int nodeId) { if (!canAllocate(player, nodeId)) {
	 * return false; } getAllocatedNodes(player).add(nodeId); persistInsert(player, nodeId); applyAll(player); return true; } private void persistInsert(Player player, int nodeId) { final int classIndex = PassiveTreeConfig.SEPARATE_SUBCLASS_POINTS ? player.getClassIndex() : 0; try (Connection con =
	 * DatabaseFactory.getConnection(); PreparedStatement ps = con.prepareStatement("INSERT INTO character_passive_tree (char_id, class_index, node_id) VALUES (?, ?, ?)")) { ps.setInt(1, player.getObjectId()); ps.setInt(2, classIndex); ps.setInt(3, nodeId); ps.execute(); } catch (Exception e) {
	 * e.printStackTrace(); } }
	 * @param player
	 * @param nodeId
	 */
	private void persistDelete(Player player, int nodeId)
	{
		final int classIndex = PassiveTreeConfig.SEPARATE_SUBCLASS_POINTS ? player.getClassIndex() : 0;
		
		try (Connection con = DatabaseFactory.getConnection();
			PreparedStatement ps = con.prepareStatement("DELETE FROM character_passive_tree WHERE char_id = ? AND class_index = ? AND node_id = ?"))
		{
			ps.setInt(1, player.getObjectId());
			ps.setInt(2, classIndex);
			ps.setInt(3, nodeId);
			ps.executeUpdate();
		}
		catch (SQLException e)
		{
			LOGGER.warning(getClass().getSimpleName() + ": Failed to delete passive node " + nodeId + " for player " + player.getObjectId() + " - " + e.getMessage());
		}
	}
}