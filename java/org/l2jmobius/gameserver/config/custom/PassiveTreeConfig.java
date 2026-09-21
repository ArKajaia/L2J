package org.l2jmobius.gameserver.config.custom;

/**
 * Configuration for the Path-of-Exile-style passive tree system. Values here are meant to be loaded from config/custom/PassiveTree.ini the same way your other custom.* config classes work; hardcoded defaults are given so the system is usable immediately.
 */
public class PassiveTreeConfig
{
	public static boolean PASSIVE_TREE_ENABLED = true;
	
	/** First level at which a point becomes available. */
	public static int PASSIVE_TREE_START_LEVEL = 2;
	
	/** Hard cap on points per class_index, matching (85 - 50 + 1) = 36 by default. */
	public static int PASSIVE_TREE_MAX_POINTS = 120;
	
	/**
	 * If true, each subclass (class_index 0-3) keeps its own independent allocation on the same node graph. If false, all class indexes share one pooled allocation (not recommended - changes the "1/5th of the tree per 3 subclasses" design goal into "1/5th total, shared").
	 */
	public static boolean SEPARATE_SUBCLASS_POINTS = false;
	
	public static int RESET_ITEM_ID = 57; // Adena
	public static long RESET_ITEM_COUNT = 1000000;
}
