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

/**
 * One row from {@code class_transfer_tree}: a valid transfer from one PlayerClass ID to another
 * at a given tier, with an optional item requirement.
 * @author Zoey76
 */
public class ClassTransferHolder
{
	private final int fromClassId;
	private final int toClassId;
	private final int tier;
	private final Integer requiredItemId; // null = no item requirement
	private final long requiredItemCount;
	
	public ClassTransferHolder(int fromClassId, int toClassId, int tier, Integer requiredItemId, long requiredItemCount)
	{
		this.fromClassId = fromClassId;
		this.toClassId = toClassId;
		this.tier = tier;
		this.requiredItemId = requiredItemId;
		this.requiredItemCount = requiredItemCount;
	}
	
	public int getFromClassId()
	{
		return fromClassId;
	}
	
	public int getToClassId()
	{
		return toClassId;
	}
	
	public int getTier()
	{
		return tier;
	}
	
	public Integer getRequiredItemId()
	{
		return requiredItemId;
	}
	
	public long getRequiredItemCount()
	{
		return requiredItemCount;
	}
}
