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

import org.l2jmobius.gameserver.model.actor.Npc;

/**
 * What code outside the AI asks of a roaming fake player's AI, whichever brain it runs ({@link FakeBrain}): {@link FakePlayerPvpAI} or {@link FakePlayerPvpAIExperimental}.
 */
public interface FakePlayerPvpBrain
{
	/**
	 * @return {@code true} while it rests after a hard fight (it regenerates like a sitting player)
	 */
	boolean isResting();

	/**
	 * Keeps it standing with {@code partner} for a talk (see {@code FakePlayerPvpManager#converse}).
	 * @param partner the other fake player
	 * @param until when the talk ends at the latest
	 */
	void holdForTalk(Npc partner, long until);

	/**
	 * @param partner a fake player
	 * @return {@code true} if it is talking with {@code partner}
	 */
	boolean isTalkingWith(Npc partner);

	/**
	 * Ends a talk, if it is talking with someone, for both of them.
	 */
	void endTalk();

	/**
	 * At the end of a talk about the spot it hits the other fake player once, like a taunt.
	 * @param rival the other fake player
	 */
	void startRivalry(Npc rival);

	/**
	 * It joined a party.
	 */
	void onJoinParty();

	/**
	 * It left its party.
	 */
	void onLeaveParty();
}
