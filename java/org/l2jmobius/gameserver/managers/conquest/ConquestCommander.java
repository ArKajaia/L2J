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
package org.l2jmobius.gameserver.managers.conquest;

/**
 * The mind of a Conquest team as a whole: every {@link org.l2jmobius.gameserver.config.custom.ConquestConfig#COMMANDER_INTERVAL} seconds it looks at the match and gives each of its team's parties an order ({@link ConquestParty#setOrder}): a capture point
 * to take or hold, or an enemy group to go for. The parties carry the orders out on their own: they fight any enemy they meet on the way (the party and fake player AIs), and their leaders march where they were sent.<br>
 * {@link GenericConquestCommander} is the plain one; another (a learned one) takes its place by implementing this.
 */
public interface ConquestCommander
{
	/**
	 * @return a short name, for the GM page
	 */
	String getName();

	/**
	 * Gives the team's parties their orders.
	 * @param match the match (its capture points, both teams, its ways: {@link ConquestMatch#getNav})
	 * @param team the team to command
	 * @param now the time
	 */
	void command(ConquestMatch match, ConquestTeam team, long now);
}
