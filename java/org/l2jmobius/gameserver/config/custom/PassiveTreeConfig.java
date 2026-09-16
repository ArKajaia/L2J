/*
 * This file is part of the L2J Mobius project.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
package org.l2jmobius.gameserver.config.custom;

import org.l2jmobius.commons.util.ConfigReader;

/** Configuration for the class-based passive tree. */
public final class PassiveTreeConfig
{
    private static final String CONFIG_FILE = "./config/Custom/PassiveTree.ini";

    public static boolean ENABLED;
    public static int START_LEVEL;
    public static int POINTS_PER_LEVEL;
    public static int MAX_POINTS_PER_CLASS;
    public static int MAX_CLASS_INDEX;
    public static boolean ALLOW_RESET;
    public static int RESET_ITEM_ID;
    public static long RESET_ITEM_COUNT;

    private PassiveTreeConfig()
    {
    }

    public static void load()
    {
        final ConfigReader config = new ConfigReader(CONFIG_FILE);
        ENABLED = config.getBoolean("PassiveTreeEnabled", true);
        START_LEVEL = Math.max(1, config.getInt("PassiveTreeStartLevel", 50));
        POINTS_PER_LEVEL = Math.max(1, config.getInt("PassiveTreePointsPerLevel", 1));
        MAX_POINTS_PER_CLASS = Math.max(1, config.getInt("PassiveTreeMaxPointsPerClass", 36));
        MAX_CLASS_INDEX = Math.max(0, config.getInt("PassiveTreeMaxClassIndex", 2));
        ALLOW_RESET = config.getBoolean("PassiveTreeAllowReset", true);
        RESET_ITEM_ID = config.getInt("PassiveTreeResetItemId", 57);
        RESET_ITEM_COUNT = Math.max(0, config.getLong("PassiveTreeResetItemCount", 0));
    }
}
