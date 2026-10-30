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
package org.l2jmobius.gameserver.ai;

import org.l2jmobius.gameserver.model.actor.holders.npc.FakePlayerPvpProfile;

/**
 * Which AI a fake player of a party-vs-party simulation runs (see {@code FakeSimulationManager}), so a change to the AI can be tested against the current one before it replaces it. See docs/fake-player-simulation-issues.md.
 * <ul>
 * <li>{@link #A}: the current AI, which every roaming fake player runs: a party brain shared by the party ({@link FakePartyBrain}, held by {@code FakePlayerParty}) and a player brain for each member ({@link FakePlayerPvpAI}).</li>
 * <li>{@link #B}: the experimental AI, where changes go first, with the same two tiers: {@link FakePartyBrainExperimental} (held by {@code FakePlayerPartyExperimental}) and {@link FakePlayerPvpAIExperimental}.</li>
 * </ul>
 */
public enum FakeBrain
{
	A,
	B;

	/**
	 * @param text a brain letter (a or b, any case)
	 * @return that brain, {@code null} if the text names none
	 */
	public static FakeBrain parse(String text)
	{
		if (text != null)
		{
			for (FakeBrain brain : values())
			{
				if (brain.name().equalsIgnoreCase(text.trim()))
				{
					return brain;
				}
			}
		}
		return null;
	}

	/**
	 * @param profile the profile of a fake player, {@code null} for none
	 * @return the brain it runs ({@link #A} without a profile)
	 */
	public static FakeBrain of(FakePlayerPvpProfile profile)
	{
		return profile != null ? profile.getBrain() : A;
	}
}
