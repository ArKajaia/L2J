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
package org.l2jmobius.gameserver.data.sql;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;

import org.l2jmobius.commons.database.DatabaseFactory;

/**
 * Loads and caches the {@code class_transfer_tree} table.
 * @author Zoey76
 */
public class ClassTransferData
{
	private static final Logger LOGGER = Logger.getLogger(ClassTransferData.class.getName());
	
	private final List<ClassTransferHolder> _transfers = new ArrayList<>();
	
	protected ClassTransferData()
	{
		load();
	}
	
	public void load()
	{
		_transfers.clear();
		
		final String query = "SELECT from_class_id, to_class_id, tier, required_item_id, required_item_count FROM class_transfer_tree";
		try (Connection con = DatabaseFactory.getConnection();
			PreparedStatement ps = con.prepareStatement(query);
			ResultSet rs = ps.executeQuery())
		{
			while (rs.next())
			{
				final int requiredItemIdRaw = rs.getInt("required_item_id");
				final Integer requiredItemId = rs.wasNull() ? null : requiredItemIdRaw;
				
				_transfers.add(new ClassTransferHolder(rs.getInt("from_class_id"), rs.getInt("to_class_id"), rs.getInt("tier"), requiredItemId, rs.getLong("required_item_count")));
			}
		}
		catch (Exception e)
		{
			LOGGER.warning("ClassTransferData: Failed loading class_transfer_tree: " + e.getMessage());
		}
		
		LOGGER.info("ClassTransferData: Loaded " + _transfers.size() + " class transfer tree entries.");
	}
	
	/**
	 * @param fromClassId the player's current PlayerClass ID
	 * @param tier the tier being attempted (1, 2, or 3)
	 * @return every row matching both, i.e. every valid target class for this player at this NPC
	 */
	public List<ClassTransferHolder> getEligibleTransfers(int fromClassId, int tier)
	{
		final List<ClassTransferHolder> result = new ArrayList<>();
		for (ClassTransferHolder holder : _transfers)
		{
			if ((holder.getFromClassId() == fromClassId) && (holder.getTier() == tier))
			{
				result.add(holder);
			}
		}
		return result;
	}
	
	public static ClassTransferData getInstance()
	{
		return SingletonHolder.INSTANCE;
	}
	
	private static class SingletonHolder
	{
		protected static final ClassTransferData INSTANCE = new ClassTransferData();
	}
}
