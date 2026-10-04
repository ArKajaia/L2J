package org.l2jmobius.gameserver.config.custom;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import org.l2jmobius.commons.util.ConfigReader;

/**
 * Configuration for the Path-of-Exile-style passive tree system, loaded from config/Custom/PassiveTree.ini. The defaults below are the values the tree ran on before the file was loaded, so a missing key changes nothing.
 */
public class PassiveTreeConfig
{
	// File
	private static final String PASSIVE_TREE_CONFIG_FILE = "./config/Custom/PassiveTree.ini";

	public static boolean PASSIVE_TREE_ENABLED = true;

	/** First level at which a point becomes available. */
	public static int PASSIVE_TREE_START_LEVEL = 2;

	/** Hard cap on the points a character can earn. */
	public static int PASSIVE_TREE_MAX_POINTS = 150;

	/**
	 * Adena charged per POINT of a node's cost when respeccing ONE node at a time via the web planner. Separate from the full-tree reset cost.
	 */
	public static long RESPEC_ADENA_PER_POINT = 1000;

	/**
	 * If true, each subclass (class_index 0-3) keeps its own independent allocation on the same node graph. If false, all class indexes share one pooled allocation.
	 */
	public static boolean SEPARATE_SUBCLASS_POINTS = false;

	/** How many passive tree templates each player has (slot 1 is the one every character starts on). */
	public static int TEMPLATE_COUNT = 5;

	/** Templates can only be switched while standing in a peace zone. */
	public static boolean TEMPLATE_PEACE_ZONE_ONLY = true;

	/** Seconds a player must wait after switching template before switching again. */
	public static int TEMPLATE_SWITCH_DELAY = 60;

	/** Most the tree may add to each base stat (STR, DEX, CON, INT, WIT, MEN). Negative = no cap. */
	public static int BASE_STAT_CAP = 5;

	/** Prefix of the per-effect cap keys in the ini, e.g. {@code PassiveTreeCap.CRIT_DMG_PCT = 60}. */
	private static final String STAT_CAP_PREFIX = "PassiveTreeCap.";
	
	/** Caps a missing {@code PassiveTreeCap.*} key falls back to, in the order the ini lists them. */
	private static final Map<String, Double> DEFAULT_STAT_CAPS = new LinkedHashMap<>();
	static
	{
		DEFAULT_STAT_CAPS.put("CRIT_DMG_PCT", 60.0);
		DEFAULT_STAT_CAPS.put("CRIT_RATE_ADD", 150.0);
		DEFAULT_STAT_CAPS.put("ACCURACY_ADD", 12.0);
		DEFAULT_STAT_CAPS.put("EVASION_ADD", 12.0);
		DEFAULT_STAT_CAPS.put("SHIELD_RATE_PCT", 25.0);
		DEFAULT_STAT_CAPS.put("REFLECT_PCT", 30.0);
		DEFAULT_STAT_CAPS.put("LIFESTEAL_PCT", 8.0);
		DEFAULT_STAT_CAPS.put("MANA_LEECH_PCT", 6.0);
		DEFAULT_STAT_CAPS.put("SKILL_DODGE_PCT", 12.0);
		DEFAULT_STAT_CAPS.put("MAGIC_REFLECT_PCT", 10.0);
		DEFAULT_STAT_CAPS.put("SKILL_REFLECT_PCT", 10.0);
		DEFAULT_STAT_CAPS.put("PVE_PDMG_PCT", 25.0);
		DEFAULT_STAT_CAPS.put("PVE_MDMG_PCT", 25.0);
		DEFAULT_STAT_CAPS.put("PVE_BOW_DMG_PCT", 25.0);
		DEFAULT_STAT_CAPS.put("PHYS_SKILL_POWER_PCT", 20.0);
		DEFAULT_STAT_CAPS.put("MCRIT_DMG_PCT", 40.0);
		DEFAULT_STAT_CAPS.put("BLOW_RATE_PCT", 20.0);
		DEFAULT_STAT_CAPS.put("HEALING_RECEIVED_PCT", 40.0);
		DEFAULT_STAT_CAPS.put("SKILL_CDR_PCT", 20.0);
		DEFAULT_STAT_CAPS.put("SPELL_CDR_PCT", 20.0);
		DEFAULT_STAT_CAPS.put("SPELL_MP_COST_RED_PCT", 30.0);
		DEFAULT_STAT_CAPS.put("CRIT_DMG_TAKEN_RED_PCT", 30.0);
		DEFAULT_STAT_CAPS.put("INTERRUPT_RES_PCT", 50.0);
		DEFAULT_STAT_CAPS.put("DEBUFF_RES_PCT", 30.0);
		DEFAULT_STAT_CAPS.put("SERVITOR_SHARE_PCT", 50.0);
		DEFAULT_STAT_CAPS.put("SHIELD_RATE_MUL_PCT", 50.0);
		DEFAULT_STAT_CAPS.put("MAXHP_PCT", 60.0);
		DEFAULT_STAT_CAPS.put("INVENTORY_SLOTS_ADD", 40.0);
		DEFAULT_STAT_CAPS.put("WEIGHT_LIMIT_PCT", 100.0);
		DEFAULT_STAT_CAPS.put("EXP_RATE_PCT", 25.0);
		DEFAULT_STAT_CAPS.put("SP_RATE_PCT", 25.0);
		DEFAULT_STAT_CAPS.put("FIRE_ATK", 150.0);
		DEFAULT_STAT_CAPS.put("WATER_ATK", 150.0);
		DEFAULT_STAT_CAPS.put("WIND_ATK", 150.0);
		DEFAULT_STAT_CAPS.put("EARTH_ATK", 150.0);
		DEFAULT_STAT_CAPS.put("HOLY_ATK", 150.0);
		DEFAULT_STAT_CAPS.put("DARK_ATK", 150.0);
		DEFAULT_STAT_CAPS.put("FIRE_RES", 150.0);
		DEFAULT_STAT_CAPS.put("WATER_RES", 150.0);
		DEFAULT_STAT_CAPS.put("WIND_RES", 150.0);
		DEFAULT_STAT_CAPS.put("EARTH_RES", 150.0);
		DEFAULT_STAT_CAPS.put("HOLY_RES", 150.0);
		DEFAULT_STAT_CAPS.put("DARK_RES", 150.0);
		DEFAULT_STAT_CAPS.put("ALL_ELEM_RES", 60.0);
		DEFAULT_STAT_CAPS.put("SUMMON_PATK_PCT", 40.0);
		DEFAULT_STAT_CAPS.put("SUMMON_MATK_PCT", 40.0);
		DEFAULT_STAT_CAPS.put("SUMMON_PDEF_PCT", 40.0);
		DEFAULT_STAT_CAPS.put("SUMMON_MDEF_PCT", 40.0);
		DEFAULT_STAT_CAPS.put("SUMMON_HP_PCT", 40.0);
		DEFAULT_STAT_CAPS.put("SUMMON_ATK_SPD_PCT", 40.0);
		DEFAULT_STAT_CAPS.put("SUMMON_CAST_SPD_PCT", 40.0);
	}
	
