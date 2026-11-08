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
package org.l2jmobius.gameserver.ai.fight;

/**
 * What the scorer needs to know about the fake player itself and its target, besides its fight read.
 * @param selfId the fake player
 * @param targetId its target
 * @param now the current time
 * @param myDps its damage per second with its best skills in reach
 * @param autoAttackDps its damage per second with normal attacks
 * @param selfHpRatio its HP, 0 to 1
 * @param targetHpRatio its target's HP, 0 to 1
 * @param meleeNear {@code true} if an enemy melee is on it or about to be
 * @param ranged {@code true} for an archer or mage
 * @param burstReady {@code true} if its burst (buffs and big hits) is ready
 * @param disabled {@code true} while it is stunned, slept or silenced
 * @param targetGroup the rough profile of its target's resistances
 * @param skillLevel its skill, 0 to 1
 */
public record ScoreContext(int selfId, int targetId, long now, double myDps, double autoAttackDps, double selfHpRatio, double targetHpRatio, boolean meleeNear, boolean ranged, boolean burstReady, boolean disabled, ClassGroup targetGroup, double skillLevel)
{
}
