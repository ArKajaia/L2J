package org.l2jmobius.gameserver.managers;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.l2jmobius.commons.database.DatabaseFactory;
import org.l2jmobius.gameserver.config.custom.FakePlayerPvpConfig;
import org.l2jmobius.gameserver.config.custom.PassiveTreeConfig;
import org.l2jmobius.gameserver.data.custom.PassiveTreeData;
import org.l2jmobius.gameserver.data.xml.ItemData;
import org.l2jmobius.gameserver.data.xml.SkillData;
import org.l2jmobius.gameserver.model.actor.Creature;
import org.l2jmobius.gameserver.model.actor.Npc;
import org.l2jmobius.gameserver.model.actor.Player;
import org.l2jmobius.gameserver.model.actor.holders.creature.TimeStamp;
import org.l2jmobius.gameserver.model.actor.holders.npc.FakePlayerPvpPassives;
import org.l2jmobius.gameserver.model.item.ItemTemplate;
import org.l2jmobius.gameserver.model.item.enums.ItemProcessType;
import org.l2jmobius.gameserver.model.itemcontainer.Inventory;
import org.l2jmobius.gameserver.model.passivetree.PassiveMechanics;
import org.l2jmobius.gameserver.model.passivetree.PassiveMechanics.ConditionalKey;
import org.l2jmobius.gameserver.model.passivetree.PassiveNode;
import org.l2jmobius.gameserver.model.passivetree.PassiveStatBonusCache;
import org.l2jmobius.gameserver.model.skill.PassiveTreeArchetypes;
import org.l2jmobius.gameserver.model.skill.Skill;
import org.l2jmobius.gameserver.model.stats.Stat;
import org.l2jmobius.gameserver.model.stats.functions.FuncAdd;
import org.l2jmobius.gameserver.model.stats.functions.FuncMul;
import org.l2jmobius.gameserver.model.zone.ZoneId;
import org.l2jmobius.gameserver.network.serverpackets.ExStorageMaxCount;
import org.l2jmobius.gameserver.network.serverpackets.SkillCoolTime;

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
	 * Effect keys NOT in this map (STR/DEX/CON/INT/WIT/MEN, PATK_PCT/PDEF_PCT/MATK_PCT/MDEF_PCT, ATK_SPD_PCT/CAST_SPD_PCT, SHIELD_DEF_PCT) are read directly by getter overrides in Player.java instead (MAXHP/MP/CP and the pool keystones by PlayerStat via PassiveMechanics, so HP/MP/CP clamping and regen see them too; the other KS_* keystone keys by the combat hooks that call PassiveMechanics) - both mechanisms coexist and PassiveStatBonusCache.get() is the single source either
	 * one reads from.
	 */
	
	/** Stats that are a plain ADD onto their base value. */
	private static final Map<String, Stat> FUNC_ADD_EFFECTS = Map.ofEntries(Map.entry("SHIELD_RATE_PCT", Stat.SHIELD_RATE), // calcShldUse: straight % , init 0
		Map.entry("REFLECT_PCT", Stat.REFLECT_DAMAGE_PERCENT), // onHitTimer: straight %, init 0
		Map.entry("CRIT_RATE_ADD", Stat.CRITICAL_RATE), // calcCrit: /1000 scale
		Map.entry("MCRIT_RATE_ADD", Stat.MCRITICAL_RATE), // calcMCrit: /1000 scale
		Map.entry("EVASION_ADD", Stat.EVASION_RATE), // calcHitMiss: 1 pt = 2%
		Map.entry("ACCURACY_ADD", Stat.ACCURACY_COMBAT), // calcHitMiss: 1 pt = 2%
		Map.entry("MOVE_SPEED_ADD", Stat.MOVE_SPEED), // flat onto ~120 base
		Map.entry("LIFESTEAL_PCT", Stat.ABSORB_DAMAGE_PERCENT), // onHitTimer: % of melee auto-attack damage healed (never bows), init 0
		Map.entry("MANA_LEECH_PCT", Stat.ABSORB_MANA_DAMAGE_PERCENT), // onHitTimer: same rules as lifesteal, restores MP
		Map.entry("SKILL_DODGE_PCT", Stat.P_SKILL_EVASION), // calcPhysicalSkillEvasion: straight % chance, init 0
		Map.entry("MAGIC_REFLECT_PCT", Stat.REFLECT_SKILL_MAGIC), // calcSkillReflect: straight % chance, init 0
		Map.entry("SKILL_REFLECT_PCT", Stat.REFLECT_SKILL_PHYSIC), // calcSkillReflect: straight % chance, init 0
		// PlayerStat.getBonus*Multiplier(): 1 + calcStat(stat, 0) / 100 - init 0, so these MUST be adds (a multiplier on 0 is a silent no-op).
		Map.entry("DROP_RATE_PCT", Stat.BONUS_DROP_RATE), Map.entry("SPOIL_RATE_PCT", Stat.BONUS_SPOIL_RATE), Map.entry("ADENA_RATE_PCT", Stat.BONUS_DROP_ADENA), Map.entry("EXP_RATE_PCT", Stat.BONUS_EXP), Map.entry("SP_RATE_PCT", Stat.BONUS_SP),
		// Player.getInventoryLimit(): base slots + calcStat(INV_LIM, 0).
		Map.entry("INVENTORY_SLOTS_ADD", Stat.INV_LIM),
		// PlayerStatus.reduceHp: % of damage taken redirected to a servitor within 1000 range (Soul Link), init 0
		Map.entry("SERVITOR_SHARE_PCT", Stat.TRANSFER_DAMAGE_PERCENT));

	/**
	 * Stats that are MULTIPLIERS - the value is a percent, applied as (1 + pct/100). Adding to these instead of multiplying is what caused the 200 -> 12,000 crit damage blowout.
	 */
	private static final Map<String, Stat> FUNC_MUL_EFFECTS = Map.ofEntries(Map.entry("CRIT_DMG_PCT", Stat.CRITICAL_DAMAGE), // calcPhysDam: init 1, pure multiplier
		Map.entry("HP_REGEN_PCT", Stat.REGENERATE_HP_RATE), // calcHpRegen: init = base regen
		Map.entry("MP_REGEN_PCT", Stat.REGENERATE_MP_RATE), // calcMpRegen: init = base regen
		Map.entry("PVE_PDMG_PCT", Stat.PVE_PHYSICAL_DMG), // calcPhysDam vs monsters (melee autos + physical skills), init 1
		Map.entry("PVE_MDMG_PCT", Stat.PVE_MAGICAL_DMG), // calcMagicDam vs monsters, init 1
		Map.entry("PVE_BOW_DMG_PCT", Stat.PVE_BOW_DMG), // calcPhysDam vs monsters, bow/crossbow auto-attacks, init 1
		Map.entry("PHYS_SKILL_POWER_PCT", Stat.PHYSICAL_SKILL_POWER), // calcPhysDam: init = skill damage
		Map.entry("MCRIT_DMG_PCT", Stat.MAGIC_CRIT_DMG), // calcMagicDam: init 1
		Map.entry("BLOW_RATE_PCT", Stat.BLOW_RATE), // calcBlowSuccess: init = base blow rate
		Map.entry("HEALING_RECEIVED_PCT", Stat.HEAL_EFFECT), // Heal effect: init = heal amount, read on the target
		Map.entry("WEIGHT_LIMIT_PCT", Stat.WEIGHT_LIMIT)); // Creature.getMaxLoad: init = CON-based base load

	/**
	 * Multipliers where LOWER is better (cooldowns, MP cost, damage taken, interrupt chance). The effect value is written as a positive "reduction" percent so the UIs colour it green, and applied as (1 - pct/100). A negative value (keystone drawback) therefore becomes a penalty multiplier above 1.
	 */
	private static final Map<String, Stat> FUNC_MUL_REDUCE_EFFECTS = Map.ofEntries(Map.entry("SKILL_CDR_PCT", Stat.P_REUSE), // Creature: physical skill reuse delay, init 1
		Map.entry("SPELL_CDR_PCT", Stat.MAGIC_REUSE_RATE), // Creature: magic skill reuse delay, init 1
		Map.entry("SPELL_MP_COST_RED_PCT", Stat.MAGICAL_MP_CONSUME_RATE), // CreatureStat.getMpConsume: init = MP cost
		Map.entry("CRIT_DMG_TAKEN_RED_PCT", Stat.DEFENCE_CRITICAL_DAMAGE), // calcPhysDam/calcBlowDamage: read on the target, init 1
		Map.entry("INTERRUPT_RES_PCT", Stat.ATTACK_CANCEL)); // calcAtkBreak: init = break chance

	/**
	 * Additive stats where LOWER is better, written as a positive "resistance" and applied as a negative add.
	 */
	private static final Map<String, Stat> FUNC_SUB_EFFECTS = Map.ofEntries(Map.entry("DEBUFF_RES_PCT", Stat.DEBUFF_VULN)); // calcEffectSuccess: 1 + calcStat(DEBUFF_VULN, 1) / 100
	
	/**
	 * Multipliers applied after every other tree Func (order 0x31 instead of 0x30), so they scale the whole total including the tree's own flat adds.
	 */
	private static final Map<String, Stat> FUNC_MUL_LATE_EFFECTS = Map.ofEntries(Map.entry("SHIELD_RATE_MUL_PCT", Stat.SHIELD_RATE)); // Deflection: calcShldUse block rate
	
	/**
	 * Getter-backed % keys (read by the Player getter overrides) and the Stat their conditional versions (PATK_PCT@BOW...) multiply instead, since a getter can't test a condition.
	 */
	private static final Map<String, Stat> CONDITIONAL_GETTER_EFFECTS = Map.ofEntries(Map.entry("PATK_PCT", Stat.POWER_ATTACK), Map.entry("PDEF_PCT", Stat.POWER_DEFENCE), Map.entry("MATK_PCT", Stat.MAGIC_ATTACK), Map.entry("MDEF_PCT", Stat.MAGIC_DEFENCE), Map.entry("ATK_SPD_PCT", Stat.POWER_ATTACK_SPEED), Map.entry("CAST_SPD_PCT", Stat.MAGIC_ATTACK_SPEED), Map.entry("SHIELD_DEF_PCT", Stat.SHIELD_DEFENCE));
	
	/**
	 * Base stat keys {@link Player} adds in its getSTR()..getMEN() overrides, as the Stat a roaming fake player gets them through instead (see {@link #applyToFakePlayer}).
	 */
	private static final Map<String, Stat> FAKE_BASE_STAT_EFFECTS = Map.ofEntries(Map.entry("STR", Stat.STAT_STR), Map.entry("DEX", Stat.STAT_DEX), Map.entry("CON", Stat.STAT_CON), Map.entry("INT", Stat.STAT_INT), Map.entry("WIT", Stat.STAT_WIT), Map.entry("MEN", Stat.STAT_MEN));
	
	/** Order of the Funcs that stand in for the Player getter overrides on a fake player: after every other Func (the highest order in use is 0x40). */
	private static final int FAKE_GETTER_ORDER = 0x50;
	
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
		
		// The tree layout can change between releases: drop saved nodes that no
		// longer exist or no longer connect to the allocated START. Their points
		// come back on their own, since points are derived from level.
		final Set<Integer> valid = connectedSubset(result);
		if (valid.size() < result.size())
		{
			result.removeAll(valid);
			try (Connection con = DatabaseFactory.getConnection();
				PreparedStatement ps = con.prepareStatement("DELETE FROM character_passive_tree WHERE char_id = ? AND class_index = ? AND node_id = ?"))
			{
				for (int nodeId : result)
				{
					ps.setInt(1, player.getObjectId());
					ps.setInt(2, classIndex);
					ps.setInt(3, nodeId);
					ps.addBatch();
				}
				ps.executeBatch();
			}
			catch (Exception e)
			{
				LOGGER.warning(getClass().getSimpleName() + ": Failed to prune passive nodes for player " + player.getObjectId() + " - " + e.getMessage());
			}
			LOGGER.info(getClass().getSimpleName() + ": Refunded " + result.size() + " passive node(s) of " + player.getName() + " that no longer connect to the tree.");
		}
		return valid;
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
		
		if (getConflictingNode(player, node) != null)
		{
			return false; // mutually exclusive keystone (Point Blank / Far Shot) already taken
		}
		
		// A character picks ONE starting point: a root (START) node is only
		// allocatable while no other one is owned, and the rest stay locked until
		// a full reset. Anything else needs at least one already-allocated parent
		// to connect through.
		if (node.isRoot())
		{
			return !hasStartNode(player);
		}
		return node.getParents().stream().anyMatch(allocated::contains);
	}

	/**
	 * @param player the player to check
	 * @param node a node the player wants to allocate
	 * @return an allocated node whose keystone can't be combined with {@code node}'s (see {@link PassiveMechanics#conflicts}), or {@code null}
	 */
	public PassiveNode getConflictingNode(Player player, PassiveNode node)
	{
		for (int allocatedId : getAllocatedNodes(player))
		{
			final PassiveNode allocated = PassiveTreeData.getInstance().getNode(allocatedId);
			if ((allocated != null) && PassiveMechanics.conflicts(node, allocated))
			{
				return allocated;
			}
		}
		return null;
	}
	
	/**
	 * @param player the player to check
	 * @return {@code true} if the player's current class_index has already allocated a starting (root) node, which locks every other one
	 */
	public boolean hasStartNode(Player player)
	{
		for (int allocatedId : getAllocatedNodes(player))
		{
			final PassiveNode allocatedNode = PassiveTreeData.getInstance().getNode(allocatedId);
			if ((allocatedNode != null) && allocatedNode.isRoot())
			{
				return true;
			}
		}
		return false;
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
	 * <li>Works out the skills the allocation grants (scaling skillLevel="auto" nodes to the character level), strips tree-granted skills that are no longer granted at that level, and grants what is missing.</li>
	 * <li>Rebuilds the aggregated stat bonus cache from scratch (read by the getter overrides in Player.java: STR/DEX/CON/INT/WIT/MEN, PATK/PDEF/MATK/MDEF %, attack/cast speed %, shield def %; by PlayerStat for MAXHP/MP/CP; and by PassiveMechanics for the keystones).</li>
	 * <li>Strips and reapplies every Func-backed stat (shield rate, reflect, crit rate/damage, evasion, accuracy, regen rates, drop/spoil/exp rate, move speed) in one pass via {@link #syncPassiveTreeStatFuncs}.</li>
	 * </ol>
	 * Called after allocate(), after resetTree(), on login, on subclass switch and (via onLevelChanged) on level change - never call addSkill/removeSkill/addStatFunc for tree nodes anywhere else, or two code paths can disagree about current state.
	 * @param player
	 */
	public void applyAll(Player player)
	{
		applyAll(player, true);
	}
	
	/**
	 * @param player
	 * @param announce tell the player which skills the tree just granted or took away (off for the login / subclass rebuild, where every skill would be "new")
	 */
	private void applyAll(Player player, boolean announce)
	{
		final Map<Integer, Integer> granted = _treeGranted.computeIfAbsent(player.getObjectId(), k -> new ConcurrentHashMap<>());
		
		// 1) Work out what the allocation grants now: skill id -> level. A node
		// with skillLevel="auto" gets the highest level of its skill whose magic
		// level the character has reached, like a retail skill learned on level up.
		final Map<Integer, Integer> wanted = new HashMap<>();
		for (int nodeId : getAllocatedNodes(player))
		{
			final PassiveNode node = PassiveTreeData.getInstance().getNode(nodeId);
			if ((node == null) || !node.grantsSkill())
			{
				continue;
			}
			
			wanted.merge(node.getSkillId(), getNodeSkillLevel(player, node), Math::max);
		}
		
		// 2) Remove ONLY what the tree added and no longer grants at that level -
		// and only if it's still our copy. The level check matters: if the
		// character has since gained their own version of the skill, the levels
		// won't match and we leave it alone. A skill that stays as it is is not
		// touched, so allocating some other node doesn't cancel its running buff.
		final Map<Integer, TimeStamp> cooldowns = new HashMap<>();
		for (Map.Entry<Integer, Integer> entry : granted.entrySet())
		{
			if (entry.getValue().equals(wanted.get(entry.getKey())))
			{
				continue;
			}
			
			final Skill known = player.getKnownSkill(entry.getKey());
			if ((known != null) && (known.getLevel() == entry.getValue()))
			{
				// Reuse is keyed by skill level: remember it, so moving to the
				// next level of the same skill doesn't reset its cooldown.
				final TimeStamp reuse = player.getSkillReuseTimeStamp(known.getReuseHashCode());
				if ((reuse != null) && reuse.hasNotPassed())
				{
					cooldowns.put(known.getId(), reuse);
				}
				player.removeSkill(known, false, true);
				if (announce && !wanted.containsKey(known.getId()))
				{
					player.sendMessage("Passive tree: you lost " + known.getName() + ".");
				}
			}
		}
		granted.keySet().retainAll(wanted.keySet());
		granted.entrySet().removeIf(entry -> !entry.getValue().equals(wanted.get(entry.getKey())));
		
		// 3) Grant what the character does NOT already have.
		for (Map.Entry<Integer, Integer> entry : wanted.entrySet())
		{
			// Already ours at this level, or owned by the class. Keep what's there
			// and don't record a class-owned skill as ours.
			if (player.getKnownSkill(entry.getKey()) != null)
			{
				continue;
			}
			
			final Skill skill = SkillData.getInstance().getSkill(entry.getKey(), entry.getValue());
			if (skill == null)
			{
				continue;
			}
			
			player.addSkill(skill, false);
			granted.put(skill.getId(), skill.getLevel());
			if (announce)
			{
				player.sendMessage("Passive tree: you learned " + skill.getName() + " (Lv " + skill.getLevel() + ").");
				LOGGER.info(getClass().getSimpleName() + ": " + player.getName() + " learned " + skill.getName() + " (" + skill.getId() + "/" + skill.getLevel() + ").");
			}
			
			final TimeStamp reuse = cooldowns.get(skill.getId());
			if ((reuse != null) && reuse.hasNotPassed())
			{
				player.addTimeStamp(skill, reuse.getReuse(), reuse.getStamp());
				player.disableSkill(skill, reuse.getRemaining());
			}
		}
		
		// 4) Stat bonuses (getter-backed + Func-backed), rebuilt from scratch.
		player.getPassiveStatBonus().recompute(player);
		syncPassiveTreeStatFuncs(player);
		
		// 5) Tell the client. Skill window first, then inventory size / weight (tree
		// nodes can change both), then stats/HP bars.
		player.sendSkillList();
		player.sendPacket(new ExStorageMaxCount(player));
		player.refreshOverloaded();
		player.broadcastUserInfo();
	}
	
	/**
	 * Strips every Func this system previously added (across ALL FUNC_BACKED_EFFECTS stats at once, via the shared owner tag), then reapplies exactly the current totals. Same rebuild-whole principle as applyAll() itself - never add/remove one of these incrementally.
	 * @param player
	 */
	private void syncPassiveTreeStatFuncs(Player player)
	{
		player.removeStatsOwner(PASSIVE_TREE_FUNC_OWNER);
		addTreeStatFuncs(player, player.getPassiveStatBonus());
	}
	
	/**
	 * Adds the Func-backed tree stats of {@code bonus} to {@code creature}, all owned by {@link #PASSIVE_TREE_FUNC_OWNER}. Shared by players and roaming fake players, so both read the effect keys the same way.
	 * @param creature the creature
	 * @param bonus its summed node effects
	 */
	private static void addTreeStatFuncs(Creature creature, PassiveStatBonusCache bonus)
	{
		for (Map.Entry<String, Stat> entry : FUNC_ADD_EFFECTS.entrySet())
		{
			final double value = bonus.get(entry.getKey());
			if (value != 0)
			{
				creature.addStatFunc(new FuncAdd(entry.getValue(), 0x30, PASSIVE_TREE_FUNC_OWNER, value, null));
			}
		}
		
		for (Map.Entry<String, Stat> entry : FUNC_MUL_EFFECTS.entrySet())
		{
			final double pct = bonus.get(entry.getKey());
			if (pct != 0)
			{
				creature.addStatFunc(new FuncMul(entry.getValue(), 0x30, PASSIVE_TREE_FUNC_OWNER, 1.0 + (pct / 100.0), null));
			}
		}

		for (Map.Entry<String, Stat> entry : FUNC_MUL_REDUCE_EFFECTS.entrySet())
		{
			final double pct = bonus.get(entry.getKey());
			if (pct != 0)
			{
				creature.addStatFunc(new FuncMul(entry.getValue(), 0x30, PASSIVE_TREE_FUNC_OWNER, 1.0 - (pct / 100.0), null));
			}
		}

		for (Map.Entry<String, Stat> entry : FUNC_SUB_EFFECTS.entrySet())
		{
			final double value = bonus.get(entry.getKey());
			if (value != 0)
			{
				creature.addStatFunc(new FuncAdd(entry.getValue(), 0x30, PASSIVE_TREE_FUNC_OWNER, -value, null));
			}
		}
		
		for (Map.Entry<String, Stat> entry : FUNC_MUL_LATE_EFFECTS.entrySet())
		{
			final double pct = bonus.get(entry.getKey());
			if (pct != 0)
			{
				creature.addStatFunc(new FuncMul(entry.getValue(), 0x31, PASSIVE_TREE_FUNC_OWNER, 1.0 + (pct / 100.0), null));
			}
		}
		
		// Conditional bonuses (PDEF_PCT@HEAVY, PATK_PCT@LOWHP...): the same Func as the plain key, gated by its condition.
		// Funcs test their condition on every stat calculation, so gear swaps, HP% and day/night apply live.
		for (String key : bonus.keys())
		{
			final ConditionalKey conditional = PassiveMechanics.parseConditional(key);
			final double value = bonus.get(key);
			if ((conditional == null) || (value == 0))
			{
				continue;
			}
			
			final String base = conditional.baseKey();
			if (CONDITIONAL_GETTER_EFFECTS.containsKey(base))
			{
				creature.addStatFunc(new FuncMul(CONDITIONAL_GETTER_EFFECTS.get(base), 0x30, PASSIVE_TREE_FUNC_OWNER, 1.0 + (value / 100.0), conditional.condition()));
			}
			else if (FUNC_ADD_EFFECTS.containsKey(base))
			{
				creature.addStatFunc(new FuncAdd(FUNC_ADD_EFFECTS.get(base), 0x30, PASSIVE_TREE_FUNC_OWNER, value, conditional.condition()));
			}
			else if (FUNC_MUL_EFFECTS.containsKey(base))
			{
				creature.addStatFunc(new FuncMul(FUNC_MUL_EFFECTS.get(base), 0x30, PASSIVE_TREE_FUNC_OWNER, 1.0 + (value / 100.0), conditional.condition()));
			}
			else if (FUNC_MUL_REDUCE_EFFECTS.containsKey(base))
			{
				creature.addStatFunc(new FuncMul(FUNC_MUL_REDUCE_EFFECTS.get(base), 0x30, PASSIVE_TREE_FUNC_OWNER, 1.0 - (value / 100.0), conditional.condition()));
			}
			else if (FUNC_SUB_EFFECTS.containsKey(base))
			{
				creature.addStatFunc(new FuncAdd(FUNC_SUB_EFFECTS.get(base), 0x30, PASSIVE_TREE_FUNC_OWNER, -value, conditional.condition()));
			}
		}
	}
	
	/**
	 * Gives a roaming fake player the stats of its passive tree (see {@link FakePlayerPvpPassiveTree}). Call on every spawn, since each life is a new body.
	 * <p>
	 * Besides the Func-backed keys a player has, the keys {@link Player} reads in its getter overrides (STR..MEN, the P. Atk./P. Def./M. Atk./M. Def./speed/shield % bonuses and max HP/MP) become Funcs too, ordered after every other one ({@link #FAKE_GETTER_ORDER}), so they
	 * apply on top of the whole stat the way those getters do.
	 * @param npc the fake player
	 * @param passives its passive tree, {@code null} for none
	 */
	public void applyToFakePlayer(Npc npc, FakePlayerPvpPassives passives)
	{
		npc.removeStatsOwner(PASSIVE_TREE_FUNC_OWNER);
		if (passives == null)
		{
			return;
		}
		
		final PassiveStatBonusCache bonus = passives.getBonus();
		addTreeStatFuncs(npc, bonus);
		
		for (Map.Entry<String, Stat> entry : FAKE_BASE_STAT_EFFECTS.entrySet())
		{
			final double value = bonus.get(entry.getKey());
			if (value != 0)
			{
				npc.addStatFunc(new FuncAdd(entry.getValue(), FAKE_GETTER_ORDER, PASSIVE_TREE_FUNC_OWNER, value, null));
			}
		}
		
		for (Map.Entry<String, Stat> entry : CONDITIONAL_GETTER_EFFECTS.entrySet())
		{
			final double pct = bonus.get(entry.getKey());
			if (pct != 0)
			{
				npc.addStatFunc(new FuncMul(entry.getValue(), FAKE_GETTER_ORDER, PASSIVE_TREE_FUNC_OWNER, 1.0 + (pct / 100.0), null));
			}
		}
		
		// Max HP: like PassiveMechanics.maxHp, (HP + flat) * (1 + %). The class CP folded into a fake player's HP takes the flat CP bonus, but not the HP %.
		final double maxHp = bonus.get("MAXHP") + (FakePlayerPvpConfig.INCLUDE_CP_IN_HP ? bonus.get("MAXCP") : 0);
		if (maxHp != 0)
		{
			npc.addStatFunc(new FuncAdd(Stat.MAX_HP, FAKE_GETTER_ORDER, PASSIVE_TREE_FUNC_OWNER, maxHp, null));
		}
		final double maxHpPct = bonus.get("MAXHP_PCT");
		if (maxHpPct != 0)
		{
			npc.addStatFunc(new FuncMul(Stat.MAX_HP, FAKE_GETTER_ORDER + 1, PASSIVE_TREE_FUNC_OWNER, 1.0 + ((maxHpPct / 100.0) * passives.getHpShare()), null));
		}
		final double maxMp = bonus.get("MAXMP");
		if (maxMp != 0)
		{
			npc.addStatFunc(new FuncAdd(Stat.MAX_MP, FAKE_GETTER_ORDER, PASSIVE_TREE_FUNC_OWNER, maxMp, null));
		}
	}
	
	/**
	 * Call on player login and immediately after a subclass switch completes.
	 * @param player
	 */
	public void onClassContextChanged(Player player)
	{
		_treeGranted.remove(player.getObjectId());
		applyAll(player, false);
		
		// The login and subclass paths send SkillCoolTime before the tree skills
		// exist, so the client would show them ready while they are on cooldown.
		player.sendPacket(new SkillCoolTime(player));
	}
	
	/**
	 * Call after the character's level changes. Moves skillLevel="auto" skills to the level that matches the new character level, if any of them changed.
	 * @param player
	 */
	public void onLevelChanged(Player player)
	{
		final Map<Integer, Integer> granted = _treeGranted.get(player.getObjectId());
		if (granted == null)
		{
			return; // Tree not applied yet (still logging in).
		}
		
		for (int nodeId : getAllocatedNodes(player))
		{
			final PassiveNode node = PassiveTreeData.getInstance().getNode(nodeId);
			if ((node != null) && node.grantsSkill() && node.isSkillLevelScaled())
			{
				final Integer current = granted.get(node.getSkillId());
				if ((current != null) && (current != getNodeSkillLevel(player, node)))
				{
					applyAll(player);
					return;
				}
			}
		}
	}
	
	/**
	 * @param player
	 * @param node a node that grants a skill
	 * @return the level of the node's skill this character gets: the fixed skillLevel, or for skillLevel="auto" the level matching the character level
	 */
	public static int getNodeSkillLevel(Player player, PassiveNode node)
	{
		return node.isSkillLevelScaled() ? getScaledSkillLevel(node.getSkillId(), player.getLevel()) : node.getSkillLevel();
	}
	
	/**
	 * @param skillId
	 * @param characterLevel
	 * @return the highest level of the skill whose magic level is at most {@code characterLevel}, and at least 1
	 */
	public static int getScaledSkillLevel(int skillId, int characterLevel)
	{
		int result = 1;
		final int maxLevel = SkillData.getInstance().getMaxLevel(skillId);
		for (int level = 2; level <= maxLevel; level++)
		{
			final Skill skill = SkillData.getInstance().getSkill(skillId, level);
			if ((skill != null) && (skill.getMagicLevel() <= characterLevel))
			{
				result = level;
			}
		}
		return result;
	}
	
	/**
	 * Clears every allocated node for the player's current class_index in exchange for the configured item cost, then reapplies (which strips the now-empty set's skills/stats). Points aren't "refunded" as a separate step because they were never stored - they're immediately available again since
	 * they're derived from level minus spent.
	 * @param player
	 * @return
	 */
	public boolean resetTree(Player player)
	{
		if (getAllocatedNodes(player).isEmpty())
		{
			player.sendMessage("Your passive tree has nothing to reset.");
			return false;
		}

		// A cost of 0 means a free reset - skip the item check entirely, since
		// destroying 0 of a non-Adena item is reported as a failure.
		if (PassiveTreeConfig.RESET_ITEM_COUNT > 0)
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
	
	// ------------------------------------------------------------------
	// Templates
	// ------------------------------------------------------------------
	// Every character has PassiveTreeConfig.TEMPLATE_COUNT templates, each its own allocation. The ACTIVE one is the plain allocation in
	// character_passive_tree that all the code above already works with, so allocate/respec/reset/applyAll never need to know templates exist.
	// The others are parked as snapshots in character_passive_tree_template, and switching swaps the two sets and rebuilds via applyAll().

	/** Player-variable prefix holding the active template id of each class index. */
	private static final String VAR_ACTIVE_TEMPLATE = "PT_TEMPLATE_ACTIVE_";

	/** Player variable holding when the player last switched template (epoch ms). Stored, so relogging does not skip the wait. */
	private static final String VAR_TEMPLATE_SWITCHED_AT = "PT_TEMPLATE_SWITCHED_AT";

	public enum SwitchResult
	{
		OK,
		DISABLED,
		INVALID,
		ALREADY_ACTIVE,
		NOT_PEACE_ZONE,
		COOLDOWN,
		NOT_ENOUGH_POINTS,
		ERROR
	}

	/**
	 * @param id 1-based template number
	 * @param active whether this is the template currently in use
	 * @param nodes allocated nodes in the template
	 * @param points points those nodes cost
	 */
	public record TemplateInfo(int id, boolean active, int nodes, int points)
	{
	}

	private int treeClassIndex(Player player)
	{
		return PassiveTreeConfig.SEPARATE_SUBCLASS_POINTS ? player.getClassIndex() : 0;
	}

	/**
	 * @param player
	 * @return the 1-based id of the template this player is using on the current class slot
	 */
	public int getActiveTemplate(Player player)
	{
		return Math.max(1, player.getVariables().getInt(VAR_ACTIVE_TEMPLATE + treeClassIndex(player), 1));
	}

	/**
	 * @param player
	 * @return milliseconds until this player may switch template again, 0 if they may now
	 */
	public long getTemplateCooldownRemaining(Player player)
	{
		final long lastSwitch = player.getVariables().getLong(VAR_TEMPLATE_SWITCHED_AT, 0);
		final long remaining = (lastSwitch + (PassiveTreeConfig.TEMPLATE_SWITCH_DELAY * 1000L)) - System.currentTimeMillis();
		// A stored time in the future (clock moved back) must not lock the player out for longer than the delay.
		return Math.max(0, Math.min(remaining, PassiveTreeConfig.TEMPLATE_SWITCH_DELAY * 1000L));
	}

	/**
	 * @param player
	 * @return {@code true} if the player stands where templates may be switched
	 */
	public boolean canSwitchTemplateHere(Player player)
	{
		return !PassiveTreeConfig.TEMPLATE_PEACE_ZONE_ONLY || player.isInsideZone(ZoneId.PEACE);
	}

	/**
	 * @param player
	 * @return every template of the player's current class slot, active one included
	 */
	public List<TemplateInfo> getTemplates(Player player)
	{
		final int active = getActiveTemplate(player);
		final Map<Integer, Set<Integer>> parked = new HashMap<>();
		try (Connection con = DatabaseFactory.getConnection();
			PreparedStatement ps = con.prepareStatement("SELECT template_id, node_id FROM character_passive_tree_template WHERE char_id = ? AND class_index = ?"))
		{
			ps.setInt(1, player.getObjectId());
			ps.setInt(2, treeClassIndex(player));
			try (ResultSet rs = ps.executeQuery())
			{
				while (rs.next())
				{
					parked.computeIfAbsent(rs.getInt("template_id"), k -> new HashSet<>()).add(rs.getInt("node_id"));
				}
			}
		}
		catch (SQLException e)
		{
			LOGGER.warning(getClass().getSimpleName() + ": Failed to load templates of player " + player.getObjectId() + " - " + e.getMessage());
		}

		final List<TemplateInfo> result = new ArrayList<>();
		for (int id = 1; id <= PassiveTreeConfig.TEMPLATE_COUNT; id++)
		{
			final Set<Integer> nodeIds = (id == active) ? getAllocatedNodes(player) : parked.getOrDefault(id, Collections.emptySet());
			int points = 0;
			for (int nodeId : nodeIds)
			{
				final PassiveNode node = PassiveTreeData.getInstance().getNode(nodeId);
				if (node != null)
				{
					points += node.getCost();
				}
			}
			result.add(new TemplateInfo(id, id == active, nodeIds.size(), points));
		}
		return result;
	}

	/**
	 * Makes {@code templateId} the active template. Only in a peace zone, and at most once per PassiveTreeConfig.TEMPLATE_SWITCH_DELAY seconds. The template being left is saved as it is, and the one being entered replaces the live allocation.
	 * @param player
	 * @param templateId 1-based
	 * @return what happened; anything but OK changed nothing
	 */
	public SwitchResult switchTemplate(Player player, int templateId)
	{
		if (!PassiveTreeConfig.PASSIVE_TREE_ENABLED)
		{
			return SwitchResult.DISABLED;
		}
		if ((templateId < 1) || (templateId > PassiveTreeConfig.TEMPLATE_COUNT))
		{
			return SwitchResult.INVALID;
		}

		final int from = getActiveTemplate(player);
		if (from == templateId)
		{
			return SwitchResult.ALREADY_ACTIVE;
		}
		if (!canSwitchTemplateHere(player))
		{
			return SwitchResult.NOT_PEACE_ZONE;
		}
		if (getTemplateCooldownRemaining(player) > 0)
		{
			return SwitchResult.COOLDOWN;
		}

		final int classIndex = treeClassIndex(player);
		final int charId = player.getObjectId();

		// Load the target. Drop what no longer connects (the layout can change between releases), like loadFromDb() does.
		final Set<Integer> target = new HashSet<>();
		try (Connection con = DatabaseFactory.getConnection();
			PreparedStatement ps = con.prepareStatement("SELECT node_id FROM character_passive_tree_template WHERE char_id = ? AND class_index = ? AND template_id = ?"))
		{
			ps.setInt(1, charId);
			ps.setInt(2, classIndex);
			ps.setInt(3, templateId);
			try (ResultSet rs = ps.executeQuery())
			{
				while (rs.next())
				{
					target.add(rs.getInt("node_id"));
				}
			}
		}
		catch (SQLException e)
		{
			LOGGER.warning(getClass().getSimpleName() + ": Failed to read template " + templateId + " of player " + charId + " - " + e.getMessage());
			return SwitchResult.ERROR;
		}

		final Set<Integer> valid = connectedSubset(target);
		int cost = 0;
		for (int nodeId : valid)
		{
			final PassiveNode node = PassiveTreeData.getInstance().getNode(nodeId);
			if (node != null)
			{
				cost += node.getCost();
			}
		}
		if (cost > getEarnedPoints(player))
		{
			return SwitchResult.NOT_ENOUGH_POINTS;
		}

		final Set<Integer> current = new HashSet<>(getAllocatedNodes(player));

		// One transaction: a crash half way must never leave the old template's nodes lost.
		try (Connection con = DatabaseFactory.getConnection())
		{
			con.setAutoCommit(false);
			try
			{
				// Park the template being left.
				replaceTemplateRows(con, charId, classIndex, from, current);
				// The entered template becomes the live allocation, so it must not also stay parked.
				replaceTemplateRows(con, charId, classIndex, templateId, Collections.emptySet());
				try (PreparedStatement ps = con.prepareStatement("DELETE FROM character_passive_tree WHERE char_id = ? AND class_index = ?"))
				{
					ps.setInt(1, charId);
					ps.setInt(2, classIndex);
					ps.execute();
				}
				try (PreparedStatement ps = con.prepareStatement("INSERT INTO character_passive_tree (char_id, class_index, node_id) VALUES (?, ?, ?)"))
				{
					for (int nodeId : valid)
					{
						ps.setInt(1, charId);
						ps.setInt(2, classIndex);
						ps.setInt(3, nodeId);
						ps.addBatch();
					}
					ps.executeBatch();
				}
				con.commit();
			}
			catch (SQLException e)
			{
				con.rollback();
				throw e;
			}
			finally
			{
				con.setAutoCommit(true);
			}
		}
		catch (SQLException e)
		{
			LOGGER.warning(getClass().getSimpleName() + ": Failed to switch template of player " + charId + " - " + e.getMessage());
			return SwitchResult.ERROR;
		}

		final Set<Integer> allocated = getAllocatedNodes(player);
		allocated.clear();
		allocated.addAll(valid);
		player.getVariables().set(VAR_ACTIVE_TEMPLATE + classIndex, templateId);
		player.getVariables().set(VAR_TEMPLATE_SWITCHED_AT, System.currentTimeMillis());

		applyAll(player);
		player.sendMessage("Passive tree template " + templateId + " is now active.");
		return SwitchResult.OK;
	}

	private void replaceTemplateRows(Connection con, int charId, int classIndex, int templateId, Set<Integer> nodeIds) throws SQLException
	{
		try (PreparedStatement ps = con.prepareStatement("DELETE FROM character_passive_tree_template WHERE char_id = ? AND class_index = ? AND template_id = ?"))
		{
			ps.setInt(1, charId);
			ps.setInt(2, classIndex);
			ps.setInt(3, templateId);
			ps.execute();
		}
		if (nodeIds.isEmpty())
		{
			return;
		}
		try (PreparedStatement ps = con.prepareStatement("INSERT INTO character_passive_tree_template (char_id, class_index, template_id, node_id) VALUES (?, ?, ?, ?)"))
		{
			for (int nodeId : nodeIds)
			{
				ps.setInt(1, charId);
				ps.setInt(2, classIndex);
				ps.setInt(3, templateId);
				ps.setInt(4, nodeId);
				ps.addBatch();
			}
			ps.executeBatch();
		}
	}

	/**
	 * @param player
	 * @param result a non-OK result of {@link #switchTemplate}
	 * @return the reason in words, as the player should read it
	 */
	public String getSwitchFailureMessage(Player player, SwitchResult result)
	{
		switch (result)
		{
			case DISABLED:
			{
				return "The passive tree is disabled.";
			}
			case INVALID:
			{
				return "There is no such template.";
			}
			case ALREADY_ACTIVE:
			{
				return "That template is already active.";
			}
			case NOT_PEACE_ZONE:
			{
				return "You can only switch passive tree templates in a peace zone.";
			}
			case COOLDOWN:
			{
				return "You can switch template again in " + ((getTemplateCooldownRemaining(player) + 999) / 1000) + " seconds.";
			}
			case NOT_ENOUGH_POINTS:
			{
				return "You don't have enough passive points for that template.";
			}
			default:
			{
				return "The template could not be switched. Try again.";
			}
		}
	}

	/**
	 * @return the full-reset price as players should read it, e.g. "100,000 Adena", or "Free" when the configured cost is 0. Shared by the Community Board button and the web planner so both always quote the same price.
	 */
	public String getResetCostText()
	{
		if (PassiveTreeConfig.RESET_ITEM_COUNT <= 0)
		{
			return "Free";
		}

		final ItemTemplate item = ItemData.getInstance().getTemplate(PassiveTreeConfig.RESET_ITEM_ID);
		return String.format("%,d", PassiveTreeConfig.RESET_ITEM_COUNT) + " " + (item != null ? item.getName() : "items");
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
		return connectedSubset(allocatedIds).containsAll(allocatedIds);
	}
	
	/**
	 * @param allocatedIds a candidate allocation set
	 * @return the nodes of {@code allocatedIds} that are reachable from an allocated START node, walking only through other nodes of {@code allocatedIds}. Ids that no longer exist in the tree data are never part of the result.
	 */
	private Set<Integer> connectedSubset(Set<Integer> allocatedIds)
	{
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
		
		return seen;
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
			if (!player.destroyItemByItemId(ItemProcessType.FEE, Inventory.ADENA_ID, cost, player, true))
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
	 * Mirror of persistInsert(): deletes one allocated node row.
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