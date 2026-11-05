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
package org.l2jmobius.gameserver.model.mapdevice;

/**
 * One tier of map (data/MapDevice.xml): the item that holds it, the monsters its map is filled with, how much stronger they are than in the open world, and which monsters drop it.
 * @param tier the tier, 1 for the lowest
 * @param itemId the "Map (Tier N)" item
 * @param minLevel the lowest level of the monsters of its map
 * @param maxLevel the highest level of the monsters of its map
 * @param dropLevel the lowest level of an open world monster that drops this tier (with no difficulty bonus)
 * @param hp multiplier on the HP of its monsters
 * @param attack multiplier on their P. Atk. and M. Atk.
 * @param defence multiplier on their P. Def. and M. Def.
 * @param reward multiplier on the experience, SP and drop chance they give
 */
public record MapTier(int tier, int itemId, int minLevel, int maxLevel, int dropLevel, double hp, double attack, double defence, double reward)
{
}
