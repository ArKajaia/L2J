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
package org.l2jmobius.gameserver.network.clientpackets;

import org.l2jmobius.gameserver.network.serverpackets.ExBrLoadEventTopRankers;

/**
 * Halloween rank list client  Format: (ch)ddd
 */
public class BrEventRankerList extends ClientPacket
{
	private int _eventId;
	private int _day;
	@SuppressWarnings("unused")
	private int _ranking;
	
	@Override
	protected void readImpl()
	{
		_eventId = readInt();
		_day = readInt(); // 0 - current, 1 - previous
		_ranking = readInt();
	}
	
	@Override
	protected void runImpl()
	{
		// TODO count, bestScore, myScore
		final int count = 0;
		final int bestScore = 0;
		final int myScore = 0;
		getClient().sendPacket(new ExBrLoadEventTopRankers(_eventId, _day, count, bestScore, myScore));
	}
}
