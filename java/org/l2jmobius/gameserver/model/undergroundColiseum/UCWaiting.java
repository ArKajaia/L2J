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
package org.l2jmobius.gameserver.model.undergroundColiseum;

import org.l2jmobius.gameserver.model.groups.Party;

public class UCWaiting
{
	private final Party _party;
	private long _registerMillis;
	private final UCArena _baseArena;
	
	public UCWaiting(Party party, UCArena baseArena)
	{
		_party = party;
		_baseArena = baseArena;
	}
	
	public void clean()
	{
		_registerMillis = 0L;
	}
	
	public UCArena getBaseArena()
	{
		return _baseArena;
	}
	
	public Party getParty()
	{
		if ((_party != null) && (_party.getLeader() == null))
		{
			setParty(false);
		}
		
		return _party;
	}
	
	public void setParty(boolean isActive)
	{
		if (isActive)
		{
			_party.setUCState(this);
		}
		else
		{
			_party.setUCState(null);
		}
	}
	
	public void hasRegisterdNow()
	{
		_registerMillis = System.currentTimeMillis();
	}
	
	public long getRegisterMillis()
	{
		return _registerMillis;
	}
}
