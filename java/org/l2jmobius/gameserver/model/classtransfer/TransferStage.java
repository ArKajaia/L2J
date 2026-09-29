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
package org.l2jmobius.gameserver.model.classtransfer;

/**
 * The three class transfer stages a challenge can unlock. The tier matches the Class Master NPC tier and the {@code tier} column of {@code class_transfer_tree}.
 * @author Mobius
 */
public enum TransferStage
{
	FIRST_TRANSFER(1, "First Class Transfer"),
	SECOND_TRANSFER(2, "Second Class Transfer"),
	THIRD_TRANSFER(3, "Third Class Transfer");

	private final int _tier;
	private final String _displayName;

	TransferStage(int tier, String displayName)
	{
		_tier = tier;
		_displayName = displayName;
	}

	public int getTier()
	{
		return _tier;
	}

	public String getDisplayName()
	{
		return _displayName;
	}

	/**
	 * @param tier the Class Master tier (1, 2 or 3)
	 * @return the matching stage, or {@code null} for any other value
	 */
	public static TransferStage fromTier(int tier)
	{
		for (TransferStage stage : values())
		{
			if (stage._tier == tier)
			{
				return stage;
			}
		}
		return null;
	}
}
