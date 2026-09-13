/*
 * Copyright (c) 2013 L2jMobius
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be
 * included in all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY,
 * WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR
 * IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
 */
package org.l2jmobius.gameserver.config.custom;

/**
 * Configuration for the class transfer NPCs (900001/900002/900003 - 1st/2nd/3rd class), gated by
 * level only per design, with the class tree itself defined in the {@code class_transfer_tree} table.
 * @author Zoey76
 */
public class ClassTransferConfig
{
	/** Master on/off switch. */
	public static boolean CLASS_TRANSFER_ENABLED = true;
	
	public static int CLASS_MASTER_TIER1_NPC_ID = 900001;
	public static int CLASS_MASTER_TIER2_NPC_ID = 900002;
	public static int CLASS_MASTER_TIER3_NPC_ID = 900003;
	
	/** Minimum character level required at each NPC. TODO: adjust to your intended progression. */
	public static int TIER1_MIN_LEVEL = 20;
	public static int TIER2_MIN_LEVEL = 40;
	public static int TIER3_MIN_LEVEL = 76;
	
	public static void load()
	{
		// TODO: read these from a .properties file the same way your other *Config classes do.
	}
}
