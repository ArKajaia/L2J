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
	public static int PASSIVE_TREE_MAX_POINTS = 120;

	/**
	 * Adena charged per POINT of a node's cost when respeccing ONE node at a time via the web planner. Separate from the full-tree reset cost.
	 */
	public static long RESPEC_ADENA_PER_POINT = 1000;

	/**
	 * If true, each subclass (class_index 0-3) keeps its own independent allocation on the same node graph. If false, all class indexes share one pooled allocation.
	 */
	public static boolean SEPARATE_SUBCLASS_POINTS = false;

	public static int RESET_ITEM_ID = 57; // Adena
	public static long RESET_ITEM_COUNT = 100000;

	public static void load()
	{
		final ConfigReader config = new ConfigReader(PASSIVE_TREE_CONFIG_FILE);
		PASSIVE_TREE_ENABLED = config.getBoolean("PassiveTreeEnabled", true);
		PASSIVE_TREE_START_LEVEL = Math.max(1, config.getInt("PassiveTreeStartLevel", 2));
		PASSIVE_TREE_MAX_POINTS = Math.max(0, config.getInt("PassiveTreeMaxPoints", 120));
		SEPARATE_SUBCLASS_POINTS = config.getBoolean("PassiveTreeSeparateSubclassPoints", false);
		RESET_ITEM_ID = config.getInt("PassiveTreeResetItemId", 57);
		RESET_ITEM_COUNT = Math.max(0, config.getLong("PassiveTreeResetItemCount", 100000));
		RESPEC_ADENA_PER_POINT = Math.max(0, config.getLong("PassiveTreeRefundAdenaPerPoint", 1000));
	}
}
