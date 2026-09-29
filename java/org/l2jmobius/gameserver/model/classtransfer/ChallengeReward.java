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
 * One reward line of a challenge, granted once when the challenge is cleared.
 * @author Mobius
 */
public class ChallengeReward
{
	public enum RewardType
	{
		/** A plain item: {@code id} and {@code count}. */
		ITEM,
		/** Survival Arena currency ({@code RatesConfig.ARENA_CURRENCY_ITEM_ID}); {@code parBonus} more when cleared within the par time. */
		ARENA_COINS,
		/** One guaranteed Sealed Cache, rolled like a Mage monster's cache. */
		SEALED_CACHE,
		EXP,
		SP
	}

	private final RewardType _type;
	private final int _itemId;
	private final long _count;
	private final long _parBonus;

	public ChallengeReward(RewardType type, int itemId, long count, long parBonus)
	{
		_type = type;
		_itemId = itemId;
		_count = Math.max(0, count);
		_parBonus = Math.max(0, parBonus);
	}

	public RewardType getType()
	{
		return _type;
	}

	public int getItemId()
	{
		return _itemId;
	}

	public long getCount()
	{
		return _count;
	}

	/**
	 * @return extra amount granted when the challenge is cleared within its par time
	 */
	public long getParBonus()
	{
		return _parBonus;
	}
}