	/**
	 * Most the tree may add to each non-base-stat effect key. A key that is absent is uncapped. Replaced as a whole on reload, never modified in place.
	 */
	public static Map<String, Double> STAT_CAPS = Collections.unmodifiableMap(new LinkedHashMap<>(DEFAULT_STAT_CAPS));
	
	public static int RESET_ITEM_ID = 57; // Adena
	public static long RESET_ITEM_COUNT = 100000;

	public static void load()
	{
		final ConfigReader config = new ConfigReader(PASSIVE_TREE_CONFIG_FILE);
		PASSIVE_TREE_ENABLED = config.getBoolean("PassiveTreeEnabled", true);
		PASSIVE_TREE_START_LEVEL = Math.max(1, config.getInt("PassiveTreeStartLevel", 2));
		PASSIVE_TREE_MAX_POINTS = Math.max(0, config.getInt("PassiveTreeMaxPoints", 150));
		SEPARATE_SUBCLASS_POINTS = config.getBoolean("PassiveTreeSeparateSubclassPoints", false);
		TEMPLATE_COUNT = Math.max(1, Math.min(9, config.getInt("PassiveTreeTemplateCount", 5)));
		TEMPLATE_PEACE_ZONE_ONLY = config.getBoolean("PassiveTreeTemplatePeaceZoneOnly", true);
		TEMPLATE_SWITCH_DELAY = Math.max(0, config.getInt("PassiveTreeTemplateSwitchDelay", 60));
		BASE_STAT_CAP = config.getInt("PassiveTreeBaseStatCap", 5);
		RESET_ITEM_ID = config.getInt("PassiveTreeResetItemId", 57);
		RESET_ITEM_COUNT = Math.max(0, config.getLong("PassiveTreeResetItemCount", 100000));
		RESPEC_ADENA_PER_POINT = Math.max(0, config.getLong("PassiveTreeRefundAdenaPerPoint", 1000));
		
		// Every default cap can be changed, and any other effect key can be capped by adding its own PassiveTreeCap.<KEY> line. Negative = no cap.
		final Map<String, Double> caps = new LinkedHashMap<>();
		for (Map.Entry<String, Double> entry : DEFAULT_STAT_CAPS.entrySet())
		{
			caps.put(entry.getKey(), config.getDouble(STAT_CAP_PREFIX + entry.getKey(), entry.getValue()));
		}
		for (String name : config.getStringPropertyNames())
		{
			if (name.startsWith(STAT_CAP_PREFIX) && (name.length() > STAT_CAP_PREFIX.length()))
			{
				final String key = name.substring(STAT_CAP_PREFIX.length());
				caps.putIfAbsent(key, config.getDouble(name, -1));
			}
		}
		caps.values().removeIf(cap -> cap < 0);
		STAT_CAPS = Collections.unmodifiableMap(caps);
	}
}
