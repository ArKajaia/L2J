package org.l2jmobius.gameserver.config.custom;

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
		RESET_ITEM_ID = config.getInt("PassiveTreeResetItemId", 57);
		RESET_ITEM_COUNT = Math.max(0, config.getLong("PassiveTreeResetItemCount", 100000));
		RESPEC_ADENA_PER_POINT = Math.max(0, config.getLong("PassiveTreeRefundAdenaPerPoint", 1000));
	}
}
