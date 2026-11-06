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
package org.l2jmobius.gameserver.model.mapdevice;

import java.sql.Connection;
import java.sql.Statement;
import java.util.logging.Logger;

import org.l2jmobius.commons.database.DatabaseFactory;

/**
 * The item ids of the Map Device moved: they were 50101-50119, which the High Five client does not take, and are 18101-18119, a free stretch inside the
 * retail range (the server's retail items stop at 22567 and have nothing from 17300 to 19999). This puts the items saved under the old ids under the new ones,
 * once, at startup, before anything loads an inventory or a warehouse. Tier N is still 18100 + N, and every other id keeps its place in the list.
 */
public final class MapItemIds
{
	private static final Logger LOGGER = Logger.getLogger(MapItemIds.class.getName());

	/** The first and last of the old ids. */
	public static final int OLD_FIRST = 50101;
	public static final int OLD_LAST = 50119;
	/** How far down they moved: 50101 is 18101 now. */
	public static final int SHIFT = 32000;

	/** The tables and columns that keep an item id. */
	private static final String[][] COLUMNS =
	{
		{
			"items",
			"item_id"
		},
		{
			"itemsonground",
			"item_id"
		},
		{
			"character_item_reuse_save",
			"itemId"
		},
		{
			"character_premium_items",
			"itemId"
		},
		{
			"character_offline_trade_items",
			"item"
		},
	};

	private MapItemIds()
	{
	}

	/**
	 * Puts every item saved under an old id under its new one. Does nothing once there are none left.
	 */
	public static void renumberStoredItems()
	{
		try (Connection con = DatabaseFactory.getConnection();
			Statement statement = con.createStatement())
		{
			for (String[] column : COLUMNS)
			{
				try
				{
					final int rows = statement.executeUpdate("UPDATE " + column[0] + " SET " + column[1] + "=" + column[1] + "-" + SHIFT + " WHERE " + column[1] + " BETWEEN " + OLD_FIRST + " AND " + OLD_LAST);
					if (rows > 0)
					{
						LOGGER.info(MapItemIds.class.getSimpleName() + ": " + rows + " row(s) of " + column[0] + " moved from the old Map Device item ids " + OLD_FIRST + "-" + OLD_LAST + " to " + (OLD_FIRST - SHIFT) + "-" + (OLD_LAST - SHIFT) + ".");
					}
				}
				catch (Exception e)
				{
					LOGGER.warning(MapItemIds.class.getSimpleName() + ": Could not renumber the Map Device items of " + column[0] + ": " + e.getMessage());
				}
			}
		}
		catch (Exception e)
		{
			LOGGER.warning(MapItemIds.class.getSimpleName() + ": Could not renumber the Map Device items: " + e.getMessage());
		}
	}
}
