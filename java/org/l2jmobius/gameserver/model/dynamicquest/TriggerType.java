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

/**
 * What offers a dynamic quest.
 * @author Mobius
 */
public enum TriggerType
{
	/** Rolled onto every town's Notice Board (and the Community Board) each day. */
	BOARD_DAILY,
	/** A request of a town npc (blacksmith, merchant, townsfolk), on that town's Notice Board each day. */
	NPC_INTERACTION,
	/** The world-event director rolls it every DynamicQuestEventInterval seconds ({@code chance}) for a player hunting in the open world; a herald brings it. */
	TIMER,
	/** A world counter passes a threshold ({@code counter}, {@code threshold}): kills in a hunting ground within DynamicQuestAreaKillWindow seconds. */
	WORLD_STATE,
	/** Something the player does ({@code action} = KILL, LEVEL_UP, ENTER_ZONE) with a {@code chance}; a herald brings it. */
	PLAYER_ACTION
}
