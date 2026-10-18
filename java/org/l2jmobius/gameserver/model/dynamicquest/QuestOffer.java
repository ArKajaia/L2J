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
package org.l2jmobius.gameserver.model.dynamicquest;

import java.util.Collections;
import java.util.Map;

import org.l2jmobius.gameserver.model.actor.Npc;

/**
 * A quest on offer: on a town's Notice Board for the day (anyone can take it, once per account), or brought to one player by a herald or as a follow-up.
 * @author Mobius
 */
public class QuestOffer
{
	private final String _key;
	private final QuestTemplate _template;
	private final String _town;
	private final long _seed;
	private final String _requester;
	private final QuestTier _tier;
	private final int _chain;
	private final Map<String, String> _carry;
	private Npc _herald;
	private long _expires;
	private boolean _arrived;
	private boolean _accepted;

	public QuestOffer(String key, QuestTemplate template, String town, long seed, String requester, QuestTier tier, int chain, Map<String, String> carry)
	{
		_key = key;
		_template = template;
		_town = town;
		_seed = seed;
		_requester = requester;
		_tier = tier;
		_chain = chain;
		_carry = carry != null ? carry : Collections.emptyMap();
	}

	public String getKey()
	{
		return _key;
	}

	public QuestTemplate getTemplate()
	{
		return _template;
	}

	/**
	 * @return the town whose board it is on, {@code null} for a herald's offer
	 */
	public String getTown()
	{
		return _town;
	}

	public long getSeed()
	{
		return _seed;
	}

	/**
	 * @return who asks ("Pinter, Blacksmith"), {@code null} if nobody in particular
	 */
	public String getRequester()
	{
		return _requester;
	}

	/**
	 * @return the tier it is offered at, {@code null} if the player chooses
	 */
	public QuestTier getTier()
	{
		return _tier;
	}

	public int getChain()
	{
		return _chain;
	}

	/**
	 * @return context carried over from the quest it follows
	 */
	public Map<String, String> getCarry()
	{
		return _carry;
	}

	public boolean isBoardOffer()
	{
		return _town != null;
	}

	/**
	 * @return {@code true} for a town npc's request, taken only at its town's Notice Board
	 */
	public boolean isRequest()
	{
		return _requester != null;
	}

	public Npc getHerald()
	{
		return _herald;
	}

	public void setHerald(Npc herald)
	{
		_herald = herald;
	}

	public long getExpires()
	{
		return _expires;
	}

	public void setExpires(long expires)
	{
		_expires = expires;
	}

	public boolean isArrived()
	{
		return _arrived;
	}

	public void setArrived(boolean arrived)
	{
		_arrived = arrived;
	}

	/**
	 * @return {@code true} once taken (a personal offer that goes away unaccepted is turned down for the day)
	 */
	public boolean isAccepted()
	{
		return _accepted;
	}

	public void setAccepted(boolean accepted)
	{
		_accepted = accepted;
	}
}
