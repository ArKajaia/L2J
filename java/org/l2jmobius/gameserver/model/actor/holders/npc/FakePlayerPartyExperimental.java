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
package org.l2jmobius.gameserver.model.actor.holders.npc;

import org.l2jmobius.gameserver.ai.FakeBrain;
import org.l2jmobius.gameserver.ai.FakePartyBrainExperimental;

/**
 * The party of a simulation team that runs the experimental AI ({@link FakeBrain#B}, see {@link org.l2jmobius.gameserver.ai.FakePlayerPvpAIExperimental}). It holds the team's party brain ({@link FakePartyBrainExperimental}), shared by its members: the
 * party's general decisions (who it fights, following, marching, its calls, who guards whom) are made there, and each member's AI carries them out. Changes to how a party decides are made there first, and move to brain A once simulations show they win
 * more (see docs/fake-player-simulation-issues.md). Nothing is overridden here: the members, leader and call are kept as in {@link FakePlayerParty}.
 */
public class FakePlayerPartyExperimental extends FakePlayerParty
{
	private final FakePartyBrainExperimental _brain = new FakePartyBrainExperimental(this);

	public FakePlayerPartyExperimental()
	{
		super(null);
	}

	/**
	 * @return the party brain its members share (brain B's; {@link #getBrain} is brain A's)
	 */
	public FakePartyBrainExperimental getExperimentalBrain()
	{
		return _brain;
	}
}
